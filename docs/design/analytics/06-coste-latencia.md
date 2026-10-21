# 06 · Coste, latencia y sostenibilidad

> Subagente: diseño de la analítica de **coste**, **latencia** y **sostenibilidad**
> de Kitoru.
>
> Módulos tocados:
> - `:core` (`kitoru.domain`): tipos nuevos de uso, el sketch de percentiles, el
>   estimador de tokens, el modelo de coste, el presupuesto y el informe
>   institucional.
> - `:llm-api`: **el gateway tiene que leer el `usage` real** que manda el
>   servidor (esto es un cambio obligatorio, no opcional: sin `usage` real no hay
>   coste ni presupuesto confiables).
> - `:backend` (`rag.agent.AgentLoop`): propagar el uso de cada ronda.
> - `:ui` (todavía no construido): panel "Uso y coste" del docente + informe
>   para la dirección de la facultad.
>
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable
> por defecto*, *determinista*, *fail-fast y con mensajes en español*, *el
> docente no ve lo que no debe*.

---

## 0. Resumen de las decisiones

| # | Decisión | Por qué |
|---|---|---|
| **D1** | El gateway pide `stream_options: {include_usage: true}` y **lee el `usage` real** del último chunk del SSE (`choices: []` + `usage`). Los tokens dejan de ser estimados cuando el servidor los informa. | El coste y el presupuesto se calculan sobre tokens. Medir "a ojo" (chars/4) con un ±25 % de error es inservible para un tope por alumno y vergonzoso en un informe para la dirección. |
| **D2** | Cuando el `usage` no llega (servidor que no lo soporta, error 400, IA local), se usa un **estimador portable** y el registro queda marcado `UsageSource.ESTIMATED`. | Nunca se disfraza una estimación como un dato. Cada número lleva su procedencia y la UI lo dice. |
| **D3** | Los percentiles se calculan sobre un **histograma de buckets exponenciales** (128 buckets, base 2^(1/8) ≈ 1,0905), no sobre la lista de muestras. | t-digest necesita una dependencia Java-only y es opaco al merge entre dispositivos; el histograma son ~700 bytes, se fusiona **exactamente** sumando contadores y da un error acotado a ±9 %. |
| **D4** | El percentil se interpola **en escala logarítmica** y con la convención del **punto medio** dentro del bucket (`frac = (rank − cum − 0,5) / n`). | Interpolar linealmente contra el borde superior del bucket sesga el estimador de forma sistemática (+9 % con n=1 por bucket). Con el punto medio el error típico baja a ≤ 4,5 %. |
| **D5** | El `usage` es un **recurso de primer clase**: `UsageRecord` (por respuesta), `UsageLedger` (agregado mergeable, lo que se persiste y lo que viaja) y `UsageRepository` (contrato). | Los mismos datos tienen que servir para el panel del docente, el presupuesto por alumno y el informe institucional sin recalcular ni duplicar. |
| **D6** | El coste se mide **en tokens**; el dinero es una capa opcional (`PriceTable`). Con el modelo alojado por la institución, la tabla está vacía y el panel dice "alojado por la institución". | La métrica honesta del costo de la institución es el **consumo de cómputo**, no una factura que no existe. El dinero aparece sólo si la cátedra usa una API comercial o como contrafactual. |
| **D7** | El presupuesto por alumno tiene **tres políticas** al agotarse: `AVISAR`, `DEGRADAR`, `BLOQUEAR`. El valor por defecto es `DEGRADAR`. | Bloquear corta el flujo pedagógico (el alumno no puede preguntar en el momento en que necesita preguntar); degradar degrada sin cortar. Avisar es lo más blando. |
| **D8** | El presupuesto **se aplica en el servidor** (Kitoru-Server), no sólo en el cliente. | El cliente es del alumno y se puede parchar: un tope medido sólo en el cliente no es un tope. En el cliente queda la *advertencia* (que es UX), en el servidor la *norma*. |
| **D9** | La energía y el disco del alumno con IA local **no son métricas**: se mencionan como nota informativa en el panel. | No son costo de la cátedra ni de la institución; medirlos sería ruido que además invita a discusiones estériles. |
| **D10** | El informe institucional se calcula con una **función pura** (`InstitutionReportBuilder`) y se exporta a Markdown/HTML. | Es un entregable real que se lleva la dirección de la facultad: tiene que ser reproducible, auditable y no depender de la UI. |
| **D11** | Al Kitoru-Server / a la institución viaja el **ledger de uso**, no el texto de los chats. | La institución necesita el consumo, no las conversaciones. Privacidad por diseño (misma línea que la matriz de visibilidad del CONTEXTO). |

---

## 1. Qué se mide y por qué

### 1.1 Las tres preguntas que la analítica tiene que responder

| Pregunta | Se responde con | Dónde se ve |
|---|---|---|
| "¿El modelo responde rápido?" | **Latencia por respuesta**: mediana, p95, p99 (no sólo la media). | Panel del docente. |
| "¿Cuánto consume la cátedra?" | **Tokens** (entrada, salida, cacheados), **peticiones por alumno**, y **coste estimado** si hay tabla de precios. | Panel del docente. |
| "¿Conviene que la institución aloje el modelo?" | **Horas de cómputo**, **concurrencia pico**, **margen**, **energía** y **costo equivalente en API comercial**. | Informe para la dirección. |

### 1.2 Por qué la media no alcanza

La media de latencia mezcla dos poblaciones: la respuesta corta ("¿qué es un gradiente?") y la respuesta larga con RAG ("resolvé este ejercicio de 3 páginas"). Con 5 respuestas de 400 ms y una de 60 s, la media da 10,4 s y el docente cree que el servidor es lento, cuando el problema es **una** respuesta larga.

Los percentiles sí separan el caso típico del caso raro:

- **p50 (mediana)**: "lo normal" — el tiempo que el alumno percibe en el caso habitual.
- **p95**: "el alumno lento" — el 5 % de las respuestas que se sientenposites.
- **p99**: "el peor caso habitual" — de acá sale la promesa de calidad de servicio.

Además, la media **no se puede fusionar**: promediar promedios de subgrupos exige guardar los pesos y es inexacto. Los histogramas sí se fusionan (§3.5), que es justo lo que hace falta para el informe institucional (muchos cursos, muchos dispositivos).

### 1.3 Definiciones (glosario que va al informe)

| Término | Definición precisa |
|---|---|
| **Respuesta (answer)** | Un mensaje del asistente visible para el alumno. Puede haber costado **más de una** llamada al modelo (bucle de herramientas). |
| **Petición (request)** | Una llamada HTTP a `/v1/chat/completions`. Es la unidad de cómputo del servidor. |
| **Latencia total** | Desde que el cliente envía la petición hasta que llega el último token. Incluye red + cola del servidor + cómputo. Es lo que percibe el alumno. |
| **TTFT** (*time to first token*) | Desde que el cliente envía hasta que llega el primer token. Es lo que percibe el alumno como "espera". |
| **Latencia de servidor** | `prompt_ms + predicted_ms` que manda llama.cpp en el bloque `timings`. Es el **cómputo real**. Sirve para las horas de cómputo; la latencia total no, porque incluye la red. |
| **Tokens de entrada** | Tokens del prompt (system + historial + contexto RAG + pregunta). |
| **Tokens de salida** | Tokens generados (completion). |
| **Tokens cacheados** | Parte de la entrada que el servidor sirvió desde su KV cache. Siguen costando cómputo, pero mucho menos; y en una API comercial suelen ser más baratos. |
| **Tokens facturables** | `tokensIn − cachedTokensIn + tokensOut`. Es lo que consume el presupuesto del alumno. |
| **Horas-máquina** | Σ de la latencia de servidor de todas las peticiones, en horas. Mide "cuánto tiempo estuvo el equipo ocupado". |
| **Horas-GPU** | Horas de cómputo estimadas a partir del throughput medido (prefill y decode por separado). Mide "cuánta GPU hay que comprar". |

---

## 2. De dónde salen los datos: el `usage` real

### 2.1 El protocolo OpenAI

En una llamada **con streaming**, la respuesta del servidor es una secuencia de chunks SSE:

```
data: {"choices":[{"delta":{"content":"Hola"}}], ...}
data: {"choices":[{"delta":{"content":" ¿qué tal?"}}], ...}
data: {"choices":[], "usage":{"prompt_tokens":812,"completion_tokens":47,"total_tokens":859}}
data: [DONE]
```

Para que el último chunk con `usage` aparezca, el cliente tiene que **pedirlo**:

```json
{
  "model": "qwen3-8b",
  "stream": true,
  "stream_options": { "include_usage": true }
}
```

Reglas del protocolo (OpenAI, "Streaming — choices"):

- Con `include_usage: true`, el último chunk de datos tiene **`choices: []`** (array vacío) y el objeto `usage` **al nivel raíz**, no dentro de `choices[0]`.
- `usage` puede traer `prompt_tokens`, `completion_tokens`, `total_tokens` y, opcionalmente, `prompt_tokens_details.cached_tokens` y `completion_tokens_details.reasoning_tokens`.
- Después de ese chunk viene `data: [DONE]`.

> **El bug que hay que corregir**: el `OpenAiCompatibleGateway` actual hace
> `choices?.firstOrNull()?.jsonObject ?: continue` — con `choices: []` hace
> `continue` y **el `usage` se pierde**. No es que falte pedirlo: es que el
> parser descarta el chunk. Por eso D1 es "cambiar el gateway", no "agregar un
> campo".

### 2.2 Qué devuelve llama.cpp (el Kitoru-Server y el `llama-server` local)

Verificado en el código de `llama.cpp` que ya está en el repo hermano
(`app-home/llama.cpp/tools/server/server-task.cpp`):

```cpp
json server_task_result_cmpl_final::usage_json_oaicompat() {
    return json {
        {"completion_tokens", n_decoded},
        {"prompt_tokens",     n_prompt_tokens},
        {"total_tokens",      n_decoded + n_prompt_tokens},
        {"prompt_tokens_details", json { {"cached_tokens", n_prompt_tokens_cache} }},
    };
}
```

y en el chunk final del stream:

```cpp
if (include_usage) {
    // OpenAI API spec ... specifies an empty `choices` array for the last chunk
    deltas.push_back({ ..., {"choices", json::array()}, ..., {"usage", usage_json_oaicompat()} });
}
if (stats.is_set()) {
    deltas.back()["timings"] = stats.to_json();
}
```

Dos consecuencias, muy buenas para este diseño:

1. **llama-server soporta `stream_options.include_usage`** y devuelve tokens
   **reales** (contados por el tokenizador del modelo, no estimados). El
   Kitoru-Server, que es un llama-server, therefore los da sin trabajo extra.
2. El chunk final **siempre** trae `timings` (`server_slot_stats::is_set()` es
   `t_start > 0`, y `send_final_response` copia los stats siempre):

```json
"timings": {
  "cache_n": 0, "prompt_n": 812, "prompt_ms": 210.4, "prompt_per_second": 3859.3,
  "predicted_n": 47, "predicted_ms": 980.2, "predicted_per_token_ms": 20.8,
  "predicted_per_second": 47.9
}
```

`timings` es **tiempo de cómputo del servidor** (`prompt_ms + predicted_ms`) y
**throughput** (tokens/s). Sin esto, las "horas de cómputo" del informe
institucional serían unaGuess: con la latencia del cliente no se puede
separar la red del cómputo.

> `timings` es una extensión de llama.cpp, no del protocolo OpenAI. El parser
> la lee **si viene** y no falla si no viene (una API comercial no la manda).

### 2.3 Plan de cambios en `:llm-api` (obligatorio)

#### 2.3.1 Archivo nuevo `llm-api/src/commonMain/kotlin/llm/api/LlmUsage.kt`

```kotlin
package llm.api

import kotlinx.serialization.Serializable

/**
 * Tokens y tiempos que informa el servidor para una llamada al modelo.
 *
 * Sale del bloque `usage` del último chunk del SSE (con
 * `stream_options.include_usage`) y, en servidores llama.cpp, del bloque
 * `timings` del mismo chunk.
 */
@Serializable
data class LlmUsage(
    /** Tokens del prompt. */
    val promptTokens: Int = 0,
    /** Tokens generados. */
    val completionTokens: Int = 0,
    /** prompt + completion (si el servidor no lo manda, se calcula). */
    val totalTokens: Int = 0,
    /** Tokens del prompt servidos desde caché (`prompt_tokens_details.cached_tokens`). */
    val cachedPromptTokens: Int = 0,
    /** Tokens de razonamiento (`completion_tokens_details.reasoning_tokens`). */
    val reasoningTokens: Int = 0,
    /** true si el servidor informó el `usage` (false = hay que estimar). */
    val reported: Boolean = false,
    /** Tiempos del servidor (extensión llama.cpp). null si no vinieron. */
    val timings: LlmTimings? = null,
) {
    /** Suma de dos usos (bucle de herramientas: una respuesta, varias rondas). */
    operator fun plus(other: LlmUsage): LlmUsage = LlmUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens,
        cachedPromptTokens = cachedPromptTokens + other.cachedPromptTokens,
        reasoningTokens = reasoningTokens + other.reasoningTokens,
        reported = reported && other.reported,
        timings = null, // los tiempos no se suman: se promedian aguas arriba
    )
}

/** Tiempos de cómputo del servidor (`timings` de llama.cpp). */
@Serializable
data class LlmTimings(
    val promptTokens: Int = 0,
    val promptMs: Double = 0.0,
    val promptPerSecond: Double = 0.0,
    val predictedTokens: Int = 0,
    val predictedMs: Double = 0.0,
    val predictedPerSecond: Double = 0.0,
    val cachedTokens: Int = 0,
) {
    /** Tiempo de cómputo total del servidor (ms). */
    val serverMs: Long get() = (promptMs + predictedMs).toLong()
}
```

