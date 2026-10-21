# 03 — Inscripción, roles y control de acceso

> Diseño del modelo de **inscripción** de Kitoru: quién pertenece a una cátedra,
> con qué rol, en qué estado, y qué puede ver y hacer cada uno.
>
> Depende de: `docs/design/CONTEXTO.md`, y de los archivos reales
> `core/src/commonMain/kotlin/kitoru/domain/{Roles,Course,Analytics,AnalyticsAnalyzer,FileRepositories}.kt`.

---

## 0. Resumen ejecutivo (6 decisiones)

| # | Decisión | Motivo |
|---|---|---|
| 1 | `Enrollment` es la **única** fuente de verdad de rol + estado + visibilidad. La visibilidad se parte en **declaración del alumno** (`visibility`) y **tope del docente** (`visibilityCap`). | El alumno tiene agencia; el docente nunca ve más de lo que el alumno declaró. |
| 2 | La visibilidad efectiva es el **mínimo de tres cosas**: tope del rol del observador, tope del docente sobre ese alumno, y declaración del alumno. | Se responde de una sola fórmula si el titular ve texto que el ayudante no ve: **sí**, por el tope por rol. |
| 3 | Las **capacidades** viven en un `enum class Capability` + un conjunto por rol; `Enrollment.can(...)` combina rol + estado. Nada de `if (role == TITULAR)` sueltos en la UI. | Una sola fuente de verdad, testeable, y evita que un rol nuevo rompa la app. |
| 4 | El estado de la inscripción (`PENDING/ACTIVE/SUSPENDED/LEFT/REMOVED`) es **explícito** y las transiciones son **puras y testeables** (`EnrollmentTransitions.apply`). | "Se anotó pero no lo acepté" es un caso de negocio real, no un `role = null`. |
| 5 | La **IA local** se declara con `aiMode` + `visibility`; los chats locales no llegan al servidor y sólo entran por **importación manual** de un archivo que el alumno exporta. | El caso "el servidor no ve los chats" es estructural, no una excepción. |
| 6 | Cambiar de rol **no mueve ni borra datos**: la autoría es por `userId`, el rol es sólo capacidad. Lo que sí cambia es la **titularidad** de la cátedra (`Course.ownerUserId`). | "El ayudante pasa a titular" no puede romper el RAG ni la historia de los alumnos. |

---

## 1. Vocabulario y archivos afectados

### Archivos nuevos

| Archivo | Contenido |
|---|---|
| `core/src/commonMain/kotlin/kitoru/domain/AccessControl.kt` | `AccessControl` (visibilidad efectiva, `canSeeChatText`, `exportScope`), `VisibilityPolicy`, `RoleVisibilityRule`, `ExportScope`. |
| `core/src/commonMain/kotlin/kitoru/domain/EnrollmentService.kt` | `EnrollmentTransitions`, `EnrollmentService`, `BulkAction`, `BulkResult`. |

### Archivos que cambian

| Archivo | Cambio |
|---|---|
| `domain/Roles.kt` | `CourseRole.capabilities`, nuevo `enum class Capability`, `ChatVisibility.level` + `atMost/atLeast`. |
| `domain/Course.kt` | `Enrollment` final, `EnrollmentStatus`, `StudentAiMode`, `EnrollmentEvent`, `EnrollmentEventType`. |
| `domain/Course.kt` (clase `Course`) | `ownerUserId`, `published`, `requireApproval`, `maxStudents`, `maxAssistants`, `visibilityPolicy`, `inactivityDays`. |
| `domain/Repositories.kt` | `EnrollmentRepository.findByUser`, `countStudents`, `listEnrolled`. |
| `domain/FileRepositories.kt` | Implementaciones de esos métodos. |
| `domain/AnalyticsAnalyzer.kt` | `analyze(..., observer: Enrollment?)` + filtrado por visibilidad efectiva + `staleStudents` / `studentsWithoutSharedData`. |

### Requisitos cruzados (para el diseño de chat)

- `ChatThread` necesita un campo `origin: ChatOrigin = HOSTED | LOCAL_IMPORT` para distinguir lo importado.
- `ChatMessage` necesita `redacted: Boolean = false` para marcar los mensajes importados sin texto.

---

## 2. `Enrollment` final

### 2.1 Tabla de campos

| Campo | Tipo | Por defecto | Quién lo escribe | Significado |
|---|---|---|---|---|
| `id` | `String` | — | sistema | id de la inscripción (`"curso\|usuario"`). |
| `courseId` | `String` | — | sistema | cátedra. |
| `userId` | `String` | — | sistema | id del usuario (el autor real de los datos). |
| `displayName` | `String` | — | usuario | nombre visible (se congela aloinscribirse). |
| `role` | `CourseRole` | `ALUMNO` | docente | `TITULAR` / `AYUDANTE` / `ALUMNO`. |
| `status` | `EnrollmentStatus` | `ACTIVE` | sistema/docente | estado de la inscripción (§3). |
| `joinedAt` | `Long` | `0L` | sistema | **fecha de alta** (momento de anotarse). |
| `acceptedAt` | `Long` | `0L` | docente | **fecha de aceptación** (`0` = nunca aceptada). |
| `leftAt` | `Long` | `0L` | usuario/docente | **fecha de baja** (`0` = sigue en la cátedra). |
| `lastAccessAt` | `Long` | `0L` | cliente | **último acceso** a la cátedra. |
| `visibility` | `ChatVisibility` | `FULL` | **alumno** | **declaración del alumno**: hasta dónde comparte sus chats. |
| `visibilityCap` | `ChatVisibility` | `FULL` | docente | **tope del docente** sobre ese alumno (sólo puede bajar). |
| `aiMode` | `StudentAiMode` | `HOSTED` | alumno | `HOSTED` / `LOCAL` / `HYBRID` (§5). |
| `declaredLocalAiAt` | `Long` | `0L` | alumno | cuándo declaró IA local (`0` = no la declaró). |
| `lastImportAt` | `Long` | `0L` | importación | última importación manual de chats locales. |
| `events` | `List<EnrollmentEvent>` | `emptyList()` | sistema | auditoría de estados y cambios de rol. |

Derivados (no se guardan):

| Derivado | Regla |
|---|---|
| `accepted` | `acceptedAt > 0 \|\| status == ACTIVE \|\| status == SUSPENDED` — "el docente la aceptó en algún momento". |
| `isEnrolled` | `status == ACTIVE \|\| status == SUSPENDED` — figura en el padrón y entra en la analítica. |
| `declaredLocalAi` | `aiMode != HOSTED` — el badge "IA local declarada". |
| `label` | `"Nombre — Rol"` (ya existía). |

### 2.2 Código final

