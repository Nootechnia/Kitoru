# 01 · Extracción de texto (material → texto indexable)

> Subagente: diseño del **pipeline de extracción** de los materiales de una cátedra.
> Alcance: un archivo subido (PDF, Word, slides, Excel, Markdown, HTML, CSV, un
> enlace) → **texto plano indexable** por el RAG, en escritorio **y** en Android.
> Documento hermano: `docs/design/teacher/03-materiales.md` §4 define *la
> pantalla*; este documento define *el algoritmo*. Donde los dos se pisan, este
> manda en el algoritmo y el otro manda en la UI.
> Estado: diseño. No hay código escrito todavía.

---

## 0. Resumen ejecutivo (la decisión de cada punto)

| # | Decisión | Veredicto |
|---|---|---|
| 1 | Dónde vive el código | **`:core`**, paquete `kitoru.material` (no `:backend`: el byte-lo que sabe leer un ZIP no es cosa del RAG; y `Material.extractedText` ya vive en `:core`) |
| 2 | Interfaz | `TextExtractor` con `suspend fun extract(file: FilePath, mime: String): ExtractionResult` + `ExtractorRegistry` que despacha **por extensión primero**, MIME como respaldo |
| 3 | PDF en escritorio | `org.apache.pdfbox:pdfbox:2.0.37` (jar, Apache-2.0) |
| 4 | PDF en Android | `com.tom-roush:pdfbox-android:2.0.27.0` (**AAR**, Apache-2.0) — **no hay variante JVM**: por eso el PDF va detrás de una interfaz `PdfBackend` con dos `actual`s de 30 líneas |
| 5 | PDF de dos columnas | Se resuelve con **geometría**: se leen los *runs* de texto con su caja, se detecta el *gutter* y se reordena en orden de lectura humano |
| 6 | PDF escaneado | Sin capa de texto → `needsOcr = true` + aviso; el material se sube pero **no aporta nada al índice** |
| 7 | DOCX / PPTX / XLSX | **Parseo manual** de ZIP + XML (`java.util.zip.ZipInputStream` existe en Android ✓). Apache POI no corre en Android |
| 8 | HTML / CSV | Parser propio en Kotlin puro (`commonMain`). CSV → **tabla Markdown** (decidido) |
| 9 | EPUB | **Fuera del hito 1**: se detecta, se sube y se avisa. La costura queda lista |
| 10 | Links | **Sí se descarga** el HTML y se cachea por SHA-1 de la URL |
| 11 | Limpieza | Común y en `commonMain`: whitespace, guiones de corte de línea, números de página, **encabezados repetidos (> 50 % de las páginas ⇒ pie de página ⇒ se borran)** |
| 12 | Idioma | Heurística de palabras funcionales es/en (sin librería: OpenNLP no anda en Android, ML Kit es Android-only) |
| 13 | Límites | 50 MB por archivo, 2 M de caracteres, 200 MB descomprimidos, 60 s de plazo, 2 000 páginas |
| 14 | Hilo | Todo el trabajo bloqueante va en `withContext(kitoru.platform.ioDispatcher)`; la UI nunca se congela porque el `ViewModel` lanza la corrutina y reporta progreso |

---

## 1. El contrato: qué es "texto indexable"

### 1.1 `ExtractionResult`

```kotlin
// core/src/commonMain/kotlin/kitoru/material/TextExtractor.kt
package kitoru.material

import kitoru.platform.FilePath

/**
 * Resultado de extraer texto de un material.
 *
 * "Indexable" significa: texto plano, con la **estructura de encabezados en
 * Markdown**, que es lo que el `Chunker` de `:backend` sabe cortar
 * (`rag.retrieval.Chunker.split` corta por líneas que empiezan con `#`).
 * Por eso los extractores no devuelven un `String` pelado: devuelven
 * encabezados (`#`, `##`), tablas Markdown y marcas de página (`## Página 7`).
 */
data class ExtractionResult(
    /** Texto indexable (Markdown-ish). Puede estar vacío si el archivo no tenía texto. */
    val text: String = "",

    /** Título del documento según sus propios metadatos. Vacío si no lo tiene. */
    val title: String = "",

    /**
     * "Unidades" del documento: páginas de PDF, diapositivas de PPTX, hojas de
     * XLSX. 0 si el formato no tiene (un .md no tiene páginas).
     */
    val pages: Int = 0,

    /** Aviso para el docente, en español. Vacío = todo bien. */
    val warning: String = "",

    /** Idioma detectado: `"es"`, `"en"` o `""` si no se pudo determinar. */
    val language: String = "",

    /** El documento no tiene capa de texto: necesita OCR (hoy no hay OCR). */
    val needsOcr: Boolean = false,

    /** Id del extractor que produjo el texto ("pdfbox", "docx-zip", "plain-text"…). */
    val extractor: String = "",

    /** El texto se cortó por `maxChars`. */
    val truncated: Boolean = false,

    /** Detalles para la UI y la auditoría. */
    val stats: ExtractionStats = ExtractionStats(),
) {
    /** ¿Hay algo que indexar? */
    val hasText: Boolean get() = text.isNotBlank()

    /** ¿Va limpio (sin avisos)? Un aviso no bloquea la indexación. */
    val isClean: Boolean get() = hasText && warning.isBlank()
}

/** Cifras de la extracción, para mostrar en la UI y para la auditoría. */
data class ExtractionStats(
    /** Caracteres del texto final. */
    val chars: Int = 0,
    /** Palabras aproximadas. */
    val words: Int = 0,
    /** Bloques (párrafos, encabezados, filas de tabla). */
    val blocks: Int = 0,
    /** Imágenes que se encontraron y se omitieron. */
    val skippedImages: Int = 0,
    /** Columnas detectadas en el PDF (1 = una sola columna). */
    val columns: Int = 1,
)
```

> **Por qué `title` y no sólo `text`.** El título del documento (de los
> metadatos del PDF, de `docProps/core.xml` del DOCX, del `<title>` del HTML)
> sirve para **prellenar el diálogo de subida** del docente: si el PDF se llama
> `scan_0001.pdf` pero su título es "Introducción a los modelos lineales", el
> material se titula con lo segundo. Si no hay metadatos, se usa el nombre del
> archivo sin extensión.

### 1.2 Errores

```kotlin
// core/src/commonMain/kotlin/kitoru/material/ExtractionErrorCode.kt
package kitoru.material

/**
 * Por qué falló una extracción.
 *
 * El código lo usa la UI para decidir la acción (reintentar, pedir otro
 * archivo, editar a mano) y el mensaje en español es lo que ve el docente.
 */
enum class ExtractionErrorCode {
    /** El archivo ya no está en el disco. */
    NOT_FOUND,
    /** 0 bytes. */
    EMPTY_FILE,
    /** Supera el máximo por archivo. */
    TOO_LARGE,
    /** Se pasó el plazo de extracción. */
    TIMEOUT,
    /** ZIP/XML/PDF dañado. */
    CORRUPT,
    /** PDF (o .docx) protegido con contraseña. */
    ENCRYPTED,
    /** Formato sin soporte de extracción. */
    UNSUPPORTED,
    /** Sin capa de texto: requiere OCR. */
    NO_TEXT_LAYER,
    /** Se agotó la memoria. */
    OUT_OF_MEMORY,
    /** Error de E/S. */
    IO,
    /** Cualquier otra cosa. */
    UNKNOWN,
}

/**
 * Error de extracción. El mensaje ya está en español y es lo que se muestra.
 */
class MaterialExtractionException(
    val code: ExtractionErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
```

### 1.3 La línea que separa "extraer" de "limpiar"

La limpieza (§5) es **común y va después de la extracción**, no adentro de cada
extractor. Motivo: el DOCX, el PDF y el HTML tienen los mismos problemas
finales (whitespace, guiones, números de página) y duplicar la lógica en tres
lugares garantiza que se queden distintos.

Excepción: la detección de **encabezados repetidos** y de **números de página**
necesita conocer la **estructura de páginas**, que sólo el PDF tiene. Por eso
el PDF arma un `List<List<String>>` (página → líneas) y el limpiador trabaja
sobre esa estructura; para el resto de los formatos el limpiador trabaja sobre
un único "bloque" y la detección de repetidos no aplica (el DOCX salta los
`header*.xml`/`footer*.xml` por otra vía, §4.3).

---

## 2. La interfaz `TextExtractor` y el despacho

### 2.1 La interfaz

```kotlin
// core/src/commonMain/kotlin/kitoru/material/TextExtractor.kt (continuación)

/**
 * Extrae texto de un material.
 *
 * Es una interfaz (y no una clase) porque cada formato tiene su quirks, y
 * porque el PDF necesita una implementación distinta por plataforma (§3).
 */
interface TextExtractor {

    /** Id del extractor, para la UI y la auditoría ("pdfbox", "docx-zip"…). */
    val id: String

    /** Extensiones que maneja, en minúsculas y **sin punto**. */
    val extensions: Set<String>

    /** MIME types que maneja (respaldo cuando la extensión no sirve). */
    val mimes: Set<String> = emptySet()

    /** ¿Este extractor maneja este archivo? */
    fun supports(file: FilePath, mime: String): Boolean =
        file.extension in extensions || mime.lowercase() in mimes

    /**
     * Extrae el texto.
     *
     * @param file el archivo **ya copiado** al almacén de la cátedra.
     * @param mime el MIME declarado por el sistema (puede ser inútil:
     *   en Windows y en Android suele ser `application/octet-stream`).
     * @throws MaterialExtractionException con mensaje en español.
     */
    suspend fun extract(file: FilePath, mime: String): ExtractionResult
}
```

**Por qué la firma no lleva el formato ni el nombre del archivo.**
`FilePath` ya trae el nombre y la extensión (`FilePath.extension`), así que el
extractor puede decidir por sí mismo. Y el formato "real" (por firma binaria)
lo calcula el `MaterialFormatDetector` **antes** de despachar, para poder
avisar "el archivo dice .pdf pero no es un PDF".

### 2.2 El registro de extractores

```kotlin
// core/src/commonMain/kotlin/kitoru/material/ExtractorRegistry.kt
package kitoru.material

import kitoru.platform.FilePath

/**
 * Registro de extractores y despacho.
 *
 * El registro es una **lista plana** indexada por extensión y por MIME. No
 * hay `when (formato)` en un solo lugar: cada extractor declara a qué
 * formatos sirve y el registro arma los índices. Agregar un formato es
 * agregar un objeto, no editar un `when`.
 */
class ExtractorRegistry(extractors: List<TextExtractor>) {

    private val byExtension: Map<String, TextExtractor> = buildMap {
        for (e in extractors) for (ext in e.extensions) put(ext.lowercase(), e)
    }

    private val byMime: Map<String, TextExtractor> = buildMap {
        for (e in extractors) for (m in e.mimes) put(m.lowercase(), e)
    }

    /** Extractor para un formato ya detectado. */
    fun forFormat(format: MaterialFormat): TextExtractor? =
        format.extensions.firstNotNullOfOrNull { byExtension[it] }

    /**
     * Extractor para un archivo.
     *
     * El orden importa: **extensión primero**, MIME después. En Windows
     * muchos `.md` llegan como `text/plain` y en Android un `.docx` puede
     * llegar como `application/octet-stream`; al revés se elegiría mal.
     */
    fun forFile(file: FilePath, mime: String): TextExtractor? =
        byExtension[file.extension.lowercase()]
            ?: byMime[mime.lowercase().substringBefore(';').trim()]

    /** Todos los extractores registrados (para la UI: "formatos soportados"). */
    val registered: List<TextExtractor> get() = byExtension.values.distinct()
}
```

### 2.3 Detección de formato (extensión + firma binaria)

```kotlin
// core/src/commonMain/kotlin/kitoru/material/MaterialFormat.kt
package kitoru.material

import kitoru.domain.MaterialKind

/**
 * Formatos que Kitoru sabe leer.
 *
 * `supportsText = false` = se sube y se guarda, pero no aporta texto.
 */
enum class MaterialFormat(
    val label: String,
    val extensions: Set<String>,
    val defaultKind: MaterialKind,
    val supportsText: Boolean,
) {
    PDF("PDF", setOf("pdf"), MaterialKind.DOCUMENT, true),
    DOCX("Word", setOf("docx"), MaterialKind.DOCUMENT, true),
    PPTX("PowerPoint", setOf("pptx"), MaterialKind.SLIDES, true),
    XLSX("Excel", setOf("xlsx"), MaterialKind.OTHER, true),
    MARKDOWN("Markdown", setOf("md", "markdown", "mdx"), MaterialKind.TRANSCRIPT, true),
    TEXT("Texto", setOf("txt", "text", "log"), MaterialKind.TRANSCRIPT, true),
    RST("reStructuredText", setOf("rst"), MaterialKind.TRANSCRIPT, true),
    ASCIIDOC("AsciiDoc", setOf("adoc", "asciidoc"), MaterialKind.TRANSCRIPT, true),
    CSV("CSV", setOf("csv", "tsv"), MaterialKind.OTHER, true),
    HTML("HTML", setOf("html", "htm", "xhtml"), MaterialKind.OTHER, true),
    /** EPUB: detectado pero **sin extractor en el hito 1** (§4.8). */
    EPUB("EPUB", setOf("epub"), MaterialKind.OTHER, false),
    /** Material de tipo enlace: no es un archivo, se descarga (§4.9). */
    LINK("Enlace", emptySet(), MaterialKind.LINK, true),
    UNKNOWN("Desconocido", emptySet(), MaterialKind.OTHER, false),
    ;

    /** Extensión principal (la que se usa para despachar). */
    val primaryExtension: String get() = extensions.firstOrNull() ?: ""

    companion object {
        /** Por extensión. */
        fun fromExtension(ext: String): MaterialFormat? {
            val e = ext.lowercase().removePrefix(".")
            return entries.firstOrNull { e in it.extensions }
        }
    }
}
```

```kotlin
// core/src/commonMain/kotlin/kitoru/material/MaterialFormatDetector.kt
package kitoru.material

import kitoru.platform.FilePath
import kitoru.platform.FileSystem

/**
 * Detecta el formato real de un archivo.
 *
 * Dos pasos, en este orden:
 *  1. **firma binaria** (los primeros bytes): es la fuente de verdad;
 *  2. **extensión**: sólo cuando la firma no dice nada (un .md no tiene firma).
 *
 * Si la extensión y la firma se contradicen → `warning`:
 * "El archivo «notas.pdf» dice ser PDF pero su contenido no lo es."
 */
class MaterialFormatDetector(private val fs: FileSystem) {

    data class Detected(
        val format: MaterialFormat,
        /** El contenido real se contradice con la extensión. */
        val mismatched: Boolean = false,
        /** La extensión que tenía el archivo. */
        val declaredExtension: String = "",
    )

    fun detect(file: FilePath): Detected {
        val ext = file.extension
        val head = readHead(file, 16)
        val bySignature = detectBySignature(head)
        val byExtension = MaterialFormat.fromExtension(ext)

        return when {
            bySignature != null && byExtension != null && bySignature != byExtension ->
                Detected(bySignature, mismatched = true, declaredExtension = ext)
            bySignature != null -> Detected(bySignature, declaredExtension = ext)
            byExtension != null -> Detected(byExtension, declaredExtension = ext)
            else -> Detected(MaterialFormat.UNKNOWN, declaredExtension = ext)
        }
    }

    private fun detectBySignature(head: ByteArray): MaterialFormat? = when {
        head.size >= 5 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() &&
            head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte() -> MaterialFormat.PDF

        head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte() -> {
            // Es un ZIP: la extensión dice cuál es (docx/pptx/xlsx/epub).
            MaterialFormat.fromExtension(...) ?: MaterialFormat.UNKNOWN
        }

        head.size >= 8 && head.copyOfRange(0, 8)
            .contentEquals(byteArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) ->
            MaterialFormat.UNKNOWN   // OLE2: .doc / .xls viejos (no soportados)

        else -> null
    }
}
```

> El ZIP se resuelve por extensión porque leer el `mimetype` interno obligaría
> a abrir el ZIP entero antes de despachar; y el ZIP OOXML siempre trae
> `[Content_Types].xml` con el tipo real, que se verifica **dentro** de cada
> extractor (`DocxExtractor` exige `word/document.xml`; si no está, el archivo
> no es un Word y el error lo dice).

### 2.4 El pipeline completo

```
  archivo copiado a courses/<courseId>/files/<materialId>__<nombre>.<ext>
        │
        ▼
  1. EXISTE / TAMAÑO   ──── 0 bytes ──────────────────► EMPTY_FILE
        │                 ──── > 50 MB ────────────────► TOO_LARGE
        ▼
  2. DETECCIÓN      MaterialFormatDetector.detect(file)
        │            → formato + flag "la extensión no coincide"
        ▼
  3. DESPACHO       ExtractorRegistry.forFormat(formato)
        │            ──── null ───────────────────────► UNSUPPORTED
        ▼
  4. EXTRACCIÓN     extractor.extract(file, mime)      ← con plazo de 60 s
        │            ──── excepción ──────────────────► MaterialExtractionException
        ▼
  5. LIMPIEZA        TextCleaner + detectores (§5)
        ▼
  6. IDIOMA         LanguageDetector.detect(text)
        ▼
  7. TRUNCADO       > 2 000 000 caracteres → corta + warning
        ▼
  8. ExtractionResult → Material(extractedText, warning, …) → Chunker → BM25
```

```kotlin
// core/src/commonMain/kotlin/kitoru/material/MaterialExtractionService.kt
package kitoru.material

import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kitoru.platform.ioDispatcher
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * Orquestador de la extracción.
 *
 * Es el único punto que conoce el orden de los pasos. Los extractores son
 * "tontos": reciben un archivo y devuelven texto.
 */
class MaterialExtractionService(
    private val fs: FileSystem,
    private val registry: ExtractorRegistry,
    private val limits: ExtractionLimits = ExtractionLimits(),
) {

    suspend fun extract(file: FilePath, mime: String): ExtractionResult = withContext(ioDispatcher) {
        // (1) existencia y tamaño
        if (!fs.exists(file)) {
            throw MaterialExtractionException(
                ExtractionErrorCode.NOT_FOUND,
                "El archivo original ya no está en el disco. Volvé a subirlo para poder reextraerlo.",
            )
        }
        val size = fs.sizeBytes(file)
        if (size <= 0L) {
            throw MaterialExtractionException(
                ExtractionErrorCode.EMPTY_FILE,
                "El archivo está vacío (0 bytes).",
            )
        }
        if (size > limits.maxFileBytes) {
            throw MaterialExtractionException(
                ExtractionErrorCode.TOO_LARGE,
                "El archivo pesa ${size / 1024 / 1024} MB y el máximo por archivo es " +
                    "${limits.maxFileBytes / 1024 / 1024} MB.",
            )
        }

        // (2) formato real
        val detected = MaterialFormatDetector(fs).detect(file)
        if (detected.mismatched) {
            // No es un error: se extrae igual, pero avisando.
        }

        // (3) despacho
        val extractor = registry.forFormat(detected.format)
            ?: throw MaterialExtractionException(
                ExtractionErrorCode.UNSUPPORTED,
                unsupportedMessage(detected),
            )

        // (4) extracción con plazo
        val started = TimeSource.Monotonic.markNow()
        val raw = try {
            extractor.extract(file, mime)
        } catch (e: MaterialExtractionException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw MaterialExtractionException(
                ExtractionErrorCode.OUT_OF_MEMORY,
                "No hay memoria suficiente para leer este archivo. Cerrá otras aplicaciones y probá de nuevo.",
                e,
            )
        } catch (e: Exception) {
            throw MaterialExtractionException(
                ExtractionErrorCode.UNKNOWN,
                "No se pudo leer el archivo: ${e.message ?: "error desconocido"}.",
                e,
            )
        }
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        if (elapsedMs > limits.maxMillis) {
            throw MaterialExtractionException(
                ExtractionErrorCode.TIMEOUT,
                "La extracción tardó más de ${limits.maxMillis / 1000} s y se cortó. " +
                    "Probá con un archivo más chico.",
            )
        }

        // (5..7) limpieza, idioma, truncado
        finish(raw, detected)
    }

    /**
     * Pasos 5 a 7: limpieza común, detección de idioma y truncado.
     *
     * Los avisos del extractor y los del servicio se **concatenan**: un DOCX
     * puede avisar "3 imágenes omitidas" y además haber salido en Latin-1.
     */
    private fun finish(raw: ExtractionResult, detected: MaterialFormatDetector.Detected): ExtractionResult {
        val text = TextCleaner.clean(raw.text)

        val notes = mutableListOf<String>()
        if (raw.warning.isNotBlank()) notes += raw.warning
        if (detected.mismatched) {
            notes += "El archivo «${detected.declaredExtension}» dice ser " +
                "${detected.format.label} pero su contenido no lo es. Revisá el archivo."
        }

        var finalText = text
        var truncated = raw.truncated
        if (finalText.length > limits.maxChars) {
            finalText = finalText.take(limits.maxChars)
            truncated = true
            notes += "El texto extraído tiene más de ${limits.maxChars} caracteres; " +
                "se indexaron los primeros ${limits.maxChars}. Revisá el texto en «Editar texto»."
        }

        return raw.copy(
            text = finalText,
            warning = notes.joinToString(" "),
            language = raw.language.ifBlank { LanguageDetector.detect(finalText) },
            truncated = truncated,
            stats = raw.stats.copy(
                chars = finalText.length,
                words = finalText.split(Regex("\\s+")).count { it.isNotBlank() },
            ),
        )
    }
}
```

---

## 3. Dónde vive el código (source sets) y cómo se resuelve PDFBox

### 3.1 Hecho verificado (corregir la premisa habitual)

> **`com.tom-roush:pdfbox-android` NO publica una variante JVM.**

Verificado contra Maven Central el 21-10-2026:

| Artefacto | Packaging | ¿Variante JVM? | Paquetes |
|---|---|---|---|
| `com.tom-roush:pdfbox-android:2.0.27.0` | **`aar`** | **No** (sólo `.aar` + `-sources.jar` + `-javadoc.jar`) | `com.tom_roush.pdfbox.*` |
| `org.apache.pdfbox:pdfbox:2.0.37` | `jar` | Sí | `org.apache.pdfbox.*` |

Consecuencias, todas importantes:

1. **No puede ir en `commonMain`**: `commonMain` no tiene artefactos de
   plataforma; cualquier dependencia declarada ahí se propaga a **todos** los
   targets, incluido el target JVM de escritorio, que **no puede consumir un
   AAR**.
2. **No puede ir en el target `desktop`**: Gradle busca un `.jar` para el
   `compileClasspath` de un target JVM y el AAR no lo tiene.
3. **Los paquetes son distintos** (`com.tom_roush.pdfbox` vs `org.apache.pdfbox`),
   así que el código **no se puede compartir tal cual** aunque se pudiera
   depender del mismo artefacto.
4. `pdfbox-android` está clavado en **PDFBox 2.0.27** (su README lo dice) y
   **pide API 19 o superior** → compatible con nuestro `minSdk = 24` ✓.
5. Por eso el escritorio se queda en la **línea 2.0.x** (`2.0.37`, la última
   2.0.x) para que las dos plataformas tengan la **misma API**: PDFBox 3.x
   renombró `PDDocument.load` por `Loader.loadPDF` y cambió `TextPosition`;
   saltar a 3.x en el escritorio rompería el paralelismo con Android.

### 3.2 La estructura de source sets

```
:core
├── src/commonMain/kotlin/kitoru/material/          ← TODO EL ALGORITMO (Kotlin puro)
│   ├── TextExtractor.kt              TextExtractor, ExtractionResult, ExtractionStats
│   ├── ExtractionErrorCode.kt        ExtractionErrorCode, MaterialExtractionException
│   ├── ExtractionLimits.kt           ExtractionLimits
│   ├── MaterialFormat.kt             MaterialFormat
│   ├── MaterialFormatDetector.kt     MaterialFormatDetector
│   ├── ExtractorRegistry.kt          ExtractorRegistry
│   ├── MaterialExtractionService.kt  MaterialExtractionService (orquestador)
│   ├── plain/PlainTextExtractor.kt   .md .txt .rst .adoc
│   ├── plain/Encoding.kt             UTF-8/UTF-16/Latin-1 + BOM
│   ├── plain/RstConverter.kt         títulos subrayados → Markdown
│   ├── plain/AdocConverter.kt        títulos = → Markdown
│   ├── html/HtmlExtractor.kt         parser HTML propio
│   ├── csv/CsvExtractor.kt           detección de separador + tabla Markdown
│   ├── pdf/PdfBackend.kt             PdfBackend, PdfTextRun, PdfContent (interfaz)
│   ├── pdf/PdfAssembler.kt           líneas, columnas, escaneo ← ALGORITMO
│   ├── pdf/PdfPageCleaner.kt         números de página + encabezados repetidos
│   ├── clean/TextCleaner.kt          limpieza común (whitespace, guiones, ligaduras)
│   ├── clean/RepeatedHeaderDetector.kt
│   ├── clean/PageNumberStripper.kt
│   ├── lang/LanguageDetector.kt      heurística es/en
│   ├── office/XmlScanner.kt          escáner XML en Kotlin puro
│   └── link/LinkFetcher.kt           interfaz de descarga (Ktor)
│
├── src/jvmCommonMain/kotlin/kitoru/material/       ← java.util.zip (JVM **y** Android)
│   ├── ZipReader.kt                  ZipInputStream, protección zip-bomb
│   ├── JvmTextExtractor.kt           despacho: ZIP + PdfBackend + los de common
│   ├── office/DocxExtractor.kt       §4.3
│   ├── office/DocxStyles.kt          estilos → nivel de encabezado
│   ├── office/DocxNumbering.kt       numId → viñeta/ordinal
│   ├── office/PptxExtractor.kt       §4.4
│   ├── office/XlsxExtractor.kt       §4.5
│   └── office/OoxmlRels.kt           lector de .rels
│
├── src/desktopMain/kotlin/kitoru/material/         ← pdfbox (JVM)
│   ├── DesktopPdfBackend.kt          org.apache.pdfbox.*
│   └── DesktopTextExtractor.kt       actual platformTextExtractor()
│
└── src/androidMain/kotlin/kitoru/material/         ← pdfbox-android (AAR)
    ├── AndroidPdfBackend.kt          com.tom_roush.pdfbox.*
    └── AndroidTextExtractor.kt       actual platformTextExtractor()
```

### 3.3 Dependencias Gradle

```kotlin
// :core/build.gradle.kts (extracto)
kotlin {
    sourceSets {
        // … commonMain / jvmCommonMain / desktopMain / androidMain (ya existen) …

        desktopMain.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
            // PDFBox 2.0.x: el mismo código que en Android, distinto paquete.
            implementation(libs.pdfbox)                    // org.apache.pdfbox:pdfbox:2.0.37
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            // AAR. Sólo Android: no hay variante JVM.
            implementation(libs.pdfbox.android)            // com.tom-roush:pdfbox-android:2.0.27.0
        }
    }
}

