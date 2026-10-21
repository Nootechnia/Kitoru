package kitoru.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests de la taxonomía de la cátedra ([Unit], [UnitTree] y la asignación por
 * etiquetas).
 *
 * La taxonomía es **opcional** pero, cuando existe, es la estructura que
 * ordena el material y la que hace que la cita del RAG sea navegable. Estos
 * tests fijan las reglas que no se negocian:
 *  - la ruta de un nodo es siempre de la raíz hacia abajo;
 *  - un árbol con un padre inexistente o con un ciclo **no** se construye:
 *    el docente tiene que ver el error, no una app que se cuelgue;
 *  - la asignación por etiquetas resuelve los ids sin que el docente elija
 *    unidades a mano.
 */
class UnitTest {

    /**
     * Una cátedra de ejemplo: dos unidades con temas y una unidad sin temas.
     *
     *   Unidad 1 (Paradigmas)
     *   Unidad 2 (Modelos lineales)
     *     Tema 2.1 (Regresión)
     *     Tema 2.2 (Clasificación)
     *   Unidad 3 (Aprendizaje)
     */
    private val unidades = listOf(
        Unit(id = "u1", courseId = "c1", parentId = null, name = "Paradigmas", code = "1", order = 1),
        Unit(id = "u2", courseId = "c1", parentId = null, name = "Modelos lineales", code = "2", order = 2),
        Unit(id = "u2.1", courseId = "c1", parentId = "u2", name = "Regresión", code = "2.1", order = 1),
        Unit(id = "u2.2", courseId = "c1", parentId = "u2", name = "Clasificación", code = "2.2", order = 2),
        Unit(id = "u3", courseId = "c1", parentId = null, name = "Aprendizaje", code = "3", order = 3),
    )

    private fun arbol() = UnitTree.of(unidades)

    private fun material(vararg tags: String) = Material(
        id = "m1",
        courseId = "c1",
        title = "Apuntes-IA",
        tags = tags.toList(),
    )

    // ==================================================================
    // A. La ruta de un nodo
    // ==================================================================

    /**
     * La ruta de un tema es la unidad y el tema, en ese orden: es lo que va
     * en la cita ("Unidad 2 > Tema 2.1").
     */
    @Test
    fun `la ruta de un tema arma unidad y tema`() {
        val arbol = arbol()

        assertEquals(listOf("u2", "u2.1"), arbol.pathIds("u2.1"))
        assertEquals(listOf("Unidad 2", "Tema 2.1"), arbol.pathLabels("u2.1"))
        assertEquals("Unidad 2 > Tema 2.1", arbol.pathLabel("u2.1"))
    }

    /** La ruta de una unidad es sólo la unidad: no hay nada arriba. */
    @Test
    fun `la ruta de una unidad es la unidad sola`() {
        val arbol = arbol()

        assertEquals(listOf("Unidad 2"), arbol.pathLabels("u2"))
        assertEquals(0, arbol.depth("u2"))
        assertEquals(1, arbol.depth("u2.1"))
    }

    /** Un id que no existe no tiene ruta: la cita degrada a "Título > Sección". */
    @Test
    fun `un id que no existe no tiene ruta`() {
        assertEquals(emptyList(), arbol().path("no-existe"))
        assertEquals(emptyList(), arbol().pathLabels("no-existe"))
    }

    /**
     * Si el docente no escribió códigos, el número se deriva del orden:
     * la segunda unidad con su primer tema son "2" y "2.1".
     */
    @Test
    fun `el numero se deriva del orden cuando el docente no escribio codigos`() {
        val sinCodigos = listOf(
            Unit(id = "u2", courseId = "c1", parentId = null, name = "Modelos lineales", order = 2),
            Unit(id = "u2.1", courseId = "c1", parentId = "u2", name = "Regresión", order = 1),
        )
        val arbol = UnitTree.of(sinCodigos)

        assertEquals(listOf("Unidad 2", "Tema 2.1"), arbol.pathLabels("u2.1"))
    }

    // ==================================================================
    // B. Integridad del árbol
    // ==================================================================

    /** Un padre que no existe es un error: el árbol no se construye. */
    @Test
    fun `un padre que no existe hace fallar la construccion`() {
        val huerfano = listOf(
            Unit(id = "t1", courseId = "c1", parentId = "fantasma", name = "Regresión", order = 1),
        )

        val error = assertFailsWith<IllegalStateException> { UnitTree.of(huerfano) }

        assertTrue(
            error.message!!.contains("padre que no existe"),
            "el mensaje tiene que decir qué está roto: ${error.message}",
        )
    }

    /** Un ciclo de padres es un error: el árbol no se construye. */
    @Test
    fun `un ciclo de padres hace fallar la construccion`() {
        val conCiclo = listOf(
            Unit(id = "a", courseId = "c1", parentId = "b", name = "A", order = 1),
            Unit(id = "b", courseId = "c1", parentId = "a", name = "B", order = 1),
        )

        val error = assertFailsWith<IllegalStateException> { UnitTree.of(conCiclo) }

        assertTrue(
            error.message!!.contains("ciclo"),
            "el mensaje tiene que decir qué está roto: ${error.message}",
        )
    }

    /** Una unidad que es su propio padre también es un ciclo. */
    @Test
    fun `una unidad que es su propio padre hace fallar la construccion`() {
        val consigoMisma = listOf(
            Unit(id = "a", courseId = "c1", parentId = "a", name = "A", order = 1),
        )

        val error = assertFailsWith<IllegalStateException> { UnitTree.of(consigoMisma) }
        assertTrue(error.message!!.contains("propio padre"), error.message)
    }

