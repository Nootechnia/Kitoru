package kitoru.domain

import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kitoru.platform.KitoruPaths
import kitoru.platform.LocalNotifier
import kitoru.platform.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * Qué tipo de evento originó la notificación.
 *
 * El tipo decide el ícono en la app y el canal en Android: "un aviso de
 * clase" y "tu clave venció" no pueden viajar por el mismo canal, porque el
 * usuario tiene que poder bajar la importancia de uno sin callar al otro.
 */
@Serializable
enum class NotificationKind(
    /** Texto que ve el usuario en la lista de notificaciones. */
    val label: String,
    /**
     * Prioridad por defecto del tipo.
     *
     * No siempre alcanza: un aviso fijado o una tarea que vence hoy suben a
     * [NotificationPriority.HIGH] aunque el tipo sea normal.
     */
    val defaultPriority: NotificationPriority,
) {
    /** Aviso nuevo publicado por la cátedra. */
    ANNOUNCEMENT("Aviso de la cátedra", NotificationPriority.NORMAL),

    /** Una entrega se acerca a su fecha límite. */
    ASSIGNMENT_DUE("Tarea por vencer", NotificationPriority.NORMAL),

    /** La clave de la cátedra venció o fue rechazada. */
    KEY_EXPIRED("Clave vencida", NotificationPriority.HIGH),

    /** La sincronización falló y hay trabajo en el outbox. */
    SYNC_ERROR("Error de sincronización", NotificationPriority.HIGH),

    /** El resumen semanal de la cátedra está listo. */
    WEEKLY_REPORT("Resumen semanal", NotificationPriority.LOW),

    /** La cátedra publicó o reemplazó un material. */
    MATERIAL_UPDATED("Material actualizado", NotificationPriority.NORMAL),
}

/**
 * Cuánto importa una notificación.
 *
 * La prioridad decide el canal en Android y si el horario silencioso la frena:
 * una clave vencida no puede esperar a la mañana.
 */
@Serializable
enum class NotificationPriority(val label: String) {
    /** Interrumpe: el usuario no puede seguir trabajando sin enterarse. */
    HIGH("Alta"),

    /** Aviso normal. */
    NORMAL("Normal"),

    /** Informativo: no hace falta enterarse ya. */
    LOW("Baja"),
    ;

    /** ¿Esta prioridad saltea el horario silencioso? */
    val interrumpe: Boolean get() = this == HIGH
}

/**
 * A dónde lleva una notificación cuando el usuario la toca.
 *
 * Es un **dato**, no una función: cada plataforma lo traduce a lo suyo (un
 * `Intent` en Android, una ruta en el shell de Compose). Por eso es
 * serializable y tiene [toUri]/[parse]: la misma URI sirve para el extra del
 * `PendingIntent` de Android y para el botón "copiar enlace" de la app.
 */
@Serializable
data class DeepLink(
    /** Qué pantalla abre el destino. */
    val kind: DeepLinkKind,
    /** Cátedra a la que pertenece el destino (vacía = destino global). */
    val courseId: String = "",
    /** Id del objeto concreto (aviso, tarea, hilo…). */
    val targetId: String = "",
) {

    /**
     * La URI del destino, por ejemplo `kitoru://course/c1/chat/hilo-7`.
     *
     * El formato es estable: es lo que viaja en el `PendingIntent` de Android
     * y lo que se copia al portapapeles, así que no puede cambiar sin migrar.
     */
    fun toUri(): String = when {
        kind == DeepLinkKind.NOTIFICATION_CENTER -> "$SCHEME://${kind.path}"
        kind == DeepLinkKind.COURSE && courseId.isEmpty() -> "$SCHEME://course"
        kind == DeepLinkKind.COURSE -> "$SCHEME://course/$courseId"
        courseId.isEmpty() -> "$SCHEME://${kind.path}" + sufijo(targetId)
        else -> "$SCHEME://course/$courseId/${kind.path}" + sufijo(targetId)
    }

    private fun sufijo(id: String): String = if (id.isEmpty()) "" else "/$id"

    companion object {
        /** Esquema de las URI de Kitoru. */
        const val SCHEME = "kitoru"

        /**
         * Parsea una URI de Kitoru. `null` si no es de Kitoru o si el tipo de
         * destino no existe.
         *
         * Se usa del lado inverso al de [toUri]: el `PendingIntent` de
         * Android entrega la URI y la app la vuelve a convertir en [DeepLink]
         * para navegar.
         */
        fun parse(uri: String): DeepLink? {
            val limpia = uri.trim()
            if (!limpia.startsWith("$SCHEME://")) return null
            val partes = limpia.removePrefix("$SCHEME://").split('/').filter { it.isNotEmpty() }
            if (partes.isEmpty()) return null
            val cabeza = partes[0].lowercase()
            if (cabeza != DeepLinkKind.COURSE.path) {
                val kind = DeepLinkKind.fromPath(cabeza) ?: return null
                return DeepLink(kind, courseId = "", targetId = partes.getOrElse(1) { "" })
            }
            // "kitoru://course/<catedra>[/<tipo>[/<id>]]"
            val courseId = partes.getOrElse(1) { "" }
            val tipo = partes.getOrNull(2)?.lowercase() ?: return DeepLink(DeepLinkKind.COURSE, courseId)
            val kind = DeepLinkKind.fromPath(tipo) ?: return null
            if (kind == DeepLinkKind.COURSE || kind == DeepLinkKind.NOTIFICATION_CENTER) return null
            return DeepLink(kind, courseId = courseId, targetId = partes.getOrElse(3) { "" })
        }
    }
}

