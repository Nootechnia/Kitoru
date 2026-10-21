package kitoru.ui

import kitoru.domain.Course
import kitoru.domain.CourseRole
import kitoru.domain.Enrollment
import kitoru.domain.UserProfile
import kitoru.platform.nowMillis

/**
 * Acciones de cátedra: crear, unirse e invitar.
 *
 * Son **funciones de extensión** del [KitoruViewModel], no métodos: es el
 * patrón de FunshiEngineAI (la lógica de cada área vive en su propio archivo
 * hermano y el ViewModel queda como el estado). Además permite que cada
 * archivo sea de un área y que los tests puedan ejercitar una pieza sin la
 * otra.
 */

/**
 * Crea una cátedra y convierte al usuario en su titular.
 *
 * El código de inscripción se genera si no viene: 6 caracteres de un alfabeto
 * Crockford (sin `0/1/I/O/L/U`, que se confunden al dictar o al copiar).
 */
fun KitoruViewModel.createCourse(
    name: String,
    code: String,
    term: String,
    joinCode: String = generateJoinCode(),
): Course? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
        error = "La cátedra necesita un nombre."
        return null
    }
    val userId = profile?.id ?: "docente-local"
    val course = Course(
        id = "catedra-${nowMillis()}",
        code = code.trim(),
        name = trimmed,
        term = term.trim(),
        createdAt = nowMillis(),
        joinCode = joinCode.trim().uppercase(),
    )
    val enrollment = Enrollment(
        id = "${course.id}-$userId",
        courseId = course.id,
        userId = userId,
        displayName = profile?.displayName ?: "Docente",
        role = CourseRole.TITULAR,
        joinedAt = nowMillis(),
    )
    scope.launch {
        runCatching {
            courses.save(course)
            enrollments.save(enrollment)
        }.onSuccess {
            profile = profile ?: UserProfile(id = userId, displayName = "Docente")
            refresh()
            openCourse(course.id, AppScreen.TeacherCourse(course.id))
            this@createCourse.error = ""
        }.onFailure {
            this@createCourse.error = "No se pudo crear la cátedra: ${it.message}"
        }
    }
    return course
}

/**
 * Une al usuario a una cátedra con su código de inscripción.
 *
 * Devuelve `null` si el código no existe. Si el usuario ya estaba inscrito,
 * no se duplica la inscripción.
 */
fun KitoruViewModel.joinCourse(joinCode: String, displayName: String): Course? {
    val code = joinCode.trim().uppercase()
    if (code.isEmpty()) {
        error = "Escribí el código de inscripción que te dio tu docente."
        return null
    }
    val course = allCourses.firstOrNull { it.joinCode.equals(code, ignoreCase = true) }
    if (course == null) {
        error = "No encontré ninguna cátedra con el código \"$code\". Revisá el código con tu docente."
        return null
    }
    val userId = profile?.id ?: "alumno-local"
    if (myEnrollments.any { it.courseId == course.id }) {
        openCourse(course.id, AppScreen.StudentChat(course.id, null))
        return course
    }
    val name = displayName.trim().ifEmpty { "Alumno" }
    val enrollment = Enrollment(
        id = "${course.id}-$userId",
        courseId = course.id,
        userId = userId,
        displayName = name,
        role = CourseRole.ALUMNO,
        joinedAt = nowMillis(),
    )
    scope.launch {
        runCatching {
            profiles.save(UserProfile(id = userId, displayName = name))
            enrollments.save(enrollment)
        }.onSuccess {
            profile = UserProfile(id = userId, displayName = name)
            refresh()
            openCourse(course.id, AppScreen.StudentChat(course.id, null))
            this@joinCourse.error = ""
        }.onFailure {
            this@joinCourse.error = "No se pudo anotarte a la cátedra: ${it.message}"
        }
    }
    return course
}

/**
 * Genera un código de inscripción.
 *
 * 6 caracteres del alfabeto Crockford: sin las letras que se confunden
 * (I/L, O/U) ni el 0 y el 1. Con el alfabeto de 32 caracteres son 30 bits de
 * entropía: suficiente para que un alumno no adivine el código de otra
 * cátedra probando.
 */
fun generateJoinCode(random: kotlin.random.Random = kotlin.random.Random.Default): String {
    val alphabet = "ABCDEFGHJKMNPQRSTVWXYZ23456789"
    return (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}
