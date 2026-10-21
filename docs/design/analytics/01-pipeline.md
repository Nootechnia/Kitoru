# 01 · Pipeline de agregación de métricas

> Subagente: diseño del **pipeline de cálculo, cacheo e invalidación** de la
> analítica de Kitoru.
> Módulos tocados: `:core` (`kitoru.domain`, `kitoru.platform`).
> Base leída: `domain/Analytics.kt`, `domain/AnalyticsAnalyzer.kt`,
> `domain/FileRepositories.kt`, `domain/JsonStore.kt`, `domain/Chat.kt`,
> `domain/Course.kt`, `domain/Roles.kt`, `platform/FileSystem.kt`,
> `platform/KitoruPaths.kt`.
> Reglas del proyecto respetadas: *reutilizar no reescribir*, *portable por
> defecto* (nada de `java.nio` en `commonMain`), *determinista* (la analítica no
> depende del modelo, de la red ni del reloj), *fail-fast con mensajes en
> español*, *el docente no ve lo que no debe*.
> **No se escribe código en este documento**: es diseño.

---

## 0. Resumen de decisiones

| # | Decisión | Por qué |
|---|---|---|
| D1 | El cálculo se hace con un **`AnalyticsAccumulator` incremental** maintained en memoria, y no con `AnalyticsAnalyzer.analyze` sobre listas completas. | `analyze` es O(S·M) (§1.1) y, peor, exige materializar los 1M mensajes en RAM: 560 MB con el texto incluido. |
| D2 | `AnalyticsAnalyzer.analyze` **se conserva como fachada**: se reimplementa encima del acumulador (`analyze = replay + freeze`). | Una sola implementación ⇒ la función pura sigue siendo el oráculo de los tests y no puede divergir del pipeline. |
| D3 | El acumulador mantiene **un cubo por usuario, independiente de la visibilidad**; la visibilidad se aplica en el *freeze*. | Cambiar la visibilidad de un alumno pasa de "recalcular 1M mensajes" a "re-freeze" (≈2 ms). Es la regla que hace que la invalidación sea barata. |
| D4 | Cada mensaje nuevo se incorpora en **O(1) amortizado** (`onMessage`); el costo total del acumulador es O(S·(d+s+t)) y **no depende del número de mensajes**. | La memoria del estado es ≈2.5–8 MB para 1M mensajes (§9.3). |
| D5 | El estado del acumulador se persiste en un **archivo binario versionado** (`state.bin`) en `cache/analytics/`, con CRC32 y escritura atómica (tmp + rename). | 1.1 MB de estado, decodificación en 20–35 ms. El JSON (`KitoruJson`) es *prettyPrint*: 3.2 MB y 60–90 ms. |
| D6 | La clave de invalidación **no es "el hash del último mensaje"** sino un **watermark compuesto**: `(version, appendSeq, storeRevision, messageCount)`. | Un hash del último mensaje no detecta un borrado en el medio ni una edición. `appendSeq` + `storeRevision` sí, y además distinguen *anexar* de *mutar* (§5.2). |
| D7 | Se distingue **cambio incremental** (`onMessage`, O(1)), **re-freeze** (visibilidad/rol/inscripción, O(S·d)), **rebuild por usuario** (borrado/edición, O(mensajes del usuario) ≈ M/S) y **rebuild completo** (caché ausente o corrupta, O(M)). | Cuatro granularidades que cubren el 99% de los casos con el mínimo trabajo (§6). |
| D8 | El store de mensajes pasa a **log append-only NDJSON por cátedra** (`courses/<id>/messages.ndjson`) con `seq` monotónico y un `manifest.json`. | El `messages.json` actual reescribe el archivo entero en cada `save` ⇒ O(M²) y 250 MB de reescritura por mensaje. Es el prerrequisito de todo lo demás (§3). |
| D9 | Toda mutación del acumulador ocurre en **un solo actor** (`Dispatchers.Default.limitedParallelism(1)`); el `Mutex` queda sólo para el archivo de caché, como ya hace `JsonStore`. | El camino caliente (`onMessage`) no compite por el cerrojo; el `Mutex` no es reentrante y usarlo para todo hace ping-pong entre hilos (§7). |
| D10 | La UI consume un **`StateFlow<AnalyticsUiState>`** por cátedra; el estado de caché se publica al instante y se marca `stale` mientras se recalcula. | La pantalla nunca bloquea: pinta la última analítica conocida y después se refresca (§7.2, §9.2). |
| D11 | El **alumno** calcula sus propias métricas localmente, sin red y sin aplicar la visibilidad (la visibilidad gobierna lo que ve el *docente*, no lo que ve el alumno sobre sí mismo). El **docente** calcula el agregado aplicando la visibilidad. | Mismo código (`AnalyticsAccumulator`), distinto ámbito y política (§8). |
| D12 | Las escrituras de caché son **debounced** (5 s) y atómicas; el rebuild completo se hace en segundo plano con progreso. | Evitar I/O por mensaje (§7.5) y no bloquear la UI en el primer arranque (§9.2). |

### 0.1 Bugs detectados en `AnalyticsAnalyzer.analyze` (y qué hacemos)

El diseño del acumulador es **bit-compatible** con `analyze` salvo por tres
desvíos deliberados, que se documentan aquí para que la migración sea
explícita:

| # | Hallazgo | Ubicación | Decisión |
|---|---|---|---|
| B1 | `topSources` **ignora la visibilidad**: cuenta las fuentes de las respuestas de alumnos con `visibility = NONE`. Es una fuga: el docente ve un dato de un alumno que pidió ocultamiento. | `AnalyticsAnalyzer.kt:69-80` | **Se corrige** en el acumulador (las fuentes se atribuyen al autor del hilo y el freeze sólo suma los visibles). Se actualiza `analyze` en el mismo PR y su test. |
| B2 | `perDay` cuenta **cualquier mensaje que no sea de usuario** como "respuesta" (`if isUser ... else replies+1`), mientras que `StudentAnalytics.replies` sólo cuenta los del asistente. Con roles `"user"`/`"assistant"` no hay diferencia; con un rol nuevo, sí. | `AnalyticsAnalyzer.kt:91` vs `:131` | Se **replica** tal cual (para no romper la reproducibilidad) y se deja anotado como deuda: al agregar roles nuevos hay que alinear ambas reglas. |
| B3 | `avgLatencyMs` del curso trunca la media **por alumno** antes de ponderar (`sum(avg_i * replies_i) / sum(replies_i)`). | `AnalyticsAnalyzer.kt:97-98` | Se **replica** tal cual. La versión exacta (`sum(latencySum_i) / sum(replies_i)`) difiere en ±1 ms; se propone corregir en ambos a la vez, con su propio changelog. |
| B4 | El filtro por alumno es O(S·M): `messages.filter { it.threadId in userThreadIds }` dentro de un `map` sobre las inscripciones. | `AnalyticsAnalyzer.kt:50` | **Se elimina**: el acumulador indexa por usuario al escribir. |

---

## 1. El problema, con números

### 1.1 Lo que realmente cuesta `analyze`

`AnalyticsAnalyzer.analyze` no es O(M): es **O(S·M)**. En
`AnalyticsAnalyzer.kt:47-52` se recorre la lista de inscripciones y, por cada
inscripción, se filtra la lista **completa** de mensajes:

```kotlin
val perStudent = visible.map { enrollment ->
    val userThreadIds = userThreads.map { it.id }.toSet()   // Set nuevo por alumno
    val userMessages = messages.filter { it.threadId in userThreadIds }  // O(M) por alumno
    studentStats(enrollment, userThreads, userMessages)
}
```

Con S = 500 alumnos y M = 1.000.000 de mensajes:

| Operación | Costo | En desktop (JVM) | En Android gama media |
|---|---|---|---|
| `messages.groupBy { it.threadId }` | O(M) + 20 000 listas | 0.8–1.5 s | 2–4 s |
| `threads.groupBy { it.authorId }` | O(T) | ~10 ms | ~40 ms |
| `messages.filter` por alumno | **S × M = 5·10⁸** pasos | 12–25 s | 40–90 s |
| `studentStats` (filtros y `.distinct()` por alumno) | O(S·M) otra vez | +10–20 s | +40–80 s |
| **Total** | | **~30–60 s** | **~2–4 min** |

Y eso **sin contar la memoria**, que es lo que mata de verdad:

| Objeto | Tamaño unitario | Total (1M mensajes) |
|---|---|---|
| `ChatMessage` (objeto) | ~80 B | 80 MB |
| `text` (String, ~400 chars UTF-16) | ~830 B | 830 MB |
| `id` + `threadId` (String) | ~100 B | 100 MB |
| **Total resident** | | **~1 GB** |

Un `ChatMessage` con el texto de una respuesta real del modelo ocupa ~1 KB.
1M de mensajes ≈ **1 GB en RAM**. Android typical heap: 256–512 MB ⇒ **OOM
garantizado** antes de terminar el cálculo. Los 100k mensajes del enunciado
(200 × 500) ya dan ~100 MB: viable en desktop, inviable en Android.

### 1.2 El verdadero cuello de botella es la *lectura*, no el cálculo

Aunque el cálculo fuese gratis, el store actual impide llegar a 200 ms:

- `FileMessageRepository.save` → `JsonListStore.update` → **lee el JSON entero,
  filtra y reescribe el archivo entero**. Con M = 1M y `prettyPrint = true`:
  archivo de ~250–500 MB, reescrito **por cada mensaje**. Total del curso:
  O(M²) ⇒ 1M × 300 MB = 300 TB de I/O. Inviable.
- `FileMessageRepository.listForCourse` devuelve `List<ChatMessage>`: obliga a
  materializar los 1M mensajes (§1.1). Además filtra por
  `threadId.startsWith("$courseId|")`, o sea O(M) con comparaciones de
  prefijo.
- `KitoruJson` tiene `prettyPrint = true`: el archivo es ~1.6× más grande de lo
  necesario y parsearlo con kotlinx.serialization va a ~60 MB/s ⇒ **4–8 s**
  solo en parsear.

**Conclusión:** el pipeline no puede construirse *encima* del store actual. El
almacenamiento es el prerrequisito (§3), y la caché de métricas es lo que
evita volver a leer el log.

### 1.3 Qué hay que construir

```
                   ┌─ append ──────────────────────────────┐
                   │                                        │
  CourseStore ─────┘                                        │
  (NDJSON + seq)                                             │
      │ events (SharedFlow)                                 │
      ▼                                                     │
  AnalyticsActor (1 hilo)                                   │
      │                                                     │
      ├─ AnalyticsAccumulator  ──(onMessage, O(1))──────────┘
      │        │
      │        ├─ freeze() → CourseAnalytics  ──→ StateFlow ──→ UI
      │        │
      │        └─ encode() → state.bin (debounced 5 s) ──→ cache/analytics/
      │
      └─ lectura en streaming del log sólo para el TAIL (seq > watermark)
```

