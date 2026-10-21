# 02 — Pantalla de chat del alumno

> Subagente: diseño de la **pantalla de chat del alumno**, el corazón de Kitoru.
> Módulos tocados: `:core` (dominio: adjuntos, referencias de fuente, modo de
> respuesta), `:ui` (Compose Multiplatform: markdown, código, chat),
> `:backend` (sólo se usa lo ya portado: `RagEngine`, `AgentLoop`,
> `ToolPermissions`).
> Código de origen: `FunshiEngineAI/frontend/src/main/kotlin/app/chat/*` y
> `app/ui/Markdown.kt` + `app/ui/CodeBlock.kt`.
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable por
> defecto*, *determinista*, *fail-fast con mensajes en español*, *el estudiante
> conserva agencia*, *el docente no ve lo que no debe*.

---

## 0. Resumen en cinco líneas

1. El chat es una `LazyColumn` de mensajes + un redactor fijo abajo; en Android
   el redactor lleva `imePadding()` y la lista se encoge sola (misma columna con
   `weight(1f)`).
2. El streaming se actualiza con `updateLast(...)` sobre un
   `SnapshotStateList<UiChatMessage>` (el `mutableStateOf` + `updateLast` de
   FunshiEngineAI, con la copia O(n) por token reemplazada por una O(1)).
3. El auto-scroll usa el `followToBottom` **exacto** de `ChatPane.kt`: el flag
   sólo cambia cuando cambia la *posición* de scroll, nunca cuando crece el
   último mensaje.
4. Se porta `parseMarkdown` / `buildInlineAnnotated` / los renderizadores tal
   cual a `:ui` común; **no** se porta JLaTeXMath: las fórmulas se dibujan con
   un renderer **en Compose puro** (común a las dos plataformas) y caen a
   *monoespaciado con el LaTeX visible* si no se pueden dibujar.
5. `CodeBlock` se porta entero salvo el portapapeles (que ya es portable:
   `LocalClipboardManager`) y el "Guardar como…", que se resuelve con un
   `expect/actual` (**desktop**: `JFileChooser`; **Android**: SAF).

### 0.1 Decisiones en una línea

| # | Decisión | Alternativa descartada | Por qué |
|---|---|---|---|
| D-1 | `StudentChatViewModel` es una clase plana (sin `androidx.lifecycle`) con un `CoroutineScope` inyectado | `ViewModel` de AndroidX | El módulo `:ui` es multiplatform; `lifecycle-viewmodel` KMP agregaría una dependencia y los tests de `:ui` la necesitarían |
| D-2 | Los mensajes viven en un `SnapshotStateList` y `updateLast` muta el último | `mutableStateOf(list)` + `dropLast(1) + last().copy()` | El port de FunshiEngineAI copia la lista entera **por token**; con 200 mensajes eso son 200·N asignaciones de lista |
| D-3 | Un único renderer de fórmulas, **en Compose puro**, común a desktop y Android | JLaTeXMath en desktop + fallback en Android | Dos renderizadores = el alumno copia cosas distintas en el escritorio y en el teléfono |
| D-4 | Sin `expect/actual` para el portapapeles: se usa `LocalClipboardManager` (ya es común) | `java.awt.Toolkit` como en `CodeBlock.kt` | `Toolkit` no existe en Android; `LocalClipboardManager` funciona en las dos |
| D-5 | El "Guardar como…" es un `expect @Composable` que devuelve un lanzador | Un `expect fun` con servicio global | SAF necesita un `ActivityResultLauncher`, que sólo se puede crear en composición |
| D-6 | Las fuentes se muestran como **chips debajo del mensaje** + hoja de detalle; el distintivo de citas es el `CitationBadge` ya diseñado en `teacher/04-prompt.md` §6.4 | Una hoja lateral fija | En pantalla angosta no hay "lateral"; los chips son un elemento familiar de chat |
| D-7 | El permiso de herramientas es **por categoría** (copiado de `PermissionDialog.kt`), con el **techo de la cátedra** por encima (`domain/08-permisos.md` §6) | Un permiso por herramienta | Es lo que ya implementa `rag.agent.ToolPermissions`; el techo lo agrega el backend |
| D-8 | El toggle "Usar el material / Preguntar libre" vive **en el redactor** (no en la barra superior) | Toggle arriba, como en FunshiEngineAI | Es una decisión *por mensaje*; en el teléfono la barra superior no tiene lugar |
| D-9 | El default es **"Usar el material de la cátedra"** (invierte el default General/Docs de FunshiEngineAI) | Default "General" | En Kitoru el RAG es el producto; el modo libre es la excepción |
| D-10 | Enter envía en escritorio; en Android envía la acción IME "Enviar" | `onPreviewKeyEvent` en todas las plataformas | El teclado del móvil **no** genera `KeyEvent`: sin `KeyboardActions` no se puede enviar |
| D-11 | El texto en streaming se pinta como **markdown parcial**: los bloques sin cerrar se muestran como texto plano | Bufferizar hasta el final | El alumno tiene que ver la respuesta aparecer, no un bloque que se completa |

---

## 1. Mapa: qué se porta de FunshiEngineAI

| Archivo de origen | Destino en Kitoru | Cambios |
|---|---|---|
| `app/chat/ChatPane.kt` | `ui/commonMain/.../chat/StudentChatScreen.kt` + `MessageList.kt` | Se le sacó el `ChatListSidebar` (los hilos viven en un cajón) y el `Row` con `width(220.dp)`; se le agrega `imePadding` |
| `app/chat/ChatScrollbarAdapter.kt` | `ui/commonMain/.../chat/ChatScrollbarAdapter.kt` | **Port literal.** `androidx.compose.foundation.v2.ScrollbarAdapter` existe en CMP 1.7 (`@OptIn(ExperimentalFoundationApi::class)`) |
| `app/ui/Scrollbar.kt` (`ThemedVerticalScrollbar`) | `ui/commonMain/.../components/ThemedVerticalScrollbar.kt` | Port literal + `scrollTargetForKey` (función pura, testeable) |
| `app/chat/MessageBubble.kt` | `ui/commonMain/.../chat/MessageBubble.kt` | `UiMessage` → `UiChatMessage`; `UiCitation` → `kitoru.domain.CitationStats`; `UiToolCall` → `kitoru.domain.ToolCallStats`; `UiAttachment` → `kitoru.domain.ChatAttachment` |
| `app/chat/MessageBubble.kt` (`CitationBadge`) | `ui/commonMain/.../components/CitationBadge.kt` | **Ya diseñado** en `teacher/04-prompt.md` §6.4 → se reutiliza tal cual, sin duplicar |
| `app/chat/ComposerBar.kt` | `ui/commonMain/.../chat/ComposerBar.kt` | Se agrega `KeyboardActions` (IME), el toggle de modo y el chip de permisos |
| `app/chat/ChatViewModel.kt` | `ui/commonMain/.../chat/StudentChatViewModel.kt` | Se le cambian los tipos (`UiMessage` → `UiChatMessage`), se le agrega la cola con su UI y el techo de herramientas |
| `app/chat/ToolCallCard.kt` | `ui/commonMain/.../components/ToolCallCard.kt` | `UiToolCall` → `ToolCallStats`; se le agrega la duración |
| `app/chat/AttachmentCard.kt` | `ui/commonMain/.../components/AttachmentCard.kt` | `java.awt.Desktop` y `javax.swing.JFileChooser` → `FileActions` (expect/actual) |
| `app/chat/PermissionDialog.kt` | `ui/commonMain/.../chat/PermissionDialog.kt` | Port + techo de la cátedra (§7.4) |
| `app/ui/Markdown.kt` (`parseMarkdown`, `MdNode`) | `ui/commonMain/.../markdown/MarkdownParser.kt` | **Port literal** (puro Kotlin) |
| `app/ui/Markdown.kt` (`buildInlineAnnotated`) | `ui/commonMain/.../markdown/Inline.kt` | Port + rama opcional de citas `[n]` (§6.4) |
| `app/ui/Markdown.kt` (`MarkdownText` y renderizadores) | `ui/commonMain/.../markdown/MarkdownText.kt` | Port; `MathBlock`/`ParagraphText` cambian (§4) |
| `app/ui/MathRender.kt` (`splitMath`, `MathSegment`) | `ui/commonMain/.../markdown/MathSegments.kt` | **Port literal** (puro Kotlin) |
| `app/ui/MathRender.kt` (`MathRender`, `MathRendered`) | **NO se porta** | Usa `java.awt` + JLaTeXMath (§4.3) |
| `app/ui/CodeBlock.kt` (`highlightCode`, `tokenize`, `LANG_TO_EXT`, `KEYWORDS_BY_LANG`) | `ui/commonMain/.../markdown/CodeHighlighter.kt` | **Port literal** (puro Kotlin) |
| `app/ui/CodeBlock.kt` (`CodeBlock`) | `ui/commonMain/.../markdown/CodeBlock.kt` | Port; el portapapeles pasa a `LocalClipboardManager` y "Guardar…" al seam |

**Lo que NO se porta**: voz (`VoiceChatButton`, `VoiceBar`, `VoiceService`), el
índice de chats con carpetas (`ChatLibrary`), los comandos `/help`, `/model`,
`/tools` (en Kitoru el modelo se configura en una pantalla, no con comandos) y
el `ChatModeToggle` de la barra superior (pasa al redactor, D-8).

---

## 2. Layout

### 2.1 Escritorio

