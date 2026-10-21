package kitoru.material

/**
 * Lectura de archivos OOXML (`.docx`, `.pptx`, `.xlsx`) sin librerías.
 *
 * ## Por qué ZIP + XML a mano
 *
 * Un `.docx` es un **ZIP con XML adentro**. Apache POI resolvería el
 * problema, pero no corre en Android (arrastra XMLBeans, `commons-io`,
 * `javax.imageio` y `java.awt`) y pesa ~15 MB. Como el material tiene que
 * leerse **en el teléfono** (el docente sube el apunte desde Android), el
 * ZIP y el XML se parsean con código propio.
 *
 * ## Por qué el ZIP está escrito en Kotlin puro
 *
 * `java.util.zip` existe en Android, pero `commonMain` **no puede** usar
 * `java.*`: el código de `commonMain` se compila para cualquier target (y
 * mañana puede haber iOS). Por eso el lector de ZIP y el descompresor
 * DEFLATE están escritos desde cero, en Kotlin puro: el extractor funciona
 * igual en escritorio, en Android y en cualquier target futuro.
 *
 * ## El algoritmo del DOCX, paso a paso
 *
 * 1. **Abrir el ZIP** y localizar `word/document.xml`. Si no está, el archivo
 *    no es un Word válido.
 * 2. **Leer los estilos** (`word/styles.xml`) y resolver el nivel de
 *    encabezado de cada `w:styleId`.
 * 3. **Leer la numeración** (`word/numbering.xml`) para saber si un párrafo
 *    con `w:numPr` es una viñeta o un ordinal.
 * 4. **Recorrer los párrafos** (`w:p`) del cuerpo: el texto sale de `w:t`,
 *    los tabuladores de `w:tab` y los saltos de línea de `w:br`.
 * 5. **Clasificar cada párrafo**: es título (por `w:pStyle`, por el nombre
 *    del estilo o por `w:outlineLvl`), es ítem de lista (`w:numPr`) o es un
 *    párrafo normal.
 * 6. **Las tablas** (`w:tbl` → `w:tr` → `w:tc`) salen como tabla Markdown.
 * 7. **Contar las imágenes** (`w:drawing`, `w:pict`, `w:object`) e informar
 *    "N imágenes omitidas": el texto no incluye su contenido.
 * 8. **Ignorar** `word/styles.xml`, `word/settings.xml`, `word/fontTable.xml`
 *    y todo lo que no sea contenido.
 */
object ZipXmlExtractor : TextExtractor {

    override val id = "ooxml-zip"

    override val extensions = setOf("docx", "pptx", "xlsx")

    override val mimes = setOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    )

    override suspend fun extract(bytes: ByteArray, fileName: String, mime: String): ExtractionResult =
        when (extensionOf(fileName)) {
            "docx" -> DocxExtractor.extract(bytes)
            "pptx" -> PptxExtractor.extract(bytes)
            "xlsx" -> XlsxExtractor.extract(bytes)
            else -> throw MaterialExtractionException(
                ExtractionErrorCode.UNSUPPORTED,
                "No se sabe leer un archivo «${fileName.substringAfterLast('/')}». " +
                    "Los formatos de Office soportados son .docx, .pptx y .xlsx.",
            )
        }
}

/**
 * Error de lectura de un contenedor ZIP.
 *
 * Lleva el código de error del pipeline para que la capa de arriba no tenga
 * que adivinar: un ZIP con contraseña es un problema distinto de un ZIP
 * truncado.
 */
internal class ZipException(
    val code: ExtractionErrorCode,
    message: String,
) : Exception(message)

/**
 * Archivo ZIP leído en memoria.
 *
 * Se lee **entrada por entrada** (no se descomprime el ZIP entero de una
 * vez) y cada entrada tiene un tope: es la protección contra *zip bombs*.
 */
/** Una entrada del directorio central de un ZIP. */
internal class ZipEntry(
    val name: String,
    /** 0 = sin comprimir, 8 = DEFLATE. */
    val method: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val crc32: Long,
    val localOffset: Long,
    val flags: Int,
)

internal class ZipArchive internal constructor(
    private val data: ByteArray,
    private val entries: List<ZipEntry>,
) {

    /** Nombres de todas las entradas. */
    fun names(): List<String> = entries.map { it.name }

    /** ¿Existe esta entrada? */
    fun contains(name: String): Boolean = entries.any { it.name == name }

    /**
     * Bytes descomprimidos de una entrada.
     *
     * @return `null` si la entrada no existe.
     */
    fun readBytes(name: String): ByteArray? {
        val entry = entries.firstOrNull { it.name == name } ?: return null
        if (entry.flags and 0x1 != 0) {
            throw ZipException(
                ExtractionErrorCode.ENCRYPTED,
                "El archivo está protegido con contraseña. Desprotegelo y volvé a subirlo.",
            )
        }
        if (entry.compressedSize > MAX_ENTRY_BYTES) {
            throw ZipException(
                ExtractionErrorCode.TOO_LARGE,
                "El archivo comprimido es demasiado grande al descomprimirlo. Revisá el archivo: puede estar dañado.",
            )
        }
        val start = readLocalHeader(entry) ?: throw ZipException(
            ExtractionErrorCode.CORRUPT,
            "El archivo está dañado: no se pudo leer su contenido.",
        )
        val from = start.toInt()
        val to = (start + entry.compressedSize).toInt()
        if (to > data.size) {
            throw ZipException(
                ExtractionErrorCode.CORRUPT,
                "El archivo está dañado: el contenido termina antes de lo que dice el índice.",
            )
        }
        val compressed = data.copyOfRange(from, to)
        val raw = when (entry.method) {
            0 -> compressed
            8 -> Inflate.inflate(compressed, MAX_ENTRY_BYTES.toInt())
            else -> throw ZipException(
                ExtractionErrorCode.CORRUPT,
                "El archivo usa un método de compresión que Kitoru no sabe leer (${entry.method}).",
            )
        }
        if (entry.uncompressedSize in 1..MAX_ENTRY_BYTES && raw.size.toLong() != entry.uncompressedSize) {
            throw ZipException(
                ExtractionErrorCode.CORRUPT,
                "El archivo está dañado: el tamaño descomprimido no coincide con el del índice.",
            )
        }
        if (crc32(raw) != entry.crc32) {
            throw ZipException(
                ExtractionErrorCode.CORRUPT,
                "El archivo está dañado: la verificación de integridad falló. Probá guardarlo otra vez.",
            )
        }
        return raw
    }

    /** Entrada leída como texto (UTF-8, con Latin-1 como respaldo). */
    fun readText(name: String): String? = readBytes(name)?.let {
        TextEncoding.decodeUtf8Strict(it) ?: TextEncoding.decodeLatin1(it)
    }

    /** Devuelve el desplazamiento de los datos de la entrada dentro del ZIP. */
    private fun readLocalHeader(entry: ZipEntry): Long? {
        val offset = entry.localOffset.toInt()
        if (offset < 0 || offset + 30 > data.size) return null
        if (data.u32(offset) != LOCAL_HEADER_SIGNATURE) return null
        val nameLen = data.u16(offset + 26).toLong()
        val extraLen = data.u16(offset + 28).toLong()
        return entry.localOffset + 30 + nameLen + extraLen
    }

    companion object {
        /** Tope de una entrada descomprimida (50 MB): un `document.xml` no pasa de eso. */
        const val MAX_ENTRY_BYTES = 52_428_800L

        /** Tope de entradas del ZIP. */
        const val MAX_ENTRIES = 5_000

        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
    }
}

/**
 * Lector de ZIP en Kotlin puro.
 *
 * Lee el **directorio central** (que está al final del archivo) y de ahí
 * sabe dónde está cada entrada y cómo descomprimirla.
 */
