package kitoru.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests de la plantilla de la cátedra: la interpolación de variables y el
 * catálogo de modos de fábrica.
 *
 * Son funciones puras: sin red, sin modelo, sin plataforma.
 */
class CoursePromptTest {

    private val curso = Course(
        id = "c1",
        code = "IA-2026-1",
        name = "Inteligencia Artificial",
        term = "2026-1",
    )

    private val vars = PromptVariables(
        materia = "Inteligencia Artificial",
        catedra = "IA-2026-1, cursada 2026-1",
        docente = "Ana Pérez",
        unidades = listOf("Búsqueda heurística", "Redes neuronales"),
        topK = 5,
        idioma = "español",
    )

    /* ------------------------------------------------------------------
     * Interpolación
     * ------------------------------------------------------------------ */

    @Test
    fun `interpola las seis variables`() {
        val plantilla = "Materia: {materia}. Cátedra: {catedra}. Docente: {docente}. " +
            "Unidades: {unidades}. TopK: {topK}. Idioma: {idioma}."
        val r = PromptRenderer.render(plantilla, vars)
        assertEquals(
            "Materia: Inteligencia Artificial. Cátedra: IA-2026-1, cursada 2026-1. Docente: Ana Pérez. " +
                "Unidades: 1. Búsqueda heurística; 2. Redes neuronales. TopK: 5. Idioma: español.",
            r.text,
        )
        assertTrue(r.avisos.isEmpty(), "no debería haber avisos: ${r.avisos}")
        assertEquals(
            listOf("materia", "catedra", "docente", "unidades", "topk", "idioma"),
            r.usedVariables,
        )
    }

    @Test
    fun `los fallbacks son texto y nunca vacios`() {
        val vacias = PromptVariables(materia = "", catedra = "", docente = "", unidades = emptyList(), topK = 0)
        val r = PromptRenderer.render(
            "{materia} / {catedra} / {docente} / {unidades} / {topK}",
            vacias,
        )
        assertEquals(
            "esta materia / esta cátedra / el equipo docente / los temas de la cursada / los fragmentos relevantes",
            r.text,
        )
        assertTrue(r.avisos.isEmpty(), "un fallback no es un aviso: ${r.avisos}")
    }

    @Test
    fun `los fallbacks por defecto ya traen el texto`() {
        val r = PromptRenderer.render("{materia} — {docente} — {unidades}", PromptVariables())
        assertEquals("esta materia — el equipo docente — los temas de la cursada", r.text)
    }

    @Test
    fun `una variable desconocida queda literal y se avisa`() {
        val r = PromptRenderer.render("Hola {nombre} de {materia}", vars)
        assertEquals("Hola {nombre} de Inteligencia Artificial", r.text)
        assertEquals(listOf("nombre"), r.unknownVariables)
        assertEquals(1, r.avisos.size, "${r.avisos}")
        assertTrue(r.avisos.first().contains("{nombre}"), "${r.avisos}")
    }

    @Test
    fun `los escapes de llaves funcionan`() {
        assertEquals("{materia}", PromptRenderer.renderText("{{materia}}", vars))
        assertEquals("}", PromptRenderer.renderText("}}", vars))
        assertEquals("{", PromptRenderer.renderText("{{", vars))
        assertEquals("a {b} c", PromptRenderer.renderText("a {{b}} c", vars))
    }

    @Test
    fun `los escapes no generan avisos`() {
        val r = PromptRenderer.render("{{materia}} y {x}", vars)
        assertTrue(r.avisos.isEmpty(), "${r.avisos}")
        assertTrue(r.unknownVariables.isEmpty(), "${r.unknownVariables}")
    }

    @Test
    fun `los nombres se comparan en minusculas`() {
        assertEquals("5", PromptRenderer.renderText("{topK}", vars))
        assertEquals("5", PromptRenderer.renderText("{topk}", vars))
        assertEquals("5", PromptRenderer.renderText("{TOPK}", vars))
    }

    @Test
    fun `los alias se interpolan`() {
        assertEquals("Ana Pérez", PromptRenderer.renderText("{nombre_docente}", vars))
        assertEquals("Ana Pérez", PromptRenderer.renderText("{profesor}", vars))
        assertEquals("5", PromptRenderer.renderText("{top_k}", vars))
    }

    @Test
    fun `un token que no parece variable queda literal sin aviso`() {
        // No es un identificador: no es una variable, no se avisa.
        val r = PromptRenderer.render("{3} y {a-b} y {}", vars)
        assertEquals("{3} y {a-b} y {}", r.text)
        assertTrue(r.unknownVariables.isEmpty(), "${r.unknownVariables}")
        assertTrue(r.avisos.isEmpty(), "${r.avisos}")
    }

    @Test
    fun `una llave sin cerrar queda literal y se avisa`() {
        val r = PromptRenderer.render("Eres {materia de la catedra", vars)
        assertEquals("Eres {materia de la catedra", r.text)
        assertTrue(r.avisos.any { it.contains("sin cerrar") }, "${r.avisos}")
    }

