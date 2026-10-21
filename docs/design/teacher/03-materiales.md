# 03 · Materiales de la cátedra (pantalla del docente)

> Subagente: diseño de la pantalla de gestión de materiales.
> Alcance: **subida → extracción → indexación → previsualización → diagnóstico**.
> Todo lo que sigue es diseño, no código: los nombres de archivo, tipos y firmas
> son los que deberían existir en el repo.

---

## 0. Qué tiene que resolver esta pantalla

El docente sube material a la cátedra y **no vuelve a mirarlo**. El material se
convierte en el corpus del RAG que usan los alumnos. El 80 % de las quejas
típicas ("la IA no encuentra lo que le pregunto") son, en realidad, **problemas
del material**: un PDF escaneado sin capa de texto, un DOCX whose tables were
parsed wrong, un slide deck exported as images, un `chunkSize` too big.

Por eso esta pantalla no es un "gestor de archivos". Es una **mesa de trabajo
de diagnóstico**: el docente sube, mira qué texto salió, mira cómo se troceó,
y **prueba una pregunta contra el material** para ver exactamente qué recupera
el índice. La tesis de diseño es:

> Si la IA no encuentra lo que busco, el problema es el material, no el modelo.
> Y para probarlo hay que poder aislar el material.

De ahí salen las tres pestañas del detalle: **Texto**, **Fragmentos**,
**Probar pregunta**.

---

## 1. Layout de la pantalla

### 1.1 Escritorio (ancho ≥ 840 dp): dos paneles

```
┌────────────────────────────────────────────────────────────────────────────────┐
│ Materiales · IA-2026-1 · Inteligencia Artificial      [Subir material]  ⟳     │
│ Índice: 12 documentos · 87 fragmentos · reindexado hace 2 min                  │
├───────────────────────────┬────────────────────────────────────────────────────┤
│ [🔍 buscar…]  [Todos ▾] │  Apuntes-IA.pdf                          ⋮  [Oculto] │
│                           │  PDF · 12 págs · 48.230 car. · pdfbox 2.0.27        │
│ ▸ 📄 Apuntes-IA.pdf   ✓   │  ┌ Texto ┬ Fragmentos ┬ Probar pregunta ┐        │
│ ▸ 📊 Diapositivas-1.pptx ⚠ │  │                                           │        │
│ ▸ 📝 Parcial-1-2025.pdf ✓ │  │  (detalle de la pestaña activa)        │        │
│ ▸ 🔗 Repo de la cátedra  · │  │                                           │        │
│                           │  └───────────────────────────────────────────┘        │
│ + Subir material          │                                                     │
└───────────────────────────┴────────────────────────────────────────────────────┘
```

- **Panel izquierdo — `MaterialListPane`** (ancho fijo 320–380 dp):
  buscador, filtro por tipo y por visibilidad, lista de `MaterialRow`, y el
  botón **Subir material** al pie. Es la zona donde cae el *drop*.
