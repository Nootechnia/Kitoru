# 08 · Pantalla de gestión de alumnos (inscripciones) — vista docente

> Documento de diseño. Subtarea: **diseño de la pantalla de alumnos del docente**.
> Depende de: `docs/design/CONTEXTO.md`, `core/.../domain/Roles.kt`,
> `core/.../domain/Course.kt`, `core/.../domain/Analytics.kt`,
> `core/.../domain/AnalyticsAnalyzer.kt`, `core/.../domain/FileRepositories.kt`.

---

## 0. Resumen en una línea

La pantalla **Alumnos** es el lugar donde el docente ve el padrón de su cátedra, decide
cuánto de cada alumno puede ver (visibilidad), y hace todo el trabajo administrativo
(altas, roles, importación masiva, exportación y supresión de datos) **sin poder
espiar**: toda subida de visibilidad es justificada, registrada y notificada al alumno.

---

## 1. Decisiones de diseño (las 7 que mandan)

| # | Decisión | Por qué |
|---|---|---|
| D1 | **La visibilidad es un `min(lo que pide la cátedra, lo que acepta el alumno)`.** El alumno puede bajarla siempre y para siempre; el docente puede bajarla libremente, pero **subirla exige consentimiento o justificación escrita**. | El docente no puede espiar. La regla es asimétrica a propósito: bajar privacidad nunca necesita permiso; subirla siempre deja rastro. |
| D2 | **El alumno ve su propia configuración de visibilidad**, con una explicación literal de qué ve el docente y qué no. Puede bajarla y puede **pedir** que se suba. | Transparency. El alumno no es un sujeto pasivo de la cátedra. |
| D3 | **Existe un flujo de solicitud de consentimiento** (`VisibilityRequest`): el alumno pide "subí a Completo", el docente aprueba o deniega con motivo, y la decisión queda auditada. | El docente no puede subir la visibilidad de un alumno por su cuenta y en silencio. |
| D4 | **Cada acceso del docente a los chats de un alumno se registra** (`TeacherAccessLog`) y el alumno lo puede ver en su app. | Antidoto del "espiar en silencio". |
| D5 | **Separar "dar de baja" de "borrar datos".** Dar de baja = sale del padrón (conserva datos, queda oculto). Borrar = supresión total (derecho de supresión). | Dar de baja es administrativo; borrar datos es un acto legal con consecuencias. No son lo mismo. |
| D6 | **Un ayudante puede ver y analizar, pero no cambia visibilidad ni borra alumnos.** La visibilidad es territorio exclusivo del titular. | `Roles.canDesign` permite editar materiales; la privacidad es un poder distinto. |
| D7 | **El padrón (nombres, roles, fechas) es visible para todos los docentes; las métricas y el texto dependen de la visibilidad de cada inscripción.** | El `AnalyticsAnalyzer` ya excluye a los `NONE` de los agregados; la UI debe ser coherente con eso, no "más permisiva". |

---

## 2. Cambios de dominio propuestos

Estos cambios son **prerrequisito** de la pantalla. van en `:core` y no rompen nada
existente salvo `AnalyticsAnalyzer`, que hay que tocar para respetar `studentCap`.

### 2.1 `ChatVisibility` gana un nivel y un combinador

`core/src/commonMain/kotlin/kitoru/domain/Roles.kt`

```kotlin
@Serializable
enum class ChatVisibility(
    val label: String,
    val description: String,
    /** 0 = más restrictivo. NONE(0) < METRICS_ONLY(1) < FULL(2). */
    val level: Int,
) {
    FULL(
        "Completo",
        "El docente ve el texto de las conversaciones y el detalle de cada mensaje.",
        level = 2,
    ),
    METRICS_ONLY(
        "Solo métricas",
        "El docente ve estadísticas y calidad de las respuestas, pero no el texto.",
        level = 1,
    ),
    NONE(
        "Oculto",
        "El docente no ve ni el texto ni las estadísticas de este alumno.",
        level = 0,
    ),
    ;

    val showsText: Boolean get() = this == FULL
    val showsMetrics: Boolean get() = this != NONE

    /** El más restrictivo de los dos. Es conmutativo y monótono. */
    fun and(other: ChatVisibility): ChatVisibility =
        if (other.level <= level) other else this

    /** ¿Este nivel da más acceso que [other]? */
    fun isBroaderThan(other: ChatVisibility): Boolean = level > other.level

    companion object {
        fun byLevel(level: Int): ChatVisibility =
            entries.firstOrNull { it.level == level } ?: NONE
    }
}
```

### 2.2 `Enrollment` extendida

`core/src/commonMain/kotlin/kitoru/domain/Course.kt`

```kotlin
/** Cómo entró la persona a la cátedra. */
@Serializable
enum class EnrollmentSource(val label: String) {
    CODE("Código de inscripción"),
    MANUAL("Alta manual"),
    IMPORT("Importación CSV"),
    INVITE("Invitación"),
    SELF("Autoinscripción"),
}

@Serializable
enum class EnrollmentStatus(val label: String) {
    ACTIVE("Activo"),
    SUSPENDED("Suspendido"),   // no puede chatear; conserva sus datos
    REMOVED("Dado de baja"),   // fuera del padrón; conserva sus datos
}

@Serializable
data class Enrollment(
    val id: String,
    val courseId: String,
    val userId: String,
    val displayName: String,
    val role: CourseRole,
    val joinedAt: Long = 0L,

    /** Qué pidió la cátedra. */
    val visibility: ChatVisibility = ChatVisibility.FULL,
    /** Techo que fijó el alumno: puede bajarlo cuando quiera y nunca se sube solo. */
    val studentCap: ChatVisibility = ChatVisibility.FULL,
    /** Consentimiento explícito del alumno sobre su nivel de visibilidad. */
    val consent: VisibilityConsent = VisibilityConsent(),

    val email: String = "",
    val note: String = "",
    val source: EnrollmentSource = EnrollmentSource.CODE,
    val status: EnrollmentStatus = EnrollmentStatus.ACTIVE,
    val invitedBy: String = "",          // userId del docente que lo agregó
    val removedAt: Long = 0L,
) {
    val label: String get() = "$displayName — ${role.label}"

    /** Visibilidad efectiva: el mínimo entre lo pedido y lo que el alumno acepta. */
    val effectiveVisibility: ChatVisibility get() = visibility.and(studentCap)

    /** El docente ve el texto de este alumno. */
    val teacherSeesText: Boolean get() = effectiveVisibility.showsText

    /** El docente ve métricas de este alumno. */
    val teacherSeesMetrics: Boolean get() = effectiveVisibility.showsMetrics

    val isActive: Boolean get() = status == EnrollmentStatus.ACTIVE
}
```

> Nota: `visibility` conserva su significado de "lo que pidió la cátedra" para no
> romper `Enrollment` serializado; el valor que manda es `effectiveVisibility`.
> La migración de `enrollments.json` es trivial: `studentCap` ausente ⇒ `FULL`.

### 2.3 Consentimiento y solicitudes

`core/src/commonMain/kotlin/kitoru/domain/Visibility.kt` (archivo nuevo)

```kotlin
@Serializable
enum class ConsentState(val label: String) {
    NOT_REQUESTED("Sin consultar"),
    PENDING("Solicitado"),
    GRANTED("Concedido"),
    DENIED("Denegado"),
    WITHDRAWN("Retirado"),
}

@Serializable
data class VisibilityConsent(
    val state: ConsentState = ConsentState.NOT_REQUESTED,
    /** Nivel pedido por el alumno. */
    val requestedLevel: ChatVisibility = ChatVisibility.NONE,
    val requestedAt: Long = 0L,
    /** Nivel efectivamente concedido (puede ser menor que lo pedido). */
    val grantedLevel: ChatVisibility = ChatVisibility.NONE,
    val grantedAt: Long = 0L,
    /** Motivo que escribió el alumno. */
    val note: String = "",
)

@Serializable
enum class RequestStatus(val label: String) {
    PENDING("Pendiente"),
    APPROVED("Aprobada"),
    DENIED("Denegada"),
    WITHDRAWN("Retirada"),
}

/** Pedido del alumno para que el docente suba su visibilidad. */
@Serializable
data class VisibilityRequest(
    val id: String,
    val courseId: String,
    val userId: String,
    val from: ChatVisibility,
    val to: ChatVisibility,
    val createdAt: Long,
    val status: RequestStatus = RequestStatus.PENDING,
    val resolvedAt: Long = 0L,
    val resolvedBy: String = "",
    val teacherNote: String = "",
)
```

### 2.4 Invitaciones (alta manual, importación masiva, ayudantes)

`core/src/commonMain/kotlin/kitoru/domain/Invites.kt` (archivo nuevo)

```kotlin
@Serializable
enum class InviteStatus(val label: String) {
    PENDING("Pendiente"),
    ACCEPTED("Aceptada"),
    EXPIRED("Vencida"),
    REVOKED("Revocada"),
}

@Serializable
data class EnrollmentInvite(
    val id: String,
    val courseId: String,
    val email: String = "",
    val displayName: String = "",
    val role: CourseRole = CourseRole.ALUMNO,
    /** Código de un solo uso que la persona usa para reclamar la inscripción. */
    val code: String,
    val createdAt: Long,
    val expiresAt: Long = 0L,          // 0 = sin vencimiento
    val status: InviteStatus = InviteStatus.PENDING,
    val acceptedBy: String = "",
    val acceptedAt: Long = 0L,
    val createdBy: String = "",
    /** Si es un alta manual sin email, el docente lo agrega al padrón ya. */
    val autoEnroll: Boolean = false,
)
```

