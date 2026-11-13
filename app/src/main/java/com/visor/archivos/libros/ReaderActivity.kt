package com.visor.archivos.libros

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import android.webkit.RenderProcessGoneDetail
import android.view.MotionEvent
import android.view.View
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Lector de ePub: por páginas (una o dos por pantalla) o en vertical continuo, un capítulo cada vez. */
class ReaderActivity : ComponentActivity() {
    /** Recibe (tecla, pulsada) y dice si la tecla es suya; lo instala el lector para pasar página. */
    var onKey: ((Int, Boolean) -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (onKey?.invoke(event.keyCode, event.action == KeyEvent.ACTION_DOWN) == true) return true
        return super.dispatchKeyEvent(event)
    }

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

/** Ruta reservada para los recursos de la app (tipos de letra), fuera del contenido del libro. */
private const val OWN_PREFIX = "__visor/"
private const val TWO_PAGES_MIN_DP = 600
private val htmlExtensions = setOf("html", "htm", "xhtml")

/** Únicos enlaces externos que un libro puede ofrecer; nada de llamadas ni enlaces a otras apps. */
private val externalSchemes = setOf("http", "https", "mailto")

// En HTML, <a id="x"/> o <title/> no se cierran solos y se tragan el resto del capítulo
private val selfClosing = Regex(
    "<(?!(?:area|base|br|col|embed|hr|img|input|link|meta|param|source|track|wbr)\\b)([A-Za-z][\\w:.\\-]*)((?:\"[^\"]*\"|'[^']*'|[^'\">])*?)/>",
    RegexOption.IGNORE_CASE,
)
private val headOpen = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
private val bodyClose = Regex("</body\\s*>", RegexOption.IGNORE_CASE)

/**
 * Paginación. El capítulo se maqueta en columnas del ancho de la pantalla y se pasa de página
 * desplazando el cuerpo. Solo lo ejecuta la app: el código del propio libro está bloqueado.
 */
private const val PAGER_SCRIPT = """
(function(){
  if (window.__v) return;
  var b = document.body;
  function measure(){
    b.style.transition = 'none';
    b.style.transform = 'none';
    document.documentElement.scrollLeft = 0; b.scrollLeft = 0; window.scrollTo(0, 0);
    // El paso entre pantallas es el ancho que la app dio al cuerpo, no el de la ventana
    var V = parseFloat(getComputedStyle(b).width) || window.innerWidth;
    var end = document.getElementById('visor-fin');
    var left = end ? end.getBoundingClientRect().left : 0;
    return { V: V, pages: Math.max(1, Math.floor((left + 1) / V) + 1) };
  }
  function show(v, k, animate){
    b.style.transition = animate ? 'transform .18s ease-out' : 'none';
    b.style.transform = 'translateX(' + (-k * v.V) + 'px)';
  }
  window.__v = {
    page: 0, pages: 1, V: 1,
    layout: function(fraction, anchor){
      var m = measure();
      var k = Math.round(fraction * (m.pages - 1));
      if (anchor) {
        var e = document.getElementById(anchor) || document.getElementsByName(anchor)[0];
        if (e) k = Math.floor((e.getBoundingClientRect().left + 1) / m.V);
      }
      k = Math.max(0, Math.min(m.pages - 1, k));
      this.page = k; this.pages = m.pages; this.V = m.V;
      show(this, k, false);
      return this.pages + ',' + this.page;
    },
    turn: function(delta){
      var m = measure();
      this.pages = m.pages; this.V = m.V;
      this.page = Math.min(this.page, m.pages - 1);
      show(this, this.page, false);
      var k = this.page + delta;
      if (k < 0) return 'prev';
      if (k >= this.pages) return 'next';
      b.getBoundingClientRect();
      this.page = k;
      show(this, k, true);
      return this.pages + ',' + this.page;
    }
  };
})();
"""

private fun pathUrl(path: String) = "https://$HOST/" + path.split('/').joinToString("/") { Uri.encode(it) }

enum class ReaderTheme(val label: String) { SYSTEM("Sistema"), LIGHT("Claro"), SEPIA("Sepia"), DARK("Oscuro") }

enum class ReaderFont(val label: String, val css: String?) {
    SANS("Sans", "sans-serif"),
    SERIF("Serif", "serif"),
    ATKINSON("Atkinson", "'Atkinson Hyperlegible',sans-serif"),
    BOOK("La del libro", null),
}

enum class PageColumns(val label: String) { AUTO("Automático"), ONE("Una"), TWO("Dos") }

private class ReaderStyle(val background: Int, val foreground: Int, val css: String)

/** [top] y [bottom]: alto de las barras del sistema, en las mismas unidades que usa la página. */
private fun readerStyle(
    theme: ReaderTheme,
    systemDark: Boolean,
    font: ReaderFont,
    paged: Boolean,
    columns: Int,
    top: Int,
    bottom: Int,
    pageWidth: Float,
    pageHeight: Float,
): ReaderStyle {
    val resolved = if (theme == ReaderTheme.SYSTEM) (if (systemDark) ReaderTheme.DARK else ReaderTheme.LIGHT) else theme
    val (bg, fg, link) = when (resolved) {
        ReaderTheme.SEPIA -> Triple("#F4ECD8", "#3B2F21", "#8A4B12")
        ReaderTheme.DARK -> Triple("#121212", "#DADADA", "#8AB4F8")
        else -> Triple("#FFFFFF", "#1C1B1F", "#1F5FA8")
    }
    val colors = "html,body{background:$bg !important;color:$fg !important}" +
        "body *{color:inherit !important;background-color:transparent !important}" +
        "a,a *{color:$link !important}"

    val fontCss = if (font.css == null) {
        ""
    } else {
        val faces = if (font != ReaderFont.ATKINSON) {
            ""
        } else {
            listOf("Regular" to "normal;font-weight:400", "Bold" to "normal;font-weight:700", "Italic" to "italic;font-weight:400", "BoldItalic" to "italic;font-weight:700")
                .joinToString("") { (file, style) ->
                    "@font-face{font-family:'Atkinson Hyperlegible';font-style:$style;src:url('https://$HOST/${OWN_PREFIX}AtkinsonHyperlegible-$file.ttf')}"
                }
        }
        // El código y los ejemplos conservan su letra de ancho fijo
        faces + "body,body *:not(pre):not(code):not(kbd):not(samp):not(tt){font-family:${font.css} !important}"
    }

    val layout = if (paged) {
        val side = if (columns == 2) 30 else 22
        val padTop = top + 20
        val padBottom = bottom + 40
        // Medidas en píxeles dadas por la app: las unidades vw/vh del WebView no son fiables aquí
        fun px(v: Float) = String.format(java.util.Locale.US, "%.2fpx", v)
        val columnWidth = (pageWidth - 2 * side * columns) / columns
        "html{height:100% !important;margin:0 !important;padding:0 !important;overflow:hidden !important}" +
            "body{box-sizing:border-box !important;margin:0 !important;width:${px(pageWidth)} !important;max-width:none !important;" +
            "min-width:0 !important;height:${px(pageHeight)} !important;padding:${padTop}px ${side}px ${padBottom}px !important;" +
            "column-width:${px(columnWidth)} !important;column-count:auto !important;column-gap:${2 * side}px !important;" +
            "column-fill:auto !important;overflow:visible !important;line-height:1.6;overflow-wrap:break-word}" +
            "img,svg,video{max-width:100% !important;max-height:${px(pageHeight - padTop - padBottom - 24)} !important;" +
            "height:auto !important;object-fit:contain;break-inside:avoid}" +
            "h1,h2,h3,h4,h5,h6{break-after:avoid}table{max-width:100%}"
    } else {
        // Hueco para que las barras no tapen el principio ni el final; scroll-padding deja las anclas a la vista
        val padTop = top + 72
        "html{scroll-padding-top:${padTop}px}" +
            "body{margin:0 auto !important;padding:${padTop}px 1.25em ${bottom + 88}px !important;max-width:40em;line-height:1.6;overflow-wrap:break-word}" +
            "img,svg,video{max-width:100% !important;height:auto !important}" +
            ".visor-siguiente{margin:3em 0 1em;text-align:center}" +
            ".visor-siguiente a{display:inline-block;padding:.8em 1.4em;border:1px solid $link;border-radius:2em;text-decoration:none;font-family:sans-serif;font-size:.95em}" +
            ".visor-fin{margin:3em 0;text-align:center;font-family:sans-serif;opacity:.6}"
    }
    val css = "html{-webkit-text-size-adjust:100%}pre{white-space:pre-wrap}" + colors + fontCss + layout
    return ReaderStyle(android.graphics.Color.parseColor(bg), android.graphics.Color.parseColor(fg), css)
}

private class ReaderPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("lector", Context.MODE_PRIVATE)