internal object Zip {

    /** Firma de la entrada del directorio central. */
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L

    /** Firma del "fin del directorio central". */
    private const val EOCD_SIGNATURE = 0x06054b50L

    fun open(bytes: ByteArray): ZipArchive {
        if (bytes.size < 22) {
            throw ZipException(ExtractionErrorCode.CORRUPT, "El archivo está dañado: es demasiado corto para ser un ZIP.")
        }
        val eocd = findEndOfCentralDirectory(bytes)
            ?: throw ZipException(
                ExtractionErrorCode.CORRUPT,
                "El archivo está dañado: no se encontró el índice central del ZIP.",
            )
        val totalEntries = bytes.u16(eocd + 10)
        val cdOffset = bytes.u32(eocd + 16)
        if (totalEntries > ZipArchive.MAX_ENTRIES) {
            throw ZipException(
                ExtractionErrorCode.TOO_LARGE,
                "El archivo tiene demasiadas partes (más de ${ZipArchive.MAX_ENTRIES}). Probá con otro archivo.",
            )
        }
        val entries = mutableListOf<ZipEntry>()
        var p = cdOffset.toInt()
        while (p + 46 <= bytes.size && bytes.u32(p) == CENTRAL_HEADER_SIGNATURE) {
            val flags = bytes.u16(p + 8)
            val method = bytes.u16(p + 10)
            val crc = bytes.u32(p + 16)
            val compressedSize = bytes.u32(p + 20)
            val uncompressedSize = bytes.u32(p + 24)
            val nameLen = bytes.u16(p + 28)
            val extraLen = bytes.u16(p + 30)
            val commentLen = bytes.u16(p + 32)
            val localOffset = bytes.u32(p + 42)
            val name = decodeName(bytes, p + 46, nameLen)
            entries += ZipEntry(
                name = name,
                method = method,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                crc32 = crc,
                localOffset = localOffset,
                flags = flags,
            )
            p += 46 + nameLen + extraLen + commentLen
        }
        if (entries.isEmpty()) {
            throw ZipException(
                ExtractionErrorCode.CORRUPT,
                "El archivo está dañado: el ZIP no tiene ninguna entrada.",
            )
        }
        return ZipArchive(bytes, entries)
    }

    /** Busca la firma "fin del directorio central" desde el final del archivo. */
    private fun findEndOfCentralDirectory(bytes: ByteArray): Int? {
        val maxComment = 0xFFFF + 22
        val from = maxOf(0, bytes.size - maxComment)
        var i = bytes.size - 22
        while (i >= from) {
            if (bytes.u32(i) == 0x06054b50L) return i
            i--
        }
        return null
    }

    /** El nombre de la entrada: UTF-8 si el bit 11 está activo, si no Latin-1. */
    private fun decodeName(bytes: ByteArray, offset: Int, length: Int): String {
        if (length <= 0 || offset + length > bytes.size) return ""
        val raw = bytes.copyOfRange(offset, offset + length)
        return TextEncoding.decodeUtf8Strict(raw) ?: TextEncoding.decodeLatin1(raw)
    }
}

/**
 * Descompresor DEFLATE (RFC 1951) en Kotlin puro.
 *
 * Es la parte "pesada" del ZIP, pero es la que permite leer un `.docx` real
 * (Word comprime las partes XML con DEFLATE). Se implementan los tres tipos
 * de bloque: *stored*, *fixed Huffman* y *dynamic Huffman*.
 */
internal object Inflate {

    private const val MAX_BITS = 15

