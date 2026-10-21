package kitoru.domain

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests del dominio de tareas: la tarea, la política de IA, la entrega, la
 * rúbrica, el vínculo con el chat y el verificador de fundamentación.
 */
class AssignmentTest {

    // -----------------------------------------------------------------------
    // Utilidades
    // -----------------------------------------------------------------------

    /** Tarea válida de referencia (todo con valores válidos). */
    private fun tarea(
        id: String = "catedra-1|tarea-1",
        courseId: String = "catedra-1",
        title: String = "Análisis de algoritmos",
        statement: String = "Analizá el costo de la búsqueda binaria.",
        dueAt: Long = 2_000L,
        points: Int = 10,
        submissionType: SubmissionType = SubmissionType.TEXT,
        allowAi: Boolean = true,
        chatCanSuggest: Boolean = false,
        assessment: Assessment = Assessment.FORMATIVE,
        published: Boolean = true,
        rubric: Rubric? = null,
        createdAt: Long = 0L,
        updatedAt: Long = 0L,
        aiPolicyOverride: AiPolicy? = null,
    ) = Assignment(
        id = id,
        courseId = courseId,
        title = title,
        statement = statement,
        dueAt = dueAt,
        points = points,
        submissionType = submissionType,
        allowAi = allowAi,
        chatCanSuggest = chatCanSuggest,
        assessment = assessment,
        published = published,
        rubric = rubric,
        createdAt = createdAt,
        updatedAt = updatedAt,
        aiPolicyOverride = aiPolicyOverride,
    )

    /** Material de la cátedra usado por el verificador de fundamentación. */
    private val material = listOf(
        "El curso usa Vulkan para renderizar la escena. La GPU tiene 8 GB de memoria.",
        "El índice de búsqueda se construye con BM25, que pondera los términos por su frecuencia inversa.",
    )

    // -----------------------------------------------------------------------
    // 1. Validación de la tarea
    // -----------------------------------------------------------------------

    @Test
    fun `una tarea completa no tiene errores de validación`() {
        assertEquals(emptyList(), tarea().validate(now = 1_000L))
    }

    @Test
    fun `el enunciado no puede estar vacío`() {
        val errores = tarea(statement = "   ").validate(now = 1_000L)
        assertContains(errores, "el enunciado no puede estar vacío")
    }

    @Test
    fun `la tarea necesita un título`() {
        val errores = tarea(title = "").validate(now = 1_000L)
        assertContains(errores, "la tarea necesita un título")
    }

    @Test
    fun `la fecha de entrega debe estar en el futuro`() {
        val errores = tarea(dueAt = 500L).validate(now = 1_000L)
        assertContains(errores, "la fecha de entrega debe estar en el futuro")
    }

    @Test
    fun `una tarea sin fecha de entrega es válida`() {
        val errores = tarea(dueAt = 0L).validate(now = 1_000L)
        assertEquals(emptyList(), errores)
    }

    @Test
    fun `los puntos no pueden ser negativos`() {
        val errores = tarea(points = -1).validate(now = 1_000L)
        assertContains(errores, "los puntos no pueden ser negativos")
    }

    @Test
    fun `una tarea sumativa vale al menos un punto`() {
        val errores = tarea(points = 0, assessment = Assessment.SUMMATIVE).validate(now = 1_000L)
        assertContains(errores, "una tarea sumativa tiene que valer al menos 1 punto")
    }

    @Test
    fun `una tarea que no admite IA no puede pedir sugerencias`() {
        val errores = tarea(allowAi = false, chatCanSuggest = true).validate(now = 1_000L)
        assertContains(errores, "una tarea que no admite IA no puede pedirle sugerencias a la IA")
    }

    @Test
    fun `la validación devuelve todos los problemas juntos`() {
        val errores = tarea(
            title = "",
            statement = "",
            dueAt = 500L,
            points = -3,
        ).validate(now = 1_000L)
        assertTrue(errores.size >= 4, "Esperaba todos los problemas, obtuve: $errores")
    }

    // -----------------------------------------------------------------------
    // 2. La política de IA
    // -----------------------------------------------------------------------