```
┌───────────────────────────────────────────────────────────────────────────────┐
│ ☰  Inteligencia Artificial · 2026-1        [Qwen3-8B ⌄]   [Solo / Con cátedra]│  ← ChatHeader
├───────────────────────────────────────────────────────────────────────────────┤
│ ⚠ Sin conexión con el servidor de la cátedra.        [Reintentar]  [Cerrar] │  ← banner (opcional)
├───────────────────────────────────────────────────────────────────────────────┤
│  ┌ (izq)                                                    ┌ (der) ┐      │
│  │ respuesta del modelo (markdown, chips de fuentes,         │  ▲    │      │  ← ThemedVerticalScrollbar
│  │ distintivo de citas, tarjetas de herramienta, adjuntos)    │  █    │      │
│  └                                                        └───────┘      │
│                                         ┌ (der)                            │
│                                         │ pregunta del alumno              │
│                                         └                                  │
│                                                                             │
├───────────────────────────────────────────────────────────────────────────────┤
│ [● Usar el material] [Preguntas libres]        Archivos: permitidos          │  ← fila del modo
│ ┌────────────────────────────────────────────────┐   ┌──────┐                │
│ │ Escribí tu pregunta sobre el material…         │   │ Enviar│                │
│ └────────────────────────────────────────────────┘   └──────┘                │
│ 2 en cola — se enviarán juntas al terminar  [Descartar] [Quitar]             │  ← cola (opcional)
└───────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Android (pantalla angosta, ≤ 600 dp)

```
┌────────────────────────────┐
│ ←  Inteligencia Artificial  │  ← TopAppBar (48 dp) + menú ⋮
├────────────────────────────┤
│ ⚠ La clave venció. [Cerrar]│  ← banner, plegable a una línea
├────────────────────────────┤
│                            │
│   mensajes (LazyColumn)    │  ← sin scrollbar (lo pone el sistema)
│                            │
│                            │
├────────────────────────────┤
│ [● Usar el material]       │  ← fila del modo (chips chicos)
│ ┌──────────────────────┐   │
│ │ Escribí tu pregunta… │ ↑ │  ← imePadding(): sube con el teclado
│ └──────────────────────┘   │
└────────────────────────────┘
```

Diferencias con el escritorio, todas justificadas por el ancho:

| Elemento | Escritorio | Android |
|---|---|---|
| Lista de hilos | Cajón lateral permanente de 240 dp | Cajón modal (`ModalNavigationDrawer`) |
| Scrollbar | `ThemedVerticalScrollbar` (puerto) | No (el sistema ya muestra el indicador) |
| Ancho de la burbuja | `widthIn(max = 700.dp)` | `fillMaxWidth()` con padding de 12 dp |
| Hoja de fuentes | `Dialog` de 640 dp | `ModalBottomSheet` (60 % de la altura) |
| Acciones del mensaje | Fila de botones siempre visible | Fila de botones **dentro** del menú contextual (⋮) de la burbuja |
| Botón de enviar | Botón de texto "Enviar" | `IconButton` con flecha ↑ (el teclado del móvil ocupa mucho) |
| Ir al final | FAB "↓" flotante | Ícono flotante "↓" |

### 2.3 Insets (el detalle que hace o rompe la pantalla angosta)

```kotlin
@Composable
fun StudentChatScreen(vm: StudentChatViewModel) {
    Column(Modifier.fillMaxSize()) {
        ChatHeader(vm)                                   // TopAppBar: maneja status bar
        if (!vm.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.banner?.let { ChatStatusBanner(it) { vm.dismissBanner() } }

        // weight(1f): SIEMPRE deja espacio para el redactor.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MessageList(vm)
        }

        // El redactor sube con el teclado; la lista se encoge porque la
        // columna tiene altura fija (fillMaxSize) y la lista tiene weight(1f).
        ComposerBar(
            vm = vm,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))
                .imePadding(),
        )
    }
}
```

**Por qué ese orden de insets** (es el error clásico):

- `WindowInsets.navigationBars.exclude(WindowInsets.ime)` → cuando el teclado
  está **cerrado** se reserva la barra del sistema; cuando está **abierto**, la
  parte de la barra que queda tapada por el teclado se descarta (`exclude`
  quita el solapamiento), así no se cuenta dos veces.
- `.imePadding()` encima → agrega exactamente la altura del teclado.
- Si se hiciera `navigationBarsPadding().imePadding()` a secas, con el teclado
  abierto la barra del sistema se contaría **dos veces** (el `ime` inset ya
  incluye la zona de la barra de navegación).
- En escritorio los tres inset valen 0: la misma cadena sirve para las dos
  plataformas, sin `expect/actual` de insets.

**La lista se ajusta sola**: la columna raíz tiene altura fija (`fillMaxSize` =
altura de la ventana). El redactor crece con `imePadding()` y la lista, que
tiene `weight(1f)`, pierde exactamente esa altura. No hace falta escuchar el teclado.

`LazyColumn` con `contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp,
horizontal = 4.dp)` y `verticalArrangement = Arrangement.spacedBy(12.dp)`.

### 2.4 Jerarquía de scroll

- La `LazyColumn` es la única que scrollea.
- Las hojas (fuentes, permisos, menú del mensaje) scrollean por su cuenta.
- El drawer de hilos scrollea por su cuenta.
- `SelectionContainer` **no** envuelve la burbuja entera en Android (el crash
  "ActiveParent with no focused child" de `MessageBubble.kt` también existe
  en Android): el `SelectionContainer` va **sólo** sobre el texto, nunca sobre
  los botones.

---

## 3. Streaming

### 3.1 Estado

```kotlin
// ui/commonMain/kotlin/kitoru/ui/chat/UiChatMessage.kt
package kitoru.ui.chat

import kitoru.domain.CitationStats
import kitoru.domain.ChatAttachment
import kitoru.domain.SourceRef
import kitoru.domain.ToolCallStats

/**
 * Un mensaje tal como lo ve la UI.
 *
 * Es el `UiMessage` de FunshiEngineAI, pero con los tipos del **dominio de
 * Kitoru** en lugar de las estructuras de la app. No se serializa: la
 * persistencia la hace `kitoru.domain.ChatMessage`.
 */
data class UiChatMessage(
    val id: String,
    val role: String,              // "user" | "assistant"
    val text: String,
    val sources: List<SourceRef> = emptyList(),
    val citation: CitationStats? = null,
    val toolCalls: List<ToolCallStats> = emptyList(),
    val attachments: List<ChatAttachment> = emptyList(),
    /** true mientras el modelo está escribiendo este mensaje. */
    val streaming: Boolean = false,
    /** Modo con el que se generó: "MATERIAL" | "LIBRE". */
    val mode: String = "MATERIAL",
)
```

### 3.2 El `ViewModel` (núcleo del streaming)

```kotlin
// ui/commonMain/kotlin/kitoru/ui/chat/StudentChatViewModel.kt
package kitoru.ui.chat

class StudentChatViewModel(
    private val scope: CoroutineScope,
    private val engine: RagEngine,
    private val gateway: LlmGateway,
    private val tools: ToolRegistry,
    private val course: Course,
    private val enrollment: Enrollment,
    private val threads: ThreadRepository,
    private val messagesRepo: MessageRepository,
) {
    /**
     * Los mensajes de la conversación.
     *
     * Es un `SnapshotStateList` (no un `mutableStateOf(list)`) a propósito: el
     * `updateLast` de FunshiEngineAI reemplaza la lista **entera** en cada
     * token (`messages = messages.dropLast(1) + messages.last().copy(...)`),
     * lo que con 200 mensajes son 200·N copias de lista por respuesta. Con el
     * `SnapshotStateList` la mutación del último mensaje es O(1) y Compose
     * sólo recompone la burbuja que cambió.
     */
    val messages = mutableStateListOf<UiChatMessage>()

    /** El modelo está escribiendo. */
    var busy by mutableStateOf(false)
    /** El motor todavía no está listo (indexando material, abriendo gateway). */
    var ready by mutableStateOf(false)
    /** Mensajes escritos mientras el modelo respondía. */
    var queuedMessages by mutableStateOf(listOf<String>())
    /** Error visible en banner. */
    var banner by mutableStateOf<ChatBanner?>(null)
    /** Petición de permiso esperando decisión del alumno. */
    var permissionRequest by mutableStateOf<PermissionRequest?>(null)
    /** Permiso pendiente (lo resuelve el diálogo). */
    internal var permissionAnswer: CompletableDeferred<PermissionDecision>? = null
    /** Categorías ya concedidas en este chat. */
    var grantedCategories by mutableStateOf(setOf<ToolCategory>())
    /** Modo del chat: usar el material de la cátedra (default) o preguntar libre. */
    var useCourseMaterial by mutableStateOf(true)
    /** Texto del redactor (vive en el VM para que el botón y el teclado usen el mismo camino). */
    var composerInput by mutableStateOf("")
    ...
}
```

### 3.3 `updateLast`: el único punto por donde entra el streaming

```kotlin
    /**
     * Actualiza el último mensaje (el que se está escribiendo).
     *
     * **Port exacto** de `ChatViewModel.updateLast` de FunshiEngineAI, con una
     * sola diferencia: en vez de reemplazar la lista completa, se muta el
     * último elemento. La semántica es idéntica: durante el streaming se
     * pisan `sources`, `toolCalls` y `attachments` con los valores acumulados
     * hasta el momento, y `citation` queda en `null` hasta que la respuesta
     * termina (recién ahí se pasa el informe de verificación).
     */
    internal fun updateLast(
        text: String,
        sources: List<SourceRef> = emptyList(),
        toolCards: List<ToolCallStats> = emptyList(),
        attachments: List<ChatAttachment> = emptyList(),
        citation: CitationStats? = null,
    ) {
        val last = messages.lastIndex
        if (last < 0) return
        messages[last] = messages[last].copy(
            text = text,
            sources = sources,
            toolCalls = toolCards,
            attachments = attachments,
            citation = citation,
        )
    }
