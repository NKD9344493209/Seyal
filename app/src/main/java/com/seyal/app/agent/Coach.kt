package com.seyal.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One sitting of a plan: "Day A: Push" with its exercises, or "Unit 2" with its topics. */
class PlanSession(val title: String, val items: List<String>, val minutes: Int) {
    fun toJson(): JSONObject = JSONObject().put("title", title).put("items", JSONArray(items)).put("minutes", minutes)
    companion object {
        fun fromJson(o: JSONObject) = PlanSession(o.optString("title"), o.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty(), o.optInt("minutes"))
    }
}

/**
 * A plan the user asked for (workout, study, diet, a habit) and, more to the point, the record of
 * whether it is being done. Sessions repeat in order; a skipped day does not skip its session.
 */
class CoachPlan(
    val id: String,
    val title: String,
    /** workout | study | diet | habit | other */
    val kind: String,
    val goal: String,
    val sessions: List<PlanSession>,
    /** Weekdays it runs on: 1 = Monday … 7 = Sunday. */
    val days: Set<Int>,
    /** "HH:mm" */
    val time: String,
    val created: Long,
    /** Sessions completed so far (decides which one is next). */
    var doneCount: Int = 0,
    /** yyyy-MM-dd → done | skip | missed */
    val log: MutableMap<String, String> = mutableMapOf(),
    var skipsInRow: Int = 0,
    /** Lighter sessions for now (after too many skips): half the items. */
    var easy: Boolean = false,
    var paused: Boolean = false,
    var snoozeUntil: Long = 0L,
    /** The day (yyyy-MM-dd) the user was last nudged. */
    var nudgedDay: String = "",
    /** What is tracked beside done/skipped ("weight kg", "pages"), with its entries (day → value). */
    val measure: String = "",
    val measures: MutableList<Pair<String, Double>> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("kind", kind).put("goal", goal).put("sessions", JSONArray(sessions.map { it.toJson() }))
        .put("days", JSONArray(days.sorted())).put("time", time).put("created", created).put("done", doneCount).put("log", JSONObject(log as Map<*, *>)).put("skips", skipsInRow)
        .put("easy", easy).put("paused", paused).put("snooze", snoozeUntil).put("nudged", nudgedDay).put("measure", measure)
        .put("measures", JSONArray(measures.map { JSONArray().put(it.first).put(it.second) }))

    /** The session that is next. */
    fun next(): PlanSession = sessions[doneCount % sessions.size]
    /** What to do in that session right now (half of it in easy mode). */
    fun todaysItems(): List<String> = next().items.let { if (easy) it.take((it.size + 1) / 2) else it }
    fun todaysMinutes(): Int = next().minutes.let { if (easy) maxOf(5, it / 2) else it }

    companion object {
        fun fromJson(o: JSONObject): CoachPlan {
            val log = mutableMapOf<String, String>(); o.optJSONObject("log")?.let { l -> l.keys().forEach { log[it] = l.optString(it) } }
            val ms = mutableListOf<Pair<String, Double>>(); o.optJSONArray("measures")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { ms += it.optString(0) to it.optDouble(1) } }
            return CoachPlan(
                o.getString("id"), o.optString("title"), o.optString("kind"), o.optString("goal"),
                o.optJSONArray("sessions")?.let { a -> (0 until a.length()).map { PlanSession.fromJson(a.getJSONObject(it)) } }.orEmpty(),
                o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() } ?: (1..7).toSet(),
                o.optString("time", "07:00"), o.optLong("created"), o.optInt("done"), log, o.optInt("skips"), o.optBoolean("easy"), o.optBoolean("paused"), o.optLong("snooze"), o.optString("nudged"), o.optString("measure"), ms,
            )
        }
    }
}

/**
 * The plan coach. Making a plan is the easy part; this is about it getting done: one small piece a
 * day at the time the user chose, one tap to answer (Done / Skip / Later), a smaller ask after a skip,
 * and an offer to lighten it after three. Reminders and tracking use no AI.
 */
object Coach {
    private var file: File? = null
    private val plans = mutableListOf<CoachPlan>()
    @Volatile var onChange: () -> Unit = {}
    private fun zone() = ZoneId.systemDefault()
    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate()
    private fun at(d: LocalDate, hhmm: String): Long = d.atTime(runCatching { LocalTime.parse(hhmm) }.getOrDefault(LocalTime.of(7, 0))).atZone(zone()).toInstant().toEpochMilli()

