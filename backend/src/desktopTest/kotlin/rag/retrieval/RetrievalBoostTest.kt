package rag.retrieval

import kitoru.domain.Unit
import kitoru.domain.UnitTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests del boosting y del filtro por unidad del RAG.
 *
 * Estas dos piezas son las que hacen que la taxonomía de la cátedra se note
 * en la recuperación: el boosting sube el material que el docente marcó (o el
 * que la pregunta nombra) y el filtro deja fuera lo que el alumno no pidió.
 *
 * Los tests fijan las reglas que no se negocian:
 *  - los factores están **acotados** (0.5 … 3.0): un material marcado sube de
 *    rango, pero no tapa al material que la pregunta describe;
 *  - el boosting es **multiplicativo** y va **después** del BM25;
 *  - un material sin etiquetas tiene factor 1 (no se lo toca);
 *  - filtrar por una unidad incluye sus descendientes;
 *  - sin filtro, el comportamiento es el de siempre.
 */
class RetrievalBoostTest {

    /**
     * Una cátedra de ejemplo:
     *
     *   Unidad 2 (Modelos lineales)
     *     Tema 2.1 (Regresión)
     *     Tema 2.2 (Clasificación)
     */
    private val unidades = listOf(
        Unit(id = "u2", courseId = "c1", parentId = null, name = "Modelos lineales", code = "2", order = 2),
        Unit(id = "u2.1", courseId = "c1", parentId = "u2", name = "Regresión", code = "2.1", order = 1),
        Unit(id = "u2.2", courseId = "c1", parentId = "u2", name = "Clasificación", code = "2.2", order = 2),
        Unit(id = "u3", courseId = "c1", parentId = null, name = "Aprendizaje", code = "3", order = 3),
    )

    private fun arbol() = UnitTree.of(unidades)

    private fun fragmento(
        texto: String,
        unidades: List<String> = emptyList(),
        etiquetas: List<String> = emptyList(),
    ) = Chunk(
        source = "cátedra",
        path = "Apuntes-IA",
        heading = "Regresión lineal",
        text = texto,
        title = "Apuntes-IA",
        unitIds = unidades,
        unitPath = listOf("Unidad 2", "Tema 2.1"),
        tags = etiquetas,
    )

    // ==================================================================
    // A. El factor estático
    // ==================================================================

    /** Un material marcado como examen pesa más que uno sin marcas. */
    @Test
    fun `un material marcado como examen pesa mas`() {
        val factor = RetrievalBoost.boostFactor(listOf("u2.1"), setOf("examen"))

        assertEquals(1.4, factor, 0.0001, "el material de examen pesa ×1.4")
    }

    /** Un material sin etiquetas tiene factor 1: no se lo toca. */
    @Test
    fun `un material sin etiquetas tiene factor uno`() {
        val factor = RetrievalBoost.boostFactor(listOf("u2.1"), emptySet())

        assertEquals(1.0, factor, 0.0001, "sin marcas, el fragmento no se mueve")
    }

    /** La etiqueta de prioridad no depende de las mayúsculas. */
    @Test
    fun `la etiqueta de prioridad no depende de las mayusculas`() {
        val factor = RetrievalBoost.boostFactor(listOf("u2.1"), setOf("Parcial"))

        assertEquals(1.4, factor, 0.0001)
    }

    /** Una etiqueta que no es de prioridad no mueve nada. */
    @Test
    fun `una etiqueta que no es de prioridad no mueve nada`() {
        val factor = RetrievalBoost.boostFactor(listOf("u2.1"), setOf("regresión"))

        assertEquals(1.0, factor, 0.0001)
    }

    /** El factor estático está acotado: ni sube sin límite ni baja de cero. */
    @Test
    fun `el factor estatico esta acotado`() {
        val alto = RetrievalBoost.boostFactor(listOf("u2.1"), setOf("examen"), base = 10.0)
        val bajo = RetrievalBoost.boostFactor(listOf("u2.1"), emptySet(), base = 0.01)

        assertEquals(RetrievalBoost.MAX_FACTOR, alto, 0.0001, "el techo es 3.0")
        assertEquals(RetrievalBoost.MIN_FACTOR, bajo, 0.0001, "el piso es 0.5")
    }

    // ==================================================================
    // B. El factor de la consulta
    // ==================================================================

    /** Una consulta que no nombra ninguna unidad no mueve nada. */
    @Test
    fun `una consulta que no nombra unidades tiene factor uno`() {
        val factor = RetrievalBoost.queryFactor("¿qué es un perceptrón?", arbol())

        assertEquals(1.0, factor, 0.0001)
    }

    /** Una consulta que nombra la unidad sube ×1.3. */
    @Test
    fun `una consulta que nombra la unidad sube`() {
        val factor = RetrievalBoost.queryFactor("repasemos la unidad 2", arbol())

        assertEquals(1.3, factor, 0.0001)
    }

    /** Una consulta que nombra el tema exacto sube más: es más específica. */
    @Test
    fun `una consulta que nombra el tema sube mas`() {
        val factor = RetrievalBoost.queryFactor("repasemos el tema 2.1", arbol())

        assertEquals(1.3 * 1.2, factor, 0.0001)
    }

    /** El factor de la consulta también está acotado. */
    @Test
    fun `el factor de la consulta esta acotado`() {
        val factor = RetrievalBoost.queryFactor("repasemos el tema 2.1", arbol())

        assertTrue(
            factor in RetrievalBoost.MIN_FACTOR..RetrievalBoost.MAX_FACTOR,
            "el factor tiene que estar acotado: $factor",
        )
    }

