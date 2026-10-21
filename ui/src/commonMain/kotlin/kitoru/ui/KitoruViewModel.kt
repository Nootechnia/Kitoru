package kitoru.ui

import kitoru.domain.ChatVisibility
import kitoru.domain.Course
import kitoru.domain.CourseRepository
import kitoru.domain.CourseRole
import kitoru.domain.Enrollment
import kitoru.domain.EnrollmentRepository
import kitoru.domain.ProfileRepository
import kitoru.domain.UserProfile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Qué está mirando el usuario ahora.
 *
 * La navegación es una pila manual (no `navigation-compose`, que no es
 * multiplataforma en Compose 1.7.3). Cada rol tiene su propio árbol.
 */
sealed class AppScreen {
    /** Elección de rol: alumno o docente. */
    data object RolePicker : AppScreen()

    /** Home del alumno: sus cátedras, avisos, tareas. */
    data object StudentHome : AppScreen()

    /** Chat del alumno dentro de una cátedra. */
    data class StudentChat(val courseId: String, val threadId: String?) : AppScreen()

    /** Configuración del modelo del alumno. */
    data class StudentModel(val courseId: String) : AppScreen()

    /** Materiales de la cátedra vistos por el alumno. */
    data class StudentMaterials(val courseId: String) : AppScreen()

    /** Panel del docente: sus cátedras. */
    data object TeacherHome : AppScreen()

    /** Editor de la cátedra. */
    data class TeacherCourse(val courseId: String) : AppScreen()

    /** Analítica de la cátedra. */
    data class TeacherAnalytics(val courseId: String) : AppScreen()

    /** Materiales de la cátedra vistos por el docente. */
    data class TeacherMaterials(val courseId: String) : AppScreen()

    /** Chats de los alumnos. */
    data class TeacherChats(val courseId: String) : AppScreen()

    /** Alumnos inscritos. */
    data class TeacherStudents(val courseId: String) : AppScreen()
}

/**
 * Estado raíz de la app.
 *
 * Es el mismo patrón de FunshiEngineAI: una clase plana con `mutableStateOf`
 * que la UI lee directamente. No es `androidx.lifecycle.ViewModel` (no
 * existe en el target común) sino un objeto que la app crea y destruye.
 *
 * Los repositorios se inyectan para que los tests puedan usar dobles.
 */
