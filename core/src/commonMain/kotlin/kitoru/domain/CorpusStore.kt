package kitoru.domain

import kitoru.platform.FilePath
import kitoru.platform.FileSystem
import kitoru.platform.KitoruPaths
import kitoru.platform.nowMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Persistencia del manifiesto del corpus en disco.
 *
 * El manifiesto vive en `courses/<courseId>/corpus/manifest.json`: es **un
 * archivo por cátedra**, chico (unos pocos KB) y versionable. El cliente del
 * alumno lo consulta siempre (1 KB de tráfico) y baja los textos **sólo** de
 * los materiales cuyo hash no tenga todavía.
 *
 * ## Por qué la escritura es atómica
 *
 * El manifiesto es el **punto de commit** del corpus: mientras no está
 * escrito, el cliente sigue usando el manifiesto viejo (funcional). Si se
 * escribiera a medias, el cliente leería un JSON roto y se quedaría sin
 * corpus.
 *
 * `FileSystem` no tiene `move` (y no lo va a tener: renombrar no es
 * atómico en todos los sistemas de archivos y no hace falta), así que la
 * escritura segura es **`.tmp` + `copy` + `delete`**:
 *
 *  1. se escribe el JSON completo en `manifest.json.tmp`;
 *  2. se copia el temporal sobre `manifest.json`;
 *  3. se borra el temporal.
 *
 * Si la app se muere en el paso 1, el manifiesto viejo sigue intacto. Si se
 * muere en el paso 2, queda un `.tmp` que se limpia en la siguiente
 * escritura (el directorio se puede regenerar siempre desde el manifiesto).
 */
class CorpusStore(
    private val fs: FileSystem,
    /**
     * Carpeta donde viven las cátedras.
     *
     * Es una **función** y no un valor porque `KitoruPaths.dataDir` lo fija
     * la aplicación al arrancar: si se capturara el valor en el constructor,
     * el store quedaría apuntando al directorio viejo.
     */
    private val baseDir: () -> FilePath = { KitoruPaths.coursesDir },
) {
    /** Cerrojo: dos publicaciones simultáneas no se pisan el archivo. */
    private val mutex = Mutex()

    /** Carpeta del corpus de una cátedra. */
    fun dir(courseId: String): FilePath =
        baseDir().resolve(courseId).resolve(CORPUS_DIR)

    /** Ruta del manifiesto de una cátedra. */
    fun manifestPath(courseId: String): FilePath =
        dir(courseId).resolve(MANIFEST_FILE)

    /** Ruta del temporal (se borra después de la copia). */
    fun tempPath(courseId: String): FilePath =
        dir(courseId).resolve("$MANIFEST_FILE$TEMP_SUFFIX")

    /**
     * Lee el manifiesto de una cátedra.
     *
     * Devuelve `null` si no hay manifiesto o si está corrupto: un archivo
     * roto no debe voltear la app (mismo criterio que el resto de la
     * persistencia del proyecto).
     */
    suspend fun read(courseId: String): CorpusManifest? = mutex.withLock { readNow(courseId) }

    /**
     * Escribe el manifiesto de forma atómica (`.tmp` + `copy` + `delete`).
     */
    suspend fun write(manifest: CorpusManifest) {
        mutex.withLock {
            val dir = dir(manifest.courseId)
            fs.createDirectories(dir)
            val tmp = tempPath(manifest.courseId)
            val target = manifestPath(manifest.courseId)
            fs.writeText(tmp, KitoruJson.encodeToString(CorpusManifest.serializer(), manifest))
            fs.copy(tmp, target)
            fs.delete(tmp)
        }
    }

    /** Versión del manifiesto vigente (0 si todavía no se publicó nada). */
    suspend fun version(courseId: String): Long = read(courseId)?.version ?: 0L

    /** ¿Hay manifiesto para esta cátedra? */
    suspend fun exists(courseId: String): Boolean = read(courseId) != null

    /** Borra el manifiesto (deja la cátedra como si nunca se hubiera publicado). */
    suspend fun delete(courseId: String) {
        mutex.withLock {
            val target = manifestPath(courseId)
            if (fs.exists(target)) fs.delete(target)
            val tmp = tempPath(courseId)
            if (fs.exists(tmp)) fs.delete(tmp)
        }
    }

    /** Lectura sin cerrojo (la usan los que ya lo tienen tomado). */
    private fun readNow(courseId: String): CorpusManifest? {
        val path = manifestPath(courseId)
        if (!fs.exists(path)) return null
        val text = try {
            fs.readText(path)
        } catch (_: Exception) {
            return null
        }
        return try {
            KitoruJson.decodeFromString(CorpusManifest.serializer(), text)
        } catch (_: Exception) {
            // Manifiesto corrupto: se ignora (el cliente conserva el suyo).
            null
        }
    }

    companion object {
        /** Carpeta del corpus dentro de la cátedra. */
        const val CORPUS_DIR = "corpus"

        /** Nombre del archivo del manifiesto. */
        const val MANIFEST_FILE = "manifest.json"

        /** Sufijo del temporal. */
        const val TEMP_SUFFIX = ".tmp"
    }
}