Reglas:
- `code` = 8 caracteres del alfabeto `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (sin I/O/0/1),
  con prefijo por rol: `ALU-XXXX` / `AYU-XXXX`. Se genera con `Random.nextInt` sembrado
  por `nowMillis()` + `userId.hashCode()` (portable, sin `java.security.SecureRandom`
  obligatorio en `commonMain`; en desktop se puede reforzar con `SecureRandom`).
- `autoEnroll = true` → el docente ya lo agrega al padrón con ese `userId` y el alumno
  "reclama" el nombre cuando entra con el código (para el caso "no puede inscribirse").
- `autoEnroll = false` + email → el docente manda la invitación; cuando la persona la
  acepta se crea la `Enrollment`.

### 2.5 Auditoría y registro de accesos

`core/src/commonMain/kotlin/kitoru/domain/EnrollmentAudit.kt` (archivo nuevo)

```kotlin
@Serializable
enum class EnrollmentAction(val label: String) {
    ENROLLED("Inscripción"),
    REMOVED("Baja"),
    ROLE_CHANGED("Cambio de rol"),
    VISIBILITY_RAISED("Visibilidad subida"),
    VISIBILITY_LOWERED("Visibilidad bajada"),
    VISIBILITY_REQUESTED("Solicitud de visibilidad"),
    VISIBILITY_REQUEST_RESOLVED("Resolución de solicitud"),
    DATA_EXPORTED("Exportación"),
    DATA_ERASED("Supresión de datos"),
}

@Serializable
data class EnrollmentAuditEvent(
    val id: String,
    val courseId: String,
    /** A quién afecta. */
    val userId: String,
    val at: Long,
    /** Quién lo hizo. */
    val actorId: String,
    val actorRole: CourseRole,
    val action: EnrollmentAction,
    val from: String = "",     // valor anterior (serializado como texto legible)
    val to: String = "",       // valor nuevo
    val reason: String = "",   // justificación escrita
)

/** Registro de accesos: el docente abrió los chats de un alumno. */
@Serializable
data class TeacherAccessLog(
    val id: String,
    val courseId: String,
    val teacherId: String,
    val teacherName: String,
    val studentUserId: String,
    val at: Long,
    /** "chats" | "analytics" | "export" | "preview" */
    val scope: String,
    /** Cuántos hilos/mensajes tocó. */
    val itemCount: Int = 0,
)
```

> Este log es lo que el alumno ve en su pantalla de privacidad. Sin él, D4 no existe.

### 2.6 Supresión de datos

`core/src/commonMain/kotlin/kitoru/domain/Erasure.kt` (archivo nuevo)

```kotlin
@Serializable
data class ErasureRecord(
    val id: String,
    val courseId: String,
    val userId: String,
    val displayName: String,          // queda el nombre: es el registro de la baja
    val at: Long,
    val byUserId: String,
    val byRole: CourseRole,
    val reason: String = "",
    /** Conteos de lo que se borró. Nunca el contenido. */
    val threadsErased: Int = 0,
    val messagesErased: Int = 0,
    val profileErased: Boolean = false,
    /** El docente ya había visto los chats: no se puede deshacer. */
    val previouslyAccessed: Boolean = false,
)
```

### 2.7 Extensiones de la analítica

`core/src/commonMain/kotlin/kitoru/domain/Analytics.kt`

```kotlin
/** Actividad agregada de una semana ISO (lunes 00:00 UTC). */
@Serializable
data class WeekActivity(
    val weekStart: Long,
    val messages: Int,
    val replies: Int,
    /** Alumnos distintos que escribieron esa semana. */
    val activeStudents: Int,
)
```

- `StudentAnalytics` suma: `topSources: List<SourceUsage> = emptyList()`.
- `CourseAnalytics` suma:
  - `recentActiveStudents: Int = 0` (con actividad en los últimos `ACTIVE_WINDOW_DAYS`).
  - `idleStudents: List<String> = emptyList()` (tuvieron actividad pero están inactivos).
  - `perWeek: List<WeekActivity> = emptyList()`.

`AnalyticsAnalyzer` cambia:

```kotlin
// antes
val visible = enrollments.filter { it.visibility.showsMetrics }
// ahora
val visible = enrollments.filter { it.effectiveVisibility.showsMetrics }
```

y en el bucle de `perDay`:

```kotlin
if (!enrollment.effectiveVisibility.showsMetrics) continue
```

Además se agrega `ACTIVE_WINDOW_DAYS = 14` en `AnalyticsAnalyzer` y el cálculo de
`recentActiveStudents` / `idleStudents` / `perWeek` (agrupando `perDay` por
`weekStart = dayStart - (dayOfWeek + 6) % 7 * 86_400_000L`).

**Regla dura:** si `course.analyticsEnabled == false`, `StudentsViewModel` **no** calcula
ni muestra la columna de mensajes/citas ni el resumen de salud (solo el padrón). El
docente lo ve como "Analítica desactivada para esta cátedra" con un link a activarla
(solo titular).

### 2.8 Repositorios nuevos

`core/src/commonMain/kotlin/kitoru/domain/Repositories.kt`

```kotlin
interface VisibilityRequestRepository {
    suspend fun listForCourse(courseId: String): List<VisibilityRequest>
    suspend fun listPendingForCourse(courseId: String): List<VisibilityRequest>
    suspend fun get(id: String): VisibilityRequest?
    suspend fun save(request: VisibilityRequest)
    suspend fun delete(id: String)
}

interface EnrollmentInviteRepository {
    suspend fun listForCourse(courseId: String): List<EnrollmentInvite>
    suspend fun findByCode(code: String): EnrollmentInvite?
    suspend fun get(id: String): EnrollmentInvite?
    suspend fun save(invite: EnrollmentInvite)
    suspend fun delete(id: String)
}

interface EnrollmentAuditRepository {
    suspend fun listForCourse(courseId: String): List<EnrollmentAuditEvent>
    suspend fun save(event: EnrollmentAuditEvent)
}

interface TeacherAccessLogRepository {
    suspend fun listForCourse(courseId: String): List<TeacherAccessLog>
    suspend fun listForStudent(courseId: String, userId: String): List<TeacherAccessLog>
    suspend fun save(log: TeacherAccessLog)
}

interface ErasureRepository {
    suspend fun listForCourse(courseId: String): List<ErasureRecord>
    suspend fun save(record: ErasureRecord)
}
```

Implementaciones `File*` en `FileRepositories.kt` con los mismos nombres de archivo en
`config/`: `visibility_requests.json`, `enrollment_invites.json`,
`enrollment_audit.json`, `teacher_access_log.json`, `erasures.json`.

**Borrado de datos: no alcanza con borrar la `Enrollment`.** `MessageRepository` borra
por `threadId`, así que hay que:

```kotlin
// EnrollmentRepository
suspend fun deleteByCourseAndUser(courseId: String, userId: String)
```

y un servicio dedicado (no un método de repo) que borra en este orden:

1. hilos del alumno en la cátedra (`ThreadRepository.listForUser` → `delete(threadId)`,
   que en `FileMessageRepository` ya borra en cascada los mensajes con
   `sameId`/`removeById(threadId)`… **ojo: `FileMessageRepository.delete(threadId)` borra
   mensajes con `id == threadId`, no con `threadId == threadId`. Es un bug**: hay que
   agregar `deleteByThread(threadId)` que filtre por `threadId`).
2. `Enrollment` de ese `userId` en esa cátedra.
3. `UserProfile` **sólo** si el usuario no tiene más inscripciones en ninguna cátedra.
4. entradas de `TeacherAccessLog` de ese alumno (el log es del docente, pero contiene
   el `studentUserId`: se anonimiza, no se borra la evidencia).
5. `ErasureRecord` con los conteos.

### 2.9 `Course` gana el código de ayudante

```kotlin
data class Course(
    ...,
    /** Código con el que los alumnos se anotan a la cátedra. */
    val joinCode: String = "",
    /** Código con el que se anotan los ayudantes (distinto del de alumnos). */
    val assistantJoinCode: String = "",
)
```

Los dos códigos se muestran en la pantalla, con botón de copiar y QR.

---

## 3. Matriz de permisos

| Acción | TITULAR | AYUDANTE | ALUMNO (sobre sí mismo) |
|---|:--:|:--:|:--:|
| Ver padrón (nombres, roles, fechas) | ✔ | ✔ | — |
| Ver métricas de un alumno | ✔ | ✔ | las propias |
| Ver **texto** de los chats | ✔ (según visibilidad) | ✔ (según visibilidad) | ✔ (los propios) |
| Ver chats de un alumno `NONE` | ✘ | ✘ | ✔ (los propios) |
| **Cambiar** visibilidad (bajar) | ✔ | ✘ | ✔ (bajar) |
| **Cambiar** visibilidad (subir) | ✔ sólo con consentimiento o justificación | ✘ | ✘ (pide) |
| Aprobar/denegar solicitudes de visibilidad | ✔ | ✘ | — |
| Cambiar rol ALUMNO ↔ AYUDANTE | ✔ | ✘ | — |
| Alta manual de alumnos | ✔ | ✔ | — |
| Importación masiva CSV | ✔ | ✔ | — |
| Invitar ayudantes | ✔ | ✘ | — |
| Dar de baja alumnos | ✔ | ✘ | — |
| Suprimir datos (derecho de supresión) | ✔ | ✘ | ✔ (los propios) |
| Exportar lista CSV | ✔ | ✔ | — |
| Configurar el modelo base | ✔ (`canConfigureModel`) | ✘ | — |
| Editar materiales / prompt | ✔ (`canDesign`) | ✔ (`canDesign`) | — |

El botón de visibilidad queda **deshabilitado con tooltip** para el ayudante, no
oculto: "Sólo el profesor titular puede cambiar la visibilidad de los alumnos."

---

## 4. Layout de la pantalla

### 4.1 Desktop (Compose Multiplatform, ventana ≥ 1000 px)

```
┌─ Ventana Kitoru ─────────────────────────────────────────────────────────────────────────┐
│ ┌──────┐  IA-2026-1 · Inteligencia Artificial                          [ ] [X]         │
│ │ ◧    │  ────────────────────────────────────────────────────────────────────────────  │
│ │ Cát. │  [Alumnos 34] [Docentes 3] [Solicitudes 1]        [Exportar CSV] [+ Alumno]  │
│ │ Mat. │  ────────────────────────────────────────────────────────────────────────────  │
│ │ Aná. │  ┌──────────┐┌──────────┐┌──────────┐┌──────────┐┌──────────┐                 │
│ │ Alum.│  │ 34       ││ 28       ││ 6        ││ 3        ││ 82 %     │                 │
│ │ Conf.│  │ alumnos  ││ activos  ││ inactivos││ nunca    ││ citas    │                 │
│ │      │  │ de 40    ││ (14 días)││          ││ chatearon││ verif.   │                 │
│ │      │  └──────────┘└──────────┘└──────────┘└──────────┘└──────────┘                 │
│ │      │  ┌───────────────────────────────────────────────────────────────────────────┐│
│ │      │  │ Uso semanal   ▁▂▅▇▆▃▁▂▄▆▇█   (8–12 semanas, 1 barra por semana)          ││
│ │      │  └───────────────────────────────────────────────────────────────────────────┘│
│ │      │  ┌───────────────────────────────────────────────────────────────────────────┐│
│ │      │  │ 🔍 Buscar   [Rol: Todos ▾] [Visib.: Todas ▾] [Estado: Todos ▾] [Ordenar ▾]││
│ │      │  │                                          34 alumnos · 1 filtro activo      ││
│ │      │  ├───────────────────────────────────────────────────────────────────────────┤│
│ │      │  │ ☐ │ Alumno          │ Rol      │ Inscripción │ Última act. │ Msgs │ Citas │⋮││
│ │      │  │ ☐ │ Ana Pérez       │ Alumno   │ 12/03/2026  │ hace 2 h    │ 142  │ 88 % │⋮││
│ │      │  │   │ ana@mail.com   │           │             │             │      │      │ ││
│ │      │  │ ☐ │ Luis Díaz       │ Alumno   │ 12/03/2026  │ hace 9 días │  38  │ 71 % │⋮││
│ │      │  │ ☐ │ Mara Sol        │ Alumno   │ 15/03/2026  │ —           │  —   │  —   │⋮││
│ │      │  │   │ 🔒 Oculto       │           │             │             │      │      │ ││
│ │      │  │ ☐ │ Nina Goya       │ Ayudante │ 10/02/2026  │ hace 1 h    │ 210  │ 93 % │⋮││
│ │      │  │ ⏸ 2 seleccionados · [Cambiar visibilidad] [Cambiar rol] [Dar de baja] [✕] ││
│ │      │  └───────────────────────────────────────────────────────────────────────────┘│
│ └──────┘  ┌───────────────────────────────────────────────────────────────────────────┐│
│            │ [⚠ Hay 1 alumno con datos pendientes de supresión]  [Ver]                ││
│            └───────────────────────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

