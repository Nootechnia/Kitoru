# 06 · Tareas y evaluaciones

> Subsistema de tareas y evaluaciones (estilo Moodle) de Kitoru: qué publica
> la cátedra, qué entrega el alumno, cómo entra la IA en el flujo de
> evaluación y qué ve el docente.
>
> Dominio: `kitoru.domain` (`:core`). Motores de IA: `:backend` (RAG + agente).

---

## 1. Panorama

El subsistema tiene tres entidades y un paquete de apoyo:

```
Course (aiPolicy)                ← política de IA de la cátedra
   │
   ├── Assignment (tarea)        ← publica el docente
   │      ├── materialIds ──────→ Material   (material de referencia)
   │      ├── rubric ───────────→ Rubric     (criterio + escala)
   │      ├── teacherBrief ─────→ TeacherBrief (solución/pistas para el docente)
   │      └── (ChatThread.assignmentIds) ← vínculo con el chat del alumno
   │
   ├── Submission (entrega)      ← entrega el alumno
   │      ├── text + files
   │      ├── threadIds ─────────→ ChatThread  (el proceso, no sólo el resultado)
   │      └── aiDeclaration
   │
   └── AssignmentFeedback        ← revisa el docente
          ├── text + score + criteriaScores
          └── aiMetrics (métricas deterministas) + aiProposal (sugerencias)
```

Reglas que guían todo el diseño:

1. **El docente no ve lo que no debe.** Lo que el docente ve de un chat sigue
   mandando `Enrollment.visibility` (`ChatVisibility.FULL` / `METRICS_ONLY` /
   `NONE`). La política de IA **no** cambia esa matriz: no da permiso extra ni
   lo quita. Sólo decide qué puede hacer el alumno y qué debe declarar.
2. **Determinista.** Las métricas (fundamentación, citas, ratios) salen de
   funciones puras sobre texto — nunca del modelo. El modelo *propone*; la
   nota la pone el docente.
3. **Portable.** Nada de `java.*` en `commonMain`; la persistencia de archivos
   usa `kitoru.platform.FileSystem`.
4. **Fail-fast en español**, como en el resto del dominio.

---

## 2. Enums nuevos

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/** Qué tiene que entregar el alumno. */
@Serializable
enum class SubmissionType(val label: String) {
    TEXT("Texto"),
    FILE("Archivo"),
    BOTH("Texto y archivo"),
    ;

    /** ¿La tarea acepta un texto escrito por el alumno? */
    val acceptsText: Boolean get() = this == TEXT || this == BOTH

    /** ¿La tarea acepta archivos adjuntos? */
    val acceptsFiles: Boolean get() = this == FILE || this == BOTH

