package com.visor.archivos.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lo que se sabe de un archivo antes de abrirlo. [size] es -1 si el origen no lo dice. */
data class DocInfo(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mime: String?,
    val kind: FileKind,
)

object Docs {
    private const val HEAD_BYTES = 4096

    suspend fun inspect(context: Context, uri: Uri, hintMime: String?): DocInfo = withContext(Dispatchers.IO) {
        var name: String? = null
        var size = -1L
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val s = c.getColumnIndex(OpenableColumns.SIZE)
                            if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                            if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                        }
                    }
            }
        } else {
            uri.path?.let { File(it) }?.let { f ->
                name = f.name
                if (f.exists()) size = f.length()
            }
        }
        val mime = (runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: hintMime)
            ?.takeUnless { it == "*/*" }
        val head = open(context, uri).use { readUpTo(it, HEAD_BYTES) }
        val finalName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Archivo"
        DocInfo(uri, finalName, size, mime, TypeDetector.detect(head, finalName, mime))
    }

    fun open(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("No se puede abrir el archivo")

    /** Lee como mucho [max] bytes. Devuelve los bytes y si quedaba más por leer. */
    suspend fun readLimited(context: Context, uri: Uri, max: Int): Pair<ByteArray, Boolean> = withContext(Dispatchers.IO) {
        open(context, uri).use { input ->
            val bytes = readUpTo(input, max)
            bytes to (bytes.size == max && input.read() >= 0)
        }
    }

    fun readUpTo(input: InputStream, max: Int): ByteArray {
        var buffer = ByteArray(minOf(max, 64 * 1024))
        var count = 0
        while (count < max) {
            if (count == buffer.size) buffer = buffer.copyOf(minOf(max, buffer.size * 2))
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
        }
        return if (count == buffer.size) buffer else buffer.copyOf(count)
    }
}
