package com.visor.archivos.viewers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visor.archivos.core.Csv
import com.visor.archivos.core.Table
import com.visor.archivos.ui.LocalTextScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val MAX_CELL_CHARS = 36

/** CSV/TSV como tabla: cabecera fija, columnas al ancho de su contenido y desplazamiento en ambos ejes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TableViewer(
    text: String,
    search: SearchState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val table by produceState<Table?>(null, text) {
        value = withContext(Dispatchers.Default) { Csv.parse(text) }
    }
    val t = table
    if (t == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (t.columns == 0) {
        CenteredMessage("Tabla vacía", "El archivo no contiene filas.", modifier)
        return
    }

    // Ancho de cada columna según su celda más larga (mirando un tramo inicial, con tope)
    val scale = LocalTextScale.current
    var openCell by remember { mutableStateOf<Pair<String, String>?>(null) }
    val widths = remember(t, scale) {
        List(t.columns) { col ->
            var longest = t.header.getOrNull(col)?.length ?: 0
            for (i in 0 until minOf(t.rows.size, 2000)) {
                longest = maxOf(longest, t.rows[i].getOrNull(col)?.length ?: 0)
            }
            (longest.coerceIn(3, MAX_CELL_CHARS) * 8 + 24).dp * scale
        }
    }
    val gutter = (t.rows.size.toString().length * 9 + 20).dp * scale
    val showCell: (Int, String) -> Unit = { col, value -> openCell = (t.header.getOrNull(col)?.ifBlank { null } ?: "Columna ${col + 1}") to value }

    LaunchedEffect(t, search.query) {
        if (search.query.isEmpty()) {
            search.matches = emptyList()
            search.current = 0
            return@LaunchedEffect
        }
        delay(200)
        val query = search.query
        val found = withContext(Dispatchers.Default) {
            t.rows.indices.filter { r -> t.rows[r].any { it.contains(query, ignoreCase = true) } }
        }
        search.matches = found
        search.current = 0
    }
    // +1: la cabecera ocupa la primera posición de la lista
    LaunchedEffect(search.matches, search.current) {
        search.matches.getOrNull(search.current)?.let { listState.animateScrollToItem(it + 1) }
    }
    val matchSet = remember(search.matches) { search.matches.toHashSet() }
    val currentRow = search.matches.getOrNull(search.current)

    val headerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val matchColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
    val currentColor = MaterialTheme.colorScheme.tertiaryContainer
    val hScroll = rememberScrollState()

    openCell?.let { (title, value) ->
        AlertDialog(
            onDismissRequest = { openCell = null },
            title = { Text(title) },
            text = { SelectionContainer { Text(value, modifier = Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { openCell = null }) { Text("Cerrar") } },
        )
    }

    SelectionContainer(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().horizontalScroll(hScroll),
        ) {
            stickyHeader {
                TableRow("#", t.header, widths, gutter, bold = true, background = headerColor, onOpen = showCell)
                HorizontalDivider()
            }
            itemsIndexed(t.rows) { index, row ->
                val background = when {
                    index == currentRow -> currentColor
                    index in matchSet -> matchColor
                    else -> Color.Transparent
                }
                TableRow((index + 1).toString(), row, widths, gutter, bold = false, background = background, onOpen = showCell)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

/** ¿Se corta la celda al dibujarla? Entonces se puede tocar para leerla entera. */
private fun isCut(value: String) = value.length > MAX_CELL_CHARS * 2 || '\n' in value

@Composable
private fun TableRow(
    label: String,
    cells: List<String>,
    widths: List<Dp>,
    gutter: Dp,
    bold: Boolean,
    background: Color,
    onOpen: (Int, String) -> Unit,
) {
    Row(Modifier.background(background)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(gutter).padding(horizontal = 8.dp, vertical = 8.dp),
        )
        widths.forEachIndexed { col, width ->
            val value = cells.getOrNull(col).orEmpty()
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(width)
                    .then(if (isCut(value)) Modifier.clickable(onClickLabel = "Ver celda completa") { onOpen(col, value) } else Modifier)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}
