package kitoru.testing

import kitoru.platform.FilePath
import kitoru.platform.FileSystem

/**
 * Sistema de archivos en memoria, para tests.
 *
 * La app usa [kitoru.platform.FileSystem] justamente para que el código sea
 * portable entre desktop y Android. Este doble permite probar la
 * persistencia (lectura, escritura, corrupción, concurrencia) sin tocar el
 * disco real y **con los mismos tests en los dos targets**.
 */
class InMemoryFileSystem : FileSystem {

    /** Contenido de cada archivo, por ruta. */
    private val files = mutableMapOf<String, String>()

    /** Carpetas creadas explícitamente. */
    private val dirs = mutableSetOf<String>()

    /** Rutas que fallan al escribir (para probar el manejo de errores). */
    val failOnWrite: MutableSet<String> = mutableSetOf()

    override fun exists(path: FilePath): Boolean =
        files.containsKey(path.value) || dirs.contains(path.value)

    override fun isDirectory(path: FilePath): Boolean = dirs.contains(path.value)

    override fun sizeBytes(path: FilePath): Long = files[path.value]?.toByteArray()?.size?.toLong() ?: 0L

    override fun readText(path: FilePath): String = files[path.value] ?: error("No existe: ${path.value}")

    override fun writeText(path: FilePath, text: String) {
        if (path.value in failOnWrite) error("Fallo simulado al escribir ${path.value}")
        files[path.value] = text
        // La carpeta de un archivo existe por definición.
        path.parent?.let { dirs.add(it.value) }
    }

    override fun delete(path: FilePath) {
        files.remove(path.value)
        dirs.remove(path.value)
    }

    override fun createDirectories(path: FilePath) {
        dirs.add(path.value)
    }

    override fun listFiles(dir: FilePath): List<FilePath> = files.keys
        .filter { it.startsWith("${dir.value}/") || it.startsWith("${dir.value}\\") }
        .filter { path ->
            // Sólo los archivos que están justo en la carpeta (no en subcarpetas).
            val rest = path.removePrefix(dir.value).trimStart('/', '\\')
            !rest.contains('/') && !rest.contains('\\')
        }
        .map { FilePath(it) }
        .sortedBy { it.name }

    override fun copy(from: FilePath, to: FilePath) {
        val text = readText(from)
        writeText(to, text)
    }

    override fun move(from: FilePath, to: FilePath) {
        val text = files.remove(from.value) ?: error("No existe: ${from.value}")
        files[to.value] = text
        dirs.remove(from.value)
        to.parent?.let { dirs.add(it.value) }
    }

    // ---- Utilidades para los tests ----

    /** Escribe un archivo directamente (sin pasar por la app). */
    fun put(path: String, content: String) {
        files[path] = content
        FilePath(path).parent?.let { dirs.add(it.value) }
    }

    /** El contenido crudo de un archivo (o null si no existe). */
    fun peek(path: String): String? = files[path]

    /** Todas las rutas que se escribieron, para verificar-effects. */
    fun writtenPaths(): List<String> = files.keys.sorted()

    /** Vacía todo. */
    fun clear() {
        files.clear()
        dirs.clear()
        failOnWrite.clear()
    }
}
