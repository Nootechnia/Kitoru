# 06 · Panel de analítica de la cátedra — vista docente

> Documento de diseño. Subtarea: **diseño del panel de analítica** (estadísticas, comportamiento y calidad de las respuestas).
> Depende de: `docs/design/CONTEXTO.md`, `core/.../domain/Analytics.kt`, `core/.../domain/AnalyticsAnalyzer.kt`,
> `core/.../domain/Chat.kt`, `core/.../domain/Roles.kt`, `core/.../domain/Course.kt`,
> `backend/.../rag/engine/CitationVerifier.kt`.
> Continúa (no repite) a: `01-navegacion.md` (§f, el esqueleto del panel), `08-alumnos.md` (visibilidad),
> `07-chats.md` (chats del docente y auditoría), `09-calidad.md` (detalle de calidad y panel de respuestas),
> `10-informes.md` (exportación e IR).

---

## 0. Resumen en una línea

El panel de analítica es **la respuesta a tres preguntas del docente**: *¿lo están
usando?*, *¿el modelo responde bien?* y *¿el material alcanza?* — y cada respuesta
tiene que ser un **número determinista** (salen de lo que ya está persistido, sin
llamar al modelo) y una **acción** (revisar un material, hablar con un alumno,
ajustar el prompt).

| # | Decisión | Por qué |
|---|---|---|
| D1 | **Todo número es determinista y auditable.** Ninguna métrica llama al modelo ni a la red: se calcula sobre `ChatMessage` ya persistidos (`citation`, `sources`, `latencyMs`, `tokens`). | Regla 3 del proyecto. El docente puede pedir "¿de dónde sale este 87 %?" y la respuesta es una fórmula, no una opinión del modelo. |
| D2 | **Un número sí, un texto no.** Los agregados y las tasas se calculan siempre; lo único que la visibilidad censura es el **texto**. | El `AnalyticsAnalyzer` ya excluye a los `NONE`; faltaba el filtro de texto (`METRICS_ONLY` → sin nombres ni sin fragmentos en las tablas). §7. |
| D3 | **Los alumnos `METRICS_ONLY` aparecen con un alias estable** (`A-7C3F`), no con su nombre. | El nombre es dato del padrón (que el docente ya ve en otra pantalla); la analítica y sus exportaciones no lo vuelven a escupir. Un archivo que se manda al departamento no debería llevar nombres de alumnos que sólo compartieron métricas. |
| D4 | **Seis KPIs de cabecera, no diez.** Participación, volumen, calidad por cita, calidad por respuesta, comportamiento y el material que más falla. | Los seis contestan las tres preguntas; el resto vive en las tablas y en los gráficos. §2. |
| D5 | **Gráficos dibujados con `Canvas` de Compose, sin librería de charts.** La matemática de layout (escalas, ticks, cortes) es **función pura y testeable**; el `Canvas` sólo pinta. | Control total del *look*, integración con Material 3, cero dependencias, portabilidad desktop/Android real, y la interacción que hace falta (filtrar al cruzar) no existe en las librerías genéricas. §3.1. |
| D6 | **La calidad de una respuesta es "respaldo en el material", medido con el verificador de citas.** El panel nunca dice "la IA acertó": dice "el material no lo dice". | El verificador es determinista y explicable; "calidad de la IA" sería una falsa precisión. §6. |
| D7 | **"El alumno preguntó X" = un mensaje de rol `user` en un hilo de la cátedra.** "Respuesta inútil" = la respuesta que vino sin respaldo, sin fuente, vacía, o directamente no vino. **"Volvió a preguntar" = una segunda pregunta del mismo alumno con ≥ 80 % de similitud.** | Detectar el "no encontré la respuesta" sin un juez de lenguaje: el verificador ya dice si la respuesta se apoyaba en el material. §5. |
| D8 | **El detector de repetición se ejecuta donde está el texto; el número viaja, el texto no.** Si no hay texto (alumno local que sólo mandó métricas), el valor es **"no determinable"**, nunca 0. | Mentir con un 0 es peor que no tener el dato. La celda del CSV queda vacía (regla de `10-informes.md`: sin dato ≠ cero). |
| D9 | **La tabla por alumno es el centro de la pantalla**, no un extra: es el desglose de todos los números de arriba. | El docente Think: primero "cómo va la cátedra", después "quién". |
| D10 | **La exportación sale por el seam `FileExporter`/`SaveTarget` de `10-informes.md`**, en CSV y JSON, con el mismo formato (separador `;`, BOM, CRLF, `snake_case` sin acentos). | Un solo formato de exportación en todo el proyecto. §8. |

---

## 1. Qué se mide y por qué

### 1.1 Las tres preguntas del docente

| Pregunta | Se responde con | Familia |
|---|---|---|
| **¿Lo están usando?** | inscripción, actividad, cuestionamiento, días activos, cierre de hilos | A. Volumen y participación |
| **¿El modelo responde bien?** | tasa de citas respaldadas, respuestas sin respaldo, respuestas sin citar | C. Calidad |
| **¿El material alcanza?** | citas por material, respuestas sin respaldo por material, dudas que se repiten | B. Comportamiento + C. Calidad |

Cada familia tiene sus KPIs de cabecera (§2), sus gráficos (§3) y su tabla (§4, §6).

### 1.2 Definiciones operativas (las palabras que usa el panel)

Estas definiciones son la base: si no, cada gráfico contaría cosas distintas.

| Término | Definición exacta | Dónde se calcula |
|---|---|---|
| **Pregunta** | Un `ChatMessage` con `role == "user"` cuyo hilo pertenece a un alumno con `visibility.showsMetrics`. Los mensajes con `role == "teacher"` (los que escribe el docente) y `role == "tool"` **no** cuentan. | `BehaviorAnalyzer` |
| **Respuesta** | El **primer** `ChatMessage` con `role == "assistant"` del mismo hilo, con `createdAt >=` el de la pregunta. Si no hay ninguno, la pregunta **quedó sin respuesta**. | `BehaviorAnalyzer` |
| **Turno** | El par (pregunta, respuesta). | `BehaviorAnalyzer` |
| **Respuesta inútil** | La respuesta de un turno que incumple cualquiera de: (a) no vino; (b) llegó vacía o con menos de 20 caracteres; (c) tiene ≥ 1 cita **sin respaldo** o **sin fuente**; (d) recuperó material (`sources` no vacío) pero no produjo **ninguna** cita verificable. | `BehaviorAnalyzer` → `AnswerIssue` |
| **Citas de la respuesta** | `ChatMessage.citation` (los contadores `verified`/`unsupported`/`unknown` y, desde `09-calidad.md`, el detalle `checks`). | ya persistido |
| **Fuentes de la respuesta** | `ChatMessage.sources` (etiquetas de los fragmentos recuperados) y `ChatMessage.sourceIds` (a qué material pertenece cada uno). | ya persistido |
| **Sesión** | Racha de preguntas del mismo alumno con menos de **30 minutos** de separación. Corta la inactivity. | `BehaviorAnalyzer` |
| **Hilo abandonado** | Hilo cuyo **último** mensaje es del alumno (se fue sin cerrar) **y** la última respuesta fue inútil. | `BehaviorAnalyzer` |
| **Pregunta reiterada** | Una pregunta `q2` del mismo alumno para la que existe una pregunta anterior `q1` con **similitud ≥ 0,80** (§5.3) y las cinco guardas de §5.3.3. | `BehaviorAnalyzer` |
| **Duda no resuelta** | Una pregunta reiterada cuya respuesta anterior **fue inútil**. Es el cruce entre comportamiento y calidad: el material no respondió y el alumno insiste. | `BehaviorAnalyzer` |

> **Nota sobre "el modelo no encontró la respuesta".** No hay un detector de "no lo sé":
> no se puede saber sin un juez de lenguaje. Lo que sí se puede saber, y es
> determinista, es **si la respuesta se apoyó en el material** (`citation`) y **si el
> alumno volvió a preguntar lo mismo** (§5). La cadena
> *"el verificador dice que la respuesta no se apoyaba"* + *"el alumno volvió a
> preguntar"* es la señal de que **el material no cubría la duda** — y es del
> **material**, no del alumno.

### 1.3 Inventario de métricas

Todas se calculan sobre datos **ya persistidos**. La columna "origen" dice de qué
campo salen; no hay ninguna que dependa del modelo.

#### A. Volumen y participación

| Métrica | Fórmula exacta | Qué significa | Bueno | Alerta | Origen |
|---|---|---|---|---|---|
| `students` | `count(enrollments)` con `role == ALUMNO` **y** `visibility.showsMetrics` | Alumnos que aportan datos a la analítica | — | — | `Enrollment` (ya existe) |
| `enrolledStudents` **(nuevo)** | `count(enrollments)` con `role == ALUMNO` (cualquier visibilidad) | Alumnos inscritos | — | — | `Enrollment` |
| `activeStudents` | `count(perStudent)` con `messages > 0` | Alumnos que interactuaron | — | — | (ya existe) |
| `participationRate` **(nuevo)** | `activeStudents / max(1, students)` | Proporción de los **visibles** que usaron la herramienta | ≥ 0,70 | < 0,35 | derivado |
| `hiddenStudents` **(nuevo)** | `enrolledStudents − students` | Alumnos que no comparten nada (NO aparecen con nombre, ni en ninguna tabla) | 0 | > 0 | derivado |
| `totalMessages` | `Σ messages` de los alumnos visibles | Preguntas totales | — | — | (ya existe) |
| `totalReplies` | `Σ replies` | Respuestas totales | — | — | (ya existe) |
| `totalThreads` | `Σ threads` | Conversaciones iniciadas | — | — | (ya existe) |
| `avgMessagesPerStudent` | `round(totalMessages / max(1, students))` | Profundidad de uso promedio | 10 – 60 | < 3 (nadie pregunta nada) | (ya existe) |
| `messagesPerActiveDay` **(nuevo)** | `totalMessages / max(1, Σ activeDays)` | Preguntas por día activo: mide cuánto profundiza el que ya entró | ≥ 3 | < 1,5 | derivado |
| `courseActiveDays` **(nuevo)** | `count(distinct dayStart(createdAt))` sobre los mensajes visibles | Días con actividad en la cátedra | — | — | derivado |
| `activeDays` (por alumno) | `count(distinct dayStart(createdAt))` de sus mensajes | Días en los que chateó | ≥ 3 en la primera semana | 1 | (ya existe) |
| `lastActivity` (por alumno) | `max(createdAt)` de sus mensajes | Última interacción | < 7 días | > 21 días | (ya existe) |

> **Por qué el denominador es `students` y no `enrolledStudents`.** Un alumno con
> `NONE` no es un alumno que no usa la herramienta: es un alumno que decidió no
> compartir. Medir la participación contra el padrón completo castigaría la
> privacidad. Por eso `students` es el denominador y `enrolledStudents` /
> `hiddenStudents` se muestran **aparte**, con la nota "3 de 45 alumnos no
> comparten sus datos" (misma regla que `10-informes.md` §4.6).

#### B. Comportamiento

| Métrica | Fórmula exacta | Qué significa | Bueno | Alerta | Origen |
|---|---|---|---|---|---|
| `questions` | `messages` del alumno | Preguntas hechas | — | — | (ya existe como `messages`) |
| `sessions` **(nuevo)** | Nº de rachas con hueco ≥ 30 min | Cuántas veces vino a trabajar | — | — | derivado |
| `questionsPerSession` **(nuevo)** | `questions / max(1, sessions)` | Cuánto pregunta por sesión | 3 – 10 | < 1,5 (preguntas sueltas) | derivado |
| `avgQuestionTokens` **(nuevo)** | `tokensIn / max(1, messages)` | Tamaño de la pregunta | — | > 400 (preguntas largas) | derivado |
| `unsatisfiedQuestions` **(nuevo)** | Nº de preguntas cuya respuesta fue inútil (§1.2) | Preguntas que no quedaron contestadas | ≤ 15 % | > 30 % | derivado |
| `unsatisfiedRate` **(nuevo)** | `unsatisfiedQuestions / max(1, messages)` | Proporción de preguntas mal contestadas | ≤ 0,15 | > 0,30 | derivado |
| `unansweredQuestions` **(nuevo)** | Preguntas sin mensaje del asistente después | Preguntas que el modelo no llegó a contestar | ≤ 5 % | > 12 % | derivado |
| `repeatedQuestions` **(nuevo, nullable)** | Preguntas que no son la primera de su grupo de similitud | Cuánto repite el alumno | ≤ 5 % | > 15 % | derivado (§5) |
| `repeatRate` **(nuevo, nullable)** | `repeatedQuestions / max(1, messages)` | Proporción de repeticiones | ≤ 0,05 | > 0,15 | derivado |
| `repeatGroups` **(nuevo)** | Nº de grupos con ≥ 2 preguntas | Cuántas dudas distintas se repitieron | ≤ 5 % de las preguntas | > 15 % | derivado |
| `repeatedAfterUnhelpful` **(nuevo)** | Repetidas cuya respuesta anterior fue inútil | **Dudas no resueltas**: el material no respondió y el alumno volvió | ≤ 2 % | > 8 % | derivado |
| `abandonedThreads` **(nuevo)** | Hilos cuyo último mensaje es del alumno y cuya última respuesta fue inútil | Hilos que se quedaron a medias | ≤ 10 % de los hilos | > 25 % | derivado |
| `topicTags` **(nuevo, opcional)** | `distinct(ChatThread.tags)` | Etiquetas que puso el propio alumno ("duda de parcial") | — | — | `ChatThread.tags` |

