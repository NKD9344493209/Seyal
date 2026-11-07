package com.seyal.app.engine

import com.seyal.app.data.RunStatus
import com.seyal.app.data.Step
import com.seyal.app.data.StepStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure lane logic (no Android): which steps can run now, which can never run,
 * how outputs flow into later steps, and how a finished run is summed up.
 */
object Lanes {

    private val dead = setOf(StepStatus.FAILED, StepStatus.SKIPPED, StepStatus.CANCELLED)

    /** Marks pending steps whose dependency failed / was skipped / cancelled. Repeats until stable. */
    fun skipBlocked(steps: List<Step>) {
        val byId = steps.associateBy { it.id }
        var changed = true
        while (changed) {
            changed = false
            for (s in steps) {
                if (s.status != StepStatus.PENDING) continue
                if (s.dependsOn.mapNotNull { byId[it] }.any { it.status in dead }) {
                    s.status = StepStatus.SKIPPED
                    s.error = "Skipped: an earlier step didn't finish"
                    changed = true
                }
            }
        }
    }

    /** Pending steps whose dependencies are all done. These start at the same time. */
    fun ready(steps: List<Step>, alreadyLaunched: (Step) -> Boolean = { false }): List<Step> {
        val byId = steps.associateBy { it.id }
        return steps.filter { s ->
            s.status == StepStatus.PENDING &&
                s.dependsOn.mapNotNull { byId[it] }.all { it.status == StepStatus.DONE } &&
                !alreadyLaunched(s)
        }
    }

    /**
     * Safety net: if nothing is running or waiting and nothing is ready, any pending step
     * is stuck forever (e.g. a dependency loop). Fail those instead of hanging.
     */
    fun failStuck(steps: List<Step>, anythingRunning: Boolean): Boolean {
        if (anythingRunning) return false
        if (steps.any { it.status.isWaiting || it.status == StepStatus.RUNNING }) return false
        if (ready(steps).isNotEmpty()) return false
        val stuck = steps.filter { it.status == StepStatus.PENDING }
        stuck.forEach { it.status = StepStatus.FAILED; it.error = "Can't start: its steps wait on each other" }
        return stuck.isNotEmpty()
    }

    /** True when no step can make progress any more. */
    fun isFinished(steps: List<Step>): Boolean = steps.all { it.status.isFinished }

    // ---------------------------------------------------------------- references

    private val ref = Regex("\\{\\{\\s*([A-Za-z0-9_]+)(?:\\.([A-Za-z0-9_.]+))?\\s*\\}\\}")

    /** Replaces {{s1.phone}}, {{s2.items.0.title}} or {{s3}} with earlier outputs. Unknown refs stay as written. */
    fun substitute(args: JSONObject, steps: List<Step>): JSONObject {
        val byId = steps.associateBy { it.id }
        val out = JSONObject()
        val keys = args.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = args.opt(k)
            out.put(k, if (v is String) substituteText(v, byId) else v)
        }
        return out
    }

    fun substituteText(text: String, byId: Map<String, Step>): String = ref.replace(text) { m ->
        val step = byId[m.groupValues[1]] ?: return@replace m.value
        val output = step.output ?: return@replace ""
        val path = m.groupValues[2]
        if (path.isBlank()) return@replace output.toString()
        var cur: Any? = output
        for (part in path.split('.')) {
            cur = when (val c = cur) {
                is JSONObject -> c.opt(part)
                is JSONArray -> part.toIntOrNull()?.let { c.opt(it) }
                else -> null
            }
        }
        if (cur == null || cur == JSONObject.NULL) "" else cur.toString()
    }

    // ---------------------------------------------------------------- outcome

    class Outcome(val status: RunStatus, val summary: String, val finalText: String)

    fun outcome(steps: List<Step>): Outcome {
        val total = steps.size
        val done = steps.count { it.status == StepStatus.DONE }
        val failed = steps.count { it.status == StepStatus.FAILED || it.status == StepStatus.SKIPPED }
        val cancelled = steps.count { it.status == StepStatus.CANCELLED }
        val status = when {
            cancelled > 0 && done == 0 -> RunStatus.CANCELLED
            failed == 0 && cancelled == 0 -> RunStatus.DONE
            done == 0 -> RunStatus.FAILED
            else -> RunStatus.PARTIAL
        }
        val summary = summaryLine(done, failed + cancelled, total)
        val aiText = steps.filter { it.status == StepStatus.DONE && it.tool == "ai" }
            .mapNotNull { it.output?.optString("text")?.trim()?.ifBlank { null } }
        val lines = steps.filter { it.tool != "ai" }.map { s ->
            when (s.status) {
                StepStatus.DONE -> "✓ ${s.line ?: s.title}"
                StepStatus.FAILED -> "✗ ${s.title}: ${s.error ?: "failed"}"
                StepStatus.SKIPPED -> "– ${s.title}: skipped"
                StepStatus.CANCELLED -> "– ${s.title}: cancelled"
                else -> "• ${s.title}"
            }
        }
        // Answer first (what the user asked for), then the short log.
        val text = (aiText + (if (aiText.isNotEmpty() && lines.isNotEmpty()) listOf("") else emptyList()) + lines).joinToString("\n").trim()
        return Outcome(status, summary, text.ifBlank { summary })
    }

    fun summaryLine(done: Int, notDone: Int, total: Int): String = when {
        total == 0 -> "Nothing to do"
        notDone == 0 && total == 1 -> "Done"
        notDone == 0 -> "All $total tasks done"
        done == 0 -> if (total == 1) "Failed" else "$total tasks failed"
        else -> "$done of $total done · $notDone didn't"
    }
}