    // Longitudes y distancias: tabla canónica de RFC 1951.
    private val LENGTH_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    private val LENGTH_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )
    private val DIST_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
        8193, 12289, 16385, 24577,
    )
    private val DIST_EXTRA = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    )

    /** Huffman de longitud fija (RFC 1951 §3.2.6). */
    private val FIXED_LITERAL: Huffman by lazy {
        val lengths = IntArray(288)
        for (i in 0..143) lengths[i] = 8
        for (i in 144..255) lengths[i] = 9
        for (i in 256..279) lengths[i] = 7
        for (i in 280..287) lengths[i] = 8
        Huffman(lengths)
    }

    private val FIXED_DISTANCE: Huffman by lazy { Huffman(IntArray(30) { 5 }) }

    /**
     * Descomprime un stream DEFLATE.
     *
     * @param data el stream comprimido.
     * @param maxOutput tope de bytes de salida (protección contra zip bombs).
     */
    fun inflate(data: ByteArray, maxOutput: Int): ByteArray {
        val reader = BitReader(data)
        val out = ByteBuffer(maxOutput)
        var last = false
        while (!last) {
            last = reader.readBit() == 1
            when (val type = reader.readBits(2)) {
                0 -> inflateStored(reader, out)
                1 -> inflateBlock(reader, out, FIXED_LITERAL, FIXED_DISTANCE)
                2 -> {
                    val (literal, distance) = readDynamicTables(reader)
                    inflateBlock(reader, out, literal, distance)
                }
                else -> throw ZipException(
                    ExtractionErrorCode.CORRUPT,
                    "El archivo está dañado: el contenido comprimido usa un método desconocido.",
                )
            }
        }
        return out.toByteArray()
    }

    /** Bloque "stored": los bytes van tal cual, con su longitud al principio. */
    private fun inflateStored(reader: BitReader, out: ByteBuffer) {
        reader.alignToByte()
        val len = reader.readByte() or (reader.readByte() shl 8)
        val nlen = reader.readByte() or (reader.readByte() shl 8)
        if (len != (nlen.inv() and 0xFFFF)) {
            throw ZipException(ExtractionErrorCode.CORRUPT, "El archivo está dañado: bloque comprimido inválido.")
        }
        out.append(reader.readBytes(len))
    }

    /** Lee las tablas Huffman dinámicas de un bloque. */
    private fun readDynamicTables(reader: BitReader): Pair<Huffman, Huffman> {
        val literalCount = reader.readBits(5) + 257
        val distanceCount = reader.readBits(5) + 1
        val codeLengthCount = reader.readBits(4) + 4

        // El orden de los códigos de longitud es fijo (RFC 1951 §3.2.7).
        val order = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
        val codeLengthLengths = IntArray(19)
        for (i in 0 until codeLengthCount) {
            codeLengthLengths[order[i]] = reader.readBits(3)
        }
        val codeLengthTable = Huffman(codeLengthLengths)

        val lengths = IntArray(literalCount + distanceCount)
        var index = 0
        while (index < lengths.size) {
            when (val symbol = codeLengthTable.decode(reader)) {
                in 0..15 -> lengths[index++] = symbol
                16 -> {
                    if (index == 0) {
                        throw ZipException(ExtractionErrorCode.CORRUPT, "El archivo está dañado: tabla de longitudes inválida.")
                    }
                    val previous = lengths[index - 1]
                    val repeat = 3 + reader.readBits(2)
                    for (i in 0 until repeat) {
                        if (index < lengths.size) lengths[index++] = previous
                    }
                }
                17 -> {
                    val repeat = 3 + reader.readBits(3)
                    for (i in 0 until repeat) {
                        if (index < lengths.size) lengths[index++] = 0
                    }
                }
                18 -> {
                    val repeat = 11 + reader.readBits(7)
                    for (i in 0 until repeat) {
                        if (index < lengths.size) lengths[index++] = 0
                    }
                }
                else -> throw ZipException(
                    ExtractionErrorCode.CORRUPT,
                    "El archivo está dañado: no se pudo leer la tabla de compresión.",
                )
            }
        }
        val literal = Huffman(lengths.copyOfRange(0, literalCount))
        val distance = Huffman(lengths.copyOfRange(literalCount, lengths.size))
        return literal to distance
    }

    /** Decodifica los símbolos de un bloque comprimido. */
    private fun inflateBlock(reader: BitReader, out: ByteBuffer, literal: Huffman, distance: Huffman) {
        while (true) {
            val symbol = literal.decode(reader)
            when {
                symbol < 256 -> out.append(symbol.toByte())
                symbol == 256 -> return
                else -> {
                    val lengthIndex = symbol - 257
                    if (lengthIndex >= LENGTH_BASE.size) {
                        throw ZipException(ExtractionErrorCode.CORRUPT, "El archivo está dañado: longitud inválida.")
                    }
                    val length = LENGTH_BASE[lengthIndex] + reader.readBits(LENGTH_EXTRA[lengthIndex])
                    val distanceSymbol = distance.decode(reader)
                    if (distanceSymbol >= DIST_BASE.size) {
                        throw ZipException(ExtractionErrorCode.CORRUPT, "El archivo está dañado: distancia inválida.")
                    }
                    val offset = DIST_BASE[distanceSymbol] + reader.readBits(DIST_EXTRA[distanceSymbol])
                    out.copyBack(offset, length)
                }
            }
        }
    }

    /** Lector de bits (menos significativo primero, como manda DEFLATE). */
    private class BitReader(private val data: ByteArray) {
        private var position = 0
        private var buffer = 0
        private var bits = 0

        fun readBit(): Int {
            if (bits == 0) {
                if (position >= data.size) {
                    throw ZipException(ExtractionErrorCode.CORRUPT, "El archivo está dañado: el contenido comprimido termina antes de tiempo.")
                }
                buffer = data[position++].toInt() and 0xFF
                bits = 8
            }
            val bit = buffer and 1
            buffer = buffer shr 1
            bits--
            return bit
        }

        fun readBits(count: Int): Int {
            var value = 0
            for (i in 0 until count) value = value or (readBit() shl i)
            return value
        }

        /** Descarta los bits que quedan del byte actual (para bloques "stored"). */
        fun alignToByte() {
            buffer = 0
            bits = 0
        }

        fun readByte(): Int {
            if (position >= data.size) {
                throw ZipException(ExtractionErrorCode.CORRUPT, "El archivo está dañado: el contenido comprimido termina antes de tiempo.")
            }
            return data[position++].toInt() and 0xFF
        }

        fun readBytes(count: Int): ByteArray {
            if (position + count > data.size) {
                throw ZipException(ExtractionErrorCode.CORRUPT, "El archivo está dañado: el contenido comprimido termina antes de tiempo.")
            }
            val out = data.copyOfRange(position, position + count)
            position += count
            return out
        }
    }

    /**
     * Huffman canónico.
     *
     * Se decodifica bit a bit con el algoritmo de conteo: para cada longitud
     * se sabe cuántos códigos hay y dónde empieza el bloque de símbolos.
     */
    private class Huffman(lengths: IntArray) {
        private val counts = IntArray(MAX_BITS + 1)
        private val symbols = IntArray(lengths.size)

        init {
            for (length in lengths) counts[length]++
            counts[0] = 0
            val offsets = IntArray(MAX_BITS + 2)
            for (i in 1..MAX_BITS) offsets[i + 1] = offsets[i] + counts[i]
            for (symbol in lengths.indices) {
                if (lengths[symbol] != 0) symbols[offsets[lengths[symbol]]++] = symbol
            }
        }

        fun decode(reader: BitReader): Int {
            var code = 0
            var first = 0
            var index = 0
            for (length in 1..MAX_BITS) {
                code = code or reader.readBit()
                val count = counts[length]
                if (code - first < count) return symbols[index + (code - first)]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            throw ZipException(ExtractionErrorCode.CORRUPT, "El archivo está dañado: código de compresión inválido.")
        }
    }

    /** Buffer de salida que crece y copia hacia atrás (las repeticiones). */
    private class ByteBuffer(private val maxOutput: Int) {
        private var buffer = ByteArray(1024)
        private var size = 0

        fun append(byte: Byte) {
            ensure(1)
            buffer[size++] = byte
        }

        fun append(bytes: ByteArray) {
            ensure(bytes.size)
            bytes.copyInto(buffer, size)
            size += bytes.size
        }

        /** Copia `length` bytes desde `distance` posiciones atrás. */
        fun copyBack(distance: Int, length: Int) {
            if (distance <= 0 || distance > size) {
                throw ZipException(ExtractionErrorCode.CORRUPT, "El archivo está dañado: referencia de compresión inválida.")
            }
            ensure(length)
            var source = size - distance
            for (i in 0 until length) {
                buffer[size++] = buffer[source++]
            }
        }

        private fun ensure(extra: Int) {
            if (size + extra <= buffer.size) return
            if (size + extra > maxOutput) {
                throw ZipException(
                    ExtractionErrorCode.TOO_LARGE,
                    "El archivo comprimido es demasiado grande al descomprimirlo. Revisá el archivo: puede estar dañado.",
                )
            }
            var newSize = buffer.size
            while (newSize < size + extra) newSize *= 2
            buffer = buffer.copyOf(newSize)
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }
}

/** CRC-32 (el mismo que usa el ZIP): tabla de 256 entradas. */
internal fun crc32(bytes: ByteArray): Long {
    var crc = 0xFFFFFFFFL
    for (byte in bytes) {
        val index = ((crc xor byte.toLong()) and 0xFF).toInt()
        crc = (crc shr 8) xor CRC_TABLE[index].toLong()
    }
    return (crc xor 0xFFFFFFFFL) and 0xFFFFFFFFL
}

private val CRC_TABLE = IntArray(256) { i ->
    var c = i
    repeat(8) {
        c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c shr 1)) else (c shr 1)
    }
    c
}

/** Lectura de enteros little-endian (el orden del ZIP). */
private fun ByteArray.u16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32(offset: Int): Long =
    (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)

/**
 * Escáner de XML en Kotlin puro.
 *
 * **Por qué no `javax.xml` ni DOM.** `javax.xml.parsers` no existe en Android
 * (o existe con comillas raras) y un DOM carga el documento entero en
 * memoria; además el scanner es **mucho** más rápido y no necesita ninguna
 * dependencia. El parser es de eventos: no construye un árbol, va soltando
 * eventos mientras recorre el texto.
 *
 * Es robusto frente a:
 * - atributos cuyo valor contiene `>` (`<w:t xml:space="a > b">`),
 * - entidades (`&amp;`, `&#225;`, `&#xE1;`),
 * - CDATA (`<![CDATA[ ... ]]>`),
 * - comentarios (`<!-- ... -->`) y el DOCTYPE.
 */
internal class XmlScanner(private val xml: String) {

    /** Eventos del documento. */
    sealed interface Event {
        /** Etiqueta de apertura (con sus atributos). */
        data class Open(val name: String, val attrs: Map<String, String>, val selfClosing: Boolean) : Event

        /** Etiqueta de cierre. */
        data class Close(val name: String) : Event

        /** Texto entre etiquetas (con las entidades ya resueltas). */
        data class Text(val value: String) : Event

        /** Bloque CDATA (texto literal). */
        data class CData(val value: String) : Event
    }

