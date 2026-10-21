# 07 — Patrón de gestión de estado

> Subagente: diseño del **patrón de estado** de Kitoru (Compose Multiplatform).
> Módulos tocados: `:ui` (ViewModels, estado, navegación, seams de plataforma);
> `:core` (sólo lectura: `ioDispatcher`, `nowMillis`, repositorios).
> Código de origen: `FunshiEngineAI/frontend/src/main/kotlin/app/chat/*`
> (`ChatViewModel.kt`, `ChatLibrary.kt`, `ChatCatalog.kt`, `ChatCommands.kt`) y
> `Kitoru/ui/src/commonMain/kotlin/kitoru/ui/KitoruViewModel.kt`.
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable por
> defecto*, *determinista*, *fail-fast con mensajes en español*, *el estudiante
> conserva agencia*, *el docente no ve lo que no debe*.

---

## 0. Resumen ejecutivo

| # | Decisión | Alternativa descartada | Por qué |
|---|---|---|---|
| **D-1** | **Clase plana con `mutableStateOf` + `SnapshotStateList`**, instanciada con `remember { }`, con la lógica repartida en **funciones de extensión por área** | `StateFlow` + `collectAsState`; `androidx.lifecycle.ViewModel` | Compose lee el estado directamente (sin puente `collectAsState`), el valor se lee **de forma síncrona** fuera de la composición (testeable sin Compose) y no hace falta `Channel`/`SharedFlow` para los eventos de una vez |
| **D-2** | Los mensajes del chat viven en un **`SnapshotStateList`** y `updateLast` muta el último elemento | `mutableStateOf(list)` + `dropLast(1) + last().copy()` | La copia por token es **O(n)**: 400 tokens × 60 mensajes = 24.000 copias de lista y 400 invalidaciones del estado completo. Con `SnapshotStateList` es **O(1)** y sólo se recompone la burbuja que cambió |
| **D-3** | Los ViewModels son **dueños de su estado**; la lógica por área va en **funciones de extensión** (`internal`) en archivos siblings | Un ViewModel de 800 líneas | Es el patrón de `ChatLibrary.kt` / `ChatCatalog.kt` de FunshiEngineAI; los archivos se pueden testear por separado |
| **D-4** | **Un `CoroutineScope` por ViewModel**, inyectado por constructor; el estado se escribe en el hilo de UI, el I/O en `kitoru.platform.ioDispatcher` | `Dispatchers.IO` adentro del ViewModel | `Dispatchers.IO` no existe en `commonMain`; el dispatcher se inyecta para que el test sea determinista |
| **D-5** | El lazo del agente corre en el dispatcher de fondo y **las escrituras al estado se encolan al hilo de UI** (`postToMain`) | Escribir el estado desde el hilo de fondo (como hace FunshiEngineAI) | Las herramientas pueden escribir archivos (E/S bloqueante): no pueden correr en el hilo de UI. Y el estado se escribe en **un solo hilo**, que es la forma de razonar sin carreras |
| **D-6** | **Guard de identidad** (`Origin(courseId, threadId)`) en cada callback del streaming | Escribir siempre en el estado actual | Es el patrón `originChatId` de FunshiEngineAI: la respuesta de un hilo no se pinta en otro |
| **D-7** | Errores: **`AppError?` (banner global, nullable)** + **`LoadState<T>` selado** para las pantallas con ciclo carga/datos/error | Un `String` vacío; un `UiState` gigante de la app entera | `""` es ambiguo; un `sealed` global obligaría a tocar el estado raíz por cada pantalla nueva |
| **D-8** | **Pila de navegación manual** (`NavStack<AppScreen>`) + `PlatformBackHandler` (expect/actual) | `navigation-compose` | El grafo es chato (11 pantallas) y el `NavStack` es testeable en `commonTest` sin Compose. **Nota de precisión**: CMP 1.7.3 *sí* trae navigation multiplatform (ver §7.2): el motivo real es la madurez de la API y el doble mecanismo, no la falta de soporte KMP |
| **D-9** | Los ViewModels se **cierran explícitamente** (`close()`), con `DisposableEffect` en la UI | `viewModelScope` automático | El `ViewModel` de AndroidX no resuelve nada aquí (el estado vive en los repositorios) ⇒ el ciclo de vida lo escribimos nosotros (ver §5) |

### 0.1 Diagrama de capas

```
┌──────────────────────────────────────────────────────────────────────┐
│ :ui  ·  composables (dumb)                                          │
│   KitoruApp → AppShell → StudentChatScreen / TeacherAnalyticsPane …  │
│   · leen estado, llaman acciones. NO tocan repositorios.            │
└───────────────▲──────────────────────────────────────────────────────┘
                │ lee (mutableStateOf / SnapshotStateList)
                │ llama (acciones)
┌───────────────┴──────────────────────────────────────────────────────┐
│ :ui  ·  ViewModel (dueño del estado) + extensiones por área          │
│   KitoruViewModel + KitoruNavigation.kt + KitoruProfile.kt           │
│   ChatViewModel   + ChatThreads.kt + ChatBoot.kt + ChatPermissions.kt│
│   TeacherViewModel+ TeacherMaterials.kt + TeacherAnalytics.kt + …    │
│   · estado: `mutableStateOf` / `SnapshotStateList`                   │
│   · concurrencia: `scope` (hilo UI) + `io` (fondo)                   │
└───────────────▲──────────────────────────────────────────────────────┘
                │ llama (suspend)
┌───────────────┴──────────────────────────────────────────────────────┐
│ :core  ·  dominio + repositorios   │  :backend  ·  RAG + agente      │
│   Course, Enrollment, ChatMessage  │   RagEngine, AgentLoop,         │
│   CourseRepository, MessageRepo…   │   CitationVerifier, Bm25Index   │
│   (puro, sin Compose)              │   (puro, sin Compose)           │
└───────────────▲──────────────────────────────────────────────────────┘
                │ usa
┌───────────────┴──────────────────────────────────────────────────────┐
│ :llm-api  ·  LlmGateway (OpenAI-compatible, local, MCP)             │
└──────────────────────────────────────────────────────────────────────┘
```

**Regla de oro**: `mutableStateOf` aparece **sólo** en `:ui`, y **sólo** dentro de
un ViewModel. Ningún composable declara estado propio salvo el efímero de la
composición (`remember { }` local a un componente). Ningún `:core`/`:backend`
importa `androidx.compose.runtime`.

---

## 1. El patrón: `mutableStateOf` + funciones de extensión por área

### 1.1 Las tres candidatas

#### (a) `mutableStateOf` en clases planas + extensiones por área — el de FunshiEngineAI

```kotlin
class ChatViewModel {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var messages by mutableStateOf(listOf<UiMessage>())
        internal set
    var busy by mutableStateOf(false)
        internal set
    ...
}

// ChatLibrary.kt — la lógica en funciones de extensión:
internal fun ChatViewModel.switchChat(id: String) { ... }
internal fun ChatViewModel.persistActiveChat() { ... }
```

Ventajas:

- **Cero dependencias**: `mutableStateOf` es `androidx.compose.runtime`, que ya
  está en `commonMain` de `:ui`.
- **Lectura síncrona**: `vm.error` devuelve el valor actual, sin `collectAsState`
  y sin un frame de retraso. Esto es lo que hace testeable al ViewModel (§9).
- **Un solo mechanism**: no hay dos sistemas de estado conviviendo.
- **Portado**: es literalmente el patrón de FunshiEngineAI (regla 1).

Desventajas:

- `var` público con setter: nada impide escribir desde donde sea.
- El ciclo de vida del `scope` hay que administrarlo a mano.

#### (b) `StateFlow` + `collectAsState`

```kotlin
class ChatViewModel {
    private val _state = MutableStateFlow(ChatState.Idle)
    val state: StateFlow<ChatState> = _state.asStateFlow()
}

@Composable
fun ChatScreen(vm: ChatViewModel) {
    val state by vm.state.collectAsState()   // ← el "puente"
    ...
}
```

Ventajas:

- `StateFlow` es **hot**: si nadiecollected, el valor sigue siendo el último
  (`state.value`), y el back-pressure/confluencia son gratis.
- Encaja con la corrutina: `state.first { it is Ready }`, `combine`, `map`.

Desventajas (y son las que deciden):

1. **El puente `collectAsState` cuesta un turno de recomposición.** La
   `collectAsState` lanza una corrutina en el ámbito de la recomposición; el
   valor nuevo llega a la UI **un frame después** de que se emitió. Con
   `mutableStateOf` el valor está disponible en la siguiente pasada de
   composición, sin intermediario.
2. **Un `StateFlow` es estado, no evento.** Los eventos de una vez (un banner de
   error, un snackbar, "se guardó") necesitan `Channel` o `SharedFlow` aparte.
   Con `SharedFlow(replay = 0)` el evento **se pierde** si nadie está
   colectando en ese instante (típico: el error ocurre mientras la pantalla
   está en segundo plano). Con `Channel` hay que elegir `receiveAsFlow` vs
   `consumeAsFlow` y acordarse de cerrarlo.
   Con `mutableStateOf` el "evento" es un `var` que la UI lee y borra
   (`vm.clearError()`): no hay pérdida posible.
3. **Coalescencia silenciosa.** `StateFlow` confluye: si el estado cambia tres
   veces rápido, el colector ve el último. Con `mutableStateOf` + recomposición
   por frame, Compose también coalesce, pero **el estado intermedio sigue
   existiendo** y se puede leer en un test.
4. **Doble fuente de verdad.** Termina habiendo `MutableStateFlow` *y*
   `mutableStateOf` en el mismo ViewModel, con dos semánticas distintas.

> Matiz honesto: la invalidación fina de Compose funciona **igual** con las dos
> opciones (cada `collectAsState` crea su propio `MutableState`, así que sólo
> se recompone el composable que lee ese estado). La ventaja de `mutableStateOf`
> no es la granularidad sino **la ausencia del puente**: sin colector, sin
> corrutina, sin un frame de retraso, sin evento que se pueda perder.

#### (c) `androidx.lifecycle.ViewModel`

> **Precisión**: el artefacto de AndroidX (`androidx.lifecycle:lifecycle-viewmodel`)
> es Android-only, pero Compose Multiplatform **publica la versión multiplatform**
> (`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.8.3`, que CMP 1.7.3
> ya trae en su bundle). O sea: **el `ViewModel` de AndroidX sí existe en el
> target común**. La razón para no usarlo no es la inexistencia, es la que sigue.

Ventajas (reales):

- `onCleared()` se llama solo cuando muere el dueño ⇒ **cancelación
  automática** de los jobs.
- Sobrevive a los cambios de configuración de Android (rotación).
- `SavedStateHandle` para el estado que debe sobrevivir a la muerte del proceso.

Desventajas en Kitoru:

1. **El dueño (`ViewModelStoreOwner`) no existe en desktop.** En desktop hay que
   cablear `LocalViewModelStoreOwner` a mano; en Android sale gratis de la
   Activity. Eso significa **dos caminos de cableado** para el mismo código.
2. **`viewModel()` es un composable de `lifecycle-viewmodel-compose`**, otra
   dependencia, y su API KMP es más reciente que la de `compose.runtime`.
3. **El estado de Kitoru ya está persistido en los repositorios** (JSON en
   `KitoruPaths.configDir`). El `ViewModel` es una **proyección en memoria** de
   los repositorios, no la fuente de verdad ⇒ la rotación no pierde nada: se
   recarga del disco. `SavedStateHandle` sobra para el 95 % de los casos
   (sólo ayudaría con el texto del redactor a medio escribir).
4. Es el patrón de **Android**, y Kitoru es una app de escritorio con Android
   como segundo target. La regla 2 del CONTEXTO (*portable por defecto*) pide
   que el núcleo de la app no dependa de una plataforma.

**Veredicto**: `ViewModel` de AndroidX es razonable si la app fuera Android-only.
Kitoru es multiplatform ⇒ clase plana.

### 1.2 Comparación lado a lado

