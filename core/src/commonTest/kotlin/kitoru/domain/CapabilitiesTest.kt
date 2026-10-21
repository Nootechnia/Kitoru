package kitoru.domain

import kitoru.platform.FilePath
import kitoru.testing.InMemoryFileSystem
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests del modelo de permisos y capacidades.
 *
 * Cubren cuatro cosas:
 * 1. la matriz rol × capacidad (cobertura e invariantes),
 * 2. las condiciones puras,
 * 3. la visibilidad efectiva (el mínimo de los tres ejes y el fail-closed),
 * 4. el registro de accesos (append-only, tope y persistencia).
 */
class CapabilitiesTest {

    // -----------------------------------------------------------------------
    // Utilidades
    // -----------------------------------------------------------------------

    private fun curso(
        id: String = "catedra-1",
        analytics: Boolean = true,
        allowLocalModels: Boolean = true,
        allowStudentOverride: Boolean = true,
        toolsEnabled: Boolean = true,
    ) = Course(
        id = id,
        code = "IA-2026-1",
        name = "Inteligencia Artificial",
        term = "2026-1",
        analyticsEnabled = analytics,
        allowLocalModels = allowLocalModels,
        modelPolicy = ModelPolicy(
            allowStudentOverride = allowStudentOverride,
            toolsEnabled = toolsEnabled,
        ),
    )

    private fun inscripcion(
        role: CourseRole,
        userId: String = "u-${role.name.lowercase()}",
        displayName: String = "Persona ${role.name.lowercase()}",
        visibility: ChatVisibility = ChatVisibility.FULL,
        courseId: String = "catedra-1",
    ) = Enrollment(
        id = "$courseId-$userId",
        courseId = courseId,
        userId = userId,
        displayName = displayName,
        role = role,
        visibility = visibility,
    )

    private fun contexto(
        actor: Enrollment,
        course: Course = curso(),
        target: PermissionTarget? = null,
        app: PermissionAppSettings = PermissionAppSettings(),
        facts: PermissionFacts = PermissionFacts(published = true),
    ) = PermissionContext(
        course = course,
        actor = actor,
        target = target,
        app = app,
        facts = facts,
    )

    private fun target(
        alumno: Enrollment,
        preference: ChatVisibility? = null,
        ceiling: ChatVisibility = alumno.visibility,
    ) = PermissionTarget.Student(
        owner = alumno,
        enrollmentCeiling = ceiling,
        studentPreference = preference,
    )

    // -----------------------------------------------------------------------
    // 1. La matriz
    // -----------------------------------------------------------------------

    @Test
    fun `la matriz tiene una fila por capacidad y ninguna repetida`() {
        val capacidades = PermissionMatrix.ROWS.map { it.capability }
        assertEquals(capacidades.size, capacidades.toSet().size, "Hay capacidades repetidas en la matriz.")
        assertEquals(Capability.entries.toSet(), capacidades.toSet(), "La matriz no cubre todas las capacidades.")
    }

    @Test
    fun `hay 64 capacidades repartidas en 7 areas`() {
        assertEquals(64, Capability.entries.size)
        assertEquals(7, CapabilityArea.entries.size)
        assertEquals(64, PermissionMatrix.ROWS.size)
    }

    @Test
    fun `toda capacidad condicional define condicion y ningun rol la tiene permitida`() {
        for (fila in PermissionMatrix.ROWS) {
            val veredictos = listOf(fila.titular, fila.ayudante, fila.alumno)
            if (veredictos.contains(Verdict.CONDITIONAL)) {
                assertNotNull(fila.capability.condition, "${fila.capability.name} es condicional sin condición.")
            }
            if (fila.capability.condition != null) {
                assertFalse(
                    veredictos.contains(Verdict.ALLOWED),
                    "${fila.capability.name} tiene condición y no puede estar permitida.",
                )
            }
        }
    }

    @Test
    fun `las capacidades de la app tienen el mismo veredicto en los tres roles`() {
        for (fila in PermissionMatrix.ROWS) {
            if (fila.capability.scope != CapabilityScope.APP) continue
            assertEquals(fila.titular, fila.ayudante, "${fila.capability.name} depende del rol.")
            assertEquals(fila.ayudante, fila.alumno, "${fila.capability.name} depende del rol.")
        }
    }

