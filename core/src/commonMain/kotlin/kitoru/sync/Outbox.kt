package kitoru.sync

import kitoru.domain.KitoruJson
import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kitoru.platform.KitoruPaths
import kitoru.platform.nowMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.random.Random

/**
 * Qué clase de operación es una entrada de la cola.
 *
 * La [priority] ordena las operaciones que compiten por el mismo instante: la
 * cabecera de un hilo (0) sale antes que sus mensajes (1), así el hilo llega
 * al servidor con su título y sus etiquetas.
 */
@Serializable
enum class OutboxOperation(val label: String, val priority: Int) {

    /** Alta o edición de un hilo: la cabecera de la conversación. */
    UPSERT_THREAD("Subir hilo", 0),

    /** Mensajes nuevos de un hilo (append-only). */
    APPEND_MESSAGES("Subir mensajes", 1),

    /** Subida manual de chats hechos con IA local (lleva consentimiento). */
    SHARE_LOCAL_CHATS("Compartir chats locales", 1),

    /** Declaración del alumno: visibilidad, modo de IA y etiqueta. */
    SET_VISIBILITY("Cambiar la visibilidad", 1),

    /** Envío de una entrega. */
    SUBMIT_ASSIGNMENT("Enviar entrega", 1),

    /** Marca un aviso como leído. */
    MARK_ANNOUNCEMENT_READ("Marcar aviso leído", 2),

    /** Publicación de un aviso (docente). */
    PUBLISH_ANNOUNCEMENT("Publicar aviso", 2),

    /** Subida de la configuración de la cátedra (docente). */
    SYNC_COURSE("Subir la configuración", 2),

    /** Subida de un material (docente). */
    SYNC_MATERIAL("Subir material", 2),

    /** Auditoría de accesos (docente). */
    APPEND_ACCESS_LOG("Subir la auditoría", 2),
    ;

    /** ¿Es la cabecera de un hilo? */
    val isThread: Boolean get() = this == UPSERT_THREAD

    /** ¿Es un mensaje de un hilo? */
    val isMessage: Boolean get() = this == APPEND_MESSAGES
}

/**
 * Estado de una entrada de la cola.
 */
@Serializable
enum class OutboxState(val label: String) {

    /** Espera su turno. */
    PENDING("Pendiente"),

    /** No se pudo enviar: agotó los intentos o el servidor la rechazó. */
    FAILED("No se pudo enviar"),
}

/**
 * Una operación pendiente de enviar.
 *
 * El [id] lo genera el cliente y es único en el dispositivo. El
 * [idempotencyKey] viaja al servidor como cabecera `Idempotency-Key`: no
 * cambia entre reintentos, así que un reintento tras un timeout en el que el
 * servidor **sí** guardó la operación no la duplica.
 */
@Serializable
data class OutboxEntry(
    /** Id local de la operación. */
    val id: String,
    /** Qué hay que hacer. */
    val operation: OutboxOperation,
    /** El cuerpo de la operación, en JSON. */
    val payload: String,
    /** Cuándo se encoló (epoch ms). */
    val createdAt: Long,
    /** Intentos ya realizados. */
    val attempts: Int = 0,
    /** Cuándo se puede volver a intentar (epoch ms). */
    val nextAttemptAt: Long = 0L,
    /** Último error, en español, listo para mostrar. */
    val lastError: String = "",
    /** Clave de idempotencia: viaja al servidor como `Idempotency-Key`. */
    val idempotencyKey: String = "",
    /** Cátedra a la que pertenece la operación. */
    val courseId: String = "",
    /** Hilo afectado: sirve para agrupar y para ordenar las entradas. */
    val threadId: String = "",
    /** Estado de la entrada. */
    val state: OutboxState = OutboxState.PENDING,
    /** Cuándo se intentó por última vez (epoch ms). */
    val lastAttemptAt: Long = 0L,
)

/**
 * El resultado de intentar enviar una entrada.
 */
sealed interface OutboxSendResult {

    /** Se envió: la entrada sale de la cola. */
    data object Success : OutboxSendResult

