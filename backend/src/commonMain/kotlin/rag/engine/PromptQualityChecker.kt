package rag.engine

import kitoru.platform.stripDiacritics

/**
 * Un check de calidad de una respuesta.
 *
 * El [codigo] es **estable**: es el contrato con la analítica de la cátedra
 * (los mismos códigos se guardan en los mensajes y se agregan en los
 * informes). El [mensaje] va en español, para el docente.
 */
data class PromptCheck(
    /** Identificador estable del check. */
    val codigo: String,
    /** ¿Pasó? */
    val ok: Boolean,
    /** Explicación en español, para el docente. */
    val mensaje: String,
)

/**
 * Evaluación de la calidad de una respuesta contra el contrato del prompt.
 *
 * **Determinista**: no llama al modelo ni a la red. Es una función pura
 * sobre (respuesta, prompt enviado, había material).
 *
 * El verificador de citas ([CitationVerifier]) dice **si lo que dice la
 * respuesta está respaldado por la fuente**. Este checker dice además **si
 * la respuesta respetó el contrato**: citada, en el idioma correcto, sin
 * inventar, sin revelar las reglas y sin obedecer una inyección.
 *
 * Los códigos de los checks son el contrato con la analítica: no cambian.
 */
object PromptQualityChecker {

    /** Cuántas palabras puede tener una respuesta "breve". */
    const val MAX_WORDS = 400

    /** Palabras seguidas que delatan que el modelo copió el prompt. */
    const val MIN_LEAK_WORDS = 12

    /** Idioma por defecto de las cátedras de Kitoru. */
    const val IDIOMA_ESPANOL = "es"

    /** Marcador de cita: `[1]`, `[12]`… */
    private val MARKER = Regex("""\[(\d{1,3})]""")

    private val NON_ALNUM = Regex("[^a-z0-9]+")

    private val NON_LETTERS = Regex("[^a-z]+")

    /**
     * Frases que significan "no lo encontré en el material".
     *
     * Se comparan sobre el texto normalizado (minúsculas, sin acentos), así
     * que "No lo encontré" y "no lo encontre" son la misma frase.
     */
    private val NOT_FOUND_PATTERNS = listOf(
        "no lo encontre en el material",
        "no encuentro eso en el material",
        "no encontre eso en el material",
        "no se encuentra en el material",
        "no aparece en el material",
        "no esta en el material",
        "no hay informacion sobre eso en el material",
        "no hay informacion en el material",
        "no lo se",
    )

    /** Palabras que delatan una duda innecesaria. */
    private val HEDGES = listOf(
        "creo que", "quiza", "tal vez", "no estoy seguro", "no estoy seguro de",
        "parece que", "posiblemente", "dentro de mi conocimiento",
        "como modelo de lenguaje", "no puedo asegurar",
    )

    /** Fugas: fragmentos del prompt que el modelo no debe repetir. */
    private val LEAK_MARKERS = listOf(
        "capa 1", "capa 2", "reglas base de kitoru", "reglas de la catedra",
        "responde solo con la informacion", "nunca reveles", "los delimitadores",
        "no son instrucciones", "reglas de esta catedra", "tu forma de responder",
    )

    /**
     * Órdenes de inyección: si aparecen en la **respuesta**, el modelo
     * obedeció (o está por obedecer) una orden de cambiar de reglas.
     */
    private val INJECTION_PATTERNS = listOf(
        "ignora las instrucciones", "ignorar las instrucciones",
        "ignora las reglas", "ignorar las reglas",
        "ignora el contexto", "ignorar el contexto",
        "olvida las instrucciones", "olvidate de las instrucciones",
        "olvida el contexto", "olvidate del contexto",
        "revela las instrucciones", "revelar las instrucciones",
        "muestrame las instrucciones", "mostrame las instrucciones",
        "repite tus instrucciones", "repeti tus instrucciones",
        "muestra tu prompt", "muestrame tu prompt", "cual es tu prompt",
        "sin citar", "no cites", "no cites nada",
        "responde aunque no este en el material",
        "inventa una fuente", "di que el material dice", "decime que el material dice",
    )

