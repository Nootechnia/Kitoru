package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Un hilo de conversación dentro de una cátedra.
 *
 * Un alumno puede tener varios hilos; el docente los ve agregados en la
 * analítica (o el texto, según la visibilidad de la inscripción).
 */
@Serializable
data class ChatThread(
    val id: String,
    val courseId: String,
    /** Autor del hilo (id de usuario). */
    val authorId: String,
    val authorName: String = "",
    val title: String = "Conversación",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Modelo que usó el autor (etiqueta legible). */
    val modelLabel: String = "",
    /** Configuración efectiva con la que se generó (base + ajustes del alumno). */
    val effectiveModel: ModelPolicy = ModelPolicy(),
    /** Etiquetas que el autor puso al hilo (ej. "duda de parcial"). */
    val tags: List<String> = emptyList(),
)

/** Verificación de citas de una respuesta (heredada del verificador de FunshiEngineAI). */
@Serializable
data class CitationStats(
    /** Citas respaldadas por la fuente que citan. */
    val verified: Int = 0,
    /** Citas cuya fuente existe pero no respalda lo que se dice. */
    val unsupported: Int = 0,
    /** Citas que apuntan a una fuente inexistente. */
    val unknown: Int = 0,
) {
    val total: Int get() = verified + unsupported + unknown

    /** Proporción de citas respaldadas (0..1). Sin citas = 0. */
    val supportRate: Double get() = if (total == 0) 0.0 else verified.toDouble() / total

    /** ¿Todas las citas están respaldadas? */
    val fullySupported: Boolean get() = total > 0 && unsupported == 0 && unknown == 0
}

/** Herramienta que el modelo pidió durante una respuesta. */
@Serializable
data class ToolCallStats(
    val name: String,
    val args: String = "",
    /** null = la herramienta se está ejecutando. */
    val ok: Boolean? = null,
    val result: String = "",
    val durationMs: Long = 0L,
)

/**
 * Un mensaje de una conversación.
 *
 * Guarda lo necesario para la analítica: fuentes usadas, verificación de
 * citas, herramientas y latencia. Con eso el docente puede medir la
 * *calidad* de las respuestas sin depender del modelo.
 */
@Serializable
data class ChatMessage(
    val id: String,
    val threadId: String,
    /** "user" o "assistant". */
    val role: String,
    val text: String,
    val createdAt: Long = 0L,
    /** Etiquetas de las fuentes recuperadas (corpus: ruta). */
    val sources: List<String> = emptyList(),
    /** Verificación de citas (sólo en respuestas del asistente). */
    val citation: CitationStats? = null,
    /** Herramientas usadas para producir la respuesta. */
    val toolCalls: List<ToolCallStats> = emptyList(),
    /** Latencia de la respuesta del asistente. */
    val latencyMs: Long = 0L,
    /** Tokens estimados de la respuesta. */
    val tokensOut: Int = 0,
    /** Tokens estimados de la pregunta. */
    val tokensIn: Int = 0,
) {
    val isUser: Boolean get() = role == "user"
    val isAssistant: Boolean get() = role == "assistant"
}