    /** 125 %: tamaño cómodo con presbicia leve sin llegar a cortar demasiado las líneas. */
    var zoom: Int
        get() = prefs.getInt("zoom", 125)
        set(value) = prefs.edit().putInt("zoom", value).apply()
    var theme: ReaderTheme
        get() = enumPref("tema", ReaderTheme.SYSTEM)
        set(value) = prefs.edit().putString("tema", value.name).apply()
    var font: ReaderFont
        get() = enumPref("letra", ReaderFont.SANS)
        set(value) = prefs.edit().putString("letra", value.name).apply()
    var columns: PageColumns
        get() = enumPref("paginas", PageColumns.AUTO)
        set(value) = prefs.edit().putString("paginas", value.name).apply()
    var paged: Boolean
        get() = prefs.getBoolean("paginado", true)
        set(value) = prefs.edit().putBoolean("paginado", value).apply()
    var volumeKeys: Boolean
        get() = prefs.getBoolean("teclasVolumen", true)
        set(value) = prefs.edit().putBoolean("teclasVolumen", value).apply()
    var hintSeen: Boolean
        get() = prefs.getBoolean("ayudaVista", false)
        set(value) = prefs.edit().putBoolean("ayudaVista", value).apply()

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default
}

@SuppressLint("ViewConstructor")
private class ReaderWebView(context: Context) : WebView(context) {
    var onResize: () -> Unit = {}

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
        // Al plegar, desplegar o girar cambia el tamaño y el texto se recoloca
        if (oldw != 0 && oldh != 0 && (w != oldw || h != oldh)) onResize()
    }
}