    /** Todos los eventos del documento, en orden. */
    fun events(): List<Event> {
        val out = mutableListOf<Event>()
        var i = 0
        val n = xml.length
        while (i < n) {
            if (xml[i] != '<') {
                val next = xml.indexOf('<', i)
                val raw = if (next < 0) xml.substring(i) else xml.substring(i, next)
                if (raw.isNotEmpty()) out += Event.Text(HtmlEntities.decode(raw))
                i = if (next < 0) n else next
                continue
            }
            when {
                xml.startsWith("<!--", i) -> i = skipPast("-->", i + 4)
                xml.startsWith("<![CDATA[", i) -> {
                    val end = xml.indexOf("]]>", i)
                    val raw = if (end < 0) xml.substring(i + 9) else xml.substring(i + 9, end)
                    out += Event.CData(raw)
                    i = if (end < 0) n else end + 3
                }
                xml.startsWith("<!DOCTYPE", i) -> i = skipDoctype(i)
                xml.startsWith("<!", i) -> i = skipPast(">", i + 2)
                xml.startsWith("<?", i) -> i = skipPast(">", i + 2)
                xml.startsWith("</", i) -> {
                    val end = xml.indexOf('>', i)
                    val name = xml.substring(i + 2, if (end < 0) n else end).trim()
                    if (name.isNotEmpty()) out += Event.Close(name)
                    i = if (end < 0) n else end + 1
                }
                else -> {
                    val tag = parseTag(i)
                    out += Event.Open(tag.name, tag.attrs, tag.selfClosing)
                    if (tag.selfClosing) out += Event.Close(tag.name)
                    i = tag.end
                }
            }
        }
        return out
    }

    private fun skipPast(marker: String, from: Int): Int {
        val end = xml.indexOf(marker, from)
        return if (end < 0) xml.length else end + marker.length
    }

    /** El DOCTYPE puede traer un subconjunto interno con corchetes. */
    private fun skipDoctype(from: Int): Int {
        var i = from
        var inSubset = false
        while (i < xml.length) {
            when (xml[i]) {
                '[' -> inSubset = true
                ']' -> inSubset = false
                '>' -> if (!inSubset) return i + 1
            }
            i++
        }
        return xml.length
    }

    private class Tag(val name: String, val attrs: Map<String, String>, val selfClosing: Boolean, val end: Int)

    /** Lee una etiqueta de apertura con sus atributos. */
    private fun parseTag(from: Int): Tag {
        var p = from + 1
        val nameStart = p
        while (p < xml.length && (xml[p].isLetterOrDigit() || xml[p] == ':' || xml[p] == '_' || xml[p] == '-' || xml[p] == '.')) p++
        val name = xml.substring(nameStart, p)
        val attrs = mutableMapOf<String, String>()
        var selfClosing = false
        while (p < xml.length) {
            while (p < xml.length && xml[p].isWhitespace()) p++
            if (p >= xml.length) break
            if (xml[p] == '>') { p++; break }
            if (xml[p] == '/') {
                selfClosing = true
                p++
                continue
            }
            val attrStart = p
            while (p < xml.length && !xml[p].isWhitespace() && xml[p] != '=' && xml[p] != '>' && xml[p] != '/') p++
            val attrName = xml.substring(attrStart, p)
            while (p < xml.length && xml[p].isWhitespace()) p++
            var value = ""
            if (p < xml.length && xml[p] == '=') {
                p++
                while (p < xml.length && xml[p].isWhitespace()) p++
                if (p < xml.length && (xml[p] == '"' || xml[p] == '\'')) {
                    val quote = xml[p]
                    p++
                    val valueStart = p
                    while (p < xml.length && xml[p] != quote) p++
                    value = xml.substring(valueStart, minOf(p, xml.length))
                    if (p < xml.length) p++
                } else {
                    val valueStart = p
                    while (p < xml.length && !xml[p].isWhitespace() && xml[p] != '>') p++
                    value = xml.substring(valueStart, p)
                }
            }
            if (attrName.isNotEmpty()) attrs[attrName] = HtmlEntities.decode(value)
        }
        return Tag(name, attrs, selfClosing, p)
    }
}

/**
 * Relaciones OOXML (los archivos `.rels`).
 *
 * Formato: `<Relationship Id="rId5" Type="…/hyperlink" Target="https://…" TargetMode="External"/>`.
 */
internal class OoxmlRels(
    private val targetById: Map<String, String>,
    private val typeById: Map<String, String>,
) {
    /** URL o ruta del vínculo con ese id. */
    fun target(id: String?): String? = id?.let { targetById[it] }

    /** Todos los destinos (para buscar, por ejemplo, la nota del orador). */
    fun targets(): List<String> = targetById.values.toList()

    /** Tipo de la relación ("…/hyperlink", "…/slide"…). */
    fun type(id: String?): String? = id?.let { typeById[it] }

    companion object {
        fun read(zip: ZipArchive, path: String): OoxmlRels {
            val xml = zip.readText(path) ?: return OoxmlRels(emptyMap(), emptyMap())
            val targets = mutableMapOf<String, String>()
            val types = mutableMapOf<String, String>()
            for (event in XmlScanner(xml).events()) {
                if (event is XmlScanner.Event.Open && event.name == "Relationship") {
                    val id = event.attrs["Id"] ?: continue
                    event.attrs["Target"]?.let { targets[id] = it }
                    event.attrs["Type"]?.let { types[id] = it }
                }
            }
            return OoxmlRels(targets, types)
        }
    }
}

/**
 * Resuelve una ruta relativa de OOXML (`../slideLayouts/slideLayout1.xml`)
 * contra la carpeta del documento.
 */
internal fun resolveOoxmlPath(base: String, target: String): String {
    val parts = base.split('/').dropLast(1).toMutableList()
    for (segment in target.split('/')) {
        when (segment) {
            ".", "" -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(segment)
        }
    }
    return parts.joinToString("/")
}

/** Abre el ZIP o convierte el error en un error del pipeline. */
internal fun openZip(bytes: ByteArray): ZipArchive = try {
    Zip.open(bytes)
} catch (e: ZipException) {
    throw MaterialExtractionException(e.code, e.message ?: "El archivo está dañado.", e)
}

// ---------------------------------------------------------------------------
// DOCX
// ---------------------------------------------------------------------------

/** Un bloque del documento de Word, ya clasificado. */
internal sealed interface DocxBlock {
    /** Un título: sale como `#`, `##`… según el nivel. */
    data class Heading(val level: Int, val text: String) : DocxBlock

    /** Un párrafo normal. */
    data class Paragraph(val text: String) : DocxBlock

    /** Un ítem de lista: sale como `- ` o `1. `. */
    data class ListItem(val marker: String, val text: String) : DocxBlock

    /** Una tabla: sale como tabla Markdown. */
    data class Table(val rows: List<List<String>>) : DocxBlock
}

/**
 * Extrae el texto de un `.docx` parseando el ZIP y el XML a mano.
 *
 * El algoritmo completo está en el KDoc de [ZipXmlExtractor].
 */
internal object DocxExtractor {

    fun extract(bytes: ByteArray): ExtractionResult {
        val zip = openZip(bytes)

        // (1) El cuerpo del documento.
        val documentXml = zip.readText("word/document.xml") ?: throw MaterialExtractionException(
            ExtractionErrorCode.CORRUPT,
            "El archivo .docx está dañado o no es un Word válido (falta word/document.xml).",
        )

        // (2..4) Estilos, numeración y vínculos.
        val styles = DocxStyles.read(zip)
        val numbering = DocxNumbering.read(zip)
        val rels = OoxmlRels.read(zip, "word/_rels/document.xml.rels")

        // (5..7) La máquina de estados sobre el cuerpo.
        val body = DocxBody(styles, numbering, rels)
        body.consume(XmlScanner(documentXml).events())
        val blocks = body.blocks()

        // (8) Markdown.
        val text = render(blocks)

        // (9) Título y avisos.
        val title = readCoreTitle(zip)
        val notes = mutableListOf<String>()
        if (body.images > 0) {
            notes += "${body.images} " +
                (if (body.images == 1) "imagen omitida" else "imágenes omitidas") +
                " (el texto no incluye su contenido)."
        }
        if (body.footnotes > 0) {
            notes += "${body.footnotes} notas al pie anexadas al final."
        }
        if (text.isBlank()) {
            notes += "El documento no tiene texto (sólo imágenes). Revisá el archivo o pegá el texto a mano."
        }

        return ExtractionResult(
            text = text,
            title = title,
            pages = 0, // un .docx no tiene páginas conocidas
            warning = notes.joinToString(" "),
            extractor = "docx-zip",
            stats = ExtractionStats(
                chars = text.length,
                words = text.split(Regex("\\s+")).count { it.isNotBlank() },
                blocks = blocks.size,
                skippedImages = body.images,
            ),
        )
    }

