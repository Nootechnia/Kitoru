package kitoru.sync

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kitoru.domain.ChatMessage
import kitoru.domain.ChatThread
import kitoru.domain.ChatVisibility
import kitoru.domain.CitationStats
import kitoru.domain.Course
import kitoru.domain.CourseRole
import kitoru.domain.Enrollment
import kitoru.domain.Material
import kitoru.domain.MaterialKind
import kitoru.domain.ModelPolicy
import kitoru.domain.ToolCallStats
import kitoru.platform.nowMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * El transporte de la API de sincronización: lo mínimo que el cliente necesita.
 *
 * Existe para que el cliente no dependa de ninguna implementación concreta de
 * HTTP: los tests usan un doble y la app puede usar el cliente del servidor.
 *
 * **Integración con el cliente del servidor:** `SyncClient` no depende de
 * `KitoruServerClient` (que vive en `:llm-api`): habla con un [SyncTransport].
 * Para conectarlos hay que pasarle una implementación de esta interfaz; la
 * que trae este módulo, [KtorSyncTransport], habla el mismo contrato HTTP con
 * las cabeceras de la API, así que sirve de puente mientras tanto.
 */
interface SyncTransport {

    /** Un GET contra la API. */
    suspend fun get(
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): SyncResponse

    /** Un POST contra la API. */
    suspend fun post(
        path: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): SyncResponse

    /**
     * El heartbeat: `GET /v1/ping`.
     *
     * Es la señal que manda para saber si hay conexión: no dice "hay
     * internet", dice "el servidor de la institución está vivo".
     */
    suspend fun ping(): Boolean = true
}

/**
 * La respuesta cruda del transporte.
 *
 * @property status el código HTTP.
 * @property body el cuerpo, tal cual lo devolvió el servidor.
 * @property headers las cabeceras de la respuesta.
 */
data class SyncResponse(
    val status: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
) {

    /** ¿La respuesta fue exitosa? */
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * Los tres papeles de autenticación, separados.
 *
 * La `inferenceKey` es un **permiso de consumo** (llamar al modelo), no una
 * identidad: si valiera para sincronizar, un alumno podría leer el padrón de
 * la cátedra o subir chats en nombre de otro. Por eso la API de
 * sincronización usa **sólo** el `syncToken`, y el `sessionToken` (SSO del
 * docente) para las operaciones de escritura de la cátedra.
 *
 * @property syncToken el token de sincronización: habilita los endpoints de la
 *   cátedra.
 * @property sessionToken el token de sesión del docente.
 * @property inferenceKey la API key de inferencia: nunca va a esta API.
 */
data class SyncCredentials(
    val syncToken: String = "",
    val sessionToken: String = "",
    val inferenceKey: String = "",
) {

    init {
        require(syncToken.isBlank() || syncToken != inferenceKey) {
            "La API key de inferencia no puede ser el token de sincronización: son permisos distintos."
        }
    }

    companion object {

        /** Sin credenciales: el cliente falla con un error del servidor. */
        val EMPTY = SyncCredentials()
    }
}

/**
 * Un error de la API de sincronización.
 *
 * El mensaje viene del servidor y está en español: la UI lo muestra tal cual.
 *
 * @property status el código HTTP.
 * @property code el código de error del servidor ("cursor_expired", …).
 */
class SyncException(
    val status: Int,
    val code: String,
    override val message: String,
) : Exception(message) {

    /** ¿Vale la pena reintentar? */
    val retryable: Boolean get() = status == 408 || status == 429 || status >= 500

    companion object {

        /** Convierte una respuesta fallida en una excepción con contexto. */
        fun from(response: SyncResponse): SyncException {
            val body = runCatching { SyncJson.parseToJsonElement(response.body) }.getOrNull()
            val error = body?.jsonObject?.get("error")?.jsonObject
            val code = error?.get("code")?.jsonPrimitive?.contentOrNull
                ?: "http_${response.status}"
            val message = error?.get("message")?.jsonPrimitive?.contentOrNull
                ?: "El servidor rechazó la operación (HTTP ${response.status})."
            return SyncException(response.status, code, message)
        }
    }
}

/**
 * El transporte sobre Ktor.
 *
 * Habla el mismo contrato que el cliente del servidor: `Authorization: Bearer`
 * con el token de sincronización, `X-Kitoru-Protocol: 1` en toda petición y la
 * cabecera `Idempotency-Key` en los POST de la cola.
 */
class KtorSyncTransport(
    private val baseUrl: String,
    private val credentials: () -> SyncCredentials,
    private val client: HttpClient,
) : SyncTransport {

    override suspend fun get(
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
    ): SyncResponse {
        val response = client.get(url(path, query)) { applyHeaders(headers) }
        return response.toSyncResponse()
    }

    override suspend fun post(
        path: String,
        body: String,
        headers: Map<String, String>,
    ): SyncResponse {
        val response = client.post(url(path, emptyMap())) {
            applyHeaders(headers)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return response.toSyncResponse()
    }

    override suspend fun ping(): Boolean = try {
        client.get(url("/v1/ping", emptyMap())) { applyHeaders(emptyMap()) }.status.isSuccess()
    } catch (_: Exception) {
        false
    }

    /** Las cabeceras comunes: el protocolo y el token de sincronización. */
    private fun HttpRequestBuilder.applyHeaders(extra: Map<String, String>) {
        header(PROTOCOL_HEADER, PROTOCOL_VERSION)
        val token = credentials().syncToken
        if (token.isNotBlank()) bearerAuth(token)
        extra.forEach { (name, value) -> header(name, value) }
    }

    /** La URL completa, con la consulta escapada. */
    private fun url(path: String, query: Map<String, String>): String {
        val base = baseUrl.trimEnd('/')
        val full = if (path.startsWith("/")) "$base$path" else "$base/$path"
        val params = query.entries
            .filter { it.value.isNotBlank() }
            .joinToString("&") { (name, value) ->
                "${name.encodeURLParameter()}=${value.encodeURLParameter()}"
            }
        return if (params.isBlank()) full else "$full?$params"
    }

    private companion object {
        const val PROTOCOL_HEADER = "X-Kitoru-Protocol"
        const val PROTOCOL_VERSION = "1"
    }
}

/**
 * Un cliente HTTP con los timeouts de la API.
 *
 * El motor no se elige acá: `HttpClient()` sin argumento resuelve CIO en el
 * escritorio y OkHttp en Android por el classpath. Escribir `HttpClient(CIO)`
 * en `commonMain` rompe el target de Android.
 */
fun syncHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }
}

