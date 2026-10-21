package llm.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Tipos de la API de gestión del Kitoru-Server (todo vive bajo `/v1`).
 *
 * **Por qué se lee y se escribe el JSON a mano y no con `@Serializable`.**
 * El módulo `:llm-api` no aplica el plugin del compilador de
 * `kotlinx.serialization` (sólo lo aplica `:core`), así que estas clases no
 * tienen serializer generado y `Json.decodeFromString<T>()` fallaría en
 * runtime con "Serializer for class ... is not found". Por eso el
 * (de)serializado se hace con la API de `JsonElement`, igual que ya hace
 * `OpenAiCompatibleGateway` en este mismo módulo.
 *
 * Como efecto secundario, el cliente tolera los campos que el servidor
 * agregue en el futuro sin tocar una línea (equivale a `ignoreUnknownKeys`).
 */

/** Identidad de quien usa una credencial: un alumno (`STUDENT`) o la cátedra (`COURSE`). */
data class ServerSubject(
    /** `STUDENT` o `COURSE`. */
    val type: String = "",
    /** Id de inscripción (alumno) o código de cátedra. */
    val id: String = "",
    /** Nombre para mostrar en la UI del docente. */
    val displayName: String = "",
)

/**
 * Una clave del Kitoru-Server tal como la devuelve `/v1/keys`.
 *
 * @param id `kid` público de la credencial (el `id` de `GET /v1/keys/{id}`).
 * @param token el secreto completo: **sólo viene al emitir** (`POST
 *   `/v1/keys``) y el servidor no lo vuelve a mostrar nunca.
 * @param status `ACTIVE`, `SUSPENDED`, `REVOKED` o `EXPIRED`.
 * @param expiresAt vencimiento (epoch ms); nunca puede pasar del fin de la
 *   cursada ([termEndsAt]).
 */
data class ServerKey(
    val id: String = "",
    val token: String? = null,
    val tokenLast4: String = "",
    val type: String = "",
    val courseId: String = "",
    val term: String = "",
    val subject: ServerSubject = ServerSubject(),
    val label: String = "",
    val scope: List<String> = emptyList(),
    val status: String = "",
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L,
    val termEndsAt: Long = 0L,
    val renewals: Int = 0,
    val maxUses: Int = 0,
    val usedSubjects: Int = 0,
    val lastUsedAt: Long? = null,
    val revokedAt: Long? = null,
    val models: List<String> = emptyList(),
) {
    /** `true` mientras la clave sirve para hablar con el modelo. */
    val isActive: Boolean get() = status.equals("ACTIVE", ignoreCase = true)

    /**
     * El `toString()` de un `data class` incluiría el [token] (el secreto) y
     * basta un `log` accidental para filtrarlo. Se pisa para que lo que salga
     * sea la máscara, nunca el secreto.
     */
    override fun toString(): String =
        "ServerKey(id=$id, courseId=$courseId, term=$term, status=$status, " +
            "token=${if (token == null) "null" else "«oculto»"})"
}

/** Una cátedra registrada en el servidor (`GET /v1/courses`). */
data class ServerCourse(
    /** Código de la cátedra (ej.: "IA-2026-1"). */
    val id: String = "",
    val name: String = "",
    /** Cursada a la que pertenece (ej.: "2026-2"). */
    val term: String = "",
    /** Código con el que se inscribe el alumno. */
    val joinCode: String = "",
    val analyticsEnabled: Boolean = true,
    val paused: Boolean = false,
)

/**
 * Un hilo de conversación del alumno, tal como se empuja al servidor.
 *
 * El servidor lo agrupa para que el docente vea "conversaciones" y no
 * mensajes sueltos.
 */
data class ServerChatThread(
    /** Id del hilo (el `ChatThread.id` del dominio). */
    val id: String = "",
    val courseId: String = "",
    val term: String = "",
    /** Id de inscripción del alumno. */
    val authorId: String = "",
    val authorName: String = "",
    val title: String = "",
    val createdAt: Long = 0L,
)

/**
 * Un mensaje del hilo.
 *
 * @param clientMessageId id estable del lado del cliente: es la **clave de
 *   idempotencia** del `POST /v1/chats`. Si el servidor ya lo tenía,
 *   responde 409 y el cliente lo trata como éxito (no se duplica).
 */
data class ServerChatMessage(
    val clientMessageId: String = "",
    /** `user`, `assistant`, `system`… */
    val role: String = "",
    val text: String = "",
    val createdAt: Long = 0L,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val latencyMs: Long = 0L,
    /** Nombres de los materiales que el mensaje usó como contexto. */
    val sources: List<String> = emptyList(),
)

