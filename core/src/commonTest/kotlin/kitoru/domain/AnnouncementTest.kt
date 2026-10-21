package kitoru.domain

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests del dominio de anuncios: validación, audiencia y lecturas.
 */
class AnnouncementTest {

    // -----------------------------------------------------------------------
    // Utilidades
    // -----------------------------------------------------------------------

    /** Aviso válido de referencia. */
    private fun aviso(
        id: String = "catedra-1|anuncio|1",
        courseId: String = "catedra-1",
        title: String = "Clase del martes cancelada",
        body: String = "La clase del martes no se dicta por la lluvia.",
        authorId: String = "prof-1",
        authorName: String = "Ana Gómez",
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
        audience: Audience = Audience.ALL,
        pinned: Boolean = false,
        attachments: List<AnnouncementAttachment> = emptyList(),
        aiGenerated: Boolean = false,
        aiModel: String = "",
        aiPrompt: String = "",
        commentsEnabled: Boolean = true,
    ) = Announcement(
        id = id,
        courseId = courseId,
        title = title,
        body = body,
        authorId = authorId,
        authorName = authorName,
        createdAt = createdAt,
        updatedAt = updatedAt,
        audience = audience,
        pinned = pinned,
        attachments = attachments,
        aiGenerated = aiGenerated,
        aiModel = aiModel,
        aiPrompt = aiPrompt,
        commentsEnabled = commentsEnabled,
    )

    private fun inscripcion(role: CourseRole, userId: String = "u-1") = Enrollment(
        id = "catedra-1-$userId",
        courseId = "catedra-1",
        userId = userId,
        displayName = "Persona $userId",
        role = role,
    )

    // -----------------------------------------------------------------------
    // 1. Validación
    // -----------------------------------------------------------------------

    @Test
    fun `un aviso completo no tiene errores de validación`() {
        assertEquals(emptyList(), aviso().validate())
    }

    @Test
    fun `el aviso necesita un título`() {
        val errores = aviso(title = "  ").validate()
        assertContains(errores, "el aviso necesita un título")
    }

    @Test
    fun `el título no puede superar los 120 caracteres`() {
        val errores = aviso(title = "a".repeat(121)).validate()
        assertContains(errores, "el título no puede superar los 120 caracteres")
    }

    @Test
    fun `un título de 120 caracteres es válido`() {
        val errores = aviso(title = "a".repeat(120)).validate()
        assertEquals(emptyList(), errores)
    }

    @Test
    fun `el aviso necesita un cuerpo`() {
        val errores = aviso(body = " ").validate()
        assertContains(errores, "el aviso necesita un cuerpo")
    }

    @Test
    fun `un aviso generado con IA tiene que registrar el modelo`() {
        val errores = aviso(aiGenerated = true, aiModel = "").validate()
        assertContains(errores, "un aviso generado con IA tiene que registrar el modelo que lo redactó")
    }

    @Test
    fun `un aviso generado con IA con modelo registrado es válido`() {
        val errores = aviso(aiGenerated = true, aiModel = "llama-3.1-8b-instruct").validate()
        assertEquals(emptyList(), errores)
    }

    @Test
    fun `la validación devuelve todos los problemas juntos`() {
        val errores = aviso(title = "", body = "").validate()
        assertEquals(2, errores.size)
    }

    // -----------------------------------------------------------------------
    // 2. La audiencia
    // -----------------------------------------------------------------------

    @Test
    fun `la audiencia decide quién ve el aviso según el rol`() {
        assertTrue(Audience.ALL.includes(CourseRole.ALUMNO))
        assertTrue(Audience.ALL.includes(CourseRole.TITULAR))
        assertTrue(Audience.ALL.includes(CourseRole.AYUDANTE))

        assertTrue(Audience.STUDENTS.includes(CourseRole.ALUMNO))
        assertFalse(Audience.STUDENTS.includes(CourseRole.TITULAR))
        assertFalse(Audience.STUDENTS.includes(CourseRole.AYUDANTE))

        assertFalse(Audience.STAFF.includes(CourseRole.ALUMNO))
        assertTrue(Audience.STAFF.includes(CourseRole.TITULAR))
        assertTrue(Audience.STAFF.includes(CourseRole.AYUDANTE))
    }

    @Test
    fun `una inscripción nula no ve ningún aviso`() {
        assertFalse(Audience.ALL.matches(null))
        assertFalse(Audience.STUDENTS.matches(null))
        assertFalse(Audience.STAFF.matches(null))
    }