```kotlin
// domain/Course.kt  (reemplaza la data class Enrollment actual)

/** Estado de una inscripción dentro de la cátedra. */
@Serializable
enum class EnrollmentStatus(
    val label: String,
    val description: String,
) {
    /** Se anotó con el código pero el docente todavía no lo aceptó. */
    PENDING("Pendiente", "Se anotó con el código de la cátedra, pero el docente todavía no la aceptó."),

    /** Inscripción vigente: puede chatear y participa de la cátedra. */
    ACTIVE("Activa", "Inscripción vigente: puede chatear y su actividad entra en la analítica."),

    /** Inscripción vigente pero bloqueada: no puede chatear; conserva su historial. */
    SUSPENDED("Suspendida", "Inscripción bloqueada: no puede chatear, pero conserva su historial y el docente lo sigue viendo."),

    /** El alumno se dio de baja por su cuenta. Conserva su historial. */
    LEFT("Abandonó", "El alumno se dio de baja por su cuenta. Su historial queda en la cátedra."),

    /** El docente lo removió de la cátedra. */
    REMOVED("Removida", "El docente lo removió de la cátedra. Su historial queda salvo que se purgue explícitamente."),
    ;

    /** ¿Figura en el padrón y cuenta para la analítica? */
    val isEnrolled: Boolean get() = this == ACTIVE || this == SUSPENDED

    /** ¿Permite interactuar con la cátedra? */
    val allowsInteraction: Boolean get() = this == ACTIVE

    companion object {
        fun fromName(name: String): EnrollmentStatus? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** De dónde corre el modelo que usa la persona inscrita. */
@Serializable
enum class StudentAiMode(val label: String, val description: String) {
    /** El modelo corre en el servidor de la institución: la cátedra ve los chats según la visibilidad. */
    HOSTED("IA de la cátedra", "El modelo corre en el servidor de la institución; la cátedra ve los chats según la visibilidad pactada."),

    /** El modelo corre en el dispositivo del alumno: la cátedra no ve nada hasta que el alumno importe el historial. */
    LOCAL("IA local", "El modelo corre en el dispositivo del alumno; la cátedra no ve sus conversaciones salvo que el alumno las importe."),

    /** Usa los dos: los chats locales quedan sólo en el dispositivo. */
    HYBRID("IA local y alojada", "El alumno usa los dos modelos; las conversaciones locales quedan sólo en su dispositivo."),
}

/** Tipo de evento de la auditoría de una inscripción. */
@Serializable
enum class EnrollmentEventType(val label: String) {
    ENROLLED("Se inscribió"),
    ACCEPTED("Aceptada"),
    REJECTED("Rechazada"),
    SUSPENDED("Suspendida"),
    REINSTATED("Reactivada"),
    LEFT("Abandonó"),
    REMOVED("Removida"),
    ROLE_CHANGED("Cambió de rol"),
    VISIBILITY_CHANGED("Cambió la visibilidad"),
    AI_MODE_DECLARED("Declaró el modo de IA"),
    CHATS_IMPORTED("Importó conversaciones"),
    BULK_ACTION("Acción masiva"),
}

/** Un evento de la auditoría de una inscripción. */
@Serializable
data class EnrollmentEvent(
    val type: EnrollmentEventType,
    val at: Long,
    /** Quién lo ejecutó (id de usuario): el docente o el propio alumno. */
    val actorUserId: String = "",
    val fromStatus: EnrollmentStatus? = null,
    val toStatus: EnrollmentStatus? = null,
    val fromRole: CourseRole? = null,
    val toRole: CourseRole? = null,
    val note: String = "",
)

/**
 * Inscripción de una persona a una cátedra.
 *
 * Es la unidad de acceso: define el **rol**, el **estado** y la
 * **visibilidad** de esa persona dentro de la cátedra.
 *
 * Visibilidad en dos niveles:
 *  - [visibility]: lo que el **alumno declara** compartir (su techo).
 *  - [visibilityCap]: el tope que el **docente** le impone (sólo puede bajar).
 *
 * La visibilidad real la calcula [AccessControl.effectiveVisibility], que
 * además aplica el tope por rol del observador.
 */
@Serializable
data class Enrollment(
    val id: String,
    val courseId: String,
    val userId: String,
    val displayName: String,
    val role: CourseRole = CourseRole.ALUMNO,

    /** Estado de la inscripción (ver [EnrollmentStatus]). */
    val status: EnrollmentStatus = EnrollmentStatus.ACTIVE,

    /** Fecha de alta: cuándo se anotó. */
    val joinedAt: Long = 0L,

    /** Fecha de aceptación por el docente (0 = nunca aceptada). */
    val acceptedAt: Long = 0L,

    /** Fecha de baja (0 = sigue en la cátedra). */
    val leftAt: Long = 0L,

    /** Último acceso del usuario a la cátedra. */
    val lastAccessAt: Long = 0L,

    /** Qué declara el alumno compartir: es su techo, nadie puede superarlo. */
    val visibility: ChatVisibility = ChatVisibility.FULL,

    /** Tope que el docente impone sobre este alumno (nunca sube de [ChatVisibility.FULL]). */
    val visibilityCap: ChatVisibility = ChatVisibility.FULL,

    /** Modo de IA que usa la persona. */
    val aiMode: StudentAiMode = StudentAiMode.HOSTED,

    /** Cuándo declaró IA local (0 = no la declaró). */
    val declaredLocalAiAt: Long = 0L,

    /** Última importación manual de conversaciones locales (0 = nunca). */
    val lastImportAt: Long = 0L,

    /** Auditoría de cambios de estado y de rol. */
    val events: List<EnrollmentEvent> = emptyList(),
) {
    /** Etiqueta "Nombre — Rol" para las listas. */
    val label: String get() = "$displayName — ${role.label}"

    /** Etiqueta de estado para las listas. */
    val statusLabel: String get() = status.label

    /** ¿El docente la aceptó alguna vez? */
    val accepted: Boolean
        get() = acceptedAt > 0L || status == EnrollmentStatus.ACTIVE || status == EnrollmentStatus.SUSPENDED

    /** ¿Figura en el padrón de la cátedra? */
    val isEnrolled: Boolean get() = status.isEnrolled

    /** ¿Está activa (puede chatear)? */
    val isActive: Boolean get() = status == EnrollmentStatus.ACTIVE

    /** ¿La persona declaró usar IA local? */
    val declaredLocalAi: Boolean get() = aiMode != StudentAiMode.HOSTED

    /**
     * ¿Esta persona puede realizar esta acción en su propio nombre?
     *
     * Reglas:
     *  1. Sólo una inscripción **activa** actúa (PENDING, SUSPENDED, LEFT y
     *     REMOVED no pueden hacer nada).
     *  2. El **rol** define el techo de capacidades ([CourseRole.capabilities]).
     *  3. La **cátedra** puede recortar (ej. [Capability.OVERRIDE_MODEL] exige
     *     `modelPolicy.allowStudentOverride`).
     *
     * Las capacidades dirigidas a otra persona ([Capability.MANAGE_ENROLLMENTS],
     * [Capability.VIEW_CHAT_TEXT], …) se resuelven con [canOn] o con
     * [AccessControl], porque dependen del objetivo y de la visibilidad.
     */
    fun can(capability: Capability, course: Course? = null): Boolean {
        if (status != EnrollmentStatus.ACTIVE) return false
        if (capability !in role.capabilities) return false
        if (course != null) {
            if (capability == Capability.OVERRIDE_MODEL && !course.modelPolicy.allowStudentOverride) {
                return false
            }
            if (capability == Capability.CHAT && role == CourseRole.ALUMNO && !course.published) {
                return false
            }
        }
        return true
    }

    /**
     * ¿Puede agir **sobre otra inscripción** con esta capacidad?
     *
     * [Capability.VIEW_CHAT_TEXT] no se resuelve acá: depende de la visibilidad
     * efectiva y se calcula en [AccessControl.canSeeChatText].
     */
    fun canOn(target: Enrollment, capability: Capability): Boolean {
        if (!can(capability)) return false
        if (target.id == id) return false
        if (target.courseId != courseId) return false
        return when (capability) {
            // Un ayudante administra alumnos; sólo el titular administra docentes.
            Capability.MANAGE_ENROLLMENTS ->
                if (target.role == CourseRole.ALUMNO) true else role == CourseRole.TITULAR

            // Cambiar roles: exclusivo del titular, y sólo sobre docentes.
            Capability.MANAGE_TEAM ->
                role == CourseRole.TITULAR && target.role != CourseRole.ALUMNO

            // Bajar visibilidad: exclusivo del titular, y sólo sobre alumnos.
            Capability.REDUCE_STUDENT_VISIBILITY ->
                role == CourseRole.TITULAR && target.role == CourseRole.ALUMNO

            // Censurar chats: sólo sobre alumnos.
            Capability.MODERATE_CHATS -> target.role == CourseRole.ALUMNO

            else -> false
        }
    }
}
```

### 2.3 Datos de ejemplo

```json
{
  "id": "IA-2026-1|u-ana",
  "courseId": "IA-2026-1",
  "userId": "u-ana",
  "displayName": "Ana Pérez",
  "role": "ALUMNO",
  "status": "ACTIVE",
  "joinedAt": 1770000000000,
  "acceptedAt": 1770000600000,
  "leftAt": 0,
  "lastAccessAt": 1770500000000,
  "visibility": "METRICS_ONLY",
  "visibilityCap": "FULL",
  "aiMode": "LOCAL",
  "declaredLocalAiAt": 1770100000000,
  "lastImportAt": 1770200000000,
  "events": [
    { "type": "ENROLLED", "at": 1770000000000, "actorUserId": "u-ana" },
    { "type": "ACCEPTED", "at": 1770000600000, "actorUserId": "u-doc", "fromStatus": "PENDING", "toStatus": "ACTIVE" },
    { "type": "AI_MODE_DECLARED", "at": 1770100000000, "actorUserId": "u-ana", "note": "IA local declarada" },
    { "type": "CHATS_IMPORTED", "at": 1770200000000, "actorUserId": "u-doc", "note": "3 conversaciones (texto ocultado)" }
  ]
}
```

