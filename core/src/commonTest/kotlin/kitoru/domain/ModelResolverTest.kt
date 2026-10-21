package kitoru.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests del modelo de deltas: cómo se resuelve la configuración de un alumno
 * contra la política de su cátedra y qué diff ve el docente.
 */
class ModelResolverTest {

    // ------------------------------------------------------------------
    // La tabla completa: bloqueado × delta × allowStudentOverride
    // ------------------------------------------------------------------

    @Test
    fun `la tabla de bloqueado, delta y override decide el estado del campo`() {
        data class Caso(
            val bloqueado: Boolean,
            val tieneDelta: Boolean,
            val permiteOverride: Boolean,
            val estado: ChangeStatus,
            val efectivo: Double,
        )

        val casos = listOf(
            // Libre y con override: el delta del alumno se aplica.
            Caso(bloqueado = false, tieneDelta = true, permiteOverride = true, estado = ChangeStatus.ACCEPTED, efectivo = 0.7),
            // Libre, sin delta: se hereda el valor de la cátedra.
            Caso(bloqueado = false, tieneDelta = false, permiteOverride = true, estado = ChangeStatus.INHERITED, efectivo = 0.2),
            // Sin override el delta no se aplica…
            Caso(bloqueado = false, tieneDelta = true, permiteOverride = false, estado = ChangeStatus.REJECTED, efectivo = 0.2),
            Caso(bloqueado = false, tieneDelta = false, permiteOverride = false, estado = ChangeStatus.INHERITED, efectivo = 0.2),
            // …y un campo bloqueado siempre gana la cátedra.
            Caso(bloqueado = true, tieneDelta = true, permiteOverride = true, estado = ChangeStatus.REJECTED, efectivo = 0.2),
            Caso(bloqueado = true, tieneDelta = false, permiteOverride = true, estado = ChangeStatus.LOCKED, efectivo = 0.2),
            Caso(bloqueado = true, tieneDelta = true, permiteOverride = false, estado = ChangeStatus.REJECTED, efectivo = 0.2),
            Caso(bloqueado = true, tieneDelta = false, permiteOverride = false, estado = ChangeStatus.LOCKED, efectivo = 0.2),
        )

        for (caso in casos) {
            val politica = ModelPolicy(
                temperature = 0.2,
                allowStudentOverride = caso.permiteOverride,
                lockedFields = if (caso.bloqueado) setOf(LockedField.TEMPERATURE) else emptySet(),
            )
            val alumno = StudentModelProfile(
                userId = "u1",
                courseId = "c1",
                deltas = if (caso.tieneDelta) mapOf(PolicyField.TEMPERATURE to "0.7") else emptyMap(),
            )

            val resolucion = ModelResolver.resolve(politica, alumno)
            val cambio = assertNotNull(
                resolucion.changeFor(PolicyField.TEMPERATURE),
                "El cambio de temperatura tiene que estar en el diff (caso: $caso).",
            )

            assertEquals(
                caso.estado,
                cambio.estado,
                "Estado esperado ${caso.estado} para el caso $caso.",
            )
            assertEquals(
                caso.efectivo,
                resolucion.effective.temperature,
                0.0001,
                "Temperatura efectiva para el caso $caso.",
            )
            assertEquals(
                caso.estado == ChangeStatus.REJECTED,
                resolucion.rejected.contains(cambio),
                "La lista de rechazados tiene que coincidir con el estado (caso: $caso).",
            )
        }
    }

    @Test
    fun `un campo bloqueado se rechaza con motivo y gana la catedra`() {
        val politica = ModelPolicy(
            temperature = 0.2,
            lockedFields = setOf(LockedField.TEMPERATURE),
        )
        val alumno = StudentModelProfile(
            userId = "u1",
            courseId = "c1",
            deltas = mapOf(PolicyField.TEMPERATURE to "1.5"),
        )

        val resolucion = ModelResolver.resolve(politica, alumno)
        val cambio = assertNotNull(resolucion.changeFor(PolicyField.TEMPERATURE))

        assertEquals(ChangeStatus.REJECTED, cambio.estado)
        assertEquals(0.2, resolucion.effective.temperature, 0.0001)
        assertEquals("1.5", cambio.valorPedido)
        assertEquals("0.2", cambio.valorEfectivo)
        assertTrue(cambio.motivo.isNotBlank(), "Un rechazo tiene que explicar el motivo.")
        assertEquals(ChangeOrigin.STUDENT, cambio.origen)
    }

