package kitoru.platform.log

import kitoru.platform.FilePath
import kitoru.platform.KitoruPaths
import kitoru.platform.nowMillis

/**
 * Resumen de latencias de las peticiones medidas.
 *
 * Los percentiles son **estimaciones** hechas sobre un histograma de buckets
 * (ver [HealthMetrics]): no son valores exactos, y el informe lo dice
 * explícitamente para que nadie los interprete como precisión de producción.
 */
data class LatencyStats(
    /** Cantidad de peticiones medidas. */
    val count: Long = 0,
    val avgMs: Long = 0,
    val p50Ms: Long = 0,
    val p95Ms: Long = 0,
    val maxMs: Long = 0,
)

/** Un error reciente, listo para el informe. */
data class ErrorInfo(
    val timestampMillis: Long,
    /** Nombre del código (`LlmErrorCode.name` u otro). */
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    /** Nivel con el que se registró el error (para el informe). */
    val level: LogLevel = LogLevel.ERROR,
)

/** Foto de las métricas en un instante. Inmutable. */
data class MetricsSnapshot(
    /** Cuándo arrancó la sesión de métricas. */
    val sinceMillis: Long = 0,
    val uptimeMs: Long = 0,
    val requests: Long = 0,
    val requestsOk: Long = 0,
    val requestsFailed: Long = 0,
    /** Errores por código: "SIN_CUOTA" → 3. */
    val errorsByCode: Map<String, Long> = emptyMap(),
    val latency: LatencyStats = LatencyStats(),
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    /** Contadores libres: "index.chunks" → 1284, "materials" → 12. */
    val gauges: Map<String, Long> = emptyMap(),
    /** Últimos errores (más reciente al final). */
    val recentErrors: List<ErrorInfo> = emptyList(),
) {
    companion object {
        /** Foto vacía: el informe puede generarse sin métricas. */
        val EMPTY = MetricsSnapshot()
    }
}

/**
 * Contadores de salud en memoria.
 *
 * Sin dependencias, sin reloj propio (usa `kitoru.platform.nowMillis`) y con
 * un **histograma de buckets** para las latencias: no se guardan las
 * muestras individuales (serían 200 longs por sesión, y peor: el percentil
 * exacto no dice nada más que el aproximado), sino cuántos valores caen en
 * cada rango. El p50 y el p95 salen de ahí, con una interpolación dentro del
 * bucket.
 *
 * Las métricas son de **la sesión actual**: al reiniciar la app se reinician
 * los contadores. No hay base de datos de telemetría (mandar el uso a un
 * servidor sería una fuga que la institución no autorizó).
 */