---

## 3. Estados de la inscripción

### 3.1 Estados

| Estado | Significado | Chatea | En el padrón | En la analítica | Ve los chats de sus alumnos |
|---|---|---|---|---|---|
| `PENDING` | Se anotó con el código, el docente todavía no lo aceptó. | No | No | No | No (aún no es docente) |
| `ACTIVE` | Inscripción vigente. | Sí | Sí | Sí | Sí |
| `SUSPENDED` | Vigente pero bloqueada (impago, sanción). | No | Sí | Sí (histórico) | Sí |
| `LEFT` | Se dio de baja por su cuenta. | No | No | No | No (ya no es docente) |
| `REMOVED` | Lo removió el docente. | No | No | No | No |

> `SUSPENDED` conserva el historial: el docente sigue viendo lo que el alumno chatteó
> (según la visibilidad, que no cambia al suspender). Lo que se pierde es la
> capacidad de chatear.

### 3.2 Transiciones

| Desde | Hacia | Quién | Condiciones |
|---|---|---|---|
| — | `PENDING` | alumno | al anotarse con el código, si `course.requireApproval` |
| — | `ACTIVE` | alumno | al anotarse con el código, si **no** `course.requireApproval` |
| `PENDING` | `ACTIVE` | docente | aceptar |
| `PENDING` | `REMOVED` | docente | rechazar (deja `leftAt`) |
| `ACTIVE` | `SUSPENDED` | docente | suspender |
| `SUSPENDED` | `ACTIVE` | docente | reactivar |
| `ACTIVE` | `LEFT` | alumno o docente | baja voluntaria |
| `SUSPENDED` | `LEFT` | alumno o docente | baja voluntaria |
| `ACTIVE` | `REMOVED` | docente | remover |
| `SUSPENDED` | `REMOVED` | docente | remover |
| `LEFT` | `ACTIVE` | docente | re-inscripción (reactiva el mismo registro) |
| `LEFT` | `PENDING` | sistema | re-inscripción con aprobación requerida |
| `REMOVED` | `ACTIVE` | docente | readmisión |
| `REMOVED` | `PENDING` | sistema | readmisión con aprobación requerida |

Reglas transversales:

1. **Nadie se auto-gestiona**: un docente no cambia su propio estado de rol; el
   titular puede suspender a otro titular, pero no puede dejar la cátedra sin
   titular activo.
2. **Invariante de titularidad**: una cátedra **publicada** tiene siempre **al
   menos un `TITULAR` activo**. Si la transición dejaría la cátedra publicada
   sin titular, se rechaza con: `"La cátedra necesita al menos un profesor
   titular activo."`
3. **Un ayudante no toca a otro docente**: sólo el titular gestiona a docentes.
4. **El estado nunca se borra**: `LEFT` y `REMOVED` conservan `joinedAt`,
   `acceptedAt` y los `events`; la analítica histórica se puede consultar.
5. **Reinscripción = reactivar el mismo registro** (mismo `id`), para que el
   padrón tenga una fila por persona y la auditoría quede en `events`.

### 3.3 Código: transiciones puras

```kotlin
// domain/EnrollmentService.kt

/**
 * Transiciones de estado de una inscripción.
 *
 * Funciones **puras**: no tocan disco ni relyan el reloj. La hora la pasa el
 * llamador. Así son 100% testeables y el mensaje de error es siempre el mismo.
 */
object EnrollmentTransitions {

    private val allowed: Map<EnrollmentStatus, Set<EnrollmentStatus>> = mapOf(
        EnrollmentStatus.PENDING to setOf(EnrollmentStatus.ACTIVE, EnrollmentStatus.REMOVED),
        EnrollmentStatus.ACTIVE to setOf(EnrollmentStatus.SUSPENDED, EnrollmentStatus.LEFT, EnrollmentStatus.REMOVED),
        EnrollmentStatus.SUSPENDED to setOf(EnrollmentStatus.ACTIVE, EnrollmentStatus.LEFT, EnrollmentStatus.REMOVED),
        EnrollmentStatus.LEFT to setOf(EnrollmentStatus.ACTIVE, EnrollmentStatus.PENDING),
        EnrollmentStatus.REMOVED to setOf(EnrollmentStatus.ACTIVE, EnrollmentStatus.PENDING),
    )

    /** Transiciones que un AYUDANTE puede ejecutar sobre un alumno. */
    private val ayudanteAllowed: Set<Pair<EnrollmentStatus, EnrollmentStatus>> = setOf(
        EnrollmentStatus.PENDING to EnrollmentStatus.ACTIVE,
        EnrollmentStatus.ACTIVE to EnrollmentStatus.SUSPENDED,
        EnrollmentStatus.SUSPENDED to EnrollmentStatus.ACTIVE,
        EnrollmentStatus.ACTIVE to EnrollmentStatus.LEFT,
    )

    /** ¿La transición es válida en abstracto? */
    fun canTransition(from: EnrollmentStatus, to: EnrollmentStatus): Boolean =
        to in allowed.getValue(from)

    /** ¿Este rol puede ejecutar esta transición sobre un alumno? */
    fun can(role: CourseRole, from: EnrollmentStatus, to: EnrollmentStatus): Boolean {
        if (!canTransition(from, to)) return false
        return when (role) {
            CourseRole.TITULAR -> true
            CourseRole.AYUDANTE -> (from to to) in ayudanteAllowed
            CourseRole.ALUMNO -> false
        }
    }

    /**
     * Aplica una transición y devuelve la inscripción nueva.
     *
     * Fail-fast en español, como el resto del proyecto.
     */
    fun apply(
        enrollment: Enrollment,
        to: EnrollmentStatus,
        actorUserId: String,
        now: Long,
        note: String = "",
    ): Enrollment {
        require(canTransition(enrollment.status, to)) {
            "No se puede pasar una inscripción de ${enrollment.status.label} a ${to.label}."
        }
        val type = when (to) {
            EnrollmentStatus.PENDING -> EnrollmentEventType.ENROLLED
            EnrollmentStatus.ACTIVE -> if (enrollment.status == EnrollmentStatus.PENDING) {
                EnrollmentEventType.ACCEPTED
            } else {
                EnrollmentEventType.REINSTATED
            }
            EnrollmentStatus.SUSPENDED -> EnrollmentEventType.SUSPENDED
            EnrollmentStatus.LEFT -> EnrollmentEventType.LEFT
            EnrollmentStatus.REMOVED -> EnrollmentEventType.REMOVED
        }
        return enrollment.copy(
            status = to,
            acceptedAt = if (to == EnrollmentStatus.ACTIVE && enrollment.acceptedAt == 0L) {
                now
            } else {
                enrollment.acceptedAt
            },
            leftAt = if (to == EnrollmentStatus.LEFT || to == EnrollmentStatus.REMOVED) {
                now
            } else {
                enrollment.leftAt
            },
            events = enrollment.events + EnrollmentEvent(
                type = type,
                at = now,
                actorUserId = actorUserId,
                fromStatus = enrollment.status,
                toStatus = to,
                note = note,
            ),
        )
    }

    /** Cambia el rol dejando rastro en la auditoría. */
    fun withRole(
        enrollment: Enrollment,
        newRole: CourseRole,
        actorUserId: String,
        now: Long,
        note: String = "",
    ): Enrollment = enrollment.copy(
        role = newRole,
        events = enrollment.events + EnrollmentEvent(
            type = EnrollmentEventType.ROLE_CHANGED,
            at = now,
            actorUserId = actorUserId,
            fromRole = enrollment.role,
            toRole = newRole,
            note = note,
        ),
    )
}
```

---

## 4. Matriz de capacidades

### 4.1 Tabla rol × acción