    @Test
    fun `el markdown de la matriz lista todas las capacidades`() {
        val markdown = PermissionMatrix.toMarkdown()
        for (capability in Capability.entries) {
            assertContains(markdown, "`${capability.name}`")
        }
    }

    @Test
    fun `los veredictos de la matriz son los del diseno`() {
        assertEquals(Verdict.ALLOWED, PermissionMatrix.verdict(CourseRole.TITULAR, Capability.COURSE_EDIT))
        assertEquals(Verdict.DENIED, PermissionMatrix.verdict(CourseRole.AYUDANTE, Capability.COURSE_EDIT))
        assertEquals(Verdict.DENIED, PermissionMatrix.verdict(CourseRole.ALUMNO, Capability.COURSE_EDIT))
        assertEquals(Verdict.CONDITIONAL, PermissionMatrix.verdict(CourseRole.TITULAR, Capability.MATERIAL_UPLOAD))
        assertEquals(Verdict.ALLOWED, PermissionMatrix.verdict(CourseRole.ALUMNO, Capability.CHAT_SEND))
        assertEquals(Verdict.DENIED, PermissionMatrix.verdict(CourseRole.TITULAR, Capability.ENROLLMENT_SELF_LEAVE))
        assertEquals(Verdict.ALLOWED, PermissionMatrix.verdict(CourseRole.ALUMNO, Capability.ENROLLMENT_SELF_LEAVE))
    }

    @Test
    fun `la fachada del rol consulta la matriz`() {
        assertTrue(CourseRole.TITULAR.can(Capability.COURSE_EDIT))
        assertFalse(CourseRole.AYUDANTE.can(Capability.COURSE_EDIT))
        assertTrue(CourseRole.ALUMNO.can(Capability.CHAT_VIEW_OTHER))
    }

    // -----------------------------------------------------------------------
    // 2. Las condiciones
    // -----------------------------------------------------------------------

    @Test
    fun `CoursePublished mira si la catedra esta publicada`() {
        val titular = inscripcion(CourseRole.TITULAR)
        val borrador = contexto(titular, facts = PermissionFacts(published = false))
        val publicada = contexto(titular, facts = PermissionFacts(published = true))
        assertFalse(CoursePublished.evaluate(borrador))
        assertTrue(CoursePublished.evaluate(publicada))
        assertEquals("la cátedra está publicada", CoursePublished.requirement)
    }

    @Test
    fun `CourseHasMaterials mira la cantidad de materiales`() {
        val titular = inscripcion(CourseRole.TITULAR)
        assertFalse(CourseHasMaterials.evaluate(contexto(titular, facts = PermissionFacts())))
        assertTrue(
            CourseHasMaterials.evaluate(contexto(titular, facts = PermissionFacts(materialCount = 3))),
        )
    }

    @Test
    fun `AnalyticsEnabled exige la catedra y la app`() {
        val titular = inscripcion(CourseRole.TITULAR)
        assertTrue(AnalyticsEnabled.evaluate(contexto(titular)))
        assertFalse(
            AnalyticsEnabled.evaluate(
                contexto(titular, course = curso(analytics = false), facts = PermissionFacts(published = true)),
            ),
        )
        assertFalse(
            AnalyticsEnabled.evaluate(
                contexto(
                    titular,
                    app = PermissionAppSettings(analyticsEnabled = false),
                    facts = PermissionFacts(published = true),
                ),
            ),
        )
    }

    @Test
    fun `las condiciones del objetivo dependen de la visibilidad efectiva`() {
        val titular = inscripcion(CourseRole.TITULAR)
        val alumno = inscripcion(CourseRole.ALUMNO, userId = "ana", displayName = "Ana")

        val conTexto = contexto(titular, target = target(alumno, preference = ChatVisibility.FULL))
        assertTrue(TargetAllowsText.evaluate(conTexto))
        assertTrue(TargetAllowsMetrics.evaluate(conTexto))

        val conMetricas = contexto(titular, target = target(alumno, preference = ChatVisibility.METRICS_ONLY))
        assertFalse(TargetAllowsText.evaluate(conMetricas))
        assertTrue(TargetAllowsMetrics.evaluate(conMetricas))

        val oculto = contexto(titular, target = target(alumno, preference = ChatVisibility.NONE))
        assertFalse(TargetAllowsText.evaluate(oculto))
        assertFalse(TargetAllowsMetrics.evaluate(oculto))
    }