    @Synchronized fun init(dir: File) {
        file = File(dir, "seyal/plans.json"); plans.clear()
        runCatching { val a = JSONArray(file!!.readText()); for (i in 0 until a.length()) plans += CoachPlan.fromJson(a.getJSONObject(i)) }
    }
    private fun save() { val f = file ?: return; runCatching { f.parentFile?.mkdirs(); f.writeText(JSONArray(plans.map { it.toJson() }).toString()) } }
    @Synchronized fun all(): List<CoachPlan> = plans.toList()
    @Synchronized fun clear() { plans.clear(); save(); onChange() }
    @Synchronized fun add(p: CoachPlan) { plans.removeAll { it.title.equals(p.title, true) }; plans += p; save(); onChange() }
    /** By id, or by a few words of the title; the only plan when there is just one. */
    @Synchronized fun find(idOrTitle: String): CoachPlan? {
        val q = idOrTitle.trim()
        return plans.firstOrNull { it.id == q } ?: plans.firstOrNull { q.isNotBlank() && (it.title.contains(q, true) || it.kind.equals(q, true)) } ?: plans.singleOrNull()
    }
    @Synchronized fun remove(id: String): Boolean = plans.removeAll { it.id == id }.also { if (it) { save(); onChange() } }
    @Synchronized fun touch() { save(); onChange() }

    // ---- what the request is, and what must be asked first

