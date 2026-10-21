package kitoru.domain

import kotlinx.serialization.Serializable

/**
 * Nodo de la taxonomía de la cátedra.
 *
 * La taxonomía tiene **dos niveles**: la unidad ("Unidad 2 — Modelos lineales")
 * y el tema ("Tema 2.1 — Regresión"). El nivel se deduce de la posición en el
 * árbol y no de un campo: la raíz es una unidad y sus hijos son temas. Así el
 * modelo de datos no se rompe si una cátedra organiza sus materiales con un
 * nivel más.
 *
 * La taxonomía es **opcional**: una cátedra que no carga unidades sigue
 * funcionando igual (el material se indexa sin ruta y la cita degrada a
 * "Título > Sección"). Por eso todos los campos tienen valor por defecto y el
 * material no tiene ningún campo obligatorio que apunte a una unidad.
 *
 * @param id id del nodo.
 * @param courseId cátedra a la que pertenece.
 * @param parentId id del padre; `null` si el nodo es una raíz (una unidad).
 * @param name nombre del nodo ("Modelos lineales", "Regresión").
 * @param code código corto que escribe el docente ("2", "2.1").
 * @param order posición dentro del padre (1, 2, 3...).
 * @param description texto libre para el docente.
 * @param createdAt momento de creación (milisegundos desde epoch).
 */
@Serializable
data class Unit(
    val id: String,
    val courseId: String,
    /** Id del padre. `null` = raíz: el nodo es una "Unidad". */
    val parentId: String? = null,
    /** Nombre del nodo: "Modelos lineales", "Regresión". */
    val name: String = "",
    /** Código corto que escribe el docente: "2", "2.1". */
    val code: String = "",
    /** Posición dentro del padre: 1, 2, 3… */
    val order: Int = 0,
    /** Descripción libre para el docente (no entra al RAG). */
    val description: String = "",
    /** Momento de creación (milisegundos desde epoch). */
    val createdAt: Long = 0L,
)

/**
 * Árbol de unidades de una cátedra.
 *
 * Son consultas **puras** sobre la lista de unidades: no tocan disco, no
 * llaman al modelo y no leen el reloj, así que se testean al 100 %.
 *
 * Hay dos formas de construirlo:
 *  - [of] valida la integridad y falla rápido si los datos están rotos (es la
 *    que usa el docente cuando guarda la taxonomía);
 *  - [lenient] no valida, para leer datos ya guardados sin que una fila mala
 *    impida abrir la cátedra.
 */
