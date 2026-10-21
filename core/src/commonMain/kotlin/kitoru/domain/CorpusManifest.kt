package kitoru.domain

import kitoru.platform.stripDiacritics
import kotlinx.serialization.Serializable

/**
 * Configuración de troceado con la que se construyó el índice del corpus.
 *
 * Viaja en el manifiesto a propósito: si el docente cambia el tamaño de
 * fragmento, el cliente del alumno **reindexa sin volver a descargar un byte**
 * (el texto no cambió, sólo cambió cómo se lo parte). Separar "cambió el
 * texto" (se descarga) de "cambió el troceado" (se reindexa) es la mitad del
 * tráfico de una cursada.
 */
@Serializable
data class ChunkingConfig(
    /** Caracteres por fragmento. */
    val chunkSize: Int = 1200,
    /** Caracteres de solapamiento entre fragmentos vecinos. */
    val chunkOverlap: Int = 150,
    /**
     * Huella del troceador (`"kitoru-chunker/1"`).
     *
     * Si el cliente tiene otra versión, avisa en lugar de fallar: la paridad
     * exacta del RAG importa menos que que el alumno pueda usar el material.
     */
    val algorithm: String = ALGORITHM,
) {
    companion object {
        /** Versión del algoritmo de troceado que usa Kitoru. */
        const val ALGORITHM = "kitoru-chunker/1"
    }
}

/**
 * Un material dentro del manifiesto.
 *
 * Es la unidad que el cliente del alumno descarga y con la que arma su
 * índice local.
 */
@Serializable
data class ManifestEntry(
    /** Id del material (estable en el tiempo: re-subir el archivo no lo cambia). */
    val materialId: String,
    /** Título, como aparece en las citas ("cátedra: Apuntes-IA"). */
    val title: String = "",
    /** Tipo de material. */
    val kind: MaterialKind = MaterialKind.DOCUMENT,
    /**
     * sha-256 (hexadecimal) del **texto extraído**, no del archivo binario.
     *
     * Es el hash que indexa el alumno: el texto es lo que viaja, lo que se
     * trocea y lo que el modelo cita. El binario puede cambiar sin que el
     * texto cambie (mismo PDF re-exportado) y en ese caso el material **no**
     * tiene que volver a bajarse.
     */
    val sha256: String = "",
    /** Tamaño del texto en UTF-8 (bytes), para la cuota y el progreso. */
    val textBytes: Int = 0,
    /**
     * Versión del material dentro del manifiesto.
     *
     * Sube **sólo** cuando cambia el texto: guardar sin tocar nada no crea
     * versiones, para que el historial sea una línea de tiempo honesta.
     */
    val version: Int = 1,
    /** Cuándo se publicó esta versión (epoch millis). */
    val updatedAt: Long = 0L,
    /**
     * Si el material es visible para los alumnos.
     *
     * El diseño de distribución ("07-corpus-local") propone que lo oculto no
     * viaje al manifiesto; acá se mantiene el campo porque el catálogo del
     * docente necesita distinguir "publicado y oculto" de "publicado", y
     * porque el cliente tiene que **poder avisar** que algo se ocultó en vez
     * de borrarlo en silencio.
     */
    val visible: Boolean = true,
    /** Unidades de la taxonomía a las que pertenece el material. */
    val unitIds: List<String> = emptyList(),
) {
    /** ¿El material tiene texto publicable? */
    val hasText: Boolean get() = sha256.isNotBlank() && textBytes > 0
}

/**
 * Manifiesto del corpus publicado de una cátedra.
 *
 * Es el **único contrato** entre el servidor (o la carpeta compartida) y el
 * cliente del alumno: un JSON por cátedra que dice qué hay, con qué versión
 * y con qué hash. Sin manifiesto no hay forma de saber qué cambió sin bajarse
 * el corpus entero.
 *
 * La [version] es monótona: si el cliente la ve más grande que la local, hay
 * que sincronizar.
 */