- **Panel derecho — `MaterialDetailPane`**: cabecera + pestañas. Cuando no hay
  material seleccionado muestra el estado vacío ("Elegí un material para ver su
  texto, sus fragmentos y probar preguntas contra él").
- Divisor vertical arrastrable entre paneles (`rememberSplitterState`), con
  rango 280–560 dp, persistido por cátedra.

### 1.2 Android (ancho < 840 dp): lista → detalle

Una sola columna, navegación con **retroceso**:

```
MaterialRoute.List ──tap en una fila──▶ MaterialRoute.Detail(materialId)
        ▲                                        │
        └────────────── atrás (BackHandler) ──────┘
```

- `MaterialListScreen`: la lista completa + el FAB **+** (subir) + una barra
  inferior con "Subir" y "Compartir a Kitoru" (el `ACTION_SEND` inbound).
- `MaterialDetailScreen`: la cabecera + pestañas; el botón de atrás de la
  barra superior (y `BackHandler` del sistema) vuelve a la lista.
- La navegación es **local** a la pantalla, sin agregar todavía la librería de
  navegación: un `sealed interface MaterialRoute` + `var route by
  rememberSaveable { mutableStateOf<MaterialRoute>(MaterialRoute.List) }`. Cuando
  el resto de la app tenga un grafo de navegación real, esto se reemplaza por
  `NavHost` sin cambiar las pantallas.

### 1.3 Cómo se decide el layout

`BoxWithConstraints` en `MaterialsScreen`:

```kotlin
@Composable
fun MaterialsScreen(vm: MaterialViewModel, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) TwoPaneLayout(vm)   // lista | detalle
        else SinglePaneLayout(vm)                   // lista → detalle
    }
}
```

Umbral 840 dp (el breakpoint de "medium" de Material 3 / adaptive).

---

## 2. Subida de archivos

### 2.1 El seam: una interfaz, no un `expect fun` pelado

El `expect fun platformFilePicker(): ...` que se suele proponer **no funciona**
en Android, y la razón es concreta:

> `ActivityResultContracts.OpenDocument` se lanza con un
> `ActivityResultLauncher`, y ese launcher **tiene que crearse dentro de una
> composición** (`rememberLauncherForActivityResult`) porque se registra contra
> el `ComponentActivity` antes de que la actividad llegue a `onStart`. Un
> `expect fun` que devuelve un objeto no tiene dónde guardar ese launcher, y
> una función que *abre* el diálogo de forma bloqueante no puede devolver un
> `Uri` sin volverse un `callback` disfrazado.

Por eso el seam es **una interfaz con callback**, y el *factory* sí es un
`expect` de tipo Compose:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/FilePicker.kt
package kitoru.platform

/** Una petición de selección de archivos. */
data class FilePickRequest(
    val title: String = "Seleccionar material",
    /** MIME types permitidos. Vacío = cualquiera. */
    val mimeTypes: List<String> = emptyList(),
    val allowMultiple: Boolean = true,
)

/** Un archivo elegido por el usuario. */
data class PickedFile(
    /** Nombre visible (con extensión). */
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    /**
     * Identificador opaco de la plataforma:
     *  - escritorio: ruta absoluta del archivo
     *  - Android: URI `content://…`
     */
    val handle: String,
)

/**
 * Selector de archivos de la plataforma.
 *
 * Vive en `:core` (sin dependencias de Compose) para que la lógica de
 * ingestión sea testeable sin UI.
 */
interface FilePicker {
    /**
     * Abre el selector. El resultado vuelve en [onResult] (lista vacía si el
     * usuario canceló). No debe llamarse desde un hilo de UI que no pueda
     * mostrar un diálogo modal.
     */
    fun launch(request: FilePickRequest, onResult: (List<PickedFile>) -> Unit)

    /**
     * Copia el archivo elegido a [dest] y devuelve cuántos bytes escribió.
     *
     * Va por bytes y no por `InputStream` porque `java.io.InputStream` no
     * existe en `commonMain`: la copia se hace en trozos de 64 KB contra el
     * [FileSystem] común, así un PDF de 50 MB no pasa entero por memoria.
     */
    suspend fun copyTo(file: PickedFile, dest: FilePath, fs: FileSystem): Long
}
```

Y el factory, en la capa de UI (que sí depende de Compose):

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/materials/FilePickerFactory.kt
/** Devuelve el selector de archivos de la plataforma, listo para lanzar. */
@Composable
expect fun rememberFilePicker(): FilePicker
```

- `ui/src/desktopMain/…/DesktopFilePicker.kt` → implementación AWT.
- `ui/src/androidMain/…/AndroidFilePicker.kt` → implementación SAF.

`FilePicker` además se puede falsear en tests (`FakeFilePicker`) para probar el
flujo de subida sin tocar el disco.

### 2.2 Escritorio: `java.awt.FileDialog`

**Por qué AWT y no Swing.** `javax.swing.JFileChooser` funciona, pero su look &
feel no es nativo en Windows y en Linux (GTK) se ve ajeno. `java.awt.FileDialog`
es el diálogo **nativo** del sistema en Windows, macOS y Linux. Y como ya
`desktopMain` depende de `kotlinx-coroutines-swing`, no hay que sumar nada.

```kotlin
// ui/src/desktopMain/kotlin/kitoru/ui/teacher/materials/DesktopFilePicker.kt
class DesktopFilePicker : FilePicker {

    override fun launch(request: FilePickRequest, onResult: (List<PickedFile>) -> Unit) {
        // Compose Desktop corre sobre el EDT de AWT: el FileDialog es modal y
        // bloquea ese hilo, así que hay que abrirlo desde el hilo de UI.
        val dialog = java.awt.FileDialog(
            null as java.awt.Frame?, request.title, java.awt.FileDialog.LOAD,
        ).apply {
            isMultipleMode = request.allowMultiple
            isVisible = true          // modal: bloquea hasta que se cierra
        }
        val dir = dialog.directory
        val file = dialog.file      // null = cancelado
        if (dir == null || file.isNullOrBlank()) {
            onResult(emptyList())
            return
        }
        val picked = file.split(File.pathSeparatorChar).map { name ->
            val f = java.io.File(dir, name)
            PickedFile(
                name = f.name,
                sizeBytes = f.length(),
                mimeType = guessMime(f.name),
                handle = f.absolutePath,
            )
        }
        onResult(picked)
    }
}
```

Detalles y trampas:

| Tema | Comportamiento |
|---|---|
| Cancelar | `dialog.file == null` (o `""` en algunos LAF) → `onResult(emptyList())`. En macOS, `directory` también puede venir `null`. |
| Multi-selección | `isMultipleMode = true` (Java 7+). En macOS el separador es `:` y en Windows `;` → de ahí el `File.pathSeparatorChar`. |
| Filtros por extensión | `java.awt.FileDialog` **no** soporta filtros. Se valida **después** de elegir: si la extensión no está soportada, se descarta el archivo y se avisa (§7). |
| Hilo | `launch` se llama desde un callback de Compose (hilo de UI = EDT). El diálogo modal congela la ventana, que es lo esperado para un modal. No llamar desde `Dispatchers.IO`. |
| Filtro nativo (alternativa) | Si se quiere filtro por extensión nativo, usar `javax.swing.JFileChooser` con `FileFilter` + `isMultiSelectionEnabled`. Mismo `FilePicker`, misma interfaz. |
| Alternativa de Compose | Compose Multiplatform 1.7.3 trae `androidx.compose.ui.window.FileDialog` / `rememberFileDialog` (Swing). Se descarta preferentemente porque no es nativo en Windows. |
| `java.nio.file` | `java.nio.file.FileSystem` **no es un selector de archivos** (es la abstracción de un sistema de archivos, `FileSystems.getDefault()`). Sirve para leer, no para elegir. La lectura del lado escritorio se hace con el `FileSystem` de `kitoru.platform`, que ya está sobre `java.io.File`. |

### 2.3 Android: SAF (`ActivityResultContracts.OpenDocument`)

```kotlin
// ui/src/androidMain/kotlin/kitoru/ui/teacher/materials/AndroidFilePicker.kt
@Composable
actual fun rememberFilePicker(): FilePicker {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<FilePickRequest?>(null) }

    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val req = pending
        pending = null
        if (uri == null) return@rememberLauncherForActivityResult
        onResultFromUri(context, uri, req)
    }
    val openMultiple = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> /* idem, una PickedFile por uri */ }
    val getContent = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> /* idem */ }

    return remember(openDoc, openMultiple, getContent) {
        object : FilePicker {
            override fun launch(request: FilePickRequest, onResult: (List<PickedFile>) -> Unit) {
                pending = request
                if (request.allowMultiple) {
                    openMultiple.launch(request.mimeTypes.toTypedArray().ifEmpty { arrayOf("*/*") })
                } else {
                    openDoc.launch(request.mimeTypes.toTypedArray().ifEmpty { arrayOf("*/*") })
                }
            }
            override suspend fun copyTo(file: PickedFile, dest: FilePath, fs: FileSystem): Long =
                copyContentUriToFile(context, Uri.parse(file.handle), dest, fs)
        }
    }
}
```

**`OpenDocument` vs `GetContent`.** Se usan los dos, con un choice sheet:

- `OpenDocument` (principal): abre el **Storage Access Framework** del sistema
  (el "Explorador de archivos" de Android 11+). Devuelve un URI con permiso
  **persistente**: se puede llamar a
  `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`
  y seguir leyendo el archivo días después, sin volver a pedirlo.
- `GetContent` (alternativa, botón "Buscar en el dispositivo"): algunos
  exploradores y galerías no implementan bien `DocumentsProvider`; `GetContent`
  los cubre. Da un permiso de un solo uso, así que hay que copiar el archivo
  **antes** de perderlo (que es justo lo que hace el pipeline).
- `OpenMultipleDocuments` para multi-selección.

**Detalles que hay que resolver sí o sí en Android:**

1. `OpenDocument` devuelve un URI, no una ruta: **nunca** se puede usar
   `File(uri.path)`. El acceso es por `contentResolver.openInputStream(uri)`.
2. El MIME del archivo hay que sacarlo de
   `contentResolver.getType(uri)`, y el **nombre** de
   `DocumentsContract.Document.COLUMN_DISPLAY_NAME` (el URI no siempre trae
   extensión: `content://…/document/1234`).
3. `takePersistableUriPermission` puede tirar `SecurityException` si el
   proveedor no lo soporta → `runCatching { … }`.
4. La copia se hace en trozos:
   ```kotlin
   context.contentResolver.openInputStream(uri)!!.use { input ->
       var total = 0L
       val buf = ByteArray(64 * 1024)
       while (true) {
           val n = input.read(buf); if (n <= 0) break
           fs.writeBytes(dest, buf.copyOf(n), append = total > 0)
           total += n
       }
       total
   }
   ```
   → requiere agregar `writeBytes(path, bytes, append)` al `FileSystem` de
   `:core` (§4.1).

### 2.4 Por qué SAF y no `READ_EXTERNAL_STORAGE`

No es una preferencia estética, es la única opción que funciona y la única que
se puede publicar:

1. **Scoped storage (Android 10 / API 29)**: desde ahí, `READ_EXTERNAL_STORAGE`
   ya no da acceso libre al disco. En Android 11+ la permisos solo sirve para
   archivos multimedia de *otras* apps, y el acceso a documentos requiere
   SAF o `MANAGE_EXTERNAL_STORAGE`.
2. **`READ_EXTERNAL_STORAGE` es una permissão de runtime**: implica un diálogo
   de permiso al usuario, que además se deniega mucho. Una app de una
   universidad pide "acceso a todos tus archivos" para subir un apunte: es
   dystopian y anti-Play-Store.
3. **`MANAGE_EXTERNAL_STORAGE` ("Acceso a todos los archivos")** está reservada
   a gestores de archivos, apps de backup y antivirus. Google Play la rechaza
   para apps educativas salvo excepciones justificadas. Kitoru no califica.
4. **SAF concede el permiso *por archivo*, elegido por el usuario**: la app ve
   exactamente el PDF que el docente eligió y nada más. Es el mismo criterio que
   ya usa el proyecto para los chats ("el docente no ve lo que no debe"): el
   mínimo privilegio.
5. **Cero permisos en el manifiesto**: el formulario de privacidad de Play
   queda limpio y no hay permission-gate en la primera pantalla.
6. **Permisos persistentes**: `takePersistableUriPermission` permite releer el
   archivo más tarde (por ejemplo, para "volver a extraer") sin volver a
   molestar al usuario.
7. **Compatible desde API 19** y con comportamiento parejo entre fabricantes
   (la alternativa "abrir con" del sistema también es SAF por debajo).

Contraargumentos honestos y cómo se mitigan:

| Contra | Mitigación |
|---|---|
| SAF es más lento para elegir muchos archivos | Se ofrece multi-selección (`OpenMultipleDocuments`) y la vía rápida de *drag & drop* en escritorio. |
| El explorador de SAF en algunos Samsung/Xiaomi es feo | Se offers `GetContent` como alternativa, y el `intent` se puede filtrar por MIME (`EXTRA_MIME_TYPES`) para acotar a documentos. |
| Con SAF no se puede listar una carpeta | No hace falta: el docente sube archivos, no directorios. Si más adelante se quiere "subir una carpeta", `ACTION_OPEN_DOCUMENT_TREE` (API 21+) da la carpeta y se recorre con `DocumentsContract`. |
| El permiso caduca | `takePersistableUriPermission` + copiar el archivo al almacén de la cátedra en el momento de la subida (que es lo que hace el pipeline). |

### 2.5 El diálogo de "nuevo material"

Después de elegir, se abre `MaterialUploadDialog`, que permite configurar cada
archivo antes de procesar:

```
┌ Subir material ─────────────────────────────────────┐
│  📄 Parcial-1-2025.pdf            2,4 MB            │
│  Título    [Parcial 1 2025                    ]      │
│  Tipo      [Documento ▾]                             │
│  Etiquetas [parcial ▾][+ agregar]                     │
│  Visible para los alumnos                    [✓]     │
│                                                     │
│  Formatos: PDF, Word, PowerPoint, Markdown, Texto, CSV│
│                              [Cancelar]  [Subir]     │
└─────────────────────────────────────────────────────┘
```

- Multi-archivo: una fila por archivo, con un checkbox de selección.
- El **tipo** se prellena con la heurística de §4.2 y el **título** con el
  nombre del archivo sin extensión.
- Se puede **subir y seguir** (no cerrar el diálogo) para cargar varios
  cursos de materiales de corrido.

El *drag & drop* de escritorio **no** abre este diálogo: ingiere directo con
los valores por defecto y muestra un snackbar ("Se subió «X»") con un atajo
"Editar". Es el camino rápido para quien ya sabe lo que está subiendo.

---

## 3. Drag & drop (escritorio) y compartir desde otra app (Android)

### 3.1 Distinguir dos cosas que se confunden

El patrón de `FunshiEngineAI/frontend/.../ChatSidebar.kt`

```kotlin
.onPointerEvent(PointerEventType.Press) { e ->
    if (e.button != PointerButton.Secondary) return@onPointerEvent
    openMenu(rowId, e.changes.first().position)
}
```

**es el menú contextual (click derecho)**, no drag & drop de archivos. Son dos
mecanismos distintos y en Kitoru se usan los dos:

| Mecanismo | API | Para qué |
|---|---|---|
| Menú contextual de la fila | `Modifier.onPointerEvent(PointerEventType.Press)` + `PointerButton.Secondary` (copiado de `ChatSidebar.kt`) | renombrar, editar, ocultar, reindexar, eliminar |
| Arrastrar archivos desde el sistema | `Modifier.dragAndDropTarget` (nuevo en Compose Multiplatform 1.7.0, disponible en common y funcional en desktop) | subir PDFs arrastrándolos desde el explorador de archivos |

El *drop* de archivos del sistema operativo **no llega como eventos de
puntero**: llega por el mecanismo de drag & drop de AWT/Swing. Por eso
`onPointerEvent` no sirve para eso.

### 3.2 Drop de archivos en escritorio

Compose Multiplatform 1.7.3 (la versión del repo) implementó
`Modifier.dragAndDropTarget` para desktop. El evento expone el `Transferable`
de AWT, del cual se saca la lista de archivos.

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/materials/MaterialDropTarget.kt
/**
 * Contenedor que acepta archivos arrastrados desde el escritorio.
 * En Android es un no-op (no hay drop de archivos del sistema).
 */
@Composable
expect fun MaterialDropTarget(
    enabled: Boolean,
    onFilesDropped: (List<PickedFile>) -> Unit,
    content: @Composable () -> Unit,
)
```

- **Escritorio** (`ui/src/desktopMain/…/DesktopMaterialDropTarget.kt`):

```kotlin
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun MaterialDropTarget(
    enabled: Boolean,
    onFilesDropped: (List<PickedFile>) -> Unit,
    content: @Composable () -> Unit,
) {
    var hovering by remember { mutableStateOf(false) }
    val target = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) { hovering = true }
            override fun onEnded(event: DragAndDropEvent) { hovering = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val transferable = event.awtTransferable          // sólo desktop
                val files = transferable.getTransferData(DataFlavor.javaFileListFlavor)
                        as? List<*> ?: return false
                onFilesDropped(files.filterIsInstance<java.io.File>().map { f ->
                    PickedFile(f.name, f.length(), guessMime(f.name), f.absolutePath)
                })
                return true
            }
        }
    }
    Box(
        Modifier
            .dragAndDropTarget(shouldStartDragAndDrop = { enabled }, target = target)
            .then(if (hovering) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier),
    ) { content() }
}
```

  - `event.awtTransferable` es **sólo de escritorio**: por eso el `expect` /
  `actual` (en Android el `actual` es un `Box` pelado que ignora el drop).
  - `DataFlavor.javaFileListFlavor` es el sabor estándar para "archivos".
    Se aceptan también `DataFlavor.stringFlavor` (cuando el origen pone una
    ruta como texto) como plan B.
  - El drop se aplica al **root de la pantalla**, no a la lista: el docente
    puede soltar en cualquier parte. Aparece una capa translúcida
    ("Soltá los archivos para subirlos").
  - `onDrop` corre en el EDT: se copia la lista y se delega al ViewModel
    (`viewModel.importFiles(files)`), que hace el trabajo pesado en un
    `Dispatchers.IO`.

- **Android** (`ui/src/androidMain/…/AndroidMaterialDropTarget.kt`): `actual`
  que solo renderiza `content` (no hay drag & drop del sistema hacia una app
  Android; la vía equivalente es *compartir*, §3.3).

### 3.3 Compartir desde otra app (Android) — `ACTION_SEND`

El docente puede, desde el explorador de archivos o desde Drive, elegir
"Compartir → Kitoru" y mandar el PDF a la cátedra.

**Manifiesto** (`android/src/main/AndroidManifest.xml`):

```xml
<activity
    android:name=".MainActivity"
    android:launchMode="singleTask"
    android:exported="true">
  <intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="application/pdf" />
    <data android:mimeType="application/vnd.openxmlformats-officedocument.wordprocessingml.document" />
    <data android:mimeType="application/vnd.openxmlformats-officedocument.presentationml.presentation" />
    <data android:mimeType="text/plain" />
    <data android:mimeType="text/markdown" />
    <data android:mimeType="text/csv" />
  </intent-filter>
  <!-- y el equivalente para ACTION_SEND_MULTIPLE -->
