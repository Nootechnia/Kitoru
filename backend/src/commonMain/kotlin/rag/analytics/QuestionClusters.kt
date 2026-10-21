package rag.analytics

import kitoru.domain.ChatMessage
import kitoru.domain.ChatThread
import kitoru.domain.CitationStats

/**
 * Las cuatro señales de fracaso de un turno.
 *
 * Cada componente está en `[0, 1]`:
 *
 * - [repetition] (**R**): el alumno ya había hecho esta pregunta antes.
 * - [grounding] (**G**): la respuesta no está anclada en el material de la
 *   cátedra (no recuperó fuentes, o las citas no se respaldaron).
 * - [reask] (**Q**): el alumno repreguntó en el mismo hilo.
 * - [abandonment] (**A**): el alumno dejó de escribir después de esa respuesta.
 *
 * Ninguna señal sola alcanza el umbral de fracaso: hace falta la conjunción de
 * al menos dos, que es lo que discrimina el ruido de la señal real.
 */
data class TurnSignals(
    val repetition: Double = 0.0,
    val grounding: Double = 0.0,
    val reask: Double = 0.0,
    val abandonment: Double = 0.0,
    /** La repregunta trae más detalle que la original: el alumno insiste. */
    val insistent: Boolean = false,
) {
    /**
     * Puntaje de fracaso del turno: `F = 0,30·R + 0,35·G + 0,25·Q + 0,10·A`.
     *
     * Los pesos no son arbitrarios: **G** es la señal más dura (sale de las
     * fuentes y de las citas, sin interpretar el texto), así que es la más
     * pesada; **A** es la más ruidosa (todo hilo termina algún día), así que es
     * la más liviana.
     */
    val failure: Double
        get() = 0.30 * repetition + 0.35 * grounding + 0.25 * reask + 0.10 * abandonment

    /** Los motivos en texto, para la evidencia que ve el docente. */
    fun reasons(): List<String> = buildList {
        if (repetition >= 0.99) add("Repitió una pregunta que ya había hecho")
        if (grounding >= 0.60) add("La IA no encontró nada en el material")
        else if (grounding > 0.0) add("La respuesta no está respaldada por la fuente")
        if (reask >= 0.99) add("Repreguntó lo mismo en el mismo hilo")
        if (abandonment >= 0.99) add("Después de esta respuesta dejó de escribir")
    }
}

/**
 * El veredicto de un turno: su puntaje de fracaso y si cuenta como fracaso.
 *
 * @param turn las señales del turno.
 * @param threshold el `F` mínimo para que el turno sea un fracaso.
 */
data class TurnFailure(
    val turn: TurnSignals,
    val threshold: Double = TurnFailure.DEFAULT_THRESHOLD,
) {
    /** Puntaje de fracaso del turno (0..1). */
    val failure: Double get() = turn.failure

    /** ¿El turno es un fracaso? */
    val isFailure: Boolean get() = failure >= threshold

    companion object {
        /**
         * Umbral de fracaso por turno.
         *
         * Con 0,50 el caso "la IA no recuperó nada y el alumno repreguntó"
         * (F = 0,46) no sería fracaso, y ese es justamente el caso que define
         * al producto. Con 0,45 entra, y el caso "no recuperó nada y el alumno
         * se fue" (F = 0,38) sigue afuera, que es lo correcto: si el alumno no
         * repreguntó, no hay evidencia de que le faltara respuesta.
         */
        const val DEFAULT_THRESHOLD = 0.45
    }
}

/**
 * Tipo de hueco detectado en un cluster de preguntas.
 *
 * La clasificación es **accionable**: no dice "hay un problema" sino cuál de
 * los tres es, porque cada uno tiene una remedy distinto para el docente.
 */
enum class GapKind(val label: String, val action: String) {
    /** El cluster no es un hueco: el material reached a los alumnos. */
    NINGUNO(
        "Sin hueco",
        "",
    ),