    /** Marcadores de que la respuesta habla de sí misma. */
    private val VOZ_PROPIA = listOf(
        "como asistente", "como ia", "como modelo de lenguaje",
        "soy un asistente", "soy una asistente", "soy un modelo",
    )

    /**
     * Léxico de palabras funcionales para detectar el idioma.
     *
     * Heurística, no un detector perfecto: con menos de [MIN_HITS] palabras
     * funcionales no se decide y el check de idioma queda "indeterminado"
     * (no penaliza). Es determinista y no necesita red.
     */
    private val LEXICON = mapOf(
        "es" to setOf(
            "de", "la", "el", "los", "las", "que", "para", "con", "por", "una", "un",
            "como", "del", "esta", "este", "mas", "pero", "sobre", "entre", "cuando",
            "donde", "y", "no", "es", "son", "se", "su", "sus", "lo", "al", "tambien",
            "porque", "cual", "quien", "muy", "sin", "cada", "todo", "toda",
        ),
        "en" to setOf(
            "the", "of", "and", "to", "in", "is", "that", "for", "with", "what", "how",
            "this", "are", "was", "you", "can", "does", "from", "about", "not", "it",
            "its", "as", "at", "by", "we", "be", "have", "which", "there", "their",
        ),
        "pt" to setOf(
            "nao", "voce", "isso", "com", "uma", "dos", "das", "como", "por", "mais",
            "sobre", "para", "que", "de", "esta", "se", "na", "ao", "dos", "ele",
        ),
    )

    /** Mínimo de palabras funcionales para decidir el idioma. */
    private const val MIN_HITS = 2