    /**
     * Convierte los bloques en Markdown.
     *
     * Los ítems de lista seguidos van con un solo salto de línea (una lista
     * suelta en Markdown); el resto de bloques van con una línea en blanco
     * (que es lo que Markdown necesita para separar párrafos y tablas).
     */
    private fun render(blocks: List<DocxBlock>): String = buildString {
        var previous: DocxBlock? = null
        for (block in blocks) {
            val bothListItems = previous is DocxBlock.ListItem && block is DocxBlock.ListItem
            if (previous != null && !bothListItems) append("\n\n")
            when (block) {
                is DocxBlock.Heading ->
                    append("#".repeat(block.level.coerceIn(1, 6))).append(' ').append(block.text)
                is DocxBlock.Paragraph -> append(block.text)
                is DocxBlock.ListItem -> append(block.marker).append(block.text)
                is DocxBlock.Table -> append(renderTable(block.rows))
            }
            previous = block
        }
    }.trim()

    /**
     * Tabla → Markdown.
     *
     * La primera fila es el encabezado (Markdown lo exige). Si la tabla real
     * no tiene encabezado se usa la primera fila igual: al RAG no le afecta y
     * una tabla Markdown nunca corta una fila por la mitad.
     */
    private fun renderTable(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val width = rows.maxOf { it.size }
        fun escape(value: String) = value.replace("|", "\\|").replace("\n", " ").trim()
        return buildString {
            fun row(cells: List<String>) {
                val padded = cells + List(width - cells.size) { "" }
                append("| ").append(padded.joinToString(" | ") { escape(it) }).append(" |\n")
            }
            row(rows.first())
            append("| ").append(List(width) { "---" }.joinToString(" | ")).append(" |\n")
            for (r in rows.drop(1)) row(r)
        }.trimEnd()
    }
}

/**
 * Los estilos de Word: `w:styleId` → nivel de encabezado.
 *
 * **El truco importante: no se puede decidir sólo por el `styleId`**, porque
 * Word lo localiza: un "Título 1" en un Word en español tiene
 * `styleId="Ttulo1"` (con la e acentuada eliminada) y en inglés
 * `styleId="Heading1"`. Por eso se mira **el nombre** del estilo
 * (`w:name w:val="heading 1"`), que Word escribe en inglés incluso en una
 * instalación en español, y además `w:outlineLvl` (el nivel de esquema, que no
 * depende del idioma).
 */
internal class DocxStyles {

    data class Style(val id: String, val name: String, val outlineLevel: Int?)

    private val byId = mutableMapOf<String, Style>()

    /** Nivel de encabezado (1..6) del estilo, o 0 si no es un encabezado. */
    fun headingLevel(styleId: String?): Int {
        if (styleId.isNullOrBlank()) return 0
        val style = byId[styleId] ?: return 0
        // 1) `w:outlineLvl`: la señal más robusta (0..5 → 1..6).
        style.outlineLevel?.let { return if (it in 0..5) it + 1 else 0 }
        // 2) El nombre del estilo.
        return levelFromName(style.name) ?: levelFromName(style.id) ?: 0
    }

    companion object {

        /**
         * Nivel de encabezado a partir del nombre o del id de un estilo.
         *
         * Acepta "Heading1", "Ttulo1", "Título 1", "heading 1"…
         */
        fun levelFromName(raw: String): Int {
            val ascii = kitoru.platform.stripDiacritics(raw.lowercase())
                .replace(Regex("[^a-z0-9]"), "")
            val match = Regex("^(heading|ttulo|titulo)([1-6])$").find(ascii) ?: return 0
            return match.groupValues[2].toInt()
        }

        fun read(zip: ZipArchive): DocxStyles {
            val styles = DocxStyles()
            val xml = zip.readText("word/styles.xml") ?: return styles
            var currentId: String? = null
            var currentName: String? = null
            var currentOutline: Int? = null
            for (event in XmlScanner(xml).events()) {
                when (event) {
                    is XmlScanner.Event.Open -> when (event.name) {
                        "w:style" -> {
                            currentId = event.attrs["w:styleId"]
                            currentName = null
                            currentOutline = null
                        }
                        "w:name" -> if (currentId != null) currentName = event.attrs["w:val"]
                        "w:outlineLvl" -> if (currentId != null) currentOutline = event.attrs["w:val"]?.toIntOrNull()
                    }
                    is XmlScanner.Event.Close -> if (event.name == "w:style") {
                        val id = currentId
                        if (id != null) styles.byId[id] = Style(id, currentName.orEmpty(), currentOutline)
                        currentId = null
                        currentName = null
                        currentOutline = null
                    }
                    else -> Unit                }
            }
            return styles
        }
    }
}

/**
 * La numeración de Word: `w:numId` → formato de lista.
 *
 * - `w:num w:numId="7"` apunta a un `w:abstractNumId`.
 * - Cada `w:abstractNum` define, por nivel (`w:lvl`), el formato
 *   (`w:numFmt`: "bullet", "decimal", "lowerLetter"…).
 */
internal class DocxNumbering(
    private val formatByNumIdLevel: Map<Pair<String, Int>, String>,
) {
    /**
     * Marcador de lista para un párrafo.
     *
     * @param counters contadores compartidos para los ordinales.
     */
    fun marker(numId: String?, level: Int, counters: MutableMap<Pair<String, Int>, Int>): String? {
        if (numId.isNullOrBlank()) return null
        val format = formatByNumIdLevel[numId to level] ?: "bullet"
        val key = numId to level
        return when (format) {
            "decimal" -> {
                val n = counters.getOrDefault(key, 0) + 1
                counters[key] = n
                "$n. "
            }
            "lowerLetter" -> {
                val n = counters.getOrDefault(key, 0) + 1
                counters[key] = n
                "${('a' + ((n - 1) % 26))}. "
            }
            "upperLetter" -> {
                val n = counters.getOrDefault(key, 0) + 1
                counters[key] = n
                "${('A' + ((n - 1) % 26))}. "
            }
            "lowerRoman" -> "i. "
            "upperRoman" -> "I. "
            else -> "- "
        }
    }

    companion object {
        fun read(zip: ZipArchive): DocxNumbering {
            val xml = zip.readText("word/numbering.xml") ?: return DocxNumbering(emptyMap())

            // Primera pasada: `w:abstractNum` → nivel → formato.
            val formatByAbstractLevel = mutableMapOf<String, MutableMap<Int, String>>()
            var currentAbstract: String? = null
            var currentLevel = 0
            var currentFormat: String? = null
            for (event in XmlScanner(xml).events()) {
                when (event) {
                    is XmlScanner.Event.Open -> when (event.name) {
                        "w:abstractNum" -> currentAbstract = event.attrs["w:abstractNumId"]
                        "w:lvl" -> {
                            currentLevel = event.attrs["w:ilvl"]?.toIntOrNull() ?: 0
                            currentFormat = null
                        }
                        "w:numFmt" -> currentFormat = event.attrs["w:val"]
                    }
                    is XmlScanner.Event.Close -> when (event.name) {
                        "w:lvl" -> {
                            val abstract = currentAbstract
                            if (abstract != null) {
                                formatByAbstractLevel
                                    .getOrPut(abstract) { mutableMapOf() }[currentLevel] = currentFormat ?: "bullet"
                            }
                            currentFormat = null
                        }
                        "w:abstractNum" -> currentAbstract = null
                    }
                    else -> Unit
                }
            }

            // Segunda pasada: `w:num` → `w:abstractNumId`.
            val abstractByNumId = mutableMapOf<String, String>()
            var pendingNumId: String? = null
            for (event in XmlScanner(xml).events()) {
                when (event) {
                    is XmlScanner.Event.Open -> when (event.name) {
                        "w:num" -> pendingNumId = event.attrs["w:numId"]
                        "w:abstractNumId" -> {
                            val numId = pendingNumId
                            val abstract = event.attrs["w:val"]
                            if (numId != null && abstract != null) abstractByNumId[numId] = abstract
                        }
                    }
                    is XmlScanner.Event.Close -> if (event.name == "w:num") pendingNumId = null
                    else -> Unit
                }
            }

            val result = mutableMapOf<Pair<String, Int>, String>()
            for ((numId, abstract) in abstractByNumId) {
                val levels = formatByAbstractLevel[abstract] ?: continue
                for ((level, format) in levels) result[numId to level] = format
            }
            return DocxNumbering(result)
        }
    }
}

