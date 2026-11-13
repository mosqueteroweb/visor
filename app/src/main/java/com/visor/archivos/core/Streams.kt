package com.visor.archivos.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class TooLargeException : IOException("El archivo es demasiado grande.")

object Streams {
    /** Tope para copiar un archivo dentro de la app: nadie necesita un libro o un PDF mayor. */
    const val MAX_COPY_BYTES = 512L shl 20

    /** Margen de almacenamiento que una copia nunca debe consumir. */
    private const val KEEP_FREE_BYTES = 100L shl 20

    /** Copia como mucho [max] bytes; si el origen tiene más, falla en vez de llenar el almacenamiento. */
    fun copyLimited(input: InputStream, output: OutputStream, max: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw TooLargeException()
            output.write(buffer, 0, n)
        }
    }

    /** Copia a [target] con el tope general y sin apurar el espacio libre; si falla, no deja restos. */
    fun copyToFile(input: InputStream, target: File) {
        val free = (target.parentFile?.usableSpace ?: Long.MAX_VALUE) - KEEP_FREE_BYTES
        try {
            target.outputStream().use { copyLimited(input, it, minOf(MAX_COPY_BYTES, maxOf(0, free))) }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    /** Lee como mucho [max] bytes; devuelve null si hay más (no se fía del tamaño declarado). */
    fun readLimited(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > max) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
