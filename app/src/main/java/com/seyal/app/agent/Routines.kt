package com.seyal.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Seyal's memory for how things were done. When a request finishes well, the steps that did it are
 * kept. The same request again is then done straight from those steps, with no AI at all (instant, and
 * it costs nothing from the day's AI limit); a similar request gets the earlier steps as a head start,
 * so the AI does it in one go instead of feeling its way again.
 */
object Routines {
    class Step(val name: String, val args: JSONObject)
    class Routine(val key: String, val said: String, val steps: List<Step>, val replayable: Boolean, var uses: Int = 0, var at: Long = 0L) {
        fun toJson(): JSONObject = JSONObject().put("key", key).put("said", said).put("replayable", replayable).put("uses", uses).put("at", at)
            .put("steps", JSONArray(steps.map { JSONObject().put("name", it.name).put("args", it.args) }))
    }

    private const val MAX = 80
    private var file: File? = null
    private val all = LinkedHashMap<String, Routine>()

    @Synchronized fun init(dir: File) {
        file = File(dir, "seyal/routines.json")
        all.clear()
        runCatching {
            val arr = JSONArray(file!!.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val steps = o.getJSONArray("steps").let { s -> (0 until s.length()).map { Step(s.getJSONObject(it).getString("name"), s.getJSONObject(it).optJSONObject("args") ?: JSONObject()) } }
                all[o.getString("key")] = Routine(o.getString("key"), o.optString("said"), steps, o.optBoolean("replayable"), o.optInt("uses"), o.optLong("at"))
            }
        }
    }

    private fun save() { val f = file ?: return; runCatching { f.parentFile?.mkdirs(); f.writeText(JSONArray(all.values.map { it.toJson() }).toString()) } }

    @Synchronized fun clear() { all.clear(); save() }
    @Synchronized fun count(): Int = all.size
    @Synchronized fun instant(): Int = all.values.count { it.replayable }

    private val filler = Regex("\\b(please|pls|plz|kindly|can you|could you|will you|seyal|hey|hi|bro|da|pa|now|for me|just)\\b", RegexOption.IGNORE_CASE)

    /** The request with case, punctuation and politeness taken out, so "Torch on please!" and "torch on" are one. */
    fun normalise(said: String): String = filler.replace(said.lowercase(), " ").replace(Regex("[^\\p{L}\\p{N}@:.₹ ]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')

    /** Words that point at something said earlier or at "now": such a request means something else next time. */
    private val dependsOnContext = Regex("\\b(it|this|that|these|those|him|her|them|same|again|above|previous|last one|continue|retry|yes|no|ok)\\b", RegexOption.IGNORE_CASE)
    private val dependsOnDay = Regex("\\b(today|tonight|tomorrow|yesterday|day after|next|this (week|month|mon|tue|wed|thu|fri|sat|sun)\\w*|monday|tuesday|wednesday|thursday|friday|saturday|sunday|naalai|naalaikku|inniku|indru|netru)\\b|நாளை|இன்று", RegexOption.IGNORE_CASE)
    private val dated = Regex("\\d{4}-\\d{2}-\\d{2}")

    /** Tools whose answer is different every time, or that only make sense with the AI reading the result. */
    private val readsTheWorld = setOf(
        "web_search", "read_page", "news", "weather", "wikipedia", "read_mail", "notifications_read", "location", "phone_state", "storage_info",
        "files_find", "calendar_read", "calendar_list", "screen_read", "screenshot", "apps_list", "skill_list", "inbox_list", "watch_list", "daily_brief",
    )
    private val neverKept = setOf("ask_user", "delegate", "memory_save", "memory_forget", "skill_save", "skill_save_this", "skill_delete", "lesson_save", "order_check", "seats_check", "watch_add", "watch_remove")

    /**
     * Whether the steps can be repeated blindly for the same words: every step is a plain action with
     * fixed values (nothing read from the screen or the web, no day-relative value, nothing the user was asked).
     */
    fun canReplay(said: String, steps: List<Step>, wantsAnswer: Boolean): Boolean =
        steps.isNotEmpty() && steps.size <= 6 && !wantsAnswer && !dependsOnContext.containsMatchIn(said) && !dependsOnDay.containsMatchIn(said) &&
            steps.none { it.name in readsTheWorld || it.name in neverKept || it.name.startsWith("screen_") || dated.containsMatchIn(it.args.toString()) }

    /** A request finished well: keep how it was done. [allOk]: every step worked and the user refused none. */
    @Synchronized fun learn(said: String, steps: List<Step>, allOk: Boolean, wantsAnswer: Boolean, now: Long) {
        val key = normalise(said)
        if (!allOk || key.length < 4 || key.length > 160 || steps.isEmpty() || steps.size > 25) return
        if (dependsOnContext.containsMatchIn(said)) return
        // Questions to the user and bookkeeping are not part of "how it was done".
        val kept = steps.filter { it.name !in neverKept }
        if (kept.isEmpty()) return
        // Repeating blindly needs every step whole: nothing dropped, nothing cut short.
        val whole = kept.size == steps.size && steps.all { it.args.toString().length <= 600 }
        val old = all.remove(key)
        all[key] = Routine(key, said.trim().take(160), kept.map { Step(it.name, slim(it.args)) }, whole && canReplay(said, steps, wantsAnswer), (old?.uses ?: 0) + 1, now)
        while (all.size > MAX) all.remove(all.entries.minByOrNull { it.value.at }!!.key)
        save()
    }

    /** A routine that failed when repeated is no longer trusted to run by itself. */
    @Synchronized fun distrust(said: String) { val key = normalise(said); val r = all[key] ?: return; all[key] = Routine(r.key, r.said, r.steps, false, r.uses, r.at); save() }

    /** The steps to repeat without the AI for exactly this request, or null. */
    @Synchronized fun exact(said: String): Routine? = all[normalise(said)]?.takeIf { it.replayable }

    private val small = setOf("and", "the", "to", "in", "on", "for", "me", "my", "of", "at", "is", "it", "an", "from", "with", "ku", "la", "oru")
    private fun words(key: String): List<String> = key.split(' ').filter { it.length >= 2 && it !in small }.distinct()

    /**
     * The most similar earlier request, for a head start: at least [min] of the shorter one's words in
     * common, and either two words shared or the same opening verb ("order …", "book …").
     */
    @Synchronized fun similar(said: String, min: Double = 0.5): Routine? {
        val key = normalise(said); val w = words(key)
        all[key]?.let { return it }
        if (w.isEmpty()) return null
        return all.values.mapNotNull { r ->
            val o = words(r.key); if (o.isEmpty()) return@mapNotNull null
            val both = w.count { it in o }
            val score = both.toDouble() / minOf(w.size, o.size)
            if (score >= min && (both >= 2 || (both == 1 && w.first() == o.first() && minOf(w.size, o.size) <= 2))) r to score + both / 100.0 else null
        }.maxByOrNull { it.second }?.first
    }

    /** The head start shown to the AI. */
    fun hint(r: Routine): String = buildString {
        appendLine("YOU DID THIS BEFORE. For \"${r.said}\" these steps worked:")
        r.steps.take(14).forEachIndexed { i, s -> appendLine("${i + 1}. ${s.name}(${s.args.toString().take(140)})") }
        if (r.steps.size > 14) appendLine("… and ${r.steps.size - 14} more.")
        append("If the request now is the same kind, go the same way with the new details, in as few replies as you can (several calls in one reply). Element ids from then are gone: use labels. If the screen differs, look and adapt.")
    }

    /** Long values (a whole document, a long message) are cut: the head start only needs the shape. */
    private fun slim(args: JSONObject): JSONObject {
        if (args.toString().length <= 600) return args
        val o = JSONObject()
        args.keys().forEach { k -> val v = args.opt(k); o.put(k, if (v is String && v.length > 200) v.take(200) + "…" else if (v is JSONArray && v.toString().length > 300) "(${v.length()} items)" else v) }
        return o
    }
}
