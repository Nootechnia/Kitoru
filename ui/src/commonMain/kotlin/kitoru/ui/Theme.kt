package kitoru.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kitoru.ui.theme.KitoruAccent

/**
 * Tema de Kitoru.
 *
 * Es el mismo patrón de FunshiEngineAI (tema claro/oscuro + color de acento
 * chosen por el usuario), pero con la paleta de Material 3 completa para que
 * los componentes de las dos apps (alumno y docente) se vean iguales.
 */
@Composable
fun KitoruTheme(
    dark: Boolean = false,
    accent: Int = KitoruAccent.Default.value,
    content: @Composable () -> Unit,
) {
    val primary = Color(accent)
    val scheme = if (dark) {
        darkColorScheme(
            primary = primary,
            secondary = Color(accent).copy(alpha = 1f).let { lighten(it, 0.25f) },
        )
    } else {
        lightColorScheme(
            primary = primary,
            secondary = lighten(primary, 0.2f),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * Aclara un color mezclándolo con blanco.
 *
 * Sirve para derivar el color secundario del acento sin tener que hardcodear
 * una paleta entera.
 */
private fun lighten(color: Color, amount: Float): Color = Color(
    red = color.red + (1f - color.red) * amount,
    green = color.green + (1f - color.green) * amount,
    blue = color.blue + (1f - color.blue) * amount,
    alpha = color.alpha,
)