| Criterio | (a) `mutableStateOf` | (b) `StateFlow` | (c) `ViewModel` |
|---|---|---|---|
| Funciona igual en desktop y Android | ✅ | ✅ | ⚠️ hay que cablear el `ViewModelStoreOwner` en desktop |
| Recomposición sin recomponer de más | ✅ sin puente | ⚠️ un frame de retraso | ✅ |
| Eventos de una vez (banner, snackbar) | ✅ un `var` que la UI borra | ❌ necesita `Channel`/`SharedFlow` | ✅ |
| Lectura síncrona fuera de la composición | ✅ | ✅ (`.value`) | ✅ |
| Testeable sin Compose | ✅ | ✅ | ✅ |
| Cancelación automática al morir la pantalla | ❌ hay que escribirla | ❌ hay que escribirla | ✅ |
| Sobrevive a la rotación en Android | ❌ (se recarga del disco) | ❌ | ✅ |
| Dependencias extra | ninguna | ninguna | `lifecycle-viewmodel(-compose)` |
| Tamaño del código | el menor | el mayor (flujo + colectores + eventos) | intermedio |

### 1.3 La recomendación

**Se elige (a): clase plana con `mutableStateOf` + `SnapshotStateList`, creada con
`remember { }`, con la lógica en funciones de extensión por área.**

El criterio que manda es uno solo, y es el que enuncia Compose Multiplatform:
**el estado de la UI es estado de Compose, no un flujo**. `mutableStateOf` es el
idioma nativo del runtime de Compose: la UI lo lee directamente en la
composición y el runtime invalida **exactamente** a quien lo leyó. `StateFlow`
agrega una capa de traducción (`collectAsState`) que no compra nada para el
estado de una pantalla, y obliga a un segundo mecanismo para los eventos.
`ViewModel` de AndroidX resuelve un problema (ciclo de vida) que en Kitoru no
existe, porque el estado de la app vive en los repositorios y el ViewModel es
una caché.

### 1.4 Los límites de esta decisión (explícitos)

Los límites son parte de la decisión: si no se nombran, aparecen como bugs.

| # | Límite | Consecuencia | Mitigación |
|---|---|---|---|
| L-1 | **El estado no sobrevive a la muerte del proceso** ni a la rotación en Android (`remember` se pierde) | Al rotar el teléfono, la pantalla se reconstruye | El estado real vive en los repositorios (JSON); el ViewModel se reconstruye leyendo de ahí. Lo único que se pierde es lo efímero (texto del redactor a medias) ⇒ `rememberSaveable` para ese campo |
| L-2 | **El ciclo de vida es manual**: `scope.cancel()` hay que llamarlo | Un ViewModel olvidado deja jobs colgados (y en desktop, un `llama-server` vivo) | Todo ViewModel con `scope` tiene `close()`; la UI lo llama en `DisposableEffect`. Hay un test que verifica que `close()` cancela el job |
| L-3 | **`var` con setter**: nada impide escribir el estado desde cualquier lado | Escrituras cruzadas → carreras | `internal set` (no `private set`) + la regla "el estado sólo se escribe en el hilo de UI" (§4.2) |
| L-4 | **No hay `SavedStateHandle`**: no se restaura "la pantalla exacta" tras muerte del proceso | El usuario vuelve al home, no al chat | Aceptado: en el hito 1 el chat se reabre por el hilo, no por la pantalla exacta. Si hace falta, `rememberSaveable` con el `threadId` |
| L-5 | **Un solo dueño por estado**: dos ViewModels no escriben el mismo `mutableStateOf` | Carrera si se rompe la regla | El estado de cada pantalla vive en **su** ViewModel; el estado raíz (`KitoruViewModel`) es de sólo lectura para los hijos |
| L-6 | **El ViewModel no es serializable**: no se puede pasar por `SavedStateHandle` ni por un Intent | — | Consecuencia directa de L-1; se acepta |

### 1.5 La corrección mínima al `KitoruViewModel` actual

Hoy está escrito así:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/KitoruViewModel.kt  (actual)
class KitoruViewModel(
    private val courses: CourseRepository,
    private val enrollments: EnrollmentRepository,
    private val profiles: ProfileRepository,
) {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var profile by mutableStateOf<UserProfile?>(null)
        private set                                   // ← (1) bloquea las extensiones
    var screen by mutableStateOf<AppScreen>(AppScreen.RolePicker)
        private set                                   // ← (1)
    ...
}
```

Para que el patrón de las extensiones por área funcione hacen falta **tres
cambios**:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/KitoruViewModel.kt  (propuesto)
class KitoruViewModel(
    parentScope: CoroutineScope,                        // ← (2) inyectado
    private val courses: CourseRepository,
    private val enrollments: EnrollmentRepository,
    private val profiles: ProfileRepository,
    private val io: CoroutineDispatcher = kitoru.platform.ioDispatcher,   // ← (3)
) {
    /**
     * Ámbito de corrutinas de este ViewModel.
     *
     * Hereda el **dispatcher** del scope padre (en producción `Dispatchers.Main`
     * vía `rememberCoroutineScope()`) y crea su propio `SupervisorJob` hijo:
     * un fallo en una carga no cancela a las demás, y `close()` cancela todo
     * de una.
     */
    internal val scope: CoroutineScope = CoroutineScope(
        SupervisorJob(parentScope.coroutineContext[Job]) +
            (parentScope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Main),
    )

    /** Se cerró el ViewModel: no se aceptan más escrituras (§5.3). */
    internal var closed: Boolean = false
        private set

    var profile by mutableStateOf<UserProfile?>(null)
        internal set                                    // ← (1) `internal set`
    var screen by mutableStateOf<AppScreen>(AppScreen.RolePicker)
        internal set
    var error by mutableStateOf<AppError?>(null)        // ← nullable, ver §6
        internal set
    ...
    /** Cierra el ViewModel: cancela los jobs en vuelo. */
    fun close() {
        closed = true
        scope.cancel()
    }
}
```

> **(1) `private set` vs `internal set` — el detalle que hace que todo el
> patrón funcione.** Una función de extensión **no** puede asignar a una
> propiedad con `private set` (el setter es privado de la clase). Con
> `internal set`, las extensiones del mismo módulo sí pueden escribir. Es
> exactamente por eso que FunshiEngineAI usa `internal set` en todo el
> `ChatViewModel`. El control de acceso sigue siendo estricto: `internal` = sólo
> el módulo `:ui`.
>
> **(2) y (3)** son los requisitos de testeabilidad (§9.2).

---

## 2. La lista de mensajes del chat: `SnapshotStateList`

### 2.1 El costo del patrón actual

El `updateLast` de FunshiEngineAI (y el que se está portando) es:

```kotlin
internal fun updateLast(text: String, sources: List<String> = emptyList(), ...) {
    if (messages.isEmpty()) return
    messages = messages.dropLast(1) + messages.last().copy(text = text, ...)
}
```

Por token:

| Operación | Costo |
|---|---|
| `messages.dropLast(1)` | copia n−1 elementos → O(n) |
| `+ messages.last().copy(...)` | otra lista nueva → O(n) + 1 allocación |
| `messages = ...` | **invalida el estado `messages` completo** |

Con una respuesta de 400 tokens en un hilo de 60 mensajes:

- 400 asignaciones de listas de 60 elementos = **24.000 copias de referencia**.
- 400 invalidaciones del objeto de estado `messages` ⇒ **todo** composable que
  leyó la lista se invalida: la `LazyColumn` reconstruye su proveedor de ítems
  y vuelve a medir la columna **en cada token**.
- 400 objetos `UiChatMessage` nuevos (el `copy`).

El problema de fondo no es sólo el coste: **el objeto de estado es la lista
entera**, así que no hay forma de que Compose sepa *qué* cambió.

### 2.2 La solución: `SnapshotStateList`

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/chat/UiChatMessage.kt
package kitoru.ui.chat

import kitoru.domain.CitationStats
import kitoru.domain.SourceRef
import kitoru.domain.ToolCallStats

/**
 * Un mensaje tal como lo ve la UI.
 *
 * Es el `UiMessage` de FunshiEngineAI con los tipos del **dominio** de Kitoru.
 * No se serializa: la persistencia la hace `kitoru.domain.ChatMessage`.
 */
data class UiChatMessage(
    val id: String,
    val role: String,                       // "user" | "assistant"
    val text: String,
    val sources: List<SourceRef> = emptyList(),
    val citation: CitationStats? = null,
    val toolCalls: List<ToolCallStats> = emptyList(),
    /** true mientras el modelo está escribiendo este mensaje. */
    val streaming: Boolean = false,
    /** "MATERIAL" | "LIBRE": con qué modo se generó. */
    val mode: String = "MATERIAL",
)
```

```kotlin
// En el ViewModel:
import androidx.compose.runtime.mutableStateListOf

/**
 * Los mensajes de la conversación.
 *
 * Es un `SnapshotStateList` (no un `mutableStateOf(list)`) a propósito: el
 * `updateLast` de FunshiEngineAI reemplaza la lista **entera** en cada token
 * (`messages = messages.dropLast(1) + messages.last().copy(...)`), lo que con
 * 60 mensajes son 60·N copias de lista por respuesta. Con `SnapshotStateList`
 * la mutación del último mensaje es O(1) y Compose sólo recompone la burbuja
 * que cambió.
 */
val messages = mutableStateListOf<UiChatMessage>()
```

Y el `updateLast`:

```kotlin
/**
 * Actualiza el último mensaje (el que se está escribiendo).
 *
 * **O(1)**: se muta un elemento de la lista, no se reemplaza la lista. Es el
 * `updateLast` de FunshiEngineAI con la diferencia exacta de rendimiento.
 *
 * La semántica es idéntica: durante el streaming se pisan `sources` y
 * `toolCalls` con los valores acumulados hasta el momento, y `citation` queda
 * en `null` hasta que la respuesta termina (recién ahí se pasa el informe de
 * verificación de citas).
 */
internal fun updateLast(
    text: String,
    sources: List<SourceRef> = emptyList(),
    toolCards: List<ToolCallStats> = emptyList(),
    citation: CitationStats? = null,
) {
    val last = messages.lastIndex
    if (last < 0) return
    messages[last] = messages[last].copy(
        text = text,
        sources = sources,
        toolCalls = toolCards,
        citation = citation,
    )
}
```

### 2.3 Cómo se lee en la `LazyColumn` (la parte que hace que el O(1) sirva)

```kotlin
@Composable
fun MessageList(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            items = vm.messages,                 // ← SnapshotStateList
            key = { _, m -> m.id },              // ← identidad estable
        ) { _, m ->
            MessageBubble(m)                     // ← lee m, no la lista
        }
    }
}
```

Por qué funciona:

1. `itemsIndexed` con `key = { it.id }` da **identidad estable** por mensaje: al
   cambiar el texto del último mensaje, Compose no recrea el ítem, lo *recompone*.
2. La lectura `messages[i]` ocurre **dentro del ámbito del ítem**, así que la
   invalidación es por elemento: sólo se recompone la burbuja que cambió. Las
   otras 59 no se tocan.
3. `messages.size` se lee en el ámbito de la `LazyColumn` (para el proveedor de
   ítems): un cambio de tamaño invalida la lista, pero eso pasa **dos veces por
   respuesta** (pregunta + placeholder), no en cada token.

> Con `mutableStateOf(list)`, en cambio, el objeto de estado es la lista
> **completa**: cada token la invalida entera.

### 2.4 Reglas de uso del `SnapshotStateList`

1. **Se muta, no se reemplaza.** `messages.add(...)`, `messages[i] = ...`,
   `messages.removeAt(i)`. **Nunca** `messages = listOf(...)` (no compila: es
   `val`). Para reemplazar todo: `messages.clear(); messages.addAll(nuevos)`.
2. **Se itera sólo desde el hilo de UI.** `SnapshotStateList` no tolera que otro
   hilo la recorra mientras se la muta (`ConcurrentModificationException`). La
   regla del §4.2 (un solo hilo de escritura) lo garantiza. Para pasar la
   conversación al motor, se **copia** a una `List` inmutable en el hilo de UI:
   `val history = messages.filter { it.text.isNotBlank() }.map { ... }`.
3. **`key` por id, siempre.** Sin `key`, Compose usa el índice y el mensaje
   "se mueve" de lugar al editar el historial.
4. **Nada de listas dentro de un `mutableStateOf` que también se muta.** Si un
   sub-elemento cambia, se reemplaza el `data class` completo (que es lo que
   hace `copy()`).

### 2.5 Qué **no** va en un `SnapshotStateList`

- **Los catálogos que no cambian durante la pantalla** (modelos disponibles,
  lista de cátedras): `mutableStateOf(listOf(...))` alcanza. La lista se
  reemplaza una vez y listo.
- **El historial que se le pasa al motor**: `List` normal e inmutable (se
  construye en el hilo de UI y no se muta).
- **Lo que se persiste**: `kitoru.domain.ChatMessage` (serializable), no el
  tipo de UI. Hay un mapeo explícito:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/chat/UiChatMessageMapping.kt
package kitoru.ui.chat

import kitoru.domain.ChatMessage as DomainMessage
import kitoru.domain.CitationStats as DomainCitation
import kitoru.domain.ToolCallStats as DomainToolCall

/** De la UI al dominio (lo que se persiste). */
fun UiChatMessage.toDomain(threadId: String, createdAt: Long): DomainMessage =
    DomainMessage(
        id = id,
        threadId = threadId,
        role = role,
        text = text,
        createdAt = createdAt,
        sources = sources.map { it.label },
        citation = citation?.let { DomainCitation(it.verified, it.unsupported, it.unknown) },
        toolCalls = toolCalls.map { DomainToolCall(it.name, it.args, it.ok, it.result, it.durationMs) },
    )

/** Del dominio a la UI (lo que se pinta). */
fun DomainMessage.toUi(): UiChatMessage =
    UiChatMessage(
        id = id,
        role = role,
        text = text,
        sources = sources.map { SourceRef(label = it) },
        citation = citation?.let { DomainCitation(it.verified, it.unsupported, it.unknown) },
        toolCalls = toolCalls.map { DomainToolCall(it.name, it.args, it.ok, it.result, it.durationMs) },
    )
```