    /**
     * La carga tolerante (`lenient`) no falla: un material que apunta a una
     * unidad borrada no puede impedir que la cátedra abra.
     */
    @Test
    fun `el arbol tolerante se construye aunque falte el padre`() {
        val huerfano = listOf(
            Unit(id = "t1", courseId = "c1", parentId = "fantasma", name = "Regresión", order = 1),
        )

        val arbol = UnitTree.lenient(huerfano)

        assertEquals(1, arbol.units.size)
        assertTrue(arbol.problems().isNotEmpty(), "el problema se puede mostrar igual")
    }

    // ==================================================================
    // C. Descendientes
    // ==================================================================

    /** Los descendientes de la unidad 2 son sus dos temas. */
    @Test
    fun `los descendientes son el subarbol`() {
        val arbol = arbol()

        assertEquals(listOf("u2", "u2.1", "u2.2"), arbol.descendants("u2"))
        assertEquals(listOf("u2.1"), arbol.descendants("u2.1"))
        assertEquals(emptyList(), arbol.descendants("no-existe"))
    }

    /** Un tema es descendiente de su unidad, y no al revés. */
    @Test
    fun `un tema cuelga de su unidad`() {
        val arbol = arbol()

        assertTrue(arbol.isUnder("u2.1", "u2"))
        assertTrue(arbol.isUnder("u2.1", "u2.1"))
        assertTrue(!arbol.isUnder("u2", "u2.1"))
        assertTrue(!arbol.isUnder("u3", "u2"))
    }

    // ==================================================================
    // D. Asignación por etiquetas
    // ==================================================================

    /** "unidad:2" asigna el material a la segunda unidad. */
    @Test
    fun `la etiqueta de unidad asigna por orden`() {
        assertEquals(listOf("u2"), assignUnits(material("unidad:2"), arbol()))
    }

    /** "tema:2.1" asigna el material al tema con ese código. */
    @Test
    fun `la etiqueta de tema asigna por codigo`() {
        assertEquals(listOf("u2.1"), assignUnits(material("tema:2.1"), arbol()))
    }

    /**
     * Si el docente no escribió códigos, "tema:2.1" se lee como "el tema 1 de
     * la unidad 2": la parte antes del punto es la unidad.
     */
    @Test
    fun `la etiqueta de tema se resuelve por orden cuando no hay codigos`() {
        val sinCodigos = listOf(
            Unit(id = "u2", courseId = "c1", parentId = null, name = "Modelos lineales", order = 2),
            Unit(id = "u2.1", courseId = "c1", parentId = "u2", name = "Regresión", order = 1),
        )
        val arbol = UnitTree.of(sinCodigos)

        assertEquals(listOf("u2.1"), assignUnits(material("tema:2.1"), arbol))
    }

    /** La primera etiqueta que resuelve es la unidad primaria. */
    @Test
    fun `la primera etiqueta que resuelve es la unidad primaria`() {
        val ids = assignUnits(material("examen", "tema:2.1", "unidad:2"), arbol())

        assertEquals(listOf("u2.1", "u2"), ids)
    }

    /**
     * Una etiqueta que no resuelve no rompe nada: el material queda sin
     * unidades asignadas, que es el estado normal de una cátedra sin taxonomía.
     */
    @Test
    fun `una etiqueta que no resuelve deja el material sin unidades`() {
        assertEquals(emptyList(), assignUnits(material("examen", "unidad:9"), arbol()))
        assertEquals(emptyList(), assignUnits(material("parcial"), arbol()))
    }

    /** Las etiquetas no tienen que estar en minúscula ni sin espacios. */
    @Test
    fun `la etiqueta se lee sin importar el formato`() {
        assertEquals(listOf("u2"), assignUnits(material(" UNIDAD : 2 "), arbol()))
    }

    // ==================================================================
    // E. La cita
    // ==================================================================

    /** La cita compone la jerarquía, el material y la sección. */
    @Test
    fun `la cita compone unidad tema material y seccion`() {
        assertEquals(
            "Unidad 2 > Tema 2.1 > Apuntes-IA > Regresión lineal",
            citationLabel(listOf("Unidad 2", "Tema 2.1"), "Apuntes-IA", "Regresión lineal"),
        )
    }

    /** Sin unidades, la cita queda con el material y la sección. */
    @Test
    fun `la cita sin unidades queda con material y seccion`() {
        assertEquals(
            "Apuntes-IA > Regresión lineal",
            citationLabel(emptyList(), "Apuntes-IA", "Regresión lineal"),
        )
    }

    /** La sección no se repite cuando dice lo mismo que el material. */
    @Test
    fun `la cita no repite la seccion cuando es el material`() {
        assertEquals("Unidad 2 > Apuntes-IA", citationLabel(listOf("Unidad 2"), "Apuntes-IA", "Apuntes-IA"))
    }

    /** Sin sección (documento sin encabezados) la cita queda en la jerarquía. */
    @Test
    fun `la cita sin seccion queda en la jerarquia`() {
        assertEquals(
            "Unidad 2 > Tema 2.1 > Apuntes-IA",
            citationLabel(listOf("Unidad 2", "Tema 2.1"), "Apuntes-IA", ""),
        )
    }
}