Tres propiedades de este pipeline:

1. **El log de mensajes se lee incrementalmente, no completo.** Al abrir la app
   se lee el *estado* (1.1 MB), no los 250 MB de mensajes.
2. **El costo de una consulta de métricas no depende de M**, sino de
   S·(días + fuentes + hilos) ≈ 100k operaciones.
3. **El único O(M) real es el primer build** (o un rebuild forzado), y ocurre
   en segundo plano, en streaming, con progreso.

---

## 2. Arquitectura

### 2.1 Capas

| Capa | Tipo | Responsabilidad | Costo típico |
|---|---|---|---|
| **L0 · Fuente de verdad** | `CourseStore` (nuevo) | Mensajes, hilos e inscripciones de una cátedra. Log append-only + manifiesto. `seq` monotónico. | Append O(1), lectura por cola O(k) |
| **L1 · Acumulador** | `AnalyticsAccumulator` | Estado agregado en memoria: cubo por usuario. | `onMessage` O(1), memoria O(S·(d+s+t)) |
| **L2 · Vista congelada** | `CourseAnalytics` | Lo que ve la UI. Se produce con `freeze()`. | O(S·d + S·s) ≈ 2–4 ms |
| **L3 · Caché en disco** | `state.bin` + `snapshot.json` | Sobrevive al cierre de la app. | 1.1 MB, 25 ms de decode |

### 2.2 Quién produce qué

| Consumidor | Ámbito del acumulador | Política | Caché en disco |
|---|---|---|---|
| Alumno (panel "Mi actividad") | sólo sus mensajes | `SELF(userId)`: **no** aplica visibilidad | No (son ~2 000 mensajes; se recalcula en < 5 ms) |
| Docente (pantalla "Analítica") | todos los mensajes presentes localmente | `TEACHER`: aplica `showsMetrics` y rol | Sí, por cátedra |

Un mismo proceso puede ser las dos cosas (un docente que además se inscribió
como alumno). Los dos acumuladores son instancias distintas del mismo tipo.

---

## 3. Prerrequisito 0 · el almacenamiento de mensajes

> Sin esto, no hay watermark (§5.2) ni lectura en streaming (§1.3), y el
> pipeline no existe. El cambio es **interno**: las interfaces
> `MessageRepository` / `ThreadRepository` / `EnrollmentRepository` no cambian
> (excepto por una extensión explícita, §3.4), de modo que la UI no se entera.

### 3.1 Layout en disco

```
<dataDir>/
  config/                         (lo que ya hay: courses.json, profiles.json, ...)
  courses/
    <courseId>/
      manifest.json               ← nuevo: estado del store (§3.3)
      threads.json                ← nuevo: hilos de LA cátedra (no todos)
      enrollments.json            ← nuevo: inscripciones de LA cátedra
      materials.json              ← nuevo: materiales de LA cátedra
      messages.ndjson             ← nuevo: log append-only de mensajes
  cache/
    analytics/
      <safeCourseId>-<hash8>.state    ← estado del acumulador (binario)
      <safeCourseId>-<hash8>.snapshot.json  ← CourseAnalytics congelado
```

El `courseId` no se usa crudo en el nombre de archivo (puede traer `|`, `/`,
`:`). Se sanitiza a `[A-Za-z0-9_-]` y se le anexa un hash de 8 hex del
`courseId` original para evitar colisiones.

### 3.2 Formato del log de mensajes

Una línea por mensaje, JSON **compacto** (sin `prettyPrint`), con el `seq`
como envoltorio:

```json
{"s":123456,"m":{"id":"m_9f2a","threadId":"c1|t_0042","role":"assistant","text":"…","createdAt":1767225600123,"sources":["materiales/IA-2026-1.pdf#p12"],"citation":{"verified":2,"unsupported":0,"unknown":0},"toolCalls":[],"latencyMs":1830,"tokensOut":412,"tokensIn":58}}
```

| Campo | Tipo | Nota |
|---|---|---|
| `s` | `u64` | `seq`: monotónico **por cátedra**, sin huecos. Es el cursor de la cola. |
| `m` | `ChatMessage` | El dominio, sin cambios. |

Propiedades:

- **Append-only**: `save()` escribe **una línea** al final (O(1)). Nunca
  reescribe el archivo.
- **El `seq` se asigna al escribir**, no al leer. Un mensaje importado con fecha
  vieja recibe `seq` nuevo ⇒ la cola siempre es "los últimos `k` mensajes
  escritos", que es exactamente lo que el acumulador necesita.
- **Un mensaje editado o borrado** no se borra del log: se escribe una línea de
  **tombstone** (`{"t":123}` = "el mensaje con seq 123 ya no está"). El
  tombstone **no consume `seq`** (los `seq` siguen siendo correlativos y sin
  huecos); es la forma barata de "deshacer" sin reescribir 250 MB.

### 3.3 El manifiesto

```json
{
  "version": 2,
  "courseId": "c1",
  "appendSeq": 123456,
  "messageCount": 123000,
  "threadCount": 20000,
  "storeRevision": 123456,
  "enrollmentHash": 8123456789,
  "logBytes": 253214567,
  "compactedAt": 1767225600000
}
```

| Campo | Para qué |
|---|---|
| `appendSeq` | último `seq` **vivo** (no tombstone) del log |
| `messageCount` | cantidad de mensajes vivos ⇒ detecta borrados |
| `storeRevision` | **contador monótono que sube con cualquier mutación** (append, delete, edit, import). Es la pieza que permite distinguir "solo se anexó" de "se mutó". |
| `enrollmentHash` | hash FNV-1a 64 de la lista ordenada de `(userId, role, visibility, displayName)`. |

### 3.4 API del store (nueva, en `kitoru.domain` o `kitoru.store`)

```kotlin
class CourseStore(
    private val fs: FileSystem,
    private val scope: CoroutineScope,
    val courseId: String,
) {
    val manifest: StoreManifest                 // en memoria, actualizado en cada cambio
    val enrollments: List<Enrollment>           // en memoria (≤ 500 filas)
    val threads: List<ChatThread>               // en memoria (≤ 20 000 filas ≈ 4 MB)
    val events: SharedFlow<StoreEvent>          // Appended / Edited / Deleted / Imported

    suspend fun append(message: ChatMessage): Long            // → seq
    suspend fun markDeleted(seq: Long)
    suspend fun markEdited(seq: Long, message: ChatMessage)
    suspend fun importMessages(batch: List<ChatMessage>): ImportResult

    /** Lee **en streaming** los mensajes con seq > from. Nunca materializa la lista. */
    suspend fun forEachSince(from: Long, sink: (SeqMessage) -> Unit)

    /** Borra todos los mensajes de un hilo (lo usa el repo al borrar un hilo). */
    suspend fun deleteThread(threadId: String)
}
```

`forEachSince` es la clave: **el rebuild completo recorre el log línea a línea
alimentando el acumulador, con memoria constante**. Nunca se construye una
`List<ChatMessage>` de 1M elementos.

### 3.5 Extensiones a `FileSystem` (mínimo, portable)

```kotlin
interface FileSystem {
    // … lo que ya existe …
    /** Lee el archivo línea a línea, sin cargarlo entero en memoria. */
    fun readLines(path: FilePath, onLine: (String) -> Unit)
    /** Añade texto al final del archivo (append). */
    fun appendText(path: FilePath, text: String)
    /** Renombra (reemplazo atómico en el mismo volumen). */
    fun rename(from: FilePath, to: FilePath): Boolean
}
```

Implementación en `jvmCommonMain` con `java.io`: `BufferedReader.forEachLine`,
`FileWriter(path, append = true)`, `File.renameTo`. `java.io` está disponible
en Android desde la API 1, así que no hace falta desugaring.

> **Nota de alcance**: `readText` se mantiene para los archivos chicos
> (`courses.json`, `profiles.json`). Para el log de mensajes, `readText` está
> prohibido por OOM (§1.1).

### 3.6 Compaction del log

El log crece sin límite (con tombstones y con mensajes borrados). Compactación:

- **Disparador**: `logBytes > 64 MB` **y** `tombstones > 10%` de las líneas.
- **Acción**: reescribir el log con sólo los mensajes vivos, renumerando `seq`
  de forma **monótona y creciente** (`seq' = seqBase + i`), y actualizando
  `appendSeq`, `messageCount` y `storeRevision` en el manifiesto.
- **Efecto sobre la caché**: la compactación cambia el `seq` de los mensajes ⇒
  el watermark de la caché queda desfasado ⇒ el motor detecta que
  `storeRevision` no se explica por anexos y hace un **rebuild completo**.
  Por eso la compactación se hace **en horario de baja actividad** y se
  fuerza el rebuild de la caché justo después.

---

## 4. Índices incrementales · `AnalyticsAccumulator`

### 4.1 Qué mantiene en memoria

El acumulador es **una tabla hash de cubos por usuario**. Nada más: no guarda
mensajes, ni hilos completos, ni texto.

```
AnalyticsAccumulator
├── users: LinkedHashMap<String, UserBucket>       // 1 entrada por usuario visto
├── enrollments: LinkedHashMap<String, Enrollment>  // 1 entrada por inscripción (orden de carga)
├── threadAuthors: HashMap<String, String>          // threadId → userId
├── storeRevision: Long                            // revisión del store replicada
├── appendSeq: Long                                // último seq incorporado
└── messageCount: Long

UserBucket
├── userId, displayName, role, visibility           // identidad y política (del Enrollment)
├── messages, replies                              // contadores de volumen
├── tokensIn, tokensOut                            // tokens
├── latencySum: Long                               // suma de latencias (para la media)
├── citationsVerified/Unsupported/Unknown          // calidad de citas
├── answersWithUnsupported                         // respuestas con ≥1 cita sin respaldo
├── toolCalls, toolFailures                        // herramientas
├── lastActivity: Long                             // max(createdAt)
├── lastSeq: Long                                  // último seq procesado
├── threadIds: MutableSet<String>                  // hilos del usuario  → `threads`
├── days: MutableMap<Long, DayCounts>              // día → (msgs, replies, activity)
└── sources: MutableMap<String, Int>               // etiqueta de fuente → veces
```

#### Por qué estas estructuras y no otras

