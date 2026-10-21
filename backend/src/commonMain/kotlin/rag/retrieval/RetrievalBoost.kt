package rag.retrieval

import kitoru.domain.Unit
import kitoru.domain.UnitTree
import kitoru.platform.stripDiacritics

/**
 * Ajustes de la puntuación por metadatos de la cátedra.
 *
 * Hay **dos factores**, con responsabilidades bien separadas:
 *
 *  - el **estático** ([boostFactor]): lo decide el docente. Un material
 *    etiquetado como "examen" o "parcial" pesa más en la recuperación. Se
 *    calcula al indexar y es el mismo para cualquier pregunta.
 *  - el **dinámico** ([queryFactor]): depende de lo que se preguntó. Si el
 *    alumno pregunta "de la unidad 2", los fragmentos de esa unidad suben.
 *
 * Los dos son **multiplicativos** y **acotados** (0.5 … 3.0). Multiplicar
 * conserva la semántica de BM25 (el orden relativo de los fragmentos con el
 * mismo factor no cambia) y acotar evita que un material marcado termine
 * respondiendo preguntas que no son suyas: sube de rango, no tapa.
 *
 * Todo es **determinista**: función pura de (metadatos del fragmento, consulta).
 * Cero modelo, cero red, cero reloj.
 *
 * ### Dónde se engancha
 *
 * El boosting se aplica **después** del BM25, en `KnowledgeBase.retrieve`:
 *
 * ```kotlin
 * fun retrieve(query: String, k: Int = cfg.topK): List<Chunk> {
 *     val candidatos = index.search(query, pool)          // ← BM25 puro
 *     val puntuados = RetrievalBoost.apply(              // ← boosting multiplicativo
 *         candidatos,
 *         query,
 *         unitTree,
 *     )
 *     return puntuados.take(k).map { it.first }
 * }
 * ```
 *
 * `Bm25Index` no se toca: el índice sigue siendo un BM25 pelado y el
 * boosting vive en una capa aparte, que se puede apagar o cambiar sin tocar
 * el índice.
 */
object RetrievalBoost {

    /** Peso extra si el material tiene una etiqueta de prioridad ("examen"). */
    const val PRIORITY_TAG = 1.4

    /** Peso extra si la consulta menciona la unidad del fragmento. */
    const val UNIT_MATCH = 1.3

    /** Peso extra si la consulta menciona el tema exacto del fragmento. */
    const val TOPIC_MATCH = 1.2

    /** Piso de cualquier factor: por debajo, el fragmento se hunde. */
    const val MIN_FACTOR = 0.5

    /** Techo de cualquier factor: por encima, el fragmento tapa al resto. */
    const val MAX_FACTOR = 3.0

    /** Etiquetas con las que el docente marca un material como prioritario. */
    val PRIORITY_TAGS: Set<String> = setOf("examen", "parcial", "final", "importante", "clave")

    /** Longitud mínima de un nombre de unidad para que la consulta lo mencione. */
    private const val MIN_NAME_MATCH = 4

