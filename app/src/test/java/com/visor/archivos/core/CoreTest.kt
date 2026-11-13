package com.visor.archivos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

class CoreTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun `el contenido manda sobre la extension`() {
        assertEquals(FileKind.PDF, TypeDetector.detect("%PDF-1.7\n".toByteArray(), "informe.txt", "text/plain"))
        assertEquals(FileKind.IMAGE, TypeDetector.detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A), "foto.bin", null))
        assertEquals(FileKind.IMAGE, TypeDetector.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0), null, "application/octet-stream"))
        assertEquals(FileKind.IMAGE, TypeDetector.detect("RIFF\u0001\u0002\u0003\u0004WEBPVP8 ".toByteArray(Charsets.ISO_8859_1), null, null))
        assertEquals(FileKind.IMAGE, TypeDetector.detect("\u0000\u0000\u0000\u0018ftypheic".toByteArray(Charsets.ISO_8859_1), null, null))
    }

    @Test fun `un video mp4 no pasa por imagen`() {
        assertEquals(FileKind.BINARY, TypeDetector.detect("\u0000\u0000\u0000\u0018ftypisom".toByteArray(Charsets.ISO_8859_1), "v.mp4", "video/mp4"))
    }

    @Test fun `texto markdown y svg`() {
        assertEquals(FileKind.TEXT, TypeDetector.detect("hola\nmundo".toByteArray(), "notas.log", "application/octet-stream"))
        assertEquals(FileKind.MARKDOWN, TypeDetector.detect("# Título".toByteArray(), "LEEME.MD", null))
        assertEquals(FileKind.IMAGE, TypeDetector.detect("<?xml version=\"1.0\"?><svg xmlns=\"x\"/>".toByteArray(), "dibujo", null))
        assertEquals(FileKind.TEXT, TypeDetector.detect(ByteArray(0), "vacio", null))
        assertEquals(FileKind.BINARY, TypeDetector.detect(bytes(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x08), "a.zip", null))
    }

    @Test fun `codificaciones`() {
        assertEquals(DecodedText("año", "UTF-8"), TextDecoder.decode("año".toByteArray(Charsets.UTF_8)))
        assertEquals(DecodedText("año", "Windows-1252"), TextDecoder.decode(bytes(0x61, 0xF1, 0x6F)))
        assertEquals(DecodedText("hi", "UTF-16 LE"), TextDecoder.decode(bytes(0xFF, 0xFE, 0x68, 0x00, 0x69, 0x00)))
        // Un carácter cortado al final por el límite de lectura no debe hacer caer a Windows-1252
        val cut = "añ".toByteArray(Charsets.UTF_8).copyOf(2)
        assertEquals(DecodedText("a", "UTF-8"), TextDecoder.decode(cut, truncated = true))
    }

    @Test fun `filas y busqueda`() {
        val long = "x".repeat(TextDoc.MAX_ROW_CHARS + 5)
        val doc = TextDoc.build(DecodedText("uno\r\ndos Uno\n$long\nfin", "UTF-8"), Syntax.NONE, false)
        assertEquals(4, doc.lineCount)
        assertEquals(listOf(1, 2, 3, null, 4), doc.rows.map { it.number })
        assertEquals("uno", doc.text.substring(doc.rows[0].start, doc.rows[0].end))
        assertEquals(listOf(0, 1), doc.search("UNO"))
        assertEquals(listOf(4), doc.search("fin"))
        assertTrue(doc.search("nada").isEmpty())
    }

    @Test fun `resaltado`() {
        val json = "{\"a\": \"b\", \"n\": 12, \"t\": true}"
        val tokens = Highlighter.highlight(json, Syntax.JSON).map { json.substring(it.start, it.end) to it.token }
        assertEquals(
            listOf("\"a\"" to Token.KEY, "\"b\"" to Token.STRING, "\"n\"" to Token.KEY, "12" to Token.NUMBER, "\"t\"" to Token.KEY, "true" to Token.KEYWORD),
            tokens,
        )
        val kt = "val x = 1 // nota\nfun f() = \"s\""
        val k = Highlighter.highlight(kt, Syntax.C_LIKE).map { kt.substring(it.start, it.end) to it.token }
        assertEquals(
            listOf("val" to Token.KEYWORD, "1" to Token.NUMBER, "// nota" to Token.COMMENT, "fun" to Token.KEYWORD, "\"s\"" to Token.STRING),
            k,
        )
        val doc = TextDoc.build(DecodedText(kt, "UTF-8"), Syntax.C_LIKE, false)
        assertEquals(2, doc.spansFor(doc.rows[1]).size)
    }
}

