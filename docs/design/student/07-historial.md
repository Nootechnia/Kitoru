# 07 · Historial de conversaciones del alumno

> Cómo el alumno **organiza**, **revisa**, **busca** y **comparte** sus
> conversaciones con la IA de una cátedra.
>
> Depende de: `docs/design/CONTEXTO.md`, el dominio `kitoru.domain`
> (`Chat.kt`, `Analytics.kt`, `AnalyticsAnalyzer.kt`, `Roles.kt`, `Course.kt`),
> la UI de chat de FunshiEngineAI (`app/core/ChatTree.kt`, `app/chat/ChatSidebar.kt`,
> `app/chat/ChatLibrary.kt`, `app/chat/ChatViewModel.kt`) y
> los docs hermanos `docs/design/teacher/07-chats.md` (el otro lado del paquete
> `.kitoru`), `docs/design/teacher/08-alumnos.md` (visibilidad y "Privacidad y datos")
> y `docs/design/domain/08-permisos.md` (capacidades y auditoría).

---

## 0. Resumen en cinco líneas

1. **No hay árbol de carpetas anidables.** El modelo es **lista plana + grupos de un
   solo nivel + etiquetas**; la UI es **adaptativa**: en desktop los grupos son
   secciones plegables con drag & drop, en móvil la lista es plana y el grupo es un
   filtro. Anidar en un teléfono es una molestia y en un escritorio es un lujo que
   el estudiante real no necesita.
2. El **título automático** sale del primer mensaje del alumno con el patrón de
   `ChatLibrary.persistActiveChat` (`autoTitle` + derivación idempotente) y se congela
   en cuanto el alumno renombra a mano (con acción para volver al automático).
3. Cada hilo tiene **siete acciones**: continuar, renombrar, etiquetar, mover de
   grupo, duplicar/ramificar, exportar/compartir y eliminar; todas mapeadas a
   capacidades de `docs/design/domain/08-permisos.md` (`CHAT_VIEW_OWN`,
   `CHAT_RENAME_TAG`, `CHAT_DELETE_OWN`).
4. **Exportar es el camino hacia el docente**: el paquete `.kitoru` (ZIP con
   `manifest.json`, `threads.json`, `messages.json`, `groups.json`, `attachments/`)
   es el mismo formato que el importador del docente, con `formatVersion`, `sha256`
   de contenido y **firma Ed25519 opcional** que prueba integridad, no autoría.
5. La persistencia pasa de "un `messages.json` que se reescribe entero" a
   **un directorio por cátedra con un archivo por hilo** (`meta.json` +
   `messages.jsonl` append-only): escribir un mensaje pasa de reescribir 2,7 MB a
   anexar 900 bytes (~3000× menos E/S), y el alumno ve **los mismos números que ve su
   docente**, calculados por el mismo `AnalyticsAnalyzer`.

---

## 1. Decisiones en una línea

| # | Decisión | Motivo |
|---|---|---|
| D1 | **Lista plana + grupos de un nivel + etiquetas.** Sin carpetas anidables, sin `parentId`. | El dominio `ChatThread` no tiene jerarquía y `AnalyticsAnalyzer` no la necesita; el árbol obligaría a tocar repositorios, exportación y analítica para modelar un problema que el alumno real no tiene. |
| D2 | La UI es **adaptativa**: `GROUPS` (desktop, ≥ 760 dp) o `FLAT` (móvil). | Las carpetas anidadas son cómodas con mouse y pésimas con dedo. El modelo de datos único, dos presentaciones. |
| D3 | Las **etiquetas** son multi-valor y libres; los **grupos** son de un solo nivel y excluyentes. | Una conversación sobre "complejidad" pertenece a "Unidad 3" **y** a "Parcial 1": las jerarquías obligan a elegir un hogar, las etiquetas no. |
| D4 | Título automático con el patrón `autoName` de FunshiEngineAI; el renombrado manual lo congela. | Comportamiento conocido y ya probado por los usuarios de FunshiEngineAI; además el título es *texto del alumno* y en `METRICS_ONLY` el docente no lo ve. |
| D5 | "Continuar" = abrir el hilo **restaurando el modelo y el historial**; "ramificar" = abrir un hilo nuevo desde un mensaje. | "Continuar" no necesita una acción propia: abrir el hilo ya lo hace. La acción útil es *ramificar* (probar otra variante sin arruinar el original). |
| D6 | El paquete `.kitoru` **es el mismo** que importa el docente (`formatVersion: 1`), con `groups.json` y `attachments/` opcionales. | Un solo formato, dos lectores. La compatibilidad hacia adelante se logra con campos opcionales e `ignoreUnknownKeys = true`. |
| D7 | La firma Ed25519 es **opcional** y prueba **integridad del archivo**, no autoría del contenido. | Prometer más de lo que se puede probar es la peor falla de un sistema de confianza. |
| D8 | La búsqueda de texto reusa el **BM25 de `:backend`** (`rag.retrieval.Bm25Index`), igual que el lado docente. | Regla 1 del CONTEXTO: reutilizar, no reescribir. |
| D9 | Persistencia: **un directorio por cátedra, un directorio por hilo**, `meta.json` + `messages.jsonl` (append-only). | Reescribir 2,7 MB por cada mensaje es el defecto de escala más caro que tiene hoy el repo. |
| D10 | La pantalla del alumno usa **el mismo `AnalyticsAnalyzer`** que el del docente. | La transparencia real es "los mismos números", no "números parecidos". |

---

## 2. Estructura: hilos, grupos y etiquetas

### 2.1 El problema, con números

Una cursada: 3 cátedras × 40 hilos × 15 mensajes ≈ **1 800 mensajes** por alumno en
un semestre. Es una lista de **40 elementos por cátedra**: chico. La necesidad real del
alumno no es *almacenamiento jerárquico* sino **volver a encontrar** algo:

> "donde estaba mi duda sobre el SVM con margen" → ¿en qué chat?

Un árbol de carpetas responde a "¿dónde lo guardé?" (una pregunta de almacenamiento).
La búsqueda y las etiquetas responden a "¿dónde estaba?" (una pregunta de
recuperación). La segunda es la que el alumno se hace 40 veces por semana; la primera,
2 veces.

### 2.2 Por qué no carpetas anidables (el argumento)

1. **El coste de un árbol no está en el modelo, está en la interacción.**
   `ChatTree` de FunshiEngineAI tiene un árbol enlazado, un índice `id → posición`,
   un estado `expandedIds` que hay que persistir, `isSelfOrDescendant` para evitar
   carpetas dentro de sí mismas, y un drag & drop **escrito a mano** (~150 líneas
   de `detectDragGestures` + `boundsInRoot` + hit-testing de filas, en
   `ChatSidebar.kt`). Ese drag & drop **no existe en un teléfono**: no hay arrastre
   fino, hay pulsación larga, y el resultado de soltar sobre una fila a 400 px de
   distancia es impredecible. Escribirlo para desktop y tener que desactivarlo en
   Android es el peor de los dos mundos.
2. **El anidamiento profundo rompe la cognición.** Un árbol de altura 4 con 40 hojas
   exige recordar *la ruta*, no el contenido. En una lista de 40 elementos, el
   recuerdo se apoya en la forma de la pregunta y en las fechas, que es lo que de
   verdad se recuerda.
3. **Las carpetas fuerzan una pertenencia única; la materia no la tiene.** Un hilo
   "¿me explicás el margen?" sirve para la Unidad 3 y para el Parcial 1. En un árbol
   hay que elegir carpeta y duplicar el hilo o dejar de usarlo ("el hilo del parcial
   está en la carpeta de la unidad y nunca lo encuentro"). Las **etiquetas** resuelven
   esto de forma nativa: un hilo con `["unidad-3", "parcial-1"]` aparece en ambas
   vistas sin duplicar nada.
4. **El dominio ya está}/>
 plano y no debería engordar.** `ChatThread` tiene `tags: List<String>` desde el
   principio y `AnalyticsAnalyzer` agrupa por `authorId`. Meter `parentId` obliga a
   tocar `ThreadRepository`, `MessageRepository`, la exportación, la importación y la
   analítica para soportar algo que el producto no pide.
5. **El efecto "carpeta vacía".** Un árbol jerárquico produce ramas abandonadas:
   el alumno crea "TP2", deja dos chats ahí, y vuelve a la raíz. La lista plana con
   grupos de un nivel y búsqueda no produce basura estructural.

**Contra-argumento honesto:** en desktop, arrastrar un chat a una carpeta es
satisfactorio y muchos lo esperan. Por eso **los grupos existen** (D1/D2): se
conservan las secciones y el arrastre horizontal, pero **sin anidamiento**: una sola
línea de grupos, cada chat en exactamente un grupo o ninguno. El 90 % del placer del
drag & drop, con el 10 % del coste, y sin la necesidad de tener que resolver "soltar una
carpeta dentro de sí misma".

### 2.3 Lo que sí: lista plana + grupos de un nivel + etiquetas

| Concepto | ¿Anida? | ¿Multi-valor? | Ejemplo | Dónde vive |
|---|---|---|---|---|
| **Hilo** (`ChatThread`) | — | — | "¿Me explicás SVM con margen?" | `kitoru.domain.ChatThread` (ya existe) |
| **Grupo** (`ThreadGroup`) | **No** (1 nivel) | No (un grupo o ninguno) | "Unidad 3", "Parcial 1", "Dudas sueltas" | `kitoru.domain.ThreadGroup` (nuevo) |
| **Etiqueta** (`ChatThread.tags`) | — | **Sí** (hasta 6) | `["parcial-1", "svm", "duda"]` | `ChatThread.tags` (ya existe) |
| **Fijado** (`ChatThread.pinned`) | — | — | arriba de todo, siempre | nuevo campo |

Reglas:

- Un grupo pertenece a **una cátedra** (`ThreadGroup.courseId`).
- Un hilo pertenece a **una cátedra** y a **cero o un grupo**.
- Un grupo **no** puede contener otro grupo. No hay "grupos de grupos".
- Las etiquetas son **globales a la app** (el alumno las ve en todas sus cátedras) pero
  se filtran por cátedra en el panel de filtros.
- Un hilo **fijado** (`pinned`) aparece arriba de la lista en cualquier modo y en
  cualquier cátedra.
- Borrar un grupo **no borra hilos**: los hilos quedan "sin grupo" (`groupId = ""`).
  Es la misma política que en el correo: borrar una etiqueta no borra el mensaje.

### 2.4 La solución adaptativa

El **modelo de datos es uno**; lo que cambia es la **presentación**:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/history/ThreadListMode.kt
enum class ThreadListMode {
    /** Desktop: los grupos son secciones plegables, con drag & drop. */
    GROUPS,

    /** Móvil: lista única ordenada por actividad; el grupo es un filtro. */
    FLAT,
}

/**
 * El mismo breakpoint que usa el resto de la app
 * (`docs/design/teacher/01-navegacion.md` §6 y `07-chats.md` §2.2).
 */
fun threadListMode(widthDp: Int): ThreadListMode =
    if (widthDp >= 760) ThreadListMode.GROUPS else ThreadListMode.FLAT
```

**`GROUPS` (desktop, ≥ 760 dp)** — el sidebar de la cátedra:

```
┌─ Conversaciones · IA-2026-1 ────────────────┐
│ [🔍 buscar en el historial]      [+ Nuevo]   │
│ chips: [Todo] [Unidad 3] [Parcial 1] [sin gr.]│
├──────────────────────────────────────────────┤
│ 📌 Fijados                                     │
│   📄 Duda sobre SVM con margen        14 msj  │
├──────────────────────────────────────────────┤
│ ▼ Unidad 3                          ⋮ ⋮ ⋮    │
│   📄 Convexidad y separación de hiperplanos  │
│   📄 Optimización combinatoria                │
├──────────────────────────────────────────────┤
│ ▼ Parcial 1                        ⋮ ⋮ ⋮    │
│   📄 Teoría de SVM                    22 msj  │
├──────────────────────────────────────────────┤
│ ▸ Sin grupo (12)                            │
└──────────────────────────────────────────────┘
```

- Los grupos son secciones plegables; el estado de plegado vive en el `ViewModel`
  (no se persiste: se reabre todo expandido, que es lo razonable).
- **Drag & drop** (simplificado respecto de FunshiEngineAI): soltar un hilo sobre
  la cabecera de un grupo lo mueve a ese grupo; soltar entre dos filas reordena
  dentro del grupo. **No hay arrastre anidado** → no existe el caso "soltar una
  carpeta dentro de sí misma" ni hace falta `isSelfOrDescendant`.
- El estado de orden manual por hilo (`ChatThread.manualOrder`? no) — **decisión: no
  hay orden manual**. El orden dentro de un grupo es por `updatedAt` descendente.
  Razón: el orden manual obliga a guardar un campo más, a resolver colisiones y a
  pelearse con la búsqueda; y un hilo que el alumno acaba de tocar ya está arriba.
  El "arriba" lo resuelve `pinned`.

**`FLAT` (móvil, < 760 dp)** — una sola lista:

```
┌─ Historial · IA-2026-1 ──────────────────────┐
│ [🔍 buscar…]                                  │
│ chips: [Todos] [Unidad 3] [Parcial 1] [+ eti] │
├──────────────────────────────────────────────┤
│ 📌 Duda sobre SVM con margen          14 msj │
│    Unidad 3 ·ayer 18:24 · 💬 3 citas        │
│ 📄 Convexidad y separación…           6 msj │
│    Unidad 3 ·lun 12/10                       │
│ 📄 Teoría de SVM                     22 msj │
│    Parcial 1 ·vie 10/10                      │
└──────────────────────────────────────────────┘
```

- El grupo aparece como **subtítulo de la fila** (chip pequeño), no como sección.
- Filtrar por grupo = tocar un chip de la fila de filtros.
- Mover de grupo = menú contextual → "Mover a grupo…" (hoja inferior con la lista
  de grupos + "Sin grupo" + "Nuevo grupo").
- **No hay drag & drop en Android**: no existe arrastre fino confiable.

**Modo `SEARCH`**: si hay una consulta de texto activa, **los dos modos** ceden a una
lista plana ordenada por puntaje (el agrupar por grupo estorba al ranking). Es el
mismo criterio que usa el docente en `07-chats.md` §5.2.

### 2.5 Modelo de dominio

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ThreadGroup.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Un grupo de conversaciones del alumno dentro de una cátedra.
 *
 * **Nunca anida**: un grupo no puede contener otro grupo. Es la decisión D1:
 * las carpetas jerárquicas son cómodas con mouse y una molestia con el dedo, así
 * que Kitoru conserva lo útil (secciones, arrastre, agrupación) y descarta lo
 * caro (la jerarquía).
 *
 * Es una estructura **local del alumno**: la cátedra no la ve, no la usa para
 * nada. Se exporta en el paquete `.kitoru` para que el docente entienda cómo se
 * organizó el trabajo, pero no altera la analítica.
 */
@Serializable
data class ThreadGroup(
    /** Id: `"<courseId>|grupo|<uuid>"`. */
    val id: String,
    val courseId: String,
    /** Nombre visible ("Unidad 3"). */
    val name: String,
    /** Orden de aparición entre los grupos de la cátedra. */
    val order: Int = 0,
    val createdAt: Long = 0L,
) {
    val label: String get() = name.trim().ifBlank { "Grupo sin nombre" }
}
```

