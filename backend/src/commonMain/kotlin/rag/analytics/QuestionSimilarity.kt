package rag.analytics

import kitoru.platform.stripDiacritics
import kotlin.math.ln

/**
 * Tokenizador de las preguntas del detector de repetidas.
 *
 * Replica **exactamente** las reglas de `rag.retrieval.Bm25Index.tokenize`:
 * partir el camel case, pasar a minúsculas, sacar los diacríticos, partir por
 * lo que no es alfanumérico y descartar las palabras vacías y las de una
 * letra. La razón de que las reglas sean las mismas es que el RAG y la
 * analítica tienen que hablar el mismo idioma: si el índice saca "definicion"
 * de una pregunta, el detector de repetidas también, y el "no encontré nada"
 * significa lo mismo en los dos lados.
 *
 * TODO(analitica): cuando exista el tokenizador compartido
 * `rag.retrieval.TextTokenizer` (extraído de `Bm25Index`), delegar ahí y
 * borrar esta copia: el objetivo es que la lista de palabras viva en un solo
 * lugar.
 */
object QuestionTokenizer {

    private val CAMEL = Regex("([a-z0-9])([A-Z])")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /**
     * Palabras vacías (es/en). Es el mismo conjunto que usa el índice BM25:
     * son palabras gramaticales que no distinguen una pregunta de otra.
     */
    val STOP: Set<String> = setOf(
        "el", "la", "los", "las", "de", "del", "un", "una", "y", "o", "en", "que", "por",
        "para", "con", "es", "se", "como", "cual", "the", "an", "and", "or", "of", "to",
        "in", "is", "for", "on", "with", "how", "what",
    )

    /**
     * Parte el texto en tokens de contenido.
     *
     * @return los tokens en minúsculas, sin diacríticos y sin repetidos.
     */
    fun tokenize(text: String): List<String> {
        val spaced = text.replace(CAMEL, "$1 $2").lowercase()
        val norm = stripDiacritics(spaced)
        return norm.split(NON_ALNUM).filter { it.length > 1 && it !in STOP }
    }
}

/**
 * Umbrales y parámetros del detector de preguntas repetidas.
 *
 * Los valores por defecto salen de la calibración del diseño
 * (`docs/design/analytics/03-preguntas-repetidas.md`) y se escriben en el
 * informe: un número de la cátedra siempre dice con qué umbrales se calculó,
 * así que dos informes de cursadas distintas son comparables.
 */
data class QuestionClusterConfig(
    /** Similitud mínima para que dos preguntas sean "la misma pregunta" (0..1). */
    val similarityThreshold: Double = 0.62,
    /** `df` máximo para que un token sirva de ancla de candidatas. */
    val maxAnchorDf: Int = 60,
    /** Tokens de contenido mínimos para que una pregunta sea clusterizable. */
    val minTokensForMatch: Int = 2,
    /** Tokens compartidos mínimos para unir dos preguntas. */
    val minSharedTokens: Int = 2,
    /** `df` a partir del cual un token compartido único NO alcanza para unir. */
    val rareTokenDf: Int = 5,
    /** `F` mínimo para que un turno sea un fracaso (0..1). */
    val failureThreshold: Double = 0.45,
    /** Preguntas previas del mismo alumno que se comparan (tope de costo). */
    val previousQuestionsForRepeat: Int = 50,
    /** Alumnos distintos mínimos para que un cluster sea un hueco de material. */
    val minDistinctStudentsForGap: Int = 2,
    /** Proporción mínima de turnos sin fuente para que un cluster sea un hueco. */
    val minNoSourceRateForGap: Double = 0.50,
    /** Días sin escribir para considerar que el alumno abandononó el hilo. */
    val idleDaysForAbandon: Int = 7,
)

/**
 * Resultado de comparar dos preguntas indexadas.
 *
 * @property similarity Jaccard ponderado por IDF (0..1).
 * @property sharedTokens cantidad de tokens de contenido en común.
 * @property sharedTokenId id interno del último token compartido; se usa para
 *   aplicar la regla del "token raro" (un único token genérico no une).
 */
data class Comparison(
    val similarity: Double,
    val sharedTokens: Int,
    val sharedTokenId: Int = -1,
)

