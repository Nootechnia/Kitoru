# 07 · Avisos y comunicaciones de la cátedra

> Subsistema de anuncios: qué es un aviso, a quién va, cómo lo leen, cómo lo
> redacta el modelo, cómo se notifica y cómo se discute.

## 0. Decisiones en una línea

| Decisión | Respuesta | Motivo |
|---|---|---|
| Subgrupos de alumnos | **No** (sólo `ALL`, `STUDENTS`, `STAFF`) | El dominio no tiene grupos; agregarlos arrastra ciclo de vida de membresías para un caso que se resuelve con 3 valores. |
| Marcar como leído | **Sí** (`AnnouncementRead`) | Un aviso de cancelación que nadie leyó es un aviso que no sirvió. Es metadato del curso, no del chat del alumno: no contradice la regla de visibilidad. |
| IA redactando avisos | **Sí**, con `aiGenerated = true` | Reutiliza el RAG que ya existe (`KnowledgeBase` + `Bm25Index`); el docente edita antes de publicar. |
| Push con la app cerrada | **Android sí** (`NotificationManager` + polling en background). **Desktop: no** sin daemon; bandeja del sistema sólo con la app viva | FCM/daemon exceden el alcance; el núcleo portable es la bandeja dentro de la app, que se deriva de `AnnouncementRead`. |
| Comentarios | **Sí**, un solo nivel, con moderación | Es el canal real docente-alumno ("¿se cancela?"). Sin comentarios, 40 mensajes privados idénticos. |

---

## 1. Entidad `Announcement`

Archivo: `core/src/commonMain/kotlin/kitoru/domain/Announcement.kt`

```kotlin
@Serializable
data class Announcement(
    val id: String,
    val courseId: String,
    /** Título corto: "Clase del martes cancelada". */
    val title: String,
    /** Cuerpo en Markdown. Es lo que renderiza la UI. */
    val body: String = "",
    /** Id del autor (docente). */
    val authorId: String = "",
    /** Nombre del autor al publicar (copia: el perfil puede cambiar después). */
    val authorName: String = "",
    /** Publicación. */
    val createdAt: Long = 0L,
    /** Última edición (0 = nunca editado). */
    val updatedAt: Long = 0L,
    /** A quién va dirigido. */
    val audience: Audience = Audience.ALL,
    /** Aviso fijado: va arriba de la lista, aunque el alumno ya lo leyó. */
    val pinned: Boolean = false,
    /** Materiales adjuntos (referencia al material de la cátedra). */
    val attachments: List<AnnouncementAttachment> = emptyList(),
    /** El texto fue generado con asistencia de IA (registro, no presentación). */
    val aiGenerated: Boolean = false,
    /** Modelo que generó el borrador (ej. "llama-3.1-8b-instruct"). */
    val aiModel: String = "",
    /** Instrucción que el docente dio al modelo (auditoría; NO es el prompt completo). */
    val aiPrompt: String = "",
    /** Si los alumnos pueden comentar este aviso. */
    val commentsEnabled: Boolean = true,
)
```

### Campos, campo por campo

| Campo | Tipo | Notas de diseño |
|---|---|---|
| `id` | `String` | Mismo criterio que `ChatThread`/`ChatMessage`: `"curso\|anuncio\|uuid"` para que el filtro por prefijo funcione en los repositorios de archivo (ver `FileMessageRepository.listForCourse`). |
| `courseId` | `String` | Toda la cátedra. |
| `title` | `String` | Obligatorio, ≤ 120 caracteres. Se muestra en la lista y en la notificación (el título de la notificación **es** el título del aviso). |
| `body` | `String` | Markdown. Es el cuerpo completo; el render es de la capa `:ui` (no hay renderer en `:core`). Sin soporte de HTML ni de adjuntos binarios: los adjuntos son **materiales de la cátedra**, no archivos arbitrarios. |
| `authorId` / `authorName` | `String` | Copia del nombre al publicar, igual que `ChatThread.authorName`: si el docente cambia su nombre, los avisos viejos siguen mostrando el nombre con el que se publicaron (trazabilidad). |
| `createdAt` / `updatedAt` | `Long` | Epoch millis (`kitoru.platform.nowMillis()`). `updatedAt` permite mostrar el chip "editado" y detectar cambios en la sincronización. |
| `audience` | `Audience` | Ver §2. |
| `pinned` | `Boolean` | Los fijados van primero y quedan arriba de la bandeja del alumno aunque ya los leyó. Sirve para el aviso de modalidad de examen. |
| `attachments` | `List<AnnouncementAttachment>` | Referencia a `Material` por id + copia de título/ruta/tipo para pintar la lista sin hacer el join. |
| `aiGenerated` | `Boolean` | **Registro obligatorio**: el texto nació de una IA. Sobrevive a que el docente reescriba todo. |
| `aiModel` | `String` | Modelo concreto (`modelPolicy.modelName` o `modelLabel`). Permite auditar "¿con qué modelo se redactó?". |
| `aiPrompt` | `String` | La instrucción en lenguaje natural del docente ("redactá un aviso de cancelación para mañana"). No se guarda el prompt completo con el material inyectado: serían KB de texto por aviso. |
| `commentsEnabled` | `Boolean` | El docente puede cerrar las preguntas en un aviso (típico: "se cancela", todos preguntan lo mismo y el docente responde 40 veces). |

