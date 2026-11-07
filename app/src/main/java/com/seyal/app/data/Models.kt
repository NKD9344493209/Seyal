package com.seyal.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Who decides for a tool: run on its own, ask first, or never run. */
enum class Level { AUTO, ASK, OFF }

enum class StepStatus {
    PENDING, WAITING_APPROVAL, WAITING_CHOICE, WAITING_RETURN, RUNNING, DONE, FAILED, SKIPPED, CANCELLED;

    val isWaiting: Boolean get() = this == WAITING_APPROVAL || this == WAITING_CHOICE || this == WAITING_RETURN
    val isFinished: Boolean get() = this == DONE || this == FAILED || this == SKIPPED || this == CANCELLED
}

enum class RunStatus { PLANNED, RUNNING, WAITING, DONE, PARTIAL, FAILED, CANCELLED;

    val isOpen: Boolean get() = this == PLANNED || this == RUNNING || this == WAITING
}

fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

/** One action in a plan. Steps with no unfinished dependency run at the same time. */
class Step(
    val id: String,
    val tool: String,
    val title: String,
    val args: JSONObject,
    val dependsOn: List<String>,
) {
    var status: StepStatus = StepStatus.PENDING
    var output: JSONObject? = null
    var error: String? = null
    var line: String? = null
    var attempts: Int = 0
    var approved: Boolean = false
    var choicePrompt: String? = null
    var choices: JSONArray? = null

    fun fresh(): Step = Step(id, tool, title, JSONObject(args.toString()), dependsOn)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("tool", tool)
        put("title", title)
        put("args", args)
        put("depends_on", JSONArray(dependsOn))
        put("status", status.name)
        output?.let { put("output", it) }
        error?.let { put("error", it) }
        line?.let { put("line", it) }
        put("attempts", attempts)
        put("approved", approved)
        choicePrompt?.let { put("choice_prompt", it) }
        choices?.let { put("choices", it) }
    }

    /** The plan-only view used in the code view and in saved recipes. */
    fun toPlanJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("tool", tool)
        put("title", title)
        put("args", args)
        put("depends_on", JSONArray(dependsOn))
    }

    companion object {
        fun fromJson(o: JSONObject): Step {
            val deps = mutableListOf<String>()
            o.optJSONArray("depends_on")?.let { arr -> for (i in 0 until arr.length()) deps += arr.optString(i) }
            return Step(
                id = o.optString("id", newId()),
                tool = o.optString("tool"),
                title = o.optString("title", o.optString("tool")),
                args = o.optJSONObject("args") ?: JSONObject(),
                dependsOn = deps,
            ).apply {
                status = runCatching { StepStatus.valueOf(o.optString("status", "PENDING")) }.getOrDefault(StepStatus.PENDING)
                output = o.optJSONObject("output")
                error = o.optString("error").ifBlank { null }
                line = o.optString("line").ifBlank { null }
                attempts = o.optInt("attempts", 0)
                approved = o.optBoolean("approved", false)
                choicePrompt = o.optString("choice_prompt").ifBlank { null }
                choices = o.optJSONArray("choices")
            }
        }
    }
}

/** One execution of a command or a recipe. */
class Run(
    val id: String,
    val command: String,
    val title: String,
    val createdAt: Long,
    val steps: MutableList<Step>,
    val recipeId: String? = null,
) {
    var status: RunStatus = RunStatus.PLANNED
    var approved: Boolean = false
    var summary: String? = null
    var finalText: String? = null
    var updatedAt: Long = createdAt
    /** Set when the AI needs one answer before it can plan. */
    var question: String? = null
    var options: List<String> = emptyList()
    /** "now" | "schedule" | "answer" | "clarify" */
    var mode: String = "now"

    fun step(id: String): Step? = steps.firstOrNull { it.id == id }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("command", command)
        put("title", title)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("recipe_id", recipeId ?: "")
        put("status", status.name)
        put("approved", approved)
        put("mode", mode)
        summary?.let { put("summary", it) }
        finalText?.let { put("final_text", it) }
        question?.let { put("question", it) }
        put("options", JSONArray(options))
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

    fun planJson(): String = JSONArray().apply { steps.forEach { put(it.toPlanJson()) } }.toString(2)

    companion object {
        fun fromJson(o: JSONObject): Run {
            val steps = mutableListOf<Step>()
            o.optJSONArray("steps")?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { steps += Step.fromJson(it) } }
            return Run(
                id = o.optString("id", newId()),
                command = o.optString("command"),
                title = o.optString("title"),
                createdAt = o.optLong("created_at", System.currentTimeMillis()),
                steps = steps,
                recipeId = o.optString("recipe_id").ifBlank { null },
            ).apply {
                status = runCatching { RunStatus.valueOf(o.optString("status", "DONE")) }.getOrDefault(RunStatus.DONE)
                approved = o.optBoolean("approved", false)
                summary = o.optString("summary").ifBlank { null }
                finalText = o.optString("final_text").ifBlank { null }
                updatedAt = o.optLong("updated_at", createdAt)
                question = o.optString("question").ifBlank { null }
                mode = o.optString("mode", "now")
                val opts = mutableListOf<String>()
                o.optJSONArray("options")?.let { a -> for (i in 0 until a.length()) opts += a.optString(i) }
                options = opts
                // A run that was in flight when the app died is not running any more.
                if (status.isOpen) {
                    steps.forEach { if (it.status == StepStatus.RUNNING) { it.status = StepStatus.FAILED; it.error = "App was closed while running" } }
                    status = if (steps.any { it.status.isWaiting }) RunStatus.WAITING else RunStatus.PARTIAL
                }
            }
        }
    }
}

