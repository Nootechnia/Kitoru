package kitoru.material

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Extractor de PDF con **PDFBox 2.0**.
 *
 * ## Por qué PDFBox
 *
 * Es la única biblioteca libre que lee texto de PDF en Java, es Apache-2.0 y
 * funciona en los dos targets de Kitoru (escritorio y Android). Las
 * alternativas (iText → AGPL, PDFium → nativa, Tesseract → OCR) quedan
 * fuera por licencia o por complejidad.
 *
 * ## Por qué la geometría y no el orden del PDF
 *
 * **El orden de lectura importa más que la calidad del texto.** Un PDF de dos
 * columnas leído en el orden equivocado produce un texto donde cada línea de
 * la columna izquierda se entrelaza con la derecha. El BM25 no lo nota
 * (indexa las mismas palabras), pero el modelo recibe fragmentos donde la
 * mitad de las frases no tienen sentido.
 *
 * Por eso el extractor **no confía en el orden en que el PDF emite el
 * texto**: recalcula el orden de lectura a partir de la geometría:
 *
 * 1. Agrupa los fragmentos en **líneas** (por solapamiento vertical).
 * 2. Detecta el **gutter** (la banda vertical vacía entre columnas).
 * 3. Reordena: dentro de cada banda, primero la columna izquierda completa y
 *    después la derecha.
 */
object JvmPdfExtractor : TextExtractor {

    override val id = "pdfbox"

    override val extensions = setOf("pdf")

    override val mimes = setOf("application/pdf")

    override suspend fun extract(bytes: ByteArray, fileName: String, mime: String): ExtractionResult {
        val document = try {
            PDDocument.load(ByteArrayInputStream(bytes))
        } catch (e: org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw MaterialExtractionException(
                ExtractionErrorCode.ENCRYPTED,
                "El PDF está protegido con contraseña. Desprotegelo y volvé a subirlo.",
                e,
            )
        } catch (e: IOException) {
            throw MaterialExtractionException(
                ExtractionErrorCode.CORRUPT,
                "No se pudo leer el PDF: ${e.message ?: "archivo dañado"}. " +
                    "Probá abrirlo de nuevo y guardarlo otra vez.",
                e,
            )
        }

        document.use { pdf ->
            val pages = mutableListOf<PdfPage>()
            val stripper = RunStripper()
            for (index in 0 until pdf.numberOfPages) {
                stripper.runs.clear()
                stripper.startPage = index + 1
                stripper.endPage = index + 1
                stripper.getText(pdf)

                val lines = groupLines(stripper.runs)
                val gutter = detectGutter(lines)
                val ordered = readingOrder(lines, gutter)
                pages += PdfPage(
                    number = index + 1,
                    lines = ordered,
                    columns = if (gutter == null) 1 else 2,
                )
            }

            // Números de página y encabezados repetidos: se limpian **por
            // página**, antes de unirlas, porque el detector necesita saber
            // qué línea está arriba y cuál abajo.
            val cleaned = TextCleanup.removeRepeatedHeaders(pages.map { it.lines })
            val finalPages = pages.mapIndexed { index, page -> page.copy(lines = cleaned[index]) }

            val emptyPages = finalPages.count { page ->
                page.lines.joinToString(" ").trim().length < MIN_PAGE_CHARS
            }
            val scanned = finalPages.isNotEmpty() &&
                emptyPages.toDouble() / finalPages.size >= SCANNED_RATIO

            val text = render(finalPages)
            val info = pdf.documentInformation

            val warnings = mutableListOf<String>()
            if (scanned) {
                warnings += "Este PDF no tiene capa de texto ($emptyPages de ${finalPages.size} páginas tienen texto). " +
                    "Parece un escaneo: las imágenes no se pueden leer. " +
                    "Subilo desde el original con texto seleccionable, " +
                    "o pegá el texto a mano en «Editar texto»."
            } else if (emptyPages > 0) {
                warnings += "$emptyPages de ${finalPages.size} páginas no tienen texto (parecen escaneadas). " +
                    "El contenido de esas páginas no se pudo leer."
            }

            return ExtractionResult(
                text = text,
                title = info?.title?.takeIf { it.isNotBlank() }.orEmpty(),
                pages = finalPages.size,
                warning = warnings.joinToString(" "),
                needsOcr = scanned,
                extractor = id,
                stats = ExtractionStats(
                    chars = text.length,
                    words = text.split(Regex("\\s+")).count { it.isNotBlank() },
                    blocks = finalPages.sumOf { it.lines.count { line -> line.isNotBlank() } },
                    columns = finalPages.maxOf { it.columns },
                ),
            )
        }
    }

