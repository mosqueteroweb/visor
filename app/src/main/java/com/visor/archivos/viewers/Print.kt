package com.visor.archivos.viewers

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.TextUtils
import android.webkit.WebView
import android.webkit.WebViewClient
import com.visor.archivos.core.Table
import java.io.FileOutputStream
import kotlin.concurrent.thread
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/** Impresión con el servicio del sistema (impresoras y «Guardar como PDF»). */
object Printer {
    private const val STYLE = """
        body { font-family: sans-serif; font-size: 11pt; line-height: 1.4; color: #000; }
        pre, code { font-family: monospace; font-size: 9pt; white-space: pre-wrap; word-break: break-word; }
        table { border-collapse: collapse; font-size: 9pt; }
        th, td { border: 1px solid #999; padding: 3px 6px; text-align: left; vertical-align: top; }
        th { background: #eee; }
        img { max-width: 100%; }
        blockquote { border-left: 3px solid #999; margin-left: 0; padding-left: 12px; }
    """

    // El WebView tiene que seguir vivo mientras dura el trabajo de impresión
    private var pending: WebView? = null

    /** Un PDF se entrega tal cual al sistema, sin volver a dibujarlo. */
    fun pdf(activity: Activity, uri: Uri, name: String) {
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes, signal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
                if (signal.isCanceled) return callback.onLayoutCancelled()
                val info = PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
                callback.onLayoutFinished(info, new != old)
            }

            override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, signal: CancellationSignal, callback: WriteResultCallback) {
                thread {
                    try {
                        activity.contentResolver.openInputStream(uri)!!.use { input ->
                            FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) }
                        }
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback.onWriteFailed(e.message)
                    }
                }
            }
        }
        manager(activity).print(name, adapter, null)
    }

    fun text(activity: Activity, text: String, name: String) =
        html(activity, "<pre>${TextUtils.htmlEncode(text)}</pre>", name)

    fun markdown(activity: Activity, source: String, name: String) {
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
        html(activity, HtmlGenerator(source, tree, flavour).generateHtml(), name)
    }

    fun table(activity: Activity, table: Table, name: String) {
        val body = buildString {
            append("<table><tr>")
            table.header.forEach { append("<th>").append(TextUtils.htmlEncode(it)).append("</th>") }
            append("</tr>")
            table.rows.forEach { row ->
                append("<tr>")
                for (col in 0 until table.columns) append("<td>").append(TextUtils.htmlEncode(row.getOrNull(col).orEmpty())).append("</td>")
                append("</tr>")
            }
            append("</table>")
        }
        html(activity, body, name)
    }

    fun image(activity: Activity, uri: Uri, name: String) =
        html(activity, "<img src=\"${TextUtils.htmlEncode(uri.toString())}\">", name)

    private fun html(activity: Activity, body: String, name: String) {
        val web = WebView(activity)
        pending = web
        // Solo se dibuja contenido propio: ni scripts ni red
        web.settings.javaScriptEnabled = false
        web.settings.blockNetworkLoads = true
        web.settings.allowContentAccess = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                manager(activity).print(name, view.createPrintDocumentAdapter(name), null)
            }
        }
        web.loadDataWithBaseURL(null, "<html><head><meta charset=\"utf-8\"><style>$STYLE</style></head><body>$body</body></html>", "text/html", "utf-8", null)
    }

    private fun manager(context: Context) = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
}
