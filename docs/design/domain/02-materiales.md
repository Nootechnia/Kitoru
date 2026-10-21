# 02 · Materiales de la cátedra (el corpus del RAG)

> Subsistema de materiales: qué es un material, cómo entra, cómo se le saca
> el texto, cómo se versiona, cómo se organiza, quién lo ve y cuánto ocupa.

## 0. Decisiones en una línea

| Decisión | Respuesta | Motivo |
|---|---|---|
| ¿Dónde vive el texto extraído? | En disco (`text/extracted.md`), **fuera** del catálogo JSON | `config/materials.json` se reescribe entero en cada guardado; con el texto adentro, 50 apuntas de 200 KB son 10 MB de JSON reescrito por cada material nuevo. |
| ¿Un material es un archivo o un par (archivo + texto)? | Un **archivo** (el original) + un `Material` (metadatos) + un texto derivado en disco | El original es la fuente de verdad; el texto es un caché regenerable. |
| Estados | `MaterialStatus`: `PENDING`, `EXTRACTING`, `READY`, `FAILED`, `STALE` | Una máquina explícita, no un `isIndexed: Boolean`: el docente tiene que ver *por qué* un material no está en el índice. |
| ¿Quién mueve el estado? | Sólo `MaterialIndexer` y `MaterialService`; la UI pide **acciones** | Si la UI escribe `status`, aparecen estados imposibles ("Listo" sin texto). |
| Formatos | `.md`, `.txt`, `.pdf`, `.docx`, `.pptx`, `.csv`, `.html`, `.epub` | Cubre el 95 % de lo que sube un docente. `.doc`/`.ppt`/`.odt` quedan fuera. |
| PDF | `pdfbox-android` (Android) / `pdfbox` (desktop), **behind `expect/actual`** | Es la única opción Android-compatible y license Apache-2.0. Los paquetes difieren (`com.tom_roush.pdfbox.*` vs `org.apache.pdfbox.*`) → el código se duplica en un `actual` por plataforma. |
| docx / pptx | **ZIP + XML a mano**, no Apache POI | POI en Android es un problema conocido: falta `java.awt`, no hay StAX en el boot classpath, `poi-ooxml` + XMLBeans pesan ~10 MB y rompen el límite de métodos de dex. ZIP (`java.util.zip`) y DOM (`javax.xml.parsers`) **sí** están en el boot classpath de Android (API 1). |
| PDF escaneado | `warning` (no error): el material queda `READY` con 0 caracteres y no aporta al índice | No es un fallo de la app: es un archivo sin capa de texto. El mensaje se lo sube al docente, no al alumno. |
| ¿Re-subir el mismo archivo crea versión? | **Mismo contenido (mismo SHA-256) → NO**: se actualiza en el sitio. **Contenido distinto → sí**, versión nueva encadenada | Un índice con dos copias casi idénticas parte el IDF de BM25, duplica citas y hace que el modelo cite la versión vieja. |
| ¿El índice tiene las dos versiones? | **No.** Sólo entra la versión vigente (`supersededBy` vacío) | Ver arriba. Las versiones viejas quedan en disco (auditoría) pero fuera del índice; el docente puede "revertir" a una anterior. |
| Unidades / temas | **Sí**, `CourseUnit` con **2 niveles** ("Unidad 1" → "Paradigmas"); el material apunta a una unidad con `unitId` | El docente ya piensa así ("lo de la unidad 2"), y el alumno pregunta así. Un árbol más profundo no aporta: el `Chunker` ya parte por los encabezados del propio documento. |
| ¿Las unidades entran al RAG? | Como **filtro** (preguntar sólo sobre la unidad 2) y como **bonus de score** (+25 %); el nombre de la unidad además se indexa como texto | No como estructura: el RAG no necesita un árbol, necesita que "paradigmas" encuentre el material de Paradigmas. |
| Visibilidad | 3 estados: `STUDENTS`, `TEACHERS_ONLY`, `HIDDEN` | Ocultar tiene dos motivos distintos: "no lo vean los alumnos pero lo use el docente" (guías, respuestas) y "no lo use nadie" (borrador, duplicado). |
| ¿El material oculto entra al RAG del alumno? | **NO.** Sólo `STUDENTS` entra al RAG del alumno | Regla 6 del proyecto: el docente no ve (ni filtra de forma distinta) lo que no debe. Un material "sólo para el docente" jamás puede aparecer en una respuesta de un alumno. |
| Almacenamiento | `courses/<courseId>/materials/<materialId>/{original/,text/,meta.json}` | Una carpeta por material = borrar es borrar una carpeta; el texto se puede regenerar; `meta.json` permite **reconstruir el catálogo** si `config/materials.json` se corrompe. |
| Cuota | 50 MB por material, 1 GB por cátedra, 500 materiales por cátedra | Números conservadores para un móvil: el original + el texto extraído + el índice BM25 en memoria. |
| Al exceder la cuota | La subida **se rechaza** con un mensaje en español, antes de copiar el archivo | Fallar rápido y no dejar basura en disco a medias. |

---

## 1. El modelo `Material`

### 1.1 Archivos

El material **sale de `Course.kt`** y pasa a su propio archivo, porque ya no son
dos líneas: son cuatro tipos y una máquina de estados.

- `core/src/commonMain/kotlin/kitoru/domain/Material.kt` — `Material`,
  `MaterialKind`, `MaterialFormat`, `MaterialStatus`, `MaterialVisibility`,
  `CourseUnit`.
- `core/src/commonMain/kotlin/kitoru/domain/MaterialQuota.kt` — los topes.

### 1.2 `Material` final (copiar y pegar)

```kotlin
package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Material de la cátedra: el corpus del RAG.
 *
 * Un `Material` es **una versión de un archivo**. Cuando el docente re-sube
 * el mismo material con otro contenido se crea un `Material` nuevo
 * (`version = N + 1`) encadenado al anterior por [previousVersionId],
 * [rootId] y [supersededBy].
 *
 * El texto extraído **no** se guarda en el catálogo: vive en
 * `courses/<courseId>/materials/<materialId>/text/extracted.md` y se hidrata
 * en memoria ([extractedText]) sólo cuando hace falta.
 */
@Serializable
data class Material(
    /** Id único de esta versión: "curso|material|uuid". */
    val id: String,
    val courseId: String,
    /** Título que ve el alumno. Editable; no es el nombre del archivo. */
    val title: String,
    /** Tipo pedagógico que eligió el docente. */
    val kind: MaterialKind = MaterialKind.DOCUMENT,
    /** Formato técnico, detectado por extensión. */
    val format: MaterialFormat = MaterialFormat.UNKNOWN,

    // ---------------- Archivos ----------------

    /** Ruta del original, relativa a `courses/<courseId>/`. */
    val path: String = "",
    /** Ruta del texto extraído, relativa a `courses/<courseId>/`. */
    val textPath: String = "",
    /** Nombre del archivo tal como lo subió el docente (ya sanitizado). */
    val originalFileName: String = "",
    /** Tamaño del original, en bytes. */
    val sizeBytes: Long = 0L,
    /** SHA-256 del original en hex minúsculas ("" si no se calculó). */
    val checksum: String = "",
    /** MIME del original ("" si es un enlace). */
    val mimeType: String = "",

    // ---------------- Contenido ----------------

    /** Páginas (PDF / EPUB / PPTX); 0 si el formato no las tiene. */
    val pageCount: Int = 0,
    /** Caracteres del texto extraído (0 si no se pudo extraer). */
    val extractedChars: Int = 0,
    /** Idioma ISO 639-1 detectado ("es"); "" si no se pudo decidir. */
    val language: String = "",

    // ---------------- Estado ----------------

    val status: MaterialStatus = MaterialStatus.PENDING,
    /** Error duro de extracción (mensaje en español). Vacío si no falló. */
    val extractionError: String = "",
    /** Advertencia no fatal (ej. "El PDF no tiene capa de texto"). */
    val warning: String = "",
    /** Versión del extractor que produjo el texto (para forzar re-extracción). */
    val extractorVersion: Int = 1,

    // ---------------- Versionado ----------------

    /** Número de versión dentro de la cadena (1 = original). */
    val version: Int = 1,
    /** Id de la versión anterior ("" si esta es la primera). */
    val previousVersionId: String = "",
    /** Id de la primera versión de la cadena (estable entre versiones). */
    val rootId: String = "",
    /** Id de la versión que reemplaza a esta ("" si es la vigente). */
    val supersededBy: String = "",

    // ---------------- Organización ----------------

    /** Etiquetas libres ("parcial-1", "teoria", "2026"). */
    val tags: List<String> = emptyList(),
    /** Unidad o tema de la cátedra (ver [CourseUnit]). "" si no tiene. */
    val unitId: String = "",
    /** A quién ve este material. */
    val visibility: MaterialVisibility = MaterialVisibility.STUDENTS,

    // ---------------- Autoría ----------------

    /** Id del usuario que lo subió. */
    val uploadedBy: String = "",
    /** Epoch millis de la última subida de esta versión. */
    val uploadedAt: Long = 0L,
    /** Epoch millis de la última modificación de los metadatos. */
    val updatedAt: Long = 0L,
) {
    /**
     * Texto extraído, hidratado en memoria desde
     * `courses/<courseId>/<textPath>`. **No se serializa**: por eso el
     * catálogo JSON sigue siendo liviano.
     */
    @Transient
    val extractedText: String = ""

    /** Extensión del original, sin punto y en minúsculas. */
    val extension: String get() = originalFileName.substringAfterLast('.', "").lowercase()

    /** ¿Es la versión vigente de su cadena? */
    val isCurrent: Boolean get() = supersededBy.isBlank()

    /** ¿Hay una versión más nueva que esta? */
    val isSuperseded: Boolean get() = !isCurrent

    /** ¿Aporta texto al índice? (no mira la visibilidad) */
    val isIndexable: Boolean
        get() = status == MaterialStatus.READY && isCurrent && extractedChars > 0

    /** Visible para los alumnos. Atajo de dominio. */
    val visible: Boolean get() = visibility.studentVisible
}
```

### 1.3 `MaterialKind` (sin cambios) y `MaterialFormat` (nuevo)

`kind` es **pedagógico** (lo elige el docente: "esto son diapositivas"),
`format` es **técnico** (lo detecta la extensión). Antes se confundían: un
`.pptx` podía ser `SLIDES` o `DOCUMENT` según el humor del docente, y el
extractor no sabía qué hacer.

```kotlin
/** Tipo de material de la cátedra (lo elige el docente). */
@Serializable
enum class MaterialKind(val label: String) {
    DOCUMENT("Documento"),
    SLIDES("Diapositivas"),
    TRANSCRIPT("Apuntes"),
    LINK("Enlace"),
    OTHER("Otro"),
    ;

    companion object {
        fun fromName(name: String): MaterialKind? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** Formato técnico del archivo (lo detecta la extensión). */
@Serializable
enum class MaterialFormat(
    val label: String,
    val mimeType: String,
) {
    MARKDOWN("Markdown", "text/markdown"),
    TEXT("Texto plano", "text/plain"),
    PDF("PDF", "application/pdf"),
    DOCX(
        "Word (.docx)",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ),
    PPTX(
        "PowerPoint (.pptx)",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ),
    CSV("CSV", "text/csv"),
    HTML("HTML", "text/html"),
    EPUB("EPUB", "application/epub+zip"),
    /** Un enlace: no hay archivo, el texto lo pega el docente. */
    LINK("Enlace", ""),
    /** Extensión no reconocida. */
    UNKNOWN("Sin formato", "application/octet-stream"),
    ;

    /** ¿Se le puede extraer texto? */
    val extractable: Boolean get() = this != LINK && this != UNKNOWN

    companion object {
        private val BY_EXTENSION: Map<String, MaterialFormat> = mapOf(
            "md" to MARKDOWN, "markdown" to MARKDOWN,
            "txt" to TEXT, "text" to TEXT, "log" to TEXT,
            "pdf" to PDF,
            "docx" to DOCX,
            "pptx" to PPTX,
            "csv" to CSV,
            "html" to HTML, "htm" to HTML, "xhtml" to HTML,
            "epub" to EPUB,
        )

        /** Detecta el formato por el nombre del archivo. */
        fun fromFileName(fileName: String): MaterialFormat =
            BY_EXTENSION[fileName.substringAfterLast('.', "").lowercase()] ?: UNKNOWN

        fun fromName(name: String): MaterialFormat? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
```