```

### 3.4 El envío (streaming real)

```kotlin
    fun send(text: String) {
        if (text.isBlank()) return
        val trimmed = text.trim()
        // La IA está respondiendo: el texto se encola.
        if (busy) {
            queuedMessages = queuedMessages + trimmed
            status = "${queuedMessages.size} mensaje(s) en cola — se enviarán juntos al terminar la respuesta."
            return
        }
        val history = messages.filter { it.text.isNotEmpty() }.map {
            llm.config.ChatMessage(it.role, it.text)
        }
        // El mensaje del alumno + el placeholder del asistente, de una.
        messages += UiChatMessage(newId(), "user", trimmed, mode = currentMode())
        messages += UiChatMessage(newId(), "assistant", "…", streaming = true, mode = currentMode())

        if (!ready) { pending.add(trimmed to history); return }
        sendInternal(trimmed, history)
    }

    private fun sendInternal(text: String, history: List<llm.config.ChatMessage>) {
        busy = true
        banner = null
        chatJob?.cancel()
        chatJob = scope.launch {
            try {
                val prepared = withContext(ioDispatcher) { engine.prepare(text, history, useCourseMaterial) }
                val sourceRefs = prepared.sources.mapIndexed { i, c ->
                    SourceRef(index = i + 1, label = c.label, materialTitle = c.source, heading = c.heading)
                }
                //(mode nativo = REMOTE; por prompt = LOCAL), como en FunshiEngineAI.
                val native = effectivePolicy.mode == ModelMode.REMOTE
                val loop = AgentLoop(gateway, tools, native, toolTimeoutMs)
                var cards = listOf<ToolCallStats>()
                var attachments = listOf<ChatAttachment>()
                val sb = StringBuilder()

                val ctx = ToolContext(
                    chatId = threadId,
                    outputsDir = outputsDirFor(threadId),
                    allowedCategories = toolCeiling,        // techo de la cátedra (08-permisos §6.4)
                    grantedCategories = grantedCategories,
                    requestPermission = { req -> askPermission(req) },
                )

                val answer = withContext(ioDispatcher) {
                    loop.run(prepared.messages, ctx) { ev ->
                        when (ev) {
                            is AgentEvent.Text -> {
                                sb.append(ev.delta)
                                updateLast(sb.toString(), sourceRefs, cards, attachments)
                            }
                            is AgentEvent.ToolStart -> {
                                cards = cards + ToolCallStats(ev.name, ev.argsJson, null, "", 0L)
                                updateLast(sb.toString(), sourceRefs, cards, attachments)
                            }
                            is AgentEvent.ToolEnd -> {
                                val i = cards.indexOfLast { it.name == ev.name && it.ok == null }
                                cards = if (i >= 0) {
                                    cards.withIndex().map { (j, c) ->
                                        if (j == i) c.copy(ok = ev.ok, result = ev.result) else c
                                    }
                                } else {
                                    cards + ToolCallStats(ev.name, "", ev.ok, ev.result, 0L)
                                }
                                attachments = attachments + ev.artifacts.map {
                                    ChatAttachment(it.name, it.path, it.sizeBytes, it.mime)
                                }
                                updateLast(sb.toString(), sourceRefs, cards, attachments)
                            }
                        }
                    }
                }
                // Verificación de citas (determinista, sin red): sólo tiene
                // sentido en modo material y con fuentes recuperadas.
                val citation = if (useCourseMaterial && prepared.sources.isNotEmpty()) {
                    val report = engine.verify(answer.text, prepared.sources)
                    CitationStats(report.verified, report.unsupported, report.unknownCount)
                } else null
                val last = messages.lastIndex
                if (last >= 0) messages[last] = messages[last].copy(streaming = false)
                updateLast(answer.text, sourceRefs, cards, attachments, citation)
            } catch (e: CancellationException) {
                val last = messages.lastIndex
                if (last >= 0) messages[last] = messages[last].copy(text = "(detenido)", streaming = false)
                throw e
            } catch (e: Exception) {
                val last = messages.lastIndex
                if (last >= 0) messages[last] = messages[last].copy(text = "⚠ ${friendly(e)}", streaming = false)
            } finally {
                busy = false
                persistActiveThread()
                flushSendQueue()
            }
        }
    }
```

Detalles que importan:

- `updateLast` se llama **en cada delta**: el texto crece de a token, como en
  FunshiEngineAI. No hay batching (no hace falta: `SnapshotStateList` + Compose
  ya coalescan los frames).
- Al terminar, `citation` se calcula **fuera** del streaming (es un cálculo
  determinista sobre el texto final) y se pasa en la llamada final a
  `updateLast`.
- En `CancellationException` el texto parcial **se conserva** y se marca
  "(detenido)": el alumno no pierde lo que el modelo alcanzó a decir.
- `flushSendQueue()` manda lo encolado **junto** (mismo comportamiento que
  FunshiEngineAI).

### 3.5 Auto-scroll: el `followToBottom` de `ChatPane.kt`, portado exacto

```kotlin
// ui/commonMain/kotlin/kitoru/ui/chat/MessageList.kt

/** Píxeles "de más" al hacer scroll-to-bottom: el último mensaje suele ser más alto que el viewport. */
private const val SCROLL_PX_TO_END = 1_000_000f

@Composable
fun MessageList(vm: StudentChatViewModel) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scrollbarAdapter = rememberStableChatScrollbarAdapter(listState, vm.threadId)

    /**
     * ¿Seguir el pie del chat mientras la IA escribe?
     *
     * PORT LITERAL del comentario de `ChatPane.kt`: el flag sólo cambia cuando
     * la POSICIÓN de scroll se mueve (scroll del usuario o nuestro): NO cuando
     * el último mensaje crece. Con el chequeo anterior («último ítem a la
     * vista») una respuesta más alta que el viewport mantenía el flag en true
     * y cada token arrastraba al usuario al fondo: era imposible leer una
     * respuesta larga mientras se escribía.
     */
    val followToBottom = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var prev = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { pos ->
            if (pos != prev) {
                val subio = pos.first < prev.first ||
                    (pos.first == prev.first && pos.second < prev.second)
                // Subió = dejó de seguir; bajó = sólo sigue si llegó al pie real.
                followToBottom.value = if (subio) false else !listState.canScrollForward
                prev = pos
            }
        }
    }

    /**
     * Baja hasta el final real de la lista (el último mensaje puede ser más
     * alto que el viewport). Usa el índice de los DATOS (vm.messages), no el
     * de layoutInfo: justo después de enviar, layoutInfo todavía no midió los
     * mensajes nuevos y apuntaba al final VIEJO de la conversación.
     */
    suspend fun scrollToBottom() {
        val last = vm.messages.lastIndex
        if (last < 0) return
        listState.scrollToItem(last)
        listState.scroll { scrollBy(SCROLL_PX_TO_END) }
    }

    // Al entrar al chat o cambiar de hilo: abajo del todo.
    LaunchedEffect(vm.threadId) {
        if (vm.messages.isNotEmpty()) scrollToBottom()
    }

    // Mensaje NUEVO en la lista (envío del usuario, placeholder de la IA o
    // cambio de hilo): SIEMPRE se ve. No se chequea nearBottom() acá: al
    // recién agregar ítems, el último visible deja de ser el último de la
    // lista y el chequeo daba falso.
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) scrollToBottom()
    }

    // Mientras la IA responde el último mensaje crece SIN cambiar la cantidad
    // de ítems: el chat sigue bajando sólo si el usuario no se fue a leer
    // hacia arriba (ahí layoutInfo es confiable, no cambió el conteo).
    LaunchedEffect(vm.messages.lastOrNull()?.text?.length) {
        if (vm.messages.isNotEmpty() && followToBottom.value) scrollToBottom()
    }
    ...
}
```

**Los cuatro `LaunchedEffect` son el corazón del comportamiento** y se portan
1:1. Su orden importa:

| # | Trigger | Qué hace | Por qué |
|---|---|---|---|
| 1 | `vm.threadId` | Abajo del todo | Al cambiar de hilo |
| 2 | `vm.messages.size` | Abajo del todo, **siempre** | Un mensaje nuevo siempre se ve (no se chequea `followToBottom`: al recién agregar ítems el chequeo da falso) |
| 3 | `text.length` del último | Abajo **sólo** si `followToBottom` | El streaming crece sin cambiar el conteo de ítems |
| 4 | `snapshotFlow` de la posición | Actualiza `followToBottom` | El flag depende de la **posición**, no del tamaño del contenido |

> **Trampa a evitar**: si el efecto 3 se cambiara por "el último ítem está
> visible", volvería el bug que el comentario de `ChatPane.kt` documenta. Se
> copia el algoritmo, no la idea.

### 3.6 Ir al final a mano

Cuando `!followToBottom` aparece un botón flotante:

```kotlin
// En MessageList.kt, sobre el Box que contiene la LazyColumn
AnimatedVisibility(
    visible = !followToBottom.value,
    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
) {
    SmallFloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(vm.messages.lastIndex) } }) {
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Ir al final")
    }
}
```

### 3.7 Cancelar (botón "Detener")

```kotlin
    /** Cancela la generación en curso (botón Detener) y descarta lo encolado. */
    fun cancel() {
        queuedMessages = emptyList()
        chatJob?.cancel()
        // El diálogo de permiso pendiente se cierra con DENY: si no, el AgentLoop
        // queda esperando una decisión que nunca va a llegar.
        permissionAnswer?.complete(PermissionDecision.DENY)
        permissionRequest = null
    }
```

> La diferencia con FunshiEngineAI: allí el `permissionAnswer` quedaba
> colgado y el loop esperaba para siempre. En Kitoru el "Detener" también
> resuelve el diálogo de permiso como **DENY**.

---

## 4. Markdown y código

### 4.1 Lo que se porta tal cual (puro Kotlin)

`app/ui/Markdown.kt` es, salvo `MathRender` y `toPainter()`, **Kotlin puro**:
`parseMarkdown`, `MdNode`, `isListItemStart`, `isTableSeparator`,
`splitTableRow`, `tableToText`, `buildInlineAnnotated`, `splitMath`,
`findInlineMathEnd`, `MathSegment`. Se portan **literalmente** (mismos
nombres, misma lógica, mismos comentarios) a:

- `ui/commonMain/kotlin/kitoru/ui/markdown/MarkdownParser.kt`
- `ui/commonMain/kotlin/kitoru/ui/markdown/Inline.kt`
- `ui/commonMain/kotlin/kitoru/ui/markdown/MathSegments.kt`

### 4.2 El renderizador

```kotlin
// ui/commonMain/kotlin/kitoru/ui/markdown/MarkdownText.kt

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    /** true si las citas "[n]" se marcan como referencias a las fuentes. */
    withCitations: Boolean = false,
    /** Se llama al tocar una cita "[n]". */
    onCitationClick: (Int) -> Unit = {},
) {
    val nodes = remember(text) { parseMarkdown(text) }
    Column(modifier) {
        nodes.forEachIndexed { idx, node ->
            when (node) {
                is MdNode.Heading -> HeadingText(node.level, node.text)
                is MdNode.Paragraph -> ParagraphText(node.text, withCitations, onCitationClick)
                is MdNode.Bullets -> BulletsText(node.items, node.ordered)
                is MdNode.Code -> CodeBlock(lang = node.lang, code = node.text, closed = node.closed)
                is MdNode.Quote -> QuoteText(node.text)
                is MdNode.Table -> TableText(node.header, node.rows)
                is MdNode.Math -> MathBlock(node.latex)
                MdNode.Divider -> DividerText()
            }
            if (idx != nodes.lastIndex) Spacer(Modifier.padding(top = 4.dp, bottom = 4.dp))
        }
    }
}
```

### 4.3 Fórmulas: la decisión

**D-3: se descarta JLaTeXMath y se implementa un renderer en Compose puro,
común a desktop y Android.**

`MathRender` (FunshiEngineAI) usa `java.awt.Graphics2D` + `BufferedImage` +
`org.scilab.forge.jlatexmath` (dependencia Java, no disponible en Android) y
después convierte el bitmap con `toPainter()` (extensión **JVM-only** de
`androidx.compose.ui.graphics`). Portarlo tal cual es imposible; portarlo
"adaptado" significa reimplementar el motor de composición de JLaTeXMath
(fracciones, raíces, scripts, matrices, delimitadores ajustados) — eso es
**reescribir**, no portar, y va en contra de la regla 1 del proyecto.

Tres opciones y por qué se elige la tercera:

| Opción | A favor | En contra |
|---|---|---|
| A. JLaTeXMath en desktop + fallback en Android | Fidelidad tipográfica máxima en el escritorio | **Dos renderizadores**: el alumno ve una cosa en el escritorio y otra en el teléfono; lo que copia y lo que el docente analiza cambia según el dispositivo |
| B. Sólo el fallback monoespaciado (nunca renderizar) | Cero riesgo, cero trabajo | Se pierde la matemática, que en una cátedra de cálculo/IA es contenido central |
| C. **Un renderer en Compose puro, igual en las dos plataformas** | Una sola verdad; portable; texto seleccionable; accesible (TalkBack lee la fórmula) | Hay que escribir el parser y el layout (unas 400 líneas) |

**Se elige C.** El renderer **no** dibuja un bitmap: produce una lista de
primitivas de dibujo que se pintan con `Text` de Compose. Ventajas: el texto
sigue siendo **seleccionable y copiable** (con un bitmap no lo es), y
`contentDescription` puede ser el LaTeX original para lectores de pantalla.

**Fallback**: si el renderer no puede parsear la fórmula (LaTeX inválido o fuera
del subconjunto soportado) → se muestra **el LaTeX crudo en monoespaciado**,
exactamente el comportamiento que ya tiene FunshiEngineAI cuando
`MathRender.render` devuelve `null`. **Nunca se pierde información.**

### 4.4 API del renderer

```kotlin
// ui/commonMain/kotlin/kitoru/ui/markdown/MathRenderer.kt
package kitoru.ui.markdown

