package com.visor.archivos.viewers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MAX_ZOOM = 4f
private const val MAX_BITMAP_PIXELS = 16_000_000L

/** PDF abierto. PdfRenderer solo admite una página abierta a la vez, de ahí el cerrojo. */
/** La búsqueda y la extracción de texto de PDF existen en el sistema desde Android 15. */
val pdfTextSupported: Boolean get() = Build.VERSION.SDK_INT >= 35

/** Una coincidencia de la búsqueda: página y rectángulos (en puntos de la página) que la cubren. */
private class PdfHit(val page: Int, val rects: List<RectF>)

/** Texto de todas las páginas, para verlo o copiarlo. Vacío si el PDF es solo imagen. */
suspend fun extractPdfText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val doc = PdfDoc.open(context, uri)
    try {
        buildString {
            for (page in 0 until doc.pageCount) {
                val text = doc.text(page).trim()
                if (text.isEmpty()) continue
                if (isNotEmpty()) append("\n\n")
                if (doc.pageCount > 1) append("— Página ${page + 1} —\n\n")
                append(text)
            }
        }
    } finally {
        doc.close()
    }
}

private class PdfDoc(
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
    private val tempFile: File?,
) : Closeable {
    private val mutex = Mutex()
    private var closed = false
    val pageCount: Int = renderer.pageCount

    /** Proporción ancho/alto de cada página; 0 mientras no se conoce. */
    val ratios = FloatArray(pageCount)

    /** Ancho de cada página en puntos, para situar las coincidencias sobre la imagen. */
    val widths = FloatArray(pageCount)
    val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 4).coerceAtMost(160L shl 20).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (closed) return@withContext null
            renderer.openPage(index).use { page ->
                val ratio = page.width.toFloat() / page.height
                ratios[index] = ratio
                widths[index] = page.width.toFloat()
                var w = widthPx.coerceAtLeast(1)
                var h = (w / ratio).toInt().coerceAtLeast(1)
                if (w.toLong() * h > MAX_BITMAP_PIXELS) {
                    val k = kotlin.math.sqrt(MAX_BITMAP_PIXELS.toDouble() / (w.toLong() * h))
                    w = (w * k).toInt()
                    h = (h * k).toInt()
                }
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(index, bitmap)
                bitmap
            }
        }
    }

    suspend fun search(index: Int, query: String): List<List<RectF>> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (closed || Build.VERSION.SDK_INT < 35) return@withContext emptyList()
            renderer.openPage(index).use { page ->
                widths[index] = page.width.toFloat()
                page.searchText(query).map { it.bounds }
            }
        }
    }

    suspend fun text(index: Int): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (closed || Build.VERSION.SDK_INT < 35) return@withContext ""
            renderer.openPage(index).use { page -> page.textContents.joinToString("\n") { it.text } }
        }
    }

    override fun close() {
        // Fuera de la interfaz: espera a que termine la página en curso antes de cerrar
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                closed = true
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
                tempFile?.delete()
            }
        }
    }

    companion object {
        /** Abre el PDF; si el origen no permite acceso aleatorio, lo copia antes a la caché. */
        fun open(context: Context, uri: Uri): PdfDoc {
            val direct = runCatching {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("sin descriptor")
                try {
                    PdfDoc(PdfRenderer(pfd), pfd, null)
                } catch (e: Exception) {
                    pfd.close()
                    throw e
                }
            }
            direct.getOrNull()?.let { return it }
            if (direct.exceptionOrNull() is SecurityException) throw direct.exceptionOrNull()!!

            val temp = File.createTempFile("visor", ".pdf", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { input.copyTo(it) }
                } ?: error("No se puede leer el archivo")
                val pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
                return PdfDoc(PdfRenderer(pfd), pfd, temp)
            } catch (e: Exception) {
                temp.delete()
                throw e
            }
        }
    }
}

private sealed interface PdfState {
    data object Loading : PdfState
    data class Ready(val doc: PdfDoc) : PdfState
    data class Failed(val protectedByPassword: Boolean) : PdfState
}

@Composable
fun PdfViewer(uri: Uri, contentPadding: PaddingValues, search: SearchState, onViewAsHex: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by produceState<PdfState>(PdfState.Loading, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                val doc = PdfDoc.open(context, uri)
                if (doc.pageCount > 0) doc.render(0, 64) // deja conocida la proporción de la primera página
                PdfState.Ready(doc)
            } catch (e: SecurityException) {
                PdfState.Failed(protectedByPassword = true)
            } catch (e: Exception) {
                PdfState.Failed(protectedByPassword = false)
            }
        }
    }

    when (val s = state) {
        PdfState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is PdfState.Failed -> CenteredMessage(
            title = if (s.protectedByPassword) "PDF protegido con contraseña" else "No se puede abrir el PDF",
            detail = if (s.protectedByPassword) "Este visor no abre documentos cifrados." else "El archivo está dañado o incompleto.",
            modifier = modifier,
            action = { TextButton(onClick = onViewAsHex) { Text("Ver en hexadecimal") } },
        )
        is PdfState.Ready -> {
            DisposableEffect(s.doc) { onDispose { s.doc.close() } }
            PdfPages(s.doc, contentPadding, search, modifier)
        }
    }
}

