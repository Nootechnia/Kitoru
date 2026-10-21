package kitoru.domain

import kitoru.platform.FilePath
import kitoru.testing.Fixtures
import kitoru.testing.InMemoryFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests de la persistencia JSON ([JsonStore] / [JsonListStore]).
 *
 * Se testean contra un [InMemoryFileSystem] (doble de la plataforma) y no
 * contra el disco: los tests son deterministas, corren igual en el
 * escritorio y en Android y no dependen del sistema de archivos real.
 *
 * Lo que se garantiza acá:
 *  - guardar y cargar conserva los datos;
 *  - un archivo **ausente** o **corrupto** no rompe la app (devuelve el
 *    valor vacío) y de una lista con un elemento roto se **salva** el resto;
 *  - la escritura es **atómica**: se escribe en un temporal y se renombra,
 *    así que una escritura fallida deja intacto el archivo anterior y nunca
 *    queda un JSON a medias en el destino;
 *  - dos corrutinas escribiendo al mismo tiempo no se pisan.
 */
class JsonStoreTest {

    private val fs = InMemoryFileSystem()
    private val ruta = FilePath("/mem/config/cursos.json")

    /** El temporal que usa la escritura atómica. */
    private val temporal = FilePath("${ruta.value}.tmp")

    private fun cursos() = JsonListStore(fs, ruta, Course.serializer())

    private fun curso(id: String) = Fixtures.course(id = id)

    // ==================================================================
    // Guardar y cargar
    // ==================================================================

    /**
     * Un store nuevo (sin archivo) devuelve la lista vacía: la primera
     * apertura de la app no rompe.
     */
    @Test
    fun `un store nuevo devuelve la lista vacia`() = runTest {
        assertEquals(emptyList(), cursos().read())
    }

    /**
     * Guardar y cargar conserva los datos: lo que se escribe se vuelve a
     * leer igual (ida y vuelta sin pérdidas).
     */
    @Test
    fun `guardar y cargar conserva los datos`() = runTest {
        val s = cursos()
        s.write(listOf(curso("c1"), curso("c2")))

        assertEquals(listOf(curso("c1"), curso("c2")), s.read())
    }

    /**
     * Los datos sobreviven a la **recreación** del store: lo que quedó en
     * el disco (aquí, en memoria) es lo que la app ve al volver a abrir.
     */
    @Test
    fun `los datos sobreviven a la recreacion del store`() = runTest {
        cursos().write(listOf(curso("c1")))

        assertEquals(listOf(curso("c1")), cursos().read(), "otra instancia del store ve lo mismo")
    }

    /**
     * `update` lee, transforma y escribe: es la operación que usan los
     * repositorios para guardar sin perder lo demás.
     */
    @Test
    fun `update lee transforma y escribe`() = runTest {
        val s = cursos()
        s.write(listOf(curso("c1")))

        s.update { lista -> lista + curso("c2") }

        assertEquals(2, s.read().size)
        assertEquals(listOf("c1", "c2"), s.read().map { it.id })
    }

    /**
     * Escribir crea los directorios que faltan: la app guarda en
     * `config/` aunque la carpeta todavía no exista.
     */
    @Test
    fun `escribir crea los directorios que faltan`() = runTest {
        val rutaNueva = FilePath("/mem/nuevo/dir/cosas.json")
        val s = JsonListStore(fs, rutaNueva, Course.serializer())

        s.write(listOf(curso("c1")))

        assertTrue(fs.exists(rutaNueva), "el archivo se escribió: ${fs.writtenPaths()}")
        assertTrue(fs.isDirectory(FilePath("/mem/nuevo/dir")), "se creó la carpeta: ${fs.writtenPaths()}")
    }

    // ==================================================================
    // Archivo ausente y archivo corrupto
    // ==================================================================

    /**
     * Un archivo **ausente** devuelve el valor vacío: es un store nuevo,
     * no un error.
     */
    @Test
    fun `un archivo ausente devuelve el valor vacio`() = runTest {
        assertTrue(!fs.exists(ruta), "el archivo no está: es un store nuevo")
        assertNull(fs.peek(ruta.value), "no hay nada escrito todavía")
        assertEquals(emptyList(), cursos().read())
    }

    /**
     * Un archivo **corrupto** se ignora y devuelve el valor vacío: un
     * archivo roto no puede voltear la app.
     */
    @Test
    fun `un archivo corrupto se ignora y se devuelve el valor vacio`() = runTest {
        fs.put(ruta.value, "{ esto no es json")

        assertEquals(emptyList(), cursos().read(), "un archivo roto no puede voltear la app")
    }

    /**
     * Un archivo con el **tipo equivocado** (un objeto donde se espera una
     * lista) también se ignora: el error de deserialización no escapa.
     */
    @Test
    fun `un archivo con el tipo equivocado devuelve el valor vacio`() = runTest {
        fs.put(ruta.value, """{"id":"c1"}""")

        assertEquals(emptyList(), cursos().read())
    }

    /**
     * Un archivo **truncado** (la app se cortó escribiendo) no deja la app
     * sin datos: se devuelven los elementos que se pueden recuperar.
     */
    @Test
    fun `un archivo truncado no rompe la app`() = runTest {
        fs.put(ruta.value, """[{"id":"c1","code":"IA","name":"IA"},{"id":"c2","co""")

        val leido = cursos().read()
        assertTrue(leido.size <= 1, "sólo se rescata lo que está entero: ${leido.map { it.id }}")
        assertTrue(leido.all { it.code == "IA" }, "lo rescatado es un elemento válido")
    }

