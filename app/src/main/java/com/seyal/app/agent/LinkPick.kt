package com.seyal.app.agent

/**
 * "Go to that link", "open the second link", "open the Amazon one": which link of the answer just given
 * the user means. Plain rules, no AI, so it opens at once.
 */
object LinkPick {
    private val I = RegexOption.IGNORE_CASE
    private val url = Regex("https?://[^\\s<>\"')\\]]+", I)
    private val polite = Regex("^(?:please|pls|can you|could you|kindly)\\s+|\\s+(?:please|pls|now|for me)$", I)
    private const val LINKWORD = "(?:links?|urls?|sites?|websites?|web ?pages?|pages?)"
    private const val ORD = "(first|1st|second|2nd|third|3rd|fourth|4th|last)"

    /** The words point at a link or a page rather than name a place ("that link", "the website", "it", "www.x.com"). */
    fun pointsAtLink(text: String): Boolean {
        val t = polite.replace(text.trim().lowercase().trimEnd('.', '!', '?'), "").trim()
        return Regex("(?:^|\\s)(?:$LINKWORD|results?)$", I).containsMatchIn(t) ||
            Regex("https?://|\\bwww\\.|[a-z0-9]\\.(?:com|org|net|io|co\\.in|in)(?:/|$)", I).containsMatchIn(t) ||
            Regex("\\bto (?:it|that|this|there|(?:the |that )?(?:$ORD )?one)$", I).containsMatchIn(t)
    }

    /** Every link in [answer], in order, without the punctuation that follows a link in a sentence. */
    fun links(answer: String?): List<String> =
        url.findAll(answer.orEmpty()).map { it.value.trimEnd('.', ',', ';', ':', '!', '?', '*', '_') }.filter { it.length > 10 }.distinct().toList()

    fun host(link: String): String = Regex("^https?://(?:www\\.)?([^/?#]+)", I).find(link)?.groupValues?.get(1) ?: link.take(40)

    private val ask = Regex(
        "^(?:go|go to|goto|open|show|visit|take me to|launch|click|tap)(?: on| to| me)?\\s+(?:the |that |this |above |same |given )?(?:$ORD\\s+)?(?:(\\p{L}[\\p{L}\\p{N}]{2,20})\\s+)?($LINKWORD|one)$" +
            "|^(?:go|go to|goto|open|visit)(?: to)? (?:it|that|this)$",
        I,
    )
    private val direct = Regex("^(?:go to|goto|open|visit|go)\\s+((?:https?://)?(?:www\\.)?[a-z0-9][a-z0-9.-]*\\.(?:com|in|org|net|io|co|co\\.in|gov\\.in|ac\\.in|app|dev)(?:/\\S*)?)$", I)

    /**
     * The link [said] asks for among those in [lastAnswer]; a web address typed outright ("go to irctc.co.in") is
     * that address. Null when the words are not such a request, or the answer has no link to mean.
     */
    fun pick(said: String, lastAnswer: String?): String? {
        val t = polite.replace(said.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " "), "").trim()
        direct.find(t)?.let { m -> val a = m.groupValues[1]; return if (a.startsWith("http", true)) a else "https://$a" }
        val m = ask.find(t) ?: return null
        val all = links(lastAnswer)
        if (all.isEmpty()) return null
        val ord = m.groupValues.getOrNull(1).orEmpty().lowercase()
        val name = m.groupValues.getOrNull(2).orEmpty().lowercase().takeIf { it !in setOf("that", "the", "this", "above", "same", "given", "web") }.orEmpty()
        // "the one" says which only with "first" or a name in front of it.
        if (m.groupValues.getOrNull(3).orEmpty().equals("one", true) && ord.isBlank() && name.isBlank()) return null
        if (name.isNotBlank()) {
            all.firstOrNull { host(it).contains(name, true) }?.let { return it }
            // Named something that is no link here ("open the settings page"): not ours to answer.
            if (ord.isBlank()) return null
        }
        return when (ord) {
            "second", "2nd" -> all.getOrNull(1)
            "third", "3rd" -> all.getOrNull(2)
            "fourth", "4th" -> all.getOrNull(3)
            "last" -> all.last()
            else -> all.first()
        }
    }
}
