package kitoru.material

/**
 * Texto plano, Markdown, reStructuredText, AsciiDoc y CSV.
 *
 * **Por qué un solo extractor para los cinco.** Los cuatro primeros son
 * "el mismo archivo con sintaxis de títulos diferente": el contenido útil es
 * el texto, y lo único que hay que hacer es convertir los títulos a Markdown
 * para que el `Chunker` de `:backend` pueda cortar por secciones. El CSV es
 * una tabla: también es texto, pero necesita un parser de comillas.
 *
 * **Por qué el Markdown se conserva tal cual.** El `Chunker` corta por líneas
 * que empiezan con `#`; un `.md` con encabezados se indexa mejor que un
 * `.txt` con el mismo contenido. Convertir a texto pelado sería tirar
 * información que ya está ahí.
 */
object PlainTextExtractor : TextExtractor {

    override val id = "plain-text"

    override val extensions = setOf(
        "md", "markdown", "mdx",
        "txt", "text", "log",
        "rst",
        "adoc", "asciidoc",
        "csv", "tsv",
    )

    override val mimes = setOf(
        "text/plain", "text/markdown", "text/x-markdown",
        "text/x-rst", "text/asciidoc",
        "text/csv", "text/tab-separated-values",
    )

    override suspend fun extract(bytes: ByteArray, fileName: String, mime: String): ExtractionResult {
        val decoded = TextEncoding.decode(bytes)
        val notes = mutableListOf<String>()

        var text = decoded.text
        when (extensionOf(fileName)) {
            "rst" -> text = RstConverter.toMarkdown(text)
            "adoc", "asciidoc" -> text = AdocConverter.toMarkdown(text)
            "csv", "tsv" -> {
                val table = CsvTable.toMarkdown(text)
                text = table.markdown
                if (table.truncated) {
                    notes += "El CSV tiene más de ${CsvTable.MAX_ROWS} filas; " +
                        "se indexaron las primeras ${CsvTable.MAX_ROWS}."
                }
            }
        }

        if (decoded.fallbackUsed) {
            notes += "El archivo no estaba en UTF-8; se leyó como ${decoded.charsetName}. " +
                "Revisá que no haya caracteres raros."
        }
        if (text.isBlank()) {
            notes += "El archivo no tiene texto (está vacío o sólo tiene imágenes)."
        }

        return ExtractionResult(
            text = text.trim(),
            title = firstHeading(text).ifBlank { fileName.substringBeforeLast('.') },
            warning = notes.joinToString(" "),
            extractor = id,
            stats = ExtractionStats(
                chars = text.length,
                words = text.split(WHITESPACE).count { it.isNotBlank() },
                blocks = text.split(BLANK_LINE).count { it.isNotBlank() },
            ),
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

    private val WHITESPACE = Regex("\\s+")
    private val BLANK_LINE = Regex("\\n\\s*\\n")
}

/**
 * Decodificación de texto plano.
 *
 * Orden: BOM primero, después UTF-8 **estricto**, después Latin-1 (que nunca
 * falla, así que es el último recurso).
 *
 * > **Por qué el UTF-8 estricto y no `bytes.toString(Charsets.UTF_8)`.** En
 * > Kotlin/JVM, `toString(Charsets.UTF_8)` reemplaza los bytes inválidos por
 * > "�" sin avisar: un `.txt` en Latin-1 se convertiría en una tanda de
 * > rombos y el docente vería un texto corrupto **sin ningún aviso**. Con el
 * > decoder estricto, si el archivo no es UTF-8 válido caemos al fallback
 * > Latin-1 y le decimos al docente lo que pasó.
 */
object TextEncoding {

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
        // 1) BOM UTF-8.
        if (bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals(UTF8_BOM)) {
            return Decoded(
                text = decodeUtf8Strict(bytes.copyOfRange(3, bytes.size)) ?: decodeLatin1(bytes.copyOfRange(3, bytes.size)),
                charsetName = "UTF-8",
                fallbackUsed = false,
                hadBom = true,
            )
        }
        // 2) BOM UTF-16 LE / BE.
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Decoded(decodeUtf16(bytes, 2, littleEndian = true), "UTF-16LE", fallbackUsed = false, hadBom = true)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Decoded(decodeUtf16(bytes, 2, littleEndian = false), "UTF-16BE", fallbackUsed = false, hadBom = true)
        }
        // 3) UTF-8 estricto.
        val strict = decodeUtf8Strict(bytes)
        if (strict != null) return Decoded(strict, "UTF-8", fallbackUsed = false)
        // 4) Latin-1: nunca falla, pero conserva el byte como carácter.
        return Decoded(decodeLatin1(bytes), "Latin-1", fallbackUsed = true)
    }

