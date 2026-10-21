package kitoru.platform

import kitoru.domain.Notification

/**
 * Publica notificaciones en el **sistema operativo**.
 *
 * Es una costura `expect/actual`: la app no sabe en qué plataforma está. El
 * patrón es el mismo que [FileSystem] y que el `FileSaver` de la UI.
 *
 * Reglas de la costura:
 *  - `show()` **nunca** lanza: si el SO no puede notificar, es un no-op
 *    silencioso. El error se reporta, no se propaga.
 *  - `show()` es **idempotente por id**: publicar dos veces el mismo id
 *    *reemplaza* la notificación, no la duplica.
 *  - la app **nunca** llama a `show()` sin pasar por el
 *    [kitoru.domain.NotificationCenter], que es quien deduplica.
 *
 * Y la regla de oro del subsistema:
 *
 * > **Si el sistema operativo no notifica, la app igual muestra la novedad.**
 *
 * Por eso [areEnabled] en `false` no es un error: sólo cambia *dónde* se ve
 * la notificación (dentro de la app en vez de en el SO).
 */
interface LocalNotifier {

    /** ¿El sistema operativo permite notificar? (permiso + ajustes del SO) */
    fun areEnabled(): Boolean

    /**
     * ¿Falta pedir permiso al usuario?
     *
     * `true` sólo en Android 13+ (`POST_NOTIFICATIONS`). En el resto, `false`.
     */
    fun needsPermission(): Boolean = false

    /**
     * Publica (o reemplaza) la notificación.
     *
     * En Android: `NotificationManager.notify`.
     * En escritorio: el globo de la bandeja del sistema (o no-op si no hay
     * bandeja).
     */
    fun show(notification: Notification)

    /** Cierra la notificación con ese id. */
    fun cancel(id: String)

    /**
     * Cierra todas las notificaciones (o las de una cátedra).
     *
     * Se usa cuando la app "absorbe" las notificaciones: si el usuario ya
     * las ve en el centro interno, el SO no debe seguir insistiendo.
     */
    fun cancelAll(courseId: String? = null)

    /**
     * Abre los ajustes de notificaciones del sistema operativo.
     *
     * Se usa cuando el usuario rechazó el permiso y hay que mandarlo a
     * habilitarlo a mano (en Android 13+, el diálogo del sistema no vuelve a
     * aparecer después de dos rechazos).
     */
    fun openSystemSettings() {}

    /**
     * ¿Se puede abrir la app al tocar la notificación?
     *
     * - Android: `true` (hay `PendingIntent`).
     * - Escritorio: **`false`**. El globo de la bandeja del sistema no es
     *   clickeable (`TrayIcon` no tiene callback del globo), así que en
     *   escritorio la notificación vive **dentro de la app**.
     */
    val supportsTapToOpen: Boolean get() = true

    /**
     * ¿Por qué no se puede notificar? (para explicárselo al usuario).
     * Vacío = sí se puede.
     */
    val unavailableReason: String get() = ""
}

/** La implementación del notificador de la plataforma. */
expect fun createLocalNotifier(): LocalNotifier
