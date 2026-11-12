package com.visor.archivos.libros

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.visor.archivos.core.Epub
import com.visor.archivos.core.EpubFile
import com.visor.archivos.core.TocEntry
import com.visor.archivos.ui.VisorTheme
import com.visor.archivos.viewers.CenteredMessage
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lector de ePub: un capítulo cada vez, con desplazamiento vertical. */
class ReaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Library.init(this)
        val id = intent.getStringExtra(EXTRA_BOOK)
        setContent { VisorTheme { ReaderScreen(id, onBack = ::finish) } }
    }

    companion object {
        private const val EXTRA_BOOK = "libro"

        fun start(context: Context, id: String) {
            context.startActivity(Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK, id))
        }
    }
}

private const val HOST = "libro.visor"
private val htmlExtensions = setOf("html", "htm", "xhtml")

// En HTML, <a id="x"/> o <title/> no se cierran solos y se tragan el resto del capítulo
private val selfClosing = Regex(
    "<(?!(?:area|base|br|col|embed|hr|img|input|link|meta|param|source|track|wbr)\\b)([A-Za-z][\\w:.\\-]*)((?:\"[^\"]*\"|'[^']*'|[^'\">])*?)/>",
    RegexOption.IGNORE_CASE,
)
private val headOpen = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
private val bodyClose = Regex("</body\\s*>", RegexOption.IGNORE_CASE)

private fun pathUrl(path: String) = "https://$HOST/" + path.split('/').joinToString("/") { Uri.encode(it) }

enum class ReaderTheme(val label: String) { SYSTEM("Sistema"), LIGHT("Claro"), SEPIA("Sepia"), DARK("Oscuro") }

private class ReaderColors(val background: Int, val css: String)

private fun readerColors(theme: ReaderTheme, systemDark: Boolean): ReaderColors {
    val resolved = if (theme == ReaderTheme.SYSTEM) (if (systemDark) ReaderTheme.DARK else ReaderTheme.LIGHT) else theme
    val (bg, fg, link) = when (resolved) {
        ReaderTheme.SEPIA -> Triple("#F4ECD8", "#3B2F21", "#8A4B12")
        ReaderTheme.DARK -> Triple("#121212", "#DADADA", "#8AB4F8")
        else -> Triple("#FFFFFF", "#1C1B1F", "#1F5FA8")
    }
    val css = "html{-webkit-text-size-adjust:100%}" +
        "body{margin:0 auto !important;padding:1.5em 1.25em 2em !important;max-width:40em;line-height:1.6;overflow-wrap:break-word}" +
        "img,svg,video{max-width:100% !important;height:auto !important}" +
        "pre{white-space:pre-wrap}" +
        "html,body{background:$bg !important;color:$fg !important}" +
        "body *{color:inherit !important;background-color:transparent !important}" +
        "a,a *{color:$link !important}" +
        ".visor-siguiente{margin:3em 0 1em;text-align:center}" +
        ".visor-siguiente a{display:inline-block;padding:.8em 1.4em;border:1px solid $link;border-radius:2em;text-decoration:none;font-family:sans-serif;font-size:.95em}" +
        ".visor-fin{margin:3em 0;text-align:center;font-family:sans-serif;opacity:.6}"
    return ReaderColors(android.graphics.Color.parseColor(bg), css)
}

private class ReaderPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("lector", Context.MODE_PRIVATE)
    var zoom: Int
        get() = prefs.getInt("zoom", 100)
        set(value) = prefs.edit().putInt("zoom", value).apply()
    var theme: ReaderTheme
        get() = runCatching { ReaderTheme.valueOf(prefs.getString("tema", null)!!) }.getOrDefault(ReaderTheme.SYSTEM)
        set(value) = prefs.edit().putString("tema", value.name).apply()
}

@SuppressLint("ViewConstructor")
private class ReaderWebView(context: Context) : WebView(context) {
    var onWidthChange: () -> Unit = {}

    private fun maxScroll() = computeVerticalScrollRange() - computeVerticalScrollExtent()

    fun scrollFraction(): Float {
        val max = maxScroll()
        return if (max <= 0) 0f else (scrollY.toFloat() / max).coerceIn(0f, 1f)
    }

