package kitoru.domain

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Veredictos, áreas y ámbito
// ---------------------------------------------------------------------------

/**
 * Veredicto de la matriz rol × capacidad.
 *
 * - [ALLOWED]: permitido sin más condiciones.
 * - [CONDITIONAL]: permitido sólo si se cumple la condición intrínseca de la
 *   capacidad (la misma para todos los roles donde no está [DENIED]).
 * - [DENIED]: el rol no tiene la capacidad en esta cátedra.
 */
enum class Verdict(val label: String) {
    ALLOWED("Permitido"),
    CONDITIONAL("Condicional"),
    DENIED("Denegado"),
}

/**
 * Área de la app a la que pertenece una capacidad.
 *
 * El área sirve para armar la UI (pestañas, secciones) y para agrupar el
 * registro de accesos.
 */
enum class CapabilityArea(val label: String) {
    CATEDRA("Cátedra"),
    MATERIALES("Materiales"),
    MODELO("Modelo"),
    CHATS("Chats"),
    ANALITICA("Analítica"),
    ENROLMENTS("Inscripciones"),
    APP("Configuración de la app"),
}

/**
 * Ámbito al que pertenece una capacidad.
 *
 * - [COURSE]: se resuelve con el rol dentro de una cátedra.
 * - [APP]: no depende del rol de cátedra (el usuario local es dueño de su
 *   instalación); su veredicto real sale del estado global de la app.
 */
enum class CapabilityScope(val label: String) {
    COURSE("Cátedra"),
    APP("App"),
}

/**
 * Categoría de herramienta que el modelo puede invocar.
 *
 * El docente pone el **techo** por cátedra (qué categorías existen en el
 * chat) y el usuario concede por chat **dentro** de ese techo. El techo
 * siempre gana.
 *
 * El nombre lleva el prefijo `Permission` para no chocar con la categoría de
 * herramientas del backend (`rag.agent.ToolCategory`): cuando `:core` incorpore
 * esa enumeración, esta se puede alinear sin romper nada.
 */
enum class PermissionToolCategory(val label: String) {
    /** Herramientas sin efecto externo: siempre se ejecutan. */
    NONE("Sin permisos"),

    /** Archivos: leer y escribir archivos del dispositivo. */
    FILES("Archivos"),

    /** Web: consultar y descargar de internet. */
    WEB("Web"),

    /** MCP: herramientas de servidores MCP externos. */
    MCP("MCP"),
    ;

