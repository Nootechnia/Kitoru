package llm.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import llm.config.ChatMessage

/**
 * Tests de la captura de uso (tokens) y del fin de stream.
 *
 * El bug que estos tests fijan: el parser del SSE leía `choices` primero y
 * descartaba el chunk de uso (que viene con `choices: []` y el bloque `usage`
 * en la raíz), así que el consumo de tokens se perdía y no había forma de
 * saber cuánto costaba una respuesta.
 */
class UsageTest {

    // ------------------------------------------------------------------
    // Parser del SSE
    // ------------------------------------------------------------------

    /**
     * El caso que fallaba: el chunk de uso de OpenAI viene con `choices: []`.
     */
    @Test
    fun `el parser captura el usage del chunk con choices vacias`() {
        val sse = """
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"Hola"},"finish_reason":null}]}

            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o","choices":[],"usage":{"prompt_tokens":812,"completion_tokens":47,"total_tokens":859}}

            data: [DONE]
        """.trimIndent()

        val usage = parseSse(sse).filterIsInstance<LlmChunk.Usage>().single()

        assertEquals(812, usage.promptTokens, "tokens del prompt")
        assertEquals(47, usage.completionTokens, "tokens generados")
        assertEquals(859, usage.totalTokens, "tokens totales")
    }

    /**
     * llama.cpp manda el mismo bloque, con los tokens cacheados adentro.
     */
    @Test
    fun `el parser lee los tokens cacheados que manda llama cpp`() {
        val sse = """
            data: {"id":"cmpl-1","object":"chat.completion.chunk","model":"local","choices":[{"index":0,"delta":{"content":"Hola"},"finish_reason":null}],"usage":null}

            data: {"id":"cmpl-1","object":"chat.completion.chunk","model":"local","choices":[],"usage":{"completion_tokens":12,"prompt_tokens":34,"total_tokens":46,"prompt_tokens_details":{"cached_tokens":8}}}

            data: [DONE]
        """.trimIndent()

        val usage = parseSse(sse).filterIsInstance<LlmChunk.Usage>().single()

        assertEquals(34, usage.promptTokens, "tokens del prompt")
        assertEquals(12, usage.completionTokens, "tokens generados")
        assertEquals(46, usage.totalTokens, "tokens totales")
        assertEquals(8, usage.cachedTokens, "tokens servidos desde caché")
        // Lo cacheado no consume presupuesto: (34 − 8) + 12.
        assertEquals(38, usage.billableTokens, "tokens facturables")
    }

    @Test
    fun `el parser captura el finish reason aunque el chunk no traiga delta`() {
        val sse = """
            data: {"choices":[{"index":0,"delta":{"content":"Hola"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"length"}]}

            data: [DONE]
        """.trimIndent()

        val finish = parseSse(sse).filterIsInstance<LlmChunk.Finish>().single()

        assertEquals("length", finish.reason, "motivo de fin")
        assertTrue(finish.truncated, "`length` significa que se cortó por max_tokens")
    }

    @Test
    fun `el parser sigue emitiendo el texto del stream`() {
        val sse = """
            data: {"choices":[{"index":0,"delta":{"content":"Hola"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"content":" ¿qué tal?"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]
        """.trimIndent()

        val chunks = parseSse(sse)

        val text = chunks.filterIsInstance<LlmChunk.Text>().joinToString("") { it.delta }
        assertEquals("Hola ¿qué tal?", text, "el texto no se puede perder")
        assertEquals("stop", chunks.filterIsInstance<LlmChunk.Finish>().single().reason)
    }

    @Test
    fun `sin usage no se inventa un chunk de uso`() {
        val sse = """
            data: {"choices":[{"index":0,"delta":{"content":"Hola"},"finish_reason":"stop"}]}

            data: [DONE]
        """.trimIndent()

        assertTrue(
            parseSse(sse).none { it is LlmChunk.Usage },
            "sin bloque `usage` no hay dato: no se inventa",
        )
    }

    @Test
    fun `un usage vacio no cuenta como dato`() {
        val sse = """
            data: {"choices":[],"usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}

            data: [DONE]
        """.trimIndent()

        assertTrue(
            parseSse(sse).none { it is LlmChunk.Usage },
            "un usage de ceros es ruido, no un dato",
        )
    }

