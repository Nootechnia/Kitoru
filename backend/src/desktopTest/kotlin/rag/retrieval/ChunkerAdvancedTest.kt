package rag.retrieval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ln
import kotlin.random.Random

/**
 * Tests avanzados del troceador y del índice.
 *
 * Cubren los defectos que se midieron en el diseño: el corte a mitad de
 * palabra, el solapamiento que se pierde en los límites de página, el
 * `heading` inútil de un PDF y el `heading` de todo PDF igual a "Página N".
 */
class ChunkerAdvancedTest {

    private fun doc(text: String, path: String = "apuntes/Apuntes-IA.pdf") = Doc("cátedra", path, text)

    /** Un PDF sintético: párrafos separados por línea en blanco, sin encabezados Markdown. */
    private fun pdfLikeText(parrafos: Int = 14): String {
        val cuerpo = (1..parrafos).joinToString("\n\n") { i ->
            "Parrafo $i del material. La regresion lineal explica la relacion entre " +
                "variables. El estimador minimiza la suma de residuos al cuadrado."
        }
        return "1. Modelos lineales\n\n$cuerpo"
    }

    // ------------------------------------------------------------------
    // Cortes
    // ------------------------------------------------------------------

    @Test
    fun `ningun fragmento arranca en mitad de una palabra`() {
        val texto = pdfLikeText()
        val chunks = Chunker.split(doc(texto), MaterialShape.PROSE)
        assertTrue(chunks.size > 1, "el texto tiene que trocearse en varios fragmentos")
        for (chunk in chunks) {
            val pos = texto.indexOf(chunk.text)
            assertTrue(pos >= 0, "el fragmento tiene que ser un excerpt fiel: ${chunk.text.take(40)}")
            if (pos > 0) {
                val anterior = texto[pos - 1]
                assertFalse(
                    anterior.isLetterOrDigit(),
                    "el fragmento arranca en mitad de una palabra: '${chunk.text.take(40)}'",
                )
            }
        }
    }

    @Test
    fun `ningun fragmento termina en mitad de una palabra`() {
        val texto = pdfLikeText()
        val chunks = Chunker.split(doc(texto), MaterialShape.PROSE)
        for (chunk in chunks) {
            val pos = texto.indexOf(chunk.text)
            val fin = pos + chunk.text.length
            if (fin < texto.length) {
                val siguiente = texto[fin]
                assertFalse(
                    siguiente.isLetterOrDigit(),
                    "el fragmento termina en mitad de una palabra: '...${chunk.text.takeLast(40)}'",
                )
            }
        }
    }

    @Test
    fun `el corte cae en un limite de palabra`() {
        val texto = "uno dos tres cuatro cinco seis siete ocho nueve diez"
        val corte = Chunker.cutAtBoundary(texto, 20)
        assertTrue(corte <= 20, "el corte no puede pasar el límite")
        assertTrue(texto[corte].isWhitespace(), "el corte tiene que caer en un espacio")
        assertTrue(texto.substring(0, corte).endsWith("cuatro"), "el fragmento tiene que ser entero")
    }

    @Test
    fun `el piso del corte evita un fragmento minusculo`() {
        // La última palabra antes del límite es larguísima: con el piso del
        // 60 % el corte cae en el límite y no en un fragmento de dos letras.
        val texto = "ab " + "x".repeat(80) + " cd"
        val corte = Chunker.cutAtBoundary(texto, 20)
        assertTrue(corte >= 12, "el corte no puede caer antes del piso del 60 % (actual: $corte)")
    }

    // ------------------------------------------------------------------
    // Solapamiento
    // ------------------------------------------------------------------