</activity>
```

**Recepción** (`ui/src/androidMain/…/ShareIntentHandler.kt`):

```kotlin
/** Traduce un Intent de compartir a una lista de archivos a importar. */
fun Intent.sharedUris(): List<Uri> = when (action) {
    Intent.ACTION_SEND -> listOfNotNull(getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    Intent.ACTION_SEND_MULTIPLE ->
        getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    else -> emptyList()
}
```

- `MainActivity` (con `launchMode="singleTask"`) captura el intent en
  `onCreate` **y** en `onNewIntent` (compartir con la app ya abierta).
- Los URIs se pasan al árbol de Compose mediante un `MutableSharedFlow` de
  nivel de app:

  ```kotlin
  // ui/src/androidMain/…/KitoruAppState.kt
  class KitoruAppState {
      val incomingFiles = MutableSharedFlow<List<PickedFile>>(extraBufferCapacity = 4)
  }
  ```

  `MainActivity` hace `appState.incomingFiles.tryEmit(files)` y el
  `MaterialViewModel` lo recoge con `LaunchedEffect`.
- **¿A qué cátedra?** Si el docente no está dentro de una cátedra, se abre un
  diálogo "¿A qué cátedra sumás este material?" con la lista de cátedras donde
  es titular o ayudante. Si está en una sola, se usa esa sin preguntar.
- El archivo compartido se copia al almacén de la cátedra con el mismo
  `FilePicker.copyTo` (usando `contentResolver`), y entra al mismo pipeline.
- `takePersistableUriPermission` sobre el URI compartido suele fallar (el que
  comparte no es el propietario) → `runCatching`. No hace falta: el archivo se
  copia enseguida.

En escritorio, el equivalente es el *drag & drop* de §3.2 (no hay `ACTION_SEND`
en desktop; `java.awt.Desktop` con `mailto:` es para otra cosa).

---

## 4. Extracción de texto: el pipeline

### 4.1 El pipeline completo

```
  archivos elegidos
        │
        ▼
  1. COPIA      → courses/<courseId>/files/<materialId>__<nombre>.pdf
        │          (vía FilePicker.copyTo, en trozos de 64 KB)
        ▼
  2. DETECCIÓN  → MaterialFormat por extensión + firma binaria
        │
        ▼
  3. EXTRACCIÓN → TextExtractor.extract() por formato
        │          → ExtractionResult(text, warning, extractor, pageCount)
        ▼
  4. PERSISTENCIA→ Material(status=READY|NEEDS_REVIEW|FAILED, extractedText, …)
        │
        ▼
  5. TROCEADO   → Chunker.split(doc, course.chunkSize, 150)
        │
        ▼
  6. INDEXACIÓN → Bm25Index(chunks)   (CourseIndex.reindex)
        │
        ▼
  7. VERIF.     → el material sigue las reglas de la cátedra
```

`MaterialStatus` es un enum nuevo en el dominio, **persistido** (así una
interrupción no pierde el estado):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Course.kt (agregar)
@Serializable
enum class MaterialStatus(val label: String) {
    PENDING("Pendiente"),
    EXTRACTING("Extrayendo texto"),
    INDEXING("Indexando"),
    READY("Listo"),
    NEEDS_REVIEW("Requiere revisión"),
    FAILED("Error"),
}
```

Y `Material` gana estos campos (todos con valor por defecto → compatible con
los JSON ya guardados):

```kotlin
@Serializable
data class Material(
    val id: String,
    val courseId: String,
    val title: String,
    val kind: MaterialKind = MaterialKind.DOCUMENT,
    val path: String = "",
    val sizeBytes: Long = 0L,
    val uploadedBy: String = "",
    val uploadedAt: Long = 0L,
    val tags: List<String> = emptyList(),
    val visible: Boolean = true,
    val extractedText: String = "",
    val warning: String = "",

    // ---- nuevos ----
    /** Estado del pipeline de subida. */
    val status: MaterialStatus = MaterialStatus.PENDING,
    /** Detalle del estado: progreso o error (en español). */
    val statusDetail: String = "",
    /** Nombre original del archivo (puede diferir del título). */
    val originalName: String = "",
    /** Qué extractor produjo el texto (para mostrar y para auditar). */
    val extractor: String = "",
    /** Cuándo se extrajo por última vez. */
    val extractedAt: Long = 0L,
    /** Si el docente editó el texto a mano. */
    val editedManually: Boolean = false,
    /** Páginas del documento original (PDF/PPTX). */
    val pageCount: Int = 0,
    /** Fragmentos que aporta al índice (se recalcula al reindexar). */
    val chunkCount: Int = 0,
)
```

> **Nota de tamaño.** `extractedText` vive dentro de `config/materials.json`, y
> `JsonStore` reescribe el archivo completo en cada `save`. Con 50 materiales de
> 200 KB son 10 MB reescritos por cada guardado. **Mitigación para el
> milestone 1**: se acepta (es el modelo ya existente y el `CourseMaterialSource`
> ya lee de ahí). **Plan**: mover el texto a
> `courses/<courseId>/text/<materialId>.txt` y dejar en el JSON sólo los
> metadatos + los primeros 2 000 caracteres (previsualización), con
> `FileMaterialRepository` hidratando el texto completo al leer. Queda
> anotado como deuda, no como cambio de este diseño.

### 4.2 Detección de formato

```kotlin
// core/src/commonMain/kotlin/kitoru/material/MaterialFormat.kt
enum class MaterialFormat(
    val label: String,
    val extensions: Set<String>,
    val defaultKind: MaterialKind,
    val supportsText: Boolean,
) {
    PDF("PDF", setOf("pdf"), MaterialKind.DOCUMENT, true),
    DOCX("Word", setOf("docx"), MaterialKind.DOCUMENT, true),
    PPTX("PowerPoint", setOf("pptx"), MaterialKind.SLIDES, true),
    MARKDOWN("Markdown", setOf("md", "markdown"), MaterialKind.TRANSCRIPT, true),
    TEXT("Texto", setOf("txt", "text", "log"), MaterialKind.TRANSCRIPT, true),
    CSV("CSV", setOf("csv"), MaterialKind.OTHER, true),
    HTML("HTML", setOf("html", "htm"), MaterialKind.OTHER, true),
    LINK("Enlace", emptySet(), MaterialKind.LINK, true),
    UNKNOWN("Desconocido", emptySet(), MaterialKind.OTHER, false),
}
```

Detección en dos pasos:

1. **Por extensión** (rápido): `MaterialFormat.fromExtension(ext)`.
2. **Por firma binaria** (desconfía): se leen los primeros bytes.
   - `%PDF-` → PDF.
   - `PK\x03\x04` → ZIP → se mira qué entradas tiene:
     `word/document.xml` → DOCX, `ppt/presentation.xml` → PPTX,
     `mimetype` con `application/epub+zip` → EPUB (no soportado todavía).
   - `{\\rtf` → RTF (no soportado).
   - Si la extensión dice una cosa y la firma dice otra → **warning**:
     "El archivo se llama .pdf pero su contenido no es un PDF.".

Si el formato no soporta texto (`UNKNOWN`), el material se sube igual pero
queda en `NEEDS_REVIEW` con el aviso de §7, y el docente puede pegarle el
texto a mano (o usar un `.md`).

### 4.3 PDF — qué librería en cada plataforma

| Plataforma | Librería | Versión | Notas |
|---|---|---|---|
| Escritorio | `org.apache.pdfbox:pdfbox` | **2.0.36** | Artefacto JVM normal (`jar`). Es la línea 2.x para que el código sea equivalente al de Android. |
| Android | `com.tom-roush:pdfbox-android` | **2.0.27.0** | Port de PDFBox 2.0.27 a Android. **El groupId es `com.tom-roush` (con guion), no `com.tom_roush`.** Es un `.aar`: no se puede consumir desde un source set JVM pelado. |

Los dos exponen la misma API **pero con paquetes distintos**
(`org.apache.pdfbox.*` vs `com.tom_roush.pdfbox.*`), así que **no se puede
compartir el archivo fuente tal cual**. La solución es un *backend* chico con
todo el algoritmo en común:

```kotlin
// core/src/commonMain/kotlin/kitoru/material/PdfBackend.kt
package kitoru.material

import kitoru.platform.FilePath

/** Error de lectura de un PDF, con motivo ya en español. */
class PdfException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Lector de PDF de la plataforma.
 *
 * Cada plataforma implementa **sólo** la llamada a PDFBox; el algoritmo
 * (heurísticas, marcas de página, detección de escaneado) vive en común y
 * es testeable.
 */
interface PdfBackend {
    /**
     * Texto de cada página, en orden.
     * @throws PdfException si el PDF está protegido, corrupto o no es un PDF.
     */
    fun pageTexts(file: FilePath): List<String>
}
```

Y el algoritmo común:

```kotlin
// core/src/commonMain/kotlin/kitoru/material/PdfTextAssembler.kt
object PdfTextAssembler {

    /** Une las páginas en un texto con marcas de página legibles. */
    fun assemble(pages: List<String>): String =
        pages.mapIndexed { i, t ->
            "## Página ${i + 1}\n\n${t.trim()}"
        }.joinToString("\n\n")

    /**
     * Heurística de "PDF escaneado": no hay capa de texto.
     *
     * Si menos del 40 % de las páginas devuelven texto, el PDF es un escaneo
     * (o un PDF de imágenes). Se marca como [NeedsReview] con un aviso.
     */
    fun looksScanned(pages: List<String>): Boolean {
        if (pages.isEmpty()) return true
        val withText = pages.count { it.trim().length >= 20 }
        return withText.toDouble() / pages.size < 0.4
    }
}
```

Las marcas `## Página N` no son decorativas: el `Chunker` de `:backend` corta
por encabezados Markdown, así que **cada página del PDF se convierte en
fragmentos con encabezado "Página N"** → las citas quedan
`cátedra: Apuntes-IA · Página 7` y el docente puede ubicar el contenido en el
PDF original con un clic.

Las dos implementaciones (unas 30 líneas cada una):

```kotlin
// core/src/desktopMain/kotlin/kitoru/material/DesktopPdfBackend.kt
class DesktopPdfBackend : PdfBackend {
    override fun pageTexts(file: FilePath): List<String> {
        val f = java.io.File(file.value)
        if (!f.exists()) throw PdfException("El archivo original ya no está en el disco.")
        return try {
            // PDFBox 2.0.x: `PDDocument.load(File)`. En 3.x sería `Loader.loadPDF(f)`.
            // `setupMainMemoryOnly()` evita el archivo temporal (necesario en
            // Android, donde `java.io.tmpdir` puede no ser escribible).
            org.apache.pdfbox.pdmodel.PDDocument.load(
                f,
                org.apache.pdfbox.io.MemoryUsageSetting.setupMainMemoryOnly(),
            ).use { doc ->
                (0 until doc.numberOfPages).map { i ->
                    org.apache.pdfbox.text.PDFTextStripper().let { s ->
                        s.startPage = i + 1
                        s.endPage = i + 1
                        s.getText(doc)
                    }
                }
            }
        } catch (e: org.apache.pdfbox.exception.InvalidPasswordException) {
            throw PdfException("El PDF está protegido con contraseña. Desprotegelo y volvé a subirlo.", e)
        } catch (e: java.io.IOException) {
            throw PdfException("No se pudo leer el PDF: ${e.message ?: "archivo dañado"}.", e)
        }
    }
}
```

```kotlin
// core/src/androidMain/kotlin/kitoru/material/AndroidPdfBackend.kt
class AndroidPdfBackend : PdfBackend {
    override fun pageTexts(file: FilePath): List<String> {
        val f = java.io.File(file.value)
        if (!f.exists()) throw PdfException("El archivo original ya no está en el dispositivo.")
        return try {
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(
                f,
                com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMainMemoryOnly(),
            ).use { doc ->
                (0 until doc.numberOfPages).map { i ->
                    com.tom_roush.pdfbox.text.PDFTextStripper().let { s ->
                        s.startPage = i + 1
                        s.endPage = i + 1
                        s.getText(doc)
                    }
                }
            }
        } catch (e: com.tom_roush.pdfbox.exception.InvalidPasswordException) {
            throw PdfException("El PDF está protegido con contraseña. Desprotegelo y volvé a subirlo.", e)
        } catch (e: Exception) {
            throw PdfException("No se pudo leer el PDF: ${e.message ?: "archivo dañado"}.", e)
        }
    }
}
```

**PDFBox en Android: dos detalles obligatorios**

1. `PDFBoxResourceLoader.init(context)` hay que llamarlo **una vez**, al
   arrancar la app (`Application.onCreate` o `MainActivity.onCreate`), antes de
   usar PDFBox. Es lo que carga las fuentes y los recursos empaquetados.
2. PDFBox escribe archivos temporales (para PDFs cifrados) usando
   `java.io.tmpdir`, que en Android puede no ser escribible. Al arrancar:
   ```kotlin
   System.setProperty("java.io.tmpdir", File(context.cacheDir, "tmp").apply { mkdirs() }.absolutePath)
   ```
   y, para PDFs chicos, usar `MemoryUsageSetting.setupMainMemoryOnly()` (PDFBox
   2.x) para no tocar disco.

En `:core/build.gradle.kts`:

```kotlin
desktopMain.dependencies { implementation("org.apache.pdfbox:pdfbox:2.0.36") }
androidMain.dependencies { implementation("com.tom-roush:pdfbox-android:2.0.27.0") }
```

**Límite conocido de PDFBox**: no hace OCR. Un PDF escaneado devuelve páginas
vacías → `looksScanned` → `NEEDS_REVIEW` + aviso. Ver §4.6.

### 4.4 DOCX y PPTX: parseo manual de ZIP + XML

**Por qué no Apache POI.** POI (y poi-ooxml) no corre en Android: arrastra
XMLBeans, `commons-io`, `javax.imageio` y `java.awt`, y revienta con
`NoClassDefFoundError` apenas se toca un documento con imágenes. Además pesa
~15 MB y no es KMP-friendly. docx4j tiene el mismo problema. Un `.docx` y un
`.pptx` son, en el fondo, un **ZIP con XML adentro**: parsearlos a mano es
mucho más barato y totalmente portable.

**Estructura de un `.docx`:**

```
[Content_Types].xml
word/document.xml          <- el cuerpo del documento
word/styles.xml            <- nombres de los estilos ("heading 1", "Título 1")
word/_rels/document.xml.rels <- r:id -> URL (hipervínculos, imágenes)
word/numbering.xml
word/media/…
```

**Estructura de un `.pptx`:**

```
ppt/presentation.xml
ppt/slides/slide1.xml      <- una diapositiva
ppt/slides/_rels/slide1.xml.rels
ppt/notesSlides/notesSlide1.xml   <- notas del orador
ppt/media/…
```

#### 4.4.1 El lector de ZIP (`jvmCommonMain`)

`java.util.zip.ZipInputStream` existe **tanto en el JVM de escritorio como en
Android**, así que va en el source set compartido:

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/material/ZipReader.kt
package kitoru.material

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Lee un ZIP (docx, pptx, epub) en memoria.
 *
 * `java.util.zip` está en el JDK y en Android: por eso vive en jvmCommonMain
 * y el resto del pipeline es común.
 */
class ZipReader private constructor(private val entries: Map<String, ByteArray>) {

    /** ¿Existe la entrada? */
    operator fun contains(name: String): Boolean = entries.containsKey(name)

    /** Nombres de las entradas (en orden de lectura). */
    fun names(): List<String> = entries.keys.toList()

    /** Contenido de una entrada, o null si no está. */
    fun read(name: String): ByteArray? = entries[name]

    /** Contenido de una entrada como texto UTF-8. */
    fun readText(name: String): String? = entries[name]?.toString(Charsets.UTF_8)

    /** Nombres que matchean un patrón (ej. `ppt/slides/slide\d+\.xml`). */
    fun namesMatching(regex: Regex): List<String> =
        entries.keys.filter { regex.matches(it) }.sorted()

    companion object {
        /** Tope de tamaño descomprimido, para no reventar la memoria. */
        const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

        fun read(bytes: ByteArray): ZipReader {
            val out = LinkedHashMap<String, ByteArray>()
            var total = 0L
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val data = zis.readBytes()
                    total += data.size
                    if (total > MAX_TOTAL_BYTES) {
                        throw MaterialExtractionException(
                            "El archivo descomprimido es demasiado grande (más de 200 MB)."
                        )
                    }
                    out[entry.name] = data
                }
            }
            return ZipReader(out)
        }
    }
}
```

#### 4.4.2 El escáner de XML (commonMain, Kotlin puro)

En vez de `javax.xml.parsers.SAXParser` (que en Android es la implementación
de Harmony, con quirks conocidos y no testeable desde `commonTest`), se
escribe un escáner **de strings**, en Kotlin puro, sin dependencias:

```kotlin
// core/src/commonMain/kotlin/kitoru/material/office/XmlScanner.kt
package kitoru.material.office