    /** Ninguna pregunta del cluster recuperó una fuente: falta material. */
    SIN_MATERIAL(
        "Falta material",
        "Falta un apunte sobre este tema: la IA no encontró nada que responder.",
    ),

    /** Se recuperó material pero las respuestas no lo respaldan. */
    NO_RECUPERADO(
        "Material sin recuperar",
        "El material existe pero las citas no lo respaldan: revisá el indexado de ese archivo.",
    ),

    /** Se recuperó y se respaldó, pero el alumno volvió a preguntar. */
    SIN_RESPALDO(
        "Material insuficiente",
        "El material está y está bien citado, pero no alcanza: agregá ejemplos y detalle.",
    ),
}

/**
 * Un cluster de preguntas con su diagnóstico.
 *
 * @property representative la pregunta más central del cluster.
 * @property topic etiqueta del tema (los tokens más discriminativos).
 * @property members los textos de las preguntas del cluster.
 * @property distinctStudents alumnos distintos que lo preguntaron: la métrica
 *   que importa, porque "10 alumnos preguntaron lo mismo" es el problema real.
 * @property meanFailure promedio de `F` de los turnos del cluster.
 * @property noSourceTurns turnos en los que la IA no recuperó ninguna fuente.
 * @property noSourceRate proporción de turnos sin fuente (0..1).
 * @property unsupportedTurns turnos en los que se recuperó material pero las
 *   citas no lo respaldaron.
 * @property reaskTurns turnos en los que el alumno volvió a preguntar.
 * @property gapScore impacto: `alumnos × frecuenciaMedia × (0,5 + tasaSinFuente)`.
 * @property isGap ¿es un hueco de material? (los tres criterios juntos).
 * @property kind qué falta: material, indexado o contenido.
 */
data class QuestionGap(
    val representative: String,
    val topic: String = "",
    val members: List<String> = emptyList(),
    val distinctStudents: Int = 0,
    val questions: Int = 0,
    val turns: Int = 0,
    val meanFailure: Double = 0.0,
    val failedTurns: Int = 0,
    val noSourceTurns: Int = 0,
    val noSourceRate: Double = 0.0,
    val unsupportedTurns: Int = 0,
    val reaskTurns: Int = 0,
    val gapScore: Double = 0.0,
    val isGap: Boolean = false,
    val kind: GapKind = GapKind.NINGUNO,
)

/**
 * El informe de preguntas repetidas de una cátedra.
 *
 * @property gaps los clusters que son un hueco de material, ordenados por
 *   impacto (de mayor a menor `gapScore`).
 * @property clusters todos los clusters, también ordenados por impacto.
 */
data class CourseQuestionReport(
    val gaps: List<QuestionGap> = emptyList(),
    val clusters: List<QuestionGap> = emptyList(),
    val totalQuestions: Int = 0,
    val totalTurns: Int = 0,
    val failedTurns: Int = 0,
    /** Umbrales con los que se calculó el informe (para reproducibilidad). */
    val similarityThreshold: Double = 0.62,
    val failureThreshold: Double = 0.45,
)

/**
 * Detector de preguntas repetidas y de huecos del material.
 *
 * Es una **función pura**: no toca disco, no llama al modelo, no depende de la
 * red. Los mismos chats producen siempre el mismo informe (regla 3 del
 * proyecto).
 *
 * La visibilidad no se resuelve acá: el que llama es responsable de pasar
 * solamente los chats de los alumnos cuya inscripción deja ver las métricas
 * (regla 6: el docente no ve lo que no debe).
 */
object QuestionClusters {

