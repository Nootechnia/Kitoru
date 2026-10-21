package kitoru.domain

import kitoru.platform.FilePath
import kitoru.platform.KitoruPaths
import kitoru.testing.Fixtures
import kitoru.testing.InMemoryFileSystem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests de regresión de los bugs de analítica y persistencia.
 *
 * Cada test fija **una** regla de negocio que estaba rota:
 *
 *  1. Un alumno con visibilidad `NONE` no puede filtrar qué material consultó.
 *  2. La actividad por día es de los alumnos, no de los docentes.
 *  3. Una persona con dos inscripciones es una sola fila.
 *  4. Una respuesta que no cita nada no es una respuesta fundamentada.
 *  5. Un alumno que pidió ocultamiento no aparece en ninguna lista.
 *  6. Borrar un hilo borra sus mensajes.
 *  7. El store escribe el archivo de forma atómica.
 *  8. Un elemento roto no hace perder toda la lista.
 */
class AnalyticsRegressionTest {

    // ------------------------------------------------------------------
    // 1. Fuga de visibilidad en el top de fuentes
    // ------------------------------------------------------------------

    @Test
    fun `las fuentes de un alumno oculto no aparecen en las mas consultadas`() {
        val ana = Fixtures.enrollment(userId = "alumno-1", displayName = "Ana")
        val beto = Fixtures.enrollment(
            userId = "alumno-2",
            displayName = "Beto",
            visibility = ChatVisibility.NONE,
        )
        val hilos = listOf(
            Fixtures.thread(id = "hilo-ana", authorId = "alumno-1"),
            Fixtures.thread(id = "hilo-beto", authorId = "alumno-2"),
        )
        val mensajes = listOf(
            Fixtures.userMessage(id = "m1", threadId = "hilo-ana"),
            Fixtures.assistantMessage(id = "m2", threadId = "hilo-ana", sources = listOf("apuntes.md")),
            Fixtures.userMessage(id = "m3", threadId = "hilo-beto"),
            Fixtures.assistantMessage(id = "m4", threadId = "hilo-beto", sources = listOf("secreto.md")),
        )

        val a = AnalyticsAnalyzer.analyze(Fixtures.course(), listOf(ana, beto), hilos, mensajes)

        assertEquals(
            listOf("apuntes.md"),
            a.topSources.map { it.label },
            "las fuentes de un alumno oculto no pueden delatar qué material consultó",
        )
    }

    // ------------------------------------------------------------------
    // 2. La actividad por día mezclaba docentes
    // ------------------------------------------------------------------

    @Test
    fun `la actividad por dia no incluye la actividad de los docentes`() {
        val titular = Fixtures.enrollment(
            userId = "docente-1",
            displayName = "Titular",
            role = CourseRole.TITULAR,
        )
        val ana = Fixtures.enrollment(userId = "alumno-1", displayName = "Ana")
        val hilos = listOf(
            Fixtures.thread(id = "hilo-docente", authorId = "docente-1"),
            Fixtures.thread(id = "hilo-ana", authorId = "alumno-1"),
        )
        val mensajes = listOf(
            Fixtures.userMessage(id = "m1", threadId = "hilo-docente", createdAt = 1_000L),
            Fixtures.assistantMessage(id = "m2", threadId = "hilo-docente", createdAt = 2_000L),
            Fixtures.userMessage(id = "m3", threadId = "hilo-ana", createdAt = 1_000L),
            Fixtures.assistantMessage(id = "m4", threadId = "hilo-ana", createdAt = 2_000L),
        )

        val a = AnalyticsAnalyzer.analyze(Fixtures.course(), listOf(titular, ana), hilos, mensajes)

        assertEquals(1, a.perDay.sumOf { it.messages }, "la actividad del docente no es de los alumnos")
        assertEquals(1, a.perDay.sumOf { it.replies })
        assertEquals(a.totalMessages, a.perDay.sumOf { it.messages }, "el gráfico tiene que cuadrar con el total")
    }