/**
 * El cliente de la API de sincronización.
 *
 * Habla con el Kitoru-Server: manifiesto, materiales, chats e inscripciones.
 * Los cursores son **opacos**: se guardan y se mandan, nunca se interpretan.
 *
 * No depende de `KitoruServerClient` (que vive en `:llm-api`): el transporte
 * se inyecta, así que los tests pueden usar un doble y la app puede usar el
 * cliente del servidor sin que este módulo dependa de él.
 */
class SyncClient(
    private val transport: SyncTransport,
    private val clientId: String = "",
) {

    /**
     * El heartbeat: `true` si el servidor respondió.
     *
     * Es la señal que manda para saber si hay conexión.
     */
    suspend fun ping(): Boolean = transport.ping()

    /**
     * El manifiesto de la cátedra: qué cambió desde el [cursor].
     *
     * @param courseId la cátedra.
     * @param cursor el cursor opaco de la última sincronización; vacío ⇒
     *   manifiesto completo.
     * @param include qué bloques traer. Por defecto no se pide el padrón: un
     *   alumno que lo pide recibe un `403`.
     */
    suspend fun fetchManifest(
        courseId: String,
        cursor: String = "",
        include: List<String> = listOf("course", "materials"),
    ): ManifestResponse {
        val query = buildMap {
            if (cursor.isNotBlank()) put("since", cursor)
            if (include.isNotEmpty()) put("include", include.joinToString(","))
        }
        val body = get("/v1/courses/${courseId.escape()}/manifest", query)
        return SyncJson.decodeFromString(ManifestResponse.serializer(), body)
    }

    /**
     * Los materiales de la cátedra (metadatos; opcionalmente el texto).
     *
     * @param courseId la cátedra.
     * @param ids si no está vacío, sólo estos materiales.
     * @param includeText si se pide el texto extraído en el mismo cuerpo.
     */
    suspend fun fetchMaterials(
        courseId: String,
        ids: List<String> = emptyList(),
        includeText: Boolean = false,
    ): MaterialsResponse {
        val query = buildMap {
            if (ids.isNotEmpty()) put("ids", ids.joinToString(","))
            put("include", if (includeText) "meta,text" else "meta")
        }
        val body = get("/v1/courses/${courseId.escape()}/materials", query)
        return SyncJson.decodeFromString(MaterialsResponse.serializer(), body)
    }

    /**
     * Sube conversaciones.
     *
     * El [idempotencyKey] viaja como cabecera `Idempotency-Key`: es el mismo
     * en cada reintento, así que un reintento tras un timeout no duplica los
     * mensajes (el servidor además deduplica por `clientMessageId`).
     *
     * @param courseId la cátedra.
     * @param entries las entradas (hilos y mensajes).
     * @param idempotencyKey la clave de idempotencia de la operación.
     */
    suspend fun pushChat(
        courseId: String,
        entries: List<ChatUploadEntry>,
        idempotencyKey: String = "",
    ): ChatPushResponse {
        val body = SyncJson.encodeToString(
            ChatPushRequest.serializer(),
            ChatPushRequest(clientId = clientId, clientTime = nowMillis(), entries = entries),
        )
        val headers = if (idempotencyKey.isBlank()) {
            emptyMap()
        } else {
            mapOf(IDEMPOTENCY_HEADER to idempotencyKey)
        }
        val response = transport.post("/v1/courses/${courseId.escape()}/chats", body, headers)
        if (!response.isSuccess) throw SyncException.from(response)
        return SyncJson.decodeFromString(ChatPushResponse.serializer(), response.body)
    }

    /**
     * Baja conversaciones.
     *
     * @param courseId la cátedra.
     * @param afterSeq el número de secuencia desde el que se quiere (0 = todo).
     * @param author de quién son las conversaciones; vacío = las que el token
     *   puede ver.
     */
    suspend fun pullChats(
        courseId: String,
        afterSeq: Long = 0L,
        author: String = "",
        limit: Int = 500,
    ): ChatsResponse {
        val query = buildMap {
            if (afterSeq > 0L) put("after", afterSeq.toString())
            if (author.isNotBlank()) put("author", author)
            put("limit", limit.toString())
            put("include", "threads,messages")
        }
        val body = get("/v1/courses/${courseId.escape()}/chats", query)
        return SyncJson.decodeFromString(ChatsResponse.serializer(), body)
    }

    /**
     * Las inscripciones de la cátedra (el padrón) o la propia.
     *
     * @param courseId la cátedra.
     * @param since el cursor opaco de la última sincronización.
     * @param mine si es `true`, pide sólo la inscripción propia (un alumno no
     *   puede leer el padrón: el servidor responde `403`).
     */
    suspend fun fetchEnrollments(
        courseId: String,
        since: String = "",
        mine: Boolean = false,
    ): EnrollmentsResponse {
        if (mine) {
            val body = get("/v1/courses/${courseId.escape()}/inscriptions/me", emptyMap())
            val own = SyncJson.decodeFromString(EnrollmentMeResponse.serializer(), body)
            return EnrollmentsResponse(enrollments = listOfNotNull(own.enrollment))
        }
        val query = buildMap {
            if (since.isNotBlank()) put("since", since)
        }
        val body = get("/v1/courses/${courseId.escape()}/inscriptions", query)
        return SyncJson.decodeFromString(EnrollmentsResponse.serializer(), body)
    }

    /** Un GET que falla rápido si el servidor no está contento. */
    private suspend fun get(path: String, query: Map<String, String>): String {
        val response = transport.get(path, query)
        if (!response.isSuccess) throw SyncException.from(response)
        return response.body
    }

    companion object {

        /** La cabecera de idempotencia de la API. */
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
    }
}

