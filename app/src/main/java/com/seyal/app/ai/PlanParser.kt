package com.seyal.app.ai

import com.seyal.app.data.Step
import com.seyal.app.data.Trigger
import org.json.JSONObject

/** What the AI decided for one command. */
class Plan(
    val mode: String,            // "now" | "schedule" | "answer" | "clarify"
    val title: String,
    val steps: List<Step>,
    val trigger: Trigger?,
    val answer: String?,
    val question: String?,
    val options: List<String>,
    val raw: String,
)

class PlanException(message: String) : Exception(message)

/**
 * Pure parsing of the AI's reply (no Android). The AI is not trusted to be tidy:
 * unknown tools are dropped, missing ids filled, bad and circular dependencies removed.
 */
object PlanParser {

    /** Pulls the first JSON object out of a model reply, even if wrapped in ``` fences or prose. */
    fun extractJson(text: String): JSONObject {
        val cleaned = text.replace("```json", "```").trim()
        val fenced = Regex("```\\s*([\\s\\S]*?)```").find(cleaned)?.groupValues?.get(1)?.trim()
        val candidate = fenced ?: cleaned
        runCatching { return JSONObject(candidate) }
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { return JSONObject(candidate.substring(start, end + 1)) }
        }
        throw PlanException("The AI did not return a plan. Try again or rephrase.")
    }

    fun parse(o: JSONObject, raw: String, knownTools: Set<String>): Plan {
        val steps = mutableListOf<Step>()
        val usedIds = HashSet<String>()
        o.optJSONArray("steps")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                if (s.optString("tool") !in knownTools) continue
                var id = s.optString("id").ifBlank { "s${i + 1}" }
                if (!usedIds.add(id)) { id = "s${i + 1}x"; usedIds.add(id) }
                s.put("id", id)
                steps += Step.fromJson(s)
            }
        }
        val ids = steps.map { it.id }.toSet()
        var cleaned = steps.map { st -> Step(st.id, st.tool, st.title.ifBlank { st.tool }, st.args, st.dependsOn.filter { it in ids && it != st.id }.distinct()) }
        cleaned = breakCycles(cleaned)

        val opts = mutableListOf<String>()
        o.optJSONArray("options")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { opts += it } }

        var mode = o.optString("mode", "now").lowercase()
        if (mode !in setOf("now", "schedule", "answer", "clarify")) mode = "now"
        val answer = o.optString("answer").ifBlank { null }?.takeUnless { it == "null" }
        val question = o.optString("question").ifBlank { null }?.takeUnless { it == "null" }
        if (mode == "now" && cleaned.isEmpty()) mode = if (answer != null) "answer" else "clarify"
        val trigger = o.optJSONObject("trigger")?.let { Trigger.fromJson(it) }
        if (mode == "schedule" && cleaned.isEmpty()) mode = "clarify"
        if (mode == "schedule" && trigger == null) mode = "now"
        if (mode == "answer" && answer == null) mode = if (cleaned.isNotEmpty()) "now" else "clarify"

        return Plan(
            mode = mode,
            title = o.optString("title").ifBlank { cleaned.firstOrNull()?.title ?: "Command" }.take(48),
            steps = cleaned,
            trigger = trigger,
            answer = answer,
            question = question ?: if (mode == "clarify") "What exactly should I do?" else null,
            options = opts,
            raw = raw,
        )
    }

    /** Drops any dependency that would make steps wait on each other forever. */
    fun breakCycles(steps: List<Step>): List<Step> {
        val deps = steps.associate { it.id to it.dependsOn.toMutableList() }
        val state = HashMap<String, Int>() // 0 = new, 1 = visiting, 2 = done
        fun visit(id: String) {
            state[id] = 1
            val list = deps[id] ?: return
            val it = list.iterator()
            while (it.hasNext()) {
                val d = it.next()
                when (state[d] ?: 0) {
                    1 -> it.remove() // back edge: this dependency closes a loop
                    0 -> visit(d)
                }
            }
            state[id] = 2
        }
        steps.forEach { if ((state[it.id] ?: 0) == 0) visit(it.id) }
        return steps.map { Step(it.id, it.tool, it.title, it.args, deps[it.id] ?: emptyList()) }
    }
}