class HealthMetrics(
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
    private var totalLatencyMs = 0L
    private var maxLatencyMs = 0L
    private var samples = 0L

    private val errorsByCode = mutableMapOf<String, Long>()
    private val gauges = mutableMapOf<String, Long>()
    private val errors = ArrayDeque<ErrorInfo>()

    /** Contadores por bucket de latencia (el último es "todo lo demás"). */
    private val bucketCounts = LongArray(LATENCY_BUCKETS.size + 1)

    /** Registra el resultado de una petición al modelo. */
    fun recordRequest(latencyMs: Long, ok: Boolean, errorCode: String? = null) = synchronized(lock) {
        requests++
        if (ok) requestsOk++ else requestsFailed++
        if (errorCode != null) {
            errorsByCode[errorCode] = (errorsByCode[errorCode] ?: 0L) + 1
        }
        val safe = if (latencyMs < 0) 0L else latencyMs
        totalLatencyMs += safe
        if (safe > maxLatencyMs) maxLatencyMs = safe
        samples++
        bucketCounts[bucketIndex(safe)]++
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
        MetricsSnapshot(
            sinceMillis = startedAt,
            uptimeMs = nowMillis() - startedAt,
            requests = requests,
            requestsOk = requestsOk,
            requestsFailed = requestsFailed,
            errorsByCode = errorsByCode.toMap(),
            latency = LatencyStats(
                count = samples,
                avgMs = if (samples == 0L) 0L else totalLatencyMs / samples,
                p50Ms = percentile(0.50),
                p95Ms = percentile(0.95),
                maxMs = maxLatencyMs,
            ),
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            gauges = gauges.toMap(),
            recentErrors = errors.toList(),
        )
    }

    /** Pone los contadores en cero (tests, o "reiniciar métricas" del panel). */
    fun reset() = synchronized(lock) {
        requests = 0L
        requestsOk = 0L
        requestsFailed = 0L
        tokensIn = 0L
        tokensOut = 0L
        totalLatencyMs = 0L
        maxLatencyMs = 0L
        samples = 0L
        errorsByCode.clear()
        gauges.clear()
        errors.clear()
        bucketCounts.fill(0L)
    }

    /** En qué bucket cae una latencia. */
    private fun bucketIndex(millis: Long): Int {
        for (i in LATENCY_BUCKETS.indices) {
            if (millis <= LATENCY_BUCKETS[i]) return i
        }
        return LATENCY_BUCKETS.size
    }

    /**
     * Percentil estimado sobre el histograma.
     *
     * Se caminan los buckets en orden hasta llegar al que contiene el
     * percentil, y se interpola dentro de él. Es una estimación, pero con
     * buckets de 50 ms a 10 s la diferencia con el valor real es de milisegundos.
     */
    private fun percentile(p: Double): Long {
        if (samples == 0L) return 0L
        val target = p * samples
        var cumulative = 0L
        for (i in bucketCounts.indices) {
            val count = bucketCounts[i]
            if (count == 0L) continue
            if (cumulative + count >= target) {
                val lower = if (i == 0) 0L else LATENCY_BUCKETS[i - 1]
                val upper = if (i < LATENCY_BUCKETS.size) LATENCY_BUCKETS[i] else lower
                val fraction = ((target - cumulative) / count.toDouble()).coerceIn(0.0, 1.0)
                return (lower + (upper - lower) * fraction).toLong()
            }
            cumulative += count
        }
        return maxLatencyMs
    }

    companion object {
        /**
         * Bordes superiores de los buckets de latencia, en milisegundos.
         *
         * El último bucket es "todo lo que pase de 100 s", que en la práctica
         * es un timeout.
         */
        val LATENCY_BUCKETS = longArrayOf(
            50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000,
        )

        /** Instancia global de la app. */
        val global = HealthMetrics()
    }
}

/** Datos de la app para el informe. */
data class AppInfo(
    val version: String = "",
    val build: String = "",
    val channel: String = "",
    /** Sistema operativo: "Windows 11 10.0.22631 (amd64)". */
    val platform: String = "",
    /** Runtime: "OpenJDK 21.0.5". */
    val runtime: String = "",
    /** Zona horaria local: "America/Argentina/Buenos_Aires". */
    val timeZone: String = "",
)

/** Datos de la cátedra para el informe. */
data class CourseInfo(
    val code: String = "",
    val name: String = "",
    /** Rol del usuario en la cátedra: "docente", "alumno"… */
    val role: String = "",
    val enrollments: Int = 0,
    /** Inscripciones cuya visibilidad incluye el texto de los chats. */
    val enrollmentsWithText: Int = 0,
    val materials: Int = 0,
    val materialsWithText: Int = 0,
    val materialsWithWarning: Int = 0,
)

/** Estado del índice de una cátedra. */
data class IndexInfo(
    val status: Status = Status.VACIO,
    val documents: Int = 0,
    val chunks: Int = 0,
    val lastSyncAt: Long = 0,
    val lastSyncDurationMs: Long = 0,
    val lastError: String? = null,
) {
    enum class Status(val label: String) {
        VACIO("VACÍO"),
        AL_DIA("AL DÍA"),
        PARCIAL("PARCIAL"),
        ERROR("ERROR"),
    }
}

/**
 * Configuración efectiva del modelo.
 *
 * La API key **no** está: sólo el dato de si está configurada. Un informe
 * que no puede contener el secreto ni por accidente es un informe que se
 * puede pegar en un ticket sin pensarlo dos veces.
 */
data class ModelInfo(
    val mode: String = "",
    val baseUrl: String = "",
    val apiKeyConfigured: Boolean = false,
    val modelName: String = "",
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topK: Int? = null,
    val chunkSize: Int? = null,
    val chunkOverlap: Int? = null,
    val toolsEnabled: Boolean = false,
    val timeoutMs: Long? = null,
)

/** Qué se incluye en el informe. */
data class DiagnosticOptions(
    /**
     * Incluir la ruta absoluta de la carpeta de datos.
     *
     * Por defecto no: la ruta puede mostrar el nombre de usuario del
     * alumno, y el informe se pega en sitios públicos.
     */
    val includeDataPath: Boolean = false,
    /** Cuántas líneas de log se incluyen como máximo. */
    val maxLogLines: Int = 40,
    /** Cuántos errores recientes se incluyen como máximo. */
    val maxErrors: Int = 10,
)

