package kitoru.platform.log

import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kitoru.platform.KitoruPaths
import kitoru.platform.nowMillis

/**
 * Nivel de un evento de log.
 *
 * El orden del enum **es** el orden de severidad: [passes] compara por
 * ordinal, así que subir el nivel de un logger filtra todo lo de abajo sin
 * necesidad de una tabla aparte (que siempre termina desincronizada).
 */
enum class LogLevel(val label: String) {
    TRACE("TRACE"),
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
    ;

    /** ¿Un evento de este nivel pasa el filtro de nivel [min]? */
    fun passes(min: LogLevel): Boolean = ordinal >= min.ordinal
}

/**
 * Un evento de log ya formateado.
 *
 * El mensaje llega **redactado** ([SecretRedactor]): la redacción se aplica
 * en el logger, *antes* de construir el registro, para que ni el archivo, ni
 * el buffer circular, ni el portapapeles puedan mostrar un secreto que haya
 * pasado por el logger. Si la redacción se hiciera en el sink, el buffer en
 * memoria conservaría el texto original y el diagnóstico lo filtraría.
 */
data class LogRecord(
    /** Momento del evento (milisegundos desde epoch). */
    val timestampMillis: Long,
    val level: LogLevel,
    /** Origen del evento: "ChatEngine", "Gateway", "Index"… */
    val tag: String,
    val message: String,
    /** Excepción ya descripta y redactada (tipo, mensaje y stack). */
    val throwableText: String? = null,
) {

    /**
     * Render de una línea (o de un bloque, si hay excepción).
     *
     * Formato fijo: `2026-10-21 14:03:22.123 [WARN ] ChatEngine: el índice está vacío`.
     * El formato es parte del contrato con el usuario (los bug reports viejos
     * siguen siendo legibles) y por eso hay un test que lo verifica con una
     * regexp.
     */
    fun render(): String {
        val head = "${formatTimestamp(timestampMillis)} [${level.label.padEnd(5)}] $tag: $message"
        val tail = throwableText
            ?.takeIf { it.isNotBlank() }
            ?.let { "\n    " + it.replace("\n", "\n    ") }
        return head + (tail ?: "")
    }
}

/**
 * Logger de la aplicación.
 *
 * Contrato mínimo: un nivel, un tag y un mensaje. Sin dependencias externas:
 * la implementación escribe en memoria, en un archivo y (según la
 * plataforma) en la consola del sistema.
 */
interface Logger {

    /** Nivel mínimo que se registra: por debajo se descarta sin formatear. */
    val minLevel: LogLevel

    /**
     * Alias de [minLevel] con el nombre que usa el panel de diagnóstico
     * ("severidad mínima"). Existe para que quien lee el panel de la app no
     * tenga que traducir mentalmente "minLevel" a "severidad".
     */
    val severity: LogLevel get() = minLevel

    /** Registra un evento. */
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)

    fun trace(tag: String, message: String) = log(LogLevel.TRACE, tag, message)

    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)

    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)

    fun warn(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.WARN, tag, message, throwable)

    fun error(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, throwable)
}

/**
 * Ejecuta [block] y loguea cuánto tardó, a nivel DEBUG.
 *
 * Sirve para medir de un vistazo dónde se va el tiempo, sin instrumentar a
 * mano cada llamada.
 */
inline fun <T> Logger.timed(tag: String, block: () -> T): T {
    val t0 = nowMillis()
    try {
        return block()
    } finally {
        log(LogLevel.DEBUG, tag, "terminó en ${nowMillis() - t0} ms")
    }
}

/**
 * Tags canónicos: el filtro del panel de diagnóstico los ofrece de a uno.
 *
 * Un tag fuera de esta lista se acepta (es un `String`), pero conviene usar
 * estos para que el filtro del panel sea útil.
 */
object LogTags {
    const val APP = "App"           // arranque, cierre, versión
    const val GATEWAY = "Gateway"   // peticiones al modelo y sus errores
    const val RAG = "Rag"           // recuperación y verificación de citas
    const val INDEX = "Index"       // indexación de materiales
    const val CHAT = "ChatEngine"   // motor de chat
    const val IO = "IO"             // lectura/escritura de archivos y repos
    const val UI = "UI"             // acciones de pantalla
    const val DIAG = "Diag"         // el propio panel de diagnóstico
}

/**
 * Buffer circular de los últimos [capacity] eventos.
 *
 * Es un ring buffer con cerrojo: se escribe desde cualquier hilo (la UI, el
 * dispatcher de I/O, el agente) y se lee desde el hilo de la UI.
 */
