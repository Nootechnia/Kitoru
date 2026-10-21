package rag.analytics

import kitoru.domain.ChatMessage
import kitoru.domain.ChatThread
import kitoru.domain.CitationStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests del detector de preguntas repetidas.
 *
 * Lo que se verifica acá es lo que el docente va a ver: qué preguntas se
 * agrupan, cuáles son un hueco del material y qué se le dice que haga al
 * respecto.
 */
class QuestionSimilarityTest {

    // ------------------------------------------------------------------
    // Similitud
    // ------------------------------------------------------------------

    @Test
    fun `dos preguntas identicas dan similitud 1`() {
        val index = QuestionSimilarity.of(corpusDePrueba())

        val similarity = index.similarity(AUTOMATA, AUTOMATA)

        assertEquals(1.0, similarity, 1e-9, "la misma pregunta tiene que dar 1")
    }

    @Test
    fun `la parafrasis de una pregunta supera el umbral`() {
        val index = QuestionSimilarity.of(corpusDePrueba())

        val similarity = index.similarity(AUTOMATA, QUE_ES_AUTOMATA)

        assertTrue(
            similarity >= UMBRAL,
            "la paráfrasis tiene que pasar el umbral (obtenido: $similarity)",
        )
    }

    @Test
    fun `preguntas del mismo tema pero distintas no pasan el umbral`() {
        val index = QuestionSimilarity.of(corpusDePrueba())

        val similarity = index.similarity(AUTOMATA, DIFERENCIA)

        assertTrue(
            similarity < UMBRAL,
            "mismo tema no es la misma pregunta (obtenido: $similarity)",
        )
    }

    @Test
    fun `la idf de un termino que no aparece en ninguna otra pregunta es alta`() {
        val index = QuestionSimilarity.of(corpusDePrueba())

        val unico = index.idf("turing")
        val generico = index.idf("define")

        assertTrue(
            unico > generico,
            "un término único pesa más que uno genérico (único: $unico, genérico: $generico)",
        )
    }

    @Test
    fun `la similitud es simetrica`() {
        val index = QuestionSimilarity.of(corpusDePrueba())

        assertEquals(
            index.similarity(AUTOMATA, DIFERENCIA),
            index.similarity(DIFERENCIA, AUTOMATA),
            1e-12,
            "comparar A con B y B con A tiene que dar lo mismo",
        )
    }