// gradle/libs.versions.toml
// pdfbox = { module = "org.apache.pdfbox:pdfbox", version.ref = "pdfbox" }
// pdfbox-android = { module = "com.tom-roush:pdfbox-android", version.ref = "pdfboxAndroid" }
```

```toml
[versions]
pdfbox = "2.0.37"
pdfboxAndroid = "2.0.27.0"
```

Notas importantes:

- **`implementation`, no `api`**: los tipos de PDFBox **nunca** aparecen en una
  API pública (la costura `PdfBackend` los esconde) → no se filtran al
  `commonMain` de los consumidores ni al ABI.
- **El `jvmCommonMain` no necesita ninguna dependencia**: `java.util.zip` está
  en el JDK de escritorio **y** en `android.jar` (API 1+). Por eso `ZipReader`
  vive ahí y no en `commonMain`.
- `java.util.zip.ZipInputStream` en Android: ✓ confirmado (no necesita
  desugaring; `minSdk 24` ya cubre `java.util.zip` entero).

### 3.4 Alternativas descartadas (y por qué)

| Alternativa | Por qué no |
|---|---|
| Meter `pdfbox-android` en `commonMain` | No hay variante JVM → rompe el target de escritorio (§3.1) |
| Meter `pdfbox-android` en `desktopMain` | Es un AAR; el target JVM pide un `.jar`. Además `PDFBoxResourceLoader.init(Context)` es Android-only: sin él no cargan las fuentes |
| Descomprimir el `classes.jar` del AAR y usarlo en escritorio | Requiere un hack de Gradle (descomprimir el AAR en cada build) y el port depende del `AssetManager` de Android para las fuentes → no funciona en escritorio |
| Usar `org.apache.pdfbox:pdfbox` (JVM) **también** en Android | PDFBox 2.0 arrastra referencias a `java.awt` y `javax.imageio` (en `PDFRenderer`, `ImageType`…). D8 avisa y el dexing es frágil. El port de Tom Roush existe justamente para eso |
| Usar `org.apache.pdfbox:pdfbox:3.x` | Cambia la API (`Loader.loadPDF`) y `pdfbox-android` sigue en 2.0.27 → dos APIs distintas para mantener |
| Apache POI para docx/xlsx | No corre en Android (XMLBeans, `commons-io`, `javax.imageio`, `java.awt`), pesa ~15 MB. Parseo manual: §4.3–4.5 |
| `docx4j` | Mismo problema que POI + pesado |
| Ktor HTML parser | `ktor-client-html` es **JVM-only**; no sirve en `commonMain`. Parser propio: §4.6 |
| Apache Tika | Android-incompatible, enorme, y trae el doble de lo que necesitamos |
| OCR en el hito 1 | 30–90 s por PDF de 200 páginas en un teléfono. Se deja la costura (§4.2.4), no la implementación |

### 3.5 La costura `PdfBackend` (lo único específico de plataforma)

```kotlin
// core/src/commonMain/kotlin/kitoru/material/pdf/PdfBackend.kt
package kitoru.material.pdf

import kitoru.platform.FilePath

/**
 * Un fragmento de texto con su caja en la página.
 *
 * Sistema de coordenadas **ajustado a la dirección de lectura**: `x` crece
 * hacia la derecha, `y` crece **hacia abajo**, y el origen es la esquina
 * superior izquierda del área de la página. Es el mismo criterio que usan
 * `TextPosition.getXDirAdj()` / `getYDirAdj()` de PDFBox, así que las dos
 * implementaciones translates sin conversión.
 */
data class PdfTextRun(
    val text: String,
    /** Borde izquierdo. */
    val x: Float,
    /** Borde superior. */
    val y: Float,
    /** Ancho del run. */
    val width: Float,
    /** Alto de la caja (tamaño de fuente). */
    val height: Float,
) {
    val endX: Float get() = x + width
    val bottom: Float get() = y + height
}

/** Metadatos del PDF. */
data class PdfMetadata(
    val title: String = "",
    val author: String = "",
    val producer: String = "",
)

/**
 * Lo que la plataforma sabe leer de un PDF.
 *
 * `pages` es una lista de páginas; cada página es la lista de sus runs de
 * texto **en el orden en que los emitió el PDF**, que no es necesariamente el
 * orden de lectura (ver §4.2.3).
 */
data class PdfContent(
    val metadata: PdfMetadata = PdfMetadata(),
    val pages: List<List<PdfTextRun>> = emptyList(),
)

/**
 * Lector de PDF de la plataforma.
 *
 * Es la **única** interfaz que necesita dos implementaciones. Todo el
 * algoritmo (líneas, columnas, escaneo, limpieza, avisos) vive en
 * `commonMain` y se testea sin JVM.
 */
interface PdfBackend {
    /**
     * Lee el PDF completo.
     *
     * @throws PdfReadException con mensaje en español.
     */
    fun read(file: FilePath): PdfContent
}

