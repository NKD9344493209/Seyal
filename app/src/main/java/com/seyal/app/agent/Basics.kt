package com.seyal.app.agent

import com.seyal.app.util.Times
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDateTime

/**
 * The everyday commands, understood on the phone itself: call, message, alarm, reminder, weather,
 * battery, time, volume, directions, search, and Seyal's own lists. No AI is asked, so they work
 * at once and keep working when the AI is slow, overloaded or out of its daily limit.
 * Anything not plainly one of these is left to the AI: a wrong guess here would be worse than a wait.
 */
object Basics {
    /**
     * One command, ready to run. [who]: a contact to look up first (its number goes into args as "phone",
     * its saved name as "name"). [say]: the whole answer, when no tool is needed. [answer]: turns the
     * tool's result into the reply; without it the tool's own one-line summary is used.
     */
    class Plan(val tool: String, val args: JSONObject = JSONObject(), val who: String? = null, val say: String? = null, val answer: ((JSONObject) -> String)? = null)

    private fun args(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }
    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    private val polite = Regex("^(hey |hi |ok |okay )?(seyal[, ]+)?(please |pls |can you |could you |will you |kindly )?|( please| pls| da| bro| pa)$", RegexOption.IGNORE_CASE)

    /** Words that can't be a contact: the request is about something else ("call me a cab", "message him"). */
    private val notAPerson = Regex(
        "\\b(me|him|her|them|it|us|back|last|missed|number|cab|taxi|auto|uber|ola|rapido|ambulance|police|customer|care|support|helpline|everyone|all|group|someone|anyone|whoever|the|a|an|this|that|and|or|then|if|when|after|before|about|with)\\b|\\d{3,}",
        RegexOption.IGNORE_CASE,
    )

    /** "my dad" → "dad"; null when it is not plainly one person's name. */
    internal fun person(raw: String): String? {
        val w = raw.trim().trim('.', ',', '!', '?', '"', '\'').replace(Regex("^(my|to|for) ", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), " ").trim()
        if (w.length < 2 || w.length > 30 || w.split(' ').size > 3 || notAPerson.containsMatchIn(w)) return null
        return w
    }

    /**
     * Which of the contacts found is meant. [matches]: name and phone of each. Returns the one to use,
     * or null when it is not clear (several different people): then the user is asked.
     */
    fun pick(who: String, matches: List<Pair<String, String>>): Pair<String, String>? {
        val withPhone = matches.filter { it.second.isNotBlank() }
        if (withPhone.isEmpty()) return null
        fun digits(p: String) = p.filter { it.isDigit() }.takeLast(10)
        if (withPhone.map { digits(it.second) }.distinct().size == 1) return withPhone.first()
        val exact = withPhone.filter { it.first.equals(who, true) }
        if (exact.isNotEmpty() && exact.map { digits(it.second) }.distinct().size == 1) return exact.first()
        return null
    }

    /** A message as the user dictated it, tidied: quotes off, first letter up. Null when it is too short to be one. */
    private fun messageText(raw: String): String? {
        val t = raw.trim().trim('"', '\'', '“', '”').trim().trimEnd(',')
        if (t.length < 2 || t.length > 400) return null
        return t.replaceFirstChar { it.uppercase() }
    }