class UnitTree private constructor(
    /** Todas las unidades de la cátedra, en el orden en que las cargó el docente. */
    val units: List<Unit>,
) {

    private val byId: Map<String, Unit> = units.associateBy { it.id }

    private val byParent: Map<String?, List<Unit>> =
        units.groupBy { it.parentId }
            .mapValues { (_, hijos) -> hijos.sortedWith(compareBy({ it.order }, { it.code }, { it.name })) }

    companion object {
        /**
         * Construye el árbol validando la integridad.
         *
         * @param units unidades de la cátedra.
         * @return el árbol listo para usar.
         * @throws IllegalStateException si alguna unidad apunta a un padre que
         *   no existe, si una unidad es su propio padre o si el árbol tiene un
         *   ciclo de padres. El mensaje trae la lista completa de problemas.
         */
        fun of(units: List<Unit>): UnitTree {
            val tree = UnitTree(units)
            val problemas = tree.problems()
            if (problemas.isNotEmpty()) {
                throw IllegalStateException(
                    "El árbol de unidades está roto: ${problemas.joinToString("; ")}.",
                )
            }
            return tree
        }

        /**
         * Construye el árbol sin validar.
         *
         * Se usa al leer datos guardados: un material que apunta a una unidad
         * borrada no puede impedir que la cátedra abra.
         */
        fun lenient(units: List<Unit>): UnitTree = UnitTree(units)
    }

    /** La unidad con ese id, o `null` si no existe. */
    fun unit(unitId: String): Unit? = byId[unitId]

    /** Las unidades raíz (las "Unidades"), ordenadas. */
    fun roots(): List<Unit> = children(null)

    /**
     * Los hijos directos de un padre, ordenados por posición.
     *
     * @param parentId id del padre; `null` para las raíces.
     */
    fun children(parentId: String?): List<Unit> = byParent[parentId].orEmpty()

    /**
     * La ruta de una unidad: desde la raíz hasta ella, incluida.
     *
     * @param unitId id de la unidad.
     * @return la lista de unidades de la ruta, o una lista vacía si el id no existe.
     */
    fun path(unitId: String): List<Unit> {
        val camino = mutableListOf<Unit>()
        val vistos = mutableSetOf<String>()
        var actual: String? = unitId
        while (actual != null && vistos.add(actual)) {
            val unidad = byId[actual] ?: break
            camino += unidad
            actual = unidad.parentId
        }
        return camino.reversed()
    }

    /** Los ids de la ruta: `["u2", "u2.1"]`. */
    fun pathIds(unitId: String): List<String> = path(unitId).map { it.id }

    /** Las etiquetas de la ruta: `["Unidad 2", "Tema 2.1"]`. */
    fun pathLabels(unitId: String): List<String> = path(unitId).map { label(it.id) }

    /** La ruta como texto: `"Unidad 2 > Tema 2.1"`. */
    fun pathLabel(unitId: String): String = pathLabels(unitId).joinToString(" > ")

    /** Profundidad del nodo (0 = raíz, 1 = tema). */
    fun depth(unitId: String): Int = path(unitId).size - 1

    /**
     * La unidad y todos sus descendientes (ids).
     *
     * Se usa para el filtro del RAG: "repasá la unidad 2" tiene que traer
     * también los temas que cuelgan de ella.
     *
     * @param unitId id de la unidad.
     * @return los ids de la unidad y sus descendientes; lista vacía si la unidad no existe.
     */
    fun descendants(unitId: String): List<String> {
        if (unitId !in byId) return emptyList()
        val out = mutableListOf(unitId)
        var frontera = listOf(unitId)
        while (frontera.isNotEmpty()) {
            val nuevos = frontera.flatMap { children(it) }.map { it.id }.filter { it !in out }
            if (nuevos.isEmpty()) break
            out += nuevos
            frontera = nuevos
        }
        return out
    }

    /**
     * ¿Es [unitId] la misma unidad o un descendiente de [ancestorId]?
     *
     * @param unitId id de la unidad que se pregunta.
     * @param ancestorId id del posible ancestro.
     */
    fun isUnder(unitId: String, ancestorId: String): Boolean = path(unitId).any { it.id == ancestorId }

    /**
     * El número del nodo: el código que escribió el docente o, si no hay, el
     * derivado de la posición ("2", "2.1").
     */
    fun number(unitId: String): String = number(unitId, mutableSetOf())

    private fun number(unitId: String, vistos: MutableSet<String>): String {
        // El corte por `vistos` evita que un ciclo de padres se convierta en
        // una recursión infinita cuando el árbol se construyó con [lenient].
        if (!vistos.add(unitId)) return ""
        val unidad = byId[unitId] ?: return ""
        if (unidad.code.isNotBlank()) return unidad.code.trim()
        val padre = unidad.parentId ?: return if (unidad.order > 0) unidad.order.toString() else ""
        val numeroPadre = number(padre, vistos)
        return if (numeroPadre.isBlank()) "" else "$numeroPadre.${unidad.order}"
    }

    /**
     * La etiqueta corta del nodo: `"Unidad 2"`, `"Tema 2.1"`.
     *
     * Es la etiqueta que va en la cita del RAG, así que tiene que ser corta y
     * reconocible de un vistazo.
     */
    fun label(unitId: String): String {
        val unidad = byId[unitId] ?: return ""
        val nivel = when (depth(unitId)) {
            0 -> "Unidad"
            1 -> "Tema"
            else -> "Apartado"
        }
        val numero = number(unitId)
        return if (numero.isBlank()) nivel else "$nivel $numero"
    }

    /** La unidad raíz con esa posición (la segunda unidad → `order == 2`). */
    fun rootByOrder(order: Int): Unit? = roots().firstOrNull { it.order == order }

    /** La unidad con ese código ("2.1"), sin distinguir mayúsculas. */
    fun byCode(code: String): Unit? {
        val limpio = code.trim()
        if (limpio.isEmpty()) return null
        return units.firstOrNull { it.code.trim().equals(limpio, ignoreCase = true) }
    }

    /** El hijo [order] del padre [parentId] ("el segundo tema de la unidad 2"). */
    fun childByOrder(parentId: String, order: Int): Unit? = children(parentId).firstOrNull { it.order == order }

    /**
     * Los problemas de consistencia del árbol.
     *
     * Se devuelven **todos**, no sólo el primero: el docente tiene que ver el
     * panorama completo de lo que está roto, no un error por vez.
     */
    fun problems(): List<String> {
        val problemas = mutableListOf<String>()
        for (unidad in units) {
            val padre = unidad.parentId
            if (padre != null && padre !in byId) {
                problemas += "«${label(unidad.id)}» apunta a un padre que no existe ($padre)."
            }
            if (padre == unidad.id) {
                problemas += "«${label(unidad.id)}» es su propio padre."
            }
        }
        for (unidad in units) {
            if (isInCycle(unidad.id)) {
                problemas += "«${label(unidad.id)}» está en un ciclo de padres."
            }
        }
        return problemas
    }

    /**
     * Valida el árbol.
     *
     * @throws IllegalStateException con la lista completa de problemas.
     */
    fun validate() {
        val problemas = problems()
        if (problemas.isNotEmpty()) {
            throw IllegalStateException(
                "El árbol de unidades está roto: ${problemas.joinToString("; ")}.",
            )
        }
    }

    private fun isInCycle(unitId: String): Boolean {
        val vistos = mutableSetOf<String>()
        var actual = byId[unitId]?.parentId
        while (actual != null) {
            if (!vistos.add(actual)) return true
            actual = byId[actual]?.parentId
        }
        return false
    }
}

