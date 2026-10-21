# 04 — Conversaciones y mensajes

> Cómo se guardan los chats, los hilos y los mensajes; cómo los ve el docente.
> Documento de diseño. No implementa código: la única fuente de verdad son los
> archivos del repo (`core/`, `backend/`, `llm-api/`).

---

## 0. Resumen ejecutivo (decisiones)

| # | Decisión | Por qué |
|---|---|---|
| D1 | `ChatThread.id` y `ChatMessage.id` son **UUID v4 puros**; `courseId`/`authorId` son **FKs explícitos** | El id no codifica la cátedra: sirve para un servidor, no se rompe al importar, y las consultas por cátedra/alumno las resuelve el **layout de carpetas**, no un `startsWith` |
| D2 | **Un directorio por hilo** con `thread.json` + `messages.jsonl` (append) + `stats.json` | Un `messages.json` global reescribe 200 MB por cada mensaje nuevo (§5.3) |
| D3 | **`messages.jsonl`**: un mensaje por línea, append-only, reparación de línea partida | Escribir un mensaje cuesta 1 KB, no el archivo entero |
| D4 | **Escritura atómica**: `.tmp` + `fsync` + `rename` | Hoy `writeText` trunca: un corte a mitad de escritura deja el archivo vacío (§5) |
| D5 | **Título automático determinista** del primer mensaje del alumno (como FunshiEngineAI) + bandera `titleSource` | Sin llamada al modelo: el título existe al instante y no cuesta tokens. `MANUAL` congela el título |
| D6 | **`seq` monotónico por hilo** para el orden; `createdAt` sólo para mostrar y agrupar | El reloj del alumno puede estar mal; el orden no puede depender de él |
| D7 | Exportación **`.kitoru`** (JSON versionado) del lado del alumno; importación idempotente por id del lado del docente | Es el único camino para que el docente vea chats hechos con IA local (§6) |
| D8 | **Borrado lógico** (`deletedAt`, `deletedBy`, `deleteReason`) + registro de auditoría | Un alumno borra local, pero lo que ya llegó al docente no se puede desenviar: hay que ser honesto y dar la herramienta de supresión (§7) |
| D9 | **Métricas por mensaje** + `stats.json` por hilo + `aggregate.json` por cátedra | La analítica del docente no relee nunca los mensajes (§8) |
| D10 | `noSourceFound` con **marcador explícito** (`NO_ENCONTRADO_EN_MATERIAL`) + heurística de respaldo | "El modelo dijo que no encontró la fuente" es una señal de calidad clave del RAG |

---

## 1. Estado actual y qué falla

Hoy, en `core/src/commonMain/kotlin/kitoru/domain/`:

```kotlin
// Chat.kt
data class ChatThread(id, courseId, authorId, authorName, title, createdAt, updatedAt,
                      modelLabel, effectiveModel, tags)
data class ChatMessage(id, threadId, role, text, createdAt, sources, citation,
                       toolCalls, latencyMs, tokensOut, tokensIn)
```

Y en `FileRepositories.kt`:

```kotlin
// FileMessageRepository
override suspend fun listForCourse(courseId: String): List<ChatMessage> =
    all().filter { it.threadId.startsWith("$courseId|") }.sortedBy { it.createdAt }
```

Problemas concretos:

| # | Problema | Consecuencia |
|---|---|---|
| P1 | `threads.json` y `messages.json` son archivos **únicos y globales** (`config/`) | Cada mensaje nuevo reescribe el archivo completo (§5.3) |
| P2 | `messages.json` mezcla **todas las cátedras** | No hay forma de "borrar una cátedra" sin tocar el archivo global; el export de un alumno arrastra todo |
| P3 | El repositorio de mensajes depende de una **convención del id** (`"$courseId|uuid"`) | Acoplamiento opaco: si un hilo se importa en otra cátedra, el prefijo miente |
| P4 | `threadId` no sirve para "mensajes de este alumno" | Hay que cruzarlo contra todos los hilos de todas las cátedras |
| P5 | `JsonStore._write` usa `fs.writeText` | Trunca y escribe: un corte a mitad de escritura deja el archivo corrupto (y `_read` devuelve `empty()` → **se pierden todos los chats**) |
| P6 | `ChatMessage.tokensIn/tokensOut` son "estimados" y no se distinguen de los reales | La analítica de consumo no es confiable |
| P7 | No hay forma de saber que una respuesta fue **regenerada**, **editada**, o que el modelo **no encontró la fuente** | Se pierde información de calidad y de comportamiento |
| P8 | `ChatThread.effectiveModel: ModelPolicy` guarda el `ModelPolicy` **completo, incluida la `apiKey`** | La API key de la cátedra queda escrita en el disco del alumno (y se copiaría al `.kitoru`) |
| P9 | Borrar es físico (`removeById`) y no hay `deletedAt` | No se puede "borrar y que el docente lo vea", ni auditar quién borró qué |
| P10 | No hay forma de que el docente reciba los chats de un alumno con IA local | Falta el formato de exportación y el flujo de importación |

---

## 2. Modelo final

> Archivos: `core/src/commonMain/kotlin/kitoru/domain/Chat.kt` (modelo),
> `core/src/commonMain/kotlin/kitoru/domain/ChatStats.kt` (agregados),
> `core/src/commonMain/kotlin/kitoru/domain/ChatExport.kt` (`.kitoru`).

### 2.1 `Chat.kt` (completo, listo para copiar-pegar)