    // ------------------------------------------------------------------
    // Estimador portable (fallback cuando el servidor no manda `usage`)
    // ------------------------------------------------------------------

    @Test
    fun `el estimador cuenta el texto en espanol`() {
        // 33 caracteres ⇒ ceil(33 / 4) = 9 tokens.
        val count = TokenEstimator.estimate("Hola, ¿cómo estás? Bien, gracias.")

        assertEquals(9, count.value, "tokens estimados del texto en español")
        assertTrue(count.estimated, "una estimación tiene que quedar marcada")
    }

    @Test
    fun `el estimador cuenta el texto en ingles`() {
        // 44 caracteres ⇒ ceil(44 / 4) = 11 tokens.
        val count = TokenEstimator.estimate("The quick brown fox jumps over the lazy dog.")

        assertEquals(11, count.value, "tokens estimados del texto en inglés")
        assertTrue(count.estimated, "una estimación tiene que quedar marcada")
    }

    @Test
    fun `el estimador corrige el texto en cjk`() {
        // 4 ideogramas ⇒ ceil(4 / 1,5) = 3 tokens (un ideograma pesa más que
        // un carácter latino: 4 caracteres son ~1 token en un tokenizer BPE).
        val count = TokenEstimator.estimate("你好世界")

        assertEquals(3, count.value, "tokens estimados del texto en CJK")
    }

    @Test
    fun `el estimador corrige los emoji`() {
        // 2 emoji ⇒ 2 tokens cada uno (un emoji suele ser 2 o 3 tokens).
        val count = TokenEstimator.estimate("😀🎉")

        assertEquals(4, count.value, "tokens estimados de los emoji")
    }

    @Test
    fun `el texto vacio no vale tokens`() {
        assertEquals(0, TokenEstimator.estimate("").value, "texto vacío")
        assertEquals(0, TokenEstimator.estimate("   ").value, "texto en blanco")
    }

    @Test
    fun `la conversacion suma el envoltorio del chat`() {
        val messages = listOf(
            ChatMessage.system("Sos un profesor de inteligencia artificial."),
            ChatMessage.user("¿Qué es un gradiente?"),
        )
        val count = TokenEstimator.estimateConversation(messages)

        assertTrue(count.value > 0, "una conversación siempre vale algo")
        assertTrue(count.estimated, "una estimación tiene que quedar marcada")
    }

    // ------------------------------------------------------------------
    // Acumulador (una respuesta y las varias rondas del agente)
    // ------------------------------------------------------------------

    @Test
    fun `el acumulador suma las rondas del agente`() {
        val acc = UsageAccumulator()
        acc.add(LlmChunk.Usage(promptTokens = 100, completionTokens = 20, totalTokens = 120))
        acc.add(LlmChunk.Usage(promptTokens = 150, completionTokens = 30, totalTokens = 180))

        val totals = acc.totals
        assertEquals(250, totals.promptTokens, "tokens de entrada de las dos rondas")
        assertEquals(50, totals.completionTokens, "tokens de salida de las dos rondas")
        assertEquals(300, totals.totalTokens, "tokens totales de las dos rondas")
        assertEquals(2, acc.rounds, "cantidad de llamadas al modelo")
        assertFalse(totals.estimated, "todo vino del servidor: nada es estimado")
    }

    @Test
    fun `el acumulador marca estimado cuando alguna parte fue estimada`() {
        val acc = UsageAccumulator()
        acc.add(LlmChunk.Usage(promptTokens = 100, completionTokens = 20, totalTokens = 120))
        acc.addEstimated(promptTokens = 80, completionTokens = 10)

        val totals = acc.totals
        assertEquals(180, totals.promptTokens, "tokens de entrada")
        assertEquals(30, totals.completionTokens, "tokens de salida")
        assertTrue(totals.estimated, "una parte estimada alcanza para marcar el total")
    }

    @Test
    fun `el acumulador ignora los chunks que no son de uso`() {
        val chunks = listOf(
            LlmChunk.Text("Hola"),
            LlmChunk.Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
            LlmChunk.ToolCall(id = "1", name = "get_time", argsJson = "{}"),
            LlmChunk.Finish("stop"),
        )

        val totals = UsageAccumulator.of(chunks)

        assertEquals(10, totals.promptTokens, "tokens de entrada")
        assertEquals(5, totals.completionTokens, "tokens de salida")
        assertEquals(15, totals.totalTokens, "tokens totales")
    }

