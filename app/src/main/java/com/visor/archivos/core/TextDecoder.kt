package com.visor.archivos.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Texto decodificado junto con la codificación que se ha usado. */
data class DecodedText(val text: String, val charset: String)

object TextDecoder {

    /**
     * BOM si lo hay; si no, UTF-8 estricto; y si falla, Windows-1252 (nunca falla).
     * Con [truncated] se tolera que el último carácter UTF-8 esté cortado.
     */
    fun decode(bytes: ByteArray, truncated: Boolean = false): DecodedText {
        if (has(bytes, 0xEF, 0xBB, 0xBF)) return DecodedText(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8")
        if (has(bytes, 0xFF, 0xFE)) return DecodedText(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16 LE")
        if (has(bytes, 0xFE, 0xFF)) return DecodedText(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16 BE")

        val length = if (truncated) bytes.size - incompleteUtf8Tail(bytes) else bytes.size
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            DecodedText(decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString(), "UTF-8")
        } catch (_: CharacterCodingException) {
            DecodedText(String(bytes, Charset.forName("windows-1252")), "Windows-1252")
        }
    }

    /** Bytes finales que pertenecen a un carácter UTF-8 a medias. */
    private fun incompleteUtf8Tail(b: ByteArray): Int {
        var i = b.size - 1
        var back = 0
        while (i >= 0 && back < 4) {
            val v = b[i].toInt() and 0xFF
            if (v and 0xC0 != 0x80) {
                val need = when {
                    v and 0x80 == 0 -> 1
                    v and 0xE0 == 0xC0 -> 2
                    v and 0xF0 == 0xE0 -> 3
                    v and 0xF8 == 0xF0 -> 4
                    else -> 1
                }
                return if (need > back + 1) back + 1 else 0
            }
            back++
            i--
        }
        return 0
    }

    private fun has(b: ByteArray, vararg v: Int) =
        b.size >= v.size && v.indices.all { (b[it].toInt() and 0xFF) == v[it] }
}