| Necesidad | Estructura | Costo por mensaje | Memoria (500 u / 120 d / 30 src / 40 hilos) |
|---|---|---|---|
| `messages`, `replies`, tokens, citas, herramientas | contadores `Int`/`Long` en el cubo | O(1) | 500 × ~96 B ≈ **48 KB** |
| `threads` del alumno | `MutableSet<String>` (ids de hilo) | O(1) amortizado | 20 000 entradas ≈ **2.7 MB** (con `String` propios) / **640 KB** (con *interning*) |
| `activeDays` **y** `perDay` | `MutableMap<Long, DayCounts>` por usuario | O(1) amortizado | 60 000 entradas ≈ **4.3 MB** (HashMap) / **1.0 MB** (mapa abierto compacto) |
| `topSources` **con visibilidad** | `MutableMap<String, Int>` **por usuario** | O(1) amortizado | 15 000 entradas ≈ **720 KB** |
| `lastActivity` | `max` sobre un `Long` | O(1) | incluido |

**Punto clave**: las fuentes y los días se guardan **por usuario**, no
globales. Por eso el freeze puede aplicar la visibilidad sin volver a leer un
solo mensaje (§4.3). Si fueran globales, un alumno con `visibility = NONE`
dejaría de ser excluible sin recalcular (y además se filtraría su actividad
diaria).

#### Optimizaciones de memoria (opcionales, midiendo)

1. **Interning** de ids de hilo y de etiquetas de fuente en una tabla global
   del acumulador (`HashMap<String, Int>` + `ArrayList<String>`): evita 20 000
   `String` duplicados ⇒ ahorra ~2 MB.
2. **Mapa abierto compacto** `Long → Int` para los días (implementado en
   `kitoru.platform`, 40 líneas, portable): 16 B/entrada en vez de 72 B ⇒
   ahorra ~3 MB.
3. `java.util.BitSet` NO es usable en `commonMain` (no está en el stdlib
   común): si se quiere, un `LongOpenHashSet` propio.

Los números de §9.3 se dan para las colecciones estándar de Kotlin (sin
optimizaciones) y luego con las optimizaciones.

### 4.2 Operaciones y sus costos

| Operación | Firma | Costo | Cuándo se usa |
|---|---|---|---|
| `onThread` | `(ChatThread) -> Unit` | O(1) | hilo creado |
| `onMessage` | `(ChatMessage, authorId: String, seq: Long) -> Unit` | **O(1) amortizado** | cada mensaje nuevo |
| `onEnrollment` | `(Enrollment) -> Unit` | O(1) | alta / cambio de inscripción |
| `removeEnrollment` | `(userId: String) -> Unit` | O(1) | baja |
| `rebuildUser` | `(userId, threads, messages) -> Unit` | O(msgs del usuario) ≈ M/S | borrado / edición / import con colisión |
| `freeze` | `(Course) -> CourseAnalytics` | O(S·d + S·s) ≈ 100k ops | lectura de métricas |
| `studentOf` | `(userId) -> StudentAnalytics?` | O(d) | panel del alumno |

`onMessage` en detalle (todo O(1) amortizado):

```kotlin
fun onMessage(message: ChatMessage, authorId: String, seq: Long = 0L) {
    val b = bucketOf(authorId)
    val day = AnalyticsAnalyzer.dayStart(message.createdAt)
    val d = b.days.getOrPut(day) { DayCounts() }
    d.activity++
    if (message.isUser) {
        b.messages++; d.messages++
        b.tokensIn += message.tokensIn
    } else {
        b.replies++; d.replies++
        b.tokensOut += message.tokensOut
        b.latencySum += message.latencyMs
        message.citation?.let { c ->
            b.citationsVerified += c.verified
            b.citationsUnsupported += c.unsupported
            b.citationsUnknown += c.unknown
            if (c.unsupported > 0 || c.unknown > 0) b.answersWithUnsupported++
        }
        b.toolCalls += message.toolCalls.size
        b.toolFailures += message.toolCalls.count { it.ok == false }
        for (s in message.sources) b.sources[s] = (b.sources[s] ?: 0) + 1
    }
    if (message.createdAt > b.lastActivity) b.lastActivity = message.createdAt
    if (seq > b.lastSeq) b.lastSeq = seq
    messageCount++
    if (seq > appendSeq) appendSeq = seq
}
```

Observaciones:

- `d.replies++` **sólo** para `isAssistant`, pero el `perDay` del analizador
  cuenta *cualquier* no-usuario como respuesta (bug B2). Para ser
  bit-compatible hay que usar `if (message.isUser) d.messages++ else d.replies++`.
  En el código final se replica B2 y se deja el `// TODO(B2)` al lado.
- `sources` se atribuyen al **autor del hilo** (el que se pasa como
  `authorId`), no al autor del mensaje: las respuestas del asistente
  pertenecen al hilo del alumno. Por eso el freeze puede excluirlos si el
  alumno no es visible.
- `lastActivity` es el `max(createdAt)` de **todos** los mensajes del usuario
  (incluidos los del asistente), igual que en `analyze`.

### 4.3 El *freeze*: de `AnalyticsAccumulator` a `CourseAnalytics`

`freeze(course)` es **una función pura del estado acumulado**:

```kotlin
fun freeze(course: Course): CourseAnalytics {
    val visible = enrollments.values.filter { it.visibility.showsMetrics }   // orden de carga preservado
    val perStudent = visible.map { e -> studentAnalytics(e, users[e.userId]) }
        .sortedByDescending { it.messages }
    val students = perStudent.filter { it.role == CourseRole.ALUMNO }
    val teachers = perStudent.count { it.role != CourseRole.ALUMNO }

    // … totales, citas, latencia, tokens: idénticos a analyze() …
    val topSources = aggregateSources(visible)   // sólo visibles (B1 corregido)
    val perDay = aggregateDays(visible)           // sólo visibles
    return CourseAnalytics(/* … */)
}
```

Costo: `|inscripciones| × |días por alumno| + |inscripciones| × |fuentes por alumno|`
= 500 × 120 + 500 × 30 = **75 000 operaciones** ≈ **2–4 ms**.

El freeze se puede ejecutar tantas veces como haga falta: es barato. Por eso
la **visibilidad, el rol y el alta/baja de inscripciones son invalidaciones de
segundo nivel** (§6): no tocan los mensajes.

### 4.4 Equivalencia exacta con `AnalyticsAnalyzer.analyze`

El acumulador se valida contra un **oráculo de referencia**: una copia
**literal** de la implementación actual de `analyze`, que se conserva **sólo en
los sources de test** (`core/src/commonTest/.../AnalyticsAnalyzerReference.kt`)
con el fin de demostrar que la refactorización no cambia los números.

```kotlin
// core/src/commonTest/kotlin/kitoru/domain/AnalyticsAnalyzerReference.kt
// Copia literal del AnalyticsAnalyzer actual (antes de la refactorización).
// Vive en los tests: es el oráculo de la equivalencia.
object AnalyticsAnalyzerReference { fun analyze(/* … */): CourseAnalytics { /* … */ } }
```

```kotlin
@Test fun `el acumulador reproduce al analizador anterior`() {
    val (course, enrollments, threads, messages) = Corpus.random(seed = 42)
    val expected = AnalyticsAnalyzerReference.analyze(course, enrollments, threads, messages)
    val acc = AnalyticsAccumulator(course.id)
    val authorOf = threads.associate { it.id to it.authorId }
    enrollments.forEach(acc::onEnrollment)
    threads.forEach(acc::onThread)
    messages.forEach { acc.onMessage(it, authorOf.getValue(it.threadId)) }
    val actual = acc.freeze(course)
    // Sólo difiere en B1 (topSources ya no incluye a los alumnos ocultos).
    assertEquals(expected.copy(topSources = actual.topSources), actual)
}
```

Una vez probada la equivalencia, lo más limpio es **reimplementar `analyze`
sobre el acumulador** (decisión D2):

```kotlin
object AnalyticsAnalyzer {
    fun analyze(course, enrollments, threads, messages): CourseAnalytics {
        val acc = AnalyticsAccumulator(course.id)
        enrollments.forEach(acc::onEnrollment)
        threads.forEach(acc::onThread)
        messages.forEach { acc.onMessage(it, authorOf(threads, it.threadId)) }
        return acc.freeze(course)
    }
}
```

Ventajas: una sola implementación, la función pura sigue siendo el oráculo de
los tests, el costo baja de O(S·M) a O(M), y la divergencia entre "el análisis
puro" y "el pipeline" es imposible por construcción.

> `analyze` **no** se usa en producción: el motor usa el acumulador
> incremental. Se usa en tests y como camino de emergencia (rebuild).

### 4.5 Qué NO mantiene el acumulador

| No se mantiene | Por qué | Dónde vive |
|---|---|---|
| Texto de los mensajes | 830 B/mensaje ⇒ 830 MB | `messages.ndjson` |
| Los mensajes en sí | O(M) en RAM | `messages.ndjson` (log) |
| Los hilos completos | 4 MB, se releen del store | `threads.json` |
| Las inscripciones completas | 500 filas | `enrollments.json` |
| Cualquier marca de tiempo "de cálculo" | Determinismo | `computedAt` va en el estado de UI, no en las métricas |

---

## 5. Caché en disco

### 5.1 Qué se cachea (dos artefactos, responsabilidades distintas)

| Artefacto | Formato | Tamaño (1M msj) | Para qué | Cuándo se escribe |
|---|---|---|---|---|
| `cache/analytics/<id>.state` | **binario** versionado | **≈1.1 MB** | Reanudar el acumulador sin releer mensajes | Debounced 5 s + al salir de la app |
| `cache/analytics/<id>.snapshot.json` | JSON compacto | ≈210 KB | Pintar la pantalla al instante aunque el estado falte; diagnóstico legible | Junto con el estado |

El `snapshot.json` **no** es la caché del pipeline: es un atajo de *pintado
temprano* y una herramienta de diagnóstico. Si falta, no pasa nada: el estado
lo produce `freeze()` en 2–4 ms.

### 5.2 La clave de invalidación

> **No** se usa "el hash del último mensaje". Un hash del último mensaje no
> detecta un borrado en el medio, ni una edición de un mensaje viejo, ni un
> cambio de visibilidad, ni una importación que inserte mensajes viejos.

La clave es un **watermark compuesto**, guardado en la cabecera del estado:

```
AnalyticsWatermark {
    formatVersion : Int      // versión del formato de state.bin
    courseId      : String
    appendSeq     : Long     // último seq incorporado al acumulador
    messageCount  : Long     // mensajes vivos conocidos
    storeRevision : Long     // revisión del store en el momento del build
    threadCount   : Int      // hilos conocidos
    enrollmentHash: Long     // huella de (userId, role, visibility, displayName)
    builtAt       : Long     // informativo, NO entra en la validación
}
```

**Regla de validez** (evaluada al abrir la app, en ~2 ms):

