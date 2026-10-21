# 07 — Analítica temporal y por cohortes

> Subagente: diseño de las **vistas temporales** de la analítica del docente:
> la curva de uso a lo largo de la cursada, la **retención**, las **cohortes**
> (inscripción / modo de uso / frecuencia), los **patrones horarios** y las
> **series de latencia y calidad en el tiempo**.
> Módulos tocados: `:core` (dominio + analítica pura), `:ui` (Compose
> Multiplatform, todavía no construido), `:desktop`/`:android` (cero cambios).
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable
> por defecto*, *determinista*, *fail-fast con mensajes en español*, *el
> estudiante conserva agencia*, *el docente no ve lo que no debe*.

---

## 0. Decisiones en una línea

| Decisión | Respuesta | Motivo |
|---|---|---|
| Dónde vive el cálculo | Un analizador **nuevo y puro** `TemporalAnalyzer` en `:core`; `AnalyticsAnalyzer` no se reescribe | `AnalyticsAnalyzer` ya está construido y testeado; el temporal necesita otra granularidad (semanas, cohortes, horas) y otro anclaje (fecha de inicio de la cursada). |
| Zona horaria | **La cátedra tiene zona horaria** (`CourseSchedule.timeZoneOffsetMinutes`) y cada mensaje guarda el offset de su autor (`ChatMessage.tzOffsetMinutes`) | Hoy `dayStart()` es **medianoche UTC** y el comentario de `DayActivity` dice "medianoche local": mienten entre sí. En Argentina (UTC−3) un mensaje de las 21:00 cae en el día siguiente. Para un heatmap "hora × día" eso es un bug, no un detalle. |
| Ancla de las semanas | **Lunes de la semana** en que arranca la cursada; `weekIndex` 0-based, la UI muestra "S1", "S2", … | Todas las cohortes comparten la misma grilla y "semana 8" es un número que el docente puede decir en voz alta. |
| Qué es "activo" | El alumno **escribió al menos un mensaje** en el bucket (una respuesta del modelo siempre viene de una pregunta) | Los mensajes del asistente no cuentan como actividad: una respuesta sin pregunta previa es ruido. |
| Retención | **Cohorte de la semana 1** (los activos en la primera semana de la cursada) y % de esa cohorte todavía activo en la semana W | Es la métrica de salud de la herramienta y es comparable entre cursadas. |
| Cohortes | Tres dimensiones **independientes y excluyentes**: `SIGNUP_WEEK`, `MODEL_MODE`, `USAGE_TIER` | Cada una responde una pregunta distinta del docente. |
| Ejes de la tabla de cohortes | `SIGNUP_WEEK` → eje **relativo** (semana desde la inscripción). `MODEL_MODE` y `USAGE_TIER` → eje **absoluto** (semana de la cursada) | Una cohorte de "IA local" no tiene fecha de arranque propia: compararla por "semana 0" no significaría nada. |
| Mínimo de cohorte | 5 personas; por debajo, la celda se muestra como "—" | Es la misma lógica de privacidad que ya usa el resto de la analítica: un porcentaje sobre 2 personas no es un dato, es el dato de una persona. |
| ¿La IA local rinde más? | Se **ofrece la comparación** con n, retención, volumen y calidad por modo, y **se documentan los sesgos** (selección, visibilidad, hardware, modelo) | La pregunta es interesante y contestable con datos. Lo que no se puede es vender el resultado como causal. |
| Hitos del calendario | `ScheduleEntry` **lo marca el docente** a mano; no hay feriados precargados ni importación `.ics` en el hito 1 | Una tabla de feriados mal cargada es peor que ninguna: el docente marca 12 fechas por cuatrimestre y listo. |
| Determinismo | Todo en `commonMain`, funciones puras, `now` como **parámetro**, porcentajes en **puntos básicos** (`Int`) | Regla 3 del proyecto. Sin `now()` adentro, sin `Double` en las comparaciones, sin iterar mapas sin ordenar. |

---

## 1. Las preguntas que responde esta vista

La analítica "de resumen" que ya existe (`CourseAnalytics`) responde *cuánto* y
*quién*. Esta cubre el *cuándo* y el *quién en qué momento*:

| # | Pregunta del docente | Vista | § |
|---|---|---|---|
| P1 | ¿Cómo se usa la herramienta a lo largo de la cursada? ¿Hay picos antes de los exámenes? | Curva diaria/semanal con el calendario encima | §4 |
| P2 | ¿Los alumnos que chokan en la semana 1 siguen usando la herramienta en la semana 8? | Curva de retención | §5 |
| P3 | ¿Los que se anotaron temprano se quedan, o el grupo se descrema? | Cohortes por fecha de inscripción | §6.2.1 |
| P4 | ¿La IA local (en el dispositivo del alumno) rinde igual que la alojada? | Cohortes por modo de uso | §6.2.2 |
| P5 | ¿Hay un núcleo asiduo y una periferia esporádica? ¿Cómo se usan? | Cohortes por frecuencia | §6.2.3 |
| P6 | ¿La gente estudia de noche? ¿El fin de semana? | Heatmap hora × día de la semana | §7 |
| P7 | ¿La tasa de citas respaldadas sube o baja conforme avanza la cursada? ¿Y la latencia? | Series de calidad y latencia | §8 |
| P8 | ¿Cómo sabe la app cuándo es un parcial? | `ScheduleEntry` | §9 |

Cada vista es **una pestaña** de `Docente → Cátedra → Analítica`:

```
Analítica de la cátedra
 [Resumen] [Actividad] [Retención] [Cohortes] [Horarios] [Calidad] [Calendario]
             §4         §5        §6         §7        §8       §9
```

---

## 2. Corrección de base: el día local

### 2.1 El problema que ya existe

`AnalyticsAnalyzer.dayStart()` (`core/src/commonMain/kotlin/kitoru/domain/AnalyticsAnalyzer.kt`, líneas 184-187):

```kotlin
fun dayStart(millis: Long): Long {
    val dayMillis = 86_400_000L
    return ((millis / dayMillis) + if (millis % dayMillis < 0) -1 else 0) * dayMillis
}
```

Eso es **medianoche UTC**. Pero `DayActivity` está documentado como *"Día en
epoch millis (medianoche local)"* y `CourseAnalytics.perDay` lo expone al
docente. Los dos contratos no pueden ser ciertos a la vez.

El impacto no es cosmético:

- En Argentina (UTC−3), un mensaje del **lunes 21:30** cae en el bucket del
  **martes** en el grouping actual.
- Un heatmap "hora × día" calculado con UTC muestra el pico de la noche en
  la franja de la mañana: el docente concluiría "estudian a las 6 de la
  mañana", que es exactamente la conclusión opuesta a la realidad.
- La "semana" de un alumno puede arrancar el domingo a la noche, partiendo
  los lunes al medio.

**Decisión: la analítica temporal trabaja en la zona horaria de la
cátedra** (`CourseSchedule.timeZoneOffsetMinutes`), y **el heatmap trabaja en
la zona horaria del autor del mensaje** (`ChatMessage.tzOffsetMinutes`),
porque de lo que se trata ahí es del hábito de la persona, no del calendario
institucional.

El offset es un entero de **minutos** con signo (Argentina: `-180`). Fijo, no
DST: la DST complica la reproducibilidad, y en Argentina el calendario
académico no cambia; en otros países se resuelve con un offset por cursada
que el docente puede corregir a mano.

### 2.2 `CalendarMath`: la aritmética de fechas, en `commonMain`

Archivo nuevo: `core/src/commonMain/kotlin/kitoru/domain/CalendarMath.kt`

```kotlin
package kitoru.domain

/**
 * Aritmética de calendario, **sin java.time** (minSdk = 24).
 *
 * Todas las funciones son puras y enteras: el mismo input da el mismo output
 * en cualquier plataforma, en cualquier momento. Eso es lo que hace que la
 * analítica sea testeable y que dos docentes que miran la misma cátedra vean
 * los mismos números.
 */
object CalendarMath {

    const val DAY_MS: Long = 86_400_000L
    const val WEEK_MS: Long = 7 * DAY_MS
    const val MINUTE_MS: Long = 60_000L

    /** Medianoche local del día que contiene a [millis], con [offsetMinutes] de la zona. */
    fun localDayStart(millis: Long, offsetMinutes: Int): Long {
        val day = floorDiv(millis + offsetMinutes * MINUTE_MS, DAY_MS)
        return day * DAY_MS - offsetMinutes * MINUTE_MS
    }

    /** Día de la semana local: 0 = lunes … 6 = domingo. */
    fun localDayOfWeek(millis: Long, offsetMinutes: Int): Int {
        val epochDay = floorDiv(millis + offsetMinutes * MINUTE_MS, DAY_MS)
        // El epoch (1970-01-01) fue un jueves: con lunes = 0, el jueves es 3.
        return ((epochDay + 3) % 7 + 7) % 7
    }

    /** Lunes de la semana que contiene a [dayStart] (medianoche local). */
    fun weekStart(dayStart: Long): Long {
        val dow = localDayOfWeek(dayStart, 0)
        return dayStart - dow * DAY_MS
    }

    /**
     * Semana de la cursada (0-based) del día [dayStart].
     *
     * La grilla arranca en el **lunes de la semana en que empieza la cursada**
     * ([termWeekStart]), así todas las semanas de todas las cohortes caen en
     * la misma grilla. La semana 0 puede estar incompleta (si la cursada
     * arranca un miércoles, la semana 0 tiene 3 días).
     */
    fun weekIndex(dayStart: Long, termWeekStart: Long): Int =
        floorDiv(dayStart - termWeekStart, WEEK_MS).toInt()

    /** Hora local (0..23) del instante, en la zona del autor. */
    fun hourOfDay(millis: Long, offsetMinutes: Int): Int =
        (((millis + offsetMinutes * MINUTE_MS) % DAY_MS + DAY_MS) % DAY_MS / (60 * MINUTE_MS)).toInt()

    /** División entera que redondea hacia −∞ (el `%` de Kotlin trunca). */
    fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
    }
}
```

Chequeo mental de `localDayOfWeek(0, 0)`: `epochDay = 0` → `(0 + 3) % 7 = 3` →
jueves ✓ (el 1 de enero de 1970 fue jueves).

### 2.3 `ChatMessage.tzOffsetMinutes`: la zona de cada autor

```kotlin
@Serializable
data class ChatMessage(
    // … los campos actuales, sin cambios …
    /**
     * Offset UTC (minutos con signo) **del autor en el momento de escribir**.
     *
     * El heatmap "hora × día" usa la zona del autor: lo que se quiere medir
     * es el hábito de la persona, no el del calendario de la institución.
     * 0 = sin dato (mensajes viejos): se usa el offset de la cátedra.
     */
    val tzOffsetMinutes: Int = 0,
)
```

El campo tiene **default** → los JSON ya guardados siguen deserializando
(`KitoruJson` tiene `ignoreUnknownKeys = true` y los defaults se aplican).

