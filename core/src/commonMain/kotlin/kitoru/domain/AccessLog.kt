package kitoru.domain

import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kitoru.platform.KitoruPaths
import kitoru.platform.nowMillis
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Resultado de un acceso auditado.
 */
@Serializable
enum class AccessOutcome(val label: String) {
    /** El acceso se permitió. */
    ALLOWED("Permitido"),

    /** El acceso se denegó (sirve para detectar bugs de permisos o abuso). */
    DENIED("Denegado"),
}

/**
 * En qué se basó la decisión de un acceso.
 *
 * Es lo que hace auditable el log: no alcanza con decir "se permitió", hay
 * que poder mostrar **por qué**: el rol que lo pidió y el consentimiento que
 * había sobre los datos del objetivo.
 *
 * @property role rol del actor en la cátedra.
 * @property consent visibilidad efectiva que se tenía de los datos del
 *   objetivo (`null` si el acceso no era sobre datos de un alumno).
 * @property requirement requisito que faltaba cuando el acceso fue denegado
 *   por una condición.
 */
@Serializable
data class AccessBasis(
    /** Rol del actor en la cátedra. */
    val role: CourseRole? = null,

    /** Visibilidad efectiva sobre los datos del objetivo. */
    val consent: ChatVisibility? = null,

    /** Requisito que faltó (si el acceso fue denegado por condición). */
    val requirement: String = "",
)

/**
 * Una entrada del registro de accesos: quién, qué, cuándo, a quién y con qué
 * resultado.
 *
 * Es **append-only**: las entradas se agregan al final y nunca se editan. Si
 * se supera el tope de entradas se descartan las más viejas.
 */
@Serializable
data class AccessLogEntry(
    /** Identificador único de la entrada. */
    val id: String,

    /** Momento del acceso (epoch en milisegundos). */
    val at: Long,

    /** Quién accedió (id de usuario). */
    val actorId: String,

    /** Quién accedió (nombre visible). */
    val actorName: String,

    /** Cátedra en la que se accedió. */
    val courseId: String,

    /** Qué se intentó hacer. */
    val capability: Capability,

    /** De quién son los datos consultados (id de usuario). */
    val targetUserId: String = "",

    /** De quién son los datos consultados (nombre visible). */
    val targetUserName: String = "",

    /** Detalle libre (ej. "hilo 42", "material m-7", "exportación CSV"). */
    val detail: String = "",

    /** Resultado del acceso. */
    val outcome: AccessOutcome = AccessOutcome.ALLOWED,

    /** En qué se basó la decisión. */
    val basis: AccessBasis = AccessBasis(),
)

/**
 * Registro de accesos: append-only, persistido en JSON y con tope de entradas.
 *
 * Existe para que quede constancia de qué leen los docentes de los chats de
 * los alumnos: el titular ve si un ayudante está leyendo conversaciones y
 * el propio docente deja rastro de lo que abrió.
 *
 * Sólo se registran las capacidades con [Capability.audited] en `true`: el
 * ruido tapa la señal.
 *
 * El log es **local a la instalación** (el archivo del docente).
 */
class AccessLog(
    private val store: JsonListStore<AccessLogEntry>,
    /** Tope de entradas: al superarlo se descartan las más viejas. */
    private val maxEntries: Int = MAX_ENTRIES,
    private val clock: () -> Long = { nowMillis() },
    private val idFactory: () -> String = { defaultId() },
) {

    /**
     * Agrega una entrada al final del registro.
     *
     * Append-only: las entradas anteriores no se tocan. Si se supera el tope
     * de [maxEntries], se descartan las más viejas.
     */
    suspend fun append(entry: AccessLogEntry): AccessLogEntry {
        store.update { list ->
            val actualizada = list + entry
            if (actualizada.size > maxEntries) actualizada.takeLast(maxEntries) else actualizada
        }
        return entry
    }

    /**
     * Registra un acceso si la capacidad es auditable.
     *
     * @param context contexto de la consulta (define el actor y el objetivo).
     * @param capability capacidad que se ejerció.
     * @param target inscripción de cuyos datos se trata, si aplica.
     * @param detail detalle libre (ej. "hilo 42").
     * @param outcome resultado del acceso.
     * @param requirement requisito que faltó, si el acceso fue denegado.
     * @return la entrada agregada, o `null` si la capacidad no es auditable.
     */
    suspend fun record(
        context: PermissionContext,
        capability: Capability,
        target: Enrollment? = null,
        detail: String = "",
        outcome: AccessOutcome = AccessOutcome.ALLOWED,
        requirement: String = "",
    ): AccessLogEntry? {
        if (!capability.audited) return null
        val duenno = target ?: context.target?.owner
        val entrada = AccessLogEntry(
            id = idFactory(),
            at = clock(),
            actorId = context.actor.userId,
            actorName = context.actor.displayName,
            courseId = context.course.id,
            capability = capability,
            targetUserId = duenno?.userId.orEmpty(),
            targetUserName = duenno?.displayName.orEmpty(),
            detail = detail,
            outcome = outcome,
            basis = AccessBasis(
                role = context.actor.role,
                consent = context.target?.let { context.effectiveVisibility() },
                requirement = requirement,
            ),
        )
        append(entrada)
        return entrada
    }

    /**
     * Entradas del registro, de la más reciente a la más antigua.
     *
     * @param courseId filtra por cátedra; `null` trae todas.
     */
    suspend fun list(courseId: String? = null): List<AccessLogEntry> =
        store.read()
            .filter { courseId == null || it.courseId == courseId }
            .sortedByDescending { it.at }

    /** Cantidad de entradas guardadas. */
    suspend fun size(): Int = store.read().size

    /** Vacía el registro (lo usa `APP_RESET`). */
    suspend fun clear() {
        store.write(emptyList())
    }

    companion object {
        /** Tope de entradas: con ~10 accesos por día alcanza para años. */
        const val MAX_ENTRIES = 5000

        /** Nombre del archivo del registro dentro de la carpeta de configuración. */
        const val FILE_NAME = "access-log.json"

        /** Log sobre un archivo (por defecto, `config/access-log.json`). */
        fun on(
            fs: FileSystem,
            path: FilePath = KitoruPaths.configDir.resolve(FILE_NAME),
            maxEntries: Int = MAX_ENTRIES,
            clock: () -> Long = { nowMillis() },
            idFactory: () -> String = { defaultId() },
        ): AccessLog = AccessLog(
            store = JsonListStore(
                fs = fs,
                path = path,
                itemSerializer = AccessLogEntry.serializer(),
            ),
            maxEntries = maxEntries,
            clock = clock,
            idFactory = idFactory,
        )

        /** Identificador de entrada legible y suficientemente único. */
        private fun defaultId(): String =
            "acc-${nowMillis().toString(36)}-${Random.nextInt(0, 0x100000).toString(36)}"
    }
}
