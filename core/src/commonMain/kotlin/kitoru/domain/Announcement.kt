package kitoru.domain

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Audiencia
// ---------------------------------------------------------------------------

/**
 * A quién va dirigido un aviso.
 *
 * Son tres valores y a propósito: el dominio no tiene grupos ni comisiones,
 * y agregar "subgrupos" arrastraría un ciclo de vida de membresías para un
 * caso que se resuelve con dos avisos ("la TP2 se corre" y "la TP2 no se
 * corre", uno para alumnos y otro para docentes).
 */
@Serializable
enum class Audience(val label: String, val description: String) {
    /** Todos los inscritos: alumnos y docentes. */
    ALL("Toda la cátedra", "Lo ven todos los inscriptos: alumnos y docentes."),

    /** Sólo alumnos. */
    STUDENTS("Sólo alumnos", "Lo ven únicamente los alumnos inscriptos."),

    /** Sólo docentes (titular y ayudantes). */
    STAFF("Sólo docentes", "Lo ven el titular y los ayudantes; los alumnos no lo ven."),
    ;

    /** ¿Una persona con este rol debe ver el aviso? */
    fun includes(role: CourseRole): Boolean = when (this) {
        ALL -> true
        STUDENTS -> role == CourseRole.ALUMNO
        STAFF -> role != CourseRole.ALUMNO
    }

    /** ¿Una inscripción concreta debe ver el aviso? (null = no está inscripto) */
    fun matches(enrollment: Enrollment?): Boolean =
        enrollment != null && includes(enrollment.role)