/**
 * CONVENCIÓN DE ASIGNACIÓN DE UNIDADES POR ETIQUETAS
 *
 * El docente no tiene que elegir unidades en un selector: escribe una
 * etiqueta en el material y el sistema resuelve el id.
 *
 *   "unidad:2"  →  la unidad raíz con `order == 2` (la segunda unidad).
 *   "tema:2.1"  →  el tema con `code == "2.1"`. Si el docente no escribió
 *                  códigos, se resuelve como "el tema `order == 1` de la
 *                  unidad `order == 2`": la parte antes del punto es la unidad
 *                  y la de después, el tema.
 *
 * Las etiquetas son texto libre: el resto ("examen", "parcial") no asigna
 * unidades pero sí pesa en la recuperación (ver `RetrievalBoost`).
 *
 * Se pueden escribir varias etiquetas de asignación: la primera que resuelve
 * es la unidad **primaria** (la que va en la cita del RAG) y las demás son
 * "también aparece en".
 */
fun assignUnits(material: Material, tree: UnitTree): List<String> {
    val ids = mutableListOf<String>()
    for (etiqueta in material.tags) {
        val referencia = parseUnitTag(etiqueta) ?: continue
        val id = resolveUnitTag(referencia, tree) ?: continue
        if (id !in ids) ids += id
    }
    return ids
}

/** Referencia de una etiqueta de asignación: "unidad:2", "tema:2.1". */
private data class UnitTagRef(val tipo: String, val valor: String)

private val RE_TAG_UNIDAD = Regex("""^\s*unidad\s*:\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)
private val RE_TAG_TEMA = Regex("""^\s*tema\s*:\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)

/** Interpreta una etiqueta como referencia a una unidad, o `null` si no lo es. */
private fun parseUnitTag(tag: String): UnitTagRef? {
    RE_TAG_UNIDAD.matchEntire(tag)?.let { return UnitTagRef("unidad", it.groupValues[1]) }
    RE_TAG_TEMA.matchEntire(tag)?.let { return UnitTagRef("tema", it.groupValues[1]) }
    return null
}

/** Resuelve la referencia a un id de unidad del árbol, o `null` si no existe. */
private fun resolveUnitTag(referencia: UnitTagRef, tree: UnitTree): String? = when (referencia.tipo) {
    "unidad" -> resolveUnidadTag(referencia.valor, tree)
    else -> resolveTemaTag(referencia.valor, tree)
}

