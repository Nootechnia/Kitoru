# 04 — Observabilidad y diagnóstico

> Subagente: diseño de **cómo se diagnostican los problemas de Kitoru**, tanto
> para el usuario que reporta un bug como para el desarrollador.
> Módulos tocados: `:core` (logger, redacción, métricas, reporte),
> `:llm-api` (decorador que instrumenta el gateway), `:backend` (estado del
> índice), `:ui` (panel de diagnóstico y "Acerca de").
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable
> por defecto*, *fail-fast con mensajes en español*, *el estudiante conserva
> agencia*, *el docente no ve lo que no debe*.

---

## 0. Objetivo, alcance y decisiones

Kitoru es una app **local-first**: la institución aloja el modelo, pero los
datos del alumno (chats, materiales, índice) viven en la máquina del alumno y
—según la visibilidad de la inscripción— pueden llegar al docente. Eso obliga
a que la observabilidad sea **deliberadamente silenciosa**: nada sale del
dispositivo sin que el usuario lo pegue, él mismo, en un bug report.

### 0.1 Las ocho decisiones, en una tabla

| # | Decisión | Por qué |
|---|---|---|
| 1 | **Logger propio** en `kitoru.platform.log`, sin dependencias externas | FunshiEngineAI loguea con `println` (ver `frontend/src/main/kotlin/app/screens/SettingsPane.kt:41`: `println("TEMP-DEBUG value=$it")`). Eso no sirve: no tiene niveles, no se puede filtrar, no sobrevive a un crash y no se puede adjuntar a un bug report. |
| 2 | **Texto plano legible**, una línea por evento, con timestamp fijo | Nadie va a correr `jq` sobre el log de un alumno. Un desarrollador de la cátedra lo abre en un bloc de notas. El formato es, aun así, parseable por una regexp (§4.4). |
| 3 | **Nunca** la API key, **nunca** el texto completo del alumno | La redacción se aplica **en el sink**, no en el call site: aunque alguien escriba `logger.i("key=$apiKey")`, la clave no sale. (§5) |
| 4 | Log en la **carpeta de datos**, con rotación por tamaño | `~/.kitoru/logs/kitoru.log` en desktop; `context.filesDir/kitoru/logs/kitoru.log` en Android (interno, **nunca** el almacenamiento externo). (§6) |
| 5 | **Sin crash reporting externo** (ni Crashlytics ni Sentry) | Los chats son datos institucionales; mandarlos a un SaaS es una fuga que la institución no autorizó. Se resuelve con log local + "Copiar diagnóstico" + handler de crash local. (§7) |
| 6 | **Métricas en memoria**, sin persistencia | Contadores de la sesión actual (peticiones, errores por código, latencias, tokens). Se ven en "Acerca de" y se adjuntan al diagnóstico. No hay base de datos de telemetría: eso sería una instancia de la decisión 5. (§3) |
| 7 | El "copiar diagnóstico" es un **texto plano por secciones**, con **consentimiento explícito** para incluir chats | Un JSON gigante no lo lee un docente. Y el contenido del chat sólo entra si el usuario lo pide en el momento. (§5, §9) |
| 8 | **Tests de humo** end-to-end con gateway falso y corpus de prueba | Un fallo en el flujo real (indexar → recuperar → responder → persistir → loguear) es el tipo de bug que más se reporta y el que más se escapa de los tests unitarios. (§8) |

### 0.2 Qué queda fuera de alcance

- Envío automático de reportes a un servidor (§7).
- Persistencia de métricas entre sesiones (§3).
- Un APM, un tracer distribuido o métricas OpenTelemetry (§7).
- Edición de logs desde la app: el log es de lectura, se borra o se copia (§6).

---

## 1. El logger

### 1.1 Dónde vive

`kitoru.platform.log`, dentro de `:core`. Es el módulo del que dependen
`:llm-api`, `:backend` y `:ui`, así que cualquiera puede loguear sin generar
dependencias circulares.

```
core/src/commonMain/kotlin/kitoru/platform/log/
    LogLevel.kt        enum de niveles
    LogRecord.kt       un evento (timestamp, nivel, tag, mensaje, excepción)
    Logger.kt          la interfaz + helpers (d/i/w/e, timed)
    LogTags.kt         tags canónicos
    LogBuffer.kt       buffer circular en memoria
    LogSink.kt         destino de una línea (consola, archivo, …)
    DefaultLogger.kt   la implementación que compone buffer + sinks + redacción
    FileLogSink.kt     escritura a archivo con rotación por tamaño
    Redactor.kt        la redacción de secretos (§5)
    TimeFormat.kt      expect fun formatTimestamp(millis): String
    KitoruLog.kt       el logger global + swap para tests

core/src/jvmCommonMain/kotlin/kitoru/platform/log/
    TimeFormat.jvm.kt          actual (SimpleDateFormat, API 1)
    CrashHandler.jvm.kt        handler de excepción no capturada

core/src/desktopMain/kotlin/kitoru/platform/log/
    Logger.desktop.kt          actual: consola (stderr) + archivo
core/src/androidMain/kotlin/kitoru/platform/log/
    Logger.android.kt          actual: logcat + archivo
```

### 1.2 Niveles

```kotlin
package kitoru.platform.log

/**
 * Nivel de un evento de log.
 *
 * El orden es el de comparación: [passes] usa el ordinal, así que subir el
 * nivel de un logger filtra todo lo de abajo.
 */
enum class LogLevel(val label: String) {
    TRACE("TRACE"),
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
    ;

    /** ¿Un evento de este nivel pasa el filtro de nivel [min]? */
    fun passes(min: LogLevel): Boolean = ordinal >= min.ordinal
}
```

Niveles por defecto:

| Plataforma | Release | Debug |
|---|---|---|
| Desktop | `INFO` | `DEBUG` |
| Android | `INFO` | `DEBUG` |

`TRACE` existe para instrumentación muy fina (trazado del RAG, token a token)
y **no se escribe al archivo** salvo que el usuario suba el nivel a mano desde
el panel de diagnóstico (§9).

### 1.3 La interfaz `Logger`

```kotlin
package kitoru.platform.log

/**
 * Logger de la aplicación.
 *
 * Contrato mínimo: un nivel, un tag y un mensaje. Sin dependencias externas:
 * la implementación escribe en memoria, en un archivo y (según la plataforma)
 * en la consola del sistema.
 */
interface Logger {

    /** Nivel mínimo que se registra: por debajo se descarta sin formatear. */
    val minLevel: LogLevel

    /** Registra un evento. */
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)

    fun trace(tag: String, message: String) = log(LogLevel.TRACE, tag, message)
    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.WARN, tag, message, throwable)

    fun error(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, throwable)
}
```

Y los helpers de uso (en `Logger.kt`, mismo archivo):

```kotlin
/** Ejecuta [block] y loguea cuánto tardó, a nivel DEBUG. */
inline fun <T> Logger.timed(tag: String, block: () -> T): T {
    val t0 = nowMillis()
    try {
        return block()
    } finally {
        log(LogLevel.DEBUG, tag, "terminó en ${nowMillis() - t0} ms")
    }
}
```

### 1.4 Tags canónicos

```kotlin
package kitoru.platform.log

/** Tags canónicos: el filtro del panel de diagnóstico los ofrece de a uno. */
object LogTags {
    const val APP = "App"           // arranque, cierre, versión
    const val GATEWAY = "Gateway"   // peticiones al modelo y sus errores
    const val RAG = "Rag"           // recuperación y verificación de citas
    const val INDEX = "Index"       // indexación de materiales
    const val CHAT = "ChatEngine"   // motor de chat
    const val IO = "IO"             // lectura/escritura de archivos y repos
    const val UI = "UI"             // acciones de pantalla
    const val DIAG = "Diag"         // el propio panel de diagnóstico
}
```

Un tag fuera de esta lista se acepta (es un `String`), pero el panel de
diagnóstico ofrece un desplegable con estos ocho y "todos".

### 1.5 `LogRecord` y el render

```kotlin
package kitoru.platform.log

import kitoru.platform.nowMillis

/**
 * Un evento de log ya formateado.
 *
 * El mensaje llega **redactado** (§5): la redacción se aplica en el logger,
 * antes de construir el registro, para que ni el archivo ni el buffer
 * circular guarden el texto original.
 */
data class LogRecord(
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    /** Excepción ya descripta y redactada (tipo, mensaje y stack). */
    val throwableText: String? = null,
) {
    /**
     * Render de una línea (o de un bloque, si hay excepción).
     *
     * Formato: `2026-10-21 14:03:22.123 [WARN ] ChatEngine: el índice está vacío`
     */
    fun render(): String {
        val head = "${formatTimestamp(timestampMillis)} [${level.label.padEnd(5)}] $tag: $message"
        val tail = throwableText
            ?.takeIf { it.isNotBlank() }
            ?.let { "\n    " + it.replace("\n", "\n    ") }
        return head + (tail ?: "")
    }
}
```

### 1.6 El buffer circular

Los **últimos N mensajes en memoria** son el corazón del "copiar diagnóstico":
sirven para el panel de la app (§9) y para el reporte (§5), sin tocar disco.

```kotlin
package kitoru.platform.log

/**
 * Buffer circular de los últimos [capacity] eventos.
 *
 * Es un ring buffer con cerrojo: se escribe desde cualquier hilo (la UI, el
 * dispatcher de I/O, el agente) y se lee desde el hilo de la UI.
 */
class LogBuffer(val capacity: Int = 500) {

    init {
        require(capacity > 0) { "La capacidad del buffer debe ser mayor que 0." }
    }

    private val items = arrayOfNulls<LogRecord>(capacity)
    private var count = 0
    private var head = 0
    private val lock = Any()

    /** Agrega un evento, descartando el más viejo si el buffer está lleno. */
    fun append(record: LogRecord) = synchronized(lock) {
        items[(head + count) % capacity] = record
        if (count < capacity) count++ else head = (head + 1) % capacity
    }

    /** Copia de los eventos, del más viejo al más nuevo. */
    fun snapshot(): List<LogRecord> = synchronized(lock) {
        (0 until count).map { items[(head + it) % capacity]!! }
    }

    /** Vacía el buffer (botón "Borrar logs"). */
    fun clear() = synchronized(lock) {
        items.indices.forEach { items[it] = null }
        count = 0
        head = 0
    }

    /** Render de los eventos filtrados, uno por línea. */
    fun render(
        minLevel: LogLevel = LogLevel.TRACE,
        tag: String? = null,
        limit: Int = Int.MAX_VALUE,
    ): String = snapshot()
        .filter { it.level.passes(minLevel) && (tag == null || it.tag == tag) }
        .takeLast(limit)
        .joinToString("\n") { it.render() }
}
```