/** Error de lectura de PDF, ya en español. */
class PdfReadException(
    val code: ExtractionErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
```

Las dos implementaciones son **estructuralmente idénticas** (30 líneas cada
una) y sólo cambian los paquetes:

```kotlin
// core/src/desktopMain/kotlin/kitoru/material/DesktopPdfBackend.kt
package kitoru.material

import com.tom_roush.pdfbox.pdmodel.PDDocument            // ← no: en desktop es org.apache
```

```kotlin
// core/src/desktopMain/kotlin/kitoru/material/DesktopPdfBackend.kt  (real)
package kitoru.material

import kitoru.material.pdf.PdfBackend
import kitoru.material.pdf.PdfContent
import kitoru.material.pdf.PdfMetadata
import kitoru.material.pdf.PdfTextRun
import kitoru.platform.FilePath
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

class DesktopPdfBackend : PdfBackend {

    override fun read(file: FilePath): PdfContent {
        val f = java.io.File(file.value)
        if (!f.exists()) {
            throw PdfReadException(
                ExtractionErrorCode.NOT_FOUND,
                "El archivo original ya no está en el disco. Volvé a subirlo para poder reextraerlo.",
            )
        }
        return try {
            // PDFBox 2.0.x: `Loader` es de 3.0. `setupMainMemoryOnly()` evita
            // que PDFBox escriba archivos temporales (importante en Android,
            // donde java.io.tmpdir puede no ser escribible).
            PDDocument.load(f, "", MemoryUsageSetting.setupMainMemoryOnly()).use { doc ->
                val stripper = RunStripper()
                val pages = (0 until doc.numberOfPages).map { i ->
                    stripper.startPage = i + 1
                    stripper.endPage = i + 1
                    stripper.runs.clear()
                    stripper.getText(doc)          // dispara writeString()
                    stripper.runs.toList()
                }
                val info = doc.documentInformation
                PdfContent(
                    metadata = PdfMetadata(
                        title = info?.title.orEmpty(),
                        author = info?.author.orEmpty(),
                        producer = info?.producer.orEmpty(),
                    ),
                    pages = pages,
                )
            }
        } catch (e: org.apache.pdfbox.exception.InvalidPasswordException) {
            throw PdfReadException(
                ExtractionErrorCode.ENCRYPTED,
                "El PDF está protegido con contraseña. Desprotegelo y volvé a subirlo.",
                e,
            )
        } catch (e: java.io.IOException) {
            throw PdfReadException(
                ExtractionErrorCode.CORRUPT,
                "No se pudo leer el PDF: ${e.message ?: "archivo dañado"}. " +
                    "Probá abrirlo de nuevo y guardarlo otra vez.",
                e,
            )
        }
    }
}

/**
 * Captura los runs de texto con su geometría.
 *
 * `writeString` la llama PDFBox **una vez por línea** (con la lista de
 * posiciones de todos sus glifos). No escribimos el texto: sólo lo medimos.
 */
private class RunStripper : PDFTextStripper() {
    val runs = mutableListOf<PdfTextRun>()

    init {
        sortByPosition = false          // el orden lo armamos nosotros (§4.2.3)
        addMoreFormatting = false        // sin saltos de línea extra
        wordSeparator = " "              // no lo usamos, pero evita ruido
    }

    override fun writeString(text: List<TextPosition>) {
        if (text.isEmpty()) return
        val sb = StringBuilder(text.size)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (tp in text) {
            val u = tp.unicode ?: continue
            sb.append(u)
            val x = tp.xDirAdj
            val y = tp.yDirAdj
            val w = tp.widthDirAdj
            val h = tp.heightDir
            if (x < minX) minX = x
            if (x + w > maxX) maxX = x + w
            if (y < minY) minY = y
            if (y + h > maxY) maxY = y + h
        }
        if (sb.isEmpty() || minX > maxX) return
        runs += PdfTextRun(sb.toString(), minX, minY, maxX - minX, maxY - minY)
    }
}
```

```kotlin
// core/src/androidMain/kotlin/kitoru/material/AndroidPdfBackend.kt
package kitoru.material

// Idéntico, con los paquetes del port:
//   com.tom_roush.pdfbox.pdmodel.PDDocument
//   com.tom_roush.pdfbox.io.MemoryUsageSetting
//   com.tom_roush.pdfbox.text.PDFTextStripper / TextPosition
//   com.tom_roush.pdfbox.exception.InvalidPasswordException
```

**Dos obligations en Android** (en el módulo `:android`, `Application.onCreate`):

```kotlin
// android/src/main/kotlin/.../KitoruApplication.kt
class KitoruApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 1) PDFBox-Android carga sus recursos (fuentes) desde el AssetManager:
        //    sin esto, leer un PDF con fuentes embebidas falla.
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)
        // 2) PDFBox usa java.io.tmpdir para PDFs cifrados; en Android puede no
        //    ser escribible. Se apunta al caché de la app.
        System.setProperty(
            "java.io.tmpdir",
            File(cacheDir, "tmp").apply { mkdirs() }.absolutePath,
        )
    }
}
```

---

## 4. Formato por formato

### 4.0 Panorama

| Ext | Formato | Librería | Licencia | ¿Android? | Extractor | Hito 1 |
|---|---|---|---|---|---|---|
| `md`, `markdown`, `mdx` | Markdown | ninguna (Kotlin puro) | — | ✓ | `plain-text` | ✅ |
| `txt`, `text`, `log` | Texto plano | ninguna | — | ✓ | `plain-text` | ✅ |
| `rst` | reStructuredText | ninguna | — | ✓ | `plain-text` (+ `RstConverter`) | ✅ |
| `adoc`, `asciidoc` | AsciiDoc | ninguna | — | ✓ | `plain-text` (+ `AdocConverter`) | ✅ |
| `pdf` | PDF | desktop `org.apache.pdfbox:pdfbox:2.0.37` / Android `com.tom-roush:pdfbox-android:2.0.27.0` | **Apache-2.0** (+ BouncyCastle MIT como transitiva) | ✓ (API 19+) | `pdfbox` | ✅ |
| `docx` | Word (OOXML) | **parseo manual** ZIP+XML | — | ✓ (`java.util.zip`) | `docx-zip` | ✅ |
| `pptx` | PowerPoint (OOXML) | **parseo manual** ZIP+XML | — | ✓ | `pptx-zip` | ✅ |
| `xlsx` | Excel (OOXML) | **parseo manual** ZIP+XML | — | ✓ | `xlsx-zip` | ✅ |
| `html`, `htm`, `xhtml` | HTML | parser propio | — | ✓ | `html` | ✅ |
| `csv`, `tsv` | CSV | parser propio | — | ✓ | `csv` | ✅ |
| `epub` | EPUB | (ZIP+OPF+XHTML) | — | ✓ | — | ❌ (aviso) |
| `doc`, `xls`, `ppt` | OLE2 (Word 97 / Excel 2003) | — | — | — | — | ❌ (aviso) |
| `rtf` | RTF | — | — | — | — | ❌ (aviso) |
| `odt`, `odp`, `ods` | ODF | — | — | — | — | ❌ (aviso) |
| `png`, `jpg`… | Imágenes | — | — | — | — | ❌ (aviso + OCR futuro) |
| — | URL | Ktor client | Apache-2.0 | ✓ | `link` | ✅ |

**Sobre las licencias.** PDFBox es **Apache-2.0** y `pdfbox-android` también
(la licencia del port es la del PDFBox original). BouncyCastle (dependencia
transitiva de `pdfbox-android`) usa la licencia Bouncy Castle (MIT-like). No
hay copyleft en ninguna de las dos → se pueden distribuir sin problemas. Lo
único que hay que cumplir es la atribución: incluir un aviso "PDFBox ©
The Apache Software Foundation" en la pantalla "Acerca de" de la app, y
reproducir la licencia si se redistribuye el binario.

**Sobre el nombre del grupo de Maven.** Es `com.tom-roush` (**con guion**), no
`com.tom_roush`. El **paquete** Java sí lleva guion bajo: `com.tom_roush.pdfbox`.
Es el error clásico.

---

### 4.1 Texto plano, Markdown, RST y AsciiDoc

```kotlin
// core/src/commonMain/kotlin/kitoru/material/plain/PlainTextExtractor.kt
package kitoru.material.plain

import kitoru.material.ExtractionResult
import kitoru.material.TextExtractor
import kitoru.platform.FilePath

/**
 * `.md`, `.txt`, `.rst`, `.adoc`.
 *
 * El Markdown se conserva **tal cual**: es lo que el `Chunker` de `:backend`
 * sabe cortar por encabezados. Un `.md` con `#` se indexa mejor que un `.txt`
 * con el mismo contenido.
 */
object PlainTextExtractor : TextExtractor {

    override val id = "plain-text"
    override val extensions = setOf("md", "markdown", "mdx", "txt", "text", "log", "rst", "adoc", "asciidoc")
    override val mimes = setOf("text/plain", "text/markdown", "text/x-markdown", "text/x-rst", "text/asciidoc")

    override suspend fun extract(file: FilePath, mime: String): ExtractionResult {
        val bytes = file.readBytes()          // FileSystem.readBytes (nuevo, §9)
        val decoded = Encoding.decode(bytes)  // UTF-8 / UTF-16 / Latin-1 + BOM
        var text = decoded.text
        val notes = mutableListOf<String>()

        when (file.extension) {
            "rst" -> text = RstConverter.toMarkdown(text)
            "adoc", "asciidoc" -> text = AdocConverter.toMarkdown(text)
        }

        if (decoded.fallbackUsed) {
            notes += "El archivo no estaba en UTF-8; se leyó como ${decoded.charsetName}. Revisá que no haya caracteres raros."
        }

        return ExtractionResult(
            text = text,
            title = firstHeading(text),
            warning = notes.joinToString(" "),
            extractor = id,
        )
    }

    /** El primer encabezado Markdown del archivo sirve de título. */
    private fun firstHeading(text: String): String =
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("#") }
            ?.trimStart('#')
            ?.trim()
            .orEmpty()
}
```

**Detección de encoding y BOM** (`Encoding.decode`):

```kotlin
// core/src/commonMain/kotlin/kitoru/material/plain/Encoding.kt
package kitoru.material.plain

/**
 * Decodificación de texto plano.
 *
 * Orden: BOM primero, después UTF-8 estricto, después Latin-1 (que nunca
 * falla, así que es el último recurso).
 */
object Encoding {

    data class Decoded(
        val text: String,
        val charsetName: String,
        /** True si hubo que recurrir a un fallback (no era UTF-8 válido). */
        val fallbackUsed: Boolean,
        /** True si venía un BOM (se quitó del texto). */
        val hadBom: Boolean = false,
    )

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun decode(bytes: ByteArray): Decoded {
        // 1) BOM UTF-8
        if (bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals(UTF8_BOM)) {
            return Decoded(
                bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8),
                "UTF-8", fallbackUsed = false, hadBom = true,
            )
        }
        // 2) BOM UTF-16 LE / BE
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Decoded(
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE),
                "UTF-16LE", fallbackUsed = false, hadBom = true,
            )
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Decoded(
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE),
                "UTF-16BE", fallbackUsed = false, hadBom = true,
            )
        }
        // 3) UTF-8 estricto
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            Decoded(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString(), "UTF-8", fallbackUsed = false)
        } catch (_: java.nio.charset.CharacterCodingException) {
            // 4) Latin-1: no falla nunca, pero conserva el byte como carácter.
            Decoded(bytes.toString(Charsets.ISO_8859_1), "Latin-1", fallbackUsed = true)
        }
    }
}
```

> **Ojo con `Charsets.UTF_8.newDecoder()`**: `java.nio.charset` **no** está en
> `commonMain` de Kotlin (es API de JVM). Por eso `Encoding` va en
> **`jvmCommonMain`**, no en `commonMain` — igual que `ZipReader`. La
> alternativa portable es decodificar UTF-8 a mano, que no vale la pena.
> Lo que **sí** es `commonMain` es `RstConverter` y `AdocConverter`.

**RST → Markdown** (`RstConverter`): los títulos de reStructuredText van
subrayados (`====`, `----`, `~~~~`, `^^^^`), no con `#`.

```
 título
=======            →  # título
 subtítulo
---------          →  ## subtítulo
```

**ADOC → Markdown** (`AdocConverter`):

```
= Título            →  # Título
== Sección          →  ## Sección
* ítem              →  - ítem
. ítem              →  - ítem
```

Ambos son conversiones **de una sola pasada y sin dependencias**. Si el
archivo ya tiene `#`, no se toca.

---

### 4.2 PDF

#### 4.2.1 Por qué PDFBox y no otra cosa

| Librería | Android | Licencia | Veredicto |
|---|---|---|---|
| `org.apache.pdfbox:pdfbox` | ❌ (`java.awt`, `javax.imageio`) | Apache-2.0 | Sólo escritorio |
| `com.tom-roush:pdfbox-android` | ✅ (API 19+) | Apache-2.0 | Sólo Android |
| `itext` (`com.lowagie:itext`) | ❌ | AGPL / comercial | Fuera (AGPL) |
| `pdfium` (via JNI) | ✅ | BSD | Requiere binario nativo por ABI → descarta |
| `PdfBox-Android` + `PDFRenderer` | parcial | Apache-2.0 | No renderiza (el port no trae `java.awt`) |

Decisión: **PDFBox 2.0.x en las dos plataformas**, con dos implementaciones
chicas de `PdfBackend` (§3.5) y **todo el algoritmo en `commonMain`**.

#### 4.2.2 Extracción página por página

El bucle es por página, y cada página produce:

1. Sus **runs** (`PdfTextRun`), con geometría.
2. Sus **líneas**, agrupando runs por solapamiento vertical.
3. Su **texto**, con los números de página y los encabezados repetidos ya
   eliminados (§4.2.5, §5).
4. Una marca `## Página N` en el texto final, que el `Chunker` usa como
   encabezado → **las citas quedan "cátedra: Apuntes · Página 7"**.

```kotlin
// core/src/commonMain/kotlin/kitoru/material/pdf/PdfAssembler.kt
package kitoru.material.pdf

import kitoru.material.ExtractionResult

/**
 * Convierte los runs con geometría en texto con orden de lectura.
 *
 * Todo esto es Kotlin puro: se testea en `commonTest` con runs sintéticos, sin
 * necesidad de un PDF real ni de una JVM.
 */
object PdfAssembler {

    /** Caracteres mínimos para que una página se considere "con texto". */
    const val MIN_PAGE_CHARS = 20

    data class Page(
        val number: Int,
        val lines: List<String>,
        /** 1 = una columna, 2 = dos columnas. */
        val columns: Int,
    )

    data class Assembly(
        val pages: List<Page>,
        /** El PDF es un escaneo (o lo es en su mayoría). */
        val needsOcr: Boolean,
        /** Páginas sin texto (escaneadas o vacías). */
        val emptyPages: Int,
    )

    fun assemble(content: PdfContent): Assembly {
        val pages = content.pages.mapIndexed { i, runs ->
            val lines = toLines(runs)
            val gutter = detectGutter(lines)
            val ordered = readingOrder(lines, gutter)
            Page(number = i + 1, lines = ordered, columns = if (gutter == null) 1 else 2)
        }
        val empty = pages.count { p -> p.lines.joinToString(" ").length < MIN_PAGE_CHARS }
        val scanned = pages.isNotEmpty() && empty.toDouble() / pages.size >= 0.6
        return Assembly(pages, scanned, empty)
    }
}
```

**Paso a paso: de los runs a las líneas.**

```kotlin
/**
 * Agrupa los runs en líneas.
 *
 * Dos runs están en la misma línea si sus cajas verticales se solapan al
 * menos un 50 % de la altura del más chico. Es robusto frente a fuentes de
 * tamaños distintos (un título en negrita más grande no parte la línea en dos).
 */
private fun groupLines(runs: List<PdfTextRun>): List<List<PdfTextRun>> {
    val sorted = runs.sortedBy { it.y }
    val lines = mutableListOf<List<PdfTextRun>>()
    var current = mutableListOf<PdfTextRun>()
    var top = 0f
    var bottom = 0f
    for (r in sorted) {
        if (current.isEmpty()) {
            current.add(r); top = r.y; bottom = r.bottom
            continue
        }
        val overlap = minOf(bottom, r.bottom) - maxOf(top, r.y)
        val minHeight = minOf(bottom - top, r.height).coerceAtLeast(0.01f)
        if (overlap / minHeight >= 0.5f) {
            current.add(r)
            top = minOf(top, r.y); bottom = maxOf(bottom, r.bottom)
        } else {
            lines += current
            current = mutableListOf(r); top = r.y; bottom = r.bottom
        }
    }
    if (current.isNotEmpty()) lines += current
    return lines
}

/**
 * Parte un run en sub-runs cuando tiene un hueco horizontal enorme.
 *
 * Algunos PDFs escriben la columna izquierda y la derecha en la misma
 * operación de texto; sin partir, esa línea parecería "de ancho completo" y
 * rompería la detección de columnas.
 */
private fun splitWideRuns(runs: List<PdfTextRun>): List<PdfTextRun> {
    val out = mutableListOf<PdfTextRun>()
    for (r in runs) {
        if (r.text.length < 40) { out += r; continue }
        // (heurística: si el texto tiene muchos espacios internos, se parte)
        val pieces = r.text.split(Regex(" {3,}"))
        if (pieces.size <= 1) { out += r; continue }
        val total = pieces.sumOf { it.length }.coerceAtLeast(1)
        var x = r.x
        for ((i, p) in pieces.withIndex()) {
            val w = r.width * (p.length.toFloat() / total)
            if (p.isNotBlank()) out += PdfTextRun(p, x, r.y, w, r.height)
            x += w
        }
    }
    return out
}

/** Texto de una línea: los runs ordenados por x, con espacios en los huecos. */
private fun lineText(runs: List<PdfTextRun>): String {
    val sb = StringBuilder()
    var prevEnd = Float.NaN
    for (r in runs.sortedBy { it.x }) {
        if (!prevEnd.isNaN()) {
            val gap = r.x - prevEnd
            // Un hueco mayor a ~1/5 del alto de la fuente es un espacio.
            if (gap > 0.2f * r.height) sb.append(' ')
        }
        sb.append(r.text)
        prevEnd = r.endX
    }
    return sb.toString().trim()
}
```

#### 4.2.3 Orden de lectura y PDF de dos columnas — **el punto crítico**

> **El orden de lectura importa más que la calidad del texto.**
> Un PDF de dos columnas leído en el orden equivocado produce un texto donde
> cada línea del textoSalto de la columna izquierda se entrelaza con la
> derecha. El BM25 no lo nota (indexa las mismas palabras), pero el **modelo**
> recibe fragmentos donde la mitad de las frases no tienen sentido: la cita
> verificada va a fallar y el alumno recibe basura.

Por eso el extractor **no confía en el orden del PDF**: recalcula el orden de
lectura a partir de la geometría.

**Paso 1 — detectar el *gutter* (el canal entre columnas).**

```kotlin
/**
 * Devuelve la abscisa del corte entre columnas, o `null` si la página es de
 * una sola columna.
 *
 * Criterio: existe una banda vertical sin texto que
 *  - tenga al menos `max(8pt, 2 % del ancho del texto)` de ancho,
 *  - no sea cruzada por más del 30 % de las líneas, y
 *  - deje al menos 3 líneas completamente a la izquierda y 3 a la derecha.
 *
 * El último criterio es el que evita el falso positivo clásico: una página de
 * una sola columna con un gráfico al centro tiene un "gutter" enorme, pero no
 * hay texto a la derecha.
 */
fun detectGutter(lines: List<List<PdfTextRun>>): Float? {
    if (lines.size < 6) return null
    val all = lines.flatten()
    val minX = all.minOf { it.x }
    val maxX = all.maxOf { it.endX }
    val textWidth = maxX - minX
    if (textWidth <= 1f) return null

    // Histograma de ocupación en x (400 frascos).
    val step = textWidth / 400f
    val buckets = IntArray(401)
    for (r in all) {
        val from = ((r.x - minX) / step).toInt().coerceIn(0, 400)
        val to = ((r.endX - minX) / step).toInt().coerceIn(0, 400)
        for (i in from..to) buckets[i]++
    }

    // Bandas vacías.
    val gaps = mutableListOf<Pair<Int, Int>>()
    var i = 0
    while (i <= 400) {
        if (buckets[i] == 0) {
            val start = i
            while (i <= 400 && buckets[i] == 0) i++
            gaps += start to (i - 1)
        } else i++
    }
    if (gaps.isEmpty()) return null

    val minGapWidth = maxOf(8f * step, 0.02f * textWidth)

    return gaps
        .filter { (a, b) -> (b - a) * step >= minGapWidth }
        .map { (a, b) ->
            val g0 = minX + a * step
            val g1 = minX + (b + 1) * step
            val crossing = lines.count { line -> line.any { it.x < g1 && it.endX > g0 } }
            val left = lines.count { line -> line.all { it.endX <= g0 } }
            val right = lines.count { line -> line.all { it.x >= g1 } }
            Triple(g0, g1, crossing to (left to right))
        }
        .firstOrNull { (_, _, (crossing, sides)) ->
            val (left, right) = sides
            left >= 3 && right >= 3 && crossing.toDouble() / lines.size <= 0.3
        }
        ?.let { (g0, g1) -> (g0 + g1) / 2f }
}
```

**Paso 2 — reordenar en orden de lectura.**

