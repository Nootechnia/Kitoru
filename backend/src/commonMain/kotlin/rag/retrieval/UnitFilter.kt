package rag.retrieval

import kitoru.domain.UnitTree

/**
 * Filtro por unidad y tema: recorta los candidatos **antes** de puntuar.
 *
 * Filtrar antes es mejor que filtrar después por dos razones:
 *
 *  - el BM25 calcula el score de todo el corpus; si el alumno pidió "repasá la
 *    unidad 2", el material de la unidad 5 no tiene por qué competir;
 *  - un filtro posterior dejaría huecos en la lista de resultados (el top-k
 *    lleno de material que después se descarta).
 *
 * El filtro es una **restricción**, no un sesgo: si se filtra por una unidad,
 * los fragmentos que no están en esa unidad quedan fuera. Por eso la lista
 * vacía significa "no filtrar" y no "filtrar por nada": una cátedra sin
 * taxonomía se comporta exactamente como antes.
 *
 * Los ids que usa son los que el fragmento lleva en `unitIds`. Por
 * convención el indexador guarda ahí la unidad primaria **y sus ancestros**
 * (`["u2", "u2.1"]`), así que filtrar por "u2" trae también los fragmentos
 * de "u2.1" sin necesidad de consultar el árbol. Cuando sí se pasa el árbol,
 * los descendientes se resuelven por la jerarquía real.
 */
object UnitFilter {

    /**
     * Deja sólo los fragmentos de las unidades indicadas.
     *
     * @param chunks los candidatos.
     * @param unitIds ids de las unidades admitidas. Si está vacío, no filtra.
     * @param unitTree taxonomía de la cátedra; si se pasa, se incluyen los
     *   descendientes de cada unidad indicada.
     * @return los fragmentos que quedan, en el mismo orden en que vinieron.
     */
    fun filter(
        chunks: List<Chunk>,
        unitIds: List<String>,
        unitTree: UnitTree? = null,
    ): List<Chunk> {
        if (unitIds.isEmpty()) return chunks
        val admitidas = LinkedHashSet<String>()
        for (id in unitIds) {
            admitidas += id
            unitTree?.let { admitidas += it.descendants(id) }
        }
        return chunks.filter { chunk -> chunk.unitIds.any { it in admitidas } }
    }

    /**
     * Deja sólo los fragmentos de una unidad y de sus descendientes.
     *
     * "Repasá la unidad 2" tiene que traer los temas que cuelgan de ella, no
     * sólo los fragmentos etiquetados con la unidad exacta.
     *
     * @param chunks los candidatos.
     * @param unitId id de la unidad.
     * @param unitTree taxonomía de la cátedra; si se pasa, los descendientes
     *   se resuelven por la jerarquía real.
     * @return los fragmentos que quedan, en el mismo orden en que vinieron.
     */
    fun filterByUnit(chunks: List<Chunk>, unitId: String, unitTree: UnitTree? = null): List<Chunk> =
        filter(chunks, listOf(unitId), unitTree)
}
