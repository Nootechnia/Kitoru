package kitoru.platform.log

import kitoru.platform.FilePath
import kitoru.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests de la observabilidad: formato de la línea, redacción de secretos,
 * buffer circular, rotación del archivo e informe de diagnóstico.
 *
 * Todo corre en `commonTest` (es decir, en los dos targets) y sin disco
 * real: el `FileSystem` es el doble en memoria del `testkit`.
 */
class LoggerTest {

    // ------------------------------------------------------------------
    // Formato de la línea
    // ------------------------------------------------------------------

    /**
     * El contrato de formato (§4.1 del diseño): timestamp, nivel de 5
     * columnas, tag y mensaje. Si alguien cambia el formato, este test
     * falla: el formato es parte del contrato con el usuario (los bug
     * reports viejos siguen siendo legibles).
     */
    private val LINEA = Regex(
        """^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \[[A-Z ]{5}\] [^:\s]{1,24}: .+$""",
    )

    @Test
    fun `la linea tiene el formato exacto`() {
        val record = LogRecord(0L, LogLevel.WARN, "ChatEngine", "el índice está vacío")
        val line = record.render()

        assertTrue(LINEA.matches(line), "La línea no cumple el formato del contrato: $line")
        assertTrue(
            line.contains("[WARN ] ChatEngine: el índice está vacío"),
            "La línea no tiene la forma esperada: $line",
        )
    }