    @Test
    fun `la audiencia compara contra la inscripción`() {
        assertTrue(Audience.ALL.matches(inscripcion(CourseRole.ALUMNO)))
        assertTrue(Audience.STUDENTS.matches(inscripcion(CourseRole.ALUMNO)))
        assertFalse(Audience.STAFF.matches(inscripcion(CourseRole.ALUMNO)))
        assertTrue(Audience.STAFF.matches(inscripcion(CourseRole.AYUDANTE)))
    }

    @Test
    fun `toda audiencia tiene etiqueta y descripción`() {
        for (audiencia in Audience.entries) {
            assertTrue(audiencia.label.isNotBlank())
            assertTrue(audiencia.description.isNotBlank())
        }
    }

    // -----------------------------------------------------------------------
    // 3. Las lecturas
    // -----------------------------------------------------------------------

    @Test
    fun `la lectura se registra con el aviso y la persona`() {
        val lectura = AnnouncementRead(
            announcementId = "catedra-1|anuncio|1",
            userId = "ana",
            readAt = 5_000L,
            userName = "Ana",
        )
        assertEquals("catedra-1|anuncio|1", lectura.announcementId)
        assertEquals("ana", lectura.userId)
        assertEquals(5_000L, lectura.readAt)
    }

    @Test
    fun `marcar como leído es idempotente y conserva la primera fecha`() {
        val lecturas = listOf(
            AnnouncementRead(announcementId = "a1", userId = "ana", readAt = 5_000L),
        ).markRead(announcementId = "a1", userId = "ana", at = 9_000L)

        assertEquals(1, lecturas.size)
        assertEquals(5_000L, lecturas.single().readAt, "Releer no puede pisar la primera lectura.")
    }

    @Test
    fun `marcar como leído agrega la lectura que faltaba`() {
        val lecturas = emptyList<AnnouncementRead>()
            .markRead(announcementId = "a1", userId = "ana", at = 7_000L, userName = "Ana")

        assertEquals(1, lecturas.size)
        assertEquals(7_000L, lecturas.single().readAt)
        assertEquals("Ana", lecturas.single().userName)
    }

    @Test
    fun `las lecturas de un aviso son las de ese aviso`() {
        val lecturas = listOf(
            AnnouncementRead(announcementId = "a1", userId = "ana", readAt = 1L),
            AnnouncementRead(announcementId = "a1", userId = "beto", readAt = 2L),
            AnnouncementRead(announcementId = "a2", userId = "carla", readAt = 3L),
        )
        assertEquals(setOf("ana", "beto"), lecturas.readUserIds("a1"))
        assertEquals(setOf("carla"), lecturas.readUserIds("a2"))
    }

    // -----------------------------------------------------------------------
    // 4. Estados derivados
    // -----------------------------------------------------------------------

    @Test
    fun `un aviso recién publicado no aparece como editado`() {
        assertFalse(aviso(createdAt = 1_000L, updatedAt = 1_000L).isEdited)
        assertFalse(aviso(createdAt = 1_000L, updatedAt = 30_000L).isEdited)
        assertTrue(aviso(createdAt = 1_000L, updatedAt = 500_000L).isEdited)
    }

    @Test
    fun `los adjuntos referencian el material de la cátedra`() {
        val adjunto = AnnouncementAttachment(
            materialId = "mat-1",
            title = "Programa 2026",
            path = "materials/programa.pdf",
            kind = MaterialKind.DOCUMENT,
        )
        val anuncio = aviso(attachments = listOf(adjunto))
        assertEquals(1, anuncio.attachments.size)
        assertNotNull(anuncio.attachments.first())
        assertEquals("mat-1", anuncio.attachments.first().materialId)
    }

    @Test
    fun `el aviso guarda el registro de la IA que lo redactó`() {
        val anuncio = aviso(
            aiGenerated = true,
            aiModel = "llama-3.1-8b-instruct",
            aiPrompt = "redactá un aviso de cancelación para mañana",
        )
        assertTrue(anuncio.aiGenerated)
        assertEquals("llama-3.1-8b-instruct", anuncio.aiModel)
        assertEquals("redactá un aviso de cancelación para mañana", anuncio.aiPrompt)
    }

    @Test
    fun `un comentario de un aviso puede ser respuesta de otro`() {
        val comentario = AnnouncementComment(
            id = "c-1",
            announcementId = "catedra-1|anuncio|1",
            courseId = "catedra-1",
            authorId = "ana",
            body = "¿La TP2 también se corre?",
        )
        assertFalse(comentario.isReply)
        assertTrue(comentario.copy(parentId = "c-0").isReply)
    }
}
