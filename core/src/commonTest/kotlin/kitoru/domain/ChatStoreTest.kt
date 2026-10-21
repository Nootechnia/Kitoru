package kitoru.domain

import kitoru.platform.FilePath
import kitoru.testing.InMemoryFileSystem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests de la persistencia de chats.
 *
 * Cubren lo que el store promete:
 * 1. guardar y volver a leer un hilo,
 * 2. el append de muchos mensajes,
 * 3. que una línea corrupta no tire abajo el hilo,
 * 4. el borrado lógico (desaparece del listado, el archivo sigue),
 * 5. la exportación y la importación de un paquete `.kitoru`,
 * 6. que un manifiesto alterado no importe nada,
 * 7. que las cátedras no se mezclen.
 */
class ChatStoreTest {

    private val fs = InMemoryFileSystem()
    private val store = ChatStore(fs) { FilePath("datos") }

    // -----------------------------------------------------------------------
    // Utilidades
    // -----------------------------------------------------------------------

    private fun hilo(
        courseId: String = CURSO,
        id: String = HILO,
        authorId: String = AUTOR,
    ) = ChatThread(
        id = id,
        courseId = courseId,
        authorId = authorId,
        authorName = "Ana Pérez",
        title = "Overfitting",
        createdAt = 1_000L,
        updatedAt = 2_000L,
    )

    private fun mensaje(
        threadId: String = HILO,
        index: Int = 0,
        role: String = "user",
    ) = ChatMessage(
        id = "msg-$index",
        threadId = threadId,
        role = role,
        text = "mensaje $index",
        createdAt = 1_000L + index,
    )

    // -----------------------------------------------------------------------
    // Hilos
    // -----------------------------------------------------------------------

    @Test
    fun `guarda un hilo y lo vuelve a leer`() = runTest {
        store.saveThread(hilo())

        val leidos = store.listThreads(CURSO)

        assertEquals(1, leidos.size)
        assertEquals(HILO, leidos.first().id)
        assertEquals("Overfitting", leidos.first().title)
        assertEquals(CURSO, leidos.first().courseId)
        assertEquals(AUTOR, leidos.first().authorId)
    }

    @Test
    fun `el hilo queda en el layout de la cátedra`() = runTest {
        store.saveThread(hilo())

        val meta = store.metaPath(CURSO, AUTOR, HILO)
        val mensajes = store.messagesPath(CURSO, AUTOR, HILO)

        assertTrue(fs.exists(meta), "tiene que existir el meta.json del hilo")
        assertTrue(fs.exists(mensajes), "tiene que existir el messages.jsonl del hilo")
        assertEquals("datos/$CURSO/chats/$AUTOR/$HILO/meta.json", meta.value)
        assertEquals("datos/$CURSO/chats/$AUTOR/$HILO/messages.jsonl", mensajes.value)
    }

    @Test
    fun `la escritura es atómica: no queda ningún temporal`() = runTest {
        store.saveThread(hilo())
        store.appendMessage(mensaje())

        val temporales = fs.writtenPaths().filter { it.endsWith(".tmp") }

        assertTrue(temporales.isEmpty(), "no puede quedar ningún temporal: $temporales")
    }

    @Test
    fun `la clave de api no se escribe en disco`() = runTest {
        val conClave = hilo().copy(effectiveModel = ModelPolicy(apiKey = "secreto"))

        store.saveThread(conClave)

        val meta = fs.peek(store.metaPath(CURSO, AUTOR, HILO).value) ?: ""
        assertFalse(meta.contains("secreto"), "la clave de API no puede quedar escrita en el disco")
        assertEquals("", store.getThread(HILO)?.effectiveModel?.apiKey)
    }

    // -----------------------------------------------------------------------
    // Mensajes
    // -----------------------------------------------------------------------

    @Test
    fun `agrega 500 mensajes al hilo`() = runTest {
        store.saveThread(hilo())

        repeat(500) { store.appendMessage(mensaje(index = it)) }

        val leidos = store.messages(HILO)
        assertEquals(500, leidos.size)
        assertEquals("mensaje 0", leidos.first().text)
        assertEquals("mensaje 499", leidos.last().text)
    }

    @Test
    fun `cada mensaje es una línea del jsonl`() = runTest {
        store.saveThread(hilo())
        store.appendMessage(mensaje(index = 0))
        store.appendMessage(mensaje(index = 1, role = "assistant"))

        val contenido = fs.peek(store.messagesPath(CURSO, AUTOR, HILO).value) ?: ""
        val lineas = contenido.trim().lines()

        assertEquals(2, lineas.size, "el JSONL lleva un mensaje por línea")
        assertTrue(lineas.all { it.startsWith("{") && it.endsWith("}") }, "cada línea es un JSON")
    }

    @Test
    fun `una línea corrupta no pierde el resto del hilo`() = runTest {
        store.saveThread(hilo())
        store.appendMessage(mensaje(index = 0))
        store.appendMessage(mensaje(index = 1))
        val ruta = store.messagesPath(CURSO, AUTOR, HILO)
        val original = fs.peek(ruta.value) ?: ""

        // Se inyecta una línea rota en el medio del JSONL.
        fs.put(ruta.value, original.replaceFirst("\n", "\n{esto no es json}\n"))

        val lectura = store.readThread(HILO)
        assertEquals(2, lectura.messages.size, "las líneas sanas tienen que seguir ahí")
        assertEquals(1, lectura.corruptedLines, "la línea corrupta se cuenta y se saltea")
        assertEquals("mensaje 0", lectura.messages.first().text)
    }

