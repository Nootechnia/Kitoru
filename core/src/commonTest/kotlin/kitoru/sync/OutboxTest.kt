package kitoru.sync

import kitoru.platform.FilePath
import kitoru.testing.InMemoryFileSystem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests de la cola de salida, de la política de caché y del modo de trabajo.
 *
 * Todos son deterministas: el reloj y los ids se inyectan, así que no hace
 * falta ni `Thread.sleep` ni `Random` global.
 */
class OutboxTest {

    // -----------------------------------------------------------------------
    // Utilidades
    // -----------------------------------------------------------------------

    /** Un reloj que sólo avanza cuando el test lo dice. */
    private class Reloj(var ahora: Long = 1_000L) {
        fun leer(): Long = ahora
        fun avanzar(ms: Long) {
            ahora += ms
        }
    }

    /** Genera ids secuenciales, para poder compararlos. */
    private class Contador {
        var valor: Int = 0
        fun siguiente(): String = "op-${++valor}"
    }

    private val archivo = FilePath("config/outbox.json")

    private fun outbox(
        fs: InMemoryFileSystem,
        reloj: Reloj = Reloj(),
        contador: Contador = Contador(),
        backoff: Backoff = Backoff(),
    ) = Outbox(
        fs = fs,
        path = archivo,
        backoff = backoff,
        now = reloj::leer,
        random = { 0.5 },
        idGenerator = contador::siguiente,
    )

    // -----------------------------------------------------------------------
    // Persistencia
    // -----------------------------------------------------------------------

    @Test
    fun `la cola se guarda en disco y se recarga`() = runTest {
        val fs = InMemoryFileSystem()
        val primera = outbox(fs)
        primera.enqueue(OutboxOperation.APPEND_MESSAGES, "{\"texto\":\"hola\"}", threadId = "t-1")
        primera.enqueue(OutboxOperation.UPSERT_THREAD, "{\"titulo\":\"Duda\"}", threadId = "t-1")

        // Una instancia nueva (la app se cerró y volvió a abrir) lee lo mismo.
        val segunda = outbox(fs)
        val entradas = segunda.entries()

        assertEquals(2, entradas.size)
        assertEquals("{\"texto\":\"hola\"}", entradas.first { it.operation == OutboxOperation.APPEND_MESSAGES }.payload)
        assertTrue(fs.exists(archivo), "La cola tiene que quedar en disco.")
        assertFalse(fs.exists(FilePath("config/outbox.json.tmp")), "El temporal no puede quedar tirado.")
    }

    @Test
    fun `una cola corrupta no voltea la app`() = runTest {
        val fs = InMemoryFileSystem()
        fs.put(archivo.value, "{ esto no es json")

        val cola = outbox(fs)

        assertEquals(emptyList(), cola.entries())
    }

    // -----------------------------------------------------------------------
    // Orden
    // -----------------------------------------------------------------------

    @Test
    fun `las entradas salen en orden de creación`() = runTest {
        val fs = InMemoryFileSystem()
        val reloj = Reloj()
        val cola = outbox(fs, reloj)

        cola.enqueue(OutboxOperation.APPEND_MESSAGES, "{}", threadId = "t-1")
        reloj.avanzar(1_000)
        cola.enqueue(OutboxOperation.APPEND_MESSAGES, "{}", threadId = "t-1")
        reloj.avanzar(1_000)
        cola.enqueue(OutboxOperation.SET_VISIBILITY, "{}")

        val orden = cola.entries().map { it.operation }

        assertEquals(
            listOf(
                OutboxOperation.APPEND_MESSAGES,
                OutboxOperation.APPEND_MESSAGES,
                OutboxOperation.SET_VISIBILITY,
            ),
            orden,
        )
    }

    @Test
    fun `la cabecera del hilo sale antes que sus mensajes`() = runTest {
        val fs = InMemoryFileSystem()
        val reloj = Reloj()
        val cola = outbox(fs, reloj)

        // El caso normal: el mensaje se encola primero y el hilo después, al
        // terminar la conversación.
        cola.enqueue(OutboxOperation.APPEND_MESSAGES, "{}", threadId = "t-1")
        reloj.avanzar(1_000)
        cola.enqueue(OutboxOperation.UPSERT_THREAD, "{}", threadId = "t-1")
        reloj.avanzar(1_000)
        cola.enqueue(OutboxOperation.APPEND_MESSAGES, "{}", threadId = "t-1")

        val orden = cola.entries().map { it.operation }

        assertEquals(
            listOf(
                OutboxOperation.UPSERT_THREAD,
                OutboxOperation.APPEND_MESSAGES,
                OutboxOperation.APPEND_MESSAGES,
            ),
            orden,
        )
    }