/**
 * El informe de diagnóstico: un texto plano por secciones.
 *
 * "Copiar diagnóstico" genera este texto y lo manda al portapapeles. Nadie
 * va a correr `jq` sobre el log de un alumno: lo lee un docente o un
 * desarrollador, de un tirón, en un ticket.
 *
 * Todo el texto pasa por [SecretRedactor] al final: el informe puede incluir
 * mensajes de error del proveedor (que a veces traen el cuerpo crudo de la
 * respuesta) y no por eso debe filtrar una clave.
 */
object DiagnosticReport {

    /** Ancho de la columna de etiquetas, para que el informe se lea alineado. */
    private const val LABEL_WIDTH = 15

    /**
     * Arma el informe.
     *
     * @param app versión y plataforma.
     * @param course datos de la cátedra (opcional).
     * @param index estado del índice (opcional).
     * @param model configuración efectiva del modelo (opcional).
     * @param health métricas de la sesión.
     * @param logs últimos eventos del log (del buffer circular).
     * @param dataDir carpeta de datos: se muestra sólo el nombre salvo que
     *        [DiagnosticOptions.includeDataPath] lo autorice.
     * @param options qué se incluye.
     */
    fun build(
        app: AppInfo = AppInfo(),
        course: CourseInfo? = null,
        index: IndexInfo? = null,
        model: ModelInfo? = null,
        health: MetricsSnapshot = MetricsSnapshot.EMPTY,
        logs: List<LogRecord> = emptyList(),
        dataDir: FilePath = KitoruPaths.dataDir,
        options: DiagnosticOptions = DiagnosticOptions(),
    ): String {
        val sb = StringBuilder()

        sb.appendLine("=== KITORU · DIAGNÓSTICO ===")
        val generated = "${formatTimestamp(nowMillis())}"
        if (app.timeZone.isNotBlank()) sb.appendLine("Generado: $generated (${app.timeZone})") else sb.appendLine("Generado: $generated")
        val version = buildString {
            append(app.version.ifBlank { "desconocida" })
            if (app.build.isNotBlank()) append(" (build ${app.build})")
            if (app.channel.isNotBlank()) append(" · canal: ${app.channel}")
        }
        field(sb, "Versión", version)
        if (app.platform.isNotBlank()) field(sb, "Plataforma", app.platform)
        if (app.runtime.isNotBlank()) field(sb, "Runtime", app.runtime)
        field(sb, "Carpeta de datos", dataFolder(dataDir, options.includeDataPath))

        if (course != null) {
            sb.appendLine()
            sb.appendLine("--- CÁTEDRA ---")
            field(sb, "Curso", "${course.code} - ${course.name}".trim().trim('-').trim())
            if (course.role.isNotBlank()) field(sb, "Rol", course.role)
            field(
                sb, "Inscripciones",
                "${course.enrollments} (${course.enrollmentsWithText} con visibilidad de texto)",
            )
            field(
                sb, "Materiales",
                "${course.materials} (${course.materialsWithText} con texto extraído) · " +
                    "${course.materialsWithWarning} con advertencia",
            )
        }

        if (index != null) {
            sb.appendLine()
            sb.appendLine("--- ÍNDICE ---")
            field(sb, "Estado", "${index.status.label} · ${index.chunks} fragmentos de ${index.documents} documentos")
            if (index.lastSyncAt > 0) {
                field(
                    sb, "Última sync",
                    "${formatTimestamp(index.lastSyncAt)} (${formatMs(index.lastSyncDurationMs)})",
                )
            }
            index.lastError?.takeIf { it.isNotBlank() }?.let { field(sb, "Último error", it) }
        }

        if (model != null) {
            sb.appendLine()
            sb.appendLine("--- MODELO (configuración efectiva) ---")
            if (model.mode.isNotBlank()) field(sb, "Modo", model.mode)
            if (model.baseUrl.isNotBlank()) field(sb, "Endpoint", model.baseUrl)
            field(sb, "Clave", if (model.apiKeyConfigured) "configurada" else "no configurada")
            if (model.modelName.isNotBlank()) field(sb, "Modelo", model.modelName)
            val generation = buildList {
                model.temperature?.let { add("temperature ${formatDecimal(it)}") }
                model.maxTokens?.let { add("max_tokens $it") }
                add(if (model.toolsEnabled) "herramientas habilitadas" else "sin herramientas")
            }.joinToString(" · ")
            field(sb, "Generación", generation)
            val rag = buildList {
                model.topK?.let { add("top_k $it") }
                model.chunkSize?.let { add("chunk_size $it") }
                model.chunkOverlap?.let { add("overlap $it") }
            }.joinToString(" · ")
            if (rag.isNotBlank()) field(sb, "RAG", rag)
            field(sb, "Timeout", model.timeoutMs?.let { formatMs(it) } ?: "sin límite")
        }

        sb.appendLine()
        sb.appendLine("--- SALUD (esta sesión) ---")
        field(sb, "Peticiones", "${health.requests} (${health.requestsOk} ok · ${health.requestsFailed} error)")
        val errorsByCode = health.errorsByCode.entries
            .sortedByDescending { it.value }
            .joinToString(" · ") { "${it.key} × ${it.value}" }
        if (errorsByCode.isNotBlank()) field(sb, "Errores", errorsByCode)
        field(
            sb, "Latencia",
            "n=${health.latency.count} · media ${formatMs(health.latency.avgMs)} · " +
                "p50 ${formatMs(health.latency.p50Ms)} · p95 ${formatMs(health.latency.p95Ms)} · " +
                "máx ${formatMs(health.latency.maxMs)}",
        )
        field(sb, "Tokens", "entrada ${groupDigits(health.tokensIn)} · salida ${groupDigits(health.tokensOut)}")
        if (health.gauges.isNotEmpty()) {
            field(sb, "Contadores", health.gauges.entries.joinToString(" · ") { "${it.key} ${it.value}" })
        }

        val recentErrors = health.recentErrors.takeLast(options.maxErrors)
        if (recentErrors.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("--- ÚLTIMOS ERRORES ---")
            recentErrors.forEachIndexed { i, error ->
                sb.appendLine(
                    "${i + 1}. ${formatTimestamp(error.timestampMillis)} [${error.level.label}] " +
                        "${error.code}: ${error.message}",
                )
            }
        }

        val lastLogs = logs.takeLast(options.maxLogLines)
        if (lastLogs.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("--- LOG (sesión actual, últimas ${lastLogs.size} líneas) ---")
            lastLogs.forEach { sb.appendLine(it.render()) }
        }

        sb.appendLine()
        sb.appendLine("--- FIN DEL DIAGNÓSTICO ---")
        sb.appendLine(
            "Generado localmente por Kitoru ${app.version.ifBlank { "" }}".trim() +
                ". No se envió nada a ningún servidor.",
        )

        // Última barrera: aunque algo se colara en un dato suelto, el informe
        // entero pasa por el redactor.
        return SecretRedactor.redact(sb.toString())
    }

