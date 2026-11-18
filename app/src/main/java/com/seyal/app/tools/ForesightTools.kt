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
