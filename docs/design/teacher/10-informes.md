# 10. Informes y exportación del docente

> Subsistema que define **cómo el docente saca la información de la cátedra
> hacia afuera**: qué informes existen, qué se ve en cada uno, en qué formato
> se exportan, dónde se escribe el archivo y cómo se llama.

---

## 0. Decisiones en una línea

| Tema | Decisión |
|---|---|
| Formatos | **CSV, JSON, Markdown** en el hito 1 (puro Kotlin, sin dependencias). **PDF** desde el hito 1 en desktop y desde el hito 2 en Android. |
| IR | Un único modelo de informe (`Report`) → 4 renderizadores. Se escribe una vez, se imprime cuatro. |
| Dónde se escribe | Seam `FileExporter` + `SaveTarget` sobre `FileSystem`. 4 destinos: app, documentos, "guardar como…" (SAF / diálogo nativo), compartir. |
| PDF Android | `com.tom_roush:pdfbox-android` (API idéntica a PDFBox 2.0) → **un solo renderer en `jvmCommonMain`**. Activado en el hito 2 (4 MB de APK). |
| Horas de estudio | Estimación **determinista** por contenido + silencios. Se etiqueta siempre como "estimado" con su metodología al pie. |
| Exportación masiva | Un `.kitoru` = ZIP con `manifest.json` (versión + checksums) + `courses/materials/threads/messages.json` + materiales. |
| Programados | **No en el hito 1.** Desktop: planificador dentro de la app abierta. Android: `WorkManager` semanal. Decidido para el hito 2. |
| Visibilidad | Regla dura: `FULL` ve texto, `METRICS_ONLY` ve agregados, `NONE` no aparece (ni en el conteo de nombres). |

---

## 1. Formatos de exportación

### 1.1 Cuál sirve para qué

| Formato | Para qué | Quién lo consume | Estado |
|---|---|---|---|
| **CSV** | Planilla (Excel/LibreOffice) y análisis externo (pandas, R, SQL). Una tabla por archivo, columnas planas, números crudos. | El docente y el departamento | Hito 1 |
| **JSON** | Reimportar en Kitoru (reabrir un informe en otra máquina) o procesar con scripts. Contiene el IR completo, con tipos. | Scripts, el propio Kitoru | Hito 1 |
| **Markdown** | Informe **legible**: para un parcial, una reunión de cátedra, un mail al departamento o un ticket. Se pega en cualquier lado. | El docente, el departamento | Hito 1 |
| **PDF** | Reporte **imprimible**: mismo contenido que Markdown pero paginado, con portada, números de página y notas al pie. | El docente (imprime), el departamento | Hito 1 desktop / Hito 2 Android |
| **`.kitoru`** | Respaldo/traslado completo de una cátedra. Un solo archivo con todo. | El docente, otro docente, Kitoru mismo | Hito 1 (escritura) / Hito 2 (importación) |

Decisión de producto: **CSV, JSON y Markdown cubren el 90 % de los casos con
cero dependencias**. El PDF es "el formato de impresión" y entra después. El `.kitoru`
no es un formato de informe sino de **respaldo** (§6).

### 1.2 CSV

* Separador: **`;` por defecto** (Excel/LibreOffice en español parten por `;`),
  con un interruptor en el diálogo de exportación a `,` para análisis externo.
  Constante única `CsvRenderer.SEPARATOR` para que nadie la hardcodee.
* **BOM UTF-8** (`\uFEFF`) al principio: sin él, Excel rompe los acentos.
* Fin de línea **CRLF**: Excel en Windows lo espera.
* Comillas: RFC 4180 — un campo va entre `"` si contiene `;`, `"`, salto de
  línea o espacios al principio/final; las `"` internas se duplican.
* **Separador decimal `.`** y **sin separador de miles**: el CSV es para
  máquinas. `0.8734`, no `87,34 %`.
* **Sin dato ≠ cero**: si no hay citas, la celda va **vacía** (no `0`). Por eso
  la celda del IR distingue `number = null` de `number = 0.0`.
* **Una tabla por archivo**: `informe-actividad_...csv` trae la tabla resumen
  por alumno; `informe-calidad_...csv` trae la tabla de calidad por material.
  Los informes con varias tablas se exportan **una tabla por archivo**, con
  sufijo (`..._calidad-por-material.csv`), para que `pd.read_csv` funcione de
  una. El JSON y el Markdown sí llevan todo junto.
* La primera fila es la de encabezados; los nombres de columna son
  `snake_case` sin acentos (`tasa_citas_respaldo`, `horas_estimadas`), para
  que sirvan directamente de nombres de columna en pandas.

### 1.3 JSON

* Se serializa el **IR** (`Report`) con el `KitoruJson` que ya usa la app
  (`prettyPrint = true`, `encodeDefaults = true`, `ignoreUnknownKeys = true`).
  Un solo formato JSON en todo el proyecto: el de `JsonStore.kt`.
* Lleva `schemaVersion: Int = 1` en la raíz. Al reimportar, si
  `schemaVersion > REPORT_SCHEMA_VERSION` se rechaza con un mensaje en
  español: *"Este informe se generó con una versión más nueva de Kitoru."*
* El JSON es **la fuente de verdad para reimportar**: `ReportReader` lo lee,
  lo valida y la UI lo vuelve a renderizar (así el docente puede abrir en el
  celular un informe generado en el escritorio).
* Los números van como números (`0.8734`), los textos con acentos, y las
  celdas sin dato como `null`. No hay formato "humano" en el JSON.

### 1.4 Markdown

* GFM: títulos `#`/`##`, tablas `|…|`, listas `-`, código ```` ``` ````.
* Encabezado del informe con los metadatos (cátedra, código, cursada,
  período analizado, fecha de generación, resumen de visibilidad) en una
  tabla de dos columnas.
* Cada tabla lleva un título `###` y, si aplica, una nota al pie `> …`.
* **Al pie del documento, siempre**, dos secciones:
  * `## Metodología` — de dónde salen los números (en especial las horas
    estimadas) y qué reglas de visibilidad se aplicaron.
  * `## Advertencias` — p.ej. *"3 de 45 alumnos no comparten sus métricas con
    la cátedra; sus datos no están en este informe."*
* Es el formato que se **pega en un mail** sin retocar. Las tablas no se
  acortan: en Markdown va la tabla entera.

### 1.5 PDF

#### 1.5.1 El veredicto

**El PDF se genera en las dos plataformas con un único renderer**, porque
`com.tom_roush:pdfbox-android` es un fork de **PDFBox 2.0** y expone
exactamente la misma API que `org.apache.pdfbox:pdfbox` de escritorio:

| | Escritorio | Android |
|---|---|---|
| Artefacto | `org.apache.pdfbox:pdfbox:2.0.30` | `com.tom_roush:pdfbox-android:2.0.27.0` |
| API | `org.apache.pdfbox.*` (línea 2.0) | `org.apache.pdfbox.*` (línea 2.0) |
| Código | `PdfRenderer` en `:reports/desktopMain` | el mismo archivo, sin cambios |
| Extra | 2 MB de dependencia en el jar de escritorio | ≈ 4 MB en el APK (fuentes y recursos) |

**Veredicto en dos tiempos:**

* **Hito 1 → PDF en desktop solamente.** Motivo honesto: el APK de Android
  crece ~4 MB y el docente (que es la persona que *imprime* un informe para
  una reunión) trabaja casi siempre en el escritorio. El APK chico importa en
  Android, donde la app se distribuye a alumnos.
* **Hito 2 → PDF en Android**, sin reescribir nada: se **mueve** el renderer
  de `:reports/desktopMain` a `:reports/jvmCommonMain` y se agrega
  `implementation(libs.pdfbox.android)` en `:reports/androidMain`. El código
  no cambia una línea.

Restricciones que hay que respetar (si no, el PDF sale roto):

1. **Ambos artefactos en la línea 2.0.** PDFBox 3.0 movió
   `PDType1Font`/`PDDocument.save` a otra API (`org.apache.pdfbox:pdfbox:3.x`
   usa `Loader.loadPDF` y cambió el manejo de fuentes). Si el escritorio
   sube a 3.x, el renderer hay que adaptarlo. Se anota como deuda conocida.
2. **Fuentes estándar (WinAnsi).** `PDType1Font.HELVETICA` cubre
   español (`á é í ó ú ñ ü`), pero **no** emojis, flechas, `≥`, `€` fuera de
   Latin-1, etc. El renderer pasa **todo** el texto por un saneador
   `sanitizeWinAnsi()` que reemplaza `— → -`, `· → -`, `→ → ->`, `• → -`,
   `… → ...`, `“ ” → "`, `‘ ’ → '` y descarta lo que no entre en
   `32..126 ∪ 160..255`. (Alternativa a futuro: empaquetar una TTF — Noto
   Sans — como recurso; son ~500 KB más y queda mucho mejor. Es el plan B si
   el sanitize empieza a comerse cosas.)
3. **PDFBox en Android necesita `PDFBoxResourceLoader.init(context)`** una
   vez al arrancar, si no no encuentra sus recursos.
4. **Memoria.** Un informe de cátedra son decenas de páginas: se genera
   enteramente en memoria (`PDDocument` → `ByteArrayOutputStream`) y se
   escribe de una. Para informes enormes (> 200 MB de texto) se corta con un
   mensaje claro en vez de hacer OOM.
5. **Alternativa ligera si el APK pesa**: en Android, `PrintManager` +
   `PrintDocumentAdapter` sobre un HTML deja que el **diálogo de impresión del
   sistema** ofrezca "Guardar como PDF" sin ninguna dependencia. Se deja
   anotado como plan C (hito 3), no como plan A.

#### 1.5.2 Cómo se arma el PDF (a mano, sin librerías de layout)

PDFBox 2.0 no trae un motor de tablas. El renderer dibuja:

* Página A4: 595 × 842 pt. Márgenes 56 pt (2 cm). Ancho útil 483 pt.
* Fuentes: `HELVETICA_BOLD` 16 pt (portada), 12 pt (título de tabla),
  `HELVETICA` 9 pt (cuerpo).
* **Portada**: título del informe, cátedra (código · nombre), cursada,
  período analizado, fecha y hora de generación, resumen de visibilidad.
  Pie: *"Kitoru · informe generado automáticamente a partir de los datos de la
  cátedra"*.
* **Una tabla por sección**, con salto de página entre tablas.
* **Ancho de columna**: proporcional a `max(longitud del encabezado, media de
  la longitud de las celdas)`, con mínimo 45 pt y máximo 220 pt, y
  normalizado al ancho útil. El resto del ancho se reparte proporcionalmente.
