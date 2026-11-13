package com.visor.archivos.libros

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.visor.archivos.core.Epub
import com.visor.archivos.core.EpubException
import com.visor.archivos.core.EpubFile
import com.visor.archivos.core.EpubMeta
import com.visor.archivos.core.EpubScanner
import com.visor.archivos.core.Streams
import com.visor.archivos.core.TooLargeException
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Libro de la estantería.
 * - [uri]: documento original (con permiso duradero) o, si [copy], la copia guardada dentro de la app.
 * - [folder]: carpeta analizada de la que procede, si es el caso.
 * - [chapter]/[fraction]: por dónde se iba; [progress]: proporción leída del libro entero.
 */
data class Book(
    val id: String,
    val uri: String,
    val title: String,
    val author: String?,
    val cover: String?,
    val size: Long,
    val folder: String?,
    val copy: Boolean,
    val chapter: Int = 0,
    val fraction: Float = 0f,
    val progress: Float = 0f,
    val addedAt: Long,
    val openedAt: Long = 0,
)

/** Carpeta analizada. [excluded]: libros que se quitaron a mano y no deben volver al reanalizar. */
data class Folder(val uri: String, val name: String, val excluded: Set<String> = emptySet())

data class ScanState(val folder: String, val done: Int, val total: Int)

class AddResult(val book: Book, val isNew: Boolean)