> **Tipo pendiente en el dominio**: `SourceRef` (índice, etiqueta, título del
> material, encabezado) no existe todavía en `kitoru.domain` — el dominio
> actual guarda `sources: List<String>` (la etiqueta). `SourceRef` está
> propuesto en `docs/design/student/02-chat.md` §3.1 y hay que agregarlo a
> `core/src/commonMain/kotlin/kitoru/domain/Chat.kt` antes de compilar los
> mapeos de arriba. Si se decide no agregarlo, los mapeos se simplifican a
> `sources.map { SourceRef(label = it) }` sobre un tipo más chico.

---

## 3. La separación de la UI de la lógica

### 3.1 Las cuatro capas y qué puede ver cada una

| Capa | Puede ver | **No** puede |
|---|---|---|
| `:core` dominio | datos, reglas puras, repositorios (interfaces) | Compose, corrutinas con estado, `Dispatchers` |
| `:core` plataforma | `FilePath`, `FileSystem`, `ioDispatcher`, `nowMillis` | lógica de dominio |
| `:backend` | RAG, agente, verificador de citas | Compose, estado de UI |
| `:ui` ViewModel | dominio, backend, `compose.runtime` | tocar el disco directamente (va por repositorios) |
| `:ui` composables | el ViewModel | repositorios, backend, `Dispatchers` |

### 3.2 El ViewModel: qué tiene y qué no

**Tiene** (y es todo lo que tiene):

- El **estado** (`mutableStateOf` / `SnapshotStateList`).
- Las **acciones** que son parte de la máquina de estados: `send`, `cancel`,
  `switchThread`, `go`, `back`.
- Las **guardas**: no permitir una acción si el rol no da permiso.
- Las **referencias** a los colaboradores (repositorios, motor), **inyectadas**.

**No tiene**:

- Lógica de formato de textos para la UI (va en `kitoru.ui.core.Formatting`).
- Llamadas al sistema de archivos (van por `kitoru.platform.FileSystem`).
- Cálculos de analítica (van por `AnalyticsAnalyzer`, que ya es puro en `:core`).
- Reglas de negocio duplicadas: si una regla ya está en el dominio
  (`ModelPolicy.resolveFor`, `ChatVisibility.showsText`), la UI **la usa**, no
  la reimplementa.

### 3.3 Las funciones de extensión por área (el patrón de FunshiEngineAI)

FunshiEngineAI reparte la lógica de `ChatViewModel` en archivos siblings:

| Archivo de FunshiEngineAI | Qué contiene | Extensión en Kitoru |
|---|---|---|
| `ChatViewModel.kt` | estado + envío + tema | `ChatViewModel.kt` (estado + envío) |
| `ChatLibrary.kt` | árbol de chats/carpetas + persistencia | `ChatThreads.kt` (hilos: crear, renombrar, borrar, cambiar de hilo, persistir) |
| `ChatEngine.kt` | arranque del motor, gateway, documentación | `ChatBoot.kt` (gateway, índice RAG, prueba de conexión) |
| `ChatCatalog.kt` | catálogo de modelos y APIs remotas | `ChatModels.kt` (modelos remotos disponibles, probador de conexión) |
| `ChatCommands.kt` | comandos `/help`, `/model`… | **no se porta** (en Kitoru el modelo se configura en una pantalla, no con comandos — ver `student/02-chat.md` §1) |
| `ChatTools.kt` / `ChatPermissions.kt` | herramientas y permisos por categoría | `ChatPermissions.kt` (permiso por categoría + techo de la cátedra) |

Reglas de la convención:

1. **Una extensión = una acción.** Cada función de extensión hace **una** cosa
   observable. Si necesita tres pasos, son tres extensiones o es una acción
   compuesta con un nombre claro.
2. **Las extensiones pueden leer y escribir el estado del ViewModel** (por eso
   `internal set`, §1.5). No pueden tocar `private`.
3. **Las extensiones no declaran estado nuevo.** Si un área necesita estado
   propio, tiene su propio ViewModel.
4. **Todas son `internal`**: no son parte de la API pública de `:ui`.
5. **Tope de tamaño**: un archivo de ViewModel no pasa de ~400 líneas. Pasado ese
   límite, se extrae a una extensión o a un archivo de UI.
6. **Una extensión no abre archivos**: si necesita leer o escribir en disco, lo
   hace por un repositorio (nunca por `kitoru.platform.FileSystem` directo).
7. Las extensiones se **testean** como funciones: son funciones sobre el
   ViewModel (con repositorios falsos).

### 3.4 El mapa de archivos de `:ui`

```
ui/src/commonMain/kotlin/kitoru/ui/
├── KitoruApp.kt                     @Composable raíz; crea los ViewModels
├── KitoruViewModel.kt               estado raíz (perfil, inscripciones, cátedras)
├── KitoruNavigation.kt              extensiones: push/pop/replace de la pila
├── KitoruProfile.kt                 extensiones: crear/editar el perfil local
├── core/
│   ├── AppScreen.kt                 sealed class de pantallas (ya existe)
│   ├── NavStack.kt                  la pila de navegación manual
│   ├── AppError.kt                  error accionable para el usuario
│   ├── LoadState.kt                 sealed interface LoadState<T>
│   └── Formatting.kt                humanSize(), humanDate(), relativeTime()
├── chat/
│   ├── ChatViewModel.kt             estado + envío del chat
│   ├── ChatThreads.kt               extensiones: hilos y persistencia
│   ├── ChatBoot.kt                  extensiones: arranque del motor y gateway
│   ├── ChatPermissions.kt           extensiones: permisos por categoría
│   └── UiChatMessage.kt             el tipo de mensaje de la UI + mapeos
├── teacher/                         (según teacher/01-navegacion.md)
│   ├── TeacherViewModel.kt
│   ├── TeacherNavState.kt
│   ├── TeacherMaterials.kt  TeacherAnalytics.kt  TeacherStudents.kt …
├── student/
│   ├── StudentViewModel.kt
│   └── StudentHome.kt
├── settings/                        compartido por los dos roles
├── components/                      StatTile, EmptyState, ErrorBanner, …
└── platform/
    ├── FileSaver.kt                 expect/actual (ya existe)
    └── BackHandler.kt               expect/actual del botón "atrás"
```

### 3.5 Qué se testea, y dónde

| Capa | Qué se testea | Dónde | Cómo |
|---|---|---|---|
| `:core` | reglas puras: `ModelPolicy.resolveFor`, `validate`, `AnalyticsAnalyzer`, `ChatVisibility` | `core/src/commonTest` | test unitario puro |
| `:core` | repositorios sobre `FileSystem` | `core/src/commonTest` | `FileSystem` falso en memoria |
| `:backend` | BM25, chunker, verificador de citas, `AgentLoop` | `backend/src/desktopTest` (ya existe) | JUnit 5 |
| `:ui` | **ViewModels**: transiciones de estado, guardas de permiso, encolado, cancelación, guard de hilo | `ui/src/commonTest` | `runTest` + repositorios falsos (§9) |
| `:ui` | componentes con lógica de layout (el `followToBottom`, el scroll) | `ui/src/commonTest` | función pura aparte del composable + `runComposeUiTest` sólo si hace falta |
| `:ui` | componentes puramente visuales | opcional | `runComposeUiTest` (CMP 1.7.3 lo soporta en common) |

**Regla**: si una regla se puede testear sin Compose, se testea sin Compose. Los
`mutableStateOf` **no necesitan recomposición para leerse**: `vm.error` devuelve
el valor actual en cualquier hilo, sin `@Composable`, sin `Snapshot.sendApplyNotifications`.

---

## 4. Concurrencia

### 4.1 Un `CoroutineScope` por ViewModel

```kotlin
internal val scope: CoroutineScope = CoroutineScope(
    SupervisorJob(parentScope.coroutineContext[Job]) +
        (parentScope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Main),
)
```

- **`SupervisorJob`**: un fallo en una corrutina (una carga que no encuentra
  cátedras, un test de conexión que falla) **no** cancela a las sisters. Sin
  supervisor, un `throw` en un `launch` tumbaría todo el scope.
- **El dispatcher se hereda, no se escribe**: en producción el padre es
  `rememberCoroutineScope()` ⇒ `Dispatchers.Main.immediate`. En tests el padre
  es el `TestScope` de `runTest` ⇒ `StandardTestDispatcher`. Un solo lugar decide
  en qué hilo corre la UI.
- **El `Job` es hijo del padre**: cuando la pantalla sale de la composición, el
  scope padre se cancela y con él este.

### 4.2 La regla del hilo de estado

> **El estado de Compose se escribe en un solo hilo: el hilo de UI.**

| Operación | Dónde |
|---|---|
| Escribir `mutableStateOf` / mutar `SnapshotStateList` | hilo de UI |
| `courses.list()`, `messages.save(...)`, `profiles.save(...)` | dispatcher de fondo |
| `AgentLoop.run(...)` (incluye las herramientas) | dispatcher de fondo |
| `RagEngine.prepare(...)`, `RagEngine.verify(...)` | dispatcher de fondo |

**Por qué no se escribe el estado desde el hilo de fondo.** Compose permite
escribir el estado desde cualquier hilo (el sistema de snapshots es MVCC y
thread-safe), pero un modelo de un solo escritor es mucho más fácil de razonar:
no hay que preguntarse "¿qué pasa si el callback del stream y el clic del
usuario escriben a la vez?". Además, la invalidación desde un hilo
secundario se aplica en el próximo frame, lo que produce recomposiciones
impredecibles.

Por eso el patrón de Kitoru **no** copia el de FunshiEngineAI (que escribe el
estado desde el hilo del `withContext(Dispatchers.IO)`), sino que lo mejora:

```kotlin
/**
 * Encola una escritura de estado en el hilo de UI.
 *
 * El lazo del agente corre en el dispatcher de fondo (las herramientas pueden
 * escribir archivos: E/S bloqueante que no puede ir en el hilo de UI), así que
 * sus callbacks llegan desde ese hilo. El estado de Compose se escribe
 * **siempre** en el hilo de UI: por eso el callback encola y no escribe directo.
 *
 * El orden se conserva: todas las llamadas a `postToMain` salen del mismo hilo
 * y se encolan en el mismo dispatcher ⇒ se ejecutan en orden.
 */
internal fun postToMain(block: () -> Unit) {
    if (closed) return
    scope.launch { block() }
}
```

### 4.3 El dispatcher de fondo

**En `commonMain` no existe `Dispatchers.IO`.** (`Dispatchers.IO` es una
declaración de la JVM.) Kitoru ya lo resuelve:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/Dispatchers.kt
expect val ioDispatcher: CoroutineDispatcher
```

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/platform/Dispatchers.jvm.kt
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
```

Reglas:

- `Dispatchers.IO` **nunca** se escribe en `commonMain`.
- `Dispatchers.Default` es para CPU (no bloqueante): el `Bm25Index` es CPU
  puro, pero es rápido (milisegundos) y no amerita mudarlo de hilo.
- El dispatcher de fondo **se inyecta** en el ViewModel con default
  `kitoru.platform.ioDispatcher`, para que el test pueda substituting un
  `TestDispatcher` y ser determinista.

