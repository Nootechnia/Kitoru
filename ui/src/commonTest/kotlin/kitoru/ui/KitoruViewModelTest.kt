package kitoru.ui

import kitoru.domain.ChatVisibility
import kitoru.domain.Course
import kitoru.domain.CourseRepository
import kitoru.domain.CourseRole
import kitoru.domain.Enrollment
import kitoru.domain.EnrollmentRepository
import kitoru.domain.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests del ViewModel raíz.
 *
 * El ViewModel no necesita recomposición para leerse: los `mutableStateOf` se
 * leen directo. Por eso estos tests van en `commonTest` (corren en el
 * escritorio **y** en Android) y son deterministas: el scope y el reloj se
 * inyectan, así que ningún test depende de `Dispatchers.Main` ni de la hora.
 */
class KitoruViewModelTest {

    // -----------------------------------------------------------------------
    // Utilidades
    // -----------------------------------------------------------------------

    private fun curso(id: String, nombre: String = "Cátedra $id") = Course(
        id = id,
        code = "IA-$id",
        name = nombre,
        term = "2026-1",
        joinCode = "CODE$id",
    )

    private fun inscripcion(
        courseId: String,
        userId: String = "u1",
        role: CourseRole = CourseRole.ALUMNO,
        visibility: ChatVisibility = ChatVisibility.FULL,
    ) = Enrollment(
        id = "$courseId-$userId",
        courseId = courseId,
        userId = userId,
        displayName = "Persona $userId",
        role = role,
        visibility = visibility,
    )

    /**
     * Un ViewModel con el scope y el reloj del test.
     *
     * El scope usa el dispatcher virtual del `runTest`: la app usa
     * `Dispatchers.Main`, que en el escritorio es el dispatcher de Swing y
     * haría que el test avance antes de que la corrutina corra.
     */
    private fun TestScope.nuevoVm(
        courses: CourseRepository = FakeCourseRepository(),
        enrollments: EnrollmentRepository = FakeEnrollmentRepository(),
        profiles: ProfileRepository = FakeProfileRepository(),
        clock: Clock = FixedClock(1_700_000_000_000L),
    ): KitoruViewModel = KitoruViewModel(
        courses = courses,
        enrollments = enrollments,
        profiles = profiles,
        threads = FakeThreadRepository(),
        messages = FakeMessageRepository(),
        materials = FakeMaterialRepository(),
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        clock = clock,
    )

    // -----------------------------------------------------------------------
    // Las cátedras de cada rol
    // -----------------------------------------------------------------------

    @Test
    fun `un usuario sin inscripciones no ve catedras ni de docente ni de alumno`() = runTest {
        val vm = nuevoVm(courses = FakeCourseRepository(listOf(curso("c1"), curso("c2"))))

        vm.setProfile("u1", "Ana")
        advanceUntilIdle()

        assertTrue(
            vm.teachingCourses.isEmpty(),
            "Sin inscripciones no hay cátedras de docente: ${vm.teachingCourses.map { it.id }}",
        )
        assertTrue(
            vm.learningCourses.isEmpty(),
            "Sin inscripciones no hay cátedras de alumno: ${vm.learningCourses.map { it.id }}",
        )
    }

    @Test
    fun `una inscripcion como titular deja la catedra en el panel del docente`() = runTest {
        val vm = nuevoVm(
            courses = FakeCourseRepository(listOf(curso("c1"))),
            enrollments = FakeEnrollmentRepository(listOf(inscripcion("c1", role = CourseRole.TITULAR))),
        )

        vm.setProfile("u1", "Ana")
        advanceUntilIdle()

        assertEquals(listOf("c1"), vm.teachingCourses.map { it.id })
        assertTrue(
            vm.learningCourses.isEmpty(),
            "El titular no es alumno de su propia cátedra: ${vm.learningCourses.map { it.id }}",
        )
    }

    @Test
    fun `una inscripcion como alumno deja la catedra en el panel del alumno`() = runTest {
        val vm = nuevoVm(
            courses = FakeCourseRepository(listOf(curso("c1"))),
            enrollments = FakeEnrollmentRepository(listOf(inscripcion("c1", role = CourseRole.ALUMNO))),
        )

        vm.setProfile("u1", "Ana")
        advanceUntilIdle()

        assertEquals(listOf("c1"), vm.learningCourses.map { it.id })
        assertTrue(
            vm.teachingCourses.isEmpty(),
            "El alumno no es docente: ${vm.teachingCourses.map { it.id }}",
        )
    }

