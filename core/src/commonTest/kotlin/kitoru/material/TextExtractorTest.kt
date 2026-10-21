package kitoru.material

import kitoru.domain.Material
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests de la extracción de texto.
 *
 * Todo es **portable**: el `.docx` de prueba se construye en memoria (un ZIP
 * escrito a mano, con las mismas estructuras que usa el extractor), así que
 * el mismo test corre en escritorio y en Android.
 */
class TextExtractorTest {

    // -----------------------------------------------------------------------
    // Limpieza
    // -----------------------------------------------------------------------

    @Test
    fun `el BOM no queda en el texto`() {
        val texto = "\uFEFF# Título\n\nContenido."
        assertFalse(TextCleanup.clean(texto).contains("\uFEFF"))
    }

    @Test
    fun `los espacios se colapsan`() {
        val texto = "Hola    mundo\t\tcon   espacios."
        assertEquals("Hola mundo con espacios.", TextCleanup.clean(texto))
    }

    @Test
    fun `las palabras partidas por un guion se unen`() {
        val texto = "El perceptrón es una unidad auto-\nmóvil muy usada."
        assertEquals(
            "El perceptrón es una unidad automóvil muy usada.",
            TextCleanup.clean(texto),
        )
    }

    @Test
    fun `los numeros de pagina sueltos se eliminan`() {
        val texto = """
            Introducción
            Página 3 de 12
            - 4 -
            5
            El cuerpo del texto
        """.trimIndent()
        val limpio = TextCleanup.clean(texto)
        assertFalse(limpio.contains("Página 3 de 12"))
        assertFalse(limpio.contains("- 4 -"))
        assertFalse(limpio.lines().any { it.trim() == "5" })
        assertContains(limpio, "El cuerpo del texto")
    }

    @Test
    fun `los encabezados repetidos se eliminan`() {
        val texto = """
            Introducción a los modelos lineales

            Primer párrafo con contenido suficiente.

            Segundo párrafo con contenido suficiente.

            Tercer párrafo con contenido suficiente.

            Introducción a los modelos lineales
        """.trimIndent()
        val limpio = TextCleanup.clean(texto)
        assertFalse(limpio.contains("Introducción a los modelos lineales"))
        assertContains(limpio, "Primer párrafo con contenido suficiente.")
    }

    @Test
    fun `los bloques de codigo conservan la indentacion`() {
        val texto = "Antes\n\n```kotlin\nfun main() {\n    println(1)\n}\n```\n\nDespués"
        val limpio = TextCleanup.clean(texto)
        assertContains(limpio, "    println(1)")
    }

    @Test
    fun `los marcadores de pagina del PDF no se borran por ser repetidos`() {
        val texto = "## Página 1\n\nTexto uno.\n\n## Página 2\n\nTexto dos."
        assertContains(TextCleanup.clean(texto), "## Página 1")
        assertContains(TextCleanup.clean(texto), "## Página 2")
    }

    // -----------------------------------------------------------------------
    // El escáner de XML
    // -----------------------------------------------------------------------

    @Test
    fun `un atributo puede contener el signo mayor`() {
        val eventos = XmlScanner("""<w:t xml:space="a > b">hola</w:t>""").events()
        val apertura = eventos.filterIsInstance<XmlScanner.Event.Open>().first()
        assertEquals("a > b", apertura.attrs["xml:space"])
    }

    @Test
    fun `las entidades se resuelven`() {
        val eventos = XmlScanner("<t>Tom &amp; Jerry &#225; &#xE1;</t>").events()
        val texto = eventos.filterIsInstance<XmlScanner.Event.Text>().joinToString("") { it.value }
        assertEquals("Tom & Jerry á á", texto)
    }

    @Test
    fun `el CDATA se conserva literal`() {
        val eventos = XmlScanner("<t><![CDATA[a < b & c]]></t>").events()
        val cdata = eventos.filterIsInstance<XmlScanner.Event.CData>().single()
        assertEquals("a < b & c", cdata.value)
    }

    @Test
    fun `los comentarios y el DOCTYPE se ignoran`() {
        val eventos = XmlScanner("""<!DOCTYPE w:document [ <!ENTITY x "y"> ]><!-- nada --><w:p>texto</w:p>""").events()
        val nombres = eventos.filterIsInstance<XmlScanner.Event.Open>().map { it.name }
        assertEquals(listOf("w:p"), nombres)
    }

    @Test
    fun `una etiqueta auto-cerrada produce apertura y cierre`() {
        val eventos = XmlScanner("<w:tab/>").events()
        assertEquals(2, eventos.size)
        assertTrue(eventos[0] is XmlScanner.Event.Open)
        assertTrue(eventos[1] is XmlScanner.Event.Close)
    }