    /**
     * Palabras de conversación que no son contenido.
     *
     * El tokenizador ya saca las palabras vacías gramaticales; estas son las
     * que aparecen en el chat de un alumno ("hola", "gracias", "duda") y que no
     * dicen de qué tema está preguntando. Sólo se usan para armar la etiqueta
     * del tema, no para la similitud: así la lista de palabras del RAG y la de
     * la analítica siguen siendo la misma.
     */
    private val CONVERSATION_STOP = setOf(
        "hola", "gracias", "profe", "profesor", "dale", "entiendo", "no", "si", "ok",
        "okay", "buenisimo", "porfa", "ahi", "esto", "eso", "cual", "cuando", "ejemplo",
        "duda", "ay", "ayuda", "help", "thanks", "hi", "hello", "yes", "please", "explain",
        "example", "question", "answer",
    )

    /** Milisegundos por día (para calcular los días sin escribir). */
    private const val MILLIS_PER_DAY = 86_400_000L

    /**
     * Analiza los chats de una cátedra y devuelve sus clusters de preguntas
     * repetidas y sus huecos de material.
     *
     * @param threads los hilos de la cátedra (de acá sale el dueño de cada
     *   pregunta).
     * @param messages los mensajes de esos hilos.
     * @param now el instante de referencia para calcular el abandono. Si es 0,
     *   no se cuenta abandono (no hay reloj: no se inventa información).
     * @param config los umbrales del detector.
     */
    fun analyze(
        threads: List<ChatThread>,
        messages: List<ChatMessage>,
        now: Long = 0L,
        config: QuestionClusterConfig = QuestionClusterConfig(),
    ): CourseQuestionReport {
        val threadById = threads.associateBy { it.id }
        val messagesByThread = messages
            .filter { threadById.containsKey(it.threadId) }
            .groupBy { it.threadId }

        // 1. Turnos por hilo: una pregunta + la respuesta que la atiende + la
        //    pregunta siguiente. La pregunta siguiente es a la vez evidencia de
        //    repregunta y la pregunta del turno que sigue.
        val turns = ArrayList<Turn>()
        for (threadId in messagesByThread.keys.sorted()) {
            val studentId = threadById.getValue(threadId).authorId
            for (turn in buildTurns(messagesByThread.getValue(threadId))) {
                turns += turn.withStudent(studentId)
            }
        }
        val ordered = turns.sortedWith(compareBy({ it.question.createdAt }, { it.question.id }))

        // 2. Índice de preguntas: la IDF sale de las preguntas de la cátedra.
        val index = QuestionSimilarity(config.maxAnchorDf)
        for (turn in ordered) turn.questionId = index.add(turn.question.text)
        index.build()

        // 3. Agrupamiento por union-find sobre el grafo de similitud.
        val clusterer = QuestionClusterer(config)
        val indexed = clusterer.clusterIndexed(index)

        // 4. Puntaje de fracaso por turno.
        val questionIdByMessage = HashMap<String, Int>()
        for (turn in ordered) questionIdByMessage[turn.question.id] = turn.questionId
        val signals = scoreTurns(ordered, index, questionIdByMessage, config, now)

        // 5. Agregado por cluster.
        val turnByQuestionId = HashMap<Int, Turn>()
        for (turn in ordered) turnByQuestionId[turn.questionId] = turn

        val clusters = indexed.map { cluster ->
            val clusterTurns = cluster.memberIds.mapNotNull { turnByQuestionId[it] }
            buildGap(cluster, clusterTurns, signals, index, config)
        }.sortedWith(
            compareByDescending<QuestionGap> { it.gapScore }
                .thenByDescending { it.distinctStudents }
                .thenBy { it.representative },
        )

        val failed = signals.values.count { it.failure >= config.failureThreshold }
        return CourseQuestionReport(
            gaps = clusters.filter { it.isGap },
            clusters = clusters,
            totalQuestions = ordered.size,
            totalTurns = ordered.size,
            failedTurns = failed,
            similarityThreshold = config.similarityThreshold,
            failureThreshold = config.failureThreshold,
        )
    }

