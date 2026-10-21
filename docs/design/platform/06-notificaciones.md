# 06 — Notificaciones multiplataforma

> Subagente: diseño del **subsistema de notificaciones** de Kitoru (Kotlin
> Multiplatform + Compose Multiplatform).
> Módulos tocados: `:core` (modelo + costura de plataforma), `:ui` (centro de
> notificaciones dentro de la app), `:android` (Worker, permiso, ícono),
> `:desktop` (bandeja del sistema, planificador interno).
> Documentos leídos antes de escribir: `docs/design/CONTEXTO.md`,
> `core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt` (patrón
> `expect/actual` de `:core`),
> `ui/src/commonMain/kotlin/kitoru/ui/platform/FileSaver.kt` +
> `FileSaver.desktop.kt` + `FileSaver.android.kt` (patrón de costura con
> `expect`/`actual` para una capacidad de plataforma),
> `domain/07-anuncios.md` (§5 ya define un `LocalNotifier`),
> `student/06-home-anuncios.md` (§8 usa ese seam),
> `student/05-tareas.md` (§8.2-8.3 umbrales 3/1/0 y `AssignmentReminder`),
> `teacher/10-informes.md` (§7 resumen semanal y `WorkManager`),
> `sync/02-offline.md` (outbox, dead letters, `lastError`).
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable
> por defecto*, *determinista*, *fail-fast con mensajes en español*, *el
> estudiante conserva agencia*, *el docente no ve lo que no debe*.

---

## 0. Decisiones en una línea

| Decisión | Respuesta | Motivo |
|---|---|---|
| ¿Dónde vive el modelo? | `kitoru.platform.Notification` (`:core/commonMain`) + `kitoru.domain.NotificationCenter` | La entidad es un valor puro; si viviera en `domain`, la costura `LocalNotifier` (que vive en `platform`) dependería del dominio y la dependencia se invertiría. Igual criterio que `FileSystem`. |
| ¿Sustituye a `LocalNotification` de `07-anuncios.md` §5.2? | **Sí**: una sola entidad (`Notification`) y un solo método (`show`) | Dos tipos paralelos (`LocalNotification`/`Notification`) se desincronizan. Ver §12 (reconciliación). |
| ¿Se persiste la notificación? | **Sí**: `config/notifications.json` | El centro dentro de la app es lo que *siempre* funciona (§5). El SO es sólo la señal. |
| ¿Cómo se deduplica? | **Id estable por evento** + un *ledger* de ids ya disparados (`config/notification-ledger.json`) | Un id estable (`aviso:<id>`, `tarea:<id>:<umbral>`) es la clave; el ledger sobrevive al borrado de la lista. |
| Seam de plataforma | `interface LocalNotifier` + `expect fun createLocalNotifier(): LocalNotifier` | El mismo patrón que `FileSaver` / `systemFileSystem()`. |
| ¿Cómo se abre la app al tocar? | Android: `PendingIntent` con el `deepLink` en extras. Desktop: **no hay click** → la notificación vive dentro de la app | `TrayIcon.displayMessage` no tiene callback de clic en el globo (sólo en el ícono). |
| Android | `NotificationManager` + `NotificationCompat`, 4 canales, `POST_NOTIFICATIONS` (33+), `PendingIntent` inmutable, **ícono pequeño vectorial obligatorio** | Es lo que Android sabe hacer. |
| Desktop | **(b) bandeja interna siempre** + **(a) `SystemTray` best-effort** + **(c) D-Bus en Linux como fase 2** | `SystemTray` está **deprecado para remoción en Java SE 26** y no funciona en Wayland. |
| ¿App cerrada en desktop? | **No, y se dice con todas las letras** | No hay daemon. "Con la app viva (minimizada a la bandeja)" es la verdad. |
| ¿App cerrada en Android? | **Sí, con `WorkManager`** (15 min el periodic work) | Persiste reinicios y app cerrada. Pero Doze retrasa: se avisa tarde, no nunca. |
| ¿Cuándo se pide el permiso del SO? | **En contexto**: la primera vez que el alumno activa "avisos por vencimiento" (o cualquier categoría que avise). **Nunca en el primer arranque** | Pedir un permiso sin contexto quema el único intento que Android 13+ concede. |
| ¿Qué se notifica? | 6 tipos: `ANNOUNCEMENT`, `COMMENT_REPLY`, `ASSIGNMENT_DUE`, `KEY_EXPIRED`, `SYNC_FAILED`, `WEEKLY_REPORT` | Cubre lo pedido y lo que ya aparece en los otros documentos. |
| Prioridades | `HIGH` (clave vencida, sync roto, vencimiento hoy), `NORMAL` (avisos, tareas), `LOW` (resumen semanal) | La prioridad decide el canal Android y si el horario silencioso la frena. |

---

## 1. El modelo

### 1.1 La entidad `Notification`

Es **una** entidad, no dos. Sirve para las dos cosas que la app necesita:

1. lo que se ve en el **centro de notificaciones dentro de la app** (siempre
   funciona), y
2. lo que se **publica en el sistema operativo** (a veces funciona).

Archivo: `core/src/commonMain/kotlin/kitoru/platform/Notification.kt`

```kotlin
package kitoru.platform

import kotlinx.serialization.Serializable

/**
 * Una notificación de Kitoru.
 *
 * Es a la vez:
 *  - lo que se lista en el **centro de notificaciones dentro de la app**
 *    (que funciona siempre), y
 *  - lo que se publica en el **sistema operativo** (a veces).
 *
 * Por eso tiene `read`: el estado de lectura es del **usuario**, no del SO.
 * El SO no sabe si el usuario leyó la notificación; la app sí, y por eso
 * el centro interno es la fuente de verdad del badge.
 */
@Serializable
data class Notification(
    /**
     * Id estable del evento que la originó.
     *
     * Es la **clave de deduplicación**: el mismo evento produce siempre el
     * mismo id, así que nunca se notifica dos veces.
     *
     * Convenciones:
     *  - `aviso:<announcementId>`
     *  - `comentario:<commentId>`
     *  - `tarea:<assignmentId>:<umbral>`  (umbral ∈ {3, 1, 0})
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

    /** ¿El usuario ya la leyó? Lo manage la app, no el SO. */
    val read: Boolean = false,

    /** A dónde lleva al tocarla. `null` = no lleva a ningún lado. */
    val deepLink: DeepLink? = null,

    /**
     * Prioridad.
     *
     * **Campo extra** respecto de la forma mínima: la prioridad no siempre se
     * deduce del `kind` (un aviso fijado es `HIGH`; un vencimiento a 0 días
     * es `HIGH`; el mismo `kind` con otra prioridad es otro caso).
     */
    val priority: NotificationPriority = NotificationPriority.NORMAL,
)
```

### 1.2 `NotificationKind`: los seis eventos

```kotlin
/**
 * Qué tipo de evento originó la notificación.
 *
 * El `kind` decide: el ícono en la app, el canal en Android, la categoría
 * que lo activa en los ajustes, y el color.
 */
@Serializable
enum class NotificationKind(
    val label: String,
    val category: NotificationCategory,
    /** Prioridad por defecto; se puede subir por evento (ver §6.2). */
    val basePriority: NotificationPriority,
) {
    /** Aviso nuevo de la cátedra. */
    ANNOUNCEMENT("Aviso de la cátedra", NotificationCategory.AVISOS, NotificationPriority.NORMAL),

    /** El docente respondió un comentario del usuario. */
    COMMENT_REPLY("Respuesta a tu consulta", NotificationCategory.AVISOS, NotificationPriority.NORMAL),

    /** Una tarea se acerca a su fecha de entrega. */
    ASSIGNMENT_DUE("Tarea por vencer", NotificationCategory.TAREAS, NotificationPriority.NORMAL),

    /** La clave de la cátedra venció o fue rechazada. */
    KEY_EXPIRED("Clave vencida", NotificationCategory.ALERTAS, NotificationPriority.HIGH),

    /** La sincronización falló y hay trabajo en el outbox. */
    SYNC_FAILED("Fallo de sincronización", NotificationCategory.ALERTAS, NotificationPriority.HIGH),

    /** El resumen semanal de la cátedra está listo (es del docente). */
    WEEKLY_REPORT("Resumen semanal", NotificationCategory.INFORMES, NotificationPriority.LOW),
    ;

    companion object {
        fun fromName(name: String): NotificationKind? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** Grupo de ajustes al que pertenece un tipo de notificación. */
@Serializable
enum class NotificationCategory(val label: String, val description: String) {
    AVISOS("Avisos de la cátedra", "Novedades publicadas por tus profesores y respuestas a tus consultas."),
    TAREAS("Tareas por vencer", "Recordatorios cuando se acerca la fecha de entrega."),
    ALERTAS("Alertas", "Problemas que te bloquean: la clave venció, o no se pudo sincronizar tu trabajo."),
    INFORMES("Informes", "Resúmenes semanales de la cátedra (sólo para docentes)."),
}

/** Prioridad de la notificación. */
@Serializable
enum class NotificationPriority {
    /** Interrumpe: el usuario no puede seguir trabajando sin enterarse. */
    HIGH,
    /** Aviso normal. */
    NORMAL,
    /** Informativo: no hace falta enterarse ya. */
    LOW,
    ;
    /** ¿Esta prioridad salta el horario silencioso? */
    val bypassesQuietHours: Boolean get() = this == HIGH
}
```

### 1.3 `DeepLink`: el destino

Es **un dato**, no una función: cada plataforma lo traduce a lo suyo (un
`Intent` en Android, una ruta en el shell de Compose).

```kotlin
/**
 * Destino de una notificación.
 *
 * Se serializa como una URI `kitoru://<tipo>/<id>` para que:
 *  - el `PendingIntent` de Android la lleve en un extra,
 *  - el botón "Copiar enlace" de un aviso use **exactamente el mismo** formato
 *    (ver `student/06-home-anuncios.md` §5.5, que ya usa `kitoru://anuncio/<id>`).
 */
@Serializable
data class DeepLink(
    val kind: DeepLinkKind,
    /** Id del objeto (aviso, tarea, informe…). Puede estar vacío. */
    val id: String = "",
    /** Cátedra, cuando el destino es ambiguo sin ella. */
    val courseId: String = "",
) {
    /** La URI que se copia al portapapeles y que viaja en el `Intent`. */
    fun toUri(): String {
        val base = "kitoru://${kind.path}" + if (id.isNotEmpty()) "/$id" else ""
        return if (courseId.isEmpty()) base else "$base?catedra=$courseId"
    }

    companion object {
        /** Parsea una URI. `null` si no es de Kitoru o el tipo no existe. */
        fun parse(uri: String): DeepLink? {
            val rest = uri.removePrefix("kitoru://").substringBefore('?')
            val parts = rest.split('/', limit = 2)
            val kind = DeepLinkKind.fromPath(parts.getOrElse(0) { "" }) ?: return null
            val courseId = uri.substringAfter('?', "").let {
                if (it.startsWith("catedra=")) it.removePrefix("catedra=") else ""
            }
            return DeepLink(kind, parts.getOrElse(1) { "" }, courseId)
        }
    }
}

@Serializable
enum class DeepLinkKind(val path: String) {
    /** Detalle de un aviso. */
    ANNOUNCEMENT("anuncio"),
    /** Hilo de un comentario. */
    COMMENT("comentario"),
    /** Detalle de una tarea. */
    ASSIGNMENT("tarea"),
    /** Un informe generado. */
    REPORT("informe"),
    /** Ajustes de notificaciones. */
    NOTIFICATION_SETTINGS("ajustes-notificaciones"),
    /** Diagnóstico de sincronización. */
    SYNC("sync"),
    /** Pantalla del centro de notificaciones. */
    NOTIFICATION_CENTER("notificaciones"),
    ;

