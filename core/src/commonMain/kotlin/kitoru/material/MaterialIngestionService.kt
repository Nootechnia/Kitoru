package kitoru.material

import kitoru.domain.Material
import kitoru.platform.ioDispatcher
import kotlinx.coroutines.withContext

/**
 * Estado de un material en el ciclo de indexación.
 *
 * Es la forma en que el pipeline le dice al docente **por qué** un material
 * no está en el índice: "Listo" y "Requiere revisión" son cosas muy
 * distintas para el que está preparando la cátedra.
 */
enum class MaterialStatus(val label: String, val description: String) {
    /** Subido, todavía sin leer. */
    PENDING("Pendiente", "Se subió pero todavía no se leyó su contenido."),

    /** Extrayendo el texto ahora mismo. */
    EXTRACTING("Extrayendo", "Se está leyendo el archivo para extraer su texto."),

    /** Texto extraído, listo para trocear e indexar. */
    INDEXING("Indexando", "El texto se extrajo y se está armando el índice."),

    /** Texto extraído y guardado: el material está en el índice. */
    READY("Listo", "El texto se extrajo y el material está en el índice."),

    /** Se leyó, pero hay que revisarlo (sin texto, escaneado, imágenes). */
    NEEDS_REVIEW("Requiere revisión", "El material se leyó, pero el texto necesita una mirada del docente."),

    /** La extracción falló. El mensaje está en [IngestionResult.error]. */
    FAILED("Error", "No se pudo extraer el texto del archivo."),
}

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

    /** Tope de filas de una hoja de Excel. */
    val maxSheetRows: Int = 2_000,
)

/**
 * Resultado de la ingestión de un material.
 *
 * @param material el material **actualizado** (con `extractedText` y `warning`).
 * @param extraction el detalle de la extracción.
 * @param status en qué estado quedó el material.
 * @param error el mensaje en español cuando la extracción falló.
 */
data class IngestionResult(
    val material: Material,
    val extraction: ExtractionResult,
    val status: MaterialStatus,
    val error: String = "",
    /** La excepción original, para el registro de errores. */
    val cause: Throwable? = null,
) {
    /** ¿El material aporta fragmentos al índice? */
    val isIndexable: Boolean get() = status == MaterialStatus.READY && material.extractedText.isNotBlank()
}

/**
 * Pipeline de ingestión de un material.
 *
 * **El orden de los pasos es el que importa**:
 *
 * ```
 * bytes → despacho por extensión → extracción → limpieza → truncado → estado
 * ```
 *
 * - **Despacho por extensión primero**: en Windows un `.md` llega como
 *   `text/plain` y en Android un `.docx` llega como
 *   `application/octet-stream`; si se despatara por MIME se elegiría mal.
 * - **La limpieza va después de extraer** y no adentro de cada extractor: el
 *   DOCX, el PDF y el HTML tienen los mismos problemas finales y duplicar la
 *   lógica garantiza que se queden distintos.
 * - **El truncado va al final**: es el último paso antes de guardar, así el
 *   texto que se guarda es exactamente el que se indexa.
 *
 * **La indexación (BM25) no está acá**: vive en `:backend`, que depende de
 * `:core` (al revés no se puede). Este servicio deja el material con el
 * texto listo y devuelve el estado; el llamador dispara la reindexación.
 */