    /** Falló por una causa transitoria (red, 5xx, 429): se reintenta con backoff. */
    data class Retryable(val message: String) : OutboxSendResult

    /** Falló por una causa permanente (4xx): no tiene sentido reintentar. */
    data class Permanent(val message: String) : OutboxSendResult
}

/**
 * El resultado de vaciar la cola.
 *
 * @property sent cuántas entradas se enviaron.
 * @property deadLetters las entradas que quedaron markedas como fallidas.
 * @property remaining cuántas entradas siguen en la cola.
 */
data class FlushReport(
    val sent: Int = 0,
    val deadLetters: List<OutboxEntry> = emptyList(),
    val remaining: Int = 0,
)

/**
 * Backoff exponencial con jitter.
 *
 * `espera(n) = min(base * 2^(n-1), tope) * jitter`, con el jitter en
 * `[1 - razon, 1 + razon]`.
 *
 * El jitter existe para que 40 alumnos no le peguen al servidor en el mismo
 * milisegundo cuando vuelve la red. Es una **función pura del intento y del
 * generador**, así se puede testear sin reloj y sin `Random` global.
 *
 * @property baseDelayMs la espera del primer reintento.
 * @property maxDelayMs el tope de la espera.
 * @property jitterRatio cuánto puede desviarse la espera (0.2 = ±20 %).
 * @property maxAttempts cuántos intentos se hacen antes de dar la entrada por
 *   fallida.
 */
data class Backoff(
    val baseDelayMs: Long = 5_000,
    val maxDelayMs: Long = 300_000,
    val jitterRatio: Double = 0.2,
    val maxAttempts: Int = 10,
) {

    /**
     * La espera antes del intento [attempt] (1 = el primero).
     *
     * @param attempt el número de intento (1 es el primero).
     * @param jitter el desvío, en [0, 1]: 0.5 es el valor nominal.
     */
    fun delayFor(attempt: Int, jitter: Double = 0.5): Long {
        require(attempt >= 1) { "El número de intento arranca en 1 (recibido: $attempt)." }
        val shift = (attempt - 1).coerceAtMost(30)
        val exponential = if (shift >= 30) maxDelayMs else baseDelayMs shl shift
        val capped = exponential.coerceAtMost(maxDelayMs)
        val factor = 1.0 + (jitter * 2 - 1) * jitterRatio
        return (capped * factor).toLong().coerceAtLeast(1_000)
    }
}

/**
 * La cola de salida: operaciones que la app quiso hacer contra el servidor y
 * no pudo.
 *
 * Es **persistente**: vive en `config/outbox.json` y sobrevive al cierre de la
 * app. No es un `try/catch` con reintento: es una cola con estado, porque el
 * reintento tiene que sobrevivir al proceso.
 *
 * El orden de envío es FIFO por `createdAt`, con una regla dura: **la
 * cabecera de un hilo va antes que sus mensajes**, sin importar la marca de
 * tiempo (el hilo se encola al terminar la conversación, o sea, después que
 * los mensajes).
 */