Cambios en `ChatThread` (`core/src/commonMain/kotlin/kitoru/domain/Chat.kt`):

```kotlin
@Serializable
data class ChatThread(
    val id: String,                     // "<courseId>|hilo|<uuid>"
    val courseId: String,
    val authorId: String,
    val authorName: String = "",
    val title: String = "Conversación",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val modelLabel: String = "",
    val effectiveModel: ModelPolicy = ModelPolicy(),
    val tags: List<String> = emptyList(),

    // ---- cambios de este doc ----

    /**
     * Id del grupo del alumno ("<courseId>|grupo|<uuid>"). Vacío = sin grupo.
     * Un hilo pertenece a **cero o un** grupo: los grupos no anidan.
     */
    val groupId: String = "",

    /**
     * `true` mientras el título se derive del primer mensaje del alumno.
     * Renombrar a mano lo pone en `false`; "Volver al título automático" lo
     * vuelve a `true`. Es el mismo patrón que `ChatNode.Chat.autoName` de
     * FunshiEngineAI (`app/core/ChatTree.kt`).
     */
    val autoTitle: Boolean = true,

    /** Fijado: aparece arriba de la lista en cualquier modo. */
    val pinned: Boolean = false,

    /** Id del hilo del que es copia (si el alumno duplicó o ramificó). */
    val duplicateOf: String = "",

    // ---- cambios propuestos por el doc del docente (07-chats.md §8.5) ----
    val origin: ThreadOrigin = ThreadOrigin.LIVE,
    val originPackageId: String = "",
) {
    val isPinned: Boolean get() = pinned
}
```

Y `ChatMessage` gana `attachments` (propuesto por `07-chats.md` §4.5):

```kotlin
@Serializable
data class ChatAttachment(
    val name: String,
    /** Ruta relativa dentro del paquete (o del directorio del hilo). */
    val path: String,
    val sizeBytes: Long = 0L,
    val mime: String = "",
)
```

> **Nota de visibilidad (consistencia con `07-chats.md` §3.3).** El título, las
> etiquetas y el **nombre del grupo** son texto escrito por el alumno: en
> `METRICS_ONLY` el docente **no** los ve (se muestran como "Conversación N"). Por
> eso el paquete `.kitoru` que el alumno comparte lleva esa advertencia explícita
> (§5.6): compartir el paquete **revela los títulos y los grupos**, aunque la
> visibilidad de la inscripción sea "Solo métricas".

### 2.6 El árbol de carpetas de FunshiEngineAI: qué se porta y qué no

| Origen (FunshiEngineAI) | Decisión | Motivo |
|---|---|---|
| `app/core/ChatTree.kt` → `ChatTree`, `ChatNode.Folder` | **NO portar** | Modelo jerárquico; el modelo de Kitoru es plano + grupos de un nivel. Portarlo significaría mantener un árbol que la UI no muestra. |
| `app/core/ChatTree.kt` → `ChatNode.Chat.autoName` | **Portar el patrón** | Se conserva como `ChatThread.autoTitle` (D4). |
| `app/core/ChatTree.kt` → `ChatTree.flatten` | **Portar la idea** | Se reemplaza por `ThreadListBuilder.flatten(groups, threads, mode, expanded)`, sin `depth`. |
| `app/chat/ChatLibrary.kt` → `persistActiveChat` | **Portar el patrón** | Derivar el título mientras `autoTitle`; ver §3. |
| `app/chat/ChatLibrary.kt` → `renameNode` | **Portar** | `renameThread(id, name)` → `autoTitle = false`. |
| `app/chat/ChatLibrary.kt` → `moveNode` / `dropOnto` | **Portar simplificado** | Sólo del mismo nivel: mover a un grupo o reordenar. Sin descendientes. |
| `app/chat/ChatSidebar.kt` → `ChatRowItem` (chevron, padding, fondo activo) | **Portar** | `ThreadRow` + `GroupHeader` reusan el aspecto. |
| `app/chat/ChatSidebar.kt` → drag & drop manual (`boundsInRoot` + hit-test) | **Portar la idea, reescribir el mecanismo** | En desktop se mantiene (mouse); en Android no. El hit-test se hace contra las cabeceras de grupo, no contra todas las filas: son 3–6 objetivos, no 40. |
| `app/chat/ChatSidebar.kt` → menú contextual con click derecho | **Portar** | En desktop: `DropdownMenu`. En Android: hoja inferior (no hay click derecho). |
| `app/chat/ChatViewModel.kt` → `newChatId()` | **Portar con otro formato** | `newId(prefix) = "$courseId|$prefix|${uuid4()}"` (§2.7). |

### 2.7 Convenciones de id

El CONTEXTO ya define el criterio (`"curso|hilo|uuid"`), que permite filtrar por
prefijo sin joins:

| Entidad | Formato | Ejemplo |
|---|---|---|
| Hilo | `"<courseId>\|hilo\|<uuid>"` | `c-ia26\|hilo\|9f2c…` |
| Mensaje | `"<threadId>\|msj\|<uuid>"` | `c-ia26\|hilo\|9f2c…\|msj\|0a1b…` |
| Grupo | `"<courseId>\|grupo\|<uuid>"` | `c-ia26\|grupo\|7d3e…` |
| Material | `"<courseId>\|mat\|<uuid>"` | `c-ia26\|mat\|4c8f…` |

`FileMessageRepository.listForCourse` sigue funcionando con
`threadId.startsWith("$courseId|")` (ya está escrito así en `FileRepositories.kt`).

---

## 3. Título automático y renombrado

### 3.1 El patrón que se porta

De `app/chat/ChatLibrary.kt` (FunshiEngineAI):

```kotlin
internal fun ChatViewModel.persistActiveChat() {
    val chat = tree.nodeOf(activeChatId) as? ChatNode.Chat ?: return
    val stored = messages.filter { it.text.isNotBlank() && it.text != "…" }
        .map { ChatMessages.toStored(it) }
    // El nombre sigue derivándose del primer mensaje sólo si el usuario
    // nunca lo renombró a mano.
    if (chat.autoName) {
        stored.firstOrNull { it.role == "user" }
            ?.text?.lineSequence()?.firstOrNull()?.take(40)?.ifBlank { null }
            ?.let { chat.name = it }
    }
    chat.messages = stored
    persistTree()
    refreshRows()
}
```

Cuatro cosas se conservan: (a) se deriva del **primer mensaje del usuario**, (b) sólo
si el usuario **nunca renombró a mano** (`autoName`), (c) es **idempotente** (siempre
sale lo mismo), (d) ocurre al **persistir**, no en un observer.

