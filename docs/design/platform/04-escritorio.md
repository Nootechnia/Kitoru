# 04 — Integración con el sistema operativo (escritorio)

> Subagente: diseño de la integración de **Kitoru con el sistema operativo en
> escritorio** (Compose Multiplatform Desktop).
> Módulos tocados: `:desktop` (entry point, empaquetado), `:ui` (menú, atajos,
> seams de plataforma), `:core` (plataforma: rutas, SO, portable).
> Reglas del proyecto que se respetan: *reutilizar no reescribir*, *portable por
> defecto*, *fail-fast con mensajes en español*, *el estudiante conserva agencia*,
> *el docente no ve lo que no debe*.
> Referencias: `docs/design/CONTEXTO.md`, `docs/design/process/01-packaging-ci.md`
> (empaquetado, `AppConfig`, `schemaVersion`), `docs/design/student/02-chat.md`
> §5 (seam `FileActions`), `docs/design/teacher/01-navegacion.md` §6–§7
> (breakpoints y atajos), `docs/design/teacher/07-chats.md` §2 (panel de tres
> columnas), `docs/design/security/01-claves.md` (`SecureStore`),
> `docs/design/security/03-threat-model.md` (`CredentialStore`).
> Código de referencia de los repos hermanos: `FunshiEngineAI/frontend/src/main/kotlin/app/Main.kt`
> (la ventana), `app/NavigationRail.kt` (el rail), `Silvy/README.md`
> (la "casa" de Silvy: `--home` > `SILVY_HOME` > junto al binario).

---

## 0. Resumen ejecutivo (las decisiones, en una línea cada una)

| # | Decisión | Alternativa descartada | Por qué |
|---|---|---|---|
| **D1** | Ventana de **1280 × 820 dp** al abrir, **mínimo 1024 × 680 dp**, y tamaño **limitado al 90 % de la pantalla** | Tamaño fijo 950×720 (el de FunshiEngineAI) | El panel de tres columnas del explorador de chats necesita **1100 dp de área de contenido**; 950 dp lo deja ilegible |
| **D2** | El rail lateral **se colapsa solo** por debajo de 1340 dp de ventana | Dejarlo siempre expandido (240 dp) | 240 + 280 + 320 = 840 dp: a 1024 dp de ventana el visor del chat queda con 184 dp |
| **D3** | **`onCloseRequest` no cierra nunca directo**: pide confirmación si hay trabajo sin guardar, y **guarda antes de salir** (borrador, geometría, respuesta parcial) | `onCloseRequest = { exitApplication() }` | Perder el borrador y la geometría en cada cierre es la marca de una app que no se siente terminada |
| **D4** | **No hay menú nativo.** Menú dibujado por Compose (`Row` de `Text` + `DropdownMenu`) **+ atajos de teclado como fuente de verdad** | `FrameWindowScope.MenuBar` (Swing `JMenuBar`) | El `MenuBar` de Compose Desktop es Swing: no sigue el tema, no porta a Android, duplica el manejo de atajos y come 32 dp de alto |
| **D5** | Los atajos se despachan con `Modifier.onPreviewKeyEvent` en la raíz, con un helper propio (`AppShortcut` + `ShortcutRegistry`) | `KeyShortcut` de AWT (aceleradores del `JMenuBar`) | AWT y Compose se pisarían; además el `MenuBar` de Swing no existe en Android |
| **D6** | Arrastrar y soltar: seam `expect @Composable fun Modifier.dragAndDropFiles(onFiles)` en `commonMain`, implementación con `Modifier.dragAndDropTarget` en `desktopMain` | `onExternalDrag` (la API vieja) | En Compose 1.7.3 `onExternalDrag` está **deprecada con nivel ERROR**; la API buena es `dragAndDropTarget` |
| **D7** | Las asociaciones de archivo se declaran en el **DSL del plugin** (`nativeDistributions { fileAssociation(...) }`), que ya traduce a `--file-associations` de jpackage y a `CFBundleDocumentTypes` en macOS | Editar el `.msi`/`.dmg` a mano | El plugin 1.7.3 lo hace solo; el `.kitoru` propio se registra con un MIME type propio |
| **D8** | El archivo que se abre llega por **argv** y, en macOS, por `Desktop.setOpenFileHandler`; ambos encolan en un **buzón** que la UI drena | Leer `args` y ya | En macOS el doble clic no pasa por `argv`; y una segunda instancia no debe tocar los mismos datos |
| **D9** | Las integraciones de SO van por el seam **`FileActions` extendido** (`open`, `revealInFolder`, `openFolder`, `copyPath`, `openUrl`) sobre `java.awt.Desktop` | Llamar a `Desktop` desde la UI, o inventar un seam paralelo `OsActions` | `java.awt.Desktop` no existe en Android (regla 2 del proyecto); y `FileActions` ya existe (`student/02-chat.md` §5.2) ⇒ se extiende, no se duplica |
| **D10** | **No hay bandeja del sistema en v1.** El cierre de la ventana **sale de la app** | `ApplicationScope.Tray` | La bandeja confunde el modelo mental de una app educativa y en Linux ni siquiera existe; además filtra el nombre de la cátedra en una máquina compartida |
| **D11** | Las notificaciones son **un toast dentro de la app** + **un sonido opcional** (apagado por defecto). Sin notificación nativa | `TrayState.sendNotification` | Sin `SystemTray` no hay notificación nativa simple, y ya no usamos bandeja |
| **D12** | "Guardar como…" cambia de `java.awt.FileDialog` a **`javax.swing.JFileChooser`** | `FileDialog` | En Windows el `FileDialog` de AWT **no filtra por extensión** (verificado en el código del JDK 21) |
| **D13** | Modo **portable**: marcador `kitoru.portable` junto al ejecutable; precedencia `--home` > `KITORU_HOME` > `<app>/kitoru-home` > portable > `~/.kitoru` | Sin modo portable | Es la misma precedencia de la "casa" de Silvy (`--home` > `SILVY_HOME` > junto al binario): una sola idea para toda la familia |
| **D14** | El **tamaño mínimo real** se impone con `ComposeWindow.setMinimumSize(...)`, no con lógica de layout | Confiar en que el layout se degrade | Si no se impone, el usuario puede dejar la ventana en 200 px y la app queda inusable |

---

## 1. Hechos verificados (la base del diseño, no supuesto)

Todo lo que sigue se verificó contra las fuentes exactas que usa el proyecto
(Compose Multiplatform **1.7.3**, Kotlin **2.1.0**, JDK **21.0.12**). No es
"documentación de memoria": son los archivos que el build va a compilar.

### 1.1 La ventana

`androidx.compose.ui.window.Window` (desktop) tiene esta firma:

```kotlin
@ExperimentalComposeUiApi
@Composable
fun Window(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "Untitled",
    icon: Painter? = null,
    decoration: WindowDecoration,
    transparent: Boolean = false,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable FrameWindowScope.() -> Unit
)
```

* `WindowState` expone `placement` (`Floating`/`Maximized`/`Fullscreen`),
  `isMinimized`, `position` y `size` (`DpSize`). `rememberWindowState` usa
  `rememberSaveable`: **sobrevive a la recomposición, no al reinicio del proceso**.
* `FrameWindowScope.window` es un `ComposeWindow`, que **extiende `javax.swing.JFrame`**
  ⇒ tiene `setMinimumSize(Dimension)`, `getGraphicsConfiguration()`, `toFront()`.
  `ComposeWindow` es público, así que el mínimo se puede imponer sin reflection.
* **No existe un parámetro `minimumSize` en el `Window` de Compose Desktop 1.7.3.**
  Hay que bajarlo a `JFrame`.
* `LocalWindow` es **internal**: no se puede usar desde la app. Sólo sirve
  `FrameWindowScope.window`.

### 1.2 El menú nativo (el problema conocido)

`androidx.compose.ui.window.Menu` tiene **dos implementaciones** y la diferencia
es exactamente el problema:

| Scope | Implementación | Qué NO soporta |
|---|---|---|
| `MenuScope` sobre `java.awt.Menu` (bandeja) | `AwtMenuScope` | **Nada**: iconos, mnemónicos, atajos y `RadioButtonItem` tiran `UnsupportedOperationException` |
| `MenuBarScope` sobre `javax.swing.JMenuBar` (ventana) | `SwingMenuScope` | Soporta todo, **pero es Swing**: no sigue el tema de la app, no hay equivalente en Android |

`FrameWindowScope.MenuBar { … }` pone un `JMenuBar` en el `JFrame`. En Windows el
resultado es una barra de menús Swing **dentro** de la ventana, debajo de la barra
de título nativa: se ve "de Windows" pero no *es* Windows (no cambia con el tema
oscuro, y el `JMenuBar` de Swing tiene su propio look). Además:

* los `KeyShortcut` de Swing registran **aceleradores AWT**, que se dispararían
  **en paralelo** de los atajos de Compose ⇒ doble ejecución o carrera;
* `JMenuBar` mide 32 dp de alto y no se puede colapsar;
* no existe en Android, así que el menú sería **código de escritorio puro** en
  una app que se quiere multiplataforma.

**Conclusión: no usamos el menú nativo.** El menú de Kitoru es una fila de
`Text` + `DropdownMenu` dibujada por Compose (portable, temable, con el mismo
código en Android) y **los atajos de teclado son la fuente de verdad**: el menú
muestra el atajo, y el atajo funciona aunque el menú esté cerrado.

### 1.3 Arrastrar y soltar

* La API buena en 1.7.3 es
  `androidx.compose.foundation.draganddrop.dragAndDropTarget`
  (`@ExperimentalFoundationApi`):

  ```kotlin
  fun Modifier.dragAndDropTarget(
      shouldStartDragAndDrop: (startEvent: DragAndDropEvent) -> Boolean,
      target: DragAndDropTarget,
  ): Modifier
  ```

* La API vieja (`androidx.compose.ui.onExternalDrag` +
  `androidx.compose.ui.DragData.FilesList`) está **deprecada con
  `DeprecationLevel.ERROR`** en 1.7.3: el mensaje oficial es
  *"Use the new drag-and-drop API: Modifier.dragAndDropTarget"*. **No se puede
  usar sin `@Suppress("DEPRECATION_ERROR")`**, y es la API que se va a borrar.
* En desktop, `DragAndDropEvent.dragData()` (extension en
  `androidx.compose.ui.draganddrop`) devuelve el `DragData` ya detectado, y
  `DragData.FilesList.readFiles()` devuelve **las URIs** de los archivos
  (`file:///C:/Users/…`), no rutas planas. Internamente usa
  `DataFlavor.javaFileListFlavor` (confirmado en `AwtDragData.kt` del propio
  Compose 1.7.3):

  ```kotlin
  internal fun Transferable.dragData(): DragData {
      val bestTextFlavor = selectBestTextFlavor(transferDataFlavors)
      return when {
          isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> DragDataFilesListImpl(this)
          isDataFlavorSupported(DataFlavor.imageFlavor)           -> DragDataImageImpl(this)
          bestTextFlavor != null                                   -> DragDataTextImpl(bestTextFlavor, this)
          else                                                     -> UnknownDragData
      }
  }
  ```

  O sea: el `DataFlavor.javaFileListFlavor` **ya lo consulta el framework**; lo
  que nos falta es *sacarle el contenido* y traducir las URIs a rutas.
* `DragAndDropEvent.dragData()` es **desktop only** ⇒ el `actual` del seam tiene
  que estar en `desktopMain`.
* `Modifier.composed` está deprecado con `DeprecationLevel.HIDDEN` en 1.7.3:
  **no se puede usar**. Por eso el seam tiene que ser `@Composable`.

### 1.4 `jpackage` y las asociaciones de archivo

El JDK 21 **`jpackage` sí soporta `--file-associations`**:

```
--file-associations <file path>
      Path to a Properties file that contains list of key, value pairs.
      The keys "extension", "mime-type", "icon", and "description" can be used.
```

Y **el plugin de Compose Desktop 1.7.3 lo expone en el DSL**:

```kotlin
// org.jetbrains.compose.desktop.application.dsl.JvmApplicationDistributions
@JvmOverloads
fun fileAssociation(
    mimeType: String, extension: String, description: String,
    linuxIconFile: File? = null, windowsIconFile: File? = null, macOSIconFile: File? = null,
)
```

Qué hace el plugin con eso (verificado en
`org/jetbrains/compose/desktop/application/tasks/AbstractJPackageTask.kt`):

