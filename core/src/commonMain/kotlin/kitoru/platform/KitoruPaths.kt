package kitoru.platform

/**
 * Raíz de datos de la aplicación.
 *
 * Cada plataforma la fija al arrancar:
 *  - Desktop: `<user.home>/.kitoru`
 *  - Android: `context.filesDir/kitoru`
 */
object KitoruPaths {
    /** Carpeta de datos. Si no se fijó, cae en el directorio actual. */
    var dataDir: FilePath = FilePath(".")
        private set

    /** Fija la carpeta de datos (lo hace la app al arrancar). */
    fun setDataDir(path: FilePath) {
        dataDir = path
    }

    /** Carpeta de configuración. */
    val configDir: FilePath get() = dataDir.resolve("config")

    /** Carpeta de datos de las cátedras (materiales, índice, chats). */
    val coursesDir: FilePath get() = dataDir.resolve("courses")

    /** Carpeta de archivos temporales descargados. */
    val cacheDir: FilePath get() = dataDir.resolve("cache")
}