/**
 * Un evento del outbox del servidor, tal como lo devuelve `GET /v1/chats`.
 *
 * El servidor ya aplicó la visibilidad: si el alumno no autorizó el texto,
 * el evento llega con `textAvailable = false` y sin mensajes.
 */
data class ServerChatEvent(
    /** Secuencia monotónica del outbox: es el cursor de la sincronización. */
    val seq: Long = 0L,
    val id: String = "",
    val courseId: String = "",
    val term: String = "",
    val keyId: String = "",
    val threadId: String = "",
    val createdAt: Long = 0L,
    /** `FULL`, `METRICS_ONLY` o `NONE`. */
    val visibility: String = "",
    /** `false` cuando el servidor recortó el texto por visibilidad. */
    val textAvailable: Boolean = false,
    val messages: List<ServerChatMessage> = emptyList(),
)

/**
 * Cuerpo de error único del servidor: `{"error":{"code","message"}}`.
 *
 * @param code código estable en snake_case ("rate_limited", "quota_exceeded",
 *   "key_expired"…).
 * @param message mensaje en español, listo para mostrar al usuario.
 * @param retryAfter segundos a esperar (sólo en `rate_limited`).
 * @param details datos extra del error; en el 409 de idempotencia trae
 *   `clientMessageId` con el mensaje que el servidor ya tenía.
 */
data class ServerError(
    val code: String = "",
    val message: String = "",
    /** Familia estilo OpenAI (`rate_limit_error`, `insufficient_quota`…). */
    val type: String = "",
    val param: String? = null,
    val retryAfter: Int? = null,
    val details: Map<String, String> = emptyMap(),
)

/**
 * Una página de chats con su cursor, para la sincronización incremental.
 *
 * @param chats los eventos de la página.
 * @param nextSeq el `seq` del último evento: se guarda como cursor y la
 *   próxima llamada pide `afterSeq = nextSeq`.
 * @param hasMore `true` si el servidor tiene más eventos.
 */
data class ChatPage(
    val chats: List<ServerChatEvent> = emptyList(),
    val nextSeq: Long = 0L,
    val hasMore: Boolean = false,
)

/**
 * Resultado de empujar un hilo de chat al servidor.
 *
 * @param accepted ids de mensajes que el servidor aceptó.
 * @param duplicates ids de mensajes que el servidor **ya tenía** (reintento
 *   idempotente: no son un error).
 * @param seq secuencia del outbox donde quedó registrado el hilo, si el
 *   servidor la devuelve.
 */
data class PushResult(
    val accepted: List<String> = emptyList(),
    val duplicates: List<String> = emptyList(),
    val seq: Long? = null,
)

// ───────────────────────────── lectura del JSON ─────────────────────────────

/** Toma un texto; si el campo falta o no es texto, devuelve [default]. */
internal fun JsonObject.text(name: String, default: String = ""): String =
    (this[name] as? JsonPrimitive)?.contentOrNull ?: default

/** Toma un entero largo; si el campo falta o no es número, devuelve [default]. */
internal fun JsonObject.longValue(name: String, default: Long = 0L): Long =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: default

/** Toma un entero largo opcional (`null` cuando el campo falta o es `null`). */
internal fun JsonObject.longOrNull(name: String): Long? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

/** Toma un entero; si el campo falta o no es número, devuelve [default]. */
internal fun JsonObject.intValue(name: String, default: Int = 0): Int =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: default

/** Toma un booleano; si el campo falta o no es booleano, devuelve [default]. */
internal fun JsonObject.boolValue(name: String, default: Boolean = false): Boolean =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: default

