package kitoru.domain

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Acumulador **incremental** de las métricas de una cátedra.
 *
 * Es la contraparte stateful de [AnalyticsAnalyzer.analyze]: mantiene un cubo
 * por usuario y se actualiza en **O(1)** por cada mensaje nuevo; al final se
 * "congela" en un [CourseAnalytics] con [freeze].
 *
 * Reglas de diseño:
 *
 *  - **El acumulador no aplica visibilidad.** Calcula para todos los usuarios
 *    y es el [freeze] el que decide a quién se le muestra. Por eso cambiar la
 *    visibilidad de un alumno es un *re-freeze* barato (O(usuarios·días)) y
 *    no un recálculo de un millón de mensajes.
 *  - **Las fuentes y la actividad diaria se guardan por usuario**, no
 *    globales, por la misma razón: si fueran globales, un alumno oculto
 *    dejaría de ser excluible sin releer todos sus mensajes.
 *  - **No guarda mensajes, textos ni hilos completos**: sólo contadores. La
 *    memoria no crece con la cantidad de mensajes.
 *  - Es **determinista**: no usa el reloj, la red ni el modelo.
 *
 * Es la **implementación de referencia** de las reglas de la analítica:
 * [AnalyticsAccumulator.analyze] es el camino puro (replay + freeze) y el
 * acumulador, el camino incremental. No puede haber dos implementaciones de
 * las mismas reglas que diverjan.
 */
