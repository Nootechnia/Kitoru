package kitoru.ui.chat

import kitoru.domain.ChatMessage as DomainChatMessage
import kitoru.domain.CitationStats
import kitoru.domain.Course
import kitoru.domain.ModelPolicy
import kitoru.domain.ToolCallStats
import llm.api.LlmGateway
import llm.api.OpenAiCompatibleGateway
import llm.config.ChatMessage
import llm.config.GatewayConfig
import llm.config.LocalModelSettings
import llm.error.LlmException
import rag.agent.AgentAnswer
import rag.agent.AgentEvent
import rag.agent.AgentLoop
import rag.agent.PermissionDecision
import rag.agent.PermissionRequest
import rag.agent.ToolCategory
import rag.agent.ToolContext
import rag.agent.ToolRegistry
import rag.config.RagConfig
import rag.engine.RagEngine
import rag.retrieval.KnowledgeBase

/**
 * Motor de chat del alumno (y del docente, para el botón "probar").
 *
 * Es el mismo circuito de FunshiEngineAI:
 *
 * ```
 * pregunta → RagEngine.prepare (recupera fragmentos y arma el prompt)
 *          → AgentLoop (pide al modelo; si pide una herramienta, la ejecuta
 *            y le pasa el resultado; si no, termina)
 *          → CitationVerifier (comprueba cada cita contra su fuente)
 * ```
 *
 * La diferencia en Kitoru es que el modelo puede ser **el de la cátedra**
 * (servidor de la institución) o **el local del alumno**, y que el
 * verificador de citas es la métrica de calidad que ve el docente.
 */