> **`repeatedQuestions` es `Int?`.** `null` significa **"no determinable"**: el
> detector necesita el texto de la pregunta y el texto no está (alumno con IA
> local que mandó sólo métricas). No es 0. En la UI sale "—" y en el CSV una celda
> vacía.

#### C. Calidad (respuestas)

| Métrica | Fórmula exacta | Qué significa | Bueno | Alerta | Origen |
|---|---|---|---|---|---|
| `citationSupportRate` | `verified / max(1, verified + unsupported + unknown)` | Citas que el material respalda | ≥ 0,85 | < 0,60 | (ya existe) |
| `ungroundedAnswerRate` | `answersWithUnsupportedCitations / max(1, replies)` | Respuestas que afirmaron algo que el material no dice | ≤ 0,10 | > 0,25 | (ya existe) |
| `repliesWithoutCitations` **(nuevo, de `09-calidad.md`)** | Respuestas del asistente con `citation == null` **o** `citation.total == 0` | El modelo respondió **de memoria** | ≤ 0,20 | > 0,40 | derivado |
| `uncitedAnswerRate` **(nuevo)** | `repliesWithoutCitations / max(1, replies)` | Proporción de respuestas sin cita | ≤ 0,20 | > 0,40 | derivado |
| `groundedAnswerRate` **(nuevo, de `09-calidad.md`)** | `answersGrounded / max(1, totalReplies)` | Respuestas **enteramente** apoyadas en el material | ≥ 0,70 | < 0,40 | derivado |
| `citationReasons` **(nuevo, de `09-calidad.md`)** | `Map<reasonKind, count>` | Por qué falló cada cita | — | — | derivado |
| `p50LatencyMs`, `p95LatencyMs` **(nuevo)** | Percentiles sobre `latencyMs` de las respuestas | Latencia típica y cola | p95 ≤ 8 s | p95 > 20 s | derivado |
| `tokensPerAnswer` **(nuevo)** | `totalTokens / max(1, totalReplies)` | Coste por respuesta | — | > 1.500 tokens | derivado |
| `toolFailureRate` **(nuevo)** | `toolFailures / max(1, toolCalls)` | Herramientas que fallaron | ≤ 0,05 | > 0,15 | derivado |

#### D. Por material (el cruce — §6)

| Métrica | Fórmula exacta | Qué significa | Bueno | Alerta |
|---|---|---|---|---|
| `material.answersUsing` | Respuestas con ≥ 1 cita que apunta a ese material | Exposición del material | — | — |
| `material.citations` | Citas (apariciones) que apuntan a ese material | Volumen de citas | — | — |
| `material.supportRate` | `citationsSupported / max(1, citations)` | Citas que el material sostiene | ≥ 0,85 | < 0,60 |
| `material.unsupportedAnswerRate` | `answersWithFailures / max(1, answersUsing)` | Respuestas que fallaron **usando** este material | ≤ 0,15 | ≥ 0,25 **con ≥ 5 respuestas** |
| `material.unresolvedDoubts` **(nuevo)** | Nº de **dudas no resueltas** (§1.2) cuya respuesta anterior usó este material | Preguntas que el material no pudo responder | 0 | ≥ 2 |

> **Regla de significancia** (misma que `09-calidad.md` §3.3):
> `MIN_MUESTRAS_MATERIAL = 5`. Con menos de 5 respuestas que citan el material, la
> tasa se muestra en gris con la etiqueta *"pocos datos, no concluyente"*. Un PDF
> con un fallo aislado no es un material problema.

### 1.4 Los números que ya existen: dos correcciones y una extensión

`AnalyticsAnalyzer` ya está implementado y es correcto en lo esencial. Al usarlo
para este panel aparecen tres cosas que hay que tocar:

**(a) `topSources` hoy cuenta fuentes de alumnos que no puede ver.**
El cálculo de `sourceCounts` recorre **todos** los hilos, sin filtrar por
visibilidad:

```kotlin
// AnalyticsAnalyzer.kt (actual) — BUG
val sourceCounts = mutableMapOf<String, Int>()
for (thread in threads) {                        // ← sin filtro de visibilidad
    for (message in messagesByThread[thread.id].orEmpty()) {
        if (!message.isAssistant) continue
        for (source in message.sources) { ... }
```

Con un alumno `NONE` (o `METRICS_ONLY` si se decide no compartir el detalle), su
material más consultado aparece en el top-N del docente. **Hay que corregirlo**:
recorrer sólo los hilos cuyo autor está en `visible`.

**(b) `inactiveStudents` es una lista de nombres.**
```kotlin
inactiveStudents = students.filter { it.messages == 0 }.map { it.displayName }
```
Un nombre de alumno en una lista de la analítica es exactamente lo que D3 quiere
evitar. Se cambia a **ids** y la etiqueta la resuelve la capa de visibilidad:

```kotlin
/** Alumnos sin actividad (ids: la etiqueta la decide la política de visibilidad). */
val inactiveStudentIds: List<String> = emptyList(),
```

**(c) `DayActivity` no alcanza para el heatmap ni para la línea de actividad.**
`DayActivity(day, messages, replies)` no dice **cuántos alumnos** hubo ese día, y
no hay actividad **por alumno y por día**. Se extiende:

```kotlin
@Serializable
data class DayActivity(
    val day: Long,
    val messages: Int,
    val replies: Int,
    /** Alumnos distintos que preguntaron ese día (nuevo). */
    val activeStudents: Int = 0,
)
```
y se agrega a `StudentAnalytics`:
```kotlin
/** Actividad por día (sólo los días con actividad). */
val daily: List<DayActivity> = emptyList(),
```

> **Día UTC.** `AnalyticsAnalyzer.dayStart` calcula la medianoche **UTC**
> (el `KDoc` dice "medianoche local": hay que corregir el comentario). Se mantiene
> UTC a propósito — es determinista y no depende de la zona horaria de la máquina
> — y la UI rotula el eje con la fecha UTC. Documentado como límite conocido.

### 1.5 Qué falta en el dominio (cambios propuestos)

Todos son **aditivos** (valor por defecto) → los `messages.json` / `analytics.json`
viejos se leen sin excepción (`ignoreUnknownKeys = true`).

**`:core` — `Analytics.kt`**

```kotlin
@Serializable
data class StudentAnalytics(
    // … campos existentes …
    /** Actividad por día (sólo los días con actividad). */
    val daily: List<DayActivity> = emptyList(),
    /** Preguntas cuya respuesta fue inútil. */
    val unsatisfiedQuestions: Int = 0,
    /** Preguntas que el modelo no llegó a contestar. */
    val unansweredQuestions: Int = 0,
    /** Preguntas repetidas. null = no determinable (no hay texto). */
    val repeatedQuestions: Int? = null,
    /** Grupos de preguntas repetidas. */
    val repeatGroups: Int? = null,
    /** Repetidas que vinieron después de una respuesta inútil. */
    val repeatedAfterUnhelpful: Int? = null,
    /** Hilos que el alumno dejó a medias. */
    val abandonedThreads: Int = 0,
    /** Rachas de actividad (separadas por 30 min o más). */
    val sessions: Int = 0,
    /** Latencia mediana y percentil 95. */
    val p50LatencyMs: Long = 0L,
    val p95LatencyMs: Long = 0L,
) {
    /** Proporción de preguntas mal contestadas (0..1). */
    val unsatisfiedRate: Double
        get() = if (messages == 0) 0.0 else unsatisfiedQuestions.toDouble() / messages
    /** Proporción de preguntas repetidas (0..1). null = no determinable. */
    val repeatRate: Double?
        get() = if (messages == 0 || repeatedQuestions == null) null
                else repeatedQuestions.toDouble() / messages
    /** Proporción de herramientas fallidas (0..1). */
    val toolFailureRate: Double
        get() = if (toolCalls == 0) 0.0 else toolFailures.toDouble() / toolCalls
}

@Serializable
data class CourseAnalytics(
    // … campos existentes …
    /** Alumnos inscritos (cualquier visibilidad). */
    val enrolledStudents: Int = 0,
    /** Alumnos que no comparten datos (sólo el número, nunca nombres). */
    val hiddenStudents: Int = 0,
    /** Alumnos sin actividad (ids; la etiqueta la decide la visibilidad). */
    val inactiveStudentIds: List<String> = emptyList(),
    /** Días con actividad en la cátedra. */
    val courseActiveDays: Int = 0,
    /** Preguntas repetidas en la cátedra (null = no determinable). */
    val repeatedQuestions: Int? = null,
    /** Dudas no resueltas (repetidas tras respuesta inútil). */
    val unresolvedDoubts: Int? = null,
) {
    /** Proporción de alumnos visibles que usaron la herramienta (0..1). */
    val participationRate: Double
        get() = if (students == 0) 0.0 else activeStudents.toDouble() / students
    /** Preguntas por día activo. */
    val messagesPerActiveDay: Double
        get() {
            val days = perStudent.sumOf { it.activeDays }
            return if (days == 0) 0.0 else totalMessages.toDouble() / days
        }
}
```

**`:core` — `Behavior.kt` (nuevo)** — el detalle del comportamiento, con la
separación texto/número que exige D2:

```kotlin
package kitoru.domain

/** Por qué la respuesta a una pregunta no sirvió. */
enum class AnswerIssue(val label: String, val isUnhelpful: Boolean) {
    NINGUNO("La respuesta fue útil", false),
    SIN_RESPUESTA("El modelo no respondió", true),
    RESPUESTA_VACIA("La respuesta llegó vacía", true),
    CITA_SIN_RESPALDO("Afirmó cosas que el material no dice", true),
    CITA_SIN_FUENTE("Citó una fuente que no existe", true),
    SIN_CITAS("Recuperó material pero no respaldó nada", true),
    ;
}

/**
 * Una pregunta que el alumno repitió.
 *
 * `originalText` / `repeatText` vienen **vacíos** si la inscripción no muestra
 * texto: el detector necesita el texto para agrupar, pero el texto no tiene
 * por qué viajar ni quedar guardado.
 */
@Serializable
data class RepeatedQuestion(
    val originalMessageId: String,
    val originalThreadId: String,
    val repeatMessageId: String,
    val repeatThreadId: String,
    /** Similitud entre las dos preguntas (0..1). */
    val similarity: Double,
    /** Motivo por el que la respuesta anterior no sirvió. */
    val previousIssue: AnswerIssue,
    /** Materiales que se usaron en la respuesta anterior (ids). */
    val previousMaterialIds: List<String> = emptyList(),
    /** Texto de la pregunta original (vacío si el texto está oculto). */
    val originalText: String = "",
    val repeatText: String = "",
    /** true = el texto fue ocultado por la visibilidad de la inscripción. */
    val textRedacted: Boolean = false,
) {
    /** ¿Es una duda que el material no respondió? */
    val isUnresolved: Boolean get() = previousIssue.isUnhelpful
}

/** Comportamiento de un alumno (números + detalle con texto opcional). */
@Serializable
data class StudentBehavior(
    val userId: String,
    val questions: Int = 0,
    val sessions: Int = 0,
    val unsatisfiedQuestions: Int = 0,
    val unansweredQuestions: Int = 0,
    val repeatedQuestions: Int? = null,
    val repeatGroups: Int? = null,
    val repeatedAfterUnhelpful: Int? = null,
    val abandonedThreads: Int = 0,
    /** Los grupos de repetición con su detalle (texto si se puede ver). */
    val groups: List<RepeatedQuestion> = emptyList(),
)

/** El resumen de comportamiento de la cátedra. */
@Serializable
data class BehaviorOverview(
    val questions: Int = 0,
    val sessions: Int = 0,
    val unsatisfiedQuestions: Int = 0,
    val unansweredQuestions: Int = 0,
    val repeatedQuestions: Int? = null,
    val repeatGroups: Int? = null,
    val repeatedAfterUnhelpful: Int? = null,
    val unresolvedDoubts: Int? = null,
    val abandonedThreads: Int = 0,
    val perStudent: List<StudentBehavior> = emptyList(),
    /** Los grupos de repetición (con texto sólo si se puede ver). */
    val groups: List<RepeatedQuestion> = emptyList(),
    /** Dudas no resueltas por material. */
    val unresolvedByMaterial: List<MaterialGap> = emptyList(),
    /** false = el texto no estaba disponible: los números de repetición no se calcularon. */
    val textAvailable: Boolean = true,
)

/** Cuánto material quedó sin poder responder dudas. */
@Serializable
data class MaterialGap(
    val materialId: String,
    val title: String,
    /** Respuestas que usaron el material y no se respaldaron. */
    val unsupportedAnswers: Int = 0,
    /** Respuestas que usaron el material. */
    val answersUsing: Int = 0,
    /** Dudas reiteradas sin resolver atribuidas a este material. */
    val unresolvedDoubts: Int = 0,
)
```