    @Test
    fun `el solapamiento es una oracion completa`() {
        val texto = pdfLikeText()
        val chunks = Chunker.split(doc(texto), MaterialShape.PROSE)
        assertTrue(chunks.size > 1)
        for (i in 0 until chunks.size - 1) {
            val primero = chunks[i].text
            val segundo = chunks[i + 1].text
            val oracion = primeraOracion(segundo)
            assertTrue(
                oracion.endsWith("."),
                "el solapamiento tiene que ser una oración completa: '$oracion'",
            )
            assertTrue(
                primero.contains(oracion),
                "la oración del solapamiento tiene que estar en el fragmento anterior",
            )
            assertTrue(
                segundo.startsWith(oracion),
                "el fragmento siguiente tiene que arrancar con la oración de solapamiento",
            )
        }
    }

    @Test
    fun `una oracion mas larga que el tope se recorta en un limite de palabra`() {
        val larga = "Esta oracion es deliberadamente mas larga que el tope de 180 caracteres " +
            "para comprobar que el solapamiento se recorta en un limite de palabra y no " +
            "parte ninguna a la mitad."
        val texto = (1..6).joinToString("\n\n") { "$larga Fin del parrafo $it." }
        val chunks = Chunker.split(doc(texto), MaterialShape.PROSE)
        assertTrue(chunks.size > 1, "el texto tiene que trocearse")
        val solape = solapamiento(chunks[0].text, chunks[1].text)
        assertTrue(solape.isNotEmpty(), "los fragmentos tienen que solaparse")
        assertTrue(solape.length <= 180, "la cola de solapamiento está acotada: ${solape.length}")
        assertTrue(
            solape.last().isLetterOrDigit(),
            "el recorte del solapamiento no puede partir una palabra: '${solape.takeLast(20)}'",
        )
    }

    @Test
    fun `el solapamiento no cruza un titulo`() {
        val texto = """
            1. Modelos lineales

            ${"Frase del primer modelo que explica la regresion lineal. ".repeat(6)}

            2. Redes neuronales

            ${"Frase de la red neuronal que explica el aprendizaje profundo. ".repeat(6)}
        """.trimIndent()
        val chunks = Chunker.split(doc(texto), MaterialShape.PROSE)
        val indice = chunks.indexOfFirst { it.heading.contains("Redes") }
        assertTrue(indice > 0, "tiene que haber un fragmento de la segunda sección")
        val anterior = chunks[indice - 1].text
        val propio = chunks[indice].text
        assertFalse(
            propio.contains("regresion lineal"),
            "el fragmento de la sección nueva no puede repetir la sección anterior",
        )
        assertTrue(anterior.contains("regresion lineal"))
    }

    // ------------------------------------------------------------------
    // Títulos
    // ------------------------------------------------------------------

    @Test
    fun `un titulo numerado se detecta`() {
        val texto = """
            1. Modelos lineales

            Contenido de la primera sección con suficiente texto.

            2. Redes neuronales

            Contenido de la segunda sección con suficiente texto.
        """.trimIndent()
        val chunks = Chunker.split(doc(texto), MaterialShape.PROSE)
        assertEquals(listOf("Modelos lineales", "Redes neuronales"), chunks.map { it.heading }.distinct())
    }

    @Test
    fun `un titulo jerarquico se detecta`() {
        val texto = """
            1.1 Regresión lineal

            Contenido de la subsección con suficiente texto para que exista.

            1.2 Regresión logística

            Contenido de la otra subsección con suficiente texto para que exista.
        """.trimIndent()
        val chunks = Chunker.split(doc(texto), MaterialShape.PROSE)
        assertEquals(
            listOf("Regresión lineal", "Regresión logística"),
            chunks.map { it.heading }.distinct(),
        )
    }

    @Test
    fun `un titulo con palabra clave se detecta`() {
        val texto = """
            Tema 3: Redes neuronales

            Contenido del tema con suficiente texto para que el fragmento exista.

            CAPÍTULO 2

            Contenido del capítulo con suficiente texto para que el fragmento exista.

            Unidad 4

            Contenido de la unidad con suficiente texto para que el fragmento exista.
        """.trimIndent()
        val headings = Chunker.split(doc(texto), MaterialShape.PROSE).map { it.heading }.distinct()
        assertTrue(headings.contains("Redes neuronales"), "el título del tema tiene que detectarse: $headings")
        assertTrue(headings.contains("Unidad 4"), "el título de la unidad tiene que detectarse: $headings")
    }

