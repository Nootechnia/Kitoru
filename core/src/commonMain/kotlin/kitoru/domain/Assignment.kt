package kitoru.domain

import kitoru.platform.nowMillis
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Enumeraciones del subsistema de tareas
// ---------------------------------------------------------------------------

/**
 * Qué tiene que entregar el alumno.
 *
 * Es un dato del enunciado, no una preferencia: la UI de entrega arma el
 * formulario a partir de acá (campo de texto, selector de archivos o ambos).
 */
@Serializable
enum class SubmissionType(val label: String) {
    /** Sólo un texto escrito por el alumno. */
    TEXT("Texto"),

    /** Sólo archivos adjuntos. */
    FILES("Archivos"),

    /** Texto y archivos: el texto explica, los archivos demuestran. */
    BOTH("Texto y archivos"),
    ;

    /** ¿La tarea acepta un texto escrito por el alumno? */
    val acceptsText: Boolean get() = this == TEXT || this == BOTH

    /** ¿La tarea acepta archivos adjuntos? */
    val acceptsFiles: Boolean get() = this == FILES || this == BOTH

    companion object {
        fun fromName(name: String): SubmissionType? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Si la tarea suma a la nota o es sólo de práctica.
 *
 * No cambia cómo se entrega ni cómo se corrige: sirve para que el docente
 * distinga "esto va al parcial" de "esto es para practicar".
 */
@Serializable
enum class Assessment(val label: String) {
    /** No cuenta en la nota: sirve para practicar y para ver el proceso. */
    FORMATIVE("Formativa"),

    /** Cuenta en la nota. */
    SUMMATIVE("Sumativa"),
    ;

    companion object {
        fun fromName(name: String): Assessment? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Estado de una entrega del alumno.
 *
 * El recorrido es lineal: borrador → entregada → corregida. El borrador es el
 * único editable, porque una entrega entregada es una foto de lo que el
 * alumno quiso entregar en ese momento.
 */
@Serializable
enum class SubmissionStatus(val label: String) {
    /** Se está escribiendo: el alumno puede seguir editando. */
    DRAFT("Borrador"),

    /** El alumno entregó: ya no se puede tocar. */
    SUBMITTED("Entregada"),

    /** El docente corrigió y publicó el feedback. */
    GRADED("Corregida"),
    ;

    /** ¿Se puede seguir editando? Sólo el borrador. */
    val isEditable: Boolean get() = this == DRAFT

    /** ¿Ya fue entregada? */
    val isDelivered: Boolean get() = this != DRAFT

    companion object {
        fun fromName(name: String): SubmissionStatus? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Cómo declara el alumno el uso de IA en su entrega.
 *
 * La declaración y la detección automática son cosas distintas: la primera
 * la escribe el alumno (y es la que el docente contrasta), la segunda sale
 * de los hilos y de los archivos. Que no coincidan es información, no un
 * error: por eso [AiMetrics.declarationMismatch] existe.
 */
@Serializable
enum class AiDeclaration(val label: String, val description: String) {
    /** El alumno no usó IA. */
    NONE(
        "No usé IA",
        "El alumno declara que no usó IA para esta entrega.",
    ),

    /** El alumno usó IA y lo dice. */
    USED_AI(
        "Usé IA",
        "El alumno declara que usó IA para trabajar la entrega.",
    ),

    /** El alumno usó IA como apoyo (conceptos, dudas, correcciones). */
    USED_AI_WITH_HELP(
        "Usé IA con ayuda",
        "El alumno declara que usó IA sólo como apoyo: conceptos, dudas o correcciones.",
    ),

    /** La política de la cátedra o de la tarea prohíbe la IA en esta entrega. */
    DENIED_BY_POLICY(
        "Prohibida por la política",
        "La política de IA no permite usar IA en esta tarea: el uso queda marcado como infracción.",
    ),
    ;

    /** ¿El alumno dice que usó IA? */
    val usedAi: Boolean get() = this == USED_AI || this == USED_AI_WITH_HELP

    /** ¿Es una infracción de la política? */
    val isViolation: Boolean get() = this == DENIED_BY_POLICY

    companion object {
        fun fromName(name: String): AiDeclaration? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

// ---------------------------------------------------------------------------
// Política de IA
// ---------------------------------------------------------------------------

/**
 * Política de uso de IA de una cátedra (o de una tarea, que puede cerrarla
 * más pero nunca abrirla más).
 *
 * Los cuatro niveles:
 *  - [DISABLED]: el alumno no puede usar IA. No se abre chat de trabajo, no
 *    se pueden vincular hilos y usar IA cuenta como infracción.
 *  - [ASSISTED]: el alumno puede usar IA, pero **tiene que declararlo** en
 *    la entrega.
 *  - [GUIDED]: la IA **sólo da pistas** (preguntas guía, conceptos): no
 *    escribe la solución ni un texto entregable.
 *  - [FREE]: el alumno puede usar IA libremente y no está obligado a
 *    declararlo.
 *
 * La política **no** cambia la matriz de visibilidad del docente: eso lo
 * manda `Enrollment.visibility`. Sólo decide qué puede hacer el alumno y qué
 * tiene que declarar.
 */
@Serializable
enum class AiPolicy(val label: String, val description: String) {
    DISABLED(
        "Sin IA",
        "El alumno no puede usar IA en esta tarea: no se abre chat de trabajo " +
            "ni se pueden vincular hilos a la entrega.",
    ),
    ASSISTED(
        "IA asistida",
        "El alumno puede usar IA, pero debe declarar el uso en la entrega.",
    ),
    GUIDED(
        "IA guiada",
        "La IA sólo da pistas y preguntas guía; no resuelve la consigna ni " +
            "escribe un texto que pueda entregarse.",
    ),
    FREE(
        "IA libre",
        "El alumno puede usar IA libremente y no está obligado a declararlo.",
    ),
    ;

    /** ¿El alumno puede usar IA? */
    val allowsStudentUse: Boolean get() = this != DISABLED

    /** ¿La entrega tiene que declarar el uso de IA? */
    val requiresDeclaration: Boolean get() = this == ASSISTED

    /** ¿La IA está limitada a pistas? */
    val hintsOnly: Boolean get() = this == GUIDED

    /** ¿El docente puede usar IA con el material de la cátedra? (siempre sí) */
    val allowsTeacherUse: Boolean get() = true

    companion object {
        fun fromName(name: String): AiPolicy? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Reglas de IA ya resueltas (sin enumerado): tres banderas independientes.
 *
 * Un enum no alcanza: "sólo pistas" y "declarar el uso" son ejes distintos
 * (`GUIDED` + declaración requerida es una combinación válida). Por eso la
 * composición se hace con una clase de tres banderas, que se combinan con
 * [narrow] **cerrando** (nunca abriendo) permisos.
 */
@Serializable
data class AiRules(
    /** ¿El alumno puede usar IA? */
    val allowsStudentUse: Boolean = true,
    /** ¿La IA está limitada a pistas (no propone el texto de la entrega)? */
    val hintsOnly: Boolean = false,
    /** ¿La entrega exige declarar el uso de IA? */
    val requiresDeclaration: Boolean = false,
) {
    /**
     * Nivel canónico, sólo para el badge de la UI.
     *
     * Ojo: es un resumen. La fuente de verdad son las tres banderas; por
     * ejemplo una cátedra `ASSISTED` con una tarea `GUIDED` da
     * `level = GUIDED` pero `requiresDeclaration = true`.
     */
    val level: AiPolicy
        get() = when {
            !allowsStudentUse -> AiPolicy.DISABLED
            hintsOnly -> AiPolicy.GUIDED
            requiresDeclaration -> AiPolicy.ASSISTED
            else -> AiPolicy.FREE
        }

    /** Combina dos juegos de reglas cerrando (nunca abriendo) permisos. */
    fun narrow(other: AiRules): AiRules = AiRules(
        allowsStudentUse = allowsStudentUse && other.allowsStudentUse,
        hintsOnly = hintsOnly || other.hintsOnly,
        requiresDeclaration = requiresDeclaration || other.requiresDeclaration,
    )

    companion object {
        /** Convierte un nivel de política en reglas. */
        fun of(policy: AiPolicy): AiRules = when (policy) {
            AiPolicy.DISABLED -> AiRules(allowsStudentUse = false)
            AiPolicy.ASSISTED -> AiRules(requiresDeclaration = true)
            AiPolicy.GUIDED -> AiRules(hintsOnly = true)
            AiPolicy.FREE -> AiRules()
        }
    }
}

/**
 * Política de IA de la cátedra.
 *
 * TODO(enganchar a `Course`): este tipo existe sólo porque en esta tanda no
 * se puede tocar `Course.kt`. Cuando se agregue el campo
 * `val aiPolicy: AiPolicy = AiPolicy.ASSISTED` a `Course`, este tipo se
 * elimina y la fuente de verdad pasa a ser `Course.aiPolicy`; el resto del
 * código debería leer la política desde la cátedra y no desde un objeto
 * paralelo, porque tener las dos cosas garantiza que se desincronicen.
 */
@Serializable
data class CourseAiPolicy(
    /** Nivel de la política. */
    val policy: AiPolicy = AiPolicy.ASSISTED,
    /** Si la cátedra exige declarar el uso de IA en las entregas. */
    val requireDeclaration: Boolean = true,
) {
    /** Reglas resultantes de la política de la cátedra. */
    val rules: AiRules
        get() = AiRules.of(policy).let {
            if (requireDeclaration) it.copy(requiresDeclaration = true) else it
        }

    /** Reglas de una tarea concreta: la tarea sólo puede cerrar más. */
    fun rulesFor(assignment: Assignment): AiRules = assignment.effectiveRules(rules)
}

// ---------------------------------------------------------------------------
// Rúbrica
// ---------------------------------------------------------------------------

/**
 * Nivel de una escala (ej. "Insuficiente", "Suficiente", "Excelente").
 *
 * Los puntos son los que vale ese nivel **dentro del criterio**: el máximo
 * del criterio es el que dice [RubricCriterion.points].
 */
@Serializable
data class ScaleLevel(
    /** Etiqueta visible ("Suficiente"). */
    val label: String = "",
    /** Puntos que vale este nivel. */
    val points: Double = 0.0,
    /** Qué se espera en este nivel. */
    val description: String = "",
)

/**
 * Criterio de evaluación: una faceta de la entrega ("correctitud",
 * "claridad", "fundamentación") con su escala.
 */
@Serializable
data class RubricCriterion(
    /** Id del criterio (estable: es lo que se guarda en la corrección). */
    val id: String = "",
    /** Qué se evalúa. */
    val description: String = "",
    /** Niveles del criterio; si está vacío se usa la escala de la rúbrica. */
    val levels: List<ScaleLevel> = emptyList(),
    /** Peso del criterio en puntos (la suma de todos es el máximo). */
    val points: Int = 0,
)

/**
 * Rúbrica de la tarea: criterios + escala.
 *
 * La usan dos cosas: la IA para **proponer** un nivel por criterio y el
 * docente para **fijar** el nivel final. Es deliberadamente simple: no hay
 * celdas ni matrices, hay criterios y niveles con puntaje.
 */
@Serializable
data class Rubric(
    /** Id de la rúbrica. */
    val id: String = "",
    /** Nombre ("Análisis de algoritmos"). */
    val name: String = "",
    /** Criterios de evaluación. */
    val criteria: List<RubricCriterion> = emptyList(),
    /** Escala común a todos los criterios (si cada criterio tiene la suya, va vacía). */
    val levels: List<ScaleLevel> = emptyList(),
    /** Si el alumno puede ver la rúbrica (lo habitual: sí, para saber qué se espera). */
    val published: Boolean = true,
) {
    /** Máximo de puntos de la rúbrica. */
    val maxPoints: Int get() = criteria.sumOf { it.points }

    /** Niveles que aplican a un criterio: los suyos o los de la rúbrica. */
    fun levelsFor(criterion: RubricCriterion): List<ScaleLevel> =
        if (criterion.levels.isNotEmpty()) criterion.levels else levels

    /** Busca un criterio por id. */
    fun criterion(id: String): RubricCriterion? = criteria.firstOrNull { it.id == id }
}

// ---------------------------------------------------------------------------
// Tarea
// ---------------------------------------------------------------------------

/**
 * Tarea publicada por la cátedra.
 *
 * Es el ancla del subsistema de evaluaciones: define qué se pide, cuándo, cómo
 * se entrega y **qué puede hacer la IA** (ver [allowAi], [chatCanSuggest] y
 * [aiPolicyOverride]).
 *
 * Los alumnos sólo la ven si [published] es `true`.
 */
@Serializable
data class Assignment(
    // ---- Identificación ----
    /** Id de la tarea. Convención: `"<courseId>|tarea-<n>"`. */
    val id: String = "",
    /** Cátedra dueña. */
    val courseId: String = "",

    // ---- Qué se pide ----
    /** Título de la tarea. */
    val title: String = "",
    /** Enunciado en Markdown: es el texto que ve el alumno. */
    val statement: String = "",
    /** Material de referencia asociado (ids de [Material]). */
    val materialIds: List<String> = emptyList(),

    // ---- Cuándo y cuánto ----
    /** Fecha de entrega (epoch millis). 0 = sin fecha. */
    val dueAt: Long = 0L,
    /** Puntos de la tarea. */
    val points: Int = 0,
    /** Tipo de entrega que se pide. */
    val submissionType: SubmissionType = SubmissionType.TEXT,
    /** ¿La tarea admite IA? */
    val allowAi: Boolean = true,
    /** ¿El chat puede *sugerir* respuestas (o sólo dar pistas)? */
    val chatCanSuggest: Boolean = false,
    /** ¿Es formativa (no cuenta) o sumativa (cuenta en la nota)? */
    val assessment: Assessment = Assessment.FORMATIVE,
    /** Publicada: la ven y trabajan los alumnos. */
    val published: Boolean = false,
    /** Rúbrica que usa la IA para pre-evaluar. */
    val rubric: Rubric? = null,
    /** Auditoría. */
    val createdAt: Long = 0L,
    /** Última modificación. */
    val updatedAt: Long = 0L,

    // ---- Política de IA de la tarea ----
    /**
     * Política de IA de esta tarea.
     *
     * Si es `null` manda la de la cátedra. Si está puesta, la tarea sólo
     * puede **cerrar** más, nunca abrir más (ver [effectiveRules]).
     */
    val aiPolicyOverride: AiPolicy? = null,

    // ---- Entrega ----
    /** Cuándo se publicó (0 = sigue en borrador). */
    val publishedAt: Long = 0L,
    /** Entregas máximas por alumno. 1 = una sola entrega. */
    val maxAttempts: Int = 1,
    /** Si se aceptan entregas fuera de plazo. */
    val allowLate: Boolean = false,
    /** Fin del plazo de entrega tardía (0 = sin plazo extra). */
    val lateUntil: Long = 0L,
    /** Etiquetas ("parcial 1", "entrega de código"). */
    val tags: List<String> = emptyList(),
    /** Id del docente que la creó. */
    val createdBy: String = "",
) {
    /** ¿Es sumativa (cuenta en la nota)? */
    val isSummative: Boolean get() = assessment == Assessment.SUMMATIVE

    /** ¿La tarea acepta texto escrito por el alumno? */
    val acceptsText: Boolean get() = submissionType.acceptsText

    /** ¿La tarea acepta archivos adjuntos? */
    val acceptsFiles: Boolean get() = submissionType.acceptsFiles

    /** Plazo efectivo (incluye la ventana de entregas tardías). */
    val effectiveDeadline: Long
        get() = if (allowLate && lateUntil > dueAt) lateUntil else dueAt

    /** ¿Venció el plazo? (sin fecha, nunca vence). */
    fun isPastDue(now: Long): Boolean = effectiveDeadline > 0L && now > effectiveDeadline

    /** ¿La ventana de entrega sigue abierta? */
    fun isOpen(now: Long): Boolean = published && !isPastDue(now)

    /**
     * Reglas de IA **efectivas** de esta tarea, combinando la política de la
     * cátedra con el override de la tarea.
     *
     * La tarea sólo puede cerrar más, nunca abrir más:
     *  - el uso del alumno se calcula con AND (si la cátedra lo prohíbe, la
     *    tarea no lo habilita);
     *  - "sólo pistas" se calcula con OR;
     *  - "declarar el uso" se calcula con OR.
     */
    fun effectiveRules(courseRules: AiRules): AiRules {
        val base = courseRules.narrow(overrideRules)
        val withUse = if (allowAi) base else base.copy(allowsStudentUse = false)
        // Sin permiso de sugerir, la IA no puede proponer un texto: sólo guía.
        return if (chatCanSuggest) withUse else withUse.copy(hintsOnly = true)
    }

    /** Reglas efectivas a partir del nivel de política de la cátedra. */
    fun effectiveRules(coursePolicy: AiPolicy): AiRules = effectiveRules(AiRules.of(coursePolicy))

    /** Nivel canónico de la política efectiva (para el badge de la UI). */
    fun effectivePolicy(coursePolicy: AiPolicy): AiPolicy =
        effectiveRules(coursePolicy).level

    /** Reglas que impone el override de la tarea (si no hay, no cierra nada). */
    private val overrideRules: AiRules
        get() = aiPolicyOverride?.let { AiRules.of(it) } ?: AiRules()

    /**
     * Problemas de la tarea (vacío = válida). Mensajes en español.
     *
     * Es la validación del formulario de creación/edición: una tarea ya
     * publicada y vencida no "está mal", simply pasó el plazo (para eso está
     * [isPastDue]).
     */
    fun validate(now: Long = nowMillis()): List<String> {
        val errors = mutableListOf<String>()
        if (title.isBlank()) errors += "la tarea necesita un título"
        if (statement.isBlank()) errors += "el enunciado no puede estar vacío"
        if (dueAt in 1 until now) errors += "la fecha de entrega debe estar en el futuro"
        if (points < 0) errors += "los puntos no pueden ser negativos"
        if (assessment == Assessment.SUMMATIVE && points <= 0) {
            errors += "una tarea sumativa tiene que valer al menos 1 punto"
        }
        if (!allowAi && chatCanSuggest) {
            errors += "una tarea que no admite IA no puede pedirle sugerencias a la IA"
        }
        if (maxAttempts < 1) errors += "hay que permitir al menos un intento de entrega"
        return errors
    }
}

// ---------------------------------------------------------------------------
// Entrega
// ---------------------------------------------------------------------------

/** De dónde sale un archivo adjunto a la entrega. */
@Serializable
enum class SubmissionFileOrigin(val label: String) {
    /** Lo subió el alumno desde su disco. */
    UPLOAD("Subido por el alumno"),

    /** Lo generó la IA con la herramienta de creación de archivos del chat. */
    AI_ARTIFACT("Generado por la IA"),
    ;

    companion object {
        fun fromName(name: String): SubmissionFileOrigin? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** Archivo adjunto a una entrega. */
@Serializable
data class SubmissionFile(
    /** Nombre visible del archivo. */
    val name: String = "",
    /** Ruta relativa dentro de la carpeta de la entrega. */
    val path: String = "",
    val sizeBytes: Long = 0L,
    val mime: String = "",
    /** ¿Lo subió el alumno o lo generó la IA? */
    val origin: SubmissionFileOrigin = SubmissionFileOrigin.UPLOAD,
    /** Hilo del que salió el archivo (si lo generó la IA). */
    val sourceThreadId: String = "",
    val addedAt: Long = 0L,
)

/**
 * Entrega de una tarea.
 *
 * Nace como borrador, se entrega una vez ([status] = SUBMITTED) y termina
 * corregida ([status] = GRADED).
 *
 * Lo que la hace valiosa para el docente es [threadIds]: los chats en los que
 * el alumno trabajó la tarea. Con eso se ve el *cómo*, no sólo el *qué*.
 */
@Serializable
data class Submission(
    /** Id de la entrega. Convención: `"<courseId>|entrega-<n>"`. */
    val id: String = "",
    /** Tarea a la que corresponde. */
    val assignmentId: String = "",
    /** Cátedra (desnormalizado para consultas). */
    val courseId: String = "",
    /** Alumno. */
    val studentId: String = "",
    /** Texto de la entrega en Markdown. */
    val text: String = "",
    /** Archivos adjuntos. */
    val files: List<SubmissionFile> = emptyList(),
    /** Estado de la entrega. */
    val status: SubmissionStatus = SubmissionStatus.DRAFT,
    /** Declaración del alumno sobre el uso de IA. */
    val aiDeclaration: AiDeclaration = AiDeclaration.NONE,
    /** Chats que el alumno usó para trabajar esta tarea. */
    val threadIds: List<String> = emptyList(),
    /** Cuándo se entregó (0 = sigue en borrador). */
    val submittedAt: Long = 0L,
    /** Cuándo se corrigió (0 = sin corregir). */
    val gradedAt: Long = 0L,
    /** Nota final (la pone el docente al publicar el feedback). */
    val grade: Double? = null,
    /** Comentario del docente (copia corta; el detalle vive en el feedback). */
    val feedback: String = "",

    // ---- Datos de contexto y auditoría ----
    /** Nombre del alumno al entregar (copia: el perfil puede cambiar después). */
    val studentName: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Número de intento (1 = primera entrega). */
    val attempt: Int = 1,
    /** Si es una reentrega: id de la entrega anterior. */
    val revisionOf: String = "",
) {
    /** ¿Es un borrador editable? */
    val isDraft: Boolean get() = status == SubmissionStatus.DRAFT

    /** ¿Ya fue entregada? */
    val isDelivered: Boolean get() = status != SubmissionStatus.DRAFT

    /** ¿Tiene algo entregable? (texto o archivos) */
    val hasContent: Boolean get() = text.isNotBlank() || files.isNotEmpty()

    /** ¿Fue entregada fuera de plazo? */
    fun isLate(dueAt: Long): Boolean = submittedAt > 0L && dueAt > 0L && submittedAt > dueAt

    /** Cantidad de palabras del texto de la entrega. */
    val wordCount: Int
        get() = text.split(Regex("\\s+")).count { it.isNotBlank() }

    /** Archivos que generó la IA (relevantes para la política). */
    val aiGeneratedFiles: List<SubmissionFile>
        get() = files.filter { it.origin == SubmissionFileOrigin.AI_ARTIFACT }

    /** ¿Hay algún rastro de uso de IA (hilos o archivos generados)? */
    val hasAiTrace: Boolean get() = threadIds.isNotEmpty() || aiGeneratedFiles.isNotEmpty()
}

// ---------------------------------------------------------------------------
// Corrección
// ---------------------------------------------------------------------------

/** Estado de la corrección. */
@Serializable
enum class FeedbackStatus(val label: String) {
    /** En preparación: la IA ya propuso, el docente todavía no lo publicó. */
    DRAFT("Borrador"),

    /** Publicado: el alumno lo ve. */
    PUBLISHED("Publicado"),
    ;

    companion object {
        fun fromName(name: String): FeedbackStatus? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** Puntaje del docente por criterio de la rúbrica. */
@Serializable
data class RubricScore(
    /** Criterio evaluado (ver [RubricCriterion.id]). */
    val criterionId: String = "",
    /** Nivel elegido (etiqueta de [ScaleLevel]). */
    val level: String = "",
    /** Puntos dados (si el docente no usa la escala). */
    val points: Double? = null,
    /** Comentario del docente sobre este criterio. */
    val comment: String = "",
)

/** Propuesta de la IA para un criterio de la rúbrica. */
@Serializable
data class RubricSuggestion(
    /** Criterio propuesto. */
    val criterionId: String = "",
    /** Nivel propuesto (etiqueta de [ScaleLevel]). */
    val level: String = "",
    /** Puntos propuestos. */
    val points: Double? = null,
    /** Confianza de la IA (0..1). */
    val confidence: Double = 0.0,
    /** Por qué propone eso. */
    val justification: String = "",
    /** Evidencia: fragmentos de la entrega que sostienen la propuesta. */
    val evidence: List<String> = emptyList(),
)

/**
 * Lo que la IA **sugiere** al docente.
 *
 * No es una nota: es una propuesta con confianza y justificación, que el
 * docente acepta, cambia o ignora. La nota final la pone el docente
 * ([AssignmentFeedback.score]).
 */
@Serializable
data class AiProposal(
    /** Nota sugerida (la final la pone el docente). */
    val suggestedScore: Double? = null,
    /** Sugerencias por criterio de la rúbrica. */
    val rubricSuggestions: List<RubricSuggestion> = emptyList(),
    /** Justificación general. */
    val justification: String = "",
    /** Confianza global (0..1). */
    val confidence: Double = 0.0,
    /** Modelo que generó la propuesta. */
    val modelLabel: String = "",
    /** Cuándo se generó. */
    val generatedAt: Long = 0L,
)

/**
 * Métricas calculadas sobre la entrega y los hilos que la sustentan.
 *
 * Todo lo que hay acá es **determinista** (fundamentación, conteos): sale de
 * funciones puras sobre texto, nunca del modelo. El modelo sólo aparece en
 * [AiProposal].
 */
@Serializable
data class AiMetrics(
    /** Fundamentación del texto de la entrega contra el material. */
    val grounding: GroundingReport = GroundingReport(),
    /** Afirmación por afirmación (el docente ve sólo las excerpts permitidas). */
    val claims: List<GroundingClaim> = emptyList(),
    /** Se detectó uso de IA (hilos vinculados o archivos generados). */
    val detectedAiUse: Boolean = false,
    /** La declaración del alumno no coincide con lo detectado. */
    val declarationMismatch: Boolean = false,
    /** El alumno usó IA en una tarea donde la política lo prohíbe. */
    val policyViolation: Boolean = false,
    /** Hilos analizados. */
    val threadsAnalyzed: Int = 0,
    /** Modelo que ayudó (sólo para la parte de propuesta). */
    val modelLabel: String = "",
    /** Cuándo se calcularon las métricas. */
    val generatedAt: Long = 0L,
)

/**
 * Revisión de una entrega.
 *
 * Nace como borrador con la pre-evaluación de la IA ([aiMetrics] +
 * [aiProposal]) y la publica el docente, que es quien fija la nota real
 * ([score] y [rubricScores]).
 */
@Serializable
data class AssignmentFeedback(
    /** Tarea revisada. */
    val assignmentId: String = "",
    /** Entrega revisada. */
    val submissionId: String = "",
    /** Nota final que asigna el docente. */
    val score: Double? = null,
    /** Comentario del docente (Markdown). */
    val comments: String = "",
    /** Puntaje del docente por criterio. */
    val rubricScores: List<RubricScore> = emptyList(),
    /** Métricas deterministas sobre la entrega y sus hilos. */
    val aiMetrics: AiMetrics? = null,
    /** Propuesta de la IA (el docente la edita; la nota la pone él). */
    val aiProposal: AiProposal? = null,
    /** Auditoría. */
    val createdAt: Long = 0L,

    // ---- Contexto ----
    /** Id del feedback. Convención: `"<submissionId>|fb-<n>"`. */
    val id: String = "",
    val courseId: String = "",
    val studentId: String = "",
    val studentName: String = "",
    /** Docente que corrige. */
    val authorId: String = "",
    val authorName: String = "",
    /** Estado: borrador o publicado. */
    val status: FeedbackStatus = FeedbackStatus.DRAFT,
    /** Cuándo se publicó (0 = sigue en borrador). */
    val publishedAt: Long = 0L,
) {
    /** ¿El alumno ya lo puede ver? */
    val isPublished: Boolean get() = status == FeedbackStatus.PUBLISHED

    /** Puntaje del docente para un criterio. */
    fun scoreFor(criterionId: String): Double? =
        rubricScores.firstOrNull { it.criterionId == criterionId }?.points

    /** Nivel que eligió el docente para un criterio. */
    fun levelFor(criterionId: String): String =
        rubricScores.firstOrNull { it.criterionId == criterionId }?.level.orEmpty()

    /** Propuesta de la IA para un criterio (si la hay). */
    fun proposalFor(criterionId: String): RubricSuggestion? =
        aiProposal?.rubricSuggestions?.firstOrNull { it.criterionId == criterionId }
}

// ---------------------------------------------------------------------------
// Vínculo tarea ↔ chat
// ---------------------------------------------------------------------------

/**
 * Prefijo de la etiqueta que vincula un hilo con una tarea.
 *
 * Convention: `"tarea:<id-de-la-tarea>"`.
 */
const val ASSIGNMENT_TAG_PREFIX: String = "tarea:"

/** Arma la etiqueta que vincula un hilo con una tarea. */
fun assignmentTag(assignmentId: String): String = "$ASSIGNMENT_TAG_PREFIX$assignmentId"

/**
 * La tarea con la que está vinculado este hilo, si hay alguna.
 *
 * TODO(campo dedicado): lo correcto sería que `ChatThread` tuviera un
 * `assignmentIds: Set<String>` (o `assignmentId: String?`) como campo de
 * primera clase, porque las etiquetas son texto libre: un alumno puede
 * escribir "tarea: 3" con un espacio, borrar la etiqueta sin querer o
 * chuspear un tag similar. Con un campo dedicado, además, se puede indexar.
 * Mientras tanto, se deriva de las etiquetas con la convención
 * [ASSIGNMENT_TAG_PREFIX], que es la que usa el resto del código.
 */
fun ChatThread.assignmentIdOrNull(): String? =
    tags.firstOrNull { it.startsWith(ASSIGNMENT_TAG_PREFIX) }
        ?.removePrefix(ASSIGNMENT_TAG_PREFIX)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** Todas las tareas con las que está vinculado este hilo (puede haber varias). */
fun ChatThread.assignmentIds(): List<String> =
    tags.filter { it.startsWith(ASSIGNMENT_TAG_PREFIX) }
        .map { it.removePrefix(ASSIGNMENT_TAG_PREFIX).trim() }
        .filter { it.isNotEmpty() }

/** ¿Este hilo fue usado para trabajar la tarea [assignmentId]? */
fun ChatThread.isLinkedTo(assignmentId: String): Boolean =
    assignmentIds().contains(assignmentId)