/** Estilo de un glifo (la cursiva es la convención de LaTeX para variables). */
enum class GlyphStyle { ITALIC, ROMAN, BOLD }

/** Una primitiva de dibujo: un fragmento de texto en una posición. */
data class MathGlyph(
    val text: String,
    val xPx: Float,
    val yPx: Float,
    val sizePx: Float,
    val style: GlyphStyle,
)

/** Una regla (línea):分数线 de una fracción, barra de una raíz. */
data class MathRule(
    val x1Px: Float, val y1Px: Float,
    val x2Px: Float, val y2Px: Float,
    val thicknessPx: Float,
)

/** Resultado del layout de una fórmula. */
data class MathLayout(
    val widthPx: Float,
    val heightPx: Float,
    /** Distancia de la línea base respecto del borde superior. */
    val baselinePx: Float,
    val glyphs: List<MathGlyph> = emptyList(),
    val rules: List<MathRule> = emptyList(),
)

/**
 * Mide texto para el layout de las fórmulas.
 *
 * Se abstrae para que el renderer sea **testeable sin Compose**: los tests
 * pasan un fake de anchos fijos.
 */
fun interface MathTextMeasurer {
    fun width(text: String, sizePx: Float, style: GlyphStyle): Float
}

/** Convierte una fórmula LaTeX en primitivas de dibujo. */
interface MathRenderer {
    /**
     * null si la fórmula no se puede parsear o usa algo fuera del subconjunto
     * soportado: en ese caso la UI muestra el LaTeX en monoespaciado.
     */
    fun render(latex: String, display: Boolean, sizePx: Float, measurer: MathTextMeasurer): MathLayout?
}

/**
 * Renderer por defecto: el de Compose puro.
 *
 * Es **único** para las dos plataformas (decisión D-3). Se puede apagar con
 * `MathRendering.enabled = false` (banderín de hito 1) para que todo caiga al
 * fallback monoespaciado sin tocar la UI.
 */
val mathRenderer: MathRenderer = ComposeMathRenderer()

/**
 * Mide texto con las métricas de Compose.
 *
 * Envuelve el `TextMeasurer` de Compose (`rememberTextMeasurer()`, que existe
 * en el `commonMain` de `compose-ui`) para que el renderer no dependa de la
 * API de Compose y pueda testearse con un fake.
 */
@Composable
fun rememberMathTextMeasurer(): MathTextMeasurer {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) {
        MathTextMeasurer { text, sizePx, style ->
            val result = measurer.measure(
                text = text,
                style = TextStyle(
                    fontSize = with(density) { (sizePx / density.density).sp },
                    fontStyle = if (style == GlyphStyle.ITALIC) FontStyle.Italic else FontStyle.Normal,
                    fontWeight = if (style == GlyphStyle.BOLD) FontWeight.Bold else FontWeight.Normal,
                ),
                density = density,
                layoutDirection = LayoutDirection.Ltr,
                maxLines = 1,
            )
            result.size.width.toFloat()
        }
    }
}
```

### 4.5 Subconjunto de LaTeX soportado (v1)

| Grupo | Soportado | Notas |
|---|---|---|
| Fracciones | `\frac{a}{b}`, `\dfrac`, `\tfrac` | Anidamiento de un nivel |
| Scripts | `x^2`, `x_i`, `x_i^2`, `^{...}`, `_{...}` | Un nivel de anidamiento |
| Raíces | `\sqrt{x}`, `\sqrt[n]{x}` | |
| Operadores grandes | `\sum`, `\prod`, `\int`, `\oint` | Con `\limits` (límites arriba/abajo) y sin él (al lado) |
| Griegas | `\alpha` … `\omega`, `\Gamma`, `\Delta`, `\Theta`, `\Lambda`, `\Xi`, `\Pi`, `\Sigma`, `\Phi`, `\Psi`, `\Omega` | |
| Símbolos | `\cdot \times \div \pm \mp \leq \geq \neq \approx \infty \partial \nabla \to \rightarrow \Rightarrow \in \notin \subset \subseteq \cup \cap \forall \exists \neg \land \lor \ldots \cdots \dots \ldots` | |
| Delimitadores | `( ) [ ] \{ \} \|` y `\left…\right` | Sin ajuste de tamaño automático |
| Texto | `\text{...}`, `\mathrm{...}`, `\mathbf{...}` | |
| Blackboards | `\mathbb{R}` | Con la fuente serif; si no está, cae al texto plano |
| Funciones | `\sin \cos \tan \log \ln \exp \lim` | Romanas (no cursivas) |
| **NO** soportado (→ fallback monoespaciado) | `pmatrix`/`bmatrix`, `align`, `cases`, `\left` con altura automática, `\text` con comandos dentro, `\color`, `\href` | |

### 4.6 Cómo se pinta

`ParagraphText` conserva la estructura de FunshiEngineAI (el texto con las
fórmulas "enchufadas" como contenido en línea), pero en lugar de un `Image`
con `Placeholder`, usa la lista de primitivas:

```kotlin
@Composable
private fun ParagraphText(text: String, withCitations: Boolean, onCitationClick: (Int) -> Unit) {
    val segments = remember(text) { splitMath(text) }
    val hasMath = segments.any { it is MathSegment.Formula }
    if (!hasMath) {
        Text(
            text = buildInlineAnnotated(text, citationStyle = citationSpanStyle(withCitations)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            onTextLayout = { /* para el click en [n] */ },
        )
        return
    }

    // Hay fórmulas: se renderizan y se enchufan dentro del texto.
    val onSurface = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val measurer = rememberMathTextMeasurer()
    val rendered = remember(text, onSurface, density) {
        segments.map { segment ->
            if (segment is MathSegment.Formula) {
                mathRenderer.render(segment.latex, segment.display, with(density) { 20.dp.toPx() }, measurer)
            } else null
        }
    }
    val annotated = remember(text, rendered) { buildAnnotatedWithMath(segments, rendered) }
    val inlineContent = remember(rendered, density) {
        rendered.mapIndexedNotNull { index, layout ->
            if (layout == null) return@mapIndexedNotNull null
            // El id es el índice del SEGMENTO (no del render), así dos
            // fórmulas idénticas no chocan.
            val id = "math$index"
            id to InlineTextContent(
                placeholder = Placeholder(
                    width = with(density) { layout.widthPx.toDp().toSp() },
                    height = with(density) { layout.heightPx.toDp().toSp() },
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                ),
                children = { MathGlyphs(layout, onSurface) },
            )
        }.toMap()
    }

    Text(text = annotated, inlineContent = inlineContent, style = MaterialTheme.typography.bodyMedium, color = onSurface)
}

/** Pinta la fórmula: cada glifo es un `Text` posicionado (100 % portable). */
@Composable
private fun MathGlyphs(layout: MathLayout, color: Color) {
    Box(Modifier.fillMaxSize()) {
        layout.rules.forEach { r ->
            Canvas(Modifier.fillMaxSize()) {
                drawLine(color, Offset(r.x1Px, r.y1Px), Offset(r.x2Px, r.y2Px), r.thicknessPx)
            }
        }
        layout.glyphs.forEach { g ->
            Text(
                text = g.text,
                color = color,
                fontSize = with(LocalDensity.current) { (g.sizePx / LocalDensity.current.density).sp },
                fontStyle = if (g.style == GlyphStyle.ITALIC) FontStyle.Italic else FontStyle.Normal,
                fontWeight = if (g.style == GlyphStyle.BOLD) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.offset { IntOffset(g.xPx.roundToInt(), g.yPx.roundToInt()) },
            )
        }
    }
}
```

> Por qué `Text` posicionados y no `drawText`: `DrawScope.drawText` es
> `ExperimentalTextApi` en Compose 1.7 y no existe en todas las versiones de
> CMP; el `Box` + `Modifier.offset` + `Text` usa **sólo** API estable y
> multiplatform. Es un poco más de nodos, pero una fórmula tiene 10–40 glifos y
> se repinta una vez por token como máximo.

`MathBlock` (fórmula centrada) es el mismo `Box` con `contentAlignment = Center`.

### 4.7 Código: `CodeBlock`

`app/ui/CodeBlock.kt` es Compose puro **salvo** dos cosas:

| Qué | En FunshiEngineAI | En Kitoru |
|---|---|---|
| Resaltado | `highlightCode`/`tokenize` (puro Kotlin) | **Port literal** a `CodeHighlighter.kt` |
| Copiar | `java.awt.Toolkit` + `StringSelection` | **`LocalClipboardManager`** (común, D-4) |
| Guardar como… | `javax.swing.JFileChooser` + `Files.writeString` | **Seam `expect/actual`** (D-5, §5.3) |

```kotlin
// ui/commonMain/kotlin/kitoru/ui/markdown/CodeBlock.kt
@Composable
fun CodeBlock(
    lang: String,
    code: String,
    closed: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val ext = LANG_TO_EXT[lang.lowercase()] ?: "txt"
    val primaryColor = MaterialTheme.colorScheme.primary
    // ... (los 8 colores del tema, igual que en FunshiEngineAI) ...
    val highlighted = remember(code, lang, primaryColor, ...) {
        highlightCode(code, lang.lowercase(), primaryColor, onSurfaceVariant, onSurface, tertiary, secondary)
    }
    val clipboard = LocalClipboardManager.current
    val saver = rememberFileActions()
    var copied by remember { mutableStateOf(false) }
    var savedName by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth().background(surfaceContainerHighest).clip(RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (closed) lang.uppercase() else "$lang (en curso)", style = MaterialTheme.typography.labelMedium, color = onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true }) {
                    Icon(if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy, "Copiar")
                }
                IconButton(onClick = {
                    scope.launch {
                        val saved = saver.save(SaveFileRequest("bloque.$ext", "text/plain", code.encodeToByteArray()))
                        savedName = saved?.displayName
                    }
                }) {
                    Icon(Icons.Filled.Save, "Guardar como…")
                }
            }
        }
        Text(highlighted, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp), modifier = Modifier.fillMaxWidth().padding(12.dp))
        // banners "Copiado" / "Guardado en …" (igual que FunshiEngineAI)
    }
}
```

El bloque **en curso** (sin cerrar el ```) se rotula `"(en curso)"`: durante el
streaming el modelo puede haber abierto un bloque de código y todavía no lo
cerró; mostrarlo como código (sin highlight final) es mejor que mostrar los
backticks crudos.