    // -----------------------------------------------------------------------
    // El ZIP
    // -----------------------------------------------------------------------

    @Test
    fun `un zip con entradas sin comprimir se lee`() {
        val zip = construirZip(
            listOf(Entrada("hola.txt", "hola".toByteArray())),
        )
        val archivo = Zip.open(zip)
        assertTrue(archivo.contains("hola.txt"))
        assertEquals("hola", archivo.readText("hola.txt"))
    }

    @Test
    fun `una entrada comprimida con deflate se descomprime`() {
        // Fijo de un stream DEFLATE (Huffman fijo) generado con zlib.
        val comprimido = hexABytes("cbc8cf490400")
        val zip = construirZip(
            listOf(Entrada("hola.txt", "hola".toByteArray(), comprimido = comprimido, method = 8)),
        )
        val archivo = Zip.open(zip)
        assertEquals("hola", archivo.readText("hola.txt"))
    }

    @Test
    fun `una entrada comprimida con huffman dinamico se descomprime`() {
        val texto = "El perceptrón es una unidad artificial de una red neuronal. " +
            "Se entrena con el algoritmo de peso. La red neuronal tiene capas."
        val comprimido = hexABytes(
            "55cbc10d02310c44d156a68274c18d1b1558c9802c193b72bc956d09db18811b" +
                "877ffa7a37c36476cecaeb7470e170d9e99001c9d2a77615c3e06f24079c47" +
                "868b353c087a25f7e8b1b141ec15a9f58e2f985cd170ff5728a5135da6acf601",
        )
        val zip = construirZip(
            listOf(Entrada("texto.txt", texto.toByteArray(), comprimido = comprimido, method = 8)),
        )
        assertEquals(texto, Zip.open(zip).readText("texto.txt"))
    }

    // -----------------------------------------------------------------------
    // DOCX
    // -----------------------------------------------------------------------

    @Test
    fun `un docx devuelve titulos, parrafos, listas y tablas`() = runTest {
        val resultado = ZipXmlExtractor.extract(docxDePrueba(), "apuntes.docx", "")
        val texto = resultado.text
        assertContains(texto, "# Modelos lineales")
        assertContains(texto, "Un modelo lineal es una función de las variables.")
        assertContains(texto, "- Recta de regresión")
        assertContains(texto, "| x | y |")
        assertContains(texto, "| 1 | 2 |")
        assertEquals("docx-zip", resultado.extractor)
        assertEquals("Apuntes de IA", resultado.title)
    }

    @Test
    fun `un docx con imagenes avisa cuantas se omitieron`() = runTest {
        val resultado = ZipXmlExtractor.extract(docxDePrueba(), "apuntes.docx", "")
        assertContains(resultado.warning, "1 imagen omitida")
        assertEquals(1, resultado.stats.skippedImages)
    }

    @Test
    fun `un docx ignora el texto borrado y los codigos de campo`() = runTest {
        val resultado = ZipXmlExtractor.extract(docxDePrueba(), "apuntes.docx", "")
        assertFalse(resultado.text.contains("borrado"))
        assertFalse(resultado.text.contains("999"))
    }

    @Test
    fun `un docx sin word document xml avisa que esta danado`() = runTest {
        val zip = construirZip(listOf(Entrada("word/styles.xml", "<w:styles/>".toByteArray())))
        val error = assertFailsWith<MaterialExtractionException> {
            ZipXmlExtractor.extract(zip, "apuntes.docx", "")
        }
        assertEquals(ExtractionErrorCode.CORRUPT, error.code)
        assertContains(error.message.orEmpty(), "word/document.xml")
    }

    // -----------------------------------------------------------------------
    // Texto plano y CSV
    // -----------------------------------------------------------------------

    @Test
    fun `un markdown conserva sus encabezados`() = runTest {
        val resultado = PlainTextExtractor.extract(
            "# Apuntes\n\nTexto del apunte.".toByteArray(),
            "apuntes.md",
            "",
        )
        assertContains(resultado.text, "# Apuntes")
        assertEquals("Apuntes", resultado.title)
    }

    @Test
    fun `un csv se convierte en tabla markdown`() = runTest {
        val resultado = PlainTextExtractor.extract(
            "alumno,nota\nAna,7\nBeto,9\n".toByteArray(),
            "notas.csv",
            "",
        )
        assertContains(resultado.text, "| alumno | nota |")
        assertContains(resultado.text, "| Ana | 7 |")
    }

    @Test
    fun `un archivo que no es utf8 avisa y se lee igual`() = runTest {
        val resultado = PlainTextExtractor.extract(
            byteArrayOf('C', 'a', 'f', 0xE9.toByte()),
            "notas.txt",
            "",
        )
        assertContains(resultado.warning, "UTF-8")
        assertContains(resultado.text, "Café")
    }