**`:core` — `AnalyticsThresholds.kt` (nuevo)** — los umbrales en **un solo lugar**,
versionados, para que la UI pueda mostrar "según el umbral de la cátedra" y para
que en el futuro sean configurables:

```kotlin
package kitoru.domain

/**
 * Umbrales de la analítica.
 *
 * Son **heurísticas iniciales**, no verdades: sirven para marcar "esto hay que
 * mirarlo". Un valor con pocas muestras no dispara nada (ver
 * `MIN_MUESTRAS_*`). Todo el panel usa estas constantes; ningún número está
 * hardcodeado en la UI.
 */
object AnalyticsThresholds {
    /** Participación de los alumnos visibles. */
    const val PARTICIPATION_GOOD = 0.70
    const val PARTICIPATION_ALERT = 0.35

    /** Tasa de citas respaldadas. */
    const val SUPPORT_GOOD = 0.85
    const val SUPPORT_ALERT = 0.60

    /** Respuestas con alguna cita sin respaldo. */
    const val UNGROUNDED_GOOD = 0.10
    const val UNGROUNDED_ALERT = 0.25

    /** Respuestas sin ninguna cita. */
    const val UNCITED_GOOD = 0.20
    const val UNCITED_ALERT = 0.40

    /** Preguntas repetidas. */
    const val REPEAT_GOOD = 0.05
    const val REPEAT_ALERT = 0.15

    /** Preguntas con respuesta inútil. */
    const val UNSATISFIED_GOOD = 0.15
    const val UNSATISFIED_ALERT = 0.30

    /** Hilos abandonados. */
    const val ABANDONED_GOOD = 0.10
    const val ABANDONED_ALERT = 0.25

    /** Latencia p95 (ms). */
    const val LATENCY_P95_GOOD = 8_000L
    const val LATENCY_P95_ALERT = 20_000L

    /** Mínimo de respuestas para concluir sobre un material. */
    const val MIN_MUESTRAS_MATERIAL = 5

    /** Mínimo de respuestas de la cátedra para que un umbral sea significativo. */
    const val MIN_MUESTRAS_CATEDRA = 20

    /** Mínimo de preguntas de un alumno para que un porcentaje sea significativo. */
    const val MIN_MUESTRAS_ALUMNO = 10
}
```

**`:core` — `AnalyticsAlias.kt` (nuevo)** — el alias estable (D3):

```kotlin
package kitoru.domain

/**
 * Alias estable para un alumno cuya inscripción NO muestra texto.
 *
 * No es una barrera de seguridad (el docente que gestiona el padrón ya ve los
 * nombres): es una **regla de presentación** para que la analítica, sus
 * capturas y sus exportaciones no vuelvan a escupir nombres de alumnos que
 * sólo compartieron métricas.
 *
 * Derivado de un hash no criptográfico (FNV-1a 64) de `courseId|userId`, así
 * es estable entre sesiones, entre máquinas y entre exportaciones.
 */
object AnalyticsAlias {
    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L

    fun forUser(courseId: String, userId: String): String {
        var hash = FNV_OFFSET
        val text = "$courseId|$userId"
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash *= FNV_PRIME
        }
        val hex = java.lang.Long.toHexString(hash).uppercase().takeLast(6)
        return "A-$hex"
    }
}
```

> **Por qué FNV-1a y no SHA-256.** `commonMain` no tiene SHA-256 en la biblioteca
> estándar (ni `java.security.MessageDigest` ni `crypto`), y agregar una
> implementación propia de SHA-256 en `commonMain` son ~150 líneas para un uso que
> **no es criptográfico**: el alias no protege un secreto, sólo evita repetir un
> nombre en otro contexto. Si en el futuro el alias se usa como token de acceso,
> ahí sí hay que usar SHA-256 (o `expect/actual` con `MessageDigest` en
> `jvmCommonMain`).

---

## 2. KPIs de cabecera

### 2.1 Los seis

```
┌────────────────────┬────────────────────┬────────────────────┐
│ 👥 Alumnos activos │ 💬 Preguntas       │ ✅ Citas respaldadas│
│ 18 de 21           │ 1.284              │ 87,3 %             │
│ 85,7 % de los que  │ 53 por alumno      │ 1.096 de 1.255     │
│ comparten métricas │ activo             │ citas              │
├────────────────────┼────────────────────┼────────────────────┤
│ ⚠️ Sin respaldo    │ 🔁 Repetidas       │ 📄 Material que    │
│ 9,1 %              │ 37                 │ más falla          │
│ 112 de 1.231       │ 2,9 % de las       │ Tema 3 — GPUs.pdf  │
│ respuestas         │ preguntas          │ 39 % sin respaldo  │
└────────────────────┴────────────────────┴────────────────────┘
   24 inscritos · 3 no comparten sus datos
```

| # | KPI | Valor | Subtítulo | Por qué este número y no otro |
|---|---|---|---|---|
| 1 | **Alumnos activos** | `activeStudents / students` | "18 de 21 · 85,7 % de los que comparten métricas" + al pie "24 inscritos · 3 no comparten sus datos" | Es la primera pregunta de cualquier docente en la semana 2: *¿lo están usando?*. El denominador es la población **visible** (D2, §1.3): el número de inscritos y el de ocultos van aparte, para que el dato sea completo sin que la privacidadcie castigue. |
| 2 | **Preguntas** | `totalMessages` | "1.284 · 53 por alumno activo" | Volumen. Es el contexto de todos los porcentajes: un 9 % de respuestas sin respaldo sobre 40 respuestas no dice nada; sobre 1.231, sí. |
| 3 | **Citas respaldadas** | `citationSupportRate` | "87,3 % · 1.096 de 1.255 citas" | La métrica de calidad **más defendible del proyecto**: la produce el verificador determinista, se puede audiciar cita por cita y no depende del modelo. Es el número que el docente puede mostrar al departamento sin discutir. |
| 4 | **Respuestas sin respaldo** | `ungroundedAnswerRate` | "9,1 % · 112 de 1.231 respuestas" | La versión *por respuesta* del #3, que es la que entiende el docente ("1 de cada 11 respuestas afirmó algo que el material no dice") y la que dispara el trabajo concreto. **Las dos van juntas**: la tasa por cita dice "el texto no cierra", la tasa por respuesta dice "el alumno se lleva una respuesta mala". |
| 5 | **Preguntas reiteradas** | `repeatedQuestions` | "37 · 2,9 % de las preguntas" | Es el único indicador de **comportamiento** de la cabecera, y el que conecta con la calidad: si el material no responde, el alumno vuelve a preguntar. Es también el que el docente puede actuar ("faltó la guía de instalación"). |
| 6 | **Material que más falla** | `material` con mayor `unsupportedAnswerRate` (≥ 5 respuestas) | "Tema 3 — GPUs.pdf · 39 % sin respaldo" | Convierte un porcentaje en **una tarea**. Es el KPI que hace que el panel valga: no dice "la calidad es 87 %", dice "**arreglá este PDF**". |

### 2.2 Qué pasa si no hay espacio

En ventanas angostas (Android) la fila de KPIs pasa a un `LazyVerticalGrid` de
**2 columnas** y quedan 6 tarjetas apiladas en 3 filas. Si aun así no entra,
el orden de sacrificio es: **#6 → #2 → #4**. Nunca se sacrifican #3 (calidad por
cita) ni #5 (comportamiento), que son los dos que justifican el panel.

### 2.3 Copy exacto y estados vacíos

| Situación | Texto |
|---|---|
| Cátedra sin actividad | "Todavía nadie chatted en esta cátedra." + botón "Ver los alumnos sin actividad". |
| `analyticsEnabled == false` | `EmptyState` con candado: *"La analítica está desactivada para esta cátedra."* + botón "Activarla" (🔒 sólo `TITULAR`). (Mismo criterio que `01-navegacion.md` §f.) |
| Sin citas todavía | "No hay respuestas verificadas: los alumnos todavía no preguntaron sobre el material." |
| Pocos datos | "Con menos de 20 respuestas las tasas no son concluyentes." (chip gris junto al número) |
| Repeticiones no determinables | "— (sin texto compartido)" |

Los números se muestran con el **formato de lectura en español** ("1.284",
"87,3 %"), pero **el CSV y el JSON van con punto decimal y sin separador de
miles** (regla de `10-informes.md` §1.2).

---

## 3. Gráficos

### 3.1 Canvas de Compose vs. librería de charts — decisión

| Criterio | Librería de charts (Vico, KoalaPlot,…) | **Canvas de Compose** |
|---|---|---|
| Dependencias | Sumar un artefacto transitivo a `:ui` (que se reparte entre desktop y Android) | Cero |
| Portabilidad real | La mayoría son Android-first o "KMP en desarrollo"; el *look* cambia entre plataformas | Es el **mismo código** en las dos |
| Interacción a medida | El filtrado cruzado (click en un día → filtra la tabla; click en un material → filtra el panel de calidad) es un caso particular que estas librerías no modelan | Se implementa como un `onSelect` más |
| Tema Material 3 | Hay que pelearse con sus defaults (colores, tipografía, padding) | `MaterialTheme.colorScheme` directo |
| Accesibilidad | Variable; muchas dibujan sin `contentDescription` | Se escribe el `contentDescription` y la tabla gemela a mano |
| Testeabilidad | La geometría es interna y no testeable | La geometría es **nuestra** y es una función pura testeable sin UI |
| Volumen de datos | Prefieren series grandes | Nuestro caso es ≤ 500 filas: el `Canvas` va sobrado |
| Costo | ~0 líneas escribidas, ~horas de ajuste y de chasing de API | ~450 líneas nuestras (ejes, ticks, tooltip, hit-test) |

**Decisión: `Canvas` de Compose puro**, con la regla que hace que sea sostenible:

> **La matemática es función pura y testeable; el `Canvas` sólo pinta.**
> Escalas, cortes de eje, buckets de color y *hit-testing* viven en
> `ChartScales.kt`, sin un solo `import` de Compose, y se prueban en
> `commonTest`. Si mañana se quiere meter una librería, se reemplaza la capa de
> pintado y los tests siguen valiendo.

Extra: **toda gráfica tiene su tabla gemela** (el mismo dato en texto). Eso sirve
para el lector de pantalla, para el `copy` al portapapeles y para el docente que
quiere el número, no la figura.

### 3.2 Kit mínimo de gráficos (`kitoru.ui.teacher.analytics.charts`)

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/analytics/charts/ChartScales.kt
package kitoru.ui.teacher.analytics.charts

/** Escala lineal de un dominio numérico a un rango de píxeles. Pura. */
data class LinearScale(val min: Double, val max: Double, val from: Float, val to: Float) {
    fun toPx(value: Double): Float { … }
}

/** Marcas "lindas" de un eje (1, 2, 5 × 10ⁿ). Pura. */
fun niceTicks(min: Double, max: Double, target: Int = 5): List<Double>

/** Corta una lista de valores en los cortes pedidos (para el heatmap). */
fun bucketize(values: List<Int>, edges: IntArray): IntArray

/** Índice de la barra bajo un x en píxeles. Pura. */
fun barIndexAt(x: Float, count: Int, left: Float, width: Float): Int?

