package kitoru.domain

import kitoru.platform.nowMillis
import kotlinx.serialization.Serializable

/**
 * Paquete portable de conversaciones: el archivo `.kitoru`.
 *
 * ## Por qué un ZIP
 *
 * El `.kitoru` es lo que el alumno entrega al docente cuando chatted con IA
 * local (el único camino por el que el texto sale del equipo del alumno). El
 * formato es un **ZIP** —el que entiende cualquier sistema, sin instalar
 * nada— con esta estructura:
 *
 * ```
 * manifest.json      # formato, versión, conteos y hashes de las entradas
 * threads.json       # las cabeceras de los hilos
 * messages.jsonl     # los mensajes, uno por línea
 * attachments/       # los adjuntos de los hilos (vacío por ahora)
 * ```
 *
 * ## Por qué un manifiesto con hashes
 *
 * El manifiesto declara el **hash SHA-256** de cada entrada. Al importar se
 * verifica: si un byte no coincide, el paquete está corrupto o fue editado a
 * mano y **no se importa nada** (fail-closed). Un `.kitoru` que no valida no
 * deja ni un hilo a medias en el equipo del docente.
 *
 * ## Por qué no `java.util.zip`
 *
 * `commonMain` no puede usar `java.*` (la regla de portabilidad del proyecto
 * deja la API de Java para `jvmCommonMain`), así que el ZIP se arma en Kotlin
 * común: es el formato estándar, con entradas "stored" (sin comprimir), que
 * `java.util.zip`, el explorador de archivos y `unzip` abren sin problema.
 */
object KitoruPackage {

    /** Identificador del formato del paquete. */
    const val FORMAT = "kitoru.chat-package"

    /** Versión del formato (para migrar). */
    const val VERSION = 1

    /** Extensión del archivo exportado. */
    const val EXTENSION = "kitoru"

    /** Entrada con el manifiesto. */
    const val MANIFEST_ENTRY = "manifest.json"

    /** Entrada con las cabeceras de los hilos. */
    const val THREADS_ENTRY = "threads.json"

    /** Entrada con los mensajes (uno por línea). */
    const val MESSAGES_ENTRY = "messages.jsonl"

    /** Entrada (carpeta) con los adjuntos. */
    const val ATTACHMENTS_ENTRY = "attachments/"

    /**
     * Contenido de un paquete importado.
     *
     * @property threads las cabeceras de los hilos del paquete.
     * @property messages los mensajes del paquete, en orden.
     * @property exportedAt instante de la exportación (epoch millis).
     * @property corruptedLines líneas del JSONL que no se pudieron parsear.
     */
    data class Content(
        val threads: List<ChatThread>,
        val messages: List<ChatMessage>,
        val exportedAt: Long,
        val corruptedLines: Int,
    )

    /**
     * El paquete no es válido: no se importa nada.
     *
     * Es un error explícito (y no un `null` silencioso) porque el usuario tiene
     * que saber que el archivo que eligió no se puede usar.
     */
    class InvalidPackageException(message: String) : IllegalArgumentException(message)

    /**
     * Exporta un conjunto de hilos a un paquete `.kitoru`.
     *
     * La clave de API **no viaja**: los hilos se guardan redactados (ver
     * [ChatThread.redacted]), porque el `.kitoru` lo lee el docente.
     *
     * @param store de dónde se leen los mensajes de cada hilo.
     * @param threads los hilos a exportar.
     * @return los bytes del archivo `.kitoru`.
     */
    suspend fun export(store: ChatStore, threads: List<ChatThread>): ByteArray {
        val headers = threads.map { it.redacted() }
        val messages = threads.flatMap { store.messages(it.id) }
        val threadsJson = chatJson.encodeToString(threadListSerializer, headers)
        val messagesJsonl = messages.joinToString(separator = "\n") {
            chatJsonLines.encodeToString(ChatMessage.serializer(), it)
        }
        val entries = listOf(
            KitoruZip.Entry(THREADS_ENTRY, threadsJson.encodeToByteArray()),
            KitoruZip.Entry(MESSAGES_ENTRY, messagesJsonl.encodeToByteArray()),
            KitoruZip.Entry(ATTACHMENTS_ENTRY, ByteArray(0)),
        )
        val manifest = PackageManifest(
            createdAt = nowMillis(),
            threadCount = headers.size,
            messageCount = messages.size,
            entries = entries.map {
                PackageEntry(name = it.name, size = it.data.size.toLong(), sha256 = Sha256.hex(it.data))
            },
        )
        val manifestBytes = chatJson.encodeToString(PackageManifest.serializer(), manifest).encodeToByteArray()
        val all = buildList {
            add(KitoruZip.Entry(MANIFEST_ENTRY, manifestBytes))
            addAll(entries)
        }
        return KitoruZip.write(all)
    }

