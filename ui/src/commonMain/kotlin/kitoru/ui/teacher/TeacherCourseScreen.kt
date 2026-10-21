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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import kitoru.domain.Material
import kitoru.ui.KitoruViewModel
import kitoru.ui.student.EmptyState

/**
 * Materiales de la cátedra: el corpus del RAG.
 *
 * Es la pantalla donde el docente sube lo que la IA va a poder citar.
 */
@Composable
fun TeacherMaterialsScreen(vm: KitoruViewModel, courseId: String) {
    val course = vm.allCourses.firstOrNull { it.id == courseId }
    if (course == null) {
        vm.back()
        return
    }

    var materials by remember(courseId) { mutableStateOf<List<Material>>(emptyList()) }

    LaunchedEffect(courseId) {
        materials = vm.materialsFor(courseId)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Material de la cátedra", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Lo que subas es lo que el tutor puede citar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FloatingActionButton(onClick = { vm.error = "La subida de archivos llega en el próximo hito." }) {
                Icon(Icons.Filled.Add, contentDescription = "Subir material")
            }
        }

        if (materials.isEmpty()) {
            EmptyState(
                title = "Todavía no hay material",
                body = "Sin material la IA responde con su conocimiento propio, que no es lo que " +
                    "querés para tu cátedra. Subí los apuntes, las diapositivas o la guía de ejercicios.",
                actionLabel = "Subir el primer material",
                onAction = { vm.error = "La subida de archivos llega en el próximo hito." },
            )
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(top = 12.dp),
        ) {
            items(materials, key = { it.id }) { material ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Filled.MenuBook, contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(material.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${material.kind.label} · ${material.textLength} caracteres" +
                                    if (material.warning.isNotBlank()) " · ${material.warning}" else "",
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
 * Editor de la cátedra.
 *
 * Por ahora muestra los datos y el prompt; la edición completa (con autoguardado
 * y publicación) llega en el próximo hito.
 */
@Composable
fun TeacherCourseScreen(vm: KitoruViewModel, courseId: String) {
    val course = vm.allCourses.firstOrNull { it.id == courseId }
    if (course == null) {
        vm.back()
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(course.name, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "${course.code} · ${course.term}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(top = 16.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Datos de la cátedra", style = MaterialTheme.typography.titleSmall)
                        LabelledValue("Nombre", course.name)
                        LabelledValue("Código", course.code)
                        LabelledValue("Cursada", course.term)
                        LabelledValue("Código de inscripción", course.joinCode.ifBlank { "(sin código)" })
                        LabelledValue("Fragmentos recuperados", course.topK.toString())
                        LabelledValue(
                            "IA local permitida",
                            if (course.allowLocalModels) "Sí" else "No",
                        )
                        LabelledValue(
                            "Analítica habilitada",
                            if (course.analyticsEnabled) "Sí" else "No",
                        )
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Instrucciones del tutor", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = course.systemPrompt.ifBlank {
                                "Usando las instrucciones por defecto de Kitoru.\n\n" +
                                    kitoru.ui.chat.ChatEngine.DEFAULT_SYSTEM_PROMPT
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Modelo de la cátedra", style = MaterialTheme.typography.titleSmall)
                        LabelledValue("Modo", course.modelPolicy.mode.label)
                        LabelledValue("Servidor", course.modelPolicy.remoteBaseUrl.ifBlank { "(sin configurar)" })
                        LabelledValue("Modelo", course.modelPolicy.modelName.ifBlank { "(sin configurar)" })
                        LabelledValue("Temperatura", course.modelPolicy.temperature.toString())
                        LabelledValue("Límite de tokens", course.modelPolicy.maxTokens.toString())
                        LabelledValue(
                            "El alumno puede modificarla",
                            if (course.modelPolicy.allowStudentOverride) "Sí" else "No",
                        )
                        LabelledValue(
                            "Campos bloqueados",
                            if (course.modelPolicy.lockedFields.isEmpty()) {
                                "Ninguno"
                            } else {
                                course.modelPolicy.lockedFields.joinToString(", ") { it.label }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Un par rótulo/valor. */
@Composable
private fun LabelledValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
