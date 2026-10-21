package rag.engine

import kitoru.domain.Course
import kitoru.domain.CoursePromptTemplate
import kitoru.domain.PromptModes
import kitoru.domain.PromptPresets
import kitoru.domain.PromptRenderer
import kitoru.domain.PromptVariables
import llm.config.ChatMessage
import rag.retrieval.Chunk

/**
 * Ensamblado del payload del chat con material.
 *
 * Es el **único** lugar donde se decide la forma del prompt: lo usan el
 * motor ([RagEngine]) y la vista previa del docente, así que lo que el
 * docente ve es exactamente lo que ve el modelo.
 *
 * El payload tiene **tres** mensajes:
 *
 *  1. `system` — el kernel de la app + el prompt del docente (interpolado).
 *  2. `user` — el material, entre `<<<CONTEXTO>>>` y `<<<FIN CONTEXTO>>>`.
 *  3. `user` — la pregunta, entre `<<<PREGUNTA>>>` y `<<<FIN PREGUNTA>>>`.
 *
 * **Aislar la pregunta en su propio mensaje** es la primera defensa contra
 * la inyección de prompt: el modelo no puede confundir la pregunta con el
 * material ni con las reglas.
 */
object PromptAssembler {

    /** Apertura del bloque de material. */
    const val CTX_OPEN = "<<<CONTEXTO>>>"

    /** Cierre del bloque de material. */
    const val CTX_CLOSE = "<<<FIN CONTEXTO>>>"

    /** Apertura del bloque de pregunta. */
    const val Q_OPEN = "<<<PREGUNTA>>>"

    /** Cierre del bloque de pregunta. */
    const val Q_CLOSE = "<<<FIN PREGUNTA>>>"

    /** Separador entre fragmentos dentro del contexto. */
    const val CHUNK_SEPARATOR = "\n\n---\n\n"

    /** El texto del bloque de contexto cuando el RAG no encontró nada. */
    const val CONTEXTO_VACIO = "(sin resultados en el material de la cátedra)"

    /**
     * Prompt del modo general (chat libre, sin material).
     *
     * Sin contexto, **sin citas** ("No inventes fuentes ni citas"): en este
     * modo no hay fuentes, así que cualquier `[n]` es ruido. Tampoco se le
     * aplica la capa 2 de la cátedra: habla de un CONTEXTO que no está.
     */
    val GENERAL = """
        Eres un asistente útil y conversacional.
        Responde en el mismo idioma de la pregunta, de forma clara y concisa.
        Si muestras código, usa bloques de código. No inventes fuentes ni citas.
    """.trimIndent()

    /**
     * Una cabecera de rol (`system:`, `assistant:`…) al comienzo de una línea.
     *
     * El material no puede abrir un turno nuevo de conversación.
     */
    private val ROLE_HEADER = Regex(
        "(?im)^([ \\t]*)(system|assistant|user|tool|developer)([ \\t]*):",
    )

    /**
     * Arma los tres mensajes de una pregunta con material.
     *
     * @param course la cátedra (completa las variables que el caller dejó vacías).
     * @param template la plantilla del docente (capa 2).
     * @param variables los datos de la interpolación.
     * @param question la pregunta del alumno.
     * @param contextChunks los fragmentos recuperados, **en el mismo orden**
     *   que se numeran: el fragmento `n` es `contextChunks[n - 1]`.
     */
    fun assemble(
        course: Course,
        template: CoursePromptTemplate,
        variables: PromptVariables,
        question: String,
        contextChunks: List<Chunk>,
    ): List<ChatMessage> = buildMessages(
        system = buildSystemText(course, template, variables),
        context = formatContext(contextChunks),
        question = question,
    )

    /**
     * El mensaje `system`: el kernel de la app + el prompt del docente.
     *
     * La capa 2 se busca en este orden:
     *  1. el cuerpo de la [template] (interpolado con las variables);
     *  2. el prompt ya interpolado de `course.modelPolicy.systemPrompt`;
     *  3. el prompt de `course.systemPrompt` (interpolado);
     *  4. la plantilla estricta de fábrica.
     */
    fun buildSystemText(
        course: Course,
        template: CoursePromptTemplate,
        variables: PromptVariables,
    ): String {
        val vars = variables.conDatosDe(course)
        val capa2 = when {
            template.cuerpo.isNotBlank() -> PromptRenderer.render(template.cuerpo, vars).text
            course.modelPolicy.systemPrompt.isNotBlank() -> course.modelPolicy.systemPrompt
            course.systemPrompt.isNotBlank() -> PromptRenderer.render(course.systemPrompt, vars).text
            else -> PromptRenderer.render(PromptModes.ESTRICTO.cuerpo, vars).text
        }
        if (capa2.isBlank()) return PromptPresets.KERNEL
        return buildString {
            append(PromptPresets.KERNEL)
            append("\n\n")
            append(PromptPresets.LAYER_2_HEADER)
            append("\n")
            append(capa2.trim())
        }
    }

    /**
     * Los tres mensajes del payload.
     *
     * El orden es: reglas → material → pregunta. La pregunta va **en su
     * propio mensaje** y **delimitada**.
     */
    fun buildMessages(system: String, context: String, question: String): List<ChatMessage> = listOf(
        ChatMessage("system", system),
        ChatMessage("user", "$CTX_OPEN\n$context\n$CTX_CLOSE"),
        ChatMessage("user", "$Q_OPEN\n$question\n$Q_CLOSE"),
    )

    /**
     * El bloque de material, con la numeración que espera el verificador:
     * el fragmento `n` es `contextChunks[n - 1]`.
     */
    fun formatContext(chunks: List<Chunk>): String =
        if (chunks.isEmpty()) {
            CONTEXTO_VACIO
        } else {
            chunks.mapIndexed { i, c ->
                "[${i + 1}] (${sanitize(c.label)} — ${sanitize(c.heading)})\n${sanitize(c.text)}"
            }.joinToString(CHUNK_SEPARATOR)
        }

    /**
     * Sanea el texto del material para que no pueda forzar la estructura del
     * prompt: ni cerrar el bloque de contexto, ni abrir una cabecera de rol
     * o de capa.
     *
     * Es una sustitución **visible** (el docente la ve en la vista previa):
     * no se oculta lo que pasó.
     *
     * Ojo: la *pregunta* del alumno **no** se sanea. El texto del alumno se
     * registra tal cual (los intentos de inyección son datos que el docente
     * quiere ver) y la pregunta va aislada en su propio mensaje.
     */
    fun sanitize(text: String): String = text
        .replace("<<<", "«««")
        .replace(">>>", "»»»")
        .replace("===", "≡≡≡")
        .replace(ROLE_HEADER) { m -> "${m.groupValues[1]}${m.groupValues[2]}≡:" }
}
