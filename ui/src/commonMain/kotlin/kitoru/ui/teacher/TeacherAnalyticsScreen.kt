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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kitoru.domain.AnalyticsAnalyzer
import kitoru.domain.CourseAnalytics
import kitoru.ui.KitoruViewModel
import kitoru.ui.student.EmptyState
import kitoru.ui.components.MetricCard

/**
 * Panel de analítica de la cátedra.
 *
 * Es la pantalla que pidió el usuario: **estadísticas, comportamiento y
 * calidad de las respuestas**.
 *
 * Los números salen del [AnalyticsAnalyzer], que es una función pura: no
 * llama al modelo ni a la red. Por eso son reproducibles y verificables.
 */
@Composable
fun TeacherAnalyticsScreen(vm: KitoruViewModel, courseId: String) {
    val course = vm.allCourses.firstOrNull { it.id == courseId }
    if (course == null) {
        vm.back()
        return
    }

    var analytics by remember(courseId) { mutableStateOf<CourseAnalytics?>(null) }
    var loading by remember(courseId) { mutableStateOf(true) }

    LaunchedEffect(courseId) {
        loading = true
        val enrollments = vm.enrollmentsFor(courseId)
        val threads = vm.threadsFor(courseId)
        val messages = vm.messagesFor(courseId)
        analytics = AnalyticsAnalyzer.analyze(course, enrollments, threads, messages)
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(course.name, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Cómo se usa la IA en la cátedra y qué tan buenas son las respuestas.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        val data = analytics
        if (loading) {
            Text("Calculando…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        if (data == null || data.totalMessages == 0) {
            EmptyState(
                title = "Todavía no hay actividad",
                body = "Cuando tus alumnos empiece a chatear, acá vas a ver el uso, " +
                    "el comportamiento y la calidad de las respuestas.",
            )
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text("En números", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCard("Alumnos", "${data.activeStudents}/${data.students}", "activos")
                    MetricCard("Mensajes", "${data.totalMessages}", "de alumnos")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCard(
                        label = "Citas respaldadas",
                        value = "${(data.citationSupportRate * 100).toInt()} %",
                        hint = "${data.citationsVerified} de ${data.citationsVerified + data.citationsUnsupported + data.citationsUnknown}",
                    )
                    MetricCard(
                        label = "Latencia media",
                        value = "${data.avgLatencyMs} ms",
                        hint = "por respuesta",
                    )
                }
            }

            item {
                Text(
                    text = "Calidad de las respuestas",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        QualityLine(
                            "Respuestas apoyadas en el material",
                            "${data.citationsVerified}",
                            MaterialTheme.colorScheme.primary,
                        )
                        QualityLine(
                            "Afirmaciones que el material no respalda",
                            "${data.citationsUnsupported}",
                            MaterialTheme.colorScheme.error,
                        )
                        QualityLine(
                            "Citas que apuntan a una fuente inexistente",
                            "${data.citationsUnknown}",
                            MaterialTheme.colorScheme.tertiary,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "La calidad se mide con un verificador determinista: cada cita " +
                                "de la respuesta se comprueba contra el texto de la fuente. " +
                                "No depende de la opinión del modelo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (data.perStudent.isNotEmpty()) {
                item {
                    Text(
                        text = "Por alumno",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(data.perStudent, key = { it.userId }) { student ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(student.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${student.messages} mensajes",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (student.citationsTotal > 0) {
                                Text(
                                    text = "${student.citationsVerified} de ${student.citationsTotal} citas respaldadas " +
                                        "(${(student.citationSupportRate * 100).toInt()} %)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (data.topSources.isNotEmpty()) {
                item {
                    Text(
                        text = "Material más consultado",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(data.topSources) { source ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(source.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${source.times}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Una línea de la sección de calidad. */
@Composable
private fun QualityLine(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}