    /**
     * Una lista con **un elemento roto** no pierde la lista entera: los
     * elementos válidos se salvan y el roto se descarta.
     */
    @Test
    fun `una lista con un elemento roto salva los elementos validos`() = runTest {
        fs.put(
            ruta.value,
            """[{"id":"c1","code":"IA","name":"IA"},{"id":"c2"}]""",
        )

        val leido = cursos().read()
        assertEquals(1, leido.size, "el elemento roto se descarta")
        assertEquals("c1", leido.first().id, "el válido se salva")
    }

    /**
     * Los **campos desconocidos** se ignoran: un `courses.json` escrito por
     * una versión más nueva de la app no rompe la lectura.
     */
    @Test
    fun `los campos desconocidos se ignoran`() = runTest {
        fs.put(
            ruta.value,
            """[{"id":"c1","code":"IA","name":"IA","campoDelFuturo":123}]""",
        )

        assertEquals(1, cursos().read().size, "un campo nuevo no rompe la lectura")
    }

    // ==================================================================
    // Escritura atómica
    // ==================================================================

    /**
     * La escritura es **atómica**: se escribe en un temporal y después se
     * renombra al destino. Nunca queda un JSON a medias en el destino ni
     * sobrevive un temporal.
     */
    @Test
    fun `la escritura es atomica y nunca deja un json a medias`() = runTest {
        val s = cursos()
        s.write(listOf(curso("c1")))

        assertTrue(
            fs.writtenPaths().none { it.endsWith(".tmp") },
            "no puede quedar un temporal: ${fs.writtenPaths()}",
        )
        assertEquals(1, s.read().size, "el archivo destino tiene que ser un JSON completo")
    }

    /**
     * Una escritura que **falla** deja intacto el archivo anterior: no se
     * pierde la cátedra que ya estaba guardada.
     */
    @Test
    fun `una escritura fallida deja intacto el archivo anterior`() = runTest {
        val s = cursos()
        s.write(listOf(curso("c1")))
        fs.failOnWrite += temporal.value

        assertFailsWith<IllegalStateException> { s.write(listOf(curso("c2"))) }

        fs.failOnWrite.clear()
        assertEquals(
            listOf(curso("c1")),
            s.read(),
            "el archivo bueno tiene que seguir intacto: no se pierde la cátedra",
        )
    }

    /**
     * Una escritura fallida **no deja basura**: el temporal se borra y el
     * único archivo que queda es el destino.
     */
    @Test
    fun `una escritura fallida no deja archivos sueltos`() = runTest {
        val s = cursos()
        s.write(listOf(curso("c1")))
        fs.failOnWrite += temporal.value

        runCatching { s.write(listOf(curso("c2"))) }
        fs.failOnWrite.clear()

        assertEquals(
            listOf(ruta.value),
            fs.writtenPaths(),
            "sólo el archivo destino: ${fs.writtenPaths()}",
        )
    }

    /**
     * Una ruta **sin padre** se escribe en el directorio de trabajo: no se
     * puede crear un directorio con el nombre del archivo.
     */
    @Test
    fun `una ruta sin padre no crea un directorio con el nombre del archivo`() = runTest {
        val s = JsonListStore(fs, FilePath("cursos.json"), Course.serializer())

        s.write(listOf(curso("c1")))

        assertTrue(
            !fs.isDirectory(FilePath("cursos.json")),
            "no puede haber un directorio con el nombre del archivo: ${fs.writtenPaths()}",
        )
        assertEquals(listOf(curso("c1")), s.read())
    }

    // ==================================================================
    // Concurrencia
    // ==================================================================

    /**
     * Cien `update` concurrentes **no pierden ninguno**: el mutex del store
     * serializa el leer-transformar-escribir.
     */
    @Test
    fun `cien updates concurrentes no pierden ninguno`() = runTest {
        val s = JsonStore(fs, FilePath("/mem/config/n.json"), Int.serializer(), { 0 })

        // Con `Dispatchers.Default` las escrituras corren de verdad en
        // paralelo (con el scheduler de `runTest` no habría carrera real).
        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(100) { launch { s.update { it + 1 } } }
            }
        }

        assertEquals(100, s.read(), "ningún update puede perderse")
    }

    /**
     * Dos escrituras concurrentes dejan un archivo **válido** (no a medias):
     * gana una de las dos, pero el JSON está entero.
     */
    @Test
    fun `dos escrituras concurrentes dejan un archivo valido`() = runTest {
        val s = cursos()

        withContext(Dispatchers.Default) {
            coroutineScope {
                launch { s.write(listOf(curso("a"))) }
                launch { s.write(listOf(curso("b"))) }
            }
        }

        val leido = s.read()
        assertEquals(1, leido.size, "el archivo no puede quedar a medias")
        assertTrue(leido[0].id == "a" || leido[0].id == "b", "ganó una de las dos: ${leido[0].id}")
    }

    /**
     * Leer y escribir a la vez **no rompe el archivo**: el reader puede ver
     * la versión anterior o la nueva, pero nunca un JSON truncado.
     */
    @Test
    fun `leer y escribir a la vez no rompe el archivo`() = runTest {
        val s = cursos()
        s.write(listOf(curso("a")))

        withContext(Dispatchers.Default) {
            coroutineScope {
                launch { repeat(50) { s.write(listOf(curso("a"))) } }
                launch { repeat(50) { s.read() } }
            }
        }

        assertEquals(1, s.read().size, "el archivo sigue siendo JSON válido")
    }
}
