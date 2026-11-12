package com.seyal.app.tools

import com.seyal.app.agent.Catcher
import com.seyal.app.agent.Inbox
import com.seyal.app.agent.Tool
import com.seyal.app.agent.ToolContext
import com.seyal.app.agent.ToolResult
import com.seyal.app.agent.Toolbox
import com.seyal.app.agent.Watcher
import com.seyal.app.agent.Watchers
import com.seyal.app.agent.schema
import com.seyal.app.agent.noArgs
import com.seyal.app.data.Level
import com.seyal.app.data.Settings
import com.seyal.app.data.newId
import com.seyal.app.device.Busy
import com.seyal.app.device.BusyRules
import com.seyal.app.device.Notify
import com.seyal.app.tools.Common.ctx
import com.seyal.app.util.Parsers
import com.seyal.app.util.Times
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/** The things Seyal does while the user is not looking: what it caught from messages, pages it watches, the morning summary, busy mode. */
object AssistTools {
    private fun caughtJson(now: Long) = JSONArray(Inbox.open(now).map { c ->
        JSONObject().put("id", c.id).put("kind", c.kind).put("what", c.title).put("due", Catcher.whenText(c.due, c.hasTime, now)).put("from", c.source)
            .also { if (c.link.isNotBlank()) it.put("link", c.link); if (c.amount > 0) it.put("amount", c.amount) }
    })

    private val quiet = ToolContext("assist", { emptyList() }, { _, _ -> "" })
    private suspend fun lineOf(tool: String, args: JSONObject): ToolResult.Done? = runCatching { Toolbox.get(tool)?.takeIf { it.available() }?.run?.invoke(args, quiet) as? ToolResult.Done }.getOrNull()

    /** The morning summary, put together on the phone with no AI: day, weather, calendar, what is due, watched pages, missed calls. */
    suspend fun brief(): String {
        val now = System.currentTimeMillis()
        val lines = mutableListOf<String>()
        val today = LocalDateTime.now()
        lines += today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", java.util.Locale.ENGLISH))
        if (Settings.homeCity.isNotBlank()) lineOf("weather", JSONObject().put("place", Settings.homeCity))?.let { lines += "Weather: ${it.line}" }
        val end = today.toLocalDate().atTime(23, 59)
        lineOf("calendar_list", JSONObject().put("from", today.toString().take(16)).put("to", end.toString().take(16)))?.output?.optJSONArray("events")?.let { ev ->
            if (ev.length() == 0) lines += "Calendar: nothing today"
            else { lines += "Today:"; for (i in 0 until minOf(ev.length(), 6)) ev.optJSONObject(i)?.let { e -> lines += "• ${e.optString("start").substringAfter('T').take(5)} ${e.optString("title")}".trim() } }
        }
        val due = Inbox.open(now).filter { it.due < now + 3L * 24 * 3600_000L }
        if (due.isNotEmpty()) { lines += "Due soon:"; due.take(6).forEach { c -> lines += "• ${Catcher.whenText(c.due, c.hasTime, now)}: ${c.title}" } }
        val later = Inbox.open(now).size - due.size
        if (later > 0) lines += "$later more caught for later (Skills → Caught for you)"
        val hits = Watchers.all().filter { it.hitAt > now - 24 * 3600_000L }
        hits.forEach { lines += "• Watched: ${it.what} happened" }
        val watching = Watchers.active().size
        if (watching > 0) lines += "Watching $watching page${if (watching == 1) "" else "s"} for you"
        Busy.missed().takeIf { it.isNotEmpty() }?.let { lines += "Missed calls to return: ${BusyRules.summary(it)}" }
        return lines.joinToString("\n")
    }