sealed interface XmlEvent {
    data class Open(val name: String, val attrs: Map<String, String>) : XmlEvent
    data class Close(val name: String) : XmlEvent
    data class Text(val value: String) : XmlEvent
    data class CData(val value: String) : XmlEvent
}

/**
 * Escáner de XML mínimo, sólo para OOXML.
 *
 * No resuelve entidades externas, no valida DTD, no hace namespaces: es un
 * lector de *streaming* de tags y texto. Al no resolver nada externo, **no
 * hay XXE por construcción** (contra un SAX hay que deshabilitarlo a mano).
 *
 * Escribo en Kotlin puro (y no con SAX) por dos razones:
 *  1. se comporta **igual** en escritorio y en Android (Harmony tiene bugs
 *     conocidos con CDATA, `xml:space` y DOCTYPE);
 *  2. se testea en `commonTest` sin depender de una JVM.
 */
class XmlScanner(private val src: String) {
    fun events(): Sequence<XmlEvent> = sequence {
        var i = 0
        val n = src.length
        while (i < n) {
            val lt = src.indexOf('<', i)
            if (lt < 0) { emitText(src.substring(i)); break }
            if (lt > i) emitText(src.substring(i, lt))
            i = lt
            when {
                src.startsWith("<!--", i) -> {
                    val end = src.indexOf("-->", i)
                    i = if (end < 0) n else end + 3
                }
                src.startsWith("<![CDATA[", i) -> {
                    val end = src.indexOf("]]>", i)
                    val value = if (end < 0) src.substring(i + 9) else src.substring(i + 9, end)
                    yield(XmlEvent.CData(value))
                    i = if (end < 0) n else end + 3
                }
                src.startsWith("<?", i) -> {
                    val end = src.indexOf("?>", i)
                    i = if (end < 0) n else end + 2
                }
                src.startsWith("<!", i) -> {           // DOCTYPE y friends: se ignoran
                    val end = src.indexOf('>', i)
                    i = if (end < 0) n else end + 1
                }
                src.startsWith("</", i) -> {
                    val end = src.indexOf('>', i)
                    val name = src.substring(i + 2, if (end < 0) n else end).trim()
                    yield(XmlEvent.Close(name))
                    i = if (end < 0) n else end + 1
                }
                else -> {
                    val end = findTagEnd(i)
                    val body = src.substring(i + 1, end).trim()
                    val selfClosing = body.endsWith("/")
                    val clean = if (selfClosing) body.dropLast(1).trim() else body
                    val sp = clean.indexOfFirst { it.isWhitespace() }
                    val name = if (sp < 0) clean else clean.substring(0, sp)
                    val attrs = if (sp < 0) emptyMap() else parseAttrs(clean.substring(sp + 1))
                    yield(XmlEvent.Open(name, attrs))
                    i = end + 1
                }
            }
        }
    }
    // … emitText decodifica entidades, parseAttrs lee name="value" …
}
```

Decodificación de entidades: `&amp; &lt; &gt; &quot; &apos; &nbsp;` y numéricas
(`&#225;`, `&#xE1;`).

#### 4.4.3 Algoritmo DOCX

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/material/office/DocxExtractor.kt
object DocxExtractor {

    fun extract(bytes: ByteArray): ExtractionResult {
        val zip = ZipReader.read(bytes)
        val documentXml = zip.readText("word/document.xml")
            ?: throw MaterialExtractionException(
                "El archivo .docx está dañado o no es un Word válido (falta word/document.xml)."
            )
        val styleNames = readStyleNames(zip)          // styleId -> nombre legible
        val rels = readRelationships(zip)              // rId -> URL

        val out = StringBuilder()
        var para = StringBuilder()                     // buffer del párrafo
        var headingLevel = 0                           // 0 = no es encabezado
        var isListItem = false
        var inText = false
        var inFieldCode = false
        var hyperlinkId: String? = null

        fun flushParagraph() {
            val text = para.toString().trim()
            para = StringBuilder()
            if (text.isNotEmpty()) {
                out.append(if (headingLevel > 0) "#".repeat(headingLevel) + " " else "")
                out.append(if (isListItem) "- " else "")
                out.append(text).append("\n\n")
            }
            headingLevel = 0
            isListItem = false
        }

        for (ev in XmlScanner(documentXml).events()) {
            when (ev) {
                is XmlEvent.Open -> when (ev.name) {
                    "w:p" -> { flushParagraph() }        // safety: párrafos anidados
                    "w:pStyle" -> {
                        val styleId = ev.attrs["w:val"].orEmpty()
                        headingLevel = headingLevelOf(styleNames[styleId] ?: styleId)
                    }
                    "w:numPr" -> isListItem = true
                    "w:t" -> inText = true
                    "w:tab" -> para.append(' ')
                    "w:br", "w:cr" -> para.append('\n')
                    "w:noBreakHyphen" -> para.append('-')
                    "w:instrText" -> inFieldCode = true     // códigos de campo (TOC, REF)
                    "w:hyperlink" -> hyperlinkId = ev.attrs["r:id"]
                    "w:drawing", "w:pict" -> { }             // imágenes: se ignoran
                }
                is XmlEvent.Close -> when (ev.name) {
                    "w:p" -> flushParagraph()
                    "w:t" -> inText = false
                    "w:instrText" -> inFieldCode = false
                    "w:hyperlink" -> {
                        val url = hyperlinkId?.let { rels[it] }
                        if (!url.isNullOrBlank()) para.append(" ($url)")
                        hyperlinkId = null
                    }
                }
                is XmlEvent.Text, is XmlEvent.CData -> {
                    if (inText && !inFieldCode) para.append(ev.value)
                }
            }
        }
        flushParagraph()
        return ExtractionResult(text = out.toString().trim(), extractor = "docx-zip")
    }
}
```

Puntos clave del algoritmo:

| Tema | Decisión |
|---|---|
| **Buffer por párrafo** | El nivel de encabezado se decide al cerrar `</w:p>`: es lo único que funciona si el `w:pPr` viene raro. Por eso el texto se acumula en `para` y se vuelca al final. |
| **Encabezados → Markdown** | `word/styles.xml` se lee para mapear `styleId` → nombre real (`w:name w:val="heading 1"` / `"título 1"`). El `styleId` está localizado ("Ttulo1" en Word en español), así que **no se puede decidir por el id**: hay que mirar el nombre. `^(heading\|t.tulo)\s*([1-6])$` → `#`..`######`; `title` → `#`; `subtitle` → `##`. Esto es lo que hace que el `Chunker` de `:backend` corte por encabezado. |
| **Tablas** | `<w:tc>` → separador `" | "`, `<w:tr>` → salto de línea. La estructura de la tabla sobrevive como texto plano (no se reconstruye la tabla, no hace falta para el RAG). |
| **Listas** | `<w:numPr>` → prefijo `"- "`. |
| **Hipervínculos** | `w:hyperlink r:id` → se resuelve con `document.xml.rels` y se agrega `(url)`. |
| **Campos** (`w:instrText`) | Se ignoran: son códigos de Word (índice de contenido, referencias cruzadas), no contenido. |
| **Cambios trackeados** | `<w:ins>` se incluye, `<w:del>` se excluye (el texto borrado no está). |
| **Imágenes** | Se ignoran (quedaría `![imagen]` en el texto para que el docente vea que falta algo). |
| **Texto en cuadros de texto** | Los `w:txbxContent` están dentro del flujo del XML → se leen como texto normal. |
| **Encoding** | El XML declara `encoding="UTF-8"`; los textos van en UTF-8. Los caracteres de control se limpian. |
| **Documento vacío** | Si el texto sale vacío → `NEEDS_REVIEW` con "El documento no tiene texto (sólo imágenes)." |

