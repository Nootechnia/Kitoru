package kitoru.ui.platform

import androidx.compose.runtime.Composable

/**
 * Guarda un archivo que el usuario produce (un bloque de código, un informe).
 *
 * El mecanismo es distinto en cada plataforma:
 *  - **Escritorio**: diálogo nativo "guardar como…" (JFileChooser).
 *  - **Android**: SAF (`ACTION_CREATE_DOCUMENT`), que es lo único que permite
 *    escribir en una carpeta elegida por el usuario sin permisos invasivos.
 *
 * Por eso es una costura `expect/actual`: la UI no sabe en qué plataforma
 * está.
 */
interface FileSaver {
    /**
     * Pide al usuario dónde guardar y escribe el contenido.
     * @return `true` si se guardó; `false` si el usuario canceló.
     */
    suspend fun save(
        suggestedName: String,
        content: String,
        mimeType: String = "text/plain",
    ): Boolean
}

/** Guardador de archivos de la plataforma. */
@Composable
expect fun rememberFileSaver(): FileSaver
