package kitoru.domain

import kitoru.platform.FilePath
import kitoru.testing.InMemoryFileSystem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests del acumulador incremental de métricas ([AnalyticsAccumulator]).
 *
 * El acumulador es la **implementación de referencia** de las reglas de la
 * analítica: mantiene un cubo por usuario, se actualiza en O(1) por mensaje y
 * se "congela" en un [CourseAnalytics] aplicando la visibilidad.
 *
 * Lo que se verifica acá:
 *  - que el acumulador dé **exactamente** los mismos números que el analizador
 *    puro ([AnalyticsAnalyzer.analyze]), que es el oráculo;
 *  - que el camino incremental (mensaje por mensaje) sea indistinguible del
 *    camino de una sola pasada;
 *  - que la visibilidad se aplique en el *freeze* y no en la acumulación;
 *  - que las tasas estén siempre en rango y que no haya `NaN`;
 *  - que la caché en disco guarde, restaure e invalide correctamente.
 */
class AnalyticsAccumulatorTest {

    // ------------------------------------------------------------------
    // Corpus de prueba (determinista: ni reloj ni azar)
    // ------------------------------------------------------------------

    /** Medianoche UTC del 1 de enero de 2026. */
    private val dia0 = 1_767_225_600_000L

    /** Un día. */
    private val dia = 86_400_000L

    private fun catedra() = Course(
        id = "c1",
        code = "IA-2026-1",
        name = "Inteligencia Artificial",
    )

    private fun inscripción(
        userId: String,
        nombre: String,
        rol: CourseRole = CourseRole.ALUMNO,
        visibilidad: ChatVisibility = ChatVisibility.FULL,
    ) = Enrollment(
        id = "e-$userId",
        courseId = "c1",
        userId = userId,
        displayName = nombre,
        role = rol,
        visibility = visibilidad,
    )

    /**
     * Inscripciones de la cátedra.
     *
     * @param conOculto si `true` incluye a Cora, que pidió que el docente no
     *   vea nada de su actividad.
     */
    private fun inscripciones(conOculto: Boolean = false): List<Enrollment> = buildList {
        add(inscripción("u-doc", "Docente", CourseRole.TITULAR))
        add(inscripción("u-ayud", "Ayudante", CourseRole.AYUDANTE, ChatVisibility.METRICS_ONLY))
        add(inscripción("u-ana", "Ana"))
        add(inscripción("u-beto", "Beto", visibilidad = ChatVisibility.METRICS_ONLY))
        if (conOculto) add(inscripción("u-cora", "Cora", visibilidad = ChatVisibility.NONE))
    }

    private fun hilo(id: String, autorId: String) = ChatThread(
        id = id,
        courseId = "c1",
        authorId = autorId,
        authorName = autorId,
    )

    /** Hilos de la cátedra (el de Cora sólo existe si hay alumno oculto). */
    private fun hilos(conOculto: Boolean = false): List<ChatThread> = buildList {
        add(hilo("t-ana", "u-ana"))
        add(hilo("t-ana2", "u-ana"))
        add(hilo("t-beto", "u-beto"))
        // Un hilo abierto sin ningún mensaje: cuenta como conversación.
        add(hilo("t-vacio", "u-ana"))
        // El docente también chatea: su actividad no es de los alumnos.
        add(hilo("t-doc", "u-doc"))
        if (conOculto) add(hilo("t-cora", "u-cora"))
    }

    private fun mensaje(
        id: String,
        hiloId: String,
        rol: String = "user",
        at: Long,
        fuentes: List<String> = emptyList(),
        citas: CitationStats? = null,
        latenciaMs: Long = 0L,
        tokensIn: Int = 0,
        tokensOut: Int = 0,
        herramientas: List<ToolCallStats> = emptyList(),
    ) = ChatMessage(
        id = id,
        threadId = hiloId,
        role = rol,
        text = "texto de $id",
        createdAt = at,
        sources = fuentes,
        citation = citas,
        toolCalls = herramientas,
        latencyMs = latenciaMs,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
    )

