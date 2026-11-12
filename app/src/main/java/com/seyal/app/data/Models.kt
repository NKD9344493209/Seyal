package com.seyal.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

/** How a tool may run: by itself, after asking, or never. */
enum class Level { AUTO, ASK, OFF }

/** Where a session stands. */
enum class SessionStatus {
    RUNNING, WAITING, DONE, FAILED, CANCELLED;

    val isOpen: Boolean get() = this == RUNNING || this == WAITING
}

/** One tool call's runtime state (the transcript holds the call itself). */
enum class CallStatus {
    PENDING, WAITING_APPROVAL, WAITING_USER, RUNNING, DONE, FAILED, DENIED, CANCELLED;

    val isWaiting: Boolean get() = this == WAITING_APPROVAL || this == WAITING_USER
    val isFinished: Boolean get() = this == DONE || this == FAILED || this == DENIED || this == CANCELLED
}

// ------------------------------------------------------------------ transcript

/** A piece of a message: text, an image, a tool call, or a tool result. */
sealed class Part {
    class Text(val text: String) : Part()
    class Image(val mime: String, val b64: String) : Part()
    /** [signature] is Gemini 3's thought signature, which must be echoed back with the call. */
    class Call(val id: String, val name: String, val args: JSONObject, val signature: String? = null) : Part()
    class Result(val callId: String, val name: String, val output: JSONObject, val isError: Boolean = false, val image: Image? = null) : Part()

    fun toJson(): JSONObject = when (this) {
        is Text -> JSONObject().put("type", "text").put("text", text)
        is Image -> JSONObject().put("type", "image").put("mime", mime).put("b64", b64)
        is Call -> JSONObject().put("type", "call").put("id", id).put("name", name).put("args", args).also { o -> signature?.let { o.put("signature", it) } }
        is Result -> JSONObject().put("type", "result").put("call_id", callId).put("name", name).put("output", output).put("is_error", isError)
            .also { o -> image?.let { o.put("image", it.toJson()) } }
    }

    companion object {
        fun fromJson(o: JSONObject): Part? = when (o.optString("type")) {
            "text" -> Text(o.optString("text"))
            "image" -> Image(o.optString("mime", "image/jpeg"), o.optString("b64"))
            "call" -> Call(o.optString("id"), o.optString("name"), o.optJSONObject("args") ?: JSONObject(), o.optString("signature").ifBlank { null })
            "result" -> Result(
                o.optString("call_id"), o.optString("name"), o.optJSONObject("output") ?: JSONObject(), o.optBoolean("is_error"),
                o.optJSONObject("image")?.let { fromJson(it) as? Image },
            )
            else -> null
        }
    }
}

/** One message in the conversation. role: "user" | "assistant" | "tool". */
class Msg(val role: String, val parts: List<Part>, val at: Long = System.currentTimeMillis()) {
    val text: String get() = parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }.trim()
    val calls: List<Part.Call> get() = parts.filterIsInstance<Part.Call>()
    val results: List<Part.Result> get() = parts.filterIsInstance<Part.Result>()

    fun toJson(): JSONObject = JSONObject().put("role", role).put("at", at).put("parts", JSONArray().apply { parts.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Msg {
            val parts = mutableListOf<Part>()
            o.optJSONArray("parts")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { p -> Part.fromJson(p)?.let { parts += it } } }
            return Msg(o.optString("role", "user"), parts, o.optLong("at", System.currentTimeMillis()))
        }
    }
}

/** Runtime state of one tool call, shown on screen next to the call. */
class CallState(val callId: String) {
    var status: CallStatus = CallStatus.PENDING
    /** Friendly one-liner: "Found Appa", "WhatsApp sent to Ram". */
    var line: String? = null
    var error: String? = null
    var startedAt: Long = 0L
    var endedAt: Long = 0L
    /** Preview shown on the approval card, e.g. the message text. */
    var preview: String? = null
    /** For ask_user: the options offered. */
    var options: List<String> = emptyList()
    /** Child session ids for delegate(). */
    var children: List<String> = emptyList()
    /** This call sent a message to someone (kept for the run, not saved). */
    @Volatile var sent: Boolean = false

    fun toJson(): JSONObject = JSONObject().apply {
        put("call_id", callId); put("status", status.name)
        line?.let { put("line", it) }; error?.let { put("error", it) }; preview?.let { put("preview", it) }
        put("started_at", startedAt); put("ended_at", endedAt)
        if (options.isNotEmpty()) put("options", JSONArray(options))
        if (children.isNotEmpty()) put("children", JSONArray(children))
    }

