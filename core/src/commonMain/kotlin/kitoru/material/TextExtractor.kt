package kitoru.material

/**
 * Contrato de extracción de texto de los materiales de una cátedra.
 *
 * ## Por qué una interfaz y no una función con un `when`
 *
 * Cada formato tiene sus quirks (el ZIP del Word, el XML del Excel, la
 * geometría del PDF) y cada plataforma tiene sus límites (PDFBox no anda en
 * Android tal cual). Con una interfaz, **agregar un formato es agregar un
 * objeto**: no hay un `when (extension)` central que alguien tenga que
 * editar (y que nadie edita, y que se rompe).
 *
 * ## Por qué el texto sale "Markdown-ish"
 *
 * "Indexable" significa: texto plano con la **estructura de encabezados en
 * Markdown**, porque es lo que el `Chunker` de `:backend` sabe cortar
 * (`Chunker.split` corta por las líneas que empiezan con `#`). Por eso los
 * extractores no devuelven un `String` pelado: devuelven encabezados (`#`,
 * `##`), tablas Markdown y marcas de página (`## Página 7`).
 */
interface TextExtractor {

    /** Id del extractor, para la UI y la auditoría ("pdfbox", "docx-zip"…). */
    val id: String

    /** Extensiones que maneja, en minúsculas y **sin punto**. */
    val extensions: Set<String>

    /**
     * MIME types que maneja (respaldo cuando la extensión no sirve).
     *
     * Las interfaces no pueden tener inicializadores de propiedades (no hay
     * campo que las guarde), así que el valor por defecto va en el getter.
     */
    val mimes: Set<String> get() = emptySet()

    /** ¿Este extractor maneja este archivo? */
    fun supports(fileName: String, mime: String): Boolean =
        extensionOf(fileName) in extensions || mimeOf(mime) in mimes

    /**
     * Extrae el texto.
     *
     * @param bytes el contenido **completo** del archivo.
     * @param fileName el nombre (con extensión) del archivo: es lo que decide
     *   el despacho y de dónde sale el título si el archivo no trae metadatos.
     * @param mime el MIME declarado por el sistema (puede ser inútil: en
     *   Windows y en Android suele ser `application/octet-stream`).
     * @throws MaterialExtractionException con mensaje en español.
     */
    suspend fun extract(bytes: ByteArray, fileName: String, mime: String): ExtractionResult
}

/**
 * Resultado de extraer texto de un material.
 *
 * Un aviso (`warning`) **no bloquea** la indexación: el docente puede subir un
 * PDF con 3 imágenes y el texto que se puede leer se indexa igual. Lo que sí
 * bloquea es [needsOcr]: sin capa de texto no hay nada que indexar y conviene
 * que el modelo diga "no lo sé" en vez de inventar.
 */
data class ExtractionResult(
    /** Texto indexable (Markdown-ish). Puede estar vacío si el archivo no tenía texto. */
    val text: String = "",

    /** Título del documento según sus propios metadatos. Vacío si no lo tiene. */
    val title: String = "",

    /**
     * "Unidades" del documento: páginas de PDF, diapositivas de PPTX, hojas de
     * XLSX. 0 si el formato no tiene (un `.md` no tiene páginas).
     */
    val pages: Int = 0,

    /** Aviso para el docente, en español. Vacío = todo bien. */
    val warning: String = "",

    /** Idioma detectado: `"es"`, `"en"` o `""` si no se pudo determinar. */
    val language: String = "",

    /** El documento no tiene capa de texto: necesita OCR (hoy no hay OCR). */
    val needsOcr: Boolean = false,

    /** Id del extractor que produjo el texto ("pdfbox", "docx-zip"…). */
    val extractor: String = "",

    /** El texto se cortó por el tope de caracteres. */
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

/**
 * Por qué falló una extracción.
 *
 * El código lo usa la UI para decidir la acción (reintentar, pedir otro
 * archivo, editar a mano) y el mensaje en español es lo que ve el docente.
 */
enum class ExtractionErrorCode {
    /** El archivo está vacío (0 bytes). */
    EMPTY_FILE,

    /** Supera el máximo por archivo. */
    TOO_LARGE,

    /** Se pasó el plazo de extracción. */
    TIMEOUT,

    /** ZIP/XML/PDF dañado. */
    CORRUPT,

    /** PDF (o `.docx`) protegido con contraseña. */
    ENCRYPTED,

    /** Formato sin soporte de extracción. */
    UNSUPPORTED,

    /** Sin capa de texto: requiere OCR. */
    NO_TEXT_LAYER,

    /** Error de E/S. */
    IO,

    /** Cualquier otra cosa. */
    UNKNOWN,
}

/** Error de extracción. El mensaje ya está en español y es lo que se muestra. */
class MaterialExtractionException(
    val code: ExtractionErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Registro de extractores y despacho.
 *
 * El registro es una **lista plana** indexada por extensión y por MIME. No hay
 * un `when (formato)` en un solo lugar: cada extractor declara a qué formatos
 * sirve y el registro arma los índices.
 */
class ExtractorRegistry(extractors: List<TextExtractor>) {

    private val byExtension: Map<String, TextExtractor> = buildMap {
        for (e in extractors) for (ext in e.extensions) put(ext.lowercase(), e)
    }

    private val byMime: Map<String, TextExtractor> = buildMap {
        for (e in extractors) for (m in e.mimes) put(m.lowercase(), e)
    }

    /**
     * Extractor para un archivo.
     *
     * El orden importa: **extensión primero**, MIME después. En Windows
     * muchos `.md` llegan como `text/plain` y en Android un `.docx` puede
     * llegar como `application/octet-stream`; al revés se elegiría mal.
     */
    fun forFile(fileName: String, mime: String): TextExtractor? =
        byExtension[extensionOf(fileName)] ?: byMime[mimeOf(mime)]

    /** Todos los extractores registrados (para la UI: "formatos soportados"). */
    val registered: List<TextExtractor> get() = byExtension.values.distinct()

    /** Extensiones soportadas, ordenadas (para los mensajes de error). */
    val supportedExtensions: List<String> get() = registered.flatMap { it.extensions }.distinct().sorted()

    companion object {
        /**
         * Registro con todos los extractores del proyecto.
         *
         * El PDF entra por [platformPdfExtractor] porque es el único
         * extractor que **no es portable**: en escritorio lee el PDF con
         * PDFBox y en Android puede no estar disponible (ver el `actual`).
         */
        fun default(): ExtractorRegistry = ExtractorRegistry(
            listOf(
                PlainTextExtractor,
                MarkupExtractor,
                ZipXmlExtractor,
            ) + listOfNotNull(platformPdfExtractor()),
        )
    }
}

/**
 * Extractor de PDF de la plataforma.
 *
 * Es la **única** costura que necesita dos implementaciones: PDFBox 2.0 es
 * JVM puro pero en Android el port oficial es otro artefacto (y el D8 a
 * veces lo rechaza). Si la plataforma no puede leer PDFs, devuelve `null` y
 * el pipeline avisa que el formato no está soportado.
 */
expect fun platformPdfExtractor(): TextExtractor?

/** Extensión en minúsculas y sin punto ("apuntes.MD" → "md"). */
internal fun extensionOf(fileName: String): String =
    fileName.substringAfterLast('.', "").substringAfterLast('/').substringAfterLast('\\').lowercase()

/** MIME normalizado: sin parámetros y en minúsculas ("text/plain; charset=utf-8" → "text/plain"). */
internal fun mimeOf(mime: String): String =
    mime.substringBefore(';').trim().lowercase()