    @Test
    fun `TargetIsSelf y TargetIsNotOwner miran la inscripcion`() {
        val titular = inscripcion(CourseRole.TITULAR)
        val alumno = inscripcion(CourseRole.ALUMNO, userId = "ana")
        val ayudante = inscripcion(CourseRole.AYUDANTE, userId = "beto")

        assertTrue(TargetIsSelf.evaluate(contexto(alumno, target = target(alumno))))
        assertFalse(TargetIsSelf.evaluate(contexto(titular, target = target(alumno))))

        assertTrue(TargetIsNotOwner.evaluate(contexto(titular, target = target(alumno))))
        assertFalse(TargetIsNotOwner.evaluate(contexto(titular, target = target(titular))))
        assertTrue(TargetIsNotOwner.evaluate(contexto(titular, target = target(ayudante))))
    }

    @Test
    fun `las condiciones del modelo y de las herramientas leen el curso`() {
        val titular = inscripcion(CourseRole.TITULAR)

        assertTrue(
            StudentOverrideAllowed.evaluate(
                contexto(titular, course = curso(allowStudentOverride = true)),
            ),
        )
        assertFalse(
            StudentOverrideAllowed.evaluate(
                contexto(titular, course = curso(allowStudentOverride = false)),
            ),
        )

        assertTrue(
            LocalModelsAllowed.evaluate(contexto(titular, course = curso(allowLocalModels = true))),
        )
        assertFalse(
            LocalModelsAllowed.evaluate(contexto(titular, course = curso(allowLocalModels = false))),
        )

        assertTrue(
            ToolsEnabledInCourse.evaluate(contexto(titular, course = curso(toolsEnabled = true))),
        )
        assertFalse(
            ToolsEnabledInCourse.evaluate(contexto(titular, course = curso(toolsEnabled = false))),
        )
    }

    @Test
    fun `ToolCategoryAllowed exige que el docente habilite la categoria`() {
        val titular = inscripcion(CourseRole.TITULAR)
        val conWeb = contexto(
            titular,
            facts = PermissionFacts(published = true, toolCategories = setOf(PermissionToolCategory.WEB)),
        )
        assertTrue(ToolCategoryAllowed(PermissionToolCategory.WEB).evaluate(conWeb))
        assertFalse(ToolCategoryAllowed(PermissionToolCategory.FILES).evaluate(conWeb))
        assertFalse(
            ToolCategoryAllowed(PermissionToolCategory.FILES).evaluate(
                contexto(titular, facts = PermissionFacts(published = true)),
            ),
        )
    }

    @Test
    fun `las condiciones de la app leen los ajustes globales`() {
        val titular = inscripcion(CourseRole.TITULAR)

        assertFalse(McpAllowedByApp.evaluate(contexto(titular)))
        assertTrue(
            McpAllowedByApp.evaluate(
                contexto(titular, app = PermissionAppSettings(mcpEnabled = true)),
            ),
        )

        assertTrue(AppNotManaged.evaluate(contexto(titular)))
        assertFalse(
            AppNotManaged.evaluate(contexto(titular, app = PermissionAppSettings(managed = true))),
        )

        assertTrue(AuditLogEnabled.evaluate(contexto(titular)))
        assertFalse(
            AuditLogEnabled.evaluate(
                contexto(titular, app = PermissionAppSettings(auditLogEnabled = false)),
            ),
        )
    }