#### 4.4.4 Algoritmo PPTX

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/material/office/PptxExtractor.kt
object PptxExtractor {

    private val SLIDE = Regex("ppt/slides/slide(\\d+)\\.xml")

    fun extract(bytes: ByteArray): ExtractionResult {
        val zip = ZipReader.read(bytes)
        if (!zip.contains("ppt/presentation.xml")) {
            throw MaterialExtractionException(
                "El archivo .pptx está dañado o no es un PowerPoint válido."
            )
        }
        // Orden por número de diapositiva, no por orden del ZIP.
        val slides = zip.namesMatching(SLIDE)
            .sortedBy { SLIDE.find(it)!!.groupValues[1].toInt() }

        val out = StringBuilder()
        for (slide in slides) {
            val number = SLIDE.find(slide)!!.groupValues[1].toInt()
            out.append("## Diapositiva ").append(number).append("\n\n")
            out.append(readSlideText(zip.readText(slide)!!))
            out.append("\n\n")
            // Notas del orador (si las hay)
            val notes = zip.readText("ppt/notesSlides/notesSlide$number.xml")
            if (!notes.isNullOrBlank()) {
                out.append("### Notas\n\n").append(readSlideText(notes)).append("\n\n")
            }
        }
        return ExtractionResult(text = out.toString().trim(), extractor = "pptx-zip")
    }

    /** Texto de una diapositiva: párrafos `<a:p>`, runs `<a:t>`. */
    private fun readSlideText(xml: String): String {
        val out = StringBuilder()
        var inText = false
        for (ev in XmlScanner(xml).events()) {
            when (ev) {
                is XmlEvent.Open -> when (ev.name) {
                    "a:t" -> inText = true
                    "a:br" -> out.append('\n')
                }
                is XmlEvent.Close -> when (ev.name) {
                    "a:t" -> inText = false
                    "a:p" -> out.append('\n')       // fin de párrafo
                    "p:sp" -> out.append("\n\n")   // fin de una forma: separación
                }
                is XmlEvent.Text, is XmlEvent.CData -> if (inText) out.append(ev.value)
            }
        }
        return out.toString().trim()
    }
}
```

Decisiones:

| Tema | Decisión |
|---|---|
| **Orden de diapositivas** | Por el número del archivo (`slide1`, `slide2`, `slide10`), **no** por el orden del ZIP. Ordenar por `Int` y no por `String` (si no, `slide10` va antes que `slide2`). |
| **Orden de lectura** | El escaneo lineal del XML respeta el orden del árbol → primero el texto de la forma de arriba. Suficiente para el RAG. |
| **Formas** | `</p:sp>` mete una línea en blanco → dos textos de dos cajas distintas no quedan pegados. |
| **Notas del orador** | Se incluyen bajo `### Notas`: a veces el material útil está ahí y no en la diapositiva. |
| **Tablas de PPTX** | `a:tbl` → `a:tr`/`a:tc`: se leen como texto (mismo criterio que en DOCX). |
| **Imágenes y gráficos** | Se ignoran. Es la limitación más visible de PPTX: una presentación exportada como imágenes no tiene texto. → `NEEDS_REVIEW` con el aviso de §7. |
| **SmartArt / gráficos** | Mismo caso: sin texto extraíble. |
| **`docProps`** | Se ignoran (metadatos del archivo, no contenido). |

### 4.5 Texto plano, Markdown, CSV, HTML

En `commonMain`, sin dependencias:

- **TXT/MD/RST/ADOC**: se lee tal cual (UTF-8, con fallback a Latin-1 si hay
  bytes inválidos). El Markdown se conserva: es lo que el `Chunker` ya sabe
  cortar por encabezados.
- **CSV**: se detecta el separador (`,` `;` `\t` `|`) por la primera línea
  **consistente**; se emite como tabla Markdown (`| a | b |`) para que el
  chunker no corte filas por la mitad.
- **HTML**: se quitan `script`/`style`, se convierten `<h1..h6>` en `#`..`######`,
  `<p>/<br>/<li>` en saltos de línea, y se des-escapan las entidades. Es
  heurístico y suficiente.
- **Encoding**: se prueba UTF-8 y, si falla, ISO-8859-1 (muchos `.txt` de la
  faculty vienen en Latin-1). Se avisa: "El archivo no estaba en UTF-8; se leyó
  como Latin-1."

### 4.6 PDF escaneado y OCR (hueco definido)

**Detección**: `PdfTextAssembler.looksScanned(pages)` (§4.3) + el caso
"páginas con menos de 20 caracteres".

**Qué pasa**: el material se sube igual (el docente quiere tenerlo), pero:

- `status = NEEDS_REVIEW`
- `warning = "Este PDF no tiene capa de texto (0 de 12 páginas tienen texto). Parece un escaneo: las imágenes no se pueden leer. Subilo desde el original con texto seleccionable, o pegá el texto a mano en «Editar texto»."`
- En la lista, el material aparece con un ícono de warning y el chip
  "Requiere revisión".
- **El material NO se indexa** (o se indexa vacío, que es lo mismo): `CourseMaterialSource`
  filtra por `extractedText.isNotBlank()` → no aporta fragmentos.
- En la pestaña "Probar pregunta", un material escaneado da siempre 0
  fragmentos → el diagnóstico dice: *"Este material no tiene texto extraído:
  el PDF es un escaneo. La IA no puede responder con él."* — **esa es la
  respuesta que el docente necesita**, y es exactamente el caso que la
  pantalla tiene que hacer visible.

**Hueco de OCR (diseñado, no implementado).** Se deja la costura:

```kotlin
// core/src/commonMain/kotlin/kitoru/material/OcrEngine.kt
/**
 * Motor de OCR opcional.
 *
 * Hoy no hay implementación: la interfaz queda para que el motor (ML Kit
 * Text Recognition en Android, Tesseract en escritorio, o el OCR del modelo
 * en Kitoru-Server) se enchufe sin tocar el pipeline.
 */
interface OcrEngine {
    /** ¿Puede procesar este formato? */
    fun supports(format: MaterialFormat): Boolean

    /** Texto reconocido. Lanza [OcrException] si falla. */
    suspend fun recognize(file: FilePath, format: MaterialFormat): String
}

/** Se usa cuando un PDF no tiene capa de texto. */
class OcrExtractor(private val ocr: OcrEngine) { /* … */ }
```

- **Android**: `com.google.mlkit:text-recognition` (on-device, gratis,
  español + inglés) → `TextRecognizer` sobre las imágenes de cada página.
  Se renderiza la página con… PDFBox-Android no renderiza; se usaría
  `android.graphics.pdf.PdfRenderer` (API 21+) parabitmap de cada página.
- **Escritorio**: Tesseract (`net.sourceforge.tess4j:tess4j`) o el OCR del
  modelo multimodal de Kitoru-Server.
- **Coste**: OCR de 200 páginas ≈ 30–90 s en un teléfono. Por eso el OCR es
  **opt-in y en segundo plano**, nunca parte del camino automático: el
  docente lo dispara explícitamente con un botón "Reconocer texto (OCR)" en
  el detalle de un material escaneado.
- El PDF original **se conserva**: el texto OCR se guarda aparte y el docente
  puede comparar/editarlo.

### 4.7 Indexación

Después de extraer, el material se indexa. La indexación usa lo que ya existe:

```kotlin
// backend/src/commonMain/kotlin/rag/CourseIndex.kt (nuevo)
package rag

/**
 * Índice vivo de una cátedra.
 *
 * Envuelve el [KnowledgeBase] y sabe reconstruirlo a partir de la lista de
 * materiales. Es lo que el docente invalida cada vez que toca un material.
 */
class CourseIndex(private val cfg: RagConfig) {
    val kb = KnowledgeBase(cfg)

    /** Reconstruye el índice completo. */
    suspend fun reindex(materials: List<Material>, onStatus: (String) -> Unit = {}) {
        kb.sources = listOf(CourseMaterialSource(materials))
        kb.sync(onStatus)
    }

    /** Sólo los materiales visibles (lo que ve el alumno). */
    suspend fun reindexVisible(materials: List<Material>, onStatus: (String) -> Unit = {}) =
        reindex(materials.filter { it.visible }, onStatus)
}
```

- `CourseMaterialSource` ya filtra por `visible` y por `extractedText.isNotBlank()`
  → **el material oculto y el material sin texto quedan fuera del corpus del
  alumno** sin tocar nada más. ✔
- La reindexación es **completa** (BM25 en memoria): para 100 documentos /
  800 fragmentos tarda menos de 200 ms. Se muestra el progreso con el
  `onStatus` que ya tiene `KnowledgeBase.sync`.
- Se reindexa en estos momentos: alta de material, edición manual del texto,
  cambiar visibilidad, borrar, y "Reindexar" explícito.

#### Dos cambios concretos en `:backend` que hacen falta

**(a) `Doc` y `Chunk` necesitan saber a qué material pertenecen.** Hoy
`Chunk.path` es el *título* del material, y `Chunk.label` es
`"cátedra: <título>"`. Con eso no se puede filtrar por material de forma
segura (dos materiales pueden tener el mismo título) ni mostrar "qué material
aportó este fragmento" en la prueba de pregunta.

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Models.kt
data class Doc(val source: String, val path: String, val text: String, val docId: String = "")
data class Chunk(
    val source: String, val path: String, val heading: String, val text: String,
    val docId: String = "",       // id del material ("" si no aplica)
) { val label get() = "$source: $path" }
```

`CourseMaterialSource` pasa `docId = material.id` y `Chunker.split` lo
propaga (`Chunk(doc.source, doc.path, heading, piece, doc.docId)`). Los
valores por defecto mantienen la compatibilidad con los tests existentes.

**(b) `KnowledgeBase` tiene que devolver el score.** `Bm25Index.search` ya
devuelve `List<Pair<Chunk, Double>>`, pero `KnowledgeBase.retrieve` tira la
puntuación. Sin el score, la pantalla "Probar pregunta" no puede explicar
**por qué** recuperó un fragmento ni ordenar por relevancia.

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/KnowledgeBase.kt
/** Fragmento recuperado con su puntaje BM25. */
data class ScoredChunk(val chunk: Chunk, val score: Double)

suspend fun sync(onStatus: (String) -> Unit = {}) { /* … */ }

/** Recupera los fragmentos más relevantes, con su puntaje. */
fun retrieveScored(query: String, k: Int = cfg.topK): List<ScoredChunk> =
    index.search(query, k).map { ScoredChunk(it.first, it.second) }

/** Recupera los fragmentos más relevantes (sin puntaje). */
fun retrieve(query: String, k: Int = cfg.topK): List<Chunk> =
    retrieveScored(query, k).map { it.chunk }

/** Cuántos fragmentos aporta cada material (por docId). */
fun chunkCountByDoc(): Map<String, Int> =
    index.chunksByDocId()          // nuevo en Bm25Index
```

