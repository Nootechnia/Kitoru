# 07 — Explorador de chats de los alumnos (docente)

> Pantalla donde el docente (titular o ayudante) navega los chats de los
> alumnos de una cátedra: lista de alumnos → lista de hilos → conversación.
> Es la pantalla donde la **visibilidad por inscripción** se vuelve visible en
> la UI.
>
> Depende de: `docs/design/CONTEXTO.md`, el dominio `kitoru.domain`
> (`Chat.kt`, `Analytics.kt`, `AnalyticsAnalyzer.kt`, `Roles.kt`) y la UI de
> chat de FunshiEngineAI (`ChatPane.kt`, `MessageBubble.kt`, `ToolCallCard.kt`,
> `ChatSidebar.kt`).

---

## 0. Decisiones en una línea

| # | Decisión | Motivo |
|---|---|---|
| D1 | Desktop: **panel de tres columnas** (alumnos 280 dp · hilos 320 dp · visor resto). Android: **pila** con `NavHost` propio. | Es la navegación natural "de lo general a lo particular" y ya existe en el patrón de la app. |
| D2 | La visibilidad se aplica **en el repositorio**, no en la UI: si el texto no se puede ver, **no se carga en memoria**. | El cliente no es la frontera de seguridad; además evita "cargar y luego tapar". |
| D3 | `METRICS_ONLY` → el alumno **aparece** en el listado, con métricas; el hilo **no se puede abrir**: se ve un panel de métricas y fichas por mensaje sin texto. `NONE` → **no aparece**; sólo se informa la **cantidad** de alumnos ocultos (sin nombres). | `NONE` significa "no ve ni estadísticas": no hay nada que mostrar. El conteo evita que el padrón parezca incompleto. |
| D4 | `MessageBubble` de FunshiEngineAI se porta **en modo sólo lectura** (sin editar/borrar/reiniciar). `ChatSidebar` **no** se porta (es UI del alumno). | El chat del alumno es evidencia: no se toca. |
| D5 | Las fórmulas se muestran como **LaTeX en texto monoespaciado** en v1. `MathRender` (java.awt + JLaTeXMath) **no** se porta. | Regla 2 del proyecto: portable por defecto. |
| D6 | El filtro de visibilidad es **central**: filtros, búsqueda y exportación sólo operan sobre material visible. | Coherencia con la regla 6 ("el docente no ve lo que no debe"). |
| D7 | La auditoría (`AccessLog`) es **append-only** y se registra en el momento de *renderizar texto*. | Requisito explícito del encargo. |
| D8 | Exportación canónica: **Markdown** y **JSON** (portables, `commonMain`). **PDF** sólo en escritorio (`expect/actual`); en Android no se ofrece. | PDF no es portable; se degrada con honestidad en vez de romper. |
| D9 | El paquete importado se guarda **separado** del store vivo (`courses/<id>/imports/<pkgId>/`) y se re-clavea `imp|<pkgId>|<idOriginal>`. | El store vivo se sincroniza con el servidor; lo importado es del alumno y se puede borrar. |
| D10 | La búsqueda de texto usa el **BM25 ya portado** (`:backend`, `rag.retrieval.Bm25Index`) sobre los mensajes visibles, índice en memoria, construido lazy. | Reutilizar, no reescribir (regla 1). |

---

## 1. Objetivo y alcance

**Qué resuelve.** El docente necesita responder, para un alumno o para toda la
cátedra: *¿cómo usó la IA?*, *¿las respuestas estaban respaldadas por el
material?*, *¿qué material se consulta más?*, *¿qué alumno pidió ayuda con
el parcial?*. Para eso necesita (a) **métricas por alumno**, (b) **leer
conversaciones concretas** y (c) **no leer lo que no le corresponde**.

**Fuera de alcance (otros docs):** la analítica agregada de la cátedra (doc de
analítica), la configuración de la cátedra (doc de cátedra), la pantalla de
inscripción/visibilidad (doc de alumnos). Acá sólo se usa lo que esas pantallas
definan.

**Actor y permisos.** Sólo `CourseRole.TITULAR` y `CourseRole.AYUDANTE` entran a
esta pantalla. `ALUMNO` no tiene ningún camino de navegación hacia ella (ni
siquiera un `route` en el `NavHost` del alumno).

---

## 2. Layout

### 2.1 Desktop — panel de tres columnas

```
┌────────────────────────────────────────────────────────────────────────────────────┐
│ Toolbar:  IA-2026-1 · Inteligencia Artificial   [🔍 buscar en los chats] [Filtros] │
│                                            [Importar chats] [Exportar] [🛡 Auditoría]│
├──────────────────────┬──────────────────────┬──────────────────────────────────────┤
│ ALUMNOS (280 dp)     │ HILOS (320 dp)        │ CONVERSACIÓN (resto)                 │
│ ──────────────────── │ ──────────────────── │ ─────────────────────────────────── │
│ [🔍 filtrar alumnos] │ [🔍 filtrar hilos]    │ ┌──────────────────────────────────┐ │
│ ┌──────────────────┐ │ ┌──────────────────┐  │ │ Ana Ruiz · "Duda de parcial"     │ │
│ │▎Ana Ruiz  [👁]   │ │ │ Duda de parcial  │  │ │ [Phi-4-mini][Servidor][12/03]    │ │
│ │ 38 msj · 21 hilos │ │ │ 12 mar · 14 msj  │  │ └──────────────────────────────────┘ │
│ │ Citas 89% · 4 ⚠   │ │ │ ⚠ 1 sin respaldo │  │ ⚠ Consultas de texto quedan       │ │
│ │ [phi-4-mini]      │ │ └──────────────────┘  │   registradas · Ver auditoría        │
│ └──────────────────┘ │ ┌──────────────────┐  │ ─────────────────────────────────── │
│ ┌──────────────────┐ │ │ Teoría de SVM    │  │ ┌──────────────────────────────────┐ │
│ │▎Bruno Díaz [📊]   │ │ │ 10 mar · 8 msj   │  │ │ 👤 12/03 18:24 · 118 tokens      │ │
│ │ 12 msj · 3 hilos  │ │ │ ✅ citas ok      │  │ │ ¿Me explicás SVM con margen?      │ │
│ │ (sólo métricas)   │ │ └──────────────────┘  │ └──────────────────────────────────┘ │
│ └──────────────────┘ │                       │ ┌──────────────────────────────────┐ │
│ 2 alumnos no          │                       │ │ 🤖 12/03 18:24 · 2.1 s           │ │
│ aparecen (ocultos)   │                       │ │ [respuesta con markdown + LaTeX]  │ │
│                      │                       │ │ [1] Apuntes-01.pdf  ✅ Citas(2)   │ │
└──────────────────────┴──────────────────────┴──────────────────────────────────────┘
```

Anchos: alumnos `280.dp`, hilos `320.dp`, visor `weight(1f)`. Ambos paneles
laterales tienen `Surface(surfaceVariant)` y su propio `VerticalScrollbar`
temático (portado de `app/ui/Scrollbar.kt`, ver §4.1).

**Columna 1 — alumnos.** Cada fila (`StudentRow`) muestra:

```
▎ Ana Ruiz                                   [👁 Completo]
  38 msj · 21 hilos · 5 días activos
  Citas 31/35 (89 %) · 4 sin respaldo (12 %)
  [phi-4-mini · local]  [📥 1 paquete importado]
```

- La **franja vertical** (4 dp) a la izquierda codifica el estado:
  `primary` = FULL, `tertiary` (ámbar) = METRICS_ONLY, `secondary` = importado.
- La métrica resumida sale de `StudentAnalytics` (ya calculado por
  `AnalyticsAnalyzer.analyze(...).perStudent`). No se recalcula en la UI.
- Los chips son de sólo lectura. Un alumno METRICS_ONLY **no** tiene botón de
  "ver chats": la fila entera es un único botón que abre el panel de métricas.
- Al pie: `"2 alumnos no aparecen porque configuraron «Oculto»."` (sólo el
  número; ver §3.4).

**Columna 2 — hilos.** Un hilo por fila (`ThreadRow`), ordenado por
`updatedAt` descendente:

```
  Duda de parcial                    12 mar 18:31
  14 mensajes · 6 respuestas · 2.1 s medio
  ✅ 3 citas verificadas · ⚠ 1 sin respaldo
  [gpt-oss-20b]  [⏱ 2.1 s]
```

En METRICS_ONLY el título **no** se muestra: la fila se titula
`"Conversación 1"`, `"Conversación 2"`… numeradas por orden de `createdAt`
(§3.3).

**Columna 3 — visor.** Encabezado pegajoso + banner de auditoría + `LazyColumn`
de mensajes + pie con el resumen del hilo. Detalle en §4.

### 2.2 Breakpoints (window size class)

| Ancho | Layout |
|---|---|
| `>= 1100 dp` | 3 columnas (por defecto). |
| `760..1100 dp` | 2 columnas: alumnos (260 dp) + visor. La columna de hilos se abre como **panel superpuesto** (drawer modal) desde un botón "Hilos (n)" en el encabezado del visor. |
| `< 760 dp` (Android) | 1 columna, navegación por pila (§2.4). |

La decisión de qué columnas se muestran se toma con `BoxWithConstraints` +
un `ChatLayoutMode` calculado, para que el mismo árbol de composables sirva en
los tres casos:

```kotlin
enum class ChatLayoutMode { THREE_COLUMNS, TWO_COLUMNS, STACK }
fun chatLayoutMode(widthDp: Int): ChatLayoutMode = when {
    widthDp >= 1100 -> ChatLayoutMode.THREE_COLUMNS
    widthDp >= 760  -> ChatLayoutMode.TWO_COLUMNS
    else            -> ChatLayoutMode.STACK
}
```

### 2.3 Encabezado de la pantalla