    /** Un fragmento de otra unidad no se mueve aunque la consulta nombre alguna. */
    @Test
    fun `un fragmento de otra unidad no se mueve`() {
        val chunk = fragmento("texto", unidades = listOf("u3"))

        val factor = RetrievalBoost.queryFactor("repasemos la unidad 2", arbol(), chunk)

        assertEquals(1.0, factor, 0.0001)
    }

    /** Un fragmento de la unidad nombrada sí se mueve. */
    @Test
    fun `un fragmento de la unidad nombrada se mueve`() {
        val chunk = fragmento("texto", unidades = listOf("u2.1"))

        val factor = RetrievalBoost.queryFactor("repasemos la unidad 2", arbol(), chunk)

        assertEquals(1.3, factor, 0.0001)
    }

    // ==================================================================
    // C. La aplicación del boosting
    // ==================================================================

    /** El score final es el de BM25 por el estático por el dinámico. */
    @Test
    fun `el score final multiplica el estatico y el dinamico`() {
        val chunk = fragmento("texto", unidades = listOf("u2.1"), etiquetas = listOf("examen"))

        val estatico = RetrievalBoost.boostFactor(chunk.unitIds, chunk.tags.toSet())
        val dinamico = RetrievalBoost.queryFactor("repasemos la unidad 2", arbol(), chunk)
        val final = RetrievalBoost.apply(chunk, 2.0, estatico, dinamico)

        assertEquals(2.0 * 1.4 * 1.3, final, 0.0001)
    }

    /** Un fragmento sin marcas conserva su score de BM25. */
    @Test
    fun `un fragmento sin marcas conserva su score`() {
        val chunk = fragmento("texto", unidades = listOf("u2.1"))

        val final = RetrievalBoost.apply(chunk, 2.0, staticFactor = 1.0, queryFactor = 1.0)

        assertEquals(2.0, final, 0.0001)
    }

    /** Aplicar el boosting reordena: el material de examen sube. */
    @Test
    fun `aplicar el boosting reordena los candidatos`() {
        val normal = fragmento("normal", unidades = listOf("u2.1"))
        val deExamen = fragmento("de examen", unidades = listOf("u2.1"), etiquetas = listOf("examen"))

        val candidatos = listOf(normal to 3.0, deExamen to 3.0)
        val resultado = RetrievalBoost.apply(candidatos, "regresión", arbol())

        assertEquals("de examen", resultado.first().first.text, "el material de examen queda arriba")
        assertTrue(resultado.first().second > resultado.last().second, "y con más score")
    }

    /** El boosting no inventa fragmentos: sólo reordena los que hay. */
    @Test
    fun `el boosting no inventa fragmentos`() {
        val candidatos = listOf(fragmento("normal", unidades = listOf("u2.1")) to 3.0)

        val resultado = RetrievalBoost.apply(candidatos, "regresión", arbol())

        assertEquals(1, resultado.size)
    }

    // ==================================================================
    // D. El filtro por unidad
    // ==================================================================

    /** El filtro deja sólo los fragmentos de las unidades pedidas. */
    @Test
    fun `el filtro deja solo los fragmentos de las unidades pedidas`() {
        val deLaUnidad = fragmento("de la unidad", unidades = listOf("u2.1"))
        val deOtra = fragmento("de otra", unidades = listOf("u3"))

        val resultado = UnitFilter.filter(listOf(deLaUnidad, deOtra), listOf("u2.1"))

        assertEquals(1, resultado.size)
        assertEquals("de la unidad", resultado.first().text)
    }

    /** Una lista de unidades vacía no filtra: el comportamiento es el de siempre. */
    @Test
    fun `una lista de unidades vacia no filtra`() {
        val chunks = listOf(
            fragmento("uno", unidades = listOf("u2.1")),
            fragmento("dos", unidades = listOf("u3")),
        )

        val resultado = UnitFilter.filter(chunks, emptyList())

        assertEquals(2, resultado.size, "sin filtro entran todos")
    }

    /** Filtrar por una unidad trae también sus descendientes. */
    @Test
    fun `filtrar por una unidad incluye sus descendientes`() {
        val deLaUnidad = fragmento("de la unidad", unidades = listOf("u2"))
        val delTema = fragmento("del tema", unidades = listOf("u2.1"))
        val deOtra = fragmento("de otra", unidades = listOf("u3"))

        val resultado = UnitFilter.filterByUnit(
            listOf(deLaUnidad, delTema, deOtra),
            "u2",
            arbol(),
        )

        assertEquals(2, resultado.size, "la unidad y su tema")
        assertTrue(resultado.any { it.text == "del tema" }, "el descendiente entra")
        assertTrue(resultado.none { it.text == "de otra" }, "otra unidad no entra")
    }

    /**
     * Sin árbol, el filtro se apoya en la convención de ids: el fragmento
     * lleva la ruta de su unidad (la unidad y sus ancestros).
     */
    @Test
    fun `sin arbol el filtro usa la ruta que trae el fragmento`() {
        val delTema = fragmento("del tema", unidades = listOf("u2", "u2.1"))
        val deOtra = fragmento("de otra", unidades = listOf("u3"))

        val resultado = UnitFilter.filterByUnit(listOf(delTema, deOtra), "u2")

        assertEquals(1, resultado.size)
        assertEquals("del tema", resultado.first().text)
    }

    // ==================================================================
    // E. La cita
    // ==================================================================

    /** La cita del fragmento compone la jerarquía con el material y la sección. */
    @Test
    fun `la cita del fragmento compone la jerarquia`() {
        val chunk = fragmento("texto", unidades = listOf("u2.1"))

        assertEquals(
            "Unidad 2 > Tema 2.1 > Apuntes-IA > Regresión lineal",
            chunk.citationLabel,
        )
    }
}