    // ------------------------------------------------------------------
    // 3. Inscripciones duplicadas duplicaban los totales
    // ------------------------------------------------------------------

    @Test
    fun `dos inscripciones del mismo alumno no duplican los totales`() {
        val ana = Fixtures.enrollment(userId = "alumno-1", displayName = "Ana")
        // La misma persona, inscrita dos veces en la misma cátedra.
        val anaComoAyudante = ana.copy(id = "catedra-1-alumno-1-2", role = CourseRole.AYUDANTE)
        val hilos = listOf(Fixtures.thread(id = "hilo-1", authorId = "alumno-1"))
        val mensajes = listOf(
            Fixtures.userMessage(id = "m1", threadId = "hilo-1"),
            Fixtures.assistantMessage(id = "m2", threadId = "hilo-1"),
        )

        val a = AnalyticsAnalyzer.analyze(
            Fixtures.course(),
            listOf(ana, anaComoAyudante),
            hilos,
            mensajes,
        )

        assertEquals(1, a.perStudent.size, "la misma persona es una sola fila")
        assertEquals(1, a.totalMessages, "sus mensajes no se cuentan dos veces")
        assertEquals(1, a.totalReplies)
        assertEquals(1, a.totalThreads)
    }

    // ------------------------------------------------------------------
    // 4. Una respuesta sin citas no es una respuesta fundamentada
    // ------------------------------------------------------------------

    @Test
    fun `una respuesta sin citas no cuenta como respuesta con fundamento`() {
        val ana = Fixtures.enrollment(userId = "alumno-1", displayName = "Ana")
        val hilos = listOf(Fixtures.thread(id = "hilo-1", authorId = "alumno-1"))
        val mensajes = listOf(
            Fixtures.userMessage(id = "m1", threadId = "hilo-1"),
            // El modelo tiene material en el contexto pero no cita nada.
            Fixtures.assistantMessage(id = "m2", threadId = "hilo-1", citation = null),
        )

        val a = AnalyticsAnalyzer.analyze(Fixtures.course(), listOf(ana), hilos, mensajes)

        assertEquals(
            1.0,
            a.perStudent.first().ungroundedAnswerRate,
            "no citar nada no es calidad perfecta: la respuesta no se puede sustentar",
        )
    }

    @Test
    fun `un alumno sin respuestas no tiene tasa de respuestas sin fundamento`() {
        val ana = Fixtures.enrollment(userId = "alumno-1", displayName = "Ana")

        val a = AnalyticsAnalyzer.analyze(Fixtures.course(), listOf(ana), emptyList(), emptyList())

        assertNull(
            a.perStudent.first().ungroundedAnswerRate,
            "sin respuestas no hay nada que evaluar: es sin datos, no 0 %",
        )
    }

    // ------------------------------------------------------------------
    // 5. Un alumno invisible no puede aparecer en ninguna lista
    // ------------------------------------------------------------------

    @Test
    fun `un alumno invisible no aparece en la lista de inactivos`() {
        val ana = Fixtures.enrollment(userId = "alumno-1", displayName = "Ana")
        // La misma persona, con una inscripción que pide ocultamiento.
        val anaOculta = ana.copy(
            id = "catedra-1-alumno-1-oculta",
            visibility = ChatVisibility.NONE,
        )

        val a = AnalyticsAnalyzer.analyze(
            Fixtures.course(),
            listOf(ana, anaOculta),
            emptyList(),
            emptyList(),
        )

        assertTrue(
            a.inactiveStudents.isEmpty(),
            "un alumno que pidió ocultamiento no puede aparecer en la lista de inactivos: ${a.inactiveStudents}",
        )
        assertTrue(a.perStudent.isEmpty(), "tampoco en el detalle por alumno")
    }

