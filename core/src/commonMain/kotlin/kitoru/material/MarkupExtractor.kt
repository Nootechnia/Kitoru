package kitoru.material

/**
 * HTML → texto indexable.
 *
 * **Por qué un parser propio y no el de Ktor.** `ktor-client-html` es
 * **JVM-only** y este código vive en `commonMain` (es el mismo en escritorio
 * y en Android). Además no hace falta un navegador: no se ejecuta JavaScript,
 * no se cargan imágenes y no se interpreta CSS. Es un "lector de artículo":
 * saca el texto y conserva la jerarquía.
 *
 * **Por qué se conservan los encabezados.** Igual que en el DOCX: el
 * `Chunker` de `:backend` corta por las líneas que empiezan con `#`, así que
 * `<h2>Tema</h2>` tiene que salir como `## Tema` para que el material se
 * trocee por secciones y las citas digan "cátedra: Apuntes · Tema".
 */
object MarkupExtractor : TextExtractor {

    override val id = "html"

    override val extensions = setOf("html", "htm", "xhtml")

    override val mimes = setOf("text/html", "application/xhtml+xml")

    override suspend fun extract(bytes: ByteArray, fileName: String, mime: String): ExtractionResult {
        val decoded = TextEncoding.decode(bytes)
        val notes = mutableListOf<String>()
        val doc = HtmlParser(decoded.text).parse()

        if (decoded.fallbackUsed) {
            notes += "El archivo no estaba en UTF-8; se leyó como ${decoded.charsetName}."
        }
        val text = doc.text.trim()
        if (text.isBlank()) {
            notes += "El HTML no tiene texto visible (todo el contenido está en JavaScript o en imágenes)."
        }

        return ExtractionResult(
            text = text,
            title = doc.title.ifBlank { fileName.substringBeforeLast('.') },
            warning = notes.joinToString(" "),
            extractor = id,
            stats = ExtractionStats(
                chars = text.length,
                words = text.split(Regex("\\s+")).count { it.isNotBlank() },
                blocks = text.split(Regex("\\n\\s*\\n")).count { it.isNotBlank() },
            ),
        )
    }
}

/** Resultado del parser: el texto y el título del documento. */
data class HtmlDocument(val text: String, val title: String)

/**
 * Parser de HTML en una sola pasada.
 *
 * El HTML real **no es XML**: le faltan cierres de `<p>`, tiene atributos sin
 * comillas y `<` sueltos en el texto. Por eso no se puede usar un parser
 * estricto: el escáner es tolerante y nunca falla, sólo saltea lo que no
 * entiende.
 */
class HtmlParser(private val html: String) {

    /** Etiquetas cuyo contenido se descarta entero (cromo o código). */
    private val dropContent = setOf(
        "script", "style", "noscript", "svg", "canvas", "iframe", "template",
        "object", "embed", "applet", "nav", "aside", "footer", "form",
        "button", "select", "option", "textarea",
    )

    private var i = 0
    private val out = StringBuilder()
    private var title = ""
    private var inTitle = false
    private var skipDepth = 0
    private val openTags = mutableListOf<String>()
    private val linkStack = mutableListOf<String>()
    private var inPre = false
    private var preFence = false

    // Tabla en curso: se arma aparte y al final se vuelve tabla Markdown.
    private var tableRows: MutableList<List<String>>? = null
    private var tableRow: MutableList<String> = mutableListOf()
    private var cellBuffer: StringBuilder? = null

