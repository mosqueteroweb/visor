package com.visor.archivos.core

/** Cómo se va a mostrar un archivo. */
enum class FileKind { TEXT, MARKDOWN, IMAGE, PDF, BINARY }

/** Familias de sintaxis que sabe colorear [Highlighter]. */
enum class Syntax { NONE, C_LIKE, HASH, KEY_VALUE, JSON, MARKUP, SQL }

/**
 * Decide el tipo de un archivo mirando primero su contenido (cabecera) y solo después
 * el tipo MIME y la extensión, que a menudo llegan mal desde otras apps.
 */
object TypeDetector {

    private val markdownExt = setOf("md", "markdown", "mdown", "mkd")
    private val heifBrands = setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")

    fun detect(head: ByteArray, name: String?, mime: String?): FileKind {
        val ext = extensionOf(name)
        val m = mime?.lowercase().orEmpty()

        if (startsWith(head, "%PDF-")) return FileKind.PDF
        if (isRasterImage(head)) return FileKind.IMAGE
        if (startsWith(head, "BM") && (ext == "bmp" || m == "image/bmp" || m == "image/x-ms-bmp")) return FileKind.IMAGE

        if (looksLikeText(head)) {
            if (ext == "svg" || m == "image/svg+xml" || containsSvgTag(head)) return FileKind.IMAGE
            if (ext in markdownExt || m == "text/markdown" || m == "text/x-markdown") return FileKind.MARKDOWN
            return FileKind.TEXT
        }
        return FileKind.BINARY
    }

    fun extensionOf(name: String?): String =
        name?.substringAfterLast('.', "")?.lowercase().orEmpty()

    fun syntaxFor(name: String?, mime: String?): Syntax {
        val m = mime?.lowercase().orEmpty()
        val lower = name?.lowercase().orEmpty()
        return when (extensionOf(name)) {
            "kt", "kts", "java", "js", "mjs", "cjs", "jsx", "ts", "tsx", "c", "h", "cpp", "cc", "hpp", "cs",
            "go", "rs", "swift", "dart", "php", "gradle", "groovy", "scala", "css", "scss", "less", "m", "mm" -> Syntax.C_LIKE
            "py", "sh", "bash", "zsh", "fish", "rb", "pl", "r", "ps1", "mk", "cmake", "tcl" -> Syntax.HASH
            "yml", "yaml", "toml", "ini", "cfg", "conf", "properties", "env", "editorconfig", "gitignore" -> Syntax.KEY_VALUE
            "json", "jsonl", "geojson", "webmanifest", "ipynb" -> Syntax.JSON
            "xml", "html", "htm", "xhtml", "svg", "plist", "xsl", "xsd", "kml", "gpx", "vue" -> Syntax.MARKUP
            "sql" -> Syntax.SQL
            else -> when {
                lower == "dockerfile" || lower == "makefile" -> Syntax.HASH
                "json" in m -> Syntax.JSON
                "xml" in m || "html" in m -> Syntax.MARKUP
                "yaml" in m -> Syntax.KEY_VALUE
                "javascript" in m -> Syntax.C_LIKE
                else -> Syntax.NONE
            }
        }
    }

    private fun isRasterImage(h: ByteArray): Boolean {
        if (bytesAt(h, 0, 0x89, 0x50, 0x4E, 0x47)) return true // PNG
        if (bytesAt(h, 0, 0xFF, 0xD8, 0xFF)) return true // JPEG
        if (startsWith(h, "GIF8")) return true
        if (startsWith(h, "RIFF") && asciiAt(h, 8, 4) == "WEBP") return true
        if (asciiAt(h, 4, 4) == "ftyp" && asciiAt(h, 8, 4) in heifBrands) return true // HEIC / AVIF
        return false
    }

    private fun containsSvgTag(h: ByteArray): Boolean =
        String(h, 0, minOf(h.size, 2048), Charsets.ISO_8859_1).contains("<svg", ignoreCase = true)

    /** Texto si no hay bytes nulos ni apenas caracteres de control (salvo que sea UTF-16 con BOM). */
    fun looksLikeText(h: ByteArray): Boolean {
        if (h.isEmpty()) return true
        if (bytesAt(h, 0, 0xFF, 0xFE) || bytesAt(h, 0, 0xFE, 0xFF)) return true
        var control = 0
        for (b in h) {
            val v = b.toInt() and 0xFF
            if (v == 0) return false
            if (v < 0x20 && v != 0x09 && v != 0x0A && v != 0x0D && v != 0x0C && v != 0x1B) control++
        }
        return control * 100 <= h.size * 2
    }

    private fun startsWith(h: ByteArray, ascii: String) = asciiAt(h, 0, ascii.length) == ascii

    private fun asciiAt(h: ByteArray, offset: Int, length: Int): String? =
        if (h.size >= offset + length) String(h, offset, length, Charsets.ISO_8859_1) else null

    private fun bytesAt(h: ByteArray, offset: Int, vararg values: Int): Boolean {
        if (h.size < offset + values.size) return false
        return values.indices.all { (h[offset + it].toInt() and 0xFF) == values[it] }
    }
}