class ChatEngine(
    private val course: Course,
    private val kb: KnowledgeBase,
    private val tools: ToolRegistry = ToolRegistry(),
    private val toolTimeoutMs: Long = 60_000L,
) {

    /** Gateway activo. Cambia según el modo (servicio o local). */
    @Volatile
    var gateway: LlmGateway? = null
        private set

    /** Configuración del modelo que se está usando ahora mismo. */
    @Volatile
    var effectivePolicy: ModelPolicy = course.modelPolicy
        private set

    /** Motor de preguntas sobre el material de la cátedra. */
    val rag: RagEngine
        get() = RagEngine(
            cfg = RagConfig(
                chunkSize = course.chunkSize,
                topK = effectivePolicy.topK,
            ),
            kb = kb,
            llm = gateway ?: error("El motor todavía no está listo"),
            systemPrompt = { systemPrompt() },
        )

    /**
     * El prompt del sistema de la cátedra.
     *
     * Si el docente no escribió uno, se usa el de por defecto, que tiene las
     * reglas que hacen **medible** la calidad: responder sólo con el contexto
     * y citar con `[n]`.
     */
    private fun systemPrompt(): String =
        course.systemPrompt.ifBlank { course.modelPolicy.systemPrompt }
            .ifBlank { DEFAULT_SYSTEM_PROMPT }

    /**
     * Prepara el gateway para la configuración pedida.
     *
     * En modo REMOTE habla con el servidor de la cátedra (compatible OpenAI).
     * En modo LOCAL el arranque del motor es asíncrono y lo hace el caller
     * (puede tardar en descargar el modelo): se usa [setGateway].
     */
    fun prepare(policy: ModelPolicy): Result<LlmGateway> {
        effectivePolicy = policy
        return when (policy.mode) {
            kitoru.domain.ModelMode.REMOTE -> {
                if (policy.remoteBaseUrl.isBlank()) {
                    Result.failure(
                        LlmException(
                            llm.error.LlmErrorCode.NO_CONFIGURADO,
                            "La cátedra no tiene configurado el servidor de la IA.",
                        ),
                    )
                } else {
                    val gw = OpenAiCompatibleGateway(
                        GatewayConfig(
                            baseUrl = policy.remoteBaseUrl,
                            apiKey = policy.apiKey,
                            model = policy.modelName,
                            temperature = policy.temperature,
                            maxTokens = policy.maxTokens,
                        ),
                    )
                    gateway = gw
                    Result.success(gw)
                }
            }

            kitoru.domain.ModelMode.LOCAL -> Result.failure(
                LlmException(
                    llm.error.LlmErrorCode.NO_CONFIGURADO,
                    "El modelo local se arranca por separado (puede tardar).",
                ),
            )
        }
    }

    /**
     * Fija el gateway (por ejemplo, el del motor local ya arrancado).
     *
     * @param gw el gateway a usar.
     * @param policy la configuración con la que se va a trabajar. Por defecto
     *   es la que ya tenía el motor; se pasa explícita cuando el gateway se
     *   inyecta ya armado (modelo local, o un doble en los tests).
     */
    fun setGateway(gw: LlmGateway, policy: ModelPolicy = effectivePolicy) {
        gateway = gw
        effectivePolicy = policy
    }

    /**
     * Ejecuta una pregunta y devuelve la respuesta con su verificación de
     * citas.
     *
     * @param question lo que escribió el alumno.
     * @param history los turnos anteriores (para el contexto).
     * @param useDocs si se usa el material de la cátedra (sí, por defecto).
     * @param requestPermission se llama cuando el modelo pide una herramienta
     *   que necesita permiso.
     * @param onEvent se llama con cada fragmento de texto para pintar en vivo.
     */
    suspend fun ask(
        question: String,
        history: List<ChatMessage>,
        useDocs: Boolean = true,
        outputsDir: kitoru.platform.FilePath,
        grantedCategories: Set<ToolCategory> = emptySet(),
        requestPermission: suspend (PermissionRequest) -> PermissionDecision =
            { PermissionDecision.DENY },
        onEvent: (AgentEvent) -> Unit = {},
    ): ChatOutcome {
        val gw = gateway ?: throw LlmException(
            llm.error.LlmErrorCode.NO_CONFIGURADO,
            "El modelo todavía no está listo.",
        )
        val engine = RagEngine(
            cfg = RagConfig(chunkSize = course.chunkSize, topK = effectivePolicy.topK),
            kb = kb,
            llm = gw,
            systemPrompt = { systemPrompt() },
        )
        val prepared = engine.prepare(question, history, useDocs)

        // Camino nativo (tool_calls del protocolo OpenAI) o por prompt
        // (bloque delimitado), según el modo del motor.
        val native = effectivePolicy.mode == kitoru.domain.ModelMode.REMOTE
        val loop = AgentLoop(gw, tools, native, toolTimeoutMs)
        val ctx = ToolContext(
            chatId = "kitoru",
            outputsDir = outputsDir,
            grantedCategories = grantedCategories,
            requestPermission = { req ->
                requestPermission(
                    PermissionRequest(
                        chatId = req.chatId,
                        tool = req.tool,
                        category = req.category,
                        summary = req.summary,
                        argsJson = req.argsJson,
                    ),
                )
            },
        )

        val answer: AgentAnswer = loop.run(prepared.messages, ctx, onEvent)

        // Verificación determinista de las citas: no llama al modelo ni la red.
        val citation = if (useDocs && prepared.sources.isNotEmpty()) {
            val report = engine.verify(answer.text, prepared.sources)
            CitationStats(report.verified, report.unsupported, report.unknownCount)
        } else {
            null
        }

        return ChatOutcome(
            text = answer.text,
            sources = prepared.sources.map { it.label },
            citation = citation,
            toolCalls = answer.toolCalls.map {
                ToolCallStats(it.name, it.argsJson, it.ok, it.result)
            },
        )
    }

    /** Cierra el gateway (libera la conexión HTTP). */
    fun close() {
        gateway?.close()
        gateway = null
    }

    companion object {
        /**
         * Prompt del sistema por defecto de una cátedra.
         *
         * Las reglas de citar y de no inventar son las que hacen que el
         * verificador de citas pueda medir la calidad: sin `[n]` no hay
         * nada que verificar.
         */
        val DEFAULT_SYSTEM_PROMPT = """
            Sos el tutor de la cátedra. Respondé en el mismo idioma de la pregunta.

            Reglas:
            - Respondé SIEMPRE basándote en el CONTEXTO que se te entrega.
            - Citá las fuentes con [1], [2]… según los números del CONTEXTO.
            - Si la respuesta no está en el CONTEXTO, decilo explícitamente:
              "No encontré eso en el material de la cátedra".
            - No inventes datos, cifras, fechas ni nombres que no estén en el CONTEXTO.
            - Sé breve y directo. Si la pregunta es ambigua, pedí una aclaración.
        """.trimIndent()
    }
}

/** Resultado de una pregunta del chat. */
data class ChatOutcome(
    val text: String,
    val sources: List<String>,
    val citation: CitationStats?,
    val toolCalls: List<ToolCallStats>,
)