    @Test
    fun `los combinadores juntan condiciones`() {
        val titular = inscripcion(CourseRole.TITULAR)
        val todas = allOf(CoursePublished, CourseHasMaterials)
        val alguna = anyOf(CoursePublished, CourseHasMaterials)

        assertFalse(
            todas.evaluate(contexto(titular, facts = PermissionFacts(published = true))),
        )
        assertTrue(
            todas.evaluate(
                contexto(titular, facts = PermissionFacts(published = true, materialCount = 2)),
            ),
        )
        assertTrue(
            alguna.evaluate(contexto(titular, facts = PermissionFacts(published = true))),
        )
        assertFalse(
            Not(CoursePublished).evaluate(contexto(titular, facts = PermissionFacts(published = true))),
        )
        assertTrue(
            Not(CoursePublished).evaluate(contexto(titular, facts = PermissionFacts(published = false))),
        )
        assertEquals(2, todas.flatten().size)
        assertEquals(
            "la cátedra está publicada y la cátedra tiene materiales cargados",
            todas.requirement,
        )
        assertEquals("la cátedra está publicada o la cátedra tiene materiales cargados", alguna.requirement)
        assertEquals("no la cátedra está publicada", Not(CoursePublished).requirement)
    }

    // -----------------------------------------------------------------------
    // 3. El resolvedor
    // -----------------------------------------------------------------------

    @Test
    fun `una capacidad permitida por rol se concede`() {
        val titular = inscripcion(CourseRole.TITULAR)
        val decision = PermissionResolver.resolve(contexto(titular), Capability.COURSE_EDIT)
        assertTrue(decision is PermissionDecision.Granted)
        assertTrue(decision.allows)
        assertNull(decision.reason)
    }

    @Test
    fun `una capacidad prohibida por rol se deniega con motivo en espanol`() {
        val ayudante = inscripcion(CourseRole.AYUDANTE)
        val decision = PermissionResolver.resolve(contexto(ayudante), Capability.COURSE_EDIT)
        assertTrue(decision is PermissionDecision.Denied)
        assertFalse(decision.allows)
        val motivo = assertNotNull(decision.reason)
        assertContains(motivo, "Ayudante")
        assertContains(motivo, "Editar los datos de la cátedra")
    }

    @Test
    fun `una capacidad condicional devuelve sus condiciones`() {
        val ayudante = inscripcion(CourseRole.AYUDANTE)
        val decision = PermissionResolver.resolve(
            contexto(ayudante, facts = PermissionFacts(published = false)),
            Capability.MATERIAL_UPLOAD,
        )
        assertTrue(decision is PermissionDecision.Conditional)
        val condicional = decision as PermissionDecision.Conditional
        assertFalse(condicional.satisfied)
        assertEquals(1, condicional.conditions.size)
        assertContains(assertNotNull(condicional.reason), "Requiere: la cátedra está publicada.")
    }

    @Test
    fun `una capacidad condicional cumplida se concede`() {
        val ayudante = inscripcion(CourseRole.AYUDANTE)
        val decision = PermissionResolver.resolve(
            contexto(ayudante, facts = PermissionFacts(published = true)),
            Capability.MATERIAL_UPLOAD,
        )
        assertTrue(decision.allows)
        assertNull(decision.reason)
    }

    @Test
    fun `el alumno no puede leer el texto de otro alumno sin autorizacion`() {
        val titular = inscripcion(CourseRole.TITULAR)
        val alumno = inscripcion(CourseRole.ALUMNO, userId = "ana")

        val conTexto = contexto(titular, target = target(alumno, preference = ChatVisibility.FULL))
        assertTrue(
            PermissionResolver.resolve(conTexto, Capability.CHAT_VIEW_OTHER).allows,
        )

        val conMetricas = contexto(titular, target = target(alumno, preference = ChatVisibility.METRICS_ONLY))
        assertFalse(
            PermissionResolver.resolve(conMetricas, Capability.CHAT_VIEW_OTHER).allows,
        )
        assertTrue(
            PermissionResolver.resolve(conMetricas, Capability.CHAT_VIEW_OTHER_METRICS).allows,
        )
    }