/** Las pantallas a las que puede llevar un [DeepLink]. */
@Serializable
enum class DeepLinkKind(val path: String) {
    /** La cátedra en sí. */
    COURSE("course"),

    /** El detalle de un aviso. */
    ANNOUNCEMENT("announcement"),

    /** El detalle de una tarea. */
    ASSIGNMENT("assignment"),

    /** Un hilo de chat. */
    CHAT_THREAD("chat"),

    /** Un informe generado. */
    REPORT("report"),

    /** El detalle de un material. */
    MATERIAL("material"),

    /** El diagnóstico de sincronización. */
    SYNC("sync"),

    /** El centro de notificaciones de la app. */
    NOTIFICATION_CENTER("notifications"),
    ;

    companion object {
        /** Convierte el segmento de la URI en un tipo de destino. */
        fun fromPath(path: String): DeepLinkKind? =
            entries.firstOrNull { it.path == path.trim().lowercase() }
    }
}

/**
 * Una notificación de Kitoru.
 *
 * Es a la vez las dos cosas que la app necesita:
 *  - lo que se lista en el **centro de notificaciones dentro de la app**
 *    (que funciona siempre), y
 *  - lo que se publica en el **sistema operativo** (a veces).
 *
 * Por eso tiene [read]: el estado de lectura es del **usuario**, no del SO.
 * El SO no sabe si el usuario leyó la notificación; la app sí, y por eso el
 * centro interno es la fuente de verdad del badge.
 */
@Serializable
data class Notification(
    /**
     * Id estable del evento que la originó.
     *
     * Es la **clave de deduplicación**: el mismo evento produce siempre el
     * mismo id, así que nunca se notifica dos veces. Convenciones:
     *  - `aviso:<announcementId>`
     *  - `tarea:<assignmentId>:<umbral>` (umbral ∈ {3, 1, 0})
     *  - `clave:<courseId>:<credentialRef>`
     *  - `sync:<courseId>:<errorCode>`
     *  - `informe:<courseId>:<semanaIso>`
     */
    val id: String,

    /** Cátedra a la que pertenece (vacío = aviso global de la app). */
    val courseId: String = "",

    /** Qué tipo de evento es. Decide el ícono, el canal y el color. */
    val kind: NotificationKind,

    /** Título (una línea). Es el texto que se ve en el aviso del SO. */
    val title: String,

    /** Cuerpo (texto plano, sin Markdown). Puede estar vacío. */
    val body: String = "",

    /** Momento de creación (epoch millis). Es lo que ordena la lista. */
    val createdAt: Long = 0L,

    /** ¿El usuario ya la leyó? Lo maneja la app, no el SO. */
    val read: Boolean = false,

    /** A dónde lleva al tocarla. `null` = no lleva a ningún lado. */
    val deepLink: DeepLink? = null,

    /** Prioridad: decide el canal y si el horario silencioso la frena. */
    val priority: NotificationPriority = NotificationPriority.NORMAL,
)