    @Test
    fun `la política de IA dice qué puede hacer el alumno`() {
        assertFalse(AiPolicy.DISABLED.allowsStudentUse)
        assertTrue(AiPolicy.ASSISTED.allowsStudentUse)
        assertTrue(AiPolicy.ASSISTED.requiresDeclaration)
        assertTrue(AiPolicy.GUIDED.hintsOnly)
        assertFalse(AiPolicy.FREE.requiresDeclaration)
        assertFalse(AiPolicy.FREE.hintsOnly)
        assertTrue(AiPolicy.FREE.allowsStudentUse)
    }

    @Test
    fun `toda política tiene descripción para la interfaz`() {
        for (politica in AiPolicy.entries) {
            assertTrue(politica.description.isNotBlank(), "${politica.name} no tiene descripción.")
            assertTrue(politica.label.isNotBlank(), "${politica.name} no tiene etiqueta.")
        }
    }

    @Test
    fun `las reglas de dos políticas se combinan cerrando permisos`() {
        val asistida = AiRules.of(AiPolicy.ASSISTED)
        val guiada = AiRules.of(AiPolicy.GUIDED)
        val cerradas = asistida.narrow(guiada)

        assertTrue(cerradas.requiresDeclaration, "La declaración se combine con OR.")
        assertTrue(cerradas.hintsOnly, "El límite de pistas se combina con OR.")
        assertTrue(cerradas.allowsStudentUse)
    }

    @Test
    fun `la tarea no puede abrir lo que la cátedra cerró`() {
        val tarea = tarea(allowAi = true, chatCanSuggest = true)
        val reglas = tarea.effectiveRules(AiPolicy.DISABLED)

        assertFalse(reglas.allowsStudentUse, "La cátedra prohibió la IA: la tarea no la habilita.")
        assertEquals(AiPolicy.DISABLED, tarea.effectivePolicy(AiPolicy.DISABLED))
    }

    @Test
    fun `sin permiso para sugerir la IA sólo da pistas`() {
        val tarea = tarea(allowAi = true, chatCanSuggest = false)
        val reglas = tarea.effectiveRules(AiPolicy.FREE)

        assertTrue(reglas.allowsStudentUse)
        assertTrue(reglas.hintsOnly, "Sin permiso de sugerir, la IA sólo guía.")
    }

    @Test
    fun `el override de la tarea sólo cierra`() {
        val tarea = tarea(aiPolicyOverride = AiPolicy.DISABLED)
        val reglas = tarea.effectiveRules(AiPolicy.FREE)

        assertFalse(reglas.allowsStudentUse, "El override de la tarea cierra el uso de IA.")
        assertEquals(AiPolicy.DISABLED, tarea.effectivePolicy(AiPolicy.FREE))
    }

    @Test
    fun `la política de la cátedra se resuelve con su propio tipo`() {
        val politica = CourseAiPolicy(policy = AiPolicy.ASSISTED, requireDeclaration = true)
        assertTrue(politica.rules.requiresDeclaration)

        val tarea = tarea(allowAi = true, chatCanSuggest = true)
        val reglas = politica.rulesFor(tarea)
        assertTrue(reglas.allowsStudentUse)
        assertTrue(reglas.requiresDeclaration)
    }

    // -----------------------------------------------------------------------
    // 3. La entrega
    // -----------------------------------------------------------------------

    @Test
    fun `una entrega en borrador es editable y una entregada no`() {
        val borrador = Submission(status = SubmissionStatus.DRAFT)
        assertTrue(borrador.isDraft)
        assertTrue(borrador.status.isEditable)

        val entregada = borrador.copy(status = SubmissionStatus.SUBMITTED, submittedAt = 1_000L)
        assertFalse(entregada.isDraft)
        assertTrue(entregada.isDelivered)
    }

    @Test
    fun `una entrega con texto o con archivos tiene contenido`() {
        assertFalse(Submission().hasContent)
        assertTrue(Submission(text = "una respuesta").hasContent)
        assertTrue(
            Submission(files = listOf(SubmissionFile(name = "solucion.pdf"))).hasContent,
        )
    }