    /** Escribe una línea "etiqueta: valor" con la etiqueta alineada. */
    private fun field(sb: StringBuilder, label: String, value: String) {
        sb.append(("$label:").padEnd(LABEL_WIDTH)).appendLine(value)
    }

    /**
     * Carpeta de datos.
     *
     * Sin consentimiento se muestra sólo el último nombre de la carpeta (que
     * no identifica a nadie); con consentimiento, la ruta completa.
     */
    private fun dataFolder(dataDir: FilePath, includeFullPath: Boolean): String {
        if (includeFullPath) return dataDir.value
        val name = dataDir.value.trimEnd('/', '\\').substringAfterLast('/', "").substringAfterLast('\\', "")
        return if (name.isBlank()) "(no incluida)" else "$name (ruta no incluida)"
    }

    /** Miles con espacio: 1284 → "1 284" (formato español, sin punto). */
    private fun groupDigits(value: Long): String {
        if (value < 0) return "-${groupDigits(-value)}"
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val sb = StringBuilder()
        var count = 0
        for (i in digits.indices.reversed()) {
            sb.append(digits[i])
            count++
            if (count % 3 == 0 && i != 0) sb.append(' ')
        }
        return sb.reverse().toString()
    }

    /** Milisegundos: "1 842 ms" si es menos de 10 s, "2,1 s" si no. */
    private fun formatMs(millis: Long): String = when {
        millis < 10_000 -> "${groupDigits(millis)} ms"
        else -> "${millis / 1000},${(millis % 1000) / 100} s"
    }

    /** Decimales con coma: 0.2 → "0,20". */
    private fun formatDecimal(value: Double): String {
        val hundredths = (value * 100).toLong()
        return "${hundredths / 100},${(hundredths % 100).toString().padStart(2, '0')}"
    }
}
                                                                                                                                                                                                                                                                          