`chunkCountForSource(source)` hoy devuelve `index.size` (el total) sin
importarle la fuente: es un bug silencioso. Se reemplaza por
`chunkCountByDoc()`, que es lo que la pestaña "Fragmentos" necesita.

### 4.8 El orquestador

```kotlin
// core/src/commonMain/kotlin/kitoru/material/TextExtractor.kt
package kitoru.material

/** Resultado de extraer el texto de un archivo. */
data class ExtractionResult(
    val text: String,
    /** Aviso para el docente (PDF escaneado, encoding raro, …). Vacío = ok. */
    val warning: String = "",
    /** Qué extractor se usó (para mostrarlo en la UI). */
    val extractor: String = "",
    /** Páginas del documento original. */
    val pageCount: Int = 0,
    /** Si el texto quedó truncado por un límite. */
    val truncated: Boolean = false,
)

/** Error de extracción con mensaje en español. */
class MaterialExtractionException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Extrae texto de un material.
 *
 * Es una interfaz (y no una clase concreta) para que cada plataforma plugue
 * su backend de PDF y las implementaciones dependan sólo de la interfaz.
 */
interface TextExtractor {
    /** ¿Este extractor maneja el formato? */
    fun supports(format: MaterialFormat): Boolean

    /** Extrae el texto del archivo (que ya está copiado al disco de la cátedra). */
    suspend fun extract(file: FilePath, format: MaterialFormat): ExtractionResult
}
```

Y el que despacha por formato, en `jvmCommonMain` (porque usa el `ZipReader`
y el `PdfBackend`):

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/material/JvmTextExtractor.kt
class JvmTextExtractor(
    private val pdf: PdfBackend,
    private val ocr: OcrEngine? = null,
) : TextExtractor {

    override fun supports(format: MaterialFormat) = format.supportsText

    override suspend fun extract(file: FilePath, format: MaterialFormat): ExtractionResult =
        when (format) {
            MaterialFormat.PDF -> extractPdf(file)
            MaterialFormat.DOCX -> DocxExtractor.extract(readAll(file))
            MaterialFormat.PPTX -> PptxExtractor.extract(readAll(file))
            MaterialFormat.MARKDOWN, MaterialFormat.TEXT -> plainText(file)
            MaterialFormat.CSV -> csvText(file)
            MaterialFormat.HTML -> htmlText(file)
            MaterialFormat.LINK -> ExtractionResult("", extractor = "link")
            MaterialFormat.UNKNOWN -> throw MaterialExtractionException(
                "No se sabe cómo leer este tipo de archivo."
            )
        }
}
```

El factory por plataforma:

```kotlin
// core/src/commonMain/kotlin/kitoru/material/MaterialExtractors.kt
/** Extractor de texto de la plataforma. */
expect fun platformTextExtractor(): TextExtractor
```

- `core/src/desktopMain/…/DesktopTextExtractor.kt` →
  `JvmTextExtractor(pdf = DesktopPdfBackend())`.
- `core/src/androidMain/…/AndroidTextExtractor.kt` →
  `JvmTextExtractor(pdf = AndroidPdfBackend())`.

### 4.9 El servicio de ingestión

```kotlin
// core/src/commonMain/kotlin/kitoru/material/MaterialIngestService.kt
class MaterialIngestService(
    private val fs: FileSystem,
    private val extractor: TextExtractor,
    private val materials: MaterialRepository,
) {
    /**
     * Sube un archivo: lo copia, lo extrae y lo guarda.
     *
     * Es transaccional a medias: si la extracción falla, el material se
     * guarda igual con status FAILED (el docente necesita ver el archivo
     * que falló, no un error invisible).
     */
    suspend fun ingest(
        courseId: String,
        source: PickedFile,
        title: String,
        kind: MaterialKind,
        tags: List<String>,
        visible: Boolean,
        uploadedBy: String,
        onStatus: (String) -> Unit = {},
    ): Material { /* … */ }
}
```

Pasos internos (nombres exactos de las operaciones):

1. `val id = newId()` → `"mat_" + UUID corto`.
2. `val dest = courseFilesDir(courseId).resolve("$id__${sanitize(source.name)}")`.
   - `sanitize` quita acentos, espacios y caracteres raros, deja
   `[A-Za-z0-9._-]`, corta a 80 caracteres y **preserva la extensión**
     (necesaria para el detector por extensión).
3. `picker.copyTo(source, dest, fs)` → bytes escritos. Si `> maxMaterialBytes`
   → se borra el archivo y se devuelve el error de cuota.
4. `val format = MaterialFormat.detect(dest, source.name, source.mimeType)`.
5. `extractor.extract(dest, format)` → `ExtractionResult`.
   - Si el texto supera `maxCharsPerMaterial` (2 000 000) → se trunca y
     `truncated = true` + warning.
6. Se arma el `Material` con `status` según el resultado:
   - texto no vacío y sin warning → `READY`
   - texto no vacío con warning → `READY` + `warning` (el warning no bloquea)
   - texto vacío → `NEEDS_REVIEW` + warning explicativo
   - excepción → `FAILED` + `statusDetail` = mensaje en español
7. `materials.save(material)`.
8. El llamador (ViewModel) dispara `courseIndex.reindex(...)`.

---

## 5. Previsualización: las tres pestañas

### 5.1 Pestaña "Texto" — qué salió del archivo

```
┌ Apuntes-IA.pdf ───────────────────────────────────────────────────────┐
│ PDF · 12 págs · 48.230 car. · extraído con pdfbox 2.0.27 · hace 2 min  │
│ ⚠ Aviso: el texto tiene 3 columnas y puede estar mezclado. Revisá.    │
│                                                                     │
│  [🔍 buscar en el texto…]              [Editar texto] [Copiar]      │
│ ┌─────────────────────────────────────────────────────────────────┐   │
│ │ ## Página 1                                                     │   │
│ │                                          # Modelo lineal         │   │
│ │ Introducción a los modelos lineales…                            │   │
│ │ ## Página 2                                                     │   │
│ │ …                                                               │   │
│ └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

- **Lectura**: `LazyColumn` con el texto en `monospace`, con los encabezados
  `##` resaltados.
- **Búsqueda**: `TextField` + resaltado de coincidencias (el texto se
  procesa en chunks visibles, no se pinta un `Text` gigante).
- **Editar texto** (el fix manual del PDF mal parseado):
  - Conmutable a un `BasicTextField` con todo el texto.
  - Botones: **Guardar** / **Descartar** / **Restaurar extracción original**.
  - Al guardar: `material.extractedText = texto`, `editedManually = true`,
    `extractor = "manual"`, `status = READY`, se reindexa.
  - "Restaurar extracción original" vuelve a correr el extractor sobre el
    archivo y **descarta** la edición manual (con confirmación).
  - Si el texto es largo (> 100 k caracteres), el editor muestra un aviso:
    "El texto es largo; considerá editar sólo la parte que está mal. Guardo
    todo el archivo igual." (La edición es de texto plano completo, no un
    editor por rangos: es la primera versión.)

### 5.2 Pestaña "Fragmentos" — cómo quedó troceado

Lo que **sale del índice** es lo que la IA va a ver. Esta pestaña lo muestra.

```
┌ Fragmentos (14) ─────────────────────────────────────────────────────┐
│ chunkSize 1200 · overlap 150 · reindexado hace 2 min   [Reindexar]  │
│ ⚠ El texto cambió después del último reindexado.                     │
│                                                                     │
│ #1  ## Página 1        1.180 car.   [★ marcar como esperado]       │
│     Introducción a los modelos lineales…                            │
│ #2  ## Página 2        1.190 car.                                    │
│     …                                                               │
│ #3  ## Modelos lineales  940 car.                                    │
└─────────────────────────────────────────────────────────────────────┘
```

- Los fragmentos se calculan **al vuelo** con el `Chunker` sobre el texto
  actual del material y el `chunkSize` de la cátedra:
  ```kotlin
  val chunks = Chunker.split(
      Doc("cátedra", material.title, material.extractedText, material.id),
      course.chunkSize, 150,
  )
  ```
  → así la pestaña refleja el texto actual, incluso si el índice está viejo.
- Si el texto cambió desde el último reindexado, se muestra el banner
  "El índice está desactualizado" con un botón **Reindexar** (que dispara
  `courseIndex.reindex`).
- Cada fragmento muestra: número, encabezado, cantidad de caracteres y una
  vista previa.
- **★ Marcar como esperado**: el docente marca el fragmento que *debería*
  responder a su pregunta de prueba. Es el "gold" contra el que se mide la
  recuperación en la pestaña siguiente. Se guarda en el material:
  ```kotlin
  val expectedChunkId: String = ""   // hash del texto del fragmento esperado
  ```
  (el id de un fragmento no es estable entre reindexados, así que se guarda
  un **hash del texto** — `chunk.text.hashCode().toString()` — y se busca por
  coincidencia exacta o por prefijo de 200 caracteres).

### 5.3 Pestaña "Probar pregunta" — la herramienta clave

Esta es la respuesta directa a *"si la IA no encuentra lo que busco, el
problema es el material, no el modelo"*. El docente escribe una pregunta
**como la escribiría un alumno** y ve exactamente qué recupera el índice.

```
┌ Probar pregunta ─────────────────────────────────────────────────────┐
│  Pregunta: [ ¿Qué es un perceptrón?                        ] [Probar]│
│  ◉ Solo este material    ○ Toda la cátedra                            │
│                                                                     │
│  Sugerencias: [¿Qué es un perceptrón?] [¿Cómo se entrena una red?]    │
├─────────────────────────────────────────────────────────────────────┤
│  ✅ El material contiene la respuesta                                │
│     El fragmento marcado como esperado fue el #1 (puntaje 12.43).      │
├─────────────────────────────────────────────────────────────────────┤
│  #1   12.43   cátedra · Apuntes-IA · Página 7      ★ esperado         │
│       «…el perceptrón es la unidad básica de una red neuronal…»       │
│  #2    3.10   cátedra · Apuntes-IA · Página 3                         │
│       «…las redes neuronales se componen de capas de perceptrones…»   │
├─────────────────────────────────────────────────────────────────────┤
│  [Probar de extremo a extremo con el modelo]                          │
└─────────────────────────────────────────────────────────────────────┘
```

**Los tres modos y qué contestan:**

| Modo | Qué hace | Pregunta que responde |
|---|---|---|
| **Solo este material** | Indexa **únicamente** este material (un `Bm25Index` efímero con sus fragmentos) y recupera contra él. | "¿Este material, aisladamente, sabe la respuesta?" |
| **Toda la cátedra** | Usa el `KnowledgeBase` vivo de la cátedra. | "¿La respuesta está en el material, pero el topK o los otros materiales la desplazan?" |
| **Extremo a extremo** | `RagEngine.prepare(pregunta, vacío, useDocs = true)` + `llm.chatText(...)` + `engine.verify(respuesta, fuentes)`. Muestra la respuesta, las fuentes usadas y el informe de citas. | "¿La IA responde bien con este material?" |