    companion object {
        fun fromPath(path: String): DeepLinkKind? =
            entries.firstOrNull { it.path == path.trim().lowercase() }
    }
}
```

Ejemplos de `toUri()`:

```
kitoru://anuncio/IA-2026-1|anuncio|7f3c
kitoru://tarea/IA-2026-1|tarea-2
kitoru://informe/resumen-semanal_IA-2026-1_2026W43.md
kitoru://ajustes-notificaciones
kitoru://sync/IA-2026-1?catedra=IA-2026-1
```

> Ojo con el `|` en los ids: `parse` no lo decodifica, pero es un carácter
> válido en un path de URI y `Intent.getStringExtra` no lo toca. Si en el
> futuro se usa `Intent.parseUri`, hay que usar `Uri.encode` en `toUri()`.

### 1.4 `NotificationSpec`: lo que se *pide* notificar

Lo que decide **qué** notificar es una función pura (§6.2) que devuelve
`NotificationSpec`s. El centro las convierte en `Notification` y las publica.

```kotlin
/**
 * Intención de notificar. Todavía no es una notificación: es la *entrada*
 * del centro, que se puede descartar por deduplicación, por ajustes o por
 * horario silencioso.
 */
data class NotificationSpec(
    /** Id estable (clave de dedup). */
    val id: String,
    val kind: NotificationKind,
    val courseId: String = "",
    val title: String,
    val body: String = "",
    val deepLink: DeepLink? = null,
    /** Por defecto, la prioridad base del `kind` (§1.2). */
    val priority: NotificationPriority = kind.basePriority,
)
```

### 1.5 El `NotificationCenter`: el que publica

Es el **único** que escribe en el store y el **único** que llama al SO.
Nadie más llama a `LocalNotifier.show(...)`.

Archivo: `core/src/commonMain/kotlin/kitoru/domain/NotificationCenter.kt`

```kotlin
package kitoru.domain

import kitoru.platform.*

/**
 * El centro de notificaciones de Kitoru.
 *
 * Es el **único** que:
 *  - escribe en el store de notificaciones,
 *  - llama al `LocalNotifier` (la costura de plataforma),
 *  - mantiene el estado que la UI observa,
 *  - rutea los deep links (desde el SO o desde la app).
 *
 * Se instancia **una vez** por app y se inyecta por constructor en los
 * view-models (inyección manual, como `FileSystem`), para que los tests usen
 * un doble en memoria.
 */
class NotificationCenter(
    private val repository: NotificationRepository,
    private val ledger: NotificationLedgerRepository,
    private val notifier: LocalNotifier,
    private val settings: NotificationSettingsRepository,
    private val scope: CoroutineScope,
) {

    private val _items = MutableStateFlow<List<Notification>>(emptyList())

    /** Todo el store, del más nuevo al más viejo. Lo mira la UI. */
    val items: StateFlow<List<Notification>> = _items.asStateFlow()

    private val _deepLinks = MutableSharedFlow<DeepLink>(extraBufferCapacity = 8)

    /** Destinos a los que hay que navegar. Lo colecta el shell. */
    val deepLinks: SharedFlow<DeepLink> = _deepLinks.asSharedFlow()

    init {
        // Carga inicial (el store es local: no hay red).
        scope.launch {
            _items.value = repository.list()
        }
    }

    /**
     * Publica un aviso: lo guarda, lo deduplica y lo muestra en el SO.
     *
     * @return `true` si se publicó (es decir, si no estaba deduplicado,
     *         si la categoría está activa y si no cae en horario silencioso).
     */
    suspend fun publish(spec: NotificationSpec): Boolean {
        val cfg = settings.get()
        if (!cfg.isEnabled(spec.kind.category)) return false
        if (ledger.contains(spec.id)) return false                      // dedup
        if (!cfg.allowsNow(spec.priority, localDateTime(nowMillis()).hour)) {
            return false                                               // horario silencioso
        }

        val n = Notification(
            id = spec.id,
            courseId = spec.courseId,
            kind = spec.kind,
            title = spec.title,
            body = spec.body,
            createdAt = nowMillis(),
            read = false,
            deepLink = spec.deepLink,
            priority = spec.priority,
        )
        repository.save(n)
        ledger.markSent(spec.id, nowMillis())
        _items.value = repository.list()

        notifier.show(n)
        return true
    }

    /** Publica varios (agrupados por el evaluador de políticas). */
    suspend fun publish(specs: List<NotificationSpec>): Int {
        var sent = 0
        for (spec in specs) if (publish(spec)) sent++
        return sent
    }

    /** Marca una notificación como leída y **cancela** la del SO. */
    suspend fun markRead(id: String) {
        val current = repository.get(id) ?: return
        if (current.read) return
        repository.save(current.copy(read = true))
        _items.value = repository.list()
        notifier.cancel(id)          // sombra: el SO ya no puede "sobrescribir" a la app
    }

    /** Marca todo como leído (de una cátedra, o de todas). */
    suspend fun markAllRead(courseId: String? = null) {
        val toRead = _items.value.filter { !it.read && (courseId == null || it.courseId == courseId) }
        toRead.forEach { repository.save(it.copy(read = true)) }
        _items.value = repository.list()
        notifier.cancelAll(courseId)
    }

    /**
     * Descarta una notificación (la quita de la lista).
     *
     * Importante: el id queda en el **ledger**, así que un aviso descartado
     * **no vuelve a aparecer** aunque su evento siga vivo (una tarea que
     * sigue sin entregarse no vuelve a notificar "vence en 3 días").
     */
    suspend fun dismiss(id: String) {
        repository.delete(id)
        ledger.markSent(id, nowMillis())
        _items.value = repository.list()
        notifier.cancel(id)
    }

    /** Vacía la lista: borra las leídas (las no leídas se descartan una por una). */
    suspend fun clearRead(courseId: String? = null) {
        val toDelete = _items.value.filter { it.read && (courseId == null || it.courseId == courseId) }
        toDelete.forEach { repository.delete(it.id) }
        _items.value = repository.list()
    }

    /** Cuántas no leídas hay. **Derivado del store**: nunca un contador paralelo. */
    fun unreadCount(courseId: String? = null): Int =
        _items.value.count { !it.read && (courseId == null || it.courseId == courseId) }

    /**
     * El usuario tocó la notificación en el sistema operativo.
     *
     * La app se abrió (o ya estaba abierta): se marca leída y se emite el
     * deep link para que el shell navegue.
     */
    suspend fun openFromOs(deepLink: DeepLink) {
        val id = notificationIdFor(deepLink)
        if (id != null) markRead(id)
        _deepLinks.tryEmit(deepLink)
    }

    /** Cierra las notificaciones del SO de una cátedra (la app las absorbe). */
    fun absorbOsNotifications(courseId: String?) = notifier.cancelAll(courseId)

    private fun notificationIdFor(link: DeepLink): String? = _items.value
        .firstOrNull { it.deepLink?.kind == link.kind && it.deepLink?.id == link.id }
        ?.id
}
```

### 1.6 Persistencia

Tres archivos JSON en `config/`, con el patrón `JsonListStore` que ya usa el
proyecto:

| Archivo | Contenido | Repositorio |
|---|---|---|
| `notifications.json` | `List<Notification>` (el store visible) | `NotificationRepository` |
| `notification-ledger.json` | `List<LedgerEntry>` (ids ya disparados) | `NotificationLedgerRepository` |
| `notification-settings.json` | `NotificationSettings` | `NotificationSettingsRepository` |

```kotlin
/** Un id que ya se notificó (o que el usuario descartó). No vuelve a dispararse. */
@Serializable
data class LedgerEntry(
    val id: String,
    /** Epoch millis del disparo (o del descarte). */
    val at: Long = 0L,
)

interface NotificationRepository {
    suspend fun list(): List<Notification>            // del más nuevo al más viejo
    suspend fun get(id: String): Notification?
    suspend fun save(notification: Notification)
    suspend fun delete(id: String)
    /** Borra las que tienen más de [keep] elementos. Poda. */
    suspend fun prune(keep: Int = 200)
}

interface NotificationLedgerRepository {
    suspend fun contains(id: String): Boolean
    suspend fun markSent(id: String, at: Long = nowMillis())
    /** Poda: se quedan los 500 ids más recientes. */
    suspend fun prune(keep: Int = 500)
}

interface NotificationSettingsRepository {
    suspend fun get(): NotificationSettings
    suspend fun save(settings: NotificationSettings)
}
```

**El ledger es lo que hace que la deduplicación sobreviva al borrado.** Sin
él, si el usuario descarta la notificación "vence en 3 días" y abre la app al
día siguiente, volvería a aparecer.

### 1.7 Los ajustes

```kotlin
@Serializable
data class NotificationSettings(
    /** Avisos de la cátedra y respuestas a comentarios. */
    val avisos: Boolean = true,
    /** Recordatorios de vencimiento. **Apagado por defecto**: es lo que el
     *  alumno activa, y es el momento en que se pide el permiso del SO. */
    val tareas: Boolean = false,
    /** Clave vencida / fallo de sincronización. */
    val alertas: Boolean = true,
    /** Resumen semanal (docente). */
    val informes: Boolean = true,

    /** Silenciar de noche. */
    val quietHours: Boolean = true,
    /** Inicio del silencio (minutos desde medianoche). 22:00 = 1320. */
    val quietFrom: Int = 22 * 60,
    /** Fin del silencio. 8:00 = 480. */
    val quietTo: Int = 8 * 60,

    /** Cuándo se pidió el permiso del SO por última vez (0 = nunca). */
    val osPermissionAskedAt: Long = 0L,
    /** Cuándo lo rechazó (0 = nunca). */
    val osPermissionDeniedAt: Long = 0L,
) {
    fun isEnabled(category: NotificationCategory): Boolean = when (category) {
        NotificationCategory.AVISOS -> avisos
        NotificationCategory.TAREAS -> tareas
        NotificationCategory.ALERTAS -> alertas
        NotificationCategory.INFORMES -> informes
    }

    /**
     * ¿Se puede notificar ahora mismo?
     *
     * `HIGH` siempre pasa: una clave vencida no espera al otro día.
     */
    fun allowsNow(priority: NotificationPriority, hourOfDay: Int): Boolean {
        if (!quietHours || priority.bypassesQuietHours) return true
        val inQuiet = if (quietFrom <= quietTo) {
            hourOfDay >= quietFrom && hourOfDay < quietTo
        } else {
            // El silencio cruza la medianoche (22:00 → 08:00).
            hourOfDay >= quietFrom || hourOfDay < quietTo
        }
        return !inQuiet
    }
}
```

Para el horario local hace falta la hora del día, que no es portable con
`nowMillis()` (que es UTC). Se agrega al paquete de plataforma:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/LocalDateTime.kt

/** Fecha y hora **locales**, descompuestas. */
data class LocalDateTime(
    val year: Int,
    /** 1..12 */
    val month: Int,
    /** 1..31 */
    val day: Int,
    /** 0..23 */
    val hour: Int,
    val minute: Int,
    /** 1 = lunes … 7 = domingo (igual que `Calendar.DAY_OF_WEEK` desplazado). */
    val dayOfWeek: Int,
) {
    /** Clave de semana ISO: "2026W43". */
    fun isoWeekKey(): String = "$year-W${isoWeekNumber():02d}"
}

/** La hora local del reloj del sistema en el instante dado. */
expect fun localDateTime(epochMillis: Long): LocalDateTime
```