    /**
     * UTF-8 estricto en Kotlin puro.
     *
     * `java.nio.charset` no existe en `commonMain`, así que el decoder va
     * escrito a mano. Devuelve `null` si los bytes no son UTF-8 válido
     * (sobrecodificación, surrogados, código de punto inválido o secuencia
     * truncada).
     */
    fun decodeUtf8Strict(bytes: ByteArray): String? {
        val sb = StringBuilder(bytes.size)
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b0 = bytes[i].toInt() and 0xFF
            val size: Int
            var cp: Int
            when {
                b0 < 0x80 -> { cp = b0; size = 1 }
                b0 in 0xC2..0xDF -> { cp = b0 and 0x1F; size = 2 }
                b0 in 0xE0..0xEF -> { cp = b0 and 0x0F; size = 3 }
                b0 in 0xF0..0xF4 -> { cp = b0 and 0x07; size = 4 }
                else -> return null // byte de continuación suelto o líder inválido
            }
            if (i + size > n) return null // secuencia truncada
            for (k in 1 until size) {
                val b = bytes[i + k].toInt() and 0xFF
                if (b !in 0x80..0xBF) return null
                cp = (cp shl 6) or (b and 0x3F)
            }
            // Sobrecodificaciones (un carácter de 2 bytes no puede ser < 0x80).
            if (size == 3 && cp < 0x800) return null
            if (size == 4 && cp < 0x10000) return null
            if (cp > 0x10FFFF) return null
            if (cp in 0xD800..0xDFFF) return null // surrogados sueltos
            appendCodePoint(sb, cp)
            i += size
        }
        return sb.toString()
    }

    /** Latin-1: cada byte es un carácter (0..255). Nunca falla. */
    fun decodeLatin1(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        for (b in bytes) sb.append((b.toInt() and 0xFF).toChar())
        return sb.toString()
    }

    /** UTF-16 con BOM: se lee de a dos bytes. */
    private fun decodeUtf16(bytes: ByteArray, offset: Int, littleEndian: Boolean): String {
        val sb = StringBuilder((bytes.size - offset) / 2)
        var i = offset
        while (i + 1 < bytes.size) {
            val hi = bytes[i].toInt() and 0xFF
            val lo = bytes[i + 1].toInt() and 0xFF
            val unit = if (littleEndian) (hi or (lo shl 8)) else ((hi shl 8) or lo)
            sb.append(unit.toChar())
            i += 2
        }
        return sb.toString()
    }

    /** `StringBuilder` no tiene `appendCodePoint` en `commonMain`: se hace a mano. */
    private fun appendCodePoint(sb: StringBuilder, cp: Int) {
        if (cp <= 0xFFFF) {
            sb.append(cp.toChar())
        } else {
            val v = cp - 0x10000
            sb.append((0xD800 + (v shr 10)).toChar())
            sb.append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }
}

/**
 * reStructuredText → Markdown.
 *
 * En RST los títulos van **subrayados** (`====`, `----`), no con `#`. El
 * nivel sale del carácter del subrayado (por convención `=` es nivel 1, `-`
 * nivel 2, `~` nivel 3, `^` nivel 4), no de la cantidad de caracteres.
 */
object RstConverter {

    private val UNDERLINE = Regex("^([=\\-~^\"'`#*+._:])\\1{1,}\\s*$")

    /** Carácter del subrayado → nivel de encabezado (1..6). */
    private val LEVEL_BY_CHAR = mapOf(
        '=' to 1, '-' to 2, '~' to 3, '^' to 4, '"' to 5, '\'' to 6,
    )

