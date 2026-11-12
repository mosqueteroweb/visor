package com.visor.archivos.libros

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.visor.archivos.viewers.CenteredMessage
import java.io.File
import kotlin.math.roundToInt

/** Estantería: los libros abiertos y los de las carpetas añadidas, primero los últimos leídos. */
@Composable
fun ShelfContent(padding: PaddingValues) {
    val context = LocalContext.current
    val books by Library.books.collectAsState()
    val scan by Library.scan.collectAsState()
    val sorted = remember(books) {
        books.sortedWith(compareByDescending<Book> { it.openedAt }.thenByDescending { it.addedAt })
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        scan?.let { s ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    if (s.total > 0) "Analizando «${s.folder}»: ${s.done} de ${s.total}" else "Buscando libros en «${s.folder}»…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.total > 0) {
                    LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        }
        if (sorted.isEmpty()) {
            if (scan == null) {
                CenteredMessage(
                    title = "La estantería está vacía",
                    detail = "Pulsa «Añadir» para elegir un libro o una carpeta con libros ePub. " +
                        "Los ePub que abras desde otras apps también aparecerán aquí.\n\nLos libros con DRM no se pueden abrir.",
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 112.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(sorted, key = { it.id }) { book ->
                    BookCard(
                        book,
                        onOpen = { ReaderActivity.start(context, book.id) },
                        onRemove = { Library.remove(book.id) },
                    )
                }
            }
        }
    }
}

@Composable
fun ShelfAddButton() {
    var menu by remember { mutableStateOf(false) }
    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Library.addBook(uri)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) Library.addFolder(uri)
    }
    Box {
        ExtendedFloatingActionButton(
            onClick = { menu = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Añadir") },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Añadir libro") },
                onClick = {
                    menu = false
                    bookPicker.launch(arrayOf("application/epub+zip"))
                },
            )
            DropdownMenuItem(
                text = { Text("Añadir carpeta") },
                onClick = {
                    menu = false
                    folderPicker.launch(null)
                },
            )
        }
    }
}

/** Acciones de la barra superior en la pestaña de libros. */
@Composable
fun ShelfActions() {
    val folders by Library.folders.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    if (folders.isEmpty()) return

    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más opciones") }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(
            text = { Text("Volver a analizar carpetas") },
            onClick = {
                menu = false
                Library.rescanAll()
            },
        )
        DropdownMenuItem(
            text = { Text("Carpetas…") },
            onClick = {
                menu = false
                showFolders = true
            },
        )
    }
    if (showFolders) FoldersDialog(folders, onDismiss = { showFolders = false })
}

@Composable
private fun FoldersDialog(folders: List<Folder>, onDismiss: () -> Unit) {
    val books by Library.books.collectAsState()
    LaunchedEffect(folders.isEmpty()) { if (folders.isEmpty()) onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Carpetas") },
        text = {
            Column {
                Text(
                    "Al quitar una carpeta salen sus libros de la estantería. Los archivos no se borran.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                folders.forEach { folder ->
                    val count = books.count { it.folder == folder.uri }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (count == 1) "1 libro" else "$count libros",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { Library.removeFolder(folder.uri) }) { Icon(Icons.Filled.Close, "Quitar ${folder.name}") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun BookCard(book: Book, onOpen: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }, onLongClickLabel = "Opciones")
                .padding(4.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            ) {
                if (book.cover != null) {
                    AsyncImage(
                        model = File(book.cover),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                if (book.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { book.progress },
                        trackColor = Color.Black.copy(alpha = 0.25f),
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    )
                }
            }
            Text(
                book.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            val detail = listOfNotNull(book.author, progressLabel(book)).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Quitar de la estantería") },
                onClick = {
                    menu = false
                    onRemove()
                },
            )
        }
    }
}

private fun progressLabel(book: Book): String? = when {
    book.progress >= 0.995f -> "Terminado"
    book.openedAt > 0 -> "${(book.progress * 100).roundToInt()} %"
    else -> null
}
