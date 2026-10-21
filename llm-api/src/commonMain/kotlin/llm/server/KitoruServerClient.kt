package llm.server

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import llm.error.LlmErrorCode
import llm.error.LlmException

/**
 * Cliente de la API de gestión del Kitoru-Server (todo bajo `/v1`).
 *
 * Es el cliente que usa la app del docente para emitir y revocar claves y
 * para sincronizar los chats de los alumnos. La inferencia (el chat del
 * alumno) **no** pasa por acá: esa la habla `OpenAiCompatibleGateway`, que
 * ya es compatible con OpenAI.
 *
 * **Autenticación.** El token (`ktor_<kid>_<secret>`) viaja en
 * `Authorization: Bearer`, como en OpenAI. Se agrega **por petición** y no
 * con un plugin global: así el secreto sale únicamente hacia la URL del
 * Kitoru-Server y nunca hacia otro host. Además, el token nunca aparece en
 * un log ni en el mensaje de un error (ver [withoutToken]).
 *
 * **Errores.** El servidor responde con un cuerpo único
 * `{"error":{"code","message"}}`; [ServerErrorMapper] lo traduce a un
 * [LlmException] con el código bien tipado. Ojo con el 429: el servidor
 * desdobla `rate_limited` (reintentable) de `quota_exceeded` (terminal).
 *
 * @param baseUrl URL del servidor. Se acepta tanto la raíz
 *   ("https://ia.universidad.edu/kitoru") como la URL que ya incluye el
 *   prefijo "/v1".
 * @param httpClient cliente HTTP a usar (por defecto, uno nuevo).
 * @param token credencial del Kitoru-Server: key de alumno (`K…`), token de
 *   servicio del docente (`S…`) o de administración (`A…`).
 */