    @Test
    fun `agregar un mensaje a un hilo inexistente falla con un mensaje claro`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            store.appendMessage(mensaje(threadId = "no-existe"))
        }

        assertTrue(
            error.message?.contains("no-existe") == true,
            "el error tiene que decir qué hilo falta: ${error.message}",
        )
    }

    // -----------------------------------------------------------------------
    // Borrado lógico
    // -----------------------------------------------------------------------

    @Test
    fun `el borrado lógico saca el hilo del listado pero deja el archivo`() = runTest {
        store.saveThread(hilo())
        store.appendMessage(mensaje())

        store.deleteThread(HILO)

        assertTrue(store.listThreads(CURSO).isEmpty(), "un hilo borrado no se lista")
        assertTrue(
            fs.exists(store.metaPath(CURSO, AUTOR, HILO)),
            "el borrado lógico no borra el archivo del disco",
        )
        assertEquals(
            1,
            store.messages(HILO).size,
            "los mensajes siguen en el disco: el borrado es lógico",
        )
    }

    // -----------------------------------------------------------------------
    // Paquete .kitoru
    // -----------------------------------------------------------------------

    @Test
    fun `exporta e importa un paquete sin perder datos`() = runTest {
        store.saveThread(hilo())
        store.appendMessage(mensaje(index = 0))
        store.appendMessage(mensaje(index = 1, role = "assistant"))

        val bytes = KitoruPackage.export(store, listOf(hilo()))
        val contenido = KitoruPackage.importPackage(bytes)

        assertEquals(1, contenido.threads.size)
        assertEquals("Overfitting", contenido.threads.first().title)
        assertEquals(2, contenido.messages.size)
        assertEquals(0, contenido.corruptedLines)

        val destino = ChatStore(InMemoryFileSystem()) { FilePath("destino") }
        KitoruPackage.importInto(destino, bytes)

        assertEquals(1, destino.listThreads(CURSO).size)
        assertEquals(2, destino.messages(HILO).size)
        assertEquals("mensaje 1", destino.messages(HILO).last().text)
    }

    @Test
    fun `el manifiesto alterado no importa nada`() = runTest {
        store.saveThread(hilo())
        store.appendMessage(mensaje(index = 0))
        val bytes = KitoruPackage.export(store, listOf(hilo()))

        // Se altera el manifiesto: los hashes ya no coinciden con el contenido.
        val entradas = KitoruZip.read(bytes)
        val manifiesto = chatJson.decodeFromString(
            PackageManifest.serializer(),
            entradas.getValue(KitoruPackage.MANIFEST_ENTRY).decodeToString(),
        )
        val alterado = manifiesto.copy(entries = manifiesto.entries.map { it.copy(sha256 = "0".repeat(64)) })
        val manipulado = KitoruZip.write(
            buildList {
                add(
                    KitoruZip.Entry(
                        KitoruPackage.MANIFEST_ENTRY,
                        chatJson.encodeToString(PackageManifest.serializer(), alterado).encodeToByteArray(),
                    ),
                )
                for ((nombre, datos) in entradas) {
                    if (nombre != KitoruPackage.MANIFEST_ENTRY) add(KitoruZip.Entry(nombre, datos))
                }
            },
        )

        assertFailsWith<KitoruPackage.InvalidPackageException> {
            KitoruPackage.importPackage(manipulado)
        }

        val destino = ChatStore(InMemoryFileSystem()) { FilePath("destino") }
        assertFailsWith<KitoruPackage.InvalidPackageException> {
            KitoruPackage.importInto(destino, manipulado)
        }
        assertTrue(destino.listThreads(CURSO).isEmpty(), "un paquete inválido no deja hilos a medias")
    }

    // -----------------------------------------------------------------------
    // Aislamiento entre cátedras
    // -----------------------------------------------------------------------

    @Test
    fun `los hilos de cátedras distintas no se mezclan`() = runTest {
        store.saveThread(hilo(courseId = "curso-a", id = "hilo-a"))
        store.saveThread(hilo(courseId = "curso-b", id = "hilo-b"))

        assertEquals(listOf("hilo-a"), store.listThreads("curso-a").map { it.id })
        assertEquals(listOf("hilo-b"), store.listThreads("curso-b").map { it.id })
        assertEquals(1, store.listThreads("curso-a", AUTOR).size)
        assertEquals(1, store.listThreads("curso-b", AUTOR).size)
    }

    @Test
    fun `los mensajes de un hilo no se mezclan con los de otro`() = runTest {
        store.saveThread(hilo(courseId = "curso-a", id = "hilo-a"))
        store.saveThread(hilo(courseId = "curso-b", id = "hilo-b"))
        store.appendMessage(mensaje(threadId = "hilo-a", index = 0))
        store.appendMessage(mensaje(threadId = "hilo-b", index = 1))

        assertEquals(1, store.messages("hilo-a").size)
        assertEquals("mensaje 0", store.messages("hilo-a").first().text)
        assertEquals("mensaje 1", store.messages("hilo-b").first().text)
    }

    // -----------------------------------------------------------------------
    // Sumas de control
    // -----------------------------------------------------------------------

    @Test
    fun `el sha256 coincide con el vector conocido`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun `el crc32 coincide con el vector conocido`() {
        assertEquals(0xCBF43926.toInt(), Crc32.of("123456789".encodeToByteArray()))
    }

    private companion object {
        const val CURSO = "curso-1"
        const val HILO = "hilo-1"
        const val AUTOR = "autor-1"
    }
}