    /**
     * Importa un paquete `.kitoru` y devuelve su contenido.
     *
     * **Fail-closed**: si el manifiesto no valida (formato desconocido,
     * versión futura, falta una entrada, el hash no coincide) no se devuelve
     * nada: se lanza [InvalidPackageException].
     *
     * @param bytes el contenido del archivo `.kitoru`.
     * @throws InvalidPackageException si el paquete no es válido.
     */
    fun importPackage(bytes: ByteArray): Content {
        val entries = try {
            KitoruZip.read(bytes)
        } catch (e: IllegalArgumentException) {
            throw InvalidPackageException("El archivo no es un paquete .kitoru válido: ${e.message}")
        }
        val manifestBytes = entries[MANIFEST_ENTRY]
            ?: throw InvalidPackageException("El paquete no tiene manifiesto: no es un archivo .kitoru de Kitoru.")
        val manifest = try {
            chatJson.decodeFromString(PackageManifest.serializer(), manifestBytes.decodeToString())
        } catch (_: Exception) {
            throw InvalidPackageException("El manifiesto del paquete está corrupto: no se puede leer.")
        }
        if (manifest.format != FORMAT) {
            throw InvalidPackageException("El archivo no es un paquete de Kitoru (formato '${manifest.format}').")
        }
        if (manifest.version > VERSION) {
            throw InvalidPackageException(
                "El paquete usa la versión ${manifest.version} del formato y esta versión de Kitoru entiende hasta la $VERSION.",
            )
        }
        // Verificación de integridad: si algo no coincide, no se importa nada.
        for (entry in manifest.entries) {
            val data = entries[entry.name]
                ?: throw InvalidPackageException("El paquete no incluye '${entry.name}', que el manifiesto declara.")
            if (data.size.toLong() != entry.size) {
                throw InvalidPackageException(
                    "'${entry.name}' tiene un tamaño distinto al declarado (${data.size} en vez de ${entry.size}).",
                )
            }
            val hash = Sha256.hex(data)
            if (hash != entry.sha256) {
                throw InvalidPackageException(
                    "'${entry.name}' no coincide con el hash del manifiesto: el archivo está corrupto o fue editado.",
                )
            }
        }
        val threadsBytes = entries[THREADS_ENTRY]
            ?: throw InvalidPackageException("El paquete no incluye la lista de hilos.")
        val threads = try {
            chatJson.decodeFromString(threadListSerializer, threadsBytes.decodeToString())
        } catch (_: Exception) {
            throw InvalidPackageException("La lista de hilos del paquete está corrupta: no se puede leer.")
        }
        val messages = mutableListOf<ChatMessage>()
        var corrupted = 0
        val messagesBytes = entries[MESSAGES_ENTRY]
        if (messagesBytes != null) {
            for (line in messagesBytes.decodeToString().split('\n')) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                try {
                    messages += chatJsonLines.decodeFromString(ChatMessage.serializer(), trimmed)
                } catch (_: Exception) {
                    // Una línea corrupta no invalida el paquete: se saltea.
                    corrupted++
                }
            }
        }
        return Content(
            threads = threads,
            messages = messages,
            exportedAt = manifest.createdAt,
            corruptedLines = corrupted,
        )
    }

    /**
     * Importa un paquete `.kitoru` en un store.
     *
     * Primero valida el manifiesto y recién después escribe: si el paquete no
     * valida, el store queda intacto.
     *
     * @return el contenido importado.
     * @throws InvalidPackageException si el paquete no es válido.
     */
    suspend fun importInto(store: ChatStore, bytes: ByteArray): Content {
        val content = importPackage(bytes)
        val threadIds = content.threads.map { it.id }.toSet()
        for (thread in content.threads) {
            store.saveThread(thread)
        }
        for (message in content.messages) {
            // Un mensaje sin hilo no se importa: quedaría huérfano.
            if (message.threadId in threadIds) store.appendMessage(message)
        }
        return content
    }
}

