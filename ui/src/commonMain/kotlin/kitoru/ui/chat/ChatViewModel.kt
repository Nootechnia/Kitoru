package kitoru.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kitoru.domain.CitationStats
import kitoru.domain.Course
import kitoru.domain.ModelPolicy
import kitoru.domain.ToolCallStats
import kitoru.platform.FilePath
import kitoru.ui.Clock
import kitoru.ui.SystemClock
import llm.api.LlmGateway
import llm.config.ChatMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rag.agent.AgentEvent
import rag.agent.PermissionDecision
import rag.agent.PermissionRequest
import rag.agent.ToolCategory
import rag.agent.ToolRegistry
import rag.engine.RagEngine
import rag.retrieval.KnowledgeBase

/**
 * Estado de un mensaje en la pantalla.
 *
 * Es la versión UI del modelo de dominio: lo que se ve mientras el modelo
 * escribe (el texto crece) y lo que queda después (citas, fuentes, tools).
 */
data class UiChatMessage(
    val role: String,
    val text: String,
    val sources: List<String> = emptyList(),
    val citation: CitationStats? = null,
    val toolCalls: List<ToolCallStats> = emptyList(),
    /** Error si esta respuesta no se pudo completar. */
    val error: String? = null,
    /** Cuánto tardó el modelo en responder (del reloj inyectado). */
    val latencyMs: Long = 0L,
)

/**
 * ViewModel del chat.
 *
 * Es el port del `ChatViewModel` de FunshiEngineAI, con tres diferencias que
 * importan en Kitoru:
 *
 *  1. Los mensajes viven en un [mutableStateListOf], no en un
 *     `mutableStateOf(list)`: con la lista anterior, **cada token** del
 *     streaming copiaba la lista entera (O(n) por token).
 *  2. El motor (servidor de la cátedra o local) lo elige el alumno: el
 *     `effectivePolicy` se resuelve con la política de la cátedra.
 *  3. Cada respuesta guarda su verificación de citas, que es lo que el
 *     docente ve en la analítica.
 */
