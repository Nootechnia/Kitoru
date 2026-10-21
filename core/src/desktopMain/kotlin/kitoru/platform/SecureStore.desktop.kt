package kitoru.platform

/**
 * El almacén de secretos del escritorio.
 *
 * Eligió el protector: el **nativo del sistema operativo** (DPAPI en Windows,
 * llavero en macOS, Secret Service en Linux) y, si no hay ninguno, un
 * **archivo cifrado con AES-GCM** (protección reducida) o, en el peor caso,
 * **sólo memoria** (que es la "regla de oro": el secreto nunca se escribe en
 * texto plano).
 *
 * La implementación está en el código compartido de las dos plataformas JVM
 * (`SecureStore.jvm.kt`) porque el motor de cifrado lo usan también Android;
 * lo que es **sólo** de escritorio —lanzar procesos del sistema operativo— vive
 * acá.
 */
actual fun secureStore(): SecureStore = DesktopSecureStore

/**
 * Ejecuta un comando del sistema operativo con `ProcessBuilder`.
 *
 * En el escritorio es lo natural: `powershell.exe`, `security` y `secret-tool`
 * son programas que se invocan así. En Android este `actual` no se usa (el
 * `actual` de Android devuelve un error explícito), pero tiene que existir
 * para que el source set compartido compile.
 */
internal actual fun runOsCommand(
    command: List<String>,
    stdin: String?,
    timeoutMs: Long,
): OsCommandResult = try {
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()

    // La entrada estándar se escribe y se cierra: si el comando no la lee,
    // el proceso no queda esperando.
    val input = process.outputStream
    try {
        if (stdin != null) {
            input.write(stdin.toByteArray(Charsets.UTF_8))
            input.flush()
        }
    } finally {
        input.close()
    }

    // La salida se lee en otro hilo para poder cortar el proceso si se
    // cuelga: `security` puede pedir un permiso y quedarse esperando.
    val buffer = StringBuilder()
    val reader = Thread {
        runCatching { buffer.append(process.inputStream.readBytes().toString(Charsets.UTF_8)) }
    }
    reader.isDaemon = true
    reader.start()
    reader.join(timeoutMs)

    if (reader.isAlive) {
        process.destroyForcibly()
        OsCommandResult(-2, "El comando '${command.firstOrNull()}' tardó demasiado y se cortó.")
    } else {
        OsCommandResult(process.waitFor(), buffer.toString())
    }
} catch (e: Exception) {
    // El comando no existe o no se pudo arrancar: el protector lo trata como
    // "no disponible" y el almacén degrada de forma visible.
    OsCommandResult(-1, e.message ?: "No se pudo ejecutar el comando.")
}
