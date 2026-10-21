package kitoru.platform

import kitoru.domain.Notification
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * Notificador de escritorio: el globo de la bandeja del sistema.
 *
 * Es **best effort**:
 *
 *  - en Windows y macOS `java.awt.SystemTray` funciona y el globo aparece;
 *  - en **Linux no funciona siempre**: AWT habla X11, así que en **Wayland**
 *    (que es el default desde Fedora 40 y Ubuntu 24.10) `SystemTray.isSupported()`
 *    devuelve `false` o el ícono no aparece. Tampoco hay bandeja en un
 *    escritorio pelado, en una sesión remota o en un servidor sin pantalla.
 *
 * Cuando no hay bandeja, `areEnabled()` devuelve `false` y [show] es un no-op
 * silencioso: **no es un error**. La notificación sigue estando en la bandeja
 * interna de la app (el [kitoru.domain.NotificationCenter]), que es la parte
 * que siempre funciona.
 *
 * Ojo con `java.awt.SystemTray`: está **deprecado para remoción en Java SE
 * 26**. Por eso todo el uso de AWT queda encerrado en este archivo, para
 * poder sacarlo (o reemplazarlo por D-Bus, el estándar de Linux) sin tocar
 * nada más.
 */
class DesktopLocalNotifier : LocalNotifier {

    /** El ícono de la bandeja, o `null` si este sistema no tiene bandeja. */
    private val icon: TrayIcon? = crearIcono()

    override fun areEnabled(): Boolean = icon != null

    /** En escritorio no hay permiso que pedir: no hay sistema operativo que lo pida. */
    override fun needsPermission(): Boolean = false

    override fun show(notification: Notification) {
        val icon = icon ?: return
        // El globo muestra un par de líneas: el cuerpo se recorta.
        JvmNotifierSupport.conRespaldo {
            icon.displayMessage(
                notification.title,
                notification.body.take(140),
                TrayIcon.MessageType.INFO,
            )
        }
    }

    override fun cancel(id: String) {
        // El globo de AWT no se puede cerrar programáticamente: se va solo.
    }

    override fun cancelAll(courseId: String?) {
        // Ídem: la bandeja interna es la que lleva la cuenta de lo leído.
    }

    /**
     * En escritorio **no** se puede abrir la app al tocar la notificación:
     * el globo de AWT no es clickeable (`TrayIcon` no tiene callback del
     * globo). Por eso el aviso vive dentro de la app.
     */
    override val supportsTapToOpen: Boolean get() = false

    override val unavailableReason: String
        get() = if (icon != null) {
            ""
        } else {
            "Este sistema no tiene bandeja de notificaciones: los avisos se ven " +
                "dentro de la app (el ícono de la campana, arriba a la izquierda)."
        }

    /**
     * Crea el ícono de la bandeja, o `null` si este sistema no la tiene.
     *
     * Todo el AWT queda acá adentro: si la API desaparece, se borra este
     * archivo y el resto del subsistema no se entera.
     */
    private fun crearIcono(): TrayIcon? {
        return try {
            if (!SystemTray.isSupported()) return null
            val icon = TrayIcon(imagenKitoru(), "Kitoru")
            icon.isImageAutoSize = true
            SystemTray.getSystemTray().add(icon)
            icon
        } catch (t: Throwable) {
            // Un escritorio roto (sin pantalla, sin bandeja) no puede romper la app.
            null
        }
    }

    /** El ícono de la app dibujado en memoria: no depende de un recurso. */
    private fun imagenKitoru(): BufferedImage {
        val size = 16
        val imagen = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = imagen.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0x2E, 0x6B, 0xE6)
            g.fillRoundRect(0, 0, size, size, 6, 6)
            g.color = Color.WHITE
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
            val metrics = g.fontMetrics
            val x = (size - metrics.stringWidth("K")) / 2
            val y = (size - metrics.height) / 2 + metrics.ascent
            g.drawString("K", x, y)
        } finally {
            g.dispose()
        }
        return imagen
    }
}

/** Notificador de escritorio. */
actual fun createLocalNotifier(): LocalNotifier = DesktopLocalNotifier()