/**
 * La máquina de estados del cuerpo de Word.
 *
 * Sus reglas, una por una:
 *
 * | Evento | Acción |
 * |---|---|
 * | `<w:p>` | Cierra el párrafo anterior y abre uno nuevo |
 * | `<w:pStyle w:val="Ttulo1">` | Guarda el estilo del párrafo |
 * | `<w:numPr>` con `<w:ilvl>` y `<w:numId>` | Marca el párrafo como ítem de lista |
 * | `<w:outlineLvl>` | Nivel de esquema del párrafo (0..5) |
 * | `<w:t>` | Captura el texto |
 * | `<w:tab>` | Añade un espacio |
 * | `<w:br/>`, `<w:cr/>` | Añade un salto de línea dentro del párrafo |
 * | `<w:noBreakHyphen/>` | Añade `-` |
 * | `<w:drawing>`, `<w:pict>`, `<w:object>` | Cuenta una imagen |
 * | `<w:hyperlink r:id="…">` | Añade la URL al final del texto del vínculo |
 * | `<w:instrText>`, `<w:fldSimple>` | Se ignoran: son códigos de campo |
 * | `<w:del>` | Se ignora su contenido: es texto borrado |
 * | `<w:ins>` | Normal: su contenido **sí** cuenta |
 * | `<w:tbl>` → `<w:tr>` → `<w:tc>` | Tabla Markdown |
 * | `<w:footnoteReference>` | Cuenta la nota al pie |
 */
internal class DocxBody(
    private val styles: DocxStyles,
    private val numbering: DocxNumbering,
    private val rels: OoxmlRels,
) {
    private val blocks = mutableListOf<DocxBlock>()
    private val buffer = StringBuilder()
    private val listCounters = mutableMapOf<Pair<String, Int>, Int>()
    private val hyperlinkStack = mutableListOf<String>()

    private var paragraphStyle: String? = null
    private var paragraphOutline: Int? = null
    private var numId: String? = null
    private var listLevel = 0
    private var inText = false
    private var inField = false
    private var inDeleted = false
    private var table: MutableList<List<String>>? = null
    private var row: MutableList<String>? = null
    private var cell: StringBuilder? = null

    /** Imágenes encontradas (se ignoran, pero se cuentan). */
    var images = 0
        private set

    /** Notas al pie referenciadas. */
    var footnotes = 0
        private set

    fun consume(events: List<XmlScanner.Event>) {
        for (event in events) {
            when (event) {
                is XmlScanner.Event.Open -> onOpen(event)
                is XmlScanner.Event.Close -> onClose(event)
                is XmlScanner.Event.Text -> onText(event.value)
                is XmlScanner.Event.CData -> onText(event.value)
            }
        }
        flushParagraph()
    }

    fun blocks(): List<DocxBlock> = blocks

    private fun onOpen(event: XmlScanner.Event.Open) {
        when (event.name) {
            "w:p" -> {
                flushParagraph()
                paragraphStyle = null
                paragraphOutline = null
                numId = null
                listLevel = 0
            }
            "w:pStyle" -> paragraphStyle = event.attrs["w:val"]
            "w:outlineLvl" -> paragraphOutline = event.attrs["w:val"]?.toIntOrNull()
            "w:ilvl" -> listLevel = event.attrs["w:val"]?.toIntOrNull() ?: 0
            "w:numId" -> numId = event.attrs["w:val"]
            "w:t" -> inText = true
            "w:tab" -> append(" ")
            "w:br", "w:cr" -> append("\n")
            "w:noBreakHyphen" -> append("-")
            "w:drawing", "w:pict", "w:object" -> images++
            "w:hyperlink" -> hyperlinkStack.add(event.attrs["r:id"].orEmpty())
            "w:instrText", "w:fldSimple" -> inField = true
            "w:del" -> inDeleted = true
            "w:footnoteReference" -> footnotes++
            "w:tbl" -> table = mutableListOf()
            "w:tr" -> row = mutableListOf()
            "w:tc" -> cell = StringBuilder()
        }
    }

    private fun onClose(event: XmlScanner.Event.Close) {
        when (event.name) {
            "w:t" -> inText = false
            "w:instrText", "w:fldSimple" -> inField = false
            "w:del" -> inDeleted = false
            "w:p" -> flushParagraph()
            "w:hyperlink" -> {
                val id = hyperlinkStack.removeLastOrNull().orEmpty()
                val target = rels.target(id)
                if (!target.isNullOrBlank()) append(" ($target)")
            }
            "w:tc" -> {
                val currentCell = cell
                val currentRow = row
                if (currentCell != null && currentRow != null) {
                    currentRow.add(currentCell.toString().trim())
                    cell = null
                }
            }
            "w:tr" -> {
                val currentRow = row
                val currentTable = table
                if (currentRow != null && currentTable != null) {
                    if (currentRow.isNotEmpty()) currentTable.add(currentRow.toList())
                    row = null
                }
            }
            "w:tbl" -> {
                val currentTable = table
                if (currentTable != null && currentTable.isNotEmpty()) {
                    blocks += DocxBlock.Table(currentTable.toList())
                }
                table = null
            }
        }
    }

    private fun onText(value: String) {
        if (!inText || inField || inDeleted) return
        append(value)
    }

    /** Escribe en el párrafo o en la celda de la tabla en curso. */
    private fun append(value: String) {
        val target = cell ?: buffer
        target.append(value)
    }

    /**
     * Vuelca el párrafo al bloque que corresponde.
     *
     * El nivel de encabezado y la condición de lista **sólo se conocen al
     * cerrar el párrafo** (el `w:pPr` puede venir al principio o, en
     * documentos de terceros, al final). Por eso el texto se acumula y se
     * vuelca al final: es lo que hace que el algoritmo funcione con cualquier
     * productor de OOXML.
     */
    private fun flushParagraph() {
        val text = buffer.toString().trim()
        buffer.setLength(0)
        if (text.isEmpty()) return

        val currentCell = cell
        if (currentCell != null) {
            // Estamos dentro de una tabla: el texto va a la celda.
            if (currentCell.isNotEmpty()) currentCell.append(' ')
            currentCell.append(text)
            return
        }
        val level = headingLevel()
        val marker = numbering.marker(numId, listLevel, listCounters)
        blocks += when {
            level > 0 -> DocxBlock.Heading(level, text)
            marker != null -> DocxBlock.ListItem(marker, text)
            else -> DocxBlock.Paragraph(text)
        }
    }

    /** Nivel de encabezado del párrafo actual (0 = no es título). */
    private fun headingLevel(): Int {
        val style = paragraphStyle
        if (!style.isNullOrBlank()) {
            val direct = DocxStyles.levelFromName(style)
            if (direct > 0) return direct
            val fromTable = styles.headingLevel(style)
            if (fromTable > 0) return fromTable
        }
        val outline = paragraphOutline
        if (outline != null && outline in 0..5) return outline + 1
        return 0
    }
}

