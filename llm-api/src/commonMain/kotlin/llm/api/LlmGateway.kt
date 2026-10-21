package llm.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import llm.config.ChatMessage
import llm.error.LlmErrorCode
import llm.error.LlmException
import kitoru.platform.ioDispatcher

/** Contrato que usa el backend. No sabe si detrás hay llama.cpp, Ollama, OpenAI o el Kitoru-Server. */
interface LlmGateway {
    /**
     * Streaming de la respuesta, tipado: texto de a fragmentos y, si el modelo
     * pidió una herramienta, [LlmChunk.ToolCall] con la llamada completa.
     * [tools] opcional: catálogo de herramientas que el modelo puede pedir.
     *
     * El stream además puede traer [LlmChunk.Usage] (los tokens que informó
     * el servidor) y [LlmChunk.Finish] (por qué terminó la generación).
     */
    fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>? = null): Flow<LlmChunk>

    /** Sólo los fragmentos de texto (lo que pinta el chat sin herramientas). */
    fun chatText(messages: List<ChatMessage>): Flow<String> =
        chatStream(messages).map { chunk -> (chunk as? LlmChunk.Text)?.delta.orEmpty() }

    /** Respuesta completa (sin streaming). */
    suspend fun chat(messages: List<ChatMessage>): String =
        chatText(messages).toList().joinToString("")

    /**
     * Respuesta completa **con el uso de tokens**.
     *
     * Si el servidor informó `usage`, los números son reales; si no, se
     * estiman con [TokenEstimator] y quedan marcados como estimados en
     * [UsageTotals.estimated]. Nunca se disfraza una estimación de un dato.
     */
    suspend fun chatWithUsage(messages: List<ChatMessage>): LlmResponse {
        val text = StringBuilder()
        val acc = UsageAccumulator()
        chatStream(messages).collect { chunk ->
            if (chunk is LlmChunk.Text) text.append(chunk.delta)
            acc.addChunk(chunk)
        }
        if (acc.rounds == 0) {
            // El servidor no mandó `usage`: se estima y queda marcado.
            acc.add(TokenEstimator.estimateConversation(messages), TokenEstimator.estimate(text.toString()))
        }
        return LlmResponse(text.toString(), acc.totals)
    }

    /** Lista los modelos del endpoint (`GET /models`); sirve para "Probar conexión". */
    suspend fun listModels(): List<String> = emptyList()

    fun close() {}
}

/**
 * Respuesta completa con el uso de tokens.
 */
data class LlmResponse(
    /** Texto generado por el modelo. */
    val text: String,
    /** Tokens consumidos (reales o estimados: lo dice [UsageTotals.estimated]). */
    val usage: UsageTotals,
)

/**
 * Cliente para cualquier endpoint /v1/chat/completions con streaming SSE.
 *
 * Soporte de herramientas:
 *  - Si recibe [tools], los envía en el body (`tools[]`, formato OpenAI) y
 *    acumula los `delta.tool_calls` fragmentados del stream para emitirlos como
 *    [LlmChunk.ToolCall] al terminar ([DONE] o cierre del stream).
 *  - Los mensajes `role=tool` (resultados) y `role=assistant` con `tool_calls`
 *    se serializan según el protocolo OpenAI.
 *
 * Captura de uso (lo que antes se perdía):
 *  - El body pide `stream_options: {"include_usage": true}` para que el
 *    servidor mande el bloque `usage` al final del stream.
 *  - El parser lee ese bloque **antes** de mirar `choices`, porque el chunk de
 *    uso viene con `choices: []` y con el `usage` en la raíz del JSON.
 *  - El `finish_reason` se lee aunque el chunk no traiga `delta`: es lo que
 *    permite avisar que la respuesta se cortó por `max_tokens`.
 *
 * Errores tipados ([LlmException]) clasificando status HTTP (401→clave,
 * 429→cuota, 400+tokens→contexto, 5xx→servidor) en vez de `error(...)` crudo.
 * Timeout por petición opcional (`chatTimeoutMs`; 0 = sin límite, que es
 * lo por defecto) sobre el flujo completo.
 *
 * Es **común** a desktop y Android: el motor HTTP lo resuelve el runtime
 * (CIO en escritorio, OkHttp en Android) a partir del classpath.
 */