/** Toma una lista de textos; si el campo falta, devuelve una lista vacía. */
internal fun JsonObject.textList(name: String): List<String> =
    (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

/** Toma un objeto anidado (`null` si el campo falta o no es un objeto). */
internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

/**
 * Lee una lista de objetos de una envoltura tolerante.
 *
 * El servidor puede envolver la lista en `{"keys":[…]}`, `{"data":[…]}`
 * (formato OpenAI) o devolver el array suelto; se aceptan las tres formas
 * para no romper cuando el contrato evolucione.
 */
internal fun objectsIn(root: JsonElement?, vararg keys: String): List<JsonObject> = when (root) {
    is JsonArray -> root.filterIsInstance<JsonObject>()
    is JsonObject -> {
        val array = keys.firstNotNullOfOrNull { key -> root[key] as? JsonArray }
        array?.filterIsInstance<JsonObject>() ?: emptyList()
    }
    else -> emptyList()
}

// ───────────────────────────── modelos desde el JSON ─────────────────────────

internal fun JsonObject.toServerSubject(): ServerSubject = ServerSubject(
    type = text("type"),
    id = text("id"),
    displayName = text("displayName"),
)

internal fun JsonObject.toServerKey(): ServerKey = ServerKey(
    id = text("id"),
    token = (this["token"] as? JsonPrimitive)?.contentOrNull,
    tokenLast4 = text("tokenLast4"),
    type = text("type"),
    courseId = text("courseId"),
    term = text("term"),
    subject = obj("subject")?.toServerSubject() ?: ServerSubject(),
    label = text("label"),
    scope = textList("scope"),
    status = text("status"),
    createdAt = longValue("createdAt"),
    expiresAt = longValue("expiresAt"),
    termEndsAt = longValue("termEndsAt"),
    renewals = intValue("renewals"),
    maxUses = intValue("maxUses"),
    usedSubjects = intValue("usedSubjects"),
    lastUsedAt = longOrNull("lastUsedAt"),
    revokedAt = longOrNull("revokedAt"),
    models = textList("models"),
)

internal fun JsonObject.toServerCourse(): ServerCourse = ServerCourse(
    id = text("id").ifBlank { text("courseId") },
    name = text("name"),
    term = text("term"),
    joinCode = text("joinCode"),
    analyticsEnabled = boolValue("analyticsEnabled", default = true),
    paused = boolValue("paused", default = false),
)

internal fun JsonObject.toServerChatMessage(): ServerChatMessage = ServerChatMessage(
    clientMessageId = text("clientMessageId").ifBlank { text("id") },
    role = text("role"),
    text = text("text"),
    createdAt = longValue("createdAt"),
    tokensIn = intValue("tokensIn"),
    tokensOut = intValue("tokensOut"),
    latencyMs = longValue("latencyMs"),
    sources = textList("sources"),
)

internal fun JsonObject.toServerChatEvent(): ServerChatEvent = ServerChatEvent(
    seq = longValue("seq"),
    id = text("id"),
    courseId = text("courseId"),
    term = text("term"),
    keyId = text("keyId"),
    threadId = text("threadId"),
    createdAt = longValue("createdAt"),
    visibility = text("visibility"),
    textAvailable = boolValue("textAvailable", default = false),
    messages = (this["messages"] as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.map { it.toServerChatMessage() }
        ?: emptyList(),
)

internal fun JsonObject.toServerError(): ServerError = ServerError(
    code = text("code"),
    message = text("message"),
    type = text("type"),
    param = (this["param"] as? JsonPrimitive)?.contentOrNull,
    retryAfter = intValue("retryAfter").takeIf { it > 0 },
    details = obj("details")
        ?.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull ?: "" }
        ?: emptyMap(),
)

internal fun JsonElement.toChatPage(): ChatPage {
    val root = this as? JsonObject ?: return ChatPage()
    return ChatPage(
        chats = objectsIn(root, "events", "chats", "data").map { it.toServerChatEvent() },
        nextSeq = root.longValue("nextSeq"),
        hasMore = root.boolValue("hasMore", default = false),
    )
}

/**
 * Lee el resultado del push.
 *
 * Si el servidor no dice qué aceptó, se asume que aceptó todo lo que se
 * mandó: es el resultado que el cliente ya conoce.
 */
internal fun JsonElement.toPushResult(sentIds: List<String>): PushResult {
    val root = this as? JsonObject ?: return PushResult(accepted = sentIds)
    val accepted = root.textList("accepted")
    val duplicates = root.textList("duplicates")
    return PushResult(
        accepted = if (root.obj("accepted") != null) accepted else sentIds,
        duplicates = duplicates,
        seq = root.longOrNull("seq"),
    )
}

// ───────────────────────────── modelos hacia el JSON ────────────────────────

/** Serializa el hilo para el `POST /v1/chats`. */
internal fun ServerChatThread.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("courseId", courseId)
    put("term", term)
    put("authorId", authorId)
    put("authorName", authorName)
    put("title", title)
    put("createdAt", createdAt)
}

/**
 * Serializa el mensaje para el `POST /v1/chats`.
 *
 * El [clientMessageId] viaja siempre: es lo que permite que un reintento no
 * duplique el mensaje en el outbox del servidor.
 */
internal fun ServerChatMessage.toJson(): JsonObject = buildJsonObject {
    put("clientMessageId", clientMessageId)
    put("role", role)
    put("text", text)
    put("createdAt", createdAt)
    put("tokensIn", tokensIn)
    put("tokensOut", tokensOut)
    put("latencyMs", latencyMs)
    putJsonArray("sources") { sources.forEach { add(JsonPrimitive(it)) } }
}