Estructura de la pantalla (`StudentsScreen`):

```
Scaffold(
    topBar = { StudentsTopBar(course, ...) },      // tabs + acciones globales
) { padding ->
    Column {
        CourseHealthSummary(health, onFilterClick)   // tarjetas + sparkline
        StudentFiltersBar(filters, onChange, ...)
        Box(weight = 1f) {
            if (bulkSelection.isNotEmpty()) BulkActionBar(...)
            StudentListAdaptive(rows, selection, ...)
        }
    }
}
```

### 4.2 Android (cards)

```
┌───────────────────────────────┐
│ ‹ IA-2026-1            ⋮ ⋯    │   ← top bar: título de cátedra + overflow
│ [Alumnos] [Docentes] [Pedidos]│   ← tabs scrollables
├───────────────────────────────┤
│  34 alumnos · 28 activos      │   ← resumen compacto (1 línea, clickable)
│  ▁▂▅▇▆▃▁▂▄▆▇█                │   ← sparkline
├───────────────────────────────┤
│  🔍 Buscar alumno              │   ← campo de búsqueda
│  [Rol ▾] [Visib. ▾] [Estado ▾]│   ← chips de filtro (fila scrolleable)
├───────────────────────────────┤
│ ┌───────────────────────────┐ │
│ │ ☐  Ana Pérez      [Alumno]│ │   ← card
│ │    ana@mail.com           │ │
│ │    📅 12/03/2026 · hace 2 h│ │
│ │    💬 142 mensajes · 7 chats│ │
│ │    📎 88 % citas verificadas│ │
│ │    👁 Completo             │ │
│ │                    ⋮ Acciones│ │
│ └───────────────────────────┘ │
│ ┌───────────────────────────┐ │
│ │ ☐  Mara Sol       [Alumno]│ │
│ │    🔒 Oculto               │ │
│ │    Sin datos visibles      │ │   ← el cuerpo de métricas se oculta entero
│ └───────────────────────────┘ │
└───────────────────────────────┘
```

Reglas de la card Android:
- Si `effectiveVisibility == NONE`: se oculta **todo** el bloque de métricas (mensajes,
  citas, última actividad) y se muestra sólo `🔒 Oculto — sin datos visibles`. No se
  muestran ceros ni guiones: ceros filtrarían información ("0 mensajes" ≠ "no lo sé").
- Selección múltiple: **long-press** sobre la card entra en modo selección; la fila
  contextual aparece abajo (`Scaffold.bottomBar`) con las acciones masivas.
- Menú de acciones por alumno: `⋮` abre un `ModalBottomSheet` con las mismas acciones
  que el menú `⋮` del desktop (misma función `StudentActionSheet`).

### 4.3 Columnas (contrato de la tabla)

| # | Columna | Origen | Desktop | Android | Notas |
|---|---|---|---|---|---|
| 1 | Selección | `selection` | checkbox 36 px | checkbox | |
| 2 | Alumno | `enrollment.displayName`, `email`, `userId` | flexible, `ellipsis` | título | "Nombre o pseudónimo": `displayName` es texto libre, el alumno puede poner un pseudónimo |
| 3 | Rol | `role` | chip 120 px | chip | `CourseRole.label` |
| 4 | Inscripción | `joinedAt` | 110 px, `dd/MM/yyyy` | línea secundaria | |
| 5 | Última actividad | `StudentAnalytics.lastActivity` | 130 px, relativo ("hace 2 h") | línea secundaria | `—` si no hay datos |
| 6 | Mensajes | `messages` (+ `threads` en subtexto) | 90 px | línea | subtexto "7 chats" |
| 7 | Tasa de citas | `citationSupportRate` | 90 px, % | línea | con color: ≥85 % verde, 60–85 % ámbar, <60 % rojo |
| 8 | Visibilidad | `effectiveVisibility` | chip 130 px | chip | color: Full=azul, Metrics=violeta, None=gris + candado |
| 9 | Menú | — | 48 px (⋮) | botón `⋮` | |

Criterio de "última actividad": `StudentAnalytics.lastActivity` es el `max(createdAt)`
de sus mensajes. Para un alumno `NONE` no hay `StudentAnalytics` (el analizador lo
excluye) ⇒ columnas 5–7 muestran `—` con el candado de visibilidad.

### 4.4 Mini-resumen de salud del curso (`CourseHealthSummary`)

Cinco tarjetas:

| Tarjeta | Valor | Definición exacta |
|---|---|---|
| **Alumnos** | `34 de 40` | inscritos con `status == ACTIVE` y `role == ALUMNO` / total de filas del padrón de alumnos |
| **Activos** | `28` | `lastActivity >= now - 14d` (`ACTIVE_WINDOW_DAYS`) |
| **Inactivos** | `6` | `messages > 0` y `lastActivity < now - 14d` |
| **Nunca chatearon** | `3` | `messages == 0` (`CourseAnalytics.inactiveStudents`) |
| **Citas verificadas** | `82 %` | `CourseAnalytics.citationSupportRate` |

Cada tarjeta es un **filtro clicable**: click en "Activos" pone el filtro
`Estado = Activo`; click en "Nunca chatearon" → `Estado = Nunca`. El color es
semántico: verde (activo), ámbar (inactivo), gris (nunca).

Si `course.analyticsEnabled == false`: las tarjetas de Activos/Inactivos/Citas se
reemplazan por un aviso "La analítica está desactivada para esta cátedra"
(solo el titular puede activarla).

### 4.5 Distribución de uso por semana (`WeeklyUsageChart`)

Gráfico de barras dibujado con `Canvas` (sin librería externa): una barra por semana
ISO de las últimas 12 semanas.

- Eje X: etiquetas de la semana (`03/03`).
- Barra dupla: azul = mensajes del alumno, verde = respuestas del modelo (apiladas o
  lado a lado; en ancho < 400 px, sólo la barra de mensajes).
- La barra más alta se normaliza al 100 % del alto disponible.
- Tooltip/click en una barra → filtra la lista a esa semana? No: se muestra un
  `Text` con el detalle ("Semana del 03/03: 412 mensajes, 28 alumnos activos").
- Debajo: "Actividad por semana" + `— sin actividad —` si no hay datos.

Fuente: `CourseAnalytics.perWeek` (nuevo) o, si no está, se computa en el VM a partir
de `perDay` con la misma fórmula de `AnalyticsAnalyzer` (se comparte como
`AnalyticsAnalyzer.weekStart(day)`).

---

## 5. Acciones por alumno (menú `⋮`)

Un único composable `StudentActionSheet` / `StudentRowMenu` con las mismas acciones:

| Acción | Habilitada cuando | Destino |
|---|---|---|
| **Ver sus conversaciones** | `teacherSeesText \|\| teacherSeesMetrics` y `messages > 0` | `StudentChatsSheet` |
| **Ver su analítica** | `teacherSeesMetrics` | `StudentAnalyticsSheet` |
| **Cambiar rol** | `role.canDesign` es del actor, actor es TITULAR, objetivo es ALUMNO | `RoleDialog` |
| **Cambiar visibilidad** | actor es TITULAR | `VisibilityDialog` (§6) |
| **Exportar sus datos** | `teacherSeesMetrics` | `ExportStudentDialog` (§9) |
| **Dar de baja** | actor es TITULAR | `RemoveStudentDialog` |
| **Suprimir sus datos** | actor es TITULAR | `EraseStudentDialog` (§9.3) |
| Copiar id de usuario | siempre | portapapeles |
| Ver auditoría | actor es TITULAR | `StudentAuditSheet` |

Azul: "Cambiar rol" **no** aparece para ayudantes (ni deshabilitado: no existe la
acción para ellos). Las acciones que el actor no puede hacer aparecen **deshabilitadas
con motivo** ("Sólo el titular puede cambiar la visibilidad") para que la UI sea
honesta y no parezca un bug.

### 5.1 `RoleDialog` (ALUMNO ↔ AYUDANTE)