    /**
     * Prior estática de un material: lo decide el docente, no la consulta.
     *
     * @param unitIds ids de las unidades a las que pertenece el material.
     *   No cambian el factor por sí solos: la jerarquía pesa en la cita y en el
     *   filtro de candidatos, no en el score léxico (BM25 no entiende
     *   jerarquías). Viajan en la firma para que el indexador pase siempre el
     *   mismo contexto y para que el peso por unidad pueda agregarse más
     *   adelante sin cambiar los llamadores.
     * @param boostTags etiquetas libres del material ("examen", "parcial"...).
     * @param priorityTags etiquetas que marcan un material como prioritario.
     * @param base peso propio del material (1.0 por defecto).
     * @return el factor estático, acotado a [MIN_FACTOR] … [MAX_FACTOR].
     */
    fun boostFactor(
        unitIds: List<String>,
        boostTags: Set<String>,
        priorityTags: Set<String> = PRIORITY_TAGS,
        base: Double = 1.0,
    ): Double {
        var factor = base
        val prioritarias = priorityTags.map { normalize(it) }.toSet()
        if (boostTags.any { normalize(it) in prioritarias }) factor *= PRIORITY_TAG
        return factor.coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /**
     * Factor dependiente de la consulta.
     *
     * Si el alumno menciona una unidad ("de la unidad 2") los fragmentos de
     * esa unidad suben; si menciona el tema exacto ("del tema 2.1") suben un
     * poco más, porque la pregunta es más precisa.
     *
     * @param query la pregunta del alumno.
     * @param unitTree la taxonomía de la cátedra (puede estar vacía).
     * @return el factor, acotado a [MIN_FACTOR] … [MAX_FACTOR].
     */
    fun queryFactor(query: String, unitTree: UnitTree): Double {
        val mencionadas = mentionedUnits(query, unitTree)
        if (mencionadas.isEmpty()) return 1.0
        var factor = UNIT_MATCH
        // Mencionar un tema es más específico que mencionar la unidad: suma.
        if (mencionadas.any { unitTree.depth(it) >= 1 }) factor *= TOPIC_MATCH
        return factor.coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /**
     * Factor dependiente de la consulta **para un fragmento concreto**.
     *
     * A diferencia de [queryFactor], que devuelve el factor de la consulta en
     * general, éste dice si este fragmento está en el camino de la pregunta:
     * un fragmento de otra unidad no se mueve aunque la consulta haya
     * mencionado alguna.
     *
     * @param query la pregunta del alumno.
     * @param unitTree la taxonomía de la cátedra.
     * @param chunk el fragmento candidato.
     * @return el factor, acotado a [MIN_FACTOR] … [MAX_FACTOR].
     */
    fun queryFactor(query: String, unitTree: UnitTree, chunk: Chunk): Double {
        val mencionadas = mentionedUnits(query, unitTree)
        if (mencionadas.isEmpty()) return 1.0
        // El alcance de la mención incluye los ancestros: nombrar el tema 2.1
        // también alcanza a la unidad 2 y a sus otros temas.
        val alcance = mutableSetOf<String>()
        for (id in mencionadas) alcance += unitTree.pathIds(id)
        var factor = 1.0
        if (chunk.unitIds.any { id -> alcance.any { unitTree.isUnder(id, it) } }) {
            factor *= UNIT_MATCH
        }
        val temas = mencionadas.filter { unitTree.depth(it) >= 1 }
        if (chunk.unitIds.any { id -> temas.any { unitTree.isUnder(id, it) } }) {
            factor *= TOPIC_MATCH
        }
        return factor.coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /**
     * Ids de las unidades que la consulta menciona.
     *
     * Se detectan de dos maneras:
     *  - por el código, cuando la consulta dice "unidad 2" o "tema 2.1";
     *  - por el nombre del nodo, cuando la consulta usa sus palabras
     *    ("¿qué es la regresión lineal?").
     *
     * @param query la pregunta del alumno.
     * @param unitTree la taxonomía de la cátedra.
     * @return los ids de las unidades mencionadas (puede estar vacío).
     */
    fun mentionedUnits(query: String, unitTree: UnitTree): Set<String> {
        val q = normalize(query)
        if (q.isBlank()) return emptySet()
        val mencionadas = mutableSetOf<String>()
        for (unidad in unitTree.units) {
            if (mentionsUnit(q, unidad, unitTree)) mencionadas += unidad.id
        }
        return mencionadas
    }

    /**
     * Aplica el boosting a un fragmento ya puntuado por el índice.
     *
     * El score final es `score × factorEstatico × factorConsulta`. El boosting
     * es multiplicativo y va **después** del BM25: sumar una constante no
     * reordena nada, multiplicar sí.
     *
     * @param chunk el fragmento.
     * @param score su score de BM25.
     * @param staticFactor el factor estático.
     * @param queryFactor el factor de la consulta.
     * @return el score final.
     */
    fun apply(chunk: Chunk, score: Double, staticFactor: Double, queryFactor: Double): Double =
        score * staticFactor * queryFactor

    /**
     * Aplica el boosting a los candidatos ya puntuados por el índice.
     *
     * @param candidates fragmentos con su score de BM25.
     * @param staticFactor el factor estático (el mismo para todos).
     * @param queryFactor el factor de la consulta (el mismo para todos).
     * @return los candidatos con su score final, ordenados de mayor a menor.
     *   El orden es estable: los empates conservan el orden del índice.
     */
    fun apply(
        candidates: List<Pair<Chunk, Double>>,
        staticFactor: Double,
        queryFactor: Double,
    ): List<Pair<Chunk, Double>> =
        candidates
            .map { (chunk, score) -> chunk to apply(chunk, score, staticFactor, queryFactor) }
            .sortedByDescending { it.second }

    /**
     * Aplica el boosting usando los metadatos del fragmento y la consulta.
     *
     * Es la variante que usa el `KnowledgeBase`: el factor estático sale de las
     * etiquetas del fragmento y el dinámico de si el fragmento está en el
     * camino de la pregunta.
     *
     * @param candidates fragmentos con su score de BM25.
     * @param query la pregunta del alumno.
     * @param unitTree la taxonomía de la cátedra.
     * @param priorityTags etiquetas que marcan un material como prioritario.
     * @return los candidatos con su score final, ordenados de mayor a menor.
     */
    fun apply(
        candidates: List<Pair<Chunk, Double>>,
        query: String,
        unitTree: UnitTree,
        priorityTags: Set<String> = PRIORITY_TAGS,
    ): List<Pair<Chunk, Double>> =
        candidates
            .map { (chunk, score) ->
                val estatico = boostFactor(chunk.unitIds, chunk.tags.toSet(), priorityTags)
                val dinamico = queryFactor(query, unitTree, chunk)
                chunk to apply(chunk, score, estatico, dinamico)
            }
            .sortedByDescending { it.second }

    /**
     * ¿La consulta menciona esta unidad?
     *
     * Por código hace falta la palabra "unidad" o "tema": un "2" suelto
     * ("2 ejemplos") no puede referenciar a la segunda unidad.
     */
    private fun mentionsUnit(q: String, unidad: Unit, unitTree: UnitTree): Boolean {
        val nombre = normalize(unidad.name)
        if (nombre.length >= MIN_NAME_MATCH && containsPhrase(q, nombre)) return true
        val numero = unitTree.number(unidad.id)
        if (numero.isNotBlank() && (q.contains("unidad") || q.contains("tema")) && containsPhrase(q, numero)) {
            return true
        }
        return false
    }

    /**
     * Forma canónica de un texto para comparar: minúsculas, sin acentos y sin
     * puntuación (salvo el punto, que separa los códigos "2.1").
     */
    private fun normalize(text: String): String =
        stripDiacritics(text.trim().lowercase())
            .replace(Regex("[^a-z0-9.]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** ¿El texto contiene la frase completa, por palabra? */
    private fun containsPhrase(texto: String, frase: String): Boolean {
        val t = texto.split(' ').filter { it.isNotBlank() }
        val f = frase.split(' ').filter { it.isNotBlank() }
        if (f.isEmpty()) return false
        if (f.size == 1) return t.contains(f[0])
        for (i in 0..(t.size - f.size)) {
            if (t.subList(i, i + f.size) == f) return true
        }
        return false
    }
}