class LogBuffer(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "La capacidad del buffer debe ser mayor que 0." }
    }

    private val items = arrayOfNulls<LogRecord>(capacity)
    private var count = 0
    private var head = 0
    private val lock = Any()

    /** Agrega un evento, descartando el más viejo si el buffer está lleno. */
    fun append(record: LogRecord) = synchronized(lock) {
        items[(head + count) % capacity] = record
        if (count < capacity) count++ else head = (head + 1) % capacity
    }

    /** Copia de los eventos, del más viejo al más nuevo. */
    fun snapshot(): List<LogRecord> = synchronized(lock) {
        (0 until count).map {
            requireNotNull(items[(head + it) % capacity]) {
                "El buffer del log debería tener el elemento $it."
            }
        }
    }

    /** Vacía el buffer (botón "Borrar logs"). */
    fun clear() = synchronized(lock) {
        items.indices.forEach { items[it] = null }
        count = 0
        head = 0
    }

    /** Render de los eventos filtrados, uno por línea. */
    fun render(
        minLevel: LogLevel = LogLevel.TRACE,
        tag: String? = null,
        limit: Int = Int.MAX_VALUE,
    ): String = snapshot()
        .filter { it.level.passes(minLevel) && (tag == null || it.tag == tag) }
        .takeLast(limit)
        .joinToString("\n") { it.render() }

    companion object {
        /** Capacidad por defecto: 500 eventos (~60 KB) alcanzan para una sesión. */
        const val DEFAULT_CAPACITY = 500
    }
}

/**
 * Destino de una línea de log ya renderizada.
 *
 * `write` no debe lanzar: el logger aísla los fallos de los sinks, porque un
 * disco lleno no puede tumbar la app.
 */
interface LogSink {
    /** Escribe la línea. */
    fun write(record: LogRecord, line: String)
}

/**
 * Sink de consola: imprime la línea con la función inyectada.
 *
 * Se inyecta el imprime porque la consola no es portable: en el escritorio es
 * `System.err` y en Android es `android.util.Log` con el nivel mapeado.
 */
class ConsoleSink(private val printer: (String) -> Unit) : LogSink {
    override fun write(record: LogRecord, line: String) = printer(line)
}

/**
 * Logger por defecto: buffer circular en memoria + sinks + redacción.
 *
 * La redacción se aplica **antes** de construir el [LogRecord], así que
 * ningún sink (ni el archivo, ni el buffer, ni el portapapeles) puede
 * mostrar un secreto que haya pasado por el logger.
 */
class DefaultLogger(
    override val minLevel: LogLevel,
    private val sinks: List<LogSink>,
    /** Buffer circular: últimos eventos para el panel y el diagnóstico. */
    val buffer: LogBuffer = LogBuffer(),
    private val redact: (String) -> String = SecretRedactor::redact,
) : Logger {

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        // Se descarta antes de formatear: el costo de redactar y renderizar
        // un evento que nadie va a ver es puro trabajo perdido.
        if (!level.passes(minLevel)) return
        val record = LogRecord(
            timestampMillis = nowMillis(),
            level = level,
            tag = tag,
            message = redact(message).take(MAX_MESSAGE_CHARS),
            throwableText = throwable?.let { redact(describe(it)).take(MAX_THROWABLE_CHARS) },
        )
        buffer.append(record)
        val line = record.render()
        sinks.forEach { sink -> runCatching { sink.write(record, line) } }
    }

    /** Describe una excepción para el log: tipo, mensaje y stack. */
    private fun describe(t: Throwable): String = buildString {
        append(t::class.simpleName ?: "Throwable")
        t.message?.takeIf { it.isNotBlank() }?.let { append(": "); append(it) }
        append("\n")
        append(t.stackTraceToString())
    }

    companion object {
        /** Tope del mensaje: evita que un volcado enorme contamine el log. */
        const val MAX_MESSAGE_CHARS = 4_000

        /** Tope del stack trace (contando el mensaje de la excepción). */
        const val MAX_THROWABLE_CHARS = 8_000
    }
}

/**
 * Escribe el log en un archivo, con rotación por tamaño.
 *
 * Archivos: `kitoru.log` (el actual) y `kitoru.log.1` … `kitoru.log.[keep]`
 * (los anteriores). El total en disco no pasa de `maxBytes * (keep + 1)`.
 *
 * La rotación **no** ocurre al arrancar: si el archivo actual ya tiene 1,5 MB,
 * la sesión nueva sigue escribiendo en él y el bug report tiene el contexto
 * completo. La rotación ocurre al superar el tope, durante la escritura.
 */