    companion object {
        fun fromJson(o: JSONObject): CallState = CallState(o.optString("call_id")).apply {
            status = runCatching { CallStatus.valueOf(o.optString("status")) }.getOrDefault(CallStatus.DONE)
            line = o.optString("line").ifBlank { null }; error = o.optString("error").ifBlank { null }; preview = o.optString("preview").ifBlank { null }
            startedAt = o.optLong("started_at"); endedAt = o.optLong("ended_at")
            options = o.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            children = o.optJSONArray("children")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        }
    }
}

/**
 * One conversation with the agent: the user's request(s), everything the agent did, and the result.
 * The messages are exactly what the AI sees; [calls] carries the live status of each tool call.
 */
class Session(
    val id: String,
    val createdAt: Long,
    /** "user" | "skill:<id>" | "trigger:<text>" | "child:<parent id>" */
    val source: String = "user",
) {
    var title: String = ""
    var status: SessionStatus = SessionStatus.RUNNING
    val messages: MutableList<Msg> = mutableListOf()
    val calls: MutableMap<String, CallState> = linkedMapOf()
    /** One line the user sees in lists and notifications. */
    var result: String? = null
    var updatedAt: Long = createdAt
    var steps: Int = 0
    var llmCalls: Int = 0
    var tokens: Long = 0L
    var llmMs: Long = 0L
    /** Set when a child of delegate(). */
    var parentId: String? = null
    /** Sends the user already agreed to when they set a skill up ("tool|target"): they run without asking again. Not saved. */
    @Volatile var preApproved: Set<String> = emptySet()
    /** The app this task is working in (package), so Seyal can go back to it after the user answers a question. Not saved. */
    @Volatile var workApp: String? = null
    /** A skill started by a message from this person may reply to them without asking (the user set it up). Not saved. */
    @Volatile var trustedReplyTo: String? = null
    /** The notification that started this run: a reply goes to exactly that chat, never to one that merely has a similar name. Not saved. */
    @Volatile var replyKey: String? = null
    /** Started by an incoming message or call, with nobody watching. Sending anything needs "Automatic replies" on. Not saved. */
    @Volatile var unattended: Boolean = false
    /** Self-checks already made before saying "done" in this run (each is made once). Not saved. */
    val checked: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    /** Follow-ups typed while a tool round is in flight; appended once the round's results are in (keeps the transcript valid). Not saved. */
    val queued: MutableList<Msg> = mutableListOf()

    val firstCommand: String get() = messages.firstOrNull { it.role == "user" }?.text ?: title

    fun state(callId: String): CallState = calls.getOrPut(callId) { CallState(callId) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("created_at", createdAt); put("source", source); put("title", title); put("status", status.name)
        put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
        put("calls", JSONArray().apply { calls.values.forEach { put(it.toJson()) } })
        result?.let { put("result", it) }; parentId?.let { put("parent_id", it) }
        put("updated_at", updatedAt); put("steps", steps); put("llm_calls", llmCalls); put("tokens", tokens); put("llm_ms", llmMs)
    }

    /** What lists need, without the transcript. */
    fun toIndexJson(): JSONObject = JSONObject().apply {
        put("id", id); put("created_at", createdAt); put("source", source); put("title", title); put("status", status.name)
        result?.let { put("result", it) }; parentId?.let { put("parent_id", it) }
        put("updated_at", updatedAt); put("steps", steps)
    }

    /** A separate copy for the screens, so Compose always sees a new object when something changed. */
    fun snapshot(): Session = Session(id, createdAt, source).also { c ->
        c.title = title; c.status = status; c.result = result; c.updatedAt = updatedAt; c.parentId = parentId
        c.steps = steps; c.llmCalls = llmCalls; c.tokens = tokens; c.llmMs = llmMs
        c.messages.addAll(messages) // Msg and Part are immutable
        c.messages.addAll(queued)   // shown to the user straight away, sent to the AI after the round
        calls.values.forEach { s -> c.calls[s.callId] = CallState.fromJson(s.toJson()) }
    }

    companion object {
        fun fromJson(o: JSONObject): Session = Session(o.optString("id", newId()), o.optLong("created_at", System.currentTimeMillis()), o.optString("source", "user")).apply {
            title = o.optString("title"); result = o.optString("result").ifBlank { null }; parentId = o.optString("parent_id").ifBlank { null }
            status = runCatching { SessionStatus.valueOf(o.optString("status")) }.getOrDefault(SessionStatus.DONE)
            updatedAt = o.optLong("updated_at", createdAt); steps = o.optInt("steps"); llmCalls = o.optInt("llm_calls"); tokens = o.optLong("tokens"); llmMs = o.optLong("llm_ms")
            o.optJSONArray("messages")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { messages += Msg.fromJson(it) } }
            o.optJSONArray("calls")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { s -> CallState.fromJson(s).let { calls[it.callId] = it } } }
            // A session that was in flight (or waiting on the user) when the app died cannot continue by itself:
            // the loop and its waiting handlers are gone. Close it; "Continue" starts a fresh round on the same transcript.
            if (status.isOpen) {
                calls.values.forEach { if (!it.status.isFinished) { it.status = CallStatus.FAILED; it.error = "App was closed while this was running" } }
                status = SessionStatus.FAILED
                if (result == null) result = "Stopped: the app was closed. Send a message to pick it up."
            }
        }
    }
}