### 1.4 `MaterialVisibility`

```kotlin
/**
 * A quién ve un material.
 *
 * Reemplaza al `visible: Boolean` anterior, que no distinguía "lo tengo
 * acá pero es una guía del docente" de "esto ni lo indexes".
 */
@Serializable
enum class MaterialVisibility(val label: String, val description: String) {
    /** Se muestra en la cátedra y alimenta el RAG de todos. */
    STUDENTS(
        "Visible para los alumnos",
        "Los alumnos lo ven en la lista y el chat responde con su contenido.",
    ),

    /** Sólo el equipo docente. Nunca entra al RAG de un alumno. */
    TEACHERS_ONLY(
        "Sólo para el docente",
        "Los alumnos no lo ven. Lo usan los docentes: guías, respuestas, borradores de aviso.",
    ),

    /** No entra al RAG de nadie. Queda archivado. */
    HIDDEN(
        "Oculto",
        "No aparece en el RAG de nadie. Sirve para guardar un archivo sin indexarlo.",
    ),
    ;

    /** ¿Lo ven los alumnos? */
    val studentVisible: Boolean get() = this == STUDENTS

    companion object {
        fun fromName(name: String): MaterialVisibility? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
```

### 1.5 `CourseUnit` (unidades y temas)

```kotlin
/**
 * Unidad o tema de la cátedra.
 *
 * Jerarquía de **dos niveles**: las raíces son las unidades ("Unidad 1") y
 * sus hijos son los temas ("Paradigmas"). No se admite un tercer nivel
 * (ver §5.2).
 */
@Serializable
data class CourseUnit(
    val id: String,
    val courseId: String,
    /** "Unidad 1" (raíz) o "Paradigmas" (tema). */
    val title: String,
    /** Orden entre los hermanos. */
    val order: Int = 0,
    /** Id de la unidad padre ("" si esta es una unidad raíz). */
    val parentId: String = "",
    val createdAt: Long = 0L,
) {
    /** ¿Es una unidad (nivel raíz)? */
    val isRoot: Boolean get() = parentId.isBlank()
}
```

### 1.6 Por qué `extractedText` es `@Transient`

El catálogo vive en `config/materials.json` (`JsonListStore`), que **reescribe
el archivo entero** en cada `save`. Con el texto adentro:

- 50 apuntas de 200 KB ⇒ 10 MB de JSON reescritos por cada material nuevo.
- El texto es **derivado**: se puede regenerar desde el original. Guardarlo en
  dos places sólo crea la posibilidad de que se desincronicen.

Por eso:

- El texto vive en `courses/<courseId>/materials/<materialId>/text/extracted.md`.
- `Material.extractedText` queda como campo **transitorio** (hidratable con
  `copy(extractedText = ...)`), para no romper los call sites que ya lo leen.
- `Material.extractedChars` (persistido) es el que permite saber, sin tocar
  disco, si el material aporta algo al índice.

> `@Transient` en el constructor primario es un patrón soportado por
> kotlinx.serialization (`data class Project(val name: String, @Transient val
> language: String = "Kotlin")`): la propiedad **no entra** en el descriptor
> de serialización. Consecuencia práctica: los `materials.json` viejos, que
> tenían `"extractedText"`, siguen decodificando porque `KitoruJson` tiene
> `ignoreUnknownKeys = true` (sin eso, kotlinx tiraría `JsonDecodingException`
> por una clave transitoria).

El `FileSystem` actual sólo tiene `readText`/`writeText`; para leer y escribir
el **original binario** hay que ampliarlo (§7.6).

---

## 2. Estado de indexación: la máquina de estados

### 2.1 `MaterialStatus`

```kotlin
/**
 * Estado de un material en el ciclo de indexación.
 *
 * `status` describe **la extracción de los bytes de esta versión**. Si esta
 * versión fue reemplazada por otra, lo dice [Material.supersededBy], no el
 * estado: son dos ejes distintos y separarlos evita estados imposibles.
 */
@Serializable
enum class MaterialStatus(val label: String, val description: String) {
    /** Subido, todavía sin extraer. */
    PENDING("Pendiente", "Se subió pero todavía no se leyó su contenido."),

    /** Extrayendo el texto ahora mismo. */
    EXTRACTING("Extrayendo", "Se está leyendo el archivo para extraer su texto."),

    /** Texto extraído y guardado en disco. */
    READY("Listo", "El texto se extrajo y el material está en el índice."),

    /** La extracción falló. Hay [Material.extractionError]. */
    FAILED("Error", "No se pudo extraer el texto del archivo."),

    /** El texto guardado ya no es de fiar (o hay versión más nueva). */
    STALE("Desactualizado", "El texto guardado corresponde a una versión anterior del archivo."),
    ;

    /** ¿Se permite pasar a [next]? */
    fun canTransitionTo(next: MaterialStatus): Boolean = when (this) {
        PENDING -> next == EXTRACTING || next == FAILED
        EXTRACTING -> next == READY || next == FAILED
        READY -> next == EXTRACTING || next == STALE
        FAILED -> next == EXTRACTING || next == STALE
        STALE -> next == EXTRACTING || next == READY
    }

    /** Falla rápido si la transición no está permitida (regla 4 del proyecto). */
    fun requireTransition(next: MaterialStatus) {
        check(canTransitionTo(next)) {
            "Transición de material no permitida: $label → ${next.label}"
        }
    }

    companion object {
        fun fromName(name: String): MaterialStatus? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
```

### 2.2 Transiciones

| Desde | Hacia | Quién lo mueve | Cuándo |
|---|---|---|---|
| — (nuevo) | `PENDING` | `MaterialService.upload` | Se creó el material y el original está en disco. |
| `PENDING` | `EXTRACTING` | `MaterialIndexer.index` | Empieza la extracción. |
| `EXTRACTING` | `READY` | `MaterialIndexer.index` | Se extrajo texto y se escribió `text/extracted.md`. |
| `EXTRACTING` | `FAILED` | `MaterialIndexer.index` | Error: corrupto, cifrado, zip bomb, sin permiso. |
| `FAILED` | `EXTRACTING` | `MaterialIndexer.index` | Reintento (manual, o automático: hasta 2 reintentos). |
| `READY` | `EXTRACTING` | `MaterialIndexer.index` | Reindexación a pedido del docente. |
| `READY` | `STALE` | `MaterialService` | Se subió una versión nueva que ya está `READY`, o el checksum del archivo en disco cambió. |
| `FAILED` | `STALE` | `MaterialService` | Quedó reemplazada por una versión nueva. |
| `STALE` | `EXTRACTING` | `MaterialIndexer.index` | Reindexar (el docente lo pidió, o cambió el extractor). |
| `STALE` | `READY` | `MaterialService` | *Rollback*: la versión nueva falló al extraerse y vuelve la anterior. |

Cualquier otra transición lanza `IllegalStateException` con mensaje en
español (`MaterialStatus.requireTransition`).

### 2.3 Quién puede mover el estado

**Sólo dos clases**:

- `MaterialIndexer` (`:materials`) — extrae el texto y mueve
  `PENDING|FAILED|STALE|READY → EXTRACTING → READY|FAILED`.
- `MaterialService` (`:materials`) — crea materiales y hace el *swap* de
  versiones (`→ STALE`) y el *rollback* (`→ READY`).

**La UI nunca escribe `status`.** La UI llama acciones:

```kotlin
suspend fun upload(course: Course, request: UploadRequest, uploadedBy: String): Material
suspend fun reindex(material: Material): Material
suspend fun replace(material: Material, request: UploadRequest, uploadedBy: String): Material
suspend fun setVisibility(material: Material, visibility: MaterialVisibility): Material
suspend fun setUnit(material: Material, unitId: String): Material
suspend fun delete(material: Material)
```

Si la UI pudiera escribir `status`, aparecerían estados imposibles ("Listo"
sin texto, "Error" sin `extractionError`) y el `KnowledgeBase` no sabría si
puede indexar.

### 2.4 Recuperación al arrancar

Si la app muere durante una extracción, el material queda `EXTRACTING` para
siempre. Al arrancar, `MaterialService.recover()` hace:

```kotlin
/** Los materiales que quedaron a medio extraer vuelven a la cola. */
suspend fun recover() {
    repo.listForCourse(courseId)   // por cátedra
        .filter { it.status == MaterialStatus.EXTRACTING }
        .forEach { repo.save(it.copy(status = MaterialStatus.PENDING)) }
}
```

Es idempotente y no necesita disco: `EXTRACTING` nunca sobrevive a un
arranque.

### 2.5 Qué ve el docente en la lista

| Estado | Chip | Botón |
|---|---|---|
| `PENDING` | "Pendiente" (gris) | "Indexar" |
| `EXTRACTING` | "Extrayendo…" (con spinner) | — (deshabilitado) |
| `READY` | "Listo" (verde) + `N car.` | "Reindexar", "Descargar" |
| `READY` + `warning` | "Listo ⚠" (ámbar) + el texto de `warning` | "Reindexar" |
| `FAILED` | "Error" (rojo) + `extractionError` | "Reintentar", "Descargar" |
| `STALE` | "Desactualizado" (naranja) | "Reindexar", "Revertir a la anterior" |

---

## 3. Extracción de texto

### 3.1 El contrato

```kotlin
// materials/src/commonMain/kotlin/kitoru/materials/Extraction.kt

/**
 * Resultado de extraer texto de un archivo.
 *
 * [error] es un fallo duro (el material queda `FAILED`); [warning] es un
 * matiz (el material queda `READY` pero con una advertencia visible).
 */
class ExtractionResult(
    val text: String = "",
    val pageCount: Int = 0,
    val warning: String = "",
    val error: String = "",
    val language: String = "",
) {
    val ok: Boolean get() = error.isBlank()
    val isEmpty: Boolean get() = text.isBlank()
}

/** Lo que recibe un extractor. */
class ExtractionInput(
    val fileName: String,
    val bytes: ByteArray,
)

/** Extrae texto de un formato. */
interface TextExtractor {
    val format: MaterialFormat
    suspend fun extract(input: ExtractionInput): ExtractionResult
}

/** Registro de extractores, uno por formato soportado. */
class ExtractorRegistry(extractors: List<TextExtractor>) {
    private val byFormat: Map<MaterialFormat, TextExtractor> =
        extractors.associateBy { it.format }

    fun forFormat(format: MaterialFormat): TextExtractor? = byFormat[format]

    /** Formatos que el usuario puede subir (para los mensajes de error). */
    val supported: Set<MaterialFormat> get() = byFormat.keys
}
```

### 3.2 Formato por formato

