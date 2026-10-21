package llm.resilience

import llm.error.LlmErrorCode
import llm.error.LlmException
import kitoru.platform.nowMillis

/**
 * Estado del circuit breaker.
 *
 * Es el-breaker-el-estado: `CLOSED` (todo normal), `OPEN` (cortado: no se
 * llama al destino) y `HALF_OPEN` (se deja pasar **un** intento para ver si
 * el destino volvió).
 */
enum class BreakerState {
    /** Normal: el tráfico pasa. */
    CLOSED,

    /** Cortado: el destino falló demasiado; no se le manda nada. */
    OPEN,

    /** Prueba: pasa un único intento para comprobar si el destino volvió. */
    HALF_OPEN,
}

/**
 * Circuit breaker: corta la cadena cuando el destino (el servidor del modelo)
 * falla repetidamente.
 *
 * Por qué existe: un servidor caído con 40 alumnos reintentando 3 veces cada
 * uno son 120 pedidos en 10 segundos. El breaker los convierte en 3 pedidos y
 * después ni uno, hasta que el servidor se recupera.
 *
 * Reglas:
 *  - [failureThreshold] fallos **consecutivos** → `OPEN` durante
 *    [resetTimeoutMillis].
 *  - En `OPEN` se **falla rápido**: no se llama al gateway y se devuelve un
 *    [LlmException] con código [LlmErrorCode.SERVIDOR_CAIDO] que explica que
 *    se está esperando a que el servidor se recupere.
 *  - Pasado el tiempo de reset el breaker pasa a `HALF_OPEN` y deja pasar
 *    **un** intento: si sale bien se cierra (contador a cero), si falla vuelve
 *    a abrirse.
 *
 * El reloj es inyectable ([now]) para poder testear el paso del tiempo sin
 * dormir de verdad.
 */
class CircuitBreaker(
    /** Cuántos fallos consecutivos abren el breaker. */
    val failureThreshold: Int = 3,
    /** Cuánto tiempo queda abierto antes de dejar pasar un intento de prueba. */
    val resetTimeoutMillis: Long = 60_000,
    /** Reloj inyectable (milisegundos desde epoch). */
    private val now: () -> Long = { nowMillis() },
) {

    init {
        require(failureThreshold >= 1) { "failureThreshold tiene que ser >= 1 (fue $failureThreshold)." }
        require(resetTimeoutMillis >= 0) { "resetTimeoutMillis no puede ser negativo." }
    }

    /** Estado actual del breaker. */
    var state: BreakerState = BreakerState.CLOSED
        private set

    /** Fallos consecutivos acumulados (se pone a cero con un éxito). */
    var consecutiveFailures: Int = 0
        private set

    /** Momento en que se abrió el breaker (0 si nunca se abrió). */
    private var openedAt: Long = 0L

    /**
     * ¿Se permite llamar al destino?
     *
     * `false` ⇒ el breaker está abierto: el llamador tiene que fallar rápido
     * (ver [openException]) sin tocar la red.
     */
    fun allow(): Boolean = when (state) {
        BreakerState.CLOSED -> true
        BreakerState.OPEN ->
            if (now() - openedAt >= resetTimeoutMillis) {
                // Pasó el tiempo de reset: se deja pasar UN intento de prueba.
                state = BreakerState.HALF_OPEN
                true
            } else {
                false
            }
        // Ya hay un intento de prueba en vuelo: no se deja pasar otro.
        BreakerState.HALF_OPEN -> false
    }

    /** El destino respondió bien: el breaker se cierra. */
    fun recordSuccess() {
        consecutiveFailures = 0
        state = BreakerState.CLOSED
        openedAt = 0L
    }

    /** El destino falló: se cuenta y se abre si se alcanzó el umbral. */
    fun recordFailure() {
        consecutiveFailures++
        // En `HALF_OPEN` el fallo del intento de prueba reabre el breaker
        // (el destino sigue muerto) y reinicia la ventana de espera.
        if (state == BreakerState.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = BreakerState.OPEN
            openedAt = now()
        }
    }

    /**
     * Error de **fallo rápido**: el breaker está abierto y no se va a llamar al
     * gateway. El mensaje está en español y explica que se está esperando a
     * que el servidor se recupere (para que el alumno no piense que su
     * conexión falló).
     */
    fun openException(): LlmException = LlmException(
        LlmErrorCode.SERVIDOR_CAIDO,
        "El servidor del modelo no está respondiendo y quedó en pausa de seguridad " +
            "por $failureThreshold fallos seguidos. No se hacen más pedidos hasta que se " +
            "recupere (en unos ${resetTimeoutMillis / 1000} segundos). " +
            "Esperá un momento y volvé a intentarlo.",
        retryable = true,
    )
}
