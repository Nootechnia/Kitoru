package kitoru.domain

import kitoru.platform.FilePath
import kitoru.testing.InMemoryFileSystem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests del contrato del corpus: manifiesto, versionado, calidad y
 * publicación.
 *
 * Son **deterministas**: no dependen del disco real (usa el
 * `InMemoryFileSystem` del testkit), ni de la red, ni del modelo. Los
 * mismos datos tienen que dar el mismo informe en cualquier máquina.
 */
class CorpusManifestTest {

    // ------------------------------------------------------------------
    // Manifiesto: serialización
    // ------------------------------------------------------------------

    @Test
    fun `el manifiesto se serializa y se vuelve a leer sin perder nada`() {
        val manifest = manifestDeEjemplo()

        val json = KitoruJson.encodeToString(CorpusManifest.serializer(), manifest)
        val vuelta = KitoruJson.decodeFromString(CorpusManifest.serializer(), json)

        assertEquals(manifest, vuelta)
        assertEquals("catedra-1", vuelta.courseId)
        assertEquals(7L, vuelta.version)
        assertEquals(1_700_000_000_000L, vuelta.generatedAt)
        assertEquals(900, vuelta.chunking.chunkSize)
        assertEquals(120, vuelta.chunking.chunkOverlap)
        assertEquals(listOf("mat-1", "mat-2"), vuelta.materialIds)

        val primera = vuelta.materials.first()
        assertEquals("Apuntes-IA.pdf", primera.title)
        assertEquals(MaterialKind.DOCUMENT, primera.kind)
        assertEquals("hash-1", primera.sha256)
        assertEquals(1200, primera.textBytes)
        assertEquals(3, primera.version)
        assertTrue(primera.visible)
        assertEquals(listOf("unidad-1", "unidad-2"), primera.unitIds)
    }

    @Test
    fun `el manifiesto sobrevive al store sin perder nada`() = runTest {
        val fs = InMemoryFileSystem()
        val store = storeDePrueba(fs)
        val manifest = manifestDeEjemplo()

        store.write(manifest)

        val leido = store.read("catedra-1")
        assertNotNull(leido)
        assertEquals(manifest, leido)
        assertEquals(7L, store.version("catedra-1"))
    }

    @Test
    fun `el manifiesto vive en courses sobre courseId sobre corpus`() {
        val store = storeDePrueba(InMemoryFileSystem())

        val ruta = store.manifestPath("catedra-1").value

        assertEquals("/data/courses/catedra-1/corpus/manifest.json", ruta)
    }

    @Test
    fun `un manifiesto inexistente no es un error`() = runTest {
        val store = storeDePrueba(InMemoryFileSystem())

        assertNull(store.read("catedra-1"))
        assertEquals(0L, store.version("catedra-1"))
        assertFalse(store.exists("catedra-1"))
    }

    // ------------------------------------------------------------------
    // Manifiesto: comparación (sincronización incremental)
    // ------------------------------------------------------------------

    @Test
    fun `compare detecta cada tipo de cambio`() {
        val anterior = CorpusManifest(
            courseId = "catedra-1",
            version = 3L,
            materials = listOf(
                entrada("mat-1", "Apuntes-IA.pdf", "hash-1"),
                entrada("mat-2", "Diapositivas", "hash-2"),
                entrada("mat-3", "Parcial 1", "hash-3"),
            ),
        )
        val nuevo = CorpusManifest(
            courseId = "catedra-1",
            version = 4L,
            materials = listOf(
                entrada("mat-1", "Apuntes-IA.pdf", "hash-1-cambiado"), // cambió el texto
                entrada("mat-2", "Diapositivas", "hash-2", visible = false), // cambió la visibilidad
                entrada("mat-4", "Transcripción", "hash-4"), // nuevo
            ),
        )

        val diff = CorpusManifest.compare(anterior, nuevo)

        // Agregado.
        assertEquals(listOf("mat-4"), diff.added.map { it.materialId })
        // Cambiado (el texto es distinto: hay que rebajarlo).
        assertEquals(listOf("mat-1"), diff.changed.map { it.materialId })
        // Quitado (el material ya no está: hay que borrarlo del dispositivo).
        assertEquals(listOf("mat-3"), diff.removed.map { it.materialId })
        // Visibilidad.
        assertEquals(1, diff.visibilityChanged.size)
        val cambio = diff.visibilityChanged.first()
        assertEquals("mat-2", cambio.materialId)
        assertTrue(cambio.visibleBefore)
        assertFalse(cambio.visibleNow)
        assertTrue(cambio.becameHidden)
        assertFalse(cambio.becameVisible)
        // Lo que no cambió se reporta como tal.
        assertTrue(diff.unchanged.isEmpty())
        // Lo que hay que descargar es lo agregado + lo cambiado.
        assertEquals(listOf("mat-4", "mat-1"), diff.toDownload.map { it.materialId })
        assertTrue(diff.hasChanges)
        assertTrue(diff.summary().contains("1 agregados"))
    }