    // ------------------------------------------------------------------
    // El modo local como válvula de escape
    // ------------------------------------------------------------------

    @Test
    fun `el modo local se acepta aunque la catedra no permita ajustar`() {
        val politica = ModelPolicy(
            mode = ModelMode.REMOTE,
            apiKey = "kitoru-institucional",
            allowStudentOverride = false,
        )
        val alumno = StudentModelProfile(
            userId = "u1",
            courseId = "c1",
            deltas = mapOf(PolicyField.MODE to "LOCAL"),
            localModel = LocalModelProfile(hfFile = "Phi-4-mini-instruct-Q4_K_M.gguf"),
        )

        val resolucion = ModelResolver.resolve(politica, alumno)
        val cambio = assertNotNull(resolucion.changeFor(PolicyField.MODE))

        assertEquals(ModelMode.LOCAL, resolucion.effective.mode, "El modo local es la válvula de escape.")
        assertEquals(ChangeStatus.ACCEPTED, cambio.estado)
        assertTrue(cambio.motivo.isNotBlank(), "Hay que explicar por qué se aceptó el modo local.")
    }

    @Test
    fun `sin override solo se aplica el modo local y ningun otro delta`() {
        val politica = ModelPolicy(
            mode = ModelMode.REMOTE,
            temperature = 0.2,
            maxTokens = 1024,
            allowStudentOverride = false,
        )
        val alumno = StudentModelProfile(
            userId = "u1",
            courseId = "c1",
            deltas = mapOf(
                PolicyField.MODE to "LOCAL",
                PolicyField.TEMPERATURE to "0.9",
                PolicyField.MAX_TOKENS to "4096",
            ),
        )

        val resolucion = ModelResolver.resolve(politica, alumno)

        assertEquals(ModelMode.LOCAL, resolucion.effective.mode)
        assertEquals(0.2, resolucion.effective.temperature, 0.0001, "La temperatura la manda la cátedra.")
        assertEquals(1024, resolucion.effective.maxTokens, "El tope de tokens lo manda la cátedra.")
        assertEquals(
            listOf(PolicyField.TEMPERATURE, PolicyField.MAX_TOKENS),
            resolucion.rejected.map { it.campo },
            "Lo único que se cuela es el modo local.",
        )
    }

    @Test
    fun `la catedra puede rechazar el modo local si no lo permite`() {
        val politica = ModelPolicy(mode = ModelMode.REMOTE, allowStudentOverride = false)
        val alumno = StudentModelProfile(
            userId = "u1",
            courseId = "c1",
            deltas = mapOf(PolicyField.MODE to "LOCAL"),
        )

        val resolucion = ModelResolver.resolve(politica, alumno, allowLocalModels = false)
        val cambio = assertNotNull(resolucion.changeFor(PolicyField.MODE))

        assertEquals(ModelMode.REMOTE, resolucion.effective.mode)
        assertEquals(ChangeStatus.REJECTED, cambio.estado)
    }

    // ------------------------------------------------------------------
    // Rango: se ajusta, no se rechaza
    // ------------------------------------------------------------------

