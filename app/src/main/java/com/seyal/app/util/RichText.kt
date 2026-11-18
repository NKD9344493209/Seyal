package com.seyal.app.util

/**
 * One line of an answer cut into plain text, **bold** and links, so the chat can draw a link as its name
 * and open it on a tap. (On a real phone the sources of an answer showed as raw "[name](https://…)".)
 */
object RichText {
    enum class Kind { PLAIN, BOLD, LINK }
    class Piece(val kind: Kind, val text: String, val url: String = "")

    private val token = Regex("\\*\\*(.+?)\\*\\*|\\[([^\\]\\n]{1,200})\\]\\((https?://[^)\\s]+)\\)|(https?://[^\\s<>\"')\\]]+)")

    /** The address without what a sentence puts after it. */
    private fun tidy(url: String) = url.trimEnd('.', ',', ';', ':', '!', '?', '*', '_')

    /** A bare link as it is shown: the site and the start of its path. */
    fun short(url: String): String {
        val u = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
        return if (u.length <= 42) u else u.take(40) + "…"
    }

    fun pieces(line: String): List<Piece> {
        val s = line.replace("`", "")
        val out = ArrayList<Piece>()
        var at = 0
        for (m in token.findAll(s)) {
            if (m.range.first > at) out += Piece(Kind.PLAIN, s.substring(at, m.range.first))
            when {
                m.groupValues[1].isNotEmpty() -> out += Piece(Kind.BOLD, m.groupValues[1])
                m.groupValues[3].isNotEmpty() -> out += Piece(Kind.LINK, m.groupValues[2].trim(), tidy(m.groupValues[3]))
                else -> {
                    val url = tidy(m.groupValues[4])
                    out += Piece(Kind.LINK, short(url), url)
                    // The full stop that ended the sentence stays as text.
                    if (url.length < m.groupValues[4].length) out += Piece(Kind.PLAIN, m.groupValues[4].substring(url.length))
                }
            }
            at = m.range.last + 1
        }
        if (at < s.length) out += Piece(Kind.PLAIN, s.substring(at))
        // A stray "**" with no partner is not shown.
        return out.map { if (it.kind == Kind.PLAIN) Piece(Kind.PLAIN, it.text.replace("**", "")) else it }.filter { it.text.isNotEmpty() }
    }

    /** The first link of a line: where a tap on the line goes. */
    fun firstLink(line: String): String? = pieces(line).firstOrNull { it.kind == Kind.LINK }?.url
}
