package llm.api

/**
 * Totales de uso de tokens.
 *
 * Es el número que se muestra y el que se guarda: nunca un texto, sólo
 * conteos. El campo [estimated] viaja con el total para que la UI pueda
 * decir "estimado" sin tener que adivinar de dónde salió el número.
 */
data class UsageTotals(
    /** Tokens de entrada (prompt) de todas las rondas. */
    val promptTokens: Int = 0,
    /** Tokens de salida (completion) de todas las rondas. */
    val completionTokens: Int = 0,
    /** Tokens totales (entrada + salida). */
    val totalTokens: Int = 0,
    /** Tokens de entrada servidos desde caché. */
    val cachedTokens: Int = 0,
    /**
     * `true` si **alguna** parte del total fue estimada.
     *
     * Con varias rondas del agente puede pasar que una venga real y otra
     * estimada: el total entero queda marcado como estimado, porque es la
     * parte dudosa la que manda.
     */
    val estimated: Boolean = false,
) {
    /**
     * Tokens que consumen presupuesto: lo cacheado ya se pagó, así que no
     * cuenta. Es el número contra el que se mide el tope por alumno.
     */
    val billableTokens: Int
        get() = (promptTokens - cachedTokens).coerceAtLeast(0) + completionTokens

    /**
     * Suma de dos totales.
     *
     * La marca de estimación se conserva con un OR lógico: si cualquiera de
     * los dos fue estimado, la suma no puede presentarse como real.
     */
    operator fun plus(other: UsageTotals): UsageTotals = UsageTotals(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        estimated = estimated || other.estimated,
    )

    /** El total como chunk de uso (para quien consume el stream). */
    fun toChunk(): LlmChunk.Usage =
        LlmChunk.Usage(promptTokens, completionTokens, totalTokens, cachedTokens)
}

/**
 * Acumulador del uso de tokens de una respuesta.
 *
 * Hay dos alcances y el mismo tipo sirve para los dos:
 *  - **una respuesta**: una sola ronda del gateway;
 *  - **un hilo del agente**: varias rondas (el modelo pidió una herramienta,
 *    el loop la ejecutó y volvió a preguntar). El consumo real es la **suma**
 *    de las rondas, porque cada llamada al modelo consume lo suyo.
 *
 * Es un objeto con estado y no una función pura a propósito: el loop la va
 * llenando mientras corre el stream, sin copiar la lista de chunks.
 */
class UsageAccumulator {

    private var acc = UsageTotals()

    /** Cuántas rondas (llamadas al modelo) se acumularon. */
    var rounds: Int = 0
        private set

    /** El total acumulado hasta ahora. */
    val totals: UsageTotals get() = acc

    /**
     * Agrega el `usage` que informó el servidor (dato real).
     *
     * Si el servidor no mandó `total_tokens`, se calcula como
     * `prompt + completion`.
     */
    fun add(usage: LlmChunk.Usage): UsageAccumulator {
        val total = if (usage.totalTokens > 0) {
            usage.totalTokens
        } else {
            usage.promptTokens + usage.completionTokens
        }
        acc += UsageTotals(
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            totalTokens = total,
            cachedTokens = usage.cachedTokens.coerceAtLeast(0),
            estimated = false,
        )
        rounds++
        return this
    }

    /**
     * Agrega tokens de una ronda usando conteos con su procedencia.
     *
     * Sirve para el camino estimado: [TokenCount.approx] deja la marca y el
     * total acumulado queda como aproximado.
     */
    fun add(prompt: TokenCount, completion: TokenCount, cachedTokens: Int = 0): UsageAccumulator {
        acc += UsageTotals(
            promptTokens = prompt.value.coerceAtLeast(0),
            completionTokens = completion.value.coerceAtLeast(0),
            totalTokens = prompt.value.coerceAtLeast(0) + completion.value.coerceAtLeast(0),
            cachedTokens = cachedTokens.coerceAtLeast(0),
            estimated = prompt.estimated || completion.estimated,
        )
        rounds++
        return this
    }

    /**
     * Agrega una ronda **estimada** (el servidor no mandó `usage`).
     */
    fun addEstimated(promptTokens: Int, completionTokens: Int): UsageAccumulator =
        add(TokenCount.approx(promptTokens), TokenCount.approx(completionTokens))

    /**
     * Agrega el uso de un chunk del stream.
     *
     * Los chunks que no son de uso (texto, tool call, fin) se ignoran: el
     * acumulador sólo mira el `usage`.
     */
    fun addChunk(chunk: LlmChunk): UsageAccumulator {
        if (chunk is LlmChunk.Usage) add(chunk)
        return this
    }

    /** Agrega el uso de todos los chunks de un stream. */
    fun addAll(chunks: Iterable<LlmChunk>): UsageAccumulator {
        chunks.forEach { addChunk(it) }
        return this
    }

    /** Empieza de cero (para reusar el acumulador en otra respuesta). */
    fun reset(): UsageAccumulator {
        acc = UsageTotals()
        rounds = 0
        return this
    }

    companion object {
        /**
         * Total de uso de un stream completo.
         *
         * Atajo para el caso "un solo chat sin herramientas": se collectea el
         * flujo y se devuelve el total.
         */
        fun of(chunks: Iterable<LlmChunk>): UsageTotals =
            UsageAccumulator().addAll(chunks).totals
    }
}
