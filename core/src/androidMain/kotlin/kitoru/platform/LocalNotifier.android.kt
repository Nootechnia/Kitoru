package kitoru.platform

import android.Manifest
import android.app.Notification as AndroidNotification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import kitoru.domain.Notification
import kitoru.domain.NotificationKind
import kitoru.domain.NotificationPriority

/**
 * Contexto de la app Android para el notificador.
 *
 * `:core` es una biblioteca sin `Activity`: la app inyecta el contexto al
 * arrancar (igual que hace con [KitoruPaths.setDataDir]) y el notificador lo
 * usa. Si no se inyectó, el notificador no hace nada: es mejor no avisar
 * que romper la app.
 */
object AndroidNotifierHost {
    private var appContext: Context? = null
    private var activityClassName: String = ""

    /** La app inyecta el contexto de la aplicación al arrancar. */
    fun init(context: Context, activityClassName: String = "") {
        appContext = context.applicationContext
        this.activityClassName = activityClassName
    }

    /** El contexto de la app, o `null` si todavía no se inicializó. */
    val context: Context? get() = appContext

    /** La `Activity` principal, si la app la declaró. */
    val activityClassName: String get() = activityClassName
}

/**
 * Notificador de Android: `NotificationManager` + canales.
 *
 * Se usa la API del framework (`android.app.Notification.Builder`) y no
 * `NotificationCompat` a propósito: así `:core` no necesita una dependencia
 * nueva de AndroidX, y el camino es el mismo en la API 24 (mínimo del
 * proyecto) y en la 35.
 *
 * Lo que **no** hace esta clase: pedir el permiso `POST_NOTIFICATIONS`. Pedir
 * un permiso necesita una `Activity` y `:core` es una biblioteca sin ella;
 * por eso expone [needsPermission] y la app lo pide en contexto.
 */
class AndroidLocalNotifier : LocalNotifier {

    private val context: Context? get() = AndroidNotifierHost.context

    private val manager: NotificationManager?
        get() = context?.getSystemService(NotificationManager::class.java)

    override fun areEnabled(): Boolean =
        manager?.areNotificationsEnabled() == true && !needsPermission()