class AnalyticsAccumulator(
    /** Cátedra analizada. */
    val course: Course,
    /** Inscripciones: dan el rol y la visibilidad de cada persona. */
    enrollments: List<Enrollment> = emptyList(),
) {

    // ------------------------------------------------------------------
    // Estado interno
    // ------------------------------------------------------------------

    /** Actividad de un día para un usuario. */
    private class DayCounts(
        /** Mensajes escritos por el usuario ese día. */
        var messages: Int = 0,
        /** Respuestas (todo lo que no sea del usuario) ese día. */
        var replies: Int = 0,
        /** Cualquier mensaje, sea del rol que sea: marca el día como activo. */
        var activity: Int = 0,
    )

    /** Todo lo que se sabe de un usuario: su "cubo". */
    private class UserBucket(
        val userId: String,
        /** Mensajes escritos por el usuario. */
        var messages: Int = 0,
        /** Respuestas del modelo recibidas. */
        var replies: Int = 0,
        var tokensIn: Int = 0,
        var tokensOut: Int = 0,
        /** Suma de latencias (para la media). */
        var latencySum: Long = 0L,
        var citationsVerified: Int = 0,
        var citationsUnsupported: Int = 0,
        var citationsUnknown: Int = 0,
        /** Respuestas con al menos una cita sin respaldo. */
        var answersWithUnsupported: Int = 0,
        /** Respuestas evaluables (con material en el contexto o con citas). */
        var groundableReplies: Int = 0,
        /** Respuestas evaluables sin fundamento verificable. */
        var answersWithoutGrounding: Int = 0,
        var toolCalls: Int = 0,
        var toolFailures: Int = 0,
        /** Instante del último mensaje (cualquier rol). */
        var lastActivity: Long = 0L,
        /** Último `seq` incorporado (0 si el store no usa secuencia). */
        var lastSeq: Long = 0L,
    ) {
        /** Hilos del usuario: cada conversación abierta cuenta una. */
        val threadIds: MutableSet<String> = HashSet()

        /** Día (medianoche UTC) → actividad de ese día. */
        val days: MutableMap<Long, DayCounts> = HashMap()

        /** Etiqueta de fuente → cuántas veces se consultó. */
        val sources: MutableMap<String, Int> = HashMap()
    }

    /** Cubo por usuario. `LinkedHashMap` para conservar el orden de carga. */
    private val users = LinkedHashMap<String, UserBucket>()

    /**
     * Inscripciones por persona, en el orden en que las entrega el store.
     *
     * Una persona puede tener más de una inscripción (por ejemplo, una como
     * alumno y otra como ayudante): se guardan todas y se resuelven al
     * congelar, porque de eso depende qué se ve.
     */
    private val enrollmentsByUser = LinkedHashMap<String, MutableList<Enrollment>>()

    /** Autor de cada hilo: permite atribuir respuestas y fuentes. */
    private val threadAuthors = HashMap<String, String>()

    /** Último `seq` incorporado (0 si el store no usa secuencia). */
    var appendSeq: Long = 0L
        private set

    /** Cantidad de mensajes incorporados al acumulador. */
    var messageCount: Long = 0L
        private set

    init {
        enrollments.forEach { onEnrollment(it) }
    }

    /** Identificador de la cátedra. */
    val courseId: String get() = course.id

    // ------------------------------------------------------------------
    // Ingesta incremental
    // ------------------------------------------------------------------

    /**
     * Registra un hilo del autor.
     *
     * Un hilo cuenta como conversación aunque no tenga mensajes: el docente
     * tiene que ver que el alumno abrió un chat y no escribió.
     */
    fun onThread(thread: ChatThread) {
        if (!esDeEstaCatedra(thread)) return
        threadAuthors[thread.id] = thread.authorId
        bucketOf(thread.authorId).threadIds.add(thread.id)
    }

    /**
     * Incorpora un mensaje nuevo. **O(1) amortizado.**
     *
     * @param thread hilo al que pertenece el mensaje (de ahí sale el autor).
     * @param message mensaje nuevo.
     * @param seq secuencia en el log (0 si el store no la usa).
     */
    fun add(thread: ChatThread, message: ChatMessage, seq: Long = 0L) {
        if (!esDeEstaCatedra(thread)) return
        onThread(thread)
        onMessage(message, thread.authorId, seq)
    }

    /**
     * Incorpora un mensaje cuyo autor ya se conoce.
     *
     * @param authorId autor del **hilo** (no del mensaje): las respuestas del
     *   modelo pertenecen a la conversación del alumno y se le atribuyen a él.
     */
    fun onMessage(message: ChatMessage, authorId: String, seq: Long = 0L) {
        val b = bucketOf(authorId)
        val dia = dayStart(message.createdAt)
        val d = b.days.getOrPut(dia) { DayCounts() }

        // Cualquier mensaje marca el día como activo (aunque sea del sistema).
        d.activity++

        if (message.role == ROLE_USER) {
            b.messages++
            d.messages++
            b.tokensIn += message.tokensIn
        } else {
            // El gráfico por día cuenta como "respuesta" todo lo que no sea
            // del usuario (regla heredada del analizador histórico: un rol
            // nuevo cuenta como respuesta del modelo).
            d.replies++
        }

        if (message.role == ROLE_ASSISTANT) {
            b.replies++
            b.tokensOut += message.tokensOut
            b.latencySum += message.latencyMs
            val cita = message.citation
            cita?.let { c ->
                b.citationsVerified += c.verified
                b.citationsUnsupported += c.unsupported
                b.citationsUnknown += c.unknown
                if (c.unsupported > 0 || c.unknown > 0) b.answersWithUnsupported++
            }
            // Una respuesta sólo se puede evaluar si el modelo tuvo material
            // en el contexto o si alguien verificó sus citas. Una respuesta
            // de chat general no es "mala": no hay nada que evaluar.
            val evaluable = message.sources.isNotEmpty() || cita != null
            if (evaluable) {
                b.groundableReplies++
                // Fundamentada = al menos una cita verificada, cero sin
                // respaldo y cero inexistentes. Una respuesta que no citó
                // nada **no** está fundamentada: "no cité nada" no es
                // "respondí con respaldo".
                val fundamentada = cita != null &&
                    cita.verified >= 1 &&
                    cita.unsupported == 0 &&
                    cita.unknown == 0
                if (!fundamentada) b.answersWithoutGrounding++
            }
            b.toolCalls += message.toolCalls.size
            b.toolFailures += message.toolCalls.count { it.ok == false }
            // Las fuentes se atribuyen al **autor del hilo**: el material que
            // consultó una conversación es de ese alumno, no del modelo.
            for (fuente in message.sources) {
                b.sources[fuente] = (b.sources[fuente] ?: 0) + 1
            }
        }

        if (message.createdAt > b.lastActivity) b.lastActivity = message.createdAt
        if (seq > b.lastSeq) b.lastSeq = seq
        messageCount++
        if (seq > appendSeq) appendSeq = seq
    }

    /**
     * Alta o cambio de inscripción (rol, visibilidad, nombre).
     *
     * La inscripción se reemplaza por su id: si el alumno cambió la
     * visibilidad, lo que vale es la última, no las dos.
     */
    fun onEnrollment(enrollment: Enrollment) {
        val lista = enrollmentsByUser.getOrPut(enrollment.userId) { mutableListOf() }
        val i = lista.indexOfFirst { it.id == enrollment.id }
        if (i >= 0) lista[i] = enrollment else lista.add(enrollment)
    }

    /**
     * Baja de inscripción: la persona desaparece de los agregados.
     *
     * Su cubo se conserva: si vuelve a inscribirse, el histórico sigue ahí.
     */
    fun removeEnrollment(userId: String) {
        enrollmentsByUser.remove(userId)
    }

    // ------------------------------------------------------------------
    // Salida
    // ------------------------------------------------------------------

    /**
     * Congela el estado acumulado en un [CourseAnalytics].
     *
     * **Acá se aplica la visibilidad**: el acumulador calculó para todos y el
     * freeze decide a quién se le muestra. Un alumno con
     * [ChatVisibility.NONE] no aparece en ningún agregado (ni en el listado,
     * ni en los totales, ni en la actividad por día, ni en las fuentes).
     *
     * Costo: O(inscripciones·(días + fuentes)). Es barato a propósito: se
     * puede congelar tantas veces como haga falta.
     */
    fun freeze(): CourseAnalytics {
        val visibles = visibles()

        // El orden es el que espera la UI: de más mensajes a menos. El
        // ordenamiento es estable, así que los empates conservan el orden de
        // las inscripciones.
        val porAlumno = visibles
            .map { e -> studentAnalytics(e, users[e.userId]) }
            .sortedByDescending { it.messages }

        val alumnos = porAlumno.filter { it.role == CourseRole.ALUMNO }
        val docentes = porAlumno.count { it.role != CourseRole.ALUMNO }

        val totalMessages = alumnos.sumOf { it.messages }
        val totalReplies = alumnos.sumOf { it.replies }
        val activos = alumnos.count { it.messages > 0 }

        val citasVerificadas = alumnos.sumOf { it.citationsVerified }
        val citasSinRespaldo = alumnos.sumOf { it.citationsUnsupported }
        val citasDesconocidas = alumnos.sumOf { it.citationsUnknown }
        val citasTotales = citasVerificadas + citasSinRespaldo + citasDesconocidas

        // La latencia media de la cátedra pondera por respuestas, pero
        // truncando antes la media de cada alumno (regla heredada del
        // analizador histórico: los números que ya vio el docente no cambian).
        val latenciaTotal = alumnos.sumOf { it.avgLatencyMs * it.replies }
        val latenciaMedia = if (totalReplies == 0) 0L else latenciaTotal / totalReplies

        return CourseAnalytics(
            courseId = course.id,
            students = alumnos.size,
            activeStudents = activos,
            teachers = docentes,
            totalMessages = totalMessages,
            totalReplies = totalReplies,
            totalThreads = alumnos.sumOf { it.threads },
            avgMessagesPerStudent = if (alumnos.isEmpty()) 0.0
            else (totalMessages.toDouble() / alumnos.size).roundToInt().toDouble(),
            citationSupportRate = if (citasTotales == 0) 0.0
            else citasVerificadas.toDouble() / citasTotales,
            citationsVerified = citasVerificadas,
            citationsUnsupported = citasSinRespaldo,
            citationsUnknown = citasDesconocidas,
            avgLatencyMs = latenciaMedia,
            totalTokens = alumnos.sumOf { it.tokensIn + it.tokensOut },
            perStudent = porAlumno,
            perDay = aggregateDays(visibles),
            topSources = aggregateSources(visibles),
            inactiveStudents = alumnos.filter { it.messages == 0 }.map { it.displayName },
        )
    }

    /**
     * Métricas de un usuario (para el panel "Mi actividad" del alumno).
     *
     * **No** aplica visibilidad: el alumno ve lo suyo aunque su inscripción
     * esté oculta para el docente. La visibilidad gobierna lo que ve el
     * *docente*, no lo que ve el alumno sobre sí mismo.
     */
    fun studentOf(userId: String): StudentAnalytics? {
        val e = inscripcionDe(userId) ?: return null
        return studentAnalytics(e, users[userId])
    }

    // ------------------------------------------------------------------
    // Estado serializable (lo usa la caché)
    // ------------------------------------------------------------------

    /** Estado del acumulador, listo para serializar. */
    fun toState(): AnalyticsAccumulatorState = AnalyticsAccumulatorState(
        courseId = course.id,
        appendSeq = appendSeq,
        messageCount = messageCount,
        users = users.values.map { b ->
            AnalyticsUserState(
                userId = b.userId,
                messages = b.messages,
                replies = b.replies,
                tokensIn = b.tokensIn,
                tokensOut = b.tokensOut,
                latencySum = b.latencySum,
                citationsVerified = b.citationsVerified,
                citationsUnsupported = b.citationsUnsupported,
                citationsUnknown = b.citationsUnknown,
                answersWithUnsupported = b.answersWithUnsupported,
                groundableReplies = b.groundableReplies,
                answersWithoutGrounding = b.answersWithoutGrounding,
                toolCalls = b.toolCalls,
                toolFailures = b.toolFailures,
                lastActivity = b.lastActivity,
                lastSeq = b.lastSeq,
                threadIds = b.threadIds.toList(),
                days = b.days.map { (dia, d) ->
                    AnalyticsDayState(dia, d.messages, d.replies, d.activity)
                },
                sources = b.sources.map { (etiqueta, n) -> AnalyticsSourceState(etiqueta, n) },
            )
        },
        enrollments = enrollmentsByUser.values.flatten(),
        threadAuthors = threadAuthors.toMap(),
    )

    // ------------------------------------------------------------------
    // Internos
    // ------------------------------------------------------------------

    private fun bucketOf(userId: String): UserBucket =
        users.getOrPut(userId) { UserBucket(userId) }

    /** Un hilo de otra cátedra no entra en los agregados de ésta. */
    private fun esDeEstaCatedra(thread: ChatThread): Boolean =
        thread.courseId == course.id

    /**
     * Las inscripciones que el docente puede ver, **una fila por persona**.
     *
     * - Si alguna inscripción de la persona dice [ChatVisibility.NONE], no
     *   aparece en ninguna lista: pidió que no se lo vieran y eso vale más
     *   que cualquier otro agregado.
     * - Entre las que sí se pueden ver, manda la de rol [CourseRole.ALUMNO]:
     *   una persona inscrita como alumno cuenta como alumno.
     */
    private fun visibles(): List<Enrollment> =
        enrollmentsByUser.values.mapNotNull { candidatas ->
            if (candidatas.any { !it.visibility.showsMetrics }) return@mapNotNull null
            candidatas.firstOrNull { it.role == CourseRole.ALUMNO } ?: candidatas.first()
        }

    /** La inscripción que describe a una persona (sin aplicar visibilidad). */
    private fun inscripcionDe(userId: String): Enrollment? {
        val candidatas = enrollmentsByUser[userId] ?: return null
        return candidatas.firstOrNull { it.role == CourseRole.ALUMNO } ?: candidatas.first()
    }

    /** Métricas de un alumno a partir de su cubo (o de ceros si no chatted). */
    private fun studentAnalytics(e: Enrollment, b: UserBucket?): StudentAnalytics {
        if (b == null) {
            return StudentAnalytics(userId = e.userId, displayName = e.displayName, role = e.role)
        }
        val replies = b.replies
        return StudentAnalytics(
            userId = e.userId,
            // El nombre de la inscripción manda sobre el del hilo.
            displayName = e.displayName,
            role = e.role,
            messages = b.messages,
            replies = replies,
            threads = b.threadIds.size,
            activeDays = b.days.size,
            lastActivity = b.lastActivity,
            avgLatencyMs = if (replies == 0) 0L else b.latencySum / replies,
            tokensIn = b.tokensIn,
            tokensOut = b.tokensOut,
            citationsVerified = b.citationsVerified,
            citationsUnsupported = b.citationsUnsupported,
            citationsUnknown = b.citationsUnknown,
            toolCalls = b.toolCalls,
            toolFailures = b.toolFailures,
            answersWithUnsupportedCitations = b.answersWithUnsupported,
            groundableReplies = b.groundableReplies,
            answersWithoutGrounding = b.answersWithoutGrounding,
        )
    }

    /**
     * Actividad diaria agregada.
     *
     * Sólo cuenta la actividad de los **alumnos**: la de los docentes se
     * reporta aparte, si no un profesor probando el sistema infla el gráfico
     * de actividad de los alumnos (y la suma del gráfico no cuadra con los
     * totales).
     */
    private fun aggregateDays(visibles: List<Enrollment>): List<DayActivity> {
        val porDia = HashMap<Long, IntArray>()
        for (e in visibles) {
            if (e.role != CourseRole.ALUMNO) continue
            val b = users[e.userId] ?: continue
            for ((dia, d) in b.days) {
                val acc = porDia.getOrPut(dia) { IntArray(2) }
                acc[0] += d.messages
                acc[1] += d.replies
            }
        }
        return porDia.entries.sortedBy { it.key }
            .map { DayActivity(it.key, it.value[0], it.value[1]) }
    }

    /**
     * Fuentes más consultadas (sólo de las personas visibles).
     *
     * Las fuentes se guardan **por usuario** justamente para poder aplicar acá
     * la visibilidad: las fuentes que consultó un alumno oculto no pueden
     * filtrar al docente qué material leyó ese alumno.
     */
    private fun aggregateSources(visibles: List<Enrollment>): List<SourceUsage> {
        val conteo = HashMap<String, Int>()
        for (e in visibles) {
            val b = users[e.userId] ?: continue
            for ((etiqueta, n) in b.sources) conteo[etiqueta] = (conteo[etiqueta] ?: 0) + n
        }
        return conteo.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(10)
            .map { SourceUsage(it.key, it.value) }
    }

    /**
     * Medianoche (UTC) del día de un instante.
     *
     * Se usa como clave de agrupación: dos mensajes del mismo día caen en la
     * misma entrada. Es una copia local de la regla del analizador histórico
     * para que el acumulador no dependa de otro archivo.
     */
    private fun dayStart(millis: Long): Long {
        val dayMillis = 86_400_000L
        return ((millis / dayMillis) + if (millis % dayMillis < 0) -1 else 0) * dayMillis
    }

    companion object {

        /** Rol del mensaje que escribe el usuario. */
        private const val ROLE_USER = "user"

        /** Rol del mensaje que escribe el modelo. */
        private const val ROLE_ASSISTANT = "assistant"

        /**
         * Analiza una cátedra con el acumulador (replay + freeze).
         *
         * Es el **camino puro**: la misma firma que
         * [AnalyticsAnalyzer.analyze] y los mismos números, pero calculados
         * con la implementación de referencia. La UI y los tests pueden usar
         * cualquiera de los dos: no pueden divergir porque es el mismo
         * código.
         */
        fun analyze(
            course: Course,
            enrollments: List<Enrollment>,
            threads: List<ChatThread>,
            messages: List<ChatMessage>,
        ): CourseAnalytics {
            val acc = AnalyticsAccumulator(course, enrollments)
            // Los hilos de otra cátedra no se mezclan: el mismo alumno puede
            // estar en dos materias.
            val hilos = threads.filter { it.courseId == course.id }
            val autorDe = hilos.associate { it.id to it.authorId }
            hilos.forEach(acc::onThread)
            for (m in messages) {
                // Un mensaje sin hilo no se le puede atribuir a nadie.
                val autor = autorDe[m.threadId] ?: continue
                acc.onMessage(m, autor)
            }
            return acc.freeze()
        }

        /**
         * Reconstruye un acumulador desde un estado serializado.
         *
         * Es lo que hace la caché al cargar: se retoma el estado sin releer
         * los mensajes.
         */
        fun fromState(
            course: Course,
            state: AnalyticsAccumulatorState,
        ): AnalyticsAccumulator {
            val acc = AnalyticsAccumulator(course)
            for (e in state.enrollments) acc.onEnrollment(e)
            for (u in state.users) {
                val b = UserBucket(u.userId)
                b.messages = u.messages
                b.replies = u.replies
                b.tokensIn = u.tokensIn
                b.tokensOut = u.tokensOut
                b.latencySum = u.latencySum
                b.citationsVerified = u.citationsVerified
                b.citationsUnsupported = u.citationsUnsupported
                b.citationsUnknown = u.citationsUnknown
                b.answersWithUnsupported = u.answersWithUnsupported
                b.groundableReplies = u.groundableReplies
                b.answersWithoutGrounding = u.answersWithoutGrounding
                b.toolCalls = u.toolCalls
                b.toolFailures = u.toolFailures
                b.lastActivity = u.lastActivity
                b.lastSeq = u.lastSeq
                b.threadIds.addAll(u.threadIds)
                for (d in u.days) {
                    b.days[d.day] = DayCounts(d.messages, d.replies, d.activity)
                }
                for (s in u.sources) {
                    b.sources[s.label] = s.times
                }
                acc.users[u.userId] = b
            }
            acc.threadAuthors.putAll(state.threadAuthors)
            acc.appendSeq = state.appendSeq
            acc.messageCount = state.messageCount
            return acc
        }
    }
}