class PgnTest {
    private val two = """
        [Event "Torneo"]
        [White "Ana"]
        [Black "Luis"]
        [Result "1-0"]

        1. e4 e5 {apertura abierta} 2. Nf3 1-0

        [Event "?"]
        [White "?"]
        [Black "Eva"]
        [Result "*"]

        1. d4 *
    """.trimIndent()

    @Test fun `parte el archivo en partidas`() {
        val games = Pgn.games(two)
        assertEquals(2, games.size)
        assertEquals("Ana – Luis", games[0].title)
        assertEquals("1-0 · Torneo", games[0].detail)
        assertEquals("? – Eva", games[1].title)
        assertTrue(games[1].text.startsWith("[Event \"?\"]") && games[1].text.endsWith("1. d4 *"))
        assertEquals(listOf("Partida 1"), Pgn.games("1. e4 e5 2. Nf3").map { it.title })
    }

    @Test fun `enlace de analisis y sintaxis`() {
        assertEquals("https://www.chess.com/analysis?tab=analysis&pgn=1.%20e4%20e5%20%7Bx%7D", Pgn.analysisUrl("1. e4 e5 {x}"))
        assertEquals(Syntax.PGN, TypeDetector.syntaxFor("Partida.PGN", null))
        assertEquals(Syntax.PGN, TypeDetector.syntaxFor("sin-extension", "application/x-chess-pgn"))
        val line = "[White \"Ana\"]\n1. e4 {bien} 1-0"
        assertEquals(
            listOf("White" to Token.KEY, "\"Ana\"" to Token.STRING, "1." to Token.NUMBER, "{bien}" to Token.COMMENT, "1-0" to Token.KEYWORD),
            Highlighter.highlight(line, Syntax.PGN).map { line.substring(it.start, it.end) to it.token },
        )
    }
}

class DataTest {
    @Test fun `csv con comillas y separador detectado`() {
        val t = Csv.parse("nombre;nota;comentario\r\nAna;9,5;\"dijo \"\"hola\"\"; y se fue\"\r\n\r\nLuis;7;\"dos\nlíneas\"\n")
        assertEquals(listOf("nombre", "nota", "comentario"), t.header)
        assertEquals(listOf(listOf("Ana", "9,5", "dijo \"hola\"; y se fue"), listOf("Luis", "7", "dos\nlíneas")), t.rows)
        assertEquals(3, t.columns)
        assertEquals('\t', Csv.detectDelimiter("a\tb\tc\n1\t2,5\t3"))
        assertEquals(',', Csv.detectDelimiter("sin separadores"))
        assertTrue(Csv.isCsv("datos.CSV", null) && Csv.isCsv("x", "text/tab-separated-values") && !Csv.isCsv("x.txt", "text/plain"))
    }

    @Test fun `json formateado sin alterar el contenido`() {
        val src = "{\"a\":[1,2.50,{}],\"b\":\"x,y:{\\\"\",\"c\":[]}"
        assertEquals(
            "{\n  \"a\": [\n    1,\n    2.50,\n    {}\n  ],\n  \"b\": \"x,y:{\\\"\",\n  \"c\": []\n}",
            JsonFormat.pretty(src),
        )
        assertEquals("{\n  \"a\": 1\n}\n{\n  \"a\": 2\n}", JsonFormat.pretty("{\"a\":1}\n{\"a\":2}\n"))
        assertTrue(JsonFormat.looksMinified("{" + "\"k\":1,".repeat(100) + "}"))
        assertTrue(!JsonFormat.looksMinified("{\n  \"a\": 1\n}"))
        assertEquals("https://lichess.org/analysis/pgn/1.%20e4%20e5", Pgn.lichessUrl("1. e4 e5"))
    }
}

