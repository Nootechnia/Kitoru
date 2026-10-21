package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Métricas de un alumno dentro de una cátedra.
 *
 * Es el resumen que el docente ve por alumno: volumen de actividad,
 * comportamiento de uso y **calidad** de las respuestas (citas verificadas).
 */
@Serializable
data class StudentAnalytics(
    val userId: String,
    val displayName: String,
    val role: CourseRole,
    /** Mensajes escritos por el alumno. */
    val messages: Int = 0,
    /** Respuestas del modelo recibidas. */
    val replies: Int = 0,
    /** Conversaciones iniciadas. */
    val threads: Int = 0,
    /** Días distintos en los que chatted. */
    val activeDays: Int = 0,
    val lastActivity: Long = 0L,
    /** Latencia media de las respuestas (ms). */
    val avgLatencyMs: Long = 0L,
    /** Tokens estimados consumidos. */
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    /** Citas verificadas / sin respaldo / inexistentes, acumuladas. */
    val citationsVerified: Int = 0,
    val citationsUnsupported: Int = 0,
    val citationsUnknown: Int = 0,
    /** Herramientas invocadas por el modelo. */
    val toolCalls: Int = 0,
    /** Herramientas que fallaron. */
    val toolFailures: Int = 0,
    /** Respuestas que hadaron al menos una cita sin respaldo. */
    val answersWithUnsupportedCitations: Int = 0,
    /**
     * Respuestas del modelo que se pueden evaluar: las que recibieron
     * material en el contexto o las que tienen verificación de citas.
     *
     * Es el denominador de [ungroundedAnswerRate]: una respuesta de chat
     * general (sin material y sin citas verificadas) no es "mala", es
     * **no evaluable**, y no debe entrar en el denominador.
     */
    val groundableReplies: Int = 0,
    /**
     * Respuestas del modelo **sin fundamento verificable**: las que no
     * citada nada y las que citaron un material que no respalda lo que dicen.
     *
     * "No cité nada" no es "respondí con respaldo": una respuesta sin citas
     * no se puede sostener, así que cuenta acá. Si sólo se contaran las
     * citas sin respaldo, un modelo que nunca cita daría una tasa de 0 %
     * (calidad perfecta) cuando en realidad no fundamentó nada.
     */
    val answersWithoutGrounding: Int = 0,
) {
    /** Total de citas. */
    val citationsTotal: Int get() = citationsVerified + citationsUnsupported + citationsUnknown

    /** Proporción de citas respaldadas por su fuente (0..1). */
    val citationSupportRate: Double
        get() = if (citationsTotal == 0) 0.0 else citationsVerified.toDouble() / citationsTotal

    /**
     * Proporción de respuestas sin fundamento verificable (0..1).
     *
     * Es el indicador de "el modelo respondió sin respaldo": 0 es lo ideal y
     * 1 significa que ninguna respuesta se apoya en el material.
     *
     * Es `null` (= "sin datos") cuando no hay respuestas evaluables: el
     * modelo nunca recibió material y no se verificó ninguna cita. Un `0.0`
     * real significa que todas las respuestas evaluadas sí estaban
     * fundamentadas.
     */
    val ungroundedAnswerRate: Double?
        get() = if (groundableReplies == 0) null
        else answersWithoutGrounding.toDouble() / groundableReplies
}

/** Actividad de un día (para el gráfico de comportamiento). */
@Serializable
data class DayActivity(
    /** Día en epoch millis (medianoche local). */
    val day: Long,
    val messages: Int,
    val replies: Int,
)

/** Uso de una fuente del corpus (qué material se consulta más). */
@Serializable
data class SourceUsage(
    val label: String,
    val times: Int,
)

/**
 * Analítica agregada de una cátedra.
 *
 * Es lo que el docente abre para entender cómo se usa la herramienta:
 * quién participa, con qué frecuencia, y qué tan buenas son las respuestas
 * (la calidad se mide con el verificador de citas, que es determinista).
 */
@Serializable
data class CourseAnalytics(
    val courseId: String,
    val students: Int = 0,
    val activeStudents: Int = 0,
    val teachers: Int = 0,
    val totalMessages: Int = 0,
    val totalReplies: Int = 0,
    val totalThreads: Int = 0,
    val avgMessagesPerStudent: Double = 0.0,
    /** Citas respaldadas / citas totales de toda la cátedra. */
    val citationSupportRate: Double = 0.0,
    val citationsVerified: Int = 0,
    val citationsUnsupported: Int = 0,
    val citationsUnknown: Int = 0,
    val avgLatencyMs: Long = 0L,
    val totalTokens: Int = 0,
    val perStudent: List<StudentAnalytics> = emptyList(),
    val perDay: List<DayActivity> = emptyList(),
    val topSources: List<SourceUsage> = emptyList(),
    /** Alumnos sin actividad (inscritos pero nunca chatearon). */
    val inactiveStudents: List<String> = emptyList(),
)