### `AnnouncementAttachment`

```kotlin
@Serializable
data class AnnouncementAttachment(
    /** Id del material referenciado. */
    val materialId: String,
    /** Título para mostrar (copia del material al adjuntarlo). */
    val title: String = "",
    /** Ruta relativa dentro de la carpeta de la cátedra. */
    val path: String = "",
    val kind: MaterialKind = MaterialKind.DOCUMENT,
)
```

Adjuntar un material es una **referencia**, no una copia: el material ya vive en
`MaterialRepository` y en la carpeta de la cátedra. Adjuntar material invisible
(`visible = false`) no lo puede hacer el docente (el selector de adjuntos sólo
ofrece visibles).

### Validación (estilo `ModelPolicy.validate()`)

```kotlin
fun validate(): List<String> {
    val errors = mutableListOf<String>()
    if (title.isBlank()) errors += "El aviso necesita un título."
    if (title.length > MAX_TITLE) errors += "El título no puede superar los $MAX_TITLE caracteres."
    if (body.isBlank()) errors += "El aviso necesita un cuerpo."
    if (body.length > MAX_BODY) errors += "El cuerpo no puede superar los $MAX_BODY caracteres."
    if (aiGenerated && aiModel.isBlank()) errors += "Un aviso generado con IA debe registrar el modelo que lo redactó."
    return errors
}
```

`MAX_TITLE = 120`, `MAX_BODY = 8_000`. Mensajes en español, fail-fast, igual que
`ModelPolicy.validate()`.

### Estados derivados (sin campos nuevos)

- `isEdited: Boolean` → `updatedAt > createdAt + 60_000` (tolerancia de 1 min
  para no marcar como "editado" un guardado inmediatamente posterior a publicar).
- `hasAiCredit` → `aiGenerated`.
- El badge de "no leído" **no** vive en `Announcement`: se deriva de
  `AnnouncementRead` (§3). Una sola fuente de verdad.

---

## 2. `Audience`: a quién va dirigido

```kotlin
@Serializable
enum class Audience(val label: String, val description: String) {
    /** Todos los inscritos: alumnos y docentes. */
    ALL("Toda la cátedra", "Lo ven todos los inscriptos: alumnos y docentes."),

    /** Sólo alumnos. */
    STUDENTS("Sólo alumnos", "Lo ven únicamente los alumnos inscriptos."),

    /** Sólo docentes (titular y ayudantes). */
    STAFF("Sólo docentes", "Lo ven el titular y los ayudantes; los alumnos no lo ven."),
    ;

    /** ¿Una persona con este rol debe ver el aviso? */
    fun includes(role: CourseRole): Boolean = when (this) {
        ALL -> true
        STUDENTS -> role == CourseRole.ALUMNO
        STAFF -> role != CourseRole.ALUMNO
    }

    /** ¿Una inscripción concreta debe ver el aviso? (null = no está inscripto) */
    fun matches(enrollment: Enrollment?): Boolean =
        enrollment != null && includes(enrollment.role)

    companion object {
        fun fromName(name: String): Audience? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
```

### ¿Hacen falta subgrupos? **No, en esta versión.**

El razonamiento, con el código a la vista:

1. El dominio **no tiene la noción de grupo**. `Enrollment(id, courseId, userId,
   displayName, role, joinedAt, visibility)` describe persona + rol. No hay
   "comisión", "turno", "grupo de taller" ni membresías. Agregar subgrupos
   implica inventar `Group(id, courseId, name)` + `GroupMember`, con su ciclo de
   vida: ¿qué pasa con el alumno que se inscribe después? ¿se lo agrega a mano a
   cada grupo? ¿el aviso al grupo pierde sentido?