### 4.4 El `AgentLoop`

```kotlin
// En el ViewModel del chat:
private fun sendInternal(text: String, history: List<LlmChatMessage>) {
    busy = true
    error = null
    chatJob?.cancel()

    // Hilo al que pertenece esta respuesta: si mientras generamos se cambia
    // de hilo, los callbacks NO tocan el hilo nuevo (§5.2).
    val originThreadId = threadId

    chatJob = scope.launch {
        try {
            // 1) Recuperación y armado del prompt: CPU + E/S, sin estado.
            val prepared = withContext(io) {
                engine.prepare(text, history, useCourseMaterial)
            }
            val sourceRefs = prepared.sources.mapIndexed { i, c ->
                SourceRef(index = i + 1, label = c.label, materialTitle = c.source, heading = c.heading)
            }

            val loop = AgentLoop(gateway, tools, nativeToolCalls = native, toolTimeoutMs = toolTimeoutMs)
            val sb = StringBuilder()
            var cards = listOf<ToolCallStats>()

            val ctx = ToolContext(
                chatId = originThreadId,
                outputsDir = outputsDirFor(originThreadId),
                grantedCategories = grantedCategories,
                requestPermission = { req -> askPermission(req) },
            )

            // 2) El lazo corre en el dispatcher de fondo: las herramientas
            //    escriben archivos y no pueden bloquear el hilo de UI.
            //    El gateway ya hace `flowOn(ioDispatcher)`, así que la red no
            //    bloquea nada.
            val answer = withContext(io) {
                loop.run(prepared.messages, ctx) { ev ->
                    when (ev) {
                        is AgentEvent.Text -> {
                            sb.append(ev.delta)
                            // 3) La escritura del estado va al hilo de UI.
                            postToMain {
                                if (threadId == originThreadId) {
                                    updateLast(sb.toString(), sourceRefs, cards)
                                }
                            }
                        }
                        is AgentEvent.ToolStart -> {
                            cards = cards + ToolCallStats(ev.name, ev.argsJson, null, "", 0L)
                            postToMain {
                                if (threadId == originThreadId) {
                                    updateLast(sb.toString(), sourceRefs, cards)
                                }
                            }
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
                            postToMain {
                                if (threadId == originThreadId) {
                                    updateLast(sb.toString(), sourceRefs, cards)
                                }
                            }
                        }
                    }
                }
            }

            // 4) Verificación de citas: determinista, sin red ni modelo.
            val citation = if (useCourseMaterial && prepared.sources.isNotEmpty()) {
                withContext(io) { engine.verify(answer.text, prepared.sources) }
                    .let { CitationStats(it.verified, it.unsupported, it.unknownCount) }
            } else {
                null
            }

            postToMain {
                if (threadId == originThreadId) {
                    val last = messages.lastIndex
                    if (last >= 0) messages[last] = messages[last].copy(streaming = false)
                    updateLast(answer.text, sourceRefs, cards, citation)
                }
            }
        } catch (e: CancellationException) {
            postToMain {
                if (threadId == originThreadId) {
                    val last = messages.lastIndex
                    if (last >= 0) messages[last] = messages[last].copy(text = "(detenido)", streaming = false)
                }
            }
            throw e
        } catch (e: Exception) {
            postToMain {
                if (threadId == originThreadId) {
                    val last = messages.lastIndex
                    if (last >= 0) messages[last] = messages[last].copy(text = "⚠ ${friendly(e)}", streaming = false)
                }
            }
        } finally {
            postToMain {
                if (!closed) {
                    busy = false
                    if (threadId == originThreadId) persistActiveThread()
                    flushSendQueue()
                }
            }
        }
    }
}
```

Cuatro detalles que importan:

1. **`withContext(io)` alrededor del lazo, no de la escritura.** El lazo
   incluye la ejecución de herramientas (`CreateFileTool` escribe un archivo):
   eso es E/S bloqueante y no puede ir en el hilo de UI.
2. **La red no bloquea el hilo de UI de todos modos.**
   `OpenAiCompatibleGateway.chatStream` termina en `.flowOn(ioDispatcher)`: el
   body del `flow {}` (la lectura SSE) ya corre en el dispatcher de fondo. Por
   eso `AgentLoop.run` podría.collectarse en el hilo de UI sin bloquearlo… pero
   las herramientas sí bloquearían. De ahí el `withContext(io)`.
3. **`postToMain` conserva el orden.** Todas las llamadas salen del mismo hilo
   (el del lazo) y se encolan en el mismo dispatcher ⇒ FIFO. El `updateLast`
   final, que se encola **después** de que terminó el lazo, se aplica después
   que todos los deltas. ✓
4. **`CancellationException` se re-lanza.** El `AgentLoop` la propaga a
   propósito (regla del bucle). Si el ViewModel se tragara la cancelación, el
   scope no se cerraría nunca.

> Nota sobre `Dispatchers.IO` vs `kitoru.platform.ioDispatcher`: en
> `commonMain` **no** se puede escribir `Dispatchers.IO`. Todo el código de
> ejemplo usa `io`, que es el dispatcher inyectado
> (`kitoru.platform.ioDispatcher` en producción = `Dispatchers.IO` en la JVM).

### 4.5 El `finally`, el `busy` y la persistencia

- `busy = false` **siempre** (en el `finally`), aunque se cancele: si no, la
  UI queda con el botón "Detener" eternamente.
- `persistActiveThread()` sólo si `threadId == originThreadId`: si el usuario
  cambió de hilo, el hilo nuevo ya fue guardado por quien lo cambió.
- `flushSendQueue()` manda lo encolado **junto** (mismo comportamiento que
  FunshiEngineAI): los mensajes escritos mientras el modelo respondía se
  envían en un solo mensaje al terminar.

---

## 5. El ciclo de vida

### 5.1 Quién cancela qué

| Job | Quién lo cancela | Cuándo |
|---|---|---|
| Cargas (`refresh`, `reload`) | `scope.cancel()` (del `close()` del VM) | al salir de la pantalla |
| Streaming de la respuesta | `chatJob?.cancel()` | botón "Detener", `send()` nuevo, `close()` |
| Permiso de herramienta pendiente | `cancel()` completa el `CompletableDeferred` con `DENY` | botón "Detener" |
| `LocalModelService` (llama-server) | `engine.close()` | `close()` del VM de chat |
| Todo lo del ViewModel | `scope.cancel()` | `close()` |

**El botón "Detener" también resuelve el diálogo de permiso.** Es una
corrección sobre FunshiEngineAI: allí el `permissionAnswer` quedaba colgado y
el `AgentLoop` esperaba para siempre. En Kitoru:

```kotlin
/** Cancela la generación en curso (botón "Detener") y descarta lo encolado. */
fun cancel() {
    queuedMessages = emptyList()
    chatJob?.cancel()
    // El diálogo de permiso pendiente se cierra con DENY: si no, el AgentLoop
    // queda esperando una decisión que nunca va a llegar.
    permissionAnswer?.complete(PermissionDecision.DENY)
    permissionRequest = null
}
```

### 5.2 El guard de identidad: `Origin(courseId, threadId)`

El problema: el usuario escribe una pregunta, cambia de hilo (o de cátedra) y
la respuesta llega. Si el callback escribe en el estado **actual**, la
respuesta del hilo A aparece en el hilo B.

FunshiEngineAI lo resuelve con `originChatId`:

```kotlin
val originChatId = activeChatId          // se captura ANTES de generar
...
if (activeChatId == originChatId) {       // y se compara en CADA callback
    updateLast(sb.toString(), sourceLabels, cards, attachments)
}
```

En Kitoru hay **dos** identidades que capturar, porque el chat vive dentro de
una cátedra:

```kotlin
/**
 * Identidad del chat que lanzó la generación.
 *
 * Se captura antes de empezar y se compara en cada callback: si el usuario
 * cambió de hilo o de cátedra mientras el modelo respondía, los callbacks NO
 * tocan lo que hay ahora en pantalla (el patrón `originChatId` de
 * FunshiEngineAI, extendido al nivel de cátedra).
 */
private data class Origin(val courseId: String, val threadId: String)
```

```kotlin
val origin = Origin(courseId, threadId)
...
if (origin == Origin(courseId, threadId)) {
    updateLast(...)
}
```

Además, el guard estructural: **el ViewModel del chat se crea con la pantalla
y se cierra al salir** (§5.4). El guard `origin` cubre el caso en que la
pantalla sigue viva pero cambió de hilo (el cajón de hilos).

### 5.3 El flag `closed`: ningún ViewModel huérfano escribe

```kotlin
/** Se cerró el ViewModel: no se aceptan más escrituras. */
internal var closed: Boolean = false
    private set

/** Cierra el ViewModel: cancela los jobs en vuelo y bloquea las escrituras. */
fun close() {
    closed = true
    scope.cancel()
}
```

`postToMain` ya chequea `closed`. Y en el `finally` del streaming:

```kotlin
finally {
    postToMain {
        if (!closed) {          // ← no escribimos en un VM muerto
            busy = false
            ...
        }
    }
}
```

**Por qué importa**: si el usuario cierra la ventana de escritorio mientras el
modelo responde, sin este flag el `finally` escribiría `busy = false` y
`persistActiveThread()` en un ViewModel que ya no existe → una escritura a disco
después de cerrar la app (y en Android, un `IllegalStateException` si el
repositorio ya cerró sus recursos).

### 5.4 El ViewModel con pantalla

Decisión: **el ViewModel de chat vive mientras vive la pantalla de chat.**

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/KitoruApp.kt
@Composable
fun KitoruApp(env: KitoruEnv) {
    // Scope de la app: vive mientras la composición exista.
    val appScope = rememberCoroutineScope()

    // ViewModel raíz: vive mientras la app.
    val app = remember {
        KitoruViewModel(
            parentScope = appScope,
            courses = env.courses,
            enrollments = env.enrollments,
            profiles = env.profiles,
        )
    }

    // ViewModel de la pantalla de chat: vive mientras la pantalla.
    val chat = remember(app.activeCourseId, app.activeThreadId) {
        ChatViewModel(
            parentScope = appScope,
            course = app.activeCourse ?: return@remember null,
            ...
        )
    }

    // La pantalla se lleva por delante el ViewModel: al salir, se cierra.
    DisposableEffect(chat) {
        onDispose { chat?.close() }
    }

    KitoruTheme(dark = app.isDark, accent = app.accentColor) {
        AppShell(app = app, chat = chat)
    }
}
```

Consecuencias:

- Salir del chat **corta la generación** (y guarda el texto parcial). Es lo
  correcto: nadie está mirando la respuesta.
- El `scope` del chat es hijo del `scope` de la app ⇒ si la app se cierra, se
  cae todo.
- El `close()` del chat también cierra el `ChatEngine` (y con él el
  `llama-server` local, si lo hubiera).

---

## 6. Los errores

### 6.1 Las dos opciones

**(a) `var error: String`** — como está hoy en `KitoruViewModel`.

```kotlin
var error by mutableStateOf("")
    private set
```

Problemas:

- `""` es ambiguo: ¿"no hay error" o "un error con mensaje vacío"?
- No distingue un error recuperable de uno fatal.
- No hay forma de ofrecer "Reintentar" con el contexto del error.

**(b) Un `sealed UiState` único para toda la app.**

```kotlin
sealed interface AppUiState {
    data object Loading : AppUiState
    data class Ready(val data: AppData) : AppUiState
    data class Failed(val error: AppError) : AppUiState
}
```

Problema: la app tiene 11 pantallas con datos distintos. Un `sealed` global
obliga a que **cada** pantalla nueva agregue una rama, y a que el error de la
pantalla de analítica compita con el error de la de materiales. Es el "estado
gigante" que nadie mantiene.

### 6.2 La recomendación: las dos cosas, con reglas claras

> **Regla 1 — el error que acompaña a los datos va con los datos.**
> **Regla 2 — el error ortogonal a los datos (un banner sobre lo que haya) va aparte.**

**Regla 1**: un `sealed` genérico y reutilizable, para las pantallas que tienen
un ciclo carga → datos → error:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/core/LoadState.kt
package kitoru.ui.core

/**
 * Estado de carga de una pantalla.
 *
 * Se usa cuando los datos **pertenecen** al estado: si la carga falla, no hay
 * datos que mostrar, y la UI muestra el error en lugar de la pantalla.
 */
sealed interface LoadState<out T> {
    /** Todavía no se pidió nada. */
    data object Idle : LoadState<Nothing>

    /** Cargando. */
    data object Loading : LoadState<Nothing>

    /** Cargado: estos son los datos. */
    data class Ready<out T>(val data: T) : LoadState<T>

    /** Falló: no hay datos. */
    data class Failed(val error: AppError) : LoadState<Nothing>
}
```

