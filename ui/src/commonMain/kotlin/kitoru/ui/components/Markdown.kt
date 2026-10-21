package kitoru.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density

/* ==========================================================================
 * Renderer markdown ligero (Compose puro, sin dependencias externas).
 *
 * Parser -> nodos; los nodos se dibujan con Text reutilizables del tema.
 * Cubre: encabezados, párrafos, listas, blockquotes, tablas, reglas,
 * bloques de código fenced (con lenguaje, ver CodeBlock) y los inline
 * (bold, italic, strike, inline-code y links).
 *
 * Además renderiza matemática (F5a): `$…$` en línea y `$$…$$` centrada, con
 * JLaTeXMath (offline). Ver MathRender.kt.
 * ========================================================================== */

sealed class MdNode {
    data class Heading(val level: Int, val text: String) : MdNode()
    data class Paragraph(val text: String) : MdNode()
    data class Bullets(val items: List<String>, val ordered: Boolean) : MdNode()
    data class Code(val lang: String, val text: String, val closed: Boolean) : MdNode()
    data class Quote(val text: String) : MdNode()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdNode()
    /** Fórmula centrada (`$$…$$`). */
    data class Math(val latex: String) : MdNode()
    object Divider : MdNode()
}

fun parseMarkdown(src: String): List<MdNode> {
    val out = ArrayList<MdNode>()
    val lines = src.split('\n')
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val t = raw.trim()
        when {
            t.startsWith("```") -> {
                val lang = t.removePrefix("```").trim().lowercase()
                val body = StringBuilder()
                var closed = false
                i++
                while (i < lines.size) {
                    if (lines[i].trimStart().startsWith("```")) { closed = true; i++; break }
                    body.append(lines[i]).append('\n'); i++
                }
                out += MdNode.Code(lang.ifBlank { "text" }, body.toString().trimEnd('\n'), closed)
            }
            t.length > 1 && t[0] == '#' && t.takeWhile { it == '#' }.let { it.length in 1..6 && (t.length == it.length || t[it.length] == ' ') } -> {
                val level = t.takeWhile { it == '#' }.length
                out += MdNode.Heading(level, t.dropWhile { it == '#' }.trim()); i++
            }
            (t == "---" || t == "***" || t == "___") -> { out += MdNode.Divider; i++ }
            t.startsWith("$$") -> {
                // Fórmula centrada: `$$x$$` en una línea o un bloque de varias.
                if (t.length > 4 && t.endsWith("$$")) {
                    out += MdNode.Math(t.removePrefix("$$").removeSuffix("$$").trim()); i++
                } else {
                    val start = i
                    val body = StringBuilder(); var closed = false; i++
                    while (i < lines.size) {
                        val l = lines[i].trim()
                        if (l.endsWith("$$") && l.length >= 2) {
                            body.append(l.removeSuffix("$$").trim()).append('\n'); i++; closed = true; break
                        }
                        body.append(lines[i]).append('\n'); i++
                    }
                    if (closed) {
                        out += MdNode.Math(body.toString().trim())
                    } else {
                        // Sin cierre: no se dibuja nada (así el streaming no muestra
                        // media fórmula), pero el texto no se pierde.
                        out += MdNode.Paragraph(lines.subList(start, i).joinToString("\n").trim())
                    }
                }
            }
            t.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().isTableSeparator() -> {
                val header = splitTableRow(t); i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) { rows += splitTableRow(lines[i].trim()); i++ }
                out += MdNode.Table(header, rows)
            }
            t.startsWith(">") -> { val body = StringBuilder(); while (i < lines.size && lines[i].trim().startsWith(">")) { body.append(lines[i].trim().removePrefix(">").trim()).append(' '); i++ }; out += MdNode.Quote(body.toString().trim()) }
            t.isListItemStart() -> { val ordered = t.first().isDigit(); val items = ArrayList<String>(); while (i < lines.size && lines[i].trim().isListItemStart()) { val l = lines[i].trim(); items += l.dropWhile { it.isDigit() || it == '.' || it == ')' || it == '-' || it == '*' || it == '+' }.removePrefix(" ").trim(); i++ }; out += MdNode.Bullets(items, ordered) }
            t.isNotEmpty() -> { val body = StringBuilder(t); i++; while (i < lines.size) { val n = lines[i].trim(); if (n.isEmpty() || n.startsWith("```") || n.startsWith("#") || n.startsWith(">") || n.startsWith("$$") || n.isListItemStart() || n.startsWith("|") || n == "---" || n == "***") break; body.append(' ').append(n); i++ }; out += MdNode.Paragraph(body.toString()) }
            else -> i++
        }
    }
    return out
}

private fun String.isListItemStart(): Boolean {
    val s = trim()
    if (s.isEmpty()) return false
    if (s.startsWith("- ") || s.startsWith("* ") || s.startsWith("+ ")) return true
    if (s.first().isDigit()) {
        val afterDot = s.dropWhile { it.isDigit() }.firstOrNull()
        return (afterDot == '.' || afterDot == ')') && s.dropWhile { it.isDigit() }.drop(1).firstOrNull() == ' '
    }
    return false
}

private fun String.isTableSeparator(): Boolean {
    val t = trim(); if (!t.startsWith("|")) return false
    for (c in t) if (c != '-' && c != '=' && c != ':' && c != '|') return false
    return true
}