2. El caso real que suele pedirlo es "turno A / turno B". Eso se resuelve con
   **dos avisos** (uno por turno) mientras no haya grupos. El costo de dos
   avisos es un click; el costo de un sistema de grupos es un modelo entero,
   su UI, su sincronización y sus bugs.
3. La alternativa "subgrupo ad-hoc por aviso" (una lista de `userIds` en el
   aviso) es mala: es una caja de "¿elegí 40 de 200" en la UI, deja ids
   colgando cuando alguien se da de baja, y no escala a la nada.
4. Con 3 valores se cubre el ~100 % de lo que una cátedra necesita:
   "se cancela la clase" (`ALL`), "la entrega de la TP2" (`STUDENTS`),
   "reunión de cátedra" (`STAFF`).

**Cómo se extiende el día que haga falta** (sin romper los datos ya guardados):
`KitoruJson` tiene `ignoreUnknownKeys = true`, pero un enum desconocido sí
rompe el decode. La migración sería reemplazar el enum por una `sealed interface
Audience { All; Students; Staff; Groups(ids: Set<String>) }` con un
`@Serializable` propio; los archivos viejos con `"ALL"` siguen deserializando
porque el `JsonNames`/discriminador se puede mapear. Se deja anotado en el
código, no se implementa.

### Reglas de visibilidad (coherentes con la matriz de CONTEXTO)

- La visibilidad de **avisos** es independiente de `ChatVisibility`: un aviso
  es comunicación de la cátedra, no contenido del chat del alumno. El docente
  puede ver quién leyó **sin** que eso le dé acceso al texto de los chats: es
  la misma categoría de metadato que la analítica de citas.
- Un alumno sólo ve los avisos donde `audience.matches(inscripción)`.
- Un aviso `STAFF` **no existe** para el alumno: el filtro se aplica al
  listar (repositorio/UI), no al pintar. Un cliente filtrado no debe recibir
  siquiera el `body` de un aviso de docentes (en el futuro, el servidor tampoco
  lo envía).

---

## 3. Lecturas: `AnnouncementRead`

**¿Vale la pena? Sí.** El caso de uso concreto es el aviso de cancelación: el
docente necesita saber si el mensaje llegó. Y el costo es bajo: un registro por
(aviso, persona).

```kotlin
@Serializable
data class AnnouncementRead(
    val id: String,
    val announcementId: String,
    val courseId: String,
    val userId: String,
    /** Nombre copiado al leer (para la lista del docente sin resolver perfiles). */
    val userName: String = "",
    /** Momento de la **primera** lectura. No cambia al releer. */
    val readAt: Long = 0L,
)
```

### Reglas

- **Idempotente**: `markRead` hace upsert por `(announcementId, userId)` y
  **conserva el `readAt` más antiguo**. Releer no pisa la fecha original
  ("lo leyó el martes", no "lo leyó hoy").
- **Se marca al abrir el aviso**, no al verlo en la lista. Marcar en la lista
  haría que un scroll marcara todo como leído.
- **Un alumno que se inscribe después** no ve como "nuevo" un aviso anterior a
  su `joinedAt`: el contador de pendientes sólo cuenta avisos con
  `createdAt >= enrollment.joinedAt`. Los anteriores se listan igual, marcados
  como históricos.
- **Al borrar un aviso** se borran sus lecturas y sus comentarios ( cascada en
  el repositorio).
- El docente ve **el número y la lista de nombres** de quienes leyeron. No ve
  *cuándo lo releyeron* ni cuántas veces: no hay dato de eso (el `readAt` es
  inmutable). Es una decisión de privacidad explícita: el dato es "lo leyó",
  no "lo está mirando".

### Lo que ve cada uno

| Vista | Contenido |
|---|---|
| Alumno | "Sin leer" / "Leído el martes 21/10" (sólo lo suyo). |
| Docente | "Leído por 37 de 41" + la lista de nombres (los que no leyeron quedan en la lista de "pendientes"). |
| Docente (audiencia `STAFF`) | Lo mismo, pero sólo con los docentes. |

---

## 4. Anuncios generados por la IA

### 4.1 Flujo de usuario