| SO | Qué genera |
|---|---|
| **Windows / Linux** | Escribe un `FA<ext>.properties` (`mime-type`, `extension`, `description`, `icon`) y pasa `--file-associations <file>` a jpackage. En el `.msi`, el builder de WiX escribe las claves de registro con `Extension`, `Verb` (`open`), `Command`, `Description` e `Icon`, más una entrada de menú contextual (`ContextMenuCommandLabel`) |
| **macOS** | Escribe `CFBundleDocumentTypes` en el `Info.plist` con `CFBundleTypeRole=Editor`, `CFBundleTypeExtensions`, `CFBundleTypeMIMETypes`, `CFBundleTypeName`, `CFBundleTypeIconFile` y `CFBundleTypeOSTypes` |

Además, en macOS el plugin permite `macOS { infoPlist { extraKeysRawXml = … } }`
para claves extra del `Info.plist`.

**Honestidad importante sobre "abrir con Kitoru por defecto":**

* En **Windows**, el instalador registra la app y la agrega a la lista
  *"Abrir con"*; **no puede volverla la app por defecto**: desde Windows 8 el
  `UserChoice` de `HKCU` sólo lo puede escribir el usuario a mano. Hay que
  decirlo en el README y en la pantalla de "Acerca de": *"Kitoru se registró
  para estos tipos de archivo; para que sea el predeterminado, elegilo en
  Configuración → Aplicaciones predeterminadas"*.
* En **macOS** el `Info.plist` declara los tipos; el "predeterminado" lo elige
  el usuario en *Ajustes del Sistema → Escritorio y Dock*.
* En **Linux** la asociación sale del `.desktop` con `MimeType=`; el gestor de
  archivos la muestra como opción.

### 1.5 `java.awt.FileDialog` vs `javax.swing.JFileChooser`

Verificado contra el código del JDK 21 (`java.desktop/java/awt/FileDialog.java`
y `sun/awt/windows/WFileDialogPeer.class`):

* El javadoc de `FileDialog.setFilenameFilter` dice textualmente:
  *"Filename filters do not function in Sun's reference implementation for
  Microsoft Windows."*
* En el peer de Windows, `setFilenameFilter` **sólo guarda el filtro** en un
  campo; el `checkFilenameFilter(...)` se usa para **validar el nombre escrito**
  cuando se cierra el diálogo, **no para filtrar la lista de archivos**.

⇒ En Windows, el `FileDialog` de AWT **muestra todos los archivos** y sólo
rechaza un nombre tecleado que no matchea el filtro. Es exactamente el
problema que enunciaba el encargo. `JFileChooser` sí tiene
`FileNameExtensionFilter` real, con el combo "Tipo de archivo", botón "Crear
carpeta" y memoria de la última carpeta.

### 1.6 `java.awt.Desktop`, `SystemTray` y el portapapeles

* `java.awt.Desktop` expone `OPEN`, `EDIT`, `PRINT`, `MAIL`, `BROWSE`,
  `BROWSE_FILE_DIR` (desde Java 9) y `MOVE_TO_TRASH`. En la práctica:
  * `open(File)` → abre con la app por defecto del SO;
  * `browseFileDirectory(File)` → **Explorer / Finder** con el archivo
    seleccionado ("mostrar en el explorador de archivos");
  * `browse(URI)` → abre una URL en el navegador.
  * `Desktop.getDesktop()` tira `HeadlessException` si no hay display y
    `UnsupportedOperationException`/`IOException` si la acción no está soportada
    ⇒ **todo el uso va envuelto en `runCatching`**.
* `java.awt.SystemTray.isSupported()` es `false` en muchos Linux de escritorio
  (GNOME con AppIndicator, Wayland) ⇒ la bandeja no es portable.
* `TrayState.sendNotification(Notification)` **sólo funciona con una `Tray`
  instalada**: el `Flow` de notificaciones se consume en la `Tray`. Sin bandeja
  no hay notificación nativa.
* El portapapeles del escritorio tiene dos caminos: `java.awt.Toolkit` (AWT) o
  `LocalClipboardManager` de Compose (común, y ya es el que usa
  `student/02-chat.md` D-4). **Se usa `LocalClipboardManager`**: es común y
  funciona en las dos plataformas.

---

## 2. La ventana

### 2.1 Números

| Concepto | Valor | Por qué |
|---|---|---|
| Tamaño inicial | **1280 × 820 dp** | Es el menor tamaño en el que el explorador de chats del docente entra en **3 columnas** con el rail colapsado (§2.2) |
| Tamaño mínimo | **1024 × 680 dp** | Por debajo, el panel de chats pasa a 2 columnas (`teacher/07-chats.md` §2.2) y el rail queda en iconos: 72 + 260 + visor = funcional |
| Alto mínimo | 680 dp | Cabecera (56) + banner (40) + lista + redactor (~140) + barra de estado (28): con 680 hay lugar para 6–7 mensajes visibles |
| Límite con la pantalla | **90 % del área útil** | En un portátil de 1366×768 la altura útil es ~728 px: una ventana de 820 dp se saldría de la pantalla |
| Ancho máximo | 100 % del área útil | — |

> **Ojo con los dp.** En Compose Desktop, `Dp` se multiplica por la densidad de
> la pantalla: en Windows con escalado 125 %, 1280 dp son 1600 px físicos. Por
> eso el cálculo de "cabe en la pantalla" hay que hacerlo con la densidad real
> (`Toolkit.getDefaultToolkit().screenResolution` / 96), no suponiendo 1.

### 2.2 El panel de tres columnas y el rail (el cálculo que manda)

`teacher/07-chats.md` §2.1/§2.2 define el panel del explorador de chats:

```
alumnos 280 dp │ hilos 320 dp │ visor = el resto
```

y el breakpoint es **del contenedor**: 3 columnas si el contenedor ≥ 1100 dp;
2 columnas si ≥ 760 dp.

`teacher/01-navegacion.md` §6.1 define el rail: 240 dp expandido, 72 dp en iconos.

| Ancho de ventana | Rail | Contenido | Layout del explorador | Ancho del visor |
|---|---|---|---|---|
| 1340 dp | 240 (expandido) | 1100 | 3 columnas | 500 dp |
| 1340 dp | 72 (colapsado) | 1268 | 3 columnas | 668 dp |
| 1280 dp | 72 (colapsado) | 1208 | 3 columnas | 608 dp |
| 1024 dp | 72 (colapsado) | 952 | 2 columnas | 692 dp |
| 1024 dp | 240 (expandido) | 784 | 2 columnas | 524 dp |

**Regla que sale de la tabla (D2): el rail se colapsa solo por debajo de
1340 dp.** Es decir: el rail expandido es un lujo de pantalla grande; en
cualquier otra cosa, iconos. El usuario puede igual expandirlo a mano
(`Ctrl+B`), y en ese caso el explorador de chats baja solo a 2 columnas (eso
lo resuelve el `BoxWithConstraints` del panel, que ya está diseñado).

El estado del colapso **se guarda en `AppConfig`** (`teacher/01-navegacion.md`
§6.3: *"El estado de colapso se guarda en `AppConfig`"*). Se le agrega un
campo:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/AppConfig.kt (agenda)
@Serializable
data class AppConfig(
    // … los campos que ya define process/01-packaging-ci.md §6.1 …
    /** El rail lateral está colapsado a iconos. */
    val railCollapsed: Boolean = false,
    /** Geometría de la ventana (escritorio). `null` = la de por defecto. */
    val window: WindowGeometry? = null,
)

/** Geometría de la ventana, en dp. */
@Serializable
data class WindowGeometry(
    val widthDp: Int,
    val heightDp: Int,
    val xDp: Int? = null,
    val yDp: Int? = null,
    val maximized: Boolean = false,
)
```

> Los dos campos son **aditivos y con default**, así que **no bumpean
> `schemaVersion`** (regla de `process/01-packaging-ci.md` §6.2). El
> `schemaVersion` sigue siendo responsabilidad de ese documento.

### 2.3 Persistencia de la geometría

`rememberWindowState` usa `rememberSaveable`: sobrevive a la recomposición,
**no al reinicio del proceso**. Para que al reabrir la app la ventana esté
donde el usuario la dejó hay que guardarla a mano:

* se lee de `AppConfig.window` al arrancar;
* se escribe al **cerrar** (en el `onCloseRequest`, §2.5) y también cada
  2 s mientras la ventana cambia de tamaño (`LaunchedEffect` con `snapshotFlow`
  sobre `state.size`).

### 2.4 `onCloseRequest`: guardar antes de cerrar

El `onCloseRequest` se invoca en el **hilo de eventos de AWT** (no en el
`Dispatchers.Main` de Compose): **no se puede bloquear** ahí. La estrategia es
un **diálogo de confirmación en Compose**, no un `JOptionPane`.

Estados en los que hay que preguntar:

| Situación | Qué se le pregunta al usuario |
|---|---|
| Hay una generación en curso | *"La respuesta está en curso. Si salís ahora queda guardada a medias."* → **Salir** / **Esperar** |
| Hay un borrador sin enviar en el redactor | *"Tenés un mensaje sin enviar."* → **Descartar y salir** / **Cancelar** |
| Hay un chat modificado que no se guardó en el store | (no aplica: el store escribe en cada mutación) |
| Nada de eso | se sale sin preguntar |

Y en **todos** los casos se guarda antes de salir:

1. el **borrador** del redactor (`composerInput`) en `config/drafts.json`;
2. la **geometría** de la ventana en `AppConfig.window`;
3. la **respuesta parcial** en curso: se cancela el `Job` y el mensaje queda con
   el texto parcial y la marca `"(detenido)"` (que es exactamente lo que ya
   hace `StudentChatViewModel.sendInternal`, `student/02-chat.md` §3.4);
4. el **índice** y los repositorios: `JsonListStore` escribe en cada mutación,
   pero se fuerza un `flush()` por si quedó algo en un buffer;
5. se **cierra el gateway** (`gateway.close()`) para no dejar un `llama-server`
   huérfano.

### 2.5 El código de la ventana

```kotlin
// desktop/src/main/kotlin/com/funshiengine/kitoru/desktop/Main.kt
package com.funshiengine.kitoru.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kitoru.ui.KitoruApp
import kitoru.ui.platform.DesktopPaths

fun main(args: Array<String>) = application {

    // 1. Rutas: portable, KITORU_HOME, --home, ~/.kitoru.  (§11)
    //    Se resuelve ANTES de componer: KitoruPaths es un objeto global.
    val paths = remember { DesktopPaths.resolve(args) }
    KitoruPaths.setDataDir(paths.dataDir)

    // 2. La geometría inicial ya viene recortada a la pantalla. (§2.1)
    val initialSize = remember { paths.initialWindowSize }

    val state = rememberWindowState(
        position = WindowPosition.PlatformDefault,
        size = initialSize,
    )

    val scope = rememberCoroutineScope()
    val vm = remember { KitoruViewModelFactory.create(paths) }

    // 3. El cierre pide confirmación si hay trabajo sin guardar. (§2.4)
    var pendingClose by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = { pendingClose = true },
        state = state,
        title = "Kitoru",
        icon = painterResource("/ic_kitoru.png"),
        resizable = true,
        alwaysOnTop = false,
    ) {
        // 4. El tamaño mínimo real se impone sobre el JFrame. (§2.1, D14)
        LaunchedEffect(Unit) {
            applyMinimumWindowSize(window, paths.minimumWindowSize)
        }

        MaterialTheme(colorScheme = vm.colorScheme) {
            KitoruApp(
                vm = vm,
                onQuit = {
                    scope.launch {
                        vm.shutdown()          // 5. guardar antes de salir
                        exitApplication()
                    }
                },
                onRequestClose = { pendingClose = true },
            )
        }

        // 6. El diálogo de confirmación del cierre.
        if (pendingClose) {
            CloseConfirmationDialog(
                canDiscardDraft = vm.hasDraft,
                isGenerating = vm.isGenerating,
                onConfirm = {
                    pendingClose = false
                    scope.launch {
                        vm.shutdown()
                        exitApplication()
                    }
                },
                onDismiss = { pendingClose = false },
            )
        }
    }
}
```

```kotlin
// desktop/src/main/kotlin/com/funshiengine/kitoru/desktop/DesktopWindow.kt
package com.funshiengine.kitoru.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import kotlin.math.roundToInt

/**
 * Tamaño mínimo e inicial de la ventana, recortados a la pantalla.
 *
 * Se calcula **fuera** de la composición (antes de crear la ventana) porque
 * `rememberWindowState` fija el tamaño en el primer frame: si se ajustara
 * después, la ventana aparecería con el tamaño grande y saltaría.
 */
object DesktopWindowGeometry {

    /** Tamaño por defecto, antes de recortar por la pantalla. */
    val DEFAULT = DpSize(1280.dp, 820.dp)

    /** Tamaño mínimo, antes de recortar por la pantalla. */
    val MINIMUM = DpSize(1024.dp, 680.dp)

    /** Fracción del área de pantalla que se puede usar. */
    private const val SCREEN_FRACTION = 0.90