class KitoruViewModel(
    internal val courses: CourseRepository,
    internal val enrollments: EnrollmentRepository,
    internal val profiles: ProfileRepository,
    internal val threads: kitoru.domain.ThreadRepository =
        kitoru.domain.FileThreadRepository(kitoru.platform.systemFileSystem()),
    internal val messages: kitoru.domain.MessageRepository =
        kitoru.domain.FileMessageRepository(kitoru.platform.systemFileSystem()),
    internal val materials: kitoru.domain.MaterialRepository =
        kitoru.domain.FileMaterialRepository(kitoru.platform.systemFileSystem()),
    /**
     * Scope de la app.
     *
     * Se inyecta porque `Dispatchers.Main` no sirve en un test de ViewModel:
     * en el escritorio es el dispatcher de Swing, así que el test avanzaría
     * antes de que la corrutina corra (tests *flaky* por construcción). Los
     * tests pasan su propio scope, con el dispatcher virtual del test.
     */
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    /**
     * Reloj de la app.
     *
     * Los tests inyectan uno fijo: ningún test depende de la hora real.
     */
    internal val clock: Clock = SystemClock,
) {

    // ---- Identidad ----
    var profile by mutableStateOf<UserProfile?>(null)
        internal set

    /** Inscripciones del usuario actual (determinan su rol en cada cátedra). */
    var myEnrollments by mutableStateOf<List<Enrollment>>(emptyList())
        internal set

    // ---- Navegación ----
    var screen by mutableStateOf<AppScreen>(AppScreen.RolePicker)
        internal set

    var status by mutableStateOf("")
        internal set

    var error by mutableStateOf("")
        internal set

    /** Cátedra abierta ahora mismo (la que se está viendo). */
    var activeCourseId by mutableStateOf<String?>(null)
        internal set

    // ---- Cátedras ----
    /** Cátedras donde el usuario es docente (titular o ayudante). */
    var teachingCourses by mutableStateOf<List<Course>>(emptyList())
        internal set

    /** Cátedras donde el usuario es alumno. */
    var learningCourses by mutableStateOf<List<Course>>(emptyList())
        internal set

    /** Todas las cátedras conocidas (para el caso de una cátedra sin inscripción aún). */
    var allCourses by mutableStateOf<List<Course>>(emptyList())
        internal set

    /**
     * Cuándo se recargaron los datos por última vez.
     *
     * Sale del reloj inyectado ([clock]), no del reloj del sistema: con un
     * reloj fijo el test sabe exactamente qué marca espera.
     */
    var lastRefreshAt by mutableStateOf(0L)
        internal set

    init {
        refresh()
    }

    /** Recarga todo desde los repositorios. */
    fun refresh() {
        scope.launch {
            runCatching {
                val all = courses.list()
                val mine = enrollments.listForUser(profile?.id.orEmpty())
                allCourses = all
                myEnrollments = mine
                teachingCourses = all.filter { course ->
                    mine.any { it.courseId == course.id && it.role.canDesign }
                }
                learningCourses = all.filter { course ->
                    mine.any { it.courseId == course.id && it.role == CourseRole.ALUMNO }
                }
                lastRefreshAt = clock.nowMillis()
            }.onFailure { error = "No se pudieron cargar las cátedras: ${it.message}" }
        }
    }

    /** Cambia el rol activo y entra a la home correspondiente. */
    fun goToRole(role: AppScreen) {
        screen = role
        error = ""
    }

    /** Navega a una pantalla. */
    fun go(target: AppScreen) {
        screen = target
        error = ""
    }

    /** Vuelve a la pantalla anterior. */
    fun back() {
        screen = when (screen) {
            is AppScreen.StudentChat -> AppScreen.StudentHome
            is AppScreen.StudentModel, is AppScreen.StudentMaterials -> AppScreen.StudentHome
            is AppScreen.TeacherCourse,
            is AppScreen.TeacherAnalytics,
            is AppScreen.TeacherMaterials,
            is AppScreen.TeacherChats,
            is AppScreen.TeacherStudents,
            -> AppScreen.TeacherHome
            else -> screen
        }
    }

    /** Abre una cátedra y remembers cuál es. */
    fun openCourse(courseId: String, target: AppScreen) {
        activeCourseId = courseId
        screen = target
    }

    /** La cátedra activa (o null). */
    val activeCourse: Course?
        get() = allCourses.firstOrNull { it.id == activeCourseId }

    /** El rol del usuario en la cátedra activa (o null si no está inscrito). */
    val activeRole: CourseRole?
        get() = myEnrollments.firstOrNull { it.courseId == activeCourseId }?.role

    /** La inscripción del usuario en la cátedra activa. */
    val activeEnrollment: Enrollment?
        get() = myEnrollments.firstOrNull { it.courseId == activeCourseId }

    /**
     * Qué ve el docente de los chats del usuario en la cátedra activa.
     *
     * Lo decide la inscripción ([ChatVisibility]); es la regla que hace que
     * un alumno con IA local pueda ocultar sus conversaciones.
     */
    val activeVisibility: ChatVisibility
        get() = activeEnrollment?.visibility ?: ChatVisibility.NONE

    /** Crea (o actualiza) el perfil local del usuario. */
    fun setProfile(id: String, displayName: String) {
        scope.launch {
            val existing = profiles.get(id)
            val profile = existing?.copy(displayName = displayName)
                ?: UserProfile(id = id, displayName = displayName)
            profiles.save(profile)
            this@KitoruViewModel.profile = profile
            refresh()
        }
    }

    /** Borra el error mostrado. */
    fun clearError() {
        error = ""
    }

    /** Cierra la app: cancela los jobs en curso. */
    fun shutdown() {
        scope.cancel()
    }

    // ---- Datos de una cátedra (para las pantallas que los necesitan) ----

    /** Inscripciones de una cátedra. */
    suspend fun enrollmentsFor(courseId: String): List<Enrollment> =
        enrollments.listForCourse(courseId)

    /** Hilos de una cátedra. */
    suspend fun threadsFor(courseId: String): List<kitoru.domain.ChatThread> =
        threads.listForCourse(courseId)

    /** Mensajes de una cátedra. */
    suspend fun messagesFor(courseId: String): List<kitoru.domain.ChatMessage> =
        messages.listForCourse(courseId)

    /** Materiales de una cátedra. */
    suspend fun materialsFor(courseId: String): List<kitoru.domain.Material> =
        materials.listForCourse(courseId)
}
