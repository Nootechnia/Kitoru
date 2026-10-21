package llm.resilience

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import llm.api.LlmGateway
import llm.error.LlmException
import kitoru.platform.nowMillis

/**
 * Estado de salud del motor (el servidor del modelo).
 *
 * - [UNKNOWN]: todavía no se comprobó nada.
 * - [HEALTHY]: responde bien y rápido.
 * - [DEGRADED]: responde, pero lento; o falló alguna vez y todavía no se
 *   acumula la evidencia de que está caído.
 * - [DOWN]: falló varias veces seguidas: el servidor no está sirviendo.
 */
enum class HealthState {
    /** Sin comprobar todavía. */
    UNKNOWN,

    /** Responde bien. */
    HEALTHY,

    /** Responde pero lento, o con fallos aislados. */
    DEGRADED,

    /** Caído: varios fallos seguidos. */
    DOWN,
}

/**
 * Foto del estado de salud del motor en el momento actual.
 *
 * @param status estado del motor.
 * @param lastCheckAt cuándo se hizo la última comprobación (ms desde epoch).
 * @param lastLatencyMs cuánto tardó la última comprobación.
 * @param lastError mensaje del último error (null si la última salió bien).
 * @param consecutiveFailures fallos seguidos de la última racha.
 */
data class HealthStatus(
    val status: HealthState = HealthState.UNKNOWN,
    val lastCheckAt: Long = 0L,
    val lastLatencyMs: Long = 0L,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
)

/**
 * Latido del motor: sondea el gateway (`GET /models`) periódicamente y guarda
 * el estado, la última latencia y el último error.
 *
 * Por qué existe: el alumno necesita saber si el problema es suyo o del
 * servidor ("● Servidor de la cátedra · 320 ms" contra "● Servidor caído"), y
 * el docente necesita el mismo dato agregado.
 *
 * Reglas:
 *  - El sondeo es **pasivo**: usa `listModels()`, que no consume cuota de
 *    inferencia.
 *  - Al monitor hay que pasarle el gateway **crudo**, no el decorado con
 *    reintentos: el sondeo tiene que medir la salud real, sin que los
 *    reintentos disimulen la latencia.
 *  - [check] nunca propaga un fallo: el sondeo *es* la medición del fallo.
 *
 * @param gateway el gateway a sondear.
 * @param intervalMillis cada cuánto se repite el latido.
 * @param slowThresholdMillis a partir de qué latencia el motor se considera
 *   lento (degradado).
 * @param downThreshold cuántos fallos seguidos marcan el motor como caído.
 * @param now reloj inyectable (los tests no dependen del reloj real).
 */
class HealthMonitor(
    private val gateway: LlmGateway,
    val intervalMillis: Long = 120_000,
    val slowThresholdMillis: Long = 3_000,
    val downThreshold: Int = 3,
    private val now: () -> Long = { nowMillis() },
) {

    init {
        require(intervalMillis > 0) { "intervalMillis tiene que ser positivo (fue $intervalMillis)." }
        require(slowThresholdMillis >= 0) { "slowThresholdMillis no puede ser negativo." }
        require(downThreshold >= 1) { "downThreshold tiene que ser >= 1 (fue $downThreshold)." }
    }

    private val _status = MutableStateFlow(HealthStatus())

    /** Estado actual (para que la UI lo observe sin preguntar cada vez). */
    val status: StateFlow<HealthStatus> = _status.asStateFlow()

    private var job: Job? = null

    /**
     * Arranca el latido en [scope]. Es idempotente: si ya está corriendo no
     * se lanza otro.
     */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                check()
                delay(intervalMillis)
            }
        }
    }

    /** Detiene el latido (deja el último estado congelado para mostrarlo). */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Hace una comprobación puntual y devuelve el estado resultante.
     *
     * Nunca lanza: si el gateway falla, el fallo es el dato.
     */
    suspend fun check(): HealthStatus {
        val startedAt = now()
        var failure: LlmException? = null
        try {
            gateway.listModels()
        } catch (e: CancellationException) {
            throw e // la cancelación del "Detener" no es un fallo del motor.
        } catch (e: Exception) {
            failure = LlmException.wrap(e)
        }
        val latencyMs = (now() - startedAt).coerceAtLeast(0L)
        val previous = _status.value
        val consecutive = if (failure == null) 0 else previous.consecutiveFailures + 1
        val state = when {
            failure != null && consecutive >= downThreshold -> HealthState.DOWN
            failure != null -> HealthState.DEGRADED
            latencyMs > slowThresholdMillis -> HealthState.DEGRADED
            else -> HealthState.HEALTHY
        }
        val updated = HealthStatus(
            status = state,
            lastCheckAt = now(),
            lastLatencyMs = latencyMs,
            lastError = failure?.message,
            consecutiveFailures = consecutive,
        )
        _status.value = updated
        return updated
    }
}