`TeacherChatsScreen` es una pestaña de la pantalla de cátedra del docente
(`TeacherCourseScreen`,Tabs: Cátedra · Materiales · Analítica · **Chats** ·
Alumnos). Toolbar propio:

| Elemento | Acción |
|---|---|
| Selector de cátedra | `CourseDropdown` (reutiliza el de la app del alumno si existe; si no, `ExposedDropdownMenu` con `Course.shortName`). |
| `🔍 buscar en los chats` | Campo de búsqueda **global** (alcance = curso). Enter / icono → `SearchResultsPanel`. |
| `Filtros` | Abre `ChatFilterSheet` (dropdown o bottom sheet). |
| `Importar chats` | Abre `ImportPackageDialog` (§8). |
| `Exportar` | Exporta el hilo abierto; deshabilitado si no hay hilo. |
| `🛡 Auditoría` | Abre `AuditLogPanel` (§6.3). |

### 2.4 Android — navegación por pila

Se usa una pila propia (no `androidx.navigation`, que no es multiplataforma);
el botón físico "atrás" se intercepta con `androidx.compose.ui.backhandler.BackHandler`
(Común en Compose Multiplatform 1.7) y, si no estuviera disponible en la
versión fijada, con un `expect/actual` `PlatformBackHandler`.

```kotlin
sealed interface ChatNavLevel {
    data object Students : ChatNavLevel
    data class Threads(val studentId: String) : ChatNavLevel
    data class Conversation(val studentId: String, val threadId: String) : ChatNavLevel
}
```

- `ChatNavStack` en el `TeacherChatsViewModel`: `List<ChatNavLevel>`.
- Cada nivel es una pantalla completa con `TopAppBar` con flecha "atrás".
- `STACK` muestra sólo el nivel superior de la pila.
- La selección de alumno: `stack = listOf(Students, Threads(studentId))`.
- La selección de hilo: `stack = listOf(Students, Threads(studentId), Conversation(studentId, threadId))`.
- **Regla de visibilidad:** si el alumno es METRICS_ONLY, seleccionar un hilo
  **no** agrega el nivel `Conversation`: se queda en `Threads` y muestra el
  panel de métricas (el `Conversation` de un alumno sin texto no existe).

En desktop la pila no se usa: la selección es "columna activa".

### 2.5 Atajos de teclado (desktop)

| Atajo | Acción |
|---|---|
| `Ctrl/⌘ + F` | Enfocar el buscador global. |
| `Ctrl/⌘ + E` | Exportar el hilo abierto. |
| `Ctrl/⌘ + Shift + E` | Exportar la selección (varios hilos). |
| `↑ / ↓` | Mover la selección en la columna con foco. |
| `Enter` | Abrir el elemento seleccionado. |
| `Esc` | Cerrar el diálogo/panel abierto. |
| `Alt + 1 / 2 / 3` | Llevar el foco a alumnos / hilos / visor. |

---

## 3. El filtro de visibilidad (núcleo de la pantalla)

### 3.1 Principio: la visibilidad se aplica antes de leer

`ChatVisibility` (`Roles.kt`) tiene tres estados con `showsText` / `showsMetrics`.
La regla del proyecto ("el docente no ve lo que no debe") se implementa en **dos
capas**:

1. **Datos**: cuando el chats llegan de un servidor (Kitoru-Server), el servidor
   ya no envía el texto de un alumno `METRICS_ONLY`. En el milestone 1 (stores
   JSON locales) la garantía es del lado de la app: `TeacherChatRepository`
   **falla** si se le pide el texto de un hilo no autorizado, de modo que el
   texto no llega a la memoria del proceso.
2. **UI**: los composables que muestran texto reciben el texto ya resuelto;
   no hay un `if (visible)` que tape lo ya cargado.

Función pura y testeable (nuevo, `:core`):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/ChatPolicy.kt
object ChatPolicy {
    /** ¿El docente puede leer el texto de este hilo? */
    fun canReadText(
        enrollment: Enrollment?,
        thread: ChatThread,
        importedPackage: ImportedPackage?,
    ): Boolean

    /** ¿El docente puede ver métricas de este alumno? */
    fun canSeeMetrics(enrollment: Enrollment?): Boolean = enrollment?.visibility?.showsMetrics == true

