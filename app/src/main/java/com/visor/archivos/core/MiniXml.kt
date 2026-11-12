package com.visor.archivos.core

/** Etiqueta encontrada en un texto XML/HTML. [name] y las claves de [attrs] van en minúsculas y sin prefijo. */
class XmlTag(
    val name: String,
    val closing: Boolean,
    val selfClosing: Boolean,
    val attrs: Map<String, String>,
    val start: Int,
    val end: Int,
) {
    fun attr(key: String): String? = attrs[key]
}

/**
 * Lector de etiquetas tolerante. Los ePub suelen traer XML mal formado (entidades HTML,
 * etiquetas sin cerrar) que un analizador estricto rechaza; aquí basta con encontrar etiquetas y texto.
 */
object MiniXml {
    private val comment = Regex("<!--[\\s\\S]*?-->")
    private val cdata = Regex("<!\\[CDATA\\[([\\s\\S]*?)]]>")
    private val tag = Regex("<(/?)([A-Za-z_][\\w:.\\-]*)([^<>]*?)(/?)>")
    private val attr = Regex("([^\\s=/>\"']+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")
    private val anyTag = Regex("<[^>]*>")
    private val spaces = Regex("\\s+")
    private val entity = Regex("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»",
        "iexcl" to "¡", "iquest" to "¿", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
    )

    /** Borra los comentarios con espacios, para que no cuenten como etiquetas y no se muevan las posiciones. */
    fun clean(text: String): String = comment.replace(text) { " ".repeat(it.value.length) }

    fun tags(text: String): List<XmlTag> = tag.findAll(text).map { m ->
        val attrs = HashMap<String, String>()
        for (a in attr.findAll(m.groupValues[3])) {
            val key = a.groupValues[1].lowercase()
            val value = decode(a.groups[2]?.value ?: a.groups[3]?.value ?: a.groupValues[4])
            attrs[key] = value
            attrs.putIfAbsent(key.substringAfter(':'), value)
        }
        XmlTag(
            name = m.groupValues[2].substringAfter(':').lowercase(),
            closing = m.groupValues[1] == "/",
            selfClosing = m.groupValues[4] == "/",
            attrs = attrs,
            start = m.range.first,
            end = m.range.last + 1,
        )
    }.toList()

    /** Texto visible entre dos posiciones, sin etiquetas y con los espacios normalizados. */
    fun innerText(text: String, from: Int, to: Int): String {
        val raw = cdata.replace(text.substring(from, to)) { it.groupValues[1] }
        return decode(anyTag.replace(raw, " ")).replace(spaces, " ").trim()
    }

    fun decode(s: String): String = if ('&' !in s) s else entity.replace(s) { m ->
        val e = m.groupValues[1]
        val code = when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)
            e.startsWith("#") -> e.substring(1).toIntOrNull()
            else -> null
        }
        when {
            code != null && Character.isValidCodePoint(code) -> String(Character.toChars(code))
            code == null -> named[e] ?: m.value
            else -> m.value
        }
    }
}