    companion object {
        /** Convierte el nombre persistido ("WEB") al enum; null si no existe. */
        fun fromName(name: String): PermissionToolCategory? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

// ---------------------------------------------------------------------------
// Contexto y hechos
// ---------------------------------------------------------------------------

/**
 * Estado global de la app (ámbito [CapabilityScope.APP]).
 *
 * Es el estado que **no** depende del rol de cátedra: el usuario local es
 * dueño de su instalación. Los interruptores analíticos, de auditoría y de
 * MCP son los que usa el modelo de permisos.
 */
@Serializable
data class PermissionAppSettings(
    /** Interruptor global de analítica. */
    val analyticsEnabled: Boolean = true,

    /** Registrar los accesos a datos de alumnos. */
    val auditLogEnabled: Boolean = true,

    /** La app admite servidores MCP. */
    val mcpEnabled: Boolean = false,

    /** La instalación está gestionada por la institución: los ajustes sensibles se bloquean. */
    val managed: Boolean = false,
)

/**
 * Hechos derivados de la cátedra que una condición puede necesitar.
 *
 * Viven fuera de [Course] porque el dominio todavía no los modela: cuando la
 * cátedra tenga `published` o su política de herramientas, estos hechos se
 * completan desde ahí.
 */
@Serializable
data class PermissionFacts(
    /** Si la cátedra está publicada (mientras es borrador el corpus está "en construcción"). */
    val published: Boolean = false,

    /** Cantidad de materiales cargados. */
    val materialCount: Int = 0,

    /** Materiales visibles para los alumnos. */
    val visibleMaterialCount: Int = 0,

    /** Participantes inscriptos. */
    val enrolledCount: Int = 0,

    /** Categorías de herramientas que el docente habilitó en la cátedra. */
    val toolCategories: Set<PermissionToolCategory> = emptySet(),
)

/**
 * A qué datos se está accediendo.
 *
 * Siempre se sabe de quién son (el dueño: el alumno o el docente), y
 * opcionalmente **qué** recurso concreto se está mirando.
 */
@Serializable
sealed interface PermissionTarget {

    /** Inscripción del dueño de los datos consultados. */
    val owner: Enrollment

    /**
     * Tope de visibilidad que admite la inscripción: lo máximo que el alumno
     * autorizó. El docente nunca puede verlo por encima de este tope.
     */
    val enrollmentCeiling: ChatVisibility

    /**
     * Preferencia explícita del alumno.
     *
     * `null` significa que el alumno **nunca la dejó**: en ese caso el modelo
     * falla cerrado (ver [effectiveVisibility]).
     */
    val studentPreference: ChatVisibility?

    /** Datos de un participante de la cátedra. */
    @Serializable
    data class Student(
        override val owner: Enrollment,
        override val enrollmentCeiling: ChatVisibility = ChatVisibility.FULL,
        override val studentPreference: ChatVisibility? = null,
    ) : PermissionTarget

    /** Un hilo concreto de una conversación. */
    @Serializable
    data class Thread(
        val thread: ChatThread,
        override val owner: Enrollment,
        override val enrollmentCeiling: ChatVisibility = ChatVisibility.FULL,
        override val studentPreference: ChatVisibility? = null,
    ) : PermissionTarget
}

/**
 * Contexto inmutable de una consulta de permisos.
 *
 * Es la entrada de [PermissionResolver] y de todas las [Condition]: con el
 * mismo contexto, la misma respuesta (función pura, sin disco, sin red y sin
 * reloj).
 *
 * @property course cátedra en la que se opera.
 * @property actor inscripción de quien pide el permiso (define el rol).
 * @property user usuario que actúa (la persona detrás del actor).
 * @property target a qué datos se accederá: un alumno o un hilo.
 * @property app estado global de la app.
 * @property facts hechos derivados de la cátedra.
 */
@Serializable
data class PermissionContext(
    val course: Course,
    val actor: Enrollment,
    val user: UserProfile = UserProfile(id = actor.userId, displayName = actor.displayName),
    val target: PermissionTarget? = null,
    val app: PermissionAppSettings = PermissionAppSettings(),
    val facts: PermissionFacts = PermissionFacts(),
)

// ---------------------------------------------------------------------------
// Condiciones
// ---------------------------------------------------------------------------

/**
 * Condición de habilitación de una capacidad.
 *
 * Es una **función pura** sobre un [PermissionContext] inmutable: mismo
 * contexto, mismo resultado. Sin disco, sin red, sin reloj → testeable al
 * 100%.
 */
sealed interface Condition {

    /** Texto para la UI: "la cátedra está publicada". */
    val requirement: String

    /** ¿Se cumple la condición? */
    fun evaluate(ctx: PermissionContext): Boolean

    /**
     * Condiciones hoja, para que la UI pueda mostrar cada requisito por
     * separado. Por defecto la propia condición es la hoja.
     */
    fun flatten(): List<Condition> = listOf(this)
}

/** La cátedra está publicada (mientras es borrador el corpus está "en construcción"). */
data object CoursePublished : Condition {
    override val requirement: String = "la cátedra está publicada"
    override fun evaluate(ctx: PermissionContext): Boolean = ctx.facts.published
}

/** La cátedra tiene materiales cargados. */
data object CourseHasMaterials : Condition {
    override val requirement: String = "la cátedra tiene materiales cargados"
    override fun evaluate(ctx: PermissionContext): Boolean = ctx.facts.materialCount > 0
}

/** La analítica está habilitada en la cátedra **y** en la app. */
data object AnalyticsEnabled : Condition {
    override val requirement: String = "la analítica está habilitada en la cátedra y en la app"
    override fun evaluate(ctx: PermissionContext): Boolean =
        ctx.course.analyticsEnabled && ctx.app.analyticsEnabled
}

/** El alumno autorizó ver el **texto** de sus chats. */
data object TargetAllowsText : Condition {
    override val requirement: String = "el alumno autorizó ver el texto de sus chats"
    override fun evaluate(ctx: PermissionContext): Boolean = ctx.effectiveVisibility().showsText
}

/** El alumno autorizó ver las **métricas** de sus chats. */
data object TargetAllowsMetrics : Condition {
    override val requirement: String = "el alumno autorizó ver las métricas de sus chats"
    override fun evaluate(ctx: PermissionContext): Boolean = ctx.effectiveVisibility().showsMetrics
}

/** Es la propia inscripción. */
data object TargetIsSelf : Condition {
    override val requirement: String = "es la propia inscripción"
    override fun evaluate(ctx: PermissionContext): Boolean =
        ctx.target?.owner?.id == ctx.actor.id
}

/** El objetivo no es el titular de la cátedra. */
data object TargetIsNotOwner : Condition {
    override val requirement: String = "el objetivo no es el titular de la cátedra"
    override fun evaluate(ctx: PermissionContext): Boolean =
        ctx.target?.owner?.role != CourseRole.TITULAR
}

/** La cátedra permite que el alumno ajuste la configuración. */
data object StudentOverrideAllowed : Condition {
    override val requirement: String = "la cátedra permite que el alumno ajuste la configuración"
    override fun evaluate(ctx: PermissionContext): Boolean =
        ctx.course.modelPolicy.allowStudentOverride
}

/** La cátedra permite usar IA local en el dispositivo del alumno. */
data object LocalModelsAllowed : Condition {
    override val requirement: String = "la cátedra permite usar IA local"
    override fun evaluate(ctx: PermissionContext): Boolean = ctx.course.allowLocalModels
}

/** Las herramientas están habilitadas en la cátedra. */
data object ToolsEnabledInCourse : Condition {
    override val requirement: String = "las herramientas están habilitadas en la cátedra"
    override fun evaluate(ctx: PermissionContext): Boolean = ctx.course.modelPolicy.toolsEnabled
}

/** El docente habilitó esa categoría de herramientas en la cátedra. */
data class ToolCategoryAllowed(val category: PermissionToolCategory) : Condition {
    override val requirement: String = "el docente habilitó la categoría «${category.label}»"
    override fun evaluate(ctx: PermissionContext): Boolean = category in ctx.facts.toolCategories
}

/** La app permite servidores MCP. */
data object McpAllowedByApp : Condition {
    override val requirement: String = "la app permite servidores MCP"
    override fun evaluate(ctx: PermissionContext): Boolean = ctx.app.mcpEnabled
}

/** La instalación no está gestionada por la institución. */
data object AppNotManaged : Condition {
    override val requirement: String = "la instalación no está gestionada por la institución"
    override fun evaluate(ctx: PermissionContext): Boolean = !ctx.app.managed
}

/** El registro de accesos está habilitado. */
data object AuditLogEnabled : Condition {
    override val requirement: String = "el registro de accesos está habilitado"
    override fun evaluate(ctx: PermissionContext): Boolean = ctx.app.auditLogEnabled
}

// ---------------- Combinadores ----------------

/** Todas las partes deben cumplirse. */
data class AllOf(val parts: List<Condition>) : Condition {
    override val requirement: String get() = parts.joinToString(" y ") { it.requirement }
    override fun evaluate(ctx: PermissionContext): Boolean = parts.all { it.evaluate(ctx) }
    override fun flatten(): List<Condition> = parts.flatMap { it.flatten() }
}

/** Al menos una parte debe cumplirse. */
data class AnyOf(val parts: List<Condition>) : Condition {
    override val requirement: String get() = parts.joinToString(" o ") { it.requirement }
    override fun evaluate(ctx: PermissionContext): Boolean = parts.any { it.evaluate(ctx) }
}

/** La parte no debe cumplirse. */
data class Not(val part: Condition) : Condition {
    override val requirement: String get() = "no ${part.requirement}"
    override fun evaluate(ctx: PermissionContext): Boolean = !part.evaluate(ctx)
}

/** Azúcar sintáctico: `allOf(a, b)` en lugar de `AllOf(listOf(a, b))`. */
fun allOf(vararg parts: Condition): AllOf = AllOf(parts.toList())

/** Azúcar sintáctico: `anyOf(a, b)` en lugar de `AnyOf(listOf(a, b))`. */
fun anyOf(vararg parts: Condition): AnyOf = AnyOf(parts.toList())

// ---------------------------------------------------------------------------
// Capacidades
// ---------------------------------------------------------------------------

/**
 * Toda acción que la app puede autorizar.
 *
 * - [area]: a qué sección de la app pertenece (para la UI y el log).
 * - [label]: texto legible para la UI y para los mensajes de error.
 * - [scope]: si la capacidad depende del rol de cátedra o del estado global.
 * - [condition]: condición intrínseca. Si existe, la capacidad es
 *   `CONDITIONAL` en todo rol donde no esté `DENIED` (invariante I2).
 * - [audited]: si el ejercicio de la capacidad queda en el `AccessLog`.
 *
 * Es `@Serializable` porque aparece dentro de [AccessLogEntry].
 * kotlinx.serialization serializa los enums **por nombre**, así que la
 * propiedad [condition] (que no es serializable) no se persiste: al leer un
 * log, el enum se reconstruye y su condición se recupera del propio enum.
 */
@Serializable
enum class Capability(
    val area: CapabilityArea,
    val label: String,
    val scope: CapabilityScope = CapabilityScope.COURSE,
    val condition: Condition? = null,
    val audited: Boolean = false,
) {
    // ---------------- Cátedra ----------------
    COURSE_VIEW(CapabilityArea.CATEDRA, "Ver la cátedra"),
    COURSE_EDIT(CapabilityArea.CATEDRA, "Editar los datos de la cátedra"),
    COURSE_PUBLISH(CapabilityArea.CATEDRA, "Publicar la cátedra"),
    COURSE_UNPUBLISH(CapabilityArea.CATEDRA, "Volver a borrador"),
    COURSE_ARCHIVE(CapabilityArea.CATEDRA, "Archivar la cátedra"),
    COURSE_DELETE(CapabilityArea.CATEDRA, "Eliminar la cátedra"),
    COURSE_VIEW_JOIN_CODE(CapabilityArea.CATEDRA, "Ver el código de inscripción"),
    COURSE_ROTATE_JOIN_CODE(CapabilityArea.CATEDRA, "Regenerar el código de inscripción"),
    COURSE_CONFIG_RAG(
        CapabilityArea.CATEDRA, "Ajustar la recuperación",
        condition = CoursePublished,
    ),
    COURSE_VIEW_SETTINGS(CapabilityArea.CATEDRA, "Ver la configuración de la cátedra"),
    COURSE_EXPORT(CapabilityArea.CATEDRA, "Exportar la cátedra"),

    // ---------------- Materiales ----------------
    MATERIAL_VIEW(CapabilityArea.MATERIALES, "Ver los materiales"),
    MATERIAL_VIEW_CONTENT(CapabilityArea.MATERIALES, "Leer el contenido de un material"),
    MATERIAL_UPLOAD(
        CapabilityArea.MATERIALES, "Subir materiales",
        condition = CoursePublished,
    ),
    MATERIAL_EDIT(
        CapabilityArea.MATERIALES, "Editar los metadatos de un material",
        condition = CoursePublished,
    ),
    MATERIAL_DELETE(CapabilityArea.MATERIALES, "Eliminar materiales"),
    MATERIAL_REINDEX(
        CapabilityArea.MATERIALES, "Reindexar los materiales",
        condition = CoursePublished,
    ),
    MATERIAL_VIEW_HIDDEN(CapabilityArea.MATERIALES, "Ver los materiales ocultos a los alumnos"),

    // ---------------- Modelo ----------------
    MODEL_USE(CapabilityArea.MODELO, "Usar el modelo"),
    MODEL_VIEW_POLICY(CapabilityArea.MODELO, "Ver la configuración del modelo"),
    MODEL_EDIT_POLICY(CapabilityArea.MODELO, "Editar la configuración base del modelo"),
    MODEL_SET_MODE(CapabilityArea.MODELO, "Cambiar el modo del modelo"),
    MODEL_LOCK_FIELDS(CapabilityArea.MODELO, "Bloquear campos de la configuración"),
    MODEL_SET_ALLOW_OVERRIDE(CapabilityArea.MODELO, "Permitir el ajuste del alumno"),
    MODEL_EDIT_SYSTEM_PROMPT(CapabilityArea.MODELO, "Editar las instrucciones del sistema"),
    MODEL_OVERRIDE(
        CapabilityArea.MODELO, "Ajustar la configuración para sí mismo",
        condition = StudentOverrideAllowed,
    ),
    MODEL_USE_LOCAL(
        CapabilityArea.MODELO, "Usar IA local en el dispositivo",
        condition = LocalModelsAllowed,
    ),

    // ---------------- Herramientas del modelo ----------------
    MODEL_TOOLS_ENABLE(CapabilityArea.MODELO, "Habilitar herramientas en la cátedra"),
    MODEL_TOOLS_FILES(
        CapabilityArea.MODELO, "Permitir herramientas de archivos",
        condition = allOf(ToolsEnabledInCourse, ToolCategoryAllowed(PermissionToolCategory.FILES)),
    ),
    MODEL_TOOLS_WEB(
        CapabilityArea.MODELO, "Permitir herramientas web",
        condition = allOf(ToolsEnabledInCourse, ToolCategoryAllowed(PermissionToolCategory.WEB)),
    ),
    MODEL_TOOLS_MCP(
        CapabilityArea.MODELO, "Permitir herramientas MCP",
        condition = allOf(
            ToolsEnabledInCourse,
            ToolCategoryAllowed(PermissionToolCategory.MCP),
            McpAllowedByApp,
            AppNotManaged,
        ),
    ),
    MODEL_TOOLS_GRANT(
        CapabilityArea.MODELO, "Conceder permisos de herramientas por chat",
        condition = ToolsEnabledInCourse,
    ),

    // ---------------- Chats ----------------
    CHAT_CREATE(CapabilityArea.CHATS, "Crear conversaciones"),
    CHAT_SEND(CapabilityArea.CHATS, "Enviar mensajes"),
    CHAT_VIEW_OWN(CapabilityArea.CHATS, "Ver las conversaciones propias"),
    CHAT_VIEW_OTHER(
        CapabilityArea.CHATS, "Ver el texto de las conversaciones de otro participante",
        condition = TargetAllowsText, audited = true,
    ),
    CHAT_VIEW_OTHER_METRICS(
        CapabilityArea.CHATS, "Ver las métricas de otro participante",
        condition = TargetAllowsMetrics, audited = true,
    ),
    CHAT_VIEW_TOOL_CALLS(
        CapabilityArea.CHATS, "Ver las herramientas invocadas en conversaciones ajenas",
        condition = TargetAllowsText, audited = true,
    ),
    CHAT_DELETE_OWN(CapabilityArea.CHATS, "Borrar las conversaciones propias"),
    CHAT_DELETE_ANY(
        CapabilityArea.CHATS, "Borrar las conversaciones de cualquier participante",
        audited = true,
    ),
    CHAT_RENAME_TAG(CapabilityArea.CHATS, "Renombrar y etiquetar conversaciones"),

    // ---------------- Analítica ----------------
    ANALYTICS_VIEW_COURSE(
        CapabilityArea.ANALITICA, "Ver la analítica de la cátedra",
        condition = AnalyticsEnabled,
    ),
    ANALYTICS_VIEW_PER_STUDENT(
        CapabilityArea.ANALITICA, "Ver la analítica por alumno",
        condition = allOf(AnalyticsEnabled, TargetAllowsMetrics), audited = true,
    ),
    ANALYTICS_VIEW_ANSWER_TEXT(
        CapabilityArea.ANALITICA, "Ver el texto de las respuestas de un alumno",
        condition = allOf(AnalyticsEnabled, TargetAllowsText), audited = true,
    ),
    ANALYTICS_VIEW_SOURCES(
        CapabilityArea.ANALITICA, "Ver el uso de fuentes",
        condition = AnalyticsEnabled,
    ),
    ANALYTICS_EXPORT(
        CapabilityArea.ANALITICA, "Exportar la analítica",
        condition = AnalyticsEnabled, audited = true,
    ),
    ANALYTICS_VIEW_OWN(CapabilityArea.ANALITICA, "Ver la propia actividad"),

    // ---------------- Inscripciones ----------------
    ENROLLMENT_VIEW_ALL(CapabilityArea.ENROLMENTS, "Ver todos los participantes"),
    ENROLLMENT_VIEW_OWN(
        CapabilityArea.ENROLMENTS, "Ver la propia inscripción",
        condition = TargetIsSelf,
    ),
    ENROLLMENT_INVITE(CapabilityArea.ENROLMENTS, "Inscribir a alguien"),
    ENROLLMENT_REMOVE(
        CapabilityArea.ENROLMENTS, "Quitar a un participante",
        condition = TargetIsNotOwner,
    ),
    ENROLLMENT_CHANGE_ROLE(CapabilityArea.ENROLMENTS, "Cambiar el rol de un participante"),
    ENROLLMENT_SET_VISIBILITY(
        CapabilityArea.ENROLMENTS, "Restringir la visibilidad de un alumno",
        audited = true,
    ),
    ENROLLMENT_SET_OWN_VISIBILITY(
        CapabilityArea.ENROLMENTS, "Fijar la propia visibilidad",
        condition = TargetIsSelf, audited = true,
    ),
    ENROLLMENT_SELF_LEAVE(CapabilityArea.ENROLMENTS, "Darse de baja de la cátedra"),

    // ---------------- Configuración de la app ----------------
    APP_SETTINGS_VIEW(
        CapabilityArea.APP, "Ver los ajustes de la app",
        scope = CapabilityScope.APP,
    ),
    APP_SETTINGS_EDIT(
        CapabilityArea.APP, "Editar los ajustes de la app",
        scope = CapabilityScope.APP,
    ),
    APP_LOCAL_MODEL_MANAGE(
        CapabilityArea.APP, "Administrar los modelos locales",
        scope = CapabilityScope.APP, condition = AppNotManaged,
    ),
    APP_MCP_MANAGE(
        CapabilityArea.APP, "Administrar los servidores MCP",
        scope = CapabilityScope.APP, condition = allOf(McpAllowedByApp, AppNotManaged),
    ),
    APP_TOOLS_POLICY(
        CapabilityArea.APP, "Editar la política global de herramientas",
        scope = CapabilityScope.APP, condition = AppNotManaged,
    ),
    APP_AUDIT_LOG_VIEW(
        CapabilityArea.APP, "Ver el registro de accesos",
        scope = CapabilityScope.APP, condition = AuditLogEnabled, audited = true,
    ),
    APP_AUDIT_LOG_EXPORT(
        CapabilityArea.APP, "Exportar el registro de accesos",
        scope = CapabilityScope.APP, condition = AuditLogEnabled, audited = true,
    ),
    APP_DATA_EXPORT(
        CapabilityArea.APP, "Exportar los datos locales",
        scope = CapabilityScope.APP,
    ),
    APP_RESET(
        CapabilityArea.APP, "Borrar los datos locales",
        scope = CapabilityScope.APP,
    ),
    ;

    companion object {
        /** Convierte el nombre persistido ("COURSE_EDIT") al enum; null si no existe. */
        fun fromName(name: String): Capability? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

// ---------------------------------------------------------------------------
// La matriz rol × capacidad
// ---------------------------------------------------------------------------

/**
 * Una fila de la matriz rol × capacidad.
 *
 * Una fila por capacidad: el markdown del documento de diseño se genera a
 * partir de estas filas, así que el documento no puede quedar viejo.
 */
data class PermissionRow(
    val capability: Capability,
    val titular: Verdict,
    val ayudante: Verdict,
    val alumno: Verdict,
) {
    /** Veredicto de este rol. */
    fun verdict(role: CourseRole): Verdict = when (role) {
        CourseRole.TITULAR -> titular
        CourseRole.AYUDANTE -> ayudante
        CourseRole.ALUMNO -> alumno
    }
}

/**
 * La matriz de permisos: la fuente única de verdad.
 *
 * Es **datos**, no lógica: se puede revisar de un vistazo, se puede mostrar en
 * la app y se puede generar el markdown del diseño.
 */
object PermissionMatrix {

    /** Una fila por capacidad: la tabla completa. */
    val ROWS: List<PermissionRow> = listOf(
        // ---------------- Cátedra ----------------
        PermissionRow(Capability.COURSE_VIEW, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.COURSE_EDIT, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_PUBLISH, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_UNPUBLISH, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_ARCHIVE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_DELETE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_VIEW_JOIN_CODE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_ROTATE_JOIN_CODE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_CONFIG_RAG, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.COURSE_VIEW_SETTINGS, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.DENIED),
        PermissionRow(Capability.COURSE_EXPORT, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),

        // ---------------- Materiales ----------------
        PermissionRow(Capability.MATERIAL_VIEW, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.MATERIAL_VIEW_CONTENT, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.MATERIAL_UPLOAD, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.MATERIAL_EDIT, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.MATERIAL_DELETE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.MATERIAL_REINDEX, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.MATERIAL_VIEW_HIDDEN, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),

        // ---------------- Modelo ----------------
        PermissionRow(Capability.MODEL_USE, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.MODEL_VIEW_POLICY, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.MODEL_EDIT_POLICY, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.MODEL_SET_MODE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.MODEL_LOCK_FIELDS, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.MODEL_SET_ALLOW_OVERRIDE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.MODEL_EDIT_SYSTEM_PROMPT, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.MODEL_OVERRIDE, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.MODEL_USE_LOCAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),

        // ---------------- Herramientas del modelo ----------------
        PermissionRow(Capability.MODEL_TOOLS_ENABLE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.MODEL_TOOLS_FILES, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.MODEL_TOOLS_WEB, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.MODEL_TOOLS_MCP, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.MODEL_TOOLS_GRANT, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),

        // ---------------- Chats ----------------
        PermissionRow(Capability.CHAT_CREATE, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.CHAT_SEND, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.CHAT_VIEW_OWN, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.CHAT_VIEW_OTHER, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.CHAT_VIEW_OTHER_METRICS, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.CHAT_VIEW_TOOL_CALLS, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.CHAT_DELETE_OWN, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.CHAT_DELETE_ANY, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.CHAT_RENAME_TAG, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),

        // ---------------- Analítica ----------------
        PermissionRow(Capability.ANALYTICS_VIEW_COURSE, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.ANALYTICS_VIEW_PER_STUDENT, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.ANALYTICS_VIEW_ANSWER_TEXT, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.ANALYTICS_VIEW_SOURCES, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.ANALYTICS_EXPORT, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.ANALYTICS_VIEW_OWN, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),

        // ---------------- Inscripciones ----------------
        PermissionRow(Capability.ENROLLMENT_VIEW_ALL, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.DENIED),
        PermissionRow(Capability.ENROLLMENT_VIEW_OWN, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.ENROLLMENT_INVITE, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.DENIED),
        PermissionRow(Capability.ENROLLMENT_REMOVE, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.DENIED),
        PermissionRow(Capability.ENROLLMENT_CHANGE_ROLE, Verdict.ALLOWED, Verdict.DENIED, Verdict.DENIED),
        PermissionRow(Capability.ENROLLMENT_SET_VISIBILITY, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.DENIED),
        PermissionRow(Capability.ENROLLMENT_SET_OWN_VISIBILITY, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.ENROLLMENT_SELF_LEAVE, Verdict.DENIED, Verdict.ALLOWED, Verdict.ALLOWED),

        // ---------------- Configuración de la app ----------------
        PermissionRow(Capability.APP_SETTINGS_VIEW, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.APP_SETTINGS_EDIT, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.APP_LOCAL_MODEL_MANAGE, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.APP_MCP_MANAGE, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.APP_TOOLS_POLICY, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.APP_AUDIT_LOG_VIEW, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.APP_AUDIT_LOG_EXPORT, Verdict.CONDITIONAL, Verdict.CONDITIONAL, Verdict.CONDITIONAL),
        PermissionRow(Capability.APP_DATA_EXPORT, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
        PermissionRow(Capability.APP_RESET, Verdict.ALLOWED, Verdict.ALLOWED, Verdict.ALLOWED),
    )

    init {
        // I1: cobertura total, sin duplicados. Si falta una capacidad, la app no arranca.
        val capacidades = ROWS.map { it.capability }
        require(capacidades.size == capacidades.toSet().size) {
            "La matriz de permisos tiene capacidades repetidas."
        }
        require(capacidades.toSet() == Capability.entries.toSet()) {
            val faltan = Capability.entries.toSet() - capacidades.toSet()
            val sobran = capacidades.toSet() - Capability.entries.toSet()
            "La matriz de permisos no coincide con las capacidades. Faltan: $faltan. Sobran: $sobran."
        }
        for (fila in ROWS) {
            val veredictos = listOf(fila.titular, fila.ayudante, fila.alumno)
            // I5: toda capacidad condicional en la matriz tiene condición.
            require(!veredictos.contains(Verdict.CONDITIONAL) || fila.capability.condition != null) {
                "La capacidad ${fila.capability.name} es condicional pero no define condición."
            }
            // I2: la condicionalidad es intrínseca: si hay condición, nadie la tiene como permitida.
            if (fila.capability.condition != null) {
                require(!veredictos.contains(Verdict.ALLOWED)) {
                    "La capacidad ${fila.capability.name} tiene condición y no puede estar permitida en ningún rol."
                }
            }
            // I3: las capacidades de la app son iguales en los tres roles.
            if (fila.capability.scope == CapabilityScope.APP) {
                require(fila.titular == fila.ayudante && fila.ayudante == fila.alumno) {
                    "La capacidad ${fila.capability.name} es de ámbito app: su veredicto no puede depender del rol."
                }
            }
        }
    }

    private val index: Map<Capability, PermissionRow> = ROWS.associateBy { it.capability }

    /** Veredicto de un rol para una capacidad (sin evaluar condiciones). */
    fun verdict(role: CourseRole, capability: Capability): Verdict =
        index.getValue(capability).verdict(role)

    /** La fila de una capacidad. */
    fun row(capability: Capability): PermissionRow = index.getValue(capability)

    /** Veredicto de un rol para una capacidad, evaluando la condición en [ctx]. */
    fun verdict(role: CourseRole, capability: Capability, ctx: PermissionContext): Verdict {
        val base = verdict(role, capability)
        if (base != Verdict.CONDITIONAL) return base
        val condition = capability.condition ?: return Verdict.DENIED
        return if (condition.evaluate(ctx)) Verdict.ALLOWED else Verdict.DENIED
    }

    /**
     * Markdown de la matriz.
     *
     * La columna "Condición" usa el texto de UI de la condición (libre); el
     * test de sincronización con el documento compara capacidades y
     * veredictos, no esa prosa.
     */
    fun toMarkdown(): String = buildString {
        appendLine("| Capacidad | Ámbito | TITULAR | AYUDANTE | ALUMNO | Condición |")
        appendLine("|---|---|:---:|:---:|:---:|---|")
        for (fila in ROWS) {
            val cap = fila.capability
            val simbolos = listOf(fila.titular, fila.ayudante, fila.alumno).joinToString(" | ") { marca(it) }
            val condicion = cap.condition?.requirement ?: "—"
            appendLine("| `${cap.name}` | ${cap.scope.label.lowercase()} | $simbolos | $condicion |")
        }
    }

    private fun marca(v: Verdict): String = when (v) {
        Verdict.ALLOWED -> "✔"
        Verdict.CONDITIONAL -> "◆"
        Verdict.DENIED -> "✖"
    }
}

/**
 * Fachada sobre la matriz: ¿este rol tiene esta capacidad en alguna cátedra?
 *
 * No dice si la operación concreta se permite: eso lo resuelve
 * [PermissionResolver] con el estado de la cátedra y del alumno.
 */
fun CourseRole.can(capability: Capability): Boolean =
    PermissionMatrix.verdict(this, capability) != Verdict.DENIED

// ---------------------------------------------------------------------------
// Visibilidad efectiva
// ---------------------------------------------------------------------------

/**
 * Nivel de visibilidad: 0 = oculta, 1 = sólo métricas, 2 = completa.
 *
 * Sirve para comparar dos visibilidades y para calcular el mínimo de varios
 * ejes (el menor nivel gana).
 */
fun ChatVisibility.level(): Int = when (this) {
    ChatVisibility.NONE -> 0
    ChatVisibility.METRICS_ONLY -> 1
    ChatVisibility.FULL -> 2
}

/**
 * Visibilidad **efectiva**: el mínimo de los tres ejes.
 *
 * | Eje | Qué es |
 * |---|---|
 * | [teacherCap] | Lo que el rol del docente puede ver según la matriz. |
 * | [enrollmentCeiling] | El tope de la inscripción: lo máximo que el alumno autorizó. |
 * | [studentPreference] | La preferencia explícita del alumno. |
 *
 * El mínimo siempre gana: nadie ve más de lo que autorizó el alumno, y el
 * docente nunca supera el tope de la inscripción.
 *
 * **Fail-closed:** si el alumno nunca dejó una preferencia explícita
 * ([studentPreference] es `null`), el resultado nunca es [ChatVisibility.FULL]:
 * como mucho [ChatVisibility.METRICS_ONLY]. Sin consentimiento explícito no
 * hay acceso al texto.
 */
fun effectiveVisibility(
    teacherCap: ChatVisibility,
    enrollmentCeiling: ChatVisibility,
    studentPreference: ChatVisibility?,
): ChatVisibility {
    // Sin consentimiento explícito se limita a las métricas (fail-closed).
    val preferencia = studentPreference ?: ChatVisibility.METRICS_ONLY
    return listOf(teacherCap, enrollmentCeiling, preferencia).minBy { it.level() }
}

/**
 * Reglas de visibilidad del lado del docente.
 */
object VisibilityPolicy {

    /**
     * Máximo que el rol puede ver según la matriz de permisos.
     *
     * Si el rol no tiene [Capability.CHAT_VIEW_OTHER] pero sí
     * [Capability.CHAT_VIEW_OTHER_METRICS], el tope son las métricas.
     */
    fun teacherCap(role: CourseRole): ChatVisibility {
        val texto = PermissionMatrix.verdict(role, Capability.CHAT_VIEW_OTHER)
        if (texto == Verdict.DENIED) {
            val metricas = PermissionMatrix.verdict(role, Capability.CHAT_VIEW_OTHER_METRICS)
            return if (metricas == Verdict.DENIED) ChatVisibility.NONE else ChatVisibility.METRICS_ONLY
        }
        return ChatVisibility.FULL
    }

    /** Visibilidad efectiva de un objetivo concreto dentro de un contexto. */
    fun effective(context: PermissionContext): ChatVisibility {
        val target = context.target ?: return ChatVisibility.NONE
        return effectiveVisibility(
            teacherCap = teacherCap(context.actor.role),
            enrollmentCeiling = target.enrollmentCeiling,
            studentPreference = target.studentPreference,
        )
    }
}

/** Visibilidad efectiva del objetivo de este contexto. */
fun PermissionContext.effectiveVisibility(): ChatVisibility = VisibilityPolicy.effective(this)

// ---------------------------------------------------------------------------
// Resolvedor
// ---------------------------------------------------------------------------

/**
 * Resultado de pedir una capacidad.
 *
 * - [Granted]: permitida sin condiciones.
 * - [Conditional]: el veredicto base es condicional; [satisfied] dice si las
 *   condiciones se cumplen y [reason] explica el requisito que falta.
 * - [Denied]: el rol no tiene la capacidad en esta cátedra.
 */
sealed interface PermissionDecision {

    /** Capacidad consultada. */
    val capability: Capability

    /** ¿Se puede hacer la operación? */
    val allows: Boolean

    /** ¿Está denegada? */
    val denies: Boolean get() = !allows

    /** Motivo en español (null si está permitida). */
    val reason: String?

    /** Permitida sin condiciones. */
    data class Granted(override val capability: Capability) : PermissionDecision {
        override val allows: Boolean get() = true
        override val reason: String? get() = null
    }

    /** Denegada, con el motivo en español. */
    data class Denied(
        override val capability: Capability,
        override val reason: String,
    ) : PermissionDecision {
        override val allows: Boolean get() = false
    }

    /** Depende de una o más condiciones. */
    data class Conditional(
        override val capability: Capability,
        /** Condiciones hoja que hay que cumplir. */
        val conditions: List<Condition>,
        /** Si las condiciones se cumplen con el contexto dado. */
        val satisfied: Boolean,
        /** Motivo en español cuando no se cumplen. */
        override val reason: String? = null,
    ) : PermissionDecision {
        override val allows: Boolean get() = satisfied
    }
}

/** Por qué se denegó una capacidad. */
data class DenialReason(
    /** Mensaje en español para la UI. */
    val message: String,
    /** Condición que faltó, si la denegación vino de una condición. */
    val condition: Condition? = null,
)

/**
 * El conjunto de capacidades efectivas de un actor en una cátedra.
 *
 * [granted] y [denied] son disjuntos. [conditional] son las capacidades cuyo
 * veredicto base era condicional: sirve para que la UI diga "requiere X",
 * estén concedidas o no.
 */
data class EffectivePermissions(
    val granted: Set<Capability>,
    val denied: Map<Capability, DenialReason>,
    val conditional: Map<Capability, Condition>,
) {
    /** ¿La operación está permitida? */
    fun allows(capability: Capability): Boolean = capability in granted

    /** ¿La operación está denegada? */
    fun isDenied(capability: Capability): Boolean = capability in denied

    /** ¿El permiso depende de una condición? */
    fun isConditional(capability: Capability): Boolean = capability in conditional

    /** Motivo del permiso denegado (null si está concedido). */
    fun reason(capability: Capability): String? = denied[capability]?.message

    /** Falla rápido con un mensaje en español si la capacidad no está concedida. */
    fun require(capability: Capability) {
        if (allows(capability)) return
        val motivo = reason(capability) ?: "El rol no tiene el permiso «${capability.label}»."
        throw IllegalStateException(motivo)
    }

    /** Sólo las capacidades concedidas de un área, para pintar la UI. */
    fun grantedOf(area: CapabilityArea): Set<Capability> =
        granted.filter { it.area == area }.toSet()
}

/**
 * Resolvedor de permisos efectivos.
 *
 * Función **pura**: mismo contexto → mismo resultado. No lee disco, no llama
 * a la red y no usa el reloj (la hora del log la pone el servicio que
 * escribe).
 */
object PermissionResolver {

    /**
     * Veredicto de una capacidad para el actor del contexto.
     *
     * @param context contexto inmutable de la consulta.
     * @param capability capacidad que se quiere consultar.
     */
    fun resolve(context: PermissionContext, capability: Capability): PermissionDecision {
        return when (PermissionMatrix.verdict(context.actor.role, capability)) {
            Verdict.ALLOWED -> PermissionDecision.Granted(capability)

            Verdict.DENIED -> PermissionDecision.Denied(
                capability,
                "El rol «${context.actor.role.label}» no tiene el permiso «${capability.label}» en esta cátedra.",
            )

            Verdict.CONDITIONAL -> {
                val condition = capability.condition
                    ?: return PermissionDecision.Denied(
                        capability,
                        "El permiso «${capability.label}» no tiene condición definida.",
                    )
                val condiciones = condition.flatten()
                val cumplido = condiciones.all { it.evaluate(context) }
                val motivo = if (cumplido) {
                    null
                } else {
                    "Requiere: ${condiciones.joinToString(" y ") { it.requirement }}."
                }
                PermissionDecision.Conditional(capability, condiciones, cumplido, motivo)
            }
        }
    }

    /** Todas las capacidades efectivas del actor en el contexto. */
    fun resolveAll(context: PermissionContext): EffectivePermissions {
        val granted = mutableSetOf<Capability>()
        val denied = mutableMapOf<Capability, DenialReason>()
        val conditional = mutableMapOf<Capability, Condition>()

        for (capability in Capability.entries) {
            when (val decision = resolve(context, capability)) {
                is PermissionDecision.Granted -> granted += capability

                is PermissionDecision.Denied ->
                    denied[capability] = DenialReason(decision.reason)

                is PermissionDecision.Conditional -> {
                    val condition = capability.condition
                    if (condition != null) conditional[capability] = condition
                    if (decision.satisfied) {
                        granted += capability
                    } else {
                        denied[capability] = DenialReason(
                            decision.reason.orEmpty(),
                            condition,
                        )
                    }
                }
            }
        }
        return EffectivePermissions(granted, denied, conditional)
    }

    /** Atajo: ¿puede este docente leer el texto de los chats de este alumno? */
    fun canSeeStudentChats(
        actor: Enrollment,
        course: Course,
        student: Enrollment,
        app: PermissionAppSettings = PermissionAppSettings(),
        studentPreference: ChatVisibility? = null,
    ): Boolean = resolve(
        PermissionContext(
            course = course,
            actor = actor,
            target = PermissionTarget.Student(
                owner = student,
                enrollmentCeiling = student.visibility,
                studentPreference = studentPreference,
            ),
            app = app,
        ),
        Capability.CHAT_VIEW_OTHER,
    ).allows

    /** Atajo: ¿puede este docente ver las métricas de este alumno? */
    fun canSeeStudentMetrics(
        actor: Enrollment,
        course: Course,
        student: Enrollment,
        app: PermissionAppSettings = PermissionAppSettings(),
        studentPreference: ChatVisibility? = null,
    ): Boolean = resolve(
        PermissionContext(
            course = course,
            actor = actor,
            target = PermissionTarget.Student(
                owner = student,
                enrollmentCeiling = student.visibility,
                studentPreference = studentPreference,
            ),
            app = app,
        ),
        Capability.CHAT_VIEW_OTHER_METRICS,
    ).allows
}
