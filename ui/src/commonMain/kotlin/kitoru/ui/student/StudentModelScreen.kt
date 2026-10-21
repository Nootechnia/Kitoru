package kitoru.ui.student

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
import androidx.compose.material3.OutlinedTextField
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
import kitoru.domain.LockedField
import kitoru.domain.Material
import kitoru.domain.ModelMode
import kitoru.ui.KitoruViewModel

/**
 * Configuración del modelo del alumno.
 *
 * Es la pantalla de la **libertad**: la cátedra pone la base y el alumno
 * puede ajustarla, salvo en los campos bloqueados (la clave, por ejemplo, la
 * provee la institución).
 */
@Composable
fun StudentModelScreen(vm: KitoruViewModel, courseId: String) {
    val course = vm.allCourses.firstOrNull { it.id == courseId }
    if (course == null) {
        vm.back()
        return
    }

    val base = course.modelPolicy
    var mode by remember(courseId) { mutableStateOf(base.mode) }
    var temperature by remember(courseId) { mutableStateOf(base.temperature.toString()) }
    var maxTokens by remember(courseId) { mutableStateOf(base.maxTokens.toString()) }
    var topK by remember(courseId) { mutableStateOf(base.topK.toString()) }

    val locked = base.lockedFields

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Ajustar el modelo", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Estos son los valores que tu cátedra propone. Podés cambiarlos " +
                "salvo los que estén bloqueados.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Servidor de la cátedra", style = MaterialTheme.typography.titleSmall)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ModeOption("Servidor de la cátedra", mode == ModelMode.REMOTE) {
                                mode = ModelMode.REMOTE
                            }
                            ModeOption("IA en mi dispositivo", mode == ModelMode.LOCAL) {
                                mode = ModelMode.LOCAL
                            }
                        }
                        if (mode == ModelMode.LOCAL && !course.allowLocalModels) {
                            Text(
                                text = "Tu cátedra no permite usar IA local. " +
                                    "Podés seguir usando el servidor de la facultad.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        LabelledRow("Servidor", base.remoteBaseUrl.ifBlank { "(sin configurar)" })
                        LabelledRow("Modelo", base.modelName.ifBlank { "(sin configurar)" })
                        if (LockedField.API_KEY in locked) {
                            LabelledRow("Clave de API", "La provee tu cátedra — no se puede cambiar")
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Generación", style = MaterialTheme.typography.titleSmall)
                        if (LockedField.TEMPERATURE in locked) {
                            LabelledRow("Temperatura", "${base.temperature} (bloqueado por la cátedra)")
                        } else {
                            OutlinedTextField(
                                value = temperature,
                                onValueChange = { temperature = it },
                                label = { Text("Temperatura") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                        }
                        if (LockedField.MAX_TOKENS in locked) {
                            LabelledRow("Límite de tokens", "${base.maxTokens} (bloqueado por la cátedra)")
                        } else {
                            OutlinedTextField(
                                value = maxTokens,
                                onValueChange = { maxTokens = it },
                                label = { Text("Límite de tokens") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Recuperación del material", style = MaterialTheme.typography.titleSmall)
                        if (LockedField.TOP_K in locked) {
                            LabelledRow("Fragmentos recuperados", "${base.topK} (bloqueado por la cátedra)")
                        } else {
                            OutlinedTextField(
                                value = topK,
                                onValueChange = { topK = it },
                                label = { Text("Fragmentos recuperados") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                        }
                        Text(
                            text = "Más fragmentos = más contexto, pero respuestas más lentas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Un botón de opción de modo. */
@Composable
private fun ModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

/** Un par rótulo/valor. */
@Composable
internal fun LabelledRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Material de la cátedra visto por el alumno.
 *
 * Lo que el docente marcó como no visible no aparece.
 */
@Composable
fun StudentMaterialsScreen(vm: KitoruViewModel, courseId: String) {
    val course = vm.allCourses.firstOrNull { it.id == courseId }
    if (course == null) {
        vm.back()
        return
    }

    var materials by remember(courseId) { mutableStateOf<List<Material>>(emptyList()) }

    LaunchedEffect(courseId) {
        materials = vm.materialsFor(courseId).filter { it.visible }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Material de la cátedra", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Lo mismo que puede citar el tutor.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        if (materials.isEmpty()) {
            EmptyState(
                title = "Tu cátedra todavía no tiene material",
                body = "Cuando el docente suba los apuntes, los vas a ver acá.",
            )
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(materials, key = { it.id }) { material ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(material.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${material.kind.label} · ${material.textLength} caracteres",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