    // ------------------------------------------------------------------
    // Gateway de punta a punta (con el MockEngine de Ktor, sin red)
    // ------------------------------------------------------------------

    @Test
    fun `el gateway pide el uso y lo captura del stream`() = runTest {
        var body = ""
        val engine = MockEngine { request ->
            body = (request.body as? TextContent)?.text.orEmpty()
            respond(
                content = SSE_CON_USO,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
        val gateway = gatewayCon(engine)

        val chunks = gateway.chatStream(listOf(ChatMessage.user("Hola"))).toList()

        // El pedido tiene que pedir el `usage`: sin eso el servidor no lo manda.
        val pedido = Json.parseToJsonElement(body).jsonObject
        val streamOptions = pedido["stream_options"]?.jsonObject
        assertTrue(streamOptions != null, "el body tiene que pedir stream_options")
        assertEquals(
            "true",
            streamOptions["include_usage"]?.jsonPrimitive?.content,
            "include_usage tiene que estar en true",
        )

        val usage = chunks.filterIsInstance<LlmChunk.Usage>().single()
        assertEquals(812, usage.promptTokens, "tokens del prompt")
        assertEquals(47, usage.completionTokens, "tokens generados")
        assertEquals(859, usage.totalTokens, "tokens totales")

        val finish = chunks.filterIsInstance<LlmChunk.Finish>().single()
        assertEquals("stop", finish.reason, "motivo de fin")
        assertEquals("Hola ¿qué tal?", chunks.filterIsInstance<LlmChunk.Text>().joinToString("") { it.delta })
    }

    @Test
    fun `el gateway reintenta sin stream options si el servidor lo rechaza`() = runTest {
        val bodies = mutableListOf<String>()
        var primero = true
        val engine = MockEngine { request ->
            bodies += (request.body as? TextContent)?.text.orEmpty()
            if (primero) {
                primero = false
                respond(
                    content = """{"error":{"message":"unrecognized field stream_options"}}""",
                    status = HttpStatusCode.BadRequest,
                )
            } else {
                respond(
                    content = SSE_CON_USO,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                )
            }
        }
        val gateway = gatewayCon(engine)

        val chunks = gateway.chatStream(listOf(ChatMessage.user("Hola"))).toList()

        assertEquals(2, bodies.size, "hubo que reintentar una vez")
        assertTrue(bodies[0].contains("stream_options"), "el primer intento pide el uso")
        assertFalse(bodies[1].contains("stream_options"), "el reintento va sin stream_options")
        assertEquals(
            "Hola ¿qué tal?",
            chunks.filterIsInstance<LlmChunk.Text>().joinToString("") { it.delta },
            "el chat tiene que seguir funcionando",
        )
    }

    // ------------------------------------------------------------------
    // Utilidades del test
    // ------------------------------------------------------------------

    /** Parsea un SSE crudo con el parser del gateway. */
    private fun parseSse(raw: String): List<LlmChunk> {
        val parser = ChatCompletionSseParser()
        val out = mutableListOf<LlmChunk>()
        raw.lineSequence().forEach { out += parser.onLine(it) }
        out += parser.close()
        return out
    }

    /** Gateway apuntando a un motor de prueba. */
    private fun gatewayCon(engine: MockEngine): OpenAiCompatibleGateway = OpenAiCompatibleGateway(
        baseUrl = "http://localhost:9999/v1",
        apiKey = "",
        model = "qwen3-8b",
        temperature = 0.2,
        maxTokens = 512,
        chatTimeoutMs = 0L,
        httpClient = HttpClient(engine),
    )

    companion object {
        /** SSE real de OpenAI con `stream_options.include_usage`. */
        private val SSE_CON_USO = """
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"Hola"},"finish_reason":null}]}

            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o","choices":[{"index":0,"delta":{"content":" ¿qué tal?"},"finish_reason":null}]}

            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o","choices":[],"usage":{"prompt_tokens":812,"completion_tokens":47,"total_tokens":859}}

            data: [DONE]
        """.trimIndent()
    }
}