#### 2.3.2 `llm-api/src/commonMain/kotlin/llm/api/Tools.kt` — un chunk nuevo

```kotlin
/** Chunk tipado del streaming del modelo: texto, herramienta o uso. */
sealed class LlmChunk {
    data class Text(val delta: String) : LlmChunk()

    data class ToolCall(
        val id: String,
        val name: String,
        val argsJson: String,
    ) : LlmChunk()

    /**
     * NUEVO: uso real de tokens de la llamada que terminó.
     *
     * Llega **al final** del stream (después del último texto) y sólo si el
     * servidor informó `usage`. Si no llega, el que llama estima (§2.4).
     */
    data class Usage(val usage: LlmUsage) : LlmChunk()
}
```

Agregar la variante obliga a tocar el `when (chunk)` del `AgentLoop` (§2.5):
es la forma de que el compilador nos asegure que nadie se olvide del uso.

#### 2.3.3 `llm-api/src/commonMain/kotlin/llm/api/LlmGateway.kt` — el diff

**(a) Pedir el `usage`** en el body del request:

```kotlin
override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> = flow {
    val body = buildJsonObject {
        put("model", model)
        put("stream", true)
        put("temperature", temperature)
        put("max_tokens", effectiveMaxTokens)
        if (thinking.isNotBlank() && !thinking.equals("off", ignoreCase = true)) {
            put("reasoning_effort", thinking.lowercase())
        }
        // NUEVO: pedir el `usage` real. Es lo que habilita coste y presupuesto.
        if (includeUsage && usageSupported != false) {
            putJsonObject("stream_options") { put("include_usage", true) }
        }
        if (!tools.isNullOrEmpty()) { putJsonArray("tools") { tools.forEach { add(toolToBody(it)) } } }
        putJsonArray("messages") { messages.forEach { add(messageToBody(it)) } }
    }
    ...
```

**(b) Parsear el chunk de `usage`** — la parte clave. Va **antes** de mirar
`choices`, porque el chunk de `usage` viene con `choices: []`:

```kotlin
    while (true) {
        val line = channel.readUTF8Line() ?: break
        if (!line.startsWith("data:")) continue
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") break

        val root = json.parseToJsonElement(data).jsonObject

        // NUEVO: el chunk de uso va al nivel raíz, con `choices: []`.
        // Hay que leerlo ANTES de tocar `choices`, porque el `?: continue`
        // de abajo se lo tragaría.
        root["usage"]?.let { raw ->
            (raw as? JsonObject)?.let { obj ->
                val usage = parseUsage(obj)
                if (usage.reported) emit(LlmChunk.Usage(usage))
            }
        }

        val choice = root["choices"]
            ?.let { it as? JsonArray }?.firstOrNull()?.jsonObject ?: continue
        val delta = choice["delta"]?.jsonObject
        ...  // (fragmentos de texto y tool_calls: igual que hoy)
    }
```

**(c) El parser del `usage`**, tolerante a servidores distintos:

```kotlin
    /**
     * Lee el bloque `usage` de la respuesta.
     *
     * Acepta las dos variantes que hay en la calle:
     *  - OpenAI: `prompt_tokens`, `completion_tokens`, `total_tokens`,
     *    `prompt_tokens_details.cached_tokens`, `completion_tokens_details.reasoning_tokens`.
     *  - llama.cpp: los mismos + `timings` (extensión propia).
     */
    private fun parseUsage(o: JsonObject): LlmUsage {
        val prompt = o.int("prompt_tokens")
        val completion = o.int("completion_tokens")
        val total = o.int("total_tokens").let { if (it > 0) it else prompt + completion }
        val cached = o.obj("prompt_tokens_details").int("cached_tokens")
        val reasoning = o.obj("completion_tokens_details").int("reasoning_tokens")
        val timings = o.obj("timings")?.let { t ->
            LlmTimings(
                promptTokens = t.int("prompt_n"),
                promptMs = t.dbl("prompt_ms"),
                promptPerSecond = t.dbl("prompt_per_second"),
                predictedTokens = t.int("predicted_n"),
                predictedMs = t.dbl("predicted_ms"),
                predictedPerSecond = t.dbl("predicted_per_second"),
                cachedTokens = t.int("cache_n"),
            )
        }
        val reported = prompt > 0 || completion > 0
        return LlmUsage(prompt, completion, total, cached, reasoning, reported, timings)
    }

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

    private fun JsonObject.dbl(key: String): Double =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
```

**(d) El fallback si el endpoint no conoce `stream_options`.** Algunos
servidores OpenAI-compatible (vLLM viejos, proxies) contestan `400` a un
campo desconocido. El gateway **reintenta una sola vez sin `stream_options`**
y deja de pedirlo en esa instancia:

```kotlin
    /** null = todavía no sabemos si el endpoint soporta `stream_options`. */
    @Volatile private var usageSupported: Boolean? = null

    /** Error interno: el endpoint rechazó `stream_options`. */
    private class UsageNotSupported : Exception()

    // dentro de execute { resp -> ... }
    if (!resp.status.isSuccess()) {
        val body = resp.bodyAsText()
        if (resp.status.value == 400 && withUsage &&
            (body.contains("stream_options", ignoreCase = true) ||
             body.contains("unrecognized", ignoreCase = true) ||
             body.contains("unknown field", ignoreCase = true))
        ) {
            throw UsageNotSupported()
        }
        throw LlmException.fromHttp(resp.status.value, body, local = isLocalUrl)
    }
```

y en el `try` que envuelve la petición:

```kotlin
    try {
        try {
            attempt(withUsage = includeUsage && usageSupported != false)
        } catch (e: UsageNotSupported) {
            // El endpoint no soporta `stream_options`: reintentamos sin él y
            // no lo volvemos a pedir en esta instancia del gateway.
            usageSupported = false
            attempt(withUsage = false)
        }
    } catch (e: LlmException) { ... }   // el resto igual que hoy
```

`attempt` es el `request` actual, refactorizado para recibir `withUsage`. El
timeout `chatTimeoutMs` se aplica **por intento** (el reintento tiene su propio
timeout, no hereda el que ya venció).

**(e) La configuración**:

```kotlin
// llm-api/src/commonMain/kotlin/llm/config/GatewayConfig.kt
@Serializable
data class GatewayConfig(
    ...,
    /**
     * Pedir el `usage` real al servidor (`stream_options.include_usage`).
     * Se puede apagar si el endpoint da problemas con ese campo.
     */
    val includeUsage: Boolean = true,
)
```

y el parámetro correspondiente en `OpenAiCompatibleGateway`.

**(f) Una API de conveniencia** para quien no quiere el streaming:

```kotlin
// en LlmGateway
/** Respuesta completa con el uso real (o null si el servidor no lo informó). */
suspend fun chatWithUsage(messages: List<ChatMessage>): LlmResponse {
    val text = StringBuilder()
    var usage: LlmUsage? = null
    chatStream(messages).collect { chunk ->
        when (chunk) {
            is LlmChunk.Text -> text.append(chunk.delta)
            is LlmChunk.Usage -> usage = chunk.usage
            is LlmChunk.ToolCall -> Unit
        }
    }
    return LlmResponse(text.toString(), usage)
}

data class LlmResponse(val text: String, val usage: LlmUsage? = null)
```

### 2.4 El estimador portable (fallback)

Cuando el `usage` no llega, hay que estimar. La heurística estándar de la
industria es **4 caracteres por token**; en español técnico el error real es de
**−12 % a +25 %** (las palabras en español son más largas y los tokenizadores
BPE cortan diferente).

Archivo nuevo `core/src/commonMain/kotlin/kitoru/domain/TokenEstimator.kt`:

```kotlin
package kitoru.domain

import kotlin.math.ceil

/**
 * Estimador portable de tokens.
 *
 * Es el **fallback**: si el servidor informó `usage`, se usan esos números
 * (ver `UsageSource.REAL`). Este estimador existe para (a) servidores que no
 * devuelven `usage` y (b) la IA local del alumno, donde no hay servidor que
 * cuente.
 *
 * Regla: 4 caracteres por token en texto latino (la heurística estándar),
 * con corrección para CJK (donde un carácter es ~1 token) y para emoji.
 *
 * Límites (documentados a propósito):
 *  - Error típico en español/inglés técnico: **−12 % … +25 %**.
 *  - No sirve para facturar: sólo para tendencias y para topes *aproximados*.
 *  - No modela el tokenizador del modelo (BPE vs SPM): dos modelos distintos
 *    con el mismo texto dan números distintos.
 */
object TokenEstimator {

    /** Tokens que agrega el formato de chat por mensaje (role + separadores). */
    const val CHAT_OVERHEAD_PER_MESSAGE = 4

    /** Tokens del prefijo de respuesta del asistente. */
    const val REPLY_OVERHEAD = 3

    /** Caracteres (code points) por token en texto latino. */
    const val CHARS_PER_TOKEN = 4.0

    /** Code points CJK por token. */
    const val CJK_CHARS_PER_TOKEN = 1.5

    /** Tokens por emoji o símbolo. */
    const val TOKENS_PER_SYMBOL = 2

    /** Tokens estimados de un texto. 0 si el texto está vacío. */
    fun estimate(text: String): Int {
        if (text.isBlank()) return 0
        var latin = 0
        var cjk = 0
        var symbols = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            // Code point completo (los emoji viven fuera del BMP).
            val cp: Int
            val step: Int
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                cp = 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                step = 2
            } else {
                cp = c.code
                step = 1
            }
            i += step
            when {
                isCjk(cp) -> cjk++
                isSymbol(cp) -> symbols++
                else -> latin++
            }
        }
        val tokens = ceil(latin / CHARS_PER_TOKEN) +
            ceil(cjk / CJK_CHARS_PER_TOKEN) +
            symbols * TOKENS_PER_SYMBOL
        return maxOf(1, tokens)
    }

    /** Tokens estimados de una conversación, con el envoltorio del chat. */
    fun estimateConversation(messages: List<Pair<String, String>>): Int {
        var total = REPLY_OVERHEAD
        for ((_, content) in messages) {
            total += estimate(content) + CHAT_OVERHEAD_PER_MESSAGE
        }
        return total
    }

    /** ¿Es un code point CJK (kanji, kana, hangul)? */
    fun isCjk(cp: Int): Boolean = when (cp) {
        in 0x3040..0x30FF -> true    // hiragana + katakana
        in 0x3400..0x4DBF -> true    // CJK ext. A
        in 0x4E00..0x9FFF -> true    // CJK unificado
        in 0xF900..0xFAFF -> true    // compatibilidad
        in 0xAC00..0xD7AF -> true    // hangul
        in 0x20000..0x2A6DF -> true  // CJK ext. B
        else -> false
    }

    /** ¿Es un emoji o símbolo (cuenta más que un carácter latino)? */
    fun isSymbol(cp: Int): Boolean =
        cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF
}
```

> **Portable**: no usa `java.lang.Character` ni `String.codePointAt` (no existen
> en `commonMain`); los surrogate pairs se combinan a mano con
> `Char.isHighSurrogate()` / `isLowSurrogate()`, que sí son de `kotlin.text`.

**Dónde se usa el estimador:**

| Situación | Tokens |
|---|---|
| Servidor con `usage` (Kitoru-Server, OpenAI, …) | **reales** (`UsageSource.REAL`) |
| Servidor que rechazó `stream_options` | estimados (`UsageSource.ESTIMATED`) |
| IA local del alumno (llama-server sin `include_usage`) | estimados (`UsageSource.ESTIMATED`) |

**La UI muestra la diferencia**: el panel dice "Tokens (reales)" o "Tokens
(estimados, ±25 %)". Un presupuesto sobre tokens estimados se marca como
"aproximado" en el panel del docente.

### 2.5 Propagar el uso por el bucle de herramientas

Con herramientas, **una respuesta = varias peticiones**. El uso total de la
respuesta es la suma de las rondas.

`backend/src/commonMain/kotlin/rag/agent/AgentLoop.kt`:

