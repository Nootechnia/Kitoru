package kitoru.ui.platform

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * Guardador de archivos de Android: SAF (`ACTION_CREATE_DOCUMENT`).
 *
 * SAF es la única forma soportada de escribir en una carpeta elegida por el
 * usuario sin pedir permisos invasivos (`READ/WRITE_EXTERNAL_STORAGE` están
 * restringidos desde Android 10 y `MANAGE_EXTERNAL_STORAGE` no se permite en
 * Play). El archivo se escribe con el `ContentResolver`.
 */
private class AndroidSaveRequest(
    val name: String,
    val content: String,
)

private class AndroidSaveHolder {
    var request: AndroidSaveRequest? = null
    var deferred: CompletableDeferred<Boolean>? = null

    fun complete(ok: Boolean) {
        deferred?.complete(ok)
        deferred = null
        request = null
    }
}

@Composable
actual fun rememberFileSaver(): FileSaver {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember { AndroidSaveHolder() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        val request = holder.request
        if (uri == null || request == null) {
            holder.complete(false)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(request.content.toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
            holder.complete(ok)
        }
    }

    return remember(launcher, holder) {
        object : FileSaver {
            override suspend fun save(
                suggestedName: String,
                content: String,
                mimeType: String,
            ): Boolean {
                val deferred = CompletableDeferred<Boolean>()
                holder.request = AndroidSaveRequest(suggestedName, content)
                holder.deferred = deferred
                launcher.launch(suggestedName)
                return deferred.await()
            }
        }
    }
}
