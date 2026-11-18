package com.seyal.app.agent

import org.json.JSONObject
import com.seyal.app.util.Times

/** The parts of saving a meeting that are plain rules: when Seyal's own reminder goes off, and what is said afterwards. */
object Meetings {
    /**
     * When Seyal's own reminder should fire: [before] minutes ahead of the start; if that moment has already passed
     * (a meeting in five minutes), one minute from now; null when the meeting is about to start anyway.
     */
    fun ownReminderAt(startMs: Long, before: Int, now: Long): Long? {
        val at = startMs - before * 60_000L
        return when {
            at > now + 60_000 -> at
            startMs > now + 3 * 60_000 -> now + 60_000
            else -> null
        }
    }

    fun reminderText(title: String, startMs: Long, at: Long): String {
        val mins = ((startMs - at) / 60_000L).toInt()
        return if (mins <= 1) "$title starts now" else "$title starts in $mins minutes (${Times.clock(startMs)})"
    }

    private val saysNotSaved = Regex("\\bnot\\b[^.\\n]{0,40}\\bcalendar\\b|\\bcalendar\\b[^.\\n]{0,40}\\b(not|could ?not|couldn.?t|failed)\\b|\\b(could ?not|couldn.?t|unable to)\\b[^.\\n]{0,40}\\bcalendar\\b", RegexOption.IGNORE_CASE)
    private val saysNoAlert = Regex("\\bno (alert|alarm|reminder)\\b|\\bwithout (an? |any )?(alert|alarm|reminder)\\b|\\b(alert|alarm|reminder)s?\\b[^.\\n]{0,30}\\b(not|could ?not|couldn.?t)\\b", RegexOption.IGNORE_CASE)

    /**
     * The AI's final answer with the truth about each meeting made certain. Seen in a test run: the calendar tool
     * reported "NOT saved in your calendar", and the AI told the user "Team meeting scheduled". When a meeting was not
     * put in the calendar, or has no alert at all, and [answer] does not say so, the tool's own sentence is added.
     * [outputs]: what calendar_add returned for this request.
     */
    fun withProof(answer: String, outputs: List<JSONObject>): String {
        val add = ArrayList<String>()
        for (o in outputs) {
            val say = o.optString("say")
            if (say.isBlank()) continue
            val saved = o.optBoolean("saved_in_calendar")
            val anyAlert = o.optBoolean("calendar_alert") || o.optBoolean("seyal_reminder")
            if (!saved && !saysNotSaved.containsMatchIn(answer)) add += say
            else if (saved && !anyAlert && !saysNoAlert.containsMatchIn(answer)) add += say
        }
        return if (add.isEmpty()) answer else (answer.trim() + "\n\n" + add.distinct().joinToString("\n")).trim()
    }

    /**
     * What the user is told, word for word. It says where the meeting is saved and which alerts are in place,
     * and when something is missing it says so first.
     */
    fun proof(title: String, whenText: String, saved: Boolean, calendarAlert: Boolean, own: Boolean, before: Int, ownAt: String?): String {
        val ahead = if (before == 0) "at the start" else "$before min before"
        return when {
            saved && calendarAlert && own -> "Saved in your calendar: $title, $whenText. Two alerts are set: the calendar's ($ahead) and Seyal's own reminder."
            saved && calendarAlert -> "Saved in your calendar: $title, $whenText, with the calendar's alert $ahead."
            saved && own -> "Saved in your calendar: $title, $whenText. The calendar's own alert could not be added, so Seyal will remind you ($ahead)."
            saved -> "Saved in your calendar: $title, $whenText. NO alert could be set: open the event and add one, or set an alarm."
            own -> "NOT saved in your calendar (it is not allowed or no calendar was found). Seyal will still remind you: $title, $whenText${ownAt?.let { ", reminder $it" } ?: ""}. To have it in the calendar too, allow Calendar and ask again."
            else -> "NOT saved: $title, $whenText could not be put in the calendar and no reminder was set. Please add it yourself."
        }
    }
}