    @Test
    fun `un ayudante ve la catedra en el panel del docente`() = runTest {
        val vm = nuevoVm(
            courses = FakeCourseRepository(listOf(curso("c1"))),
            enrollments = FakeEnrollmentRepository(listOf(inscripcion("c1", role = CourseRole.AYUDANTE))),
        )

        vm.setProfile("u1", "Ana")
        advanceUntilIdle()

        assertEquals(listOf("c1"), vm.teachingCourses.map { it.id })
        assertTrue(vm.learningCourses.isEmpty())
    }

    @Test
    fun `el rol sale de la inscripcion: titular en una catedra y alumno en otra`() = runTest {
        val vm = nuevoVm(
            courses = FakeCourseRepository(listOf(curso("c1"), curso("c2"))),
            enrollments = FakeEnrollmentRepository(
                listOf(
                    inscripcion("c1", role = CourseRole.TITULAR),
                    inscripcion("c2", role = CourseRole.ALUMNO),
                ),
            ),
        )

        vm.setProfile("u1", "Ana")
        advanceUntilIdle()

        assertEquals(listOf("c1"), vm.teachingCourses.map { it.id })
        assertEquals(listOf("c2"), vm.learningCourses.map { it.id })
    }

    // -----------------------------------------------------------------------
    // La cátedra activa
    // -----------------------------------------------------------------------

    @Test
    fun `abrir una catedra deja activa la catedra`() = runTest {
        val vm = nuevoVm(courses = FakeCourseRepository(listOf(curso("c1"), curso("c2"))))
        vm.setProfile("u1", "Ana")
        advanceUntilIdle()

        vm.openCourse("c1", AppScreen.StudentChat("c1", null))

        assertEquals("c1", vm.activeCourseId)
        assertEquals("c1", vm.activeCourse?.id)
        assertEquals(AppScreen.StudentChat("c1", null), vm.screen)
    }

    @Test
    fun `el rol de la catedra activa sale de la inscripcion`() = runTest {
        val vm = nuevoVm(
            courses = FakeCourseRepository(listOf(curso("c1"), curso("c2"))),
            enrollments = FakeEnrollmentRepository(
                listOf(
                    inscripcion("c1", role = CourseRole.TITULAR),
                    inscripcion("c2", role = CourseRole.ALUMNO),
                ),
            ),
        )
        vm.setProfile("u1", "Ana")
        advanceUntilIdle()

        vm.openCourse("c2", AppScreen.StudentChat("c2", null))
        assertEquals(CourseRole.ALUMNO, vm.activeRole)

        vm.openCourse("c1", AppScreen.TeacherCourse("c1"))
        assertEquals(CourseRole.TITULAR, vm.activeRole)
    }

    @Test
    fun `la visibilidad sale de la inscripcion de la catedra activa`() = runTest {
        val vm = nuevoVm(
            courses = FakeCourseRepository(listOf(curso("c1"))),
            enrollments = FakeEnrollmentRepository(
                listOf(inscripcion("c1", visibility = ChatVisibility.METRICS_ONLY)),
            ),
        )
        vm.setProfile("u1", "Ana")
        advanceUntilIdle()

        vm.openCourse("c1", AppScreen.TeacherChats("c1"))

        assertEquals(ChatVisibility.METRICS_ONLY, vm.activeVisibility)
    }

    @Test
    fun `sin inscripcion la visibilidad es ninguna`() = runTest {
        val vm = nuevoVm(courses = FakeCourseRepository(listOf(curso("c1"), curso("c2"))))
        vm.setProfile("u1", "Ana")
        advanceUntilIdle()

        vm.openCourse("c2", AppScreen.TeacherChats("c2"))

        assertNull(vm.activeRole, "Sin inscripción no hay rol en la cátedra activa.")
        assertEquals(ChatVisibility.NONE, vm.activeVisibility)
    }

    // -----------------------------------------------------------------------
    // La navegación
    // -----------------------------------------------------------------------

    @Test
    fun `ir a un rol cambia de pantalla`() = runTest {
        val vm = nuevoVm()

        vm.goToRole(AppScreen.StudentHome)
        assertEquals(AppScreen.StudentHome, vm.screen)

        vm.goToRole(AppScreen.TeacherHome)
        assertEquals(AppScreen.TeacherHome, vm.screen)
    }