/**
 * Estado serializable de un [AnalyticsAccumulator].
 *
 * Es lo que se guarda en disco: los contadores agregados, **no** los
 * mensajes. Por eso el archivo no crece con la cantidad de chats.
 */
@Serializable
data class AnalyticsAccumulatorState(
    val courseId: String = "",
    /** Último `seq` incorporado al acumulador. */
    val appendSeq: Long = 0L,
    /** Mensajes incorporados. */
    val messageCount: Long = 0L,
    /** Un cubo por usuario. */
    val users: List<AnalyticsUserState> = emptyList(),
    /** Inscripciones (rol y visibilidad) al momento del guardado. */
    val enrollments: List<Enrollment> = emptyList(),
    /** Hilo → autor. */
    val threadAuthors: Map<String, String> = emptyMap(),
)

/** Estado serializable del cubo de un usuario. */
@Serializable
data class AnalyticsUserState(
    val userId: String,
    val messages: Int = 0,
    val replies: Int = 0,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val latencySum: Long = 0L,
    val citationsVerified: Int = 0,
    val citationsUnsupported: Int = 0,
    val citationsUnknown: Int = 0,
    val answersWithUnsupported: Int = 0,
    val groundableReplies: Int = 0,
    val answersWithoutGrounding: Int = 0,
    val toolCalls: Int = 0,
    val toolFailures: Int = 0,
    val lastActivity: Long = 0L,
    val lastSeq: Long = 0L,
    val threadIds: List<String> = emptyList(),
    val days: List<AnalyticsDayState> = emptyList(),
    val sources: List<AnalyticsSourceState> = emptyList(),
)

/** Actividad de un usuario en un día. */
@Serializable
data class AnalyticsDayState(
    val day: Long,
    val messages: Int = 0,
    val replies: Int = 0,
    val activity: Int = 0,
)

/** Veces que un usuario consultó una fuente. */
@Serializable
data class AnalyticsSourceState(
    val label: String,
    val times: Int = 0,
)
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     