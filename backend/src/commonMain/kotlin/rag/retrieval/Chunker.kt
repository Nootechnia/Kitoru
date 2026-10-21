package rag.retrieval

/**
 * Troceador de documentos.
 *
 * Trocea por **estructura**, no por índice de carácter:
 *
 *  1. **Secciones**: los títulos se detectan por patrón (Markdown, numeración
 *     jerárquica, "Tema 3", "CAPÍTULO 2", línea en mayúsculas). Un PDF no
 *     tiene encabezados Markdown, pero sí tiene títulos: sin esto, todos los
 *     fragmentos de un PDF quedan con el mismo `heading` ("Página 3") y la cita
 *     no dice de qué tema habla.
 *  2. **Párrafos**: dentro de una sección, el texto se agrupa por bloques
 *     separados por línea en blanco.
 *  3. **Oraciones**: un párrafo que no entra entero se parte por oración.
 *
 * El corte **nunca** cae en mitad de una palabra: los límites son de oración,
 * de párrafo o, en el último resorto, de palabra ([cutAtBoundary]).
 *
 * El solapamiento es **por oración**: se arrastra la última oración completa
 * del fragmento anterior (con tope de 180 caracteres y piso de 20), no 150
 * caracteres arbitrarios que parten palabras al azar.
 */
object Chunker {

    /**
     * Trocea un documento con la política de su forma.
     *
     * @param doc el documento a trocear.
     * @param shape la forma del material (decide el tamaño de los fragmentos).
     * @param courseChunkSize el tamaño que eligió el docente: es el **tope**,
     *   no el objetivo.
     */
    fun split(
        doc: Doc,
        shape: MaterialShape,
        courseChunkSize: Int = ChunkingStrategy.DEFAULT_COURSE_CHUNK_SIZE,
    ): List<Chunk> = split(doc, ChunkingStrategy.forShape(shape, courseChunkSize))

    /**
     * Trocea un documento con la política de su extensión.
     *
     * @param doc el documento a trocear.
     * @param path la ruta o el nombre del material: de ahí sale la forma.
     * @param courseChunkSize el tamaño que eligió el docente.
     */
    fun split(
        doc: Doc,
        path: String,
        courseChunkSize: Int = ChunkingStrategy.DEFAULT_COURSE_CHUNK_SIZE,
    ): List<Chunk> = split(doc, ChunkingStrategy.forPath(path, courseChunkSize))

    /**
     * Sobrecarga de compatibilidad: la firma histórica (`size` + `overlap`).
     *
     * `size` es el **tope duro** del fragmento y `overlap` el tope de
     * solapamiento. La semántica es la de siempre (el tamaño no se normaliza),
     * pero el troceado es por unidades: párrafos, oraciones y palabras.
     */
    fun split(doc: Doc, size: Int, overlap: Int): List<Chunk> {
        val tope = size.coerceAtLeast(1)
        return split(
            doc,
            ChunkingStrategy(
                targetChars = tope,
                minChars = 1,
                maxChars = tope,
                overlapSentences = if (overlap > 0) 1 else 0,
                maxOverlapChars = overlap.coerceAtLeast(0),
                minOverlapChars = 0,
                shape = MaterialShape.PROSE,
            ),
        )
    }

    /**
     * Trocea un documento con la política dada.
     *
     * @param doc el documento a trocear.
     * @param strategy la política de troceado.
     */
    fun split(doc: Doc, strategy: ChunkingStrategy): List<Chunk> {
        val text = doc.text
        if (text.isBlank()) return emptyList()

        val lines = splitLines(text)
        val headings = detectHeadings(lines)
        val sections = buildSections(lines, headings)
        val markerLines = headings.filter { it.kind != HeadingKind.TITLE }.map { it.line }.toSet()
        val fallbackHeading = doc.path.substringAfterLast('/')

        val out = mutableListOf<Chunk>()
        for (section in sections) {
            val units = sectionUnits(section, lines, text, strategy, markerLines)
            if (units.isEmpty()) continue
            for (range in pack(units, strategy, text)) {
                val body = text.substring(range.first, range.last + 1).trim()
                if (body.isEmpty()) continue
                out += Chunk(doc.source, doc.path, section.heading ?: fallbackHeading, body)
            }
        }
        return out
    }