@Composable
private fun PdfPages(doc: PdfDoc, contentPadding: PaddingValues, search: SearchState, modifier: Modifier) {
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }

    var hits by remember { mutableStateOf<List<PdfHit>>(emptyList()) }
    LaunchedEffect(doc, search.query) {
        hits = emptyList()
        search.matches = emptyList()
        search.current = 0
        val query = search.query.trim()
        if (query.length < 2) return@LaunchedEffect
        delay(300) // no buscar en cada pulsación
        val found = ArrayList<PdfHit>()
        for (page in 0 until doc.pageCount) {
            doc.search(page, query).forEach { found += PdfHit(page, it) }
            // En documentos largos los resultados van apareciendo por tandas
            if (page % 10 == 9 || page == doc.pageCount - 1) {
                hits = found.toList()
                search.matches = found.map { it.page }
            }
        }
    }
    val hitsByPage = remember(hits) { hits.withIndex().groupBy { it.value.page } }

    fun zoomBy(factor: Float, centroid: Offset) {
        val target = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val applied = target / zoom
        if (applied == 1f) return
        // Mantiene bajo los dedos el punto que se estaba mirando
        val dy = (listState.firstVisibleItemScrollOffset + centroid.y) * (applied - 1f)
        val dx = (hScroll.value + centroid.x) * (applied - 1f)
        zoom = target
        scope.launch {
            withFrameNanos { }
            listState.scrollBy(dy)
            hScroll.scrollBy(dx)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        val baseWidth = maxWidth
        val basePx = with(LocalDensity.current) { baseWidth.roundToPx() }
        val fallbackRatio = doc.ratios.firstOrNull()?.takeIf { it > 0f } ?: 0.707f

        LaunchedEffect(search.current, hits.isNotEmpty()) {
            val hit = hits.getOrNull(search.current) ?: return@LaunchedEffect
            val scale = doc.widths[hit.page].takeIf { it > 0f }?.let { basePx * zoom / it } ?: 0f
            val top = hit.rects.minOfOrNull { it.top } ?: 0f
            // Deja la coincidencia a un tercio de la pantalla, no pegada al borde
            listState.animateScrollToItem(hit.page, (top * scale).toInt() - constraints.maxHeight / 3)
        }

        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Solo con dos dedos; con uno, el desplazamiento normal sigue su curso
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val change = event.calculateZoom()
                                if (change != 1f) zoomBy(change, event.calculateCentroid(useCurrent = false))
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { at -> zoomBy(if (zoom > 1.01f) 1f / zoom else 2f, at) })
                }
                .horizontalScroll(hScroll),
        ) {
            items(doc.pageCount, key = { it }) { index ->
                PdfPage(doc, index, (basePx * zoom).toInt(), baseWidth * zoom, fallbackRatio, hitsByPage[index].orEmpty(), search.current)
            }
        }

        val current by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = RoundedCornerShape(50),
            modifier = Modifier.align(Alignment.BottomCenter).padding(contentPadding).padding(bottom = 12.dp),
        ) {
            Text(
                "$current / ${doc.pageCount}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PdfPage(
    doc: PdfDoc,
    index: Int,
    widthPx: Int,
    width: androidx.compose.ui.unit.Dp,
    fallbackRatio: Float,
    hits: List<IndexedValue<PdfHit>>,
    currentHit: Int,
) {
    var bitmap by remember(index) { mutableStateOf(doc.cache.get(index)) }
    var ratio by remember(index) { mutableFloatStateOf(doc.ratios[index].takeIf { it > 0f } ?: fallbackRatio) }

    LaunchedEffect(index, widthPx) {
        val cached = bitmap
        if (cached != null && kotlin.math.abs(cached.width - widthPx) <= 2) return@LaunchedEffect
        if (cached != null) delay(180) // durante el pellizco se estira la imagen; se repinta al soltar
        doc.render(index, widthPx)?.let {
            bitmap = it
            ratio = doc.ratios[index]
        }
    }

    Box(
        Modifier
            .width(width)
            .aspectRatio(ratio)
            .background(Color.White)
            .drawWithContent {
                drawContent()
                val pageWidth = doc.widths[index]
                if (hits.isEmpty() || pageWidth <= 0f) return@drawWithContent
                val k = size.width / pageWidth
                for (hit in hits) {
                    val color = if (hit.index == currentHit) Color(0x99FF9800) else Color(0x66FFEB3B)
                    for (r in hit.value.rects) {
                        drawRect(color, Offset(r.left * k, r.top * k), Size(r.width() * k, r.height() * k))
                    }
                }
            },
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Página ${index + 1}",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