```
Cambiar el rol de Ana Pérez
┌──────────────────────────────────────────────────────────────┐
│ (•) Alumno                                                   │
│     Usa el modelo de la cátedra y conversa en el chat.       │
│     No accede a materiales ni analítica.                      │
│ ( ) Ayudante                                                 │
│     Puede editar materiales y ver la analítica de la         │
│     cátedra. No puede cambiar la configuración base del       │
│     modelo ni la visibilidad de los alumnos.                  │
└──────────────────────────────────────────────────────────────┘
Cambiar a Ayudante le da acceso a los chats de los alumnos cuya
visibilidad lo permite.
                                    [Cancelar] [Cambiar a Ayudante]
```

Al confirmar:
- `enrollment.role = AYUDANTE`, `enrollment.visibility = FULL` (un ayudante es docente:
  la visibilidad es "no aplica") y `studentCap = FULL`.
- Se registra `EnrollmentAuditEvent(ROLE_CHANGED, from="Alumno", to="Ayudante")`.
- Se muestra un toast: "Ana Pérez ahora es Ayudante."
- Si el alumno **no está en la app** (alta manual sin cuenta), el cambio se aplica al
  padrón; el rol se ve en la app cuando la persona reclame la inscripción.

Al volver a Alumno: `visibility` vuelve a `FULL` (o al valor previo guardado) y
`studentCap` se mantiene (el alumno puede haber bajado la suya).

### 5.2 `StudentChatsSheet`

Lista de los hilos del alumno (`ThreadRepository.listForUser(courseId, userId)`), cada
uno expandible:

```
Ana Pérez · 7 conversaciones                                    [Cerrar]
┌───────────────────────────────────────────────────────────────┐
│ ▸ "Derivadas parcial"          12/03 · llama3.1 · 8 mensajes  │
│ ▾ "Ejercicio 3 - normal"       12/03 · llama3.1 · 14 mensajes │
│     👤: ¿Cómo se define ...?                                  │
│     🤖: (texto de la respuesta)                1.2 s · 342 tok  │
│         📎Fuentes: TP2.pdf §3.1                               │
│         📊 Citas: 4 verificadas, 0 sin respaldo               │
└───────────────────────────────────────────────────────────────┘
```

- Con `FULL`: se muestra el texto de usuario y del modelo.
- Con `METRICS_ONLY`: se muestra **sólo el metadato** de cada mensaje (rol, fecha,
  latencia, tokens, fuentes usadas, verificación de citas) y una nota fija arriba:
  "Con la visibilidad actual (*Solo métricas*) no podés leer el texto de las
  conversaciones. Podés pedirle al alumno que suba su visibilidad."
  El texto se **omite del árbol de composición** (no se oculta con CSS: en Compose no
  se renderiza), no sólo se lo tapa.
- Con `NONE`: la acción está deshabilitada (§5).
- Al abrir el sheet se escribe un `TeacherAccessLog(scope="chats", itemCount=n)`.
- Botón "Ver sólo respuestas sin respaldo" (filtro por
  `answersWithUnsupportedCitations > 0`).

### 5.3 `StudentAnalyticsSheet`

Tarjetas con: mensajes, respuestas, hilos, días activos, latencia media, tokens,
citas (verificadas / sin respaldo / inexistentes), `citationSupportRate`,
`ungroundedAnswerRate`, herramientas invocadas y fallidas, top de fuentes del alumno
(`StudentAnalytics.topSources`), y un gráfico de actividad por día del alumno.

Botón "Cambiar visibilidad" (sólo titular) → `VisibilityDialog`.

---

## 6. Cambiar la visibilidad (la parte delicada)

### 6.1 Modelo de permisos

```
visibilidad efectiva = min(visibilidad pedida por la cátedra, studentCap del alumno)
                        (min por nivel: NONE < METRICS_ONLY < FULL)
```

- El **alumno** fija `studentCap` (lo baja cuando quiere; lo pide para subirlo).
- La **cátedra** fija `visibility`.
- El mínimo manda. **El alumno siempre gana cuando baja.**
- El docente **nunca** ve texto ni métricas de un alumno cuyo `effectiveVisibility`
  es `NONE`, aunque el `AnalyticsAnalyzer` lo excluya: la UI tampoco lo muestra.

### 6.2 Regla asimétrica: bajar es libre, subir se justifica

| Acción del docente | Regla |
|---|---|
| FULL → METRICS_ONLY | **Libre**, inmediata. Se registra `VISIBILITY_LOWERED`. |
| FULL/METRICS_ONLY → NONE | **Libre**, inmediata. Se registra `VISIBILITY_LOWERED`. |
| NONE → METRICS_ONLY | **Requiere** una de estas dos: (a) hay una `VisibilityRequest` aprobada del alumno; (b) el docente escribe una justificación (mínimo 30 caracteres) y confirma "Informé al alumno". Se registra `VISIBILITY_RAISED` + `reason` + se genera un aviso para el alumno. |
| NONE/METRICS_ONLY → FULL | Igual que arriba. Además, si la justificación menciona "revisión"/"evaluación", la app sugiere usar el flujo de **solicitud del alumno** en su lugar. |

**El diálogo nunca permite subir sin una de las dos condiciones.** Si el docente tries
subir sin nada, el botón "Guardar" está deshabilitado y el motivo se explica abajo:

> Para subir la visibilidad de un alumno necesitás que el alumno lo autorice.
> Podés pedirlo desde la app del alumno ("Pedir que el docente vea mis chats") o
> dejarlo asentado por escrito en el motivo.

### 6.3 `VisibilityDialog` (docente)

```
Visibilidad de Ana Pérez
──────────────────────────────────────────────────────────────────
¿Qué puede ver la cátedra de las conversaciones de Ana?
El cambio aplica a partir de ahora y queda registrado.

( ) ● Completo
    El docente ve el texto de las conversaciones y el detalle de cada
    mensaje: lo que el alumno escribe y lo que responde el modelo.
    Podés leerlo todo. El alumno lo sabe y lo autorizó.

( ) ○ Solo métricas
    El docente ve cuántos mensajes escribe, qué fuentes se consultan,
    cuánto tarda el modelo y cuántas citas están respaldadas.
    El texto NO es visible: ni las preguntas ni las respuestas.

( ) ○ Oculto
    El docente no ve ni el texto ni las estadísticas. En el padrón el
    alumno aparece sin métricas.
    Es la opción más respetuosa y siempre está disponible.

──────────────────────────────────────────────────────────────────
Con esta configuración, la cátedra verá:
  ✔ Cantidad de mensajes y conversaciones
  ✔ Fuentes consultadas, latencia y tokens
  ✔ Calidad de las citas (verificadas / sin respaldo)
  ✘ El texto de las preguntas y de las respuestas
──────────────────────────────────────────────────────────────────
⚠ Estás subiendo la visibilidad. Para hacerlo necesitás:
  ○ El alumno ya lo autorizó (hay una solicitud aprobada)
  ○ Anotar el motivo y confirmar que informaste al alumno
     [_______________________________]  (mín. 30 caracteres)

                                    [Cancelar]  [Guardar]
```

- El preview "Con esta configuración, la cátedra verá" se recalcula en vivo según el radio
  seleccionado. Es la pieza que evita el error mental más caro (confundir
  "Solo métricas" con "Solo cantidad de mensajes").
- Los textos de cada opción son **literales y normativos**, no adjetivos: el docente
  tiene que leer exactamente lo que el alumno va a ver.
- En `METRICS_ONLY` y `NONE` se agrega la línea "El alumno puede ver esta configuración
  desde su app y bajarla cuando quiera."

### 6.4 Solicitudes del alumno (flujo de consentimiento)

**Lado del alumno** (pantalla "Privacidad", §6.6):

```
Pedir que el docente vea mis conversaciones
────────────────────────────────────────────────
Ahora mismo tu docente ve: Solo métricas
Si lo subís a Completo, podrá leer el texto de tus conversaciones.

Motivo (opcional, se lo lee el docente):
[_________________________________________]

Enviarle la solicitud
```

**Lado del docente**: la pantalla de alumnos muestra un badge
`Solicitudes (1)` y una banda:

```
┌───────────────────────────────────────────────────────────────────┐
│ 📩 Ana Pérez pide que veas el texto de sus conversaciones.        │
│    "Quiero que revises mis respuestas del parcial antes de        │
│     entregar."  ·  pedida hace 2 h                                │
│    [Aprobar]  [Denegar]  [Ver detalle]                            │
└───────────────────────────────────────────────────────────────────┘
```

- **Aprobar** ⇒ `enrollment.visibility = FULL`, `studentCap = FULL`,
  `consent.state = GRANTED`, `consent.grantedLevel = FULL`,
  `VisibilityRequest.status = APPROVED`, y se registra
  `VISIBILITY_REQUEST_RESOLVED` + `VISIBILITY_RAISED`.
- **Denegar** ⇒ `status = DENIED` + `teacherNote` (obligatorio, 10+ caracteres).
  LaConsola del alumno ve: "Tu docente denegó la solicitud: <motivo>".
- La lista completa de solicitudes (resueltas y pendientes) vive en la tab
  "Solicitudes" de la pantalla de alumnos.

### 6.5 ¿El alumno puede ver su propia configuración de visibilidad?

**Sí, siempre.** Es el requisito central de la transparencia. La pantalla del alumno
("Privacidad y datos") muestra:

```
Qué ve tu docente
──────────────────────────────────────────────────────────────────
Estado actual: Solo métricas

Lo que tu docente PUEDE ver:
  · Quantos mensajes escribís y cuántas conversaciones abrís
  · Qué materiales del curso se consultan
  · Cuánto tarda el modelo y cuántos tokens usa
  · Si las citas de las respuestas están respaldadas por el material

Lo que tu docente NO PUEDE ver:
  · El texto de tus preguntas
  · El texto de las respuestas del modelo
──────────────────────────────────────────────────────────────────
Podés bajar este nivel cuando quieras. Tu docente no puede subirlo
sin tu autorización.

[ Bajar a "Oculto" ]        [ Pedir que vea todo ]
```

Y además, al final:

```
Registro de accesos
──────────────────────────────────────────────────────────────────
12/03 15:04 · Prof. Titular abrió 3 de tus conversaciones
11/03 09:12 · Prof. Titular exportó tus datos
```

### 6.6 Cambios que hace el alumno

