package llm.api

import llm.config.ChatMessage
import kotlin.math.ceil

/**
 * Cantidad de tokens **con su procedencia**.
 *
 * El campo [estimated] es lo importante: un número de tokens que salió de una
 * heurística (chars/4) y uno que informó el servidor no se pueden mezclar.
 * Quien lo muestra tiene que poder decir "estimado" sin adivinar.
 */
data class TokenCount(
    /** Cantidad de tokens. */
    val value: Int,
    /** `true` si el número es una estimación, `false` si es real. */
    val estimated: Boolean,
) {
    companion object {
        /** Tokens que informou el servidor: dato duro. */
        fun real(value: Int): TokenCount = TokenCount(value, estimated = false)

        /** Tokens estimados: el servidor no mandó `usage`. */
        fun approx(value: Int): TokenCount = TokenCount(value, estimated = true)
    }
}

/**
 * Estimador portable de tokens.
 *
 * Es el **fallback**: si el servidor informó `usage` (ver [LlmChunk.Usage]),
 * se usan esos números, que son reales. Este estimador existe para los
 * servidores que no devuelven `usage` (o que rechazan `stream_options`) y
 * para la IA local del alumno, donde no hay quien cuente.
 *
 * Regla: 4 caracteres por token en texto latino (la heurística estándar de la
 * industria), con corrección para CJK (donde un carácter es ≈ 1 token) y para
 * emoji (que suelen ser 2 o 3 tokens).
 *
 * Límites, documentados a propósito:
 *  - Error típico en español/inglés técnico: **−12 % … +25 %**.
 *  - No sirve para facturar: sólo para tendencias y topes *aproximados*.
 *  - No modela el tokenizador del modelo (BPE vs SPM): dos modelos distintos
 *    con el mismo texto dan números distintos.
 *
 * Portable: no usa `java.lang.Character` ni `String.codePointAt` (no existen
 * en `commonMain`); los surrogate pairs se combinan a mano con
 * `Char.isHighSurrogate()` / `isLowSurrogate()`, que sí son de `kotlin.text`.
 */
object TokenEstimator {

    /** Tokens que agrega el formato de chat por mensaje (role + separadores). */
    const val CHAT_OVERHEAD_PER_MESSAGE = 4

    /** Tokens del prefijo de respuesta del asistente. */
    const val REPLY_OVERHEAD = 3

    /** Caracteres (code points) por token en texto latino. */
    const val CHARS_PER_TOKEN = 4.0

    /** Code points CJK por token. */
    const val CJK_CHARS_PER_TOKEN = 1.5

    /** Tokens por emoji o símbolo. */
    const val TOKENS_PER_SYMBOL = 2

    /**
     * Tokens estimados de un texto, marcados como estimados.
     *
     * Texto vacío o en blanco ⇒ 0 tokens (no hay nada que estimar).
     */
    fun estimate(text: String): TokenCount = TokenCount.approx(estimateText(text))

    /**
     * Tokens estimados de un texto, como número pelado.
     *
     * Es el cálculo puro; [estimate] es el que además deja la marca de
     * "estimado" para que no se confunda con el dato del servidor.
     */
    fun estimateText(text: String): Int {
        if (text.isBlank()) return 0
        var latin = 0
        var cjk = 0
        var symbols = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            // Code point completo: los emoji viven fuera del BMP (2 chars).
            val cp: Int
            val step: Int
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                cp = 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                step = 2
            } else {
                cp = c.code
                step = 1
            }
            i += step
            when {
                isCjk(cp) -> cjk++
                isSymbol(cp) -> symbols++
                else -> latin++
            }
        }
        val tokens = ceil(latin / CHARS_PER_TOKEN).toInt() +
            ceil(cjk / CJK_CHARS_PER_TOKEN).toInt() +
            symbols * TOKENS_PER_SYMBOL
        // Un texto no vacío siempre vale al menos un token.
        return maxOf(1, tokens)
    }

    /**
     * Tokens estimados de una conversación, con el envoltorio del chat.
     *
     * Suma el texto de cada mensaje más el sobrecosto por mensaje (el rol y
     * los separadores que agrega el chat template) y el prefijo de la
     * respuesta del asistente.
     */
    fun estimateConversation(messages: List<ChatMessage>): TokenCount {
        var total = REPLY_OVERHEAD
        for (m in messages) {
            total += estimateText(m.content) + CHAT_OVERHEAD_PER_MESSAGE
        }
        return TokenCount.approx(total)
    }

    /** ¿Es un code point CJK (kanji, kana, hangul)? */
    fun isCjk(cp: Int): Boolean = when (cp) {
        in 0x3040..0x30FF -> true // hiragana + katakana
        in 0x3400..0x4DBF -> true // CJK ext. A
        in 0x4E00..0x9FFF -> true // CJK unificado
        in 0xF900..0xFAFF -> true // compatibilidad
        in 0xAC00..0xD7AF -> true // hangul
        in 0x20000..0x2A6DF -> true // CJK ext. B
        else -> false
    }

    /** ¿Es un emoji o símbolo (cuenta más que un carácter latino)? */
    fun isSymbol(cp: Int): Boolean =
        cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF
}