/** One row of the Activity list: everything about a session except its transcript. Immutable, so screens refresh correctly. */
data class SessionInfo(
    val id: String, val title: String, val status: SessionStatus, val result: String, val source: String,
    val createdAt: Long, val updatedAt: Long, val steps: Int,
) {
    val isChild: Boolean get() = source.startsWith("child")

    companion object {
        fun fromJson(o: JSONObject) = SessionInfo(
            o.optString("id"), o.optString("title"), runCatching { SessionStatus.valueOf(o.optString("status")) }.getOrDefault(SessionStatus.DONE),
            o.optString("result"), o.optString("source", "user"), o.optLong("created_at"), o.optLong("updated_at"), o.optInt("steps"),
        )
    }
}

// ------------------------------------------------------------------ skills

/**
 * "Only when…": conditions a triggered skill also needs before it runs. Empty = always.
 * after/before are "HH:mm" (a window may cross midnight: after 22:00, before 06:00).
 */
class Only(
    val after: String = "",
    val before: String = "",
    val days: List<Int> = emptyList(),
    val batteryBelow: Int = 0,
    val batteryAbove: Int = 0,
    val charging: Boolean? = null,
    /** Connected Wi-Fi name contains this ("" = don't care, "*" = any Wi-Fi, "-" = not on Wi-Fi). */
    val wifi: String = "",
) {
    val isEmpty: Boolean get() = after.isBlank() && before.isBlank() && days.isEmpty() && batteryBelow == 0 && batteryAbove == 0 && charging == null && wifi.isBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        if (after.isNotBlank()) put("after", after)
        if (before.isNotBlank()) put("before", before)
        if (days.isNotEmpty()) put("days", JSONArray(days))
        if (batteryBelow > 0) put("battery_below", batteryBelow)
        if (batteryAbove > 0) put("battery_above", batteryAbove)
        charging?.let { put("charging", it) }
        if (wifi.isNotBlank()) put("wifi", wifi)
    }

    /**
     * Why the conditions are not met right now, or null when they are.
     * [dayOfWeek] 1 = Monday … 7 = Sunday; [minute] = minutes since midnight; [wifiName] null = not on Wi-Fi.
     */
    fun fails(dayOfWeek: Int, minute: Int, battery: Int, isCharging: Boolean, wifiName: String?): String? {
        if (days.isNotEmpty() && dayOfWeek !in days) return "not one of its days"
        val a = mins(after); val b = mins(before)
        if (a != null && b != null) {
            val inside = if (a <= b) minute in a until b else (minute >= a || minute < b)
            if (!inside) return "only between $after and $before"
        } else if (a != null && minute < a) return "only after $after"
        else if (b != null && minute >= b) return "only before $before"
        if (batteryBelow > 0 && battery >= batteryBelow) return "only when the battery is below $batteryBelow%"
        if (batteryAbove > 0 && battery <= batteryAbove) return "only when the battery is above $batteryAbove%"
        if (charging != null && charging != isCharging) return if (charging) "only while charging" else "only when not charging"
        when {
            wifi.isBlank() -> {}
            wifi == "-" -> if (wifiName != null) return "only when not on Wi-Fi"
            wifi == "*" -> if (wifiName == null) return "only on Wi-Fi"
            else -> if (wifiName == null || !wifiName.contains(wifi, true)) return "only on Wi-Fi \"$wifi\""
        }
        return null
    }

    fun describe(): String {
        val out = mutableListOf<String>()
        if (days.isNotEmpty()) out += days.sorted().joinToString(" ") { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").getOrElse(it - 1) { "?" } }
        if (after.isNotBlank() && before.isNotBlank()) out += "$after–$before" else if (after.isNotBlank()) out += "after $after" else if (before.isNotBlank()) out += "before $before"
        if (batteryBelow > 0) out += "battery below $batteryBelow%"
        if (batteryAbove > 0) out += "battery above $batteryAbove%"
        charging?.let { out += if (it) "while charging" else "not charging" }
        when { wifi == "-" -> out += "not on Wi-Fi"; wifi == "*" -> out += "on Wi-Fi"; wifi.isNotBlank() -> out += "on Wi-Fi $wifi" }
        return out.joinToString(", ")
    }

    companion object {
        private fun mins(hhmm: String): Int? = Regex("^(\\d{1,2}):(\\d{2})$").find(hhmm.trim())?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

        fun fromJson(o: JSONObject?): Only? {
            o ?: return null
            val days = mutableListOf<Int>()
            o.optJSONArray("days")?.let { a -> for (i in 0 until a.length()) days += a.optInt(i) }
            val only = Only(
                o.optString("after"), o.optString("before"), days.filter { it in 1..7 },
                o.optInt("battery_below", 0).coerceIn(0, 100), o.optInt("battery_above", 0).coerceIn(0, 100),
                if (o.has("charging")) o.optBoolean("charging") else null, o.optString("wifi"),
            )
            return only.takeIf { !it.isEmpty }
        }
    }
}

