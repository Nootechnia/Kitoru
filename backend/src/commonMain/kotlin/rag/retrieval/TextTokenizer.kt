package rag.retrieval

import kitoru.platform.stripDiacritics

/**
 * Palabras vacías por idioma.
 *
 * Regla de diseño (la del diseño de calidad de recuperación): la lista
 * contiene **palabras gramaticales e interrogativas**, nunca palabras técnicas
 * por frecuentes que sean. "definición", "diferencia", "teorema", "fórmula" o
 * "ejemplo" son el contenido de la pregunta, no palabras vacías: si se sacan,
 * el material que las contiene deja de ser encontrable.
 *
 * La lista se separa por idioma (antes estaba todo mezclado en un único set,
 * lo que hacía imposible auditar qué se estaba sacando) y se compara **ya
 * normalizada**: minúsculas y sin acentos, como los tokens.
 */
object StopWords {

    /** Español: artículos, pronombres, preposiciones, conjunciones, auxiliares y cortesía. */
    val ES: Set<String> = setOf(
        // artículos
        "el", "la", "los", "las", "un", "una", "unos", "unas", "lo",
        // pronombres
        "yo", "tu", "tus", "ella", "nosotros", "vos", "ellos", "ellas",
        "usted", "ustedes", "me", "se", "te", "os", "le", "les", "mi", "mis",
        "su", "sus", "nuestro", "nuestra", "este", "esta", "esto", "estos", "estas",
        "ese", "esa", "esos", "esas", "aquel", "aquella", "aquellos", "aquellas",
        "cual", "cuales", "quien", "quienes",
        // determinantes y cuantificadores
        "no", "otra", "otro", "otras", "otros",
        "ningun", "ninguna", "ninguno", "algun", "alguna", "alguno",
        // preposiciones
        "a", "al", "de", "del", "en", "desde", "hasta", "hacia", "para", "por",
        "con", "sin", "sobre", "entre", "contra", "durante", "tras",
        "mediante", "segun", "bajo", "ante",
        // conjunciones
        "y", "e", "o", "u", "ni", "pero", "que", "porque", "aunque", "si", "sino",
        "como", "cuando", "donde", "mientras", "entonces", "luego",
        "ademas", "tambien", "tampoco", "aun", "ya",
        // auxiliares y verbos de apoyo
        "es", "son", "era", "eran", "fue", "fueron", "sea", "sean", "ser",
        "he", "has", "ha", "han", "hemos", "hay",
        "puede", "pueden", "pudo", "debe", "deben", "deberia",
        "tiene", "tienen", "tengo",
        // adverbios y cuantificadores
        "muy", "mas", "menos", "todo", "toda", "todos", "todas", "cada",
        "algo", "nada", "solo", "unicamente", "solamente",
        "asi", "aqui", "ahi", "alla",
        "ahora", "antes", "despues", "todavia",
        "bien", "mal", "mejor", "peor", "mucho", "poco", "bastante",
        "demasiado", "tan", "tanto", "tanta", "tantos", "tantas",
        "cuanto", "cuantos", "cuantas",
        // cortesía y formas coloquiales del alumno
        "gracias", "hola", "favor", "ayuda", "ayudan",
        "podrias", "podes", "puedes", "podria", "quisiera", "queria", "querrias",
        "necesito", "explicame", "explicas", "decime", "dime",
        "repasame", "repasa", "contame", "enserame",
        "consulta", "pregunta", "duda", "ayudame", "sabes",
        // verbos vacíos de intención
        "sirve", "sirven", "significa", "significado", "quiere", "decir",
        "hagamos", "mirar", "ver", "pasa",
    )

    /** Inglés: el material de una cátedra puede ser bilingüe. */
    val EN: Set<String> = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "by",
        "at", "from", "is", "are", "was", "were", "be", "been", "being", "it",
        "its", "this", "that", "these", "those", "how", "what", "when", "where",
        "which", "who", "why", "do", "does", "did", "can", "could", "would",
        "should", "will", "shall", "may", "might", "must", "there", "their",
        "they", "them", "we", "you", "i", "he", "she", "his", "her", "as", "if",
        "but", "not", "no", "so", "than", "then", "into", "about", "have", "has",
    )

    /** Todas: la política por defecto. */
    val ALL: Set<String> = ES + EN
}

/**
 * Política de tokenización.
 *
 * @property stopWords palabras vacías a descartar.
 * @property stemming si se aplica el [SpanishStemmer].
 * @property minLength longitud mínima de un token (1 = no se filtra nada).
 */
data class TokenPolicy(
    val stopWords: Set<String> = StopWords.ALL,
    val stemming: Boolean = true,
    val minLength: Int = 2,
) {
    companion object {
        /** Política por defecto de Kitoru: stop words en español e inglés, con stemmer. */
        val DEFAULT = TokenPolicy()

        /** Las 32 palabras vacías de la tokenización original (19 en español, 13 en inglés). */
        val LEGACY_STOP_WORDS: Set<String> = setOf(
            "el", "la", "los", "las", "de", "del", "un", "una", "y", "o", "en", "que", "por", "para", "con", "es", "se",
            "como", "cual", "the", "an", "and", "or", "of", "to", "in", "is", "for", "on", "with", "how", "what",
        )

        /**
         * La política histórica (FunshiEngineAI): 32 palabras vacías y sin stemming.
         *
         * Es la que usa el índice por defecto: cambiar la tokenización cambia
         * los scores, y los scores son el contrato de la recuperación.
         */
        val LEGACY = TokenPolicy(stopWords = LEGACY_STOP_WORDS, stemming = false, minLength = 2)
    }
}

