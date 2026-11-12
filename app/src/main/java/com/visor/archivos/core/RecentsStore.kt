package com.visor.archivos.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class Recent(val uri: Uri, val name: String, val size: Long, val mime: String?, val kind: FileKind, val openedAt: Long)

/**
 * Archivos abiertos hace poco. Solo se guardan los que Android deja volver a abrir más tarde
 * (permiso persistente); los que llegan con un permiso de un solo uso no aparecerían rotos en la lista.
 */
class RecentsStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("recientes", Context.MODE_PRIVATE)

    fun list(): List<Recent> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Recent(
                    uri = Uri.parse(o.getString("uri")),
                    name = o.getString("name"),
                    size = o.optLong("size", -1),
                    mime = o.optString("mime").ifEmpty { null },
                    kind = runCatching { FileKind.valueOf(o.getString("kind")) }.getOrDefault(FileKind.BINARY),
                    openedAt = o.optLong("at"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Intenta conservar el permiso de lectura y, si se consigue, apunta el archivo como reciente. */
    fun remember(info: DocInfo) {
        if (info.uri.scheme != "content") return
        val resolver = app.contentResolver
        runCatching { resolver.takePersistableUriPermission(info.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val persisted = resolver.persistedUriPermissions.any { it.uri == info.uri && it.isReadPermission }
        if (!persisted) return

        val current = list().filterNot { it.uri == info.uri }
        val updated = listOf(Recent(info.uri, info.name, info.size, info.mime, info.kind, System.currentTimeMillis())) + current
        updated.drop(MAX).forEach { release(it.uri) }
        save(updated.take(MAX))
    }

    fun remove(uri: Uri) {
        release(uri)
        save(list().filterNot { it.uri == uri })
    }

    fun clear() {
        list().forEach { release(it.uri) }
        save(emptyList())
    }

    private fun release(uri: Uri) {
        runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun save(items: List<Recent>) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("uri", it.uri.toString())
                    .put("name", it.name)
                    .put("size", it.size)
                    .put("mime", it.mime.orEmpty())
                    .put("kind", it.kind.name)
                    .put("at", it.openedAt),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "items"
        const val MAX = 30
    }
}