```kotlin
/** Evento nuevo: el servidor informó el uso de la ronda. */
sealed class AgentEvent {
    ...
    /** El servidor informó el uso real de tokens de esta ronda. */
    data class Usage(val usage: LlmUsage) : AgentEvent()
}

/** Respuesta completa del loop: texto visible + herramientas + uso. */
data class AgentAnswer(
    val text: String,
    val toolCalls: List<ExecutedTool> = emptyList(),
    /** Uso acumulado de todas las rondas (null si el servidor no informó). */
    val usage: LlmUsage? = null,
    /** Cuántas llamadas al modelo hizo esta respuesta. */
    val rounds: Int = 1,
)
```

y dentro de `run()`:

```kotlin
    var totalUsage: LlmUsage? = null
    var rounds = 0
    ...
    for (iteration in 1..maxIterations) {
        rounds++
        gateway.chatStream(work, ...).collect { chunk ->
            when (chunk) {
                is LlmChunk.Text -> { ... }
                is LlmChunk.ToolCall -> call = chunk
                is LlmChunk.Usage -> {
                    totalUsage = (totalUsage ?: LlmUsage())!! + chunk.usage
                    onEvent(AgentEvent.Usage(chunk.usage))
                }
            }
        }
        ...
    }
    return AgentAnswer(visible.toString(), used, totalUsage, rounds)
```

> Ojo con el `+` de `LlmUsage`: los `timings` no se suman (son de una ronda);
> para el throughput se promedian aguas arriba en el ledger (§4.4).

### 2.6 Del `LlmUsage` al dominio

`LlmUsage` vive en `:llm-api`; el dominio habla `UsageRecord`. El mapeo va en
`:llm-api` (que ya depende de `:core`):

```kotlin
// llm-api/src/commonMain/kotlin/llm/api/LlmUsage.kt (extensión)

/** Convierte el uso del gateway en el registro de dominio. */
fun LlmUsage.toRecord(
    model: String,
    latencyMs: Long,
    ttftMs: Long,
    questionTokens: Int,
    tools: List<String>,
    rounds: Int,
    at: Long,
): UsageRecord = UsageRecord(
    model = model,
    latencyMs = latencyMs,
    ttftMs = ttftMs,
    tokensIn = promptTokens,
    tokensOut = completionTokens,
    cachedTokensIn = cachedPromptTokens,
    reasoningTokens = reasoningTokens,
    questionTokens = questionTokens,
    rounds = rounds,
    tools = tools,
    serverMs = timings?.serverMs ?: 0L,
    promptTokensPerSec = timings?.promptPerSecond ?: 0.0,
    decodeTokensPerSec = timings?.predictedPerSecond ?: 0.0,
    source = if (reported) UsageSource.REAL else UsageSource.ESTIMATED,
    at = at,
)
```

---

## 3. Latencia

### 3.1 Las tres latencias que se miden

| Latencia | Dónde se mide | Para qué |
|---|---|---|
| **Total** | Cliente (`nowMillis()` antes y después del stream). | Lo que percibe el alumno. Va al p50/p95/p99 del panel. |
| **TTFT** | Cliente (primer `LlmChunk.Text`). | "Cuánto espera antes de ver algo". |
| **Servidor** | `timings` de llama.cpp (`prompt_ms + predicted_ms`). | Horas de cómputo reales (§7). |

Las dos primeras se miden **siempre**; la tercera sólo cuando el servidor la
manda (Kitoru-Server sí).

Además, `ToolCallStats.durationMs` (que ya existe) mide la **latencia de cada
herramienta**: es lo que alimenta el corte "latencia por herramienta" (§3.6).

### 3.2 Por qué un histograma y no t-digest

**Lo que se descarta y por qué:**

| Alternativa | Por qué no |
|---|---|
| Guardar todas las muestras y ordenar | Es exactamente lo que no se quiere: la memoria crece con el uso (100 k respuestas × 8 bytes = 800 kB por dimensión, y hay 4 dimensiones) y **no se puede fusionar** sin mandar todas las muestras. |
| t-digest | Excelente para percentiles de alta precisión, pero: (a) necesita una dependencia (`com.tdunning:t-digest`) que es Java puro → no entra en `commonMain` sin workarounds; (b) su merge es "aproximado" y **no conmutativo de forma exacta** → dos ledgers mergeados en distinto orden dan números distintos (mal para un informe que se audita); (c) el formato serializado es opaco (centroides + pesos). |
| Reservoir sampling | No sirve: da una muestra uniforme, no la cola. El p99 es justamente la cola. |
| KLL / DDSketch | DDSketch es interesante (garantía relativa de error) pero es otro algoritmo que mantener y no está en el stdlib de Kotlin. |

**Lo que se elige: histograma de buckets exponenciales** (D3).

Propiedades:

- **Merge exacto**: fusionar dos histogramas es sumar contadores bucket a
  bucket. El resultado es *idéntico* al que se obtendría si se hubieran
  mezclado las muestras antes. Esto es lo que hace auditable el informe
  institucional (el orden de fusión no importa).
- **Memoria acotada**: 128 contadores (`Int`) = 512 bytes + 4 escalares. Da
  igual si hay 10 o 10 millones de muestras.
- **Costo de actualización O(1)**: una suma y un `ln`. Se puede actualizar en
  el camino caliente (cada respuesta) sin impacto.
- **Serializable**: un `List<Int>` en JSON. Un ledger por cátedra pesa ~700 B
  por dimensión.
- **Error relativo acotado**: con base 2^(1/8), cada bucket abarca un factor
  1,0905 → el error worst-case del percentil es **±9,05 %** y el típico
  **≤ ±4,5 %**. Para latencia (una magnitud que la gente lee "en segundos")
  es más que suficiente.
- **Portable**: `kotlin.math.ln`/`pow` están en el stdlib común. Cero
  dependencias.

### 3.3 El histograma: definición exacta

Bucket `i` cubre el intervalo **`[edge(i), edge(i+1))`** en milisegundos:

```
edge(i) = 1 ms · BASE^i        con BASE = 2^(1/8) ≈ 1,0905077326652577
```

- Bucket 0: `[1 ms, 1,09 ms)`. Los 0 ms (sin medir) no entran.
- Bucket 127: `[60,1 s, ∞)`. El último bucket no tiene borde superior fijo:
  para interpolar se usa el **máximo observado** (`maxMs`), que además es
  exacto.
- Cobertura: `edge(128) = 65,5 s`. Por encima de 1 minuto la latencia se
  considera "fuera de rango" y se ve como anomalía, no como percentil.

Índice del bucket de una latencia:

```
bucketOf(ms) = floor( ln(ms / 1) / ln(BASE) )        para ms ≥ 1
             = 0                                      para ms ≤ 1
```

El tipo, tal como va en `:core` (archivo nuevo
`core/src/commonMain/kotlin/kitoru/domain/LatencyHistogram.kt`):

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Histograma de latencias con buckets exponenciales.
 *
 * Es el sketch que usan los percentiles: **no guarda las muestras**, guarda
 * cuántas hay en cada bucket. Por eso:
 *  - la memoria es constante (128 contadores) por más que haya millones de
 *    respuestas;
 *  - dos histogramas se fusionan **exactamente** (se suman los contadores),
 *    que es lo que permite agregar las cursadas de toda la institución;
 *  - el percentil es una estimación con error relativo acotado a
 *    `BASE - 1 = 9,05 %` (típico ≤ 4,5 % con la convención del punto medio).
 *
 * Es una función pura: `add` y `merge` devuelven un histograma nuevo.
 */
@Serializable
data class LatencyHistogram(
    /** Contadores por bucket (índice 0..[BUCKETS]-1). */
    val counts: List<Int> = List(BUCKETS) { 0 },
    /** Cantidad total de muestras. */
    val count: Long = 0L,
    /** Suma de las latencias (ms). */
    val sumMs: Long = 0L,
    /** Latencia mínima observada (ms). */
    val minMs: Long = 0L,
    /** Latencia máxima observada (ms). */
    val maxMs: Long = 0L,
) {
    /** Agrega una muestra. */
    fun add(latencyMs: Long): LatencyHistogram {
        val c = counts.toMutableList()
        while (c.size < BUCKETS) c.add(0)   // tolera un JSON con menos contadores
        c[bucketOf(latencyMs)] += 1
        return copy(
            counts = c,
            count = count + 1L,
            sumMs = sumMs + latencyMs,
            minMs = if (count == 0L) latencyMs else minOf(minMs, latencyMs),
            maxMs = maxOf(maxMs, latencyMs),
        )
    }

    /** Fusiona dos histogramas. El merge es exacto (suma de contadores). */
    fun merge(other: LatencyHistogram): LatencyHistogram {
        if (other.count == 0L) return this
        if (count == 0L) return other
        val c = counts.toMutableList()
        while (c.size < BUCKETS) c.add(0)
        for (i in c.indices) c[i] += other.counts.getOrElse(i) { 0 }
        return copy(
            counts = c,
            count = count + other.count,
            sumMs = sumMs + other.sumMs,
            minMs = minOf(minMs, other.minMs),
            maxMs = maxOf(maxMs, other.maxMs),
        )
    }

    /**
     * Percentil `p` (0..1) estimado sobre el histograma.
     *
     * Interpolación geométrica (el bucket es exponencial) con la convención
     * del punto medio: `frac = (rank - cum - 0,5) / n`.
     */
    fun percentile(p: Double): Long {
        if (count <= 0L) return 0L
        val rank = ceil(p.coerceIn(0.0, 1.0) * count).toLong().coerceIn(1L, count)
        var cum = 0L
        for (i in counts.indices) {
            val n = counts[i].toLong()
            if (n == 0L) continue
            if (cum + n >= rank) {
                val lo = edgeMs(i)
                val hi = if (i == BUCKETS - 1) maxOf(maxMs.toDouble(), lo) else edgeMs(i + 1)
                val frac = ((rank - cum - 0.5) / n).coerceIn(0.0, 1.0)
                return (lo * (hi / lo).pow(frac)).roundToLong().coerceIn(minMs, maxMs)
            }
            cum += n
        }
        return maxMs
    }

    /** Media exacta (no se estima desde los buckets). */
    val meanMs: Long get() = if (count == 0L) 0L else sumMs / count
    val p50Ms: Long get() = percentile(0.50)
    val p95Ms: Long get() = percentile(0.95)
    val p99Ms: Long get() = percentile(0.99)

    companion object {
        /** Cantidad de buckets (fijo). */
        const val BUCKETS = 128

        /** Base de los buckets: 2^(1/8) ≈ 1,0905077326652577 (8 por octava). */
        const val BASE = 1.0905077326652577

        /** Borde inferior del bucket 0 (ms). */
        const val MIN_MS = 1.0

        /**
         * Índice del bucket donde cae `ms`.
         * Cobertura: bucket 127 empieza en 60 096 ms (60,1 s) y es el último.
         */
        fun bucketOf(ms: Long): Int {
            if (ms <= 1L) return 0
            val i = (ln(ms.toDouble() / MIN_MS) / ln(BASE)).toInt()
            return i.coerceIn(0, BUCKETS - 1)
        }

        /** Borde inferior del bucket `i` (ms). */
        fun edgeMs(i: Int): Double = MIN_MS * BASE.pow(i)
    }
}
```

### 3.4 El cálculo del percentil (convención del punto medio)

```
percentile(p):
    si count == 0 → 0
    rank = ceil(p · count)                    (nearest-rank)
    recorrer los buckets acumulando cum:
        si cum + n ≥ rank:
            lo = edge(i)
            hi = edge(i+1)   (o maxMs si i es el último bucket)
            frac = (rank − cum − 0.5) / n      ∈ (0, 1]
            est = lo · (hi/lo)^frac            (interpolación GEOMÉTRICA)
            devolver est
    devolver maxMs
