package com.funshiengine.kitoru.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kitoru.domain.FileCourseRepository
import kitoru.domain.FileEnrollmentRepository
import kitoru.domain.FileProfileRepository
import kitoru.platform.FilePath
import kitoru.platform.KitoruPaths
import kitoru.platform.systemFileSystem
import kitoru.ui.KitoruApp
import kitoru.ui.KitoruTheme
import kitoru.ui.KitoruViewModel
import kitoru.ui.theme.KitoruAccent
import java.io.File

/**
 * Punto de entrada de la app de escritorio.
 *
 * Antes de construir la UI hay que fijar la carpeta de datos: es la que usan
 * los repositorios (config, cátedras, chats). En el escritorio vive en
 * `<user.home>/.kitoru`, salvo que se pase `-Dkitoru.home=...` (útil para
 * una instalación "portable" al lado del ejecutable).
 */
fun main() = application {
    val dataDir = resolveDataDir()
    KitoruPaths.setDataDir(dataDir)

    val fs = systemFileSystem()
    val vm = remember {
        KitoruViewModel(
            courses = FileCourseRepository(fs),
            enrollments = FileEnrollmentRepository(fs),
            profiles = FileProfileRepository(fs),
        )
    }

    Window(
        onCloseRequest = { exitApplication() },
        title = "Kitoru",
        state = rememberWindowState(width = 1180.dp, height = 780.dp),
    ) {
        KitoruTheme(dark = false, accent = KitoruAccent.Default.value) {
            KitoruApp(vm)
        }
    }
}

/**
 * Carpeta de datos de la app.
 *
 * Prioridad: `-Dkitoru.home`, la variable de entorno `KITORU_HOME`, y por
 * defecto `<user.home>/.kitoru`.
 */
private fun resolveDataDir(): FilePath {
    val fromProperty = System.getProperty("kitoru.home")
    val fromEnv = System.getenv("KITORU_HOME")
    val path = when {
        !fromProperty.isNullOrBlank() -> fromProperty
        !fromEnv.isNullOrBlank() -> fromEnv
        else -> System.getProperty("user.home") + File.separator + ".kitoru"
    }
    val dir = File(path)
    dir.mkdirs()
    return FilePath(dir.absolutePath)
}