```kotlin
/**
 * Orden de lectura.
 *
 * - Sin gutter: de arriba abajo (y de izquierda a derecha dentro de la línea).
 * - Con gutter: se separa la página en **bandas** delimitadas por las líneas
 *   de **ancho completo** (los títulos a dos columnas, que son el único
 *   elemento que cruza el canal). Dentro de cada banda se lee la columna
 *   izquierda completa y después la derecha.
 */
fun readingOrder(lines: List<List<PdfTextRun>>, gutter: Float?): List<String> {
    if (gutter == null) return lines.map { lineText(it) }

    val out = mutableListOf<String>()
    var band = mutableListOf<List<PdfTextRun>>()

    for (line in lines) {
        val minX = line.minOf { it.x }
        val maxX = line.maxOf { it.endX }
        val isFullWidth = minX < gutter && maxX > gutter
        if (isFullWidth) {
            out += orderBand(band, gutter)
            band = mutableListOf()
            out += lineText(line)          // el título de ancho completo
        } else {
            band += line
        }
    }
    out += orderBand(band, gutter)
    return out
}

private fun orderBand(band: List<List<PdfTextRun>>, gutter: Float): List<String> {
    if (band.isEmpty()) return emptyList()
    val left = band.filter { line -> line.maxOf { it.endX } <= gutter }
    val right = band.filter { line -> line.minOf { it.x } >= gutter }
    return when {
        left.isEmpty() -> right.map { lineText(it) }
        right.isEmpty() -> left.map { lineText(it) }
        else -> left.map { lineText(it) } + right.map { lineText(it) }
    }
}
```

**Ejemplo de la diferencia** (lo que ve el modelo):

```
MAL (orden del PDF)              BIEN (orden de lectura)
  1. El perceptrón es                1. El perceptrón es una unidad
     una unidad artificial.             artificial de una red neuronal.
  2. Se entrena con el                2. Se entrena con el algoritmo
     algoritmo de peso.                 de peso.
  1. La red neuronal tiene            3. La red neuronal tiene capas.
     capas.                        4. La red neuronal tiene capas.
  2. Se entrena con el
     algoritmo de peso.
```

**Paso 3 — el texto final del PDF.**

```kotlin
/**
 * Une las páginas en el texto indexable.
 *
 * Las marcas `## Página N` no son decorativas: el `Chunker` de `:backend` corta
 * por encabezados Markdown, así que **cada página del PDF se convierte en
 * fragmentos con encabezado "Página N"** → las citas quedan
 * "cátedra: Apuntes-IA · Página 7" y el docente puede ubicar el contenido en
 * el PDF original con un clic.
 */
fun render(assembly: Assembly): String = buildString {
    for (page in assembly.pages) {
        val body = page.lines.filter { it.isNotBlank() }.joinToString("\n")
        append("## Página ").append(page.number).append("\n\n")
        append(body).append("\n\n")
    }
}.trim()
```

#### 4.2.4 PDF escaneado, PDF protegido, PDF vacío

```kotlin
// core/src/commonMain/kotlin/kitoru/material/pdf/PdfScanner.kt
package kitoru.material.pdf

import kitoru.material.ExtractionResult

/**
 * Decide el estado de un PDF a partir de su ensamblado.
 */
object PdfScanner {

    fun evaluate(content: PdfContent, assembly: PdfAssembler.Assembly): ExtractionResult {
        val total = assembly.pages.size
        val empty = assembly.emptyPages

        // 1) PDF sin páginas: está roto o es un contenedor vacío.
        if (total == 0) {
            throw PdfReadException(
                ExtractionErrorCode.CORRUPT,
                "El PDF no tiene páginas. Probá abrirlo de nuevo y guardarlo otra vez.",
            )
        }

        // 2) Escaneo: la mayoría de las páginas sin capa de texto.
        if (assembly.needsOcr) {
            return ExtractionResult(
                text = render(assembly),
                title = content.metadata.title,
                pages = total,
                needsOcr = true,
                warning = "Este PDF no tiene capa de texto ($empty de $total páginas tienen texto). " +
                    "Parece un escaneo: las imágenes no se pueden leer. " +
                    "Subilo desde el original con texto seleccionable, " +
                    "o pegá el texto a mano en «Editar texto».",
                extractor = "pdfbox",
            )
        }

        // 3) Escaneo parcial: hay páginas sin texto entre páginas con texto.
        val warning = if (empty > 0) {
            "$empty de $total páginas no tienen texto (parecen escaneadas). " +
                "El contenido de esas páginas no se pudo leer."
        } else ""

        return ExtractionResult(
            text = render(assembly),
            title = content.metadata.title,
            pages = total,
            warning = warning,
            extractor = "pdfbox",
        )
    }
}
```

> **El material NO se indexa.** `CourseMaterialSource` (que ya existe en
> `:backend`) filtra por `material.extractedText.isNotBlank()` → un PDF
> escaneado aporta 0 fragmentos. Eso es correcto: es preferible que la IA no
> tenga el material a que tenga un material vacío y responda "no lo sé" con
> seguridad. La UI de materiales (§5 de `teacher/03-materiales.md`) muestra el
> chip "Requiere revisión" y el texto del aviso.

**La costura del OCR (diseñada, no implementada).**

```kotlin
// core/src/commonMain/kotlin/kitoru/material/OcrEngine.kt
package kitoru.material

import kitoru.platform.FilePath

/**
 * Motor de OCR opcional.
 *
 * Hoy no hay implementación: la interfaz queda para que el motor (ML Kit
 * Text Recognition en Android, Tesseract en escritorio, o el OCR del modelo
 * multimodal de Kitoru-Server) se enchufe sin tocar el pipeline.
 */
interface OcrEngine {
    /** ¿Puede procesar este formato? */
    fun supports(format: MaterialFormat): Boolean

    /** Texto reconocido. Lanza [MaterialExtractionException] si falla. */
    suspend fun recognize(file: FilePath, format: MaterialFormat): String
}
```

- **Android**: `com.google.mlkit:text-recognition` (on-device, gratis, es/en) →
  `TextRecognizer` sobre las imágenes de cada página. Para renderizar la
  página: `android.graphics.pdf.PdfRenderer` (API 21+), que dibuja cada
  página en un `Bitmap`.
- **Escritorio**: Tesseract (`net.sourceforge.tess4j:tess4j`) o el OCR del
  modelo multimodal de Kitoru-Server.
- **Coste**: ~30–90 s por 200 páginas en un teléfono → el OCR es **opt-in y en
  segundo plano**, nunca parte del camino automático: el docente lo dispara
  con un botón "Reconocer texto (OCR)" en el detalle del material.
- El PDF original **se conserva**; el texto OCR se guarda aparte para que el
  docente pueda compararlo o editarlo.

#### 4.2.5 Encabezados, pies y números de página del PDF

Este es **el problema clásico de los PDF**. Un PDF de apuntes tiene, en cada
página:

```
Introduction to Machine Learning                     ← encabezado repetido
──────────────────────────────────────────────
… contenido …
12                                                ← número de página
```

Si eso queda en el texto, el BM25 indexa 12 veces "Introduction to Machine
Learning" y 12 veces "12", y el modelo recibe fragmentos que empiezan siempre
con el mismo ruido.

**Solución en dos partes:**

1. **Números de página** → se borran por patrón (`PageNumberStripper`).
2. **Encabezados/pies repetidos** → se detectan por frecuencia
   (`RepeatedHeaderDetector`).

Ambas operaciones necesitan saber **dónde está cada línea dentro de la
página** (arriba o abajo), por eso se hacen en `PdfPageCleaner`, que trabaja
sobre `List<List<String>>` (página → líneas), **antes** de unir las páginas.

```kotlin
// core/src/commonMain/kotlin/kitoru/material/clean/PageNumberStripper.kt
package kitoru.material.clean

/**
 * Reconoce líneas que son sólo un número de página.
 *
 * El patrón "sólo dígitos" **sólo** se aplica en las zonas de encabezado y
 * pie: un "2024" en el medio de una página es contenido.
 */
object PageNumberStripper {

    private val ONLY_DIGITS = Regex("^\\s*[\\u2013\\u2014-]?\\s*\\p{N}{1,4}\\s*[\\u2013\\u2014-]?\\s*$")

    private val NAMED = Regex(
        "^\\s*(p\\.?\\s*gina|pagina|página|page|fol\\.?|seite)\\s*\\p{N}{1,4}" +
            "(\\s*(de|of|/|de la)\\s*\\p{N}{1,4})?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** "12 | Nombre del Apunte" (pies típicos de LaTeX y Word). */
    private val PIPE = Regex("^\\s*\\p{N}{1,4}\\s*[|/]\\s*\\S.{0,90}$")

    /** ¿Es un número de página (en cualquier zona)? */
    fun isPageNumberAnywhere(line: String): Boolean =
        NAMED.matches(line) || PIPE.matches(line)

    /** ¿Es un número de página (sólo válido en la cabecera o el pie)? */
    fun isPageNumberInEdge(line: String): Boolean =
        ONLY_DIGITS.matches(line) || isPageNumberAnywhere(line)
}
```

El detector de repetidos (el "> 50 % ⇒ pie de página" del enunciado):

```kotlin
// core/src/commonMain/kotlin/kitoru/material/clean/RepeatedHeaderDetector.kt
package kitoru.material.clean

import kitoru.platform.stripDiacritics

/**
 * Detecta encabezados y pies que se repiten en la mayoría de las páginas.
 *
 * Regla: una línea que aparece en la **misma zona** (arriba o abajo) en más
 * del 50 % de las páginas es un encabezado o un pie de página → se borra de
 * todas las páginas.
 *
 * Se comparan las líneas **normalizadas** (minúsculas, sin diacríticos, sin
 * dígitos, sin puntuación, espacios colapsados) para que "Página 3" y
 * "Página 4" — que sólo difieren en el número — se reconozcan como la misma
 * línea.
 */
object RepeatedHeaderDetector {

    /** Cuántas líneas se miran arriba y abajo de cada página. */
    const val ZONE = 3

    /** Con menos páginas que esto, "repetido" no significa nada. */
    const val MIN_PAGES = 4

    /** Fracción de páginas a partir de la cual se considera repetido (> 50 %). */
    const val THRESHOLD = 0.5

    enum class Zone { HEAD, FOOT }

    data class Hit(val key: String, val zone: Zone, val pages: Int)

    /**
     * @param pages páginas, cada una con sus líneas.
     */
    fun detect(pages: List<List<String>>): Set<Hit> {
        if (pages.size < MIN_PAGES) return emptySet()
        val head = mutableMapOf<String, Int>()
        val foot = mutableMapOf<String, Int>()
        for (page in pages) {
            val lines = page.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            for (l in lines.take(ZONE)) key(l)?.let { head.merge(it, 1, Int::plus) }
            for (l in lines.takeLast(ZONE)) key(l)?.let { foot.merge(it, 1, Int::plus) }
        }
        // "> 50 %" → estrictamente mayor que la mitad.
        val minPages = (pages.size * THRESHOLD).toInt() + 1
        val hits = mutableSetOf<Hit>()
        head.filterValues { it >= minPages }.forEach { (k, n) -> hits += Hit(k, Zone.HEAD, n) }
        foot.filterValues { it >= minPages }.forEach { (k, n) -> hits += Hit(k, Zone.FOOT, n) }
        return hits
    }