/** Estado de la lectura y puente entre el WebView y el libro. */
private class ReaderController(private val context: Context, val epub: EpubFile, startChapter: Int, startFraction: Float) {
    val spine = epub.pkg.spine
    private val spineSet = spine.toHashSet()
    var chapter by mutableIntStateOf(startChapter.coerceIn(0, spine.lastIndex))
        private set

    /** Proporción leída del capítulo (0–1), común a los dos modos de lectura. */
    var fraction by mutableFloatStateOf(startFraction.coerceIn(0f, 1f))
        private set
    var page by mutableIntStateOf(0)
        private set
    var pageCount by mutableIntStateOf(1)
        private set

    var paged = true
    @Volatile var css = ""
    lateinit var web: ReaderWebView

    /** Se llama al cambiar de capítulo o de página y al dejar de desplazarse: guarda la posición. */
    var onPositionChange: () -> Unit = {}

    /** Al pasar página se recogen las barras, que si no tapan el principio y el final del texto. */
    var onTurn: () -> Unit = {}

    /** Un enlace hacia fuera del libro que el lector ha tocado: se pregunta antes de abrirlo. */
    var onExternalLink: (Uri) -> Unit = {}
    var onRendererGone: () -> Unit = {}
    private var pending: Float? = null
    private var pendingAnchor: String? = null
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

    private val reveal = Runnable { web.alpha = 1f }
    private val saveWhenIdle = Runnable { onPositionChange() }

    fun indexOf(path: String) = spine.indexOfFirst { it.equals(path, ignoreCase = true) }

    fun go(index: Int, fragment: String? = null, at: Float = 0f) {
        if (index !in spine.indices) return
        chapter = index
        fraction = if (fragment == null) at else 0f
        if (paged) {
            pending = fraction
            pendingAnchor = fragment
            page = 0
            pageCount = 1
            // Oculto hasta colocarse en su página, para no enseñar antes la primera
            web.alpha = 0f
            web.removeCallbacks(reveal)
            web.postDelayed(reveal, 1500)
            web.loadUrl(pathUrl(spine[index]))
        } else {
            web.alpha = 1f
            pending = if (fragment == null) at else null
            restoring = fragment == null
            web.loadUrl(pathUrl(spine[index]) + (fragment?.let { "#" + Uri.encode(it) } ?: ""))
        }
        onPositionChange()
    }

