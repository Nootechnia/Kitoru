package llm.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Mensaje de conversación en formato OpenAI.
 *
 * Es el mismo tipo que usa FunshiEngineAI: el gateway lo serializa tal cual
 * hacia `/chat/completions`, así que el backend (RAG y agente) no sabe si
 * detrás hay llama.cpp, Ollama, LM Studio o el Kitoru-Server.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    /**
     * Formato OpenAI: en `role=assistant` con tool call, el array `tool_calls`
     * crudo (para el siguiente round del agente). null normalmente.
     */
    val toolCalls: JsonElement? = null,
    /** En `role=tool`: id del tool call que este mensaje responde. */
    val toolCallId: String? = null,
    /** En `role=tool`: nombre de la herramienta respondida (algunos proveedores). */
    val toolName: String? = null,
) {
    companion object {
        fun system(content: String) = ChatMessage("system", content)
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)
    }
}