```

Dos decisiones y por qué:

1. **Interpolación geométrica (en escala logarítmica), no lineal.** El bucket
   es exponencial: dentro de él la distribución es uniforme en *log*, no en
   valores. Interpolar linealmente sesga hacia el borde inferior cuando el
   bucket es ancho.
2. **Convención del punto medio** (`− 0,5` en el numerador). Con `n` muestras
   en el bucket, el estimador no sesgado del k-ésimo orden dentro del bucket
   usa la posición `(k − 0,5)/n`, no `k/n`. Con `n = 1` (el caso más común:
   muchas respuestas en buckets distintos) esto da el **punto medio** del
   bucket en lugar de su borde superior. Sin esta corrección, el p50 de un
   conjunto con una muestra por bucket queda sistemáticamente ~+9 % arriba.

**Error máximo garantizado**: como `est` siempre cae dentro de `[edge(i),
edge(i+1))` y ese intervalo abarca un factor `BASE`, el error relativo es
`≤ BASE − 1 = 9,05 %`. Con la convención del punto medio, el error *esperado*
es ≤ 4,5 %.

### 3.5 Ejemplo numérico (verificable)

Muestras de latencia (ms): `420, 510, 640, 780, 950, 1200, 1500, 2100, 3400, 8000`.

| Métrica | Exacto (ordenando) | Histograma | Error |
|---|---|---|---|
| p50 | 950 ms (rank 5) | bucket 79, `lo=939,0`, `hi=1024,0`, `frac=0,5` → `√(939,0·1024,0)` = **981 ms** | +3,2 % |
| p95 | 8000 ms (rank 10) | bucket 103, `lo=7512,1`, `hi=8192,0`, `frac=0,5` → **7845 ms** | −1,9 % |
| p99 | 8000 ms (rank 10) | **7845 ms** | −1,9 % |
| media | 1950 ms | `sumMs / count` = **1950 ms** | 0 % (la media es exacta) |
| mínimo / máximo | 420 / 8000 | exactos | 0 % |

La media se calcula con `sumMs` y `count` exactos (no desde el histograma):
es gratis y exacta.

### 3.6 Las dimensiones del corte

| Dimensión | Clave | Por qué |
|---|---|---|
| **Global** | — | La salud general del servicio. |
| **Por modelo** | `model` (el id efectivo) | Un modelo chico y uno grande se comportan distinto; el docente tiene que ver cuál está frenando. |
| **Por tamaño de contexto** | `ContextSize` (§3.7) | "preguntas largas vs cortas": separa el costo del RAG del costo de la pregunta. |
| **Por herramienta** | nombre de la tool | Una tool que llama a la web agrega segundos; el docente tiene que verlo. |

Además, para las herramientas hay **dos** histogramas distintos y no se
confunden:

1. **Latencia de la herramienta** (`ToolCallStats.durationMs`): cuánto tardó
   la herramienta en ejecutarse.
2. **Latencia de la respuesta que la usó**: la latencia total del mensaje que
   tuvo esa tool. Una respuesta con 2 tools cuenta en los dos cortes (no es
   excluyente): sirve para "¿las respuestas con `web_search` tardan más?".

### 3.7 El corte por tamaño de contexto

El contexto es el driver del costo y de la latencia. Se define sobre los
**tokens de entrada** (`tokensIn`, que ya incluyen el contexto RAG + historial
+ pregunta):

| Bucket | Rango (`tokensIn`) | Lectura |
|---|---|---|
| `CORTA` | ≤ 512 | Pregunta corta, sin RAG. |
| `MEDIA` | 513 – 2 048 | RAG típico (5 fragmentos × ~400 tokens). |
| `LARGA` | 2 049 – 8 192 | Historial largo o RAG pesado. |
| `MUY_LARGA` | > 8 192 | RAG con muchos fragmentos o contexto casi completo. |

Además del corte por contexto total, se guarda **`questionTokens`** (los
tokens de la pregunta del alumno, sin el contexto RAG) para poder separar:

- "pregunta corta + mucho RAG" → el costo lo genera el material de la cátedra,
  no el alumno.
- "pregunta larga" → el costo lo genera el alumno (y es el que hay que
  limitar con el presupuesto).

Esto alimenta una recomendación concreta para el docente: *bajar `topK` de 8 a
4 ahorra X tokens por respuesta y baja el p95 de Y ms*.

### 3.8 El sketch: dónde vive y cómo se mantiene

- **En caliente**: cada respuesta que se produce actualiza el histograma
  (`add`), O(1), en memoria. El `UsageLedger` de la cátedra se persiste
  (debounce) en `config/usage.json`.
- **Al recalcular**: el `AnalyticsAnalyzer` puede reconstruir el ledger desde
  los mensajes (por ejemplo si se borra el archivo o si llegan chats
  sincronizados). El fold es **streaming**: recorre los mensajes una vez y
  va sumando al histograma; **nunca materializa una lista ordenada de
  latencias**.
- **Al fusionar**: el informe institucional fusiona los ledgers de todas las
  cátedra (y de todos los dispositivos). El merge es exacto (§3.2).

> **Por qué no guardar las muestras**: el ledger es lo que viaja al servidor y
> lo que se muestra; las muestras crudas viven en los mensajes, que ya están
> guardados por otro motivo (el docente puede leer los chats). La maquinaria de
> percentiles **no depende** de ellas: si mañana se deja de guardar el texto
> de los chats (política de privacidad más estricta), los percentiles siguen
> funcionando porque están en el ledger.

### 3.9 El panel: qué se muestra y con qué advertencias

| Fila | Advertencia en la UI |
|---|---|
| p50 / p95 / p99 | Si `n < 100`, el p99 es prácticamente el máximo: la UI lo marca con un chip "muestra chica" y no lo usa para promesas de servicio. |
| Media | Se muestra **junto** a la mediana, no la reemplaza: "media 4,2 s · mediana 1,1 s" hace visible el efecto de la cola larga. |
| Por modelo / contexto / tool | Cada fila muestra su `n`: si una fila tiene 3 muestras, no se la toma en serio. |

---

## 4. Tokens

### 4.1 De dónde sale cada número

| Dato | Fuente | Marcado como |
|---|---|---|
| `tokensIn` / `tokensOut` | `usage` del SSE | `UsageSource.REAL` |
| `tokensIn` / `tokensOut` (fallback) | `TokenEstimator` | `UsageSource.ESTIMATED` |
| `cachedTokensIn` | `usage.prompt_tokens_details.cached_tokens` (llama.cpp) | `REAL` o 0 |
| `questionTokens` | `TokenEstimator` sobre la pregunta del alumno | siempre estimado (el servidor no lo separa) |
| `reasoningTokens` | `usage.completion_tokens_details.reasoning_tokens` | `REAL` o 0 |

### 4.2 La regla de no-doble-conteo

Esto es un bug esperando: con herramientas, una respuesta tiene N rondas y
**cada ronda manda su propio `usage`**. Si se suman los `promptTokens` de
todas las rondas, el consumo real está correcto (cada ronda consume lo suyo),
pero el "contexto de la pregunta" se cuenta N veces.

Regla:

- `tokensIn` = **suma de los prompt de todas las rondas** (es el consumo real
  del servidor).
- `questionTokens` = **sólo de la primera ronda** (la pregunta no cambia).
- `tokensOut` = suma de las completions.
- `rounds` = número de llamadas.

Además, el prompt de la ronda 2 incluye el prompt de la ronda 1 (historial) —
eso **es** consumo real y queda contabilizado. Por eso "tokens por respuesta"
en una conversación con herramientas es mayor: es correcto y hay que
explicarlo en el panel ("1 respuesta = 3 peticiones al modelo").

### 4.3 Tokens facturables vs tokens totales

```
tokensTotal     = tokensIn + tokensOut
tokensFacturable= (tokensIn − cachedTokensIn) + tokensOut
```

El presupuesto del alumno se mide en **tokens facturables** (los cacheados no
consumen presupuesto: ya se pagaron) pero el panel muestra los dos, porque
para la institución el token cacheado **sí** consumió cómputo (más barato, pero
consumido).

### 4.4 Throughput (para el informe institucional)

De los `timings` del servidor salen dos números que el panel del docente
también puede mostrar (son la explicación de la latencia):

```
prefillTokensPerSec = prompt_n / (prompt_ms / 1000)
decodeTokensPerSec  = predicted_n / (predicted_ms / 1000)
```

El ledger guarda la **media ponderada** por tokens:

```
promptTokensPerSec̄ = Σ(prompt_n) / Σ(prompt_ms / 1000)
```

(ponderar por tokens, no promediar los ratios: un request de 5 tokens no debe
pesar lo mismo que uno de 8000).

### 4.5 El ledger de uso (agregado mergeable)

Archivo nuevo `core/src/commonMain/kotlin/kitoru/domain/UsageLedger.kt`:

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Agregado mergeable del uso de una cátedra.
 *
 * Es lo que se persiste, lo que muestra el panel y lo que viaja al
 * Kitoru-Server / a la institución. **Nunca** contiene texto de los chats:
 * sólo números. La privacidad viene por diseño (D11).
 */
@Serializable
data class UsageLedger(
    val courseId: String = "",
    /** Cursada ("2026-1"). */
    val period: String = "",
    /** Respuestas del asistente (mensajes del modelo). */
    val answers: Long = 0L,
    /** Llamadas al modelo (peticiones HTTP). */
    val requests: Long = 0L,
    val tokensIn: Long = 0L,
    val tokensOut: Long = 0L,
    val cachedTokensIn: Long = 0L,
    /** Latencia global: histograma. */
    val latency: LatencyHistogram = LatencyHistogram(),
    /** Latencia por modelo (acotado a [MAX_KEYS] entradas). */
    val byModel: Map<String, LatencyHistogram> = emptyMap(),
    /** Latencia por tamaño de contexto. */
    val byContext: Map<String, LatencyHistogram> = emptyMap(),
    /** Latencia por herramienta. */
    val byTool: Map<String, LatencyHistogram> = emptyMap(),
    /** Suma de la latencia de servidor (ms). 0 si el servidor no la manda. */
    val serverMs: Long = 0L,
    /** Tokens de prefill y de decode (los que procesó la GPU). */
    val prefillTokens: Long = 0L,
    val decodeTokens: Long = 0L,
    /** Suma de milisegundos de prefill y de decode (para el throughput medio). */
    val prefillMs: Long = 0L,
    val decodeMs: Long = 0L,
    /** Consumo por alumno. */
    val perStudent: Map<String, StudentUsage> = emptyMap(),
    /** Registros con tokens estimados (para decir "esto es aproximado"). */
    val estimatedRecords: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** Fusiona dos ledgers. El merge de histogramas es exacto. */
    fun merge(other: UsageLedger): UsageLedger { ... }

    /** Agrega el uso de una respuesta. */
    fun add(record: UsageRecord, studentId: String): UsageLedger { ... }

    companion object {
        /** Tope de entradas por corte (modelos, herramientas). */
        const val MAX_KEYS = 12
    }
}
```

Reglas de `add` y `merge`:

- `byModel` / `byTool`: si se supera `MAX_KEYS`, las claves nuevas caen en
  `"otros"` (el modelo lo elige el alumno libremente: sin tope, el mapa crece
  sin límite y rompe el presupuesto de memoria del ledger).
- `byContext`: como mucho 4 claves (los 4 buckets de `ContextSize`).
- `merge` suma contadores, histogramas y contadores por alumno.

### 4.6 `usage` como recurso de primer clase

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Repositories.kt (extensión)

/**
 * Lectura del uso.
 *
 * Implementación por defecto: `MessageUsageRepository`, que lee los
 * `UsageRecord` de los mensajes (fuente de verdad) — no duplica datos.
 */
interface UsageRepository {
    suspend fun listForCourse(courseId: String): List<UsageRecord>
    suspend fun listForStudent(courseId: String, userId: String): List<UsageRecord>
    /** Ledger agregado de la cátedra (recalculado o persistido). */
    suspend fun ledgerForCourse(courseId: String): UsageLedger
}
```

Y el almacenamiento del ledger agregado:

```kotlin
/** Ledger persistido por cátedra (`config/usage.json`). */
interface UsageLedgerRepository {
    suspend fun get(courseId: String): UsageLedger?
    suspend fun save(ledger: UsageLedger)
    suspend fun merge(courseId: String, delta: UsageLedger): UsageLedger
}
```

Implementación `FileUsageLedgerRepository` sobre `JsonListStore` (el patrón de
`FileRepositories.kt`), con el archivo `usage.json` en `config/`.

> **Escala**: `usage.json` con una entrada por cátedra es suficiente para el
> milestone 1 (cientos de entradas). Si una institución multiplica el
> sistema, el mismo contrato se implementa sobre JSONL append-only o sobre el
> Kitoru-Server; la UI no cambia.

---

## 5. Coste

### 5.1 Qué se mide cuando el modelo lo aloja la institución

Si el modelo está en el Kitoru-Server de la institución, **no hay coste por
token**: el euro (o el peso) que la institución paga es el del equipo y el de
la electricidad. Por eso el panel del docente mide:

| Métrica | Para qué |
|---|---|
| **Tokens totales** (entrada / salida / cacheados) | El volumen de cómputo. |
| **Peticiones por alumno** | Quién consume y cuánto (para el presupuesto y para el reparto). |
| **Tokens por alumno** | Reparto y presupuesto. |
| **Horas-máquina / horas-GPU** | El costo real (§7). |
| **Coste estimado (USD)** | **Sólo** si hay `PriceTable` para el modelo. Con el modelo institucional, la tabla está vacía y el panel dice: *"El modelo lo aloja la institución: no hay coste por token. El costo real es de infraestructura (ver informe)."* |

El panel tiene que ser explícito sobre esto, porque es la diferencia entre
"la cátedra gastó $12" (mentira) y "la cátedra consumió 4,2 M tokens en 3.180
peticiones, que equivalen a ~9 h de GPU" (verdad).

### 5.2 El modelo de precios (capa opcional)

Archivo nuevo `core/src/commonMain/kotlin/kitoru/domain/CostModel.kt`:

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Precio de un modelo, en dólares por millón de tokens.
 *
 * Es una capa **opcional**: sirve cuando la cátedra usa una API comercial
 * (OpenAI, Anthropic, Gemini…) o cuando la institución quiere comparar su
 * servidor con el precio de la API (contrafactual del informe).
 */
@Serializable
data class ModelPrice(
    /** Id del modelo, tal como lo devuelve `/models`. */
    val model: String,
    /** USD por millón de tokens de entrada. */
    val inputPerMillion: Double = 0.0,
    /** USD por millón de tokens de salida. */
    val outputPerMillion: Double = 0.0,
    /** USD por millón de tokens de entrada servidos desde caché. */
    val cachedInputPerMillion: Double = 0.0,
    val currency: String = "USD",
)

/** Tabla de precios por modelo. */
@Serializable
data class PriceTable(
    val prices: List<ModelPrice> = emptyList(),
    val updatedAt: Long = 0L,
) {
    /** Precio del modelo, o null si la tabla no lo conoce. */
    fun priceOf(model: String): ModelPrice? = prices.firstOrNull { it.model == model }

    /** true si no hay ningún precio cargado (caso del modelo institucional). */
    val isEmpty: Boolean get() = prices.isEmpty()

    /**
     * Costo estimado de un registro de uso. null si el modelo no tiene precio.
     */
    fun estimate(usage: UsageRecord): Double? {
        val p = priceOf(usage.model) ?: return null
        val cached = usage.cachedTokensIn.coerceIn(0, usage.tokensIn)
        val fresh = usage.tokensIn - cached
        return fresh / 1_000_000.0 * p.inputPerMillion +
            cached / 1_000_000.0 * p.cachedInputPerMillion +
            usage.tokensOut / 1_000_000.0 * p.outputPerMillion
    }
}
```