```
Pantalla "Nuevo aviso" (docente)
  └─ botón "Generar con IA"
       └─ diálogo: ¿qué querés que diga?  [redactá un aviso de cancelación para mañana]
          + audience (prelleno con el del formulario)  + tono (formal/informal, opcional)
          + [Usar el material de la cátedra] (switch, por defecto ON)
       └─ el modelo redacta (spinner, ~2-8 s)
       └─ el editor se abre con el borrador: título y cuerpo PRELLENADOS
          + banner: "Borrador generado con IA. Revisalo y editá antes de publicar."
          + chip "Generado con IA" (no se quita aunque reescribas todo)
          + botones: [Regenerar] [Descartar] [Publicar]
       └─ el docente edita (Markdown con vista previa)
       └─ "Publicar" → Announcement(aiGenerated = true, aiModel = ..., aiPrompt = ...)
```

Reglas duras del flujo:

1. **Nada se publica solo.** El borrador nunca se persiste como `Announcement`
   hasta que el docente aprieta "Publicar". No hay publicación automática ni
   "publicar con IA" en un solo paso.
2. **El crédito va en el cuerpo.** Al publicar, la app agrega (de forma
   determinística, no el modelo) una línea al final del cuerpo:

   ```markdown
   > _Generado con asistencia de IA (modelo «llama-3.1-8b-instruct») y revisado por el docente._
   ```

   El modelo **no** escribe el crédito (se lo pedimos explícitamente en el
   prompt: "el crédito lo agrega la app, no lo escribas vos"). Así no se puede
   "olvidar" de ponerlo.
3. **Sí queda registrado**: `aiGenerated = true`, `aiModel` (el modelo real
   usado) y `aiPrompt` (la instrucción del docente). El flag es el *registro*;
   el crédito en el cuerpo es la *presentación*. Si el docente borra a mano la
   línea de crédito, el registro sigue diciendo la verdad.
4. **El modelo que redacta es el de la cátedra** (`course.modelPolicy`), no el
   override del alumno: el docente escribe con el servidor de la institución
   (REMOTE). Si la `modelPolicy` no valida, el botón queda deshabilitado con el
   mensaje de `ModelPolicy.validate()` (fail-fast, en español).
5. **Si el modelo falla** (timeout, 401, 429), el error se muestra tal cual
   (`LlmException` ya trae mensajes en español) y **el docente puede seguir
   escribiendo a mano**: el aviso manual nunca queda bloqueado por la IA.

### 4.2 El prompt

El contexto de la cátedra se arma en `:backend`, reusando el RAG que ya existe
(`KnowledgeBase` + `Bm25Index` + `CourseMaterialSource`): los fragmentos
recuperados son los mismos que usa el chat del alumno.

Sistema (constante del módulo, con la cátedra inyectada):

```
Sos el redactor de la cátedra «{course.shortName}» (cursada {course.term}).
Escribís avisos para los estudiantes (y a veces para el equipo docente).

Reglas:
- Respondé en español rioplatense neutro, como el resto de la cátedra.
- Devolvé ÚNICAMENTE un JSON válido con esta forma:
  {"title": "...", "body": "..."}
  sin texto antes ni después, sin bloques de código y sin explicaciones.
- El body es Markdown: usá **negrita**, listas y citas (> ) si aportan.
- Máximo 120 palabras en el body. Título de máximo 80 caracteres.
- No inventes fechas, horarios, enlaces, notas, bibliografía ni nombres.
  Si te falta un dato, dejá un placeholder entre corchetes: [hora de la clase].
- El crédito de IA lo agrega la app: no lo escribas vos.
```

Usuario (bloque con el contexto):

```
CÁTEDRA
- Nombre: {course.name}
- Código: {course.code}
- Cursada: {course.term}
- Fecha de hoy: {context.todayLabel}
- Autor del aviso: {context.authorName}
- Destinatarios: {audience.label}
- Material de la cátedra: {títulos de los materiales visibles}

INSTRUCCIONES DE LA CÁTEDRA
{course.systemPrompt}

MATERIAL RELEVANTE (fragmentos del corpus de la cátedra)
[1] (cátedra: Apuntes — Parcial 1) {texto del chunk}
[2] ...

INSTRUCCIÓN DEL DOCENTE
{instruction}
```

Si la cátedra no tiene material indexado, el bloque dice
`(sin material indexado)` y el modelo redacta sólo con lo que el docente escribió.

### 4.3 Contrato de salida y parseo

El modelo devuelve JSON. El parseo es **puro y testeable** (regla 3 del
proyecto: lo que no depende del modelo es testeable):

```kotlin
data class AnnouncementDraft(val title: String, val body: String)

object AnnouncementDraftParser {
    /** Extrae el primer objeto JSON del texto y lee title/body. null si no hay. */
    fun parse(raw: String): AnnouncementDraft?
}
```

