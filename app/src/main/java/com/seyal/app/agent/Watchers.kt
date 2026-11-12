package com.seyal.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One thing Seyal keeps checking on a web page so the user doesn't have to. */
class Watcher(
    val id: String,
    /** In the user's words: "Leo bookings at Broadway". */
    val what: String,
    val url: String,
    /** contains | gone | price_below | changed */
    val mode: String,
    /** The words to look for, or the price limit. */
    val value: String,
    /** Words on the page just before the part that matters (the product's name before its price). */
    val near: String = "",
    val everyMin: Int = 60,
    var enabled: Boolean = true,
    var lastCheck: Long = 0L,
    /** What was seen last time (a price, "present"/"absent", or a fingerprint of the watched part). */
    var lastSeen: String = "",
    var hitAt: Long = 0L,
    var fails: Int = 0,
    val created: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("what", what).put("url", url).put("mode", mode).put("value", value).put("near", near)
        .put("every", everyMin).put("enabled", enabled).put("lastCheck", lastCheck).put("lastSeen", lastSeen).put("hitAt", hitAt).put("fails", fails).put("created", created)

    /** "when the page says “Book tickets”" */
    fun rule(): String = when (mode) {
        "contains" -> "when the page says \"$value\""
        "gone" -> "when \"$value\" is no longer on the page"
        "price_below" -> "when the price is ₹$value or less"
        else -> "when the page changes" + (if (near.isNotBlank()) " near \"$near\"" else "")
    }

    companion object {
        fun fromJson(o: JSONObject) = Watcher(
            o.getString("id"), o.optString("what"), o.optString("url"), o.optString("mode"), o.optString("value"), o.optString("near"),
            o.optInt("every", 60), o.optBoolean("enabled", true), o.optLong("lastCheck"), o.optString("lastSeen"), o.optLong("hitAt"), o.optInt("fails"), o.optLong("created"),
        )
    }
}

/**
 * Watching web pages: bookings opening, a price dropping, results appearing. The check is a plain
 * page fetch and a text comparison (no AI), so it can run every half hour all day at no cost.
 */
object Watchers {
    const val MAX = 12
    const val MIN_EVERY = 15
    /** After this many failed reads in a row the user is told and the watcher stops. */
    const val MAX_FAILS = 8

    class Eval(val hit: Boolean, val seen: String, val note: String)

    private val price = Regex("(?:₹|\\brs\\.?|\\binr)\\s?([\\d,]{2,9})(?:\\.\\d{1,2})?", RegexOption.IGNORE_CASE)

    /** The first price written after [near] on the page (or the first on the page), in rupees. */
    fun priceIn(page: String, near: String): Int? {
        val from = if (near.isBlank()) 0 else page.indexOf(near, ignoreCase = true).let { if (it < 0) return null else it }
        return price.find(page, from)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
    }