    @Test
    fun `una linea en mayusculas se detecta`() {
        val texto = """
            TEOREMA DE MINIMOS CUADRADOS

            El estimador resuelve el problema de optimización con suficientes palabras.

            Segunda sección con otro contenido para que el documento tenga dos fragmentos.
        """.trimIndent()
        val chunks = Chunker.split(doc(texto), MaterialShape.PROSE)
        assertTrue(
            chunks.any { it.heading.contains("minimos cuadrados", ignoreCase = true) },
            "la línea en mayúsculas tiene que detectarse: ${chunks.map { it.heading }}",
        )
    }

    @Test
    fun `un numero que no es secuencial no es un titulo`() {
        val texto = """
            1. Modelos lineales

            Contenido de la primera sección con suficiente texto.

            2024 fue un año complicado para la cátedra entera.

            1.5 % de los alumnos aprobaron la materia.
        """.trimIndent()
        val chunks = Chunker.split(doc(texto), MaterialShape.PROSE)
        val headings = chunks.map { it.heading }.distinct()
        assertFalse(headings.any { it.contains("2024") }, "un año no es un título: $headings")
        assertFalse(headings.any { it.contains("%") }, "un porcentaje no es un título: $headings")
    }

    @Test
    fun `la marca de pagina no es el titulo del fragmento`() {
        val texto = """
            1. Modelos lineales

            Contenido de la primera sección con suficiente texto para el fragmento.

            ## Página 2

            Contenido de la segunda página con suficiente texto para el fragmento.
        """.trimIndent()
        val chunks = Chunker.split(doc(texto), MaterialShape.PROSE)
        assertTrue(chunks.isNotEmpty())
        for (chunk in chunks) {
            assertNotEquals("Página 2", chunk.heading, "la marca de página no es una sección")
        }
    }

    // ------------------------------------------------------------------
    // Troceado por párrafo y tamaños
    // ------------------------------------------------------------------

    @Test
    fun `el texto plano se trocea por parrafo`() {
        val parrafos = (1..8).joinToString("\n\n") { i -> "Parrafo $i con su contenido." }
        val chunks = Chunker.split(doc(parrafos), MaterialShape.PROSE)
        assertEquals(1, chunks.size, "los párrafos chicos van juntos en un fragmento")
        assertTrue(chunks[0].text.startsWith("Parrafo 1"), "el fragmento arranca en un párrafo")
    }

    @Test
    fun `un parrafo grande se parte por oracion`() {
        val parrafo = (1..40).joinToString(" ") { "Oracion numero $it con contenido." }
        val chunks = Chunker.split(doc(parrafo), MaterialShape.PROSE)
        assertTrue(chunks.size > 1, "un párrafo largo tiene que partirse")
        for (chunk in chunks) {
            assertTrue(chunk.text.length <= 800, "ningún fragmento pasa el máximo: ${chunk.text.length}")
        }
    }

    @Test
    fun `los tamanos son adaptativos por tipo de material`() {
        assertEquals(600, ChunkingStrategy.forShape(MaterialShape.PROSE).targetChars)
        assertEquals(400, ChunkingStrategy.forShape(MaterialShape.PROSE).minChars)
        assertEquals(800, ChunkingStrategy.forShape(MaterialShape.PROSE).maxChars)

        assertEquals(1200, ChunkingStrategy.forShape(MaterialShape.MARKDOWN).targetChars)
        assertEquals(600, ChunkingStrategy.forShape(MaterialShape.MARKDOWN).minChars)
        assertEquals(1500, ChunkingStrategy.forShape(MaterialShape.MARKDOWN).maxChars)

        assertEquals(500, ChunkingStrategy.forShape(MaterialShape.SLIDES).targetChars)
        assertEquals(0, ChunkingStrategy.forShape(MaterialShape.SLIDES).overlapSentences)
    }