    @Test
    fun `la cota superior nunca descarta un par valido`() {
        val index = QuestionSimilarity()
        val ids = corpusDePrueba().map { index.add(it) }
        index.build()

        var paresValidos = 0
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val similarity = index.similarity(ids[i], ids[j])
                if (similarity < UMBRAL) continue
                paresValidos++
                assertTrue(
                    index.upperBound(ids[i], ids[j]) >= similarity,
                    "la cota superior tiene que ser mayor o igual que la similitud real",
                )
            }
        }
        assertTrue(paresValidos > 0, "el corpus de prueba no tiene pares por encima del umbral")
    }

    // ------------------------------------------------------------------
    // Agrupamiento
    // ------------------------------------------------------------------

    @Test
    fun `el agrupamiento junta las que deben y separa las que no`() {
        val clusters = QuestionClusterer().cluster(
            corpusDePrueba(AUTOMATA, QUE_ES_AUTOMATA, DIFERENCIA),
        )

        val automaticas = clusterDe(clusters, AUTOMATA)
        assertEquals(2, automaticas.size, "la paráfrasis tiene que estar en el mismo cluster")
        assertTrue(
            QUE_ES_AUTOMATA in automaticas.members,
            "la paráfrasis tiene que estar en el cluster: ${automaticas.members}",
        )

        val diferencia = clusterDe(clusters, DIFERENCIA)
        assertEquals(1, diferencia.size, "otra pregunta sobre el mismo tema va aparte")
        assertEquals(DIFERENCIA, diferencia.representative)
    }

    @Test
    fun `el representante es la pregunta mas central del cluster`() {
        // El centro del cluster es la pregunta que más se parece a las demás:
        // la que tiene la suma de similitudes más alta.
        val preguntaCentral = "¿Qué es un autómata finito?"
        val clusters = QuestionClusterer().cluster(
            corpusDePrueba(AUTOMATA, QUE_ES_AUTOMATA, preguntaCentral),
        )

        val cluster = clusterDe(clusters, AUTOMATA)

        assertEquals(3, cluster.size)
        assertTrue(
            cluster.representative in cluster.members,
            "el representante tiene que ser una pregunta del cluster",
        )
    }

    @Test
    fun `una pregunta demasiado corta no forma cluster`() {
        val clusters = QuestionClusterer().cluster(listOf("hola", "hola", "hola"))

        assertTrue(
            clusters.isEmpty(),
            "una pregunta de un solo token no dice nada: no puede ser un cluster",
        )
    }

    // ------------------------------------------------------------------
    // Puntaje de fracaso
    // ------------------------------------------------------------------

    @Test
    fun `las cuatro senales al maximo dan F igual a 1 y es un fracaso`() {
        val veredicto = TurnFailure(TurnSignals(repetition = 1.0, grounding = 1.0, reask = 1.0, abandonment = 1.0))

        assertEquals(1.0, veredicto.failure, 1e-9)
        assertTrue(veredicto.isFailure, "con las cuatro señales al máximo es un fracaso")
    }

    @Test
    fun `sin senales el turno no es un fracaso`() {
        val veredicto = TurnFailure(TurnSignals())

        assertEquals(0.0, veredicto.failure, 1e-9)
        assertFalse(veredicto.isFailure, "un turno sin señales no es un fracaso")
    }

    @Test
    fun `sin fuente y repregunta es un fracaso`() {
        val veredicto = TurnFailure(TurnSignals(grounding = 0.60, reask = 1.0))

        assertEquals(0.46, veredicto.failure, 1e-9)
        assertTrue(veredicto.isFailure, "es el caso que define al producto")
    }

    @Test
    fun `repetir y repreguntar tambien es un fracaso`() {
        val veredicto = TurnFailure(TurnSignals(repetition = 1.0, reask = 1.0))

        assertEquals(0.55, veredicto.failure, 1e-9)
        assertTrue(veredicto.isFailure)
    }

    @Test
    fun `ninguna senal sola alcanza el umbral de fracaso`() {
        val senales = listOf(
            TurnSignals(repetition = 1.0),
            TurnSignals(grounding = 1.0),
            TurnSignals(reask = 1.0),
            TurnSignals(abandonment = 1.0),
        )

        for (senal in senales) {
            val veredicto = TurnFailure(senal)
            assertFalse(
                veredicto.isFailure,
                "una sola señal no puede marcar un fracaso (F = ${veredicto.failure})",
            )
        }
    }

    // ------------------------------------------------------------------
    // Huecos del material
    // ------------------------------------------------------------------

    @Test
    fun `un cluster donde la IA nunca encontro material es un hueco sin material`() {
        val (threads, messages) = curso(
            listOf(
                Alumno("a", Estilo.SIN_FUENTE),
                Alumno("b", Estilo.SIN_FUENTE),
            ),
        )

        val informe = QuestionClusters.analyze(threads, messages, ahora())

        assertEquals(1, informe.gaps.size, "hay un solo cluster de preguntas repetidas")
        val hueco = informe.gaps.first()
        assertTrue(hueco.isGap)
        assertEquals(GapKind.SIN_MATERIAL, hueco.kind, "nunca se recuperó una fuente")
        assertEquals(2, hueco.distinctStudents, "dos alumnos preguntaron lo mismo")
        assertEquals(1.0, hueco.noSourceRate, 1e-9)
        assertTrue(hueco.meanFailure >= 0.45, "la media de fracaso tiene que dar el hueco")
    }

    @Test
    fun `un cluster que recupera material sin respaldarlo es material no recuperado`() {
        val (threads, messages) = curso(
            listOf(
                Alumno("a", Estilo.SIN_FUENTE),
                Alumno("b", Estilo.SIN_RESPALDO),
            ),
        )

        val informe = QuestionClusters.analyze(threads, messages, ahora())

        val hueco = informe.gaps.firstOrNull()
        assertTrue(hueco != null, "el cluster tiene que ser un hueco: ${informe.gaps}")
        assertEquals(GapKind.NO_RECUPERADO, hueco.kind, "recuperó material pero las citas no lo respaldan")
        assertEquals(3, hueco.unsupportedTurns, "tres turnos con material sin respaldo")
    }

    @Test
    fun `un cluster con material bien respaldado pero que se sigue preguntando es material insuficiente`() {
        val (threads, messages) = curso(
            listOf(
                Alumno("a", Estilo.SIN_FUENTE),
                Alumno("b", Estilo.CON_RESPALDO),
            ),
        )

        val informe = QuestionClusters.analyze(threads, messages, ahora())

        val hueco = informe.gaps.firstOrNull()
        assertTrue(hueco != null, "el cluster tiene que ser un hueco: ${informe.gaps}")
        assertEquals(
            GapKind.SIN_RESPALDO,
            hueco.kind,
            "el material estaba y estaba respaldado, pero el alumno volvió a preguntar",
        )
    }

    @Test
    fun `un solo alumno no hace un hueco`() {
        val (threads, messages) = curso(listOf(Alumno("a", Estilo.SIN_FUENTE)))

        val informe = QuestionClusters.analyze(threads, messages, ahora())

        assertTrue(informe.gaps.isEmpty(), "un solo alumno no es un problema de la cátedra")
        assertEquals(1, informe.clusters.size, "el cluster existe igual, para el docente")
    }

    @Test
    fun `los huecos se ordenan por impacto`() {
        val (threads, messages) = curso(
            listOf(
                Alumno("a", Estilo.SIN_FUENTE),
                Alumno("b", Estilo.SIN_FUENTE),
                Alumno("c", Estilo.SIN_FUENTE, PREGUNTA_COMPLEJIDAD),
            ),
        )

        val informe = QuestionClusters.analyze(threads, messages, ahora())

        assertEquals(2, informe.gaps.size, "son dos temas distintos")
        val primero = informe.gaps.first()
        assertEquals(3, primero.distinctStudents, "el de más alumnos va primero")
        assertTrue(
            informe.gaps[0].gapScore >= informe.gaps[1].gapScore,
            "el orden es por impacto: ${informe.gaps.map { it.gapScore }}",
        )
    }

    @Test
    fun `el analisis es determinista`() {
        val (threads, messages) = curso(
            listOf(Alumno("a", Estilo.SIN_FUENTE), Alumno("b", Estilo.SIN_FUENTE)),
        )

        val primero = QuestionClusters.analyze(threads, messages, ahora())
        val segundo = QuestionClusters.analyze(threads, messages, ahora())

        assertEquals(primero, segundo, "los mismos chats tienen que dar el mismo informe")
    }

    @Test
    fun `el informe guarda los umbrales con los que se calculo`() {
        val (threads, messages) = curso(listOf(Alumno("a", Estilo.SIN_FUENTE)))

        val informe = QuestionClusters.analyze(threads, messages, ahora())

        assertEquals(0.62, informe.similarityThreshold, 1e-9)
        assertEquals(0.45, informe.failureThreshold, 1e-9)
    }

    // ------------------------------------------------------------------
    // Eficiencia
    // ------------------------------------------------------------------

    @Test
    fun `con mil preguntas el agrupamiento no explota`() {
        val preguntas = generarPreguntas(1000)
        val clusterer = QuestionClusterer()

        val inicio = System.nanoTime()
        val clusters = clusterer.cluster(preguntas)
        val milisegundos = (System.nanoTime() - inicio) / 1_000_000

        val todosLosPares = 1000 * 999 / 2
        assertTrue(
            clusterer.lastComparisons < todosLosPares / 4,
            "la poda tiene que evitar el all-pairs (comparaciones: ${clusterer.lastComparisons} de $todosLosPares)",
        )
        assertTrue(
            milisegundos < 10_000,
            "mil preguntas tienen que analizarse en menos de 10 s (tardó ${milisegundos}ms)",
        )
        assertEquals(50, clusters.size, "son 50 temas de 20 preguntas cada uno")
        assertTrue(
            clusters.all { it.size == 20 },
            "cada tema tiene que quedar con sus 20 preguntas: ${clusters.map { it.size }}",
        )
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun clusterDe(clusters: List<QuestionCluster>, question: String): QuestionCluster =
        clusters.firstOrNull { question in it.members }
            ?: error("no hay ningún cluster que contenga la pregunta: $question")

    /**
     * Corpus sintético con la distribución de frecuencias del ejemplo canónico
     * del diseño: 1000 preguntas, `df(define) = 120`, `df(automata) = 40`,
     * `df(finito) = 25`.
     *
     * El relleno no es decorativo: la IDF se calcula sobre el corpus, y con un
     * corpus chico todos los términos pesan lo mismo y la ponderación deja de
     * distinguir "misma pregunta" de "mismo tema".
     */
    private fun corpusDePrueba(vararg canonicas: String): List<String> {
        val relleno = PALABRAS_DE_RELLENO
        val out = ArrayList<String>()
        // 119 preguntas con "define": es una palabra de redacción, no de contenido.
        repeat(119) { i ->
            out += "${relleno[i % relleno.size]} ${relleno[(i / relleno.size) % relleno.size]} define"
        }
        // 38 con "automata" y 23 con "finito": completan los `df` canónicos.
        repeat(38) { i -> out += "${relleno[i % relleno.size]} automata" }
        repeat(23) { i -> out += "${relleno[i % relleno.size]} finito" }
        out += canonicas.toList()
        // Relleno neutro hasta 1000: sin tokens que se mezclen con las canónicas.
        var i = 0
        while (out.size < 1000) {
            out += "${relleno[i % relleno.size]} ${relleno[(i / relleno.size) % relleno.size]} entrega"
            i++
        }
        return out
    }

    /** Mil preguntas repartidas en 50 temas de 20 variantes. */
    private fun generarPreguntas(cantidad: Int): List<String> {
        val temas = 50
        val variantes = 20
        return List(cantidad) { i ->
            val tema = i % temas
            val variante = (i / temas) % variantes
            "tema$tema variante$variante comun$tema extra$tema"
        }
    }

    /** Cómo responde la IA al alumno. */
    private enum class Estilo { SIN_FUENTE, SIN_RESPALDO, CON_RESPALDO }

    /** Un alumno que repite la misma pregunta. */
    private data class Alumno(
        val id: String,
        val estilo: Estilo,
        val pregunta: String = AUTOMATA,
        val veces: Int = 3,
    )

    /**
     * Arma un curso con los alumnos indicados.
     *
     * Cada alumno abre un hilo y repite su pregunta `veces` veces, una por
     * día. El último turno de cada hilo es el único que puede contar abandono.
     */
    private fun curso(alumnos: List<Alumno>): Pair<List<ChatThread>, List<ChatMessage>> {
        val threads = ArrayList<ChatThread>()
        val messages = ArrayList<ChatMessage>()
        for (alumno in alumnos) {
            val threadId = "hilo-${alumno.id}"
            threads += ChatThread(
                id = threadId,
                courseId = "IA-1",
                authorId = alumno.id,
                authorName = alumno.id,
            )
            for (i in 0 until alumno.veces) {
                val at = INICIO + i * DIA
                messages += ChatMessage(
                    id = "q-${alumno.id}-$i",
                    threadId = threadId,
                    role = "user",
                    text = alumno.pregunta,
                    createdAt = at,
                )
                val sources = if (alumno.estilo == Estilo.SIN_FUENTE) {
                    emptyList()
                } else {
                    listOf("cátedra: Apuntes de teoría.pdf")
                }
                val citation = when (alumno.estilo) {
                    Estilo.SIN_FUENTE -> null
                    Estilo.SIN_RESPALDO -> CitationStats(unsupported = 1)
                    Estilo.CON_RESPALDO -> CitationStats(verified = 1)
                }
                messages += ChatMessage(
                    id = "a-${alumno.id}-$i",
                    threadId = threadId,
                    role = "assistant",
                    text = "Esta es la respuesta.",
                    createdAt = at + 1_000,
                    sources = sources,
                    citation = citation,
                )
            }
        }
        return threads to messages
    }

    /** Instante de referencia: 40 días después del inicio, para que se vea el abandono. */
    private fun ahora(): Long = INICIO + 40 * DIA

    private companion object {
        const val UMBRAL = 0.62
        const val INICIO = 1_700_000_000_000L
        const val DIA = 86_400_000L

        const val AUTOMATA = "¿Cómo se define un autómata finito?"
        const val QUE_ES_AUTOMATA = "¿Qué es un autómata finito?"
        const val DIFERENCIA = "¿Qué diferencia hay entre autómata finito y máquina de Turing?"
        const val PREGUNTA_COMPLEJIDAD = "¿Cómo se calcula la complejidad de un algoritmo?"

        val PALABRAS_DE_RELLENO = listOf(
            "tema", "ejercicio", "practica", "guia", "laboratorio", "seminario", "clase",
            "unidad", "material", "parcial", "entrega", "criterios", "bibliografia", "agenda",
            "correo", "aula", "horario", "profesor", "notas", "tp",
        )
    }
}
                                                                                                                                                      