class OpenAiCompatibleGateway(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val temperature: Double,
    private val maxTokens: Int,
    private val chatTimeoutMs: Long,
    /**
     * Nivel de razonamiento ("off" | "low" | "medium" | "high"). Si no es "off"
     * se envía `reasoning_effort` (Gemini 3, Nvidia, OpenAI, Grok lo soportan).
     */
    private val thinking: String = "off",
    /**
     * Potencia de respuesta ("low" | "medium" | "high"): ajusta `max_tokens`.
     * "medium" respeta [maxTokens] configurado; "low"/"high" lo fijan a 256/4096.
     */
    private val power: String = "medium",
    /**
     * Cliente HTTP a usar. Por defecto se crea uno con el motor del runtime
     * (CIO en escritorio, OkHttp en Android). Se puede inyectar uno de
     * pruebas (el `MockEngine` de Ktor) para testear el protocolo sin red.
     */
    private val httpClient: HttpClient? = null,
) : LlmGateway {

    /** Constructor desde la configuración de dominio ya resuelta. */
    constructor(config: llm.config.GatewayConfig) : this(
        baseUrl = config.baseUrl,
        apiKey = config.apiKey,
        model = config.model,
        temperature = config.temperature,
        maxTokens = config.maxTokens,
        chatTimeoutMs = config.chatTimeoutMs,
        thinking = config.thinking,
        power = config.power,
    )

    /** `max_tokens` efectivo según la potencia pedida. */
    private val effectiveMaxTokens: Int = when (power.lowercase()) {
        "low" -> 256
        "high" -> maxOf(maxTokens, 4096)
        else -> maxTokens
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** true si el endpoint es loopback (llama-server, Ollama, LM Studio locales). */
    private val isLocalUrl: Boolean =
        baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost") || baseUrl.contains("[::1]")

    /**
     * ¿El endpoint soporta `stream_options`?
     *
     * `null` = todavía no sabemos (se manda y se ve). `false` = lo rechazó:
     * no se vuelve a pedir (para no Burning una petición extra por chat).
     */
    private var usageSupported: Boolean? = null

    private val client: HttpClient = httpClient ?: HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 15_000
        }
    }

    /** Convierte un ToolSpec al formato `tools[]` que espera el protocolo OpenAI. */
    private fun toolToBody(spec: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", spec.name)
            put("description", spec.description)
            put("parameters", json.parseToJsonElement(spec.parametersSchema))
        })
    }

    /** Ensambla una conversación a JSON: system/user/assistant/tool con sus extras. */
    private fun messageToBody(m: ChatMessage): JsonObject = buildJsonObject {
        put("role", m.role)
        // Assistant con tool_calls y sin texto: content se omite (algunos
        // proveedores lo exigen). Resto de roles: siempre content.
        val omitContent = m.role == "assistant" && m.content.isEmpty() && m.toolCalls != null
        if (!omitContent) put("content", m.content)
        m.toolCalls?.let { put("tool_calls", it) }
        m.toolCallId?.let { put("tool_call_id", it) }
        m.toolName?.let { put("name", it) }
    }

    /**
     * Error de transporte con contexto útil: incluye la URL y qué revisar.
     */
    private fun transportError(e: Exception): LlmException {
        val detail = e.message ?: e.javaClass.simpleName
        return if (isLocalUrl) {
            LlmException(
                LlmErrorCode.SERVIDOR_CAIDO,
                "No se pudo hablar con el servidor local ($baseUrl): $detail",
                retryable = true, cause = e,
            )
        } else {
            LlmException(
                LlmErrorCode.MODELO_NO_DISPONIBLE,
                "No se pudo conectar con la API ($baseUrl): $detail. " +
                    "Revisá que la URL base sea correcta (debe terminar en /v1) " +
                    "y que el servicio esté accesible.",
                retryable = true, cause = e,
            )
        }
    }

    override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> = flow {
        /**
         * Cuerpo del pedido.
         *
         * `stream_options.include_usage` es lo que hace que el servidor mande
         * el bloque `usage` al final del stream: sin esto no hay forma de
         * saber cuánto costó la respuesta. Los servers que no lo entienden lo
         * ignoran (y si contestan 400, se reintenta sin él: ver
         * [attempt]).
         */
        fun buildBody(withUsage: Boolean): JsonObject = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", temperature)
            put("max_tokens", effectiveMaxTokens)
            // Razonamiento: sólo si el usuario lo encendió.
            if (thinking.isNotBlank() && !thinking.equals("off", ignoreCase = true)) {
                put("reasoning_effort", thinking.lowercase())
            }
            // Pedir el uso real de tokens (es lo que habilita coste y presupuesto).
            if (withUsage) {
                putJsonObject("stream_options") { put("include_usage", true) }
            }
            // Catálogo de herramientas: sólo si el caller las pasó.
            if (!tools.isNullOrEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { add(toolToBody(it)) }
                }
            }
            putJsonArray("messages") {
                messages.forEach { add(messageToBody(it)) }
            }
        }

        // El parser del SSE vive aparte: se puede testear con un payload crudo.
        val parser = ChatCompletionSseParser(json)

        /**
         * Una petición al endpoint y la lectura del stream.
         *
         * Se separa del `try` general para poder reintentar sin `usage` si el
         * servidor rechaza `stream_options`.
         */
        suspend fun request(withUsage: Boolean) {
            client.preparePost("${baseUrl.trimEnd('/')}/chat/completions") {
                contentType(ContentType.Application.Json)
                if (apiKey.isNotBlank()) bearerAuth(apiKey)
                setBody(buildBody(withUsage).toString())
            }.execute { resp ->
                if (!resp.status.isSuccess()) {
                    val text = resp.bodyAsText()
                    if (resp.status.value == 400 && withUsage && mentionsStreamOptions(text)) {
                        throw UsageNotSupported()
                    }
                    throw LlmException.fromHttp(resp.status.value, text, local = isLocalUrl)
                }
                val channel = resp.bodyAsChannel()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    parser.onLine(line).forEach { emit(it) }
                }
            }
        }

        /** Un intento con su propio timeout (el reintento no hereda el anterior). */
        suspend fun attempt(withUsage: Boolean) {
            if (chatTimeoutMs > 0) withTimeout(chatTimeoutMs) { request(withUsage) } else request(withUsage)
        }

        try {
            try {
                attempt(withUsage = usageSupported != false)
            } catch (e: UsageNotSupported) {
                // El endpoint no conoce `stream_options`: se reintenta una sola
                // vez sin él y no se lo vuelve a pedir en esta instancia.
                // Los tokens van a quedar estimados (con la marca puesta).
                usageSupported = false
                attempt(withUsage = false)
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            throw LlmException(LlmErrorCode.TIMEOUT, "La petición superó $chatTimeoutMs ms.", retryable = true, cause = e)
        } catch (e: CancellationException) {
            throw e // cancelación real del usuario: no la convertimos en error
        } catch (e: Exception) {
            throw transportError(e)
        }

        // Terminó el stream: soltar las tool calls acumuladas y el motivo de fin.
        parser.close().forEach { emit(it) }
    }.flowOn(ioDispatcher)

    /**
     * Prueba de conexión: pide `GET {baseUrl}/models` y devuelve los ids.
     * Es lo que usa el botón "Probar conexión", para que un error de URL/clave
     * se vea ANTES de escribir en el chat.
     */
    override suspend fun listModels(): List<String> = try {
        val resp = client.get("${baseUrl.trimEnd('/')}/models") {
            if (apiKey.isNotBlank()) bearerAuth(apiKey)
        }
        if (!resp.status.isSuccess()) {
            throw LlmException.fromHttp(resp.status.value, resp.bodyAsText(), local = isLocalUrl)
        }
        val text = resp.bodyAsText()
        val arr = Json.parseToJsonElement(text).jsonObject["data"]?.let { it as? JsonArray }
            ?: return emptyList()
        arr.mapNotNull { el ->
            el.jsonObject["id"]?.let { v -> try { v.jsonPrimitive.content } catch (_: Exception) { null } }
        }
    } catch (e: LlmException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw transportError(e)
    }

    override fun close() = client.close()
}