    /**
     * Une las páginas en el texto indexable.
     *
     * Las marcas `## Página N` no son decorativas: el `Chunker` de `:backend`
     * corta por encabezados Markdown, así que **cada página se convierte en
     * fragmentos con encabezado "Página N"** → las citas quedan
     * "cátedra: Apuntes · Página 7" y el docente puede ubicar el contenido en
     * el PDF original.
     */
    private fun render(pages: List<PdfPage>): String = buildString {
        for (page in pages) {
            val body = page.lines.filter { it.isNotBlank() }.joinToString("\n")
            append("## Página ").append(page.number).append("\n\n")
            append(body).append("\n\n")
        }
    }.trim()

}

/** Caracteres mínimos para que una página se considere "con texto". */
private const val MIN_PAGE_CHARS = 20

/** Si esta proporción de páginas está vacía, el PDF es un escaneo. */
private const val SCANNED_RATIO = 0.6
/**
 * Extractor de PDF de la plataforma.
 *
 * En escritorio y en Android se usa PDFBox 2.0 (el mismo código para los
 * dos). Si en el futuro se usa el port `com.tom-roush:pdfbox-android` (que
 * tiene otro nombre de paquete), este es el único punto que hay que tocar.
 */
actual fun platformPdfExtractor(): TextExtractor? = JvmPdfExtractor

/** Una página del PDF con sus líneas ya en orden de lectura. */
private data class PdfPage(
    val number: Int,
    val lines: List<String>,
    /** 1 = una columna, 2 = dos columnas. */
    val columns: Int,
)

/**
 * Un fragmento de texto con su caja en la página.
 *
 * Sistema de coordenadas **ajustado a la dirección de lectura**: `x` crece
 * hacia la derecha, `y` crece **hacia abajo**, y el origen es la esquina
 * superior izquierda. Es el mismo criterio que usan
 * `TextPosition.getXDirAdj()` / `getYDirAdj()` de PDFBox.
 */
private data class PdfTextRun(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val endX: Float get() = x + width
    val bottom: Float get() = y + height
}

/**
 * Captura los fragmentos de texto con su geometría.
 *
 * `writeString` la llama PDFBox **una vez por línea** (con la lista de
 * posiciones de todos sus glifos). No se usa el texto que trae PDFBox: se
 * reconstruye desde las posiciones, que es lo que permite detectar columnas.
 */
private class RunStripper : PDFTextStripper() {

    val runs = mutableListOf<PdfTextRun>()

    init {
        // El orden lo armamos nosotros con la geometría.
        sortByPosition = false
        addMoreFormatting = false
        wordSeparator = " "
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (textPositions.isEmpty()) return
        val builder = StringBuilder(textPositions.size)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (position in textPositions) {
            val character = position.unicode ?: continue
            builder.append(character)
            val x = position.xDirAdj
            val y = position.yDirAdj
            val w = position.widthDirAdj
            val h = position.heightDir
            if (x < minX) minX = x
            if (x + w > maxX) maxX = x + w
            if (y < minY) minY = y
            if (y + h > maxY) maxY = y + h
        }
        if (builder.isEmpty() || minX > maxX) return
        runs += PdfTextRun(builder.toString(), minX, minY, maxX - minX, maxY - minY)
    }
}

/**
 * Agrupa los fragmentos en **líneas**.
 *
 * Dos fragmentos están en la misma línea si sus cajas verticales se solapan
 * al menos un 50 % de la altura del más chico. Es robusto frente a fuentes
 * de tamaños distintos (un título en negrita más grande no parte la línea en
 * dos).
 */
private fun groupLines(runs: List<PdfTextRun>): List<List<PdfTextRun>> {
    val sorted = runs.sortedBy { it.y }
    val lines = mutableListOf<List<PdfTextRun>>()
    var current = mutableListOf<PdfTextRun>()
    var top = 0f
    var bottom = 0f
    for (run in sorted) {
        if (current.isEmpty()) {
            current.add(run)
            top = run.y
            bottom = run.bottom
            continue
        }
        val overlap = minOf(bottom, run.bottom) - maxOf(top, run.y)
        val minHeight = minOf(bottom - top, run.height).coerceAtLeast(0.01f)
        if (overlap / minHeight >= 0.5f) {
            current.add(run)
            top = minOf(top, run.y)
            bottom = maxOf(bottom, run.bottom)
        } else {
            lines += current
            current = mutableListOf(run)
            top = run.y
            bottom = run.bottom
        }
    }
    if (current.isNotEmpty()) lines += current
    return lines
}

