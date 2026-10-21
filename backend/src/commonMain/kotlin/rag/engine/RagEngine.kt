package rag.engine

import kotlinx.coroutines.flow.Flow
import llm.api.LlmGateway
import llm.config.ChatMessage
import rag.config.RagConfig
import rag.retrieval.Chunk
import rag.retrieval.KnowledgeBase

class Answer(val sources: List<Chunk>, val tokens: Flow<String>)

/** Mensajes ya armados para una pregunta, con las fuentes de contexto. */
data class Prepared(val sources: List<Chunk>, val messages: List<ChatMessage>)

/**
 * Motor de preguntas sobre el material de la cátedra.
 *
 * Es el mismo RAG de FunshiEngineAI (BM25 + prompt con CONTEXTO + citas),
 * con dos cambios:
 *  - el prompt del sistema lo provee la **cátedra** (y lo puede editar el
 *    docente), en vez de leerse de un archivo;
 *  - el motor de inferencia entra por [llm.api.LlmGateway], así que puede
 *    ser el servidor de la cátedra o el modelo local del alumno.
 */
class RagEngine(
    private val cfg: RagConfig,
    private val kb: KnowledgeBase,
    private val llm: LlmGateway,
    /**
     * Prompt del sistema de la cátedra. Se evalúa en cada pregunta para que
     * el docente pueda editarlo sin reiniciar.
     */
    private val systemPrompt: () -> String,
) {
    /** Gateway en uso (por si el caller arma un AgentLoop con el mismo motor). */
    val gateway: LlmGateway get() = llm

    /** Config del RAG (topK, historial…). */
    val config: RagConfig get() = cfg

    /**
     * Arma los mensajes de una pregunta (con o sin RAG). Es lo que el
     * AgentLoop usa como base del bucle; separado de la ejecución para que
     * el loop pueda agregar las rondas de herramientas sin recalcular el
     * contexto.
     */
    fun prepare(question: String, history: List<ChatMessage>, useDocs: Boolean): Prepared {
        if (!useDocs) {
            return Prepared(
                emptyList(),
                buildList {
                    add(ChatMessage("system", GENERAL_PROMPT))
                    addAll(history.takeLast(cfg.historyTurns * 2))
                    add(ChatMessage("user", question))
                },
            )
        }
        val lastUser = history.lastOrNull { it.role == "user" }?.content.orEmpty()
        val hits = kb.retrieve("$question $lastUser", cfg.topK)

        val context = if (hits.isEmpty()) "(sin resultados en el material de la cátedra)" else
            hits.mapIndexed { i, c -> "[${i + 1}] (${c.label} — ${c.heading})\n${c.text}" }
                .joinToString("\n\n---\n\n")

        val messages = buildList {
            add(ChatMessage("system", systemPrompt()))
            addAll(history.takeLast(cfg.historyTurns * 2))
            add(ChatMessage("user", "CONTEXTO:\n$context\n\nPREGUNTA:\n$question"))
        }
        return Prepared(hits, messages)
    }

    /** Verifica las citas de una respuesta contra sus fuentes. */
    fun verify(answer: String, sources: List<Chunk>): CitationReport =
        CitationVerifier.verify(answer, sources)

    companion object {
        /** Prompt del modo General (chatear sin depender del material). */
        private val GENERAL_PROMPT = """
            Eres un asistente útil y conversacional.
            Responde en el mismo idioma de la pregunta, de forma clara y concisa.
            Si muestras código, usa bloques de código. No inventes fuentes ni citas.
        """.trimIndent()
    }
}