    fun toMarkdown(text: String): String {
        val lines = text.split('\n')
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val next = lines.getOrNull(i + 1)
            if (next != null && line.isNotBlank() && UNDERLINE.matches(next)) {
                val char = next.trimStart().first()
                val level = LEVEL_BY_CHAR[char] ?: 1
                out.append("#".repeat(level)).append(' ').append(line.trim()).append('\n')
                i += 2
                continue
            }
            out.append(convertBullets(line)).append('\n')
            i++
        }
        return out.toString().trim()
    }

    /** `* ítem` y `- ítem` de RST pasan a `- ítem` de Markdown. */
    private fun convertBullets(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("* ") || trimmed.startsWith("- ")) {
            val indent = line.length - trimmed.length
            return " ".repeat(indent) + "- " + trimmed.substring(2)
        }
        return line
    }
}

/**
 * AsciiDoc → Markdown.
 *
 * `= Título` → `# Título`, `== Sección` → `## Sección`. Los ítems `* x` y
 * `. x` → `- x`.
 */
object AdocConverter {

    fun toMarkdown(text: String): String = buildString {
        for (line in text.split('\n')) {
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length
            if (trimmed.startsWith("= ")) {
                val level = trimmed.takeWhile { it == '=' }.length.coerceIn(1, 6)
                append("#".repeat(level)).append(' ').append(trimmed.substring(level).trim()).append('\n')
                continue
            }
            if ((trimmed.startsWith("* ") || trimmed.startsWith(". ")) && !trimmed.startsWith("** ")) {
                append(" ".repeat(indent)).append("- ").append(trimmed.substring(2)).append('\n')
                continue
            }
            append(line).append('\n')
        }
    }.trim()
}

/**
 * CSV → tabla Markdown.
 *
 * **Por qué tabla y no texto plano.**
 *
 * 1. El `Chunker` no parte una fila por la mitad: con el CSV plano, una celda
 *    larga deja un fragmento que termina a mitad de fila; con la tabla, cada
 *    fila es una línea.
 * 2. La estructura sobrevive: una tabla de "Nota / Alumno" permite que el
 *    modelo diga "el alumno X sacó un 7" con la relación fila↔columna intacta.
 * 3. El costo es un `| --- |` de ruido, que el tokenizador de BM25 reduce a
 *    tokens de un carácter que descarta. Negligible.
 */
object CsvTable {

    /** Tope de filas: más que esto es una tabla, no un material legible. */
    const val MAX_ROWS = 5_000

    data class Result(val markdown: String, val truncated: Boolean, val rows: Int)

    fun toMarkdown(text: String): Result {
        val delimiter = detectDelimiter(text)
        val all = parse(text, delimiter)
        if (all.isEmpty()) return Result("", false, 0)
        val truncated = all.size > MAX_ROWS
        val rows = if (truncated) all.take(MAX_ROWS) else all
        return Result(render(rows), truncated, rows.size)
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
        val counts = candidates.associateWith { c -> lines.sumOf { countOutsideQuotes(it, c) } }
        // Desempate: el que más apariciones; a igualdad, la coma.
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    private fun countOutsideQuotes(line: String, char: Char): Int {
        var count = 0
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == char && !inQuotes -> count++
            }
        }
        return count
    }

    /**
     * Parser CSV: campo entre comillas dobles, `""` es una comilla, el salto
     * de línea dentro de un campo entrecomillado se conserva.
     */
    fun parse(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"'); i += 2; continue
                }
                c == '"' -> { inQuotes = !inQuotes; i++; continue }
                !inQuotes && c == delimiter -> {
                    row.add(field.toString().trim()); field.setLength(0); i++; continue
                }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    row.add(field.toString().trim()); field.setLength(0)
                    rows += row; row = mutableListOf()
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    i++; continue
                }
                else -> { field.append(c); i++; continue }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString().trim())
            rows += row
        }
        return rows.filter { it.any { cell -> cell.isNotBlank() } }
    }

    private fun render(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val width = rows.maxOf { it.size }
        fun escape(s: String) = s.replace("|", "\\|").replace("\n", " ").trim()
        return buildString {
            fun row(cells: List<String>) {
                val padded = cells + List(width - cells.size) { "" }
                append("| ").append(padded.joinToString(" | ") { escape(it) }).append(" |\n")
            }
            row(rows.first())
            append("| ").append(List(width) { "---" }.joinToString(" | ")).append(" |\n")
            for (r in rows.drop(1)) row(r)
        }.trimEnd()
    }
}