private fun splitTableRow(line: String): List<String> = line.removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

internal fun tableToText(header: List<String>, rows: List<List<String>>): String = buildString { appendLine(header.joinToString(" | ")); rows.forEach { appendLine(it.joinToString(" | ")) } }

/* ---------- inline: bold / italic / strike / code / link ---------- */

fun buildInlineAnnotated(inline: String, base: SpanStyle = SpanStyle()): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var rest = inline
    val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|`([^`]+)`|\*([^*\s][^*]*)\*|\[([^\]]+)\]\(([^)]+)\)""")
    while (rest.isNotEmpty()) {
        val m = INLINE.find(rest)
        if (m == null) {
            builder.withStyle(base) { append(rest) }
            break
        }
        builder.withStyle(base) { append(rest.substring(0, m.range.first)) }
        when {
            m.groupValues[1].isNotEmpty() -> builder.withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[1]) }
            m.groupValues[2].isNotEmpty() -> builder.withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[2]) }
            m.groupValues[3].isNotEmpty() -> builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(m.groupValues[3]) }
            m.groupValues[4].isNotEmpty() -> builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(m.groupValues[4]) }
            m.groupValues[5].isNotEmpty() -> builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[5]) }
            m.groupValues[6].isNotEmpty() -> builder.withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("${m.groupValues[6]} (${m.groupValues[7]})") }
        }
        rest = rest.substring(m.range.last + 1)
    }
    return builder.toAnnotatedString()
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val nodes = remember(text) { parseMarkdown(text) }
    Column(modifier) {
        nodes.forEachIndexed { idx, node ->
            when (node) {
                is MdNode.Heading -> HeadingText(level = node.level, text = node.text)
                is MdNode.Paragraph -> ParagraphText(text = node.text)
                is MdNode.Bullets -> BulletsText(items = node.items, ordered = node.ordered)
                is MdNode.Code -> CodeBlock(lang = node.lang, code = node.text, closed = node.closed)
                is MdNode.Quote -> QuoteText(text = node.text)
                is MdNode.Table -> TableText(header = node.header, rows = node.rows)
                is MdNode.Math -> MathBlock(latex = node.latex)
                MdNode.Divider -> DividerText()
            }
            if (idx != nodes.lastIndex) Spacer(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        }
    }
}

@Composable private fun HeadingText(level: Int, text: String) {
    val size = when (level) { 1 -> 28.sp; 2 -> 24.sp; 3 -> 20.sp; 4 -> 18.sp; 5 -> 16.sp; else -> 14.sp }
    Text(text = buildInlineAnnotated(text), fontSize = size, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
}

@Composable private fun ParagraphText(text: String) {
    val segments = remember(text) { splitMath(text) }
    val hasMath = segments.any { it is MathSegment.Formula }

    if (!hasMath) {
        Text(text = buildInlineAnnotated(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        return
    }

    // Hay fórmulas: se muestran como LaTeX monoespaciado.
    //
    // Kitoru es multiplataforma (desktop + Android) y el render de fórmulas de
    // FunshiEngineAI usa `java.awt` + JLaTeXMath, que no existe en Android.
    // En vez de perder la fórmula, se muestra su código LaTeX, que es
    // copiable y legible. En el escritorio se puede activar el render real
    // más adelante (ver `kitoru.ui.platform.MathRendering`).
    val annotated = remember(text) { buildAnnotatedWithMath(segments) }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** Fórmula centrada (`$$…$$`). */
@Composable private fun MathBlock(latex: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = latex,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Arma el texto del párrafo con las fórmulas en monoespaciado.
 *
 * La fórmula conserva sus signos `$` para que se sepa que es LaTeX y para que
 * el alumno pueda copiarla tal cual.
 */
internal fun buildAnnotatedWithMath(segments: List<MathSegment>): AnnotatedString {
    val builder = AnnotatedString.Builder()
    segments.forEach { segment ->
        when (segment) {
            is MathSegment.Plain -> builder.append(buildInlineAnnotated(segment.text))
            is MathSegment.Formula -> builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                append(if (segment.display) "\$\$${segment.latex}\$\$" else "\$${segment.latex}\$")
            }
        }
    }
    return builder.toAnnotatedString()
}

@Composable private fun BulletsText(items: List<String>, ordered: Boolean) {
    Column { items.forEachIndexed { i, item -> Row(Modifier.fillMaxWidth()) { Text(text = if (ordered) "${i + 1}. " else "• ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface); Text(text = buildInlineAnnotated(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) } } }
}

@Composable private fun QuoteText(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).border(width = 2.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), shape = RectangleShape).padding(start = 12.dp)) {
        Text(text = buildInlineAnnotated(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), fontStyle = FontStyle.Italic)
    }
}

@Composable private fun TableText(header: List<String>, rows: List<List<String>>) {
    Column {
        Row(modifier = Modifier.fillMaxWidth()) { header.forEach { cell -> Text(text = buildInlineAnnotated(cell), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(8.dp).background(MaterialTheme.colorScheme.primaryContainer)) } }
        rows.forEach { row -> Row(modifier = Modifier.fillMaxWidth()) { row.forEach { cell -> Text(text = buildInlineAnnotated(cell), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(8.dp)) } } }
    }
}

@Composable private fun DividerText() { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant) }