class EpubTest {
    private val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    private val opf3 = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>El Quijote &amp; más</dc:title><dc:creator id="a">Miguel de Cervantes</dc:creator><!-- <dc:title>Falso</dc:title> --></metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="c1" href="Text/cap%201.xhtml" media-type="application/xhtml+xml"/>
<item id="c2" href="Text/cap2.xhtml" media-type="application/xhtml+xml"/>
<item id="img" href="Images/portada.jpg" media-type="image/jpeg" properties="cover-image"/>
</manifest>
<spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""

    private val nav = """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="landmarks"><ol><li><a href="Text/cap2.xhtml">Marca</a></li></ol></nav>
<nav epub:type="toc"><h1>Índice</h1><ol>
<li><a href="Text/cap%201.xhtml">Primera <b>parte</b></a><ol><li><a href="Text/cap%201.xhtml#s2">Sección&nbsp;2</a></li></ol></li>
<li><a href="Text/cap2.xhtml">Segunda</a></li></ol></nav></body></html>"""

    private fun write(entries: List<Pair<String, String>>): File {
        val file = File.createTempFile("prueba", ".epub")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun epub3(extra: List<Pair<String, String>> = emptyList()) = write(
        listOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to container,
        ) + extra + listOf(
            "OEBPS/content.opf" to opf3,
            "OEBPS/nav.xhtml" to nav,
            "OEBPS/Text/cap 1.xhtml" to "<html><body><p>Uno</p></body></html>",
            "OEBPS/Text/cap2.xhtml" to "<html><body><p>Dos</p></body></html>",
            "OEBPS/Images/portada.jpg" to "JPEG",
        ),
    )

    @Test fun `paquete e indice epub 3`() {
        EpubFile.open(epub3()).use { book ->
            assertEquals("El Quijote & más", book.pkg.title)
            assertEquals("Miguel de Cervantes", book.pkg.author)
            assertEquals(listOf("OEBPS/Text/cap 1.xhtml", "OEBPS/Text/cap2.xhtml"), book.pkg.spine)
            assertEquals("OEBPS/Images/portada.jpg", book.pkg.coverPath)
            assertEquals(
                listOf(
                    TocEntry("Primera parte", "OEBPS/Text/cap 1.xhtml", 0),
                    TocEntry("Sección 2", "OEBPS/Text/cap 1.xhtml#s2", 1),
                    TocEntry("Segunda", "OEBPS/Text/cap2.xhtml", 0),
                ),
                book.toc,
            )
            assertEquals("Uno", Regex("<p>(.*)</p>").find(book.text("OEBPS/Text/cap 1.xhtml")!!)!!.groupValues[1])
            assertTrue(!book.drm)
        }
    }

    @Test fun `lectura en flujo para la estanteria`() {
        val file = epub3()
        val meta = EpubScanner.read { file.inputStream() }
        assertEquals("El Quijote & más", meta.pkg.title)
        assertEquals("JPEG", String(meta.cover!!))
        assertTrue(!meta.drm)
    }