/**
 * El centro de notificaciones de Kitoru.
 *
 * Es el **único** que escribe en el store y el **único** que llama al SO.
 * Nadie más llama a `LocalNotifier.show(...)`.
 *
 * La garantía del subsistema está acá y no en el sistema operativo: la
 * **bandeja interna** (la lista de [list] y el contador de [unreadCount])
 * funciona siempre, con o sin permiso del SO, con o sin bandeja del
 * sistema. El `LocalNotifier` es sólo el extra que le avisa al usuario
 * aunque esté mirando otra cosa.
 */
class NotificationCenter(
    private val fs: FileSystem,
    private val notifier: LocalNotifier,
    /** Reloj inyectable: los tests no dependen del reloj real. */
    private val clock: () -> Long = { nowMillis() },
) {

    /**
     * El store se crea **perezoso** porque la ruta depende de
     * [KitoruPaths.dataDir], que cada plataforma fija al arrancar. Si se
     * calculara en el constructor, la ruta quedaría antes de que la app fijara
     * la carpeta de datos.
     */
    private val store: JsonStore<NotificationFile> by lazy {
        JsonStore(
            fs = fs,
            path = storePath(),
            serializer = NotificationFile.serializer(),
            empty = { NotificationFile() },
        )
    }

    /**
     * Todas las notificaciones, de la más nueva a la más vieja.
     *
     * Es la **bandeja interna**: funciona siempre, aunque el SO no avise nada.
     */
    suspend fun list(courseId: String? = null): List<Notification> =
        store.read().notifications
            .filter { courseId == null || it.courseId == courseId }
            .sortedByDescending { it.createdAt }

    /**
     * Cuántas notificaciones hay sin leer.
     *
     * Es **derivado del store**: no hay ningún contador almacenado, así que
     * no hay nada que se pueda desincronizar.
     */
    suspend fun unreadCount(courseId: String? = null): Int =
        list(courseId).count { !it.read }

    /** La notificación con ese id, o `null`. */
    suspend fun get(id: String): Notification? = store.read().notifications.firstOrNull { it.id == id }

    /**
     * Publica una notificación: la guarda, la deduplica y la muestra en el SO.
     *
     * @return `true` si se publicó; `false` si ya estaba en el ledger (o sea,
     *         si el mismo evento ya se había avisado antes).
     */
    suspend fun publish(notification: Notification): Boolean {
        val archivo = store.read()
        // El ledger manda: si el id ya se disparó, no se vuelve a disparar.
        // Sobrevive al borrado de la lista, así que descartar una notificación
        // tampoco la hace volver.
        if (archivo.fired.any { it.id == notification.id }) return false
        if (archivo.notifications.any { it.id == notification.id }) return false

        val conFecha = if (notification.createdAt == 0L) {
            notification.copy(createdAt = clock())
        } else {
            notification
        }
        store.write(
            archivo.copy(
                notifications = podar((archivo.notifications + conFecha).sortedByDescending { it.createdAt }),
                fired = (archivo.fired + FiredNotification(conFecha.id, clock())).takeLast(MAX_FIRED),
            ),
        )
        // El SO es un extra: si falla, la notificación ya quedó guardada y
        // el usuario la va a ver igual en la bandeja interna.
        conRespaldo { notifier.show(conFecha) }
        return true
    }

    /** Publica varias. Devuelve cuántas se publicaron de verdad. */
    suspend fun publish(notifications: List<Notification>): Int {
        var publicadas = 0
        for (notification in notifications) if (publish(notification)) publicadas++
        return publicadas
    }

    /**
     * Marca una notificación como leída y **cancela** la del SO.
     *
     * Cancelar la del SO es la regla de "shadowing": el sistema operativo no
     * puede seguir diciendo "tenés 3 cosas nuevas" si la app ya las mostró.
     *
     * @return `true` si la notificación existía.
     */
    suspend fun markRead(id: String): Boolean {
        val archivo = store.read()
        if (archivo.notifications.none { it.id == id }) return false
        store.write(
            archivo.copy(
                notifications = archivo.notifications.map { if (it.id == id) it.copy(read = true) else it },
            ),
        )
        conRespaldo { notifier.cancel(id) }
        return true
    }

    /**
     * Marca todo como leído (de una cátedra, o de todas).
     *
     * @return cuántas pasaron a leídas.
     */
    suspend fun markAllRead(courseId: String? = null): Int {
        val archivo = store.read()
        val pendientes = archivo.notifications.filter { !it.read && (courseId == null || it.courseId == courseId) }
        if (pendientes.isEmpty()) return 0
        store.write(
            archivo.copy(
                notifications = archivo.notifications.map {
                    if (!it.read && (courseId == null || it.courseId == courseId)) it.copy(read = true) else it
                },
            ),
        )
        conRespaldo { notifier.cancelAll(courseId) }
        return pendientes.size
    }

    /**
     * Descarta una notificación (la quita de la lista).
     *
     * El id queda en el **ledger**, así que un aviso descartado no vuelve a
     * aparecer aunque su evento siga vivo: una tarea que sigue sin entregarse
     * no vuelve a avisar "vence en 3 días".
     */
    suspend fun dismiss(id: String): Boolean {
        val archivo = store.read()
        if (archivo.notifications.none { it.id == id }) return false
        store.write(archivo.copy(notifications = archivo.notifications.filterNot { it.id == id }))
        conRespaldo { notifier.cancel(id) }
        return true
    }

    /**
     * Vacía la bandeja interna: borra las notificaciones de la lista.
     *
     * El **ledger no se toca**: lo ya avisado no vuelve a avisarse.
     *
     * @return cuántas se borraron.
     */
    suspend fun clear(courseId: String? = null): Int {
        val archivo = store.read()
        val aBorrar = archivo.notifications.filter { courseId == null || it.courseId == courseId }
        if (aBorrar.isEmpty()) return 0
        val quedan = if (courseId == null) {
            emptyList()
        } else {
            archivo.notifications.filter { it.courseId != courseId }
        }
        store.write(archivo.copy(notifications = quedan))
        conRespaldo { notifier.cancelAll(courseId) }
        return aBorrar.size
    }

    /**
     * Poda la lista: cuando pasa de [MAX_NOTIFICATIONS] se borran primero las
     * leídas más antiguas, y sólo si no alcanza, las más antiguas en general.
     */
    private fun podar(lista: List<Notification>): List<Notification> {
        if (lista.size <= MAX_NOTIFICATIONS) return lista
        val sobrantes = lista.size - MAX_NOTIFICATIONS
        val leidas = lista.filter { it.read }
        val descartadas = leidas.takeLast(sobrantes).map { it.id }.toSet()
        val resto = lista.filterNot { it.id in descartadas }
        return if (resto.size <= MAX_NOTIFICATIONS) resto else resto.take(MAX_NOTIFICATIONS)
    }

    private fun storePath(): FilePath = KitoruPaths.configDir.resolve(STORE_FILE)

    companion object {
        /** Archivo donde vive la bandeja interna y el ledger. */
        const val STORE_FILE = "notifications.json"

        /** Tope de la bandeja interna. */
        const val MAX_NOTIFICATIONS = 200

        /** Tope del ledger de ids ya disparados. */
        const val MAX_FIRED = 500
    }
}