    @Test
    fun `una entrega fuera de plazo se marca tardía`() {
        val entrega = Submission(status = SubmissionStatus.SUBMITTED, submittedAt = 2_000L)
        assertTrue(entrega.isLate(dueAt = 1_000L))
        assertFalse(entrega.isLate(dueAt = 3_000L))
        assertFalse(entrega.isLate(dueAt = 0L))
    }

    @Test
    fun `los archivos generados por la IA se distinguen de los del alumno`() {
        val entrega = Submission(
            files = listOf(
                SubmissionFile(name = "enunciado.pdf", origin = SubmissionFileOrigin.UPLOAD),
                SubmissionFile(name = "borrador.py", origin = SubmissionFileOrigin.AI_ARTIFACT),
            ),
        )
        assertEquals(1, entrega.aiGeneratedFiles.size)
        assertEquals("borrador.py", entrega.aiGeneratedFiles.first().name)
        assertTrue(entrega.hasAiTrace, "Un archivo de la IA es rastro de uso de IA.")
    }

    @Test
    fun `la declaración de IA distingue el uso del incumplimiento`() {
        assertFalse(AiDeclaration.NONE.usedAi)
        assertTrue(AiDeclaration.USED_AI.usedAi)
        assertTrue(AiDeclaration.USED_AI_WITH_HELP.usedAi)
        assertTrue(AiDeclaration.DENIED_BY_POLICY.isViolation)
    }

    // -----------------------------------------------------------------------
    // 4. La rúbrica
    // -----------------------------------------------------------------------

    @Test
    fun `la rúbrica suma los puntos de sus criterios`() {
        val rubrica = Rubric(
            id = "r-1",
            name = "Análisis de algoritmos",
            levels = listOf(ScaleLevel(label = "Insuficiente", points = 0.0)),
            criteria = listOf(
                RubricCriterion(id = "c-correcto", description = "Correctitud", points = 60),
                RubricCriterion(id = "c-claro", description = "Claridad", points = 40),
            ),
        )
        assertEquals(100, rubrica.maxPoints)
        assertNotNull(rubrica.criterion("c-claro"))
        assertNull(rubrica.criterion("c-inexistente"))
    }

    @Test
    fun `un criterio sin escala propia usa la de la rúbrica`() {
        val comun = listOf(ScaleLevel(label = "Bueno", points = 80.0))
        val rubrica = Rubric(
            id = "r-1",
            levels = comun,
            criteria = listOf(
                RubricCriterion(id = "c-propio", description = "Con escala propia", points = 10),
            ),
        )
        assertEquals(comun, rubrica.levelsFor(rubrica.criteria.first()))
    }

    // -----------------------------------------------------------------------
    // 5. El vínculo con el chat
    // -----------------------------------------------------------------------

    @Test
    fun `el hilo expone la tarea por la etiqueta`() {
        val hilo = ChatThread(
            id = "catedra-1|hilo-1",
            courseId = "catedra-1",
            authorId = "ana",
            tags = listOf("duda de parcial", assignmentTag("catedra-1|tarea-1")),
        )
        assertEquals("catedra-1|tarea-1", hilo.assignmentIdOrNull())
        assertTrue(hilo.isLinkedTo("catedra-1|tarea-1"))
    }

    @Test
    fun `un hilo sin etiqueta de tarea no expone ninguna`() {
        val hilo = ChatThread(
            id = "catedra-1|hilo-2",
            courseId = "catedra-1",
            authorId = "ana",
            tags = listOf("duda de parcial"),
        )
        assertNull(hilo.assignmentIdOrNull())
        assertFalse(hilo.isLinkedTo("catedra-1|tarea-1"))
    }

    // -----------------------------------------------------------------------
    // 6. La corrección
    // -----------------------------------------------------------------------

