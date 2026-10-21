package com.funshiengine.kitoru

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import kitoru.domain.FileCourseRepository
import kitoru.domain.FileEnrollmentRepository
import kitoru.domain.FileProfileRepository
import kitoru.platform.AndroidPlatform
import kitoru.platform.FilePath
import kitoru.platform.KitoruPaths
import kitoru.platform.systemFileSystem
import kitoru.ui.KitoruApp
import kitoru.ui.KitoruTheme
import kitoru.ui.KitoruViewModel
import kitoru.ui.theme.KitoruAccent

/**
 * Activity de Kitoru en Android.
 *
 * Lo primero es fijar la carpeta de datos: en Android es
 * `context.filesDir/kitoru` (privada para la app; el almacenamiento externo
 * público no hace falta y evita permisos invasivos).
 *
 * El [KitoruViewModel] vive como campo de la Activity: es un objeto plano
 * con su propio `CoroutineScope`, así que sobrevive a los recomposiciones y
 * se recrea con la Activity (que es lo que corresponde a este hito; más
 * adelante se envolverá en un `androidx.lifecycle.ViewModel` para sobrevivir
 * a los cambios de configuración).
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: KitoruViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dataDir = FilePath(filesDir.resolve("kitoru").absolutePath)
        KitoruPaths.setDataDir(dataDir)

        // El almacén seguro necesita el sandbox privado de la app: sin el
        // contexto no hay `filesDir` y las credenciales no tienen dónde vivir.
        AndroidPlatform.init(this)

        val fs = systemFileSystem()
        viewModel = KitoruViewModel(
            courses = FileCourseRepository(fs),
            enrollments = FileEnrollmentRepository(fs),
            profiles = FileProfileRepository(fs),
        )

        setContent {
            KitoruContent(viewModel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::viewModel.isInitialized) viewModel.shutdown()
    }
}

/** El contenido de la app, con el tema del sistema. */
@Composable
private fun KitoruContent(vm: KitoruViewModel) {
    KitoruTheme(dark = isSystemInDarkTheme(), accent = KitoruAccent.Default.value) {
        KitoruApp(vm)
    }
}
