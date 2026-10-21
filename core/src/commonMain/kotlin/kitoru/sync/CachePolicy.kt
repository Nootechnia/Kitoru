package kitoru.sync

/**
 * Política de caché de un recurso, vista desde el cliente.
 *
 * - [ALWAYS]: la copia local **es** la copia. No se borra automáticamente y la
 *   app funciona completa sin ella.
 * - [IF_ONLINE]: la copia local es una **réplica**. Se puede tirar y
 *   recuperar; mientras no haya red, es lo único que hay.
 * - [NEVER]: no se guarda copia local (o se guarda fuera del alcance de la
 *   caché: en el almacén de credenciales, o no se guarda).
 */
enum class CachePolicy(val label: String) {

    /** Siempre local. */
    ALWAYS("Siempre local"),

    /** Réplica: se recupera del servidor cuando hay red. */
    IF_ONLINE("Si hay red"),

    /** Nunca se guarda. */
    NEVER("Nunca"),
}

/**
 * Qué clase de recurso se está cacheando.
 */
enum class CacheKind {

    /** Archivos originales del corpus (PDF, diapositivas, apuntes). */
    MATERIAL_FILES,

    /** Texto extraído y fragmentos (chunks) del corpus (derivado). */
    MATERIAL_INDEX,

    /** Avisos de la cátedra. */
    ANNOUNCEMENTS,

    /** Tareas, enunciados y rúbricas. */
    ASSIGNMENTS,

    /** Configuración de la cátedra: `Course`, `ModelPolicy` y prompt. */
    COURSE_CONFIG,

    /** Catálogo de inscripciones. */
    ENROLLMENTS,

    /** Hilos y mensajes del propio usuario. */
    OWN_THREADS,

    /** Hilos y mensajes de otros (la vista del docente). */
    OTHER_THREADS,

    /** Entregas del alumno. */
    SUBMISSIONS,

    /** Lecturas de avisos. */
    READS,

    /** Secretos: la API key y los tokens. */
    SECRETS,

    /** Catálogo de modelos del servidor. */
    MODEL_CATALOG,
}

/**
 * La tabla de políticas de caché: **un solo lugar** decide qué se guarda.
 *
 * La regla de dedo: si el recurso es la **materia prima** de una función que
 * tiene que funcionar sin red → ALWAYS. Si es una **réplica** de algo que el
 * servidor puede volver a mandar → IF_ONLINE. Si es un **secreto** o algo
 * efímero → NEVER.
 */
object CachePolicies {

    /** La tabla completa. */
    val byKind: Map<CacheKind, CachePolicy> = mapOf(
        // El corpus es la materia prima de la búsqueda: sin red, sin material.
        CacheKind.MATERIAL_FILES to CachePolicy.ALWAYS,
        CacheKind.MATERIAL_INDEX to CachePolicy.ALWAYS,

        // La configuración de la cátedra y el padrón son lo último que se
        // conoce: la app tiene que poder abrir la cátedra sin red.
        CacheKind.COURSE_CONFIG to CachePolicy.ALWAYS,
        CacheKind.ENROLLMENTS to CachePolicy.ALWAYS,

        // El origen local: la verdad está en el dispositivo.
        CacheKind.OWN_THREADS to CachePolicy.ALWAYS,
        CacheKind.SUBMISSIONS to CachePolicy.ALWAYS,
        CacheKind.READS to CachePolicy.ALWAYS,

        // Réplicas de lectura: se pueden volver a bajar.
        CacheKind.ANNOUNCEMENTS to CachePolicy.IF_ONLINE,
        CacheKind.ASSIGNMENTS to CachePolicy.IF_ONLINE,
        CacheKind.OTHER_THREADS to CachePolicy.IF_ONLINE,

        // Nunca en un store JSON.
        CacheKind.SECRETS to CachePolicy.NEVER,
        CacheKind.MODEL_CATALOG to CachePolicy.NEVER,
    )

    /**
     * La política de un recurso.
     *
     * Un tipo que no está en la tabla se trata como [CachePolicy.IF_ONLINE]: es
     * una réplica y el servidor la puede volver a mandar.
     */
    fun policyOf(kind: CacheKind): CachePolicy = byKind[kind] ?: CachePolicy.IF_ONLINE
}

/**
 * El modo de trabajo de la app.
 *
 * Es **derivado** del estado de la conexión y de la cola: no puede quedar
 * desincronizado con la realidad.
 */
enum class SyncMode(val label: String) {

    /** Hay conexión: se lee y se escribe contra el servidor. */
    ONLINE("Conectado"),

    /**
     * No hay conexión pero hay cosas en la cola: la app sigue funcionando con
     * lo local y lo que se escriba queda en la cola, para subirlo sola.
     */
    READ_ONLY("Sin conexión (sólo lectura)"),

    /** No hay conexión y no hay nada en la cola: la app es puramente local. */
    OFFLINE("Sin conexión"),
}

/**
 * Deriva el modo de trabajo.
 *
 * @param isOnline lo que dice el monitor de conexión.
 * @param pending cuántas entradas hay en la cola de salida.
 */
fun deriveSyncMode(isOnline: Boolean, pending: Int): SyncMode = when {
    isOnline -> SyncMode.ONLINE
    pending > 0 -> SyncMode.READ_ONLY
    else -> SyncMode.OFFLINE
}