    @Test
    fun `el pdf se trocea mas chico que el markdown`() {
        val texto = pdfLikeText(20)
        val pdf = Chunker.split(doc(texto, "apuntes.pdf"), MaterialShape.PROSE)
        val markdown = Chunker.split(doc(texto, "apuntes.md"), MaterialShape.MARKDOWN)
        assertTrue(pdf.size > markdown.size, "el PDF se trocea en fragmentos más chicos")
        for (chunk in pdf) {
            assertTrue(chunk.text.length <= 800, "el PDF no puede pasar de 800: ${chunk.text.length}")
        }
        for (chunk in markdown) {
            assertTrue(chunk.text.length <= 1500, "el Markdown no puede pasar de 1500: ${chunk.text.length}")
        }
    }

    @Test
    fun `la estrategia se elige por la extension`() {
        assertEquals(MaterialShape.MARKDOWN, ChunkingStrategy.forPath("apuntes.md").shape)
        assertEquals(MaterialShape.SLIDES, ChunkingStrategy.forPath("clase.pptx").shape)
        assertEquals(MaterialShape.PROSE, ChunkingStrategy.forPath("apuntes.pdf").shape)
        assertEquals(MaterialShape.TABLE, ChunkingStrategy.forPath("datos.csv").shape)
    }

    // ------------------------------------------------------------------
    // Tokenizador y stemmer
    // ------------------------------------------------------------------

    @Test
    fun `la lista de palabras vacias en espanol esta completa`() {
        assertEquals(199, StopWords.ES.size, "la lista en español tiene que estar completa")
        assertEquals(69, StopWords.EN.size, "la lista en inglés tiene que estar completa")
        for (palabra in listOf("cuando", "donde", "quien", "cuales", "tambien", "porque", "ademas")) {
            assertTrue(palabra in StopWords.ES, "falta la palabra vacía '$palabra'")
        }
        for (palabra in listOf("definicion", "diferencia", "formula", "teorema", "ejemplo", "parcial")) {
            assertFalse(palabra in StopWords.ES, "una palabra técnica no puede ser vacía: '$palabra'")
        }
    }

    @Test
    fun `el tokenizador saca las palabras vacias en espanol`() {
        val tokens = TextTokenizer.tokenize("cuando llega el parcial, el alumno pregunta")
        assertFalse("cuando" in tokens, "las palabras vacías se sacan: $tokens")
        assertFalse("el" in tokens, "las palabras vacías se sacan: $tokens")
        assertTrue("parcial" in tokens, "las palabras técnicas se conservan: $tokens")
    }

    @Test
    fun `el tokenizador con stemming casa plural con singular`() {
        val indice = TextTokenizer.tokenize("los modelos de regresion lineal")
        val consulta = TextTokenizer.tokenize("modelo regresion")
        assertTrue(consulta.all { it in indice }, "la consulta tiene que casar con el índice: $consulta vs $indice")
    }

    @Test
    fun `el stemmer conserva las raices`() {
        val golden = listOf(
            "arboles" to "arbol", "arbol" to "arbol",
            "profesores" to "profesor",
            "clases" to "clas", "clase" to "clas",
            "responsables" to "responsabl", "responsable" to "responsabl",
            "grafos" to "graf", "grafo" to "graf", "grafico" to "grafic",
            "regresiones" to "regresion", "regresion" to "regresion",
            "definiciones" to "definicion", "definicion" to "definicion",
            "evaluaciones" to "evaluacion", "informaciones" to "informacion",
            "calculo" to "calcul", "calculos" to "calcul", "calcular" to "calcul",
            "calculado" to "calcul", "calculamos" to "calcul",
            "rapidamente" to "rapid", "rapido" to "rapid",
            "almacenamiento" to "almacen", "almacenar" to "almacen",
            "matrices" to "matriz", "matriz" to "matriz",
            "indices" to "indic", "indice" to "indic",
            "series" to "serie", "serie" to "serie",
            "datos" to "dato", "dato" to "dato",
            "paises" to "pais",
            "ingleses" to "ingles", "ingles" to "ingles",
            "universidades" to "universidad",
            "funciones" to "funcion",
            "busquedas" to "busqueda",
            "importantes" to "important", "importante" to "important",
            "resultados" to "result", "resultado" to "result",
        )
        for ((palabra, raiz) in golden) {
            assertEquals(raiz, SpanishStemmer.stem(palabra), "la raíz de '$palabra' cambió")
        }
    }