class KitoruServerClient(
    baseUrl: String,
    private val httpClient: HttpClient = HttpClient(),
    private val token: String = "",
) {

    /** Constructor de uso habitual: URL, token y (opcional) cliente HTTP. */
    constructor(
        baseUrl: String,
        token: String,
        httpClient: HttpClient = HttpClient(),
    ) : this(baseUrl, httpClient, token)

    /**
     * Raíz de la API con el prefijo `/v1` garantizado.
     *
     * El contrato entero vive bajo `/v1`, así que se aceptan las dos formas
     * de URL que manejan el docente y la institución: con la raíz y con el
     * prefijo puesto. Así nunca se arma una URL "/v1/v1/keys".
     */
    private val root: String = baseUrl.trimEnd('/').let { base ->
        if (base.endsWith("/v1")) base else "$base/v1"
    }

    // ─────────────────────────────── claves ──────────────────────────────────

    /**
     * Emite una clave nueva para una cátedra.
     *
     * @param courseId código de la cátedra (ej.: "IA-2026-1").
     * @param term cursada (ej.: "2026-2"). La clave nunca vive más allá del
     *   fin de esa cursada.
     * @param label texto libre para la UI del docente.
     * @return la clave creada; [ServerKey.token] trae el secreto, que el
     *   servidor muestra **una sola vez**.
     * @throws LlmException si el servidor rechaza la emisión (cuota de
     *   claves, cursada cerrada, permisos…).
     */
    suspend fun createKey(courseId: String, term: String, label: String): ServerKey {
        val body = buildJsonObject {
            put("courseId", courseId)
            put("term", term)
            put("label", label)
        }
        val raw = call { post("$root/keys") { auth(); jsonBody(body) } }
        return parseObject(raw).toServerKey()
    }

    /** Lista las claves que la credencial puede ver. */
    suspend fun listKeys(): List<ServerKey> {
        val raw = call { get("$root/keys") { auth() } }
        val json = parseJson(raw)
        return objectsIn(json, "keys", "data", "items").map { it.toServerKey() }
    }

    /**
     * Revoca una clave.
     *
     * La revocación es inmediata e irrevocable en el servidor: desde este
     * momento la clave deja de servir para hablar con el modelo.
     *
     * @param id `kid` de la clave (ej.: "K01J9Z8Y2QK4M").
     */
    suspend fun revokeKey(id: String) {
        call { delete("$root/keys/${id.encodeURLPathPart()}") { auth() } }
    }

    /**
     * Renova una clave hasta [until] (epoch ms).
     *
     * El servidor recorta el vencimiento al fin de la cursada: la promesa
     * institucional ("la clave vence al terminar la cursada") es una
     * invariante del servidor, no una convención.
     *
     * @param id `kid` de la clave.
     * @param until nuevo vencimiento (epoch ms).
     */
    suspend fun extendKey(id: String, until: Long) {
        val body = buildJsonObject { put("expiresAt", until) }
        call { post("$root/keys/${id.encodeURLPathPart()}/extend") { auth(); jsonBody(body) } }
    }

    // ─────────────────────────────── modelos ────────────────────────────────

    /**
     * Lista los modelos y alias disponibles (compatible con OpenAI).
     *
     * Es lo que muestra el botón "Probar conexión" del docente.
     */
    suspend fun listModels(): List<String> {
        val raw = call { get("$root/models") { auth() } }
        val json = parseJson(raw)
        return objectsIn(json, "data", "models")
            .map { it.text("id") }
            .filter { it.isNotBlank() }
    }

    // ──────────────────────────────── chats ────────────────────────────────

    /**
     * Empuja un hilo de chat al servidor (para que el docente lo vea).
     *
     * Cada mensaje lleva su `clientMessageId`: si el servidor ya lo tenía
     * responde 409 y el cliente lo trata como **éxito** (el mensaje no se
     * duplica). Por eso un reintento después de un corte de internet es
     * seguro.
     *
     * @param thread el hilo (con su curso y su cursada).
     * @param messages los mensajes del hilo.
     * @return qué aceptó el servidor y qué mensajes ya estaban.
     */
    suspend fun pushChat(
        thread: ServerChatThread,
        messages: List<ServerChatMessage>,
    ): PushResult {
        val body = buildJsonObject {
            put("courseId", thread.courseId)
            put("term", thread.term)
            put("thread", thread.toJson())
            putJsonArray("messages") { messages.forEach { add(it.toJson()) } }
        }
        val raw = request { post("$root/chats") { auth(); jsonBody(body) } }
        if (raw.status == 409) {
            // Idempotencia: el servidor ya tenía el mensaje. No es un error.
            val duplicate = ServerErrorMapper.duplicateMessageId(
                raw.body,
                messages.map { it.clientMessageId },
            )
            if (duplicate != null) {
                return PushResult(accepted = emptyList(), duplicates = listOf(duplicate))
            }
            throw ServerErrorMapper.fromHttp(raw.status, raw.body).withoutToken()
        }
        if (raw.status !in 200..299) {
            throw ServerErrorMapper.fromHttp(raw.status, raw.body).withoutToken()
        }
        return parse(raw.body) { element -> element.toPushResult(messages.map { it.clientMessageId }) }
    }

    /**
     * Trae una página de chats del servidor.
     *
     * La sincronización es incremental: se guarda el [ChatPage.nextSeq] y la
     * próxima llamada pide `afterSeq = nextSeq`, así que ni se pierde ni se
     * duplica un evento.
     *
     * @param courseId cátedra cuyos chats se quieren traer.
     * @param afterSeq cursor: devuelve los eventos con `seq` mayor que este
     *   valor. `null` para empezar desde el principio.
     */
    suspend fun pullChats(courseId: String, afterSeq: Long?): ChatPage {
        val query = buildList {
            add("courseId=${courseId.encodeURLParameter()}")
            if (afterSeq != null) add("afterSeq=$afterSeq")
        }.joinToString("&")
        val raw = call { get("$root/chats?$query") { auth() } }
        return parse(raw) { it.toChatPage() }
    }

    // ────────────────────────────── cátedras ───────────────────────────────

    /** Lista las cátedras que la credencial puede ver. */
    suspend fun listCourses(): List<ServerCourse> {
        val raw = call { get("$root/courses") { auth() } }
        val json = parseJson(raw)
        return objectsIn(json, "courses", "data", "items").map { it.toServerCourse() }
    }

    /** Cierra el cliente HTTP. */
    fun close() = httpClient.close()

    // ────────────────────────────── internals ──────────────────────────────

    /** Respuesta cruda: status y cuerpo (el cuerpo se lee una sola vez). */
    private class RawResponse(val status: Int, val body: String)

    /**
     * Ejecuta la petición y devuelve el cuerpo de la respuesta.
     *
     * @throws LlmException si el servidor responde con un error: el cuerpo
     *   `{"error":{…}}` se traduce con [ServerErrorMapper].
     */
    private suspend fun call(block: suspend HttpClient.() -> HttpResponse): String {
        val raw = request(block)
        if (raw.status !in 200..299) {
            throw ServerErrorMapper.fromHttp(raw.status, raw.body).withoutToken()
        }
        return raw.body
    }

    /**
     * Ejecuta la petición y devuelve la respuesta cruda (status + cuerpo).
     *
     * El cuerpo se lee **una sola vez**: leerlo dos veces en Kitor da error.
     */
    private suspend fun request(block: suspend HttpClient.() -> HttpResponse): RawResponse {
        val response = try {
            httpClient.block()
        } catch (e: CancellationException) {
            // Cancelación real (el usuario cerró la pantalla): no es un error
            // de negocio, se propaga tal cual.
            throw e
        } catch (e: Exception) {
            throw LlmException(
                LlmErrorCode.MODELO_NO_DISPONIBLE,
                "No se pudo conectar con el Kitoru-Server ($root): " +
                    "${e.message ?: "error de red"}.",
                retryable = true,
                cause = e,
            )
        }
        return RawResponse(response.status.value, response.bodyAsText())
    }

    /**
     * Agrega el token a la petición.
     *
     * Va **por petición** (y no con un plugin global) para que la credencial
     * salga únicamente hacia la URL del Kitoru-Server.
     */
    private fun HttpRequestBuilder.auth() {
        if (token.isNotBlank()) bearerAuth(token)
    }

    /** Manda un cuerpo JSON. */
    private fun HttpRequestBuilder.jsonBody(body: JsonObject) {
        contentType(ContentType.Application.Json)
        setBody(body.toString())
    }

    /** Parsea el cuerpo de una respuesta exitosa. */
    private fun parseJson(body: String): JsonElement = try {
        Json.parseToJsonElement(body)
    } catch (e: Exception) {
        throw LlmException(
            LlmErrorCode.DESCONOCIDO,
            "El Kitoru-Server devolvió una respuesta que no es JSON: ${body.take(120)}",
            cause = e,
        ).withoutToken()
    }

    /** Parsea el cuerpo de una respuesta exitosa con el lector indicado. */
    private fun <T> parse(raw: String, reader: (JsonElement) -> T): T =
        reader(parseJson(raw))

    /** Parsea el cuerpo de una respuesta exitosa y exige que sea un objeto. */
    private fun parseObject(raw: String): JsonObject {
        val json = parseJson(raw)
        return json as? JsonObject ?: throw LlmException(
            LlmErrorCode.DESCONOCIDO,
            "El Kitoru-Server devolvió una respuesta inesperada: se esperaba un objeto JSON.",
        ).withoutToken()
    }

    /**
     * Red de seguridad: si un mensaje del servidor incluyera el token por
     * error, se tapa antes de mostrarlo o loguearlo. El token es un secreto:
     * no puede terminar en una excepción ni en un log.
     */
    private fun LlmException.withoutToken(): LlmException {
        if (token.isBlank() || !message.contains(token)) return this
        return LlmException(code, message.replace(token, "«oculto»"), retryable, cause)
    }
}