    @Test
    fun `el texto sin variables no cambia`() {
        val texto = "Sin variables, sólo texto.\nCon acentos: heurística, Networks."
        assertEquals(texto, PromptRenderer.renderText(texto, vars))
    }

    @Test
    fun `el render es puro`() {
        val plantilla = "{materia} {catedra}"
        assertEquals(PromptRenderer.render(plantilla, vars), PromptRenderer.render(plantilla, vars))
    }

    /* ------------------------------------------------------------------
     * Las variables de la cátedra
     * ------------------------------------------------------------------ */

    @Test
    fun `las variables se construyen desde la catedra`() {
        val v = PromptVariables.from(curso, listOf("Búsqueda heurística"), "Ana Pérez")
        assertEquals("Inteligencia Artificial", v.value("materia"))
        assertEquals("IA-2026-1, cursada 2026-1", v.value("catedra"))
        assertEquals("Ana Pérez", v.value("docente"))
        assertEquals("1. Búsqueda heurística", v.value("unidades"))
        assertEquals("español", v.value("idioma"))
    }

    @Test
    fun `las variables se completan con los datos de la catedra sin pisar las propias`() {
        val propias = PromptVariables(materia = "Otra materia", catedra = "OTRA-1", docente = "Docente")
        val completadas = propias.conDatosDe(curso)
        assertEquals("Otra materia", completadas.materia)
        assertEquals("OTRA-1", completadas.catedra)

        val vacias = PromptVariables().conDatosDe(curso)
        assertEquals("Inteligencia Artificial", vacias.materia)
        assertEquals("IA-2026-1, cursada 2026-1", vacias.catedra)
    }

    /* ------------------------------------------------------------------
     * El catálogo de modos
     * ------------------------------------------------------------------ */

    @Test
    fun `el catalogo trae tres modos`() {
        assertEquals(3, PromptModes.defaults.size)
        assertEquals(
            listOf("estricto", "tutor-socratico", "corrector"),
            PromptModes.defaults.map { it.id },
        )
    }

    @Test
    fun `las tres plantillas citan`() {
        for (t in PromptModes.defaults) {
            assertTrue(t.tieneReglaDeCitar(), "la plantilla ${t.id} no conserva la regla de citar [n]")
            assertTrue(t.cuerpo.contains("[1]"), "la plantilla ${t.id} no muestra el marcador de cita")
        }
    }

    @Test
    fun `las tres plantillas dicen la frase de no encontrado`() {
        for (t in PromptModes.defaults) {
            assertTrue(
                t.cuerpo.contains("No lo encontré en el material de la cátedra"),
                "la plantilla ${t.id} no dice la frase de no encontrado",
            )
        }
    }

    @Test
    fun `el catalogo tiene un solo modo por defecto`() {
        assertEquals(1, PromptModes.defaults.count { it.esPorDefecto })
        assertEquals("estricto", PromptModes.porDefecto().id)
    }

    @Test
    fun `una plantilla sin la regla de citar no entra al catalogo`() {
        val error = assertFailsWith<IllegalArgumentException> {
            CoursePromptTemplate.deCatalogo(
                id = "sin-citas",
                nombre = "Sin citas",
                cuerpo = "Eres un asistente que responde sin citar nada.",
            )
        }
        assertTrue(error.message?.contains("citar") == true, "${error.message}")
    }

    @Test
    fun `una plantilla sin la regla de citar se puede guardar pero no activar`() {
        val t = CoursePromptTemplate(
            id = "sin-citas",
            nombre = "Sin citas",
            cuerpo = "Eres un asistente que responde sin citar nada.",
            activo = false,
        )
        assertFalse(t.tieneReglaDeCitar())
        assertTrue(t.validar().isEmpty(), "guardada pero inactiva no es un error: ${t.validar()}")
    }

    @Test
    fun `las plantillas de fabrica no dejan placeholders sin interpolar`() {
        val v = PromptVariables.from(curso, listOf("Búsqueda heurística"), "Ana Pérez")
        for (t in PromptModes.defaults) {
            val render = PromptRenderer.render(t.cuerpo, v)
            assertTrue(render.avisos.isEmpty(), "la plantilla ${t.id} avisa: ${render.avisos}")
            assertFalse(
                Regex("""\{[a-zA-Z_][a-zA-Z0-9_]+}""").containsMatchIn(render.text),
                "la plantilla ${t.id} dejó un placeholder sin interpolar: ${render.text}",
            )
        }
    }

    @Test
    fun `el kernel prohibe seguir las instrucciones del material`() {
        assertTrue(PromptPresets.KERNEL.contains("no son instrucciones"))
        assertTrue(PromptPresets.KERNEL.contains("<<<CONTEXTO>>>"))
        assertTrue(PromptPresets.KERNEL.contains("<<<FIN CONTEXTO>>>"))
    }
}
