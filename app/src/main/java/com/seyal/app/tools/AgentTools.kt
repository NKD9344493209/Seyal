package com.seyal.app.tools

import com.seyal.app.agent.Memory
import com.seyal.app.agent.Skills
import com.seyal.app.agent.Tool
import com.seyal.app.agent.ToolResult
import com.seyal.app.agent.noArgs
import com.seyal.app.agent.schema
import com.seyal.app.data.Part
import com.seyal.app.data.Trigger
import com.seyal.app.data.newId
import com.seyal.app.util.Times
import org.json.JSONArray
import org.json.JSONObject

/** Talking to the user, splitting work, remembering, and saving skills. */
object AgentTools {

    val all: List<Tool> by lazy {
        listOf(
            Tool(
                "ask_user", "agent",
                "Ask the user one short question and wait for the answer. Use only when something essential is missing or ambiguous (which person, what to say, which option). Offer 2-4 options when possible.",
                schema { str("question", "The question", required = true); strList("options", "Short options to tap") },
            ) { a, ctx ->
                val opts = a.optJSONArray("options")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
                val answer = ctx.askUser(a.optString("question"), opts.take(6))
                ToolResult.Done(JSONObject().put("answer", answer), "You said: ${answer.take(60)}")
            },

            Tool(
                "delegate", "agent",
                "Run 2-5 independent sub-goals at the same time, each by its own agent with all tools. Use for big multi-part requests (e.g. 'find shoes' + 'message Ram' + 'check mail'). Output: one result line per goal.",
                schema { strList("goals", "Each goal as a full sentence with everything it needs", required = true) },
            ) { a, ctx ->
                val goals = a.optJSONArray("goals")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
                if (goals.size < 1) return@Tool ToolResult.Failed("Give at least one goal")
                val results = ctx.delegate(goals.take(5))
                val ok = results.count { it.optString("status") == "done" }
                ToolResult.Done(JSONObject().put("results", JSONArray(results)), "$ok of ${results.size} sub-tasks done")
            },

            Tool(
                "memory_save", "agent",
                "Remember a lasting fact about the user for next time: who people are ('dad = Appa Jio'), preferences ('likes Broadway Cinemas'), places, routines. One fact per call.",
                schema { str("fact", "Short fact, e.g. 'Ram = Ram Kumar (project friend)'", required = true) },
            ) { a, _ -> Memory.add(a.optString("fact")); ToolResult.ok("Remembered: ${a.optString("fact").take(60)}") },

            Tool("memory_forget", "agent", "Forget remembered facts containing some text.", schema { str("text", "Text to match", required = true) }) { a, _ ->
                val n = Memory.forget(a.optString("text")); ToolResult.ok("Forgot $n fact(s)", "removed" to n)
            },

            Tool(
                "skill_save", "agent",
                "Create an automation from a description. goal = what to do, in words (an agent works it out each run). steps (optional) = exact tool calls to replay without the AI; in a notification-triggered skill their text args may use {{sender}}, {{text}}, {{app}}. trigger_json (optional): " +
                    "{\"type\":\"time\",\"repeat\":\"daily|weekly|once\",\"time\":\"HH:mm\",\"days\":[1-7 Mon-Sun],\"day\":\"YYYY-MM-DD\"} | {\"type\":\"notification\",\"app\":\"WhatsApp\",\"contains\":\"Amma\"} | {\"type\":\"event\",\"event\":\"charging|unplugged|boot|app_opened:YouTube\"}. " +
                    "Examples: 'every morning at 8 give me AI news' → goal + daily time trigger, no steps. 'When Amma messages on WhatsApp reply I'm in class' → notification trigger + one step notification_reply {match:'{{sender}}', app:'WhatsApp', text:\"I'm in class\"}.",
                schema {
                    str("name", "Short name, e.g. 'Morning AI news'", required = true)
                    str("description", "One line on what it does")
                    str("goal", "The goal in words, e.g. 'Fetch today's AI news and summarise 5 points'", required = true)
                    objList("steps", "Tool calls to replay in order") { str("tool", "Tool name", required = true); str("args_json", "The arguments as a JSON string", required = true) }
                    str("trigger_json", "The trigger as a JSON string, or empty for manual")
                },
            ) { a, _ ->
                val steps = mutableListOf<Part.Call>()
                a.optJSONArray("steps")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        val args = runCatching { JSONObject(s.optString("args_json", "{}")) }.getOrDefault(JSONObject())
                        if (s.optString("tool").isNotBlank()) steps += Part.Call(newId(), s.optString("tool"), args)
                    }
                }
                val trigger = a.optString("trigger_json").takeIf { it.isNotBlank() }?.let { runCatching { Trigger.fromJson(JSONObject(it)) }.getOrNull() }
                val skill = Skills.save(a.optString("name"), a.optString("description"), a.optString("goal"), steps, trigger)
                val whenText = trigger?.describe() ?: "manual"
                val next = if (skill.nextRun > 0) " · next ${Times.format(skill.nextRun)}" else ""
                ToolResult.ok("Saved skill \"${skill.name}\" · $whenText$next", "id" to skill.id, "when" to whenText, "steps" to steps.size)
            },

