package kitoru.ui

import kitoru.domain.ChatMessage
import kitoru.domain.ChatThread
import kitoru.domain.Course
import kitoru.domain.CourseRepository
import kitoru.domain.Enrollment
import kitoru.domain.EnrollmentRepository
import kitoru.domain.Material
import kitoru.domain.MaterialRepository
import kitoru.domain.MessageRepository
import kitoru.domain.ProfileRepository
import kitoru.domain.ThreadRepository
import kitoru.domain.UserProfile

/**
 * Repositorios falsos en memoria.
 *
 * El ViewModel no sabe de disco: sabe de `CourseRepository` y compañía. Un
 * doble en memoria hace que el test sea más rápido y que el fallo diga algo
 * útil ("el repo no devolvió la cátedra" y no "el JSON estaba roto").
 *
 * Todos aceptan la lista inicial y la devuelven **copiada**, para que el test
 * no pueda mutar el estado interno por accidente. Con [fallarCon] se simula
 * el disco roto (el camino de error de la UI).
 */
class FakeCourseRepository(initial: List<Course> = emptyList()) : CourseRepository {

    private val items = initial.associateBy { it.id }.toMutableMap()

    /** Si no es null, todas las operaciones fallan con esta excepción. */
    var fallarCon: Throwable? = null

    override suspend fun list(): List<Course> {
        fallarCon?.let { throw it }
        return items.values.sortedBy { it.id }
    }

    override suspend fun get(id: String): Course? {
        fallarCon?.let { throw it }
        return items[id]
    }

    override suspend fun save(course: Course) {
        fallarCon?.let { throw it }
        items[course.id] = course
    }

    override suspend fun delete(id: String) {
        fallarCon?.let { throw it }
        items -= id
    }

    /** Todas las cátedras guardadas (para aserciones). */
    fun todas(): List<Course> = items.values.toList()
}

/** Inscripciones en memoria. */
class FakeEnrollmentRepository(initial: List<Enrollment> = emptyList()) : EnrollmentRepository {

    private val items = initial.associateBy { it.id }.toMutableMap()

    /** Si no es null, todas las operaciones fallan con esta excepción. */
    var fallarCon: Throwable? = null

    override suspend fun listForCourse(courseId: String): List<Enrollment> {
        fallarCon?.let { throw it }
        return items.values.filter { it.courseId == courseId }.sortedBy { it.id }
    }

    override suspend fun listForUser(userId: String): List<Enrollment> {
        fallarCon?.let { throw it }
        return items.values.filter { it.userId == userId }.sortedBy { it.id }
    }

    override suspend fun get(id: String): Enrollment? {
        fallarCon?.let { throw it }
        return items[id]
    }

    override suspend fun save(enrollment: Enrollment) {
        fallarCon?.let { throw it }
        items[enrollment.id] = enrollment
    }

    override suspend fun delete(id: String) {
        fallarCon?.let { throw it }
        items -= id
    }

    /** Todas las inscripciones guardadas (para aserciones). */
    fun todas(): List<Enrollment> = items.values.toList()
}

/** Perfiles en memoria. */
class FakeProfileRepository(initial: List<UserProfile> = emptyList()) : ProfileRepository {

    private val items = initial.associateBy { it.id }.toMutableMap()

    /** Si no es null, todas las operaciones fallan con esta excepción. */
    var fallarCon: Throwable? = null

    override suspend fun get(id: String): UserProfile? {
        fallarCon?.let { throw it }
        return items[id]
    }

    override suspend fun list(): List<UserProfile> {
        fallarCon?.let { throw it }
        return items.values.sortedBy { it.id }
    }

    override suspend fun save(profile: UserProfile) {
        fallarCon?.let { throw it }
        items[profile.id] = profile
    }

    /** Todos los perfiles guardados (para aserciones). */
    fun todos(): List<UserProfile> = items.values.toList()
}

/** Materiales en memoria. */
class FakeMaterialRepository(initial: List<Material> = emptyList()) : MaterialRepository {

    private val items = initial.associateBy { it.id }.toMutableMap()

    override suspend fun listForCourse(courseId: String): List<Material> =
        items.values.filter { it.courseId == courseId }.sortedBy { it.id }

    override suspend fun get(id: String): Material? = items[id]

    override suspend fun save(material: Material) {
        items[material.id] = material
    }

    override suspend fun delete(id: String) {
        items -= id
    }
}

/** Hilos de chat en memoria. */
class FakeThreadRepository(initial: List<ChatThread> = emptyList()) : ThreadRepository {

    private val items = initial.associateBy { it.id }.toMutableMap()

    override suspend fun listForCourse(courseId: String): List<ChatThread> =
        items.values.filter { it.courseId == courseId }.sortedBy { it.id }

    override suspend fun listForUser(courseId: String, userId: String): List<ChatThread> =
        items.values.filter { it.courseId == courseId && it.authorId == userId }.sortedBy { it.id }

    override suspend fun get(id: String): ChatThread? = items[id]

    override suspend fun save(thread: ChatThread) {
        items[thread.id] = thread
    }

    override suspend fun delete(id: String) {
        items -= id
    }
}

/** Mensajes de chat en memoria. */
class FakeMessageRepository(initial: List<ChatMessage> = emptyList()) : MessageRepository {

    private val items = initial.associateBy { it.id }.toMutableMap()

    override suspend fun listForThread(threadId: String): List<ChatMessage> =
        items.values.filter { it.threadId == threadId }.sortedBy { it.id }

    override suspend fun listForCourse(courseId: String): List<ChatMessage> =
        items.values.filter { it.threadId.startsWith("$courseId|") }.sortedBy { it.id }

    override suspend fun save(message: ChatMessage) {
        items[message.id] = message
    }

    override suspend fun delete(threadId: String) {
        items.keys.filter { it.startsWith(threadId) }.forEach { items -= it }
    }
}

/**
 * Reloj fijo: el tiempo no se mueve.
 *
 * Es el reloj que se inyecta en los ViewModel para que ninguna aserción
 * dependa de la hora en que corrió el test.
 */
class FixedClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
}

/**
 * Reloj que avanza un paso por cada lectura.
 *
 * Sirve para medir una latencia: la primera lectura es el arranque y la
 * segunda el final, así que la diferencia es exacta y no depende del reloj
 * del sistema.
 */
class SteppingClock(private val steps: List<Long>) : Clock {

    private var index = 0

    override fun nowMillis(): Long {
        val value = steps.getOrElse(index) { steps.lastOrNull() ?: 0L }
        index++
        return value
    }
}