    /**
     * Arma los turnos de un hilo a partir de sus mensajes.
     *
     * Los mensajes se ordenan por `createdAt` (desempate por id) para que el
     * resultado no dependa del orden de lectura del JSON.
     */
    private fun buildTurns(messages: List<ChatMessage>): List<Turn> {
        val ordered = messages.sortedWith(compareBy({ it.createdAt }, { it.id }))
        val turns = ArrayList<Turn>()
        var pending: ChatMessage? = null
        var answer: ChatMessage? = null
        for (message in ordered) {
            if (message.isUser) {
                val previous = pending
                if (previous != null) {
                    turns += Turn(question = previous, answer = answer, nextQuestion = message)
                }
                pending = message
                answer = null
            } else if (message.isAssistant) {
                // La última respuesta gana (reintentos del modelo). Una
                // respuesta sin pregunta previa no se le atribuye a nadie.
                if (pending != null) answer = message
            }
        }
        val last = pending
        if (last != null) turns += Turn(question = last, answer = answer, nextQuestion = null)
        return turns
    }

    /**
     * Calcula las señales de fracaso de cada turno.
     *
     * @return las señales indexadas por id del mensaje de la pregunta.
     */
    private fun scoreTurns(
        turns: List<Turn>,
        index: QuestionSimilarity,
        questionIdByMessage: Map<String, Int>,
        config: QuestionClusterConfig,
        now: Long,
    ): Map<String, TurnSignals> {
        // Último mensaje de cada alumno en la cátedra: de eso depende el
        // abandono ("el alumno dejó de escribir").
        val lastUserAt = HashMap<String, Long>()
        for (turn in turns) {
            val previous = lastUserAt[turn.studentId]
            if (previous == null || turn.question.createdAt > previous) {
                lastUserAt[turn.studentId] = turn.question.createdAt
            }
        }

        // Preguntas previas por alumno: es lo que hace posible la señal de
        // repetición ("este alumno ya lo había preguntado").
        val previousByStudent = HashMap<String, MutableList<Int>>()
        val out = HashMap<String, TurnSignals>()
        for (turn in turns) {
            val previous = previousByStudent[turn.studentId] ?: ArrayList()
            var maxSimilarity = 0.0
            for (previousId in previous.takeLast(config.previousQuestionsForRepeat)) {
                val similarity = index.similarity(previousId, turn.questionId)
                if (similarity > maxSimilarity) maxSimilarity = similarity
            }
            val repetition = normalize(maxSimilarity, config.similarityThreshold)

            val grounding = groundingOf(turn.answer)

            var reask = 0.0
            var insistent = false
            val next = turn.nextQuestion
            if (next != null) {
                val nextId = questionIdByMessage[next.id] ?: -1
                if (nextId >= 0) {
                    reask = normalize(index.similarity(turn.questionId, nextId), config.similarityThreshold)
                    // Si la repregunta trae más detalle, el alumno insiste: es
                    // evidencia para el docente (no entra en el puntaje).
                    insistent = index.tokenCount(nextId) > index.tokenCount(turn.questionId)
                }
            }

            val lastInThread = turn.answer != null && turn.nextQuestion == null
            val lastAt = lastUserAt[turn.studentId] ?: turn.question.createdAt
            val idleDays = if (now > 0L && lastAt > 0L) {
                ((now - lastAt).coerceAtLeast(0L) / MILLIS_PER_DAY).toInt()
            } else {
                0
            }
            val abandonment = abandonmentOf(lastInThread, idleDays, config.idleDaysForAbandon)

            out[turn.question.id] = TurnSignals(
                repetition = repetition,
                grounding = grounding,
                reask = reask,
                abandonment = abandonment,
                insistent = insistent,
            )
            previousByStudent.getOrPut(turn.studentId) { ArrayList() } += turn.questionId
        }
        return out
    }

