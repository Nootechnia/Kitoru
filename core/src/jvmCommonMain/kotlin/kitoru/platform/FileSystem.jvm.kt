package kitoru.platform

import java.io.File
import java.text.Normalizer

/** Implementación JVM de [systemFileSystem] con `java.io.File`. */
actual fun systemFileSystem(): FileSystem = JvmFileSystem

private object JvmFileSystem : FileSystem {
    private fun f(p: FilePath): File = File(p.value)

    override fun exists(path: FilePath): Boolean = f(path).exists()

    override fun isDirectory(path: FilePath): Boolean = f(path).isDirectory

    override fun sizeBytes(path: FilePath): Long {
        val file = f(path)
        return if (file.exists()) file.length() else 0L
    }

    override fun readText(path: FilePath): String = f(path).readText(Charsets.UTF_8)

    override fun writeText(path: FilePath, text: String) {
        val file = f(path)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    override fun delete(path: FilePath) {
        val file = f(path)
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    override fun createDirectories(path: FilePath) {
        f(path).mkdirs()
    }

    override fun listFiles(dir: FilePath): List<FilePath> {
        val root = f(dir)
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.isFile }
            .map { FilePath(it.absolutePath) }
            .sortedBy { it.name }
    }

    override fun copy(from: FilePath, to: FilePath) {
        val src = f(from)
        val dst = f(to)
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
    }

    /**
     * Renombra un archivo.
     *
     * `File.renameTo` es atómico en el mismo volumen, pero en Windows no
     * renombra encima de un archivo existente: por eso, si falla, se borra el
     * destino y se reintenta (y si tampoco, se copia, que es lo último).
     */
    override fun move(from: FilePath, to: FilePath) {
        val src = f(from)
        val dst = f(to)
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return
        dst.delete()
        if (src.renameTo(dst)) return
        src.copyTo(dst, overwrite = true)
        src.delete()
    }
}

/** Quita diacríticos con `java.text.Normalizer` (disponible en Android). */
actual fun stripDiacritics(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS, "")

private val DIACRITICS = Regex("\\p{M}+")

/** Reloj del sistema. */
actual fun nowMillis(): Long = System.currentTimeMillis()