/**
 * Índice de preguntas con similitud Jaccard **ponderada por IDF**.
 *
 * La fórmula es la del diseño:
 *
 * ```
 * sim(A, B) = Σ_{t ∈ A ∩ B} w(t) / Σ_{t ∈ A ∪ B} w(t)
 * w(t) = idf(t) = ln(1 + N / (1 + df(t)))
 * ```
 *
 * donde `N` es la cantidad de preguntas del corpus (las preguntas de la
 * cátedra) y `df(t)` la cantidad de preguntas que contienen el token `t`.
 *
 * Ponderar por IDF es lo que separa "mismo tema" de "misma pregunta": los
 * tokens genéricos ("hola", "definicion") pesan poco y los discriminativos
 * ("automata", "turing") pesan mucho. Una pregunta con términos que no
 * aparecen en ninguna otra pregunta del corpus tiene IDF alto: por eso se
 * parece poco a las demás, que es justo lo que hay que detectar.
 *
 * Es puro y determinista: los mismos datos de entrada dan los mismos números
 * (regla 3 del proyecto: la analítica no depende del modelo ni de la red).
 *
 * ### Escala: por qué hace falta la poda
 *
 * Con 100 000 preguntas el all-pairs son 5×10⁹ pares: inviable. Por eso las
 * comparaciones salen de un índice invertido y sólo se comparan las preguntas
 * que comparten algún token **de baja frecuencia** (`df ≤ maxAnchorDf`): los
 * tokens muy frecuentes son genéricos y generarían un all-pairs disfrazado.
 *
 * | Token | `df` típico | Candidatas por pregunta | Pares con 100k preguntas |
 * |---|---|---|---|
 * | muy raros (`df ≤ 10`) | 8 | ~35 | 0,2 M |
 * | raros (`10 < df ≤ 60`) | 30 | ~450 | 2,0 M |
 * | medios (`60 < df ≤ 300`) | 130 | ~8 400 | (descartados) |
 *
 * Con `maxAnchorDf = 60` se examinan ~2,2 M de pares en vez de 5×10⁹: **2200×
 * menos**. A ~40 ns por par (un `merge` lineal sobre dos `IntArray` de 15
 * elementos) son ~0,09 s de comparaciones; con la poda por cota superior, menos
 * de la mitad llega a calcularse.
 */
class QuestionSimilarity(private val maxAnchorDf: Int = DEFAULT_MAX_ANCHOR_DF) {

    companion object {
        /**
         * `df` máximo para que un token sirva de ancla de candidatas.
         *
         * Es configuración y no una constante mágica: si una cátedra genera
         * decenas de millones de candidatas, el síntoma es que su vocabulario
         * es chico y hay que bajar este número.
         */
        const val DEFAULT_MAX_ANCHOR_DF = 60

        /**
         * Construye el índice con un corpus de preguntas y calcula la IDF.
         *
         * @param corpus las preguntas de la cátedra: son el corpus de la IDF.
         */
        fun of(corpus: List<String>, maxAnchorDf: Int = DEFAULT_MAX_ANCHOR_DF): QuestionSimilarity {
            val index = QuestionSimilarity(maxAnchorDf)
            for (question in corpus) index.add(question)
            index.build()
            return index
        }
    }

    /** token → id interno. */
    private val vocab = HashMap<String, Int>()

    /** id interno → token (inverso de `vocab`, para no buscar con `entries.first {}`). */
    private val tokenTexts = ArrayList<String>()

    /** Texto original de cada pregunta indexada. */
    private val questionTexts = ArrayList<String>()

    /** Tokens (ids) de cada pregunta: ordenados y sin repetir. */
    private val questionTokens = ArrayList<IntArray>()

    // Todo lo de abajo se calcula en `build()`: la IDF depende del corpus
    // completo, así que hasta indexar todas las preguntas no hay números.
    private var weights = DoubleArray(0)
    private var documentFreq = IntArray(0)
    private var postingOffsets = IntArray(0)
    private var postingIds = IntArray(0)
    private var questionWeight = DoubleArray(0)
    private var stamps = IntArray(0)
    private var stampCounter = 0

    /** Cantidad de preguntas indexadas. */
    val size: Int get() = questionTokens.size

    /** ¿Se calculó ya la IDF? */
    val isBuilt: Boolean get() = weights.isNotEmpty()

    /**
     * Indexa una pregunta y devuelve su id interno.
     *
     * @return el id con el que se la puede comparar con [similarity].
     */
    fun add(text: String): Int {
        val id = questionTexts.size
        val tokens = QuestionTokenizer.tokenize(text)
        val ids = IntArray(tokens.size)
        var n = 0
        for (token in tokens) {
            val tid = vocab.getOrPut(token) {
                tokenTexts.size.also { tokenTexts += token }
            }
            ids[n++] = tid
        }
        questionTexts += text
        questionTokens += distinctSorted(ids.copyOf(n))
        return id
    }