    /**
     * Normaliza una línea para comparar.
     *
     * Se quitan los dígitos (por eso "Página 3 de 12" y "Página 7 de 12" son
     * la misma clave) y los acentos (por eso "Programación" y "Programacion"
     * son la misma clave).
     */
    fun key(line: String): String? {
        val k = stripDiacritics(line.lowercase())
            .replace(Regex("\\p{N}+"), " ")
            .replace(Regex("[^\\p{L}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return k.ifEmpty { null }?.takeIf { it.length >= 3 }
    }
}
```

Y el limpiador por página:

```kotlin
// core/src/commonMain/kotlin/kitoru/material/pdf/PdfPageCleaner.kt
package kitoru.material.pdf

import kitoru.material.clean.PageNumberStripper
import kitoru.material.clean.RepeatedHeaderDetector

/**
 * Limpia cada página del PDF: saca números de página y encabezados/pies
 * repetidos.
 */
object PdfPageCleaner {

    fun clean(pages: List<List<String>>): List<List<String>> {
        val repeated = RepeatedHeaderDetector.detect(pages)
        if (repeated.isEmpty()) {
            return pages.map { dropEdgeNumbers(it) }
        }
        val headKeys = repeated.filter { it.zone == RepeatedHeaderDetector.Zone.HEAD }.map { it.key }.toSet()
        val footKeys = repeated.filter { it.zone == RepeatedHeaderDetector.Zone.FOOT }.map { it.key }.toSet()

        return pages.map { page ->
            val lines = page.filter { it.isNotBlank() }
            val kept = lines.filterIndexed { index, line ->
                val k = RepeatedHeaderDetector.key(line)
                val inHead = index < RepeatedHeaderDetector.ZONE
                val inFoot = index >= lines.size - RepeatedHeaderDetector.ZONE
                // 1) número de página en los bordes → fuera
                if ((inHead || inFoot) && PageNumberStripper.isPageNumberInEdge(line)) return@filterIndexed false
                // 2) encabezado repetido en la cabecera → fuera
                if (inHead && k != null && k in headKeys) return@filterIndexed false
                // 3) pie repetido al pie → fuera
                if (inFoot && k != null && k in footKeys) return@filterIndexed false
                // 4) "Página 4 de 12" en cualquier posición → fuera
                if (PageNumberStripper.isPageNumberAnywhere(line)) return@filterIndexed false
                true
            }
            // Nunca dejar una página vacía si tenía contenido.
            if (kept.isEmpty() && lines.isNotEmpty()) lines else kept
        }
    }

    /**
     * Camino rápido: no hay encabezados repetidos, pero los números de página
     * hay que sacarlos igual.
     */
    private fun dropEdgeNumbers(page: List<String>): List<String> {
        val lines = page.filter { it.isNotBlank() }
        return lines.filterIndexed { index, line ->
            val inEdge = index < RepeatedHeaderDetector.ZONE ||
                index >= lines.size - RepeatedHeaderDetector.ZONE
            when {
                inEdge && PageNumberStripper.isPageNumberInEdge(line) -> false
                PageNumberStripper.isPageNumberAnywhere(line) -> false
                else -> true
            }
        }
    }
}
```

> **Ojo con un falso positivo famouso**: en un documento de 4 páginas donde
> cada página empieza con "1. Introducción", el detector ve la misma clave 4
> veces (> 50 %) y la borra. Se acepta: el contenido se pierde, pero el
> texto sigue siendo coherente y el costo es bajo comparado con dejar el
> ruido en todas las páginas. El guardia `MIN_PAGES = 4` evita el caso de un
> documento de 2 páginas, donde "repetido" es la mitad del documento.

---

### 4.3 DOCX — el formato más común de un profesor (detalle)

> Un `.docx` es un **ZIP** con XML adentro. No hace falta POI: hace falta un
> lector de ZIP y un lector de XML. Los dos son portables.

#### 4.3.1 Estructura interna

```
[Content_Types].xml
_rels/.rels
docProps/core.xml            <- dc:title  (el título del documento)
docProps/app.xml
word/document.xml            <- EL CUERPO
word/styles.xml              <- w:styleId -> w:name ("heading 1", "título 1")
word/numbering.xml           <- w:numId -> formato de lista (viñeta / ordinal)
word/_rels/document.xml.rels <- r:id -> URL (hipervínculos, imágenes)
word/media/image1.png        <- las imágenes (se ignoran)
word/header1.xml             <- encabezado de página (se ignora)
word/footer1.xml             <- pie de página (se ignora)
word/footnotes.xml           <- notas al pie (opcional: se anexan)
```

#### 4.3.2 El algoritmo, paso a paso

**Paso 0 — abrir el ZIP.** `ZipReader.read(bytes)` (§4.9 del código, más
abajo). Tope de 200 MB descomprimidos y 5 000 entradas (protección zip-bomb).

**Paso 1 — localizar el cuerpo.** `word/document.xml` debe existir. Si no:
error `CORRUPT` → *"El archivo .docx está dañado o no es un Word válido (falta
word/document.xml)."*

**Paso 2 — leer los estilos** (`word/styles.xml`) y resolver el **nivel de
encabezado** de cada estilo. Esto es lo que hace que el `Chunker` pueda
cortar por secciones.

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/material/office/DocxStyles.kt
package kitoru.material.office

import kitoru.material.office.XmlEvent

/**
 * Lee `word/styles.xml` y resuelve el nivel de encabezado de cada estilo.
 *
 * El truco importante: **no se puede decidir por el `styleId`**, porque Word
 * lo localiza. Un "Título 1" en un Word en español tiene `styleId="Ttulo1"`
 * (con la e acentuada eliminada), y en inglés `styleId="Heading1"`. Por eso
 * se lee el **nombre** del estilo (`w:name w:val="heading 1"`), que Word
 * escribe **en inglés incluso en una instalación en español**.
 *
 * Y mejor todavía: se usa `w:outlineLvl` (el nivel de esquema, 0-based), que
 * es el atributo que Word usa para el panel de navegación y que no depende
 * del idioma.
 */
class DocxStyles {

    data class Style(
        val id: String,
        val name: String,
        /** `w:outlineLvl` (0-based) si el estilo lo define. */
        val outlineLevel: Int? = null,
    )

    private val byId = mutableMapOf<String, Style>()

    /** Nivel de encabezado (1..6) del estilo, o 0 si no es un encabezado. */
    fun headingLevel(styleId: String?): Int {
        if (styleId.isNullOrBlank()) return 0
        val style = byId[styleId] ?: return 0
        // 1) outlineLvl: la señal más robusta (0..5 → 1..6; 9 = cuerpo de texto).
        style.outlineLevel?.let { return if (it in 0..5) it + 1 else 0 }
        // 2) nombre del estilo.
        return levelFromName(style.name) ?: levelFromName(style.id) ?: 0
    }

    private fun levelFromName(raw: String): Int? {
        val n = raw.lowercase().replace(" ", "")
        // "heading1", "título1" → "titulo1" (sin diacríticos se hace abajo)
        val ascii = n.map { if (it in 'a'..'z') it else null }.filterNotNull().joinToString("")
        val m = Regex("^(heading|titulo)(\\d)$").find(ascii) ?: return null
        val level = m.groupValues[2].toInt()
        return if (level in 1..6) level else null
    }

    companion object {
        fun read(zip: ZipReader): DocxStyles {
            val styles = DocxStyles()
            val xml = zip.readText("word/styles.xml") ?: return styles
            var currentId: String? = null
            var currentName: String? = null
            var currentOutline: Int? = null
            var inStyle = false
            var inName = false
            var inOutline = false
            for (ev in XmlScanner(xml).events()) {
                when (ev) {
                    is XmlEvent.Open -> when (ev.name) {
                        "w:style" -> { inStyle = true; currentId = ev.attrs["w:styleId"]; currentName = null; currentOutline = null }
                        "w:name" -> if (inStyle) { inName = true; currentName = ev.attrs["w:val"] }
                        "w:outlineLvl" -> if (inStyle) { inOutline = true; currentOutline = ev.attrs["w:val"]?.toIntOrNull() }
                    }
                    is XmlEvent.Close -> when (ev.name) {
                        "w:name" -> inName = false
                        "w:outlineLvl" -> inOutline = false
                        "w:style" -> {
                            inStyle = false
                            val id = currentId
                            if (id != null) {
                                styles.byId[id] = Style(id, currentName.orEmpty(), currentOutline)
                            }
                        }
                    }
                    is XmlEvent.Text, is XmlEvent.CData -> Unit
                }
            }
            return styles
        }
    }
}
```

> `levelFromName` normaliza a ASCII para que "título" y "titulo" den lo mismo
> (en `jvmCommonMain` se puede usar `stripDiacritics`, que ya existe en
> `:core`).

**Paso 3 — leer las relaciones** (`word/_rels/document.xml.rels`) para
resolver los hipervínculos:

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/material/office/OoxmlRels.kt
package kitoru.material.office

/**
 * Lee un archivo `.rels` (relsaciones OOXML).
 *
 * Formato: `<Relationship Id="rId5" Type=".../hyperlink" Target="https://…" TargetMode="External"/>`
 */
data class OoxmlRels(
    val byId: Map<String, String>,
    val typeById: Map<String, String>,
) {
    /** URL de un hipervínculo (o null). */
    fun url(id: String?): String? = id?.let { byId[it] }

    companion object {
        fun read(zip: ZipReader, path: String): OoxmlRels {
            val xml = zip.readText(path) ?: return OoxmlRels(emptyMap(), emptyMap())
            val byId = mutableMapOf<String, String>()
            val typeById = mutableMapOf<String, String>()
            for (ev in XmlScanner(xml).events()) {
                if (ev is XmlEvent.Open && ev.name == "Relationship") {
                    val id = ev.attrs["Id"] ?: continue
                    ev.attrs["Target"]?.let { byId[id] = it }
                    ev.attrs["Type"]?.let { typeById[id] = it }
                }
            }
            return OoxmlRels(byId, typeById)
        }
    }
}
```

**Paso 4 — leer la numeración** (`word/numbering.xml`) para saber si una
viñeta es "•" o "1.":

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/material/office/DocxNumbering.kt
package kitoru.material.office

/**
 * Lee `word/numbering.xml`.
 *
 * - `w:num w:numId="7" -> w:abstractNumId w:val="2"`: el numId apunta a una
 *   definición abstracta.
 * - `w:abstractNum` define, por nivel (`w:lvl`), el formato (`w:numFmt`:
 *   "bullet", "decimal", "lowerLetter"…) y el texto (`w:lvlText`).
 */
class DocxNumbering(
    private val formatByNumIdLevel: Map<Pair<String, Int>, String>,
) {
    /**
     * Marcador de lista para un párrafo.
     *
     * @param counters contadores compartidos para los ordinales.
     */
    fun marker(numId: String?, level: Int, counters: MutableMap<Pair<String, Int>, Int>): String? {
        if (numId.isNullOrBlank()) return null
        val format = formatByNumIdLevel[numId to level] ?: "bullet"
        return when (format) {
            "decimal" -> {
                val key = numId to level
                val n = counters.getOrDefault(key, 0) + 1
                counters[key] = n
                "$n. "
            }
            "lowerLetter" -> {
                val key = numId to level
                val n = counters.getOrDefault(key, 0) + 1
                counters[key] = n
                "${('a' + ((n - 1) % 26))}. "
            }
            "upperLetter" -> {
                val key = numId to level
                val n = counters.getOrDefault(key, 0) + 1
                counters[key] = n
                "${('A' + ((n - 1) % 26))}. "
            }
            "lowerRoman" -> "i. "
            "upperRoman" -> "I. "
            else -> "- "                       // bullet
        }
    }

    companion object {
        fun read(zip: ZipReader): DocxNumbering {
            val xml = zip.readText("word/numbering.xml") ?: return DocxNumbering(emptyMap())
            val abstractById = mutableMapOf<String, MutableMap<Int, String>>()
            var numToAbstract = mutableMapOf<String, String>()
            var currentAbstract: String? = null
            var currentLevel = 0
            var currentFormat: String? = null
            var inNumFmt = false
            for (ev in XmlScanner(xml).events()) {
                when (ev) {
                    is XmlEvent.Open -> when (ev.name) {
                        "w:abstractNum" -> currentAbstract = ev.attrs["w:abstractNumId"]
                        "w:num" -> { /* el abstractNumId se ve al cerrar */ }
                        "w:lvl" -> currentLevel = ev.attrs["w:ilvl"]?.toIntOrNull() ?: 0
                        "w:numFmt" -> inNumFmt = true
                    }
                    is XmlEvent.Text, is XmlEvent.CData -> if (inNumFmt && currentFormat == null) {
                        currentFormat = ev.value.trim()
                    }
                    is XmlEvent.Close -> when (ev.name) {
                        "w:numFmt" -> inNumFmt = false
                        "w:lvl" -> {
                            val a = currentAbstract
                            if (a != null) {
                                abstractById.getOrPut(a) { mutableMapOf() }[currentLevel] = currentFormat ?: "bullet"
                            }
                            currentFormat = null
                        }
                        "w:abstractNum" -> currentAbstract = null
                        "w:num" -> { /* nada */ }
                    }
                }
            }
            // segunda pasada: w:num -> abstractNumId
            var pendingNumId: String? = null
            for (ev in XmlScanner(xml).events()) {
                when (ev) {
                    is XmlEvent.Open -> when (ev.name) {
                        "w:num" -> pendingNumId = ev.attrs["w:numId"]
                        "w:abstractNumId" -> {
                            val n = pendingNumId
                            val a = ev.attrs["w:val"]
                            if (n != null && a != null) numToAbstract[n] = a
                        }
                    }
                    is XmlEvent.Close -> if (ev.name == "w:num") pendingNumId = null
                    else -> Unit
                }
            }
            val result = mutableMapOf<Pair<String, Int>, String>()
            for ((numId, absId) in numToAbstract) {
                val levels = abstractById[absId] ?: continue
                for ((lvl, fmt) in levels) result[numId to lvl] = fmt
            }
            return DocxNumbering(result)
        }
    }
}
```

**Paso 5 — el cuerpo: máquina de estados sobre `word/document.xml`.**

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/material/office/DocxExtractor.kt
package kitoru.material.office

import kitoru.material.ExtractionResult
import kitoru.material.MaterialExtractionException
import kitoru.material.ExtractionErrorCode

/**
 * Extrae el texto de un `.docx` parseando el ZIP y el XML a mano.
 *
 * El algoritmo completo está documentado en
 * `docs/design/rag/01-extraccion.md` §4.3.2.
 */
object DocxExtractor {

    /** Bloques del documento, en orden. */
    private sealed interface Block {
        data class Para(val text: String) : Block
        data class Heading(val level: Int, val text: String) : Block
        data class ListItem(val marker: String, val text: String) : Block
        data class Table(val rows: List<List<String>>) : Block
    }

    fun extract(bytes: ByteArray): ExtractionResult {
        val zip = ZipReader.read(bytes)                                   // (0)

        val documentXml = zip.readText("word/document.xml")              // (1)
            ?: throw MaterialExtractionException(
                ExtractionErrorCode.CORRUPT,
                "El archivo .docx está dañado o no es un Word válido (falta word/document.xml).",
            )

        val styles = DocxStyles.read(zip)                                // (2)
        val rels = OoxmlRels.read(zip, "word/_rels/document.xml.rels")   // (3)
        val numbering = DocxNumbering.read(zip)                          // (4)

        val body = Body(styles, rels, numbering)                         // (5)
        body.consume(XmlScanner(documentXml).events())                   // (6)
        val blocks = body.finish()                                       // (7)

        val text = render(blocks)                                        // (8)
        val title = readCoreTitle(zip)                                   // (9)

        val warning = buildString {
            if (body.imageCount > 0) {
                append("${body.imageCount} ")
                append(if (body.imageCount == 1) "imagen omitida" else "imágenes omitidas")
                append(" (el texto no incluye su contenido).")
            }
            if (body.footnoteCount > 0) {
                if (isNotEmpty()) append(' ')
                append("${body.footnoteCount} notas al pie anexadas al final.")
            }
        }

        return ExtractionResult(
            text = text,
            title = title,
            pages = 0,                 // un .docx no tiene páginas conocidas
            warning = warning,
            extractor = "docx-zip",
            stats = kitoru.material.ExtractionStats(
                chars = text.length,
                words = text.split(Regex("\\s+")).count { it.isNotBlank() },
                blocks = blocks.size,
                skippedImages = body.imageCount,
            ),
        )
    }
    ...
}
```

**El cuerpo (`Body`) es la máquina de estados.** Sus reglas, una por una:

| Evento XML | Acción |
|---|---|
| `<w:p>` | Cierra el párrafo anterior y abre uno nuevo (buffer, estilo, numeración) |
| `<w:pPr>` | Nada: sus hijos se leen |
| `<w:pStyle w:val="Ttulo1">` | Guarda el `styleId` del párrafo |
| `<w:numPr>` | Marca el párrafo como ítem de lista |
| `<w:ilvl w:val="0">` | Nivel de la lista (0 = principal) |
| `<w:numId w:val="3">` | Lista concreta |
| `<w:t>` | Activa la captura de texto |
| `</w:t>` | Desactiva la captura |
| `<w:tab>` | Añade un espacio |
| `<w:br/>`, `<w:cr/>` | Añade un salto de línea dentro del párrafo |
| `<w:noBreakHyphen/>` | Añade `-` |
| `<w:softHyphen/>` | No añade nada (y marca "unir") |
| `<w:sym>` | Se ignora (fuentes de símbolos) |
| `<w:drawing>`, `<w:pict>`, `<w:object>` | Cuenta una imagen; se lee su `wp:docPr@descr` (texto alternativo) |
| `<w:hyperlink r:id="rId5">` | Guarda el id del vínculo |
| `</w:hyperlink>` | Añade ` (url)` al final del texto del vínculo |
| `<w:instrText>` | **Se ignora**: son códigos de campo (índice, referencias cruzadas, `PAGE`) |
| `<w:fldSimple>` | Se ignora su contenido (mismo motivo) |
| `<w:del>` | **Se ignora su contenido**: es texto borrado con control de cambios |
| `<w:ins>` | Normal: su contenido **sí** cuenta |
| `<w:tbl>` | Abre una tabla |
| `<w:tr>` | Abre una fila |
| `<w:tc>` | Abre una celda |
| `</w:tc>` | Cierra la celda |
| `</w:tr>` | Cierra la fila |
| `</w:tbl>` | Cierra la tabla |
| `<w:footnoteReference>` | Se ignora (la nota se anexa al final desde `footnotes.xml`) |
| `<w:sdt>` | Transparente: se baja adentro (los "content controls") |
| `<w:txbxContent>` | Transparente: el texto de los cuadros de texto **es contenido** |

El texto (eventos `Text`/`CData`) se copia al buffer **sólo** si: se está
dentro de un `<w:t>`, **no** se está dentro de un campo, y **no** se está
dentro de un `<w:del>`.

**Paso 6 — vaciar el párrafo** (al cerrar `</w:p>`):

```kotlin
private fun flushParagraph() {
    val text = buffer.toString().trim()
    buffer = StringBuilder()
    if (text.isEmpty()) return
    val level = styles.headingLevel(paragraphStyle)
    val marker = numbering.marker(numId, listLevel, listCounters)
    val block = when {
        level > 0 -> Block.Heading(level, text)
        marker != null -> Block.ListItem(marker, text)
        else -> Block.Para(text)
    }
    blocks += block
    paragraphStyle = null
    numId = null
    listLevel = 0
}
```

> **Por qué el buffer por párrafo.** El nivel de encabezado y la condición de
> lista sólo se conocen **al cerrar** el párrafo (el `w:pPr` puede venir al
> principio o, en documentos produced por terceros, al final). Por eso el texto
> se acumula y se vuelca al final. Es la decisión que hace que el algoritmo
> funcione con cualquier productor de OOXML.

**Paso 7 — render a Markdown.**

```kotlin
private fun render(blocks: List<Block>): String = buildString {
    var previousWasTable = false
    for (b in blocks) {
        when (b) {
            is Block.Heading -> {
                if (isNotEmpty()) append('\n')
                append("#".repeat(b.level.coerceIn(1, 6))).append(' ').append(b.text)
                previousWasTable = false
            }
            is Block.Para -> {
                if (isNotEmpty()) append('\n')
                append(b.text)
                previousWasTable = false
            }
            is Block.ListItem -> {
                if (isNotEmpty()) append('\n')
                append(b.marker).append(b.text)
                previousWasTable = false
            }
            is Block.Table -> {
                if (isNotEmpty()) append('\n')
                append(renderTable(b.rows))
                previousWasTable = true
            }
        }
    }
}.trim()

/**
 * Tabla → Markdown.
 *
 * La primera fila es el encabezado (Markdown lo exige). Si la tabla real no
 * tiene encabezado, se usa la primera fila igual: el RAG no le cares la
 * diferencia y una tabla Markdown nunca corta una fila por la mitad.
 */
private fun renderTable(rows: List<List<String>>): String {
    if (rows.isEmpty()) return ""
    val width = rows.maxOf { it.size }
    fun esc(s: String) = s.replace("|", "\\|").replace("\n", " ").trim()
    return buildString {
        fun row(cells: List<String>) {
            append("| ").append((cells + List(width - cells.size) { "" }).joinToString(" | ") { esc(it) }).append(" |\n")
        }
        row(rows.first())
        append("| ").append(List(width) { "---" }.joinToString(" | ")).append(" |\n")
        for (r in rows.drop(1)) row(r)
    }.trimEnd()
}
```

**Paso 8 — las imágenes.**

Se ignoran (no hay OCR de imágenes embebidas en el hito 1), pero **se
cuentan** y el resultado lo dice:

> `warning = "3 imágenes omitidas (el texto no incluye su contenido)."`

Esto es información útil para el docente: si sube un apunte de 20 páginas con
12 imágenes y ninguna tiene texto, el aviso le dice que le falta contenido.
Si además el texto extraído es sospechosamente corto, tiene la explicación.

Cuando el `.docx` es **sólo imágenes** (un apunte escaneado y pegado en Word),
el texto sale vacío → el material queda en `NEEDS_REVIEW` con el aviso
*"El documento no tiene texto (sólo imágenes). Revisá el archivo o pegá el
texto a mano."*

**Paso 9 — el título.** De `docProps/core.xml`:

```xml
<cp:coreProperties …>
  <dc:title>Introducción a los modelos lineales</dc:title>
  <dc:creator>Prof. Pérez</dc:creator>
</cp:coreProperties>
```

Se busca el elemento `dc:title` (con prefijo o sin él) y se usa su texto. Si
no hay, `title = ""` y la UI usa el nombre del archivo.

**Ejemplo trabajado.** Entrada:

```xml
<w:document …>
 <w:body>
  <w:p><w:pPr><w:pStyle w:val="Ttulo1"/></w:pPr>
        <w:r><w:t>Modelos lineales</w:t></w:r></w:p>
  <w:p><w:r><w:t>Un modelo lineal es</w:t></w:r>
        <w:r><w:t xml:space="preserve"> una función de las variables.</w:t></w:r></w:p>
  <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="3"/></w:numPr></w:pPr>
        <w:r><w:t>Recta de regresión</w:t></w:r></w:p>
  <w:tbl>
    <w:tr><w:tc><w:p><w:r><w:t>x</w:t></w:r></w:p></w:tc>
            <w:tc><w:p><w:r><w:t>y</w:t></w:r></w:p></w:tc></w:tr>
    <w:tr><w:tc><w:p><w:r><w:t>1</w:t></w:r></w:p></w:tc>
            <w:tc><w:p><w:r><w:t>2</w:t></w:r></w:p></w:tc></w:tr>
  </w:tbl>
  <w:p><w:r><w:drawing>…</w:drawing></w:r></w:p>
 </w:body>
</w:document>
```

Salida:

```markdown
# Modelos lineales

Un modelo lineal es una función de las variables.

- Recta de regresión

| x | y |
| --- | --- |
| 1 | 2 |
```

Con `warning = "1 imagen omitida (el texto no incluye su contenido)."`.

#### 4.3.3 Lo que el DOCX **no** trae y hay que resolver

| Tema | Decisión |
|---|---|
| **Encabezados y pies de página** (`word/header1.xml`, `footer1.xml`) | **Se ignoran por completo**: nunca son contenido y son la causa del "encabezado repetido" |
| **Notas al pie** (`word/footnotes.xml`) | Se anexan al final bajo `## Notas al pie` (o se ignoran; **decisión: se anexan**, son contenido) |
| **Texto en cuadros de texto** (`w:txbxContent`) | Se incluye: es contenido |
| **Cambios trackeados** | `w:ins` se incluye, `w:del` se excluye |
| **Campos** (`PAGE`, `REF`, `TOC`, `DATE`) | Se excluyen: son códigos, no contenido |
| **Hipervínculos** | Se incluye el texto y la URL entre paréntesis: `ver más (https://…)` |
| **Tablas anidadas** | Se aplanan: el texto de la tabla interna se concatena en la celda |
| **SmartArt / gráficos** | Sin texto: se ignoran (se cuentan como imágenes) |
| **Documento vacío** | Texto vacío → `NEEDS_REVIEW` con el aviso de "sólo imágenes" |
| **.doc (Word 97, OLE2)** | **No soportado**: no es un ZIP. Se avisa |
| **.docx encriptado (con contraseña)** | Es un ZIP con `EncryptionInfo` → error `ENCRYPTED` |

---

### 4.4 PPTX

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/material/office/PptxExtractor.kt
package kitoru.material.office

object PptxExtractor {

    private const val REL_SLIDE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"
    private const val REL_NOTES = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide"

    fun extract(bytes: ByteArray): ExtractionResult {
        val zip = ZipReader.read(bytes)
        val presentationXml = zip.readText("ppt/presentation.xml")
            ?: throw MaterialExtractionException(
                ExtractionErrorCode.CORRUPT,
                "El archivo .pptx está dañado o no es un PowerPoint válido (falta ppt/presentation.xml).",
            )
        val presRels = OoxmlRels.read(zip, "ppt/_rels/presentation.xml.rels")

        // (1) EL ORDEN DE LAS DIAPOSITIVAS ESTÁ EN presentation.xml, NO EN EL
        //     NOMBRE DEL ARCHIVO. `slide10.xml` no es necesariamente la
        //     décima diapositiva: el orden real es el de <p:sldIdLst>.
        val slideRelIds = mutableListOf<String>()
        for (ev in XmlScanner(presentationXml).events()) {
            if (ev is XmlEvent.Open && ev.name == "p:sldId") {
                ev.attrs["r:id"]?.let { slideRelIds += it }
            }
        }

        val out = StringBuilder()
        var images = 0
        var index = 0

        for (relId in slideRelIds) {
            index++
            val target = presRels.url(relId) ?: continue
            val slidePath = normalize("ppt/" + target)      // "ppt/slides/slide1.xml"
            val slideXml = zip.readText(slidePath) ?: continue

            val shapes = readShapes(slideXml)                // (2) formas con su texto
            val title = shapes.firstOrNull { it.isTitle }?.text.orEmpty()
            images += shapes.count { it.hasImage }

            out.append("## Diapositiva ").append(index)
            if (title.isNotBlank()) out.append(" — ").append(title)
            out.append("\n\n")

            for (s in shapes) {
                if (s.isTitle && s.text == title) continue     // el título ya salió
                if (s.text.isNotBlank()) out.append(s.text).append("\n")
            }

            // (3) NOTAS DEL ORADOR
            val notesPath = notesPathFor(zip, slidePath)
            val notes = notesPath?.let { zip.readText(it) }?.let { readShapes(it) }
                ?.filter { it.text.isNotBlank() }
                ?.joinToString("\n") { it.text }
                .orEmpty()
            if (notes.isNotBlank()) {
                out.append("\n### Notas\n\n").append(notes).append("\n")
            }
            out.append("\n")
        }

        val text = out.toString().trim()
        return ExtractionResult(
            text = text,
            title = readCoreTitle(zip),
            pages = index,                    // "páginas" = diapositivas
            warning = if (images > 0) "$images imágenes omitidas (el texto no incluye su contenido)." else "",
            extractor = "pptx-zip",
            stats = kitoru.material.ExtractionStats(
                chars = text.length,
                words = text.split(Regex("\\s+")).count { it.isNotBlank() },
                blocks = index,
                skippedImages = images,
            ),
        )
    }
}
```

**Lectura de una forma** (`readShapes`): el texto de una diapositiva vive en
`p:sp` → `p:txBody` → `a:p` (párrafo) → `a:r` → `a:t` (el texto). El título
es la forma cuyo marcador de posición es `p:ph w:type="title"` o
`"ctrTitle"`.

| Tema | Decisión |
|---|---|
| **Orden de diapositivas** | El de `p:sldIdLst` en `presentation.xml` (¡no el nombre del archivo! `slide10.xml` puede ser la 3ª) |
| **Orden dentro de la diapositiva** | El del XML: de arriba hacia abajo, según el orden de las formas. Suficiente para el RAG |
| **Notas del orador** | Se incluyen bajo `### Notas`: a veces el material útil está ahí y no en la diapositiva |
| **Tablas** (`a:tbl` / `a:tr` / `a:tc`) | Se leen como texto, celda por celda |
| **Imágenes y gráficos** | Se ignoran y se cuentan. **Es la limitación más visible del PPTX**: una presentación exportada como imágenes no tiene texto |
| **SmartArt** | Sin texto extraíble |
| **Marcadores de posición vacíos** ("Haga clic para agregar título") | En PPTX no se guardan en el XML (sólo en el layout) → no aparecen |
| **`docProps`** | Se ignoran (metadatos) |

---

### 4.5 XLSX

```kotlin
// core/src/jvmCommonMain/kotlin/kitoru/material/office/XlsxExtractor.kt
package kitoru.material.office

object XlsxExtractor {

    fun extract(bytes: ByteArray): ExtractionResult {
        val zip = ZipReader.read(bytes)
        val workbookXml = zip.readText("xl/workbook.xml")
            ?: throw MaterialExtractionException(
                ExtractionErrorCode.CORRUPT,
                "El archivo .xlsx está dañado o no es un Excel válido (falta xl/workbook.xml).",
            )
        val wbRels = OoxmlRels.read(zip, "xl/_rels/workbook.xml.rels")
        val shared = readSharedStrings(zip)              // xl/sharedStrings.xml

        // Hojas: nombre + rid, en el orden de <sheets>
        val sheets = mutableListOf<Pair<String, String>>()
        for (ev in XmlScanner(workbookXml).events()) {
            if (ev is XmlEvent.Open && ev.name == "sheet") {
                val name = ev.attrs["name"].orEmpty()
                val rid = ev.attrs["r:id"]
                if (rid != null) sheets += name to rid
            }
        }

        val out = StringBuilder()
        var totalRows = 0
        var truncated = false

        for ((name, rid) in sheets) {
            val target = wbRels.url(rid) ?: continue
            val path = normalize("xl/" + target)          // "xl/worksheets/sheet1.xml"
            val sheetXml = zip.readText(path) ?: continue
            val rows = readSheet(sheetXml, shared)

            val limited = if (rows.size > MAX_ROWS) { truncated = true; rows.take(MAX_ROWS) } else rows
            if (limited.isEmpty()) continue

            out.append("## ").append(name).append("\n\n")
            if (limited.size == 1) {
                // Una sola fila: no hay tabla que hacer.
                out.append(limited.first().joinToString(" | ")).append("\n\n")
            } else {
                out.append(renderTable(limited)).append("\n\n")
            }
            totalRows += limited.size
        }

        val text = out.toString().trim()
        val warning = buildString {
            if (truncated) {
                append("La hoja tiene más de $MAX_ROWS filas; se indexaron las primeras $MAX_ROWS.")
            }
            if (text.isBlank()) {
                append("El archivo Excel no tiene datos (sólo celdas vacías o formatos).")
            }
        }
        ...
    }
}
```

**Las piezas del XLSX:**

| Pieza | Dónde | Qué se lee |
|---|---|---|
| Nombres de las hojas | `xl/workbook.xml` → `<sheet name="Hoja 1" r:id="rId1"/>` | El nombre de la hoja (clave y legible) |
| Ordem de las hojas | el orden de `<sheets>` en `workbook.xml` | El orden real |
| Rels | `xl/_rels/workbook.xml.rels` | `rId` → `worksheets/sheet1.xml` |
| Cadenas compartidas | `xl/sharedStrings.xml` → `<sst><si><t>Hola</t></si>` | El índice de la celda `t="s"` |
| Celdas | `xl/worksheets/sheetN.xml` → `<c r="A1" t="s"><v>3</v></c>` | Ver tabla de tipos abajo |
| Fórmulas | `<f>…</f>` + `<v>…</v>` | Se usa el **valor cacheado** (`<v>`); si no hay, la celda queda vacía |
| Filtros y formatos | `autoFilter`, `styles.xml` | Se ignoran |

**Tipos de celda:**

| `t=` | Contenido | Se escribe |
|---|---|---|
| `s` | índice en `sharedStrings` | el texto de la cadena |
| `inlineStr` | `<is><t>texto</t></is>` | el texto |
| `str` | resultado de una fórmula | el texto |
| `b` | booleano | `TRUE` / `FALSE` |
| `e` | error (`#DIV/0!`) | el texto del error |
| `d` | fecha ISO | la fecha tal cual |
| (ninguno) | número | el número **tal cual** |

> **Las fechas no se convierten.** Un `.xlsx` guarda las fechas como un
> número serial (días desde 1899-12-30, o desde 1904 si el archivo usa el
> calendario de Mac). Convertirlo exige leer `styles.xml` para saber el
> `numFmt` de cada celda: es un rabbit hole con Bugs de epoch. **Decisión: se
> deja el número crudo.** Para el RAG, "45292" es inútil, pero el texto que
> acompaña a la tabla ("Fecha de entrega: …") sí se indexa.

**Topes:** 2 000 filas por hoja, 100 columnas; si se superan → se trunca y se
avisa.

**¿Y `.xls` (Excel 97, OLE2)?** No soportado: no es un ZIP. Se avisa.

---

### 4.6 HTML

```kotlin
// core/src/commonMain/kotlin/kitoru/material/html/HtmlExtractor.kt
package kitoru.material.html

import kitoru.material.ExtractionResult
import kitoru.material.TextExtractor

/**
 * Convierte HTML en texto indexable.
 *
 * Parser propio, en Kotlin puro, porque el parser de Ktor (`ktor-client-html`)
 * es **JVM-only** y este código vive en `commonMain`.
 *
 * No es un navegador: no ejecuta JS, no carga imágenes, no interpreta CSS.
 * Es un "lector de artículo": saca el texto y conserva la jerarquía.
 */
object HtmlExtractor : TextExtractor {

    override val id = "html"
    override val extensions = setOf("html", "htm", "xhtml")
    override val mimes = setOf("text/html", "application/xhtml+xml")

    /** Contenido que se descarta entero (con todo lo que hay adentro). */
    private val DROP = setOf(
        "script", "style", "noscript", "svg", "canvas", "iframe",
        "nav", "aside", "footer", "form", "button", "select", "template",
    )

    override suspend fun extract(file: FilePath, mime: String): ExtractionResult {
        val html = file.readText()
        val doc = HtmlParser(html).parse()
        ...
    }
}
```

**Reglas del parser:**

| Etiqueta | Qué hace |
|---|---|
| `<!-- … -->` | Se descarta |
| `<script>`, `<style>`, `<noscript>`, `<svg>`, `<canvas>`, `<iframe>` | Se descarta **con todo su contenido** |
| `<nav>`, `<aside>`, `<footer>`, `<form>`, `<button>`, `<select>` | Se descarta (es cromo de la página, no contenido) |
| `<header>` | **Se conserva** (suele contener el título del artículo) |
| `<title>` | Se extrae como **título** del documento |
| `<h1>`…`<h6>` | → `#`…`######` + línea en blanco antes y después |
| `<p>`, `<div>`, `<section>`, `<article>`, `<br>` | Salto de línea |
| `<li>` | → `- ` + el texto del ítem |
| `<td>`, `<th>` | Separador ` | ` entre celdas; `</tr>` → salto de línea |
| `<a href="u">texto</a>` | `texto (u)` |
| `<pre>` | Bloque de código delimitado por ``` (fenced) |
| `<code>` | Se conserva el texto |
| `&amp;` `&lt;` `&gt;` `&quot;` `&nbsp;` `&aacute;`… y `&#225;` `&#xE1;` | Se decodifican |
| Texto suelto | Se copia |

**El parser, en escaneo de una pasada** (como el `XmlScanner`, pero tolerante:
el HTML real no es XML —falta el cierre de `<p>`, las comillas sin escapar, los
`<` sueltos—):

```
i = 0
mientras i < largo:
    si html[i] == '<':
        si empieza por "<!--"  → saltar hasta "-->"
        si empieza por "<!"    → saltar hasta ">"   (doctype)
        si empieza por "</"    → nombre de cierre → cerrar el elemento
        si empieza por "<?"    → saltar hasta ">"
        si no → es un "<" de texto → copiarlo
        si no → nombre de apertura + atributos → abrir el elemento
    si no:
        copiar texto hasta el próximo '<' y decodificar entidades
```

Los atributos se leen con un mini-escáner: `nombre = "valor"`, `nombre='valor'`,
`nombre=valor`, `nombre` (sin valor). Sólo interesan `href` y `alt`.

**Sobre `<header>` vs `<footer>`:** el `footer` de una página web es el
equivalent del pie de página de un PDF (copyright, links, "última
actualización") → se descarta. El `header` suele ser el título del artículo →
se conserva.

---

### 4.7 CSV — decisión: **tabla Markdown**

> **Decisión: el CSV se convierte en una tabla Markdown**, no se deja como
> texto plano.

**Por qué tabla y no texto:**

1. **El `Chunker` no parte una fila por la mitad.** El chunker corta por
   saltos de línea (`Chunker.split` busca el último `\n` antes del límite). Con
   el CSV plano, una celda con saltos de línea o un texto muy largo puede
   dejar un fragmento que termina a mitad de fila. Con la tabla, cada fila es
   una línea.
2. **La estructura sobrevive.** Una tabla de "Nota / Alumno / sheltered" indexada
   como tabla permite que el modelo diga "el alumno X sacó un 7" con la
   relación fila↔columna intacta.
3. **El costo es un `| --- |` de ruido**, que el tokenizador de BM25 reduce a
   un token (`---` → se parte en tokens de 1 carácter que se descartan por el
   filtro `it.length > 1`). Negligible.

**El algoritmo:**

```kotlin
// core/src/commonMain/kotlin/kitoru/material/csv/CsvExtractor.kt
package kitoru.material.csv

object CsvExtractor : TextExtractor {

    override val id = "csv"
    override val extensions = setOf("csv", "tsv")
    override val mimes = setOf("text/csv", "text/tab-separated-values")

    override suspend fun extract(file: FilePath, mime: String): ExtractionResult {
        val text = file.readText()
        val delimiter = detectDelimiter(text)
        val rows = parseCsv(text, delimiter)
        ...
    }

    /**
     * Detecta el separador: el que aparece **más veces de forma consistente**
     * en las primeras 5 líneas no vacías.
     *
     * Se cuentan sólo los separadores **fuera de comillas** (un CSV puede
     * tener "Smith, John" en una celda).
     */
    fun detectDelimiter(text: String): Char {
        val candidates = listOf(',', ';', '\t', '|')
        val lines = text.lineSequence().filter { it.isNotBlank() }.take(5).toList()
        if (lines.isEmpty()) return ','
        val counts = candidates.associateWith { c ->
            lines.sumOf { countOutsideQuotes(it, c) }
        }
        // Desempate: el que más apariciones; a igualdad, la coma.
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }
}
```

**Reglas del parser CSV:**

- Campo entre comillas dobles: `"a, b"` → `a, b`.
- Comilla escapada dentro de un campo: `""` → `"`.
- Salto de línea dentro de un campo entrecomillado → se conserva.
- Celdas vacías → `""`.
- Se recorta el espacio exterior de cada celda (opcional; por defecto sí,
  porque los CSV exports de Excel vienen con espacios).
- La **primera fila** es el encabezado de la tabla Markdown.
- Tope: 5 000 filas (si se supera → se trunca y se avisa).

---

### 4.8 EPUB — decisión: **fuera del hito 1**

**Decisión: no se implementa en el hito 1.**

**Por qué no:**

1. Un EPUB es un ZIP con `META-INF/container.xml` → `content.opf` → manifiesto +
   *spine* → archivos XHTML por capítulo. Son **tres** formatos más (ZIP + XML
   + HTML) sobre un caso poco frecuente: un profesor que sube un libro
   electrónico como material de cátedra.
2. El EPUB **no encaja con el modelo de "material"**: un libro son 300
   páginas y 40 capítulos; el `Course.chunkSize` de 1 200 caracteres lo
   convertiría en ~4 000 fragmentos de una sola fuente, que compiten entre sí
   en el BM25 y desplazan al resto del material. Es un problema de
   **indexación**, no de extracción.
3. Lo que el docente necesitaría de verdad ("este libro, buscame esto") es
   una búsqueda **dentro** del libro, que es otra feature.

**Qué se hace en el meantime (hito 1):**

- Se **detecta** (la firma `PK` + el `mimetype` interno `application/epub+zip`
  o la extensión `.epub`).
- El material **se sube y se guarda** (el docente no pierde el archivo), con
  `status = NEEDS_REVIEW` y este aviso:

  > "Los libros EPUB todavía no se pueden leer. Si el material es importante, "
  > "subilo como PDF o exportalo a Markdown."

- La **costura queda lista**: `MaterialFormat.EPUB.supportsText = false` y un
  `EpubExtractor` que se registra cuando se implemente. Agregarlo después es
  un archivo nuevo, sin tocar el pipeline.

**El algoritmo, sketched, para cuando se implemente:**

```
1. Abrir el ZIP; leer "mimetype" (debe decir "application/epub+zip").
2. Leer "META-INF/container.xml" → <rootfile full-path="OEBPS/content.opf"/>
3. Leer content.opf → <manifest> (id → href) y <spine> (orden de lectura).
4. Para cada item del spine: leer el XHTML y pasarlo por el HtmlExtractor.
5. Unir con "## Capítulo N — <título del capítulo>".
```

---

### 4.9 Links (material de tipo `LINK`)

**Decisión: sí, se descarga el HTML y se cachea.**

**Por qué sí:**

1. Un material `LINK` **sin texto no aporta nada al índice**. El `CourseMaterialSource`
   filtra por `extractedText.isNotBlank()` → un enlace sin descargar es un
   material decorativo. Y la expectativa del docente al pegar un link a una
   página de la cátedra es "que la IA pueda responder con eso".
2. Es un caso de uso real: la cátedra publica las consignas en una página
   web, o enlaza un cuaderno de la biblioteca, o una wiki.

**Por qué no "siempre en vivo":** la app puede estar sin red (IA local), la
   página puede cambiar, y una extracción en cada indexización haría el RAG
   no determinista. **Se descarga una vez y se cachea.**

**El modo de cacheo:**

| Aspecto | Decisión |
|---|---|
| Dónde | `KitoruPaths.cacheDir.resolve("links")` → `<cacheDir>/links/<sha1(url)>.html` |
| Clave | **SHA-1 de la URL normalizada** (sin fragmento `#…`, con el esquema y el host en minúsculas) |
| Metadatos | `<cacheDir>/links/<sha1(url)>.json` → `{ "url", "finalUrl", "contentType", "fetchedAt", "status" }` |
| Cuándo se descarga | Al crear el material, y **sólo** cuando el docente pulsa "Actualizar" |
| Si falla la descarga | El material se guarda igual, con `status = NEEDS_REVIEW` y el aviso "No se pudo descargar el enlace (HTTP 403). Podés pegar el texto a mano en «Editar texto»." |
| URLs Changes | Se guarda `finalUrl` (después de redirects) en los metadatos |

**El `LinkFetcher`:**

```kotlin
// core/src/commonMain/kotlin/kitoru/material/link/LinkFetcher.kt
package kitoru.material.link

/**
 * Descarga el contenido de una URL.
 *
 * La implementación usa Ktor (`ktor-client-core` en `commonMain`, motor CIO en
 * escritorio y OkHttp en Android — los tres ya están en el catálogo de
 * dependencias).
 */
interface LinkFetcher {
    /**
     * @throws MaterialExtractionException con mensaje en español.
     */
    suspend fun fetch(url: String): FetchedLink
}

class FetchedLink(
    /** URL final, después de los redirects. */
    val finalUrl: String,
    /** Content-Type del servidor (puede ser "text/html; charset=utf-8"). */
    val contentType: String,
    /** Cuerpo de la respuesta. */
    val body: ByteArray,
    /** Fecha de descarga (epoch ms). */
    val fetchedAt: Long,
)
```

**Reglas de seguridad y de tamaño (no es opcional):**

| Regla | Valor | Por qué |
|---|---|---|
| Esquemas permitidos | `http`, `https` | Nada de `file://`, `ftp://`, `jar:` |
| Tamaño máximo | 5 MB | Una página de 5 MB ya es un libros |
| Timeout de conexión | 10 s | |
| Timeout total | 20 s | |
| Redirects | máx. 5, sólo a `http`/`https` | Un redirect a `file://` sería un problema |
| User-Agent | `Kitoru/1.0 (+material de cátedra)` | Algunos sitios rechazan el cliente vacío |
| Sin cookies, sin credenciales | — | No se mandan datos del usuario a un sitio arbitrario |
| HTML con `<script>` | eliminado | El `HtmlExtractor` lo descarta |

**Y un caso especial que vale la pena:** si el servidor responde
`Content-Type: application/pdf` (o la URL termina en `.pdf`), el contenido
**se guarda como archivo y pasa por el `PdfExtractor`**. Un link a un PDF es
de los casos más comunes de la cátedra.

**El `LinkExtractor` como `TextExtractor`:** el enlace **no** es un archivo,
así que no encaja en `extract(file, mime)`. Se maneja como un camino aparte
del `MaterialExtractionService`:

```kotlin
// El material es un LINK: no hay archivo, hay una URL.
suspend fun extractUrl(url: String): ExtractionResult {
    val link = linkFetcher.fetch(url)
    return when {
        link.contentType.startsWith("application/pdf") ->
            // Se guarda en el caché como .pdf y se extrae con el PdfExtractor.
            pdfExtractor.extract(cachePath, link.contentType)
        else -> {
            val html = Encoding.decode(link.body).text
            val result = HtmlExtractor.extractText(html)
            result.copy(warning = "Descargado de $url el ${formatDate(link.fetchedAt)}.")
        }
    }
}
```

---

## 5. Limpieza común

```kotlin
// core/src/commonMain/kotlin/kitoru/material/clean/TextCleaner.kt
package kitoru.material.clean

import kitoru.platform.stripDiacritics

/**
 * Limpieza común a todos los formatos.
 *
 * Va **después** de la extracción, no adentro de cada extractor (§1.3).
 */
object TextCleaner {

    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val SPACES = Regex("[ \\t\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]+")
    private val BLANKS = Regex("\n{3,}")
    private val TRAILING = Regex("[ \\t]+$", RegexOption.MULTILINE)

    /** Ligaduras tipográficas → letras ASCII. */
    private val LIGATURES = mapOf(
        'ﬁ' to "fi", 'ﬂ' to "fl", 'ﬀ' to "ff", 'ﬃ' to "ffi",
        'ﬄ' to "ffl", 'ﬅ' to "ft", 'ﬆ' to "st", 'ﬓ' to "ffi", 'ﬔ' to "ffl",
    )

    /**
     * Guion de corte de línea.
     *
     * "auto-\nmóvil" → "automóvil".
     *
     * Se **une sin guion** (no "auto móvil"). Es lo estándar y acierta en la
     * enorme mayoría de los casos: el guion de corte de línea se inserta en
     * un límite silábico, así que unir restituye la palabra original.
     *
     * El contraejemplo es un compuesto con guion real partido por el corte de
     * línea ("auto-\nmatizado" → "automizado"). El costo en el RAG es bajo
     * (una palabra mal pegada) frente al costo de no unir ("automóvil" nunca
     * se encontraría). Y como el tokenizador de BM25 parte por
     * `[^a-z0-9]`, "auto-matizado" habría dado los tokens "auto" y "matizado":
     * con la unión se pierde "matizado".
     *
     * La decisión es un parámetro: `joinHyphenated = true`.
     */
    private val HYPHEN_BREAK = Regex("(\\p{L})-\\n(\\p{L})")

    fun clean(text: String): String {
        if (text.isEmpty()) return text
        var out = text

        // 1) Caracteres de control (menos \n, \r y \t).
        out = CONTROL.replace(out, "")

        // 2) BOM y zero-width.
        out = out.replace("\uFEFF", "").replace("\u200B", "").replace("\u200C", "")

        // 3) Ligaduras.
        for ((k, v) in LIGATURES) out = out.replace(k.toString(), v)

        // 4) Guion blando: se borra (y se unen las dos partes).
        out = out.replace("\u00AD", "")

        // 5) Guiones de corte de línea → unión.
        out = HYPHEN_BREAK.replace(out) { m -> m.groupValues[1] + m.groupValues[2] }

        // 6) Espacios: se colapsan, pero NO dentro de bloques de código.
        out = collapseOutsideCodeFences(out)

        // 7) Fin de línea: se unifica a \n.
        out = out.replace("\r\n", "\n").replace('\r', '\n')

        // 8) Espacios al final de la línea.
        out = TRAILING.replace(out, "")

        // 9) Como máximo dos líneas en blanco seguidas.
        out = BLANKS.replace(out, "\n\n")

        return out.trim()
    }
}
```

**El detalle del punto 6 (no tocar los bloques de código)** es importante: el
`Chunker` ya sabe reconocer los fences ```` ``` ```` y no corta dentro de
ellos. Si la limpieza colapsa la indentación de un bloque de código, el
contenido del ejemplo queda mal y además el `Chunker` puede cortar un
fragmento por la mitad.

```kotlin
/**
 * Colapsa los espacios fuera de los bloques de código delimitados por
 * ``` (los bloques de código conservan su indentación: es parte del
 * contenido).
 */
private fun collapseOutsideCodeFences(text: String): String {
    val out = StringBuilder()
    var inFence = false
    for (line in text.split('\n')) {
        val trimmed = line.trimStart()
        val isFence = trimmed.startsWith("```") || trimmed.startsWith("~~~")
        if (isFence) inFence = !inFence
        if (inFence) {
            out.append(line)          // dentro del bloque: no se toca
        } else {
            out.append(SPACES.replace(line, " "))
        }
        out.append('\n')
    }
    return out.toString()
}
```

**El detector de encabezados repetidos** ya está en §4.2.5
(`RepeatedHeaderDetector`): es la pieza que resuelve el "Página 3 de 12 en
cada página" y el "título del apunte repetido arriba".

**El stripper de números de página** también (§4.2.5,
`PageNumberStripper`).

**Qué NO hace la limpieza** (deliberadamente):

| No se hace | Por qué |
|---|---|
| Quitar tildes | El índice ya normaliza con `stripDiacritics`; el texto **mostrado** al docente debe conservar la ortografía |
| Quitar acentos o "corregir" la ortografía | Mismo motivo |
| Resumir o parafrasear | El RAG necesita el texto, no un resumen |
| Quitar las stopwords | El tokenizador de BM25 ya las filtra |
| Normalizar a NFC | No hay normalizador Unicode en `commonMain`; el texto se guarda tal cual |

---

## 6. Idioma

**Decisión: heurística de palabras funcionales, en `commonMain`, sin librería.**

**Por qué no una librería:**

| Opción | Android | Veredicto |
|---|---|---|
| `org.apache.opennlp:opennlp-tools` | ❌ (trae `java.util.logging`, `javax.naming`, Spark) | Descartada |
| `com.google.mlkit:language-id` | ✓ | **Sólo Android** → el escritorio quedaría sin detección, y el texto de un PDF tiene que analizarse igual en las dos plataformas |
| `langdetect` / `fasttext` | ❌ / pesado | Descartadas |
| Heurística de stopwords | ✓ | **Elegida**: 200 palabras, 0 dependencias, funciona en las dos plataformas, y para texto académico es suficiente |

```kotlin
// core/src/commonMain/kotlin/kitoru/material/lang/LanguageDetector.kt
package kitoru.material.lang

import kitoru.platform.stripDiacritics

/**
 * Detección de idioma por palabras funcionales.
 *
 * No pretende ser un detector de Languages: pretende contestar "¿esto está en
 * español o en inglés?" con suficiente confianza como para mostrarlo en la UI.
 */
object LanguageDetector {

    /** Idiomas que se detectan en el hito 1. */
    const val ES = "es"
    const val EN = "en"

    /** Por debajo de esta proporción de palabras funcionales, no se decide. */
    private const val MIN_RATIO = 0.02

    /** Diferencia mínima entre los dos puntajes para decidir. */
    private const val MIN_DIFF = 0.008

    fun detect(text: String): String {
        if (text.length < 200) return ""            // muy poco texto: no se decide
        val tokens = tokenize(text)
        if (tokens.size < 30) return ""
        val es = tokens.count { it in STOP_ES }
        val en = tokens.count { it in STOP_EN }
        val ratioEs = es.toDouble() / tokens.size
        val ratioEn = en.toDouble() / tokens.size
        return when {
            ratioEs < MIN_RATIO && ratioEn < MIN_RATIO -> ""
            ratioEs - ratioEn >= MIN_DIFF -> ES
            ratioEn - ratioEs >= MIN_DIFF -> EN
            else -> ""
        }
    }

    private val WORD = Regex("[\\p{L}']+")

    private fun tokenize(text: String): List<String> =
        WORD.findAll(stripDiacritics(text.lowercase()))
            .map { it.value }
            .filter { it.length >= 2 }
            .take(4000)                 // con 4 000 palabras alcanza de sobra
            .toList()
}
```

**Los conjuntos de palabras funcionales.** Se incluyen ~120 en español y ~150
en inglés. **Las palabras ambiguas quedan fuera de ambos conjuntos**: "a"
(artículo inglés / preposición española), "no", "on", "as", "so", "tan", "sin"
— que están en los dos idiomas con distinto uso — no cuentan para ninguno, o
sumarían ruido.

```kotlin
    private val STOP_ES = setOf(
        "de", "la", "el", "en", "y", "que", "los", "se", "del", "las", "un", "una",
        "por", "con", "no", "su", "para", "es", "al", "lo", "como", "más", "pero",
        "sus", "le", "ya", "o", "este", "sí", "porque", "esta", "entre", "cuando",
        "muy", "sobre", "también", "hasta", "hay", "donde", "quien", "desde",
        "todo", "nos", "durante", "todos", "uno", "les", "ni", "contra", "otros",
        "ese", "eso", "ante", "ellos", "esto", "antes", "algunos", "qué", "unos",
        "otro", "otras", "otra", "tanto", "estos", "mucho", "quienes", "nada",
        "muchos", "cual", "poco", "ella", "estas", "algunas", "algo", "nosotros",
        "ser", "estar", "tiene", "tienen", "puede", "pueden", "hace", "cada",
        "sea", "debe", "están", "aquí", "así", "solo", "dentro", "fuera",
        "siguiente", "anterior", "además", "mientras", "cuyo", "cuya", "demás",
        "mismo", "misma", "todo", "ningún", "ninguna", "varios", "cierta",
        "cierto", "misma", "mediante", "respecto", "además", "asimismo",
    )

    private val STOP_EN = setOf(
        "the", "of", "and", "to", "in", "is", "that", "for", "it", "with", "as",
        "was", "on", "be", "at", "by", "this", "have", "from", "or", "are", "not",
        "but", "they", "which", "you", "an", "we", "their", "has", "its", "one",
        "if", "all", "will", "would", "there", "can", "about", "who", "more",
        "when", "out", "so", "said", "what", "up", "into", "than", "them", "only",
        "other", "new", "some", "could", "time", "these", "two", "may", "then",
        "do", "first", "any", "my", "now", "such", "like", "our", "over", "man",
        "me", "even", "most", "made", "after", "also", "did", "many", "before",
        "must", "through", "back", "years", "where", "much", "your", "way",
        "well", "down", "should", "because", "each", "just", "those", "people",
        "how", "too", "little", "state", "good", "very", "make", "world", "still",
        "own", "see", "men", "work", "long", "get", "here", "between", "both",
        "life", "being", "under", "never", "day", "same", "another", "know",
        "while", "last", "might", "us", "great", "old", "year", "off", "come",
        "since", "against", "go", "came", "right", "used", "take", "three",
    )
```

**¿Para qué sirve el idioma?** Hoy, para **mostrarlo** en la ficha del material
("idioma: español") y para detectar un material en el idioma equivocado
(un PDF de un paper en inglés en una cátedra de español: es útil que el
docente lo sepa). **No cambia el índice**: BM25 con `stripDiacritics` es
agnóstico al idioma, y las stopwords del tokenizador ya están mezcladas
(es/en). Cuando llegue la búsqueda semántica o la lematización por idioma,
este campo ya está.

**Sobre el español rioplatense:** los mensajes al usuario usan "vos"
(*"Subilo"*, *"Revisá"*) porque el proyecto ya está escrito así; el material
del usuario puede estar en español de cualquier variedad y el detector no
distingue variantes (no hace falta).

---

## 7. Errores y mensajes (en español)

### 7.1 Los mensajes exactos

| Situación | Código | Mensaje que ve el docente |
|---|---|---|
| Formato no soportado | `UNSUPPORTED` | "«diagrama.png» no es un formato que se pueda indexar. Se admiten: PDF, Word (.docx), PowerPoint (.pptx), Excel (.xlsx), Markdown (.md), texto (.txt), CSV, HTML y enlaces." |
| Extensión ≠ contenido | (warning) | "El archivo «notas.pdf» dice ser PDF pero su contenido no lo es. Revisá el archivo." |
| Archivo vacío | `EMPTY_FILE` | "El archivo está vacío (0 bytes)." |
| Archivo inexistente | `NOT_FOUND` | "El archivo original ya no está en el disco. Volvé a subirlo para poder reextraerlo." |
| Archivo demasiado grande | `TOO_LARGE` | "El archivo pesa 84 MB y el máximo por archivo es 50 MB." |
| ZIP descomprimido enorme | `TOO_LARGE` | "El documento comprimido es demasiado grande al descomprimirlo (más de 200 MB). Revisá el archivo: puede estar dañado." |
| PDF protegido | `ENCRYPTED` | "El PDF está protegido con contraseña. Desprotegelo y volvé a subirlo." |
| DOCX protegido | `ENCRYPTED` | "El archivo .docx está protegido con contraseña. Desprotegelo y volvé a subirlo." |
| PDF corrupto | `CORRUPT` | "No se pudo leer el PDF: archivo dañado. Probá abrirlo de nuevo y guardarlo otra vez." |
| DOCX inválido | `CORRUPT` | "El archivo .docx está dañado o no es un Word válido (falta word/document.xml)." |
| PPTX inválido | `CORRUPT` | "El archivo .pptx está dañado o no es un PowerPoint válido (falta ppt/presentation.xml)." |
| XLSX inválido | `CORRUPT` | "El archivo .xlsx está dañado o no es un Excel válido (falta xl/workbook.xml)." |
| **PDF escaneado** | (warning + `needsOcr`) | "Este PDF no tiene capa de texto (0 de 12 páginas tienen texto). Parece un escaneo: las imágenes no se pueden leer. Subilo desde el original con texto seleccionable, o pegá el texto a mano en «Editar texto»." |
| PDF parcialmente escaneado | (warning) | "3 de 12 páginas no tienen texto (parecen escaneadas). El contenido de esas páginas no se pudo leer." |
| DOCX/PPTX sólo con imágenes | (warning) | "El documento no tiene texto (sólo imágenes). Revisá el archivo o pegá el texto a mano." |
| Imágenes omitidas | (warning) | "3 imágenes omitidas (el texto no incluye su contenido)." |
| Encoding raro | (warning) | "El archivo no estaba en UTF-8; se leyó como Latin-1. Revisá que no haya caracteres raros." |
| Texto truncado | (warning + `truncated`) | "El texto extraído tiene 3.200.000 caracteres; se indexaron los primeros 2.000.000. Revisá el texto en «Editar texto»." |
| Se pasó el plazo | `TIMEOUT` | "La extracción tardó más de 60 s y se cortó. Probá con un archivo más chico." |
| Sin memoria | `OUT_OF_MEMORY` | "No hay memoria suficiente para leer este archivo. Cerrá otras aplicaciones y probá de nuevo." |
| Error de E/S | `IO` | "No se pudo leer el archivo: el archivo está en uso o no tenés permiso. Revisá los permisos e intentá de nuevo." |
| EPUB | (warning) | "Los libros EPUB todavía no se pueden leer. Si el material es importante, subilo como PDF o exportalo a Markdown." |
| .doc / .xls / .rtf / .odt | `UNSUPPORTED` | "Los archivos .doc, .xls, .rtf y .odt todavía no se pueden leer. Guardá el material como PDF o .docx." |
| Enlace que no se pudo descargar | (warning) | "No se pudo descargar el enlace (HTTP 403). Podés pegar el texto a mano en «Editar texto»." |
| Cualquier otro error | `UNKNOWN` | "No se pudo leer el archivo: <motivo>. Probá de nuevo o subilo en otro formato." |

### 7.2 Cómo se muestran

- El error **queda en el material** (`status = FAILED`, `statusDetail = mensaje`),
  no como un snackbar que se pierde: el docente tiene que ver que el archivo
  falló, volver a él y entender por qué.
- Un **warning** no bloquea: el material queda `READY` (o `NEEDS_REVIEW` si no
  hay texto) y se indexa igual.
- `needsOcr = true` → el material queda `NEEDS_REVIEW` y **no** aporta
  fragmentos al índice (el `CourseMaterialSource` ya filtra por texto no
  vacío).

---

## 8. Límites, concurrencia y cancelación

### 8.1 Los límites

```kotlin
// core/src/commonMain/kotlin/kitoru/material/ExtractionLimits.kt
package kitoru.material

/**
 * Topes de la extracción.
 *
 * Son generosos a propósito: el objetivo es que un docente no tenga que
 * pensar en esto, y que un material patológico no reviente la app.
 */
data class ExtractionLimits(
    /** Máximo por archivo: 50 MB. */
    val maxFileBytes: Long = 52_428_800L,

    /** Máximo de caracteres extraídos por material: 2 M. */
    val maxChars: Int = 2_000_000,

    /** Plazo de extracción: 60 s. */
    val maxMillis: Long = 60_000,

    /** Máximo descomprimido de un ZIP: 200 MB (protección zip-bomb). */
    val maxUnzippedBytes: Long = 209_715_200L,

    /** Máximo de entradas de un ZIP. */
    val maxZipEntries: Int = 5_000,

    /** Máximo de bytes de una parte XML dentro de un OOXML. */
    val maxXmlBytes: Int = 50_000_000,

    /** Máximo de páginas de un PDF. */
    val maxPdfPages: Int = 2_000,

    /** Máximo de filas por hoja de Excel. */
    val maxSheetRows: Int = 2_000,

    /** Máximo de columnas por hoja de Excel. */
    val maxSheetColumns: Int = 100,

    /** Máximo de filas de un CSV. */
    val maxCsvRows: Int = 5_000,

    /** Máximo de bytes de una descarga de enlace. */
    val maxLinkBytes: Long = 5_242_880L,
)
```

### 8.2 El hilo: la UI no se congela

**El problema.** Extraer es trabajo **bloqueante** de E/S y de CPU: leer 50
MB, descomprimir un ZIP, parsear 2 000 páginas de PDF. Hecho en el hilo
principal, la ventana se congela (en escritorio) o la app se cuelga y Android
mata el proceso ("ANR" a los 5 s).

**La solución, en tres partes:**

1. **Todo el trabajo va en `withContext(ioDispatcher)`.**
   `kitoru.platform.ioDispatcher` ya existe en `:core` (es `Dispatchers.IO` en
   las dos plataformas). El `MaterialExtractionService.extract` lo envuelve
   entero.

2. **La corrutina se lanza desde el `ViewModel`, no desde el click.**
   En Compose Multiplatform, un `ViewModel` propio (o `rememberCoroutineScope` +
   un estado) lanza la corrutina; el click sólo la dispara:

   ```kotlin
   // ui/src/commonMain/kotlin/kitoru/ui/teacher/materials/MaterialViewModel.kt
   fun importFiles(files: List<PickedFile>) {
       viewModelScope.launch {
           for (f in files) {
               _state.update { it.copy(phase = Phase.Extracting(f.name)) }
               val result = runCatching { ingestService.ingest(courseId, f, …) }
               // el resultado se refleja en el estado; la UI nunca se bloquea
           }
       }
   }
   ```

   El `ViewModel` de Compose Multiplatform (`androidx.lifecycle.ViewModel` en
   desktop, con `lifecycle-viewmodel` KMP) ya expone `viewModelScope`.

3. **El trabajo es cancelable.** Si el docente cierra el diálogo o navega
   atrás, la corrutina se cancela. Para que la cancelación **entre** en medio
   de la extracción, el bucle de páginas del PDF y el de filas del Excel
   comprueban la cancelación:

   ```kotlin
   // dentro del bucle de páginas del PDF
   for (i in 0 until doc.numberOfPages) {
       currentCoroutineContext().ensureActive()      // ← cancelable
       if (System.nanoTime() - startedAt > deadlineNanos) {
           throw MaterialExtractionException(ExtractionErrorCode.TIMEOUT, "…")
       }
       // … extraer la página …
   }
   ```

   `ensureActive()` funciona porque el código de extracción es `suspend`.

4. **El plazo de 60 s se verifica de dos formas**: con `withTimeout` (la red
   de seguridad) y con una **comprobación explícita del reloj dentro de los
   bucles** (que es la que realmente corta, porque `withTimeout` sólo puede
   actuar en un punto de suspensión y PDFBox no interrumpe un `getText()` a
   mitad).

   ```kotlin
   // El reloj corre aunque nadie Cancellatione.
   val deadline = System.nanoTime() + limits.maxMillis * 1_000_000
   ```

5. **Progreso.** El `ingest` reporta con un callback `onStatus: (String) -> {}`
   (el mismo patrón que ya usa `KnowledgeBase.sync(onStatus)`), que la UI
   muestra como "Extrayendo… (página 7 de 12)". No hace falta un `Flow`
   complicated para el hito 1.

### 8.3 La memoria

| Situación | Mitigación |
|---|---|
| PDF en Android | `MemoryUsageSetting.setupMainMemoryOnly()` → PDFBox no escribe temporales |
| PDF cifrado | PDFBox necesita un temporal → `java.io.tmpdir` apunta al caché de la app |
| ZIP descomprimido | Tope de 200 MB y 5 000 entradas; además se lee **entrada por entrada**, no el ZIP entero |
| `document.xml` enorme | Tope de 50 MB por parte XML |
| El texto extraído | Tope de 2 M de caracteres (2 MB de RAM) |
| `extractedText` en `materials.json` | Deuda conocida: el texto vive en el JSON y se reescribe entero en cada guardado. Mitigación en la iteración 2: moverlo a `courses/<id>/text/<materialId>.txt` (ya está propuesto en `teacher/03-materiales.md` §4.1) |

---

## 9. Nombres de archivos y clases

### 9.1 `:core` — `src/commonMain/kotlin/kitoru/material/`

| Archivo | Contenido | Tipos |
|---|---|---|
| `TextExtractor.kt` | La interfaz y el resultado | `TextExtractor`, `ExtractionResult`, `ExtractionStats` |
| `ExtractionErrorCode.kt` | Errores | `ExtractionErrorCode`, `MaterialExtractionException` |
| `ExtractionLimits.kt` | Topes | `ExtractionLimits` |
| `MaterialFormat.kt` | Formatos | `MaterialFormat` |
| `MaterialFormatDetector.kt` | Detección | `MaterialFormatDetector` |
| `ExtractorRegistry.kt` | Registro y despacho | `ExtractorRegistry` |
| `MaterialExtractionService.kt` | Orquestador | `MaterialExtractionService` |
| `plain/PlainTextExtractor.kt` | `.md` `.txt` `.rst` `.adoc` | `PlainTextExtractor` |
| `plain/Encoding.kt` | Decodificación | `Encoding` (jvmCommonMain) |
| `plain/RstConverter.kt` | RST → Markdown | `RstConverter` |
| `plain/AdocConverter.kt` | ADOC → Markdown | `AdocConverter` |
| `html/HtmlExtractor.kt` | HTML → texto | `HtmlExtractor`, `HtmlParser` |
| `csv/CsvExtractor.kt` | CSV → tabla | `CsvExtractor` |
| `pdf/PdfBackend.kt` | La costura de plataforma | `PdfBackend`, `PdfTextRun`, `PdfContent`, `PdfMetadata`, `PdfReadException` |
| `pdf/PdfAssembler.kt` | Líneas, columnas, escaneo | `PdfAssembler` |
| `pdf/PdfPageCleaner.kt` | Números y encabezados | `PdfPageCleaner` |
| `pdf/PdfScanner.kt` | Estado del PDF | `PdfScanner` |
| `clean/TextCleaner.kt` | Limpieza común | `TextCleaner` |
| `clean/RepeatedHeaderDetector.kt` | Encabezados repetidos | `RepeatedHeaderDetector` |
| `clean/PageNumberStripper.kt` | Números de página | `PageNumberStripper` |
| `lang/LanguageDetector.kt` | Idioma | `LanguageDetector` |
| `office/XmlScanner.kt` | XML en Kotlin puro | `XmlScanner`, `XmlEvent` |
| `link/LinkFetcher.kt` | Descarga de enlaces | `LinkFetcher`, `FetchedLink` |
| `OcrEngine.kt` | Costura del OCR | `OcrEngine` |

### 9.2 `:core` — `src/jvmCommonMain/kotlin/kitoru/material/`

| Archivo | Contenido | Tipos |
|---|---|---|
| `ZipReader.kt` | Lectura de ZIP | `ZipReader` |
| `JvmTextExtractor.kt` | Despacho de JVM | `JvmTextExtractor` |
| `office/DocxExtractor.kt` | Algoritmo DOCX | `DocxExtractor` |
| `office/DocxStyles.kt` | Estilos → encabezado | `DocxStyles` |
| `office/DocxNumbering.kt` | Listas | `DocxNumbering` |
| `office/PptxExtractor.kt` | Algoritmo PPTX | `PptxExtractor` |
| `office/XlsxExtractor.kt` | Algoritmo XLSX | `XlsxExtractor` |
| `office/OoxmlRels.kt` | Relaciones | `OoxmlRels` |

### 9.3 `:core` — específico de plataforma

| Archivo | Contenido |
|---|---|
| `src/desktopMain/…/DesktopPdfBackend.kt` | `org.apache.pdfbox` |
| `src/desktopMain/…/DesktopTextExtractor.kt` | `actual fun platformTextExtractor()` |
| `src/androidMain/…/AndroidPdfBackend.kt` | `com.tom_roush.pdfbox` |
| `src/androidMain/…/AndroidTextExtractor.kt` | `actual fun platformTextExtractor()` |
| `src/androidMain/…/AndroidOcrEngine.kt` | (hito 2) ML Kit Text Recognition |

### 9.4 Cambios en `:core` existentes

| Archivo | Cambio |
|---|---|
| `platform/FileSystem.kt` | **Agregar** `fun readBytes(path: FilePath): ByteArray` y `fun readText(path: FilePath, charset)`; también `fun writeBytes(path, bytes, append)` (lo necesita la copia de archivos en trozos) |

### 9.5 `:backend` — cambios

| Archivo | Cambio |
|---|---|
| `rag/retrieval/DocumentSource.kt` | `CourseMaterialSource` ya filtra por `visible` y por `extractedText.isNotBlank()` → **no hay que tocarlo** para que un PDF escaneado quede fuera del índice ✓ |
| `rag/config/RagConfig.kt` | `extensions` deja de ser útil para los materiales ya extraídos (lo que se indexa es el texto). **Opcional**: dejarla como filtro de "qué se sube", no de "qué se indexa" |

---

## 10. Tests

| Archivo | Source set | Qué cubre |
|---|---|---|
| `core/src/commonTest/…/TextCleanerTest.kt` | common | whitespace, ligaduras, soft hyphen, guiones de corte de línea, bloques de código, BOM |
| `core/src/commonTest/…/RepeatedHeaderDetectorTest.kt` | common | encabezado en 7 de 12 páginas → detectado; en 3 de 12 → no; "Página 3 de 12" y "Página 9 de 12" → la misma clave |
| `core/src/commonTest/…/PageNumberStripperTest.kt` | common | "12", "- 12 -", "Página 3 de 12", "Page 3 of 12", "12 | Título" |
| `core/src/commonTest/…/LanguageDetectorTest.kt` | common | un texto en español → "es"; uno en inglés → "en"; un texto 数学 → "" |
| `core/src/commonTest/…/HtmlExtractorTest.kt` | common | `<script>` y `<nav>` fuera; `<h2>` → `##`; `<li>` → `- `; entidades; `<a href>` → `(url)` |
| `core/src/commonTest/…/CsvExtractorTest.kt` | common | detección de separador, comillas, tabla Markdown, recorte de filas |
| `core/src/commonTest/…/PdfAssemblerTest.kt` | common | **el test clave**: runs sintéticos de una página de dos columnas → el texto sale ordenado por columnas; una página de una columna con un gráfico al centro → **no** se detecta gutter; una página sin texto → escaneo |
| `core/src/commonTest/…/XmlScannerTest.kt` | common | tags, atributos, CDATA, comentarios, entidades, auto-cierre, DOCTYPE |
| `core/src/commonTest/…/MaterialFormatDetectorTest.kt` | common | %PDF, PK, extensión, mismatch |
| `core/src/jvmCommonTest/…/ZipReaderTest.kt` | jvmCommon | ZIP mínimo, entradas, tope de tamaño |
| `core/src/jvmCommonTest/…/DocxExtractorTest.kt` | jvmCommon | **genera un `.docx` mínimo en el test** (un ZIP con `document.xml` y `styles.xml` escritos a mano) y verifica párrafos, encabezados (con `styleId` localizado "Ttulo1"), listas, tablas, hipervínculos, imágenes omitidas, `w:del` excluido, `w:instrText` ignorado |
| `core/src/jvmCommonTest/…/PptxExtractorTest.kt` | jvmCommon | genera un `.pptx` mínimo: verifica que el orden viene de `p:sldIdLst` (¡no del nombre!), las notas, el título |
| `core/src/jvmCommonTest/…/XlsxExtractorTest.kt` | jvmCommon | genera un `.xlsx` mínimo: `sharedStrings`, celdas numéricas, booleanas, de error |
| `core/src/desktopTest/…/PdfExtractionTest.kt` | desktop | **genera un PDF con PDFBox** (dibujando dos columnas con `PDPageContentStream`) y verifica: el texto sale, el orden de lectura es por columnas, la detección de escaneo (un PDF con una imagen y sin texto), las marcas `## Página N` |
| `core/src/desktopTest/…/MaterialExtractionServiceTest.kt` | desktop | pipeline completo con un archivo real: un `.md`, un `.pdf` y un `.docx` generado |

**El test de dos columnas es el más importante del lote.** Si el orden de
lectura se rompe, todo lo demás sigue funcionando y el RAG devuelve basura sin
que nadie lo note.

---

## 11. Checklist de implementación (orden sugerido)

1. `FileSystem.readBytes` / `writeBytes` (`:core`).
2. `ExtractionErrorCode`, `ExtractionLimits`, `ExtractionResult`, `TextExtractor`.
3. `MaterialFormat` + `MaterialFormatDetector` (+ test).
4. `XmlScanner` (+ test) → `ZipReader` → `DocxExtractor` (+ test) → `PptxExtractor` → `XlsxExtractor`.
5. `PlainTextExtractor` + `Encoding` + `RstConverter` + `AdocConverter` (+ test).
6. `HtmlExtractor` + `CsvExtractor` (+ test).
7. `PdfBackend` + `PdfAssembler` (+ test **de dos columnas**) + `PdfPageCleaner` + `PdfScanner`.
8. `DesktopPdfBackend` (pdfbox 2.0.37) + `AndroidPdfBackend` (pdfbox-android 2.0.27.0) + `AndroidPdfInit`.
9. `TextCleaner` + `RepeatedHeaderDetector` + `PageNumberStripper` (+ tests).
10. `LanguageDetector` (+ test).
11. `ExtractorRegistry` + `MaterialExtractionService` + `JvmTextExtractor`.
12. `LinkFetcher` (Ktor) + caché de enlaces.
13. `MaterialIngestService` (de `teacher/03-materiales.md` §4.9) usando el servicio de extracción.
14. Tests end-to-end con archivos reales.

---

## 12. Fuera de alcance (anotado)

- **OCR**: la costura (`OcrEngine`) está definida; la implementación no.
- **EPUB**: detectado y avisado; el extractor queda para la iteración 2.
- **`.doc` / `.xls` / `.rtf` / `.odt`**: no soportados; se avisa al docente.
- **Imágenes sueltas** (`.png`): no soportadas; en la iteración 2, OCR.
- **Búsqueda semántica / embeddings**: el índice es BM25 léxico. El texto
  extraído no depende de eso, así que migrar después no toca este documento.
- **Indexación incremental**: hoy se reindexa todo (BM25 en memoria: < 200 ms
  para 100 documentos).
- **Guardar el texto fuera de `materials.json`**: deuda conocida (§8.3).