    fun reload() = go(chapter, null, fraction)

    /** Tras cambiar el tamaño de letra o el ancho, recupera la misma proporción del capítulo. */
    fun keepPosition() {
        if (paged) {
            web.postDelayed({ layout() }, 120)
        } else {
            pending = fraction
            restoring = true
            applyPendingScroll()
        }
    }

    fun onPageFinished() {
        when {
            paged -> layout()
            pending == null -> restoring = false
            else -> applyPendingScroll()
        }
    }

    fun onScrolled() {
        if (paged || restoring) return
        fraction = web.scrollFraction()
        // Guarda el punto de lectura cuando se deja de desplazar, por si el sistema cierra la app
        web.removeCallbacks(saveWhenIdle)
        web.postDelayed(saveWhenIdle, 1500)
    }

    /** Pasa página; al salirse del capítulo continúa en el siguiente o en el final del anterior. */
    fun turn(delta: Int, byGesture: Boolean = true) {
        if (!paged) return
        if (byGesture) onTurn()
        web.evaluateJavascript("window.__v ? __v.turn($delta) : ''") { raw ->
            when (val result = raw?.trim('"')) {
                "prev" -> if (chapter > 0) go(chapter - 1, at = 1f)
                "next" -> if (chapter < spine.lastIndex) go(chapter + 1)
                else -> if (applyPages(result)) {
                    web.removeCallbacks(saveWhenIdle)
                    web.postDelayed(saveWhenIdle, 800)
                }
            }
        }
    }

    /** Capítulo en el que cae una proporción del libro entero. */
    fun chapterAt(progress: Float): Int {
        val target = progress.coerceIn(0f, 1f) * before.last()
        return spine.indices.firstOrNull { target < before[it + 1] } ?: spine.lastIndex
    }

    /** Salta a una proporción del libro entero (barra de progreso). */
    fun seek(progress: Float) {
        val index = chapterAt(progress)
        val target = progress.coerceIn(0f, 1f) * before.last()
        go(index, at = ((target - before[index]) / sizes[index]).toFloat().coerceIn(0f, 1f))
    }

    private fun layout() {
        val target = pending ?: fraction
        val anchor = pendingAnchor?.let { JSONObject.quote(it) } ?: "null"
        pending = null
        pendingAnchor = null
        web.evaluateJavascript("$PAGER_SCRIPT __v.layout($target, $anchor)") { raw ->
            applyPages(raw?.trim('"'))
            web.removeCallbacks(reveal)
            web.alpha = 1f
        }
    }

    private fun applyPages(result: String?): Boolean {
        val parts = result?.split(',')?.mapNotNull { it.toIntOrNull() } ?: return false
        if (parts.size != 2) return false
        pageCount = parts[0].coerceAtLeast(1)
        page = parts[1].coerceIn(0, pageCount - 1)
        fraction = if (pageCount > 1) page.toFloat() / (pageCount - 1) else 0f
        return true
    }

    private fun applyPendingScroll() {
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
        if (path.startsWith(OWN_PREFIX)) {
            val name = path.removePrefix(OWN_PREFIX)
            if ('/' in name || !name.endsWith(".ttf")) return empty(404, "Not Found")
            return runCatching { WebResourceResponse("font/ttf", null, context.assets.open("fuentes/$name")) }
                .getOrElse { empty(404, "Not Found") }
        }
        val entry = epub.entry(path) ?: return empty(404, "Not Found")
        val ext = path.substringAfterLast('.', "").lowercase()
        return try {
            if (ext in htmlExtensions || path in spineSet) {
                // Un capítulo desmesurado (bomba de compresión) no se carga: agotaría la memoria
                val source = epub.text(path)
                    ?: "<html><head></head><body><p>Este capítulo es demasiado grande para mostrarlo.</p></body></html>"
                val html = decorate(path, source)
                // La app necesita JavaScript para paginar, pero el del libro no se ejecuta nunca
                val headers = mapOf("Content-Security-Policy" to "script-src 'none'; object-src 'none'; frame-src 'none'")
                WebResourceResponse("text/html", "utf-8", 200, "OK", headers, ByteArrayInputStream(html.toByteArray()))
            } else {
                WebResourceResponse(mimeFor(ext), null, epub.open(entry))
            }
        } catch (e: Throwable) {
            empty(500, "Error")
        }
    }

