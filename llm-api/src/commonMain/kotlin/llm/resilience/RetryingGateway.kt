package llm.resilience

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import llm.api.LlmChunk
import llm.api.LlmGateway
import llm.api.ToolSpec
import llm.config.ChatMessage
import llm.error.LlmErrorCode
import llm.error.LlmException

/**
 * Qué pasó en un reintento: lo que la UI necesita para avisarle al alumno
 * ("Reintentando (2/3)…") sin tener que interceptar los errores.
 *
 * @param attempt número del intento que falló (1 = el primero).
 * @param maxAttempts tope de intentos de esta llamada.
 * @param code código del error que provocó el reintento.
 * @param delayMs cuánto se esperó antes del siguiente intento.
 */
data class RetryEvent(
    val attempt: Int,
    val maxAttempts: Int,
    val code: LlmErrorCode,
    val delayMs: Long,
)

/**
 * Decorador de [LlmGateway] que reintenta los fallos transitorios.
 *
 * Es la **única** autoridad de reintento del sistema: ni la UI ni el agente
 * reintentan por su cuenta. Si reintentara cada uno, un timeout se convertiría
 * en 3 × 3 = 9 pedidos por mensaje.
 *
 * Reglas duras:
 *  - **Nunca reintenta si ya salió texto.** Si el modelo ya dijo algo, el
 *    parcial está en pantalla y reintentar lo duplicaría. El error sube y la
 *    UI ofrece "Continuar". Se implementa con el flag [textEmitted] de la
 *    recolección del flow (§4.7 del diseño de escalamiento).
 *  - **Nunca reintenta un error determinista**: clave inválida, cuota agotada
 *    y 404 (el modelo no existe) no se arreglan reintentando.
 *  - **Nunca reintenta una cancelación**: el "Detener" del alumno corta
 *    incluso la espera del backoff.
 *  - **Nunca reintenta si el circuit breaker está abierto**: en ese caso se
 *    falla rápido sin tocar la red.
 *
 * @param gateway el gateway real (el que sabe hablar con el servidor).
 * @param maxAttempts intentos totales por llamada (1 = sin reintentos).
 * @param backoff la espera entre intentos.
 * @param breaker circuit breaker opcional: si está, corta la cadena cuando el
 *   destino falla repetidamente.
 * @param retryQuota si `true` reintenta también la cuota agotada (tareas de
 *   fondo; en el chat interactivo no: la cuota se repone por ventana, no por
 *   segundo).
 * @param retryContextExhausted si `true` reintenta el contexto agotado (lo
 *   normal es que lo resuelva el compactador de contexto, no un reintento
 *   idéntico).
 * @param onRetry se llama antes de cada espera: la UI lo usa para mostrar
 *   "Reintentando (2/3)…".
 * @param sleeper la espera: inyectable para que los tests no dependan del
 *   reloj real.
 */
