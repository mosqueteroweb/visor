package com.visor.archivos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.visor.archivos.core.DocInfo
import com.visor.archivos.core.Docs
import com.visor.archivos.core.FileKind
import com.visor.archivos.core.RecentsStore
import com.visor.archivos.core.TextDecoder
import com.visor.archivos.core.TextDoc
import com.visor.archivos.core.TypeDetector
import com.visor.archivos.ui.VisorTheme
import com.visor.archivos.ui.kindLabel
import com.visor.archivos.ui.sizeLabel
import com.visor.archivos.viewers.CenteredMessage
import com.visor.archivos.viewers.HexViewer
import com.visor.archivos.viewers.ImageViewer
import com.visor.archivos.viewers.PdfViewer
import com.visor.archivos.viewers.SearchState
import com.visor.archivos.viewers.TextViewer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_TEXT_BYTES = 4 * 1024 * 1024
private const val MAX_HEX_BYTES = 8 * 1024 * 1024

/** Recibe los archivos de «Abrir con» y «Compartir» y los muestra. */
class ViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = resolveUri(intent)
        val mime = intent.type
        setContent {
            VisorTheme {
                if (uri == null) {
                    Scaffold { padding ->
                        CenteredMessage(
                            "No hay nada que mostrar",
                            "La app que ha compartido no ha enviado ningún archivo.",
                            Modifier.padding(padding),
                        )
                    }
                } else {
                    ViewerScreen(uri, mime, onBack = ::finish)
                }
            }
        }
    }

    private fun resolveUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND ->
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(::sharedTextFile)
        else -> intent.data
    }

    /** El texto compartido sin archivo se guarda en la caché para tratarlo como cualquier otro. */
    private fun sharedTextFile(text: String): Uri {
        val dir = File(cacheDir, "compartido").apply { mkdirs() }
        val file = File(dir, "Texto compartido.txt")
        file.writeText(text)
        return Uri.fromFile(file)
    }
}

private enum class ViewMode { TEXT, MARKDOWN, IMAGE, PDF, HEX }

private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

