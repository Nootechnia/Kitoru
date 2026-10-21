package rag.retrieval

import kotlin.math.ln

/**
 * Índice BM25 en memoria, con **índice invertido**.
 *
 * La versión anterior recorría **todos** los fragmentos por cada término de la
 * consulta (O(chunks × términos)) y después ordenaba el array completo de
 * scores para quedarse con los `k` mejores. Con 3.000 fragmentos y una consulta
 * de 10 términos eso son 30.000 lookups más un sort de 3.000.
 *
 * Ahora el índice guarda, para cada término, la lista de posting
 * `(fragmento, frecuencia)`: la consulta sólo toca las posting lists de los
 * términos que aparecen en la consulta, y el top-K se arma sobre los candidatos
 * que efectivamente tocaron algún término.
 *
 * **El score no cambia**: es la misma fórmula, con los mismos `df`, los mismos
 * `lens` y la misma `avgLen`, y las sumas se acumulan en el mismo orden (el de
 * los términos de la consulta). Por eso los resultados son idénticos bit a bit
 * a los del recorrido lineal: lo que cambia es el costo, no la semántica.
 */
class Bm25Index(
    private val chunks: List<Chunk>,
    private val policy: TokenPolicy = TokenPolicy.LEGACY,
) {
    private val k1 = 1.5
    private val b = 0.75

    private val termIds: HashMap<String, Int>
    private val postings: List<Postings>
    private val lens: IntArray
    private val avgLen: Double

    init {
        val built = build(chunks, policy)
        termIds = built.termIds
        postings = built.postings
        lens = built.lens
        avgLen = built.avgLen
    }

    /** Cantidad de fragmentos indexados. */
    val size get() = chunks.size

    /**
     * Busca los fragmentos relevantes.
     *
     * @param query la consulta del alumno.
     * @param k cuántos fragmentos se devuelven como máximo.
     */
    fun search(query: String, k: Int): List<Pair<Chunk, Double>> {
        if (chunks.isEmpty() || k <= 0) return emptyList()
        val n = chunks.size.toDouble()
        val scores = DoubleArray(chunks.size)
        val marcados = BooleanArray(chunks.size)
        val tocados = IntList()

        for (term in tokenize(query).distinct()) {
            val id = termIds[term] ?: continue
            val posting = postings[id]
            val d = posting.docs.size
            val idf = ln(1 + (n - d + 0.5) / (d + 0.5))
            val docs = posting.docs.toIntArray()
            val tfs = posting.tfs.toIntArray()
            for (p in docs.indices) {
                val i = docs[p]
                val f = tfs[p]
                scores[i] += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * lens[i] / avgLen))
                if (!marcados[i]) {
                    marcados[i] = true
                    tocados.add(i)
                }
            }
        }

        // Top-K acotado: sólo los fragmentos que tocaron algún término pueden
        // entrar, y el desempate es por índice ascendente (como el sort estable
        // del recorrido lineal).
        val mejoresIdx = IntArray(k)
        val mejoresScore = DoubleArray(k)
        var mejores = 0
        for (p in 0 until tocados.size) {
            val i = tocados[p]
            val score = scores[i]
            if (score <= 0.0) continue
            var pos = mejores
            while (pos > 0 && mejor(i, score, mejoresIdx[pos - 1], mejoresScore[pos - 1])) pos--
            if (pos >= k) continue
            var j = if (mejores < k) mejores else k - 1
            while (j > pos) {
                mejoresIdx[j] = mejoresIdx[j - 1]
                mejoresScore[j] = mejoresScore[j - 1]
                j--
            }
            mejoresIdx[pos] = i
            mejoresScore[pos] = score
            if (mejores < k) mejores++
        }
        return (0 until mejores).map { chunks[mejoresIdx[it]] to mejoresScore[it] }
    }

    /**
     * Cuántos fragmentos contienen el término (para diagnóstico).
     *
     * @param term el término buscado.
     */
    fun documentFrequency(term: String): Int {
        val id = termIds[term] ?: return 0
        return postings[id].docs.size
    }

    companion object {
        /**
         * Tokeniza con la política histórica (la del índice por defecto).
         *
         * @param text el texto a tokenizar.
         */
        fun tokenize(text: String): List<String> = TextTokenizer.tokenize(text, TokenPolicy.LEGACY)

        /**
         * Tokeniza con la política dada.
         *
         * @param text el texto a tokenizar.
         * @param policy la política de tokenización.
         */
        fun tokenize(text: String, policy: TokenPolicy): List<String> = TextTokenizer.tokenize(text, policy)
    }

    /** ¿El par (a) va antes que (b) en el ranking? */
    private fun mejor(a: Int, scoreA: Double, b: Int, scoreB: Double): Boolean =
        scoreA > scoreB || (scoreA == scoreB && a < b)

    // ------------------------------------------------------------------
    // Construcción
    // ------------------------------------------------------------------

    private class Postings {
        val docs = IntList()
        val tfs = IntList()
    }

    private class IndexData(
        val termIds: HashMap<String, Int>,
        val postings: List<Postings>,
        val lens: IntArray,
        val avgLen: Double,
    )

    private fun build(chunks: List<Chunk>, policy: TokenPolicy): IndexData {
        val termIds = HashMap<String, Int>()
        val postings = ArrayList<Postings>()
        val lens = IntArray(chunks.size)

        for ((i, chunk) in chunks.withIndex()) {
            val tokens = tokenize("${chunk.heading} ${chunk.path} ${chunk.text}", policy)
            lens[i] = tokens.size
            val counts = HashMap<String, Int>()
            for (token in tokens) counts[token] = (counts[token] ?: 0) + 1
            for ((term, f) in counts) {
                val id = termIds.getOrPut(term) {
                    postings.add(Postings())
                    postings.size - 1
                }
                postings[id].docs.add(i)
                postings[id].tfs.add(f)
            }
        }

        val avgLen = if (lens.isEmpty()) 1.0 else lens.average().coerceAtLeast(1.0)
        return IndexData(termIds, postings, lens, avgLen)
    }
}

/** Lista de enteros que crece sin copiar la de atrás en cada agregado. */
private class IntList(initialCapacity: Int = 8) {
    private var data = IntArray(initialCapacity)
    private var count = 0

    fun add(value: Int) {
        if (count == data.size) data = data.copyOf(data.size * 2)
        data[count] = value
        count++
    }

    operator fun get(index: Int): Int = data[index]

    val size get() = count

    fun toIntArray(): IntArray = data.copyOf(count)
}
