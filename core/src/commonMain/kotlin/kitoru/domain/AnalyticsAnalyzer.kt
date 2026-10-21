package kitoru.domain

import kotlin.math.roundToInt

/**
 * Calcula la analítica de una cátedra a partir de las conversaciones.
 *
 * Es una función **pura**: no toca disco, no llama al modelo y no depende de
 * la red. Por eso es testeable al 100% y los números que ve el docente son
 * reproducibles.
 *
 * Reglas:
 *  - Sólo se cuentan los chats de alumnos cuya inscripción **muestra
 *    métricas** ([ChatVisibility.showsMetrics]); un alumno con visibilidad
 *    [ChatVisibility.NONE] no aparece en ningún agregado (tampoco en el top
 *    de fuentes: si no, puede filtrar qué material consultó).
 *  - Una persona es una sola fila, aunque tenga varias inscripciones en la
 *    misma cátedra.
 *  - Los agregados de la cátedra son de los **alumnos**: la actividad de los
 *    docentes se cuenta aparte, si no infla los números de los alumnos.
 *  - El texto de los mensajes se respeta según
 *    [ChatVisibility.showsText]; aquí sólo se agrega el texto cuando hace
 *    falta para un indicador.
 *  - La calidad de las respuestas se mide con la verificación de citas: es
 *    determinista (comprueba el texto contra la fuente), no una opinión del
 *    modelo. Una respuesta que no cita nada no está fundamentada.
 */
object AnalyticsAnalyzer {

    /**
     * Analiza una cátedra.
     *
     * @param course cátedra analizada.
     * @param enrollments inscripciones (dan el rol y la visibilidad).
     * @param threads hilos de la cátedra.
     * @param messages mensajes de esos hilos.
     */
    fun analyze(
        course: Course,
        enrollments: List<Enrollment>,
        threads: List<ChatThread>,
        messages: List<ChatMessage>,
    ): CourseAnalytics {
        // Una persona es **una sola fila**, aunque tenga varias inscripciones
        // en la misma cátedra: si no, sus mensajes se cuentan dos veces.
        // Y si alguna de sus inscripciones dice NONE, no aparece en ninguna
        // lista: el pedido de privacidad del alumno gana siempre.
        val visible = visibleEnrollments(enrollments)
        val byUser = visible.associateBy { it.userId }
        val threadById = threads.associateBy { it.id }

        // Los mensajes se agrupan por hilo y luego por autor.
        val messagesByThread = messages.groupBy { it.threadId }
        val threadsByUser = threads.groupBy { it.authorId }

        val perStudent = visible.map { enrollment ->
            val userThreads = threadsByUser[enrollment.userId].orEmpty()
            val userThreadIds = userThreads.map { it.id }.toSet()
            val userMessages = userThreadIds.flatMap { messagesByThread[it].orEmpty() }
            studentStats(enrollment, userThreads, userMessages)
        }.sortedByDescending { it.messages }

        val students = perStudent.filter { it.role == CourseRole.ALUMNO }
        val teachers = perStudent.count { it.role != CourseRole.ALUMNO }

        val totalMessages = students.sumOf { it.messages }
        val totalReplies = students.sumOf { it.replies }
        val activeStudents = students.count { it.messages > 0 }

        val citationsVerified = students.sumOf { it.citationsVerified }
        val citationsUnsupported = students.sumOf { it.citationsUnsupported }
        val citationsUnknown = students.sumOf { it.citationsUnknown }
        val citationsTotal = citationsVerified + citationsUnsupported + citationsUnknown

        // Fuentes más consultadas: cuenta cada etiqueta de fuente de las
        // respuestas del asistente.
        //
        // Sólo se cuentan las de los hilos de los usuarios **visibles**: un
        // alumno con `visibility = NONE` no puede filtrar qué material
        // consultó (fuga de datos: el top de fuentes lo delata).
        val sourceCounts = mutableMapOf<String, Int>()
        for (thread in threads) {
            if (!byUser.containsKey(thread.authorId)) continue
            for (message in messagesByThread[thread.id].orEmpty()) {
                if (!message.isAssistant) continue
                for (source in message.sources) {
                    sourceCounts[source] = (sourceCounts[source] ?: 0) + 1
                }
            }
        }
        val topSources = sourceCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(10)
            .map { SourceUsage(it.key, it.value) }

        // Actividad por día (medianoche UTC del día del mensaje).
        //
        // Sólo se cuenta la actividad de los **alumnos**: la de los docentes
        // se reporta aparte, si no un profesor probando el sistema infla el
        // gráfico de actividad de los alumnos (y la suma del gráfico no
        // cuadra con los totales).
        val byDay = mutableMapOf<Long, Pair<Int, Int>>()
        for (message in messages) {
            val thread = threadById[message.threadId] ?: continue
            val enrollment = byUser[thread.authorId] ?: continue
            if (enrollment.role != CourseRole.ALUMNO) continue
            val day = dayStart(message.createdAt)
            val (msgs, replies) = byDay.getOrDefault(day, 0 to 0)
            byDay[day] = if (message.isUser) (msgs + 1) to replies else msgs to (replies + 1)
        }
        val perDay = byDay.entries.sortedBy { it.key }.map {
            DayActivity(it.key, it.value.first, it.value.second)
        }

        val totalLatency = students.sumOf { it.avgLatencyMs * it.replies }
        val avgLatency = if (totalReplies == 0) 0L else totalLatency / totalReplies

        return CourseAnalytics(
            courseId = course.id,
            students = students.size,
            activeStudents = activeStudents,
            teachers = teachers,
            totalMessages = totalMessages,
            totalReplies = totalReplies,
            totalThreads = students.sumOf { it.threads },
            avgMessagesPerStudent = if (students.isEmpty()) 0.0
            else (totalMessages.toDouble() / students.size).roundToInt().toDouble(),
            citationSupportRate = if (citationsTotal == 0) 0.0
            else citationsVerified.toDouble() / citationsTotal,
            citationsVerified = citationsVerified,
            citationsUnsupported = citationsUnsupported,
            citationsUnknown = citationsUnknown,
            avgLatencyMs = avgLatency,
            totalTokens = students.sumOf { it.tokensIn + it.tokensOut },
            perStudent = perStudent,
            perDay = perDay,
            topSources = topSources,
            inactiveStudents = students.filter { it.messages == 0 }.map { it.displayName },
        )
    }

