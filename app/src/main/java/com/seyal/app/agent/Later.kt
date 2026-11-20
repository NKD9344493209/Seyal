package com.seyal.app.agent

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A request for later: "give the news about gold at today 11:09 pm", "AI news tonight at 9", "tomorrow 8 am brief me",
 * "in 10 minutes give me the gold news", "every day at 7 pm gold news". The time is taken out and what is left is the
 * request itself, to be run then, not now.
 *
 * Seen on a real phone (19 Nov 2026, 11:08 PM): "give the news about gold trend at today 11:09 pm" was answered at
 * once, because the news shortcut never looked for a time.
 */
object Later {
    private val I = RegexOption.IGNORE_CASE

    /** [at]: the one time to run (null for a daily one); [daily]: the clock time of a daily one; [rest]: the request without its time. */
    class When(val at: LocalDateTime?, val daily: LocalTime?, val rest: String, val past: Boolean = false)

    // Android's pattern reader (ICU) takes "[:" … ":]" anywhere in a pattern as one named class and refuses the whole
    // pattern, where the laptop's Java does not. So ":" never stands first or last inside [ ] here. (3.27 and 3.28
    // broke every typed request on a phone because of exactly this.)
    private const val CLOCK = "(\\d{1,2})(?:[.:](\\d{2}))?\\s?(am|pm|a\\.m\\.?|p\\.m\\.?)|(\\d{1,2})[.:](\\d{2})"
    private const val DAY = "today|tonight|tomorrow|tmrw|tomorow|innaiku|inniku|naalaikku|naalaiku|nalaiku"
    private const val EVERY = "every ?day|daily|every morning|every evening|every night|dhinamum|daily once"

    /** "[every day|today|tomorrow] [at] 11:09 pm [today|tomorrow|every day]" anywhere in the request. */
    private val clockAt = Regex("(?:\\b($EVERY|$DAY)\\s+)?(?:\\b(?:at|by|@)\\s+)?(?:\\b($EVERY|$DAY)\\s+)?(?:\\b(?:at|by|@)\\s+)?\\b(?:$CLOCK)(?!\\d|:)(?:\\s+\\b($EVERY|$DAY)\\b)?", I)
    private val inMinutes = Regex("\\b(?:in|after)\\s+(\\d{1,3}|a|an|one|half an?)\\s*(min|mins|minutes?|hrs?|hours?)\\b", I)

    /** The patterns kept here, for the check that Android can read every one of them. */
    internal val patterns: List<Regex> get() = listOf(clockAt, inMinutes, timeIsTheThing, notRemind, doItYourself, toPeopleOrMoney, actionFirst, webSearch)

    /** Requests where the time belongs to the thing itself: an alarm, a reminder, a meeting, a booking, a show time. */
    private val timeIsTheThing = Regex(
        "\\b(alarm|remind|reminder|timer|wake|meeting|calendar|event|appointment|book|booking|ticket|tickets|movie|train|bus|flight|cab|taxi|auto|order|deliver|delivery|schedule|snooze)\\b",
        I,
    )

    private fun clockOf(m: MatchResult): LocalTime? {
        val g = m.groupValues
        val (hRaw, minRaw, ap) = if (g[3].isNotBlank()) Triple(g[3], g[4], g[5].lowercase().replace(".", "")) else Triple(g[6], g[7], "")
        var h = hRaw.toIntOrNull() ?: return null
        val min = minRaw.ifBlank { "0" }.toIntOrNull() ?: return null
        if (min !in 0..59) return null
        when (ap) { "pm" -> if (h in 1..11) h += 12; "am" -> if (h == 12) h = 0 }
        if (ap.isBlank() && g[3].isBlank() && g[1].isBlank() && g[2].isBlank() && g[8].isBlank()) {
            // "10:30" alone, with no "at", day or am/pm, may be a score or a ratio: only with "at" in front of it.
            if (!Regex("\\b(?:at|by|@)\\s+$", I).containsMatchIn(m.value.substringBefore(hRaw))) return null
        }
        return if (h in 0..23) LocalTime.of(h, min) else null
    }

