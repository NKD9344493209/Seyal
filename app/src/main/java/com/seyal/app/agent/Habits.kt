package com.seyal.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Notices what the user does again and again, and offers to do it for them: Do Not Disturb at
 * about the same time every night, the same request to Seyal every morning, the same app after
 * the headphones go in. One offer a day at most, one tap to accept, and a "no" is remembered for good.
 * Only what happened and when is kept (on the phone); nothing else is watched.
 */
object Habits {
    class Event(val key: String, val at: Long)
    /** An offer: [key] names the habit; [title] and [text] are shown; [skill] is what gets created on "Yes". */
    class Offer(val key: String, val title: String, val text: String, val skill: JSONObject)

    private var file: File? = null
    private val events = ArrayList<Event>()
    /** Changes Seyal made itself are not the user's habits: ignored until this time. */
    @Volatile private var ownUntil = 0L

    @Synchronized fun init(dir: File) {
        file = File(dir, "seyal/habits.json"); events.clear()
        runCatching { val a = JSONArray(file!!.readText()); for (i in 0 until a.length()) a.getJSONObject(i).let { events += Event(it.optString("k"), it.optLong("t")) } }
    }
    private fun save() { val f = file ?: return; runCatching { f.parentFile?.mkdirs(); f.writeText(JSONArray(events.map { JSONObject().put("k", it.key).put("t", it.at) }).toString()) } }
    @Synchronized fun clear() { events.clear(); save() }

    /** Seyal is about to change a setting itself: what follows in the next seconds is not the user's doing. */
    fun own(now: Long = System.currentTimeMillis()) { ownUntil = now + 8_000 }

    /**
     * Something the user did: "dnd:on", "ringer:silent", "cmd:<their request>", "headset>Spotify".
     * The same thing twice within ten minutes is one event.
     */
    @Synchronized fun log(key: String, now: Long) {
        if (now < ownUntil || key.length > 120) return
        if (events.asReversed().take(6).any { it.key == key && kotlin.math.abs(now - it.at) < 10 * 60_000L }) return
        events += Event(key, now)
        val cutoff = now - 21L * 24 * 3600_000L
        events.removeAll { it.at < cutoff }
        while (events.size > 1500) events.removeAt(0)
        save()
    }

    private fun zone() = ZoneId.systemDefault()
    private fun minuteOfDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone()).let { it.hour * 60 + it.minute }
    private fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate()
    private fun clock(min: Int): String { val h = min / 60; val m = min % 60; return "${if (h % 12 == 0) 12 else h % 12}:${m.toString().padStart(2, '0')} ${if (h < 12) "AM" else "PM"}" }
    private fun hhmm(min: Int) = "${(min / 60).toString().padStart(2, '0')}:${(min % 60).toString().padStart(2, '0')}"

    /** The middle of a set of clock times, treating the day as a circle (11:50 PM and 12:10 AM are close). */
    internal fun typicalMinute(mins: List<Int>): Pair<Int, Int> {
        // Shift so that late-night times don't straddle midnight, then take the median and the widest gap from it.
        val shift = if (mins.count { it < 180 } > 0 && mins.count { it > 1260 } > 0) 720 else 0
        val s = mins.map { (it + shift) % 1440 }.sorted()
        val mid = s[s.size / 2]
        return ((mid - shift + 1440) % 1440) to s.maxOf { kotlin.math.abs(it - mid) }
    }

    private fun skill(name: String, goal: String, time: String, steps: List<Pair<String, JSONObject>> = emptyList(), event: String = ""): JSONObject =
        JSONObject().put("name", name).put("goal", goal).put("time", time).put("event", event)
            .put("steps", JSONArray(steps.map { JSONObject().put("name", it.first).put("args", it.second) }))

    /**
     * The best offer to make now, or null. A habit needs to show on at least [minDays] different days of
     * the last ten, at about the same time (within 45 minutes). [declined]: keys the user said no to.
     * [existing]: names of skills that already exist (nothing is offered twice).
     */
    @Synchronized fun offer(now: Long, declined: Set<String>, existing: Set<String>, minDays: Int = 4): Offer? {
        val since = now - 10L * 24 * 3600_000L
        val recent = events.filter { it.at >= since }
        val offers = mutableListOf<Pair<Int, Offer>>()
        recent.groupBy { it.key }.forEach { (key, list) ->
            if (key in declined) return@forEach
            val days = list.map { dayOf(it.at) }.distinct().size
            // One time per day (the first), so a busy day doesn't blur the pattern.
            val perDay = list.groupBy { dayOf(it.at) }.map { (_, v) -> minuteOfDay(v.minOf { it.at }) }
            when {
                key.startsWith("headset>") -> if (list.size >= 3 && days >= 3) {
                    val app = key.removePrefix("headset>")
                    val name = "Headphones: open $app"
                    if (name !in existing) offers += days to Offer(key, "Open $app when headphones connect?", "You opened $app right after connecting your headphones on $days days. I can open it for you each time.",
                        skill(name, "Open $app.", "", listOf("open_app" to JSONObject().put("name", app)), event = "headphones_in"))
                }
                key == "dnd:on" || key == "dnd:off" || key.startsWith("ringer:") -> if (days >= minDays) {
                    val (typical, spread) = typicalMinute(perDay)
                    if (spread <= 45) {
                        val what = when (key) { "dnd:on" -> "turn on Do Not Disturb"; "dnd:off" -> "turn off Do Not Disturb"; else -> "put the phone on ${key.removePrefix("ringer:")}" }
                        val name = what.replaceFirstChar { it.uppercase() } + " at " + clock(typical)
                        val step = if (key.startsWith("dnd")) JSONObject().put("name", "dnd").put("value", key.removePrefix("dnd:")) else JSONObject().put("name", "ringer").put("value", key.removePrefix("ringer:"))
                        if (existing.none { it.startsWith(what.replaceFirstChar { c -> c.uppercase() }) }) offers += days to Offer(key, "Do this for you every day?", "You $what around ${clock(typical)} most days ($days of the last 10). Shall I do it for you at that time?",
                            skill(name, what.replaceFirstChar { it.uppercase() } + ".", hhmm(typical), listOf("set_setting" to step)))
                    }
                }
                key.startsWith("cmd:") -> if (days >= 3) {
                    val (typical, spread) = typicalMinute(perDay)
                    val said = key.removePrefix("cmd:")
                    if (spread <= 60 && said.length in 6..90) {
                        val name = said.replaceFirstChar { it.uppercase() }.take(40)
                        if (name !in existing) offers += days to Offer(key, "Make this automatic?", "You asked \"$said\" around ${clock(typical)} on $days days. I can do it by myself at that time every day and show you the result.",
                            skill(name, said.replaceFirstChar { it.uppercase() }, hhmm(typical)))
                    }
                }
            }
        }
        return offers.maxByOrNull { it.first }?.second
    }

    /** Requests worth learning as a possible daily habit: a question or a look-up, not a one-off action on something. */
    fun worthLogging(said: String): Boolean {
        val t = said.trim()
        if (t.length !in 6..90 || t.contains('\n')) return false
        if (Regex("\\b(it|this|that|him|her|them|yes|no|ok|continue|book|order|buy|send|call|message|whatsapp|email|delete|remind|alarm|timer|open|play)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return false
        return Regex("\\b(news|weather|summary|brief|deadlines?|bills?|orders?|sales|spend|spent|collection|score|price|rate|status|plan|notifications?|headlines?|updates?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)
    }
}