| Capacidad (`Capability`) | TITULAR | AYUDANTE | ALUMNO | Notas y límites |
|---|:--:|:--:|:--:|---|
| `VIEW_MATERIALS` Ver materiales | ✔ | ✔ | ✔ | El alumno sólo ve los materiales con `Material.visible = true`. |
| `CHAT` Chatear | ✔ | ✔ | ✔ | Requiere inscripción `ACTIVE` y cátedra `published`. |
| `EDIT_MATERIALS` Editar materiales | ✔ | ✔ | ✘ | El ayudante sube/edita/etiqueta; no borra los de otros (borrar = `MODERATE_CHATS` sólo del titular… ver nota). |
| `EDIT_MODEL_CONFIG` Editar config del modelo | ✔ | ✘ | ✘ | Es la configuración **base de la cátedra** (`Course.modelPolicy`, `topK`, `chunkSize`). |
| `OVERRIDE_MODEL` Ajustar el modelo en su cliente | ✔ | ✔ | ✔ | Depende de `modelPolicy.allowStudentOverride` y de `lockedFields`. Es la agencia del alumno. |
| `EDIT_SYSTEM_PROMPT` Cambiar el prompt | ✔ | ✘ | ✘ | `Course.systemPrompt` y `modelPolicy.systemPrompt`. |
| `VIEW_ANALYTICS` Ver analítica | ✔ | ✔ | ✘ | La analítica se calcula **filtrada por la visibilidad efectiva** del observador. |
| `VIEW_CHAT_TEXT` Ver texto de los chats | ✔ | ✔\* | ✘ | \*El ayudante **no ve el texto** por defecto: el tope por rol es `METRICS_ONLY`. El titular ve el texto **sólo** si el alumno lo declaró `FULL` y el docente no le bajó el tope. |
| `MANAGE_ENROLLMENTS` Gestionar inscripciones | ✔ | ✔\*\* | ✘ | \*\*El ayudante acepta, suspende, reactiva y da de baja **alumnos**; no remueve, no toca docentes, no cambia roles. |
| `MANAGE_TEAM` Gestionar el equipo docente | ✔ | ✘ | ✘ | Alta/baja de ayudantes y **cambio de roles**. Exclusivo del titular. |
| `PUBLISH_COURSE` Publicar la cátedra | ✔ | ✘ | ✘ | Activa el código de inscripción y la experiencia del alumno. |
| `REDUCE_STUDENT_VISIBILITY` Bajar la visibilidad de un alumno | ✔ | ✘ | ✘ | Sólo `ALUMNO`; sólo puede bajar el tope (`visibilityCap`). |
| `MODERATE_CHATS` Eliminar/censurar chats | ✔ | ✘ | ✘ | Sólo sobre alumnos; deja evento de auditoría. |
| `EXPORT_DATA` Exportar datos | ✔ | ✔\*\*\* | ✘ | \*\*\*El ayudante exporta **sólo la analítica agregada**; el titular exporta todo (texto incluido, si la visibilidad lo permite). El alumno exporta **sólo lo propio**. |

**Legenda de la granularidad**: `✔` = sí, `✘` = no, con notas cuando hay un
alcance parcial. El alcance parcial **no** se modela con booleanos: se modela
con `AccessControl.exportScope(actor)` y con las transiciones permitidas por
rol (`EnrollmentTransitions.can`).

### 4.2 Código: `Capability` y matriz

```kotlin
// domain/Roles.kt

/**
 * Una acción que alguien con un rol puede intentar hacer en una cátedra.
 *
 * Es el vocabulario único de permisos: la UI, los servicios y los tests
 * hablan de capacidades, no de roles sueltos.
 */
@Serializable
enum class Capability(val label: String) {
    VIEW_MATERIALS("Ver materiales"),
    CHAT("Chatear"),
    EDIT_MATERIALS("Editar materiales"),
    EDIT_MODEL_CONFIG("Editar la configuración base del modelo"),
    OVERRIDE_MODEL("Ajustar el modelo en su propio cliente"),
    EDIT_SYSTEM_PROMPT("Cambiar el prompt del sistema"),
    VIEW_ANALYTICS("Ver la analítica"),
    VIEW_CHAT_TEXT("Ver el texto de las conversaciones"),
    MANAGE_ENROLLMENTS("Gestionar las inscripciones de los alumnos"),
    MANAGE_TEAM("Gestionar al equipo docente"),
    PUBLISH_COURSE("Publicar la cátedra"),
    REDUCE_STUDENT_VISIBILITY("Bajar la visibilidad de un alumno"),
    MODERATE_CHATS("Censurar conversaciones de alumnos"),
    EXPORT_DATA("Exportar datos"),
}

/** Capacidades del titular: control total de la cátedra. */
private val TITULAR_CAPABILITIES: Set<Capability> = setOf(
    Capability.VIEW_MATERIALS,
    Capability.CHAT,
    Capability.EDIT_MATERIALS,
    Capability.EDIT_MODEL_CONFIG,
    Capability.OVERRIDE_MODEL,
    Capability.EDIT_SYSTEM_PROMPT,
    Capability.VIEW_ANALYTICS,
    Capability.VIEW_CHAT_TEXT,
    Capability.MANAGE_ENROLLMENTS,
    Capability.MANAGE_TEAM,
    Capability.PUBLISH_COURSE,
    Capability.REDUCE_STUDENT_VISIBILITY,
    Capability.MODERATE_CHATS,
    Capability.EXPORT_DATA,
)

/**
 * Capacidades del ayudante.
 *
 * Deliberadamente **no** incluye: editar la configuración del modelo, cambiar
 * el prompt, publicar, tocar la visibilidad de un alumno ni gestionar
 * docentes. Sí puede ver el texto, pero el tope por rol lo impide por
 * defecto (§5).
 */
private val AYUDANTE_CAPABILITIES: Set<Capability> = setOf(
    Capability.VIEW_MATERIALS,
    Capability.CHAT,
    Capability.EDIT_MATERIALS,
    Capability.OVERRIDE_MODEL,
    Capability.VIEW_ANALYTICS,
    Capability.VIEW_CHAT_TEXT,
    Capability.MANAGE_ENROLLMENTS,
    Capability.EXPORT_DATA,
)

/** Capacidades del alumno: usar la cátedra, no administrarla. */
private val ALUMNO_CAPABILITIES: Set<Capability> = setOf(
    Capability.VIEW_MATERIALS,
    Capability.CHAT,
    Capability.OVERRIDE_MODEL,
)

// extension sobre CourseRole (en el mismo archivo Roles.kt)
val CourseRole.capabilities: Set<Capability>
    get() = when (this) {
        CourseRole.TITULAR -> TITULAR_CAPABILITIES
        CourseRole.AYUDANTE -> AYUDANTE_CAPABILITIES
        CourseRole.ALUMNO -> ALUMNO_CAPABILITIES
    }
```

### 4.3 Código: alcance de exportación

```kotlin
// domain/AccessControl.kt

/** Qué alcance tiene una exportación de datos para un actor. */
enum class ExportScope(val label: String) {
    /** Todo: padrón, analítica y texto de las conversaciones. */
    FULL("Todo"),

    /** Sólo la analítica agregada de la cátedra (sin texto de conversaciones). */
    AGGREGATES_ONLY("Sólo analítica agregada"),

    /** Sólo los datos propios del usuario. */
    OWN_ONLY("Sólo lo propio"),

    /** Nada. */
    NONE("Nada"),
}
```

### 4.4 Uso en la UI

```kotlin
// Un botón "Subir material" sólo aparece si el usuario tiene la capacidad.
if (session.can(Capability.EDIT_MATERIALS)) { ... }

// Un docente sólo puede moderar los chats de un alumno si además el alumno
// es visible para él.
if (session.canOn(target, Capability.MODERATE_CHATS) &&
    AccessControl.canSeeChatText(session, target, course)) { ... }
```

---

## 5. Visibilidad: dos ejes

### 5.1 El problema

`ChatVisibility` (`FULL` / `METRICS_ONLY` / `NONE`) alcanza para un caso: *el
alumno X*, *el docente Y*. Pero aparecen dos preguntas distintas:

1. **¿Qué acepta compartir el alumno?** → eje **por alumno**.
2. **¿Qué tiene derecho a ver un ayudante?** → eje **por rol del observador**.

Son ejes distintos y se combinan. Un solo campo no alcanza.

### 5.2 Los dos ejes

**Eje 1 — por alumno** (dentro de `Enrollment`):

| Campo | Dueño | Significado |
|---|---|---|
| `visibility` | **el alumno** | su **techo**: hasta dónde comparte. Nadie puede superarlo. |
| `visibilityCap` | **el docente** | el **piso** que le impone al alumno (sólo baja). Sirve para "este alumno lo talks más por la vía que no sea el modelo", etc. |