/** Índice de la celda de un heatmap bajo un (x, y). Pura. */
fun cellIndexAt(x: Float, y: Float, cols: Int, rows: Int, cellW: Float, cellH: Float): Pair<Int, Int>?
```

El pintado (`ChartFrame.kt`) aporta lo común: fondo, ejes, grilla, etiquetas con
`rememberTextMeasurer`, leyenda y **el tooltip**. El tooltip **no** se dibuja en el
`Canvas`: se dibuja como un `Surface` de Material 3 en un `Box` superpuesto,
posicionado con `Modifier.offset { IntOffset(x, y) }` — así hereda tipografía,
elevación y modo oscuro sin escribir a mano.

### 3.3 G1 — Actividad diaria (barras agrupadas + línea)

| | |
|---|---|
| **Tipo** | Barras agrupadas (preguntas / respuestas) + línea superpuesta (alumnos activos) |
| **Datos** | `CourseAnalytics.perDay` (con `activeStudents`, campo nuevo de §1.4c) |
| **Eje X** | Días. Ticks cada 7 días (o cada día si el rango ≤ 14). Etiqueta: `dd/MM` |
| **Eje Y** | Cantidad. Ticks con `niceTicks(0, max, 4)`. Unidad: "preguntas" |
| **Barras** | `messages` en `primary` (relleno), `replies` en `secondary` (contorno, 1 dp) — así se distinguishen también en escala de grises. Ancho: `slotWidth / 2 − 1 dp` |
| **Línea** | `activeStudents` en `tertiary`, 2 dp, con puntos de 3 dp |
| **Área bajo la línea** | Degradado `tertiary.copy(alpha = 0.12f)` → transparente |
| **Tooltip** | "Martes 12/03 — 18 preguntas · 17 respuestas · 6 alumnos activos" |
| **Interacción** | Click/tap en un día → **filtra la tabla por ese día** (chip "Día: 12/03 ✕"). Arrastre horizontal → zoom del rango (con un botón "Restablecer"). |
| **Vacío** | "Sin actividad en el período seleccionado." |
| **Accesibilidad** | `contentDescription` = "Actividad de los últimos 30 días: pico el 12 de marzo con 18 preguntas; total 1.284 preguntas." |

### 3.4 G2 — Heatmap de actividad (calendario por alumno)

| | |
|---|---|
| **Tipo** | Heatmap: filas = alumnos, columnas = días |
| **Datos** | `StudentAnalytics.daily` + `CourseAnalytics.perDay` |
| **Filas** | Top **25** alumnos por `messages` + una fila final "Otros (N)". Orden por actividad descendente (estable: desempate por alias/nombre) |
| **Columnas** | Los días del rango seleccionado (por defecto, los últimos **30**) |
| **Celda** | 12 × 12 dp con 2 dp de separación. Color = `bucketize(preguntas, [1,3,6,11])` → 5 niveles: vacío / `primary` al 12 % / 30 % / 55 % / 100 % |
| **Leyenda** | "menos ▢▢▢▢▢ más" con los cortes: 1, 3, 6, 11+ |
| **Tooltip** | "Ana Ruiz — 12/03: 8 preguntas, 7 respuestas" |
| **Interacción** | Click en una celda → filtra por **alumno + día**. Click en el nombre de la fila → filtra por alumno (y abre el detalle) |
| **Por qué esta gráfica** | Es la única que muestra de un vistazo **dónde están los huecos**: un alumno que pregunta sólo el día del parcial, una cátedra que muere a mitad de julio, o un día puntual en el que se pregunta todo. |
| **Accesibilidad** | `contentDescription` con el resumen + la tabla gemela (`StudentAnalyticsTable`) con la actividad por día como columna. |

### 3.5 G3 — Top-N de fuentes (barras horizontales apiladas)

| | |
|---|---|
| **Tipo** | Barras horizontales, apiladas en 2 segmentos |
| **Datos** | `CourseAnalytics.topSources` (corregido por §1.4a) cruzado con `QualityOverview.perMaterial` |
| **Segmentos** | `citationsSupported` en `primary` · `citationsUnsupported + citationsUnknown` en `error`. El largo total = nº de **citas** al material |
| **Eje X** | Cantidad de citas (ticks `niceTicks`). Etiquetas a la derecha del todo: "38 citas · 12 % sin respaldo" |
| **N** | Selector 5 / 10 / 20 (por defecto 10) |
| **Tooltip** | "Tema 3 — GPUs.pdf — 38 citas · 26 respaldadas · 12 sin respaldo · 39 % de las respuestas que lo citaron fallaron" |
| **Interacción** | Click en una barra → **filtra el panel de calidad** (`09-calidad.md`) por ese material y abre la pestaña "Por material" |
| **Rotulación** | Etiquetas de hasta 28 caracteres con "…" (el texto completo va en el tooltip) |
| **Por qué** | "Qué material se usa de verdad" es la pregunta que ningún otro gráfico contesta. |

### 3.6 G4 — Distribución de calidad (apilada + histograma)

Dos gráficospegados en una tarjeta:

**(a) Barra apilada 100 %** — `verified` / `unsupported` / `unknown`, en
`primary` / `error` / `tertiary`. Una sola barra a lo ancho de la tarjeta, con
leyenda de tres ítems y el detalle en el tooltip. Es el "100 % de las citas de la
cátedra" de un vistazo.

**(b) Histograma de alumnos por tasa de respaldo** — 5 tramos:
`[0, 0,2)`, `[0,2, 0,4)`, `[0,4, 0,6)`, `[0,6, 0,8)`, `[0,8, 1]`.
Barras verticales, `primary`. Sirve para **distinguir "el problema es de todos" de
"el problema es de algunos"**:

- todo el mundo en el último tramo → el material (o el troceado) está mal;
- dos o tres alumnos en el primero → son esos alumnos (o sus preguntas);
- una barra intermedia enorme → el problema es de un material concreto.

> El histograma **no** muestra a los alumnos `NONE` (no existen para el docente) y
> **muestra el alias** de los `METRICS_ONLY`.

### 3.7 G5 — Materiales problemáticos (barras horizontales con umbral)

| | |
|---|---|
| **Tipo** | Barras horizontales, 10 materiales con más `unsupportedAnswerRate` |
| **Eje X** | Tasa 0–100 % |
| **Línea de umbral** | Vertical punteada en **25 %** (`AnalyticsThresholds`) |
| **Color** | `error` si supera el umbral **y** tiene ≥ 5 respuestas; `tertiary` si supera el umbral con pocas muestras ("pocos datos"); `outline` si no |
| **Etiqueta a la derecha** | "39 % · 15 de 38 respuestas" — **la tasa sola engaña**: con 1 de 1 respuestas el 100 % no significa nada |
| **Tooltip** | Además: nº de citas del material, motivo dominante (`09-calidad.md` §3.4) y **dudas no resueltas** (`unresolvedDoubts`) |
| **Interacción** | Click → abre el material en el panel de calidad con el filtro puesto |

### 3.8 G6 — Preguntas reiteradas por alumno (barras horizontales apiladas)

| | |
|---|---|
| **Tipo** | Barras horizontales, top 10 alumnos por `repeatedQuestions` |
| **Segmentos** | `repeatedAfterUnhelpful` en `error` ("el material no respondió") · el resto en `tertiary` ("preguntó otra vez") |
| **Etiqueta** | "37 · 2,9 %" y, en la fila, el chip `dudas no resueltas: 9` |
| **Tooltip** | "Ana Ruiz — 12 preguntas repetidas de 4 dudas distintas · 9 vinieron después de una respuesta sin respaldo" |
| **Por qué** | Separa "insiste porque no le sirvió" de "insiste porque no entendió". El primero es problema del **material**; el segundo, de la **interfaz** o de la redacción del modelo. |

### 3.9 G7 — Evolución de la calidad (línea) — *P2*

Línea con dos series por semana: `citationSupportRate` (verde) y
`ungroundedAnswerRate` (rojo). Sirve para responder *"¿mejoró cuando subí el
material nuevo?"*. Se implementa después de G1–G6; los datos ya están
(`perDay` + los contadores por día).

### 3.10 Reglas de accesibilidad de todos los gráficos

1. `contentDescription` con un **resumen textual** del gráfico (qué muestra, máximo,
   mínimo y total). No con "gráfico de barras".
2. Cada gráfico tiene su **tabla gemela** (mismo dato en texto, en el detalle
   desplegable "Ver datos").
3. Interacción por **teclado**: los gráficos son `focusable`, y con las flechas se
   recorre el eje (día por día, barra por barra) leyendo el tooltip por
   `contentDescription` dinámico.
4. Contraste: los colores de estado (`primary` / `error` / `tertiary`) se eligen
   desde `MaterialTheme.colorScheme`, y el modo oscuro se prueba en los dos
   temas.
5. **Nunca** el color solo: cada barra lleva su número escrito al lado. Un
   docente con daltonismo tiene que poder leer el panel entero.

---

## 4. La tabla por alumno

### 4.1 Columnas

| # | Columna | Contenido | Ancho | Origen |
|---|---|---|---|---|
| 1 | **Alumno** | Nombre, o **alias** `A-7C3F` si la inscripción es `METRICS_ONLY`. Debajo, un chip de visibilidad (✓ Completo / ◐ Sólo métricas). | 180 dp | `Enrollment` + `AnalyticsAlias` |
| 2 | **Preguntas** | `messages` | 72 dp | `StudentAnalytics` |
| 3 | **Respuestas** | `replies` | 72 dp | |
| 4 | **Hilos** | `threads` | 64 dp | |
| 5 | **Días activos** | `activeDays` | 80 dp | |
| 6 | **Última actividad** | Relativa: "hace 2 h", "hace 3 d", "hace 3 sem" | 104 dp | `lastActivity` |
| 7 | **Citas** | Micro-barra apilada (verde/rojo/ámbar) + "38/41" | 116 dp | `citation` |
| 8 | **Tasa de respaldo** | `87,3 %`, en `primary`; en `error` si < 60 % | 96 dp | `citationSupportRate` |
| 9 | **Sin respaldo** | `9` + `9,1 %`; en `error` si > 25 % | 104 dp | `ungroundedAnswerRate` |
| 10 | **Repetidas** | `4` + `3,1 %`; en `error` si > 15 %; **"—"** si no es determinable | 104 dp | `repeatRate` (§5) |
| 11 | **Latencia** | `2,1 s` (media) | 84 dp | `avgLatencyMs` |
| 12 | **Tokens** | `12.480` (in + out) | 88 dp | `tokensIn + tokensOut` |
| 13 | **⋮** | Menú de acciones (§4.3) | 48 dp | — |

**Totales al pie** (fila fija): la suma de las columnas 2–5 y 12, y las tasas
*ponderadas* (no el promedio de los promedios):

```
Totales            1.284      1.231      186      243      —          1.096/1.255   87,3 %   9,1 %   2,9 %   2,1 s   412.900
```

> **La fila de totales tiene que reconciliar con los KPIs de cabecera.** Es la
> prueba de que la tabla y los gráficos contaron lo mismo. Si no cuadran, hay un
> bug: el test de §11 lo verifica. Las columnas que no se suman (última
> actividad) van con "—", y la latencia es la **media ponderada por respuesta**
> (`Σ latencia × respuestas / Σ respuestas`), no el promedio de las medias.

### 4.2 Orden

- **Por defecto**: `messages` descendente (quien más usa la herramienta primero).
- **Ordenable por cualquier columna**: click en el encabezado alterna asc/desc; un
  triángulo ▲/▼ marca la columna activa.
- **Desempate estable**: por `alias` o nombre (alfabético), así dos ejecuciones
  del analizador dan la misma tabla.
- **Alternativas rápidas** (filtros, no ordenes): "sólo alumnos con alertas"
  (cualquier métrica en zona de alerta), "sólo activos", "sólo inactivos".

### 4.3 Acciones por fila

Click en la fila → **abre el detalle** (panel lateral en desktop, hoja inferior en
Android): las métricas del alumno, su mini-timeline, sus hilos (según
visibilidad) y sus dudas reiteradas. El menú `⋮` ofrece:

| Acción | Disponible | Qué hace |
|---|---|---|
| **Ver sus chats** | sólo si `visibility == FULL` | `nav.push(STUDENT_CHATS, userId)` (`07-chats.md`). Con `METRICS_ONLY`, la acción aparece **deshabilitada** con el motivo: *"Este alumno sólo comparte métricas: no puedo mostrar sus conversaciones."* |
| **Ver su ficha** | siempre | `nav.push(STUDENTS, userId)` (`08-alumnos.md`) |
| **Exportar métricas** | siempre | CSV/JSON de **esa** fila (sin texto si no hay visibilidad) |
| **Exportar sus hilos** | sólo si `FULL` | Markdown (regla de `07-chats.md` §9) |
| **Cambiar visibilidad** | sólo `TITULAR` | Abre el `VisibilitySelector` de `08-alumnos.md`. Bajar la visibilidad (FULL → METRICS_ONLY → NONE) pide confirmación; **subirla** exige el consentimiento que define `08-alumnos.md` D3 |
| **Copiar resumen** | siempre | Al portapapeles: un texto de 5 líneas con las métricas del alumno (para pegar en un informe o en un mail) |
| **Marcar para revisión** | siempre | Guarda una nota interna en el alumno (extensión de `MaterialNote` de `09-calidad.md`, applied al alumno) |

### 4.4 Responsive

| Ancho | Presentación |
|---|---|
| ≥ 1000 dp | Tabla completa, 13 columnas, scroller horizontal con la primera columna **fija** (sticky) |
| 600–999 dp | Tabla con las columnas 1, 2, 3, 7, 8, 9, 10 y el resto en el detalle |
| < 600 dp (Android) | `StudentAnalyticsList`: una **tarjeta por alumno** con nombre/alias, 4 números grandes, la micro-barra de citas y dos chips (alertas). Un `LazyColumn` |

---

## 5. Comportamiento

### 5.1 Qué significa "el alumno preguntó X"

- Un `ChatMessage` con `role == "user"`, en un hilo de la cátedra, cuyo autor es
  un alumno con `visibility.showsMetrics`.
- Los mensajes del docente (`role == "teacher"`) y los de herramienta
  (`role == "tool"`) **no** son preguntas.
- Una pregunta del alumno en un hilo **creado por otro** (responder en el hilo de
  un compañero) no ocurre: los hilos son por autor (`ChatThread.authorId`).

### 5.2 La pregunta sin respuesta útil

La cadena de hechos que el panel cuenta (todo determinista):

```
pregunta (user)
   │
   ├─ ¿hay una respuesta del asistente después?  ── no ──► AnswerIssue.SIN_RESPUESTA
   │
   └─ sí: la respuesta es…
         ├─ texto vacío o < 20 caracteres ─────────► RESPUESTA_VACIA
         ├─ citation.unsupported > 0 ───────────────► CITA_SIN_RESPALDO
         ├─ citation.unknown > 0 ───────────────────► CITA_SIN_FUENTE
         ├─ sources no vacío y citation == null
         │   o citation.total == 0 ────────────────► SIN_CITAS
         └─ ninguna de las anteriores ─────────────► NINGUNO (la respuesta fue útil)