class RetryingGateway(
    private val gateway: LlmGateway,
    val maxAttempts: Int = 3,
    val backoff: Backoff = Backoff.exponential(),
    private val breaker: CircuitBreaker? = null,
    private val retryQuota: Boolean = false,
    private val retryContextExhausted: Boolean = false,
    private val onRetry: (RetryEvent) -> Unit = {},
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) : LlmGateway {

    init {
        require(maxAttempts >= 1) { "maxAttempts tiene que ser >= 1 (fue $maxAttempts)." }
    }

    /**
     * Streaming con reintentos.
     *
     * El flag [textEmitted] se pone en `true` con el **primer** chunk de texto
     * no vacío. Desde ese momento, cualquier fallo sube sin reintentar: el
     * texto que el alumno ya vio no se puede repetir.
     */
    override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> = flow {
        var attempt = 1
        var textEmitted = false
        while (true) {
            // El breaker puede cortar el pedido antes de tocar la red.
            breaker?.let { if (!it.allow()) throw it.openException() }

            var gatewayFailure: Throwable? = null
            var downstreamFailure = false
            try {
                gateway.chatStream(messages, tools).collect { chunk ->
                    // Un `ToolCall` NO bloquea el reintento: la herramienta todavía
                    // no se ejecutó (la ejecuta el agente cuando cierra el
                    // stream), así que repetir es seguro.
                    if (chunk is LlmChunk.Text && chunk.delta.isNotEmpty()) textEmitted = true
                    try {
                        emit(chunk)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // El error vino de quien consume el flow, no del gateway:
                        // reintentar no corresponde (y podría duplicar el texto).
                        downstreamFailure = true
                        throw e
                    }
                }
                breaker?.recordSuccess()
                return@flow
            } catch (e: CancellationException) {
                throw e // "Detener" del alumno: nunca se reintenta.
            } catch (e: Exception) {
                if (downstreamFailure) throw e
                gatewayFailure = e
            }

            val error = LlmException.wrap(
                requireNotNull(gatewayFailure) { "El gateway falló sin excepción (intento $attempt)." },
            )
            if (!shouldRetry(error, attempt, textEmitted)) {
                recordForBreaker(error)
                throw error
            }
            val delayMs = backoff.nextDelayMillis(attempt)
            onRetry(RetryEvent(attempt, maxAttempts, error.code, delayMs))
            sleeper(delayMs)
            attempt++
        }
    }

    /** Sólo los fragmentos de texto, con los reintentos del decorador. */
    override fun chatText(messages: List<ChatMessage>): Flow<String> =
        chatStream(messages).map { chunk -> (chunk as? LlmChunk.Text)?.delta.orEmpty() }

    /** Respuesta completa, con los reintentos del decorador. */
    override suspend fun chat(messages: List<ChatMessage>): String =
        chatText(messages).toList().joinToString("")

    /**
     * Lista los modelos (`GET /models`). También reintenta: la usan "Probar
     * conexión" y el monitor de salud, y un fallo de red ahí es tan
     * transitorio como un timeout de chat.
     */
    override suspend fun listModels(): List<String> {
        var attempt = 1
        while (true) {
            breaker?.let { if (!it.allow()) throw it.openException() }
            try {
                val models = gateway.listModels()
                breaker?.recordSuccess()
                return models
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = LlmException.wrap(e)
                if (!shouldRetry(error, attempt, textEmitted = false)) {
                    recordForBreaker(error)
                    throw error
                }
                val delayMs = backoff.nextDelayMillis(attempt)
                onRetry(RetryEvent(attempt, maxAttempts, error.code, delayMs))
                sleeper(delayMs)
                attempt++
            }
        }
    }

    override fun close() = gateway.close()

    /**
     * ¿Se reintenta este error?
     *
     * @param error el error que falló.
     * @param attempt el intento que acaba de fallar (1 = el primero).
     * @param textEmitted si ya salió un token: **no** se reintenta nunca.
     */
    private fun shouldRetry(error: LlmException, attempt: Int, textEmitted: Boolean): Boolean {
        // La regla dura: si el alumno ya leyó un fragmento, reintentar le
        // duplicaría el texto en pantalla.
        if (textEmitted) return false
        // Cancelación del usuario.
        if (error.code == LlmErrorCode.CANCELADO) return false
        // Error determinista: la clave de la cátedra, la configuración, etc.
        if (!error.retryable) return false
        // 404: el modelo no existe en el servidor; no va a aparecer solo.
        if (isNotFound(error)) return false
        // 429: la cuota se repone por ventana (minutos u horas), no por segundo.
        if (error.code == LlmErrorCode.SIN_CUOTA && !retryQuota) return false
        // El contexto agotado no se arregla repitiendo el mismo pedido: lo
        // resuelve el compactador (menos historial, menos fragmentos).
        if (error.code == LlmErrorCode.CONTEXTO_AGOTADO && !retryContextExhausted) return false
        // ¿Quedan intentos?
        return attempt < maxAttempts
    }

    /**
     * ¿El error es "del servidor" (transitorio)? Sólo estos cuentan para el
     * circuit breaker: un 404 o una clave inválida no significan que el
     * servidor esté caído.
     */
    private fun isTransient(error: LlmException): Boolean = when (error.code) {
        LlmErrorCode.CLAVE_INVALIDA, LlmErrorCode.NO_CONFIGURADO, LlmErrorCode.CANCELADO -> false
        LlmErrorCode.SIN_CUOTA -> retryQuota
        LlmErrorCode.CONTEXTO_AGOTADO -> retryContextExhausted
        else -> error.retryable && !isNotFound(error)
    }

    /**
     * Traduce el resultado de una operación para el circuit breaker.
     *
     * El breaker mide la **salud del destino**, no el éxito de la petición:
     * un 401 o un 404 prueban que el servidor está vivo, así que lo cierran;
     * un timeout o un 5xx lo opened.
     */
    private fun recordForBreaker(error: LlmException) {
        val breaker = breaker ?: return
        when {
            // El usuario canceló: el destino no dijo nada, no se toca el breaker.
            error.code == LlmErrorCode.CANCELADO -> Unit
            // Fallo transitorio: el destino no está sirviendo.
            isTransient(error) -> breaker.recordFailure()
            // El servidor respondió (aunque con un error determinista): está vivo.
            else -> breaker.recordSuccess()
        }
    }

    /**
     * Detecta el 404 del modelo.
     *
     * `LlmException.fromHttp` clasifica el 404 como `MODELO_NO_DISPONIBLE`
     * reintentable (el modelo puede no estar todavía), pero en la práctica no
     * lo es: el modelo no va a aparecer solo en el servidor. Como la excepción
     * todavía no expone el status HTTP, se reconoce por el mensaje que arma
     * `fromHttp`.
     */
    private fun isNotFound(error: LlmException): Boolean =
        error.code == LlmErrorCode.MODELO_NO_DISPONIBLE && NOT_FOUND_PATTERN.containsMatchIn(error.message)

    private companion object {
        /** El mensaje de `fromHttp` para el 404 es "... (HTTP 404) ...". */
        val NOT_FOUND_PATTERN = Regex("HTTP\\s*404")
    }
}
                                                                                                                                                                                                                                                                                                                                      