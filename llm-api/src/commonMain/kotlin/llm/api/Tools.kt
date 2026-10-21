package llm.api

import kotlinx.serialization.Serializable

/**
 * Definición de una herramienta para el modelo (formato OpenAI):
 * nombre, descripción y JSON Schema de los argumentos.
 *
 * Vive en `llm-api` (no en backend) porque el gateway la serializa en el
 * request `tools[]`; backend depende de llm-api, al revés no.
 *
 * [parametersSchema] es el JSON Schema como texto (ej. `{"type":"object","properties":{}}`);
 * se guarda como String para no acoplar allá a JsonElement en todos los módulos.
 */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: String = "{\"type\":\"object\",\"properties\":{}}",
)

/**
 * Motivos de fin de stream (`finish_reason` del protocolo OpenAI).
 *
 * Son las cadenas que manda el servidor; se mantienen como `String` (y no como
 * enum) porque hay servidores que agregan valores propios y un `enum` obliga
 * a castear: lo que no se conoce tiene que poder viajar igual.
 */
object FinishReason {
    /** El modelo terminó la respuesta por su cuenta. */
    const val STOP = "stop"

    /** Se agotó `max_tokens`: la respuesta quedó cortada (caso "length"). */
    const val LENGTH = "length"

    /** El modelo pidió una herramienta: el loop tiene que ejecutarla. */
    const val TOOL_CALLS = "tool_calls"

    /** Un filtro de contenido frenó la generación. */
    const val CONTENT_FILTER = "content_filter"

    /**
     * Texto en español para la UI (o para el log). Lo desconocido se muestra
     * como "terminó de otra forma" en vez de inventar un motivo.
     */
    fun label(reason: String): String = when (reason) {
        STOP -> "terminó"
        LENGTH -> "se alcanzó el límite de tokens"
        TOOL_CALLS -> "pidió una herramienta"
        CONTENT_FILTER -> "lo frenó el filtro de contenido"
        else -> "terminó de otra forma"
    }
}

/** Chunk tipado del streaming del modelo: texto, herramienta, uso o cierre. */
sealed class LlmChunk {
    /** Fragmento de texto normal (lo que se muestra en el chat). */
    data class Text(val delta: String) : LlmChunk()

    /**
     * Llamada a herramienta completa (acumulada: llega emisión única por call,
     * no fragmentada), lista para ejecutar.
     */
    data class ToolCall(
        val id: String,
        val name: String,
        /** Argumentos como JSON crudo (el esquema valida cada Tool). */
        val argsJson: String,
    ) : LlmChunk()

    /**
     * Uso de tokens de la llamada: el bloque `usage` que manda el servidor.
     *
     * Llega **al final** del stream (después del último texto) y sólo existe
     * si el servidor lo informó: para eso el gateway pide
     * `stream_options: {"include_usage": true}`. Cuando no llega, quien llama
     * lo estima con [TokenEstimator] y el número queda **marcado como
     * estimado** (nunca se disfraza de real).
     *
     * Ojo: el chunk de `usage` viene con `choices: []` y el bloque en la raíz
     * del JSON; si el parser mira las choices primero, lo pierde (era el bug
     * que hacía que hoy no se supiera cuánto costaba cada respuesta).
     */
    data class Usage(
        /** Tokens del prompt (entrada). */
        val promptTokens: Int,
        /** Tokens generados (salida). */
        val completionTokens: Int,
        /** `prompt + completion` (lo que informa el servidor). */
        val totalTokens: Int,
        /** Tokens del prompt servidos desde caché (`prompt_tokens_details.cached_tokens`). */
        val cachedTokens: Int = 0,
    ) : LlmChunk() {
        /**
         * Tokens que "consumen presupuesto": lo cacheado ya se pagó, así que
         * no cuenta. Es el número que usa el tope por alumno.
         */
        val billableTokens: Int
            get() = (promptTokens - cachedTokens).coerceAtLeast(0) + completionTokens
    }

    /**
     * Cierre del stream: por qué terminó la generación.
     *
     * Es lo que permite distinguir "el modelo terminó" de "lo cortamos": con
     * `reason == "length"` (ver [FinishReason.LENGTH]) la respuesta se cortó
     * por `max_tokens` y la UI tiene que avisar y ofrecer "Continuar".
     *
     * Sólo se emite si el servidor mandó `finish_reason`: si no lo mandó, no
     * se inventa un motivo (el fin del flujo ya dice que terminó).
     */
    data class Finish(val reason: String) : LlmChunk() {
        /** ¿La respuesta quedó cortada por `max_tokens`? */
        val truncated: Boolean get() = reason == FinishReason.LENGTH

        /** Texto en español del motivo (para la UI). */
        val label: String get() = FinishReason.label(reason)
    }
}