    @Test
    fun `el stemmer hace converger las parejas de singular y plural`() {
        val parejas = listOf(
            "arbol" to "arboles",
            "profesor" to "profesores",
            "clase" to "clases",
            "responsable" to "responsables",
            "grafo" to "grafos",
            "grafico" to "graficos",
            "dato" to "datos",
            "pais" to "paises",
            "importante" to "importantes",
            "resultado" to "resultados",
            "serie" to "series",
            "base" to "bases",
            "libre" to "libres",
            "grande" to "grandes",
            "verde" to "verdes",
            "normal" to "normales",
            "final" to "finales",
            "total" to "totales",
            "real" to "reales",
            "ideal" to "ideales",
            "natural" to "naturales",
            "animal" to "animales",
            "local" to "locales",
            "formal" to "formales",
            "general" to "generales",
            "material" to "materiales",
            "variable" to "variables",
            "estable" to "estables",
            "visible" to "visibles",
            "posible" to "posibles",
            "responsabilidad" to "responsabilidades",
            "cliente" to "clientes",
            "estudiante" to "estudiantes",
            "profesional" to "profesionales",
            "sistema" to "sistemas",
            "metodo" to "metodos",
            "modelo" to "modelos",
            "proceso" to "procesos",
            "concepto" to "conceptos",
            "ejemplo" to "ejemplos",
            "problema" to "problemas",
            "programa" to "programas",
            "formula" to "formulas",
            "teorema" to "teoremas",
            "algoritmo" to "algoritmos",
            "nodo" to "nodos",
            "tabla" to "tablas",
            "frase" to "frases",
            "oracion" to "oraciones",
            "seccion" to "secciones",
            "definicion" to "definiciones",
            "informacion" to "informaciones",
            "evaluacion" to "evaluaciones",
            "regresion" to "regresiones",
            "indice" to "indices",
            "matriz" to "matrices",
            "vertice" to "vertices",
            "apice" to "apices",
            "ingles" to "ingleses",
            "calculo" to "calculos",
            "universidad" to "universidades",
            "funcion" to "funciones",
            "busqueda" to "busquedas",
            "numerico" to "numericos",
            "practico" to "practicos",
        )
        assertEquals(65, parejas.size, "el golden tiene que tener 65 parejas")
        for ((singular, plural) in parejas) {
            assertEquals(
                SpanishStemmer.stem(singular),
                SpanishStemmer.stem(plural),
                "'$singular' y '$plural' tienen que dar la misma raíz",
            )
        }
    }

    // ------------------------------------------------------------------
    // Índice BM25
    // ------------------------------------------------------------------

    @Test
    fun `el indice invertido da los mismos scores que el recorrido lineal`() {
        val chunks = corpusDePrueba()
        val index = Bm25Index(chunks)
        for (consulta in consultasDePrueba()) {
            val nuevo = index.search(consulta, 10)
            val viejo = recorridoLineal(chunks, consulta, 10)
            assertEquals(viejo.size, nuevo.size, "la cantidad de resultados tiene que ser la misma: $consulta")
            for (i in viejo.indices) {
                assertEquals(viejo[i].first.text, nuevo[i].first.text, "el orden tiene que ser el mismo: $consulta")
                assertEquals(
                    viejo[i].second,
                    nuevo[i].second,
                    1e-9,
                    "el score tiene que ser el mismo: $consulta",
                )
            }
        }
    }

