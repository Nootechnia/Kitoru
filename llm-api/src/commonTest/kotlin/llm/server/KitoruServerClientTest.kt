package llm.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import llm.error.LlmErrorCode
import llm.error.LlmException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests del cliente del Kitoru-Server.
 *
 * Se usan el `MockEngine` de Ktor: no hay red ni servidor, así que los tests
 * son deterministas y corren en los dos platforms.
 *
 * Lo que se cubre:
 *  - el token viaja en `Authorization: Bearer` y sólo hacia la URL del
 *    servidor,
 *  - cada endpoint pega a su URL (y con sus query params),
 *  - el mapeo de cada código de error, en especial la diferencia entre
 *    `rate_limited` (reintentable) y `quota_exceeded` (terminal),
 *  - el 409 idempotente del push de chats,
 *  - el parseo de las respuestas.
 */
class KitoruServerClientTest {

    private val base = "https://servidor.test"
    private val token = "ktor_K01J9Z8Y2QK4M_x7Fq2mN9pL4vR8tY1uB6cD3eH5gJ0kA"

    // ───────────────────────────── autenticación ─────────────────────────────

    @Test
    fun `manda el token en Authorization y a la URL del servidor`() = runTest {
        val registro = mutableListOf<HttpRequestData>()
        val api = cliente(registro = registro)

        api.listKeys()

        assertEquals(1, registro.size, "se esperaba una sola petición")
        assertEquals(
            "Bearer $token",
            registro.single().headers["Authorization"],
            "el token tiene que viajar como Bearer",
        )
        assertEquals(
            "https://servidor.test/v1/keys",
            registro.single().url.toString(),
            "la petición tiene que ir a la URL del Kitoru-Server",
        )
    }

    @Test
    fun `cada endpoint usa su URL`() = runTest {
        val registro = mutableListOf<HttpRequestData>()
        val api = cliente(registro = registro)

        api.createKey("IA-2026-1", "2026-2", "Ana Ruiz")
        api.listKeys()
        api.revokeKey("K01J9Z8Y2QK4M")
        api.extendKey("K01J9Z8Y2QK4M", 1_798_761_600_000L)
        api.listModels()
        api.pushChat(
            thread = ServerChatThread(id = "thr_7a2", courseId = "IA-2026-1"),
            messages = listOf(ServerChatMessage(clientMessageId = "m_1", role = "user", text = "hola")),
        )
        api.pullChats("IA-2026-1", afterSeq = 10L)
        api.listCourses()

        assertEquals(
            listOf(
                "/v1/keys",
                "/v1/keys",
                "/v1/keys/K01J9Z8Y2QK4M",
                "/v1/keys/K01J9Z8Y2QK4M/extend",
                "/v1/models",
                "/v1/chats",
                "/v1/chats",
                "/v1/courses",
            ),
            registro.map { it.url.encodedPath },
            "cada método tiene que pegarle a su endpoint",
        )
        assertEquals(
            listOf(
                HttpMethod.Post,
                HttpMethod.Get,
                HttpMethod.Delete,
                HttpMethod.Post,
                HttpMethod.Get,
                HttpMethod.Post,
                HttpMethod.Get,
                HttpMethod.Get,
            ),
            registro.map { it.method },
            "cada método tiene que usar su verbo HTTP",
        )
    }

    @Test
    fun `el pull de chats manda el cursor`() = runTest {
        val registro = mutableListOf<HttpRequestData>()
        val api = cliente(registro = registro)

        api.pullChats("IA-2026-1", afterSeq = 8123L)

        assertEquals(
            "https://servidor.test/v1/chats?courseId=IA-2026-1&afterSeq=8123",
            registro.single().url.toString(),
            "el cursor de sincronización va en el query",
        )
    }

    @Test
    fun `sin cursor no manda afterSeq`() = runTest {
        val registro = mutableListOf<HttpRequestData>()
        val api = cliente(registro = registro)

        api.pullChats("IA-2026-1", afterSeq = null)

        assertEquals(
            "https://servidor.test/v1/chats?courseId=IA-2026-1",
            registro.single().url.toString(),
        )
    }

    @Test
    fun `acepta la URL que ya trae el prefijo v1`() = runTest {
        val registro = mutableListOf<HttpRequestData>()
        val api = cliente(baseUrl = "https://servidor.test/v1", registro = registro)

        api.listKeys()

        assertEquals(
            "https://servidor.test/v1/keys",
            registro.single().url.toString(),
            "no debe quedar un /v1/v1 en la URL",
        )
    }