```
el estado sirve  ⟺  state.formatVersion == VERSION
                ∧  state.appendSeq <= store.appendSeq
                ∧  state.storeRevision == store.storeRevision
                                     - (store.appendSeq - state.appendSeq)
```

La tercera conjunción es la importante: dice *"entre el build del estado y
ahora, lo único que cambió en el store fueron anexos"*. Si el store subió la
revisión por un borrado o una edición, la igualdad no se cumple y hay que
reconstruir.

| Situación | Acción | Costo |
|---|---|---|
| `appendSeq` de la caché < `appendSeq` del store, y la revisión lo explica | **catch-up incremental**: leer sólo `seq > state.appendSeq` y llamar `onMessage` | O(k) con k = mensajes nuevos |
| La revisión no lo explica (borrado, edición, import con colisión) | **rebuild completo** (§6) | O(M), en background |
| `formatVersion` distinto | **rebuild completo** | O(M) |
| Archivo corrupto (CRC no coincide) | **rebuild completo** | O(M) |

`enrollmentHash` **no invalida el estado**: como la visibilidad y el rol se
aplican en el freeze (§4.3), un cambio de inscripción sólo obliga a re-freezar
(2–4 ms). Se guarda igual, para diagnóstico y para detectar que la caché se
construyó con otra lista de inscripciones.

### 5.3 Formato de `state.bin` (binario, portable, versionado)

 little-endian, sin dependencias de `java.nio`:

```
┌─ CABECERA (48 bytes) ──────────────────────────────────────────────┐
│ magic      : 4 B   "KIT1"                                          │
│ version    : u16  (= 1)                                            │
│ flags      : u16  (bit0 = tiene threads, bit1 = tiene days, …)     │
│ courseIdLen: u16                                                    │
│ courseId   : u16 × courseIdLen  (UTF-8)                            │
│ appendSeq  : u64                                                   │
│ messageCnt : u64                                                   │
│ storeRev   : u64                                                   │
│ enrollHash : u64                                                   │
│ payloadLen : u32                                                   │
│ crc32      : u32  (CRC32 del payload)                              │
└─────────────────────────────────────────────────────────────────────┘
┌─ PAYLOAD ──────────────────────────────────────────────────────────┐
│ userCount   : u32                                                  │
│ labelTable  : u32 + (u16 len + UTF-8) × labelTable   ← interning  │
│ por usuario:                                                       │
│   userIdLen : u16 + UTF-8                                         │
│   role      : u8    (0=ALUMNO,1=AYUDANTE,2=TITULAR)               │
│   visibility: u8    (0=FULL,1=METRICS_ONLY,2=NONE)                 │
│   counters  : messages u32, replies u32, tokensIn u32,            │
│               tokensOut u32, latencySum u64, citVer u32,           │
│               citUnsup u32, citUnk u32, ansUnsup u32,              │
│               toolCalls u32, toolFailures u32, lastActivity u64,    │
│               lastSeq u64                        (56 bytes fijos)   │
│   threadCount : u32 + (u16 len + UTF-8) × threadCount              │
│   dayCount    : u32 + (varint delta-de-día + u32 msgs               │
│                        + u32 replies + u32 activity) × dayCount     │
│   sourceCount : u32 + (u16 labelIdx + varint count) × sourceCount  │
└─────────────────────────────────────────────────────────────────────┘
```

Detalles:

- **Deltas de día**: dentro de cada usuario los días van ordenados
  ascendentemente y se codifica el *delta* contra el día anterior
  (`varint`, 1–2 bytes). Como los días de un alumno se encuadran en una
  ventana de ~120, el delta es casi siempre 1 ⇒ 1 byte por día.
- **Interning de etiquetas**: las fuentes ("materiales/IA-2026-1.pdf#p12") se
  repiten entre alumnos. Una tabla global (`labelTable`) los codifica como
  `u16` ⇒ 15 000 entradas × 2 B en vez de × 40 B.
- **CRC32**: implementarlo es 30 líneas (polinomioIEEE, tabla de 256
  entradas) y detecta truncamientos y corrupción parcial. Es la misma
  robustez que el `try/catch` de `JsonStore._read`, pero más barato de
  verificar.
- **Los contadores por usuario son de tamaño fijo** (u32/u64, 56 B) porque son
  pocos y se leen directo; **las multiplicidades** (días, fuentes) usan `varint`
  (`unsigned LEB128`) porque son muchas y casi siempre valen 1. Con 500 alumnos
  el archivo ronda **1.1 MB**.

### 5.4 El `snapshot.json`

Usa un `Json` **compacto** (el `KitoruJson` del repo tiene `prettyPrint = true`;
hace falta uno nuevo, p. ej. `KitoruCompactJson`, o pasar `prettyPrint` como
configuración por instancia):

```json
{
  "formatVersion": 1,
  "courseId": "c1",
  "computedAt": 1767225600123,
  "watermark": { "appendSeq": 123456, "storeRevision": 123456, "enrollmentHash": 8123456789 },
  "analytics": { /* CourseAnalytics, con sus @Serializable actuales */ }
}
```

### 5.5 Escritura atómica y corrupción

- Se escribe primero en `<id>.state.tmp` y luego `rename` (atómico en el mismo
  volumen). Si se corta la app a mitad de escritura, el `.state` viejo sigue
  intacto.
- Al leer: se valida el *magic*, la versión, el `payloadLen` y el CRC32. Si
  algo falla ⇒ el archivo se descarta silenciosamente (igual que hace
  `JsonStore._read`, que devuelve `empty()` ante un JSON roto) y se dispara un
  rebuild completo. **Nunca se rompe la app por una caché corrupta.**
- El `.tmp` huérfano se borra al arrancar.

### 5.6 Tiempos de serialización (estimados; a confirmar con un benchmark en M2)

| Artefacto | Formato | Tamaño | Encode | Decode |
|---|---|---|---|---|
| Estado del acumulador | JSON pretty (referencia) | 3.2 MB | 45–70 ms | 60–90 ms |
| Estado del acumulador | JSON compacto | 1.8 MB | 25–40 ms | 35–55 ms |
| **Estado del acumulador** | **binario varint (§5.3)** | **1.1 MB** | **6–12 ms** | **20–35 ms** |
| `CourseAnalytics` | JSON compacto | 210 KB | 3–6 ms | 6–10 ms |
| `CourseAnalytics` | JSON pretty (`KitoruJson`) | 480 KB | 8–15 ms | 12–20 ms |

**Decisión**: el estado va en binario; el snapshot va en JSON compacto. La
diferencia (35 ms vs 8 ms de decode) es la que separa "cabe en el presupuesto
de 200 ms" de "no".

---

## 6. Invalidación

### 6.1 Tabla de reglas

| Evento | Detección | Acción | Costo (1M msj) |
|---|---|---|---|
| **Mensaje nuevo** (append) | evento `StoreEvent.Appended(seq)` | `accumulator.onMessage(...)` + re-freeze debounced | **O(1)** + 2–4 ms |
| **Mensaje editado** | `storeRevision` sube sin que `appendSeq` suba | `rebuildUser(autor)` leyendo el log de ese usuario | **O(M/S) ≈ 2 000** (≈ 2 ms) |
| **Mensaje borrado** | `StoreEvent.Deleted(seq)` + `messageCount` baja | `rebuildUser(autor)` | **O(M/S)** |
| **Hilo borrado** | `StoreEvent.ThreadDeleted` | quitar el id de hilo del cubo + `rebuildUser(autor)` | O(M/S) |
| **Visibilidad FULL ↔ METRICS_ONLY** | `enrollmentHash` cambia | **nada en el acumulador**; re-freeze | **O(S·d) ≈ 75 k** (2–4 ms) |
| **Visibilidad → NONE** | ídem | ídem: el cubo del alumno se excluye del freeze | O(S·d) |
| **Inscripción dada de baja** | ídem | ídem + purga opcional del log de ese usuario (§6.3) | O(S·d) + O(msjs) |
| **Rol ALUMNO ↔ AYUDANTE** | ídem | re-freeze (cambia `students`, `teachers`, `totalMessages`) | O(S·d) |
| **Material eliminado** | — | **ninguna**: las métricas usan las `CitationStats` ya verificadas y guardadas. No se re-verifica al vuelo | **0** |
| **Material editado / reindexado** | — | ninguna, salvo que se pida explícitamente **re-verificar citas** (acción del docente): O(mensajes con citas) | 0 / O(M) |
| **Importación de un paquete de chats** | `StoreEvent.Imported(newSeq, replacedIds)` | si el paquete **sólo anexa** ⇒ catch-up por `seq` (O(k)); si **reemplaza ids** ⇒ `rebuildUser` de los usuarios afectados | O(k) / O(msjs afectados) |
| **Cátedra borrada** | `CourseDeleted` | borrar `.state` y `.snapshot.json` | 0 |
| **`analyticsEnabled = false`** | flag del curso | no calcular; `freeze` devuelve un agregado vacío | 0 |
| **Caché corrupta / versión nueva** | magic/CRC/`formatVersion` | rebuild completo en background | **O(M)** |
| **Compactación del log** | `storeRevision` no explicable por anexos | rebuild completo (forzado por la compactación) | **O(M)** |

### 6.2 Las cuatro granularidades y por qué

| Granularidad | Disparador | Trabajo | Cuándo |
|---|---|---|---|
| **Incremental** (`onMessage`) | mensaje nuevo | O(1) | el 95% de las veces |
| **Re-freeze** | inscripción / visibilidad / rol | O(S·d + S·s) ≈ 75 k | el 4% |
| **Rebuild por usuario** | borrado, edición, import con colisión | O(msgs del usuario) ≈ M/S = 2 000 | el 0.9% |
| **Rebuild completo** | caché ausente/corrupta, compactación | O(M) | el 0.1% (una vez por instalación) |

La razón de las granularidades es la **asimetría de costos** en una cátedra real:

```
full rebuild : 1 000 000 ops   (× 1 vez)
rebuild user :     2 000 ops   (× 1 por alumno afectado)
re-freeze     :    75 000 ops   (× 1 por cambio de inscripción)
incremental   :         1 op   (× 1 por mensaje)
```

Con 500 alumnos, un rebuild por usuario es **500× más barato** que el full y
**37× más barato** que un re-freeze. Por eso borrar un mensaje re-construye
**sólo el cubo de su autor**, y no todo.

### 6.3 Dos casos que requieren criterio explícito

**a) Borrado de un mensaje.** El acumulador no guarda un delta por mensaje
(permitiría restar en O(1)), porque ese delta costaría ~80 B × 1M = **80 MB**
de RAM y de disco. Se acepta el costo de reconstruir el cubo del autor: 2 000
mensajes ≈ 2 ms. Alternativa descartada: guardar sólo un `Map<messageId, Contribution>`
—la memoria no lo justifica.

