package kitoru.domain

import kitoru.platform.FilePath
import kitoru.platform.KitoruPaths
import kitoru.platform.LocalNotifier
import kitoru.testing.InMemoryFileSystem
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests del centro de notificaciones.
 *
 * Lo que se garantiza acá es lo que el usuario nunca ve romper:
 *  - la deduplicación (el mismo evento no avisa dos veces),
 *  - el contador de no leídas (derivado del store, no un contador paralelo),
 *  - la persistencia (la bandeja interna y el ledger sobreviven al reinicio),
 *  - y que **el sistema operativo es un extra**: si falla, el aviso igual
 *    queda guardado y se ve dentro de la app.
 */
class NotificationCenterTest {

    private val fs = InMemoryFileSystem()
    private val notificador = NotificadorFalso()
    private var dataDirAnterior: FilePath = KitoruPaths.dataDir

    @BeforeTest
    fun preparar() {
        // La bandeja interna vive en `config/notifications.json`, debajo de la
        // carpeta de datos: se apunta a una carpeta de prueba para no tocar
        // la del usuario.
        dataDirAnterior = KitoruPaths.dataDir
        KitoruPaths.setDataDir(FilePath("/datos-kitoru"))
    }

    @AfterTest
    fun restaurar() {
        KitoruPaths.setDataDir(dataDirAnterior)
    }

    // -----------------------------------------------------------------------
    // Utilidades
    // -----------------------------------------------------------------------

    private fun centro() = NotificationCenter(fs, notificador, clock = { 5_000L })

    private fun notificacion(
        id: String = "aviso:1",
        courseId: String = "catedra-1",
        titulo: String = "La clase del martes se cancela",
        creado: Long = 1_000L,
    ) = Notification(
        id = id,
        courseId = courseId,
        kind = NotificationKind.ANNOUNCEMENT,
        title = titulo,
        body = "Se cancela la clase del martes 27/10.",
        createdAt = creado,
        deepLink = DeepLink(DeepLinkKind.ANNOUNCEMENT, courseId, id),
    )

    // -----------------------------------------------------------------------
    // 1. La bandeja interna
    // -----------------------------------------------------------------------

    @Test
    fun `publicar guarda la notificacion en la bandeja interna`() = runTest {
        val centro = centro()

        assertTrue(centro.publish(notificacion()))

        assertEquals(1, centro.list().size)
        assertEquals(1, centro.unreadCount())
        assertNotNull(
            fs.peek("/datos-kitoru/config/notifications.json"),
            "La notificación no se guardó en config/notifications.json.",
        )
    }

    @Test
    fun `la bandeja interna no depende del sistema operativo`() = runTest {
        // Sin bandeja del sistema (o sin permiso) el aviso se ve igual, pero
        // adentro de la app.
        notificador.habilitado = false
        val centro = centro()

        assertTrue(centro.publish(notificacion()))

        assertEquals(1, centro.list().size, "El aviso tiene que verse dentro de la app.")
        assertTrue(notificador.mostradas.isEmpty(), "El SO no avisó porque está deshabilitado.")
    }

    @Test
    fun `las notificaciones se ordenan de la mas nueva a la mas vieja`() = runTest {
        val centro = centro()
        centro.publish(notificacion(id = "aviso:viejo", creado = 1_000L))
        centro.publish(notificacion(id = "aviso:nuevo", creado = 9_000L))

        assertEquals(listOf("aviso:nuevo", "aviso:viejo"), centro.list().map { it.id })
    }

    // -----------------------------------------------------------------------
    // 2. La deduplicación
    // -----------------------------------------------------------------------

    @Test
    fun `el mismo id dos veces publica una sola notificacion`() = runTest {
        val centro = centro()
        val aviso = notificacion()

        assertTrue(centro.publish(aviso))
        assertFalse(centro.publish(aviso), "El segundo aviso era el mismo evento.")

        assertEquals(1, centro.list().size)
        assertEquals(1, notificador.mostradas.size, "El SO no tiene que ver dos globos.")
    }