* **Envolvido de texto**: se parte por espacios usando
  `PDType1Font.getStringWidth(txt) / 1000 * tamaño`; una celda con más de 8
  líneas se recorta con `…` y la tabla lleva la nota *"texto recortado; el
  informe completo está en Markdown/CSV"*.
* **Encabezado de tabla repetido** en cada página.
* **Numeración** "Página X de Y" (Y se conoce al cerrar el documento) y un
  pie con la fecha.
* Se genera en `withContext(ioDispatcher)` porque `PDDocument.save` es
  bloqueante.

### 1.6 Un renderizador, cuatro salidas

```
              ┌──────────────────────────┐
   dominio ──►│  Report (IR, commonMain) │──► CsvRenderer   (commonMain)
              │  ReportTable/Column/Cell │──► JsonRenderer  (commonMain)
              │                          │──► MarkdownRenderer (commonMain)
              └──────────────────────────┘──► PdfRenderer   (desktop → jvmCommonMain)
```

El IR es **la única forma** en que un informe existe. La UI lo muestra en
pantalla con el mismo IR (así lo que ve el docente en pantalla es exactamente
lo que se exporta), y los renderizadores son funciones puras
`Report → ByteArray`. Consecuencia práctica: **un bug de formato se arregla
en un solo lugar**, y el PDF nunca puede "desincronizarse" del Markdown.

---

## 2. Dónde se escribe el archivo: el seam `FileExporter` / `SaveTarget`

### 2.1 Por qué `FileSystem` no alcanza

`FileSystem` (`core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt`) es
portable y funciona en las dos plataformas, pero:

* **En Android no se puede escribir en `/storage/emulated/0/Download`** sin
  SAF: desde Android 10 (API 29) el almacenamiento por scopes prohíbe la
  escritura directa en el almacenamiento compartido, y `WRITE_EXTERNAL_STORAGE`
  está deprecada y no se concede en target SDK 33+. La única forma soportada
  de que el usuario elija un lugar es
  `Intent.ACTION_CREATE_DOCUMENT` (SAF), que devuelve un `content://` URI —
  **no una ruta de archivo**, y por lo tanto no lo maneja `FileSystem`.
* **En desktop** escribir en `/home/usuario/Escritorio` sí se puede con
  `FileSystem`, pero el usuario **espera un diálogo nativo** ("Guardar como…"),
  no una ruta escrita a dedo.
* **Compartir** (mandar el informe por mail/WhatsApp) es una operación del
  sistema, no del sistema de archivos.

De ahí el seam: `FileSystem` sigue siendo la forma de escribir **dentro de la
carpeta de la app**, y `FileExporter` agrega las tres operaciones de usuario.

### 2.2 Las interfaces (`kitoru.platform`, `:core` commonMain)

Archivo nuevo: `core/src/commonMain/kotlin/kitoru/platform/Export.kt`

```kotlin
package kitoru.platform

/** Formatos que el subsistema de informes sabe producir. */
enum class ExportFormat(
    val extension: String,
    val mimeType: String,
    val label: String,
) {
    CSV("csv", "text/csv", "CSV — planilla"),
    JSON("json", "application/json", "JSON — datos"),
    MARKDOWN("md", "text/markdown", "Markdown — informe"),
    PDF("pdf", "application/pdf", "PDF — imprimir"),
    /** Un bundle `.kitoru`: es un ZIP, se anuncia como application/zip. */
    BUNDLE("kitoru", "application/zip", "Kitoru — respaldo de la cátedra"),
}

/** Un archivo listo para exportar (ya renderizado en bytes). */
class ExportFile(
    /** Nombre sugerido, con extensión. Ej. "informe-actividad_IA-2026-1_20261021.csv". */
    val name: String,
    val format: ExportFormat,
    val bytes: ByteArray,
) {
    val mimeType: String get() = format.mimeType
    val sizeBytes: Int get() = bytes.size
}

/** Dónde quiere el usuario que quede el archivo. */
sealed interface SaveTarget {
    /** Carpeta privada de la app. Siempre funciona, en las dos plataformas. */
    data object AppData : SaveTarget

    /** Carpeta de documentos visible al usuario (`Documents/Kitoru`). */
    data object Documents : SaveTarget

    /** El usuario elige el destino con el selector del sistema. */
    data object UserChosen : SaveTarget

    /** Compartir con otras aplicaciones. */
    data object Share : SaveTarget
}

/** Resultado de una exportación. */
sealed interface ExportOutcome {
    /** Quedó escrito en disco. [path] es la ruta real; [displayPath] la que se muestra. */
    data class Saved(val path: FilePath, val displayPath: String) : ExportOutcome
    /** Se entregó a otra aplicación. [handler] es el nombre de la app receptora. */
    data class Shared(val handler: String) : ExportOutcome
    /** El usuario canceló el selector. */
    data object Cancelled : ExportOutcome
    /** No se pudo exportar. [message] está en español y es mostrable. */
    data class Failed(val message: String) : ExportOutcome
}

/** Escribe bytes en un destino opaco (una ruta o un content:// URI). */
fun interface ByteSink {
    suspend fun write(bytes: ByteArray)
}

/** Destino elegido por el usuario en el selector del sistema. */
class ExportDestination(
    /** Nombre final (puede diferir del sugerido si el usuario lo cambió). */
    val displayName: String,
    val sink: ByteSink,
)

/**
 * Ancla de plataforma para lo que necesita UI: elegir destino, compartir y
 * mostrar la carpeta.
 *
 * La implementa la app, no la biblioteca: en Android es la Activity (necesita
 * `registerForActivityResult` para SAF y `FileProvider` para compartir); en
 * desktop es la ventana (necesita el `FileDialog` nativo).
 */
interface ExportHost {
    /** Abre el selector del sistema. `null` = el usuario canceló. */
    suspend fun chooseDestination(suggestedName: String, mimeType: String): ExportDestination?

    /** Comparte un archivo ya escrito en disco. `false` = no se pudo. */
    suspend fun shareFile(path: FilePath, mimeType: String): Boolean

    /** Abre la carpeta/visor del archivo. `false` = no soportado. */
    suspend fun reveal(path: FilePath): Boolean
}

/** El seam: exporta un archivo ya renderizado a un destino. */
interface FileExporter {
    suspend fun export(file: ExportFile, target: SaveTarget): ExportOutcome

    /** Atajo para contenido de texto (CSV/JSON/Markdown). */
    suspend fun exportText(
        name: String,
        format: ExportFormat,
        text: String,
        target: SaveTarget,
    ): ExportOutcome = export(
        ExportFile(name, format, text.toByteArray(Charsets.UTF_8)),
        target,
    )
}

/** Crea el exportador de la plataforma. */
expect fun createFileExporter(fs: FileSystem, host: ExportHost): FileExporter
```

### 2.3 Los `expect/actual`

El `expect` vive en `commonMain`; cada plataforma tiene su `actual` **fino**,
porque la lógica común (escribir en la carpeta de la app, preparar el
compartido) es idéntica y va en `jvmCommonMain`:

```
core/src/commonMain/kotlin/kitoru/platform/Export.kt        (expect + tipos)
core/src/jvmCommonMain/kotlin/kitoru/platform/BaseFileExporter.kt   (lógica común)
core/src/desktopMain/kotlin/kitoru/platform/Export.desktop.kt (actual)
core/src/androidMain/kotlin/kitoru/platform/Export.android.kt (actual)
```

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/platform/BaseFileExporter.kt
internal abstract class BaseFileExporter(
    protected val fs: FileSystem,
    protected val host: ExportHost,
) : FileExporter {

    override suspend fun export(file: ExportFile, target: SaveTarget): ExportOutcome =
        when (target) {
            SaveTarget.AppData -> saveTo(KitoruPaths.exportsDir, file)
            SaveTarget.Documents -> saveTo(KitoruPaths.documentsDir, file)
            SaveTarget.UserChosen -> saveViaPicker(file)
            SaveTarget.Share -> shareFile(file)
        }

    private suspend fun saveTo(dir: FilePath, file: ExportFile): ExportOutcome = try {
        val path = dir.resolve(file.name)
        fs.createDirectories(dir)
        fs.writeBytes(path, file.bytes)          // ← ver §2.6: hace falta writeBytes
        ExportOutcome.Saved(path, path.value)
    } catch (e: Exception) {
        ExportOutcome.Failed("No se pudo escribir el archivo: ${e.message}")
    }

    private suspend fun saveViaPicker(file: ExportFile): ExportOutcome {
        val dest = host.chooseDestination(file.name, file.mimeType)
            ?: return ExportOutcome.Cancelled
        return try {
            dest.sink.write(file.bytes)
            // En Android `displayName` es el `content://` URI del SAF; en
            // desktop, la ruta final del diálogo. Sólo se usa para mostrar.
            ExportOutcome.Saved(FilePath(dest.displayName), dest.displayName)
        } catch (e: Exception) {
            ExportOutcome.Failed("No se pudo guardar en el destino elegido: ${e.message}")
        }
    }

    private suspend fun shareFile(file: ExportFile): ExportOutcome = try {
        val dir = KitoruPaths.sharedDir
        fs.createDirectories(dir)
        val path = dir.resolve(file.name)
        fs.writeBytes(path, file.bytes)
        if (host.shareFile(path, file.mimeType)) {
            ExportOutcome.Shared("la aplicación elegida")
        } else {
            ExportOutcome.Failed("Compartir no está disponible en esta plataforma.")
        }
    } catch (e: Exception) {
        ExportOutcome.Failed("No se pudo compartir el archivo: ${e.message}")
    }
}
```

```kotlin
// core/src/desktopMain/kotlin/kitoru/platform/Export.desktop.kt
actual fun createFileExporter(fs: FileSystem, host: ExportHost): FileExporter =
    DesktopFileExporter(fs, host)

/**
 * En escritorio "compartir" no existe como concepto del sistema: el informe
 * se guarda en Documentos/Kitoru y se abre la carpeta. El error es explícito
 * para que la UI no ofrezca una opción que no puede cumplir.
 */
private class DesktopFileExporter(fs: FileSystem, host: ExportHost) : BaseFileExporter(fs, host) {
    override suspend fun export(file: ExportFile, target: SaveTarget): ExportOutcome =
        if (target == SaveTarget.Share) {
            ExportOutcome.Failed(
                "En el escritorio no hay \"compartir\": usá \"Guardar en Documentos\" " +
                    "o \"Guardar como…\"."
            )
        } else super.export(file, target)
}
```

```kotlin
// core/src/androidMain/kotlin/kitoru/platform/Export.android.kt
actual fun createFileExporter(fs: FileSystem, host: ExportHost): FileExporter =
    AndroidFileExporter(fs, host)