/**
 * Error interno: el endpoint rechazó `stream_options`.
 *
 * No es un error de negocio: es la señal de "este servidor no lo soporta",
 * y sirve para reintentar la misma llamada sin ese campo.
 */
private class UsageNotSupported : Exception("El endpoint no soporta stream_options")

/**
 * ¿El cuerpo del error dice que el servidor no conoce `stream_options`?
 *
 * Los proxies y servidores viejos contestan 400 con un texto como
 * "unrecognized field stream_options" o "unknown field".
 */
private fun mentionsStreamOptions(body: String): Boolean =
    body.contains("stream_options", ignoreCase = true) ||
        body.contains("unrecognized", ignoreCase = true) ||
        body.contains("unknown field", ignoreCase = true) ||
        body.contains("not permitted", ignoreCase = true)

/**
 * Parser del SSE de `/chat/completions` (protocolo OpenAI).
 *
 * Vive aparte del cliente HTTP por dos razones:
 *  - se puede testear con un payload crudo, sin red;
 *  - concentra el manejo del chunk de `usage`, que es la parte que antes se
 *    perdía.
 *
 * El orden de lectura importa: el chunk de uso llega con `choices: []` y el
 * bloque `usage` **en la raíz** del JSON. Si se miran las choices primero,
 * el `?: continue` se traga el chunk y los tokens se pierden (que es
 * exactamente lo que pasaba).
 */
class ChatCompletionSseParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /**
     * Acumulación de `tool_calls` fragmentados: cada delta puede traer un
     * trocito de `arguments` (u `id`/`name` en el primer delta de esa call).
     */
    private val pending = LinkedHashMap<Int, PendingCall>()

    /**
     * `finish_reason` que informó el servidor.
     *
     * `null` si el servidor no lo mandó: en ese caso no se inventa un motivo
     * (el fin del flujo ya dice que la generación terminó).
     */
    var finishReason: String? = null
        private set

    /** ¿Se leyó ya el bloque `usage`? (evita duplicados si llega dos veces). */
    private var usageSeen = false

    /**
     * Procesa una línea del stream y devuelve los chunks que produce.
     *
     * Las líneas que no son `data:` (comentarios, `event:`, vacías) se
     * ignoran, igual que el centinela `[DONE]`.
     */
    fun onLine(line: String): List<LlmChunk> {
        if (!line.startsWith("data:")) return emptyList()
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty() || data == "[DONE]") return emptyList()

        // Un chunk malformado no puede voltear el stream entero: se ignora.
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()

        val out = mutableListOf<LlmChunk>()

        // 1) `usage` PRIMERO: viene en la raíz del objeto, con `choices: []`.
        parseUsage(root)?.let { usage ->
            if (!usageSeen) {
                usageSeen = true
                out += usage
            }
        }

        // 2) `choices`: texto, tool calls y el motivo de fin.
        val choice = root["choices"]
            ?.let { it as? JsonArray }
            ?.firstOrNull()
            ?.jsonObject
        if (choice != null) {
            val delta = choice["delta"]?.let { it as? JsonObject }

            // Fragmentos de texto normal.
            delta?.get("content")?.let { c ->
                val token = (c as? JsonPrimitive)?.contentOrNull
                if (!token.isNullOrEmpty()) out += LlmChunk.Text(token)
            }

            // Fragmentos de tool calls: se acumulan por index.
            delta?.get("tool_calls")?.let { raw ->
                (raw as? JsonArray)?.forEach { item ->
                    accumulateToolCall(item as? JsonObject ?: return@forEach)
                }
            }

            // `finish_reason` se lee aunque el chunk NO traiga `delta`: el
            // último chunk del stream suele venir con el `delta` vacío y es
            // justamente el que dice por qué terminó.
            (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { finishReason = it }
        }

        return out
    }

    /**
     * Cierra el stream: devuelve las tool calls acumuladas y, si el servidor
     * mandó `finish_reason`, el motivo de fin.
     *
     * El orden es: primero las tool calls y después `Finish`, que es la
     * señal de "el stream terminó".
     */
    fun close(): List<LlmChunk> {
        val out = mutableListOf<LlmChunk>()
        pending.entries.sortedBy { it.key }.forEach { (_, p) ->
            val name = p.name.toString().trim()
            if (name.isNotEmpty()) {
                out += LlmChunk.ToolCall(
                    id = p.id.toString().ifBlank { name },
                    name = name,
                    argsJson = p.args.toString().ifBlank { "{}" },
                )
            }
        }
        pending.clear()
        finishReason?.let { out += LlmChunk.Finish(it) }
        return out
    }

    /** Acumula un fragmento de `tool_calls` (por `index`). */
    private fun accumulateToolCall(tc: JsonObject) {
        val idx = (tc["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val p = pending.getOrPut(idx) { PendingCall() }
        (tc["id"] as? JsonPrimitive)?.contentOrNull?.let { p.id.append(it) }
        (tc["function"] as? JsonObject)?.let { fn ->
            (fn["name"] as? JsonPrimitive)?.contentOrNull?.let { p.name.append(it) }
            (fn["arguments"] as? JsonPrimitive)?.contentOrNull?.let { p.args.append(it) }
        }
    }

    /**
     * Lee el bloque `usage` de la raíz del chunk.
     *
     * Acepta las variantes que hay en la calle:
     *  - OpenAI: `prompt_tokens`, `completion_tokens`, `total_tokens` y
     *    `prompt_tokens_details.cached_tokens`.
     *  - llama.cpp: lo mismo (además manda `timings`, que no se usa acá).
     *
     * Devuelve `null` si el chunk no trae uso utilizable: un `usage` vacío
     * no es un dato, es ruido.
     */
    private fun parseUsage(root: JsonObject): LlmChunk.Usage? {
        val usage = root["usage"] as? JsonObject ?: return null
        val prompt = usage.int("prompt_tokens")
        val completion = usage.int("completion_tokens")
        val total = usage.int("total_tokens").let { if (it > 0) it else prompt + completion }
        val cached = (usage["prompt_tokens_details"] as? JsonObject)?.int("cached_tokens")
            ?: usage.int("cached_tokens")
        if (prompt <= 0 && completion <= 0 && total <= 0) return null
        return LlmChunk.Usage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = total,
            cachedTokens = cached.coerceAtLeast(0),
        )
    }

    /** Tool call en construcción. */
    private class PendingCall(
        val id: StringBuilder = StringBuilder(),
        val name: StringBuilder = StringBuilder(),
        val args: StringBuilder = StringBuilder(),
    )
}

/** Lee un entero de un objeto JSON (0 si no está o no es un número). */
private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