| Acción del alumno | Efecto |
|---|---|
| Bajar `studentCap` | Inmediato y definitivo. `effectiveVisibility` baja. Se registra `VISIBILITY_LOWERED` con `actorRole = ALUMNO`. El docente ve un aviso "Ana Pérez bajó su visibilidad a Oculto" **en la fila, sin metrics**. |
| Pedir subir | Crea `VisibilityRequest(from = actual, to = elegido)`. El alumno puede cancelar la solicitud mientras esté `PENDING`. |
| Cambiar `studentCap` a un nivel **mayor** que el de la cátedra | No se permite (sería subir solo). Se le ofrece el botón de solicitud. |
| Borrar sus propios datos | Derecho de supresión: el alumno borra su cuenta, sus hilos y sus mensajes de todas las cátedras. Se genera `ErasureRecord` y el docente ve en su padrón "Ana Pérez eliminó sus datos". |

### 6.7 Notificaciones al alumno

Cuando el docente cambia la visibilidad (para cualquier nivel), la app del alumno
muestra un aviso al abrir:

> Tu docente cambió la visibilidad de tus conversaciones a *Completo* el 12/03.
> Si no estás de acuerdo, podés bajarla o pedir que la cambie.

Ese aviso se genera a partir del `EnrollmentAuditEvent` con `action = VISIBILITY_RAISED`.

---

## 7. Alta de alumnos

Tres puertas distintas, con una pantalla de elección desde el botón `+ Alumno`:

```
┌──────────────────────────────────────────────────────────────────┐
│ + Alumno                                                        │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ 🔑 Inscripción por código                (la usual)        │  │
│  │ Los alumnos se anotan solos con el código de la cátedra.   │  │
│  │    Código: IA26-7F3K   [Copiar]   [QR]                     │  │
│  └────────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ 👤 Agregar manualmente                                    │  │
│  │ Para alumnos que no pueden inscribirse (sin cuenta,       │  │
│  │ sin internet en el momento).                               │  │
│  └────────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ 📄 Importar una lista                                     │  │
│  │ Subí un CSV con nombres y emails para matricular de a uno. │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

### 7.1 (a) Inscripción por código (la usual)

- El código vive en `Course.joinCode` (alumnos) y `Course.assistantJoinCode` (ayudantes).
- Formato: `IA26-7F3K` = `<prefijo del código de la cátedra>-<4 alfanuméricos>`.
  Si el código no tiene prefijo, se genera `XXXX-XXXX`.
- En la pantalla se muestra con botón **Copiar** (portapapeles) y **QR**
  (`QrCodeImage`, generado con `Canvas` + matriz de módulos; sin dependencia externa:
  el algoritmo de QR es medio complejo — **decisión**: usar la librería
  `com.google.zxing:core` sólo en `jvmCommonMain` para el render, o bien mostrar
  **sólo el código en texto grande** hasta que haya una dependencia de QR. Para no
  agregar dependencias en el milestone 1, la pantalla muestra el código en texto
  monoespaciado grande + copiar, y el QR queda como improvement).
- La detección de un alumno que **usa un código de ayudante** es responsabilidad de la
  app del alumno (§8.3), no de esta pantalla.

### 7.2 (b) Alta manual

```
Agregar alumno
──────────────────────────────────────────────────────────────────
Nombre o pseudónimo *
[____________________________________________]
Email (opcional)
[____________________________________________]

Rol
(•) Alumno   ( ) Ayudante

Visibilidad inicial
(•) Completo   ( ) Solo métricas   ( ) Oculto
  Se puede cambiar en cualquier momento. El alumno puede bajarla
  cuando quiera.

[✓] Informar al alumno de la configuración

              [Cancelar]  [Agregar a la cátedra]
```

Comportamiento:

1. Se valida el nombre: no vacío, ≤ 60 caracteres, no duplicado (comparación
   case-insensitive y sin diacríticos contra el padrón actual: usar
   `kitoru.platform.stripDiacritics`).
2. Si hay email:
   - Si ya existe un `UserProfile` con ese email ⇒ se reutiliza su `userId` y se crea
     la `Enrollment` con `source = MANUAL`.
   - Si no ⇒ se crea un `UserProfile` nuevo con `userId = uuid`, y **además** una
     `EnrollmentInvite` con `autoEnroll = false`, `role = ALUMNO` y un código
     `ALU-XXXX` que el alumno usa para "reclamar" su cuenta cuando abra la app.
     La UI muestra el código para copiarlo y se lo mandás al alumno por el medio que
     quieras (WhatsApp, email…): **"pasale este código y que lo pegue en la app"**.
3. Si **no** hay email:
   - Se crea el `UserProfile` con el nombre y un `userId` provisorio
     (`uuid`), la `Enrollment` con `source = MANUAL` y una `EnrollmentInvite` con
     `autoEnroll = true` y código `ALU-XXXX`.
   - El alumno queda **ya en el padrón** (aparece en la lista, con métricas en cero)
     y al entrar en la app con el código completa su perfil.
4. Se registra `EnrollmentAuditEvent(ENROLLED)`.
5. Toast: "Ana Pérez se agregó a la cátedra. Código de acceso: ALU-7KD2".

La lista queda **ordenada por `joinedAt` descendente** para que el alta manual
(que no tiene email) no quede enterrada.

### 7.3 (c) Importación masiva (CSV)

**Paso 1 — Origen**

```
Importar alumnos
──────────────────────────────────────────────────────────────────
(•) Pegar el CSV en el área de texto
( ) Elegir un archivo .csv

nombre,email
Ana Pérez,ana@mail.com
Luis Díaz,luis@mail.com
──────────────────────────────────────────────────────────────────
Formato esperado: una línea por alumno, con las columnas
`nombre`, `email` y (opcional) `rol`. Se aceptan `,` `;` o tab como separador
y se puede usar `nombre;email;rol`.
```

- **Pegar**: `TextField` multilínea (funciona en Android sin permisos).
- **Elegir archivo**: en desktop, diálogo nativo (`java.awt.FileDialog` vía
  `expect/actual`); en Android, SAF `OpenDocument` con MIME `text/csv`.
  Se necesita un `expect` nuevo en `:core`:
  ```kotlin
  // core/src/commonMain/kotlin/kitoru/platform/FilePicker.kt
  expect suspend fun pickOpenTextFile(mime: String, extension: String): FilePath?
  expect suspend fun pickSaveTextFile(suggestedName: String, mime: String): FilePath?
  ```
  con `actual` en `jvmCommonMain` (Swing/AWT) y en `androidMain` (SAF +
  `ActivityResultContracts`).

**Paso 2 — Parseo y revisión**

```
Revisar la importación
──────────────────────────────────────────────────────────────────
24 filas · 22 se van a inscribir · 2 con problemas

  ✓ Ana Pérez          ana@mail.com      Alumno
  ✓ Luis Díaz          luis@mail.com      Alumno
  ⚠ 453                (sin email)        Alumno
  ✗ "Juan, Pérez"      juan@mail.com      — nombre con coma
──────────────────────────────────────────────────────────────────
✗ juan@mail.com  — email inválido
```

Reglas del parser (`core/src/commonMain/kotlin/kitoru/domain/StudentRosterCsv.kt`):

```kotlin
object StudentRosterCsv {
    data class Row(
        val line: Int,
        val displayName: String,
        val email: String,
        val role: CourseRole?,
    )
    data class Problem(val line: Int, val message: String, val raw: String)
    data class ParseResult(
        val rows: List<Row>,
        val problems: List<Problem>,
        val separator: Char,
        val hadHeader: Boolean,
    )
    fun parse(text: String): ParseResult
}
```

- Se quita el BOM (`\uFEFF`).
- Se ignoran líneas vacías y las que empiezan con `#`.
- **Detección de separador**: se cuentan `,`, `;` y `\t` en la primera línea no vacía y
  gana el más frecuente; si empatan, gana `;` (Excel en español usa `;`).
- **Detección de encabezado**: si la primera fila, normalizada
  (`lowercase`, sin diacríticos, sin espacios), contiene alguna de
  `nombre`/`name`/`displayname`/`apellido`/`email`/`correo`/`rol`/`role`, se trata como
  encabezado y se saltea.
- **Celdas**: se quitan las comillas simples y dobles; `""` dentro de una celda
  entrecomillada es una comilla literal.
- **Email**: regex `^[^\s@]+@[^\s@]+\.[^\s@]+$`; si el email es inválido, la fila es un
  problema (no se importa a medias).
- **Nombre**: obligatorio. Si falta pero hay email ⇒ se deriva del email
  (`ana@mail.com` → "Ana"). Si no hay ninguno ⇒ problema.
- **Rol**: opcional; valores aceptados `alumno`/`student`/`a`, `ayudante`/`assistant`/`h`.
  Desconocido ⇒ problema. **Nunca** `titular` por CSV (sólo el titular puede serlo).
- **Duplicados**: dentro del CSV (mismo email o mismo nombre normalizado) y contra el
  padrón existente ⇒ se marcan como "ya está en la cátedra" y se saltan.

**Paso 3 — Qué hacer con los que no tienen cuenta**

```
Qué hacemos con los alumnos que todavía no usan Kitoru
──────────────────────────────────────────────────────────────────
(•) Inscribirlos ahora
    Quedan en el padrón con un código de acceso individual para
    que entren cuando puedan. No se les manda nada.

( ) Mandarles una invitación por email
    Cada uno recibe un código y un enlace. Requiere Kitoru-Server
    (o exportar las invitaciones a CSV y mandarlas vos).
──────────────────────────────────────────────────────────────────
                                    [Cancelar]  [Importar 22 alumnos]
```

- Con **"Inscribirlos ahora"** se crean `UserProfile` + `Enrollment` por fila, y una
  `EnrollmentInvite(autoEnroll = true)` por alumno, con su código individual. La
  pantalla final muestra los códigos para copiar en bloque:
  "Ana Pérez → ALU-4KD2 · Luis Díaz → ALU-9XT3" y un botón **Copiar todo**.
- Con **"Mandarles una invitación"** se crean `EnrollmentInvite(autoEnroll = false)` con
  email. Si no hay servidor SMTP configurado, la app **no falla**: avisa
  "No hay servidor de correo configurado. Exportá las invitaciones y mandalas vos"
  y ofrece **Exportar invitaciones (CSV)**.

**Reporte final** (`ImportReport`):