    fun parse(): HtmlDocument {
        while (i < html.length) {
            val c = html[i]
            if (c != '<') {
                val next = html.indexOf('<', i)
                val chunk = if (next < 0) html.substring(i) else html.substring(i, next)
                emit(HtmlEntities.decode(chunk))
                i = if (next < 0) html.length else next
                continue
            }
            when {
                html.startsWith("<!--", i) -> {
                    i = skipPast("-->", i + 4)
                }
                html.startsWith("<![CDATA[", i) -> {
                    val end = html.indexOf("]]>", i)
                    val raw = if (end < 0) html.substring(i + 9) else html.substring(i + 9, end)
                    emit(HtmlEntities.decode(raw))
                    i = if (end < 0) html.length else end + 3
                }
                html.startsWith("<!", i) -> {
                    i = skipPast(">", i + 2)
                }
                html.startsWith("<?", i) -> {
                    i = skipPast(">", i + 2)
                }
                html.startsWith("</", i) -> {
                    i = handleCloseTag()
                }
                i + 1 < html.length && html[i + 1].isLetter() -> {
                    i = handleOpenTag()
                }
                else -> {
                    // Un "<" suelto es texto (HTML tolerante).
                    emit("<")
                    i++
                }
            }
        }
        // Si el documento quedó con una tabla sin cerrar, se cierra igual.
        flushTable()
        return HtmlDocument(finish(out.toString()), title.trim())
    }

    /** Lee una etiqueta de apertura y devuelve el índice siguiente. */
    private fun handleOpenTag(): Int {
        var p = i + 1
        val nameStart = p
        while (p < html.length && (html[p].isLetterOrDigit() || html[p] == '-' || html[p] == ':' || html[p] == '_')) p++
        val name = html.substring(nameStart, p).lowercase()
        val attrs = parseAttributes(p)
        val selfClosing = attrs.selfClosing
        i = attrs.end

        if (skipDepth > 0) {
            if (!selfClosing && name !in VOID_TAGS) {
                skipDepth++
                openTags.add(name)
            }
            return i
        }

        when (name) {
            "title" -> inTitle = true
            "br" -> newline()
            "hr" -> newline()
            "p", "div", "section", "article", "header", "blockquote", "ul", "ol",
            "dl", "dd", "dt", "figure", "figcaption", "main", "address" -> newline()
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                newline()
                out.append("#".repeat(name[1] - '0')).append(' ')
            }
            "li" -> {
                newline()
                out.append("- ")
            }
            "pre" -> {
                newline()
                out.append("```\n")
                preFence = true
            }
            "code" -> Unit // el texto se conserva tal cual
            "a" -> linkStack.add(attrs.map["href"].orEmpty())
            "img" -> {
                val alt = attrs.map["alt"].orEmpty()
                if (alt.isNotBlank()) emit(HtmlEntities.decode(alt))
            }
            "table" -> {
                tableRows = mutableListOf()
                tableRow = mutableListOf()
            }
            "td", "th" -> {
                if (tableRows != null) cellBuffer = StringBuilder()
            }
            in dropContent -> {
                if (!selfClosing) {
                    skipDepth = 1
                    openTags.add(name)
                }
            }
            else -> Unit
        }