```kotlin
package kitoru.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─────────────────────────────── roles y estados ───────────────────────────────

/** Rol de un mensaje dentro de un hilo. */
@Serializable
enum class MessageRole(val label: String) {
    @SerialName("user") USER("Alumno"),
    @SerialName("assistant") ASSISTANT("Asistente"),
    @SerialName("system") SYSTEM("Sistema"),
    @SerialName("tool") TOOL("Herramienta"),
}

/** Cómo terminó la generación de un mensaje del asistente. */
@Serializable
enum class StopReason(val label: String) {
    @SerialName("stop") STOP("Fin"),
    @SerialName("length") LENGTH("Límite de tokens"),
    @SerialName("tool") TOOL("Usó herramienta"),
    @SerialName("cancelled") CANCELLED("Cancelado"),
    @SerialName("error") ERROR("Error"),
}

/** De dónde salió el título del hilo. */
@Serializable
enum class TitleSource(val label: String) {
    /** Se deriva del primer mensaje del alumno; se recalcula si ese mensaje se edita. */
    @SerialName("auto") AUTO("Automático"),
    /** El alumno lo editó a mano: no se toca más. */
    @SerialName("manual") MANUAL("Manual"),
}

/** De dónde salió el hilo. */
@Serializable
enum class ThreadOrigin(val label: String) {
    @SerialName("local") LOCAL("Este dispositivo"),
    /** Sincronizado desde el Kitoru-Server de la cátedra. */
    @SerialName("remote") REMOTE("Servidor de la cátedra"),
    /** Importado de un archivo `.kitoru` que entregó un alumno. */
    @SerialName("imported") IMPORTED("Importado"),
}

/** Qué se comparte cuando el alumno exporta sus chats. */
@Serializable
enum class ChatShareScope(val label: String) {
    /** Texto de los mensajes + todas las métricas. */
    @SerialName("full") FULL("Texto y métricas"),
    /** Sólo métricas agregadas por mensaje: el docente no ve el texto. */
    @SerialName("metrics") METRICS("Sólo métricas"),
}

/** Verificación de citas de una respuesta (verificador determinista de FunshiEngineAI). */
@Serializable
data class CitationStats(
    /** Citas respaldadas por la fuente que citan. */
    val verified: Int = 0,
    /** Citas cuya fuente existe pero no respalda lo que se dice. */
    val unsupported: Int = 0,
    /** Citas que apuntan a una fuente inexistente. */
    val unknown: Int = 0,
) {
    val total: Int get() = verified + unsupported + unknown

    /** Proporción de citas respaldadas (0..1). Sin citas = 0. */
    val supportRate: Double get() = if (total == 0) 0.0 else verified.toDouble() / total

    /** ¿Todas las citas están respaldadas? */
    val fullySupported: Boolean get() = total > 0 && unsupported == 0 && unknown == 0
}

/** Archivo que produjo una herramienta (adjunto del mensaje). */
@Serializable
data class ToolArtifact(
    val name: String,
    /** Ruta relativa al directorio de datos de la app. */
    val path: String = "",
    val sizeBytes: Long = 0L,
    val mime: String = "",
)

/** Herramienta que el modelo pidió durante una respuesta. */
@Serializable
data class ToolCallStats(
    /** Id de la llamada (para emparejar con la tarjeta de la UI). */
    val callId: String = "",
    val name: String,
    val args: String = "",
    /** null = la herramienta se está ejecutando. */
    val ok: Boolean? = null,
    val result: String = "",
    val durationMs: Long = 0L,
    /** Archivos que produjo la herramienta. */
    val artifacts: List<ToolArtifact> = emptyList(),
)

// ─────────────────────────────── el hilo ───────────────────────────────

/**
 * Un hilo de conversación dentro de una cátedra.
 *
 * Un alumno puede tener varios hilos; el docente los ve agregados en la
 * analítica (o el texto, según la visibilidad de la inscripción).
 *
 * `id` es un **UUID v4 puro**: no lleva el id de la cátedra adentro. La
 * relación con la cátedra y con el autor son FKs explícitas (`courseId`,
 * `authorId`) y el índice para consultarlas es el layout de carpetas
 * (`courses/<courseId>/chats/<authorId>/<threadId>/`). Ver §4.
 */
@Serializable
data class ChatThread(
    /** UUID v4. Identificador global, igual en el alumno y en el docente. */
    val id: String,
    /** FK a la cátedra. */
    val courseId: String,
    /** FK al autor (id de usuario / inscripción). */
    val authorId: String,
    val authorName: String = "",
    /** Título visible del hilo. */
    val title: String = "Conversación",
    /** De dónde salió el título (AUTO = se recalcula; MANUAL = congelado). */
    val titleSource: TitleSource = TitleSource.AUTO,
    /** Id del mensaje del que se derivó el título (sólo si `titleSource == AUTO`). */
    val titleMessageId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Instante del último mensaje (mismo reloj que `updatedAt`, por comodidad de consulta). */
    val lastMessageAt: Long = 0L,
    /** Modelo que usó el autor (etiqueta legible). */
    val modelLabel: String = "",
    /**
     * Configuración efectiva con la que se generó (base + ajustes del alumno).
     * **Nunca debe contener la `apiKey`**: se guarda con `effectiveModel.redacted()`.
     */
    val effectiveModel: ModelPolicy = ModelPolicy(),
    /** Etiquetas que el autor puso al hilo (ej. "duda de parcial"). */
    val tags: List<String> = emptyList(),
    /** Cantidad de mensajes del hilo (cache para pintar la lista sin abrirlo). */
    val messageCount: Int = 0,
    /** Origen del hilo (local, servidor o importado). */
    val origin: ThreadOrigin = ThreadOrigin.LOCAL,
    /** Borrado lógico: null = vivo. */
    val deletedAt: Long? = null,
    /** Quién borró: "student:<id>", "teacher:<id>", "teacher:suppression", "system". */
    val deletedBy: String = "",
    /** Motivo del borrado (obligatorio en borrado por derecho de supresión). */
    val deleteReason: String = "",
    /** Instante a partir del cual se purga del disco (papelera). 0 = nunca. */
    val purgeAfter: Long = 0L,
) {
    val isDeleted: Boolean get() = deletedAt != null
    val isAutoTitle: Boolean get() = titleSource == TitleSource.AUTO
}

// ─────────────────────────────── el mensaje ───────────────────────────────

/**
 * Un mensaje de una conversación.
 *
 * Guarda lo necesario para la analítica: fuentes usadas, verificación de
 * citas, herramientas, latencia y tokens. Con eso el docente puede medir la
 * *calidad* de las respuestas sin depender del modelo.
 *
 * El **orden** de los mensajes dentro del hilo lo da [seq] (monotónico, 0-based)
 * y no [createdAt]: el reloj del dispositivo del alumno puede estar mal
 * (zona horaria, reloj desincronizado) y el orden de una conversación no puede
 * depender de eso. [createdAt] se usa para mostrar y para agrupar por día.
 */
@Serializable
data class ChatMessage(
    /** UUID v4. */
    val id: String,
    /** FK al hilo. */
    val threadId: String,
    /** Posición dentro del hilo, 0-based y monotónica. */
    val seq: Int = 0,
    val role: MessageRole = MessageRole.USER,
    val text: String,
    val createdAt: Long = 0L,
    /** Id del mensaje al que responde este (regeneración o respuesta directa). */
    val replyToMessageId: String? = null,
    /** `seq` del mensaje al que responde; -1 si no responde a ninguno. */
    val replyToSeq: Int = -1,
    /** El alumno editó este mensaje: instante de la última edición. */
    val editedAt: Long? = null,
    /** Quién editó ("student:<id>"). */
    val editedBy: String = "",
    /** Esta respuesta es una **regeneración** de otra: id del mensaje reemplazado. */
    val regeneratesMessageId: String? = null,
    /** Este mensaje quedó **reemplazado** por una regeneración: id del mensaje nuevo. */
    val supersededByMessageId: String? = null,
    /** Tokens de la pregunta (reales si el servidor los mandó). */
    val tokensIn: Int = 0,
    /** Tokens de la respuesta. */
    val tokensOut: Int = 0,
    /** true = los tokens son estimados (el servidor no mandó `usage`). */
    val tokensEstimated: Boolean = true,
    /** Latencia total de la respuesta (ms). */
    val latencyMs: Long = 0L,
    /** Tiempo hasta el primer token (ms). */
    val ttftMs: Long = 0L,
    /** Modelo que respondió este mensaje (el alumno puede cambiar a mitad de hilo). */
    val modelLabel: String = "",
    /** Etiquetas de las fuentes recuperadas (corpus: ruta). */
    val sources: List<String> = emptyList(),
    /** Fragmentos que el RAG recuperó para esta pregunta. */
    val retrievedChunks: Int = 0,
    /** Score BM25 del mejor fragmento (0 = sin recuperación). */
    val topRetrievalScore: Double = 0.0,
    /** Verificación de citas (sólo en respuestas del asistente). */
    val citation: CitationStats? = null,
    /** Herramientas usadas para producir la respuesta. */
    val toolCalls: List<ToolCallStats> = emptyList(),
    /** El modelo dijo que **no encontró la fuente** en el material de la cátedra. */
    val noSourceFound: Boolean = false,
    /** Cómo se detectó: "marker" (token explícito) | "heuristic" | "" (no aplica). */
    val noSourceKind: String = "",
    /** Cómo terminó la generación. */
    val stopReason: StopReason = StopReason.STOP,
    /** Código de error de `llm.error.LlmErrorCode` si `stopReason == ERROR`. */
    val errorCode: String = "",
    /** Mensaje de error legible (se muestra al alumno y se cuenta para analítica). */
    val errorMessage: String = "",
    /** Borrado lógico del mensaje. */
    val deletedAt: Long? = null,
) {
    val isUser: Boolean get() = role == MessageRole.USER
    val isAssistant: Boolean get() = role == MessageRole.ASSISTANT
    val isRegeneration: Boolean get() = regeneratesMessageId != null
    val wasSuperseded: Boolean get() = supersededByMessageId != null
    val wasEdited: Boolean get() = editedAt != null
}
```

### 2.2 `ChatStats.kt` (agregados por hilo y por cátedra)

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Agregados de un hilo.
 *
 * Es un **cache** derivado de los mensajes: se recomputa cuando el hilo
 * cambia. Permite que el docente abra el panel de analítica sin leer ni un
 * solo mensaje (ver §8).
 */
@Serializable
data class ThreadStats(
    val threadId: String,
    val courseId: String = "",
    val authorId: String = "",
    val messageCount: Int = 0,
    val replyCount: Int = 0,
    val userMessageCount: Int = 0,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val latencySumMs: Long = 0L,
    val latencySamples: Int = 0,
    val ttftSumMs: Long = 0L,
    val citationsVerified: Int = 0,
    val citationsUnsupported: Int = 0,
    val citationsUnknown: Int = 0,
    /** Respuestas en las que el modelo no encontró la fuente. */
    val noSourceCount: Int = 0,
    /** Respuestas regeneradas por el alumno. */
    val regeneratedCount: Int = 0,
    /** Mensajes del alumno editados a mano. */
    val editedCount: Int = 0,
    val toolCalls: Int = 0,
    val toolFailures: Int = 0,
    val errorCount: Int = 0,
    val firstMessageAt: Long = 0L,
    val lastMessageAt: Long = 0L,
    /** Días (medianoche local) en los que hubo actividad. */
    val activeDays: List<Long> = emptyList(),
    /** Fuentes más consultadas del hilo. */
    val sourceCounts: Map<String, Int> = emptyMap(),
) {
    val avgLatencyMs: Long get() = if (latencySamples == 0) 0L else latencySumMs / latencySamples
    val avgTtftMs: Long get() = if (replyCount == 0) 0L else ttftSumMs / replyCount
}

/**
 * Agregados de una cátedra: lo que el docente ve en el panel.
 *
 * Se calcula sumando los `stats.json` de cada hilo. Es un cache: se marca
 * `stale` cuando algún hilo cambió después de `computedAt`.
 */
@Serializable
data class CourseAggregate(
    val courseId: String,
    val computedAt: Long = 0L,
    val students: Int = 0,
    val activeStudents: Int = 0,
    val threads: Int = 0,
    val messages: Int = 0,
    val replies: Int = 0,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val latencySumMs: Long = 0L,
    val latencySamples: Int = 0,
    val citationsVerified: Int = 0,
    val citationsUnsupported: Int = 0,
    val citationsUnknown: Int = 0,
    val noSourceCount: Int = 0,
    val regeneratedCount: Int = 0,
    val editedCount: Int = 0,
    val toolCalls: Int = 0,
    val toolFailures: Int = 0,
    val errorCount: Int = 0,
    /** Por alumno: userId → stats del hilo (agregadas). */
    val perStudent: Map<String, StudentAggregate> = emptyMap(),
    /** Días (medianoche local) → actividad. */
    val perDay: Map<Long, DayCount> = emptyMap(),
    /** Fuentes más consultadas de la cátedra. */
    val sourceCounts: Map<String, Int> = emptyMap(),
)

@Serializable
data class StudentAggregate(
    val userId: String,
    val displayName: String = "",
    val threads: Int = 0,
    val messages: Int = 0,
    val replies: Int = 0,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val latencySumMs: Long = 0L,
    val latencySamples: Int = 0,
    val citationsVerified: Int = 0,
    val citationsUnsupported: Int = 0,
    val citationsUnknown: Int = 0,
    val noSourceCount: Int = 0,
    val regeneratedCount: Int = 0,
    val editedCount: Int = 0,
    val toolCalls: Int = 0,
    val toolFailures: Int = 0,
    val activeDays: Int = 0,
    val firstMessageAt: Long = 0L,
    val lastMessageAt: Long = 0L,
)

@Serializable
data class DayCount(val messages: Int = 0, val replies: Int = 0)
```

### 2.3 Detección de "el modelo no encontró la fuente"

`noSourceFound` se calcula en dos cascadas, en el backend, al cerrar la respuesta:

```kotlin
package kitoru.domain

/**
 * Marca de "no encontré la fuente".
 *
 * Cascada 1 (marker): la cátedra instruye al modelo a emitir el token
 * `NO_ENCONTRADO_EN_MATERIAL` cuando el material no alcanza. Es determinista.
 *
 * Cascada 2 (heuristic): para modelos chicos que no siguen la instrucción,
 * se buscan frases típicas. Es una heurística: se marca como tal para que la
 * analítica pueda separar "lo dijo el modelo" de "lo detectamos nosotros".
 */