    /**
     * Componente de anclaje: la respuesta no está respaldada por el material.
     *
     * ```
     * G = 0,60·sinFuente + 0,20·sinCita + 0,20·proporciónSinRespaldo
     * ```
     *
     * La separación importa: "no encontró el material" (G = 0,60) y
     * "encontró el material pero respondió mal" (G = 0,20) son dos problemas
     * distintos con dos acciones distintas.
     */
    private fun groundingOf(answer: ChatMessage?): Double {
        if (answer == null) return 0.0
        val noSource = if (answer.sources.isEmpty()) 1.0 else 0.0
        val citation = answer.citation
        val noCitation = if (citation == null || citation.total == 0) 1.0 else 0.0
        val unsupported = if (citation == null || citation.total == 0) {
            0.0
        } else {
            (citation.unsupported + citation.unknown).toDouble() / citation.total
        }
        return 0.60 * noSource + 0.20 * noCitation + 0.20 * unsupported
    }

    /**
     * Normaliza una similitud al rango [0, 1] según el umbral.
     *
     * Así la componente dice "cuánto más allá del umbral" y no "cuánto
     * parecido": una similitud de 0,62 no aporta nada.
     */
    private fun normalize(similarity: Double, threshold: Double): Double {
        if (similarity <= threshold) return 0.0
        return ((similarity - threshold) / (1.0 - threshold)).coerceIn(0.0, 1.0)
    }

    /**
     * Componente de abandono.
     *
     * @param lastInThread si la respuesta del turno es la última del hilo.
     * @param idleDays días desde el último mensaje del alumno en la cátedra.
     */
    private fun abandonmentOf(lastInThread: Boolean, idleDays: Int, idleThreshold: Int): Double = when {
        !lastInThread -> 0.0
        idleDays >= idleThreshold -> 1.0
        idleDays >= maxOf(1, idleThreshold / 2) -> 0.5
        else -> 0.0
    }

    /** ¿La respuesta recuperó material pero no lo respaldó? */
    private fun isUnsupported(answer: ChatMessage?): Boolean {
        if (answer == null) return false
        if (answer.sources.isEmpty()) return false
        val citation = answer.citation ?: return true
        if (citation.total == 0) return true
        return citation.unsupported + citation.unknown > 0
    }

    /** Agrega las estadísticas de un cluster. */
    private fun buildGap(
        cluster: IndexedCluster,
        turns: List<Turn>,
        signals: Map<String, TurnSignals>,
        index: QuestionSimilarity,
        config: QuestionClusterConfig,
    ): QuestionGap {
        val turnSignals = turns.map { signals[it.question.id] ?: TurnSignals() }
        val students = turns.map { it.studentId }.distinct().size
        val meanFailure = if (turnSignals.isEmpty()) 0.0 else turnSignals.sumOf { it.failure } / turnSignals.size
        val noSourceTurns = turns.count { it.answer == null || it.answer.sources.isEmpty() }
        val noSourceRate = if (turns.isEmpty()) 0.0 else noSourceTurns.toDouble() / turns.size
        val unsupportedTurns = turns.count { isUnsupported(it.answer) }
        val reaskTurns = turnSignals.count { it.reask > 0.0 }
        val failedTurns = turnSignals.count { it.failure >= config.failureThreshold }

        // Un cluster es un hueco cuando lo preguntaron varios alumnos, la media
        // de fracaso es alta y la IA no encontró material para la mitad de los
        // turnos: las tres cosas juntas, no una sola.
        val isGap = students >= config.minDistinctStudentsForGap &&
            meanFailure >= config.failureThreshold &&
            noSourceRate >= config.minNoSourceRateForGap
        val gapScore = students * meanFailure * (0.5 + noSourceRate)

        return QuestionGap(
            representative = index.textOf(cluster.representativeId),
            topic = topicOf(cluster, index),
            members = cluster.memberIds.map { index.textOf(it) },
            distinctStudents = students,
            questions = cluster.memberIds.size,
            turns = turns.size,
            meanFailure = meanFailure,
            failedTurns = failedTurns,
            noSourceTurns = noSourceTurns,
            noSourceRate = noSourceRate,
            unsupportedTurns = unsupportedTurns,
            reaskTurns = reaskTurns,
            gapScore = gapScore,
            isGap = isGap,
            kind = classify(noSourceTurns, turns.size, unsupportedTurns, reaskTurns),
        )
    }

