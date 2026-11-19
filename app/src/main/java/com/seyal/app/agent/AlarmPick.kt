package com.seyal.app.agent

import java.time.Instant
import java.time.ZoneId

/**
 * "Dismiss the alarm", "cancel the 6:30 alarm", "stop all alarms": which of Seyal's alarms is meant.
 *
 * Seen on a real phone: the user pressed Snooze instead of Dismiss, asked Seyal to dismiss the alarm, and it
 * rang again ten minutes later: Seyal could set alarms but had no way to list or cancel one. Plain rules, no AI.
 */
object AlarmPick {
    private val I = RegexOption.IGNORE_CASE

    /** One alarm waiting in Seyal. A snoozed one carries an id that begins with "snz". */
    class A(val id: String, val at: Long, val label: String) { val snoozed: Boolean get() = id.startsWith("snz") }

    /** What was asked: every alarm, the one at a time ("6:30", "6 am"), or just "the alarm". */
    class Want(val all: Boolean, val time: String)

    private const val VERB = "(?:dismiss|stop|cancel|turn off|switch off|delete|remove|clear|end|kill|off)"
    private val ask = Regex(
        "^(?:please |pls )?$VERB\\s+(all |every )?(?:the |my |that |this |of (?:the |my )?)*(?:snoozed? |snoozing |ringing |next |seyal |seyal's )?(?:(.{1,12}?)\\s+)?alarms?(?:\\s+(?:for|at|of|set for)\\s+(.{1,12}))?(?: please| now)?$" +
            "|^(?:the |my )?alarm(?:a|ai)? ?(?:off|stop|venam|vendam|venaam|niruthu|cancel)(?: pannu| panu| seiyu)?$" +
            "|^(?:no more snooze|stop snoozing|stop the snooze|cancel (?:the )?snooze|dismiss (?:the )?snooze)$" +
            "|^அலாரம்(?:த்தை|ை)? ?(?:நிறுத்து|வேண்டாம்|ஆஃப்)(?: பண்ணு| செய்)?$",
        I,
    )
    private val list = Regex("^(?:what|which|show|list|tell me)(?: are| is)?(?: all)?(?: (?:my|the))? alarms?(?: (?:are|is))?(?: set| on| pending| waiting)*(?: (?:do i have|have i set))?$|^(?:my |any |do i have (?:any )?)alarms?(?: set)?$", I)

    /** "dismiss the alarm", "cancel all alarms", "cancel the 6:30 alarm", "alarm off pannu" → what to cancel. Null = something else. */
    fun asks(text: String): Want? {
        val t = text.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        val m = ask.find(t) ?: return null
        val all = m.groupValues.getOrNull(1).orEmpty().isNotBlank() || Regex("\\balarms\\b", I).containsMatchIn(t) && Regex("\\ball\\b|\\bevery\\b", I).containsMatchIn(t)
        val time = listOf(m.groupValues.getOrNull(3).orEmpty(), m.groupValues.getOrNull(2).orEmpty()).firstOrNull { clock(it) != null }.orEmpty()
        // "cancel the morning alarm": a word that is not a time is not ours to guess at.
        val word = m.groupValues.getOrNull(2).orEmpty().trim()
        if (time.isBlank() && word.isNotBlank() && !Regex("^(?:first|last|same|current|one|only|new|old)$", I).matches(word)) return null
        return Want(all, time)
    }