    @Test
    fun `un delta de temperatura se acepta y uno fuera de rango se ajusta`() {
        val politica = ModelPolicy(
            temperature = 0.2,
            lockedFields = emptySet(),
        )
        val reglas = mapOf(PolicyField.TEMPERATURE to PolicyRule.Ranged(0.0, 0.8))

        val dentro = ModelResolver.resolve(
            politica,
            StudentModelProfile("u1", "c1", mapOf(PolicyField.TEMPERATURE to "0.5")),
            rules = reglas,
        )
        val cambioDentro = assertNotNull(dentro.changeFor(PolicyField.TEMPERATURE))
        assertEquals(0.5, dentro.effective.temperature, 0.0001)
        assertEquals(ChangeStatus.ACCEPTED, cambioDentro.estado)

        val fuera = ModelResolver.resolve(
            politica,
            StudentModelProfile("u1", "c1", mapOf(PolicyField.TEMPERATURE to "0.95")),
            rules = reglas,
        )
        val cambioFuera = assertNotNull(fuera.changeFor(PolicyField.TEMPERATURE))
        assertEquals(0.8, fuera.effective.temperature, 0.0001, "Fuera de rango se ajusta al borde.")
        assertEquals(ChangeStatus.CLAMPED, cambioFuera.estado)
        assertTrue(cambioFuera.motivo.isNotBlank(), "Un ajuste tiene que explicar el motivo.")
        assertTrue(fuera.rejected.isEmpty(), "Un valor ajustado no está rechazado: se aplicó recortado.")
    }

    // ------------------------------------------------------------------
    // El diff
    // ------------------------------------------------------------------

    @Test
    fun `el diff muestra base, pedido y efectivo`() {
        val politica = ModelPolicy(temperature = 0.2, lockedFields = emptySet())
        val alumno = StudentModelProfile(
            userId = "u1",
            courseId = "c1",
            deltas = mapOf(PolicyField.TEMPERATURE to "0.95"),
        )

        val resolucion = ModelResolver.resolve(
            politica,
            alumno,
            rules = mapOf(PolicyField.TEMPERATURE to PolicyRule.Ranged(0.0, 0.8)),
        )
        val cambio = assertNotNull(resolucion.changeFor(PolicyField.TEMPERATURE))

        assertEquals(PolicyField.TEMPERATURE, cambio.campo)
        assertEquals("0.2", cambio.valorBase, "La base es el valor de la cátedra.")
        assertEquals("0.95", cambio.valorPedido, "El pedido es lo que puso el alumno.")
        assertEquals("0.8", cambio.valorEfectivo, "El efectivo es lo que se usó.")
        assertEquals(ChangeOrigin.STUDENT, cambio.origen)
    }

    @Test
    fun `un campo que el alumno no toco aparece heredado y con origen de la catedra`() {
        val politica = ModelPolicy(topK = 5, lockedFields = emptySet())
        val alumno = StudentModelProfile("u1", "c1", emptyMap())

        val resolucion = ModelResolver.resolve(politica, alumno)
        val cambio = assertNotNull(resolucion.changeFor(PolicyField.TOP_K))

        assertEquals(ChangeStatus.INHERITED, cambio.estado)
        assertEquals(ChangeOrigin.POLICY, cambio.origen)
        assertEquals("5", cambio.valorBase)
        assertEquals("5", cambio.valorEfectivo)
    }

    // ------------------------------------------------------------------
    // El informe del docente
    // ------------------------------------------------------------------

    @Test
    fun `el informe del docente nunca contiene la api key`() {
        val politica = ModelPolicy(
            apiKey = "kitoru-catedra-123456",
            lockedFields = emptySet(),
        )
        val alumno = StudentModelProfile(
            userId = "u1",
            courseId = "c1",
            deltas = mapOf(PolicyField.API_KEY to "sk-alumno-propia-987654"),
        )

        val resolucion = ModelResolver.resolve(politica, alumno)
        val reporte = ModelResolver.report("u1", "Ana Gómez", resolucion)

        val texto = reporte.toString()
        assertFalse(texto.contains("sk-alumno-propia-987654"), "La clave del alumno no puede aparecer.")
        assertFalse(texto.contains("kitoru-catedra-123456"), "La clave de la cátedra no puede aparecer.")

        for (cambio in reporte.changes) {
            if (cambio.campo != PolicyField.API_KEY) continue
            assertFalse(cambio.valorBase.contains("kitoru-catedra"))
            assertFalse(cambio.valorPedido.contains("sk-alumno"))
            assertFalse(cambio.valorEfectivo.contains("sk-alumno"))
            assertEquals("••••7654", cambio.valorPedido, "La clave se muestra enmascarada.")
            assertEquals("••••3456", cambio.valorBase, "La clave se muestra enmascarada.")
        }
    }

