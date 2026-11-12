package com.visor.archivos.core

object JsonFormat {
    /**
     * Sangra un JSON sin interpretarlo: no toca números, orden de claves ni cadenas,
     * así que el contenido mostrado es exactamente el del archivo.
     */
    fun pretty(src: String): String {
        val out = StringBuilder(src.length + src.length / 4)
        var depth = 0
        var i = 0
        val n = src.length
        fun newLine() {
            out.append('\n')
            repeat(depth) { out.append("  ") }
        }
        while (i < n) {
            val c = src[i]
            when {
                c == '"' -> {
                    val start = i
                    i++
                    while (i < n && src[i] != '"') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    out.append(src, start, minOf(i + 1, n))
                }
                c == '{' || c == '[' -> {
                    var j = i + 1
                    while (j < n && src[j].isWhitespace()) j++
                    if (j < n && (src[j] == '}' || src[j] == ']')) {
                        out.append(c).append(src[j])
                        i = j
                    } else {
                        out.append(c)
                        depth++
                        newLine()
                    }
                }
                c == '}' || c == ']' -> {
                    depth = maxOf(0, depth - 1)
                    newLine()
                    out.append(c)
                }
                c == ',' -> {
                    out.append(c)
                    if (depth > 0) newLine()
                }
                c == ':' -> out.append(": ")
                // Varios valores seguidos (JSON Lines): uno por línea
                c == '\n' && depth == 0 -> if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                c.isWhitespace() -> Unit
                else -> out.append(c)
            }
            i++
        }
        return out.toString().trimEnd('\n')
    }

    /** Un JSON «minificado» tiene líneas tan largas que el visor las parte en trozos. */
    fun looksMinified(text: String): Boolean {
        var lineStart = 0
        while (lineStart < text.length) {
            var end = text.indexOf('\n', lineStart)
            if (end < 0) end = text.length
            if (end - lineStart > 400) return true
            lineStart = end + 1
        }
        return false
    }
}