La tabla se guarda en `config/prices.json` (`PriceTableRepository`), se edita a
mano (es un JSON chico) o se precarga con una tabla de referencia. **Nunca se
consulta una API de precios en runtime**: la app no depende de la red para
calcular.

### 5.3 Fórmulas de coste

```
coste(registro) = (tokensIn − cached) / 1e6 · precioEntrada
                 + cached / 1e6 · precioEntradaCacheada
                 + tokensOut / 1e6 · precioSalida

costeCurso      = Σ coste(registro)                    (por cátedra)
costeAlumno     = Σ coste(registro) del alumno
costePorAlumno  = costeCurso / alumnosActivos          (si se quiere un promedio)
```

Si `priceOf(model) == null` → el coste de ese registro es **desconocido**, no
cero. El agregador distingue `costoConocido` de `costoDesconocido` y la UI
muestra "costo parcial (2 de 3 modelos con precio)".

### 5.4 Reparto por alumno (para el presupuesto y el reparto de cuentas)

```
tokensAlumno    = Σ tokensFacturables de sus respuestas
peticionesAlumno= Σ rounds de sus respuestas
costoAlumno     = Σ coste de sus respuestas
```

El reparto se hace **por alumno y por clave**: si la institución usa una key
por alumno (`ApiKeyMode.PER_STUDENT`), el Kitoru-Server puede medir el mismo
número del lado del servidor, que es el que vale (el cliente puede mentir).

---

## 6. IA local del alumno: energía y disco (mención, no métrica)

Cuando el alumno chatea contra un modelo **en su propio dispositivo**
(`ModelPolicy.mode = LOCAL`), no hay tokens facturables ni presupuesto que
gastar. Lo único que "cuesta" es del alumno:

| Concepto | Valor típico | Qué hacemos |
|---|---|---|
| **Disco** | 2–9 GB por GGUF | Se muestra el tamaño del modelo en la configuración local (informativo). No entra en la analítica. |
| **Energía** | Portátil 15–30 W; escritorio con GPU 150–300 W | **No es métrica.** Una hora de chat en un portátil = ~0,03 kWh ≈ $0,005. Medirlo da más impresión que información. |
| **Batería / calor** | Ruido, desgaste | Fuera de alcance. |

Consecuencias de diseño:

1. **El presupuesto del alumno no se consume con IA local.** Un alumno con IA
   local puede chatear todo lo que quiera; su consumo del presupuesto de la
   cátedra es 0. Es lo correcto: el presupuesto es un tope de recursos de la
   institución.
2. **El panel del docente** separa explícitamente los dos mundos:

   ```
   Servidor de la cátedra        IA local del alumno
   ─────────────────────────      ─────────────────────
   3.180 peticiones               412 respuestas
   4,2 M tokens                   0 tokens (no consume presupuesto)
   p95 6,2 s                      p95 11,4 s (en el dispositivo del alumno)
   ```

   Y una nota explícita: *"Las conversaciones con IA local no consumieron
   recursos del servidor de la institución y no se facturan a la cátedra."*
3. **Los tokens de las respuestas locales** se estiman (el `llama-server` local
   puede devolver `usage` si se le pide con `include_usage`; si no, se estima)
   pero **no se suman** al presupuesto: se muestran en gris y con el rótulo
   "no computa para el presupuesto".
4. La latencia **sí** se mide y **sí** se muestra: es información útil para el
   docente ( "¿el modelo local responde tan bien como el del servidor?") y no
   cuesta nada.

---

## 7. Sostenibilidad de la institución: el informe para la dirección

Este es el **entregable real**: un informe que el docente (o el responsable de
TI) le lleva a la dirección de la facultad para justificar el servidor.

### 7.1 Las preguntas que el informe tiene que responder

1. ¿Cuánta carga puts el modelo de la institución?
2. ¿Cuántas **horas de cómputo** por cursada?
3. ¿Cuántos **alumnos a la vez** hay que sostener?
4. ¿Cuánto **margen** queda en el equipo que ya se compró?
5. ¿Cuánto **cuesta** (energía + amortización del equipo)?
6. ¿Cuánto **costaría** lo mismo con una API comercial? (el contrafactual)
7. ¿Cuánto **CO₂e** genera?

### 7.2 Fórmulas

Con `S` = servidor (perfil), `C` = conjunto de peticiones de la cursada:

```
requests        = Σ rounds

tokensIn        = Σ tokensIn
tokensOut       = Σ tokensOut
cachedIn        = Σ cachedTokensIn

── Latencia (servidor) ──────────────────────────────────────────────
serverMs        = Σ timings.serverMs            (si no hay timings: Σ latencyMs, marcado ESTIMADO)
machineHours    = serverMs / 3 600 000           [horas]
                  = "horas en las que el equipo estuvo ocupado"

── Horas-GPU (a partir del throughput medido) ────────────────────────
prefillHours    = prefillTokens / (prefillTokensPerSec · 3600)
decodeHours     = decodeTokens  / (decodeTokensPerSec  · 3600)
gpuHours        = prefillHours + decodeHours
                  (si no hay throughput medido: gpuHours ≈ tokensTotal / (tokensPorSec · 3600),
                   con el valor configurado en el perfil del servidor, marcado ESTIMADO)

── Energía ───────────────────────────────────────────────────────────
activeKwh       = gpuHours · watts / 1000
standbyKwh      = (díasDeCursada · 24 · hoursPerDay) · watts / 1000
kwh             = activeKwh + standbyKwh
co2eKg          = kwh · co2ePerKwh
energyCostUsd   = kwh · usdPerKwh

── Capacidad ─────────────────────────────────────────────────────────
peakConcurrency = máximo de solapamiento de los intervalos [at, at + serverMs)
capacitySlots   = S.slots
marginRatio     = 1 − peakConcurrency / capacitySlots        (>0 = hay margen)
slotsNeeded     = ceil(peakConcurrency / 0,7)                  (objetivo de uso 70 %)

── Dinero ────────────────────────────────────────────────────────────
serverCostPerYearUsd = hardwareCostUsd / amortizeYears + energyCostAnualUsd
termShareOfServer    = serverCostPerYearUsd · (díasDeCursada / 365)
costPerStudentUsd    = (termShareOfServer + energyDeLaCursadaUsd) / alumnos
equivalentApiCostUsd = Σ por modelo (tokensIn/1e6·precioIn + tokensOut/1e6·precioOut)
savingsUsd           = equivalentApiCostUsd − (termShareOfServer + energyDeLaCursadaUsd)
```

**Advertencia metodológica que va al informe**: el contrafactual
`equivalentApiCostUsd` compara cosas distintas (el costo *de cómputo* de la
cursada contra el costo *total anual* del servidor). Por eso el informe
presenta las dos cifras por separado y nunca las resta sin explicarlo. La
comparación justa es: **costo del servidor prorrateado a la cursada** vs.
**costo de la API para el mismo volumen**.

### 7.3 El perfil del servidor

```kotlin
/**
 * Perfil del equipo que aloja el modelo.
 *
 * Los valores por defecto son estimates honestos y editables: el informe
 * muestra los supuestos que usó, para que la dirección pueda discutirlos.
 */
@Serializable
data class ServerProfile(
    val id: String = "",
    /** "Servidor de la facultad - NVIDIA RTX 4070" */
    val label: String = "",
    /** Modelo principal que sirve este equipo. */
    val model: String = "",
    /** Slots de inferencia (paralelismo real del llama-server). */
    val slots: Int = 1,
    /** Tokens/s de prefill medidos (0 = usar el default). */
    val promptTokensPerSec: Double = 0.0,
    /** Tokens/s de decode medidos (0 = usar el default). */
    val decodeTokensPerSec: Double = 0.0,
    /** Consumo típico del equipo completo (W, GPU + CPU + RAM). */
    val watts: Int = 300,
    /** Horas por día que el equipo está encendido. */
    val hoursPerDay: Double = 24.0,
    /** Precio de la energía (USD/kWh). */
    val usdPerKwh: Double = 0.15,
    /** Factor de emisión (kg CO2e por kWh). */
    val co2ePerKwh: Double = 0.40,
    /** Costo del equipo (CAPEX, USD). */
    val hardwareCostUsd: Double = 0.0,
    /** Años de amortización del equipo. */
    val amortizeYears: Double = 4.0,
    /** Costo anual de mantenimiento (opcional). */
    val maintenanceUsdPerYear: Double = 0.0,
)
```

### 7.4 La estructura del informe

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/InstitutionReport.kt

@Serializable
data class InstitutionReport(
    val generatedAt: Long = 0L,
    /** "Facultad de Ingeniería" */
    val institution: String = "",
    /** "2026-1" */
    val term: String = "",
    /** CURSADAS incluidas en el informe. */
    val courses: List<CourseUsageReport> = emptyList(),
    /** Totales de todas las cátedra. */
    val totals: InstitutionTotals = InstitutionTotals(),
    /** Equipos que_estimaron los números. */
    val servers: List<ServerProfile> = emptyList(),
    /** Supuestos del cálculo (van textualizados en el informe). */
    val assumptions: List<String> = emptyList(),
    /** Advertencias: datos faltantes, estimaciones, meses incompletos. */
    val caveats: List<String> = emptyList(),
)
```

Secciones del documento exportado:

| # | Sección | Contenido |
|---|---|---|
| 1 | **Portada** | Institución, cursada, período, fecha, quién lo generó, versión del informe. |
| 2 | **Resumen ejecutivo** | 6 números + veredicto en una línea: *"La cursada consumió 41 h de GPU y 2,3 M tokens; el equipo actual alcanza con 3× de margen; el equivalente en API comercial sería USD 312."* |
| 3 | **Alcance y método** | Qué se midió, qué no, y los supuestos (con los números de §7.2 a la vista). |
| 4 | **Uso por cátedra** | Tabla: cátedra, alumnos, peticiones, tokens entrada/salida, costo estimado, horas-GPU. |
| 5 | **Calidad de servicio** | p50/p95/p99 por cátedra + % de respuestas por debajo del objetivo de servicio. |
| 6 | **Capacidad y concurrencia** | Concurrencia pico, horas-máquina, horas-GPU, slots necesarios, margen. |
| 7 | **Costos y sostenibilidad** | Energía (kWh), CO₂e, costo de energía, amortización del equipo, costo por alumno. |
| 8 | **Contrafactual: API comercial** | Costo equivalente con la tabla de precios + comparación. |
| 9 | **Riesgos y límites** | Datos estimados, cobertura de alumnos, modelo usado, etc. |
| 10 | **Anexo: definiciones** | El glosario de §1.3. |

### 7.5 Construcción: función pura, sin UI

```kotlin
object InstitutionReportBuilder {

    /**
     * Construye el informe. Función pura: mismos datos → mismo informe.
     *
     * @param courses uso agregado por cátedra.
     * @param servers perfiles de los equipos.
     * @param term cursada.
     */
    fun build(
        courses: List<CourseUsageReport>,
        servers: List<ServerProfile>,
        term: String,
        institution: String = "",
        generatedAt: Long = 0L,
        daysInTerm: Int = 120,
    ): InstitutionReport { ... }
}
```

Y el renderizado, también puro (para que el informe se pueda testear y
comparar):

```kotlin
object InstitutionReportRenderer {
    /** Markdown para pegar en un documento o mailing. */
    fun renderMarkdown(report: InstitutionReport): String

