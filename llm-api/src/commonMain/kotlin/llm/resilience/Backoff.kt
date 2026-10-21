package llm.resilience

import kotlin.random.Random

/**
 * Espera entre reintentos: backoff exponencial con **jitter medio**.
 *
 * Por qué backoff exponencial y no una espera fija: si el servidor está
 * saturado (o caído y por lo tanto autorreiniciándose), todos los alumnos que
 * fallan al mismo tiempo vuelven a golpear al mismo instante. La espera
 * creciente reparte la carga y le da al servidor tiempo de recuperarse.
 *
 * Por qué jitter: sin él, 40 alumnos que chocan contra el mismo servidor
 * caído reintentan sincronizados y lo mantienen caído ("thundering herd").
 * El jitter **medio** (la espera real está entre la mitad y el total de la
 * nominal) desincroniza a los clientes sin llegar a esperar 0 ms.
 *
 * Es **determinista e inyectable**: el [Random] se pasa por parámetro, así
 * los tests pueden fijar la semilla y obtener siempre la misma secuencia
 * (si dependiera de `Random.Default`, los tests serían flaky).
 */
class Backoff(
    /** Espera nominal del primer reintento. */
    val baseMillis: Long = DEFAULT_BASE_MILLIS,
    /** Tope máximo de la espera nominal (nunca se espera más que esto). */
    val maxMillis: Long = DEFAULT_MAX_MILLIS,
    /** Si `true` se aplica el jitter medio sobre la espera nominal. */
    val jitter: Boolean = true,
    /** Fuente de azar: inyectable para que los tests sean reproducibles. */
    val random: Random = Random.Default,
) {

    init {
        require(baseMillis > 0) { "baseMillis tiene que ser positivo (fue $baseMillis)." }
        require(maxMillis >= baseMillis) {
            "maxMillis ($maxMillis) no puede ser menor que baseMillis ($baseMillis)."
        }
    }

    /**
     * Espera a hacer **después** del intento [attempt] (1 = el primer intento).
     *
     * La nominal es `min(maxMillis, baseMillis * 2^(attempt-1))`:
     * 500 ms → 1.000 ms → 2.000 ms → 4.000 ms → 8.000 ms (tope).
     *
     * Con jitter, la espera real es `nominal/2 + random(0, nominal/2)`, o
     * sea, está entre la mitad y el total de la nominal.
     */
    fun nextDelayMillis(attempt: Int): Long {
        require(attempt >= 1) { "El intento es 1-based (fue $attempt)." }
        val nominal = nominalMillis(attempt)
        if (!jitter) return nominal
        val half = nominal / 2
        // `nextLong(0, half + 1)` es inclusivo en los dos extremos: [half, nominal].
        return half + random.nextLong(0, half + 1)
    }

    /** Espera nominal (sin jitter) del reintento [attempt]. */
    fun nominalMillis(attempt: Int): Long {
        require(attempt >= 1) { "El intento es 1-based (fue $attempt)." }
        var value = baseMillis
        // Se duplica paso a paso en vez de usar `shl` para no desbordar el Long
        // con esperas absurdas (y para poder cortar en el tope enseguida).
        repeat(attempt - 1) {
            if (value >= maxMillis) return maxMillis
            value *= 2
        }
        return minOf(value, maxMillis)
    }

    companion object {
        /** Espera base por defecto: 500 ms. */
        const val DEFAULT_BASE_MILLIS: Long = 500L

        /** Tope por defecto: 8 s. */
        const val DEFAULT_MAX_MILLIS: Long = 8_000L

        /**
         * Backoff exponencial con jitter (la configuración por defecto de la
         * app). El [random] es inyectable: con la misma semilla, la misma
         * secuencia de esperas.
         */
        fun exponential(
            baseMillis: Long = DEFAULT_BASE_MILLIS,
            maxMillis: Long = DEFAULT_MAX_MILLIS,
            jitter: Boolean = true,
            random: Random = Random.Default,
        ): Backoff = Backoff(baseMillis, maxMillis, jitter, random)
    }
}
