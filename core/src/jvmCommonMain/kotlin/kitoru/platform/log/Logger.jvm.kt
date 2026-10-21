package kitoru.platform.log

import kitoru.platform.systemFileSystem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formateador del timestamp.
 *
 * `SimpleDateFormat` no es thread-safe, así que va en un [ThreadLocal]: el
 * logger escribe desde el hilo de la UI, del dispatcher de I/O y del agente, y
 * un formateador compartido devolvería líneas mezcladas.
 */
private val timestampFormatter = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
}

/**
 * `AAAA-MM-DD HH:MM:SS.mmm` en la zona horaria local.
 *
 * `SimpleDateFormat` es API 1 en Android, así que sirve para el escritorio y
 * para el teléfono con `minSdk 24` (a diferencia de `java.time`, que es API
 * 26 y obligaría a desugaring).
 */
actual fun formatTimestamp(millis: Long): String =
    requireNotNull(timestampFormatter.get()) {
        "El formateador de timestamp debería estar inicializado."
    }.format(Date(millis))

/**
 * Logger por defecto: consola (stderr) + archivo.
 *
 * A stderr y no a stdout: stdout se usa para la salida de la app (o para
 * datos), y el log tiene que ir al canal de errores para poder separarse.
 *
 * Este `actual` vive en `jvmCommonMain` porque **desktop** y **android**
 * comparten ese source set: Kotlin no permite que un source set hoja
 * (android) redefina un `actual` que ya está en el source set intermedio.
 * Por eso el logger de Android (logcat + archivo) se instala explícitamente
 * con `installAndroidLogger(context)` al arrancar la app, que además fija la
 * carpeta de datos en `filesDir`.
 */
actual fun defaultLogger(): Logger = DefaultLogger(
    minLevel = LogLevel.INFO,
    sinks = listOf(
        ConsoleSink { line -> System.err.println(line) },
        FileLogSink(systemFileSystem(), { logFilePath }),
    ),
)