@Serializable
data class CorpusManifest(
    /** Cátedra a la que pertenece. */
    val courseId: String,
    /** Versión del manifiesto. Sube en cada publicación. */
    val version: Long = 0L,
    /** Cuándo se generó (epoch millis). */
    val generatedAt: Long = 0L,
    /** Troceado con el que se construyó el índice. */
    val chunking: ChunkingConfig = ChunkingConfig(),
    /** Materiales publicados. */
    val materials: List<ManifestEntry> = emptyList(),
) {
    /** Ids de los materiales del manifiesto. */
    val materialIds: List<String> get() = materials.map { it.materialId }

    /** La entrada de un material (o null si no está). */
    fun entryOf(materialId: String): ManifestEntry? =
        materials.firstOrNull { it.materialId == materialId }

    /** Qué cambió respecto de [anterior]. */
    fun diffFrom(anterior: CorpusManifest?): ManifestDiff = compare(anterior, this)

    companion object {

        /**
         * Compara dos manifiestos y dice **qué hay que hacer**.
         *
         * Es la base de la sincronización incremental: el cliente baja sólo
         * lo que cambió ([ManifestDiff.toDownload]) en vez de todo el corpus.
         *
         * - [ManifestDiff.added]: el material es nuevo.
         * - [ManifestDiff.changed]: el texto cambió (hash distinto) → hay que
         *   volver a bajarlo.
         * - [ManifestDiff.removed]: el material ya no está (lo retiraron) →
         *   hay que borrarlo del dispositivo.
         * - [ManifestDiff.visibilityChanged]: cambió la visibilidad → hay que
         *   mostrarlo u ocultarlo.
         * - [ManifestDiff.metadataChanged]: cambió el título, el tipo o las
         *   unidades: no hay que bajar nada, pero hay que avisar.
         */
        fun compare(anterior: CorpusManifest?, nuevo: CorpusManifest): ManifestDiff {
            if (anterior == null) {
                // Primera publicación: todo es nuevo.
                return ManifestDiff(added = nuevo.materials)
            }
            val prev = anterior.materials.associateBy { it.materialId }
            val next = nuevo.materials.associateBy { it.materialId }

            val added = mutableListOf<ManifestEntry>()
            val changed = mutableListOf<ManifestEntry>()
            val removed = mutableListOf<ManifestEntry>()
            val visibility = mutableListOf<VisibilityChange>()
            val metadata = mutableListOf<ManifestEntry>()
            val unchanged = mutableListOf<String>()

            for ((id, entry) in next) {
                val before = prev[id]
                if (before == null) {
                    added += entry
                    continue
                }
                if (before.sha256 != entry.sha256 || before.version != entry.version) {
                    // Cambió el contenido: es lo único que obliga a rebajar.
                    changed += entry
                    continue
                }
                if (before.visible != entry.visible) {
                    visibility += VisibilityChange(
                        materialId = id,
                        title = entry.title,
                        visibleBefore = before.visible,
                        visibleNow = entry.visible,
                    )
                    continue
                }
                if (before.title != entry.title ||
                    before.kind != entry.kind ||
                    before.unitIds != entry.unitIds
                ) {
                    metadata += entry
                    continue
                }
                unchanged += id
            }
            for ((id, entry) in prev) {
                if (id !in next) removed += entry
            }
            return ManifestDiff(
                added = added,
                changed = changed,
                removed = removed,
                visibilityChanged = visibility,
                metadataChanged = metadata,
                unchanged = unchanged,
            )
        }
    }
}

/**
 * Cambio de visibilidad de un material entre dos manifiestos.
 */
data class VisibilityChange(
    val materialId: String,
    val title: String = "",
    val visibleBefore: Boolean = true,
    val visibleNow: Boolean = true,
) {
    /** ¿El material pasó a estar visible? */
    val becameVisible: Boolean get() = !visibleBefore && visibleNow

    /** ¿El material pasó a estar oculto? */
    val becameHidden: Boolean get() = visibleBefore && !visibleNow
}

/**
 * Qué cambió entre dos manifiestos.
 *
 * Es una **vista**: no se persiste, se calcula al vuelo.
 */
data class ManifestDiff(
    /** Materiales nuevos. */
    val added: List<ManifestEntry> = emptyList(),
    /** Materiales cuyo texto cambió (hay que rebajarlos). */
    val changed: List<ManifestEntry> = emptyList(),
    /** Materiales que ya no están (hay que borrarlos). */
    val removed: List<ManifestEntry> = emptyList(),
    /** Materiales que cambiaron de visibilidad. */
    val visibilityChanged: List<VisibilityChange> = emptyList(),
    /** Materiales con metadata nueva (título, tipo, unidades). */
    val metadataChanged: List<ManifestEntry> = emptyList(),
    /** Ids de los materiales que no cambiaron. */
    val unchanged: List<String> = emptyList(),
) {
    /** ¿Hubo algún cambio? */
    val hasChanges: Boolean
        get() = added.isNotEmpty() || changed.isNotEmpty() || removed.isNotEmpty() ||
            visibilityChanged.isNotEmpty() || metadataChanged.isNotEmpty()

    /** Materiales cuyo texto hay que volver a bajar. */
    val toDownload: List<ManifestEntry> get() = added + changed

    /** Ids de los materiales que cambiaron (para logging y avisos). */
    val changedMaterialIds: List<String>
        get() = (added + changed + removed + metadataChanged).map { it.materialId } +
            visibilityChanged.map { it.materialId }

    /** Resumen legible, para el log y para el banner del alumno. */
    fun summary(): String = buildString {
        append("${added.size} agregados")
        append(", ${changed.size} modificados")
        append(", ${removed.size} quitados")
        if (visibilityChanged.isNotEmpty()) append(", ${visibilityChanged.size} con visibilidad cambiada")
        if (metadataChanged.isNotEmpty()) append(", ${metadataChanged.size} con datos nuevos")
        append(", ${unchanged.size} sin cambios")
    }
}