Capacidad por defecto: **500 eventos**. Con líneas de ~120 caracteres, son
~60 KB en memoria: despreciable, y suficiente para cubrir una sesión completa
de trabajo.

### 1.7 Sinks

```kotlin
package kitoru.platform.log

/** Destino de una línea de log ya renderizada. */
interface LogSink {
    /** Escribe la línea. No debe lanzar: el logger aísla los fallos. */
    fun write(record: LogRecord, line: String)
}
```

Implementaciones:

| Sink | Dónde | Qué hace |
|---|---|---|
| `ConsoleSink` | `commonMain` | Escribe con un `printer: (String) -> Unit` inyectado. Desktop: `System.err`. Android: `android.util.Log` con el nivel mapeado (`Log.i/w/e`) para que `adb logcat` lo vea. |
| `FileLogSink` | `commonMain` | Escribe en el archivo con rotación por tamaño (§6). |
| `MemorySink` | `commonMain` | No hace falta: el buffer está dentro del `Logger`. |

### 1.8 `DefaultLogger`: buffer + sinks + redacción

```kotlin
package kitoru.platform.log

import kitoru.platform.nowMillis

/**
 * Logger por defecto: buffer circular en memoria + sinks + redacción.
 *
 * La redacción se aplica **antes** de construir el [LogRecord], así que
 * ningún sink (ni el archivo, ni el buffer, ni el portapapeles) puede
 * mostrar un secreto que haya pasado por el logger.
 */
class DefaultLogger(
    override val minLevel: LogLevel,
    private val sinks: List<LogSink>,
    /** Buffer circular: últimos eventos para el panel y el diagnóstico. */
    val buffer: LogBuffer = LogBuffer(),
    private val redact: (String) -> String = Redactor::redact,
) : Logger {

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (!level.passes(minLevel)) return
        val record = LogRecord(
            timestampMillis = nowMillis(),
            level = level,
            tag = tag,
            message = redact(message).take(MAX_MESSAGE_CHARS),
            throwableText = throwable?.let { redact(describe(it)).take(MAX_THROWABLE_CHARS) },
        )
        buffer.append(record)
        val line = record.render()
        sinks.forEach { sink -> runCatching { sink.write(record, line) } }
    }

    private fun describe(t: Throwable): String = buildString {
        append(t::class.simpleName ?: "Throwable")
        t.message?.takeIf { it.isNotBlank() }?.let { append(": "); append(it) }
        append("\n")
        append(t.stackTraceToString())
    }

    companion object {
        /** Tope del mensaje: evita que un volcado enorme contamine el log. */
        const val MAX_MESSAGE_CHARS = 4_000

        /** Tope del stack trace (contando el mensaje de la excepción). */
        const val MAX_THROWABLE_CHARS = 8_000
    }
}
```

### 1.9 `expect fun defaultLogger()`

```kotlin
package kitoru.platform.log

/**
 * Logger global de la aplicación.
 *
 * Devuelve **siempre la misma instancia** en cada plataforma: el buffer
 * circular es compartido y es la fuente del panel de diagnóstico.
 *
 * - Desktop: consola (stderr) + archivo `logs/kitoru.log`.
 * - Android: logcat + archivo `logs/kitoru.log` (interno al sandbox).
 */
expect fun defaultLogger(): Logger
```

- `core/src/desktopMain/kotlin/kitoru/platform/log/Logger.desktop.kt`
- `core/src/androidMain/kotlin/kitoru/platform/log/Logger.android.kt`

Y el holder común, para el uso diario y para los tests:

```kotlin
package kitoru.platform.log

/** Acceso al logger global. */
object KitoruLog {

    /** Logger activo. Los tests lo pueden reemplazar con [install]. */
    var logger: Logger = defaultLogger()
        private set

    /** Reemplaza el logger global (tests, o arranque con otro nivel). */
    fun install(replacement: Logger) {
        logger = replacement
    }
}
```

Los call sites usan `KitoruLog.logger.info(LogTags.INDEX, "…")`. Es una
indirección de una línea que permite testear sin tocar disco.

### 1.10 Formato del timestamp

`formatTimestamp` es `expect` porque el formateo de fecha no es portable
(`java.time` es API 26 en Android y `minSdk` es 24):

```kotlin
package kitoru.platform.log

/**
 * Formatea un instante como `AAAA-MM-DD HH:MM:SS.mmm` en la zona horaria
 * local. El timestamp del log es local, no UTC: el que lee el log está
 * mirando la misma hora que la app.
 */
expect fun formatTimestamp(millis: Long): String
```

El `actual` va en `core/src/jvmCommonMain/.../TimeFormat.jvm.kt` con
`SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)` (API 1 en
Android), guardado en un `ThreadLocal` porque `SimpleDateFormat` no es
thread-safe:

```kotlin
private val formatter = ThreadLocal.withInitial {
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT)
}

actual fun formatTimestamp(millis: Long): String = formatter.get()!!.format(java.util.Date(millis))
```

La zona horaria se incluye aparte en el reporte (§5), porque el log por sí
solo no la dice.

---

## 2. Qué se loguea y qué no

La regla general: **el log cuenta lo que pasó con el sistema, no lo que dijo
el alumno**.

| Evento | Se loguea | Detalle |
|---|---|---|
| Arranque de la app | `INFO App` | versión, SO, ruta de datos (relativa), nivel de log |
| Petición al modelo | `DEBUG Gateway` | modelo, nº de mensajes, nº de tools, tamaño del prompt en caracteres |
| Error del modelo | `ERROR Gateway` | `LlmErrorCode`, mensaje de la excepción (redactado), si es reintentable |
| Inicio/fin de indexación | `INFO Index` | documentos, fragmentos, duración |
| Recuperación RAG | `DEBUG Rag` | consulta, nº de fragmentos recuperados, scores (no el texto) |
| Verificación de citas | `DEBUG Rag` | citas verificadas / sin respaldo / desconocidas |
| Respuesta del asistente | `INFO ChatEngine` | latencia, tokens estimados, nº de fuentes citadas |
| **Mensaje del alumno** | **NO** | sólo `longitud en caracteres` y el hilo/rol |
| **API key** | **NO** | sólo "configurada / no configurada" |
| Prompt del sistema de la cátedra | **NO** | sólo su longitud |
| Contenido de un material | **NO** | sólo nombre, tamaño y nº de fragmentos |

Ejemplo de línea válida y de línea prohibida:

```
2026-10-21 14:03:22.123 [INFO ] ChatEngine: respuesta en 1820 ms, 421 tokens, 3 fuentes   ← bien
2026-10-21 14:03:22.123 [INFO ] ChatEngine: alumno: ¿me explicás la factorización?          ← mal
```

La forma canónica de registrar un mensaje del alumno es:

```kotlin
logger.info(LogTags.CHAT, "mensaje del alumno en $threadId ($len caracteres)")
```

---

## 3. Las métricas de salud

### 3.1 Por qué en memoria

Un contador persistente es una base de datos de telemetría disfrazada, y la
decisión 5 (§0.1) dice que no hay telemetría. Las métricas de la **sesión
actual** alcanzan para responder "¿está fallando ahora?" y para el reporte.
Al reiniciar la app, se reinician los contadores: es el comportamiento
esperado y está escrito en el panel ("métricas de esta sesión").

### 3.2 Ubicación y modelos

`kitoru.platform.metrics`, en `:core`:

```kotlin
package kitoru.platform.metrics

/** Resumen de latencias de las peticiones medidas. */
data class LatencyStats(
    val count: Long = 0,
    val avgMs: Long = 0,
    val p50Ms: Long = 0,
    val p95Ms: Long = 0,
    val maxMs: Long = 0,
)

/** Un error reciente, listo para el reporte. */
data class ErrorInfo(
    val timestampMillis: Long,
    /** Nombre del código (`LlmErrorCode.name` u otro). */
    val code: String,
    val message: String,
    val retryable: Boolean = false,
)

/** Foto de las métricas en un instante. Inmutable. */
data class MetricsSnapshot(
    val sinceMillis: Long,
    val uptimeMs: Long,
    val requests: Long,
    val requestsOk: Long,
    val requestsFailed: Long,
    /** Errores por código: "SIN_CUOTA" → 3. */
    val errorsByCode: Map<String, Long>,
    val latency: LatencyStats,
    val tokensIn: Long,
    val tokensOut: Long,
    /** Contadores libres: "index.chunks" → 1284, "materials" → 12. */
    val gauges: Map<String, Long>,
    /** Últimos errores (más reciente al final). */
    val recentErrors: List<ErrorInfo> = emptyList(),
)
```

### 3.3 `HealthMetrics`

```kotlin
package kitoru.platform.metrics

import kitoru.platform.nowMillis

/**
 * Contadores de salud en memoria.
 *
 * Sin dependencias, sin reloj propio (usa `kitoru.platform.nowMillis`) y
 * con un anillo de latencias para poder calcular percentiles aproximados.
 */
class HealthMetrics(
    /** Cuántas latencias se guardan para calcular percentiles. */
    private val latencyCapacity: Int = 200,
    /** Cuántos errores recientes se recuerdan. */
    private val errorCapacity: Int = 20,
) {
    private val lock = Any()
    private val startedAt = nowMillis()

    private var requests = 0L
    private var requestsOk = 0L
    private var requestsFailed = 0L
    private var tokensIn = 0L
    private var tokensOut = 0L
    private var errorsByCode = mutableMapOf<String, Long>()
    private var gauges = mutableMapOf<String, Long>()

    private val latencies = LongArray(latencyCapacity)
    private var latencyCount = 0
    private var latencyHead = 0

    private val errors = ArrayDeque<ErrorInfo>()

    /** Registra el resultado de una petición al modelo. */
    fun recordRequest(latencyMs: Long, ok: Boolean, errorCode: String? = null) = synchronized(lock) {
        requests++
        if (ok) requestsOk++ else requestsFailed++
        if (errorCode != null) {
            errorsByCode[errorCode] = (errorsByCode[errorCode] ?: 0L) + 1
        }
        latencies[(latencyHead + latencyCount) % latencyCapacity] = latencyMs
        if (latencyCount < latencyCapacity) latencyCount++ else latencyHead = (latencyHead + 1) % latencyCapacity
    }

    /** Registra tokens (estimados por la app, no por el proveedor). */
    fun recordTokens(input: Int, output: Int) = synchronized(lock) {
        tokensIn += input
        tokensOut += output
    }

    /** Fija un contador libre: "index.chunks", "materials", "threads"… */
    fun setGauge(name: String, value: Long) = synchronized(lock) {
        gauges[name] = value
    }

    /** Registra un error reciente (los últimos [errorCapacity]). */
    fun recordError(info: ErrorInfo) = synchronized(lock) {
        errors.addLast(info)
        while (errors.size > errorCapacity) errors.removeFirst()
    }

    /** Foto de las métricas. */
    fun snapshot(): MetricsSnapshot = synchronized(lock) {
        val sorted = latencies.take(latencyCount).sorted()
        MetricsSnapshot(
            sinceMillis = startedAt,
            uptimeMs = nowMillis() - startedAt,
            requests = requests,
            requestsOk = requestsOk,
            requestsFailed = requestsFailed,
            errorsByCode = errorsByCode.toMap(),
            latency = LatencyStats(
                count = latencyCount.toLong(),
                avgMs = if (latencyCount == 0) 0 else sorted.sum() / latencyCount,
                p50Ms = percentile(sorted, 0.50),
                p95Ms = percentile(sorted, 0.95),
                maxMs = sorted.lastOrNull() ?: 0,
            ),
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            gauges = gauges.toMap(),
            recentErrors = errors.toList(),
        )
    }

    /** Pone los contadores en cero (tests). */
    fun reset() = synchronized(lock) {
        requests = 0; requestsOk = 0; requestsFailed = 0
        tokensIn = 0; tokensOut = 0
        errorsByCode.clear(); gauges.clear(); errors.clear()
        latencyCount = 0; latencyHead = 0
    }

    private fun percentile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    companion object {
        /** Instancia global de la app. */
        val global = HealthMetrics()
    }
}
```