    private fun squash(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** The part of the page a "changed" watcher compares: 500 characters after [near], or the whole text. Numbers that tick (times, counters) are ignored. */
    internal fun fingerprint(page: String, near: String): String {
        val text = squash(page)
        val part = if (near.isBlank()) text else text.indexOf(near, ignoreCase = true).let { if (it < 0) "" else text.substring(it, minOf(text.length, it + 500)) }
        return part.replace(Regex("\\d{1,2}:\\d{2}(:\\d{2})?\\s?(am|pm)?", RegexOption.IGNORE_CASE), "").replace(Regex("\\d+ (seconds?|minutes?|mins?|hours?|views?) ago"), "").hashCode().toString()
    }

    /** Looks at the page text for what [w] waits for. Pure: no network, no clock. */
    fun evaluate(w: Watcher, pageText: String): Eval {
        val page = squash(pageText)
        return when (w.mode) {
            "contains" -> page.contains(w.value, ignoreCase = true).let { Eval(it, if (it) "present" else "absent", if (it) "\"${w.value}\" is on the page now." else "\"${w.value}\" is not on the page yet.") }
            "gone" -> (!page.contains(w.value, ignoreCase = true)).let { Eval(it, if (it) "absent" else "present", if (it) "\"${w.value}\" is no longer on the page." else "\"${w.value}\" is still on the page.") }
            "price_below" -> {
                val p = priceIn(page, w.near); val limit = w.value.filter { it.isDigit() }.toIntOrNull() ?: 0
                if (p == null) Eval(false, "", "No price was found on the page" + (if (w.near.isNotBlank()) " after \"${w.near}\"." else "."))
                else Eval(p <= limit, p.toString(), if (p <= limit) "The price is ₹$p now (your limit: ₹$limit)." else "The price is ₹$p (waiting for ₹$limit or less).")
            }
            else -> {
                val f = fingerprint(page, w.near)
                if (w.near.isNotBlank() && !page.contains(w.near, ignoreCase = true)) Eval(w.lastSeen.isNotBlank() && w.lastSeen != "missing", "missing", "\"${w.near}\" is not on the page.")
                else Eval(w.lastSeen.isNotBlank() && w.lastSeen != f, f, if (w.lastSeen.isBlank()) "Noted how the page looks now." else if (w.lastSeen != f) "The page has changed." else "No change.")
            }
        }
    }

    /** A page that came back nearly empty was drawn by scripts or wants a login: it can't be watched this way. */
    fun readable(pageText: String): Boolean = squash(pageText).length >= 200

    // ---- the list

    private var file: File? = null
    private val list = mutableListOf<Watcher>()
    @Volatile var onChange: () -> Unit = {}

    @Synchronized fun init(dir: File) {
        file = File(dir, "seyal/watchers.json"); list.clear()
        runCatching { val a = JSONArray(file!!.readText()); for (i in 0 until a.length()) list += Watcher.fromJson(a.getJSONObject(i)) }
    }
    private fun save() { val f = file ?: return; runCatching { f.parentFile?.mkdirs(); f.writeText(JSONArray(list.map { it.toJson() }).toString()) } }

    @Synchronized fun all(): List<Watcher> = list.toList()
    @Synchronized fun active(): List<Watcher> = list.filter { it.enabled }
    @Synchronized fun add(w: Watcher) { list += w; save(); onChange() }
    @Synchronized fun remove(idOrWhat: String): Watcher? {
        val w = list.firstOrNull { it.id == idOrWhat } ?: list.firstOrNull { it.what.contains(idOrWhat, true) } ?: return null
        list.remove(w); save(); onChange(); return w
    }
    @Synchronized fun clear() { list.clear(); save(); onChange() }
    @Synchronized fun touch() { save(); onChange() }

    /** When the next check is due (epoch ms), or null when nothing is being watched. */
    @Synchronized fun nextCheck(now: Long): Long? = list.filter { it.enabled }.minOfOrNull { maxOf(now + 5_000, it.lastCheck + it.everyMin * 60_000L) }

    /** The watchers whose turn it is. */
    @Synchronized fun dueNow(now: Long): List<Watcher> = list.filter { it.enabled && it.lastCheck + it.everyMin * 60_000L <= now + 30_000 }

    /**
     * Records one check of [w]. Returns what to tell the user: a hit ("It's here"), a give-up after
     * too many failed reads, or null (nothing to say). One-shot watchers stop after their hit.
     */
    @Synchronized fun record(w: Watcher, pageText: String?, now: Long): String? {
        w.lastCheck = now
        if (pageText == null || !readable(pageText)) {
            w.fails++
            // No answer at all is usually the network (or the phone dozing): be far more patient with that than with an empty page.
            val giveUp = w.fails >= (if (pageText == null) MAX_FAILS * 6 else MAX_FAILS)
            if (giveUp) w.enabled = false
            save(); onChange()
            return if (giveUp) "I can no longer read the page for \"${w.what}\", so I stopped watching it. Open it yourself: ${w.url}" else null
        }
        w.fails = 0
        val e = evaluate(w, pageText)
        w.lastSeen = e.seen
        if (e.hit) { w.hitAt = now; if (w.mode != "changed") w.enabled = false }
        save(); onChange()
        return if (e.hit) "${w.what}: ${e.note}" else null
    }
}