**Eje 2 — por rol del observador** (dentro de `Course`):

```kotlin
/** Tope de visibilidad que tiene un observador con este rol. */
@Serializable
data class RoleVisibilityRule(
    val role: CourseRole,
    val maxVisibility: ChatVisibility,
)

/**
 * Política de visibilidad de la cátedra: qué puede ver **como máximo** cada
 * rol. La fija el titular y es **retroactiva**.
 */
@Serializable
data class VisibilityPolicy(
    val rules: List<RoleVisibilityRule> = DEFAULT_RULES,
) {
    /** Tope del rol. Si el rol no está en la política, no ve nada. */
    fun maxFor(role: CourseRole): ChatVisibility =
        rules.firstOrNull { it.role == role }?.maxVisibility ?: ChatVisibility.NONE

    companion object {
        /**
         * Por defecto: el titular ve todo, el ayudante ve métricas pero **no
         * el texto**, y el alumno no ve nada de terceros.
         */
        val DEFAULT_RULES: List<RoleVisibilityRule> = listOf(
            RoleVisibilityRule(CourseRole.TITULAR, ChatVisibility.FULL),
            RoleVisibilityRule(CourseRole.AYUDANTE, ChatVisibility.METRICS_ONLY),
            RoleVisibilityRule(CourseRole.ALUMNO, ChatVisibility.NONE),
        )
    }
}
```

### 5.3 La fórmula

```
visibilidad_efectiva(observador, alumno)
    = min( tope_por_rol(observador.role),
           alumno.visibilityCap,
           alumno.visibility )
```

con `NONE < METRICS_ONLY < FULL` (nivel 0 < 1 < 2).

En código:

```kotlin
// domain/AccessControl.kt

/**
 * Reglas de acceso a una cátedra.
 *
 * Funciones puras: reciben (cátedra, observador, objetivo) y devuelven una
 * decisión. La UI las consulta; no las reimplementa.
 */
object AccessControl {

    /**
     * Visibilidad que tiene [observer] sobre los chats de [student].
     *
     * - [observer] `null` = vista "de titular" (el panel del docente por
     *   defecto, o un proceso de importación sin usuario).
     * - El **alumno nunca ve los chats de terceros**: el tope por rol es NONE.
     */
    fun effectiveVisibility(
        course: Course,
        observer: Enrollment?,
        student: Enrollment,
    ): ChatVisibility {
        val roleCap = course.visibilityPolicy.maxFor(observer?.role ?: CourseRole.TITULAR)
        return ChatVisibility.atMost(roleCap, student.visibilityCap, student.visibility)
    }

    /** ¿El observador puede leer el **texto** de las conversaciones del alumno? */
    fun canSeeChatText(observer: Enrollment?, student: Enrollment, course: Course): Boolean {
        if (observer == null) return false
        if (observer.userId == student.userId) return true // cada uno ve lo suyo
        if (!observer.can(Capability.VIEW_CHAT_TEXT)) return false
        return effectiveVisibility(course, observer, student).showsText
    }

    /** ¿El observador puede ver las **métricas** del alumno? */
    fun canSeeMetrics(observer: Enrollment?, student: Enrollment, course: Course): Boolean {
        if (observer == null) return false
        if (observer.userId == student.userId) return true
        if (!observer.can(Capability.VIEW_ANALYTICS)) return false
        return effectiveVisibility(course, observer, student).showsMetrics
    }

    /** Alcance de exportación para un actor. */
    fun exportScope(actor: Enrollment): ExportScope = when (actor.role) {
        CourseRole.TITULAR -> ExportScope.FULL
        CourseRole.AYUDANTE -> ExportScope.AGGREGATES_ONLY
        CourseRole.ALUMNO -> ExportScope.OWN_ONLY
    }

    /** Materiales que el actor puede ver. */
    fun visibleMaterials(actor: Enrollment, materials: List<Material>): List<Material> =
        if (actor.role == CourseRole.ALUMNO) materials.filter { it.visible } else materials
}
```

Y el nivel en `ChatVisibility`:

```kotlin
// domain/Roles.kt (reemplaza el enum actual)

@Serializable
enum class ChatVisibility(
    val label: String,
    val description: String,
    /** Nivel de acceso: 0 = nada, 1 = métricas, 2 = texto. */
    val level: Int,
) {
    FULL("Completo", "El docente ve el texto de las conversaciones y el detalle de cada mensaje.", 2),
    METRICS_ONLY("Solo métricas", "El docente ve estadísticas y calidad de las respuestas, pero no el texto.", 1),
    NONE("Oculto", "El docente no ve ni el texto ni las estadísticas de este alumno.", 0),
    ;

    /** ¿El texto de los mensajes es visible para el docente? */
    val showsText: Boolean get() = level >= FULL.level

    /** ¿Las estadísticas son visibles para el docente? */
    val showsMetrics: Boolean get() = level >= METRICS_ONLY.level

    companion object {
        /** El más restrictivo de los dos. */
        fun atMost(a: ChatVisibility, b: ChatVisibility): ChatVisibility =
            if (a.level <= b.level) a else b

        /** El más permisivo de los dos. */
        fun atLeast(a: ChatVisibility, b: ChatVisibility): ChatVisibility =
            if (a.level >= b.level) a else b

        /** El más restrictivo de los tres. */
        fun atMost(a: ChatVisibility, b: ChatVisibility, c: ChatVisibility): ChatVisibility =
            atMost(atMost(a, b), c)

        fun fromName(name: String): ChatVisibility? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
```

### 5.4 Respuestas directas

**¿Un ayudante tiene menos visibilidad que el titular?**
Sí, **por defecto**: el tope por rol del ayudante es `METRICS_ONLY` y el del
titular es `FULL`. Ve las mismas métricas (volumen, días activos, latencia,
tokens, calidad de citas) pero **no el texto** de las conversaciones individuales.

**¿El titular puede ver el texto que un ayudante no ve?**
Sí, **si y sólo si** el alumno declaró `FULL` (`visibility = FULL`) y el
docente no le bajó el tope (`visibilityCap = FULL`). Con la declaración
`METRICS_ONLY` del alumno, **nadie** ve el texto: ni el titular. Esa es la
garantía de "el docente no ve lo que no debe".

**El titular puede cambiar el tope por rol** (subir al ayudante a `FULL`) desde
la configuración de la cátedra. Es **retroactivo**: si sube el tope, el
ayudante empieza a ver textos que antes no veía, y viceversa.

**Tabla de visibilidad efectiva** (alumno con `visibility = FULL`,
`visibilityCap = FULL`):

| Observador | Tope por rol | Efectiva | ¿Texto? | ¿Métricas? |
|---|---|---|---|---|
| Titular | FULL | FULL | Sí | Sí |
| Ayudante | METRICS_ONLY | METRICS_ONLY | No | Sí |
| Otro alumno | NONE | NONE | No | No |

**Alumno con `visibility = METRICS_ONLY`:**

| Observador | Efectiva | ¿Texto? | ¿Métricas? |
|---|---|---|---|
| Titular | METRICS_ONLY | No | Sí |
| Ayudante | METRICS_ONLY | No | Sí |

**Alumno con `visibility = NONE`:** nadie ve nada, ni el titular. El docente ve
el alumno en el padrón, pero sin datos.

---

## 6. Caso límite: IA local (el servidor no ve los chats)

### 6.1 Qué pasa realmente

Con `ModelMode.LOCAL`, el modelo corre en el dispositivo del alumno: los
**mensajes nunca salen de la máquina del alumno**. No hay texto, ni métricas, ni
timestamps en el servidor. No es un problema de permisos: es una ausencia de
datos.

Por eso la visibilidad de ese alumno **no se decide en el servidor**: se
declara en el cliente y se comunica como un dato de la inscripción.

### 6.2 ¿Quién elige la visibilidad?

**El alumno.** Con un matiz importante:

- El **alumno declara** su techo (`visibility`). Es el que más puede ver de
  sí mismo.
- El **docente puede bajarlo** (`visibilityCap`), nunca subirlo.
- El **tope por rol** limita al observador, no al alumno.
- **Nadie** puede ver más de lo que el alumno declaró. Ni el titular.

Si la institución necesita el texto, la vía es que el **alumno lo comparta
explícitamente** (importación), no que el docente lo obligue.