    /** HTML autocontenido (se imprime a PDF desde el navegador). */
    fun renderHtml(report: InstitutionReport): String
}
```

El Markdown se arma con `StringBuilder` — sin dependencias, sin plantillas.

### 7.6 La concurrencia pico (el número que define el hardware)

```kotlin
object Concurrency {
    /**
     * Máximo de peticiones simultáneas.
     *
     * Barrido de eventos: cada petición abre un intervalo
     * [at, at + durationMs); en un empate, el cierre va antes que la apertura
     * (dos peticiones que terminan y arrancan en el mismo milisegundo no
     * están solapadas).
     */
    fun peak(records: List<UsageRecord>, durationOf: (UsageRecord) -> Long): Int {
        val events = ArrayList<Pair<Long, Int>>(records.size * 2)
        for (r in records) {
            val d = durationOf(r)
            if (d <= 0L) continue
            events += r.at to 1
            events += (r.at + d) to -1
        }
        events.sortWith(compareBy({ it.first }, { it.second })) // -1 antes que +1
        var current = 0
        var max = 0
        for ((_, delta) in events) {
            current += delta
            if (current > max) max = current
        }
        return max
    }
}
```

Con `durationOf = { it.serverMs.takeIf { m -> m > 0 } ?: it.latencyMs }`.

> Ojo: si el servidor no manda `timings`, la duración es la latencia **del
> cliente** (incluye red) → la concurrencia pico queda **sobrestimada**. El
> informe lo dice en las advertencias ("concurrencia estimada sobre latencia
> de cliente: es un techo, no un valor exacto").

### 7.7 El contenido del informe (ejemplo de salida)

> ## Resumen ejecutivo
>
> | Indicador | Valor |
> |---|---|
> | Cursadas analizadas | 6 |
> | Alumnos activos | 412 |
> | Peticiones al modelo | 38.204 |
> | Tokens (entrada / salida) | 61,2 M / 3,4 M |
> | Latencia p50 / p95 / p99 | 1,4 s / 6,2 s / 14,8 s |
> | Horas-máquina (servidor) | 96,4 h |
> | Horas-GPU estimadas | 41,7 h |
> | Concurrencia pico | 23 de 32 slots (margen 28 %) |
> | Energía de la cursada | 34,1 kWh (≈ 13,6 kg CO₂e) |
> | Costo del servidor (cursada prorrateada) | USD 118 |
> | Costo por alumno | USD 0,29 |
> | **Equivalente en API comercial** | **USD 312** |

Los números de arriba son **de ejemplo**: lo que se entrega es el
*procedimiento*, no la cifra.

---

## 8. Presupuesto por alumno y por cátedra

### 8.1 Modelo

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Budget.kt

/**
 * Qué pasa cuando un alumno agota su presupuesto.
 */
@Serializable
enum class BudgetPolicy(val label: String, val description: String) {
    /** Se avisa y se sigue igual. */
    AVISAR(
        "Sólo avisar",
        "El alumno sigue usando el modelo. Se le avisa y el docente ve el aviso.",
    ),
    /**
     * Las respuestas pasan a un modelo más chico con un límite de tokens
     * menor. El alumno puede seguir preguntando.
     */
    DEGRADAR(
        "Degradar",
        "Las respuestas usan un modelo más liviano y con menos tokens por respuesta.",
    ),
    /** No se aceptan más mensajes hasta que el docente amplíe el presupuesto. */
    BLOQUEAR(
        "Bloquear",
        "No se aceptan más mensajes hasta que el docente amplíe el presupuesto.",
    ),
}

/** Nivel del presupuesto frente al consumo. */
@Serializable
enum class BudgetLevel { OK, AVISO, AGOTADO }

/**
 * Presupuesto de una cátedra o de un alumno.
 *
 * Hay dos alcances en el mismo tipo:
 *  - `studentId == ""` → presupuesto de la cátedra (tope global).
 *  - `studentId != ""` → presupuesto individual.
 */
@Serializable
data class Budget(
    val id: String,
    val courseId: String,
    /** "" = presupuesto de la cátedra. */
    val studentId: String = "",
    /** Cursada ("2026-1"). */
    val period: String = "",
    /** Tope de tokens facturables de la cátedra. 0 = sin tope. */
    val tokensTotal: Int = 0,
    /** Tope de tokens facturables por alumno. 0 = sin tope. */
    val tokensPerStudent: Int = 0,
    /** Tope monetario en USD (sólo si la cátedra usa una API comercial). 0 = sin tope. */
    val costUsd: Double = 0.0,
    /** Umbral de aviso (fracción del tope). */
    val warnAt: Double = 0.8,
    val policy: BudgetPolicy = BudgetPolicy.DEGRADAR,
    /** Modelo al que se degrada. Vacío = el mismo modelo con menos tokens. */
    val fallbackModel: String = "",
    /** Límite de tokens por respuesta al degradar. */
    val degradedMaxTokens: Int = 512,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)
```

Ejemplo del enunciado: **100 k tokens por alumno por cursada**.

```json
{
  "id": "bud-ia2026-1",
  "courseId": "IA-2026-1",
  "studentId": "",
  "period": "2026-1",
  "tokensTotal": 2000000,
  "tokensPerStudent": 100000,
  "costUsd": 0.0,
  "warnAt": 0.8,
  "policy": "DEGRADAR",
  "fallbackModel": "phi4-mini",
  "degradedMaxTokens": 512
}
```

### 8.2 Cálculo del consumo y del estado

```kotlin
@Serializable
data class BudgetStatus(
    val budgetId: String = "",
    val studentId: String = "",
    /** Tope por alumno (0 = sin tope). */
    val studentLimit: Int = 0,
    /** Tope de la cátedra (0 = sin tope). */
    val courseLimit: Int = 0,
    /** Tokens facturables del alumno. */
    val used: Int = 0,
    /** Tokens facturables de la cátedra. */
    val courseUsed: Int = 0,
    val level: BudgetLevel = BudgetLevel.OK,
    val policy: BudgetPolicy = BudgetPolicy.DEGRADAR,
    val fallbackModel: String = "",
    val degradedMaxTokens: Int = 512,
) {
    /** Tokens que le quedan al alumno (el menor de los dos topes). */
    val remaining: Int
        get() = minOf(
            if (studentLimit > 0) studentLimit - used else Int.MAX_VALUE,
            if (courseLimit > 0) courseLimit - courseUsed else Int.MAX_VALUE,
        ).coerceAtLeast(0)

    /** Fracción consumida del tope más específico. */
    val ratio: Double
        get() = when {
            studentLimit > 0 -> (used.toDouble() / studentLimit).coerceIn(0.0, 1.0)
            courseLimit > 0 -> (courseUsed.toDouble() / courseLimit).coerceIn(0.0, 1.0)
            else -> 0.0
        }
}
```

El evaluador (función pura, testeable):

```kotlin
object BudgetEvaluator {

    /**
     * Estado del presupuesto de un alumno.
     *
     * @param budget presupuesto de la cátedra (o null si no hay).
     * @param courseUsed tokens facturables de toda la cátedra.
     * @param studentUsed tokens facturables del alumno.
     */
    fun status(budget: Budget?, courseUsed: Int, studentUsed: Int): BudgetStatus {
        if (budget == null) return BudgetStatus()
        val remaining = minOf(
            if (budget.tokensPerStudent > 0) budget.tokensPerStudent - studentUsed else Int.MAX_VALUE,
            if (budget.tokensTotal > 0) budget.tokensTotal - courseUsed else Int.MAX_VALUE,
        )
        val level = when {
            remaining <= 0 -> BudgetLevel.AGOTADO
            ratioOf(budget, courseUsed, studentUsed) >= budget.warnAt -> BudgetLevel.AVISO
            else -> BudgetLevel.OK
        }
        return BudgetStatus(
            budgetId = budget.id,
            studentId = budget.studentId,
            studentLimit = budget.tokensPerStudent,
            courseLimit = budget.tokensTotal,
            used = studentUsed,
            courseUsed = courseUsed,
            level = level,
            policy = budget.policy,
            fallbackModel = budget.fallbackModel,
            degradedMaxTokens = budget.degradedMaxTokens,
        )
    }

    /** Qué hacer con ese estado: permitir, degradar o bloquear. */
    fun decide(status: BudgetStatus): BudgetDecision { ... }
}
```

La decisión:

```kotlin
@Serializable
data class BudgetDecision(
    /** ¿Se puede enviar el mensaje? */
    val allowed: Boolean = true,
    /** Hay que cambiar de modelo y/o limitar los tokens. */
    val degraded: Boolean = false,
    /** Modelo efectivo (vacío = el de la cátedra). */
    val effectiveModel: String = "",
    /** Límite de tokens por respuesta (0 = sin cambio). */
    val effectiveMaxTokens: Int = 0,
    /** Mensaje para el alumno (español). Vacío = no hay nada que avisar. */
    val studentMessage: String = "",
    /** Aviso para el docente. */
    val teacherAlert: String = "",
)
```

### 8.3 Qué pasa exactamente al agotarlo

| Política | Qué hace el cliente | Qué hace el servidor | Mensaje al alumno |
|---|---|---|---|
| **`AVISAR`** | Nada: sigue igual. Muestra un banner permanente con el saldo. | Nada (sólo avisa). | *"Te quedan 12.400 tokens de tu presupuesto de la cursada (100.000)."* |
| **`DEGRADAR`** (default) | Cambia `model` a `fallbackModel` (si hay), baja `maxTokens` a `degradedMaxTokens` y **desactiva las herramientas** (cada tool es una petición extra). | Idem: sirve con el modelo de reserva y `max_tokens` acotado. | *"Alcanzaste el presupuesto de la cursada. Desde ahora tus respuestas usan el modelo «phi4-mini» con un máximo de 512 tokens por respuesta."* |
| **`BLOQUEAR`** | No envía; muestra el cartel y un botón "Pedir ampliación". | Responde `429` con `{"error":"budget_exhausted", ...}` → el cliente lo mapea a `LlmErrorCode.SIN_CUOTA` (ya existe). | *"Alcanzaste el presupuesto de la cursada (100.000 tokens). Pedile al profesor una ampliación para seguir chateando."* |

Detalles de diseño:

- **Degradar es el default** porque bloquear corta el flujo pedagógico en el
  momento exacto en que el alumno necesita la herramienta (el parcial de la
  semana, la duda de último momento). Degradar mantiene el servicio con menor
  calidad.
- **El aviso (80 %) es obligatorio en las tres políticas**: el `warnAt` es el
  umbral por defecto y el panel del docente lo muestra en ámbar.
- **La degradación se aplica en el servidor** (que es quien tiene el modelo):
  el Kitoru-Server recibe la petición, ve que el alumno agotó su quota y
  sirve con el modelo de reserva. El cliente **también** degrada (para que el
  alumno lo vea antes de gastar) — pero la norma es la del servidor.
- **Ampliación manual**: el docente puede subir el tope de un alumno
  (`BudgetOverride`) sin tocar el presupuesto de la cátedra:

```kotlin
/** Ampliación puntual que el docente le da a un alumno. */
@Serializable
data class BudgetOverride(
    val id: String,
    val courseId: String,
    val studentId: String,
    /** Tokens extra que se le suman al tope. */
    val extraTokens: Int = 0,
    /** Epoch ms hasta el que vale. 0 = sin vencimiento. */
    val expiresAt: Long = 0L,
    val note: String = "",
    val grantedBy: String = "",
    val createdAt: Long = 0L,
)
```

- **El presupuesto con IA local no aplica** (§6): el alumno con IA local no
  consume presupuesto de la institución. La UI del alumno muestra el saldo
  sólo cuando está en modo `REMOTE`.

### 8.4 Dónde se aplica (cliente y servidor)

**Cliente** (UX, en `:ui`): antes de enviar, calcula `BudgetStatus` con el
ledger local y:

1. Muestra el banner si `level == AVISO`.
2. Aplica la degradación si `level == AGOTADO && policy == DEGRADAR`.
3. Bloquea el envío si `level == AGOTADO && policy == BLOQUEAR`.

**Servidor** (norma, en Kitoru-Server): mide por `(key, X-Kitoru-Student)` y
responde:

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json

{
  "error": "budget_exhausted",
  "scope": "student",
  "limit": 100000,
  "used": 104213,
  "policy": "DEGRADAR",
  "fallback_model": "phi4-mini",
  "degraded_max_tokens": 512
}
```

El cliente lo traduce: si el cuerpo trae `error == "budget_exhausted"`, la UI
muestra el mensaje de presupuesto (no el error genérico de cuota).

### 8.5 El contrato de sincronización del uso

Para que el docente y la institución tengan los números **reales** (y no los
estimados del cliente), el Kitoru-Server manda el uso junto con los chats:

```http
POST /api/usage/sync
Content-Type: application/json