        if (!selfClosing && name !in VOID_TAGS && name !in dropContent) {
            openTags.add(name)
        }
        return i
    }

    /** Lee una etiqueta de cierre y devuelve el índice siguiente. */
    private fun handleCloseTag(): Int {
        val end = html.indexOf('>', i)
        val name = html.substring(i + 2, if (end < 0) html.length else end).trim().lowercase()
        i = if (end < 0) html.length else end + 1

        if (skipDepth > 0) {
            // Cierra el elemento saltado (o uno de sus hijos).
            if (openTags.isNotEmpty()) {
                openTags.removeLast()
                skipDepth--
            }
            return i
        }

        when (name) {
            "title" -> inTitle = false
            "h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "section", "article",
            "li", "blockquote", "header", "figure", "figcaption" -> newline()
            "pre" -> {
                if (preFence) {
                    out.append("```")
                    preFence = false
                }
                newline()
            }
            "a" -> {
                val href = linkStack.removeLastOrNull().orEmpty()
                if (href.isNotBlank() && !href.startsWith("#") && !href.startsWith("javascript:")) {
                    out.append(" (").append(href).append(')')
                }
            }
            "td", "th" -> {
                val buffer = cellBuffer
                if (buffer != null && tableRows != null) {
                    tableRow.add(buffer.toString().trim())
                    cellBuffer = null
                }
            }
            "tr" -> {
                if (tableRows != null) {
                    if (tableRow.isNotEmpty()) {
                        tableRows!!.add(tableRow.toList())
                        tableRow = mutableListOf()
                    }
                }
            }
            "table" -> flushTable()
            else -> Unit
        }
        if (openTags.isNotEmpty()) openTags.removeLast()
        return i
    }

    /** Escribe la tabla acumulada como tabla Markdown. */
    private fun flushTable() {
        val rows = tableRows ?: return
        tableRows = null
        if (rows.isEmpty()) return
        val width = rows.maxOf { it.size }
        newline()
        fun row(cells: List<String>) {
            val padded = cells + List(width - cells.size) { "" }
            out.append("| ").append(padded.joinToString(" | ") { it.replace("|", "\\|").trim() }).append(" |\n")
        }
        row(rows.first())
        out.append("| ").append(List(width) { "---" }.joinToString(" | ")).append(" |\n")
        for (r in rows.drop(1)) row(r)
    }

    /**
     * Escribe texto en el destino correcto: el título del documento, una
     * celda de tabla o el cuerpo.
     */
    private fun emit(text: String) {
        if (text.isEmpty()) return
        val normalized = normalizeSpaces(text)
        if (normalized.isEmpty()) return
        when {
            inTitle -> title += normalized
            cellBuffer != null -> cellBuffer!!.append(normalized)
            else -> out.append(normalized)
        }
    }

    /** Salto de línea: garantiza que no haya dos seguidos. */
    private fun newline() {
        val current = out.toString()
        if (current.isEmpty()) return
        if (current.endsWith("\n\n")) return
        if (current.endsWith("\n")) return
        out.append('\n')
    }

    /** Colapsa los espacios (el HTML no los muestra) y respeta el `<pre>`. */
    private fun normalizeSpaces(text: String): String =
        if (inPre) text else text.replace(Regex("[ \\t\\r\\n]+"), " ")

    /** Cierra el texto: una sola línea en blanco entre bloques como máximo. */
    private fun finish(text: String): String =
        text.replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    /** Salta hasta el final del marcador (o al final del documento). */
    private fun skipPast(marker: String, from: Int): Int {
        val end = html.indexOf(marker, from)
        return if (end < 0) html.length else end + marker.length
    }

    private class Attributes(val map: Map<String, String>, val selfClosing: Boolean, val end: Int)

    /**
     * Mini-escáner de atributos: `name="valor"`, `name='valor'`, `name=valor`
     * y `name` (sin valor). Sólo interesan `href` y `alt`.
     */
    private fun parseAttributes(from: Int): Attributes {
        val map = mutableMapOf<String, String>()
        var p = from
        var selfClosing = false
        while (p < html.length) {
            while (p < html.length && html[p].isWhitespace()) p++
            if (p >= html.length) break
            if (html[p] == '>') { p++; break }
            if (html[p] == '/') {
                selfClosing = true
                p++
                continue
            }
            val nameStart = p
            while (p < html.length && !html[p].isWhitespace() && html[p] != '=' && html[p] != '>' && html[p] != '/') p++
            val name = html.substring(nameStart, p).lowercase()
            while (p < html.length && html[p].isWhitespace()) p++
            var value = ""
            if (p < html.length && html[p] == '=') {
                p++
                while (p < html.length && html[p].isWhitespace()) p++
                if (p < html.length && (html[p] == '"' || html[p] == '\'')) {
                    val quote = html[p]
                    p++
                    val valueStart = p
                    while (p < html.length && html[p] != quote) p++
                    value = html.substring(valueStart, minOf(p, html.length))
                    if (p < html.length) p++
                } else {
                    val valueStart = p
                    while (p < html.length && !html[p].isWhitespace() && html[p] != '>') p++
                    value = html.substring(valueStart, p)
                }
            }
            if (name.isNotEmpty()) map[name] = HtmlEntities.decode(value)
        }
        return Attributes(map, selfClosing, p)
    }

    private companion object {
        /** Etiquetas que nunca tienen cierre. */
        val VOID_TAGS = setOf(
            "br", "hr", "img", "input", "meta", "link", "area", "base", "col",
            "embed", "param", "source", "track", "wbr",
        )
    }
}