/**
 * En Android, "compartir" es un intent (`ACTION_SEND`) con un URI de
 * `FileProvider`; lo resuelve el host, que es quien tiene la Activity.
 */
private class AndroidFileExporter(fs: FileSystem, host: ExportHost) : BaseFileExporter(fs, host)
```

> El `actual` de Android queda casi vacío **a propósito**: todo lo que
> necesita una `Activity` (SAF, intents, `FileProvider`) vive en el host, en
> el módulo `:android`. Si mañana el exportador necesita pedir permisos de
> almacenamiento, el seam ya está donde tiene que estar.

### 2.4 Los `ExportHost` de cada plataforma

**Desktop** — `desktop/src/main/kotlin/kitoru/desktop/export/DesktopExportHost.kt`

```kotlin
class DesktopExportHost(private val fs: FileSystem = systemFileSystem()) : ExportHost {
    override suspend fun chooseDestination(
        suggestedName: String,
        mimeType: String,
    ): ExportDestination? = withContext(Dispatchers.Swing) {
        // java.awt.FileDialog es el diálogo NATIVO del sistema (Windows/macOS).
        // Se corre en el EDT: Swing tiene su propio event loop, así que el
        // modal no congela la app.
        // El `owner` puede ser el AWT Frame de la ventana de Compose; con
        // `null` el diálogo es modal de la app.
        val dialog = FileDialog(null as Frame?, "Guardar como…", FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        val dir = dialog.directory
        val name = dialog.file
        if (dir == null || name == null) return@withContext null      // cancelado
        val target = FilePath(File(dir, name).absolutePath)
        ExportDestination(
            displayName = target.value,
            sink = ByteSink { bytes -> fs.writeBytes(target, bytes) },
        )
    }

    override suspend fun shareFile(path: FilePath, mimeType: String): Boolean = false

    override suspend fun reveal(path: FilePath): Boolean =
        withContext(Dispatchers.Swing) {
            Desktop.getDesktop().browse(File(path.value).parentFile)   // "mostrar en la carpeta"
        }
}
```

* `Dispatchers.Swing` viene de `kotlinx-coroutines-swing`, que ya está en
  `core/desktopMain`.
* `FileDialog` no muestra un filtro por extensión: el filtro lo pone la app
  (`.csv`, `.json`, `.md`, `.pdf`, `.kitoru`) y el nombre sugerido ya trae la
  extensión.
* Alternativa si `FileDialog` molesta en Linux: `JFileChooser` (Swing puro).
  Se deja una flag `useNativeDialog` en `KitoruPaths`/config.

**Android** — `android/src/main/kotlin/kitoru/android/export/AndroidExportHost.kt`

```kotlin
class AndroidExportHost(private val activity: ComponentActivity) : ExportHost {

    private val resolver: ContentResolver get() = activity.contentResolver

    // CreateDocument con MIME dinámico: el contrato estándar fija el tipo al
    // construirse, así que se hace uno propio.
    private val createDocument = activity.registerForActivityResult(CreateDocumentWithType()) { uri ->
        pending?.resume(uri)
        pending = null
    }
    private var pending: CancellableContinuation<Uri?>? = null

    override suspend fun chooseDestination(
        suggestedName: String,
        mimeType: String,
    ): ExportDestination? = suspendCancellableCoroutine { cont ->
        pending = cont
        createDocument.launch(suggestedName, mimeType)
    }.let { uri ->
        uri ?: return null
        ExportDestination(
            displayName = uri.lastPathSegment ?: suggestedName,
            sink = ByteSink { bytes ->
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: error("No se pudo abrir el destino elegido.")
                }
            },
        )
    }

    override suspend fun shareFile(path: FilePath, mimeType: String): Boolean {
        val uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", File(path.value),
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Compartir informe"))
        return true
    }

    override suspend fun reveal(path: FilePath): Boolean = false
}
```

Requisitos de Android que hay que configurar:

* `res/xml/file_paths.xml`:
  ```xml
  <paths>
      <cache-path name="compartidos" path="shared/" />
      <files-path name="exportes" path="exports/" />
      <external-files-path name="documentos" path="Documents/" />
  </paths>
  ```
* `<provider android:name="androidx.core.content.FileProvider" … />` en el
  manifest.
* **No hace falta ningún permiso de almacenamiento** para
  `ACTION_CREATE_DOCUMENT`: el usuario elige el archivo y la app escribe ahí.
  (Es una ventaja real de SAF frente a `WRITE_EXTERNAL_STORAGE`.)
* `registerForActivityResult` tiene que correr **antes** de que la Activity
  pase a `STARTED`: el host se crea en `onCreate` (o en el `setContent` de
  Compose), no en un click.

### 2.5 `KitoruPaths`: la carpeta de la app y la de documentos

`core/src/commonMain/kotlin/kitoru/platform/KitoruPaths.kt` necesita un
agregado (es un cambio chico y aditivo):

```kotlin
object KitoruPaths {
    var dataDir: FilePath = FilePath(".")
        private set
    /** Carpeta visible al usuario: Documents/Kitoru. En Android, la externa de la app. */
    var documentsDir: FilePath = FilePath(".")
        private set
    /** Carpeta de reportes exportados (privada de la app). */
    val exportsDir: FilePath get() = dataDir.resolve("exports")
    /** Carpeta temporal para compartir (la que expone el FileProvider). */
    val sharedDir: FilePath get() = cacheDir.resolve("shared")
    // … configDir / coursesDir / cacheDir como ya están
    fun setDataDir(path: FilePath) { dataDir = path }
    fun setDocumentsDir(path: FilePath) { documentsDir = path }
}
```

Arranque por plataforma:

| | `dataDir` | `documentsDir` |
|---|---|---|
| Desktop | `~/.kitoru` | `~/Documents/Kitoru` |
| Android | `context.filesDir/kitoru` | `context.getExternalFilesDir(DIRECTORY_DOCUMENTS)/Kitoru` (si es `null`, cae a `dataDir`) |

La carpeta externa de la app en Android (`Android/data/<paquete>/files/Documents`)
**no necesita permisos** en ninguna versión y es visible desde el explorador de
archivos desde Android 11. Es el mejor "punto de salida" sin SAF.

### 2.6 `FileSystem` necesita `writeBytes` / `readBytes`

`FileSystem` sólo tiene `readText`/`writeText`. El PDF y el bundle `.kitoru`
son **binarios**. Cambio propuesto (aditivo, hay que actualizar `JvmFileSystem`
y los fakes de test):

```kotlin
interface FileSystem {
    // … lo que ya está …
    /** Escribe bytes crudos (PDF, ZIP). */
    fun writeBytes(path: FilePath, bytes: ByteArray)
    /** Lee bytes crudos. */
    fun readBytes(path: FilePath): ByteArray
}
```

Alternativa si no se quiere tocar la interfaz: una subinterfaz
`BinaryFileSystem : FileSystem` implementada por el mismo `JvmFileSystem`.
Es peor: obliga a castear en cada uso. **Se cambia la interfaz.**

### 2.7 Historial de exportaciones

Cada exportación se anota en `config/exports.json` (con `JsonListStore`, como
el resto):

```kotlin
@Serializable
data class ExportRecord(
    val id: String,
    val courseId: String,
    val reportKind: String,     // ReportKind.id
    val fileName: String,
    val format: String,         // ExportFormat.name
    val createdAt: Long,
    val sizeBytes: Long,
    val path: String,           // ruta donde quedó
    val shared: Boolean = false,
)
```

Sirve para: volver a abrir o volver a compartir un informe del lunes pasado,
y para que el resumen semanal programado aparezca en la misma lista.
Es de costo casi cero y resuelve un dolor real ("¿dónde quedó el PDF que
generé?").

### 2.8 El flujo en la UI

```
[ Exportar ▾ ]  ──►  ExportDialog
                        ├─ Formato:  ( ) CSV  ( ) JSON  ( ) Markdown  ( ) PDF
                        ├─ Separador CSV:  (•) ;   ( ) ,
                        ├─ Destino:  ( ) Carpeta de la app
                        │            ( ) Documentos
                        │            ( ) Guardar como…      ← SAF / diálogo nativo
                        │            ( ) Compartir            ← sólo Android
                        └─ [ Exportar ]  →  ExportOutcome
                                          ├─ Saved    → "Guardado en …"  [+ Compartir] [+ Abrir carpeta]
                                          ├─ Shared   → "Compartido con …"
                                          ├─ Cancelled→ no se dice nada
                                          └─ Failed   → snackbar con el mensaje
```

En desktop la opción "Compartir" **no aparece** (no hay a quién compartirla);
en Android "Guardar en Documentos" y "Compartir" son las dos salidas que el
docente usa desde el celular.

---

## 3. El modelo de informe (IR) y los renderizadores

Módulo nuevo: **`:reports`** (`kitoru.report`), que depende de `:core` y de
`:backend` (para el verificador de citas y el índice BM25).

```
reports/src/commonMain/kotlin/kitoru/report/
    Report.kt              IR: Report, ReportTable, ReportColumn, ReportCell, ColumnType
    ReportFilter.kt        período + alcance
    ReportBuilder.kt       construcción del IR desde el dominio
    ActivityReport.kt
    QualityReport.kt
    MaterialUsageReport.kt
    StudentReport.kt
    CourseReport.kt
    StudyTimeEstimator.kt
    TopicsAnalyzer.kt      "qué se pregunta más"
    MaterialResolver.kt    etiqueta de fuente → Material
    render/ReportRenderer.kt
    render/CsvRenderer.kt
    render/JsonRenderer.kt
    render/MarkdownRenderer.kt
reports/src/jvmCommonMain/kotlin/kitoru/report/
    render/PdfRenderer.kt         (hito 2: se suma pdfbox-android)
    bundle/ZipBundleWriter.kt
    bundle/ZipBundleReader.kt     (hito 2)
```

### 3.1 El IR

```kotlin
package kitoru.report

@Serializable
enum class ReportKind(val id: String, val label: String, val fileName: String) {
    ACTIVITY("actividad", "Informe de actividad", "informe-actividad"),
    QUALITY("calidad", "Informe de calidad", "informe-calidad"),
    MATERIALS("materiales", "Informe de uso de materiales", "informe-uso-materiales"),
    STUDENT("alumno", "Informe por alumno", "informe-alumno"),
    COURSE("catedra", "Informe de cátedra", "informe-catedra"),
    WEEKLY("semanal", "Resumen semanal", "resumen-semanal"),
}

