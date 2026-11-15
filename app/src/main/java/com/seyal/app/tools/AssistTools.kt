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
    /** These answers come from reading notifications. With the reader off and nothing collected, say so plainly instead of "nothing". */
    private fun readerOff(empty: Boolean): ToolResult.Failed? =
        if (empty && !com.seyal.app.device.SeyalNotificationListener.connected)
            ToolResult.Failed("Seyal can't see your messages and payment alerts yet, so there is nothing to add up. The user must turn on Notification access for Seyal (Control tab → Notification access). Tell them that; don't guess an answer.")
        else null

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
        com.seyal.app.agent.Parcels.open(now).filter { it.status in setOf("out", "shipped") }.take(4).forEach { p -> lines += "• Order: ${p.line()}" }
        val (y0, y1) = com.seyal.app.agent.Ledger.range("yesterday", now)
        if (com.seyal.app.agent.Ledger.between(y0, y1).isNotEmpty()) lines += com.seyal.app.agent.Ledger.summary("yesterday", "both", now).lines().filter { it.startsWith("Received") || it.startsWith("Spent") }.filterNot { it.endsWith("nothing seen.") }
        com.seyal.app.agent.Coach.today(now).forEach { lines += "• Plan: $it" }
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
                "money_summary", "agent",
                "Money that came in and went out, added up from the payment notifications on this phone (Google Pay, PhonePe, Paytm, bank SMS): totals, who paid most, spending by kind. Use for \"today's sales\", \"how much did I get today\", \"how much did I spend this month\", \"what did I spend on food\". Only payments seen since Seyal was installed are counted.",
                schema {
                    str("range", "Which period", enum = listOf("today", "yesterday", "week", "month", "last_month"))
                    str("direction", "in = received, out = spent, both", enum = listOf("in", "out", "both"))
                    bool("notify", "true = also show it as a notification (for a scheduled daily total)")
                },
            ) { a, _ ->
                val now = System.currentTimeMillis()
                readerOff(com.seyal.app.agent.Ledger.all().isEmpty())?.let { return@Tool it }
                val range = a.optString("range").ifBlank { "today" }; val dir = a.optString("direction").ifBlank { "both" }
                val text = com.seyal.app.agent.Ledger.summary(range, dir, now)
                val (from, to) = com.seyal.app.agent.Ledger.range(range, now)
                val rows = com.seyal.app.agent.Ledger.between(from, to).takeLast(40).map { t ->
                    JSONObject().put("amount", t.amount).put("direction", if (t.incoming) "in" else "out").put("who", t.party).put("kind", t.category).put("via", t.via).put("at", Times.format(t.at))
                }
                if (a.optBoolean("notify")) Notify.line(ctx, text)
                ToolResult.Done(JSONObject().put("ok", true).put("summary", text).put("payments", JSONArray(rows)).put("reading_messages", Settings.catchMessages), text.lines().first().take(100))
            },

            Tool(
                "orders_list", "agent",
                "The user's online orders on their way (Amazon, Flipkart, Meesho, Myntra, Swiggy, Zomato and couriers), pieced together from delivery notifications: what, from where, status, when. Use for \"where are my orders\", \"when does my parcel come\", \"any delivery today\".",
                noArgs,
            ) { _, _ ->
                val now = System.currentTimeMillis()
                val list = com.seyal.app.agent.Parcels.open(now)
                readerOff(list.isEmpty())?.let { return@Tool it }
                val arr = JSONArray(list.map { p -> JSONObject().put("shop", p.shop).put("item", p.item).put("status", p.status).put("when", p.eta).put("updated", Times.format(p.updated)) })
                ToolResult.Done(JSONObject().put("ok", true).put("orders", arr).put("summary", com.seyal.app.agent.Parcels.summary(now)), if (list.isEmpty()) "No orders on the way" else "${list.size} order${if (list.size == 1) "" else "s"}")
            },

            Tool(
                "past_search", "agent",
                "Search the messages that arrived on this phone in the last 30 days (WhatsApp, SMS, mail and other notifications Seyal saw): \"the address Ram sent\", \"the form link from the class group\", \"what did the HOD say about the exam\", \"the PDF my professor sent\". " +
                    "Gives the matching messages with who, where and when. For the file itself, follow with files_find (WhatsApp files are under Android/media/com.whatsapp/WhatsApp/Media and Download).",
                schema {
                    str("query", "What to look for, in a few words: the topic and the kind of thing (link, pdf, address, number)", required = true)
                    str("from", "Only this person or group, if the user named one")
                    int("days", "How many days back (default: all 30)")
                },
            ) { a, _ ->
                if (!Settings.keepHistory || !Settings.catchMessages) return@Tool ToolResult.Failed("Keeping messages for search is turned off (Skills → Caught for you). Tell the user; nothing can be searched until it is on.")
                val now = System.currentTimeMillis()
                readerOff(com.seyal.app.agent.Past.size() == 0)?.let { return@Tool it }
                val hits = com.seyal.app.agent.Past.search(a.optString("query"), now, a.optInt("days", 0), a.optString("from"))
                val arr = JSONArray(hits.map { n -> JSONObject().put("when", Times.format(n.at)).put("app", n.app).put("from", n.chat + if (n.group) " (group)" else "").put("text", n.text) })
                ToolResult.Done(
                    JSONObject().put("ok", true).put("matches", arr).put("kept_messages", com.seyal.app.agent.Past.size())
                        .also { if (hits.isEmpty()) it.put("note", "Nothing matching among the ${com.seyal.app.agent.Past.size()} messages kept. Only messages that arrived after Seyal was installed (and within 30 days) can be found. Try other words, or files_find for a file.") },
                    if (hits.isEmpty()) "Nothing found" else "Found ${hits.size} message${if (hits.size == 1) "" else "s"}",
                )
            },

            Tool(
                "plan_create", "agent",
                "Save a plan the user asked for (workout, study, diet, a habit) so Seyal reminds them of one piece each day and tracks whether it gets done. Ask what you need first (goal, equipment or topics, days and minutes, time of day, level, anything to avoid). " +
                    "sessions repeat in order: each has a title, 3 to 8 concrete items and its minutes. A skipped day does not skip its session.",
                schema {
                    str("title", "Short name, e.g. \"Home workout\", \"DC exam revision\"", required = true)
                    str("kind", "What kind of plan", enum = listOf("workout", "study", "diet", "habit", "other"))
                    str("goal", "The user's goal in a few words")
                    arr("sessions", "The sessions, in order. Each: {\"title\":\"Day A: Push\",\"minutes\":20,\"items\":[\"Push-ups 3 x 8\",\"…\"]}", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("title", JSONObject().put("type", "string")).put("minutes", JSONObject().put("type", "integer")).put("items", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))), required = true)
                    strList("days", "Which days: [\"daily\"], [\"weekdays\"], or names like [\"mon\",\"wed\",\"fri\"]")
                    str("time", "When to remind, 24-hour HH:mm, e.g. 06:30 or 18:00", required = true)
                    str("measure", "Optional: one number to track beside done/skipped, e.g. \"weight kg\", \"pages\"")
                },
            ) { a, _ ->
                val sessions = a.optJSONArray("sessions")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { com.seyal.app.agent.PlanSession.fromJson(it) } }.orEmpty().filter { it.items.isNotEmpty() }
                if (sessions.isEmpty()) return@Tool ToolResult.Failed("Give at least one session with its items.")
                val time = Times.parseClock(a.optString("time"))?.toString()?.take(5) ?: Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(a.optString("time").trim())?.value?.padStart(5, '0')
                    ?: return@Tool ToolResult.Failed("Give the reminder time as HH:mm, e.g. 06:30.")
                val days = com.seyal.app.agent.Coach.parseDays(a.optJSONArray("days")?.let { d -> (0 until d.length()).map { d.optString(it) } }.orEmpty())
                val p = com.seyal.app.agent.CoachPlan(
                    newId(), a.optString("title").take(50).ifBlank { "My plan" }, a.optString("kind").ifBlank { "other" }, a.optString("goal").take(120),
                    sessions.map { com.seyal.app.agent.PlanSession(it.title.take(60).ifBlank { "Session" }, it.items.take(10).map { i -> i.take(120) }, it.minutes.coerceIn(5, 240).let { m -> if (m == 5 && it.minutes == 0) 20 else m }) },
                    days, time, System.currentTimeMillis(), measure = a.optString("measure").take(20),
                )
                com.seyal.app.agent.Coach.add(p)
                val next = com.seyal.app.agent.Coach.nextNudge(System.currentTimeMillis())
                ToolResult.ok(
                    "Plan saved: ${p.title}", "id" to p.id, "sessions" to p.sessions.size, "first_reminder" to (next?.let { Times.format(it) } ?: "none"),
                    "today" to "${p.next().title}: ${p.todaysItems().joinToString("; ")}", "note" to "Each reminder has Done / Skip / Later. The plan and its streak are under Skills → Caught for you.",
                )
            },

            Tool("plan_list", "agent", "The user's plans and how they are going: today's session, streak, this week, the tracked number. Use for \"how is my workout plan going\", \"what is my workout today\".", noArgs) { _, _ ->
                val now = System.currentTimeMillis()
                val plans = com.seyal.app.agent.Coach.all()
                val arr = JSONArray(plans.map { p -> JSONObject().put("id", p.id).put("title", p.title).put("status", com.seyal.app.agent.Coach.status(p, now)).put("next_session", p.next().title).put("items", JSONArray(p.todaysItems())).put("minutes", p.todaysMinutes()).put("paused", p.paused) })
                ToolResult.Done(JSONObject().put("ok", true).put("plans", arr).put("summary", if (plans.isEmpty()) "No plans yet." else plans.joinToString("\n") { "• " + com.seyal.app.agent.Coach.status(it, now) }), if (plans.isEmpty()) "No plans yet" else "${plans.size} plan${if (plans.size == 1) "" else "s"}")
            },

            Tool(
                "plan_mark", "agent", "Record how today went for a plan: done, skip, or later (remind again in an hour). With value, also logs the plan's tracked number (weight, pages).",
                schema { str("plan", "The plan's id or a few words of its title (empty if there is only one)"); str("state", "What happened", enum = listOf("done", "skip", "later")); num("value", "The tracked number, if the user gave one") },
            ) { a, _ ->
                val p = com.seyal.app.agent.Coach.find(a.optString("plan")) ?: return@Tool ToolResult.Failed("Which plan? (plan_list shows them.)")
                val now = System.currentTimeMillis()
                val parts = mutableListOf<String>()
                if (a.optString("state").isNotBlank()) parts += com.seyal.app.agent.Coach.mark(p, a.optString("state"), now)
                if (a.has("value") && !a.isNull("value")) parts += com.seyal.app.agent.Coach.measure(p, a.optDouble("value"), now)
                if (parts.isEmpty()) return@Tool ToolResult.Failed("Say the state (done, skip, later) or a value.")
                runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).cancel("plan${p.id}".hashCode()) }
                ToolResult.ok(parts.joinToString(" "), "status" to com.seyal.app.agent.Coach.status(p, now))
            },

            Tool(
                "plan_change", "agent", "Change a plan: make it easier, pause or resume it, move its reminder time or days, or delete it.",
                schema {
                    str("plan", "The plan's id or a few words of its title (empty if there is only one)")
                    str("action", "What to change", required = true, enum = listOf("easier", "pause", "resume", "time", "days", "delete"))
                    str("time", "For action time: HH:mm"); strList("days", "For action days: [\"daily\"], [\"weekdays\"] or day names")
                },
            ) { a, _ ->
                val p = com.seyal.app.agent.Coach.find(a.optString("plan")) ?: return@Tool ToolResult.Failed("Which plan? (plan_list shows them.)")
                val now = System.currentTimeMillis()
                when (a.optString("action")) {
                    "easier", "pause", "resume" -> ToolResult.ok(com.seyal.app.agent.Coach.mark(p, a.optString("action"), now))
                    "delete" -> { com.seyal.app.agent.Coach.remove(p.id); ToolResult.ok("Deleted the plan \"${p.title}\"") }
                    "time", "days" -> {
                        val time = if (a.optString("action") == "time") (Times.parseClock(a.optString("time"))?.toString()?.take(5) ?: Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(a.optString("time").trim())?.value?.padStart(5, '0') ?: return@Tool ToolResult.Failed("Give the time as HH:mm.")) else p.time
                        val days = if (a.optString("action") == "days") com.seyal.app.agent.Coach.parseDays(a.optJSONArray("days")?.let { d -> (0 until d.length()).map { d.optString(it) } }.orEmpty()) else p.days
                        val moved = com.seyal.app.agent.CoachPlan(p.id, p.title, p.kind, p.goal, p.sessions, days, time, p.created, p.doneCount, p.log, p.skipsInRow, p.easy, p.paused, 0L, "", p.measure, p.measures)
                        com.seyal.app.agent.Coach.add(moved)
                        ToolResult.ok("Changed \"${p.title}\": reminders at $time", "status" to com.seyal.app.agent.Coach.status(moved, now))
                    }
                    else -> ToolResult.Failed("Unknown action.")
                }
            },

            Tool(
                "khata", "agent",
                "The credit book: who owes the user money. gave = the user gave credit (\"Ravi owes me 500\", \"put 200 on Ravi's account\"); got = they paid back (\"Ravi paid 200\"); list = who owes how much; remind = a polite reminder text for one person, which you then send with send_whatsapp (the user approves it).",
                schema {
                    str("action", "What to do", required = true, enum = listOf("gave", "got", "list", "remind"))
                    str("name", "The person (for gave, got, remind)"); int("amount", "Rupees (for gave, got)"); str("note", "What it was for")
                },
            ) { a, _ ->
                val now = System.currentTimeMillis()
                val name = a.optString("name").trim()
                when (a.optString("action")) {
                    "gave", "got" -> {
                        if (name.isBlank() || a.optInt("amount") <= 0) return@Tool ToolResult.Failed("Say who and how much.")
                        val bal = com.seyal.app.agent.Khata.add(name, a.optInt("amount"), a.optString("action") == "gave", a.optString("note"), now)
                        ToolResult.ok(if (bal > 0) "$name now owes ₹$bal" else if (bal == 0) "$name is all settled" else "$name has paid ₹${-bal} extra", "balance" to bal)
                    }
                    "remind" -> {
                        if (com.seyal.app.agent.Khata.balance(name) <= 0) return@Tool ToolResult.Failed("$name owes nothing in the credit book.")
                        ToolResult.ok("Reminder ready for $name", "text" to com.seyal.app.agent.Khata.reminder(name, Settings.userName), "balance" to com.seyal.app.agent.Khata.balance(name), "note" to "Look the person up (contacts_search) and send this with send_whatsapp. The user approves before it goes.")
                    }
                    else -> ToolResult.Done(JSONObject().put("ok", true).put("summary", com.seyal.app.agent.Khata.summary(now)).put("people", JSONArray(com.seyal.app.agent.Khata.owing().map { JSONObject().put("name", it.first).put("owes", it.second) })), com.seyal.app.agent.Khata.summary(now).lines().first())
                }
            },

            Tool(
                "split_bill", "agent",
                "Split an amount among people, exactly (use this instead of working it out yourself). Equal shares, or by what each one had. Gives each person's share and a ready message for each.",
                schema {
                    int("total", "The bill total in rupees, tax and tip included", required = true)
                    strList("people", "Everyone sharing, the user included as \"me\"", required = true)
                    arr("amounts", "Optional: what each person's own items cost, in the same order as people; what is left (tax, shared dishes) is split equally", JSONObject().put("type", "integer"))
                    str("what", "What the bill was for, e.g. \"dinner at A2B\"")
                },
            ) { a, _ ->
                val people = a.optJSONArray("people")?.let { p -> (0 until p.length()).map { p.optString(it).trim() }.filter { it.isNotBlank() } }.orEmpty()
                val own = a.optJSONArray("amounts")?.let { p -> (0 until p.length()).map { p.optInt(it) } }.orEmpty()
                val shares = com.seyal.app.agent.Split.shares(a.optInt("total"), people, own) ?: return@Tool ToolResult.Failed("Give the total and at least two people; each one's own amounts must not add up to more than the total.")
                val what = a.optString("what").ifBlank { "the bill" }
                val arr = JSONArray(shares.map { (n, amt) -> JSONObject().put("name", n).put("share", amt).put("message", "Hi $n, your share for $what is ₹$amt (total ₹${a.optInt("total")}, ${people.size} people). Please send it when you can.") })
                ToolResult.Done(JSONObject().put("ok", true).put("shares", arr).put("note", "To send them: contacts_search each person, then send_whatsapp with their message (skip \"me\"). Add the user's UPI ID to the message only if they gave it."), shares.joinToString(", ") { "${it.first} ₹${it.second}" })
            },

            Tool(
                "photos_to_pdf", "files",
                "Turn the pictures the user attached in this chat (a bill, notes, a certificate, pages of a document) into one PDF file, a page per picture, saved in Documents/Seyal. Gives its uri: follow with send_file to mail it or send it on WhatsApp.",
                schema { str("title", "A name for the file, e.g. \"Fee receipt\"") },
            ) { a, cx ->
                val s = com.seyal.app.agent.Agent.session(cx.sessionId)
                val uris = com.seyal.app.device.Attach.recentPictures(cx.sessionId)
                val bitmaps = withContext(Dispatchers.IO) {
                    // The pictures as picked (sharper), or else the copies that went to the AI.
                    val fromUris = uris.mapNotNull { u -> runCatching { ctx.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) } }.getOrNull() }
                    fromUris.ifEmpty {
                        s?.messages?.lastOrNull { m -> m.role == "user" && m.parts.any { it is com.seyal.app.data.Part.Image && it.mime.startsWith("image/") } }?.parts.orEmpty()
                            .filterIsInstance<com.seyal.app.data.Part.Image>().filter { it.mime.startsWith("image/") && it.b64.isNotBlank() }
                            .mapNotNull { img -> runCatching { android.util.Base64.decode(img.b64, android.util.Base64.DEFAULT).let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull() }
                    }
                }
                if (bitmaps.isEmpty()) return@Tool ToolResult.Failed("There are no pictures in this chat to put in a PDF. Ask the user to attach them with the + button (or share them to Seyal), then try again.")
                val bytes = withContext(Dispatchers.IO) {
                    val doc = android.graphics.pdf.PdfDocument()
                    bitmaps.forEachIndexed { i, b ->
                        // A4 at 72 dpi, the picture fitted inside a small margin.
                        val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
                        val scale = minOf(555f / b.width, 802f / b.height)
                        val w = b.width * scale; val h = b.height * scale
                        page.canvas.drawBitmap(b, null, android.graphics.RectF((595 - w) / 2, (842 - h) / 2, (595 + w) / 2, (842 + h) / 2), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                        doc.finishPage(page)
                    }
                    java.io.ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
                }
                val name = com.seyal.app.util.Office.fileName(a.optString("title").ifBlank { "Scanned pages" }, "pdf")
                val uri = DocTools.save(name, "application/pdf", bytes) ?: return@Tool ToolResult.Failed("The PDF could not be saved (storage permission?).")
                ToolResult.ok("Made a ${bitmaps.size}-page PDF: $name", "uri" to uri.toString(), "name" to name, "pages" to bitmaps.size, "saved_in" to "Documents/Seyal")
            },

            Tool(
                "send_to_many", "comms",
                "Send a message to several people in one go, each with their own wording (festival or birthday wishes, an invitation, a notice to the people it concerns). The user sees the whole list once and approves it; then each is sent. " +
                    "Look every person up first (contacts_search) and write each message personally (their name, your relationship). Never a group chat, at most 25 people, and only people the user named or clearly meant.",
                schema {
                    arr("people", "Everyone to send to. Each: {\"name\":\"Appa\",\"phone\":\"+91…\",\"text\":\"Happy Diwali, Appa!…\"}", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("name", JSONObject().put("type", "string")).put("phone", JSONObject().put("type", "string")).put("text", JSONObject().put("type", "string"))), required = true)
                    str("via", "How to send", enum = listOf("whatsapp", "sms"))
                },
                defaultLevel = Level.ASK, sideEffect = true,
                preview = { a ->
                    val arr = a.optJSONArray("people")
                    val n = arr?.length() ?: 0
                    "Send to $n ${if (n == 1) "person" else "people"} by ${a.optString("via").ifBlank { "whatsapp" }}:\n" +
                        (0 until minOf(n, 25)).joinToString("\n") { i -> arr!!.optJSONObject(i)?.let { "• ${it.optString("name").ifBlank { it.optString("phone") }}: \"${it.optString("text").take(70)}\"" } ?: "" }
                },
            ) { a, cx ->
                val arr = a.optJSONArray("people") ?: return@Tool ToolResult.Failed("Give the people to send to.")
                val people = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("phone").isNotBlank() && it.optString("text").isNotBlank() }
                    .distinctBy { Common.normalisePhone(it.optString("phone")) }
                if (people.isEmpty()) return@Tool ToolResult.Failed("Each person needs a phone number (contacts_search) and a message.")
                if (people.size > 25) return@Tool ToolResult.Failed("That is ${people.size} people. Send to at most 25 at a time.")
                val tool = Toolbox.get(if (a.optString("via") == "sms") "send_sms" else "send_whatsapp")?.takeIf { it.available() } ?: return@Tool ToolResult.Failed("That way of sending is not available on this phone.")
                val sent = mutableListOf<String>(); val failed = mutableListOf<String>()
                for (p in people) {
                    val who = p.optString("name").ifBlank { p.optString("phone") }
                    val r = runCatching { tool.run(JSONObject().put("phone", p.optString("phone")).put("text", p.optString("text")).put("name", p.optString("name")), cx) }.getOrNull()
                    if (r is ToolResult.Done && r.output.optBoolean("sent", true)) sent += who else failed += who + ((r as? ToolResult.Failed)?.message?.let { " (${it.take(50)})" } ?: " (not sent)")
                    kotlinx.coroutines.delay(1200)
                }
                ToolResult.Done(
                    JSONObject().put("ok", failed.isEmpty() || sent.isNotEmpty()).put("sent", sent.isNotEmpty()).put("sent_to", JSONArray(sent)).put("not_sent", JSONArray(failed)),
                    "Sent to ${sent.size} of ${people.size}" + (if (failed.isNotEmpty()) " · not sent: ${failed.joinToString(", ").take(80)}" else ""),
                )
            },

            Tool(
                "chats_search", "agent",
                "Search the user's earlier conversations with Seyal: what was asked, what was found, the links and answers given. Use for \"the link you found yesterday\", \"what did you tell me about …\", \"do the thing from last week again\".",
                schema { str("query", "A few words of what to look for", required = true) },
            ) { a, cx ->
                val q = a.optString("query").lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }
                if (q.isEmpty()) return@Tool ToolResult.Failed("Say what to look for.")
                val hits = com.seyal.app.agent.Agent.history.value.asSequence().filter { it.id != cx.sessionId && !it.isChild }.take(300).mapNotNull { e ->
                    val hay = (e.title + " " + e.result).lowercase()
                    val n = q.count { hay.contains(it) }
                    if (n == 0) null else e to n
                }.sortedWith(compareByDescending<Pair<com.seyal.app.data.SessionInfo, Int>> { it.second }.thenByDescending { it.first.updatedAt }).take(6).toList()
                val arr = JSONArray(hits.map { (e, _) -> JSONObject().put("when", Times.format(e.updatedAt)).put("asked", e.title).put("answer", e.result.take(600)) })
                ToolResult.Done(JSONObject().put("ok", true).put("chats", arr), if (hits.isEmpty()) "Nothing found in earlier chats" else "Found ${hits.size} earlier chat${if (hits.size == 1) "" else "s"}")
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
