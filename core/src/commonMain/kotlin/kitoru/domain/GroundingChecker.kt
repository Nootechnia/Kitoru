package kitoru.domain

import kitoru.platform.stripDiacritics
import kotlinx.serialization.Serializable

/**
 * Estado de una afirmación del alumno frente al material de la cátedra.
 */
@Serializable
enum class GroundingStatus(val label: String) {
    /** La afirmación se apoya en el material. */
    SUPPORTED("Respaldada"),

    /** La afirmación no se apoya en el material. */
    UNSUPPORTED("Sin respaldo"),

    /** No se puede evaluar (no hay material, o la afirmación no dice nada). */
    UNKNOWN("No evaluable"),
    ;

    companion object {
        fun fromName(name: String): GroundingStatus? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Una afirmación de la entrega y su veredicto de fundamentación.
 *
 * [excerpt] es el texto del alumno: la capa que lo muestra al docente tiene
 * que vaciarlo si la visibilidad de la inscripción no le da acceso al texto
 * (ver `Enrollment.visibility`).
 */
@Serializable
data class GroundingClaim(
    /** Índice de la afirmación dentro del texto de la entrega. */
    val index: Int = 0,
    /** Texto de la afirmación (vacío si no se puede mostrar). */
    val excerpt: String = "",
    /** Veredicto. */
    val status: GroundingStatus = GroundingStatus.UNKNOWN,
    /** Por qué tiene ese veredicto. */
    val reason: String = "",
    /** Índice del fragmento de material que la respalda (-1 = ninguno). */
    val sourceIndex: Int = -1,
)

/**
 * Resultado de medir la fundamentación de una entrega.
 *
 * Las tres cuentas son disjuntas y suman [total]:
 *  - [supported]: afirmaciones que se apoyan en el material,
 *  - [unsupported]: afirmaciones que no se apoyan (dicen otra cosa, o
 *    inventan nombres propios y números),
 *  - [unknown]: afirmaciones que no se pueden evaluar (no hay material de
 *    referencia, o la afirmación es tan corta que no dice nada).
 *
 * [rate] es la proporción de afirmaciones respaldadas (0..1) y se calcula
 * siempre con la factory [of], para que el número no se desincronice de las
 * cuentas.
 */
@Serializable
data class GroundingReport(
    /** Afirmaciones respaldadas por el material. */
    val supported: Int = 0,
    /** Afirmaciones sin respaldo. */
    val unsupported: Int = 0,
    /** Afirmaciones que no se pudieron evaluar. */
    val unknown: Int = 0,
    /** Total de afirmaciones analizadas. */
    val total: Int = 0,
    /** Proporción de afirmaciones respaldadas (0..1). */
    val rate: Double = 0.0,
) {
    companion object {
        /** Arma el informe calculando el total y la proporción. */
        fun of(supported: Int, unsupported: Int, unknown: Int): GroundingReport {
            val total = supported + unsupported + unknown
            val rate = if (total == 0) 0.0 else supported.toDouble() / total
            return GroundingReport(
                supported = supported,
                unsupported = unsupported,
                unknown = unknown,
                total = total,
                rate = rate,
            )
        }
    }
}

/**
 * Análisis detallado de la fundamentación: las afirmaciones y su veredicto.
 */
@Serializable
data class GroundingAnalysis(
    /** Afirmación por afirmación. */
    val claims: List<GroundingClaim> = emptyList(),
    /** Los números del análisis. */
    val report: GroundingReport = GroundingReport(),
)

/**
 * Verificador de fundamentación de una entrega contra el material de la
 * cátedra.
 *
 * Responde una pregunta que el modelo no puede responder de forma confiable:
 * *¿el alumno escribió algo que el material de la cátedra respalda?* La
 * respuesta es determinista: la misma entrada da siempre el mismo informe,
 * sin modelo y sin red (regla 3 del proyecto).
 *
 * El criterio es **el mismo** del verificador de citas
 * (`rag.engine.CitationVerifier`), con las tres reglas:
 *  1. **Literal**: la afirmación aparece tal cual en el material (tras
 *     normalizar: minúsculas, sin acentos, sin puntuación).
 *  2. **Sin detalles inventados**: los términos clave de la afirmación
 *     (nombres propios y números de dos dígitos o más) tienen que aparecer
 *     en el material. Es lo que separa una paráfrasis de una invención: si el
 *     alumno escribió "usa DirectX 12" y el material dice "usa Vulkan", la
 *     afirmación no está respaldada aunque las frases se parezcan.
 *  3. **Paráfrasis**: al menos el [MIN_WORD_COVERAGE] (50 %) de las palabras
 *     significativas de la afirmación aparecen en el material. Alcanza para
 *     una paráfrasis honesta; no alcanza si el alumno dijo otra cosa.
 *
 * Por qué está **duplicado** acá y no se reutiliza el del backend: `:core`
 * no puede depender de `:backend` (la dependencia va al revés: `:backend`
 * depende de `:core`), y el dominio de `:core` es la capa que la UI consume.
 * La unificación correcta es mover este núcleo a `:core` (por ejemplo a
 * `kitoru.domain.text` o a `kitoru.platform`) y hacer que
 * `rag.engine.CitationVerifier` lo delegue, dejando en `:backend` sólo la
 * parte que habla de citas (`[1]`, `[2]`) y de fragmentos del RAG. Mientras
 * eso no pase, esta copia es intencional y las dos tienen que evolucionar
 * juntas.
 */
object GroundingChecker {

    /** Porcentaje de palabras significativas que deben aparecer en el material. */
    const val MIN_WORD_COVERAGE: Double = 0.5

    /** Largo mínimo (en caracteres) de una palabra para que cuente. */
    private const val MIN_WORD_LENGTH: Int = 4

    /** Prefijo común mínimo para confundir dos palabras como la misma. */
    private const val MIN_COMMON_PREFIX: Int = 5

    /** Diferencia de largo tolerada al comparar dos palabras parecidas. */
    private const val MAX_LENGTH_DIFF: Int = 3

    private val CAMEL = Regex("([a-z0-9])([A-Z])")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val NUMBER = Regex("[0-9]+([.,][0-9]+)*")

    /**
     * Palabras funcionales que aparecen capitalizadas al empezar una frase.
     * No son nombres propios: no cuentan como término clave.
     */
    private val CAPITALIZED_COMMON = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas", "este", "esta", "esto",
        "estos", "estas", "ese", "esa", "eso", "aquel", "aquella", "es", "son", "fue",
        "en", "para", "con", "sin", "sobre", "entre", "desde", "hasta", "hacia", "por",
        "que", "quien", "cual", "cuando", "donde", "como", "si", "y", "o", "u", "pero",
        "se", "su", "sus", "tu", "tus", "mi", "mis", "al", "de", "del", "the", "a",
        "an", "this", "that", "these", "those", "it", "its", "in", "on", "for", "with",
        "without", "to", "of", "and", "or", "but", "if", "is", "are", "was", "were",
        "there", "their", "his", "her", "as", "at", "by", "from", "we", "you", "i",
    )

    /** Palabras vacías: no cuentan para medir el respaldo. */
    private val STOP = setOf(
        "este", "esta", "esto", "estos", "estas", "todo", "toda", "todos", "todas",
        "cada", "como", "cuando", "donde", "porque", "para", "pero", "tambien",
        "sobre", "entre", "desde", "hasta", "hacia", "mediante", "segun", "donde",
        "the", "this", "that", "these", "those", "there", "their", "which", "when",
        "where", "with", "from", "into", "than", "then", "they", "them", "been",
    )

    // -----------------------------------------------------------------------
    // API
    // -----------------------------------------------------------------------

    /**
     * Mide la fundamentación de [text] contra el material.
     *
     * @param text texto de la entrega (Markdown).
     * @param material fragmentos de material de la cátedra (el RAG devuelve
     *   varios; da igual el orden).
     * @return los números de afirmaciones respaldadas, sin respaldo y no
     *   evaluables.
     */
    fun ground(text: String, material: List<String>): GroundingReport =
        analyze(text, material).report

    /** Igual que [ground], con el material en un solo texto. */
    fun ground(text: String, material: String): GroundingReport =
        ground(text, listOf(material))

    /**
     * Igual que [ground], pero además devuelve el veredicto de cada
     * afirmación (para que el docente pueda ver *cuál* no tiene respaldo).
     */
    fun analyze(text: String, material: List<String>): GroundingAnalysis {
        val claims = splitClaims(text)
        if (claims.isEmpty()) {
            return GroundingAnalysis(claims = emptyList(), report = GroundingReport.of(0, 0, 0))
        }

        val fragments = material.map { it.trim() }.filter { it.isNotEmpty() }
        val veredictos = claims.map { claim -> judge(claim, fragments) }

        val report = GroundingReport.of(
            supported = veredictos.count { it.status == GroundingStatus.SUPPORTED },
            unsupported = veredictos.count { it.status == GroundingStatus.UNSUPPORTED },
            unknown = veredictos.count { it.status == GroundingStatus.UNKNOWN },
        )
        return GroundingAnalysis(
            claims = veredictos.mapIndexed { index, veredicto ->
                GroundingClaim(
                    index = index,
                    excerpt = claims[index],
                    status = veredicto.status,
                    reason = veredicto.reason,
                    sourceIndex = veredicto.sourceIndex,
                )
            },
            report = report,
        )
    }

    // -----------------------------------------------------------------------
    // Núcleo
    // -----------------------------------------------------------------------

    /** Veredicto de una afirmación contra el material. */
    private data class Veredicto(
        val status: GroundingStatus,
        val reason: String,
        val sourceIndex: Int = -1,
        val coverage: Double = 0.0,
    )

    /**
     * Juzga una afirmación contra todos los fragmentos: si **algún**
     * fragmento la respalda, está respaldada.
     */
    private fun judge(claim: String, fragments: List<String>): Veredicto {
        val normClaim = normalize(claim)
        if (normClaim.isBlank()) {
            return Veredicto(GroundingStatus.UNKNOWN, "la afirmación está vacía")
        }

        if (fragments.isEmpty()) {
            return Veredicto(
                GroundingStatus.UNKNOWN,
                "no hay material de la cátedra con el cual comparar la afirmación",
            )
        }

        val words = significantWords(normClaim)

        var mejor: Veredicto? = null
        fragments.forEachIndexed { index, fragment ->
            val veredicto = judgeAgainst(claim, normClaim, words, fragment, index)
            val actual = mejor
            if (actual == null || mejorScore(veredicto) > mejorScore(actual)) {
                mejor = veredicto
            }
        }
        return mejor ?: Veredicto(GroundingStatus.UNKNOWN, "no se pudo evaluar la afirmación")
    }

    /** Un veredicto respaldado vale más que uno con mayor cobertura. */
    private fun mejorScore(v: Veredicto): Double =
        if (v.status == GroundingStatus.SUPPORTED) 2.0 + v.coverage else v.coverage

    /** Aplica las tres reglas del verificador de citas contra un fragmento. */
    private fun judgeAgainst(
        claim: String,
        normClaim: String,
        words: List<String>,
        fragment: String,
        index: Int,
    ): Veredicto {
        val normFragment = normalize(fragment)
        if (normFragment.isBlank()) {
            return Veredicto(GroundingStatus.UNKNOWN, "el fragmento de material está vacío", index)
        }

        // 1) Literal: la afirmación aparece tal cual.
        if (normFragment.contains(normClaim)) {
            return Veredicto(GroundingStatus.SUPPORTED, "la afirmación aparece en el material", index, 1.0)
        }

        // Una afirmación sin palabras significativas ("Sí.", "Gracias.") no
        // dice nada: no se puede respaldar ni desmentir.
        if (words.isEmpty()) {
            return Veredicto(
                GroundingStatus.UNKNOWN,
                "la afirmación no tiene contenido suficiente para verificarla",
                index,
            )
        }

        val fragmentWords = normFragment.split(' ').toSet()

        // 2) Los detalles concretos tienen que estar en el material: un nombre
        //    propio o un número que no aparece es un detalle inventado.
        val inventados = keyTerms(claim).filter { !normFragment.contains(it.text) }
        if (inventados.isNotEmpty()) {
            val lista = inventados.joinToString(", ") { "\"${it.raw}\"" }
            return Veredicto(
                GroundingStatus.UNSUPPORTED,
                "la afirmación menciona $lista, que no aparece en el material",
                index,
            )
        }

        // 3) Paráfrasis: suficientes palabras significativas en común.
        val covered = words.count { coveredBy(it, fragmentWords) }
        val coverage = covered.toDouble() / words.size
        if (coverage >= MIN_WORD_COVERAGE) {
            return Veredicto(
                GroundingStatus.SUPPORTED,
                "la afirmación es una paráfrasis del material (${(coverage * 100).toInt()} % de coincidencia)",
                index,
                coverage,
            )
        }

        val percent = (coverage * 100).toInt()
        return Veredicto(
            GroundingStatus.UNSUPPORTED,
            "la afirmación no aparece en el material (sólo el $percent % de sus palabras)",
            index,
            coverage,
        )
    }

    /**
     * Parte el texto de la entrega en afirmaciones.
     *
     * Se corta por punto, signo de exclamación, interrogación, punto y coma y
     * salto de línea, sin partir los decimales ("3.14" es un número, no el fin
     * de una frase).
     */
    private fun splitClaims(text: String): List<String> {
        val claims = mutableListOf<String>()
        val actual = StringBuilder()
        for ((i, c) in text.withIndex()) {
            actual.append(c)
            val corta = c == '.' || c == '!' || c == '?' || c == ';' || c == '\n'
            if (!corta) continue
            // Un punto seguido de un dígito es parte de un decimal.
            if (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) continue
            val claim = actual.toString().trim()
            if (claim.isNotEmpty()) claims += claim
            actual.clear()
        }
        val ultima = actual.toString().trim()
        if (ultima.isNotEmpty()) claims += ultima
        return claims
    }

    /**
     * Normaliza para comparar: minúsculas, sin acentos, sin puntuación.
     *
     * Es la misma normalización del verificador de citas: sin ella,
     * "Definición" y "definicion" serían palabras distintas.
     */
    internal fun normalize(text: String): String {
        val spaced = text.replace(CAMEL, "$1 $2").lowercase()
        val norm = stripDiacritics(spaced)
        return norm.replace(NON_ALNUM, " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    /** Palabras que cuentan para medir el respaldo (largas y no vacías). */
    internal fun significantWords(normalized: String): List<String> =
        normalized.split(' ')
            .filter { it.length >= MIN_WORD_LENGTH && it !in STOP }
            .distinct()

    /**
     * ¿La palabra [word] de la respuesta aparece en el material?
     *
     * Cuenta la coincidencia exacta y también la de raíz común:
     * "renderiza" y "renderizar" son la misma palabra escrita de otra forma.
     * Se exige un prefijo de 5 letras y una diferencia de largo de 3 o menos,
     * para no confundir palabras distintas.
     */
    private fun coveredBy(word: String, materialWords: Set<String>): Boolean {
        if (word in materialWords) return true
        return materialWords.any { other ->
            val common = commonPrefix(word, other)
            common >= MIN_COMMON_PREFIX && kotlin.math.abs(word.length - other.length) <= MAX_LENGTH_DIFF
        }
    }

    /** Largo del prefijo común entre dos palabras. */
    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    /**
     * Términos clave de la afirmación: los detalles concretos que, si no
     * están en el material, son una invención.
     *
     * Son dos clases:
     *  - **Nombres propios**: palabras que empiezan en mayúscula y no son
     *    palabras funcionales ("Vulkan", "OpenGL", "Windows"). La primera
     *    palabra de la afirmación no cuenta (está capitalizada por el inicio,
     *    no por ser un nombre) ni las funcionales ("El", "También", "The").
     *  - **Números con forma de versión o cantidad**: dos dígitos o más, o con
     *    punto decimal ("12", "1.0", "2024"). Un dígito solo ("sección 3")
     *    no cuenta: suele ser una referencia interna.
     */
    internal fun keyTerms(claim: String): List<GroundingKeyTerm> {
        val palabras = claim
            .split(' ', '\n', '\t', ',', ';', ':', '(', ')', '"', '\'', '¿', '?', '!', '¡')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val out = mutableListOf<GroundingKeyTerm>()
        for ((i, limpia) in palabras.withIndex()) {
            if (NUMBER.matches(limpia)) {
                if (limpia.count { it.isDigit() } >= 2 || limpia.contains('.')) {
                    out += GroundingKeyTerm(normalize(limpia), limpia, isNumber = true)
                }
                continue
            }
            if (!limpia.first().isUpperCase()) continue
            // La primera palabra de la afirmación está capitalizada por el
            // inicio: capitalización sola no alcanza para sospechar un nombre.
            if (i == 0) continue
            val norm = normalize(limpia)
            if (norm in CAPITALIZED_COMMON) continue
            out += GroundingKeyTerm(norm, limpia, isNumber = false)
        }
        return out.filter { it.text.isNotBlank() }.distinctBy { it.text }
    }
}

/**
 * Detalle concreto de una afirmación: un nombre propio o un número.
 * Si no aparece en el material, la afirmación es una invención.
 */
@Serializable
data class GroundingKeyTerm(
    /** El término normalizado (para comparar). */
    val text: String = "",
    /** El término como lo escribió el alumno (para mostrarlo). */
    val raw: String = "",
    /** ¿Es un número? (los números se buscan como substring, no como palabra). */
    val isNumber: Boolean = false,
)