@Serializable
enum class ColumnType { TEXT, NUMBER, PERCENT, DURATION, DATE, BOOL }

/**
 * Una celda tiene dos caras: `text` (formateada, para leer en Markdown/PDF)
 * y `number` (cruda, para CSV/JSON). `number == null` significa "sin dato",
 * que es distinto de cero.
 */
@Serializable
data class ReportCell(
    val text: String = "",
    val number: Double? = null,
) {
    companion object {
        fun of(value: String) = ReportCell(text = value)
        fun num(value: Double, formatted: String) = ReportCell(text = formatted, number = value)
        fun pct(fraction: Double, digits: Int = 1): ReportCell =
            num(fraction, "${fmt(fraction * 100, digits)} %")
        fun dur(seconds: Long): ReportCell = num(seconds.toDouble(), humanDuration(seconds))
        val EMPTY = ReportCell(text = "—")
    }
}

@Serializable
data class ReportColumn(
    val key: String,             // snake_case, sin acentos: "tasa_citas_respaldo"
    val label: String,           // "Tasa de citas respaldadas"
    val type: ColumnType = ColumnType.TEXT,
    val unit: String = "",       // "s", "%", "h"
    val total: Boolean = false,  // ¿participa en la fila de totales?
)

@Serializable
data class ReportRow(val cells: List<ReportCell>)

@Serializable
data class ReportTable(
    val id: String,
    val title: String,
    val columns: List<ReportColumn>,
    val rows: List<ReportRow>,
    val totalRow: ReportRow? = null,
    val note: String = "",
)

/** Alcance del informe. `kind` es "course" o "student". */
@Serializable
data class ReportScope(
    val kind: String,
    val userId: String = "",
    val displayName: String = "",
)

/** Cómo se repartió la visibilidad (para que el informe sea honesto). */
@Serializable
data class VisibilitySummary(
    val full: Int = 0,
    val metricsOnly: Int = 0,
    val none: Int = 0,
) {
    val total: Int get() = full + metricsOnly + none
    val covered: Int get() = full + metricsOnly
    val hidden: Int get() = none
}

@Serializable
data class Report(
    /** Versión del formato; para reimportar informes viejos. */
    val schemaVersion: Int = 1,
    val kind: ReportKind,
    val title: String,
    val courseId: String,
    val courseCode: String,
    val courseName: String,
    val term: String = "",
    val generatedAt: Long,
    val periodFrom: Long? = null,
    val periodTo: Long? = null,
    val scope: ReportScope,
    val visibility: VisibilitySummary = VisibilitySummary(),
    val tables: List<ReportTable> = emptyList(),
    /** Notas del docente (texto libre que se agrega al informe). */
    val notes: List<String> = emptyList(),
    /** Cómo se calcularon los números (siempre al pie). */
    val methodology: List<String> = emptyList(),
    /** Advertencias (visibilidad parcial, estimaciones, etc.). */
    val warnings: List<String> = emptyList(),
)
```

Helpers de formato (en `kitoru.report`, sin dependencias): `fmt(doble, decimales)`
— con coma decimal y punto de miles, estilo español— y `humanDuration(segundos)`
— `"45 s"`, `"3,2 min"`, `"1,4 h"`. `ReportCell.EMPTY` es la celda "sin dato"
(guion largo) que se usa cuando no hay citas, no un cero.

### 3.2 El contrato de renderizado

```kotlin
package kitoru.report.render