### 6.3 Cómo se declara "opto por IA local"

Flujo en el cliente del alumno:

1. El alumno cambia el modo del modelo a **IA local** (sólo si
   `course.allowLocalModels`).
2. Kitoru muestra el diálogo de consentimiento:

   > **¿Qué querés compartir con la cátedra?**
   > - **Nada** → los docentes no ven nada de lo que hago acá.
   > - **Solo métricas** → ven volúmenes y calidad, no el texto.
   > - **Todo** → ven el texto de estas conversaciones (sólo si las importás).

3. La elección escribe en la inscripción:
   `aiMode = LOCAL`, `visibility = <elección>`, `declaredLocalAiAt = now`.
   En el milestone 1 (archivos JSON) la declaración se guarda en el perfil
   local del alumno y se sincroniza con la cátedra cuando hay servidor.

### 6.4 Qué ve el docente en ese caso

| Elemento | Qué ve el docente |
|---|---|
| Padrón | El alumno con badge **"IA local declarada (12/03)"**. |
| Analítica del alumno | Fila con **"sin datos compartidos"** en lugar de "0 mensajes" (0 mensajes y "no hay datos" son cosas distintas). |
| `CourseAnalytics` | Aparece en `studentsWithoutSharedData`, **no** en `inactiveStudents`. |
| Texto | Nada, hasta que el alumno importe. |
| Métricas | Sólo de las conversaciones **importadas**, y sólo si la visibilidad efectiva lo permite. |

Esto exige separar en la analítica dos conceptos que hoy están mezclados:

- `inactiveStudents`: inscrita, **compartió** datos, pero nunca chateó.
- `studentsWithoutSharedData`: inscrita, **no compartió** nada (IA local sin importar).

### 6.5 Exportar (alumno) e importar (docente)

**Exportación — desde la app del alumno** ("Exportar mis chats"):

```json
{
  "format": "kitoru.chats",
  "version": 1,
  "courseId": "IA-2026-1",
  "student": { "userId": "u-ana", "displayName": "Ana Pérez" },
  "exportedAt": 1770200000000,
  "mode": "LOCAL",
  "threads": [
    {
      "id": "IA-2026-1|t-1",
      "title": "Duda sobre backprop",
      "createdAt": 1770100000000,
      "updatedAt": 1770100600000,
      "modelLabel": "Phi-4-mini-instruct-Q4_K_M",
      "tags": ["parcial"],
      "messages": [
        {
          "role": "user",
          "text": "¿Cómo se define la derivada en una red profunda?",
          "createdAt": 1770100000000,
          "sources": ["materiales:backprop.pdf#p12"],
          "tokensIn": 24
        },
        {
          "role": "assistant",
          "text": "La derivada se define como el límite...",
          "createdAt": 1770100600000,
          "sources": ["materiales:backprop.pdf#p12"],
          "citation": { "verified": 2, "unsupported": 0, "unknown": 0 },
          "latencyMs": 1820,
          "tokensOut": 310
        }
      ]
    }
  ]
}
```

**Importación — desde la app del docente** ("Importar conversaciones"):

1. Validar `format` y `version`; si el `courseId` no coincide, se pregunta al
   docente a qué cátedra pertenece.
2. Identificar al alumno por `student.userId` → debe existir una inscripción
   en esa cátedra. Si no, error: `"El archivo corresponde a un usuario que no
   está inscrito en esta cátedra."`
3. **Aplicar la visibilidad declarada por ese alumno** (esta es la regla clave):

| `alumno.visibility` | Qué hace la importación |
|---|---|
| `FULL` | Importa todo (texto, métricas, citas). |
| `METRICS_ONLY` | Importa **sólo métricas**: los mensajes se guardan con `text = ""` y `redacted = true`. Se conservan conteos, latencia, tokens, fuentes y verificación de citas. |
| `NONE` | **Rechaza la importación**: `"Este alumno declaró que no comparte sus conversaciones. Pedile que cambie la visibilidad antes de importar."` |

   Nota: la redacción ocurre **en el momento de importar**, no al leer. El texto
   nunca se escribe en el disco del docente si el alumno no lo autorizó. La
   operación **no es reversible** → la UI pide confirmación explícita:
   `"Se importarán 3 conversaciones con el texto oculto. ¿Continuar?"`
4. Los hilos importados se guardan con `origin = LOCAL_IMPORT` (campo nuevo en
   `ChatThread`) para que la UI los distinga.