/** El título del documento, de `docProps/core.xml` (`dc:title`). */
internal fun readCoreTitle(zip: ZipArchive): String {
    val xml = zip.readText("docProps/core.xml") ?: return ""
    val title = StringBuilder()
    var inside = false
    for (event in XmlScanner(xml).events()) {
        when (event) {
            is XmlScanner.Event.Open ->
                if (event.name == "dc:title" || event.name == "title") inside = true
            is XmlScanner.Event.Close ->
                if (event.name == "dc:title" || event.name == "title") inside = false
            is XmlScanner.Event.Text -> if (inside) title.append(event.value)
            is XmlScanner.Event.CData -> if (inside) title.append(event.value)
        }
    }
    return title.toString().trim()
}

// ---------------------------------------------------------------------------
// PPTX
// ---------------------------------------------------------------------------

/**
 * Extrae el texto de un `.pptx`.
 *
 * **El orden de las diapositivas NO es el del nombre del archivo**: la
 * presentación declara su orden en `p:sldIdLst` (`slide10.xml` puede ser la
 * tercera diapositiva). Si esa lista no está, se usa el orden numérico de los
 * archivos como respaldo.
 */
internal object PptxExtractor {

    fun extract(bytes: ByteArray): ExtractionResult {
        val zip = openZip(bytes)
        val presentationXml = zip.readText("ppt/presentation.xml")
        val presentationRels = OoxmlRels.read(zip, "ppt/_rels/presentation.xml.rels")

        val slidePaths = mutableListOf<String>()
        if (presentationXml != null) {
            for (event in XmlScanner(presentationXml).events()) {
                if (event is XmlScanner.Event.Open && event.name == "p:sldId") {
                    val relId = event.attrs["r:id"] ?: continue
                    val target = presentationRels.target(relId) ?: continue
                    slidePaths += resolveOoxmlPath("ppt/presentation.xml", target)
                }
            }
        }
        if (slidePaths.isEmpty()) {
            // Respaldo: los archivos `slideN.xml`, ordenados por número.
            slidePaths += zip.names()
                .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                .sortedBy { numberInName(it) }
        }

        val out = StringBuilder()
        var images = 0
        var index = 0

        for (path in slidePaths) {
            index++
            val slideXml = zip.readText(path) ?: continue
            val content = readSlide(slideXml)
            images += content.images

            out.append("## Diapositiva ").append(index)
            if (content.title.isNotBlank()) out.append(" — ").append(content.title)
            out.append("\n\n")
            for (line in content.lines) out.append(line).append("\n")

            // Las notas del orador suelen tener el material más útil.
            val notes = readNotes(zip, path)
            if (notes.isNotBlank()) {
                out.append("\n### Notas\n\n").append(notes).append("\n")
            }
            out.append("\n")
        }

        val text = out.toString().trim()
        val warnings = mutableListOf<String>()
        if (images > 0) {
            warnings += "$images " +
                (if (images == 1) "imagen omitida" else "imágenes omitidas") +
                " (el texto no incluye su contenido)."
        }
        if (text.isBlank()) {
            warnings += "La presentación no tiene texto (sólo imágenes). Revisá el archivo o pegá el texto a mano."
        }

        return ExtractionResult(
            text = text,
            title = readCoreTitle(zip),
            pages = index, // "páginas" = diapositivas
            warning = warnings.joinToString(" "),
            extractor = "pptx-zip",
            stats = ExtractionStats(
                chars = text.length,
                words = text.split(Regex("\\s+")).count { it.isNotBlank() },
                blocks = index,
                skippedImages = images,
            ),
        )
    }

    /** El texto de una diapositiva: título, líneas e imágenes. */
    private data class SlideContent(val title: String, val lines: List<String>, val images: Int)

    private fun readSlide(xml: String): SlideContent {
        val lines = mutableListOf<String>()
        var title = ""
        var images = 0
        var shapeBuffer = StringBuilder()
        var shapeIsTitle = false
        var inText = false
        var inTable = false
        var cell = StringBuilder()
        var row = StringBuilder()

        fun flushShape() {
            val text = shapeBuffer.toString().trim()
            shapeBuffer = StringBuilder()
            if (text.isEmpty()) return
            if (shapeIsTitle && title.isEmpty()) {
                title = text.lineSequence().first().trim()
                val rest = text.lineSequence().drop(1).joinToString("\n").trim()
                if (rest.isNotEmpty()) lines += rest
            } else {
                lines += text
            }
            shapeIsTitle = false
        }

        for (event in XmlScanner(xml).events()) {
            when (event) {
                is XmlScanner.Event.Open -> when (event.name) {
                    "p:sp", "p:graphicFrame" -> {
                        flushShape()
                        shapeIsTitle = false
                    }
                    "p:ph" -> {
                        val type = event.attrs["type"]
                        if (type == "title" || type == "ctrTitle") shapeIsTitle = true
                    }
                    "p:pic" -> images++
                    "a:tbl" -> inTable = true
                    "a:tr" -> row = StringBuilder()
                    "a:tc" -> cell = StringBuilder()
                    "a:t" -> inText = true
                }
                is XmlScanner.Event.Text -> if (inText) {
                    if (inTable) cell.append(event.value) else shapeBuffer.append(event.value)
                }
                is XmlScanner.Event.CData -> if (inText) {
                    if (inTable) cell.append(event.value) else shapeBuffer.append(event.value)
                }
                is XmlScanner.Event.Close -> when (event.name) {
                    "p:sp", "p:graphicFrame" -> flushShape()
                    "a:t" -> inText = false
                    "a:tc" -> {
                        row.append(cell.toString().trim()).append(" | ")
                        cell = StringBuilder()
                    }
                    "a:tr" -> {
                        val text = row.toString().trim().removeSuffix(" | ")
                        if (text.isNotEmpty()) shapeBuffer.append(text).append('\n')
                        row = StringBuilder()
                    }
                    "a:tbl" -> inTable = false
                }
            }
        }
        flushShape()
        return SlideContent(title, lines, images)
    }

    /** Las notas del orador de una diapositiva. */
    private fun readNotes(zip: ZipArchive, slidePath: String): String {
        val relsPath = slidePath.substringBeforeLast('/') + "/_rels/" + slidePath.substringAfterLast('/') + ".rels"
        val rels = OoxmlRels.read(zip, relsPath)
        val notesPath = rels.targets().firstOrNull { it.endsWith("/notesSlide") || it.contains("notesSlide") }
            ?.let { resolveOoxmlPath(slidePath, it) }
            ?: zip.names().firstOrNull { it.startsWith("ppt/notesSlides/notesSlide") }
                ?.let { candidate ->
                    val number = numberInName(candidate)
                    val slideNumber = numberInName(slidePath)
                    if (number == slideNumber) candidate else null
                }
        val notesXml = notesPath?.let { zip.readText(it) } ?: return ""
        return readSlide(notesXml).lines.joinToString("\n").trim()
    }

    /** El número que aparece en el nombre (`slide12.xml` → 12). */
    private fun numberInName(path: String): Int =
        Regex("(\\d+)").findAll(path).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}

/** El número que aparece en el nombre (`slide12.xml` → 12). */
internal fun numberInName(path: String): Int =
    Regex("(\\d+)").findAll(path).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

// ---------------------------------------------------------------------------
// XLSX
// ---------------------------------------------------------------------------

