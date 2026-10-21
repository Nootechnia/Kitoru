package kitoru.platform

import kotlinx.coroutines.CancellationException

/**
 * Piezas compartidas por los notificadores de escritorio y de Android.
 *
 * `jvmCommonMain` es el source set que comparten los dos targets JVM, así que
 * lo que vive acá tiene que ser Java común: **nada de `java.awt`** (Android
 * no lo tiene en su `android.jar`) ni nada de `android.*`. Por eso el
 * notificador de escritorio, que sí usa AWT, vive en `desktopMain`.
 */
internal object JvmNotifierSupport {

    /**
     * Clave numérica estable para una notificación.
     *
     * El sistema operativo identifica las notificaciones con un entero, pero
     * Kitoru las identifica con un id de texto (`aviso:7f3c`). Este hash
     * traduce uno en otro de forma **estable**: la misma notificación siempre
     * cae en la misma ranura del SO, que es lo que hace que publicar dos
     * veces la misma la *reemplace* en vez de duplicarla.
     */
    fun claveEstable(notificationId: String): Int = notificationId.hashCode()

    /**
     * Ejecuta una acción del sistema operativo sin propagar su fallo.
     *
     * El SO es un extra de la bandeja interna: si la bandeja del sistema no
     * existe, si el permiso está denegado o si el sistema operativo falla, la
     * app sigue funcionando y el aviso queda en la bandeja interna.
     */
    fun <T> conRespaldo(bloque: () -> T): T? = try {
        bloque()
    } catch (e: CancellationException) {
        // La cancelación se propaga: no es un error del sistema operativo.
        throw e
    } catch (e: Exception) {
        null
    }
}
