package kitoru.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import llm.api.LlmChunk
import llm.api.LlmGateway
import llm.api.ToolSpec
import llm.config.ChatMessage

/**
 * Gateway falso que responde con un guion de rondas.
 *
 * Es el port del `ScriptedGateway` de FunshiEngineAI (que está en
 * `backend/src/test/kotlin/rag/agent/AgentLoopTest.kt`): permite probar el
 * bucle del agente sin red y sin modelo.
 */
class ScriptedGateway(
    /** Un guion por ronda: lo que devuelve el gateway en cada llamada. */
    private val script: List<List<LlmChunk>>,
) : LlmGateway {

    /** Cada request que recibió el gateway (para ver qué se le pasa al modelo). */
    val requests = mutableListOf<List<ChatMessage>>()

    /** El catálogo de herramientas que recibió en cada ronda (null = modo por prompt). */
    val catalogs = mutableListOf<List<ToolSpec>?>()

    /** Los modelos que se listaron en la última llamada a [listModels]. */
    var listedModels: List<String> = listOf("modelo-de-prueba")

    private var round = 0

    override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> {
        requests += messages
        catalogs += tools
        val chunks = script.getOrElse(round) { emptyList() }
        round++
        return flow {
            chunks.forEach { emit(it) }
        }
    }

    override suspend fun listModels(): List<String> = listedModels
}

/**
 * Gateway que siempre falla con el mismo error.
 *
 * Sirve para probar el camino de error del chat (la UI tiene que mostrar un
 * mensaje útil, no un `stack trace`).
 */
class FailingGateway(private val error: Throwable) : LlmGateway {
    override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> =
        flow { throw error }
}