    @Test
    fun `la clave bloqueada por la catedra se rechaza y no se filtra`() {
        val politica = ModelPolicy(
            apiKey = "kitoru-catedra-123456",
            apiKeyRef = "apikey:course:IA-2026-1",
            lockedFields = setOf(LockedField.API_KEY),
        )
        val alumno = StudentModelProfile(
            userId = "u1",
            courseId = "c1",
            deltas = mapOf(PolicyField.API_KEY to "sk-alumno-propia-987654"),
        )

        val resolucion = ModelResolver.resolve(politica, alumno)
        val cambio = assertNotNull(resolucion.changeFor(PolicyField.API_KEY))

        assertEquals(ChangeStatus.REJECTED, cambio.estado)
        assertEquals("kitoru-catedra-123456", resolucion.effective.apiKey, "Gana la clave de la cátedra.")
        assertEquals("apikey:course:IA-2026-1", resolucion.effective.apiKeyRef, "Gana el alias de la cátedra.")
        assertTrue(resolucion.rejected.contains(cambio))
        assertFalse(cambio.valorPedido.contains("sk-alumno"), "La clave pedida no puede aparecer en crudo.")
        assertEquals("••••7654", cambio.valorPedido)
    }

    @Test
    fun `la clave del alumno reemplaza el alias de la catedra`() {
        val politica = ModelPolicy(
            apiKey = "kitoru-catedra-123456",
            apiKeyRef = "apikey:course:IA-2026-1",
            lockedFields = emptySet(),
        )
        val alumno = StudentModelProfile(
            userId = "u1",
            courseId = "c1",
            deltas = mapOf(PolicyField.API_KEY to "sk-alumno-propia-987654"),
            personalKeyRef = "apikey:student:enroll_9f3",
        )

        val resolucion = ModelResolver.resolve(politica, alumno)

        assertEquals("sk-alumno-propia-987654", resolucion.effective.apiKey)
        assertEquals(
            "apikey:student:enroll_9f3",
            resolucion.effective.apiKeyRef,
            "El alias de la cátedra deja de aplicar cuando gana la clave del alumno.",
        )
    }

    @Test
    fun `la politica redacted queda sin la clave de api`() {
        val politica = ModelPolicy(apiKey = "kitoru-catedra-123456", apiKeyRef = "apikey:course:IA-2026-1")

        val redacted = politica.redacted()

        assertEquals("", redacted.apiKey, "La copia sin secreto no puede tener la clave.")
        assertEquals("", redacted.apiKeyRef, "La copia sin secreto no puede tener el alias.")
        assertEquals("kitoru-catedra-123456", politica.apiKey, "La original no se toca.")
    }

    // ------------------------------------------------------------------
    // Un cambio de la cátedra se propaga solo
    // ------------------------------------------------------------------

    @Test
    fun `un cambio de la catedra se propaga y no pisa el delta del alumno`() {
        val politica = ModelPolicy(topK = 5, lockedFields = emptySet())
        val alumnoQueNoLoToco = StudentModelProfile("u1", "c1", emptyMap())
        val alumnoQueLoToco = StudentModelProfile("u2", "c1", mapOf(PolicyField.TOP_K to "3"))

        val politicaNueva = politica.copy(topK = 8)

        val sinTocar = ModelResolver.resolve(politicaNueva, alumnoQueNoLoToco)
        val tocado = ModelResolver.resolve(politicaNueva, alumnoQueLoToco)

        assertEquals(8, sinTocar.effective.topK, "El cambio de la cátedra se propaga solo.")
        assertEquals(3, tocado.effective.topK, "El delta del alumno no se pisa.")
        assertEquals(
            ChangeStatus.INHERITED,
            assertNotNull(sinTocar.changeFor(PolicyField.TOP_K)).estado,
        )
        assertEquals(
            ChangeStatus.ACCEPTED,
            assertNotNull(tocado.changeFor(PolicyField.TOP_K)).estado,
        )
    }