    /** Mensajes de la cátedra. */
    private fun mensajes(conOculto: Boolean = false): List<ChatMessage> = buildList {
        add(mensaje("m1", "t-ana", at = dia0 + 3_600_000L, tokensIn = 20))
        add(
            mensaje(
                "m2", "t-ana", rol = "assistant", at = dia0 + 3_600_000L + 5_000L,
                fuentes = listOf("apuntes.md", "guia.pdf"),
                citas = CitationStats(verified = 2, unsupported = 1, unknown = 0),
                latenciaMs = 1_200L,
                tokensOut = 100,
                herramientas = listOf(
                    ToolCallStats("GetTime", ok = true),
                    ToolCallStats("CreateFile", ok = false),
                ),
            ),
        )
        add(mensaje("m3", "t-ana2", at = dia0 + dia + 7_200_000L, tokensIn = 15))
        add(
            mensaje(
                "m4", "t-ana2", rol = "assistant", at = dia0 + dia + 7_200_000L + 4_000L,
                fuentes = listOf("apuntes.md"),
                citas = CitationStats(verified = 1, unsupported = 0, unknown = 1),
                latenciaMs = 800L,
                tokensOut = 50,
            ),
        )
        // Rol desconocido: cuenta como actividad del día, no como mensaje.
        add(mensaje("m5", "t-beto", rol = "system", at = dia0 + 2 * dia))
        add(
            mensaje(
                "m6", "t-beto", rol = "assistant", at = dia0 + 2 * dia + 60_000L,
                fuentes = listOf("guia.pdf"),
                latenciaMs = 2_000L,
                tokensOut = 30,
            ),
        )
        // El docente chatea: su actividad no es de los alumnos.
        add(mensaje("m7", "t-doc", at = dia0 + 3 * dia, tokensIn = 4))
        add(
            mensaje(
                "m8", "t-doc", rol = "assistant", at = dia0 + 3 * dia + 10_000L,
                fuentes = listOf("guia.pdf"),
                citas = CitationStats(verified = 1),
                latenciaMs = 300L,
                tokensOut = 7,
            ),
        )
        if (conOculto) {
            add(mensaje("m10", "t-cora", at = dia0 + 2 * dia, tokensIn = 9))
            add(
                mensaje(
                    "m11", "t-cora", rol = "assistant", at = dia0 + 2 * dia + 30_000L,
                    fuentes = listOf("secreto.md"),
                    citas = CitationStats(verified = 1),
                    latenciaMs = 500L,
                    tokensOut = 10,
                ),
            )
        }
        // Un mensaje sin hilo no se le puede atribuir a nadie: se ignora.
        add(mensaje("m9", "hilo-fantasma", at = dia0 + 3 * dia))
    }

    /** Acumulador con todo el corpus cargado. */
    private fun acumulador(conOculto: Boolean = false): AnalyticsAccumulator {
        val acc = AnalyticsAccumulator(catedra(), inscripciones(conOculto))
        val hilos = hilos(conOculto)
        hilos.forEach(acc::onThread)
        for (m in mensajes(conOculto)) {
            val h = hilos.firstOrNull { it.id == m.threadId } ?: continue
            acc.add(h, m)
        }
        return acc
    }

    // ------------------------------------------------------------------
    // Comparaciones campo por campo
    // ------------------------------------------------------------------