object Library {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val addLock = Mutex()
    private val scanLock = Mutex()
    private val saveLock = Any()

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()
    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()
    private val _scan = MutableStateFlow<ScanState?>(null)
    val scan: StateFlow<ScanState?> = _scan.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val dataFile get() = File(app.filesDir, "biblioteca.json")
    private val copiesDir get() = File(app.filesDir, "libros").apply { mkdirs() }
    private val coversDir get() = File(app.filesDir, "portadas").apply { mkdirs() }
    private val readingDir get() = File(app.cacheDir, "lectura").apply { mkdirs() }

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        runCatching { load() }.onSuccess { scope.launch { runCatching { deleteOrphans() } } }
    }

    fun get(id: String): Book? = _books.value.firstOrNull { it.id == id }

    /** Mensaje para el usuario a partir de un error al añadir o abrir un libro. */
    fun describe(e: Throwable): String = when (e) {
        is EpubException -> e.message ?: "El libro no es válido."
        is TooLargeException -> "El libro es demasiado grande o no queda espacio para abrirlo."
        is SecurityException -> "No hay permiso para leer el libro. Vuelve a añadirlo."
        is FileNotFoundException -> "El archivo ya no existe o se ha movido."
        is OutOfMemoryError -> "El libro es demasiado grande para la memoria disponible."
        else -> "El archivo está dañado o no es un ePub válido."
    }

    /** Desde el selector de la estantería: añade y avisa del resultado. */
    fun addBook(uri: Uri) {
        scope.launch {
            try {
                val result = addDocument(uri, null)
                _messages.tryEmit(if (result.isNew) "Añadido «${result.book.title}»" else "«${result.book.title}» ya estaba en la estantería")
            } catch (e: Exception) {
                _messages.tryEmit(describe(e))
            }
        }
    }

    /**
     * Añade un libro suelto. Si Android deja conservar el permiso de lectura, se guarda la referencia;
     * si no (lo normal al abrir desde otra app), se guarda una copia dentro de la app.
     */
    suspend fun addDocument(uri: Uri, name: String?): AddResult = withContext(Dispatchers.IO) {
        _books.value.firstOrNull { it.uri == uri.toString() }?.let { return@withContext AddResult(it, false) }
        val resolver = app.contentResolver
        if (uri.scheme == "content") {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val persisted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

        if (persisted) {
            val meta = try {
                readMeta(uri)
            } catch (e: Exception) {
                release(uri)
                throw e
            }
            if (meta.drm) {
                release(uri)
                throw EpubException(Epub.DRM_MESSAGE)
            }
            val size = querySize(uri)
            addLock.withLock {
                duplicateOf(meta, size)?.let {
                    release(uri)
                    return@withLock AddResult(it, false)
                }
                val book = newBook(hash(uri.toString()), uri.toString(), meta, size, null, false, name ?: queryName(uri))
                append(book)
                AddResult(book, true)
            }
        } else {
            val incoming = File(copiesDir, "entrando-${System.nanoTime()}.epub")
            try {
                (resolver.openInputStream(uri) ?: throw FileNotFoundException()).use { Streams.copyToFile(it, incoming) }
                val meta = EpubFile.open(incoming).use { it.meta() }
                if (meta.drm) throw EpubException(Epub.DRM_MESSAGE)
                val size = incoming.length()
                addLock.withLock {
                    duplicateOf(meta, size)?.let { return@withLock AddResult(it, false) }
                    val id = hash("copia|${meta.pkg.title}|${meta.pkg.author}|$size")
                    val target = File(copiesDir, "$id.epub")
                    target.delete()
                    if (!incoming.renameTo(target)) throw FileNotFoundException()
                    val book = newBook(id, Uri.fromFile(target).toString(), meta, size, null, true, name)
                    append(book)
                    AddResult(book, true)
                }
            } finally {
                incoming.delete()
            }
        }
    }

    fun addFolder(tree: Uri) {
        scope.launch {
            runCatching { app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val existing = _folders.value.firstOrNull { it.uri == tree.toString() }
            val folder = existing ?: Folder(tree.toString(), folderName(tree)).also { f ->
                _folders.update { it + f }
                save()
            }
            scanFolder(folder.uri)
        }
    }

    fun rescanAll() {
        scope.launch { _folders.value.map { it.uri }.forEach { scanFolder(it) } }
    }

    // Libros quitados que todavía se pueden recuperar con «Deshacer»
    private val removed = HashMap<String, Book>()

    /**
     * Quita el libro de la estantería, pero conserva sus archivos hasta [confirmRemoval] por si se deshace.
     * Nunca borra el archivo original; al confirmar sí la copia propia y la portada.
     */
    fun remove(id: String): Book? {
        val book = get(id) ?: return null
        synchronized(removed) { removed[id] = book }
        _books.update { list -> list.filterNot { it.id == id } }
        if (book.folder != null) {
            _folders.update { list -> list.map { if (it.uri == book.folder) it.copy(excluded = it.excluded + book.uri) else it } }
        }
        scope.launch { save() }
        return book
    }

    fun undoRemove(id: String) {
        val book = synchronized(removed) { removed.remove(id) } ?: return
        if (book.folder != null) {
            _folders.update { list -> list.map { if (it.uri == book.folder) it.copy(excluded = it.excluded - book.uri) else it } }
        }
        _books.update { list -> if (list.any { it.id == id }) list else list + book }
        scope.launch { save() }
    }

    fun confirmRemoval(id: String) {
        val book = synchronized(removed) { removed.remove(id) } ?: return
        if (book.folder == null && !book.copy) release(Uri.parse(book.uri))
        deleteFiles(book)
    }

    /** Borra copias y portadas que ya no pertenecen a ningún libro (p. ej. si la app se cerró antes de confirmar). */
    private fun deleteOrphans() {
        val keep = _books.value.flatMap { b -> listOfNotNull(b.cover, if (b.copy) Uri.parse(b.uri).path else null) }.toHashSet()
        // Solo lo que lleva un rato sin tocarse: lo reciente puede ser un libro que se está añadiendo ahora
        val old = System.currentTimeMillis() - 10 * 60 * 1000
        (copiesDir.listFiles()?.toList().orEmpty() + coversDir.listFiles()?.toList().orEmpty())
            .forEach { if (it.absolutePath !in keep && it.lastModified() < old) it.delete() }
    }

    /** Quita la carpeta y todos sus libros de la estantería (los archivos se quedan donde están). */
    fun removeFolder(uri: String) {
        val gone = _books.value.filter { it.folder == uri }
        gone.forEach(::deleteFiles)
        _books.update { list -> list.filterNot { it.folder == uri } }
        _folders.update { list -> list.filterNot { it.uri == uri } }
        release(Uri.parse(uri))
        scope.launch { save() }
    }

    fun savePosition(id: String, chapter: Int, fraction: Float, progress: Float) {
        val now = System.currentTimeMillis()
        _books.update { list ->
            list.map { if (it.id == id) it.copy(chapter = chapter, fraction = fraction, progress = progress, openedAt = now) else it }
        }
        scope.launch { save() }
    }

    /** Archivo local con acceso aleatorio para leer el libro. Solo se guarda en caché el último abierto. */
    suspend fun localFile(book: Book): File = withContext(Dispatchers.IO) {
        val uri = Uri.parse(book.uri)
        if (book.copy) {
            val file = File(uri.path ?: throw FileNotFoundException())
            if (!file.exists()) throw FileNotFoundException()
            return@withContext file
        }
        val target = File(readingDir, "${book.id}.epub")
        if (!(target.exists() && book.size > 0 && target.length() == book.size)) {
            val partial = File(readingDir, "${book.id}.parcial")
            (app.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()).use { Streams.copyToFile(it, partial) }
            target.delete()
            partial.renameTo(target)
        }
        readingDir.listFiles()?.filter { it != target }?.forEach { it.delete() }
        target
    }

    private suspend fun scanFolder(folderUri: String) = scanLock.withLock {
        val folder = _folders.value.firstOrNull { it.uri == folderUri } ?: return@withLock
        val tree = Uri.parse(folder.uri)
        _scan.value = ScanState(folder.name, 0, 0)
        try {
            val found = listEpubs(tree)
            var added = 0
            var drm = 0
            var failed = 0
            found.forEachIndexed { i, item ->
                _scan.value = ScanState(folder.name, i, found.size)
                val key = item.uri.toString()
                val excluded = _folders.value.firstOrNull { it.uri == folder.uri }?.excluded.orEmpty()
                if (key in excluded || _books.value.any { it.uri == key }) return@forEachIndexed
                try {
                    val meta = readMeta(item.uri)
                    if (meta.drm) {
                        drm++
                        return@forEachIndexed
                    }
                    addLock.withLock {
                        if (duplicateOf(meta, item.size) == null) {
                            append(newBook(hash(key), key, meta, item.size, folder.uri, false, item.name))
                            added++
                        }
                    }
                } catch (e: Exception) {
                    failed++
                }
            }
            // Los libros que ya no están en la carpeta salen de la estantería
            val present = found.map { it.uri.toString() }.toHashSet()
            val gone = _books.value.filter { it.folder == folder.uri && it.uri !in present }
            if (gone.isNotEmpty()) {
                gone.forEach(::deleteFiles)
                val ids = gone.map { it.id }.toHashSet()
                _books.update { list -> list.filterNot { it.id in ids } }
                save()
            }
            val parts = buildList {
                add(
                    when (added) {
                        0 -> "no hay libros nuevos"
                        1 -> "1 libro nuevo"
                        else -> "$added libros nuevos"
                    },
                )
                if (drm > 0) add("$drm con DRM omitidos")
                if (failed > 0) add("$failed no se pudieron leer")
                if (gone.isNotEmpty()) add("${gone.size} ya no estaban")
            }
            _messages.tryEmit("«${folder.name}»: ${parts.joinToString(", ")}")
        } catch (e: SecurityException) {
            _messages.tryEmit("Sin permiso para leer «${folder.name}». Quítala y vuelve a añadirla.")
        } catch (e: Exception) {
            _messages.tryEmit("No se pudo analizar «${folder.name}».")
        } finally {
            _scan.value = null
        }
    }

    private class Found(val uri: Uri, val name: String, val size: Long)

    /** Todos los .epub de la carpeta y sus subcarpetas (salvo las ocultas). */
    private fun listEpubs(tree: Uri): List<Found> {
        val out = ArrayList<Found>()
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add(DocumentsContract.getTreeDocumentId(tree) to 0)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        while (pending.isNotEmpty()) {
            val (docId, depth) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            app.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2).orEmpty()
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 8 && !name.startsWith(".")) pending.add(id to depth + 1)
                    } else if (name.endsWith(".epub", ignoreCase = true) || mime == "application/epub+zip") {
                        out += Found(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, size)
                    }
                }
            }
        }
        return out
    }

    private fun readMeta(uri: Uri): EpubMeta = try {
        EpubScanner.read { app.contentResolver.openInputStream(uri) ?: throw FileNotFoundException() }
    } catch (e: SecurityException) {
        throw e
    } catch (e: FileNotFoundException) {
        throw e
    } catch (e: Exception) {
        // Algunos ZIP no se pueden leer en flujo: se copian y se abren con acceso aleatorio
        val temp = File.createTempFile("analisis", ".epub", app.cacheDir)
        try {
            (app.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()).use { Streams.copyToFile(it, temp) }
            EpubFile.open(temp).use { it.meta() }
        } finally {
            temp.delete()
        }
    }

    private fun duplicateOf(meta: EpubMeta, size: Long): Book? {
        if (size <= 0) return null
        val title = meta.pkg.title ?: return null
        return _books.value.firstOrNull { it.size == size && it.title == title && it.author == meta.pkg.author }
    }

    private fun newBook(id: String, uri: String, meta: EpubMeta, size: Long, folder: String?, copy: Boolean, name: String?): Book =
        Book(
            id = id,
            uri = uri,
            title = meta.pkg.title?.takeIf { it.isNotBlank() }
                ?: name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: "Sin título",
            author = meta.pkg.author?.takeIf { it.isNotBlank() },
            cover = saveCover(id, meta.cover),
            size = size,
            folder = folder,
            copy = copy,
            addedAt = System.currentTimeMillis(),
        )

    private fun append(book: Book) {
        _books.update { it + book }
        save()
    }

    /** Miniatura de la portada, reducida para no cargar imágenes enormes en la estantería. */
    private fun saveCover(id: String, bytes: ByteArray?): String? {
        bytes ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 360) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val file = File(coversDir, "$id.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
            file.absolutePath
        }.getOrNull()
    }

    private fun deleteFiles(book: Book) {
        book.cover?.let { File(it).delete() }
        if (book.copy) Uri.parse(book.uri).path?.let { File(it).delete() }
        File(readingDir, "${book.id}.epub").delete()
    }

    private fun release(uri: Uri) {
        runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun querySize(uri: Uri): Long = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        }
    }.getOrNull() ?: -1L

    private fun queryName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun folderName(tree: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        app.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: tree.lastPathSegment?.substringAfterLast(':')?.ifEmpty { null } ?: "Carpeta"

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private fun save() = synchronized(saveLock) {
        val root = JSONObject()
            .put("books", JSONArray().apply { _books.value.forEach { put(it.toJson()) } })
            .put(
                "folders",
                JSONArray().apply {
                    _folders.value.forEach { f ->
                        put(JSONObject().put("uri", f.uri).put("name", f.name).put("excluded", JSONArray(f.excluded.toList())))
                    }
                },
            )
        val temp = File(app.filesDir, "biblioteca.json.tmp")
        temp.writeText(root.toString())
        temp.renameTo(dataFile)
    }

    private fun load() {
        if (!dataFile.exists()) return
        val root = JSONObject(dataFile.readText())
        val books = root.optJSONArray("books") ?: JSONArray()
        _books.value = (0 until books.length()).mapNotNull { runCatching { bookOf(books.getJSONObject(it)) }.getOrNull() }
        val folders = root.optJSONArray("folders") ?: JSONArray()
        _folders.value = (0 until folders.length()).map { i ->
            val o = folders.getJSONObject(i)
            val excluded = o.optJSONArray("excluded") ?: JSONArray()
            Folder(o.getString("uri"), o.optString("name", "Carpeta"), (0 until excluded.length()).map { excluded.getString(it) }.toSet())
        }
    }

    private fun Book.toJson() = JSONObject()
        .put("id", id)
        .put("uri", uri)
        .put("title", title)
        .put("author", author.orEmpty())
        .put("cover", cover.orEmpty())
        .put("size", size)
        .put("folder", folder.orEmpty())
        .put("copy", copy)
        .put("chapter", chapter)
        .put("fraction", fraction.toDouble())
        .put("progress", progress.toDouble())
        .put("added", addedAt)
        .put("opened", openedAt)

    private fun bookOf(o: JSONObject) = Book(
        id = o.getString("id"),
        uri = o.getString("uri"),
        title = o.getString("title"),
        author = o.optString("author").ifEmpty { null },
        cover = o.optString("cover").ifEmpty { null },
        size = o.optLong("size", -1),
        folder = o.optString("folder").ifEmpty { null },
        copy = o.optBoolean("copy"),
        chapter = o.optInt("chapter"),
        fraction = o.optDouble("fraction", 0.0).toFloat(),
        progress = o.optDouble("progress", 0.0).toFloat(),
        addedAt = o.optLong("added"),
        openedAt = o.optLong("opened"),
    )
}