class FileLogSink(
    private val fs: FileSystem,
    /** Se resuelve en cada escritura: la carpeta de datos puede fijarse tarde. */
    private val pathProvider: () -> FilePath,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val keep: Int = 1,
    /**
     * Cuánto se acumula en memoria antes de volcar al disco.
     *
     * Escribir cada línea por separado implicaría reescribir el archivo
     * entero en cada evento (el [FileSystem] portable no tiene "append"), y
     * eso es O(n²) sobre un archivo de 2 MB. Acumulando un poco, el archivo
     * se escribe una vez por lote. Los errores se vuelcan enseguida: son las
     * líneas que más importan cuando algo sale mal.
     */
    private val flushThresholdBytes: Int = DEFAULT_FLUSH_THRESHOLD,
) : LogSink {

    private val lock = Any()
    private val pending = StringBuilder()

    override fun write(record: LogRecord, line: String) {
        // TRACE no va al archivo: es voluminoso y no sirve para el bug report.
        // Queda sólo en el buffer circular (que el panel puede mostrar).
        if (record.level == LogLevel.TRACE) return
        synchronized(lock) {
            pending.append(line).append('\n')
            if (pending.length >= flushThresholdBytes || record.level == LogLevel.ERROR) {
                flushLocked()
            }
        }
    }

    /** Vuelca al disco lo que está pendiente. */
    fun flush() = synchronized(lock) { flushLocked() }

    private fun flushLocked() {
        if (pending.isEmpty()) return
        // Se saca el lote del buffer *antes* de tocar el disco: si la
        // escritura falla (disco lleno, carpeta sin permisos), el lote se
        // pierde pero el logger sigue funcionando y el buffer no crece sin
        // límite. Perder una línea de log es mejor que perder la app.
        val chunk = pending.toString()
        pending.clear()
        runCatching {
            val path = pathProvider()
            path.parent?.let { fs.createDirectories(it) }
            val current = if (fs.exists(path)) fs.readText(path) else ""
            fs.writeText(path, current + chunk)
            if (fs.sizeBytes(path) > maxBytes) rotate(path)
        }
    }

    /**
     * Rota los archivos: el actual pasa a ser el ".1" y el ".1" viejo se
     * borra.
     *
     * Hoy se usa `copy` + `delete` porque `FileSystem` no tiene `move`: un
     * renombrado sería mucho más barato que copiar el archivo entero, así
     * que la operación queda centralizada acá para poder cambiar el día que
     * `FileSystem` aprenda a renombrar.
     */
    private fun rotate(path: FilePath) {
        // 1. Se borra el más viejo.
        if (keep >= 1 && fs.exists(sibling(path, keep))) fs.delete(sibling(path, keep))
        // 2. Los demás se desplazan uno.
        for (i in keep - 1 downTo 1) {
            val from = sibling(path, i)
            if (fs.exists(from)) {
                fs.copy(from, sibling(path, i + 1))
                fs.delete(from)
            }
        }
        // 3. El actual pasa a ser el ".1".
        if (fs.exists(path)) {
            fs.copy(path, sibling(path, 1))
            fs.delete(path)
        }
    }

    private fun sibling(path: FilePath, index: Int): FilePath =
        (path.parent ?: path).resolve("${path.name}.$index")

    companion object {
        /** Tope del archivo: 2 MB (un mes de uso diario del docente entra). */
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024

        /** Cuánto se acumula antes de escribir al disco. */
        const val DEFAULT_FLUSH_THRESHOLD = 16 * 1024
    }
}

/**
 * Acceso al logger global.
 *
 * Los call sites usan `KitoruLog.logger.info(LogTags.INDEX, "…")`. Es una
 * indirección de una línea que permite testear sin tocar disco y cambiar el
 * destino (consola, archivo, logcat) en una sola parte.
 */
object KitoruLog {

    /** Logger activo. Los tests y la app lo pueden reemplazar con [install]. */
    var logger: Logger = defaultLogger()
        private set

    /** Reemplaza el logger global (tests, o arranque con otro nivel). */
    fun install(replacement: Logger) {
        logger = replacement
    }
}

/**
 * Carpeta de logs: `<dataDir>/logs`.
 *
 * Vive acá (y no en [KitoruPaths]) para no tocar un archivo que otros agentes
 * están editando; el contrato es el mismo.
 */
val logsDir: FilePath get() = KitoruPaths.dataDir.resolve("logs")

/** Ruta del archivo de log: `<dataDir>/logs/kitoru.log`. */
val logFilePath: FilePath get() = logsDir.resolve("kitoru.log")

/**
 * Logger global de la aplicación.
 *
 * Devuelve **siempre la misma instancia** en cada plataforma: el buffer
 * circular es compartido y es la fuente del panel de diagnóstico.
 *
 * - Desktop: consola (stderr) + archivo `logs/kitoru.log`.
 * - Android: logcat + archivo `logs/kitoru.log` (interno al sandbox).
 */
expect fun defaultLogger(): Logger

/**
 * Formatea un instante como `AAAA-MM-DD HH:MM:SS.mmm` en la zona horaria
 * local. El timestamp del log es local, no UTC: el que lee el log está
 * mirando la misma hora que la app.
 */
expect fun formatTimestamp(millis: Long): String