{
  "courseId": "IA-2026-1",
  "period": "2026-1",
  "records": [
    {
      "studentId": "alu_01H...",
      "model": "qwen3-8b",
      "latencyMs": 2410,
      "ttftMs": 320,
      "tokensIn": 812,
      "tokensOut": 47,
      "cachedTokensIn": 0,
      "rounds": 1,
      "tools": [],
      "serverMs": 2190,
      "promptTokensPerSec": 3859.3,
      "decodeTokensPerSec": 47.9,
      "source": "REAL",
      "at": 1767225600000
    }
  ]
}
```

Y para la institución, lo que viaja es el **ledger** (mergeable, sin texto):

```http
POST /api/usage/ledger
{ "courseId": "...", "period": "...", "ledger": { ... } }
```

El receptor responde con el ledger ya fusionado (el merge es exacto, así que
el orden de llegada no importa).

---

## 9. La vista

### 9.1 Panel "Uso y coste" (docente)

Ubicación: `Docente → Cátedra → Analítica → pestaña "Uso y coste"`.
(`:ui`, todavía no construido; aquí se define el contrato de datos y la
estructura.)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Uso y coste · IA-2026-1 · 2026-1                                             │
│ Tokens reales del servidor · 38.204 peticiones · 6 cátedras                  │
├──────────────────────────────────────────────────────────────────────────────┤
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌───────────┐   │
│  │ Peticiones │ │ Tokens in  │ │ Tokens out │ │ Coste      │ │ Alumnos   │   │
│  │ 38.204     │ │ 61,2 M     │ │ 3,4 M      │ │ Alojado por │ │ sobre     │  │
│  │            │ │            │ │            │ │ la instit.  │ │ presupuesto│ │
│  └────────────┘ └────────────┘ └────────────┘ └────────────┘ └───────────┘   │
├──────────────────────────────────────────────────────────────────────────────┤
│ LATENCIA                                                                     │
│  p50 1,4 s   p95 6,2 s   p99 14,8 s   media 4,2 s   máx 62,1 s   n = 38.204  │
│  [histograma de barras, eje X logarítmico, 128 buckets]                      │
│                                                                              │
│  Por modelo            │ n      │ p50    │ p95    │ p99    │ media           │
│  qwen3-8b              │ 31.002 │ 1,3 s  │ 5,8 s  │ 13,9 s │ 3,9 s           │
│  phi4-mini             │  7.202 │ 0,5 s  │ 1,1 s  │ 2,0 s  │ 0,6 s           │
│                                                                              │
│  Por contexto          │ n      │ p50    │ p95    │ tokens/resp              │
│  Corta (≤512)          │  9.115 │ 0,6 s  │ 1,2 s  │ 214                      │
│  Media (≤2 k)          │ 24.310 │ 1,5 s  │ 6,4 s  │ 903                      │
│  Larga (≤8 k)          │  4.779 │ 4,1 s  │ 11,2 s │ 2.180                    │
├──────────────────────────────────────────────────────────────────────────────┤
│ HERRAMIENTAS                                                                 │
│  web_search   1.204 usos · 2,9 s por llamada (p95 8,1 s) · 3 fallos          │
│  create_file     318 usos · 0,1 s por llamada (p95 0,3 s) · 0 fallos         │
├──────────────────────────────────────────────────────────────────────────────┤
│ PRESUPUESTO                                                                  │
│  Cátedra: 2.000.000 tokens · usados 1.412.000 (71 %) · sin alumnos agotados  │
│  [tabla por alumno: nombre · tokens · % del tope · estado]                   │
│  [Configurar presupuesto]                                                    │
├──────────────────────────────────────────────────────────────────────────────┤
│ [ Generar informe para la dirección ]   [ Exportar CSV ]                     │
└──────────────────────────────────────────────────────────────────────────────┘
```

Reglas de la vista:

- **La latencia se muestra con percentiles, y la media al lado** (para que se
  vea el efecto de la cola).
- **Cada fila muestra su `n`**: con 3 muestras, la fila se marca "muestra
  chica".
- **El coste dice de dónde sale**: "alojado por la institución" (sin precio) o
  "USD 312 (precio de referencia de gpt-4o-mini, 2026-01)".
- **La IA local va en una sección aparte, al final**, con el rótulo "no
  computa para el presupuesto".
- El p95/p99 se comparan contra el **objetivo de servicio** de la cátedra
  (`Course.latencyTargetMs`, opcional) y se marca en verde/ámbar.

### 9.2 Informe para la dirección de la facultad

Ubicación: `Docente → Analítica → "Informe para la dirección"` (o desde la
herramienta de administración del Kitoru-Server).

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Informe institucional · 2026-1                                               │
│ Destinatario: Dirección de la Facultad de Ingeniería                         │
│                                                                              │
│ [Período: 2026-1 ▾]  [Cátedras: 6 ▾]  [Servidor: Servidor de la facultad ▾]  │
│                                                                              │
│ ┌── Vista previa ──────────────────────────────────────────────────────────┐ │
│ │  ## Resumen ejecutivo                                                 │    │
│ │  | Indicador | Valor |                                               │     │
│ │  | ... | ... |                                                       │     │
│ └────────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  [ Descargar Markdown ]  [ Descargar HTML ]  [ Imprimir / PDF ]              │
└──────────────────────────────────────────────────────────────────────────────┘
```

- La vista previa es el Markdown renderizado (mismo contenido que el archivo
  exportado: lo que se ve es lo que se entrega).
- El PDF sale de la impresión del navegador sobre el HTML (sin dependencias
  de PDF en la app).
- Todo el cálculo es la función pura de §7.5: la UI no calcula nada.

### 9.3 Lo que ve el alumno

En el chat del alumno, arriba:

```
┌────────────────────────────────────────────────────────┐
│ ⚠ Te quedan 12.400 tokens de los 100.000 de tu cátedra │
│   [Ver detalle]                                        │
└────────────────────────────────────────────────────────┘
```

y cuando se agota (política `DEGRADAR`):

```
┌──────────────────────────────────────────────────────────────┐
│i Alcanzaste el presupuesto de la cursada. Las respuestas     │
│  ahora usan «phi4-mini» con hasta 512 tokens por respuesta.  │
│  [Pedir ampliación al profesor]                              │
└──────────────────────────────────────────────────────────────┘
```

El alumno **nunca** ve números de coste en dólares de la institución (no son
suyos); ve su consumo en tokens, que es lo que puede gastar.

---

## 10. Cambios en el modelo

### 10.1 Campos nuevos y modificados

| Tipo | Archivo | Cambio |
|---|---|---|
| `ChatMessage` | `core/.../domain/Chat.kt` | **+** `usage: UsageRecord? = null`. `latencyMs`, `tokensIn`, `tokensOut` **se mantienen** (son el espejo legacy: los mensajes viejos no tienen `usage`). |
| `UsageRecord` | **nuevo** `core/.../domain/Usage.kt` | El registro de uso de una respuesta. |
| `UsageSource` | **nuevo** `core/.../domain/Usage.kt` | `REAL` / `ESTIMATED`. |
| `LatencyHistogram` | **nuevo** `core/.../domain/LatencyHistogram.kt` | El sketch de percentiles. |
| `LatencySummary` | **nuevo** `core/.../domain/Usage.kt` | p50/p95/p99/media/min/max de un conjunto. |
| `TokenEstimator` | **nuevo** `core/.../domain/TokenEstimator.kt` | El estimador portable. |
| `Budget`, `BudgetPolicy`, `BudgetLevel`, `BudgetStatus`, `BudgetDecision`, `BudgetOverride` | **nuevo** `core/.../domain/Budget.kt` | El modelo de presupuesto. |
| `BudgetEvaluator` | **nuevo** `core/.../domain/Budget.kt` | La evaluación pura. |
| `ModelPrice`, `PriceTable` | **nuevo** `core/.../domain/CostModel.kt` | La capa de coste. |
| `UsageLedger` | **nuevo** `core/.../domain/UsageLedger.kt` | El agregado mergeable. |
| `InstitutionReport`, `InstitutionTotals`, `CourseUsageReport`, `ServerProfile`, `InstitutionReportBuilder`, `InstitutionReportRenderer`, `Concurrency` | **nuevo** `core/.../domain/InstitutionReport.kt` | El informe y sus fórmulas. |
| `StudentAnalytics` | `core/.../domain/Analytics.kt` | **+** `requests`, `tokensCached`, `latencyP95Ms`, `budget: BudgetStatus?`. |
| `CourseAnalytics` | `core/.../domain/Analytics.kt` | **+** `requests`, `tokensIn`, `tokensOut`, `tokensCached`, `latency: LatencySummary`, `byModel: List<ModelUsage>`, `byContext: List<ContextUsage>`, `byTool: List<ToolUsage>`, `costUsd: Double?`, `budgetStatus: BudgetStatus?`, `overBudgetStudents: List<String>`. |
| `Course` | `core/.../domain/Course.kt` | **+** `latencyTargetMs: Long = 0` (objetivo de servicio; 0 = sin objetivo). |
| `LlmChunk` | `llm-api/.../api/Tools.kt` | **+** variante `Usage`. |
| `LlmUsage`, `LlmTimings` | **nuevo** `llm-api/.../api/LlmUsage.kt` | El `usage` del protocolo. |
| `GatewayConfig` | `llm-api/.../config/GatewayConfig.kt` | **+** `includeUsage: Boolean = true`. |
| `AgentEvent`, `AgentAnswer` | `backend/.../agent/AgentLoop.kt` | **+** `Usage`, `usage`, `rounds`. |

### 10.2 `UsageRecord` (el tipo central)

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Usage.kt

/**
 * De dónde salen los tokens de un registro de uso.
 */
@Serializable
enum class UsageSource(val label: String) {
    /** El servidor los informó (`usage` en la respuesta). */
    REAL("Real"),

    /** Se calcularon con el estimador portable. */
    ESTIMATED("Estimado"),
}

/**
 * Uso de una respuesta del modelo: tokens, latencia y contexto.
 *
 * Viaja dentro del [ChatMessage]. Es la fuente de verdad del consumo; el
 * [UsageLedger] es su agregado.
 */
@Serializable
data class UsageRecord(
    /** Id del modelo efectivo que generó la respuesta. */
    val model: String = "",
    /** Latencia total del cliente (ms): del envío al último token. */
    val latencyMs: Long = 0L,
    /** Tiempo hasta el primer token (ms). 0 si no se midió. */
    val ttftMs: Long = 0L,
    /** Tokens de entrada (prompt: system + historial + RAG + pregunta). */
    val tokensIn: Int = 0,
    /** Tokens de salida. */
    val tokensOut: Int = 0,
    /** Tokens de entrada servidos desde caché. */
    val cachedTokensIn: Int = 0,
    /** Tokens de razonamiento, si el proveedor los separa. */
    val reasoningTokens: Int = 0,
    /** Tokens de la pregunta del alumno (sin el contexto RAG). */
    val questionTokens: Int = 0,
    /** Llamadas al modelo que hizo esta respuesta (rondas del bucle). */
    val rounds: Int = 1,
    /** Herramientas que se usaron. */
    val tools: List<String> = emptyList(),
    /** Tiempo de cómputo del servidor (ms), si vino en `timings`. */
    val serverMs: Long = 0L,
    /** Tokens/s de prefill medidos en el servidor (0 = desconocido). */
    val promptTokensPerSec: Double = 0.0,
    /** Tokens/s de decode medidos en el servidor (0 = desconocido). */
    val decodeTokensPerSec: Double = 0.0,
    /** De dónde salen los tokens. */
    val source: UsageSource = UsageSource.ESTIMATED,
    /** Epoch ms del request. */
    val at: Long = 0L,
) {
    /** Tokens totales. */
    val totalTokens: Int get() = tokensIn + tokensOut

    /** Tokens que consumen presupuesto (la caché no se cobra). */
    val billableTokens: Int get() = (tokensIn - cachedTokensIn).coerceAtLeast(0) + tokensOut

    /** Latencia de servidor si se conoce; si no, la del cliente. */
    val effectiveServerMs: Long get() = if (serverMs > 0L) serverMs else latencyMs
}

/** Resumen de latencia de un conjunto de respuestas. */
@Serializable
data class LatencySummary(
    val count: Long = 0L,
    val meanMs: Long = 0L,
    val p50Ms: Long = 0L,
    val p95Ms: Long = 0L,
    val p99Ms: Long = 0L,
    val minMs: Long = 0L,
    val maxMs: Long = 0L,
) {
    companion object {
        /** Resume un histograma. */
        fun of(h: LatencyHistogram): LatencySummary = LatencySummary(
            count = h.count,
            meanMs = h.meanMs,
            p50Ms = h.p50Ms,
            p95Ms = h.p95Ms,
            p99Ms = h.p99Ms,
            minMs = h.minMs,
            maxMs = h.maxMs,
        )
    }
}
```

### 10.3 Serialización y compatibilidad

