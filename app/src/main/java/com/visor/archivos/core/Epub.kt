package com.visor.archivos.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class EpubException(message: String) : Exception(message)

/** Entrada del índice. [target] es la ruta dentro del ZIP, con su ancla si la tiene. */
data class TocEntry(val title: String, val target: String, val depth: Int) {
    val path: String get() = target.substringBefore('#')
    val fragment: String? get() = target.substringAfter('#', "").ifEmpty { null }
}

/** Lo que dice el archivo OPF del libro. Todas las rutas son rutas completas dentro del ZIP. */
data class EpubPackage(
    val title: String?,
    val author: String?,
    val spine: List<String>,
    val tocPath: String?,
    val tocIsNav: Boolean,
    val coverPath: String?,
)

class EpubMeta(val pkg: EpubPackage, val cover: ByteArray?, val drm: Boolean)

object Epub {
    const val CONTAINER = "META-INF/container.xml"
    const val DRM_MESSAGE = "Este libro tiene DRM y no se puede abrir."
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")
    private val fontExtensions = setOf("ttf", "otf", "woff", "woff2", "eot")

    fun rootfile(containerXml: String): String? =
        MiniXml.tags(MiniXml.clean(containerXml))
            .firstOrNull { it.name == "rootfile" && !it.closing }
            ?.attr("full-path")
            ?.let { resolve("", it) }

    fun parsePackage(opfText: String, opfPath: String): EpubPackage {
        val text = MiniXml.clean(opfText)
        val tags = MiniXml.tags(text)

        fun firstText(name: String): String? {
            val open = tags.indexOfFirst { it.name == name && !it.closing && !it.selfClosing }
            if (open < 0) return null
            val close = (open + 1 until tags.size).firstOrNull { tags[it].name == name && tags[it].closing } ?: return null
            return MiniXml.innerText(text, tags[open].end, tags[close].start).ifBlank { null }
        }

        class Item(val id: String, val href: String, val type: String, val props: List<String>)
        val items = tags.filter { it.name == "item" && !it.closing }.mapNotNull { t ->
            val href = t.attr("href") ?: return@mapNotNull null
            Item(
                id = t.attr("id").orEmpty(),
                href = resolve(opfPath, href).substringBefore('#'),
                type = t.attr("media-type").orEmpty().lowercase(),
                props = t.attr("properties").orEmpty().split(' ').filter { it.isNotEmpty() },
            )
        }
        val byId = items.associateBy { it.id }
        val spineTag = tags.firstOrNull { it.name == "spine" && !it.closing }
        val spine = tags.filter { it.name == "itemref" && !it.closing }
            .mapNotNull { byId[it.attr("idref")]?.href }
            .distinct()
        val nav = items.firstOrNull { "nav" in it.props }
        val ncx = spineTag?.attr("toc")?.let(byId::get) ?: items.firstOrNull { it.type == "application/x-dtbncx+xml" }
        val coverId = tags.firstOrNull { it.name == "meta" && it.attr("name") == "cover" }?.attr("content")
        val cover = items.firstOrNull { "cover-image" in it.props }
            ?: coverId?.let(byId::get)?.takeIf { it.type.startsWith("image/") }
            ?: items.firstOrNull { it.type.startsWith("image/") && ("cover" in it.id.lowercase() || "cover" in it.href.lowercase()) }
        return EpubPackage(firstText("title"), firstText("creator"), spine, (nav ?: ncx)?.href, nav != null, cover?.href)
    }