```

Tres comentarios importantes:

1. **"Respondió de memoria" NO es lo mismo que "respondió mal".** Una respuesta
   sin citas (`SIN_CITAS`) se cuenta aparte, como `uncitedAnswerRate`: el modelo
   puede haber contestado bien una pregunta general ("¿qué me recomendás
   estudiar?"). Por eso `SIN_CITAS` **no** es una respuesta inútil por sí sola; es
   inútil **cuando el modelo recuperó material** (`sources` no vacío) y no se
   apoyó en él. Esa distinción es la que separa "el material no alcanzó" de "el
   modelo se lo sabe".
2. **`SIN_RESPUESTA` también es una respuesta inútil**: el alumno preguntó y no
   obtuvo nada (timeout, error, cierre de la app a mitad de la generación). Es un
   dato de **fiabilidad del servicio**, no del material; por eso se reporta
   aparte (`unansweredQuestions`).
3. **Una respuesta con citas pero sin respaldo es el caso Interesting**: el
   modelo *intentó* apoyarse en el material y no pudo. Es exactamente el caso que
   `09-calidad.md` atiende en detalle; aquí sólo se cuenta.

### 5.3 El detector de pregunta reiterada

#### 5.3.1 Normalización del texto

Reutilizable por `:reports` (que ya agrupa "temas" con BM25) y por `:core`:

```kotlin
// core/src/commonMain/kotlin/kitoru/text/QuestionText.kt   (NUEVO)
package kitoru.text

import kitoru.platform.stripDiacritics

/**
 * Normalización y tokenización de preguntas.
 *
 * Vive en `:core` (y no en `:backend`) porque lo usan el analizador de
 * comportamiento (`:core`), el agrupador de temas (`:reports`) y, más adelante,
 * el índice BM25 (`:backend`). `Bm25Index.tokenize` puede delegar acá pasando
 * su propia stoplist: el resultado no cambia.
 */
object QuestionText {