| Formato | Estrategia | Librería | Dónde vive el código | Salida |
|---|---|---|---|---|
| `.md` | Lectura directa | ninguna | `materials/src/commonMain/…/MarkdownExtractor.kt` | Markdown tal cual (el `Chunker` ya parte por `#`) |
| `.txt` | Lectura directa (UTF-8 con fallback Latin-1) | ninguna | `materials/src/commonMain/…/PlainTextExtractor.kt` | Texto plano |
| `.pdf` | PDFBox | **desktop**: `org.apache.pdfbox:pdfbox:2.0.27` · **Android**: `com.tom-roush:pdfbox-android:2.0.27.0` | `materials/src/desktopMain/…/PdfEngine.desktop.kt` · `materials/src/androidMain/…/PdfEngine.android.kt` | Texto por página + nº de páginas |
| `.docx` | **ZIP + XML a mano** | `java.util.zip` + `javax.xml.parsers` (ambos en el boot classpath de Android) | `materials/src/jvmCommonMain/…/DocxExtractor.kt` | Párrafos, con los `Heading N` como `#` |
| `.pptx` | **ZIP + XML a mano** | ídem | `materials/src/jvmCommonMain/…/PptxExtractor.kt` | `# Diapositiva N` + renglones |
| `.csv` | Parser RFC 4180 propio | ninguna | `materials/src/commonMain/…/CsvExtractor.kt` | Tabla Markdown |
| `.html` | jsoup | `org.jsoup:jsoup:1.17.2` (Java puro, funciona en Android) | `materials/src/jvmCommonMain/…/HtmlExtractor.kt` | Markdown con encabezados |
| `.epub` | ZIP + XML (container → OPF → spine) + jsoup para el XHTML | ídem | `materials/src/jvmCommonMain/…/EpubExtractor.kt` | `# Capítulo` + texto |
| `.doc`, `.ppt`, `.odt`, `.rtf` | — | — | — | **No soportado**: error al subir, con la lista de formatos válidos |
| imágenes, `.zip`, `.mp4` | — | — | — | **No soportado** |

### 3.3 Por qué NO Apache POI (docx/pptx)

POI es la respuesta obvia para `.docx`/`.pptx`, pero en Android es un
problema conocido y documentado:

1. **Falta `java.awt`.** POI usa `java.awt.Color`, `java.awt.geom` y otras
   clases que Android no tiene: reventan en tiempo de ejecución, no de
   compilación.
2. **No hay StAX.** `poi-ooxml` exige un `XMLInputFactory`. El boot classpath
   de Android (`core/tasks/check_boot_jars/package_whitelist.txt`) incluye
   `javax\.xml\.parsers` y `javax\.xml\.transform`, pero **no**
   `javax\.xml\.stream`. Hay que shadyar Aalto y setear tres `System.setProperty`.
3. **Peso y límite de dex.** `poi` + `poi-ooxml` + `poi-ooxml-lite` +
   `xmlbeans` + `commons-compress` ≈ 10 MB y se comer el límite de métodos de
   `classes.dex` (la causa clásica del error *"Too many method references"*).
4. **Mantenimiento.** El proyecto `centic9/poi-on-android` existe justamente
   porque hay que *arreglar* POI para que ande en Android.

La alternativa —**leer el ZIP y el XML a mano**— usa sólo
`java.util.zip.ZipInputStream` y `javax.xml.parsers.DocumentBuilderFactory`,
que están en el boot classpath de Android desde la API 1, y son las mismas
clases en desktop y en Android ⇒ **un solo código para las dos plataformas**.
Son ~150 líneas por formato y no agregan ni un KB al APK.

La comparación se lee en la práctica: para extraer texto de un `.docx` sólo
necesitamos `word/document.xml` y los nodos `<w:t>`. No necesitamos estilos,
tablas, imágenes ni escritura. POI resuelve un problema que Kitoru no tiene.

### 3.4 PDF: por plataforma

El PDF es el único formato que **necesita una dependencia pesada**, y la
dependencia no es la misma en las dos plataformas:

| Plataforma | Dependencia | Paquetes | Notas |
|---|---|---|---|
| Desktop | `org.apache.pdfbox:pdfbox:2.0.27` (jar, trae `commons-logging`) | `org.apache.pdfbox.*` | Referencia upstream. |
| Android | `com.tom-roush:pdfbox-android:2.0.27.0` (AAR, 3.1 MB, Apache-2.0) | `com.tom_roush.pdfbox.*` | Puerto de PDFBox 2.0.27; requiere API 19+ (nosotros: 24). |

**Ojo con los paquetes**: el puerto de Tom Roush **reempaqueta** las clases
(`com.tom_roush.pdfbox.pdmodel.PDDocument`, `com.tom_roush.pdfbox.text.PDFTextStripper`,
`com.tom_roush.pdfbox.android.PDFBoxResourceLoader`), no mantiene
`org.apache.pdfbox`. Por eso el código de PDF **no puede ser `commonMain`**: va
como `expect`/`actual`, con el mismo cuerpo y distinto `import`.

```kotlin
// materials/src/commonMain/kotlin/kitoru/materials/PdfEngine.kt

/**
 * Motor de extracción de PDF.
 *
 * Desktop usa Apache PDFBox; Android usa `pdfbox-android` (mismo PDFBox 2.0.x
 * reempaquetado). Por eso es un `expect`: las clases Java tienen paquetes
 * distintos en cada plataforma.
 */
interface PdfEngine {
    fun extract(input: ExtractionInput): ExtractionResult
}

expect fun createPdfEngine(): PdfEngine
```

```kotlin
// materials/src/desktopMain/kotlin/kitoru/materials/PdfEngine.desktop.kt
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper

actual fun createPdfEngine(): PdfEngine = object : PdfEngine {
    override fun extract(input: ExtractionInput): ExtractionResult {
        val doc = runCatching { PDDocument.load(input.bytes.inputStream()) }
            .getOrElse { e ->
                return ExtractionResult(error = "No se pudo leer el PDF: ${e.message ?: "archivo inválido"}")
            }
        doc.use { d ->
            val pages = d.numberOfPages
            if (pages > MaterialQuota.MAX_PAGES) {
                return ExtractionResult(
                    error = "El PDF tiene $pages páginas y supera el máximo de ${MaterialQuota.MAX_PAGES}.",
                )
            }
            val text = PDFTextStripper().apply { sortByPosition = true }.getText(d)
            return ExtractionResult(
                text = text.trim(),
                pageCount = pages,
                warning = scannedWarning(text, pages),
            )
        }
    }
}
```

```kotlin
// materials/src/androidMain/kotlin/kitoru/materials/PdfEngine.android.kt
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

actual fun createPdfEngine(): PdfEngine = object : PdfEngine {
    override fun extract(input: ExtractionInput): ExtractionResult {
        // En Android hay que llamar PDFBoxResourceLoader.init(context) al
        // arrancar la app: sin eso no encuentra las fuentes estándar y el
        // texto sale vacío o corrupto. Lo hace KitoruAndroid.init(context).
        val doc = runCatching { PDDocument.load(input.bytes.inputStream()) }
            .getOrElse { e ->
                return ExtractionResult(error = "No se pudo leer el PDF: ${e.message ?: "archivo inválido"}")
            }
        doc.use { d ->
            val pages = d.numberOfPages
            if (pages > MaterialQuota.MAX_PAGES) {
                return ExtractionResult(
                    error = "El PDF tiene $pages páginas y supera el máximo de ${MaterialQuota.MAX_PAGES}.",
                )
            }
            val text = PDFTextStripper().apply { sortByPosition = true }.getText(d)
            return ExtractionResult(
                text = text.trim(),
                pageCount = pages,
                warning = scannedWarning(text, pages),
            )
        }
    }
}
```

El `scannedWarning` es compartido (`commonMain`):

```kotlin
/** El PDF no tiene capa de texto: casi seguro es un escaneo. */
internal fun scannedWarning(text: String, pages: Int): String =
    if (pages > 0 && text.isBlank()) {
        "El PDF no tiene capa de texto (probablemente es un escaneo): no aporta nada al índice. " +
            "Subí un PDF con texto seleccionable o convertilo con OCR."
    } else {
        ""
    }
```

### 3.5 PDF escaneado: `warning`, no `error`

Un PDF escaneado **no es un error de la app**: el archivo está perfecto, sólo
que la información está en píxeles. Por eso:

- `status = READY`, `extractedChars = 0`, `warning` con el texto de arriba.
- `isIndexable == false` ⇒ **no entra al índice** (no aporta chunks).
- El material **sigue visible** en la lista del docente y de los alumnos: el
  alumno lo puede descargar y leerlo, aunque la IA no lo pueda citar.
- El texto de la `warning` es lo que ve el docente. El alumno ve "Este material
  no tiene texto extraíble" (un mensaje derived de `extractedChars == 0`), no
  el detalle técnico.

**No hay OCR.** No entra en el alcance: requiere Tesseract (4 MB + modelos) o
un servicio externo. Cuando exista Kitoru-Server, el OCR del lado del servidor
es la salida natural. Queda anotado como no-goal explícito.

### 3.6 Los extractores en detalle

**Markdown y texto plano** (`commonMain`, sin dependencias):

```kotlin
// materials/src/commonMain/kotlin/kitoru/materials/PlainTextExtractor.kt

/** Texto plano y Markdown: se lee tal cual, sólo se normalizan los finales de línea. */
object PlainTextExtractor : TextExtractor {

    override val format = MaterialFormat.TEXT

    override suspend fun extract(input: ExtractionInput): ExtractionResult {
        val text = decodeText(input.bytes).normalizeNewlines()
        return ExtractionResult(text = text)
    }
}

/** Decodifica UTF-8 y, si el resultado parece roto, prueba Latin-1. */
internal fun decodeText(bytes: ByteArray): String {
    val utf8 = bytes.decodeToString()
    val broken = utf8.count { it == '\uFFFD' }
    if (broken > 0 && broken * 100 > utf8.length) return decodeLatin1(bytes)
    return utf8
}

/** Latin-1 a mano: `Charsets.ISO_8859_1` no existe en `commonMain`. */
internal fun decodeLatin1(bytes: ByteArray): String =
    bytes.map { (it.toInt() and 0xFF).toChar() }.joinToString("")

/** CRLF → LF y CR → LF. */
internal fun String.normalizeNewlines(): String =
    replace("\r\n", "\n").replace('\r', '\n')
```

El Markdown usa el mismo lector con `format = MARKDOWN`; no se toca nada: el
`Chunker` de `:backend` ya parte por los `#`.

**CSV** (`commonMain`, parser propio):

```kotlin
// materials/src/commonMain/kotlin/kitoru/materials/CsvExtractor.kt

/**
 * CSV → tabla Markdown.
 *
 * Parser RFC 4180 propio: comillas dobles, comillas escapadas (""), saltos de
 * línea dentro del campo y separador autodetectado (`,` `;` tab).
 */
object CsvExtractor : TextExtractor {

    override val format = MaterialFormat.CSV

    override suspend fun extract(input: ExtractionInput): ExtractionResult {
        val raw = decodeText(input.bytes).normalizeNewlines()
        if (raw.isBlank()) return ExtractionResult(warning = "El CSV está vacío.")
        val rows = parseCsv(raw)
        if (rows.isEmpty()) return ExtractionResult(warning = "El CSV no tiene filas.")
        val head = rows.first()
        val body = rows.drop(1).take(MaterialQuota.MAX_CSV_ROWS)
        val sb = StringBuilder()
        sb.appendLine("| " + head.joinToString(" | ") + " |")
        sb.appendLine("| " + head.joinToString(" | ") { "---" } + " |")
        for (r in body) sb.appendLine("| " + r.joinToString(" | ") + " |")
        val warning = if (rows.size - 1 > MaterialQuota.MAX_CSV_ROWS) {
            "El CSV tiene ${rows.size - 1} filas; sólo se indexaron las primeras ${MaterialQuota.MAX_CSV_ROWS}."
        } else ""
        return ExtractionResult(text = sb.toString(), warning = warning)
    }
}
```

**DOCX** (`jvmCommonMain`, ZIP + DOM):

```kotlin
// materials/src/jvmCommonMain/kotlin/kitoru/materials/DocxExtractor.kt

private const val WML = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

/**
 * DOCX → Markdown.
 *
 * Un .docx es un ZIP. El texto está en `word/document.xml`, en los nodos
 * `<w:t>`. Los párrafos (`<w:p>`) se convierten en líneas y los estilos
 * `Heading1..6` en `#`, para que el `Chunker` pueda partir por sección.
 */
