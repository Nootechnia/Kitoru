package kitoru.domain

import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kitoru.platform.KitoruPaths
import kitoru.platform.nowMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Caché en disco del estado de un [AnalyticsAccumulator].
 *
 * Guarda el acumulado **serializado** (JSON con [KitoruJson]) junto con una
 * [AnalyticsWatermark] que dice hasta dónde llegan los números guardados.
 *
 * Reglas:
 *
 *  - **La caché es una optimización, nunca una fuente de verdad.** Si falta,
 *    está corrupta o es de otra versión, se descarta y se recalcula: la app
 *    nunca se rompe por una caché.
 *  - **El formato está versionado.** Si la versión no coincide, el archivo se
 *    borra y se recalcula: los números de dos formatos distintos no son
 *    comparables (cambian las reglas, no los datos).
 *  - **La escritura es atómica en la intención**: se escribe primero un `.tmp`
 *    y recién después se publica el archivo final, así una interrupción a
 *    mitad de camino no destruye la caché anterior.
 */
class AnalyticsCache(
    private val fs: FileSystem,
    /** Carpeta donde viven los archivos de caché. */
    private val baseDir: FilePath = KitoruPaths.cacheDir.resolve("analytics"),
    /**
     * Versión del formato que escribe esta caché.
     *
     * Es un parámetro para poder simular un cambio de formato (una app nueva
     * con otro formato no puede leer los números de la anterior).
     */
    private val formatVersion: Int = FORMAT_VERSION,
) {

    /**
     * Cerrojo de las operaciones de archivo.
     *
     * Un [Mutex] no es reentrante: por eso las funciones públicas lo toman y
     * las internas no.
     */
    private val mutex = Mutex()

    /**
     * Guarda el estado del acumulador.
     *
     * @return true si se pudo escribir; false si falló (disco lleno, permiso
     *   denegado). Un fallo **no** es una excepción: la caché es opcional.
     */
    suspend fun save(accumulator: AnalyticsAccumulator): Boolean = mutex.withLock {
        val payload = AnalyticsCachePayload(
            watermark = AnalyticsWatermark(
                formatVersion = formatVersion,
                courseId = accumulator.courseId,
                appendSeq = accumulator.appendSeq,
                messageCount = accumulator.messageCount,
                builtAt = nowMillis(),
            ),
            state = accumulator.toState(),
        )
        val texto = try {
            KitoruJson.encodeToString(AnalyticsCachePayload.serializer(), payload)
        } catch (e: Exception) {
            // Un estado que no se puede serializar no se guarda: se pierde la
            // caché, no la app.
            return@withLock false
        }
        try {
            escribirAtomico(pathFor(accumulator.courseId), texto)
            true
        } catch (e: Exception) {
            // Disco lleno o permiso denegado: la app sigue funcionando con
            // el acumulador en memoria.
            false
        }
    }

    /**
     * Carga el estado guardado de una cátedra.
     *
     * @return el acumulador restaurado, o `null` si no hay caché, si está
     *   corrupta o si su formato no es el de esta versión (en ese caso se
     *   descarta y hay que recalcular).
     */
    suspend fun load(course: Course): AnalyticsAccumulator? = mutex.withLock {
        val path = pathFor(course.id)
        if (!fs.exists(path)) return@withLock null

        val texto = try {
            fs.readText(path)
        } catch (e: Exception) {
            return@withLock null
        }

        val payload = try {
            KitoruJson.decodeFromString(AnalyticsCachePayload.serializer(), texto)
        } catch (e: Exception) {
            // Caché corrupta (a medias, editada a mano, de otra app): se
            // descarta y se recalcula.
            fs.delete(path)
            return@withLock null
        }

        if (payload.watermark.formatVersion != formatVersion) {
            // Formato de otra versión: los números no son comparables.
            fs.delete(path)
            return@withLock null
        }
        if (payload.watermark.courseId != course.id) {
            // Archivo de otra cátedra (nombre pisado): no se usa.
            fs.delete(path)
            return@withLock null
        }

        AnalyticsAccumulator.fromState(course, payload.state)
    }

    /**
     * Marca de agua de la caché guardada (para diagnóstico y para el
     * catch-up incremental). `null` si no hay caché utilizable.
     */
    suspend fun watermark(courseId: String): AnalyticsWatermark? = mutex.withLock {
        val path = pathFor(courseId)
        if (!fs.exists(path)) return@withLock null
        val texto = try {
            fs.readText(path)
        } catch (e: Exception) {
            return@withLock null
        }
        val payload = try {
            KitoruJson.decodeFromString(AnalyticsCachePayload.serializer(), texto)
        } catch (e: Exception) {
            return@withLock null
        }
        if (payload.watermark.formatVersion != formatVersion) return@withLock null
        payload.watermark
    }

    /** Borra la caché de una cátedra (la cátedra se borró, por ejemplo). */
    suspend fun clear(courseId: String) = mutex.withLock {
        val path = pathFor(courseId)
        if (fs.exists(path)) fs.delete(path)
    }

    /** Ruta del archivo de caché de una cátedra. */
    fun pathFor(courseId: String): FilePath = baseDir.resolve(nombreArchivo(courseId))

    /**
     * Nombre del archivo de caché.
     *
     * El `courseId` crudo puede traer `|`, `/` o `:` (no es un id "sano"), así
     * que se sanitiza y se le anexa una huella del original: dos cátedras con
     * nombres parecidos no pueden terminar en el mismo archivo.
     */
    private fun nombreArchivo(courseId: String): String {
        val limpio = buildString {
            for (c in courseId) {
                append(if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_')
            }
        }.ifEmpty { "catedra" }
        return "$limpio-${huella(courseId)}.json"
    }

    /**
     * Huella FNV-1a de 64 bits.
     *
     * Es estable en las dos plataformas (a diferencia de `String.hashCode()`,
     * que no está garantizado entre runtimes) y no necesita dependencias.
     */
    private fun huella(texto: String): String {
        var h = -3750763034362895579L // base del FNV-1a de 64 bits
        for (c in texto) {
            h = h xor c.code.toLong()
            h *= 1099511628211L // primo del FNV-1a de 64 bits
        }
        return h.toString(16).padStart(16, '0')
    }

    /**
     * Escritura con publicación: se escribe primero en `.tmp` y recién
     * después se publica el archivo final.
     *
     * `FileSystem` no tiene `move`, así que la publicación se emula con
     * `copy` + `delete`. Si la app se corta en el medio, el archivo final
     * puede quedar a medias: no pasa nada, porque [load] valida el JSON y
     * descarta lo que no se puede leer.
     */
    private fun escribirAtomico(path: FilePath, texto: String) {
        val tmp = FilePath(path.value + ".tmp")
        fs.createDirectories(path.parent ?: path)
        fs.writeText(tmp, texto)
        fs.copy(tmp, path)
        fs.delete(tmp)
    }

    companion object {
        /**
         * Versión del formato de la caché.
         *
         * Sube cuando cambia la **forma** de los datos guardados (no cuando
         * cambian las reglas de la analítica: en ese caso el archivo se
         * regenera y el contenido sigue siendo válido).
         */
        const val FORMAT_VERSION: Int = 1
    }
}

/**
 * Marca de agua de la caché: hasta dónde llegan los números guardados.
 *
 * @property formatVersion versión del formato: si no coincide, la caché se
 *   descarta y se recalcula.
 * @property appendSeq último `seq` incorporado al acumulador.
 * @property messageCount mensajes conocidos cuando se guardó.
 * @property builtAt cuándo se guardó (informativo: no invalida nada).
 */
@Serializable
data class AnalyticsWatermark(
    val formatVersion: Int = AnalyticsCache.FORMAT_VERSION,
    val courseId: String = "",
    val appendSeq: Long = 0L,
    val messageCount: Long = 0L,
    val builtAt: Long = 0L,
)

/** Contenido del archivo de caché: la marca de agua más el estado. */
@Serializable
data class AnalyticsCachePayload(
    val watermark: AnalyticsWatermark = AnalyticsWatermark(),
    val state: AnalyticsAccumulatorState = AnalyticsAccumulatorState(),
)
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        