package llm.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import llm.error.LlmErrorCode
import llm.error.LlmException

/**
 * Traduce el cuerpo de error del Kitoru-Server (`{"error":{"code","message"}}`)
 * a un [LlmException] con el [LlmErrorCode] correcto.
 *
 * **Por qué el `code` del servidor decide y no el status HTTP.** El status
 * no alcanza: el servidor usa 429 para dos cosas opuestas. `rate_limited`
 * significa "vas muy rápido, esperá y reintentá" (ventana de peticiones por
 * minuto) y `quota_exceeded` significa "se acabó la cuota del día, reintentar
 * no sirve hasta el reinicio". `LlmException.fromHttp` clasifica **todo** 429
 * como `SIN_CUOTA` **reintentable**, así que una app que confíe en él
 * insiste contra el servidor hasta agotar la cuota de reintentos y le
 * muestra al alumno un error que no va a pasar nunca. Por eso el código del
 * cuerpo es el que decide la reintentabilidad: `rate_limited` es
 * reintentable y `quota_exceeded` no lo es.
 */
object ServerErrorMapper {

    /**
     * Traduce una respuesta HTTP fallida del Kitoru-Server.
     *
     * @param status status HTTP de la respuesta.
     * @param body cuerpo de la respuesta (se espera `{"error":{…}}`; si tiene
     *   otra forma, el error queda en [LlmErrorCode.DESCONOCIDO]).
     * @return la excepción tipada, lista para mostrar o loguear.
     */
    fun fromHttp(status: Int, body: String): LlmException {
        val error = parseError(body)
        return fromError(status, error, body)
    }

    /**
     * Traduce un error ya parseado.
     *
     * @param status status HTTP (sólo se usa para el mensaje del error
     *   desconocido y para decidir si un 5xx vale la pena reintentar).
     * @param error el error del servidor.
     * @param rawBody cuerpo original: se usa como detalle cuando el servidor
     *   no mandó un mensaje legible.
     */
    fun fromError(status: Int, error: ServerError, rawBody: String = ""): LlmException {
        val serverMessage = error.message.trim()
        return when (family(error.code)) {
            "rate_limited" -> LlmException(
                LlmErrorCode.SIN_CUOTA,
                rateLimitedText(serverMessage, error.retryAfter),
                // Reintentable: el servidor dice que en un rato vuelve a haber
                // lugar. El cliente puede esperar y reintentar solo.
                retryable = true,
            )

            "quota_exceeded" -> LlmException(
                LlmErrorCode.SIN_CUOTA,
                quotaText(serverMessage),
                // NO reintentable: la cuota se agotó. Reintentar sólo gastaría
                // requests para volver a fallar hasta el reinicio del día.
                retryable = false,
            )

            "key_expired" -> LlmException(
                LlmErrorCode.CLAVE_INVALIDA,
                expiredText(serverMessage),
                // La acción es renovar la clave, no reintentar el chat.
                retryable = false,
            )

            "key_invalid" -> LlmException(
                LlmErrorCode.CLAVE_INVALIDA,
                serverMessage.ifBlank { "La clave no es válida." },
                retryable = false,
            )

            "not_found" -> LlmException(
                LlmErrorCode.MODELO_NO_DISPONIBLE,
                serverMessage.ifBlank { "El servidor no encontró lo que se pidió." },
                // Reintentar no crea lo que falta: la entidad no existe.
                retryable = false,
            )

            else -> LlmException(
                LlmErrorCode.DESCONOCIDO,
                unknownText(status, serverMessage, rawBody),
                // Un 5xx es una falla del servidor: reintentar tiene sentido.
                // Un 4xx desconocido, no: hay que cambiar algo para que pase.
                retryable = status >= 500,
            )
        }
    }