    // -----------------------------------------------------------------------
    // HTML
    // -----------------------------------------------------------------------

    @Test
    fun `el html no incluye el javascript ni la barra de navegacion`() = runTest {
        val html = """
            <html><head><title>Apuntes</title><script>var x = 1;</script></head>
            <body><nav>Inicio</nav><h1>Tema</h1><p>Texto del <b>artículo</b>.</p></body></html>
        """.trimIndent()
        val resultado = MarkupExtractor.extract(html.toByteArray(), "apuntes.html", "")
        assertFalse(resultado.text.contains("var x"))
        assertFalse(resultado.text.contains("Inicio"))
        assertContains(resultado.text, "# Tema")
        assertContains(resultado.text, "Texto del artículo.")
        assertEquals("Apuntes", resultado.title)
    }

    @Test
    fun `el html decodifica las entidades`() = runTest {
        val resultado = MarkupExtractor.extract(
            "<p>Caf&eacute; &amp; m&aacute;s</p>".toByteArray(),
            "a.html",
            "",
        )
        assertContains(resultado.text, "Café & más")
    }

    @Test
    fun `el html convierte las listas y los vinculos`() = runTest {
        val html = """<ul><li>Primero</li><li>Segundo</li></ul><a href="https://ejemplo.org">Ir</a>"""
        val resultado = MarkupExtractor.extract(html.toByteArray(), "a.html", "")
        assertContains(resultado.text, "- Primero")
        assertContains(resultado.text, "Ir (https://ejemplo.org)")
    }

    // -----------------------------------------------------------------------
    // El despacho y la ingestión
    // -----------------------------------------------------------------------

    @Test
    fun `el despacho es por extension y no por mime`() {
        val registro = ExtractorRegistry.default()
        assertTrue(registro.forFile("apuntes.md", "application/octet-stream") is PlainTextExtractor)
        assertTrue(registro.forFile("apuntes.docx", "text/plain") is ZipXmlExtractor)
        assertTrue(registro.forFile("apuntes.html", "") is MarkupExtractor)
        assertEquals(null, registro.forFile("foto.png", "image/png"))
    }

    @Test
    fun `la ingestion actualiza el material con el texto y el aviso`() = runTest {
        val servicio = MaterialIngestionService()
        val material = Material(
            id = "mat_1",
            courseId = "cur_1",
            title = "Apuntes",
            path = "files/apuntes.md",
        )
        val resultado = servicio.ingest(material, "# Apuntes\n\nContenido del material.".toByteArray())
        assertEquals(MaterialStatus.READY, resultado.status)
        assertContains(resultado.material.extractedText, "Contenido del material.")
        assertEquals("", resultado.material.warning)
    }

    @Test
    fun `un formato sin soporte queda en error con mensaje en espanol`() = runTest {
        val servicio = MaterialIngestionService()
        val material = Material(
            id = "mat_2",
            courseId = "cur_1",
            title = "Foto",
            path = "files/foto.png",
        )
        val resultado = servicio.ingest(material, byteArrayOf(1, 2, 3))
        assertEquals(MaterialStatus.FAILED, resultado.status)
        assertContains(resultado.error, "no es un formato que se pueda indexar")
    }

    @Test
    fun `un archivo sin texto queda para revisar`() = runTest {
        val servicio = MaterialIngestionService()
        val material = Material(
            id = "mat_3",
            courseId = "cur_1",
            title = "Escaneo",
            path = "files/escaneo.md",
        )
        val resultado = servicio.ingest(material, "   ".toByteArray())
        assertEquals(MaterialStatus.NEEDS_REVIEW, resultado.status)
        assertTrue(resultado.extraction.needsOcr)
        assertFalse(resultado.isIndexable)
    }

    // -----------------------------------------------------------------------
    // Ayudantes del test
    // -----------------------------------------------------------------------

    /** Una entrada del ZIP de prueba. */
    private data class Entrada(
        val name: String,
        val content: ByteArray,
        /** Si no se pasa, la entrada se guarda sin comprimir. */
        val comprimido: ByteArray = content,
        val method: Int = 0,
    )