    @Test
    fun `la analitica por alumno exige analitica habilitada y consentimiento`() {
        val titular = inscripcion(CourseRole.TITULAR)
        val alumno = inscripcion(CourseRole.ALUMNO, userId = "ana")
        val targetMetricas = target(alumno, preference = ChatVisibility.METRICS_ONLY)

        val habilitado = contexto(titular, target = targetMetricas)
        assertTrue(
            PermissionResolver.resolve(habilitado, Capability.ANALYTICS_VIEW_PER_STUDENT).allows,
        )
        assertFalse(
            PermissionResolver.resolve(habilitado, Capability.ANALYTICS_VIEW_ANSWER_TEXT).allows,
        )

        val sinAnalitica = contexto(
            titular,
            course = curso(analytics = false),
            target = targetMetricas,
        )
        assertFalse(
            PermissionResolver.resolve(sinAnalitica, Capability.ANALYTICS_VIEW_PER_STUDENT).allows,
        )
        assertFalse(
            PermissionResolver.resolve(sinAnalitica, Capability.ANALYTICS_VIEW_COURSE).allows,
        )
    }

    @Test
    fun `las capacidades de la app no dependen del rol`() {
        for (rol in CourseRole.entries) {
            val decision = PermissionResolver.resolve(
                contexto(inscripcion(rol), app = PermissionAppSettings(mcpEnabled = true)),
                Capability.APP_MCP_MANAGE,
            )
            assertTrue(decision.allows, "APP_MCP_MANAGE debería depender sólo de la app.")
        }
    }

    @Test
    fun `resolveAll reparte todas las capacidades sin ambiguedad`() {
        val titular = inscripcion(CourseRole.TITULAR)
        val alumno = inscripcion(CourseRole.ALUMNO, userId = "ana")
        val permisos = PermissionResolver.resolveAll(
            contexto(titular, target = target(alumno, preference = ChatVisibility.METRICS_ONLY)),
        )
        assertEquals(Capability.entries.size, permisos.granted.size + permisos.denied.size)
        assertTrue(permisos.granted.intersect(permisos.denied.keys).isEmpty())
        assertTrue(permisos.allows(Capability.CHAT_VIEW_OTHER_METRICS))
        assertFalse(permisos.allows(Capability.CHAT_VIEW_OTHER))
        assertContains(assertNotNull(permisos.reason(Capability.CHAT_VIEW_OTHER)), "Requiere:")
        assertTrue(permisos.isConditional(Capability.CHAT_VIEW_OTHER))
        assertTrue(permisos.grantedOf(CapabilityArea.CHATS).isNotEmpty())
        assertFailsWithMessage {
            permisos.require(Capability.CHAT_VIEW_OTHER)
        }
    }

    // -----------------------------------------------------------------------
    // 4. La visibilidad efectiva
    // -----------------------------------------------------------------------

    @Test
    fun `el nivel de visibilidad ordena las tres variantes`() {
        assertEquals(0, ChatVisibility.NONE.level())
        assertEquals(1, ChatVisibility.METRICS_ONLY.level())
        assertEquals(2, ChatVisibility.FULL.level())
    }

    @Test
    fun `la visibilidad efectiva es el minimo de los tres ejes`() {
        // Los tres en FULL: FULL.
        assertEquals(
            ChatVisibility.FULL,
            effectiveVisibility(ChatVisibility.FULL, ChatVisibility.FULL, ChatVisibility.FULL),
        )
        // El tope de la inscripción manda: METRICS_ONLY.
        assertEquals(
            ChatVisibility.METRICS_ONLY,
            effectiveVisibility(ChatVisibility.FULL, ChatVisibility.METRICS_ONLY, ChatVisibility.FULL),
        )
        // La preferencia del alumno manda: NONE.
        assertEquals(
            ChatVisibility.NONE,
            effectiveVisibility(ChatVisibility.FULL, ChatVisibility.FULL, ChatVisibility.NONE),
        )
        // El tope del docente manda: NONE.
        assertEquals(
            ChatVisibility.NONE,
            effectiveVisibility(ChatVisibility.NONE, ChatVisibility.FULL, ChatVisibility.FULL),
        )
        // Gana el menor de los tres.
        assertEquals(
            ChatVisibility.METRICS_ONLY,
            effectiveVisibility(ChatVisibility.FULL, ChatVisibility.METRICS_ONLY, ChatVisibility.METRICS_ONLY),
        )
    }