    @Test
    fun `el ledger sobrevive a que la app se reinicie`() = runTest {
        val centro = centro()
        centro.publish(notificacion())

        // "App nueva": otro centro sobre el mismo sistema de archivos.
        val otroCentro = centro()

        assertFalse(otroCentro.publish(notificacion()), "El ledger no dejó pasar el evento.")
        assertEquals(1, otroCentro.list().size)
        assertEquals(1, notificador.mostradas.size)
    }

    @Test
    fun `descartar una notificacion la quita de la lista y no vuelve`() = runTest {
        val centro = centro()
        centro.publish(notificacion())

        assertTrue(centro.dismiss("aviso:1"))
        assertTrue(centro.list().isEmpty())
        assertFalse(centro.publish(notificacion()), "Un aviso descartado no vuelve a avisar.")
    }

    @Test
    fun `vaciar la bandeja deja el ledger intacto`() = runTest {
        val centro = centro()
        centro.publish(notificacion())
        centro.publish(notificacion(id = "aviso:2"))

        assertEquals(2, centro.clear())
        assertTrue(centro.list().isEmpty())
        assertFalse(centro.publish(notificacion()), "Lo ya avisado no se avisa otra vez.")
    }

    // -----------------------------------------------------------------------
    // 3. El contador de no leídas
    // -----------------------------------------------------------------------

    @Test
    fun `el contador de no leidas baja al marcar una como leida`() = runTest {
        val centro = centro()
        centro.publish(notificacion(id = "aviso:1"))
        centro.publish(notificacion(id = "aviso:2"))
        assertEquals(2, centro.unreadCount())

        assertTrue(centro.markRead("aviso:1"))

        assertEquals(1, centro.unreadCount())
        assertTrue(centro.list().first { it.id == "aviso:1" }.read)
        assertEquals(listOf("aviso:1"), notificador.canceladas, "La del SO se cancela al leerla.")
    }

    @Test
    fun `marcar todo como leido deja el contador en cero`() = runTest {
        val centro = centro()
        centro.publish(notificacion(id = "aviso:1"))
        centro.publish(notificacion(id = "aviso:2"))

        assertEquals(2, centro.markAllRead())

        assertEquals(0, centro.unreadCount())
        assertTrue(centro.list().all { it.read })
    }

    @Test
    fun `marcar todo como leido de una catedra no toca las otras`() = runTest {
        val centro = centro()
        centro.publish(notificacion(id = "aviso:1", courseId = "catedra-1"))
        centro.publish(notificacion(id = "aviso:2", courseId = "catedra-2"))

        assertEquals(1, centro.markAllRead("catedra-1"))

        assertEquals(1, centro.unreadCount())
        assertEquals(1, centro.unreadCount("catedra-2"))
        assertEquals(0, centro.unreadCount("catedra-1"))
    }

    @Test
    fun `el contador de no leidas es el de la lista`() = runTest {
        val centro = centro()
        centro.publish(notificacion(id = "aviso:1"))
        centro.publish(notificacion(id = "aviso:2"))
        centro.markRead("aviso:1")

        // No hay ningún contador almacenado: sale de la lista.
        assertEquals(centro.list().count { !it.read }, centro.unreadCount())
    }

    // -----------------------------------------------------------------------
    // 4. La persistencia
    // -----------------------------------------------------------------------

    @Test
    fun `la bandeja interna sobrevive a que la app se reinicie`() = runTest {
        val centro = centro()
        centro.publish(notificacion())

        val otroCentro = centro()

        assertEquals(1, otroCentro.list().size)
        assertEquals(1, otroCentro.unreadCount())
        assertEquals("La clase del martes se cancela", otroCentro.list().single().title)
    }