/**
 * SHA-256 en Kotlin puro.
 *
 * Por qué no `java.security.MessageDigest`: existe en la JVM y en Android,
 * pero `commonMain` no puede verlo (y `expect/actual` obligaría a tocar
 * `desktopMain`/`androidMain`, que son de otros). Con esta implementación el
 * mismo código corre en las dos plataformas, se testea en `commonTest` y no
 * depende de la plataforma.
 *
 * La implementación sigue FIPS 180-4: relleno de 1 bit, ceros y la longitud
 * en bits al final, bloques de 64 bytes, 64 rondas.
 */
object Sha256 {

    /** Constantes de la ronda (FIPS 180-4 §4.2.2). */
    private val K = intArrayOf(
        0x428a2f98.toInt(), 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    /** Dígitos hexadecimales (el formateo de números no existe en `commonMain`). */
    private const val HEX = "0123456789abcdef"

    /**
     * sha-256 de los bytes dados, en hexadecimal minúscula (64 caracteres).
     */
    fun hex(bytes: ByteArray): String {
        val digest = digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * sha-256 de un texto, en UTF-8 (que es como viaja el material).
     */
    fun hexOf(text: String): String = hex(text.encodeToByteArray())

    /** El digest crudo (32 bytes). */
    fun digest(message: ByteArray): ByteArray {
        var h0 = 0x6a09e667
        var h1 = 0xbb67ae85.toInt()
        var h2 = 0x3c6ef372
        var h3 = 0xa54ff53a.toInt()
        var h4 = 0x510e527f
        var h5 = 0x9b05688c.toInt()
        var h6 = 0x1f83d9ab.toInt()
        var h7 = 0x5be0cd19

        val len = message.size
        // El relleno lleva el mensaje a un múltiplo de 64 bytes: 1 bit de
        // "1", ceros, y la longitud en bits como entero de 64 bits big-endian.
        val total = ((len + 8) / 64 + 1) * 64
        val padded = ByteArray(total)
        message.copyInto(padded)
        padded[len] = 0x80.toByte()
        val bitLen = len.toLong() * 8
        for (i in 0 until 8) {
            padded[total - 1 - i] = ((bitLen ushr (8 * i)) and 0xFF).toByte()
        }

        val w = IntArray(64)
        var offset = 0
        while (offset < total) {
            for (i in 0 until 16) {
                val j = offset + i * 4
                w[i] = ((padded[j].toInt() and 0xFF) shl 24) or
                    ((padded[j + 1].toInt() and 0xFF) shl 16) or
                    ((padded[j + 2].toInt() and 0xFF) shl 8) or
                    (padded[j + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7
            for (i in 0 until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + K[i] + w[i]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }
            h0 += a; h1 += b; h2 += c; h3 += d
            h4 += e; h5 += f; h6 += g; h7 += h
            offset += 64
        }

        val out = ByteArray(32)
        val hs = intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7)
        for (i in 0 until 8) {
            out[i * 4] = ((hs[i] ushr 24) and 0xFF).toByte()
            out[i * 4 + 1] = ((hs[i] ushr 16) and 0xFF).toByte()
            out[i * 4 + 2] = ((hs[i] ushr 8) and 0xFF).toByte()
            out[i * 4 + 3] = (hs[i] and 0xFF).toByte()
        }
        return out
    }

    /** Rotación a la derecha (lógica, sobre los 32 bits). */
    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))
}

/**
 * Hash del contenido de un material.
 *
 * Hay **dos** hashes y no es un descuido:
 *
 * - [exact] es el del **texto tal cual**, en UTF-8: es el que viaja en el
 *   manifiesto y el que verifica el cliente al descargar. Si el texto
 *   cambia un carácter, el hash cambia y el material hay que rebajarlo.
 * - [normalizedHash] es el del texto **normalizado** (sin acentos, en
 *   minúsculas, sin marcas de página, con espacios colapsados): es el que
 *   sirve para detectar duplicados, porque el mismo PDF re-exportado por otra
 *   herramienta da otro texto pero el mismo contenido.
 */
object ContentHasher {

    /** Marcas de página de los PDF ("Página 12", "## Página 12"). */
    private val PAGE_MARKS = Regex("(?im)^\\s*#*\\s*p(?:á|a)gina\\s+\\d+.*$")

    /**
     * Normaliza el texto para comparar **contenido** y no formato.
     */
    fun normalize(text: String): String {
        val noAccents = stripDiacritics(text).lowercase()
        val noPageMarks = noAccents.replace(PAGE_MARKS, " ")
        return noPageMarks.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * sha-256 del texto **exacto** (sin normalizar), en hexadecimal.
     *
     * Es el hash del contrato: el del manifiesto y el que el alumno verifica
     * después de descargar.
     */
    fun exact(text: String): String = Sha256.hexOf(text)

    /**
     * sha-256 del texto **normalizado**, en hexadecimal.
     *
     * Sirve para detectar duplicados: dos materiales con el mismo
     * `normalizedHash` tienen el mismo contenido.
     */
    fun normalizedHash(text: String): String = Sha256.hexOf(normalize(text))
}
                                                                                                            