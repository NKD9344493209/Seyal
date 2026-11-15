package com.seyal.app.agent

import org.json.JSONObject
import java.io.File

/**
 * A searchable record of the messages that arrived as notifications, so "the address Ram sent me"
 * or "the PDF my professor sent last week" can be found later. Kept only on the phone, for
 * [KEEP_DAYS] days, never sent anywhere; codes and passwords are not kept.
 */
object Past {
    class Note(val at: Long, val app: String, val chat: String, val text: String, val group: Boolean) {
        fun toJson(): JSONObject = JSONObject().put("at", at).put("app", app).put("chat", chat).put("text", text).put("g", group)
    }

    const val KEEP_DAYS = 30
    const val MAX = 4000
    private var file: File? = null
    private val notes = ArrayList<Note>()
    private var sinceCompact = 0
    private val secret = Regex("\\b(otp|one[- ]time|verification code|passcode|password|cvv|upi pin)\\b", RegexOption.IGNORE_CASE)

    @Synchronized fun init(dir: File) {
        file = File(dir, "seyal/past.jsonl"); notes.clear()
        runCatching {
            file!!.forEachLine { line -> runCatching { JSONObject(line).let { o -> notes += Note(o.optLong("at"), o.optString("app"), o.optString("chat"), o.optString("text"), o.optBoolean("g")) } } }
        }
        compact(System.currentTimeMillis())
    }

    private fun compact(now: Long) {
        val cutoff = now - KEEP_DAYS * 24L * 3600_000L
        val before = notes.size
        notes.removeAll { it.at < cutoff }
        while (notes.size > MAX) notes.removeAt(0)
        if (notes.size != before || sinceCompact > 500) { runCatching { file?.let { f -> f.parentFile?.mkdirs(); f.writeText(notes.joinToString("") { it.toJson().toString() + "\n" }) } }; sinceCompact = 0 }
    }

    @Synchronized fun clear() { notes.clear(); runCatching { file?.delete() } }
    @Synchronized fun size(): Int = notes.size

    /** Remembers one message. Codes and passwords are skipped; an exact repeat of the last one from that chat is too. */
    @Synchronized fun add(app: String, chat: String, text: String, group: Boolean, at: Long) {
        val t = text.trim().take(700)
        if (t.length < 3 || secret.containsMatchIn(t)) return
        if (notes.asReversed().take(8).any { it.app == app && it.chat == chat && it.text == t }) return
        val n = Note(at, app, chat.take(60), t, group)
        notes += n
        runCatching { file?.let { f -> f.parentFile?.mkdirs(); f.appendText(n.toJson().toString() + "\n") } }
        if (++sinceCompact > 500 || notes.size > MAX + 200) compact(at)
    }

    private val small = setOf("the", "a", "an", "to", "me", "my", "i", "of", "in", "on", "at", "for", "that", "this", "is", "was", "did", "from", "sent", "send", "message", "msg", "what", "which", "who", "find", "show", "get", "last", "week", "yesterday", "today", "ago", "about", "said", "say", "told", "tell", "and", "or", "by", "with")
    private val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val fileWord = Regex("\\.(pdf|docx?|pptx?|xlsx?|zip|jpg|png|mp4)\\b|📄|📎|\\b(document|file|photo|image|video)\\b", RegexOption.IGNORE_CASE)
    private val phone = Regex("(?<!\\d)(?:\\+?91[\\s-]?)?[6-9]\\d{4}[\\s-]?\\d{5}(?!\\d)")
    private val address = Regex("\\b(street|st\\.|road|rd\\.|nagar|colony|layout|cross|main|avenue|opp\\.?|near|door no|no\\.? ?\\d|floor|apartment|flat|pin ?code|\\d{6})\\b", RegexOption.IGNORE_CASE)

    private fun words(s: String): List<String> = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(Regex("\\s+")).filter { it.length >= 2 && it !in small }

    /**
     * The messages that best match [query], newest first among equals. [days]: how far back (0 = all kept).
     * [from]: only this person or chat. Asking for "the link / pdf / address / number" favours messages that have one.
     */
    @Synchronized fun search(query: String, now: Long, days: Int = 0, from: String = "", limit: Int = 8): List<Note> {
        val q = words(query)
        val wantsLink = Regex("\\b(link|url|website|site|form)\\b", RegexOption.IGNORE_CASE).containsMatchIn(query)
        val wantsFile = Regex("\\b(pdf|file|document|doc|ppt|photo|image|video|notes)\\b", RegexOption.IGNORE_CASE).containsMatchIn(query)
        val wantsPhone = Regex("\\b(number|phone|contact|mobile)\\b", RegexOption.IGNORE_CASE).containsMatchIn(query)
        val wantsAddress = Regex("\\b(address|location|where)\\b", RegexOption.IGNORE_CASE).containsMatchIn(query)
        val kindWords = setOf("link", "url", "website", "site", "form", "pdf", "file", "document", "doc", "ppt", "photo", "image", "video", "notes", "number", "phone", "contact", "mobile", "address", "location", "where")
        val topic = q.filter { it !in kindWords }
        val cutoff = if (days > 0) now - days * 24L * 3600_000L else 0L
        val who = from.trim().lowercase()
        return notes.asSequence().filter { it.at >= cutoff && (who.isBlank() || it.chat.lowercase().contains(who)) }.mapNotNull { n ->
            val hay = words(n.chat + " " + n.text + " " + n.app).toSet()
            val hits = topic.count { w -> w in hay || hay.any { h -> h.length >= 4 && w.length >= 4 && (h.startsWith(w) || w.startsWith(h)) } }
            var score = hits * 10
            if (wantsLink && url.containsMatchIn(n.text)) score += 8
            if (wantsFile && fileWord.containsMatchIn(n.text)) score += 8
            if (wantsPhone && phone.containsMatchIn(n.text)) score += 8
            if (wantsAddress && address.containsMatchIn(n.text)) score += 8
            // Something must tie the message to the question: a topic word, or (when the chat is given) the kind of thing asked for.
            if (topic.isNotEmpty() && hits == 0 && !(who.isNotBlank() && score > 0)) return@mapNotNull null
            if (topic.isEmpty() && score == 0 && who.isBlank()) return@mapNotNull null
            n to score
        }.sortedWith(compareByDescending<Pair<Note, Int>> { it.second }.thenByDescending { it.first.at }).take(limit).map { it.first }.toList()
    }
}