object DocxExtractor : TextExtractor {

    override val format = MaterialFormat.DOCX

    override suspend fun extract(input: ExtractionInput): ExtractionResult {
        val xml = ZipText.readEntry(input.bytes, "word/document.xml")
            ?: return ExtractionResult(
                error = "El .docx no contiene word/document.xml (¿archivo corrupto, cifrado o renombrado?).",
            )
        val doc = runCatching { ZipText.parseXml(xml) }.getOrElse {
            return ExtractionResult(error = "El .docx tiene un XML inválido: ${it.message}")
        }
        val paragraphs = doc.getElementsByTagNameNS(WML, "p")
        val sb = StringBuilder()
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i)
            val level = headingLevel(p)
            val runs = p.getElementsByTagNameNS(WML, "t")
            val line = buildString {
                for (j in 0 until runs.length) append(runs.item(j).textContent.orEmpty())
            }.trim()
            if (line.isEmpty()) continue
            if (level > 0) sb.appendLine("#".repeat(level) + " " + line)
            else sb.appendLine(line)
        }
        return ExtractionResult(text = sb.toString().trim())
    }
}

/**
 * Nivel del encabezado de un párrafo de Word (0 = no es encabezado).
 *
 * El estilo va en `<w:pPr><w:pStyle w:val="Heading1"/>`. Se leen los dos
 * primeros niveles del nombre del estilo ("Heading1", "Heading 1", "Ttulo1").
 */
private fun headingLevel(p: org.w3c.dom.Element): Int {
    val style = p.getElementsByTagNameNS(WML, "pStyle")
    if (style.length == 0) return 0
    val value = style.item(0).getAttributes().getNamedItem("w:val")?.nodeValue.orEmpty()
    val digits = value.filter { it.isDigit() }
    val level = digits.take(1).toIntOrNull() ?: return 0
    return level.coerceIn(1, 6)
}
```

**PPTX** (`jvmCommonMain`): se leen `ppt/slides/slideN.xml` ordenadas por
número; cada `<a:t>` es un fragmento de texto y cada `<a:p>` una línea. Se
emite `# Diapositiva N` para que el chunker treats cada slide como sección.

**EPUB** (`jvmCommonMain`): `META-INF/container.xml` → `rootfile@full-path` →
el OPF → `<spine>` (orden de lectura) → cada ítem XHTML se pasa por el
extractor de HTML. El título del capítulo sale del `<h1>` o del `<title>`.

**HTML** (`jvmCommonMain`, jsoup):

```kotlin
// materials/src/jvmCommonMain/kotlin/kitoru/materials/HtmlExtractor.kt

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node

/**
 * HTML → Markdown.
 *
 * jsoup es Java puro y funciona en Android (con *core library desugaring*
 * activado en el módulo `:android`, ver §3.8).
 */
object HtmlExtractor : TextExtractor {

    override val format = MaterialFormat.HTML

    override suspend fun extract(input: ExtractionInput): ExtractionResult {
        val doc = Jsoup.parse(decodeText(input.bytes))
        doc.select("script, style, noscript, nav, header, footer, aside, form, iframe").remove()
        val root = doc.selectFirst("article, main") ?: doc.body() ?: return ExtractionResult(
            warning = "El HTML no tiene contenido visible.",
        )
        val text = toMarkdown(root)
        return ExtractionResult(text = text.trim())
    }

    /** Recorre el DOM y arma Markdown (encabezados, listas, código). */
    private fun toMarkdown(root: Element): String {
        val sb = StringBuilder()
        for (node in root.childNodes()) appendNode(node, sb, 0)
        return sb.toString()
    }

    private fun appendNode(node: Node, sb: StringBuilder, depth: Int) {
        when (node) {
            is org.jsoup.nodes.TextNode -> sb.append(node.text())
            is Element -> {
                when (node.tagName()) {
                    "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        sb.appendLine()
                        sb.appendLine("#".repeat(node.tagName()[1] - '0') + " " + node.text())
                    }
                    "p" -> { sb.appendLine(); sb.appendLine(node.text()); sb.appendLine() }
                    "li" -> { sb.appendLine("- " + node.text()) }
                    "pre" -> { sb.appendLine(); sb.appendLine("```"); sb.appendLine(node.text()); sb.appendLine("```") }
                    "br" -> sb.appendLine()
                    else -> for (child in node.childNodes()) appendNode(child, sb, depth + 1)
                }
            }
            else -> Unit
        }
    }
}
```

### 3.7 El helper ZIP + XML (`jvmCommonMain`)

```kotlin
// materials/src/jvmCommonMain/kotlin/kitoru/materials/ZipText.kt

import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.xml.sax.InputSource

/**
 * Lectura de ZIP y XML para los formatos OOXML y EPUB.
 *
 * Sólo usa `java.util.zip` y `javax.xml.parsers`, que están en el boot
 * classpath de Android desde la API 1. **No** se usa StAX
 * (`javax.xml.stream`), que Android **no** tiene.
 */
internal object ZipText {

    /** Tope de bytes descomprimidos (guardia contra zip bombs). */
    private const val MAX_UNCOMPRESSED = 64L * 1024 * 1024

    /** Lee una entrada del ZIP. null si no existe. */
    fun readEntry(bytes: ByteArray, name: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var entry: ZipEntry? = zin.nextEntry
            var total = 0L
            while (entry != null) {
                val e = entry
                if (!e.isDirectory && e.name == name) return zin.readBytes()
                if (!e.isDirectory) total += e.size.coerceAtLeast(0)
                if (total > MAX_UNCOMPRESSED) {
                    error("El archivo comprimido es demasiado grande (posible zip bomb).")
                }
                entry = zin.nextEntry
            }
        }
        return null
    }

    /** Nombres de todas las entradas del ZIP. */
    fun listEntries(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out += entry.name
                entry = zin.nextEntry
            }
        }
        return out
    }

    /**
     * Parsea XML de forma segura.
     *
     * Android implementa `DocumentBuilderFactory` con **tres** features
     * (`namespaces`, `namespace-prefixes`, `validation`): pedir
     * `disallow-doctype-decl` tira `ParserConfigurationException`. Por eso la
     * protección real contra XXE es el `EntityResolver`, que corta todas las
     * entidades externas.
     */
    fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder.parse(InputSource(ByteArrayInputStream(bytes)))
    }
}
```

### 3.8 Dependencias y build