    /**
     * El área útil de la pantalla principal, en **dp**.
     *
     * `screenResolution` devuelve los dpi del sistema (96 en Windows al 100 %,
     * 120 al 125 %, 144 al 150 %). De ahí la conversión a dp.
     */
    fun screenSizeInDp(): DpSize {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val bounds = ge.defaultScreenDevice.defaultConfiguration.bounds
        val dpi = try {
            java.awt.Toolkit.getDefaultToolkit().screenResolution
        } catch (e: Throwable) {
            96
        }.coerceAtLeast(72)
        val scale = dpi / 96.0
        return DpSize(
            width = (bounds.width / scale).roundToInt().dp,
            height = (bounds.height / scale).roundToInt().dp,
        )
    }

    /** El tamaño con el que se abre la ventana. */
    fun initial(): DpSize {
        val screen = screenSizeInDp()
        val maxW = (screen.width.value * SCREEN_FRACTION).roundToInt().dp
        val maxH = (screen.height.value * SCREEN_FRACTION).roundToInt().dp
        val w = minOf(DEFAULT.width, maxW).coerceAtLeast(MINIMUM.width)
        val h = minOf(DEFAULT.height, maxH).coerceAtLeast(MINIMUM.height)
        return DpSize(w, h)
    }

    /** El tamaño mínimo real, sin pasar de la pantalla. */
    fun minimum(): DpSize {
        val screen = screenSizeInDp()
        return DpSize(
            width = minOf(MINIMUM.width, screen.width),
            height = minOf(MINIMUM.height, screen.height),
        )
    }
}

/**
 * Impone el tamaño mínimo en la ventana real.
 *
 * Compose Desktop 1.7.3 **no** expone `minimumSize` en el `Window`: hay que
 * bajarlo al `JFrame` que hay debajo. `ComposeWindow` es público y
 * `setMinimumSize` es de `Component`, así que no hace falta reflection.
 */
fun applyMinimumWindowSize(window: androidx.compose.ui.awt.ComposeWindow, min: DpSize) {
    val density = window.density.densitySafe()
    window.setMinimumSize(
        Dimension(
            (min.width.value * density).roundToInt().coerceAtLeast(320),
            (min.height.value * density).roundToInt().coerceAtLeast(240),
        )
    )
}
```

> `window.density` es una extensión **internal** de Compose; por eso el código
> real usa `LocalDensity.current` dentro de la composición, o
> `Toolkit.getDefaultToolkit().screenResolution / 96f` fuera. La versión final
> del helper calcula la densidad una vez y la pasa por parámetro, para no
> depender de API interna.

### 2.6 Varias pantallas

| Situación | Comportamiento |
|---|---|
| Ventana única, navegación interna | Es lo de Kitoru: **una sola ventana**; el rol, la cátedra y la pantalla se cambian dentro (ver `teacher/01-navegacion.md`). La app no abre ventanas nuevas |
| Diálogos | `Dialog` de Compose (Material 3), **no** `java.awt.Dialog`: son modales de verdad, se ven con el tema de la app y no se salen de la ventana |
| "Abrir en una ventana aparte" | **No existe en v1.** Un chat en ventana aparte es tentador para el docente que quiere comparar dos alumnos, pero multiplica el estado de la app (`AppState` compartido, atajos, foco). Si se pide, se hace con un `DialogWindow` de Compose, que es una ventana de verdad, no un `Dialog` |
| Pantalla completa | `WindowState.placement = WindowPlacement.Fullscreen`; se agrega un atajo (`Ctrl+Shift+F`) y una entrada en el menú "Ver" |
| Minimizar | `state.isMinimized`: minimiza a la barra de tareas (no a la bandeja — §8) |

---

## 3. El menú de la aplicación

### 3.1 Opciones evaluadas

| Opción | A favor | En contra | Veredicto |
|---|---|---|---|
| **(a) Menú dibujado con Compose** (`Row` de `Text` + `DropdownMenu`) | Portable (el mismo código en Android, dentro de un `ModalBottomSheet`), temable, con íconos, con el atajo visible, y **funciona aunque el SO no quiera** | No es el menú del sistema: no aparece en la barra de título nativa de macOS ni se integra con el ALT de Windows | **ELEGIDA** |
| **(b) Barra de herramientas** | occupied menos alto, los íconos se leen más rápido | Oculta las acciones, y "Guardar como…" / "Salir" en una barra de herramientas es raro. Un toolbar sin menús es la UI de un editor, no de una app de escritorio | Descartada como **reemplazo**; se usa **además** del menú, para 3–4 acciones de alta frecuencia (ver §3.4) |
| **(c) Sólo atajos, sin menú** | Cero código de menú | El usuario **no descubre** los atajos: nadie adivina `Ctrl+Shift+E`. Sin menús, la app no se siente de escritorio | Se conserva como **complemento**: los atajos son la fuente de verdad, el menú los **documenta** |
| **(d) `FrameWindowScope.MenuBar` (Swing)** | Es "de verdad" un menú de escritorio | Swing: no sigue el tema, no hay en Android, duplica el manejo de atajos, y el `JMenuBar` de Swing con la LAF de Windows se ve de 2005 | Descartada (§1.2) |

**Recomendación: (a) + (c), con (b) como capa opcional.**
El menú es un **modelo de datos** en `commonMain` (para que Android pueda
usarlo en su menú `⋮`) y **dos renderizadores**: una fila horizontal en
escritorio, una hoja/hoja desplegable en Android. Los atajos se definen
**junto al comando**, así que el menú los muestra y el teclado los ejecuta: no
hay dos listas que mantener sincronizadas.

### 3.2 El modelo (en `commonMain`)

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/shell/AppCommand.kt
package kitoru.ui.shell

/**
 * Un comando de la aplicación.
 *
 * El comando es la **unidad de acción** de Kitoru: lo pueden disparar el
 * menú, un atajo de teclado, un botón de la toolbar o un clic. Se define en
 * un solo lugar, así que la etiqueta, el atajo y la disponibilidad siempre
 * coinciden.
 */
data class AppCommand(
    /** Identificador estable. Se usa en los tests y en el log. */
    val id: String,
    /** Etiqueta visible en el menú. */
    val label: String,
    /** Atajo de teclado (opcional). */
    val shortcut: AppShortcut? = null,
    /** Si el comando está disponible ahora. */
    val enabled: () -> Boolean = { true },
    /** Qué hace. */
    val run: () -> Unit,
)

/** Un ítem del menú. */
sealed interface AppMenuItem {
    /** Un comando. */
    data class Action(val command: AppCommand) : AppMenuItem

    /** Un submenú. */
    data class Submenu(val label: String, val items: List<AppMenuItem>) : AppMenuItem

    /** Separador. */
    data object Separator : AppMenuItem
}

/** Un menú de primer nivel ("Archivo", "Editar", …). */
data class AppMenu(val label: String, val items: List<AppMenuItem>)
```

### 3.3 El catálogo de menús

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/shell/AppMenus.kt
package kitoru.ui.shell

/**
 * Los menús de Kitoru.
 *
 * Es **la** definición de qué hace la app; el menú de escritorio y el de
 * Android se renderizan desde acá. Para que el docente y el alumno no
 * tengan menús distintos, los ítems se filtran por rol.
 */
object AppMenus {

    /** Construye los menús para el contexto dado. */
    fun build(ctx: MenuContext): List<AppMenu> = listOf(
        AppMenu("&Archivo", listOf(
            AppMenuItem.Action(ctx.newChat),
            AppMenuItem.Separator,
            AppMenuItem.Action(ctx.saveChatAsMarkdown),
            AppMenuItem.Action(ctx.exportData),
            AppMenuItem.Action(ctx.importCourse),
            AppMenuItem.Separator,
            AppMenuItem.Action(ctx.openDataFolder),
            AppMenuItem.Separator,
            AppMenuItem.Action(ctx.quit),
        )),
        AppMenu("&Editar", listOf(
            AppMenuItem.Action(ctx.search),
            AppMenuItem.Separator,
            AppMenuItem.Action(ctx.copy),
            AppMenuItem.Action(ctx.paste),
            AppMenuItem.Separator,
            AppMenuItem.Action(ctx.settings),
        )),
        AppMenu("&Ver", listOf(
            AppMenuItem.Action(ctx.toggleRail),
            AppMenuItem.Action(ctx.reloadIndex),
            AppMenuItem.Separator,
            AppMenuItem.Action(ctx.fullScreen),
        )),
        AppMenu("A&yuda", listOf(
            AppMenuItem.Action(ctx.help),
            AppMenuItem.Action(ctx.manual),
            AppMenuItem.Separator,
            AppMenuItem.Action(ctx.about),
        )),
    )
}
```

El `&` antes de una letra marca el **mnemónico**: en Windows, `Alt+A` abre
"Archivo" (si el menú es de Compose, hay que resolverlo a mano — ver §3.5).

### 3.4 El renderizador de escritorio

```kotlin
// ui/src/desktopMain/kotlin/kitoru/ui/shell/DesktopMenuBar.kt
package kitoru.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kitoru.ui.platform.currentHostOs

/**
 * La barra de menús de Kitoru, dibujada con Compose.
 *
 * **Por qué no `FrameWindowScope.MenuBar`**: esa API de Compose Desktop es un
 * `javax.swing.JMenuBar` (ver docs/design/platform/04-escritorio.md §1.2):
 * no sigue el tema, no existe en Android y sus aceleradores AWT pisan los
 * atajos de Compose. Acá el menú es un `Row` de `Text` con un `DropdownMenu`,
 * que es portable y temable.
 *
 * @param menus los menús (de `AppMenus.build`).
 * @param onDismiss se llama cuando se elige un ítem (para cerrar el menú).
 */
