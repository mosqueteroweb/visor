package com.visor.archivos.viewers

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HexStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
private const val HEX = "0123456789ABCDEF"

/** Volcado clásico: desplazamiento, bytes en hexadecimal y su lectura como texto. */
@Composable
fun HexViewer(bytes: ByteArray, truncated: Boolean, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    if (bytes.isEmpty()) {
        CenteredMessage("Archivo vacío", "No contiene ningún byte.", modifier)
        return
    }
    val offsetColor = MaterialTheme.colorScheme.onSurfaceVariant
    val asciiColor = MaterialTheme.colorScheme.primary

    BoxWithConstraints(modifier.fillMaxSize()) {
        // 16 bytes por fila solo si caben; en el móvil plegado, 8
        val perRow = if (maxWidth >= 560.dp) 16 else 8
        val rows = (bytes.size + perRow - 1) / perRow
        val hScroll = rememberScrollState()

        SelectionContainer {
            LazyColumn(
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize().horizontalScroll(hScroll),
            ) {
                items(rows, key = { it }) { row ->
                    val line = remember(row, perRow) {
                        buildAnnotatedString {
                            val start = row * perRow
                            withStyle(SpanStyle(color = offsetColor)) {
                                append(start.toString(16).uppercase().padStart(8, '0'))
                            }
                            append("  ")
                            for (i in 0 until perRow) {
                                val index = start + i
                                if (index < bytes.size) {
                                    val v = bytes[index].toInt() and 0xFF
                                    append(HEX[v ushr 4])
                                    append(HEX[v and 0x0F])
                                } else {
                                    append("  ")
                                }
                                append(if (i == 7 && perRow == 16) "  " else " ")
                            }
                            append(' ')
                            withStyle(SpanStyle(color = asciiColor)) {
                                for (i in 0 until perRow) {
                                    val index = start + i
                                    if (index >= bytes.size) break
                                    val v = bytes[index].toInt() and 0xFF
                                    append(if (v in 0x20..0x7E) v.toChar() else '·')
                                }
                            }
                        }
                    }
                    Text(line, style = HexStyle, softWrap = false, modifier = Modifier.padding(horizontal = 12.dp))
                }
                if (truncated) {
                    item {
                        Text(
                            "Archivo demasiado grande: solo se muestra el principio.",
                            style = MaterialTheme.typography.bodySmall,
                            color = offsetColor,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}
