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
                "Ask the user and wait for the answer. Use when something essential is missing or ambiguous (which person, what to say, which option), and before any purchase, booking or order whose details the user didn't give: then put ALL the missing details in this one question as a numbered list (they answer in one line, e.g. \"chicken, 2, under 300\"). Offer 2-4 options when there is one choice.",
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
                "Create an automation. goal = what to do, in words (an agent works it out each run, with the trigger's details). " +
                    "steps (optional) = exact tool calls replayed without the AI (instant, works offline): give them whenever every value is fixed; in a message-triggered skill their text args may use {{sender}}, {{text}}, {{app}}. trigger_json (optional), one of: " +
                    "{\"type\":\"time\",\"repeat\":\"daily|weekly|once\",\"time\":\"HH:mm\",\"days\":[1-7 Mon-Sun],\"day\":\"YYYY-MM-DD\"} | " +
                    "{\"type\":\"notification\",\"app\":\"WhatsApp\",\"from\":\"Amma\",\"contains\":\"where are you|where r u|enga irukka\"} | " +
                    "{\"type\":\"event\",\"event\":\"E\"} with E = charging | unplugged | boot | app_opened:YouTube | wifi_connected[:Name] | wifi_disconnected[:Name] | bluetooth_connected[:Device] | bluetooth_disconnected[:Device] | headphones_in | headphones_out | battery_below:20 | battery_above:90 | call_from[:Name] | arrive:Place | leave:Place. " +
                    "arrive/leave: add \"place\":\"an address or place name\" (or \"here\" for where the phone is now) and optional \"radius\" in metres. " +
                    "Any trigger may add \"only\": {\"after\":\"HH:mm\",\"before\":\"HH:mm\",\"days\":[1-7],\"battery_below\":n,\"battery_above\":n,\"charging\":true|false,\"wifi\":\"Name\" (\"*\" any Wi-Fi, \"-\" not on Wi-Fi)} for conditions like 'only on weekdays', 'only after 9 PM', 'only at home (home Wi-Fi)'. " +
                    "Message triggers: from = the person's name exactly as saved in contacts (contacts_search first: 'mom' may be 'Amma'); contains = the words with a few other ways of saying them separated by |; empty = any message from them. " +
                    "A skill that replies MUST have from (who it answers): never leave it empty unless the user clearly said 'anyone' / 'everyone'. It answers only that person, only in their own chat, one message per run; the user is asked once to allow it. " +
                    "Group chats are ignored unless \"groups\":true (only when the user named a group), and Seyal never sends into a group by itself: there it can only tell the user (notify / say). " +
                    "Examples: 'every morning at 8 give me AI news' → goal + daily time trigger. " +
                    "'When Amma messages on WhatsApp reply I'm in class' → notification trigger + one step notification_reply {match:'{{sender}}', app:'WhatsApp', text:\"I'm in class\"}. " +
                    "'When mom asks where I am, send my location' → notification trigger {from Amma, contains 'where are you|where r u|enga irukka'} + goal 'Get my location with location, then notification_reply to the sender with the place and map_link'. " +
                    "'When I reach home turn Wi-Fi panel on and message Amma' → event arrive:Home with place. 'At night when battery is below 20 turn on DND' → event battery_below:20 + only {after 21:00}. " +
                    "'Tomorrow 8 am send Ram a meeting link' → time trigger repeat once with day + goal. " +
                    "Tell the user exactly when it will run.",
                schema {
                    str("name", "Short name, e.g. 'Morning AI news'", required = true)
                    str("description", "One line on what it does")
                    str("goal", "The goal in words, e.g. 'Fetch today's AI news and summarise 5 points'", required = true)
                    objList("steps", "Tool calls to replay in order") { str("tool", "Tool name", required = true); str("args_json", "The arguments as a JSON string", required = true) }
                    str("trigger_json", "The trigger as a JSON string, or empty for manual")
                },
            ) { a, ctx ->
                val steps = mutableListOf<Part.Call>()
                a.optJSONArray("steps")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        val args = runCatching { JSONObject(s.optString("args_json", "{}")) }.getOrDefault(JSONObject())
                        if (s.optString("tool").isNotBlank()) steps += Part.Call(newId(), s.optString("tool"), args)
                    }
                }
                var trigger = a.optString("trigger_json").takeIf { it.isNotBlank() }?.let { runCatching { Trigger.fromJson(JSONObject(it)) }.getOrNull() }
                // A message trigger tied to an app that is not on this phone would never fire: listen to every app instead.
                if (trigger != null && trigger.type == "notification" && trigger.app.isNotBlank()) {
                    val labels = runCatching { com.seyal.app.agent.Preflight.appLabels() }.getOrDefault(emptyList())
                    val wanted = trigger.app
                    if (labels.isNotEmpty() && labels.none { it.contains(wanted, true) }) trigger = Trigger.fromJson(trigger.toJson().put("app", ""))
                }
                // A skill that answers people by itself when they message or call: the user agrees once, knowing exactly who gets answered.
                val isSend = { n: String -> com.seyal.app.agent.Toolbox.get(n)?.let { it.group == "comms" && it.sideEffect } == true }
                val answering = trigger != null && (trigger.type == "notification" || (trigger.type == "event" && trigger.event.startsWith("call_from"))) &&
                    com.seyal.app.agent.AutoGuard.sends(a.optString("goal"), steps.map { it.name }, isSend)
                if (answering && trigger != null) {
                    // Never into a group by itself, whatever was asked.
                    if (trigger.groups) trigger = Trigger.fromJson(trigger.toJson().put("groups", false))
                    val tr = trigger!!
                    val who = when {
                        tr.type == "event" -> tr.event.substringAfter(':', "").ifBlank { "ANYONE who calls you" }
                        tr.from.isNotBlank() -> tr.from.replace("|", " or ")
                        else -> "ANYONE who messages you" + (if (tr.app.isNotBlank()) " on ${tr.app}" else "")
                    }
                    val what = steps.firstOrNull { isSend(it.name) }?.args?.optString("text")?.takeIf { it.isNotBlank() }?.let { " with: \"${it.take(120)}\"" } ?: ""
                    val words = if (tr.type == "notification" && tr.contains.isNotBlank()) " when the message says \"${tr.contains.split('|').first()}\"" else ""
                    val answer = ctx.askUser(
                        "Seyal will answer $who BY ITSELF$words$what.\n• Only in their own chat, never in a group chat\n• One answer per chat, then quiet for ${if (tr.contains.isNotBlank()) "10 minutes" else "an hour"}\n• You get a notification each time\nTurn this on?",
                        listOf("Yes, turn it on", "No"),
                    )
                    if (!Regex("^(y|ok|sure|sari|aam|turn|the first|first|go ahead|do it|on\\b)", RegexOption.IGNORE_CASE).containsMatchIn(answer.trim())) return@Tool ToolResult.Failed("The user did not allow the automatic reply. Nothing was saved. Ask what they want changed (who it should answer, or what it should say).")
                    com.seyal.app.data.Settings.autoReplies = true
                }
                // A skill that will send something by itself at a set time: the user confirms the exact message once, now.
                val sends = if (answering) emptyList() else steps.filter { com.seyal.app.agent.Toolbox.get(it.name)?.group == "comms" && !it.args.toString().contains("{{sender}}") }
                if (sends.isNotEmpty()) {
                    val what = sends.joinToString("\n") { st -> "• " + (runCatching { com.seyal.app.agent.Toolbox.get(st.name)!!.preview(st.args) }.getOrNull()?.ifBlank { null } ?: com.seyal.app.agent.Prompts.friendly(st)) }
                    val answer = ctx.askUser("Schedule this to be sent by itself (${trigger?.describe() ?: "when you run the skill"})?\n$what", listOf("Yes, schedule it", "No"))
                    if (!Regex("^(y|ok|sure|sari|aam|schedule|the first|first|go ahead|do it)", RegexOption.IGNORE_CASE).containsMatchIn(answer.trim())) return@Tool ToolResult.Failed("The user did not confirm the scheduled send. Ask what they want changed.")
                }
                // A place skill needs the spot on the map: "here", or the address looked up.
                if (trigger != null && trigger.type == "event" && (trigger.event.startsWith("arrive:") || trigger.event.startsWith("leave:")) && trigger.lat == 0.0 && trigger.lon == 0.0) {
                    val raw = runCatching { JSONObject(a.optString("trigger_json")) }.getOrDefault(JSONObject())
                    val place = raw.optString("place").ifBlank { trigger.event.substringAfter(':') }
                    val spot = PhoneTools.locate(place) ?: return@Tool ToolResult.Failed("Could not find \"$place\" on the map. Ask the user for the address, or to say \"here\" while they are at the place (Location must be on).")
                    trigger = Trigger.fromJson(raw.put("lat", spot.first).put("lon", spot.second))
                }
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

            Tool(
                "lesson_save", "agent",
                "Save a short how-to about an app for next time (where a button is, what works, what to avoid): use it when the user teaches you how they want something done in an app, or after you found the way round a problem. General method only, no personal details.",
                schema { str("app", "App name, e.g. 'Swiggy'", required = true); str("lesson", "One line", required = true) },
            ) { a, _ ->
                if (com.seyal.app.agent.Lessons.add(a.optString("app"), a.optString("lesson"))) ToolResult.ok("Noted for ${a.optString("app")}")
                else ToolResult.Failed("Not saved: give an app name and a one-line method (no numbers or personal details).")
            },

            Tool(
                "seats_check", "agent",
                "After selecting seats (movie, bus, train) and before the payment step: give the seat labels the screen shows as selected and how many tickets the user wants. Tells you whether they are next to each other in one row and the count is right, and what to do if not.",
                schema { strList("seats", "The selected seats as shown, e.g. [\"A5\", \"A6\"]", required = true); int("wanted", "How many tickets the user asked for", required = true); bool("user_agreed_apart", "true only if the user said separate seats are fine") },
            ) { a, _ ->
                val raw = a.optJSONArray("seats")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
                val labels = raw.flatMap { com.seyal.app.agent.Seats.split(it) }
                val v = com.seyal.app.agent.Seats.check(labels, a.optInt("wanted"))
                if (!v.ok) return@Tool ToolResult.Failed(v.message)
                val fine = v.together || a.optBoolean("user_agreed_apart")
                ToolResult.Done(
                    JSONObject().put("ok", true).put("together", v.together).put("fine", fine).put("result", if (v.together || !fine) v.message else "Separate seats, as the user agreed: ${labels.joinToString(", ")}."),
                    if (v.together) "Seats ${labels.joinToString(", ")} are together" else if (fine) "Separate seats (you agreed): ${labels.joinToString(", ")}" else "Seats ${labels.joinToString(", ")} are not together",
                )
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