    @Test
    fun `el perfil del alumno no copia la politica de la catedra`() {
        val politica = ModelPolicy(temperature = 0.2, topK = 5, lockedFields = emptySet())
        val alumno = StudentModelProfile("u1", "c1", mapOf(PolicyField.TEMPERATURE to "0.9"))

        val resolucion = ModelResolver.resolve(politica, alumno)

        assertEquals(0.9, resolucion.effective.temperature, 0.0001)
        assertEquals(0.2, politica.temperature, 0.0001, "La política de la cátedra no cambia.")
        assertEquals(1, alumno.deltas.size, "El perfil del alumno sólo guarda sus deltas.")
        assertEquals("0.9", alumno.delta(PolicyField.TEMPERATURE))
        assertNull(alumno.delta(PolicyField.TOP_K), "Lo que no se tocó no se guarda.")
    }

    // ------------------------------------------------------------------
    // El prompt del sistema se compone
    // ------------------------------------------------------------------

    @Test
    fun `el prompt del sistema se compone con el de la catedra`() {
        val politica = ModelPolicy(systemPrompt = "Sos un tutor de IA.", lockedFields = emptySet())
        val alumno = StudentModelProfile(
            userId = "u1",
            courseId = "c1",
            deltas = mapOf(PolicyField.SYSTEM_PROMPT to "Resumí en viñetas."),
        )

        val resolucion = ModelResolver.resolve(politica, alumno)
        val cambio = assertNotNull(resolucion.changeFor(PolicyField.SYSTEM_PROMPT))

        assertEquals("Sos un tutor de IA.\n\nResumí en viñetas.", resolucion.effective.systemPrompt)
        assertEquals(ChangeStatus.ACCEPTED, cambio.estado)
        assertEquals("Sos un tutor de IA.", cambio.valorBase)
        assertEquals("Resumí en viñetas.", cambio.valorPedido)
    }

    @Test
    fun `el prompt bloqueado descarta el del alumno`() {
        val politica = ModelPolicy(
            systemPrompt = "Sos un tutor de IA.",
            lockedFields = setOf(LockedField.SYSTEM_PROMPT),
        )
        val alumno = StudentModelProfile(
            userId = "u1",
            courseId = "c1",
            deltas = mapOf(PolicyField.SYSTEM_PROMPT to "Resumí en viñetas."),
        )

        val resolucion = ModelResolver.resolve(politica, alumno)
        val cambio = assertNotNull(resolucion.changeFor(PolicyField.SYSTEM_PROMPT))

        assertEquals("Sos un tutor de IA.", resolucion.effective.systemPrompt)
        assertEquals(ChangeStatus.REJECTED, cambio.estado)
        assertTrue(cambio.motivo.isNotBlank())
    }

    // ------------------------------------------------------------------
    // El modelo local es del alumno
    // ------------------------------------------------------------------

    @Test
    fun `el modelo local del alumno se usa en modo local y no toca la politica`() {
        val politica = ModelPolicy(
            mode = ModelMode.REMOTE,
            hfRepo = "unsloth/Phi-4-mini-instruct-GGUF",
            hfFile = "Phi-4-mini-instruct-Q4_K_M.gguf",
            contextSize = 8192,
            lockedFields = emptySet(),
        )
        val alumno = StudentModelProfile(
            userId = "u1",
            courseId = "c1",
            deltas = mapOf(PolicyField.MODE to "LOCAL"),
            localModel = LocalModelProfile(
                hfRepo = "bartowski/Phi-4-mini-instruct-GGUF",
                hfFile = "Phi-4-mini-instruct-Q8_0.gguf",
                localPort = 9090,
                contextSize = 16384,
                gpuLayers = 20,
            ),
        )

        val resolucion = ModelResolver.resolve(politica, alumno)

        assertEquals(ModelMode.LOCAL, resolucion.effective.mode)
        assertEquals("bartowski/Phi-4-mini-instruct-GGUF", resolucion.effective.hfRepo)
        assertEquals("Phi-4-mini-instruct-Q8_0.gguf", resolucion.effective.hfFile)
        assertEquals(9090, resolucion.effective.localPort)
        assertEquals(16384, resolucion.effective.contextSize)
        assertEquals(20, resolucion.effective.gpuLayers)
        assertEquals(
            "unsloth/Phi-4-mini-instruct-GGUF",
            politica.hfRepo,
            "La política de la cátedra no se ensucia con el modelo local del alumno.",
        )
    }