object NoSourceMarker {
    const val TOKEN = "NO_ENCONTRADO_EN_MATERIAL"

    private val PATTERNS = listOf(
        Regex("no (?:encontré|encuentro|hay|está|figura|aparece)[^.]*\\b(?:material|documento|fuente|contenido)"),
        Regex("el material[^.]*no (?:contiene|menciona|incluye)"),
        Regex("no tengo (?:información|acceso)[^.]*material"),
        Regex("no puedo (?:responder|contestar)[^.]*con (?:el|este) material"),
    )

    /** ¿El texto dice explícitamente que no encontró la fuente? */
    fun detect(text: String): Pair<Boolean, String> {
        if (text.contains(TOKEN, ignoreCase = true)) return true to "marker"
        val t = text.lowercase()
        if (PATTERNS.any { it.containsMatchIn(t) }) return true to "heuristic"
        return false to ""
    }

    /** Limpia el token del texto que se muestra y persiste. */
    fun strip(text: String): String =
        text.replace(Regex("\\s*\\[?$TOKEN]?\\s*", RegexOption.IGNORE_CASE), "\n")
            .trim()
}
```

El prompt del sistema de la cátedra (que edita el docente) debería incluir la
instrucción correspondiente; se propone agregarla al `RagEngine` cuando la
cátedra no la tenga.

---

## 3. Titulado automático

### 3.1 La regla

Idéntica en espíritu a la de FunshiEngineAI (`ChatLibrary.persistActiveChat` +
`ChatNode.Chat.autoName`), pero con el flag explícito en el modelo:

> El título de un hilo se deriva del **primer mensaje del alumno**: se toma su
> primera línea no vacía, se le limpian los adornos de markdown, se colapsan los
> espacios y se corta a 48 caracteres. Si el alumno **renombra el hilo a mano**,
> el título queda congelado (`titleSource = MANUAL`) y nunca se vuelve a tocar.

FunshiEngineAI lo hace con un `Boolean autoName` en el nodo del árbol; en
Kitoru el equivalente es el enum `TitleSource`, que además permite saber *por
qué* el título es el que es (y auditarlo).

### 3.2 Implementación

```kotlin
package kitoru.domain

/**
 * Títulos automáticos de hilos.
 *
 * Determinista y sin modelo: el título existe al instante, no cuesta tokens y
 * no depende de la red (regla 3 del proyecto: la analítica y lo que se guarda
 * no pueden depender del modelo).
 */
object ThreadTitles {

    /** Largo máximo del título. */
    const val MAX_LENGTH = 48

    /** Título por defecto cuando el primer mensaje no sirve. */
    const val FALLBACK = "Conversación"

    private val MARKDOWN_PREFIX = Regex("^(#{1,6}\\s*|[-*+]\\s+|\\d+[.)]\\s+|>+\\s*)")
    private val SPACES = Regex("\\s+")

    /**
     * Deriva el título del primer mensaje del alumno.
     *
     * @param firstUserMessage texto del primer mensaje del alumno (o null).
     * @return el título derivado (nunca vacío).
     */
    fun derive(firstUserMessage: String?): String {
        val raw = firstUserMessage?.trim().orEmpty()
        if (raw.isEmpty()) return FALLBACK
        val firstLine = raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return FALLBACK
        val clean = SPACES.replace(MARKDOWN_PREFIX.replace(firstLine, ""), " ").trim()
        if (clean.isEmpty()) return FALLBACK
        if (clean.length <= MAX_LENGTH) return clean
        // Corte en el último espacio antes del tope: "Chequeo de_fpga / ejemplo"
        val cut = clean.take(MAX_LENGTH)
        val lastSpace = cut.lastIndexOf(' ')
        val head = if (lastSpace > MAX_LENGTH / 2) cut.take(lastSpace) else cut
        return head.trimEnd(' ', ',', ';', ':', '.', '-', '–', '—') + "…"
    }

    /**
     * Recalcula el título de un hilo salvo que el alumno lo haya renombrado.
     *
     * @return el hilo actualizado (o el mismo si el título es manual).
     */
    fun refresh(
        thread: ChatThread,
        messages: List<ChatMessage>,
    ): ChatThread {
        if (thread.titleSource == TitleSource.MANUAL) return thread
        val firstUser = messages.firstOrNull { it.role == MessageRole.USER } ?: return thread
        val derived = derive(firstUser.text)
        return thread.copy(
            title = derived,
            titleMessageId = firstUser.id,
            titleSource = TitleSource.AUTO,
        )
    }
}
```

### 3.3 Qué pasa con los títulos editados a mano

| Situación | Resultado |
|---|---|
| Hilo nuevo, el alumno manda su primer mensaje | `title = derive(texto)`, `titleSource = AUTO`, `titleMessageId = <id del mensaje>` |
| El alumno renombra el hilo | `title = <lo que escribió>`, `titleSource = MANUAL`. **Nunca** se recalcula |
| El alumno edita su primer mensaje y el título es AUTO | Se recalcula con `refresh()` (mismo comportamiento que FunshiEngineAI: el nombre sigue al primer mensaje) |
| El alumno edita su primer mensaje y el título es MANUAL | No se toca |
| Un hilo importado con `titleSource = MANUAL` | Se respeta tal cual |
| El primer mensaje se borra lógicamente | El título **no** cambia (ya quedó congelado con el texto en su momento); el hilo conserva su título |

### 3.4 Título por modelo (opcional, fase 2)

Se puede agregar un título "inteligente" pidiéndole al modelo un resumen de
6 palabras. **No es el default** por tres razones: cuesta tokens y latencia en
el primer mensaje, depende de la red, y el modelo puede no estar disponible
(IA local). Si se implementa:

- se agrega `titleMethod: TitleMethod` (`FIRST_LINE` | `MODEL`) al `ChatThread`;
- sólo se pide cuando `titleSource == AUTO` y el hilo tiene ≥ 1 turno;
- el resultado se cachea; si falla la llamada, se conserva el título de la
  primera línea (degradación limpia);
- el prompt es fijo y corto ("Resumí la consulta del usuario en ≤ 6 palabras,
  sin comillas ni punto final"), con `max_tokens = 24`.

---

## 4. Identificadores

### 4.1 El esquema actual y su evaluación

Hoy (según `FileMessageRepository.listForCourse`) el id del hilo es:

```
threadId = "<courseId>|<uuid>"
```

| Criterio | Evaluación |
|---|---|
| "Mensajes de esta cátedra" | Funciona **por convención**: `it.threadId.startsWith("$courseId\|")`. Es un filtro de strings sobre un archivo global: O(total de mensajes de todas las cátedras) por consulta |
| "Mensajes de este alumno" | **No funciona**: hay que saber los ids de los hilos del alumno (cruce con `threads.json` completo) y después filtrar el global de mensajes |
| Sincronización con un servidor | **Molesto**: el servidor tendría que parsear `"<uuid>|<uuid>"` como clave primaria, o guardar el id compuesto. No es una FK |
| Importar un chat en otra cátedra | El prefijo queda mintiendo (`"<courseA>|<uuid>"` dentro de `courseB`) |
| Robustez | Depende de que ningún `courseId` contenga `|` y de que nadie cambie el esquema sin migrar |
| Legibilidad / depuración | Ruido: el id aparece en logs, en la UI, en los `.kitoru` |

### 4.2 Decisión: **UUID puro + FKs explícitas + jerarquía de carpetas como índice**

```
ChatThread.id        = "9f1c2e6a-3b47-4d1e-9c0f-6a1b2c3d4e5f"   (UUID v4, 36 chars)
ChatThread.courseId  = "0c7f...-..."                             (FK)
ChatThread.authorId  = "b3a9...-..."                             (FK)
ChatMessage.id       = "5d2b...-..."                             (UUID v4)
ChatMessage.threadId = "9f1c2e6a-..."                            (FK)
```

El **id no codifica nada del contexto**. El contexto se resuelve con la ruta:

```
<dataDir>/courses/<courseId>/chats/<authorId>/<threadId>/
```

que es un índice materializado en disco: listar una cátedra o un alumno es
listar directorios, no filtrar strings.

Generación de ids (portable, sin `java.util.UUID` en `commonMain`):

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/Ids.kt
package kitoru.platform

/** Id nuevo, universally unique. UUID v4 (36 chars, con guiones). */
expect fun newId(): String
```

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/platform/Ids.jvmCommonMain.kt
package kitoru.platform

import java.util.UUID