    /** "what alarms do I have", "show my alarms". */
    fun asksList(text: String): Boolean = list.matches(text.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " "))

    /** "6:30", "6.30 pm", "6 am", "18:30" → minutes of the day, and whether am/pm was said. Null when it is not a time. */
    fun clock(s: String): Pair<Int, Boolean>? {
        val m = Regex("^\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.?|p\\.m\\.?)?\\s*$", I).find(s) ?: return null
        var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toIntOrNull() ?: 0
        val half = m.groupValues[3].lowercase().replace(".", "")
        if (h > 23 || min > 59) return null
        if (half.isEmpty() && m.groupValues[2].isEmpty()) return null   // a bare "6" is not said as a time here
        if (half == "pm" && h < 12) h += 12
        if (half == "am" && h == 12) h = 0
        return (h * 60 + min) to (half.isNotEmpty() || h > 12)
    }

    class Choice(val cancel: List<A>, val stopRinging: Boolean, val say: String)

    private fun names(xs: List<A>, fmt: (Long) -> String) = xs.joinToString(", ") { fmt(it.at) + (if (it.snoozed) " (snoozed)" else "") + (it.label.takeIf { l -> l.isNotBlank() && l != "Alarm" }?.let { l -> " \"$l\"" } ?: "") }

    /**
     * Which [alarms] to cancel for [want]. [ringing]: one is sounding right now (it is always stopped).
     * With nothing said about which: the snoozed one(s); else the only one there is; with several and none
     * snoozed, nothing is cancelled and they are listed so the user can say which.
     */
    fun choose(alarms: List<A>, want: Want, ringing: Boolean, zone: ZoneId, fmt: (Long) -> String): Choice {
        val sorted = alarms.sortedBy { it.at }
        fun done(xs: List<A>, lead: String) = Choice(xs, ringing, (if (ringing) "Stopped the alarm that was ringing. " else "") + lead)
        if (want.all) return if (sorted.isEmpty()) done(emptyList(), if (ringing) "No other alarm is waiting." else "No alarm is set in Seyal.") else done(sorted, "Cancelled ${if (sorted.size == 1) "the alarm" else "all ${sorted.size} alarms"}: ${names(sorted, fmt)}.")
        if (want.time.isNotBlank()) {
            val (mins, exact) = clock(want.time) ?: (0 to false)
            val hit = sorted.filter { a ->
                val t = Instant.ofEpochMilli(a.at).atZone(zone); val m = t.hour * 60 + t.minute
                m == mins || (!exact && (m == mins + 720 || m + 720 == mins))
            }
            return if (hit.isEmpty()) done(emptyList(), "No alarm at ${want.time}." + (if (sorted.isEmpty()) " None is set in Seyal." else " Waiting: ${names(sorted, fmt)}."))
            else done(hit, "Cancelled the alarm for ${names(hit, fmt)}.")
        }
        val snoozed = sorted.filter { it.snoozed }
        return when {
            snoozed.isNotEmpty() -> done(snoozed, "Dismissed the snoozed alarm: it will not ring again." + (sorted.size > snoozed.size).let { more -> if (more) " Still set: ${names(sorted - snoozed.toSet(), fmt)}." else "" })
            ringing && sorted.isEmpty() -> done(emptyList(), "No other alarm is waiting.")
            ringing -> done(emptyList(), "Still set: ${names(sorted, fmt)}.")
            sorted.size == 1 -> done(sorted, "Cancelled the alarm for ${names(sorted, fmt)}.")
            sorted.isEmpty() -> done(emptyList(), "No alarm is set in Seyal.")
            else -> done(emptyList(), "You have ${sorted.size} alarms: ${names(sorted, fmt)}. Say which one (\"cancel the ${fmt(sorted[0].at)} alarm\") or \"cancel all alarms\".")
        }
    }

    /** The answer to "what alarms do I have". */
    fun listed(alarms: List<A>, fmt: (Long) -> String): String =
        if (alarms.isEmpty()) "No alarm is set in Seyal." else "Alarms in Seyal: ${names(alarms.sortedBy { it.at }, fmt)}."

    /** A clock app (Google's, Xiaomi's, Samsung's, OnePlus's…), by its package name. */
    fun isClockApp(pkg: String): Boolean = Regex("deskclock|\\.clock(?:package)?(?:\\.|$)|alarmclock|\\.alarm(?:\\.|$)|clockpackage", I).containsMatchIn(pkg)

    /** The button on a clock notification that ends the alarm (not the one that snoozes it). */
    fun isDismiss(title: String): Boolean = Regex("^\\s*(?:dismiss|stop|turn off|close|cancel|off|end)\\b", I).containsMatchIn(title) && !Regex("snooze", I).containsMatchIn(title)

    /** Added when the phone's own clock app also had a ringing or snoozed alarm that was dismissed. */
    const val CLOCK_TOO = " Also dismissed the alarm in the phone's Clock app."
    const val CLOCK_HINT = " If it was set in the phone's Clock app, open Clock to turn it off: Seyal can only dismiss a Clock alarm while its notification is showing."
}