Tolerancias: acepta el JSON envuelto en un bloque ```` ```json ````, acepta
`"cuerpo"` como alias de `"body"`, y si el JSON no parsea devuelve `null`
(la UI avisa "el modelo no devolvió un borrador usable" y deja escribir a mano).
Nunca lanza.

### 4.4 Dónde vive el código

`backend/src/commonMain/kotlin/rag/announce/AnnouncementWriter.kt`

```kotlin
/** Contexto de la cátedra para redactar un aviso. */
data class AnnouncementContext(
    val course: Course,
    val authorName: String,
    val audience: Audience,
    val materials: List<Material>,
    /** Fragmentos recuperados del corpus (los aporta el llamador o el KB). */
    val chunks: List<Chunk>,
    /** Fecha ya formateada por la plataforma ("miércoles 21 de octubre de 2026"). */
    val todayLabel: String,
)

class AnnouncementWriter(
    private val kb: KnowledgeBase,
    private val llm: LlmGateway,
    private val topK: Int = 5,
) {
    /** Recupera del corpus y redacta. Lanza [LlmException] si el modelo falla. */
    suspend fun draft(ctx: AnnouncementContext, instruction: String): AnnouncementDraft
}
```

La fecha llega **formateada desde afuera** (`todayLabel`): `commonMain` no puede
usar `java.time` (API 26 en Android, `minSdk = 24`), así que el formateo es de
la plataforma y el prompt builder sigue siendo puro.

`AnnouncementPrompt.build(ctx, instruction): List<ChatMessage>` es una función
pura → se testea sin modelo.

### 4.5 Lo que NO se hace

- **No se verifican citas** con `CitationVerifier`: un aviso no cita fuentes y
  el verificador mide citas textuales. Lo que protege la honestidad del texto es
  (a) el prompt que prohíbe inventar datos, (b) el placeholder `[hora]` en vez
  de inventar, y (c) la revisión obligatoria del docente.
- **No se publica sin revisión** (§4.1, regla 1).
- **No se manda el material completo**: sólo `topK` fragmentos (5 por defecto),
  para no llenar la ventana de contexto.

---

## 5. Notificaciones push locales

### 5.1 La verdad incómoda

El almacenamiento actual es **local por dispositivo** (un JSON por tipo en
`config/`). Un aviso no "llega" solo: alguien tiene que traerlo. Hoy eso
significa el bundle de la cátedra (export/import) y mañana el Kitoru-Server.
Por lo tanto:

> La notificación local no es el transporte: es **la señal visible de que
> llegó algo nuevo**. El aviso llega al store local por el canal que exista
> (import hoy, servidor mañana), y en el momento en que se escribe en el store
> se dispara la notificación.

Por eso la regla de implementación es:

> **Cualquiera que escriba un aviso nuevo en el store (salvo el propio docente
> que lo está redactando) dispara la notificación local.**

### 5.2 La abstracción portable

`core/src/commonMain/kotlin/kitoru/platform/Notifier.kt`

```kotlin
package kitoru.platform

/** Notificación local del dispositivo. */
data class LocalNotification(
    /** Id estable (permite reemplazar/cancelar). */
    val id: String,
    val title: String,
    val body: String,
    /** Cátedra (para agrupar y para el deep link). */
    val courseId: String = "",
    /** Recurso a abrir al tocar: id de aviso o de comentario. */
    val targetId: String = "",
)

/** Publica notificaciones locales. Una implementación por plataforma. */
interface LocalNotifier {
    /** ¿El usuario habilitó las notificaciones? (permiso, ajustes del SO) */
    fun areEnabled(): Boolean
    /** Publica (o reemplaza) una notificación. */
    fun post(notification: LocalNotification)
    /** Cierra una notificación ya publicada. */
    fun cancel(id: String)
}