```kotlin
data class ImportReport(
    val imported: Int,
    val skippedExisting: Int,
    val failed: Int,
    val invites: List<EnrollmentInvite>,
)
```

---

## 8. Docentes:ayuantes e invitaciones

### 8.1 La tab "Docentes"

La pantalla tiene tres tabs: **Alumnos** / **Docentes** / **Solicitudes**.
"Docentes" lista al titular y a los ayudantes, con la misma tabla pero sin las
columnas de métricas (un docente no es "un alumno con chats"). Cada fila de docente
tiene: nombre, email, rol, fecha de alta, último acceso, y acciones:

- Ver analítica de la cátedra (ayudante) — no aplica al titular.
- Ver el registro de accesos (titular).
- Quitar el rol de ayudante (titular) — el ayudante vuelve a ser alumno.
- Eliminar al ayudante (titular) — borra la inscripción, no sus datos de otras cátedras.

### 8.2 Invitar un ayudante

```
Invitar ayudante
──────────────────────────────────────────────────────────────────
Nombre *
[____________________________________________]
Email *
[____________________________________________]

Se generará un código de un solo uso. El ayudante lo usa en
"Sumarse a una cátedra" y entra con permisos de Ayudante.

   Código:  AYU-8KQ4     [Copiar]  [Revocar]
   Vence:   14/03/2026 (7 días)

              [Cancelar]  [Generar invitación]
```

- `EnrollmentInvite(role = AYUDANTE, code = "AYU-XXXX", expiresAt = now + 7d)`.
- El texto listo para copiar y pegar:
  ```
  Te sumo como ayudante de "IA-2026-1 · Inteligencia Artificial".
  En Kitoru, elegí "Sumarme a una cátedra" y usá el código AYU-8KQ4.
  ```
- Si hay servidor de correo conectado, botón "Enviar por email".
- La lista de invitaciones pendientes muestra código, email, rol, vencimiento y
  acciones: **Copiar**, **Revocar** (`InviteStatus.REVOKED`), **Reenviar**.

### 8.3 Códigos: el de alumnos y el de ayudantes

| Código | Dónde vive | Quién lo usa | Cuántas veces |
|---|---|---|---|
| `Course.joinCode` | cátedra | alumnos | ilimitado |
| `Course.assistantJoinCode` | cátedra | ayudantes | ⚠ **ilimitado: cualquiera con el código entra como ayudante** |
| `ALU-XXXX` / `AYU-XXXX` | invitación | una persona | 1 vez, con vencimiento |

**Decisión de seguridad:** el **código de ayudantes compartido es peligroso** (cualquiera
que lo tenga se vuelve ayudante y ve los chats de los alumnos). Por eso:

1. Por defecto el flujo de alta de ayudantes es **por invitación individual** (§8.2),
   que es un código de un solo uso y con vencimiento.
2. El `assistantJoinCode` existe pero la pantalla muestra un aviso explícito:

   ```
   ⚠ Este código concede el rol de Ayudante a cualquiera que lo use.
     Se recomienda usar invitaciones individuales con vencimiento.
   [ Desactivar el código compartido ]
   ```

   Y el docente puede desactivarlo (queda en `""`).
3. Si se usa, se muestra un contador de usos (`assistantCodeUses`) y un aviso cuando
   pasa de 5: "Este código ya se usó N veces. ¿Revocarlo?".

El código de alumnos es de conocimiento público (se lo pasa al grupo), así que no
tiene estas precauciones.

### 8.4 Capacidades del ayudante (respetando `Roles`)

| Capacidad | `canDesign` (TITULAR=✔, AYUDANTE=✔) | `canConfigureModel` (sólo TITULAR) |
|---|---|---|
| Editar materiales,.tags, `visible` | ✔ | — |
| Editar el prompt de la cátedra | ✔ | — |
| Ver analítica de la cátedra | ✔ | — |
| Ver chats de alumnos (según visibilidad) | ✔ | — |
| Cambiar la configuración base del modelo | — | ✔ |
| Cambiar la visibilidad de un alumno | — (decisión de producto: sólo titular) | ✔ |

En la UI, el botón de la configuración del modelo aparece **deshabilitado** para el
ayudante con el tooltip: "Sólo el profesor titular puede cambiar la configuración
base del modelo." (mismo patrón que `canConfigureModel` ya sugiere).

---

## 9. Exportación y supresión de datos

### 9.1 Exportar la lista (CSV)

Botón **Exportar CSV** en la top bar. Diálogo:

```
Exportar
──────────────────────────────────────────────────────────────────
(•) La lista filtrada (34 alumnos)
( ) Toda la cátedra (40)

[✓] Incluir métricas (mensajes, citas, última actividad)
[✓] Incluir emails

Formato: CSV (separado por ; , compatible con Excel en español)
```

Reglas dura:

- El CSV **respeta la visibilidad**: para un alumno con `effectiveVisibility == NONE`
  las columnas de métricas van **vacías** (no `0`), igual que en la tabla. Con
  `METRICS_ONLY` van las métricas pero nunca texto.
- El CSV **nunca** incluye el texto de los chats. Es una lista, no un volcado.
- Separador `;` y BOM UTF-8 (`\uFEFF`) para que Excel en español lo abra bien.
- Escritura: `pickSaveTextFile("alumnos-IA-2026-1.csv", "text/csv")` → `fs.writeText`.
- Si no hay `FilePicker` disponible (o el usuario cancela), se ofrece "Copiar al
  portapapeles".

Columnas:

```
nombre;email;rol;inscripcion;ultima_actividad;mensajes;conversaciones;citas_verificadas;citas_totales;tasa_citas;visibilidad;origen
Ana Pérez;ana@mail.com;Alumno;12/03/2026;hace 2 h;142;7;88;100;88 %;Completo;Código
Mara Sol;;Alumno;15/03/2026;;;;;;Oculto;Código
```

### 9.2 Exportar los datos de un alumno

Acción por alumno → `ExportStudentDialog`:

```
Exportar los datos de Ana Pérez
──────────────────────────────────────────────────────────────────
(•) Métricas y actividad (CSV)
    Mensajes, respuestas, hilos, citas, latencia, tokens y actividad
    por día. Sin texto de conversaciones.

( ) Todo, incluido el texto (JSON)
    Perfil, inscripción, conversaciones completas y métricas.
    Sólo disponible con visibilidad Completo.

El archivo se guarda en tu equipo. Si lo compartís, queda bajo
tu responsabilidad.

              [Cancelar]  [Exportar]
```

Estructura del JSON (con texto, sólo si `FULL`):

```json
{
  "exportedAt": 1772664000000,
  "course": { "id": "IA-2026-1", "name": "Inteligencia Artificial", "term": "2026-1" },
  "student": {
    "userId": "u_8f3a", "displayName": "Ana Pérez", "email": "ana@mail.com",
    "role": "ALUMNO", "joinedAt": 1772664000000,
    "visibility": { "teacherSetting": "FULL", "studentCap": "FULL", "effective": "FULL" }
  },
  "analytics": {
    "messages": 142, "replies": 138, "threads": 7, "activeDays": 12,
    "lastActivity": 1772742000000, "avgLatencyMs": 1180,
    "tokensIn": 22140, "tokensOut": 18402,
    "citations": { "verified": 88, "unsupported": 9, "unknown": 3, "supportRate": 0.88 },
    "toolCalls": 21, "toolFailures": 2,
    "ungroundedAnswerRate": 0.065
  },
  "activityByDay": [ { "day": "2026-03-12", "messages": 21, "replies": 20 } ],
  "threads": [
    { "id": "…", "title": "Ejercicio 3", "modelLabel": "llama3.1:8b",
      "createdAt": 1772742000000,
      "messages": [
        { "role": "user", "text": "¿Cómo se define…", "createdAt": 1772742000000 },
        { "role": "assistant", "text": "…", "sources": ["TP2.pdf §3.1"],
          "citation": { "verified": 4, "unsupported": 0, "unknown": 0 },
          "latencyMs": 1180, "tokensOut": 342 }
      ] }
  ]
}
```

Con `METRICS_ONLY`, el JSON sale **sin el array `threads`** y con
`"threadsOmitted": "visibilidad METRICS_ONLY"`. Con `NONE`, la acción está deshabilitada.

Cada exportación escribe un `TeacherAccessLog(scope = "export")` y un
`EnrollmentAuditEvent(DATA_EXPORTED)`.

### 9.3 Derecho de supresión: borrar a un alumno y todos sus datos

Acción por alumno → `EraseStudentDialog`. Es el diálogo más cuidadoso de la app:

```
⚠ Suprimir los datos de Ana Pérez
──────────────────────────────────────────────────────────────────
Esta acción no se puede deshacer. Se eliminará:

  ✓ Su inscripción en IA-2026-1
  ✓ 7 conversaciones y 143 mensajes
  ✓ Su perfil en Kitoru (no está en ninguna otra cátedra)
  ✓ Sus métricas y su actividad

Lo que ya viste o exportaste no se puede recuperar: si leíste sus
conversaciones, esa información no desaparece de tu memoria ni de
los archivos que hayas guardado. Kitoru va a dejar constancia de
la supresión.

[ ] Marcar como "solicitud de supresión de datos" (recomendado)
    Se genera un registro auditable con fecha y conteos, sin contenido.
    Si Kitoru-Server está conectado, se le avisa para que borre su copia.

Escribí  SUPRIMIR  para confirmar:
[__________________]

                              [Cancelar]  [Suprimir]
```

- El botón **"Suprimir"** está deshabilitado hasta que se escriba exactamente
  `SUPRIMIR` (mayúsculas, sin tildes) — con el mismo patrón que "ELIMINAR" en §10.
- Con la casilla marcada se crea el `ErasureRecord` con los conteos y
  `previouslyAccessed = true/false` según si hay `TeacherAccessLog` para ese alumno.
- Si el alumno está en más de una cátedra, el diálogo avisa: "Está inscripto en N
  cátedras más. Esta acción sólo afecta a IA-2026-1" y por defecto **no** borra el
  perfil (checkbox "Borrar también el perfil y las demás cátedras", desactivado por
  defecto y con doble confirmación).
- Al terminar, la fila del alumno desaparece del padrón y queda el registro:
  `Ana Pérez · datos suprimidos el 12/03`.

### 9.4 "Dar de baja" ≠ "suprimir"