class MaterialIngestionService(
    private val registry: ExtractorRegistry = ExtractorRegistry.default(),
    private val limits: ExtractionLimits = ExtractionLimits(),
) {

    /**
     * Extrae el texto de un material y actualiza sus campos.
     *
     * **No lanza excepciones por un archivo roto**: devuelve el resultado
     * con [MaterialStatus.FAILED] y el mensaje en español, porque el docente
     * tiene que **ver** el archivo que falló (un error invisible se pierde).
     *
     * @param material el material tal como está (se devuelve actualizado).
     * @param bytes el contenido del archivo.
     * @param mime el MIME declarado por el sistema (puede ser inútil).
     * @param onProgress avisos de progreso para la UI.
     */
    suspend fun ingest(
        material: Material,
        bytes: ByteArray,
        mime: String = "",
        onProgress: (String) -> Unit = {},
    ): IngestionResult = withContext(ioDispatcher) {
        val fileName = material.path.substringAfterLast('/').ifBlank { material.title }

        if (bytes.isEmpty()) {
            return@withContext failed(material, "El archivo está vacío (0 bytes).")
        }
        if (bytes.size > limits.maxFileBytes) {
            val weight = bytes.size / 1024 / 1024
            val max = limits.maxFileBytes / 1024 / 1024
            return@withContext failed(
                material,
                "El archivo pesa $weight MB y el máximo por archivo es $max MB.",
            )
        }

        val extractor = registry.forFile(fileName, mime)
        if (extractor == null) {
            return@withContext failed(material, unsupportedMessage(fileName))
        }

        onProgress("Extrayendo el texto de «$fileName»…")

        val raw = try {
            extractor.extract(bytes, fileName, mime)
        } catch (e: MaterialExtractionException) {
            return@withContext failed(material, e.message ?: "No se pudo leer el archivo.", e)
        } catch (e: Exception) {
            return@withContext failed(
                material,
                "No se pudo leer el archivo: ${e.message ?: "error desconocido"}. Probá de nuevo o subilo en otro formato.",
                e,
            )
        }

        // Limpieza común (después de extraer, nunca adentro del extractor).
        var text = TextCleanup.clean(raw.text)
        var truncated = raw.truncated
        if (text.length > limits.maxChars) {
            text = text.take(limits.maxChars)
            truncated = true
        }

        // El texto "casi vacío" es tan inútil como el vacío: no hay nada que
        // indexar y conviene avisarlo en vez de fingir que se leyó algo.
        val almostEmpty = text.isBlank() || text.length < MIN_TEXT_CHARS
        val needsOcr = raw.needsOcr || almostEmpty

        val notes = mutableListOf<String>()
        if (raw.warning.isNotBlank()) notes += raw.warning
        if (truncated) {
            notes += "El texto extraído tiene más de ${limits.maxChars} caracteres; " +
                "se indexaron los primeros ${limits.maxChars}. Revisá el texto en «Editar texto»."
        }
        if (needsOcr && raw.warning.isBlank()) {
            notes += if (text.isBlank()) {
                "El archivo no tiene texto (es un escaneo o sólo tiene imágenes). " +
                    "No se puede indexar: subilo desde el original con texto seleccionable, " +
                    "o pegá el texto a mano en «Editar texto»."
            } else {
                "El archivo tiene muy poco texto (${text.length} caracteres): " +
                    "parece un escaneo. Revisá el material."
            }
        }
        val warning = notes.joinToString(" ")

        val extraction = raw.copy(
            text = text,
            warning = warning,
            needsOcr = needsOcr,
            truncated = truncated,
            stats = raw.stats.copy(
                chars = text.length,
                words = text.split(Regex("\\s+")).count { it.isNotBlank() },
            ),
        )

        val updated = material.copy(extractedText = text, warning = warning)
        val status = when {
            needsOcr -> MaterialStatus.NEEDS_REVIEW
            else -> MaterialStatus.READY
        }
        if (status == MaterialStatus.READY) onProgress("Texto extraído: ${text.length} caracteres.")

        IngestionResult(
            material = updated,
            extraction = extraction,
            status = status,
        )
    }

    /** Material que no se pudo leer: queda en [MaterialStatus.FAILED] con el motivo. */
    private fun failed(material: Material, message: String, cause: Throwable? = null): IngestionResult {
        val updated = material.copy(extractedText = "", warning = message)
        return IngestionResult(
            material = updated,
            extraction = ExtractionResult(
                warning = message,
                extractor = "",
                needsOcr = false,
            ),
            status = MaterialStatus.FAILED,
            error = message,
            cause = cause,
        )
    }

    /** El mensaje de "formato no soportado", con la lista de lo que sí se puede leer. */
    private fun unsupportedMessage(fileName: String): String {
        val name = fileName.substringAfterLast('/')
        return "«$name» no es un formato que se pueda indexar. Se admiten: " +
            registry.supportedExtensions.joinToString(", ") { ".$it" } + "."
    }

    private companion object {
        /**
         * Caracteres mínimos para que el texto sea "algo".
         *
         * Un PDF escaneado puede dejar dos o tres palabras sueltas (el pie de
         * página); indexar eso es peor que avisar que no hay texto.
         */
        const val MIN_TEXT_CHARS = 20
    }
}