La `isoWeekNumber()` es una función **pura** en `commonMain` (semana ISO 8601:
lunes = día 1; la semana 1 es la que contiene el primer jueves del año). Se
testea con fechas fijas, sin reloj — como las de `NotificationPolicy`.

El `actual` va en `jvmCommonMain` (desktop **y** Android son JVM) con
`java.util.Calendar` — que existe desde la API 1 —, no con `java.time`
(API 26, y el proyecto tiene `minSdk = 24`).

---

## 2. La costura de plataforma

### 2.1 La interfaz

Es exactamente el patrón de `FileSaver` (`ui/src/commonMain/.../FileSaver.kt`):
interfaz en `commonMain`, `expect` de la fábrica, `actual` por plataforma.

Archivo: `core/src/commonMain/kotlin/kitoru/platform/Notifier.kt`

```kotlin
package kitoru.platform

/**
 * Publica notificaciones en el **sistema operativo**.
 *
 * Es una costura `expect/actual`: la app no sabe en qué plataforma está.
 *
 * Reglas de la costura:
 *  - `show()` **nunca** lanza: si el SO no puede notificar, es un no-op
 *    silencioso. El error se reporta, no se propaga.
 *  - `show()` es **idempotente por id**: publicar dos veces el mismo id
 *    *reemplaza* la notificación, no la duplica.
 *  - la app **nunca** llama a `show()` sin pasar por el
 *    `NotificationCenter` (que es quien deduplica).
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
     * En desktop: el globo de la bandeja del sistema (o no-op si no hay bandeja).
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
     * habilitarlo a mano (en Android 13+, el diálogo del sistema no vuelve
     * a aparecer después de dos rechazos).
     */
    fun openSystemSettings() {}

    /**
     * ¿Se puede abrir la app al tocar la notificación?
     *
     * - Android: `true` (hay `PendingIntent`).
     * - Desktop: **`false`**. El globo de la bandeja del sistema no es
     *   clickeable (`TrayIcon` no tiene callback del globo), y en Linux/D-Bus
     *   depende del daemon. Por eso, en desktop, la notificación vive
     *   **dentro de la app**.
     */
    val supportsTapToOpen: Boolean get() = true

    /**
     * ¿Por qué no se puede notificar? (para explicárselo al usuario).
     * Vacío = sí se puede.
     */
    val unavailableReason: String get() = ""
}

/** La implementación de la plataforma. */
expect fun createLocalNotifier(): LocalNotifier
```

### 2.2 Los `actual`

| commonMain | desktopMain | androidMain |
|---|---|---|
| `kitoru.platform.Notification` (modelo) | — | — |
| `kitoru.platform.Notifier` (`LocalNotifier` + `expect`) | `Notifier.desktop.kt` → `DesktopLocalNotifier` (SystemTray) | `Notifier.android.kt` → `AndroidLocalNotifier` (`NotificationManager`) |

Los dos `actual` **no** contienen lógica de negocio: sólo traducen la
`Notification` a la llamada del SO y contestan `areEnabled()`.

### 2.3 Cómo se abre la app al tocarla

Es la parte que más difiere entre plataformas, así que va explícita:

| | Android | Desktop |
|---|---|---|
| ¿Tocar la notificación abre algo? | **Sí**: `PendingIntent` → `Intent` a la `Activity` principal con el `deepLink` en un extra | **No**: el globo no es clickeable. La notificación vive en el centro interno de la app |
| ¿Cómo se llega al destino? | La `Activity` (o `onNewIntent`) lee el extra, lo parsea con `DeepLink.parse`, y llama a `NotificationCenter.openFromOs(link)` → el shell navega | El usuario abre la app y ve el centro de notificaciones; si hace clic en un ítem, el shell navega al `deepLink` |
| ¿Y si la app está abierta? | `launchMode = "singleTask"` + `FLAG_ACTIVITY_SINGLE_TOP`: llega `onNewIntent` y no se crea una segunda instancia | N/A |
| Clic en el ícono de la bandeja | N/A | Levanta la ventana y navega a `kitoru://notificaciones` (el centro) |

**El "deep link" es el mismo dato en las dos plataformas**: la UI navega
usando `DeepLink`, no usando un `Intent` de Android. Ése es el motivo de que
`DeepLink` sea serializable y tenga `toUri()`/`parse()`.

### 2.4 La regla de oro

> **Si el sistema operativo no notifica, la app igual muestra la novedad.**

Por eso:

- el badge y el centro interno **no** dependen de `LocalNotifier.areEnabled()`;
  se calculan del store (`NotificationCenter.unreadCount`),
- `areEnabled() == false` **no** es un error ni deshabilita la función: sólo
  cambia *dónde* se ve la notificación (dentro de la app en vez de en el SO);
- la UI de ajustes muestra el motivo (`unavailableReason`) en vez de un error.

---

## 3. Android

Archivo: `core/src/androidMain/kotlin/kitoru/platform/Notifier.android.kt`

### 3.1 El contexto de la app

Igual que `KitoruPaths.setDataDir(...)`: el contexto se inyecta al arrancar.

```kotlin
// core/src/androidMain/kotlin/kitoru/platform/KitoruAndroid.kt
package kitoru.platform

import android.content.Context

/**
 * Estado global de la app Android.
 *
 * Lo inicializa el `Application` de `:android` antes de usar nada:
 *
 * ```kotlin
 * class KitoruApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         KitoruAndroid.init(this, MainActivity::class.java.name)
 *     }
 * }
 * ```
 */
object KitoruAndroid {
    private lateinit var appContext: Context
    private var activityClassName: String = ""

    /**
     * Hook que la app inyecta: corre el barrido de notificaciones.
     *
     * Existe porque `:core` no conoce a `:android`: el `Worker` de
     * WorkManager vive en la biblioteca, pero el evaluador (que necesita
     * repositorios y perfil) vive en la app.
     */
    var onEvaluate: (suspend () -> Unit)? = null

    fun init(context: Context, activityClassName: String) {
        appContext = context.applicationContext
        this.activityClassName = activityClassName
    }

    val context: Context get() = appContext
    val activity: String get() = activityClassName
}
```

### 3.2 `AndroidLocalNotifier`

```kotlin
package kitoru.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.funshiengine.kitoru.core.R   // el R de la biblioteca :core

/**
 * Notificador de Android: `NotificationManager` + `NotificationCompat`.
 *
 * `NotificationCompat` (de `androidx.core:core-ktx`) se usa en vez del
 * `Notification.Builder` nativo porque funciona igual desde la API 24 y
 * evita mantener dos caminos de construcción.
 *
 * El ícono pequeño y el color viven en los recursos **de la biblioteca**
 * (`core/src/androidMain/res/`), no en los de la app: así el notificador es
 * autónomo y la app no tiene que acordarse de nada.
 */
class AndroidLocalNotifier : LocalNotifier {

    private val manager get() = NotificationManagerCompat.from(KitoruAndroid.context)

    override fun areEnabled(): Boolean =
        NotificationManagerCompat.from(KitoruAndroid.context).areNotificationsEnabled() &&
            !needsPermission()

    override fun needsPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            KitoruAndroid.context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun show(notification: Notification) {
        if (!areEnabled()) return
        ensureChannels()
        val builder = NotificationCompat.Builder(KitoruAndroid.context, channelOf(notification))
            .setSmallIcon(R.drawable.ic_stat_kitoru)     // ← OBLIGATORIO (§3.6)
            .setColor(KitoruAndroid.context.getColor(R.color.kitoru_accent))
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setWhen(notification.createdAt)
            .setShowWhen(true)
            .setAutoCancel(true)                          // al tocarla se cierra
            .setCategory(categoryOf(notification))
            .setPriority(priorityOf(notification))
            .setGroup("kitoru_${notification.courseId}")
            .setContentIntent(contentIntent(notification))
        manager.notify(TAG, notification.id.hashCode(), builder.build())
        // Se recuerda para poder cancelar por cátedra (§3.6).
        published.getOrPut(notification.courseId) { mutableSetOf() } += notification.id
    }

    override fun cancel(id: String) {
        manager.cancel(TAG, id.hashCode())
        published.values.forEach { it -= id }
    }

    override fun cancelAll(courseId: String?) {
        if (courseId == null) {
            manager.cancelAll()
            published.clear()
            return
        }
        // NotificationManager no tiene "cancelar por grupo": hay que saber los ids.
        val ids = published[courseId].orEmpty()
        if (ids.isEmpty()) manager.cancelAll() else ids.forEach { manager.cancel(TAG, it.hashCode()) }
        published.remove(courseId)
    }

    override fun openSystemSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, KitoruAndroid.context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        KitoruAndroid.context.startActivity(intent)
    }

    override val supportsTapToOpen: Boolean get() = true

    companion object {
        const val TAG = "kitoru"
        /** ids publicados por cátedra, para poder cancelarlos en grupo. */
        private val published = mutableMapOf<String, MutableSet<String>>()
    }
}
```

El `contentIntent` es el deep link:

```kotlin
private fun contentIntent(n: Notification): PendingIntent {
    val intent = Intent(KitoruAndroid.context, KitoruAndroid.activity).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_DEEP_LINK, n.deepLink?.toUri() ?: "")
        putExtra(EXTRA_NOTIFICATION_ID, n.id)
    }
    return PendingIntent.getActivity(
        KitoruAndroid.context,
        n.id.hashCode(),          // requestCode estable: una notificación, un intent
        intent,
        // Android 12+ (API 31) exige IMMUTABLE o MUTABLE explícito.
        // IMMUTABLE es lo correcto: no hace falta que el Notificación modifique el intent.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

const val EXTRA_DEEP_LINK = "kitoru.deepLink"
const val EXTRA_NOTIFICATION_ID = "kitoru.notificationId"
```

En la `Activity`:

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/android/MainActivity.kt
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        // …
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.getStringExtra(EXTRA_DEEP_LINK).orEmpty()
        if (uri.isEmpty()) return
        val link = DeepLink.parse(uri) ?: return
        scope.launch { notificationCenter.openFromOs(link) }
    }
}
```

`AndroidManifest.xml` de `:android`:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<activity
    android:name=".MainActivity"
    android:launchMode="singleTask"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="kitoru" />
    </intent-filter>
</activity>
```

El `intent-filter` es para el caso "copiar enlace" (`kitoru://anuncio/<id>`) y
para que otra app pueda abrir Kitoru; el camino normal es el extra.

### 3.3 Los canales

Los canales son la granularidad que el usuario controla en Ajustes del sistema.
Se crean **de forma perezosa** (la primera vez que se postea algo de esa
categoría) y **una sola vez**: la importancia de un canal **no se puede
cambiar por código** después de crearlo (Android la congela; hay que borrar
el canal y crear otro con id nuevo, y eso pierde los ajustes del usuario).

Canal nuevo = id nuevo. Por eso los ids llevan versión: `kitoru_avisos_v1`.

| Id del canal | Nombre visible | `IMPORTANCE` | Qué entra |
|---|---|---|---|
| `kitoru_avisos_v1` | "Avisos de la cátedra" | `DEFAULT` | `ANNOUNCEMENT`, `COMMENT_REPLY` |
| `kitoru_tareas_v1` | "Tareas por vencer" | `DEFAULT` | `ASSIGNMENT_DUE` |
| `kitoru_alertas_v1` | "Alertas" | `HIGH` | `KEY_EXPIRED`, `SYNC_FAILED` |
| `kitoru_informes_v1` | "Informes" | `LOW` | `WEEKLY_REPORT` |

> Los dos canales que pide el enunciado (`kitoru_avisos`, `kitoru_tareas`)
> están; los otros dos son una extensión justificada: mezclar una clave
> vencida con un aviso de clase en el mismo canal obliga a que el usuario
> baje la importancia de todo, y el resumen semanal del docente no merece
> la misma interrupción que "vence hoy".