Los percentiles se calculan sobre las **últimas 200 peticiones** (no un
histograma exacto): es suficiente para distinguir "va lento" de "está caído",
y el reporte lo dice explícitamente para que nadie lo interprete como
precisión de producción.

### 3.4 Quién alimenta las métricas

`:llm-api` aporta el error tipado y la latencia, con un **decorador** que no
tocaría la firma del gateway existente:

```kotlin
package llm.api

import kitoru.platform.log.LogTags
import kitoru.platform.log.Logger
import kitoru.platform.metrics.HealthMetrics
import kitoru.platform.nowMillis
import llm.error.LlmErrorCode
import llm.error.LlmException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Envuelve un [LlmGateway] para loguear y contar.
 *
 * Se aplica por fuera (`InstrumentedGateway(gateway, logger, metrics)`), así
 * que `OpenAiCompatibleGateway` queda sin tocar y el decorador se puede
 * testear con un gateway falso.
 */
class InstrumentedGateway(
    private val delegate: LlmGateway,
    private val logger: Logger,
    private val metrics: HealthMetrics,
) : LlmGateway {

    override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> = flow {
        val t0 = nowMillis()
        var chars = 0
        logger.debug(
            LogTags.GATEWAY,
            "petición: ${messages.size} mensajes, ${tools?.size ?: 0} tools, " +
                "${messages.sumOf { it.content.length }} caracteres",
        )
        try {
            delegate.chatStream(messages, tools).collect { chunk ->
                if (chunk is LlmChunk.Text) chars += chunk.delta.length
                emit(chunk)
            }
            val ms = nowMillis() - t0
            metrics.recordRequest(ms, ok = true)
            logger.info(LogTags.GATEWAY, "respuesta completa en $ms ms, $chars caracteres")
        } catch (e: LlmException) {
            val ms = nowMillis() - t0
            metrics.recordRequest(ms, ok = false, errorCode = e.code.name)
            metrics.recordError(ErrorInfo(nowMillis(), e.code.name, e.message, e.retryable))
            logger.error(LogTags.GATEWAY, "fallo ${e.code.name}: ${e.message}", e)
            throw e
        }
    }

    override suspend fun listModels(): List<String> = delegate.listModels()

    override fun close() = delegate.close()
}
```

Y la extensión tipada para no perder el `LlmErrorCode`:

```kotlin
package llm.metrics

import kitoru.platform.metrics.HealthMetrics
import llm.error.LlmErrorCode

/** Registra una llamada al LLM con el código de error tipado. */
fun HealthMetrics.recordLlmCall(latencyMs: Long, code: LlmErrorCode? = null) {
    recordRequest(latencyMs, ok = code == null, errorCode = code?.name)
}
```

El conteo de **tokens** lo hace el motor de chat (que ya calcula
`tokensIn`/`tokensOut` estimados en `ChatMessage`): `metrics.recordTokens(...)`
desde el chat engine, no desde el gateway, porque el gateway no ve el uso que
reporta el proveedor.

Los **gauges** los fija quien conoce el dato:

```kotlin
metrics.setGauge("index.chunks", kb.chunkCount)
metrics.setGauge("index.documents", kb.sources.size)
metrics.setGauge("materials", materials.size)
metrics.setGauge("threads", threads.size)
```

### 3.5 Dónde se ven

- **"Acerca de"**: una tarjeta con el resumen de la sesión (§9.4).
- **Panel de diagnóstico**: la tabla completa (§9.2).
- **Reporte**: la sección `--- SALUD ---` (§5.4).

---

## 4. El formato de los logs

### 4.1 Decisión: texto, no JSON

**Se loguea en texto plano con una línea por evento.**

Justificación, en el orden en que importa:

1. **El consumidor real es humano.** El log de Kitoru lo lee (a) el docente
   que reporta un problema, (b) el desarrollador que recibe el bug report, (c)
   el alumno que quiere ver qué pasó. Ninguno de ellos va a escribir `jq`.
2. **El destino es un pegado en un issue.** Un JSON de 400 líneas en un
   ticket de GitHub es ilegible; 40 líneas de texto con timestamps se leen de
   un tirón y el desarrollador ubica el evento en el tiempo al instante.
3. **El volumen es chico.** Una sesión de chat genera cientos de eventos, no
   millones. JSON no compra nada a esta escala: su ventaja (agregación) es
   irrelevante y su costo (ruido, escapes, claves repetidas en cada línea) es
   real.
4. **El desktop no tiene un pipeline.** No hay Loki, no hay Datadog, no hay
   `journalctl`. La "infraestructura" de observabilidad de Kitoru es un
   archivo de texto y un portapapeles.
5. **La app también es Android.** Un `logcat` con líneas JSON es más difícil
   de leer en un teléfono que texto con timestamps.

**Lo que sí se conserva: la estructura.** El formato de línea es fijo y
parseable por una regexp, así que un test puede verificar el contrato y un
script puede extraer campos si algún día hace falta:

```
^(?<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[(?<level>[A-Z]{3,5} )\] (?<tag>[^:]{1,24}): (?<msg>.*)$
```

Y el `LogRecord` ya está descompuesto (timestamp, nivel, tag, mensaje), así
que **si algún día hay que exportar a JSON**, se agrega un sink
`JsonSink` que serialice el `LogRecord` — sin tocar el logger ni los call
sites. Es un agregar, no una migración.

### 4.2 La línea

```
2026-10-21 14:03:22.123 [WARN ] ChatEngine: el índice está vacío
└──────────┬──────────────┘ └─────┬───┘ └────┬───┘ └───────────┬─────────────┘
   timestamp local               nivel      tag             mensaje
                              (5 columnas)
```

Reglas:

- **Timestamp**: `AAAA-MM-DD HH:MM:SS.mmm`, zona horaria local. Sin timestamp
  no hay diagnóstico: el orden de los eventos es lo primero que se pregunta
  ("¿cuándo pasó?").
- **Nivel**: entre corchetes, **siempre 5 caracteres** (`WARN ` con espacio,
  `INFO `, `ERROR`, `DEBUG`, `TRACE`). El ancho fijo permite alinear a ojo
  y parsear con una regexp simple.
- **Tag**: el origen del evento (`ChatEngine`, `Gateway`, `Index`…), sin
  espacios y sin `:`.
- **Mensaje**: en español, en presente, describing lo que pasó:
  `el índice está vacío`, `respuesta en 1820 ms`, `fallo SIN_CUOTA: la API
  alcanzó su límite de uso`.
- **Excepción**: va a continuación, indentada 4 espacios, con el tipo y el
  mensaje en la primera línea y el stack debajo. La indentación marca que
  es continuación y no un evento nuevo.

Ejemplo con excepción:

```
2026-10-21 14:03:22.123 [ERROR] Gateway: fallo SIN_CUOTA: la API alcanzó su límite de uso (HTTP 429)
    llm.error.LlmException: la API alcanzó su límite de uso (HTTP 429).
        at llm.api.OpenAiCompatibleGateway.chatStream(LlmGateway.kt:218)
        at rag.engine.RagEngine.answer(RagEngine.kt:96)
```

### 4.3 Niveles y qué se escribe en el archivo

| Nivel | Se escribe | Ejemplos |
|---|---|---|
| `TRACE` | **no** (sólo en memoria) | token a token del stream, cada chunk del RAG |
| `DEBUG` | sí | petición al modelo, recuperación, verificaciones |
| `INFO` | sí | arranque, indexación completada, respuesta servida |
| `WARN` | sí | reintento, índice vacío, degradación a modo local |
| `ERROR` | sí | excepción de la llamada al modelo, fallo de escritura |

`TRACE` no va al archivo porque es voluminoso y no sirve para el bug report;
queda en el buffer circular (que el panel puede mostrar si el usuario sube el
nivel).

### 4.4 Contrato de formato (test)

`core/src/desktopTest/kotlin/kitoru/platform/log/LogFormatTest.kt` verifica
que la línea renderizada cumple la regexp de §4.1 y que el tag no tiene
espacios. Si alguien cambia el formato, el test falla: el formato es parte
del contrato con el usuario (los bug reports viejos siguen siendo legibles).

---

## 5. La privacidad en los logs

### 5.1 Las tres reglas duras

1. **La API key nunca se registra.** Ni completa, ni parcial, ni sus últimos
   cuatro caracteres. En el reporte: `Clave: configurada` o
   `Clave: no configurada`.
2. **El texto del alumno nunca se registra.** Del mensaje del alumno sólo
   sale su longitud y el hilo. Si el usuario pide incluir el contenido del
   chat en el diagnóstico, eso es **otro** camino (el consentimiento explícito
   de §5.5), no un log.
3. **El prompt del sistema de la cátedra y el contenido de los materiales no
   se registran.** Sólo su longitud y su nombre.

### 5.2 `Redactor`