    @Test
    fun `el token no aparece en el mensaje de error`() = runTest {
        val body = """{"apiVersion":1,"error":{"code":"key_invalid","message":"Credencial $token rechazada"}}"""
        val api = cliente(status = HttpStatusCode.Unauthorized, body = body)

        val error = assertFailsWith<LlmException> { api.listKeys() }

        assertFalse(
            error.message.contains(token),
            "el token es un secreto: no puede terminar en un mensaje de error",
        )
    }

    // ────────────────────────────── errores ────────────────────────────────

    @Test
    fun `rate_limited es SIN_CUOTA reintentable`() = runTest {
        val error = errorDelServidor(
            status = HttpStatusCode.TooManyRequests,
            code = "rate_limited",
            message = "Vas muy rápido. Podés hacer 20 peticiones por minuto.",
        )

        assertEquals(LlmErrorCode.SIN_CUOTA, error.code)
        assertTrue(error.retryable, "el servidor dice que se puede reintentar")
    }

    @Test
    fun `quota_exceeded es SIN_CUOTA no reintentable`() = runTest {
        val error = errorDelServidor(
            status = HttpStatusCode.TooManyRequests,
            code = "quota_exceeded",
            message = "Alcanzaste la cuota diaria de la cursada 2026-2.",
        )

        assertEquals(LlmErrorCode.SIN_CUOTA, error.code)
        assertFalse(error.retryable, "la cuota se agotó: reintentar no sirve")
    }

    @Test
    fun `la cuota agotada y el ritmo lento se distinguen`() = runTest {
        val lento = errorDelServidor(HttpStatusCode.TooManyRequests, "rate_limited", "Vas muy rápido.")
        val cuota = errorDelServidor(HttpStatusCode.TooManyRequests, "quota_exceeded", "Se agotó la cuota.")

        assertEquals(lento.code, cuota.code, "los dos son SIN_CUOTA")
        assertTrue(lento.retryable, "rate_limited se reintenta")
        assertFalse(cuota.retryable, "quota_exceeded no se reintenta")
    }

    @Test
    fun `key_expired avisa que la clave vencio`() = runTest {
        val error = errorDelServidor(
            status = HttpStatusCode.Unauthorized,
            code = "key_expired",
            message = "La credencial ya no está vigente.",
        )

        assertEquals(LlmErrorCode.CLAVE_INVALIDA, error.code)
        assertFalse(error.retryable, "la acción es renovar la clave, no reintentar")
        assertTrue(
            error.message.contains("venció"),
            "el mensaje tiene que decir que la clave venció: ${error.message}",
        )
    }