    /**
     * Evalúa la respuesta.
     *
     * @param answer la respuesta del modelo.
     * @param prompt el prompt que se le mandó (el `system`): sirve para
     *   detectar una fuga de las instrucciones.
     * @param contextPresent si había material de la cátedra en el contexto.
     * @param idiomaEsperado el idioma en el que debería responder.
     * @return la lista de checks, siempre con los mismos códigos.
     */
    fun check(
        answer: String,
        prompt: String,
        contextPresent: Boolean,
        idiomaEsperado: String = IDIOMA_ESPANOL,
    ): List<PromptCheck> {
        val checks = mutableListOf<PromptCheck>()
        val norm = normalize(answer)
        val citas = MARKER.findAll(answer)
            .map { it.groupValues[1].toIntOrNull() ?: 0 }
            .toList()
        val diceNoEncontrado = NOT_FOUND_PATTERNS.any { norm.contains(it) }

        // --- 1: la respuesta existe ---------------------------------------
        checks += PromptCheck(
            codigo = "RESPUESTA_VACIA",
            ok = answer.isNotBlank(),
            mensaje = if (answer.isNotBlank()) {
                "El modelo devolvió una respuesta."
            } else {
                "El modelo devolvió una respuesta vacía."
            },
        )

        // --- 2: hay citas cuando había material ---------------------------
        checks += PromptCheck(
            codigo = "CITAS_PRESENTES",
            ok = !contextPresent || citas.isNotEmpty(),
            mensaje = when {
                !contextPresent -> "No había material de la cátedra: las citas no aplican."
                citas.isEmpty() -> "La respuesta no incluye citas [n]: no se puede verificar contra el material."
                else -> "La respuesta cita ${citas.size} fragmento(s)."
            },
        )

        // --- 3: la cita va al final de la frase ---------------------------
        val citasMalColocadas = citasFueraDeFrase(answer)
        checks += PromptCheck(
            codigo = "CITA_EN_LA_FRASE",
            ok = citasMalColocadas.isEmpty(),
            mensaje = if (citasMalColocadas.isEmpty()) {
                "Cada cita está al final de la frase que respalda."
            } else {
                "Hay ${citasMalColocadas.size} cita(s) que no están al final de la frase: la cita tiene que ir al final."
            },
        )

        // --- 4: los números de cita son válidos ---------------------------
        val invalidas = citas.filter { it <= 0 }
        checks += PromptCheck(
            codigo = "CITA_INVALIDA",
            ok = invalidas.isEmpty(),
            mensaje = if (invalidas.isEmpty()) {
                "Los números de cita son válidos."
            } else {
                "Hay citas con número inválido: ${invalidas.joinToString(", ") { "[$it]" }}. " +
                    "El primer fragmento es [1]."
            },
        )

        // --- 5: sin material no puede haber citas -------------------------
        checks += PromptCheck(
            codigo = "CITAS_SIN_CONTEXTO",
            ok = contextPresent || citas.isEmpty(),
            mensaje = if (contextPresent || citas.isEmpty()) {
                "No hay citas huérfanas."
            } else {
                "La respuesta cita ${citas.joinToString(", ") { "[$it]" }} pero no había material de la cátedra: son fuentes inventadas."
            },
        )

        // --- 6: "no lo encontré" y citas a la vez --------------------------
        checks += PromptCheck(
            codigo = "NO_ENCONTRADO_SIN_CITAS",
            ok = !(diceNoEncontrado && citas.isNotEmpty()),
            mensaje = if (diceNoEncontrado && citas.isNotEmpty()) {
                "La respuesta dice que no encontró el dato y además cita fuentes: es contradictorio."
            } else {
                "La respuesta no se contradice sobre la disponibilidad del dato."
            },
        )

        // --- 7: sin material tiene que decir que no lo encontró ------------
        checks += PromptCheck(
            codigo = "NO_ENCONTRADO_SIN_CONTEXTO",
            ok = contextPresent || answer.isBlank() || diceNoEncontrado,
            mensaje = when {
                contextPresent -> "Había material de la cátedra: no aplica."
                answer.isBlank() -> "No aplica: la respuesta está vacía."
                diceNoEncontrado -> "El material no cubría la pregunta y la respuesta lo dice."
                else -> "No había material de la cátedra y la respuesta NO dice que no lo encontró: respondió de memoria."
            },
        )

        // --- 8: idioma ------------------------------------------------------
        val idioma = detectLanguage(answer)
        checks += PromptCheck(
            codigo = "IDIOMA",
            ok = idioma == null || idioma == idiomaEsperado,
            mensaje = when {
                idioma == null -> "No se pudo determinar el idioma de la respuesta con certeza."
                idioma == idiomaEsperado -> "La respuesta está en el idioma esperado ($idioma)."
                else -> "La respuesta está en $idioma y se esperaba $idiomaEsperado."
            },
        )

        // --- 9: formato -----------------------------------------------------
        val palabras = answer.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        checks += PromptCheck(
            codigo = "FORMATO_BREVE",
            ok = palabras <= MAX_WORDS,
            mensaje = "La respuesta tiene $palabras palabras (tope: $MAX_WORDS).",
        )

        // --- 10: dudas improcedentes ----------------------------------------
        val duda = HEDGES.any { norm.contains(it) }
        checks += PromptCheck(
            codigo = "SIN_DUDAS_IMPROCEDENTES",
            ok = !(duda && !diceNoEncontrado),
            mensaje = if (duda && !diceNoEncontrado) {
                "La respuesta duda sobre algo que el material sí sostiene."
            } else {
                "La respuesta no duda de lo que el material sostiene."
            },
        )

        // --- 11: no habla de sí ---------------------------------------------
        val hablaDeSi = VOZ_PROPIA.any { norm.startsWith(it) }
        checks += PromptCheck(
            codigo = "NO_HABLO_DE_SI",
            ok = !hablaDeSi,
            mensaje = if (hablaDeSi) {
                "La respuesta empieza hablando de sí misma."
            } else {
                "La respuesta no empieza hablando de sí misma."
            },
        )

        // --- 12: no revela el prompt -----------------------------------------
        val fugas = LEAK_MARKERS.filter { norm.contains(it) }
        val copia = contieneFragmentoDelPrompt(norm, normalize(prompt))
        checks += PromptCheck(
            codigo = "NO_REVELA_REGLAS",
            ok = fugas.isEmpty() && !copia,
            mensaje = when {
                fugas.isNotEmpty() -> "La respuesta repite un fragmento de las instrucciones (${fugas.joinToString(", ") { "\"$it\"" }})."
                copia -> "La respuesta copia un tramo largo del prompt."
                else -> "La respuesta no revela las instrucciones."
            },
        )

        // --- 13: inyección sospechada -----------------------------------------
        val inyeccion = INJECTION_PATTERNS.filter { norm.contains(it) }
        checks += PromptCheck(
            codigo = "INYECCION_SOSPECHADA",
            ok = inyeccion.isEmpty(),
            mensaje = if (inyeccion.isEmpty()) {
                "La respuesta no contiene órdenes de cambiar las reglas."
            } else {
                "La respuesta contiene una orden de cambiar las reglas (${inyeccion.joinToString(", ") { "\"$it\"" }})."
            },
        )

        // --- 14: no filtra la estructura del prompt ----------------------------
        val delimits = answer.contains("<<<") || answer.contains(">>>")
        checks += PromptCheck(
            codigo = "DELIMITADORES_EN_LA_RESPUESTA",
            ok = !delimits,
            mensaje = if (delimits) {
                "La respuesta filtra los delimitadores del prompt."
            } else {
                "La respuesta no filtra los delimitadores del prompt."
            },
        )

        return checks
    }

    /* ------------------------------------------------------------------
     * Auxiliares (deterministas)
     * ------------------------------------------------------------------ */

    /**
     * Normaliza para comparar: minúsculas, sin acentos, sin puntuación.
     */
    private fun normalize(text: String): String =
        stripDiacritics(text.lowercase())
            .replace(NON_ALNUM, " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")

    /**
     * Las citas que **no** están al final de su frase.
     *
     * Una cita tiene que ir al final de la frase que respalda: si después
     * del marcador sigue texto, el verificador de citas juzga la oración
     * equivocada.
     */
    private fun citasFueraDeFrase(answer: String): List<Int> {
        val fuera = mutableListOf<Int>()
        for (match in MARKER.findAll(answer)) {
            var fin = answer.length
            for (i in match.range.last + 1 until answer.length) {
                val c = answer[i]
                if (c == '.' || c == '!' || c == '?' || c == '\n' || c == ';') {
                    fin = i
                    break
                }
            }
            if (answer.substring(match.range.last + 1, fin).isNotBlank()) {
                fuera += match.groupValues[1].toIntOrNull() ?: 0
            }
        }
        return fuera
    }

    /**
     * ¿La respuesta copia un tramo largo del prompt?
     *
     * Se buscan [MIN_LEAK_WORDS] palabras seguidas de la respuesta que
     * aparezcan **seguidas** en el prompt. Es literal y determinista: no
     * juzga si la respuesta es "parecida", sino si es un calco.
     */
    private fun contieneFragmentoDelPrompt(normAnswer: String, normPrompt: String): Boolean {
        if (normPrompt.isBlank()) return false
        val palabras = normAnswer.split(' ').filter { it.isNotBlank() }
        if (palabras.size < MIN_LEAK_WORDS) return false
        val prompt = normPrompt.split(' ').filter { it.isNotBlank() }.joinToString(" ")
        for (i in 0..palabras.size - MIN_LEAK_WORDS) {
            val tramo = palabras.subList(i, i + MIN_LEAK_WORDS).joinToString(" ")
            if (prompt.contains(tramo)) return true
        }
        return false
    }

    /**
     * El idioma de un texto, o `null` si no hay evidencia suficiente.
     *
     * Desempate: si dos idiomas empatan, no se decide (ambos son plausibles).
     */
    private fun detectLanguage(text: String): String? {
        val palabras = stripDiacritics(text.lowercase())
            .split(NON_LETTERS)
            .filter { it.isNotBlank() }
        if (palabras.size < 3) return null
        val puntajes = LEXICON.mapValues { (_, palabrasFuncionales) ->
            palabras.count { it in palabrasFuncionales }
        }
        val mejor = puntajes.maxByOrNull { it.value } ?: return null
        if (mejor.value < MIN_HITS) return null
        val ganadores = puntajes.filterValues { it == mejor.value }.keys
        return ganadores.singleOrNull()
    }
}