    /**
     * Índice de corte en un **límite de palabra**.
     *
     * Busca el último espacio antes de [limit]. Si ese espacio está por debajo
     * del piso (60 % de [limit]), el corte cae en [limit]: es preferible un
     * fragmento un poco más largo que uno diminuto, y un corte a mitad de
     * palabra es el último recurso.
     *
     * @param text el texto a cortar.
     * @param limit el límite de caracteres del fragmento.
     * @return el índice del carácter que corta el texto: el fragmento es
     *   `text.substring(0, corte)` y el carácter en esa posición es el espacio.
     */
    fun cutAtBoundary(text: String, limit: Int): Int {
        if (limit <= 0) return 0
        if (limit >= text.length) return text.length
        val piso = (limit * 0.6).toInt()
        var i = limit - 1
        while (i >= piso) {
            if (text[i].isWhitespace()) return i
            i--
        }
        return limit
    }

    // ------------------------------------------------------------------
    // Líneas
    // ------------------------------------------------------------------

    /** Una línea del documento con sus offsets en el texto original. */
    private data class Line(val text: String, val start: Int, val end: Int)

    private fun splitLines(text: String): List<Line> {
        val out = mutableListOf<Line>()
        var i = 0
        while (i <= text.length) {
            val nl = text.indexOf('\n', i)
            if (nl < 0) {
                if (i < text.length) out += Line(text.substring(i), i, text.length)
                break
            }
            var end = nl
            if (end > i && text[end - 1] == '\r') end--
            out += Line(text.substring(i, end), i, end)
            i = nl + 1
        }
        return out
    }

    // ------------------------------------------------------------------
    // Títulos
    // ------------------------------------------------------------------

    private enum class HeadingKind {
        /** Un título de verdad: arma la sección y aparece en la cita. */
        TITLE,

        /** "## Página 7": marca de localización, no es una sección. */
        PAGE,

        /** "## Diapositiva 12": ídem. */
        SLIDE,
    }

    private data class DetectedHeading(
        val line: Int,
        val level: Int,
        val title: String,
        val kind: HeadingKind,
        /** La numeración ("1", "1.2") cuando el título es numerado. */
        val number: String = "",
    )

    private val MARKDOWN_HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*#*$""")
    private val PAGE_MARKER = Regex(
        """^(P(?:[áa]g\.?|gina)|Diapositiva|Slide)\s*(\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val KEYWORD_HEADING = Regex(
        """^(Unidad|Tema|Cap[ií]tulo|Secci[óo]n|Apartado|Parte|Anexo|Introducci[óo]n|""" +
            """Conclusi[óo]n|Bibliograf[ií]a|Objetivos?)\s+([0-9IVXLCDM]+)?\s*([.:)]+)?\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val NUMBERED_HEADING = Regex("""^(\d+(?:\.\d+)*)[.)]?\s+(\S.*)$""")

    private fun detectHeadings(lines: List<Line>): List<DetectedHeading> {
        val inFence = fenceLines(lines)
        val repeated = repeatedLines(lines)
        val out = mutableListOf<DetectedHeading>()
        val numbered = mutableListOf<DetectedHeading>()

        for (i in lines.indices) {
            if (inFence[i]) continue
            val t = lines[i].text.trim()
            if (t.isEmpty()) continue
            if (t in repeated) continue

            val markdown = MARKDOWN_HEADING.find(t)
            if (markdown != null) {
                val title = markdown.groupValues[2].trim()
                if (title.isEmpty()) continue
                val kind = when {
                    PAGE_MARKER.containsMatchIn(title) -> HeadingKind.PAGE
                    title.lowercase().startsWith("diapositiva") || title.lowercase().startsWith("slide") ->
                        HeadingKind.SLIDE
                    else -> HeadingKind.TITLE
                }
                out += DetectedHeading(i, markdown.groupValues[1].length, title, kind)
                continue
            }

            val keyword = KEYWORD_HEADING.find(t)
            if (keyword != null) {
                val numero = keyword.groupValues[2]
                val separador = keyword.groupValues[3]
                // "Tema de la semana" no es un título: hace falta un número o
                // un separador ("Tema 3", "Tema 3: Redes neuronales").
                if (numero.isEmpty() && separador.isEmpty()) continue
                val resto = keyword.groupValues[4].trim()
                val titulo = if (resto.isEmpty()) t else resto
                out += DetectedHeading(
                    line = i,
                    level = levelForKeyword(keyword.groupValues[1].lowercase()),
                    title = titulo,
                    kind = HeadingKind.TITLE,
                )
                continue
            }

            val numero = NUMBERED_HEADING.find(t)
            if (numero != null) {
                numbered += DetectedHeading(
                    line = i,
                    level = numero.groupValues[1].count { it == '.' } + 1,
                    title = numero.groupValues[2].trim(),
                    kind = HeadingKind.TITLE,
                    number = numero.groupValues[1],
                )
                continue
            }

            if (looksLikeUppercaseTitle(t)) {
                out += DetectedHeading(i, 1, titleCase(t), HeadingKind.TITLE)
            }
        }

        out += validateNumbered(numbered, lines)
        return out.sortedBy { it.line }
    }