class Outbox(
    private val fs: FileSystem,
    private val path: FilePath = KitoruPaths.configDir.resolve("outbox.json"),
    private val backoff: Backoff = Backoff(),
    private val now: () -> Long = { nowMillis() },
    private val random: () -> Double = { Random.nextDouble() },
    private val idGenerator: () -> String = { newOperationId() },
) {

    /** Serializa las escrituras de la cola. */
    private val mutex = Mutex()

    /** Serializa los vaciados: dos subidas a la vez pelearían por el orden. */
    private val flushMutex = Mutex()

    /**
     * La cola, en orden de envío.
     *
     * Si el archivo no existe o está corrupto, la cola vacía: un archivo de
     * cola roto no puede voltear la app.
     */
    suspend fun entries(): List<OutboxEntry> = mutex.withLock { readLocked() }

    /** Escribe la cola completa en disco (de forma atómica). */
    suspend fun save(entries: List<OutboxEntry>) = mutex.withLock { writeLocked(entries) }

    /**
     * Encola una operación.
     *
     * @param operation qué hay que hacer.
     * @param payload el cuerpo de la operación, en JSON.
     * @param courseId la cátedra afectada.
     * @param threadId el hilo afectado, si la operación es de un chat.
     * @param idempotencyKey la clave de idempotencia; si viene vacía se genera
     *   una que **no cambia** entre reintentos.
     * @return la entrada encolada.
     */
    suspend fun enqueue(
        operation: OutboxOperation,
        payload: String,
        courseId: String = "",
        threadId: String = "",
        idempotencyKey: String = "",
    ): OutboxEntry {
        val entry = OutboxEntry(
            id = idGenerator(),
            operation = operation,
            payload = payload,
            createdAt = now(),
            idempotencyKey = idempotencyKey.ifBlank { "idem-${idGenerator()}" },
            courseId = courseId,
            threadId = threadId,
        )
        mutex.withLock { writeLocked(readLocked() + entry) }
        return entry
    }

    /** Saca una entrada de la cola (a mano o porque se envió). */
    suspend fun remove(id: String): Boolean = mutex.withLock {
        val entries = readLocked()
        val kept = entries.filterNot { it.id == id }
        if (kept.size == entries.size) return@withLock false
        writeLocked(kept)
        true
    }

    /**
     * Vuelve a encolar una entrada que había fallado, resetting el contador de
     * intentos (el botón "Reintentar" de la UI).
     */
    suspend fun retry(id: String): Boolean = mutex.withLock {
        val entries = readLocked()
        val updated = entries.map {
            if (it.id != id) it
            else it.copy(
                state = OutboxState.PENDING,
                attempts = 0,
                nextAttemptAt = 0L,
                lastError = "",
            )
        }
        if (updated == entries) return@withLock false
        writeLocked(updated)
        true
    }

    /** Cuántas entradas esperan ser enviadas. */
    suspend fun pendingCount(): Int = mutex.withLock {
        readLocked().count { it.state == OutboxState.PENDING }
    }

    /** Las entradas que no se pudieron enviar (para mostrarlas en la UI). */
    suspend fun deadLetters(): List<OutboxEntry> = mutex.withLock {
        readLocked().filter { it.state == OutboxState.FAILED }
    }

    /**
     * Vacía la cola: envía las entradas que vencen, en orden.
     *
     * El vaciado se **detiene en el primer error transitorio**: no se saltea
     * entradas, porque el orden es parte del contrato. Un error permanente
     * (el servidor rechazó la operación) marca esa entrada como fallida y
     * sigue con las demás.
     *
     * @param sender la función que manda una entrada al servidor.
     * @param force ignora el `nextAttemptAt` de cada entrada: se usa cuando
     *   vuelve la red, para no esperar al backoff.
     * @param onDeadLetter se llama con cada entrada que queda como fallida, para
     *   que la UI pueda avisar.
     */
    suspend fun flush(
        sender: suspend (OutboxEntry) -> OutboxSendResult,
        force: Boolean = false,
        onDeadLetter: (OutboxEntry) -> Unit = {},
    ): FlushReport = flushMutex.withLock {
        var sent = 0
        val deadLetters = mutableListOf<OutboxEntry>()

        while (true) {
            val next = mutex.withLock {
                val head = readLocked().firstOrNull { it.state == OutboxState.PENDING }
                if (head == null) return@withLock null
                if (!force && head.nextAttemptAt > now()) return@withLock null
                head
            } ?: break

            when (val result = sender(next)) {
                is OutboxSendResult.Success -> mutex.withLock {
                    writeLocked(readLocked().filterNot { it.id == next.id })
                    sent++
                }

                is OutboxSendResult.Retryable -> {
                    val attempts = next.attempts + 1
                    val exhausted = attempts >= backoff.maxAttempts
                    val updated = next.copy(
                        attempts = attempts,
                        lastAttemptAt = now(),
                        lastError = result.message,
                        nextAttemptAt = now() + backoff.delayFor(attempts, random()),
                        state = if (exhausted) OutboxState.FAILED else OutboxState.PENDING,
                    )
                    mutex.withLock {
                        writeLocked(readLocked().map { if (it.id == next.id) updated else it })
                    }
                    if (exhausted) {
                        deadLetters += updated
                        onDeadLetter(updated)
                    } else {
                        // Error transitorio: se detiene el vaciado para no
                        // saltarse el orden.
                        break
                    }
                }

                is OutboxSendResult.Permanent -> {
                    val updated = next.copy(
                        attempts = next.attempts + 1,
                        lastAttemptAt = now(),
                        lastError = result.message,
                        state = OutboxState.FAILED,
                    )
                    mutex.withLock {
                        writeLocked(readLocked().map { if (it.id == next.id) updated else it })
                    }
                    deadLetters += updated
                    onDeadLetter(updated)
                }
            }
        }

        val remaining = mutex.withLock { readLocked().size }
        FlushReport(sent = sent, deadLetters = deadLetters, remaining = remaining)
    }

    // -----------------------------------------------------------------------
    // Disco
    // -----------------------------------------------------------------------

    /** Lee la cola del disco. */
    private fun readLocked(): List<OutboxEntry> {
        if (!fs.exists(path)) return emptyList()
        val text = try {
            fs.readText(path)
        } catch (_: Exception) {
            return emptyList()
        }
        val entries = try {
            KitoruJson.decodeFromString(ListSerializer(OutboxEntry.serializer()), text)
        } catch (_: Exception) {
            // Un archivo corrupto no debe voltear la app: se empieza de cero.
            return emptyList()
        }
        return order(entries)
    }

    /**
     * Escribe la cola en disco, de forma atómica.
     *
     * Se escribe primero un temporal y después se copia encima del archivo
     * final: un corte de luz en mitad de la escritura no deja la cola
     * partida. `FileSystem` todavía no tiene `move`, así que el reemplazo es
     * `copy` + `delete`.
     */
    private fun writeLocked(entries: List<OutboxEntry>) {
        val text = KitoruJson.encodeToString(ListSerializer(OutboxEntry.serializer()), entries)
        path.parent?.let { fs.createDirectories(it) }
        val tmp = path.resolve("${path.name}.tmp")
        fs.writeText(tmp, text)
        fs.copy(tmp, path)
        fs.delete(tmp)
    }

    /**
     * El orden de envío: FIFO por `createdAt`, con la cabecera del hilo antes
     * que sus mensajes.
     *
     * La cabecera gana aunque se haya encolado después que los mensajes: para
     * eso el instante efectivo de un mensaje es, como mínimo, el de la
     * cabecera de su hilo, y el desempate lo gana la prioridad.
     */
    private fun order(entries: List<OutboxEntry>): List<OutboxEntry> {
        val threadAt = HashMap<String, Long>()
        for (entry in entries) {
            if (!entry.operation.isThread || entry.threadId.isBlank()) continue
            val previous = threadAt[entry.threadId]
            threadAt[entry.threadId] = previous?.let { minOf(it, entry.createdAt) } ?: entry.createdAt
        }
        return entries.sortedWith(
            compareBy<OutboxEntry> { effectiveCreatedAt(it, threadAt) }
                .thenBy { it.operation.priority }
                .thenBy { it.createdAt }
                .thenBy { it.id }
        )
    }

    /** El instante con el que se ordena una entrada. */
    private fun effectiveCreatedAt(entry: OutboxEntry, threadAt: Map<String, Long>): Long {
        if (entry.operation.isThread) return entry.createdAt
        val thread = threadAt[entry.threadId] ?: return entry.createdAt
        return maxOf(entry.createdAt, thread)
    }
}

/**
 * Un id nuevo para una entrada de la cola.
 *
 * No es un UUID v4 (en `commonMain` no hay `java.util.UUID`), pero cumple lo
 * que importa: lo genera el cliente, es único en el dispositivo y no cambia
 * entre reintentos.
 */
private fun newOperationId(): String {
    val time = nowMillis().toString(16)
    val random = Random.nextLong().toString(16)
    return "op-$time-$random"
}
