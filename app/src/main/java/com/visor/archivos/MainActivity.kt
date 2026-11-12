package com.visor.archivos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.visor.archivos.core.FileKind
import com.visor.archivos.core.Recent
import com.visor.archivos.core.RecentsStore
import com.visor.archivos.ui.VisorTheme
import com.visor.archivos.ui.sizeLabel
import com.visor.archivos.viewers.CenteredMessage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VisorTheme { HomeScreen() } }
    }
}

private fun openViewer(context: Context, uri: Uri, mime: String?) {
    context.startActivity(
        Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val store = remember { RecentsStore(context) }
    var recents by remember { mutableStateOf(store.list()) }
    // Al volver del visor la lista puede haber cambiado
    LifecycleResumeEffect(Unit) {
        recents = store.list()
        onPauseOrDispose { }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openViewer(context, uri, null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Visor") },
                actions = {
                    if (recents.isNotEmpty()) {
                        TextButton(onClick = { store.clear(); recents = emptyList() }) { Text("Vaciar") }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Abrir archivo") },
            )
        },
    ) { padding ->
        if (recents.isEmpty()) {
            CenteredMessage(
                title = "Todavía no has abierto nada",
                detail = "Pulsa «Abrir archivo», o elige Visor en «Abrir con» o «Compartir» desde otra app.\n\nTexto y código, Markdown, imágenes y PDF.",
                modifier = Modifier.padding(padding),
            )
        } else {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                // Una columna en el móvil plegado; dos o más al desplegar
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 320.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Recientes",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                    items(recents, key = { it.uri.toString() }) { recent ->
                        RecentRow(
                            recent,
                            onOpen = { openViewer(context, recent.uri, recent.mime) },
                            onRemove = { store.remove(recent.uri); recents = store.list() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentRow(recent: Recent, onOpen: () -> Unit, onRemove: () -> Unit) {
    val (badge, color) = when (recent.kind) {
        FileKind.PDF -> "PDF" to MaterialTheme.colorScheme.errorContainer
        FileKind.IMAGE -> "IMG" to MaterialTheme.colorScheme.tertiaryContainer
        FileKind.MARKDOWN -> "MD" to MaterialTheme.colorScheme.secondaryContainer
        FileKind.TEXT -> "TXT" to MaterialTheme.colorScheme.primaryContainer
        FileKind.BINARY -> "BIN" to MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val whenOpened = DateUtils.getRelativeTimeSpanString(recent.openedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(badge, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(recent.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(sizeLabel(recent.size), whenOpened).joinToString(" · "),
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, "Quitar de recientes", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
