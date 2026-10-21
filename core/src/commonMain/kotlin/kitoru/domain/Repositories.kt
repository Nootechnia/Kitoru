package kitoru.domain

/**
 * Contratos de persistencia.
 *
 * Son interfaces para que la UI y la lógica no dependan de dónde viven los
 * datos: en el milestone 1 es un archivo JSON por tipo dentro de la carpeta
 * de la app; más adelante puede ser un servidor (Kitoru-Server) sin tocar la
 * UI.
 */
interface CourseRepository {
    suspend fun list(): List<Course>
    suspend fun get(id: String): Course?
    suspend fun save(course: Course)
    suspend fun delete(id: String)
}

interface EnrollmentRepository {
    suspend fun listForCourse(courseId: String): List<Enrollment>
    suspend fun listForUser(userId: String): List<Enrollment>
    suspend fun get(id: String): Enrollment?
    suspend fun save(enrollment: Enrollment)
    suspend fun delete(id: String)
}

interface MaterialRepository {
    suspend fun listForCourse(courseId: String): List<Material>
    suspend fun get(id: String): Material?
    suspend fun save(material: Material)
    suspend fun delete(id: String)
}

interface ThreadRepository {
    suspend fun listForCourse(courseId: String): List<ChatThread>
    suspend fun listForUser(courseId: String, userId: String): List<ChatThread>
    suspend fun get(id: String): ChatThread?
    suspend fun save(thread: ChatThread)
    suspend fun delete(id: String)
}

interface MessageRepository {
    suspend fun listForThread(threadId: String): List<ChatMessage>
    suspend fun listForCourse(courseId: String): List<ChatMessage>
    suspend fun save(message: ChatMessage)
    suspend fun delete(threadId: String)
}

/** Perfil local del usuario de la app. */
interface ProfileRepository {
    suspend fun get(id: String): UserProfile?
    suspend fun list(): List<UserProfile>
    suspend fun save(profile: UserProfile)
}
