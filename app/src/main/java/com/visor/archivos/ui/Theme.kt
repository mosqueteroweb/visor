package com.visor.archivos.ui

import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.visor.archivos.core.FileKind
import com.visor.archivos.core.Token

private val Light = lightColorScheme(primary = Color(0xFF1F5FA8), secondary = Color(0xFF52607A))
private val Dark = darkColorScheme(primary = Color(0xFFA6C8FF), secondary = Color(0xFFBAC7E4))

@Composable
fun VisorTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Colores del resaltado de sintaxis, con contraste suficiente en claro y en oscuro. */
fun tokenColor(token: Token, dark: Boolean): Color = when (token) {
    Token.COMMENT -> if (dark) Color(0xFF8B949E) else Color(0xFF6A737D)
    Token.STRING -> if (dark) Color(0xFF9ECE6A) else Color(0xFF1A7F37)
    Token.NUMBER -> if (dark) Color(0xFFFFA657) else Color(0xFFB35900)
    Token.KEYWORD -> if (dark) Color(0xFFD2A8FF) else Color(0xFF8250DF)
    Token.KEY -> if (dark) Color(0xFF79C0FF) else Color(0xFF0550AE)
}

fun kindLabel(kind: FileKind, name: String): String {
    val ext = name.substringAfterLast('.', "").uppercase().takeIf { it.length in 1..5 }
    return when (kind) {
        FileKind.PDF -> "PDF"
        FileKind.MARKDOWN -> "Markdown"
        FileKind.IMAGE -> ext?.let { "Imagen $it" } ?: "Imagen"
        FileKind.TEXT -> ext?.let { "Texto $it" } ?: "Texto"
        FileKind.BINARY -> ext?.let { "Binario $it" } ?: "Binario"
    }
}

@Composable
fun sizeLabel(size: Long): String? =
    if (size >= 0) Formatter.formatShortFileSize(LocalContext.current, size) else null