    /**
     * Calcula la IDF y construye el índice invertido.
     *
     * Hay que llamar esto **después** de indexar todas las preguntas: la IDF
     * depende del corpus completo.
     */
    fun build() {
        val vocabulary = tokenTexts.size
        val n = size
        documentFreq = IntArray(vocabulary)
        for (tokens in questionTokens) {
            for (t in tokens) documentFreq[t]++
        }
        weights = DoubleArray(vocabulary) { ln(1.0 + n / (1.0 + documentFreq[it])) }
        questionWeight = DoubleArray(n)
        for (q in 0 until n) {
            var sum = 0.0
            for (t in questionTokens[q]) sum += weights[t]
            questionWeight[q] = sum
        }
        // Postings en formato CSR: offsets + ids. Ocupa menos que una lista de
        // listas y se recorre con un `for` sobre un `IntArray`.
        postingOffsets = IntArray(vocabulary + 1)
        for (i in 0 until vocabulary) postingOffsets[i + 1] = postingOffsets[i] + documentFreq[i]
        val cursor = postingOffsets.copyOf()
        postingIds = IntArray(postingOffsets[vocabulary])
        for (q in 0 until n) {
            for (t in questionTokens[q]) postingIds[cursor[t]++] = q
        }
        stamps = IntArray(n)
    }

    /**
     * IDF de un término del corpus: `ln(1 + N / (1 + df))`.
     *
     * Un término que no aparece en ninguna pregunta tiene `df = 0` y el IDF
     * más alto posible: es el caso interesante, porque una pregunta con
     * términos únicos no se parece a ninguna otra.
     */
    fun idf(term: String): Double {
        checkBuilt()
        val df = vocab[term]?.let { documentFreq[it] } ?: 0
        return ln(1.0 + size / (1.0 + df))
    }

    /** Cantidad de preguntas del corpus que contienen el término. */
    fun documentFrequency(term: String): Int {
        checkBuilt()
        return vocab[term]?.let { documentFreq[it] } ?: 0
    }

    /** IDF de un token interno. */
    fun weightOfToken(tokenId: Int): Double = weights[tokenId]

    /** `df` de un token interno. */
    fun documentFrequencyOfToken(tokenId: Int): Int = documentFreq[tokenId]

    /** Texto original del token interno. */
    fun tokenText(tokenId: Int): String = tokenTexts[tokenId]

    /** Tokens internos de una pregunta indexada. */
    fun tokensOf(questionId: Int): IntArray = questionTokens[questionId]

    /** Cantidad de tokens de contenido de una pregunta indexada. */
    fun tokenCount(questionId: Int): Int = questionTokens[questionId].size

    /** Texto original de una pregunta indexada. */
    fun textOf(questionId: Int): String = questionTexts[questionId]

    /**
     * Similitud Jaccard ponderada por IDF entre dos preguntas indexadas.
     *
     * El `merge` es lineal sobre los ids ordenados: `O(|A| + |B|)` y sin
     * asignaciones, que es lo que hace viable el costo por par.
     */
    fun similarity(a: Int, b: Int): Double = compare(a, b).similarity

    /**
     * Compara dos preguntas indexadas y devuelve la similitud y la cantidad de
     * tokens compartidos.
     */
    fun compare(a: Int, b: Int): Comparison {
        if (a == b) return Comparison(1.0, questionTokens[a].size, questionTokens[a].lastOrNull() ?: -1)
        val x = questionTokens[a]
        val y = questionTokens[b]
        if (x.isEmpty() || y.isEmpty()) return Comparison(0.0, 0)
        var intersection = 0.0
        var union = 0.0
        var shared = 0
        var sharedToken = -1
        var i = 0
        var j = 0
        while (i < x.size && j < y.size) {
            when {
                x[i] == y[j] -> {
                    val w = weights[x[i]]
                    intersection += w
                    union += w
                    shared++
                    sharedToken = x[i]
                    i++
                    j++
                }
                x[i] < y[j] -> {
                    union += weights[x[i]]
                    i++
                }
                else -> {
                    union += weights[y[j]]
                    j++
                }
            }
        }
        while (i < x.size) {
            union += weights[x[i]]
            i++
        }
        while (j < y.size) {
            union += weights[y[j]]
            j++
        }
        val sim = if (union <= 0.0) 0.0 else intersection / union
        return Comparison(sim, shared, sharedToken)
    }