    fun parseToc(source: String, tocPath: String, isNav: Boolean): List<TocEntry> {
        val text = MiniXml.clean(source)
        val tags = MiniXml.tags(text)
        val out = ArrayList<TocEntry>()

        fun closingIndex(from: Int, name: String) =
            (from + 1 until tags.size).firstOrNull { tags[it].name == name && tags[it].closing }

        if (isNav) {
            val navs = tags.indices.filter { tags[it].name == "nav" && !tags[it].closing }
            val start = navs.firstOrNull { i ->
                tags[i].attr("type")?.split(' ')?.contains("toc") == true || tags[i].attr("role") == "doc-toc"
            } ?: navs.firstOrNull() ?: return out
            var lists = 0
            var navDepth = 0
            var i = start
            while (i < tags.size) {
                val t = tags[i]
                when (t.name) {
                    "nav" -> if (t.closing) {
                        navDepth--
                        if (navDepth == 0) break
                    } else {
                        navDepth++
                    }
                    "ol", "ul" -> if (t.closing) lists-- else lists++
                    "a" -> if (!t.closing) {
                        val close = closingIndex(i, "a")
                        val href = t.attr("href")
                        if (close != null) {
                            val title = MiniXml.innerText(text, t.end, tags[close].start)
                            if (href != null && title.isNotEmpty()) out += TocEntry(title, resolve(tocPath, href), maxOf(0, lists - 1))
                            i = close
                        }
                    }
                }
                i++
            }
        } else {
            var depth = 0
            var label: String? = null
            var i = 0
            while (i < tags.size) {
                val t = tags[i]
                when (t.name) {
                    "navpoint" -> if (t.closing) {
                        depth--
                    } else {
                        depth++
                        label = null
                    }
                    "text" -> if (!t.closing) {
                        val close = closingIndex(i, "text")
                        if (close != null) {
                            label = MiniXml.innerText(text, t.end, tags[close].start)
                            i = close
                        }
                    }
                    "content" -> if (!t.closing && depth > 0) {
                        val src = t.attr("src")
                        val title = label
                        if (src != null && !title.isNullOrEmpty()) out += TocEntry(title, resolve(tocPath, src), depth - 1)
                    }
                    "navmap" -> if (t.closing) break
                }
                i++
            }
        }
        return out
    }

    /** Ruta completa dentro del ZIP de un enlace relativo a [base] (un archivo). Respeta el ancla. */
    fun resolve(base: String, href: String): String {
        if (scheme.containsMatchIn(href)) return href
        val fragment = href.substringAfter('#', "")
        val raw = href.substringBefore('#')
        val path = if (raw.isEmpty()) {
            base
        } else {
            val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
            val dir = base.substringBeforeLast('/', "")
            val joined = when {
                decoded.startsWith("/") -> decoded.drop(1)
                dir.isEmpty() -> decoded
                else -> "$dir/$decoded"
            }
            val parts = ArrayDeque<String>()
            for (p in joined.split('/')) {
                when (p) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(p)
                }
            }
            parts.joinToString("/")
        }
        return if (fragment.isEmpty()) path else "$path#$fragment"
    }

    /** Hay DRM si existe rights.xml o si se cifra algo que no sea una fuente (la ofuscación de fuentes no impide leer). */
    fun isDrm(encryption: String?, rights: Boolean): Boolean {
        if (rights) return true
        encryption ?: return false
        return MiniXml.tags(MiniXml.clean(encryption)).any { t ->
            t.name == "cipherreference" && !t.closing &&
                t.attr("uri")?.substringAfterLast('.')?.lowercase()?.let { it !in fontExtensions } == true
        }
    }
}

/** Libro abierto con acceso aleatorio, para leerlo. */
class EpubFile private constructor(private val zip: ZipFile) : Closeable {
    private val lowerNames = HashMap<String, String>().apply { for (e in zip.entries()) put(e.name.lowercase(), e.name) }
    val pkg: EpubPackage
    val toc: List<TocEntry>
    val drm: Boolean

