package kitoru.ui.chat

import kitoru.domain.CitationStats
import kitoru.domain.Course
import kitoru.domain.Material
import kitoru.domain.ModelMode
import kitoru.domain.ModelPolicy
import kitoru.testing.FailingGateway
import kitoru.testing.ScriptedGateway
import kitoru.ui.Clock
import kitoru.ui.FixedClock
import kitoru.ui.SteppingClock
import llm.api.LlmChunk
import llm.api.LlmGateway
import llm.api.ToolSpec
import llm.config.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import rag.config.RagConfig
import rag.retrieval.CourseMaterialSource
import rag.retrieval.KnowledgeBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests del ViewModel del chat.
 *
 * El motor se prueba contra un gateway falso (`ScriptedGateway`): ni red ni
 * modelo. El scope, el dispatcher y el reloj se inyectan, así que el test
 * controla exactamente cuándo corre cada corrutina y ningún test depende de
 * la hora.
 */
class ChatViewModelTest {

    // -----------------------------------------------------------------------
    // Utilidades
    // -----------------------------------------------------------------------

    private val curso = Course(
        id = "c1",
        code = "IA-2026-1",
        name = "Inteligencia Artificial",
        term = "2026-1",
        modelPolicy = ModelPolicy(
            mode = ModelMode.REMOTE,
            remoteBaseUrl = "http://localhost:8080/v1",
            modelName = "modelo-de-prueba",
            topK = 5,
        ),
        systemPrompt = "Respondé con el contexto y citá [1].",
    )