    /**
     * Similitud entre dos preguntas que **no** están indexadas, usando la IDF
     * del corpus como ponderación.
     *
     * Es la puerta de entrada para una comparación suelta ("¿estas dos
     * preguntas son la misma?"); el resto del detector trabaja con ids.
     */
    fun similarity(a: String, b: String): Double {
        checkBuilt()
        val wa = weightsOf(a)
        val wb = weightsOf(b)
        if (wa.isEmpty() && wb.isEmpty()) return 0.0
        var intersection = 0.0
        var union = 0.0
        for ((term, w) in wa) {
            union += w
            if (wb.containsKey(term)) intersection += w
        }
        for ((term, w) in wb) {
            if (!wa.containsKey(term)) union += w
        }
        return if (union <= 0.0) 0.0 else intersection / union
    }

    /**
     * Cota superior **rigurosa** de la similitud, sin intersectar:
     * `sim(A,B) ≤ min(Σ_A w) / max(Σ_A w)`.
     *
     * El numerador no puede pasar la suma menor y el denominador no puede
     * bajar de la suma mayor. Sirve para descartar pares sin calcularlos.
     */
    fun upperBound(a: Int, b: Int): Double {
        val sa = questionWeight[a]
        val sb = questionWeight[b]
        return minOf(sa, sb) / maxOf(sa, sb)
    }

    /**
     * Candidatas para la pregunta [questionId]: las que comparten al menos un
     * token "ancla" (frecuencia baja ⇒ discriminativo).
     *
     * Los tokens demasiado frecuentes (`df > maxAnchorDf`) **no** generan
     * candidatas: "hola" o "profesor" aparecen en cientos de preguntas y
     * convertirían el índice en un all-pairs disfrazado.
     *
     * @return los ids de las candidatas, en orden ascendente y sin repetir.
     */
    fun candidates(questionId: Int): IntArray {
        checkBuilt()
        stampCounter++
        val stamp = stampCounter
        val out = ArrayList<Int>()
        for (t in questionTokens[questionId]) {
            if (documentFreq[t] > maxAnchorDf) continue
            for (k in postingOffsets[t] until postingOffsets[t + 1]) {
                val candidate = postingIds[k]
                if (stamps[candidate] == stamp) continue
                stamps[candidate] = stamp
                out += candidate
            }
        }
        out.sort()
        return out.toIntArray()
    }

    private fun weightsOf(text: String): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (token in QuestionTokenizer.tokenize(text)) {
            if (!out.containsKey(token)) out[token] = idf(token)
        }
        return out
    }

    private fun checkBuilt() {
        check(isBuilt) { "QuestionSimilarity: falta llamar a build() antes de comparar." }
    }

    private fun distinctSorted(values: IntArray): IntArray {
        if (values.size <= 1) return values
        val sorted = values.sorted()
        var unique = 1
        for (i in 1 until sorted.size) {
            if (sorted[i] != sorted[unique - 1]) {
                sorted[unique] = sorted[i]
                unique++
            }
        }
        return sorted.copyOf(unique)
    }
}

/**
 * Un grupo de preguntas que la IA no está resolviendo.
 *
 * @property representative la pregunta más central del grupo: la de mayor
 *   suma de similitudes con las demás. Es la que se muestra al docente como
 *   ejemplo del cluster.
 * @property members los textos de todas las preguntas del grupo.
 */
data class QuestionCluster(
    val representative: String,
    val members: List<String>,
) {
    /** Cantidad de preguntas del grupo. */
    val size: Int get() = members.size
}

/**
 * Cluster con los ids internos de las preguntas (para poder agregarle
 * estadísticas sin volver a tokenizar).
 */
data class IndexedCluster(
    val representativeId: Int,
    val memberIds: List<Int>,
)

/**
 * Agrupa preguntas que son "la misma pregunta" usando union-find sobre el
 * grafo de similitud.
 *
 * El grafo tiene una arista entre dos preguntas cuando su similitud ponderada
 * supera el umbral (0,62 por defecto). Las componentes conexas de ese grafo son
 * los clusters: la unión es **transitiva** (si A~B y B~C entonces A, B y C
 * están en el mismo cluster) porque las paráfrasis forman cadenas.
 *
 * ### Reglas que evitan los falsos positivos caros
 *
 * 1. **Tokens compartidos ≥ 2**: dos preguntas que comparten un único token
 *    sólo se unen si ese token es *raro* (`df ≤ rareTokenDf`). Evita que
 *    "¿cómo se define X?" y "¿qué es X?" se unan sólo por "define".
 * 2. **Tokens de contenido mínimos**: una pregunta de un solo token ("hola")
 *    no forma cluster: sería ruido estadístico.
 * 3. **Cota superior**: si `upperBound < umbral` el par no puede llegar al
 *    umbral y ni se calcula.
 */