    @Test
    fun `sin consentimiento explicito la visibilidad nunca es completa`() {
        val visible = effectiveVisibility(ChatVisibility.FULL, ChatVisibility.FULL, null)
        assertEquals(ChatVisibility.METRICS_ONLY, visible)
        assertFalse(visible.showsText)
        assertTrue(visible.showsMetrics)

        // Si el tope o el rol ya lo limitan, sigue mandando el mínimo.
        assertEquals(
            ChatVisibility.NONE,
            effectiveVisibility(ChatVisibility.NONE, ChatVisibility.FULL, null),
        )
        assertEquals(
            ChatVisibility.NONE,
            effectiveVisibility(ChatVisibility.FULL, ChatVisibility.NONE, null),
        )
    }

    @Test
    fun `el contexto calcula la visibilidad efectiva del objetivo`() {
        val titular = inscripcion(CourseRole.TITULAR)
        val alumno = inscripcion(CourseRole.ALUMNO, userId = "ana")

        val conTexto = contexto(titular, target = target(alumno, preference = ChatVisibility.FULL))
        assertEquals(ChatVisibility.FULL, conTexto.effectiveVisibility())

        val sinPreferencia = contexto(titular, target = target(alumno))
        assertEquals(ChatVisibility.METRICS_ONLY, sinPreferencia.effectiveVisibility())

        val sinObjetivo = contexto(titular)
        assertEquals(ChatVisibility.NONE, sinObjetivo.effectiveVisibility())
    }

    @Test
    fun `el tope del docente sale de la matriz`() {
        assertEquals(ChatVisibility.FULL, VisibilityPolicy.teacherCap(CourseRole.TITULAR))
        assertEquals(ChatVisibility.FULL, VisibilityPolicy.teacherCap(CourseRole.AYUDANTE))
        assertEquals(ChatVisibility.FULL, VisibilityPolicy.teacherCap(CourseRole.ALUMNO))
    }

    // -----------------------------------------------------------------------
    // 5. El registro de accesos
    // -----------------------------------------------------------------------

    private fun logDePrueba(
        fs: InMemoryFileSystem,
        maxEntries: Int = AccessLog.MAX_ENTRIES,
    ): AccessLog {
        var contador = 0
        return AccessLog.on(
            fs = fs,
            path = FilePath("/config/access-log.json"),
            maxEntries = maxEntries,
            clock = { 1_000L },
            idFactory = { "acc-${contador++}" },
        )
    }

    @Test
    fun `solo se registran las capacidades auditables`() = runTest {
        val fs = InMemoryFileSystem()
        val log = logDePrueba(fs)
        val titular = inscripcion(CourseRole.TITULAR)
        val alumno = inscripcion(CourseRole.ALUMNO, userId = "ana")
        val ctx = contexto(titular, target = target(alumno, preference = ChatVisibility.FULL))

        assertNull(log.record(ctx, Capability.CHAT_VIEW_OWN))
        assertNull(log.record(ctx, Capability.COURSE_EDIT))
        assertEquals(0, log.size())

        val entrada = log.record(ctx, Capability.CHAT_VIEW_OTHER, detail = "hilo 1")
        assertNotNull(entrada)
        assertEquals(1, log.size())
        assertEquals(Capability.CHAT_VIEW_OTHER, entrada.capability)
        assertEquals(AccessOutcome.ALLOWED, entrada.outcome)
        assertEquals("hilo 1", entrada.detail)
        assertEquals("ana", entrada.targetUserId)
        assertEquals(CourseRole.TITULAR, entrada.basis.role)
        assertEquals(ChatVisibility.FULL, entrada.basis.consent)
    }

    @Test
    fun `los accesos denegados tambien quedan registrados`() = runTest {
        val fs = InMemoryFileSystem()
        val log = logDePrueba(fs)
        val titular = inscripcion(CourseRole.TITULAR)
        val alumno = inscripcion(CourseRole.ALUMNO, userId = "ana")
        val ctx = contexto(titular, target = target(alumno, preference = ChatVisibility.METRICS_ONLY))

        val entrada = log.record(
            ctx,
            Capability.CHAT_VIEW_OTHER,
            outcome = AccessOutcome.DENIED,
            requirement = "el alumno autorizó ver el texto de sus chats",
        )
        assertNotNull(entrada)
        assertEquals(AccessOutcome.DENIED, entrada.outcome)
        assertEquals("el alumno autorizó ver el texto de sus chats", entrada.basis.requirement)
        assertEquals(ChatVisibility.METRICS_ONLY, entrada.basis.consent)
    }

