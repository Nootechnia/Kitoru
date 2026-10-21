package kitoru.material

import kitoru.platform.stripDiacritics

/**
 * Limpieza común a todos los formatos.
 *
 * Va **después** de la extracción y no adentro de cada extractor: el DOCX, el
 * PDF y el HTML tienen los mismos problemas finales (whitespace, guiones de
 * corte de línea, números de página) y duplicar la lógica en tres lugares
 * garantiza que se queden distintos.
 *
 * **Qué no hace (a propósito).** No quita tildes ni "corrige" la ortografía:
 * el texto que ve el docente tiene que ser el que escribió. El índice ya
 * normaliza con `stripDiacritics` cuando tokeniza.
 */
object TextCleanup {

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
     * Se **une sin guion** (no "auto móvil"): es lo estándar y acierta en la
     * enorme mayoría de los casos, porque el guion de corte de línea se
     * inserta en un límite silábico y unir restituye la palabra original. El
     * contraejemplo es un compuesto con guion real partido por el corte
     * ("auto-\nmatizado"), pero el costo en el RAG es bajo comparado con no
     * unir: "automóvil" nunca se encontraría en el índice.
     */
    private val HYPHEN_BREAK = Regex("(\\p{L})-\\n(\\p{L})")

    /** "12", "- 12 -", "— 12 —": un número de página suelto. */
    private val ONLY_DIGITS = Regex("^\\s*[\\u2013\\u2014\\-\\u2212]?\\s*\\p{N}{1,3}\\s*[\\u2013\\u2014\\-\\u2212]?\\s*$")