    @Test
    fun `el estado de lectura sobrevive a que la app se reinicie`() = runTest {
        val centro = centro()
        centro.publish(notificacion())
        centro.markRead("aviso:1")

        val otroCentro = centro()

        assertEquals(0, otroCentro.unreadCount())
        assertTrue(otroCentro.list().single().read)
    }

    @Test
    fun `el archivo guarda la lista y el ledger`() = runTest {
        val centro = centro()
        centro.publish(notificacion())

        val json = assertNotNull(fs.peek("/datos-kitoru/config/notifications.json"))

        assertContains(json, "aviso:1", message = "La lista no está en el archivo.")
        assertContains(json, "fired", message = "El ledger no está en el archivo.")
    }

    // -----------------------------------------------------------------------
    // 5. El sistema operativo es un extra
    // -----------------------------------------------------------------------

    @Test
    fun `un notificador que falla no rompe el guardado`() = runTest {
        notificador.fallaAlMostrar = true
        val centro = centro()

        assertTrue(centro.publish(notificacion()))

        assertEquals(1, centro.list().size, "El aviso tiene que quedar guardado igual.")
        assertEquals(1, centro.unreadCount())
        assertNotNull(fs.peek("/datos-kitoru/config/notifications.json"))
    }

    @Test
    fun `un notificador que falla no rompe la deduplicacion`() = runTest {
        notificador.fallaAlMostrar = true
        val centro = centro()
        centro.publish(notificacion())

        // El ledger se escribió antes de avisarle al SO: el segundo intento
        // se deduplica igual.
        assertFalse(centro.publish(notificacion()))
        assertEquals(1, centro.list().size)
    }

    @Test
    fun `un notificador que falla al marcar como leida no rompe el guardado`() = runTest {
        val centro = centro()
        centro.publish(notificacion())
        notificador.fallaAlMostrar = true

        assertTrue(centro.markRead("aviso:1"))

        assertEquals(0, centro.unreadCount())
    }

    // -----------------------------------------------------------------------
    // 6. El deep link
    // -----------------------------------------------------------------------

    @Test
    fun `el deep link de un hilo se serializa con la catedra`() {
        val link = DeepLink(DeepLinkKind.CHAT_THREAD, courseId = "catedra-1", targetId = "hilo-7")

        assertEquals("kitoru://course/catedra-1/chat/hilo-7", link.toUri())
    }

    @Test
    fun `el deep link se vuelve a leer desde la uri`() {
        val link = DeepLink(DeepLinkKind.CHAT_THREAD, courseId = "catedra-1", targetId = "hilo-7")

        assertEquals(link, DeepLink.parse(link.toUri()))
    }

    @Test
    fun `el deep link de un aviso sin catedra se vuelve a leer`() {
        val link = DeepLink(DeepLinkKind.ANNOUNCEMENT, targetId = "aviso-9")

        assertEquals(link, DeepLink.parse(link.toUri()))
    }

    @Test
    fun `una uri que no es de kitoru no se parsea`() {
        assertNull(DeepLink.parse("https://ejemplo.com/aviso"))
        assertNull(DeepLink.parse("kitoru://destino-que-no-existe"))
    }

    /**
     * Doble de la costura de plataforma: no toca el sistema operativo y
     * deja ver qué se le pidió.
     */
    private class NotificadorFalso : LocalNotifier {
        val mostradas = mutableListOf<Notification>()
        val canceladas = mutableListOf<String>()
        var habilitado = true
        var fallaAlMostrar = false

        override fun areEnabled(): Boolean = habilitado

        override fun needsPermission(): Boolean = false

        override fun show(notification: Notification) {
            if (fallaAlMostrar) error("El sistema operativo no puede mostrar la notificación.")
            if (!habilitado) return
            mostradas += notification
        }

        override fun cancel(id: String) {
            canceladas += id
        }

        override fun cancelAll(courseId: String?) {
            canceladas += "todas:${courseId ?: "*"}"
        }

        override val supportsTapToOpen: Boolean get() = false
    }
}