    /** Compara dos analíticas campo por campo (para saber cuál difiere). */
    private fun assertAnalyticsIguales(expected: CourseAnalytics, actual: CourseAnalytics) {
        assertEquals(expected.courseId, actual.courseId, "courseId")
        assertEquals(expected.students, actual.students, "students")
        assertEquals(expected.activeStudents, actual.activeStudents, "activeStudents")
        assertEquals(expected.teachers, actual.teachers, "teachers")
        assertEquals(expected.totalMessages, actual.totalMessages, "totalMessages")
        assertEquals(expected.totalReplies, actual.totalReplies, "totalReplies")
        assertEquals(expected.totalThreads, actual.totalThreads, "totalThreads")
        assertEquals(expected.avgMessagesPerStudent, actual.avgMessagesPerStudent, "avgMessagesPerStudent")
        assertEquals(expected.citationSupportRate, actual.citationSupportRate, "citationSupportRate")
        assertEquals(expected.citationsVerified, actual.citationsVerified, "citationsVerified")
        assertEquals(expected.citationsUnsupported, actual.citationsUnsupported, "citationsUnsupported")
        assertEquals(expected.citationsUnknown, actual.citationsUnknown, "citationsUnknown")
        assertEquals(expected.avgLatencyMs, actual.avgLatencyMs, "avgLatencyMs")
        assertEquals(expected.totalTokens, actual.totalTokens, "totalTokens")
        assertEquals(expected.inactiveStudents, actual.inactiveStudents, "inactiveStudents")
        assertEquals(expected.perStudent.size, actual.perStudent.size, "perStudent.size")
        expected.perStudent.forEachIndexed { i, e ->
            assertAlumnoIgual(e, actual.perStudent[i], "perStudent[$i]")
        }
        assertEquals(expected.perDay, actual.perDay, "perDay")
        assertEquals(expected.topSources, actual.topSources, "topSources")
    }

    /** Compara las métricas de un alumno campo por campo. */
    private fun assertAlumnoIgual(expected: StudentAnalytics, actual: StudentAnalytics, contexto: String) {
        assertEquals(expected.userId, actual.userId, "$contexto.userId")
        assertEquals(expected.displayName, actual.displayName, "$contexto.displayName")
        assertEquals(expected.role, actual.role, "$contexto.role")
        assertEquals(expected.messages, actual.messages, "$contexto.messages")
        assertEquals(expected.replies, actual.replies, "$contexto.replies")
        assertEquals(expected.threads, actual.threads, "$contexto.threads")
        assertEquals(expected.activeDays, actual.activeDays, "$contexto.activeDays")
        assertEquals(expected.lastActivity, actual.lastActivity, "$contexto.lastActivity")
        assertEquals(expected.avgLatencyMs, actual.avgLatencyMs, "$contexto.avgLatencyMs")
        assertEquals(expected.tokensIn, actual.tokensIn, "$contexto.tokensIn")
        assertEquals(expected.tokensOut, actual.tokensOut, "$contexto.tokensOut")
        assertEquals(expected.citationsVerified, actual.citationsVerified, "$contexto.citationsVerified")
        assertEquals(expected.citationsUnsupported, actual.citationsUnsupported, "$contexto.citationsUnsupported")
        assertEquals(expected.citationsUnknown, actual.citationsUnknown, "$contexto.citationsUnknown")
        assertEquals(expected.toolCalls, actual.toolCalls, "$contexto.toolCalls")
        assertEquals(expected.toolFailures, actual.toolFailures, "$contexto.toolFailures")
        assertEquals(
            expected.answersWithUnsupportedCitations,
            actual.answersWithUnsupportedCitations,
            "$contexto.answersWithUnsupportedCitations",
        )
        assertEquals(expected.groundableReplies, actual.groundableReplies, "$contexto.groundableReplies")
        assertEquals(
            expected.answersWithoutGrounding,
            actual.answersWithoutGrounding,
            "$contexto.answersWithoutGrounding",
        )
    }

    // ==================================================================
    // A. Equivalencia con el analizador puro (el oráculo)
    // ==================================================================

    /**
     * El acumulador da **exactamente** los mismos números que el analizador
     * puro, campo por campo.
     */
    @Test
    fun `el acumulador reproduce exactamente al analizador`() {
        val course = catedra()
        val enrollments = inscripciones()
        val threads = hilos()
        val messages = mensajes()

        val esperado = AnalyticsAnalyzer.analyze(course, enrollments, threads, messages)
        val actual = AnalyticsAccumulator.analyze(course, enrollments, threads, messages)

        assertAnalyticsIguales(esperado, actual)
    }

