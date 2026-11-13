package com.visor.archivos.viewers

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visor.archivos.core.Row as TextRow
import com.visor.archivos.core.TextDoc
import com.visor.archivos.ui.LocalTextScale
import com.visor.archivos.ui.tokenColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Estado de la búsqueda, compartido entre la barra superior y el visor de texto. */
@Stable
class SearchState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var matches by mutableStateOf<List<Int>>(emptyList())
    var current by mutableIntStateOf(0)

    fun next() {
        if (matches.isNotEmpty()) current = (current + 1) % matches.size
    }

    fun previous() {
        if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size
    }

    fun close() {
        open = false
        query = ""
        matches = emptyList()
        current = 0
    }
}

private val CodeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)

@Composable
fun TextViewer(
    doc: TextDoc,
    wrap: Boolean,
    search: SearchState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val matchColor = MaterialTheme.colorScheme.tertiaryContainer
    val currentRowColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gutterWidth = (doc.lineCount.toString().length * 9 + 14).dp * LocalTextScale.current

    LaunchedEffect(doc, search.query) {
        if (search.query.isEmpty()) {
            search.matches = emptyList()
            search.current = 0
            return@LaunchedEffect
        }
        delay(200) // no buscar en cada pulsación
        val query = search.query
        val found = withContext(Dispatchers.Default) { doc.search(query) }
        search.matches = found
        search.current = found.indexOfFirst { it >= listState.firstVisibleItemIndex }.coerceAtLeast(0)
    }
    LaunchedEffect(search.matches, search.current) {
        search.matches.getOrNull(search.current)?.let { listState.animateScrollToItem(it) }
    }

    val hScroll = rememberScrollState()
    val currentRow = search.matches.getOrNull(search.current)

    SelectionContainer(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll)),
        ) {
            itemsIndexed(doc.rows, key = { index, _ -> index }) { index, row ->
                val line = remember(row, search.query, dark) {
                    annotate(doc, row, search.query, dark, matchColor)
                }
                Row(
                    Modifier
                        .then(if (wrap) Modifier.fillMaxWidth() else Modifier)
                        .background(if (index == currentRow) currentRowColor else Color.Transparent),
                ) {
                    Text(
                        text = row.number?.toString().orEmpty(),
                        style = CodeStyle,
                        color = gutterColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(gutterWidth).padding(end = 10.dp),
                    )
                    Text(
                        text = line,
                        style = CodeStyle,
                        softWrap = wrap,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            }
            if (doc.truncated) {
                item {
                    Text(
                        "Archivo demasiado grande: solo se muestra el principio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

private fun annotate(doc: TextDoc, row: TextRow, query: String, dark: Boolean, matchColor: Color): AnnotatedString =
    buildAnnotatedString {
        append(doc.text, row.start, row.end)
        for (span in doc.spansFor(row)) {
            val from = maxOf(span.start, row.start) - row.start
            val to = minOf(span.end, row.end) - row.start
            if (from < to) addStyle(SpanStyle(color = tokenColor(span.token, dark)), from, to)
        }
        if (query.isNotEmpty()) {
            var at = doc.text.indexOf(query, row.start, ignoreCase = true)
            while (at >= 0 && at + query.length <= row.end) {
                addStyle(SpanStyle(background = matchColor), at - row.start, at - row.start + query.length)
                at = doc.text.indexOf(query, at + query.length, ignoreCase = true)
            }
        }
    }

/** Aviso centrado para errores y estados vacíos de cualquier visor. */
@Composable
fun CenteredMessage(title: String, detail: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (action != null) Box(Modifier.padding(top = 16.dp)) { action() }
        }
    }
}