    /**
     * Extrae el `{"error":{…}}` del cuerpo.
     *
     * Si el cuerpo no tiene esa forma (el servidor puede devolver HTML o un
     * texto plano), se devuelve un error vacío: el mensaje final se arma con
     * el status y el código queda en [LlmErrorCode.DESCONOCIDO].
     */
    fun parseError(body: String): ServerError {
        val root = try {
            Json.parseToJsonElement(body)
        } catch (_: Exception) {
            return ServerError()
        }
        val error = (root as? JsonObject)?.obj("error") ?: return ServerError()
        return error.toServerError()
    }

    /**
     * Extrae de un 409 el `clientMessageId` que el servidor **ya tenía**.
     *
     * Es lo que hace que un reintento del `POST /v1/chats` no duplique
     * mensajes: el servidor responde 409 con el id del mensaje repetido y el
     * cliente lo toma como éxito.
     *
     * @param body cuerpo de la respuesta 409.
     * @param knownIds los ids que el cliente mandó en este push.
     * @return el id duplicado, o `null` si el 409 no habla de un mensaje
     *   nuestro (entonces sí es un error de verdad).
     */
    fun duplicateMessageId(body: String, knownIds: List<String>): String? {
        if (knownIds.isEmpty()) return null
        val details = parseError(body).details
        val candidate = details["clientMessageId"]
            ?: details["client_message_id"]
            ?: details["clientMessageIds"]
                ?.split(",")
                ?.firstOrNull()
                ?.trim()
        if (candidate != null && knownIds.contains(candidate)) return candidate
        // Respaldo: algunos servidores nombran el id en otro campo del error.
        return knownIds.firstOrNull { body.contains(it) }
    }

    /**
     * Agrupa los códigos que son hermanos de los de la tabla principal.
     *
     * El contrato del servidor define `model_not_found`, `key_not_found`,
     * `course_not_found`… (todos `*_not_found`, el mismo caso que
     * `not_found`) y `key_revoked`, `key_suspended`, `invalid_token` y
     * `missing_credentials` (el mismo caso que `key_invalid`: el problema es
     * la credencial, no el servicio). No hace falta inventar una categoría
     * nueva para ellos.
     */
    private fun family(code: String): String {
        val normalized = code.trim().lowercase()
        return when {
            normalized.endsWith("_not_found") -> "not_found"
            normalized in CREDENTIAL_CODES -> "key_invalid"
            else -> normalized
        }
    }

    /** Códigos que son un problema de credencial (idem a `key_invalid`). */
    private val CREDENTIAL_CODES = setOf(
        "key_revoked",
        "key_suspended",
        "invalid_token",
        "missing_credentials",
    )

    /** "Vas muy rápido": el mensaje del servidor más el tiempo de espera. */
    private fun rateLimitedText(message: String, retryAfter: Int?): String {
        val base = message.ifBlank { "Vas muy rápido." }
        return if (retryAfter != null && retryAfter > 0 && !base.contains(retryAfter.toString())) {
            "$base Probá en $retryAfter s."
        } else {
            base
        }
    }

    /** "Se acabó la cuota": el mensaje del servidor dice cuándo se renueva. */
    private fun quotaText(message: String): String =
        message.ifBlank { "Alcanzaste la cuota de uso." }

    /**
     * "Tu clave venció": el mensaje tiene que decirlo, porque la acción que
     * corresponde es renovar la clave, no reintentar.
     */
    private fun expiredText(message: String): String {
        val base = message.trim()
        if (base.isBlank()) return "La clave venció. Pedí una renovación a tu docente."
        // Si el servidor ya lo dice con esa palabra, se respeta su texto.
        return if (base.contains("venció", ignoreCase = true)) base else "La clave venció: $base"
    }

    /** Error sin código conocido: se muestra el detalle del servidor. */
    private fun unknownText(status: Int, message: String, rawBody: String): String {
        if (message.isNotBlank()) return message
        val detail = rawBody.trim().take(200).ifBlank { "sin detalle" }
        return "El Kitoru-Server respondió con un error (HTTP $status): $detail"
    }
}
                                                                                                                                                  