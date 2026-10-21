package kitoru.testing

import kitoru.domain.AnalyticsAnalyzer
import kitoru.domain.ChatMessage
import kitoru.domain.ChatThread
import kitoru.domain.CitationStats
import kitoru.domain.Course
import kitoru.domain.CourseRole
import kitoru.domain.Enrollment
import kitoru.domain.ChatVisibility
import kitoru.domain.ModelPolicy

/**
 * Constructores de datos de prueba.
 *
 * Tenerlos en un solo lugar evita que cada test invente sus propios valores
 * y hace que los casos sean comparables entre sí.
 */
object Fixtures {

    /** Una cátedra mínima. */
    fun course(
        id: String = "catedra-1",
        name: String = "Inteligencia Artificial",
        code: String = "IA-2026-1",
        term: String = "2026-1",
        policy: ModelPolicy = ModelPolicy(),
    ) = Course(
        id = id,
        code = code,
        name = name,
        term = term,
        modelPolicy = policy,
        systemPrompt = "Respondé con el contexto y citá [1].",
        joinCode = "IA7F3K",
    )

    /** Una inscripción. */
    fun enrollment(
        courseId: String = "catedra-1",
        userId: String = "alumno-1",
        displayName: String = "Alumno 037",
        role: CourseRole = CourseRole.ALUMNO,
        visibility: ChatVisibility = ChatVisibility.FULL,
    ) = Enrollment(
        id = "$courseId-$userId",
        courseId = courseId,
        userId = userId,
        displayName = displayName,
        role = role,
        visibility = visibility,
    )

    /** Un hilo. */
    fun thread(
        id: String = "hilo-1",
        courseId: String = "catedra-1",
        authorId: String = "alumno-1",
        authorName: String = "Alumno 037",
    ) = ChatThread(
        id = id,
        courseId = courseId,
        authorId = authorId,
        authorName = authorName,
        title = "Consultas",
    )

    /** Un mensaje de usuario. */
    fun userMessage(
        id: String = "m1",
        threadId: String = "hilo-1",
        text: String = "¿Qué es un perceptrón?",
        createdAt: Long = 1_000L,
        tokensIn: Int = 10,
    ) = ChatMessage(
        id = id,
        threadId = threadId,
        role = "user",
        text = text,
        createdAt = createdAt,
        tokensIn = tokensIn,
    )

    /** Un mensaje del asistente, con su verificación de citas. */
    fun assistantMessage(
        id: String = "m2",
        threadId: String = "hilo-1",
        text: String = "Un perceptrón es una unidad de cómputo [1].",
        createdAt: Long = 2_000L,
        sources: List<String> = listOf("cátedra:/apuntes.md"),
        citation: CitationStats? = CitationStats(verified = 1, unsupported = 0, unknown = 0),
        latencyMs: Long = 500L,
        tokensOut: Int = 20,
    ) = ChatMessage(
        id = id,
        threadId = threadId,
        role = "assistant",
        text = text,
        createdAt = createdAt,
        sources = sources,
        citation = citation,
        latencyMs = latencyMs,
        tokensOut = tokensOut,
    )
}