Quién lo escribe: el cliente, al crear el mensaje, con el offset de plataforma
(`expect fun localUtcOffsetMinutes(): Int` en `kitoru.platform`; en JVM sale de
`TimeZone.getDefault().getOffset(now)` y en Android de `java.util.TimeZone`).
Si el valor es 0, se usa `course.schedule.timeZoneOffsetMinutes`.

### 2.4 `CourseSchedule`: el ancla temporal de la cátedra

```kotlin
@Serializable
data class CourseSchedule(
    /**
     * Inicio de la cursada: **medianoche local del primer día** (epoch millis).
     * 0 = todavía no se definió.
     */
    val termStart: Long = 0L,
    /** Zona horaria de la cátedra, en minutos con signo. Argentina: −180. */
    val timeZoneOffsetMinutes: Int = 0,
    /** Semanas de cursada declaradas (16 por defecto en la mayoría de las materias). */
    val termWeeks: Int = 16,
    /** Etiqueta legible de la cursada ("1er cuatrimestre"). */
    val termLabel: String = "",
) {
    /** Lunes de la semana 0 (ancla de la grilla de semanas). 0 si no hay cursada. */
    val termWeekStart: Long
        get() = if (termStart == 0L) 0L
        else CalendarMath.weekStart(
            CalendarMath.localDayStart(termStart, timeZoneOffsetMinutes)
        )

    /** ¿La cursada ya tiene inicio definido? */
    val isDefined: Boolean get() = termStart > 0L
}
```

Y en `Course` (extensión con default → los JSON viejos siguen ok):

```kotlin
@Serializable
data class Course(
    // … los campos actuales …
    /** Calendario académico de la cátedra: inicio, zona horaria, hitos. */
    val schedule: CourseSchedule = CourseSchedule(),
)
```

`Course.term` (el texto `"2026-1"`) **no se usa como fecha**: es una etiqueta.
El docente además pone la fecha de arranque en la pantalla de calendario; si
no la pone, la analítica temporal usa un ancla derivada:

> **Fallback de ancla**: si `termStart == 0`, el ancla es el **lunes de la
> semana del primer mensaje de la cátedra** (o el lunes de la semana del
> `createdAt` de la cursada si no hay mensajes). El eje queda alineado igual y
> la UI avisa: *"El inicio de la cursada no está definido: se tomó como
> referencia el 12/04/2026."* Es determinista (el primer mensaje no depende
> del reloj) y evita que la vista quede vacía.

### 2.5 `AnalyticsAnalyzer`: qué se toca (y qué no)

`AnalyticsAnalyzer.analyze` gana **un parámetro opcional**:

```kotlin
fun analyze(
    course: Course,
    enrollments: List<Enrollment>,
    threads: List<ChatThread>,
    messages: List<ChatMessage>,
    /** Zona horaria de la cátedra. Por defecto, la que declara el curso. */
    timeZoneOffsetMinutes: Int = course.schedule.timeZoneOffsetMinutes,
): CourseAnalytics
```

y `dayStart` gana una sobrecarga:

```kotlin
/** Medianoche (UTC) del día de un instante. Se conserva por compatibilidad. */
fun dayStart(millis: Long): Long = dayStart(millis, 0)

/** Medianoche local del día, con el offset de la zona. */
fun dayStart(millis: Long, offsetMinutes: Int): Long =
    CalendarMath.localDayStart(millis, offsetMinutes)
```

Los callers existentes siguen compilando (el parámetro tiene default) y el
comportamiento no cambia **mientras `course.schedule.timeZoneOffsetMinutes`
sea 0**, que es el caso de todo curso guardado hoy. Cuando el docente defina
la zona, la pantalla "Resumen" y la pestaña "Actividad" muestran **los
mismos** números: no hay dos verdades.

El comentario de `DayActivity.day` se corrige a *"Día en epoch millis
(medianoche en la zona de la cátedra)"*.

---

## 3. Los tipos de salida

Archivo nuevo: `core/src/commonMain/kotlin/kitoru/domain/TemporalAnalytics.kt`

> **Nota de portabilidad**: `@JvmInline` es una anotación de JVM y **no**
> existe en `commonMain`. Las "value class" se implementan como `data class`
> de un solo campo o directamente como `Int` con nombre explícito
> (`retentionBp`). En este documento se usa `Int`: sin wrappers, sin
> boxing raro, y los tests comparan enteros.

```kotlin
package kitoru.domain

/** Actividad de un día, en la zona de la cátedra. */
data class DayBucket(
    /** Medianoche local del día (epoch millis). */
    val day: Long,
    /** Semana de la cursada (0-based). */
    val weekIndex: Int,
    /** Día de la semana local: 0 = lunes. */
    val weekday: Int,
    val messages: Int,
    val replies: Int,
    /** Alumnos distintos que escribieron ese día. */
    val activeStudents: Int,
    /** Alumnos que hacen su **primer** uso ese día. */
    val newStudents: Int,
)

/** Actividad agregada de una semana de la cursada. */
data class WeekBucket(
    val weekIndex: Int,
    /** Lunes de la semana (medianoche local). */
    val startDay: Long,
    val messages: Int,
    val replies: Int,
    val activeStudents: Int,
    val newStudents: Int,
    /** Latencia media de las respuestas de la semana (ms). */
    val avgLatencyMs: Long,
    val citationsVerified: Int,
    val citationsUnsupported: Int,
    val citationsUnknown: Int,
    /** Respuestas con al menos una cita sin respaldo o inexistente. */
    val answersWithUnsupported: Int,
    /** Respuestas que tengan cita (denominador del % de respuestas sin respaldo). */
    val repliesWithCitation: Int,
) {
    /** % de citas respaldadas, en puntos básicos. 0 si no hay citas. */
    val supportRateBp: Int
        get() = if (citationsTotal == 0) 0 else citationsVerified * 10_000 / citationsTotal
    /** % de respuestas sin respaldo, en puntos básicos. */
    val ungroundedRateBp: Int
        get() = if (repliesWithCitation == 0) 0
        else answersWithUnsupported * 10_000 / repliesWithCitation
}

/** Punto de la curva de retención (cohorte base = semana 1). */
data class RetentionPoint(
    val weekIndex: Int,
    /** Alumnos activos esa semana (cualquier cohorte). */
    val activeStudents: Int,
    /** Alumnos de la cohorte base (semana 1) que siguen activos. */
    val retainedStudents: Int,
    /** % retenido respecto de la cohorte base, en puntos básicos. */
    val retentionBp: Int,
    /** Alumnos que vuelven (ya habían usado la herramienta y vuelven). */
    val returningStudents: Int,
    /** Alumnos que suman su primer uso. */
    val newStudents: Int,
    /** Alumnos que la semana anterior estaban y ésta no (churn semanal). */
    val churnedStudents: Int,
)

/** Dimensión de cohorte. */
enum class CohortDimension(val label: String) {
    SIGNUP_WEEK("Semana de inscripción"),
    MODEL_MODE("Modo de uso"),
    USAGE_TIER("Frecuencia de uso"),
    ;

    companion object {
        fun fromName(name: String): CohortDimension? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** Eje de lectura de la tabla de cohortes. */
enum class CohortAxis(val label: String) {
    /** Columna = semana desde el arranque de la cohorte. */
    RELATIVE("Semana desde la inscripción"),
    /** Columna = semana de la cursada. */
    ABSOLUTE("Semana de la cursada"),
}

/** Una celda de la tabla de cohortes. */
data class CohortCell(
    /** Índice de columna según el eje de la cohorte. */
    val column: Int,
    /** Alumnos de la cohorte activos en esa columna. */
    val activeStudents: Int,
    /** % de la cohorte activa, en puntos básicos. null si no aplica o si n < mínimo. */
    val activeBp: Int?,
    /** Mensajes por alumno activo en esa columna. */
    val messagesPerActive: Int,
)

/** Una cohorte. */
data class Cohort(
    val dimension: CohortDimension,
    /** Clave estable ("2026-04-20", "REMOTE", "HABITUAL"). */
    val key: String,
    /** Etiqueta legible para el docente. */
    val label: String,
    val axis: CohortAxis,
    /** Tamaño de la cohorte (alumnos). */
    val size: Int,
    /** Columnas de la matriz. */
    val cells: List<CohortCell>,
    /** Semana de la cursada que es la columna 0. */
    val anchorWeek: Int,
)

/** Celda del heatmap hora × día de la semana. */
data class HourCell(
    /** 0 = lunes … 6 = domingo. */
    val weekday: Int,
    /** 0..23, en la zona del autor del mensaje. */
    val hour: Int,
    val messages: Int,
    val replies: Int,
    /** Alumnos distintos que escribieron en esa franja. */
    val students: Int,
)

/** Punto de la serie de calidad por semana. */
data class QualityPoint(
    val weekIndex: Int,
    /** % de citas respaldadas (puntos básicos). null si esa semana no tuvo citas. */
    val supportRateBp: Int?,
    /** % de respuestas sin respaldo (puntos básicos). */
    val ungroundedRateBp: Int?,
    /** Latencia media de las respuestas (ms). */
    val avgLatencyMs: Long,
    /** Respuestas de la semana (muestra). */
    val replies: Int,
    /** Pendiente de la recta de % de citas por semana (puntos básicos por semana). */
    val supportSlopeBpPerWeek: Int?,
)

/** Comparación por modo de uso (IA alojada vs IA local). */
data class ModeComparison(
    val mode: ModelMode,
    val students: Int,
    val messages: Int,
    val replies: Int,
    val activeStudents: Int,
    /** Mensajes por alumno activo (evita el sesgo de "hay más alumnos"). */
    val messagesPerActiveStudent: Int,
    /** Retención de la semana 1 a la semana 8 (puntos básicos). null si no hay base. */
    val retentionWeek1To8Bp: Int?,
    /** % de citas respaldadas del modo (puntos básicos). */
    val supportRateBp: Int?,
    /** Latencia media (ms). */
    val avgLatencyMs: Long,
)

/** Resumen completo de la analítica temporal. */
data class TemporalAnalytics(
    val courseId: String,
    /** Instante de referencia (el "ahora" que se le pasó al analizador). */
    val referenceNow: Long,
    val timeZoneOffsetMinutes: Int,
    /** Ancla de la cursada (medianoche local del lunes de la semana 0). */
    val termWeekStart: Long,
    /** Semanas de cursada declaradas. */
    val termWeeks: Int,
    /** Serie diaria, densa (sin huecos). */
    val daily: List<DayBucket>,
    /** Serie semanal. */
    val weekly: List<WeekBucket>,
    /** Curva de retención (cohorte base = semana 1). */
    val retention: List<RetentionPoint>,
    /** Cohortes (las tres dimensiones, en ese orden). */
    val cohorts: List<Cohort>,
    /** Heatmap 7 × 24 (168 celdas, ordenadas lunes→domingo, hora 0→23). */
    val heatmap: List<HourCell>,
    /** Serie de calidad y latencia. */
    val quality: List<QualityPoint>,
    /** Comparación IA alojada vs IA local. */
    val modes: List<ModeComparison>,
    /** Alumnos visibles (inscritos con `visibility.showsMetrics`). */
    val visibleStudents: Int,
    /** Alumnos que escribieron al menos un mensaje. */
    val activeStudents: Int,
    /** Mensajes considerados (para auditar la cobertura). */
    val messagesConsidered: Int,
    /** Hitos del calendario que caen dentro del rango analizado. */
    val entries: List<ScheduleEntry>,
)
```