    val all: List<Tool> by lazy {
        listOf(
            Tool(
                "inbox_list", "agent",
                "Deadlines, bills, renewals and return windows Seyal caught from the user's messages (form links included), soonest first. Use for: \"what deadlines do I have\", \"any bills due\", \"what did I miss in the groups\".",
                noArgs,
            ) { _, _ ->
                val now = System.currentTimeMillis()
                val items = caughtJson(now)
                ToolResult.Done(JSONObject().put("ok", true).put("items", items).put("reading_messages", Settings.catchMessages), if (items.length() == 0) "Nothing waiting" else "${items.length()} thing${if (items.length() == 1) "" else "s"} caught")
            },

            Tool(
                "inbox_done", "agent", "Mark something from inbox_list as done, or as not for the user (it stops reminding).",
                schema { str("id", "The item's id from inbox_list", required = true); bool("not_for_me", "true = it does not concern the user") },
            ) { a, _ ->
                val c = Inbox.mark(a.optString("id"), if (a.optBoolean("not_for_me")) "dismissed" else "done") ?: return@Tool ToolResult.Failed("No such item. Call inbox_list for the ids.")
                ToolResult.ok("Marked done: ${c.title.take(50)}")
            },

            Tool(
                "watch_add", "agent",
                "Keep checking a web page for the user and tell them the moment something happens: bookings open, a product is back in stock, a price drops, results or a notice appear. Checked in the background with no AI, every 15 minutes to once a day. " +
                    "First find the exact page (web_search / read_page) and the exact words that will appear on it. mode: contains = tell when the words appear; gone = when they disappear (e.g. \"Sold out\", \"Coming soon\"); price_below = when the price after `near` is at or under value; changed = when the part after `near` changes. " +
                    "The page is read once now to make sure it can be watched: if this fails, try another page, or tell the user it cannot be watched (pages that need a login or are drawn by an app cannot).",
                schema {
                    str("what", "What the user is waiting for, in their words", required = true)
                    str("url", "The page to check, https://…", required = true)
                    str("mode", "How to tell", required = true, enum = listOf("contains", "gone", "price_below", "changed"))
                    str("value", "contains / gone: the exact words. price_below: the price limit in rupees")
                    str("near", "price_below / changed: a few words on the page just before the part that matters (the product's name)")
                    int("every_minutes", "How often to check: 15 to 1440 (default 60)")
                },
            ) { a, _ ->
                val url = a.optString("url").trim()
                val mode = a.optString("mode")
                val value = a.optString("value").trim()
                if (!url.startsWith("http")) return@Tool ToolResult.Failed("Give the page's full address (https://…).")
                if (mode in setOf("contains", "gone") && value.length < 2) return@Tool ToolResult.Failed("Say the exact words to look for in value.")
                if (mode == "price_below" && value.none { it.isDigit() }) return@Tool ToolResult.Failed("Say the price limit in value.")
                if (Watchers.active().size >= Watchers.MAX) return@Tool ToolResult.Failed("Already watching ${Watchers.MAX} pages. Remove one first (watch_list, watch_remove).")
                val page = withContext(Dispatchers.IO) { runCatching { Parsers.strip(Common.http(url, timeoutMs = 15_000)) } }
                val text = page.getOrNull()
                if (text == null || !Watchers.readable(text)) return@Tool ToolResult.Failed(
                    "This page cannot be read in the background (" + (page.exceptionOrNull()?.message ?: "it came back empty: it is drawn by scripts or needs a login") + "). Try another page that shows the same thing as plain text, or tell the user this one cannot be watched.",
                )
                val w = Watcher(newId(), a.optString("what").take(100), url, mode, value, a.optString("near").trim(), a.optInt("every_minutes", 60).coerceIn(Watchers.MIN_EVERY, 1440), created = System.currentTimeMillis())
                val now = Watchers.evaluate(w, text)
                // Already true: there is nothing to wait for.
                if (now.hit && mode != "changed") return@Tool ToolResult.ok("Already so: ${now.note}", "already" to true, "now" to now.note, "note" to "Nothing was set up, because it is already the case. Tell the user, with the link.", "link" to url)
                if (mode == "price_below" && now.seen.isBlank()) return@Tool ToolResult.Failed("${now.note} Give `near`: a few words that sit just before the price on that page.")
                if (mode == "changed" && now.seen == "missing") return@Tool ToolResult.Failed("${now.note} Give words that are on the page now.")
                w.lastSeen = now.seen; w.lastCheck = System.currentTimeMillis()
                Watchers.add(w)
                ToolResult.ok("Watching: ${w.what}", "id" to w.id, "now" to now.note, "checks" to "every ${w.everyMin} minutes", "tells_you" to w.rule())
            },

            Tool("watch_list", "agent", "The pages Seyal is watching for the user, and what each last showed.", noArgs) { _, _ ->
                val arr = JSONArray(Watchers.all().map { w ->
                    JSONObject().put("id", w.id).put("what", w.what).put("tells_you", w.rule()).put("on", w.enabled).put("every_minutes", w.everyMin).put("last_seen", w.lastSeen)
                        .put("last_check", if (w.lastCheck > 0) Times.format(w.lastCheck) else "not yet").put("happened", w.hitAt > 0).put("link", w.url)
                })
                ToolResult.Done(JSONObject().put("ok", true).put("watching", arr), if (arr.length() == 0) "Not watching anything" else "Watching ${arr.length()}")
            },

            Tool("watch_remove", "agent", "Stop watching a page.", schema { str("id", "The id from watch_list, or a few words of what it watches", required = true) }) { a, _ ->
                val w = Watchers.remove(a.optString("id")) ?: return@Tool ToolResult.Failed("Nothing like that is being watched (watch_list shows what is).")
                ToolResult.ok("Stopped watching: ${w.what}")
            },

            Tool(
                "daily_brief", "agent",
                "The user's summary for today, made on the phone: weather, today's calendar, deadlines and bills due soon, watched pages, missed calls to return. Use for \"my morning summary\", \"what's on today\", \"what do I need to do today\".",
                schema { bool("notify", "true = also show it as a notification (for the scheduled morning summary)") },
            ) { a, _ ->
                val text = brief()
                if (a.optBoolean("notify")) Notify.line(ctx, text)
                ToolResult.Done(JSONObject().put("ok", true).put("summary", text), text.lines().take(3).joinToString(" · ").take(120))
            },

            Tool(
                "busy_mode", "phone",
                "Busy mode: \"I'm in class till 4\", \"in a meeting for an hour\", \"driving\". Until the end time Seyal can silence the phone, text people whose call was missed (once each, saved contacts only), let a second call from the same person through as urgent, and afterwards list who to call back. on=false ends it now.",
                schema {
                    bool("on", "true to start, false to end now", required = true)
                    str("until", "When it ends: local ISO (2026-11-13T16:00) or a clock time (16:00)")
                    str("message", "The SMS for missed callers, in the user's voice, e.g. \"In class, will call you after 4.\" Empty = text nobody")
                    bool("silence", "Also turn on Do Not Disturb until it ends (default true)")
                },
                defaultLevel = Level.ASK, sideEffect = true,
                preview = { a ->
                    if (!a.optBoolean("on", true)) "End busy mode now"
                    else "Busy until ${a.optString("until")}." + (if (a.optString("message").isNotBlank()) "\nPeople in your contacts whose call you miss get ONE text:\n\"${a.optString("message")}\"" else "\nNobody is texted.") +
                        "\nThe same person calling twice in 10 minutes rings through."
                },
            ) { a, _ ->
                if (!a.optBoolean("on", true)) {
                    if (!Busy.active()) return@Tool ToolResult.ok("Busy mode was not on")
                    val missed = Busy.missed(); Busy.stop(ctx)
                    return@Tool ToolResult.ok("Busy mode ended", "missed_calls" to BusyRules.summary(missed).ifBlank { "none" })
                }
                val raw = a.optString("until").trim()
                val now = LocalDateTime.now()
                val end = Times.parse(raw) ?: Times.parseClock(raw)?.let { t -> now.toLocalDate().atTime(t).let { if (it.isAfter(now)) it else it.plusDays(1) } }
                    ?: return@Tool ToolResult.Failed("Until when? Give a time like 16:00.")
                val until = Times.toEpoch(end)
                if (until <= System.currentTimeMillis() + 60_000 || until > System.currentTimeMillis() + 24 * 3600_000L) return@Tool ToolResult.Failed("The end time must be within the next 24 hours.")
                val msg = a.optString("message").trim().take(300)
                val canText = msg.isNotBlank() && Toolbox.get("send_sms")?.available() == true
                Busy.start(ctx, until, msg, canText, a.optBoolean("silence", true))
                ToolResult.ok(
                    "Busy until ${Times.format(until)}", "until" to Times.format(until), "texts_missed_callers" to canText,
                    "note" to (if (msg.isNotBlank() && !canText) "Texting is not possible on this phone (no SMS permission, or the Lite edition): callers are only listed afterwards." else null),
                )
            },
        )
    }
}