actual fun newId(): String = UUID.randomUUID().toString()
```

> `jvmCommonMain` es el source set compartido por desktop y Android (ya existe
> en el repo), y `java.util.UUID` está disponible en ambos (API 1 en Android).

### 4.3 Cómo se resuelven las tres consultas

| Consulta | Cómo se resuelve | Coste |
|---|---|---|
| "Mensajes de esta cátedra" | `fs.listFiles(courses/<courseId>/chats)` → por autor → por hilo → leer `messages.jsonl` | Sólo se leen los hilos de esa cátedra |
| "Mensajes de este alumno" | `fs.listFiles(courses/<courseId>/chats/<authorId>)` | Un `listFiles` y listo |
| "Sincronizar con un servidor" | El servidor guarda `threads(id uuid pk, course_id uuid fk, author_id uuid fk, …)` y `messages(id uuid pk, thread_id uuid fk, seq int, …)`. El cliente genera los ids → `upsert` idempotente y offline-first | El ids son los mismos en el alumno, en el `.kitoru` y en el servidor |

**Por qué ids del cliente y no del servidor**: el alumno puede chatear sin
servidor (IA local) y exportar; si el id lo generara el servidor, el `.kitoru`
no tendría id y la importación no sería idempotente. Con id del cliente, el
`POST /messages` del servidor es un `upsert` por id: reintentar no duplica.

**Compatibilidad hacia adelante**: si mañana el servidor quiere un id corto
legible, se agrega una columna `slug` en el servidor; el id del cliente nunca
cambia.

### 4.4 Limpieza del código actual

`FileMessageRepository.listForCourse` deja de filtrar por prefijo: el
repositorio nuevo (§6.1) recibe el `courseId` y navega el directorio. Se elimina
la convención `"<courseId>|"` del modelo y de la documentación.

---

## 5. Persistencia

### 5.1 Layout en disco

```
<dataDir>/                                   # ~/.kitoru (desktop) · filesDir/kitoru (Android)
├── config/
│   ├── courses.json                         # catálogo de cátedras (metadata)
│   ├── enrollments.json                     # inscripciones y visibilidad
│   ├── materials.json                       # metadata de materiales
│   ├── profiles.json                        # perfiles locales
│   └── app.json                             # id local del usuario, preferencias
├── courses/
│   └── <courseId>/
│       ├── course.json                      # copia de la cátedra (para exportar)
│       ├── materials/                       # archivos del corpus + index.json
│       ├── chats/
│       │   ├── <authorId>/                  # ← índice por alumno
│       │   │   └── <threadId>/              # ← un directorio por hilo
│       │   │       ├── thread.json          # cabecera del hilo (ChatThread)
│       │   │       ├── messages.jsonl       # un mensaje por línea (append)
│       │   │       └── stats.json           # agregados del hilo (cache)
│       │   └── aggregate.json               # agregados de la cátedra (cache)
│       └── deletions.log                    # auditoría de borrados (JSONL)
├── exports/                                 # .kitoru que el alumno genera
└── cache/
```

Reglas:

1. **El id del hilo es el nombre del directorio**, no un campo dentro del
   archivo. El path *es* el índice.
2. `authorId` y `courseId` se usan como nombres de carpeta. Para que un id
   importado (con caracteres raros) no rompa el filesystem, se sanitiza:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/FileKey.kt
package kitoru.platform

/**
 * Convierte un id en un nombre de carpeta seguro.
 *
 * Los ids de Kitoru son UUID, así que esto es una red de seguridad para los
 * ids importados (que pueden venir de otra app).
 */
fun safeFileKey(raw: String): String {
    val cleaned = raw.map { c ->
        if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_'
    }.joinToString("")
    return if (cleaned == raw && cleaned.isNotEmpty()) cleaned
    else "${cleaned.take(48)}_${raw.hashCode().toUInt().toString(16)}"
}
```

3. `stats.json` y `aggregate.json` son **caches**: se pueden borrar y se
   recomputan. El mensaje es la verdad; el resto es derivado.
4. Nada de esto se versiona con el repo; es datos del usuario.

### 5.2 Formato de `messages.jsonl`

Un mensaje por línea, JSON **compacto** (sin `prettyPrint`), con un `Json`
propio:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/JsonStore.kt (agregar)
/** Json compacto para persistencia línea a línea (JSONL). */
val KitoruJsonCompact: Json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = false   // los defaults del data class reconstruyen el mensaje
}
```

`encodeDefaults = false` + `ignoreUnknownKeys = true` ⇒ **evolución compatible**:
un archivo viejo se lee con los defaults nuevos, y un archivo nuevo con un
cliente viejo no rompe (ignora campos que no conoce).

Ejemplo de línea (una sola, sin saltos):

```json
{"id":"5d2b9c11-0f2a-4c3e-9a55-2b1d0e9f7a10","threadId":"9f1c2e6a-3b47-4d1e-9c0f-6a1b2c3d4e5f","seq":1,"role":"assistant","text":"La Encontré en la slide 4...","createdAt":1764000000000,"replyToMessageId":"1a2b...","replyToSeq":0,"tokensIn":312,"tokensOut":228,"tokensEstimated":false,"latencyMs":4210,"ttftMs":380,"modelLabel":"Phi-4-mini-instruct-Q4_K_M","sources":["Apuntes.pdf: p.12","Diapositivas.pdf: slide 4"],"retrievedChunks":3,"topRetrievalScore":12.7,"citation":{"verified":2,"unsupported":0,"unknown":0},"toolCalls":[],"noSourceFound":false,"noSourceKind":"","stopReason":"stop"}
```

### 5.3 Números: ¿por qué `messages.json` global no escala?

Supuestos (medianos, no optimistas):

- Pregunta del alumno: **180 caracteres**.
- Respuesta del asistente: **1.200 caracteres**.
- Tamaño en JSONL compacto: usuario ≈ **420 B**, asistente ≈ **1.800 B**
  (texto + fuentes + citas + métricas) ⇒ **1.100 B por mensaje**, **2.200 B por turno**.
- Escenario: **500 mensajes/mes por alumno** (= 250 turnos/mes), cursada de 6 meses.
- Cátedra: **60 alumnos**.

| Magnitud | Cálculo | Resultado |
|---|---|---|
| Tamaño de `messages.json` de **un alumno** | 3.000 msg × 1.100 B | **3,3 MB** |
| Tamaño de `messages.json` de **la cátedra** (60 alumnos) | 60 × 3,3 MB | **≈ 200 MB** |
| Turnos por alumno en el semestre | 500/mes × 6 | 3.000 mensajes |
| Hilos por alumno | ~1 conversación por cada 2 días de uso | ~25 hilos/semestre |

**Costo de reescribir el archivo global en cada mensaje** (porcento de
"bytes escritos" para una lista de N elementos de tamaño `t`, reescribiendo
todo en cada append):

```
bytes escritos ≈ N²/2 × t
```

| Escenario | N (mensajes) | Archivo | Bytes escritos por semestre |
|---|---|---|---|
| 1 alumno | 3.000 | 3,3 MB | **4,5 GB** |
| 1 cátedra (60 alumnos) | 180.000 | 200 MB | **≈ 18 TB** |

Y el costo de **parseo**: leer y decodificar 200 MB de JSON en cada mensaje con
`kotlinx.serialization` (~100 MB/s) son **~2 s por mensaje**, sólo para agregar
un mensaje. La UI se congela al final de la cursada.

Con el diseño elegido:

| Diseño | Bytes escritos por semestre (cátedra) | Append | Lectura para la analítica |
|---|---|---|---|
| `messages.json` global | ≈ 18 TB | reescribe 200 MB | relee 200 MB por pregunta |
| Un archivo por hilo (reescritura) | 60 alumnos × 3.000 × ~33 KB ≈ **6 GB** | reescribe 66 KB | relee el hilo |
| **`messages.jsonl` por hilo (append)** | 60 × 3,3 MB = **≈ 200 MB** | **escribe 1,1 KB** | lee sólo lo que necesita |

**Conclusión**: un directorio por hilo con `messages.jsonl` en modo append.
Cuesta 200 MB por semestre y cátedra (con 60 alumnos), y las escrituras son
O(1).

### 5.4 Alternativas descartadas (y por qué)

| Alternativa | Por qué no |
|---|---|
| **SQLite / SQLDelight** | Es la opción "correcta" para consultas analíticas, pero agrega una dependencia (driver por plataforma, `expect/actual`, ~1 MB) y una base que **no se puede copiar como carpeta** (el docente tiene que recibir los chats de un alumno: con archivos, es un `zip`; con SQLite, hay que exportarlo). Además, el modelo de datos es append-only con verificación determinista: no hay necesidad de transacciones cross-hilo. **Decisión: archivos ahora**; si el servidor necesita consultas SQL, migrar a SQLDelight **manteniendo el layout de archivos como formato de importación** (el importador SQLDelight es un lector de JSONL). Si el nº de alumnos crece mucho (>500 por cátedra) o aparecen consultas nuevas, se reconsidera |
| Un único `messages.jsonl` por alumno | Es válido (append O(1), 3,3 MB por alumno) y más simple (2 archivos en vez de 3). Descartado porque: (a) el borrado/regeneración de un mensaje obliga a reescribir los 3,3 MB enteros; (b) el hilo es la unidad natural de la UI y del export; (c) el directorio por hilo permite **borrar un hilo con un `delete` de un directorio** sin tocar nada más |
| Un `chats.json` por cátedra | Vuelve al problema de reescritura O(n) |
| Base de datos "en memoria + snapshot" | Pierde durability y no evita el snapshot completo |
| Comprimir los JSONL con gzip | `commonMain` no tiene gzip; se evaluaría sólo para el `.kitoru` (ver §7.3) |

### 5.5 El store JSONL

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/JsonlStore.kt
package kitoru.domain

import kitoru.platform.AtomicFiles
import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer

/**
 * Archivo JSONL: un item por línea, en modo append.
 *
 * Reglas:
 *  - **append**: agregar un mensaje escribe sólo esa línea (1 KB), no el archivo.
 *  - **tolerante**: una línea partida (escritura interrumpida) se descarta al
 *    leer y el archivo se repara en la siguiente escritura.
 *  - **atómico por línea**: cada línea se escribe con un único `writeText` de
 *    la línea completa (`\n` incluido).
 */
class JsonlStore<T : Any>(
    private val fs: FileSystem,
    private val path: FilePath,
    private val serializer: KSerializer<T>,
) {
    private val mutex = Mutex()

    /** Lee todos los items. Las líneas inválidas se saltan (log interno). */
    suspend fun readAll(): List<T> = mutex.withLock { _readAll() }

    /** Agrega un item al final. */
    suspend fun append(item: T) = mutex.withLock {
        fs.createDirectories(path.parent ?: path)
        val line = KitoruJsonCompact.encodeToString(serializer, item) + "\n"
        // Antes de agregar, se repara una posible cola partida (línea a medio
        // escribir por un corte previo).
        _repairTail()
        // Append atómico: la línea se escribe completa en un temporal y se
        // renombra. Cuesta el doble de I/O y garantiza que nunca queda una
        // línea a medias.
        AtomicFiles.appendLine(fs, path, line)
    }

    /** Reescribe el archivo completo (edición, borrado, regeneración). */
    suspend fun rewrite(items: List<T>) = mutex.withLock {
        val body = items.joinToString("\n") {
            KitoruJsonCompact.encodeToString(serializer, it)
        }
        AtomicFiles.writeText(fs, path, if (body.isEmpty()) "" else body + "\n")
    }

    private fun _readAll(): List<T> {
        if (!fs.exists(path)) return emptyList()
        val text = try { fs.readText(path) } catch (_: Exception) { return emptyList() }
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<T>()
        for (line in text.split('\n')) {
            val l = line.trim()
            if (l.isEmpty()) continue
            try {
                out += KitoruJsonCompact.decodeFromString(serializer, l)
            } catch (_: Exception) {
                // Línea corrupta: se saltea (no se pierde el resto del archivo).
            }
        }
        return out
    }

    /** Corta la cola inválida del archivo (línea a medio escribir). */
    private fun _repairTail() {
        if (!fs.exists(path)) return
        val text = try { fs.readText(path) } catch (_: Exception) { return }
        if (text.isEmpty() || text.endsWith("\n")) return
        val lines = text.split('\n')
        val good = lines.dropLast(1).filter { line ->
            line.isBlank() || runCatching {
                KitoruJsonCompact.decodeFromString(serializer, line.trim())
            }.isSuccess
        }
        val repaired = good.joinToString("\n").trimEnd('\n') + if (good.isEmpty()) "" else "\n"
        if (repaired != text) AtomicFiles.writeText(fs, path, repaired)
    }
}
```