// ---------------------------------------------------------------------------
// Los tipos del cable
// ---------------------------------------------------------------------------

/**
 * Un material, como lo manda el servidor.
 *
 * Tiene más campos que el [Material] del dominio: los hashes y la versión son
 * de transporte (el dominio no los necesita) y el texto extraído viaja
 * aparte.
 */
@Serializable
data class RemoteMaterial(
    val id: String = "",
    val courseId: String = "",
    val title: String = "",
    val kind: MaterialKind = MaterialKind.DOCUMENT,
    val path: String = "",
    val sizeBytes: Long = 0L,
    val uploadedBy: String = "",
    val uploadedAt: Long = 0L,
    val tags: List<String> = emptyList(),
    val visible: Boolean = true,
    val warning: String = "",
    /** Hash del archivo original ("sha256:…"). */
    val contentHash: String = "",
    /** Hash del texto extraído. */
    val textHash: String = "",
    /** Texto extraído (sólo si se pidió `include=text`). */
    val text: String = "",
    /** Versión del material en el servidor. */
    val version: Int = 0,
) {

    /** El material del dominio. */
    fun toDomain(): Material = Material(
        id = id,
        courseId = courseId,
        title = title,
        kind = kind,
        path = path,
        sizeBytes = sizeBytes,
        uploadedBy = uploadedBy,
        uploadedAt = uploadedAt,
        tags = tags,
        visible = visible,
        extractedText = text,
        warning = warning,
    )
}

