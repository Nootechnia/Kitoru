package kitoru.ui.teacher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kitoru.domain.ChatVisibility
import kitoru.domain.Enrollment
import kitoru.ui.KitoruViewModel
import kitoru.ui.student.EmptyState

/**
 * Chats de los alumnos.
 *
 * El filtro de visibilidad se aplica **antes** de la UI: si la inscripción
 * del alumno es [ChatVisibility.METRICS_ONLY], el texto no llega a esta
 * pantalla. El docente ve el número, no el contenido.
 */
@Composable
fun TeacherChatsScreen(vm: KitoruViewModel, courseId: String) {
    val course = vm.allCourses.firstOrNull { it.id == courseId }
    if (course == null) {
        vm.back()
        return
    }

    var enrollments by remember(courseId) { mutableStateOf<List<Enrollment>>(emptyList()) }
    var threadCount by remember(courseId) { mutableStateOf(0) }

    LaunchedEffect(courseId) {
        enrollments = vm.enrollmentsFor(courseId)
        val threads = vm.threadsFor(courseId)
        val messages = vm.messagesFor(courseId)
        threadCount = threads.count { t ->
            val author = enrollments.firstOrNull { it.userId == t.authorId }
            author != null && messages.any { it.threadId == t.id }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Chats de los alumnos", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Sólo se ve lo que cada alumno autorizó.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        val visible = enrollments.filter { it.visibility.showsMetrics }
        if (visible.isEmpty()) {
            EmptyState(
                title = "Todavía no hay alumnos inscriptos",
                body = "Compartí el código de inscripción de la cátedra para que se anoten.",
            )
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("$threadCount conversación(es) en total", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            items(visible, key = { it.id }) { enrollment ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(enrollment.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = enrollment.visibility.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!enrollment.visibility.showsText) {
                            Text(
                                text = "Este alumno no compartió el texto de sus conversaciones: " +
                                    "sólo las estadísticas.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Alumnos de la cátedra.
 *
 * Desde acá el docente cambia la visibilidad de cada uno: es la palanca
 * que decide si ve el texto o sólo las métricas.
 */
@Composable
fun TeacherStudentsScreen(vm: KitoruViewModel, courseId: String) {
    val course = vm.allCourses.firstOrNull { it.id == courseId }
    if (course == null) {
        vm.back()
        return
    }

    var enrollments by remember(courseId) { mutableStateOf<List<Enrollment>>(emptyList()) }

    LaunchedEffect(courseId) {
        enrollments = vm.enrollmentsFor(courseId)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Alumnos", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Qué ve el docente de cada alumno.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        if (enrollments.isEmpty()) {
            EmptyState(
                title = "Nadie se anotó todavía",
                body = "Compartí el código ${course.joinCode.ifBlank { "(sin código)" }} con tus alumnos.",
            )
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(enrollments, key = { it.id }) { enrollment ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(enrollment.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = enrollment.role.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "Visibilidad: ${enrollment.visibility.label} — ${enrollment.visibility.description}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