### 5.6 Escrituras atómicas

**El problema de hoy**: `JsonStore._write` hace `fs.writeText(path, json)`. La
implementación JVM abre el archivo en modo *truncate* y escribe: si el proceso
se corta (crash, cierre de sesión, batería) en medio, el archivo queda con
contenido parcial → `JsonStore._read` no lo puede parsear → **devuelve
`empty()`** → la app ve que no hay ningún curso, ningún chat y ningún
material. Un corte de 200 ms pierde la cursada entera.

**El patrón** (es el estándar *write-temp-then-rename*):

1. Se escribe el contenido nuevo en `<archivo>.tmp` (archivo nuevo, el
   original queda intacto).
2. Se fuerza a disco (`fsync`).
3. Se renombra `<archivo>.tmp` → `<archivo>`. El rename es **atómico** en el
   mismo volumen: o el archivo viejo quedó, o el nuevo, nunca un mixto.

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/AtomicFiles.kt
package kitoru.platform

/**
 * Escrituras atómicas sobre [FileSystem].
 *
 * Patrón "write-temp-then-rename": se escribe en `<archivo>.tmp`, se fuerza a
 * disco y se renombra. El rename es atómico dentro del mismo volumen, así que
 * un corte de luz nunca deja un archivo a medio escribir.
 */
object AtomicFiles {

    /** Sufijo del archivo temporal. */
    const val TMP_SUFFIX = ".tmp"

    /** Escribe [text] en [path] de forma atómica. */
    fun writeText(fs: FileSystem, path: FilePath, text: String) {
        val dir = path.parent
        if (dir != null) fs.createDirectories(dir)
        val tmp = path.resolve(path.name + TMP_SUFFIX)
        try {
            fs.writeText(tmp, text)
            fs.sync(tmp)            // a disco antes de renombrar
            fs.move(tmp, path)      // atómico
        } catch (e: Exception) {
            runCatching { fs.delete(tmp) }
            throw e
        }
    }

    /**
     * Agrega una línea al final de un archivo JSONL.
     *
     * La línea se escribe completa (con su `\n`) en un temporal y luego se
     * concatenan temporal + archivo original en un segundo temporal, que se
     * renombra. Cuesta el doble de I/O pero **nunca** deja una línea partida.
     *
     * (Variante: con un `FileOutputStream` en modo `append` el append del SO
     * es atómico por debajo de 4 KB; se deja como optimization posterior.)
     */
    fun appendLine(fs: FileSystem, path: FilePath, line: String) {
        val dir = path.parent
        if (dir != null) fs.createDirectories(dir)
        val tmp = path.resolve(path.name + TMP_SUFFIX)
        val previous = if (fs.exists(path)) fs.readText(path) else ""
        try {
            fs.writeText(tmp, previous + line)
            fs.sync(tmp)
            fs.move(tmp, path)
        } catch (e: Exception) {
            runCatching { fs.delete(tmp) }
            throw e
        }
    }
}
```

**Extensión de `FileSystem`** (`core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt`):

```kotlin
interface FileSystem {
    fun exists(path: FilePath): Boolean
    fun isDirectory(path: FilePath): Boolean
    fun sizeBytes(path: FilePath): Long
    fun readText(path: FilePath): String
    fun writeText(path: FilePath, text: String)
    fun delete(path: FilePath)
    fun createDirectories(path: FilePath)
    fun listFiles(dir: FilePath): List<FilePath>
    fun copy(from: FilePath, to: FilePath)

    /** Renombra (mueve). Atómico si [from] y [to] están en el mismo volumen. */
    fun move(from: FilePath, to: FilePath)

    /** Fuerza a disco el contenido de [path]. No-op si la plataforma no puede. */
    fun sync(path: FilePath) {}

    /** Última modificación (epoch millis); 0 si no se sabe. */
    fun lastModifiedMillis(path: FilePath): Long = 0L
}
```

Implementación JVM (`jvmCommonMain`):

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/platform/FileSystem.jvmCommonMain.kt
override fun move(from: FilePath, to: FilePath) {
    val src = File(from.value)
    val dst = File(to.value)
    // renameTo usa MoveFileEx(REPLACE_EXISTING) en Windows y rename(2) en
    // Unix: en ambos casos reemplaza el destino de forma atómica.
    if (src.renameTo(dst)) return
    // Fallback: volúmenes distintos o destino bloqueado.
    src.copyTo(dst, overwrite = true)
    src.delete()
}

override fun sync(path: FilePath) {
    runCatching {
        // Se abre en modo append para no truncar, y se fuerza a disco.
        FileOutputStream(File(path.value), true).use { it.fd.sync() }
    }
}

override fun lastModifiedMillis(path: FilePath): Long =
    File(path.value).lastModified()
```

**Dónde se usa**:

- `JsonStore._write` → `AtomicFiles.writeText(fs, path, json)`.
- `JsonlStore.append` → `AtomicFiles.appendLine(fs, path, line)`.
- Escrituras de `thread.json`, `stats.json`, `aggregate.json` →
  `AtomicFiles.writeText`.
- `config/*.json` → también atómico (hoy no lo son).

**Recuperación ante un corte**: como el `.tmp` se escribe al lado del
original, si el proceso muere entre el paso 1 y el 3, queda un
`archivo.json.tmp` huérfano. Al arrancar, `AtomicFiles.recover(fs, dir)` lo
promociona si es **más nuevo** que el original (el rename probablemente se
perdió) o lo borra si es viejo:

```kotlin
/** Al arrancar: recupera temporales de escrituras interrumpidas. */
fun recover(fs: FileSystem, dir: FilePath) {
    if (!fs.exists(dir)) return
    for (f in fs.listFiles(dir)) {
        if (!f.name.endsWith(TMP_SUFFIX)) continue
        val target = f.resolve(f.name.removeSuffix(TMP_SUFFIX))
        val tmpTime = fs.lastModifiedMillis(f)
        val targetTime = fs.lastModifiedMillis(target)
        if (!fs.exists(target) || tmpTime > targetTime) {
            runCatching { fs.move(f, target) }   // promover
        } else {
            runCatching { fs.delete(f) }          // descartar
        }
    }
}
```

### 5.7 Carga inicial (qué se lee al abrir la app)

| Momento | Qué se lee | Coste (60 alumnos, 1.500 hilos) |
|---|---|---|
| Arranque | `config/*.json` + los `thread.json` de todas las cátedras | 1.500 × 700 B ≈ **1 MB**, ~40 ms |
| Panel del docente | Sólo `stats.json` + `aggregate.json` | **~50 KB**, instantáneo |
| Abrir un hilo | `messages.jsonl` de ese hilo | 66 KB (60 mensajes) |
| Mandar un mensaje | Append de 1 línea | **1,1 KB** |

Por eso `thread.json` es chico: **nunca** lleva el texto de los mensajes.

---

## 6. Qué se guarda del lado del alumno y qué se sincroniza al docente

### 6.1 Dos mundos

| | Alumno | Docente |
|---|---|---|
| Dónde vive | Carpeta de datos de la app del alumno (`~/.kitoru`) | Carpeta de datos de la app del docente |
| Qué ve | Sus propios hilos, con texto | Sus propios hilos **+** los que importó de los alumnos, según la visibilidad de cada inscripción |
| Cuándo ve al otro | Cuando exporta un `.kitoru` (IA local) o cuando el servidor de la cátedra le manda los chats (IA alojada) | Al importar un `.kitoru` o al sincronizar con el Kitoru-Server |

Matriz de visibilidad (ya definida en `kitoru.domain.ChatVisibility`):

| Modo del alumno | ¿El docente ve el texto? | ¿Ve estadísticas? |
|---|---|---|
| IA alojada (REMOTE, con API key) | Sí | Sí |
| IA local (LOCAL) | No, salvo que el alumno exporte | Depende de la visibilidad de la inscripción |

**Consecuencia de diseño clave**: el texto del alumno **nunca viaja solo**. Sólo
viaja si (a) el alumno exporta un `.kitoru` con `scope = FULL`, o (b) el
servidor de la cátedra lo retransmite (caso REMOTE, con el consentimiento
implícito del alumno al usar la IA de la institución). Si el alumno exporta con
`scope = METRICS`, el `.kitoru` **no contiene el texto**: el docente ve
métricas sin haber leído nunca la conversación.

### 6.2 Repositorios (el contrato nuevo)

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Repositories.kt
package kitoru.domain