**b) Inscripción dada de baja (derecho a eliminar).** Excluir del agregado es
trivial (freeze). Pero los **mensajes** de ese alumno siguen en el log del
dispositivo del docente. Si el docente dio de baja a un alumno por solicitud de
ese alumno, hay que **purgar el log** de sus mensajes (reescribir el NDJSON sin
ellos ⇒ compactación forzada). Eso invalida la caché (rebuild completo). Es un
acto raro y deliberado: se documenta, no se automatiza en el camino caliente.

---

## 7. Concurrencia

### 7.1 Un solo actor para las mutaciones

Todas las mutaciones del acumulador ocurren en **un único hilo lógico**:

```kotlin
private val analyticsDispatcher = Dispatchers.Default.limitedParallelism(1)
```

`limitedParallelism(1)` crea un "hilo lógico" sobre el pool por defecto: en
Android no crea un thread extra (a diferencia de `newSingleThreadContext`) y en
desktop es barato. (Si la versión de coroutines lo marca como experimental,
se necesita `@OptIn(ExperimentalCoroutinesApi::class)`.)

**Por qué actor y no `Mutex` para todo:**

| | Actor (1 hilo) | `Mutex` |
|---|---|---|
| Contención | Nula: se encola | El mensaje nuevo y el freeze compiten |
| Orden | Determinista y FIFO | Depende del scheduler |
| Riesgo | Starvation si una operación bloquea | **No reentrante**: llamar a `suspend` que tome el mismo mutex ⇒ deadlock |
| Latencia | Una operación larga (p. ej. `freeze` de 3 ms) delaying la cola | Idem, pero además hay parking/unparking |

El `Mutex` queda **sólo para el archivo de caché** (escritura atómica), con el
mismo patrón que ya usa `JsonStore`: `read`/`write` con cerrojo, `_read`/`_write`
sin cerrojo.

### 7.2 El contrato con la UI: `StateFlow`

```kotlin
sealed interface AnalyticsUiState {
    data object Loading : AnalyticsUiState
    data class Ready(
        val analytics: CourseAnalytics,
        val stale: Boolean,        // true mientras se recalcula en background
        val computedAt: Long,
    ) : AnalyticsUiState
    data class Failed(val message: String) : AnalyticsUiState
}
```

```kotlin
/** Un único hilo lógico para todas las mutaciones del acumulador. */
private val analyticsDispatcher = Dispatchers.Default.limitedParallelism(1)

class CourseAnalyticsHolder(
    private val store: CourseStore,
    private val cache: AnalyticsCache,
    private val scope: CoroutineScope,
    private val course: Course,
    val courseId: String = course.id,
) {
    private val actorContext = scope.coroutineContext + analyticsDispatcher
    private val _state = MutableStateFlow<AnalyticsUiState>(AnalyticsUiState.Loading)
    val state: StateFlow<AnalyticsUiState> = _state.asStateFlow()

    private val accumulator = AnalyticsAccumulator(courseId)
    private var dirty = false
    private var rebuildJob: Job? = null
    private var persistJob: Job? = null

    /** Señal de "hay que recalcular", conflada: 5 000 eventos ⇒ 1 señal. */
    private val pending = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Flujo de eventos del store → acumulador. */
    fun start() {
        scope.launch(actorContext) {
            store.events.collect { ev -> onStoreEvent(ev) }
        }
        scope.launch(actorContext) {
            pending.debounce(300).collect { publish() }   // un freeze por ráfaga
        }
    }

    /** Carga la caché del disco (o dispara el rebuild) sin bloquear a nadie. */
    suspend fun warmUp() { /* ... */ }

    /** Congela y publica. Sólo se llama desde el actor. */
    private fun publish() {
        val analytics = accumulator.freeze(course)
        _state.value = AnalyticsUiState.Ready(analytics, stale = dirty, computedAt = now())
    }
}
```

La UI **nunca** llama a `analyze` ni espera un cálculo: hace

```kotlin
val uiState by holder.state.collectAsState()
```

y pinta:
- `Loading` ⇒ esqueleto.
- `Ready(stale = false)` ⇒ datos definitivos.
- `Ready(stale = true)` ⇒ datos de la caché + un chip "recalculando…".

### 7.3 Coalescing: ráfagas

Durante una importación o una sincronización pueden llegar 5 000 mensajes en un
segundo. Si cada uno dispara un `freeze` de 3 ms ⇒ 15 s de CPU quemada para
producir un único resultado.

Solución: un **flujo conflado + debounce**:

```kotlin
private val pending = MutableSharedFlow<Unit>(
    replay = 0,
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,   // = conflated
)

init {
    scope.launch(actorContext) {
        pending.debounce(300).collect { publish() }
    }
}
```

Concretamente:

1. `onStoreEvent(Appended)` ⇒ `accumulator.onMessage(...)` (O(1), inmediato) y
   `dirty = true`; después `pending.trySend(Unit)`.
2. `debounce(300)` ⇒ cuando el flujo queda 300 ms sin señal, se ejecuta
   `freeze` + se publica. Con 5 000 mensajes seguidos ⇒ **un** freeze al final.
3. `DROP_OLDEST` con `extraBufferCapacity = 1` ⇒ si llegan 5 000 señales, el
   buffer guarda **una**: el operador `debounce` nunca ve más de la última.

> Alternativa sin `Flow`: un `Channel(CONFLATED)` + un bucle con
> `withTimeoutOrNull(300)` que drena la cola hasta que esté 300 ms en
> silencio. Es lo mismo con dos piezas menos; se prefiere el flujo porque
> `debounce` ya está en `kotlinx-coroutines` estable desde 1.6.

El resultado que ve la UI es el mismo (el estado final), y el trabajo es
proporcional al resultado, no al ruido.

### 7.4 Persistencia debounced

```kotlin
private fun schedulePersist() {
    persistJob?.cancel()
    persistJob = scope.launch {
        delay(5_000)                       // agrupa ráfagas
        // El encode se hace en el actor: el acumulador no debe cambiar
        // mientras se serializa (6–12 ms).
        val payload = withContext(analyticsDispatcher) { encoder.encode(accumulator) }
        withContext(ioDispatcher) {        // el I/O no bloquea el actor
            fs.writeText(tmp, payload)     // 1.1 MB
            fs.rename(tmp, final)          // atómico
        }
    }
}
```

Además se persiste **al salir de la app** (evento de lifecycle `onStop`), que
es el momento natural para un `flush`.

Detalle de concurrencia: **el encode se hace bajo el actor** (para que el
acumulador no cambie mientras se serializa), pero **la escritura del archivo
se hace fuera**, en `ioDispatcher`. `JsonStore` usa el patrón inverso (todo
bajo el mismo `Mutex`); aquí se separa porque el estado es 20× más grande que
un `courses.json` y no queremos bloquear el actor 15 ms por escritura.

### 7.5 `Mutex`: dónde sí y dónde no

| Recurso | Cerrojo | Motivo |
|---|---|---|
| Acumulador (lectura/escritura) | **Actor** (hilo único) | evita contención en el camino caliente |
| `state.bin` | `Mutex` + `tmp`/`rename` | el `Mutex` no es reentrante: `read`/`write` con cerrojo, `_read`/`_write` sin cerrojo, como en `JsonStore` |
| `snapshot.json` | el mismo `Mutex` | consistencia entre los dos artefactos |
| `CourseStore` | `Mutex` del `JsonStore` (para el manifiesto) + log append con `synchronized` de JVM | el log es append-only: el `seq` se asigna bajo cerrojo |
| `StateFlow` | ninguno | `MutableStateFlow` es thread-safe por diseño |

### 7.6 Secuencia completa: el docente abre la pantalla de analítica

```
t=0 ms    UI → holder.state (subscription)
t=2 ms    holder: leer manifest.json (2 KB) + enrollmentHash del store
t=5 ms    leer state.bin (1.1 MB) del disco        ─┐
t=12 ms   decodificar state.bin (25 ms)              │→ acumulador en memoria
t=37 ms   catch-up: leer del log los mensajes con     │
          seq > appendSeq  (0 en el caso normal)      │
t=38 ms   freeze(course) → CourseAnalytics (3 ms)
t=41 ms   publicar en StateFlow → la UI ya puede pintar
t=41..130 ms   Compose: composición + primer frame (40–90 ms)
────────────────────────────────────────────────────
total ≈ 70–170 ms  ✅  (< 200 ms en desktop)
```

En Android el mismo camino mide ≈ 120–225 ms: el presupuesto lo cierra el
**precalentado** del `state.bin` de la última cátedra (el dato queda listo en
memoria ⇒ < 20 ms); el resto lo consume el primer frame de Compose (§9.2).

Si la caché **no** existe (primera vez):

```
t=0 ms      UI muestra esqueleto + "preparando analítica (0%)"
t=5 ms      rebuild en background: forEachSince(0) sobre el NDJSON, en streaming
            1M mensajes × (parse 3 µs + onMessage 0.1 µs) ≈ 3–5 s (desktop)
            ≈ 8–12 s (Android gama media)
t=5 s..     progreso (cada 20 000 mensajes → un tick de UI)
t=…         freeze + publicar + persistir
```

**El primer build es caro y es una sola vez.** Todo lo posterior es el camino
rápido de arriba.

---

## 8. Cliente del alumno vs. cliente del docente

### 8.1 El alumno

**Qué ve**: un panel "Mi actividad" con **sus propias** métricas:
mensajes enviados, respuestas recibidas, conversaciones abiertas, días activos,
última actividad, latencia media, tokens, herramientas usadas y la **calidad de
las respuestas** (`citationSupportRate`, `ungroundedAnswerRate`).

**Cómo se calcula**: localmente, en su dispositivo, con el **mismo**
`AnalyticsAccumulator`, pero como ámbito de **un solo usuario**:

```kotlin
val acc = AnalyticsAccumulator(course.id)
threads.filter { it.authorId == me }.forEach(acc::onThread)
messagesOfMine.forEach { acc.onMessage(it, me) }
val mine = acc.studentOf(me)!!    // → StudentAnalytics
```

| Métrica | Dónde | Costo (2 000 mensajes) |
|---|---|---|
| `StudentAnalytics` del alumno | **dispositivo del alumno** | build < 5 ms, freeze < 0.1 ms |
| Caché en disco del alumno | **no hace falta** | el rebuild cuesta menos que el I/O de guardar |
| Comparación con el cohorte | **no disponible offline** | requeriría los datos de la cátedra; si el docente publica un resumen, se distribuye como un `CourseBenchmark` anónimo (sin identificar alumnos) |

**Reglas**:

1. El alumno ve sus métricas **aunque su inscripción tenga
   `visibility = NONE`**. La visibilidad gobierna **lo que ve el docente**, no
   lo que el alumno ve sobre sí mismo. Esto es exactamente lo que significa
   "el estudiante conserva agencia" y "el docente no ve lo que no debe".
2. El alumno con IA **local** y `visibility = NONE` no envía absolutamente
   nada: sus métricas son suyas.
3. El alumno puede **exportar** sus métricas (no el texto) para compartirlas
   con el docente: un `StudentMetricsExport` JSON explícito, que es un acto
   deliberado de consentimiento, no un efecto colateral.

### 8.2 El docente

**Qué ve**: la pantalla "Analítica" de la cátedra: totales, actividad diaria,
calidad de citas, latencia, fuentes más consultadas, ranking de alumnos,
alumnos inactivos y el detalle por alumno.

**Cómo se calcula**: el `AnalyticsAccumulator` de la cátedra (todos los
usuarios presentes localmente), congelado con la **política de docente**:

```kotlin
// D3: la visibilidad se aplica en el freeze, no en la acumulación.
val visible = enrollments.filter { it.visibility.showsMetrics }
```

Un alumno con `visibility = NONE`:
- no aparece en `perStudent`,
- no cuenta para `students` ni `activeStudents`,
- **no aporta** a `perDay`,
- **no aporta** a `topSources` (este es el bug B1 que se corrige).

**Dónde están los datos**: el docente calcula **en su propio dispositivo**, sobre
los chats que tenga localmente. Si la cátedra usa IA alojada en
Kitoru-Server, el servidor puede sincronizar los chats al dispositivo del
docente (módulo futuro, fuera de este documento). Si la instalación del alumno
es local y no comparte, el docente simplemente **no tiene** esos mensajes: el
alumno aparece con 0 actividad, y eso es exactamente lo que corresponde.

### 8.3 Matriz

| | Alumno | Docente |
|---|---|---|
| Ámbito del acumulador | `userId` propio | todos los usuarios locales |
| Política | `SELF`: sin filtro de visibilidad | `TEACHER`: `showsMetrics` + rol |
| Ubicación del cómputo | dispositivo del alumno | dispositivo del docente |
| Red | ninguna | ninguna (el sync del server es otro módulo) |
| Caché en disco | no | sí, por cátedra (`state.bin`) |
| Costo típico | < 5 ms | 2–4 ms (freeze) desde caché |
| Quién puede ver los datos | el propio alumno | el docente, según visibilidad |

---

## 9. Escala · números concretos

**Escenario**: 500 alumnos × 2 000 mensajes = **1 000 000 de mensajes**,
20 000 hilos, 120 días de cursada, 30 fuentes distintas, 2 000 caracteres de
texto medio por mensaje.

### 9.1 Los cuatro caminos (estimados; a confirmar con un benchmark en M2)

| Camino | Operaciones | Desktop (JVM) | Android gama media | RAM |
|---|---|---|---|---|
| `analyze` actual (O(S·M)) | 5·10⁸ | 30–60 s | 2–4 min | **~1 GB ⇒ OOM** |
| Rebuild completo con acumulador (streaming) | 1 000 000 | 3–5 s | 8–12 s | **≈8 MB** (constante) |
| Rebuild por usuario | 2 000 | 2–4 ms | 8–15 ms | 8 MB |
| Re-freeze | 75 000 | 2–4 ms | 6–12 ms | 8 MB |
| `onMessage` | 1 | ~100 ns | ~300 ns | — |
| Cargar caché (I/O + decode) | — | 25–45 ms | 60–110 ms | 8 MB |

> El rebuild completo está dominado por el **parseo** del log (3 µs/mensaje con
> kotlinx), no por el acumulador (0.1 µs/mensaje). Como el rebuild ocurre una
> sola vez, la optimización es un **log de proyección** (`metrics.ndjson`, sin
> el texto: ~90 B/mensaje en vez de ~830 B ⇒ 90 MB en vez de 830 MB, y parseo 4×
> más rápido: rebuild ≈ 1–1.5 s). Se deja como optimización del milestone 6.

### 9.2 Presupuesto de la apertura de pantalla (`< 200 ms`)

| Paso | Bytes | Desktop | Android | ¿Bloquea la UI? |
|---|---|---|---|---|
| Abrir `manifest.json` + hash de inscripciones | 2 KB | 1–2 ms | 3–6 ms | no |
| Leer `state.bin` | 1.1 MB | 4–8 ms | 8–20 ms | no |
| Decodificar `state.bin` | — | 20–30 ms | 45–70 ms | no (actor) |
| Catch-up de cola (0–50 mensajes nuevos) | — | 0–2 ms | 0–5 ms | no |
| `freeze()` → `CourseAnalytics` | — | 2–4 ms | 6–12 ms | no |
| Publicar en `StateFlow` | — | < 1 ms | < 1 ms | no |
| Composición + primer frame de Compose | — | 40–70 ms | 60–110 ms | sí (normal) |
| **Total** | | **≈ 70–115 ms** | **≈ 120–225 ms** | |

El presupuesto es holgado en desktop y **justo en Android** (el primer frame
de Compose se lleva la mitad). Palancas, en orden de coste/beneficio:

1. **Precalentar el `state.bin` de la última cátedra abierta** al iniciar la
   app, en el dispatcher de IO, en paralelo con el arranque de la UI: le
   quita ~50–90 ms del camino crítico en Android.
2. **Precalentar sólo el `snapshot.json`** (210 KB, 6–10 ms) para pintar en
   < 40 ms y refrescar después con el estado completo.
3. Reducir el estado: omitir los `threadIds` (600 KB, 10 ms de decode)
   reconstruyéndolos desde `threads.json` en background (¡pero eso cuesta
   50–80 ms de parseo! ⇒ no conviene; se deja el estado completo).
4. `state.bin` con `mmap`-like: no aplica (portable).

**Si el presupuesto no se cumple en un dispositivo concreto**, el plan B es
**precalcular en el proceso que ya está**: al abrir la app, el motor carga
*todos* los `.state` de las cátedras del usuario en background (I/O Cheap:
1.1 MB × N cátedras) y los deja en un `LruCache` de 3 entradas. La pantalla
   de analítica siempre encuentra su estado en memoria ⇒ **< 20 ms** para la
parte de datos, y el total queda en ≈ **100–150 ms** en Android (limitado por
el primer frame de Compose).

### 9.3 Memoria del acumulador

| Estructura | Entradas | Bytes/entrada (kotlin stdlib) | Total |
|---|---|---|---|
| `users` (cubos) | 500 | ~136 B (objeto 96 + `HashMap.Node` 40) | **68 KB** |
| `enrollments` | 500 | ~180 B | **90 KB** |
| `threadAuthors` | 20 000 | ~48 B (id + nodo) | **960 KB** |
| `threadIds` (en los cubos) | 20 000 | ~136 B (`Node` 32 + `String` 104) | **2.7 MB** |
| `days` (por usuario) | 60 000 | ~72 B (`Node` 32 + `Long` 16 + `DayCounts` 24) | **4.3 MB** |
| `sources` (por usuario) | 15 000 | ~48 B (`Node` 32 + `Int` 16) | **720 KB** |
| **Total (colecciones estándar)** | | | **≈ 8.8 MB** |
| **Total (con interning + mapa compacto de días)** | | | **≈ 2.9 MB** |

**Conclusión clave**: la memoria del acumulador **no crece con el número de
mensajes**. 1 M o 100 M de mensajes ⇒ el mismo footprint (crece sólo con
alumnos, días activos y fuentes distintas). Esto es lo que hace viable la
analítica en un móvil.

El resto de la memoria del proceso:

| Componente | 1 M de mensajes |
|---|---|
| Acumulador | 3–9 MB |
| `threads` en memoria (20 000 × ~200 B) | 4 MB |
| `enrollments` | 0.1 MB |
| **Estado permanente** | **≈ 7–13 MB** |
| `CourseAnalytics` congelado (500 alumnos + 120 días) | ~250 KB |
| **Pico durante un rebuild** | **+1 mensaje** (streaming) ⇒ +0 MB |

El rebuild completo **nunca** materializa la lista de mensajes: `forEachSince`
alimenta el acumulador mensaje a mensaje.

### 9.4 Qué pasa si se pierde la caché

- **Se pierde `state.bin`** ⇒ rebuild completo (3–5 s desktop / 8–12 s
  Android) en background. La UI muestra el `snapshot.json` si existe, o un
  esqueleto con progreso.
- **Se pierde `snapshot.json`** ⇒ nada: se regenera con el `freeze()`.
- **Se pierde el log** (borrado de `courses/<id>/`) ⇒ rebuild desde cero, y el
  estado guardado es inútil (mensajeCount = 0). El motor detecta
  `state.appendSeq > store.appendSeq` ⇒ descarta la caché y reconstruye.
- **Disco lleno** ⇒ la escritura del `.state.tmp` falla ⇒ se ignora el error
  (log a consola), se reintenta en el próximo flush, y la app sigue
  funcionando con el acumulador en memoria. **La caché es una optimización,
  nunca una fuente de verdad.**

---

## 10. Código

### 10.1 `AnalyticsAccumulator` (completo)

Archivo: `core/src/commonMain/kotlin/kitoru/domain/AnalyticsAccumulator.kt`