    @Test
    fun `un alumno con visibilidad NONE no aparece en ninguna lista`() {
        val beto = Fixtures.enrollment(
            userId = "alumno-2",
            displayName = "Beto",
            visibility = ChatVisibility.NONE,
        )

        val a = AnalyticsAnalyzer.analyze(Fixtures.course(), listOf(beto), emptyList(), emptyList())

        assertTrue(a.inactiveStudents.isEmpty(), "no es inactivo: está oculto")
        assertTrue(a.perStudent.isEmpty())
        assertEquals(0, a.students)
    }

    // ------------------------------------------------------------------
    // 6. Borrar un hilo tiene que borrar sus mensajes
    // ------------------------------------------------------------------

    @Test
    fun `borrar un hilo borra sus mensajes`() = runTest {
        val fs = InMemoryFileSystem()

        conDataDir("/datos") {
            val repo = FileMessageRepository(fs)
            repo.save(Fixtures.userMessage(id = "m1", threadId = "hilo-1"))
            repo.save(Fixtures.assistantMessage(id = "m2", threadId = "hilo-1"))
            repo.save(Fixtures.userMessage(id = "m3", threadId = "hilo-2"))

            repo.delete("hilo-1")

            assertTrue(repo.listForThread("hilo-1").isEmpty(), "los mensajes del hilo se borran")
            assertEquals(listOf("m3"), repo.listForThread("hilo-2").map { it.id }, "los de otro hilo no se tocan")
        }
    }

    // ------------------------------------------------------------------
    // 7. Escritura atómica
    // ------------------------------------------------------------------

    @Test
    fun `el store escribe el archivo de forma atomica`() = runTest {
        val fs = InMemoryFileSystem()
        val store = JsonListStore(fs, FilePath("/datos/lista.json"), ChatMessage.serializer())

        store.write(listOf(Fixtures.userMessage(id = "m1")))

        assertNull(fs.peek("/datos/lista.json.tmp"), "el temporal no puede quedar tirado")
        assertTrue(fs.peek("/datos/lista.json") != null, "el archivo bueno está en su lugar")
    }

    @Test
    fun `una escritura cortada deja el archivo anterior intacto`() = runTest {
        val fs = InMemoryFileSystem()
        val store = JsonListStore(fs, FilePath("/datos/lista.json"), ChatMessage.serializer())
        store.write(listOf(Fixtures.userMessage(id = "m1")))
        val bueno = fs.peek("/datos/lista.json")

        // Se corta la escritura: el temporal falla, el destino no se toca.
        fs.failOnWrite.add("/datos/lista.json.tmp")
        assertFailsWith<IllegalStateException> {
            store.write(listOf(Fixtures.userMessage(id = "m2")))
        }

        assertEquals(bueno, fs.peek("/datos/lista.json"), "el archivo anterior sigue intacto")
    }

    // ------------------------------------------------------------------
    // 8. Un elemento roto no puede hacer perder la lista entera
    // ------------------------------------------------------------------

    @Test
    fun `un elemento roto no hace perder toda la lista`() = runTest {
        val fs = InMemoryFileSystem()
        val store = JsonListStore(fs, FilePath("/datos/lista.json"), ChatMessage.serializer())
        // El segundo mensaje tiene la latencia como texto: no se puede leer.
        fs.put(
            "/datos/lista.json",
            """
            [
              {"id":"m1","threadId":"hilo-1","role":"user","text":"hola","createdAt":1},
              {"id":"m2","threadId":"hilo-1","role":"assistant","text":"hola","createdAt":2,"latencyMs":"mucho"}
            ]
            """.trimIndent(),
        )

        val lista = store.read()

        assertEquals(listOf("m1"), lista.map { it.id }, "se salvage el elemento sano")
    }

    /**
     * Fija la carpeta de datos mientras corre el bloque y la restaura después:
     * [KitoruPaths] es global y los tests no pueden dejar rastro.
     */
    private suspend fun <T> conDataDir(ruta: String, bloque: suspend () -> T): T {
        val anterior = KitoruPaths.dataDir
        KitoruPaths.setDataDir(FilePath(ruta))
        return try {
            bloque()
        } finally {
            KitoruPaths.setDataDir(anterior)
        }
    }
}
