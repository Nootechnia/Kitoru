package kitoru.platform.log

import android.content.Context
import android.util.Log
import kitoru.platform.FilePath
import kitoru.platform.KitoruPaths
import kitoru.platform.systemFileSystem
import java.io.File

/**
 * Logger de Android: logcat + archivo.
 *
 * El archivo va dentro del sandbox (`filesDir`): el almacenamiento externo es
 * legible por otras apps (en Android ≤ 10 sin permisos), sobrevive a la
 * desinstalación en algunos casos y no se limpia solo. `filesDir` es
 * privado, se borra al desinstalar y no pide permisos.
 *
 * No es un `actual` de [defaultLogger] porque `jvmCommonMain` (compartido
 * con el escritorio) ya aporta ese `actual`, y Kotlin no admite que un
 * source set hoja lo redefina. La app Android llama a
 * [installAndroidLogger] al arrancar y a partir de ahí el log va a logcat.
 */
fun androidLogger(minLevel: LogLevel = LogLevel.INFO): Logger = DefaultLogger(
    minLevel = minLevel,
    sinks = listOf(
        LogcatSink(),
        FileLogSink(systemFileSystem(), { logFilePath }),
    ),
)

/**
 * Sink de logcat: cada nivel del log va al nivel de Android equivalente,
 * para que `adb logcat` pueda filtrar por severidad.
 */
private class LogcatSink : LogSink {
    override fun write(record: LogRecord, line: String) {
        when (record.level) {
            LogLevel.TRACE -> Log.v(TAG, line)
            LogLevel.DEBUG -> Log.d(TAG, line)
            LogLevel.INFO -> Log.i(TAG, line)
            LogLevel.WARN -> Log.w(TAG, line)
            LogLevel.ERROR -> Log.e(TAG, line)
        }
    }

    private companion object {
        const val TAG = "Kitoru"
    }
}

/**
 * Instala el logger de la app Android y fija la carpeta de datos.
 *
 * Se llama una vez, en el arranque de la `Application`: deja la carpeta de
 * datos en `filesDir/kitoru` (dentro del sandbox) y el logger escribiendo en
 * logcat y en `filesDir/kitoru/logs/kitoru.log`.
 */
fun installAndroidLogger(context: Context, minLevel: LogLevel = LogLevel.INFO): Logger {
    KitoruPaths.setDataDir(FilePath(File(context.filesDir, "kitoru").absolutePath))
    val logger = androidLogger(minLevel)
    KitoruLog.install(logger)
    return logger
}