/**
 * Extrae el texto de un `.xlsx`.
 *
 * Las celdas salen como tablas Markdown, una por hoja. **Las fechas no se
 * convierten**: un `.xlsx` las guarda como un número serial y convertirlas
 * exige leer `styles.xml` (un rabbit hole con bugs de epoch); para el RAG el
 * texto que acompaña a la tabla es lo que importa.
 */
internal object XlsxExtractor {

    /** Tope de filas por hoja. */
    const val MAX_ROWS = 2_000

    /** Tope de columnas por hoja. */
    const val MAX_COLUMNS = 100

    fun extract(bytes: ByteArray): ExtractionResult {
        val zip = openZip(bytes)
        val workbookXml = zip.readText("xl/workbook.xml") ?: throw MaterialExtractionException(
            ExtractionErrorCode.CORRUPT,
            "El archivo .xlsx está dañado o no es un Excel válido (falta xl/workbook.xml).",
        )
        val workbookRels = OoxmlRels.read(zip, "xl/_rels/workbook.xml.rels")
        val shared = readSharedStrings(zip)

        // Hojas: nombre + relación, en el orden de `<sheets>`.
        val sheets = mutableListOf<Pair<String, String>>()
        for (event in XmlScanner(workbookXml).events()) {
            if (event is XmlScanner.Event.Open && event.name == "sheet") {
                val name = event.attrs["name"].orEmpty()
                val relId = event.attrs["r:id"] ?: continue
                sheets += name to relId
            }
        }

        var sheetPaths = sheets.map { (name, relId) ->
            val target = workbookRels.target(relId)
            name to (target?.let { resolveOoxmlPath("xl/workbook.xml", it) } ?: "")
        }
        if (sheetPaths.isEmpty() || sheetPaths.all { it.second.isEmpty() }) {
            // Respaldo: los archivos `sheetN.xml`, ordenados por número.
            val files = zip.names()
                .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .sortedBy { numberInName(it) }
            sheetPaths = files.mapIndexed { index, path ->
                (sheets.getOrNull(index)?.first ?: "Hoja ${index + 1}") to path
            }
        }

        val out = StringBuilder()
        var totalRows = 0
        var truncated = false

        for ((name, path) in sheetPaths) {
            val sheetXml = zip.readText(path) ?: continue
            val rows = readSheet(sheetXml, shared)
            if (rows.isEmpty()) continue
            val limited = if (rows.size > MAX_ROWS) {
                truncated = true
                rows.take(MAX_ROWS)
            } else {
                rows
            }
            out.append("## ").append(name).append("\n\n")
            if (limited.size == 1) {
                out.append(limited.first().joinToString(" | ")).append("\n\n")
            } else {
                out.append(renderTable(limited)).append("\n\n")
            }
            totalRows += limited.size
        }

        val text = out.toString().trim()
        val warnings = mutableListOf<String>()
        if (truncated) {
            warnings += "La hoja tiene más de $MAX_ROWS filas; se indexaron las primeras $MAX_ROWS."
        }
        if (text.isBlank()) {
            warnings += "El archivo Excel no tiene datos (sólo celdas vacías o formatos)."
        }

        return ExtractionResult(
            text = text,
            title = readCoreTitle(zip),
            pages = sheetPaths.size,
            warning = warnings.joinToString(" "),
            extractor = "xlsx-zip",
            stats = ExtractionStats(
                chars = text.length,
                words = text.split(Regex("\\s+")).count { it.isNotBlank() },
                blocks = totalRows,
            ),
        )
    }

    /** `xl/sharedStrings.xml`: las cadenas que las celdas referencian por índice. */
    private fun readSharedStrings(zip: ZipArchive): List<String> {
        val xml = zip.readText("xl/sharedStrings.xml") ?: return emptyList()
        val strings = mutableListOf<String>()
        val current = StringBuilder()
        var inItem = false
        var inText = false
        for (event in XmlScanner(xml).events()) {
            when (event) {
                is XmlScanner.Event.Open -> when (event.name) {
                    "si" -> {
                        inItem = true
                        current.clear()
                    }
                    "t" -> if (inItem) inText = true
                }
                is XmlScanner.Event.Close -> when (event.name) {
                    "t" -> inText = false
                    "si" -> {
                        inItem = false
                        strings += current.toString()
                        current.clear()
                    }
                }
                is XmlScanner.Event.Text -> if (inItem && inText) current.append(event.value)
                is XmlScanner.Event.CData -> if (inItem && inText) current.append(event.value)
            }
        }
        return strings
    }

    /** Lee una hoja: lista de filas, cada una una lista de celdas. */
    private fun readSheet(xml: String, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var cells: MutableMap<Int, String> = mutableMapOf()
        var cellType = ""
        var cellRef = ""
        val value = StringBuilder()
        var inline = StringBuilder()
        var inValue = false
        var inInline = false

        fun finishCell() {
            val column = columnIndex(cellRef)
            val text = resolveCell(cellType, value.toString(), inline.toString(), shared)
            if (text.isNotEmpty()) cells[column] = text
            value.clear()
            inline.clear()
            cellType = ""
            cellRef = ""
        }

        for (event in XmlScanner(xml).events()) {
            when (event) {
                is XmlScanner.Event.Open -> when (event.name) {
                    "row" -> {
                        cells = mutableMapOf()
                    }
                    "c" -> {
                        cellType = event.attrs["t"].orEmpty()
                        cellRef = event.attrs["r"].orEmpty()
                    }
                    "v" -> inValue = true
                    "t" -> if (cellType == "inlineStr") inInline = true
                }
                is XmlScanner.Event.Close -> when (event.name) {
                    "v" -> inValue = false
                    "t" -> if (cellType == "inlineStr") inInline = false
                    "c" -> finishCell()
                    "row" -> {
                        if (cells.isNotEmpty()) {
                            val width = (cells.keys.maxOrNull() ?: -1) + 1
                            val row = MutableList(minOf(width, MAX_COLUMNS)) { cells[it] ?: "" }
                            rows += row
                        }
                    }
                }
                is XmlScanner.Event.Text -> when {
                    inValue -> value.append(event.value)
                    inInline -> inline.append(event.value)
                }
                is XmlScanner.Event.CData -> when {
                    inValue -> value.append(event.value)
                    inInline -> inline.append(event.value)
                }
            }
        }
        return rows
    }

    /** El valor de una celda, según su tipo. */
    private fun resolveCell(type: String, value: String, inline: String, shared: List<String>): String = when (type) {
        "s" -> value.trim().toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
        "inlineStr" -> inline
        "str" -> value
        "b" -> if (value.trim() == "1") "TRUE" else "FALSE"
        "e" -> value
        "d" -> value
        else -> value
    }

    /** Columna de una referencia de celda ("B3" → 1, "AA1" → 26). */
    private fun columnIndex(reference: String): Int {
        var index = 0
        for (character in reference) {
            if (!character.isLetter()) break
            index = index * 26 + (character.uppercaseChar() - 'A' + 1)
        }
        return (index - 1).coerceAtLeast(0)
    }

    private fun renderTable(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val width = rows.maxOf { it.size }
        fun escape(value: String) = value.replace("|", "\\|").replace("\n", " ").trim()
        return buildString {
            fun row(cells: List<String>) {
                val padded = cells + List(width - cells.size) { "" }
                append("| ").append(padded.joinToString(" | ") { escape(it) }).append(" |\n")
            }
            row(rows.first())
            append("| ").append(List(width) { "---" }.joinToString(" | ")).append(" |\n")
            for (r in rows.drop(1)) row(r)
        }.trimEnd()
    }

    /** El número que aparece en el nombre (`sheet12.xml` → 12). */
    private fun numberInName(path: String): Int =
        Regex("(\\d+)").findAll(path).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}