expect fun createLocalNotifier(): LocalNotifier
```

Núcleo portable en `commonMain`, que decide **qué** notificar:

`core/src/commonMain/kotlin/kitoru/domain/AnnouncementNotifier.kt`

```kotlin
class AnnouncementNotifier(
    private val announcements: AnnouncementRepository,
    private val reads: AnnouncementReadRepository,
    private val notifier: LocalNotifier,
) {
    /**
     * Notifica los avisos que el usuario todavía no leyó.
     * Devuelve cuántos notificó. No vuelve a notificar lo ya leído.
     */
    suspend fun notifyPending(course: Course, enrollment: Enrollment): Int
}
```

Reglas de `notifyPending`:

- filtra por `audience.matches(enrollment)`;
- saltea los que ya tienen registro en `AnnouncementRead` (no se re-notifica);
- saltea los anteriores a `enrollment.joinedAt` (§3);
- el texto de la notificación es `title` + los primeros ~140 caracteres del
  `body` en plano (el Markdown se muestra crudo; sin renderer en `:core`);
- `id = "anuncio:${announcement.id}"` → el mismo aviso no genera dos
  notificaciones.

### 5.3 Android

`core/src/androidMain/kotlin/kitoru/platform/Notifier.android.kt`

- Canal único `kitoru_avisos` ("Avisos de la cátedra"), `IMPORTANCE_DEFAULT`,
  creado de forma perezosa.
- `NotificationManager.notify(id, builder.build())` con un `PendingIntent` que
  abre la app en el detalle del aviso (deep link por `targetId`).
- Permiso `POST_NOTIFICATIONS` (API 33+): la app lo pide al arrancar (o la
  primera vez que va a notificar). En API 24-32 el permiso es implícito.
- Dependencia a agregar en `:core`: `androidx.core:core-ktx` (para
  `NotificationCompat`, que funciona desde API 14 y evita el `if (SDK >= 26)`
  con dos caminos de construcción).
- El contexto de la aplicación se inyecta al arrancar, igual que
  `KitoruPaths.setDataDir(...)`:
  `KitoruAndroid.init(context)` en `androidMain`.

**App cerrada**: con polling en background es posible.
`androidx.work:work-runtime-ktx` + un `PeriodicWorkRequest` (intervalo mínimo
15 min) que llama al cliente del Kitoru-Server, guarda lo nuevo en el store
local y postea la notificación. Mientras no exista servidor, ese worker es un
stub que no hace nada. **FCM / push real queda fuera de alcance**: requiere
servidor, cuenta de Firebase y no es portable.

### 5.4 Desktop

`core/src/desktopMain/kotlin/kitoru/platform/Notifier.desktop.kt`

- `java.awt.SystemTray`: si `SystemTray.isSupported()`, se instala un `TrayIcon`
  con el ícono de la app y `displayMessage(title, body, MessageType.INFO)`
  cuando llega un aviso. Es el "globo" de la bandeja del sistema.
- Si **no** hay bandeja (Linux sin tray, RDP, servidores), `post` es un no-op
  silencioso: la app muestra el banner interno (§5.5).
- **App cerrada**: no hay notificación. No hay daemon y no se va a agregar uno
  en esta versión. Lo que sí se ofrece es **modo bandeja**: cerrar la ventana
  minimiza a la bandeja y el proceso sigue vivo, sonando los avisos nuevos
  mientras el alumno trabaja en otra cosa. Decir "push con la app cerrada" en
  desktop sería falso; la verdad es "con la app viva, minimizada a la bandeja".

### 5.5 La bandeja dentro de la app (el núcleo portable)

Es lo que funciona **igual en las dos plataformas** y no necesita permiso del
sistema operativo:

- Pantalla **"Avisos"** de la cátedra: lista de avisos, con los fijados arriba,
  los no leídos destacados y un badge con el conteo.
- El badge se **deriva de los datos** (`AnnouncementRead` + `Audience`), no de
  un contador paralelo: no puede desincronizarse.
- BannerEmergent cuando entra un aviso con la app abierta (Desktop y Android).
- El aviso se marca leído al abrirlo (§3).

Resumen de portabilidad:

| Mecanismo | Desktop | Android | ¿Portable? |
|---|---|---|---|
| Bandeja dentro de la app + badge | ✅ | ✅ | ✅ (es la base) |
| Banner emergente en la app | ✅ | ✅ | ✅ |
| Notificación del sistema | ✅ bandeja del sistema (si existe) | ✅ `NotificationManager` | ⚠️ dos adaptadores finos sobre `LocalNotifier` |
| Con la app **cerrada** | ❌ (salvo modo bandeja) | ✅ con `WorkManager` + servidor | ❌ por ahora |
| Push del servidor (FCM) | ❌ | ❌ (fuera de alcance) | ❌ |

---

## 6. Comentarios en los anuncios

**¿Sí o no? Sí**, pero acotados: son el canal de preguntas del alumno y la
respuesta del docente. Sin ellos, el docente recibe 40 consultas privadas
idénticas.

Modelo:

```kotlin
@Serializable
data class AnnouncementComment(
    val id: String,
    val announcementId: String,
    val courseId: String,
    val authorId: String,
    val authorName: String = "",
    val authorRole: CourseRole = CourseRole.ALUMNO,
    /** Cuerpo en Markdown. */
    val body: String = "",
    val createdAt: Long = 0L,
    /** Id del comentario al que responde (un solo nivel de anidamiento). */
    val parentId: String? = null,
    /** Si el texto fue generado con IA (respuesta sugerida al docente). */
    val aiGenerated: Boolean = false,
) {
    val isReply: Boolean get() = parentId != null
}
```

### Reglas

- **Un solo nivel**: un comentario puede responder a otro comentario
  (`parentId`), pero no a una respuesta. La vista es un hilo plano: pregunta +
  respuestas.
- **Visibles para toda la cátedra**: el alumno que pregunta y los demás alumnos
  ven las respuestas. Es lo que evita la multiplicación de consultas privadas y
  lo que hace útil el aviso como FAQ de la cursada. (No hay comentarios
  anónimos en esta versión: en un foro de 200 personas el anonimato sólo
  habilita el abuso. Es un no-goal explícito.)
- **El docente modera**: puede borrar cualquier comentario; el alumno puede
  borrar el propio mientras nadie le haya respondido.
- **El docente puede cerrar las preguntas** por aviso: `commentsEnabled = false`
  (además de poder editar el aviso después).
- **Límites**: cuerpo ≤ 2000 caracteres, 1 comentario cada 30 s por cliente
  (el servidor, cuando exista, impone el límite real), máximo 200 comentarios
  por aviso (los más antiguos se podan).
- **Notificación**: cuando el docente responde un comentario de un alumno, ese
  alumno recibe una notificación local (mismo `LocalNotifier`,
  `id = "comentario:${comment.id}"`, deep link al hilo).
- **IA para responder** (opcional, mismo servicio): el docente puede pedir
  "redactá una respuesta para la pregunta X" y el modelo usa el material de la
  cátedra. El borrador **nunca** se postea solo: se edita y se publica a mano;
  si se usó IA, el comentario queda con `aiGenerated = true` y lleva el mismo
  crédito al final del cuerpo.

### Interacción en la UI (resumen)

```
[Anuncio: "Clase del martes cancelada"]        [fijado]
  Martes 21/10 · Prof. Ana Gómez · Toda la cátedra
  <markdown>

  💬 3 comentarios
  ┌──────────────────────────────────────────────┐
  │ Alumno: "¿La TP2 también se corre?"           │
  │   ↳ Docente: "Sí, corre el viernes."         │
  │   ↳ Alumno: "Gracias"                        │
  └──────────────────────────────────────────────┘
  [Escribir una pregunta…            ] [Enviar]
