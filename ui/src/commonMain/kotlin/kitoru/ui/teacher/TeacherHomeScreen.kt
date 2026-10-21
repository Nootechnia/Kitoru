package kitoru.ui.teacher

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
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.unit.dp
import kitoru.domain.Course
import kitoru.ui.AppScreen
import kitoru.ui.KitoruViewModel
import kitoru.ui.student.EmptyState

/**
 * Home del docente: sus cátedras.
 *
 * Es el punto de partida del recorrido de diseño de una cátedra: entrar,
 * subir material, configurar el modelo y mirar la analítica.
 */
@Composable
fun TeacherHomeScreen(vm: KitoruViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Mis cátedras", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Diseñá la cátedra, subí el material y mirá cómo se usa la IA.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        if (vm.teachingCourses.isEmpty()) {
            EmptyState(
                title = "Todavía no creaste ninguna cátedra",
                body = "Creá una cátedra, subí el material de la materia y compartí el código " +
                    "con tus alumnos.",
                actionLabel = "Crear cátedra",
                onAction = { vm.error = "La creación de cátedras llega en el próximo hito." },
            )
            return
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(vm.teachingCourses, key = { it.id }) { course ->
                TeacherCourseCard(vm, course)
            }
        }
    }
}

/** Tarjeta de una cátedra en el panel del docente. */
@Composable
private fun TeacherCourseCard(vm: KitoruViewModel, course: Course) {
    val role = vm.myEnrollments.firstOrNull { it.courseId == course.id }?.role
    Card(
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
                        text = "${course.code} · ${course.term}" +
                            if (role != null) " · ${role.label}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (course.joinCode.isNotBlank()) {
                Text(
                    text = "Código de inscripción: ${course.joinCode}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.openCourse(course.id, AppScreen.TeacherCourse(course.id)) }) {
                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Diseñar")
                }
                OutlinedButton(onClick = { vm.openCourse(course.id, AppScreen.TeacherMaterials(course.id)) }) {
                    Icon(Icons.Filled.MenuBook, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Material")
                }
                OutlinedButton(onClick = { vm.openCourse(course.id, AppScreen.TeacherAnalytics(course.id)) }) {
                    Icon(Icons.Filled.Analytics, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Analítica")
                }
                TextButton(onClick = { vm.openCourse(course.id, AppScreen.TeacherChats(course.id)) }) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Chats")
                }
                TextButton(onClick = { vm.openCourse(course.id, AppScreen.TeacherStudents(course.id)) }) {
                    Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Alumnos")
                }
            }
        }
    }
}