/**
 * Stemmer ligero para español.
 *
 * No es Snowball ni Lucene: son reglas de sufijo en tres etapas más un ajuste
 * final, sin dependencias externas, suficientes para casar plurales y formas
 * verbales en material técnico.
 *
 * Reglas:
 *  1. Etapas en orden fijo: plural → verbo → derivación → vocal final.
 *     El orden importa: "formulas" tiene que converger con "formula".
 *  2. La raíz no puede quedar con menos de [MIN_STEM] caracteres.
 *  3. Hay una lista de excepciones y otra de plurales irregulares.
 */
object SpanishStemmer {

    /** Longitud mínima de la raíz. */
    const val MIN_STEM = 4

    /**
     * Palabras cuyo singular termina en "-s": a estas el plural no se les puede
     * quitar mecánicamente ("ingles" → "ingle" sería incorrecto).
     */
    private val EXCEPTIONS: Set<String> = setOf("ingles", "atlas")

    /** Plurales irregulares que las reglas no resuelven. */
    private val IRREGULAR: Map<String, String> = mapOf(
        "indices" to "indice",
        "vertices" to "vertice",
        "apices" to "apice",
    )

    /** Etapa 1 — plural nominal: "-ciones" → "-cion", "-iones" → "-ion". */
    private val NOMINAL: List<Pair<String, String>> = listOf(
        "ciones" to "cion", "iones" to "ion", "uciones" to "ucion",
    )

    /** Etapa 2 — terminaciones verbales. */
    private val VERB: List<Pair<String, String>> = listOf(
        "aremos" to "", "eremos" to "", "iremos" to "",
        "amos" to "", "emos" to "", "imos" to "",
        "aron" to "", "ieron" to "",
        "ando" to "", "iendo" to "",
        "ado" to "", "ada" to "", "ido" to "", "ida" to "",
        "ar" to "", "er" to "", "ir" to "",
    )

    /** Etapa 3 — derivación nominal y adverbios. */
    private val DERIVATIONAL: List<Pair<String, String>> = listOf(
        "amientos" to "", "amiento" to "",
        "imientos" to "", "imiento" to "",
        "amente" to "", "mente" to "",
    )

    /**
     * Terminaciones verbales en "-s": no se les quita la "s" del plural
     * ("calculamos" no es "calculamo").
     */
    private val VERBAL_PLURAL: List<String> = listOf("amos", "emos")

    private const val VOWELS = "aeiou"

    /** Raíz de una palabra (ya normalizada: minúsculas y sin acentos). */
    fun stem(word: String): String {
        if (word.length < MIN_STEM) return word
        if (word in EXCEPTIONS) return word
        var w = IRREGULAR[word] ?: word

        w = dePluralizar(w)
        w = applyStage(w, VERB)
        w = applyStage(w, DERIVATIONAL)

        // Vocal final tras consonante: "responsable" → "responsabl",
        // "arbole" → "arbol". Es lo que hace converger singular y plural.
        for (vocal in listOf('e', 'o')) {
            if (w.length >= 5 && w.endsWith(vocal) && w[w.length - 2] !in VOWELS) {
                w = w.dropLast(1)
            }
        }
        return w
    }

    /** Etapa 1 — el plural se reduce a su singular. */
    private fun dePluralizar(word: String): String {
        for ((suffix, replacement) in NOMINAL) {
            if (!word.endsWith(suffix)) continue
            val base = word.dropLast(suffix.length) + replacement
            if (base.length >= MIN_STEM) return base
        }
        // "-ces" → "-z": "matrices" → "matriz".
        if (word.endsWith("ces")) {
            val base = word.dropLast(3) + "z"
            if (base.length >= MIN_STEM) return base
        }
        // "-s": "arboles" → "arbole" → (ajuste final) → "arbol".
        if (word.endsWith("s") && VERBAL_PLURAL.none { word.endsWith(it) }) {
            val base = word.dropLast(1)
            if (base.length >= MIN_STEM) return base
        }
        return word
    }

    /** Aplica las reglas de una etapa: la primera que encaja, y sólo esa. */
    private fun applyStage(word: String, rules: List<Pair<String, String>>): String {
        for ((suffix, replacement) in rules) {
            if (!word.endsWith(suffix)) continue
            val base = word.dropLast(suffix.length) + replacement
            if (base.length < MIN_STEM) continue
            return base
        }
        return word
    }

    /** Aplica el stemmer a una lista de tokens. */
    fun stemAll(tokens: List<String>): List<String> = tokens.map { stem(it) }
}

/**
 * Tokenizador del RAG.
 *
 * El texto se normaliza (camel case partido, minúsculas, sin acentos), se
 * parte por lo que no es alfanumérico, se descartan las palabras vacías y, si
 * la política lo pide, se aplica el [SpanishStemmer].
 *
 * La normalización es **simétrica**: la misma función se usa al indexar y al
 * consultar, así que un plural en la pregunta casa con su singular en el
 * material.
 */
object TextTokenizer {

    private val CAMEL = Regex("([a-z0-9])([A-Z])")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /**
     * Tokeniza [text] con la política dada.
     *
     * @param text el texto a tokenizar.
     * @param policy qué palabras vacías se descartan y si se aplica el stemmer.
     */
    fun tokenize(text: String, policy: TokenPolicy = TokenPolicy.DEFAULT): List<String> {
        val spaced = text.replace(CAMEL, "$1 $2").lowercase()
        val norm = stripDiacritics(spaced)
        val tokens = norm.split(NON_ALNUM).filter { it.length >= policy.minLength }
        val filtered = if (policy.stopWords.isEmpty()) tokens else tokens.filter { it !in policy.stopWords }
        return if (policy.stemming) filtered.map { SpanishStemmer.stem(it) } else filtered
    }
}
