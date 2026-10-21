package kitoru.ui.components

/**
 * Un tramo de un párrafo: texto normal o fórmula.
 *
 * [MathSegment.Formula.id] identifica la fórmula dentro del párrafo y
 * [MathSegment.Formula.display] dice si es centrada (`$$…$$`) o en línea
 * (`$…$`).
 */
sealed class MathSegment {
    /** Texto normal. */
    data class Plain(val text: String) : MathSegment()

    /** Una fórmula (LaTeX). */
    data class Formula(
        val id: String,
        val latex: String,
        val display: Boolean,
    ) : MathSegment()
}

/**
 * Parte un texto en tramos de texto y de fórmula.
 *
 * Reglas:
 * - `$$…$$` es una fórmula **centrada**; `$…$` es una fórmula **en línea**.
 * - Una fórmula sin cerrar se deja como texto (importante con streaming: el
 *   modelo escribe `$x` y recién después el `$$` final).
 * - Los signos `$` de precios (`$5 … $10`) no se toman como fórmula: el cierre
 *   no puede estar seguido de un dígito.
 * - El código en línea (`…`) se copia tal cual: ahí no hay matemática.
 *
 * Es la parte portable del render de fórmulas de FunshiEngineAI: el render
 * real (JLaTeXMath + AWT) no existe en Android, así que Kitoru muestra la
 * fórmula como LaTeX monoespaciado.
 */
fun splitMath(src: String): List<MathSegment> {
    val out = ArrayList<MathSegment>()
    val plain = StringBuilder()
    var index = 0

    fun flush() {
        if (plain.isNotEmpty()) {
            out += MathSegment.Plain(plain.toString())
            plain.clear()
        }
    }

    while (index < src.length) {
        val c = src[index]

        // Código en línea: se copia entero.
        if (c == '`') {
            val end = src.indexOf('`', index + 1)
            if (end > index) {
                plain.append(src, index, end + 1)
                index = end + 1
                continue
            }
        }

        // Fórmula centrada: $$…$$
        if (c == '$' && index + 1 < src.length && src[index + 1] == '$') {
            val end = src.indexOf("$$", index + 2)
            if (end > index + 2) {
                flush()
                out += MathSegment.Formula(
                    id = "math${out.size}",
                    latex = src.substring(index + 2, end).trim(),
                    display = true,
                )
                index = end + 2
                continue
            }
        }

        // Fórmula en línea: $…$
        if (c == '$') {
            val end = findInlineMathEnd(src, index + 1)
            if (end > index) {
                flush()
                out += MathSegment.Formula(
                    id = "math${out.size}",
                    latex = src.substring(index + 1, end).trim(),
                    display = false,
                )
                index = end + 1
                continue
            }
        }

        plain.append(c)
        index++
    }
    flush()
    return out
}

/**
 * Busca el `$` que cierra una fórmula en línea que empieza en [from].
 * Devuelve -1 si no hay cierre válido.
 */
private fun findInlineMathEnd(src: String, from: Int): Int {
    var i = from
    while (i < src.length) {
        val c = src[i]
        if (c == '\n') return -1                       // la fórmula no corta de línea
        if (c == '\\') { i += 2; continue }           // carácter escapado
        if (c == '$') {
            if (i == from) return -1                  // "$$" no es inline
            if (src[i - 1].isWhitespace()) return -1   // "$ x $" no es fórmula
            val next = src.getOrNull(i + 1)
            if (next != null && next.isDigit()) return -1  // "$5 … $10" son precios
            return i
        }
        i++
    }
    return -1
}