private fun resolveUnidadTag(valor: String, tree: UnitTree): String? {
    val numero = valor.toIntOrNull()
    if (numero != null) {
        tree.rootByOrder(numero)?.let { return it.id }
    }
    return tree.roots().firstOrNull { it.code.trim().equals(valor.trim(), ignoreCase = true) }?.id
}

private fun resolveTemaTag(valor: String, tree: UnitTree): String? {
    // Primero se busca un código literal ("2.1"): es lo que escribe el docente.
    tree.byCode(valor)?.let { return it.id }
    // Si no hay códigos, "2.1" se lee como "el tema 1 de la unidad 2".
    val partes = valor.trim().split('.')
    if (partes.size != 2) return null
    val numeroUnidad = partes[0].toIntOrNull() ?: return null
    val numeroTema = partes[1].toIntOrNull() ?: return null
    val unidad = tree.rootByOrder(numeroUnidad)
        ?: tree.roots().firstOrNull { it.code.trim() == numeroUnidad.toString() }
        ?: return null
    return tree.childByOrder(unidad.id, numeroTema)?.id
}

/**
 * Un evento del cronograma de la cátedra.
 *
 * El cronograma lo carga el docente a mano: no hay feriados precargados ni
 * fechas calculadas, porque cada cátedra tiene su propio calendario y una
 * fecha inventada es peor que una fecha ausente.
 *
 * Es opcional: una cátedra puede no tener cronograma. Cuando lo tiene, sirve
 * para dos cosas que sin él no se pueden hacer:
 *  - ubicar en el tiempo lo que el alumno pregunta ("la clase de la semana
 *    pasada");
 *  - atribuir preguntas y respuestas a una semana en la analítica del docente.
 *
 * @param id id del evento.
 * @param courseId cátedra a la que pertenece.
 * @param title título del evento ("Clase 3: Paradigmas").
 * @param date fecha del evento en formato ISO "2026-04-06" (opcional).
 * @param week semana de la cursada (1 = la primera).
 * @param unitIds unidades que se ven en este evento.
 * @param materialIds materiales de apoyo del evento.
 */
@Serializable
data class ScheduleEntry(
    val id: String,
    val courseId: String,
    /** Título del evento: "Clase 3: Paradigmas". */
    val title: String = "",
    /** Fecha del evento en formato ISO "2026-04-06" (opcional). */
    val date: String = "",
    /** Semana de la cursada (1 = la primera). */
    val week: Int = 0,
    /** Unidades que se ven en este evento. */
    val unitIds: List<String> = emptyList(),
    /** Materiales de apoyo del evento. */
    val materialIds: List<String> = emptyList(),
) {
    /** "Semana 3 — Clase 3: Paradigmas". */
    val label: String get() = if (title.isBlank()) "Semana $week" else "Semana $week — $title"
}

/**
 * Compone la etiqueta de cita de un fragmento con la jerarquía de la cátedra.
 *
 * "Unidad 2 > Tema 2.1 > Apuntes-IA > Regresión lineal"
 *
 * La cita es lo que el alumno lee y lo que el modelo recibe entre paréntesis:
 * con la jerarquía, el alumno sabe **de dónde** sale la respuesta y puede
 * buscarla en sus apuntes en vez de reciba un fragmento suelto.
 *
 * @param unitPath etiquetas de la ruta de unidades (puede estar vacío si el
 *   material no está asignado a ninguna unidad).
 * @param materialTitle título del material.
 * @param sectionHeading encabezado de la sección dentro del material (puede
 *   estar vacío si el documento no tiene encabezados).
 * @return la etiqueta compuesta, con las partes separadas por " > ".
 */
fun citationLabel(unitPath: List<String>, materialTitle: String, sectionHeading: String): String {
    val partes = mutableListOf<String>()
    for (etiqueta in unitPath) {
        val limpia = etiqueta.trim()
        if (limpia.isNotEmpty()) partes += limpia
    }
    val titulo = materialTitle.trim()
    if (titulo.isNotEmpty()) partes += titulo
    val seccion = sectionHeading.trim()
    // El encabezado no se repite cuando dice lo mismo que el material
    // ("Apuntes > Apuntes" no le dice nada al alumno).
    if (seccion.isNotEmpty() && seccion !in partes) partes += seccion
    return partes.joinToString(" > ")
}