/**
 * When a skill runs on its own.
 * type: "time" (daily | weekly | once), "notification" (app, from, contains), "event":
 * charging · unplugged · boot · app_opened:<app> · wifi_connected[:<name>] · wifi_disconnected ·
 * bluetooth_connected[:<device>] · bluetooth_disconnected[:<device>] · headphones_in · headphones_out ·
 * battery_below:<n> · battery_above:<n> · call_from[:<name>] · arrive:<place> · leave:<place> (with lat, lon, radius).
 */
class Trigger(
    val type: String,
    val time: String = "",
    val day: String? = null,
    val days: List<Int> = emptyList(),
    /** For type "time": "daily" | "weekly" | "once". */
    val repeat: String = "daily",
    val app: String = "",
    /** For type "notification": words in the message; several ways of saying it separated by "|". */
    val contains: String = "",
    val event: String = "",
    /** For type "notification": who it is from (the name the app shows), several separated by "|". */
    val from: String = "",
    /** "Only when…" conditions, for any type. */
    val only: Only? = null,
    /** For arrive:/leave: events: the place. */
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val radius: Int = 200,
    /** For type "notification": also react to messages in group chats. Off unless the user named a group. */
    val groups: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type); put("time", time); put("repeat", repeat)
        if (groups) put("groups", true)
        day?.let { put("day", it) }
        if (days.isNotEmpty()) put("days", JSONArray(days))
        if (app.isNotBlank()) put("app", app)
        if (contains.isNotBlank()) put("contains", contains)
        if (from.isNotBlank()) put("from", from)
        if (event.isNotBlank()) put("event", event)
        only?.takeIf { !it.isEmpty }?.let { put("only", it.toJson()) }
        if (lat != 0.0 || lon != 0.0) { put("lat", lat); put("lon", lon); put("radius", radius) }
    }

    /**
     * Whether a notification sets this trigger off: the app, the sender and the words all fit. Forgiving
     * about case, punctuation and extra words ("Where are you??" fits "where are you"); the sender also
     * fits inside a group title ("Family: Amma") or a group message ("Amma: where are you").
     * Returns null when it fits, else why not (for the trigger log).
     */
    fun misses(appLabel: String, pkg: String, title: String, text: String, group: Boolean = false): String? {
        if (type != "notification") return "not a notification trigger"
        if (app.isNotBlank() && !appLabel.contains(app, true) && !pkg.contains(app, true)) return "other app"
        // A group chat is many people: a skill made for a person stays out of it.
        if (group && !groups) return "a group chat (skills answer people, not groups)"
        val who = norm(title + " " + text.substringBefore(':', ""))
        if (from.isNotBlank() && from.split('|').flatMap { relatives(norm(it)) }.none { it.isNotBlank() && Regex("(^| )${Regex.escape(it)}( |$)").containsMatchIn(who) }) return "from someone else"
        val body = norm("$title $text")
        if (contains.isNotBlank() && contains.split('|').map { norm(it) }.none { alt ->
                alt.isNotBlank() && (body.contains(alt) || alt.split(' ').all { w -> body.split(' ').contains(w) })
            }) return "other words"
        return null
    }

    fun describe(): String = when (type) {
        "time" -> when (repeat) {
            "daily" -> "Daily · $time"
            "weekly" -> {
                val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                "Weekly · " + days.filter { it in 1..7 }.joinToString(" ") { names[it - 1] } + " · $time"
            }
            else -> "Once · ${day ?: "today"} $time"
        }
        "notification" -> if (from.isBlank()) "When ${app.ifBlank { "an app" }} notifies" + (if (contains.isNotBlank()) " about \"${contains.split('|').first()}\"" else "")
            else "When ${from.replace("|", " or ")} messages" + (if (app.isNotBlank()) " on $app" else "") + (if (contains.isNotBlank()) " saying \"${contains.split('|').first()}\"" else "") + (if (groups) " (groups too)" else "")
        "event" -> when {
            event == "charging" -> "When charging starts"
            event == "unplugged" -> "When unplugged"
            event == "boot" -> "When the phone starts"
            event.startsWith("app_opened:") -> "When ${event.removePrefix("app_opened:")} opens"
            event.startsWith("wifi_connected") -> "When Wi-Fi connects" + event.substringAfter(':', "").let { if (it.isBlank()) "" else " to $it" }
            event.startsWith("wifi_disconnected") -> "When Wi-Fi disconnects" + event.substringAfter(':', "").let { if (it.isBlank()) "" else " from $it" }
            event.startsWith("bluetooth_connected") -> "When " + event.substringAfter(':', "").ifBlank { "a Bluetooth device" } + " connects"
            event.startsWith("bluetooth_disconnected") -> "When " + event.substringAfter(':', "").ifBlank { "a Bluetooth device" } + " disconnects"
            event == "headphones_in" -> "When headphones are plugged in"
            event == "headphones_out" -> "When headphones are unplugged"
            event.startsWith("battery_below:") -> "When the battery drops below ${event.substringAfter(':')}%"
            event.startsWith("battery_above:") -> "When the battery goes above ${event.substringAfter(':')}%"
            event.startsWith("call_from") -> "When " + event.substringAfter(':', "").ifBlank { "someone" } + " calls"
            event.startsWith("arrive:") -> "When I reach ${event.substringAfter(':')}"
            event.startsWith("leave:") -> "When I leave ${event.substringAfter(':')}"
            else -> "On $event"
        }
        else -> "Manual"
    }.let { base -> only?.describe()?.takeIf { it.isNotBlank() }?.let { "$base · only $it" } ?: base }

    val isTime: Boolean get() = type == "time"

    companion object {
        fun fromJson(o: JSONObject): Trigger {
            val days = mutableListOf<Int>()
            o.optJSONArray("days")?.let { a -> for (i in 0 until a.length()) days += a.optInt(i) }
            // Old recipes stored {type: daily|weekly|once}; map them to the time trigger.
            val rawType = o.optString("type", "manual")
            val legacy = rawType in setOf("daily", "weekly", "once")
            return Trigger(
                type = if (legacy) "time" else rawType,
                time = o.optString("time", "09:00"),
                day = o.optString("day").ifBlank { null },
                days = days,
                repeat = if (legacy) rawType else o.optString("repeat", "daily"),
                app = o.optString("app"), contains = o.optString("contains"), event = o.optString("event"), from = o.optString("from"),
                only = Only.fromJson(o.optJSONObject("only")),
                lat = o.optDouble("lat", 0.0), lon = o.optDouble("lon", 0.0), radius = o.optInt("radius", 200).coerceIn(50, 5000),
                groups = o.optBoolean("groups", false),
            )
        }

        /**
         * Whether a notification title is a group chat's, going by how chat apps write them:
         * "Class 3B: Priya", "Priya @ Class 3B", "Family (3 messages): Amma". Used on top of what
         * Android itself says about the notification, because not every app says.
         */
        fun looksLikeGroup(title: String): Boolean {
            val t = title.trim()
            if (t.isBlank()) return false
            return Regex("\\S: \\S").containsMatchIn(t) || t.contains(" @ ") || Regex("\\(\\d+ (new )?messages?\\): ").containsMatchIn(t)
        }

        /** The kinds of event that carry a detail after a colon ("wifi_connected:Home"). */
        private val detailed = setOf("app_opened", "wifi_connected", "wifi_disconnected", "bluetooth_connected", "bluetooth_disconnected", "call_from", "arrive", "leave")

        /**
         * Whether the event a skill waits for ([want]) is the one that just happened ([event]).
         * "wifi_connected" waits for any Wi-Fi; "wifi_connected:Home" for a name containing "Home".
         */
        fun eventHit(want: String, event: String): Boolean {
            if (want.equals(event, true)) return true
            val wk = want.substringBefore(':').lowercase(); val ek = event.substringBefore(':').lowercase()
            if (wk != ek || wk !in detailed) return false
            val wd = want.substringAfter(':', "").trim(); val ed = event.substringAfter(':', "").trim()
            if (wd.isBlank()) return true
            if (wk == "call_from") return relatives(norm(wd)).any { it.isNotBlank() && Regex("(^| )${Regex.escape(it)}( |$)").containsMatchIn(norm(ed)) }
            return ed.contains(wd, true)
        }

        /** The ways people save family in their contacts: "mom" may be "Amma", "Mummy" or "Mother". */
        private val families = listOf(
            setOf("mom", "mother", "amma", "mummy", "mum", "maa", "ammi", "mommy", "அம்மா"),
            setOf("dad", "father", "appa", "daddy", "papa", "nanna", "அப்பா"),
            setOf("brother", "anna", "thambi", "bro", "annan", "அண்ணா", "தம்பி"),
            setOf("sister", "akka", "thangachi", "sis", "அக்கா", "தங்கச்சி"),
            setOf("wife", "wifey", "pondatti", "manaivi"),
            setOf("husband", "hubby", "purushan", "kanavar"),
        )
        internal fun relatives(name: String): List<String> = families.firstOrNull { name in it }?.toList() ?: listOf(name)

        /** Lower case, letters and digits only (any script), single spaces. */
        fun norm(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()
    }
}