    /** The time asked for and the request without it; null when the request names no later time. */
    fun find(text: String, now: LocalDateTime): When? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        // "…don't remind, you perform" asks for no reminder: the word "remind" there must not make it one.
        if (t.length < 6 || timeIsTheThing.containsMatchIn(notRemind.replace(t, " "))) return null
        inMinutes.find(t)?.let { m ->
            val n = when (m.groupValues[1].lowercase()) { "a", "an", "one" -> 1; "half a", "half an" -> 0; else -> m.groupValues[1].toInt() }
            val hours = m.groupValues[2].lowercase().startsWith("h")
            val minutes = if (m.groupValues[1].lowercase().startsWith("half")) 30L else if (hours) n * 60L else n.toLong()
            if (minutes !in 1..(24 * 60)) return null
            return When(now.plusMinutes(minutes).withSecond(0).withNano(0), null, cut(t, m.range))
        }
        val m = clockAt.find(t) ?: return null
        val clock = clockOf(m) ?: return null
        val words = listOf(m.groupValues[1], m.groupValues[2], m.groupValues[8]).joinToString(" ").lowercase()
        val rest = cut(t, m.range)
        if (Regex(EVERY, I).containsMatchIn(words)) return When(null, clock, rest)
        val tomorrow = Regex("tomorrow|tmrw|tomorow|naalaikku|naalaiku|nalaiku", I).containsMatchIn(words)
        val saidToday = Regex("today|tonight|innaiku|inniku", I).containsMatchIn(words)
        // "tonight at 9" is 9 PM.
        val c = if (words.contains("tonight") && clock.hour in 1..11) clock.plusHours(12) else clock
        var at = now.toLocalDate().atTime(c)
        if (tomorrow) at = at.plusDays(1)
        else if (!at.isAfter(now.plusSeconds(20))) {
            // "today 11:09 pm" when it is already 11:10: say so instead of guessing tomorrow.
            if (saidToday) return When(at, null, rest, past = true)
            at = at.plusDays(1)
        }
        return When(at, null, rest)
    }

    // ---- something to DO at that time, by Seyal itself ("at 3:34 pm search bairavaa in chrome, don't remind, you perform")

    /** "don't remind me", "no reminder", "not a reminder", "don't just notify me". */
    private val notRemind = Regex("\\b(?:don'?t|do not|dont|no need to|never)\\s+(?:just\\s+)?(?:remind|notify)(?:\\s+me)?\\b|\\b(?:no|not a|not an|without a|without)\\s+(?:reminders?|notifications?|reminding)\\b|\\binstead of remind\\w*\\b", I)
    /** "you perform", "do it yourself", "perform it", "do it automatically", "you do it". */
    private val doItYourself = Regex(
        "\\byou\\s+(?:only\\s+|just\\s+|must\\s+|should\\s+|have to\\s+)?(?:perform|execute)(?:\\s+it)?(?:\\s+(?:yourself|by yourself|on your own))?\\b|\\byou\\s+(?:just\\s+|must\\s+|should\\s+)?do\\s+it(?:\\s+(?:yourself|by yourself|on your own))?\\b|" +
            "\\b(?:just\\s+)?(?:perform|execute)\\s+it\\b|\\bperform\\s*$|\\bdo it (?:yourself|by yourself|on your own|automatically)\\b|\\b(?:by yourself|on your own|automatically)\\b|\\bneeye (?:pannu|sei)\\b|\\bnee pannu\\b",
        I,
    )
    /** Messages, calls and posts go to people; money moves. Those are planned by the AI with its own checks, as before. */
    private val toPeopleOrMoney = Regex(
        "\\b(send|message|msg|text|whatsapp|sms|e-?mail|mail|call|ring|dial|reply|post|share|forward|wish|invite|inform|tell (?!me\\b)\\w+|pay|transfer|recharge|buy|purchase|upi|gpay|phonepe|paytm)\\b|அனுப்பு",
        I,
    )
    private val actionFirst = Regex(
        "^(?:please |pls |kindly )?(?:search|google|look up|open|launch|play|go to|visit|browse|turn (?:on|off)|switch (?:on|off)|(?:turn|switch) .{2,24} (?:on|off)|set|put|mute|unmute|start|stop|pause|resume|download|take|record|navigate|run|clear|close|enable|disable|increase|decrease|reduce|raise|lower)\\b",
        I,
    )

    /** Whether the user said to do it and not to remind ("don't remind, you perform", "do it yourself", "no reminder"). */
    fun saysPerform(said: String): Boolean = notRemind.containsMatchIn(said) || doItYourself.containsMatchIn(said)

    /** The thing to do, without "don't remind", "you perform" and stray dots: "search bairavaa in chrome". "" when nothing is left. */
    fun task(rest: String): String =
        doItYourself.replace(notRemind.replace(rest, " "), " ")
            .replace(Regex("\\.{2,}|…|\\s+[.,;]+(?=\\s|$)"), " ").replace(Regex("\\s+"), " ")
            .replace(Regex("^(?:and|but|then|so|,)\\s+|\\s+(?:and|but|then|so|,)$", I), "")
            .trim().trim(',', '.', '-', ':', ';').trim()

    /**
     * Whether [task] (what is left of a request with a later time, in the user's words [said]) is something for Seyal
     * to DO at that time: the user said so, or it is plainly an action on the phone ("search…", "open…", "play…",
     * "turn on…"). Never a message, a call or a payment: those keep their own planning and checks.
     * (Seen on a real phone, 20 Nov 2026: "at 3:34 pm today.. search bairavaa in chrome... don't remind you perform"
     * got a reminder that opens Chrome on a tap.)
     */
    fun performs(task: String, said: String): Boolean {
        if (task.length < 3 || toPeopleOrMoney.containsMatchIn(task)) return false
        return saysPerform(said) || actionFirst.containsMatchIn(task)
    }

    private val webSearch = Regex("^(?:please |pls )?(?:search|google|look up)\\s+(?:for\\s+)?(.{1,80}?)\\s+(?:in|on|using|with|from)\\s+(?:the\\s+|my\\s+)?(chrome|google|browser|web|internet|net|youtube|you tube|yt)(?:\\s+(?:app|browser))?$", I)

    /**
     * The step that does [task] with no AI at all, as (tool, arguments), when it is as plain as a web or YouTube
     * search; null when the AI has to work it out at that time.
     */
    fun step(task: String): Pair<String, org.json.JSONObject>? {
        val m = webSearch.find(task.trim()) ?: return null
        val q = java.net.URLEncoder.encode(m.groupValues[1].trim().trim('"', '\''), "UTF-8")
        val where = m.groupValues[2].lowercase()
        if (q.isBlank()) return null
        return if (where.startsWith("you") || where == "yt") "open_url" to org.json.JSONObject().put("url", "https://www.youtube.com/results?search_query=$q")
        else "open_url" to org.json.JSONObject().put("url", "https://www.google.com/search?q=$q").apply { if (where == "chrome") put("app", "Chrome") }
    }

    /** [t] without the span [r] and the little words left hanging ("at", "on", a comma). */
    private fun cut(t: String, r: IntRange): String =
        (t.substring(0, r.first) + " " + t.substring(r.last + 1))
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+(?:at|by|on|for)\\s*$", I), "")
            .replace(Regex("^\\s*(?:at|by|on|,)\\s+", I), "")
            .trim().trim(',', '-', ':').trim()

    /** "11:09 PM today", "8:00 AM tomorrow", "9:00 PM Sat 22 Nov". */
    fun say(at: LocalDateTime, now: LocalDateTime): String {
        val day = when (at.toLocalDate()) {
            now.toLocalDate() -> "today"
            now.toLocalDate().plusDays(1) -> "tomorrow"
            else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
        }
        return at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)) + " " + day
    }

    fun sayClock(t: LocalTime): String = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
}