    /**
     * En Android 13+ (`API 33`) el permiso `POST_NOTIFICATIONS` se pide en
     * tiempo de ejecución. Antes de eso el permiso era implícito y no hay
     * nada que pedir.
     */
    override fun needsPermission(): Boolean {
        val context = context ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    override fun show(notification: Notification) {
        val context = context ?: return
        val manager = manager ?: return
        if (!areEnabled()) return
        ensureChannels(manager)
        val androidNotification = construir(context, notification)
        // El id numérico sale del id estable: publicar dos veces la misma
        // notificación la reemplaza en vez de duplicarla.
        manager.notify(JvmNotifierSupport.claveEstable(notification.id), androidNotification)
        // Se recuerda el id por cátedra para poder cancelarlas en grupo:
        // `NotificationManager` no tiene "cancelar por cátedra".
        published.getOrPut(notification.courseId) { mutableSetOf() } += notification.id
    }

    override fun cancel(id: String) {
        manager?.cancel(JvmNotifierSupport.claveEstable(id))
        published.values.forEach { it -= id }
    }

    override fun cancelAll(courseId: String?) {
        val manager = manager ?: return
        if (courseId == null) {
            manager.cancelAll()
            published.clear()
            return
        }
        published.remove(courseId).orEmpty().forEach {
            manager.cancel(JvmNotifierSupport.claveEstable(it))
        }
    }

    override fun openSystemSettings() {
        val context = context ?: return
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        JvmNotifierSupport.conRespaldo { context.startActivity(intent) }
    }

    /** En Android sí: el `PendingIntent` abre la app en el destino. */
    override val supportsTapToOpen: Boolean get() = true

    override val unavailableReason: String
        get() = when {
            context == null -> "La app todavía no se inicializó."
            needsPermission() -> "Kitoru necesita permiso para mostrar notificaciones " +
                "en Android. Los avisos se ven igual dentro de la app."
            else -> ""
        }

    /** Construye la notificación del sistema a partir de la de Kitoru. */
    private fun construir(context: Context, notification: Notification): AndroidNotification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AndroidNotification.Builder(context, canal(notification))
        } else {
            @Suppress("DEPRECATION")
            AndroidNotification.Builder(context).apply {
                // Antes de Android 8 la importancia la ponía la notificación,
                // no el canal.
                priority = prioridadLegada(notification.priority)
            }
        }
        builder.setSmallIcon(Icon.createWithBitmap(iconoKitoru()))
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setWhen(notification.createdAt)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setColor(COLOR_ACENT)
            .setCategory(categoria(notification))
            .setGroup("kitoru_${notification.courseId}")
        if (notification.body.isNotBlank()) {
            builder.setStyle(AndroidNotification.BigTextStyle().bigText(notification.body))
        }
        intentDeContenido(context, notification)?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    /**
     * El `PendingIntent` que abre la app en el destino de la notificación.
     *
     * El deep link viaja **como extra** (no como URI del `Intent`): así el
     * destino no depende de que la `Activity` declare un `intent-filter` para
     * el esquema `kitoru`.
     */
    private fun intentDeContenido(context: Context, notification: Notification): PendingIntent? {
        val link = notification.deepLink ?: return null
        val intent = intentDeApertura(context) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra(EXTRA_DEEP_LINK, link.toUri())
        intent.putExtra(EXTRA_NOTIFICATION_ID, notification.id)
        return PendingIntent.getActivity(
            context,
            JvmNotifierSupport.claveEstable(notification.id),
            intent,
            // Android 12+ (API 31) exige IMMUTABLE o MUTABLE explícito.
            // IMMUTABLE es lo correcto: la notificación no modifica el intent.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** El `Intent` que abre la app (el de lanzador, si existe). */
    private fun intentDeApertura(context: Context): Intent? {
        val lanzador = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (lanzador != null) return Intent(lanzador)
        val clase = AndroidNotifierHost.activityClassName
        if (clase.isBlank()) return null
        val claseCargada: Class<*>? = try {
            Class.forName(clase)
        } catch (e: ClassNotFoundException) {
            null
        }
        return claseCargada?.let { Intent(context, it) }
    }

    /**
     * Crea los canales de notificación (una sola vez).
     *
     * Los canales son la granularidad que el usuario controla en los ajustes
     * del sistema. Se crean **de forma perezosa** y su importancia **no se
     * puede cambiar por código** una vez creada (Android la congela), por
     * eso los ids llevan versión: un cambio de importancia es un canal nuevo.
     */
    private fun ensureChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val canales = listOf(
            NotificationChannel(
                CANAL_AVISOS,
                "Avisos de la cátedra",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Novedades publicadas por tus profesores y materiales nuevos."
            },
            NotificationChannel(
                CANAL_TAREAS,
                "Tareas por vencer",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Recordatorios cuando se acerca la fecha de entrega."
            },
            NotificationChannel(
                CANAL_ALERTAS,
                "Alertas",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Problemas que te bloquean: clave vencida o sincronización fallida."
            },
            NotificationChannel(
                CANAL_INFORMES,
                "Informes",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Resúmenes semanales de la cátedra."
            },
        )
        canales.forEach { manager.createNotificationChannel(it) }
    }

    /** Qué canal va cada tipo de notificación. */
    private fun canal(notification: Notification): String = when (notification.kind) {
        NotificationKind.ANNOUNCEMENT, NotificationKind.MATERIAL_UPDATED -> CANAL_AVISOS
        NotificationKind.ASSIGNMENT_DUE -> CANAL_TAREAS
        NotificationKind.KEY_EXPIRED, NotificationKind.SYNC_ERROR -> CANAL_ALERTAS
        NotificationKind.WEEKLY_REPORT -> CANAL_INFORMES
    }

    /** La categoría del sistema, que agrupa el aviso en los ajustes. */
    private fun categoria(notification: Notification): String = when (notification.kind) {
        NotificationKind.ANNOUNCEMENT, NotificationKind.MATERIAL_UPDATED -> AndroidNotification.CATEGORY_MESSAGE
        NotificationKind.ASSIGNMENT_DUE -> AndroidNotification.CATEGORY_REMINDER
        NotificationKind.KEY_EXPIRED, NotificationKind.SYNC_ERROR -> AndroidNotification.CATEGORY_ERROR
        NotificationKind.WEEKLY_REPORT -> AndroidNotification.CATEGORY_PROGRESS
    }

    /** La prioridad **legacy** (antes de Android 8 mandaba la notificación). */
    @Suppress("DEPRECATION")
    private fun prioridadLegada(priority: NotificationPriority): Int = when (priority) {
        NotificationPriority.HIGH -> AndroidNotification.PRIORITY_HIGH
        NotificationPriority.NORMAL -> AndroidNotification.PRIORITY_DEFAULT
        NotificationPriority.LOW -> AndroidNotification.PRIORITY_LOW
    }

    /**
     * El ícono chico de la barra de estado.
     *
     * Sin ícono chico **la notificación no se muestra**, así que se dibuja
     * acá: un globo de diálogo blanco y monocromo, que es lo que el sistema
     * enmascara con el color del canal.
     */
    private fun iconoKitoru(): Bitmap {
        val cached = icono
        if (cached != null) return cached
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val path = Path().apply {
            addOval(RectF(6f, 6f, 58f, 46f), Path.Direction.CW)
            moveTo(20f, 42f)
            lineTo(16f, 58f)
            lineTo(34f, 42f)
            close()
        }
        canvas.drawPath(path, paint)
        icono = bitmap
        return bitmap
    }

    companion object {
        /** Canal de los avisos de la cátedra y los materiales nuevos. */
        const val CANAL_AVISOS = "kitoru_avisos_v1"

        /** Canal de los recordatorios de vencimiento. */
        const val CANAL_TAREAS = "kitoru_tareas_v1"

        /** Canal de las alertas (clave vencida, sincronización rota). */
        const val CANAL_ALERTAS = "kitoru_alertas_v1"

        /** Canal de los resúmenes semanales. */
        const val CANAL_INFORMES = "kitoru_informes_v1"

        /** Extra con la URI del destino de la notificación. */
        const val EXTRA_DEEP_LINK = "kitoru.deepLink"

        /** Extra con el id de la notificación. */
        const val EXTRA_NOTIFICATION_ID = "kitoru.notificationId"

        /** Color con el que el sistema tiñe el aviso en la barra de estado. */
        private const val COLOR_ACENT = 0xFF2E6BE6.toInt()

        /** Ids publicados por cátedra, para poder cancelarlos en grupo. */
        private val published = mutableMapOf<String, MutableSet<String>>()

        /** El ícono se dibuja una vez y se reutiliza. */
        private var icono: Bitmap? = null
    }
}

/** Notificador de Android. */
actual fun createLocalNotifier(): LocalNotifier = Androi  