    init {
        val container = text(Epub.CONTAINER) ?: throw EpubException("El archivo no es un ePub válido.")
        val opfPath = Epub.rootfile(container) ?: throw EpubException("El archivo no es un ePub válido.")
        val opf = text(opfPath) ?: throw EpubException("El archivo no es un ePub válido.")
        pkg = Epub.parsePackage(opf, opfPath)
        if (pkg.spine.isEmpty()) throw EpubException("El libro no tiene capítulos.")
        toc = pkg.tocPath?.let { path ->
            text(path)?.let { runCatching { Epub.parseToc(it, path, pkg.tocIsNav) }.getOrNull() }
        }.orEmpty()
        drm = Epub.isDrm(text("META-INF/encryption.xml"), entry("META-INF/rights.xml") != null)
    }

    /** Busca la entrada tal cual y, si no, sin distinguir mayúsculas (hay libros con rutas mal escritas). */
    fun entry(path: String): ZipEntry? = zip.getEntry(path) ?: lowerNames[path.lowercase()]?.let { zip.getEntry(it) }

    fun open(entry: ZipEntry): InputStream = zip.getInputStream(entry)

    fun bytes(path: String): ByteArray? = entry(path)?.let { e -> open(e).use { it.readBytes() } }

    fun text(path: String): String? = bytes(path)?.let { TextDecoder.decode(it).text }

    /** Tamaño sin comprimir de un capítulo, para calcular el progreso. */
    fun size(path: String): Long = entry(path)?.size?.takeIf { it > 0 } ?: 1L

    fun meta() = EpubMeta(pkg, pkg.coverPath?.let(::bytes), drm)

    override fun close() = zip.close()

    companion object {
        fun open(file: File): EpubFile {
            val zip = try {
                ZipFile(file)
            } catch (e: ZipException) {
                throw EpubException("El archivo no es un ePub válido.")
            }
            return try {
                EpubFile(zip)
            } catch (e: Exception) {
                zip.close()
                throw e
            }
        }
    }
}

/**
 * Lee título, autor y portada recorriendo el ZIP en flujo, sin copiarlo: sirve para analizar
 * carpetas con muchos libros. Algunos ZIP no se dejan leer así; quien llama recurre entonces a [EpubFile].
 */
object EpubScanner {
    private const val MAX_TEXT = 4 shl 20
    private const val MAX_COVER = 15 shl 20

    fun read(open: () -> InputStream): EpubMeta {
        var container: String? = null
        var encryption: String? = null
        var rights = false
        val opfs = HashMap<String, String>()
        open().use { raw ->
            val zip = ZipInputStream(raw.buffered())
            while (true) {
                val name = zip.nextEntry?.name ?: break
                when {
                    name.equals(Epub.CONTAINER, ignoreCase = true) -> container = readText(zip)
                    name.equals("META-INF/encryption.xml", ignoreCase = true) -> encryption = readText(zip)
                    name.equals("META-INF/rights.xml", ignoreCase = true) -> rights = true
                    name.endsWith(".opf", ignoreCase = true) -> opfs[name] = readText(zip)
                }
                val root = container?.let(Epub::rootfile)
                if (root != null && opfs.keys.any { it.equals(root, ignoreCase = true) }) break
            }
        }
        val root = container?.let(Epub::rootfile) ?: throw EpubException("El archivo no es un ePub válido.")
        val opf = opfs.entries.firstOrNull { it.key.equals(root, ignoreCase = true) }?.value
            ?: throw EpubException("El archivo no es un ePub válido.")
        val pkg = Epub.parsePackage(opf, root)
        val cover = pkg.coverPath?.let { path ->
            open().use { raw ->
                val zip = ZipInputStream(raw.buffered())
                var found: ByteArray? = null
                while (true) {
                    val name = zip.nextEntry?.name ?: break
                    if (name.equals(path, ignoreCase = true)) {
                        found = readBytes(zip, MAX_COVER)
                        break
                    }
                }
                found
            }
        }
        return EpubMeta(pkg, cover, Epub.isDrm(encryption, rights))
    }

    private fun readText(input: InputStream) = TextDecoder.decode(readBytes(input, MAX_TEXT)).text

    private fun readBytes(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = input.read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