/**
 * Una inscripción, como la manda el servidor.
 *
 * Tiene más campos que la [Enrollment] del dominio: el estado, el tope de
 * visibilidad y el modo de IA son cosas del servidor.
 */
@Serializable
data class RemoteEnrollment(
    val id: String = "",
    val courseId: String = "",
    val userId: String = "",
    val displayName: String = "",
    val role: CourseRole = CourseRole.ALUMNO,
    /** "ACTIVE", "PENDING", "SUSPENDED", "LEFT". */
    val status: String = "ACTIVE",
    val joinedAt: Long = 0L,
    /** Lo que el alumno autorizó que el docente vea. */
    val visibility: ChatVisibility = ChatVisibility.FULL,
    /** El tope que puso el docente. */
    val visibilityCap: ChatVisibility = ChatVisibility.FULL,
    /** "REMOTE" o "LOCAL". */
    val aiMode: String = "",
    val declaredLocalAiAt: Long = 0L,
) {

    /** La inscripción del dominio. */
    fun toDomain(): Enrollment = Enrollment(
        id = id,
        courseId = courseId,
        userId = userId,
        displayName = displayName,
        role = role,
        joinedAt = joinedAt,
        visibility = visibility,
    )
}

/**
 * Un hilo, como lo manda el servidor.
 */
@Serializable
data class RemoteThread(
    val id: String = "",
    val courseId: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val title: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val modelLabel: String = "",
    val effectiveModel: ModelPolicy = ModelPolicy(),
    val tags: List<String> = emptyList(),
    /** Cuántos mensajes tiene el hilo. */
    val messageCount: Int = 0,
    /** "HOSTED" o "LOCAL_SHARED". */
    val origin: String = "HOSTED",
    /** Si el texto fue redactado por el servidor (visibilidad parcial). */
    val redacted: Boolean = false,
) {

    /** El hilo del dominio. */
    fun toDomain(): ChatThread = ChatThread(
        id = id,
        courseId = courseId,
        authorId = authorId,
        authorName = authorName,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        modelLabel = modelLabel,
        effectiveModel = effectiveModel,
        tags = tags,
    )
}

/**
 * Un mensaje, como lo manda el servidor.
 */
@Serializable
data class RemoteMessage(
    val id: String = "",
    val threadId: String = "",
    val role: String = "",
    val text: String = "",
    val createdAt: Long = 0L,
    val sources: List<String> = emptyList(),
    val citation: CitationStats? = null,
    val toolCalls: List<ToolCallStats> = emptyList(),
    val latencyMs: Long = 0L,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    /** Si el texto fue redactado por el servidor (visibilidad parcial). */
    val redacted: Boolean = false,
) {

    /** El mensaje del dominio. */
    fun toDomain(): ChatMessage = ChatMessage(
        id = id,
        threadId = threadId,
        role = role,
        text = text,
        createdAt = createdAt,
        sources = sources,
        citation = citation,
        toolCalls = toolCalls,
        latencyMs = latencyMs,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
    )
}

/**
 * El manifiesto de una cátedra.
 *
 * @property cursor el cursor opaco para la próxima sincronización.
 */
@Serializable
data class ManifestResponse(
    val protocol: Int = 1,
    val courseId: String = "",
    val serverTime: Long = 0L,
    val cursor: String = "",
    val hasMore: Boolean = false,
    val course: Course? = null,
    val materials: MaterialsBlock? = null,
    val roster: RosterBlock? = null,
    val corpus: CorpusInfo? = null,
)

/**
 * El bloque de materiales del manifiesto.
 */
@Serializable
data class MaterialsBlock(
    val upserted: List<RemoteMaterial> = emptyList(),
    val removed: List<String> = emptyList(),
)

/**
 * El bloque de inscripciones del manifiesto.
 */
@Serializable
data class RosterBlock(
    val upserted: List<RemoteEnrollment> = emptyList(),
    val removed: List<String> = emptyList(),
)

/**
 * La información del corpus (para descargar el bundle).
 */