```kotlin
package kitoru.platform.log

/**
 * Redacción de secretos y truncado de textos.
 *
 * Se aplica **en el logger** (antes de construir el registro), no en los call
 * sites: es una defensa en profundidad. Si alguien escribe
 * `logger.info("key=$apiKey")`, la clave no llega al archivo ni al buffer.
 */
object Redactor {

    /** Máximo de caracteres de un preview de texto. */
    const val MAX_PREVIEW = 160

    /**
     * Nombres de campo tratados como secretos.
     *
     * `token` **no** está en la lista a propósito: en Kitoru "token" es la
     * unidad de conteo del modelo ("se agotaron los tokens"), no un secreto.
     */
    private val SECRET_FIELDS = listOf(
        "api_key", "apikey", "api-key",
        "authorization", "x-api-key",
        "secret", "password", "passwd",
        "access_token", "refresh_token", "session_token",
    )

    private val FIELD_PATTERN = Regex(
        """(?i)\b(${SECRET_FIELDS.joinToString("|") { Regex.escape(it) }})\b(\s*[:=]\s*"?)([^"\s,;&}]+)""",
    )

    private val BEARER_PATTERN = Regex("""(?i)\b(bearer)\s+([A-Za-z0-9._\-]{8,})""")

    /** Tokens con prefijo reconocible (OpenAI, GitHub, Google). */
    private val PREFIXED_PATTERNS = listOf(
        Regex("""\bsk-[A-Za-z0-9_\-]{6,}"""),
        Regex("""\bgh[pousr]_[A-Za-z0-9]{16,}"""),
        Regex("""\bAIza[0-9A-Za-z_\-]{20,}"""),
    )

    /** `https://usuario:clave@host` → `https://[REDACTED]@host`. */
    private val URL_USERINFO_PATTERN = Regex("""(?i)(://)[^/\s:@]+:[^/\s@]+@""")

    private const val MASK = "[REDACTED]"

    /** Reemplaza los secretos que se puedan detectar en [text]. */
    fun redact(text: String): String {
        var out = FIELD_PATTERN.replace(text) { m -> "${m.groupValues[1]}${m.groupValues[2]}$MASK" }
        out = BEARER_PATTERN.replace(out) { "${m.groupValues[1]} $MASK" }
        PREFIXED_PATTERNS.forEach { out = it.replace(out, MASK) }
        out = URL_USERINFO_PATTERN.replace(out) { "${m.groupValues[1]}$MASK@" }
        return out
    }

    /**
     * Estado de un secreto, sin revelar nada: `configurada` / `no configurada`.
     *
     * No se muestra ni un prefijo ni los últimos caracteres: la regla es
     * "nunca", no "casi nunca".
     */
    fun maskSecret(secret: String): String =
        if (secret.isBlank()) "no configurada" else "configurada"

    /**
     * Preview de un texto para el log: los primeros [max] caracteres.
     *
     * Se usa **sólo** para textos que no son del alumno (nombres de archivo,
     * mensajes de error). Para el contenido del alumno se usa [lengthOnly].
     */
    fun preview(text: String, max: Int = MAX_PREVIEW): String = when {
        text.length <= max -> text
        else -> text.take(max) + "… (+${text.length - max} caracteres)"
    }

    /** Marca de longitud: el contenido no se registra. */
    fun lengthOnly(text: String): String = "($(text.length) caracteres, contenido no registrado)"
}
```

Ejemplos de `redact`:

| Entrada | Salida |
|---|---|
| `apiKey=sk-abc123def456` | `apiKey=[REDACTED]` |
| `"api_key": "abc123xyz"` | `"api_key": "[REDACTED]"` |
| `Authorization: Bearer eyJhbGciOi...` | `Authorization: Bearer [REDACTED]` |
| `usó sk-proj-AAAAbbbbCCCC` | `usó [REDACTED]` |
| `https://user:pass@ia.univ.edu/v1` | `https://[REDACTED]@ia.univ.edu/v1` |
| `el modelo agotó los tokens` | `el modelo agotó los tokens` (intacto) |

### 5.3 Qué se redacta en la práctica

| Dónde | Qué | Cómo |
|---|---|---|
| `ModelPolicy.apiKey` | la clave | nunca se pasa al logger; en el reporte va `maskSecret` |
| Petición HTTP | header `Authorization` | `bearerAuth()` de Ktor no se loguea; si alguna vez se loguea, `redact` lo cubre |
| Cuerpo de error del proveedor | puede traer el cuerpo crudo | `redact` + tope de 4 000 caracteres |
| Mensaje del alumno | el texto | `lengthOnly` |
| Prompt del sistema | el texto | `lengthOnly` |
| Contenido de un material | el texto | `lengthOnly` |
| Excepción | mensaje + stack | `redact` + tope de 8 000 caracteres |
| Ruta absoluta de datos | puede traer el nombre del usuario | en el log, ruta **relativa**; en el reporte, sólo con consentimiento (§5.5) |

### 5.4 El reporte: "Copiar diagnóstico"

**Qué produce:** un texto plano por secciones, legible de un tirón. Nada de
JSON gigante: el que lo lee es un docente o un desarrollador, no un script.

Formato (ejemplo real, con los valores de ejemplo):

```
=== KITORU · DIAGNÓSTICO ===
Generado: 2026-10-21 14:03:22.123 (America/Argentina/Buenos_Aires)
Versión:   0.3.0 (build 42) · canal: desktop
Sistema:   Windows 11 10.0.22631 (amd64)
Runtime:   OpenJDK 21.0.5 · memoria libre 742 MB

--- CÁTEDRA ---
Curso:      12 - Álgebra (código 4KX9)
Rol:        docente · ayudante
Inscripciones: 34 (12 con visibilidad de texto)
Materiales: 12 (9 con texto extraído) · 1 con advertencia
Índice:     AL DIA · 1284 fragmentos de 9 documentos · indexado 2026-10-21 13:44:02 (2,1 s)

--- MODELO (configuración efectiva) ---
Modo:        Servidor de la cátedra (REMOTE)
Endpoint:    https://ia.universidad.edu.ar/v1
Clave:       configurada
Modelo:      kitoru-alumno-7b
Generación:  temperature 0.20 · max_tokens 1024 · thinking off · power medium
RAG:         top_k 5 · chunk_size 1200 · overlap 150
Herramientas: 2 habilitadas (listar_materiales, buscar_faq)
Timeout:     sin límite

--- SALUD (esta sesión) ---
Peticiones:  37 (36 ok · 1 error)
Errores:     SIN_CUOTA × 1
Latencia:    n=37 · media 1842 ms · p50 1610 ms · p95 4902 ms · máx 7331 ms
Tokens:      entrada 128940 · salida 12004
Índice:      2 sincronizaciones · última OK

--- ÚLTIMOS ERRORES ---
1. 2026-10-21 13:58:11.204 [ERROR] Gateway: fallo SIN_CUOTA: la API alcanzó su límite de uso (HTTP 429)
   llm.error.LlmException: la API alcanzó su límite de uso (HTTP 429).
       at llm.api.OpenAiCompatibleGateway.chatStream(LlmGateway.kt:218)

--- LOG (sesión actual, últimas 40 líneas) ---
2026-10-21 14:03:20.101 [INFO ] Index: indexación completa: 1284 fragmentos en 2,1 s
2026-10-21 14:03:21.330 [DEBUG] Rag: recuperación: 5 fragmentos de 1284 (consulta: 34 caracteres)
2026-10-21 14:03:22.123 [WARN ] ChatEngine: el índice está vacío
...

--- FIN DEL DIAGNÓSTICO ---
Generado localmente por Kitoru 0.3.0. No se envió nada a ningún servidor.
```

Con el consentimiento de chats, se agrega una sección más, **al final** (para
que el que lee las secciones de sistema no tenga que saltar por encima del
contenido sensible):

```
--- CONTENIDO DEL CHAT (incluido con consentimiento explícito) ---
Hilo: "División con raíces" · 6 mensajes · 2026-10-21 13:55 → 14:02
[alumno] ¿Me explicás cómo se factoriza un polinomio de grado 3?
[asistente] Para factorizar un polinomio de grado 3 primero se busca una raíz
  racional… (texto completo, 4213 caracteres)
```

Cada mensaje se **trunca a 2000 caracteres** y se marca el corte:
`… (truncado: 4213 de 4213 caracteres)`. El motivo: un chat de 60 mensajes
puede ser de 200 KB, que no entra cómodo en un ticket ni en un mail.

### 5.5 El diálogo de consentimiento

El botón **"Copiar diagnóstico"** no copia nada todavía: abre una hoja
(`DiagnosticShareDialog`) donde el usuario decide **qué** se copia. Las
opciones se presentan con su estado por defecto, que es **todo excluido**:

```
┌───────────────────────────────────────────────────────────┐
│  Compartir diagnóstico                                   │
│                                                           │
│  Se va a generar un texto plano con el estado de la app   │
│  para que puedas pegarlo en un reporte de problema.       │
│  No se envía nada a ningún servidor: sólo queda en tu      │
│  portapapeles.                                            │
│                                                           │
│  ○ Diagnóstico básico                                     │
│    Versión, sistema, estado del índice, configuración      │
│    efectiva, métricas, últimos errores y logs.             │
│    No incluye el contenido de las conversaciones.          │
│                                                           │
│  ○ Diagnóstico con el contenido del chat                  │
│    Además incluye los últimos 20 mensajes del hilo         │
│    "División con raíces". Puede contener datos            │
│    personales: revisalo antes de compartirlo.              │
│    [ Elegir otro hilo ▾ ]   [ 20 mensajes ▾ ]             │
│                                                           │
│  ☐ Incluir la ruta de la carpeta de datos                 │
│    (puede mostrar tu nombre de usuario)                    │
│                                                           │
│  ☐ Incluir mi nombre y mi correo                          │
│                                                           │
│  [ Cancelar ]                    [ Copiar diagnóstico ]    │
└───────────────────────────────────────────────────────────┘
```

Reglas del consentimiento:

- **Opt-in explícito**: por defecto sólo el "diagnóstico básico". El contenido
  del chat requiere marcar la segunda opción a propósito.
- **No se recuerda**: la elección vale para esa copia. Al cerrar la app, el
  consentimiento se olvida. No hay una preferencia "incluir siempre".
- ** granular**: el hilo y la cantidad de mensajes se eligen en el momento.
- **Aviso explícito**: el texto dice que puede haber datos personales y que
  el contenido no se envía automáticamente.
- **El contenido del chat se marca**: la sección del reporte lleva el título
  `CONTENIDO DEL CHAT (incluido con consentimiento explícito)`, para que un
  desarrollador que reciba el reporte sepa de antemano que hay material
  sensible y lo trate en consecuencia.