```

---

## 7. Persistencia

Tres archivos JSON en `config/`, siguiendo el patrón de `JsonListStore`:

| Archivo | Contenido | Repositorio |
|---|---|---|
| `announcements.json` | `List<Announcement>` | `AnnouncementRepository` |
| `announcement_reads.json` | `List<AnnouncementRead>` | `AnnouncementReadRepository` |
| `announcement_comments.json` | `List<AnnouncementComment>` | `AnnouncementCommentRepository` |

Interfaces (en `Repositories.kt`, junto a las existentes):

```kotlin
interface AnnouncementRepository {
    suspend fun listForCourse(courseId: String): List<Announcement>
    suspend fun get(id: String): Announcement?
    suspend fun save(announcement: Announcement)
    suspend fun delete(id: String)          // cascade: borra lecturas y comentarios
}

interface AnnouncementReadRepository {
    suspend fun listForAnnouncement(announcementId: String): List<AnnouncementRead>
    suspend fun listForCourse(courseId: String): List<AnnouncementRead>
    /** Ids de usuario que leyeron el aviso (para el badge y el contador). */
    suspend fun readUserIds(announcementId: String): Set<String>
    /** Upsert idempotente; conserva el `readAt` más antiguo. */
    suspend fun markRead(announcement: Announcement, user: UserProfile, at: Long = nowMillis())
    suspend fun deleteForAnnouncement(announcementId: String)
}

