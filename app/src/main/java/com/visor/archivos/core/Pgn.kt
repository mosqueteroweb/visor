package com.visor.archivos.core

import java.net.URLEncoder

/** Una partida dentro de un archivo PGN. [title] resume jugadores y resultado para elegirla en una lista. */
data class PgnGame(val title: String, val detail: String, val text: String)

object Pgn {
    /** Por encima de este tamaño el enlace deja de ser fiable y se recurre al portapapeles. */
    const val MAX_URL_CHARS = 6000

    private val gameStart = Regex("^\\[Event\\s", RegexOption.MULTILINE)
    private val tag = Regex("^\\[(\\w+)\\s+\"((?:\\\\.|[^\"\\\\])*)\"\\]", RegexOption.MULTILINE)

    /** Parte el archivo en partidas; si no hay etiquetas [Event], todo el texto es una sola partida. */
    fun games(text: String): List<PgnGame> {
        val starts = gameStart.findAll(text).map { it.range.first }.toList()
        val chunks = if (starts.isEmpty()) {
            listOf(text)
        } else {
            starts.mapIndexed { i, start -> text.substring(start, starts.getOrElse(i + 1) { text.length }) }
        }
        return chunks.map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { i, chunk ->
            val tags = tag.findAll(chunk).associate { it.groupValues[1] to it.groupValues[2] }
            val white = tags["White"].known()
            val black = tags["Black"].known()
            val title = if (white != null || black != null) "${white ?: "?"} – ${black ?: "?"}" else "Partida ${i + 1}"
            val detail = listOfNotNull(tags["Result"].known()?.takeUnless { it == "*" }, tags["Event"].known(), tags["Date"].known()?.takeUnless { it.startsWith("????") })
                .joinToString(" · ")
            PgnGame(title, detail, chunk)
        }
    }

    /** Enlace que abre la partida en el tablero de análisis de chess.com. */
    fun analysisUrl(game: String): String =
        "https://www.chess.com/analysis?tab=analysis&pgn=" + URLEncoder.encode(game, "UTF-8").replace("+", "%20")

    /** Enlace que abre la partida en el tablero de análisis de Lichess. */
    fun lichessUrl(game: String): String =
        "https://lichess.org/analysis/pgn/" + URLEncoder.encode(game, "UTF-8").replace("+", "%20")

    private fun String?.known(): String? = this?.trim()?.takeUnless { it.isEmpty() || it == "?" }
}