class ChatViewModel(
    val course: Course,
    private val kb: KnowledgeBase,
    private val tools: ToolRegistry = ToolRegistry(),
    /**
     * Gateway con el que se habla con el modelo.
     *
     * Si es `null`, el motor se arma solo con la política (el servidor de la
     * cátedra). Los tests inyectan un doble (`ScriptedGateway`) y el modelo
     * local inyecta el suyo cuando ya está arrancado.
     */
    internal val gateway: LlmGateway? = null,
    /**
     * Scope de la app.
     *
     * Se inyecta porque `Dispatchers.Main` no sirve en un test de ViewModel:
     * en el escritorio es el dispatcher de Swing, así que el test avanzaría
     * antes de que la corrutina corra (tests *flaky* por construcción).
     */
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    /**
     * Dispatcher del trabajo pesado (la recuperación del RAG).
     *
     * En la app es `Dispatchers.Default`; los tests inyectan el dispatcher
     * virtual, para que ninguna parte de la respuesta se escape del reloj del
     * test.
     */
    internal val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Reloj: los tests inyectan uno fijo, así que ningún test depende de la
     * hora real.
     */
    internal val clock: Clock = SystemClock,
) {

    /** Los mensajes del hilo, en orden. */
    val messages = mutableStateListOf<UiChatMessage>()

    /** ¿Se está generando una respuesta? */
    var busy by mutableStateOf(false)
        private set

    /** Mensajes encolados mientras el modelo responde. */
    val queued = mutableStateListOf<String>()

    /** Error de la última operación. */
    var error by mutableStateOf("")
        private set

    /** Permiso pedido por el modelo, a la espera de respuesta. */
    var permissionRequest by mutableStateOf<PermissionRequest?>(null)
        private set

    /** Si el chat usa el material de la cátedra (por defecto, sí). */
    var useDocs by mutableStateOf(true)
        private set

    /** Configuración efectiva del modelo (la de la cátedra + los ajustes). */
    var effectivePolicy by mutableStateOf(course.modelPolicy)
        private set

    /** Motor de chat. */
    var engine: ChatEngine? = null
        private set

    private var job: Job? = null

    /**
     * Prepara el motor con la configuración pedida.
     *
     * Si hay un gateway inyectado (modelo local ya arrancado, o el doble de
     * un test), se usa tal cual: no hay nada que preparar y no hay error que
     * mostrar. Si no, se arma el gateway del servidor de la cátedra.
     */
    fun start(policy: ModelPolicy) {
        effectivePolicy = policy
        val eng = ChatEngine(course, kb, tools)
        val gw = gateway
        if (gw != null) {
            eng.setGateway(gw, policy)
        } else {
            val result = eng.prepare(policy)
            result.onFailure { error = it.message ?: "No se pudo preparar el modelo." }
        }
        engine = eng
    }

    /** Cambia entre "usar el material" y "preguntar libre". */
    fun toggleDocs() {
        useDocs = !useDocs
    }

    /**
     * Envía un mensaje.
     *
     * Si el modelo está respondiendo, el mensaje se encola y se manda todo
     * junto cuando termina (como en FunshiEngineAI).
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (busy) {
            queued += trimmed
            return
        }
        sendNow(trimmed)
    }

    private fun sendNow(text: String) {
        val eng = engine
        if (eng == null) {
            error = "El modelo todavía no está listo."
            return
        }
        busy = true
        error = ""

        // El historial que se manda al modelo.
        val history = messages
            .filter { it.text.isNotBlank() && it.error == null }
            .map { ChatMessage(it.role, it.text) }

        messages += UiChatMessage(role = "user", text = text)
        messages += UiChatMessage(role = "assistant", text = "")

        job = scope.launch {
            val builder = StringBuilder()
            var cards = listOf<ToolCallStats>()
            val sourceLabels = mutableListOf<String>()
            val startedAt = clock.nowMillis()

            try {
                val outcome = withContext(workDispatcher) {
                    eng.ask(
                        question = text,
                        history = history,
                        useDocs = useDocs,
                        outputsDir = FilePath("outputs"),
                        grantedCategories = setOf(ToolCategory.NONE),
                        requestPermission = { req ->
                            permissionRequest = req
                            PermissionDecision.DENY
                        },
                        onEvent = { event ->
                            when (event) {
                                is AgentEvent.Text -> {
                                    builder.append(event.delta)
                                    updateLast(builder.toString(), sourceLabels, cards)
                                }

                                is AgentEvent.ToolStart -> {
                                    cards += ToolCallStats(event.name, event.argsJson, null, "")
                                    updateLast(builder.toString(), sourceLabels, cards)
                                }

                                is AgentEvent.ToolEnd -> {
                                    val i = cards.indexOfLast { it.name == event.name && it.ok == null }
                                    cards = cards.toMutableList().also { list ->
                                        if (i >= 0) list[i] = list[i].copy(ok = event.ok, result = event.result)
                                    }
                                    updateLast(builder.toString(), sourceLabels, cards)
                                }
                            }
                        },
                    )
                }
                sourceLabels.clear()
                sourceLabels += outcome.sources
                updateLast(
                    text = outcome.text,
                    sources = outcome.sources,
                    toolCalls = outcome.toolCalls,
                    citation = outcome.citation,
                    latencyMs = clock.nowMillis() - startedAt,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                updateLast("(cancelado)")
            } catch (e: Exception) {
                updateLast(error = e.message ?: "Falló la respuesta del modelo.")
            } finally {
                busy = false
                flushQueue()
            }
        }
    }

    /** Reemplaza el último mensaje (el que se está escribiendo). */
    private fun updateLast(
        text: String = messages.lastOrNull()?.text.orEmpty(),
        sources: List<String> = emptyList(),
        toolCalls: List<ToolCallStats> = emptyList(),
        citation: CitationStats? = null,
        error: String? = null,
        latencyMs: Long? = null,
    ) {
        val last = messages.lastIndex
        if (last < 0) return
        messages[last] = messages[last].copy(
            text = text,
            sources = sources.ifEmpty { messages[last].sources },
            toolCalls = toolCalls.ifEmpty { messages[last].toolCalls },
            citation = citation ?: messages[last].citation,
            error = error,
            latencyMs = latencyMs ?: messages[last].latencyMs,
        )
    }

    /** Manda los mensajes encolados, de a uno. */
    private fun flushQueue() {
        if (queued.isEmpty()) return
        val next = queued.removeAt(0)
        sendNow(next)
    }

    /** Cancela la generación en curso. */
    fun cancel() {
        job?.cancel()
        job = null
        busy = false
        queued.clear()
    }

    /** Borra un mensaje del hilo. */
    fun deleteAt(index: Int) {
        if (index in messages.indices) messages.removeAt(index)
    }

    /** Cierra el chat (libera la conexión del gateway). */
    fun shutdown() {
        cancel()
        engine?.close()
        scope.cancel()
    }
}