- **El reporte se redacta igual**: aun con consentimiento, el texto pasa por
  `Redactor.redact()` al final. El consentimiento es para incluir contenido,
  no para desactivar la redacción de secretos.

### 5.6 Dónde vive la lógica del reporte

`:core`, paquete `kitoru.diagnostics`:

| Archivo | Contenido |
|---|---|
| `DiagnosticModels.kt` | `DiagnosticSnapshot`, `DiagnosticOptions`, `AppInfo`, `CourseInfo`, `ModelInfo`, `IndexInfo` (los `ErrorInfo` ya viven en `kitoru.platform.metrics` y se reutilizan) |
| `DiagnosticReport.kt` | `DiagnosticReport.render(snapshot, options): String` — **función pura** |
| `DiagnosticBuilder.kt` | `DiagnosticBuilder` que junta las piezas y arma el snapshot |

La clave del diseño: **`DiagnosticReport.render` es una función pura** que
recibe un snapshot y devuelve un `String`. Se testea sin disco, sin red y sin
UI. El `DiagnosticBuilder` es el que talka con el mundo (repos, índice,
logger, métricas).

```kotlin
package kitoru.diagnostics

/** Qué se incluye en el reporte. Todos los "incluir…" arrancan en false. */
data class DiagnosticOptions(
    /** Incluir los últimos mensajes del chat. */
    val includeChat: Boolean = false,
    /** Cuántos mensajes, como máximo. */
    val maxMessages: Int = 20,
    /** Caracteres por mensaje, como máximo. */
    val maxMessageChars: Int = 2_000,
    /** Incluir la ruta absoluta de la carpeta de datos. */
    val includeDataPath: Boolean = false,
    /** Incluir nombre y correo del usuario. */
    val includeIdentity: Boolean = false,
)

/** Estado del índice de una cátedra. Lo llena `:backend`. */
data class IndexInfo(
    val status: IndexStatus,
    val documents: Int,
    val chunks: Int,
    val lastSyncAt: Long,
    val lastSyncDurationMs: Long,
    val lastError: String? = null,
) {
    enum class IndexStatus(val label: String) {
        VACIO("VACÍO"),
        AL_DIA("AL DÍA"),
        PARCIAL("PARCIAL"),
        ERROR("ERROR"),
    }
}
```

El render produce el texto del §5.4. Reglas de renderizado:

- Los números grandes se separan con un espacio de miles (`1 284`), no con
  punto: es el formato que se usa en español y se lee mejor.
- Los millisegundos se muestran como `1 842 ms` cuando son menos de 10 s, y
  como `2,1 s` cuando son más.
- Una sección vacía se omite entera (por ejemplo, si no hay errores, no
  aparece `--- ÚLTIMOS ERRORES ---`).
- Si el índice está vacío, la sección `--- CÁTEDRA ---` lo dice explícitamente:
  es la causa #1 de "el modelo no sabe nada de mi cátedra".

---

## 6. El archivo de log

### 6.1 Dónde vive

Se agrega a `KitoruPaths`:

```kotlin
object KitoruPaths {
    // ... lo actual ...

    /** Carpeta de logs (diagnóstico). */
    val logsDir: FilePath get() = dataDir.resolve("logs")
}
```

| Plataforma | Ruta |
|---|---|
| Desktop | `<user.home>/.kitoru/logs/kitoru.log` |
| Android | `context.filesDir/kitoru/logs/kitoru.log` |

**En Android, dentro del sandbox.** No se usa `Environment.getExternalStorageDirectory()`
ni `MediaStore`: el almacenamiento externo es legible por otras apps (en
Android ≤ 10 sin permisos, y con permisos en Android 11+), sobrevive a la
desinstalación en algunos casos y no se limpia solo. `filesDir` es privado,
se borra al desinstalar y no requiere permisos. Ésta es exactamente la razón
que da la consigna: *"en Android, `context.filesDir/logs` (no en el externo,
que es legible)"*.

### 6.2 Rotación

Política: **tamaño máximo, con un archivo anterior**.

| Parámetro | Valor | Motivo |
|---|---|---|
| `maxBytes` | 2 MB | Un mes de uso diario del docente sigue entrando en 1 archivo |
| `keep` | 1 (o sea `kitoru.log` + `kitoru.log.1`) | 4 MB como techo absoluto |
| Al superar | se rota | `kitoru.log` → `kitoru.log.1`, se borra la `.1` vieja |

La rotación **no** ocurre al arrancar: si el archivo actual ya tiene 1,5 MB,
la sesión nueva sigue escribiendo en él y el bug report tiene el contexto
completo. La rotación ocurre al superar el tope, durante la escritura.

```kotlin
package kitoru.platform.log

import kitoru.platform.FilePath
import kitoru.platform.FileSystem

/**
 * Escribe el log en un archivo, con rotación por tamaño.
 *
 * Archivos: `kitoru.log` (el actual) y `kitoru.log.1` … `kitoru.log.[keep]`
 * (los anteriores). El total en disco no pasa de `maxBytes * (keep + 1)`.
 */
class FileLogSink(
    private val fs: FileSystem,
    /** Se resuelve en cada escritura: la carpeta de datos puede fijarse tarde. */
    private val pathProvider: () -> FilePath,
    private val maxBytes: Long = 2L * 1024 * 1024,
    private val keep: Int = 1,
) : LogSink {

    override fun write(record: LogRecord, line: String) {
        val path = pathProvider()
        val parent = path.parent
        if (parent != null) fs.createDirectories(parent)
        fs.appendText(path, line + "\n")
        if (fs.sizeBytes(path) > maxBytes) rotate(path)
    }

    private fun rotate(path: FilePath) {
        val parent = path.parent
        // 1. Se borra el más viejo.
        if (keep >= 1) fs.delete(sibling(path, keep))
        // 2. Los demás se desplazan uno.
        for (i in keep - 1 downTo 1) {
            val from = sibling(path, i)
            if (fs.exists(from)) fs.move(from, sibling(path, i + 1))
        }
        // 3. El actual pasa a ser el ".1".
        fs.move(path, sibling(path, 1))
    }

    private fun sibling(path: FilePath, index: Int): FilePath =
        (path.parent ?: path).resolve("${path.name}.$index")
}
```

`FileSystem` no tiene `appendText` ni `move`: se agregan. Son dos métodos
pequeños y portables:

```kotlin
interface FileSystem {
    // ... lo actual ...
    /** Agrega texto al final del archivo (crea el archivo si no existe). */
    fun appendText(path: FilePath, text: String)
    /** Mueve (renombra) un archivo. */
    fun move(from: FilePath, to: FilePath)
}
```

con sus implementaciones en `core/src/jvmCommonMain/.../FileSystem.jvm.kt`:

```kotlin
override fun appendText(path: FilePath, text: String) {
    val f = path.value.toFile()
    f.parentFile?.mkdirs()
    f.appendText(text)          // java.io.File.appendText, API 1
}

override fun move(from: FilePath, to: FilePath) {
    val src = from.value.toFile()
    val dst = to.value.toFile()
    dst.parentFile?.mkdirs()
    if (!src.renameTo(dst)) {    // renameTo falla entre volúmenes distintos
        src.copyTo(dst, overwrite = true)
        src.delete()
    }
}
```

### 6.3 Cómo se borra

Tres caminos, todos explícitos:

1. **Botón "Borrar logs"** en el panel de diagnóstico (§9.3): borra
   `kitoru.log`, `kitoru.log.1`, `crash.log` y vacía el buffer circular.
   Pide confirmación con un texto claro ("se borran los logs de esta
   instalación; no se puede deshacer").
2. **Al desinstalar**: en desktop, la carpeta es `<home>/.kitoru` y el
   desinstalador de Windows no la borra necesariamente → el panel ofrece
   "Borrar todos los datos de Kitoru" (lo borra todo: config, chats, logs).
   En Android, `filesDir` se borra con la desinstalación.
3. **Al reportar un problema**: el usuario puede decidir no compartir nada
   borrando antes.

**Nunca** se borra un log automáticamente por edad: el tope de tamaño ya
acota el disco, y un log de hace un mes puede ser justo el que explica el
bug.

### 6.4 Qué **no** va al archivo de log

Los chats, los materiales y el índice viven en `config/` y `courses/` (los
JSON del dominio). El log **no** los duplica: si el log tuviera el texto de
los chats, borrar el log no borraría los datos y la promesa de privacidad se
rompería.

---

## 7. El crash reporting

### 7.1 Decisión: **sin servicios externos**

Kitoru **no** manda crashes a Crashlytics, Sentry, Bugsnag ni a ningún otro
servicio. El crash report es **el log local + el diagnóstico manual**.

Por qué, en orden de peso:

1. **Fuga de datos.** Un SDK de crash reporting recolecta el estado del
   dispositivo, el identificador de la app y —según cómo se configure— los
   breadcrumbs de la sesión. En Kitoru, un crash durante una respuesta del
   modelo puede arrastrar el contexto del chat. Los chats de los alumnos son
   datos institucionales: mandarlos (aunque sea fragmentados) a un servidor
   de terceros es exactamente lo que la institución no quiere. El principio
   *local-first* no es sólo dónde se guardan los datos: es **quién puede
   verlos**, y un SDK de telemetría rompe ese principio por la puerta de
   atrás.
2. **La app es de escritorio primero.** Crashlytics sólo existe para Android.
   Sentry tiene SDK de escritorio, pero arrastra un SDK nativo, una clave de
   proyecto y un proceso de registro de organización. El target principal (escritorio)
   quedaría sin cubrir.
3. **La red suele ser justamente lo que falla.** Los errores más molestos
   (permisos de archivo, el `llama-server` local que no arranca, la carpeta
   de datos en un disco lleno) ocurren **sin red**. Un reporte remoto no
   ayuda: el reporte ya está en el disco.
4. **El diagnóstico manual cubre el caso real.** El flujo de reporte de
   Kitoru es: el docente (o el alumno) copia el diagnóstico y lo manda por
   el canal que ya usan (mail, WhatsApp, un ticket). Ese flujo ya existe, ya
   está en las manos de la institución y no requiere que nadie más se
   entere.
5. **Costo de mantenimiento.** Una dependencia con SDK nativo, claves de
   proyecto y un panel web es un costo permanente por un beneficio marginal
   frente a un panel de diagnóstico local que además sirve para el usuario.

**Lo que se hace en su lugar:**

- **Handler de excepción no capturada local.** Al arrancar, la app instala
  un `UncaughtExceptionHandler` que escribe el crash en
  `logs/crash.log` y en el log corriente, y después deja que la app termine
  como siempre (en desktop, la JVM imprime el stack y sale; en Android, el
  proceso muere y Android guarda el tombstone del sistema).

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/platform/log/CrashHandler.jvm.kt
package kitoru.platform.log

import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kitoru.platform.nowMillis
import kitoru.platform.formatTimestamp

/**
 * Instala un handler de excepción no capturada que deja rastro local.
 *
 * No cambia el comportamiento de la app: después de escribir el crash,
 * delega en el handler anterior (el de la JVM / Android).
 */
internal fun installCrashHandler(
    fs: FileSystem,
    crashFile: () -> FilePath,
    logger: Logger,
) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching {
            val path = crashFile()
            path.parent?.let { fs.createDirectories(it) }
            val text = buildString {
                append("=== CRASH ===\n")
                append("momento:   ${formatTimestamp(nowMillis())}\n")
                append("hilo:      ${thread.name}\n")
                append("excepción: ${error::class.qualifiedName}\n")
                append("mensaje:   ${error.message}\n")
                append("--- stack ---\n")
                append(error.stackTraceToString())
            }
            fs.appendText(path, text + "\n")
        }
        runCatching { logger.error(LogTags.APP, "cierre inesperado: ${error.message}", error) }
        previous?.uncaughtException(thread, error)
    }
}
```

- **Banner en el panel de diagnóstico.** Al arrancar, si existe
  `logs/crash.log`, el panel muestra arriba:
  *"La última sesión terminó de forma inesperada (2026-10-21 13:44:02). Ver"*
  con un botón que abre el crash y otro que lo incluye en el diagnóstico.

- **Regla de oro del crash report**: *si el crash no se puede reproducir en
  casa, no se arregla*. El crash local + el diagnóstico manual dan el
  suficiente contexto para reproducirlo en el 90% de los casos; un panel
  remoto sin datos de contexto no da más.

### 7.2 La puerta de atrás: telemetría institutional (futura, no ahora)

Si algún día la institución quiere telemetría, la forma correcta **no** es un
SaaS: es un endpoint en **Kitoru-Server** (que es de la institución), con
opt-in explícito del usuario, sólo agregados y sin contenido de chats. Se
deja anotado para que quede claro que la decisión es "no ahora, y no a un
tercero", no "no nunca".

---

## 8. Los tests de humo

### 8.1 Qué se busca

Los tests unitarios cubren cada pieza. Falta el test que **cubre el flujo
completo**: indexar materiales → recuperar → responder → persistir → loguear →
generar el diagnóstico. Es el tipo de bug que más se reporta ("el chat no
responde", "no encuentra nada en mi material") y el que más se escapa de los
tests por unidad.

### 8.2 Ubicación

`:backend` es el módulo que ve `:core` + `:llm-api` + `:backend`: es donde se
puede correr el flujo entero.

```
backend/src/desktopTest/kotlin/kitoru/smoke/
    SmokeHarness.kt          el andamiaje: datos temporales, cátedra, índice
    FakeGateway.kt           gateway falso, determinista
    TestCorpus.kt            corpus de prueba
    FlujoCompletoSmokeTest.kt   los tests