Una cosa se corrige: FunshiEngineAI corta con `.take(40)` a mitad de palabra
("¿Me explicás SVM con marg…en" no, pero "El teorema de la incompletitud de Gödel
seg…"). Kitoru corta en un límite de palabra y agrega "…".

### 3.2 `ThreadTitle.derive` (función pura, en `:core`)

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ThreadTitle.kt
package kitoru.domain

/**
 * Deriva el título automático de un hilo a partir del primer mensaje del alumno.
 *
 * Es una **función pura** (regla 3 del CONTEXTO): sin disco, sin red, sin reloj
 * (la fecha de respaldo se pasa como parámetro). Por eso es testeable al 100 %.
 */
object ThreadTitle {

    /** Largo máximo del título. */
    const val MAX_LENGTH = 48

    /** Prefijos de markdown que se limpian del principio de la línea. */
    private val MARKDOWN_PREFIX = Regex("^\\s*(#{1,6}\\s*|>\\s*|[-*+]\\s+|\\d+[.)]\\s+)+")

    /**
     * Título a partir del primer mensaje del alumno.
     *
     * @param firstUserMessage texto del primer mensaje del usuario (puede ser vacío).
     * @param fallback título de respaldo si el mensaje no sirve (ej. "Conversación 21/10").
     */
    fun derive(firstUserMessage: String, fallback: String): String {
        val line = firstUserMessage.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.let { MARKDOWN_PREFIX.replace(it, "") }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

        if (line.length < 3) return fallback

        if (line.length <= MAX_LENGTH) return line

        // Corte en límite de palabra.
        val cut = line.take(MAX_LENGTH - 1)
        val lastSpace = cut.lastIndexOf(' ')
        val head = if (lastSpace >= MAX_LENGTH / 2) cut.take(lastSpace) else cut
        return "$head…"
    }
}
```

Ejemplos (tabla de test):

| Primer mensaje | Título derivado |
|---|---|
| `¿Me explicás SVM con margen?` | `¿Me explicás SVM con margen?` |
| `# Resumen de la unidad 3\n\n…` | `Resumen de la unidad 3` |
| `> - **a)** 3x + 2y = 7` | `**a)** 3x + 2y = 7` |
| `ok` | `Conversación 21/10 18:24` (fallback) |
| `a` | `Conversación 21/10 18:24` (fallback: < 3 caracteres) |
| `El teorema de la incompletitud de Gödel según Church es fundamental para entender la recursión` | `El teorema de la incompletitud de Gödel según…` (48) |
| `   ` (vacío) | fallback |

### 3.3 Cuándo se deriva

En el mismo lugar donde hoy se persiste el hilo, pero **una sola vez por mensaje
nuevo** (no en cada guardado):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ThreadTitle.kt (extensión)
// Se llama al enviar el PRIMER mensaje del usuario de un hilo.
fun ChatThread.deriveTitleIfNeeded(firstUserMessage: String, fallback: String): ChatThread =
    if (!autoTitle || title.isNotBlank() && title != "Conversación") this
    else copy(title = ThreadTitle.derive(firstUserMessage, fallback))
```

Reglas:

- **Se deriva cuando llega el primer mensaje del usuario** del hilo. Si el hilo ya
  tenía un título (heredado de una conversación duplicada), no se toca.
- **Es idempotente**: derivar dos veces da el mismo resultado.
- **Si el alumno edita el primer mensaje** (FunshiEngineAI permite editar y
  reenviar), el título **se vuelve a derivar** si `autoTitle` sigue en `true`, y la
  UI avisa con un Snackbar: *"Título actualizado: «…»"*. Si el alumno lo renombró a
  mano, el título no se toca nunca más.
- **Si el hilo queda vacío** (se crea y se borra todo), el título queda
  `"Conversación"` y `autoTitle` sigue en `true`: el próximo mensaje lo derivará.

### 3.4 Renombrado manual

`RenameThreadDialog`:

```
┌─ Renombrar conversación ─────────────────────┐
│  [ ¿Me explicás SVM con margen?           ]  │
│  El título automático se desactivó.          │
│  [ ] Volver al título automático             │
│                                                │
│  [ Cancelar ]              [ Guardar ]        │
└────────────────────────────────────────────────┘
```

- Guardar → `thread.copy(title = texto.trim(), autoTitle = false)`.
- El checkbox "Volver al título automático" → `autoTitle = true` y el título se
  vuelve a derivar en el próximo guardado (o inmediatamente, si hay mensajes).
- **Límites**: 1–120 caracteres; se recorta a 120 con aviso; no se permite vacío
  (se usa "Conversación").
- El diálogo es **el mismo** para desktop y Android (`AlertDialog` de Material 3).

### 3.5 El título en los otros lugares

| Lugar | Qué se muestra |
|---|---|
| Sidebar / lista | `thread.title` |
| Encabezado del chat | `thread.title` + chips (grupo, etiquetas, modelo, fecha) |
| Exportación Markdown | `# <título>` |
| Paquete `.kitoru` | `threads.json` incluye `title` |
| Docente (`METRICS_ONLY`) | `"Conversación N"` (§3.3 de `07-chats.md`) |
| Resumen del alumno | `thread.title` (es suyo) |

---

## 4. Acciones por hilo

### 4.1 El menú

**Desktop** (`DropdownMenu` al hacer click derecho en la fila, o el botón `⋮`):

```
┌──────────────────────────────┐
│ Continuar                    │   ← abrir con el mismo contexto
│ Renombrar…                   │
│ Etiquetar…                   │
│ Mover a grupo…               │
│ Fijar / Desfijar             │
│ ─────────────────────────────│
│ Duplicar                     │
│ Ramificar desde…             │
│ ─────────────────────────────│
│ Exportar…                    │
│ Compartir…                   │
│ ─────────────────────────────│
│ Eliminar…                    │
└──────────────────────────────┘
```

**Android**: hoja inferior (`ModalBottomSheet`) con los mismos ítems, en el mismo
orden, con iconos.

### 4.2 Continuar — abrir con el mismo contexto

"Continuar" **no es una acción especial**: es lo que pasa al abrir un hilo. Lo que
la acción hace es **declarar explícitamente** qué contexto se restaura, y eso es
lo que el alumno necesita saber ("¿esto me va a mostrar lo mismo que antes?").

Al abrir un hilo, la app restaura:

| Contexto | Origen | Efecto |
|---|---|---|
| **Modelo y parámetros** | `ChatThread.effectiveModel` | Se reaplica la `ModelPolicy` con la que se generó: modo (REMOTE/LOCAL), base URL, modelo, temperatura, `maxTokens`, `topK`, contexto. Si el alumno había ajustado algo (agencia del alumno, regla 5), se restauró **su** ajuste. |
| **Historial** | Los últimos mensajes del hilo | El próximo envío manda como historial los últimos `historyTurns * 2` mensajes (12 por defecto: `RagConfig.historyTurns = 6`). |
| **Sources citadas** | `ChatMessage.sources` | Cada respuesta vieja conserva **las fuentes con las que se generó**: la transcripción no cambia aunque el corpus cambie. |
| **Herramientas concedidas** | `ChatThread` / estado del hilo | Las categorías de herramientas concedidas ("archivos", "web") se mantienen, como en `ChatNode.Chat.grantedCats` de FunshiEngineAI. |
| **Etiquetas y grupo** | `ChatThread` | Se restauran (no cambian). |

**Avisos al continuar** (calculados, no inventados):

1. **Cambió la configuración de la cátedra**:

   > La configuración del modelo cambió desde esta conversación.
   > Antes: `gpt-oss-20b`, temperatura 0.2 · Ahora: `phi-4-mini`, temperatura 0.4.
   > [Usar la nueva] [Usar la de aquella vez]

   La comparación es `ModelPolicy` vs `ModelPolicy`: campos `modelName`,
   `temperature`, `maxTokens`, `topK`, `mode`, `remoteBaseUrl`.

2. **Cambió el material de la cátedra**:

   > El material de la cátedra cambió desde tu última conversación
   > (2 materiales nuevos, 1 modificado). Las respuestas viejas siguen
   > mostrando las fuentes con las que se generaron; las nuevas usan el material
   > actual.

   Se calcula comparando `Material.uploadedAt` con `thread.updatedAt`.

3. **La visibilidad cambió**: si el docente bajó la visibilidad, la app lo avisa al
   abrir ("Tu docente cambió la visibilidad de tus conversaciones a *Solo métricas*
   el 12/03") — es el aviso que ya define `08-alumnos.md` §6.7.

### 4.3 Ramificar desde…

Crear un hilo **nuevo** que hereda los mensajes hasta un punto chosen. Es la acción
que realmente aporta: permite "probar otra respuesta" o "cambiar de enfoque" sin
ensuciar el hilo original (que además puede estar compartido con el docente).

```
┌─ Ramificar conversación ──────────────────────┐
│  Nueva conversación a partir de:              │
│                                                 │
│  ● … hasta el final (22 mensajes)             │
│  ○ después de:                                 │
│     ▸ 12/03 18:24  ¿Me explicás SVM con margen?│
│     ▸ 12/03 18:25  El margen es la distancia…  │
│     ▸ 12/03 18:31  ¿Y con margen suave?        │
│                                                 │
│  Título: [ ¿Y con margen suave?            ]  │
│  Grupo: [ Unidad 3 ▾ ]   Etiquetas: [svm, …]  │
│                                                 │
│  [ Cancelar ]        [ Ramificar ]             │
└─────────────────────────────────────────────────┘
```

- El nuevo hilo tiene id nuevo, `createdAt = now`, `autoTitle = false`,
  `duplicateOf = <id del original>`, `groupId` y `tags` heredados,
  `effectiveModel` heredado.
- **El original no se toca.** Es la diferencia con "Duplicar".
- El docente ve el nuevo hilo con el chip "copia de «…»" (`duplicateOf`).

### 4.4 Duplicar

Duplicar = ramificar desde el final, con el título `"(copia)"`:

- Título: `"<título original> (copia)"` (con sufijo numérico si ya existe:
  `"… (copia 2)"`).
- `autoTitle = false`, `duplicateOf = <id original>`.
- Copia **todos** los mensajes, con **los mismos ids de mensaje**? No: los ids de
  mensaje se **regeneran** (`<nuevoThreadId>|msj|<uuid>`), porque un id repetido
  rompería el índice y la deduplicación al importar paquetes.
- `pinned = false`.
- Es la acción que el alumno usa para "probar el mismo tema con otro modelo".

### 4.5 Etiquetar

`ThreadTagsEditor`:

```
┌─ Etiquetas ───────────────────────────────────┐
│  [svm ×] [parcial-1 ×] [duda ×]  [ + etiqueta ]│
│                                                 │
│  Sugeridas:  [ml] [tp2] [ejercicio] [parcial-2]│
│                                                 │
│  Límite: 6 etiquetas por conversación.          │
│  [ Cancelar ]              [ Guardar ]          │
└─────────────────────────────────────────────────┘
```

- Normalización: se comparan **sin acentos y en minúsculas** (usando
  `kitoru.platform.stripDiacritics`, que ya existe) para no duplicar
  "Parcial-1" y "parcial 1"; se **muestra** la forma que el alumno escribió la
  primera vez.
- Máximo 6; la 7ª avisa "Llegaste al máximo de 6 etiquetas."
- Las sugerencias salen de las etiquetas ya usadas **por el mismo alumno** en
  cualquier cátedra.

### 4.6 Mover a grupo / Fijar

- "Mover a grupo…" → hoja con la lista de grupos de la cátedra + "Sin grupo" +
  "Nuevo grupo…" (que abre el diálogo de nombre).
- "Fijar" / "Desfijar" → alterna `pinned`.
- En desktop, arrastrar la fila sobre una cabecera de grupo hace lo mismo.
- Un grupo se renombra desde el `⋮` de su cabecera; se borra desde el mismo `⋮`
  (con confirmación: *"Se borrará el grupo «Unidad 3». Las 8 conversaciones que
  tiene NO se borran: quedan sin grupo."*).

### 4.7 Eliminar

```
┌─ Eliminar conversación ────────────────────────┐
│  ¿Eliminar «Duda sobre SVM con margen»?         │
│  Se borrarán 14 mensajes. Esto no se puede      │
│  deshacer.                                       │
│                                                 │
│  Si ya compartiste un paquete con esta          │
│  conversación, el docente conserva su copia.    │
│                                                 │
│  [ Cancelar ]        [ Eliminar ]               │
└─────────────────────────────────────────────────┘
```

- Borra el directorio del hilo (`courses/<id>/threads/<threadId>/`) y su entrada en el
  índice. Es instantáneo y no deja basura.
- Permiso: `CHAT_DELETE_OWN` (✔ para `ALUMNO` en `08-permisos.md` §3.5).
- Si el hilo estaba **fijado**, se desfija.
- No se puede eliminar durante una generación en curso de ese hilo: primero hay que
  cancelar la respuesta (mismo criterio que `ChatLibrary.deleteNode` de FunshiEngineAI,
  que corta la generación si el hilo borrado es el activo).

### 4.8 Permisos y auditoría

| Acción | Capacidad (`08-permisos.md`) | ¿Auditada? |
|---|---|---|
| Ver el hilo | `CHAT_VIEW_OWN` | No (lo propio no se audita) |
| Enviar mensajes | `CHAT_SEND` | No |
| Crear hilo | `CHAT_CREATE` | No |
| Renombrar / etiquetar / mover / fijar | `CHAT_RENAME_TAG` | No |
| Duplicar / ramificar | `CHAT_CREATE` + `CHAT_VIEW_OWN` | No |
| **Exportar / compartir** | `CHAT_VIEW_OWN` (se exporta lo propio) | **Sí**: se registra un `ExportRecord` local (§5.8) |
| Eliminar | `CHAT_DELETE_OWN` | No (pero sí el borrado masivo, `APP_RESET` / derecho de supresión) |
| Ver "Mi actividad" | `ANALYTICS_VIEW_OWN` | No |
| Ver "Qué ve tu docente" | `ANALYTICS_VIEW_OWN` + `ENROLLMENT_VIEW_OWN` | No |
| Firmar el paquete | `APP_SETTINGS_EDIT` (la clave vive en los ajustes) | No |

> **Por qué no se agrega `CHAT_EXPORT_OWN`:** la matriz de `08-permisos.md` tiene un
> invariante que obliga a registrar cada capacidad nueva en `PermissionMatrix.ROWS`
> y en su test. Exportar lo propio ya está cubierto por `CHAT_VIEW_OWN` (leer lo
> propio) y, para el volcado completo de la instalación, por `APP_DATA_EXPORT`.
> Agregar una capacidad por comodidad es deuda.
>
> **Lo que sí se registra**: cada exportación deja un `ExportRecord` **local del
> alumno** (§5.8), que es el que alimenta el panel "Lo que compartiste" de §8.6.

---

## 5. Exportar

### 5.1 Formatos

| Formato | Dónde | Para qué | Notas |
|---|---|---|---|
| **`.kitoru`** (ZIP) | todas | **el camino hacia el docente**: el paquete que la app del docente importa | Formato canónico; ver §5.2 |
| **Markdown** (`.md`) | todas | leer, imprimir, mandar por WhatsApp/mail, archivar | Texto plano, sin dependencias; incluye cabecera de procedencia |
| **PDF** | desktop (y Android vía servicio de impresión del sistema) | entregar, archivar | En Android se genera con el `PrintManager` del sistema ("Guardar como PDF"); en desktop con una biblioteca PDF de JVM **sin AWT** (`com.fleeksoft:kotlin-pdf`) |
| **JSON** | todas | respaldo técnico, diff, debugging | Mismo esquema que `threads.json` + `messages.json` del paquete |

> Coherencia con el docente: `07-chats.md` §7.1 declara canónicos **Markdown** y
> **JSON**, y el PDF como extra de escritorio. El alumno exporta en **Markdown** y
> **PDF** (lo que un humano lee) y el **`.kitoru`** (lo que la app del docente
> importa). El JSON del alumno es el mismo formato, sin `provenance` de docente.

### 5.2 El paquete `.kitoru`

**Es el mismo formato que importa el docente** (`07-chats.md` §8.1). Un solo
formato, dos lectores.

```
IA-2026-1_mis-conversaciones_2026-03-13.kitoru
├── manifest.json      # metadatos, versión, integridad, firma
├── threads.json       # List<ChatThread>
├── messages.json      # List<ChatMessage>
├── groups.json        # List<ThreadGroup>          (opcional)
└── attachments/       # archivos del modelo          (opcional)
    └── <threadId>/<nombre-del-archivo>
```

**Orden de las entradas en el ZIP**: `manifest.json` primero, para que un lector
pueda mostrar la vista previa sin descomprimir todo. Las demás en orden fijo
(threads, messages, groups, attachments).

#### `manifest.json`

```json
{
  "formatVersion": 1,
  "contentType": "kitoru.chat-package",

  "app": "Kitoru 1.0",
  "generator": { "version": "1.0.0", "platform": "android" },

  "courseId": "c-ia26",
  "courseCode": "IA-2026-1",
  "courseName": "Inteligencia Artificial",

  "studentId": "u-7f3a",
  "studentName": "Ana Ruiz",

  "exportedAt": 1773012345000,
  "dateFrom": 1767225600000,
  "dateTo": 1773012345000,

  "scope": "ALL",
  "threadIds": null,

  "threads": 21,
  "messages": 412,

  "model": { "mode": "LOCAL", "name": "Phi-4-mini-instruct-Q4_K_M" },

  "visibilityAtExport": "METRICS_ONLY",

  "contents": {
    "threads": true,
    "messages": true,
    "groups": true,
    "toolCalls": true,
    "citations": true,
    "attachments": false
  },

  "sha256": "9f2c…",
  "signature": {
    "algorithm": "ed25519",
    "publicKey": "MCowBQYDK2VwAyEA…",
    "signedDigest": "9f2c…",
    "signature": "…",
    "signedAt": 1773012345000
  }
}
```

| Campo | Tipo | Qué es | Por qué |
|---|---|---|---|
| `formatVersion` | `Int` | Versión del **formato** (no de la app). Hoy **1**. | El importador del docente ya avisa: *"El paquete usa el formato v2 y esta versión de Kitoru entiende hasta la v1. Actualizá Kitoru."* |
| `contentType` | `String` | `"kitoru.chat-package"` | Distingue un paquete de un ZIP cualquiera (el importador dice "El archivo no es un paquete de Kitoru"). |
| `app` / `generator` | `String` / obj | Versión de la app que generó el archivo | Diagnóstico: "generado por Kitoru 1.0.0 en Android". |
| `courseId` / `courseCode` / `courseName` | `String` | La cátedra | El importador avisa si no coincide con la cátedra abierta. |
| `studentId` / `studentName` | `String` | Quién exportó | El importador permite asignar el paquete a una inscripción. |
| `exportedAt` | `Long` | Cuándo | Epoch ms. |
| `dateFrom` / `dateTo` | `Long` | Rango cubierto por los hilos incluidos | El importador lo muestra ("Rango de fechas 01/03/2026 — 13/03/2026"). |
| `scope` | `String` | `"ALL"` (todos los hilos de la cátedra) o `"SELECTED"` | Distingue "te comparto todo" de "te comparto estos 3". |
| `threadIds` | `List<String>?` | ids incluidos cuando `scope = "SELECTED"` | `null` = todos. |
| `threads` / `messages` | `Int` | Conteos | Vista previa sin abrir los archivos. |
| `model` | `{mode, name}` | Modelo usado | "Phi-4-mini-instruct (IA local)". `mode` ∈ {`LOCAL`, `REMOTE`}. |
| `visibilityAtExport` | `String` | `FULL` / `METRICS_ONLY` / `NONE` | **Qué.level tenía la inscripción del alumno al exportar.** Es la información que le falta al docente: si es `NONE` y el alumno exportó igual, es un compartir explícito. |
| `contents` | obj | Qué incluye el paquete | El importador puede avisar "este paquete no incluye los adjuntos". |
| `sha256` | `String` | Digest del contenido (§5.3) | Integridad: detecta corrupción y manipulación trivial. |
| `signature` | obj? | Firma opcional (§5.4) | Integridad con authorship verificable contra una clave conocida. |

#### `threads.json` / `messages.json` / `groups.json`

- `threads.json` = `List<ChatThread>` con el **mismo serializador del dominio**
  (`ChatThread.serializer()`): el paquete lo genera el chat del alumno y lo lee la
  app del docente **sin conversiones**.
- `messages.json` = `List<ChatMessage>` con `ChatMessage.serializer()`.
- `groups.json` = `List<ThreadGroup>`. Es una **extensión del formato 1**: un
  lector de la v1 que no lo conozca simplemente lo ignora (el importador ignora los
  archivos que no conoce). Por eso **`formatVersion` no sube**.

### 5.3 Integridad: `sha256` (siempre) y la firma (opcional)

#### Digest de contenido

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/PackageDigest.kt
package kitoru.domain

/**
 * Digest del contenido de un paquete `.kitoru`.
 *
 * Regla (estable, versionada con `formatVersion`):
 *   1. Se excluyen `manifest.json` y `signature.json` (el manifiesto lleva el
 *      digest; la firma firma el digest: incluirlos sería circular).
 *   2. Las entradas restantes se ordenan por nombre, en orden de bytes UTF-8.
 *   3. Para cada entrada se hashean: el nombre, un byte 0x00 y el contenido.
 *
 * Es determinista y no depende del orden en que el ZIP guardó las entradas
 * (que puede variar entre implementaciones).
 */
object PackageDigest {
    fun compute(entries: Map<String, ByteArray>): String
}
```

```
H = SHA-256()
para nombre en ordenar(nombres - {"manifest.json", "signature.json"}):
    H.update(nombre en UTF-8)
    H.update(0x00)
    H.update(contenido)
sha256 = hex(H.digest())
```

> **Coordinación con el docente.** `07-chats.md` §8.1 menciona un `"sha256"` en el
> manifiesto sin definir la regla. **La regla canónica es la de arriba** y debe
> quedar escrita también en el doc del docente (o, mejor, en el código de
> `PackageDigest`, que es el único que ambos lados importan). Si el docente
> calcula el digest de otra manera, los hashes no coincidirán y el importador
> reportará un falso positivo de manipulación.

#### Firma opcional (Ed25519)

Qué **sí** prueba:

1. Que el archivo **no fue modificado** después de firmarlo (integridad).
2. Que fue producido por **quien tiene la clave privada** (autenticidad de
   la *máquina*, no de la persona).

Qué **NO** prueba (y la app lo dice):

1. **No prueba que las conversaciones sean del alumno.** El modelo escribió la
   mitad; el alumno pudo pegar la transcripción de otro.
2. **No prueba que las respuestas sean correctas** ni que el alumno las haya
   leído.
3. **No es una identidad** si la clave pública no se registró previamente: un
   alumno puede generar una clave nueva y firmar cualquier cosa. Para que sea
   prueba de identidad, la clave pública tiene que haberse exchanged por un canal
   confiable (el docente la registra a mano una vez, o la verifica contra el
   Kitoru-Server).

> La UI del paquete muestra, sin rodeos:
> *"La firma demuestra que este archivo no fue alterado desde que lo exportaste.
> No demuestra que las conversaciones sean tuyas ni que las respuestas sean
> correctas."*

**Implementación:**

- Algoritmo: **Ed25519** (determinista, 64 bytes de firma, clave pública de 32 bytes).
- Librería: `net.i2p.crypto:eddsa:0.3.0` — Java puro, funciona en `jvmCommonMain`
  (que cubre **desktop y Android**, `minSdk = 24` sin desugaring).
- SHA-256: `java.security.MessageDigest` (disponible en `jvmCommonMain`).
- La clave privada vive en `config/keys/signing.key` (permisos 0600 en desktop;
  `filesDir` en Android). **Nunca sale del dispositivo.**
- La clave pública se muestra en "Privacidad y datos → Mi clave de firma" para que
  el alumno la comparta con su docente (o la registre en el Kitoru-Server).

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/PackageSignature.kt
@Serializable
data class PackageSignature(
    val algorithm: String = "ed25519",
    /** Clave pública en base64 (SPKI DER). */
    val publicKey: String = "",
    /** Digest firmado (mismo valor que `manifest.sha256`). */
    val signedDigest: String = "",
    /** Firma en base64. */
    val signature: String = "",
    val signedAt: Long = 0L,
)
```

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/platform/Signing.jvm.kt
package kitoru.platform

import net.i2p.crypto.eddsa.EdDSAKeyPairGenerator
import net.i2p.crypto.eddsa.EdDSAPublicKey
import java.security.MessageDigest

/** Firma y digest en la JVM (desktop y Android comparten este `actual`). */
object JvmSigning {
    fun sha256Hex(bytes: ByteArray): String
    fun keyPair(): Pair<ByteArray, ByteArray>   // (privada, pública) en base64
    fun sign(privateKey: ByteArray, payload: ByteArray): ByteArray
    fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean
}
```

> Si la librería `eddsa` no se quiere sumar al build, el plan B honesto es
> **sin firma**: el `sha256` sigue detectando corrupción y modificación accidental,
> pero no la manipulación deliberada. La app no dice "firmado" si no hay firma.

### 5.4 Política de versiones

| Regla | Detalle |
|---|---|
| `formatVersion` es un **entero** | Hoy `1`. |
| **Cambio incompatible** (se borra o renombra un campo obligatorio, o cambia el significado de uno) | `formatVersion += 1`. El importador rechaza `formatVersion > MAX_SUPPORTED` con *"El paquete usa el formato v2 y esta versión de Kitoru entiende hasta la v1. Actualizá Kitoru."* |
| **Cambio aditivo** (campo opcional nuevo, archivo opcional nuevo) | **No** sube la versión. Los lectores ignoran lo que no conocen (`KitoruJson` ya tiene `ignoreUnknownKeys = true`) y los archivos desconocidos se descartan. |
| **Regla de oro** | El que **exporta** escribe siempre la versión actual; el que **importa** acepta `1..MAX_SUPPORTED` y rechaza el resto. El exportador nunca escribe una versión futura. |
| Evolución prevista | `2`: `attachments` en streaming, `messages` como JSONL (paquetes grandes), `groups` con color. |
| `MAX_SUPPORTED_PACKAGE_VERSION` | Constante en `:core` (`PACKAGE_FORMAT_VERSION = 1`). |

### 5.5 Cómo lo envía el alumno

#### Android

1. La app arma el ZIP en `cacheDir/exports/<nombre>.kitoru`.
2. Lo publica con un `FileProvider`:
   ```xml
   <!-- android/src/main/AndroidManifest.xml -->
   <provider
       android:name="androidx.core.content.FileProvider"
       android:authorities="${applicationId}.fileprovider"
       android:exported="false"
       android:grantUriPermissions="true">
       <meta-data
           android:name="android.support.FILE_PROVIDER_PATHS"
           android:resource="@xml/file_paths" />
   </provider>
   ```
   ```xml
   <!-- android/src/main/res/xml/file_paths.xml -->
   <paths>
       <cache-path name="exports" path="exports/" />
   </paths>
   ```
3. Comparte con el `ACTION_SEND`:
   ```kotlin
   // core/src/androidMain/kotlin/kitoru/platform/Share.android.kt
   val intent = Intent(Intent.ACTION_SEND).apply {
       type = "application/zip"                 // portable: cualquier app lo abre
       putExtra(Intent.EXTRA_STREAM, uri)
       putExtra(Intent.EXTRA_SUBJECT, "Conversaciones de IA-2026-1")
       addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
   }
   context.startActivity(Intent.createChooser(intent, "Compartir conversaciones"))
   ```
   - **MIME `application/zip`**: los MIME personalizados (`application/x-kitoru`)
     no los entienden las apps de correo; el nombre `.kitoru` conserva la identidad.
   - `FileProvider` requiere `androidx.core:core-ktx` (se agrega a `:android`).

#### Desktop

No hay hoja de compartir del sistema. El degradado honesto:

```
┌─ Paquete listo ────────────────────────────────┐
│  IA-2026-1_mis-conversaciones_2026-03-13.kitoru│
│  412 KB · 21 conversaciones · 412 mensajes      │
│                                                  │
│  [ Guardar como… ]   ya guardado en:            │
│                      ~/Descargas/IA-2026-1_…kitoru│
│                                                  │
│  [ Abrir carpeta ]  [ Copiar la ruta ]          │
│                                                  │
│  Para enviárselo a tu docente, copiá este texto: │
│  ┌───────────────────────────────────────────┐  │
│  │ Te paso mis conversaciones de la cátedra   │  │
│  │ IA-2026-1 (Kitoru, 21 conversaciones):     │  │
│  │ /home/ana/Descargas/IA-2026-1_mis-…kitoru │  │
│  └───────────────────────────────────────────┘  │
│  [ Copiar ]                                      │
└──────────────────────────────────────────────────┘
```

- "Abrir carpeta" → `java.awt.Desktop.getDesktop().open(dir)`.
- No se ofrece `mailto:` con adjunto: no es portable ni confiable.

### 5.6 La pantalla de consentimiento (lo que el alumno ve antes de compartir)

Es la pieza más importante del flujo: el alumno tiene que saber **exactamente** qué
va a ver el docente.

```
┌─ Compartir conversaciones con tu docente ───────┐
│  Cátedra: IA-2026-1 · Inteligencia Artificial     │
│                                                  │
│  Qué vas a compartir                              │
│  ┌────────────────────────────────────────────┐  │
│  │ 21 conversaciones · 412 mensajes           │  │
│  │ del 01/03/2026 al 13/03/2026               │  │
│  │ modelo: Phi-4-mini-instruct (IA local)     │  │
│  └────────────────────────────────────────────┘  │
│                                                  │
│  Qué va a poder ver el docente                   │
│  ✔ El texto de tus preguntas                     │
│  ✔ El texto de las respuestas del modelo         │
│  ✔ Los títulos de las conversaciones  ← ⚠        │
│  ✔ Las etiquetas y los grupos que pusiste ← ⚠    │
│  ✔ Qué fuentes se citaron y si el respaldo       │
│  ✔ Qué herramientas usó el modelo                │
│  ✘ Tu clave de API, tu clave de firma, tus       │
│    otros chats de otras cátedras                  │
│                                                  │
│  ⚠ Tu visibilidad actual es "Solo métricas":      │
│    con "Solo métricas" el docente NO ve el texto.│
│    Al compartir este paquete SÍ lo va a ver.     │
│    [ Cambiar mi visibilidad ]                     │
│                                                  │
│  Qué incluir                                     │
│  [✔] Conversaciones y mensajes                   │
│  [✔] Títulos, etiquetas y grupos                 │
│  [✔] Verificación de citas                       │
│  [ ] Archivos adjuntos (0 archivos, 0 KB)        │
│                                                  │
│  Firma opcional                                  │
│  [ ] Firmar el paquete con mi clave              │
│      La firma demuestra que el archivo no cambió │
│      desde que lo exportaste. No demuestra que   │
│      las conversaciones sean tuyas.               │
│                                                  │
│  [ Cancelar ]              [ Compartir ]         │
└──────────────────────────────────────────────────┘
```

- La lista de "qué va a poder ver el docente" es **literal y normativa**: los mismos
  textos que usa el `VisibilityDialog` del docente (`08-alumnos.md` §6.3), para que
  los dos lados lean lo mismo. Se propone un **catálogo compartido** en `:core`:
  `VisibilityCatalog.whatTeacherSees(visibility): List<String>`, usado por el
  diálogo del docente, por el panel "Qué ve tu docente" del alumno y por esta
  pantalla. (Ver §11, coordinación.)
- Si se exporta **un solo hilo**, la lista se acota: "1 conversación · 14 mensajes".
- El interruptor de adjuntos está **deshabilitado** si no hay adjuntos, con el
  motivo "Este hilo no tiene archivos adjuntos."

### 5.7 Exportar a Markdown (qué produce)

```markdown
# Duda sobre SVM con margen

- **Cátedra:** IA-2026-1 · Inteligencia Artificial
- **Alumno:** Ana Ruiz
- **Modelo:** Phi-4-mini-instruct (IA local)
- **Inicio:** 12/03/2026 18:24 — **Última actividad:** 12/03/2026 18:31
- **Mensajes:** 14 (7 preguntas · 7 respuestas)
- **Citas:** 5 verificadas · 1 sin respaldo · 0 sin fuente
- **Grupo:** Unidad 3 — **Etiquetas:** svm, parcial-1
- **Exportado:** 13/03/2026 09:12 por Ana Ruiz (alumna)

---

### Ana Ruiz — 12/03 18:24

¿Me explicás SVM con margen?

### IA — 12/03 18:24

El margen es la distancia entre la frontera de decisión y el punto más cercano
de cada clase. [1]

*Fuentes: [1] Apuntes-01.pdf — SVM*
*Citas: 1 verificada · 2.1 s · 412 tokens*

---

> Exportado por Ana Ruiz el 13/03/2026 09:12 con Kitoru.
> Contiene conversaciones con IA: parte de este texto fue generado por un modelo.
```

- El bloque de provenance va **al final**, como en el export del docente
  (`07-chats.md` §7.2), y la advertencia "parte de este texto fue generado por un
  modelo" es **obligatoria**: un Markdown suelto puede terminar en un PDF de un
  parcial y es justo que diga de dónde salió.

### 5.8 Registro local de exportaciones

Cada exportación deja una traza **local del alumno**:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ExportHistory.kt
@Serializable
data class ExportRecord(
    val id: String,
    val courseId: String,
    val courseCode: String = "",
    /** "kitoru" | "markdown" | "pdf" | "json". */
    val format: String,
    /** Threads included (count) and messages included (count). */
    val threadCount: Int = 0,
    val messageCount: Int = 0,
    /** File name (not the full path: the path may change). */
    val fileName: String = "",
    val exportedAt: Long = 0L,
    /** Whether the package was signed. */
    val signed: Boolean = false,
    /** Where it was sent: "android_share" | "saved" | "desktop_folder". */
    val channel: String = "",
)

interface ExportHistoryRepository {
    suspend fun listForCourse(courseId: String): List<ExportRecord>
    suspend fun list(): List<ExportRecord>
    suspend fun save(record: ExportRecord)
}
```

- Vive en `config/export_history.json` (tope: 200 registros).
- Alimenta el panel "Lo que compartiste" (§8.6).
- **No** se manda a la cátedra: es un registro del alumno sobre sus propias
  acciones. Cuando exista el Kitoru-Server, se podrá sincronizar.

---

## 6. Búsqueda en el historial

### 6.1 Modelo de filtro

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/HistoryFilter.kt
package kitoru.domain

/** Alcance de la búsqueda. */
enum class HistoryScope(val label: String) {
    COURSE("Esta cátedra"),
    ALL("Todas mis cátedras"),
    THREAD("Esta conversación"),
}

/**
 * Filtros del historial del alumno.
 *
 * Los tres ejes que el encargo pide (texto, fecha, material) más los que la
 * estructura aporta (grupo, etiqueta, sin respaldo).
 */
data class HistoryFilter(
    val query: String = "",
    val scope: HistoryScope = HistoryScope.COURSE,
    /** Desde (epoch ms), inclusive. */
    val dateFrom: Long? = null,
    /** Hasta (epoch ms), inclusive. */
    val dateTo: Long? = null,
    /** Material citado (id de `Material`). */
    val materialId: String? = null,
    /** Grupo del alumno. */
    val groupId: String? = null,
    /** Etiqueta (normalizada: sin acentos, minúsculas). */
    val tag: String? = null,
    /** Sólo hilos con al menos una cita sin respaldo o inexistente. */
    val onlyUngrounded: Boolean = false,
    /** Sólo hilos fijados. */
    val pinnedOnly: Boolean = false,
) {
    val isEmpty: Boolean
        get() = query.isBlank() && dateFrom == null && dateTo == null &&
            materialId == null && groupId == null && tag == null &&
            !onlyUngrounded && !pinnedOnly
}
```

### 6.2 Texto: BM25 sobre los mensajes propios

Se reusa **el mismo motor que el lado docente** (`rag.retrieval.Bm25Index`, ya
portado y testeado en `:backend`):

```kotlin
// backend/src/commonMain/kotlin/rag/history/HistorySearchEngine.kt
package rag.history

import rag.retrieval.Bm25Index
import rag.retrieval.Chunk

/**
 * Búsqueda de texto en el historial del alumno.
 *
 * Reutiliza el BM25 de `:backend` (regla 1 del CONTEXTO). El índice es **en
 * memoria** y se construye *lazy*: no hay índice persistido (escribir el texto
 * de los alumnos en un archivo aparte sería, además, un problema de privacidad).
 */
class HistorySearchEngine(messages: List<SearchableMessage>) {

    /** Un mensaje indexable. */
    data class SearchableMessage(
        val messageId: String,
        val threadId: String,
        val courseId: String,
        val role: String,
        val text: String,
        val createdAt: Long,
    )

    private val bm25 = Bm25Index(
        messages.map {
            Chunk(
                source = it.courseId,
                path = it.threadId,
                heading = it.role,
                text = it.text,
            )
        }
    )

    /** Devuelve los mensajes que matchean, ordenados por puntaje. */
    fun search(query: String, k: Int = 200): List<Hit>

    data class Hit(
        val messageId: String,
        val threadId: String,
        val courseId: String,
        val role: String,
        val snippet: String,
        val score: Double,
        val createdAt: Long,
    )
}
```

- Un `Chunk` por mensaje; `source = courseId`, `path = threadId`, `heading = role`.
- El índice se construye **la primera vez** que hay una consulta no vacía, en
  `Dispatchers.Default`, y se cachea en el `ViewModel` con un `dataVersion` que
  se invalida cuando cambian hilos o mensajes.
- El `snippet` es una ventana de **±120 caracteres** alrededor de la primera
  coincidencia, con los términos resaltados (`IntRange` para el resaltado, como
  el `highlight` de `ReadOnlyMessageBubble` del lado docente).
- Máximo **200 resultados**.
- La **consulta no se persiste**: vive en el estado del `ViewModel`. (El docente
  guarda sus últimas 10 consultas; el alumno no: son suyas y no hacen falta fuera
  de la sesión.)

> **Coordinación.** `07-chats.md` §5.2 propone un `ChatSearchIndex` en `:ui` que
> envuelve a `Bm25Index`. **Debe delegar en `rag.history.HistorySearchEngine`** para
> que los dos lados compartan tokenización, normalización y snippet. Si no, una
> búsqueda del alumno y una del docente pueden dar resultados distintos sobre el
> mismo texto — que es exactamente la clase de bug que erosiona la confianza.

### 6.3 Fecha

- Presets: **Hoy**, **Últimos 7 días**, **Últimos 30 días**, **Todo el curso**, y un
  rango personalizado (dos selectores de fecha).
- El filtro de fecha se aplica a `ChatMessage.createdAt` (no a `thread.createdAt`):
  un hilo abierto en marzo y usado en octubre debe aparecer en "Octubre".
- A nivel de hilo, sobreviven los que tienen **algún** mensaje en el rango; el
  visor muestra "3 de 14 mensajes fuera del rango" con un botón "verlos igual".

### 6.4 Material citado

- `ChatMessage.sources` guarda las **etiquetas** de las fuentes recuperadas
  (`"corpus: ruta"`). Se matchean contra `Material.path` normalizando separadores.
- El desplegable lista los materiales de la cátedra **con el contador de citas**
  ("Apuntes-01.pdf — 12"), más una entrada **"materiales externos"** para las
  etiquetas que no corresponden a ningún `Material` (fuera del corpus de la cátedra).
- El filtro es de granularidad fina: filtra **mensajes**, no hilos.

### 6.5 Interacción

```
┌─ Buscar en el historial ────────────────────────┐
│ [🔍 SVM margen                          ] [✕]  │
│ chips: [📅 Últimos 7 días] [📄 Apuntes-01.pdf]  │
│       [🏷 svm] [⚠ sin respaldo] [limpiar]       │
├──────────────────────────────────────────────────┤
│ 12/03 18:24 · Duda sobre SVM con margen         │
│ …la distancia entre la frontera de decisión y el │
│ punto más cercano de cada cla**se**. Support…    │
│                                        8 msj    │
└──────────────────────────────────────────────────┘
```

- Clic en un resultado → abre el hilo en ese mensaje y lo resalta 2 s.
- Si la consulta está activa, la lista **deja de agrupar por grupo** y muestra los
  resultados ordenados por puntaje (modo `SEARCH`).
- Atajo de teclado en desktop: `Ctrl/⌘ + F` enfoca el buscador; `Esc` lo limpia.

---

## 7. Persistencia y escala

### 7.1 El problema de hoy, con números

Hoy todo vive en dos JSON planos de `config/`:

| Archivo | Contenido | Problema |
|---|---|---|
| `config/threads.json` | `List<ChatThread>` de **todas** las cátedras | Pequeño: 40 hilos ≈ 10 KB. Aceptable. |
| `config/messages.json` | `List<ChatMessage>` de **todas** las cátedras y **todos** los alumnos de la máquina | **Catastrófico**: se reescribe entero en cada `save()`. |

`FileBackedRepository.put()` hace: `store.update { list -> list.filterNot { … } + item }`
→ **lee el JSON entero, lo deserializa, filtra, serializa todo y lo escribe**.

Números para un alumno de un semestre (5 cátedras × 40 hilos × 15 mensajes):

| Magnitud | Valor |
|---|---|
| Mensajes | 3 000 |
| Tamaño de `messages.json` (texto ≈ 600 B + metadatos ≈ 300 B) | **≈ 2,7 MB** |
| I/O por cada mensaje guardado | leer 2,7 MB + parsear 3 000 objetos + escribir 2,7 MB |
| I/O de una sesión de 20 mensajes | **≈ 108 MB escritos + 108 MB leídos**, 60 000 objetos allocating |
| Listar los hilos del sidebar | leer 2,7 MB (o filtrar en memoria) |
| Abrir un hilo | releer 2,7 MB |

En el escenario del docente (200 alumnos × 3 000 mensajes) el archivo sería de
**≈ 540 MB** y cada escritura del docente lo reescribiría entero. Inusable.

### 7.2 El esquema final

```
<dataDir>/
├── config/                          # ajustes, cursos, inscripciones, perfiles, auditoría
│   ├── courses.json
│   ├── enrollments.json
│   ├── materials.json
│   ├── profiles.json
│   ├── app-settings.json
│   ├── export_history.json          # traza de exportaciones del alumno (§5.8)
│   └── access-log.json              # (lado docente)
└── courses/<courseId>//
    ├── index.json                   # ÍNDICE de hilos (metadatos) → el sidebar
    ├── groups.json                  # grupos del alumno en esta cátedra
    └── threads/<threadId>/
        ├── meta.json                # ChatThread (metadata del hilo)
        └── messages.jsonl           # append-only: 1 ChatMessage por línea
```

**Por qué esta forma y no otras:**

| Alternativa | Por qué no |
|---|---|
| **Un solo `messages.json` por cátedra** | Sigue reescribiendo 300 KB por mensaje. Mejor que hoy, pero no resuelve el problema. |
| **Un `.json` por hilo** (`threads/<id>.json` con todo) | Funciona, pero cada mensaje reescribe el archivo del hilo completo (14 KB). Aceptable, pero se pierde el append de O(1). |
| **SQLite ya desde el inicio** | La dependencia (fork multiplataforma de `androidx.sqlite`) y la complejidad de migraciones no se justifican en el milestone 1, y `FileSystem` + JSONL ya dan el 90 % del beneficio. Se deja como paso siguiente (§7.6). |
| **Un directorio por cátedra con JSONL por hilo** ✅ | Escritura O(1), borrado de un directorio, lectura acotada al hilo, y **la misma forma de pensar** que ya usa el repo (`KitoruPaths.coursesDir`). |

**El índice (`index.json`) es la clave del rendimiento.** Es lo único que se lee
para pintar la lista; los mensajes se leen **sólo** al abrir un hilo.

```json
{
  "version": 1,
  "courseId": "c-ia26",
  "updatedAt": 1773012345000,
  "threads": [
    {
      "id": "c-ia26|hilo|9f2c…",
      "title": "Duda sobre SVM con margen",
      "groupId": "c-ia26|grupo|7d3e…",
      "tags": ["svm", "parcial-1"],
      "pinned": true,
      "autoTitle": true,
      "createdAt": 1772742000000,
      "updatedAt": 1772743860000,
      "messageCount": 14,
      "byteSize": 12680,
      "hasUnsupportedCitations": true,
      "modelLabel": "Phi-4-mini-instruct"
    }
  ]
}
```

- Una entrada por hilo ≈ **250 B** → 40 hilos ≈ **10 KB**.
- Se reescribe con **debounce de 2 s** (o al pausar/cerrar la app): escribir un
  mensaje no toca el índice al instante.
- Escritura **atómica**: se escribe `index.json.tmp` y se renombra (`move`).

### 7.3 Costos concretos (nuevo vs. hoy)

| Operación | Hoy | Nuevo | Mejora |
|---|---|---|---|
| **Guardar un mensaje** | reescribir 2,7 MB (read + parse + write) | anexar ~900 B a `messages.jsonl` | **≈ 3000× menos E/S** |
| **Listar los hilos del sidebar** | leer 2,7 MB | leer 10 KB de `index.json` | **≈ 270× menos** |
| **Abrir un hilo** | releer 2,7 MB | leer 0,6 KB + 14 KB | **≈ 180× menos** |
| **Renombrar un hilo** | reescribir `threads.json` (10 KB) | reescribir `meta.json` (0,6 KB) | ≈ 17× menos |
| **Eliminar un hilo** | reescribir 2,7 MB | borrar un directorio | instantáneo |
| **Espacio total en disco** (5 cátedras × 40 hilos × 15 msj) | ≈ 2,7 MB + 50 KB | ≈ 3,0 MB + 50 KB | ~igual |
| **Archivos** | 7 | 5 + (5 × 40 × 2) = **405** | +400 inodos (aceptable: ext4/NTFS, y `listFiles` por directorio es O(1) por hilo) |

El último renglón es el precio: **muchos archivos pequeños**. Es el mismo canje
que hace Git (muchos archivos, escrituras chicas) y que evita el archivo gigante.
En Android (`filesDir` sobre ext4/f2fs) no hay problema; el límite real de
inodos (100 000) se alcanzaría con 50 000 hilos: no es un escenario de un semestre
humano, pero el doc lo registra.

### 7.4 Los repositorios (firmas nuevas)

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Repositories.kt (modificado)

interface ThreadRepository {
    suspend fun listForCourse(courseId: String): List<ChatThread>
    suspend fun listForUser(courseId: String, userId: String): List<ChatThread>
    suspend fun get(id: String): ChatThread?
    suspend fun save(thread: ChatThread)
    suspend fun delete(id: String)

    // ---- nuevo ----
    /** Mueve el hilo a un grupo ("" = sin grupo). */
    suspend fun setGroup(threadId: String, groupId: String)
    /** Fija / desfija. */
    suspend fun setPinned(threadId: String, pinned: Boolean)
}

interface MessageRepository {
    suspend fun listForThread(threadId: String): List<ChatMessage>
    suspend fun listForCourse(courseId: String): List<ChatMessage>

    // ---- nuevo: append O(1) ----
    /** Agrega el mensaje al final del hilo. No reescribe el archivo. */
    suspend fun append(message: ChatMessage)

    /**
     * Reescribe el hilo completo. Se usa cuando el alumno **edita o borra** un
     * mensaje (operación rara) o cuando se importa un paquete.
     */
    suspend fun replaceThread(threadId: String, messages: List<ChatMessage>)

    suspend fun delete(threadId: String)
}

interface ThreadGroupRepository {
    suspend fun listForCourse(courseId: String): List<ThreadGroup>
    suspend fun get(id: String): ThreadGroup?
    suspend fun save(group: ThreadGroup)
    suspend fun delete(id: String)          // los hilos quedan sin grupo
    suspend fun rename(id: String, name: String)
}
```

**Durabilidad de `messages.jsonl`:**

- Se escribe **una línea completa** (`ChatMessage` serializado + `\n`) con una
  única llamada `appendBytes`. Una caída a mitad de escritura deja una última
  línea **incompleta**: el lector la **descarta** (si el archivo no termina en
  `\n`, la última línea no se parsea). Se pierde como máximo **el último mensaje**,
  que es el que se estaba escribiendo. Es el compromiso clásico de JSONL y es
  aceptable para un historial.
- `meta.json` e `index.json` se escriben con **tmp + rename** (atómico en POSIX y
  NTFS).

**Falta en `kitoru.platform.FileSystem`:** `readBytes`, `writeBytes`,
`appendBytes` y `move`. Las cuatro son triviales en `java.io.File` y portables a
Android.

### 7.5 Migración desde el esquema actual

Se ejecuta **una vez**, al primer arranque de la versión nueva:

1. Si `config/messages.json` **no** existe → no hay nada que hacer.
2. Si `courses/<id>/threads/` ya tiene hilos → la migración ya corrió → no hacer
   nada (idempotencia).
3. Crear `courses/<id>/.migrating/`, escribir ahí la estructura nueva.
4. Renombrar `.migrating/` → `threads/`.
5. Renombrar `config/messages.json` → `config/messages.json.v1.bak` y
   `config/threads.json` → `config/threads.json.v1.bak` (**se conservan**: son el
   respaldo de los datos del usuario; la app ofrece borrarlos desde Ajustes →
   "Limpiar datos antiguos").
6. Escribir `config/migration.json` con `{"schemaVersion": 2, "migratedAt": …}`.

- **Coste**: 3 000 mensajes ≈ 2,7 MB escritos una vez, en ~3 000 anexos: **< 1 s**.
- **Crash-safe**: si se corta en el paso 4, el paso 5 no corrió → la próxima
  ejecución ve que `threads/` no existe y reintenta desde cero (los `.bak` se
  regeneran).
- La migración **no** cambia los datos: los `ChatThread` y `ChatMessage` se
  copian tal cual; los campos nuevos (`groupId = ""`, `autoTitle = true`,
  `pinned = false`) toman sus valores por defecto.
- Los hilos antiguos whose título sea `"Conversación"` (el valor por defecto) se
  marcan `autoTitle = true` para que el próximo guardado los derive. Los que ya
  tienen un título real se marcan `autoTitle = false`: **no se pisa el trabajo del
  usuario**.

### 7.6 SQLite: el siguiente paso, con el criterio de corte

**No en el milestone 1.** Pero el diseño deja la puerta abierta:

- Los repositorios (`ThreadRepository`, `MessageRepository`) son la **frontera**:
  cambiar el backend de archivos por SQLite **no cambia la UI**.
- **Criterio de corte** (se cumple cualquiera):
  1. más de **50 000 mensajes** o **50 MB** en el store local;
  2. aparece el **Kitoru-Server** (el store local pasa a ser caché y hay que
     sincronizar);
  3. se necesita **búsqueda de texto persistida** (FTS5) en lugar del índice en
     memoria.
- **Librería**: `org.jetbrains.androidx.sqlite:sqlite-bundled` (el fork
  multiplataforma de `androidx.sqlite:sqlite-bundled`), que compila el SQLite de
  Android para **desktop y Android** con el mismo SQL.
- **Esquema** previsto:
  ```sql
  CREATE TABLE threads (
    id TEXT PRIMARY KEY, course_id TEXT NOT NULL, author_id TEXT NOT NULL,
    title TEXT, group_id TEXT, tags TEXT, pinned INTEGER, auto_title INTEGER,
    created_at INTEGER, updated_at INTEGER, meta TEXT
  );
  CREATE TABLE messages (
    thread_id TEXT NOT NULL, id TEXT NOT NULL, role TEXT, text TEXT,
    created_at INTEGER, sources TEXT, citation TEXT, tool_calls TEXT,
    PRIMARY KEY (thread_id, id)
  );
  CREATE INDEX idx_messages_thread ON messages(thread_id, created_at);
  CREATE VIRTUAL TABLE messages_fts USING fts5(text, content='messages', content_rowid='rowid');
  ```
- La búsqueda pasaría de BM25-en-memoria a FTS5 **sólo** si hace falta; hasta
  entonces, el motor actual cumple.

---

## 8. Lo que el alumno ve de sus propios datos

### 8.1 La pantalla

Se integra en **"Privacidad y datos"**, la pantalla que ya define
`08-alumnos.md` §6.5 y §11.2 (`ui/src/commonMain/kotlin/kitoru/ui/student/privacy/`).
Este doc le **agrega la pestaña "Mi actividad"**; la pestaña "Qué ve tu docente" ya
está diseñada por el doc del docente y no se duplica.

```
┌─ Privacidad y datos ─────────────── [ Mi actividad │ Qué ve tu docente ] ─┐
│                                                                            │
│  IA-2026-1 · Inteligencia Artificial                                       │
│  ────────────────────────────────────────────────────────────────────────  │
│  38 conversaciones   142 mensajes   5 días activos                          │
│  ⏱ Tiempo de uso estimado: ≈ 3 h 20 min                                   │
│                                                                            │
│  Citas de tus respuestas                                                   │
│  ████████████░░░░░░  31 verificadas · 4 sin respaldo · 0 sin fuente        │
│  89 % de tus citas están respaldadas por el material                        │
│                                                                            │
│  Materiales más consultados                                               │
│   1. Apuntes-01.pdf              12 citas                                   │
│   2. Parcial-2025.pdf              7 citas                                  │
│   3. TPs-resueltas.pdf             4 citas                                  │
│                                                                            │
│  ⚠ 4 respuestas con citas sin respaldo                    [ Verlas ]         │
│                                                                            │
│  ────────────────────────────────────────────────────────────────────────  │
│  Esto es exactamente lo que ve tu docente: los mismos números, calculados  │
│  por el mismo código.                                                      │
│  [ Ir a la cátedra ]                                                        │
└────────────────────────────────────────────────────────────────────────────┘
```

### 8.2 Los números son los del docente (no una aproximación)

La regla que hace que esta pantalla sirva de verdad:

> **El resumen del alumno se calcula con `AnalyticsAnalyzer.analyze(...)`, la misma
> función pura que usa el panel del docente, con la inscripción del propio alumno
> como `enrollments`.**

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/StudentActivity.kt
package kitoru.domain

/**
 * Resumen de la actividad del alumno en una cátedra.
 *
 * **No es un cálculo nuevo**: es el `StudentAnalytics` que produce
 * `AnalyticsAnalyzer.analyze(...)`, que es el mismo objeto que el docente ve.
 * La diferencia es de *perspectiva*, no de *fórmula*: si mañana cambia la
 * definición de "cita respaldada", cambian los dos lados a la vez.
 */
data class StudentActivity(
    val courseId: String,
    val analytics: StudentAnalytics,
    /** Tiempo de uso estimado (no "horas de estudio": es tiempo con el modelo). */
    val estimatedUsageMillis: Long = 0L,
    /** Materiales más consultados, con el conteo de citas. */
    val topSources: List<SourceUsage> = emptyList(),
    /** Respuestas con al menos una cita sin respaldo o inexistente. */
    val ungroundedMessageIds: List<String> = emptyList(),
)

object StudentActivityAnalyzer {
    /**
     * Resumen del alumno. Envuelve `AnalyticsAnalyzer` (no lo reimplementa).
     */
    fun analyze(
        course: Course,
        enrollment: Enrollment,
        threads: List<ChatThread>,
        messages: List<ChatMessage>,
    ): StudentActivity
}
```

- `StudentAnalytics` (ya existe en `Analytics.kt`) aporta: mensajes, respuestas,
  hilos, días activos, latencia media, tokens, citas verificadas/sin respaldo/sin
  fuente, respuestas sin respaldo, herramientas.
- Se le **agrega** un campo: `StudentAnalytics.estimatedUsageMillis: Long = 0L`,
  para que el docente también vea el tiempo estimado (coherencia: los dos lados
  ven lo mismo). **Coordinación requerida** con el doc de analítica y con
  `08-alumnos.md` (que lista los campos del resumen del docente).

### 8.3 El tiempo estimado (algoritmo)

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/StudyTime.kt
package kitoru.domain

/**
 * Estimación del tiempo de uso del chat.
 *
 * **No es "horas de estudio"**: es el tiempo transcurrido entre el primer y el
 * último mensaje de cada sesión de trabajo, con topes para no contar una
 * conversación que quedó abierta toda la noche.
 */
object StudyTimeEstimator {

    /** Dos mensajes con más de este intervalo son sesiones distintas. */
    const val SESSION_GAP_MS = 15 * 60 * 1000L

    /** Tope por sesión: una conversación abierta 8 h no suma 8 h. */
    const val MAX_SESSION_MS = 45 * 60 * 1000L

    /**
     * @return milisegundos estimados de uso.
     */
    fun estimate(messages: List<ChatMessage>): Long {
        if (messages.isEmpty()) return 0L
        val times = messages.map { it.createdAt }.sorted()
        var total = 0L
        var start = times.first()
        var last = times.first()
        for (t in times.drop(1)) {
            if (t - last > SESSION_GAP_MS) {
                total += minOf(last - start, MAX_SESSION_MS)
                start = t
            }
            last = t
        }
        total += minOf(last - start, MAX_SESSION_MS)
        return total
    }
}
```

Características (y lo que la UI debe decir):

- Una sesión de **un solo mensaje** cuenta **0** (preguntó y se fue).
- El tiempo se **acota** por sesión (45 min): un chat abierto mientras el alumno
  almuerza no suma una hora.
- La etiqueta es **"Tiempo de uso estimado"**, nunca "horas de estudio" ni "tiempo
  de cursada": es una estimación de presencia, y decirlo de otra forma sería
  mentir.

### 8.4 La lista de respuestas sin respaldo

Es la parte que más valor pedagógico tiene y la que más vería el alumno:

```
┌─ Respuestas con citas sin respaldo (4) ─────────┐
│                                                  │
│  12/03 · Duda sobre SVM con margen               │
│  "El margen se define con la norma euclídea [2]"  │
│  ⚠ La cita menciona "euclídea", que no aparece   │
│    en la fuente.                                  │
│  [ Ver la respuesta ]  [ Ver la fuente ]          │
│                                                  │
│  09/03 · Teoría de SVM                           │
│  …                                               │
└──────────────────────────────────────────────────┘
```

- El "motivo" (`reason`) lo produce `CitationVerifier`, que es **determinista**
  (regla 3 del CONTEXTO): el mismo texto contra la misma fuente da el mismo
  veredicto, en el dispositivo del alumno y en el del docente.
- El alumno puede abrir la fuente citada y compararla con la respuesta.
- **Es la misma lista que el docente puede ver** (si su visibilidad es `FULL`).
  Verla antes que él es la diferencia entre "me evalúan" y "aprendo del error".

### 8.5 "Qué ve tu docente" (extensión)

La pantalla ya está diseñada en `08-alumnos.md` §6.5. Este doc le agrega:

1. **El estado efectivo**, no el pedido:
   ```
   Tu docente ve tus conversaciones en:  Solo métricas
   (lo pidió la cátedra: Completo · tu tope: Solo métricas — el mínimo manda)
   ```
   Con `Enrollment.effectiveVisibility` (`visibility.and(studentCap)`).
2. **El espejo de las métricas**: un botón "Ver exactamente lo que ve mi docente"
   que abre la misma tarjeta de §8.1 con el encabezado cambiado a "Así te ve tu
   docente".
3. **"Lo que compartiste"** (§8.6).
4. **El registro de accesos** — con una salvedad honesta:
   - El `AccessLog` vive **en la instalación del docente** en el milestone 1
     (`08-permisos.md` §8.4: "El log es local en el milestone 1"). El alumno
     **no puede verlo** desde su dispositivo en el milestone 1.
   - Cuando exista el Kitoru-Server, el registro se sincroniza y el alumno ve
     "12/03 15:04 · Prof. Titular abrió 3 de tus conversaciones".
   - En el milestone 1, el panel muestra la verdad:
     > El registro de accesos a tus conversaciones vive en la instalación de tu
     > docente. Cuando la cátedra use el servidor de Kitoru, vas a poder ver
     > acá quién consultó tus conversaciones y cuándo.

### 8.6 "Lo que compartiste"

```
┌─ Lo que compartiste ─────────────────────────────┐
│                                                  │
│  📦 IA-2026-1_mis-conversaciones_2026-03-13.kitoru│
│     21 conversaciones · 412 mensajes              │
│     13/03/2026 09:12 · firmado                   │
│     enviado por compartir (Android)              │
│                                                  │
│  📄 Duda-SVM_2026-03-11.md                        │
│     1 conversación · 14 mensajes                 │
│     11/03/2026 20:03 · guardado en Descargas    │
│                                                  │
│  ⚠ Compartir un archivo no es revocable: si ya lo │
│    enviaste, el docente conserva su copia. Podés  │
│    borrarlo de tu equipo.                          │
│  [ Borrar mis exportaciones registradas ]        │
└──────────────────────────────────────────────────┘
```

- Sale de `ExportRecord` (§5.8).
- "Borrar mis exportaciones registradas" **sólo borra el registro local**, no los
  archivos ya compartidos (y el texto lo dice).
- Es la pieza que hace que la acción de compartir sea **consciente**: el alumno ve
  un historial de lo que sacó de su máquina.

---

## 9. Nombres: archivos, composables, view-models

### 9.1 `:core` (KMP, `commonMain` salvo indicado)

| Archivo | Contenido |
|---|---|
| `core/src/commonMain/kotlin/kitoru/domain/Chat.kt` | **Modificado**: `ChatThread.groupId`, `.autoTitle`, `.pinned`, `.duplicateOf`, `.origin`, `.originPackageId`; `ChatAttachment`; `ChatMessage.attachments`. |
| `core/src/commonMain/kotlin/kitoru/domain/ThreadGroup.kt` | **Nuevo**: `ThreadGroup`, `ThreadGroupRepository`. |
| `core/src/commonMain/kotlin/kitoru/domain/ThreadTitle.kt` | **Nuevo**: `ThreadTitle.derive`, `MAX_LENGTH`. |
| `core/src/commonMain/kotlin/kitoru/domain/ThreadDuplicator.kt` | **Nuevo**: `ThreadDuplicator.duplicate(...)`, `.branch(...)` (puros; devuelven `ChatThread` + `List<ChatMessage>` con ids regenerados). |
| `core/src/commonMain/kotlin/kitoru/domain/HistoryFilter.kt` | **Nuevo**: `HistoryFilter`, `HistoryScope`. |
| `core/src/commonMain/kotlin/kitoru/domain/StudyTime.kt` | **Nuevo**: `StudyTimeEstimator`. |
| `core/src/commonMain/kotlin/kitoru/domain/StudentActivity.kt` | **Nuevo**: `StudentActivity`, `StudentActivityAnalyzer`. |
| `core/src/commonMain/kotlin/kitoru/domain/Package.kt` | **Nuevo**: `ChatPackageManifest`, `PackageScope`, `PackageContents`, `PackageSignature`, `PACKAGE_FORMAT_VERSION = 1`. **Fuente única del manifiesto** (el doc del docente la importa desde acá, no la redefine). |
| `core/src/commonMain/kotlin/kitoru/domain/PackageBuilder.kt` | **Nuevo**: `ChatPackageBuilder.build(course, student, threads, messages, groups, options): Map<String, ByteArray>` — puro: devuelve las entradas del ZIP. |
| `core/src/commonMain/kotlin/kitoru/domain/PackageDigest.kt` | **Nuevo**: `PackageDigest.compute(entries): String`. |
| `core/src/commonMain/kotlin/kitoru/domain/Export.kt` | **Nuevo (compartido con el docente)**: `ExportFormat`, `ExportHeader`, `ChatExporter.markdown(...)`, `.json(...)`. |
| `core/src/commonMain/kotlin/kitoru/domain/ExportHistory.kt` | **Nuevo**: `ExportRecord`, `ExportHistoryRepository`. |
| `core/src/commonMain/kotlin/kitoru/domain/Analytics.kt` | **Modificado**: `StudentAnalytics.estimatedUsageMillis`. |
| `core/src/commonMain/kotlin/kitoru/domain/AnalyticsAnalyzer.kt` | **Modificado**: usar `effectiveVisibility`; agregar el tiempo estimado. |
| `core/src/commonMain/kotlin/kitoru/domain/Repositories.kt` | **Modificado**: `ThreadRepository`, `MessageRepository` (nuevas firmas), `+ ThreadGroupRepository`, `+ ExportHistoryRepository`. |
| `core/src/commonMain/kotlin/kitoru/domain/FileRepositories.kt` | **Modificado**: `FileThreadRepository` (nuevo layout), `FileMessageRepository` (JSONL), `+ FileThreadGroupRepository`, `+ FileExportHistoryRepository`. |
| `core/src/commonMain/kotlin/kitoru/domain/FileSchemaMigrator.kt` | **Nuevo**: migración `messages.json` → layout por hilo (§7.5). |
| `core/src/commonMain/kotlin/kitoru/domain/VisibilityCatalog.kt` | **Nuevo**: `VisibilityCatalog.whatTeacherSees(visibility): List<String>` — **texto normativo compartido** con el diálogo del docente. |
| `core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt` | **Modificado**: `+ readBytes`, `+ writeBytes`, `+ appendBytes`, `+ move`. |
| `core/src/commonMain/kotlin/kitoru/platform/Zip.kt` | **Nuevo**: `expect fun zip(entries): ByteArray`, `expect fun unzip(bytes): Map<String, ByteArray>` (actual en `jvmCommonMain` con `java.util.zip`). |
| `core/src/commonMain/kotlin/kitoru/platform/Share.kt` | **Nuevo**: `expect interface ShareTarget { suspend fun shareFile(path: FilePath, mime: String, subject: String): Boolean }` + `expect fun shareTarget(): ShareTarget`. |
| `core/src/commonMain/kotlin/kitoru/platform/Signing.kt` | **Nuevo (commonMain)**: `expect object Signing { fun sha256Hex(bytes): String; fun keyPair(): KeyPair; fun sign(...): ByteArray; fun verify(...): Boolean }`. |
| `core/src/jvmCommonMain/kotlin/kitoru/platform/Signing.jvm.kt` | **Nuevo**: Ed25519 con `net.i2p.crypto:eddsa` + `MessageDigest`. |
| `core/src/jvmCommonMain/kotlin/kitoru/platform/Zip.jvm.kt` | **Nuevo**: `java.util.zip`. |
| `core/src/jvmCommonMain/kotlin/kitoru/platform/FileSystem.jvm.kt` | **Modificado**: los cuatro métodos nuevos con `java.io.File`. |

### 9.2 `:backend`

| Archivo | Contenido |
|---|---|
| `backend/src/commonMain/kotlin/rag/history/HistorySearchEngine.kt` | **Nuevo**: `HistorySearchEngine` (BM25 sobre los mensajes del alumno). **Fuente única**: el `ChatSearchIndex` del docente (§11) delega acá. |

### 9.3 `:ui` — `commonMain`

```
ui/src/commonMain/kotlin/kitoru/ui/student/history/
├── StudentHistoryScreen.kt        (StudentHistoryScreen, StudentHistoryRoute)
├── StudentHistoryViewModel.kt     (StudentHistoryViewModel, StudentHistoryUiState)
├── StudentHistoryUiState.kt       (ThreadListItem, GroupListItem, HistorySearchState,
│                                   ThreadListMode, threadListMode())
├── ThreadSidebar.kt               (ThreadSidebar: desktop/móvil, según el modo)
├── ThreadRow.kt                   (ThreadRow, ThreadTagsChips)
├── GroupSection.kt                (GroupSection, GroupHeader, drag & drop)
├── ThreadListBuilder.kt           (ThreadListBuilder.flatten: grupos/plano/búsqueda)
├── ThreadActionsMenu.kt           (ThreadActionsMenu: DropdownMenu de escritorio)
├── ThreadActionsSheet.kt          (ThreadActionsSheet: ModalBottomSheet de Android)
├── RenameThreadDialog.kt          (RenameThreadDialog)
├── ThreadTagsEditor.kt            (ThreadTagsEditor)
├── MoveToGroupSheet.kt            (MoveToGroupSheet, NewGroupDialog)
├── BranchThreadDialog.kt          (BranchThreadDialog)
├── ExportThreadDialog.kt          (ExportThreadDialog: md / pdf / json)
├── ExportPackageDialog.kt         (ExportPackageDialog: el .kitoru + consentimiento)
├── PackageConsentCard.kt          (PackageConsentCard: qué verá el docente)
├── ExportResultCard.kt            (ExportResultCard: dónde quedó, compartir)
├── HistorySearchBar.kt            (HistorySearchBar)
├── HistoryFilterSheet.kt          (HistoryFilterSheet: fecha, material, etiqueta)
├── HistorySearchResults.kt        (HistorySearchResults, HistoryHitRow)
├── HistoryEmptyState.kt           (HistoryEmptyState)
└── ThreadContextChecker.kt        (aviso "la configuración cambió" / "el material cambió")
```

```
ui/src/commonMain/kotlin/kitoru/ui/student/activity/
├── MyActivityScreen.kt            (MyActivityScreen, MyActivityRoute)
├── MyActivityViewModel.kt         (MyActivityViewModel, MyActivityUiState)
├── MyActivityCards.kt             (UsageCards, CitationRateCard, TopMaterialsCard)
├── UngroundedAnswersList.kt       (UngroundedAnswersList, UngroundedAnswerRow)
└── SharedPackagesPanel.kt         (SharedPackagesPanel: "lo que compartiste")
```

> `ui/src/commonMain/kotlin/kitoru/ui/student/privacy/` (`PrivacyScreen.kt`,
> `PrivacyViewModel.kt`, `RequestVisibilityDialog.kt`) ya está reservado por
> `08-alumnos.md` §11.2: este doc **no** lo duplica, le agrega la pestaña
> "Mi actividad" (que vive en `activity/` y se monta dentro de `PrivacyScreen`).

Componentes compartidos que se reusan del lado docente (mismos archivos, sin
copias): `ui/src/commonMain/kotlin/kitoru/ui/chat/CitationBadge.kt`,
`MessageMeta.kt`, `SourceList.kt`, `MarkdownText.kt`.

### 9.4 `:ui` — plataformas

| Archivo | Contenido |
|---|---|
| `ui/src/desktopMain/kotlin/kitoru/ui/student/history/ThreadSidebarDesktop.kt` | Columna con grupos + drag & drop + click derecho. |
| `ui/src/androidMain/kotlin/kitoru/ui/student/history/ThreadSidebarAndroid.kt` | Lista plana + hoja inferior de acciones. |
| `ui/src/desktopMain/kotlin/kitoru/ui/platform/ChatPdfExporter.desktop.kt` | PDF con `com.fleeksoft:kotlin-pdf` (sin AWT). |
| `ui/src/androidMain/kotlin/kitoru/ui/platform/ChatPdfExporter.android.kt` | PDF con el `PrintManager` del sistema ("Guardar como PDF"). |
| `ui/src/desktopMain/kotlin/kitoru/ui/platform/ShareTarget.desktop.kt` | "Abrir carpeta" + "copiar la ruta". |
| `ui/src/androidMain/kotlin/kitoru/ui/platform/ShareTarget.android.kt` | `ACTION_SEND` + `FileProvider`. |

### 9.5 El ViewModel

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/student/history/StudentHistoryViewModel.kt
class StudentHistoryViewModel(
    private val courseId: String,
    private val user: UserProfile,
    private val threads: ThreadRepository,
    private val messages: MessageRepository,
    private val groups: ThreadGroupRepository,
    private val courses: CourseRepository,
    private val enrollments: EnrollmentRepository,
    private val materials: MaterialRepository,
    private val search: HistorySearchEngine,          // de :backend
    private val exporter: ChatExportService,           // :core
    private val packager: ChatPackageBuilder,
    private val shareTarget: ShareTarget,
    private val fileDialogs: FileDialogs,
) {
    val state: StateFlow<StudentHistoryUiState>

    // --- navegación y organización ---
    fun selectThread(threadId: String)
    fun newThread(): String
    fun renameThread(threadId: String, title: String)
    fun restoreAutoTitle(threadId: String)
    fun setTags(threadId: String, tags: List<String>)
    fun setGroup(threadId: String, groupId: String)
    fun setPinned(threadId: String, pinned: Boolean)
    fun moveThread(threadId: String, groupId: String)      // drag & drop
    fun newGroup(name: String): String
    fun renameGroup(id: String, name: String)
    fun deleteGroup(id: String)

    // --- duplicar / ramificar ---
    fun duplicateThread(threadId: String)
    fun branchThread(threadId: String, upToMessageId: String?, newTitle: String)

    // --- búsqueda ---
    fun setQuery(q: String)
    fun setFilter(filter: HistoryFilter)
    fun clearFilters()

    // --- exportación ---
    fun exportThread(threadId: String, format: ExportFormat, destination: ExportDestination)
    fun exportPackage(scope: PackageScope, options: PackageOptions)
    fun shareExported(path: FilePath)

    // --- borrado ---
    fun deleteThread(threadId: String)
}
```

```kotlin
data class StudentHistoryUiState(
    val loading: Boolean = true,
    val courseId: String = "",
    val course: Course? = null,
    /** Modo de presentación según el ancho. */
    val listMode: ThreadListMode = ThreadListMode.FLAT,
    /** Grupos del alumno (en orden). */
    val groups: List<GroupListItem> = emptyList(),
    /** Hilos visibles (ya aplanados según el modo). */
    val threads: List<ThreadListItem> = emptyList(),
    /** Id de los grupos plegados (desktop). */
    val collapsedGroups: Set<String> = emptySet(),
    val selectedThreadId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val search: HistorySearchState = HistorySearchState(),
    val exportState: ExportState = ExportState.Idle,
    val message: String = "",       // Snackbar
)
```

---

## 10. Casos borde

| Caso | Comportamiento |
|---|---|
| Alumno sin ningún hilo en la cátedra | El sidebar muestra `HistoryEmptyState`: "Todavía no tenés conversaciones en esta cátedra." + botón "Empezar a chatear". La búsqueda devuelve "No hay conversaciones para buscar." |
| Hilo sin título y sin mensajes | Título `"Conversación"`, `autoTitle = true`. Al llegar el primer mensaje se deriva. |
| Hilo con el primer mensaje vacío (el alumno lo borró) | El título **no** cambia: la derivación usa el primer mensaje **no vacío**; si no queda ninguno, el título queda como está y `autoTitle` vuelve a `true` para el próximo mensaje. |
| El alumno edita el primer mensaje | Si `autoTitle` sigue en `true`, el título se vuelve a derivar y se avisa por Snackbar. Si lo renombró a mano, el título no se toca. |
| El alumno renombra a mano y después borra el primer mensaje | El título manual se conserva (no se re-deriva: `autoTitle` ya está en `false`). |
| Hilo con 2 000 mensajes | La lista no lo carga entero de una: el visor hace scroll virtual (`LazyColumn`) sobre `messages.jsonl` completo. El JSONL de 2 000 mensajes ≈ 1,8 MB: se lee entero (el visor necesita el orden). Si en el futuro molesta, el índice guardará los `offsets` por mensaje para lectura por rango. |
| Un mensaje quedó a medias (fallo al escribir) | La última línea del `messages.jsonl` no termina en `\n` → se descarta al leer. Se pierde **como máximo el último mensaje**. La UI avisa: "El último mensaje no se guardó (se interrumpió la escritura). Volvelo a enviar." |
| Dos hilos con el mismo título | Se permiten: los distingue el id. En el sidebar se muestra un chip con el modelo y la fecha cuando hay títulos repetidos. |
| Un grupo con 0 hilos | Se muestra con "(0)" y un ícono atenuado; se puede borrar. |
| Borrar un grupo con hilos | Los hilos quedan sin grupo; el diálogo lo dice explícitamente. |
| Borrar un hilo con un mensaje en streaming | Se cancela la generación antes de borrar (mismo criterio que `ChatLibrary.deleteNode`). |
| El hilo tiene una conversación con el modelo **local** y ahora no hay modelo | Al abrirlo, el mensaje de aviso: "El modelo con el que esta conversación se generó no está disponible. Podés seguir la conversación con el modelo de la cátedra." El `effectiveModel` se restaura sólo si el modelo existe. |
| La cátedra cambió de `topK` o de prompt | Al continuar, aparece el aviso "La configuración de la cátedra cambió" (§4.2) con las dos opciones. |
| El material de la cátedra cambió | Aviso con el conteo de materiales nuevos/modificados (comparando `Material.uploadedAt` con `thread.updatedAt`). |
| La visibilidad del alumno cambió | Aviso al abrir la app (ya definido en `08-alumnos.md` §6.7). |
| Exportar un hilo con adjuntos | El diálogo ofrece incluir los adjuntos (por defecto **no**): los archivos son del alumno, pueden ser personales. |
| Exportar un hilo con citas sin respaldo | Se exportan **tal cual**, con su veredicto: el docente tiene que ver la realidad, no una versión saneada. El diálogo lo dice: "Se incluyen 1 cita sin respaldo, con su veredicto." |
| El paquete `.kitoru` pesa > 200 MB | Se rechaza con "El paquete es demasiado grande (240 MB). Se admiten hasta 200 MB." (mismo límite que el importador del docente). |
| El disco está lleno | La escritura del mensaje falla → se muestra "No se pudo guardar el mensaje (disco lleno)." y el mensaje **queda en pantalla** para reintentar. No se pierde en silencio. |
| La búsqueda no encuentra nada | "No hay coincidencias." + el alcance aplicado + el botón "Quitar filtros". |
| La búsqueda con acentos | "matematica" encuentra "matemática": el tokenizador normaliza con `stripDiacritics` (el mismo del índice BM25). |
| La búsqueda encuentra sólo mensajes del asistente | Se muestra el filtro por rol (implícito: los resultados marcan el rol). |
| La clave de firma se pierde | El paquete sigue exportándose, sin firma, y la app avisa: "No hay clave de firma en este dispositivo: el paquete se exportará sin firmar." Se puede regenerar una clave (eso invalida las firmas anteriores). |
| La firma no verifica | El importador del docente muestra: "La firma no coincide: el archivo fue modificado después de exportarse, o la clave no es la del alumno." |
| Exportar mientras se genera una respuesta | Se exporta lo que hay hasta ese momento, con un aviso: "La conversación se está generando: el paquete no incluye la respuesta en curso." |
| El alumno tiene 40 hilos y ninguno en un grupo | El grupo "Sin grupo (40)" los muestra todos. |
| El alumno comparte un paquete y después lo borra | El archivo enviado no se puede recuperar: el panel "Lo que compartiste" lo dice. |

---

## 11. Criterios de aceptación

1. El sidebar del alumno muestra los hilos de la cátedra **sin carpetas anidadas**:
   en desktop, grupos de un nivel como secciones plegables con drag & drop; en
   móvil (`< 760 dp`), una lista plana con el grupo como chip y filtro.
2. Arrastrar un hilo a la cabecera de un grupo lo mueve; soltar un hilo sobre un
   grupo no puede producir un error de "no se puede soltar dentro de sí mismo"
   (no existe esa condición).
3. El título de un hilo nuevo se deriva del primer mensaje del alumno con
   `ThreadTitle.derive` (48 caracteres, corte en límite de palabra, fallback
   "Conversación <fecha>"), y renombrar a mano lo congela; "Volver al título
   automático" lo restaura.
4. El menú de un hilo ofrece: Continuar, Renombrar, Etiquetar, Mover a grupo,
   Fijar, Duplicar, Ramificar, Exportar, Compartir y Eliminar; "Continuar"
   restaura el `effectiveModel` del hilo y avisa si la configuración de la
   cátedra o el material cambiaron.
5. "Duplicar" crea un hilo nuevo con todos los mensajes y `duplicateOf` apuntando
   al original; el original no se modifica. "Ramificar" crea un hilo nuevo con
   los mensajes **hasta** el punto elegido.
6. Exportar un hilo produce Markdown con la cabecera de procedencia y el bloque
   final "parte de este texto fue generado por un modelo".
7. Exportar el paquete produce un `.kitoru` **válido para el importador del
   docente**: ZIP con `manifest.json`, `threads.json`, `messages.json`,
   `groups.json`; el manifiesto trae `formatVersion: 1`, `contentType`,
   `sha256` del contenido y `scope`.
8. El `sha256` del manifiesto coincide con el que calcula `PackageDigest` sobre
   las mismas entradas (test vectors compartidos con el importador del docente).
9. Con la firma activada, el paquete incluye `signature.json` con una firma
   Ed25519 válida sobre el digest, y la verificación da `true`; sin firma, el
   paquete **no** incluye `signature.json` y la app no dice "firmado".
10. En Android, "Compartir" abre la hoja de compartir del sistema con el
    `.kitoru` adjunto; en desktop, "Guardar como…" + "Abrir carpeta" + "Copiar la
    texto para el mail".
11. La pantalla de consentimiento lista **qué va a poder ver el docente** y
    advierte explícitamente cuando la visibilidad es "Solo métricas" y el paquete
    **sí** revela el texto, los títulos y los grupos.
12. La búsqueda encuentra un mensaje por coincidencia **aproximada** (BM25), no
    por palabra exacta; "matematica" encuentra "matemática".
13. La búsqueda filtra por fecha, material citado, grupo, etiqueta y por
    "respuestas sin respaldo".
14. Guardar un mensaje **no** reescribe los mensajes de los otros hilos: el
    archivo `messages.jsonl` del hilo crece por anexado y el tamaño de los demás
    archivos no cambia (test: se mide el tamaño del `messages.jsonl` de otro hilo
    antes y después).
15. Listar los hilos lee `index.json` y **no** lee ningún `messages.jsonl`
    (test con un `FileSystem` doble que cuenta las lecturas).
16. La migración desde `config/messages.json` conserva **todos** los mensajes,
    deja los `.bak` y es idempotente (se puede ejecutar dos veces).
17. "Mi actividad" muestra los mismos números que el panel del docente: un test
    compara `StudentActivity.analytics` con el `StudentAnalytics` que produce
    `AnalyticsAnalyzer.analyze(...)` para la misma inscripción.
18. El tiempo de uso estimado respeta los topes (15 min de corte entre sesiones,
    45 min por sesión) y la interfaz lo llama "tiempo de uso estimado".
19. La lista de "respuestas sin respaldo" muestra el motivo exacto del
    `CitationVerifier` y permite abrir la fuente.
20. "Lo que compartiste" lista las exportaciones registradas con fecha, formato,
    cantidad de hilos y si fueron firmadas; y dice que compartir no es revocable.

---

## 12. Dependencias con otros documentos

| Otro doc | Relación |
|---|---|
| `docs/design/teacher/07-chats.md` (docente: chats) | **Debe coincidir** el formato `.kitoru`: `manifest.json`, `threads.json`, `messages.json`, `formatVersion`. Este doc propone `contentType`, `scope`, `groups.json`, `attachments/` y la firma; el doc del docente debe **importar el manifiesto desde `:core`** (`kitoru.domain.Package`) y no redefinirlo. La regla del `sha256` debe ser la de `PackageDigest` en ambos lados. |
| `docs/design/teacher/07-chats.md` §5.2 | El `ChatSearchIndex` del docente debe **delegar** en `rag.history.HistorySearchEngine` para que la tokenización y los snippets coincidan. |
| `docs/design/teacher/07-chats.md` §8.5 | `ThreadOrigin`, `ChatThread.origin`, `originPackageId` y `ChatAttachment` se adoptan tal cual (el hilo importado en la app del alumno usa la misma clave `imp|<pkgId>|<idOriginal>`). |
| `docs/design/teacher/08-alumnos.md` (docente: alumnos) | `Enrollment.studentCap` y `effectiveVisibility` son la base de "Qué ve tu docente". `VisibilityCatalog.whatTeacherSees(...)` debe ser el **único** catálogo de textos normativos, compartido por el diálogo del docente, el panel del alumno y la pantalla de consentimiento. |
| `docs/design/teacher/08-alumnos.md` §11.2 | `ui/.../student/privacy/` ya existe: este doc **agrega** la pestaña "Mi actividad" (`ui/.../student/activity/`) y no duplica `PrivacyScreen`. |
| `docs/design/domain/08-permisos.md` | Capacidades usadas: `CHAT_CREATE`, `CHAT_VIEW_OWN`, `CHAT_SEND`, `CHAT_RENAME_TAG`, `CHAT_DELETE_OWN`, `ANALYTICS_VIEW_OWN`, `ENROLLMENT_VIEW_OWN`, `APP_SETTINGS_EDIT`, `APP_DATA_EXPORT`. **No se agregan capacidades** (la matriz tiene un invariante de cobertura). La firma del paquete requiere `APP_SETTINGS_EDIT` (la clave vive en los ajustes). |
| `docs/design/domain/08-permisos.md` §8 | `AccessLogEntry`: el alumno **no puede** verlo en el milestone 1 (el log vive en la instalación del docente). El panel del alumno lo dice explícitamente en vez de mostrar un vacío. |
| Doc de analítica (a definir) | `StudentAnalytics` gana `estimatedUsageMillis`; el docente debe mostrarlo también para que los dos paneles coincidan. |
| Doc de navegación del alumno (a definir) | Dónde vive el acceso al historial y a "Privacidad y datos" en la app del alumno (rail en desktop, pestaña/destino en Android). |
| `CONTEXTO.md` §"Reglas de diseño" | Regla 1 (reutilizar BM25, `ModelPolicy`, `CitationVerifier`), regla 2 (portable: `Zip`/`Signing` con `expect/actual`, `java.util.zip` y `eddsa` en `jvmCommonMain`), regla 3 (determinista: `ThreadTitle`, `StudyTimeEstimator`, `PackageDigest` y `AnalyticsAnalyzer` son funciones puras), regla 5 (agencia: el `effectiveModel` del hilo se restaura tal como el alumno lo dejó). |

---

## Anexo A · Diagrama del flujo de datos

```
     ALUMNO
        │
        │  escribe
        ▼
  ┌───────────┐   ChatMessage    ┌──────────────────────┐
  │  CHAT     │ ───────────────► │ MessageRepository    │
  │ (runtime) │                  │  append → messages.jsonl
  └───────────┘                  └──────────────────────┘
        │                                  │
        │ primer mensaje                   │ lee
        ▼                                  ▼
  ┌───────────┐  derive   ┌──────────────────────┐
  │ThreadTitle│ ────────► │ ThreadRepository     │
  └───────────┘           │  meta.json + index   │
                          └──────────────────────┘
                                 │
        ┌────────────────────────┼────────────────────────┐
        │                        │                        │
        ▼                        ▼                        ▼
   EXPORTAR                BUSCAR                    RESUMIR
   .kitoru (ZIP)          BM25 en memoria      AnalyticsAnalyzer.analyze
   manifest+sha256        (rag.history)         (los MISMOS números
        │                       │                      que ve el docente)
        ▼                       ▼                        │
   ┌─────────┐          ┌─────────────┐                  │
   │ docente │          │  resultados │                  │
   │ importa │          └─────────────┘                  │
   └─────────┘                                             ▼
                                                   "Mi actividad"
                                                   "Qué ve tu docente"
                                                   "Lo que compartiste"
```