    @Test
    fun `el timestamp tiene el formato de fecha y hora con milisegundos`() {
        val ts = formatTimestamp(1_700_000_000_123L)

        assertTrue(
            Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$""").matches(ts),
            "El timestamp no tiene el formato esperado: $ts",
        )
    }

    @Test
    fun `el nivel ocupa siempre cinco columnas`() {
        for (level in LogLevel.values()) {
            val line = LogRecord(0L, level, "App", "mensaje").render()
            val nivel = line.substringAfter("[").substringBefore("]")

            assertEquals(5, nivel.length, "El nivel '$nivel' no ocupa 5 columnas: $line")
            assertEquals(level.label.padEnd(5), nivel, "El nivel no coincide con la etiqueta: $line")
        }
    }

    @Test
    fun `la excepcion se agrega al render, indentada`() {
        val record = LogRecord(
            timestampMillis = 0L,
            level = LogLevel.ERROR,
            tag = "Gateway",
            message = "fallo SIN_CUOTA",
            throwableText = "LlmException: la API alcanzó su límite\n    at x.y(z.kt:1)",
        )
        val line = record.render()

        // Con excepción el render es un bloque: la primera línea es la del
        // contrato y las de abajo son la continuación indentada.
        val cabecera = line.lineSequence().first()
        assertTrue(LINEA.matches(cabecera), "El render no cumple el formato del contrato: $cabecera")
        assertTrue(line.contains("[ERROR] Gateway: fallo SIN_CUOTA"), "Falta la cabecera: $line")
        assertTrue(line.contains("\n    LlmException:"), "La excepción no está indentada: $line")
    }

    @Test
    fun `el logger descarta lo que está por debajo del nivel mínimo`() {
        val buffer = LogBuffer()
        val logger = DefaultLogger(minLevel = LogLevel.INFO, sinks = emptyList(), buffer = buffer)

        logger.debug("App", "no debería aparecer")
        logger.info("App", "sí debería aparecer")

        val events = buffer.snapshot()
        assertEquals(1, events.size, "Sólo debería quedar el evento INFO: $events")
        assertEquals(LogLevel.INFO, events.first().level)
    }

    @Test
    fun `el logger redacta antes de construir el registro`() {
        val buffer = LogBuffer()
        val logger = DefaultLogger(minLevel = LogLevel.TRACE, sinks = emptyList(), buffer = buffer)

        logger.info("App", "apiKey=sk-secreta-de-prueba")

        val message = buffer.snapshot().single().message
        assertFalse(message.contains("sk-secreta-de-prueba"), "La clave llegó al buffer: $message")
        assertTrue(message.contains("REDACTED"), "La clave no fue reemplazada: $message")
    }

    // ------------------------------------------------------------------
    // Redacción de secretos
    // ------------------------------------------------------------------

    @Test
    fun `el redactor oculta la clave de la API`() {
        val salida = SecretRedactor.redact("apiKey=sk-abc123def456")

        assertFalse(salida.contains("sk-abc123def456"), "La clave quedó visible: $salida")
        assertTrue(salida.contains("REDACTED"), "La clave no fue reemplazada: $salida")
    }

    @Test
    fun `el redactor oculta la clave de la API en JSON`() {
        val salida = SecretRedactor.redact("\"api_key\": \"abc123xyz\"")

        assertFalse(salida.contains("abc123xyz"), "La clave quedó visible: $salida")
        assertTrue(salida.contains("REDACTED"), "La clave no fue reemplazada: $salida")
    }

    @Test
    fun `el redactor oculta el encabezado Authorization Bearer`() {
        val salida = SecretRedactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc")

        assertFalse(salida.contains("eyJhbGciOiJIUzI1NiJ9"), "El token quedó visible: $salida")
        assertTrue(salida.contains("REDACTED"), "El token no fue reemplazado: $salida")
    }

    @Test
    fun `el redactor oculta un token de ktor`() {
        val salida = SecretRedactor.redact("usó ktor_abc123def456 para la sesión")

        assertFalse(salida.contains("ktor_abc123def456"), "El token quedó visible: $salida")
        assertTrue(salida.contains("REDACTED"), "El token no fue reemplazado: $salida")
    }

    @Test
    fun `el redactor no rompe el texto normal`() {
        val texto = "el modelo agotó los tokens: 421 tokens de entrada"

        assertEquals(texto, SecretRedactor.redact(texto), "El redactor alteró una frase normal")
    }

    @Test
    fun `el redactor no rompe una URL sin credenciales`() {
        val texto = "endpoint: https://ia.universidad.edu.ar/v1"

        assertEquals(texto, SecretRedactor.redact(texto), "El redactor alteró una URL normal")
    }

    @Test
    fun `el redactor oculta las credenciales de una URL`() {
        val salida = SecretRedactor.redact("https://alumno:clave123@ia.univ.edu/v1")

        assertFalse(salida.contains("clave123"), "La clave de la URL quedó visible: $salida")
        assertTrue(salida.contains("ia.univ.edu"), "La URL se rompió: $salida")
    }

    // ------------------------------------------------------------------
    // Buffer circular
    // ------------------------------------------------------------------

    @Test
    fun `el buffer circular no crece de 500`() {
        val buffer = LogBuffer()

        repeat(600) { i ->
            buffer.append(LogRecord(i.toLong(), LogLevel.INFO, "App", "evento $i"))
        }

        val events = buffer.snapshot()
        assertEquals(500, events.size, "El buffer debería quedarse con 500 eventos: ${events.size}")
        assertEquals(100L, events.first().timestampMillis, "No descartó los más viejos")
        assertEquals(599L, events.last().timestampMillis, "No conservó los más nuevos")
    }

    @Test
    fun `el buffer circular conserva el orden cronológico`() {
        val buffer = LogBuffer(capacity = 3)

        buffer.append(LogRecord(1L, LogLevel.INFO, "App", "uno"))
        buffer.append(LogRecord(2L, LogLevel.INFO, "App", "dos"))
        buffer.append(LogRecord(3L, LogLevel.INFO, "App", "tres"))
        buffer.append(LogRecord(4L, LogLevel.INFO, "App", "cuatro"))

        assertEquals(
            listOf(2L, 3L, 4L),
            buffer.snapshot().map { it.timestampMillis },
            "El buffer no está en orden cronológico",
        )
    }

    @Test
    fun `el buffer se puede vaciar`() {
        val buffer = LogBuffer()
        buffer.append(LogRecord(1L, LogLevel.INFO, "App", "evento"))
        buffer.clear()

        assertTrue(buffer.snapshot().isEmpty(), "El buffer no quedó vacío")
    }

    // ------------------------------------------------------------------
    // Archivo y rotación
    // ------------------------------------------------------------------

    @Test
    fun `el archivo del log rota cuando supera el tope`() {
        val fs = InMemoryFileSystem()
        val path = FilePath("/datos/logs/kitoru.log")
        val sink = FileLogSink(
            fs = fs,
            pathProvider = { path },
            maxBytes = 500,
            keep = 1,
            flushThresholdBytes = 64,
        )

        repeat(200) { i ->
            val record = LogRecord(i.toLong(), LogLevel.INFO, "App", "línea número $i")
            sink.write(record, record.render())
        }
        // Un evento más después de la rotación: el archivo actual tiene que
        // volver a existir (la rotación lo dejó renombrado como ".1").
        val ultimo = LogRecord(999L, LogLevel.INFO, "App", "línea final")
        sink.write(ultimo, ultimo.render())
        sink.flush()

        assertTrue(fs.exists(path), "El archivo actual debería existir tras la rotación")
        assertTrue(fs.exists(FilePath("/datos/logs/kitoru.log.1")), "No se creó el archivo anterior")

        // Con `keep = 1` hay como máximo dos archivos: el actual y el anterior.
        val archivos = fs.listFiles(FilePath("/datos/logs")).map { it.name }.sorted()
        assertEquals(listOf("kitoru.log", "kitoru.log.1"), archivos, "Quedaron archivos de más: $archivos")

        // El archivo anterior conserva lo que había antes de la última
        // rotación y el actual, lo nuevo.
        assertTrue(
            fs.peek("/datos/logs/kitoru.log.1").orEmpty().isNotBlank(),
            "El archivo anterior quedó vacío",
        )
        assertTrue(
            fs.peek(path.value).orEmpty().contains("línea final"),
            "El archivo actual no tiene la última línea",
        )

        // El actual nunca debería pasar el tope por más de un lote.
        assertTrue(
            fs.sizeBytes(path) <= 500 + 64,
            "El archivo actual excedió el tope: ${fs.sizeBytes(path)} bytes",
        )
    }

    @Test
    fun `el archivo del log escribe una línea por evento`() {
        val fs = InMemoryFileSystem()
        val path = FilePath("/datos/logs/kitoru.log")
        val sink = FileLogSink(fs, { path }, flushThresholdBytes = 8)

        val primera = LogRecord(0L, LogLevel.INFO, "App", "primera")
        val segunda = LogRecord(0L, LogLevel.INFO, "App", "segunda")
        sink.write(primera, primera.render())
        sink.write(segunda, segunda.render())
        sink.flush()

        val contenido = fs.peek(path.value).orEmpty()
        assertEquals(2, contenido.trim().lines().size, "Cada evento debería ser una línea: $contenido")
        assertTrue(contenido.contains("primera"), "Falta la primera línea: $contenido")
        assertTrue(contenido.contains("segunda"), "Falta la segunda línea: $contenido")
    }

    @Test
    fun `el trace no se escribe en el archivo`() {
        val fs = InMemoryFileSystem()
        val path = FilePath("/datos/logs/kitoru.log")
        val sink = FileLogSink(fs, { path }, flushThresholdBytes = 8)

        val record = LogRecord(0L, LogLevel.TRACE, "App", "detalle fino")
        sink.write(record, record.render())
        sink.flush()

        val contenido = fs.peek(path.value).orEmpty()
        assertFalse(contenido.contains("detalle fino"), "El TRACE no debería ir al archivo: $contenido")
    }

    // ------------------------------------------------------------------
    // Métricas de salud
    // ------------------------------------------------------------------

    @Test
    fun `las métricas cuentan las peticiones y los errores por tipo`() {
        val metrics = HealthMetrics()

        repeat(100) { metrics.recordRequest(100, ok = true) }
        repeat(10) { metrics.recordRequest(5_000, ok = false, errorCode = "SIN_CUOTA") }

        val snapshot = metrics.snapshot()
        assertEquals(110L, snapshot.requests, "No contó las peticiones")
        assertEquals(100L, snapshot.requestsOk, "No contó las exitosas")
        assertEquals(10L, snapshot.requestsFailed, "No contó las fallidas")
        assertEquals(10L, snapshot.errorsByCode["SIN_CUOTA"], "No contó el error por tipo")
    }

    @Test
    fun `el percentil de latencia sale del histograma y ordena p50 p95 y máximo`() {
        val metrics = HealthMetrics()

        repeat(100) { metrics.recordRequest(100, ok = true) }
        repeat(10) { metrics.recordRequest(5_000, ok = false, errorCode = "SIN_CUOTA") }

        val latency = metrics.snapshot().latency
        assertEquals(110L, latency.count, "No contó las muestras")
        assertTrue(latency.p50Ms <= latency.p95Ms, "p50 (${latency.p50Ms}) > p95 (${latency.p95Ms})")
        assertTrue(latency.p95Ms <= latency.maxMs, "p95 (${latency.p95Ms}) > máx (${latency.maxMs})")
        assertEquals(5_000L, latency.maxMs, "El máximo no es el de la muestra más lenta")
        // Estimación sobre buckets: el p50 cae en el bucket de 100 ms y el p95
        // en el de 5 s.
        assertTrue(latency.p50Ms in 50..100, "El p50 estimado no cae en su bucket: ${latency.p50Ms}")
        assertTrue(latency.p95Ms in 2_000..5_000, "El p95 estimado no cae en su bucket: ${latency.p95Ms}")
    }

    @Test
    fun `las métricas se pueden reiniciar`() {
        val metrics = HealthMetrics()
        metrics.recordRequest(100, ok = true)
        metrics.recordTokens(10, 20)
        metrics.setGauge("index.chunks", 7)

        metrics.reset()

        val snapshot = metrics.snapshot()
        assertEquals(0L, snapshot.requests, "No reinició las peticiones")
        assertEquals(0L, snapshot.tokensIn, "No reinició los tokens")
        assertTrue(snapshot.gauges.isEmpty(), "No reinició los contadores libres")
    }

    // ------------------------------------------------------------------
    // Informe de diagnóstico
    // ------------------------------------------------------------------

    @Test
    fun `el diagnóstico tiene las secciones esperadas`() {
        val texto = DiagnosticReport.build(
            app = AppInfo(version = "0.3.0", build = "42", channel = "desktop", platform = "Windows 11"),
            course = CourseInfo(code = "4KX9", name = "Álgebra", role = "docente", enrollments = 34),
            index = IndexInfo(status = IndexInfo.Status.AL_DIA, documents = 9, chunks = 1284),
            model = ModelInfo(
                mode = "REMOTE",
                baseUrl = "https://ia.univ.edu/v1",
                apiKeyConfigured = true,
                modelName = "kitoru-alumno-7b",
            ),
            health = MetricsSnapshot(
                requests = 37,
                requestsOk = 36,
                requestsFailed = 1,
                errorsByCode = mapOf("SIN_CUOTA" to 1L),
                recentErrors = listOf(ErrorInfo(0L, "SIN_CUOTA", "la API alcanzó su límite de uso")),
            ),
            logs = listOf(LogRecord(0L, LogLevel.WARN, "ChatEngine", "el índice está vacío")),
        )

        assertTrue(texto.startsWith("=== KITORU · DIAGNÓSTICO ==="), "No empieza con el encabezado")
        assertTrue(texto.contains("--- CÁTEDRA ---"), "Falta la sección de la cátedra")
        assertTrue(texto.contains("--- ÍNDICE ---"), "Falta la sección del índice")
        assertTrue(texto.contains("--- MODELO (configuración efectiva) ---"), "Falta la sección del modelo")
        assertTrue(texto.contains("--- SALUD (esta sesión) ---"), "Falta la sección de salud")
        assertTrue(texto.contains("--- ÚLTIMOS ERRORES ---"), "Falta la sección de errores")
        assertTrue(texto.contains("--- LOG (sesión actual"), "Falta la sección del log")
        assertTrue(texto.contains("0.3.0"), "Falta la versión")
        assertTrue(
            Regex("""Clave:\s+configurada""").containsMatchIn(texto),
            "No dice si la clave está configurada: $texto",
        )
    }

    @Test
    fun `el diagnóstico no contiene secretos`() {
        val texto = DiagnosticReport.build(
            app = AppInfo(version = "0.3.0"),
            model = ModelInfo(
                mode = "REMOTE",
                baseUrl = "https://alumno:clave-secreta@ia.univ.edu/v1",
                apiKeyConfigured = true,
                modelName = "kitoru-alumno-7b",
            ),
            health = MetricsSnapshot(
                requests = 1,
                requestsFailed = 1,
                recentErrors = listOf(
                    ErrorInfo(0L, "SIN_CUOTA", "fallo con api_key=sk-secreta-de-prueba"),
                ),
            ),
            logs = listOf(
                LogRecord(0L, LogLevel.ERROR, "Gateway", "Authorization: Bearer abcdef1234567890"),
            ),
        )

        assertFalse(texto.contains("clave-secreta"), "La clave de la URL quedó en el informe")
        assertFalse(texto.contains("sk-secreta-de-prueba"), "La API key quedó en el informe")
        assertFalse(texto.contains("abcdef1234567890"), "El token Bearer quedó en el informe")
        assertTrue(texto.contains("REDACTED"), "No se.redactó nada: $texto")
    }

    @Test
    fun `el diagnóstico no incluye la ruta de datos sin consentimiento`() {
        val dataDir = FilePath("/home/alumno/.kitoru")

        val sinConsentimiento = DiagnosticReport.build(dataDir = dataDir)
        val conConsentimiento = DiagnosticReport.build(
            dataDir = dataDir,
            options = DiagnosticOptions(includeDataPath = true),
        )

        assertFalse(sinConsentimiento.contains("/home/alumno"), "La ruta quedó sin consentimiento")
        assertTrue(conConsentimiento.contains("/home/alumno"), "La ruta no aparece con consentimiento")
    }

    @Test
    fun `el diagnóstico omite las secciones sin datos`() {
        val texto = DiagnosticReport.build(app = AppInfo(version = "0.3.0"))

        assertFalse(texto.contains("--- CÁTEDRA ---"), "Aparece una sección vacía")
        assertFalse(texto.contains("--- ÍNDICE ---"), "Aparece una sección vacía")
        assertFalse(texto.contains("--- ÚLTIMOS ERRORES ---"), "Aparece una sección vacía")
        assertFalse(texto.contains("--- LOG ("), "Aparece una sección vacía")
    }
}