/**
 * Cuándo llega a los alumnos un cambio del corpus.
 *
 * La decisión es del docente: el material de la cátedra cambia seguido
 * (un apunte corregido, unas diapositivas nuevas) y el alumno no puede
 * enterarse de un cambio a medio hacer.
 */
@Serializable
enum class CorpusPublishMode(
    val label: String,
    val description: String,
) {
    /** En el acto, al publicar el material. */
    INMEDIATO(
        "Inmediato",
        "El cambio llega a los alumnos en el momento en que publicás el material.",
    ),

    /** En el próximo cambio de estado de la cátedra a publicada. */
    AL_PUBLICAR_CATEDRA(
        "Al publicar la cátedra",
        "Los cambios se acumulan y salen juntos la próxima vez que publiques la cátedra.",
    ),

    /** En un lote diario a una hora fija. */
    DIARIO(
        "Por lote diario",
        "Los cambios se acumulan y salen todos juntos a la hora que elijas.",
    ),
}

/**
 * Resultado de una publicación.
 */
data class PublishOutcome(
    /** ¿Se publicó una versión nueva del manifiesto? */
    val published: Boolean = false,
    /** ¿Quedan cambios pendientes (lote o publicación de la cátedra)? */
    val pending: Boolean = false,
    /** ¿El corpus era idéntico y no se publicó nada? */
    val unchanged: Boolean = false,
    /** Versión del manifiesto vigente. */
    val version: Long = 0L,
    /** Manifiesto publicado (o el vigente, si no se publicó nada). */
    val manifest: CorpusManifest? = null,
    /** Mensaje para el docente. */
    val message: String = "",
)

/**
 * Publica el corpus de una cátedra.
 *
 * Es el punto donde la app decide **cuándo** lo que el docente cambió llega
 * a los alumnos, y es el único que escribe la versión del manifiesto.
 *
 * Reglas:
 *
 * - El manifiesto se **regenera** desde los materiales actuales: en los modos
 *   "acumulados" (lote diario, al publicar la cátedra) no hace falta
 *   guardar nada pendiente, porque lo pendiente es simplemente "el estado
 *   actual del material".
 * - Publicar es **idempotente**: si el manifiesto resultante es idéntico al
 *   vigente, no se sube una versión nueva. Publicar 30 veces por día son 30
 *   chequeos locales, no 30 versiones.
 * - La versión del manifiesto **sube** con cada publicación efectiva, y la
 *   versión de cada material sube **sólo** cuando su texto cambia.
 */