    fun navigate(url: Uri, byUser: Boolean): Boolean {
        // Solo se navega cuando el lector toca un enlace: un libro no puede saltar ni abrir nada por su cuenta
        if (!byUser) return true
        if (url.host == HOST) {
            val path = url.path.orEmpty().removePrefix("/")
            if (path.equals(spine[chapter], ignoreCase = true)) {
                // Ancla dentro del mismo capítulo: en vertical la resuelve el WebView; por páginas, se salta a la suya
                if (!paged) return false
                pendingAnchor = url.fragment
                layout()
                return true
            }
            val index = indexOf(path)
            if (index >= 0) go(index, url.fragment)
            return true
        }
        if (url.scheme in externalSchemes) onExternalLink(url)
        return true
    }

    private fun decorate(path: String, html: String): String {
        val head = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no\"/><style>$css</style>"
        val fixed = selfClosing.replace(html) { m -> "<${m.groupValues[1]}${m.groupValues[2]}></${m.groupValues[1]}>" }
        val headMatch = headOpen.find(fixed)
        val withHead = if (headMatch != null) {
            fixed.substring(0, headMatch.range.last + 1) + head + fixed.substring(headMatch.range.last + 1)
        } else {
            head + fixed
        }
        val index = indexOf(path)
        if (index < 0) return withHead
        val footer = when {
            // Marca del final del texto: con ella se cuentan las páginas
            paged -> "<span id=\"visor-fin\"></span>"
            index < spine.lastIndex -> {
                val next = chapterTitles[index + 1]?.takeIf { it != chapterTitles[index] } ?: "Siguiente capítulo"
                "<div class=\"visor-siguiente\"><a href=\"${TextUtils.htmlEncode(pathUrl(spine[index + 1]))}\">${TextUtils.htmlEncode(next)} →</a></div>"
            }
            else -> "<div class=\"visor-fin\">Fin</div>"
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

@SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
private fun createWebView(context: Context, controller: ReaderController, textZoom: Int, background: Int, onTap: () -> Unit): ReaderWebView {
    val web = ReaderWebView(context)
    // JavaScript solo para la paginación propia: cada capítulo se sirve con una política que bloquea
    // el código del libro, y toda petición que no sea al propio libro se rechaza.
    web.settings.javaScriptEnabled = true
    web.settings.allowFileAccess = false
    web.settings.allowContentAccess = false
    web.settings.textZoom = textZoom
    // Sin esto, el WebView aleja la página al ver que el capítulo paginado es más ancho que la pantalla
    web.settings.useWideViewPort = false
    web.settings.loadWithOverviewMode = false
    web.overScrollMode = View.OVER_SCROLL_NEVER
    web.setBackgroundColor(background)
    web.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
            controller.serve(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            controller.navigate(request.url, request.hasGesture())

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Sin esto, un libro que tumbe el motor de dibujo cerraría la app entera
            controller.onRendererGone()
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) = controller.onPageFinished()
    }
    web.setOnScrollChangeListener { _, _, _, _, _ -> controller.onScrolled() }

    val swipeMin = 48 * context.resources.displayMetrics.density
    val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Un toque sobre un enlace es del enlace, no un paso de página
                val hit = web.hitTestResult.type
                if (hit == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return false
                val x = if (web.width > 0) e.x / web.width else 0.5f
                when {
                    !controller.paged -> onTap()
                    x < 0.3f -> controller.turn(-1)
                    x > 0.7f -> controller.turn(1)
                    else -> onTap()
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (!controller.paged || e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < swipeMin || abs(dx) < abs(dy) * 1.5f) return false
                controller.turn(if (dx < 0) 1 else -1)
                return true
            }
        },
    )
    web.setOnTouchListener { _, event ->
        gestures.onTouchEvent(event)
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
    val density = LocalDensity.current
    val prefs = remember { ReaderPrefs(context) }
    var zoom by remember { mutableIntStateOf(prefs.zoom) }
    var theme by remember { mutableStateOf(prefs.theme) }
    var font by remember { mutableStateOf(prefs.font) }
    var paged by remember { mutableStateOf(prefs.paged) }
    var columnsPref by remember { mutableStateOf(prefs.columns) }
    val systemDark = isSystemInDarkTheme()
    var barsVisible by rememberSaveable { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var externalLink by remember { mutableStateOf<Uri?>(null) }
    var volumeKeys by remember { mutableStateOf(prefs.volumeKeys) }
    var showHint by remember { mutableStateOf(!prefs.hintSeen) }
    var seeking by remember { mutableStateOf<Float?>(null) }

    // Alto de las barras del sistema, medido una vez (luego se ocultan y valdría cero)
    val statusInset = WindowInsets.statusBars.getTop(density)
    val navInset = WindowInsets.navigationBars.getBottom(density)
    val insets = remember { with(density) { statusInset.toDp().value.roundToInt() to navInset.toDp().value.roundToInt() } }
    var widthDp by remember { mutableIntStateOf(0) }
    val screenWidthDp = LocalConfiguration.current.screenWidthDp

    fun columnsFor(width: Int) = when (columnsPref) {
        PageColumns.ONE -> 1
        PageColumns.TWO -> 2
        PageColumns.AUTO -> if (width >= TWO_PAGES_MIN_DP) 2 else 1
    }
    var columns by remember { mutableIntStateOf(columnsFor(screenWidthDp)) }
    var pageSize by remember { mutableStateOf(0f to 0f) }
    fun style() = readerStyle(theme, systemDark, font, paged, columns, insets.first, insets.second, pageSize.first, pageSize.second)
    var colors by remember { mutableStateOf(style()) }

    // El tamaño elegido se suma al tamaño de letra configurado en Android
    val fontScale = LocalConfiguration.current.fontScale
    fun textZoom(z: Int) = (z * fontScale).roundToInt()

    val controller = remember(epub) { ReaderController(context, epub, book.chapter, book.fraction) }
    fun save() = Library.savePosition(book.id, controller.chapter, controller.fraction, controller.progress)
    val web = remember(controller) {
        controller.css = colors.css
        controller.paged = paged
        createWebView(context, controller, textZoom(zoom), colors.background) { barsVisible = !barsVisible }.also {
            controller.web = it
            controller.onPositionChange = { save() }
            // Con TalkBack las barras se quedan: en ellas están los botones de pasar página
            val accessibility = context.getSystemService(AccessibilityManager::class.java)
            controller.onTurn = { if (accessibility?.isTouchExplorationEnabled != true) barsVisible = false }
            controller.onExternalLink = { externalLink = it }
            controller.onRendererGone = {
                Toast.makeText(context, "Este libro no se puede mostrar.", Toast.LENGTH_LONG).show()
                onBack()
            }
        }
    }

    /** Aplica los ajustes actuales. Recarga el capítulo, que es lo que lleva los estilos. */
    fun restyle() {
        pageSize = web.width / density.density to web.height / density.density
        val s = style()
        colors = s
        controller.css = s.css
        controller.paged = paged
        web.setBackgroundColor(s.background)
        controller.reload()
    }

    web.onResize = {
        widthDp = (web.width / density.density).roundToInt()
        if (paged) {
            // Las páginas dependen del tamaño exacto de la pantalla: se vuelven a maquetar
            columns = columnsFor(widthDp)
            restyle()
        } else {
            controller.keepPosition()
        }
    }

    // La primera carga espera a que el lector tenga tamaño, que es lo que mide las páginas
    LaunchedEffect(controller) {
        web.doOnLayout {
            widthDp = (web.width / density.density).roundToInt()
            columns = columnsFor(widthDp)
            restyle()
        }
    }
    DisposableEffect(web) { onDispose { web.destroy() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { save() }

    // Teclado y botones de volumen: otra forma de pasar página, también con lector de pantalla
    DisposableEffect(controller) {
        val reader = activity as? ReaderActivity
        reader?.onKey = { code, down ->
            val delta = when (code) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeys) 1 else 0
                KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeys) -1 else 0
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> 1
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> -1
                else -> 0
            }
            if (delta != 0 && controller.paged) {
                if (down) controller.turn(delta)
                true
            } else {
                false
            }
        }
        onDispose { reader?.onKey = null }
    }

    LaunchedEffect(barsVisible) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (barsVisible) bars.show(WindowInsetsCompat.Type.systemBars()) else bars.hide(WindowInsetsCompat.Type.systemBars())
    }

    val percent = (controller.progress * 100).roundToInt()

    Box(Modifier.fillMaxSize().background(Color(colors.background))) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout))

        // Con las barras ocultas queda un indicador discreto de por dónde se va
        if (paged && !barsVisible) {
            Text(
                "${controller.page + 1} / ${controller.pageCount} · $percent %",
                style = MaterialTheme.typography.labelMedium,
                color = Color(colors.foreground).copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (insets.second + 12).dp),
            )
        }

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
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    if (paged) {
                        IconButton(onClick = { controller.turn(-1, byGesture = false) }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Página anterior")
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = if (paged) 4.dp else 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val target = seeking
                        val chapterLabel = "Capítulo ${controller.chapter + 1} de ${controller.spine.size}"
                        Text(
                            when {
                                target != null -> "Ir al ${(target * 100).roundToInt()} % · Capítulo ${controller.chapterAt(target) + 1}"
                                paged -> "Página ${controller.page + 1} de ${controller.pageCount} · $chapterLabel · $percent %"
                                else -> "$chapterLabel · $percent %"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Arrastrando se salta a cualquier punto del libro
                        Slider(
                            value = target ?: controller.progress,
                            onValueChange = { seeking = it },
                            onValueChangeFinished = {
                                seeking?.let(controller::seek)
                                seeking = null
                            },
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Posición en el libro" },
                        )
                    }
                    if (paged) {
                        IconButton(onClick = { controller.turn(1, byGesture = false) }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Página siguiente")
                        }
                    }
                }
            }
        }
    }

    // Primera vez: dónde tocar para pasar página
    if (paged && showHint) {
        fun dismiss() {
            showHint = false
            prefs.hintSeen = true
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).clickable(onClickLabel = "Entendido") { dismiss() }) {
            Row(Modifier.fillMaxSize()) {
                @Composable
                fun zone(weight: Float, text: String) = Box(Modifier.weight(weight).fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                    Text(text, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                }
                zone(0.3f, "‹\nPágina anterior")
                zone(0.4f, "Toca el centro para ver el menú\n\nTambién puedes deslizar o usar los botones de volumen")
                zone(0.3f, "›\nPágina siguiente")
            }
            Button(onClick = { dismiss() }, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 48.dp)) {
                Text("Entendido")
            }
        }
    }

    externalLink?.let { link ->
        AlertDialog(
            onDismissRequest = { externalLink = null },
            title = { Text("¿Abrir este enlace?") },
            text = { Text("El libro enlaza a una dirección de fuera:\n\n$link") },
            confirmButton = {
                TextButton(onClick = {
                    externalLink = null
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, link)) }
                }) { Text("Abrir") }
            },
            dismissButton = { TextButton(onClick = { externalLink = null }) { Text("Cancelar") } },
        )
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
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text("Tamaño de letra", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    fun setZoom(z: Int) {
                        zoom = z
                        prefs.zoom = z
                        web.settings.textZoom = textZoom(z)
                        controller.keepPosition()
                    }
                    OutlinedButton(onClick = { setZoom(zoom - 5) }, enabled = zoom > 60) { Text("A−") }
                    Text("$zoom %", textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { setZoom(zoom + 5) }, enabled = zoom < 300) { Text("A+") }
                }

                @Composable
                fun <T> choice(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
                    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        options.forEach { option ->
                            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
                        }
                    }
                }

                choice("Tipo de letra", ReaderFont.entries, font, { it.label }) {
                    font = it
                    prefs.font = it
                    restyle()
                }
                choice("Tema", ReaderTheme.entries, theme, { it.label }) {
                    theme = it
                    prefs.theme = it
                    restyle()
                }
                choice("Paso de página", listOf(true, false), paged, { if (it) "Horizontal" else "Vertical continuo" }) {
                    paged = it
                    prefs.paged = it
                    restyle()
                }
                if (paged) {
                    choice("Páginas por pantalla", PageColumns.entries, columnsPref, { it.label }) {
                        columnsPref = it
                        prefs.columns = it
                        columns = columnsFor(if (widthDp > 0) widthDp else screenWidthDp)
                        restyle()
                    }
                    choice("Pasar página con los botones de volumen", listOf(true, false), volumeKeys, { if (it) "Sí" else "No" }) {
                        volumeKeys = it
                        prefs.volumeKeys = it
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
