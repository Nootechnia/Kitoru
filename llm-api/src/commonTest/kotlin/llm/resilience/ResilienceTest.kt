package llm.resilience

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import llm.api.LlmChunk
import llm.api.LlmGateway
import llm.api.ToolSpec
import llm.config.ChatMessage
import llm.error.LlmErrorCode
import llm.error.LlmException
import kitoru.testing.ScriptedGateway

/**
 * Tests del escalamiento de fallos: reintentos, circuit breaker y monitor de
 * salud.
 *
 * Todos son deterministas: sin red, sin reloj real (el reloj es inyectable) y
 * con dobles que cuentan las llamadas al gateway.
 */
class ResilienceTest {

    // ------------------------------------------------------------------
    // Dobles locales
    // ------------------------------------------------------------------

    /** Qué hace el gateway falso en un intento. */
    private sealed interface Guion {
        /** Responde bien con estos chunks. */
        data class Ok(val chunks: List<LlmChunk> = emptyList()) : Guion

        /** Falla antes de emitir nada. */
        data class Falla(val error: Throwable) : Guion

        /** Emite estos chunks y después falla: el caso "ya salió texto". */
        data class ParcialYfalla(val chunks: List<LlmChunk>, val error: Throwable) : Guion
    }

    /**
     * Doble local que **cuenta** las llamadas al gateway: es lo que permite
     * afirmar "no reintentó" (o "reintentó dos veces") sin depender de tiempos.
     */
    private class GatewayContador(
        private val guion: (intento: Int) -> Guion,
    ) : LlmGateway {
        /** Cuántas veces se llamó al gateway. */
        var llamadas: Int = 0
            private set

        override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> = flow {
            val intento = ++llamadas
            when (val g = guion(intento)) {
                is Guion.Ok -> g.chunks.forEach { emit(it) }
                is Guion.Falla -> throw g.error
                is Guion.ParcialYfalla -> {
                    g.chunks.forEach { emit(it) }
                    throw g.error
                }
            }
        }
    }

    /** Qué hace el gateway falso en un sondeo de salud. */
    private sealed interface GuionDeSalud {
        /** Responde bien, tardando [latencyMs]. */
        data class Ok(val latencyMs: Long, val modelos: List<String> = listOf("modelo-1")) : GuionDeSalud

        /** Falla. */
        data class Falla(val error: Throwable) : GuionDeSalud
    }

    /** Doble local para el monitor de salud: devuelve un guion por sondeo. */
    private class GatewayDeSalud(
        private val guiones: MutableList<GuionDeSalud>,
        private val reloj: Reloj,
    ) : LlmGateway {
        /** Cuántas veces se sondeó. */
        var llamadas: Int = 0
            private set

        override suspend fun listModels(): List<String> {
            llamadas++
            // Si no queda guion, responde bien: sirve para el latido continuo.
            val g = if (guiones.isEmpty()) GuionDeSalud.Ok(0) else guiones.removeAt(0)
            return when (g) {
                is GuionDeSalud.Ok -> {
                    reloj.avanzar(g.latencyMs)
                    g.modelos
                }
                is GuionDeSalud.Falla -> throw g.error
            }
        }

        // El monitor de salud sólo usa `listModels`: el chat no se usa.
        override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> = flow { }
    }

    /** Reloj de mentira: lo mueve el doble a mano. */
    private class Reloj(var actual: Long = 0L) {
        fun avanzar(ms: Long) {
            actual += ms
        }
    }

    // ------------------------------------------------------------------
    // Ayudas
    // ------------------------------------------------------------------

    private val mensajes = listOf(ChatMessage.user("hola"))

    /** Un timeout: el ejemplo canónico de error reintentable. */
    private fun timeout(): LlmException = LlmException(
        LlmErrorCode.TIMEOUT,
        "La petición superó 120000 ms.",
        retryable = true,
    )