Ejemplo de uso (pantalla de analítica del docente):

```kotlin
var analytics: LoadState<CourseAnalytics> by mutableStateOf(LoadState.Idle)
    internal set

fun refreshAnalytics() {
    analytics = LoadState.Loading
    scope.launch {
        analytics = try {
            val threads = threads.listForCourse(courseId)
            val msgs = messages.listForCourse(courseId)
            LoadState.Ready(AnalyticsAnalyzer.analyze(course, enrollments, threads, msgs))
        } catch (e: Exception) {
            LoadState.Failed(AppError.from(e, "No se pudo calcular la analítica de la cátedra."))
        }
    }
}
```

**Regla 2**: un error **global**, nullable, para el banner:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/core/AppError.kt
package kitoru.ui.core

/**
 * Un error que el usuario tiene que ver.
 *
 * El mensaje va en **español** y dice qué pasó y qué hacer (regla 4 del
 * CONTEXTO). Nunca es un `exception.message` pelado.
 */
data class AppError(
    /** Qué pasó, en una frase. */
    val message: String,
    /** Qué puede hacer el usuario al respecto (opcional). */
    val hint: String = "",
    /** Si tiene sentido ofrecer "Reintentar". */
    val retryable: Boolean = false,
) {
    companion object {
        /**
         * Construye un error legible desde una excepción.
         *
         * Las excepciones del dominio ya traen su mensaje en español
         * (`LlmException`); las demás se envuelven con un texto que dice qué
         * se intentó hacer.
         */
        fun from(e: Throwable, context: String, retryable: Boolean = false): AppError =
            when (e) {
                is LlmException -> AppError(e.message ?: context, hint = e.hint(), retryable = e.retryable)
                is CancellationException -> throw e
                else -> AppError(context, hint = e.message ?: "", retryable = retryable)
            }
    }
}
```

Y en el ViewModel:

```kotlin
/** Error global visible en banner (null = no hay error). */
var error: AppError? by mutableStateOf(null)
    internal set

/** Borra el error del banner. */
fun clearError() {
    error = null
}
```

El `error` **se limpia al navegar** (`go`, `back`, `openCourse`): un error de
la pantalla anterior no debe quedar flotando en la nueva.

### 6.3 Resumen de la política de errores

| Situación | Dónde vive | Cómo se ve |
|---|---|---|
| Falló la carga de una pantalla | `LoadState.Failed(error)` de esa pantalla | la pantalla muestra el error con "Reintentar" |
| Falló una acción puntual (guardar, borrar, probar conexión) | `error: AppError?` global | banner dismissible sobre la pantalla actual |
| Falló el streaming de la respuesta | el último mensaje del hilo | el mensaje queda con `⚠ <mensaje>` (el alumno no pierde lo escrito) |
| El modelo pidió una herramienta sin permiso | `permissionRequest` | diálogo de permiso (no es un error) |
| La IA local no está disponible | `error` + `status` | banner con el motivo y qué revisar |

Y el helper `friendly`, portado de FunshiEngineAI:

```kotlin
/**
 * Convierte una excepción en un mensaje que se puede mostrar.
 *
 * Las excepciones del dominio (`LlmException`) ya vienen con un mensaje en
 * español y accionable; las demás se envuelven con un texto genérico.
 */
internal fun friendly(e: Throwable): String = when (e) {
    is LlmException -> e.message
    else -> e.message ?: "Error desconocido"
}
```

---

## 7. La navegación

### 7.1 La pila manual

Hoy `KitoruViewModel` tiene `screen: AppScreen` (una sola pantalla) y un `back()`
con un `when` hardcodeado:

```kotlin
fun back() {
    screen = when (screen) {
        is AppScreen.StudentChat -> AppScreen.StudentHome
        is AppScreen.StudentModel, is AppScreen.StudentMaterials -> AppScreen.StudentHome
        ...
        else -> screen
    }
}
```

Eso **no es una pila**: no hay historia. Si el alumno entra a "Materiales" desde
el chat y vuelve atrás, lo lleva al home, no al chat.

La pila:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/core/NavStack.kt
package kitoru.ui.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Pila de navegación manual.
 *
 * Sustituye a `navigation-compose` (ver §7.2). Es una lista de destinos; el
 * último es el visible. Deliberadamente **genérica** (`NavStack<T>`) para que el
 * rol docente pueda tener la suya (`TeacherNavState`, ver
 * `teacher/01-navegacion.md` §2.4) sin duplicar el código.
 *
 * Es estado de Compose: `entries` es un `mutableStateOf<List<T>>` y por eso
 * `AppShell` se recompone al navegar.
 */
class NavStack<T>(private val root: T) {

    /** La pila: el último elemento es la pantalla visible. */
    var entries: List<T> by mutableStateOf(listOf(root))
        private set

    /** La pantalla visible. */
    val current: T get() = entries.last()

    /** ¿Se puede volver atrás? */
    val canGoBack: Boolean get() = entries.size > 1

    /** Entra a un destino (apila). */
    fun push(entry: T) {
        entries = entries + entry
    }

    /** Cambia el destino visible **sin** apilar (usado por el rail de secciones). */
    fun replace(entry: T) {
        entries = entries.dropLast(1) + entry
    }

    /** Vuelve atrás. `false` si ya no hay a dónde volver. */
    fun pop(): Boolean {
        if (!canGoBack) return false
        entries = entries.dropLast(1)
        return true
    }

    /** Vuelve hasta la última aparición de [entry] (la incluye). */
    fun popTo(entry: T): Boolean {
        val i = entries.lastIndexOf(entry)
        if (i <= 0) return false
        entries = entries.take(i + 1)
        return true
    }

    /** Descarta toda la pila y deja sólo [entry] (usado al cambiar de rol). */
    fun resetTo(entry: T) {
        entries = listOf(entry)
    }

    /** ¿Está [entry] en la pila? */
    fun contains(entry: T): Boolean = entry in entries
}
```

Y su uso en el `KitoruViewModel`:

```kotlin
/** La pila de navegación de la app. */
internal val nav: NavStack<AppScreen> = NavStack(AppScreen.RolePicker)

/** La pantalla visible. */
val screen: AppScreen get() = nav.current

/** ¿Se puede volver atrás? */
val canGoBack: Boolean get() = nav.canGoBack

/** Navega a una pantalla (apila). */
fun go(target: AppScreen) {
    error = null
    nav.push(target)
}

/** Cambia la pantalla visible sin apilar (cambio de sección del rail). */
fun replace(target: AppScreen) {
    error = null
    nav.replace(target)
}

/** Vuelve atrás. `false` si no hay a dónde volver. */
fun back(): Boolean {
    error = null
    return nav.pop()
}

/** Cambia de rol: descarta la pila (el rol nuevo tiene su propio árbol). */
fun switchRole(role: AppScreen) {
    error = null
    nav.resetTo(role)
}
```

**Reglas de la pila**:

| Acción | Operación | Por qué |
|---|---|---|
| Abrir un chat, un material, una ficha | `push` | el "atrás" vuelve al lugar de donde se vino |
| Cambiar de sección del rail | `replace` | el "atrás" no recorre las secciones |
| Cambiar de rol (Alumno ↔ Docente) | `resetTo` | el árbol del otro rol no es alcanzable con "atrás" |
| Entrar a la cátedra desde la home | `push` | |
| Salir de la cátedra (volver a la home) | `popTo(home)` | |

### 7.2 `navigation-compose`: el estado real de la cuestión

> **Nota de precisión (importante, porque el planteo original de esta tarea
> decía que "navigation-compose no es KMP en Compose 1.7.3")**: eso es cierto
> para el artefacto de AndroidX (`androidx.navigation:navigation-compose`, que
> es Android-only), pero **Compose Multiplatform 1.7.3 sí publica la versión
> multiplatform**: `org.jetbrains.androidx.navigation:navigation-compose:2.8.0-alpha10`
> (y `material-navigation:1.7.0-beta02`), con navegación type-safe. O sea: la
> opción KMP **existe** en la toolchain que ya está en el repo.

Dit o, la decisión sigue siendo **pila manual**, pero por otras razones:

| Motivo | Detalle |
|---|---|
| **Madurez** | El artefacto que trae CMP 1.7.3 es `2.8.0-alpha10`: API en alfa, con cambios entre versiones. Meter una dependencia alfa en una app que va a producción no es una decisión que se tome por comodidad. |
| **Dos mecanismos** | El rol docente ya necesita una pila propia para los **paneles de detalle** (`TeacherNavState`, `teacher/01-navegacion.md` §2.4). Con `navigation-compose` habría dos sistemas de back stack conviviendo. |
| **El grafo es chato** | 11 pantallas, dos árboles planos, sin deep links, sin argumentos tipados complejas, sin gráficos anidados. `navigation-compose` resuelve problemas que Kitoru no tiene. |
| **Testeable sin Compose** | `NavStack` se testea en `commonTest` con dos líneas. Un `NavController` necesita `ComposeTestRule` para probarse. |
| **Sin dependencias extra** | `navigation-compose` arrastra `navigation-runtime`, `navigation-common` y (para type-safe) el plugin de serialización en el grafo de rutas. |

**Cómo se deja la puerta abierta**: la API de `NavStack` (`push`/`pop`/`replace`/
`popTo`/`resetTo`) es **un subconjunto de `NavController`** (`navigate`,
`popBackStack`, `popUpTo`). Si más adelante se adopta `navigation-compose`, el
cambio se limita a la implementación de `NavStack` y a las llamadas en la UI;
las acciones del ViewModel (`go`, `back`) no cambian.

### 7.3 El botón "atrás" en Android y el `Esc` en escritorio

`androidx.activity.compose.BackHandler` es **Android-only** ⇒ `expect/actual`:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/BackHandler.kt
package kitoru.ui.platform

import androidx.compose.runtime.Composable

/**
 * El botón "atrás" de la plataforma.
 *
 * En Android delega en `androidx.activity.compose.BackHandler`; en escritorio
 * no hace nada (el "atrás" es la tecla `Esc`, que atiende la ventana).
 *
 * Es un `expect @Composable` porque `BackHandler` necesita un `Activity`:
 * sólo se puede registrar desde la composición.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
```

```kotlin
// ui/src/androidMain/kotlin/kitoru/ui/platform/BackHandler.android.kt
package kitoru.ui.platform

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
```

```kotlin
// ui/src/desktopMain/kotlin/kitoru/ui/platform/BackHandler.desktop.kt
package kitoru.ui.platform

import androidx.compose.runtime.Composable

/**
 * En escritorio no hay botón "atrás" del sistema: el atajo es `Esc`, y lo
 * atiende la ventana (`Window(onKeyEvent = ...)`) porque `onPreviewKeyEvent`
 * de un `Box` no ve las teclas que no tienen el foco.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // no-op: el `Esc` se resuelve en la ventana del escritorio.
}
```

Y el uso, en el shell:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/shell/AppShell.kt
@Composable
fun AppShell(app: KitoruViewModel, modifier: Modifier = Modifier) {
    // Botón "atrás" del sistema (Android). En escritorio es un no-op.
    PlatformBackHandler(enabled = app.canGoBack) { app.back() }

    when (val screen = app.screen) {
        is AppScreen.RolePicker -> RolePickerScreen(...)
        is AppScreen.StudentHome -> StudentHomeScreen(...)
        ...
    }
}
```

En el escritorio, el `Esc`:

```kotlin
// desktop/src/main/kotlin/com/funshiengine/kitoru/desktop/Main.kt
@Composable
fun App() {
    val vm = remember { KitoruViewModel(...) }
    Window(
        onKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                vm.back()      // si no puede volver, no hace nada
                true           // consume la tecla
            } else {
                false
            }
        },
    ) {
        KitoruApp(vm)
    }
}
```

Reglas del botón atrás:

- **Se habilita sólo si hay pila** (`app.canGoBack`). En la raíz, el botón
  atrás del sistema **cierra la app** (comportamiento estándar de Android) en
  vez de no hacer nada.
- En escritorio, `Esc` en la raíz **no cierra la ventana**: la app de escritorio
  no se cierra con `Esc` (a diferencia de un diálogo).
- El "atrás" **limpia el error** (`back()` pone `error = null`).

---

## 8. Ejemplo completo: el `ChatViewModel` de Kitoru

### 8.1 Los archivos

```
ui/src/commonMain/kotlin/kitoru/ui/chat/
├── UiChatMessage.kt        el tipo de mensaje de la UI + los mapeos al dominio
├── ChatViewModel.kt        el estado + las acciones del chat  (§8.2)
├── ChatThreads.kt          extensiones: hilos y persistencia        (§8.3)
└── ChatBoot.kt             extensiones: arranque del motor/gateway   (§8.4)
```

### 8.2 `ChatViewModel.kt`

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/chat/ChatViewModel.kt
package kitoru.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kitoru.domain.CitationStats
import kitoru.domain.Course
import kitoru.domain.Enrollment
import kitoru.domain.MessageRepository
import kitoru.domain.ThreadRepository
import kitoru.domain.ToolCallStats
import kitoru.platform.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import llm.config.ChatMessage as LlmChatMessage
import rag.agent.AgentEvent
import rag.agent.AgentLoop
import rag.agent.PermissionDecision
import rag.agent.PermissionRequest
import rag.agent.ToolCategory
import rag.agent.ToolContext
import rag.agent.ToolRegistry
import rag.engine.RagEngine
import rag.retrieval.KnowledgeBase

/**
 * Estado y flujo del chat del alumno.
 *
 * Mismo patrón que `ChatViewModel` de FunshiEngineAI, con cuatro
 * diferencias, todas justificadas en `docs/design/platform/07-estado.md`:
 *
 *  1. **El scope se inyecta** (no se crea adentro) ⇒ el test corre con
 *     `TestScope` y es determinista.
 *  2. **Los mensajes son un `SnapshotStateList`** ⇒ `updateLast` es O(1) y sólo
 *     se recompone la burbuja que cambió.
 *  3. **El estado se escribe en el hilo de UI**: el lazo del agente corre en
 *     el dispatcher de fondo y sus callbacks encolan la escritura con
 *     [postToMain].
 *  4. **Guard de identidad** ([origin]): la respuesta de un hilo no se pinta
 *     en otro (patrón `originChatId` de FunshiEngineAI, extendido a la
 *     cátedra).
 *
 * La lógica por área (hilos, arranque del motor, permisos) vive en funciones de
 * extensión siblings: `ChatThreads.kt`, `ChatBoot.kt`, `ChatPermissions.kt`.
 */
