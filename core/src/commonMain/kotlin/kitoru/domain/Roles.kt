package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Rol dentro de una cátedra.
 *
 * - [TITULAR]: profesor titular. Control total: diseña la cátedra, sube
 *   materiales, fija la configuración base del modelo y ve todos los chats.
 * - [AYUDANTE]: asistente. Puede editar materiales y ver la analítica, pero
 *   no cambiar la configuración base del modelo.
 * - [ALUMNO]: estudiante. Usa el modelo de la cátedra (con la libertad de
 *   ajuste que el docente habilite) y conversa en el chat.
 */
@Serializable
enum class CourseRole(val label: String) {
    TITULAR("Profesor titular"),
    AYUDANTE("Ayudante"),
    ALUMNO("Alumno"),
    ;

    /** ¿Puede diseñar la cátedra (materiales y configuración base)? */
    val canDesign: Boolean get() = this == TITULAR || this == AYUDANTE

    /** ¿Puede cambiar la configuración base del modelo? */
    val canConfigureModel: Boolean get() = this == TITULAR

    companion object {
        fun fromName(name: String): CourseRole? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** Persona dentro de la app (un docente o un alumno, según el contexto). */
@Serializable
data class UserProfile(
    val id: String,
    val displayName: String,
    val email: String = "",
)

/**
 * Qué puede ver el docente de los chats de un alumno.
 *
 * Es la pieza que responde al requisito de "si el alumno usa IA local, el
 * servidor no ve sus chats": en ese caso el alumno puede dejar la cátedra en
 * [METRICS_ONLY] (el docente ve estadísticas y calidad, no el texto) o en
 * [NONE].
 */
@Serializable
enum class ChatVisibility(val label: String, val description: String) {
    FULL(
        "Completo",
        "El docente ve el texto de las conversaciones y el detalle de cada mensaje.",
    ),
    METRICS_ONLY(
        "Solo métricas",
        "El docente ve estadísticas y calidad de las respuestas, pero no el texto.",
    ),
    NONE(
        "Oculto",
        "El docente no ve ni el texto ni las estadísticas de este alumno.",
    ),
    ;

    /** ¿El texto de los mensajes es visible para el docente? */
    val showsText: Boolean get() = this == FULL

    /** ¿Las estadísticas son visibles para el docente? */
    val showsMetrics: Boolean get() = this != NONE
}