    /**
     * Clasifica el hueco: qué es lo que falta.
     *
     * El orden importa porque cada caso descarta al anterior:
     *
     * 1. nunca se recuperó una fuente ⇒ **falta material**;
     * 2. se recuperó pero las citas no lo respaldan ⇒ **el indexado o el
     *    modelo no lo están usando**;
     * 3. se recuperó y se respaldó, pero el alumno volvió ⇒ **el material no
     *    alcanza**.
     */
    private fun classify(noSourceTurns: Int, turns: Int, unsupportedTurns: Int, reaskTurns: Int): GapKind = when {
        turns > 0 && noSourceTurns >= turns -> GapKind.SIN_MATERIAL
        unsupportedTurns > 0 -> GapKind.NO_RECUPERADO
        reaskTurns > 0 -> GapKind.SIN_RESPALDO
        else -> GapKind.NINGUNO
    }

    /**
     * Etiqueta del tema del cluster: los tokens más discriminativos.
     *
     * El peso es `tf_cluster × idf`: un token que aparece en muchas
     * preguntas del cluster y es raro en la cátedra es el que describe el
     * tema. Se descartan los tokens genéricos (aparecen en más del 30 % de las
     * preguntas de la cátedra) y las palabras de conversación.
     */
    private fun topicOf(cluster: IndexedCluster, index: QuestionSimilarity): String {
        val total = index.size
        if (total == 0) return ""
        val genericLimit = 0.30 * total
        val termFrequency = HashMap<Int, Int>()
        for (questionId in cluster.memberIds) {
            for (tokenId in index.tokensOf(questionId)) {
                termFrequency[tokenId] = (termFrequency[tokenId] ?: 0) + 1
            }
        }
        val candidates = termFrequency.mapNotNull { (tokenId, count) ->
            val text = index.tokenText(tokenId)
            if (text in CONVERSATION_STOP) return@mapNotNull null
            Triple(tokenId, count * index.weightOfToken(tokenId), text)
        }
        if (candidates.isEmpty()) return ""
        // Se descartan los tokens genéricos (aparecen en más del 30 % de las
        // preguntas de la cátedra: "hola", "profesor"). Si el filtro deja la
        // lista vacía es porque la cátedra es chica: mejor un tema poco preciso
        // que un cluster sin nombre.
        val specific = candidates.filter { index.documentFrequencyOfToken(it.first) <= genericLimit }
        val scored = if (specific.isEmpty()) candidates else specific
        val best = scored.maxOf { it.second }
        val floor = 0.35 * best
        val top = scored
            .filter { it.second >= floor }
            .sortedWith(compareByDescending<Triple<Int, Double, String>> { it.second }.thenBy { it.third })
        // Con dos o más preguntas distintas, una sola palabra es demasiado
        // ambigua para describir el cluster.
        val wanted = when {
            top.size >= 3 -> 3
            top.size == 2 -> 2
            else -> 1
        }
        return top.take(wanted).joinToString(" ") { it.third }
    }
}

/**
 * Un turno: una pregunta del alumno, la respuesta que la atiende y la
 * pregunta siguiente del mismo hilo.
 */
private class Turn(
    val question: ChatMessage,
    val answer: ChatMessage?,
    val nextQuestion: ChatMessage?,
    val studentId: String = "",
) {
    /** Id de la pregunta en el índice de preguntas. */
    var questionId: Int = -1

    /** Copia del turno con el alumno dueño del hilo. */
    fun withStudent(studentId: String): Turn = Turn(question, answer, nextQuestion, studentId)
}