```kotlin
private fun ensureChannels() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = KitoruAndroid.context.getSystemService(NotificationManager::class.java) ?: return
    listOf(
        NotificationChannel("kitoru_avisos_v1", "Avisos de la cátedra", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Novedades publicadas por tus profesores y respuestas a tus consultas." },
        NotificationChannel("kitoru_tareas_v1", "Tareas por vencer", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Recordatorios cuando se acerca la fecha de entrega." },
        NotificationChannel("kitoru_alertas_v1", "Alertas", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Problemas que te bloquean: clave vencida o sincronización fallida." },
        NotificationChannel("kitoru_informes_v1", "Informes", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Resúmenes semanales de la cátedra." },
    ).forEach { nm.createNotificationChannel(it) }
}

/** Qué canal va cada tipo de notificación. */
private fun channelOf(n: Notification): String = when (n.kind) {
    NotificationKind.ANNOUNCEMENT, NotificationKind.COMMENT_REPLY -> "kitoru_avisos_v1"
    NotificationKind.ASSIGNMENT_DUE -> "kitoru_tareas_v1"
    NotificationKind.KEY_EXPIRED, NotificationKind.SYNC_FAILED -> "kitoru_alertas_v1"
    NotificationKind.WEEKLY_REPORT -> "kitoru_informes_v1"
}

/** `NotificationCompat` agrupa por categoría del sistema. */
private fun categoryOf(n: Notification): String = when (n.kind) {
    NotificationKind.ANNOUNCEMENT, NotificationKind.COMMENT_REPLY -> NotificationCompat.CATEGORY_MESSAGE
    NotificationKind.ASSIGNMENT_DUE -> NotificationCompat.CATEGORY_REMINDER
    NotificationKind.KEY_EXPIRED, NotificationKind.SYNC_FAILED -> NotificationCompat.CATEGORY_ERROR
    NotificationKind.WEEKLY_REPORT -> NotificationCompat.CATEGORY_PROGRESS
}

/** La prioridad **legacy** (pre-Android 8). En API 26+ manda el canal. */
private fun priorityOf(n: Notification): Int = when (n.priority) {
    NotificationPriority.HIGH -> NotificationCompat.PRIORITY_HIGH
    NotificationPriority.NORMAL -> NotificationCompat.PRIORITY_DEFAULT
    NotificationPriority.LOW -> NotificationCompat.PRIORITY_LOW
}
```

`IMPORTANCE_HIGH` además de sonar, **vibra**: por eso la categoría `ALERTAS`
está apagada por defecto para el usuario que no la quiere.

### 3.4 El permiso `POST_NOTIFICATIONS` (API 33+)

- **API 24–32**: el permiso es implícito (se concede en la instalación). No
  hace falta pedir nada. La app **no** declara nada especial.
- **API 33+**: permiso en tiempo de ejecución.
  - Declarar en el manifest (ya está arriba).
  - `:core` **no pide permisos** (necesita una `Activity`): expone
    `needsPermission()`.
  - `:android` lo pide, **en contexto** (§7).

```kotlin
// android/src/main/kotlin/com/funshiengine/kitoru/android/NotificationPermission.kt

/**
 * Pedido del permiso de notificaciones de Android 13+.
 *
 * Vive en `:android` (la app), no en `:core`: pedir un permiso necesita una
 * `Activity`, y `:core` es una biblioteca sin Activity.
 */
@Composable
fun rememberNotificationPermissionRequester(
    onResult: (granted: Boolean) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onResult(granted) }

    return remember(launcher) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onResult(true)
            }
        }
    }
}
```

Detalle importante: en Android 13+, si el usuario rechaza el diálogo **dos
veces**, el sistema **deja de mostrarlo**. La app detecta ese caso (el
`launcher` devuelve `false` y `shouldShowRequestPermissionRationale` es
`false`) y, en vez de volver a pedir, **manda al usuario a los ajustes del
sistema** con `LocalNotifier.openSystemSettings()`.

### 3.5 El ícono pequeño: **obligatorio**

> **Sin `setSmallIcon(...)`, la notificación no se muestra.** No hay excepción:
> en API 21+ el ícono se recorta a una máscara blanca y, si falta, el
> `NotificationManager` lanza `IllegalArgumentException` o simplemente no
> publica nada.

Dos errores clásicos:

1. **Usar el ícono del lanzador** (`R.mipmap.ic_launcher`): es un PNG a color
   con fondo transparente y detalle; el sistema lo enmascara y queda un
   **cuadrado blanco** o una mancha irreconocible. Hay que hacer un recurso
   propio.
2. **Usar un PNG con degradado**: se ve mal en la barra de estado.

El recurso correcto es un **vector monocromo** (silueta blanca sobre
transparente, 24×24 dp):

```xml
<!-- core/src/androidMain/res/drawable/ic_stat_kitoru.xml -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <!-- Silueta de un globo de diálogo: es el "avatar" de Kitoru. -->
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M4,4h16a2,2 0 0 1 2,2v9a2,2 0 0 1 -2,2H9.5L5,20.5V17H4a2,2 0 0 1 -2,-2V6a2,2 0 0 1 2,-2z" />
</vector>
```

Reglas del recurso:

- **blanco puro** (`#FFFFFFFF`), sin degradados ni colores: el sistema lo
  tiñe con el color del canal o del accent.
- **sin detalle fino**: a 24 dp en la barra de estado, un detalle de 1 dp
  desaparece. Silueta gruesa.
- el **accent** se setea aparte con `setColor(R.color.kitoru_accent)`, no en el
  vector.
- el ícono de la app (`ic_launcher`) se sigue usando para el lanzador; el de
  la barra de estado es **otro** recurso.

### 3.6 "Shadowing": el SO no puede sobrescribir a la app

Cuando el usuario lee una notificación en el centro interno, la del SO se
**cancela** (`NotificationCenter.markRead` → `notifier.cancel(id)`). Y al
abrir la app, `absorbOsNotifications(courseId)` cierra las que sobren.

Es la regla que hace que la app y el SO no se contradigan: el SO nunca dice
"tenés 3 cosas nuevas" cuando la app ya está mostrando 0.

---

## 4. Desktop

### 4.1 Las tres opciones

| | (a) `java.awt.SystemTray` | (b) Sólo el centro interno | (c) Notificaciones del SO |
|---|---|---|---|
| Qué es | `TrayIcon.displayMessage(...)`: el globo de la bandeja | La lista dentro de la app, con badge | D-Bus `org.freedesktop.Notifications` en Linux; `UNUserNotificationCenter` en macOS; toast de WinRT en Windows |
| Windows | ✅ Funciona (globo / toast del Action Center) | ✅ | ✅ (pero requiere `WinRT`, no hay en el JDK) |
| macOS | ⚠️ Funciona vía `NSUserNotification` (JDK-8195735), que **Apple deprecó** en Big Sur; el ícono sale genérico si la app no es un `.app` bundle | ✅ | ⚠️ Hay que escribir un puente nativo a `UNUserNotificationCenter` (Objective-C/Swift) |
| Linux (X11) | ⚠️ Sólo si el DE tiene bandeja (StatusNotifierItem/XEmbed). Muchos ya no la tienen | ✅ | ✅ (`org.freedesktop.Notifications`, estándar desde 2009) |
| **Linux (Wayland)** | ❌ **No funciona**: AWT habla X11; bajo XWayland `SystemTray.isSupported()` suele ser `false` o el ícono no aparece. Y Wayland es el default desde Fedora 40 / Ubuntu 24.10 | ✅ | ✅ (D-Bus no depende de X11) |
| Costo | Bajo (30 líneas) | Bajo | Medio: D-Bus necesita cliente (`dbus-java`) y un daemon escuchando |
| Mantenimiento | **`java.awt.SystemTray` está *deprecado para remoción en Java SE 26*** (JDK 26, marzo 2026). El proyecto usa JDK 21, así que funciona hoy, pero la API está en su fin de vida | — | El estándar freedesktop es estable |
| Dependencia del proceso | La app tiene que estar viva | La app tiene que estar viva | La app tiene que estar viva |

### 4.2 Veredicto por plataforma

| SO | Veredicto | Motivo |
|---|---|---|
| **Windows** | **(a) + (b)**: `SystemTray` como extra, centro interno como base | El globo de Windows funciona y es familiar. Pero la API está deprecada para remoción: se encapsula en **un solo archivo** (`Notifier.desktop.kt`) para poder sacarla sin tocar nada más |
| **macOS** | **(b) + (a) best-effort** | `displayMessage` llega al Centro de Notificaciones, pero sobre `NSUserNotification` (deprecado por Apple) y el ícono de la bandeja sale genérico si la app no se empaqueta como `.app`. Un `.app` bundle se arma en la fase de packaging (`process/01-packaging-ci.md`), no en este subsistema |
| **Linux/X11** | **(a) + (b)**, y (a) sólo si `SystemTray.isSupported()` | Depende del DE; en KDE/GNOME con extensión de bandeja anda, en un WM pelado no |
| **Linux/Wayland** | **(b) solamente** en el hito 1; **(c) D-Bus** como fase 2 | AWT no habla Wayland. El camino correcto es el estándar `org.freedesktop.Notifications` por D-Bus, que funciona igual en X11 y Wayland |

### 4.3 Recomendación pragmática

> **La bandeja interna de la app es la base y el sistema operativo es un extra
> opcional que puede no existir.** Ninguna función de Kitoru depende de que
> el SO notifique.

Concretamente, tres capas, de la más confiable a la menos:

1. **Centro de notificaciones dentro de la app** (§5) — *siempre*, en las dos
   plataformas, sin permiso, sin daemon, sin configuración del SO. Es la
   garantía.
2. **`SystemTray` (best effort)** — se usa si `SystemTray.isSupported()`. Si
   no, `areEnabled()` devuelve `false` y `unavailableReason` lo explica
   ("Este sistema no tiene bandeja de notificaciones: los avisos se ven
   dentro de la app"). **No es un error**: la app sigue funcionando.
3. **D-Bus en Linux (fase 2, opcional)** — `org.freedesktop.Notifications`
   sobre D-Bus. Se activa sólo si responde un daemon de notificaciones
   (`dunst`, `mako`, `gnome-shell`, `kde-notifications`). Es el único camino
   que funciona bajo Wayland, y como tal es **el único que justifica un
   intento de notificación con la app minimizada** en Linux.

Lo que **no** se hace:

- **No se escribe un daemon.** En desktop, "con la app cerrada" no existe.
  Si alguna vez se quiere, es un proyecto aparte (`process/02-roadmap.md`).
- **No se usa `notify-send` ni `ProcessBuilder`**: depender de un binario
  externo que puede no existir es frágil, y `ProcessBuilder` no es portable.
- **No se promete "notificaciones con la app cerrada"** en ninguna parte de la
  UI. La frase honesta es: *"Con Kitoru abierto (puede estar minimizado en la
  bandeja), te avisamos."*

### 4.4 La implementación en desktop

Archivo: `core/src/desktopMain/kotlin/kitoru/platform/Notifier.desktop.kt`