    @Test
    fun `el log persiste en JSON y se puede volver a leer`() = runTest {
        val fs = InMemoryFileSystem()
        val log = logDePrueba(fs)
        val titular = inscripcion(CourseRole.TITULAR)
        val alumno = inscripcion(CourseRole.ALUMNO, userId = "ana")
        val ctx = contexto(titular, target = target(alumno, preference = ChatVisibility.FULL))

        log.record(ctx, Capability.CHAT_VIEW_OTHER, detail = "hilo 42")

        val json = assertNotNull(fs.peek("/config/access-log.json"))
        assertContains(json, "CHAT_VIEW_OTHER")
        assertContains(json, "hilo 42")

        // Se relee del archivo con otro log sobre el mismo sistema de archivos.
        val otro = logDePrueba(fs)
        val entradas = otro.list()
        assertEquals(1, entradas.size)
        assertEquals(Capability.CHAT_VIEW_OTHER, entradas.first().capability)
        assertEquals("hilo 42", entradas.first().detail)
    }

    @Test
    fun `el log es append-only y descarta las entradas mas viejas`() = runTest {
        val fs = InMemoryFileSystem()
        val log = logDePrueba(fs, maxEntries = 3)
        val titular = inscripcion(CourseRole.TITULAR)
        val ctx = contexto(titular)

        repeat(5) { i ->
            log.append(
                AccessLogEntry(
                    id = "entrada-$i",
                    at = i.toLong(),
                    actorId = titular.userId,
                    actorName = titular.displayName,
                    courseId = ctx.course.id,
                    capability = Capability.CHAT_VIEW_OWN,
                    detail = "número $i",
                ),
            )
        }

        val entradas = log.list()
        assertEquals(3, entradas.size)
        assertEquals(listOf("entrada-4", "entrada-3", "entrada-2"), entradas.map { it.id })
    }

    @Test
    fun `el log filtra por catedra`() = runTest {
        val fs = InMemoryFileSystem()
        val log = logDePrueba(fs)
        val titular = inscripcion(CourseRole.TITULAR)

        log.record(contexto(titular, curso(id = "catedra-1")), Capability.CHAT_VIEW_OTHER)
        log.record(
            contexto(titular, curso(id = "catedra-2")),
            Capability.CHAT_VIEW_OTHER,
        )

        assertEquals(2, log.list().size)
        assertEquals(1, log.list(courseId = "catedra-2").size)
        assertEquals("catedra-2", log.list(courseId = "catedra-2").first().courseId)
    }

    @Test
    fun `una entrada sobrevive el viaje por JSON`() = runTest {
        val fs = InMemoryFileSystem()
        val log = logDePrueba(fs)
        val titular = inscripcion(CourseRole.TITULAR)
        val alumno = inscripcion(CourseRole.ALUMNO, userId = "ana")
        val ctx = contexto(titular, target = target(alumno, preference = ChatVisibility.FULL))

        log.record(ctx, Capability.CHAT_VIEW_OTHER, detail = "hilo 42")
        val json = assertNotNull(fs.peek("/config/access-log.json"))
        val entradas = KitoruJson.decodeFromString(
            ListSerializer(AccessLogEntry.serializer()),
            json,
        )
        assertEquals(1, entradas.size)
        val releida = entradas.single()
        assertEquals(Capability.CHAT_VIEW_OTHER, releida.capability)
        assertEquals(AccessOutcome.ALLOWED, releida.outcome)
        assertEquals(CourseRole.TITULAR, releida.basis.role)
        assertEquals(ChatVisibility.FULL, releida.basis.consent)
        assertEquals("hilo 42", releida.detail)
    }

    /** Atajo para comprobar que [require] falla con mensaje en español. */
    private fun assertFailsWithMessage(block: () -> kotlin.Unit) {
        val error = kotlin.test.assertFailsWith<IllegalStateException> { block() }
        assertContains(error.message.orEmpty(), "Requiere")
    }
}