    /** "Página 3 de 12", "Page 3 of 12", "pág. 3", "Fol. 3". */
    private val NAMED = Regex(
        "^\\s*(p\\.?\\s*gina|pagina|página|pag\\.?|p\\.g\\.?|page|fol\\.?|seite|bl\\.?)\\s*\\p{N}{1,4}" +
            "(\\s*(de|del|of|/|de la)\\s*\\p{N}{1,4})?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** "12 | Nombre del apunte": pie típico de LaTeX y Word. */
    private val PIPE = Regex("^\\s*\\p{N}{1,4}\\s*[|/]\\s*\\S.{0,90}$")

    /**
     * Cantidad mínima de bloques para que "repetido" signifique algo.
     *
     * Con dos o tres bloques, una coincidencia es casualidad: el texto se
     * perdería sin ganar nada.
     */
    const val MIN_BLOCKS = 4

    /** Umbral: una línea que aparece en más de este porcentaje de bloques es pie de página. */
    const val REPEATED_THRESHOLD = 0.5

    /**
     * Limpia el texto extraído.
     *
     * @param text texto crudo del extractor.
     * @return texto limpio, listo para indexar.
     */
    fun clean(text: String): String {
        if (text.isEmpty()) return text
        var out = text

        // 1) BOM y caracteres de ancho cero: no se ven pero ensucian el índice.
        out = out.replace("\uFEFF", "").replace("\u200B", "").replace("\u200C", "").replace("\u200D", "")

        // 2) Caracteres de control (menos \n, \r y \t).
        out = CONTROL.replace(out, "")

        // 3) Ligaduras tipográficas.
        for ((k, v) in LIGATURES) out = out.replace(k.toString(), v)

        // 4) Guion blando: se borra y se unen las dos partes.
        out = out.replace("\u00AD", "")

        // 5) Fin de línea unificado.
        out = out.replace("\r\n", "\n").replace('\r', '\n')

        // 6) Espacios al final de la línea (antes de unir guiones: "auto- \n").
        out = TRAILING.replace(out, "")

        // 7) Guiones de corte de línea → palabras unidas.
        out = mapOutsideCodeFences(out) { segment -> HYPHEN_BREAK.replace(segment) { m -> m.groupValues[1] + m.groupValues[2] } }

        // 8) Espacios colapsados (los bloques de código conservan la indentación).
        out = mapOutsideCodeFences(out) { segment -> SPACES.replace(segment, " ") }

        // 9) Como máximo dos líneas en blanco seguidas.
        out = BLANKS.replace(out, "\n\n")

        // 10) Números de página sueltos.
        out = stripPageNumbers(out)

        // 11) Encabezados y pies repetidos.
        out = removeRepeatedBlocks(out)

        return out.trim()
    }

    /**
     * Quita el BOM y los caracteres invisibles.
     *
     * Un BOM al principio del texto hace que el primer título sea "\uFEFF# Título"
     * y el `Chunker` no lo reconoce como encabezado.
     */
    fun removeBom(text: String): String =
        text.replace("\uFEFF", "").replace("\u200B", "").replace("\u200C", "").replace("\u200D", "")

    /**
     * Colapsa los espacios fuera de los bloques de código.
     *
     * El `Chunker` ya reconoce los fences ``` ``` ``` y no corta dentro de
     * ellos: si la limpieza colapsara la indentación de un bloque de código,
     * el ejemplo quedaría mal.
     */
    fun normalizeWhitespace(text: String): String =
        mapOutsideCodeFences(text) { segment -> SPACES.replace(segment, " ") }

    /**
     * Une las palabras partidas por un guion de corte de línea.
     *
     * "auto-\nmóvil" → "automóvil".
     */
    fun joinLineBreakHyphens(text: String): String =
        mapOutsideCodeFences(text) { segment -> HYPHEN_BREAK.replace(segment) { m -> m.groupValues[1] + m.groupValues[2] } }

    /**
     * Quita las líneas que son sólo un número de página.
     *
     * Se borran la línea completa, no sólo el número: el número solo no
     * aporta nada y la línea vacía separa mejor los párrafos.
     */
    fun stripPageNumbers(text: String): String =
        text.split('\n')
            .filterNot { isPageNumber(it) }
            .joinToString("\n")

    /** ¿Esta línea es sólo un número de página? */
    fun isPageNumber(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        return NAMED.matches(trimmed) || PIPE.matches(trimmed) || ONLY_DIGITS.matches(trimmed)
    }

    /**
     * Quita los encabezados y pies que se repiten.
     *
     * Regla: un bloque que aparece en **más del 50 %** de los bloques del
     * documento es un pie de página (el título del apunte, el nombre de la
     * cátedra, "Página 3 de 12"…) y se elimina de todas partes.
     *
     * **Por qué no se borran los bloques que son encabezados de Markdown.**
     * El PDF marca cada página con "## Página N": normalizados (sin dígitos)
     * todos los marcadores son iguales, así que la regla los borraría a todos
     * y el texto quedaría sin paginación (y las citas sin página). Los
     * encabezados de Markdown son estructura, no ruido.
     */
    fun removeRepeatedBlocks(text: String, threshold: Double = REPEATED_THRESHOLD): String {
        val blocks = text.split(Regex("\\n\\s*\\n"))
        if (blocks.size < MIN_BLOCKS) return text

        val keys = blocks.map { block ->
            if (block.trimStart().startsWith("#")) null else normalizeKey(block)
        }
        val counts = mutableMapOf<String, Int>()
        for (key in keys) if (key != null) counts[key] = counts.getOrDefault(key, 0) + 1

        val limit = blocks.size * threshold
        val repeated = counts.filterValues { it > limit }.keys
        if (repeated.isEmpty()) return text

        return blocks.filterIndexed { index, _ ->
            val key = keys[index] ?: return@filterIndexed true
            key !in repeated
        }.joinToString("\n\n")
    }

    /**
     * Quita los encabezados y pies repetidos **por página**.
     *
     * Es la versión que usa el PDF: conoce la estructura de la página (qué
     * líneas están arriba y cuáles abajo) y sólo mira la cabecera y el pie,
     * porque en el medio de la página un texto repetido es contenido.
     *
     * @param pages páginas, cada una con sus líneas (en orden).
     * @return las páginas con las líneas repetidas eliminadas.
     */
    fun removeRepeatedHeaders(
        pages: List<List<String>>,
        zone: Int = ZONE,
        threshold: Double = REPEATED_THRESHOLD,
    ): List<List<String>> {
        if (pages.size < MIN_PAGES) {
            return pages.map { page -> page.filterNot { isPageNumber(it) } }
        }
        val headCounts = mutableMapOf<String, Int>()
        val footCounts = mutableMapOf<String, Int>()
        for (page in pages) {
            val lines = page.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            for (line in lines.take(zone)) {
                val key = normalizeKey(line) ?: continue
                headCounts[key] = headCounts.getOrDefault(key, 0) + 1
            }
            for (line in lines.takeLast(zone)) {
                val key = normalizeKey(line) ?: continue
                footCounts[key] = footCounts.getOrDefault(key, 0) + 1
            }
        }
        val limit = pages.size * threshold
        val repeatedHead = headCounts.filterValues { it > limit }.keys
        val repeatedFoot = footCounts.filterValues { it > limit }.keys

        return pages.map { page ->
            val lines = page.filter { it.isNotBlank() }
            val kept = lines.filterIndexed { index, line ->
                val inHead = index < zone
                val inFoot = index >= lines.size - zone
                if ((inHead || inFoot) && isPageNumber(line)) return@filterIndexed false
                val key = normalizeKey(line)
                if (inHead && key != null && key in repeatedHead) return@filterIndexed false
                if (inFoot && key != null && key in repeatedFoot) return@filterIndexed false
                true
            }
            // Nunca dejar una página vacía si tenía contenido.
            if (kept.isEmpty() && lines.isNotEmpty()) lines else kept
        }
    }

    /**
     * Normaliza una línea para compararla con otra.
     *
     * Se quitan los dígitos (por eso "Página 3 de 12" y "Página 7 de 12" son
     * la misma clave) y los acentos (por eso "Programación" y "Programacion"
     * son la misma clave).
     */
    fun normalizeKey(line: String): String? {
        val key = stripDiacritics(line.lowercase())
            .replace(Regex("\\p{N}+"), " ")
            .replace(Regex("[^\\p{L}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return key.ifEmpty { null }?.takeIf { it.length >= 3 }
    }

    /** Cuántas líneas se miran arriba y abajo de cada página. */
    const val ZONE = 3

    /** Con menos páginas que esto, "repetido" no significa nada. */
    const val MIN_PAGES = 4

    /**
     * Aplica una transformación sólo fuera de los bloques de código.
     *
     * Los bloques delimitados por ``` ``` ``` o `~~~` conservan su contenido
     * tal cual: la indentación es parte del ejemplo.
     */
    private fun mapOutsideCodeFences(text: String, transform: (String) -> String): String {
        val segments = splitCodeFences(text)
        if (segments.size == 1) return transform(text)
        return segments.joinToString("") { segment -> if (segment.isCode) segment.text else transform(segment.text) }
    }

    private data class Segment(val text: String, val isCode: Boolean)

    /** Parte el texto en segmentos de código y de texto normal. */
    private fun splitCodeFences(text: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        val current = StringBuilder()
        var inFence = false
        var currentIsCode = false
        for (line in text.split('\n')) {
            val trimmed = line.trimStart()
            val isFence = trimmed.startsWith("```") || trimmed.startsWith("~~~")
            val lineIsCode = inFence
            if (current.isNotEmpty() && lineIsCode != currentIsCode) {
                segments += Segment(current.toString(), currentIsCode)
                current.clear()
            }
            currentIsCode = lineIsCode
            current.append(line).append('\n')
            if (isFence) inFence = !inFence
        }
        if (current.isNotEmpty()) segments += Segment(current.toString(), currentIsCode)
        return segments
    }
}