interface AnnouncementCommentRepository {
    suspend fun listForAnnouncement(announcementId: String): List<AnnouncementComment>
    suspend fun listForCourse(courseId: String): List<AnnouncementComment>
    suspend fun get(id: String): AnnouncementComment?
    suspend fun save(comment: AnnouncementComment)
    suspend fun delete(id: String)
    suspend fun deleteForAnnouncement(announcementId: String)
}
```

Implementaciones `File*Repository` sobre `FileBackedRepository` (mismo patrón que
`FileMaterialRepository`): `listForCourse` filtra por `courseId` y ordena por
`createdAt` descendente, con los `pinned` primero.

**Escala**: 200 alumnos × 30 avisos = 6000 registros de lectura en un JSON.
Aceptable para el milestone 1 (los chats ya generan miles de mensajes). Cuando
moleste, `announcement_reads.json` pasa a SQLite o a un store por cátedra
(`courses/<id>/reads.json`).

---

## 8. Casos borde

| Caso | Comportamiento |
|---|---|
| El alumno se inscribe después de publicado el aviso | Lo ve (si `audience` lo incluye) pero **no** cuenta como pendiente ni dispara notificación (`createdAt < joinedAt`). |
| El docente edita un aviso ya publicado | `updatedAt` cambia; los alumnos ven el chip "editado"; los que lo habían leído siguen con el badge de leído, pero el aviso vuelve a la lista si estaba fijado. |
| Aviso `STAFF` y el filtro | El cliente del alumno **no lo recibe** (ni el body). Nunca se filtra "en pantalla". |
| Se borra un aviso | Cascade: se borran lecturas y comentarios. |
| Dos avisos con el mismo título | Se permiten; el id los distingue. |
| El modelo devuelve basura | `AnnouncementDraftParser.parse` devuelve `null` → la UI avisa y deja escribir a mano. |
| El modelo tarda o falla | `LlmException` con mensaje en español; el borrador manual sigue disponible. |
| La cátedra no tiene modelo configurado | El botón "Generar con IA" se deshabilita con el mensaje de `ModelPolicy.validate()`. |
| El alumno tiene las notificaciones del SO apagadas | `LocalNotifier.areEnabled() == false` → no se postea nada, pero el badge interno sigue funcionando (de ahí la importancia del núcleo portable). |
| Un docente publica para `STAFF` | No aparece en la lista del alumno; en la del docente sí, con la etiqueta "Sólo docentes". |

---

## 9. Archivos a crear o tocar

**Nuevos:**
- `core/src/commonMain/kotlin/kitoru/domain/Announcement.kt` — `Announcement`,
  `Audience`, `AnnouncementAttachment`, `AnnouncementRead`, `AnnouncementComment`.
- `core/src/commonMain/kotlin/kitoru/domain/AnnouncementNotifier.kt` — el servicio
  que decide qué notificar.
- `core/src/commonMain/kotlin/kitoru/platform/Notifier.kt` — `LocalNotification`,
  `LocalNotifier`, `expect createLocalNotifier()`.
- `core/src/androidMain/kotlin/kitoru/platform/Notifier.android.kt` —
  `NotificationManager`.
- `core/src/desktopMain/kotlin/kitoru/platform/Notifier.desktop.kt` —
  `SystemTray`.
- `backend/src/commonMain/kotlin/rag/announce/AnnouncementWriter.kt` — contexto,
  prompt, writer y parser.

**Modificados:**
- `core/src/commonMain/kotlin/kitoru/domain/Repositories.kt` — 3 interfaces.
- `core/src/commonMain/kotlin/kitoru/domain/FileRepositories.kt` — 3 impls `File*`.
- `core/build.gradle.kts` — `androidx.core:core-ktx` (notificaciones Android).
- `gradle/libs.versions.toml` — versiones de `androidx.core` y
  `androidx.work` (este último, sólo cuando exista el servidor).

**Tests:**
- `core/src/desktopTest/kotlin/kitoru/domain/AnnouncementTest.kt` —
  `validate()`, `Audience.includes/matches`, upsert de lectura idempotente,
  cascada de borrado.
- `backend/src/desktopTest/kotlin/rag/announce/AnnouncementDraftParserTest.kt` —
  parseo con JSON limpio, con fences, con fences y texto extra, JSON inválido,
  alias `cuerpo`.
- `backend/src/desktopTest/kotlin/rag/announce/AnnouncementPromptTest.kt` — el
  prompt incluye el contexto de la cátedra y **no** filtra material invisible.

---

## 10. Fuera de alcance (explícito)

- Subgrupos de alumnos / grupos y turnos (§2).
- Push real del servidor (FCM/APNs) y notificaciones cuando la app está cerrada
  en desktop.
- Borrador persistente de un aviso (la edición ocurre en el formulario, antes
  de crear la entidad).
- Anónimos en los comentarios.
- Reacción (emoji) a los avisos.
- Suscripción a avisos por etiqueta ("quiero sólo los de examen").
- Notificaciones por correo (el canal institucional es otro sistema).
- Exportar un aviso a PDF / calendario (.ics) — interesante para el aviso de
  examen, pero es otro subsistema.