    @Test fun `indice ncx de epub 2 y portada por meta`() {
        val opf2 = """<package version="2.0"><metadata><dc:title>Libro</dc:title><meta name="cover" content="cov"/></metadata>
<manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="a" href="a.html" media-type="application/xhtml+xml"/>
<item id="cov" href="img/c.png" media-type="image/png"/></manifest><spine toc="ncx"><itemref idref="a"/></spine></package>"""
        val ncx = """<ncx><docTitle><text>Libro</text></docTitle><navMap>
<navPoint id="n1"><navLabel><text>Uno</text></navLabel><content src="a.html"/>
<navPoint id="n2"><navLabel><text>Uno.1</text></navLabel><content src="a.html#x"/></navPoint></navPoint>
</navMap><pageList><pageTarget><navLabel><text>1</text></navLabel><content src="a.html#p1"/></pageTarget></pageList></ncx>"""
        val file = write(listOf("META-INF/container.xml" to container, "OEBPS/content.opf" to opf2, "OEBPS/toc.ncx" to ncx, "OEBPS/a.html" to "<p>a</p>"))
        EpubFile.open(file).use { book ->
            assertEquals("Libro", book.pkg.title)
            assertEquals(null, book.pkg.author)
            assertEquals("OEBPS/img/c.png", book.pkg.coverPath)
            assertEquals(listOf(TocEntry("Uno", "OEBPS/a.html", 0), TocEntry("Uno.1", "OEBPS/a.html#x", 1)), book.toc)
        }
    }

    @Test fun `rutas relativas`() {
        assertEquals("OEBPS/Images/x y.png#f", Epub.resolve("OEBPS/Text/a.xhtml", "../Images/x%20y.png#f"))
        assertEquals("OEBPS/a.xhtml#n", Epub.resolve("OEBPS/a.xhtml", "#n"))
        assertEquals("b.html", Epub.resolve("a.html", "./b.html"))
        assertEquals("https://example.com/x", Epub.resolve("OEBPS/a.xhtml", "https://example.com/x"))
    }

    @Test fun `drm`() {
        val font = """<encryption><EncryptedData><CipherData><CipherReference URI="OEBPS/fonts/a.otf"/></CipherData></EncryptedData></encryption>"""
        val text = """<encryption><EncryptedData><CipherData><CipherReference URI="OEBPS/Text/cap1.xhtml"/></CipherData></EncryptedData></encryption>"""
        assertTrue(!Epub.isDrm(font, false))
        assertTrue(Epub.isDrm(text, false))
        assertTrue(Epub.isDrm(null, true))
        assertTrue(EpubFile.open(epub3(listOf("META-INF/encryption.xml" to text))).use { it.drm })
    }

    @Test fun `deteccion de epub`() {
        val head = "PK\u0003\u0004".toByteArray(Charsets.ISO_8859_1) + ByteArray(26) + "mimetypeapplication/epub+zip".toByteArray()
        assertEquals(FileKind.EPUB, TypeDetector.detect(head, "libro.bin", null))
        assertEquals(FileKind.BINARY, TypeDetector.detect("PK\u0003\u0004\u0000\u0000".toByteArray(Charsets.ISO_8859_1), "a.zip", null))
    }
}

class LimitsTest {
    @Test fun `lecturas y copias con tope`() {
        val data = ByteArray(1000) { 7 }
        assertEquals(1000, Streams.readLimited(data.inputStream(), 1000)!!.size)
        assertEquals(null, Streams.readLimited(data.inputStream(), 999))
        val out = java.io.ByteArrayOutputStream()
        Streams.copyLimited(data.inputStream(), out, 1000)
        assertEquals(1000, out.size())
        assertTrue(runCatching { Streams.copyLimited(data.inputStream(), java.io.ByteArrayOutputStream(), 999) }.exceptionOrNull() is TooLargeException)
    }

    @Test fun `un capitulo desmesurado no se carga`() {
        val file = File.createTempFile("grande", ".epub").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(name: String, content: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() }
            add("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"c.opf\"/></rootfiles></container>".toByteArray())
            add("c.opf", "<package><manifest><item id=\"a\" href=\"a.html\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"a\"/></spine></package>".toByteArray())
            add("a.html", ByteArray(EpubFile.MAX_ENTRY_BYTES + 1) { 'x'.code.toByte() })
        }
        EpubFile.open(file).use { book ->
            assertEquals(null, book.text("a.html"))
            assertTrue(book.tooLarge("a.html") && !book.tooLarge("no-existe.html"))
        }
    }
}