            Tool(
                "skill_save_this", "agent",
                "Save what you just did in THIS conversation as a skill (the user said 'save this as a skill', 'do this every day', 'remember how to do this'). The successful actions are recorded automatically; you only give a name and an optional trigger.",
                schema { str("name", "Short name", required = true); str("trigger_json", "Trigger as a JSON string (same format as skill_save), or empty for manual") },
            ) { a, ctx ->
                val session = com.seyal.app.agent.Agent.session(ctx.sessionId) ?: return@Tool ToolResult.Failed("Could not find this conversation")
                val skill = Skills.fromSession(session) ?: return@Tool ToolResult.Failed("Nothing here can be replayed. Use skill_save with a goal instead.")
                val trigger = a.optString("trigger_json").takeIf { it.isNotBlank() }?.let { runCatching { Trigger.fromJson(JSONObject(it)) }.getOrNull() }
                Skills.update(skill.id, a.optString("name").ifBlank { skill.name }, trigger)
                ToolResult.ok("Saved skill \"${a.optString("name").ifBlank { skill.name }}\" · ${skill.steps.size} steps · ${trigger?.describe() ?: "manual"}", "id" to skill.id, "steps" to skill.steps.size)
            },

            Tool("skill_list", "agent", "The user's saved skills.", noArgs) { _, _ ->
                val arr = JSONArray()
                Skills.all().forEach { s -> arr.put(JSONObject().put("name", s.name).put("description", s.description).put("when", s.trigger?.describe() ?: "manual").put("enabled", s.enabled).put("steps", s.steps.size)) }
                ToolResult.Done(JSONObject().put("skills", arr), "${arr.length()} skills")
            },

            Tool("skill_run", "agent", "Run a saved skill now by name.", schema { str("name", "Skill name", required = true) }) { a, _ ->
                val s = Skills.byName(a.optString("name")) ?: return@Tool ToolResult.Failed("No skill called \"${a.optString("name")}\"")
                val id = Skills.run(s.id) ?: return@Tool ToolResult.Failed("\"${s.name}\" is already running or Seyal is paused")
                ToolResult.ok("Started \"${s.name}\"", "session_id" to id)
            },

            Tool("skill_delete", "agent", "Delete a saved skill by name.", schema { str("name", "Skill name", required = true) }) { a, _ ->
                val s = Skills.byName(a.optString("name")) ?: return@Tool ToolResult.Failed("No skill called \"${a.optString("name")}\"")
                Skills.delete(s.id); ToolResult.ok("Deleted \"${s.name}\"")
            },
        )
    }
}
