package llm.error

/**
 * Errores tipados del módulo LLM: cada error sabe si es reintentable o
 * terminal, para que quien llama decida sin parsear mensajes de texto.
 */
enum class LlmErrorCode {
    /** La API alcanzó su límite de uso (429). */
    SIN_CUOTA,

    /** La clave de la API fue rechazada (401/403). */
    CLAVE_INVALIDA,

    /** Se superó el tiempo máximo de respuesta. */
    TIMEOUT,

    /** El modelo o el servidor no están configurados. */
    NO_CONFIGURADO,

    /** El modelo/petición respondió un error del servidor (5xx) o el modelo no está disponible. */
    MODELO_NO_DISPONIBLE,

    /** La ventana de contexto / KV cache se agotó (400 con indicios de tokens). */
    CONTEXTO_AGOTADO,

    /** La descarga del modelo falló (HTTP, red o integridad). */
    DESCARGA_FALLIDA,

    /** El servidor local se cayó o no responde en red. */
    SERVIDOR_CAIDO,

    /** La generación fue cancelada por el usuario. */
    CANCELADO,

    /** Error desconocido. */
    DESCONOCIDO,
}

/**
 * Excepción tipada del módulo LLM.
 *
 * @param code código identificador.
 * @param retryable `true` si un reintento puede resolverlo; los errores
 * deterministas (clave inválida, cuota, config) son `false`.
 * @param cause causa original cuando existe.
 */
class LlmException(
    val code: LlmErrorCode,
    override val message: String,
    val retryable: Boolean = false,
    override val cause: Throwable? = null,
) : Exception(message, cause) {

    override fun toString(): String = "LlmException[$code, retryable=$retryable]: $message"

    companion object {
        /**
         * Clasifica una respuesta HTTP fallida de un endpoint OpenAI-compatible
         * (local o remoto) a un [LlmException]: mira status y palabras clave
         * del cuerpo ("context", "kv cache", "tokens").
         */
        fun fromHttp(status: Int, body: String, local: Boolean): LlmException {
            val origin = if (local) "llama-server" else "la API"
            val snippet = body.take(300).ifBlank { "(sin cuerpo)" }
            return when {
                status == 401 || status == 403 ->
                    LlmException(LlmErrorCode.CLAVE_INVALIDA, "$origin rechazó la clave (HTTP $status).")
                status == 429 ->
                    LlmException(LlmErrorCode.SIN_CUOTA, "$origin alcanzó su límite de uso (HTTP 429).", retryable = true)
                status == 408 ->
                    LlmException(LlmErrorCode.TIMEOUT, "$origin tardó demasiado (HTTP 408).", retryable = true)
                status == 400 && CONTEXT_PATTERN.containsMatchIn(body) ->
                    LlmException(
                        LlmErrorCode.CONTEXTO_AGOTADO,
                        "$origin agotó el contexto: $snippet",
                        retryable = true,
                    )
                status == 404 ->
                    LlmException(
                        LlmErrorCode.MODELO_NO_DISPONIBLE,
                        "$origin no encontró el modelo/ruta (HTTP 404). Detalle: $snippet",
                        retryable = true,
                    )
                status >= 500 ->
                    LlmException(LlmErrorCode.MODELO_NO_DISPONIBLE, "$origin respondió HTTP $status: $snippet", retryable = true)
                else ->
                    LlmException(LlmErrorCode.DESCONOCIDO, "$origin respondió HTTP $status: $snippet")
            }
        }

        /** Clasifica fallos de red/transporte (conexión rechazada, reset, timeout…). */
        fun fromNetwork(t: Throwable, local: Boolean): LlmException {
            if (t is LlmException) return t
            val msg = t.message ?: t.javaClass.simpleName
            return if (local) {
                LlmException(LlmErrorCode.SERVIDOR_CAIDO, "No se pudo hablar con llama-server: $msg", retryable = true, cause = t)
            } else {
                LlmException(LlmErrorCode.MODELO_NO_DISPONIBLE, "Fallo de red con la API: $msg", retryable = true, cause = t)
            }
        }

        /**
         * Normaliza cualquier Throwable a [LlmException] preservando la
         * información. Es portable: no mira tipos de `java.io` (que no existen
         * en Android común), sino el mensaje.
         */
        fun wrap(t: Throwable): LlmException = when {
            t is LlmException -> t
            t is kotlinx.coroutines.CancellationException -> LlmException(
                LlmErrorCode.CANCELADO,
                "La generación fue cancelada.",
                cause = t,
            )
            else -> {
                val msg = t.message ?: "Error desconocido"
                when {
                    TIMEOUT_HINTS.any { msg.contains(it, ignoreCase = true) } ->
                        LlmException(LlmErrorCode.TIMEOUT, msg, retryable = true, cause = t)
                    IO_HINTS.any { msg.contains(it, ignoreCase = true) } ->
                        LlmException(LlmErrorCode.SERVIDOR_CAIDO, "Fallo de E/S: $msg", retryable = true, cause = t)
                    else -> LlmException(LlmErrorCode.DESCONOCIDO, msg, cause = t)
                }
            }
        }

        private val CONTEXT_PATTERN = Regex("context|ctx_size|kv cache|kv_cache|tokens", RegexOption.IGNORE_CASE)

        private val TIMEOUT_HINTS = listOf("timeout", "timed out", "timeoutexception")
        private val IO_HINTS = listOf("connection refused", "connection reset", "broken pipe", "unreachable", "eof")
    }
}