    @Test
    fun `key_invalid es CLAVE_INVALIDA`() = runTest {
        val error = errorDelServidor(
            status = HttpStatusCode.Unauthorized,
            code = "key_invalid",
            message = "La clave no es válida.",
        )

        assertEquals(LlmErrorCode.CLAVE_INVALIDA, error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun `not_found es MODELO_NO_DISPONIBLE`() = runTest {
        val error = errorDelServidor(
            status = HttpStatusCode.NotFound,
            code = "not_found",
            message = "No existe esa clave.",
        )

        assertEquals(LlmErrorCode.MODELO_NO_DISPONIBLE, error.code)
    }

    @Test
    fun `un codigo desconocido es DESCONOCIDO`() = runTest {
        val error = errorDelServidor(
            status = HttpStatusCode.BadRequest,
            code = "algo_raro",
            message = "No se pudo completar la operación.",
        )

        assertEquals(LlmErrorCode.DESCONOCIDO, error.code)
    }

    // ─────────────────────────── push idempotente ──────────────────────────

    @Test
    fun `un 409 con el id del mensaje es un exito`() = runTest {
        val body = """
            {"apiVersion":1,"error":{"code":"duplicate_message","message":"Ya estaba registrado",
             "details":{"clientMessageId":"m_1"}}}
        """.trimIndent()
        val api = cliente(status = HttpStatusCode.Conflict, body = body)

        val resultado = api.pushChat(
            thread = ServerChatThread(id = "thr_7a2", courseId = "IA-2026-1"),
            messages = listOf(ServerChatMessage(clientMessageId = "m_1", role = "user", text = "hola")),
        )

        assertEquals(listOf("m_1"), resultado.duplicates, "el mensaje ya estaba: no es un error")
    }

    @Test
    fun `un 409 que no es de nuestros mensajes si es un error`() = runTest {
        val body = """{"apiVersion":1,"error":{"code":"key_limit_reached","message":"Demasiadas claves."}}"""
        val api = cliente(status = HttpStatusCode.Conflict, body = body)

        assertFailsWith<LlmException> {
            api.pushChat(
                thread = ServerChatThread(id = "thr_7a2", courseId = "IA-2026-1"),
                messages = listOf(ServerChatMessage(clientMessageId = "m_1", role = "user", text = "hola")),
            )
        }
    }

    // ────────────────────────────── parseo ─────────────────────────────────

    @Test
    fun `createKey lee la clave emitida`() = runTest {
        val body = """
            {"apiVersion":1,"id":"K01J9Z8Y2QK4M","token":"$token","tokenLast4":"J0kA",
             "type":"STUDENT_KEY","courseId":"IA-2026-1","term":"2026-2",
             "subject":{"type":"STUDENT","id":"enroll_9f3","displayName":"Ana Ruiz"},
             "label":"Ana Ruiz — IA 2026-2","scope":["chat"],"status":"ACTIVE",
             "createdAt":1772000000000,"expiresAt":1798761600000,"termEndsAt":1798761600000,
             "renewals":0,"maxUses":1,"usedSubjects":0,"lastUsedAt":null,"revokedAt":null,
             "models":["alias:catedra/rapido"]}
        """.trimIndent()
        val api = cliente(status = HttpStatusCode.Created, body = body)

        val key = api.createKey("IA-2026-1", "2026-2", "Ana Ruiz — IA 2026-2")

        assertEquals("K01J9Z8Y2QK4M", key.id)
        assertEquals(token, key.token, "el token viene una sola vez, al emitir")
        assertEquals("J0kA", key.tokenLast4)
        assertEquals("IA-2026-1", key.courseId)
        assertEquals("2026-2", key.term)
        assertEquals("Ana Ruiz", key.subject.displayName)
        assertEquals("ACTIVE", key.status)
        assertTrue(key.isActive)
        assertEquals(1_798_761_600_000L, key.expiresAt)
        assertEquals(listOf("alias:catedra/rapido"), key.models)
    }

    @Test
    fun `createKey manda el cuerpo de la peticion`() = runTest {
        val registro = mutableListOf<HttpRequestData>()
        val api = cliente(registro = registro)

        api.createKey("IA-2026-1", "2026-2", "Ana Ruiz")

        val cuerpo = registro.single().bodyText()
        assertTrue(cuerpo.contains("\"courseId\":\"IA-2026-1\""), "el cuerpo tiene la cátedra: $cuerpo")
        assertTrue(cuerpo.contains("\"term\":\"2026-2\""), "el cuerpo tiene la cursada: $cuerpo")
        assertTrue(cuerpo.contains("\"label\":\"Ana Ruiz\""), "el cuerpo tiene la etiqueta: $cuerpo")
    }

    @Test
    fun `listKeys lee la lista de claves`() = runTest {
        val body = """
            {"apiVersion":1,"keys":[
              {"id":"K01J9Z8Y2QK4M","status":"ACTIVE","courseId":"IA-2026-1","term":"2026-2"},
              {"id":"K01J9Z8Y2QK4N","status":"REVOKED","courseId":"IA-2026-1","term":"2026-2"}
            ],"total":2}
        """.trimIndent()
        val api = cliente(body = body)

        val keys = api.listKeys()

        assertEquals(2, keys.size)
        assertEquals("K01J9Z8Y2QK4M", keys[0].id)
        assertEquals("ACTIVE", keys[0].status)
        assertEquals("REVOKED", keys[1].status)
        assertFalse(keys[1].isActive)
    }

    @Test
    fun `extendKey manda el nuevo vencimiento`() = runTest {
        val registro = mutableListOf<HttpRequestData>()
        val api = cliente(registro = registro)

        api.extendKey("K01J9Z8Y2QK4M", 1_798_761_600_000L)

        assertTrue(
            registro.single().bodyText().contains("\"expiresAt\":1798761600000"),
            "el cuerpo tiene el vencimiento: ${registro.single().bodyText()}",
        )
    }

    @Test
    fun `revokeKey no espera cuerpo`() = runTest {
        val api = cliente(status = HttpStatusCode.NoContent, body = "")

        api.revokeKey("K01J9Z8Y2QK4M")
    }

    @Test
    fun `listModels lee los ids del formato OpenAI`() = runTest {
        val body = """
            {"object":"list","apiVersion":1,"data":[
              {"id":"Qwen3-8B-Instruct","object":"model"},
              {"id":"alias:catedra/rapido","object":"model"}
            ]}
        """.trimIndent()
        val api = cliente(body = body)

        val models = api.listModels()

        assertEquals(listOf("Qwen3-8B-Instruct", "alias:catedra/rapido"), models)
    }

    @Test
    fun `listCourses lee las catedras`() = runTest {
        val body = """
            {"apiVersion":1,"courses":[
              {"id":"IA-2026-1","name":"Inteligencia Artificial","term":"2026-2","joinCode":"IA26"}
            ]}
        """.trimIndent()
        val api = cliente(body = body)

        val courses = api.listCourses()

        assertEquals(1, courses.size)
        assertEquals("IA-2026-1", courses.single().id)
        assertEquals("Inteligencia Artificial", courses.single().name)
        assertEquals("2026-2", courses.single().term)
        assertEquals("IA26", courses.single().joinCode)
    }

    @Test
    fun `pushChat manda el clientMessageId de cada mensaje`() = runTest {
        val registro = mutableListOf<HttpRequestData>()
        val api = cliente(registro = registro)

        api.pushChat(
            thread = ServerChatThread(id = "thr_7a2", courseId = "IA-2026-1", term = "2026-2"),
            messages = listOf(
                ServerChatMessage(clientMessageId = "m_1", role = "user", text = "¿Qué es SVM?"),
                ServerChatMessage(clientMessageId = "m_2", role = "assistant", text = "El margen…"),
            ),
        )

        val cuerpo = registro.single().bodyText()
        assertTrue(cuerpo.contains("\"clientMessageId\":\"m_1\""), "cada mensaje lleva su id: $cuerpo")
        assertTrue(cuerpo.contains("\"clientMessageId\":\"m_2\""), "cada mensaje lleva su id: $cuerpo")
        assertTrue(cuerpo.contains("\"threadId\":\"thr_7a2\"") || cuerpo.contains("\"id\":\"thr_7a2\""),
            "el hilo viaja en el cuerpo: $cuerpo")
    }

    @Test
    fun `pushResult lee lo que acepto el servidor`() = runTest {
        val body = """{"apiVersion":1,"accepted":["m_1","m_2"],"duplicates":[],"seq":8125}"""
        val api = cliente(body = body)

        val resultado = api.pushChat(
            thread = ServerChatThread(id = "thr_7a2", courseId = "IA-2026-1"),
            messages = listOf(
                ServerChatMessage(clientMessageId = "m_1", role = "user"),
                ServerChatMessage(clientMessageId = "m_2", role = "assistant"),
            ),
        )

        assertEquals(listOf("m_1", "m_2"), resultado.accepted)
        assertEquals(8125L, resultado.seq)
    }

    @Test
    fun `pullChats lee los eventos y el cursor`() = runTest {
        val body = """
            {"apiVersion":1,"events":[
              {"seq":8124,"id":"evt_1","courseId":"IA-2026-1","threadId":"thr_7a2",
               "visibility":"FULL","textAvailable":true,
               "messages":[{"id":"m_1","role":"user","text":"¿Me explicás SVM?","createdAt":1772000100000}]}
            ],"nextSeq":8125,"hasMore":false}
        """.trimIndent()
        val api = cliente(body = body)

        val pagina = api.pullChats("IA-2026-1", afterSeq = 8123L)

        assertEquals(1, pagina.chats.size)
        assertEquals(8124L, pagina.chats.single().seq)
        assertEquals("thr_7a2", pagina.chats.single().threadId)
        assertTrue(pagina.chats.single().textAvailable)
        assertEquals("¿Me explicás SVM?", pagina.chats.single().messages.single().text)
        assertEquals(8125L, pagina.nextSeq, "el cursor permite seguir sin perder eventos")
        assertFalse(pagina.hasMore)
    }

    @Test
    fun `un evento sin texto llega con la visibilidad recortada`() = runTest {
        val body = """
            {"apiVersion":1,"events":[
              {"seq":8125,"id":"evt_2","visibility":"METRICS_ONLY","textAvailable":false,"messages":[]}
            ],"nextSeq":8126,"hasMore":false}
        """.trimIndent()
        val api = cliente(body = body)

        val pagina = api.pullChats("IA-2026-1", afterSeq = 8124L)

        assertFalse(pagina.chats.single().textAvailable)
        assertTrue(pagina.chats.single().messages.isEmpty(), "sin texto no hay mensajes")
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    /**
     * Cliente contra un motor simulado: no toca la red y deja registrar lo
     * que se pidió.
     */
    private fun cliente(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "{}",
        baseUrl: String = base,
        registro: MutableList<HttpRequestData> = mutableListOf(),
    ): KitoruServerClient {
        val engine = MockEngine { request ->
            registro += request
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return KitoruServerClient(baseUrl, HttpClient(engine), token)
    }

    /** Provoca un error del servidor y devuelve la excepción tipada. */
    private suspend fun errorDelServidor(
        status: HttpStatusCode,
        code: String,
        message: String,
    ): LlmException {
        val body = """{"apiVersion":1,"error":{"code":"$code","message":"$message"}}"""
        val api = cliente(status = status, body = body)
        return assertFailsWith<LlmException> { api.listKeys() }
    }

    /** Texto del cuerpo de la petición, para verificar lo que se mandó. */
    private suspend fun HttpRequestData.bodyText(): String =
        (body as? TextContent)?.text ?: ""
}