    private fun pretty(dt: LocalDateTime, now: LocalDateTime): String {
        val day = when (dt.toLocalDate()) { now.toLocalDate() -> "today"; now.toLocalDate().plusDays(1) -> "tomorrow"; else -> dt.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH)) }
        return "${dt.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH))} $day"
    }

    /** "7:30 am", "6 pm tomorrow" → the next time the clock shows it. A bare "7" is not clear enough. */
    internal fun clock(text: String, now: LocalDateTime): LocalDateTime? {
        val t = Times.parseClock(text.lowercase().replace("a.m.", "am").replace("p.m.", "pm").replace("a.m", "am").replace("p.m", "pm")) ?: return null
        val tomorrow = Regex("\\b(tomorrow|tmrw|naalai|naalaikku|nalaiku)\\b|நாளை", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val at = now.toLocalDate().atTime(t)
        return if (tomorrow) at.plusDays(1) else if (at.isAfter(now.plusSeconds(30))) at else at.plusDays(1)
    }

    /**
     * The plan for [raw], or null when it is not plainly one of the everyday commands.
     * [whatsApp]: WhatsApp is on the phone (a plain "message" then goes there, otherwise by SMS).
     */
    fun plan(raw: String, now: LocalDateTime = LocalDateTime.now(), whatsApp: Boolean = true): Plan? {
        val original = polite.replace(polite.replace(raw.trim(), ""), "").trim().trimEnd('.', '!')
        val t = original.lowercase().replace(Regex("\\s+"), " ").trimEnd('?').trim()
        if (t.length < 3 || t.length > 220 || t.contains('\n')) return null
        fun <T> Regex.on(block: (MatchResult) -> T?): T? = find(t)?.let(block)
        /** The same match on the text as typed (the message keeps its capitals). */
        val asTyped = original.replace(Regex("\\s+"), " ").trimEnd('?').trim()
        fun typed(m: MatchResult, group: Int): String = m.groups[group]?.range?.takeIf { asTyped.length == t.length }?.let { r -> asTyped.substring(r) } ?: m.groupValues[group]

        // ---- the time, the date, the battery: answered from the phone itself
        if (Regex("^(what(?:'s| is)? (?:the )?time(?: (?:is it|now))?|what time is it(?: now)?|time(?: now)?|current time|time enna|mani enna|enna mani)$").matches(t))
            return Plan("", say = "It's ${now.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH))}.")
        if (Regex("^(what(?:'s| is)? (?:the |today'?s )?(?:date|day)(?: today| is it)?|(?:today'?s? )?date|which day is (?:it|today)|inniku enna (?:date|theathi|kizhamai))$").matches(t))
            return Plan("", say = "Today is ${now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", java.util.Locale.ENGLISH))}.")
        if (Regex("^(battery|battery (?:level|percentage|percent|status|left)|how much (?:battery|charge)(?: (?:is )?left| do i have)?|what(?:'s| is) (?:the |my )?battery(?: level| percentage)?|charge evlo(?: irukku)?|battery evlo(?: irukku)?)$").matches(t))
            return Plan("phone_state") { o -> "Battery is at ${o.optInt("battery_percent")}%" + (if (o.optBoolean("charging")) ", charging." else ".") }

        // ---- weather now (a forecast for another day is the AI's to word)
        if (!Regex("\\b(tomorrow|week|weekend|monday|tuesday|wednesday|thursday|friday|saturday|sunday|next|forecast|naalai)\\b").containsMatchIn(t)) {
            Regex("^(?:what(?:'s| is) the |how(?:'s| is) the |tell me the |show (?:me )?the |today'?s? )?(?:weather|climate|temperature)(?: (?:like|now|today|right now|outside))*(?: (?:in|at|for|of) ([\\p{L} .'-]{2,40}?))?(?: (?:now|today|right now))?$").on { m ->
                Plan("weather", args("place" to m.groupValues[1].trim()))
            }?.let { return it }
            Regex("^([\\p{L} .'-]{2,30}?) (?:weather|climate|temperature)(?: now| today)?$").on { m -> m.groupValues[1].trim().takeIf { it !in setOf("the", "today", "todays", "today's", "current", "what is the", "whats the") }?.let { Plan("weather", args("place" to it)) } }?.let { return it }
        }

        // ---- calls
        Regex("^(?:make a |place a )?(?:phone |voice )?(?:call|phone|ring|dial)(?: to)? (.{2,34})$").on { m -> person(m.groupValues[1])?.let { Plan("call", who = it) } }?.let { return it }
        Regex("^(.{2,30}?) ?(?:ku|kku|kitta|ah|a) (?:oru )?(?:call|phone) (?:pannu|podu|pannunga|panu|pani)$").on { m -> person(m.groupValues[1])?.let { Plan("call", who = it) } }?.let { return it }
        Regex("^(.{2,30}?)(?:க்கு|கு|வுக்கு|விற்கு|ுக்கு) (?:ஒரு )?(?:கால்|போன்|call) (?:பண்ணு|போடு|பண்ணுங்க|செய்)$").on { m -> person(m.groupValues[1])?.let { Plan("call", who = it) } }?.let { return it }

        // ---- messages: who and the words must both be plain
        // "…at 8 pm", "tomorrow", "every day", "…and call him": a send for later, or two jobs in one. The AI plans those.
        val notNow = Regex("\\b(every|whenever|daily|schedule|scheduled|later|tomorrow|tonight|after \\d|in \\d+ ?(min|mins|minutes|hour|hours))\\b|\\bat \\d{1,2}([:.]\\d{2})? ?(am|pm)\\b|\\b(and|then) (then )?(call|send|open|set|remind|book|order|tell|message|email|mail|play|share)\\b")
        fun message(who: String, text: String, channel: String): Plan? {
            if (notNow.containsMatchIn(t)) return null
            val p = person(who) ?: return null
            val msg = messageText(text) ?: return null
            val tool = when { channel.contains("whatsapp") || channel == "wa" -> "send_whatsapp"; channel.contains("sms") || channel.contains("text") -> "send_sms"; whatsApp -> "send_whatsapp"; else -> "send_sms" }
            return Plan(tool, args("text" to msg), who = p)
        }
        val ch = "(whatsapp message|whatsapp msg|whatsapp|wa|sms|text message|text|message|msg)"
        // "send a WhatsApp to dad saying I'll be late", "message Ram that the meeting is at 5"
        Regex("^(?:send |drop |write )?(?:a |an )?$ch (?:to )?(.{2,30}?) (?:saying|that says|that|telling (?:him|her|them)(?: that)?|:|-) (.{2,})$").on { m -> message(m.groupValues[2], typed(m, 3), m.groupValues[1]) }?.let { return it }
        // "tell dad that I'll be late", "tell amma I reached"
        Regex("^(?:tell|inform|let) (.{2,30}?) (?:know )?(?:that|saying) (.{2,})$").on { m -> message(m.groupValues[1], typed(m, 2), "") }?.let { return it }
        // "send good morning to dad on whatsapp", "send hi to ram"
        Regex("^send (.{2,}?) to (.{2,30}?)(?: (?:on|in|via|through|by) (whatsapp|sms|text|message))?$").on { m ->
            if (Regex("\\b(file|photo|pic|picture|image|video|pdf|ppt|document|doc|location|contact|link|it|this|that|screenshot|money|rs|₹|rupees)\\b").containsMatchIn(m.groupValues[1])) null
            else message(m.groupValues[2], typed(m, 1).removePrefix("a ").removePrefix("message "), m.groupValues[3])
        }?.let { return it }
        // "dad ku I will be late nu message pannu"
        Regex("^(.{2,30}?) ?(?:ku|kku|kitta) (.{2,}?) (?:nu|nnu|endru|apdinu) (?:oru )?(whatsapp|message|msg|sms|text) (?:pannu|anuppu|anupu|podu|pannunga|send pannu)$").on { m -> message(m.groupValues[1], typed(m, 2), m.groupValues[3]) }?.let { return it }

        // ---- alarms, reminders, timers
        Regex("^(?:set |put |keep |create |add )?(?:an |a |the )?alarm (?:for|at|to) (.{2,30})$|^wake me(?: up)? (?:at|by) (.{2,30})$|^(.{2,30}?) ?(?:ku|kku|manikku) (?:oru )?alarm (?:vei|vai|podu|set pannu|vechiru)$").on { m ->
            val w = m.groupValues.drop(1).first { it.isNotBlank() }
            clock(w, now)?.let { at -> Plan("alarm_set", args("at" to at.toString().take(16)), say = "Alarm set for ${pretty(at, now)}.") }
        }?.let { return it }
        Regex("^remind me (?:to |about |that )?(.{2,120}?) (?:at|by) (\\d.{1,24})$").on { m ->
            clock(m.groupValues[2], now)?.let { at -> Plan("reminder_set", args("at" to at.toString().take(16), "text" to typed(m, 1).replaceFirstChar { it.uppercase() }), say = "I'll remind you at ${pretty(at, now)}: ${typed(m, 1)}.") }
        }?.let { return it }
        Regex("^remind me (?:at|by) (\\d[\\d:. ]*(?:am|pm|a\\.m\\.?|p\\.m\\.?)?(?: tomorrow)?) (?:to |about |that )?(.{2,120})$").on { m ->
            clock(m.groupValues[1], now)?.let { at -> Plan("reminder_set", args("at" to at.toString().take(16), "text" to typed(m, 2).replaceFirstChar { it.uppercase() }), say = "I'll remind you at ${pretty(at, now)}: ${typed(m, 2)}.") }
        }?.let { return it }
        Regex("^remind me (?:to |about |that )?(.{2,120}?) (?:in|after) (\\d{1,3}) ?(min|mins|minute|minutes|hour|hours|hr|hrs)$|^remind me (?:in|after) (\\d{1,3}) ?(min|mins|minute|minutes|hour|hours|hr|hrs) (?:to |about |that )?(.{2,120})$").on { m ->
            val first = m.groupValues[1].isNotBlank()
            val n = (if (first) m.groupValues[2] else m.groupValues[4]).toLong()
            val unit = if (first) m.groupValues[3] else m.groupValues[5]
            val what = typed(m, if (first) 1 else 6)
            val at = if (unit.startsWith("h")) now.plusHours(n) else now.plusMinutes(n)
            Plan("reminder_set", args("at" to at.toString().take(16), "text" to what.replaceFirstChar { it.uppercase() }), say = "I'll remind you at ${pretty(at, now)}: $what.")
        }?.let { return it }
        Regex("^(?:set |start |put )?(?:a |an )?timer (?:for |of )?(\\d{1,3}) ?(sec|secs|second|seconds|min|mins|minute|minutes|hour|hours|hr|hrs)$|^(\\d{1,3}) ?(min|mins|minute|minutes|hour|hours) timer$").on { m ->
            val n = (m.groupValues[1].ifBlank { m.groupValues[3] }).toInt(); val unit = m.groupValues[2].ifBlank { m.groupValues[4] }
            val minutes = when { unit.startsWith("h") -> n * 60; unit.startsWith("s") -> maxOf(1, n / 60); else -> n }
            Plan("timer_set", args("minutes" to minutes))
        }?.let { return it }

        // ---- sound and switches
        Regex("^(?:set |make |turn |put )?(?:the )?(?:media |music |phone )?(?:volume|sound)(?: level)? (?:to |at )?(\\d{1,3}) ?(?:%|percent)?$").on { m -> Plan("set_setting", args("name" to "volume_media", "value" to m.groupValues[1].toInt().coerceIn(0, 100).toString())) }?.let { return it }
        if (Regex("^(?:full|max|maximum) volume$|^volume (?:full|max|maximum)$").matches(t)) return Plan("set_setting", args("name" to "volume_media", "value" to "100"))
        if (Regex("^mute(?: the)?(?: phone| volume| sound| music)?$").matches(t)) return Plan("set_setting", args("name" to "volume_media", "value" to "0"))
        Regex("^(?:turn |switch )?(on|off) (?:the )?(wifi|wi-fi|wi fi|bluetooth|auto rotate|auto-rotate|rotation)$|^(?:turn |switch )?(?:the )?(wifi|wi-fi|wi fi|bluetooth|auto rotate|auto-rotate|rotation) (on|off)$").on { m ->
            val onOff = m.groupValues[1].ifBlank { m.groupValues[4] }; val what = m.groupValues[2].ifBlank { m.groupValues[3] }
            Plan("set_setting", args("name" to when { what.startsWith("wi") -> "wifi"; what.startsWith("blue") -> "bluetooth"; else -> "auto_rotate" }, "value" to onOff))
        }?.let { return it }
        if (Regex("^(?:put (?:the )?phone (?:on|in|to) |switch to |set (?:the )?(?:phone|ringer) to )?(?:normal|ring|ringing|sound)(?: mode)?$|^unmute(?: the)?(?: phone)?$").matches(t)) return Plan("set_setting", args("name" to "ringer", "value" to "normal"))

        // ---- places and searches: open the right page straight away
        Regex("^(?:navigate|navigation|directions?|route|take me|drive|go)(?: me)? to (.{2,80})$|^(?:show|get|give)(?: me)? (?:the )?(?:directions?|route|way) to (.{2,80})$|^how (?:do i|to) (?:get|go|reach) to (.{2,80})$|^(.{2,60}?) ?(?:ku|kku) (?:vazhi|route|direction) (?:kaattu|kaatu|sollu|podu)$").on { m ->
            val place = typed(m, (1..m.groupValues.lastIndex).first { m.groupValues[it].isNotBlank() })
            Plan("open_url", args("url" to "https://www.google.com/maps/dir/?api=1&destination=${enc(place)}"), say = "Opened directions to ${place.trim()} in Maps.")
        }?.let { return it }
        Regex("^(?:search|find|look up) (?:for )?(.{2,80}?) (?:on|in) youtube$|^(?:search )?youtube (?:for |la |search (?:for )?)(.{2,80}?)(?: search pannu)?$|^youtube ?la (.{2,80}?) (?:search|thedu)(?: pannu)?$").on { m ->
            val q = typed(m, (1..m.groupValues.lastIndex).first { m.groupValues[it].isNotBlank() })
            Plan("open_url", args("url" to "https://www.youtube.com/results?search_query=${enc(q)}"), say = "Here are the YouTube results for \"${q.trim()}\".")
        }?.let { return it }
        Regex("^(?:google|search google for|search on google for|google search(?: for)?|search the web for|web search(?: for)?) (.{2,120})$|^(?:google ?la) (.{2,80}?) (?:search|thedu)(?: pannu)?$").on { m ->
            val q = typed(m, (1..m.groupValues.lastIndex).first { m.groupValues[it].isNotBlank() })
            Plan("open_url", args("url" to "https://www.google.com/search?q=${enc(q)}"), say = "Here is the Google search for \"${q.trim()}\".")
        }?.let { return it }

        // ---- Seyal's own lists
        if (Regex("^(?:(?:what|which|any|show|list|tell me)(?: are| is)? )?(?:(?:my|the|me my|me the|me) )?(?:deadlines?|due dates?|bills?(?: due)?|things? (?:are )?due|forms? to fill|caught(?: for me)?)(?: do i have| are (?:there|due|pending|coming)| pending| due| coming up| this week| today)*$|^what did you catch(?: for me)?$|^what did i miss$").matches(t))
            return Plan("inbox_list") { o ->
                val items = o.optJSONArray("items")
                if (items == null || items.length() == 0) "Nothing is waiting: no deadlines, bills or renewals caught from your messages."
                else "Caught from your messages:\n" + (0 until items.length()).joinToString("\n") { i -> items.getJSONObject(i).let { c -> "• Due ${c.optString("due")}: ${c.optString("what")}" + (c.optString("link").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") } }
            }
        if (Regex("^(?:give me |show me |tell me |what(?:'s| is) )?(?:my |the |today'?s? )?(?:morning |daily |day'?s? )?(?:summary|brief|briefing|digest)(?: for today| today| of (?:the day|today))?$|^what(?:'s| is) on today$|^what do i (?:have|need to do) today$").matches(t))
            return Plan("daily_brief") { o -> o.optString("summary").ifBlank { "Nothing to report today." } }
        if (Regex("^what (?:are you|is seyal) watching(?: for me)?$|^(?:show|list)(?: me)?(?: my| the)? watch(?:ers|es|ed pages|ing list)$|^what are my watchers$").matches(t))
            return Plan("watch_list") { o ->
                val w = o.optJSONArray("watching")
                if (w == null || w.length() == 0) "I'm not watching any page for you right now."
                else "Watching for you:\n" + (0 until w.length()).joinToString("\n") { i -> w.getJSONObject(i).let { x -> "• ${x.optString("what")}: tells you ${x.optString("tells_you")}" + (if (x.optBoolean("happened")) " (it happened)" else if (!x.optBoolean("on")) " (stopped)" else "") } }
            }
        if (Regex("^(?:end|stop|turn off|switch off|cancel|exit) (?:the )?busy(?: mode)?$|^busy mode off$|^i(?:'m| am) (?:free|back|done|available)(?: now)?$").matches(t))
            return Plan("busy_mode", args("on" to false))
        return null
    }
}