@Composable
fun DesktopMenuBar(
    menus: List<AppMenu>,
    modifier: Modifier = Modifier,
) {
    var openIndex by remember { mutableStateOf<Int?>(null) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            menus.forEachIndexed { index, menu ->
                Box {
                    val open = openIndex == index
                    Text(
                        text = menu.label.removePrefix("&"),
                        modifier = Modifier
                            .clickable { openIndex = if (open) null else index }
                            .background(
                                if (open) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer
                            )
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (open) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                    )
                    DropdownMenu(
                        expanded = open,
                        onDismissRequest = { openIndex = null },
                    ) {
                        menu.items.forEach { item ->
                            AppMenuItemRow(item) { openIndex = null }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Una fila del menú. */
@Composable
private fun AppMenuItemRow(item: AppMenuItem, onPicked: () -> Unit) {
    when (item) {
        AppMenuItem.Separator -> HorizontalDivider()

        is AppMenuItem.Action -> {
            val cmd = item.command
            DropdownMenuItem(
                text = { Text(cmd.label) },
                enabled = cmd.enabled(),
                onClick = {
                    onPicked()
                    cmd.run()
                },
                // El atajo se muestra a la derecha, como en cualquier app.
                trailingIcon = {
                    cmd.shortcut?.let { s ->
                        Text(
                            text = s.label(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }

        is AppMenuItem.Submenu -> {
            // Submenú: se abre otro DropdownMenu anclado al ítem.
            // Truco estándar de Material 3: un DropdownMenu dentro del
            // contenido del DropdownMenuItem.
            var subOpen by remember { mutableStateOf(false) }
            Box {
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = { subOpen = true },
                    trailingIcon = { Text("▸", style = MaterialTheme.typography.labelSmall) },
                )
                DropdownMenu(
                    expanded = subOpen,
                    onDismissRequest = { subOpen = false },
                ) {
                    item.items.forEach { sub -> AppMenuItemRow(sub) { subOpen = false; onPicked() } }
                }
            }
        }
    }
}
```

### 3.5 Detalles que hay que resolver

* **Mnemónicos (`Alt+F`)**: en un menú de Compose no hay mnemónico nativo. Se
  implementa con un `onPreviewKeyEvent` en la raíz que, con `Alt` apretada,
  busca el ítem cuyo `&` coincide y abre su menú. Es opcional y va detrás de
  un flag (`AppConfig.useMenuMnemonics`, default `true` en Windows/Linux,
  `false` en macOS, donde el mnemonic es `Ctrl+⌘`).
* **Atajo de la app**: el `Ctrl+,` de Ajustes es el estándar de Windows y
  macOS; en Linux, `Ctrl+,` también. Se usa `Key.Comma`.
* **La toolbar**: 3–4 acciones de alta frecuencia (Nuevo chat, Buscar,
  Adjuntar, Ajustes) en una `Row` de `IconButton` **debajo** de la barra de
  menús, sólo en escritorio. Se activa con `AppConfig.showToolbar`.
* **El atajo se ve en el menú, y el menú no es obligatorio**: el usuario que
  nunca abre el menú igual tiene los atajos; el usuario que abre elmenú
  descubre los atajos.

---

## 4. Los atajos de teclado

### 4.1 El mapa completo

Se **fusiona** lo que pide el encargo con lo que ya definieron los docs de
teacher (`teacher/01-navegacion.md` §7.2) y student. Un solo mapa, sin
duplicados.

| Atajo | Acción | Ámbito | Dónde se implementa |
|---|---|---|---|
| `Ctrl/Cmd + N` | **Nuevo chat** | global | `AppCommand.NewChat` |
| `Ctrl/Cmd + K` | **Buscar** (búsqueda global / paleta de comandos) | global | `AppCommand.Search` |
| `Ctrl/Cmd + ,` | **Ajustes** | global | `AppCommand.Settings` |
| `Ctrl/Cmd + Q` | **Salir** | global | `AppCommand.Quit` |
| `F1` | **Ayuda** | global | `AppCommand.Help` |
| `Esc` | **Cerrar** (ver §4.4) | contextual | `EscStack` |
| `Ctrl/Cmd + S` | Guardar la conversación como Markdown | chat | `AppCommand.SaveChatAsMarkdown` |
| `Ctrl/Cmd + O` | Abrir un archivo (`.kitoru`, material) | global | `AppCommand.OpenFile` |
| `Ctrl/Cmd + L` | Abrir la carpeta de datos en el explorador | global | `AppCommand.OpenDataFolder` |
| `Ctrl/Cmd + B` | Expandir / colapsar el rail lateral | global | `AppCommand.ToggleRail` |
| `Ctrl/Cmd + F` | Foco en el buscador de la sección actual | sección | `AppCommand.FocusSearch` |
| `Ctrl/Cmd + R` | Recargar el índice de material | sección | `AppCommand.ReloadIndex` |
| `Ctrl/Cmd + 1..9` | Ir a la sección *n* (en el orden del rail) | global | `AppCommand.GotoSection(n)` |
| `Ctrl/Cmd + Shift + E` | Exportar el informe analítico | docente | `AppCommand.ExportReport` |
| `Ctrl/Cmd + Shift + C` | Copiar el código de inscripción | docente | `AppCommand.CopyJoinCode` |
| `Alt + R` | Cambiar de rol (Alumno ⇄ Docente) | global | `AppCommand.SwitchRole` |
| `Alt + ↑/↓` | Mover el foco dentro del rail | rail | `teacher/01-navegacion.md` §7.2 |
| `Enter` | Enviar el mensaje (en el redactor) | chat | `student/02-chat.md` D-10 (ya implementado) |
| `Ctrl/Cmd + Shift + F` | Pantalla completa | global | `AppCommand.FullScreen` |

**"Ctrl" significa Ctrl *o* Cmd.** En macOS, `⌘N`; en Windows y Linux, `Ctrl+N`.
El matcher no distingue: si el usuario apretó cualquiera de los dos, el atajo
dispara. (Es además lo que espera la gente: `⌘N` en un teclado Windows no es un
gesto que alguien haga por accidente.)

### 4.2 El helper

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/shell/AppShortcut.kt
package kitoru.ui.shell

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kitoru.platform.HostOs

/**
 * Un atajo de teclado de Kitoru.
 *
 * **"Ctrl" significa Ctrl *o* Cmd**: en macOS el atajo es ⌘, en el resto es
 * Ctrl. [matches] no distingue entre los dos: si el usuario apretó cualquiera,
 * el atajo dispara. (Es lo que espera la gente, y lo que hacen las apps
 * multiplataforma.)
 *
 * Los modificadores son **exactos**: un atajo declarado sin `shift` **no**
 * dispara con `Shift` apretado. Así `Ctrl+S` y `Ctrl+Shift+S` son distintos.
 */
data class AppShortcut(
    /** La tecla. */
    val key: Key,
    /** Ctrl o Cmd. */
    val ctrl: Boolean = false,
    /** Shift. */
    val shift: Boolean = false,
    /** Alt. */
    val alt: Boolean = false,
) {

    /** ¿Este atajo coincide con el evento? */
    fun matches(event: KeyEvent): Boolean {
        // Sólo la pulsación dispara: el "key up" se ignora.
        if (event.type != KeyEventType.KeyDown) return false
        if (event.key != key) return false
        if (event.isAltPressed != alt) return false
        if (event.isShiftPressed != shift) return false
        val primary = event.isCtrlPressed || event.isMetaPressed
        return primary == ctrl
    }

    /**
     * Cómo se muestra en el menú: "Ctrl+N", "⌘K", "F1".
     *
     * @param os el SO, para saber si se usa ⌘ o Ctrl.
     */
    fun label(os: HostOs = currentHostOs()): String = buildString {
        if (ctrl) append(if (os == HostOs.MACOS) "⌘" else "Ctrl+")
        if (alt) append(if (os == HostOs.MACOS) "⌥" else "Alt+")
        if (shift) append(if (os == HostOs.MACOS) "⇧" else "Shift+")
        append(keyLabel(key))
    }
}

/** El nombre legible de una tecla. */
fun keyLabel(key: Key): String = when (key) {
    Key.Escape -> "Esc"
    Key.F1 -> "F1"
    Key.F2 -> "F2"
    // …
    Key.Comma -> ","
    Key.Period -> "."
    Key.Slash -> "/"
    Key.Space -> "Espacio"
    Key.Enter -> "Enter"
    Key.Tab -> "Tab"
    Key.One -> "1"
    Key.Two -> "2"
    // …
    Key.Nine -> "9"
    else -> key.toString().removePrefix("Key")
}
```

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/shell/ShortcutRegistry.kt
package kitoru.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.Modifier

/**
 * Registro de atajos: asocia atajos con acciones.
 *
 * Es una lista (no un mapa) porque **el orden importa**: si dos atajos
 * coincidieran, gana el primero registrado. En la práctica no deberían
 * coincidir nunca, pero el orden hace el comportamiento determinista.
 */
class ShortcutRegistry(entries: List<Pair<AppShortcut, () -> Unit>>) {

    private val entries: List<Pair<AppShortcut, () -> Unit>> = entries

    /** ¿Hay algún atajo registrado? */
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * Ejecuta la acción del primer atajo que coincide con [event].
     *
     * @return `true` si el evento se consumió (y no debe seguir bajando).
     */
    fun dispatch(event: KeyEvent): Boolean {
        for ((shortcut, action) in entries) {
            if (shortcut.matches(event)) {
                action()
                return true
            }
        }
        return false
    }
}

/**
 * El manejador de atajos, remembered.
 *
 * Devuelve una lambda **estable** (la misma identidad en cada recomposición)
 * que siempre ve la última lista de acciones: por eso se usa
 * `rememberUpdatedState` en vez de capturar las acciones a mano.
 */
@Composable
fun rememberShortcutHandler(
    actions: List<Pair<AppShortcut, () -> Unit>>,
): (KeyEvent) -> Boolean {
    val current by rememberUpdatedState(actions)
    return remember {
        { event: KeyEvent -> ShortcutRegistry(current).dispatch(event) }
    }
}

/**
 * Instala el manejador global de atajos.
 *
 * Va en la **raíz** de la composición de la ventana. `onPreviewKeyEvent` se
 * dispara **de abajo hacia arriba**: en la raíz, el evento se ve **antes**
 * que los campos de texto. Por eso el handler devuelve `false` cuando el
 * atajo no aplica, y así el evento sigue bajando al control con el foco.
 *
 * Ojo: mientras hay un diálogo **nativo** (el `JFileChooser` de Swing) abierto,
 * el árbol de Compose **no recibe eventos de teclado**: los atajos globales no
 * funcionan. Es aceptable (el diálogo es modal) pero hay que saberlo.
 */
fun Modifier.appShortcuts(handler: (KeyEvent) -> Boolean): Modifier =
    this.onPreviewKeyEvent { event -> handler(event) }
```

### 4.3 Cómo se enchufa en la app

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/shell/AppShell.kt (extracto)
@Composable
fun AppShell(vm: KitoruViewModel) {
    val esc = remember { EscStack() }
    val scope = rememberCoroutineScope()

    val handler = rememberShortcutHandler(
        buildList {
            add(AppShortcuts.NewChat to { vm.newChat() })
            add(AppShortcuts.Search to { vm.openSearch() })
            add(AppShortcuts.Settings to { vm.goTo(Screen.Settings) })
            add(AppShortcuts.Quit to { vm.requestQuit() })
            add(AppShortcuts.Help to { vm.openHelp() })
            add(AppShortcuts.Escape to {
                // Esc es contextual: primero se cierra lo que esté abierto.
                if (!esc.closeTop()) vm.stopGenerationOrNothing()
            })
            add(AppShortcuts.ToggleRail to { vm.toggleRail() })
            // … el resto del mapa de §4.1 …
        }
    )

    Box(Modifier.fillMaxSize().appShortcuts(handler)) {
        Column(Modifier.fillMaxSize()) {
            DesktopMenuBar(menus)            // sólo escritorio
            Box(Modifier.weight(1f)) { contenido }
        }
    }
}
```

**Orden de resolución de `Esc`** (de más específico a menos):

1. Si hay un **diálogo** abierto → se cierra.
2. Si hay un **menú desplegable** abierto → se cierra.
3. Si hay un **panel/hoja** abierto (fuentes del mensaje, panel de métricas) → se cierra.
4. Si el **campo de búsqueda** tiene el foco → se limpia.
5. Si la IA está **generando** → se detiene (y queda el texto parcial).
6. Si no hay nada abierto → no se hace nada (el evento sigue, sin efecto).

Eso se implementa con una pila de capas cerrables:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/shell/EscStack.kt
package kitoru.ui.shell

import androidx.compose.runtime.mutableStateListOf

/**
 * Una capa que se puede cerrar con la tecla Esc.
 */
interface EscLayer {
    /** Identificador (para depurar). */
    val id: String
    /** Cierra la capa. */
    fun close()
}

/**
 * La pila de capas cerrables con Esc.
 *
 * Vive en el shell: el atajo global de Esc no necesita saber qué hay abierto;
 * le pregunta a la pila y cierra la de arriba. Cada diálogo/hoja se registra
 * al aparecer y se desregistra al desaparecer (con `DisposableEffect`).
 */
class EscStack {
    private val layers = mutableStateListOf<EscLayer>()

    /** Registra una capa. Devuelve lo que hay que pasarle a `DisposableEffect`. */
    fun push(layer: EscLayer) { layers.add(layer) }

    /** Desregistra una capa. */
    fun remove(layer: EscLayer) { layers.remove(layer) }

    /** ¿Hay alguna capa abierta? */
    val isEmpty: Boolean get() = layers.isEmpty()

    /**
     * Cierra la capa de arriba.
     * @return `false` si no había ninguna (el atajo debe seguir su camino).
     */
    fun closeTop(): Boolean {
        val top = layers.lastOrNull() ?: return false
        top.close()
        return true
    }
}
```

### 4.4 `Esc` y los campos de texto

Un detalle que suele romper las apps: si el foco está en un `TextField` y el
usuario aprieta `Ctrl+S`, el atajo **debe** disparar (es lo que espera
cualquiera). Por eso los atajos con `Ctrl` se manejan en el `onPreviewKeyEvent`
de la raíz, que ve el evento **antes** que el campo. Los atajos **sin**
modificadores (`F1`, `Esc`) también: `F1` no molesta en un campo, y `Esc` lo
queremos global.

Lo que **no** hay que hacer es registrar atajos de una sola letra (tipo `?` o
`g`), porque en un campo de textogls esa letra tiene que escribirse. El mapa
§4.1 no tiene ninguno.

---

## 5. Arrastrar y soltar archivos

### 5.1 Qué se puede soltar

| Se suelta | Qué hace Kitoru |
|---|---|
| Un `.pdf` | Lo agrega como **material** de la cátedra actual (si el usuario es docente) o lo adjunta al chat (si es alumno) |
| Un `.md` / `.txt` | Idem |
| Un `.kitoru` | Lo **importa** como cátedra (bundle exportado) |
| Varios archivos | Se procesan en fila, con un resumen ("Se agregaron 3 archivos") |
| Una imagen | En v1: no se acepta; se avisa "Kitoru todavía no acepta imágenes" (las citas son sobre texto) |
| Texto suelto | No se acepta (lo de las citas es de archivos de la cátedra) |

### 5.2 El seam

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/DragAndDrop.kt
package kitoru.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Acepta archivos soltados sobre este componente.
 *
 * Es un `expect` porque el drag-and-drop **del sistema** sólo existe en
 * escritorio: en Android el equivalente es el *share sheet* y el
 * `ActivityResultContracts.OpenDocument`, que ya están cubiertos por otros
 * seams.
 *
 * @param enabled si está activo. `false` ⇒ el modifier no hace nada.
 * @param onFiles recibe las **rutas absolutas** de los archivos soltados.
 *        Recibe una lista vacía si lo soltado no eran archivos.
 */
@Composable
expect fun Modifier.dragAndDropFiles(
    enabled: Boolean = true,
    onFiles: (List<String>) -> Unit,
): Modifier
```

> Es `@Composable` a propósito: `Modifier.composed` está deprecado con
> `DeprecationLevel.HIDDEN` en Compose 1.7.3, así que no hay forma de tener
> un `remember` dentro de un modifier no composable. Con `expect @Composable`
> el `actual` de escritorio puede usar `rememberUpdatedState`.

### 5.3 La implementación de escritorio

```kotlin
// ui/src/desktopMain/kotlin/kitoru/ui/platform/DragAndDrop.desktop.kt
package kitoru.ui.platform

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import java.io.File
import java.net.URI

/**
 * Arrastrar y soltar de archivos, en escritorio.
 *
 * Se usa la API nueva (`Modifier.dragAndDropTarget`): la vieja
 * (`Modifier.onExternalDrag` + `androidx.compose.ui.DragData`) está
 * **deprecada con nivel ERROR** en Compose 1.7.3.
 *
 * `DragAndDropEvent.dragData()` ya resuelve el `DataFlavor.javaFileListFlavor`
 * por nosotros (ver `AwtDragData.kt` del propio Compose): devuelve un
 * `DragData.FilesList` si lo soltado eran archivos. Lo que falta es
 * **traducir las URIs a rutas**: `readFiles()` devuelve `file:///…`, no rutas.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.dragAndDropFiles(
    enabled: Boolean,
    onFiles: (List<String>) -> Unit,
): Modifier {
    if (!enabled) return this

    // `onFiles` cambia en cada recomposición: con `rememberUpdatedState` el
    // handler siempre llama a la última versión sin recrear el modifier.
    val currentOnFiles by rememberUpdatedState(onFiles)

    return this.dragAndDropTarget(
        // Sólo nos interesan los arrastres de archivos: si lo que se arrastra
        // es texto o una imagen, no participated de la sesión.
        shouldStartDragAndDrop = { event -> event.dragData() is DragData.FilesList },
        target = object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val data = event.dragData() as? DragData.FilesList ?: return false
                val paths = runCatching { data.readFiles() }
                    .getOrDefault(emptyList())
                    .mapNotNull { uri -> uriToPath(uri) }
                if (paths.isEmpty()) return false
                currentOnFiles(paths)
                // `true` = el evento se consumió: el SO muestra el ícono de
                // "copiado" y no busca otro destino.
                return true
            }
        },
    )
}

/** Convierte `file:///C:/Users/x/a.pdf` en `C:\Users\x\a.pdf`. */
private fun uriToPath(uri: String): String? = runCatching {
    val parsed = URI(uri)
    if (parsed.scheme == "file") File(parsed).path else null
}.getOrNull()
```

### 5.4 La implementación de Android

```kotlin
// ui/src/androidMain/kotlin/kitoru/ui/platform/DragAndDrop.android.kt
package kitoru.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * En Android no hay drag-and-drop del sistema con archivos: el camino es el
 * *share sheet* y el `OpenDocument`, que ya están resueltos en otros seams.
 *
 * Este `actual` es un no-op deliberado: **no** se tira excepción, para que el
 * mismo código de UI (con `dragAndDropFiles` en la raíz) compile y funcione en
 * las dos plataformas sin `if` por plataforma.
 */
@Composable
actual fun Modifier.dragAndDropFiles(
    enabled: Boolean,
    onFiles: (List<String>) -> Unit,
): Modifier = this
```

### 5.5 Dónde se usa y qué pasa con lo soltado

El destino de un archivo soltado depende de **dónde** se suelta:

| Se suelta sobre | Efecto |
|---|---|
| La lista de **materiales** de una cátedra (docente) | Se sube el archivo como material |
| El **redactor** del chat (alumno) | Se adjunta al mensaje (copia el archivo a `courses/<id>/attachments/`) |
| El **explorador de chats** (docente) | Nada: no tiene sentido; se avisa |

El procesamiento **no** puede pasar en el `onDrop` (es un callback de AWT, sin
coroutines): el `onDrop` sólo devuelve las rutas; el `ViewModel` las procesa en
un `launch(Dispatchers.IO)`.

---

## 6. Abrir archivos con la app por defecto

### 6.1 Qué se registra

| Extensión | MIME type | Descripción | Qué pasa al abrirlo |
|---|---|---|---|
| `.kitoru` | `application/x-kitoru` | "Cátedra de Kitoru" | **Importar** la cátedra del bundle (el flujo de `teacher/07-chats.md` §8) |
| `.md` | `text/markdown` | "Documento Markdown" | Abrir el **visor de material** de la cátedra actual (o ofrecer importarlo) |
| `.pdf` | `application/pdf` | "Documento PDF" | **Agregar el PDF como material** de la cátedra actual (docente) o **adjuntarlo al chat** (alumno) |

> **Decisión debatable, y por qué se elige igual:** registrar `.pdf` es
> agresivo (todo el mundo abre PDFs con el visor). Pero el flujo de Kitoru
> es exactamente "ingerir un PDF como material", y el doble clic es el camino
> más corto para hacerlo. Si molesta, se saca `.pdf` del registro y queda sólo
> `.kitoru` y `.md`: es **una línea** en el build.

### 6.2 El build

```kotlin
// desktop/build.gradle.kts (extracto; el resto en process/01-packaging-ci.md §3.1)
compose.desktop {
    application {
        mainClass = "com.funshiengine.kitoru.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Kitoru"
            packageVersion = kitoruVersion
            // … vendor, copyright, description …

            // --- Associaciones de archivo -----------------------------------
            // El plugin escribe un FA<ext>.properties y pasa
            // --file-associations a jpackage (Windows/Linux), y arma el
            // CFBundleDocumentTypes del Info.plist (macOS).
            fileAssociation(
                mimeType = "application/x-kitoru",
                extension = "kitoru",
                description = "Cátedra de Kitoru",
                linuxIconFile = project.file("icons/kitoru.png"),
                windowsIconFile = project.file("icons/kitoru.ico"),
                macOSIconFile = project.file("icons/kitoru.icns"),
            )
            fileAssociation(
                mimeType = "text/markdown",
                extension = "md",
                description = "Documento Markdown",
                linuxIconFile = project.file("icons/kitoru.png"),
                windowsIconFile = project.file("icons/kitoru.ico"),
                macOSIconFile = project.file("icons/kitoru.icns"),
            )
            fileAssociation(
                mimeType = "application/pdf",
                extension = "pdf",
                description = "Documento PDF",
                linuxIconFile = project.file("icons/kitoru.png"),
                windowsIconFile = project.file("icons/kitoru.ico"),
                macOSIconFile = project.file("icons/kitoru.icns"),
            )

            // --- Windows --------------------------------------------------
            windows {
                iconFile.set(project.file("icons/kitoru.ico"))
                upgradeUuid = windowsUpgradeUuid
                perUserInstall = true   // sin UAC; las asociaciones van a HKCU
                shortcut = true
                menu = true
                menuGroup = "Kitoru"
                console = false
            }

            // --- macOS ----------------------------------------------------
            macOS {
                iconFile.set(project.file("icons/kitoru.icns"))
                bundleID = "com.funshiengine.kitoru"
                packageVersion = macSafeVersion
                packageBuildVersion = kitoruVersionCode.toString()
                dockName = "Kitoru"
                appCategory = "public.app-category.education"
                minimumSystemVersion = "11.0"
                // Claves extra del Info.plist, si hicieran falta.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSHumanReadableCopyright</key>
                        <string>© 2026 Nootechnia</string>
                    """.trimIndent()
                }
            }

            // --- Linux ----------------------------------------------------
            linux {
                iconFile.set(project.file("icons/kitoru.png"))
                packageName = "kitoru"
                shortcut = true
                menuGroup = "Kitoru"
                appCategory = "Education"
                debMaintainer = "Nootechnia <soporte@nootechnia.dev>"
                appRelease = kitoruVersionCode.toString()
            }
        }
    }
}
```

### 6.3 Recibir el archivo que se abrió

Hay **tres** caminos, y los tres tienen que.write en el mismo buzón:

| SO | Cómo llega el archivo |
|---|---|
| Windows / Linux | **argv**: el SO lanza `Kitoru.exe "C:\...\curso.kitoru"` |
| macOS | **Apple Event `open`**, que AWT entrega a `Desktop.setOpenFileHandler` |
| Los tres | Un **doble clic** sobre un archivo ya asociado, cuando la app **ya está corriendo**: en Windows/Linux eso **vuelve a lanzar el proceso** (no hay IPC) ⇒ es el caso de la instancia única (§6.5) |

```kotlin
// desktop/src/main/kotlin/com/funshiengine/kitoru/desktop/OpenedFiles.kt
package com.funshiengine.kitoru.desktop

import java.awt.Desktop
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * El buzón de archivos que el SO pidió abrir.
 *
 * Los archivos llegan por dos caminos distintos y en momentos distintos:
 *  - por `argv`, sincrónicamente, antes de que exista la UI;
 *  - por el handler de macOS, en cualquier momento (incluso con la app ya
 *    corriendo).
 *
 * El buzón los junta y la UI los drena cuando está lista.
 */
class OpenedFiles {

    private val queue = ConcurrentLinkedQueue<String>()

    /** Encola un archivo para abrir. */
    fun offer(path: String) { queue.add(path) }

    /** Saca todos los archivos pendientes. */
    fun drain(): List<String> = buildList {
        while (true) {
            val p = queue.poll() ?: break
            add(p)
        }
    }

    companion object {
        /**
         * Registra el handler de "abrir con" de macOS.
         *
         * En Windows y Linux esto no hace nada (el archivo llega por `argv`),
         * pero registrarlo es inofensivo.
         */
        fun installMacOsHandler(onFile: (String) -> Unit) {
            runCatching {
                if (!Desktop.isDesktopSupported()) return
                val desktop = Desktop.getDesktop()
                desktop.setOpenFileHandler { event ->
                    event.file?.let { f -> onFile(f.absolutePath) }
                }
            }
        }
    }
}
```

```kotlin
// desktop/src/main/kotlin/com/funshiengine/kitoru/desktop/Main.kt (extracto)
fun main(args: Array<String>) = application {
    val paths = remember { DesktopPaths.resolve(args) }
    KitoruPaths.setDataDir(paths.dataDir)

    val opened = remember { OpenedFiles() }

    // 1. Los archivos de la línea de comandos.
    LaunchedEffect(Unit) {
        args.filter { it.isNotBlank() && !it.startsWith("-") }
            .map { File(it).absolutePath }
            .filter { File(it).isFile }
            .forEach { opened.offer(it) }
    }

    // 2. El handler de macOS (por si el SO lo dispara con la app corriendo).
    DisposableEffect(Unit) {
        OpenedFiles.installMacOsHandler { opened.offer(it) }
        onDispose { }
    }

    // 3. La UI drena el buzón cuando está lista.
    LaunchedEffect(vm.isReady) {
        if (!vm.isReady) return@LaunchedEffect
        opened.drain().forEach { vm.openExternalFile(it) }
    }
    // …
}
```

`vm.openExternalFile(path)` decide qué hacer según la extensión (§6.1) y
muestra el diálogo de confirmación correspondiente ("¿Importar esta cátedra?",
"¿Agregar este PDF como material de *Inteligencia Artificial*?").

### 6.4 Ser honesto sobre "por defecto"

En la pantalla "Acerca de" y en el README hay que decirlo:

> Kitoru se registra para abrir archivos `.kitoru`, `.md` y `.pdf`.
> En **Windows** el instalador la agrega a la lista *"Abrir con"* y al menú
> contextual, pero **no puede volverla la aplicación predeterminada**: eso lo
> elige el usuario en *Configuración → Aplicaciones → Predeterminados* (o con
> clic derecho → *Abrir con* → *Elegir otra aplicación* → *Marcar como
> predeterminada*). En **macOS**, en *Ajustes del Sistema → General → Escritorio
> y Dock*. En **Linux**, depende del gestor de archivos (GNOME: *Propiedades →
> Abrir con*).

### 6.5 Instancia única (obligatorio por lo anterior)

Si la app está corriendo y el usuario **vuelve a hacer doble clic** en un
`.kitoru`, el SO lanza un **segundo proceso** sobre los mismos datos. Sin una
protección, dos procesos escriben el mismo `threads.json` y se corrompe.

Diseño (simple y portable):

1. Al arrancar, se toma un lock **de sistema de archivos** sobre
   `<dataDir>/config/app.lock` (`FileChannel.tryLock()`): es atómico entre
   procesos y se libera solo si el proceso muere.
2. Si el lock **no** se puede tomar → hay otra instancia corriendo:
   * el archivo pedido (si lo hay) se copia a `<dataDir>/inbox/pending-<n>.kitoru`;
   * se muestra un mensaje *"Kitoru ya está corriendo"*;
   * el proceso **sale** con código 0.
3. La instancia viva **mira** `<dataDir>/inbox/` cada 2 s y abre lo que
   aparezca.

`FileChannel.tryLock()` está en `java.nio.channels`, que existe en Android
desde la API 1 (lo que **no** existe por debajo de la API 26 es
`java.nio.file.Path`). Como esto es **código de escritorio**, va en
`desktopMain` y no hay problema.

---

## 7. Integraciones de sistema operativo

### 7.1 El seam

El doc de student (`student/02-chat.md` §5.2) ya define `FileActions` con
`save`, `open` y `share`. Este doc **extiende** ese seam en vez de crear uno
paralelo (regla 1: reutilizar, no reescribir).

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/FileActions.kt (versión final)
package kitoru.ui.platform

import androidx.compose.runtime.Composable

/**
 * Acciones sobre archivos y carpetas que dependen del sistema operativo.
 *
 * Es el **único** punto de la app que necesita saber en qué plataforma está:
 * abrir un archivo, mostrarlo en el explorador, abrir una carpeta, copiar la
 * ruta o abrir una URL.
 */
interface FileActions {

    /**
     * Abre el archivo con la aplicación predeterminada del SO.
     * @return `false` si no se pudo (o si la plataforma no lo soporta).
     */
    fun open(path: String): Boolean

    /**
     * Muestra el archivo **seleccionado** en el explorador de archivos
     * (Explorer / Finder). Si la plataforma no lo soporta, cae a
     * [openFolder] con la carpeta padre.
     */
    fun revealInFolder(path: String): Boolean

    /** Abre la carpeta en el explorador de archivos. */
    fun openFolder(path: String): Boolean

    /** Copia la ruta al portapapeles del sistema. */
    fun copyPath(path: String): Boolean

    /** Abre una URL en el navegador. */
    fun openUrl(url: String): Boolean

    /**
     * Pide un destino y escribe el contenido.
     * @return `null` si el usuario canceló.
     */
    suspend fun save(request: SaveFileRequest): SavedFile?

    /** Comparte el archivo con otra app (hoja de compartir del sistema). */
    suspend fun share(path: String, mimeType: String): Boolean
}

/** Lanzador de acciones de archivo. */
@Composable
expect fun rememberFileActions(): FileActions
```

### 7.2 La implementación de escritorio

```kotlin
// ui/src/desktopMain/kotlin/kitoru/ui/platform/FileActions.desktop.kt
package kitoru.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * Acciones de escritorio sobre `java.awt.Desktop`.
 *
 * Reglas:
 *  - `Desktop.getDesktop()` **tira** `HeadlessException` en un entorno sin
 *    display y `UnsupportedOperationException` si la acción no está soportada:
 *    todo va envuelto en `runCatching` y devuelve `false`.
 *  - `browseFileDirectory` es "mostrar en el explorador"; no todos los SO lo
 *    soportan (el peer de Linux no siempre) ⇒ si falla, se cae a
 *    `openFolder` con la carpeta padre.
 */
class DesktopFileActions(
    private val clipboard: LocalClipboardManagerCompat,
) : FileActions {

    private fun desktop(): Desktop? = runCatching {
        if (!Desktop.isDesktopSupported()) null else Desktop.getDesktop()
    }.getOrNull()

    override fun open(path: String): Boolean = runCatching {
        val file = File(path)
        if (!file.exists()) return false
        val d = desktop() ?: return false
        if (!d.isSupported(Desktop.Action.OPEN)) return false
        d.open(file)
        true
    }.getOrDefault(false)

    override fun revealInFolder(path: String): Boolean {
        val file = File(path)
        val ok = runCatching {
            val d = desktop() ?: return false
            if (!d.isSupported(Desktop.Action.BROWSE_FILE_DIR)) return false
            d.browseFileDirectory(file)
            true
        }.getOrDefault(false)
        if (ok) return true
        // Fallback: abrir la carpeta padre.
        return openFolder(file.parentFile?.absolutePath ?: path)
    }

    override fun openFolder(path: String): Boolean = runCatching {
        val dir = File(path)
        if (!dir.isDirectory) return false
        val d = desktop() ?: return false
        if (!d.isSupported(Desktop.Action.BROWSE)) return false
        d.browse(dir.toURI())
        true
    }.getOrDefault(false)

    override fun copyPath(path: String): Boolean = runCatching {
        clipboard.setText(AnnotatedString(path))
        true
    }.getOrDefault(false)

    override fun openUrl(url: String): Boolean = runCatching {
        val d = desktop() ?: return false
        if (!d.isSupported(Desktop.Action.BROWSE)) return false
        d.browse(java.net.URI(url))
        true
    }.getOrDefault(false)

    // … save() y share() en §10 …
}

/** Adaptador mínimo para no depender de `LocalClipboardManager` fuera de composición. */
interface LocalClipboardManagerCompat {
    fun setText(text: AnnotatedString)
}

@Composable
actual fun rememberFileActions(): FileActions {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) {
        DesktopFileActions(object : LocalClipboardManagerCompat {
            override fun setText(text: AnnotatedString) = clipboard.setText(text)
        })
    }
}
```

### 7.3 La implementación de Android

```kotlin
// ui/src/androidMain/kotlin/kitoru/ui/platform/FileActions.android.kt (extracto)
actual fun rememberFileActions(): FileActions = remember { AndroidFileActions(context) }

/**
 * En Android, "abrir en el explorador" no existe como tal: lo más cercano
 * es abrir la carpeta con un `ACTION_VIEW` sobre un `FileProvider`, que
 * muchos exploradores de archivos no soportan. La UI **debe** ser honesta:
 * si la acción falla, se muestra "No hay app para abrir esta carpeta" en
 * lugar de fingir que funcionó.
 */
```

> El resto de la implementación de Android (SAF para guardar, share sheet para
> compartir) ya está diseñada en `student/02-chat.md` §5.4. Este doc sólo
> **agrega** `revealInFolder`, `openFolder`, `copyPath` y `openUrl`.

### 7.4 Dónde se usan

| Acción | Dónde |
|---|---|
| "Abrir" | La tarjeta de un material (docente), la de un adjunto (alumno), el `.kitoru` importado |
| "Mostrar en el explorador" | El menú contextual de un archivo generado por una herramienta (`CreateFileTool` de `:backend`) |
| "Abrir carpeta" | "Abrir carpeta de datos" en Ajustes y en "Acerca de" |
| "Copiar ruta" | El mismo menú contextual: *"Copiar ruta"* (copia la ruta al portapapeles y avisa *"Ruta copiada"*) |
| "Abrir URL" | "Manual de uso", "Licencias", "Buscar actualizaciones" |

---

## 8. La bandeja del sistema (system tray)

### 8.1 Decisión: **no hay bandeja en v1**

**No.** Kitoru no se minimiza a la bandeja.

Cuatro razones, en orden de peso:

1. **El modelo mental.** Kitoru es una app de aula: se abre, se chatea, se
   cierra. Una app que "se queda viva" sin que se note es una fuente
   clásica deWU mysteries ("¿me está consumiendo GPU?"). Enseñar que la
   ventana minimizada = la app sigue corriendo es una carga cognitiva que no
   aporta nada en el uso real.
2. **La máquina compartida.** En un laboratorio de facultad, el alumno 1 deja
   la app abierta; el alumno 2 se sienta. Un ícono en la bandeja con el
   nombre de la cátedra y del alumno es una fuga de información. La bandeja
   hace *visible* algo que la app Ideally no expone.
3. **La bandeja no es portable.** `java.awt.SystemTray.isSupported()` es
   `false` en muchos Linux (GNOME con AppIndicator, Wayland). Habría que
   escribir el mismo menú dos veces (la `AwtMenuScope` de la bandeja **no
   soporta íconos, ni mnemónicos, ni atajos, ni `RadioButtonItem`** — ver §1.2
   y el código de `Menu.desktop.kt`).
4. **El cierre de la ventana debe salir de la app.** Es la regla de las apps
   de escritorio de escritorio (a diferencia de las de comunicación, que
   quedan en la bandeja). El `onCloseRequest` (§2.4) pide confirmación y
   guarda.

### 8.2 Qué pasa en su lugar

* **Cerrar la ventana** = guardar y salir (§2.4).
* **Minimizar la ventana** = minimiza a la barra de tareas (comportamiento
  nativo: `state.isMinimized`).
* La app **no** intercepta el clic en el ícono de la barra de tareas: no hay
  nada que interceptar.

### 8.3 Cuándo se revisaría

Hay **un** caso que sí justificaría la bandeja: el **motor local**
(`llama-server`) corriendo en segundo plano. Si someday se ofrece "dejá el
modelo local prendido mientras estudiás", el ícono de la bandeja con
"Detener el modelo local" sería razonable. **Ese caso es de
Kitoru-Server** (la app que levanta el servidor y emite las API keys), no de
la app del alumno. Queda anotado, no implementado.

Si algún día se hace, el diseño sería:

```kotlin
// NO se implementa en v1. Es la forma en que se haría.
if (isTraySupported && settings.trayEnabled) {
    Tray(
        icon = painterResource("/ic_kitoru.png"),
        state = trayState,
        tooltip = "Kitoru",
        onAction = { window.isVisible = true; window.toFront() },
        menu = {
            Item("Abrir Kitoru", onClick = { window.toFront() })
            Item("Detener el modelo local", onClick = { vm.stopLocalModel() })
            Separator()
            Item("Salir", onClick = { vm.quit() })
        },
    )
}
```

Nótese que **los atajos no funcionan en la bandeja** (la `AwtMenuScope` no los
soporta): el menú de la bandeja es un menú "a ciegas". Otra razón para no
usarla.

---

## 9. Notificaciones de escritorio

### 9.1 Decisión: **toast dentro de la app + sonido opcional (apagado por defecto)**

No hay notificación nativa del SO, y la razón es técnica, no de gusto:

* `TrayState.sendNotification(Notification)` es la **única** API de
  notificaciones de Compose Desktop, y **sólo funciona si hay una `Tray`
  instalada** (el `Flow` lo consume la `Tray`, ver `Tray.desktop.kt`). Como no
  usamos bandeja (§8), no hay notificación nativa.
* `TrayIcon.displayMessage(...)` sobre Windows muestra un globo; sobre macOS
  no hace nada útil; sobre Linux, nada. Y obligaría a tener bandeja.

**Alternativas y por qué se descartan:**

| Alternativa | Por qué no |
|---|---|
| `SystemTray` con un `TrayIcon` oculto | Es un hack: un ícono invisible que a veces se ve. Además en Linux no funciona |
| `java.awt.Toolkit.beep()` | Es un bip, no una notificación. Sirve como *acompañamiento* de un toast, no como reemplazo |
| Traer la ventana al frente (`window.toFront()` / `Desktop.requestForeground(true)`) | Es agresivo: interrumpe lo que el usuario esté haciendo. Además en Windows hay que pelear con el *foreground lock* |
| Notificación del SO vía un proceso externo (notify-send / PowerShell) | Depende del SO, del shell y de la política del sistema. Demasiado frágil |

**Lo que sí se hace:**

1. **Toast dentro de la app** (un `Snackbar` de Material 3, o una banda
   propia si se quiere el estilo de Kitoru). Es el mecanismo principal:
   * "La respuesta terminó" (cuando el usuario estaba en otra pantalla);
   * "Se agregaron 3 materiales";
   * "La clave venció: pedile una nueva a la cátedra";
   * "No se pudo guardar: el disco está lleno".
2. **Un sonido opcional**, apagado por defecto, con la palanca en
   "Ajustes → Notificaciones". Se usa `javax.sound.sampled` (dentro de
   `java.desktop`, que ya está en el runtime embebido) con un `.wav` corto
   (~120 ms) en `desktop/src/main/resources/sounds/`.
3. **La ventana vuelve al frente** *sólo* si el usuario lo pidió explícitamente
   ("Traer la ventana al frente cuando termine la respuesta"), y **sólo** si
   la ventana no tiene el foco.

### 9.2 El código

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/shell/Toast.kt
package kitoru.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * Un aviso momentáneo dentro de la app.
 *
 * No es una notificación del SO: es una banda que aparece abajo, se va
 * sola, y **no** interrumpe lo que el usuario está haciendo. La notificación
 * nativa del SO no está disponible en desktop sin bandeja del sistema
 * (ver docs/design/platform/04-escritorio.md §9).
 */
@Immutable
data class Toast(
    val message: String,
    val kind: Kind = Kind.Info,
    /** Segundos que dura. 0 = hasta que se descarte. */
    val durationSeconds: Int = 4,
) {
    enum class Kind { Info, Success, Warning, Error }
}

/**
 * El canal de avisos de la app.
 *
 * Es una interfaz (no un `MutableState`) para que la UI pueda tener **más de
 * un** emisor (la app, un ViewModel, el watchdog del motor local) y que las
 * pruebas usen un fake.
 */
interface ToastChannel {
    /** Muestra un aviso. */
    fun show(toast: Toast)

    /** Muestra un aviso informativo. */
    fun info(message: String) = show(Toast(message, Toast.Kind.Info))

    /** Muestra un aviso de error. */
    fun error(message: String) = show(Toast(message, Toast.Kind.Error, 6))
}
```

```kotlin
// ui/src/desktopMain/kotlin/kitoru/ui/shell/DesktopSound.kt
package kitoru.ui.shell

import java.io.BufferedInputStream
import javax.sound.sampled.AudioSystem

/**
 * El sonido de "terminó la respuesta".
 *
 * Se reproduce con `javax.sound.sampled` (dentro de `java.desktop`, que ya
 * está en el runtime embebido de jpackage): no hace falta ni una dependencia
 * ni un reproductor externo.
 *
 * El sonido es **opcional** y **apagado por defecto**: en un aula, un ruido
 * de cada respuesta molesta a los demás.
 */
class DesktopSound(private val enabled: () -> Boolean) {

    private val clip by lazy {
        runCatching {
            AudioSystem.getClip().also { clip ->
                val stream = DesktopSound::class.java
                    .getResourceAsStream("/sounds/kitoru-notify.wav")
                    ?.let { BufferedInputStream(it) }
                if (stream != null) {
                    clip.open(AudioSystem.getAudioInputStream(stream))
                }
            }
        }.getOrNull()
    }

    /** Reproduce el sonido, si está habilitado. Nunca tira. */
    fun play() {
        if (!enabled()) return
        runCatching {
            val c = clip ?: return
            c.stop()
            c.setFramePosition(0)
            c.start()
        }
    }
}
```

> `javax.sound.sampled` necesita un **dispositivo de audio**: en una máquina
> sin salida de audio (un servidor, un contenedor) `AudioSystem.getClip()`
> tira `LineUnavailableException` ⇒ todo va en `runCatching` y el fallo es
> silencioso.

---

## 10. Los archivos del chat y "Guardar como…"

### 10.1 El problema con `java.awt.FileDialog`

El `FileSaver` de escritorio que existe hoy usa `java.awt.FileDialog`:

```kotlin
// ui/src/desktopMain/kotlin/kitoru/ui/platform/FileSaver.desktop.kt (actual)
val dialog = FileDialog(parent, "Guardar como", FileDialog.SAVE).apply {
    file = suggestedName
    isVisible = true
}
```

Problemas concretos:

| # | Problema | Evidencia |
|---|---|---|
| 1 | **No filtra por extensión en Windows.** El `FileDialog` de AWT muestra **todos** los archivos; el `FilenameFilter` sólo valida el nombre tecleado | `sun/awt/windows/WFileDialogPeer.setFilenameFilter` **sólo guarda** el filtro; el javadoc de `FileDialog.setFilenameFilter` dice *"Filename filters do not function in Sun's reference implementation for Microsoft Windows"* |
| 2 | No hay botón "Crear carpeta" en Windows | El diálogo nativo de Windows no lo tiene; el de macOS tampoco |
| 3 | `FileDialog` es un **diálogo de AWT, no de Compose**: se le da una `Frame` invisible como padre (`Frame().apply { isUndecorated = true }`), lo que en macOS puede robar el foco y en Windows deja una ventana fantasma en la lista de tareas | el código actual |
| 4 | El diálogo nativo no recuerda la última carpeta | — |
| 5 | No maneja **bytes**: `FileSaver.save` escribe `String` con UTF-8 ⇒ no sirve para un PDF ni para un `.kitoru` (que es un ZIP) | la firma actual: `save(suggestedName, content: String, mimeType)` |
| 6 | No muestra el tipo de archivo elegido ⇒ el alumno escribe `informe` y no sabe si le va a quedar `informe` o `informe.txt` | — |

### 10.2 Recomendación: `JFileChooser` en escritorio

**Se reemplaza `FileDialog` por `javax.swing.JFileChooser`.**

| | `FileDialog` (AWT) | `JFileChooser` (Swing) |
|---|---|---|
| Filtro por extensión | ❌ en Windows | ✅ `FileNameExtensionFilter` (con el combo "Tipo de archivo") |
| Crear carpeta | ❌ | ✅ (botón en el panel izquierdo) |
| Recuerda la última carpeta | ❌ | ✅ (propiedad `CURRENT_DIRECTORY`) |
| Modal con la ventana de Compose | ⚠️ es una ventana AWT aparte | ⚠️ ídem (también es Swing) |
| aspecto | nativo | Swing LAF (el "look" de Java) |
| Tamaño del código | 20 líneas | 25 líneas |

Se acepta el único punto negativo (el aspecto) a cambio de **que la función
principal funcione**: elegir la extensión. Y además, Compose Desktop **ya
depende de Swing** (la ventana es un `JFrame`), así que no se agrega ninguna
dependencia.

> **Alternativa considerada:** un diálogo de archivos hecho en Compose
> (`KitoruFileDialog`: árbol de carpetas + nombre + combo de tipo). Da un
> aspecto consistente y es portable a Android, pero son ~400 líneas y hay que
> reimplementar la navegación de carpetas. **Se anota para el hito 2**; para
> el hito 1, `JFileChooser`.

Además, el seam se **unifica con `FileActions`** y pasa a **bytes**, que es lo
que necesitan el export del `.kitoru` (un ZIP) y los PDF:

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/FileActions.kt (extracto)
/** Una petición de "guardar este contenido como archivo". */
data class SaveFileRequest(
    /** Nombre sugerido, con extensión ("informe.pdf"). */
    val suggestedName: String,
    /** Tipo MIME ("text/plain", "application/pdf"). */
    val mimeType: String,
    /** Contenido a escribir. */
    val bytes: ByteArray,
) {
    // data class con ByteArray: equals/hashCode por referencia; no importa
    // para un request, pero se deja el aviso por si se usa en un `remember`.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Resultado de guardar. */
data class SavedFile(val path: String, val displayName: String)
```

```kotlin
// ui/src/desktopMain/kotlin/kitoru/ui/platform/FileActions.desktop.kt (extracto)
override suspend fun save(request: SaveFileRequest): SavedFile? = withContext(Dispatchers.Swing) {
    val chooser = JFileChooser().apply {
        dialogTitle = "Guardar ${request.suggestedName}"
        selectedFile = File(request.suggestedName)
        // La última carpeta usada.
        DesktopPaths.lastDirectory()?.let { currentDirectory = it }
        fileFilter = FileNameExtensionFilter(
            request.mimeType,                       // "text/plain"
            *request.suggestedName.substringAfterLast('.', "").let { ext ->
                if (ext.isEmpty()) arrayOf("*") else arrayOf(ext)
            },
        )
        isAcceptAllFileFilterUsed = false
    }
    val chosen = chooser.showSaveDialog(null) ?: return@withContext null
    val file = chosen.absoluteFile
    runCatching {
        file.parentFile?.mkdirs()
        file.writeBytes(request.bytes)
    }.onFailure { return@withContext null }
    DesktopPaths.rememberDirectory(file.parentFile)
    SavedFile(file.absolutePath, file.name)
}
```

**Y la extensión se agrega sola** si el usuario no la puso (el `FileChooser`
no lo hace):

```kotlin
// ui/src/commonMain/kotlin/kitoru/ui/platform/FileActions.kt (extracto)
/**
 * Le agrega la extensión al nombre si el usuario no la puso.
 *
 * `JFileChooser` no lo hace y `FileDialog` tampoco: el alumno escribe
 * "informe" y le queda "informe" sin extensión, que después no se abre.
 */
fun SaveFileRequest.withExtension(defaultExtension: String): SaveFileRequest {
    if (suggestedName.endsWith(".$defaultExtension", ignoreCase = true)) return this
    return copy(suggestedName = "$suggestedName.$defaultExtension")
}
```

### 10.3 Los archivos que la app produce

| Qué | Formato | Dónde se guarda por defecto |
|---|---|---|
| Una conversación (alumno) | `.md` (Markdown) | `~/Documents/Kitoru/` (o lo que elija el usuario) |
| Una conversación (docente, con auditoría) | `.md` + `.json` (con los metadatos) | idem |
| La cátedra completa | `.kitoru` (ZIP) | idem |
| Un informe analítico | `.csv` | idem |
| Un informe en PDF | `.pdf` | idem |
| Un bloque de código | `.txt` con la extensión del lenguaje | "guardar como…" |

> La carpeta por defecto (`~/Documents/Kitoru`) la define
> `teacher/10-informes.md` §5 (`documentsDir`); el `FileSaver` la propone como
> `currentDirectory` inicial.

---

## 11. El modo portable

### 11.1 Qué es

Una carpeta con el ejecutable y, al lado, **la configuración y los datos**.
Todo vive en el pendrive: se enchufa en cualquier máquina, se abre Kitoru, y
están los chats, los materiales y la configuración.

Es el modo que hace falta en tres situaciones concretas:

1. El alumno **no tiene permiso de instalar** nada en la máquina del
   laboratorio (o no quiere ensuciarla).
2. El alumno quiere **llevarse todo** a otra máquina (cambio de notebook).
3. El docente quiere **entregar** una cátedra completa junto con el material
   (el `.kitoru` es el formato de *transporte*; el modo portable es el de
   *copia de seguridad completa*).

### 11.2 La detección

**Un archivo marcador junto al ejecutable.** Es el estándar de facto de las
apps portables (`portable.txt`, `PortableApps`, etc.) y no requiere ningún
argumento ni configuración.

```
Kitoru/
├── Kitoru.exe            (o Kitoru.app, o bin/Kitoru)
├── kitoru.portable       ← marcador: activa el modo portable
├── kitoru-home/          ← la "casa" (config, cursos, caché)
│   ├── config/
│   ├── courses/
│   └── cache/
```

**Precedencia** (idéntica a la de la casa de Silvy: `--home` > `SILVY_HOME` >
junto al binario — `Silvy/README.md` §"Casa de Silvy"):

| # | Origen | Cuándo gana |
|---|---|---|
| 1 | `--home <ruta>` en la línea de comandos | Siempre: es explícito |
| 2 | Variable de entorno `KITORU_HOME` | Si está definida y el directorio existe (o se puede crear) |
| 3 | `<carpeta del ejecutable>/kitoru-home` | Si existe |
| 4 | `<carpeta del ejecutable>` (modo portable) | Si existe el marcador `kitoru.portable` |
| 5 | `~/.kitoru` | El default |

```kotlin
// desktop/src/main/kotlin/com/funshiengine/kitoru/desktop/DesktopPaths.kt
package com.funshiengine.kitoru.desktop

import java.io.File

/**
 * Resuelve dónde viven los datos de Kitoru en el escritorio.
 *
 * La precedencia es **la misma que la casa de Silvy**
 * (`--home` > `SILVY_HOME` > junto al binario), para que toda la familia de
 * repos se comporte igual.
 */
object DesktopPaths {

    /** Nombre del marcador de modo portable. */
    const val PORTABLE_MARKER = "kitoru.portable"

    /** Nombre de la casa portable, junto al ejecutable. */
    const val PORTABLE_HOME = "kitoru-home"

    /**
     * La carpeta de la aplicación.
     *
     * En un `app-image` de jpackage, el lanzador está en `<app>/bin/` y el
     * sistema pone la variable de sistema `jpackage.app-path` con `<app>/`.
     * Cuando se corre con `./gradlew run` (sin empaquetar) **no** existe:
     * ahí la carpeta es el directorio de trabajo, que **no** es portable
     * (por eso el modo portable exige el marcador).
     */
    fun appDirectory(): File? {
        System.getProperty("jpackage.app-path")?.let { return File(it) }
        // Desarrollo: el directorio del proyecto, sólo para el log.
        return File(System.getProperty("user.dir")).takeIf { it.name != "build" }
    }

    /**
     * Resuelve la "casa" de Kitoru.
     *
     * @param args los argumentos de la línea de comandos.
     */
    fun resolve(args: Array<String>): Resolved {
        val app = appDirectory()

        // 1. --home <ruta>
        val explicit = args.indexOf("--home").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        if (explicit != null) {
            return Resolved(File(explicit).absoluteFile, Source.COMMAND_LINE)
        }

        // 2. KITORU_HOME
        val env = System.getenv("KITORU_HOME")?.takeIf { it.isNotBlank() }
        if (env != null) {
            return Resolved(File(env).absoluteFile, Source.ENVIRONMENT)
        }

        // 3. <app>/kitoru-home
        if (app != null) {
            val home = File(app, PORTABLE_HOME)
            if (home.isDirectory) {
                return Resolved(home.absoluteFile, Source.APP_HOME)
            }
            // 4. modo portable: hay marcador
            if (File(app, PORTABLE_MARKER).isFile) {
                return Resolved(File(app, PORTABLE_HOME).absoluteFile, Source.PORTABLE)
            }
        }

        // 5. el default
        return Resolved(
            File(System.getProperty("user.home"), ".kitoru").absoluteFile,
            Source.USER_HOME,
        )
    }

    /** De dónde salió la ruta. Se muestra en Ajustes ("¿dónde están mis datos?"). */
    enum class Source(val label: String) {
        COMMAND_LINE("indicado con --home"),
        ENVIRONMENT("la variable KITORU_HOME"),
        APP_HOME("junto a la aplicación (kitoru-home)"),
        PORTABLE("modo portable"),
        USER_HOME("la carpeta .kitoru del usuario"),
    }

    /** El resultado de la resolución. */
    data class Resolved(val dataDir: File, val source: Source) {
        val isPortable: Boolean get() = source == Source.PORTABLE || source == Source.APP_HOME
    }
}
```

Y el arranque:

```kotlin
// desktop/src/main/kotlin/com/funshiengine/kitoru/desktop/Main.kt (extracto)
val paths = remember { DesktopPaths.resolve(args) }
KitoruPaths.setDataDir(FilePath(paths.dataDir.absolutePath))
// Y en Ajustes → "Acerca de" se muestra:
//   "Tus datos: C:\Users\ana\.kitoru  (la carpeta .kitoru del usuario)"
```

### 11.3 Lo que el modo portable **no** resuelve: los secretos

`docs/design/security/01-claves.md` define el `SecureStore` con
`SecureStoreKind.DPAPI` (Windows), `MACOS_KEYCHAIN` (macOS), `LIBSECRET`
(Linux), `FILE_FALLBACK` y `MEMORY`.

**Los tres primeros están atados a la cuenta del usuario de la máquina.** Una
API key guardada en el `SecureStore` de la máquina A **no se puede leer** en
la máquina B, aunque el pendrive Traveling sea el mismo.

⇒ En **modo portable**, el `SecureStore` **debe** caer a `FILE_FALLBACK` (el
archivo cifrado con AES-GCM y la clave al lado) o a `MEMORY`, y la UI tiene que
**decirlo**:

> ⚠ Estás en **modo portable**. Las credenciales se guardan cifradas con una
> clave que viaja con la aplicación, en el mismo pendrive. **Cualquiera que
> tenga el pendrive puede usarlas.** Para protegerlas, usá el modo normal
> (instalado) o no guardes la clave.

La regla concreta:

```kotlin
// La implementación de escritorio del SecureStore recibe el modo portable.
expect fun systemSecureStore(portable: Boolean): SecureStore
```

y en el `actual` de escritorio, si `portable == true`, se **fuerza** el
`FILE_FALLBACK` (el archivo va en `<dataDir>/config/credentials.enc`, con la
clave en `<dataDir>/config/credentials.key`, permisos 0600) y se le avisa a la
UI con el `kind` real.

### 11.4 Otras cosas del modo portable

* **El modelo local (GGUF)**: pesa GB. En modo portable **no** se descarga
  (o se descarga al pendrive, si hay lugar: `modelsDir` cuelga de `dataDir`).
  La UI debe avisar del espacio disponible.
* **`llama-server`**: es un binario externo que el usuario aporta
  (`ModelPolicy.llamaServerPath`); en modo portable puede vivir en
  `<app>/bin/`.
* **El lock de instancia única** (§6.5) va en `<dataDir>/config/app.lock`: si
  el pendrive está enchufado en dos máquinas a la vez, cada una tiene su
  sistema de archivos ⇒ **dos instancias sí pueden correr** sobre el mismo
  pendrive. Eso **no se puede evitar** y hay que decirlo: *"No abras la misma
  carpeta portable en dos máquinas al mismo tiempo"*.
* **`--home` y `KITORU_HOME` ganan sobre el modo portable**: si alguien pasa
  `--home` explícitamente, quiere eso, no el pendrive.

---

## 12. Archivos que introduce este diseño

```
desktop/src/main/kotlin/com/funshiengine/kitoru/desktop/
├── Main.kt                              (ventana, menú, ciclo de vida)
├── DesktopPaths.kt                      (casa: --home / KITORU_HOME / portable)
├── DesktopWindowGeometry.kt             (tamaño inicial y mínimo, recorte por pantalla)
├── DesktopWindow.kt                     (applyMinimumWindowSize, foco, pantalla completa)
├── OpenedFiles.kt                       (buzón de archivos abiertos por el SO)
├── SingleInstance.kt                    (lock + inbox)
├── KtoruViewModelFactory.kt             (ensamblado del VM con las dependencias del SO)

ui/src/commonMain/kotlin/kitoru/ui/
├── shell/
│   ├── AppCommand.kt                    (AppCommand, AppMenu, AppMenuItem)
│   ├── AppMenus.kt                      (el catálogo de menús)
│   ├── AppShortcut.kt                   (AppShortcut + matching + etiqueta)
│   ├── ShortcutRegistry.kt              (registro + rememberShortcutHandler + appShortcuts)
│   ├── EscStack.kt                      (capas cerrables con Esc)
│   ├── Toast.kt                         (Toast + ToastChannel)
│   └── AppShell.kt                      (raíz: menú + toolbar + atajos)
├── platform/
│   ├── FileActions.kt                   (seam: open/revealInFolder/openFolder/copyPath/openUrl/save/share)
│   └── DragAndDrop.kt                   (seam: Modifier.dragAndDropFiles)

ui/src/desktopMain/kotlin/kitoru/ui/
├── shell/DesktopMenuBar.kt              (el menú dibujado con Compose)
├── shell/DesktopToolBar.kt              (opcional)
├── shell/DesktopSound.kt                (javax.sound.sampled)
├── platform/FileActions.desktop.kt      (java.awt.Desktop + JFileChooser)
├── platform/DragAndDrop.desktop.kt      (Modifier.dragAndDropTarget)

ui/src/androidMain/kotlin/kitoru/ui/
├── platform/FileActions.android.kt      (Intent / SAF / share sheet)
├── platform/DragAndDrop.android.kt      (no-op)

core/src/commonMain/kotlin/kitoru/platform/
├── HostOs.kt                            (expect fun currentHostOs(): HostOs)

core/src/desktopMain/kotlin/kitoru/platform/
└── HostOs.desktop.kt

desktop/src/main/resources/sounds/kitoru-notify.wav
desktop/build.gradle.kts                 (+ fileAssociation x3)
```

---

## 13. Checklist de verificación

| # | Qué se verifica | Cómo |
|---|---|---|
| 1 | La ventana abre a 1280×820 y **no** se sale de la pantalla | En un monitor de 1366×768, la ventana tiene que caber |
| 2 | El mínimo se impone de verdad | Arrastrar la ventana a 100×100 → el SO **no** la deja |
| 3 | El explorador de chats entra en 3 columnas a 1280 dp | Medir: rail 72 + 280 + 320 + visor 608 |
| 4 | A 1024 dp el explorador cae a 2 columnas sin romper | Redimensionar |
| 5 | El rail se colapsa solo por debajo de 1340 dp | Redimensionar |
| 6 | `Ctrl+N`, `Ctrl+K`, `Ctrl+,`, `Ctrl+Q`, `F1` funcionan | Con el foco en un campo de texto y fuera de él |
| 7 | `Esc` cierra el diálogo → el desplegable → el panel → detiene la generación | En ese orden |
| 8 | `Ctrl+Q` pide confirmación si hay una generación en curso | — |
| 9 | Al cerrar con un borrador, el borrador **vuelve** al reabrir | — |
| 10 | La geometría de la ventana se conserva al reabrir | — |
| 11 | Soltar un PDF sobre la lista de materiales lo sube | En un `.app-image` de prueba |
| 12 | Soltar un PDF sobre el redactor lo adjunta | — |
| 13 | Soltar texto **no** hace nada (no rompe) | — |
| 14 | Doble clic en un `.kitoru` lo importa | Verificar el registro de Windows / el `Info.plist` de macOS |
| 15 | En macOS, el doble clic con la app **corriendo** abre el archivo | `Desktop.setOpenFileHandler` |
| 16 | Una segunda instancia no corrompe los datos | Lock + inbox |
| 17 | "Abrir en el explorador" abre Explorer/Finder con el archivo seleccionado | — |
| 18 | "Guardar como…" ofrece el filtro de extensión **en Windows** | `JFileChooser`, no `FileDialog` |
| 19 | "Guardar como…" propone la última carpeta usada | — |
| 20 | La app **no** deja datos en `~/.kitoru` cuando está en modo portable | — |
| 21 | El modo portable avisa que las credenciales están protegidas peor | — |
| 22 | En Linux sin bandeja, la app arranca y cierra bien | `isTraySupported == false` no debe romper nada |
| 23 | En una máquina sin audio, el sonido no rompe la app | `AudioSystem.getClip()` en `runCatching` |
| 24 | `Esc` **no** borra el texto del redactor | — |

---

## 14. Decisiones que quedan abiertas

| # | Pregunta | Por qué está abierta | Criterio para decidir |
|---|---|---|---|
| 1 | ¿Ventana única o ventanas separadas por chat? | "Abrir el chat de Ana en otra ventana" es tentador para el docente | Cuando haya **dos** usos reales (comparar alumnos, charlar con la cátedra mientras se mira el material) |
| 2 | ¿El `.pdf` se registra como asociación de archivo? | Es agresivo: todo el mundo abre PDFs con el visor | Si molesta en las pruebas del claustro, se saca (una línea) |
| 3 | ¿Barra de herramientas además del menú? | Duplica acciones | Si el menú se usa poco en las pruebas de usabilidad |
| 4 | ¿Bandeja para el motor local? | `Kitoru-Server` es la app que lo levanta | Cuando Kitoru-Server exista |
| 5 | ¿Notificación nativa por proceso externo (`notify-send`)? | Depende del SO y de la política del sistema | Si se pide "avisame aunque la ventana esté atrás" |
| 6 | ¿Diálogo de archivos propio en Compose en vez de `JFileChooser`? | Consistencia visual y portabilidad a Android | Cuando el look de Swing moleste en las pruebas |

---

## 15. Resumen en cinco líneas

1. **La ventana**: 1280 × 820 dp al abrir, mínimo real de 1024 × 680 dp impostos
   con `ComposeWindow.setMinimumSize` (Compose Desktop 1.7.3 no expone
   `minimumSize`), recortados al 90 % de la pantalla; el rail lateral se
   colapsa solo por debajo de 1340 dp para que el explorador de chats del
   docente entre en tres columnas.
2. **El menú**: no hay menú nativo (el `MenuBar` de Compose Desktop es Swing,
   no temable y no portable); se dibuja con Compose (`Row` de `Text` +
   `DropdownMenu`) sobre un catálogo de `AppCommand` en `commonMain`, y los
   atajos de teclado son la fuente de verdad.
3. **Los atajos**: un solo mapa (Ctrl+N, Ctrl+K, Ctrl+,, Ctrl+Q, F1, Esc, más
   Ctrl+S/O/L/B/F/R, Ctrl+1..9, Alt+R) despachado con
   `Modifier.onPreviewKeyEvent` en la raíz mediante un helper propio
   (`AppShortcut` + `ShortcutRegistry`), donde "Ctrl" significa Ctrl **o** Cmd.
4. **El SO**: arrastrar y soltar con `Modifier.dragAndDropTarget` (la API vieja
   está deprecada con nivel ERROR) tras un seam `expect @Composable` en
   `commonMain`; integraciones de archivos por `java.awt.Desktop` tras otro
   seam; asociaciones `.kitoru`/`.md`/`.pdf` declaradas con
   `nativeDistributions { fileAssociation(...) }`, que el plugin traduce a
   `--file-associations` de jpackage y a `CFBundleDocumentTypes` en macOS.
5. **Las tres decisiones de fondo**: **no** hay bandeja del sistema (el cierre
   de la ventana sale de la app, con guardado y confirmación previos), las
   notificaciones son un **toast dentro de la app** con sonido opcional, y
   "Guardar como…" cambia de `java.awt.FileDialog` (que en Windows **no**
   filtra por extensión) a `JFileChooser`; el modo portable se activa con un
   marcador `kitoru.portable` junto al ejecutable y usa la misma precedencia
   que la casa de Silvy (`--home` > `KITORU_HOME` > junto al binario).