    @Test
    fun `el indice inverted no cambia el resultado de una busqueda simple`() {
        val chunks = listOf(
            Chunk("cátedra", "guia.md", "Motores", "el motor renderiza en Vulkan y OpenGL"),
            Chunk("cátedra", "guia.md", "Shaders", "un tutorial de shaders con ejemplos"),
        )
        val index = Bm25Index(chunks)
        val resultados = index.search("shaders", 3)
        assertEquals(1, resultados.size)
        assertTrue("shaders" in resultados[0].first.text)
    }

    @Test
    fun `la frecuencia documental se puede consultar`() {
        val chunks = listOf(
            Chunk("cátedra", "a.md", "", "el motor motor"),
            Chunk("cátedra", "b.md", "", "el motor"),
            Chunk("cátedra", "c.md", "", "otra cosa"),
        )
        val index = Bm25Index(chunks)
        assertEquals(2, index.documentFrequency("motor"))
        assertEquals(0, index.documentFrequency("teclado"))
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private fun primeraOracion(texto: String): String {
        val fin = Regex("""\.\s""").find(texto) ?: return texto.trim()
        return texto.substring(0, fin.range.first + 1)
    }

    /** El texto que el segundo fragmento repite del primero (el solapamiento). */
    private fun solapamiento(primero: String, segundo: String): String {
        var largo = 0
        while (
            largo < minOf(primero.length, segundo.length) &&
            primero[primero.length - 1 - largo] == segundo[largo]
        ) {
            largo++
        }
        return segundo.take(largo)
    }

    private fun corpusDePrueba(): List<Chunk> {
        val random = Random(20240521)
        val palabras = listOf(
            "regresion", "lineal", "modelo", "variable", "dependiente", "estimador",
            "cuadrados", "minimos", "residuo", "matriz", "diseno", "neurona", "aprendizaje",
            "profundidad", "perdida", "entropia", "clasificador", "conjunto", "datos",
        )
        return (0 until 40).map { i ->
            val frases = (0 until 6).map {
                val largo = random.nextInt(4, 12)
                (0 until largo).map { palabras[random.nextInt(palabras.size)] }.joinToString(" ")
            }
            Chunk(
                source = "cátedra",
                path = "material-$i.md",
                heading = "Sección $i",
                text = frases.joinToString(". ") + ".",
            )
        }
    }

    private fun consultasDePrueba(): List<String> = listOf(
        "regresion lineal",
        "estimador de minimos cuadrados",
        "neuronas y aprendizaje profundo",
        "variable dependiente",
        "matriz de diseno",
        "clasificador",
        "regresion",
        "palabra que no existe en el corpus",
        "datos conjunto perdida",
    )

    /**
     * La implementación anterior del índice, como referencia.
     *
     * Recorre todos los fragmentos por cada término de la consulta y ordena
     * el array completo de scores: es la referencia contra la que se compara
     * el índice invertido.
     */
    private fun recorridoLineal(
        chunks: List<Chunk>,
        query: String,
        k: Int,
    ): List<Pair<Chunk, Double>> {
        if (chunks.isEmpty()) return emptyList()
        val k1 = 1.5
        val b = 0.75
        val tfs = chunks.map { c ->
            Bm25Index.tokenize("${c.heading} ${c.path} ${c.text}").groupingBy { it }.eachCount()
        }
        val lens = tfs.map { it.values.sum() }
        val avgLen = if (lens.isEmpty()) 1.0 else lens.average().coerceAtLeast(1.0)
        val df: Map<String, Int> = HashMap<String, Int>().also { m ->
            tfs.forEach { tf -> tf.keys.forEach { m.merge(it, 1, Int::plus) } }
        }
        val n = chunks.size.toDouble()
        val scores = DoubleArray(chunks.size)
        for (term in Bm25Index.tokenize(query).distinct()) {
            val d = df[term] ?: continue
            val idf = ln(1 + (n - d + 0.5) / (d + 0.5))
            for (i in chunks.indices) {
                val f = tfs[i][term] ?: continue
                scores[i] += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * lens[i] / avgLen))
            }
        }
        return scores.withIndex()
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(k)
            .map { chunks[it.index] to it.value }
    }
}