```kotlin
package kitoru.domain

import kotlin.math.roundToInt

/**
 * Acumulador **incremental** de las métricas de una cátedra.
 *
 * Es la contraparte stateful de [AnalyticsAnalyzer.analyze]: mantiene un cubo
 * por usuario y se actualiza en O(1) por cada mensaje nuevo. Al final se
 * "congela" en un [CourseAnalytics] con [freeze].
 *
 * Reglas:
 *  - El acumulador **no conoce la visibilidad**: calcula para todos los
 *    usuarios y el [freeze] decide a quién se le muestra. Por eso cambiar la
 *    visibilidad de un alumno es un re-freeze (O(usuarios·días)) y no un
 *    recálculo.
 *  - Las fuentes y la actividad diaria se guardan **por usuario** (no
 *    globales) por la misma razón.
 *  - No guarda mensajes, textos ni hilos completos: sólo contadores.
 *
 * Es determinista: no usa el reloj, la red ni el modelo.
 */
class AnalyticsAccumulator(
    val courseId: String,
) {

    /** Actividad de un día para un usuario. */
    private class DayCounts(
        var messages: Int = 0,
        var replies: Int = 0,
        /** Cualquier mensaje, sea del rol que sea (para `activeDays`). */
        var activity: Int = 0,
    )

    /** Todo lo que se sabe de un usuario. */
    private class UserBucket(
        val userId: String,
        var displayName: String = "",
        var role: CourseRole = CourseRole.ALUMNO,
        var visibility: ChatVisibility = ChatVisibility.FULL,
        var messages: Int = 0,
        var replies: Int = 0,
        var tokensIn: Int = 0,
        var tokensOut: Int = 0,
        var latencySum: Long = 0L,
        var citationsVerified: Int = 0,
        var citationsUnsupported: Int = 0,
        var citationsUnknown: Int = 0,
        var answersWithUnsupported: Int = 0,
        var toolCalls: Int = 0,
        var toolFailures: Int = 0,
        var lastActivity: Long = 0L,
        var lastSeq: Long = 0L,
    ) {
        val threadIds: MutableSet<String> = HashSet()
        val days: MutableMap<Long, DayCounts> = HashMap()
        val sources: MutableMap<String, Int> = HashMap()
    }

    /** Cubo por usuario. `LinkedHashMap` para conservar el orden de carga. */
    private val users = LinkedHashMap<String, UserBucket>()

    /** Inscripciones, en el orden en que las deliver el store. */
    private val enrollments = LinkedHashMap<String, Enrollment>()

    /** Autor de cada hilo (necesario para atribuir respuestas y fuentes). */
    private val threadAuthors = HashMap<String, String>()

    // ---- Watermark ---------------------------------------------------------

    /** Último `seq` incorporado. */
    var appendSeq: Long = 0L; private set

    /** Mensajes incorporados. */
    var messageCount: Long = 0L; private set

    /** Revisión del store de la que proviene este estado. */
    var storeRevision: Long = 0L

    // ---- Ingesta incremental ------------------------------------------------

    /** Registra un hilo: cuenta como conversación del autor. */
    fun onThread(thread: ChatThread) {
        threadAuthors[thread.id] = thread.authorId
        bucketOf(thread.authorId).threadIds.add(thread.id)
    }

    /**
     * Incorpora un mensaje. **O(1) amortizado.**
     *
     * @param authorId autor del hilo (no del mensaje): las respuestas del
     *   modelo pertenecen al hilo del alumno y se atribuyen a él.
     * @param seq número de secuencia en el log (0 si no lo hay).
     */
    fun onMessage(message: ChatMessage, authorId: String, seq: Long = 0L) {
        val b = bucketOf(authorId)
        val day = AnalyticsAnalyzer.dayStart(message.createdAt)
        val d = b.days.getOrPut(day) { DayCounts() }
        d.activity++

        if (message.isUser) {
            b.messages++
            d.messages++
            b.tokensIn += message.tokensIn
        } else {
            // TODO(B2): el analizador histórico cuenta como "respuesta"
            // cualquier mensaje que no sea de usuario. Se replica el
            // comportamiento para no romper la reproducibilidad; cuando se
            // agreguen roles nuevos hay que alinear ambas reglas.
            b.replies++
            d.replies++
            b.tokensOut += message.tokensOut
            b.latencySum += message.latencyMs
            message.citation?.let { c ->
                b.citationsVerified += c.verified
                b.citationsUnsupported += c.unsupported
                b.citationsUnknown += c.unknown
                if (c.unsupported > 0 || c.unknown > 0) b.answersWithUnsupported++
            }
            b.toolCalls += message.toolCalls.size
            b.toolFailures += message.toolCalls.count { it.ok == false }
            for (source in message.sources) {
                b.sources[source] = (b.sources[source] ?: 0) + 1
            }
        }

        if (message.createdAt > b.lastActivity) b.lastActivity = message.createdAt
        if (seq > b.lastSeq) b.lastSeq = seq
        messageCount++
        if (seq > appendSeq) appendSeq = seq
    }

    /** Alta o cambio de inscripción (rol, visibilidad, nombre). */
    fun onEnrollment(enrollment: Enrollment) {
        enrollments[enrollment.userId] = enrollment
        val b = bucketOf(enrollment.userId)
        b.displayName = enrollment.displayName
        b.role = enrollment.role
        b.visibility = enrollment.visibility
    }

    /** Baja de inscripción: el usuario desaparece de los agregados. */
    fun removeEnrollment(userId: String) {
        enrollments.remove(userId)
        users.remove(userId)
    }

    /**
     * Reconstruye el cubo de un usuario desde cero.
     *
     * Se usa cuando un mensaje suyo fue editado o borrado: el acumulador no
     * guarda un delta por mensaje (costaría ~80 B × M), así que se recalcula
     * sólo lo de ese usuario: O(mensajes del usuario) ≈ M/S.
     */
    fun rebuildUser(
        userId: String,
        threads: Iterable<ChatThread>,
        messages: Iterable<Pair<ChatMessage, Long>>,
    ) {
        val existing = users[userId]
        val b = UserBucket(userId).also {
            it.displayName = existing?.displayName ?: ""
            it.role = existing?.role ?: CourseRole.ALUMNO
            it.visibility = existing?.visibility ?: ChatVisibility.FULL
        }
        users[userId] = b
        for (t in threads) {
            threadAuthors[t.id] = t.userId
            b.threadIds.add(t.id)
        }
        for ((m, seq) in messages) onMessage(m, userId, seq)
    }

    /** Quita un hilo (borrado de hilo) y sus mensajes del cubo de su autor. */
    fun removeThread(threadId: String) {
        val authorId = threadAuthors.remove(threadId) ?: return
        users[authorId]?.threadIds?.remove(threadId)
    }

    // ---- Salida -------------------------------------------------------------

    /**
     * Congela el estado en un [CourseAnalytics].
     *
     * Aplica la política de visibilidad (el docente no ve lo que no debe) y
     * calcula los totales. Costo: O(usuarios·(días + fuentes)).
     */
    fun freeze(course: Course): CourseAnalytics {
        val visible = enrollments.values.filter { it.visibility.showsMetrics }

        val perStudent = visible
            .map { e -> studentAnalytics(e, users[e.userId]) }
            .sortedByDescending { it.messages }

        val students = perStudent.filter { it.role == CourseRole.ALUMNO }
        val teachers = perStudent.count { it.role != CourseRole.ALUMNO }

        val totalMessages = students.sumOf { it.messages }
        val totalReplies = students.sumOf { it.replies }
        val activeStudents = students.count { it.messages > 0 }

        val citationsVerified = students.sumOf { it.citationsVerified }
        val citationsUnsupported = students.sumOf { it.citationsUnsupported }
        val citationsUnknown = students.sumOf { it.citationsUnknown }
        val citationsTotal = citationsVerified + citationsUnsupported + citationsUnknown

        // TODO(B3): se replica el truncamiento por alumno del analizador
        // histórico. La versión exacta (suma de latencias / total de
        // respuestas) difiere en ±1 ms.
        val totalLatency = students.sumOf { it.avgLatencyMs * it.replies }
        val avgLatency = if (totalReplies == 0) 0L else totalLatency / totalReplies

        return CourseAnalytics(
            courseId = course.id,
            students = students.size,
            activeStudents = activeStudents,
            teachers = teachers,
            totalMessages = totalMessages,
            totalReplies = totalReplies,
            totalThreads = students.sumOf { it.threads },
            avgMessagesPerStudent = if (students.isEmpty()) 0.0
            else (totalMessages.toDouble() / students.size).roundToInt().toDouble(),
            citationSupportRate = if (citationsTotal == 0) 0.0
            else citationsVerified.toDouble() / citationsTotal,
            citationsVerified = citationsVerified,
            citationsUnsupported = citationsUnsupported,
            citationsUnknown = citationsUnknown,
            avgLatencyMs = avgLatency,
            totalTokens = students.sumOf { it.tokensIn + it.tokensOut },
            perStudent = perStudent,
            perDay = aggregateDays(visible),
            topSources = aggregateSources(visible),
            inactiveStudents = students.filter { it.messages == 0 }.map { it.displayName },
        )
    }

    /**
     * Métricas de un usuario (para el panel del alumno).
     *
     * No aplica visibilidad: el alumno ve lo suyo aunque su inscripción esté
     * oculta para el docente.
     */
    fun studentOf(userId: String): StudentAnalytics? {
        val e = enrollments[userId] ?: return users[userId]?.let {
            StudentAnalytics(userId = it.userId, displayName = it.displayName, role = it.role)
        }
        return studentAnalytics(e, users[userId])
    }

    // ---- Internos -----------------------------------------------------------

    private fun bucketOf(userId: String): UserBucket =
        users.getOrPut(userId) { UserBucket(userId) }

    private fun studentAnalytics(e: Enrollment, b: UserBucket?): StudentAnalytics {
        if (b == null) {
            return StudentAnalytics(userId = e.userId, displayName = e.displayName, role = e.role)
        }
        val replies = b.replies
        return StudentAnalytics(
            userId = e.userId,
            displayName = e.displayName,          // el nombre manda sobre el del hilo
            role = e.role,
            messages = b.messages,
            replies = replies,
            threads = b.threadIds.size,
            activeDays = b.days.count { it.value.activity > 0 },
            lastActivity = b.lastActivity,
            avgLatencyMs = if (replies == 0) 0L else b.latencySum / replies,
            tokensIn = b.tokensIn,
            tokensOut = b.tokensOut,
            citationsVerified = b.citationsVerified,
            citationsUnsupported = b.citationsUnsupported,
            citationsUnknown = b.citationsUnknown,
            toolCalls = b.toolCalls,
            toolFailures = b.toolFailures,
            answersWithUnsupportedCitations = b.answersWithUnsupported,
        )
    }

    /** Actividad diaria agregada (sólo usuarios visibles). */
    private fun aggregateDays(visible: List<Enrollment>): List<DayActivity> {
        val byDay = HashMap<Long, IntArray>()
        for (e in visible) {
            val b = users[e.userId] ?: continue
            for ((day, d) in b.days) {
                val acc = byDay.getOrPut(day) { IntArray(2) }
                acc[0] += d.messages
                acc[1] += d.replies
            }
        }
        return byDay.entries.sortedBy { it.key }
            .map { DayActivity(it.key, it.value[0], it.value[1]) }
    }

    /**
     * Fuentes más consultadas (sólo usuarios visibles).
     *
     * Corregido respecto del analizador histórico, que sumaba las fuentes de
     * los alumnos con visibilidad [ChatVisibility.NONE] (bug B1).
     */
    private fun aggregateSources(visible: List<Enrollment>): List<SourceUsage> {
        val counts = HashMap<String, Int>()
        for (e in visible) {
            val b = users[e.userId] ?: continue
            for ((label, n) in b.sources) counts[label] = (counts[label] ?: 0) + n
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(10)
            .map { SourceUsage(it.key, it.value) }
    }
}
```