    companion object {
        fun fromName(name: String): Audience? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

// ---------------------------------------------------------------------------
// Anuncio
// ---------------------------------------------------------------------------

/**
 * Material de la cátedra adjunto a un aviso.
 *
 * Es una **referencia**, no una copia: el material ya vive en el repositorio
 * de materiales y en la carpeta de la cátedra. Se copian el título y la ruta
 * para poder pintar la lista sin hacer el join.
 */
@Serializable
data class AnnouncementAttachment(
    /** Id del material referenciado. */
    val materialId: String = "",
    /** Título para mostrar (copia del material al adjuntarlo). */
    val title: String = "",
    /** Ruta relativa dentro de la carpeta de la cátedra. */
    val path: String = "",
    /** Tipo de material. */
    val kind: MaterialKind = MaterialKind.DOCUMENT,
)

/**
 * Aviso de la cátedra: la vía de comunicación de la materia.
 *
 * Es independiente del chat del alumno: no es contenido generado por la IA
 * sino comunicación de la cátedra, y por eso tiene su propio sistema de
 * lecturas ([AnnouncementRead]) y su propio filtro de audiencia ([Audience]).
 */
@Serializable
data class Announcement(
    /** Id del aviso. Convención: `"<courseId>|anuncio|<n>"`. */
    val id: String = "",
    /** Cátedra a la que pertenece. */
    val courseId: String = "",

    // ---- Contenido ----
    /** Título corto: "Clase del martes cancelada". */
    val title: String = "",
    /** Cuerpo en Markdown: es lo que renderiza la UI. */
    val body: String = "",

    // ---- Autoría ----
    /** Id del autor (docente). */
    val authorId: String = "",
    /**
     * Nombre del autor al publicar.
     *
     * Es una copia a propósito: si el docente cambia su nombre, los avisos
     * viejos siguen mostrando el nombre con el que se publicaron.
     */
    val authorName: String = "",

    // ---- Publicación ----
    /** Momento de publicación (epoch millis). */
    val createdAt: Long = 0L,
    /** Última edición (0 = nunca editado). */
    val updatedAt: Long = 0L,

    // ---- Destinatarios y presentación ----
    /** A quién va dirigido. */
    val audience: Audience = Audience.ALL,
    /** Aviso fijado: va arriba de la lista, aunque el alumno ya lo leyó. */
    val pinned: Boolean = false,
    /** Materiales adjuntos (referencia al material de la cátedra). */
    val attachments: List<AnnouncementAttachment> = emptyList(),

    // ---- Registro de IA ----
    /**
     * El texto fue generado con asistencia de IA.
     *
     * Es un **registro**, no una presentación: sobrevive a que el docente
     * reescriba todo. El crédito en el cuerpo es lo que se ve; esta bandera
     * es lo que queda dicho.
     */
    val aiGenerated: Boolean = false,
    /** Modelo que generó el borrador (ej. "llama-3.1-8b-instruct"). */
    val aiModel: String = "",
    /**
     * Instrucción que el docente dio al modelo (auditoría).
     *
     * No se guarda el prompt completo con el material inyectado: serían
     * kilobytes de texto por aviso.
     */
    val aiPrompt: String = "",

    /** Si se pueden hacer preguntas en el aviso. */
    val commentsEnabled: Boolean = true,
) {
    /**
     * ¿Se editó el aviso?
     *
     * Hay una tolerancia de un minuto para que un guardado inmediatamente
     * posterior a publicar no aparezca como "editado".
     */
    val isEdited: Boolean get() = updatedAt > createdAt + EDIT_TOLERANCE_MS

    /** Problemas del aviso (vacío = válido). Mensajes en español. */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (title.isBlank()) errors += "el aviso necesita un título"
        if (title.length > MAX_TITLE) {
            errors += "el título no puede superar los $MAX_TITLE caracteres"
        }
        if (body.isBlank()) errors += "el aviso necesita un cuerpo"
        if (body.length > MAX_BODY) {
            errors += "el cuerpo no puede superar los $MAX_BODY caracteres"
        }
        if (aiGenerated && aiModel.isBlank()) {
            errors += "un aviso generado con IA tiene que registrar el modelo que lo redactó"
        }
        return errors
    }

    companion object {
        /** Tope del título (es también el título de la notificación). */
        const val MAX_TITLE: Int = 120

        /** Tope del cuerpo. */
        const val MAX_BODY: Int = 8_000

        /** Tolerancia para marcar un aviso como "editado". */
        const val EDIT_TOLERANCE_MS: Long = 60_000L
    }
}

// ---------------------------------------------------------------------------
// Lecturas
// ---------------------------------------------------------------------------

/**
 * Registro de que una persona leyó un aviso.
 *
 * Vale la pena: el caso real es el aviso de cancelación, donde el docente
 * necesita saber si el mensaje llegó. Es metadato del curso, no del chat del
 * alumno: no contradice la regla de visibilidad.
 *
 * La lectura es **idempotente** y conserva la fecha de la *primera* lectura:
 * "lo leyó el martes", no "lo leyó hoy".
 */
@Serializable
data class AnnouncementRead(
    /** Aviso leído. */
    val announcementId: String = "",
    /** Persona que lo leyó. */
    val userId: String = "",
    /** Momento de la primera lectura (no cambia al releer). */
    val readAt: Long = 0L,
    /** Nombre copiado al leer (para la lista del docente sin resolver perfiles). */
    val userName: String = "",
) {
    companion object {
        /** Id estable de la lectura (upsert por aviso + persona). */
        fun idFor(announcementId: String, userId: String): String = "$announcementId|$userId"
    }
}

/**
 * Marca un aviso como leído, de forma idempotente.
 *
 * Conserva la fecha de la **primera** lectura: releer no pisa el `readAt`
 * original. Si la lectura no existía, se agrega con [at].
 */
fun List<AnnouncementRead>.markRead(
    announcementId: String,
    userId: String,
    at: Long,
    userName: String = "",
): List<AnnouncementRead> {
    val index = indexOfFirst { it.announcementId == announcementId && it.userId == userId }
    if (index < 0) {
        return this + AnnouncementRead(
            announcementId = announcementId,
            userId = userId,
            readAt = at,
            userName = userName,
        )
    }
    val previa = this[index]
    val actualizada = previa.copy(
        readAt = if (previa.readAt > 0L) previa.readAt else at,
        userName = previa.userName.ifBlank { userName },
    )
    return toMutableList().also { it[index] = actualizada }
}

/** Ids de las personas que leyeron un aviso. */
fun List<AnnouncementRead>.readUserIds(announcementId: String): Set<String> =
    filter { it.announcementId == announcementId }.map { it.userId }.toSet()

// ---------------------------------------------------------------------------
// Comentarios
// ---------------------------------------------------------------------------

/**
 * Pregunta o respuesta en el hilo de un aviso.
 *
 * Es el canal real docente-alumno: sin comentarios, 40 mensajes privados
 * idénticos. Un solo nivel de anidamiento (una respuesta a una respuesta se
 * aplana), y **sin anonimato**: en un foro de 200 personas el anonimato sólo
 * habilita el abuso.
 */
@Serializable
data class AnnouncementComment(
    /** Id del comentario. */
    val id: String = "",
    /** Aviso del que pertenece. */
    val announcementId: String = "",
    val courseId: String = "",
    /** Autor. */
    val authorId: String = "",
    /** Nombre del autor al publicar. */
    val authorName: String = "",
    /** Rol del autor en la cátedra. */
    val authorRole: CourseRole = CourseRole.ALUMNO,
    /** Cuerpo en Markdown. */
    val body: String = "",
    val createdAt: Long = 0L,
    /** Id del comentario al que responde (un solo nivel de anidamiento). */
    val parentId: String? = null,
    /** Si el texto fue generado con IA (respuesta sugerida al docente). */
    val aiGenerated: Boolean = false,
) {
    /** ¿Es una respuesta a otro comentario? */
    val isReply: Boolean get() = parentId != null
}