/**
 * Persistencia de conversaciones.
 *
 * Reemplaza a `ThreadRepository` + `MessageRepository` porque el layout nuevo
 * necesita **saber la cátedra** para ubicar los archivos (el id del hilo ya
 * no la codifica) y porque el borrado pasó a ser lógico.
 */
interface ChatRepository {

    // ---- hilos ----
    suspend fun listThreads(courseId: String): List<ChatThread>
    suspend fun listThreads(courseId: String, authorId: String): List<ChatThread>
    suspend fun getThread(threadId: String): ChatThread?
    suspend fun saveThread(thread: ChatThread)

    // ---- mensajes ----
    /** Lee el hilo completo, en orden de `seq`. */
    suspend fun listMessages(threadId: String): List<ChatMessage>
    /** Lee desde un `seq` inclusive (para recargar sólo lo nuevo). */
    suspend fun listMessagesSince(threadId: String, seq: Int): List<ChatMessage>
    /** Agrega un mensaje al final del hilo (append). */
    suspend fun appendMessage(message: ChatMessage)
    /** Reemplaza un mensaje (edición, regeneración, marcado de borrado). */
    suspend fun replaceMessage(message: ChatMessage)

    // ---- borrado ----
    /** Borrado lógico del hilo (lo ve el docente, marcado como eliminado). */
    suspend fun softDeleteThread(threadId: String, at: Long, by: String, reason: String = "")
    /** Borrado físico: elimina el directorio del hilo. */
    suspend fun purgeThread(threadId: String)

    // ---- agregados ----
    /** Agregados del hilo (cache). */
    suspend fun threadStats(threadId: String): ThreadStats?
    /** Agregados de la cátedra (cache). */
    suspend fun courseAggregate(courseId: String): CourseAggregate?
}
```

Implementación: `core/src/commonMain/kotlin/kitoru/domain/FileChatRepository.kt`

```kotlin
package kitoru.domain

import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kitoru.platform.KitoruPaths
import kitoru.platform.safeFileKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Implementación en archivos.
 *
 * Layout: `courses/<courseId>/chats/<authorId>/<threadId>/{thread.json,
 * messages.jsonl, stats.json}`. El path es el índice: no hace falta filtrar
 * por prefijo de id ni cargar todo en memoria.
 */
class FileChatRepository(
    private val fs: FileSystem,
    private val coursesDir: FilePath = KitoruPaths.coursesDir,
) : ChatRepository {

    private val mutex = Mutex()

    private fun courseDir(courseId: String) =
        coursesDir.resolve(safeFileKey(courseId))

    private fun chatsDir(courseId: String) = courseDir(courseId).resolve("chats")

    private fun threadDir(courseId: String, authorId: String, threadId: String) =
        chatsDir(courseId)
            .resolve(safeFileKey(authorId))
            .resolve(safeFileKey(threadId))

    /** Busca el directorio de un hilo recorriendo la cátedra (id → path). */
    private suspend fun findThreadDir(threadId: String): Pair<String, FilePath>? {
        for (courseId in fs.listFiles(coursesDir)) {
            val chats = chatsDir(courseId.name)
            if (!fs.exists(chats)) continue
            for (author in fs.listFiles(chats)) {
                for (t in fs.listFiles(author)) {
                    if (t.name == safeFileKey(threadId)) return courseId.name to t
                }
            }
        }
        return null
    }
    // ... el resto de los métodos, todos delegando en JsonlStore + AtomicFiles
}
```

> Nota: `findThreadDir` recorre; es aceptable porque los hilos son pocos y la
> búsqueda se cachea en memoria (`Map<String, FilePath>` indexado al cargar).
> Un índice en memoria `threadId → path` se arma al leer los `thread.json` del
> arranque.

### 6.3 Formato de exportación: `.kitoru`

Extensión `.kitoru`. Contenido: **un documento JSON** (no un zip: `FileSystem`
no tiene compresión y un JSON es inspeccionable y diffeable; si más adelante
hace falta, `.kitoru` puede pasar a ser un zip con `manifest.json` adentro).

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ChatExport.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/** Identificador y versión del formato de exportación. */
object ExportFormat {
    const val ID = "kitoru.chat-export"
    const val VERSION = 1
    const val EXTENSION = "kitoru"
}

/**
 * Un archivo `.kitoru`: los chats que un alumno decide compartir.
 *
 * Es **autodescriptivo**: lleva el curso, la identidad del alumno, la
 * configuración del modelo usada y los hilos con sus mensajes.
 */
@Serializable
data class ChatExport(
    /** Sempre "kitoru.chat-export". */
    val format: String = ExportFormat.ID,
    /** Versión del formato (para migrar). */
    val version: Int = ExportFormat.VERSION,
    /** Fecha de exportación (epoch millis). */
    val exportedAt: Long = 0L,
    /** App que generó el archivo. */
    val app: ExportApp = ExportApp(),
    /** Alumno que exporta. */
    val author: ExportAuthor = ExportAuthor(),
    /** Cátedra (contexto mínimo: el docente puede no tenerla). */
    val course: ExportCourse = ExportCourse(),
    /** Configuración del modelo efectivamente usada (sin API key). */
    val model: ExportModel = ExportModel(),
    /** Qué se comparte: texto y métricas, o sólo métricas. */
    val scope: ChatShareScope = ChatShareScope.FULL,
    /** Visibilidad que el alumno autoriza para esta exportación. */
    val visibility: String = "FULL",
    /** Hilos exportados. */
    val threads: List<ExportedThread> = emptyList(),
    /** Hilos que el alumno borró desde la última exportación: el docente
     *  debe aplicar el borrado al importar. */
    val revocations: List<RevokedThread> = emptyList(),
    /** Suma SHA-256 del payload (detecta corrupción; no autentica). */
    val checksum: String = "",
)

@Serializable
data class ExportApp(val name: String = "Kitoru", val version: String = "0.1.0")

@Serializable
data class ExportAuthor(
    val id: String = "",
    val displayName: String = "",
    val email: String = "",
)

@Serializable
data class ExportCourse(
    val id: String = "",
    val code: String = "",
    val name: String = "",
    val term: String = "",
)

/**
 * Configuración del modelo usada.
 *
 * **Nunca** incluye la API key: se exporta sólo la etiqueta y el modo. El
 * docente necesita saber *qué* modelo se usó (para comparar con la base de la
 * cátedra), no la credencial.
 */
@Serializable
data class ExportModel(
    val mode: String = "LOCAL",
    val label: String = "",
    val baseUrl: String = "",
    val modelName: String = "",
    val temperature: Double = 0.0,
    val maxTokens: Int = 0,
    val topK: Int = 0,
    val contextSize: Int = 0,
    val toolsEnabled: Boolean = false,
    /** El system prompt de la cátedra, si el alumno lo autoriza. */
    val systemPrompt: String = "",
)

@Serializable
data class ExportedThread(
    val id: String,
    val courseId: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val title: String = "",
    val titleSource: TitleSource = TitleSource.AUTO,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val modelLabel: String = "",
    val tags: List<String> = emptyList(),
    val origin: ThreadOrigin = ThreadOrigin.LOCAL,
    /** Métricas del hilo (siempre presentes, incluso con scope = METRICS). */
    val stats: ThreadStats? = null,
    /** Mensajes; vacíos si `scope = METRICS`. */
    val messages: List<ExportedMessage> = emptyList(),
)

@Serializable
data class ExportedMessage(
    val id: String,
    val seq: Int = 0,
    val role: MessageRole = MessageRole.USER,
    /** Vacío si el export es de sólo métricas. */
    val text: String = "",
    val createdAt: Long = 0L,
    val replyToMessageId: String? = null,
    val replyToSeq: Int = -1,
    val editedAt: Long? = null,
    val regeneratesMessageId: String? = null,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val tokensEstimated: Boolean = true,
    val latencyMs: Long = 0L,
    val ttftMs: Long = 0L,
    val modelLabel: String = "",
    val sources: List<String> = emptyList(),
    val retrievedChunks: Int = 0,
    val citation: CitationStats? = null,
    val toolCalls: List<ToolCallStats> = emptyList(),
    val noSourceFound: Boolean = false,
    val stopReason: StopReason = StopReason.STOP,
    val errorCode: String = "",
)

/** Un hilo que el alumno borró: el docente debe borrarlo también. */
@Serializable
data class RevokedThread(
    val threadId: String,
    val deletedAt: Long = 0L,
)
```

Ejemplo real (recortado) de `.kitoru`:

```json
{
  "format": "kitoru.chat-export",
  "version": 1,
  "exportedAt": 1765000000000,
  "app": { "name": "Kitoru", "version": "0.1.0" },
  "author": { "id": "b3a9c7d2-...", "displayName": "Ana Pérez", "email": "ana@fi.uba.ar" },
  "course": { "id": "0c7f5e21-...", "code": "IA-2026-1", "name": "Inteligencia Artificial", "term": "2026-1" },
  "model": {
    "mode": "LOCAL",
    "label": "Phi-4-mini-instruct-Q4_K_M",
    "baseUrl": "http://127.0.0.1:8081/v1",
    "modelName": "",
    "temperature": 0.2,
    "maxTokens": 1024,
    "topK": 5,
    "contextSize": 8192,
    "toolsEnabled": true,
    "systemPrompt": ""
  },
  "scope": "FULL",
  "visibility": "FULL",
  "threads": [
    {
      "id": "9f1c2e6a-3b47-4d1e-9c0f-6a1b2c3d4e5f",
      "courseId": "0c7f5e21-...",
      "authorId": "b3a9c7d2-...",
      "authorName": "Ana Pérez",
      "title": "¿Cómo se define el overfitting en un clasificador?",
      "titleSource": "auto",
      "createdAt": 1764000000000,
      "updatedAt": 1764000120000,
      "modelLabel": "Phi-4-mini-instruct-Q4_K_M",
      "tags": [],
      "origin": "local",
      "stats": { "threadId": "9f1c...", "messageCount": 4, "replyCount": 2, "tokensIn": 640, "tokensOut": 410, "citationsVerified": 2, "citationsUnsupported": 0, "citationsUnknown": 0, "noSourceCount": 0, "regeneratedCount": 1, "editedCount": 0, "toolCalls": 0, "toolFailures": 0, "firstMessageAt": 1764000000000, "lastMessageAt": 1764000120000, "activeDays": [1764000000000] },
      "messages": [
        { "id": "1a2b3c4d-...", "seq": 0, "role": "user", "text": "¿Cómo se define el overfitting?", "createdAt": 1764000000000, "tokensIn": 42, "tokensEstimated": true, "stopReason": "stop" },
        { "id": "5d2b9c11-...", "seq": 1, "role": "assistant", "text": "El overfitting ocurre cuando...", "createdAt": 1764000060000, "replyToMessageId": "1a2b3c4d-...", "replyToSeq": 0, "tokensIn": 312, "tokensOut": 228, "tokensEstimated": false, "latencyMs": 4210, "ttftMs": 380, "modelLabel": "Phi-4-mini-instruct-Q4_K_M", "sources": ["Apuntes.pdf: p.12", "Diapositivas.pdf: slide 4"], "retrievedChunks": 3, "citation": { "verified": 2, "unsupported": 0, "unknown": 0 }, "noSourceFound": false, "stopReason": "stop" }
      ]
    }
  ],
  "revocations": [],
  "checksum": "9f2c…"
}
```