### 10.2 `AnalyticsAnalyzer` reimplementado sobre el acumulador

```kotlin
object AnalyticsAnalyzer {
    fun analyze(
        course: Course,
        enrollments: List<Enrollment>,
        threads: List<ChatThread>,
        messages: List<ChatMessage>,
    ): CourseAnalytics {
        val acc = AnalyticsAccumulator(course.id)
        enrollments.forEach(acc::onEnrollment)
        val authorOf = threads.associate { it.id to it.authorId }
        threads.forEach(acc::onThread)
        for (m in messages) {
            val author = authorOf[m.threadId] ?: continue
            acc.onMessage(m, author)
        }
        return acc.freeze(course)
    }
}
```

La firma pública no cambia ⇒ la UI y los tests existentes siguen compilando, y
la función sigue siendo **pura y determinista** (regla 3 del CONTEXTO).

### 10.3 El motor (esqueleto)

```kotlin
class AnalyticsEngine(
    private val fs: FileSystem,
    private val stores: CourseStoreRegistry,
    private val scope: CoroutineScope,
) {
    private val holders = ConcurrentHashMap<String, CourseAnalyticsHolder>()

    /** La UI se suscribe a esto; nunca bloquea. */
    fun observe(courseId: String): StateFlow<AnalyticsUiState> =
        holder(courseId).state

    /** Carga la caché del disco (o dispara el rebuild) sin bloquear. */
    suspend fun ensureLoaded(courseId: String) = holder(courseId).warmUp()

    /** Fuerza un recálculo (botón "recalcular"). */
    suspend fun refresh(courseId: String, force: Boolean = false) =
        holder(courseId).refresh(force)

    private fun holder(courseId: String): CourseAnalyticsHolder =
        holders.getOrPut(courseId) {
            CourseAnalyticsHolder(
                store = stores.of(courseId),
                cache = AnalyticsCache(fs),
                scope = scope,
                course = stores.of(courseId).course,
            ).also { it.start() }
        }
}
```

Y dentro del holder, el camino caliente:

```kotlin
private suspend fun onStoreEvent(ev: StoreEvent) {
    when (ev) {
        is StoreEvent.Appended -> {
            accumulator.onMessage(ev.message, ev.authorId, ev.seq)
            dirty = true
            pending.trySend(Unit)          // flujo conflado → un freeze por ráfaga
        }
        is StoreEvent.Deleted -> {
            accumulator.rebuildUser(
                userId = ev.authorId,
                threads = store.threadsOf(ev.authorId),
                messages = store.messagesOf(ev.authorId),   // List<Pair<ChatMessage, Long>>
            )
            dirty = true
            pending.trySend(Unit)
        }
        // …
    }
}
```

### 10.4 Codec del estado (firma)

```kotlin
/** Codifica/decodifica el estado del acumulador a `state.bin`. */
object AnalyticsStateCodec {
    const val FORMAT_VERSION: Int = 1
    private const val MAGIC = "KIT1"

    fun encode(acc: AnalyticsAccumulator, watermark: AnalyticsWatermark): ByteArray
    fun decode(bytes: ByteArray): DecodedState?     // null ⇒ corrupto
}

@Serializable
data class AnalyticsWatermark(
    val formatVersion: Int = AnalyticsStateCodec.FORMAT_VERSION,
    val courseId: String,
    val appendSeq: Long = 0L,
    val messageCount: Long = 0L,
    val storeRevision: Long = 0L,
    val threadCount: Int = 0,
    val enrollmentHash: Long = 0L,
    val builtAt: Long = 0L,
)
```

---

## 11. Casos borde

| Caso | Comportamiento |
|---|---|
| **Mensaje cuyo hilo no existe** | `onMessage` ignora el mensaje (no hay autor al que atribuírlo). En `analyze` se lo ignora con `if (message.threadId !in threadById.keys) continue`. |
| **Hilo sin mensajes** | Cuenta como conversación del autor (`threads`), 0 mensajes. El freeze lo incluye en `perStudent` con `messages = 0`. |
| **Alumno sin ningún mensaje** | Aparece en `perStudent` con todo en 0 ⇒ entra en `inactiveStudents` (por `displayName`). |
| **Inscripción visible sin bucket** (nunca chateó) | `studentAnalytics` con bucket `null` ⇒ todo en 0. Idéntico al comportamiento del analizador. |
| **Mensaje con `role` desconocido** ("system", "tool") | Cuenta como actividad del día (`activity++`) pero ni como `messages` ni como `replies`. Replica el analizador (bug B2). |
| **Citas sin `citation`** | No suma nada a `citations*` ni a `answersWithUnsupported`. Igual que el analizador. |
| **`sources` vacío** | No toca `sources`. |
| **Reloj del sistema hacia atrás** | El acumulador no usa el reloj: los `dayStart` salen de `createdAt`. Sólo `computedAt` (en el estado de UI) usa el reloj. |
| **Cambio de zona horaria** | `dayStart` usa UTC (medianoche UTC), igual que el analizador. No depende de la zona horaria del dispositivo ⇒ determinista. |
| **Dos alumnos con el mismo `displayName`** | Se distinguen por `userId`; `inactiveStudents` muestra nombres (posible ambigüedad preexistente). |
| **Caché de otra cátedra** | El nombre del archivo incluye un hash del `courseId`; además el `courseId` está en la cabecera y se verifica al decodificar. |
| **`state.bin` con `formatVersion` viejo** | Se descarta y se reconstruye (con mensaje en español en el log, no en la UI). |
| **Dos procesos sobre la misma instalación** | El log NDJSON con `seq` asignado bajo cerrojo evita el entrelazado; el `rename` atómico del estado evita corrupción. Kitoru es single-proceso por diseño. |

---

## 12. Pruebas

| Test | Qué verifica |
|---|---|
| `AnalyticsAccumulatorEquivalenceTest` | `freeze()` del acumulador ≡ `AnalyticsAnalyzerReference.analyze()` (salvo B1). Corpus aleatorio con roles, visibilidades, citas, herramientas, fuentes y días dispersos. El oráculo es una copia literal del analizador actual, conservada en `commonTest`. |
| `AnalyticsAccumulatorIncrementalTest` | Añadir un mensaje cambia los contadores **exactamente** como lo haría un `analyze` completo. |
| `AnalyticsAccumulatorDayTest` | `activeDays` cuenta días con mensajes de cualquier rol; `perDay` reparte mensajes/respuestas. |
| `AnalyticsAccumulatorVisibilityTest` | Cambiar la visibilidad de un alumno **no** recalcula mensajes: cambia el resultado del freeze y nada más (se cuenta el número de llamadas a `onMessage` ⇒ 0). |
| `AnalyticsAccumulatorRebuildTest` | Borrar un mensaje + `rebuildUser` deja el estado igual al de un `analyze` sin ese mensaje. |
| `AnalyticsStateCodecTest` | `encode` → `decode` → `freeze` da el mismo `CourseAnalytics`; CRC detecta un byte corrupto; `formatVersion` viejo se rechaza. |
| `AnalyticsWatermarkTest` | La regla de validez detecta: sólo anexos (válido), un borrado (inválido), una edición (inválido), versión vieja (inválido). |
| `AnalyticsEngineTest` | Con un store falso: el `StateFlow` emite `Ready`; un evento `Appended` actualiza los números; una caché corrupta dispara el rebuild; el rebuild con 10 000 mensajes da el mismo resultado que `analyze`. |
| `AnalyticsScaleTest` (opcional, tag `slow`) | 500 000 mensajes sintéticos: tiempo de `onMessage` < 1 µs promedio; memoria del acumulador < 12 MB; `freeze` < 20 ms. |

---

## 13. Plan de implementación

| Milestone | Entregable | Riesgo |
|---|---|---|
| **M1** | `AnalyticsAccumulator` + `AnalyticsAnalyzer` reimplementado sobre él + tests de equivalencia. | Bajo: todo puro y testeable, sin I/O. |
| **M2** | `AnalyticsStateCodec` (binario) + `AnalyticsWatermark` + round-trip tests. | Bajo. |
| **M3** | `CourseStore` (NDJSON + `seq` + manifiesto) + extensiones de `FileSystem` (`readLines`, `appendText`, `rename`) + migración desde `messages.json`. | **Alto**: toca la persistencia. Es el prerrequisito. |
| **M4** | `AnalyticsEngine` + `CourseAnalyticsHolder` (actor + `StateFlow` + persist debounced) + `AnalyticsCache`. | Medio: concurrencia. |
| **M5** | UI: pantalla de analítica del docente (consume `StateFlow`) y panel "Mi actividad" del alumno (`studentOf`). | Bajo. |
| **M6** | Optimización: log de proyección de métricas para que el rebuild completo baje de 8–12 s a 1–1.5 s en Android; compactación del log; precalentado de la última cátedra. | Bajo, opcional. |

---

## 14. Riesgos

| Riesgo | Mitigación |
|---|---|
| Que `analyze` y el pipeline divergan | `analyze` **se reimplementa** sobre el acumulador: no hay dos implementaciones que comparar (D2). |
| Que el estado en disco crezca sin control | `state.bin` es O(S·(d+s+t)), no O(M): 1.1 MB para 1M de mensajes. |
| Que la app abra lenta en Android | Presupuesto medido (§9.2) + precalentado + plan B (caché en memoria, < 20 ms). |
| Que un borrado de mensaje pierda datos | El rebuild por usuario lee del log (fuente de verdad), no del acumulador. El acumulador es caché, no verdad. |
| Que la visibilidad se filtre tarde y se filtren datos | La **acumulación es ciega a la visibilidad** y el **freeze la aplica**: el dato nunca sale del cubo sin pasar por el filtro. Test específico de §12. |
| Que el log NDJSON crezca mucho (830 MB por 1M de mensajes) | Compactación (§3.6) + log de proyección (M6). |
| Que el `seq` se desincronice entre réplicas | Una sola instalación escribe el log (diseño single-proceso). Si aparece Kitoru-Server, el `seq` pasa a ser del servidor. |

---

## 15. Resumen en una línea

`AnalyticsAnalyzer.analyze` pasa de O(S·M) sobre 1 GB de RAM a un
`AnalyticsAccumulator` de O(1) por mensaje y 3–9 MB de memoria; su estado se
guarda en un binario de 1.1 MB en `cache/analytics/`, se invalida con un
watermark `(appendSeq, storeRevision, formatVersion)` que distingue anexar de
mutar, y se recalcula en cuatro granularidades (incremental, re-freeze,
rebuild por usuario, rebuild completo); la UI siempre recibe un `StateFlow` con
la última instantánea y nunca bloquea, con una apertura de pantalla de
**70–170 ms** para 500 alumnos y 1.000.000 de mensajes.
