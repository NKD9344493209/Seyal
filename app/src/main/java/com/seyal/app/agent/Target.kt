package com.seyal.app.agent

/**
 * Where a tap on a reminder or a notice should take the user: to the thing it is about, not to Seyal.
 * "Reply to the mail from the bank" opens the mail app; a reminder made from a WhatsApp message opens WhatsApp;
 * "Pay the fee: https://…" opens the link. Only when nothing fits does the tap open Seyal.
 *
 * A target is written as one short word and what follows it:
 *   ""                   Seyal itself
 *   "url:https://…"      a link
 *   "app:WhatsApp"       an app, by the name on its icon
 *   "pkg:com.bt.bms"     an app, by its package
 *   "mail:"              the mail app
 *   "note:TARGET⏎KEY"    the very chat or mail a notification is about (by pressing that notification, found by its
 *                        key); when it is no longer there, TARGET
 * Working out the target is plain logic (tested without a phone); opening it is device/Open.kt.
 */
object Target {
    private val I = RegexOption.IGNORE_CASE
    private val link = Regex("https?://[^\\s<>\"')\\]]+", I)
    private val mailWord = Regex("\\b(e-?mails?|mails?|gmail|inbox)\\b|மெயில்|மின்னஞ்சல்", I)
    /** Names that are also everyday words: seeing one in a sentence does not mean the app. */
    private val plainWords = setOf("phone", "clock", "files", "settings", "camera", "contacts", "music", "notes", "calculator", "messages", "seyal", "home", "play", "one", "assistant", "search", "tips", "wallet", "games", "themes", "video", "radio", "weather", "compass", "recorder", "security", "services", "feedback", "downloads", "browser")

    /** What the AI or a caller named as the thing to open ([open]: a link, "mail", or an app's name) as a target. */
    fun named(open: String): String {
        val o = open.trim().trim('"', '\'')
        return when {
            o.isEmpty() -> ""
            o.startsWith("url:") || o.startsWith("app:") || o.startsWith("pkg:") || o.startsWith("mail:") || o.startsWith("note:") -> o
            link.containsMatchIn(o) -> "url:" + link.find(o)!!.value.trimEnd('.', ',', ';')
            Regex("^(e-?mail|mail|gmail|inbox|my mail)$", I).matches(o) -> "mail:"
            else -> "app:" + o.take(40)
        }
    }

    /**
     * The target for a reminder or notice that says [text], when nobody named one. [labels]: the names of the apps on
     * this phone. In order: a link in the text; the mail app when it is about a mail; an app named in the text
     * (the longest name wins, so "Google Pay" beats "Google").
     */
    fun infer(text: String, labels: List<String>): String {
        link.find(text)?.let { return "url:" + it.value.trimEnd('.', ',', ';', ')') }
        val low = text.lowercase()
        val app = labels.asSequence().map { it.trim() }.filter { it.length >= 4 && it.lowercase() !in plainWords }
            .filter { l -> Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(l.lowercase()) + "(?![\\p{L}\\p{N}])").containsMatchIn(low) }
            .maxByOrNull { it.length }
        // "Gmail" on the phone and "mail" in the text: the mail app, by its own name.
        if (app != null && !(mailWord.containsMatchIn(text) && app.length < 5)) return "app:$app"
        if (mailWord.containsMatchIn(text)) return "mail:"
        return ""
    }

    /** The target to use: what was named, else what the text is about. */
    fun of(open: String, text: String, labels: List<String>): String = named(open).ifBlank { infer(text, labels) }

    /** For a notice that came from another app ("WhatsApp · CSE Final Year", "Gmail"): that app, unless it carries a link. */
    fun fromSource(source: String, link: String = ""): String = when {
        link.isNotBlank() -> named(link)
        else -> source.substringBefore(" · ").trim().takeIf { it.length >= 2 && it != "Test" }?.let { if (mailWord.containsMatchIn(it)) "mail:" else "app:$it" }.orEmpty()
    }

    /** The chat or mail behind the notification [key]; [fallback] (a target) when that notification is gone. */
    fun viaNotice(key: String, fallback: String): String = if (key.isBlank()) fallback else "note:$fallback\n$key"
    /** The two parts of a "note:" target: what to open when the notification is gone, and the notification's key. */
    fun noticeParts(target: String): Pair<String, String> = target.removePrefix("note:").let { it.substringBefore('\n') to it.substringAfter('\n', "") }

    /** What the button or the hint says: "Gmail", "WhatsApp", "the link". "" when the target is Seyal itself. */
    fun label(target: String): String = when {
        target.startsWith("note:") -> label(noticeParts(target).first).ifBlank { "the message" }
        target.startsWith("app:") -> target.removePrefix("app:")
        target.startsWith("mail:") -> "your mail"
        target.startsWith("url:") -> runCatching { java.net.URI(target.removePrefix("url:")).host.removePrefix("www.") }.getOrNull() ?: "the link"
        target.startsWith("pkg:") -> "the app"
        else -> ""
    }
}