/**
 * When a recipe runs.
 * type: "daily" | "weekly" | "once"
 * time: "HH:mm"
 * day:  for "once": "today" | "tomorrow" | weekday name | "YYYY-MM-DD"
 * days: for "weekly": 1 = Monday … 7 = Sunday
 */
class Trigger(
    val type: String,
    val time: String,
    val day: String? = null,
    val days: List<Int> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("time", time)
        day?.let { put("day", it) }
        if (days.isNotEmpty()) put("days", JSONArray(days))
    }

    fun describe(): String = when (type) {
        "daily" -> "Daily · $time"
        "weekly" -> {
            val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            "Weekly · " + days.filter { it in 1..7 }.joinToString(" ") { names[it - 1] } + " · $time"
        }
        else -> "Once · ${day ?: "today"} $time"
    }

    companion object {
        fun fromJson(o: JSONObject): Trigger {
            val days = mutableListOf<Int>()
            o.optJSONArray("days")?.let { arr -> for (i in 0 until arr.length()) days += arr.optInt(i) }
            return Trigger(
                type = o.optString("type", "once").lowercase(),
                time = o.optString("time", "09:00"),
                day = o.optString("day").ifBlank { null },
                days = days,
            )
        }
    }
}

/** A saved automation. Its steps are fixed: they run without asking the AI to plan again. */
class Recipe(
    val id: String,
    var name: String,
    var trigger: Trigger,
    val steps: List<Step>,
    val command: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    var enabled: Boolean = true
    var lastRun: Long = 0L
    var nextRun: Long = 0L

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("trigger", trigger.toJson())
        put("command", command)
        put("created_at", createdAt)
        put("enabled", enabled)
        put("last_run", lastRun)
        put("next_run", nextRun)
        put("steps", JSONArray().apply { steps.forEach { put(it.toPlanJson()) } })
    }

    /** What the code view shows. */
    fun codeJson(): String = JSONObject().apply {
        put("name", name)
        put("when", trigger.toJson())
        put("do", JSONArray().apply { steps.forEach { put(it.toPlanJson()) } })
        put("on_fail", JSONObject().put("retry", 2).put("then", "notify"))
    }.toString(2)

    companion object {
        fun fromJson(o: JSONObject): Recipe {
            val steps = mutableListOf<Step>()
            o.optJSONArray("steps")?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { steps += Step.fromJson(it) } }
            return Recipe(
                id = o.optString("id", newId()),
                name = o.optString("name", "Recipe"),
                trigger = Trigger.fromJson(o.optJSONObject("trigger") ?: JSONObject()),
                steps = steps,
                command = o.optString("command"),
                createdAt = o.optLong("created_at", System.currentTimeMillis()),
            ).apply {
                enabled = o.optBoolean("enabled", true)
                lastRun = o.optLong("last_run", 0L)
                nextRun = o.optLong("next_run", 0L)
            }
        }
    }
}

/** A pending alarm or reminder, kept so it survives a reboot. */
class Timer(val kind: String, val id: String, val at: Long, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("id", id).put("at", at).put("text", text)

    companion object {
        fun fromJson(o: JSONObject) = Timer(o.optString("kind"), o.optString("id"), o.optLong("at"), o.optString("text"))
    }
}