    /**
     * Un chat con el scope, el dispatcher y el reloj del test.
     *
     * El dispatcher virtual es el mismo para el scope y para el trabajo
     * pesado: así ninguna parte de la respuesta se escapa del reloj del test.
     */
    private fun TestScope.nuevoChat(
        gateway: LlmGateway,
        kb: KnowledgeBase = KnowledgeBase(RagConfig()),
        clock: Clock = FixedClock(0L),
    ): ChatViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ChatViewModel(
            course = curso,
            kb = kb,
            gateway = gateway,
            scope = CoroutineScope(dispatcher),
            workDispatcher = dispatcher,
            clock = clock,
        )
    }

    /** Un gateway que responde con un texto y nada más. */
    private fun gatewayDe(respuesta: String) = ScriptedGateway(listOf(listOf(LlmChunk.Text(respuesta))))

    // -----------------------------------------------------------------------
    // Enviar y mostrar
    // -----------------------------------------------------------------------

    @Test
    fun `enviar agrega el mensaje del usuario y el placeholder del asistente`() = runTest {
        val chat = nuevoChat(gatewayDe("hola"))
        chat.start(curso.modelPolicy)

        chat.send("¿Qué es IA?")

        assertEquals(2, chat.messages.size, "Se agrega el turno del alumno y el del modelo.")
        assertEquals("user", chat.messages[0].role)
        assertEquals("¿Qué es IA?", chat.messages[0].text)
        assertEquals("assistant", chat.messages[1].role)
        assertEquals("", chat.messages[1].text, "El turno del modelo arranca como placeholder vacío.")
    }

    @Test
    fun `enviar un texto vacio no hace nada`() = runTest {
        val chat = nuevoChat(gatewayDe("hola"))
        chat.start(curso.modelPolicy)

        chat.send("   ")

        assertTrue(chat.messages.isEmpty())
        assertFalse(chat.busy)
    }

    @Test
    fun `el streaming acumula el texto del modelo`() = runTest {
        val gateway = ScriptedGateway(
            listOf(listOf(LlmChunk.Text("La "), LlmChunk.Text("respuesta "), LlmChunk.Text("completa"))),
        )
        val chat = nuevoChat(gateway)
        chat.start(curso.modelPolicy)

        chat.send("¿Qué es IA?")
        advanceUntilIdle()

        assertEquals("La respuesta completa", chat.messages.last().text)
    }

    @Test
    fun `el streaming no crea un mensaje nuevo por fragmento`() = runTest {
        val gateway = ScriptedGateway(
            listOf(listOf(LlmChunk.Text("uno "), LlmChunk.Text("dos "), LlmChunk.Text("tres"))),
        )
        val chat = nuevoChat(gateway)
        chat.start(curso.modelPolicy)

        chat.send("¿Qué es IA?")
        advanceUntilIdle()

        assertEquals(2, chat.messages.size, "Cada fragmento actualiza el último mensaje, no crea uno nuevo.")
    }

    @Test
    fun `terminar la respuesta apaga la bandera de ocupado`() = runTest {
        val chat = nuevoChat(gatewayDe("hola"))
        chat.start(curso.modelPolicy)

        chat.send("¿Qué es IA?")
        assertTrue(chat.busy, "Mientras se pide la respuesta el chat está ocupado.")

        advanceUntilIdle()

        assertFalse(chat.busy)
    }

    @Test
    fun `borrar un mensaje lo quita del hilo`() = runTest {
        val chat = nuevoChat(gatewayDe("hola"))
        chat.start(curso.modelPolicy)
        chat.send("¿Qué es IA?")
        advanceUntilIdle()
        assertEquals(2, chat.messages.size)

        chat.deleteAt(0)

        assertEquals(1, chat.messages.size)
        assertEquals("assistant", chat.messages[0].role)
    }

    @Test
    fun `toggleDocs alterna entre usar el material y pregunta libre`() = runTest {
        val chat = nuevoChat(gatewayDe("hola"))

        assertTrue(chat.useDocs, "Por defecto el chat usa el material de la cátedra.")

        chat.toggleDocs()
        assertFalse(chat.useDocs, "Después de un toque es una pregunta libre.")

        chat.toggleDocs()
        assertTrue(chat.useDocs, "El interruptor es de dos estados.")
    }

    // -----------------------------------------------------------------------
    // La cola
    // -----------------------------------------------------------------------

    @Test
    fun `enviar mientras responde encola el mensaje`() = runTest {
        val chat = nuevoChat(gatewayDe("hola"))
        chat.start(curso.modelPolicy)

        chat.send("primera")
        chat.send("segunda")

        assertEquals(listOf("segunda"), chat.queued.toList())
        assertEquals(2, chat.messages.size, "Lo encolado todavía no es un turno del hilo.")
    }

    @Test
    fun `la cola se manda al terminar la respuesta`() = runTest {
        val gateway = ScriptedGateway(
            listOf(
                listOf(LlmChunk.Text("primera respuesta")),
                listOf(LlmChunk.Text("segunda respuesta")),
            ),
        )
        val chat = nuevoChat(gateway)
        chat.start(curso.modelPolicy)

        chat.send("primera")
        chat.send("segunda")
        advanceUntilIdle()

        assertTrue(chat.queued.isEmpty(), "La cola se vacía al mandarse.")
        assertEquals(4, chat.messages.size, "La pregunta encolada se convierte en un turno más.")
        assertEquals("segunda respuesta", chat.messages.last().text)
    }

    // -----------------------------------------------------------------------
    // Cancelar y fallar
    // -----------------------------------------------------------------------

    @Test
    fun `cancelar corta la respuesta y deja el mensaje cancelado`() = runTest {
        val chat = nuevoChat(GatewayLento())
        chat.start(curso.modelPolicy)

        chat.send("¿Qué es IA?")
        runCurrent()
        assertTrue(chat.busy)

        chat.cancel()
        runCurrent()

        assertEquals("(cancelado)", chat.messages.last().text)
        assertFalse(chat.busy)
    }

    @Test
    fun `cancelar descarta lo que estaba en cola`() = runTest {
        val chat = nuevoChat(GatewayLento())
        chat.start(curso.modelPolicy)

        chat.send("primera")
        runCurrent()
        chat.send("segunda")
        assertEquals(listOf("segunda"), chat.queued.toList())

        chat.cancel()
        runCurrent()

        assertTrue(chat.queued.isEmpty(), "Cancelar descarta lo pendiente: ${chat.queued.toList()}")
    }

    @Test
    fun `un gateway que falla deja el error en el mensaje y no rompe la app`() = runTest {
        val chat = nuevoChat(FailingGateway(IllegalStateException("El servidor no responde")))
        chat.start(curso.modelPolicy)

        chat.send("¿Qué es IA?")
        advanceUntilIdle()

        val ultimo = chat.messages.last()
        assertNotNull(ultimo.error, "El fallo queda en el mensaje, no en una excepción.")
        assertEquals("El servidor no responde", ultimo.error)
        assertFalse(chat.busy, "Después del error el chat vuelve a estar disponible.")
    }

    // -----------------------------------------------------------------------
    // Lo que se le manda al modelo
    // -----------------------------------------------------------------------

    @Test
    fun `sin material el modelo recibe que no hay resultados`() = runTest {
        val gateway = gatewayDe("No encontré eso")
        val chat = nuevoChat(gateway)
        chat.start(curso.modelPolicy)

        chat.send("¿Qué es el teorema de Bayes?")
        advanceUntilIdle()

        val pregunta = gateway.requests.last().last()
        assertTrue(
            pregunta.content.contains("(sin resultados en el material de la cátedra)"),
            "El modelo tiene que saber que no hay material: '${pregunta.content}'",
        )
    }

    @Test
    fun `el historial que se manda al modelo esta recortado`() = runTest {
        val gateway = ScriptedGateway(List(9) { listOf(LlmChunk.Text("respuesta")) })
        val chat = nuevoChat(gateway)
        chat.start(curso.modelPolicy)

        repeat(8) { i ->
            chat.send("pregunta $i")
            advanceUntilIdle()
        }

        val request = gateway.requests.last()
        // 1 (system) + 12 (historial recortado a 6 turnos) + 1 (pregunta).
        assertEquals(14, request.size, "El historial se recorta: ${request.size} mensajes.")
        assertEquals("system", request.first().role)
        assertEquals("pregunta 7", request.last().content.substringAfterLast("PREGUNTA:\n"))
    }

    @Test
    fun `la latencia de la respuesta se mide con el reloj inyectado`() = runTest {
        val chat = nuevoChat(
            gateway = gatewayDe("hola"),
            clock = SteppingClock(listOf(1_000L, 2_500L)),
        )
        chat.start(curso.modelPolicy)

        chat.send("¿Qué es IA?")
        advanceUntilIdle()

        assertEquals(1_500L, chat.messages.last().latencyMs)
    }

    // -----------------------------------------------------------------------
    // Prueba de humo de punta a punta
    // -----------------------------------------------------------------------

    @Test
    fun `con material de la catedra la respuesta sale con las fuentes correctas`() = runTest {
        val material = Material(
            id = "mat-1",
            courseId = "c1",
            title = "Búsqueda heurística",
            extractedText = """
                # Búsqueda heurística

                La búsqueda heurística no garantiza el óptimo: garantiza encontrar
                una solución buena en mucho menos tiempo.
            """.trimIndent(),
        )
        val kb = KnowledgeBase(RagConfig(chunkSize = 1200, topK = 5))
        kb.sources = listOf(CourseMaterialSource(listOf(material)))
        kb.sync()

        val gateway = gatewayDe(
            "La búsqueda heurística no garantiza el óptimo: garantiza encontrar una solución buena [1].",
        )
        val chat = nuevoChat(gateway, kb)
        chat.start(curso.modelPolicy)

        chat.send("¿Qué garantiza la búsqueda heurística?")
        advanceUntilIdle()

        val ultimo = chat.messages.last()
        assertEquals(
            listOf("cátedra: Búsqueda heurística"),
            ultimo.sources,
            "La respuesta tiene que citar el material de la cátedra.",
        )
        assertEquals(
            CitationStats(verified = 1, unsupported = 0, unknown = 0),
            ultimo.citation,
            "La cita tiene que estar respaldada por la fuente recuperada.",
        )
    }
}

/**
 * Gateway que se queda esperando.
 *
 * Sirve para probar el botón "Detener": el modelo empieza a responder y
 * nunca termina, así que el test lo cancela en mitad del streaming.
 */
private class GatewayLento : LlmGateway {
    override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> = flow {
        emit(LlmChunk.Text("empezando"))
        delay(60_000)
        emit(LlmChunk.Text("esto nunca se escribe"))
    }
}