    /** Las líneas de un fence (incluido el que abre) no pueden ser títulos. */
    private fun fenceLines(lines: List<Line>): BooleanArray {
        val out = BooleanArray(lines.size)
        var inFence = false
        for (i in lines.indices) {
            if (lines[i].text.trimStart().startsWith("```")) {
                inFence = !inFence
                out[i] = true
                continue
            }
            out[i] = inFence
        }
        return out
    }

    /**
     * Las líneas que se repiten mucho son el membrete de un PDF, no un título.
     */
    private fun repeatedLines(lines: List<Line>): Set<String> {
        val counts = HashMap<String, Int>()
        for (line in lines) {
            val t = line.text.trim()
            if (t.isEmpty()) continue
            counts[t] = (counts[t] ?: 0) + 1
        }
        return counts.filterValues { it >= 3 }.keys
    }

    /**
     * Valida los títulos numerados.
     *
     * Tres filtros, en el orden en que se aplican:
     *  1. el número tiene que ser razonable ("2024" no es un capítulo) y el
     *     resto tiene que parecer un título ("1.5 %" no es un título);
     *  2. una lista numerada ("1. Comprá un libro", "2. Leé el libro") no es
     *     una jerarquía de secciones;
     *  3. la numeración tiene que ser **secuencial**: si el número rompe la
     *     serie, no es un título.
     */
    private fun validateNumbered(candidates: List<DetectedHeading>, lines: List<Line>): List<DetectedHeading> {
        if (candidates.isEmpty()) return emptyList()

        val valid = candidates.filter { candidate ->
            val primero = candidate.number.substringBefore('.').toIntOrNull()
            primero != null &&
                primero <= 99 &&
                candidate.title.isNotEmpty() &&
                candidate.title.length <= 90 &&
                candidate.title.first().isLetter()
        }

        val sorted = candidates.sortedBy { it.line }
        val enLista = HashSet<Int>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (
                j + 1 < sorted.size &&
                sorted[j + 1].level == sorted[i].level &&
                !hayContenidoEntre(lines, sorted[j].line, sorted[j + 1].line)
            ) {
                j++
            }
            if (j > i) for (k in i..j) enLista += sorted[k].line
            i = j + 1
        }