```kotlin
package kitoru.platform

import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.imageio.ImageIO

/**
 * Notificador de escritorio: globo de la bandeja del sistema.
 *
 * Es **best effort**: si la máquina no tiene bandeja (Linux sin tray, Wayland,
 * RDP, servidor sin escritorio), `areEnabled()` es `false` y `show()` es un
 * no-op silencioso. La app **nunca** falla por esto: el centro de
 * notificaciones interno es el que guarantees que el usuario vea el aviso.
 *
 * Nota: `java.awt.SystemTray` está deprecado para remoción en Java SE 26.
 * Todo el uso de AWT queda encerrado en este archivo para poder sacarlo
 * (o reemplazarlo por D-Bus) sin tocar el resto del código.
 */
class DesktopLocalNotifier : LocalNotifier {

    private val tray: TrayIcon? = createTray()
    private val supported: Boolean = tray != null

    override fun areEnabled(): Boolean = supported

    override fun show(notification: Notification) {
        val icon = tray ?: return
        // El globo muestra ~2 líneas: se recorta el cuerpo.
        icon.displayMessage(
            notification.title,
            notification.body.take(120),
            TrayIcon.MessageType.INFO,
        )
    }

    override fun cancel(id: String) {
        // El globo de AWT no se puede cerrar programáticamente: se va solo.
    }

    override fun cancelAll(courseId: String?) {
        // Ídem.
    }

    override val supportsTapToOpen: Boolean get() = false

    override val unavailableReason: String
        get() = if (supported) "" else
            "Este sistema no tiene bandeja de notificaciones: los avisos se ven " +
                "dentro de la app (icono de la campana, arriba a la izquierda)."

    private fun createTray(): TrayIcon? = try {
        if (!SystemTray.isSupported()) return null
        val image: Image = KitoruIcons.load("/kitoru-tray.png") ?: return null
        val icon = TrayIcon(image, "Kitoru")
        icon.isImageAutoSize = true
        // El clic en el ícono (no en el globo) abre la app en el centro.
        icon.addActionListener { NotificationRouter.onTrayIconClicked() }
        SystemTray.getSystemTray().add(icon)
        icon
    } catch (t: Throwable) {
        // Un escritorio roto no puede romper la app.
        null
    }
}
```

Sobre el clic: el globo de AWT **no** es clickeable (`TrayIcon` no tiene
callback para el globo). Lo único que hay es el clic en el **ícono** de la
bandeja, que se usa para abrir la app en el centro de notificaciones. Por eso
`supportsTapToOpen = false` y por eso el enunciado dice que en desktop "la
notificación vive dentro de la app".

### 4.5 La ruta D-Bus (fase 2, Linux)

Se deja especificada, no implementada, para que quede claro el camino:

| Pieza | Detalle |
|---|---|
| Transporte | D-Bus, sesión `org.freedesktop.Notifications` |
| Llamada | `org.freedesktop.Notifications.Notify(app_name, replaces_id, app_icon, summary, body, actions, hints, timeout)` |
| Detección | Si el bus no responde `org.freedesktop.Notifications.GetCapabilities`, no hay daemon → no hay notificaciones |
| Identidad | `app_name = "Kitoru"`, `app_icon = "kitoru"` (un `.desktop` instalado) |
| Dependencia | `dbus-java` (o un cliente D-Bus mínimo) — **no** está en el JDK |
| Clic | El estándar soporta "actions" (botones), pero el clic en el cuerpo depende del daemon → `supportsTapToOpen` sigue siendo `false` |

---

## 5. El centro de notificaciones dentro de la app

Es lo que **siempre** funciona, en las dos plataformas, sin permiso y sin
configurar el sistema operativo. Si todo lo demás falla, esto queda.

### 5.1 La pantalla

Ruta: `alumno/notificaciones` (alumno) y `docente/notificaciones` (docente) →
el **mismo** composable, con distinto origen de datos.

```
┌───────────────────────────────────────────────────────────────────────────────┐
│  ← Inicio            Notificaciones                          [Marcar todo leído]│
│                                                                               │
│  7 notificaciones · 2 sin leer                              [Limpiar leídas]  │
├───────────────────────────────────────────────────────────────────────────────┤
│  HOY                                                                         │
│  ┌─────────────────────────────────────────────────────────────────────────┐  │
│  │ ● 🔔  La clase del martes se cancela                            14:02  │  │
│  │       Se cancela la clase del martes 27/10…                              │  │
│  └─────────────────────────────────────────────────────────────────────────┘  │
│  ┌─────────────────────────────────────────────────────────────────────────┐  │
│  │    ⏰  TP2 — Búsqueda heurística vence mañana                    09:00  │  │
│  │       Vence mañana (20 puntos).                                         │  │
│  └─────────────────────────────────────────────────────────────────────────┘  │
│  AYER                                                                         │
│  ┌─────────────────────────────────────────────────────────────────────────┐  │
│  │    🔑  Tu clave de la cátedra venció                         18/10     │  │
│  │       La clave venció el 18/10: pedile una nueva a tu docente.         │  │
│  └─────────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────────┘
```

| Elemento | Regla |
|---|---|
| Fila | Ícono por `kind` + punto `●` si no leída + título (1–2 líneas) + cuerpo (2 líneas, ellipsis) + hora relativa |
| Secciones | "Hoy" · "Ayer" · "Esta semana" · "Antes" (por `createdAt`, hora local) |
| Orden | Del más nuevo al más viejo; dentro de la sección, también |
| Tintado | Las no leídas con fondo `secondaryContainer`; las leídas, texto normal |
| Ícono por `kind` | `ANNOUNCEMENT` → `Campaign`; `COMMENT_REPLY` → `Forum`; `ASSIGNMENT_DUE` → `Schedule`; `KEY_EXPIRED` → `Key`; `SYNC_FAILED` → `CloudOff`; `WEEKLY_REPORT` → `Assessment` |
| Clic en la fila | Marca leída + navega al `deepLink` |
| Menú de la fila | "Marcar como leída" · "Marcar como no leída" · "Copiar enlace" · "Descartar" |
| Estado vacío | "No tenés notificaciones." + "Cuando tu profesor publique un aviso o se acerque una entrega, te va a aparecer acá." (nunca un error) |
| Filtro | Chips: "Todas" · "Sin leer" |

### 5.2 El badge

- **Dentro de la app**: el ícono de la campana en el riel (desktop) o en la
  barra inferior (Android) lleva el conteo de no leídas. Es
  `NotificationCenter.unreadCount(courseId)`: **derivado del store**.
- **Desktop, fuera de la app**: sufijo en el título de la ventana
  (`Kitoru (3)`) — `expect fun setAppBadge(count: Int)` en
  `ui/src/commonMain/kotlin/kitoru/ui/platform/AppBadge.kt` (ya previsto en
  `student/06-home-anuncios.md` §8.4).
- **Android, fuera de la app**: el badge del lanzador **no** es portable
  (`ShortcutBadger` es una chapuza por lanzador). Se dice la verdad: el
  Android muestra el badge en su barra inferior, no en el ícono del lanzador.

Regla dura (ya está en `student/06-home-anuncios.md` §8.5 y se repite acá):
> **No hay ningún contador almacenado.** El badge es `items.count { !it.read }`.
> Si se desincroniza, no hay nada que se pueda desincronizar.

### 5.3 El vaciado

Tres acciones distintas, con nombres distintos (mezclarlas confunde):

| Acción | Qué hace | Dónde |
|---|---|---|
| **Marcar todo como leído** | Todos los no leídos de la cátedra (o todos) pasan a leídos, y se cancelan los del SO | Barra superior, sólo si hay no leídos |
| **Limpiar leídas** | **Borra** del store las notificaciones ya leídas. El store queda con lo no leído y lo reciente | Barra superior, sólo si hay leídas |
| **Descartar** (por fila) | Borra una notificación (leída o no) y **anota su id en el ledger**: no vuelve a aparecer aunque el evento siga vivo | Menú de la fila |

Poda: el store se limita a **200** notificaciones; al superarlo se borran las
leídas más antiguas (`NotificationRepository.prune`).

### 5.4 El banner dentro de la app

Cuando llega una notificación **con la app abierta y visible**, además de
agregarse a la lista, aparece un banner (3–5 s) arriba:

```
┌──────────────────────────────────────────────────────────────┐
│ 🔔  La clase del martes se cancela          [Abrir]  [✕]   │
└──────────────────────────────────────────────────────────────┘
```

- Si la app está en primer plano, **no** se publica en el SO (sería duplicado):
  `NotificationCenter` consulta un `isForeground` que el shell actualiza. Es
  el mismo mecanismo del "shadowing" (§3.6).
- El banner se puede descartar: eso marca la notificación como leída **y**
  la anota en el ledger.

---

## 6. Qué se notifica

### 6.1 La tabla

| `kind` | Quién lo recibe | Disparador | Prioridad | Canal Android | Id de dedup |
|---|---|---|---|---|---|
| `ANNOUNCEMENT` | alumnos y docentes | aviso publicado con `createdAt >= enrollment.joinedAt` y sin leer | `NORMAL` (`HIGH` si `pinned`) | `kitoru_avisos` | `aviso:<announcementId>` |
| `COMMENT_REPLY` | el autor del comentario | el docente responde un comentario del usuario | `NORMAL` | `kitoru_avisos` | `comentario:<commentId>` |
| `ASSIGNMENT_DUE` | alumnos sin entrega | umbrales **3 / 1 / 0** días | `NORMAL` (`HIGH` en 0) | `kitoru_tareas` | `tarea:<assignmentId>:<umbral>` |
| `KEY_EXPIRED` | alumnos (y docente) | `apiKeyExpiresAt < now`, o el servidor responde `key_expired` (401) | `HIGH` | `kitoru_alertas` | `clave:<courseId>:<credentialRef>` |
| `SYNC_FAILED` | ambos | el outbox tiene *dead letters* o N reintentos agotados | `HIGH` | `kitoru_alertas` | `sync:<courseId>:<errorCode>` |
| `WEEKLY_REPORT` | docentes | el resumen semanal se generó | `LOW` | `kitoru_informes` | `informe:<courseId>:<semanaIso>` |

### 6.2 Las políticas (puras y testeables)

El "qué notificar" es una **función pura** sobre el estado del dominio: sin
repositorio, sin reloj del sistema (el `now` entra por parámetro). Es la
regla 3 del proyecto ("determinista") aplicada a las notificaciones.

Archivo: `core/src/commonMain/kotlin/kitoru/domain/NotificationPolicy.kt`