    @Test
    fun `los mensajes de un hilo conservan su orden`() = runTest {
        val fs = InMemoryFileSystem()
        val reloj = Reloj()
        val cola = outbox(fs, reloj)

        cola.enqueue(OutboxOperation.APPEND_MESSAGES, "{\"n\":1}", threadId = "t-1")
        reloj.avanzar(1_000)
        cola.enqueue(OutboxOperation.UPSERT_THREAD, "{}", threadId = "t-1")
        reloj.avanzar(1_000)
        cola.enqueue(OutboxOperation.APPEND_MESSAGES, "{\"n\":2}", threadId = "t-1")
        reloj.avanzar(1_000)
        cola.enqueue(OutboxOperation.APPEND_MESSAGES, "{\"n\":3}", threadId = "t-1")

        val orden = cola.entries().map { it.payload }

        assertEquals(listOf("{}", "{\"n\":1}", "{\"n\":2}", "{\"n\":3}"), orden)
    }

    // -----------------------------------------------------------------------
    // Reintentos
    // -----------------------------------------------------------------------

    @Test
    fun `el error transitorio espera cada vez más entre reintentos`() = runTest {
        val fs = InMemoryFileSystem()
        val reloj = Reloj()
        val cola = outbox(fs, reloj)
        cola.enqueue(OutboxOperation.APPEND_MESSAGES, "{}")

        cola.flush { OutboxSendResult.Retryable("Sin conexión con el servidor.") }

        val primera = cola.entries().single()
        assertEquals(1, primera.attempts)
        assertEquals(reloj.leer() + 5_000L, primera.nextAttemptAt)

        // Todavía no toca: el backoff espera.
        reloj.avanzar(1_000L)
        cola.flush { OutboxSendResult.Retryable("Sin conexión con el servidor.") }
        assertEquals(1, cola.entries().single().attempts, "No se reintenta antes de la espera.")

        // Cuando pasa la espera, se reintenta y la espera siguiente es mayor.
        reloj.avanzar(10_000L)
        cola.flush { OutboxSendResult.Retryable("Sin conexión con el servidor.") }
        val segunda = cola.entries().single()
        assertEquals(2, segunda.attempts)
        assertTrue(segunda.nextAttemptAt > primera.nextAttemptAt, "La espera tiene que crecer.")
    }

    @Test
    fun `la espera del backoff crece y tiene tope`() {
        val backoff = Backoff()

        assertEquals(5_000L, backoff.delayFor(1))
        assertEquals(10_000L, backoff.delayFor(2))
        assertEquals(20_000L, backoff.delayFor(3))
        assertTrue(backoff.delayFor(2) > backoff.delayFor(1))
        assertEquals(300_000L, backoff.delayFor(10), "La espera tiene un tope de 5 minutos.")
    }

    @Test
    fun `el jitter desvía la espera sin romper el tope`() {
        val backoff = Backoff()

        assertEquals(4_000L, backoff.delayFor(1, jitter = 0.0))
        assertEquals(6_000L, backoff.delayFor(1, jitter = 1.0))
        assertEquals(240_000L, backoff.delayFor(10, jitter = 0.0))
        assertEquals(360_000L, backoff.delayFor(10, jitter = 1.0))
    }

    @Test
    fun `agotar los intentos deja la entrada como fallida`() = runTest {
        val fs = InMemoryFileSystem()
        val reloj = Reloj()
        val cola = outbox(fs, reloj)
        cola.enqueue(OutboxOperation.APPEND_MESSAGES, "{}")

        var ultimoReporte: FlushReport? = null
        repeat(Backoff().maxAttempts) {
            reloj.avanzar(600_000L)
            ultimoReporte = cola.flush { OutboxSendResult.Retryable("Sin conexión con el servidor.") }
        }

        val entrada = cola.entries().single()
        assertEquals(OutboxState.FAILED, entrada.state)
        assertEquals(Backoff().maxAttempts, entrada.attempts)
        assertEquals(1, ultimoReporte?.deadLetters?.size, "La entrada agotada se avisa.")
        assertEquals(0, cola.pendingCount())
    }