        val ultimoPorNivel = HashMap<Int, List<Int>>()
        val out = mutableListOf<DetectedHeading>()
        for (candidate in valid.sortedBy { it.line }) {
            if (candidate.line in enLista) continue
            val partes = candidate.number.split(".").mapNotNull { it.toIntOrNull() }
            if (partes.isEmpty()) continue
            val previo = ultimoPorNivel[candidate.level]
            if (previo != null && !esMayor(partes, previo)) continue
            ultimoPorNivel[candidate.level] = partes
            out += candidate
        }
        return out
    }

    /** ¿Hay alguna línea con contenido entre dos títulos? */
    private fun hayContenidoEntre(lines: List<Line>, desde: Int, hasta: Int): Boolean {
        for (i in desde + 1 until hasta) {
            if (lines[i].text.isNotBlank()) return true
        }
        return false
    }

    /** Compara dos numeraciones componente a componente. */
    private fun esMayor(a: List<Int>, b: List<Int>): Boolean {
        for (k in a.indices) {
            val otro = b.getOrNull(k) ?: return true
            if (a[k] != otro) return a[k] > otro
        }
        return false
    }

    private fun levelForKeyword(palabra: String): Int = when (palabra) {
        "unidad", "parte", "capitulo", "capítulo" -> 1
        "tema", "seccion", "sección" -> 2
        "apartado", "anexo" -> 3
        else -> 2
    }

    private fun looksLikeUppercaseTitle(line: String): Boolean {
        if (line.length >= 80) return false
        if (line.count { it.isLetter() } < 3) return false
        if (line.any { it.isLowerCase() }) return false
        val palabras = line.split(" ").count { it.isNotBlank() }
        // Un párrafo en mayúsculas termina en punto: no es un título.
        if (line.endsWith(".") && palabras > 4) return false
        return true
    }

    /** "TEOREMA DE MÍNIMOS CUADRADOS" → "Teorema de mínimos cuadrados". */
    private fun titleCase(line: String): String {
        val original = line.split(" ")
        val lower = line.lowercase().split(" ")
        return lower.mapIndexed { i, word ->
            when {
                word.isEmpty() -> word
                i == 0 -> word.replaceFirstChar { it.uppercaseChar() }
                word.length <= 3 &&
                    word.any { it.isLetter() } &&
                    original.getOrNull(i)?.uppercase() == original.getOrNull(i) -> word.uppercase()
                else -> word
            }
        }.joinToString(" ")
    }

    // ------------------------------------------------------------------
    // Secciones y bloques
    // ------------------------------------------------------------------

    private class Section(val heading: String?, val contentLines: List<Int>)

    private fun buildSections(lines: List<Line>, headings: List<DetectedHeading>): List<Section> {
        val titles = headings.filter { it.kind == HeadingKind.TITLE }
        val out = mutableListOf<Section>()
        var heading: String? = null
        var content = mutableListOf<Int>()
        var next = 0

        for (i in lines.indices) {
            while (next < titles.size && titles[next].line < i) next++
            if (next < titles.size && titles[next].line == i) {
                out += Section(heading, content)
                heading = titles[next].title
                content = mutableListOf()
                next++
                continue
            }
            content += i
        }
        out += Section(heading, content)
        return out
    }

    /** Un bloque de texto (párrafo) o de código. */
    private sealed interface Block {
        val start: Int
        val end: Int

        data class Text(override val start: Int, override val end: Int) : Block
        data class Code(override val start: Int, override val end: Int) : Block
    }

    private fun buildBlocks(
        lines: List<Line>,
        contentLines: List<Int>,
        markerLines: Set<Int>,
    ): List<Block> {
        val blocks = mutableListOf<Block>()
        var parrafo = mutableListOf<Int>()
        var codigo = mutableListOf<Int>()
        var inFence = false

        fun cerrarParrafo() {
            if (parrafo.isEmpty()) return
            blocks += Block.Text(lines[parrafo.first()].start, lines[parrafo.last()].end)
            parrafo = mutableListOf()
        }

        for (i in contentLines) {
            val line = lines[i]
            if (line.text.trimStart().startsWith("```")) {
                cerrarParrafo()
                if (!inFence) {
                    inFence = true
                    codigo = mutableListOf(i)
                } else {
                    codigo += i
                    blocks += Block.Code(lines[codigo.first()].start, lines[codigo.last()].end)
                    inFence = false
                    codigo = mutableListOf()
                }
                continue
            }
            if (inFence) {
                codigo += i
                continue
            }
            if (line.text.isBlank()) {
                cerrarParrafo()
                continue
            }
            if (i in markerLines) {
                // La marca de página se conserva en el cuerpo: así el fragmento
                // sigue siendo un excerpt fiel del material.
                cerrarParrafo()
                blocks += Block.Text(line.start, line.end)
                continue
            }
            parrafo += i
        }
        cerrarParrafo()
        if (inFence && codigo.isNotEmpty()) {
            blocks += Block.Code(lines[codigo.first()].start, lines[codigo.last()].end)
        }
        return blocks
    }

    // ------------------------------------------------------------------
    // Unidades
    // ------------------------------------------------------------------

    /**
     * Una unidad de troceado: un párrafo, una oración, una línea o un trozo
     * de palabra. Nunca empieza ni termina en mitad de una palabra.
     *
     * @property tails las unidades menores (oraciones) que pueden servir de
     *   cola de solapamiento.
     */
    private class Unit(val start: Int, val end: Int, val tails: List<IntRange>)

    private fun sectionUnits(
        section: Section,
        lines: List<Line>,
        text: String,
        strategy: ChunkingStrategy,
        markerLines: Set<Int>,
    ): List<Unit> {
        val blocks = buildBlocks(lines, section.contentLines, markerLines)
        val out = mutableListOf<Unit>()
        for (block in blocks) {
            when (block) {
                // Un bloque de código no se parte nunca.
                is Block.Code -> out += Unit(block.start, block.end, emptyList())
                is Block.Text -> out += splitBlock(block.start, block.end, text, strategy.maxChars)
            }
        }
        return out
    }

    /**
     * Parte un bloque de texto en unidades que entran en un fragmento.
     *
     * Un párrafo que entra entero es una unidad (el corte cae en el límite de
     * párrafo). Uno que no entra se parte por oración; una oración que sigue
     * sin entrar, por línea; una línea, por palabra.
     */
    private fun splitBlock(start: Int, end: Int, text: String, maxChars: Int): List<Unit> {
        val body = text.substring(start, end)
        if (body.length <= maxChars) {
            val sentences = sentenceRanges(body, start)
            val tails = sentences.ifEmpty { listOf(start until end) }
            return listOf(Unit(start, end, tails))
        }
        val sentences = sentenceRanges(body, start)
        if (sentences.size > 1) {
            return sentences.flatMap { sentence ->
                if (sentence.last - sentence.first + 1 <= maxChars) {
                    listOf(Unit(sentence.first, sentence.last + 1, listOf(sentence)))
                } else {
                    splitByLines(sentence, text, maxChars)
                }
            }
        }
        return splitByLines(start until end, text, maxChars)
    }

    private fun splitByLines(range: IntRange, text: String, maxChars: Int): List<Unit> {
        val out = mutableListOf<Unit>()
        var inicio = range.first
        var i = range.first
        while (i <= range.last) {
            if (i == range.last || text[i] == '\n') {
                val segment = inicio until i
                if (segment.last >= segment.first) out += unitOrWords(segment, text, maxChars)
                inicio = i + 1
            }
            i++
        }
        return out
    }

    /** Una línea que no entra se parte por palabras. */
    private fun unitOrWords(range: IntRange, text: String, maxChars: Int): List<Unit> {
        if (range.last - range.first + 1 <= maxChars) {
            return listOf(Unit(range.first, range.last + 1, listOf(range)))
        }
        val body = text.substring(range.first, range.last + 1)
        val out = mutableListOf<Unit>()
        var pos = 0
        while (pos < body.length) {
            var end = minOf(pos + maxChars, body.length)
            if (end < body.length) {
                val corte = cutAtBoundary(body.substring(pos), end - pos)
                end = pos + corte
            }
            if (end <= pos) end = minOf(pos + maxChars, body.length)
            val piece = (range.first + pos) until (range.first + end)
            out += Unit(piece.first, piece.last + 1, listOf(piece))
            pos = end
        }
        return out
    }

    // ------------------------------------------------------------------
    // Oraciones
    // ------------------------------------------------------------------

    /** Abreviaturas que no terminan una oración. */
    private val ABREVIATURAS = setOf(
        "sr", "sra", "srta", "dr", "dra", "prof", "lic", "ing", "etc", "ej",
        "pag", "pág", "cap", "fig", "num", "núm", "art", "vol", "ed", "min",
        "max", "e", "i", "vs", "ca", "aprox", "dpto", "av", "cfr",
    )

    /**
     * Las oraciones de un bloque, con sus offsets en el texto original.
     *
     * El corte exige un espacio después del signo de puntuación, así que los
     * decimales ("3.14") no se parten, y las abreviaturas ("Sr. Pérez") no
     * cortan la oración.
     */
    private fun sentenceRanges(body: String, base: Int): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var j = i + 1
                while (j < body.length && body[j] in ".,!?…\"')]»") j++
                val abreviada = isAbbreviation(body.substring(start, i))
                val seguidoDeEspacio = j >= body.length || body[j].isWhitespace()
                if (!abreviada && seguidoDeEspacio) {
                    out += start until j
                    start = j
                    i = j
                    continue
                }
            }
            i++
        }
        if (start < body.length) out += start until body.length

        return out.map { range ->
            var a = range.first
            var b = range.last
            while (a <= b && body[a].isWhitespace()) a++
            while (b >= a && body[b].isWhitespace()) b--
            a until (b + 1)
        }.filter { it.last >= it.first }
    }

    /** ¿Lo que precede al punto es una abreviatura o una inicial? */
    private fun isAbbreviation(prefix: String): Boolean {
        val trimmed = prefix.trimEnd()
        if (trimmed.isEmpty()) return false
        val last = trimmed.substringAfterLast(' ').trimEnd('.', ',', ':', ';')
        if (last.isEmpty()) return false
        if (last.length == 1 && last[0].isLetter()) return true
        return last.lowercase() in ABREVIATURAS
    }

    // ------------------------------------------------------------------
    // Empaquetado
    // ------------------------------------------------------------------

    /**
     * Empaqueta las unidades en fragmentos.
     *
     * - `targetChars`: se cierra el fragmento al llegar a él.
     * - `maxChars`: nunca se supera (salvo una unidad indivisible).
     * - El solapamiento es la cola de oración del fragmento anterior.
     */
    private fun pack(units: List<Unit>, strategy: ChunkingStrategy, text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var pendientes = mutableListOf<Unit>()
        var agregadas = mutableListOf<Unit>()

        fun cerrar() {
            if (pendientes.isEmpty()) return
            out += pendientes.first().start until pendientes.last().end
            val cola = overlapTail(agregadas, strategy, text)
            pendientes = if (cola == null) {
                mutableListOf()
            } else {
                mutableListOf(Unit(cola.first, cola.last + 1, listOf(cola)))
            }
            agregadas = mutableListOf()
        }

        for (unit in units) {
            if (pendientes.isNotEmpty()) {
                val largo = unit.end - pendientes.first().start
                if (largo > strategy.maxChars) cerrar()
            }
            pendientes += unit
            agregadas += unit
            val actual = pendientes.last().end - pendientes.first().start
            if (actual >= strategy.targetChars) cerrar()
        }
        cerrar()

        // Un fragmento chico se fusiona con el anterior de la misma sección:
        // dos fragmentos de 30 caracteres no sirven de nada.
        if (out.size >= 2) {
            val ultimo = out[out.size - 1]
            if (ultimo.last - ultimo.first + 1 < strategy.minChars) {
                val previo = out[out.size - 2]
                out[out.size - 2] = previo.first until ultimo.last
                out.removeAt(out.size - 1)
            }
        }
        return out
    }

    /**
     * La cola de solapamiento: la última oración completa del fragmento.
     *
     * Se calcula sobre las unidades **agregadas** en el fragmento (no sobre el
     * texto entero), así que el solapamiento no se encadena: el fragmento que
     * hereda la cola no la vuelve a arrastrar.
     *
     * Si la cola es más corta que el piso, se agregan las oraciones anteriores
     * hasta llegar al piso. Si es más larga que el tope, se recorta en un
     * límite de palabra.
     */
    private fun overlapTail(
        agregadas: List<Unit>,
        strategy: ChunkingStrategy,
        text: String,
    ): IntRange? {
        if (strategy.overlapSentences <= 0) return null
        val candidatas = agregadas.flatMap { it.tails }
        if (candidatas.isEmpty()) return null

        var cola = candidatas.last()
        var i = candidatas.size - 2
        while (cola.last - cola.first + 1 < strategy.minOverlapChars && i >= 0) {
            val previa = candidatas[i]
            val largo = cola.last - cola.first + 1 + (previa.last - previa.first + 1)
            if (largo > strategy.maxOverlapChars) break
            cola = previa.first until cola.last
            i--
        }

        val longitud = cola.last - cola.first + 1
        if (longitud < strategy.minOverlapChars) return null
        if (longitud > strategy.maxOverlapChars) {
            val cuerpo = text.substring(cola.first, cola.last + 1)
            val corte = cutAtBoundary(cuerpo, strategy.maxOverlapChars)
            return cola.first until (cola.first + corte)
        }
        return cola
    }
}
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        