    /** Métricas de un alumno. */
    private fun studentStats(
        enrollment: Enrollment,
        threads: List<ChatThread>,
        messages: List<ChatMessage>,
    ): StudentAnalytics {
        val userMessages = messages.filter { it.role == "user" }
        val replies = messages.filter { it.isAssistant }

        var verified = 0
        var unsupported = 0
        var unknown = 0
        var answersWithUnsupported = 0
        var groundable = 0
        var answersWithoutGrounding = 0
        var toolCalls = 0
        var toolFailures = 0
        var tokensIn = 0
        var tokensOut = 0
        var latencySum = 0L

        for (message in replies) {
            message.citation?.let {
                verified += it.verified
                unsupported += it.unsupported
                unknown += it.unknown
                if (it.unsupported > 0 || it.unknown > 0) answersWithUnsupported++
            }
            // Una respuesta sólo se puede evaluar si el modelo tuvo material
            // en el contexto o si alguien verificó sus citas. Una respuesta
            // de chat general no es "mala": no hay nada que evaluar.
            val evaluable = message.sources.isNotEmpty() || message.citation != null
            if (evaluable) {
                groundable++
                // Fundamentada = al menos una cita verificada, cero sin
                // respaldo y cero inexistentes. Una respuesta que no citó
                // nada **no** está fundamentada: "no cité nada" no es
                // "respondí con respaldo".
                val fundamentada = message.citation != null &&
                    message.citation.verified >= 1 &&
                    message.citation.unsupported == 0 &&
                    message.citation.unknown == 0
                if (!fundamentada) answersWithoutGrounding++
            }
            toolCalls += message.toolCalls.size
            toolFailures += message.toolCalls.count { it.ok == false }
            tokensOut += message.tokensOut
            latencySum += message.latencyMs
        }
        for (message in userMessages) tokensIn += message.tokensIn

        val days = messages.map { dayStart(it.createdAt) }.distinct().size

        return StudentAnalytics(
            userId = enrollment.userId,
            displayName = enrollment.displayName,
            role = enrollment.role,
            messages = userMessages.size,
            replies = replies.size,
            threads = threads.size,
            activeDays = days,
            lastActivity = messages.maxOfOrNull { it.createdAt } ?: 0L,
            avgLatencyMs = if (replies.isEmpty()) 0L else latencySum / replies.size,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            citationsVerified = verified,
            citationsUnsupported = unsupported,
            citationsUnknown = unknown,
            toolCalls = toolCalls,
            toolFailures = toolFailures,
            answersWithUnsupportedCitations = answersWithUnsupported,
            groundableReplies = groundable,
            answersWithoutGrounding = answersWithoutGrounding,
        )
    }

    /**
     * Las inscripciones que el docente puede ver, **una fila por persona**.
     *
     * - Una persona con varias inscripciones en la misma cátedra se cuenta
     *   una sola vez: si no, sus mensajes se cuentan dos veces y todos los
     *   totales quedan inflados.
     * - Si alguna de sus inscripciones dice [ChatVisibility.NONE], no aparece
     *   en ninguna lista (ni en `perStudent`, ni en `inactiveStudents`): pidió
     *   que no se lo vieran y eso vale más que cualquier otro agregado.
     */
    private fun visibleEnrollments(enrollments: List<Enrollment>): List<Enrollment> =
        enrollments
            .groupBy { it.userId }
            .values
            .mapNotNull { candidates ->
                // La privacidad manda: si alguna inscripción es NONE, la
                // persona queda oculta por completo.
                if (candidates.any { !it.visibility.showsMetrics }) return@mapNotNull null
                // Entre las que se pueden ver, la de rol ALUMNO: una persona
                // inscrita como alumno cuenta como alumno.
                candidates.firstOrNull { it.role == CourseRole.ALUMNO } ?: candidates.first()
            }

    /**
     * Medianoche (UTC) del día de un instante. Se usa como clave de
     * agrupación: dos mensajes del mismo día caen en la misma entrada.
     */
    fun dayStart(millis: Long): Long {
        val dayMillis = 86_400_000L
        return ((millis / dayMillis) + if (millis % dayMillis < 0) -1 else 0) * dayMillis
    }
}