class ChatViewModel(
    parentScope: CoroutineScope,
    private val course: Course,
    private val enrollment: Enrollment,
    /** Repositorio de hilos (se llama `threadRepo` y no `threads` para no chocar con el estado). */
    private val threadRepo: ThreadRepository,
    /** Repositorio de mensajes (idem: el estado es `messages`). */
    private val messageRepo: MessageRepository,
    /** El índice RAG de la cátedra (lo arma `ChatBoot`). */
    private val kb: KnowledgeBase,
    /** Herramientas que puede pedir el modelo. */
    private val tools: ToolRegistry = ToolRegistry(),
    /** Tope de herramientas de la cátedra (`domain/08-permisos.md`). */
    private val toolCeiling: Set<ToolCategory> = emptySet(),
    /** Timeout por herramienta (ms). */
    private val toolTimeoutMs: Long = 60_000L,
    /** Dispatcher de fondo: inyectado para que el test sea determinista. */
    private val io: CoroutineDispatcher = ioDispatcher,
) {

    // ---- Ámbito de corrutinas -------------------------------------------

    /**
     * Ámbito del ViewModel.
     *
     * Hereda el dispatcher del padre (Main en producción, el del `TestScope`
     * en los tests) y usa un `SupervisorJob` hijo: un fallo en una carga no
     * cancela a las demás.
     */
    internal val scope: CoroutineScope = CoroutineScope(
        SupervisorJob(parentScope.coroutineContext[Job]) +
            (parentScope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Main),
    )

    /** Se cerró el ViewModel: no se aceptan más escrituras. */
    internal var closed: Boolean = false
        private set

    // ---- Estado ----------------------------------------------------------

    /**
     * Los mensajes de la conversación.
     *
     * `SnapshotStateList` y no `mutableStateOf(list)`: el `updateLast` de
     * FunshiEngineAI reemplaza la lista **entera** en cada token (O(n) por
     * token); acá se muta un elemento (O(1)) y Compose sólo recompone la
     * burbuja que cambió.
     */
    val messages = mutableStateListOf<UiChatMessage>()

    /** Id del hilo que se está viendo. */
    var threadId by mutableStateOf("")
        internal set

    /** El motor todavía no está listo (abriendo gateway, indexando). */
    var ready by mutableStateOf(false)
        internal set

    /** El modelo está escribiendo. */
    var busy by mutableStateOf(false)
        internal set

    /** Mensajes escritos mientras el modelo respondía (se mandan juntos). */
    var queuedMessages by mutableStateOf(listOf<String>())
        internal set

    /** Texto del redactor. Vive en el VM para que el botón y el teclado usen el mismo camino. */
    var composerInput by mutableStateOf("")
        internal set

    /** Modo del chat: usar el material de la cátedra (default) o preguntar libre. */
    var useCourseMaterial by mutableStateOf(true)
        internal set

    /** Petición de permiso esperando decisión del alumno (la resuelve la UI). */
    var permissionRequest by mutableStateOf<PermissionRequest?>(null)
        internal set

    /** Respuesta pendiente de la petición de permiso. */
    internal var permissionAnswer: CompletableDeferred<PermissionDecision>? = null

    /** Categorías ya concedidas en este chat. */
    var grantedCategories by mutableStateOf(setOf<ToolCategory>())
        internal set

    /** Error visible en banner (null = no hay). */
    var error by mutableStateOf<String?>(null)
        internal set

    /** Estado de carga del motor (arranque). */
    var status by mutableStateOf("")
        internal set

    // ---- Interno ---------------------------------------------------------

    /** La corrutina de la respuesta en curso (la cancela "Detener"). */
    internal var chatJob: Job? = null

    /** El motor de preguntas (lo arma `ChatBoot`). */
    internal var engine: RagEngine? = null
        internal set

    /** El gateway en uso (lo arma `ChatBoot`). */
    internal var gateway: llm.api.LlmGateway? = null
        internal set

    /**
     * Identidad del chat que lanzó la generación.
     *
     * Se captura antes de empezar y se compara en cada callback: si el
     * usuario cambió de hilo o de cátedra mientras el modelo respondía, los
     * callbacks NO tocan lo que hay ahora en pantalla.
     */
    private var origin: Origin = Origin(course.id, "")
        private set

    private data class Origin(val courseId: String, val threadId: String)

    init {
        // Carga el hilo activo (si lo hay) desde el repositorio.
        scope.launch { loadThread(threadId) }
    }

    // ---- Acciones --------------------------------------------------------

    /**
     * Envía un mensaje.
     *
     * Si el modelo está respondiendo, el texto **se encola** y se enviará
     * junto con los demás al terminar (mismo comportamiento que
     * FunshiEngineAI).
     */
    fun send(text: String) {
        if (text.isBlank() || closed) return
        val trimmed = text.trim()

        if (busy) {
            queuedMessages = queuedMessages + trimmed
            status = "${queuedMessages.size} mensaje(s) en cola — se enviarán juntos al terminar."
            return
        }

        // El historial se copia **en el hilo de UI**: una `List` inmutable que
        // el motor puede leer sin riesgo desde el dispatcher de fondo.
        val history: List<LlmChatMessage> = messages
            .filter { it.text.isNotBlank() && !it.streaming }
            .map { LlmChatMessage(it.role, it.text) }

        // El mensaje del alumno + el placeholder del asistente.
        messages += UiChatMessage(newId(), "user", trimmed, mode = currentMode())
        messages += UiChatMessage(newId(), "assistant", "", streaming = true, mode = currentMode())

        if (!ready) {
            status = "El modelo todavía no está listo."
            return
        }
        sendInternal(trimmed, history)
    }

    /** Cancela la generación en curso (botón "Detener") y descarta lo encolado. */
    fun cancel() {
        queuedMessages = emptyList()
        chatJob?.cancel()
        // El diálogo de permiso pendiente se cierra con DENY: si no, el
        // AgentLoop queda esperando una decisión que nunca va a llegar.
        permissionAnswer?.complete(PermissionDecision.DENY)
        permissionRequest = null
    }

    /** Cierra el ViewModel: cancela los jobs y suelta el motor. */
    fun close() {
        closed = true
        scope.cancel()
        chatJob?.cancel()
        engine?.let { /* el motor no tiene recursos propios */ }
        gateway?.close()
        gateway = null
    }

    /**
     * Encola una escritura de estado en el hilo de UI.
     *
     * El lazo del agente corre en el dispatcher de fondo (las herramientas
     * pueden escribir archivos), así que sus callbacks llegan desde ese hilo.
     * El estado de Compose se escribe **siempre** en el hilo de UI.
     */
    internal fun postToMain(block: () -> Unit) {
        if (closed) return
        scope.launch { block() }
    }

    /** Actualiza el último mensaje (el que se está escribiendo). O(1). */
    internal fun updateLast(
        text: String,
        sources: List<kitoru.domain.SourceRef> = emptyList(),
        toolCards: List<ToolCallStats> = emptyList(),
        citation: CitationStats? = null,
    ) {
        val last = messages.lastIndex
        if (last < 0) return
        messages[last] = messages[last].copy(
            text = text,
            sources = sources,
            toolCalls = toolCards,
            citation = citation,
        )
    }

    /** El modo actual del chat, como etiqueta. */
    internal fun currentMode(): String = if (useCourseMaterial) "MATERIAL" else "LIBRE"

    /** Ids únicos de mensaje (hilo + timestamp + contador). */
    internal fun newId(): String = "$threadId-${nowMillis()}-${messages.size}"

    /** Traduce una excepción a un mensaje mostrable. */
    internal fun friendly(e: Throwable): String = when (e) {
        is llm.error.LlmException -> e.message
        else -> e.message ?: "Error desconocido"
    }
}
```

Y el envío (`sendInternal`) está en §4.4, con el `AgentLoop`, el `withContext(io)`
y los guards.

### 8.3 `ChatThreads.kt` — las extensiones por área

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/chat/ChatThreads.kt
package kitoru.ui.chat

import kitoru.domain.ChatThread
import kitoru.domain.Course

/**
 * Hilos de conversación: crear, cambiar, renombrar, borrar y persistir.
 *
 * Funciones de extensión sobre el ViewModel, que sigue siendo el dueño del
 * estado (patrón `ChatLibrary.kt` de FunshiEngineAI).
 */

/** Carga en memoria los mensajes del hilo [id]. */
internal suspend fun ChatViewModel.loadThread(id: String) {
    if (id.isBlank()) {
        messages.clear()
        return
    }
    val loaded = messageRepo.listForThread(id)
    messages.clear()
    loaded.forEach { messages += it.toUi() }
}

/** Crea un hilo nuevo, lo deja activo y abre el chat. */
internal fun ChatViewModel.newThread(): String {
    val id = "${course.id}|${nowMillis()}"
    val thread = ChatThread(
        id = id,
        courseId = course.id,
        authorId = enrollment.userId,
        authorName = enrollment.displayName,
        title = "Conversación",
        createdAt = nowMillis(),
        updatedAt = nowMillis(),
        effectiveModel = course.modelPolicy,
    )
    scope.launch {
        threadRepo.save(thread)
        threadId = id
        messages.clear()
    }
    return id
}

/** Cambia al hilo [id] (guarda el actual y carga el destino). */
internal fun ChatViewModel.switchThread(id: String) {
    if (id == threadId || busy) return
    scope.launch {
        persistActiveThread()
        threadId = id
        loadThread(id)
        error = null
    }
}

/** Guarda los mensajes del hilo activo en el repositorio. */
internal fun ChatViewModel.persistActiveThread() {
    val id = threadId
    if (id.isBlank()) return
    scope.launch {
        val now = nowMillis()
        val guardados = messages
            .filter { it.text.isNotBlank() }
            .map { it.toDomain(id, now) }
        guardados.forEach { messageRepo.save(it) }
        threadRepo.get(id)?.let { threadRepo.save(it.copy(updatedAt = now)) }
    }
}

/** Borra un mensaje (y la respuesta del modelo que le sigue, si es del alumno). */
internal fun ChatViewModel.deleteMessage(index: Int) {
    if (busy || index !in messages.indices) return
    val end = if (
        messages[index].role == "user" &&
        index + 1 < messages.size &&
        messages[index + 1].role == "assistant"
    ) {
        index + 2
    } else {
        index + 1
    }
    repeat(end - index) { messages.removeAt(index) }
    persistActiveThread()
}

/** Vuelve a generar la respuesta del mensaje del alumno [index]. */
internal fun ChatViewModel.regenerate(index: Int) {
    if (busy || index !in messages.indices) return
    val userIndex = (index - 1 downTo 0).firstOrNull { messages[it].role == "user" } ?: return
    val userText = messages[userIndex].text
    if (userText.isBlank()) return
    // Corta el turno completo: la respuesta vieja y todo lo posterior.
    while (messages.size > userIndex) messages.removeAt(messages.lastIndex)
    send(userText)
}
```