    private val topic = "(workout|work out|fitness|gym|exercise|training|weight ?loss|fat ?loss|weight ?gain|muscle|bulking|abs|diet|meal|nutrition|study|exam|revision|reading|learning|practice|habit|sleep|meditation|yoga|running|walking|jogging|coding|dsa|leetcode|placement|interview prep|udarpayirchi)"
    private val shape = "(plan|routine|schedule|timetable|time table|program|programme|challenge|tracker|chart)"
    private val wantsRx = Regex("\\b$topic\\b.{0,40}\\b$shape\\b|\\b$shape\\b.{0,30}\\b(for|to|on)\\b.{0,40}\\b$topic\\b", RegexOption.IGNORE_CASE)
    private val askFor = Regex("\\b(make|create|build|give|prepare|need|want|set ?up|start|design|suggest|write|help me|venum|podu|kodu|pannu)\\b", RegexOption.IGNORE_CASE)
    /** Asking about a plan, or asking for something else that only mentions one (a PPT about it, a reminder, sending it). */
    private val notAPlan = Regex(
        "^(what|how is|how's|show|list|did|is|are|where)\\b|\\b(my plans?|plan status|trip|travel|tour|itinerary|data plan|recharge plan|mobile plan|plan details|" +
            "ppt|powerpoint|pdf|presentation|slides?|document|word file|e-?mail|mail|send|share|forward|remind|reminder|alarm|whatsapp|message|download|search|find|book|order|buy|fees?)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** The kind of plan the user is asking Seyal to make (workout | study | diet | habit), or null. */
    fun wants(text: String): String? {
        val t = text.trim()
        if (t.length < 8 || notAPlan.containsMatchIn(t) || !wantsRx.containsMatchIn(t) || !askFor.containsMatchIn(t)) return null
        return when {
            Regex("diet|meal|nutrition", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "diet"
            Regex("study|exam|revision|reading|learning|coding|dsa|leetcode|placement|interview", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "study"
            Regex("habit|sleep|meditation", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "habit"
            else -> "workout"
        }
    }

    /** What has to come from the user before a plan of this kind can be any good. */
    fun details(kind: String): String = when (kind) {
        "study" -> "1) the subject or exam, and its date  2) the units or topics to cover  3) which days and how many hours a day  4) what time of day  5) what they find hardest"
        "diet" -> "1) the goal (lose weight / gain / eat healthier)  2) veg or non-veg, and anything they don't eat  3) how many meals a day and who cooks  4) height and weight (optional)  5) any health condition a doctor is treating"
        "habit" -> "1) exactly what they want to do  2) how often (which days)  3) what time  4) how many minutes at a time"
        else -> "1) the goal (lose fat / build muscle / stamina / stay fit)  2) equipment they have (none / dumbbells / gym)  3) how many days a week and how many minutes a day  4) what time of day  5) their level (beginner / some experience / regular)  6) height and weight (optional)  7) any injury or pain to avoid"
    }

    /** The question Seyal asks by itself when the AI can't be reached for the interview. */
    fun fallbackQuestion(kind: String): String = "To make a plan you will actually follow, tell me:\n" +
        details(kind).split(Regex("\\s{2}(?=\\d\\))")).joinToString("\n") + "\nAnswer in one or two lines. Skip anything you'd rather not say."

    fun system(kind: String): String = """
You are the first step of a phone assistant. The user wants a $kind plan. A plan made without knowing the person is useless, so first find out what you need.

NEEDED: ${details(kind)}

Check NEEDED against what the user has already said in their latest request.
- If anything is missing: call ask_user ONCE, listing every missing point as a numbered line with two or three example answers, ending with "Answer in one or two lines. Skip anything you'd rather not say." Do not ask for what they already told you. Leave options empty.
- If nothing is missing: call details_complete.
Write in the language the user wrote in.
    """.trimIndent()

    /** How the plan is then made (added to the AI's instructions while a plan is being built). */
    const val GUIDE = """PLAN COACH (the user asked for a plan):
- Build it from their answers and SAVE it with plan_create: 3 to 7 sessions that repeat in order, each with a short title, 3 to 8 concrete items (exercise with sets x reps or minutes; chapter with pages; meal with portions) and its minutes. Days and time as they said.
- Start easy: the first sessions must feel doable for their level, and fit the minutes they gave. Use only the equipment they have.
- This is general guidance, not medical advice. If they mention a health condition, pregnancy, chest pain or an injury: keep it gentle, avoid what could hurt, and tell them to check with a doctor first.
- After saving: show today's session in a few lines, say when the first reminder comes, and that each reminder has Done / Skip / Later. Offer the whole plan as a PDF (doc_create) only if they want it.
- Later: "done" / "skipped today" → plan_mark. "Too hard", "change the time", "pause it" → plan_change. Progress → plan_list."""

    // ---- doing it

    /** A scheduled day went by with no answer: that is a miss, and it counts towards "lighten it". */
    private fun rollover(p: CoachPlan, today: LocalDate) {
        val y = today.minusDays(1)
        if (!p.paused && y.dayOfWeek.value in p.days && p.log[y.toString()] == null && day(p.created).isBefore(today) && p.nudgedDay == y.toString()) {
            p.log[y.toString()] = "missed"; p.skipsInRow++
        }
    }

    private fun dueToday(p: CoachPlan, today: LocalDate): Boolean = !p.paused && p.sessions.isNotEmpty() && today.dayOfWeek.value in p.days && p.log[today.toString()] == null

    /** The plans to nudge now: today is one of their days, their time has come, and they were not nudged yet (or a "Later" has run out). */
    @Synchronized fun dueNudges(now: Long): List<CoachPlan> {
        val today = day(now)
        var changed = false
        val due = plans.filter { p ->
            val before = p.skipsInRow; rollover(p, today); if (p.skipsInRow != before) changed = true
            dueToday(p, today) && ((p.nudgedDay != today.toString() && now >= at(today, p.time)) || (p.snoozeUntil in 1..now))
        }
        if (changed) save()
        return due
    }

    /** Records that [p] was nudged now. */
    @Synchronized fun nudged(p: CoachPlan, now: Long) { p.nudgedDay = day(now).toString(); p.snoozeUntil = 0; save() }

    /** When the next nudge of any plan is due (epoch ms), or null. */
    @Synchronized fun nextNudge(now: Long): Long? {
        val today = day(now)
        return plans.filter { !it.paused && it.sessions.isNotEmpty() }.mapNotNull { p ->
            if (p.snoozeUntil > now && p.log[today.toString()] == null) return@mapNotNull p.snoozeUntil
            (0..7L).map { today.plusDays(it) }.firstOrNull { d -> d.dayOfWeek.value in p.days && p.log[d.toString()] == null && !(d == today && p.nudgedDay == today.toString()) && at(d, p.time) > now - 60_000 }?.let { maxOf(at(it, p.time), now + 5_000) }
        }.minOrNull()
    }

    /** Scheduled days done in a row up to today (a day not yet answered today does not break it). */
    fun streak(p: CoachPlan, today: LocalDate): Int {
        var n = 0; var d = today
        repeat(400) {
            if (d.dayOfWeek.value in p.days) {
                when (p.log[d.toString()]) { "done" -> n++; null -> if (d != today) return n; else -> return n }
            }
            d = d.minusDays(1)
            if (d.isBefore(day(p.created))) return n
        }
        return n
    }

    /** Of the scheduled days in the last 7, how many were done (as "4 of 5"). */
    fun week(p: CoachPlan, today: LocalDate): Pair<Int, Int> {
        val days = (0..6L).map { today.minusDays(it) }.filter { it.dayOfWeek.value in p.days && !it.isBefore(day(p.created)) }
        return days.count { p.log[it.toString()] == "done" } to days.size
    }

    /** The reminder's title and text. After a skip the ask is smaller: just the first item, five minutes. */
    fun nudgeText(p: CoachPlan): Pair<String, String> {
        val s = p.next(); val items = p.todaysItems()
        val small = p.skipsInRow in 1..2 && items.isNotEmpty()
        val title = if (p.skipsInRow >= 3) "${p.title}: it has slipped ${p.skipsInRow} times" else if (small) "${p.title}: just 5 minutes today" else "${p.title}: ${s.title} (${p.todaysMinutes()} min)"
        val body = when {
            p.skipsInRow >= 3 -> "No guilt. Shall I make it easier, or pause it for now? Or do today's: ${s.title}."
            small -> "Only the first one: ${items.first()}. That counts. Do more if you feel like it."
            else -> items.joinToString("\n") { "• $it" }
        }
        return title to body
    }

    /** The user's answer to a nudge (or said in chat). Returns what to say back. */
    @Synchronized fun mark(p: CoachPlan, state: String, now: Long): String {
        val today = day(now)
        return when (state) {
            "done" -> {
                if (p.log[today.toString()] == "done") return "Already marked done today. Streak: ${streak(p, today)}."
                p.log[today.toString()] = "done"; p.doneCount++; p.snoozeUntil = 0
                val wasEasy = p.easy
                // Back on track for three sessions: the lighter version has done its job.
                if (p.easy && p.skipsInRow == 0 && (0..6L).count { p.log[today.minusDays(it).toString()] == "done" } >= 3) p.easy = false
                p.skipsInRow = 0
                save(); onChange()
                val st = streak(p, today)
                "Done: ${p.title}. " + (if (st > 1) "$st in a row. " else "") + "Next: ${p.next().title}." + (if (wasEasy && !p.easy) " You're back on track, so the full sessions return." else "")
            }
            "skip" -> {
                if (p.log[today.toString()] == null) { p.log[today.toString()] = "skip"; p.skipsInRow++ }
                p.snoozeUntil = 0; save(); onChange()
                if (p.skipsInRow >= 3) "Skipped. That's ${p.skipsInRow} in a row, so let's change something: say \"make it easier\", \"change the time\" or \"pause it\"."
                else "Skipped for today. Tomorrow I'll ask for just 5 minutes."
            }
            "later" -> {
                val until = now + 60 * 60_000L
                if (day(until) != today || Instant.ofEpochMilli(until).atZone(zone()).toLocalTime().isAfter(LocalTime.of(22, 30))) return mark(p, "skip", now)
                p.snoozeUntil = until; save(); onChange()
                "I'll remind you again in an hour."
            }
            "easier" -> { p.easy = true; p.skipsInRow = 0; save(); onChange(); "Made it lighter: half of each session, about ${p.todaysMinutes()} minutes. It goes back to full once you've done three." }
            "pause" -> { p.paused = true; p.snoozeUntil = 0; save(); onChange(); "Paused \"${p.title}\". Say \"resume my ${p.kind} plan\" when you want it back." }
            "resume" -> { p.paused = false; p.skipsInRow = 0; save(); onChange(); "Resumed \"${p.title}\". Next: ${p.next().title}." }
            else -> "Say done, skip or later."
        }
    }

    @Synchronized fun measure(p: CoachPlan, value: Double, now: Long): String {
        val d = day(now).toString()
        p.measures.removeAll { it.first == d }; p.measures += d to value
        save(); onChange()
        val first = p.measures.first().second
        return "Noted ${trim(value)}" + (if (p.measure.isNotBlank()) " ${p.measure}" else "") + (if (p.measures.size > 1) " (started at ${trim(first)}, change ${if (value - first >= 0) "+" else ""}${trim(value - first)})." else ".")
    }
    private fun trim(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.ENGLISH, "%.1f", v)

    /** "Workout: Day B: Pull (25 min) at 6:30 PM · streak 4 · 4 of 5 this week" */
    fun status(p: CoachPlan, now: Long): String {
        val today = day(now)
        val (done, of) = week(p, today)
        val state = when { p.paused -> "paused"; p.log[today.toString()] == "done" -> "done today"; today.dayOfWeek.value in p.days -> "today at ${p.time}: ${p.next().title} (${p.todaysMinutes()} min)"; else -> "next: ${p.next().title}" }
        return "${p.title}: $state · streak ${streak(p, today)} · $done of $of this week" + (if (p.easy) " · lighter mode" else "") +
            (p.measures.lastOrNull()?.let { " · ${trim(it.second)} ${p.measure}".trimEnd() } ?: "")
    }

    /** Lines for the morning summary: what each plan asks today. */
    @Synchronized fun today(now: Long): List<String> {
        val today = day(now)
        return plans.filter { dueToday(it, today) }.map { "${it.title} at ${it.time}: ${it.next().title} (${it.todaysMinutes()} min)" }
    }

    /** "mon, wed, fri" / "daily" / "weekdays" → weekday numbers. */
    fun parseDays(raw: List<String>): Set<Int> {
        val text = raw.joinToString(" ").lowercase()
        if (text.isBlank() || Regex("\\b(daily|every ?day|all days|each day)\\b").containsMatchIn(text)) return (1..7).toSet()
        val out = mutableSetOf<Int>()
        if (Regex("\\bweekdays?\\b").containsMatchIn(text)) out += 1..5
        if (Regex("\\bweekends?\\b").containsMatchIn(text)) out += listOf(6, 7)
        listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun").forEachIndexed { i, d -> if (Regex("\\b$d").containsMatchIn(text)) out += i + 1 }
        return out.ifEmpty { (1..7).toSet() }
    }
}
