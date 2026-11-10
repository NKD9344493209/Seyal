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
 * When a skill runs on its own.
 * type: "time" (daily | weekly | once, in [when]), "notification" (app, contains), "event" (charging, unplugged, boot, app_opened:<app>)
 */
class Trigger(
    val type: String,
    val time: String = "",
    val day: String? = null,
    val days: List<Int> = emptyList(),
    /** For type "time": "daily" | "weekly" | "once". */
    val repeat: String = "daily",
    val app: String = "",
    val contains: String = "",
    val event: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type); put("time", time); put("repeat", repeat)
        day?.let { put("day", it) }
        if (days.isNotEmpty()) put("days", JSONArray(days))
        if (app.isNotBlank()) put("app", app)
        if (contains.isNotBlank()) put("contains", contains)
        if (event.isNotBlank()) put("event", event)
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
        "notification" -> "When $app notifies" + (if (contains.isNotBlank()) " about \"$contains\"" else "")
        "event" -> when {
            event == "charging" -> "When charging starts"
            event == "unplugged" -> "When unplugged"
            event == "boot" -> "When the phone starts"
            event.startsWith("app_opened:") -> "When ${event.removePrefix("app_opened:")} opens"
            else -> "On $event"
        }
        else -> "Manual"
    }

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
                app = o.optString("app"), contains = o.optString("contains"), event = o.optString("event"),
            )
        }
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