> **Nota de visibility**: `messageRepo`, `threadRepo` y `course` son
> `private` en la clase ⇒ las extensiones **no** los pueden ver. En la
> implementación final el ViewModel los expone como `internal val` (o las
> extensiones quedan dentro del mismo archivo). **Regla**: todo lo que una
> extensión necesite debe ser `internal`, nunca `private` — igual que el
> `internal set` de §1.5.

### 8.4 `ChatBoot.kt` — el arranque del motor

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/chat/ChatBoot.kt
package kitoru.ui.chat

import llm.api.LlmGateway
import llm.api.OpenAiCompatibleGateway
import llm.config.GatewayConfig

/**
 * Arranque del motor: gateway, índice RAG y prueba de conexión.
 *
 * Es el `ChatEngine.kt` de FunshiEngineAI, con la diferencia de que la
 * configuración no se lee de un archivo sino del dominio (`ModelPolicy` de la
 * cátedra + los ajustes del alumno, ya resueltos por `resolveFor`).
 */

/**
 * Abre el gateway que corresponde a la política efectiva.
 *
 * En modo REMOTE habla con el servidor de la cátedra (compatible OpenAI).
 * En modo LOCAL lo arranca el `LocalModelService` (puede tardar: descarga).
 */
internal suspend fun ChatViewModel.boot(policy: kitoru.domain.ModelPolicy) {
    ready = false
    status = "Conectando con el modelo…"
    try {
        val gw: LlmGateway = when (policy.mode) {
            kitoru.domain.ModelMode.REMOTE -> OpenAiCompatibleGateway(
                GatewayConfig(
                    baseUrl = policy.remoteBaseUrl,
                    apiKey = policy.apiKey,
                    model = policy.modelName,
                    temperature = policy.temperature,
                    maxTokens = policy.maxTokens,
                ),
            )
            kitoru.domain.ModelMode.LOCAL -> createLocalModelService().start(policy)
        }
        gateway = gw
        engine = RagEngine(
            cfg = rag.config.RagConfig(chunkSize = course.chunkSize, topK = policy.topK),
            kb = kb,
            llm = gw,
            systemPrompt = { course.systemPrompt.ifBlank { course.modelPolicy.systemPrompt } },
        )
        ready = true
        status = "Listo: ${policy.modelLabel}"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ready = false
        status = ""
        error = "No se pudo conectar con el modelo: ${friendly(e)}"
    }
}

/**
 * Prueba la conexión con el servidor de la cátedra (botón "Probar").
 *
 * Es la acción que más se usa cuando el alumno no puede chatear: hay que
 * poder distinguir "la URL está mal" de "la clave venció" de "el servidor
 * está caído".
 */
internal fun ChatViewModel.testConnection(policy: kitoru.domain.ModelPolicy) {
    scope.launch {
        status = "Probando la conexión…"
        try {
            val names = withContext(io) {
                OpenAiCompatibleGateway(
                    GatewayConfig(
                        baseUrl = policy.remoteBaseUrl,
                        apiKey = policy.apiKey,
                        model = policy.modelName,
                    ),
                ).use { it.listModels() }
            }
            status = if (names.isEmpty()) {
                "Conectado. El servidor no-annunció modelos."
            } else {
                "Conectado. Modelos disponibles: ${names.take(5).joinToString(", ")}" +
                    if (names.size > 5) " (y ${names.size - 5} más)" else ""
            }
        } catch (e: Exception) {
            status = ""
            error = "No se pudo conectar con el servidor de la cátedra: ${friendly(e)}"
        }
    }
}
```

---

## 9. El testing

### 9.1 Por qué un ViewModel se testea **sin** Compose

`mutableStateOf` devuelve un `SnapshotMutableState<T>`, que es un estado
**mutable** con un campo `value` normal:

- **Leer** `vm.error` devuelve el valor actual, en cualquier hilo, sin
  `@Composable`, sin `Recomposer`, sin `Snapshot.sendApplyNotifications`.
- **Escribir** `vm.error = "x"` actualiza el valor de inmediato (queda
  pendiente de aplicarse al snapshot global, pero el valor ya es el nuevo).
- Lo mismo para `mutableStateListOf`: `vm.messages` es una
  `SnapshotStateList` que se lee y se muta como una lista normal.

O sea: **el estado de Compose no necesita recomposición para ser leído o
verificado**. Por eso el ViewModel es testeable con un `kotlin.test` pelado.

### 9.2 Las tres reglas que lo hacen posible (y que hay que respetar al escribir)

| # | Regla | Sin ella |
|---|---|---|
| R-1 | **El `CoroutineScope` se inyecta** por constructor | El test no puede avanzar las corrutinas: `advanceUntilIdle()` no las toca y el test falla o queda colgado |
| R-2 | **El dispatcher de fondo se inyecta** (`io: CoroutineDispatcher = ioDispatcher`) | El test depende de un hilo real → no determinista |
| R-3 | **Ninguna acción toca el sistema de archivos directamente**: todo va por los repositorios, que en el test son fakes | El test escribe en el disco del usuario |

Corolario: el ViewModel **nunca** crea `Dispatchers.Main` por dentro. El
dispatcher viene del scope padre (Main en producción, el del `TestScope` en el
test).

### 9.3 Los repositorios falsos

```kotlin
// ui/src/commonTest/kotlin/kitoru/ui/support/Fakes.kt
package kitoru.ui.support

import kitoru.domain.Course
import kitoru.domain.CourseRepository
import kitoru.domain.Enrollment
import kitoru.domain.EnrollmentRepository
import kitoru.domain.UserProfile
import kitoru.domain.ProfileRepository

/**
 * Repositorio falso en memoria.
 *
 * `failWith` sirve para probar el camino del error: si no es null, la próxima
 * operación que toque la lista tira esa excepción.
 */
class FakeCourseRepository(
    var items: List<Course> = emptyList(),
    var failWith: Throwable? = null,
) : CourseRepository {
    override suspend fun list(): List<Course> {
        failWith?.let { throw it }
        return items
    }
    override suspend fun get(id: String): Course? = items.firstOrNull { it.id == id }
    override suspend fun save(course: Course) {
        items = items.filterNot { it.id == course.id } + course
    }
    override suspend fun delete(id: String) {
        items = items.filterNot { it.id == id }
    }
}

class FakeEnrollmentRepository(
    var items: List<Enrollment> = emptyList(),
) : EnrollmentRepository {
    override suspend fun listForCourse(courseId: String) = items.filter { it.courseId == courseId }
    override suspend fun listForUser(userId: String) = items.filter { it.userId == userId }
    override suspend fun get(id: String) = items.firstOrNull { it.id == id }
    override suspend fun save(enrollment: Enrollment) {
        items = items.filterNot { it.id == enrollment.id } + enrollment
    }
    override suspend fun delete(id: String) {
        items = items.filterNot { it.id == id }
    }
}

class FakeProfileRepository(
    var items: List<UserProfile> = emptyList(),
) : ProfileRepository {
    override suspend fun get(id: String) = items.firstOrNull { it.id == id }
    override suspend fun list() = items
    override suspend fun save(profile: UserProfile) {
        items = items.filterNot { it.id == profile.id } + profile
    }
}

class FakeThreadRepository(
    var items: List<kitoru.domain.ChatThread> = emptyList(),
) : kitoru.domain.ThreadRepository {
    override suspend fun listForCourse(courseId: String) = items.filter { it.courseId == courseId }
    override suspend fun listForUser(courseId: String, userId: String) =
        items.filter { it.courseId == courseId && it.authorId == userId }
    override suspend fun get(id: String) = items.firstOrNull { it.id == id }
    override suspend fun save(thread: kitoru.domain.ChatThread) {
        items = items.filterNot { it.id == thread.id } + thread
    }
    override suspend fun delete(id: String) {
        items = items.filterNot { it.id == id }
    }
}

class FakeMessageRepository(
    var items: List<kitoru.domain.ChatMessage> = emptyList(),
) : kitoru.domain.MessageRepository {
    override suspend fun listForThread(threadId: String) =
        items.filter { it.threadId == threadId }.sortedBy { it.createdAt }
    override suspend fun listForCourse(courseId: String) =
        items.filter { it.threadId.startsWith("$courseId|") }.sortedBy { it.createdAt }
    override suspend fun save(message: kitoru.domain.ChatMessage) {
        items = items.filterNot { it.id == message.id } + message
    }
    override suspend fun delete(threadId: String) {
        items = items.filterNot { it.threadId == threadId }
    }
}
```

> Ubicación: `ui/src/commonTest/kotlin/…`. El módulo `:ui` ya tiene
> `commonTest.dependencies { implementation(libs.kotlinx.coroutines.test); implementation(kotlin("test")) }`
> en su `build.gradle.kts`. Si los tests se escriben con JUnit 5 (como los de
> `:backend`), hay que agregar `desktopTest.dependencies { implementation(libs.junit.jupiter); runtimeOnly(libs.junit.platform.launcher) }` y `tasks.withType<Test>().configureEach { useJUnitPlatform() }` — **sin** `useJUnitPlatform()` la tarea corre 0 tests y el build queda verde (ya le pasó a `:backend`).

### 9.4 El test del `KitoruViewModel`

```kotlin
// ui/src/commonTest/kotlin/kitoru/ui/KitoruViewModelTest.kt
package kitoru.ui

import kitoru.domain.Course
import kitoru.domain.CourseRole
import kitoru.domain.Enrollment
import kitoru.ui.support.FakeCourseRepository
import kitoru.ui.support.FakeEnrollmentRepository
import kitoru.ui.support.FakeProfileRepository
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KitoruViewModelTest {

    private fun curso(id: String, code: String) = Course(id = id, code = code, name = code)

    private fun inscripcion(
        id: String,
        courseId: String,
        role: CourseRole,
        visibility: kitoru.domain.ChatVisibility = kitoru.domain.ChatVisibility.FULL,
    ) = Enrollment(
        id = id,
        courseId = courseId,
        userId = "u1",
        displayName = "Ana",
        role = role,
        visibility = visibility,
    )

    private fun vm(
        scope: kotlinx.coroutines.CoroutineScope,
        courses: FakeCourseRepository,
        enrollments: FakeEnrollmentRepository,
    ) = KitoruViewModel(
        parentScope = scope,
        courses = courses,
        enrollments = enrollments,
        profiles = FakeProfileRepository(),
    )

    @Test
    fun `la carga separa las catedras de docente y de alumno`() = runTest {
        val courses = FakeCourseRepository(listOf(curso("c1", "IA"), curso("c2", "PROG")))
        val enrollments = FakeEnrollmentRepository(
            listOf(
                inscripcion("e1", "c1", CourseRole.TITULAR),
                inscripcion("e2", "c2", CourseRole.ALUMNO),
            ),
        )
        val vm = vm(this, courses, enrollments)
        advanceUntilIdle()

        assertEquals(listOf("c1"), vm.teachingCourses.map { it.id })
        assertEquals(listOf("c2"), vm.learningCourses.map { it.id })
        assertEquals(2, vm.allCourses.size)
        assertNull(vm.error)
    }

    @Test
    fun `un fallo del repositorio deja un error en español y no tira`() = runTest {
        val courses = FakeCourseRepository(failWith = IllegalStateException("boom"))
        val vm = vm(this, courses, FakeEnrollmentRepository())
        advanceUntilIdle()

        assertNotNull(vm.error)
        assertTrue(vm.error!!.contains("No se pudieron cargar las cátedras"))
        assertTrue(vm.error!!.contains("boom"))
    }

    @Test
    fun `navegar limpia el error anterior`() = runTest {
        val courses = FakeCourseRepository(failWith = IllegalStateException("boom"))
        val vm = vm(this, courses, FakeEnrollmentRepository())
        advanceUntilIdle()
        assertNotNull(vm.error)

        vm.go(AppScreen.StudentHome)
        assertNull(vm.error)
        assertEquals(AppScreen.StudentHome, vm.screen)
    }

    @Test
    fun `la pila de navegacion apila y vuelve`() = runTest {
        val vm = vm(this, FakeCourseRepository(), FakeEnrollmentRepository())
        vm.go(AppScreen.StudentHome)
        vm.go(AppScreen.StudentChat("c1", null))
        assertEquals(AppScreen.StudentChat("c1", null), vm.screen)
        assertTrue(vm.canGoBack)

        assertTrue(vm.back())
        assertEquals(AppScreen.StudentHome, vm.screen)
    }

    @Test
    fun `el rol activo sale de la inscripcion de la cátedra`() = runTest {
        val courses = FakeCourseRepository(listOf(curso("c1", "IA")))
        val enrollments = FakeEnrollmentRepository(
            listOf(inscripcion("e1", "c1", CourseRole.ALUMNO, kitoru.domain.ChatVisibility.METRICS_ONLY)),
        )
        val vm = vm(this, courses, enrollments)
        advanceUntilIdle()

        vm.openCourse("c1", AppScreen.StudentHome)
        assertEquals(CourseRole.ALUMNO, vm.activeRole)
        // El docente ve métricas pero no el texto.
        assertTrue(vm.activeVisibility.showsMetrics)
        assertTrue(!vm.activeVisibility.showsText)
    }

    @Test
    fun `cerrar el ViewModel cancela los jobs`() = runTest {
        val courses = FakeCourseRepository(failWith = IllegalStateException("boom"))
        val vm = vm(this, courses, FakeEnrollmentRepository())
        advanceUntilIdle()
        vm.close()
        assertTrue(vm.closed)
        // Después de cerrar, una carga nueva no toca el estado.
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.error)
    }
}
```

### 9.5 El test del `ChatViewModel` (streaming, cola y cancelación)

Se necesita un `LlmGateway` falso:

```kotlin
// ui/src/commonTest/kotlin/kitoru/ui/support/FakeGateway.kt
package kitoru.ui.support

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import llm.api.LlmChunk
import llm.api.LlmGateway
import llm.api.ToolSpec
import llm.config.ChatMessage