    @Test
    fun `el feedback expone el puntaje y la propuesta por criterio`() {
        val feedback = AssignmentFeedback(
            assignmentId = "catedra-1|tarea-1",
            submissionId = "catedra-1|entrega-1",
            score = 8.0,
            comments = "Buen análisis, falta el caso borde.",
            rubricScores = listOf(RubricScore(criterionId = "c-correcto", points = 6.0, level = "Bueno")),
            aiProposal = AiProposal(
                suggestedScore = 7.0,
                rubricSuggestions = listOf(
                    RubricSuggestion(criterionId = "c-correcto", level = "Suficiente", confidence = 0.7),
                ),
            ),
        )
        assertEquals(6.0, feedback.scoreFor("c-correcto"))
        assertEquals("Bueno", feedback.levelFor("c-correcto"))
        assertEquals("Suficiente", feedback.proposalFor("c-correcto")?.level)
        assertFalse(feedback.isPublished, "Un feedback en borrador no lo ve el alumno.")
    }

    @Test
    fun `la propuesta de la IA no es la nota final`() {
        val feedback = AssignmentFeedback(
            score = 9.0,
            aiProposal = AiProposal(suggestedScore = 4.0),
        )
        assertEquals(9.0, feedback.score)
        assertEquals(4.0, feedback.aiProposal?.suggestedScore)
    }

    // -----------------------------------------------------------------------
    // 7. El verificador de fundamentación
    // -----------------------------------------------------------------------

    @Test
    fun `una afirmación textual del material está respaldada`() {
        val informe = GroundingChecker.ground(
            "El curso usa Vulkan para renderizar la escena.",
            material,
        )
        assertEquals(1, informe.supported)
        assertEquals(0, informe.unsupported)
        assertEquals(1.0, informe.rate)
    }

    @Test
    fun `una afirmación que inventa un nombre propio no tiene respaldo`() {
        val informe = GroundingChecker.ground(
            "El curso usa DirectX 12 para renderizar la escena.",
            material,
        )
        assertEquals(0, informe.supported)
        assertEquals(1, informe.unsupported)
        assertEquals(0.0, informe.rate)
    }

    @Test
    fun `una afirmación que no está en el material no tiene respaldo`() {
        val informe = GroundingChecker.ground(
            "La cursada dura doce semanas.",
            material,
        )
        assertEquals(0, informe.supported)
        assertEquals(1, informe.unsupported)
    }

    @Test
    fun `una paráfrasis del material está respaldada`() {
        val informe = GroundingChecker.ground(
            "El índice usa BM25 con frecuencia inversa.",
            material,
        )
        assertEquals(1, informe.supported)
        assertEquals(0, informe.unsupported)
    }

    @Test
    fun `sin material las afirmaciones no son evaluables`() {
        val informe = GroundingChecker.ground(
            "El curso usa Vulkan para renderizar la escena.",
            emptyList(),
        )
        assertEquals(0, informe.supported)
        assertEquals(0, informe.unsupported)
        assertEquals(1, informe.unknown)
        assertEquals(1, informe.total)
    }

    @Test
    fun `el informe cuenta las afirmaciones y calcula la proporción`() {
        val informe = GroundingChecker.ground(
            "El curso usa Vulkan para renderizar la escena. " +
                "El curso usa DirectX 12 para renderizar la escena. " +
                "Sí.",
            material,
        )
        assertEquals(1, informe.supported)
        assertEquals(1, informe.unsupported)
        assertEquals(1, informe.unknown)
        assertEquals(3, informe.total)
        assertEquals(1.0 / 3.0, informe.rate, 0.0001)
    }

    @Test
    fun `el verificador de fundamentación es determinista`() {
        val texto = "El índice usa BM25 con frecuencia inversa. La cursada dura doce semanas."
        val primero = GroundingChecker.ground(texto, material)
        val segundo = GroundingChecker.ground(texto, material)
        assertEquals(primero, segundo)
    }

    @Test
    fun `el análisis detallado dice por qué una afirmación no tiene respaldo`() {
        val analisis = GroundingChecker.analyze(
            "El curso usa DirectX 12 para renderizar la escena.",
            material,
        )
        val afirmacion = analisis.claims.single()
        assertEquals(GroundingStatus.UNSUPPORTED, afirmacion.status)
        assertContains(afirmacion.reason, "DirectX")
        assertEquals("El curso usa DirectX 12 para renderizar la escena.", afirmacion.excerpt)
    }
}