    @Test
    fun `compare detecta los metadatos que cambian sin pedir descarga`() {
        val anterior = CorpusManifest(
            courseId = "catedra-1",
            materials = listOf(entrada("mat-1", "Apuntes-IA.pdf", "hash-1")),
        )
        val nuevo = CorpusManifest(
            courseId = "catedra-1",
            materials = listOf(entrada("mat-1", "Apuntes-IA v2.pdf", "hash-1")),
        )

        val diff = CorpusManifest.compare(anterior, nuevo)

        assertTrue(diff.changed.isEmpty())
        assertEquals(listOf("mat-1"), diff.metadataChanged.map { it.materialId })
        assertTrue(diff.toDownload.isEmpty())
    }

    @Test
    fun `la primera publicacion marca todo como nuevo`() {
        val nuevo = CorpusManifest(
            courseId = "catedra-1",
            version = 1L,
            materials = listOf(entrada("mat-1", "Apuntes-IA.pdf", "hash-1")),
        )

        val diff = CorpusManifest.compare(null, nuevo)

        assertEquals(1, diff.added.size)
        assertTrue(diff.changed.isEmpty())
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun `sin cambios no hay diff`() {
        val manifest = CorpusManifest(
            courseId = "catedra-1",
            materials = listOf(entrada("mat-1", "Apuntes-IA.pdf", "hash-1")),
        )

        val diff = CorpusManifest.compare(manifest, manifest)

        assertFalse(diff.hasChanges)
        assertEquals(listOf("mat-1"), diff.unchanged)
        assertTrue(diff.toDownload.isEmpty())
    }

    // ------------------------------------------------------------------
    // Hash del texto
    // ------------------------------------------------------------------

    @Test
    fun `el hash cambia si cambia un caracter del texto`() {
        val original = ContentHasher.exact("El perceptrón es la unidad básica de una red neuronal.")
        val cambiado = ContentHasher.exact("El perceptrón es la unidad básica de una red neuronal!")

        assertNotEquals(original, cambiado)
        // El mismo texto da siempre el mismo hash.
        assertEquals(original, ContentHasher.exact("El perceptrón es la unidad básica de una red neuronal."))
    }

    @Test
    fun `el hash respeta los vectores de control de sha256`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hexOf(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hexOf("abc"),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hexOf("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun `el hash normalizado ignora acentos y mayusculas`() {
        val conAcentos = "Perceptrón Y Redes"
        val sinAcentos = "perceptron y redes"

        // El hash del contrato (exacto) **sí** cambia: es el texto que viaja.
        assertNotEquals(ContentHasher.exact(conAcentos), ContentHasher.exact(sinAcentos))
        // El hash normalizado no: sirve para detectar duplicados.
        assertEquals(
            ContentHasher.normalizedHash(conAcentos),
            ContentHasher.normalizedHash(sinAcentos),
        )
    }

    // ------------------------------------------------------------------
    // Calidad: un test por check
    // ------------------------------------------------------------------

    @Test
    fun `el checker detecta el texto vacio`() {
        val material = materialDePrueba(id = "mat-1", title = "Apuntes-IA.pdf", texto = "")

        val informe = CorpusQualityChecker.check(listOf(material), emptyList())

        val check = informe.checks.first { it.id == "VACIO" }
        assertEquals(CheckSeverity.BLOQUEANTE, check.severity)
        assertTrue(check.message.contains("Apuntes-IA.pdf"))
        assertTrue(check.message.contains("escaneo"))
        assertTrue(informe.blocking.isNotEmpty())
        assertFalse(informe.publishable)
        // Un material bloqueante no puede estar sano.
        assertEquals(70, informe.healthScore)
    }

    @Test
    fun `el checker detecta el fragmento gigante unico`() {
        val texto = "a".repeat(4100)
        val material = materialDePrueba(id = "mat-1", title = "Diapositivas.pdf", texto = texto)
        val chunks = listOf(CorpusChunk("mat-1", "Diapositivas.pdf", texto))

        val informe = CorpusQualityChecker.check(listOf(material), chunks)

        val check = informe.checks.first { it.id == "CHUNK_UNICO" }
        assertEquals(CheckSeverity.ADVERTENCIA, check.severity)
        assertEquals(
            "«Diapositivas.pdf» se indexó en 1 fragmento de 4100 caracteres: " +
                "conviene subirlo más troceado o como Markdown.",
            check.message,
        )
        assertTrue(informe.warnings.isNotEmpty())
        assertTrue(informe.publishable)
    }

    @Test
    fun `el checker detecta los fragmentos diminutos`() {
        val material = materialDePrueba(id = "mat-1", title = "Apuntes-IA.pdf", texto = "contenido")
        val chunks = listOf(
            CorpusChunk("mat-1", "Página 1", "x".repeat(50)),
            CorpusChunk("mat-1", "Página 2", "y".repeat(80)),
            CorpusChunk("mat-1", "Página 3", "z".repeat(120)),
        )

        val informe = CorpusQualityChecker.check(listOf(material), chunks)

        val check = informe.checks.first { it.id == "CHUNK_DIMINUTO" }
        assertEquals(CheckSeverity.ADVERTENCIA, check.severity)
        assertEquals(3, check.count)
        assertTrue(check.message.contains("menos de 200 caracteres"))
    }

    @Test
    fun `el checker detecta la falta de encabezados`() {
        val material = materialDePrueba(id = "mat-1", title = "Apuntes-IA.pdf", texto = "contenido")
        val chunks = listOf(
            CorpusChunk("mat-1", "Apuntes-IA", "a".repeat(300)),
            CorpusChunk("mat-1", "Apuntes-IA", "b".repeat(300)),
            CorpusChunk("mat-1", "Apuntes-IA", "c".repeat(300)),
        )

        val informe = CorpusQualityChecker.check(listOf(material), chunks)

        val check = informe.checks.first { it.id == "SIN_ENCABEZADOS" }
        assertEquals(CheckSeverity.ADVERTENCIA, check.severity)
        assertTrue(check.message.contains("no tiene encabezados"))
    }

    @Test
    fun `el checker detecta los duplicados`() {
        val uno = materialDePrueba(id = "mat-1", title = "Apuntes-IA.pdf", texto = "El perceptrón es la unidad básica.")
        val otro = materialDePrueba(id = "mat-2", title = "Apuntes-IA (copia).pdf", texto = "EL PERCEPTRÓN ES LA UNIDAD BÁSICA.")

        val informe = CorpusQualityChecker.check(listOf(uno, otro), emptyList())

        val check = informe.checks.first { it.id == "DUPLICADO" }
        assertEquals(CheckSeverity.ADVERTENCIA, check.severity)
        assertEquals("mat-2", check.materialId)
        assertTrue(check.message.contains("Apuntes-IA (copia).pdf"))
        assertTrue(check.message.contains("Apuntes-IA.pdf"))
    }

    @Test
    fun `el checker detecta la mezcla de idiomas`() {
        val textoEnIngles = "The chapter describes the main concepts of the course and the way that " +
            "the students use them in the exercises. There is a chapter for each topic and the notes " +
            "include the examples of the lectures of the week with the theory and the practice of the class."
        val material = materialDePrueba(id = "mat-1", title = "Lecture-notes.pdf", texto = textoEnIngles)

        val informe = CorpusQualityChecker.check(listOf(material), emptyList())

        val check = informe.checks.first { it.id == "IDIOMA_INCONSISTENTE" }
        assertEquals(CheckSeverity.ADVERTENCIA, check.severity)
        assertTrue(check.message.contains("inglés"))
        assertTrue(check.message.contains("español"))
    }

    @Test
    fun `un material sano no tiene observaciones`() {
        val texto = "El perceptrón es la unidad básica de una red neuronal artificial: combina pesos " +
            "y un sesgo para aproximar una función cualquiera. La clase muestra cómo se entrena la " +
            "red y qué significa que aprenda de los ejemplos del tema, con la teoría y la práctica " +
            "del curso."
        val material = materialDePrueba(id = "mat-1", title = "Apuntes-IA.pdf", texto = texto)
        val chunks = listOf(CorpusChunk("mat-1", "Unidad 1", texto))

        val informe = CorpusQualityChecker.check(listOf(material), chunks)

        assertTrue(informe.checks.isEmpty(), "Checks inesperados: ${informe.checks}")
        assertEquals(100, informe.healthScore)
        assertTrue(informe.publishable)
    }

    @Test
    fun `el informe se parte por material para el catalogo`() {
        val sano = materialDePrueba(id = "mat-1", title = "Sano.pdf", texto = "El perceptrón es la unidad básica.")
        val roto = materialDePrueba(id = "mat-2", title = "Escaneado.pdf", texto = "")

        val informe = CorpusQualityChecker.check(listOf(sano, roto), emptyList())
        val delRoto = informe.forMaterial("mat-2")

        assertEquals(1, delRoto.blocking.size)
        assertTrue(delRoto.warnings.isEmpty())
        assertEquals(0, informe.forMaterial("mat-1").checks.size)
    }

    // ------------------------------------------------------------------
    // Catálogo: salud y uso
    // ------------------------------------------------------------------

    @Test
    fun `el catalogo calcula la salud y el uso`() {
        val material = materialDePrueba(id = "mat-1", title = "Apuntes-IA.pdf", texto = "El perceptrón es la unidad básica.")
        val mensajes = listOf(
            ChatMessage(
                id = "m-1",
                threadId = "catedra-1|hilo-1",
                role = "assistant",
                text = "El perceptrón es la unidad básica [1].",
                createdAt = 1_000L,
                sources = listOf("cátedra: Apuntes-IA.pdf"),
                citation = CitationStats(verified = 1, unsupported = 0, unknown = 0),
            ),
            ChatMessage(
                id = "m-2",
                threadId = "catedra-1|hilo-1",
                role = "assistant",
                text = "Respuesta sin respaldo [1].",
                createdAt = 2_000L,
                sources = listOf("cátedra: Apuntes-IA.pdf"),
                citation = CitationStats(verified = 0, unsupported = 1, unknown = 0),
            ),
        )
        val hilos = listOf(ChatThread(id = "catedra-1|hilo-1", courseId = "catedra-1", authorId = "alumno-1"))

        val catalogo = CorpusCatalogBuilder.build(
            courseId = "catedra-1",
            materials = listOf(material),
            manifest = manifestDeEjemplo(),
            quality = CorpusQualityChecker.check(listOf(material), emptyList()),
            chunkCountByMaterial = mapOf("mat-1" to 12),
            messages = mensajes,
            threads = hilos,
        )

        val fila = catalogo.entries.single()
        assertEquals(12, fila.chunkCount)
        assertEquals(2, fila.usage.citations)
        assertEquals(2, fila.usage.answers)
        assertEquals(1, fila.usage.students)
        assertEquals(1, fila.usage.unsupportedAnswers)
        assertEquals(2_000L, fila.usage.lastActivityAt)
        assertEquals(100, fila.health)
        assertEquals(100, catalogo.health)
        assertEquals(1, catalogo.summary.materials)
        assertEquals(12, catalogo.summary.chunks)
        assertEquals(0, catalogo.summary.unused)
    }

    // ------------------------------------------------------------------
    // Publicación
    // ------------------------------------------------------------------

    @Test
    fun `el modo inmediato publica en el acto`() = runTest {
        val fs = InMemoryFileSystem()
        val publisher = CorpusPublisher(fs, storeDePrueba(fs))
        val material = materialDePrueba(id = "mat-1", title = "Apuntes-IA.pdf", texto = "El perceptrón es la unidad básica.")

        val resultado = publisher.publish(
            courseId = "catedra-1",
            materials = listOf(material),
            mode = CorpusPublishMode.INMEDIATO,
            now = 1_700_000_000_000L,
        )

        assertTrue(resultado.published)
        assertFalse(resultado.pending)
        assertEquals(1L, resultado.version)
        assertEquals(1, resultado.manifest?.materials?.size)
        assertEquals(1L, storeDePrueba(fs).version("catedra-1"))
    }

    @Test
    fun `el modo al publicar catedra espera`() = runTest {
        val fs = InMemoryFileSystem()
        val publisher = CorpusPublisher(fs, storeDePrueba(fs))
        val material = materialDePrueba(id = "mat-1", title = "Apuntes-IA.pdf", texto = "El perceptrón es la unidad básica.")

        val pendiente = publisher.publish(
            courseId = "catedra-1",
            materials = listOf(material),
            mode = CorpusPublishMode.AL_PUBLICAR_CATEDRA,
            coursePublished = false,
            now = 1_700_000_000_000L,
        )

        assertFalse(pendiente.published)
        assertTrue(pendiente.pending)
        assertNull(storeDePrueba(fs).read("catedra-1"))

        // Cuando la cátedra se publica, sale todo junto.
        val publicado = publisher.publish(
            courseId = "catedra-1",
            materials = listOf(material),
            mode = CorpusPublishMode.AL_PUBLICAR_CATEDRA,
            coursePublished = true,
            now = 1_700_000_000_000L,
        )

        assertTrue(publicado.published)
        assertEquals(1L, publicado.version)
    }

    @Test
    fun `el modo diario espera hasta la hora del lote`() = runTest {
        val fs = InMemoryFileSystem()
        val publisher = CorpusPublisher(fs, storeDePrueba(fs))
        val material = materialDePrueba(id = "mat-1", title = "Apuntes-IA.pdf", texto = "El perceptrón es la unidad básica.")
        // 1.700.000.000.000 es el 14/11/2023 22:13:20 UTC.
        val antesDelLote = 1_700_000_000_000L
        val despuesDelLote = antesDelLote + 3_600_000L

        val pendiente = publisher.publish(
            courseId = "catedra-1",
            materials = listOf(material),
            mode = CorpusPublishMode.DIARIO,
            publishHour = 23,
            now = antesDelLote,
        )

        assertTrue(pendiente.pending)
        assertFalse(pendiente.published)

        val publicado = publisher.publish(
            courseId = "catedra-1",
            materials = listOf(material),
            mode = CorpusPublishMode.DIARIO,
            publishHour = 23,
            now = despuesDelLote,
        )

        assertTrue(publicado.published)
    }

    @Test
    fun `publicar sin cambios no sube una version nueva`() = runTest {
        val fs = InMemoryFileSystem()
        val publisher = CorpusPublisher(fs, storeDePrueba(fs))
        val material = materialDePrueba(id = "mat-1", title = "Apuntes-IA.pdf", texto = "El perceptrón es la unidad básica.")

        publisher.publish(
            courseId = "catedra-1",
            materials = listOf(material),
            now = 1_700_000_000_000L,
        )
        val repetido = publisher.publish(
            courseId = "catedra-1",
            materials = listOf(material),
            now = 1_700_000_100_000L,
        )

        assertFalse(repetido.published)
        assertTrue(repetido.unchanged)
        assertEquals(1L, repetido.version)
    }

    @Test
    fun `la version del material sube solo cuando cambia el texto`() = runTest {
        val fs = InMemoryFileSystem()
        val publisher = CorpusPublisher(fs, storeDePrueba(fs))
        val original = materialDePrueba(id = "mat-1", title = "Apuntes-IA.pdf", texto = "El perceptrón es la unidad básica.")

        publisher.publish(courseId = "catedra-1", materials = listOf(original), now = 1L)

        // Cambió el texto → sube la versión del material y la del manifiesto.
        val editado = original.copy(extractedText = "El perceptrón es la unidad básica de una red.")
        val trasEditar = publisher.publish(courseId = "catedra-1", materials = listOf(editado), now = 2L)
        assertTrue(trasEditar.published)
        assertEquals(2L, trasEditar.version)
        assertEquals(2, trasEditar.manifest?.materials?.single()?.version)

        // Cambió el título → no sube la versión del material (no cambia el texto).
        val renombrado = editado.copy(title = "Apuntes-IA (v2).pdf")
        val trasRenombrar = publisher.publish(courseId = "catedra-1", materials = listOf(renombrado), now = 3L)
        assertTrue(trasRenombrar.published)
        assertEquals(2, trasRenombrar.manifest?.materials?.single()?.version)
    }

    // ------------------------------------------------------------------
    // Escritura atómica
    // ------------------------------------------------------------------

    @Test
    fun `la escritura es atomica`() = runTest {
        val fs = InMemoryFileSystem()
        val store = storeDePrueba(fs)
        val manifest = manifestDeEjemplo()

        store.write(manifest)

        // 1. El archivo final existe y es un manifiesto válido.
        val final = store.manifestPath("catedra-1")
        assertTrue(fs.exists(final))
        assertEquals(manifest, store.read("catedra-1"))

        // 2. No queda ningún temporal sucio.
        assertTrue(
            fs.listFiles(store.dir("catedra-1")).none { it.name.endsWith(".tmp") },
            "Quedó un temporal: ${fs.listFiles(store.dir("catedra-1"))}",
        )

        // 3. Si la escritura falla, el manifiesto anterior queda **intacto**:
        //    nunca queda un archivo a medias que el cliente no pueda leer.
        fs.failOnWrite.add(store.tempPath("catedra-1").value)
        var fallo = false
        try {
            store.write(manifest.copy(version = 8L, generatedAt = 999L))
        } catch (_: Exception) {
            fallo = true
        }
        assertTrue(fallo, "La escritura tenía que fallar")
        val sobreviviente = store.read("catedra-1")
        assertNotNull(sobreviviente)
        assertEquals(7L, sobreviviente.version)
        assertEquals(manifest, sobreviviente)
    }

    // ------------------------------------------------------------------
    // Ayudas
    // ------------------------------------------------------------------

    private fun storeDePrueba(fs: InMemoryFileSystem): CorpusStore =
        CorpusStore(fs) { FilePath("/data/courses") }

    private fun manifestDeEjemplo(): CorpusManifest = CorpusManifest(
        courseId = "catedra-1",
        version = 7L,
        generatedAt = 1_700_000_000_000L,
        chunking = ChunkingConfig(chunkSize = 900, chunkOverlap = 120),
        materials = listOf(
            ManifestEntry(
                materialId = "mat-1",
                title = "Apuntes-IA.pdf",
                kind = MaterialKind.DOCUMENT,
                sha256 = "hash-1",
                textBytes = 1200,
                version = 3,
                updatedAt = 1_600_000_000_000L,
                visible = true,
                unitIds = listOf("unidad-1", "unidad-2"),
            ),
            ManifestEntry(
                materialId = "mat-2",
                title = "Diapositivas",
                kind = MaterialKind.SLIDES,
                sha256 = "hash-2",
                textBytes = 340,
                version = 1,
                updatedAt = 1_600_000_000_000L,
                visible = false,
            ),
        ),
    )

    private fun entrada(
        materialId: String,
        title: String,
        sha256: String,
        visible: Boolean = true,
    ) = ManifestEntry(
        materialId = materialId,
        title = title,
        kind = MaterialKind.DOCUMENT,
        sha256 = sha256,
        textBytes = 100,
        version = 1,
        updatedAt = 1L,
        visible = visible,
    )

    private fun materialDePrueba(
        id: String,
        title: String,
        texto: String,
        visible: Boolean = true,
    ) = Material(
        id = id,
        courseId = "catedra-1",
        title = title,
        kind = MaterialKind.DOCUMENT,
        path = "files/$title",
        sizeBytes = texto.length.toLong(),
        uploadedBy = "doc-1",
        uploadedAt = 1_600_000_000_000L,
        visible = visible,
        extractedText = texto,
    )
}