/**
 * Gateway falso que emite los deltas indicados, con una pausa entre cada uno.
 *
 * La pausa permite cambiar de hilo **en medio** de la respuesta y comprobar el
 * guard de identidad.
 */
class FakeGateway(
    private val deltas: List<String> = listOf("Hola", " ", "mundo"),
    private val delayMs: Long = 0,
) : LlmGateway {
    override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> = flow {
        for (d in deltas) {
            if (delayMs > 0) delay(delayMs)
            emit(LlmChunk.Text(d))
        }
    }
    override fun close() {}
}
```

Y los tests:

```kotlin
// ui/src/commonTest/kotlin/kitoru/ui/chat/ChatViewModelTest.kt
package kitoru.ui.chat

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatViewModelTest {

    /**
     * Construye un ViewModel de chat con todo falso.
     *
     * El `scope` que se le pasa es el del `TestScope`: por eso las corrutinas
     * del ViewModel son deterministas y `advanceUntilIdle()` las avanza.
     */
    private fun buildChatVm(
        scope: kotlinx.coroutines.CoroutineScope,
        gateway: FakeGateway = FakeGateway(),
        courseId: String = "c1",
    ): ChatViewModel {
        val course = kitoru.domain.Course(id = courseId, code = "IA", name = "Inteligencia Artificial")
        val enrollment = kitoru.domain.Enrollment(
            id = "e1", courseId = courseId, userId = "u1",
            displayName = "Ana", role = kitoru.domain.CourseRole.ALUMNO,
        )
        val vm = ChatViewModel(
            parentScope = scope,
            course = course,
            enrollment = enrollment,
            threadRepo = FakeThreadRepository(),
            messageRepo = FakeMessageRepository(),
            kb = rag.retrieval.KnowledgeBase(),
        )
        // El motor ya está listo: el test no quiere arrancar gateways.
        vm.gateway = gateway
        vm.engine = rag.engine.RagEngine(
            cfg = rag.config.RagConfig(),
            kb = rag.retrieval.KnowledgeBase(),
            llm = gateway,
            systemPrompt = { "Sos el tutor." },
        )
        vm.ready = true
        return vm
    }

    @Test
    fun `updateLast es O(1): no copia la lista`() = runTest {
        val vm = buildChatVm(this)
        vm.messages += UiChatMessage("m1", "user", "hola")

        val lista = vm.messages          // la MISMA instancia
        val antes = vm.messages.size

        vm.updateLast(text = "hola mundo")

        assertSame(lista, vm.messages)    // no se reemplazó la lista
        assertEquals(antes, vm.messages.size)   // no creció
        assertEquals("hola mundo", vm.messages.last().text)
    }

    @Test
    fun `enviar agrega la pregunta y el placeholder del asistente`() = runTest {
        val vm = buildChatVm(this)
        vm.send("¿qué es un autómata?")
        advanceUntilIdle()

        assertEquals(2, vm.messages.size)
        assertEquals("user", vm.messages[0].role)
        assertEquals("assistant", vm.messages[1].role)
        assertFalse(vm.messages[1].streaming)
        assertTrue(vm.messages[1].text.isNotBlank())
    }

    @Test
    fun `lo que se escribe mientras responde queda en cola`() = runTest {
        val vm = buildChatVm(this, gateway = FakeGateway(deltas = listOf("a"), delayMs = 50))
        vm.send("primera")
        vm.send("segunda")
        assertEquals(listOf("segunda"), vm.queuedMessages)
    }

    @Test
    fun `la respuesta de un hilo no se escribe en otro`() = runTest {
        val vm = buildChatVm(this, gateway = FakeGateway(deltas = listOf("a", "b"), delayMs = 50))
        vm.send("pregunta del hilo A")
        // Mientras el modelo responde, el usuario cambia de hilo.
        vm.switchThread("otro-hilo")
        advanceUntilIdle()

        // El hilo nuevo NO tiene la respuesta del hilo A.
        assertTrue(vm.messages.none { it.text.contains("a") && it.role == "assistant" })
    }

    @Test
    fun `cancelar corta la generación y deja busy en false`() = runTest {
        val vm = buildChatVm(this, gateway = FakeGateway(deltas = listOf("a"), delayMs = 1_000))
        vm.send("pregunta")
        assertTrue(vm.busy)

        vm.cancel()
        advanceUntilIdle()

        assertFalse(vm.busy)
    }

    @Test
    fun `cancelar resuelve el permiso pendiente con DENY`() = runTest {
        val vm = buildChatVm(this)
        val deferred = kotlinx.coroutines.CompletableDeferred<rag.agent.PermissionDecision>()
        vm.permissionAnswer = deferred
        vm.permissionRequest = rag.agent.PermissionRequest("c1", "tool", rag.agent.ToolCategory.FILES, "escribir un archivo")

        vm.cancel()

        assertEquals(rag.agent.PermissionDecision.DENY, deferred.await())
    }
}
```

### 9.6 Qué **no** se testea aquí (y por qué)

| Cosa | Dónde se testea |
|---|---|
| La recomposición real (que la burbuja no se repinte) | `runComposeUiTest` de CMP, sólo si hace falta; el resto se cubre con la aserción de identidad (`assertSame`) |
| El `followToBottom` del auto-scroll | Como **función pura** aparte del composable (`scrollTargetForKey`): `ui/src/commonTest` |
| El Markdown, el resaltado de código, el renderer de fórmulas | `ui/src/commonTest` (son funciones puras) |
| La analítica | Ya es pura: `core/src/commonTest` |
| El RAG y el agente | Ya existen: `backend/src/desktopTest` |

### 9.7 La regla de oro del testing del estado

> **Si una regla se puede comprobar sin Compose, se comprueba sin Compose.**
> Los `mutableStateOf` no necesitan recomposición para leerse: el test verifica
> **el estado y las transiciones**, no el render.

---

## 10. Checklist de implementación

### 10.1 Cambios en `KitoruViewModel` (los que hay que hacer ahora)

- [ ] `private set` → `internal set` en todas las propiedades de estado.
- [ ] `scope` inyectado por constructor (`parentScope`), con el dispatcher heredado.
- [ ] `io: CoroutineDispatcher = kitoru.platform.ioDispatcher` inyectado.
- [ ] `error: String` → `error: AppError?` (nullable) + `clearError()`.
- [ ] `screen: AppScreen` → `nav: NavStack<AppScreen>` + `val screen get() = nav.current`.
- [ ] `go`/`back`/`openCourse` reescritos sobre la pila (`push`/`pop`/`replace`/`resetTo`).
- [ ] `fun close()` que cancela el scope y marca `closed = true`.
- [ ] `postToMain` para las escrituras que vienen del dispatcher de fondo.

### 10.2 Archivos nuevos

- [ ] `ui/commonMain/.../core/NavStack.kt`
- [ ] `ui/commonMain/.../core/AppError.kt`
- [ ] `ui/commonMain/.../core/LoadState.kt`
- [ ] `ui/commonMain/.../platform/BackHandler.kt` (+ `androidMain` + `desktopMain`)
- [ ] `ui/commonMain/.../chat/UiChatMessage.kt`
- [ ] `ui/commonMain/.../chat/ChatViewModel.kt`
- [ ] `ui/commonMain/.../chat/ChatThreads.kt`
- [ ] `ui/commonMain/.../chat/ChatBoot.kt`
- [ ] `ui/commonMain/.../chat/ChatPermissions.kt`
- [ ] `ui/commonTest/.../support/Fakes.kt`, `support/FakeGateway.kt`
- [ ] `ui/commonTest/.../KitoruViewModelTest.kt`, `chat/ChatViewModelTest.kt`

### 10.3 Reglas que no se negocian

1. `mutableStateOf` **sólo** en `:ui` y **sólo** dentro de un ViewModel.
2. El estado se escribe **en un solo hilo** (el de UI).
3. El I/O va **siempre** por `kitoru.platform.ioDispatcher` (nunca `Dispatchers.IO` en `commonMain`).
4. Ninguna acción toca el disco: todo va por repositorios.
5. Toda acción sensible lleva **la guarda de permiso dentro del ViewModel**
   (no sólo en la UI): `if (!role.canConfigureModel) { error = ...; return }`.
6. Los mensajes del chat son un `SnapshotStateList`, nunca un `mutableStateOf(list)`.
7. Todo ViewModel con `scope` tiene `close()`, y la UI lo llama en `DisposableEffect`.
8. La lógica por área va en funciones de extensión `internal`, en archivos siblings.
9. Todo mensaje de error está **en español** y dice qué hacer.
10. La navegación es una pila manual; `BackHandler` es un `expect/actual`.

---

## 11. Referencias

- `FunshiEngineAI/frontend/src/main/kotlin/app/chat/ChatViewModel.kt` — patrón base
  (`mutableStateOf`, `internal set`, `originChatId`, cola de mensajes, `shutdown()`).
- `FunshiEngineAI/frontend/src/main/kotlin/app/chat/ChatLibrary.kt` — patrón de las
  funciones de extensión por área.
- `Kitoru/ui/src/commonMain/kotlin/kitoru/ui/KitoruViewModel.kt` — el ViewModel raíz
  actual (base de §1.5).
- `Kitoru/ui/src/commonMain/kotlin/kitoru/ui/chat/ChatEngine.kt` — el motor de chat
  ya construido en `:ui`.
- `Kitoru/core/src/commonMain/kotlin/kitoru/platform/Dispatchers.kt` — `ioDispatcher`.
- `Kitoru/backend/src/commonMain/kotlin/rag/agent/AgentLoop.kt` — el lazo del agente
  (y su regla de propagar la `CancellationException`).
- `Kitoru/llm-api/src/commonMain/kotlin/llm/api/LlmGateway.kt` — `chatStream` con
  `.flowOn(ioDispatcher)`: la red no bloquea el hilo de UI.
- `docs/design/student/02-chat.md` — la pantalla de chat (define `UiChatMessage`,
  `updateLast` y el auto-scroll).
- `docs/design/teacher/01-navegacion.md` — la navegación del docente
  (`TeacherNavState`, `PlatformBackHandler`, el patrón de ViewModel KMP).
- `docs/design/CONTEXTO.md` — las reglas del proyecto.