    @Test
    fun `la catedra puede acotar el contexto del modelo local`() {
        val politica = ModelPolicy(
            mode = ModelMode.REMOTE,
            contextSize = 8192,
            lockedFields = emptySet(),
        )
        val alumno = StudentModelProfile(
            userId = "u1",
            courseId = "c1",
            deltas = mapOf(PolicyField.MODE to "LOCAL"),
            localModel = LocalModelProfile(contextSize = 65536),
        )

        val resolucion = ModelResolver.resolve(
            politica,
            alumno,
            rules = mapOf(PolicyField.CONTEXT_SIZE to PolicyRule.Ranged(2048.0, 16384.0)),
        )
        val cambio = assertNotNull(resolucion.changeFor(PolicyField.CONTEXT_SIZE))

        assertEquals(16384, resolucion.effective.contextSize, "El contexto se recorta al rango de la cátedra.")
        assertEquals(ChangeStatus.CLAMPED, cambio.estado)
    }

    // ------------------------------------------------------------------
    // El preset de la cátedra
    // ------------------------------------------------------------------

    @Test
    fun `el preset completa lo que el alumno no toco y el delta lo pisa`() {
        val politica = ModelPolicy(temperature = 0.2, modelName = "qwen3-32b", lockedFields = emptySet())
        val preset = PolicyPreset(
            id = "rapido",
            label = "Rápido",
            values = mapOf(PolicyField.TEMPERATURE to "0.1", PolicyField.MODEL_NAME to "qwen3-8b"),
        )

        val soloPreset = ModelResolver.resolve(politica, StudentModelProfile("u1", "c1", emptyMap()), preset = preset)
        assertEquals(0.1, soloPreset.effective.temperature, 0.0001)
        assertEquals("qwen3-8b", soloPreset.effective.modelName)
        assertEquals(
            ChangeOrigin.PRESET,
            assertNotNull(soloPreset.changeFor(PolicyField.TEMPERATURE)).origen,
        )

        val conDelta = ModelResolver.resolve(
            politica,
            StudentModelProfile("u1", "c1", mapOf(PolicyField.TEMPERATURE to "0.6")),
            preset = preset,
        )
        assertEquals(0.6, conDelta.effective.temperature, 0.0001, "El delta del alumno pisa al preset.")
        assertEquals(
            ChangeOrigin.STUDENT,
            assertNotNull(conDelta.changeFor(PolicyField.TEMPERATURE)).origen,
        )
    }

    // ------------------------------------------------------------------
    // El valor pedido se interpreta según el tipo del campo
    // ------------------------------------------------------------------

    @Test
    fun `un valor que no se puede interpretar se rechaza`() {
        val politica = ModelPolicy(temperature = 0.2, lockedFields = emptySet())
        val alumno = StudentModelProfile("u1", "c1", mapOf(PolicyField.TEMPERATURE to "muy alta"))

        val resolucion = ModelResolver.resolve(politica, alumno)
        val cambio = assertNotNull(resolucion.changeFor(PolicyField.TEMPERATURE))

        assertEquals(ChangeStatus.REJECTED, cambio.estado)
        assertEquals(0.2, resolucion.effective.temperature, 0.0001)
        assertTrue(cambio.motivo.isNotBlank())
    }

    @Test
    fun `la resolucion es determinista`() {
        val politica = ModelPolicy(temperature = 0.2, topK = 5, lockedFields = emptySet())
        val alumno = StudentModelProfile("u1", "c1", mapOf(PolicyField.TEMPERATURE to "0.4"))

        val primera = ModelResolver.resolve(politica, alumno)
        val segunda = ModelResolver.resolve(politica, alumno)

        assertEquals(primera.effective, segunda.effective)
        assertEquals(primera.changes, segunda.changes)
    }
}
