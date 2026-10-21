package kitoru.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Guardador de archivos del escritorio: diálogo nativo "guardar como…".
 *
 * Se usa `java.awt.FileDialog` (y no `JFileChooser`) porque no arrastra Swing
 * y funciona igual en Windows, macOS y Linux con el aspecto del sistema.
 */
class DesktopFileSaver : FileSaver {

    override suspend fun save(suggestedName: String, content: String, mimeType: String): Boolean {
        val parent = Frame().apply { isUndecorated = true }
        val dialog = FileDialog(parent, "Guardar como", FileDialog.SAVE).apply {
            file = suggestedName
            isVisible = true
        }
        val dir = dialog.directory
        val name = dialog.file
        if (name == null) return false
        val target = File(dir, name)
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }
}

@Composable
actual fun rememberFileSaver(): FileSaver = remember { DesktopFileSaver() }