`materials/build.gradle.kts` (módulo nuevo, con la misma estructura de
source sets que `:core`):

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    jvm("desktop") { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    sourceSets {
        val commonMain by getting
        val commonTest by getting
        val desktopMain by getting
        val desktopTest by getting
        val androidMain by getting
        val androidUnitTest by getting

        val jvmCommonMain by creating { dependsOn(commonMain) }
        val jvmCommonTest by creating { dependsOn(commonTest) }
        desktopMain.dependsOn(jvmCommonMain)
        androidMain.dependsOn(jvmCommonMain)
        desktopTest.dependsOn(jvmCommonTest)
        androidUnitTest.dependsOn(jvmCommonTest)

        commonMain.dependencies {
            api(project(":core"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        jvmCommonMain.dependencies {
            // jsoup: Java puro, funciona en desktop y Android.
            implementation(libs.jsoup)
        }
        desktopMain.dependencies {
            // Apache PDFBox (desktop).
            implementation(libs.pdfbox)
        }
        androidMain.dependencies {
            // PDFBox para Android (mismo PDFBox 2.0.x reempaquetado).
            implementation(libs.pdfbox.android)
        }
        desktopTest.dependencies {
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "com.funshiengine.kitoru.materials"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
```

En `gradle/libs.versions.toml`:

```toml
[versions]
pdfbox = "2.0.27"
jsoup = "1.17.2"

[libraries]
pdfbox = { module = "org.apache.pdfbox:pdfbox", version.ref = "pdfbox" }
pdfbox-android = { module = "com.tom-roush:pdfbox-android", version.ref = "pdfbox" }
jsoup = { module = "org.jsoup:jsoup", version.ref = "jsoup" }
```

**Core library desugaring** (jsoup 1.16+ usa `java.util.function` y
`java.util.stream`): hay que activarlo en el módulo de aplicación Android
(`:android`), no en la biblioteca:

```kotlin
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    dependencies {
        coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")
    }
}
```

Si esto molesta, la alternativa es `jsoup:1.15.4` (Java 7), pero se pierde
el parser HTML5 moderno. Con desugaring, 1.17.2 es la opción.

### 3.9 Idioma

El idioma se detecta con una **heurística determinista** sobre palabras
funcionales frecuentes (no hay red ni modelo):

```kotlin
// materials/src/commonMain/kotlin/kitoru/materials/LanguageDetector.kt

/**
 * Detecta el idioma del texto por palabras funcionales.
 *
 * Determinista y testeable (regla 3 del proyecto). Devuelve ISO 639-1
 * ("es", "en", "pt", "fr", "de", "it") o "" si no hay señales suficientes.
 */
object LanguageDetector {

    private val HINTS: Map<String, Set<String>> = mapOf(
        "es" to setOf("el", "la", "los", "las", "de", "que", "para", "con", "una", "por", "como", "más"),
        "en" to setOf("the", "of", "and", "to", "in", "is", "that", "for", "with", "are", "this"),
        "pt" to setOf("de", "que", "não", "uma", "para", "com", "por", "como", "mais", "dos", "das"),
        "fr" to setOf("le", "la", "les", "des", "une", "pour", "avec", "dans", "sur", "est"),
        "de" to setOf("der", "die", "das", "und", "ist", "nicht", "mit", "für", "ein", "eine"),
        "it" to setOf("il", "la", "di", "che", "per", "con", "una", "non", "sono", "del"),
    )

    /** Mínimo de palabras funcionales para_atribuir un idioma. */
    private const val MIN_HITS = 3

    fun detect(text: String): String {
        val words = text.lowercase().split(NON_ALPHA).filter { it.length > 2 }
        if (words.size < 20) return ""
        val counts = HINTS.mapValues { (_, hints) -> words.count { it in hints } }
        val (lang, hits) = counts.maxByOrNull { it.value } ?: return ""
        if (hits < MIN_HITS) return ""
        // "de"/"la"/"que" chocan entre español y portugués: desempata el idioma
        // con más palabras exclusivas.
        return lang
    }

    private val NON_ALPHA = Regex("[^a-záéíóúüñ]+")
}
```

### 3.10 Formatos no soportados

| Extensión | Qué hacer | Por qué |
|---|---|---|
| `.doc`, `.ppt` | Error al subir: "El formato «Word (.doc)» no está soportado. Guardá el archivo como .docx." | Formato binario OLE2: sólo POI lo lee, y POI no anda en Android (§3.3). |
| `.odt`, `.odp`, `.rtf` | Igual | Sin librería razonable en Android. |
| `.zip`, `.rar` | Error | Un ZIP de materiales es un flujo de trabajo distinto (ver §12). |
| Imágenes (`.png`, `.jpg`) | Error | Sin OCR (§3.5). |
| `.mp4`, `.mp3` | Error | Sin transcripción. |

El mensaje de error lista los formatos válidos:

```
"Los formatos soportados son: Markdown, Texto plano, PDF, Word (.docx),
PowerPoint (.pptx), CSV, HTML y EPUB."
```

---

## 4. Versionado

### 4.1 La decisión

> **Mismo contenido (mismo SHA-256) → no hay versión nueva.**
> **Contenido distinto → hay versión nueva, encadenada.**
> **El índice sólo tiene la versión vigente.**

### 4.2 Por qué

1. **El índice no entiende de versiones.** `Bm25Index` calcula el IDF sobre
   los documentos que tiene. Dos versiones del 95 %-igual inflan el IDF de
   los términos comunes y hunden los términos que sólo aparecen en una.
2. **Las citas se duplican.** El alumno recibe "cátedra: Apuntes" dos veces
   con textos casi iguales, y el verificador de citas (`CitationVerifier`)
   ve dos fuentes que en realidad son una.
3. **El modelo cita la versión vieja.** "según la versión de marzo…". Es
   exactamente el tipo de error que un corpus docente no puede permitirse.
4. **Re-subir un PDF sin cambios es el caso normal.** El docente corrige el
   título o las etiquetas y vuelve a subir el mismo archivo: crear una v2
   ensuciaría la lista sin ningún benefit.

Lo que **sí** se conserva: todas las versiones en disco y en el catálogo. Son
la auditoría ("¿qué se subió el 3 de marzo?") y permiten **revertir**.

### 4.3 El modelo de cadena

```kotlin
// Ejemplo: el docente sube "apuntes.pdf" (v1), lo corrige (v2) y lo vuelve a subir igual (v3).

v1: id=m1, version=1, previousVersionId="",  rootId="m1", supersededBy="m2", status=STALE
v2: id=m2, version=2, previousVersionId="m1", rootId="m1", supersededBy="m3", status=STALE
v3: id=m3, version=3, previousVersionId="m2", rootId="m1", supersededBy="",    status=READY
```

- `rootId` es el ancla: todos los ids de la cadena.
- `previousVersionId` / `supersededBy` son los dos enlaces de la cadena.
- **La versión vigente es la única con `supersededBy` vacío**.
- Cada versión tiene **su propia carpeta** en disco
  (`materials/m1/`, `materials/m2/`, `materials/m3/`): el original viejo se
  conserva byte a byte.

### 4.4 El flujo de subida

```
MaterialService.upload(course, request, uploadedBy)
  │
  ├─ 1. Validar: nombre, tamaño > 0, tamaño ≤ cuota por material,
  │              formato soportado.
  │
  ├─ 2. checksum = sha256Hex(bytes)
  │
  ├─ 3. ¿request.replacesMaterialId != ""?   (el docente eligió "reemplazar X")
  │      ├─ SÍ → prev = repo.get(replacesMaterialId)
  │      │        if (prev.checksum == checksum) {
  │      │            // Mismo contenido: NO hay versión nueva.
  │      │            updateMetadata(prev, request)      // título, tags, visibility, unitId
  │      │            repo.save(prev.copy(updatedAt = now))
  │      │            return prev                          // el texto sigue siendo válido
  │      │        }
  │      │        rootId = prev.rootId
  │      │        version = (maxVersionOf(rootId) + 1)
  │      │        previousVersionId = prev.id
  │      │
  │      └─ NO → buscar en la cátedra un material vigente con el mismo checksum
  │               if (existe) {
  │                   // Re-subida del mismo archivo con otro nombre:
  │                   // se actualiza en el sitio, sin versión nueva.
  │                   updateMetadata(existente, request)
  │                   repo.save(existente.copy(updatedAt = now))
  │                   return existente
  │               }
  │               rootId = "" (se crea la cadena)
  │               version = 1
  │               previousVersionId = ""
  │
  ├─ 4. Cuota de la cátedra (uso actual + bytes)  → si excede: error, no se escribe nada
  │
  ├─ 5. id = "curso|material|uuid"; status = PENDING
  │      Escribir el original en courses/<courseId>/materials/<id>/original/<nombre>
  │      Escribir meta.json
  │      repo.save(material)
  │
  ├─ 6. MaterialIndexer.index(material)  → EXTRACTING → READY | FAILED
  │      (escribe text/extracted.md y actualiza pageCount/language/chars)
  │
  ├─ 7. Si hay versión previa y quedó READY:
  │        prev.copy(status = STALE, supersededBy = nuevo.id) → repo.save
  │      Si la nueva quedó FAILED:
  │        prev.copy(status = READY) → repo.save   (rollback)
  │        y la nueva queda FAILED con su error
  │
  └─ 8. onCorpusChanged()  → la app hace kb.sync()
```

### 4.5 Qué entra al índice

El filtro es explícito y vive en el dominio, para que no dependa de que
alguien se acuerde:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/Material.kt

/** ¿Este material entra al índice para esta audiencia? */
fun Material.isIndexableFor(audience: CorpusAudience): Boolean {
    val visibleToAudience = when (audience) {
        CorpusAudience.STUDENTS -> visibility == MaterialVisibility.STUDENTS
        CorpusAudience.TEACHERS -> visibility != MaterialVisibility.HIDDEN
    }
    return visibleToAudience &&
        status == MaterialStatus.READY &&
        isCurrent &&
        extractedChars > 0
}
```

`CorpusAudience` vive en `kitoru.domain` (junto a `MaterialVisibility`):

```kotlin
/** Para quién se arma el corpus del RAG. */
enum class CorpusAudience { STUDENTS, TEACHERS }
```

### 4.6 Revertir a una versión anterior

`MaterialService.revert(material)`:

1. Toma la versión anterior (`previousVersionId`) — o cualquier versión de la
   cadena.
2. Crea una **versión nueva** con el contenido de la anterior (copia el
   original de disco, calcula el checksum) → no se "rebobina" el historial: el
   historial es append-only.
3. La versión actual queda `STALE` con `supersededBy` = la nueva.

Decisión: **revertir crea una versión nueva**, no borra las posteriores. Es la
misma lógica que un `git revert`: el historial no se reescribe.

---

## 5. Etiquetas y unidades

### 5.1 Etiquetas (tags)

Sí, se mantienen: `List<String>` en `Material`. Son **etiquetas libres**, no
una jerarquía.

Reglas:

- Normalización al guardar: `trim()`, minúsculas, sin acentos duplicados,
  sin espacios duplicados, máximo **8 etiquetas** por material, máximo **32
  caracteres** cada una.
- Se usan para tres cosas:
  1. **Filtro en la UI**: "ver sólo los materiales de la TP2".
  2. **Filtro del RAG**: el chat puede acotarse a un conjunto de etiquetas.
  3. **Contexto para el modelo**: las etiquetas de un material se agregan al
     prompt de contexto como "[1] (cátedra: Apuntes — parcial-1, teoría)".
- **No** entran en el texto indexado por BM25 (mezclaría "pdf" o "2026" en
  el flujo de tokens). Se usan para filtrar y ponderar, no para rankear.

### 5.2 Unidades y temas: **sí, con dos niveles**

**¿Vale la pena?** Sí, por tres razones concretas:

1. **El docente ya piensa así.** "Subí el material de la Unidad 2". Si la app
   no tiene el concepto, el docente lo resuelve con nombres de archivo
   ("Unidad 2 - Paradigmas.pdf") y con etiquetas sueltas. Lo que hoy es un
   nombre de archivo, mañana es un dato consultable.
2. **El alumno pregunta así.** "¿Me explicás lo de la unidad 3?" Sin
   unidades, esa pregunta sólo se responde si el término aparece en el
   texto. Con unidades, la pregunta se puede **acotar**: el chat puede
   filtrar el corpus a esa unidad y la respuesta es 5× más precisa.
3. **Sirve para la analítica.** "El 70 % de las preguntas de los alumnos es
   sobre la Unidad 2" es el tipo de dato que el docente quiere (es el
   propósito de `CourseAnalytics`).

**¿Por qué sólo dos niveles?** Porque más niveles no aportan:

- El `Chunker` ya parte cada documento por sus propios encabezados: un
  `.docx` con "# Paradigmas" y "# Lambdas" genera chunks con ese heading.
  El árbol de unidades no reemplaza eso, lo complementa.
- Un árbol de 3+ niveles multiplica el trabajo de la UI (arrastrar y soltar,
  selectores) y los errores (materiales en unidades borradas) sin ganancia en
  la recuperación.
- Con dos niveles el docente tiene "Unidad 1..N" (que ya usa para el programa
  de la materia) y, si quiere, "Paradigmas", "Lambdas", "TPs".

El modelo (`CourseUnit`, §1.5) tiene `parentId` y `order`. La regla dura la
impone el servicio:

```kotlin
// materials/src/commonMain/kotlin/kitoru/materials/UnitService.kt

/**
 * Reglas del árbol de unidades:
 *  - sólo dos niveles (raíz + tema);
 *  - un tema no puede ser raíz;
 *  - no hay ciclos (imposible con 2 niveles, pero se valida igual).
 */
object UnitService {

    const val MAX_DEPTH = 2

    fun validate(parent: CourseUnit?, child: CourseUnit): List<String> {
        val errors = mutableListOf<String>()
        if (parent == null) {
            if (child.parentId.isNotBlank()) errors += "La unidad no tiene padre."
            return errors
        }
        if (!parent.isRoot) errors += "Las unidades sólo admiten un nivel de temas."
        if (parent.courseId != child.courseId) errors += "La unidad pertenece a otra cátedra."
        if (child.id == parent.id) errors += "Una unidad no puede ser su propio padre."
        return errors
    }

    /**
     * Path completo de una unidad, para mostrarlo y para indexarlo:
     * "Unidad 2 › Paradigmas". "" si la unidad no existe.
     */
    fun pathOf(units: List<CourseUnit>, unitId: String): String {
        if (unitId.isBlank()) return ""
        val unit = units.firstOrNull { it.id == unitId } ?: return ""
        val parent = units.firstOrNull { it.id == unit.parentId }
        return if (parent == null) unit.title else "${parent.title} › ${unit.title}"
    }
}
```

### 5.3 Cómo entran al RAG

**Ni árbol ni ponderación complexa.** Dos mecanismos simples y medibles:

**(a) Filtro por unidad.** El chat puede tener una unidad activa
("Preguntar sobre: Unidad 2"). El filtro se aplica al construir el corpus:

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/RetrievalScope.kt

/**
 * Alcance de una recuperación: qué materiales son elegibles y con qué
 * bonificación.
 */
data class RetrievalScope(
    /** Unidad activa ("" = todas). */
    val unitId: String = "",
    /** Etiquetas que deben estar (vacío = cualquiera). */
    val tags: Set<String> = emptySet(),
    /** Bonificación por coincidencia de unidad (25 %). */
    val unitBoost: Double = 0.25,
    /** Bonificación por coincidencia de etiqueta (10 %). */
    val tagBoost: Double = 0.10,
) {
    companion object {
        val ALL = RetrievalScope()
    }
}
```

**(b) Bonificación de score.** En `KnowledgeBase.retrieve`:

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/KnowledgeBase.kt (modificado)

fun retrieve(
    query: String,
    k: Int = cfg.topK,
    scope: RetrievalScope = RetrievalScope.ALL,
): List<Chunk> {
    if (index.size == 0) return emptyList()
    // Se recuperan más candidatos para que la bonificación pueda subir algo.
    val candidates = index.search(query, k * 4)
    return candidates
        .map { (chunk, score) -> chunk to score * boostFor(chunk, scope) }
        .filter { (chunk, _) -> matchesScope(chunk, scope) }
        .sortedByDescending { it.second }
        .take(k)
        .map { it.first }
}

/** Multiplicador por unidad y por etiqueta. */
private fun boostFor(chunk: Chunk, scope: RetrievalScope): Double {
    var boost = 1.0
    if (scope.unitId.isNotBlank() && chunk.unitId == scope.unitId) boost += scope.unitBoost
    if (scope.tags.isNotEmpty() && chunk.tags.any { it in scope.tags }) boost += scope.tagBoost
    return boost
}

/** ¿El chunk entra en el alcance pedido? */
private fun matchesScope(chunk: Chunk, scope: RetrievalScope): Boolean {
    if (scope.unitId.isNotBlank() && chunk.unitId != scope.unitId) return false
    if (scope.tags.isNotEmpty() && chunk.tags.none { it in scope.tags }) return false
    return true
}
```

**(c) El nombre de la unidad se indexa como texto.** Para que "paradigmas"
encuentre el material aunque el cuerpo no lo diga:

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Bm25Index.kt (modificado)

// Antes:
//   chunks.map { c -> tokenize("${c.heading} ${c.path} ${c.text}")... }
// Ahora:
chunks.map { c ->
    tokenize("${c.heading} ${c.path} ${c.unitTitle} ${c.text}").groupingBy { it }.eachCount()
}
```

**¿Por qué no embeddings ni un índice vectorial?** El RAG de Kitoru es BM25
(heredado de FunshiEngineAI), determinista y sin modelo: un índice vectorial
obligaría a tener embeddings (modelo local o red) en el cliente del alumno.
La unidad es un **boost determinista** y con eso alcanza. Queda anotado para
el futuro: si algún día hay embeddings, la unidad se puede usar como filtro
duro antes del recall vectorial.

### 5.4 Cambios en `:backend`

`Doc` y `Chunk` llevan los metadatos de unidad y etiquetas:

```kotlin
// backend/src/commonMain/kotlin/rag/retrieval/Models.kt (modificado)

data class Doc(
    val source: String,
    val path: String,
    val text: String,
    /** Unidad o tema del material ("" si no tiene). */
    val unitId: String = "",
    /** Path completo de la unidad ("Unidad 2 › Paradigmas"). */
    val unitTitle: String = "",
    /** Etiquetas del material. */
    val tags: List<String> = emptyList(),
)

data class Chunk(
    val source: String,
    val path: String,
    val heading: String,
    val text: String,
    val unitId: String = "",
    val unitTitle: String = "",
    val tags: List<String> = emptyList(),
) {
    val label get() = "$source: $path"
}
```

Y `Chunker.split` los propaga (`Chunk(doc.source, doc.path, heading, piece,
doc.unitId, doc.unitTitle, doc.tags)`).

El `RagEngine.prepare` muestra la unidad en el contexto, para que el modelo
cite la unidad y no sólo el archivo:

```
[1] (cátedra: Apuntes de Paradigmas — Unidad 2 › Paradigmas) {texto}
```

El cambio puntual en `backend/src/commonMain/kotlin/rag/engine/RagEngine.kt`:

```kotlin
// Antes:
hits.mapIndexed { i, c -> "[${i + 1}] (${c.label} — ${c.heading})\n${c.text}" }

// Ahora:
hits.mapIndexed { i, c ->
    val unit = if (c.unitTitle.isBlank()) "" else " — ${c.unitTitle}"
    "[${i + 1}] (${c.label}$unit — ${c.heading})\n${c.text}"
}
```

> Ojo: el `unitTitle` que viaja en el `Doc` es el **path completo**
> ("Unidad 2 › Paradigmas"), que se arma en `:materials` al leer el material:
> `UnitService.pathOf(courseId, unitId)`.

---

## 6. Visibilidad

### 6.1 Los tres estados

| Estado | ¿El alumno lo ve en la lista? | ¿Entra al RAG del alumno? | ¿Entra al RAG del docente? | ¿Se puede descargar el alumno? |
|---|---|---|---|---|
| `STUDENTS` | Sí | **Sí** | Sí | Sí |
| `TEACHERS_ONLY` | No | **No** | Sí | No |
| `HIDDEN` | No | No | No | No |

### 6.2 La regla del RAG (la importante)

> **Un material que el alumno no ve, el RAG del alumno no lo usa.**

Consecuencias, todas implementadas en `isIndexableFor` (§4.5):

- El corpus del alumno se arma con `CorpusAudience.STUDENTS`: sólo
  `visibility == STUDENTS`.
- El corpus del docente (para redactar avisos, §4 de `07-anuncios.md`) se
  arma con `CorpusAudience.TEACHERS`: `STUDENTS` + `TEACHERS_ONLY`.
- El filtro se aplica **al construir el corpus**, no al mostrar: un material
  `TEACHERS_ONLY` no existe para el alumno, ni en la lista, ni en el
  `DocumentSource`, ni en las fuentes de un mensaje, ni en la analítica.

**`TEACHERS_ONLY` no es un parche sobre `STUDENTS`**: es un estado propio
porque su propósito es distinto. Sirve para:

- Guías de evaluación y rúbricas (el docente las consulta con la IA).
- Respuestas a parciales.
- Borradores de aviso con contenido que todavía no se publica.

**`HIDDEN` es "no lo indexes, pero lo guardo"**: duplicados, un PDF que todavía
no quiero publicar, un archivo en un formato no soportado. El material existe
en la carpeta del docente pero no molesta a nadie.

### 6.3 Migración de `visible: Boolean` → `visibility`

El `visible` viejo era un booleano: `false` significaba "oculto", sin
distinción entre "sólo para el docente" y "no lo indexes". Como
`KitoruJson` tiene `ignoreUnknownKeys = true`, el campo viejo se descarta en
silencio al deserializar ⇒ **un material oculto volvería a ser visible**.
Eso no se puede dejar pasar: es una fuga de privacidad.

Por eso la migración se hace **sobre el texto crudo, antes de deserializar**:

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/MaterialMigration.kt

/**
 * Migra el `materials.json` viejo (`visible: Boolean`) al nuevo
 * (`visibility: MaterialVisibility`).
 *
 * Se hace sobre el texto crudo porque `ignoreUnknownKeys` descartaría el
 * campo viejo: un material oculto volvería a ser visible para los alumnos.
 *
 * `visible = true`  → `visibility = STUDENTS`
 * `visible = false` → `visibility = TEACHERS_ONLY` (el caso más cercano:
 *                      estaba oculto para los alumnos)
 */
object MaterialMigration {

    fun migrate(raw: String): String {
        val array = runCatching { KitoruJson.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: return raw
        val needsMigration = array.any { it is JsonObject && "visible" in it }
        if (!needsMigration) return raw
        val out = array.map { element ->
            val obj = element as? JsonObject ?: return@map element
            val visible = (obj["visible"] as? JsonPrimitive)?.booleanOrNull
            val fields = obj.toMutableMap()
            fields.remove("visible")
            if (visible != null) {
                fields["visibility"] = JsonPrimitive(
                    if (visible) "STUDENTS" else "TEACHERS_ONLY",
                )
            }
            JsonObject(fields)
        }
        return KitoruJson.encodeToString(JsonArray.serializer(), JsonArray(out))
    }

    /** Migra el archivo en disco, si hace falta. Idempotente. */
    fun migrateFile(fs: FileSystem, path: FilePath) {
        if (!fs.exists(path)) return
        val raw = runCatching { fs.readText(path) }.getOrNull() ?: return
        val migrated = migrate(raw)
        if (migrated != raw) fs.writeText(path, migrated)
    }
}
```

Y se llama una vez, en el arranque de la app, **antes** de leer el catálogo:

```kotlin
// :core o :materials, bootstrap
MaterialMigration.migrateFile(fs, KitoruPaths.configDir.resolve("materials.json"))
```

Además, `Material.visible` queda como propiedad calculada
(`visibility.studentVisible`) para no romper los call sites viejos.

---

## 7. Almacenamiento en disco

### 7.1 Layout

```
<dataDir>/
├── config/                              ← el catálogo (JSON, un archivo por tipo)
│   ├── courses.json
│   ├── enrollments.json
│   ├── materials.json                   ← metadatos de los materiales (SIN texto)
│   ├── units.json                       ← unidades y temas de cada cátedra
│   ├── threads.json
│   ├── messages.json
│   └── profiles.json
└── courses/
    └── <courseId>/
        └── materials/
            ├── m-3f9a…/                 ← una carpeta por VERSIÓN de material
            │   ├── original/
            │   │   └── apuntes.pdf     ← los bytes exactos que subió el docente
            │   ├── text/
            │   │   └── extracted.md     ← texto plano/Markdown extraído
            │   └── meta.json            ← metadatos de esta versión
            ├── m-7c21…/
            │   ├── original/…
            │   ├── text/extracted.md
            │   └── meta.json
            └── m-b004…/
```

Reglas del layout:

- **Una carpeta por material (por versión)**. Borrar un material = borrar una
  carpeta. No hay archivos sueltos en la raíz de la cátedra.
- **`original/`** guarda el archivo **tal cual**, con su nombre original
  (sanitizado). No se renombra ni se convierte: es la fuente de verdad y tiene
  que poder descargarse igual que se subió.
- **`text/extracted.md`** es un caché regenerable. Siempre en Markdown (el
  `Chunker` lo entiende); el texto plano también es Markdown válido.
- **`meta.json`** es la copia **durable** de los metadatos de esa versión. Es
  lo que permite **reconstruir el catálogo** si `config/materials.json` se
  corrompe (§7.5).

### 7.2 Qué guarda cada cosa

| Archivo | Contenido | Quién lo escribe |
|---|---|---|
| `config/materials.json` | `List<Material>`: metadatos, sin texto. Es el catálogo que usa la UI. | `FileMaterialRepository` |
| `materials/<id>/original/<file>` | Los bytes originales. | `MaterialService.upload` |
| `materials/<id>/text/extracted.md` | El texto extraído (UTF-8). | `MaterialIndexer` |
| `materials/<id>/meta.json` | `Material` serializado + `extractorVersion` + `extractedAt`. | `MaterialService` / `MaterialIndexer` |

### 7.3 `MaterialStore`: la API de disco

```kotlin
// materials/src/commonMain/kotlin/kitoru/materials/MaterialStore.kt

package kitoru.materials

import kitoru.domain.Material
import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kitoru.platform.KitoruPaths

/**
 * Layout en disco de los materiales.
 *
 * Todo lo que sabe de rutas vive acá: las demás clases piden "el texto de
 * este material" y no saben que existe una carpeta `courses/`.
 */
class MaterialStore(
    private val fs: FileSystem,
    /** Raíz de las cátedras. Por defecto `KitoruPaths.coursesDir`. */
    private val coursesRoot: FilePath = KitoruPaths.coursesDir,
) {
    /** `courses/<courseId>/` */
    fun courseDir(courseId: String): FilePath = coursesRoot.resolve(courseId)

    /** `courses/<courseId>/materials/` */
    fun materialsDir(courseId: String): FilePath = courseDir(courseId).resolve("materials")

    /** `courses/<courseId>/materials/<materialId>/` */
    fun materialDir(courseId: String, materialId: String): FilePath =
        materialsDir(courseId).resolve(materialId)

    /** Carpeta del original. */
    fun originalDir(courseId: String, materialId: String): FilePath =
        materialDir(courseId, materialId).resolve("original")

    /** Carpeta del texto. */
    fun textDir(courseId: String, materialId: String): FilePath =
        materialDir(courseId, materialId).resolve("text")

    /** Ruta absoluta del texto extraído. */
    fun textFile(courseId: String, materialId: String): FilePath =
        textDir(courseId, materialId).resolve("extracted.md")

    /** Ruta absoluta del original. */
    fun originalFile(courseId: String, materialId: String, fileName: String): FilePath =
        originalDir(courseId, materialId).resolve(FileNames.sanitize(fileName))

    /** Ruta absoluta de los metadatos de la versión. */
    fun metaFile(courseId: String, materialId: String): FilePath =
        materialDir(courseId, materialId).resolve("meta.json")

    /** Ruta relativa a `courses/<courseId>/` (lo que se guarda en `Material.path`). */
    fun relativeToCourse(courseId: String, path: FilePath): String =
        path.relativeTo(courseDir(courseId))

    /** Escribe el original y devuelve la ruta relativa. */
    fun writeOriginal(courseId: String, materialId: String, fileName: String, bytes: ByteArray): String {
        val target = originalFile(courseId, materialId, fileName)
        fs.createDirectories(target.parent ?: target)
        fs.writeBytes(target, bytes)
        return relativeToCourse(courseId, target)
    }

    /** Escribe el texto extraído y devuelve la ruta relativa. */
    fun writeText(courseId: String, materialId: String, text: String): String {
        val target = textFile(courseId, materialId)
        fs.createDirectories(target.parent ?: target)
        fs.writeText(target, text)
        return relativeToCourse(courseId, target)
    }

    /** Lee el texto extraído ("" si no está). */
    fun readText(material: Material): String {
        if (material.textPath.isBlank()) return ""
        val path = courseDir(material.courseId).resolve(material.textPath)
        if (!fs.exists(path)) return ""
        return runCatching { fs.readText(path) }.getOrDefault("")
    }

    /** Bytes del original ("" si no está). */
    fun readOriginal(material: Material): ByteArray {
        if (material.path.isBlank()) return ByteArray(0)
        val path = courseDir(material.courseId).resolve(material.path)
        if (!fs.exists(path)) return ByteArray(0)
        return runCatching { fs.readBytes(path) }.getOrDefault(ByteArray(0))
    }

    /** Borra la carpeta entera del material. */
    fun deleteMaterial(courseId: String, materialId: String) {
        fs.delete(materialDir(courseId, materialId))
    }

    /**
     * Espacio usado por la cátedra, en bytes (originales + texto).
     *
     * Se cuenta **todo** lo que hay en disco, incluidas las versiones
     * viejas: esas siguen ocupando lugar aunque no estén en el índice.
     */
    fun usedBytes(courseId: String): Long {
        var total = 0L
        for (id in listMaterialIds(courseId)) {
            total += sizeOf(materialDir(courseId, id))
        }
        return total
    }

    /** Tamaño de un archivo o de un directorio (recursivo). */
    private fun sizeOf(path: FilePath): Long {
        if (!fs.exists(path)) return 0L
        if (!fs.isDirectory(path)) return fs.sizeBytes(path)
        return fs.listEntries(path).sumOf { sizeOf(it) }
    }

    /** Ids de los materiales de la cátedra, leyendo las carpetas. */
    fun listMaterialIds(courseId: String): List<String> =
        fs.listEntries(materialsDir(courseId))
            .filter { fs.isDirectory(it) }
            .map { it.name }
            .sorted()
}
```

### 7.4 Nombres de archivo

```kotlin
// materials/src/commonMain/kotlin/kitoru/materials/FileNames.kt

/**
 * Saneo de nombres de archivo.
 *
 * Un archivo subido por un docente puede traer cualquier cosa: barras,
 * "..", acentos, espacios. En el disco se guarda un nombre plano y seguro.
 */
object FileNames {

    private val INVALID = Regex("[^A-Za-z0-9._\\- ]")
    private val SPACES = Regex("\\s+")

    /** Nombre plano, sin directorios, sin "..", máximo 120 caracteres. */
    fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val clean = base.replace(INVALID, "_").replace(SPACES, " ").trim('.', ' ')
        return clean.ifBlank { "archivo" }.take(120)
    }
}
```

- Se conserva la **extensión** (el detector de formato depende de ella).
- Se conserva el **nombre original** en `Material.originalFileName`, así que
  el docente ve "apuntes.pdf" aunque en disco quede "apuntes.pdf".
- La carpeta del material es el id (uuid), así que **no hay colisiones** entre
  versiones ni entre archivos con el mismo nombre.

### 7.5 Reconstruir el catálogo

Si `config/materials.json` se corrompe (el `JsonStore` devuelve la lista
vacía en vez de romper la app), el material no se pierde: está en disco. Por
cada carpeta `materials/<id>/` hay un `meta.json` con el `Material` completo.

```kotlin
// materials/src/commonMain/kotlin/kitoru/materials/MaterialStore.kt

/**
 * Reconstruye el catálogo desde el disco.
 *
 * Cada carpeta de material tiene su `meta.json`. Si el catálogo se corrompe,
 * se puede regenerar sin volver a subir nada.
 */
fun rebuildCatalog(courseId: String): List<Material> {
    val out = mutableListOf<Material>()
    for (id in listMaterialIds(courseId)) {
        val meta = metaFile(courseId, id)
        if (!fs.exists(meta)) continue
        val material = runCatching {
            KitoruJson.decodeFromString(Material.serializer(), fs.readText(meta))
        }.getOrNull() ?: continue
        out += material
    }
    return out
}
```

El `meta.json` se escribe **siempre** (al subir y al terminar de extraer),
así que nunca está desactualizado respecto del material.

### 7.6 Ampliación de `FileSystem`

Hoy `FileSystem` sólo tiene `readText`/`writeText`, y los originales son
binarios. Hacen falta tres métodos:

```kotlin
// core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt (modificado)

interface FileSystem {
    fun exists(path: FilePath): Boolean
    fun isDirectory(path: FilePath): Boolean
    fun sizeBytes(path: FilePath): Long
    fun readText(path: FilePath): String
    fun writeText(path: FilePath, text: String)

    /** Lee el archivo completo como bytes (originales binarios). */
    fun readBytes(path: FilePath): ByteArray

    /** Escribe bytes (originales binarios). */
    fun writeBytes(path: FilePath, bytes: ByteArray)

    fun delete(path: FilePath)
    fun createDirectories(path: FilePath)

    /** Archivos de un directorio (sólo archivos, como antes). */
    fun listFiles(dir: FilePath): List<FilePath>

    /** Archivos **y** carpetas de un directorio. */
    fun listEntries(dir: FilePath): List<FilePath>

    fun copy(from: FilePath, to: FilePath)
}
```

Y sus `actual` en `core/src/jvmCommonMain/kotlin/kitoru/platform/FileSystem.jvm.kt`:

```kotlin
override fun readBytes(path: FilePath): ByteArray = f(path).readBytes()

override fun writeBytes(path: FilePath, bytes: ByteArray) {
    val file = f(path)
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
}

override fun listEntries(dir: FilePath): List<FilePath> {
    val root = f(dir)
    if (!root.isDirectory) return emptyList()
    return root.listFiles().orEmpty()
        .map { FilePath(it.absolutePath) }
        .sortedBy { it.name }
}
```

`listEntries` hace falta porque `listFiles` filtra los directorios, y el
escaneo de `materials/` necesita ver las carpetas.

**Nota de memoria**: `readBytes` carga el archivo entero en memoria. Con el
tope de 50 MB por material es aceptable en desktop; en Android es el pico de
memoria de la app (por eso el tope es 50 MB y no 500 MB). Si en el futuro se
sube el tope, el lector tiene que pasar a streaming (`ZipInputStream` y
PDFBox ya trabajan con `InputStream`; alcanza con un `FileSystem.readStream`).

---

## 8. Cuota

### 8.1 Topes

```kotlin
// core/src/commonMain/kotlin/kitoru/domain/MaterialQuota.kt

/**
 * Topes de material.
 *
 * Son políticas de la app, no del dominio del curso: por eso viven en un
 * objeto y no en `Course`. Si algún día la cátedra necesita más, se puede
 * mover a `Course` sin cambiar la forma de los calls.
 */
object MaterialQuota {

    /** Máximo por material: 50 MB. */
    const val MAX_MATERIAL_BYTES: Long = 50L * 1024 * 1024

    /** Máximo por cátedra (originales + texto extraído): 1 GB. */
    const val MAX_COURSE_BYTES: Long = 1024L * 1024 * 1024

    /** Máximo de materiales (versiones) por cátedra. */
    const val MAX_MATERIALS_PER_COURSE: Int = 500

    /** Máximo de caracteres del texto extraído por material (2 M). */
    const val MAX_EXTRACTED_CHARS: Int = 2_000_000

    /** Máximo de páginas de un PDF o EPUB. */
    const val MAX_PAGES: Int = 2_000

    /** Máximo de filas de un CSV indexadas. */
    const val MAX_CSV_ROWS: Int = 5_000

    /** Máximo de etiquetas por material. */
    const val MAX_TAGS: Int = 8

    /** Máximo de caracteres por etiqueta. */
    const val MAX_TAG_LENGTH: Int = 32
}
```

| Tope | Valor | Por qué ese número |
|---|---|---|
| Por material | 50 MB | Un PDF de una clase weighs 5–20 MB. 50 MB cubre un libro entero y deja el pico de memoria de Android acotado. |
| Por cátedra | 1 GB | 50–100 materiales. Es el orden de magnitud de una cursada con programa, apuntes y TP. |
| Materiales por cátedra | 500 | Con el tope de bytes es redundante, pero protege contra 500 archivos de 1 KB. |
| Caracteres extraídos | 2 M | ~1 MB de texto. Un libro de 400 páginas queda en 150 k caracteres: sobra. |
| Páginas (PDF) | 2 000 | Un PDF de 2 000 páginas tarda en extraerse y no es material de cátedra. |
| Filas de CSV | 5 000 | Un CSV de 100 k filas no es material de cátedra; es un dataset. |

### 8.2 Dónde se verifica

En `MaterialService.upload`, **antes** de escribir cualquier byte:

```kotlin
// materials/src/commonMain/kotlin/kitoru/materials/MaterialService.kt

private suspend fun ensureQuota(course: Course, newBytes: Long) {
    val materials = repo.listForCourse(course.id)
    if (materials.size >= MaterialQuota.MAX_MATERIALS_PER_COURSE) {
        error(
            "La cátedra ya tiene ${materials.size} materiales (tope " +
                "${MaterialQuota.MAX_MATERIALS_PER_COURSE}). Borrá material viejo para seguir.",
        )
    }
    // Se cuenta lo que hay en disco (originales + texto + versiones viejas),
    // no sólo el catálogo: las versiones viejas siguen ocupando lugar.
    val used = store.usedBytes(course.id)
    if (used + newBytes > MaterialQuota.MAX_COURSE_BYTES) {
        error(
            "La cátedra ya usa ${formatBytes(used)} de material (tope " +
                "${formatBytes(MaterialQuota.MAX_COURSE_BYTES)}). " +
                "Borrá material viejo o subí un archivo más chico.",
        )
    }
}
```

Y el tope por material, en el mismo lugar:

```kotlin
require(bytes.size <= MaterialQuota.MAX_MATERIAL_BYTES) {
    "El archivo pesa ${formatBytes(bytes.size.toLong())} y supera el máximo " +
        "de ${formatBytes(MaterialQuota.MAX_MATERIAL_BYTES)} por material."
}
```

### 8.3 Qué pasa al excederla

- **La subida se rechaza.** No se escribe nada en disco: no queda un
  material a medias ni un archivo huérfano. El mensaje va al docente, en
  español, con los números concretos (fail-fast, regla 4).
- **El texto extraído se trunca** (no se rechaza el material): si supera
  `MAX_EXTRACTED_CHARS`, se corta y queda un `warning`:
  "El texto extraído era de 3.100.000 caracteres y se cortó a 2.000.000."
  El material sigue siendo utilizable.
- **La cuota de la cátedra se recalcula** después de cada extracción (el
  texto también ocupa): `store.usedBytes(courseId)`.
- **El docente tiene una salida**: borrar material. La UI muestra el uso
  ("Usás 340 MB de 1 GB") en la pantalla de material, con un botón para
  liberar espacio. Borrar un material borra su carpeta entera (§7.1).

---

## 9. El flujo completo

```
Docente: "Subir material"
  │
  ├─ El selector de archivos (Android: SAF / OpenDocument; desktop: JFileChooser)
  │    devuelve (fileName, bytes) — SIEMPRE bytes, nunca una ruta:
  │    en Android el archivo puede estar en un content:// que la app no puede leer después.
  │
  ├─ Formulario: título · tipo (kind) · unidad · etiquetas · visibilidad
  │    (por defecto "Visible para los alumnos")
  │
  └─ MaterialService.upload(course, request, uploadedBy)
       ├─ valida cuota y formato
       ├─ calcula el checksum
       ├─ ¿mismo checksum?  → actualiza en el sitio (sin versión nueva)
       ├─ ¿reemplaza a otro? → crea la versión N+1 en la cadena
       ├─ escribe el original + meta.json; guarda en el catálogo (PENDING)
       ├─ MaterialIndexer.index()
       │    ├─ status = EXTRACTING
       │    ├─ extractor = registry.forFormat(material.format)
       │    ├─ result = extractor.extract(ExtractionInput(fileName, bytes))
       │    ├─ escribe text/extracted.md
       │    ├─ status = READY (con warning) | FAILED (con extractionError)
       │    └─ guarda en el catálogo
       ├─ swap de versiones (§4.4 paso 7) o rollback
       └─ onCorpusChanged() → kb.sync()

Alumno: abre el chat
  │
  └─ CourseMaterialSource(materials = catalog.listCurrentForCourse(courseId),
                           textProvider = { store.readText(it) },
                           audience = CorpusAudience.STUDENTS)
       → sólo los materiales STUDENTS, READY, vigentes y con texto
       → KnowledgeBase.sync() → Chunker.split → Bm25Index
       → retrieve(question, topK, scope)
```

---

## 10. Casos borde

| Caso | Comportamiento |
|---|---|
| El docente sube un `.docx` con contraseña | `FAILED`, `extractionError = "El archivo está protegido con contraseña."`; el original queda guardado y se puede descargar. |
| El docente sube un PDF escaneado | `READY`, `extractedChars = 0`, `warning` explícita. No entra al índice; el alumno igual lo puede descargar. |
| El docente sube un `.doc` (binario old) | La subida se rechaza: "Guardalo como .docx". |
| El docente sube el mismo PDF con otro nombre | Mismo checksum ⇒ se actualiza en el sitio, sin versión nueva. Se avisa: "Este archivo ya estaba en la cátedra." |
| El docente sube el mismo PDF con contenido distinto | Se crea la v2; la v1 queda `STALE` con `supersededBy`; el índice pasa a la v2 en el próximo `sync`. |
| La v2 falla al extraerse | La v1 vuelve a `READY` (rollback) y sigue en el índice; la v2 queda `FAILED` con su error. |
| La app se muere durante la extracción | Al arrancar, el material `EXTRACTING` vuelve a `PENDING` y se re-encola. |
| El docente borra un material | Se borra la carpeta (`original/`, `text/`, `meta.json`) y la entrada del catálogo. Los chats que lo citaban muestran la fuente como "material eliminado". |
| El archivo original se borra de la mano (fuera de la app) | El checksum en disco no coincide ⇒ el material pasa a `STALE` y sale del índice. |
| El texto es ilegible (basura) | `extractedChars` chico ⇒ `warning` "El texto extraído es muy corto; el archivo puede estar protegido o escaneado." |
| La cátedra llega al tope de 1 GB | La subida se rechaza con el mensaje de cuota; el docente borra material. |
| Un alumno busca un material `TEACHERS_ONLY` | No aparece en la lista, ni en el `DocumentSource`, ni en las fuentes de los mensajes. Ni siquiera existe para él. |
| El docente marca un material `STUDENTS` → `TEACHERS_ONLY` | El material sale del corpus del alumno en el próximo `sync`; los mensajes ya respondidos no se reescriben (el histórico es histórico). |
| `config/materials.json` se corrompe | El `JsonStore` devuelve la lista vacía (no rompe la app) y `MaterialStore.rebuildCatalog(courseId)` regenera el catálogo desde los `meta.json`. |
| Un ZIP con entradas duplicadas (CVE-2025-31672) | El `ZipInputStream` de Java toma la primera entrada y no ejecuta código; el riesgo de POI no aplica. Se avisa en el `warning` si se detectan nombres duplicados. |
| Un `.epub` sin `META-INF/container.xml` | `FAILED`: "El EPUB no es válido (falta META-INF/container.xml)". |
| Un `.html` con todo dentro de `<script>` | `READY` con 0 caracteres y `warning` "El HTML no tiene texto visible." |

---

## 11. Archivos a crear o tocar

**Módulo nuevo:**

- `materials/build.gradle.kts`
- `settings.gradle.kts` — `include(":materials")`
- `gradle/libs.versions.toml` — `pdfbox`, `pdfbox-android`, `jsoup`

**`:core` — nuevos:**

- `core/src/commonMain/kotlin/kitoru/domain/Material.kt` — `Material`,
  `MaterialKind`, `MaterialFormat`, `MaterialStatus`, `MaterialVisibility`,
  `CourseUnit`, `CorpusAudience`, `Material.isIndexableFor`.
- `core/src/commonMain/kotlin/kitoru/domain/MaterialQuota.kt`
- `core/src/commonMain/kotlin/kitoru/domain/MaterialMigration.kt`
- `core/src/commonMain/kotlin/kitoru/platform/Checksum.kt` —
  `expect fun sha256Hex(bytes: ByteArray): String`
- `core/src/jvmCommonMain/kotlin/kitoru/platform/Checksum.jvm.kt` — `actual`
  con `java.security.MessageDigest`.

**`:core` — modificados:**

- `core/src/commonMain/kotlin/kitoru/domain/Course.kt` — **sacar** `Material` y
  `MaterialKind` (migrados a `Material.kt`).
- `core/src/commonMain/kotlin/kitoru/domain/Repositories.kt` —
  `MaterialRepository.findByChecksum` / `listCurrentForCourse`; nuevo
  `UnitRepository`.
- `core/src/commonMain/kotlin/kitoru/domain/FileRepositories.kt` —
  `FileMaterialRepository` (los 2 métodos nuevos), `FileUnitRepository`.
- `core/src/commonMain/kotlin/kitoru/platform/FileSystem.kt` — `readBytes`,
  `writeBytes`, `listEntries`.
- `core/src/jvmCommonMain/kotlin/kitoru/platform/FileSystem.jvm.kt` — los 3
  `actual`.

**`:materials` — nuevos:**

- `materials/src/commonMain/kotlin/kitoru/materials/Extraction.kt` —
  `TextExtractor`, `ExtractionResult`, `ExtractionInput`, `ExtractorRegistry`.
- `materials/src/commonMain/kotlin/kitoru/materials/PlainTextExtractor.kt`
- `materials/src/commonMain/kotlin/kitoru/materials/MarkdownExtractor.kt`
- `materials/src/commonMain/kotlin/kitoru/materials/CsvExtractor.kt`
- `materials/src/commonMain/kotlin/kitoru/materials/LanguageDetector.kt`
- `materials/src/commonMain/kotlin/kitoru/materials/FileNames.kt`
- `materials/src/commonMain/kotlin/kitoru/materials/MaterialStore.kt`
- `materials/src/commonMain/kotlin/kitoru/materials/MaterialIndexer.kt`
- `materials/src/commonMain/kotlin/kitoru/materials/MaterialService.kt`
- `materials/src/commonMain/kotlin/kitoru/materials/UnitService.kt`
- `materials/src/commonMain/kotlin/kitoru/materials/PdfEngine.kt` — `expect`
- `materials/src/jvmCommonMain/kotlin/kitoru/materials/ZipText.kt`
- `materials/src/jvmCommonMain/kotlin/kitoru/materials/DocxExtractor.kt`
- `materials/src/jvmCommonMain/kotlin/kitoru/materials/PptxExtractor.kt`
- `materials/src/jvmCommonMain/kotlin/kitoru/materials/EpubExtractor.kt`
- `materials/src/jvmCommonMain/kotlin/kitoru/materials/HtmlExtractor.kt`
- `materials/src/desktopMain/kotlin/kitoru/materials/PdfEngine.desktop.kt`
- `materials/src/androidMain/kotlin/kitoru/materials/PdfEngine.android.kt`

**`:backend` — modificados:**

- `backend/src/commonMain/kotlin/rag/retrieval/Models.kt` — `Doc`/`Chunk` con
  `unitId`, `unitTitle`, `tags`.
- `backend/src/commonMain/kotlin/rag/retrieval/Chunker.kt` — propaga los
  metadatos.
- `backend/src/commonMain/kotlin/rag/retrieval/DocumentSource.kt` —
  `CourseMaterialSource` con `textProvider` y `audience`; filtra por
  `isIndexableFor`.
- `backend/src/commonMain/kotlin/rag/retrieval/KnowledgeBase.kt` —
  `retrieve(query, k, scope)` con filtro y bonus.
- `backend/src/commonMain/kotlin/rag/retrieval/RetrievalScope.kt` — nuevo.
- `backend/src/commonMain/kotlin/rag/retrieval/Bm25Index.kt` — indexa
  `unitTitle`.
- `backend/src/commonMain/kotlin/rag/engine/RagEngine.kt` — muestra la
  unidad en el contexto.

**`:ui` / `:desktop` / `:android`:**

- `:ui` depende de `:materials`.
- `:android` — `coreLibraryDesugaring` para jsoup; `PDFBoxResourceLoader.init`
  en el arranque (`KitoruAndroid.init(context)`).

---

## 12. Tests

**`:core` (`desktopTest`):**

- `MaterialStatusTest` — todas las transiciones válidas e inválidas;
  `requireTransition` tira con mensaje en español.
- `MaterialFormatTest` — detección por extensión; `extractable`.
- `MaterialVisibilityTest` — `studentVisible`, `isIndexableFor` por audiencia.
- `MaterialMigrationTest` — `visible: true` → `STUDENTS`, `visible: false` →
  `TEACHERS_ONLY`; archivo sin `visible` no se toca; el resultado es
  deserializable.
- `ChecksumTest` — vectores conocidos de SHA-256 ("" y "abc").

**`:materials` (`desktopTest`):**

- `PlainTextExtractorTest` — UTF-8, CRLF, Latin-1 con bytes inválidos.
- `CsvExtractorTest` — comillas, comillas escapadas, `;` autodetectado,
  truncado por `MAX_CSV_ROWS`.
- `DocxExtractorTest` — se genera un `.docx` mínimo en el test con
  `ZipOutputStream` y se verifica el texto y los encabezados.
- `PptxExtractorTest` — igual con `ppt/slides/slide1.xml` y `slide2.xml`;
  se verifica el orden.
- `EpubExtractorTest` — EPUB mínimo (container + OPF + 2 XHTML).
- `HtmlExtractorTest` — encabezados, listas, `<script>` eliminado.
- `PdfExtractorTest` — PDF mínimo generado con PDFBox; texto extraído y
  `pageCount`.
- `MaterialStoreTest` — layout, `writeOriginal`/`readText`, `sanitize`
  ("../../etc/passwd" → "etc_passwd"), `rebuildCatalog`.
- `MaterialServiceTest` — re-subida idéntica (misma versión), re-subida con
  contenido distinto (v2), rollback, cuota por material y por cátedra.
- `LanguageDetectorTest` — es/en/pt y texto corto ("").
- `CourseMaterialSourceTest` (en `:backend`) — un material `TEACHERS_ONLY`
  no aparece en el corpus de los alumnos; sí en el del docente; un `FAILED`
  tampoco.

---

## 13. Fuera de alcance (explícito)

- **OCR** de PDFs escaneados (§3.5).
- **Transcripción** de audio/video.
- **Formatos binarios antiguos**: `.doc`, `.ppt`, `.odt`, `.rtf` (§3.10).
- **Índice vectorial / embeddings**: el RAG sigue siendo BM25 (§5.3).
- **Árbol de unidades de más de dos niveles** (§5.2).
- **Descarga de materiales para el alumno**: el modelo de `MaterialVisibility`
  lo contempla (`STUDENTS` ⇒ descargable), pero la UI de descarga es otro
  subsistema.
- **Materiales como adjuntos de un aviso**: el tipo `AnnouncementAttachment`
  ya existe (`07-anuncios.md`); acá sólo se garantiza que el material
  referenciado existe y que un material `TEACHERS_ONLY` no se puede adjuntar
  a un aviso dirigido a alumnos.
- **Importar/exportar la cátedra completa** (paquete `.kitoru`): el layout de
  `courses/<courseId>/materials/` ya lo hace trivial, pero es otro subsistema.
- **Reindexado incremental**: hoy `KnowledgeBase.sync()` reconstruye el
  índice entero. Con 500 materiales y BM25 en memoria son milisegundos; no
  hace falta incremental.