    private val CAMEL = Regex("([a-z0-9])([A-Z])")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /** Stoplist del español + los conectores típicos del inglés. */
    val STOP: Set<String> = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas", "lo", "al", "del",
        "y", "o", "u", "e", "en", "para", "por", "con", "sin", "sobre", "entre",
        "desde", "hasta", "hacia", "mediante", "segun", "que", "quien", "cual",
        "cuando", "donde", "como", "porque", "pero", "tambien", "mas", "muy",
        "menos", "ya", "si", "no", "me", "te", "se", "nos", "les", "mi", "tu",
        "su", "sus", "este", "esta", "esto", "es", "son", "era", "fue", "ser",
        "estar", "hay", "tengo", "tiene", "puedo", "puede", "quiero", "necesito",
        "gracias", "hola", "bueno", "buenas", "adios", "saludos",
        "the", "a", "an", "of", "to", "in", "is", "are", "was", "for", "on",
        "with", "how", "what", "why", "when", "where", "do", "does", "i", "you",
        "it", "this", "that", "and", "or", "be", "can", "thanks", "hello",
    )

    /** Normaliza a minúsculas, sin acentos, sin puntuación, con camelCase partido. */
    fun normalize(text: String): String {
        val spaced = text.replace(CAMEL, "$1 $2").lowercase()
        return stripDiacritics(spaced)
            .replace(NON_ALNUM, " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    /**
     * Tokens con significado: sin stoplist y sin palabras de 1-2 letras.
     *
     * Los **números se quedan**: son los tokens más discriminantes que hay
     * ("el día 12" no es "el día 13"), y la guarda G3 los usa.
     */
    fun tokens(text: String, stopwords: Set<String> = STOP, minLength: Int = 3): List<String> =
        normalize(text)
            .split(' ')
            .filter { it.length >= minLength && it !in stopwords }

    /**
     * Radical de 5 letras.
     *
     * Es el mismo criterio de generosidad que ya usa el verificador de citas
     * (`commonPrefix >= 5`): "instalar", "instalo" e "instalación" son la misma
     * palabra para estos efectos. "proyecto"/"proyectos" y "parcial"/"parciales"
     * también.
     */
    fun stem(token: String): String = if (token.length > 5) token.take(5) else token

    /** Tokens con significado, ya radicalizados y sin repetir. Los números no se radicalizan. */
    fun contentTokens(text: String): Set<String> =
        tokens(text).map { if (it.all { c -> c.isDigit() }) it else stem(it) }.toSet()

    /**
     * Los números de una pregunta (multiconjunto Normalizado).
     *
     * Sirven para la guarda G3: si dos preguntas traen números distintos, no son
     * la misma pregunta.
     */
    fun numbers(text: String): Set<String> =
        normalize(text).split(' ').filter { it.isNotEmpty() && it.all { c -> c.isDigit() } }.toSet()
}
```

> **Por qué el mismo criterio que el verificador.** El proyecto ya decidió que
> "renderiza" y "renderizar" son la misma idea para efectos de respaldo
> (`CitationVerifier.coveredBy`: prefijo común ≥ 5 y diferencia de largo ≤ 3).
> Reutilizar ese criterio en la similitud de preguntas hace que las dos piezas
> del panel hablen el mismo idioma y evita tener dos listas de stopwords.

#### 5.3.2 La medida de similitud

Cada pregunta se representa como un **conjunto** de tokens radicalizados (sin
repetición: "proyecto proyecto" = {proye}). El IDF se calcula sobre **todas las
preguntas de la cátedra** (no sólo las del alumno): con la muestra de un alumno
el IDF es ruido; con la de la cátedra, "proyecto" baja de peso y "retropropagación"
sube, que es justo lo que queremos.

```
N        = total de preguntas visibles de la cátedra
df(t)    = nº de preguntas que contienen el token t
idf(t)   = ln(1 + N / (1 + df(t)))

A, B     = conjuntos de tokens de las dos preguntas
peso(X)  = Σ_{t∈X} idf(t)²

coseno(A,B)     = peso(A ∩ B) / (√peso(A) · √peso(B))
contención(A,B) = peso(A ∩ B) / min(peso(A), peso(B))

sim(A,B) = 0,5 · coseno + 0,5 · contención
```

**Por qué la mezcla de las dos.** El coseno solo castiga las preguntas que
"contienen" a la otra (una pregunta larga que incluye a la corta no es la misma
pregunta). La contención sola no castiga que la pregunta sea una versión reducida
("¿instalo el proyecto?" vs "¿cómo instalo el proyecto?"). La media de las dos
cubre los dos casos y es simétrica.

#### 5.3.3 Las guardas (lo que evita los falsos positivos)

Una pareja de preguntas **se considera reiterada** si cumple **las cinco**:

| # | Guarda | Por qué |
|---|---|---|
| G1 | Ambas tienen **≥ 2 tokens de contenido** | "ok" o "sí" no se comparan con nada |
| G2 | Comparten **≥ 2 tokens de contenido**, o sus cadenas normalizadas son **idénticas** | Una sola palabra en común ("parcial") no es la misma pregunta |
| G3 | **No hay números en conflicto**: `QuestionText.numbers(q1) == QuestionText.numbers(q2)` (si una de las dos no tiene números, no hay conflicto) | "¿El parcial es el día 12?" y "¿El parcial es el día 13?" son preguntas distintas. Los números no se radicalizan y pesan fuerte |
| G4 | La pregunta original es **anterior** y está dentro de la **ventana de 14 días** | La misma pregunta en marzo y en noviembre no es insistencia |
| G5 | `sim(A, B) ≥ 0,80` | El umbral (§5.3.5) |

#### 5.3.4 El algoritmo, completo

```
1. Recorrer los hilos de la cátedra, agrupados por autor.
2. Para cada alumno visible:
   a. Extraer sus preguntas en orden cronológico (createdAt, y a igualdad, id).
   b. Para cada pregunta, encontrar su respuesta (el primer assistant posterior
      del mismo hilo) y clasificar el AnswerIssue (§5.2).
   c. Normalizar y tokenizar cada pregunta → conjunto de tokens.
3. Calcular df(t) e idf(t) sobre TODAS las preguntas visibles de la cátedra.
4. Índice invertido: token → lista de preguntas que lo contienen.
5. Para cada pregunta q2, generar candidatas q1:
   - sólo preguntas del mismo alumno, con createdAt(q1) < createdAt(q2),
   - dentro de la ventana de 14 días,
   - que compartan ≥ 1 token con df(t) ≤ 0,30 · N   (tokens discriminantes:
     esto evita comparar O(n²) contra las preguntas que comparten palabras
     genéricas),
   - como mucho 200 candidatas por pregunta (las de token más raro primero;
     el corte es determinista).
6. Para cada candidata, calcular sim(q1, q2) y aplicar las guardas G1..G5.
7. Agrupar con unión-find: cada pareja con sim ≥ 0,80 une los grupos.
   El representante de cada grupo es la pregunta MÁS ANTIGUA.
8. Cada pregunta que no es representante de su grupo es una REPETIDA.
9. Cada grupo con ≥ 2 miembros es un GRUPO DE REPETICIÓN.
10. Un grupo es una DUDA NO RESUELTA si la respuesta a su representante fue
    inútil (AnswerIssue.isUnhelpful).
11. Atribuir la duda no resuelta a los materiales usados en esa respuesta
    (citation.checks → materialId; si no hay detalle, sources/sourceIds).
```

**Costo.** Con `q` preguntas de un alumno: paso 5 acota a `200 · q` comparaciones
y cada comparación es O(tokens). Con 300 preguntas en toda la cátedra, el cálculo
entra holgadamente en el milisegundo. **Es una función pura**: los mismos datos
dan el mismo resultado, siempre.

#### 5.3.5 El umbral, con un ejemplo trabajado

**Datos** (40 preguntas en la cátedra; el alumno Ana tiene 6). Los tokens ya
salen radicalizados y con la stoplist aplicada:

| # | Pregunta | Tokens de contenido |
|---|---|---|
| 1 | "¿Cómo instalo el proyecto?" | {insta, proye} |
| 2 | "para instalar el proyecto final" | {insta, proye, fina} |
| 3 | "¿Cómo se compila?" | {compi} → **1 token**: no se compara (G1) |
| 4 | "¿Cuál es la fecha del parcial?" | {fech, parci} |
| 5 | "¿Cómo instalo el proyecto?" | {insta, proye} |
| 6 | "¡Gracias!" | {} → stoplist ("gracias"): no se compara (G1) |

**IDF** (N = 40, `idf(t) = ln(1 + 40/(1 + df(t)))`):

| Token | df | idf | idf² |
|---|---|---|---|
| `insta` | 5 | ln(1 + 40/6) = 2,036 | 4,146 |
| `proye` | 6 | ln(1 + 40/7) = 1,905 | 3,627 |
| `fina` | 2 | ln(1 + 40/3) = 2,663 | 7,092 |
| `fech` | 3 | ln(1 + 40/4) = 2,398 | 5,750 |
| `parci` | 12 | ln(1 + 40/13) = 1,406 | 1,976 |

**Comparación 1 vs 2** (la que decide):

| Medida | Cálculo | Valor |
|---|---|---|
| `peso(A)` | 4,146 + 3,627 | 7,773 |
| `peso(B)` | 4,146 + 3,627 + 7,092 | 14,865 |
| `peso(A∩B)` | 4,146 + 3,627 | 7,773 |
| coseno | 7,773 / (√7,773 · √14,865) = 7,773 / (2,788 · 3,855) | 0,723 |
| contención | 7,773 / min(7,773, 14,865) | 1,000 |
| **sim** | 0,5 · 0,723 + 0,5 · 1,000 | **0,861 ≥ 0,80** ✅ |

→ La pregunta 2 es una **repetición** de la 1. Guardas: G1 ✔ (2 y 3 tokens),
G2 ✔ (2 tokens compartidos), G3 ✔ (sin números), G4 ✔ (mismo día), G5 ✔.

> **Este ejemplo justifica la medida híbrida.** El coseno solo daba 0,723 (por
> debajo del umbral): la pregunta 2 es "más larga" que la 1. La contención lo
> compensa, porque la 1 **está contenida** en la 2. Con una medida de Jaccard
> puro, esta pareja tampoco se habría detectado.

**Comparación 4 vs 5**: {fech, parci} vs {insta, proye} → intersección vacía →
`sim = 0` ✘ (distintas).

**Comparación 3 vs 5**: 1 token vs 2 → **rechazada por G1** (sin mirar la
similitud) ✘.

**Resultado para Ana:** 6 preguntas → 3 grupos ({1, 2, 5}, {3}, {4}; la 6 no es
comparable) → `repeatedQuestions = 2` (las preguntas 2 y 5), `repeatGroups = 1`.
Si la respuesta a la pregunta 1 fue `CITA_SIN_RESPALDO`, ese grupo es una
**duda no resuelta** y se atribuye al material que usó esa respuesta.

#### 5.3.6 Por qué 0,80 y no otro número

| Umbral | Qué detecta | Qué falla |
|---|---|---|
| 0,70 | Aggressive: detecta reformulaciones轻度 | Marca como repetida una pregunta con un tema compartido |
| **0,80** | **Repeticiones claras**: reformulaciones, typos, minúsculas, traducciones parciales, "instalo" vs "instalar" | Deja pasar algunas reformulaciones muy distintas — aceptable: el panel es una pista, no un juez |
| 0,90 | Muy conservador: casi sólo duplicados textuales | Pierde el caso interesante ("no me explicaste bien") |

**El umbral es un parámetro, no una constante dura**: vive en
`BehaviorAnalyzer` (`const val REPEAT_SIMILARITY = 0.80`) y se puede exponer en
ajustes más adelante. Cambiarlo cambia los números de toda la cátedra, así que
cualquier cambio debe venir con una nota en el changelog.

### 5.4 Qué muestra el panel de comportamiento

| Superficie | Contenido |
|---|---|
| KPI #5 | El número de repetidas + la tasa |
| G6 | Top 10 alumnos, segmentado por "tras respuesta inútil" |
| Tarjeta "Dudas no resueltas" | Los **grupos** de repetición con su texto (si hay visibilidad): *"¿Cómo instalo el proyecto?" → 3 alumnos lo preguntaron; la respuesta no se apoyó en el material"* |
| Tabla por alumno | Columna "Repetidas" +, en el detalle del alumno, la lista de sus grupos |

En el detalle de un alumno, un grupo se ve así:

```
┌ Duda reiterada ─────────────────────────────────────────────────────────┐
│ "¿Cómo instalo el proyecto?"                        similitud 0,95      │
│ Se preguntó 3 veces: 12/03, 12/03, 15/03                              │
│ La respuesta anterior: "Afirmó cosas que el material no dice"          │
│ Materiales usados: Guía de instalación.pdf                             │
│ [Ver el material]  [Ver los hilos]        ← sólo si visibility == FULL │
└──────────────────────────────────────────────────────────────────────────┘
```

Con `METRICS_ONLY` el mismo bloque aparece **sin texto**: *"Duda reiterada (3
veces) · la respuesta anterior no se apoyó en el material"*, y los botones de
hilos no están.

---

## 6. Calidad

### 6.1 Por alumno

Ya está en `StudentAnalytics` (`citationSupportRate`, `ungroundedAnswerRate`) y se
extiende con `09-calidad.md` (`uncitedAnswerRate`, `groundedAnswerRate`,
`citationReasons`). Lo que aporta **este** panel:

- La columna "Sin respaldo" de la tabla (§4.1), con color por umbral.
- El **G4(b)**, el histograma que muestra si el problema es de todos o de algunos.
- El **cruce por material**, que es lo accionable (§6.2).

### 6.2 Por material: el cruce

**La pregunta**: *¿qué material de la cátedra genera más respuestas sin respaldo?*

Es un indicador **de calidad del material**, no del alumno: si el 40 % de las
respuestas que citan "Tema 3 — GPUs.pdf" no se apoyan en el archivo, el problema
es del archivo (está mal extraído, mal troceado, o le falta el contenido) o del
modelo con **ese** contenido. En los dos casos la acción es sobre el material.

**El cálculo** (determinista, ya especificado en `09-calidad.md` §3.3, y aquí
agregando las dudas no resueltas):

```
para cada respuesta del asistente m de un alumno visible, con m.citation != null:
    para cada check c de m.citation.checks:
        materialId = c.materialId  (o resuelto por índice → sources/sourceIds)
        si materialId == null: → cajón "material desconocido"; continue
        porMaterial[materialId].citations       += 1
        porMaterial[materialId].citationsOK     += (c.supported ? 1 : 0)
        respuestasQueUsan[materialId].add(m.id)
        si !c.supported:
            porMaterial[materialId].answersWithFailures += 1   (respuestas distintas)
            porMaterial[materialId].reasons[c.reasonKind] += 1

# las dudas no resueltas (este panel)
para cada grupo de repetición con respuesta anterior inútil:
    para cada material usado en esa respuesta:
        porMaterial[materialId].unresolvedDoubts += 1

tasaSinRespaldo(material)  = answersWithFailures / max(1, answersUsing)
tasaPorCita(material)      = citasFallidas / max(1, citations)
```

**La tabla del cruce** (la vista accionable):

```
Material                          Resp.  Sin resp.   %      Citas  Dudas sin resolver   Motivo dominante
─────────────────────────────────────────────────────────────────────────────────────────────────────
Tema 3 - GPUs.pdf                 38      15       39 %    61     7                   La fuente no lo dice
Transcripción Charla 2.mp4         9       4       44 %    14     2                   Detalle inventado
Apuntes Tema 5.pdf                21       2       10 %    33     0                   —
Clase 1 - Intro.pdf              14       3       21 %    18     1                   Detalle inventado
```

Cinco columnas y una acción:

| Columna | Cálculo | Para qué |
|---|---|---|
| **Resp.** | respuestas que citaron el material | volumen (muestra si el dato es concluyente) |
| **Sin resp.** | de esas, cuántas tuvieron ≥ 1 cita sin respaldo | cantidad absoluta |
| **%** | `Sin resp. / Resp.` | la tasa que dispara la alerta (**con ≥ 5 respuestas**) |
| **Citas** | nº de citas al material | contexto |
| **Dudas sin resolver** | grupos de reiteración sin resolver atribuidos (§5.4) | **el vínculo con el comportamiento**: el material no sólo falló, sino que hizo que el alumno volviera a preguntar |
| **Motivo dominante** | el `reasonKind` con más citas fallidas | *qué* está mal (diagnóstico de `09-calidad.md` §3.4) |

**El cruce que lo hace valioso** es la última combinación: *material → respuestas
sin respaldo → dudas que se repitieron*. Un material con 3 fallos y 0 dudas
repetidas es un problema puntual; un material con 3 fallos y 5 dudas repetidas es
un agujero en el material que el docente tiene que tapar.

### 6.3 Interpretación (lo que el copy NO dice)

El panel **nunca** dice:

- ❌ "La IA acertó el 87 %." → ✅ "El 87 % de las citas se apoya en el material."
- ❌ "Este alumno no sabe usar la herramienta." → ✅ "9 de sus 38 respuestas
  afirmaron algo que el material no dice." (y el dato es del **material**, no del
  alumno: el mismo material aparece con la misma tasa en los alumnos que sí
  citaron bien).
- ❌ "El modelo es malo." → ✅ "La fuente no lo dice" / "Detalle inventado"
  (motivos tipificados).

### 6.4 La vista de calidad completa vive en `09-calidad.md`

Este panel muestra **el resumen y el cruce**; el detalle cita por cita, los
motivos y las acciones correctivas (reindexar, editar el texto, responder al
alumno) están en el panel de calidad de `09-calidad.md`. El botón "Ver el detalle
de calidad" salta a esa pantalla con el filtro del material ya puesto.

---

## 7. Privacidad

### 7.1 La regla

> **Un número sí, un texto no.**
> Los agregados, las tasas y los conteos se calculan **siempre** (salvo los
> alumnos `NONE`, que no existen para el docente). Lo único que la visibilidad
> censura es el **texto**: preguntas, respuestas, títulos de hilo, etiquetas,
> fragmentos que fallaron y **nombres**.

Esto tiene una consecuencia importante, **a favor** del producto: un alumno con IA
local que accepte compartir **estadísticas** sigue aportando valor real a la
cátedra (participación, calidad, material problemático) sin que nadie lea una
sola palabra de lo que escribió.

### 7.2 Lo que falta hoy (el "filtro de texto")

`AnalyticsAnalyzer` ya filtra por `visibility.showsMetrics` (los `NONE` no
aparecen en ningún agregado). Lo que **no** está resuelto:

| # | Fuga | Dónde | Corrección |
|---|---|---|---|
| F1 | **Nombres de alumnos `METRICS_ONLY`** en la lista de inactivos | `CourseAnalytics.inactiveStudents: List<String>` | Cambiar a `inactiveStudentIds: List<String>` (§1.4b) y resolver la etiqueta con la política de alias |
| F2 | **Nombres en la tabla por alumno** | `StudentAnalytics.displayName` | La tabla muestra el alias cuando `visibility != FULL`; el nombre real queda en el padrón (`08-alumnos.md`) |
| F3 | **Texto de las preguntas** en cualquier vista | (nuevo: `RepeatedQuestion.originalText/repeatText`) | Sólo se puebla si `visibility.showsText`; si no, `textRedacted = true` y la UI muestra la versión sin texto |
| F4 | **Títulos de hilo** en el detalle | (nuevo) | Sólo con `FULL`; con `METRICS_ONLY` → "Conversación N" (regla de `07-chats.md` §3.2) |
| F5 | **Fragmentos que fallaron** | `CitationCheckStats.snippet` / `.sourceExcerpt` | Sólo con `FULL` (regla de `09-calidad.md` §9) |
| F6 | **`topSources` cuenta fuentes de alumnos ocultos** | `AnalyticsAnalyzer.sourceCounts` (§1.4a) | Filtrar por visibilidad |

### 7.3 La política de etiqueta

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/AnalyticsAlias.kt (AMPLIADO)

/**
 * Cómo se muestra un alumno en la analítica, según su inscripción.
 */
data class AnalyticsLabel(
    /** Lo que se escribe en la tabla. */
    val text: String,
    /** ¿Es el nombre real? */
    val isRealName: Boolean,
    /** La visibilidad que origina la etiqueta. */
    val visibility: ChatVisibility,
)

object AnalyticsLabels {
    /**
     * Etiqueta de un alumno para la analítica.
     *
     * - FULL          → el nombre real.
     * - METRICS_ONLY  → el alias estable (A-7C3F). El nombre real sigue
     *   disponible en la pantalla de Alumnos, que es donde el docente gestiona
     *   la visibilidad; la analítica y sus exportaciones no lo vuelven a sacar.
     * - NONE          → no hay etiqueta: el alumno no aparece.
     */
    fun forEnrollment(enrollment: Enrollment, alias: String): AnalyticsLabel = when {
        enrollment.visibility.showsText -> AnalyticsLabel(enrollment.displayName, true, enrollment.visibility)
        else -> AnalyticsLabel(alias, false, enrollment.visibility)
    }
}
```

**Sobre el alias, sin exagerar susocial**: **no es una barrera de seguridad**.
El docente que gestiona el padrón ve los nombres en otra pantalla y puede
correlacionar mentalmente ("A-7C3F es el de la fila 3"). Lo que el alias garantiza
es que **la analítica, sus capturas y sus archivos exportados no contengan
nombres de alumnos que no compartieron su texto** — que es exactamente el
escenario de riesgo (mandar el CSV al departamento, pegarlo en un mail, adjuntarlo
a un parcial).

> Los alumnos `NONE` **no** aparecen siquiera en el conteo de "alumnos ocultos"
> con nombre: sí aparecen en el **número** (`hiddenStudents`), porque el docente
> tiene que saber que los datos están incompletos. Es la misma regla que
> `10-informes.md` §4.6.

### 7.4 Matriz de visibilidad × superficie

| Superficie | `FULL` | `METRICS_ONLY` | `NONE` |
|---|---|---|---|
| KPIs de cabecera | sí | sí (los mismos) | no aporta |
| Gráficos (G1–G6) | sí | sí, con **alias** en las etiquetas | no aporta |
| Tabla por alumno | nombre | **alias** + chip "Sólo métricas" | no aparece |
| Totales de la tabla | sí | sí | — |
| Detalle de un alumno | todo | números, hilos **sin título**, citas **sin fragmento**, dudas reiteradas **sin texto** | — |
| "Ver sus chats" | activo | **deshabilitado** con motivo | no aparece |
| Exportar sus hilos | sí | **no** | no aparece |
| Exportar sus métricas | sí | sí (con alias) | no aparece |
| En el informe/export | nombre | **alias** | no aparece |

### 7.5 Reglas de implementación

1. **La redacción ocurre en el analizador, no en la UI.** El `BehaviorAnalyzer`
   y el `AnalyticsAnalyzer` producen las etiquetas ya aplicadas. La UI **no**
   decide: si el dato llega con el alias, muestra el alias. Esto evita que un
   `Text(message.text)` olvidado en un composable rompa la regla.
2. **El texto nunca se persiste en la analítica.** `RepeatedQuestion` con texto
   vive **en memoria**, en el estado del view-model; no se escribe en ningún
   archivo. Si algún día se cachea `CourseAnalytics`, se cachea la versión **sin
   texto**.
3. **La búsqueda de la tabla sólo busca en lo visible.** Con `METRICS_ONLY` el
   alias se busca, pero no hay texto que indexar.
4. **Mirar la analítica no se audita; exportar sí.** Coherente con `07-chats.md`
   §6.2: la agregada no se registra; una exportación de métricas se registra con
   `kind = EXPORT`, `detail = "csv-metricas"`.
5. **El `AccessLog` del docente** (07-chats) registra `VIEW_METRICS` al abrir el
   detalle de un alumno `METRICS_ONLY`, y `VIEW_TEXT` sólo si se abrió un hilo con
   texto real.

---

## 8. Exportación

La exportación **no** se reimplementa: usa el seam `FileExporter`/`SaveTarget` y
los renderizadores de `10-informes.md` (§2). Lo que este panel define es **qué se
exporta**.

### 8.1 Qué se exporta

Dos objetos, dos botones:

| Botón | Formato | Contenido |
|---|---|---|
| **Exportar CSV** | `.csv` | **Una tabla por archivo** (regla de `10-informes.md` §1.2): 4 archivos |
| **Exportar JSON** | `.json` | El objeto completo, con `schemaVersion` |

### 8.2 CSV — cuatro archivos

Con las reglas de `10-informes.md` §1.2: separador `;`, **BOM UTF-8**, **CRLF**,
comillas RFC 4180, decimal `.`, sin separador de miles, encabezados en
`snake_case` **sin acentos**, y **celda vacía ≠ 0**.

**`analitica-resumen_<código>_<fecha>.csv`** — una fila por métrica:

```
clave;valor;unidad;umbral_alerta
alumnos_inscritos;24;alumnos;
alumnos_visibles;21;alumnos;
alumnos_activos;18;alumnos;
alumnos_ocultos;3;alumnos;
participacion;0.8571;proporcion;0.35
preguntas;1284;preguntas;
respuestas;1231;respuestas;
conversaciones;186;conversaciones;
media_preguntas_por_alumno;53;preguntas;
tasa_citas_respaldo;0.8734;proporcion;0.60
respuestas_sin_respaldo;112;respuestas;
tasa_respuestas_sin_respaldo;0.0910;proporcion;0.25
preguntas_reiteradas;37;preguntas;
tasa_preguntas_reiteradas;0.0288;proporcion;0.15
dudas_sin_resolver;9;preguntas;
materiales_con_fallos;3;materiales;
```

**`analitica-alumnos_<código>_<fecha>.csv`** — una fila por alumno (el encabezado va
en **una sola línea**; aquí está partido para que se lea):

```
alumno;alias;visibilidad;preguntas;respuestas;conversaciones;dias_activos;ultima_actividad;citas_verificadas;citas_sin_respaldo;citas_sin_fuente;tasa_citas_respaldo;tasa_respuestas_sin_respaldo;respuestas_sin_citas;preguntas_reiteradas;tasa_preguntas_reiteradas;dudas_sin_resolver;hilos_abandonados;latencia_media_ms;latencia_p95_ms;tokens_entrada;tokens_salida;herramientas;fallos_herramientas
Ana Ruiz;;FULL;318;301;44;22;2026-03-12T18:24:00Z;212;9;0;0.9593;0.0299;11;12;0.0377;5;1;2100;7400;8120;9310;3;0
A-7C3F;;METRICS_ONLY;47;44;9;6;2026-03-10T09:02:00Z;22;6;1;0.7586;0.1364;9;4;0.0851;2;0;3100;5200;1650;2280;0;0
```

- La columna `alumno` va **vacía** para los `METRICS_ONLY`; su identificador va en
  `alias`.
- `preguntas_reiteradas` y `tasa_preguntas_reiteradas` van **vacías** si el
  detector no pudo correr (no hay texto): nunca `0`.
- `visibilidad` va siempre: es la explicación de por qué hay una fila con alias.

**`analitica-actividad_<código>_<fecha>.csv`** — una fila por día:

```
dia;preguntas;respuestas;alumnos_activos
2026-03-01;41;39;7
```

**`analitica-materiales_<código>_<fecha>.csv`** — una fila por material:

```
material;titulo;respuestas_que_lo_usan;respuestas_sin_respaldo;
tasa_sin_respaldo;citas;citas_verificadas;citas_sin_respaldo;tasa_por_cita;
dudas_sin_resolver;motivo_dominante;sospechoso
m-3f2a;Tema 3 - GPUs.pdf;38;15;0.3947;61;49;12;0.1967;7;no_apoya;true
```

### 8.3 JSON

```json
{
  "schemaVersion": 1,
  "kind": "analytics",
  "courseId": "c-ia-2026-1",
  "courseCode": "IA-2026-1",
  "generatedAt": 1773360000000,
  "period": { "from": 1772000000000, "to": 1773360000000 },
  "thresholds": { "supportAlert": 0.6, "ungroundedAlert": 0.25, "repeatAlert": 0.15 },
  "visibility": { "full": 12, "metricsOnly": 9, "none": 3 },
  "summary": { "…": "mismos campos que el CSV de resumen" },
  "students": [ { "…": "mismos campos que el CSV de alumnos" } ],
  "days": [ { "…": "mismos campos que el CSV de actividad" } ],
  "materials": [ { "…": "mismos campos que el CSV de materiales" } ],
  "notes": [
    "3 de 24 alumnos no comparten sus datos con la cátedra; sus datos no están en este archivo.",
    "Las preguntas repetidas no pudieron calcularse para 2 alumnos: no compartieron el texto."
  ]
}
```

- Se serializa con el `KitoruJson` de `core/.../domain/JsonStore.kt`
  (`prettyPrint`, `encodeDefaults`, `ignoreUnknownKeys`).
- `schemaVersion` permite reimportar o procesar con scripts.
- **El texto nunca viaja**: ni preguntas, ni respuestas, ni títulos.
- Los `null` van como `null` (celda vacía en el CSV) — "sin dato ≠ cero".

### 8.4 Dónde y con qué nombre

- **Desktop**: diálogo "Guardar como…" → `FileDialogs.saveFile(suggestedName, …)`
  (`07-chats.md` §9.4). Destino por defecto:
  `KitoruPaths.exportsDir/<courseId>/`.
- **Android**: `CreateDocument` (SAF) vía el mismo `FileDialogs`; si el usuario
  cancela, se escribe en `KitoruPaths.sharedDir` y se abre el *share sheet*.
- **Nombres**:
  `analitica-resumen_IA-2026-1_2026-03-12.csv`,
  `analitica-alumnos_IA-2026-1_2026-03-12.csv`,
  `analitica-actividad_IA-2026-1_2026-03-12.csv`,
  `analitica-materiales_IA-2026-1_2026-03-12.csv`,
  `analitica_IA-2026-1_2026-03-12.json`.
- La exportación queda anotada en `config/exports.json`
  (`ExportRecord` de `10-informes.md` §2.7) con `reportKind = "analytics"`.

### 8.5 Qué NO se exporta

| No se exporta | Por qué |
|---|---|
| Texto de preguntas o respuestas | Es contenido de la conversación; la visibilidad lo protege |
| Títulos de hilo y etiquetas | Son texto escrito por el alumno (`10-informes.md` §4.6) |
| Nombres de alumnos `METRICS_ONLY` | Se van como alias (§7) |
| Fragmentos que fallaron (`snippet`, `sourceExcerpt`) | Sólo con `FULL`, y el detalle vive en el panel de calidad |
| Los alumnos `NONE` | No existen para el docente |
| Los números con "pocos datos" | Van con la columna de bandera `suficiente_muestras = false` |

---

## 9. Archivos, composables y view-models

### 9.1 `:core` (dominio)

| Archivo | Estado | Contenido |
|---|---|---|
| `core/.../domain/Analytics.kt` | modificado | Campos nuevos de `StudentAnalytics` y `CourseAnalytics` (§1.5); `DayActivity.activeStudents`; `inactiveStudentIds` en lugar de `inactiveStudents` |
| `core/.../domain/AnalyticsAnalyzer.kt` | modificado | `window` (filtro por período); corregir el filtro de visibilidad de `topSources`; `daily` por alumno; percentiles de latencia; `enrolledStudents`/`hiddenStudents`/`courseActiveDays` |
| `core/.../domain/Behavior.kt` | **nuevo** | `AnswerIssue`, `RepeatedQuestion`, `StudentBehavior`, `BehaviorOverview`, `MaterialGap` |
| `core/.../domain/BehaviorAnalyzer.kt` | **nuevo** | `BehaviorAnalyzer.analyze(...)` — el detector de repetición (§5.3), función pura |
| `core/.../domain/AnalyticsThresholds.kt` | **nuevo** | Los umbrales (§1.5) |
| `core/.../domain/AnalyticsAlias.kt` | **nuevo** | `AnalyticsAlias.forUser`, `AnalyticsLabels.forEnrollment` |
| `core/.../text/QuestionText.kt` | **nuevo** | Normalización, tokenización y radical (§5.3.1) |
| `core/.../domain/AnalyticsCsv.kt` | **nuevo** | `AnalyticsCsvWriter`: los cuatro CSV (§8.2). Funciones puras que devuelven `String` |

> El renderizador CSV reutilizable (`CsvRenderer`, separador `;`, BOM, CRLF) lo
> aporta `:reports` (`10-informes.md` §3). Si `:ui` depende de `:reports` —como
> propone ese documento— los dosRenderizadores se unifican y esta tabla es
> consistente byte a byte con los informes.

### 9.2 `:ui` — paquete `kitoru.ui.teacher.analytics`

| Archivo | Composables | Qué hace |
|---|---|---|
| `AnalyticsPane.kt` | `AnalyticsPane(state, onEvent)` | Contenedor: filtros, KPIs, gráficos, tabla, tarjeta de inactivos |
| `AnalyticsFilterBar.kt` | `AnalyticsFilterBar(filter, onFilter)` | Período, alumno, modelo, "sólo con alertas", búsqueda |
| `KpiRow.kt` | `KpiRow(kpis)`, `StatTile(label, value, subtitle, tone, alert)` | Los seis KPIs (§2) |
| `StudentAnalyticsTable.kt` | `StudentAnalyticsTable(rows, sort, onSort, onOpen, onAction)` | La tabla de §4 (escritorio) |
| `StudentAnalyticsList.kt` | `StudentAnalyticsList(rows, onOpen)` | La versión tarjeta (Android) |
| `StudentAnalyticsDetail.kt` | `StudentAnalyticsDetail(behavior, quality, visibility, onOpenThread, onOpenMaterial)` | El detalle de un alumno (lateral en desktop, hoja en Android) |
| `InactiveStudentsCard.kt` | `InactiveStudentsCard(ids, labels, onRemind, onOpen)` | Los que no fraternaron |
| `AnalyticsExportDialog.kt` | `AnalyticsExportDialog(onExportCsv, onExportJson)` | Los dos botones de exportación + el resumen de qué se va a incluir |
| `AnalyticsEmptyState.kt` | `AnalyticsEmptyState(reason, action)` | Sin datos / analítica desactivada |
| `charts/ChartScales.kt` | — | `LinearScale`, `niceTicks`, `bucketize`, `barIndexAt`, `cellIndexAt` (**puras**) |
| `charts/ChartFrame.kt` | `ChartFrame(title, legend, content)`, `ChartTooltip`, `ChartLegend` | Ejes, grilla, tooltip, leyenda (comunes) |
| `charts/ActivityChart.kt` | `ActivityChart(days, selectedDay, onSelectDay)` | G1 |
| `charts/ActivityHeatmap.kt` | `ActivityHeatmap(students, days, onSelect)` | G2 |
| `charts/TopSourcesChart.kt` | `TopSourcesChart(sources, onSelect)` | G3 |
| `charts/QualityDistributionChart.kt` | `QualityDistributionChart(citations, studentRates)` | G4 (a) y (b) |
| `charts/ProblematicMaterialsChart.kt` | `ProblematicMaterialsChart(materials, onSelect)` | G5 |
| `charts/RepeatedQuestionsChart.kt` | `RepeatedQuestionsChart(students, onSelect)` | G6 |

### 9.3 `:reports` (reutilización, de `10-informes.md`)

| Pieza | Uso en este panel |
|---|---|
| `CsvRenderer` | Escribe los cuatro CSV con el formato estándar (separador `;`, BOM, CRLF) |
| `ExportFormat` / `SaveTarget` / `FileExporter` | El destino de la exportación |
| `TopicsAnalyzer` | **Complementario**, no redundante: agrupa *temas* cruzando alumnos ("de qué se pregunta la cátedra"); el detector de §5 agrupa *repeticiones del mismo alumno* ("quién no encontró lo que buscaba") |

> Conviene que los dos compartan `QuestionText` (`:core`), para que "un tema" y
> "una duda repetida" se calculen con la misma tokenización.

### 9.4 View-models

El estado vive en `TeacherViewModel` (según `01-navegacion.md` §5.2), que ya
tiene `var analytics: CourseAnalytics?`. Este panel **extiende** ese estado a un
objeto propio con filtros, orden y selección:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/analytics/AnalyticsUiState.kt

/** Período de análisis. */
enum class AnalyticsPeriod(val label: String, val days: Int?) {
    HOY("Hoy", 1),
    SIETE_DIAS("Últimos 7 días", 7),
    TREINTA_DIAS("Últimos 30 días", 30),
    CURSO("Todo el curso", null),
}

/** Columna por la que se ordena la tabla. */
enum class AnalyticsSort(val label: String) {
    NOMBRE("Alumno"),
    PREGUNTAS("Preguntas"),
    RESPUESTAS("Respuestas"),
    HILOS("Conversaciones"),
    DIAS_ACTIVOS("Días activos"),
    ULTIMA_ACTIVIDAD("Última actividad"),
    CITAS("Citas"),
    TASA_RESPALDO("Tasa de respaldo"),
    SIN_RESPALDO("Sin respaldo"),
    REPETIDAS("Repetidas"),
    LATENCIA("Latencia"),
    TOKENS("Tokens"),
}

/** Filtros del panel. */
data class AnalyticsFilter(
    val period: AnalyticsPeriod = AnalyticsPeriod.TREINTA_DIAS,
    val studentId: String? = null,
    val model: String? = null,
    val onlyAlerts: Boolean = false,
    val search: String = "",
)

data class AnalyticsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val course: Course? = null,
    val analytics: CourseAnalytics = CourseAnalytics(),
    val behavior: BehaviorOverview = BehaviorOverview(),
    val quality: QualityOverview = QualityOverview(),
    val filter: AnalyticsFilter = AnalyticsFilter(),
    val sort: AnalyticsSort = AnalyticsSort.PREGUNTAS,
    val sortAscending: Boolean = false,
    val selectedStudentId: String? = null,
    val selectedDay: Long? = null,
    val selectedMaterialId: String? = null,
    val exporting: Boolean = false,
    val feedback: String? = null,   // "Exportado: analitica-alumnos_…csv"
) {
    /** Las filas de la tabla, ya filtradas, ordenadas y con las etiquetas aplicadas. */
    val rows: List<StudentAnalyticsRow> get() = …
}

