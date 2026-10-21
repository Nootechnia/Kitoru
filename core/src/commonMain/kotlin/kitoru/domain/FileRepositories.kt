package kitoru.domain

import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kitoru.platform.KitoruPaths

/** Base común: resuelve la ruta del archivo JSON de cada tipo. */
abstract class FileBackedRepository<T : Any>(
    private val fs: FileSystem,
    private val fileName: String,
    itemSerializer: kotlinx.serialization.KSerializer<T>,
) {
    /**
     * El store se crea **perezoso**: la ruta depende de
     * [kitoru.platform.KitoruPaths.dataDir], que cada plataforma fija al
     * arrancar. Si se construyera el store en el constructor, la ruta se
     * calcularía antes de que la app fijara la carpeta de datos.
     */
    private val store: JsonListStore<T> by lazy {
        JsonListStore<T>(fs, configFile(fileName), itemSerializer)
    }

    protected suspend fun all(): List<T> = store.read()

    protected suspend fun put(item: T) = store.update { list ->
        val without = list.filterNot { sameId(it, item) }
        without + item
    }

    protected suspend fun removeById(id: String) = store.update { list ->
        list.filterNot { idOf(it) == id }
    }

    /**
     * Borra los elementos que cumplen la condición.
     *
     * Hace falta cuando el borrado no es por id (por ejemplo, borrar los
     * mensajes de un hilo: el id del mensaje no tiene nada que ver con el id
     * del hilo).
     */
    protected suspend fun removeWhere(predicate: (T) -> Boolean) = store.update { list ->
        list.filterNot(predicate)
    }

    /** ¿Son el mismo elemento? Por defecto, por id. */
    protected open fun sameId(a: T, b: T): Boolean = idOf(a) == idOf(b)

    /** Id del elemento. */
    protected abstract fun idOf(item: T): String

    companion object {
        fun configFile(name: String): FilePath = KitoruPaths.configDir.resolve(name)
    }
}

class FileCourseRepository(fs: FileSystem) : FileBackedRepository<Course>(
    fs, "courses.json", Course.serializer(),
), CourseRepository {

    override suspend fun list(): List<Course> = all().sortedBy { it.code.ifBlank { it.name } }

    override suspend fun get(id: String): Course? = all().firstOrNull { it.id == id }

    override suspend fun save(course: Course) = put(course)

    override suspend fun delete(id: String) {
        removeById(id)
    }

    override fun idOf(item: Course): String = item.id
}

class FileEnrollmentRepository(fs: FileSystem) : FileBackedRepository<Enrollment>(
    fs, "enrollments.json", Enrollment.serializer(),
), EnrollmentRepository {

    override suspend fun listForCourse(courseId: String): List<Enrollment> =
        all().filter { it.courseId == courseId }

    override suspend fun listForUser(userId: String): List<Enrollment> =
        all().filter { it.userId == userId }

    override suspend fun get(id: String): Enrollment? = all().firstOrNull { it.id == id }

    override suspend fun save(enrollment: Enrollment) = put(enrollment)

    override suspend fun delete(id: String) = removeById(id)

    override fun idOf(item: Enrollment): String = item.id
}

class FileMaterialRepository(fs: FileSystem) : FileBackedRepository<Material>(
    fs, "materials.json", Material.serializer(),
), MaterialRepository {

    override suspend fun listForCourse(courseId: String): List<Material> =
        all().filter { it.courseId == courseId }.sortedBy { it.uploadedAt }

    override suspend fun get(id: String): Material? = all().firstOrNull { it.id == id }

    override suspend fun save(material: Material) = put(material)

    override suspend fun delete(id: String) = removeById(id)

    override fun idOf(item: Material): String = item.id
}

class FileThreadRepository(fs: FileSystem) : FileBackedRepository<ChatThread>(
    fs, "threads.json", ChatThread.serializer(),
), ThreadRepository {

    override suspend fun listForCourse(courseId: String): List<ChatThread> =
        all().filter { it.courseId == courseId }.sortedByDescending { it.updatedAt }

    override suspend fun listForUser(courseId: String, userId: String): List<ChatThread> =
        all().filter { it.courseId == courseId && it.authorId == userId }
            .sortedByDescending { it.updatedAt }

    override suspend fun get(id: String): ChatThread? = all().firstOrNull { it.id == id }

    override suspend fun save(thread: ChatThread) = put(thread)

    override suspend fun delete(id: String) = removeById(id)

    override fun idOf(item: ChatThread): String = item.id
}

class FileMessageRepository(fs: FileSystem) : FileBackedRepository<ChatMessage>(
    fs, "messages.json", ChatMessage.serializer(),
), MessageRepository {

    override suspend fun listForThread(threadId: String): List<ChatMessage> =
        all().filter { it.threadId == threadId }.sortedBy { it.createdAt }

    override suspend fun listForCourse(courseId: String): List<ChatMessage> {
        // Los mensajes se guardan por hilo; para listar por cátedra hay que
        // cruzar con los hilos. El repositorio no depende del de hilos, así
        // que se filtra por el prefijo del id del hilo, que lleva el id de
        // cátedra: "curso|hilo".
        return all().filter { it.threadId.startsWith("$courseId|") }.sortedBy { it.createdAt }
    }

    override suspend fun save(message: ChatMessage) = put(message)

    /**
     * Borra los mensajes del hilo.
     *
     * Los mensajes se identifican por su propio id, así que borrar "por id"
     * con el id del hilo no borraba nada: hay que filtrar por el hilo al que
     * pertenecen.
     */
    override suspend fun delete(threadId: String) = removeWhere { it.threadId == threadId }

    override fun sameId(a: ChatMessage, b: ChatMessage): Boolean = a.id == b.id

    override fun idOf(item: ChatMessage): String = item.id
}

class FileProfileRepository(fs: FileSystem) : FileBackedRepository<UserProfile>(
    fs, "profiles.json", UserProfile.serializer(),
), ProfileRepository {

    override suspend fun get(id: String): UserProfile? = all().firstOrNull { it.id == id }

    override suspend fun list(): List<UserProfile> = all()

    override suspend fun save(profile: UserProfile) = put(profile)

    override fun idOf(item: UserProfile): String = item.id
}