/**
 * Decodifica entidades HTML.
 *
 * Se cubren las entidades con nombre más usadas y las numéricas
 * (`&#225;` y `&#xE1;`). Las entidades desconocidas se dejan como están:
 * es preferible ver `&nbsp;` en el texto que perder el símbolo.
 */
object HtmlEntities {

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "iexcl" to "¡", "cent" to "¢", "pound" to "£",
        "curren" to "¤", "yen" to "¥", "brvbar" to "¦", "sect" to "§",
        "uml" to "¨", "copy" to "©", "ordf" to "ª", "laquo" to "«",
        "not" to "¬", "reg" to "®", "macr" to "¯", "deg" to "°",
        "plusmn" to "±", "sup2" to "²", "sup3" to "³", "acute" to "´",
        "micro" to "µ", "para" to "¶", "middot" to "·", "cedil" to "¸",
        "sup1" to "¹", "ordm" to "º", "raquo" to "»", "frac14" to "¼",
        "frac12" to "½", "frac34" to "¾", "iquest" to "¿", "times" to "×",
        "divide" to "÷", "hellip" to "…", "ndash" to "–", "mdash" to "—",
        "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“",
        "rdquo" to "”", "bdquo" to "„", "dagger" to "†", "Dagger" to "‡",
        "bull" to "•", "permil" to "‰", "prime" to "′", "lsaquo" to "‹",
        "rsaquo" to "›", "euro" to "€", "trade" to "™", "larr" to "←",
        "uarr" to "↑", "rarr" to "→", "darr" to "↓", "harr" to "↔",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "aacute" to "á",
        "agrave" to "à", "acirc" to "â", "iacute" to "í", "igrave" to "ì",
        "icirc" to "î", "oacute" to "ó", "ograve" to "ò", "ocirc" to "ô",
        "uacute" to "ú", "ugrave" to "ù", "ucirc" to "û", "ntilde" to "ñ",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "ccedil" to "ç",
        "aring" to "å", "oslash" to "ø", "aelig" to "æ", "szlig" to "ß",
        "Aacute" to "Á", "Eacute" to "É", "Iacute" to "Í", "Oacute" to "Ó",
        "Uacute" to "Ú", "Ntilde" to "Ñ", "Auml" to "Ä", "Ouml" to "Ö",
        "Uuml" to "Ü", "Ccedil" to "Ç", "iexcl" to "¡",
    )

    fun decode(text: String): String {
        if (!text.contains('&')) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') { sb.append(c); i++; continue }
            val semi = text.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) { sb.append(c); i++; continue }
            val name = text.substring(i + 1, semi)
            val decoded = decodeEntity(name)
            if (decoded == null) { sb.append(c); i++; continue }
            sb.append(decoded)
            i = semi + 1
        }
        return sb.toString()
    }

    /** Decodifica una entidad sin el `&` ni el `;`. Null si no se conoce. */
    private fun decodeEntity(name: String): String? {
        if (name.isEmpty()) return null
        if (name[0] == '#') {
            val hex = name.length > 1 && (name[1] == 'x' || name[1] == 'X')
            val digits = if (hex) name.substring(2) else name.substring(1)
            val code = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
            if (code <= 0 || code > 0x10FFFF) return null
            return if (code <= 0xFFFF) {
                code.toChar().toString()
            } else {
                val v = code - 0x10000
                charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar())
                    .concatToString()
            }
        }
        return NAMED[name] ?: NAMED[name.lowercase()]
    }
}
