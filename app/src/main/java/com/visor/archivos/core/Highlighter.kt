package com.visor.archivos.core

enum class Token { COMMENT, STRING, NUMBER, KEYWORD, KEY }

/** Tramo coloreado: [start, end) sobre el texto completo. */
data class Span(val start: Int, val end: Int, val token: Token)

/**
 * Resaltado sencillo por familias de sintaxis. No analiza el lenguaje: reconoce comentarios,
 * cadenas, números, palabras clave y claves, que es lo que hace legible un archivo al verlo.
 */
object Highlighter {

    private const val STR = "\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'"
    private const val NUM = "\\b0[xX][0-9a-fA-F_]+\\b|\\b\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fFlLuU]{0,2}\\b"
    private const val WORD = "\\b[A-Za-z_][A-Za-z0-9_]*\\b"

    // Grupos: 1 comentario, 2 cadena, 3 número, 4 palabra
    private val cLike = Regex("(//[^\\n]*|/\\*[\\s\\S]*?\\*/)|($STR|`(?:\\\\.|[^`\\\\])*`)|($NUM)|($WORD)")
    private val hash = Regex("(#[^\\n]*)|(\"\"\"[\\s\\S]*?\"\"\"|$STR)|($NUM)|($WORD)")
    private val sql = Regex("(--[^\\n]*|/\\*[\\s\\S]*?\\*/)|('(?:''|[^'])*')|($NUM)|($WORD)")

    // Grupos: 1 comentario, 2 cadena, 3 clave, 4 número, 5 literal
    private val keyValue = Regex(
        "(^[ \\t]*[#;][^\\n]*|[ \\t]#[^\\n]*)|($STR)|(^[ \\t-]*[A-Za-z0-9_.\\-/\\[\\]]+(?=[ \\t]*[:=]))|($NUM)|(\\b(?:true|false|null|yes|no|on|off)\\b)",
        RegexOption.MULTILINE,
    )

    // Grupos: 1 cadena, 2 dos puntos (si es clave), 3 número, 4 literal
    private val json = Regex("(\"(?:\\\\.|[^\"\\\\])*\")(\\s*:)?|(-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)|(\\b(?:true|false|null)\\b)")

    // Grupos: 1 comentario, 2 etiqueta, 3 cadena, 4 atributo
    private val markup = Regex("(<!--[\\s\\S]*?-->)|(</?[A-Za-z_!?][\\w:.\\-]*|[/?]?>)|(\"[^\"]*\"|'[^']*')|([\\w:.\\-]+(?==))")

    private val cKeywords = (
        "abstract as async await break case catch class companion const continue data def default defer do else enum " +
            "export extends extern final finally fn for func fun function go if impl implements import in interface internal " +
            "is let match mut namespace new null nil object operator override package private protected pub public return " +
            "sealed static struct super suspend switch this throw throws trait true false try type typealias typeof undefined " +
            "use using val var void volatile when where while yield int long float double boolean bool char byte short string"
        ).split(' ').toSet()

    private val hashKeywords = (
        "and as assert async await begin break case class continue def del do done elif else elsif end esac except exit " +
            "export fi finally for from function global if import in is lambda local module nil None not or pass raise " +
            "require rescue return self then True False true false try unless until while with yield echo"
        ).split(' ').toSet()

    private val sqlKeywords = (
        "add all alter and as asc begin between by case check column commit constraint create cross database default delete " +
            "desc distinct drop else end exists foreign from full group having if in index inner insert into is join key left " +
            "like limit not null offset on or order outer primary references right rollback select set table then union unique " +
            "update values view when where with"
        ).split(' ').toSet()

    fun highlight(text: String, syntax: Syntax): List<Span> = when (syntax) {
        Syntax.NONE -> emptyList()
        Syntax.C_LIKE -> scanWords(text, cLike) { it in cKeywords }
        Syntax.HASH -> scanWords(text, hash) { it in hashKeywords }
        Syntax.SQL -> scanWords(text, sql) { it.lowercase() in sqlKeywords }
        Syntax.KEY_VALUE -> scan(text, keyValue, Token.COMMENT, Token.STRING, Token.KEY, Token.NUMBER, Token.KEYWORD)
        Syntax.MARKUP -> scan(text, markup, Token.COMMENT, Token.KEYWORD, Token.STRING, Token.KEY)
        Syntax.JSON -> json.findAll(text).mapNotNull { m ->
            when {
                m.groups[1] != null -> {
                    val r = m.groups[1]!!.range
                    Span(r.first, r.last + 1, if (m.groups[2] != null) Token.KEY else Token.STRING)
                }
                m.groups[3] != null -> Span(m.range.first, m.range.last + 1, Token.NUMBER)
                else -> Span(m.range.first, m.range.last + 1, Token.KEYWORD)
            }
        }.toList()
    }

    private fun scanWords(text: String, regex: Regex, isKeyword: (String) -> Boolean): List<Span> {
        val out = ArrayList<Span>()
        for (m in regex.findAll(text)) {
            val token = when {
                m.groups[1] != null -> Token.COMMENT
                m.groups[2] != null -> Token.STRING
                m.groups[3] != null -> Token.NUMBER
                isKeyword(m.value) -> Token.KEYWORD
                else -> continue
            }
            out += Span(m.range.first, m.range.last + 1, token)
        }
        return out
    }

    private fun scan(text: String, regex: Regex, vararg tokens: Token): List<Span> {
        val out = ArrayList<Span>()
        for (m in regex.findAll(text)) {
            val index = tokens.indices.firstOrNull { m.groups[it + 1] != null } ?: continue
            val r = m.groups[index + 1]!!.range
            if (!r.isEmpty()) out += Span(r.first, r.last + 1, tokens[index])
        }
        return out
    }
}