/**
 * A saved automation. Either fixed [steps] (tool calls replayed without the AI) or a [goal]
 * the agent works out each time. Steps are tried first; if one fails the agent takes over with the goal.
 */
class Skill(
    val id: String,
    var name: String,
    var description: String,
    var goal: String,
    var steps: List<Part.Call>,
    var trigger: Trigger?,
    val createdAt: Long = System.currentTimeMillis(),
) {
    var enabled: Boolean = true
    var lastRun: Long = 0L
    var nextRun: Long = 0L
    var runs: Int = 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("description", description); put("goal", goal); put("created_at", createdAt)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
        trigger?.let { put("trigger", it.toJson()) }
        put("enabled", enabled); put("last_run", lastRun); put("next_run", nextRun); put("runs", runs)
    }

    /** The code view. */
    fun codeJson(): String = JSONObject().apply {
        put("name", name)
        put("when", trigger?.toJson() ?: "manual")
        put("goal", goal)
        put("do", JSONArray().apply { steps.forEach { put(JSONObject().put("tool", it.name).put("args", it.args)) } })
    }.toString(2)

    fun snapshot(): Skill = Skill(id, name, description, goal, steps, trigger, createdAt).also { c -> c.enabled = enabled; c.lastRun = lastRun; c.nextRun = nextRun; c.runs = runs }

    companion object {
        fun fromJson(o: JSONObject): Skill {
            val steps = mutableListOf<Part.Call>()
            o.optJSONArray("steps")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { s ->
                    // Accept both the Part.Call form and the old recipe step form {tool, args}.
                    val name = s.optString("name").ifBlank { s.optString("tool") }
                    if (name.isNotBlank()) steps += Part.Call(s.optString("id").ifBlank { newId() }, name, s.optJSONObject("args") ?: JSONObject())
                }
            }
            return Skill(
                o.optString("id", newId()), o.optString("name", "Skill"), o.optString("description"),
                o.optString("goal").ifBlank { o.optString("command") }, steps,
                o.optJSONObject("trigger")?.let { Trigger.fromJson(it) }, o.optLong("created_at", System.currentTimeMillis()),
            ).apply { enabled = o.optBoolean("enabled", true); lastRun = o.optLong("last_run"); nextRun = o.optLong("next_run"); runs = o.optInt("runs") }
        }
    }
}

/** A pending alarm or reminder, kept so it can be put back after a reboot. */
class Timer(val kind: String, val id: String, val at: Long, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("id", id).put("at", at).put("text", text)

    companion object {
        fun fromJson(o: JSONObject) = Timer(o.optString("kind"), o.optString("id"), o.optLong("at"), o.optString("text"))
    }
}
