package com.visor.archivos.core

/** Fila visible: una línea o un trozo de una línea muy larga. [number] es nulo en las continuaciones. */
data class Row(val number: Int?, val start: Int, val end: Int)

/** Texto ya preparado para pintarse por filas. */
class TextDoc(
    val text: String,
    val rows: List<Row>,
    val spans: List<Span>,
    val lineCount: Int,
    val charset: String,
    val truncated: Boolean,
) {
    /** Tramos de color que tocan la fila, por búsqueda binaria (los tramos van ordenados y no se solapan). */
    fun spansFor(row: Row): List<Span> {
        if (spans.isEmpty()) return emptyList()
        var lo = 0
        var hi = spans.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (spans[mid].end <= row.start) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<Span>()
        var i = lo
        while (i < spans.size && spans[i].start < row.end) {
            out += spans[i]
            i++
        }
        return out
    }

    /** Índices de las filas que contienen [query], sin distinguir mayúsculas. */
    fun search(query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var row = 0
        var at = text.indexOf(query, 0, ignoreCase = true)
        while (at >= 0) {
            while (row < rows.size - 1 && rows[row + 1].start <= at) row++
            if (out.isEmpty() || out.last() != row) out += row
            at = text.indexOf(query, at + query.length, ignoreCase = true)
        }
        return out
    }

    companion object {
        /** Por encima de este tamaño no se colorea: el coste no compensa. */
        const val MAX_HIGHLIGHT_CHARS = 300_000

        /** Las líneas más largas se parten en trozos para que una sola fila no bloquee el dibujado. */
        const val MAX_ROW_CHARS = 2_000

        fun build(decoded: DecodedText, syntax: Syntax, truncated: Boolean): TextDoc {
            val text = decoded.text
            val rows = ArrayList<Row>()
            var line = 0
            var start = 0
            while (start <= text.length) {
                var end = text.indexOf('\n', start)
                if (end < 0) end = text.length
                val contentEnd = if (end > start && text[end - 1] == '\r') end - 1 else end
                line++
                var from = start
                var first = true
                do {
                    val to = minOf(contentEnd, from + MAX_ROW_CHARS)
                    rows += Row(if (first) line else null, from, to)
                    first = false
                    from = to
                } while (from < contentEnd)
                start = end + 1
            }
            val spans = if (text.length <= MAX_HIGHLIGHT_CHARS) Highlighter.highlight(text, syntax) else emptyList()
            return TextDoc(text, rows, spans, line, decoded.charset, truncated)
        }
    }
}