`RemoveStudentDialog` (más simple, el 90 % de los casos):

```
Dar de baja a Ana Pérez
──────────────────────────────────────────────────────────────────
Deja de aparecer en el padrón y no puede chatear.

Sus conversaciones y métricas se conservan (por si necesitás
revisarlas). Podés suprimirlas después con "Suprimir sus datos".

[✓] Avisar al alumno
    (si tiene cuenta, verá que fue dado de baja y por qué)

              [Cancelar]  [Dar de baja]
```

- `enrollment.status = REMOVED`, `removedAt = now`. **No** se borra nada.
- `EnrollmentAuditEvent(REMOVED)`.
- En la tabla, el filtro `Estado` ofrece "Dados de baja" para poder verlos y
  revertir la baja ("Reactivar").

---

## 10. Acciones masivas

### 10.1 Modo selección

- **Desktop**: checkbox en la primera columna + `Shift+click` para seleccionar un rango;
  checkbox de encabezado para "seleccionar todos los visibles" (los filtrados, no los
  ocultos por scroll).
- **Android**: long-press sobre una card entra en modo selección; después, un tap
  alterna. En el modo selección la top bar se reemplaza por una barra contextual.

Con selección activa aparece la `BulkActionBar`:

```
2 seleccionados                         [Cambiar visibilidad] [Cambiar rol] [Exportar] [Dar de baja] [✕]
```

### 10.2 Acciones masivas disponibles

| Acción | Diálogo | Reglas |
|---|---|---|
| **Cambiar visibilidad** | `BulkVisibilityDialog` | Sólo titular. Sólo permite **bajar** de forma masiva; para subir, exige la justificación por alumno (§6.2). Nunca sube por encima del `studentCap` de cada alumno. |
| **Cambiar rol** | `BulkRoleDialog` | Sólo titular. `Alumno → Ayudante` o `Ayudante → Alumno`. |
| **Exportar selección** | `ExportStudentDialog` en modo múltiple | Un CSV con una fila por alumno seleccionado. |
| **Dar de baja** | `BulkRemoveDialog` | Sólo titular. Lista los nombres de los seleccionados y pide confirmación. |
| **Limpiar selección** | — | `Esc` en desktop, botón `✕` en Android. |

### 10.3 `BulkVisibilityDialog`

```
Cambiar la visibilidad de 3 alumnos
──────────────────────────────────────────────────────────────────
( ) Bajar a "Solo métricas"
( ) Bajar a "Oculto"
( ) Subir a "Completo"   ← requiere justificación por alumno

Al bajar, el docente deja de ver texto y/o métricas.
Al subir, cada alumno necesita autorizar su nivel.

Nombre               Actual      Nuevo      Autorización
Ana Pérez            Completo    Solo métricas  (no hace falta)
Luis Díaz            Oculto      Solo métricas  ⚠ requiere autorización
Mara Sol             Completo    Solo métricas  (no hace falta)

                                     [Cancelar]  [Aplicar a 3 alumnos]
```

- Si alguno de los alumnos requiere autorización y no hay solicitud aprobada, la fila
  queda marcada y **el alumno no se modifica**; al final se informa:
  "Se aplicó a 2 de 3 alumnos. Luis Díaz necesita que autorices el cambio."
- Bajar la visibilidad de varios es una acción **de protección** y por eso no pide
  justificación, pero sí queda auditada.

### 10.4 "Borrar alumnos sin actividad"

Acción del menú `⋯` de la pantalla:

```
Limpiar alumnos sin actividad
──────────────────────────────────────────────────────────────────
Hay 3 alumnos inscriptos que nunca chatearon:

  ▸ Ana Pérez   (inscripta el 15/03)
  ▸ Tito Ruiz   (inscripto el 14/03)
  ▸ Zara Paz    (inscripta el 15/03)

Se los da de baja (no se borra nada, no tienen conversaciones).
Podés eliminarlos después desde "Suprimir sus datos".

                     [Cancelar]  [Dar de baja 3 alumnos]
```

- Filtra `messages == 0` **y** `role == ALUMNO` **y** `status == ACTIVE`.
- `visibility` no importa: un alumno `NONE` puede tener 0 mensajes *conocidos* pero
  haber chateado. **Ojo — caso borde**: para un alumno `NONE` no sabemos si chateó.
  La UI lo dice: "No podemos saber si estos alumnos chatearon (su visibilidad es
  Oculto). Se los da de baja igualmente." y el checkbox por defecto de esos alumnos
  viene **desmarcado**.
- Sólo el titular. Los alumnos `NONE` quedan fuera del conteo de "nunca chatearon"
  por el mismo motivo: `CourseAnalytics.inactiveStudents` ya los excluye.

---

## 11. Nombres de archivos, composables y view-models

### 11.1 `:core` — dominio (nuevos + modificados)

```
core/src/commonMain/kotlin/kitoru/domain/
├── Roles.kt                  (modificado: ChatVisibility.level, and(), isBroaderThan())
├── Course.kt                 (modificado: Enrollment extendida, EnrollmentSource,
│                             EnrollmentStatus, Course.assistantJoinCode)
├── Analytics.kt              (modificado: WeekActivity, StudentAnalytics.topSources,
│                             CourseAnalytics.recentActiveStudents/idleStudents/perWeek)
├── AnalyticsAnalyzer.kt      (modificado: effectiveVisibility, perWeek, active window)
├── Repositories.kt           (modificado: + 5 interfaces)
├── FileRepositories.kt       (modificado: + 5 implementaciones File*)
├── Visibility.kt             (nuevo: ConsentState, VisibilityConsent, RequestStatus,
│                             VisibilityRequest)
├── Invites.kt                (nuevo: InviteStatus, EnrollmentInvite)
├── EnrollmentAudit.kt        (nuevo: EnrollmentAction, EnrollmentAuditEvent,
│                             TeacherAccessLog)
├── Erasure.kt                (nuevo: ErasureRecord)
├── StudentRosterCsv.kt       (nuevo: StudentRosterCsv.parse)
└── usecases/
    ├── RosterService.kt      (nuevo: alta manual, CSV, cambio de rol, visibilidad)
    ├── VisibilityPolicy.kt   (nuevo: reglas de subida/bajada + auditoría)
    ├── ExportService.kt      (nuevo: CSV de lista, JSON de alumno, CSV de invitaciones)
    └── ErasureService.kt     (nuevo: borrado en cascada + registro)
```

`core/src/commonMain/kotlin/kitoru/platform/FilePicker.kt` (nuevo) con
`expect pickOpenTextFile` / `pickSaveTextFile` + `actual` en `jvmCommonMain` y
`androidMain`.

### 11.2 `:ui` — composables

```
ui/src/commonMain/kotlin/kitoru/ui/teacher/students/
├── StudentsScreen.kt          (Scaffold + tabs + coordinación)
├── StudentsViewModel.kt       (ver §11.3)
├── StudentsUiState.kt         (StudentsUiState, StudentRowUi, StudentFilters,
│                               StudentSort, StudentActivityState, CourseHealth)
├── StudentListTable.kt        (desktop: tabla con LazyColumn + header)
├── StudentListCards.kt        (android: cards)
├── StudentListAdaptive.kt     (expect/actual que elige tabla o cards)
├── StudentRow.kt              (desktop: fila + chips + menú ⋮)
├── StudentCard.kt             (android: card)
├── StudentTableHeader.kt      (desktop: columnas ordenables)
├── StudentFiltersBar.kt       (búsqueda + filtros + orden)
├── CourseHealthSummary.kt     (tarjetas de salud)
├── WeeklyUsageChart.kt        (gráfico de barras con Canvas)
├── StudentActionSheet.kt      (menú de acciones compartido)
├── StudentChatsSheet.kt       (lista de hilos + mensajes)
├── StudentAnalyticsSheet.kt   (métricas del alumno)
├── VisibilityDialog.kt        (diálogo de visibilidad, docente)
├── BulkVisibilityDialog.kt
├── RoleDialog.kt
├── BulkRoleDialog.kt
├── AddStudentDialog.kt
├── ImportStudentsDialog.kt    (3 pasos)
├── ImportReviewTable.kt
├── ExportDialog.kt            (CSV de la lista)
├── ExportStudentDialog.kt     (JSON/CSV de un alumno)
├── EraseStudentDialog.kt      (supresión)
├── RemoveStudentDialog.kt     (baja)
├── BulkRemoveDialog.kt
├── VisibilityRequestsSheet.kt (pedidos de los alumnos)
├── TeachersTab.kt             (tab "Docentes")
├── InviteAssistantDialog.kt   (invitar ayudante)
├── InviteCodeCard.kt          (código + copiar)
└── common/Format.kt           (formatRelativeTime, formatDate, formatPercent)
```

```
ui/src/commonMain/kotlin/kitoru/ui/student/privacy/
├── PrivacyScreen.kt           (lado alumno: qué ve el docente, registro de accesos)
├── PrivacyViewModel.kt
└── RequestVisibilityDialog.kt (el alumno pide subir)
```

Plataforma:

```
ui/src/desktopMain/kotlin/kitoru/ui/teacher/students/StudentListDesktop.kt
ui/src/androidMain/kotlin/kitoru/ui/teacher/students/StudentListAndroid.kt
```

### 11.3 `StudentsViewModel`