- Todos los campos nuevos tienen **valor por defecto** → los JSON ya guardados
  (`messages.json`, `courses.json`) siguen deserializando sin migración.
  `KitoruJson` ya tiene `ignoreUnknownKeys = true` e `encodeDefaults = true`.
- Los mensajes **anteriores** al cambio no tienen `usage`: el analizador cae
  al campo legacy:

```kotlin
/**
 * El registro de uso de un mensaje.
 *
 * Los mensajes guardados antes del cambio no tienen `usage`: en ese caso se
 * arma uno estimado a partir de los campos legacy (`latencyMs`, `tokensIn`,
 * `tokensOut`), que ya eran estimados.
 */
fun ChatMessage.usageOrLegacy(): UsageRecord = usage ?: UsageRecord(
    model = "",
    latencyMs = latencyMs,
    tokensIn = tokensIn,
    tokensOut = tokensOut,
    source = UsageSource.ESTIMATED,
    at = createdAt,
)
```

- Archivos nuevos en `config/`:
  - `usage.json` → `List<UsageLedger>` (un ledger por cátedra).
  - `budgets.json` → `List<Budget>`.
  - `budget-overrides.json` → `List<BudgetOverride>`.
  - `prices.json` → `PriceTable`.
  - `servers.json` → `List<ServerProfile>` (perfiles de los equipos).

### 10.4 Cómo se arma el `UsageRecord` en el chat (la pieza que cierra el circuito)

El servicio de chat (la capa de app, todavía no construida) hace:

```kotlin
val t0 = nowMillis()
var ttft = 0L
val answer = agentLoop.run(prepared.messages, ctx) { event ->
    when (event) {
        is AgentEvent.Text -> if (ttft == 0L) ttft = nowMillis() - t0
        is AgentEvent.Usage -> { /* el uso llega por evento o por answer.usage */ }
        else -> Unit
    }
}
val t1 = nowMillis()

val usage = answer.usage?.toRecord(
    model = effectiveModel.modelName,
    latencyMs = t1 - t0,
    ttftMs = ttft,
    questionTokens = TokenEstimator.estimate(question),
    tools = answer.toolCalls.map { it.name },
    rounds = answer.rounds,
    at = t0,
) ?: UsageRecord(   // fallback: el servidor no informó
    model = effectiveModel.modelName,
    latencyMs = t1 - t0,
    ttftMs = ttft,
    tokensIn = TokenEstimator.estimateConversation(promptPairs),
    tokensOut = TokenEstimator.estimate(answer.text),
    questionTokens = TokenEstimator.estimate(question),
    tools = answer.toolCalls.map { it.name },
    rounds = answer.rounds,
    source = UsageSource.ESTIMATED,
    at = t0,
)

val message = ChatMessage(
    id = newId(),
    threadId = thread.id,
    role = "assistant",
    text = answer.text,
    createdAt = t1,
    sources = prepared.sources.map { it.label },
    citation = citationStats,
    toolCalls = answer.toolCalls.map { ... },
    latencyMs = usage.latencyMs,     // espejo legacy
    tokensIn = usage.tokensIn,        // espejo legacy
    tokensOut = usage.tokensOut,      // espejo legacy
    usage = usage,                    // ← el registro completo
)
```

Y en paralelo se actualiza el ledger de la cátedra:

```kotlin
val ledger = usageLedgerRepository.merge(course.id, UsageLedger().add(usage, userId))
```

---

## 11. Plan de implementación (orden y archivos)

### Fase 1 — el gateway trae el `usage` (bloqueante para todo lo demás)

| # | Archivo | Qué |
|---|---|---|
| 1.1 | `llm-api/.../api/LlmUsage.kt` | **Nuevo**: `LlmUsage`, `LlmTimings`, `toRecord()`. |
| 1.2 | `llm-api/.../api/Tools.kt` | **+** `LlmChunk.Usage`. |
| 1.3 | `llm-api/.../api/LlmGateway.kt` | Pedir `stream_options`, parsear `usage` y `timings`, fallback por 400, `chatWithUsage`. |
| 1.4 | `llm-api/.../config/GatewayConfig.kt` | **+** `includeUsage`. |
| 1.5 | `backend/.../agent/AgentLoop.kt` | `AgentEvent.Usage`, `AgentAnswer.usage/rounds`, acumular por ronda. |
| 1.6 | `llm-api/src/desktopTest/.../GatewayUsageTest.kt` | **Nuevo**: con `MockEngine` de Ktor, verificar que se pide `stream_options`, que se lee `usage` del chunk con `choices: []`, que se lee `timings`, y que el fallback por 400 funciona. |

> Agregar al catálogo de versiones: `ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }`
> y a `llm-api`'s `commonTest.dependencies`.

### Fase 2 — el dominio del uso

| # | Archivo | Qué |
|---|---|---|
| 2.1 | `core/.../domain/LatencyHistogram.kt` | **Nuevo**: el sketch (§3.3-3.5). |
| 2.2 | `core/.../domain/TokenEstimator.kt` | **Nuevo**: el estimador (§2.4). |
| 2.3 | `core/.../domain/Usage.kt` | **Nuevo**: `UsageRecord`, `UsageSource`, `LatencySummary`. |
| 2.4 | `core/.../domain/UsageLedger.kt` | **Nuevo**: el agregado mergeable. |
| 2.5 | `core/.../domain/CostModel.kt` | **Nuevo**: `ModelPrice`, `PriceTable`. |
| 2.6 | `core/.../domain/Repositories.kt` | **+** `UsageRepository`, `UsageLedgerRepository`, `BudgetRepository`, `PriceTableRepository`, `ServerProfileRepository`. |
| 2.7 | `core/.../domain/FileRepositories.kt` | **+** implementaciones `File*` (mismo patrón). |

### Fase 3 — presupuesto

| # | Archivo | Qué |
|---|---|---|
| 3.1 | `core/.../domain/Budget.kt` | **Nuevo**: tipos + `BudgetEvaluator`. |
| 3.2 | `core/.../domain/Analytics.kt` | **+** campos de latencia, tokens y presupuesto en `StudentAnalytics` / `CourseAnalytics`. |
| 3.3 | `core/.../domain/AnalyticsAnalyzer.kt` | Construir histogramas y breakdowns (§3.6), aplicar el presupuesto. |

### Fase 4 — el informe institucional

| # | Archivo | Qué |
|---|---|---|
| 4.1 | `core/.../domain/InstitutionReport.kt` | **Nuevo**: tipos, `InstitutionReportBuilder`, `Concurrency`, `InstitutionReportRenderer`. |

### Fase 5 — la vista (`:ui`, todavía no construido)

| # | Componente | Qué |
|---|---|---|
| 5.1 | `ui/.../analytics/UsagePanel.kt` | El panel "Uso y coste" del docente. |
| 5.2 | `ui/.../analytics/LatencyHistogramChart.kt` | El gráfico de barras (eje log). |
| 5.3 | `ui/.../analytics/BudgetSection.kt` | La configuración y el estado de los presupuestos. |
| 5.4 | `ui/.../analytics/InstitutionReportScreen.kt` | La vista previa y la exportación. |
| 5.5 | `ui/.../chat/BudgetBanner.kt` | El banner del alumno. |

---

## 12. Tests

Todos en `commonTest` (o `desktopTest`) con `kotlin.test` (los que necesitan
JUnit 5, en `desktopTest`, como en `:backend`).

| Test | Qué verifica |
|---|---|
| `LatencyHistogramTest` | (a) El percentil estimado está dentro de ±10 % del exacto sobre 1 000 muestras log-normales sintéticas. (b) `merge` de dos histogramas es **igual** a construir el histograma con todas las muestras (merge exacto). (c) `bucketOf` es monótono y `edge(i) ≤ ms < edge(i+1)`. (d) El último bucket absorbe todo lo que pase de 59,9 s. (e) `percentile` con `count = 0` devuelve 0. |
| `TokenEstimatorTest` | (a) Un texto en español de 400 caracteres da un valor en el rango esperado (±25 %). (b) El texto vacío da 0. (c) Un texto con emoji cuenta 2 tokens por emoji. (d) Un texto con CJK no divide por 4. (e) `estimateConversation` suma el envoltorio del chat. |
| `BudgetEvaluatorTest` | (a) Sin presupuesto → `OK` y `allowed`. (b) Al 79 % → `OK`; al 81 % → `AVISO`. (c) Al 100 % → `AGOTADO` + `allowed` con `DEGRADAR`. (d) Al 100 % con `BLOQUEAR` → `allowed = false`. (e) El tope de la cátedra agota a todos aunque el alumno esté por debajo del suyo. (f) `remaining` toma el menor de los dos topes. |
| `CostModelTest` | (a) Un modelo sin precio → `null` (no 0). (b) El cálculo con caché da el precio de caché, no el de entrada. (c) `1 000 000` tokens de entrada a `3,00 $/M` dan `3,00`. |
| `UsageLedgerTest` | (a) `add` actualiza los cuatro cortes. (b) `merge` suma contadores y histogramas. (c) Pasado `MAX_KEYS` modelos, las claves nuevas caen en `"otros"`. (d) `byContext` clasifica bien los 4 buckets. |
| `InstitutionReportTest` | (a) `build` con datos vacíos no revienta. (b) `peak` calcula la concurrencia correcta sobre intervalos conocidos. (c) `gpuHours` con throughput conocido da el valor esperado. (d) El renderer produce un Markdown con las secciones del §7.4 (y el HTML equivalente). |
| `GatewayUsageTest` (`:llm-api`) | (a) El body lleva `stream_options.include_usage`. (b) Se lee el `usage` del chunk con `choices: []`. (c) Se lee `timings`. (d) Un 400 con "unrecognized request argument" reintenta sin `stream_options` y **no** vuelve a mandarlo. (e) Un `usage` ausente no emite `LlmChunk.Usage` (el que llama estima). |

---

## 13. Riesgos y límites conocidos

| # | Riesgo | Mitigación |
|---|---|---|
| R1 | El endpoint rechaza `stream_options` (400). | Fallback automático (§2.3.3 d) + `includeUsage` configurable + el registro queda `ESTIMATED`. |
| R2 | Un proveedor manda `usage` con campos que no esperamos. | El parser es tolerante (todo tiene default 0) y nunca lanza por un `usage` raro. |
| R3 | El p99 con pocas muestras es engañoso. | La UI muestra el `n` y marca "muestra chica" cuando `n < 100`. |
| R4 | El error del estimador (±25 %) distortiona el presupuesto. | Con el servidor institucional siempre hay `usage` real. Cuando hay estimación, el panel lo dice y el presupuesto se marca "aproximado". |
| R5 | El cliente puede mentir sobre su consumo. | El presupuesto **se aplica en el servidor** (§8.4). El ledger del cliente es sólo para la UI. |
| R6 | El `timings` de llama.cpp no existe en una API comercial → no hay horas-GPU exactas. | Se usa el throughput configurado en el `ServerProfile` y el informe lo marca como estimado. |
| R7 | La latencia del cliente incluye la red → la concurrencia pico queda sobrestimada. | Con `timings` se usa el tiempo de servidor; sin él, el informe lo declara como techo estimado. |
| R8 | El panel crece demasiado con muchas cátedra. | El informe institucional es un documento, no una pantalla; la pantalla es la de una cátedra. |
| R9 | El ledger crece con muchas entradas por modelo (el alumno puede cambiar de modelo). | Tope de `MAX_KEYS` con agregación en `"otros"`. |
| R10 | Cambiar el layout de buckets rompe los ledgers viejos. | `LatencyHistogram` guarda `BUCKETS` como constante: si cambia, hay que versionar el ledger (`histogramVersion`) y recalcular. Se anota como deuda a futuro. |

---

## 14. Apéndice: los números que ve el docente, explicados

| Lo que muestra | Qué es | De dónde sale |
|---|---|---|
| "38.204 peticiones" | Llamadas HTTP al modelo | Σ `rounds` de los `UsageRecord` |
| "61,2 M tokens in" | Tokens de entrada | Σ `tokensIn` (real) o estimado |
| "3,4 M tokens out" | Tokens generados | Σ `tokensOut` |
| "p50 1,4 s" | Mediana de la latencia | `LatencyHistogram.percentile(0.50)` |
| "p95 6,2 s" | El 5 % más lento | `percentile(0.95)` |
| "p99 14,8 s" | El 1 % más lento | `percentile(0.99)` |
| "media 4,2 s" | Promedio | `sumMs / count` (exacto) |
| "Alojado por la institución" | Sin coste por token | `PriceTable.isEmpty` |
| "2 alumnos sobre presupuesto" | Agotaron su tope | `BudgetStatus.level == AGOTADO` |
| "41,7 h de GPU" | Horas de cómputo | `prefillHours + decodeHours` (§7.2) |
| "23 de 32 slots" | Concurrencia pico | `Concurrency.peak` (§7.6) |
| "USD 312 equivalente" | Lo que costaría en API comercial | `PriceTable.estimate` sobre todos los registros |