/** Manifiesto de un paquete `.kitoru`. */
@Serializable
internal data class PackageManifest(
    val format: String = KitoruPackage.FORMAT,
    val version: Int = KitoruPackage.VERSION,
    /** Instante de la exportación (epoch millis). */
    val createdAt: Long = 0L,
    val threadCount: Int = 0,
    val messageCount: Int = 0,
    /** Las entradas que el manifiesto verifica. */
    val entries: List<PackageEntry> = emptyList(),
)

/** Una entrada verificada por el manifiesto. */
@Serializable
internal data class PackageEntry(
    val name: String,
    val size: Long = 0L,
    val sha256: String = "",
)

/**
 * ZIP mínimo (entradas sin comprimir) escrito en Kotlin común.
 *
 * Es el formato estándar (firma `PK`), con entradas "stored"
 * (método 0): cualquier lector de ZIP las abre sin problema.
 */
internal object KitoruZip {

    /** Una entrada del ZIP. */
    class Entry(val name: String, val data: ByteArray)

    /** Firma del encabezado local de una entrada. */
    private const val LOCAL_HEADER = 0x04034b50

    /** Firma del encabezado del directorio central. */
    private const val CENTRAL_HEADER = 0x02014b50

    /** Firma del final del directorio central. */
    private const val END_OF_CENTRAL = 0x06054b50

    /** Método de compresión: 0 = stored (sin comprimir). */
    private const val METHOD_STORED = 0

    /** Escribe las entradas en un ZIP. */
    fun write(entries: List<Entry>): ByteArray {
        val body = ByteBuffer()
        val central = ByteBuffer()
        var count = 0
        for (entry in entries) {
            val offset = body.size
            val name = entry.name.encodeToByteArray()
            val crc = Crc32.of(entry.data)
            body.u32(LOCAL_HEADER)
            body.u16(20) // versión necesaria
            body.u16(0) // banderas
            body.u16(METHOD_STORED)
            body.u16(0) // hora
            body.u16(0) // fecha
            body.u32(crc)
            body.u32(entry.data.size)
            body.u32(entry.data.size)
            body.u16(name.size)
            body.u16(0) // extra
            body.raw(name)
            body.raw(entry.data)

            central.u32(CENTRAL_HEADER)
            central.u16(20) // versión del creador
            central.u16(20) // versión necesaria
            central.u16(0) // banderas
            central.u16(METHOD_STORED)
            central.u16(0) // hora
            central.u16(0) // fecha
            central.u32(crc)
            central.u32(entry.data.size)
            central.u32(entry.data.size)
            central.u16(name.size)
            central.u16(0) // extra
            central.u16(0) // comentario
            central.u16(0) // disco
            central.u16(0) // atributos internos
            central.u32(0) // atributos externos
            central.u32(offset)
            central.raw(name)
            count++
        }
        val bodyBytes = body.toByteArray()
        val centralBytes = central.toByteArray()
        val out = ByteBuffer(bodyBytes.size + centralBytes.size + 22)
        out.raw(bodyBytes)
        out.raw(centralBytes)
        out.u32(END_OF_CENTRAL)
        out.u16(0) // disco
        out.u16(0) // disco del directorio central
        out.u16(count)
        out.u16(count)
        out.u32(centralBytes.size)
        out.u32(bodyBytes.size)
        out.u16(0) // comentario
        return out.toByteArray()
    }