```kotlin
class StudentsViewModel(
    private val courseId: String,
    private val courses: CourseRepository,
    private val enrollments: EnrollmentRepository,
    private val threads: ThreadRepository,
    private val messages: MessageRepository,
    private val requests: VisibilityRequestRepository,
    private val invites: EnrollmentInviteRepository,
    private val audit: EnrollmentAuditRepository,
    private val accessLog: TeacherAccessLogRepository,
    private val rosters: RosterService,
    private val policy: VisibilityPolicy,
    private val exports: ExportService,
    private val erasures: ErasureService,
    private val clock: () -> Long = { nowMillis() },
) {
    val state: StateFlow<StudentsUiState>

    // filtros y orden
    fun onSearch(query: String)
    fun onFilterRole(role: CourseRole?)
    fun onFilterVisibility(v: ChatVisibility?)
    fun onFilterActivity(state: StudentActivityState?)
    fun onSort(column: StudentColumn, ascending: Boolean)
    fun resetFilters()

    // selección
    fun toggleSelection(userId: String)
    fun selectAllVisible()
    fun clearSelection()

    // acciones
    fun changeRole(userId: String, role: CourseRole)
    fun changeVisibility(userId: String, target: ChatVisibility, reason: String, informed: Boolean)
    fun bulkChangeVisibility(userIds: Set<String>, target: ChatVisibility, reason: String)
    fun bulkChangeRole(userIds: Set<String>, role: CourseRole)
    fun removeStudents(userIds: Set<String>)
    fun eraseStudent(userId: String, eraseProfile: Boolean)
    fun approveRequest(requestId: String, note: String)
    fun denyRequest(requestId: String, note: String)

    // altas
    fun addStudentManually(name: String, email: String, role: CourseRole, visibility: ChatVisibility)
    fun previewImport(csv: String): ImportPreview
    fun runImport(csv: String, mode: ImportMode): ImportReport
    fun createInvite(email: String, name: String, role: CourseRole): EnrollmentInvite
    fun revokeInvite(id: String)

    // exportaciones
    fun exportRosterCsv(includeAll: Boolean): String
    fun exportStudentJson(userId: String): String
    fun exportInvitesCsv(): String

    // registro de acceso
    fun logAccess(userId: String, scope: String, itemCount: Int)
}
```

### 11.4 `StudentsUiState`

```kotlin
data class StudentsUiState(
    val loading: Boolean = true,
    val course: Course? = null,
    val actorRole: CourseRole = CourseRole.TITULAR,

    val rows: List<StudentRowUi> = emptyList(),          // padrón completo
    val visibleRows: List<StudentRowUi> = emptyList(),    // filtrado + ordenado
    val health: CourseHealth = CourseHealth(),
    val filters: StudentFilters = StudentFilters(),
    val sort: StudentSort = StudentSort(StudentColumn.NAME, ascending = true),
    val selection: Set<String> = emptySet(),               // userIds

    val pendingRequests: List<VisibilityRequest> = emptyList(),
    val invites: List<EnrollmentInvite> = emptyList(),

    val dialog: StudentsDialog? = null,                    // estado del diálogo abierto
    val snack: String? = null,
) {
    val canConfigureVisibility: Boolean get() = actorRole == CourseRole.TITULAR
}
```

### 11.5 `StudentRowUi` (la fila de la tabla)

```kotlin
data class StudentRowUi(
    val userId: String,
    val enrollmentId: String,
    val displayName: String,
    val email: String,
    val role: CourseRole,
    val joinedAt: Long,
    val status: EnrollmentStatus,
    val source: EnrollmentSource,

    /** Lo que pidió la cátedra. */
    val teacherVisibility: ChatVisibility,
    /** El techo del alumno. */
    val studentCap: ChatVisibility,
    /** El valor que manda. */
    val effectiveVisibility: ChatVisibility,

    val consent: VisibilityConsent,
    val pendingRequest: VisibilityRequest? = null,

    // métricas (sólo si effectiveVisibility.showsMetrics)
    val metricsVisible: Boolean,
    val textVisible: Boolean,
    val messages: Int = 0,
    val replies: Int = 0,
    val threads: Int = 0,
    val activeDays: Int = 0,
    val lastActivity: Long = 0L,
    val citationSupportRate: Double = 0.0,
    val ungroundedAnswerRate: Double = 0.0,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
) {
    val activityState: StudentActivityState
        get() = when {
            !metricsVisible -> StudentActivityState.UNKNOWN
            messages == 0 -> StudentActivityState.NEVER
            lastActivity >= nowMillis() - ACTIVE_WINDOW_MS -> StudentActivityState.ACTIVE
            else -> StudentActivityState.IDLE
        }
}
```

`StudentActivityState`: `ACTIVE`, `IDLE`, `NEVER`, `UNKNOWN` (visibilidad oculta).
`UNKNOWN` **no** cuenta como "nunca chateó" en el resumen de salud: sólo `NEVER`
(con visibilidad que muestra métricas y 0 mensajes) cuenta.

### 11.6 `CourseHealth`

```kotlin
data class CourseHealth(
    val totalStudents: Int = 0,
    val activeStudents: Int = 0,
    val idleStudents: Int = 0,
    val neverChatted: Int = 0,
    val hiddenStudents: Int = 0,      // visibilidad Oculto: no sabemos su actividad
    val assistants: Int = 0,
    val citationSupportRate: Double = 0.0,
    val totalMessages: Int = 0,
    val avgMessagesPerStudent: Double = 0.0,
    val perWeek: List<WeekActivity> = emptyList(),
    val analyticsEnabled: Boolean = true,
)
```

---

## 12. Casos borde

| Caso | Qué hace la UI |
|---|---|
| Alumno `NONE` con chats | Fila con candado; sin métricas (ni ceros). El resumen de salud cuenta `hiddenStudents` aparte. |
| Alumno `NONE` en la importación CSV | Se inscribe igual; `studentCap = FULL`, `visibility = FULL` por defecto pero el alumno puede bajarla al entrar. El docente ve la fila sin métricas. |
| Alumno sin email | `displayName` es el único identificador. El CSV de exportación deja el email vacío. La clave de la columna "Alumno" muestra un ícono de "sin email". |
| Dos alumnos con el mismo nombre | Se permite (pueden ser homónimos), pero el alta manual avisa: "Ya existe un alumno con ese nombre en la cátedra". La búsqueda muestra el email para desambiguar. |
| El propio docente aparece en el padrón | No: el actor (TITULAR/AYUDANTE) se excluye de la lista de alumnos. |
| Un ayudante es además alumno en la misma cátedra | No se permite: una `Enrollment` por `(courseId, userId)`. Si el actor quiere ser alumno, se crea otra cátedra. |
| El CSV tiene 5000 filas | El parseo esstreaming sobre el texto completo (limitado a 5 MB); la tabla de revisión usa `LazyColumn` virtualizado. Más de 2000 filas → se pide confirmación. |
| El CSV tiene BOM, CRLF o `sep=,` | Se maneja: BOM se quita, CRLF se normaliza, la línea `sep=,` de Excel se ignora. |
| Cambiar la visibilidad de un alumno que tiene una solicitud pendiente | El diálogo muestra la solicitud: "Este alumno ya pidió *Completo*. [Aprobar solicitud]" y el sub-formulario de justificación se oculta. |
| El docente es ayudante y hace clic en "Cambiar visibilidad" | Botón deshabilitado con motivo. Nunca "funciona a medias". |
| La cátedra tiene `analyticsEnabled = false` | El resumen de salud muestra "Analítica desactivada"; las columnas de mensajes/citas no se renderizan; `WeeklyUsageChart` muestra "—". |
| El alumno eliminó sus datos | La fila desaparece; queda un `ErasureRecord` visible como nota: "Ana Pérez · datos suprimidos el 12/03". No se puede "reactivar". |
| Reloj del sistema en el futuro / `joinedAt == 0` | `formatRelativeTime` cae a "—" si el timestamp es 0. `lastActivity == 0` → "nunca". |
| Un alumno se inscribe dos veces con el mismo código | La inscripción es idempotente por `(courseId, userId)`: si ya existe, se actualiza `displayName` y no se duplica la fila. |

---

## 13. Criterios de aceptación

1. La lista muestra todos los alumnos del padrón con las 8 columnas definidas; en
   Android son cards con la misma información.
2. Un alumno con `effectiveVisibility == NONE` no muestra **ninguna** métrica (ni ceros,
   ni guiones con número) en la lista, en el resumen de salud, ni en el CSV exportado.
3. El docente no puede subir la visibilidad sin consentimiento aprobado o justificación
   escrita de ≥ 30 caracteres; el intento queda registrado en `EnrollmentAuditEvent`.
4. El alumno ve en su app el nivel vigente, la lista literal de lo que ve el docente, el
   registro de accesos del docente y puede bajar el nivel sin fricción.
5. Una solicitud del alumno aparece en la pantalla de alumnos del docente con su motivo
   y se puede aprobar o denegar con nota; la decisión queda auditada.
6. Los tres caminos de alta funcionan: autoinscripción por código (fuera de esta
   pantalla), alta manual (nombre + email opcional → genera un código individual) e
   importación CSV (parseo tolerante, vista de revisión, reporte de errores).
7. El CSV de importación acepta `,` `;` y tab, detecta encabezado, valida emails,
   detecta duplicados (dentro del CSV y contra el padrón) y nunca importa filas inválidas.
8. Un ayudante no puede cambiar la visibilidad, dar de baja alumnos ni suprimir datos;
   el botón está deshabilitado con motivo explícito.
9. Exportar la lista respeta la visibilidad; exportar los datos de un alumno `FULL`
   incluye el texto; con `METRICS_ONLY` no lo incluye; con `NONE` la acción no existe.
10. Suprimir un alumno borra inscripción, hilos, mensajes, perfil (si no está en otra
    cátedra) y analítica, deja un `ErasureRecord` con conteos sin contenido, y exige
    escribir `SUPRIMIR`.
11. El resumen de salud muestra activos (14 días), inactivos, nunca chatearon y tasa de
    citas, y cada tarjeta filtra la lista al pulsarla.
12. El gráfico semanal muestra una barra por semana de las últimas 12, con el detalle de
    la semana al pulsarla.

---

## 14. Pendientes / decisiones abiertas

| # | Decisión abierta | Impacto |
|---|---|---|
| A1 | ¿El QR del código de inscripción entra en el milestone 1? Depende de aceptar `zxing` como dependencia en `jvmCommonMain`. | Bajo: el código en texto grande + copiar alcanza. |
| A2 | ¿El envío de invitaciones por email entra en el milestone 1 o espera a Kitoru-Server? | Medio: sin servidor, la importación masiva deja invited codes para repartir a mano. |
| A3 | ¿La ventana de "activo" son 14 días? Configurable por cátedra (`Course.activeWindowDays`)? | Bajo: hoy es una constante `ACTIVE_WINDOW_DAYS`. |
| A4 | ¿El `assistantJoinCode` se elimina y sólo quedan invitaciones individuales? | Bajo: hoy se muestra con la advertencia. |
| A5 | ¿El registro de accesos (`TeacherAccessLog`) se muestra completo al alumno o sólo un resumen? | Bajo: hoy se muestra el detalle. |
