package kitoru.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kitoru.ui.student.StudentHomeScreen
import kitoru.ui.teacher.TeacherHomeScreen

/**
 * Raíz de la app de Kitoru.
 *
 * Es el punto donde se decide qué se ve según el rol y la pantalla actual.
 * El rol **no es un flag**: se deriva de las inscripciones (una persona
 * puede ser titular en una cátedra y alumno en otra), que es lo que
 * permite que un ayudante use la misma app.
 */
@Composable
fun KitoruApp(vm: KitoruViewModel) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (val screen = vm.screen) {
            AppScreen.RolePicker -> RolePickerScreen(vm)
            AppScreen.StudentHome -> StudentHomeScreen(vm)
            is AppScreen.StudentChat ->
                kitoru.ui.chat.ChatScreen(vm, screen.courseId, screen.threadId)
            is AppScreen.StudentModel ->
                kitoru.ui.student.StudentModelScreen(vm, screen.courseId)
            is AppScreen.StudentMaterials ->
                kitoru.ui.student.StudentMaterialsScreen(vm, screen.courseId)
            AppScreen.TeacherHome -> TeacherHomeScreen(vm)
            is AppScreen.TeacherCourse ->
                kitoru.ui.teacher.TeacherCourseScreen(vm, screen.courseId)
            is AppScreen.TeacherAnalytics ->
                kitoru.ui.teacher.TeacherAnalyticsScreen(vm, screen.courseId)
            is AppScreen.TeacherMaterials ->
                kitoru.ui.teacher.TeacherMaterialsScreen(vm, screen.courseId)
            is AppScreen.TeacherChats ->
                kitoru.ui.teacher.TeacherChatsScreen(vm, screen.courseId)
            is AppScreen.TeacherStudents ->
                kitoru.ui.teacher.TeacherStudentsScreen(vm, screen.courseId)
        }
    }
}

/**
 * Primera pantalla: elegir el rol.
 *
 * Se muestran los dos caminos y, debajo, las cátedras que el usuario ya
 * tiene en cada rol (para que un ayudante que también es alumno no tenga
 * que volver a elegir cada vez).
 */
@Composable
private fun RolePickerScreen(vm: KitoruViewModel) {
    var joining by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Kitoru",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "El aula con IA: el material de la cátedra, un tutor que cita sus fuentes, " +
                "y el docente puede ver la calidad de las respuestas.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            joining -> JoinCoursePane(vm) { joining = false }
            creating -> CreateCoursePane(vm) { creating = false }
            else -> Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { joining = true },
                    modifier = Modifier.size(width = 320.dp, height = 56.dp),
                ) {
                    Icon(Icons.Filled.Person, contentDescription = null)
                    Text("  Soy alumno", style = MaterialTheme.typography.titleMedium)
                }

                OutlinedButton(
                    onClick = { creating = true },
                    modifier = Modifier.size(width = 320.dp, height = 56.dp),
                ) {
                    Icon(Icons.Filled.MenuBook, contentDescription = null)
                    Text("  Soy docente", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        if (!joining && !creating &&
            (vm.learningCourses.isNotEmpty() || vm.teachingCourses.isNotEmpty())
        ) {
            Card(modifier = Modifier.padding(top = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tus cátedras", style = MaterialTheme.typography.titleSmall)
                    vm.learningCourses.forEach {
                        Text("· ${it.shortName} (alumno)", style = MaterialTheme.typography.bodySmall)
                    }
                    vm.teachingCourses.forEach {
                        Text("· ${it.shortName} (docente)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * Panel de inscripción del alumno: el código que le dio su docente.
 */
@Composable
private fun JoinCoursePane(vm: KitoruViewModel, onCancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    Card(modifier = Modifier.size(width = 380.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Unirme a una cátedra", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase() },
                label = { Text("Código de inscripción") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Cómo te ven tus docentes") },
                supportingText = {
                    Text("Podés usar un apodo: no hace falta tu nombre real.")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (vm.error.isNotEmpty()) {
                Text(
                    text = vm.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.joinCourse(code, name) },
                    enabled = code.isNotBlank(),
                ) { Text("Unirme") }
                TextButton(onClick = onCancel) { Text("Volver") }
            }
        }
    }
}

/**
 * Panel de creación de cátedra del docente.
 */
@Composable
private fun CreateCoursePane(vm: KitoruViewModel, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var term by remember { mutableStateOf("") }

    Card(modifier = Modifier.size(width = 380.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Crear una cátedra", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nombre de la materia") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Código de la materia") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = term,
                onValueChange = { term = it },
                label = { Text("Cursada") },
                placeholder = { Text("2026-1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (vm.error.isNotEmpty()) {
                Text(
                    text = vm.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.createCourse(name, code, term) },
                    enabled = name.isNotBlank(),
                ) { Text("Crear") }
                TextButton(onClick = onCancel) { Text("Volver") }
            }
        }
    }
}
