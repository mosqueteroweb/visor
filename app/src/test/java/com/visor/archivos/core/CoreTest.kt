package com.visor.archivos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