    companion object {
        fun fromName(name: String): SubmissionType? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** Si la tarea suma a la nota o es sólo de práctica. */
@Serializable
enum class AssessmentType(val label: String) {
    /** No cuenta en la nota: sirve para practicar y para que el docente vea el proceso. */
    FORMATIVE("Formativa"),

    /** Cuenta en la nota. */
    SUMMATIVE("Sumativa"),
}

/** Estado de una entrega del alumno. */
@Serializable
enum class SubmissionStatus(val label: String) {
    DRAFT("Borrador"),
    SUBMITTED("Entregada"),
    GRADED("Corregida"),
    ;

    /** ¿Se puede seguir editando? Sólo el borrador. */
    val isEditable: Boolean get() = this == DRAFT

    /** ¿Ya está entregada (el alumno no la puede tocar más)? */
    val isDelivered: Boolean get() = this != DRAFT

    companion object {
        fun fromName(name: String): SubmissionStatus? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
```

---

## 3. `Assignment` — la tarea

### 3.1 Campos

| Campo | Tipo | Qué es |
|---|---|---|
| `id` | `String` | Identificador. Convención: `"<courseId>\|tarea-<n>"`. |
| `courseId` | `String` | Cátedra dueña. |
| `title` | `String` | Título de la tarea. |
| `statement` | `String` | **Enunciado en Markdown**. Es el texto que ve el alumno. |
| `materialIds` | `List<String>` | **Material de referencia asociado**: ids de `Material` de la cátedra que el docente adjunta a la tarea (apuntes, slides, papers). El RAG de la tarea indexa sólo estos. |
| `dueAt` | `Long` | **Fecha de entrega** (epoch millis). `0` = sin fecha. |
| `points` | `Int` | **Puntos** de la tarea (nota máxima si es sumativa). |
| `submissionType` | `SubmissionType` | **Tipo de entrega**: texto, archivo o ambos. |
| `allowAi` | `Boolean` | **Si admite IA**. Interruptor duro: en `false` el alumno no puede trabajar la tarea con IA. |
| `chatCanSuggest` | `Boolean` | **Si el chat puede "sugerir" respuestas**: con `false` la IA sólo da pistas y preguntas guía; con `true` puede proponer texto (según lo que permita la política). |
| `assessment` | `AssessmentType` | **Formativo o sumativo**. |
| `published` | `Boolean` | **Publicada**: los alumnos la ven y pueden trabajar. `false` = borrador del docente. |
| `publishedAt` | `Long` | Cuándo se publicó. |
| `rubric` | `Rubric?` | Rúbrica (criterio + escala) que usa la IA para pre-evaluar. |
| `publishedHints` | `List<String>` | Pistas que el docente **sí** publica (subset de `teacherBrief.hints`). |
| `teacherBrief` | `TeacherBrief?` | **Solución/pista que sugirió la IA para el docente**. Nunca visible para el alumno. |
| `maxAttempts` | `Int` | Entregas máximas por alumno. `1` = una sola entrega. |
| `allowLate` / `lateUntil` | `Boolean` / `Long` | Entregas tardías y hasta cuándo. |
| `aiPolicyOverride` | `AiPolicy?` | Política de IA **de esta tarea** (si es `null`, manda la de la cátedra). |
| `tags` | `List<String>` | Etiquetas ("parcial 1", "entrega de código"). |
| `createdBy` / `createdAt` / `updatedAt` | `String` / `Long` / `Long` | Autoría y auditoría. |

### 3.2 Kotlin final

`core/src/commonMain/kotlin/kitoru/domain/Assignment.kt`

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Tarea publicada por la cátedra.
 *
 * Es el ancla del subsistema de evaluaciones: define qué se pide, cuándo,
 * cómo se entrega y **qué puede hacer la IA** (ver [aiPolicyOverride] y
 * [allowAi]).
 *
 * Visible para los alumnos sólo si [published] es `true`.
 */
@Serializable
data class Assignment(
    val id: String,
    val courseId: String,

    // ---- Qué se pide ----
    val title: String,
    /** Enunciado en Markdown. */
    val statement: String = "",
    /** Material de referencia de la cátedra asociado (ids de [Material]). */
    val materialIds: List<String> = emptyList(),

    // ---- Cuándo y cuánto ----
    /** Fecha de entrega (epoch millis). 0 = sin fecha. */
    val dueAt: Long = 0L,
    /** Puntos de la tarea. */
    val points: Int = 0,
    /** Tipo de entrega que se pide. */
    val submissionType: SubmissionType = SubmissionType.TEXT,
    /** ¿Es formativa (no cuenta) o sumativa (cuenta en la nota)? */
    val assessment: AssessmentType = AssessmentType.FORMATIVE,

    // ---- IA ----
    /**
     * Si la tarea admite IA.
     *
     * Interruptor duro: con `false` no se puede abrir el chat de trabajo ni
     * vincular hilos, y usar IA cuenta como violación de la política.
     */
    val allowAi: Boolean = true,
    /**
     * Si el chat puede *sugerir* respuestas.
     *
     * Con `false` la IA de esta tarea sólo puede dar pistas, conceptos y
     * preguntas guía: no escribe un texto que el alumno pueda entregar.
     * Con `true` puede proponer, en la medida en que lo permita la política
     * de la cátedra.
     */
    val chatCanSuggest: Boolean = false,
    /**
     * Política de IA de esta tarea.
     *
     * Si es `null` manda la de la cátedra ([Course.aiPolicy]). Si está
     * puesta, la tarea sólo puede **cerrar** más, nunca abrir más: ver
     * [effectiveRules].
     */
    val aiPolicyOverride: AiPolicy? = null,

    // ---- Rúbrica y pistas ----
    /** Rúbrica de evaluación (criterio + escala). */
    val rubric: Rubric? = null,
    /** Pistas publicadas por el docente (visibles para el alumno). */
    val publishedHints: List<String> = emptyList(),
    /** Sugerencia de la IA para el docente (NO visible para el alumno). */
    val teacherBrief: TeacherBrief? = null,

    // ---- Entrega ----
    /** Entregas máximas por alumno. */
    val maxAttempts: Int = 1,
    /** Si se aceptan entregas fuera de plazo. */
    val allowLate: Boolean = false,
    /** Fin del plazo de entrega tardía (0 = sin plazo extra). */
    val lateUntil: Long = 0L,

    // ---- Estado ----
    /** Publicada: la ven y trabajan los alumnos. */
    val published: Boolean = false,
    val publishedAt: Long = 0L,
    val tags: List<String> = emptyList(),
    val createdBy: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {

    /** ¿La tarea es sumativa? */
    val isSummative: Boolean get() = assessment == AssessmentType.SUMMATIVE

    /** Plazo efectivo (incluye la ventana de entregas tardías). */
    val effectiveDeadline: Long
        get() = if (allowLate && lateUntil > dueAt) lateUntil else dueAt

    /** ¿Venció el plazo? (sin fecha, nunca vence) */
    fun isPastDue(now: Long): Boolean = effectiveDeadline > 0L && now > effectiveDeadline

    /** ¿La ventana de entrega sigue abierta? */
    fun isOpen(now: Long): Boolean = published && !isPastDue(now)

    /** ¿La tarea acepta texto escrito por el alumno? */
    val acceptsText: Boolean get() = submissionType.acceptsText

    /** ¿La tarea acepta archivos adjuntos? */
    val acceptsFiles: Boolean get() = submissionType.acceptsFiles

    /** Materiales de la tarea resueltos contra los materiales de la cátedra. */
    fun resolveMaterials(materials: List<Material>): List<Material> {
        val byId = materials.associateBy { it.id }
        return materialIds.mapNotNull { byId[it] }
    }

    /**
     * Reglas de IA **efectivas** de esta tarea, combinando la política de la
     * cátedra con el override de la tarea.
     *
     * La tarea sólo puede cerrar más, nunca abrir más:
     *  - el uso de IA por el alumno se calcula con AND (si la cátedra lo
     *    prohíbe, la tarea no lo habilita);
     *  - "sólo pistas" se calcula con OR;
     *  - "declarar el uso" se calcula con OR.
     */
    fun effectiveRules(coursePolicy: AiPolicy): AiRules {
        val base = AiRules.of(coursePolicy)
            .narrow(aiPolicyOverride?.let { AiRules.of(it) } ?: AiRules())
        val withUse = if (allowAi) base else base.copy(allowsStudentUse = false)
        // Sin permiso de sugerir, la IA no puede proponer un texto: sólo guía.
        return if (chatCanSuggest) withUse else withUse.copy(hintsOnly = true)
    }

    /** Nivel canónico de la política efectiva (para el badge de la UI). */
    fun effectivePolicy(coursePolicy: AiPolicy): AiPolicy =
        effectiveRules(coursePolicy).level
}
```

### 3.3 `Rubric` — rúbrica simple (criterio + escala)

Objetivo: que la IA pueda **pre-evaluar** con el mismo lenguaje que usa el
docente, y que el docente ajuste. Es deliberadamente simple: no hay celdas ni
matrices; hay **criterios** y una **escala de niveles** con puntaje.

```kotlin
/** Nivel de una escala (ej. "Insuficiente", "Suficiente", "Excelente"). */
@Serializable
data class ScaleLevel(
    val id: String,
    val label: String,
    /** Puntos que vale este nivel (en la escala del criterio). */
    val points: Double,
    val description: String = "",
)

/** Criterio de evaluación. */
@Serializable
data class RubricCriterion(
    val id: String,
    val title: String,
    val description: String = "",
    /** Peso del criterio en puntos (la suma de todos es el máximo de la rúbrica). */
    val points: Int = 0,
    /** Niveles propios del criterio; si está vacío se usa la escala común. */
    val levels: List<ScaleLevel> = emptyList(),
)

/**
 * Rúbrica de la tarea: criterios + escala.
 *
 * La usan dos cosas: la IA para **proponer** un nivel por criterio (con
 * confianza y justificación) y el docente para **fijar** el nivel final.
 */
@Serializable
data class Rubric(
    val id: String,
    val title: String = "Rúbrica",
    val criteria: List<RubricCriterion> = emptyList(),
    /** Escala común a todos los criterios. */
    val scale: List<ScaleLevel> = emptyList(),
    /** Si el alumno puede ver la rúbrica (lo habitual: sí, para saber qué se espera). */
    val published: Boolean = true,
) {
    /** Máximo de puntos de la rúbrica. */
    val maxPoints: Int get() = criteria.sumOf { it.points }

    /** Niveles que aplican a un criterio. */
    fun levelsFor(criterion: RubricCriterion): List<ScaleLevel> =
        if (criterion.levels.isNotEmpty()) criterion.levels else scale

    /** Busca un criterio por id. */
    fun criterion(id: String): RubricCriterion? = criteria.firstOrNull { it.id == id }
}
```

Ejemplo de rúbrica de una tarea de "Análisis de algoritmos":

```json
{
  "id": "r-1",
  "title": "Análisis de algoritmos",
  "scale": [
    { "id": "l1", "label": "Insuficiente", "points": 0,   "description": "No demuestra comprensión." },
    { "id": "l2", "label": "Suficiente",    "points": 50,  "description": "Resuelve, con errores menores." },
    { "id": "l3", "label": "Bueno",         "points": 80,  "description": "Resuelve y justifica." },
    { "id": "l4", "label": "Excelente",     "points": 100, "description": "Resuelve, justifica y analyses casos borde." }
  ],
  "criteria": [
    { "id": "c-correcto", "title": "Correctitud", "points": 60 },
    { "id": "c-claro",    "title": "Claridad",   "points": 20 },
    { "id": "c-fuente",   "title": "Fundamentación", "points": 20 }
  ]
}
```

### 3.4 `TeacherBrief` — la IA sugiere la solución (extremo seguro)

Es el extremo **(a)** de la IA en la evaluación: el docente le pide a la IA
una solución de referencia **a partir del material de la cátedra**. Es seguro
porque ocurre dentro del ámbito del docente y no toca la entrega del alumno.

```kotlin
/**
 * Sugerencia de la IA para el docente: solución de referencia, pistas y
 * errores frecuentes, apoyadas en el material de la cátedra.
 *
 * **Nunca** se muestra al alumno: es material de trabajo del docente (y
 * sirve como referencia para calibrar la corrección). Lo único que puede
 * llegar al alumno son las pistas que el docente publique en
 * [Assignment.publishedHints].
 */
@Serializable
data class TeacherBrief(
    val generatedAt: Long = 0L,
    /** Modelo que la generó (etiqueta legible). */
    val modelLabel: String = "",
    /** Pedido que hizo el docente (para poder regenerarlo). */
    val prompt: String = "",
    /** Solución de referencia en Markdown. Sólo el docente. */
    val solution: String = "",
    /** Pistas graduales (el docente puede publicar algunas). */
    val hints: List<String> = emptyList(),
    /** Errores frecuentes que hay que corregir. */
    val pitfalls: List<String> = emptyList(),
    /** Etiquetas de las fuentes del material en las que se apoyó. */
    val sources: List<String> = emptyList(),
    /** Verificación determinista de las citas de la solución. */
    val citations: CitationStats = CitationStats(),
    /** El docente revisó el brief y lo da por bueno. */
    val approved: Boolean = false,
)
```

**Cómo se genera** (flujo del docente, `:backend`):

1. Se arma un `RagEngine` con la cátedra y se indexa **sólo** el material de la
   tarea (`assignment.materialIds`) — o el material completo si la tarea no
   tiene material propio.
2. Se manda un prompt con el rol de docente: *"Resolvé la consigna, citá las
   fuentes, después listá 3 pistas graduales y 3 errores frecuentes"*.
3. La respuesta se **verifica** con `CitationVerifier.verify(answer, chunks)`:
   el brief guarda `citations` (respaladas / sin respaldo / inexistentes). Si
   hay citas sin respaldo, la UI lo avisa: *"la IA afirmó cosas que el material
   no respalda"*.
4. Se guarda en `assignment.teacherBrief` con `approved = false`. El docente
   edita, aprueba, y opcionalmente copia las pistas a `publishedHints`.

**Bajo `GUIDED`**, el brief es además la fuente de las pistas del chat del
alumno: al abrir el hilo de la tarea se inyectan **sólo** `publishedHints`
(jamas `solution`). El campo `solution` nunca entra en un prompt de alumno.

---

## 4. `AiPolicy` — la política de IA de la cátedra

### 4.1 Los cuatro niveles

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Política de uso de IA de una cátedra (o de una tarea, que puede cerrarla
 * más pero no abrirla más).
 *
 * Los cuatro niveles:
 *  - [DISABLED]: el alumno no puede usar IA para la tarea. No se abre chat de
 *    trabajo, no se pueden vincular hilos y el uso de IA cuenta como
 *    violación de la política.
 *  - [ASSISTED]: el alumno puede usar IA, pero **tiene que declararlo** en
 *    la entrega (qué usó y cómo). El docente ve el chat según la visibilidad
 *    de la inscripción.
 *  - [GUIDED]: la IA **sólo da pistas**: preguntas guía, conceptos y
 *   pistas; no escribe la solución ni un texto entregable. Es el nivel
 *    "tutor", no "solution engine".
 *  - [FREE]: el alumno puede usar IA libremente y no está obligado a
 *    declararlo. El docente igual ve el chat si la visibilidad lo permite.
 */
@Serializable
enum class AiPolicy(val label: String, val description: String) {
    DISABLED(
        "Sin IA",
        "El alumno no puede usar IA para esta tarea: no se abre chat de trabajo " +
            "ni se pueden vincular hilos a la entrega.",
    ),
    ASSISTED(
        "IA asistida",
        "El alumno puede usar IA, pero debe declarar el uso en la entrega.",
    ),
    GUIDED(
        "IA guiada",
        "La IA sólo da pistas y preguntas guía; no resuelve la consigna ni " +
            "escribe un texto que pueda entregarse.",
    ),
    FREE(
        "IA libre",
        "El alumno puede usar IA libremente y no está obligado a declararlo.",
    ),
    ;

    /** ¿El alumno puede usar IA? */
    val allowsStudentUse: Boolean get() = this != DISABLED

    /** ¿La entrega tiene que declarar el uso de IA? */
    val requiresDeclaration: Boolean get() = this == ASSISTED

    /** ¿La IA está limitada a pistas? */
    val hintsOnly: Boolean get() = this == GUIDED

    /** ¿El docente puede usar la IA con el material de la cátedra? (siempre sí) */
    val allowsTeacherUse: Boolean get() = true

    companion object {
        fun fromName(name: String): AiPolicy? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
```

### 4.2 `AiRules`: la forma resuelta (tres banderas independientes)

Un enum no alcanza: "sólo pistas" y "declarar el uso" son ejes distintos
(`GUIDED` + declaración requerida es una combinación válida). Por eso la
composición se hace con una clase de tres banderas.

```kotlin
/**
 * Reglas de IA ya resueltas (sin enumerado): tres banderas independientes.
 *
 * Se calcula combinando la política de la cátedra con la de la tarea con
 * [narrow], que **sólo cierra**: el uso del alumno se combina con AND, "sólo
 * pistas" y "declarar el uso" con OR.
 */
@Serializable
data class AiRules(
    /** ¿El alumno puede usar IA? */
    val allowsStudentUse: Boolean = true,
    /** ¿La IA está limitada a pistas (no propone el texto de la entrega)? */
    val hintsOnly: Boolean = false,
    /** ¿La entrega exige declarar el uso de IA? */
    val requiresDeclaration: Boolean = false,
) {
    /**
     * Nivel canónico, sólo para el badge de la UI.
     *
     * Ojo: es un resumen. La fuente de verdad son las tres banderas; por
     * ejemplo una cátedra `ASSISTED` con una tarea `GUIDED` da
     * `level = GUIDED` pero `requiresDeclaration = true`.
     */
    val level: AiPolicy
        get() = when {
            !allowsStudentUse -> AiPolicy.DISABLED
            hintsOnly -> AiPolicy.GUIDED
            requiresDeclaration -> AiPolicy.ASSISTED
            else -> AiPolicy.FREE
        }

    /** Combina dos juegos de reglas cerrando (nunca abriendo) permisos. */
    fun narrow(other: AiRules): AiRules = AiRules(
        allowsStudentUse = allowsStudentUse && other.allowsStudentUse,
        hintsOnly = hintsOnly || other.hintsOnly,
        requiresDeclaration = requiresDeclaration || other.requiresDeclaration,
    )

    companion object {
        /** Convierte un nivel de política en reglas. */
        fun of(policy: AiPolicy): AiRules = when (policy) {
            AiPolicy.DISABLED -> AiRules(allowsStudentUse = false)
            AiPolicy.ASSISTED -> AiRules(requiresDeclaration = true)
            AiPolicy.GUIDED -> AiRules(hintsOnly = true)
            AiPolicy.FREE -> AiRules()
        }
    }
}
```

### 4.3 Qué hace el sistema en cada nivel

| | `DISABLED` | `ASSISTED` | `GUIDED` | `FREE` |
|---|---|---|---|---|
| Botón "Trabajar con la IA" | oculto | activo | activo | activo |
| Chat de la tarea | no se crea | normal | **sólo pistas** | normal |
| `create_file` desde el hilo de la tarea | bloqueado | permitido | permitido | permitido |
| Vincular un hilo a la entrega | bloqueado | permitido | permitido | permitido |
| Declaración de IA en la entrega | no aplica | **obligatoria** | recomendada | opcional |
| ¿El docente ve el chat? | según `Enrollment.visibility` | según visibilidad | según visibilidad | según visibilidad |
| Uso de IA no declarado / prohibido | `policyViolation` | `declarationMismatch` | `declarationMismatch` | — |

Puntos clave:

- **La política no cambia qué ve el docente del texto.** Eso lo manda
  `Enrollment.visibility` (y que el hilo haya llegado al servidor de la
  cátedra). Ver §7.4.
- **`DISABLED` no borra el chat**: si el alumno ya chatted, el hilo existe y
  el docente lo ve con la visibilidad normal; lo que cambia es que usarlo fue
  una **violación** que queda marcada en las métricas y el docente la ve.
- **`GUIDED` se aplica en el prompt del sistema del hilo** (§6.3), no con un
  filtro de palabras: es la única defensa posible con un modelo abierto.

### 4.4 Dónde vive la política

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Course.kt (agregar el campo)
@Serializable
data class Course(
    // …los campos actuales…
    /**
     * Política de IA de la cátedra.
     *
     * Es el piso de permisos: una tarea puede cerrarla más (ver
     * `Assignment.aiPolicyOverride`) pero no abrirla más.
     * El valor por defecto (`ASSISTED`) es el más habitual en la universidad:
     * se puede usar IA, hay que declararlo.
     */
    val aiPolicy: AiPolicy = AiPolicy.ASSISTED,
)
```

---

## 5. `Submission` — la entrega del alumno

### 5.1 Campos

| Campo | Tipo | Qué es |
|---|---|---|
| `id` | `String` | `"<courseId>\|entrega-<n>"`. |
| `assignmentId` | `String` | Tarea a la que corresponde. |
| `courseId` | `String` | Cátedra (desnormalizado para consultas). |
| `studentId` / `studentName` | `String` | Alumno (el nombre es una foto del momento de entregar). |
| `text` | `String` | **Texto de la entrega** en Markdown. |
| `files` | `List<SubmissionFile>` | **Archivos** adjuntos (subidos por el alumno o generados por la IA). |
| `status` | `SubmissionStatus` | **Borrador / Entregada / Corregida**. |
| `createdAt` / `updatedAt` | `Long` | Auditoría del borrador. |
| `submittedAt` | `Long` | Cuándo se entregó (0 = sigue en borrador). |
| `threadIds` | `List<String>` | **Los chats que el alumno usó para trabajar esta tarea.** Es lo que le da al docente el *proceso*, no sólo el resultado. |
| `aiDeclaration` | `AiDeclaration` | Declaración del alumno sobre el uso de IA. |
| `score` | `Double?` | Nota final (la pone el docente). |
| `feedbackId` | `String` | Feedback asociado (§8). |
| `attempt` | `Int` | Número de intento (1 = primera). |
| `revisionOf` | `String` | Si es una reentrega, id de la entrega anterior. |

### 5.2 Kotlin final

`core/src/commonMain/kotlin/kitoru/domain/Submission.kt`

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/** De dónde sale un archivo adjunto a la entrega. */
@Serializable
enum class SubmissionFileOrigin(val label: String) {
    /** Lo subió el alumno desde su disco. */
    UPLOAD("Subido por el alumno"),

    /** Lo generó la IA con la herramienta `create_file` en un hilo vinculado. */
    AI_ARTIFACT("Generado por la IA"),
    ;

    companion object {
        fun fromName(name: String): SubmissionFileOrigin? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** Archivo adjunto a una entrega. */
@Serializable
data class SubmissionFile(
    /** Nombre visible del archivo. */
    val name: String,
    /**
     * Ruta relativa dentro de
     * `courses/<courseId>/submissions/<submissionId>/`.
     */
    val path: String = "",
    val sizeBytes: Long = 0L,
    val mime: String = "",
    /** ¿Lo subió el alumno o lo generó la IA? */
    val origin: SubmissionFileOrigin = SubmissionFileOrigin.UPLOAD,
    /** Hilo del que salió el archivo (si lo generó la IA). */
    val sourceThreadId: String = "",
    val addedAt: Long = 0L,
)

/** Cómo dice el alumno que usó la IA. */
@Serializable
enum class AiUsage(val label: String) {
    NONE("No usé IA"),
    EXPLORED("Consulté conceptos"),
    HINTS("Pedí pistas"),
    DRAFT("Generé un borrador"),
    REVIEW("Usé la IA para revisar"),
    SOLUTION("La IA resolvió la consigna"),
    ;

    companion object {
        fun fromName(name: String): AiUsage? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Declaración del alumno sobre el uso de IA.
 *
 * La pide el sistema cuando la política lo exige ([AiPolicy.ASSISTED]) y
 * sirve para que el docente compare lo declarado con lo detectado en el hilo.
 */
@Serializable
data class AiDeclaration(
    /** El alumno declara que usó IA. */
    val usedAi: Boolean = false,
    /** Qué hizo con la IA. */
    val usage: AiUsage = AiUsage.NONE,
    /** Modelo que usó (lo que diga el hilo). */
    val modelLabel: String = "",
    /** Lo que el alumno cuenta (opcional). */
    val note: String = "",
    /** Cuándo declaró (0 = no declaró). */
    val declaredAt: Long = 0L,
)

/**
 * Entrega de una tarea.
 *
 * Nace como borrador, se entrega una vez ([status] = SUBMITTED) y termina
 * corregida ([status] = GRADED).
 *
 * Lo que la hace valiosa para el docente es [threadIds]: los chats en los que
 * el alumno trabajó la tarea. Con eso se ve el *cómo*, no sólo el *qué*.
 */
@Serializable
data class Submission(
    val id: String,
    val assignmentId: String,
    val courseId: String,
    val studentId: String,
    val studentName: String = "",

    /** Texto de la entrega (Markdown). */
    val text: String = "",
    /** Archivos adjuntos. */
    val files: List<SubmissionFile> = emptyList(),

    /** Estado de la entrega. */
    val status: SubmissionStatus = SubmissionStatus.DRAFT,

    /** Cuándo se creó el borrador. */
    val createdAt: Long = 0L,
    /** Última modificación. */
    val updatedAt: Long = 0L,
    /** Cuándo se entregó (0 = sigue en borrador). */
    val submittedAt: Long = 0L,

    /**
     * Hilos de chat que el alumno usó para trabajar esta tarea.
     *
     * Es el vínculo más valioso del subsistema: el docente puede recorrer el
     * proceso de razonamiento (preguntas, reintentos, errores) en lugar de
     * mirar sólo el resultado final.
     */
    val threadIds: List<String> = emptyList(),

    /** Declaración sobre el uso de IA. */
    val aiDeclaration: AiDeclaration = AiDeclaration(),

    /** Nota final (la pone el docente al publicar el feedback). */
    val score: Double? = null,
    /** Id del feedback asociado. */
    val feedbackId: String = "",
    /** Número de intento (1 = primera entrega). */
    val attempt: Int = 1,
    /** Si es una reentrega: id de la entrega anterior. */
    val revisionOf: String = "",
) {
    /** ¿Es un borrador editable? */
    val isDraft: Boolean get() = status == SubmissionStatus.DRAFT

    /** ¿Ya fue entregada? */
    val isDelivered: Boolean get() = status != SubmissionStatus.DRAFT

    /** ¿Tiene algo entregable? (texto o archivos) */
    val hasContent: Boolean get() = text.isNotBlank() || files.isNotEmpty()

    /** ¿Fue entregada fuera de plazo? */
    fun isLate(dueAt: Long): Boolean = submittedAt > 0L && dueAt > 0L && submittedAt > dueAt

    /** Cantidad de palabras del texto de la entrega. */
    val wordCount: Int
        get() = text.split(Regex("\\s+")).count { it.isNotBlank() }

    /** Archivos que generó la IA (relevantes para la política). */
    val aiGeneratedFiles: List<SubmissionFile>
        get() = files.filter { it.origin == SubmissionFileOrigin.AI_ARTIFACT }
}
```

---

## 6. La relación tarea ↔ chat

### 6.1 El campo

Se agrega **un** campo a `ChatThread`:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Chat.kt (modificación)
@Serializable
data class ChatThread(
    // …los campos actuales…
    /**
     * Tareas con las que está vinculado este hilo.
     *
     * Es una etiqueta del alumno ("esto lo usé para la tarea X") y es lo que
     * permite al docente reconstruir el proceso de trabajo.
     *
     * - `Set` y no `List` porque la relación es de pertenencia y no tiene
     *   orden.
     * - No se borra: si el hilo se usó para una tarea, queda registrado
     *   (el docente puede verlo según la visibilidad de la inscripción).
     */
    val assignmentIds: Set<String> = emptySet(),
) {
    /** ¿El hilo está vinculado a esta tarea? */
    fun isLinkedTo(assignmentId: String): Boolean = assignmentId in assignmentIds
}
```

Y, del lado de la entrega, `Submission.threadIds` (ya definido).

Los dos campos responden a dos preguntas distintas:

| Campo | Pregunta que responde | Quién lo escribe |
|---|---|---|
| `ChatThread.assignmentIds` | "¿Este hilo fue usado para trabajar esta tarea?" | el alumno, al abrir o al marcar |
| `Submission.threadIds` | "¿Qué chats sustentan esta entrega?" | el alumno, al entregar (por defecto: los vinculados) |

El docente ve la **unión** de ambos (filtrada por visibilidad).

### 6.2 Los dos flujos de vinculación

**Flujo A — el alumno abre el chat desde la tarea (principal).**

1. En la pantalla de la tarea, botón **"Trabajar con la IA"**.
2. La app crea el hilo:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/AssignmentThreads.kt (nuevo)
fun newAssignmentThread(
    userId: String,
    userName: String,
    course: Course,
    assignment: Assignment,
): ChatThread {
    val rules = assignment.effectiveRules(course.aiPolicy)
    return ChatThread(
        id = newId(course.id, "hilo"),
        courseId = course.id,
        authorId = userId,
        authorName = userName,
        title = "Tarea: ${assignment.title}",
        createdAt = nowMillis(),
        updatedAt = nowMillis(),
        effectiveModel = course.modelPolicy,
        tags = listOf("tarea:${assignment.id}"),
        assignmentIds = setOf(assignment.id),
    )
}
```

3. El `systemPrompt` del hilo se arma con `AssignmentPrompts.studentSystemPrompt(course, assignment, rules)`.
4. Los mensajes del hilo se guardan como cualquier otro (`ChatMessage`), con
   `sources`, `citation`, `toolCalls`.

**Flujo B — el alumno marca un hilo existente con una tarea.**

Desde la pantalla del chat: **"Vincular a una tarea…"** → elige una tarea
publicada → `thread.copy(assignmentIds = thread.assignmentIds + assignment.id)`.

También al revés: desde la pantalla de la tarea, **"Vincular un chat existente"**
→ elige un hilo propio → agrega la tarea al `assignmentIds` del hilo y el hilo
a `threadIds` de la entrega en curso.

**Retro-alimentación al entregar.** Al entregar, la app ofrece marcar
automáticamente los hilos ya vinculados:

```kotlin
// En el flujo de entrega (capa de aplicación)
val hilosVinculados = threads.filter { it.isLinkedTo(assignment.id) }.map { it.id }
val threadIds = (hilosVinculados + submission.threadIds).distinct()
```

Así, aunque el alumnus no tique el casillero, el docente igual ve el proceso.

### 6.3 El prompt del sistema del hilo (por política)

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/AssignmentPrompts.kt (nuevo)
object AssignmentPrompts {

    /**
     * Prompt del sistema para el chat de trabajo de una tarea.
     *
     * Es lo que hace efectiva a la política: con `GUIDED` el modelo recibe
     * una instrucción explícita de no resolver la consigna.
     */
    fun studentSystemPrompt(
        course: Course,
        assignment: Assignment,
        rules: AiRules,
    ): String = buildString {
        appendLine("Sos el tutor de la cátedra ${course.shortName}.")
        appendLine("Tarea: ${assignment.title}")
        appendLine()
        appendLine("ENUNCIADO:")
        appendLine(assignment.statement)
        if (assignment.publishedHints.isNotEmpty()) {
            appendLine()
            appendLine("PISTAS PUBLICADAS POR EL DOCENTE (usarlas si hacen falta):")
            assignment.publishedHints.forEach { appendLine("- $it") }
        }
        appendLine()
        when {
            !rules.allowsStudentUse ->
                appendLine("El docente deshabilitó el uso de IA en esta tarea. " +
                    "Respondé sólo con ayuda general de la materia, sin tocar la consigna.")
            rules.hintsOnly ->
                appendLine("REGLAS DE ESTA TAREA: sólo das PISTAS. " +
                    "No escribas la solución, ni un fragmento de la respuesta, ni código " +
                    "listo para entregar. Podés: preguntar qué se entiende, dar un " +
                    "concepto clave, señalar qué mirar en el material y dar una pista " +
                    "por vez (esperá que el alumno avance antes de la siguiente).")
            rules.requiresDeclaration ->
                appendLine("REGLAS DE ESTA TAREA: podés ayudar con la consigna, pero el " +
                    "alumno tiene que declarar el uso de IA en la entrega. " +
                    "Al final de cada respuesta indicá brevemente qué le sugeriste.")
            else ->
                appendLine("Podés ayudar con la consigna. Recordale al alumno que declare " +
                    "el uso de IA en la entrega si usa tu respuesta.")
        }
        appendLine()
        appendLine("Respondé citando el material de la cátedra cuando uses información de él.")
    }
}
```

> Nota de estilo: los mensajes al modelo van en español rioplatense, como el
> resto del proyecto (ver `PromptToolParser`, `CreateFileTool`).

### 6.4 Archivos creados por `create_file` y adjuntos de la entrega

Sí: **los archivos que el modelo creó con la herramienta `create_file` se
pueden adjuntar a la entrega**. El camino completo:

1. La herramienta `create_file` escribe en
   `courses/<courseId>/outputs/<threadId>/<nombre>` (el `ToolContext.outputsDir`
   del hilo) y devuelve un `Artifact`.
2. Al persistir el mensaje del hilo, los artefactos se guardan. **Hoy
   `ToolCallStats` no los guarda**: hay que agregar un tipo de dominio (el
   dominio no puede importar `rag.agent.Artifact`, porque `:backend` depende de
   `:core`, no al revés):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Chat.kt (modificación)
/** Archivo producido por una herramienta del chat (artefacto). */
@Serializable
data class ChatArtifact(
    val name: String,
    /** Ruta del archivo en disco (relativa a la carpeta de la cátedra). */
    val path: String,
    val sizeBytes: Long = 0L,
    val mime: String = "",
)

@Serializable
data class ToolCallStats(
    val name: String,
    val args: String = "",
    val ok: Boolean? = null,
    val result: String = "",
    val durationMs: Long = 0L,
    /** Archivos que produjo la herramienta (adjuntos del mensaje). */
    val artifacts: List<ChatArtifact> = emptyList(),
)
```

   El mapeo `rag.agent.Artifact → kitoru.domain.ChatArtifact` se hace en la
   capa que persiste el mensaje (`:ui` o el servicio de chat), no en el
   dominio.

3. En la pantalla de entrega, el alumno ve la lista de **artefactos de los
   hilos vinculados** con un botón "Adjuntar a la entrega".
4. Al adjuntar, el archivo **se copia** (no se referencia) al directorio de la
   entrega y se registra:

```kotlin
// Capa de aplicación (suspend fun attachArtifact(...))
suspend fun attachArtifact(
    fs: FileSystem,
    courseId: String,
    submissionId: String,
    threadId: String,
    artifact: ChatArtifact,
): SubmissionFile {
    val destDir = KitoruPaths.coursesDir.resolve(courseId)
        .resolve("submissions").resolve(submissionId)
    fs.createDirectories(destDir)
    val dest = destDir.resolve(artifact.name)
    fs.copy(FilePath(artifact.path), dest)
    return SubmissionFile(
        name = artifact.name,
        path = dest.relativeTo(KitoruPaths.coursesDir.resolve(courseId)),
        sizeBytes = artifact.sizeBytes,
        mime = artifact.mime,
        origin = SubmissionFileOrigin.AI_ARTIFACT,
        sourceThreadId = threadId,
        addedAt = nowMillis(),
    )
}
```

5. El docente ve el archivo con la etiqueta **"Generado por la IA"**, y el
   archivo cuenta como uso de IA detectado (ver §8.3).

Reglas:

- Los archivos **subidos por el alumno** se copian igual al directorio de la
  entrega (`origin = UPLOAD`).
- Límite: 5 MB por archivo (el mismo tope de `CreateFileTool`) y las
  extensiones ya permitidas por la tool.
- Un archivo generado por la IA en una tarea `DISABLED` es una
  `policyViolation` (ver §8.3).
- El student's "usar el archivo" no borra el original del `outputs/` del hilo:
  el hilo conserva su historia.

### 6.5 Entrega: qué se valida

```kotlin
// Reglas de validación al entregar (fail-fast, en español)
fun validateForDelivery(assignment: Assignment, rules: AiRules, submission: Submission): List<String> {
    val errors = mutableListOf<String>()
    if (!submission.hasContent) errors += "La entrega está vacía: escribí una respuesta o adjuntá un archivo."
    if (assignment.acceptsText && submission.text.isBlank() && submission.files.isEmpty())
        errors += "Esta tarea pide un texto."
    if (assignment.acceptsFiles && submission.files.isEmpty())
        errors += "Esta tarea pide al menos un archivo."
    if (rules.requiresDeclaration && submission.aiDeclaration.declaredAt <= 0L)
        errors += "Esta cátedra exige declarar el uso de IA en la entrega."
    if (!rules.allowsStudentUse && (submission.aiDeclaration.usedAi || submission.threadIds.isNotEmpty()))
        errors += "Esta tarea no admite IA: no se puede entregar con chats vinculados."
    return errors
}
```

La declaración se registra con `declaredAt > 0L`: si el alumno no tilda
"usé IA" y no escribe nada, no hay declaración y bajo `ASSISTED` la entrega no
pasa la validación.

---

## 7. IA en la evaluación: los dos extremos

### 7.1 Extremo (a) — el docente pide una solución o pista

**Dónde:** en el editor de la tarea, botón "Pedirle a la IA una solución".

**Flujo:**

```
Docente                       Backend (:backend)                Dominio (:core)
   │ "Generar brief"
   ├────────────────────────────────────────────────►  RAG sobre assignment.materialIds
   │                                                 prompt "resolvé + 3 pistas + 3 errores"
   │                                                 CitationVerifier.verify(...)
   │◄──────────────────────────────────────────────── TeacherBrief(solution, hints, pitfalls, citations)
   │ Edita, aprueba
   ├─► assignment.teacherBrief = brief.copy(approved = true)
   ├─► assignment.publishedHints = brief.hints.take(2)   (opcional)
```

**Por qué es el extremo seguro:** el material es de la cátedra, la IA no ve
entregas de alumnos y el resultado no se publica solo (requiere
aprobación del docente). La solución de referencia sirve además para
**calibrar** la corrección: el docente la compara con su propio criterio.

### 7.2 Extremo (b) — el alumno usa la IA

**¿Se permite?** Depende de `AiPolicy` (cátedra ∩ tarea). Los cuatro niveles
están en §4.3.

**¿Se marca?** Sí, de tres formas complementarias:

1. **En la entrega**: la declaración (`Submission.aiDeclaration`) — obligatoria
   bajo `ASSISTED`.
2. **En el hilo**: `ChatThread.assignmentIds` dice que ese hilo pertenece al
   trabajo de la tarea, aunque el alumno no lo declare.
3. **En los archivos**: `SubmissionFile.origin = AI_ARTIFACT`.

**¿El docente ve el chat aunque la entrega no lo declare?** **Sí**, y es
intencional. La declaración mide la honestidad del alumno; el vínculo mide la
trazabilidad del trabajo. No son la misma cosa. Un alumno que no declara haber
usado IA tiene un `declarationMismatch` en las métricas, pero el docente igual
puede leer el hilo (si la visibilidad lo permite), porque:

- el hilo está vinculado a la tarea (`assignmentIds`), y
- la entrega puede no listarlo (el alumno no tildó el casillero) — por eso el
  docente ve la **unión** `thread.assignmentIds ∪ submission.threadIds`.

### 7.3 El hilo de trabajo como parte de la entrega

Para el docente, un hilo de trabajo aporta:

| Señal | De dónde sale | Para qué |
|---|---|---|
| Citas verificadas / sin respaldo | `ChatMessage.citation` (`CitationStats`) | ¿el modeloInventó? |
| Fuentes usadas | `ChatMessage.sources` | ¿el alumno leyó el material? |
| Herramientas usadas | `ChatMessage.toolCalls` | ¿generó archivos? |
| Latencia y tokens | `ChatMessage.latencyMs`, `tokensIn/Out` | esfuerzo / costo |
| Nº de mensajes y reintentos | conteo sobre `ChatMessage` | proceso de trabajo |

Todo esto ya existe en el dominio (`Chat.kt`): **no hace falta ningún tipo
nuevo** para el vínculo tarea ↔ chat, salvo `assignmentIds`.

### 7.4 Qué ve el docente: la matriz de visibilidad

La política de IA **no** cambia la matriz de visibilidad que ya define
`Enrollment.visibility` y `ChatVisibility`:

| Modo del alumno | Visibilidad de la inscripción | ¿El docente ve el texto del chat? | ¿Ve las métricas? |
|---|---|---|---|
| IA alojada (REMOTE) | `FULL` | Sí | Sí |
| IA alojada (REMOTE) | `METRICS_ONLY` | No | Sí |
| IA alojada (REMOTE) | `NONE` | No | No |
| IA local (LOCAL) | `FULL` | **No**: el hilo nunca sale del dispositivo | Sólo si el alumno lo comparte (Kitoru-Server) |

Reglas derivadas:

- Un hilo de un modelo **local** que el alumno no envió **no existe para el
  docente**: no hay texto, no hay métricas. El único rastro es la entrega.
- Cuando el alumno vincula un hilo a una tarea, la UI le avisa explícitamente:
  *"Al vincular este hilo al trabajo de «Tarea X», el docente va a poder leerlo
  según la visibilidad de tu inscripción (Completo / Solo métricas / Oculto).
  Si usaste IA local, el hilo no se envía salvo que lo compartas vos."*
- Al entregar bajo `ASSISTED`, la casilla de declaración dice: *"Declaro que
  usé IA. Entiendo que el docente puede leer los chats vinculados a esta
  entrega según la visibilidad de mi inscripción."*

---

## 8. `AssignmentFeedback` — la revisión del docente con métricas de IA

### 8.1 Campos

| Campo | Tipo | Qué es |
|---|---|---|
| `id` | `String` | `"<submissionId>\|fb-<n>"`. |
| `assignmentId` / `submissionId` / `courseId` | `String` | Contexto. |
| `studentId` / `studentName` | `String` | Alumno. |
| `authorId` / `authorName` | `String` | Docente que corrige. |
| `text` | `String` | Comentario del docente (Markdown). |
| `score` | `Double?` | **Nota final** (la del docente, no la de la IA). |
| `criteriaScores` | `List<CriteriaScore>` | **Nivel y puntaje por criterio** (decisión del docente). |
| `aiMetrics` | `AiEvaluationMetrics?` | **Métricas de la IA** sobre la entrega y sus hilos. |
| `aiProposal` | `List<AiCriterionProposal>` | **Propuesta de la IA** por criterio (sugerencia, con confianza y justificación). |
| `status` | `FeedbackStatus` | `DRAFT` (la IA propone, el docente ajusta) o `PUBLISHED` (el alumno lo ve). |
| `createdAt` / `updatedAt` / `publishedAt` | `Long` | Auditoría. |

### 8.2 Kotlin final

`core/src/commonMain/kotlin/kitoru/domain/AssignmentFeedback.kt`

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/** Estado de la revisión. */
@Serializable
enum class FeedbackStatus(val label: String) {
    /** En preparation: la IA ya propuso, el docente todavía no lo publicó. */
    DRAFT("Borrador"),

    /** Publicado: el alumno lo ve. */
    PUBLISHED("Publicado"),
    ;

    companion object {
        fun fromName(name: String): FeedbackStatus? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Fundamentación de un texto respecto de un corpus.
 *
 * Sale de una función determinista (`rag.engine.GroundingChecker`), nunca del
 * modelo: la misma entrada da siempre el mismo resultado.
 */
@Serializable
data class GroundingStats(
    /** Frases analizadas. */
    val claims: Int = 0,
    /** Frases con respaldo en el material. */
    val grounded: Int = 0,
    /** Frases sin respaldo. */
    val ungrounded: Int = 0,
) {
    /** Proporción de frases fundamentadas (0..1). */
    val groundedRatio: Double
        get() = if (claims == 0) 0.0 else grounded.toDouble() / claims
}

/**
 * Una frase del alumno que no se encuentra respaldada en el material.
 *
 * [excerpt] va vacío cuando la visibilidad de la inscripción no permite al
 * docente ver el texto del alumno: en ese caso sólo se muestran los números.
 */
@Serializable
data class UngroundedClaim(
    /** Índice de la frase dentro del texto de la entrega. */
    val index: Int,
    /** Fragmento del alumno (vacío si no se puede mostrar). */
    val excerpt: String = "",
    /** Por qué se considera no fundamentada. */
    val reason: String = "",
    /** Etiqueta de la fuente más cercana, si la hubo. */
    val closestSource: String = "",
)

/**
 * Métricas calculadas sobre una entrega y los hilos que la sustentan.
 *
 * Todo lo que hay acá es **determinista** (citas verificadas, fundamentación,
 * conteos). El modelo sólo aparece en [modelLabel] y en el resumen en prosa
 * ([summary]), que el docente edita antes de publicar.
 */
@Serializable
data class AiEvaluationMetrics(
    /** Fundamentación del texto de la entrega contra el material. */
    val grounding: GroundingStats = GroundingStats(),
    /** Frases del alumno sin respaldo (puede estar vacío si no se puede mostrar el texto). */
    val ungroundedClaims: List<UngroundedClaim> = emptyList(),
    /** Citas de la entrega del alumno verificadas contra el material. */
    val deliveryCitations: CitationStats = CitationStats(),
    /** Citas de las respuestas de la IA en los hilos vinculados. */
    val threadCitations: CitationStats = CitationStats(),
    /** Respuestas del modelo sin respaldo en el hilo. */
    val threadUnsupportedAnswers: Int = 0,
    /** Hilos analizados. */
    val threadsAnalyzed: Int = 0,
    /** Mensajes del alumno en esos hilos. */
    val studentMessages: Int = 0,
    /** Respuestas del asistente en esos hilos. */
    val assistantReplies: Int = 0,
    /** Archivos de la entrega generados por la IA. */
    val aiGeneratedFiles: Int = 0,
    /** Se detectó uso de IA en los hilos o en los archivos. */
    val detectedAiUse: Boolean = false,
    /** La declaración del alumno no coincide con lo detectado. */
    val declarationMismatch: Boolean = false,
    /** El alumno usó IA en una tarea donde la política lo prohíbe. */
    val policyViolation: Boolean = false,
    /** Política que se aplicó al medir. */
    val policy: AiPolicy = AiPolicy.FREE,
    /** El texto del alumno era visible para el docente al medir. */
    val textVisible: Boolean = false,
    /** Cuándo se calcularon las métricas. */
    val generatedAt: Long = 0L,
    /** Modelo que ayudó al cálculo (sólo para la parte de resumen/propuesta). */
    val modelLabel: String = "",
    /** Resumen en prosa (lo escribe la IA, lo edita el docente). */
    val summary: String = "",
)

/**
 * Propuesta de la IA para un criterio de la rúbrica.
 *
 * **No es una nota**: es una sugerencia con confianza y justificación, que el
 * docente puede aceptar, cambiar o ignorar.
 */
@Serializable
data class AiCriterionProposal(
    val criterionId: String,
    /** Nivel propuesto (id de [ScaleLevel]). */
    val levelId: String = "",
    /** Confianza de la IA (0..1). */
    val confidence: Double = 0.0,
    /** Por qué propone ese nivel. */
    val justification: String = "",
    /** Evidencia: fragmentos de la entrega que sostienen la propuesta. */
    val evidence: List<String> = emptyList(),
)

/** Puntaje del docente por criterio. */
@Serializable
data class CriteriaScore(
    val criterionId: String,
    /** Nivel elegido (id de [ScaleLevel]); vacío si el docente no usó niveles. */
    val levelId: String = "",
    /** Puntos dados (si el docente no usa la escala). */
    val points: Double? = null,
    val comment: String = "",
)

/**
 * Revisión de una entrega.
 *
 * Nace como borrador con la pre-evaluación de la IA ([aiMetrics] +
 * [aiProposal]) y la publica el docente ([status] = PUBLISHED), que es quien
 * fija la nota real ([score] y [criteriaScores]).
 */
@Serializable
data class AssignmentFeedback(
    val id: String,
    val assignmentId: String,
    val submissionId: String,
    val courseId: String,
    val studentId: String,
    val studentName: String = "",

    /** Docente que corrige. */
    val authorId: String = "",
    val authorName: String = "",

    /** Comentario del docente (Markdown). */
    val text: String = "",
    /** Nota final que asigna el docente. */
    val score: Double? = null,
    /** Puntaje por criterio (decisión del docente). */
    val criteriaScores: List<CriteriaScore> = emptyList(),

    /** Métricas de la IA sobre la entrega y sus hilos. */
    val aiMetrics: AiEvaluationMetrics? = null,
    /** Propuesta de la IA por criterio de la rúbrica. */
    val aiProposal: List<AiCriterionProposal> = emptyList(),

    /** Estado de la revisión. */
    val status: FeedbackStatus = FeedbackStatus.DRAFT,

    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Cuándo se publicó (0 = sigue en borrador). */
    val publishedAt: Long = 0L,
) {
    /** ¿El alumno ya lo puede ver? */
    val isPublished: Boolean get() = status == FeedbackStatus.PUBLISHED

    /** Puntaje del docente para un criterio. */
    fun scoreFor(criterionId: String): Double? =
        criteriaScores.firstOrNull { it.criterionId == criterionId }?.points

    /** Nivel que eligió el docente para un criterio. */
    fun levelFor(criterionId: String): String =
        criteriaScores.firstOrNull { it.criterionId == criterionId }?.levelId.orEmpty()

    /** Propuesta de la IA para un criterio (si la hay). */
    fun proposalFor(criterionId: String): AiCriterionProposal? =
        aiProposal.firstOrNull { it.criterionId == criterionId }
}
```

### 8.3 De dónde salen las métricas

Todas deterministas, salvo el resumen en prosa y las propuestas de nivel.

| Métrica | Cómo se calcula | Dónde |
|---|---|---|
| `grounding` / `ungroundedClaims` | Se parte el texto de la entrega en frases y cada frase se contrasta contra los fragmentos del material de la tarea con las **tres reglas** del verificador (literal / sin detalles inventados / paráfrasis). | Nueva función pura `rag.engine.GroundingChecker.ground(text, chunks)` en `:backend`. |
| `deliveryCitations` | `CitationVerifier.verify(textoEntrega, chunks)` → `CitationStats`. | `rag.engine.CitationVerifier` (ya existe). |
| `threadCitations` | Suma de `ChatMessage.citation` de los mensajes del asistente en los hilos vinculados. | `kitoru.domain` (ya existe). |
| `threadUnsupportedAnswers` | Cantidad de mensajes del asistente con `citation.unsupported > 0`. | `kitoru.domain`. |
| `studentMessages` / `assistantReplies` / `threadsAnalyzed` | Conteo sobre los mensajes de los hilos. | `kitoru.domain`. |
| `aiGeneratedFiles` | `submission.aiGeneratedFiles.size`. | `kitoru.domain`. |
| `detectedAiUse` | `threadIds.isNotEmpty() || aiGeneratedFiles.isNotEmpty()`. | `kitoru.domain`. |
| `declarationMismatch` | `detectedAiUse != aiDeclaration.usedAi` (y `aiDeclaration.usedAi` sin ningún hilo → igual hay mismatch, porque no puede haber usado IA sin dejar rastro). | `kitoru.domain`. |
| `policyViolation` | `!rules.allowsStudentUse && detectedAiUse`. | `kitoru.domain`. |
| `textVisible` | `Enrollment.visibility.showsText` de ese alumno. | `kitoru.domain`. |
| `aiProposal` | Prompt con la rúbrica + la entrega → un nivel por criterio + confianza + justificación + evidencia. **Es una sugerencia**: el docente la edita. | `:backend` (nuevo `rag.eval.RubricProposal`). |
| `summary` | Prompt con la entrega + las métricas → un párrafo. **Lo edita el docente.** | `:backend`. |

**Privacidad en las métricas.** Si `Enrollment.visibility` es `METRICS_ONLY` o
`NONE`, el texto del alumno no se le muestra al docente: en ese caso
`ungroundedClaims` se guarda con `excerpt = ""` y sólo se muestran los
**números** (`grounding.ungrounded`). El `textVisible` del snapshot deja
constancia de por qué.

### 8.4 El prompt de pre-evaluación

```kotlin
// :backend/src/commonMain/kotlin/rag/eval/RubricProposal.kt (nuevo)
object RubricProposal {

    /**
     * Prompt de pre-evaluación: la IA propone un nivel por criterio.
     *
     * Reglas duras del prompt: no inventar (sólo usar el material y la
     * entrega), no cambiar los niveles de la escala, y dar la justificación
     * con la evidencia (el fragmento de la entrega que sostiene el nivel).
     */
    fun prompt(rubric: Rubric, assignment: Assignment, submission: Submission): String = buildString {
        appendLine("Eres un corrector automático. Evaluá la entrega con la rúbrica.")
        appendLine("Usá SOLO la rúbrica y el material de la cátedra. No inventes.")
        appendLine("Para cada criterio devolvé: nivel, confianza (0..1), justificación y evidencia.")
        appendLine()
        appendLine("RÚBRICA:")
        rubric.criteria.forEach { c ->
            appendLine("- ${c.id}: ${c.title} (${c.points} pts) — ${c.description}")
            rubric.levelsFor(c).forEach { l ->
                appendLine("    · ${l.id}: ${l.label} (${l.points} pts) — ${l.description}")
            }
        }
        appendLine()
        appendLine("ENUNCIADO:")
        appendLine(assignment.statement)
        appendLine()
        appendLine("ENTREGA DEL ALUMNO:")
        appendLine(submission.text)
    }
}
```

La salida se parsea a `List<AiCriterionProposal>` con un parser tolerante
(el mismo estilo que `ToolCallSanitizer`: reintento con feedback al modelo si
el JSON viene roto). Si el parseo falla, **no se pierde la entrega**: el
feedback queda en DRAFT con `aiProposal = emptyList()` y `aiMetrics` intacto.

---

## 9. Flujos completos

### 9.1 El docente publica una tarea

```
1. Nueva tarea (borrador): published = false
2. Carga enunciado (Markdown), material de referencia (materialIds),
   fecha de entrega, puntos, tipo de entrega, rúbrica.
3. Opcional: "Pedirle a la IA una solución" → assignment.teacherBrief
   (con citas verificadas). El docente edita, aprueba y publica
   las pistas que quiera → assignment.publishedHints.
4. Define la política de la tarea: allowAi, chatCanSuggest,
   aiPolicyOverride (o deja que mande la de la cátedra).
5. "Publicar" → published = true, publishedAt = now.
   → aparece en la lista de tareas del alumno.
```

### 9.2 El alumno entrega

```
1. Ve la tarea (enunciado, material, fecha, puntos, rúbrica si está publicada,
   política de IA, pistas publicadas).
2. "Trabajar con la IA" → se crea el hilo vinculado (assignmentIds) con el
   prompt del sistema según la política. El chat guarda mensajes, citas y
   herramientas.
3. "Entregar" → se crea/recupera un Submission en DRAFT:
   - escribe el texto (si la tarea lo acepta),
   - adjunta archivos del disco o artefactos de la IA (create_file),
   - ve los hilos vinculados y puede incluir/excluir los que sustentan la
     entrega,
   - declara el uso de IA si la política lo exige.
4. "Entregar" → validaciones (tipo de entrega, declaración, política):
   status = SUBMITTED, submittedAt = now.
   Si submittedAt > dueAt → es tardía (y si allowLate = false, queda
   marcada como tardía para el docente; la cátedra decide si la acepta).
5. El alumno ya no edita; puede ver la entrega y el feedback cuando se
   publique. Con maxAttempts > 1 puede crear otra entrega (attempt + 1).
```

### 9.3 El docente corrige

```
1. "Entregas" de la tarea → lista con estado (borrador no se ve),
   tardía, declaración, uso de IA detectado.
2. Abrir una entrega:
   - el texto y los archivos del alumno,
   - los hilos vinculados (si la visibilidad lo permite), con las citas
     resaltadas (respalda / sin respaldo),
   - el panel de métricas de la IA (§8.3),
   - la pre-evaluación por criterio (propuesta con confianza y evidencia).
3. El docente ajusta: texto del comentario, niveles por criterio, nota.
4. "Publicar feedback" → status = PUBLISHED, publishedAt = now;
   submission.status = GRADED, submission.score = score.
   → el alumno ve el feedback con las métricas (menos los excerpts que la
   visibilidad no permite).
```

---

## 10. Persistencia

Siguiendo el patrón de `JsonListStore` (un JSON por tipo en `config/`):

```
config/
  courses.json
  enrollments.json
  materials.json
  threads.json
  messages.json
  assignments.json      ← nuevo
  submissions.json      ← nuevo
  feedbacks.json        ← nuevo
courses/<courseId>/
  materials/…                (material de la cátedra)
  outputs/<threadId>/…       (artefactos de create_file)
  submissions/<submissionId>/…   (archivos de la entrega)
```

Repositorios (mismo estilo que `Repositories.kt` / `FileRepositories.kt`):

```kotlin
interface AssignmentRepository {
    suspend fun listForCourse(courseId: String): List<Assignment>
    suspend fun listPublished(courseId: String): List<Assignment>
    suspend fun get(id: String): Assignment?
    suspend fun save(assignment: Assignment)
    suspend fun delete(id: String)
}

interface SubmissionRepository {
    suspend fun listForAssignment(assignmentId: String): List<Submission>
    suspend fun listForStudent(courseId: String, studentId: String): List<Submission>
    suspend fun get(id: String): Submission?
    suspend fun save(submission: Submission)
    suspend fun delete(id: String)
}

interface FeedbackRepository {
    suspend fun listForAssignment(assignmentId: String): List<AssignmentFeedback>
    suspend fun listForSubmission(submissionId: String): List<AssignmentFeedback>
    suspend fun get(id: String): AssignmentFeedback?
    suspend fun save(feedback: AssignmentFeedback)
    suspend fun delete(id: String)
}
```

Implementaciones `FileAssignmentRepository`, `FileSubmissionRepository` y
`FileFeedbackRepository` (`assignments.json`, `submissions.json`,
`feedbacks.json`).

Nota de la documentación de `FileMessageRepository`: los ids llevan el prefijo
del id de cátedra (`"curso|hilo"`) para poder filtrar por cátedra. Con el
mismo criterio, los ids de tarea, entrega y feedback llevan el prefijo de la
cátedra (`"<courseId>|tarea-1"`, `"<courseId>|entrega-3"`, `"<submissionId>|fb-1"`).

---

## 11. Cambios en el dominio existente

| Archivo | Cambio | Motivo |
|---|---|---|
| `domain/Course.kt` | + `aiPolicy: AiPolicy = AiPolicy.ASSISTED` | Política de IA de la cátedra. |
| `domain/Chat.kt` | + `ChatThread.assignmentIds: Set<String>` | Vínculo hilo ↔ tarea. |
| `domain/Chat.kt` | + `ChatArtifact` y `ToolCallStats.artifacts` | Poder adjuntar a la entrega los archivos de `create_file`. |
| `domain/Assignment.kt` (nuevo) | `Assignment`, `SubmissionType`, `AssessmentType`, `AiPolicy`, `AiRules`, `Rubric`, `RubricCriterion`, `ScaleLevel`, `TeacherBrief`, `CriteriaScore` | — |
| `domain/Submission.kt` (nuevo) | `Submission`, `SubmissionFile`, `SubmissionFileOrigin`, `AiUsage`, `AiDeclaration`, `SubmissionStatus` | — |
| `domain/AssignmentFeedback.kt` (nuevo) | `AssignmentFeedback`, `AiEvaluationMetrics`, `AiCriterionProposal`, `GroundingStats`, `UngroundedClaim`, `FeedbackStatus` | — |
| `domain/AssignmentPrompts.kt` (nuevo) | Prompts del sistema del hilo de tarea y de pre-evaluación | Funciones puras de texto. |
| `domain/Repositories.kt` | + 3 interfaces | Persistencia. |
| `domain/FileRepositories.kt` | + 3 implementaciones `File*` | Persistencia. |
| `backend/.../rag/engine/GroundingChecker.kt` (nuevo) | `ground(text, chunks): GroundingReport` | Métrica de fundamentación, determinista. |
| `backend/.../rag/eval/RubricProposal.kt` (nuevo) | Prompt + parsing de la propuesta por criterio | Pre-evaluación con IA. |
| `backend/.../rag/engine/CitationVerifier.kt` | `checkSnippet` pasa de `private` a `internal`/público | Lo reutiliza `GroundingChecker`. |

---

## 12. Casos borde

1. **Tarea sin material propio.** El RAG de la tarea usa el material completo
   de la cátedra; el `TeacherBrief` lo aclara con `sources`.
2. **Tarea `DISABLED` con entrega ya hecha.** Si el alumno entregó antes de
   que el docente cerrara la política, la entrega **no se invalida**: queda
   marcada con `policyViolation` en las métricas y el docente decide.
3. **Alumno que entrega tarde.** `isLate(dueAt)`; con `allowLate = false` la
   entrega se marca tardía pero no se bloquea (el docente ve la marca).
4. **Reentrega.** Con `maxAttempts > 1`, la entrega nueva tiene `attempt = n+1`
   y `revisionOf` apunta a la anterior. La nota que cuenta es la de la última
   entrega; las anteriores quedan como historial.
5. **Entrega sin texto.** Si la tarea pide archivos, `text` puede quedar vacío:
   `hasContent` mira texto **o** archivos.
6. **Alumno que entrega y después sigue chateando.** El hilo sigue
   vinculado; el docente ve que la conversación siguió después de la entrega
   (`submittedAt` vs `updatedAt` del hilo).
7. **Tarea borrada.** Las entregas y los feedbacks quedan (histórico), con la
   tarea en estado "eliminada"; la entrega conserva el `assignmentId` para
   que no se rompan los links.
8. **Dos tareas, un mismo hilo.** Un hilo puede estar vinculado a varias tareas
   (`assignmentIds` es un `Set`): es válido y el docente lo ve como
   "este hilo se usó en Tarea 1 y Tarea 2".
9. **Rúbrica con puntos que no cierran con la tarea.** La UI avisa: la suma de
   `criteria.points` debería igualar `assignment.points`.
10. **Cifras del modelo local.** Si el hilo es local y no se compartió, el
    docente no tiene ni texto ni métricas: `threadsAnalyzed = 0` y
    `detectedAiUse = false` salvo que haya archivos generados por la IA
    adjuntos a la entrega.
11. **Un mismo archivo generado por la IA en dos entregas.** Cada entrega
    tiene su copia en su directorio; el `sourceThreadId` apunta al hilo de
    origen.
12. **`ChatVisibility.NONE`.** El docente no ve texto ni métricas del alumno:
    el feedback se puede publicar igual (es del docente), pero sin excerpts y
    sin el detalle del hilo. `AiEvaluationMetrics.textVisible = false`.

---

## 13. Qué habría que testear

En `:core/src/desktopTest` (JUnit 5, como el resto):

- `AiRulesTest` — `narrow` en las 16 combinaciones de política de cátedra ×
  override de tarea; `level` canónico; `of(policy)`.
- `AssignmentTest` — `effectiveRules` con `allowAi = false` y con
  `chatCanSuggest = false`; `isPastDue` / `effectiveDeadline` con
  `allowLate`.
- `SubmissionTest` — `isLate`, `hasContent`, `aiGeneratedFiles`.
- `GroundingCheckerTest` (en `:backend`) — frase inventada → `ungrounded`;
  frase textual del material → `grounded`; paráfrasis → `grounded`;
  determinismo (dos corridas ⇒ mismo resultado).
- `FeedbackTest` — `scoreFor`, `levelFor`, `proposalFor`; un feedback en
  DRAFT no aparece en la vista del alumno.

En `:ui` (o donde se arme la pantalla): un test de que la visibilidad
`METRICS_ONLY` **no** renderiza los excerpts de `ungroundedClaims`.