private fun errorMessage(e: Throwable): String = when (e) {
    is SecurityException -> "Ya no hay permiso para leer este archivo. Vuelve a abrirlo desde su app de origen."
    is java.io.FileNotFoundException -> "El archivo ya no existe o se ha movido."
    is OutOfMemoryError -> "El archivo es demasiado grande para la memoria disponible."
    else -> "No se ha podido leer el archivo."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerScreen(uri: Uri, hintMime: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val info by produceState<Load<DocInfo>>(Load.Loading, uri) {
        value = try {
            val doc = Docs.inspect(context, uri, hintMime)
            withContext(Dispatchers.IO) { RecentsStore(context).remember(doc) }
            Load.Ready(doc)
        } catch (e: Exception) {
            Load.Failed(errorMessage(e))
        }
    }

    var forced by rememberSaveable { mutableStateOf<ViewMode?>(null) }
    var wrap by rememberSaveable { mutableStateOf(true) }
    var showSource by rememberSaveable { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val search = remember { SearchState() }
    val listState = rememberLazyListState()

    val doc = (info as? Load.Ready)?.value
    val natural = when (doc?.kind) {
        FileKind.TEXT -> ViewMode.TEXT
        FileKind.MARKDOWN -> ViewMode.MARKDOWN
        FileKind.IMAGE -> ViewMode.IMAGE
        FileKind.PDF -> ViewMode.PDF
        FileKind.BINARY -> ViewMode.HEX
        null -> null
    }
    val mode = forced ?: natural
    val showsRows = mode == ViewMode.TEXT || (mode == ViewMode.MARKDOWN && showSource)

    BackHandler(enabled = search.open) { search.close() }
    LaunchedEffect(showsRows) { if (!showsRows) search.close() }

    Scaffold(
        topBar = {
            if (search.open) {
                SearchBar(search)
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                    },
                    title = {
                        Column {
                            Text(doc?.name ?: "Abriendo…", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            if (doc != null) {
                                Text(
                                    listOfNotNull(kindLabel(doc.kind, doc.name), sizeLabel(doc.size)).joinToString(" · "),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        if (showsRows) IconButton(onClick = { search.open = true }) { Icon(Icons.Filled.Search, "Buscar") }
                        if (doc != null) {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Más opciones") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                @Composable
                                fun entry(label: String, action: () -> Unit) =
                                    DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; action() })

                                if (mode == ViewMode.MARKDOWN) {
                                    entry(if (showSource) "Ver con formato" else "Ver código fuente") { showSource = !showSource }
                                }
                                if (showsRows) entry(if (wrap) "No ajustar líneas" else "Ajustar líneas") { wrap = !wrap }
                                if (showsRows || mode == ViewMode.MARKDOWN) HorizontalDivider()
                                if (forced != null) entry("Vista original") { forced = null }
                                if (mode != ViewMode.TEXT && natural != ViewMode.TEXT && natural != ViewMode.MARKDOWN) {
                                    entry("Ver como texto") { forced = ViewMode.TEXT }
                                }
                                if (mode != ViewMode.HEX) entry("Ver en hexadecimal") { forced = ViewMode.HEX }
                                HorizontalDivider()
                                entry("Información") { showInfo = true }
                                if (doc.uri.scheme == "content") {
                                    entry("Abrir con otra app") { openElsewhere(context, doc, Intent.ACTION_VIEW) }
                                    entry("Compartir") { openElsewhere(context, doc, Intent.ACTION_SEND) }
                                }
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        // Los visores ocupan toda la pantalla y reciben el hueco inferior como relleno del contenido
        val topOnly = Modifier.padding(top = padding.calculateTopPadding())
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding() + 8.dp)

        when (val state = info) {
            Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is Load.Failed -> CenteredMessage("No se puede abrir", state.message, Modifier.padding(padding))
            is Load.Ready -> when (mode) {
                ViewMode.IMAGE -> ImageViewer(uri, state.value.name, onViewAsHex = { forced = ViewMode.HEX }, modifier = Modifier.padding(padding))
                ViewMode.PDF -> PdfViewer(uri, bottom, onViewAsHex = { forced = ViewMode.HEX }, modifier = topOnly)
                ViewMode.HEX -> {
                    val bytes by produceState<Load<Pair<ByteArray, Boolean>>>(Load.Loading, uri) {
                        value = try {
                            Load.Ready(Docs.readLimited(context, uri, MAX_HEX_BYTES))
                        } catch (e: Throwable) {
                            Load.Failed(errorMessage(e))
                        }
                    }
                    when (val b = bytes) {
                        Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        is Load.Failed -> CenteredMessage("No se puede abrir", b.message, Modifier.padding(padding))
                        is Load.Ready -> HexViewer(b.value.first, b.value.second, bottom, topOnly)
                    }
                }
                ViewMode.TEXT, ViewMode.MARKDOWN, null -> {
                    val text by produceState<Load<TextDoc>>(Load.Loading, uri) {
                        value = try {
                            val (bytes, truncated) = Docs.readLimited(context, uri, MAX_TEXT_BYTES)
                            withContext(Dispatchers.Default) {
                                val syntax = TypeDetector.syntaxFor(state.value.name, state.value.mime)
                                Load.Ready(TextDoc.build(TextDecoder.decode(bytes, truncated), syntax, truncated))
                            }
                        } catch (e: Throwable) {
                            Load.Failed(errorMessage(e))
                        }
                    }
                    when (val t = text) {
                        Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        is Load.Failed -> CenteredMessage("No se puede abrir", t.message, Modifier.padding(padding))
                        is Load.Ready -> when {
                            t.value.text.isEmpty() -> CenteredMessage("Archivo vacío", "No contiene texto.", Modifier.padding(padding))
                            showsRows -> TextViewer(t.value, wrap, search, listState, bottom, topOnly)
                            else -> MarkdownView(t.value.text, bottom, topOnly)
                        }
                    }
                }
            }
        }
    }

    if (showInfo && doc != null) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }) { InfoSheet(doc) }
    }
}

@Composable
private fun MarkdownView(text: String, contentPadding: PaddingValues, modifier: Modifier) {
    // Columna de lectura centrada: en pantallas anchas las líneas no se hacen interminables
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        SelectionContainer {
            Markdown(
                content = text,
                // Los títulos por defecto son enormes para la pantalla de un móvil
                typography = markdownTypography(
                    h1 = MaterialTheme.typography.headlineMedium,
                    h2 = MaterialTheme.typography.headlineSmall,
                    h3 = MaterialTheme.typography.titleLarge,
                    h4 = MaterialTheme.typography.titleMedium,
                    h5 = MaterialTheme.typography.titleSmall,
                    h6 = MaterialTheme.typography.labelLarge,
                ),
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).padding(contentPadding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(search: SearchState) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = search::close) { Icon(Icons.Filled.Close, "Cerrar búsqueda") } },
        title = {
            TextField(
                value = search.query,
                onValueChange = { search.query = it },
                placeholder = { Text("Buscar en el archivo") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search.next() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (search.query.isNotEmpty()) {
                Text(
                    if (search.matches.isEmpty()) "0" else "${search.current + 1}/${search.matches.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = search::previous, enabled = search.matches.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowUp, "Anterior") }
            IconButton(onClick = search::next, enabled = search.matches.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowDown, "Siguiente") }
        },
    )
}

@Composable
private fun InfoSheet(doc: DocInfo) {
    val rows = listOfNotNull(
        "Nombre" to doc.name,
        "Tipo detectado" to kindLabel(doc.kind, doc.name),
        doc.mime?.let { "Tipo declarado" to it },
        sizeLabel(doc.size)?.let { "Tamaño" to if (doc.size >= 1024) "$it (${"%,d".format(doc.size)} bytes)" else it },
        (doc.uri.authority ?: doc.uri.scheme)?.let { "Origen" to it },
    )
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        Text("Información", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
        SelectionContainer {
            Column {
                rows.forEach { (label, value) ->
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                    Text(value, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun openElsewhere(context: Context, doc: DocInfo, action: String) {
    val type = doc.mime ?: "*/*"
    val intent = Intent(action).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (action == Intent.ACTION_SEND) {
        intent.setType(type).putExtra(Intent.EXTRA_STREAM, doc.uri)
        intent.clipData = android.content.ClipData.newRawUri(doc.name, doc.uri)
    } else {
        intent.setDataAndType(doc.uri, type)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
        .onFailure { android.widget.Toast.makeText(context, "Ninguna app puede abrir este archivo", android.widget.Toast.LENGTH_SHORT).show() }
}