data class StudentAnalyticsRow(
    val userId: String,
    val label: String,          // nombre o alias
    val isRealName: Boolean,
    val visibility: ChatVisibility,
    val stats: StudentAnalytics,
    val behavior: StudentBehavior?,
    val alerts: List<String>,   // "Sin respaldo alto", "Repeticiones altas", …
)

class TeacherAnalyticsViewModel(
    private val courseId: String,
    private val enrollmentRepository: EnrollmentRepository,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val materialRepository: MaterialRepository,
    private val fileExporter: FileExporter,      // de `10-informes.md`
) {
    val state: StateFlow<AnalyticsUiState>

    /** Recalcula todo (los tres analizadores son funciones puras). */
    fun refresh()

    fun setPeriod(period: AnalyticsPeriod)
    fun setStudent(userId: String?)
    fun setModel(model: String?)
    fun setOnlyAlerts(only: Boolean)
    fun setSearch(text: String)
    fun sortBy(column: AnalyticsSort)
    fun selectStudent(userId: String?)
    fun selectDay(day: Long?)
    fun selectMaterial(materialId: String?)

    /** Exporta los CSV (§8.2). */
    suspend fun exportCsv(): Result<List<String>>

    /** Exporta el JSON (§8.3). */
    suspend fun exportJson(): Result<String>

    /** Cambia la visibilidad de un alumno (delega en `08-alumnos.md`). */
    suspend fun changeVisibility(userId: String, visibility: ChatVisibility): Result<Unit>
}
```

### 9.5 Delta con `01-navegacion.md`

| Lo que dice 01-navegacion | Lo que dice este documento |
|---|---|
| `AnalyticsPane` con `KpiRow`, `ActivityChart`, `SourceUsageList`, `StudentAnalyticsTable`, `InactiveStudentsCard`, `AnalyticsFilterBar` | **Se conserva la estructura** y se agregan: G2–G6, `StudentAnalyticsDetail`, `AnalyticsExportDialog`, `AnalyticsEmptyState`, `charts/*` |
| `KpiRow → StatTile × 8` | **6 KPIs** (§2), con los criterios de sacrificio del §2.2 |
| "Filtros: rango de fechas, alumno, modelo usado" | Igual, más "**sólo con alertas**" y búsqueda por alias |
| `onExportCsv()` → `analytics-<código>-<fecha>.csv` en `KitoruPaths.dataDir` | **Cuatro CSV + un JSON**, vía el seam `FileExporter` de `10-informes.md`, en `KitoruPaths.exportsDir/<courseId>/` |
| "El `AnalyticsAnalyzer` ya excluye a los `NONE` — no hay que filtrar nada más en la UI" | **Hay que corregirlo**: además de excluir, hay que **redactar texto y pseudonimizar nombres** (§7) y arreglar la fuga de `topSources` (§1.4a) |
| `var analytics: CourseAnalytics?` en `TeacherViewModel` | `AnalyticsUiState` (estado + filtros + selección), expuesto desde `TeacherViewModel` |

---

## 10. Casos borde

| Caso | Qué muestra el panel |
|---|---|
| Cátedra sin ninguna inscripción | "Todavía no hay alumnos inscriptos." + el código de inscripción |
| Alumno sin actividad (`messages == 0`) | Fila en la tabla con 0 en todo y "nunca" en la última actividad; aparece en la tarjeta de inactivos con su **alias** si es `METRICS_ONLY` |
| Alumno `NONE` | No aparece en ninguna tabla, ni en los totales, ni en el heatmap. Sólo en el **número** de alumnos ocultos |
| Alumno `METRICS_ONLY` | Alias en todas las tablas; el detalle sin texto; "Ver sus chats" deshabilitado; el KPI de repetidas puede ser "—" si no hay texto |
| Respuesta del asistente **sin `citation`** (modo general, sin RAG, o mensaje viejo) | No cuenta como "respuesta sin citas": es una respuesta **no verificada**. El panel distingue "no cita" de "no sé" |
| Material con dos archivos del mismo título | Se desambigua con `sourceId` (`09-calidad.md` §7.4); si no hay `sourceId` (datos viejos), el fallo va al cajón "material desconocido" |
| Material **nunca citado** | No aparece en el cruce de calidad (no hay exposición); aparece en G5 con 0 y en gris |
| Un alumno pregunta lo mismo 5 veces | Un grupo con 5 miembros: `repeatedQuestions = 4`, `repeatGroups = 1`. En la tabla del cruce, el material asociado suma **1** duda sin resolver (no 4): lo que cuenta es **la duda**, no sus repeticiones |
| La misma pregunta en dos hilos distintos del mismo alumno | Es una repetición (el detector no exige el mismo hilo) |
| La misma pregunta de dos alumnos distintos | **No** es una repetición: el detector es **por alumno**. (El agrupamiento de temas cruzando alumnos es el `TopicsAnalyzer` de `:reports`.) |
| Pregunta con menos de 2 palabras de contenido ("ok", "hola") | No se compara con nada (G1) |
| Pregunta con números contradictorios | No es repetición (G3) |
| La misma pregunta hace 40 días | No es repetición (G4: ventana de 14 días) |
| El texto de las preguntas no está disponible | `repeatedQuestions = null` → "—" en la UI, celda vacía en el CSV, `null` en el JSON. **Nunca 0** |
| `citation.total == 0` pero `sources` no vacío | Respuesta `SIN_CITAS`: recuperó material y no se apoyó en él → inútil |
| `citation == null` y `sources` vacío | Respuesta de memoria (p. ej. "hola") → **no** es inútil por sí sola |
| La respuesta llegó vacía (error de gateway) | `RESPUESTA_VACIA` → cuenta como inútil y como "sin responder" |
| `course.analyticsEnabled == false` | `EmptyState` con el motivo; ni un solo número (§2.3) |
| Un alumno visible pero sin `daily` (mensajes viejos) | No aparece en el heatmap; los KPIs de días siguen funcionando |
| Cambio de material (se edita o reindexa) | Las respuestas viejas **no** se recalculan: el veredicto histórico es el que se dio. El panel muestra la fecha del último fallo para entender que el material ya cambió |
| Un día con 0 mensajes (hueco en el `perDay`) | El eje temporal **interpola** los días sin actividad con 0 (no hay que rellenar el `perDay`): el `ChartScales` genera la grilla completa del rango |
| La cátedra tiene 300 alumnos | El heatmap muestra los 25 primeros + "Otros"; la tabla es un `LazyColumn` virtualizado; los cálculos siguen siendo lineales |

---

## 11. Criterios de aceptación

**Lógica (`:core`, testeable)**

1. `AnalyticsAnalyzer.analyze` es **determinista**: dos llamadas con los mismos
   datos dan el mismo `CourseAnalytics` (test "golden").
2. `topSources` **no** incluye ninguna fuente de un alumno con
   `visibility == NONE` (regresión del bug de §1.4a).
3. `inactiveStudentIds` contiene ids, **no** nombres.
4. `participationRate = activeStudents / students`, con `students` = visibles
   (nunca `enrolledStudents`).
5. `participationRate` no cambia al cambiar la visibilidad de un alumno de
   `FULL` a `METRICS_ONLY` (ambos son visibles) y **sí** cambia al pasarlo a
   `NONE` (deja de contar).
6. `BehaviorAnalyzer` es determinista y devuelve el mismo resultado con las
   preguntas en distinto orden de entrada (ordena por `createdAt`, luego por id).
7. La suma `grounded + problematic + uncited == answers` (de `09-calidad.md`).
8. `repeatedQuestions` es `null` (no 0) cuando no hay texto disponible.
9. Las guardas G1–G5 se respetan: con 2 tokens de contenido y 1 token compartido,
   dos preguntas **nunca** son reiteradas, aunque la similitud sea 1.
10. Preguntas con números contradictorios nunca son reiteradas.
11. `AnalyticsAlias.forUser` es **estable** (misma entrada → mismo alias) y
    **distinto** para dos alumnos distintos de la misma cátedra.
12. `AnalyticsLabels.forEnrollment` devuelve el nombre real con `FULL` y el alias
    con `METRICS_ONLY`.
13. La fila de totales de la tabla **reconcilia** con los KPIs de cabecera
    (mismo `totalMessages`, mismo `citationSupportRate`).
14. `material.unsupportedAnswerRate` coincide con el cálculo manual sobre los
    mensajes de ejemplo; `isSuspicious` es `false` con menos de 5 respuestas.

**Serialización / exportación**

15. Un `messages.json` viejo (sin `checks`, `reasons`, `sourceIds`) se lee sin
    excepción y produce los mismos agregados que antes.
16. Los CSV se escriben con BOM UTF-8, CRLF, separador `;` y encabezados
    `snake_case` sin acentos.
17. Un CSV de alumnos con un alumno `METRICS_ONLY` **no contiene su nombre** en
    ninguna columna (test que busca el `displayName` en el archivo).
18. El JSON lleva `schemaVersion` y serializa los `null` como `null`.
19. La celda de un dato no determinable queda **vacía**, no `0`.

**UI**

20. Los seis KPIs muestran los valores del §2.1 con los subtítulos indicados.
21. Con menos de 20 respuestas, los KPIs con umbral muestran el chip "pocos
    datos".
22. G1, G2, G3, G5 y G6 tienen `contentDescription` con un resumen textual y una
    tabla gemela.
23. Click en un día de G1 filtra la tabla por ese día y aparece el chip de filtro.
24. Click en una barra de G3 abre el panel de calidad filtrado por ese material.
25. En la tabla, un alumno `METRICS_ONLY` muestra su alias, el chip "Sólo
    métricas" y la acción "Ver sus chats" **deshabilitada** con motivo.
26. Con `analyticsEnabled == false`, la sección no muestra **ningún** número.
27. Exportar produce los cuatro CSV y el JSON, con los nombres del §8.4, y la
    acción queda registrada en el `AccessLog` con `kind = EXPORT`.

---

## 12. Riesgos y límites

| Riesgo | Mitigación |
|---|---|
| **El detector de repetición marca falsos positivos** ("¿el parcial?" y "¿el parcial de julio?"). | Guardas G1–G5, umbral 0,80, mezcla coseno+contención, y la ventana de 14 días. La UI **muestra el texto de las dos preguntas** para que el docente juzgue: es una pista, no un veredicto |
| **El panel se use para vigilar alumnos** | La vista principal es por **material**, no por persona; la tabla por alumno es un desglose; los alumnos `NONE` no existen; los `METRICS_ONLY` son un alias. El texto nunca se ve sin permiso |
| **Que el docente lea "87 % de citas respaldadas" como "87 % de aciertos"** | El copy dice siempre "citas que el material respalda" y el subtítulo aclara que es una comparación de textos, no una evaluación del modelo |
| **Que el threshold de 0,25 sobre materiales persiga un PDF con 1 caso** | `MIN_MUESTRAS_MATERIAL = 5`; con menos, la tasa sale en gris con "pocos datos, no concluyente" |
| **Que el IDF con pocas preguntas sea ruido** | El IDF se calcula sobre **todas** las preguntas de la cátedra, no sobre las del alumno; y el grupo repetido se puede revisar a mano |
| **Que las respuestas "de memoria" se confundan con "respuestas malas"** | Son métricas distintas (`uncitedAnswerRate` vs `ungroundedAnswerRate`) y sólo una cuenta como "inútil" cuando hubo material recuperado |
| **Que los gráficos no se puedan leer en un teléfono** | En < 600 dp la tabla se reemplaza por tarjetas; los gráficos tienen tooltip, tabla gemela y números escritos al lado de cada barra |
| **Que el `CourseAnalytics` cacheado lleve texto** | El texto vive en `BehaviorOverview`, que **no se persiste**; si algún día se cachea, se cachea la versión sin texto |
| **Crecimiento del costo de cómputo** | Los tres analizadores son lineales sobre el número de mensajes; el detector acota las comparaciones por pregunta (200 candidatas). Con 10.000 mensajes, el cálculo sigue en el orden de milisegundos |
| **Que la privacidad dependa de que nadie olvide un `Text(...)`** | La redacción ocurre **en el analizador**, no en la UI: el dato llega ya redactado. Un `Text()` olvidado no puede filtrar texto que no recibió |
