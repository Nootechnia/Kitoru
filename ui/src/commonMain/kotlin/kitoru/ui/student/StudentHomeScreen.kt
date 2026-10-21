package kitoru.ui.student

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kitoru.domain.ChatVisibility
import kitoru.domain.Course
import kitoru.ui.AppScreen
import kitoru.ui.KitoruViewModel

/**
 * Home del alumno: sus cátedras y el acceso al chat.
 *
 * La tarjeta de cada cátedra dice **qué ve el docente**: es la
 * transparencia que hace que el alumno use la IA sin sorpresas.
 */
@Composable
fun StudentHomeScreen(vm: KitoruViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Mis cátedras",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Entrá a una cátedra para chatear con el tutor y consultar el material.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        if (vm.learningCourses.isEmpty()) {
            EmptyState(
                title = "Todavía no estás en ninguna cátedra",
                body = "Necesitás el código que te da tu docente. Escribilo acá y quedás inscripto.",
                actionLabel = "Unirme con un código",
                onAction = { vm.setProfile(profileId(), "Alumno") },
            )
            return
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(vm.learningCourses, key = { it.id }) { course ->
                StudentCourseCard(vm, course)
            }
        }
    }
}

/** Tarjeta de una cátedra en la home del alumno. */
@Composable
private fun StudentCourseCard(vm: KitoruViewModel, course: Course) {
    val enrollment = vm.myEnrollments.firstOrNull { it.courseId == course.id }
    val visibility = enrollment?.visibility ?: ChatVisibility.NONE

    Card(
        onClick = { vm.openCourse(course.id, AppScreen.StudentChat(course.id, null)) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(course.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${course.code} · ${course.term}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chatear")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            VisibilityNote(visibility)

            Text(
                text = "Tutor: ${course.modelPolicy.modelLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.openCourse(course.id, AppScreen.StudentChat(course.id, null)) }) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Chatear")
                }
                TextButton(onClick = { vm.openCourse(course.id, AppScreen.StudentModel(course.id)) }) {
                    Text("Ajustar el modelo")
                }
                TextButton(onClick = { vm.openCourse(course.id, AppScreen.StudentMaterials(course.id)) }) {
                    Text("Material")
                }
            }
        }
    }
}

/**
 * Explica, en una línea, qué ve el docente de este alumno.
 *
 * Es la pieza de confianza: el alumno decide, pero nunca a ciegas.
 */
@Composable
internal fun VisibilityNote(visibility: ChatVisibility) {
    val text = when (visibility) {
        ChatVisibility.FULL ->
            "Tu docente ve el texto de tus conversaciones."
        ChatVisibility.METRICS_ONLY ->
            "Tu docente ve estadísticas y la calidad de tus respuestas, pero no el texto."
        ChatVisibility.NONE ->
            "Tu docente no ve tus conversaciones. Sólo las estadísticas que tú decidas compartir."
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
    )
}

/** Estado vacío genérico con una acción. */
@Composable
internal fun EmptyState(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * Id del perfil local del alumno.
 *
 * Sin servidor no hay identidad: el perfil local es la identidad. Se genera
 * una vez y se guarda en la configuración del dispositivo.
 */
internal fun profileId(): String = "alumno-local"