**El diagnóstico automático** (lo que la pantalla dice con una frase): con el
fragmento marcado como esperado (o, si no hay ninguno, con el mejor
fragmento recuperado) se evalúa el resultado:

| Situación | Mensaje en pantalla |
|---|---|
| El fragmento esperado está en el top-K | "✅ El material contiene la respuesta. Si la IA no la encuentra, el problema es el prompt, el topK o el modelo, no el material." |
| El fragmento esperado **no** está en el top-K, pero el texto sí contiene los términos | "⚠ El material contiene la respuesta, pero el troceado la dejó en un fragmento que no se recuperó. Probá bajar el chunkSize o subí el topK." |
| El fragmento esperado no está y el texto **no** contiene los términos de la pregunta | "❌ El material no contiene esa información. Agregá el texto que falta (o subí el documento que la tiene)." |
| El material no tiene texto extraído | "❌ Este material no tiene texto (PDF escaneado o formato sin soporte). No se puede indexar." |
| La pregunta no recuperó nada | "❌ Ningún fragmento coincide con la pregunta. Probá con otras palabras: el índice es léxico (BM25), no entiende sinónimos." |

La última fila es importante y hay que mostrarla tal cual: **el índice es
BM25, no向量ial**. Si el docente busca "auto" y el PDF dice "automóvil", no
va a encontrar nada. La pantalla tiene que enseñar eso, porque si no, el
docente le echa la culpa al modelo.

**Detalle de implementación del diagnóstico por términos**:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/teacher/materials/ProbeDiagnostics.kt
object ProbeDiagnostics {

    /** Términos de la pregunta (mismo tokenizador que el índice). */
    fun queryTerms(question: String): List<String> =
        rag.retrieval.Bm25Index.tokenize(question).distinct()

    /** Términos de la pregunta que NO aparecen en el texto del material. */
    fun missingTerms(question: String, text: String): List<String> {
        val inText = Bm25Index.tokenize(text).toSet()
        return queryTerms(question).filterNot { inText.contains(it) }
    }

    /** Veredicto de la prueba. */
    fun verdict(
        mode: ProbeMode,
        expectedText: String?,
        retrieved: List<ScoredChunk>,
        materialText: String,
    ): ProbeVerdict { /* … */ }
}

sealed interface ProbeVerdict {
    data class Ok(val rank: Int, val score: Double) : ProbeVerdict
    data class NotChunked(val missingTerms: List<String>) : ProbeVerdict
    data class NotInMaterial(val missingTerms: List<String>) : ProbeVerdict
    data object NoText : ProbeVerdict
    data object NothingRetrieved : ProbeVerdict
}
```

- "Solo este material" se implementa con un `Bm25Index` efímero:
  ```kotlin
  val chunks = Chunker.split(Doc("cátedra", material.title, material.extractedText, material.id),
                              course.chunkSize, 150)
  val probeIndex = Bm25Index(chunks)
  val hits = probeIndex.search(question, course.topK).map { ScoredChunk(it.first, it.second) }
  ```
  → sin tocar el índice vivo de la cátedra.
- "Toda la cátedra" usa `courseIndex.kb.retrieveScored(question, course.topK)`.
  Con `docId` en los fragmentos, la pantalla puede mostrar *de qué material*
  vino cada fragmento y si el material esperado quedó fuera por el `topK`.
- "Extremo a extremo" usa el `RagEngine` y el `LlmGateway` que ya existen. En
  Android, si la cátedra usa un modelo local, el `createLocalModelService()`
  lanza `LlmException` → la pantalla avisa: "El modelo de la cátedra es local y
  sólo está disponible en la app de escritorio. Podés probar la recuperación,
  pero no la respuesta."

### 5.4 Pestaña "Actividad" (opcional, segunda iteración)

Qué material se consulta más (usa `CourseAnalytics.topSources`, que ya existe):
"Este material aparece en 23 citas de los alumnos." Es la retroalimentación
de qué material sirve. Se puede dejar para la iteración 2.

---

## 6. Acciones por material

### 6.1 Menú contextual (click derecho en escritorio, pulsación larga en Android)

Mismo patrón que `FunshiEngineAI/frontend/.../ChatSidebar.kt`:

```kotlin
// En MaterialListPane, sobre el fondo:
.onPointerEvent(PointerEventType.Press) { e ->
    if (e.button != PointerButton.Secondary) return@onPointerEvent
    openMenu(null, e.changes.first().position)
}
// En cada MaterialRow:
.onPointerEvent(PointerEventType.Press) { e ->
    if (e.button == PointerButton.Secondary) {
        e.consume()
        openMenu(material.id, e.changes.first().position)
    }
}
```

Y el `DropdownMenu` con las acciones (mismas acciones en el botón "⋮" de la
cabecera del detalle, para que funcione igual en Android):

| Acción | Qué hace | Detalle |
|---|---|---|
| **Renombrar** | Cambia `Material.title` | Un `AlertDialog` con un `OutlinedTextField`. **No** renombra el archivo en disco: `path` sigue apuntando al original, así el docente lo reconoce en su carpeta y las citas viejas no se rompen. El título es la etiqueta de cita (`cátedra: <título>`). |
| **Editar texto** | Abre la pestaña "Texto" en modo edición | Es el fix manual del PDF mal parseado. |
| **Cambiar etiquetas** | Edita `tags` | `TagEditor`: chips con `x`, campo para agregar, y un vocabulario sugerido por la cátedra ("Parcial 1", "Parcial 2", "Teoría", "Práctica", "Bibliografía", "Trabajo final"). Las etiquetas **no** afectan al RAG en esta versión (BM25 no filtra por etiqueta): sirven para organizar. Dejarlo escrito en la UI ("Las etiquetas organizan tu material; no cambian lo que recupera la IA"). |
| **Ocultar a los alumnos** | `visible = false` (y viceversa) | `CourseMaterialSource` ya filtra por `visible` → el material sale del corpus del alumno al reindexar. En la UI: chip "Oculto para los alumnos" y el material aparece atenuado en la lista. El docente sigue viéndolo y probándolo. |
| **Reindexar** | `courseIndex.reindex(materials)` | Re-trocea y re-indexa **todo** (el índice es completo). Botón "Reindexar" también global, arriba a la derecha. |
| **Volver a extraer** | Vuelve a correr el extractor sobre el archivo original | Para cuando se actualizó la librería de extracción, o cuando el docente corrigió el archivo original por fuera y lo volvió a subir con el mismo nombre. Pregunta confirmación: "Se va a reemplazar el texto extraído. ¿Continuar?" (si el texto fue editado a mano, avisa que se pierde la edición). |
| **Duplicar** | Crea una copia con otro título | Copia el archivo y el texto. Útil para "mismo material, versión 2025 / 2026". |
| **Abrir el original** | Abre el archivo con la app del sistema | Escritorio: `java.awt.Desktop.getDesktop().open(file)`. Android: `Intent.ACTION_VIEW` con el URI + `FLAG_GRANT_READ_URI_PERMISSION`. |
| **Eliminar** | Borra el material | Ver §6.2. |

### 6.2 Eliminar

Diálogo de confirmación:

```
┌ Eliminar material ───────────────────────────────────────┐
│  ¿Eliminar «Apuntes-IA.pdf»?                              │
│                                                            │
│  Se borra el archivo, el texto extraído y sus 14          │
│  fragmentos del índice.                                    │
│                                                            │
│  ⚠ 23 citas de conversaciones de alumnos van a quedar     │
│     apuntando a un material que ya no existe (se van a     │
│     contar como "fuente desconocida").                     │
│                                                            │
│  [Cancelar]                          [Eliminar]            │
└────────────────────────────────────────────────────────────┘
```

- Se borran: el registro en `materials.json`, el archivo en
  `courses/<courseId>/files/`, y el texto.
- Las citas viejas (`ChatMessage.sources` = `"cátedra: <título>"`) quedan
  colgadas. El `CitationVerifier` ya tiene la categoría "fuente inexistente"
  (`CitationStats.unknown`) → es consistente con el modelo de datos.
- Después se reindexa.

### 6.3 Reindexar todo (global)

Botón "⟳" arriba a la derecha de la pantalla, con la línea de estado:

```
Índice: 12 documentos · 87 fragmentos · reindexado hace 2 min
```

Al reindexar: `Indexando 12 documentos…` → `Índice listo: 12 docs / 87 fragmentos`.
Si algún material falla: `Índice listo: 11 docs / 82 fragmentos · ⚠ Apuntes-2.pdf: sin texto`.

---

## 7. Cuotas y errores (mensajes en español)

### 7.1 Cuotas

Se agregan a `Course` (con defaults → compatible con lo ya guardado):

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Course.kt
/** Máximo por archivo (50 MB). */
val maxMaterialBytes: Long = 52_428_800L,
/** Máximo de material por cátedra (500 MB). */
val maxCourseBytes: Long = 524_288_000L,
/** Máximo de materiales por cátedra. */
val maxMaterialCount: Int = 200,
/** Máximo de caracteres extraídos por material (2 M). */
val maxMaterialChars: Int = 2_000_000,
```

Cálculo del uso: `materials.sumOf { it.sizeBytes }` (el tamaño del archivo
original, que es lo que ocupa disco).

La UI muestra el uso en la pantalla de materiales, abajo a la izquierda:

```
Material: 312 MB de 500 MB · 28 de 200 archivos
```

y una barra que se pone ámbar al pasar el 80 % y roja al pasar el 100 %.

### 7.2 Mensajes de error

Todos en español, sin jerga, con la acción a seguir. Esta es la tabla que
define los textos exactos de la UI:

| Situación | Mensaje | Acción |
|---|---|---|
| Formato no soportado | "«diagrama.png» no es un formato que se pueda indexar. Subí un PDF, Word, PowerPoint, Markdown, texto o CSV." | Descartar el archivo |
| Extensión ≠ contenido | "El archivo «notas.pdf» dice ser PDF pero su contenido no lo es. Revisá el archivo." | Descartar o renombrar |
| Archivo vacío | "El archivo está vacío (0 bytes)." | Descartar |
| PDF protegido | "El PDF está protegido con contraseña. Desprotegelo y volvé a subirlo." | — |
| PDF corrupto | "No se pudo leer el PDF: archivo dañado. Probá abrirlo de nuevo y guardarlo otra vez." | — |
| **PDF escaneado** | "Este PDF no tiene capa de texto (0 de 12 páginas tienen texto). Parece un escaneo: las imágenes no se pueden leer. Subilo desde el original con texto seleccionable, o pegá el texto a mano en «Editar texto»." | Editar texto / OCR (futuro) |
| PDF con poco texto | "El PDF tiene muy poco texto (34 caracteres en 12 páginas). Revisá que no sea un escaneo." | Editar texto |
| DOCX inválido | "El archivo .docx está dañado o no es un Word válido." | Descartar |
| PPTX inválido | "El archivo .pptx está dañado o no es un PowerPoint válido." | Descartar |
| DOCX/PPTX sin texto | "El documento no tiene texto (sólo imágenes). Revisá el archivo o pegá el texto a mano." | Editar texto |
| Extensión con encoding raro | "El archivo no estaba en UTF-8; se leyó como Latin-1. Revisá que no haya caracteres raros." | Revisar texto |
| Texto truncado | "El texto extraído tiene 3.200.000 caracteres; se indexaron los primeros 2.000.000. Revisá el texto en «Editar texto»." | Editar texto |
| Archivo > cuota por archivo | "El archivo pesa 84 MB y el máximo por archivo es 50 MB." | — |
| Cátedra > cuota de tamaño | "La cátedra ya tiene 512 MB de material y el máximo es 500 MB. Eliminá materiales viejos o subí archivos más chicos." | — |
| Cátedra > cuota de cantidad | "La cátedra ya tiene 200 materiales (el máximo). Eliminá los que no uses." | — |
| Permiso Android perdido | "Kitoru ya no puede leer el archivo. Volvé a elegirlo desde el selector." | Reelegir |
| Archivo original borrado | "El archivo original ya no está en el disco. Volvé a subirlo para poder reextraerlo." | Reindexar |
| Error de escritura | "No se pudo guardar el material: no hay espacio en disco. Liberá espacio y volvé a intentar." | — |
| Fallo al reindexar | "No se pudo reindexar la cátedra: <motivo>. El índice quedó como estaba." | Reintentar |
| Extensión de archivo no soportada en el escritorio | "Ese archivo no tiene un formato que Kitoru sepa leer. Se admiten: PDF, Word (.docx), PowerPoint (.pptx), Markdown (.md), texto (.txt) y CSV." | — |