class QuestionClusterer(private val config: QuestionClusterConfig = QuestionClusterConfig()) {

    /**
     * Comparaciones reales de la última llamada a [cluster] o
     * [clusterIndexed]. Sirve para medir el costo y para verificar que la
     * poda funciona (con 1000 preguntas tiene que estar muy por debajo de
     * 500 000).
     */
    var lastComparisons: Int = 0
        private set

    /**
     * Agrupa un listado de preguntas.
     *
     * @param questions los textos de las preguntas de la cátedra.
     * @return los clusters, del más grande al más chico.
     */
    fun cluster(questions: List<String>): List<QuestionCluster> {
        val index = QuestionSimilarity(config.maxAnchorDf)
        for (question in questions) index.add(question)
        index.build()
        return clusterIndexed(index).map { cluster ->
            QuestionCluster(
                representative = index.textOf(cluster.representativeId),
                members = cluster.memberIds.map { index.textOf(it) },
            )
        }
    }

    /**
     * Agrupa un índice ya construido.
     *
     * Es la vía que usa el análisis por cátedra: así el índice se tokeniza una
     * sola vez y sirve para las comparaciones, el agrupamiento y el puntaje de
     * fracaso.
     */
    fun clusterIndexed(index: QuestionSimilarity): List<IndexedCluster> {
        val n = index.size
        lastComparisons = 0
        if (n == 0) return emptyList()

        val unionFind = UnionFind(n)
        // Centralidad: suma de similitudes de cada pregunta con las que se
        // comparó. El representante es la de mayor suma (la más "central").
        val centrality = DoubleArray(n)

        for (q in 0 until n) {
            if (index.tokenCount(q) < config.minTokensForMatch) continue
            for (c in index.candidates(q)) {
                if (c <= q) continue
                if (index.tokenCount(c) < config.minTokensForMatch) continue
                // Cota superior: si no puede llegar al umbral, ni se calcula.
                if (index.upperBound(q, c) < config.similarityThreshold) continue
                lastComparisons++
                val comparison = index.compare(q, c)
                if (!allowsMerge(comparison, index)) continue
                centrality[q] += comparison.similarity
                centrality[c] += comparison.similarity
                if (comparison.similarity >= config.similarityThreshold) {
                    unionFind.union(q, c)
                }
            }
        }

        val groups = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) {
            if (index.tokenCount(i) < config.minTokensForMatch) continue
            groups.getOrPut(unionFind.find(i)) { ArrayList() } += i
        }
        return groups.values.map { members ->
            val representative = members.maxByOrNull { centrality[it] } ?: members.first()
            IndexedCluster(representative, members.sorted())
        }.sortedWith(
            compareByDescending<IndexedCluster> { it.memberIds.size }
                .thenBy { index.textOf(it.representativeId) },
        )
    }

    /**
     * ¿El par se puede unir según las reglas de tokens compartidos?
     *
     * Con dos tokens en común o más, siempre. Con uno solo, sólo si ese token
     * es raro: "automata" (que aparece en muchas preguntas) no dice que dos
     * preguntas sean la misma, pero "turing" sí.
     */
    private fun allowsMerge(comparison: Comparison, index: QuestionSimilarity): Boolean {
        if (comparison.sharedTokens >= config.minSharedTokens) return true
        if (comparison.sharedTokens == 1 && comparison.sharedTokenId >= 0) {
            return index.documentFrequencyOfToken(comparison.sharedTokenId) <= config.rareTokenDf
        }
        return false
    }
}

/**
 * Union-find con compresión de camino y unión por rango.
 *
 * Es la estructura que convierte el grafo de similitud en componentes conexas
 * (los clusters).
 */
internal class UnionFind(n: Int) {

    private val parent = IntArray(n) { it }
    private val rank = IntArray(n)

    /** Raíz del conjunto que contiene [x]. */
    fun find(x: Int): Int {
        var root = x
        while (parent[root] != root) root = parent[root]
        var current = x
        while (parent[current] != current) {
            val next = parent[current]
            parent[current] = root
            current = next
        }
        return root
    }

    /** Une los conjuntos que contienen [a] y [b]. */
    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) return
        when {
            rank[ra] < rank[rb] -> parent[ra] = rb
            rank[ra] > rank[rb] -> parent[rb] = ra
            else -> {
                parent[rb] = ra
                rank[ra]++
            }
        }
    }
}
     