    /**
     * Con un alumno oculto, el acumulador sigue dando los mismos números que
     * el analizador: la visibilidad se aplica en el freeze, así que el alumno
     * desaparece de todos los agregados (incluido el top de fuentes, que si no
     * filtraría qué material consultó).
     */
    @Test
    fun `con un alumno oculto el acumulador coincide con el analizador`() {
        val course = catedra()
        val enrollments = inscripciones(conOculto = true)
        val threads = hilos(conOculto = true)
        val messages = mensajes(conOculto = true)

        val esperado = AnalyticsAnalyzer.analyze(course, enrollments, threads, messages)
        val actual = AnalyticsAccumulator.analyze(course, enrollments, threads, messages)

        assertAnalyticsIguales(esperado, actual)
        assertTrue(
            actual.topSources.none { it.label == "secreto.md" },
            "las fuentes de un alumno oculto no se filtran: ${actual.topSources}",
        )
    }

    // ==================================================================
    // B. Camino incremental
    // ==================================================================

    /**
     * Agregar los mensajes de a uno y congelar da lo mismo que agregarlos
     * todos de una vez: el acumulador no depende del orden ni del momento en
     * que se congela.
     */
    @Test
    fun `agregar los mensajes de a uno da lo mismo que agregarlos todos`() {
        val course = catedra()
        val enrollments = inscripciones()
        val threads = hilos()
        val messages = mensajes()

        val acc = AnalyticsAccumulator(course, enrollments)
        threads.forEach(acc::onThread)

        var congeladoEnElCamino: CourseAnalytics? = null
        for (m in messages) {
            val h = threads.firstOrNull { it.id == m.threadId } ?: continue
            acc.add(h, m)
            // Congelar en el camino no tiene que alterar nada.
            congeladoEnElCamino = acc.freeze()
        }

        val final = acc.freeze()
        assertEquals(congeladoEnElCamino, final, "congelar durante la carga no cambia el resultado")
        assertAnalyticsIguales(
            AnalyticsAccumulator.analyze(course, enrollments, threads, messages),
            final,
        )
    }

    /**
     * El acumulador cuenta los mensajes que recibió y nada más: el trabajo
     * por mensaje es constante y el estado no depende del total.
     */
    @Test
    fun `el acumulador cuenta los mensajes que recibio`() {
        val acc = acumulador()

        assertEquals(8L, acc.messageCount, "los ocho mensajes con hilo; el huérfano no se cuenta")
        assertEquals(0L, acc.appendSeq, "sin secuencia del store, el seq queda en 0")
    }

    // ==================================================================
    // C. Visibilidad
    // ==================================================================

    /**
     * Un alumno con visibilidad `NONE` no aparece en ningún agregado; uno con
     * `METRICS_ONLY` aparece con todas sus métricas.
     */
    @Test
    fun `un alumno oculto no aparece y uno con metricas si`() {
        val a = acumulador(conOculto = true).freeze()

        assertTrue(a.perStudent.none { it.userId == "u-cora" }, "el alumno oculto no aparece")
        assertFalse(a.inactiveStudents.contains("Cora"), "el alumno oculto no está inactivo: está oculto")
        assertTrue(a.topSources.none { it.label == "secreto.md" }, "ni sus fuentes")
        assertEquals(2, a.students, "sólo Ana y Beto son alumnos visibles")

        val beto = a.perStudent.first { it.userId == "u-beto" }
        assertEquals(0, beto.messages)
        assertEquals(1, beto.replies, "con METRICS_ONLY el docente ve las métricas")
        assertEquals(1, beto.threads)
    }

    /**
     * La visibilidad se aplica en el **freeze**, no en la acumulación: cambiar
     * la visibilidad de un alumno no recalcula nada, sólo cambia lo que se
     * congela.
     */
    @Test
    fun `cambiar la visibilidad solo cambia el freeze`() {
        val ana = inscripción("u-ana", "Ana")
        val acc = AnalyticsAccumulator(catedra(), listOf(ana))
        val thread = hilo("t-ana", "u-ana")
        acc.onThread(thread)
        acc.add(thread, mensaje("m1", "t-ana", at = dia0))

        val antes = acc.freeze()
        assertEquals(1, antes.students)
        assertEquals(1, antes.totalMessages)
        val mensajesContados = acc.messageCount

        // Ana baja la visibilidad: el docente ya no ve sus estadísticas.
        acc.onEnrollment(ana.copy(visibility = ChatVisibility.NONE))

        val despues = acc.freeze()
        assertEquals(0, despues.students, "el alumno dejó de ser visible")
        assertEquals(0, despues.totalMessages)
        assertTrue(despues.perStudent.isEmpty())
        assertTrue(despues.perDay.isEmpty())
        assertTrue(despues.topSources.isEmpty())
        assertEquals(mensajesContados, acc.messageCount, "no se recalculó nada: sólo se re-congeló")
    }