    /** Crea el decorador con las esperas registradas en vez de dormir. */
    private fun decorador(
        gateway: LlmGateway,
        maxAttempts: Int = 3,
        breaker: CircuitBreaker? = null,
        esperas: MutableList<Long> = mutableListOf(),
        reintentos: MutableList<RetryEvent> = mutableListOf(),
    ): RetryingGateway = RetryingGateway(
        gateway = gateway,
        maxAttempts = maxAttempts,
        // Sin jitter: el test afirma sobre esperas exactas.
        backoff = Backoff.exponential(jitter = false),
        breaker = breaker,
        onRetry = { reintentos += it },
        sleeper = { esperas += it },
    )

    // ------------------------------------------------------------------
    // Reintentos
    // ------------------------------------------------------------------

    @Test
    fun `reintenta un error reintentable y termina bien`() = runTest {
        val gateway = GatewayContador { intento ->
            if (intento == 1) Guion.Falla(timeout()) else Guion.Ok(listOf(LlmChunk.Text("hola")))
        }
        val esperas = mutableListOf<Long>()
        val reintentos = mutableListOf<RetryEvent>()
        val retrying = decorador(gateway, esperas = esperas, reintentos = reintentos)

        val texto = retrying.chat(mensajes)

        assertEquals("hola", texto)
        assertEquals(2, gateway.llamadas, "Debería haber llamado al gateway dos veces (una falló).")
        assertEquals(1, reintentos.size, "Debería haber reintentado una vez.")
        assertEquals(LlmErrorCode.TIMEOUT, reintentos[0].code)
        assertEquals(1, reintentos[0].attempt)
        assertEquals(3, reintentos[0].maxAttempts)
        assertEquals(listOf(500L), esperas, "La espera del primer reintento es la nominal de 500 ms.")
    }

    @Test
    fun `no reintenta un error no reintentable`() = runTest {
        val gateway = GatewayContador {
            Guion.Falla(LlmException(LlmErrorCode.CLAVE_INVALIDA, "la API rechazó la clave (HTTP 401)."))
        }
        val reintentos = mutableListOf<RetryEvent>()
        val retrying = decorador(gateway, reintentos = reintentos)

        val error = assertFailsWith<LlmException> { retrying.chat(mensajes) }

        assertEquals(LlmErrorCode.CLAVE_INVALIDA, error.code)
        assertEquals(1, gateway.llamadas, "Una clave inválida no se reintenta: no se arregla sola.")
        assertTrue(reintentos.isEmpty(), "No debería haber ningún reintento.")
    }

    @Test
    fun `no reintenta la cuota agotada ni el 404 aunque sean reintentables`() = runTest {
        // 429: la cuota se repone por ventana (minutos u horas), no por segundo.
        val cuota = GatewayContador {
            Guion.Falla(
                LlmException(
                    LlmErrorCode.SIN_CUOTA,
                    "la API alcanzó su límite de uso (HTTP 429).",
                    retryable = true,
                ),
            )
        }
        val errorCuota = assertFailsWith<LlmException> { decorador(cuota).chat(mensajes) }
        assertEquals(LlmErrorCode.SIN_CUOTA, errorCuota.code)
        assertEquals(1, cuota.llamadas, "La cuota agotada no se reintenta en el chat.")

        // 404: el modelo no existe en el servidor; no va a aparecer solo.
        val noEncontrado = GatewayContador {
            Guion.Falla(
                LlmException(
                    LlmErrorCode.MODELO_NO_DISPONIBLE,
                    "la API no encontró el modelo/ruta (HTTP 404). Detalle: model not found",
                    retryable = true,
                ),
            )
        }
        val error404 = assertFailsWith<LlmException> { decorador(noEncontrado).chat(mensajes) }
        assertEquals(LlmErrorCode.MODELO_NO_DISPONIBLE, error404.code)
        assertEquals(1, noEncontrado.llamadas, "El 404 no se reintenta: el modelo no aparece solo.")
    }

