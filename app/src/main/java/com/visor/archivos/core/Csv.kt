package com.visor.archivos.core

/** Datos tabulares: la primera fila se trata como cabecera. */
class Table(val header: List<String>, val rows: List<List<String>>) {
    val columns: Int = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
}

object Csv {
    private val extensions = setOf("csv", "tsv", "tab")
    private val candidates = charArrayOf(',', ';', '\t', '|')

    fun isCsv(name: String?, mime: String?): Boolean {
        val m = mime?.lowercase().orEmpty()
        return TypeDetector.extensionOf(name) in extensions || "csv" in m || "comma-separated" in m || "tab-separated" in m
    }

    /** El separador que más se repite, fuera de comillas, en las primeras líneas. */
    fun detectDelimiter(text: String): Char {
        val counts = IntArray(candidates.size)
        var quoted = false
        var lines = 0
        for (c in text) {
            if (c == '"') quoted = !quoted
            if (quoted) continue
            if (c == '\n' && ++lines >= 10) break
            val i = candidates.indexOf(c)
            if (i >= 0) counts[i]++
        }
        val best = counts.indices.maxByOrNull { counts[it] } ?: 0
        return if (counts[best] == 0) ',' else candidates[best]
    }

    fun parse(text: String, delimiter: Char = detectDelimiter(text)): Table {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() {
            row.add(cell.toString())
            cell.setLength(0)
        }
        fun endRow() {
            endCell()
            // Las líneas en blanco no son filas
            if (row.size > 1 || row[0].isNotEmpty()) rows.add(row)
            row = ArrayList()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                quoted -> cell.append(c)
                c == delimiter -> endCell()
                c == '\n' -> endRow()
                c == '\r' -> Unit
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return Table(rows.firstOrNull().orEmpty(), rows.drop(1))
    }
}