    /**
     * El alumno ve sus propias métricas aunque su inscripción esté oculta
     * para el docente: la visibilidad gobierna lo que ve el docente, no lo
     * que ve el alumno sobre sí mismo.
     */
    @Test
    fun `el alumno ve sus metricas aunque este oculto para el docente`() {
        val acc = acumulador(conOculto = true)

        val cora = assertNotNull(acc.studentOf("u-cora"), "el alumno ve sus propias métricas")

        assertEquals(1, cora.messages)
        assertEquals(1, cora.replies)
        assertEquals(1, cora.threads)
    }

    // ==================================================================
    // D. Propiedades de rango e invariantes
    // ==================================================================

    /**
     * Los totales de la cátedra son la suma de los alumnos: el docente tiene
     * que poder reconciliar el número grande con el detalle.
     */
    @Test
    fun `los totales por alumno cuadran con el total de la catedra`() {
        val a = acumulador().freeze()

        val alumnos = a.perStudent.filter { it.role == CourseRole.ALUMNO }
        assertEquals(a.totalMessages, alumnos.sumOf { it.messages }, "mensajes")
        assertEquals(a.totalReplies, alumnos.sumOf { it.replies }, "respuestas")
        assertEquals(a.totalThreads, alumnos.sumOf { it.threads }, "hilos")
        assertEquals(a.totalTokens, alumnos.sumOf { it.tokensIn + it.tokensOut }, "tokens")
        assertEquals(a.citationsVerified, alumnos.sumOf { it.citationsVerified }, "citas verificadas")
        assertEquals(a.totalMessages, a.perDay.sumOf { it.messages }, "la actividad por día cuadra")
    }

    /**
     * Toda tasa está entre 0 y 1: es lo que la pantalla muestra como
     * porcentaje y un valor fuera de rango es un bug visible.
     */
    @Test
    fun `las tasas estan entre cero y uno`() {
        val a = acumulador().freeze()

        assertTrue(a.citationSupportRate in 0.0..1.0, "tasa de la cátedra: ${a.citationSupportRate}")
        for (s in a.perStudent) {
            assertTrue(s.citationSupportRate in 0.0..1.0, "${s.userId} citas: ${s.citationSupportRate}")
            val sinRespaldo = s.ungroundedAnswerRate
            if (sinRespaldo != null) {
                assertTrue(sinRespaldo in 0.0..1.0, "${s.userId} sin respaldo: $sinRespaldo")
            }
        }
    }

    /**
     * Sin denominador no hay `NaN`: una división por cero se muestra como 0
     * y una tasa que no se puede calcular es `null` ("sin datos"), no un
     * número inventado.
     */
    @Test
    fun `sin denominador las tasas son cero y no NaN`() {
        val a = AnalyticsAccumulator.analyze(catedra(), emptyList(), emptyList(), emptyList())

        assertEquals(0.0, a.avgMessagesPerStudent)
        assertFalse(a.avgMessagesPerStudent.isNaN(), "promedio por alumno")
        assertEquals(0.0, a.citationSupportRate)
        assertFalse(a.citationSupportRate.isNaN(), "tasa de citas")
        assertEquals(0L, a.avgLatencyMs)
        assertTrue(a.perStudent.isEmpty())

        // Un alumno que no recibió respuestas tampoco genera NaN.
        val solo = AnalyticsAccumulator.analyze(
            catedra(),
            listOf(inscripción("u-ana", "Ana")),
            listOf(hilo("t-ana", "u-ana")),
            listOf(mensaje("m1", "t-ana", at = dia0)),
        )
        val ana = solo.perStudent.single()
        assertNull(ana.ungroundedAnswerRate, "sin respuestas evaluables no hay tasa")
        assertEquals(0.0, ana.citationSupportRate)
        assertEquals(0L, ana.avgLatencyMs)
    }