    /**
     * Lee las entradas de un ZIP.
     *
     * @throws IllegalArgumentException si el ZIP está roto o si una entrada
     *   está comprimida (Kitoru sólo lee entradas sin comprimir).
     */
    fun read(bytes: ByteArray): Map<String, ByteArray> {
        val end = findEndOfCentralDirectory(bytes)
        val count = readU16(bytes, end + 10)
        var offset = readU32(bytes, end + 16)
        val entries = mutableMapOf<String, ByteArray>()
        repeat(count) {
            if (readU32(bytes, offset) != CENTRAL_HEADER) {
                throw IllegalArgumentException("el directorio central del ZIP no es válido")
            }
            val method = readU16(bytes, offset + 10)
            val crc = readU32(bytes, offset + 16)
            val compressedSize = readU32(bytes, offset + 20)
            val uncompressedSize = readU32(bytes, offset + 24)
            val nameLength = readU16(bytes, offset + 28)
            val extraLength = readU16(bytes, offset + 30)
            val commentLength = readU16(bytes, offset + 32)
            val localOffset = readU32(bytes, offset + 42)
            val name = bytes.decodeToString(offset + 46, offset + 46 + nameLength)
            if (method != METHOD_STORED) {
                throw IllegalArgumentException("la entrada '$name' está comprimida (método $method)")
            }
            if (readU32(bytes, localOffset) != LOCAL_HEADER) {
                throw IllegalArgumentException("la cabecera local de '$name' no es válida")
            }
            val localNameLength = readU16(bytes, localOffset + 26)
            val localExtraLength = readU16(bytes, localOffset + 28)
            val start = localOffset + 30 + localNameLength + localExtraLength
            val data = bytes.copyOfRange(start, start + compressedSize)
            if (data.size.toLong() != uncompressedSize.toLong()) {
                throw IllegalArgumentException("la entrada '$name' está truncada")
            }
            if (Crc32.of(data) != crc) {
                throw IllegalArgumentException("la entrada '$name' no coincide con su CRC")
            }
            entries[name] = data
            offset += 46 + nameLength + extraLength + commentLength
        }
        return entries
    }

    /** Busca el final del directorio central (va al final, con comentario opcional). */
    private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
        val from = maxOf(0, bytes.size - 22 - 65535)
        for (i in bytes.size - 22 downTo from) {
            if (readU32(bytes, i) == END_OF_CENTRAL) return i
        }
        throw IllegalArgumentException("no se encontró el directorio central")
    }

    /** Lee un entero de 16 bits (little endian). */
    private fun readU16(bytes: ByteArray, at: Int): Int {
        if (at < 0 || at + 2 > bytes.size) throw IllegalArgumentException("el ZIP está truncado")
        return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }

    /** Lee un entero de 32 bits (little endian). */
    private fun readU32(bytes: ByteArray, at: Int): Int {
        if (at < 0 || at + 4 > bytes.size) throw IllegalArgumentException("el ZIP está truncado")
        return (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }
}

/**
 * Buffer de bytes que crece.
 *
 * `commonMain` no tiene `ByteArrayOutputStream` (es `java.io`), así que el
 * buffer se hace a mano sobre un `ByteArray` que se duplica cuando hace falta.
 */
internal class ByteBuffer(initialCapacity: Int = 256) {

    private var data = ByteArray(initialCapacity)

    /** Cuántos bytes se escribieron. */
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= data.size) return
        var capacity = if (data.isEmpty()) 64 else data.size
        while (capacity < size + extra) capacity *= 2
        data = data.copyOf(capacity)
    }

    /** Escribe un entero de 16 bits (little endian). */
    fun u16(value: Int) {
        ensure(2)
        data[size++] = (value and 0xFF).toByte()
        data[size++] = ((value ushr 8) and 0xFF).toByte()
    }

    /** Escribe un entero de 32 bits (little endian). */
    fun u32(value: Int) {
        ensure(4)
        data[size++] = (value and 0xFF).toByte()
        data[size++] = ((value ushr 8) and 0xFF).toByte()
        data[size++] = ((value ushr 16) and 0xFF).toByte()
        data[size++] = ((value ushr 24) and 0xFF).toByte()
    }

    /** Escribe bytes tal cual. */
    fun raw(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(data, size)
        size += bytes.size
    }

    /** Devuelve los bytes escritos. */
    fun toByteArray(): ByteArray = data.copyOf(size)
}

/**
 * CRC-32 (el que exige el formato ZIP).
 *
 * El hash del manifiesto es [Sha256], que ya vive en el paquete: el CRC está
 * sólo porque el formato ZIP lo lleva en cada entrada.
 */
internal object Crc32 {

    private val TABLE = IntArray(256) { index ->
        var value = index
        repeat(8) {
            value = if (value and 1 != 0) (0xEDB88320.toInt() xor (value ushr 1)) else (value ushr 1)
        }
        value
    }

    /** Suma de control de [bytes]. */
    fun of(bytes: ByteArray): Int {
        var crc = -1
        for (byte in bytes) {
            crc = TABLE[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc.inv()
    }
}
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             