/**
 * Devuelve la abscisa del corte entre columnas, o `null` si la página es de
 * una sola columna.
 *
 * Criterio: existe una banda vertical sin texto que
 * - tenga al menos `max(8 pt, 2 % del ancho del texto)` de ancho,
 * - no sea cruzada por más del 30 % de las líneas, y
 * - deje al menos 3 líneas completamente a la izquierda y 3 a la derecha.
 *
 * El último criterio es el que evita el falso positivo clásico: una página de
 * una sola columna con un gráfico al centro tiene un "gutter" enorme, pero
 * no hay texto a la derecha.
 */
private fun detectGutter(lines: List<List<PdfTextRun>>): Float? {
    if (lines.size < 6) return null
    val all = lines.flatten()
    val minX = all.minOf { it.x }
    val maxX = all.maxOf { it.endX }
    val textWidth = maxX - minX
    if (textWidth <= 1f) return null

    // Histograma de ocupación en x (400 frascos).
    val step = textWidth / 400f
    val buckets = IntArray(401)
    for (run in all) {
        val from = ((run.x - minX) / step).toInt().coerceIn(0, 400)
        val to = ((run.endX - minX) / step).toInt().coerceIn(0, 400)
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
        } else {
            i++
        }
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
        .firstOrNull { candidate ->
            val crossing = candidate.third.first
            val left = candidate.third.second.first
            val right = candidate.third.second.second
            left >= 3 && right >= 3 && crossing.toDouble() / lines.size <= 0.3
        }
        ?.let { candidate -> (candidate.first + candidate.second) / 2f }
}

/**
 * Orden de lectura.
 *
 * - Sin gutter: de arriba abajo (y de izquierda a derecha dentro de la línea).
 * - Con gutter: se separa la página en **bandas** delimitadas por las líneas
 *   de **ancho completo** (los títulos a dos columnas, que son el único
 *   elemento que cruza el canal). Dentro de cada banda se lee la columna
 *   izquierda completa y después la derecha.
 */
private fun readingOrder(lines: List<List<PdfTextRun>>, gutter: Float?): List<String> {
    if (gutter == null) return lines.map { line -> lineText(line) }

    val out = mutableListOf<String>()
    var band = mutableListOf<List<PdfTextRun>>()

    for (line in lines) {
        val minX = line.minOf { it.x }
        val maxX = line.maxOf { it.endX }
        val isFullWidth = minX < gutter && maxX > gutter
        if (isFullWidth) {
            out += orderBand(band, gutter)
            band = mutableListOf()
            out += lineText(line) // el título de ancho completo
        } else {
            band += line
        }
    }
    out += orderBand(band, gutter)
    return out
}

/** Ordena una banda: columna izquierda completa y después la derecha. */
private fun orderBand(band: List<List<PdfTextRun>>, gutter: Float): List<String> {
    if (band.isEmpty()) return emptyList()
    val left = band.filter { line -> line.maxOf { it.endX } <= gutter }
    val right = band.filter { line -> line.minOf { it.x } >= gutter }
    return when {
        left.isEmpty() -> right.map { line -> lineText(line) }
        right.isEmpty() -> left.map { line -> lineText(line) }
        else -> left.map { line -> lineText(line) } + right.map { line -> lineText(line) }
    }
}

/** Texto de una línea: los fragmentos ordenados por x, con espacios en los huecos. */
private fun lineText(runs: List<PdfTextRun>): String {
    val builder = StringBuilder()
    var previousEnd = Float.NaN
    for (run in runs.sortedBy { it.x }) {
        if (!previousEnd.isNaN()) {
            val gap = run.x - previousEnd
            // Un hueco mayor a ~1/5 del alto de la fuente es un espacio.
            if (gap > 0.2f * run.height) builder.append(' ')
        }
        builder.append(run.text)
        previousEnd = run.endX
    }
    return builder.toString().trim()
}