interface ReportRenderer {
    val format: ExportFormat
    /** Renderiza el informe. Función pura: mismos datos → mismos bytes. */
    fun render(report: Report): ByteArray
}
```

* `CsvRenderer(format = ExportFormat.CSV)` — una tabla por archivo (ver §1.2).
  Si el informe tiene varias tablas, `render` devuelve la **tabla principal**
  y `renderTable(report, table)` permite bajar cada una por separado.
* `JsonRenderer` — `KitoruJson.encodeToString(Report.serializer(), report)`.
* `MarkdownRenderer` — GFM con la cabecera, las tablas, la metodología y las
  advertencias.
* `PdfRenderer` — desktopMain (hito 1) / jvmCommonMain (hito 2).

**Determinismo**: los renderizadores no leen el reloj. La fecha de generación
va en `Report.generatedAt` y la inyecta el builder (`now: Long = nowMillis()`),
así que los tests son estables.

### 3.3 El filtro de período y alcance

```kotlin
data class ReportFilter(
    /** null = desde el principio. */
    val from: Long? = null,
    /** null = hasta ahora. */
    val to: Long? = null,
    /** null = toda la cátedra. */
    val studentId: String? = null,
)
```

La UI ofrece: *Todo el período* / *Últimos 7 días* / *Últimos 30 días* /
*Este mes* / *Personalizado*. El filtro se aplica a los **mensajes** por
`createdAt`; los hilos se incluyen si tienen al menos un mensaje en rango.

---

## 4. El contenido de cada informe

Regla previa que aplica a todos: **los datos salen de los repositorios**
(`CourseRepository`, `EnrollmentRepository`, `MaterialRepository`,
`ThreadRepository`, `MessageRepository`) y del
`AnalyticsAnalyzer.analyze(...)` ya existente, que se reutiliza tal cual para
los agregados. Lo que agrega este subsistema es el **desglose** (por material,
por motivo de fallo) y la **estimación de horas**.

### 4.1 Informe de actividad (`ACTIVITY`)

**Alcance**: la cátedra (o un alumno). Responde: *¿quién usó la herramienta,
cuánto, y con qué ritmo?*

**Tabla 1 — Resumen por alumno** (una fila por alumno visible):

| Columna (`key`) | Etiqueta | Tipo | Origen |
|---|---|---|---|
| `alumno` | Alumno | TEXT | `Enrollment.displayName` |
| `rol` | Rol | TEXT | `CourseRole.label` |
| `conversaciones` | Conversaciones | NUMBER | `StudentAnalytics.threads` |
| `mensajes` | Mensajes | NUMBER | `StudentAnalytics.messages` |
| `respuestas` | Respuestas | NUMBER | `StudentAnalytics.replies` |
| `dias_activos` | Días activos | NUMBER | `StudentAnalytics.activeDays` |
| `ultima_actividad` | Última actividad | DATE | `StudentAnalytics.lastActivity` |
| `latencia_media_s` | Latencia media (s) | DURATION | `StudentAnalytics.avgLatencyMs` |
| `tokens_entrada` | Tokens entrada | NUMBER | `StudentAnalytics.tokensIn` |
| `tokens_salida` | Tokens salida | NUMBER | `StudentAnalytics.tokensOut` |
| `citas_verificadas` | Citas verificadas | NUMBER | `StudentAnalytics.citationsVerified` |
| `citas_sin_respaldo` | Citas sin respaldo | NUMBER | `StudentAnalytics.citationsUnsupported` |
| `citas_inexistentes` | Citas inexistentes | NUMBER | `StudentAnalytics.citationsUnknown` |
| `tasa_citas_respaldo` | Tasa de respaldo | PERCENT | `citationSupportRate` |
| `respuestas_sin_respaldo_pct` | Respuestas sin respaldo | PERCENT | `ungroundedAnswerRate` |
| `herramientas` | Herramientas (llamadas/fallos) | TEXT | `toolCalls` / `toolFailures` |
| `horas_estimadas` | Horas de estudio (est.) | DURATION | `StudyTimeEstimator` |
| `sesiones` | Sesiones | NUMBER | `StudyTimeEstimator` |

Fila de totales: mensajes, respuestas, citas, herramientas y **horas estimadas
totales**.

> Nota metodológica de la tabla: *"Un alumno con visibilidad `Oculto` no
> aparece en ningún informe. Un alumno con `Solo métricas` aparece en los
> números, pero ni su texto ni sus conversaciones."*

**Tabla 2 — Actividad por día**: `día` (fecha en UTC, la misma agrupación que
usa la app: `AnalyticsAnalyzer.dayStart`), `alumnos_activos`, `mensajes`,
`respuestas`, `horas_estimadas_dia`.

**Tabla 3 — Alumnos sin actividad** (inscritos que nunca escribieron):
`alumno`, `inscrito_desde`, `dias_desde_inscripcion`. Es un dato que a la
cátedra le importa: el alumno que se anotó y nunca usó la herramienta.

### 4.2 Informe de calidad (`QUALITY`)

**Alcance**: la cátedra. Responde: *¿el modelo responde con respaldo? ¿de
dónde salen los fallos?*

**Tabla 1 — Resumen de calidad**

| Columna | Valor |
|---|---|
| `citas_totales` | `CourseAnalytics.citationsVerified + Unsupported + Unknown` |
| `citas_verificadas` | `citationsVerified` |
| `citas_sin_respaldo` | `citationsUnsupported` |
| `citas_inexistentes` | `citationsUnknown` |
| `tasa_respaldo_global` | `CourseAnalytics.citationSupportRate` |
| `respuestas_con_cita_sin_respaldo` | `Σ StudentAnalytics.answersWithUnsupportedCitations` |
| `tasa_respuestas_sin_respaldo` | respuestas con fallo / respuestas totales |
| `respuestas_sin_citas` | respuestas del asistente que no citaron ninguna fuente (se cuenta sobre el texto de la respuesta; ver §10.1) |

**Tabla 2 — Calidad por material**: `material`, `citas_totales`,
`citas_verificadas`, `citas_sin_respaldo`, `citas_inexistentes`,
`tasa_respaldo`.

**Tabla 3 — Fallos por motivo de fallo**: `motivo` (etiqueta en español),
`cantidad`, `porcentaje_sobre_fallos`, `materiales_mas_afectados`.

Los motivos salen de un campo **nuevo** en el dominio (ver §10):

| Motivo | Significado |
|---|---|
| `FUENTE_INEXISTENTE` | La respuesta citó `[3]` y sólo se habían recuperado 2 fuentes. |
| `FUENTE_VACIA` | La fuente citada no tiene texto (material sin capa de texto). |
| `DETALLE_INVENTADO` | La cita menciona un nombre propio o un número que **no está en la fuente** (el caso grave: el modelo inventó). |
| `NO_APARECE` | El fragmento citado no aparece en la fuente (cobertura de palabras < 50 %). |
| `SIN_CITAS` | La respuesta no tuvo ninguna cita. |

**Tabla 4 — Ejemplos de respuestas sin respaldo** (sólo de alumnos `FULL`):
`alumno`, `conversacion`, `material`, `fragmento_citado` (recortado a 300
caracteres), `motivo`. Es la tabla que le sirve al docente para ver *qué
inventó el modelo*: las 10-20 peores respuestas, no un promedio.

> Esta tabla **no incluye respuestas de alumnos `METRICS_ONLY`**: son sus
> conversaciones y el docente no tiene permiso para verlas.

### 4.3 Informe de uso de materiales (`MATERIALS`)

**Alcance**: la cátedra. Responde: *¿el corpus está bien? ¿qué se usa?*

Este es el informe con la alerta más importante del producto: **un material
que nadie consulta está mal indexado o no se usa**, y eso es un problema de la
cátedra, no del alumno.

**Tabla 1 — Uso por material**:

| Columna | Significado |
|---|---|
| `material` | Título (o ruta si no tiene título). |
| `tipo` | `MaterialKind.label` (Documento, Diapositivas, Apuntes, Enlace, Otro). |
| `visible` | Si el alumno puede verlo. |
| `texto_indexado` | Sí/No: ¿tiene `extractedText` no vacío? Si no, **nunca puede ser recuperado**. |
| `tamano_kb` | `Material.sizeBytes`. |
| `subido` | `Material.uploadedAt`. |
| `veces_recuperado` | Cuántas veces el material entró en el top-K de alguna respuesta. |
| `veces_citado` | Cuántas veces el modelo **lo usó** (cita `[n]` que apunta a él). |
| `pct_respuestas_que_lo_citan` | `veces_citado` / respuestas del asistente. |
| `alumnos_que_lo_usaron` | Cuántos alumnos distintos lo consultaron. |
| `tasa_respaldo_citas` | De las citas que apuntan a este material, cuántas están respaldadas. |
| `diagnostico` | Ver abajo. |

> **Definición exacta de los dos contadores** (son distintos y el informe los
> muestra siempre juntos):
> * `veces_recuperado` = cuántas veces la etiqueta del material aparece en
>   `ChatMessage.sources` de una respuesta, es decir, cuántas veces **entró en
>   el top-K** que se le pasó al modelo.
> * `veces_citado` = cuántas veces la respuesta usó un marcador `[n]` que
>   apunta a ese material, es decir, cuántas veces el modelo **se apoyó en él**.
>
> Un material con `veces_recuperado = 0` no llegó a ser contexto; uno con
> `veces_recuperado > 0` y `veces_citado = 0` estuvo en el contexto y el
> modelo lo ignoró. La diferencia es la que separa "problema de indexación"
> de "material redundante".

**Tabla 2 — Materiales nunca citados** (la tabla que se destaca en la UI):
sólo los materiales con `veces_citado == 0`, con su diagnóstico.

**Tabla 3 — Materiales nunca recuperados**: `veces_recuperado == 0`. Son
distintos de los nunca citados: un material nunca recuperado **no entró nunca
en la ventana de contexto** (o no estaba visible, o no tenía texto, o el BM25
no lo rankeó).

**Diagnóstico** (una frase por material, la que el docente puede actuar):

| Situación | Diagnóstico |
|---|---|
| `extractedText` vacío | "No indexado: el archivo no tiene capa de texto extraíble. Volvé a subirlo o pasalo a PDF con texto." |
| `visible == false` | "Oculto a los alumnos: nunca se recuperó (esperado)." |
| `veces_recuperado == 0` y tiene texto | "Nunca recuperado: no apareció en el top-K. Revisá el título y las etiquetas, o subí el material más completo." |
| `veces_recuperado > 0` y `veces_citado == 0` | "Recuperado pero nunca citado: el modelo lo tuvo en contexto y no lo usó. Puede ser que el material sea redundante o poco pertinente." |
| `veces_citado > 0` y `tasa_respaldo_citas < 0.5` | "Citado pero sin respaldo: el modelo se apoya en este material y lo malinterpreta. Revisalo." |
| `warning` no vacío | Se agrega el `Material.warning` ("PDF sin capa de texto", etc.). |

> Nota: `veces_recuperado` y `veces_citado` son números distintos y el
> informe los muestra siempre juntos. La diferencia entre "nunca recuperado" y
> "recuperado y nunca citado" es exactamente el diagnóstico que le sirve al
> docente para arreglar el corpus.

**Tabla 4 — Resumen**: total de materiales, indexables, recuperados, citados,
nunca citados, nunca recuperados, y los 5 materiales más citados.

### 4.4 Informe por alumno (`STUDENT`)

**Alcance**: un alumno. Responde: *¿qué studied este alumno?*

**Tabla 1 — Identificación y visibilidad**: `alumno`, `rol`, `inscrito_desde`,
`visibilidad` (etiqueta + descripción de `ChatVisibility`), `conversaciones`,
`ultima_actividad`, `modelos_usados`.

> Si la visibilidad es `METRICS_ONLY`, la tabla 1 incluye una advertencia
> explícita: *"El texto de las conversaciones de este alumno no se incluye
> porque su inscripción dice 'Solo métricas'."*

**Tabla 2 — Métricas**: las mismas columnas que §4.1 (mensajes, respuestas,
días activos, latencia, tokens, citas, tasas, herramientas, horas estimadas,
sesiones).

**Tabla 3 — Conversaciones** (una fila por hilo):

| Columna | Nota |
|---|---|
| `titulo` | `ChatThread.title` — **sólo si la visibilidad es FULL** |
| `modelo` | `ChatThread.modelLabel` (y `effectiveModel.mode`: REMOTE/LOCAL) |
| `etiquetas` | `ChatThread.tags` — sólo FULL |
| `inicio` / `ultimo_mensaje` | `createdAt` / `updatedAt` |
| `mensajes` | `ChatMessageRepository.listForThread(threadId).size` |
| `fuentes_usadas` | nº de fuentes distintas citadas (FULL) o nº de fuentes recuperadas (METRICS_ONLY) |
| `citas_verificadas` | `CitationStats` agregadas del hilo (disponible en ambos casos: es métrica, no texto) |

**Tabla 4 — Transcripción** (sólo `FULL`): una fila por mensaje con
`fecha`, `rol` ("Alumno"/"IA"), `texto` (recortado a 2000 caracteres en
Markdown/PDF; completo en JSON/CSV), `fuentes` (las etiquetas de las fuentes
recuperadas), `citas` (verificadas/sin respaldo) y `latencia`.

**Tabla 5 — Materiales consultados** (por ese alumno): material, veces
recuperado, veces citado.

**Tabla 6 — Actividad por día** (por ese alumno).

### 4.5 Informe de cátedra (`COURSE`)

**Alcance**: la cátedra. Es el que va al departamento. Responde: *¿qué se
usó, cuánto y de qué?*

**Tabla 1 — Identificación**: `codigo`, `nombre`, `cursada`, `descripcion`,
`periodo_analizado` (desde/hasta), `generado`, `modelo_base`,
`top_k`, `chunk_size`, `analytics_habilitado`.

**Tabla 2 — Uso**:
`alumnos_inscritos`, `alumnos_activos`, `pct_activos`, `mensajes`,
`respuestas`, `conversaciones`, `media_mensajes_por_alumno`,
`tokens_totales`, `latencia_media_s`, `herramientas_llamadas`,
`herramientas_fallos`.

**Tabla 3 — Qué modelo se usó** (el "qué se chat se usa"):

| Columna | Nota |
|---|---|
| `modelo` | `ChatThread.effectiveModel.modelLabel` (o `hfFile` si es local). |
| `modo` | `REMOTE` (servidor de la cátedra) o `LOCAL` (IA en el dispositivo del alumno). |
| `alumnos` | Alumnos distintos que lo usaron. |
| `conversaciones` / `mensajes` | Volumen. |
| `porcentaje` | % de las conversaciones. |

> El desglose `REMOTE`/`LOCAL` le sirve al departamento para saber cuánta
> actividad se corresponde al servidor de la institución y cuánta ocurrió
> en equipos personales. No implica ningún juicio: es un dato de cobertura.

**Tabla 4 — Horas de estudio estimadas**: `horas_totales_catedra`,
`horas_media_por_alumno_activo`, `horas_promedio_por_sesion`, y las 10 filas
de alumnos con más horas (nombre + horas + sesiones).

**Tabla 5 — Temas más preguntados** (el "qué se preguntan más"):

* `terminos_frecuentes`: los términos más discriminantes de las primeras
  preguntas de cada conversación, con su frecuencia. Se calcula con
  tokenización (se reusa `Bm25Index.tokenize`, que ya normaliza y quita
  diacríticos) y una stoplist del español ampliada.
* `temas`: agrupación de las preguntas por similitud BM25 (índice de las
  preguntas + vecinos), con **hasta 5 preguntas representativas** por grupo y
  los 3 términos que lo nombran. Ejemplo:

  | tema | preguntas representativas | alumnos |
  |---|---|---|
  | "backpropagation, gradiente, pesos" | "¿Cómo funciona la retropropagación?", "¿Qué es el gradiente?", "¿Cómo se actualizan los pesos?" | 21 |
  | "transformer, atención, tokens" | "¿Qué es un transformer?", "¿Cómo funciona la atención multi-cabeza?" | 17 |

* `etiquetas_usadas`: conteo de los `ChatThread.tags` que puso el propio
  alumno ("duda de parcial", "práctica 3").

> **Estas preguntas sólo se calculan con alumnos `FULL`** (el texto de la
> pregunta es texto de la conversación). Con `METRICS_ONLY` la tabla de temas
> sale vacía y el informe lo dice: *"Sin datos de temas: los alumnos con
> visibilidad 'Solo métricas' no comparten el texto de sus preguntas."*

**Tabla 6 — Calidad (resumen)**: la tasa de respaldo global, las citas por
tipo y el puntero: *"El detalle por material y por motivo está en el
informe de calidad."*

**Tabla 7 — Materiales (resumen)**: los 5 más citados, el número de
materiales **nunca citados** (con la lista en el informe de uso de materiales)
y el número de materiales sin texto indexado.

### 4.6 Reglas de visibilidad (aplican a todos los informes)

| Visibilidad | Aparece en agregados | Texto / títulos / preguntas | Citas y calidad |
|---|---|---|---|
| `FULL` | Sí | Sí | Sí |
| `METRICS_ONLY` | Sí | **No** | Sí (son métricas) |
| `NONE` | **No** (ni en el conteo) | No | No |

* Los alumnos `NONE` no aparecen **ni siquiera** en la lista de "alumnos sin
  actividad" ni en la lista de nombres. El informe muestra **sólo el
  número** de alumnos ocultos, para que el docente sepa que los datos están
  incompletos: *"3 de 45 alumnos no comparten sus datos con la cátedra."*
* Los títulos de conversación y las etiquetas se tratan como **texto**: sólo
  se incluyen con `FULL`. Es la decisión conservadora: un título escrito por
  el alumno ("duda del parcial 2 sobre backprop") es contenido de la
  conversación.
* `ChatVisibility.NONE` es la regla que se respeta sin excepciones: si un
  alumno pidió que no se lo miren, no aparece en ningún número que lo
  identifique.
* El bundle `.kitoru` (§6) incluye los chats: **su exportación exige una
  confirmación explícita** ("esto contiene datos personales de 42 alumnos").

---

## 5. Estimación de las horas de estudio

### 5.1 Qué se puede medir y qué no

No hay un cronómetro: la app no sabe cuánto tiempo el alumno estuvo
"estudiando" con la pantalla abierta. Pero hay dos señales:

1. **El contenido**: cuánto escribió el alumno y cuánto le respondieron.
2. **Los silencios**: el tiempo entre una respuesta de la IA y la siguiente
   pregunta del alumno.

La estimación combina las dos. **Es una estimación, no una medición**: el
informe la etiqueta siempre como *"horas estimadas"* y al pie explica el
modelo, para que nadie la presente como un dato duro.

### 5.2 La fórmula

`StudyTimeEstimator` (`kitoru.report`), por conversación, con los mensajes
ordenados por `createdAt`:

```
para cada respuesta de la IA A con una pregunta U previa:

    lectura(A)     = max(15 s , palabras(A) / 220 ppm × 60)
    escritura(U)   = max(10 s , palabras(U) / 40 ppm  × 60)

    (la lectura y la escritura se suman una vez por mensaje)