---

## 4. La curva de uso a lo largo de la cursada

### 4.1 Algoritmo

`TemporalAnalyzer.buildDaily(...)`:

```
Entrada: curso, inscripciones, hilos, mensajes, offset, ancla (termWeekStart),
         today (el "now" del análisis, ya localizado al día local)

1. Índices de referencia (O(1) por lookup):
     threadsById     : id de hilo → ChatThread
     enrollmentByUser: id de usuario → Enrollment

2. Un solo recorrido de los mensajes:
     for m in messages:
        t = threadsById[m.threadId] ?: continue
        e = enrollmentByUser[t.authorId] ?: continue
        if (!e.visibility.showsMetrics) continue     ← la regla de visibilidad
        if (e.role != CourseRole.ALUMNO) continue    ← la curva es de alumnos
        day = CalendarMath.localDayStart(m.createdAt, offset)
        bucket = byDay.getOrPut(day) { Bucket() }
        if (m.isUser) bucket.messages++ else bucket.replies++
        bucket.students += t.authorId                (Set<String>)
        firstUserMessage[t.authorId] = min(...)

3. Densificación: rango = [min(primer uso, termStart) … max(último uso, today)]
   local, en días. Se generan TODOS los días del rango, aunque valgan 0.
   Tope duro: MAX_DAYS = 366; si el rango es mayor, se toman los últimos
   366 días y se deja constancia (flag) para que la UI avise.

4. newStudents: cada alumno con primer mensaje **de usuario** suma 1 en el
   day bucket de ese día. Los alumnos sin ningún mensaje de usuario
   (inscritos que nunca preguntaron) no son activos en ninguna semana;
   aparecen en `inactiveStudents` (ya existe en `CourseAnalytics`).

5. weekIndex de cada día = CalendarMath.weekIndex(day, termWeekStart)

6. weekly = días agrupados por weekIndex, sumando:
       messages, replies, activeStudents (unión de los sets de alumnos),
       newStudents, latencia media (Σ latencyMs de las respuestas / respuestas),
       citas: verified / unsupported / unknown (Σ de los ChatMessage.citation),
       answersWithUnsupported (respuestas con unsupported>0 o unknown>0),
       repliesWithCitation (respuestas con citation != null).
```

**Nota de performance**: el paso 2 es **una sola pasada** (O(M)). El
`AnalyticsAnalyzer` actual hace `messages.filter { it.threadId in userThreadIds }`
*dentro* del loop por alumno → O(alumnos × mensajes). Con 200 alumnos y
50 000 mensajes son 10 millones de comparaciones. `TemporalAnalyzer` no repite
ese error; **corregir `AnalyticsAnalyzer` queda como tarea aparte** (§11.3),
porque tocar un analizador ya en producción conceptual es otro cambio.

### 4.2 Qué se ve