5. Se actualiza `enrollment.lastImportAt = now` y se agrega un evento
   `CHATS_IMPORTED` con el detalle ("3 conversaciones, 42 mensajes, texto
   visible: no").
6. Resultado en pantalla: `"Se importaron 3 conversaciones y 42 mensajes
   (texto oculto por la visibilidad del alumno)."`

### 6.6 Reglas adicionales

- Si `course.allowLocalModels == false`, la app del alumno **no** ofrece IA
  local y `aiMode` queda en `HOSTED`.
- El modo `HYBRID` se comporta como `LOCAL` para las conversaciones locales
  (que no llegan) y como `HOSTED` para las del servidor.
- Un alumno en IA local **sigue contando** para el padrón y para el tope de
  alumnos; simplemente no aporta datos hasta que importe.

---

## 7. Cambio de rol en el medio de la cursada

### 7.1 Regla general

**El rol es una capacidad, no una propiedad de los datos.** Los datos se
atribuyen por `userId`; el rol no los mueve ni los borra.

| Dato | Dueño real | Efecto de cambiar de rol |
|---|---|---|
| Materiales (`Material.uploadedBy`) | la **cátedra** | **No se mueven ni se borran.** Siguen publicados. La autoría queda para la auditoría. |
| Conversaciones (`ChatThread.authorId`) | el **usuario** | No se mueven. Cambia **quién puede verlas**, porque el tope por rol del observador se recalcula. |
| Analítica | los **chats** | Se recalcula con el rol y la visibilidad actuales. |
| Configuración del modelo (`Course.modelPolicy`, `Course.systemPrompt`) | la **cátedra** | No tiene dueño individual; el nuevo titular la hereda. |
| **Titularidad** (`Course.ownerUserId`) | el **titular** | **Sí cambia**: hay que transferirla explícitamente. |

### 7.2 Escenario: un ayudante pasa a titular

1. El titular ejecuta "Cambiar rol → Titular".
2. Se aplica `EnrollmentTransitions.withRole(...)`: el `role` pasa a `TITULAR` y
   queda un evento `ROLE_CHANGED` con `fromRole`/`toRole`.
3. **Los materiales que subió el ayudante siguen en la cátedra**, con
   `uploadedBy = su userId`. No hay que reindexar nada (el RAG no depende del
   rol).
4. **Sus conversaciones** (las que tenía como ayudante) ahora son visibles
   para el nuevo titular: el tope por rol de `TITULAR` es `FULL`.
5. **La titularidad de la cátedra** (`ownerUserId`) **no** se transfiere
   automáticamente: el nuevo titular debe ejecutar "Transferir titularidad"
   (o el docente que la tenía la mantiene). Esto evita que un cambio de rol
  de golpe se entere de la propiedad de la cátedra por accidente.
6. **Invariante**: la cátedra sigue teniendo al menos un titular activo (el
   nuevo, o el anterior si sigue como titular).

### 7.3 Escenario inverso: un titular pasa a ayudante

- Pierde inmediatamente: `EDIT_MODEL_CONFIG`, `EDIT_SYSTEM_PROMPT`,
  `PUBLISH_COURSE`, `MANAGE_TEAM`, `REDUCE_STUDENT_VISIBILITY`,
  `MODERATE_CHATS`.
- **Sus conversacionesprevious pasan a estar sujetas al tope de `AYUDANTE`**
  (`METRICS_ONLY` por defecto) → el titular **deja de ver su texto** salvo que
  suba el tope del ayudante en la política de visibilidad. Es una consecuencia
  no obvia: la política de visibilidad es **retroactiva** y hay que avisarlo en
  la UI ("Al bajar a Ayudante, sus conversaciones pasan a 'solo métricas' para
  los titulares").
- **Sus materiales siguen publicados.**
- Si era el único titular, la transición se rechaza: `"La cátedra necesita al
  menos un profesor titular activo."` → primero hay que promover a otro.

### 7.4 Escenario: un alumno es promovido a ayudante

- Gana las capacidades de `AYUDANTE`.
- Su declaración de visibilidad (`visibility`) **se conserva** pero deja de
  aplicar (un docente no es objeto de analítica de alumnos). Si más adelante
  vuelve a ser alumno, la declaración vuelve a regir: es coherente y no obliga
  al usuario a reconfigurar nada.
- Su historial de chats como alumno **no se borra** ni se mezcla con el de
  docente: la analítica separa por rol (`StudentAnalytics.role` ya existe).

### 7.5 Baja de un docente

- El docente removido (`REMOVED`) pierde todas las capacidades.
- Sus **materiales quedan** en la cátedra con `uploadedBy` apuntando a él. El
  flujo de baja ofrece una opción explícita: *"Conservar sus materiales"*
  (por defecto) o *"Despublicar sus materiales"* (los deja con
  `visible = false`; el RAG los saca del índice en el próximo `sync`).
  **Nunca se borran automáticamente**: borrarlos rompería el índice y la
  historia de los alumnos.

---

## 8. Límites

### 8.1 Tope de alumnos por cátedra

```kotlin
// Course
val maxStudents: Int = 0,   // 0 = sin tope
val maxAssistants: Int = 0, // 0 = sin tope
```

Reglas:

- Se cuentan las inscripciones con `role == ALUMNO` y `status ∈ {PENDING,
  ACTIVE, SUSPENDED}`. Los docentes no cuentan. Los alumnos dados de baja
  (`LEFT`/`REMOVED`) no cuentan (el cupo se libera).
- Al anotarse con el código, si el tope está lleno:
  `"La cátedra IA-2026-1 alcanzó el tope de 40 alumnos."`
- Lo mismo al agregar un alumno a mano desde el panel del docente.
- `maxAssistants` funciona igual con `role == AYUDANTE`.
- El docente puede subir el tope en cualquier momento; bajar el tope por
  debajo de la cantidad de inscritos **no** desinscribe a nadie: la UI avisa
  `"Hay 47 alumnos y el tope nuevo es 40; no se desinscribió a nadie."`

### 8.2 Alumnos sin actividad

Dos categorías, ya separadas en la analítica:

| Categoría | Definición | Dónde |
|---|---|---|
| **Nunca chateó** | inscrito, con datos compartidos, 0 mensajes. | `CourseAnalytics.inactiveStudents` (existente). |
| **Inactivo hace tiempo** | último acceso (`lastAccessAt`) hace más de `course.inactivityDays` (por defecto 30). | `CourseAnalytics.staleStudents` (nuevo). |

```kotlin
@Serializable
data class StaleStudent(
    val userId: String,
    val displayName: String,
    val daysSinceLastAccess: Int,
    val lastAccessAt: Long,
)
```

Reglas:

- **Nunca se da de baja automáticamente.** Se marca, se avisa y decide el
  docente. (Regla de diseño #6: "el estudiante conserva agencia".)
- El panel de analítica muestra el filtro "Sin actividad" y un botón de
  **baja masiva** con confirmación.
- Un alumno en IA local **no** cuenta como inactivo: está en
  `studentsWithoutSharedData`, que es una categoría aparte.

### 8.3 Baja masiva

```kotlin
/** Acciones de la baja masiva. */
enum class BulkAction(val label: String) {
    SUSPEND("Suspender"),
    REINSTATE("Reactivar"),
    LEAVE("Dar de baja"),
    REMOVE("Remover"),
}

/** Resultado de una baja masiva: qué se aplicó y qué se omitió (con motivo). */
data class BulkResult(
    val applied: Int = 0,
    val skipped: List<BulkSkip> = emptyList(),
)

data class BulkSkip(
    val enrollmentId: String,
    val displayName: String,
    val reason: String,
)
```

Reglas:

1. **Una sola escritura**: el servicio arma la lista nueva y la persiste de
   una vez (`JsonListStore.update`), no escribe por alumno.
2. **Cada inscripción afectada** recibe su propio evento de auditoría (con
   `actorUserId` del docente), más una nota con la acción masiva.
3. **No se tocan docentes** con acciones de alumno: se omiten con motivo
   `"Es docente de la cátedra"`.
4. **Se omiten las transiciones inválidas** (ej. suspender a alguien que ya
   está suspendido): motivo `"Ya estaba suspendida"`.
5. **Confirmación previa** con el detalle: `"Vas a dar de baja a 12 alumnos.
   1 ya estaba dado de baja y 2 son docentes. ¿Continuar?"`.
6. Tras aplicar, mensaje: `"Se dieron de baja 12 alumnos; 3 fueron omitidos."`.
7. El resultado se puede **exportar a CSV** (padrón + estado + fecha de baja)
   para el acta de la cátedra.

### 8.4 Inscribirse con el código

```
1. El alumno mete el código de la cátedra.
2. Si !course.published → "La cátedra no está publicada todavía."
3. Si el tope está lleno → "La cátedra alcanzó el tope de N alumnos."
4. Si ya está inscrito (inscripción no REMOVED) → se devuelve la misma.
5. Si estaba LEFT/REMOVED → se reactiva (LEFT→ACTIVE o LEFT→PENDING).
6. Si course.requireApproval → estado PENDING (el docente tiene que aceptar).
   Si no → ACTIVE y acceptedAt = now.
7. Se registra el evento ENROLLED.
```

---

## 9. Servicios y repositorios

### 9.1 `EnrollmentRepository`

```kotlin
interface EnrollmentRepository {
    suspend fun listForCourse(courseId: String): List<Enrollment>
    suspend fun listForUser(userId: String): List<Enrollment>
    suspend fun get(id: String): Enrollment?
    suspend fun save(enrollment: Enrollment)
    suspend fun delete(id: String)

    /** La inscripción vigente de esa persona en esa cátedra (cualquier estado no terminal). */
    suspend fun findByUser(courseId: String, userId: String): Enrollment?

    /** Inscripciones que cuentan (PENDING/ACTIVE/SUSPENDED) de un rol dado. */
    suspend fun listEnrolled(courseId: String, role: CourseRole? = null): List<Enrollment>

    /** Cantidad de alumnos que ocupan cupo (PENDING/ACTIVE/SUSPENDED). */
    suspend fun countStudents(courseId: String): Int
}
```

Implementación en `FileEnrollmentRepository` (misma base `FileBackedRepository`):

```kotlin
override suspend fun findByUser(courseId: String, userId: String): Enrollment? =
    all().firstOrNull {
        it.courseId == courseId && it.userId == userId &&
            it.status != EnrollmentStatus.REMOVED
    }

override suspend fun listEnrolled(courseId: String, role: CourseRole?): List<Enrollment> =
    all().filter {
        it.courseId == courseId && it.status.isEnrolled && (role == null || it.role == role)
    }

override suspend fun countStudents(courseId: String): Int =
    listEnrolled(courseId, CourseRole.ALUMNO).size
```

### 9.2 `EnrollmentService` (firma de la aplicación)

```kotlin
class EnrollmentService(
    private val enrollments: EnrollmentRepository,
    private val courses: CourseRepository,
) {
    /** El alumno se anota con el código. */
    suspend fun joinByCode(code: String, user: UserProfile, now: Long): Enrollment

    /** El docente acepta una inscripción pendiente. */
    suspend fun accept(actor: Enrollment, targetId: String, now: Long): Enrollment

    suspend fun suspendEnrollment(actor: Enrollment, targetId: String, now: Long, note: String = ""): Enrollment

    suspend fun reinstate(actor: Enrollment, targetId: String, now: Long): Enrollment

    /** El alumno se da de baja. */
    suspend fun leave(enrollment: Enrollment, now: Long): Enrollment

    /** El docente remueve a alguien. */
    suspend fun remove(actor: Enrollment, targetId: String, now: Long, unpublishMaterials: Boolean = false): Enrollment

    /** Cambio de rol (sólo titular). */
    suspend fun changeRole(actor: Enrollment, targetId: String, newRole: CourseRole, now: Long): Enrollment

    /** Transferencia de titularidad de la cátedra. */
    suspend fun transferOwnership(actor: Enrollment, newOwnerUserId: String, now: Long): Course

    /** El docente baja el tope de visibilidad de un alumno. */
    suspend fun setVisibilityCap(actor: Enrollment, targetId: String, cap: ChatVisibility, now: Long): Enrollment

    /** El alumno declara su modo de IA y su visibilidad. */
    suspend fun declareAiMode(enrollment: Enrollment, mode: StudentAiMode, visibility: ChatVisibility, now: Long): Enrollment

    /** Registro de acceso (lo llama el cliente al abrir la cátedra). */
    suspend fun touch(enrollmentId: String, now: Long): Enrollment

    /** Baja / suspensión masiva. */
    suspend fun bulk(actor: Enrollment, targetIds: List<String>, action: BulkAction, now: Long): BulkResult
}
```

Reglas transversales del servicio (todas con mensajes en español y
`require`/`error`):

```kotlin
private fun requireActor(actor: Enrollment, target: Enrollment, capability: Capability) {
    require(actor.canOn(target, capability)) {
        "Tu rol (${actor.role.label}) no puede administrar esta inscripción."
    }
}

private fun requireTitular(courseId: String, enrollments: List<Enrollment>) {
    require(enrollments.any { it.courseId == courseId && it.role == CourseRole.TITULAR && it.status.isEnrolled }) {
        "La cátedra necesita al menos un profesor titular activo."
    }
}
```

### 9.3 Cambios en `Course`

```kotlin
@Serializable
data class Course(
    // … lo que ya existe …
    val ownerUserId: String = "",      // el titular que administra la cátedra
    val published: Boolean = false,     // publicada: el código de inscripción funciona
    val requireApproval: Boolean = false, // las inscripciones nuevas quedan PENDING
    val maxStudents: Int = 0,          // 0 = sin tope
    val maxAssistants: Int = 0,        // 0 = sin tope
    val visibilityPolicy: VisibilityPolicy = VisibilityPolicy(),
    val inactivityDays: Int = 30,      // umbral de "inactivo hace mucho"
)
```

> El invariante "hay al menos un titular activo" no vive en la cátedra sino en el
> padrón: lo verifica `EnrollmentService` contra `enrollments.listEnrolled(courseId, TITULAR)`.
> Un curso archivado puede quedar sin titular sin romper nada; uno publicado,
> no.

---

## 10. Cambios en `AnalyticsAnalyzer`

La analítica actual filtra por `enrollment.visibility.showsMetrics`, lo que con
dos ejes de visibilidad **no alcanza**: hay que pasarle **quién está mirando**.

```kotlin
object AnalyticsAnalyzer {

    /**
     * @param observer el docente que mira (null = vista de titular).
     */
    fun analyze(
        course: Course,
        enrollments: List<Enrollment>,
        threads: List<ChatThread>,
        messages: List<ChatMessage>,
        observer: Enrollment? = null,
    ): CourseAnalytics {
        // 1. Sólo las inscripciones vigentes y visibles para QUIEN MIRA.
        val visible = enrollments.filter {
            it.isEnrolled &&
                AccessControl.canSeeMetrics(observer, it, course)
        }
        // …
    }
}
```

Además:

- `perDay` y el resto de agregados deben usar **el mismo filtro** que
  `visible`. Hoy `topSources` agrega **todos** los mensajes sin mirar la
  visibilidad: es un **agujero de visibilidad** que hay que cerrar (un alumno
  `NONE` igual filtra el corpus más consultado).
- `StudentAnalytics` gana `dataShared: Boolean` (para distinguir "no chatted"
  de "no hay datos") y `sharedVisibility: ChatVisibility`.
- `CourseAnalytics` gana:
  - `staleStudents: List<StaleStudent>`
  - `studentsWithoutSharedData: List<String>` (IA local sin importar)

```kotlin
// dentro de analyze()
val stale = visible
    .filter { it.role == CourseRole.ALUMNO && it.lastAccessAt > 0L }
    .map {
        StaleStudent(
            userId = it.userId,
            displayName = it.displayName,
            daysSinceLastAccess = daysBetween(it.lastAccessAt, now),
            lastAccessAt = it.lastAccessAt,
        )
    }
    .filter { it.daysSinceLastAccess >= course.inactivityDays }
```

---

## 11. Recetas de uso (la parte que más se va a tocar en la UI)

```kotlin
// ¿Puedo mostrar el chat de este alumno?
if (AccessControl.canSeeChatText(session, student, course)) {
    ChatViewer(student)
} else if (AccessControl.canSeeMetrics(session, student, course)) {
    MetricsViewer(student)   // sin texto
} else {
    NoDataViewer()            // "sin datos compartidos"
}

// ¿Puedo poner el botón "Aceptar"?
if (enrollment.status == EnrollmentStatus.PENDING && session.canOn(enrollment, Capability.MANAGE_ENROLLMENTS)) {
    Button("Aceptar") { service.accept(session, enrollment.id, now) }
}

// ¿El ayudante puede quitar de la lista a este alumno?
val canRemove = session.canOn(enrollment, Capability.MANAGE_ENROLLMENTS) &&
    EnrollmentTransitions.can(session.role, enrollment.status, EnrollmentStatus.REMOVED)
// → false para el ayudante (sólo el titular puede remover)
```

---

## 12. Checklist de implementación

- [ ] `Roles.kt`: `Capability`, `CourseRole.capabilities`, `ChatVisibility.level/atMost/atLeast`.
- [ ] `Course.kt`: `Enrollment` final, `EnrollmentStatus`, `StudentAiMode`, `EnrollmentEvent(Type)`.
- [ ] `Course.kt` (clase `Course`): `ownerUserId`, `published`, `requireApproval`, `maxStudents`, `maxAssistants`, `visibilityPolicy`, `inactivityDays`.
- [ ] `AccessControl.kt`: `AccessControl`, `VisibilityPolicy`, `RoleVisibilityRule`, `ExportScope`.
- [ ] `EnrollmentService.kt`: `EnrollmentTransitions`, `EnrollmentService`, `BulkAction`, `BulkResult`, `BulkSkip`.
- [ ] `Repositories.kt` / `FileRepositories.kt`: `findByUser`, `listEnrolled`, `countStudents`.
- [ ] `AnalyticsAnalyzer`: `observer` como parámetro + filtro por visibilidad efectiva + `staleStudents` + `studentsWithoutSharedData` + cerrar el agujero de `topSources`.
- [ ] Cross-doc: `ChatThread.origin` y `ChatMessage.redacted`.
- [ ] Tests (`core/src/desktopTest`):
  - `EnrollmentTransitionsTest`: todas las transiciones válidas e inválidas; `apply` sella `acceptedAt`/`leftAt` y agrega el evento.
  - `CapabilityTest`: matriz rol × capacidad; `can` falso para estados no `ACTIVE`.
  - `AccessControlTest`: `effectiveVisibility` con los tres ejes; ayudante no ve texto por defecto; alumno `NONE` no lo ve nadie; `atMost` con tres argumentos.
  - `EnrollmentServiceTest`: tope de alumnos; invariante de titularidad; ayudante no puede remover ni tocar docentes; baja masiva con `skipped` y motivo.
  - `AnalyticsAnalyzerTest`: analítica filtrada por observador; `studentsWithoutSharedData` para IA local; `topSources` sin datos de alumnos ocultos.

---

## 13. Decisiones descartadas (y por qué)

| Alternativa | Por qué no |
|---|---|
| `role: CourseRole?` con `null` = pendiente | Mezcla "no aceptada" con "no anotada" y rompe el switch de roles. Un enum de estado explícito es más claro y serializa mejor. |
| Un solo campo `visibility` en `Enrollment` | No alcanza para el eje por rol (ayudante vs titular) ni para distinguir "lo que declaró el alumno" de "lo que el docente permite". |
| `canChat: Boolean`, `canSeeAnalytics: Boolean` sueltos en el modelo | Se desincronizan de los roles; cada nueva acción obliga a tocar 3 archivos. Un enum de capacidades es extensible y testeable. |
| Un enum `CourseRole` por alumno (sin estado) | No modela "se anotó pero no lo aceptó", que es un flujo real con código de inscripción. |
| Visibility por hilo (`ChatThread.visibility`) | Demasiado granular para el caso de uso: la decisión es por alumno y se cambia en bloque. |
| Borrar los materiales de un docente que se va | Rompe el índice RAG y la historia de los alumnos. Se ofrece "despublicar", no borrar. |
| Importar chats locales automáticamente al detectar el archivo | El docente tiene que elegir conscientemente qué importa; la importación escribe datos en su disco. |