    fun scrollToFraction(f: Float) {
        scrollTo(0, (maxScroll() * f).roundToInt().coerceAtLeast(0))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Al plegar o desplegar cambia el ancho y el texto se recoloca: se vuelve a la misma proporción
        if (oldw != 0 && w != oldw) onWidthChange()
    }
}

/** Estado de la lectura y puente entre el WebView y el libro. */
private class ReaderController(private val context: Context, val epub: EpubFile, startChapter: Int, startFraction: Float) {
    val spine = epub.pkg.spine
    private val spineSet = spine.toHashSet()
    var chapter by mutableIntStateOf(startChapter.coerceIn(0, spine.lastIndex))
        private set
    var fraction by mutableFloatStateOf(startFraction.coerceIn(0f, 1f))
        private set

    @Volatile var css = ""
    lateinit var web: ReaderWebView
    /** Se llama al cambiar de capítulo y al dejar de desplazarse: guarda la posición. */
    var onChapterChange: () -> Unit = {}
    private var pending: Float? = null
    private var restoring = false

    private val sizes = spine.map { epub.size(it).toDouble() }
    private val before = sizes.runningFold(0.0) { a, b -> a + b }
    val progress: Float
        get() = ((before[chapter] + fraction * sizes[chapter]) / before.last()).toFloat().coerceIn(0f, 1f)

    /** Título de cada capítulo según el índice; si no figura, el del anterior que sí figure. */
    val chapterTitles: List<String?> = run {
        var last: String? = null
        spine.map { path -> epub.toc.firstOrNull { it.path.equals(path, true) }?.title?.also { last = it } ?: last }
    }

    fun indexOf(path: String) = spine.indexOfFirst { it.equals(path, ignoreCase = true) }

    fun go(index: Int, fragment: String? = null, at: Float = 0f) {
        if (index !in spine.indices) return
        chapter = index
        fraction = if (fragment == null) at else 0f
        pending = if (fragment == null) at else null
        restoring = fragment == null
        web.loadUrl(pathUrl(spine[index]) + (fragment?.let { "#" + Uri.encode(it) } ?: ""))
        onChapterChange()
    }

    fun reload() = go(chapter, null, fraction)

    /** Tras cambiar el tamaño de letra o el ancho, recupera la misma proporción del capítulo. */
    fun keepPosition() {
        pending = fraction
        restoring = true
        applyPending()
    }

    fun onPageFinished() {
        if (pending == null) restoring = false else applyPending()
    }

    private val saveWhenIdle = Runnable { onChapterChange() }

    fun onScrolled() {
        if (restoring) return
        fraction = web.scrollFraction()
        // Guarda el punto de lectura cuando se deja de desplazar, por si el sistema cierra la app
        web.removeCallbacks(saveWhenIdle)
        web.postDelayed(saveWhenIdle, 1500)
    }

    private fun applyPending() {
        val target = pending ?: return
        web.postDelayed({ web.scrollToFraction(target) }, 60)
        // Segundo intento: las imágenes pueden alargar el capítulo después de cargar
        web.postDelayed({
            if (pending == target) {
                web.scrollToFraction(target)
                pending = null
                restoring = false
            }
        }, 450)
    }

    fun serve(url: Uri): WebResourceResponse {
        if (url.host != HOST) return empty(403, "Forbidden")
        val path = url.path.orEmpty().removePrefix("/")
        val entry = epub.entry(path) ?: return empty(404, "Not Found")
        val ext = path.substringAfterLast('.', "").lowercase()
        return try {
            if (ext in htmlExtensions || path in spineSet) {
                val html = decorate(path, epub.text(path).orEmpty())
                WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
            } else {
                WebResourceResponse(mimeFor(ext), null, epub.open(entry))
            }
        } catch (e: Exception) {
            empty(500, "Error")
        }
    }

    fun navigate(url: Uri): Boolean {
        if (url.host == HOST) {
            val path = url.path.orEmpty().removePrefix("/")
            // Ancla dentro del mismo capítulo: la resuelve el propio WebView
            if (path.equals(spine[chapter], ignoreCase = true)) return false
            val index = indexOf(path)
            if (index >= 0) {
                go(index, url.fragment)
                return true
            }
            return false
        }
        // Enlaces externos: a la app que corresponda (navegador, correo…)
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
        return true
    }