    @Test
    fun `no reintenta si ya salio un token para no duplicar el texto`() = runTest {
        val gateway = GatewayContador {
            Guion.ParcialYfalla(listOf(LlmChunk.Text("Hola")), timeout())
        }
        val reintentos = mutableListOf<RetryEvent>()
        val retrying = decorador(gateway, reintentos = reintentos)

        val texto = mutableListOf<String>()
        assertFailsWith<LlmException> {
            retrying.chatText(mensajes).collect { texto += it }
        }

        assertEquals(listOf("Hola"), texto, "El texto no puede aparecer duplicado.")
        assertEquals(1, gateway.llamadas, "Con texto en pantalla no se reintenta.")
        assertTrue(reintentos.isEmpty(), "No debería haber ningún reintento.")
    }

    @Test
    fun `reintenta mientras no haya salido texto y para en el primer token`() = runTest {
        // El primer intento falla sin emitir nada: se reintenta. El segundo
        // emite texto y falla: ahí se corta (aunque quede texto a medias).
        val gateway = GatewayContador { intento ->
            if (intento == 1) Guion.Falla(timeout())
            else Guion.ParcialYfalla(listOf(LlmChunk.Text("parcial")), timeout())
        }
        val texto = mutableListOf<String>()

        assertFailsWith<LlmException> {
            decorador(gateway).chatText(mensajes).collect { texto += it }
        }

        assertEquals(2, gateway.llamadas, "Reintenta una vez (antes de texto) y para después.")
        assertEquals(listOf("parcial"), texto, "Sólo se ve el texto del segundo intento, sin duplicar.")
    }

    @Test
    fun `el decorador deja pasar el texto del gateway sin tocarlo`() = runTest {
        val gateway = ScriptedGateway(listOf(listOf(LlmChunk.Text("hola "), LlmChunk.Text("mundo"))))

        val texto = decorador(gateway).chat(mensajes)

        assertEquals("hola mundo", texto)
        assertEquals(1, gateway.requests.size, "El script de una ronda se consume una sola vez.")
    }

    // ------------------------------------------------------------------
    // Backoff
    // ------------------------------------------------------------------

    @Test
    fun `el backoff crece y respeta el tope`() {
        val backoff = Backoff.exponential(baseMillis = 500, maxMillis = 8_000, jitter = false)

        assertEquals(500L, backoff.nextDelayMillis(1))
        assertEquals(1_000L, backoff.nextDelayMillis(2))
        assertEquals(2_000L, backoff.nextDelayMillis(3))
        assertEquals(4_000L, backoff.nextDelayMillis(4))
        assertEquals(8_000L, backoff.nextDelayMillis(5), "8.000 ms es el tope: de ahí no se pasa.")
        assertEquals(8_000L, backoff.nextDelayMillis(9), "El tope se mantiene en los intentos siguientes.")
    }

