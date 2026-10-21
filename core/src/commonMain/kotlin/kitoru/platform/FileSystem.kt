package kitoru.platform

/**
 * Ruta de archivo opaca. Envuelve la ruta real del sistema para que el
 * dominio no dependa de `java.nio.file` (no existe en Android por debajo de
 * API 26) ni de `okio`.
 */
data class FilePath(val value: String) {

    /** Nombre del archivo (sin directorios). */
    val name: String get() = value.substringAfterLast('/').substringAfterLast('\\')

    /** Extensión en minúsculas, sin el punto. Vacía si no tiene. */
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    /** Directorio padre, o null si la ruta no tiene. */
    val parent: FilePath?
        get() {
            val idx = maxOf(value.lastIndexOf('/'), value.lastIndexOf('\\'))
            return if (idx <= 0) null else FilePath(value.substring(0, idx))
        }

    /** Une un segmento a la ruta. */
    fun resolve(segment: String): FilePath =
        FilePath(if (value.endsWith("/") || value.endsWith("\\")) value + segment else "$value/$segment")

    /** Ruta relativa a [base], con separadores `/`. */
    fun relativeTo(base: FilePath): String {
        val b = base.value.trimEnd('/', '\\')
        return if (value.startsWith("$b/") || value.startsWith("$b\\")) {
            value.substring(b.length + 1).replace('\\', '/')
        } else {
            value.replace('\\', '/')
        }
    }

    override fun toString(): String = value
}

/**
 * Sistema de archivos mínimo y portable.
 *
 * La implementación real usa `java.io.File` (disponible en desktop y en
 * Android desde la API 1), así que el mismo código sirve para las dos
 * plataformas sin desugaring.
 */
interface FileSystem {
    fun exists(path: FilePath): Boolean
    fun isDirectory(path: FilePath): Boolean
    fun sizeBytes(path: FilePath): Long
    fun readText(path: FilePath): String
    fun writeText(path: FilePath, text: String)
    fun delete(path: FilePath)
    fun createDirectories(path: FilePath)
    fun listFiles(dir: FilePath): List<FilePath>
    fun copy(from: FilePath, to: FilePath)

    /**
     * Mueve (renombra) un archivo.
     *
     * Es lo que permite la **escritura atómica**: se escribe el contenido en
     * `archivo.tmp` y después se renombra al destino. Si la app se corta a
     * mitad de escritura, el archivo bueno sigue intacto y el `.tmp` se
     * descarta.
     */
    fun move(from: FilePath, to: FilePath)
}

/** Sistema de archivos del sistema. */
expect fun systemFileSystem(): FileSystem