### 6.4 Flujo de exportación (alumno)

```
Pantalla "Compartir / Exportar chats" (alumno)
  1. Elegir cátedra.
  2. Elegir hilos (todos o selección).
  3. Elegir alcance:
       ( ) Texto y métricas   → scope = FULL
       ( ) Sólo métricas      → scope = METRICS   (el texto no sale del equipo)
  4. Previsualización: N hilos · M mensajes · rango de fechas · modelo usado.
  5. Exportar → <exports>/IA-2026-1-Ana-2026-05-14.kitoru
  6. Se le muestra la ruta y el hash; el alumno lo envía por el canal que quiera
     (correo,drive, el chat de la cátedra).
```

Reglas:

- El `apiKey` **jamás** sale: `ExportModel` no tiene ese campo, y
  `ChatThread.effectiveModel` se guarda con `redacted()` (§8.4).
- Con `scope = METRICS`, `ExportedMessage.text = ""` y las respuestas quedan
  como métricas puras.
- El archivo se escribe con `AtomicFiles.writeText` en
  `<dataDir>/exports/<nombre>.kitoru`.
- El nombreSuggested: `<courseCode>-<authorName>-<yyyy-MM-dd>.kitoru`.

### 6.5 Flujo de importación (docente)

```
Pantalla "Importar chats de alumnos" (docente)
  1. "Elegir archivo .kitoru"  → lee y valida:
       - format == "kitoru.chat-export" y version <= soportada
       - course.id / author.id / counts
       - checksum (si no coincide: avisar "el archivo está corrupto o fue editado")
  2. Vista previa (obligatoria antes de importar):
       Alumno: Ana Pérez (b3a9c7d2-…)
       Cátedra: IA-2026-1 · 8 hilos · 96 mensajes · 2026-03-01 → 2026-05-14
       Modelo:  Phi-4-mini-instruct-Q4_K_M (IA local del alumno)
       Alcance: Texto y métricas
       Estado:  la inscripción de Ana en esta cátedra está en "Solo métricas"
                → el texto se importa pero NO se le muestra al docente (aviso)
  3. "Importar":
       a. Si la cátedra (course.id) no existe en el docente → se ofrece crearla
          con el código/nombre del export (el id se conserva).
       b. Si el alumno no está inscripto → se crea una inscripción con el
          `authorId` del export, rol ALUMNO y la visibilidad declarada.
       c. Por cada hilo:
            - si el threadId no existe → se crea el directorio y se escriben
              thread.json + messages.jsonl + stats.json
            - si ya existe → se compara `updatedAt`:
                · el del export es más nuevo → se reemplaza (reimportación)
                · si no → se conserva el local y se informa en el resumen
              (upsert por id ⇒ importar dos veces el mismo archivo no duplica)
       d. Se aplican las `revocations`: los hilos revocados se borran lógicamente
          en la copia del docente.
       e. Se recalcula `aggregate.json` de la cátedra.
  4. Resumen: "Importados 8 hilos, 96 mensajes. 1 hilo actualizado, 1 eliminado."
```

El docente **no puede** ver el texto de un alumno con visibilidad
`METRICS_ONLY` aunque el `.kitoru` lo traiga: la regla es la visibilidad de la
inscripción (§8.4 y regla 6 del proyecto). El `.kitoru` es la *fuente* del
dato, no el permiso.

### 6.6 El camino REMOTE (sincronización futura con el servidor)

El diseño es **local-first**: todo lo anterior funciona sin servidor. Cuando
exista Kitoru-Server:

- El alumno con IA alojada ya le está mandando los chats al servidor (el
  servidor es el que ve las conversaciones porque las genera el modelo). El
  servidor puede reenviar los hilos al docente.
- El **mismo formato** sirve de contrato: `POST /v1/courses/{courseId}/threads`
  y `POST /v1/threads/{threadId}/messages` con los **mismos ids del cliente**
  → `upsert` idempotente.
- El servidor guarda en el mismo layout lógico (`threads`, `messages`) y el
  docente puede seguir usando la importación de `.kitoru` para lo que venga
  de IA local.

---

## 7. Retención y borrado

### 7.1 Borrado lógico, siempre

Nada se borra físicamente hasta que corresponde. El estado de borrado viaja
con el dato:

```kotlin
// En ChatThread
val deletedAt: Long? = null      // instante
val deletedBy: String = ""       // "student:<id>" | "teacher:<id>" | "teacher:suppression" | "system"
val deleteReason: String = ""    // motivo (obligatorio en supresión)
val purgeAfter: Long = 0L        // cuándo se purga del disco (papelera)
```

Motivo de la decisión: **el `createdAt` de un mensaje no es confiable** (el
reloj del alumno puede estar mal) y, sobre todo, el borrado tiene que ser
**atribuible** ("lo borró el alumno el 14/05") y **verificable** por el docente
(que tiene que poder ver que un hilo fue eliminado y por quién, para poder
honrar un pedido de supresión).

### 7.2 Escenarios

| Escenario | Qué pasa en el alumno | Qué pasa en el docente | Qué ve la UI |
|---|---|---|---|
| **El alumno borra un hilo** | `deletedAt = now`, `deletedBy = "student:<id>"`. El texto sigue en el disco 30 días (papelera) y después se purga | Si ya lo había recibido (REMOTE o importado), **lo sigue teniendo**: el borrado local no puede deshacer lo ya compartido. Se marca "Eliminado por el alumno el 14/05" | El hilo aparece tachado/grisado, con un botón "Ver el historial de borrados" en el panel del docente |
| **El alumno borra un hilo y exporta** | El hilo no viaja en el `.kitoru`; además viaja en `revocations` | Al importar, el docente lo borra lógicamente también | El hilo desaparece del listado normal del docente |
| **El docente borra un hilo de un alumno** | No aplica | Borrado lógico en la copia del docente + registro en `deletions.log` | Confirmación: "Esto borra el hilo en tu equipo. El alumno conserva su copia." |
| **El docente borra todos los datos de un alumno** (derecho de supresión) | No aplica | Borrado lógico de **todos** los hilos de ese alumno en **esa** cátedra, con `deletedBy = "teacher:suppression"`, `deleteReason` obligatorio, y una entrada en `deletions.log`. Opción "eliminar definitivamente" → `purge` de los directorios | Diálogo con doble confirmación y **motivo obligatorio** |
| **Fin de cursada** | El alumno conserva sus chats (son suyos) | Purga automática del texto de los mensajes después de `chatRetentionDays` (por defecto 180 días desde el fin de la cursada); **se conservan las métricas** (los `stats.json` siguen) | El panel muestra el texto sólo dentro del período de retención; después, sólo métricas |

### 7.3 Registro de auditoría

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/DeletionLog.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Auditoría de borrados de una cátedra.
 *
 * Se escribe en `courses/<courseId>/deletions.log` (JSONL, append).
 * Es lo que prueba, ante la institución, que un pedido de supresión se
 * atendió: quién, cuándo, sobre qué y por qué.
 */