    @Test
    fun `el jitter con semilla es reproducible`() {
        val uno = Backoff.exponential(random = Random(42))
        val otro = Backoff.exponential(random = Random(42))

        val secuenciaUno = (1..6).map { uno.nextDelayMillis(it) }
        val secuenciaOtro = (1..6).map { otro.nextDelayMillis(it) }

        assertEquals(secuenciaUno, secuenciaOtro, "Con la misma semilla, la misma secuencia.")
        val nominales = listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L)
        secuenciaUno.forEachIndexed { i, espera ->
            assertTrue(
                espera in (nominales[i] / 2)..nominales[i],
                "La espera $espera del intento ${i + 1} debería estar entre ${nominales[i] / 2} y ${nominales[i]}.",
            )
        }
    }

    @Test
    fun `el backoff determinista crece sin jitter`() {
        val backoff = Backoff.exponential(jitter = false)

        assertEquals(500L, backoff.nextDelayMillis(1))
        assertEquals(1_000L, backoff.nextDelayMillis(2))
        assertEquals(2_000L, backoff.nextDelayMillis(3))
    }

    // ------------------------------------------------------------------
    // Circuit breaker
    // ------------------------------------------------------------------

    @Test
    fun `el circuit breaker se abre tras N fallos y falla rapido despues`() = runTest {
        var ahora = 0L
        val gateway = GatewayContador {
            Guion.Falla(
                LlmException(
                    LlmErrorCode.SERVIDOR_CAIDO,
                    "No se pudo hablar con el servidor local: connection refused",
                    retryable = true,
                ),
            )
        }
        val breaker = CircuitBreaker(failureThreshold = 3, resetTimeoutMillis = 60_000) { ahora }
        // maxAttempts = 1: cada llamada es una operación, no un reintento.
        val retrying = decorador(gateway, maxAttempts = 1, breaker = breaker)

        repeat(3) {
            assertFailsWith<LlmException> { retrying.chat(mensajes) }
        }
        assertEquals(3, gateway.llamadas)
        assertEquals(BreakerState.OPEN, breaker.state)

        // El cuarto intento tiene que fallar rápido, sin tocar el gateway.
        val error = assertFailsWith<LlmException> { retrying.chat(mensajes) }

        assertEquals(LlmErrorCode.SERVIDOR_CAIDO, error.code)
        assertEquals(3, gateway.llamadas, "Con el breaker abierto no se llama al gateway.")
        assertTrue(
            error.message.contains("Esperá un momento"),
            "El mensaje debería explicar que se está esperando: \"${error.message}\".",
        )
    }

    @Test
    fun `el circuit breaker deja pasar un intento despues del tiempo de reset`() = runTest {
        var ahora = 0L
        val gateway = GatewayContador { intento ->
            if (intento <= 3) {
                Guion.Falla(
                    LlmException(LlmErrorCode.SERVIDOR_CAIDO, "no responde", retryable = true),
                )
            } else {
                Guion.Ok(listOf(LlmChunk.Text("volvió")))
            }
        }
        val breaker = CircuitBreaker(failureThreshold = 3, resetTimeoutMillis = 60_000) { ahora }
        val retrying = decorador(gateway, maxAttempts = 1, breaker = breaker)

        repeat(3) { assertFailsWith<LlmException> { retrying.chat(mensajes) } }
        assertEquals(BreakerState.OPEN, breaker.state)

        ahora += 60_001 // pasó el tiempo de reset

        assertEquals("volvió", retrying.chat(mensajes))
        assertEquals(BreakerState.CLOSED, breaker.state, "Un éxito cierra el breaker.")
        assertEquals(4, gateway.llamadas)
    }

    @Test
    fun `el circuit breaker no queda trabado si el intento de prueba falla con un error determinista`() = runTest {
        var ahora = 0L
        val gateway = GatewayContador { intento ->
            if (intento <= 3) {
                Guion.Falla(
                    LlmException(LlmErrorCode.SERVIDOR_CAIDO, "no responde", retryable = true),
                )
            } else {
                // El servidor responde, pero con un error determinista: está vivo.
                Guion.Falla(LlmException(LlmErrorCode.CLAVE_INVALIDA, "la API rechazó la clave (HTTP 401)."))
            }
        }
        val breaker = CircuitBreaker(failureThreshold = 3, resetTimeoutMillis = 60_000) { ahora }
        val retrying = decorador(gateway, maxAttempts = 1, breaker = breaker)

        repeat(3) { assertFailsWith<LlmException> { retrying.chat(mensajes) } }
        assertEquals(BreakerState.OPEN, breaker.state)

        ahora += 60_001 // pasó el tiempo de reset: toca el intento de prueba

        assertFailsWith<LlmException> { retrying.chat(mensajes) }
        assertEquals(BreakerState.CLOSED, breaker.state, "El servidor respondió: no puede quedar a medias.")

        // El siguiente pedido tiene que pasar: el breaker no quedó trabado.
        assertFailsWith<LlmException> { retrying.chat(mensajes) }
        assertEquals(5, gateway.llamadas)
    }

    @Test
    fun `un error determinista no abre el circuit breaker`() = runTest {        var ahora = 0L
        val gateway = GatewayContador {
            Guion.Falla(LlmException(LlmErrorCode.CLAVE_INVALIDA, "la API rechazó la clave (HTTP 401)."))
        }
        val breaker = CircuitBreaker(failureThreshold = 3, resetTimeoutMillis = 60_000) { ahora }
        val retrying = decorador(gateway, maxAttempts = 1, breaker = breaker)

        repeat(5) { assertFailsWith<LlmException> { retrying.chat(mensajes) } }

        assertEquals(BreakerState.CLOSED, breaker.state, "Una clave inválida no significa que el servidor esté caído.")
    }

    // ------------------------------------------------------------------
    // Monitor de salud
    // ------------------------------------------------------------------

    @Test
    fun `el monitor de salud reporta sano lento y caido`() = runTest {
        val reloj = Reloj()
        val gateway = GatewayDeSalud(
            mutableListOf(
                GuionDeSalud.Ok(latencyMs = 120),        // sano
                GuionDeSalud.Ok(latencyMs = 5_000),      // lento → degradado
                GuionDeSalud.Falla(timeout()),           // 1er fallo → degradado
                GuionDeSalud.Falla(timeout()),           // 2do fallo → degradado
                GuionDeSalud.Falla(timeout()),           // 3er fallo → caído
            ),
            reloj,
        )
        val monitor = HealthMonitor(
            gateway = gateway,
            intervalMillis = 120_000,
            slowThresholdMillis = 3_000,
            now = { reloj.actual },
        )

        assertEquals(HealthState.UNKNOWN, monitor.status.value.status, "Antes de comprobar no se sabe nada.")

        val sano = monitor.check()
        assertEquals(HealthState.HEALTHY, sano.status)
        assertEquals(120L, sano.lastLatencyMs)
        assertNull(sano.lastError)

        val lento = monitor.check()
        assertEquals(HealthState.DEGRADED, lento.status, "Un servidor lento está degradado, no sano.")

        assertEquals(HealthState.DEGRADED, monitor.check().status, "Un fallo aislado no lo deja caído.")
        assertEquals(HealthState.DEGRADED, monitor.check().status, "Dos fallos tampoco.")
        val caido = monitor.check()

        assertEquals(HealthState.DOWN, caido.status)
        assertEquals(3, caido.consecutiveFailures)
        assertNotNull(caido.lastError)
        assertEquals(caido, monitor.status.value, "El estado publicado es el mismo que devuelve check().")
    }

    @Test
    fun `el monitor de salud se recupera despues de una racha de fallos`() = runTest {
        val reloj = Reloj()
        val gateway = GatewayDeSalud(
            mutableListOf(
                GuionDeSalud.Falla(timeout()),
                GuionDeSalud.Falla(timeout()),
                GuionDeSalud.Falla(timeout()),
                GuionDeSalud.Ok(latencyMs = 50),
            ),
            reloj,
        )
        val monitor = HealthMonitor(gateway, downThreshold = 3, now = { reloj.actual })

        monitor.check()
        monitor.check()
        assertEquals(HealthState.DOWN, monitor.check().status)

        val recuperado = monitor.check()
        assertEquals(HealthState.HEALTHY, recuperado.status)
        assertEquals(0, recuperado.consecutiveFailures, "Un éxito reinicia la racha de fallos.")
        assertNull(recuperado.lastError)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `el latido del monitor se detiene con stop`() = runTest {
        val reloj = Reloj()
        val gateway = GatewayDeSalud(mutableListOf(), reloj)
        val monitor = HealthMonitor(gateway, intervalMillis = 1_000, now = { reloj.actual })

        monitor.start(backgroundScope)
        runCurrent()
        assertEquals(1, gateway.llamadas, "El latido comprueba apenas arranca.")

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, gateway.llamadas, "El latido vuelve a comprobar pasado el intervalo.")

        monitor.stop()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, gateway.llamadas, "Con stop() el latido deja de comprobar.")
    }
}
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            