```

### 8.3 Las piezas

**`FakeGateway`** — un `LlmGateway` que responde con un guion y **no toca la
red**:

```kotlin
package kitoru.smoke

import llm.api.LlmChunk
import llm.api.LlmGateway
import llm.api.ToolSpec
import llm.config.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Gateway falso: responde con un guion y recuerda qué recibió.
 *
 * [script] decide la respuesta; [failWith] (si está) hace que la llamada
 * falle con esa excepción, para probar el camino de error.
 */
class FakeGateway(
    private val script: (List<ChatMessage>) -> String = { "Respuesta de prueba." },
    private val failWith: (() -> Throwable)? = null,
) : LlmGateway {

    /** Mensajes de la última petición: el test asserta sobre esto. */
    var lastRequest: List<ChatMessage> = emptyList()
        private set

    var calls: Int = 0
        private set

    override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> = flow {
        calls++
        lastRequest = messages
        failWith?.let { throw it() }
        // Se emite en trozos, como un stream de verdad: ejercita la
        // acumulación y hace que un bug de troceado se note.
        script(messages).chunked(7).forEach { emit(LlmChunk.Text(it)) }
    }

    override suspend fun listModels(): List<String> = listOf("modelo-falso")
}
```

**`TestCorpus`** — documentos de prueba en memoria, con la forma de un
material de cátedra (encabezados Markdown, varios documentos):

```kotlin
package kitoru.smoke

import rag.retrieval.Doc
import rag.retrieval.InMemorySource

/**
 * Corpus de prueba: tres documentos cortos con encabezados.
 *
 * El texto es fijo para que el test pueda afirmar qué fragmento tiene que
 * aparecer en el contexto.
 */
object TestCorpus {

    val docs: List<Doc> = listOf(
        Doc(
            source = "Algebra-I.pdf",
            path = "Algebra-I.pdf",
            text = """
                # Polinomios
                Un polinomio de grado n tiene n coeficientes.
                ## Teorema de la raíz racional
                Si p/q es raíz de un polinomio con coeficientes enteros,
                entonces p divide el término independiente y q divide el
                coeficiente principal.
            """.trimIndent(),
        ),
        Doc(
            source = "Analisis-I.pdf",
            path = "Analisis-I.pdf",
            text = """
                # Límites
                ## Límite lateral
                El límite lateral por la derecha puede existir sin que exista
                el límite por la izquierda.
            """.trimIndent(),
        ),
        Doc(
            source = "Geometria-I.pdf",
            path = "Geometria-I.pdf",
            text = """
                # Triángulos
                ## Ley de los senos
                En un triángulo, el cociente entre el lado y el seno de su
                ángulo opuesto es constante.
            """.trimIndent(),
        ),
    )

    fun source(): InMemorySource = InMemorySource(label = "prueba", docs = docs)
}
```

**`SmokeHarness`** — el andamiaje: un directorio temporal por test, la
cátedra sembrada y el índice construido.

```kotlin
package kitoru.smoke

import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kitoru.platform.systemFileSystem
import java.nio.file.Files

/**
 * Andamiaje del test de humo: una carpeta de datos real y temporal.
 *
 * Usa el [FileSystem] real (no un doble) porque lo que se quiere ejercitar
 * es justamente la escritura y relectura de los JSON.
 */
class SmokeHarness {

    /** Carpeta de datos del test (temporal). */
    val dataDir: FilePath

    val fs: FileSystem = systemFileSystem()

    init {
        val tmp = Files.createTempDirectory("kitoru-smoke").toFile()
        tmp.deleteOnExit()
        dataDir = FilePath(tmp.absolutePath)
    }

    /** Crea la carpeta de datos y devuelve la ruta. */
    fun prepare(): FilePath {
        fs.createDirectories(dataDir)
        return dataDir
    }
}
```

### 8.4 El test

```kotlin
package kitoru.smoke

import kitoru.diagnostics.DiagnosticOptions
import kitoru.diagnostics.DiagnosticReport
import kitoru.diagnostics.DiagnosticSnapshot
import kitoru.platform.log.LogLevel
import kitoru.platform.log.Logger
import kitoru.platform.log.LogRecord
import kitoru.platform.log.LogBuffer
import kitoru.platform.metrics.HealthMetrics
import kitoru.platform.nowMillis
import kotlinx.coroutines.test.runTest
import llm.error.LlmErrorCode
import llm.error.LlmException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Test de humo del flujo completo.
 *
 * Un test por comportamiento, en español, sin red y sin esperas
 * (las reglas de `docs/TESTING.md` del proyecto hermano).
 */
class FlujoCompletoSmokeTest {

    @Test
    fun `el flujo completo indexa, responde, persiste y deja rastro`() = runTest {
        val harness = SmokeHarness()
        harness.prepare()

        // 1. Corpus e índice.
        val kb = KnowledgeBase(RagConfig())
        kb.sources = listOf(TestCorpus.source())
        kb.sync { }
        assertTrue(kb.chunkCount > 0, "El índice debería tener fragmentos, tiene ${kb.chunkCount}.")

        // 2. Recuperación: la pregunta tiene que encontrar el fragmento correcto.
        val chunks = kb.retrieve("raíz racional de un polinomio", k = 3)
        assertTrue(
            chunks.isNotEmpty(),
            "La recuperación debería devolver fragmentos para una pregunta del corpus.",
        )
        assertTrue(
            chunks.any { it.text.contains("raíz racional") },
            "Ninguno de los fragmentos recuperados menciona la raíz racional: ${chunks.map { it.label }}",
        )

        // 3. Chat: el modelo ve el contexto y la respuesta se persiste.
        val gateway = FakeGateway { "Para factorizar, buscá una raíz racional." }
        val metrics = HealthMetrics()
        val buffer = LogBuffer()
        val logger = RecordingLogger(buffer)

        val engine = RagEngine(RagConfig(), kb, gateway) { "Sos un tutor de álgebra." }
        val prepared = engine.prepare("¿raíz racional?", emptyList(), useDocs = true)
        assertTrue(
            prepared.sources.isNotEmpty(),
            "El motor no recuperó fragmentos para una pregunta del corpus.",
        )
        assertTrue(
            prepared.messages.any { it.content.contains("raíz racional") },
            "El contexto que ve el modelo no tiene el fragmento del corpus.",
        )

        val answer = gateway.chat(listOf(ChatMessage("user", "¿raíz racional?")))
        assertTrue(answer.contains("raíz racional"), "La respuesta del gateway no llegó al chat.")
        assertEquals(1, gateway.calls, "El gateway debería haberse llamado una vez.")

        // 4. El log tiene la traza del flujo, y NO tiene la clave ni el texto del alumno.
        val rendered = buffer.render()
        assertTrue(rendered.isNotEmpty(), "El flujo debería haber dejado traza en el log.")
        assertFalse(
            rendered.contains("sk-secreta-de-prueba"),
            "El log no puede contener la API key: $rendered",
        )
        assertFalse(
            rendered.contains("texto privado del alumno"),
            "El log no puede contener el texto del alumno: $rendered",
        )

        // 5. Las métricas cuentan la llamada.
        assertEquals(1, metrics.snapshot().requests, "Las métricas deberían contar 1 petición.")
    }