class CorpusPublisher(
    private val fs: FileSystem,
    private val store: CorpusStore = CorpusStore(fs),
    private val clock: () -> Long = { nowMillis() },
) {

    /**
     * Publica el corpus de una cátedra.
     *
     * @param courseId cátedra a publicar.
     * @param materiales materiales actuales de la cátedra.
     * @param mode cuándo llega el cambio a los alumnos.
     * @param chunking troceado con el que se indexó (viaja en el manifiesto).
     * @param coursePublished ¿la cátedra está publicada? (modo
     *   [CorpusPublishMode.AL_PUBLICAR_CATEDRA]).
     * @param publishHour hora local del lote diario (0..23).
     * @param unitIdsByMaterial unidades de la taxonomía por material.
     * @param utcOffsetMinutes desfase de la zona horaria respecto de UTC: en
     *   `commonMain` no hay `java.time`, así que la plataforma lo informa.
     * @param now instante de la publicación (inyectable para los tests).
     */
    suspend fun publish(
        courseId: String,
        materials: List<Material>,
        mode: CorpusPublishMode = CorpusPublishMode.INMEDIATO,
        chunking: ChunkingConfig = ChunkingConfig(),
        coursePublished: Boolean = false,
        publishHour: Int = 8,
        unitIdsByMaterial: Map<String, List<String>> = emptyMap(),
        utcOffsetMinutes: Int = 0,
        now: Long? = null,
    ): PublishOutcome {
        val momento = now ?: clock()
        val anterior = store.read(courseId)
        val entries = buildEntries(materials, anterior, unitIdsByMaterial)

        val decision = decide(mode, coursePublished, publishHour, momento, utcOffsetMinutes)
        if (!decision.publishNow) {
            return PublishOutcome(
                pending = true,
                version = anterior?.version ?: 0L,
                manifest = anterior,
                message = decision.message,
            )
        }

        val candidate = CorpusManifest(
            courseId = courseId,
            version = (anterior?.version ?: 0L) + 1L,
            generatedAt = momento,
            chunking = chunking,
            materials = entries,
        )

        // Publicar es idempotente: si nada cambió, no se sube una versión.
        if (anterior != null && anterior.chunking == chunking && anterior.materials == entries) {
            return PublishOutcome(
                unchanged = true,
                version = anterior.version,
                manifest = anterior,
                message = "El corpus no cambió: no se publicó una versión nueva.",
            )
        }

        store.write(candidate)
        return PublishOutcome(
            published = true,
            version = candidate.version,
            manifest = candidate,
            message = "Se publicó la versión ${candidate.version} del corpus " +
                "(${entries.size} ${if (entries.size == 1) "material" else "materiales"}).",
        )
    }

    /**
     * Escribe un manifiesto ya armado (por ejemplo, el que arma otro
     * transporte). Devuelve la versión publicada.
     */
    suspend fun publishManifest(manifest: CorpusManifest): Long {
        store.write(manifest)
        return manifest.version
    }

    /**
     * Arma las entradas del manifiesto a partir de los materiales.
     *
     * - Se **excluyen** los materiales sin texto: publicar algo que la IA no
     *   puede leer es peor que no publicarlo.
     * - La versión de un material sube sólo cuando su **texto** cambia (hash
     *   distinto): renombrarlo o cambiarle la visibilidad no crea versiones.
     */
    private fun buildEntries(
        materials: List<Material>,
        anterior: CorpusManifest?,
        unitIdsByMaterial: Map<String, List<String>>,
    ): List<ManifestEntry> {
        val prev = anterior?.materials?.associateBy { it.materialId } ?: emptyMap()
        return materials
            .filter { it.extractedText.isNotBlank() }
            .sortedBy { it.id }
            .map { material ->
                val sha = ContentHasher.exact(material.extractedText)
                val before = prev[material.id]
                val version = when {
                    before == null -> 1
                    before.sha256 == sha -> before.version
                    else -> before.version + 1
                }
                ManifestEntry(
                    materialId = material.id,
                    title = material.title,
                    kind = material.kind,
                    sha256 = sha,
                    textBytes = material.extractedText.encodeToByteArray().size,
                    version = version,
                    // El modelo de materiales todavía no tiene `updatedAt`;
                    // `uploadedAt` es la mejor aproximación disponible.
                    updatedAt = material.uploadedAt,
                    visible = material.visible,
                    unitIds = unitIdsByMaterial[material.id] ?: before?.unitIds ?: emptyList(),
                )
            }
    }

    /** Decisión de publicación: ¿sale ahora o queda pendiente? */
    private fun decide(
        mode: CorpusPublishMode,
        coursePublished: Boolean,
        publishHour: Int,
        now: Long,
        utcOffsetMinutes: Int,
    ): Decision = when (mode) {
        CorpusPublishMode.INMEDIATO -> Decision(true, "")
        CorpusPublishMode.AL_PUBLICAR_CATEDRA -> if (coursePublished) {
            Decision(true, "")
        } else {
            Decision(false, "Los cambios quedan pendientes hasta que la cátedra se publique.")
        }
        CorpusPublishMode.DIARIO -> {
            val hour = hourOfDay(now, utcOffsetMinutes)
            if (hour >= publishHour) {
                Decision(true, "")
            } else {
                Decision(false, "Los cambios quedan pendientes hasta el lote diario de las " +
                    "${publishHour.toString().padStart(2, '0')}:00.")
            }
        }
    }

    /**
     * Hora local del día (0..23) de un instante.
     *
     * `commonMain` no tiene `java.time`, así que la zona horaria la informa
     * la plataforma como un desfase en minutos.
     */
    private fun hourOfDay(epochMillis: Long, offsetMinutes: Int): Int {
        val shifted = epochMillis + offsetMinutes * 60_000L
        val hours = (shifted / 3_600_000L) % 24L
        return ((hours + 24L) % 24L).toInt()
    }

    /** Decisión interna de publicación. */
    private data class Decision(val publishNow: Boolean, val message: String)
}