@Serializable
data class DeletionRecord(
    val at: Long = 0L,
    /** "teacher:<id>" | "student:<id>" | "system" */
    val actor: String = "",
    val actorRole: String = "",
    /** "THREAD" | "STUDENT" | "COURSE" | "RETENTION" */
    val scope: String = "",
    val targetUserId: String = "",
    val threadIds: List<String> = emptyList(),
    val messageCount: Int = 0,
    /** Obligatorio si `scope == "STUDENT"` (derecho de supresión). */
    val reason: String = "",
    /** true si además se purgaron los archivos del disco. */
    val purged: Boolean = false,
)
```

### 7.4 Retención automática

- `Course.chatRetentionDays: Int = 180` (campo nuevo en `Course`; si otro
  documento define la política de cátedra, se usa ese valor).
- Al arrancar la app (y una vez por día), `RetentionJob` recorre
  `courses/*/chats/*/*/thread.json` y:
  1. purga los hilos con `purgeAfter > 0 && purgeAfter < now`;
  2. purga los mensajes con `createdAt < ahora - retention` **dejando los
     `stats.json`** (se conserva la analítica, se pierde el texto);
  3. escribe un `DeletionRecord(scope = "RETENTION")`.

---

## 8. Métricas: lo que hay que guardar en el mensaje

### 8.1 Por mensaje (ya está en `ChatMessage`)

| Métrica | Campo | Para qué |
|---|---|---|
| Tokens de entrada / salida | `tokensIn`, `tokensOut` | Consumo real por alumno y por cátedra |
| ¿Tokens reales o estimados? | `tokensEstimated` | No mezclar estimados con facturados en el informe |
| Latencia total | `latencyMs` | Calidad de la experiencia y del servidor |
| Time-to-first-token | `ttftMs` | Se nota mucho más que el total en streaming |
| Verificación de citas | `citation: CitationStats` | **Calidad de la respuesta** (métrica central del proyecto) |
| Fuentes usadas | `sources: List<String>` | Qué material se consulta |
| Fragmentos recuperados | `retrievedChunks` | Eficiencia del RAG |
| Score del mejor fragmento | `topRetrievalScore` | Calidad de la recuperación (BM25) |
| Herramientas | `toolCalls: List<ToolCallStats>` | Qué herramientas usa el modelo, cuáles fallan |
| "No encontró la fuente" | `noSourceFound`, `noSourceKind` | Preguntas fuera del material: el docente ve qué le falta a la cátedra |
| Regenerado | `regeneratesMessageId` | El alumno no confía en la primera respuesta |
| Editado por el alumno | `editedAt`, `editedBy` | Correcciones del alumno (y ruido en la pregunta) |
| A qué mensaje responde | `replyToMessageId`, `replyToSeq` | Reconstruir la árvore de turnos y detectar ramas |
| Cómo terminó | `stopReason`, `errorCode`, `errorMessage` | Errores del servidor, tokens agotados, cancelaciones |
| Modelo que respondió | `modelLabel` | El alumno puede cambiar de modelo a mitad de hilo |
| Rol | `role` | Turns, respuestas y uso de herramientas |

### 8.2 Por hilo (`stats.json`) — para que el docente no relea los mensajes

`ThreadStats` (§2.2) es la suma de lo anterior **por hilo**. El panel del
docente lee `stats.json` de cada hilo (300 B) en vez de los `messages.jsonl`
(66 KB por hilo): **220× menos I/O**.

### 8.3 Por cátedra (`aggregate.json`)

`CourseAggregate` (§2.2) es la suma de los `stats.json`. Se recalcula:

- al importar un `.kitoru`;
- cuando un hilo del docente cambia;
- al arrancar, si `computedAt` es anterior al `lastModified` de algún
  `thread.json` (con `FileSystem.lastModifiedMillis`).

Con esto, `AnalyticsAnalyzer` (que hoy es una función pura que recibe
`threads` y `messages`) recibe **el agregado** y deja de necesitar los
mensajes. Se mantiene la función pura —regla 3 del proyecto—: el agregador es
otro módulo puro que se testea igual.

### 8.4 Dos correcciones que hay que hacer en el modelo

**(a) La API key no se persiste.** `ChatThread.effectiveModel: ModelPolicy`
guarda el `ModelPolicy` completo, con `apiKey`. Hay que agregar a
`ModelPolicy`:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ModelPolicy.kt
/**
 * Copia sin la clave de API (y sin las rutas locales del equipo).
 *
 * Lo que se persiste en un hilo y lo que se exporta en un `.kitoru` es
 * siempre esta versión: la credencial de la cátedra no se escribe en el
 * disco del alumno ni viaja en una exportación.
 */
fun redacted(): ModelPolicy = copy(
    apiKey = "",
    modelsDir = "",
    llamaServerPath = "",
)
```

Y `ChatThread.effectiveModel` se guarda siempre con `redacted()`.

**(b) Los tokens reales.** Hoy `tokensIn/tokensOut` son estimados. Para tener
los reales hay que tocar dos módulos:

```kotlin
// llm-api/src/commonMain/kotlin/llm/api/LlmGateway.kt
sealed class LlmChunk {
    data class Text(val delta: String) : LlmChunk()
    data class ToolCall(val id: String, val name: String, val argsJson: String) : LlmChunk()

    /** Uso de tokens del servidor (último chunk del stream). */
    data class Usage(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0,
    ) : LlmChunk()
}
```

En `OpenAiCompatibleGateway.chatStream`: enviar
`stream_options: {"include_usage": true}` en el body y, cuando el chunk del
servidor trae `"usage"`, emitir `LlmChunk.Usage(...)`. (llama.cpp también lo
soporta; si el servidor no lo manda, queda `Usage` ausente y se usa el
estimador.)

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/TokenEstimate.kt
package kitoru.domain

/**
 * Estimador de tokens (fallback cuando el servidor no manda `usage`).
 *
 * Aproximación para español e inglés: ~4 caracteres por token.
 */
object TokenEstimator {
    fun estimate(text: String): Int =
        if (text.isBlank()) 0 else (text.length + 3) / 4
}
```

Y en `:backend`, `AgentLoop` debe re-emitir el uso:

```kotlin
// backend/src/commonMain/kotlin/rag/agent/AgentLoop.kt
/** Evento nuevo: el servidor mandó el uso de tokens. */
data class Usage(val promptTokens: Int, val completionTokens: Int) : AgentEvent()

/** Respuesta completa del loop. */
data class AgentAnswer(
    val text: String,
    val toolCalls: List<ExecutedTool> = emptyList(),
    /** Uso de tokens real (null si el servidor no lo mandó). */
    val usage: TokenUsage? = null,
)
```

Con eso, el mensaje se guarda con `tokensEstimated = (usage == null)`.

---

## 9. Mapa de cambios

| Archivo | Cambio | Prioridad |
|---|---|---|
| `core/.../domain/Chat.kt` | Modelos finales (§2.1) | Alta |
| `core/.../domain/ChatStats.kt` | `ThreadStats`, `CourseAggregate`, `StudentAggregate`, `DayCount` | Alta |
| `core/.../domain/ThreadTitles.kt` | Titulado automático (§3.2) | Alta |
| `core/.../domain/NoSourceMarker.kt` | Detección de "no encontré la fuente" (§2.3) | Media |
| `core/.../domain/JsonlStore.kt` | Store JSONL append-only (§5.5) | Alta |
| `core/.../domain/JsonStore.kt` | Usar `AtomicFiles.writeText` | Alta |
| `core/.../domain/FileChatRepository.kt` | Repositorio nuevo por layout (§6.2) | Alta |
| `core/.../domain/FileRepositories.kt` | **Borrar** `FileThreadRepository` y `FileMessageRepository` (o dejarlos como fachadas deprecadas) | Alta |
| `core/.../domain/Repositories.kt` | `ChatRepository` (§6.2) | Alta |
| `core/.../domain/ChatExport.kt` | Formato `.kitoru` (§6.3) | Alta |
| `core/.../domain/DeletionLog.kt` | Auditoría de borrados (§7.3) | Media |
| `core/.../domain/ModelPolicy.kt` | `redacted()` (§8.4) | Alta |
| `core/.../domain/AnalyticsAnalyzer.kt` | Consumir `CourseAggregate` en vez de releer mensajes (§8.3) | Media |
| `core/.../platform/FileSystem.kt` | `move`, `sync`, `lastModifiedMillis` (§5.6) | Alta |
| `core/.../platform/AtomicFiles.kt` | Escrituras atómicas + `recover` (§5.6) | Alta |
| `core/.../platform/Ids.kt` | `expect fun newId(): String` (§4.2) | Alta |
| `core/.../platform/FileKey.kt` | `safeFileKey` (§5.1) | Media |
| `core/.../jvmCommonMain/.../FileSystem` | Implementación de `move`/`sync` (§5.6) | Alta |
| `llm-api/.../LlmGateway.kt` | `LlmChunk.Usage` + `stream_options` (§8.4) | Media |
| `backend/.../AgentLoop.kt` | `AgentEvent.Usage`, `AgentAnswer.usage` (§8.4) | Media |
| `backend/.../RagEngine.kt` | Instrucción del marcador `NO_ENCONTRADO_EN_MATERIAL` (§2.3) | Media |
| `core/.../domain/Course.kt` | `chatRetentionDays` (§7.4) | Baja (coordinar con el doc de cátedra) |

## 10. Tests que hay que escribir

| Test | Qué verifica |
|---|---|
| `ThreadTitlesTest` | Derivación del título: primera línea, markdown, corte a 48, título manual no se toca, recálculo si se edita el primer mensaje |
| `JsonlStoreTest` | Append, lectura, línea corrupta saltada, reparación de la cola partida, escritura atómica (simular `.tmp` huérfano) |
| `AtomicFilesTest` | Que quede el `.tmp` si el `move` falla; que `recover` promueva el `.tmp` nuevo |
| `FileChatRepositoryTest` | Listar por cátedra y por alumno, append, `seq` monotónico, borrado lógico, `purge` |
| `ChatExportTest` | Export → import round-trip; idempotencia (importar dos veces); `scope = METRICS` no contiene texto; la API key no aparece en el archivo |
| `NoSourceMarkerTest` | Marker, heurística y falsos positivos |
| `TokenEstimatorTest` | Estimación y el flag `tokensEstimated` |
| `RetentionTest` | Purga por `purgeAfter`, purga por retención conservando `stats.json`, registro en `deletions.log` |

---

## 11. Resumen

1. Los ids son **UUID puros** con FKs explícitas; el índice son las carpetas
   (`courses/<courseId>/chats/<authorId>/<threadId>/`), no un `startsWith`.
2. La persistencia es **un directorio por hilo** con `messages.jsonl` en modo
   append (1 KB por mensaje) en vez de un `messages.json` global (200 MB por
   reescritura).
3. Toda escritura pasa por **`.tmp` + `fsync` + `rename`**: hoy un corte a
   mitad de escritura deja la app sin chats.
4. El **título automático** es determinista (primera línea del primer mensaje
   del alumno, 48 chars) y se congela con `titleSource = MANUAL`; el orden de
   los mensajes lo da `seq`, no el reloj del dispositivo.
5. El docente ve los chats del alumno **sólo si éste los exporta** (`.kitoru`
   versionado, idempotente por id) o si el servidor de la cátedra se los
   manda; el borrado es **lógico y auditado** (`deletedAt`/`deletedBy`/
   `deletions.log`), y la analítica del docente corre sobre `stats.json` y
   `aggregate.json`, sin releer un solo mensaje.