/**
 * El contenido del archivo `config/notifications.json`.
 *
 * La bandeja interna y el ledger viven en el **mismo archivo**: son dos
 * partes del mismo estado y separarlas no tiene sentido (un archivo, una
 * escritura atómica, ninguna posibilidad de que uno se guarde y el otro no).
 */
@Serializable
internal data class NotificationFile(
    /** La bandeja interna, de la más nueva a la más vieja. */
    val notifications: List<Notification> = emptyList(),
    /** Los ids que ya se dispararon: es lo que deduplica. */
    val fired: List<FiredNotification> = emptyList(),
)

/** Un id que ya se notificó (o que el usuario descartó). */
@Serializable
internal data class FiredNotification(
    val id: String,
    /** Epoch millis del disparo (o del descarte). */
    val at: Long = 0L,
)

/**
 * Ejecuta una acción del sistema operativo sin propagar su fallo.
 *
 * El SO es un extra: si la bandeja del sistema no existe, si el permiso está
 * denegado o si el sistema operativo falla, la app sigue funcionando y el
 * aviso queda en la bandeja interna.
 */
private fun <T> conRespaldo(bloque: () -> T): T? = try {
    bloque()
} catch (e: CancellationException) {
    // La cancelación se propaga: no es un error del sistema operativo.
    throw e
} catch (e: Exception) {
    null
}