```kotlin
package kitoru.domain

import kitoru.platform.*

/**
 * Reglas de qué notificar.
 *
 * Son **funciones puras**: la misma entrada da siempre la misma lista de
 * `NotificationSpec`. Sin repositorio, sin reloj, sin red. Se testean con
 * listas y timestamps fijos.
 */
object NotificationPolicy {

    /** Umbrales de aviso de vencimiento, en días. */
    val DEADLINE_THRESHOLDS = listOf(3, 1, 0)

    /** ¿Qué umbral corresponde a esta cantidad de días? `null` = no avisar. */
    fun deadlineThreshold(daysUntil: Int): Int? = when {
        daysUntil <= 0 -> 0
        daysUntil <= 1 -> 1
        daysUntil <= 3 -> 3
        else -> null
    }

    /**
     * Avisos nuevos de la cátedra.
     *
     * Reglas:
     *  - filtra por audiencia (`audience.matches(enrollment)`),
     *  - saltea los anteriores a `enrollment.joinedAt` (el alumno no tiene
     *    que enterarse de lo que ya estaba),
     *  - saltea los ya leídos (`readUserIds`),
     *  - `pinned` sube la prioridad a `HIGH`.
     */
    fun announcementSpecs(
        announcements: List<Announcement>,
        enrollment: Enrollment,
        readUserIds: Set<String>,
    ): List<NotificationSpec> = announcements
        .filter { it.audience.matches(enrollment) }
        .filter { it.createdAt >= enrollment.joinedAt }
        .filter { it.id !in readUserIds }
        .map { a ->
            NotificationSpec(
                id = "aviso:${a.id}",
                kind = NotificationKind.ANNOUNCEMENT,
                courseId = a.courseId,
                title = a.title,
                body = a.body.plain(140),
                deepLink = DeepLink(DeepLinkKind.ANNOUNCEMENT, a.id, a.courseId),
                priority = if (a.pinned) NotificationPriority.HIGH else NotificationPriority.NORMAL,
            )
        }

    /**
     * Tareas por vencer.
     *
     * Un aviso por (tarea, umbral): la tarea "TP2" genera tres
     * notificaciones a lo largo de su vida ("vence en 3 días", "vence
     * mañana", "vence hoy"), y ninguna se repite.
     */
    fun deadlineSpecs(
        assignments: List<Assignment>,
        submittedAssignmentIds: Set<String>,
        courseId: String,
        now: Long,
    ): List<NotificationSpec> = assignments
        .filter { it.published && !it.closed }
        .filter { it.id !in submittedAssignmentIds }
        .filter { it.dueAt > 0L }
        .mapNotNull { a ->
            val days = DeadlineMath.daysUntil(now, a.dueAt)
            val threshold = deadlineThreshold(days) ?: return@mapNotNull null
            NotificationSpec(
                id = "tarea:${a.id}:$threshold",
                kind = NotificationKind.ASSIGNMENT_DUE,
                courseId = courseId,
                title = a.title,
                body = when (threshold) {
                    0 -> "Vence hoy. La entrega cierra esta noche."
                    1 -> "Vence mañana."
                    else -> "Vence en $threshold días."
                },
                deepLink = DeepLink(DeepLinkKind.ASSIGNMENT, a.id, courseId),
                priority = if (threshold == 0) NotificationPriority.HIGH else NotificationPriority.NORMAL,
            )
        }

    /**
     * Clave vencida.
     *
     * El id incluye la *referencia* de la credencial, no la clave misma: si el
     * docente emite una clave nueva, la referencia cambia y el aviso puede
     * volver a dispararse (que es lo que quiere el usuario).
     */
    fun credentialSpec(course: Course, credentialRef: String, now: Long): NotificationSpec? {
        val expiry = course.modelPolicy.apiKeyExpiresAt
        if (expiry <= 0L || expiry >= now) return null
        return NotificationSpec(
            id = "clave:${course.id}:$credentialRef",
            kind = NotificationKind.KEY_EXPIRED,
            courseId = course.id,
            title = "Tu clave de ${course.shortName} venció",
            body = "Pedile una clave nueva a tu docente para volver a chatear.",
            deepLink = DeepLink(DeepLinkKind.SYNC, "", course.id),
            priority = NotificationPriority.HIGH,
        )
    }

    /**
     * Fallo de sincronización.
     *
     * Sólo se notifica si hay trabajo **en riesgo** (el outbox tiene
     * elementos en *dead letter*). Un fallo de red sin nada pendiente no
     * interrumpe al usuario: se muestra en el chip de conexión.
     */
    fun syncFailureSpec(courseId: String, errorCode: String, pendingCount: Int): NotificationSpec? {
        if (pendingCount <= 0) return null
        return NotificationSpec(
            id = "sync:$courseId:$errorCode",
            kind = NotificationKind.SYNC_FAILED,
            courseId = courseId,
            title = "No pudimos sincronizar tu trabajo",
            body = if (pendingCount == 1) {
                "Hay 1 elemento sin enviar. Se reintentará solo."
            } else {
                "Hay $pendingCount elementos sin enviar. Se reintentarán solos."
            },
            deepLink = DeepLink(DeepLinkKind.SYNC, "", courseId),
            priority = NotificationPriority.HIGH,
        )
    }

    /** Resumen semanal del docente. */
    fun weeklyReportSpec(course: Course, reportId: String, weekKey: String): NotificationSpec =
        NotificationSpec(
            id = "informe:${course.id}:$weekKey",
            kind = NotificationKind.WEEKLY_REPORT,
            courseId = course.id,
            title = "Resumen semanal de ${course.shortName}",
            body = "El informe de la semana $weekKey está listo.",
            deepLink = DeepLink(DeepLinkKind.REPORT, reportId, course.id),
            priority = NotificationPriority.LOW,
        )
}
```

Y el evaluador que las corre y publica (el "motor"):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/NotificationEvaluator.kt

/**
 * Corre las políticas y publica lo que corresponde.
 *
 * Es el único que llama a [NotificationCenter.publish]. Se lo invoca:
 *  - al abrir la app,
 *  - al sincronizar,
 *  - el planificador (§8).
 */
class NotificationEvaluator(
    private val center: NotificationCenter,
    private val announcements: AnnouncementRepository,
    private val reads: AnnouncementReadRepository,
    private val assignments: AssignmentRepository,
    private val submissions: SubmissionRepository,
    private val courses: CourseRepository,
    private val enrollments: EnrollmentRepository,
    private val outbox: CommentOutbox,
    private val clock: () -> Long = { nowMillis() },
) {
    /** Evalúa todo y devuelve cuántas notificaciones publicó. */
    suspend fun evaluate(): Int {
        val now = clock()
        var sent = 0

        for (course in courses.list()) {
            for (enrollment in enrollments.listForCourse(course.id)) {
                sent += center.publish(
                    NotificationPolicy.announcementSpecs(
                        announcements.listForCourse(course.id),
                        enrollment,
                        reads.readUserIds(course.id),
                    ),
                )
            }
            sent += center.publish(
                NotificationPolicy.deadlineSpecs(
                    assignments.listPublished(course.id),
                    submissions.submittedIds(course.id),
                    course.id,
                    now,
                ),
            )
            val pending = outbox.pending(course.id).count { it.attempts >= MAX_ATTEMPTS }
            sent += center.publish(
                listOfNotNull(NotificationPolicy.syncFailureSpec(course.id, "DEAD_LETTER", pending)),
            )
        }
        return sent
    }

    companion object {
        /** Intentos a partir de los cuales un envío queda en *dead letter*. */
        const val MAX_ATTEMPTS = 5
    }
}
```

### 6.3 Deduplicación: las reglas

1. **Id estable por evento.** El id se deriva del evento, no del reloj ni de
   un contador. El mismo aviso genera siempre `aviso:<id>`.
2. **El ledger manda.** `ledger.contains(id)` → no se publica. El ledger
   sobrevive al borrado de la lista (por eso el "descartar" no hace que la
   tarea vuelva a avisar).
3. **Un umbral por tarea.** `tarea:<id>:<umbral>`: los tres umbrales son
   tres notificaciones distintas, y cada una se dispara una sola vez.
4. **La clave se renueva con la credencial.** `clave:<courseId>:<credentialRef>`:
   si el docente emite una clave nueva, la referencia cambia y el aviso puede
   repetirse (que es lo correcto: el problema volvió a aparecer).
5. **La semana es parte del id del informe.** `informe:<courseId>:<semanaIso>`:
   un informe por semana, no uno por día.
6. **La deduplicación es por evento, no por ventana temporal.** No hay "no
   notificar más de una vez cada N minutos" salvo para `SYNC_FAILED`, que
   usa su propio id (`sync:<courseId>:<errorCode>`) y por lo tanto tampoco
   se repite hasta que el error cambie.

Corolario: `AssignmentReminder` (`student/05-tareas.md` §8.3) **queda
reemplazado** por el ledger. Ver §12.

### 6.4 Agrupación

Si una cátedra tiene **3 o más** avisos nuevos sin leer, en vez de tres
globos sueltos se publica:

- una **notificación resumen**: "3 avisos nuevos en IA-2026-1" → deep link a
  la lista de avisos;
- los individuales, con `setGroup("kitoru_<courseId>")`, para que el sistema
  los agrupe y los muestre juntos al desplegar.

En Android, la notificación resumen lleva `setGroupSummary(true)`.

En desktop, el globo de AWT no agrupa: se muestra el primero y el resto
queda en el centro interno. No es grave: el centro interno es la lista
completa.

### 6.5 Horario silencioso

- Ventana por defecto: **22:00 a 08:00**, activa por defecto, ajustable.
- Las notificaciones `HIGH` **saltean** el silencio (una clave vencida a las
  3 de la mañana se puede esperar a la mañana; una clave vencida que te
  bloquea el trabajo, no).
- Durante el silencio, la notificación **se guarda igual** en el store (aparece
  en el centro interno) pero **no se publica en el SO**.

---

## 7. El permiso y los ajustes

### 7.1 La pantalla de ajustes

Ruta: `alumno/ajustes` → sección "Notificaciones" (desktop: diálogo).

```
┌─ Notificaciones ────────────────────────────────────────────────────┐
│                                                                      │
│  Notificaciones del sistema                                         │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │ [✓] Avisos de la cátedra                                        │  │
│  │     Novedades publicadas por tus profesores y respuestas.        │  │
│  ├────────────────────────────────────────────────────────────────┤  │
│  │ [ ] Tareas por vencer                                            │  │
│  │     Recordatorios cuando se acerca la fecha de entrega.          │  │
│  │     Activá esto para recibir avisos aunque la app esté cerrada. │  │
│  ├────────────────────────────────────────────────────────────────┤  │
│  │ [✓] Alertas                                                      │  │
│  │     Clave vencida o sincronización fallida.                      │  │
│  ├────────────────────────────────────────────────────────────────┤  │
│  │ [✓] Informes (sólo docentes)                                    │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  No molestar de  [22:00] a [08:00]        [✓] Silenciar de noche     │
│                                                                      │
│  Las notificaciones dentro de la app están siempre activas.          │
└──────────────────────────────────────────────────────────────────────┘
```

La última línea es importante: **la función no depende del permiso**. El
permiso del SO sólo agrega la señal fuera de la app.

### 7.2 Cuándo se pide el permiso (y cuándo no)

**Nunca en el primer arranque.** Pedir un permiso sin contexto quema el
único intento que Android 13+ concede por instalación.

| Momento | Qué pasa |
|---|---|
| Primer arranque | **No se pide nada.** Los ajustes muestran "Avisos de la cátedra: activado" (dentro de la app) y el SO todavía no está habilitado |
| El alumno **activa "Tareas por vencer"** | Se muestra un diálogo explicando **qué** va a pasar y **por qué**: "Kitoru puede avisarte cuando se acerque una fecha de entrega, aunque la app esté cerrada. Android te va a pedir permiso para mostrar notificaciones." → botón "Activar notificaciones" → se pide el permiso del SO **ahí mismo**, en contexto |
| El alumno **desactiva** una categoría | No se pide nada. Se guarda el ajuste y el SO sigue como esté (el SO no avisa de "categorías") |
| El alumno **rechaza** el permiso | El interruptor **vuelve a apagarse** (no queda en un estado mentiroso) y se muestra: "Las notificaciones van a aparecer sólo dentro de la app. Podés habilitarlas en Ajustes del sistema." + botón "Abrir ajustes" |
| El usuario rechazó **dos veces** (Android 13+ no vuelve a mostrar el diálogo) | El interruptor queda apagado y aparece **sólo** el botón "Abrir ajustes del sistema" (`LocalNotifier.openSystemSettings()`) |
| El alumno activa "Alertas" | Mismo patrón en contexto. Las alertas son `HIGH` y saltan el horario silencioso: por eso el diálogo dice "te vamos a avisar aunque la app esté cerrada" |
| En desktop | **No hay permiso del SO.** Los interruptores controlan las categorías; si no hay bandeja del sistema, el interruptor de la categoría se muestra **deshabilitado** con el motivo (`unavailableReason`) y la app aclara que los avisos se ven dentro de la app |

Regla anti-frustración: se guarda `osPermissionAskedAt` y `osPermissionDeniedAt`.
Si el usuario rechazó, **no se vuelve a pedir** en los siguientes **30 días**
(salvo que el usuario active la categoría otra vez, que es un gesto explícito).

### 7.3 El "soft ask"

En la home del alumno hay una tarjeta discreta (no un modal) que desaparece
para siempre en cuanto el usuario la descarta:

```
┌──────────────────────────────────────────────────────────────┐
│ 🔔  Llevate los avisos al teléfono                           │
│     Kitoru te avisa cuando se acerque una entrega o publicen  │
│     un aviso, aunque la app esté cerrada.                     │
│     [Activar]  [Ahora no]                                     │
└──────────────────────────────────────────────────────────────┘
```

Es un **nudge**, no un permiso: el permiso se pide sólo cuando el usuario
aprieta "Activar" (o activa la categoría en ajustes).

---

## 8. El scheduling

### 8.1 Por qué hace falta

Los avisos por vencer necesitan dispararse **aunque la app esté cerrada**.
Eso no se resuelve con un `while(true) { delay(...) }` en el proceso de la
app: si la app está cerrada, no hay proceso.

| | Mecanismo | ¿App cerrada? | Realidad |
|---|---|---|---|
| Android | `WorkManager` (`PeriodicWorkRequest`, 15 min mínimo) | **Sí** (persiste reinicios y cierre) | En Doze se retrasa: el aviso llega tarde, **nunca** se pierde |
| Desktop | Planificador **dentro del proceso** | **No** | Hay que decirlo con honestidad |

### 8.2 Android: `WorkManager`

Dependencia: `androidx.work:work-runtime-ktx` en `core/src/androidMain`
(o en `:android`; lo natural es que el `Worker` viva en `:core/androidMain`
para que la costura de plataforma esté completa — igual que
`createLocalNotifier()`).

```kotlin
// core/src/androidMain/kotlin/kitoru/platform/NotificationWorker.kt
package kitoru.platform

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Barrido periódico de notificaciones.
 *
 * Corre aunque la app esté cerrada: `WorkManager` persiste el trabajo y lo
 * reintenta. Es un **barrido**, no un reloj: evalúa qué corresponde ahora y
 * notifica lo que falte (la deduplicación evita repetidos).
 */
class NotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        KitoruAndroid.onEvaluate?.invoke()
        Result.success()
    } catch (t: Throwable) {
        // Un fallo puntual no debe matar el trabajo periódico.
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        const val PERIODIC_NAME = "kitoru-notificaciones"
        const val DEADLINE_NAME = "kitoru-vencimientos"

        /** Barrido cada 15 minutos (el mínimo de WorkManager). */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<NotificationWorker>(
                15, TimeUnit.MINUTES,
            ).setConstraints(
                Constraints.Builder().setRequiresBatteryNotLow(false).build(),
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        /**
         * Aviso puntual de un vencimiento.
         *
         * El barrido periódico puede llegar tarde (Doze, restricciones del
         * sistema). Para el umbral "vence hoy" se encola además un trabajo
         * **de una sola vez** con la demora exacta, que es lo que el usuario
         * espera.
         */
        fun scheduleDeadline(context: Context, assignmentId: String, atMillis: Long) {
            val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            val req = OneTimeWorkRequestBuilder<NotificationWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("assignmentId" to assignmentId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$DEADLINE_NAME-$assignmentId",
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }
    }
}
```

`Application` de `:android`:

```kotlin
class KitoruApp : Application() {
    lateinit var notificationScope: CoroutineScope
    lateinit var evaluator: NotificationEvaluator

    override fun onCreate() {
        super.onCreate()
        KitoruAndroid.init(this, MainActivity::class.java.name)
        notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        evaluator = buildEvaluator(this)      // el service locator de la app
        // El barrido de WorkManager llama a este hook (§8.2).
        KitoruAndroid.onEvaluate = { evaluator.evaluate() }
        NotificationWorker.schedulePeriodic(this)
    }
}
```

**Lo que WorkManager NO garantiza** (y hay que decir):

- No corre a una hora exacta. En Doze, un trabajo periódico puede retrasarse
  **horas**. Para un recordatorio de vencimiento eso es aceptable: el aviso
  llega tarde, no se pierde.
- No corre si el usuario puso la app en "restricción de fondo" o la mató a
  la fuerza con la tarea restringida.
- Requiere el permiso `POST_NOTIFICATIONS` **sólo** para la notificación: el
  barrido corre igual (y el store interno se actualiza igual).

### 8.3 Desktop: planificador dentro de la app

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/NotificationScheduler.kt
package kitoru.domain

import kotlinx.coroutines.*

/**
 * Planificador de notificaciones **dentro del proceso de la app**.
 *
 * Es lo que hay en desktop, y hay que decirlo con todas las letras:
 *
 * > **Sólo corre mientras la app está abierta.** No hay daemon, y no se
 * > va a agregar uno en esta versión. Si Kitoru está cerrado, no hay
 * > recordatorios de vencimiento ni resumen semanal.
 *
 * Para bajar el ruido, el planificador **no** duerme: recalcula el próximo
 * disparo con `delay` hasta esa hora (un `while(true) { delay(60_000) }`
 * despierta 1440 veces por hora y es un desperdicio).
 */
class NotificationScheduler(
    private val scope: CoroutineScope,
    /** Devuelve el instante (epoch millis) del próximo disparo, o `null`. */
    private val nextRunAt: () -> Long?,
    /** Qué hacer en el disparo. */
    private val onTick: suspend () -> Unit,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val at = nextRunAt()
                val delay = when {
                    at == null -> SLEEP_IDLE
                    else -> (at - nowMillis()).coerceIn(0L, MAX_SLEEP)
                }
                delay(delay)
                if (isActive) runCatching { onTick() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Espera máxima entre chequeos: 1 minuto. */
        const val MAX_SLEEP = 60_000L
        /** Espera cuando no hay nada programado. */
        const val SLEEP_IDLE = 60_000L
    }
}
```

Reglas del planificador de desktop:

- **Arranca al abrir la app** y **para al cerrarla**.
- Si la app se abrió **después** de la hora programada y el trabajo del día
  todavía no corrió, se corre **de inmediato** (mismo criterio que
  `teacher/10-informes.md` §7.4: no perder la semana porque el docente no
  abrió la app el lunes).
- El estado "última ejecución" se persiste en
  `config/notification-state.json` (`lastDeadlineScanAt`, `lastWeeklyReportAt`),
  para que la app no repita el trabajo al reiniciar.

### 8.4 La tabla de verdad de las promesas

| Promesa | Android | Desktop |
|---|---|---|
| "Te aviso cuando se acerque una entrega" | ✅ con `WorkManager` (puede llegar tarde) | ⚠️ **sólo con la app abierta** |
| "Te aviso si tu profesor publica algo" | ✅ (con el cliente del servidor, hito 2) | ⚠️ **sólo con la app abierta** |
| "Te aviso aunque la app esté cerrada" | ✅ sí | ❌ **no, y no se promete** |
| "Podés ver los avisos aunque no tengas notificaciones" | ✅ siempre (centro interno) | ✅ siempre (centro interno) |

---

## 9. Nombres de archivos y clases

### 9.1 `:core` — `commonMain`

| Archivo | Contenido |
|---|---|
| `core/src/commonMain/kotlin/kitoru/platform/Notification.kt` | `Notification`, `NotificationKind`, `NotificationCategory`, `NotificationPriority` |
| `core/src/commonMain/kotlin/kitoru/platform/DeepLink.kt` | `DeepLink`, `DeepLinkKind` |
| `core/src/commonMain/kotlin/kitoru/platform/Notifier.kt` | `LocalNotifier` + `expect fun createLocalNotifier(): LocalNotifier` |
| `core/src/commonMain/kotlin/kitoru/platform/LocalDateTime.kt` | `LocalDateTime` + `expect fun localDateTime(epochMillis)` |
| `core/src/commonMain/kotlin/kitoru/domain/NotificationCenter.kt` | `NotificationCenter`, `NotificationSpec` |
| `core/src/commonMain/kotlin/kitoru/domain/NotificationPolicy.kt` | `NotificationPolicy` (funciones puras) |
| `core/src/commonMain/kotlin/kitoru/domain/NotificationEvaluator.kt` | `NotificationEvaluator` (corre las políticas y publica) |
| `core/src/commonMain/kotlin/kitoru/domain/NotificationScheduler.kt` | `NotificationScheduler` (planificador de desktop) |
| `core/src/commonMain/kotlin/kitoru/domain/NotificationSettings.kt` | `NotificationSettings` + `NotificationSettingsRepository` |
| `core/src/commonMain/kotlin/kitoru/domain/Repositories.kt` | **Cambios**: `+ NotificationRepository`, `+ NotificationLedgerRepository`, `+ NotificationSettingsRepository` |
| `core/src/commonMain/kotlin/kitoru/domain/FileRepositories.kt` | **Cambios**: `+ FileNotificationRepository`, `+ FileNotificationLedgerRepository`, `+ FileNotificationSettingsRepository` |

### 9.2 `:core` — `androidMain`

| Archivo | Contenido |
|---|---|
| `core/src/androidMain/kotlin/kitoru/platform/Notifier.android.kt` | `AndroidLocalNotifier` (canales, `PendingIntent`, permiso) |
| `core/src/androidMain/kotlin/kitoru/platform/KitoruAndroid.kt` | `KitoruAndroid.init(context, activityClassName)` |
| `core/src/androidMain/kotlin/kitoru/platform/NotificationWorker.kt` | `NotificationWorker` (WorkManager) + `schedulePeriodic` / `scheduleDeadline` |
| `core/src/androidMain/res/drawable/ic_stat_kitoru.xml` | **El ícono pequeño monocromo** (§3.6) — recurso de la biblioteca, no de la app |
| `core/src/androidMain/res/values/colors.xml` | `kitoru_accent` (el color de la notificación) |

### 9.3 `:core` — `desktopMain`

| Archivo | Contenido |
|---|---|
| `core/src/desktopMain/kotlin/kitoru/platform/Notifier.desktop.kt` | `DesktopLocalNotifier` (`SystemTray`, best effort) |
| `core/src/jvmCommonMain/kotlin/kitoru/platform/LocalDateTime.jvm.kt` | `actual fun localDateTime(...)` con `java.util.Calendar` (desktop **y** Android son JVM) |

### 9.4 `:ui` — `commonMain`

| Archivo | Composables / tipos |
|---|---|
| `ui/src/commonMain/kotlin/kitoru/ui/notifications/NotificationCenterScreen.kt` | `NotificationCenterScreen(vm)` — la lista |
| `ui/src/commonMain/kotlin/kitoru/ui/notifications/NotificationCenterViewModel.kt` | `NotificationCenterViewModel` |
| `ui/src/commonMain/kotlin/kitoru/ui/notifications/NotificationCenterUiState.kt` | `NotificationCenterUiState`, `NotificationRowModel`, `NotificationFilter`, `NotificationSection` |
| `ui/src/commonMain/kotlin/kitoru/ui/notifications/NotificationRow.kt` | `NotificationRow(model, onOpen, onMenu)` |
| `ui/src/commonMain/kotlin/kitoru/ui/notifications/NotificationBanner.kt` | `NotificationBanner(notification, onOpen, onDismiss)` — el banner con la app abierta |
| `ui/src/commonMain/kotlin/kitoru/ui/notifications/NotificationIcon.kt` | `NotificationIcon(kind)` — el ícono por tipo |
| `ui/src/commonMain/kotlin/kitoru/ui/settings/NotificationSettingsSection.kt` | `NotificationSettingsSection(settings, onToggle, onRequestPermission)` |
| `ui/src/commonMain/kotlin/kitoru/ui/settings/NotificationPermissionDialog.kt` | El diálogo en contexto del §7.2 |
| `ui/src/commonMain/kotlin/kitoru/ui/platform/AppBadge.kt` | `expect fun setAppBadge(count: Int)` (ya previsto en `student/06`) |
| `ui/src/desktopMain/kotlin/kitoru/ui/platform/AppBadge.desktop.kt` | `actual`: sufijo en el título de la ventana |
| `ui/src/androidMain/kotlin/kitoru/ui/platform/AppBadge.android.kt` | `actual`: no-op (el badge del lanzador no es portable) |

### 9.5 `:android` (la app)

| Archivo | Contenido |
|---|---|
| `android/src/main/AndroidManifest.xml` | `POST_NOTIFICATIONS`, `launchMode="singleTask"`, `intent-filter` de `kitoru://` |
| `android/src/main/kotlin/.../KitoruApp.kt` | `Application`: `KitoruAndroid.init`, `KitoruAndroid.onEvaluate`, `NotificationWorker.schedulePeriodic` |
| `android/src/main/kotlin/.../MainActivity.kt` | Lee `EXTRA_DEEP_LINK` en `onCreate` y `onNewIntent` |
| `android/src/main/kotlin/.../NotificationPermission.kt` | `rememberNotificationPermissionRequester` |

### 9.6 `:desktop` (la app)

| Archivo | Contenido |
|---|---|
| `desktop/src/main/resources/kitoru-tray.png` | Ícono 32×32 para la bandeja |
| `desktop/src/main/kotlin/.../Main.kt` | Arranca el `NotificationScheduler` |

### 9.7 Tests

| Archivo | Qué prueba |
|---|---|
| `core/src/commonTest/kotlin/kitoru/platform/DeepLinkTest.kt` | `toUri`/`parse` ida y vuelta; URI inválida → `null` |
| `core/src/commonTest/kotlin/kitoru/platform/NotificationTest.kt` | Serialización; `priority` por defecto |
| `core/src/commonTest/kotlin/kitoru/domain/NotificationPolicyTest.kt` | Umbrales 3/1/0; filtro por audiencia; `joinedAt`; prioridad del aviso fijado |
| `core/src/commonTest/kotlin/kitoru/domain/NotificationCenterTest.kt` | Dedup con `FakeNotifier`; `markRead` cancela en el SO; `dismiss` no deja volver a la notificación |
| `core/src/commonTest/kotlin/kitoru/domain/NotificationSettingsTest.kt` | Horario silencioso, incluida la ventana que cruza la medianoche |
| `core/src/commonTest/kotlin/kitoru/domain/NotificationEvaluatorTest.kt` | Publica lo esperado y nada más (con `FakeNotifier`) |

Doble de pruebas (el mismo patrón que `FakeNotifier` de
`student/06-home-anuncios.md` §11.3):

```kotlin
class FakeNotifier : LocalNotifier {
    val shown = mutableListOf<Notification>()
    val cancelled = mutableListOf<String>()
    var enabled = true
    override fun areEnabled() = enabled
    override fun show(notification: Notification) { shown += notification }
    override fun cancel(id: String) { cancelled += id }
    override fun cancelAll(courseId: String?) { cancelled += "all:${courseId ?: "*"}" }
}
```

---

## 10. Casos borde

| Caso | Comportamiento |
|---|---|
| El SO no permite notificar (permiso denegado, bandeja ausente) | `show()` es un no-op silencioso. La notificación **igual** está en el centro interno y el badge cuenta igual |
| El usuario descartó la notificación "vence en 3 días" | Queda en el ledger: no vuelve a aparecer. La tarea sigue venciendo, pero el usuario ya la vio |
| El usuario marcó todo como leído | Todos los no leídos pasan a leídos y **se cancelan las del SO** (shadowing) |
| El usuario marcó como no leído un aviso | Se borra su registro de lectura (según `07-anuncios.md` §4.5) y **vuelve a entrar** en el conteo; la notificación del SO no se vuelve a publicar (ya está en el ledger) |
| Llega un aviso mientras la app está en primer plano | Se publica en el centro interno + banner, y **no** en el SO (duplicado) |
| El usuario toca la notificación del SO con la app cerrada | Se abre la app, se marca leída y se navega al `deepLink` |
| El usuario toca la notificación del SO con la app abierta | `onNewIntent` → mismo camino, sin duplicar la ventana (`singleTask`) |
| Un `deepLink` apunta a algo que ya no existe (el aviso se borró) | La navegación muestra un estado vacío honesto: "Este aviso ya no está disponible." |
| El reloj del sistema cambia de zona horaria | El store usa epoch millis (no le afecta); sólo cambia el agrupamiento por "Hoy/Ayer" y el horario silencioso |
| El reloj retrocede | La deduplicación por id no se rompe: los ids ya notificados siguen en el ledger |
| 10 avisos nuevos de golpe | Con ≥3 se publica una notificación resumen + las individuales agrupadas (§6.4) |
| La cátedra se desinscribe | Las notificaciones de esa cátedra se pueden vaciar ("Limpiar leídas" por cátedra) |
| El docente tiene 300 alumnos y 40 comentarios nuevos | El docente **no** recibe una notificación por comentario ajeno: sólo por los comentarios **de sus** hilos o por respuestas a lo que escribió |
| La app se desinstala y se reinstala | Se pierde el store y el ledger: los avisos "pendientes" se vuelven a notificar una vez. Es aceptable (el store local es la caché) |
| `POST_NOTIFICATIONS` se revoca desde los ajustes del sistema | `areEnabled()` pasa a `false`; los avisos siguen en el centro interno; los ajustes lo explican |
| WorkManager corre el barrido 10 veces en una hora | La deduplicación por id lo neutraliza: se notifica una sola vez |

---

## 11. Criterios de aceptación

1. `LocalNotifier` es una interfaz en `commonMain` con `show`, `cancel`,
   `cancelAll`, `areEnabled`, `needsPermission`, `openSystemSettings` y
   `supportsTapToOpen`; existe `expect fun createLocalNotifier(): LocalNotifier`
   y un `actual` por plataforma.
2. La entidad `Notification` tiene exactamente `id`, `courseId`, `kind`,
   `title`, `body`, `createdAt`, `read`, `deepLink` (más `priority`), es
   `@Serializable` y se persiste en `config/notifications.json`.
3. Publicar el mismo `id` dos veces publica **una sola** notificación (ledger).
4. Descartar una notificación la quita de la lista y **no** vuelve a aparecer.
5. En Android, la notificación aparece en la barra de estado **con el ícono
   monocromo `ic_stat_kitoru`**; sin ese recurso, la notificación no se
   muestra (y hay un test que verifica que el recurso existe y es vector).
6. En Android, tocar la notificación abre la app en el destino del
   `deepLink` (verificable a mano con `adb shell am start -a android.intent.action.VIEW -d "kitoru://anuncio/<id>"`).
7. Los canales `kitoru_avisos` y `kitoru_tareas` existen con
   `IMPORTANCE_DEFAULT`; las alertas van a un canal propio `HIGH` y los
   informes a uno `LOW`.
8. En Android 13+, el permiso `POST_NOTIFICATIONS` **no** se pide en el primer
   arranque; se pide la primera vez que el usuario activa una categoría.
   Verificable: instalar, arrancar, y comprobar que no aparece el diálogo.
9. Con el permiso denegado, la app **no** falla y el centro interno sigue
   funcionando (badge y lista).
10. En desktop sin bandeja del sistema, `areEnabled() == false`,
    `unavailableReason` lo explica y `show()` no lanza.
11. El badge de no leídas es `items.count { !it.read }`: **no hay ningún
    contador almacenado** (revisable en el código y en un test).
12. `NotificationPolicy.deadlineThreshold` devuelve `3`, `1`, `0` para 3, 1 y 0
    días, y `null` para 4 días o más.
13. El resumen semanal del docente genera **una** notificación por semana
    (`informe:<courseId>:<semanaIso>`).
14. En desktop, el planificador **no** sobrevive al cierre de la app, y la
    documentación de la app lo dice en el texto de la ayuda de las
    notificaciones ("si cerrás Kitoru, no te llegan avisos de vencimiento").
15. En Android, `NotificationWorker` está encolado como trabajo periódico
    único y sobrevive al cierre de la app.

---

## 12. Reconciliación con los otros documentos

Este doc **redefine** dos piezas que otros documentos ya habían fijado. Hay
que actualizarlos:

| Documento | Qué dice | Qué pasa con este doc |
|---|---|---|
| `domain/07-anuncios.md` §5.2 | Define `LocalNotification` (en `kitoru.platform`) y `LocalNotifier.post(...)` | **Reemplazado**: la entidad pasa a llamarse `Notification` (misma capa, `kitoru.platform`) y `post` pasa a llamarse `show`. `LocalNotification` desaparece: mantener los dos tipos garantiza que se desincronicen. Los campos `courseId`/`targetId` pasan a `courseId`/`deepLink` |
| `student/06-home-anuncios.md` §8.1 | Define `LocalNotification`, `NotificationTarget`, `NotificationTargetKind` y un `NotificationCenter` que es sólo un `SharedFlow` de destinos | **Ampliado**: `NotificationTarget` → `DeepLink` (con serialización a URI); el `NotificationCenter` pasa a ser el publicador + el store + el ruteador de deep links. Actualizar las referencias de §8.3, §8.4 y §11.1 |
| `student/05-tareas.md` §8.3 | `AssignmentReminder` + `AssignmentReminderRepository` como mecanismo de dedup | **Reemplazado** por el ledger (`NotificationLedgerRepository`): el id `tarea:<id>:<umbral>` ya es la clave y el ledger la recuerda. La tabla `assignment_reminders.json` no se crea. El resto del servicio (`AssignmentNotifier.notifyDue`) se reescribe como `NotificationPolicy.deadlineSpecs` + `NotificationEvaluator` |
| `teacher/10-informes.md` §7 | El resumen semanal se genera con un `Worker` de 7 días en Android y un planificador en desktop | **Compatible**: el `WeeklyReportWorker` publica una notificación `WEEKLY_REPORT` a través del mismo `NotificationCenter`, y el planificador de desktop es el `NotificationScheduler` de §8.3 |
| `student/06-home-anuncios.md` §8.4 | `setAppBadge(count)` en `ui/platform` | **Se reusa** tal cual (§5.2) |

Lo que **no** cambia: `Announcement`, `AnnouncementRead`, `Audience`,
`Assignment`, `DeadlineMath.daysUntil`, el patrón `File*Repository` sobre
`JsonListStore`, y la convención de `expect`/`actual` del proyecto.

---

## 13. Fuera de alcance (explícito)

- **Push del servidor** (FCM/APNs) y notificaciones de escritorio nativas
  (Electron-style). Requieren servidor y no son portables.
- **Un daemon de escritorio** para notificar con la app cerrada. Es otro
  proyecto (`process/02-roadmap.md`).
- **La ruta D-Bus en Linux** (§4.5): especificada, no implementada. Entra
  después de que el centro interno esté sólido.
- **Bridging nativo a `UNUserNotificationCenter` en macOS**: el `.app` bundle
  y el puente Objective-C son trabajo de packaging.
- **Notificaciones por correo**: el canal institucional es otro sistema.
- **Emoji/reacciones** sobre las notificaciones.
- **Suscripción por etiqueta** ("quiero sólo los de examen").
- **Recordatorios de eventos del calendario** (clases): el modelo de tareas
  no tiene horario de clase.
- **Configurar la importancia de los canales desde la app**: Android congela
  la importancia de un canal; sólo se puede desde los ajustes del sistema.