    @Test
    fun `un error del gateway se clasifica, se cuenta y se registra`() = runTest {
        val gateway = FakeGateway(failWith = {
            LlmException(LlmErrorCode.SIN_CUOTA, "la API alcanzó su límite de uso (HTTP 429)", retryable = true)
        })
        val metrics = HealthMetrics()
        val buffer = LogBuffer()
        val logger = RecordingLogger(buffer)

        val error = runCatching { gateway.chat(listOf(ChatMessage("user", "hola"))) }
            .exceptionOrNull() as LlmException

        assertEquals(LlmErrorCode.SIN_CUOTA, error.code, "El código del error no se preservó.")
        assertTrue(error.retryable, "El error de cuota debería ser reintentable.")

        metrics.recordRequest(120, ok = false, errorCode = error.code.name)
        logger.error("Gateway", "fallo ${error.code.name}: ${error.message}", error)

        assertEquals(1, metrics.snapshot().errorsByCode["SIN_CUOTA"], "El error no se contó por código.")
        assertTrue(buffer.render().contains("[ERROR]"), "El error no quedó en el log.")
    }

    @Test
    fun `el diagnóstico incluye las secciones y nunca la clave`() {
        val snapshot = DiagnosticSnapshot(/* … con una ModelPolicy que tiene apiKey = "sk-secreta" … */)
        val text = DiagnosticReport.render(snapshot, DiagnosticOptions())
        assertTrue(text.contains("--- SALUD ---"), "Falta la sección de salud.")
        assertFalse(text.contains("sk-secreta"), "El diagnóstico no puede contener la API key.")
    }
}
```

`RecordingLogger` es un logger de prueba mínimo (en `SmokeHarness.kt`):

```kotlin
/** Logger de prueba: guarda en el buffer y no escribe en disco. */
class RecordingLogger(val buffer: LogBuffer = LogBuffer()) : Logger {
    override val minLevel: LogLevel = LogLevel.TRACE
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        buffer.append(LogRecord(nowMillis(), level, tag, message))
    }
}
```

### 8.5 Reglas del test de humo

- **Sin red**: el gateway es falso. Ni un `MockWebServer` ni un puerto abierto.
- **Sin esperas**: nada de `delay` ni `Thread.sleep`. El `runTest` de
  `kotlinx-coroutines-test` alcanza.
- **Sin el disco del usuario**: todo va a un directorio temporal que se
  borra al terminar.
- **Aislado**: si el flujo toca `KitoruPaths.setDataDir` (estado global), el
  test lo restaura al final. Mejor: el harness pasa la ruta explícitamente a
  los repositorios (`File*Repository` reciben `fs` + `path`), que es lo que ya
  permite `JsonStore`.
- **Mensajes que explican**: cada `assert` dice qué esperaba y qué obtuvo
  (regla 3 de `TESTING.md`).
- **Corre en CI**: `./gradlew :backend:test`.

### 8.6 Tests unitarios de la observabilidad

En `:core/src/desktopTest/kotlin/kitoru/platform/log/`:

| Test | Qué fija |
|---|---|
| `LoggerTest` | el filtro por nivel; que un `DEBUG` no se escribe con `minLevel = INFO`; que el `throwable` se agrega al render |
| `LogFormatTest` | **el contrato de formato**: la línea cumple la regexp de §4.1; el tag no tiene espacios; el nivel ocupa 5 columnas |
| `LogBufferTest` | el buffer circular descarta el más viejo al llegar al tope; `snapshot()` viene en orden cronológico |
| `RedactorTest` | `apiKey`, `authorization`, `Bearer`, `sk-…`, `https://user:pass@host`; que "tokens" (plural) **no** se redacta |
| `FileLogSinkTest` | con un `FileSystem` en memoria: la rotación al superar `maxBytes`; que no queden más de `keep + 1` archivos |

En `:core/src/desktopTest/kotlin/kitoru/platform/metrics/`:

| Test | Qué fija |
|---|---|
| `HealthMetricsTest` | los contadores; `errorsByCode`; que p50 ≤ p95 ≤ máx; `reset()` |

En `:core/src/desktopTest/kotlin/kitoru/diagnostics/`:

| Test | Qué fija |
|---|---|
| `DiagnosticReportTest` | que el reporte tiene las secciones esperadas; que **nunca** contiene la API key; que con `includeChat = false` no aparece el texto del alumno; que con `includeChat = true` aparece truncado a `maxMessageChars` |

`:core` necesita JUnit 5 en `desktopTest` (hoy sólo tiene `kotlin("test")`):

```kotlin
desktopTest.dependencies {
    implementation(libs.junit.jupiter)
    runtimeOnly(libs.junit.platform.launcher)
    implementation(libs.kotlinx.coroutines.test)
}
```

(mismo bloque que ya usa `:backend`).

---

## 9. El panel de diagnóstico dentro de la app

Es la pantalla que el docente abre cuando algo no anda: *"la app no encuentra
mi material"*, *"el modelo no contesta"*, *"¿qué configuración está usando?"*.

### 9.1 Estructura

Ruta: `kitoru://diagnostics`. Se llega desde **"Acerca de"** y desde el menú
de la cátedra.

```
┌────────────────────────────────────────────────────────────┐
│  Diagnóstico                                        [ × ]  │
├────────────────────────────────────────────────────────────┤
│  ⚠ La última sesión terminó de forma inesperada.  [ Ver ]  │  ← sólo si hay crash
├────────────────────────────────────────────────────────────┤
│  ESTADO                                                 ⋮   │
│  Versión      0.3.0 (build 42) · desktop                   │
│  Sistema      Windows 11 10.0.22631 (amd64)                │
│  Sesión       2 h 14 min abierta                           │
├────────────────────────────────────────────────────────────┤
│  ÍNDICE                                                      │
│  Estado       AL DÍA · 1284 fragmentos · 9 documentos       │
│  Última sync  2026-10-21 13:44:02 (2,1 s)                   │
├────────────────────────────────────────────────────────────┤
│  CONFIGURACIÓN EFECTIVA                                     │
│  Modo         Servidor de la cátedra                        │
│  Endpoint     https://ia.universidad.edu.ar/v1               │
│  Clave        configurada                                    │
│  Modelo       kitoru-alumno-7b                               │
│  Generación   temperature 0.20 · max_tokens 1024             │
│  RAG          top_k 5 · chunk_size 1200 · overlap 150        │
├────────────────────────────────────────────────────────────┤
│  SALUD DE ESTA SESIÓN                                        │
│  Peticiones   37  (36 ok · 1 error)                          │
│  Errores      SIN_CUOTA × 1                                  │
│  Latencia     media 1,8 s · p50 1,6 s · p95 4,9 s            │
│  Tokens       entrada 128 940 · salida 12 004                 │
├────────────────────────────────────────────────────────────┤
│  ÚLTIMOS ERRORES (2)                          [Copiar]       │
│  13:58:11  SIN_CUOTA  la API alcanzó su límite de uso      │
│  12:41:03  TIMEOUT    la petición superó 120000 ms          │
├────────────────────────────────────────────────────────────┤
│  LOG                                       [Copiar] [Borrar]│
│  [nivel ▾] [tag ▾] [buscar…]                               │
│  14:03:22.123 [WARN ] ChatEngine: el índice está vacío       │
│  14:03:21.330 [DEBUG] Rag: recuperación: 5 fragmentos…      │
│  …                                                         │
├────────────────────────────────────────────────────────────┤
│  [ Copiar diagnóstico ]   [ Guardar en archivo ]             │
└────────────────────────────────────────────────────────────┘
```

### 9.2 Las secciones

| Sección | Contenido | De dónde sale |
|---|---|---|
| **Estado** | versión, build, canal, SO, runtime, duración de la sesión | `KitoruBuild.info` + `platformInfo()` |
| **Índice** | estado (VACÍO/AL DÍA/PARCIAL/ERROR), nº de fragmentos, nº de documentos, última sync, duración, último error | `KnowledgeBase.status(): IndexInfo` (`:backend`) |
| **Configuración efectiva** | modo, endpoint, **clave: configurada/no configurada**, modelo, temperature, max_tokens, top_k, chunk_size, overlap, tools, timeout | `ModelPolicy` resuelta (`resolveFor`) + `RagConfig` |
| **Salud** | los contadores de `MetricsSnapshot` | `HealthMetrics.global.snapshot()` |
| **Últimos errores** | las últimas 10 entradas de `recentErrors` (timestamp, código, mensaje redactado) | `HealthMetrics` |
| **Log** | las últimas 200 líneas del buffer circular, con filtro por nivel, por tag y buscador | `Logger`/`LogBuffer` |

El log se muestra **desde el buffer en memoria** (no desde el archivo): es
instantáneo y no bloquea la UI con I/O. El archivo se usa para el historial
entre sesiones, que aparece en el reporte.

### 9.3 Acciones

| Acción | Qué hace |
|---|---|
| **Copiar diagnóstico** | abre `DiagnosticShareDialog` (§5.5) y copia el texto al portapapeles |
| **Guardar en archivo** | el mismo texto, guardado como `kitoru-diagnostico-2026-10-21-140322.txt` (desktop: `JFileChooser`; Android: `ACTION_CREATE_DOCUMENT`) |
| **Borrar logs** | borra `kitoru.log`, `kitoru.log.1`, `crash.log` y vacía el buffer; pide confirmación |
| **Ver crash** | muestra `logs/crash.log` en un diálogo de texto (sólo si existe) |
| **Copiar** (por sección) | copia sólo esa sección (útil: "pasame sólo los errores") |
| **Subir nivel de log a DEBUG** | cambia el nivel del logger en caliente (útil cuando el usuario va a reproducir el problema) |

El portapapeles se hace con `LocalClipboardManager` de Compose Multiplatform
(`androidx.compose.ui.platform.LocalClipboardManager`), que funciona igual
en desktop y en Android: no hace falta un `expect/actual`.

### 9.4 "Acerca de"

```
┌──────────────────────────────────────┐
│  Kitoru 0.3.0 (build 42)             │
│  Escritorio · Windows 11             │
│                                      │
│  Esta sesión                         │
│  37 peticiones · 1 error · 1,8 s     │
│  media de latencia                   │
│                                      │
│  Carpeta de datos                    │
│  C:\Users\…\.kitoru                  │
│                                      │
│  [ Diagnóstico ]  [ Carpeta de logs ]│
│  [ Borrar todos los datos ]          │
│  Licencias · Privacidad              │
└──────────────────────────────────────┘
```

El resumen de la sesión es un botón gigante: "37 peticiones · 1 error · 1,8 s
media" es la información que el usuario quiere antes de escribir el mail.

### 9.5 El "estado del índice" como dato de primera clase

Para que el panel pueda decir "AL DÍA · 1284 fragmentos", `:backend` tiene
que exponer el estado. Se agrega a `rag/retrieval/KnowledgeBase.kt`:

```kotlin
/**
 * Estado del índice: lo que el panel de diagnóstico muestra.
 *
 * `status` distingue el caso más reportado ("no encuentra nada en mi
 * material") de los otros.
 */
fun status(): IndexInfo = IndexInfo(
    status = when {
        lastError != null -> IndexInfo.IndexStatus.ERROR
        chunkCount == 0 -> IndexInfo.IndexStatus.VACIO
        sources.isEmpty() -> IndexInfo.IndexStatus.VACIO
        else -> IndexInfo.IndexStatus.AL_DIA
    },
    documents = sources.size,
    chunks = chunkCount,
    lastSyncAt = lastSyncAt,
    lastSyncDurationMs = lastSyncDurationMs,
    lastError = lastError,
)
```

`KnowledgeBase` gana tres campos privados (`lastSyncAt`, `lastSyncDurationMs`,
`lastError`) que se actualizan en `sync()`.

### 9.6 Quién arma la pantalla

`:ui`, paquete `kitoru.ui.diagnostics`:

| Archivo | Contenido |
|---|---|
| `DiagnosticsScreen.kt` | la pantalla (Compose), sus secciones |
| `DiagnosticsPresenter.kt` | junta el snapshot y expone un `StateFlow<DiagnosticsState>`; hace la gathering en `Dispatchers.Default` |
| `DiagnosticShareDialog.kt` | el diálogo de consentimiento (§5.5) |
| `LogViewer.kt` | la lista de logs con filtros |
| `MetricsCard.kt`, `IndexCard.kt`, `ModelCard.kt`, `ErrorsCard.kt`, `AppInfoCard.kt` | las tarjetas de la pantalla |
| `kitoru.ui.about.AboutScreen.kt` | la pantalla "Acerca de" |

El presenter expone:

```kotlin
data class DiagnosticsState(
    val loading: Boolean = true,
    val appInfo: AppInfo = AppInfo(),
    val course: CourseInfo? = null,
    val model: ModelInfo = ModelInfo(),
    val index: IndexInfo? = null,
    val health: MetricsSnapshot = MetricsSnapshot(0, 0, 0, 0, 0, emptyMap(), LatencyStats(), 0, 0, emptyMap()),
    val logs: List<LogRecord> = emptyList(),
    val crash: String? = null,
)
```

y acciones:

```kotlin
suspend fun refresh()
fun copyDiagnostic(options: DiagnosticOptions): String   // arma el texto y lo copia
fun clearLogs()
fun copySection(section: String)
```

La lógica de armado del texto **no** vive en la UI: vive en
`DiagnosticReport.render` (`:core`), que es una función pura y testeable. La
UI sólo la llama y pone el resultado en el portapapeles.

---

## 10. Nombres de archivos y clases

### 10.1 Archivos nuevos

| Ruta | Contenido principal |
|---|---|
| `core/src/commonMain/kotlin/kitoru/platform/log/LogLevel.kt` | `enum LogLevel` |
| `core/src/commonMain/kotlin/kitoru/platform/log/LogRecord.kt` | `data class LogRecord` |
| `core/src/commonMain/kotlin/kitoru/platform/log/Logger.kt` | `interface Logger`, `fun Logger.timed(...)` |
| `core/src/commonMain/kotlin/kitoru/platform/log/LogTags.kt` | `object LogTags` |
| `core/src/commonMain/kotlin/kitoru/platform/log/LogBuffer.kt` | `class LogBuffer` |
| `core/src/commonMain/kotlin/kitoru/platform/log/LogSink.kt` | `interface LogSink` |
| `core/src/commonMain/kotlin/kitoru/platform/log/DefaultLogger.kt` | `class DefaultLogger` |
| `core/src/commonMain/kotlin/kitoru/platform/log/FileLogSink.kt` | `class FileLogSink` |
| `core/src/commonMain/kotlin/kitoru/platform/log/Redactor.kt` | `object Redactor` |
| `core/src/commonMain/kotlin/kitoru/platform/log/TimeFormat.kt` | `expect fun formatTimestamp` |
| `core/src/commonMain/kotlin/kitoru/platform/log/KitoruLog.kt` | `object KitoruLog` |
| `core/src/jvmCommonMain/kotlin/kitoru/platform/log/TimeFormat.jvm.kt` | `actual fun formatTimestamp` |
| `core/src/jvmCommonMain/kotlin/kitoru/platform/log/CrashHandler.jvm.kt` | `installCrashHandler(...)` |
| `core/src/desktopMain/kotlin/kitoru/platform/log/Logger.desktop.kt` | `actual fun defaultLogger()` |
| `core/src/androidMain/kotlin/kitoru/platform/log/Logger.android.kt` | `actual fun defaultLogger()` |
| `core/src/commonMain/kotlin/kitoru/platform/BuildInfo.kt` | `data class BuildInfo`, `object KitoruBuild` |
| `core/src/commonMain/kotlin/kitoru/platform/PlatformInfo.kt` | `data class PlatformInfo`, `expect fun platformInfo()` |
| `core/src/jvmCommonMain/kotlin/kitoru/platform/PlatformInfo.jvm.kt` | `actual fun platformInfo()` |
| `core/src/commonMain/kotlin/kitoru/platform/metrics/HealthMetrics.kt` | `class HealthMetrics` |
| `core/src/commonMain/kotlin/kitoru/platform/metrics/MetricsSnapshot.kt` | `LatencyStats`, `ErrorInfo`, `MetricsSnapshot` |
| `core/src/commonMain/kotlin/kitoru/diagnostics/DiagnosticModels.kt` | `DiagnosticSnapshot`, `DiagnosticOptions`, `AppInfo`, `CourseInfo`, `ModelInfo`, `IndexInfo` |
| `core/src/commonMain/kotlin/kitoru/diagnostics/DiagnosticReport.kt` | `object DiagnosticReport` (render puro) |
| `core/src/commonMain/kotlin/kitoru/diagnostics/DiagnosticBuilder.kt` | `class DiagnosticBuilder` |
| `llm-api/src/commonMain/kotlin/llm/api/InstrumentedGateway.kt` | `class InstrumentedGateway` |
| `llm-api/src/commonMain/kotlin/llm/metrics/LlmMetrics.kt` | `fun HealthMetrics.recordLlmCall(...)` |
| `ui/src/commonMain/kotlin/kitoru/ui/diagnostics/DiagnosticsScreen.kt` | pantalla |
| `ui/src/commonMain/kotlin/kitoru/ui/diagnostics/DiagnosticsPresenter.kt` | `DiagnosticsState` + presenter |
| `ui/src/commonMain/kotlin/kitoru/ui/diagnostics/DiagnosticShareDialog.kt` | diálogo de consentimiento |
| `ui/src/commonMain/kotlin/kitoru/ui/diagnostics/LogViewer.kt` | visor de logs |
| `ui/src/commonMain/kotlin/kitoru/ui/diagnostics/Cards.kt` | `AppInfoCard`, `IndexCard`, `ModelCard`, `MetricsCard`, `ErrorsCard` |
| `ui/src/commonMain/kotlin/kitoru/ui/about/AboutScreen.kt` | "Acerca de" |

### 10.2 Archivos existentes que se tocan

| Archivo | Cambio |
|---|---|
| `core/src/commonMain/kotlin/kitoru/platform/KitoruPaths.kt` | agregar `logsDir` |
| `core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt` | agregar `appendText` y `move` |
| `core/src/jvmCommonMain/kotlin/kitoru/platform/FileSystem.jvm.kt` | implementar los dos métodos nuevos |
| `backend/src/commonMain/kotlin/rag/retrieval/KnowledgeBase.kt` | agregar `status(): IndexInfo` y el tracking de la última sync |
| `core/build.gradle.kts` | JUnit 5 en `desktopTest` |
| `ui/build.gradle.kts` | nada nuevo (Compose ya trae `LocalClipboardManager`) |

### 10.3 Tests

| Ruta | Test |
|---|---|
| `core/src/desktopTest/kotlin/kitoru/platform/log/LoggerTest.kt` | niveles, filtro, excepción en el render |
| `core/src/desktopTest/kotlin/kitoru/platform/log/LogFormatTest.kt` | el contrato de la línea (§4.4) |
| `core/src/desktopTest/kotlin/kitoru/platform/log/LogBufferTest.kt` | circularidad y orden |
| `core/src/desktopTest/kotlin/kitoru/platform/log/RedactorTest.kt` | las reglas de §5.2 |
| `core/src/desktopTest/kotlin/kitoru/platform/log/FileLogSinkTest.kt` | rotación |
| `core/src/desktopTest/kotlin/kitoru/platform/metrics/HealthMetricsTest.kt` | contadores y percentiles |
| `core/src/desktopTest/kotlin/kitoru/diagnostics/DiagnosticReportTest.kt` | secciones, ausencia de secretos, consentimiento |
| `backend/src/desktopTest/kotlin/kitoru/smoke/FlujoCompletoSmokeTest.kt` | el flujo end-to-end |
| `backend/src/desktopTest/kotlin/kitoru/smoke/SmokeHarness.kt` | `SmokeHarness`, `RecordingLogger` |
| `backend/src/desktopTest/kotlin/kitoru/smoke/FakeGateway.kt` | `FakeGateway` |
| `backend/src/desktopTest/kotlin/kitoru/smoke/TestCorpus.kt` | `TestCorpus` |

---

## 11. Checklist de implementación

1. `kitoru.platform.log`: niveles, registro, tags, buffer, sinks, redacción,
   `expect/actual` de `defaultLogger` y `formatTimestamp`.
2. `FileSystem.appendText` / `FileSystem.move` + `KitoruPaths.logsDir`.
3. `kitoru.platform.metrics.HealthMetrics` + la extensión tipada en `:llm-api`.
4. `InstrumentedGateway` (log + métricas) y los gauges del índice.
5. `kitoru.diagnostics`: modelos, `DiagnosticReport.render` (puro),
   `DiagnosticBuilder`.
6. `KnowledgeBase.status()`.
7. `:ui`: `DiagnosticsScreen`, `DiagnosticShareDialog`, `LogViewer`, `AboutScreen`.
8. Handler de crash local + banner en el panel.
9. Tests: unitarios de `:core` y el test de humo de `:backend`.
10. Cablear en el arranque de la app:
    `KitoruPaths.setDataDir(...)` → `KitoruBuild.set(...)` →
    `KitoruLog.install(...)` → `installCrashHandler(...)`.

### 11.1 Lo que este diseño NO hace (a propósito)

- No sube nada a ningún servidor.
- No guarda métricas entre sesiones.
- No usa Crashlytics ni ningún SDK de telemetría.
- No escribe el texto de los alumnos en ningún archivo.
- No manda emails ni abre tickets.