    /**
     * Construye un ZIP en memoria.
     *
     * Se escribe a mano (y no con `java.util.zip`) porque el test vive en
     * `commonTest`, que corre también en Android: `commonMain` no puede usar
     * `java.*`.
     */
    private fun construirZip(entradas: List<Entrada>): ByteArray {
        val archivo = Bytes()
        val central = Bytes()
        for (entrada in entradas) {
            val offset = archivo.size
            val nombre = entrada.name.toByteArray()
            val crc = crc32(entrada.content)

            archivo.writeInt(0x04034b50)
            archivo.writeShort(20)
            archivo.writeShort(0)
            archivo.writeShort(entrada.method)
            archivo.writeShort(0)
            archivo.writeShort(0)
            archivo.writeInt(crc)
            archivo.writeInt(entrada.comprimido.size.toLong())
            archivo.writeInt(entrada.content.size.toLong())
            archivo.writeShort(nombre.size)
            archivo.writeShort(0)
            archivo.write(nombre)
            archivo.write(entrada.comprimido)

            central.writeInt(0x02014b50)
            central.writeShort(20)
            central.writeShort(20)
            central.writeShort(0)
            central.writeShort(entrada.method)
            central.writeShort(0)
            central.writeShort(0)
            central.writeInt(crc)
            central.writeInt(entrada.comprimido.size.toLong())
            central.writeInt(entrada.content.size.toLong())
            central.writeShort(nombre.size)
            central.writeShort(0)
            central.writeShort(0)
            central.writeShort(0)
            central.writeShort(0)
            central.writeInt(0)
            central.writeInt(offset.toLong())
            central.write(nombre)
        }
        val centralOffset = archivo.size
        archivo.write(central.toByteArray())
        archivo.writeInt(0x06054b50)
        archivo.writeShort(0)
        archivo.writeShort(0)
        archivo.writeShort(entradas.size)
        archivo.writeShort(entradas.size)
        archivo.writeInt(central.size.toLong())
        archivo.writeInt(centralOffset.toLong())
        archivo.writeShort(0)
        return archivo.toByteArray()
    }

    /** Un `.docx` mínimo pero completo: título, párrafo, lista, tabla e imagen. */
    private fun docxDePrueba(): ByteArray {
        val document = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr><w:pStyle w:val="Ttulo1"/></w:pPr>
                  <w:r><w:t>Modelos lineales</w:t></w:r>
                </w:p>
                <w:p>
                  <w:r><w:t>Un modelo lineal es</w:t></w:r>
                  <w:r><w:t xml:space="preserve"> una función de las variables.</w:t></w:r>
                </w:p>
                <w:p>
                  <w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="3"/></w:numPr></w:pPr>
                  <w:r><w:t>Recta de regresión</w:t></w:r>
                </w:p>
                <w:p>
                  <w:fldSimple w:instr=" PAGE "><w:r><w:t>999</w:t></w:r></w:fldSimple>
                  <w:del><w:r><w:t>borrado</w:t></w:r></w:del>
                </w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>x</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>y</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>1</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>2</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
                <w:p><w:r><w:drawing/></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()

        val styles = """
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:style w:styleId="Ttulo1"><w:name w:val="heading 1"/><w:outlineLvl w:val="0"/></w:style>
              <w:style w:styleId="Normal"><w:name w:val="Normal"/></w:style>
            </w:styles>
        """.trimIndent()

        val numbering = """
            <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:abstractNum w:abstractNumId="2">
                <w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/></w:lvl>
              </w:abstractNum>
              <w:num w:numId="3"><w:abstractNumId w:val="2"/></w:num>
            </w:numbering>
        """.trimIndent()

        val core = """
            <cp:coreProperties xmlns:cp="x" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <dc:title>Apuntes de IA</dc:title>
            </cp:coreProperties>
        """.trimIndent()

        return construirZip(
            listOf(
                Entrada("word/document.xml", document.toByteArray()),
                Entrada("word/styles.xml", styles.toByteArray()),
                Entrada("word/numbering.xml", numbering.toByteArray()),
                Entrada("docProps/core.xml", core.toByteArray()),
            ),
        )
    }
}

/** Buffer de bytes que crece (el ZIP del test se escribe a mano). */
private class Bytes {
    private var data = ByteArray(1024)

    var size: Int = 0
        private set

    fun write(value: Int) {
        ensure(1)
        data[size++] = value.toByte()
    }

    fun write(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(data, size)
        size += bytes.size
    }

    fun writeShort(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    fun writeInt(value: Long) {
        writeShort((value and 0xFFFF).toInt())
        writeShort(((value shr 16) and 0xFFFF).toInt())
    }

    private fun ensure(extra: Int) {
        if (size + extra <= data.size) return
        var newSize = data.size
        while (newSize < size + extra) newSize *= 2
        data = data.copyOf(newSize)
    }

    fun toByteArray(): ByteArray = data.copyOf(size)
}

/** Convierte un texto hexadecimal en bytes. */
private fun hexABytes(hex: String): ByteArray {
    val clean = hex.filterNot { it.isWhitespace() }
    val out = ByteArray(clean.length / 2)
    for (i in out.indices) {
        out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return out
}