    @Test
    fun `un rechazo del servidor deja la entrada fallida sin reintentar`() = runTest {
        val fs = InMemoryFileSystem()
        val reloj = Reloj()
        val cola = outbox(fs, reloj)
        cola.enqueue(OutboxOperation.APPEND_MESSAGES, "{}")

        val reporte = cola.flush { OutboxSendResult.Permanent("El servidor rechazó la operación.") }

        val entrada = cola.entries().single()
        assertEquals(OutboxState.FAILED, entrada.state)
        assertEquals(1, reporte.deadLetters.size)
        assertEquals("El servidor rechazó la operación.", entrada.lastError)
    }

    @Test
    fun `una entrada enviada sale de la cola`() = runTest {
        val fs = InMemoryFileSystem()
        val cola = outbox(fs)
        cola.enqueue(OutboxOperation.APPEND_MESSAGES, "{}")

        val reporte = cola.flush { OutboxSendResult.Success }

        assertEquals(1, reporte.sent)
        assertEquals(0, reporte.remaining)
        assertEquals(emptyList(), cola.entries())
    }

    // -----------------------------------------------------------------------
    // Idempotencia
    // -----------------------------------------------------------------------

    @Test
    fun `la clave de idempotencia no cambia entre reintentos`() = runTest {
        val fs = InMemoryFileSystem()
        val reloj = Reloj()
        val cola = outbox(fs, reloj)
        val encolada = cola.enqueue(OutboxOperation.APPEND_MESSAGES, "{}")

        cola.flush { OutboxSendResult.Retryable("Sin conexión con el servidor.") }
        val trasElReintento = cola.entries().single()

        // La clave se genera una vez y se persiste: la otra instancia de la
        // cola (la app reiniciada) ve la misma.
        val otraInstancia = outbox(fs)
        val trasRecargar = otraInstancia.entries().single()

        assertTrue(encolada.idempotencyKey.isNotBlank(), "Toda entrada lleva clave de idempotencia.")
        assertEquals(encolada.idempotencyKey, trasElReintento.idempotencyKey)
        assertEquals(encolada.idempotencyKey, trasRecargar.idempotencyKey)
        assertNotEquals(encolada.id, encolada.idempotencyKey)
    }

    @Test
    fun `una clave de idempotencia explícita se respeta`() = runTest {
        val fs = InMemoryFileSystem()
        val cola = outbox(fs)

        val entrada = cola.enqueue(OutboxOperation.APPEND_MESSAGES, "{}", idempotencyKey = "idem-fijo")

        assertEquals("idem-fijo", entrada.idempotencyKey)
    }

    // -----------------------------------------------------------------------
    // Política de caché
    // -----------------------------------------------------------------------

    @Test
    fun `la política de caché de cada recurso`() {
        assertEquals(CachePolicy.ALWAYS, CachePolicies.policyOf(CacheKind.MATERIAL_FILES))
        assertEquals(CachePolicy.ALWAYS, CachePolicies.policyOf(CacheKind.MATERIAL_INDEX))
        assertEquals(CachePolicy.ALWAYS, CachePolicies.policyOf(CacheKind.OWN_THREADS))
        assertEquals(CachePolicy.ALWAYS, CachePolicies.policyOf(CacheKind.COURSE_CONFIG))
        assertEquals(CachePolicy.ALWAYS, CachePolicies.policyOf(CacheKind.ENROLLMENTS))
        assertEquals(CachePolicy.IF_ONLINE, CachePolicies.policyOf(CacheKind.ANNOUNCEMENTS))
        assertEquals(CachePolicy.IF_ONLINE, CachePolicies.policyOf(CacheKind.ASSIGNMENTS))
        assertEquals(CachePolicy.IF_ONLINE, CachePolicies.policyOf(CacheKind.OTHER_THREADS))
        assertEquals(CachePolicy.NEVER, CachePolicies.policyOf(CacheKind.SECRETS))
        assertEquals(CachePolicy.NEVER, CachePolicies.policyOf(CacheKind.MODEL_CATALOG))
    }

    // -----------------------------------------------------------------------
    // Modo de trabajo
    // -----------------------------------------------------------------------

    @Test
    fun `el modo se deriva de la conexión y de la cola`() {
        assertEquals(SyncMode.ONLINE, deriveSyncMode(isOnline = true, pending = 0))
        assertEquals(SyncMode.READ_ONLY, deriveSyncMode(isOnline = false, pending = 3))
        assertEquals(SyncMode.OFFLINE, deriveSyncMode(isOnline = false, pending = 0))
    }
}
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    