La pestaña **Actividad** muestra una sola gráfica grande (la "curva de la
cursada") con tres modos de lectura:

| Modo | Qué grafica | Para qué |
|---|---|---|
| **Diario** (default) | Columnas apiladas por día: mensajes (abajo) + respuestas (arriba) | Ver el ritmo real: días de clase, findes, vallas |
| **Semanal** | Columna por semana de la cursada | Comparar semanas enteras (menos ruido) |
| **Por alumno activo** | `mensajes / alumnos activos` por día | Quitar el efecto "creció la matrícula" |

Encima de la gráfica, cuatro tarjetas:

```
┌──────────────┬──────────────┬──────────────┬──────────────┐
│  1.284       │  412         │  27          │  61 %        │
│  mensajes    │  respuestas  │  alumnos     │  retención   │
│  en la cursada│ en la cursada│  activos/día │  S1 → S8     │
│              │              │  (máx. 34)   │  (25 de 41)  │
└──────────────┴──────────────┴──────────────┴──────────────┘
```

Y dos **lecturas automáticas** (texto generado por código, nunca por un
modelo):

- *"Semana más intensa: S7 (12/05) con 312 mensajes."*
- *"La actividad cae un 34 % entre la semana 4 y la semana 8."* (delta de la
  media de mensajes de ambas ventanas).

### 4.3 Cómo se dibuja (Compose `Canvas`)

Archivo propuesto: `ui/src/commonMain/kotlin/kitoru/ui/teacher/analytics/UsageTimelineChart.kt`
(el módulo `:ui` todavía no existe; es donde van las pantallas).

```kotlin
@OptIn(ExperimentalTextApi::class)
@Composable
fun UsageTimelineChart(
    days: List<DayBucket>,
    entries: List<ScheduleEntry>,
    modifier: Modifier = Modifier,
    onSelectDay: (Long?) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.onSurfaceVariant)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .pointerInput(days) {
                detectTapGestures { off -> onSelectDay(dayAtOffset(days, off.x, leftPx)) }
            },
    ) {
        // --- geometría ---
        val left = 46.dp.toPx()      // margen del eje Y
        val right = 14.dp.toPx()
        val top = 18.dp.toPx()       // aire para los marcadores de parcial
        val bottom = 30.dp.toPx()    // margen del eje X
        val plotW = size.width - left - right
        val plotH = size.height - top - bottom
        if (plotW <= 0f || plotH <= 0f || days.isEmpty()) return@Canvas

        val band = plotW / days.size
        val maxY = niceCeil(days.maxOf { it.messages + it.replies }.coerceAtLeast(1))

        // --- rejilla horizontal + etiquetas ---
        for (i in 0..4) {
            val y = top + plotH * i / 4f
            drawLine(colors.outlineVariant, Offset(left, y), Offset(left + plotW, y), strokeWidth = 1f)
            drawText(measurer, "${maxY * (4 - i) / 4}",
                topLeft = Offset(0f, y - 8.dp.toPx()), style = labelStyle)
        }

        // --- bandas de fin de semana y de feriado (detrás de todo) ---
        days.forEachIndexed { i, d ->
            if (d.weekday >= 5) {
                drawRect(colors.surfaceVariant.copy(alpha = 0.35f),
                    topLeft = Offset(left + band * i, top), size = Size(band, plotH))
            }
        }
        entries.filter { it.kind == ScheduleKind.HOLIDAY }.forEach { e ->
            val from = days.indexOfFirst { it.day >= e.day }.coerceAtLeast(0)
            val to = days.indexOfLast { it.day <= (if (e.endDay > e.day) e.endDay else e.day) }
                .coerceAtLeast(from)
            drawRect(colors.surfaceVariant.copy(alpha = 0.6f),
                topLeft = Offset(left + band * from, top),
                size = Size(band * (to - from + 1), plotH))
        }

        // --- columnas apiladas ---
        days.forEachIndexed { i, d ->
            val x = left + band * i + band * 0.15f
            val w = band * 0.7f
            val hMsg = plotH * d.messages / maxY
            val hRep = plotH * d.replies / maxY
            drawRect(colors.primary, Offset(x, top + plotH - hMsg), Size(w, hMsg))
            drawRect(colors.primary.copy(alpha = 0.42f),
                Offset(x, top + plotH - hMsg - hRep), Size(w, hRep))
        }

        // --- marcadores del calendario (§9) ---
        val indexByDay = days.withIndex().associate { (i, d) -> d.day to i }
        entries.filter { it.kind == ScheduleKind.EXAM }.forEach { e ->
            val i = indexByDay[e.day] ?: return@forEach
            val x = left + band * (i + 0.5f)
            drawLine(colors.error, Offset(x, top), Offset(x, top + plotH),
                strokeWidth = 2.dp.toPx())
            drawCircle(colors.error, radius = 4.dp.toPx(), center = Offset(x, top))
        }
        entries.filter { it.kind == ScheduleKind.DEADLINE }.forEach { e ->
            val i = indexByDay[e.day] ?: return@forEach
            val x = left + band * (i + 0.5f)
            drawLine(colors.tertiary, Offset(x, top), Offset(x, top + plotH),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }

        // --- eje X: una fecha cada N días, para que no se pisen ---
        val step = max(1, days.size / 8)
        days.forEachIndexed { i, d ->
            if (i % step != 0) return@forEachIndexed
            drawText(measurer, formatDay(d.day),
                topLeft = Offset(left + band * i, top + plotH + 6.dp.toPx()), style = labelStyle)
        }
    }
}
```

**Reglas de dibujo (para que dos docentes vean lo mismo):**

- `niceCeil(v)`: redondeo entero a 1/2/5 × 10ⁿ. Implementación **sin
  flotantes**, en el mismo archivo:

  ```kotlin
  internal fun niceCeil(v: Int): Int {
      if (v <= 0) return 1
      var pow = 1
      while (pow.toLong() * 10 <= v) pow *= 10
      val base = pow / 10
      return when {
          v <= base -> base
          v <= base * 2 -> base * 2
          v <= base * 5 -> base * 5
          else -> pow
      }
  }
  ```

- El eje Y siempre arranca en 0 (nunca se recorta): con barras truncadas se
  miente.
- Si `band < 3.dp.toPx()` (más de ~120 días), las columnas se fusionan: en ese
  caso se dibuja una **línea** o se fuerza el modo semanal. Regla explícita,
  no un accidentito visual.
- El tooltip (click/tap) muestra: fecha, mensajes, respuestas, alumnos
  activos, alumnos nuevos y los hitos de ese día.

### 4.4 La superposición del calendario

El calendario **no es un fondo decorativo**: es información con jerarquía.

| Hito | Tratamiento en el gráfico | Razón |
|---|---|---|
| `EXAM` (parcial) | Línea vertical **roja** de 2 dp + círculo rojo arriba + etiqueta con el nombre, **escalonada** para que no se pisen | Es el evento que explica los picos |
| `HOLIDAY` (feriado) | Banda gris horizontal de toda la altura | "El martes no hubo clase" explica un valle |
| `CLASS` (clase) | Tick fino gris en el borde inferior + el horario en el tooltip | Ancla la rutina semanal |
| `DEADLINE` (entrega de TP) | Línea **punteada** en `tertiary` | Los picos de entrega son de otra naturaleza |
| `MATERIAL` (material nuevo) | Punto verde en el eje inferior | "¿el pico coincide con que subí el material?" |

**Escalonado de etiquetas** (determinista): las etiquetas de parcial se
colocan en filas; cada etiqueta nueva va a la primera fila cuyo último
extremo derecho sea menor que su extremo izquierdo; si no hay fila libre, se
agrega una. El ancho del texto se mide con `TextMeasurer`.

**Ventana pre-parcial**: para cada `EXAM`, se sombrea la franja `D−3 … D−1`
con `error.copy(alpha = 0.07f)` y una etiqueta chica "pre-parcial" arriba a la
izquierda. Con eso el docente ve el "sube antes del parcial" sin leer un
número.

### 4.5 Señal derivada: el "lift pre-parcial"

Métrica determinista que responde "¿el uso se concentra antes de los
exámenes?":

```
Para cada parcial E con día D:
    preSum  = Σ(mensajes + respuestas) de los días [D−3, D−1]      (3 días)
    baseSum = Σ(mensajes + respuestas) de los días [D−14, D−4]     (11 días)
    liftBp  = if (baseSum == 0) 0
              else preSum * 11 * 10_000 / (baseSum * 3)
```

- Los días **sin actividad cuentan como 0**: si no, el lift mide "días sin
  clase", no "uso".
- `liftBp > 10_000` → el uso **se duplica** en la ventana pre-parcial.
- Se muestra por parcial y un promedio ponderado por `preSum`.
- Si no hay parciales marcados: la tarjeta dice *"Marcá un parcial en la
  pestaña Calendario para ver el uso pre-parcial."*
- El mismo cálculo con la ventana `D+1 … D+3` (post-parcial) responde
  "¿el parcial vacía la herramienta?".

---

## 5. Retención: la métrica de salud

### 5.1 Definiciones (sin ambigüedad)

> **Activo en la semana W**: el alumno escribió **al menos un mensaje** en
> algún día de la semana W (semana = lunes a domingo, en la zona de la
> cátedra).

> **Cohorte base (S1)**: el conjunto de alumnos activos en la **semana 1 de la
> cursada** (`weekIndex = 0`). Es la cohorte de arranque: los que chokan en
> la primera semana.

> **Retención(W)**: `|activos en W ∩ S1| / |S1|`, en puntos básicos.

> **Retención con ventana(W, k)**: `|activos en alguna de las semanas
> [W, W+k−1] ∩ S1| / |S1|`. Con k=2 o 3 el número se estabiliza y deja de
> depender de que el alumno haya chocado **exactamente** en esa semana.

La pregunta literal del enunciado —"¿qué porcentaje de alumnos que choca la
semana 1 sigue activo en la semana 8?"— es `retentionBp(weekIndex = 7)` con
k=1. La app muestra ese número como **"Retención S1→S8"** y, al lado, el
valor con ventana de 2 semanas, que es el que se usa para decidir.

### 5.2 Algoritmo

```
1. activeByWeek: Map<Int, Set<String>>   // weekIndex → alumnos activos
   (se arma en la misma pasada de mensajes del §4.1)

2. base = activeByWeek[0] ?: emptySet()
   Si base está vacío → la curva de retención queda vacía y la UI dice
   "No hay actividad en la primera semana de la cursada."

3. Para W en 0..maxWeek:
     active   = activeByWeek[W].orEmpty()
     retained = active ∩ base
     returning= active − {alumnos cuyo primer uso es en W}
     new      = alumnos cuyo primer uso es en W
     churned  = activeByWeek[W−1] − active          (si W > 0)
     retentionBp(W) = if (base.isEmpty()) 0
                       else retained.size * 10_000 / base.size

4. healthBp         = retentionBp(7)          // semana 8 en numeración de UI
   healthWindow2Bp  = retentionConVentana(7, 2)
```

`retentionConVentana(W, k)` = `|∪_{w∈[W, W+k−1]} activeByWeek[w] ∩ base| / |base|`.

**Cuidado con la semana 0 parcial**: si la cursada arrancó el miércoles, la
semana 1 tiene 3 días y su retención está subestimada. Se avisa en el
tooltip: *"La semana 1 tiene 3 días (la cursada arrancó el miércoles)."*

**Sobre el churn**: el "churn semanal" (`churned`) **no** es la pérdida
acumulada: un alumno que vuelve la semana siguiente no está "churned" en el
sentido de abandono. Por eso la curva de retención (acumulada) y el churn
(semanal) se muestran como **dos series distintas**: la primera responde
"¿cuántos siguen?", la segunda "¿cuántos se van cada semana?".

### 5.3 La curva y cómo se lee

```
Retención — cohorte base: 36 alumnos activos en la semana 1
100 % ┤●
 90 % ┤ ●
 80 % ┤  ●
 70 % ┤   ●───●
 60 % ┤        ●───●───●───●     ● S8 = 61 % (22 de 36)
 50 % ┼ - - - - - - - - - - - - - - - - - -  referencia
 40 % ┤
      └──┬────┬────┬────┬────┬────┬────┬────┬────┬
         S1   S2   S3   S4   S5   S6   S7   S8   S9   S10
```

Dibujo: `Canvas` con `Brush.verticalGradient` bajo la curva (área), la línea
en `primary` de 2 dp, un círculo por punto (3 dp), la referencia del 50 %
punteada en `outline`, y el punto de la semana 8 con un halo + etiqueta.

**Guía de lectura (va escrita en la UI, no sólo en este doc):**

| Lo que se ve | Qué significa | Qué se puede hacer |
|---|---|---|
| Caída fuerte las primeras 2-3 semanas y meseta después | El grupo se filtra al principio y después el núcleo estable | Subir material antes de la semana 2 |
| Meseta alta (≥ 70 %) | La herramienta se volvió hábito | Celebrar y sostener |
| Caída continua, sin meseta | La herramienta no entra en la rutina | Revisar el material y la consigna de las TPs |
| Muchos alumnos que entran después de S1 | La curva de retención (que usa S1 como base) los ignora: mirar además la serie "nuevos por semana" | Onboarding para los que entran tarde |
| `returning` alto y `new` en 0 varias semanas | Uso ya establecido, no de descubrimiento | — |

El panel lateral de la pestaña muestra los **números crudos** de la curva
(cohorte base, retidos por semana, nuevos, churn), porque el docente a
menudo quiere el entero, no el porcentaje.

---

## 6. Cohortes

### 6.1 El modelo

Una **cohorte** es un grupo de alumnos definido por una regla determinista,
con una grilla de columnas (semanas) y un valor por celda.

```kotlin
// (los tipos ya están definidos en §3)
enum class CohortDimension { SIGNUP_WEEK, MODEL_MODE, USAGE_TIER }
```

Reglas comunes a las tres dimensiones:

1. Sólo entran alumnos **con `visibility.showsMetrics`** (los que elegeron
   "oculto" no aparecen en ningún agregado: es la regla del proyecto).
2. Sólo alumnos con rol `ALUMNO`.
3. **Mínimo de cohorte: 5.** Un grupo de menos de 5 se muestra con su `n`
   pero **sin porcentajes** (celdas en "—"). Razón de privacidad: un 100 %
   de 2 personas es el dato de 2 personas.
4. Las celdas con **0 alumnos activos** se muestran con el valor 0 % (no con
   "—"): la ausencia de actividad es información.
5. El eje de columnas depende de la dimensión (§6.2).

### 6.2 Las tres dimensiones

#### 6.2.1 `SIGNUP_WEEK` — por fecha de inscripción

**Regla**: la cohorte de un alumno es la **semana de la cursada en que se
inscribió** (`Enrollment.joinedAt` → día local → `weekIndex`).

- Si `joinedAt == 0` (inscripción vieja sin fecha): se usa el día del primer
  mensaje del alumno; si tampoco hay mensajes, el alumno **queda fuera de
  esta dimensión** (no es "de la semana 0": es "desconocido").
- Clave de la cohorte: el lunes de esa semana (`"2026-04-20"`).
- Etiqueta: *"Inscriptos en S1"* … *"Inscriptos en S4"*.
- **Eje: RELATIVO.** La columna 0 es la semana en que se inscribieron; la
  columna k es la semana k posterior.
- Tamaño: `n` alumnos.

Por qué eje relativo: la pregunta es "¿los que entraron temprano se
quedan?", y eso sólo se puede medir en el tiempo propio de cada grupo. La
misma cohorte comparada con la línea de tiempo de la cursada mezcla dos
cosas (¿cuándo entraron? vs ¿cuánto duran?).

**Lectura de la tabla** (ejemplo de 4 cohortes; eje relativo, cada fila
arranca en su propia semana de inscripción):

```
Cohorte                 S+0   S+1   S+2   S+3   S+4   S+5   S+6   S+7
Inscriptos en S1 (41)   88%   79%   73%   68%   65%   63%   58%   54%
Inscriptos en S2 (18)   94%   78%   67%   61%   56%   50%   44%   39%
Inscriptos en S3 (12)  100%   67%   58%   50%   33%    —     —     —
Inscriptos en S4  (7)   86%   71%   43%    —     —     —     —     —
```

(son los mismos números que la tabla absoluta de §6.3, corridos: la columna
S+0 de "Inscriptos en S2" es la semana 2 de la cursada).

| Lo que se ve | Qué significa |
|---|---|
| La diagonal (columna S+0) es brillante y las filas se apagan rápido | La cohorte se filtra en las primeras 2-3 semanas |
| La primera columna de S2/S3 está más baja que la de S1 | Los que entran tarde arrancan peor: el problema es de *onboarding*, no del contenido |
| Las filas se cruzan (S1 arriba de S2 en S+2, pero abajo en S+4) | Los que entraron tarde "compensan": no es pérdida de grupo, es reordenamiento |
| Una cohorte con S+0 < 100 % | Hubo alumnos que se anotaron y nunca escribieron: hay que mirar la visibilidad y el flujo de inscripción |

**Un recorte adicional que el docente va a pedir**: "inscriptos temprano vs
tarde". Se define como un bucket derivado de `SIGNUP_WEEK`:
`Temprano` = inscripto en las primeras 2 semanas de la cursada;
`Tarde` = después. Se muestra como un comparador de dos curvas de retención
(eje absoluto), que es la lectura "de una línea".

#### 6.2.2 `MODEL_MODE` — por modo de uso (IA alojada vs IA local)

**Regla**: la cohorte de un alumno es el **modo del modelo que más usó**, con
desempate determinista.

```
Para cada alumno visible:
    conteo = { REMOTE: nRespuestasEnHilosRemoto, LOCAL: nRespuestasEnHilosLocal }
    (los hilos sin respuestas no cuentan)
    un hilo es LOCAL si thread.effectiveModel.mode == ModelMode.LOCAL
    un hilo es REMOTE si thread.effectiveModel.mode == ModelMode.REMOTE
                    Y thread.effectiveModel.modelName NO está vacío
    en otro caso → el hilo es UNKNOWN (no aporta a ningún conteo)
    modo = el de mayor conteo; si hay empate → REMOTE (el default de la cátedra)
    si el conteo total es 0 → el alumno no entra en esta dimensión
```

- Etiquetas: *"Servidor de la cátedra"* (REMOTE) e *"IA local del alumno"*
  (LOCAL). Son los `label` de `ModelMode`, que ya existen.
- **Eje: ABSOLUTO** (semana de la cursada): una cohorte de "IA local" no
  tiene arranque propio, así que se compara contra el calendario de la
  cursada.
- Es la única dimensión cuyo tamaño **cambia a lo largo del curso** (alguien
  puede migrar de local a alojado). Por eso el descriptor de la cohorte se
  recalcula sobre la ventana completa y se muestra el "modo actual" en el
  detalle por alumno.

**El hallazgo interesante** —"¿rinde más la IA local?"— se responde con una
tarjeta comparativa (§6.5), no con la tabla de cohortes.

#### 6.2.3 `USAGE_TIER` — por frecuencia de uso

**Regla**: la intensidad de uso del alumno, medida en una **ventana fija de
las primeras 4 semanas de la cursada**, para no usar información del futuro
(ventana leakage).

```
días activos del alumno en las semanas 0..3  (días distintos con un mensaje)

si el alumno se inscribió después del fin de la semana 3
   (joinedAt > fin de la semana 3) → UNCLASSIFIED (no entra en la comparación)

si días >= 6  → HABITUAL   ("Asiduo")
si días in 2..5 → REGULAR  ("Regular")
si días <= 1  → ESPORADICO ("Esporádico")
```

- **Eje: ABSOLUTO** (semana de la cursada).
- La ventana de 4 semanas es una decisión, no un misterio: con menos, una
  semana de parcial infla al grupo; con más, no hay suficientes alumnos
  inscriptos como para clasificarlos.

**Honestidad metodológica (importante)**: comparar la retención de "asiduos"
contra "esporádicos" es **tautológico** — un esporádico por definición tiene
pocos días activos, así que su retención *tiene* que ser baja. Por eso:

- La dimensión `USAGE_TIER` **no** se usa para la tarjeta de retención.
- Se usa para **describir** (volumen por alumno activo, latencia, % de citas
  respaldadas, franjas horarias, semana de abandono) y para segmentar el
  heatmap: *"así se usa la herramienta de noche"*, que es la pregunta que sí
  tiene valor.
- La UI pone un aviso explícito sobre la tarjeta: *"El grupo de frecuencia se
  define por su actividad: comparar su retención con la de los asiduos es
  tautológico."*

### 6.3 La tabla cohorte × semana

Una **matriz**: filas = cohortes, columnas = semanas, celda = % de la cohorte
activa.

```
                    Semana de la cursada →
Cohorte          S1    S2    S3    S4    S5    S6    S7    S8    S9   S10
──────────────────────────────────────────────────────────────────────────
Inscriptos S1   88%   79%   73%   68%   65%   63%   58%   54%   50%   47%
Inscriptos S2   94%   78%   67%   61%   56%   50%   44%   39%    —     —
Inscriptos S3  100%   67%   58%   50%   33%    —     —     —     —     —
Inscriptos S4   86%   71%   43%    —     —     —     —     —     —     —
Servidor cátedra 88%  71%   62%   55%   50%   47%   44%   40%   36%   34%
IA local alumno  79%   62%   55%   48%   41%   37%   33%   29%   24%   22%
Asiduo (n=18)  100% 100%   94%   89%   83%   78%   72%   67%   61%   56%
Esporádico(n=14) 71%  29%   21%   14%    7%    7%    0%    0%    0%    0%
```

> **La fila "Inscriptos S1" NO es la curva de retención de §5.** Son dos
> denominadores distintos: la cohorte de **inscripción** son los 41 alumnos
> que se anotaron en la semana 1 (incluidos los que nunca escribieron, por
> eso la primera columna no es 100 %), y la cohorte de **retención** son los
> 36 de esos 41 que efectivamente escribieron esa semana (por eso su curva
> arranca en 100 %). Las dos cuentan la misma gente: la de inscripción en
> términos absolutos, la de retención en términos relativos.

Implementación en Compose: **no** es un `Canvas` (lleva texto), es una grilla
de `Box`:

```kotlin
@Composable
fun CohortMatrix(
    cohorts: List<Cohort>,
    axisLabel: String,
    modifier: Modifier = Modifier,
) {
    val maxColumn = cohorts.maxOfOrNull { c -> c.cells.maxOfOrNull { it.column } ?: 0 } ?: 0
    Column(modifier.verticalScroll(rememberScrollState())) {
        // encabezado: columnas
        Row {
            Text("Cohorte", Modifier.width(180.dp))
            for (col in 0..maxColumn) {
                Text(labelOfColumn(col), Modifier.width(64.dp), textAlign = TextAlign.Center)
            }
        }
        for (cohort in cohorts) {
            Row(Modifier.height(38.dp)) {
                Text(
                    "${cohort.label} (n=${cohort.size})",
                    Modifier.width(180.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                for (col in 0..maxColumn) {
                    val cell = cohort.cells.firstOrNull { it.column == col }
                    CohortCellBox(cell)
                }
            }
        }
    }
}

@Composable
private fun CohortCellBox(cell: CohortCell?) {
    val bp = cell?.activeBp
    val bg = if (bp == null) Color.Transparent else heatColor(levelOf(bp))
    Box(
        Modifier.width(64.dp).height(38.dp).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when {
                cell == null -> ""
                bp == null -> "—"      // cohorte demasiado chica o sin dato
                else -> "${bp / 100}%"
            },
            color = if (bg.luminance() > 0.5f) Color.Black else Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
```

- La escala de color tiene **5 niveles fijos** (`levelOf(bp)`), no un
  degradado continuo: el ojo compara celdas entre cohortes, y los niveles
  discretos se leen mejor en una tabla.
- Los niveles son: `0 %` / `1-25 %` / `25-50 %` / `50-75 %` / `> 75 %`.
- Debajo de la tabla, una **leyenda** con los 5 squares.
- Las celdas "—" llevan un tooltip: *"Cohorte con menos de 5 alumnos"* o
  *"fuera del rango de la cursada"*.
- La tabla es scrolleable en horizontal (16 semanas × 64 dp = 1024 dp > ancho
  típico) y en vertical.

### 6.4 Alternativa: la matriz como heatmap en `Canvas`

Cuando hay muchas cohortes (> 12 filas), la tabla con texto se vuelve
ilegible. Se ofrece un toggle "Mapa de calor" que dibuja la misma matriz en
un `Canvas` (sin texto, sólo color + tooltip):

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/analytics/CohortHeatmap.kt
Canvas(Modifier.fillMaxWidth().height((cohorts.size * 28 + 40).dp)) {
    val left = 150.dp.toPx()
    val cellW = (size.width - left) / (maxColumn + 1)
    val cellH = 28.dp.toPx()
    cohorts.forEachIndexed { r, cohort ->
        drawText(measurer, cohort.label, topLeft = Offset(0f, top + r * cellH + 6.dp.toPx()), style = labelStyle)
        cohort.cells.forEach { cell ->
            val bp = cell.activeBp ?: return@forEach
            drawRect(heatColor(levelOf(bp)),
                topLeft = Offset(left + cell.column * cellW, top + r * cellH),
                size = Size(cellW - 1f, cellH - 1f))
        }
    }
}
```

### 6.5 La comparación "IA alojada vs IA local"

Es la pregunta que más justifica la dimensión `MODEL_MODE`. Tarjeta:

```
┌────────────────────────────┬──────────────────────┬──────────────────────┐
│                            │ Servidor de cátedra  │ IA local del alumno  │
├────────────────────────────┼──────────────────────┼──────────────────────┤
│ Alumnos                    │ 38                   │ 11                   │
│ Alumnos activos            │ 31                   │ 9                    │
│ Mensajes por alumno activo │ 27                   │ 14                   │
│ Retención S1 → S8           │ 47 %                 │ 29 %                 │
│ Citas respaldadas           │ 71 %                 │ 38 %                 │
│ Latencia media              │ 2.310 ms             │ 6.840 ms             │
└────────────────────────────┴──────────────────────┴──────────────────────┘
```

Cómo se calcula cada fila (todo determinista):

| Fila | Cálculo |
|---|---|
| Alumnos | `cohort.size` |
| Alumnos activos | alumnos con ≥ 1 mensaje en el rango completo |
| Mensajes por alumno activo | `mensajes / alumnos activos` (0 si no hay activos) |
| Retención S1→S8 | `|activos(S8) ∩ base(S1) ∩ cohorte| / |base(S1) ∩ cohorte|` |
| Citas respaldadas | `verified * 10000 / total` de las respuestas de esa cohorte |
| Latencia media | `Σ latencyMs / nº respuestas` de esa cohorte |

**Los sesgos, escritos al lado de la comparación** (no escondidos):

1. **Sesgo de selección**: los alumnos que eligen IA local son, por definición,
   distintos: quizás más técnicos, con mejor equipo, o sin acceso al servidor
   institucional. La diferencia observada no prueba que un modo sea mejor.
2. **Sesgo de visibilidad**: un alumno con IA local puede tener
   `visibility = NONE` → **no aparece en ningún agregado** (ni en la
   cohorte, ni en el denominador). Los que aparecen son los que aceptaron
   ser medidos: ya es una selección.
3. **Modelos distintos**: el modelo local (`Phi-4-mini`, etc.) es más chico
   que el del servidor. Comparar "IA local" contra "servidor" compara
   *configuraciones*, no *modos*.
4. **Latencia incomparable**: la latencia local depende del hardware del
   alumno (GPU, RAM, si el servidor de llama.cpp está en otro dispositivo).
   La fila de latencia compara *experiencias*, no performances.
5. **Muestra chica**: con n=11, un alumno que cambia de comportamiento mueve
   el porcentaje varios puntos. La tarjeta muestra el `n` y marca la
   comparación como *exploratoria* si algún lado tiene menos de 10 alumnos.

Lo que sí se puede afirmar con honestidad: *"los alumnos que usan IA local
envían la mitad de mensajes por alumno activo y tienen la mitad de citas
respaldadas que los que usan el servidor de la cátedra, sobre una muestra de
11 alumnos visibles"*. Eso es un hallazgo **descriptivo**, accionable (¿el
material no rinde con el modelo chico? ¿conviene recomendar el servidor?) y
no una afirmación causal.

### 6.6 Dimensiones que NO se implementan (y por qué)

- **Crosstab (inscripción × modo)**: 4 cohortas × 3 modos = 12 filas con n
  chico. Se puede agregar después; el motor de cohortes lo soporta (basta
  componer reglas), pero en el hito 1 la tabla se vuelve ilegible.
- **Cohortes por etiquetas manuales del docente** ("los de la comisión A"):
  requeriría un modelo de grupos, que el dominio no tiene (ver
  `docs/design/domain/07-anuncios.md` §2).
- **Cohortes por "usó el material X"**: se puede derivar de `sources`, pero es
  una cohorte de *contenido*, no de personas; queda para la analítica de
  fuentes.

---

## 7. Patrones horarios: heatmap hora × día de la semana

### 7.1 Qué responde

- ¿La gente estudia de noche? (21:00-02:00)
- ¿El fin de semana? (sábado y domingo)
- ¿Justo antes y después de la clase? (el horario del día en que la clase es)

Es un dato **accionable**: si el 38 % del uso es el domingo de 20 a 22 h, el
docente publica el material de la TP el viernes a la tarde, y sube el
material de parcial un jueves, no el lunes.

### 7.2 Algoritmo

```
Para cada mensaje de un alumno visible:
    off = if (m.tzOffsetMinutes != 0) m.tzOffsetMinutes else offsetCátedra
    wd  = CalendarMath.localDayOfWeek(m.createdAt, off)   // 0 = lunes
    h   = CalendarMath.hourOfDay(m.createdAt, off)         // 0..23
    celda = heatmap[wd][h]
    if (m.isUser) celda.messages++ else celda.replies++
    celda.students += t.authorId      // Set<String> por celda

Se devuelven las 168 celdas (7 × 24), en orden lunes→domingo, hora 0→23,
incluidas las vacías (con 0). La lista no se filtra: el tamaño es fijo y la
dibujo no tiene que defenderse de huecos.
```

**Sobre la zona**: el heatmap usa la zona **del autor** (`tzOffsetMinutes`),
no la de la cátedra. Motivo: se quiere medir el hábito de la persona. Un
alumno en otra provincia (o en otro país) tiene su propia noche. Cuando el
mensaje es viejo y no tiene offset, se usa el de la cátedra.

**Sobre el volumen vs. la intensidad**: la celda guarda dos cosas distintas:
`messages` (volumen) y `students` (cuántos alumnos distintos). El color usa
`messages` por defecto, con un toggle a "alumnos distintos" para no confundir
"muchos mensajes de una persona" con "mucha gente".

### 7.3 Dibujo (`Canvas`)

```kotlin
@OptIn(ExperimentalTextApi::class)
@Composable
fun HourWeekdayHeatmap(
    cells: List<HourCell>,
    modifier: Modifier = Modifier,
    /** Horas en las que hay clase (para marcar el horario de cursada). */
    classHours: Set<Int> = emptySet(),
) {
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val byKey = remember(cells) { cells.associateBy { it.weekday to it.hour } }
    val maxMessages = remember(cells) { cells.maxOfOrNull { it.messages } ?: 0 }

    Canvas(modifier.fillMaxWidth().height(230.dp)) {
        val left = 44.dp.toPx()      // nombres de día
        val bottom = 24.dp.toPx()    // etiquetas de hora
        val top = 6.dp.toPx()
        val cellW = (size.width - left - 6.dp.toPx()) / 24f
        val cellH = (size.height - top - bottom) / 7f

        for (wd in 0 until 7) {
            // nombre del día
            drawText(measurer, dayLabel(wd),
                topLeft = Offset(0f, top + wd * cellH + cellH / 2 - 6.dp.toPx()),
                style = labelStyle)
            for (h in 0 until 24) {
                val cell = byKey[wd to h]
                val v = cell?.messages ?: 0
                drawRect(heatColor(levelOfValue(v, maxMessages)),
                    topLeft = Offset(left + h * cellW, top + wd * cellH),
                    size = Size(cellW - 1f, cellH - 1f))
                // marca del horario de clase
                if (h in classHours) {
                    drawRect(colors.onSurface.copy(alpha = 0.25f),
                        topLeft = Offset(left + h * cellW, top + wd * cellH),
                        size = Size(cellW - 1f, cellH - 1f))
                }
            }
        }
        // etiquetas de hora: cada 3 horas
        for (h in 0 until 24 step 3) {
            drawText(measurer, "%02d".format(h),
                topLeft = Offset(left + h * cellW, top + 7 * cellH + 4.dp.toPx()),
                style = labelStyle)
        }
    }
}
```

**La escala de color** (`levelOfValue`), 5 niveles discretos, calculada con
enteros:

```kotlin
/** Nivel 0..4 de un valor relativo al máximo. 0 = sin actividad. */
internal fun levelOfValue(value: Int, max: Int): Int = when {
    value <= 0 -> 0
    max <= 0 -> 0
    else -> min(4, 1 + value * 4 / max)   // 1..4
}
```

Con `max = 3` los valores quedan {0, 2, 3, 4, 4}: suficiente. Con `max`
grande, los niveles se reparten en cuartiles.

Toggle de **escala absoluta**: si el docente compara dos cátedras, la escala
relativa miente. Se ofrece un modo "escala fija" con umbrales absolutos
(1-5 / 6-15 / 16-40 / > 40 mensajes por celda), documentados en la leyenda.

### 7.4 Lecturas derivadas (las que el docente usa de verdad)

Tres números, calculados sobre el heatmap:

| Métrica | Definición | Pregunta que responde |
|---|---|---|
| **Uso nocturno** | `%` de mensajes entre las 21:00 y las 02:00 (horas del autor) | "¿estudian de noche?" |
| **Uso en fin de semana** | `%` de mensajes en sábado o domingo | "¿el fin de semana es parte de la rutina?" |
| **Concentración** | `%` de mensajes en las 4 franjas horarias con más actividad | "¿el uso está concentrado o repartido?" |

Algoritmo (ejemplo del nocturno):

```
nocturnos = Σ messages de las celdas (cualquier día, h ∈ {21,22,23,0,1})
total     = Σ messages de las 168 celdas
nocturnoBp = if (total == 0) 0 else nocturnos * 10_000 / total
```

**Con qué prepare material**: la tarjeta de "pico de uso" muestra el día y la
hora con más actividad y propone la acción:

> *"El 22 % del uso ocurre los domingos entre las 20 y las 22 h. Publicá el
> material de la TP2 antes del viernes a la noche: es cuando la gente
> realmente chatea."*

Esa frase la arma el código con plantillas fijas (nunca un modelo): es
determinista y no puede inventar.

---

## 8. Calidad y latencia en el tiempo

### 8.1 Qué se grafica

Dos series **separadas** (nunca un doble eje Y: mezclar "% de citas" y
"milisegundos" en el mismo eje produce lecturas falsas):

1. **% de citas respaldadas** por semana (`supportRateBp`). Si **baja**
   conforme avanza la cursada, la lectura es: *el material no acompaña lo que
   los alumnos preguntan* (o el modelo se degrada con consultas más
   complejas). Si **sube**, el material se está indexando bien.
2. **Latencia media de las respuestas** por semana (`avgLatencyMs`). Sube
   cuando las preguntas se hacen más complejas (o cuando el servidor está
   saturado).

Como tercera serie, el **% de respuestas sin respaldo** (`ungroundedRateBp`),
que es el negativo del anterior visto desde el alumno.

### 8.2 Algoritmo

Las métricas por semana ya salen del agrupamiento del §4.1 (`WeekBucket`).
Lo único nuevo es la **tendencia**:

```
Para cada métrica semanal m (supportRateBp, ungroundedRateBp):
    pares = semanas donde m != null            ← las semanas sin citas NO son 0
    if (pares.size < 3) → slope = null
    else:
        n = pares.size
        Σx = Σ semana ;  Σy = Σ valor ;  Σxy = Σ semana*valor ;  Σx² = Σ semana²
        num = n*Σxy - Σx*Σy
        den = n*Σx² - Σx*Σx
        slope = if (den == 0) null else num / den        ← división entera
```

- El cálculo es **de mínimos cuadrados ordinario**, pero con aritmética
  **entera**: `num` y `den` son enteros; `num / den` es división entera
  (trunca hacia 0). El resultado es un entero en puntos básicos por semana:
  `+250` = "el % de citas respaldadas sube 2,5 puntos por semana".
- **Las semanas sin citas se excluyen** de la recta (no valen 0: 0 % de citas
  respaldadas significaría "ninguna cita fue respaldada", que es distinto de
  "no hubo citas"). En el gráfico se dibujan como huecos, con un punto
  neutro y la leyenda "sin citas esa semana".
- Con menos de 3 puntos, la pendiente es `null` y la UI dice *"hace falta más
  actividad para ver una tendencia"*.

**Descomposición por modo**: la serie de calidad se puede filtrar por
`REMOTE` / `LOCAL`. Sin esa descomposición, la curva agregada mezcla dos
modelos distintos y la pendiente no significa nada.

### 8.3 La vista

```
Calidad de las respuestas                        [Todo ▾] [REMOTE | LOCAL]

% de citas respaldadas
100 ┤                        ●
 80 ┤        ●─────●────●───●─────●───●───●
 60 ┤   ●
    └──┬─────┬─────┬─────┬─────┬─────┬─────┬
       S1    S2    S3    S4    S5    S6    S7    S8
                          pendiente: −1,8 puntos por semana

Latencia media (ms)
8000 ┤                    ●
6000 ┤        ●
4000 ┤  ●─────●
2000 ┤
    └──┬─────┬─────┬─────┬─────┬─────┬─────┬
       S1    S2    S3    S4    S5    S6    S7    S8
```

Las dos series comparten el eje X (semana de la cursada) pero se dibujan en
**dos `Canvas` apilados**, con el mismo `weekIndex` en el mismo lugar.

Bajo los gráficos, una tabla de las semanas con: `semana`, `alumnos activos`,
`mensajes`, `% citas respaldadas`, `% respuestas sin respaldo`, `latencia
media`, `nº de respuestas` (la muestra). El `nº de respuestas` va siempre:
una semana con 3 respuestas no es una tendencia.

### 8.4 La lectura que le importa al docente

> **Si la pendiente de `% citas respaldadas` es negativa y consistente**
> (por debajo de −100 puntos básicos por semana durante 3+ semanas), la
> tarjeta de la pestaña Calidad muestra:
> *"La calidad de las respuestas cae a lo largo de la cursada (−1,8 puntos por
> semana). El material de las últimas semanas no está sosteniendo las
> consultas: revisá qué se subió en la última mitad del cuatrimestre."*
>
> Y, si además el uso **subió** en esas semanas, la lectura es más dura: el
> material no acompaña lo que la gente pregunta.

El "revisá qué se subió" se puede cruzar con la pestaña Actividad: los
hitos `MATERIAL` ("material nuevo") aparecen en la línea de tiempo, así que
el docente puede ver si el caída empieza justo después de subir un PDF
escaneado sin texto (que el RAG no puede recuperar bien).

---

## 9. Los hitos del calendario

### 9.1 `ScheduleEntry`

Archivo nuevo: `core/src/commonMain/kotlin/kitoru/domain/Schedule.kt`

```kotlin
package kitoru.domain

/** Tipo de hito del calendario académico. */
@Serializable
enum class ScheduleKind(val label: String, val short: String) {
    /** Clase o reunión de cátedra. */
    CLASS("Clase", "clase"),
    /** Parcial o examen. */
    EXAM("Parcial", "parcial"),
    /** Quiz, toma o entrega de un cuestionario. */
    QUIZ("Quiz", "quiz"),
    /** Entrega de un trabajo práctico. */
    DEADLINE("Entrega", "entrega"),
    /** Feriado o día sin clases. */
    HOLIDAY("Feriado", "feriado"),
    /** Se subió material nuevo a la cátedra. */
    MATERIAL("Material nuevo", "material"),
    /** Cualquier otro hito. */
    OTHER("Otro", "otro"),
    ;

    companion object {
        fun fromName(name: String): ScheduleKind? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Un hito del calendario de la cátedra.
 *
 * Lo marca el docente a mano. Es lo que permite que la analítica diga
 * "el pico de uso del 12/05 es el parcial del 14/05".
 */
@Serializable
data class ScheduleEntry(
    val id: String,
    val courseId: String,
    /** Nombre del hito ("Parcial 1", "TP2 - entrega", "Feriado del lunes"). */
    val title: String,
    val kind: ScheduleKind = ScheduleKind.CLASS,
    /**
     * Día del hito: **medianoche local** en la zona de la cátedra
     * (epoch millis). Es la misma clave que usa `DayBucket.day`.
     */
    val day: Long = 0L,
    /** Último día (inclusive) si el hito dura varios días. 0 = un solo día. */
    val endDay: Long = 0L,
    /**
     * Hora de inicio en minutos desde la medianoche (ej. 540 = 09:00).
     * null = todo el día.
     */
    val startMinute: Int? = null,
    /** Duración en minutos. null = puntual. */
    val durationMinutes: Int? = null,
    /** Notas libres del docente. */
    val notes: String = "",
    /** Quién lo creó (id de usuario). */
    val createdBy: String = "",
    val createdAt: Long = 0L,
) {
    /** El hito ocupa un rango de días. */
    val isRange: Boolean get() = endDay > day

    /** Fin del rango (inclusive). */
    val lastDay: Long get() = if (isRange) endDay else day

    /** ¿Es un evento que afecta la actividad (parcial, entrega, feriado)? */
    val isMilestone: Boolean
        get() = kind == ScheduleKind.EXAM || kind == ScheduleKind.QUIZ ||
                kind == ScheduleKind.DEADLINE || kind == ScheduleKind.HOLIDAY
}
```

**Por qué `day` es la medianoche local y no un instante**: así el hito se
alinea exactamente con las celdas del `DayBucket` y con las bandas del
heatmap, sin conversiones por el camino. La zona horaria es la de la cátedra
(`course.schedule.timeZoneOffsetMinutes`).

**Validación** (mismo estilo fail-fast en español que `ModelPolicy.validate()`):

```kotlin
fun validate(): List<String> {
    val errors = mutableListOf<String>()
    if (title.isBlank()) errors += "El hito necesita un nombre."
    if (title.length > 120) errors += "El nombre no puede superar los 120 caracteres."
    if (day <= 0L) errors += "El hito necesita una fecha."
    if (isRange && endDay < day) errors += "La fecha de fin no puede ser anterior a la de inicio."
    startMinute?.let { if (it !in 0..1439) errors += "La hora de inicio no es válida." }
    durationMinutes?.let { if (it !in 1..1440) errors += "La duración no es válida." }
    return errors
}
```

### 9.2 Persistencia

```kotlin
interface ScheduleRepository {
    suspend fun listForCourse(courseId: String): List<ScheduleEntry>
    suspend fun get(id: String): ScheduleEntry?
    suspend fun save(entry: ScheduleEntry)
    suspend fun delete(id: String)
}
```

Implementación: `FileScheduleRepository` sobre `JsonListStore`, archivo
`config/schedule.json` (mismo patrón que `FileCourseRepository`; los ids
llevan el prefijo del curso, como en los hilos: `"curso|hito|uuid"`).

El `ScheduleEntry` **no** se guarda dentro del `Course`: los hitos se editan
por separado (alta/baja) y el `Course` se serializa en cada cambio.

### 9.3 Cómo los marca el docente

Pestaña **Calendario** dentro de la analítica (o dentro de "Diseñar la
cátedra", que es donde vive el resto de la configuración):

- Una grilla de meses (no un `Canvas`: es una grilla con texto y botones).
- Click en un día → diálogo: nombre + tipo (`ScheduleKind`) + hora opcional.
- Botones rápidos: "Parcial", "Entrega", "Feriado".
- Acá también se define el **inicio de la cursada** y la **zona horaria**
  (que es lo que populate `course.schedule`).

```
┌─────────────────────────── Abril 2026 ───────────────────────────┐
│  lu  ma  mi  ju  vi  sá  do                                    │
│      1   2   3   4   5  6                                     │
│  6   7   8   9  10  11  12                                     │
│ 13  14  15  16  17  18  19                                     │
│ 20  21  22  23  24  25  26        ← 22 con punto rojo = parcial │
└──────────────────────────────────────────────────────────────────┘
```

### 9.4 El efecto de los hitos en los gráficos

| Hito | Efecto en la curva de uso | Efecto en otras vistas |
|---|---|---|
| `EXAM` | Línea vertical roja + círculo + etiqueta + sombreado pre-parcial (D−3…D−1) | Genera el **lift pre-parcial** (§4.5) y el **post-parcial**; la semana del parcial se marca en la curva de retención (picos de churn) |
| `HOLIDAY` | Banda gris de toda la altura | Los días de feriado se **excluyen** del cálculo del "uso normal" (baseline) |
| `CLASS` | Tick en el borde inferior | Marca la franja horaria de la clase en el heatmap (§7.3) |
| `DEADLINE` | Línea punteada | Igual que el parcial pero sin el sombreado |
| `MATERIAL` | Punto verde | Sugerencia de correlación en la pestaña Calidad |

**Exclusión de feriados del baseline** (determinista): al calcular el "uso
normal" de la semana, los días con `HOLIDAY` no se cuentan como días con
clase; el denominador del promedio es "días sin feriado". Si no, un fin de
semana largo con feriado baja artificialmente el promedio y exagera el
"lift".

**Regla**: un hito fuera del rango analizado (por ejemplo, un parcial del
cuatrimestre pasado) no se dibuja, pero **no se borra**: se filtra por rango
al dibujar, no al leer.

---

## 10. Determinismo: contratos y tests

### 10.1 Reglas

1. **Ninguna función del analizador lee el reloj.** `now` entra como
   parámetro: `TemporalAnalyzer.analyze(course, enrollments, threads,
   messages, entries, now = nowMillis())`. El caller decide qué es "hoy"; los
   tests fijan un `now` y obtienen siempre el mismo resultado.
2. **Nada de `Double` en las comparaciones.** Los porcentajes son `Int` en
   puntos básicos (`0..10_000`). El único `Double` que aparece es al
   formatear para pantalla (`bp / 100.0`).
3. **Orden explícito en todas las salidas.** `cohorts` se ordena por
   `(dimension, key)`; `heatmap` sale en orden lunes→domingo, hora 0→23;
   `daily` y `weekly` por índice ascendente; `entries` por `day`.
4. **Ninguna dependencia de la zona horaria del proceso.** Todo offset viene
   del parámetro, no de `TimeZone.getDefault()`.
5. **Sin flotantes en las rectas**: la pendiente es entera (§8.2).
6. **Divisiones por cero**: todo denominador se chequea y devuelve `0` o
   `null`, nunca `NaN` ni excepción.
7. **Sin aleatoriedad**: no hay muestreo, ni "top N" con desempate aleatorio,
   ni colores aleatorios. Los empates se rompen por orden estable
   (alfabético).

### 10.2 El contrato del analizador

```kotlin
object TemporalAnalyzer {

    /** Tope de días que se analyzan (el rango se recorta por el final). */
    const val MAX_DAYS: Int = 366

    /** Cohorte mínima para publicar porcentajes. */
    const val MIN_COHORT_SIZE: Int = 5

    /** Semanas de la ventana pre-parcial y post-parcial. */
    const val PRE_EXAM_DAYS: Int = 3
    const val POST_EXAM_DAYS: Int = 3
    const val BASE_EXAM_DAYS: Int = 11

    /**
     * Analiza la cátedra en el tiempo. **Función pura**: no toca disco, no
     * llama al modelo, no lee el reloj.
     *
     * @param now instante de referencia ("hoy"). Lo pasa el caller.
     */
    fun analyze(
        course: Course,
        enrollments: List<Enrollment>,
        threads: List<ChatThread>,
        messages: List<ChatMessage>,
        entries: List<ScheduleEntry> = emptyList(),
        now: Long,
    ): TemporalAnalytics
}
```

Las piezas se pueden testear por separado:

```kotlin
object CalendarMath { … }                       // §2.2
object RetentionCalculator { fun curve(activeByWeek: Map<Int, Set<String>>): List<RetentionPoint> }
object CohortBuilder { fun build(…): List<Cohort> }
object QualityTrend { fun slope(points: List<Pair<Int, Int>>): Int? }
```

### 10.3 Tests

Archivo nuevo: `core/src/desktopTest/kotlin/kitoru/domain/TemporalAnalyzerTest.kt`
(el proyecto ya testea en `core/src/desktopTest` con JUnit 5; ver
`Bm25IndexTest`, `ChunkerTest` en `:backend`).

**`CalendarMathTest`**

| Caso | Entrada | Esperado |
|---|---|---|
| Medianoche local en UTC | `localDayStart(0, 0)` | `0` |
| Medianoche local en UTC−3 | `localDayStart(1, -180)` | `-75_600_000` |
| Suelo con millis negativo | `localDayStart(-1, 0)` | `-86_400_000` |
| Día de la semana | `localDayOfWeek(0, 0)` | `3` (jueves) |
| Lunes de la semana | `weekStart(localDayStart(0, 0))` | `-3 * DAY_MS` |
| Hora local | `hourOfDay(86_400_000L + 5*3_600_000L, -180)` | `2` |
| Semana de cursada | `weekIndex(termWeekStart + 3*WEEK_MS, termWeekStart)` | `3` |
| Semana negativa (antes del inicio) | `weekIndex(termWeekStart - WEEK_MS, termWeekStart)` | `-1` |

**`TemporalAnalyzerTest`** (con un curso y una inscripción de fábrica)

| Caso | Qué se asserta |
|---|---|
| Día local correcto | Un mensaje a las 23:30 UTC−3 cae en el día local correcto, no en el siguiente |
| Semana correcta | Un mensaje del domingo 23:00 local es de la semana que termina ese domingo |
| Regla de visibilidad | Un alumno con `visibility = NONE` no aparece en `daily`, ni en el denominador de retención, ni en ninguna cohorte |
| Rol docente | Los hilos del profesor no cuentan en la curva de alumnos |
| Retención | Con 2 alumnos activos en S1, uno activo en S8 → `retentionBp = 5000` |
| Retención vacía | Sin actividad en S1 → la curva está vacía, sin excepción |
| Cohorte de inscripción | Un alumno inscrito en la semana 3 tiene su celda S+0 en la columna 0 y no en otras |
| Cohorte chica | Un grupo de 3 alumnos → `activeBp = null` (se muestra "—") |
| Modo de uso | Un alumno con hilos `LOCAL` y `REMOTE` con igual cantidad de respuestas → `REMOTE` (desempate documentado) |
| Modo desconocido | Un hilo `REMOTE` sin `modelName` no cuenta para ningún modo |
| Tier | 6 días activos en las semanas 0-3 → `HABITUAL`; 5 → `REGULAR`; 1 → `ESPORADICO` |
| Tier sin datos | Inscrito después de la semana 3 → no aparece en `USAGE_TIER` |
| Heatmap | 168 celdas, ordenadas; un mensaje a las 23:00 con offset −180 cae en `(lunes, 23)` |
| Calidad sin citas | Una semana sin citas → `supportRateBp = null` y la pendiente ignora esa semana |
| Pendiente | Con valores (1, 100), (2, 90), (3, 80) → `slope = -1000` (puntos básicos por semana) |
| Ventana pre-parcial | Con un parcial en D y uso concentrated en D−2 → `liftBp > 10_000` |
| Densificación | No hay días faltantes entre el primero y el último |
| Rango enorme | Más de `MAX_DAYS` de diferencia → se recorta y no explota la memoria |
| Cursada sin inicio | `termStart = 0` → se usa el fallback (lunes del primer mensaje) sin excepción |
| Sin datos | Cátedra vacía → todas las listas vacías, sin división por cero |

**`CohortBuilderTest`**: los ejes (relativo vs absoluto), el orden estable de
las cohortes, el mínimo de cohorte.

**`ScheduleEntryTest`**: `validate()` con título vacío, fecha inválida,
rango invertido, hora fuera de rango.

**Criterio de aceptación**: el mismo dataset produce **exactamente** el mismo
`TemporalAnalytics` en dos corridas (comparación con `assertEquals` de la
data class completa), y el dataset serializado a JSON y releído produce el
mismo resultado.

---

## 11. Archivos a crear o tocar

### 11.1 Nuevos

| Archivo | Contenido |
|---|---|
| `core/src/commonMain/kotlin/kitoru/domain/CalendarMath.kt` | Aritmética de fechas en `commonMain` (`localDayStart`, `localDayOfWeek`, `weekStart`, `weekIndex`, `hourOfDay`, `floorDiv`) |
| `core/src/commonMain/kotlin/kitoru/domain/Schedule.kt` | `ScheduleKind`, `ScheduleEntry`, `validate()` |
| `core/src/commonMain/kotlin/kitoru/domain/TemporalAnalytics.kt` | Los tipos de salida (`DayBucket`, `WeekBucket`, `RetentionPoint`, `Cohort`, `CohortCell`, `HourCell`, `QualityPoint`, `ModeComparison`, `TemporalAnalytics`) |
| `core/src/commonMain/kotlin/kitoru/domain/TemporalAnalyzer.kt` | `TemporalAnalyzer.analyze` + `RetentionCalculator` + `CohortBuilder` + `QualityTrend` (o en archivos separados) |
| `core/src/desktopTest/kotlin/kitoru/domain/CalendarMathTest.kt` | Tests de la aritmética de fechas |
| `core/src/desktopTest/kotlin/kitoru/domain/TemporalAnalyzerTest.kt` | Tests del analizador |
| `core/src/desktopTest/kotlin/kitoru/domain/CohortBuilderTest.kt` | Tests de cohortes |
| `core/src/desktopTest/kotlin/kitoru/domain/ScheduleEntryTest.kt` | Tests de `ScheduleEntry` |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/analytics/UsageTimelineChart.kt` | `Canvas` de la curva diaria/semanal |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/analytics/RetentionCurve.kt` | `Canvas` de la curva de retención |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/analytics/CohortMatrix.kt` | Tabla de cohortes + `CohortHeatmap` |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/analytics/HourWeekdayHeatmap.kt` | `Canvas` del heatmap |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/analytics/QualityTrendChart.kt` | `Canvas` de las series de calidad y latencia |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/analytics/AnalyticsTimelineScreen.kt` | La pantalla con las 7 pestañas |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/analytics/AnalyticsColors.kt` | `heatColor`, `levelOf`, `levelOfValue`, `niceCeil` (compartidos por los gráficos) |

### 11.2 Modificados

| Archivo | Cambio |
|---|---|
| `core/src/commonMain/kotlin/kitoru/domain/Course.kt` | `Course.schedule: CourseSchedule = CourseSchedule()` |
| `core/src/commonMain/kotlin/kitoru/domain/Chat.kt` | `ChatMessage.tzOffsetMinutes: Int = 0` |
| `core/src/commonMain/kotlin/kitoru/domain/Analytics.kt` | Corregir el comentario de `DayActivity.day` ("medianoche en la zona de la cátedra") |
| `core/src/commonMain/kotlin/kitoru/domain/AnalyticsAnalyzer.kt` | `analyze(..., timeZoneOffsetMinutes = course.schedule.timeZoneOffsetMinutes)` + sobrecarga `dayStart(millis, offset)` |
| `core/src/commonMain/kotlin/kitoru/domain/Repositories.kt` | `ScheduleRepository` |
| `core/src/commonMain/kotlin/kitoru/domain/FileRepositories.kt` | `FileScheduleRepository` (`config/schedule.json`) |
| `core/src/commonMain/kotlin/kitoru/platform/Time.kt` (nuevo, chico) | `expect fun localUtcOffsetMinutes(): Int` + `expect fun localZoneLabel(): String` |

### 11.3 Aparte (no bloquea este hito)

- Corregir el O(alumnos × mensajes) de `AnalyticsAnalyzer.studentStats`
  agrupando los mensajes por hilo **una sola vez** antes del loop. Es el
  mismo problema que ya se resolvió acá, pero tocar ese analizador es otro
  commit con su propio grupo de tests.
- Caché de la analítica temporal: hoy se recalcula en cada apertura de la
  pestaña. Con 50 000 mensajes son ~40 ms: no hace falta caché. Si el
  Kitoru-Server trae el historial completo de varias cátedras, ahí sí.

---

## 12. Fuera de alcance del hito 1 (decisiones explícitas)

Cada punto dice **por qué** queda afuera, para que no se lea como un
olvido:

| # | Fuera | Por qué |
|---|---|---|
| 1 | **Feriados precargados** por país y año | Una tabla de feriados mal cargada es peor que ninguna: el docente marca 12 fechas por cuatrimestre. Se puede agregar como semilla en el futuro, con un aviso de "verificá los feriados de tu país". |
| 2 | **Importar calendario `.ics` / `.csv`** desde la universidad | Formato variable por universidad, con adjuntos, recurrencias y-scrollbar. Es otro subsistema (ver `docs/design/domain/07-anuncios.md` §10, que ya dejó `.ics` fuera por lo mismo). |
| 3 | **Zona horaria con DST** | Rompe la reproducibilidad ("¿por qué el número de ayer no es el de hoy?"). Se resuelve con un offset fijo por cursada, que el docente puede corregir. |
| 4 | **Detección automática de parciales** por texto ("en el archivo dice parcial") | Requiere NLP y no es determinista. El parcial lo marca el docente. |
| 5 | **Predicción de abandono (churn score)** | Un modelo o un score heurístico sobre el alumno individual es interpreta como "este alumno se va", y eso es una afirmación que la app no puede sostener con estos datos. La retención es agregada y comparativa. |
| 6 | **Tests de significación / A/B entre cohortes** | Con n de 10 a 40 alumnos, un p-value engaña más de lo que informa. Se muestra el `n` siempre y se rotula la comparación como exploratoria. |
| 7 | **Crosstab de cohortes** (inscripción × modo × tier) | Multiplica las filas y destruye el n. El motor lo soporta, la tabla no. |
| 8 | **Cohortes por etiquetas del docente** ("comisión A") | Requiere el modelo de grupos, que el dominio no tiene (decidido en `07-anuncios.md` §2). |
| 9 | **Cohortes por contenido** ("los que preguntaron sobre el PDF 3") | Es cohorte de material, no de personas; pertenece a la analítica de fuentes. |
| 10 | **Comparar cátedras entre sí** (IA 2025 vs IA 2026) | Cada cátedra tiene su propio calendario, sus propios alumnos y su propio material. Comparar requiere normalizar por cohorte y por tamaño, que es otro diseño. |
| 11 | **Análisis del texto de los alumnos** (qué preguntan, con qué palabras) | Requiere procesar el texto de los chats; choca con la visibilidad (`METRICS_ONLY` significa que el docente **no ve** el texto) y agrega un problema de privacidad. |
| 12 | **Exportar las series a CSV / PDF** | Útil, pero es otro módulo (compartir/reportes) con su propio formato y sus permisos. |
| 13 | **Envío automático de un resumen al docente por correo/notificación** | El notification center ya es otro subsistema; la analítica no envía nada. |
| 14 | **retención con cohorte de inscripción como base** (y no la semana 1) | Es la métrica estándar de producto y tiene sentido para el curso; pero acá la pregunta es la salud de la **herramienta** en la cursada, y la base "semana 1" responde exactamente eso. Queda para la versión "producto". |
| 15 | **Streaming / tiempo real** | La analítica es un cálculo sobre el historial; el refresco es un botón "Recalcular". |
| 16 | **Comparar la latencia local con la alojada como "performance"** | La latencia local depende del hardware del alumno: no es comparable. Se muestra, pero rotulada como "experiencia", no como performance (§6.5). |
| 17 | **Gráficos en PDF/imagen para compartir** | La exportación es otro módulo; en el hito 1 el docente mira los gráficos en pantalla. |
| 18 | **Persistencia de los resultados analíticos** | Se recalculan desde el origen (los chats). Cachear introduces invalidación y desincronización; no vale la pena todavía. |

---

## Apéndice · Recetas de datos (para los tests y para entender las cifras)

**Cursada de ejemplo** — 120 alumnos inscritos, cursada de 16 semanas,
comienzo el lunes 06/04/2026, zona −180.

```
Semana        Alumnos activos   Mensajes   % citas respaldadas   Latencia
S1  (06/04)       36              310          68 %                 2.100 ms
S2  (13/04)       52              480          70 %                 2.300 ms
S3  (20/04)       55              520          66 %                 2.600 ms
S4  (27/04)       48              430          61 %                 2.900 ms
S5  (04/05)       61              690          63 %                 3.100 ms   ← pre-parcial
S6  (11/05)       57              610          58 %                 3.400 ms   ← parcial 12/05
S7  (18/05)       44              360          55 %                 2.800 ms
S8  (25/05)       38              290          54 %                 2.700 ms
S9  (01/06)       33              240          57 %                 2.600 ms
S10 (08/06)       29              200          59 %                 2.500 ms
```

Lecturas que salen de esta tabla (y que la UI debe saber producir):

- **Cohorte base de retención** = 36 alumnos (los activos en S1).
- **Retención S1→S8** = activos en S8 ∩ base / base = 22/36 = **61 %**.
- **Pendiente de calidad** = (54 % − 68 %) / 7 semanas ≈ **−2 puntos por semana**.
- **Lift pre-parcial** (parcial el 12/05, D−3…D−1 = 09/05, 10/05, 11/05) contra
  D−14…D−4 (28/04…08/05): el uso de la semana 5 está inflado → **lift ≈ 1,8×**.
- **Churn en S6**: de 61 activos en S5 a 57 en S6, con 4 que vuelven: la caída
  después del parcial es real.

Estos números son de ejemplo, pero **el motor tiene que reproducirlos
exactamente** con esos datos: si el dataset de test da otra cosa, el algoritmo
está mal.

---