---

## 5. Seams de plataforma

### 5.1 Portapapeles: no hace falta seam

`androidx.compose.ui.platform.LocalClipboardManager` existe en el
`commonMain` de `compose-ui` y tiene implementación real en **desktop**
(Swing) y en **Android**. Por lo tanto:

- `MessageBubble` (Copiar) → `LocalClipboardManager.current.setText(AnnotatedString(m.text))`.
- `CodeBlock` (Copiar) → idem.

No hay `expect/actual` para esto (D-4). La diferencia con FunshiEngineAI es
que `CodeBlock.kt` usaba `java.awt.Toolkit` "a pelo"; en Kitoru se usa la API
común.

### 5.2 Acciones sobre archivos: `FileActions`

```kotlin
// ui/commonMain/kotlin/kitoru/ui/platform/FileActions.kt
package kitoru.ui.platform

import androidx.compose.runtime.Composable

/** Una petición de "guardar este contenido como archivo". */
data class SaveFileRequest(
    /** Nombre sugerido, con extensión ("informe.pdf"). */
    val suggestedName: String,
    /** Tipo MIME ("text/plain", "application/pdf"). */
    val mimeType: String,
    /** Contenido a escribir. */
    val bytes: ByteArray,
) {
    // data class con ByteArray: equals/hashCode por referencia; no importa,
    // pero se deja el aviso por si se usa en un remember.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Resultado de guardar. */
data class SavedFile(val path: String, val displayName: String)

/**
 * Acciones sobre archivos que dependen de la plataforma.
 *
 * Es el **único** punto del chat que necesita saber en qué plataforma está:
 * abrir un archivo, guardarlo o compartirlo.
 */
interface FileActions {
    /**
     * Pide un destino y escribe los bytes.
     * Devuelve `null` si el usuario canceló.
     */
    suspend fun save(request: SaveFileRequest): SavedFile?

    /** Abre el archivo con la app del sistema. `false` si no se pudo. */
    fun open(path: String): Boolean

    /** Comparte el archivo con otra app (hoja de compartir del sistema). */
    suspend fun share(path: String, mimeType: String): Boolean
}

/**
 * Lanzador de acciones de archivo.
 *
 * Es un `expect @Composable` (y no un `expect fun`) porque **Android** necesita
 * registrar un `ActivityResultLauncher` (`ActivityResultContracts.CreateDocument`),
 * y eso sólo se puede hacer dentro de la composición.
 */
@Composable
expect fun rememberFileActions(): FileActions
```

### 5.3 Desktop (`desktopMain`)

```kotlin
// ui/desktopMain/kotlin/kitoru/ui/platform/FileActions.desktop.kt
package kitoru.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

class DesktopFileActions : FileActions {

    override suspend fun save(request: SaveFileRequest): SavedFile? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply {
            dialogTitle = "Guardar ${request.suggestedName}"
            selectedFile = File(request.suggestedName)
        }
        val chosen = chooser.showSaveDialog(null) ?: return@withContext null
        val file = chosen
        runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(request.bytes)
        }.onFailure { return@withContext null }
        SavedFile(file.absolutePath, file.name)
    }

    override fun open(path: String): Boolean = runCatching {
        val file = File(path)
        if (!file.exists()) return false
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        if (desktop != null && desktop.isSupported(Desktop.Action.OPEN)) { desktop.open(file); true } else false
    }.getOrDefault(false)

    override suspend fun share(path: String, mimeType: String): Boolean {
        // En escritorio no hay hoja de compartir del sistema: se copia la ruta
        // al portapapeles y se devuelve true (la UI avisa "ruta copiada").
        val file = File(path)
        if (!file.exists()) return false
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(java.awt.datatransfer.StringSelection(file.absolutePath), null)
        true
    }
}

@Composable
actual fun rememberFileActions(): FileActions = remember { DesktopFileActions() }
```

> `Dispatchers.Swing` + `showSaveDialog` bloquea **el EDT**, que en Compose
> Desktop **es** el hilo de la UI. El diálogo es modal, así que la ventana no
> recibe entrada mientras está abierto; el `withContext` no devuelve hasta que
> el usuario elige o cancela, y la UI sigue "viva" (el modal corre su propio
> loop de eventos).

### 5.4 Android (`androidMain`)

```kotlin
// ui/androidMain/kotlin/kitoru/ui/platform/FileActions.android.kt
package kitoru.ui.platform

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.io.File

class AndroidFileActions(
    private val context: android.content.Context,
    private val writeBytes: suspend (android.net.Uri, ByteArray) -> Unit,
    private val pendingSave: CompletableDeferred<SavedFile?>,
) : FileActions {

    override suspend fun save(request: SaveFileRequest): SavedFile? {
        val deferred = CompletableDeferred<SavedFile?>()
        // El launcher se dispara desde la UI; se resuelve el deferred.
        saveRequests.trySend(request to deferred)
        return deferred.await()
    }
    ...
}
```

La versión real separa la parte "pide el URI" (que necesita el launcher, en
composición) de la parte "escribo los bytes" (que necesita el `ContentResolver`).
La forma final, sin ambigüedad:

```kotlin
@Composable
actual fun rememberFileActions(): FileActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { mutableStateOf<Pair<SaveFileRequest, CompletableDeferred<SavedFile?>>?>(null) }

    // SAF: el usuario elige el destino en el explorador de archivos del sistema.
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val job = pending.value
        pending.value = null
        if (uri == null) { job?.second?.complete(null); return@rememberLauncherForActivityResult }
        scope.launch {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(job!!.first.bytes) }
            }.isSuccess
            job?.second?.complete(if (ok) SavedFile(uri.toString(), job.first.suggestedName) else null)
        }
    }

    return remember(context, launcher) {
        object : FileActions {
            override suspend fun save(request: SaveFileRequest): SavedFile? {
                val deferred = CompletableDeferred<SavedFile?>()
                pending.value = request to deferred
                launcher.launch(request.suggestedName)
                return deferred.await()
            }

            override fun open(path: String): Boolean = runCatching {
                val file = File(path)
                if (!file.exists()) return false
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
                true
            }.getOrDefault(false)

            override suspend fun share(path: String, mimeType: String): Boolean = runCatching {
                val file = File(path)
                if (!file.exists()) return false
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Compartir archivo"))
                true
            }.getOrDefault(false)
        }
    }
}
```

**Manifiesto Android** (en el módulo `:android`):

```xml
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
<!-- res/xml/file_paths.xml -->
<paths>
    <files-path name="salidas" path="salidas/" />
    <cache-path name="cache" path="." />
</paths>
```

> `FileProvider` es necesario porque `FileProvider.getUriForFile` lanza
> `IllegalArgumentException` si el archivo está fuera de los `<paths>`
> declarados. Los archivos del modelo (`create_file`) van a
> `KitoruPaths.dataDir/salidas/<thread-id>/`, que es `files-path`.

### 5.5 Resumen de seams

| Seam | Desktop | Android |
|---|---|---|
| Portapapeles | `LocalClipboardManager` (común) | idem |
| Guardar como… | `JFileChooser` (modal, en el EDT) | SAF `CreateDocument` |
| Abrir archivo | `Desktop.open` | `ACTION_VIEW` + `FileProvider` |
| Compartir | copia la ruta al portapapeles | `ACTION_SEND` + `FileProvider` |
| Scrollbar | `ThemedVerticalScrollbar` | no se muestra |

---

## 6. Fuentes citadas

### 6.1 El distintivo (`CitationBadge`)

**Ya está diseñado** en `teacher/04-prompt.md` §6.4: el composable vive en
`ui/commonMain/kotlin/kitoru/ui/components/CitationBadge.kt` y recibe
`kitoru.domain.CitationStats`. **No se duplica**: el chat del alumno y la
pantalla de pruebas del docente usan el mismo componente, así el "Citas
verificadas (3)" del chat y el del editor no pueden discrepar.

```kotlin
@Composable
fun CitationBadge(stats: CitationStats, modifier: Modifier = Modifier)
```

En el chat del alumno se muestra **al pie de la burbuja del asistente**, tal
como en `MessageBubble.kt` de FunshiEngineAI.

### 6.2 Las fuentes: chips debajo del mensaje

**Decisión D-6**: no hay hoja lateral. Las fuentes se muestran como una fila de
**chips** debajo del texto de la respuesta (una por fuente), y al tocar un chip
se abre el detalle.

```
┌ respuesta del modelo ─────────────────────────────┐
│  …texto con citas [1] y [2] en línea…              │
│                                                   │
│  [1] Teoría de Tensores · 2.1 Bases               │  ← FlowRow de chips
│  [2] Introducción a los tensores                  │
│  ─────────────────────────────────────────────     │
│  ✔ Citas verificadas (4)                          │  ← CitationBadge
└───────────────────────────────────────────────────┘
```

El chip muestra `[n]` + el **label** de la fuente, truncado a una línea con
`TextOverflow.Ellipsis`. El label viene de `rag.retrieval.Chunk.label`
("`source: path`"); en Kitoru se arma como `"<título del material> · <sección>"`.

### 6.3 La hoja de fuentes

Un único composable `SourcesPanel`, presentado como `Dialog` en escritorio y
como `ModalBottomSheet` en Android:

```
┌ Fuentes de la respuesta ────────────────────────────────┐
│ [1]  Teoría de Tensores.pdf                              │
│      Material: Teoría de Tensores.pdf                     │
│      Sección: 2.1 Bases                                  │
│      ┌────────────────────────────────────────────────┐  │
│      │ El tensor de deformación se define como el      │  │
│      │ producto simétrico de los gradientes…           │  │
│      └────────────────────────────────────────────────┘  │
│                                    [ Abrir material ]    │
│                                                        │
│ [2]  Introducción a los tensores                         │
│      …                                                 │
└────────────────────────────────────────────────────────┘
```

