package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * La cátedra: el espacio de una materia.
 *
 * Es la unidad que el profesor titular diseña (materiales, configuración base
 * del modelo, instrucciones) y a la que los alumnos se inscriben.
 */
@Serializable
data class Course(
    val id: String,
    /** Código de la materia (ej. "IA-2026-1"). */
    val code: String,
    /** Nombre de la materia (ej. "Inteligencia Artificial"). */
    val name: String,
    /** Cursada (ej. "2026-1"). */
    val term: String = "",
    val description: String = "",
    val createdAt: Long = 0L,

    /** Código con el que los alumnos se anotan a la cátedra. */
    val joinCode: String = "",

    /** Configuración base del modelo que impone la cátedra. */
    val modelPolicy: ModelPolicy = ModelPolicy(),

    /** Instrucciones del sistema por defecto de la cátedra. */
    val systemPrompt: String = "",

    /** Tope de fragmentos que el RAG recupera (lo puede bajar el docente). */
    val topK: Int = 5,

    /** Tamaño del chunk al indexar los materiales. */
    val chunkSize: Int = 1200,

    /** Si la analítica de los chats está habilitada para esta cátedra. */
    val analyticsEnabled: Boolean = true,

    /** Si el docente permite que el alumno use IA local en su dispositivo. */
    val allowLocalModels: Boolean = true,
) {
    /** Nombre corto para las listas. */
    val shortName: String get() = if (code.isBlank()) name else "$code · $name"
}

/**
 * Inscripción de una persona a una cátedra con un rol concreto.
 *
 * Lleva la visibilidad que el docente tiene de los chats de esa persona.
 */
@Serializable
data class Enrollment(
    val id: String,
    val courseId: String,
    val userId: String,
    val displayName: String,
    val role: CourseRole,
    val joinedAt: Long = 0L,
    /** Qué ve el docente de los chats de esta persona. */
    val visibility: ChatVisibility = ChatVisibility.FULL,
) {
    /** Etiqueta "Nombre — Rol" para las listas. */
    val label: String get() = "$displayName — ${role.label}"
}

/** Tipo de material de la cátedra. */
@Serializable
enum class MaterialKind(val label: String) {
    DOCUMENT("Documento"),
    SLIDES("Diapositivas"),
    TRANSCRIPT("Apuntes"),
    LINK("Enlace"),
    OTHER("Otro"),
    ;

    companion object {
        fun fromName(name: String): MaterialKind? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Material subido por la cátedra: es el corpus del RAG.
 *
 * El texto se extrae al indexar y se guarda aparte; [path] apunta al
 * archivo original dentro de la carpeta de la cátedra.
 */
@Serializable
data class Material(
    val id: String,
    val courseId: String,
    val title: String,
    val kind: MaterialKind = MaterialKind.DOCUMENT,
    /** Ruta del archivo original, relativa a la carpeta de la cátedra. */
    val path: String = "",
    val sizeBytes: Long = 0L,
    /** Quién lo subió (id de usuario). */
    val uploadedBy: String = "",
    val uploadedAt: Long = 0L,
    val tags: List<String> = emptyList(),
    /** Si está visible para los alumnos. */
    val visible: Boolean = true,
    /** Texto plano extraído (se usa para el índice y para mostrarlo). */
    val extractedText: String = "",
    /** Advertencia al indexar (ej. "PDF sin capa de texto"). */
    val warning: String = "",
) {
    /** Extensión del archivo original. */
    val extension: String get() = path.substringAfterLast('.', "").lowercase()

    /** Cantidad de caracteres que aporta al índice. */
    val textLength: Int get() = extractedText.length
}