    /** Etiqueta de un hilo cuando el texto no se puede mostrar. */
    fun anonymousThreadLabel(index: Int): String = "Conversación ${index + 1}"
}
```

`canReadText` devuelve `true` si:
- la inscripción es `FULL` **y** el hilo es `LIVE`; **o**
- el hilo es `IMPORTED` (el alumno lo compartió explícitamente → consentimiento
  explícito, que el propio `CONTEXTO.md` contempla: "No (salvo que el alumno
  lo comparta)").

Nunca devuelve `true` para `NONE` (ni siquiera por importación: si el alumno
compartió, se le cambia la visibilidad a `FULL` o se crea la inscripción
correspondiente durante la importación; ver §8.4).

### 3.2 Los tres estados de la UI

| | **FULL** | **METRICS_ONLY** | **NONE** |
|---|---|---|---|
| Fila en la lista de alumnos | Sí | Sí, con chip ámbar "Sólo métricas" | **No** (sólo se cuenta al pie) |
| Lista de hilos | Títulos y tags reales | Títulos ocultos: "Conversación N" | — |
| Texto de los mensajes | Sí, completo | **Nunca se carga** | — |
| Ficha por mensaje (métricas) | Sí | Sí (sin texto) | — |
| Panel de métricas agregadas | Sí | Sí (destacado) | — |
| Búsqueda de texto | Indexa sus mensajes | **No indexa nada** (no hay texto) | — |
| Filtros | Todos | Sólo por fecha / modelo / material (métricas) | — |
| Exportación de texto | Sí | **No** (sólo un CSV de métricas) | — |
| `AccessLog` | `VIEW_TEXT` al abrir | `VIEW_METRICS` al abrir el panel | — |

### 3.3 Estado METRICS_ONLY — diseño exacto

Al tocar un alumno con `METRICS_ONLY`:

- **Columna de hilos**: se muestran los hilos del alumno, pero **sin título ni
  tags** (el título lo escribió el alumno: es texto). Cada fila:
  ```
  Conversación 3                    12 mar 18:31
  14 mensajes · 6 respuestas · 2.1 s medio
  ✅ 3 citas verificadas · ⚠ 1 sin respaldo
  [gpt-oss-20b]
  ```
- **Visor**: en vez de la conversación, aparece `VisibilityNotice` + el panel de
  métricas:

  ```
  ┌────────────────────────────────────────────────────────────┐
  │ 🔒  Este alumno usa IA local y no compartió sus           │
  │     conversaciones                                          │
  │     El docente ve métricas y calidad de las respuestas,    │
  │     pero no el texto de los chats.                         │
  │     [ Ver el panel de métricas ]                            │
  └────────────────────────────────────────────────────────────┘
  ```

  Debajo, `MetricsOnlyList`: una ficha por mensaje **sin texto**:

  ```
  ┌ 12/03 18:24 ────────────────────────────────────────────┐
  │ 👤 Pregunta del alumno      · 118 tokens                  │
  │ 🤖 Respuesta del modelo     · 2.1 s · 412 tokens           │
  │    Citas: ✅ 2 verificadas · ⚠ 1 sin respaldo              │
  │    Fuentes: Apuntes-01.pdf, Parcial-2025.pdf              │
  │    Herramientas: get_time (OK · 12 ms)                    │
  └────────────────────────────────────────────────────────────┘
  ```

  y arriba el resumen agregado (`StudentAnalytics`): mensajes, respuestas,
  hilos, días activos, tasa de citas con respaldo, respuestas sin respaldo
  (%), latencia media, tokens, top materiales consultados.

- **Botón de ayuda al alumno**: `VisibilityNotice` incluye un botón
  **"Pedirle el paquete de chats"** que abre un diálogo con un texto listo
  para copiar:
  > Hola Ana, soy tu docente. Kitoru puede exportar tus conversaciones a un
  > archivo `.kitoru`. Si querés que revise cómo usaste la IA, exportá tus
  > chats (Kitoru → Chat → ⋮ → Exportar mis chats) y compartí ese archivo
  > por el medio que prefieras. Gracias.

  Este es el único camino por el cual un alumno `METRICS_ONLY` puede pasar a
  tener texto visible para el docente, y queda auditado (`IMPORT`).

**Qué se considera "métrica" y qué "texto"** (decisión explícita):

| Dato | ¿Texto? | Visible en METRICS_ONLY |
|---|---|---|
| `ChatMessage.text` | Sí | **No** |
| `ChatThread.title`, `ChatThread.tags` | Sí (lo escribió el alumno) | **No** → `"Conversación N"` |
| `ChatMessage.createdAt` | No | Sí |
| `ChatMessage.role` | No | Sí |
| `ChatMessage.tokensIn/Out`, `latencyMs` | No | Sí |
| `ChatMessage.citation` (veredict) | No | Sí |
| `ChatMessage.sources` (etiquetas de material) | No | Sí |
| `ChatMessage.toolCalls` (nombre, args, estado, duración) | No | Sí |
| `ChatMessage.attachments` (nombre, tamaño, mime) | No | Sí |

Los `args` de una herramienta pueden contener texto libre generado por el
modelo (p. ej. `{"query": "..."}`): se **truncan a 200 caracteres** y se
marcan como tales en la ficha ("args (truncados)"), para no filtrar contenido.

> **Decisión abierta (a coordinar con el doc de analítica):** permitir o no las
> fichas por mensaje en METRICS_ONLY. Recomendación: sí, con el truncado
> anterior, y agregar un interruptor de cátedra
> `Course.showPerMessageMetrics: Boolean = true` para que el titular lo pueda
> apagar si lo considera demasiado invasivo.

### 3.4 Estado NONE — diseño exacto

- El alumno **no aparece** en la lista de alumnos. No hay fila, no hay contador
  por alumno, no aparece en la búsqueda, ni en los filtros, ni en la
  exportación.
- Al pie de la columna de alumnos:
  `"2 alumnos no aparecen porque configuraron la visibilidad «Oculto»."`
  (sólo el número; **sin nombres**).
- Racionalidad: `NONE` significa que el docente no ve **ni el texto ni las
  estadísticas**. Mostrar el nombre no violaría la regla (la inscripción es
  Visible en la pestaña "Alumnos", donde el docente ve el padrón con su chip de
  visibilidad), pero **el conteo** es necesario para que el padrón de esta
  pantalla no parezca incompleto por un bug. Nombre + motivo exacto los tiene
  el docente en la pestaña "Alumnos"; esta pantalla sólo informa la cantidad.
- `AnalyticsAnalyzer.analyze` ya excluye a los `NONE` de todos los agregados
  (`visible = enrollments.filter { it.visibility.showsMetrics }`): esta pantalla
  se apoya en ese mismo criterio y no lo duplica.

### 3.5 Estado FULL — diseño exacto

- Todo visible, con el banner de auditoría arriba del visor:
  > ⚠ Estás viendo el contenido de los chats de **Ana Ruiz**. Cada consulta
  > queda registrada en la auditoría de la cátedra. · Ver auditoría

- El banner desaparece cuando el visor pasa a modo "Sólo métricas"
  (`ViewerMode.METRICS`), y en ese caso se registra `VIEW_METRICS` en vez de
  `VIEW_TEXT`.

---

## 4. El visor de conversación

### 4.1 Qué se porta de FunshiEngineAI

| Origen (FunshiEngineAI) | Destino en Kitoru | Acción | Adaptación |
|---|---|---|---|
| `app/ui/Markdown.kt` → `parseMarkdown`, `MdNode`, `MarkdownText`, `buildInlineAnnotated` | `ui/src/commonMain/kotlin/kitoru/ui/markdown/MarkdownText.kt` | **Portar** | Es Compose puro. Se le sacan `MathBlock`/`MathImage` (ver §4.3). Se le agrega un parámetro `highlight: List<IntRange> = emptyList()` para marcar las coincidencias de la búsqueda. |
| `app/ui/Markdown.kt` → `splitMath`, `MathSegment` | `ui/src/commonMain/kotlin/kitoru/ui/markdown/MathSplit.kt` | **Portar** | Puro y portable: se usa para partir el texto y detectar el LaTeX. |
| `app/ui/MathRender.kt` → `MathRender`, `MathRendered`, `toAwtColor` | — | **NO portar** | Usa `java.awt` (`BufferedImage`, `Graphics2D`) y JLaTeXMath (`TeXIcon` de Swing): no existe en Android. Se reemplaza por `LatexText` (§4.3). |
| `app/ui/CodeBlock.kt` → resaltado (`tokenize`, `styleFor`, `KEYWORDS_BY_LANG`) | `ui/src/commonMain/kotlin/kitoru/ui/markdown/CodeBlock.kt` | **Portar** | El resaltado es puro. Se sacan `java.awt.Toolkit` (portapapeles) y `javax.swing.JFileChooser` (guardar) y se reemplazan por `Clipboard`/`FileDialogs` (puertos de plataforma, §9.4). |
| `app/ui/Scrollbar.kt` → `ThemedVerticalScrollbar` | `ui/src/commonMain/kotlin/kitoru/ui/ui/ThemedVerticalScrollbar.kt` | **Portar** | Sólo usa `androidx.compose.foundation.Scrollbar` (común en CMP 1.7). *Verificar* que `rememberScrollbarAdapter` exista en `commonMain` de CMP 1.7.3; si no, la versión del visor usa `LazyColumn` sin scrollbar explícito en Android. |
| `app/chat/MessageBubble.kt` → `MessageBubble` | `ui/src/commonMain/kotlin/kitoru/ui/chat/ReadOnlyMessageBubble.kt` | **Portar y simplificar** | Se elimina toda la edición: fuera `editable`, `editableNow`, `onEdit`, `onCancelEdit`, `onSaveEdit`, `onDelete`, `onRegenerate`. Se agrega un pie de metadatos (fecha, latencia, tokens) y soporte de `highlight`. |
| `app/chat/MessageBubble.kt` → `CitationBadge` | `ui/src/commonMain/kotlin/kitoru/ui/chat/CitationBadge.kt` | **Portar** | Cambia `UiCitation` por el dominio `CitationStats` (misma forma: `verified`/`unsupported`/`unknown`). |
| `app/chat/ToolCallCard.kt` → `ToolCallCard` | `ui/src/commonMain/kotlin/kitoru/ui/chat/ToolCallCard.kt` | **Portar** | Cambia `UiToolCall` por `ToolCallStats` y muestra además `durationMs`. |
| `app/chat/AttachmentCard.kt` → `AttachmentCard` | `ui/src/commonMain/kotlin/kitoru/ui/chat/AttachmentCard.kt` | **Portar parcialmente** | Se elimina `java.awt.Desktop` y `javax.swing.JFileChooser`: "Abrir" pasa por un puerto `FileOpener` y "Guardar" por `FileDialogs` (§4.5). |
| `app/chat/ChatSidebar.kt` → `ChatListSidebar`, `ChatRowItem` | — | **NO portar** | Es la vista del alumno: árbol de carpetas, drag & drop, click derecho, renombrar, borrar. El docente no organiza carpetas ni borra chats ajenos. Se reemplaza por `TeacherThreadList` (§2.1). |
| `app/chat/ChatPane.kt` → `ChatPane` | — | **NO portar** | Es el chat *vivo*: envío, streaming, cola, permisos de herramientas, `ComposerBar`, `VoiceBar`. El visor del docente es de sólo lectura. Se reutilizan **ideas**, no código: `SelectionContainer` (con la precaución de no envolver los botones), scroll al abrir, `LazyColumn` con `key`. |
| `app/chat/ChatViewModel.kt` | `TeacherChatsViewModel` | **Nuevo** | Estado propio del dominio (§9.3). |
| `app/core/UiState.kt` → `UiMessage`, `UiCitation`, `UiToolCall`, `UiAttachment` | — | **NO portar** | El visor consume directamente el dominio `ChatMessage` (`kitoru.domain`), que ya tiene `role`, `text`, `sources`, `citation`, `toolCalls`, `latencyMs`, `tokensIn/Out`. Evita un modelo de UI duplicado. |
| `app/core/UiState.kt` → `humanSize` | `kitoru.ui.ui.Format.kt` | **Portar** | Función pura de formato de tamaño. |

### 4.2 Qué es nuevo (no existe en FunshiEngineAI)

| Composable | Qué hace |
|---|---|
| `TeacherChatsScreen` | Contenedor de la pantalla: toolbar + 3 columnas o pila. |
| `StudentListPanel` / `StudentRow` | Listado de alumnos con métrica resumida y chip de visibilidad. |
| `ThreadListPanel` / `ThreadRow` | Listado de hilos del alumno con métricas del hilo. |
| `ChatViewerPane` / `ChatViewerHeader` | Visor de sólo lectura + encabezado. |
| `VisibilityNotice` | Estado explícito de METRICS_ONLY (texto + acción). |
| `MetricsOnlyList` / `MessageMetricRow` | Fichas por mensaje sin texto. |
| `StudentMetricsPanel` | Resumen agregado del alumno (reutiliza `StudentAnalytics`). |
| `ChatFilterBar` / `ChatFilterSheet` | Barra de filtros + hoja de detalle. |
| `ChatSearchBar` / `SearchResultsPanel` | Búsqueda de texto (BM25) y panel de resultados. |
| `ExportChatDialog` | Diálogo de exportación (Markdown/JSON/PDF + destino). |
| `ImportPackageDialog` / `ImportPreviewDialog` | Importación de paquetes `.kitoru` (§8). |
| `AuditLogPanel` | Tabla de auditoría (§6.3). |
| `AuditBanner` | Aviso "esta consulta queda registrada". |
| `SourceList` | Fuentes citadas del mensaje, con distintivo de estado. |
| `LatexText` | Render del LaTeX como texto monoespaciado (§4.3). |
| `StudentBadge` / `ThreadBadge` / `OriginBadge` | Chips de visibilidad, modelo y origen. |

### 4.3 Fórmulas: decisión

**Decisión (D5): en la v1 el LaTeX se muestra como texto monoespaciado, en
desktop y en Android por igual.**

Motivos:

1. `MathRender` de FunshiEngineAI depende de `java.awt` (`BufferedImage`,
   `Graphics2D`, `Color`) y de **JLaTeXMath**, que devuelve un `TeXIcon` de
   Swing: no es portable (regla 2 del proyecto) y no hay equivalente razonable
   en Android.
2. Un renderer de fórmulas en Compose puro es un proyecto grande (layout de
   TeX: cajas, sub/superíndices, raíces, matrices) que no aporta al objetivo de
   esta pantalla.
3. En el **visor de auditoría** el texto de la fórmula se quiere **ver y
   copiar**, no sólo mirar: el docente tiene que poder copiar el LaTeX para
   verificarlo o usarlo en un parcial.

Qué se hace:

- Se porta `splitMath` (puro) para detectar `$…$` y `$$…$$`.
- El LaTeX se renderiza con `LatexText`:

```kotlin
@Composable
fun LatexText(latex: String, display: Boolean, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier,
    ) {
        Text(
            text = buildString { if (display) append("$$\n"); append(latex); if (display) append("\n$$") },
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
```

- La exportación a Markdown deja el LaTeX tal cual (correcto para `.md`).
- La exportación a **JSON** guarda el LaTeX crudo + un campo derivado
  `hasLatex: Boolean` para que un consumidor pueda re-renderizarlo.

Costura para el futuro (no implementada en la v1):

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/markdown/LatexRenderer.kt
enum class LatexMode { TEXT, RENDERED }
expect suspend fun renderLatex(latex: String, display: Boolean): ImageBitmap?
```

- `desktopMain`: JLaTeXMath (ya está en FunshiEngineAI) → imagen.
- `androidMain`: `null` → cae al texto.
- El `MarkdownText` recibe `latexMode: LatexMode = LatexMode.TEXT`; activarlo
  más adelante no toca el visor.

**Nota para el chat del alumno** (doc del alumno): si ahí se quiere render
real, la costura `expect/actual` se define **una sola vez** en `:ui` y ambos
chats la usan. No se implementa dos veces.

### 4.4 Adaptaciones de `MessageBubble` al dominio

```kotlin
@Composable
fun ReadOnlyMessageBubble(
    message: ChatMessage,
    highlight: List<IntRange> = emptyList(),
    showMetrics: Boolean = true,
    modifier: Modifier = Modifier,
)
```

- La burbuja del **usuario** va a la derecha, `primaryContainer`; la del
  **modelo** a la izquierda, `surfaceVariant`; `widthIn(max = 700.dp)`.
- El texto del **modelo** se renderiza con `MarkdownText`; el del **usuario**,
  en texto plano (igual que en FunshiEngineAI: lo que escribe el alumno no se
  interpreta como markdown).
- Antes del texto, las `ToolCallCard`.
- Después del texto, `SourceList` (las fuentes `[n]`), el `CitationBadge` y los
  `AttachmentCard`.
- Al pie, un nuevo `MessageMeta` (nuevo en esta pantalla):
  `"12/03 18:24 · 2.1 s · 412 tokens"` + acción **Copiar**.
- **Se elimina** toda la edición (`onEdit`, `onSaveEdit`, `onDelete`,
  `onRegenerate`): el docente no modifica conversaciones ajenas.
- `SelectionContainer` envuelve **sólo** el texto (no los botones), por el
  crash "ActiveParent with no focused child" que ya está documentado en
  FunshiEngineAI.
- La fecha se agrupa: un separador de día (`"lun 12/03"`) antes del primer
  mensaje de cada día.

### 4.5 Adjuntos

`ChatMessage` **no tiene** adjuntos en el dominio actual. Propuesta de cambio
(coordinar con el doc del chat del alumno):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Chat.kt
@Serializable
data class ChatAttachment(
    val name: String,
    /** Ruta relativa dentro del paquete/curso. */
    val path: String,
    val sizeBytes: Long = 0L,
    val mime: String = "",
)
```
y en `ChatMessage`: `val attachments: List<ChatAttachment> = emptyList()`.

La tarjeta se porta quitando la dependencia de AWT/Swing:

- "Abrir" → puerto `FileOpener` (`expect fun openInSystem(path: FilePath): Boolean`):
  desktop = `java.awt.Desktop`; Android = `Intent.ACTION_VIEW`.
- "Guardar" → puerto `FileDialogs.saveFile(...)`: desktop = `JFileChooser`;
  Android = `ActivityResultContracts.CreateDocument`.
- Si el archivo **no está disponible** (p. ej. el paquete importado vino sin
  adjuntos), la tarjeta lo dice: *"Archivo no disponible (no fue incluido en el
  paquete)"*.

### 4.6 Scroll y navegación

- `LazyColumn` con `key = { it.id }` y `contentPadding` vertical de 12 dp.
- Al abrir un hilo: scroll al **final** (mismo comportamiento que `ChatPane`).
- Scroll-to-message: cuando se llega desde un resultado de búsqueda, se hace
  `listState.scrollToItem(index)` con un `LaunchedEffect(messageId)` y se
  resalta el mensaje con un borde `primary` durante 2 s.

---

## 5. Filtros y búsqueda

### 5.1 Modelo de filtros

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/TeacherChat.kt
data class ChatFilters(
    /** Desde (epoch ms), inclusive. */
    val dateFrom: Long? = null,
    /** Hasta (epoch ms), inclusive. */
    val dateTo: Long? = null,
    /** Sólo hilos/respuestas con citas sin respaldo o inexistentes. */
    val onlyUngrounded: Boolean = false,
    /** Material citado (id de `Material`). */
    val materialId: String? = null,
    /** Modelo usado (`modelLabel`). */
    val modelLabel: String? = null,
    /** Rol de los mensajes a considerar. */
    val role: MessageRoleFilter = MessageRoleFilter.BOTH,
) {
    val isEmpty: Boolean get() = dateFrom == null && dateTo == null && !onlyUngrounded &&
        materialId == null && modelLabel == null && role == MessageRoleFilter.BOTH
}

enum class MessageRoleFilter(val label: String) { BOTH("Todo"), USER("Preguntas"), ASSISTANT("Respuestas") }
```

Los filtros son **de alcance alumno** (se aplican al alumno seleccionado) y
afectan a **la lista de hilos** (un hilo sobrevive si algún mensaje cumple) y
**al visor** (los mensajes que no cumplen se ocultan, con un contador
"3 de 14 mensajes ocultos por los filtros" + botón "verlos igual").

Semántica de cada filtro:

| Filtro | Cómo se calcula | Dónde se aplica |
|---|---|---|
| **Por alumno** | `state.studentQuery` filtra la columna 1 por `displayName` (sin acentos, con `stripDiacritics`). Hay además un filtro "Sólo alumnos con respuestas sin respaldo" (toggle) en la columna 1. | Columna 1 |
| **Por fecha** | `message.createdAt` dentro de `[dateFrom, dateTo]`; a nivel de hilo, `thread.updatedAt` dentro del rango. Selector de fecha con presets ("hoy", "últimos 7 días", "último mes", "todo el curso"). | Hilos + visor |
| **Respuestas sin respaldo** | `message.isAssistant && (citation.unsupported > 0 || citation.unknown > 0)`. Es el "hallazgo" principal de la herramienta. | Hilos + visor |
| **Por material citado** | `message.sources` contiene la etiqueta del material. Las etiquetas del dominio son `"corpus: ruta"`; se matchean contra `Material.path` (normalizando separadores) y, si no hay match, se ofrecen como "material externo". El desplegable lista los materiales de la cátedra (`MaterialRepository.listForCourse`) con el contador de citas. | Hilos + visor |
| **Por modelo usado** | `thread.modelLabel` (o `thread.effectiveModel.modelLabel`). El desplegable lista los modelos presentes en los hilos del alumno, con conteo de hilos. | Hilos |
| **Por rol** | usuario / asistente / ambos. | Visor |

En METRICS_ONLY los filtros de texto no aplican (no hay texto); sí los de
fecha, modelo y material (métricas).

### 5.2 Búsqueda de texto

**Dónde:** **en memoria, sobre los mensajes ya cargados**, con un índice BM25
construido *lazy*. No hay índice persistido.

**Por qué no un índice en disco:** el corpus de una cátedra es pequeño (miles de
mensajes), el store es un JSON local, y un índice en disco agrega un artefacto
que hay que invalidar, versionar y auditar (además de escribir texto de
alumnos en otro archivo, lo que choca con la privacidad). Con BM25 en memoria se
reutiliza `rag.retrieval.Bm25Index` (`:backend`, ya portado y testeado).

Implementación:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/ChatSearchIndex.kt
class ChatSearchIndex(chunks: List<Chunk>) {
    private val bm25 = Bm25Index(chunks)
    fun search(query: String, k: Int = 100): List<Pair<Chunk, Double>>
}
```

- Un `Chunk` por mensaje: `source = studentId`, `path = threadId`,
  `heading = role`, `text = message.text`.
- El índice **sólo incluye** mensajes de alumnos con `canReadText == true`
  (FULL) y de paquetes importados. **Los alumnos METRICS_ONLY no aportan
  texto al índice** (no hay texto), y NONE tampoco.
- Se construye la primera vez que se hace una búsqueda con texto no vacío
  (`LaunchedEffect` en la UI, `Dispatchers.Default` para no bloquear el hilo
  principal) y se cachea en el `ViewModel` con una `dataVersion` que se
  invalida cuando cambian hilos/mensajes/importaciones.
- Resultados: `ChatSearchHit(messageId, threadId, studentId, studentName,
  threadTitle, role, snippet, score, createdAt)`. Máximo 200 resultados.
- El **snippet** es una ventana de ±120 caracteres alrededor de la primera
  coincidencia, con los términos resaltados; en la lista de resultados se
  muestra como texto normal.
- Persistencia de la búsqueda: la consulta vive en el estado del `ViewModel`
  (se pierde al salir de la pantalla). Se guardan las **últimas 10 consultas**
  por docente para reutilizarlas (`ChatSearchHistory`), sólo la cadena de
  texto, en `config/search_history.json`.

Interacción:

- `Ctrl/⌘+F` enfoca el buscador.
- Los resultados se muestran en un panel (`SearchResultsPanel`) que reemplaza
  temporalmente la columna del medio (desktop) o es una pantalla (Android).
- Clic en un resultado → abre la conversación en ese mensaje y lo resalta.
- Búsqueda = **una** entrada de auditoría `SEARCH` por sesión de búsqueda
  (se coalesce igual que `VIEW_TEXT`).

---

## 6. Auditoría

### 6.1 Modelo `AccessLog`

Nuevo archivo en `:core`:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Audit.kt
/** Qué hizo el docente con los chats. */
@Serializable
enum class AccessKind(val label: String) {
    VIEW_TEXT("Leyó el texto"),
    VIEW_METRICS("Vio métricas"),
    SEARCH("Buscó texto"),
    EXPORT("Exportó"),
    IMPORT("Importó un paquete"),
}

/**
 * Registro de acceso a los chats de los alumnos.
 *
 * Es append-only: la app nunca borra entradas (salvo la rotación por
 * antigüedad, §6.4). El objetivo es que la cátedra pueda responder
 * "¿el docente vio este chat?" y que el alumno pueda pedir rendición de
 * cuentas.
 */
@Serializable
data class AccessLog(
    val id: String,
    val courseId: String,
    /** Id del docente que hizo la consulta. */
    val teacherId: String,
    val teacherName: String,
    /** Alumno cujos chats se consultaron; vacío = búsqueda global. */
    val studentId: String = "",
    /** Hilo consultado; vacío = todos los hilos del alumno. */
    val threadId: String = "",
    val kind: AccessKind = AccessKind.VIEW_TEXT,
    /** Epoch ms. */
    val at: Long = 0L,
    /** Detalle libre: "12 coincidencias", "markdown", "parcial-1.kitoru". */
    val detail: String = "",
    /** Cuántas veces se coalesció la misma consulta. */
    val occurrences: Int = 1,
)
```

Repositorio:

```kotlin
interface AccessLogRepository {
    suspend fun listForCourse(courseId: String): List<AccessLog>
    suspend fun listForStudent(courseId: String, studentId: String): List<AccessLog>
    suspend fun record(entry: AccessLog)
    /** Registra una consulta coalesciendo con la última si es del mismo tipo. */
    suspend fun recordAccess(
        courseId: String, teacherId: String, teacherName: String,
        studentId: String = "", threadId: String = "",
        kind: AccessKind, detail: String = "",
    )
}
```

Implementación: `FileAccessLogRepository(fs)` sobre `JsonListStore` en
`config/access_log.json` (mismo patrón que `FileBackedRepository`).

**Regla de coalescencia:** si la última entrada tiene el mismo
`(courseId, teacherId, studentId, threadId, kind)` y es **más nueva que 5
minutos**, se incrementa `occurrences` y se actualiza `at` en vez de insertar.
Esto evita que un docente que scrollea 200 mensajes genere 200 entradas, sin
perder el detalle de "estuvo mirando este chat a las 10:15".

### 6.2 Cuándo se registra

| Acción | `kind` | Condición |
|---|---|---|
| Se abre un hilo con texto visible | `VIEW_TEXT` | Al **renderizar** el primer mensaje del hilo, no al seleccionarlo en la lista (puede ser que nunca se abra). Si el hilo ya está cargado y el docente vuelve a él, no se duplica (ya está en pantalla). |
| Se cambia el visor a "Sólo métricas" de un alumno FULL | `VIEW_METRICS` | — |
| Se abre el panel de métricas de un alumno METRICS_ONLY | `VIEW_METRICS` | — |
| Se hace una búsqueda de texto | `SEARCH` | Una entrada por sesión de búsqueda, con `detail = "N coincidencias"`. **No se guarda el texto buscado ni los snippets.** |
| Se exporta un chat | `EXPORT` | `detail = "markdown" \| "json" \| "pdf"`. Si la exportación es "para compartir con el alumno", `detail = "markdown (compartido con el alumno)"`. |
| Se importa un paquete `.kitoru` | `IMPORT` | `detail = "<archivo> · N hilos · M mensajes"`. |
| Se borra un paquete importado | `IMPORT` | `detail = "borrado: <archivo>"`. |

**Lo que NO se registra:** ver la lista de alumnos, ver la lista de hilos,
mirar la analítica agregada, exportar un CSV de métricas de un alumno
METRICS_ONLY (se registra igual, con `VIEW_METRICS`? — no: el CSV de métricas
no expone texto; se registra `EXPORT` para trazabilidad, pero no `VIEW_TEXT`).

**Qué ve el alumno:** el alumno puede abrir su propia pantalla
"Auditoría de mis chats" (ver doc del alumno) que le muestra **cuántas veces y
cuándo** el docente leyó sus conversaciones (sin el detalle técnico). Ese
listado se deriva del mismo `AccessLog` filtrando por `studentId`, y **sólo**
muestra las entradas `VIEW_TEXT`/`VIEW_METRICS`/`SEARCH`. Es la misma decisión
de diseño que la visibilidad, pero al revés: la transparencia es el default.

### 6.3 Cómo se muestra

`AuditLogPanel` (desktop: panel/dialogo ancho; Android: pantalla completa):

```
┌────────────────────────────── Auditoría de la cátedra ──────────────────────────────┐
│ [Filtro: docente ▾] [alumno ▾] [acción ▾] [desde 📅] [hasta 📅] [🔍] [Exportar CSV] │
├──────────────┬───────────────┬─────────────┬─────────────┬──────────┬──────────────┤
│ Fecha        │ Docente       │ Alumno      │ Chat        │ Acción   │ Detalle      │
├──────────────┼───────────────┼─────────────┼─────────────┼──────────┼──────────────┤
│ 13/03 10:15  │ Dra. Pérez    │ Ana Ruiz    │ Duda de pa… │ Leyó el… │ ×3           │
│ 13/03 09:02  │ Lic. Gómez    │ (todos)     │ —           │ Buscó…   │ 12 coincid.  │
│ 12/03 18:30  │ Dra. Pérez    │ Bruno Díaz  │ —           │ Importó… │ parcial-1.ki │
└──────────────┴───────────────┴─────────────┴─────────────┴──────────┴──────────────┘
```

- Orden: `at` descendente.
- Filtros: docente, alumno, acción, rango de fechas, texto libre.
- **Exportar CSV** (portable, `\n`, con cabecera) → `exports/<courseId>/auditoria.csv`.
- En la **fila del alumno** de la columna 1, un chip con el ícono de escudo y
  el número de consultas: `🛡 3` → al pulsarlo, filtra la auditoría por ese
  alumno.

### 6.4 Rotación y límites

- Se conservan las **5000 entradas** más recientes por cátedra (rotación FIFO al
  insertar). El detalle: la rotación se calcula sobre el archivo completo.
- El log es **local** en el milestone 1 (`config/access_log.json`). Cuando
  exista Kitoru-Server, el log debe ir al servidor (si no, el docente puede
  borrar su rastro borrando el archivo). **Limitación conocida y anotada** en
  el doc: en desktop local la trazabilidad es "de buena fe".
- `AccessLog` no se exporta dentro de los chats exportados salvo en el modo
  "compartir con el alumno", donde se incluye una línea de procedencia
  ("Exportado el 13/03/2026 10:15 por Dra. Pérez") pero **no** el historial
  completo de accesos.

---

## 7. Exportación

### 7.1 Formatos

| Formato | Dónde | Implementación |
|---|---|---|
| **Markdown** (`.md`) | todas las plataformas | `ChatExporter.markdown(...)` en `:core` `commonMain` (función pura). |
| **JSON** (`.json`) | todas las plataformas | `ChatExporter.json(...)` en `:core` `commonMain`. |
| **PDF** (`.pdf`) | **sólo desktop** | `expect suspend fun exportChatPdf(...)` en `:ui`: `desktopMain` con una biblioteca PDF de JVM **sin AWT** (p. ej. `com.fleeksoft:kotlin-pdf`); `androidMain` devuelve un error en español ("La exportación a PDF no está disponible en Android; exportá a Markdown o JSON"). |
| **CSV de métricas** (`.csv`) | todas | Para alumnos METRICS_ONLY: una fila por mensaje con sus métricas (sin texto). |

**Decisión (D8):** Markdown y JSON son los formatos canónicos: portables,
versionables en git, y legibles por el alumno. El PDF es un extra de escritorio;
en Android el diálogo ofrece "Compartir" (que produce Markdown) en su lugar.

> Si la biblioteca PDF elegida resultara depender de AWT, el plan B es
> **"Exportar a HTML para imprimir"**: un `.html` autónomo con CSS
> `@media print` que el docente abre e imprime a PDF desde el navegador. Es
> portable, sin dependencias, y sirve igual para entregar.

### 7.2 Contenido

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Export.kt
enum class ExportFormat(val label: String, val extension: String) {
    MARKDOWN("Markdown", "md"),
    JSON("JSON", "json"),
    PDF("PDF", "pdf"),
    METRICS_CSV("CSV de métricas", "csv"),
}

data class ExportHeader(
    val course: Course,
    val studentName: String,
    val teacherName: String,
    val teacherRole: CourseRole,
    val exportedAt: Long,
    val origin: ThreadOrigin,
    val packageName: String = "",
    val sharedWithStudent: Boolean = false,
)

object ChatExporter {
    fun markdown(thread: ChatThread, messages: List<ChatMessage>, header: ExportHeader): String
    fun json(thread: ChatThread, messages: List<ChatMessage>, header: ExportHeader): String
    fun metricsCsv(summaries: List<ThreadSummary>, header: ExportHeader): String
}
```

**Markdown generado** (ejemplo real):

```markdown
# Duda de parcial

- **Alumno:** Ana Ruiz
- **Cátedra:** IA-2026-1 · Inteligencia Artificial
- **Modelo:** gpt-oss-20b (servidor de la cátedra)
- **Inicio:** 12/03/2026 18:24 — **Última actividad:** 12/03/2026 18:31
- **Mensajes:** 4 (2 preguntas · 2 respuestas)
- **Citas:** 3 verificadas · 1 sin respaldo
- **Exportado por:** Dra. Pérez (Titular) — 13/03/2026 10:15
- **Origen:** conversaciones del servidor de la cátedra

---

### Ana Ruiz — 12/03 18:24

¿Me explicás SVM con margen?

### IA — 12/03 18:24

El margen es la distancia entre la frontera de decisión y el punto más
cercano de cada clase. Support vector machine.

$F(x) = w^T x + b$

*Fuentes: [1] Apuntes-01.pdf · [2] Parcial-2025.pdf*
*Citas: 2 verificadas · 1 sin respaldo · 2.1 s · 412 tokens*

---

> Exportado por Dra. Pérez (Titular) el 13/03/2026 10:15.
> Esta exportación contiene conversaciones de alumnos y debe tratarse
> como información sensible de la cátedra.
```

**JSON**: el dominio más un bloque `provenance`:

```json
{
  "format": "kitoru.chat-export",
  "formatVersion": 1,
  "provenance": {
    "exportedAt": 1773012345000,
    "exportedBy": { "id": "u-doc-1", "name": "Dra. Pérez", "role": "TITULAR" },
    "course": { "id": "c-1", "code": "IA-2026-1", "name": "Inteligencia Artificial" },
    "student": "Ana Ruiz",
    "origin": "LIVE",
    "sharedWithStudent": false
  },
  "thread": { "id": "t-1", "title": "Duda de parcial", "modelLabel": "gpt-oss-20b", "createdAt": 0, "updatedAt": 0, "tags": [] },
  "messages": [
    {
      "id": "m-1", "role": "user", "text": "¿Me explicás SVM con margen?",
      "createdAt": 0, "tokensIn": 118, "tokensOut": 0,
      "sources": [], "citation": null, "toolCalls": [],
      "attachments": [], "hasLatex": false
    }
  ]
}
```

El JSON **no** incluye `latencyMs`? Sí lo incluye (es dato de calidad).
Incluye `hasLatex` para que un consumidor pueda re-renderizar las fórmulas.

### 7.3 Destino y diálogo

`ExportChatDialog`:

- Radio: Markdown / JSON / PDF (sólo desktop) / CSV de métricas (si el alumno
  es METRICS_ONLY).
- Interruptor **"Compartido con el alumno"**: cambia la cabecera del documento
  (agrega "Compartido con el alumno el <fecha>" y la nota de confidencialidad
  → "Este documento contiene tus conversaciones; consérvalo si lo necesitás").
- Destino:
  - Desktop: botón "Guardar como…" → `FileDialogs.saveFile(suggestedName,
    extensions)` (Swing) con suggestion
    `Ana-Ruiz_Duda-de-parcial_2026-03-13.md`.
  - Sin diálogo: se guarda en `KitoruPaths.dataDir/exports/<courseId>/` y se
    muestra el path con un botón "Abrir carpeta" (portable, `FileOpener`).
  - Android: `CreateDocument` (Activity Result API) vía `FileDialogs`.
- Nombre por defecto: `<alumno>_<hilo>_<yyyy-MM-dd>.<ext>`, sanitizado
  (sin acentos ni caracteres de ruta).

### 7.4 Exportar varios chats

- "Exportar selección" (`Ctrl/⌘+Shift+E`): el docente marca varios hilos
  (checkbox en la columna 2) y exporta:
  - Markdown → **un** archivo con secciones por hilo.
  - JSON → un archivo con `threads: [...]` y `messages: [...]`.
  - PDF → un PDF con un hilo por capítulo (sólo desktop).
- El destino por defecto: `exports/<courseId>/<alumno>_<n>-hilos_<fecha>.md`.

---

## 8. Alumno con IA local: importación de paquetes `.kitoru`

### 8.1 Formato del paquete

`.kitoru` es un **ZIP** con esta estructura:

```
parcial-1.kitoru
├── manifest.json
├── threads.json
├── messages.json
└── attachments/            (opcional)
```

`manifest.json`:

```json
{
  "formatVersion": 1,
  "app": "Kitoru 1.0",
  "courseCode": "IA-2026-1",
  "courseName": "Inteligencia Artificial",
  "studentId": "u-7f3a",
  "studentName": "Ana Ruiz",
  "exportedAt": 1773012345000,
  "threads": 21,
  "messages": 412,
  "model": { "mode": "LOCAL", "name": "Phi-4-mini-instruct-Q4_K_M" },
  "visibilityAtExport": "METRICS_ONLY",
  "sha256": "9f2c…"
}
```

`threads.json` / `messages.json` usan los mismos serializadores del dominio
(`ChatThread`, `ChatMessage`), de modo que el paquete lo genera el chat del
alumno (doc del alumno) y lo lee esta pantalla sin conversiones.

El ZIP se lee/escribe con funciones `expect/actual` en `:core`:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/Zip.kt
expect fun unzip(bytes: ByteArray): Map<String, ByteArray>
expect fun zip(entries: Map<String, ByteArray>): ByteArray
```
- `actual` en `jvmCommonMain` con `java.util.zip.ZipInputStream` /
  `ZipOutputStream` (disponible en desktop **y** Android).
- Requiere agregar `readBytes`/`writeBytes` a `kitoru.platform.FileSystem`.

### 8.2 UI de importación (3 pasos)

**Paso 1 — elegir el archivo.**
`ImportPackageDialog` paso 1:
- Botón "Elegir archivo…" → `FileDialogs.openFile(extensions = listOf("kitoru"))`.
- El diálogo es **drag & drop** en desktop (Compose `pointerInput` +
  `onGloballyPositioned` para leer la URL del archivo) y, en Android, un
  selector del sistema (`OpenDocument`).
- Zona de destino con el texto:
  > "Importá el archivo `.kitoru` que te pasó el alumno. El paquete contiene
  > sus conversaciones y **queda visible para los docentes de la cátedra**."

**Paso 2 — vista previa (`ImportPreviewDialog`).**
Se lee el manifiesto (sin escribir nada todavía) y se muestra:

```
┌──────────── Importar chats de un alumno ─────────────┐
│ 📦 parcial-1.kitoru · 412 KB                          │
│                                                         │
│ Alumno          Ana Ruiz                              │
│ Cátedra         IA-2026-1 · Inteligencia Artificial ✓  │
│ Hilos           21                                     │
│ Mensajes        412                                    │
│ Rango de fechas 01/03/2026 — 13/03/2026                │
│ Modelo          Phi-4-mini-instruct (IA local)         │
│ Exportado       13/03/2026 09:12 por el alumno        │
│                                                         │
│ ⚠ El manifiesto dice que el alumno comparte sus        │
│   conversaciones de forma explícita.                  │
│                                                         │
│ Alumno asignado a: [Ana Ruiz — Alumno ▾]             │
│                                                         │
│              [ Cancelar ]   [ Importar 21 chats ]      │
└─────────────────────────────────────────────────────────┘
```

Advertencias y errores (fail-fast, en español, como en FunshiEngineAI):

| Situación | Mensaje |
|---|---|
| No es un ZIP | "El archivo no es un paquete de Kitoru." |
| Falta `manifest.json` | "El paquete no tiene manifiesto; no parece un export de Kitoru." |
| `formatVersion` mayor que la de la app | "El paquete usa el formato v2 y esta versión de Kitoru entiende hasta la v1. Actualizá Kitoru." |
| Cátedra distinta | ⚠ "El paquete es de «MAT-2026-1» y la cátedra actual es «IA-2026-1». ¿Importarlo igual?" |
| Paquete ya importado (mismo `sha256`) | ⚠ "Este paquete ya fue importado el 12/03/2026. Si lo importás de nuevo, se duplicará." (se puede continuar) |
| Sin chats | "El paquete no tiene conversaciones." |
| No se puede asignar a ninguna inscripción | El desplegable "Alumno asignado a" ofrece crear una inscripción nueva (ver §8.4). |

**Paso 3 — resultado.**
- Se escribe el paquete en `courses/<courseId>/imports/<packageId>/` y se
  registra `ImportedPackage` en `config/imported_packages.json`.
- Se registra `AccessLog(kind = IMPORT)`.
- Mensaje de éxito (Snackbar): "Se importaron 21 chats de Ana Ruiz."
- La UI navega a la columna 2 con el alumno asignado y los hilos importados ya
  visibles.

### 8.3 Cómo se marca en el listado

- **En la columna de alumnos**: chip `📥 1 paquete · 13/03` junto a los chips de
  modelo. La franja vertical pasa a `secondary` si el alumno **sólo** tiene
  chats importados (no hay chats en el servidor) → el docente ve de un vistazo
  que ese alumno usa IA local y compartió.
- **En la columna de hilos**: cada hilo importado lleva el chip
  `📥 importado por el alumno` (con la fecha de importación en el tooltip) y un
  `BorderStroke` de 1 dp en `secondary`. Los hilos importados se ordenan
  **después** de los vivos, o se mezclan por fecha con una opción
  "Separar importados".
- **En el visor**: el encabezado muestra
  `Origen: paquete importado (parcial-1.kitoru, 13/03/2026 09:12)` y un aviso:
  > Este chat no está en el servidor de la cátedra: lo exportó el alumno y lo
  > compartiste vos.

- Un `ImportedPackage` se puede **borrar** desde un menú en el chip del alumno
  (`⋮ → Descartar paquete`), con confirmación:
  "Se borrarán 21 chats importados de Ana Ruiz. ¿Continuar?" → queda un
  `AccessLog(kind = IMPORT, detail = "borrado: parcial-1.kitoru")`.

### 8.4 Guardas de la importación

- El paquete importado **no** habilita por sí solo la visibilidad: el hilo
  importado es legible porque el alumno lo compartió (consentimiento
  explícito), y eso se refleja en la inscripción:
  - Si el alumno ya está inscripto: la importación **no cambia** su
    `visibility`; la legibilidad del contenido importado es sí o sí
    (viene del alumno). El `ChatPolicy.canReadText` lo contempla con el
    parámetro `importedPackage`.
  - Si el alumno **no** está inscripto: el docente puede asignarlo a una
    inscripción existente o crear una (`Enrollment(role = ALUMNO,
    visibility = FULL)`), con un diálogo explícito:
    > "Ana Ruiz no está inscripto en esta cátedra. Al crear la inscripción
    > con visibilidad «Completo» vas a poder ver sus conversaciones
    > importadas. Esto queda registrado en la auditoría."

  Esto evita la vía de escape de importar un paquete y ver de paso los chats
  de alguien con visibilidad Oculto: si el paquete es de un alumno `NONE` que
  no lo compartió, el docente no tiene el archivo; y si lo tiene, es porque el
  alumno se lo dio (consentimiento), lo que se audita.

### 8.5 Modelo de dominio

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Import.kt
@Serializable
data class ImportedPackage(
    val id: String,
    val courseId: String,
    val studentId: String,
    val studentName: String,
    val fileName: String,
    val importedAt: Long = 0L,
    val importedBy: String = "",        // id del docente que importó
    val importedByName: String = "",
    val threadCount: Int = 0,
    val messageCount: Int = 0,
    val sha256: String = "",
    val courseCodeAtExport: String = "",
    val modelLabel: String = "",
    val firstAt: Long = 0L,
    val lastAt: Long = 0L,
)

interface ImportedPackageRepository {
    suspend fun listForCourse(courseId: String): List<ImportedPackage>
    suspend fun save(pkg: ImportedPackage)
    suspend fun delete(packageId: String)
    suspend fun threadsForPackage(packageId: String): List<ChatThread>
    suspend fun messagesForPackage(packageId: String): List<ChatMessage>
}
```

Y el hilo queda marcado en el dominio:

```kotlin
// Chat.kt (cambio propuesto)
@Serializable
enum class ThreadOrigin(val label: String) {
    LIVE("Servidor de la cátedra"),
    IMPORTED("Importado por el alumno"),
}
// en ChatThread:
val origin: ThreadOrigin = ThreadOrigin.LIVE,
val originPackageId: String = "",
```

Al importar, los ids se re-clavean: `id = "imp|<packageId>|<idOriginal>"` (y
los mensajes con el mismo prefijo). Así conviven en el mismo repositorio sin
colisiones y se puede borrar el paquete completo filtrando por prefijo.

### 8.6 Repositorio agregado

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/TeacherChat.kt
interface TeacherChatRepository {
    /** Hilos visibles de la cátedra (vivos + importados). */
    suspend fun threadsForCourse(courseId: String): List<ChatThread>
    /** Mensajes de un hilo. Falla si el hilo no es legible por el docente. */
    suspend fun messagesForThread(threadId: String): List<ChatMessage>
}
```

`DefaultTeacherChatRepository(live: ThreadRepository, liveMessages:
MessageRepository, imports: ImportedPackageRepository)`:
- `threadsForCourse` = `live.listForCourse(courseId)` + los hilos importados
  (con `origin = IMPORTED`).
- `messagesForThread` = si el id empieza con `"imp|"` → `imports.messagesForPackage`,
  si no → `liveMessages.listForThread`. **Antes** de devolver, valida
  `ChatPolicy.canReadText` contra la inscripción del autor y lanza
  `ChatAccessDeniedException` si no corresponde.

---

## 9. Nombres: archivos, composables, view-models

### 9.1 `:core` (KMP, `commonMain` salvo indicado)

| Archivo | Contenido |
|---|---|
| `core/src/commonMain/kotlin/kitoru/domain/Chat.kt` | **Cambios**: `+ ThreadOrigin`, `ChatThread.origin`, `ChatThread.originPackageId`, `+ ChatAttachment`, `ChatMessage.attachments`. |
| `core/src/commonMain/kotlin/kitoru/domain/Audit.kt` | **Nuevo**: `AccessKind`, `AccessLog`, `AccessLogRepository`. |
| `core/src/commonMain/kotlin/kitoru/domain/FileAuditRepository.kt` | **Nuevo**: `FileAccessLogRepository`. |
| `core/src/commonMain/kotlin/kitoru/domain/Import.kt` | **Nuevo**: `ImportedPackage`, `ImportedPackageRepository`, `ChatPackageManifest`. |
| `core/src/commonMain/kotlin/kitoru/domain/FileImportRepository.kt` | **Nuevo**: `FileImportedPackageRepository` (lee/escribe `courses/<id>/imports/<pkg>/`). |
| `core/src/commonMain/kotlin/kitoru/domain/Export.kt` | **Nuevo**: `ExportFormat`, `ExportHeader`, `ChatExporter` (markdown/json/csv). |
| `core/src/commonMain/kotlin/kitoru/domain/TeacherChat.kt` | **Nuevo**: `TeacherChatRepository`, `DefaultTeacherChatRepository`, `ChatFilters`, `MessageRoleFilter`, `SearchScope`, `ChatSearchHit`. |
| `core/src/commonMain/kotlin/kitoru/domain/ChatPolicy.kt` | **Nuevo**: reglas puras de visibilidad (`canReadText`, `canSeeMetrics`, `anonymousThreadLabel`). |
| `core/src/commonMain/kotlin/kitoru/domain/ThreadAnalyzer.kt` | **Nuevo**: `ThreadSummary` + `ThreadAnalyzer.summarize(threads, messages)` (función pura; la versión por alumno ya la hace `AnalyticsAnalyzer`). |
| `core/src/commonMain/kotlin/kitoru/domain/Repositories.kt` | **Cambios**: `+ AccessLogRepository`, `+ ImportedPackageRepository`, `+ TeacherChatRepository`. |
| `core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt` | **Cambios**: `+ readBytes`, `+ writeBytes`. |
| `core/src/commonMain/kotlin/kitoru/platform/Zip.kt` | **Nuevo**: `expect/actual unzip`/`zip` (actual en `jvmCommonMain` con `java.util.zip`). |

### 9.2 `:ui` (Compose Multiplatform, `commonMain` salvo indicado)

| Archivo | Composables / funciones |
|---|---|
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/TeacherChatsScreen.kt` | `TeacherChatsScreen(vm)` — toolbar + layout por `ChatLayoutMode` + pila Android. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/TeacherChatsViewModel.kt` | `TeacherChatsViewModel` (§9.3). |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/TeacherChatsUiState.kt` | `TeacherChatsUiState`, `StudentListItem`, `ThreadListItem`, `ChatSearchState`. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/StudentListPanel.kt` | `StudentListPanel(items, selectedId, query, onQuery, onSelect, hiddenCount, ...)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/StudentRow.kt` | `StudentRow(item, selected, onClick)` + `StudentBadge`. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/StudentMetricsPanel.kt` | `StudentMetricsPanel(stats, topSources)` — resumen agregado. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/ThreadListPanel.kt` | `ThreadListPanel(items, selectedId, onSelect, onToggleSelect, ...)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/ThreadRow.kt` | `ThreadRow(item, selected, anonymous)` + `OriginBadge`. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/ChatViewerPane.kt` | `ChatViewerPane(...)` — orquesta: encabezado, banner, filtros, lista. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/ChatViewerHeader.kt` | `ChatViewerHeader(thread, student, origin, onExport, onToggleMode)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/VisibilityNotice.kt` | `VisibilityNotice(state, onShowMetrics, onRequestPackage)` — el estado explícito de METRICS_ONLY. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/MetricsOnlyList.kt` | `MetricsOnlyList(messages, summaries)` + `MessageMetricRow`. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/ChatFilterBar.kt` | `ChatFilterBar(filters, onChange, onClear)` + `ChatFilterSheet`. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/ChatSearchBar.kt` | `ChatSearchBar(query, scope, onQuery, onScope)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/SearchResultsPanel.kt` | `SearchResultsPanel(hits, onOpen)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/ChatSearchIndex.kt` | `ChatSearchIndex` (wrapper sobre `Bm25Index`). |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/ExportChatDialog.kt` | `ExportChatDialog(thread, formats, onExport, onDismiss)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/ImportPackageDialog.kt` | `ImportPackageDialog` (paso 1). |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/ImportPreviewDialog.kt` | `ImportPreviewDialog(manifest, onConfirm, onDismiss)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/AuditLogPanel.kt` | `AuditLogPanel(entries, onFilter, onExportCsv)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/teacher/chats/AuditBanner.kt` | `AuditBanner(studentName, onShowLog)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/chat/ReadOnlyMessageBubble.kt` | `ReadOnlyMessageBubble(message, highlight, showMetrics)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/chat/CitationBadge.kt` | `CitationBadge(stats: CitationStats)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/chat/ToolCallCard.kt` | `ToolCallCard(call: ToolCallStats, truncateArgs: Boolean)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/chat/AttachmentCard.kt` | `AttachmentCard(att: ChatAttachment, available: Boolean, onOpen, onSave)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/chat/SourceList.kt` | `SourceList(sources, materialTitles)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/chat/MessageMeta.kt` | `MessageMeta(message)` — fecha/latencia/tokens. |
| `ui/src/commonMain/kotlin/kitoru/ui/markdown/MarkdownText.kt` | `MarkdownText(text, highlight, latexMode)`, `parseMarkdown`, `buildInlineAnnotated`. |
| `ui/src/commonMain/kotlin/kitoru/ui/markdown/MathSplit.kt` | `splitMath`, `MathSegment`. |
| `ui/src/commonMain/kotlin/kitoru/ui/markdown/LatexText.kt` | `LatexText(latex, display)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/markdown/CodeBlock.kt` | `CodeBlock(lang, code, closed)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/ui/ThemedVerticalScrollbar.kt` | `ThemedVerticalScrollbar(adapter)`. |
| `ui/src/commonMain/kotlin/kitoru/ui/ui/Format.kt` | `humanSize(bytes)`, `formatDate`, `formatRelative`. |
| `ui/src/commonMain/kotlin/kitoru/ui/platform/FileDialogs.kt` | `expect interface FileDialogs { openFile(...); saveFile(...) }` + `expect fun fileDialogs(): FileDialogs`. |
| `ui/src/commonMain/kotlin/kitoru/ui/platform/FileOpener.kt` | `expect fun openInSystem(path: FilePath): Boolean`. |
| `ui/src/desktopMain/kotlin/kitoru/ui/platform/FileDialogs.desktop.kt` | `actual` con `JFileChooser`. |
| `ui/src/desktopMain/kotlin/kitoru/ui/platform/FileOpener.desktop.kt` | `actual` con `java.awt.Desktop`. |
| `ui/src/desktopMain/kotlin/kitoru/ui/platform/ChatPdfExporter.desktop.kt` | `actual` con biblioteca PDF de JVM. |
| `ui/src/androidMain/kotlin/kitoru/ui/platform/FileDialogs.android.kt` | `actual` con `ActivityResultContracts`. |
| `ui/src/androidMain/kotlin/kitoru/ui/platform/FileOpener.android.kt` | `actual` con `Intent.ACTION_VIEW`. |
| `ui/src/androidMain/kotlin/kitoru/ui/platform/ChatPdfExporter.android.kt` | `actual` que informa "no disponible en Android". |

### 9.3 View-model y estado

```kotlin
class TeacherChatsViewModel(
    private val courseId: String,
    private val teacher: UserProfile,
    private val enrollments: EnrollmentRepository,
    private val teacherChats: TeacherChatRepository,
    private val materials: MaterialRepository,
    private val audit: AccessLogRepository,
    private val packages: ImportedPackageRepository,
    private val exporter: ChatExportService,
    private val fileDialogs: FileDialogs,
) {
    val state: StateFlow<TeacherChatsUiState>
}
```

```kotlin
data class TeacherChatsUiState(
    val loading: Boolean = true,
    val course: Course? = null,
    /** Alumnos visibles (FULL y METRICS_ONLY). */
    val students: List<StudentListItem> = emptyList(),
    /** Alumnos con visibilidad NONE: sólo el número, nunca los nombres. */
    val hiddenStudentCount: Int = 0,
    val studentQuery: String = "",
    val onlyUngroundedStudents: Boolean = false,
    val selectedStudentId: String? = null,
    val threads: List<ThreadListItem> = emptyList(),
    val selectedThreadId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val filters: ChatFilters = ChatFilters(),
    val search: ChatSearchState = ChatSearchState(),
    /** Modo del visor: texto o sólo métricas. */
    val viewerMode: ViewerMode = ViewerMode.TEXT,
    val navStack: List<ChatNavLevel> = listOf(ChatNavLevel.Students),
    val auditEntries: List<AccessLog> = emptyList(),
    val importState: ImportState = ImportState.Idle,
    val exportState: ExportState = ExportState.Idle,
    val message: String = "",          // avisos efímeros (Snackbar)
)

enum class ViewerMode { TEXT, METRICS }

data class StudentListItem(
    val enrollment: Enrollment,
    val stats: StudentAnalytics?,
    val visibility: ChatVisibility,
    val importedPackages: List<ImportedPackage>,
    val accessCount: Int,              // consultas registradas (chip del escudo)
)

data class ThreadListItem(
    val thread: ChatThread,
    val summary: ThreadSummary,
    val origin: ThreadOrigin,
    val packageName: String = "",
)

data class ChatSearchState(
    val query: String = "",
    val scope: SearchScope = SearchScope.COURSE,
    val hits: List<ChatSearchHit> = emptyList(),
    val searching: Boolean = false,
    val indexReady: Boolean = false,
)

enum class SearchScope(val label: String) { COURSE("Toda la cátedra"), STUDENT("Este alumno"), THREAD("Este chat") }
```

Operaciones públicas del VM (todas con guarda de visibilidad):

```kotlin
fun selectStudent(id: String)
fun selectThread(id: String)          // si no puede leer el texto → abre el panel de métricas
fun setViewerMode(mode: ViewerMode)
fun setStudentQuery(q: String)
fun setFilters(f: ChatFilters)
fun clearFilters()
fun search(query: String, scope: SearchScope)
fun openSearchHit(hit: ChatSearchHit)
fun export(format: ExportFormat, sharedWithStudent: Boolean)
fun pickPackageToImport()
fun confirmImport(manifest: ChatPackageManifest, studentId: String)
fun deleteImportedPackage(packageId: String)
fun loadAuditLog()
fun refresh()
```

Guardas (todas en `TeacherChatPolicy`, testeables):

```kotlin
// en selectThread
if (!ChatPolicy.canReadText(enrollment, thread, pkg)) {
    // NO se llama a messagesForThread: el texto no se carga
    state = state.copy(viewerMode = ViewerMode.METRICS, messages = emptyList())
    audit.recordAccess(..., kind = AccessKind.VIEW_METRICS)
    return
}
// sí se puede leer
state = state.copy(messages = teacherChats.messagesForThread(thread.id))
audit.recordAccess(..., kind = AccessKind.VIEW_TEXT)
```

### 9.4 Puertos de plataforma

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/FileDialogs.kt
interface FileDialogs {
    /** Abre un selector de archivos. Devuelve null si se cancela. */
    suspend fun openFile(title: String, extensions: List<String>): FilePath?
    /** Guarda un archivo. Devuelve null si se cancela. */
    suspend fun saveFile(suggestedName: String, extensions: List<String>): FilePath?
}
expect fun fileDialogs(): FileDialogs
```

Los puertos se pasan al `ViewModel` por constructor (inyección manual, como
`FileSystem`), de modo que los tests del VM pueden usar un doble en memoria.

---

## 10. Casos borde

| Caso | Comportamiento |
|---|---|
| Alumno sin ningún hilo | Fila con métricas en cero; la columna de hilos dice "Este alumno todavía no tuvo conversaciones." |
| Alumno METRICS_ONLY sin datos | Fila con métricas en cero + chip "Sólo métricas"; el panel de métricas dice "Este alumno no registra actividad." |
| Hilo vacío (sin mensajes) | El visor muestra "Este chat no tiene mensajes." |
| Mensaje con `text` vacío | Se muestra "…" (igual que FunshiEngineAI). |
| Mensajes fuera de orden (streaming interrumpido) | Se ordenan por `createdAt`; a igual timestamp, por el orden de inserción en el store. |
| Alumno cambia la visibilidad mientras el docente mira | La vista se recarga al volver a la pantalla (y en cada `selectStudent`): si pasa a METRICS_ONLY, el texto visible se **limpia** de memoria y el visor pasa a métricas. |
| El docente abre un hilo de un alumno NONE (imposible por UI) | `TeacherChatRepository` lanza `ChatAccessDeniedException` y la UI muestra "No tenés permiso para ver esta conversación." |
| Importación de un paquete de otra cátedra | Se permite con advertencia explícita; el chat queda marcado con la cátedra de origen en el encabezado. |
| Importación de un paquete con chats de **otro** alumno dentro | Se ignora: sólo se importan los hilos cuyo `authorId` coincide con el alumno del manifiesto; los demás se descartan con un aviso "se descartaron 3 hilos de otro alumno". |
| Paquete muy grande (>200 MB) | Se rechaza con "El paquete es demasiado grande (240 MB). Se admiten hasta 200 MB." |
| Mensaje con un attachment que no existe | La tarjeta lo marca como no disponible (§4.5). |
| PDF en Android | El botón no aparece; el diálogo ofrece Markdown/JSON con "Compartir". |
| Búsqueda sin resultados | "No hay coincidencias." + el alcance aplicado ("en toda la cátedra" / "en este chat"). |
| La cátedra tiene `analyticsEnabled = false` | La pantalla de chats **sigue disponible** (es el derecho del docente), pero el panel de métricas agregadas muestra el aviso "La analítica está desactivada para esta cátedra." y sólo se ven los chats. |
| Un alumno aparece dos veces en la lista (mismo nombre) | Se muestran con el `userId` corto como sufijo: "Ana Ruiz (7f3a)". |

---

## 11. Criterios de aceptación

1. Con un alumno `FULL`, el docente ve sus hilos con títulos y puede abrir la
   conversación completa con burbujas, fuentes, distintivo de citas, tarjetas de
   herramientas y adjuntos.
2. Con un alumno `METRICS_ONLY`, el listado de alumnos lo muestra; la columna
   de hilos **no** muestra títulos; el visor **no** muestra texto y muestra el
   aviso "Este alumno usa IA local y no compartió sus conversaciones"; el
   paquete de mensajes del alumno **no** se carga (verificable con un test que
   afirme que `MessageRepository.messagesForThread` no fue invocado).
3. Con un alumno `NONE`, no aparece en el listado; el pie informa la cantidad.
4. Abrir un chat con texto genera una entrada `VIEW_TEXT` en `AccessLog` con
   `teacherId`, `studentId`, `threadId` y `at`; abrir el mismo chat dos veces
   en menos de 5 minutos coalesce en una entrada con `occurrences = 2`.
5. Los filtros de fecha, "sin respaldo", material y modelo reducen la lista de
   hilos y los mensajes del visor de forma consistente.
6. La búsqueda de texto encuentra un mensaje por fragmento (no por palabra
   exacta) y no encuentra nada en los alumnos sin permiso de lectura.
7. La exportación a Markdown incluye la cabecera de procedencia y reproduce el
   texto con las fuentes y el veredicto de citas; la de JSON es parseable y
   contiene el bloque `provenance`.
8. Importar un `.kitoru` válido muestra una vista previa con los datos del
   manifiesto, crea los hilos marcados como importados, los muestra con el chip
   "importado por el alumno" y deja un `AccessLog` de tipo `IMPORT`.
9. Importar un `.kitoru` inválido muestra un mensaje de error en español y no
   escribe nada en disco.
10. Las fórmulas LaTeX se muestran como texto monoespaciado legible y
    copiable, tanto en desktop como en Android, sin usar `java.awt`.

---

## 12. Dependencias con otros documentos

| Otro doc | Relación |
|---|---|
| Chat del alumno | Debe exportar el paquete `.kitoru` con el **mismo** `manifest.json` y los **mismos** serializadores (`ChatThread`/`ChatMessage`). Define además la pantalla "Auditoría de mis chats" que lee `AccessLog` filtrado por `studentId`. |
| Analítica de la cátedra | Comparte `AnalyticsAnalyzer` y `StudentAnalytics`; esta pantalla **no** recalcula métricas por alumno. La opción `Course.showPerMessageMetrics` debería definirse ahí. |
| Pantalla de alumnos / inscripciones | Es donde el docente ve (y cambia) la visibilidad por inscripción, y donde ve el padrón completo (incluidos los `NONE`). |
| Kitoru-Server | Define la API de auditoría y de descarga de chats; el `TeacherChatRepository` es el punto de cambio. |