    private fun decorate(path: String, html: String): String {
        val head = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/><style>$css</style>"
        val fixed = selfClosing.replace(html) { m -> "<${m.groupValues[1]}${m.groupValues[2]}></${m.groupValues[1]}>" }
        val headMatch = headOpen.find(fixed)
        val withHead = if (headMatch != null) {
            fixed.substring(0, headMatch.range.last + 1) + head + fixed.substring(headMatch.range.last + 1)
        } else {
            head + fixed
        }
        val index = indexOf(path)
        if (index < 0) return withHead
        val footer = if (index < spine.lastIndex) {
            val next = chapterTitles[index + 1]?.takeIf { it != chapterTitles[index] } ?: "Siguiente capítulo"
            "<div class=\"visor-siguiente\"><a href=\"${TextUtils.htmlEncode(pathUrl(spine[index + 1]))}\">${TextUtils.htmlEncode(next)} →</a></div>"
        } else {
            "<div class=\"visor-fin\">Fin</div>"
        }
        val close = bodyClose.findAll(withHead).lastOrNull()
        return if (close != null) withHead.substring(0, close.range.first) + footer + withHead.substring(close.range.first) else withHead + footer
    }

    private fun mimeFor(ext: String) = when (ext) {
        "css" -> "text/css"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun empty(code: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

@SuppressLint("ClickableViewAccessibility")
private fun createWebView(context: Context, controller: ReaderController, zoom: Int, background: Int, onTap: () -> Unit): ReaderWebView {
    val web = ReaderWebView(context)
    // El contenido del libro no ejecuta código ni lee archivos del dispositivo
    web.settings.javaScriptEnabled = false
    web.settings.allowFileAccess = false
    web.settings.allowContentAccess = false
    web.settings.textZoom = zoom
    web.setBackgroundColor(background)
    web.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
            controller.serve(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            controller.navigate(request.url)

        override fun onPageFinished(view: WebView, url: String?) = controller.onPageFinished()
    }
    web.setOnScrollChangeListener { _, _, _, _, _ -> controller.onScrolled() }
    web.onWidthChange = { controller.keepPosition() }
    val taps = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap()
                return false
            }
        },
    )
    web.setOnTouchListener { _, event ->
        taps.onTouchEvent(event)
        false
    }
    return web
}

