package com.seyal.app.tools

import com.seyal.app.agent.Agent
import com.seyal.app.agent.Foresight
import com.seyal.app.agent.ForesightRun
import com.seyal.app.agent.ForesightStore
import com.seyal.app.agent.Tool
import com.seyal.app.agent.ToolResult
import com.seyal.app.agent.schema
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Dirghadarshi mode from a chat: "what is coming in AI?", "run dirghadarshi", "how did your predictions turn out?". */
object ForesightTools {
    val all: List<Tool> by lazy {
        listOf(
            Tool(
                "awareness", "phone",
                "Dirghadarshi's awareness: EVERY headline that matters on a topic or in the user's interests, read from five angles (watch out, new, opportunities, research, rules and money), the same story counted once, with source, date and link. No AI writes these: they are the publishers' own headlines. " +
                    "Use it for \"any news on X\", \"what is new in X\", \"latest on X\", \"trends in X\", \"brief\", \"what should I know today\". " +
                    "action: news (topic required) | brief (all the user's interests) | more (the next page of the last list) | detail (n: the number of one headline of the last list) | interests_list | interests_set | interests_add | interests_remove (items: comma-separated) | daily_on | daily_off. " +
                    "Give the user the `say` text EXACTLY as it is, every numbered line: never shorten it, re-rank it, drop lines or add lines. Details of one headline only when they ask (\"more on 2\"): action detail with n, never read_page on a list link (those are Google News addresses that only a browser can follow). detail also returns `text`, the story's own words, for when the user wants it explained.",
                schema {
                    str("action", "news | brief | more | detail | interests_list | interests_set | interests_add | interests_remove | daily_on | daily_off", required = true)
                    str("topic", "For news: the topic in the user's words, e.g. \"AI\", \"Java\", \"gold price\"")
                    str("items", "For interests_set/add/remove: the interests, comma-separated")
                    int("days", "For news: how many days back (default 7)")
                    int("n", "For detail: the headline's number in the last list")
                },
            ) { a, _ ->
                val items = a.optString("items").split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }
                var story: com.seyal.app.agent.AwareRun.Detail? = null
                val say = when (a.optString("action").lowercase()) {
                    "more" -> com.seyal.app.agent.AwareRun.more()
                    "detail" -> com.seyal.app.agent.AwareRun.detail(a.optInt("n", 0)).also { story = it }.say
                    "news" -> {
                        val topic = a.optString("topic").trim()
                        if (topic.length < 2) return@Tool ToolResult.Failed("Give the topic, e.g. topic: \"AI\"")
                        com.seyal.app.agent.AwareRun.news(topic, a.optInt("days", 7).coerceIn(1, 30))
                    }
                    "brief" -> com.seyal.app.agent.AwareRun.brief()?.text ?: com.seyal.app.agent.Aware.NO_INTERESTS
                    "interests_set" -> { com.seyal.app.agent.AwareStore.set(items); com.seyal.app.agent.Aware.interestsSaid(com.seyal.app.agent.AwareStore.interests, com.seyal.app.agent.AwareStore.daily) }
                    "interests_add" -> { com.seyal.app.agent.AwareStore.add(items); com.seyal.app.agent.Aware.interestsSaid(com.seyal.app.agent.AwareStore.interests, com.seyal.app.agent.AwareStore.daily) }
                    "interests_remove" -> { com.seyal.app.agent.AwareStore.remove(items); com.seyal.app.agent.Aware.interestsSaid(com.seyal.app.agent.AwareStore.interests, com.seyal.app.agent.AwareStore.daily) }
                    "interests_list" -> com.seyal.app.agent.Aware.interestsSaid(com.seyal.app.agent.AwareStore.interests, com.seyal.app.agent.AwareStore.daily)
                    "daily_on", "daily_off" -> {
                        val on = a.optString("action").endsWith("on")
                        com.seyal.app.agent.AwareStore.setDaily(on)
                        runCatching { com.seyal.app.agent.Scheduler.syncBackground(Agent.app) }
                        if (!on) "The daily brief is off. Say \"brief\" whenever you want one."
                        else if (com.seyal.app.agent.AwareStore.interests.isEmpty()) "The daily brief is on, but I do not know your interests yet. " + com.seyal.app.agent.Aware.NO_INTERESTS
                        else com.seyal.app.agent.Aware.interestsSaid(com.seyal.app.agent.AwareStore.interests, true)
                    }
                    else -> return@Tool ToolResult.Failed("action must be news, brief, more, detail, interests_list, interests_set, interests_add, interests_remove, daily_on or daily_off")
                }
                val out = JSONObject().put("ok", true).put("say", say)
                story?.let { d -> if (d.text.isNotBlank()) out.put("text", d.text); if (d.url.isNotBlank()) out.put("url", d.url) }
                ToolResult.Done(out, say.lineSequence().firstOrNull().orEmpty().replace("**", "").take(90))
            },

            Tool(
                "foresight", "foresight",
                "Dirghadarshi mode: what is starting to rise in the world (AI, tech, jobs, money), from this week's public signals, with proof, what it means for the user, one thing to do, and a prediction with a date. " +
                    "action: latest = the newest report (made within the week; scans if there is none). scan = read fresh signals now (takes about half a minute). record = how the earlier predictions turned out. " +
                    "Give the user the trends with their confidence, the proof and what to do; say plainly that it is a reading of signals, not certainty. Never add investment advice.",
                schema { str("action", "latest | scan | record", enum = listOf("latest", "scan", "record")) },
            ) { a, _ ->
                val action = a.optString("action").ifBlank { "latest" }
                val calls = ForesightStore.calls()
                if (action == "record") return@Tool ToolResult.Done(
                    JSONObject().put("record", Foresight.record(calls)).put("calls", JSONArray(calls.takeLast(15).map { c -> JSONObject().put("made", c.madeOn).put("call", c.text).put("check_by", c.checkBy).put("verdict", c.verdict.ifBlank { "open" }).put("note", c.note) })),
                    Foresight.record(calls),
                )
                val have = ForesightStore.latest()?.takeIf { action == "latest" && it.day >= LocalDate.now().minusDays(7).toString() }
                val report = have ?: ForesightRun.run(Agent.app).let { out -> out.report ?: return@Tool ToolResult.Failed(out.error.ifBlank { "The scan did not finish." }, retryable = true) }
                ToolResult.Done(
                    JSONObject().put("made_on", report.day).put("report", Foresight.text(report, ForesightStore.calls(), full = true))
                        .put("links", JSONArray(report.trends.flatMap { t -> t.proof.take(2).map { p -> JSONObject().put("trend", t.title).put("title", p.title.take(80)).put("url", p.url) } })),
                    "${report.trends.size} trends from ${report.signals} signals" + if (have != null) " (report of ${report.day})" else "",
                )
            },
        )
    }
}