- El **fragmento** (el texto del chunk) sólo está en memoria durante la sesión
  (§6.5); si no está, se muestra *"Fragmento no disponible en esta sesión"*.
- "Abrir material" llama a `FileActions.open(path)`.
- En Android el `ModalBottomSheet` ocupa el 60 % de la altura y es deslizable.

### 6.4 Citas en línea (`[n]`)

`buildInlineAnnotated` se porta literal, pero con un parámetro opcional:

```kotlin
/**
 * Texto inline con formato: **negrita**, __negrita__, ~~tachado~~, `código`,
 * *cursiva*, [enlace](url) y — opcionalmente — citas [n].
 *
 * PORT de `app/ui/Markdown.kt` de FunshiEngineAI. La única diferencia es el
 * parámetro [citationStyle]: si no es null, los "[n]" se marcan con ese estilo
 * y se les pega una anotación "source" para que la UI los pueda hacer
 * clicables. Con `citationStyle = null` el comportamiento es idéntico al de
 * FunshiEngineAI.
 */
fun buildInlineAnnotated(
    inline: String,
    base: SpanStyle = SpanStyle(),
    citationStyle: SpanStyle? = null,
): AnnotatedString
```

El regex pasa a ser:

```kotlin
val INLINE = Regex(
    """\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|`([^`]+)`|\*([^*\s][^*]*)\*|\[([^\]]+)\]\(([^)]+)\)|\[(\d{1,2})\]"""
)
```

**Orden de las alternativas**: la de enlace (`[texto](url)`) va **antes** que
la de cita (`[1]`), así `[1](http://x)` sigue siendo un enlace. La rama de
cita sólo matchea dígitos.

Con `citationStyle != null`, la rama 8 hace:

```kotlin
m.groupValues[8].isNotEmpty() -> {
    builder.withStyle(citationStyle) { append(m.groupValues[8]) }
    builder.pushStringAnnotation("source", m.groupValues[8])
}
```

Y en `ParagraphText` se captura el clic:

```kotlin
Text(
    text = annotated,
    onTextLayout = { layout -> /* se registra el rango de la anotación */ },
)
```

Composición: el `Text` se envuelve en un `Modifier.pointerInput` que usa
`layoutResult.getOffsetForPosition` y busca la anotación "source" más cercana
para llamar a `onCitationClick(n)` → abre la hoja de fuentes con esa fuente
resaltada.

> **Alcance**: el resaltado de la cita seleccionada en la hoja es del hito 2.
> En el hito 1, tocar `[n]` abre la hoja con el scroll en esa fuente.

### 6.5 Persistencia de las fuentes

`kitoru.domain.ChatMessage` ya tiene `sources: List<String>` (los labels, que
sirven para la analítica). Para poder abrir la fuente y mostrar su sección hace
falta más, así que se **agrega** (campo nuevo con valor por defecto, sin tocar
los JSON ya guardados):

```kotlin
// core/commonMain/kotlin/kitoru/domain/SourceRef.kt
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Referencia a una fuente usada por una respuesta.
 *
 * Lo que se persiste es **lo mínimo para volver a abrir el material**: el
 * fragmento de texto (hasta 1200 caracteres por fuente, 5 fuentes por
 * respuesta) NO se guarda en el hilo — vive en memoria durante la sesión y se
 * vuelve a pedir al índice si hace falta.
 */
@Serializable
data class SourceRef(
    /** Número de la cita tal como aparece en la respuesta: [1], [2]… */
    val index: Int,
    /** Etiqueta legible ("Teoría de Tensores.pdf: 2.1 Bases"). */
    val label: String = "",
    /** Id del material en la cátedra ("" si el material ya no existe). */
    val materialId: String = "",
    /** Título del material. */
    val materialTitle: String = "",
    /** Sección / encabezado del fragmento. */
    val heading: String = "",
    /** Ruta del material en disco, para el botón "Abrir material". */
    val path: String = "",
)
```

Y en `ChatMessage`:

```kotlin
    /** Referencias a las fuentes (las "sources" de arriba son sólo los labels). */
    val sourceRefs: List<SourceRef> = emptyList(),
```

El `ViewModel` mantiene, **en memoria**, los fragmentos de la sesión:

```kotlin
/** Fragmentos de las fuentes por mensaje (sólo en memoria: no se persisten). */
private val snippets = mutableMapOf<String, List<Chunk>>()   // messageId -> chunks
```

---

## 7. Herramientas: tarjetas y permisos

### 7.1 `ToolCallCard`

Port de `app/chat/ToolCallCard.kt`, con `kitoru.domain.ToolCallStats`:

```kotlin
@Composable
fun ToolCallCard(call: ToolCallStats, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, modifier = modifier.padding(bottom = 6.dp).fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Herramienta: ${call.name}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (call.durationMs > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text("${call.durationMs} ms", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (call.args.isNotBlank() && call.args != "{}") {
                Text(call.args, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
            when (call.ok) {
                null -> Text("Ejecutando…", fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)
                true -> {
                    Text("OK", fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)
                    if (call.result.isNotBlank()) {
                        Text(call.result, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6)
                    }
                }
                false -> Text("Falló", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
```

Donde se muestra: **dentro de la burbuja del asistente, antes del texto**
(igual que en `MessageBubble.kt` de FunshiEngineAI), para que se vea que la
respuesta se apoyó en una herramienta.

### 7.2 `AttachmentCard` (el `create_file`)

El modelo puede pedir `create_file`; el `AgentEvent.ToolEnd` trae los
`artifacts` (nombre, ruta, tamaño, mime). Se muestran como tarjetas:

```
┌────────────────────────────────────────────────────────┐
│ 📄  informe.pdf                        12,4 KB · pdf    │
│                              [ Abrir ]  [ Guardar ]  [ ⋮ ]│
└────────────────────────────────────────────────────────┘
```

- **Abrir** → `FileActions.open(path)`.
- **Guardar** → `FileActions.save(...)` (copia a otro lugar).
- **⋮** → "Compartir" (`FileActions.share`) — en escritorio, "copiar la ruta".

Hace falta un campo nuevo en el dominio (`:core`):

```kotlin
// core/commonMain/kotlin/kitoru/domain/ChatAttachment.kt
@Serializable
data class ChatAttachment(
    /** Nombre del archivo. */
    val name: String,
    /** Ruta del archivo (en `dataDir/salidas/<thread-id>/`). */
    val path: String,
    val sizeBytes: Long = 0,
    val mime: String = "",
)
```

y en `ChatMessage`: `val attachments: List<ChatAttachment> = emptyList()`.

> En Android, `FileProvider` sólo expone `files-path/salidas/`; por eso
> `CreateFileTool` **tiene** que escribir en `KitoruPaths.dataDir/salidas/<chatId>/`
> (que es lo que ya hace `ToolContext.outputsDir`). Si el modelo pidiera
> escribir fuera de ahí, el "Abrir" en Android fallaría.

### 7.3 El diálogo de permiso

Port de `app/chat/PermissionDialog.kt`, con el texto del diálogo **en español
de Argentina** y el detalle de la categoría:

```kotlin
// ui/commonMain/kotlin/kitoru/ui/chat/PermissionDialog.kt
@Composable
fun PermissionDialog(vm: StudentChatViewModel) {
    val req: PermissionRequest = vm.permissionRequest ?: return
    AlertDialog(
        onDismissRequest = { vm.answerPermission(PermissionDecision.DENY) },
        title = { Text("Permiso: ${req.category.label}") },
        text = {
            Column {
                Text("El modelo quiere usar la herramienta `${req.tool}`.")
                Spacer(Modifier.height(8.dp))
                Text(req.summary, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text(categoryExplanation(req.category), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (req.argsJson.isNotBlank() && req.argsJson != "{}") {
                    Spacer(Modifier.height(8.dp))
                    Text(req.argsJson, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.answerPermission(PermissionDecision.ALLOW_ALWAYS) }) { Text("Permitir siempre") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.answerPermission(PermissionDecision.ALLOW_ONCE) }) { Text("Una vez") }
                TextButton(onClick = { vm.answerPermission(PermissionDecision.DENY) }) { Text("Cancelar") }
            }
        },
    )
}

/** Qué va a pasar si se concede: se dice antes de conceder. */
private fun categoryExplanation(category: ToolCategory): String = when (category) {
    ToolCategory.NONE -> "Esta herramienta no tiene efecto externo."
    ToolCategory.FILES -> "Se guardará en la carpeta del chat, en tu dispositivo. Podés abrirlo y borrarlo cuando quieras."
    ToolCategory.WEB -> "El modelo va a consultar una página web y usar lo que encuentre."
    ToolCategory.MCP -> "El modelo va a usar un servicio externo de la cátedra."
}
```

> El diálogo **no** aparece en Android si el chat está en segundo plano: si el
> alumno no está mirando la pantalla, el permiso se **niega** por defecto
> (`DENY`), con un mensaje en la burbuja: *"El modelo pidió permiso para crear
> un archivo y no se lo diste. Si querés, contestale y autorizalo."*. Es la
> opción fail-safe y además coincide con `AgentLoop`, que corta el bucle tras
> dos denegaciones.

### 7.4 El techo de la cátedra (control del docente)

Por `domain/08-permisos.md` §6.2, el permiso del alumno vive **dentro** del
techo que fija la cátedra. En el chat del alumno eso se traduce en tres
estados visibles:

| Estado | Cómo se ve en el chat | Qué hace el modelo |
|---|---|---|
| La cátedra habilitó la categoría y el alumno todavía no concedió | El chip de la barra: "Archivos: se pide permiso" | El modelo pide; aparece el diálogo |
| El alumno concedió ("Permitir siempre") | El chip: "Archivos: permitidos" | Se ejecuta sin preguntar |
| **La cátedra apagó la categoría** | El chip: "Archivos: desactivados por la cátedra" (con candado) y **no aparece diálogo** | La herramienta **no está en el catálogo** que ve el modelo; si la pide igual, se deniega sin preguntar y la tarjeta muestra *"La cátedra desactivó esta herramienta"* |
| La cátedra no permite que el alumno conceda (`allowStudentGrant = false`) | El chip: "Archivos: sólo la cátedra los autoriza" | Se deniega sin preguntar |

El chip de permisos vive en la **fila del modo** del redactor (desktop) o en el
menú ⋮ (Android):

```
[● Usar el material] [Preguntas libres]        🔒 Archivos: desactivados por la cátedra
```

Implementación: el `ToolContext` se arma con `allowedCategories = toolCeiling`
(calculado por `PermissionResolver` / la `CourseToolPolicy`) y con
`grantedCategories` (las concedidas por el alumno en este chat). `AgentLoop`
ya llama a `ToolPermissions.authorize`, que **ya** devuelve `DENY` sin preguntar
si la categoría está fuera del techo (cambio propuesto en `08-permisos.md`
§6.4). El chat sólo tiene que reflejar ese estado en la UI.

---

## 8. El redactor

### 8.1 Estructura

```kotlin
// ui/commonMain/kotlin/kitoru/ui/chat/ComposerBar.kt
@Composable
fun ComposerBar(vm: StudentChatViewModel, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    fun submit() {
        vm.send(vm.composerInput)
        vm.setComposerInput("")
        // El foco se queda en el redactor: si el modelo está respondiendo, el
        // mensaje se encola y se puede seguir escribiendo.
        if (vm.ready) focusRequester.requestFocus()
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(Modifier.fillMaxWidth()) {
            // Fila 1: modo + permisos
            ComposerModeRow(vm)

            // Fila 2: campo + botón
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = vm.composerInput,
                    onValueChange = vm::setComposerInput,
                    modifier = Modifier.weight(1f)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { e ->
                            // ENTER ENVÍA (escritorio y teclado físico en Android).
                            if (e.key == Key.Enter && e.type == KeyEventType.KeyDown && !e.isShiftPressed) {
                                submit(); true
                            } else false
                        },
                    placeholder = { Text(composerPlaceholder(vm)) },
                    // En Android el teclado blando NO genera KeyEvent: el envío
                    // llega por la acción IME.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    enabled = vm.canSend,
                    maxLines = 5,
                )
                Spacer(Modifier.width(4.dp))
                when {
                    vm.busy -> Button(onClick = vm::cancel) { Text("Detener") }
                    vm.queuedMessages.isNotEmpty() -> Button(onClick = vm::cancel) { Text("Cancelar envío") }
                    else -> Button(onClick = ::submit, enabled = vm.canSend && vm.composerInput.isNotBlank()) {
                        Text("Enviar")
                    }
                }
            }

            // Fila 3: cola (sólo si hay mensajes encolados)
            if (vm.queuedMessages.isNotEmpty()) QueuedMessagesBar(vm)
        }
    }
}
```

### 8.2 Placeholder

```kotlin
private fun composerPlaceholder(vm: StudentChatViewModel): String = when {
    !vm.ready -> "Preparando el material de la cátedra…"
    vm.busy -> "Escribí tu mensaje… (se encolará hasta que termine la respuesta)"
    vm.useCourseMaterial -> "Escribí tu pregunta sobre el material de la cátedra…"
    else -> "Escribí tu pregunta… (sin material de la cátedra)"
}
```

### 8.3 Envío con Enter

| Plataforma | Mecanismo | Shift+Enter |
|---|---|---|
| Escritorio | `onPreviewKeyEvent` + `Key.Enter` | Salto de línea (el `!e.isShiftPressed` lo permite) |
| Android (teclado blando) | `KeyboardActions(onSend = …)` con `imeAction = Send` | No aplica: el botón "Enviar" del teclado manda |
| Android (teclado físico) | `onPreviewKeyEvent` (Android también entrega `KeyEvent` de teclado físico) | Salto de línea |

> `onPreviewKeyEvent` **no** se dispara con el teclado blando de Android: por eso
> el `KeyboardActions` no es opcional. Es el error más común al portar un chat
> de escritorio a Android.

### 8.4 Cola de mensajes

Port de `ComposerBar.kt` (FunshiEngineAI): los mensajes escritos mientras el
modelo responde se **encolan** y se mandan **juntos** al terminar.

```kotlin
@Composable
private fun QueuedMessagesBar(vm: StudentChatViewModel) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${vm.queuedMessages.size} en cola — se enviarán juntos al terminar la respuesta",
                Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = vm::clearQueue) { Text("Descartar todo") }
        }
        vm.queuedMessages.forEachIndexed { i, q ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(q, Modifier.weight(1f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { vm.removeQueued(i) }) { Text("Quitar") }
            }
        }
    }
}
```

En **Android** la cola se muestra **plegada**: una sola línea
("2 en cola ▾") que al tocarse despliega la lista, porque en pantalla angosta
la cola abierta empuja el redactor fuera de la vista.

```kotlin
@Composable
private fun QueuedMessagesBar(vm: StudentChatViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${vm.queuedMessages.size} en cola", Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar" else "Ver") }
            TextButton(onClick = vm::clearQueue) { Text("Descartar") }
        }
        if (expanded) { /* la lista de FunshiEngineAI */ }
    }
}
```

`flushSendQueue()` (en el VM) une los encolados con `"\n\n"` y los manda como
**un solo mensaje**, igual que en FunshiEngineAI.

### 8.5 Botón "Detener"

`vm.cancel()` (§3.7): cancela el job, descarta la cola, cierra el diálogo de
permiso como DENY y deja el texto parcial con el sufijo "(detenido)".

### 8.6 El modo: "usar el material" vs "preguntar libre"

**Decisión D-9: el default es "usar el material de la cátedra"** (en
FunshiEngineAI el default es "General").

El toggle va en el **redactor** (D-8), no en la barra superior:

```kotlin
@Composable
private fun ComposerModeRow(vm: StudentChatViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ModeChip("Usar el material", selected = vm.useCourseMaterial) { vm.setUseCourseMaterial(true) }
            ModeChip("Preguntar libre", selected = !vm.useCourseMaterial) { vm.setUseCourseMaterial(false) }
        }
        Spacer(Modifier.width(8.dp))
        ToolPermissionChip(vm)
    }
}
```

Y el modo se explica en una línea, porque es la diferencia entre una respuesta
**fundamentada** y una respuesta de conocimiento general:

| Modo | Explicación visible | Efecto técnico |
|---|---|---|
| **Usar el material** (default) | "Las respuestas se basarán en el material de la cátedra y citarán las fuentes." | `engine.prepare(q, h, useDocs = true)` → `Chunk`s del `KnowledgeBase` + `CitationVerifier` activo + chips de fuentes + `CitationBadge` |
| **Preguntar libre** | "El modelo responde con su conocimiento general. No habrá citas ni fuentes." | `engine.prepare(q, h, useDocs = false)` → `GENERAL_PROMPT` + sin verificación + sin fuentes |

**El modo queda registrado por mensaje**: se agrega a `ChatMessage`:

```kotlin
    /** Modo con el que se generó la respuesta: "MATERIAL" | "LIBRE". */
    val mode: String = "MATERIAL",
```

Esto es lo que le permite al docente ver, en la analítica, cuántas respuestas
fueron fundadas en el material y cuántas no.

**El material vacío**: si la cátedra no tiene material indexado, el chip "Usar
el material" aparece **deshabilitado** con el motivo *"La cátedra todavía no
subió material."* y el modo efectivo es "libre".

### 8.7 Estados del botón

| Estado | Botón | Campo |
|---|---|---|
| Listo, texto vacío | "Enviar" deshabilitado | Habilitado |
| Listo, con texto | "Enviar" | Habilitado |
| Respondiendo | **"Detener"** | Habilitado (lo que se escribe se encola) |
| Con cola y sin respuesta en curso | "Cancelar envío" | Habilitado |
| Sin red / key vencida | "Enviar" deshabilitado | **Deshabilitado** (`vm.canSend == false`) |

---

## 9. Estado degradado

Cada estado degradado es un **banner** sobre la lista de mensajes (no un
diálogo: el chat tiene que seguir legible) más, cuando corresponde, una acción.

```kotlin
// ui/commonMain/kotlin/kitoru/ui/chat/ChatBanner.kt
package kitoru.ui.chat

/** Un aviso persistente sobre el chat. */
data class ChatBanner(
    val level: Level,              // INFO | WARNING | ERROR
    val title: String,
    val detail: String = "",
    /** Acción principal ("Reintentar", "Usar IA local"). */
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val dismissible: Boolean = true,
)
```

### 9.1 Tabla de estados

| Situación | `LlmErrorCode` | Banner | Acciones | `canSend` |
|---|---|---|---|---|
| **Sin red** (servidor de la cátedra caído) | `SERVIDOR_CAIDO` / `MODELO_NO_DISPONIBLE` | ⚠ **"No se pudo conectar con el servidor de la cátedra."** — *"Revisá tu conexión a internet. El chat quedó pausado: los mensajes que ya enviaste siguen guardados."* | "Reintentar" · "Usar IA local" (si `allowLocalModels` y hay modelo descargado) | `false` |
| **Key vencida o rechazada** | `CLAVE_INVALIDA` (401/403) | ✖ **"La clave de la cátedra no es válida."** — *"El servidor la rechazó (HTTP 401). Si venció, pedí una key nueva a la cátedra."* | "Usar IA local" · "Cambiar de modelo" | `false` |
| **Cuota agotada** | `SIN_CUOTA` (429) | ⚠ **"El servidor alcanzó su límite de uso."** | "Reintentar" | `false` (reintentable) |
| **Contexto agotado** | `CONTEXTO_AGOTADO` (400) | ⚠ **"Se agotó la ventana de contexto del modelo."** — *"Probá con una pregunta más corta o reducií la cantidad de fragmentos recuperados."* | "Reducir fragmentos" (⇒ pantalla de modelo del alumno) | `true` (puede reintentar con otra pregunta) |
| **Timeout** | `TIMEOUT` | ⚠ **"El servidor tardó demasiado."** | "Reintentar" | `true` |
| **IA local no disponible en Android** | — | ℹ En el selector de modelo: *"La IA local todavía no está disponible en Android. Usá la IA de la cátedra."* (opción deshabilitada) | — | `true` |
| **IA local: modelo no descargado** | `DESCARGA_FALLIDA` / — | ℹ **"El modelo local no está descargado."** — *"Descargalo (~1,2 GB) o usá la IA de la cátedra."* | "Descargar" · "Usar la IA de la cátedra" | `false` |
| **IA local: el servidor local no levanta** | `SERVIDOR_CAIDO` (local) | ⚠ **"No se pudo hablar con el modelo local."** — *"Revisá que el servidor local esté corriendo."* | "Reintentar" | `false` |
| **Material de la cátedra vacío** | — | ℹ En el toggle: *"La cátedra todavía no subió material."* (chip deshabilitado) | — | `true` |
| **Índice desactualizado** | — | Barra de progreso fina: *"Indexando el material de la cátedra…"* (debajo del header) | — | `true` (responde sin citas hasta terminar) |

### 9.2 Reglas de comportamiento

- Un banner de error **no borra el chat**: el alumno puede leer lo que ya se
  generó, copiar, y reintentar cuando quiera.
- El error que corta una respuesta en curso se muestra **dentro de la
  burbuja** (el texto se reemplaza por `"⚠ <mensaje>"`, como en FunshiEngineAI) y
  además como banner si el problema es de conexión (para que se entienda que
  no va a seguir solo).
- `canSend == false` deshabilita el redactor **y** el botón: es mejor que
  dejar escribir un mensaje que no se puede enviar. El texto escrito **se
  conserva** en `composerInput` (no se pierde al reconectar).
- El banner se descarta solo cuando el problema se resuelve (`vm.dismissBanner()`
  o un envío exitoso).

```kotlin
/** ¿Se puede enviar un mensaje ahora? */
val canSend: Boolean get() = ready && !fatalError
```

---

## 10. Nombres: archivos, tipos y composables

### 10.1 `:core` — dominio (3 archivos tocados, 1 nuevo)

| Archivo | Cambio |
|---|---|
| `core/commonMain/kotlin/kitoru/domain/Chat.kt` | `ChatMessage` gana `sourceRefs`, `attachments`, `mode` (los tres con valor por defecto) |
| `core/commonMain/kotlin/kitoru/domain/SourceRef.kt` | **Nuevo**: `SourceRef` (`@Serializable`) |
| `core/commonMain/kotlin/kitoru/domain/ChatAttachment.kt` | **Nuevo**: `ChatAttachment` (`@Serializable`) |

### 10.2 `:ui` — común (`commonMain`)

```
ui/src/commonMain/kotlin/kitoru/ui/
├── chat/
│   ├── StudentChatScreen.kt        @Composable StudentChatScreen(vm)
│   ├── StudentChatViewModel.kt     class StudentChatViewModel
│   ├── UiChatMessage.kt            data class UiChatMessage
│   ├── ChatHeader.kt               @Composable ChatHeader(vm)
│   ├── ChatStatusBanner.kt         @Composable ChatStatusBanner(banner, onDismiss)
│   ├── MessageList.kt              @Composable MessageList(vm)
│   ├── MessageBubble.kt            @Composable MessageBubble(m, editable, …)
│   ├── ComposerBar.kt              @Composable ComposerBar(vm, modifier)
│   ├── PermissionDialog.kt         @Composable PermissionDialog(vm)
│   ├── SourcesSheet.kt             @Composable SourcesSheet(sources, snippets, onOpen)
│   ├── ThreadDrawer.kt             @Composable ThreadDrawer(threads, current, onSelect)
│   ├── ChatBanner.kt               data class ChatBanner
│   └── ChatScrollbarAdapter.kt     class StableChatScrollbarAdapter + rememberStableChatScrollbarAdapter
├── components/
│   ├── CitationBadge.kt            @Composable CitationBadge(stats)          [diseño 04 §6.4]
│   ├── SourcesPanel.kt             @Composable SourcesPanel(…)              [compartido]
│   ├── ToolCallCard.kt             @Composable ToolCallCard(call)
│   ├── AttachmentCard.kt           @Composable AttachmentCard(a)
│   └── ThemedVerticalScrollbar.kt  @Composable ThemedVerticalScrollbar(adapter)  [port]
├── markdown/
│   ├── MarkdownParser.kt           MdNode, parseMarkdown                     [port]
│   ├── Inline.kt                   buildInlineAnnotated                     [port + citas]
│   ├── MarkdownText.kt             MarkdownText + renderers por nodo         [port]
│   ├── MathSegments.kt             MathSegment, splitMath                    [port]
│   ├── MathRenderer.kt             MathLayout, MathGlyph, MathRenderer      [nuevo]
│   ├── ComposeMathRenderer.kt      parser + layout en Compose puro          [nuevo]
│   ├── CodeBlock.kt                @Composable CodeBlock                    [port]
│   └── CodeHighlighter.kt          highlightCode, tokenize, LANG_TO_EXT     [port]
└── platform/
    └── FileActions.kt              SaveFileRequest, SavedFile, FileActions,
                                    expect @Composable rememberFileActions()
```

### 10.3 `:ui` — platform-specific

```
ui/src/desktopMain/kotlin/kitoru/ui/platform/FileActions.desktop.kt
ui/src/androidMain/kotlin/kitoru/ui/platform/FileActions.android.kt
```

### 10.4 Componentes públicos (para que los use la pantalla del docente)

| Componente | Lo usa |
|---|---|
| `MarkdownText` | chat del alumno, panel de prueba del prompt (docente), announcements (07) |
| `CodeBlock` | chat, panel de prueba del prompt |
| `CitationBadge` | chat, panel de prueba del prompt |
| `ToolCallCard` / `AttachmentCard` | chat, panel de prueba del prompt |

Los cuatro viven en `:ui/commonMain` para que **el docente y el alumno vean
exactamente lo mismo**: si el chat del alumno muestra un bloque de código
distinto al del panel de prueba, la comparación de calidad que hace el
docente deja de servir.

### 10.5 Tests previstos (`:ui/commonTest`)

| Test | Qué verifica |
|---|---|
| `MarkdownParserTest` | `parseMarkdown` sobre un documento con encabezado, lista, tabla, código, cita, fórmula y `---` |
| `InlineTest` | `buildInlineAnnotated`: negrita, cursiva, tachado, inline-code, enlace, y que `[1]` **no** se confunda con `[texto](url)` |
| `MathSegmentsTest` | `splitMath`: `$x$`, `$$y$$`, `$5 … $10` (precios), fórmula sin cerrar, código inline con `$` |
| `ComposeMathRendererTest` | `\frac{1}{2}` produce una layout con 3 glifos + 1 regla; `\unknowncmd` devuelve `null` (→ fallback) |
| `CodeHighlighterTest` | tokenización de Kotlin: keywords, strings, comentarios, números |
| `ScrollTargetTest` | `scrollTargetForKey` (puro, port de FunshiEngineAI) |
| `StudentChatViewModelTest` | `updateLast` muta sólo el último mensaje; la cola se une con `"\n\n"`; `cancel()` marca "(detenido)" |

---

## 11. Casos borde

| Caso | Comportamiento |
|---|---|
| El modelo devuelve un mensaje **vacío** | La burbuja muestra "…" (el placeholder) y se reemplaza por "⚠ El modelo no devolvió ninguna respuesta." con la opción "Reintentar" |
| El modelo pide una herramienta **desconocida** | `AgentLoop` genera una tarjeta `tool_call` con el motivo ("Herramienta desconocida: X. Disponibles: …") y sigue (feedback al modelo) |
| El modelo pide la **misma** herramienta dos veces | La segunda pide permiso otra vez (el permiso es por categoría, no por herramienta); tras 2 denegaciones el bucle corta |
| El alumno **cambia de hilo** mientras el modelo responde | `originThreadId` snapshot: los callbacks del job viejo **no** tocan el hilo nuevo (igual que en FunshiEngineAI con `originChatId`) |
| El alumno **borra el mensaje** que se está escribiendo | El botón "Borrar" está deshabilitado mientras `busy` |
| El **texto encolado** se descarta con "Detener" | La cola se vacía y no se manda nada (§3.7) |
| La respuesta es **muy larga** (> 100 k caracteres) | El `LazyColumn` la maneja; el resaltado de código de un bloque enorme puede tardar → se recorta el resaltado a 20 000 caracteres (el resto se muestra plano) |
| La respuesta trae **muchos `[n]`** | Los chips de fuentes se muestran como `FlowRow`; si no entran, se muestra "Fuentes (7) ▾" que abre la hoja |
| La **cátedra cambia** el material mientras el alumno chatea | El `KnowledgeBase` se re-sincroniza; el chat muestra la barra de progreso de indexación; los mensajes viejos conservan sus `sourceRefs` (puede que el material ya no exista → "Abrir material" falla con un aviso, no con un crash) |
| El alumno usa **flechas del teclado** para navegar | `ThemedVerticalScrollbar` soporta ↑ ↓ Re Pág Av Pág Inicio Fin (port de FunshiEngineAI) |
| El alumno tiene el **teclado abierto** y escribe | El redactor sube (`imePadding`), la lista se encoge, el scroll sigue al pie si `followToBottom` |

---

## 12. Criterios de aceptación

1. En escritorio, el chat muestra la lista de mensajes con scrollbar y el
   redactor fijo abajo; en Android, al abrir el teclado el redactor sube y la
   lista se encoge (nada queda tapado).
2. Durante el streaming, el texto del último mensaje crece token a token y el
   chat **baja solo** mientras el alumno esté al pie; si el alumno sube a
   leer, el chat **no lo arrastra** (reproduce el `followToBottom` de
   `ChatPane.kt`).
3. Las respuestas del modelo se renderizan con markdown: encabezados, listas,
   tablas, citas, código con resaltado y fórmulas.
4. Una fórmula con LaTeX no soportado se muestra **en monoespaciado con el
   LaTeX visible**; nunca desaparece.
5. "Copiar" funciona en las dos plataformas (portapapeles del sistema).
6. "Guardar como…" abre el diálogo nativo en escritorio y el selector de
   archivos de Android (SAF).
7. Cada respuesta en modo material muestra sus fuentes como chips y el
   distintivo de citas (verificadas / sin respaldo).
8. Cuando el modelo pide `create_file` sin permiso, aparece el diálogo con
   "Permitir siempre / Una vez / Cancelar"; si la cátedra apagó la categoría,
   **no** aparece el diálogo y la tarjeta lo dice.
9. El default del chat es "usar el material de la cátedra"; se puede cambiar a
   "preguntar libre" y el modo queda registrado en el mensaje.
10. Escribir mientras el modelo responde encola el mensaje; "Detener" cancela
    la respuesta, conserva el texto parcial y descarta la cola.
11. Sin red, con la key vencida o sin modelo local, hay un banner con el
    motivo en español y una acción; el chat sigue siendo legible y no se
    pierde lo escrito.
12. Todo el código nuevo del chat está en `commonMain` salvo los seams
    `FileActions` (desktop/android) y las APIs de plataforma inevitablemente
    específicas.

---

## 13. Fuera de alcance (de este documento)

- La **pantalla de configuración del modelo del alumno** (otra pantalla): el
  chat sólo muestra el chip del modelo efectivo y un enlace a ella.
- El **cajón de hilos** en detalle (persistencia y títulos automáticos): aquí
  sólo se usa `ThreadRepository`.
- La **voz** (dictado y lectura en voz alta) de FunshiEngineAI: no está en el
  hito 1 de Kitoru.
- La **analítica del docente**: consume los `ChatMessage` que este chat
  persiste; el diseño de esa pantalla es otro documento.
- El **login / inscripción** a la cátedra.