para cada par consecutivo (respuesta A_k , pregunta U_{k+1}) de la misma
conversación:

    gap      = (U_{k+1}.createdAt − A_k.createdAt) / 1000
    reflexión = max(0 , min(gap , 20 min) − lectura(A_k) − escritura(U_{k+1}))

total del alumno = Σ(lectura + escritura + reflexión)

sesiones         = 1 + nº de gaps > 30 min
tope diario      = 6 h por día (día UTC), se recorta el excedente
```

Constantes (todas en un solo objeto, con nombre y comentario):

| Constante | Valor | Por qué |
|---|---|---|
| `LECTURA_PPM` | 220 palabras/min | Velocidad de lectura técnica (con material de cátedra, no una novela). |
| `ESCRITURA_PPM` | 40 palabras/min | Escribir una pregunta en teclado, con las correcciones. |
| `MIN_LECTURA_S` | 15 s | Aunque la respuesta sea de una línea, leerla lleva un momento. |
| `MIN_ESCRITURA_S` | 10 s | Escribir una pregunta lleva al menos eso. |
| `GAP_MAX_S` | 20 min | Un silencio mayor es "el alumno se fue", no "el alumno estudió". |
| `SESION_MIN_GAP_S` | 30 min | Corte de sesión. |
| `TOPE_HORAS_DIA` | 6 h | Tope duro por día para que una sesión de madrugada no infle el número. |

**Por qué no se cuenta la latencia del modelo**: el tiempo que la IA tarda
en responder es tiempo de máquina, no de estudio. Va en el informe de
actividad (latencia media), no en las horas.

**Por qué la reflexión se resta**: el hueco entre la respuesta y la siguiente
pregunta ya contiene el tiempo de leer y el de escribir. Sumarlos otra vez
sería contarlos dos veces; la resta deja sólo el "tiempo de pensar".

### 5.3 Ejemplo trabajado

Conversación:

| # | Rol | Texto | createdAt |
|---|---|---|---|
| 1 | alumno | "explicame que es un embedding" (3 palabras) | 10:00:00 |
| 2 | IA | respuesta de 120 palabras | 10:00:20 |
| 3 | alumno | "y un modelo transformer?" (4 palabras) | 10:03:00 |
| 4 | IA | respuesta de 80 palabras | 10:03:15 |

```
lectura(A2)   = max(15, 120/220×60) = 32,7 s
escritura(U1) = max(10,   3/40×60) = 10 s
escritura(U3) = max(10,   4/40×60) = 10 s
lectura(A4)   = max(15,  80/220×60) = 21,8 s
gap(A2→U3)    = 160 s
reflexión     = max(0, min(160, 1200) − 32,7 − 10) = 117,3 s

total = 32,7 + 10 + 10 + 21,8 + 117,3 = 191,8 s = 3,2 min = 0,053 h
sesiones = 1
```

### 5.4 Casos borde

| Caso | Qué hace |
|---|---|
| `createdAt == 0` (mensaje sin fecha) | No entra en el cálculo de silencios; sí aporta lectura/escritura (mínimo). |
| Dos mensajes con el mismo `createdAt` | `gap = 0` → reflexión 0; siguen contando lectura y escritura. |
| Pregunta sin respuesta (el modelo falló) | Sólo la escritura. |
| Respuesta sin pregunta previa (huérfana) | Sólo la lectura. |
| Alumno con `NONE` | No se estima nada (no hay datos que mostrar). |
| Un solo mensaje en toda la cursada | 1 sesión, horas = lectura/escritura del mensaje. |
| Sesión de madrugada de 5 h | El gap se corta a 20 min y el día a 6 h. |

### 5.5 Cómo se presenta

* En la tabla: columna `horas_estimadas` con formato `1,2 h`.
* En la tabla de totales: `horas_estimadas_catedra`.
* En el informe por alumno, una nota: *"Estimación a partir del volumen de
  mensajes y de los intervalos entre mensajes. No mide tiempo real de
  pantalla."*
* En la metodología del informe (siempre al pie) va la fórmula y las
  constantes. **Un informe de horas sin su metodología no se publica.**

---

## 6. Exportación masiva: el bundle `.kitoru`

### 6.1 Qué es

Un **único archivo** con todo lo de una cátedra (o de todas), portable,
versionado y verificable. Es el "llevá tu cátedra a otra máquina" y el
"respaldo antes de tocar algo".

**Formato: ZIP.** Extensión `.kitoru`. Sirve porque:
* `java.util.zip` está en `jvmCommonMain` (desktop **y** Android) → sin
  dependencias.
* Un ZIP se abre con 7-Zip si hace falta (el `.kitoru` es un ZIP renombrado).

### 6.2 Estructura

```
catedra_IA-2026-1_20261021.kitoru
├── manifest.json
├── courses.json
├── enrollments.json
├── materials.json
├── threads.json
├── messages.json
└── materials/                  (opcional, si el docente incluye los archivos)
    ├── a3f9c1__Unidad 1 - Introducción.pdf
    └── b71d02__Apuntes de la clase 3.md
```

`manifest.json`:

```json
{
  "format": "kitoru-bundle",
  "version": 1,
  "createdAt": 1750464000000,
  "app": { "name": "Kitoru", "version": "1.0.0" },
  "scope": {
    "kind": "course",
    "courseId": "c-ia-2026-1",
    "courseCode": "IA-2026-1",
    "courseName": "Inteligencia Artificial"
  },
  "counts": {
    "courses": 1, "enrollments": 42, "materials": 12,
    "threads": 340, "messages": 5180, "materialFiles": 12
  },
  "files": [
    { "name": "courses.json",   "bytes": 1840, "sha256": "9f2c…" },
    { "name": "enrollments.json","bytes": 9210, "sha256": "4a71…" }
  ],
  "options": {
    "includeMaterialFiles": true,
    "includeMessageText": true,
    "anonymized": false
  },
  "privacy": {
    "enrollmentsIncluded": true,
    "messageTextIncluded": true,
    "hiddenStudents": 3
  }
}
```

* `version` es la **versión del formato del bundle** (empieza en 1). El
  importador acepta `version <= 1` y rechaza el resto con un mensaje claro:
  > "Este respaldo fue generado por una versión más nueva de Kitoru (formato 2).
  > Actualizá la aplicación para abrirlo."
* `sha256` por archivo: el importador verifica la integridad y avisa
  *"El archivo `messages.json` está corrupto (checksum no coincide)."*
* `options.anonymized`: si el docente marca "sin datos de alumnos", los
  `enrollments.json` se guardan con `displayName = "Alumno 001"` y los
  `userId` se hashean (`sha256(userId)[0..8]`). Los `threads`/`messages` se
  mantienen (los `authorId` se hashean igual) para que el análisis siga
  funcionando, pero no se puede identificar a nadie. **Es el modo para
  compartir un caso de estudio con el departamento.**

### 6.3 El flujo de exportación

```
Cátedra ▸ ⋯ ▸ "Exportar cátedra"
   └─► ExportBundleDialog
         ├─ Incluir archivos de material      ( ) sí  (•) no
         ├─ Incluir texto de las conversaciones (•) sí  ( ) no
         ├─ Anonimizar alumnos                ( ) no  ( ) sí
         └─ [ Exportar ]  →  .kitoru  →  FileExporter (AppData / Documents / SAF / compartir)