    @Test
    fun `volver desde el chat del alumno vuelve a la home del alumno`() = runTest {
        val vm = nuevoVm()

        vm.go(AppScreen.StudentChat("c1", null))
        vm.back()

        assertEquals(AppScreen.StudentHome, vm.screen)
    }

    @Test
    fun `volver desde cualquier pantalla del docente vuelve a la home del docente`() = runTest {
        val vm = nuevoVm()

        val pantallasDelDocente = listOf(
            AppScreen.TeacherCourse("c1"),
            AppScreen.TeacherAnalytics("c1"),
            AppScreen.TeacherMaterials("c1"),
            AppScreen.TeacherChats("c1"),
            AppScreen.TeacherStudents("c1"),
        )
        for (destino in pantallasDelDocente) {
            vm.go(destino)
            vm.back()
            assertEquals(AppScreen.TeacherHome, vm.screen, "Volver desde $destino.")
        }
    }

    @Test
    fun `volver desde la home no cambia de pantalla`() = runTest {
        val vm = nuevoVm()

        vm.goToRole(AppScreen.StudentHome)
        vm.back()

        assertEquals(AppScreen.StudentHome, vm.screen)
    }

    // -----------------------------------------------------------------------
    // Los errores
    // -----------------------------------------------------------------------

    @Test
    fun `un error al cargar deja un mensaje en espanol y no rompe la pantalla`() = runTest {
        val repos = FakeCourseRepository(listOf(curso("c1")))
        repos.fallarCon = IllegalStateException("el json está roto")
        val vm = nuevoVm(courses = repos)

        vm.setProfile("u1", "Ana")
        advanceUntilIdle()

        assertTrue(vm.error.isNotEmpty(), "El error de carga tiene que quedar a la vista.")
        assertTrue(
            vm.error.contains("cátedras"),
            "El mensaje tiene que decir qué falló: '${vm.error}'",
        )
        assertEquals(AppScreen.RolePicker, vm.screen, "La pantalla no se rompe por un error de carga.")
        assertTrue(vm.teachingCourses.isEmpty())
    }

    @Test
    fun `el error se borra al navegar`() = runTest {
        val repos = FakeCourseRepository(listOf(curso("c1")))
        repos.fallarCon = IllegalStateException("el json está roto")
        val vm = nuevoVm(courses = repos)
        vm.setProfile("u1", "Ana")
        advanceUntilIdle()
        assertTrue(vm.error.isNotEmpty())

        vm.go(AppScreen.StudentHome)

        assertEquals("", vm.error)
    }

    @Test
    fun `el error se borra al cambiar de rol`() = runTest {
        val repos = FakeCourseRepository(listOf(curso("c1")))
        repos.fallarCon = IllegalStateException("el json está roto")
        val vm = nuevoVm(courses = repos)
        vm.setProfile("u1", "Ana")
        advanceUntilIdle()
        assertTrue(vm.error.isNotEmpty())

        vm.goToRole(AppScreen.TeacherHome)

        assertEquals("", vm.error)
    }

    // -----------------------------------------------------------------------
    // El perfil y el reloj
    // -----------------------------------------------------------------------

    @Test
    fun `crear un perfil lo guarda y recarga las inscripciones`() = runTest {
        val perfiles = FakeProfileRepository()
        val vm = nuevoVm(
            enrollments = FakeEnrollmentRepository(listOf(inscripcion("c1"))),
            profiles = perfiles,
        )

        vm.setProfile("u1", "Ana Pérez")
        advanceUntilIdle()

        assertEquals("Ana Pérez", vm.profile?.displayName)
        assertEquals(listOf("u1"), perfiles.todos().map { it.id })
        assertEquals(listOf("c1"), vm.myEnrollments.map { it.courseId })
    }

    @Test
    fun `la ultima recarga se estampa con el reloj inyectado`() = runTest {
        val vm = nuevoVm(clock = FixedClock(1_700_000_000_000L))

        vm.setProfile("u1", "Ana")
        advanceUntilIdle()

        assertEquals(1_700_000_000_000L, vm.lastRefreshAt)
    }

    @Test
    fun `sin catedras la home del alumno queda vacia`() = runTest {
        val vm = nuevoVm()

        vm.setProfile("u1", "Ana")
        advanceUntilIdle()

        assertTrue(vm.allCourses.isEmpty())
        assertTrue(vm.teachingCourses.isEmpty())
        assertTrue(vm.learningCourses.isEmpty())
        assertEquals("", vm.error, "Cargar sin datos no es un error: '${vm.error}'")
    }
}