@Serializable
data class CorpusInfo(
    val version: String = "",
    val materialCount: Int = 0,
    val visibleCount: Int = 0,
    val textBytes: Long = 0L,
    val bundleUrl: String = "",
    val bundleBytes: Long = 0L,
    val bundleEtag: String = "",
)

/**
 * La respuesta de `GET /materials`.
 */
@Serializable
data class MaterialsResponse(
    val cursor: String = "",
    val hasMore: Boolean = false,
    val materials: List<RemoteMaterial> = emptyList(),
    val removed: List<String> = emptyList(),
    val nextSince: String? = null,
)

/**
 * La respuesta de `GET /inscriptions`.
 */
@Serializable
data class EnrollmentsResponse(
    val cursor: String = "",
    val hasMore: Boolean = false,
    val enrollments: List<RemoteEnrollment> = emptyList(),
    val removed: List<String> = emptyList(),
)

/**
 * La respuesta de `GET /inscriptions/me`.
 */
@Serializable
data class EnrollmentMeResponse(
    val enrollment: RemoteEnrollment? = null,
    val effectiveVisibilityForTeacher: ChatVisibility? = null,
)

/**
 * La respuesta de `GET /chats`.
 */
@Serializable
data class ChatsResponse(
    val cursor: String = "",
    val hasMore: Boolean = false,
    val threads: List<RemoteThread> = emptyList(),
    val messages: List<RemoteMessage> = emptyList(),
    val removed: List<String> = emptyList(),
)

/**
 * El cuerpo del POST de chats.
 */
@Serializable
data class ChatPushRequest(
    val clientId: String = "",
    val clientTime: Long = 0L,
    val entries: List<ChatUploadEntry> = emptyList(),
)

/**
 * Una entrada del POST de chats.
 *
 * @property kind "thread" o "message".
 * @property clientMessageId la clave de deduplicación contra el servidor: la
 *   genera el cliente al crear el mensaje y no cambia nunca.
 */
@Serializable
data class ChatUploadEntry(
    val kind: String = "message",
    val clientThreadId: String = "",
    val clientMessageId: String = "",
    /** "HOSTED" (IA de la cátedra) o "LOCAL_SHARED" (IA local, subida a mano). */
    val origin: String = "HOSTED",
    val consent: ShareConsent? = null,
    val thread: RemoteThread? = null,
    val message: RemoteMessage? = null,
)

/**
 * El consentimiento explícito del alumno para compartir una conversación.
 */
@Serializable
data class ShareConsent(
    val scope: String = "",
    val grantedAt: Long = 0L,
    val version: Int = 1,
)

/**
 * La respuesta del POST de chats.
 *
 * El resultado es por entrada: lo que se aceptó, lo que ya estaba (duplicado),
 * lo que se rechazó y lo que quedó en conflicto.
 */
@Serializable
data class ChatPushResponse(
    val serverTime: Long = 0L,
    val cursor: String = "",
    val accepted: List<PushOutcome> = emptyList(),
    val duplicates: List<PushOutcome> = emptyList(),
    val rejected: List<PushRejection> = emptyList(),
    val conflicts: List<PushConflict> = emptyList(),
)

/**
 * El resultado de una entrada aceptada (o detectada como duplicada).
 */
@Serializable
data class PushOutcome(
    val kind: String = "",
    val clientThreadId: String = "",
    val clientMessageId: String = "",
    val id: String = "",
    val serverSeq: Long = 0L,
    /** "created" o "revised". */
    val status: String = "",
)

/**
 * Una entrada rechazada por el servidor: no se reintenta.
 */
@Serializable
data class PushRejection(
    val kind: String = "",
    val clientThreadId: String = "",
    val clientMessageId: String = "",
    val code: String = "",
    val message: String = "",
)

/**
 * Una entrada en conflicto: el servidor tiene otra versión.
 */
@Serializable
data class PushConflict(
    val kind: String = "",
    val clientMessageId: String = "",
    /** "stale_revision" u otros. */
    val reason: String = "",
    val serverRev: Int = 0,
    val clientRev: Int = 0,
)

/** El JSON del cable: tolera los campos que todavía no conocemos. */
private val SyncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Escapa un identificador para usarlo en una URL. */
private fun String.escape(): String = encodeURLParameter()

/** Convierte una respuesta de Ktor en una [SyncResponse]. */
private suspend fun HttpResponse.toSyncResponse(): SyncResponse = SyncResponse(
    status = status.value,
    body = bodyAsText(),
    headers = headers.entries().associate { (name, values) -> name to values.joinToString(", ") },
)