    /**
     * Un alumno inscrito que nunca chateó aparece igual, con todo en cero y
     * como inactivo: es lo que el docente usa para el seguimiento.
     */
    @Test
    fun `un alumno sin mensajes aparece con cero`() {
        val a = AnalyticsAccumulator.analyze(
            catedra(),
            listOf(inscripción("u-ana", "Ana"), inscripción("u-beto", "Beto")),
            emptyList(),
            emptyList(),
        )

        assertEquals(2, a.students)
        assertEquals(0, a.activeStudents)
        assertEquals(listOf("Ana", "Beto"), a.inactiveStudents)
        assertEquals(0, a.totalMessages)
    }

    // ==================================================================
    // E. Caché en disco
    // ==================================================================

    /**
     * Guardar y cargar devuelve el mismo estado: la caché sirve para retomar
     * sin releer los mensajes.
     */
    @Test
    fun `la cache guarda y devuelve el mismo estado`() = runTest {
        val fs = InMemoryFileSystem()
        val cache = AnalyticsCache(fs, FilePath("cache/analytics"))
        val acc = acumulador()

        assertTrue(cache.save(acc), "la caché se guardó")

        val restaurado = assertNotNull(cache.load(catedra()), "la caché se cargó")
        assertAnalyticsIguales(acc.freeze(), restaurado.freeze())
        assertEquals(acc.messageCount, restaurado.messageCount)
        assertEquals(acc.appendSeq, restaurado.appendSeq)
    }

    /**
     * La marca de agua dice hasta dónde llegan los números guardados.
     */
    @Test
    fun `la cache guarda la marca de agua`() = runTest {
        val fs = InMemoryFileSystem()
        val cache = AnalyticsCache(fs, FilePath("cache/analytics"))
        val acc = acumulador()

        cache.save(acc)

        val wm = assertNotNull(cache.watermark(catedra().id), "hay marca de agua")
        assertEquals(AnalyticsCache.FORMAT_VERSION, wm.formatVersion)
        assertEquals(catedra().id, wm.courseId)
        assertEquals(acc.messageCount, wm.messageCount)
        assertEquals(acc.appendSeq, wm.appendSeq)
    }

    /**
     * Un cambio de versión invalida la caché: los números de dos formatos
     * distintos no son comparables, así que se descartan y se recalcula.
     */
    @Test
    fun `un cambio de version invalida la cache`() = runTest {
        val fs = InMemoryFileSystem()
        val carpeta = FilePath("cache/analytics")
        val cacheVieja = AnalyticsCache(fs, carpeta, formatVersion = 1)
        val cacheNueva = AnalyticsCache(fs, carpeta, formatVersion = 2)

        cacheVieja.save(acumulador())
        assertNotNull(cacheVieja.load(catedra()), "la caché de su propia versión se usa")

        assertNull(cacheNueva.load(catedra()), "la caché de otra versión no se usa")
        assertFalse(
            fs.exists(cacheVieja.pathFor(catedra().id)),
            "la caché inválida se descarta del disco",
        )
    }

    /**
     * Una caché corrupta (a medias, editada a mano) se descarta sin romper
     * nada: la caché es una optimización, nunca una fuente de verdad.
     */
    @Test
    fun `una cache corrupta se descarta sin romper nada`() = runTest {
        val fs = InMemoryFileSystem()
        val cache = AnalyticsCache(fs, FilePath("cache/analytics"))

        fs.put(cache.pathFor(catedra().id).value, "{ esto no es un json válido")

        assertNull(cache.load(catedra()), "la caché corrupta no se usa")
    }

    /**
     * Si no hay caché, `load` no falla: simplemente devuelve `null` para que
     * el motor recalcule.
     */
    @Test
    fun `sin cache no hay nada que cargar`() = runTest {
        val cache = AnalyticsCache(InMemoryFileSystem(), FilePath("cache/analytics"))

        assertNull(cache.load(catedra()))
    }
}
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          