private sealed interface Opening {
    data object Loading : Opening
    class Ready(val epub: EpubFile) : Opening
    class Failed(val message: String) : Opening
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(id: String?, onBack: () -> Unit) {
    val book = remember(id) { id?.let(Library::get) }
    val opening by produceState<Opening>(Opening.Loading, book?.id) {
        if (book == null) {
            value = Opening.Failed("El libro ya no está en la estantería.")
            return@produceState
        }
        value = try {
            val file = Library.localFile(book)
            val epub = withContext(Dispatchers.IO) { EpubFile.open(file) }
            if (epub.drm) {
                epub.close()
                Opening.Failed(Epub.DRM_MESSAGE)
            } else {
                Opening.Ready(epub)
            }
        } catch (e: Throwable) {
            Opening.Failed(Library.describe(e))
        }
    }

    when (val o = opening) {
        is Opening.Ready -> {
            DisposableEffect(o.epub) { onDispose { o.epub.close() } }
            Reader(book!!, o.epub, onBack)
        }
        else -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(book?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                )
            },
        ) { padding ->
            if (o is Opening.Failed) {
                CenteredMessage("No se puede abrir el libro", o.message, Modifier.padding(padding))
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Reader(book: Book, epub: EpubFile, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val prefs = remember { ReaderPrefs(context) }
    var zoom by remember { mutableIntStateOf(prefs.zoom) }
    var theme by remember { mutableStateOf(prefs.theme) }
    val systemDark = isSystemInDarkTheme()
    val colors = remember(theme, systemDark) { readerColors(theme, systemDark) }
    var barsVisible by rememberSaveable { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // Hueco para que las barras no tapen el principio ni el final del capítulo (px del WebView = dp)
    val density = LocalDensity.current
    val statusInset = WindowInsets.statusBars.getTop(density)
    val navInset = WindowInsets.navigationBars.getBottom(density)
    val barsSpace = remember {
        val top = with(density) { statusInset.toDp().value }
        val bottom = with(density) { navInset.toDp().value }
        val topSpace = (top + 72).roundToInt()
        // scroll-padding: al saltar a un ancla, el destino queda bajo la barra y no detrás
        "body{padding-top:${topSpace}px !important;padding-bottom:${(bottom + 88).roundToInt()}px !important}" +
            "html{scroll-padding-top:${topSpace}px}"
    }
    val controller = remember(epub) { ReaderController(context, epub, book.chapter, book.fraction) }
    fun save() = Library.savePosition(book.id, controller.chapter, controller.fraction, controller.progress)
    val web = remember(controller) {
        controller.css = colors.css + barsSpace
        createWebView(context, controller, zoom, colors.background) { barsVisible = !barsVisible }.also {
            controller.web = it
            controller.onChapterChange = { save() }
        }
    }
    LaunchedEffect(controller) { controller.go(controller.chapter, null, controller.fraction) }
    DisposableEffect(web) { onDispose { web.destroy() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { save() }

    LaunchedEffect(barsVisible) {
        val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (barsVisible) insets.show(WindowInsetsCompat.Type.systemBars()) else insets.hide(WindowInsetsCompat.Type.systemBars())
    }

    fun applyTheme(t: ReaderTheme) {
        theme = t
        prefs.theme = t
        val c = readerColors(t, systemDark)
        controller.css = c.css + barsSpace
        web.setBackgroundColor(c.background)
        controller.reload()
    }

    fun applyZoom(z: Int) {
        zoom = z
        prefs.zoom = z
        web.settings.textZoom = z
        controller.keepPosition()
    }

    Box(Modifier.fillMaxSize().background(Color(colors.background))) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout))

        AnimatedVisibility(barsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                title = {
                    Column {
                        Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        controller.chapterTitles[controller.chapter]?.let {
                            Text(
                                it,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showToc = true }) { Icon(Icons.Filled.Menu, "Índice") }
                    TextButton(onClick = { showSettings = true }) { Text("Aa", style = MaterialTheme.typography.titleMedium) }
                },
            )
        }

        AnimatedVisibility(barsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    IconButton(onClick = { controller.go(controller.chapter - 1) }, enabled = controller.chapter > 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Capítulo anterior")
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Capítulo ${controller.chapter + 1} de ${controller.spine.size} · ${(controller.progress * 100).roundToInt()} %",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        LinearProgressIndicator(progress = { controller.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    IconButton(onClick = { controller.go(controller.chapter + 1) }, enabled = controller.chapter < controller.spine.lastIndex) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Capítulo siguiente")
                    }
                }
            }
        }
    }

    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            TocList(controller) { entry ->
                showToc = false
                val index = controller.indexOf(entry.path)
                if (index >= 0) controller.go(index, entry.fragment)
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("Tamaño de letra", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    OutlinedButton(onClick = { applyZoom(zoom - 10) }, enabled = zoom > 60) { Text("A−") }
                    Text("$zoom %", textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { applyZoom(zoom + 10) }, enabled = zoom < 250) { Text("A+") }
                }
                Text("Tema", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    ReaderTheme.entries.forEach { t ->
                        FilterChip(selected = theme == t, onClick = { applyTheme(t) }, label = { Text(t.label) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TocList(controller: ReaderController, onSelect: (TocEntry) -> Unit) {
    val entries = remember(controller) {
        controller.epub.toc.ifEmpty { controller.spine.mapIndexed { i, path -> TocEntry("Capítulo ${i + 1}", path, 0) } }
    }
    val current = remember(entries, controller.chapter) {
        val chapters = entries.map { controller.indexOf(it.path) }
        chapters.indexOfFirst { it == controller.chapter }.takeIf { it >= 0 }
            ?: chapters.indexOfLast { it in 0 until controller.chapter }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, current - 2))
    Text("Índice", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    LazyColumn(state = listState, modifier = Modifier.navigationBarsPadding()) {
        itemsIndexed(entries) { i, entry ->
            Text(
                entry.title,
                style = if (entry.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (i == current) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(entry) }
                    .padding(start = (24 + 16 * entry.depth).dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
            )
        }
    }
}