```

El diálogo muestra **el tamaño estimado** antes de exportar
("≈ 48 MB, 42 alumnos, 12 materiales") y avisa:

> *"Este archivo contiene los chats de 42 alumnos. Guardalo en un lugar seguro."*

Si el docente exporta **toda la app** (no sólo una cátedra), el `scope.kind`
es `"app"` y el bundle lleva todos los `courses.json`.

### 6.4 Importación (hito 2)

`Kitoru-Server` y el traspaso entre docentes necesitan poder meter el bundle
de vuelta. El importador (hito 2):

1. Lee `manifest.json`; valida `format`, `version` y los checksums.
2. Muestra un resumen: "Cátedra IA-2026-1 · 42 alumnos · 340 conversaciones ·
   12 materiales".
3. **Modo de fusión**: cada entidad se identifica por su `id`; si el `id` ya
   existe localmente, se pregunta: *Sobrescribir / Mantener el local /
   Cancelar* (regla por defecto: **mantener el local**, para no pisar datos
   más nuevos).
4. Los archivos de material van a `KitoruPaths.coursesDir/<courseId>/…` y el
   `Material.path` del JSON se ajusta a la ruta local.

Mientras tanto, el `.kitoru` del hito 1 es **de ida**: se genera y se abre
con cualquier herramienta, pero Kitoru todavía no lo importa. Queda escrito en
el README del docente ("próximamente: importar un respaldo").

---

## 7. Informes automáticos programados (resumen semanal)

### 7.1 Qué se quiere

El docente no quiere "abrir la app y pedir el informe": quiere que **el
lunes aparezca un resumen de la semana** ("cuánta gente chateó, cuánto se
usó el material, si algún alumno quedó sin actividad").

### 7.2 Dónde corre

| Plataforma | Mecanismo | Limitación real |
|---|---|---|
| **Desktop** | Un planificador **dentro del proceso de la app**: un `ScheduledExecutorService` o un `coroutineScope.launch { while(true) { delay(hasta la próxima ejecución) } }` que dispara el informe mientras la app está abierta. | Si la app está cerrada, no se corre. No hay demonio ni servicio en el hito 1. |
| **Desktop (alternativa)** | Una tarea del sistema (Task Scheduler / cron / launchd) que ejecute un **CLI headless**: `kitoru-report --catedra IA-2026-1 --formato md --salida ~/Documents/Kitoru`. | Requiere el módulo CLI (hito 3) y que la instalación lo deje disponible en el PATH. |
| **Android** | **`WorkManager`** (`androidx.work:work-runtime-ktx`): un `CoroutineWorker` con `PeriodicWorkRequest` de 7 días (el mínimo de WorkManager es 15 min; 7 días entra perfecto). Es **persistente**: sobrevive al reinicio del teléfono y a que la app esté cerrada. El worker escribe el informe en `exportsDir` y (opcionalmente) **notifica** con un `NotificationCompat` + `PendingIntent` a la pantalla de informes. | Necesita el permiso `POST_NOTIFICATIONS` (API 33+) para notificar; el informe se genera igual, sólo la notificación requiere permiso. |

Restricciones de WorkManager que hay que respetar: el trabajo **periódico**
no puede correr más de una vez por hora y su ejecución real depende de las
restricciones del sistema (batería, Doze). Para un resumen semanal es
perfecto: interesa que corra "alguna vez esta semana", no exactamente el lunes
a las 8.

### 7.3 Veredicto: **no en el hito 1**

**Decisión: la programación automática entra en el hito 2, no en el hito 1.**

Por qué:

1. **El costo de construcción no escala con el valor.** WorkManager + un
   `Worker` + notificaciones + el `PermissionRequest` de API 33 + el
   planificador de desktop + el estado "última ejecución" + los tests de
   ambos = un subproyecto entero, para un entregable que se usa **una vez por
   semana**.
2. **En el hito 1 el docente puede generar el resumen en 5 segundos**: un
   botón "Informe de cátedra" → elegir Markdown → "Guardar en Documentos".
   El 90 % del valor ("tengo el informe") está cubierto sin scheduler.
3. **La app abierta es una precondición frágil.** En desktop, el informe
   programado sólo existe si la app está abierta; eso genera una expectativa
   rota ("el lunes no llegó el resumen"). En Android, WorkManager lo resuelve,
   pero requiere una dependencia de AndroidX que en el hito 1 no aporta nada
   visible.
4. **El resumen automático es un informe, no una alerta.** Se puede generar
   el mismo contenido bajo demanda. Lo único que agrega la programación es
   el *timing*, y el timing no es crítico para un informe semanal.

Lo que **sí** se hace en el hito 1 para no perder el hilo:

* La pantalla de informes muestra **"Último informe generado: hace 7 días"**
  con un botón para generar el de ahora. Es el nudge, sin scheduler.
* El **historial de exportaciones** (§2.7) queda: el docente ve los informes
  que ya generó, y el resumen semanal (cuando exista) aparece ahí.
* El modelo de datos ya lo soporta: `ReportKind.WEEKLY` existe en el IR
  desde el hito 1 y el builder `WeeklyReport` es el `CourseReport` con el
  período de los últimos 7 días. La programación es *disparar* ese builder
  una vez por semana; no cambia el informe.

### 7.4 Cómo se implementará en el hito 2 (concreto)

**Desktop** (`:desktop`):

```kotlin
// desktop/src/main/kotlin/kitoru/desktop/reports/ReportScheduler.kt
class ReportScheduler(
    private val scope: CoroutineScope,
    private val job: suspend (Long) -> Unit,   // (inicio del período) -> genera el informe
) {
    fun start(dayOfWeek: DayOfWeek, hour: Int, minute: Int) { /* delay hasta el próximo → job() → repetir */ }
    fun stop() { /* … */ }
}
```

Se dispara **al abrir la app**: si la app se abrió después de la hora
programada y todavía no corrió en ese período, se corre de inmediato (para
no perder la semana si el docente no abrió la app el lunes).

**Android** (`:android`):

```kotlin
class WeeklyReportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val courseId = inputData.getString(KEY_COURSE) ?: return Result.success()
        // 1. calcular el informe de los últimos 7 días
        // 2. renderizar a Markdown
        // 3. exportar a KitoruPaths.exportsDir
        // 4. registrar en el historial
        // 5. notificar (si hay permiso)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context, courseId: String) {
            val req = PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS)
                .setInputData(workDataOf(KEY_COURSE to courseId))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("informe-semanal-$courseId", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
```

* Se encola **único** por cátedra (`enqueueUniquePeriodicWork`) para no
  duplicar.
* El worker **no necesita red**: todo es local. `Constraints` sólo pide
  "batería no baja" para no castigar el teléfono.
* La notificación usa un canal propio ("Informes de la cátedra") y un
  `PendingIntent` que abre la pantalla del informe ya generado.

---

## 8. Nombres de archivo

### 8.1 Patrón

```
<base>_<ámbito>_<AAAAMMDD>[_HHmm].<ext>
```

| Parte | Valor | Ejemplo |
|---|---|---|
| `base` | `ReportKind.fileName` | `informe-actividad` |
| `ámbito` | slug del código de cátedra, o del alumno | `IA-2026-1` → `IA-2026-1`; `Pérez, Juan` → `perez-juan` |
| `AAAAMMDD` | fecha **local** de generación | `20261021` |
| `HHmm` | sólo si se hacen dos exportaciones en el mismo minuto (o para el resumen semanal) | `1430` |
| `ext` | `csv` / `json` / `md` / `pdf` / `kitoru` | |

Ejemplos:

```
informe-actividad_IA-2026-1_20261021.csv
informe-actividad_IA-2026-1_20261021.json
informe-actividad_IA-2026-1_20261021.md
informe-calidad_IA-2026-1_20261021.md
informe-calidad_IA-2026-1_20261021_calidad-por-material.csv
informe-uso-materiales_IA-2026-1_20261021.csv
informe-alumno_IA-2026-1_perez-juan_20261021.pdf
informe-catedra_IA-2026-1_20261021.pdf
catedra_IA-2026-1_20261021.kitoru
resumen-semanal_IA-2026-1_2026W43.md
```

### 8.2 Reglas de saneado (obligatorias, porque los archivos van a Windows)

`FileNameSanitizer.sanitize(name)` (`kitoru.platform`):

1. `stripDiacritics` (ya existe) → sin acentos.
2. Minúsculas.
3. Todo lo que no sea `[a-z0-9._-]` → `-`; se colapsan los `-` repetidos.
4. Se recortan los `-` de los extremos.
5. Se limita a **120 caracteres** (cuidado con el límite de 255 de la ruta en
   Windows: el nombre completo con la carpeta debe quedar debajo).
6. Se quitan los puntos y espacios finales (Windows no los acepta).
7. Si el nombre resultante es un **nombre reservado** de Windows
   (`CON`, `PRN`, `AUX`, `NUL`, `COM1..9`, `LPT1..9`), se le antepone `_`.
8. Nunca queda vacío: si todo se eliminó, se usa `informe`.

### 8.3 La fecha sin `java.time`

`minSdk = 24` y `java.time` es API 26+. Se agrega un helper **puro** en
`kitoru.platform`:

```kotlin
data class CivilDate(val year: Int, val month: Int, val day: Int) {
    /** "2026-10-21" */
    fun iso(): String = "%04d-%02d-%02d".format(year, month, day)
    /** "20261021" */
    fun compact(): String = "%04d%02d%02d".format(year, month, day)
    /** "2026-W43" (semana ISO) */
    fun isoWeek(): String = ...
    companion object {
        /** Algoritmo de Hinnant: días desde epoch → fecha civil. */
        fun ofEpochDay(epochDay: Long): CivilDate { ... }
        /** Fecha local a partir de epoch millis. */
        fun localOf(millis: Long, offsetMillis: Int): CivilDate = ...
    }
}

/** Desfase de la zona horaria local, en ms, para un instante dado. */
expect fun localUtcOffsetMillis(millis: Long): Int
```

* La fecha del **nombre de archivo** es local (la del usuario).
* La agrupación por día de los **datos** sigue siendo la de la app:
  `AnalyticsAnalyzer.dayStart` (medianoche **UTC**). Los dos no tienen por
  qué coincidir, y el informe lo aclara en la nota de la tabla: *"Los días
  se agrupan por medianoche UTC, igual que en la pantalla de analítica."*

---

## 9. Composables (módulo `:ui`)

Paquete: `kitoru.ui.teacher.reports`.

| Composable | Qué hace |
|---|---|
| `ReportsScreen(courseId: String)` | Pantalla central del docente. Pestañas: *Actividad · Calidad · Materiales · Alumnos · Cátedra*. |
| `ReportHeader(report, onExport)` | Título, metadatos, período, resumen de visibilidad y la barra de export. |
| `ExportBar(report, onExport: (ExportFormat) -> Unit)` | Fila de botones: `CSV · JSON · Markdown · PDF` + `Guardar en…` + `Compartir` (sólo Android). |
| `ExportDialog(report, onDone: (ExportOutcome) -> Unit)` | Separador CSV, destino, y el resultado de la exportación. |
| `ReportTableView(table: ReportTable)` | Renderiza una tabla del IR en Compose. **Es la misma vista que se exporta**: lo que se ve en pantalla es el informe. |
| `ReportCellView(cell, column)` | Formatea una celda según el `ColumnType` (número, %, duración, fecha). |
| `ActivityReportView(report)` | La vista del informe de actividad. |
| `QualityReportView(report)` | Incluye el desglose por motivo y la lista de ejemplos con citas sin respaldo. |
| `MaterialUsageView(report)` | Resalta en ámbar las filas "nunca citados" y en rojo las "nunca recuperadas". |
| `StudentReportView(report)` | Métricas + conversaciones; si la visibilidad no es `FULL`, muestra el aviso. |
| `CourseSummaryView(report)` | El resumen para el departamento. |
| `StudyTimeBadge(hours: Double)` | `≈ 1,2 h (est.)` con tooltip: "Estimación a partir del volumen y los intervalos entre mensajes". |
| `VisibilitySummaryRow(summary)` | "42 de 45 alumnos comparten sus datos · 3 no". |
| `ExportHistoryList(records, onOpen, onShare)` | Los archivos exportados antes, con "abrir" y "compartir". |
| `BundleExportDialog(course, onExport)` | Opciones del `.kitoru` (archivos, texto, anonimizar) + tamaño estimado. |
| `WeeklyReportSettings(...)` | **Hito 2**: día y hora del resumen semanal. |

Todos en Material 3, con el mismo lenguaje visual que el resto de la app de
docente, y **sincronizados con el IR**: no hay "dos verdades" entre lo que se
ve y lo que se exporta.

El `ReportViewModel` (o `ReportsController`) hace el trabajo sucio:

```kotlin
class ReportsViewModel(
    private val courses: CourseRepository,
    private val enrollments: EnrollmentRepository,
    private val materials: MaterialRepository,
    private val threads: ThreadRepository,
    private val messages: MessageRepository,
    private val exporter: FileExporter,
) {
    val state: StateFlow<ReportsState>
    fun selectKind(kind: ReportKind)
    fun setFilter(filter: ReportFilter)
    fun export(format: ExportFormat, target: SaveTarget)
    fun exportBundle(options: BundleOptions)
}
```

---

## 10. Cambios de dominio que exige este diseño

Estos cambios son **aditivos** y no rompen los datos existentes
(`ignoreUnknownKeys = true` + `encodeDefaults = true`):

### 10.1 `ChatMessage`: el detalle de las citas

Hoy `ChatMessage.citation` es un `CitationStats` (tres contadores). Con eso
**no se puede** decir *por qué* falló una cita ni *de qué material* salió el
fallo, que es justamente lo que el informe de calidad necesita. El motivo
se conoce en el momento de la verificación (`rag.engine.CitationCheck.reason`)
y **se pierde** al guardar.

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Chat.kt

/** Por qué una cita no está respaldada. */
@Serializable
enum class CitationFailureReason(val label: String) {
    NONE("Respaldada"),
    FUENTE_INEXISTENTE("La fuente citada no existe"),
    FUENTE_VACIA("La fuente citada está vacía"),
    DETALLE_INVENTADO("La cita menciona detalles que no están en la fuente"),
    NO_APARECE("El fragmento citado no aparece en la fuente"),
    SIN_CITAS("La respuesta no tuvo citas"),
}

/** El resultado de verificar una cita concreta. */
@Serializable
data class CitationOutcome(
    /** Índice citado en la respuesta (1-based, como en el texto). */
    val index: Int,
    /** Etiqueta de la fuente citada ("cátedra: Unidad 1"). */
    val label: String,
    val supported: Boolean,
    val reason: CitationFailureReason = CitationFailureReason.NONE,
    /** El fragmento de la respuesta que acompaña a la cita. */
    val snippet: String = "",
)
```

Y en `ChatMessage`:

```kotlin
/** Detalle de cada cita verificada (nuevo; vacío en los chats viejos). */
val citationDetail: List<CitationOutcome> = emptyList(),
```

* El backend lo llena en el momento de verificar
  (`CitationReport.checks` → `CitationOutcome`), mapeando `reason` al enum.
* Los chats anteriores quedan con la lista vacía: el informe de calidad
  muestra *"detalle no disponible (datos anteriores a la versión 1.2)"* en la
  columna de motivos, y el resto de los números (que salen de `CitationStats`)
  siguen funcionando.
* Con `citationDetail` el informe de calidad por material y por motivo
  deja de ser una aproximación: `label` dice el material y `index` permite
  resolver el material aunque el rótulo haya cambiado.

### 10.2 `FileSystem`: `writeBytes` / `readBytes`

Ver §2.6. Aditivo, hay que actualizar `JvmFileSystem` y los fakes de test.

### 10.3 `KitoruPaths`: `documentsDir` y `exportsDir`

Ver §2.5. Aditivo.

### 10.4 `MaterialResolver`: de la etiqueta de fuente al material

`ChatMessage.sources` guarda la etiqueta del chunk, que hoy es
`"cátedra: <título del material>"` (`Chunk.label = "$source: $path"`, y
`CourseMaterialSource` usa `material.title.ifBlank { material.path }`).
Para el informe de uso de materiales hay que resolver esa etiqueta a un
`Material`:

```kotlin
object MaterialResolver {
    /**
     * Resuelve la etiqueta de una fuente a un material.
     * 1. Se saca el prefijo "cátedra: " (lo que haya antes del primer ": ").
     * 2. Se busca por título exacto, luego por título normalizado
     *    (sin acentos, minúsculas) y por último por la ruta.
     * 3. Si hay más de un material con el mismo título, devuelve null y el
     *    informe lo cuenta como "sin material asociado" (no se adivina).
     */
    fun resolve(label: String, materials: List<Material>): Material?
}
```

* Los rótulos que no se pueden resolver van a un cubo `SIN_ASOCIAR` en el
  informe, con su número, para que el docente sepa que hay material sin
  seguimiento.
* **Mejora recomendada para el hito 2**: hacer el rótulo estable, p.ej.
  `"cátedra: a3f9c1 · Unidad 1"`, agregar `materialId` a `Doc`/`Chunk` y
  guardarlo en `ChatMessage.sources`. Con eso el resolver es exacto. Es un
  cambio de formato de rótulo: hay que actualizar el prompt del RAG y la
  vista de citas, y por eso no entra en el hito 1.

### 10.5 `TopicsAnalyzer` (nuevo, sin cambio de dominio)

Reusa `Bm25Index.tokenize` (que ya normaliza y quita diacríticos) y el
índice BM25 para agrupar preguntas por similitud. La stoplist del español
(≈120 palabras) es propia del módulo de reportes.

---

## 11. Plan por hitos

### Hito 1 — "el docente puede sacarse los datos"

* `:reports` con el IR, los 5 builders, el estimador de horas y los
  renderizadores **CSV, JSON y Markdown**.
* `FileExporter` + `SaveTarget` + `ExportHost` en `:core`, con el diálogo
  nativo de desktop y SAF en Android.
* Exportación a la carpeta de la app, a Documentos, "guardar como…" y
  compartir (Android).
* **PDF en desktop** (`PdfRenderer` en `:reports/desktopMain`).
* Bundle `.kitoru` (escritura).
* Pantalla `ReportsScreen` con las 5 pestañas, el `ExportDialog` y el
  historial de exportaciones.
* `CitationOutcome` en el dominio (para el desglose por motivo).

### Hito 2 — "el docente lo usa todas las semanas"

* PDF en **Android**: mover `PdfRenderer` a `jvmCommonMain` + `pdfbox-android`.
* Importación de bundles `.kitoru` (con checksums y modo de fusión).
* **Resumen semanal programado**: `WorkManager` en Android + planificador
  dentro de la app en desktop.
* `WeeklyReport` + `WeeklyReportSettings`.
* Rótulos de fuente estables con `materialId` (fin del `MaterialResolver`
  por heurística).

### Hito 3 — "el departamento lo recibe solo"

* CLI headless (`kitoru-report`) para automatizar desde el sistema operativo.
* Exportación por correo (SMTP o `mailto:`) desde desktop.
* Plantillas de informe (el docente elige el esqueleto del informe).
* Importación desde Kitoru-Server (los chats que llegan del servidor).

---

## 12. Tests

En `:reports/src/desktopTest` (JUnit 5, como `:backend`):

| Test | Qué fija |
|---|---|
| `CsvRendererTest` | Escape de comas, punto y comas y comillas, BOM, CRLF, celda vacía vs `0`. |
| `MarkdownRendererTest` | Tabla GFM bien formada, metodología al pie, acentos. |
| `JsonRendererTest` | Ida y vuelta `Report` → JSON → `Report`; `schemaVersion`. |
| `ActivityReportBuilderTest` | Las columnas del informe coinciden con `StudentAnalytics`; los alumnos `NONE` no aparecen. |
| `QualityReportBuilderTest` | El desglose por motivo suma el total de fallos; el total coincide con `CitationStats`. |
| `MaterialUsageReportTest` | "nunca citado" vs "nunca recuperado"; el diagnóstico de cada caso. |
| `VisibilityRulesTest` | `METRICS_ONLY` sin texto, sin títulos, sin preguntas; `NONE` ausente de todo. |
| `StudyTimeEstimatorTest` | El ejemplo trabajado (191,8 s); los casos borde (gap 0, `createdAt = 0`, tope diario, corte de sesión). |
| `TopicsAnalyzerTest` | Determinista: mismas preguntas → mismos grupos. |
| `BundleWriterTest` | ZIP válido, `manifest.json` con versión y checksums, round-trip. |
| `PdfRendererTest` (desktop) | Genera un PDF no vacío, con N páginas, y el texto se puede extraer. |

Todos los tests son **deterministas**: el reloj se inyecta
(`now: Long`), no se usa `nowMillis()` dentro de la lógica.

---

## 13. Riesgos

| Riesgo | Mitigación |
|---|---|
| Confundir "estimación" con "medición" | Etiqueta fija "estimado" + metodología al pie. En ningún lado se escribe "horas de estudio" a secas. |
| Que un informe exportado rompa la confidencialidad | La visibilidad se aplica en el **builder**, no en el renderizador: lo que no se puede ver no llega al IR. Cada informe lleva el resumen de visibilidad. |
| El bundle `.kitoru` se filtra a donde no debe | Confirmación explícita con el número de alumnos afectados; opción de anonimizar; la app nunca lo sube a ningún lado por sí sola. |
| PDF desactualizado con el Markdown | Ambos se renderizan del **mismo IR**; imposible que divergan. |
| PDFBox 3.x en desktop rompe el renderer compartido | Anotado como deuda; se fija la línea 2.0 en `libs.versions.toml` con un comentario. |
| Los acentos se rompen en el PDF | `sanitizeWinAnsi()` + fuentes estándar; test que extrae el texto del PDF y compara. |
| El tema "más preguntados" inventa cosas | No hay LLM: es BM25 + frecuencia de términos. Determinista y explicable. |
| WorkManager no dispara el día exacto | Se documenta: "el resumen semanal se genera dentro de la semana, no necesariamente el lunes". |
| El ".kitoru" con materiales puede pesar mucho | El diálogo muestra el tamaño estimado y los materiales son opcionales. |

---

## 14. Lo que este subsistema NO hace (hito 1)

* No sube nada a la nube ni manda correos.
* No se conecta con el LMS ni con Kitoru-Server (el bundle es el puente
  manual, por ahora).
* No usa un modelo para escribir el informe: **todo es determinista**. El
  modelo no redacta ni resume nada (el resumen de temas es BM25).
* No mide el tiempo real de pantalla: **estima** horas de estudio.
* No permite al docente ver conversaciones de alumnos que eligieron
  `METRICS_ONLY` o `NONE`, ni en la app ni en un archivo exportado.