Los errores de extracción se muestran **en el material** (chip "Error" +
`statusDetail`), no como un snackbar que se pierde: el docente tiene que ver
que el archivo falló.

---

## 8. Nombres de composables y archivos

### 8.1 `:ui` — `src/commonMain/kotlin/kitoru/ui/teacher/materials/`

| Archivo | Contenido | Composables / tipos |
|---|---|---|
| `MaterialsScreen.kt` | Pantalla, decide 1 o 2 paneles | `MaterialsScreen`, `TwoPaneLayout`, `SinglePaneLayout` |
| `MaterialListPane.kt` | Panel izquierdo | `MaterialListPane` |
| `MaterialRow.kt` | Fila de la lista | `MaterialRow` |
| `MaterialListFilters.kt` | Buscador + filtros | `MaterialSearchField`, `MaterialFilterChips` |
| `MaterialDetailPane.kt` | Panel derecho | `MaterialDetailPane` |
| `MaterialHeader.kt` | Cabecera del detalle | `MaterialHeader` |
| `MaterialStatusBadge.kt` | Chip de estado | `MaterialStatusBadge` |
| `MaterialKindBadge.kt` | Chip de tipo | `MaterialKindBadge` |
| `MaterialTextTab.kt` | Pestaña "Texto" | `MaterialTextTab`, `MaterialTextViewer`, `MaterialTextEditor` |
| `MaterialChunksTab.kt` | Pestaña "Fragmentos" | `MaterialChunksTab`, `MaterialChunkCard` |
| `MaterialProbeTab.kt` | Pestaña "Probar pregunta" | `MaterialProbeTab`, `MaterialProbeResult`, `ProbeVerdictBanner` |
| `MaterialActions.kt` | Menú de acciones | `MaterialActionsMenu`, `MaterialAction` (enum) |
| `MaterialDropTarget.kt` | `expect` del drop | `MaterialDropTarget` (expect) |
| `MaterialUploadDialog.kt` | Diálogo de subida | `MaterialUploadDialog`, `MaterialUploadRow` |
| `TagEditor.kt` | Editor de etiquetas | `TagEditor` |
| `RenameMaterialDialog.kt` | Diálogo de renombrado | `RenameMaterialDialog` |
| `DeleteMaterialDialog.kt` | Diálogo de borrado | `DeleteMaterialDialog` |
| `MaterialViewModel.kt` | Estado y acciones | `MaterialViewModel` |
| `MaterialUiState.kt` | Estado de la pantalla | `MaterialUiState`, `MaterialUiEvent`, `ProbeUiState`, `ProbeMode` |
| `ProbeDiagnostics.kt` | Diagnóstico de la prueba | `ProbeDiagnostics`, `ProbeVerdict` |
| `FilePickerFactory.kt` | `expect` del selector | `rememberFilePicker` (expect) |

### 8.2 `:ui` — específico de plataforma

| Archivo | Contenido |
|---|---|
| `src/desktopMain/…/DesktopFilePicker.kt` | `DesktopFilePicker` (AWT `FileDialog`) |
| `src/desktopMain/…/DesktopMaterialDropTarget.kt` | `actual MaterialDropTarget` con `dragAndDropTarget` + `DataFlavor.javaFileListFlavor` |
| `src/desktopMain/…/OpenWithSystem.kt` | `openWithSystem(file: FilePath)` (`java.awt.Desktop`) |
| `src/androidMain/…/AndroidFilePicker.kt` | `AndroidFilePicker` (SAF: `OpenDocument` / `GetContent`) |
| `src/androidMain/…/AndroidMaterialDropTarget.kt` | `actual MaterialDropTarget` (no-op) |
| `src/androidMain/…/ShareIntentHandler.kt` | `Intent.sharedUris()`, `KitoruAppState.incomingFiles` |
| `src/androidMain/…/AndroidPdfInit.kt` | `PDFBoxResourceLoader.init(context)` + `java.io.tmpdir` (o va en `:android` app) |

### 8.3 `:core` — `src/commonMain/kotlin/kitoru/`

| Archivo | Contenido |
|---|---|
| `platform/FilePicker.kt` | `FilePicker`, `FilePickRequest`, `PickedFile` |
| `platform/FileSystem.kt` | **agregar** `readBytes(path)` y `writeBytes(path, bytes, append = false)` |
| `domain/Course.kt` | **agregar** `MaterialStatus` y los campos nuevos de `Material`; **agregar** las cuotas a `Course` |
| `material/MaterialFormat.kt` | `MaterialFormat` + detección |
| `material/TextExtractor.kt` | `TextExtractor`, `ExtractionResult`, `MaterialExtractionException` |
| `material/PdfBackend.kt` | `PdfBackend`, `PdfException` |
| `material/PdfTextAssembler.kt` | `assemble`, `looksScanned` (algoritmo común de PDF) |
| `material/OcrEngine.kt` | `OcrEngine` (hueco definido) |
| `material/MaterialIngestService.kt` | `MaterialIngestService` |
| `material/PlainText.kt` | TXT/MD/CSV/HTML → texto (Kotlin puro) |
| `material/office/XmlScanner.kt` | `XmlScanner`, `XmlEvent` (Kotlin puro, `commonMain`) |

### 8.4 `:core` — `src/jvmCommonMain/kotlin/kitoru/material/`

| Archivo | Contenido |
|---|---|
| `ZipReader.kt` | Lectura de ZIP (`java.util.zip`) |
| `JvmTextExtractor.kt` | Despacho por formato |
| `office/DocxExtractor.kt` | Algoritmo DOCX |
| `office/PptxExtractor.kt` | Algoritmo PPTX |

### 8.5 `:core` — específico de plataforma

| Archivo | Contenido |
|---|---|
| `src/desktopMain/…/DesktopPdfBackend.kt` | `org.apache.pdfbox` |
| `src/desktopMain/…/DesktopTextExtractor.kt` | `actual fun platformTextExtractor()` |
| `src/androidMain/…/AndroidPdfBackend.kt` | `com.tom_roush.pdfbox` |
| `src/androidMain/…/AndroidTextExtractor.kt` | `actual fun platformTextExtractor()` |

### 8.6 `:backend` — cambios

| Archivo | Cambio |
|---|---|
| `rag/retrieval/Models.kt` | `docId` en `Doc` y `Chunk` |
| `rag/retrieval/Chunker.kt` | Propagar `docId` al `Chunk` |
| `rag/retrieval/DocumentSource.kt` | `CourseMaterialSource` pasa `docId = material.id` |
| `rag/retrieval/KnowledgeBase.kt` | `ScoredChunk`, `retrieveScored`, `chunkCountByDoc()`; arreglar `chunkCountForSource` |
| `rag/retrieval/Bm25Index.kt` | `chunksByDocId()` |
| `rag/CourseIndex.kt` | **nuevo**: `CourseIndex.reindex` |

### 8.7 Tests

| Archivo | Qué cubre |
|---|---|
| `core/src/commonTest/…/office/XmlScannerTest.kt` | Tags, atributos, CDATA, comentarios, entidades, auto-cierre |
| `core/src/commonTest/…/PlainTextTest.kt` | MD/TXT/CSV/HTML, detección de separador, encoding |
| `core/src/commonTest/…/MaterialFormatTest.kt` | Detección por extensión y por firma binaria |
| `core/src/jvmCommonTest/…/DocxExtractorTest.kt` | Genera un `.docx` mínimo en el test (ZIP con `document.xml`) y verifica párrafos, encabezados, tablas, listas |
| `core/src/jvmCommonTest/…/PptxExtractorTest.kt` | Genera un `.pptx` mínimo y verifica el orden de las diapositivas y las notas |
| `core/src/desktopTest/…/PdfExtractionTest.kt` | Genera un PDF con PDFBox y verifica el texto, las marcas de página y la detección de escaneado |
| `backend/src/desktopTest/…/ProbeTest.kt` | Recuperación con score, `docId`, `chunkCountByDoc` |

---

## 9. Checklist de implementación (orden sugerido)

1. `MaterialStatus` + campos nuevos de `Material` + cuotas de `Course`.
2. `FileSystem.readBytes/writeBytes`.
3. `MaterialFormat` + detección.
4. `XmlScanner` (+ test) → `ZipReader` → `DocxExtractor`/`PptxExtractor` (+ test).
5. `PdfBackend` (desktop + android) + `PdfTextAssembler` + `PdfException`.
6. `TextExtractor` + `JvmTextExtractor` + `MaterialIngestService`.
7. `FilePicker` (interfaz) + `DesktopFilePicker` (AWT) + `AndroidFilePicker` (SAF) + `rememberFilePicker` (expect/actual).
8. Cambios en `:backend` (`docId`, `retrieveScored`, `chunkCountByDoc`, `CourseIndex`).
9. `MaterialViewModel` + `MaterialsScreen` con lista y detalle (2 paneles y 1 panel).
10. Pestañas "Texto" y "Fragmentos".
11. Pestaña "Probar pregunta" + `ProbeDiagnostics`.
12. `MaterialDropTarget` (desktop) + `ACTION_SEND` (Android).
13. Acciones por material (menú contextual, diálogos).
14. Cuotas, mensajes de error y estados vacíos.

---

## 10. Decisiones que quedan fuera de alcance (anotadas)

- **Embeddings / búsqueda semántica**: hoy el índice es BM25 léxico. La
  pantalla "Probar pregunta" lo asume y lo explica al docente. Cuando haya
  embeddings, esta misma pestaña pasa a ser el banco de pruebas del embedding.
- **Indexación incremental**: hoy cada cambio reindexa todo. Con BM25 en
  memoria es instantáneo; si aparece un corpus grande, se hace incremental
  (re-trocear sólo el material cambiado y fusionar el índice).
- **Edición colaborativa del texto**: hoy el texto editado por el docente es
  local. Con Kitoru-Server, el texto debería dejar de vivir en `materials.json`
  y pasar a un servicio (ver §4.1, nota de tamaño).
- **OCR**: la costura está definida (`OcrEngine`), la implementación no.
- **Miniaturas / previsualización visual del PDF**: sólo texto. La previsualización
  visual del original se puede abrir con la app del sistema (§6.1).
