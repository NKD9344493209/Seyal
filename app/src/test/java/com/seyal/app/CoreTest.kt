package com.seyal.app

import com.seyal.app.agent.Memory
import com.seyal.app.ai.Llm
import com.seyal.app.agent.Agent
import com.seyal.app.tools.FileTools
import com.seyal.app.device.AdSkipper
import com.seyal.app.device.PhoneEvents
import com.seyal.app.agent.Preflight
import com.seyal.app.agent.AutoGuard
import com.seyal.app.agent.Checks
import com.seyal.app.agent.Did
import com.seyal.app.agent.Seats
import com.seyal.app.agent.Order
import com.seyal.app.agent.Basics
import com.seyal.app.agent.SeatScreen
import com.seyal.app.device.TypeCheck
import com.seyal.app.agent.Honest
import com.seyal.app.device.ShotHealth
import com.seyal.app.device.Perms
import com.seyal.app.device.ScreenWatch
import com.seyal.app.agent.Meetings
import com.seyal.app.agent.Scheduler
import com.seyal.app.ai.LlmException
import com.seyal.app.agent.Prompts
import com.seyal.app.agent.Router
import com.seyal.app.agent.Links
import com.seyal.app.agent.Opener
import com.seyal.app.agent.Detour
import com.seyal.app.device.KeepAlive
import com.seyal.app.device.Report
import com.seyal.app.agent.Scam
import com.seyal.app.agent.MoneyParser
import com.seyal.app.agent.Ledger
import com.seyal.app.agent.Parcel
import com.seyal.app.agent.ParcelParser
import com.seyal.app.agent.Parcels
import com.seyal.app.agent.Past
import com.seyal.app.agent.Feeds
import com.seyal.app.agent.Coach
import com.seyal.app.agent.CoachPlan
import com.seyal.app.agent.PlanSession
import com.seyal.app.agent.Usual
import com.seyal.app.agent.Khata
import com.seyal.app.agent.Split
import com.seyal.app.agent.Habits
import com.seyal.app.device.Attach
import com.seyal.app.device.Guardian
import com.seyal.app.device.RepeatGuard
import com.seyal.app.agent.Routines
import com.seyal.app.agent.History
import com.seyal.app.agent.Catcher
import com.seyal.app.agent.Inbox
import com.seyal.app.agent.Watcher
import com.seyal.app.agent.Watchers
import com.seyal.app.device.BusyRules
import com.seyal.app.util.Dates
import com.seyal.app.device.TapRules
import com.seyal.app.agent.Quick
import com.seyal.app.agent.Lessons
import com.seyal.app.data.Only
import com.seyal.app.util.Office
import com.seyal.app.agent.Skills
import com.seyal.app.data.CallStatus
import com.seyal.app.data.Msg
import com.seyal.app.data.Part
import com.seyal.app.data.Session
import com.seyal.app.data.SessionInfo
import com.seyal.app.data.SessionStatus
import com.seyal.app.data.Skill
import com.seyal.app.data.Trigger
import com.seyal.app.util.Parsers
import com.seyal.app.util.Times
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** Checks for the pure parts of Seyal: models, dates, parsers, memory. Runs on the JVM, no phone needed. */
object T {
    var passed = 0
    var failed = 0

    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) { passed++; println("  ok   $name") } else { failed++; println("  FAIL $name ${if (detail.isNotBlank()) "— $detail" else ""}") }
    }

    fun <A> eq(name: String, expected: A, actual: A) = check(name, expected == actual, "expected $expected, got $actual")
}

fun testModels() {
    println("Models")
    val s = Session("s1", 1000L)
    s.title = "Msg Ram"
    s.messages += Msg("user", listOf(Part.Text("message ram")))
    val call = Part.Call("c1", "contacts_search", JSONObject().put("name", "Ram"))
    s.messages += Msg("assistant", listOf(Part.Text("Looking up Ram"), call))
    s.state("c1").apply { status = CallStatus.DONE; line = "Found Ram Kumar" }
    s.messages += Msg("tool", listOf(Part.Result("c1", "contacts_search", JSONObject().put("count", 1))))
    s.status = SessionStatus.DONE; s.result = "Sent"
    val back = Session.fromJson(JSONObject(s.toJson().toString()))
    T.eq("session id", "s1", back.id)
    T.eq("messages kept", 3, back.messages.size)
    T.eq("call args kept", "Ram", back.messages[1].calls[0].args.optString("name"))
    T.eq("call state kept", "Found Ram Kumar", back.calls["c1"]?.line)
    T.eq("result kept", 1, back.messages[2].results[0].output.optInt("count"))
    T.eq("first command", "message ram", back.firstCommand)

    // A session that was running when the app died comes back as failed, not stuck.
    val stuck = Session("s2", 1L).apply { status = SessionStatus.RUNNING; state("x").status = CallStatus.RUNNING }
    val revived = Session.fromJson(JSONObject(stuck.toJson().toString()))
    T.eq("running session revived as failed", SessionStatus.FAILED, revived.status)
    T.eq("running call marked failed", CallStatus.FAILED, revived.calls["x"]?.status)
    val waiting = Session("s3", 1L).apply { status = SessionStatus.RUNNING; state("y").status = CallStatus.WAITING_APPROVAL }
    val revivedWaiting = Session.fromJson(JSONObject(waiting.toJson().toString()))
    T.eq("waiting session closed after restart (its handlers are gone)", SessionStatus.FAILED, revivedWaiting.status)
    T.eq("waiting call marked failed", CallStatus.FAILED, revivedWaiting.calls["y"]?.status)
    val sig = Part.fromJson(Part.Call("c", "news", JSONObject(), "sigABC").toJson()) as Part.Call
    T.eq("thought signature kept", "sigABC", sig.signature)

    // Snapshots are copies.
    val snap = s.snapshot()
    T.check("snapshot is a new object", snap !== s && snap.calls["c1"] !== s.calls["c1"])
    s.calls["c1"]!!.line = "changed"
    T.eq("snapshot unaffected by later change", "Found Ram Kumar", snap.calls["c1"]?.line)

    // Image parts round-trip.
    val img = Part.fromJson(Part.Image("image/jpeg", "abc").toJson()) as Part.Image
    T.eq("image part", "abc", img.b64)
    val withImg = Part.fromJson(Part.Result("c9", "screenshot", JSONObject(), false, Part.Image("image/png", "zz")).toJson()) as Part.Result
    T.eq("result image kept", "zz", withImg.image?.b64)

    // Skills and triggers, including old recipe files.
    val sk = Skill("k1", "Morning news", "", "AI news at 8", listOf(Part.Call("a", "news", JSONObject().put("topic", "AI"))), Trigger("time", "08:00", repeat = "daily"))
    val sk2 = Skill.fromJson(JSONObject(sk.toJson().toString()))
    T.eq("skill steps kept", "news", sk2.steps[0].name)
    T.eq("trigger describe", "Daily · 08:00", sk2.trigger?.describe())
    val legacy = Skill.fromJson(JSONObject("""{"id":"r1","name":"Old","command":"news","trigger":{"type":"weekly","time":"07:30","days":[1,3]},"steps":[{"id":"s1","tool":"fetch_news","args":{"topic":"AI"}}]}"""))
    T.eq("legacy recipe trigger mapped", "time", legacy.trigger?.type)
    T.eq("legacy repeat", "weekly", legacy.trigger?.repeat)
    T.eq("legacy step tool", "fetch_news", legacy.steps[0].name)
    T.eq("legacy goal from command", "news", legacy.goal)
    T.eq("notification trigger text", "When Swiggy notifies about \"order\"", Trigger("notification", app = "Swiggy", contains = "order").describe())
    T.eq("event trigger text", "When YouTube opens", Trigger("event", event = "app_opened:YouTube").describe())

    // Activity rows are plain values, so screens refresh when (and only when) something changed.
    val info = SessionInfo.fromJson(s.toIndexJson())
    T.eq("info status", SessionStatus.DONE, info.status)
    T.eq("info equal when unchanged", info, SessionInfo.fromJson(s.toIndexJson()))
    s.result = "Sent to Ram"
    T.check("info differs after a change", info != SessionInfo.fromJson(s.toIndexJson()))

    // Trigger values flow into recorded steps.
    val step = Part.Call("x", "notification_reply", JSONObject().put("match", "{{sender}}").put("app", "WhatsApp").put("text", "In class, will call. ({{app}})"))
    val filled = Skills.fill(step, mapOf("sender" to "Amma", "app" to "WhatsApp"))
    T.eq("step variable filled", "Amma", filled.args.optString("match"))
    T.eq("step variable inside text", "In class, will call. (WhatsApp)", filled.args.optString("text"))
    T.eq("original step untouched", "{{sender}}", step.args.optString("match"))
    T.check("no variables → same step", Skills.fill(step, emptyMap()) === step)
}

fun testTimes() {
    println("Times")
    val today = LocalDate.now()
    T.eq("iso", LocalDateTime.of(2026, 11, 13, 7, 0), Times.parse("2026-11-13T07:00"))
    T.eq("iso with space", LocalDateTime.of(2026, 11, 13, 7, 0), Times.parse("2026-11-13 07:00"))
    T.eq("date only → 9:00", LocalDateTime.of(2026, 11, 13, 9, 0), Times.parse("2026-11-13"))
    val inTwenty = Times.parse("in 20 minutes")!!
    T.check("in 20 minutes", java.time.Duration.between(LocalDateTime.now(), inTwenty).toMinutes() in 18..20)
    T.eq("tomorrow 6:30", today.plusDays(1).atTime(6, 30), Times.parse("tomorrow 06:30"))
    T.eq("tomorrow 6.30 am", today.plusDays(1).atTime(6, 30), Times.parse("tomorrow 6.30 am"))
    T.check("bare past time rolls forward", Times.parse("00:01")!!.isAfter(LocalDateTime.now()))
    val fri = Times.parse("friday 7am")!!
    T.check("weekday + time is a future Friday", fri.dayOfWeek == java.time.DayOfWeek.FRIDAY && fri.isAfter(LocalDateTime.now()))
    T.eq("garbage → null", null, Times.parse("whenever"))
    T.eq("blank → null", null, Times.parse(""))

    val daily = Times.nextFire(Trigger("time", "08:00", repeat = "daily"))
    T.check("daily next fire is within a day", daily > System.currentTimeMillis() && daily < System.currentTimeMillis() + 24 * 3600_000L)
    val weekly = Trigger("time", "09:00", days = listOf(3), repeat = "weekly")
    val next = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(Times.nextFire(weekly)), java.time.ZoneId.systemDefault())
    T.eq("weekly fires on Wednesday", java.time.DayOfWeek.WEDNESDAY, next.dayOfWeek)
    T.eq("notification trigger has no time", 0L, Times.nextFire(Trigger("notification", app = "x")))
    T.eq("past once → 0", 0L, Times.nextFire(Trigger("time", "09:00", day = "2020-01-01", repeat = "once")))
    T.check("format today", Times.format(System.currentTimeMillis() + 60_000).startsWith("today"))
}

fun testParsers() {
    println("Parsers")
    val rss = """<rss><channel>
<item><title>Google launches Gemini 3 for Android agents - The Verge</title><link>https://a</link><pubDate>Fri</pubDate><source url="x">The Verge</source></item>
<item><title>Google launches Gemini 3 for Android agents - TechCrunch</title><link>https://b</link><source url="y">TechCrunch</source></item>
<item><title>OpenAI &amp; Microsoft sign new deal - Reuters</title><link>https://c</link><source url="z">Reuters</source></item>
<item><title>Something else entirely</title><link>https://d</link></item>
</channel></rss>"""
    val items = Parsers.newsItems(rss, 10)
    T.eq("duplicate story removed", 3, items.length())
    T.eq("source stripped from title", "Google launches Gemini 3 for Android agents", items.getJSONObject(0).getString("title"))
    T.eq("entity unescaped", "OpenAI & Microsoft sign new deal", items.getJSONObject(1).getString("title"))

    val ddg = """<div class="result"><h2><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FRamayana_(film)&amp;rut=abc">Ramayana (2026 film) - <b>Wikipedia</b></a></h2>
<a class="result__snippet" href="x">Ramayana is an upcoming Indian film. Part 1 releases on <b>Diwali 2026</b>.</a></div>
<div class="result"><a class="result__a" href="https://example.com/news">Ramayana release date update</a><a class="result__snippet" href="y">Snippet two</a></div>"""
    val res = Parsers.searchResults(ddg, 5)
    T.eq("two results", 2, res.length())
    T.eq("redirect unwrapped", "https://en.wikipedia.org/wiki/Ramayana_(film)", res.getJSONObject(0).getString("url"))
    T.check("snippet kept", res.getJSONObject(0).getString("snippet").contains("Diwali 2026"))

    val mixed = JSONArray().apply {
        listOf("https://www.youtube.com/watch?v=1", "https://en.wikipedia.org/wiki/A", "https://en.wikipedia.org/wiki/B", "https://site.com/report.pdf", "https://www.imdb.com/list/1", "https://m.facebook.com/p", "https://news.example.in/a")
            .forEach { put(JSONObject().put("url", it)) }
    }
    T.eq("readable: skip video/social/pdf, one per site", listOf("https://en.wikipedia.org/wiki/A", "https://www.imdb.com/list/1"), Parsers.readableUrls(mixed, 2))

    val html = "<html><head><title>Hello &amp; welcome</title><style>a{}</style></head><body><script>x()</script><h1>Big</h1><p>One</p><p>Two</p></body></html>"
    T.eq("page title", "Hello & welcome", Parsers.pageTitle(html))
    val text = Parsers.strip(html)
    T.check("script/style removed", !text.contains("x()") && !text.contains("a{}"), text)
    T.check("paragraphs on lines", text.contains("One\nTwo"), text.replace("\n", "\\n"))
    T.eq("wiki bad json → empty", 0, Parsers.wikiResults("nope").length())
}

fun testMemory() {
    println("Memory")
    var m = Memory.merge("", "dad = Appa Jio")
    T.eq("first fact", "- dad = Appa Jio", m)
    m = Memory.merge(m, "college = cit.edu.in")
    T.eq("two facts", 2, m.lines().size)
    m = Memory.merge(m, "dad = Appa (new number)")
    T.eq("same subject replaces", 2, m.lines().size)
    T.check("newest dad kept", m.contains("Appa (new number)") && !m.contains("Appa Jio"))
    m = Memory.merge(m, "college = cit.edu.in")
    T.eq("duplicate not added", 2, m.lines().size)
    val (kept, n) = Memory.remove(m, "college")
    T.eq("forget removes one", 1, n)
    T.check("forget keeps the rest", kept.contains("dad"))
    T.eq("blank fact ignored", m, Memory.merge(m, "   "))
    val long = (1..100).fold("") { acc, i -> Memory.merge(acc, "fact number $i about something fairly long so the file fills up quickly") }
    T.check("memory capped", long.length <= Memory.MAX)
}

fun testLlm() {
    println("AI limits")
    fun err(quotaId: String, delay: String) = JSONObject(
        """{"code":429,"status":"RESOURCE_EXHAUSTED","details":[
           {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests","quotaId":"$quotaId"}]},
           {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"$delay"}]}""",
    )
    val day = Llm.quotaInfo(err("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "13s"))
    T.check("daily limit seen as daily", day.second)
    T.eq("daily limit retry delay read", 13_000L, day.first)
    val minute = Llm.quotaInfo(err("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "41.5s"))
    T.check("minute limit not daily", !minute.second)
    T.eq("minute limit retry delay", 41_500L, minute.first)
    T.eq("no details → nothing", 0L to false, Llm.quotaInfo(JSONObject("{\"code\":429}")))
    T.check("2.5 is Gemini 2", Llm.isGemini2("gemini-2.5-flash-lite"))
    T.check("latest alias is not Gemini 2", !Llm.isGemini2("gemini-flash-lite-latest"))
    T.check("Gemini 3 is not Gemini 2", !Llm.isGemini2("gemini-3-flash-preview"))
    T.eq("own signature kept", "abc", Llm.geminiSignature("abc", firstCall = true, model = "gemini-flash-latest"))
    T.eq("first call without signature gets placeholder", "skip_thought_signature_validator", Llm.geminiSignature(null, firstCall = true, model = "gemini-flash-latest"))
    T.eq("later parallel call needs none", null, Llm.geminiSignature(null, firstCall = false, model = "gemini-flash-latest"))
    T.eq("Gemini 2 gets no signature", null, Llm.geminiSignature("abc", firstCall = true, model = "gemini-2.5-flash"))
    T.check("flash-lite model is usable", Llm.usableFlash("gemini-3.5-flash-lite", listOf("generateContent", "countTokens")))
    T.check("image model is not", !Llm.usableFlash("gemini-3.5-flash-image", listOf("generateContent")))
    T.check("tts model is not", !Llm.usableFlash("gemini-3.5-flash-preview-tts", listOf("generateContent")))
    T.check("embedding model is not", !Llm.usableFlash("gemini-embedding-001", listOf("embedContent")))
    T.check("pro model is not a flash", !Llm.usableFlash("gemini-3.5-pro", listOf("generateContent")))
    val detail = Llm.quotaDetail(JSONObject("""{"details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaValue":"250","quotaDimensions":{"model":"gemini-3.5-flash-lite"}}]}]}"""))
    T.eq("quota detail for the log", "GenerateRequestsPerDayPerProjectPerModel-FreeTier = 250 (gemini-3.5-flash-lite)", detail)
    val reset = Llm.quotaResetAt() - System.currentTimeMillis()
    T.check("daily reset within a day", reset in 1..(25 * 3_600_000L), "$reset ms")
}

fun testTriggers() {
    println("Message triggers")
    val t = Trigger("notification", app = "WhatsApp", from = "Amma", contains = "where are you|where r u|enga irukka")
    T.eq("Amma asking matches", null, t.misses("WhatsApp", "com.whatsapp", "Amma", "Where are you??"))
    T.eq("extra words still match", null, t.misses("WhatsApp", "com.whatsapp", "Amma", "da where are you now"))
    T.eq("another way of saying it", null, t.misses("WhatsApp", "com.whatsapp", "Amma", "enga irukka?"))
    T.eq("Amma with a count in the title", null, t.misses("WhatsApp", "com.whatsapp", "Amma (2 messages)", "where r u"))
    T.eq("Amma in a family group", null, t.misses("WhatsApp", "com.whatsapp", "Family", "Amma: where are you"))
    T.eq("someone else asking is ignored", "from someone else", t.misses("WhatsApp", "com.whatsapp", "Ram Kumar", "where are you"))
    T.eq("other words ignored", "other words", t.misses("WhatsApp", "com.whatsapp", "Amma", "Come home early today"))
    T.eq("other app ignored", "other app", t.misses("Messages", "com.google.android.apps.messaging", "Amma", "where are you"))
    T.eq("no words = any message from her", null, Trigger("notification", app = "WhatsApp", from = "Amma").misses("WhatsApp", "com.whatsapp", "Amma", "ok"))
    T.eq("Tamil words match", null, Trigger("notification", contains = "எங்க இருக்க").misses("WhatsApp", "com.whatsapp", "Amma", "நீ எங்க இருக்க?"))
    T.eq("mom matches a contact saved as Amma", null, Trigger("notification", app = "WhatsApp", from = "Mom", contains = "where are you").misses("WhatsApp", "com.whatsapp", "Amma ❤️", "where are you"))
    T.eq("Amma is not inside another word", "from someone else", Trigger("notification", from = "Amma").misses("WhatsApp", "com.whatsapp", "Rammakka", "where are you"))
    T.check("skip ad button", AdSkipper.isAdButton("Skip ad", "com.google.android.youtube:id/skip_ad_button", false))
    T.check("skip ads with a count", AdSkipper.isAdButton("Skip Ads", "", false))
    T.check("ad close by id", AdSkipper.isAdButton("", "com.mxtech:id/ad_close", false))
    T.check("plain close on an ad screen with an ad id", AdSkipper.isAdButton("Close", "x:id/interstitial_close_btn", true))
    T.check("a normal Close is left alone", !AdSkipper.isAdButton("Close", "com.app:id/close", false))
    T.check("Skip intro is left alone", !AdSkipper.isAdButton("Skip intro", "com.netflix:id/skip", false))
    T.check("a tutorial Skip on a normal screen is left alone", !AdSkipper.isAdButton("Skip", "com.app:id/skip", false))
    val round = Trigger.fromJson(t.toJson())
    T.eq("from survives saving", "Amma", round.from)
    T.check("describe names the sender", t.describe().contains("Amma"), t.describe())
    T.check("same person", Agent.samePerson("amma", "Amma"))
    T.check("same person with count", Agent.samePerson("amma (2 messages)", "Amma"))
    T.check("different person", !Agent.samePerson("ram kumar", "Amma"))
    T.check("no person set", !Agent.samePerson("amma", null))
    T.eq("short chat title kept", "Book tickets for Ramayana", Agent.titleFrom("Book tickets for Ramayana"))
    T.check("long chat title cut at a word", Agent.titleFrom("Find the cheapest buses from Coimbatore to Bangalore this Friday night and send them to Ram").let { it.length <= 50 && it.endsWith("…") && !it.contains("Banga…") })
}

fun testFiles() {
    println("Files")
    T.check("ramayana finds the movie file", FileTools.score("Ramayana.2026.1080p.WEB-DL.mkv", "ramayana movie") > 0)
    T.check("all words rank higher", FileTools.score("Ramayana.Part1.mkv", "ramayana part1") > FileTools.score("Ramayana.Part2.mkv", "ramayana part1"))
    T.eq("no words in common → no fit", 0, FileTools.score("Leo.2023.mkv", "ramayana"))
    T.eq("top folder", "DCIM", FileTools.bucket("DCIM/Camera/") { it })
    T.eq("app media folder by app name", "WhatsApp media (Android/media/com.whatsapp)", FileTools.bucket("Android/media/com.whatsapp/WhatsApp/Media/") { if (it == "com.whatsapp") "WhatsApp" else it })
    T.eq("sizes read well", "1.5 GB", FileTools.human(1610612736L))
    T.eq("file name words", listOf("ramayana", "2026", "1080p", "mkv"), FileTools.words("Ramayana.2026.1080p.mkv"))
}

fun testPhase2() {
    println("AI pacing")
    val now = 1_000_000L
    T.eq("room when nothing was sent", 0L, Llm.windowRoomAt(emptyList(), 15, now))
    T.eq("room with 13 of 15 used", 0L, Llm.windowRoomAt((1..13).map { now - 50_000 + it * 1000 }, 15, now))
    val full = (1..14).map { now - 50_000 + it * 1000L }
    T.eq("no room at 14 of 15: wait for the oldest to leave the minute", full[0] + 60_500, Llm.windowRoomAt(full, 15, now))
    T.eq("requests older than a minute don't count", 0L, Llm.windowRoomAt((1..20).map { now - 120_000 + it }, 15, now))
    T.eq("a limit of 5 allows 4 in a minute", now - 4000 + 60_500, Llm.windowRoomAt(listOf(now - 4000, now - 3000, now - 2000, now - 1000), 5, now))
    val pm = Llm.perMinuteLimit(JSONObject("""{"details":[{"violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier","quotaValue":"15","quotaDimensions":{"model":"gemini-3.5-flash-lite"}}]}]}"""))
    T.eq("per-minute limit learnt from the error", "gemini-3.5-flash-lite" to 15, pm)
    T.eq("a daily limit is not a per-minute one", null, Llm.perMinuteLimit(JSONObject("""{"details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaValue":"20","quotaDimensions":{"model":"gemini-3.8-flash"}}]}]}""")))

    println("Ask before buying")
    T.eq("movie tickets", Preflight.Kind.MOVIE, Preflight.kind("Book tickets for Ramayana at Broadway Cinemas"))
    T.eq("biryani", Preflight.Kind.FOOD, Preflight.kind("order a biryani from a nearby hotel"))
    T.eq("bus", Preflight.Kind.TRAVEL, Preflight.kind("Book a bus from Coimbatore to Bangalore on Friday"))
    T.eq("cab", Preflight.Kind.CAB, Preflight.kind("book an auto to the railway station"))
    T.eq("shopping", Preflight.Kind.SHOP, Preflight.kind("Buy a phone charger on Amazon"))
    T.eq("Tanglish order", Preflight.Kind.FOOD, Preflight.kind("oru dosa order pannu"))
    T.eq("finding buses is not buying", null, Preflight.kind("Find buses from Coimbatore to Bangalore and tell me the cheapest"))
    T.eq("a question is not buying", null, Preflight.kind("How do I book tatkal tickets?"))
    T.eq("order status is not buying", null, Preflight.kind("Track my order on Swiggy"))
    T.eq("weather is not buying", null, Preflight.kind("What is the weather today?"))
    T.eq("facebook is not 'book'", null, Preflight.kind("Open Facebook and check notifications"))

    println("Instant commands")
    T.eq("torch on", "set_setting", Quick.match("Torch on")?.name)
    T.eq("turn off the flashlight", "off", Quick.match("turn off the flashlight")?.args?.optString("value"))
    T.eq("open an app", "YouTube".lowercase(), Quick.match("Open YouTube")?.args?.optString("name"))
    T.eq("open … and do more goes to the AI", null, Quick.match("open youtube and search lofi"))
    T.eq("timer", 5, Quick.match("set a timer for 5 minutes")?.args?.optInt("minutes"))
    T.eq("brightness", "30", Quick.match("brightness 30%")?.args?.optString("value"))
    T.eq("a sentence goes to the AI", null, Quick.match("Remind me in 2 minutes to drink water"))
    T.eq("a question goes to the AI", null, Quick.match("what is on my calendar tomorrow"))

    println("Only when")
    val night = Only(after = "22:00", before = "06:00")
    T.eq("23:30 is inside a night window", null, night.fails(1, 23 * 60 + 30, 50, false, null))
    T.eq("05:00 is inside a night window", null, night.fails(1, 5 * 60, 50, false, null))
    T.check("noon is outside a night window", night.fails(1, 12 * 60, 50, false, null) != null)
    T.check("after 21:00 fails at 20:59", Only(after = "21:00").fails(1, 20 * 60 + 59, 50, false, null) != null)
    T.eq("after 21:00 holds at 21:00", null, Only(after = "21:00").fails(1, 21 * 60, 50, false, null))
    T.check("weekdays only fails on Sunday", Only(days = listOf(1, 2, 3, 4, 5)).fails(7, 600, 50, false, null) != null)
    T.check("battery below 20 fails at 20", Only(batteryBelow = 20).fails(1, 600, 20, false, null) != null)
    T.eq("battery below 20 holds at 19", null, Only(batteryBelow = 20).fails(1, 600, 19, false, null))
    T.check("charging only fails when unplugged", Only(charging = true).fails(1, 600, 50, false, null) != null)
    T.eq("home Wi-Fi matches", null, Only(wifi = "Home").fails(1, 600, 50, false, "MyHome_5G"))
    T.check("home Wi-Fi fails on mobile data", Only(wifi = "Home").fails(1, 600, 50, false, null) != null)
    T.eq("'-' means not on Wi-Fi", null, Only(wifi = "-").fails(1, 600, 50, false, null))
    val tr = Trigger.fromJson(JSONObject("""{"type":"event","event":"battery_below:20","only":{"after":"21:00","charging":false}}"""))
    T.eq("only survives saving", "21:00", Trigger.fromJson(tr.toJson()).only?.after)
    T.check("described in words", tr.describe().contains("below 20%") && tr.describe().contains("after 21:00"), tr.describe())

    println("Events")
    T.check("any Wi-Fi", Trigger.eventHit("wifi_connected", "wifi_connected:Office_5G"))
    T.check("named Wi-Fi", Trigger.eventHit("wifi_connected:office", "wifi_connected:Office_5G"))
    T.check("other Wi-Fi is not it", !Trigger.eventHit("wifi_connected:Home", "wifi_connected:Office_5G"))
    T.check("exact events", Trigger.eventHit("charging", "charging") && Trigger.eventHit("battery_below:20", "battery_below:20"))
    T.check("another level is not it", !Trigger.eventHit("battery_below:20", "battery_below:15"))
    T.check("mom calling matches Amma", Trigger.eventHit("call_from:Mom", "call_from:Amma"))
    T.check("anyone calling", Trigger.eventHit("call_from", "call_from:Ram Kumar"))
    T.check("someone else calling is not it", !Trigger.eventHit("call_from:Amma", "call_from:Ram Kumar"))
    T.check("car Bluetooth", Trigger.eventHit("bluetooth_connected:car", "bluetooth_connected:My Car Audio"))
    T.check("connect is not disconnect", !Trigger.eventHit("bluetooth_connected", "bluetooth_disconnected:My Car Audio"))
    T.check("arrive home", Trigger.eventHit("arrive:Home", "arrive:Home"))
    // Coimbatore Gandhipuram ↔ Peelamedu is roughly 5-6 km.
    val d = PhoneEvents.distance(11.0183, 76.9725, 11.0258, 77.0260)
    T.check("distance is about right", d in 5000.0..7000.0, "$d")
    T.check("inside a place", PhoneEvents.within(11.0183, 76.9725, 20f, 11.0184, 76.9726, 200, null))
    T.check("far outside", !PhoneEvents.within(11.0258, 77.0260, 20f, 11.0183, 76.9725, 200, true))
    T.check("near the edge keeps the last answer", PhoneEvents.within(11.0183 + 0.0021, 76.9725, 20f, 11.0183, 76.9725, 200, true))
    T.check("a vague fix changes nothing", PhoneEvents.within(11.0258, 77.0260, 900f, 11.0183, 76.9725, 200, true))

    println("Files made on the phone")
    val blocks = listOf(Office.Block("Intro", listOf("a", "b")), Office.Block("Long", (1..9).map { "point $it" }), Office.Block("Text", text = "one\ntwo"))
    val pages = Office.paginate(blocks)
    T.eq("a long list is spread over two slides", listOf(2, 5, 4, 2), pages.map { it.bullets.size })
    T.check("continued slide is marked", pages[2].heading.endsWith("(cont.)"))
    val deck = Office.pptx("Title & <tags>", "sub", "me", blocks)
    T.check("a deck is a zip", deck.size > 2000 && deck[0] == 'P'.code.toByte() && deck[1] == 'K'.code.toByte())
    val names = java.util.zip.ZipInputStream(deck.inputStream()).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
    T.check("it has a title slide and one per page", names.count { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) } == pages.size + 1, "$names")
    T.check("special characters are escaped", !String(deck, Charsets.ISO_8859_1).contains("<tags>"))
    T.check("a document is a zip with its text part", java.util.zip.ZipInputStream(Office.docx("T", "s", blocks).inputStream()).use { z -> generateSequence { z.nextEntry }.any { it.name == "word/document.xml" } })
    T.eq("file names are safe", "Q3 plan v2 final.pptx", Office.fileName("Q3 plan: v2 / final?", "pptx"))

    println("Learning")
    T.check("same note in other words", Lessons.similar("Tap the suggestion under the search box, not Enter", "In search, tap the suggestion under the box instead of Enter"))
    T.check("different notes", !Lessons.similar("Tap the suggestion under the search box", "The cart button is at the bottom right"))
    T.check("apps named in a request", Lessons.appsIn("order a dosa on Swiggy please").contains("swiggy"))
    T.check("notes for an app are given", Lessons.textFor(listOf("YouTube")).contains("Oldest"))
    T.eq("no notes for an unknown app", "", Lessons.textFor(listOf("Some Unknown App")))
    T.check("a 'tell me' skill needs the AI to word it", Agent.wantsAnswer("Open the Calculator and tell me the answer"))
    T.check("a 'do it' skill runs without the AI", !Agent.wantsAnswer("Put the phone on silent"))

    println("Ads")
    T.check("bare Skip on an ad", AdSkipper.isAdButton("Skip", "", adOnScreen = true, adActivity = false))
    T.check("bare Close on a page that only mentions an ad is left alone", !AdSkipper.isAdButton("Close", "com.app:id/btn", adOnScreen = true, adActivity = false))
    T.check("bare Close inside a full-screen ad", AdSkipper.isAdButton("Close", "", adOnScreen = false, adActivity = true))
}

fun testPhase3() {
    println("Group chats are left alone")
    T.check("\"Class 3B: Priya\" is a group title", Trigger.looksLikeGroup("Class 3B: Priya"))
    T.check("\"Priya @ Class 3B\" is a group title", Trigger.looksLikeGroup("Priya @ Class 3B"))
    T.check("\"Family (3 messages): Amma\" is a group title", Trigger.looksLikeGroup("Family (3 messages): Amma"))
    T.check("\"Amma\" is a person", !Trigger.looksLikeGroup("Amma"))
    T.check("\"Amma (2 messages)\" is a person", !Trigger.looksLikeGroup("Amma (2 messages)"))
    val anyone = Trigger("notification", app = "WhatsApp")
    T.eq("a skill for anyone fires for a person", null, anyone.misses("WhatsApp", "com.whatsapp", "Ram Kumar", "hi", group = false))
    T.check("a skill for anyone does NOT fire in a group", anyone.misses("WhatsApp", "com.whatsapp", "Teachers: HOD", "hi", group = true) != null)
    val amma = Trigger("notification", app = "WhatsApp", from = "Amma", contains = "where are you")
    T.check("Amma asking in the family group does not fire it", amma.misses("WhatsApp", "com.whatsapp", "Family: Amma", "where are you", group = true) != null)
    T.eq("Amma asking in her own chat fires it", null, amma.misses("WhatsApp", "com.whatsapp", "Amma", "Where are you??", group = false))
    val withGroups = Trigger.fromJson(JSONObject("""{"type":"notification","app":"WhatsApp","contains":"exam","groups":true}"""))
    T.eq("a skill the user made for groups fires there", null, withGroups.misses("WhatsApp", "com.whatsapp", "Class 3B: Priya", "exam tomorrow", group = true))
    T.check("groups flag survives saving", Trigger.fromJson(withGroups.toJson()).groups)
    T.check("old skills have groups off", !Trigger.fromJson(JSONObject("""{"type":"notification","app":"WhatsApp"}""")).groups)

    println("Brakes on automatic replies")
    AutoGuard.clear()
    val t0 = 10_000_000L
    T.eq("first message from a chat is answered", null, AutoGuard.blocked("s1", "com.whatsapp|Amma", true, t0))
    T.check("first run does not trip the switch", !AutoGuard.record("s1", "com.whatsapp|Amma", t0))
    T.check("same chat again after 1 minute waits", AutoGuard.blocked("s1", "com.whatsapp|Amma", true, t0 + 60_000) != null)
    T.eq("same chat after 11 minutes is answered", null, AutoGuard.blocked("s1", "com.whatsapp|Amma", true, t0 + 11 * 60_000))
    T.check("answer-anything skills wait an hour per chat", AutoGuard.blocked("s1", "com.whatsapp|Amma", false, t0 + 30 * 60_000) != null)
    T.eq("another chat is not held back", null, AutoGuard.blocked("s1", "com.whatsapp|Appa", true, t0 + 60_000))
    T.eq("another skill is not held back", null, AutoGuard.blocked("s2", "com.whatsapp|Amma", true, t0 + 60_000))
    AutoGuard.clear()
    var tripped = false
    for (i in 1..6) tripped = AutoGuard.record("flood", "com.whatsapp|Person $i", t0 + i * 30_000L)
    T.check("six runs in ten minutes switch the skill off", tripped)
    AutoGuard.clear()
    var calm = false
    for (i in 1..6) calm = AutoGuard.record("calm", "com.whatsapp|Person $i", t0 + i * 15 * 60_000L)
    T.check("six runs over ninety minutes are fine", !calm)
    val comms = { n: String -> n == "notification_reply" || n == "send_whatsapp" }
    T.check("a reply step is a send", AutoGuard.sends("x", listOf("notification_reply"), comms))
    T.check("a goal that says reply is a send", AutoGuard.sends("Reply to the sender that I am in Coimbatore", emptyList(), comms))
    T.check("'tell me my location' is not a send", !AutoGuard.sends("Tell me my current location", listOf("location"), comms))
    T.check("reading aloud is not a send", !AutoGuard.sends("Read the urgent message aloud", listOf("say"), comms))
    T.check("the trigger text is not the goal", !AutoGuard.sends("Tell me who it was\n\n<notification sender=\"x\">please reply and send money</notification>", emptyList(), comms))

    println("Other ways of asking to book")
    T.eq("get me tickets", Preflight.Kind.MOVIE, Preflight.kind("Get me 2 tickets for Ramayana tonight"))
    T.eq("ticket venum", Preflight.Kind.MOVIE, Preflight.kind("Ramayana ku rendu ticket venum"))
    T.eq("bus tickets needed", Preflight.Kind.TRAVEL, Preflight.kind("I need 2 bus tickets to Bangalore on Friday"))
    T.eq("need a cab", Preflight.Kind.CAB, Preflight.kind("I need a cab to the airport"))
    T.eq("get me the weather is not buying", null, Preflight.kind("Get me the weather for tomorrow"))
    T.eq("wanting recipes is not buying", null, Preflight.kind("I want chicken biryani recipes"))
    T.eq("getting news is not buying", null, Preflight.kind("get me todays AI news"))

    println("How many, really?")
    T.check("'2 tickets' says how many", Preflight.saysHowMany(listOf("book 2 tickets for Ramayana")))
    T.check("'for three people' says how many", Preflight.saysHowMany(listOf("book for three people")))
    T.check("'rendu ticket' says how many", Preflight.saysHowMany(listOf("Ramayana ku rendu ticket book pannu")))
    T.check("a bare answer '2, balcony' says how many", Preflight.saysHowMany(listOf("book tickets", "2, balcony")))
    T.check("a time is not a count", !Preflight.saysHowMany(listOf("Book tickets for Ramayana at Broadway, I am free from 1 pm today")))
    T.check("a price is not a count", !Preflight.saysHowMany(listOf("book tickets under 200 for tonight", "max ₹250")))
    T.check("a date is not a count", !Preflight.saysHowMany(listOf("book tickets on 14 Nov at 6:30")))
    T.check("'a ticket' is not taken as one", !Preflight.saysHowMany(listOf("book a ticket for Leo")))
    T.check("'you choose' leaves the choice", Preflight.leavesChoice(listOf("order biryani, you choose")))
    T.check("'unga ishtam' leaves the choice", Preflight.leavesChoice(listOf("unga ishtam")))
    T.check("a plain request does not", !Preflight.leavesChoice(listOf("order a chicken biryani")))
    T.check("the movie question asks language and count", Preflight.fallbackQuestion(Preflight.Kind.MOVIE, false).let { it.contains("language") && it.contains("How many") })
    T.check("the short question is about tickets", Preflight.fallbackQuestion(Preflight.Kind.MOVIE, true).contains("How many tickets"))
    T.check("movie details ask for the language", Preflight.Kind.MOVIE.details.contains("language"))
    T.check("'under 200' says the budget", Preflight.saysSeatOrBudget(listOf("2 tickets under 200")))
    T.check("'balcony' says the seats", Preflight.saysSeatOrBudget(listOf("book 2 balcony tickets")))
    T.check("'any seat' is an answer", Preflight.saysSeatOrBudget(listOf("any seat is fine")))
    T.check("no word on seats or price", !Preflight.saysSeatOrBudget(listOf("book 2 tickets for Ramayana tonight at Broadway")))
    T.check("both missing: one question for both", Preflight.missingQuestion(Preflight.Kind.MOVIE, true, true)!!.let { it.contains("How many") && it.contains("seat class") })
    T.eq("nothing missing: no question", null, Preflight.missingQuestion(Preflight.Kind.MOVIE, false, false))

    println("Scheduled sends and a locked phone")
    T.check("a WhatsApp step needs the screen", Skills.needsScreen("Send good morning", listOf("send_whatsapp")))
    T.check("a brightness step does not", !Skills.needsScreen("Lower the brightness", listOf("set_setting", "notify")))
    T.check("a goal that opens an app needs the screen", Skills.needsScreen("Open YouTube and play lofi", emptyList()))
    T.check("a news goal does not", !Skills.needsScreen("Give me today's top AI news in 5 short points.", emptyList()))

    println("Any app")
    val phone = listOf("Zomato", "WhatsApp", "Swiggy Food & Grocery", "District: Movies Events Dining", "redBus", "Chrome")
    T.eq("food apps on the phone", listOf("Swiggy Food & Grocery", "Zomato"), Preflight.installed(Preflight.Kind.FOOD, phone))
    T.eq("movie apps on the phone", listOf("District: Movies Events Dining"), Preflight.installed(Preflight.Kind.MOVIE, phone))
    T.eq("no cab app", emptyList<String>(), Preflight.installed(Preflight.Kind.CAB, phone))
    Preflight.appLabels = { phone }
    T.eq("the app the user named comes first", "Zomato", Preflight.appsFor(Preflight.Kind.FOOD, "order biryani on zomato").first())
    T.eq("otherwise the usual order", "Swiggy Food & Grocery", Preflight.appsFor(Preflight.Kind.FOOD, "order biryani").first())
    Preflight.appLabels = { emptyList() }
    T.check("app notes are found by the icon's name", Lessons.textFor(listOf("Swiggy Food & Grocery")).contains("ADD"))
    T.check("District has notes", Lessons.textFor(listOf("District: Movies Events Dining")).contains("Movies"))

    println("Done means done")
    T.eq("'send as PPT' asks for a file", "pptx", Checks.fileAsked("list the movies in MX Player and send it to dad as a PPT"))
    T.eq("'make a pdf' asks for a file", "pdf", Checks.fileAsked("Make a PDF with a packing list"))
    T.eq("'create a word document' asks for a file", "docx", Checks.fileAsked("create a word document about solar power"))
    T.eq("'what is a pdf' asks for none", null, Checks.fileAsked("what is a pdf"))
    T.eq("a plain message asks for none", null, Checks.fileAsked("send dad a message that I will be late"))
    val msgOnly = listOf(Did("files_find", true), Did("send_whatsapp", true))
    val c1 = Checks.beforeDone("send the movie list to dad as a PPT", null, msgOnly, emptySet())
    T.eq("a message instead of the PPT is caught", "file", c1?.key)
    T.check("the reminder names the tool", c1?.text?.contains("doc_create") == true)
    T.eq("caught only once", null, Checks.beforeDone("send the movie list to dad as a PPT", null, msgOnly, setOf("file", "send")))
    val made = listOf(Did("doc_create", true))
    T.eq("a file made but not sent is caught", "send", Checks.beforeDone("make a ppt on solar and send it to dad", null, made, emptySet())?.key)
    T.eq("a file made and sent is done", null, Checks.beforeDone("make a ppt on solar and send it to dad", null, made + Did("send_file", true), emptySet()))
    T.eq("a file made, nothing to send, is done", null, Checks.beforeDone("make a ppt on solar power", null, made, emptySet()))
    T.eq("the user said no to sending: done", null, Checks.beforeDone("make a ppt on solar and send it to dad", null, made + Did("send_file", false, denied = true), emptySet()))
    T.eq("an ordinary question is done", null, Checks.beforeDone("what is the weather", null, listOf(Did("weather", true)), emptySet()))
    val gaveUp = listOf(Did("open_app", true), Did("screen_tap", true), Did("screen_tap", false))
    T.eq("an order that stopped half-way is caught", "purchase", Checks.beforeDone("order a biryani", Preflight.Kind.FOOD, gaveUp, emptySet())?.key)
    T.check("the reminder says how to recover", Checks.beforeDone("order a biryani", Preflight.Kind.FOOD, gaveUp, emptySet())?.text?.contains("open_app again") == true)
    T.eq("…but only once", null, Checks.beforeDone("order a biryani", Preflight.Kind.FOOD, gaveUp, setOf("purchase")))
    val atPay = gaveUp + Did("screen_tap", false, JSONObject().put("error", "\"Proceed to Pay\" is a payment or final-confirmation step. Seyal never presses those."))
    T.eq("an order at the payment step is done", null, Checks.beforeDone("order a biryani", Preflight.Kind.FOOD, atPay, emptySet()))
    T.eq("movie at payment without a seat check is caught", "seats", Checks.beforeDone("book 2 tickets", Preflight.Kind.MOVIE, atPay, emptySet())?.key)
    T.eq("movie at payment with seats checked is done", null, Checks.beforeDone("book 2 tickets", Preflight.Kind.MOVIE, atPay + Did("seats_check", true), emptySet()))
    T.eq("one ticket needs no seat check", null, Checks.beforeDone("book 1 ticket", Preflight.Kind.MOVIE, atPay, emptySet(), manyPeople = false))
    T.eq("nothing opened (app missing): no nagging", null, Checks.beforeDone("order a biryani", Preflight.Kind.FOOD, listOf(Did("web_search", true)), emptySet()))

    println("Seats together")
    T.check("A5 A6 are together", Seats.check(listOf("A5", "A6"), 2).together)
    T.check("A6 A5 A7 are together", Seats.check(listOf("A6", "A5", "A7"), 3).together)
    T.check("A5 and C7 are not", !Seats.check(listOf("A5", "C7"), 2).together)
    T.check("A5 and A7 leave a gap", !Seats.check(listOf("A5", "A7"), 2).together)
    T.check("the advice says to ask before splitting", Seats.check(listOf("A5", "C7"), 2).message.contains("ask_user"))
    T.check("two selected, three wanted is wrong", !Seats.check(listOf("A5", "A6"), 3).ok)
    T.check("one seat is fine", Seats.check(listOf("H12"), 1).together)
    T.check("'C-12' and 'C-13' are read", Seats.check(listOf("C-12", "C-13"), 2).together)
    T.check("'Row B Seat 7' is read", Seats.parse("Row B Seat 7")?.toString() == "B7")
    T.eq("a seat line is split", listOf("A5", "A6", "A7"), Seats.split("A5, A6 and A7"))
    T.check("rubbish is refused", !Seats.check(listOf("premium"), 1).ok)

    println("A tap the app ignored")
    T.check("a dish row may be pressed again", TapRules.safeToPressAgain("Chicken Biryani · ₹220"))
    T.check("Search may be pressed again", TapRules.safeToPressAgain("Search for dishes"))
    T.check("Play is not pressed twice", !TapRules.safeToPressAgain("Play"))
    T.check("Send is not pressed twice", !TapRules.safeToPressAgain("Send"))
    T.check("an unnamed icon is not pressed twice", !TapRules.safeToPressAgain("(unlabeled)"))

    println("Self-checks stay out of the conversation")
    val s = Session("t1", 1L)
    s.messages += Msg("user", listOf(Part.Text("send the list as a PPT")))
    s.messages += Msg("assistant", listOf(Part.Text("I sent it as a message.")))
    s.messages += Msg("user", listOf(Part.Text(Agent.CHECK + "the user asked for a file")))
    T.eq("the user's last words skip the self-check", "send the list as a PPT", Agent.lastSaid(s))
    T.eq("only the user's words count", listOf("send the list as a PPT"), Agent.userWords(s))
    T.check("the self-check is not from the user", !Agent.fromUser(s.messages.last()))
}

fun testPhase4() {
    val zone = java.time.ZoneId.systemDefault()
    val nowDt = java.time.LocalDateTime.of(2026, 11, 12, 10, 0) // a Thursday
    val now = nowDt.atZone(zone).toInstant().toEpochMilli()
    fun at(y: Int, m: Int, d: Int, h: Int = 23, min: Int = 59) = java.time.LocalDateTime.of(y, m, d, h, min)

    println("The order is written down")
    T.eq("'2 tickets' is 2", 2, Order.countIn(listOf("Sangeetha Somanur, book 2 tickets, max 250 per ticket")))
    T.eq("'rendu ticket' is 2", 2, Order.countIn(listOf("rendu ticket venum")))
    T.eq("a bare '3' answer is 3", 3, Order.countIn(listOf("book tickets for Leo", "3, balcony")))
    T.eq("a bare 'two' answer is 2", 2, Order.countIn(listOf("book tickets for Leo", "two")))
    val chat = Session("t9", 1L)
    chat.messages += Msg("user", listOf(Part.Text("Book 6 tickets for Leo at PVR")))
    chat.messages += Msg("assistant", listOf(Part.Text("Ready at the payment step.")))
    chat.messages += Msg("user", listOf(Part.Text("Book tickets for Ramayana at Sangeetha Somanur")))
    T.eq("an older request's number is not this request's", 0, Order.countIn(Agent.requestWords(chat)))
    T.check("…so the count is asked again", !Preflight.saysHowMany(Agent.requestWords(chat)))
    T.eq("the newest answer wins", 4, Order.countIn(listOf("2 tickets", "make it 4 tickets")))
    T.eq("a time is not a count", 0, Order.countIn(listOf("book tickets after 6 pm at Broadway")))
    T.eq("'max 250 per ticket' is the limit", 250, Order.budgetIn(listOf("Maximum I can spend 250 per ticket")))
    T.eq("'under ₹300' is the limit", 300, Order.budgetIn(listOf("2 tickets under ₹300")))
    T.eq("no limit said", 0, Order.budgetIn(listOf("2 tickets for tonight")))
    val order = Order.fromJson(JSONObject("""{"kind":"MOVIE","item":"Ramayana","place":"Sangeetha Somanur","date":"2026-11-13","count":2,"max_each":250,"seat":"any"}"""))
    T.eq("'any' seat means left open", "", order.seat)
    T.check("the order text names the place and the count", order.text().let { it.contains("Sangeetha Somanur") && it.contains("exactly 2") })
    T.check("Sangeeta / Sangeetha are the same place", Order.samePlace("Sangeetha Somanur", "Sangeeta Cinemas, Somanur"))
    T.check("PVR is not Sangeetha", !Order.samePlace("Sangeetha Somanur", "PVR: Brookefields Mall, Coimbatore"))
    T.check("Broadway matches Broadway Cinemas Coimbatore", Order.samePlace("Broadway", "Broadway Cinemas: Coimbatore"))
    T.check("KG is not Broadway", !Order.samePlace("Broadway Cinemas", "KG Cinemas"))
    val wrong = order.mismatches(JSONObject("""{"place":"PVR Brookefields","date":"2026-11-15","count":6,"price_each":190}"""))
    T.eq("wrong theatre, day and count are all caught", 3, wrong.size)
    T.check("the count message says what to do", wrong.any { it.contains("exactly 2") })
    T.eq("the right booking passes", 0, order.mismatches(JSONObject("""{"place":"Sangeetha Cinemas Somanur","date":"2026-11-13","count":2,"price_each":190}""")).size)
    T.eq("a booking fee on top is fine", 0, order.mismatches(JSONObject("""{"place":"Sangeetha Cinemas Somanur","date":"2026-11-13","count":2,"price_each":285}""")).size)
    T.eq("a dearer class is caught", 1, order.mismatches(JSONObject("""{"place":"Sangeetha Cinemas Somanur","date":"2026-11-13","count":2,"price_each":450}""")).size)
    val unread = mutableListOf<String>()
    order.mismatches(JSONObject("""{"count":2}"""), unread)
    T.eq("what was not read off the screen is asked for", 3, unread.size)
    T.eq("total / count gives the price of one", 0, order.mismatches(JSONObject("""{"place":"Sangeetha Somanur","date":"2026-11-13","count":2,"total":400}""")).size)
    T.eq("bus seat 'L5' is a seat", "L5", Seats.any("L5"))
    T.eq("bus seat '12' is a seat", "12", Seats.any("12"))
    T.eq("'premium' is not a seat", null, Seats.any("premium"))
    val atPay = listOf(Did("open_app", true), Did("screen_tap", false, JSONObject().put("error", "\"Pay\" is a payment or final-confirmation step.")))
    T.eq("with an order, payment step unchecked is caught", "verify", Checks.beforeDone("book 2 tickets", Preflight.Kind.MOVIE, atPay, emptySet(), hasOrder = true)?.key)
    T.eq("a failed order check is caught", "mismatch", Checks.beforeDone("book 2 tickets", Preflight.Kind.MOVIE, atPay + Did("order_check", false), setOf("verify"), hasOrder = true)?.key)
    T.eq("a passed order check is done", null, Checks.beforeDone("book 2 tickets", Preflight.Kind.MOVIE, atPay + Did("order_check", false) + Did("order_check", true), emptySet(), hasOrder = true))
    T.check("the movie notes cover theatre, day and count", Lessons.textFor(listOf("BookMyShow")).let { it.contains("by its NAME") && it.contains("date strip") && it.contains("already chosen") })
    T.check("the bus notes cover seats and boarding", Lessons.textFor(listOf("redBus")).let { it.contains("Lower and Upper") && it.contains("boarding point") })

    println("The brain: done before, done faster")
    val dir = java.io.File(System.getProperty("java.io.tmpdir"), "seyal-test-" + System.nanoTime()).apply { mkdirs() }
    Routines.init(dir)
    T.eq("politeness and case don't matter", Routines.normalise("torch on"), Routines.normalise("Please, Torch ON!"))
    fun step(n: String, vararg kv: Pair<String, Any>) = Routines.Step(n, JSONObject().apply { kv.forEach { put(it.first, it.second) } })
    val dnd = listOf(step("set_setting", "name" to "dnd", "value" to "on"), step("set_setting", "name" to "brightness", "value" to "30"))
    Routines.learn("Turn on do not disturb and set brightness to 30", dnd, true, false, now)
    T.check("the same words again run without the AI", Routines.exact("turn on Do Not Disturb and set brightness to 30 please")?.steps?.size == 2)
    T.eq("different words do not", null, Routines.exact("turn off do not disturb"))
    Routines.learn("Send good morning to dad on WhatsApp", listOf(step("contacts_search", "name" to "dad"), step("send_whatsapp", "phone" to "+919876500001", "text" to "Good morning")), true, false, now)
    T.check("a fixed message to a fixed person can be repeated", Routines.exact("send good morning to dad on whatsapp") != null)
    Routines.learn("Set an alarm for 7 am tomorrow", listOf(step("alarm_set", "time" to "2026-11-13T07:00")), true, false, now)
    T.eq("anything with a day in it is not repeated blindly", null, Routines.exact("set an alarm for 7 am tomorrow"))
    Routines.learn("What is the weather in Coimbatore", listOf(step("weather", "place" to "Coimbatore")), true, true, now)
    T.eq("a question is always asked fresh", null, Routines.exact("what is the weather in coimbatore"))
    Routines.learn("Send it to him", listOf(step("send_whatsapp", "phone" to "1", "text" to "x")), true, false, now)
    T.eq("'it' and 'him' are never remembered", null, Routines.similar("send it to him", 0.99))
    Routines.learn("Open YouTube and play lofi music", listOf(step("open_app", "name" to "YouTube"), step("screen_tap", "label" to "Search"), step("screen_type", "text" to "lofi music")), true, false, now)
    T.eq("screen work is not repeated blindly", null, Routines.exact("open youtube and play lofi music"))
    T.check("…but a similar request gets a head start", Routines.similar("Open YouTube and play tamil songs")?.said?.contains("lofi") == true)
    T.check("the head start lists the steps", Routines.hint(Routines.similar("Open YouTube and play tamil songs")!!).contains("screen_tap"))
    T.eq("an unrelated request gets none", null, Routines.similar("how much storage is free"))
    Routines.learn("Order a biryani", listOf(step("ask_user", "question" to "which"), step("open_app", "name" to "Swiggy"), step("order_check", "count" to 1)), true, false, now)
    T.eq("questions and checks are left out of what is remembered", 1, Routines.similar("order a biryani")?.steps?.size)
    Routines.learn("Make a note file", listOf(step("doc_create", "kind" to "txt", "text" to "x".repeat(900))), true, false, now)
    T.eq("a step too long to keep whole is not repeated blindly", null, Routines.exact("make a note file"))
    Routines.learn("Lower the volume", listOf(step("set_setting", "name" to "volume_media", "value" to "20")), false, false, now)
    T.eq("a run where something failed is not remembered", null, Routines.exact("lower the volume"))
    Routines.distrust("Turn on do not disturb and set brightness to 30")
    T.eq("a shortcut that failed is no longer used", null, Routines.exact("turn on do not disturb and set brightness to 30"))
    Routines.init(dir)
    T.check("it survives a restart", Routines.count() >= 4)

    println("Old parts of a chat are made small")
    val big = Msg("tool", listOf(Part.Result("c1", "web_search", JSONObject().put("ok", true).put("results", "x".repeat(4000)).put("link", "https://example.com/a"))))
    val small = History.shrink(big) { "Searched the web" }
    T.check("a long old result becomes a line", small.results[0].output.toString().length < 200 && small.results[0].output.optString("earlier_result") == "Searched the web")
    T.eq("…but keeps the link someone may point back to", "https://example.com/a", small.results[0].output.optString("link"))
    val tiny = Msg("tool", listOf(Part.Result("c2", "doc_create", JSONObject().put("ok", true).put("uri", "content://x/1").put("name", "Solar.pptx"))))
    T.check("a short result is left alone", History.shrink(tiny) { null }.results[0].output.optString("uri") == "content://x/1")
    val call = Msg("assistant", listOf(Part.Call("c3", "doc_create", JSONObject().put("kind", "pptx").put("title", "Solar").put("sections", org.json.JSONArray(List(8) { "y".repeat(100) })), "sig")))
    val cut = History.shrink(call) { null }
    T.check("a long old call keeps its name and loses its bulk", cut.calls[0].name == "doc_create" && cut.calls[0].args.toString().length < 200 && cut.calls[0].signature == "sig")

    println("Dates in messages")
    fun d(text: String) = Dates.find(text, nowDt)?.at
    T.eq("15/11", at(2026, 11, 15), d("Last date 15/11"))
    T.eq("15-11-2026", at(2026, 11, 15), d("submit before 15-11-2026"))
    T.eq("20th Nov", at(2026, 11, 20), d("Fee payment due on 20th Nov"))
    T.eq("November 20, 5 pm", at(2026, 11, 20, 17, 0), d("Registration closes on November 20 at 5 pm"))
    T.eq("tomorrow", at(2026, 11, 13), d("Record submission tomorrow"))
    T.eq("naalaikku", at(2026, 11, 13), d("naalaikku last date da"))
    T.eq("today 4.30pm", at(2026, 11, 12, 16, 30), d("fill the form by today 4.30pm"))
    T.eq("Friday", at(2026, 11, 13), d("Assignment to be submitted by Friday"))
    T.eq("next Thursday", at(2026, 11, 19), d("test on next Thursday"))
    T.eq("January is next year", at(2027, 1, 5), d("apply before 5 Jan"))
    T.eq("in 3 days", at(2026, 11, 15), d("your trial ends in 3 days"))
    T.eq("no date", null, d("please check the group"))
    T.eq("a version number is not a date", null, d("update to version 3.5 now"))
    T.eq("the date after the deadline word wins", at(2026, 11, 18), Dates.find("Exam on 25/11. Last date to pay fee: 18/11", nowDt, "Exam on 25/11. ".length)?.at)

    println("Deadlines and bills caught from messages")
    var n = 0
    fun scan(app: String, chat: String, text: String) = Catcher.scan(app, chat, text, now) { "id${n++}" }
    val form = scan("WhatsApp", "CSE Final Year: Placement Cell", "TCS drive registration\nAll eligible students fill the form before 14/11 5 PM\nhttps://forms.gle/AbCd123")
    T.eq("a placement form in a group is a deadline", "deadline", form?.kind)
    T.eq("…with its link", "https://forms.gle/AbCd123", form?.link)
    T.eq("…and its time", at(2026, 11, 14, 17, 0).atZone(zone).toInstant().toEpochMilli(), form?.due)
    T.eq("…titled by its first line", "TCS drive registration", form?.title)
    T.eq("exam fee last date", "deadline", scan("WhatsApp", "Class 3B: Staff", "Exam fee last date is 20 Nov. Pay in the office.")?.kind)
    T.eq("an electricity bill", "bill", scan("Messages", "TNEB", "Your EB bill of Rs. 1,240 is due on 18/11/2026. Pay to avoid disconnection.")?.kind)
    T.eq("…with the amount", 1240, scan("Messages", "TNEB-2", "Your EB bill of Rs. 1,240 is due on 19/11/2026.")?.amount)
    T.eq("a trial about to charge", "renewal", scan("Gmail", "Hotstar", "Your free trial ends on 14 Nov. You will be charged ₹299 after that.")?.kind)
    T.eq("a plan expiring", "recharge", scan("Messages", "Jio", "Your plan expires tomorrow. Recharge now to continue.")?.kind)
    T.eq("a return window", "return", scan("Flipkart", "Flipkart", "Return window for your order closes on 15 Nov.")?.kind)
    T.eq("an OTP is never read", null, scan("Messages", "Bank", "OTP 123456 for payment due on 15/11. Do not share."))
    T.eq("a sale is not a deadline", null, scan("Messages", "Shop", "Big sale! 50% off. Offer ends on 15/11. Register now"))
    T.eq("chat without a date is nothing", null, scan("WhatsApp", "Ram", "did you submit the assignment?"))
    T.eq("ordinary talk is nothing", null, scan("WhatsApp", "Amma", "Come home early today, we have guests"))
    T.eq("a deadline long past is not news", null, scan("WhatsApp", "Class", "Last date was 01/11 for registration"))
    val day = 24 * 3600_000L
    val due = at(2026, 11, 14, 17, 0).atZone(zone).toInstant().toEpochMilli()
    val times = Catcher.reminderTimes(due, true, now)
    T.eq("reminders: evening before, morning of, two hours before", 3, times.size)
    T.eq("the first is 6 pm the day before", at(2026, 11, 13, 18, 0).atZone(zone).toInstant().toEpochMilli(), times[0])
    T.eq("none after the deadline", 0, Catcher.reminderTimes(due, true, due + 1).size)
    T.eq("'tomorrow' is said as tomorrow", "tomorrow", Catcher.whenText(now + day, false, now))
    Inbox.init(dir)
    T.check("a new catch is added", Inbox.add(form!!, now))
    T.check("the same form again is not", !Inbox.add(Catcher.scan("WhatsApp", "Another group", "Reminder: fill the form before 14/11 https://forms.gle/AbCd123", now)!!, now))
    T.eq("one thing waiting", 1, Inbox.open(now).size)
    Inbox.told(form.id, now)
    T.eq("nothing to remind right after telling", 0, Inbox.dueReminders(now + 60_000).size)
    T.eq("the next reminder is the evening before", times[0], Inbox.nextReminder(now))
    T.eq("…and it is due then", 1, Inbox.dueReminders(times[0]).size)
    Inbox.mark(form.id, "done")
    T.eq("done things stop reminding", null, Inbox.nextReminder(now))
    T.eq("…and leave the list", 0, Inbox.open(now).size)

    println("Watching pages")
    fun w(mode: String, value: String, near: String = "", last: String = "") = Watcher("w1", "test", "https://example.com", mode, value, near, lastSeen = last)
    val page = "Leo (Tamil) Broadway Cinemas Coming soon. Notify me. Phone X Pro 128GB ₹27,999 Buy now. Phone Y ₹14,499. " + "More about films, shows, phones and prices on this page. ".repeat(4)
    T.check("words not there yet: no news", !Watchers.evaluate(w("contains", "Book tickets"), page).hit)
    T.check("words appeared: news", Watchers.evaluate(w("contains", "book tickets"), page.replace("Coming soon", "Book tickets")).hit)
    T.check("'Coming soon' gone: news", Watchers.evaluate(w("gone", "Coming soon"), page.replace("Coming soon", "Book tickets")).hit)
    T.eq("the price after the product's name", 27999, Watchers.priceIn(page, "Phone X Pro"))
    T.eq("the first price when no name is given", 27999, Watchers.priceIn(page, ""))
    T.check("price above the limit: no news", !Watchers.evaluate(w("price_below", "25000", "Phone X Pro"), page).hit)
    T.check("price at the limit: news", Watchers.evaluate(w("price_below", "28000", "Phone X Pro"), page).hit)
    T.check("the right product's price is used", Watchers.evaluate(w("price_below", "15000", "Phone Y"), page).hit)
    val first = Watchers.evaluate(w("changed", "", "Leo (Tamil)"), page)
    T.check("the first look only notes the page", !first.hit && first.seen.isNotBlank())
    T.check("the same page later: no news", !Watchers.evaluate(w("changed", "", "Leo (Tamil)", first.seen), page).hit)
    T.check("a changed page: news", Watchers.evaluate(w("changed", "", "Leo (Tamil)", first.seen), page.replace("Coming soon", "Book tickets")).hit)
    T.check("an empty page cannot be watched", !Watchers.readable("Loading…"))
    Watchers.init(dir)
    val watch = w("contains", "Book tickets"); Watchers.add(watch)
    T.eq("no news is no notification", null, Watchers.record(watch, page, now))
    T.check("news is told", Watchers.record(watch, page.replace("Coming soon", "Book tickets"), now + 3600_000)?.contains("Book tickets") == true)
    T.check("…and that watcher is finished", !watch.enabled && Watchers.nextCheck(now) == null)
    val flaky = Watcher("w2", "flaky", "https://example.com", "contains", "x", everyMin = 30); Watchers.add(flaky)
    var said: String? = null
    repeat(Watchers.MAX_FAILS) { said = Watchers.record(flaky, "Loading…", now) }
    T.check("a page that stays unreadable is given up, and the user is told", said != null && !flaky.enabled)
    val offline = Watcher("w3", "offline", "https://example.com", "contains", "x", everyMin = 30); Watchers.add(offline)
    repeat(Watchers.MAX_FAILS + 2) { Watchers.record(offline, null, now) }
    T.check("no network for a while is not a reason to stop", offline.enabled)
    T.eq("the next check is half an hour on", now + 30 * 60_000L, Watchers.nextCheck(now))

    println("Busy mode")
    T.check("two calls in ten minutes are urgent", BusyRules.urgent(listOf(now - 4 * 60_000, now), now))
    T.check("two calls an hour apart are not", !BusyRules.urgent(listOf(now - 60 * 60_000, now), now))
    T.check("one call is not", !BusyRules.urgent(listOf(now), now))
    T.check("a saved contact may be texted once", BusyRules.mayText("Amma", emptySet(), 0))
    T.check("…not twice", !BusyRules.mayText("Amma", setOf("amma"), 1))
    T.check("an unknown number is never texted", !BusyRules.mayText("+91 98765 00001", emptySet(), 0))
    T.check("'Unknown' is never texted", !BusyRules.mayText("Unknown caller", emptySet(), 0))
    T.check("there is a cap on texts", !BusyRules.mayText("Ram", emptySet(), BusyRules.MAX_TEXTS))
    T.eq("the call-back list", "Amma (2), Ram", BusyRules.summary(mapOf("Ram" to 1, "Amma" to 2)))
    dir.deleteRecursively()
}

fun testBasics() {
    val now = java.time.LocalDateTime.of(2026, 11, 16, 10, 0)
    fun plan(text: String, wa: Boolean = true) = Basics.plan(text, now, wa)

    println("Everyday commands need no AI: calls")
    T.eq("call amma", "Amma".lowercase(), plan("Call Amma")?.who)
    T.eq("…is a call", "call", plan("Call Amma")?.tool)
    T.eq("amma ku call pannu", "amma", plan("Amma ku call pannu")?.who)
    T.eq("please call my dad", "dad", plan("Please call my dad")?.who)
    T.eq("ring ram kumar", "ram kumar", plan("ring Ram Kumar")?.who)
    T.eq("அம்மாக்கு கால் பண்ணு", "call", plan("அம்மாக்கு கால் பண்ணு")?.tool)
    T.eq("call me a cab is not a contact", null, plan("call me a cab"))
    T.eq("call him back is for the AI", null, plan("call him back"))
    T.eq("call the last missed number is for the AI", null, plan("call the last missed number"))
    T.eq("call customer care is for the AI", null, plan("call airtel customer care"))
    T.eq("call ram and tell him… is for the AI", null, plan("call ram and tell him I am late"))

    println("Everyday commands: messages")
    val m1 = plan("Send a message to my dad saying I will be late today")
    T.eq("message to dad: who", "dad", m1?.who)
    T.eq("…the words, as typed", "I will be late today", m1?.args?.optString("text"))
    T.eq("…on WhatsApp when it is installed", "send_whatsapp", m1?.tool)
    T.eq("…by SMS when it is not", "send_sms", plan("Send a message to my dad saying I will be late today", wa = false)?.tool)
    T.eq("sms is sms", "send_sms", plan("send an sms to Ram saying call me")?.tool)
    val m2 = plan("Tell Amma that I reached Coimbatore")
    T.eq("tell amma that…", "amma", m2?.who)
    T.eq("…keeps capitals", "I reached Coimbatore", m2?.args?.optString("text"))
    val m3 = plan("send good morning to dad on whatsapp")
    T.eq("send X to Y on whatsapp", "Good morning", m3?.args?.optString("text"))
    T.eq("…to dad", "dad", m3?.who)
    val m4 = plan("dad ku I will be late nu message pannu")
    T.eq("tanglish message", "I will be late", m4?.args?.optString("text"))
    T.eq("a message for later is for the AI", null, plan("send a message to dad saying good morning at 8 pm"))
    T.eq("two jobs in one are for the AI", null, plan("send a message to dad saying happy birthday and call him"))
    T.eq("tell me a joke is not a message", null, plan("tell me that joke again"))
    T.eq("send the file to dad is for the AI", null, plan("send the file to dad"))
    T.eq("send it to him is for the AI", null, plan("send it to him on whatsapp"))
    T.eq("send money is for the AI", null, plan("send 500 rs to ram"))
    T.eq("message everyone is for the AI", null, plan("message everyone that the class is cancelled"))
    T.eq("a message to a group is for the AI", null, plan("send a message to the class group saying hi"))

    println("Everyday commands: alarms, reminders, timers")
    T.eq("alarm for 6:30 am is tomorrow", "2026-11-17T06:30", plan("Set an alarm for 6:30 am")?.args?.optString("at"))
    T.eq("alarm at 5 pm is today", "2026-11-16T17:00", plan("alarm at 5 pm")?.args?.optString("at"))
    T.eq("wake me up at 5.30 am", "2026-11-17T05:30", plan("wake me up at 5.30 am")?.args?.optString("at"))
    T.eq("alarm for 11 am tomorrow", "2026-11-17T11:00", plan("set alarm for 11 am tomorrow")?.args?.optString("at"))
    T.eq("a bare 7 is not clear enough", null, plan("set an alarm for 7"))
    val r1 = plan("Remind me to call the bank at 4 pm")
    T.eq("remind me to … at 4 pm", "2026-11-16T16:00", r1?.args?.optString("at"))
    T.eq("…what", "Call the bank", r1?.args?.optString("text"))
    val r2 = plan("remind me in 20 minutes to drink water")
    T.eq("remind me in 20 minutes", "2026-11-16T10:20", r2?.args?.optString("at"))
    T.eq("…what", "Drink water", r2?.args?.optString("text"))
    T.eq("remind me … in 2 hours", "2026-11-16T12:00", plan("remind me to take tablets in 2 hours")?.args?.optString("at"))
    T.eq("remind me at 6 pm to …", "Pay the fee", plan("remind me at 6 pm to pay the fee")?.args?.optString("text"))
    T.eq("timer 10 minutes", 10, plan("set a timer for 10 minutes")?.args?.optInt("minutes"))
    T.eq("1 hour timer", 60, plan("timer for 1 hour")?.args?.optInt("minutes"))
    T.eq("a reminder with a day is for the AI", null, plan("remind me to pay the fee on Friday"))

    println("Everyday commands: phone and facts")
    T.check("what time is it", plan("What time is it?")?.say?.contains("10:00 AM") == true)
    T.check("today's date", plan("what is the date today")?.say?.contains("16 November 2026") == true)
    T.eq("battery", "phone_state", plan("battery level")?.tool)
    T.eq("…worded", "Battery is at 82%, charging.", plan("how much battery is left")?.answer?.invoke(JSONObject().put("battery_percent", 82).put("charging", true)))
    T.eq("weather", "weather", plan("What is the weather in Coimbatore today?")?.tool)
    T.eq("…the place", "coimbatore", plan("What is the weather in Coimbatore today?")?.args?.optString("place"))
    T.eq("weather alone uses the home city", "", plan("weather")?.args?.optString("place"))
    T.eq("chennai weather", "chennai", plan("Chennai weather")?.args?.optString("place"))
    T.eq("tomorrow's weather is answered from the forecast", "weather", plan("what is the weather tomorrow")?.tool)
    T.eq("a whole week's weather is for the AI", null, plan("what is the weather this week"))
    T.eq("volume 40", "40", plan("set the volume to 40")?.args?.optString("value"))
    T.eq("mute", "0", plan("mute")?.args?.optString("value"))
    T.eq("bluetooth on", "bluetooth", plan("turn on bluetooth")?.args?.optString("name"))
    T.eq("wifi off", "off", plan("wifi off")?.args?.optString("value"))
    T.check("navigate", plan("Navigate to Gandhipuram bus stand")?.args?.optString("url")?.contains("destination=Gandhipuram+bus+stand") == true)
    T.check("youtube search", plan("search lofi songs on youtube")?.args?.optString("url")?.contains("search_query=lofi+songs") == true)
    T.check("google", plan("google best laptops under 50000")?.args?.optString("url")?.contains("q=best+laptops+under+50000") == true)

    println("Everyday commands: Seyal's own lists")
    T.eq("what deadlines do I have", "inbox_list", plan("What deadlines do I have?")?.tool)
    T.eq("any bills due", "inbox_list", plan("any bills due")?.tool)
    T.check("…worded from the list", plan("my deadlines")?.answer?.invoke(JSONObject().put("items", JSONArray().put(JSONObject().put("due", "tomorrow 5:00 PM").put("what", "TCS drive registration").put("link", "https://forms.gle/x"))))?.contains("TCS drive registration") == true)
    T.check("…and when empty", plan("my deadlines")?.answer?.invoke(JSONObject().put("items", JSONArray()))?.contains("Nothing is waiting") == true)
    T.eq("my summary for today", "daily_brief", plan("Give me my summary for today")?.tool)
    T.eq("what's on today", "daily_brief", plan("what's on today")?.tool)
    T.eq("what are you watching", "watch_list", plan("What are you watching for me?")?.tool)
    T.eq("end busy mode", false, plan("End busy mode")?.args?.optBoolean("on", true))

    println("Everyday commands: anything else is left to the AI")
    for (x in listOf("Book 2 tickets for Leo", "Order a biryani", "Make a PowerPoint about solar power", "What notifications do I have right now?", "Open YouTube and play lofi", "Give me todays top AI news in 5 points",
        "Play the oldest video from the YouTube channel @Google", "Find buses from Coimbatore to Bangalore this Friday night", "Remember that my favourite theatre is Broadway Cinemas", "Every morning at 8, give me AI news",
        "Tomorrow at 8 am send a WhatsApp to my dad saying good morning", "Whenever Amma messages asking where are you, tell her my location", "When the battery goes below 20 percent, set the brightness to 10", "how are you", "And tomorrow?",
        "Tell me when the page https://example.com says Book tickets", "I am in class till 4 pm, tell people who call that I will call back after 4", "tell ram to call me")) T.eq("not basic: $x", null, plan(x))

    println("Which contact")
    T.eq("one match", "Amma", Basics.pick("amma", listOf("Amma" to "+919876500004"))?.first)
    T.eq("two entries, one number", "Appa", Basics.pick("dad", listOf("Appa" to "+91 98765 00001", "Appa Jio" to "9876500001"))?.first)
    T.eq("the exact name among several", "Ram", Basics.pick("Ram", listOf("Ram Kumar" to "111", "Ram" to "222", "Ram Project" to "333"))?.first)
    T.eq("several different people: ask", null, Basics.pick("Ram", listOf("Ram Kumar" to "+919876500002", "Ram Project" to "+919876500003")))
    T.eq("nobody", null, Basics.pick("xyz", emptyList()))

    println("A booking is only a booking")
    T.eq("a watcher is not a booking", null, Preflight.kind("Tell me when the page https://example.com says Book tickets"))
    T.eq("tell me when bookings open", null, Preflight.kind("Tell me when Leo bookings open at Broadway, I want to book tickets"))
    T.eq("a reminder to book", null, Preflight.kind("Remind me to book train tickets tomorrow"))
    T.eq("reading a book", null, Preflight.kind("Summarise the book Atomic Habits for me"))
    T.eq("a good book to read", null, Preflight.kind("suggest a good book to read on the bus"))
    T.eq("showing my orders", null, Preflight.kind("Show my Amazon orders"))
    T.eq("checking and then booking is one", Preflight.Kind.TRAVEL, Preflight.kind("Check buses to Chennai on Friday and book the cheapest"))
    T.eq("booking a table is not a shop order", null, Preflight.kind("Book a table for two at Annapoorna tonight"))
    T.eq("buying a book is shopping", Preflight.Kind.SHOP, Preflight.kind("Buy the book Atomic Habits on Amazon"))
    T.eq("a plain movie booking still is", Preflight.Kind.MOVIE, Preflight.kind("Book 2 tickets for Ramayana at Sangeetha Somanur"))
    T.eq("a plain food order still is", Preflight.Kind.FOOD, Preflight.kind("Order a chicken biryani on Swiggy"))
    T.eq("a plain purchase still is", Preflight.Kind.SHOP, Preflight.kind("Order a phone charger from Flipkart"))

    println("No blind repeat taps")
    val g = RepeatGuard()
    g.tapped("A5 seat", 1000, unchanged = true)
    T.check("the same thing right again is held back", g.blocks("A5 seat", 2500))
    T.check("…once: the next try goes through", !g.blocks("A5 seat", 3000))
    g.tapped("Buy tickets", 5000, unchanged = true); g.looked()
    T.check("after a look it may be tapped again", !g.blocks("Buy tickets", 6000))
    g.tapped("Next", 7000, unchanged = false)
    T.check("when the screen moved, the same label is fine (Next, +)", !g.blocks("Next", 7500))
    g.tapped("Buy tickets", 8000, unchanged = true)
    T.check("another thing is never held back", !g.blocks("Cancel", 8500))
    g.tapped("Buy tickets", 9000, unchanged = true)
    T.check("after a while it is a new try", !g.blocks("Buy tickets", 30_000))
    T.check("a small page can be watched", Watchers.readable("Example Domain. This domain is for use in documentation examples."))
}

fun testBatch23() {
    val zone = java.time.ZoneId.systemDefault()
    val nowDt = java.time.LocalDateTime.of(2026, 11, 16, 10, 0) // a Monday
    val now = nowDt.atZone(zone).toInstant().toEpochMilli()
    val dir = java.io.File(System.getProperty("java.io.tmpdir"), "seyal-b23-" + System.nanoTime()).apply { mkdirs() }
    val min = 60_000L; val hour = 60 * min; val day = 24 * hour

    println("Scams are warned about")
    fun scam(sender: String, text: String) = Scam.check(sender, text)
    T.check("electricity cut tonight, call this number", scam("+91 98765 00011", "Dear Consumer Your Electricity power will be disconnected. Tonight at 9.30 pm from electricity office. because your previous month bill was not update. Please immediately contact with our electricity officer 8240471159 Thank you.") != null)
    T.check("…and it says why", scam("9876500011", "Dear customer your electricity will be disconnected tonight 9.30pm. Call officer 8240471159 immediately")?.why?.contains("electricity") == true)
    T.check("KYC with a short link", scam("9876500012", "Dear SBI user your YONO account will be blocked today. Please update your PAN card now click here http://bit.ly/sbi-kyc77") != null)
    T.check("you won a lottery", scam("+919876500013", "Congratulations! You have won Rs 25,00,000 in KBC lucky draw. To claim call 9876500099 or WhatsApp now.") != null)
    T.check("part-time job with daily pay", scam("+919876500014", "Hello, I am HR from Amazon. Part time job, work from home, earn Rs 3000-8000 daily. Contact on WhatsApp wa.link/x8kd") != null)
    T.check("share the OTP", scam("+919876500015", "Sir I am calling from bank, a code was sent to you by mistake, please share the OTP with me to cancel the transaction") != null)
    T.check("install a screen-sharing app", scam("9876500016", "Your refund is ready. Please install AnyDesk app and share the code with our executive to receive Rs 4,999.") != null)
    T.check("an app file to install", scam("9876500017", "Your PM Kisan payment is pending. Download and install pmkisan.apk from http://pmk-update.xyz/pmkisan.apk") != null)
    T.check("parcel stuck, pay a fee", scam("+919876500018", "India Post: Your package could not be delivered due to incomplete address. Update within 12 hrs: http://indiapost-redelivery.top/in") != null)
    T.check("digital arrest", scam("+919876500019", "This is CBI cyber crime department. Your Aadhaar was used in money laundering. You are under digital arrest. Stay on video call.") != null)
    T.check("account blocked unless you act", scam("8876500020", "Dear customer your HDFC account will be suspended within 24 hours. Complete KYC immediately: hdfc-kyc-verify.online/login") != null)
    T.check("a friend's 'new number' asking for money", scam("+919876500021", "Hi this is my new number. I am in hospital, urgently need money. Can you gpay 5000 now? Will return tomorrow.") != null)

    println("Real messages are left alone")
    T.eq("a real OTP", null, scam("AX-HDFCBK", "123456 is your OTP for txn of INR 250.00 at SWIGGY. Do not share it with anyone. -HDFC Bank"))
    T.eq("a real debit alert", null, scam("VM-SBIUPI", "Dear UPI user A/C X1234 debited by 250.0 on date 16Nov26 trf to SWIGGY Refno 631234567890. If not u? call 1800111109. -SBI"))
    T.eq("a real electricity bill", null, scam("VK-TNEBLT", "TANGEDCO: Your EB bill for Service No 0312345678 is Rs.1240. Due date 18/11/2026. Pay at https://www.tnebnet.org/awp/login"))
    T.eq("a real delivery update", null, scam("AD-AMAZON", "Your Amazon package with boAt Rockerz 255 is out for delivery. Track: https://amzn.in/d/abc123"))
    T.eq("a real KYC notice from the bank's sender, no link", null, scam("AD-ICICIB", "Dear Customer, your KYC is due for periodic update. Please visit your nearest ICICI Bank branch with ID proof."))
    T.eq("a class notice with a form", null, scam("CSE Final Year: Placement Cell", "TCS drive registration. All eligible students fill the form before 18/11 5 PM https://forms.gle/AbCd123"))
    T.eq("Amma asking to call", null, scam("Amma", "Call me when you are free. Did you eat?"))
    T.eq("a friend asking for a small payback", null, scam("Ram Kumar", "Bro yesterday's lunch share is 180, send when free"))
    T.eq("a recharge reminder", null, scam("JK-JIOINF", "Your Jio plan expires tomorrow. Recharge now on MyJio https://www.jio.com/recharge"))
    T.eq("a real job mail", null, scam("Zoho Careers", "Thank you for applying to Zoho. Your interview is scheduled on 20 Nov at 10 AM. Join: https://meet.google.com/abc-defg-hij"))
    T.check("a fake tax refund link", scam("9876500022", "Income Tax Dept: You are eligible for refund of Rs 15,490. Click here to claim today http://incometax-refund.top/claim") != null)
    T.check("SIM will be blocked, call for e-KYC", scam("+919876500023", "Dear customer, your Airtel SIM will be blocked in 24 hrs. Complete e-KYC by calling 9876500050") != null)
    T.check("a 'customs' parcel with drugs", scam("+919876500024", "This is Mumbai Police. Illegal drugs found in a parcel in your name. A case is registered against you. Call 9876500051 immediately.") != null)
    T.check("a 'task' job on Telegram", scam("CSE Final Year: +91 98765 00025", "Earn Rs 2000 daily by liking YouTube videos. No investment needed. Join t.me/easytask99") != null)
    T.eq("a job portal's alert", null, scam("VM-NAUKRI", "12 new work from home jobs match your profile. Apply now: https://www.naukri.com/mnj"))
    T.eq("the delivery person asking for the delivery code", null, scam("+919876500026", "Hi, I am your Amazon delivery partner. Please share the OTP when I reach your gate."))
    T.eq("a saved friend asking for money", null, scam("Ram Kumar", "Bro urgent, can you gpay 500 now? will return tomorrow"))
    T.eq("a classmate's new number, no money asked", null, scam("+919876500027", "Hi, this is Karthik from your class. This is my new number, please save it."))
    T.eq("a credit card bill from the bank", null, scam("AD-SBICRD", "Your SBI Credit Card bill of Rs 5,400 is due on 20 Nov. Pay via https://www.sbicard.com/pay"))
    T.eq("a real tax refund notice", null, scam("AD-ITDEPT", "Your Income Tax refund of Rs 12,340 has been credited to your bank account ending 1234."))
    T.eq("a friend talking about a police film", null, scam("Ram Kumar", "Bro that CBI movie is super, call me immediately after you watch it"))
    T.eq("a teacher warning the class about scams", null, scam("CSE Final Year: Prof. Lakshmi", "Students, be careful about fake internship messages. Do not pay any registration fee to anyone."))
    T.check("a trusted sender is not warned about", Scam.trusted("9876500011", "Amma\n9876500011"))
    T.check("an unknown one is", !Scam.trusted("9876500012", "Amma\n9876500011"))

    println("Payments are read from notifications")
    var n = 0
    fun pay(app: String, title: String, text: String, at: Long = now) = MoneyParser.parse(app, title, text, at) { "t${n++}" }
    val g1 = pay("Google Pay", "Google Pay", "Ram Kumar paid you ₹500.00")
    T.eq("Google Pay: paid you", 500, g1?.amount); T.eq("…incoming", true, g1?.incoming); T.eq("…from", "Ram Kumar", g1?.party)
    val p1 = pay("PhonePe", "PhonePe", "Received ₹1,250 from Priya S")
    T.eq("PhonePe: received", 1250, p1?.amount); T.eq("…from", "Priya S", p1?.party)
    val pt = pay("Paytm", "Paytm", "Payment of Rs.80 received from Suresh Tea Stall customer")
    T.eq("Paytm: payment received", 80, pt?.amount); T.eq("…incoming", true, pt?.incoming)
    val sbi = pay("Messages", "VM-SBIUPI", "Dear UPI user A/C X1234 debited by 250.0 on date 16Nov26 trf to SWIGGY Refno 631234567890. If not u? call 1800111109. -SBI")
    T.eq("SBI debit", 250, sbi?.amount); T.eq("…outgoing", false, sbi?.incoming); T.eq("…to", "SWIGGY", sbi?.party); T.eq("…kind", "food", sbi?.category); T.eq("…reference", "631234567890", sbi?.ref)
    val hdfc = pay("Messages", "AX-HDFCBK", "Rs 500.00 credited to HDFC Bank A/c xx1234 on 16-11-26 from VPA ramkumar@okaxis (UPI 631234567891)")
    T.eq("HDFC credit", 500, hdfc?.amount); T.eq("…incoming", true, hdfc?.incoming); T.eq("…from", "ramkumar", hdfc?.party)
    val icici = pay("Messages", "AD-ICICIB", "ICICI Bank Acct XX123 debited for Rs 1,499.00 on 16-Nov-26; AMAZON PAY credited. UPI:631234567892. Call 18002662 for dispute.")
    T.eq("ICICI debit, merchant credited", false, icici?.incoming); T.eq("…amount", 1499, icici?.amount); T.eq("…to", "AMAZON PAY", icici?.party); T.eq("…kind", "shopping", icici?.category)
    val gout = pay("Google Pay", "Google Pay", "You paid ₹120.00 to Sri Krishna Sweets")
    T.eq("Google Pay: you paid", false, gout?.incoming); T.eq("…kind", "food", gout?.category)
    T.eq("a fuel payment is travel", "travel", pay("PhonePe", "PhonePe", "Paid ₹700 to HP Petrol Bunk Ukkadam")?.category)
    T.eq("a request for money is not a payment", null, pay("Google Pay", "Google Pay", "Ram Kumar has requested ₹500 from you"))
    T.eq("a failed payment is not one", null, pay("PhonePe", "PhonePe", "Payment of ₹300 to Zomato failed"))
    T.eq("an OTP is not one", null, pay("Messages", "AX-HDFCBK", "123456 is your OTP for txn of INR 250.00 at SWIGGY. Do not share."))
    T.eq("a bill due is not one", null, pay("Messages", "VK-TNEBLT", "Your EB bill of Rs.1240 is due on 18/11/2026."))
    T.eq("a cashback offer is not one", null, pay("Paytm", "Paytm", "Get cashback up to ₹100 on your next recharge. Offer ends today"))
    T.eq("a balance message is not one", null, pay("Messages", "AX-HDFCBK", "Avl bal in A/c XX1234 is Rs 12,450.50 as on 16-Nov"))
    T.eq("a friend's chat is not a payment", null, pay("WhatsApp", "Ram Kumar", "I paid you 500 yesterday da"))
    T.eq("an upcoming auto-debit is not one yet", null, pay("Messages", "AD-NETFLX", "Rs 199 will be debited from your account on 20 Nov for Netflix"))

    Ledger.init(dir)
    T.check("a payment is added", Ledger.add(g1!!))
    T.check("the bank's SMS about the same payment is not counted twice", !Ledger.add(pay("Messages", "AX-HDFCBK", "Rs 500.00 credited to HDFC Bank A/c xx1234 on 16-11-26 from VPA ramkumar@okaxis (UPI 631234567891)", now + 20_000)!!))
    T.check("the same reference again is not counted twice", Ledger.add(sbi!!) && !Ledger.add(pay("Messages", "VM-SBIUPI", "A/C X1234 debited by 250.0 trf to SWIGGY Refno 631234567890", now + hour)!!))
    T.check("two tea payments of the same amount a minute apart are two", Ledger.add(pay("Paytm", "Paytm", "Received ₹10 from Kumar", now + 2 * min)!!) && Ledger.add(pay("Paytm", "Paytm", "Received ₹10 from Selvi", now + 3 * min)!!))
    Ledger.add(p1!!); Ledger.add(icici!!); Ledger.add(pay("PhonePe", "PhonePe", "Paid ₹700 to HP Petrol Bunk Ukkadam", now + 5 * min)!!)
    Ledger.add(pay("Google Pay", "Google Pay", "Arun paid you ₹300.00", now - day)!!)
    val later = now + 6 * hour
    val got = Ledger.summary("today", "in", later)
    T.check("today's collection adds up", got.contains("₹1,770") && got.contains("4 payments"))
    T.check("…and names who paid most", got.contains("Priya S ₹1,250"))
    val spent = Ledger.summary("today", "out", later)
    T.check("today's spending adds up, by kind", spent.contains("₹2,449") && spent.contains("shopping ₹1,499") && spent.contains("travel ₹700") && spent.contains("food ₹250"))
    T.check("yesterday is separate", Ledger.summary("yesterday", "in", later).contains("₹300"))
    T.check("the week includes both", Ledger.summary("week", "in", later).contains("₹2,070"))
    T.check("an empty day says so", Ledger.summary("today", "both", now + 5 * day).contains("nothing seen"))

    println("Orders are tracked")
    fun parcel(app: String, title: String, text: String, at: Long = now) = ParcelParser.parse(app, title, text, at)
    val a1 = parcel("Amazon", "Shipped", "Your package with boAt Rockerz 255 has been shipped. Arriving Wednesday.")
    T.eq("Amazon shipped", "shipped", a1?.status); T.eq("…the item", "boAt Rockerz 255", a1?.item)
    val a2 = parcel("Amazon", "Out for delivery", "Your package with boAt Rockerz 255 is out for delivery", now + day)
    T.eq("Amazon out for delivery", "out", a2?.status); T.eq("…today", "today", a2?.eta)
    val a3 = parcel("Amazon", "Delivered", "Your package with boAt Rockerz 255 has been delivered.", now + day + 4 * hour)
    T.eq("Amazon delivered", "delivered", a3?.status)
    val f1 = parcel("Flipkart", "Flipkart", "Your order for Puma Men Running Shoes has been shipped and will be delivered by Fri, 20 Nov.")
    T.eq("Flipkart shipped", "shipped", f1?.status); T.eq("…the item", "Puma Men Running Shoes", f1?.item)
    T.eq("a courier SMS", "out", parcel("Messages", "AD-EKARTL", "Your Flipkart order is out for delivery today. Delivery executive Kumar 9876500030.")?.status)
    T.eq("Swiggy on the way", "out", parcel("Swiggy", "Swiggy", "Your order from A2B is on the way to you. Arriving in 12 mins")?.status)
    T.eq("Meesho placed", "ordered", parcel("Meesho", "Meesho", "Order placed! Thank you for shopping. Order ID 1234567890123 for Cotton Kurti")?.status)
    T.eq("a sale is not an order", null, parcel("Amazon", "Great Indian Sale", "Up to 70% off on electronics. Shop now"))
    T.eq("a rating request is not an order", null, parcel("Flipkart", "Flipkart", "How was your Puma Men Running Shoes? Rate your experience"))
    T.eq("a delivery OTP is not kept", null, parcel("Messages", "AD-AMAZON", "Share OTP 482913 with the delivery agent to receive your package"))
    T.eq("a chat about delivery is not an order", null, parcel("WhatsApp", "Ram Kumar", "bro the food was delivered late yesterday"))
    T.eq("a friend's order is not the user's", null, parcel("WhatsApp", "Ram Kumar", "da my Amazon order has been delivered, the shoes are super"))
    T.eq("…nor in an SMS from a saved contact", null, parcel("Messages", "Amma", "Flipkart parcel has been delivered to the neighbour, collect it"))
    T.eq("the shop's own WhatsApp account counts", "shipped", parcel("WhatsApp", "Flipkart", "Your order for Noise Smartwatch has been shipped. It will reach you by Thu, 19 Nov.")?.status)
    Parcels.init(dir)
    T.check("a new order is tracked", Parcels.upsert(a1!!))
    T.check("its next status replaces the old one", Parcels.upsert(a2!!) && Parcels.open(now + day).single().status == "out")
    T.check("an older message arriving late does not move it back", !Parcels.upsert(Parcel(a1.key, "Amazon", "boAt Rockerz 255", "shipped", "", now + day + hour)))
    Parcels.upsert(f1!!)
    T.check("the answer lists them, nearest first", Parcels.summary(now + day).let { it.indexOf("out for delivery") in 1 until it.indexOf("Puma") })
    Parcels.upsert(a3!!)
    T.check("delivered ones stay for two days", Parcels.open(now + 2 * day).any { it.status == "delivered" } && Parcels.open(now + 5 * day).none { it.status == "delivered" })
    T.check("nothing on the way says so", Parcels.summary(now + 60 * day).startsWith("No orders"))

    println("Messages can be found later")
    Past.init(dir)
    Past.add("WhatsApp", "Ram Kumar", "My new address: 14, Gandhi Street, RS Puram, Coimbatore 641002", false, now - 3 * day)
    Past.add("WhatsApp", "CSE Final Year: Prof. Lakshmi", "📄 DC_Unit3_Notes.pdf", true, now - 6 * day)
    Past.add("WhatsApp", "CSE Final Year: Placement Cell", "TCS drive registration link https://forms.gle/AbCd123 fill before Friday", true, now - 2 * day)
    Past.add("Messages", "Amma", "Bring curd and milk when you come", false, now - hour)
    Past.add("WhatsApp", "Priya S", "The plumber's number is 98765 00040, call him after 5", false, now - day)
    Past.add("Messages", "AX-HDFCBK", "123456 is your OTP. Do not share.", false, now)
    T.eq("codes are never kept", 5, Past.size())
    T.check("the address Ram sent", Past.search("the address Ram sent me", now).firstOrNull()?.text?.contains("Gandhi Street") == true)
    T.check("the PDF the professor sent", Past.search("pdf my professor sent last week", now, from = "Lakshmi").firstOrNull()?.text?.contains("DC_Unit3") == true)
    T.check("the TCS form link", Past.search("TCS registration form link", now).firstOrNull()?.text?.contains("forms.gle") == true)
    T.check("the plumber's number", Past.search("plumber number", now).firstOrNull()?.text?.contains("98765 00040") == true)
    T.check("what Amma asked to bring", Past.search("what did amma ask me to bring", now, from = "amma").firstOrNull()?.text?.contains("curd") == true)
    T.eq("something never said is not found", 0, Past.search("passport renewal appointment", now).size)
    T.eq("only the last two days", 0, Past.search("address Ram", now, days = 2).size)
    Past.init(dir)
    T.eq("it survives a restart", 5, Past.size())

    println("One feed for every message")
    Inbox.init(dir); Ledger.init(java.io.File(dir, "f").apply { mkdirs() }); Parcels.init(java.io.File(dir, "f")); Past.init(java.io.File(dir, "f"))
    val s1 = Feeds.take("Messages", "9876500011", "Dear customer your electricity will be disconnected tonight 9.30pm because bill not updated. Pay Rs 10 and call officer 8240471159 immediately", false, now)
    T.check("a scam is flagged", s1.scam != null)
    T.check("…and its 'bill' and 'payment' are not believed", s1.caught == null && s1.txn == null)
    val s2 = Feeds.take("Google Pay", "Google Pay", "Ram Kumar paid you ₹500.00", false, now)
    T.check("a payment is added up", s2.txn?.amount == 500 && s2.scam == null)
    val s3 = Feeds.take("WhatsApp", "CSE Final Year: Placement Cell", "Infosys drive: register before 18/11 5 PM https://forms.gle/Zz99", true, now)
    T.check("a deadline is caught", s3.caught?.kind == "deadline")
    T.check("everything can be switched off", Feeds.take("Google Pay", "Google Pay", "Arun paid you ₹900.00", false, now, Feeds.Allow(catch = false, scam = false, history = false)).let { it.txn == null && it.scam == null })
    T.check("a news headline is not a message to judge", Feeds.take("Dinamalar", "Dinamalar", "CBI warns of digital arrest scam: callers claim your Aadhaar was used in money laundering", false, now, message = false).scam == null)
    T.check("a trusted sender's message is not flagged", Feeds.take("Messages", "9876500011", "Dear customer your electricity will be disconnected tonight. Call officer 8240471159 immediately", false, now, Feeds.Allow(trustedSenders = "9876500011")).scam == null)

    println("The plan coach")
    Coach.init(dir)
    T.eq("a workout plan request", "workout", Coach.wants("Make me a workout plan to lose weight"))
    T.eq("a study timetable", "study", Coach.wants("I need a study timetable for my DC exam"))
    T.eq("a diet plan", "diet", Coach.wants("Create a diet plan for muscle gain"))
    T.eq("in Tanglish", "workout", Coach.wants("enakku oru gym workout plan venum"))
    T.eq("a trip plan is not coaching", null, Coach.wants("Make a plan for my Ooty trip"))
    T.eq("asking about a plan is not making one", null, Coach.wants("how is my workout plan going"))
    T.eq("a recharge plan is not coaching", null, Coach.wants("what is the best Jio recharge plan"))
    T.eq("a PPT about a schedule is a PPT", null, Coach.wants("Make a PPT about my exam schedule"))
    T.eq("a reminder about a plan is a reminder", null, Coach.wants("Remind me to follow my diet plan at 8"))
    T.eq("sending a timetable is sending", null, Coach.wants("Send my study timetable to Ram"))
    T.eq("a fee schedule is not a plan to coach", null, Coach.wants("I need the gym fee schedule"))
    T.eq("a habit to build", "habit", Coach.wants("Help me build a sleep routine"))
    T.eq("coding practice", "study", Coach.wants("Create a DSA practice plan for placements"))
    T.check("the workout questions cover equipment and injuries", Coach.details("workout").let { it.contains("equipment") && it.contains("injury") })
    T.check("the built-in question is numbered", Coach.fallbackQuestion("workout").lines().count { it.matches(Regex("^\\d\\).*")) } >= 6)
    val sessions = listOf(PlanSession("Day A: Push", listOf("Push-ups 3 x 8", "Shoulder taps 3 x 10", "Plank 3 x 30 s", "Dips on chair 3 x 8"), 20), PlanSession("Day B: Legs", listOf("Squats 3 x 12", "Lunges 3 x 10", "Calf raises 3 x 15"), 20))
    val plan = CoachPlan("p1", "Home workout", "workout", "lose fat", sessions, setOf(1, 2, 3, 4, 5), "18:00", now - hour)
    Coach.add(plan)
    val six = nowDt.withHour(18).atZone(zone).toInstant().toEpochMilli()
    T.eq("the first reminder is today at the chosen time", six, Coach.nextNudge(now))
    T.eq("nothing is due before the time", 0, Coach.dueNudges(now).size)
    T.eq("it is due at the time", 1, Coach.dueNudges(six + min).size)
    T.check("the reminder shows today's session", Coach.nudgeText(plan).let { it.first.contains("Day A: Push (20 min)") && it.second.contains("Push-ups 3 x 8") })
    Coach.nudged(plan, six + min)
    T.eq("one reminder a day", 0, Coach.dueNudges(six + 2 * min).size)
    T.check("Later reminds again in an hour", Coach.mark(plan, "later", six + 5 * min).contains("hour") && Coach.dueNudges(six + 66 * min).size == 1)
    T.check("Done moves on to the next session", Coach.mark(plan, "done", six + 70 * min).contains("Next: Day B: Legs"))
    T.eq("streak is 1", 1, Coach.streak(plan, nowDt.toLocalDate()))
    T.eq("the next reminder is tomorrow", six + day, Coach.nextNudge(six + 80 * min))
    // Tuesday and Wednesday: skipped.
    Coach.nudged(plan, six + day); T.check("a skip is taken kindly", Coach.mark(plan, "skip", six + day + min).contains("5 minutes"))
    T.check("after a skip the ask is smaller", Coach.nudgeText(plan).let { it.first.contains("just 5 minutes") && it.second.contains("Squats 3 x 12") && !it.second.contains("Lunges") })
    T.eq("a skip does not skip the session", "Day B: Legs", plan.next().title)
    Coach.nudged(plan, six + 2 * day); Coach.mark(plan, "skip", six + 2 * day + min)
    // Thursday: nudged, no answer at all. Friday's check counts it as missed.
    Coach.nudged(plan, six + 3 * day)
    T.eq("a day with no answer counts as a miss", 1, Coach.dueNudges(six + 4 * day + min).size)
    T.eq("…three in a row now", 3, plan.skipsInRow)
    T.check("then it offers to lighten or pause, without guilt", Coach.nudgeText(plan).let { it.first.contains("slipped") && it.second.contains("easier") && it.second.contains("No guilt") })
    T.check("easier halves the session", Coach.mark(plan, "easier", six + 4 * day + 2 * min).contains("lighter") && plan.todaysItems().size == 2 && plan.todaysMinutes() == 10)
    Coach.mark(plan, "done", six + 4 * day + 30 * min)
    T.check("the status line says where things stand", Coach.status(plan, six + 4 * day + hour).let { it.contains("done today") && it.contains("streak 1") && it.contains("2 of 5 this week") && it.contains("lighter") })
    T.check("pausing stops reminders", Coach.mark(plan, "pause", six + 5 * day).contains("Paused") && Coach.nextNudge(six + 5 * day) == null)
    Coach.mark(plan, "resume", six + 5 * day)
    T.check("weekends are skipped for a weekday plan", java.time.Instant.ofEpochMilli(Coach.nextNudge(six + 5 * day)!!).atZone(zone).dayOfWeek == java.time.DayOfWeek.MONDAY)
    T.check("a number can be tracked", Coach.measure(plan, 72.5, now).contains("72.5") && Coach.measure(plan, 71.0, now + 7 * day).contains("change -1.5"))
    T.eq("mon wed fri", setOf(1, 3, 5), Coach.parseDays(listOf("Mon", "wed", "FRI")))
    T.eq("weekdays", setOf(1, 2, 3, 4, 5), Coach.parseDays(listOf("weekdays")))
    T.eq("daily", (1..7).toSet(), Coach.parseDays(listOf("daily")))
    Coach.init(dir)
    T.check("plans survive a restart", Coach.all().single().let { it.title == "Home workout" && it.doneCount == 2 && it.measures.size == 2 })
    T.check("the summary lists today's plan", Coach.today(six + 7 * day - 2 * hour).single().contains("Home workout at 18:00"))

    println("What you usually choose")
    Usual.init(dir)
    T.eq("nothing known at first", "", Usual.text("MOVIE"))
    Usual.noteOrder(Order(kind = "MOVIE", item = "Ramayana", place = "Sangeetha Somanur", date = "2026-11-13", count = 2, maxEach = 250, app = "BookMyShow"))
    Usual.noteOrder(Order(kind = "MOVIE", item = "Leo", place = "Sangeetha Somanur", date = "2026-11-20", count = 2, maxEach = 250))
    T.check("the usual theatre, count and limit are remembered", Usual.text("MOVIE").let { it.contains("Sangeetha Somanur (2 times)") && it.contains("2 tickets") && it.contains("₹250") })
    T.check("'same as last time' is understood", Usual.asksForSame("Book tickets for Kanguva, same as last time"))
    T.check("'the usual' in Tanglish too", Usual.asksForSame("pona thadava maari book pannu"))
    T.check("a plain request is not that", !Usual.asksForSame("Book 3 tickets for Kanguva at PVR"))
    val filled = Usual.fill(Order(kind = "MOVIE", item = "Kanguva", date = "2026-11-27"))
    T.check("what was left open is filled in", filled.place == "Sangeetha Somanur" && filled.count == 2 && filled.maxEach == 250)
    T.check("…but the film and the day are this request's own", filled.item == "Kanguva" && filled.date == "2026-11-27")
    T.eq("what is said now wins", 4, Usual.fill(Order(kind = "MOVIE", count = 4)).count)
    T.eq("another kind is separate", "", Usual.text("FOOD"))
    Usual.setAlias("Ram", "Ram Kumar", "+919876500002")
    T.eq("which Ram is remembered", "Ram Kumar", Usual.alias("ram")?.first)
    Usual.init(dir)
    T.eq("it survives a restart", 2, Usual.lines().size)
    Usual.forget("alias:ram")
    T.eq("a line can be removed", null, Usual.alias("ram"))

    println("The credit book")
    Khata.init(dir)
    T.eq("Ravi takes 500 on credit", 500, Khata.add("Ravi", 500, true, "groceries", now - 12 * day))
    T.eq("…and 200 more", 700, Khata.add("ravi", 200, true, "", now - 5 * day))
    T.eq("Ravi pays back 300", 400, Khata.add("RAVI", 300, false, "", now - day))
    Khata.add("Selvi akka", 1200, true, "", now - 20 * day)
    T.check("who owes, biggest first", Khata.summary(now).let { it.contains("₹1600 from 2 people") && it.indexOf("Selvi akka: ₹1200") < it.indexOf("Ravi: ₹400") })
    T.check("a polite reminder", Khata.reminder("ravi", "Naveen Stores").let { it.contains("Hi Ravi") && it.contains("₹400") && it.contains("Naveen Stores") })
    Khata.add("Ravi", 400, false, "", now)
    T.check("settled accounts drop off", !Khata.summary(now).contains("Ravi"))
    T.eq("an equal split adds up exactly", listOf("me" to 334, "Ram" to 333, "Priya" to 333), Split.shares(1000, listOf("me", "Ram", "Priya")))
    T.eq("own items plus an equal share of the rest", listOf("me" to 370, "Ram" to 270, "Priya" to 420), Split.shares(1060, listOf("me", "Ram", "Priya"), listOf(300, 200, 350)))
    T.eq("one person is not a split", null, Split.shares(500, listOf("me")))
    T.eq("own items above the total make no sense", null, Split.shares(500, listOf("me", "Ram"), listOf(400, 300)))

    println("Habits worth offering to automate")
    Habits.init(dir)
    fun at(daysAgo: Int, h: Int, m: Int) = nowDt.minusDays(daysAgo.toLong()).withHour(h).withMinute(m).atZone(zone).toInstant().toEpochMilli()
    listOf(1 to 5, 2 to 55, 3 to 10, 4 to 0, 6 to 20).forEach { (d, m) -> Habits.log("dnd:on", at(d, if (m >= 55) 22 else 23, m)) }
    val o1 = Habits.offer(now, emptySet(), emptySet())
    T.check("DND around 11 most nights is noticed", o1?.text?.contains("turn on Do Not Disturb around 11:") == true)
    T.check("…with a skill ready to create", o1?.skill?.optJSONArray("steps")?.optJSONObject(0)?.optString("name") == "set_setting" && o1.skill.optString("time").startsWith("23:"))
    T.eq("a 'no' is final", null, Habits.offer(now, setOf("dnd:on"), emptySet()))
    T.eq("nothing is offered twice", null, Habits.offer(now, emptySet(), setOf(o1!!.skill.optString("name"))))
    Habits.clear()
    listOf(1, 2).forEach { Habits.log("dnd:on", at(it, 23, 0)) }
    T.eq("twice is not a habit yet", null, Habits.offer(now, emptySet(), emptySet()))
    Habits.clear()
    listOf(1 to 9, 2 to 14, 3 to 21, 4 to 7).forEach { (d, h) -> Habits.log("dnd:on", at(d, h, 0)) }
    T.eq("at all hours is not a habit", null, Habits.offer(now, emptySet(), emptySet()))
    Habits.clear()
    listOf(1, 2, 4).forEach { Habits.log("cmd:give me todays top ai news in 5 points", at(it, 8, 10 + it)) }
    T.check("the same question every morning is noticed", Habits.offer(now, emptySet(), emptySet())?.text?.contains("around 8:1") == true)
    Habits.clear()
    listOf(1, 3, 5).forEach { Habits.log("headset>Spotify", at(it, 7 + it, 0)) }
    T.check("headphones then Spotify is noticed", Habits.offer(now, emptySet(), emptySet())?.skill?.optString("event") == "headphones_in")
    Habits.clear(); Habits.own(now)
    Habits.log("dnd:on", now + 1000)
    Habits.log("dnd:on", now + 60_000); Habits.log("dnd:on", now + 2 * min)
    T.check("what Seyal changed itself is not the user's habit, and repeats are one", Habits.offer(now + 20 * day, emptySet(), emptySet(), minDays = 1) == null)
    T.eq("midnight is handled", 0, Habits.typicalMinute(listOf(23 * 60 + 50, 0, 10)).first)
    T.check("news every day is worth noting", Habits.worthLogging("Give me todays top AI news in 5 points"))
    T.check("a one-off action is not", !Habits.worthLogging("Send the weather to dad"))

    println("Files attached in the chat")
    T.eq("a photo", "image", Attach.kind("image/jpeg", "IMG_2041.jpg"))
    T.eq("a PDF", "pdf", Attach.kind("application/pdf", "DC_Unit3_Notes.pdf"))
    T.eq("a video", "video", Attach.kind("video/mp4", "lecture.mp4"))
    T.eq("a voice note", "audio", Attach.kind("audio/ogg", "PTT-2026.opus"))
    T.eq("a Word file", "word", Attach.kind("application/octet-stream", "Resume.docx"))
    T.eq("a text file by its name", "text", Attach.kind("", "notes.txt"))
    T.eq("an unknown file", "other", Attach.kind("application/zip", "backup.zip"))
    T.eq("a small PDF is fine", null, Attach.problem("pdf", 2_000_000, "gemini"))
    T.check("a huge video is refused, with the limit", Attach.problem("video", 40_000_000, "gemini")?.contains("9.0 MB") == true)
    T.check("a video needs Gemini", Attach.problem("video", 1_000_000, "anthropic")?.contains("Gemini") == true)
    T.eq("Claude reads PDFs", null, Attach.problem("pdf", 1_000_000, "anthropic"))
    T.check("an unknown file is refused kindly", Attach.problem("other", 10, "gemini")?.contains("can't be read") == true)
    val docx = Office.docx("Leave letter", "", listOf(Office.Block("Reason", listOf("Fever since Monday", "Doctor advised rest"), "I request two days of leave.")))
    T.check("a Word file is read back as its words", Attach.officeText(docx, slides = false).let { it.contains("Leave letter") && it.contains("Fever since Monday") && it.contains("two days of leave") })
    val pptx = Office.pptx("Solar power", "For homes", "", listOf(Office.Block("Benefits", listOf("Lower bills", "Clean energy"), "")))
    T.check("a PowerPoint is read slide by slide", Attach.officeText(pptx, slides = true).let { it.contains("Slide 1:") && it.contains("Solar power") && it.contains("Lower bills") })

    println("New everyday commands")
    fun plan(text: String) = Basics.plan(text, nowDt, true)
    T.eq("today's sales", "in", plan("today's sales")?.args?.optString("direction"))
    T.eq("how much did I get today", "money_summary", plan("How much did I receive today?")?.tool)
    T.eq("inniku evlo vasool", "today", plan("inniku evlo vasool")?.args?.optString("range"))
    T.eq("spending this month", "month", plan("how much did I spend this month")?.args?.optString("range"))
    T.eq("…is money out", "out", plan("my expenses this month")?.args?.optString("direction"))
    T.eq("where are my orders", "orders_list", plan("Where are my orders?")?.tool)
    T.eq("track my parcel", "orders_list", plan("track my parcel")?.tool)
    T.eq("my plans", "plan_list", plan("my plans")?.tool)
    T.eq("what's my workout today", "plan_list", plan("what's my workout today")?.tool)
    T.eq("workout done", "done", plan("workout done")?.args?.optString("state"))
    T.eq("I finished my workout", "plan_mark", plan("I finished my workout")?.tool)
    T.eq("skip today's workout", "skip", plan("skip today's workout")?.args?.optString("state"))
    T.eq("who owes me", "list", plan("who owes me money")?.args?.optString("action"))
    val owe = plan("Kumar owes me 350 for tea")
    T.check("Kumar owes me 350", owe?.args?.optString("action") == "gave" && owe.args.optInt("amount") == 350 && owe.args.optString("name") == "Kumar" && owe.args.optString("note") == "tea")
    T.eq("'paid' with no account in the book is not the credit book's business", null, plan("Suresh paid 200"))
    Khata.add("Suresh", 500, true, "", now)
    T.eq("…but it is when they have one", "got", plan("Suresh paid 200")?.args?.optString("action"))
    T.eq("read my messages", "notifications_read", plan("read my messages")?.tool)
    T.eq("any new messages", "notifications_read", plan("any new messages?")?.tool)
    T.eq("மெசேஜ் படி", "notifications_read", plan("மெசேஜ் படி")?.tool)
    T.check("நேரம் என்ன is answered in Tamil", plan("நேரம் என்ன")?.say?.contains("மணி 10:00") == true)
    T.check("பேட்டரி எவ்வளவு is answered in Tamil", plan("பேட்டரி எவ்வளவு")?.answer?.invoke(JSONObject().put("battery_percent", 64))?.contains("64 சதவீதம்") == true)
    val msgs = JSONArray().put(JSONObject().put("app", "WhatsApp").put("title", "Amma").put("text", "Come home early."))
        .put(JSONObject().put("app", "Messages").put("title", "Ram Kumar").put("text", "Where are you?")).put(JSONObject().put("app", "YouTube").put("title", "New video").put("text", "Watch now"))
        .put(JSONObject().put("app", "WhatsApp").put("title", "WhatsApp").put("text", "Checking for new messages").put("ongoing", true))
    T.eq("messages are read out, people only", "2 new messages. Amma: Come home early. Ram Kumar: Where are you?.", Basics.readOut(msgs, tamil = false))
    T.check("…in Tamil when asked in Tamil", Basics.readOut(msgs, tamil = true).startsWith("2 புதிய மெசேஜ்."))
    T.eq("no messages", "You have no new messages.", Basics.readOut(JSONArray(), tamil = false))
    T.eq("'send good morning to dad' is sent as it is", "Good morning", plan("send good morning to dad on whatsapp")?.args?.optString("text"))
    for (x in listOf("Send today's sales to Appa on WhatsApp", "Send the weather to dad", "Send my plan to Ram", "send the summary to amma", "Send yesterday's collection to Appa", "Send the bus timetable to Priya", "send the match score to ram"))
        T.eq("something to look up first is not sent as words: $x", null, plan(x))
    for (x in listOf("Make me a workout plan to lose weight", "Find the address Ram sent me", "Split this bill among 3 of us", "Wish everyone in my family happy Diwali", "Make these photos into a PDF", "book the same as last time"))
        T.eq("not basic: $x", null, plan(x))

    println("Family check-in")
    val start = nowDt.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    val eleven = nowDt.withHour(11).atZone(zone).toInstant().toEpochMilli()
    val today = nowDt.toLocalDate()
    T.check("phone untouched since yesterday at 11: a text goes", Guardian.due(eleven + min, eleven, start - 3 * hour, "", today, start))
    T.check("unlocked this morning: nothing", !Guardian.due(eleven + min, eleven, start + 7 * hour, "", today, start))
    T.check("before the time: nothing", !Guardian.due(eleven - min, eleven, start - 3 * hour, "", today, start))
    T.check("one text a day", !Guardian.due(eleven + hour, eleven, start - 3 * hour, today.toString(), today, start))
    dir.deleteRecursively()
}

fun testReliability() {
    val nowDt = java.time.LocalDateTime.of(2026, 11, 16, 10, 0)
    println("Links open inside the app that owns them")
    T.eq("a Swiggy link", "Swiggy", Links.owner("https://www.swiggy.com/search?query=biryani")?.name)
    T.eq("a Zomato short link", "Zomato", Links.owner("https://zoma.to/r/12345")?.name)
    T.eq("a BookMyShow link", "BookMyShow", Links.owner("https://in.bookmyshow.com/explore/movies-coimbatore")?.name)
    T.eq("a redBus link", "redBus", Links.owner("https://www.redbus.in/bus-tickets/coimbatore-to-bangalore")?.name)
    T.eq("Amazon is left to Android", null, Links.owner("https://www.amazon.in/s?k=earphones"))
    T.eq("Flipkart is left to Android", null, Links.owner("https://www.flipkart.com/search?q=shoes"))
    T.eq("a look-alike site is not Swiggy", null, Links.owner("https://swiggy.com.offers-win.top/claim"))
    T.eq("…nor one with the name in the path", null, Links.owner("https://example.com/www.swiggy.com/search"))
    T.eq("a map link has no app to force", null, Links.owner("geo:0,0?q=Gandhipuram"))
    T.eq("the bus list for a route and day", "https://www.redbus.in/bus-tickets/coimbatore-to-bangalore?fromCityName=Coimbatore&toCityName=Bangalore&onward=20-Nov-2026", Links.bus("Coimbatore", "Bangalore", java.time.LocalDate.of(2026, 11, 20)))
    T.eq("two-word cities", "https://www.redbus.in/bus-tickets/new-delhi-to-navi-mumbai?fromCityName=New+Delhi&toCityName=Navi+Mumbai&onward=05-Dec-2026", Links.bus("New Delhi", "Navi Mumbai", java.time.LocalDate.of(2026, 12, 5)))
    T.eq("a dish on Swiggy", "https://www.swiggy.com/search?query=chicken+biryani", Links.food("Swiggy", "chicken biryani"))
    T.eq("an app with no known search link", null, Links.food("Dominos", "pizza"))
    T.eq("films in a city", "https://in.bookmyshow.com/explore/movies-coimbatore", Links.movies("Coimbatore"))

    println("Phones that close apps")
    T.eq("Redmi", "xiaomi", KeepAlive.family("Xiaomi", "Redmi"))
    T.eq("POCO", "xiaomi", KeepAlive.family("Xiaomi", "POCO"))
    T.eq("Realme", "oppo", KeepAlive.family("realme", "realme"))
    T.eq("OnePlus", "oneplus", KeepAlive.family("OnePlus", "OnePlus"))
    T.eq("iQOO", "vivo", KeepAlive.family("vivo", "iQOO"))
    T.eq("Samsung", "samsung", KeepAlive.family("samsung", "samsung"))
    T.eq("a Pixel", "other", KeepAlive.family("Google", "google"))
    T.eq("a Motorola", "other", KeepAlive.family("motorola", "motorola"))
    for (f in listOf("xiaomi", "oppo", "oneplus", "vivo", "samsung", "huawei", "other")) {
        T.check("$f has steps, each one thing to do", KeepAlive.steps(f).let { st -> st.isNotEmpty() && st.size <= 3 && st.all { it.length in 30..190 } })
        T.check("$f: a brand name to show", KeepAlive.brandName(f).isNotBlank())
    }
    T.check("Xiaomi says Autostart and the lock", KeepAlive.steps("xiaomi").let { it[0].contains("Autostart") && it[2].contains("lock") })
    T.check("Samsung says Never sleeping apps", KeepAlive.steps("samsung").any { it.contains("Never sleeping apps") })
    T.check("known brands are flagged up front", KeepAlive.strict("vivo") && !KeepAlive.strict("other"))
    T.check("each known brand has pages to try", listOf("xiaomi", "oppo", "oneplus", "vivo", "samsung", "huawei").all { KeepAlive.autostartPages(it).isNotEmpty() } && KeepAlive.autostartPages("other").isEmpty())

    println("A task cut off by the phone")
    val t0 = 1_800_000_000_000L
    T.eq("the user's task, busy a minute ago", 1, Agent.interrupted(listOf(Triple("DONE", "user", t0 - 5_000), Triple("FAILED", "user", t0 - 60_000)), t0, true))
    T.eq("nothing was running at the restart", -1, Agent.interrupted(listOf(Triple("FAILED", "user", t0 - 60_000)), t0, false))
    T.eq("an old failure is not this", -1, Agent.interrupted(listOf(Triple("FAILED", "user", t0 - 3 * 3600_000L)), t0, true))
    T.eq("a skill's run is not reported this way", -1, Agent.interrupted(listOf(Triple("FAILED", "skill:abc", t0 - 60_000)), t0, true))

    println("Sending to many people")
    T.check("a message to many is approved every time", Agent.mustAsk("send_to_many"))
    T.check("an ordinary message follows the user's settings", !Agent.mustAsk("send_whatsapp"))
    T.check("the spoken scam warning is in Tamil when asked", Scam.spoken(true).contains("மோசடி") && Scam.spoken(false).contains("scam"))

    println("The problem report")
    val head = Report.header("3.9.0-full", "Xiaomi Redmi Note 12", "14", "gemini-2.5-flash", listOf("screen control" to "yes", "notification access" to "no", "battery unrestricted" to "no"), 2, "Daily free limit reached")
    T.check("it names the app version and phone", head.contains("App: 3.9.0-full") && head.contains("Xiaomi Redmi Note 12 · Android 14"))
    T.check("it lists what is switched on", head.contains("screen control: yes · notification access: no · battery unrestricted: no"))
    T.check("it says the phone closed Seyal", head.contains("closed Seyal in the middle of a task 2 times"))
    T.check("it carries the AI limit note", head.contains("AI: Daily free limit reached"))
    T.check("a clean phone has no such lines", Report.header("3.9.0-full", "Google Pixel 8", "15", "gemini-2.5-flash", emptyList(), 0, null).let { !it.contains("closed Seyal") && !it.contains("AI:") })

    println("Tomorrow's weather without the AI")
    fun plan(text: String, last: String? = null) = Basics.plan(text, nowDt, true, last)
    val w = JSONObject().put("place", "Coimbatore, Tamil Nadu, India").put("days", JSONArray()
        .put(JSONObject().put("date", "2026-11-16").put("max_c", 29.8).put("min_c", 22.1).put("rain_chance", 10).put("sky", "partly cloudy"))
        .put(JSONObject().put("date", "2026-11-17").put("max_c", 30.4).put("min_c", 22.6).put("rain_chance", 29).put("sky", "overcast"))
        .put(JSONObject().put("date", "2026-11-18").put("max_c", 28.0).put("min_c", 21.5).put("rain_chance", 70).put("sky", "rain")))
    T.eq("tomorrow, worded", "Tomorrow in Coimbatore: overcast, 23 to 30°C, 29% chance of rain.", Basics.weatherDay(w, 1))
    T.eq("the day after", "The day after tomorrow in Coimbatore: rain, 22 to 28°C, 70% chance of rain.", Basics.weatherDay(w, 2))
    T.eq("'And tomorrow?' after a weather answer", "weather", plan("And tomorrow?", "Coimbatore")?.tool)
    T.eq("…for the same place", "Coimbatore", plan("And tomorrow?", "Coimbatore")?.args?.optString("place"))
    T.check("…answered from the forecast", plan("and tomorrow", "Coimbatore")?.answer?.invoke(w)?.startsWith("Tomorrow in Coimbatore") == true)
    T.eq("'And tomorrow?' with nothing before it is not weather", null, plan("And tomorrow?"))
    T.eq("weather tomorrow in Chennai", "Chennai", plan("What's the weather tomorrow in Chennai?")?.args?.optString("place")?.let { it.replaceFirstChar { c -> c.uppercase() } })
    T.check("will it rain tomorrow", plan("Will it rain tomorrow?")?.answer?.invoke(w)?.contains("29% chance of rain") == true)
    T.eq("next week's weather is still the AI's", null, plan("weather next week in Ooty"))
}

fun testQuality() {
    println("Every answer is shown in the chat")
    T.check("an answer made without the AI is added as a message", Agent.needsBubble(null, "tool", "Your orders:\n- Amazon: boAt Rockerz 255, out for delivery"))
    T.check("…also when nothing at all was shown (the time)", Agent.needsBubble(null, "user", "It's 10:05 AM."))
    T.check("the AI's own answer is not shown twice", !Agent.needsBubble("Alarm set for 7:15 AM.", "assistant", "Alarm set for 7:15 AM. "))
    T.check("a later no-AI answer in the same chat is still shown", Agent.needsBubble("Coimbatore: 25°C, overcast", "tool", "Tomorrow in Coimbatore: overcast, 23 to 30°C."))
    T.check("nothing to show for an empty answer", !Agent.needsBubble(null, "tool", "  "))
    T.eq("the short line keeps a list's first items, not just its heading", "Your orders: Amazon: boAt Rockerz 255, out for delivery; Flipkart: Puma shoes, shipped", Agent.shortResult("Your orders:\n- Amazon: boAt Rockerz 255, out for delivery\n- Flipkart: Puma shoes, shipped"))
    T.eq("a one-line answer stays as it is", "It's 10:05 AM.", Agent.shortResult("It's 10:05 AM."))
    T.eq("bold marks are dropped", "Test Kumar has been added to your contacts.", Agent.shortResult("**Test Kumar** has been added to your contacts."))
    T.eq("an empty answer", "Done", Agent.shortResult("  \n "))
    T.check("it is never longer than the list has room for", Agent.shortResult("Caught from your messages:\n" + (1..30).joinToString("\n") { "- item number $it with a long description" }).length <= 140)

    println("The AI's stray words are not shown")
    T.eq("'thought' alone is dropped", "", Llm.cleanText("thought"))
    T.eq("'thought' before the answer is dropped", "The alarm is set.", Llm.cleanText("thought\nThe alarm is set."))
    T.eq("a real sentence starting with the word is kept", "Thought you might like this: 3 new jobs.", Llm.cleanText("Thought you might like this: 3 new jobs."))
    T.eq("a normal answer is untouched", "Done. **2 tickets** booked.", Llm.cleanText("Done. **2 tickets** booked."))

    println("A stuck AI call gets a second model")
    val log = java.util.Collections.synchronizedList(ArrayList<String>())
    fun ask(delays: Map<String, Long>, fail: Set<String> = emptySet()): (String) -> String = { m -> log += m; Thread.sleep(delays[m] ?: 0); if (m in fail) throw LlmException("boom $m") else "answer from $m" }
    kotlinx.coroutines.runBlocking {
        log.clear()
        T.eq("a quick answer is used as it is", "lite" to "answer from lite", Llm.hedged("lite", 300, { "flash" }, {}, ask(mapOf("lite" to 20))))
        T.eq("…and no second model is asked", listOf("lite"), log.toList())
        log.clear(); var told = ""
        val t0 = System.currentTimeMillis()
        T.eq("a stuck one is overtaken by the second model", "flash" to "answer from flash", Llm.hedged("lite", 150, { "flash" }, { told = it }, ask(mapOf("lite" to 3000, "flash" to 50))))
        T.check("…without waiting for the stuck one", System.currentTimeMillis() - t0 < 1500)
        T.eq("…and the caller is told which model was added", "flash", told)
        T.eq("with no second model free it waits for the first", "lite" to "answer from lite", Llm.hedged("lite", 100, { null }, {}, ask(mapOf("lite" to 400))))
        T.eq("a slow first that still beats the second wins", "lite" to "answer from lite", Llm.hedged("lite", 100, { "flash" }, {}, ask(mapOf("lite" to 300, "flash" to 2000))))
        T.eq("the second failing leaves the first to answer", "lite" to "answer from lite", Llm.hedged("lite", 100, { "flash" }, {}, ask(mapOf("lite" to 500, "flash" to 20), fail = setOf("flash"))))
        log.clear()
        val quickFail = runCatching { Llm.hedged("lite", 300, { "flash" }, {}, ask(mapOf("lite" to 20), fail = setOf("lite"))) }
        T.check("a quick failure comes straight back, with no second request", quickFail.exceptionOrNull()?.message == "boom lite" && log.toList() == listOf("lite"))
        val bothFail = runCatching { Llm.hedged("lite", 100, { "flash" }, {}, ask(mapOf("lite" to 300, "flash" to 20), fail = setOf("lite", "flash"))) }
        T.check("when both fail the first model's error is reported", bothFail.exceptionOrNull()?.message == "boom lite")
    }

    println("Only the tools a request needs are sent")
    val everyTool = (Router.core + Router.groups.flatMap { it.tools } + "some_new_tool").toList()
    fun shown(text: String, used: List<String> = emptyList(), asked: List<String> = emptyList(), purchase: Boolean = false, skill: Boolean = false) =
        Router.pick(everyTool, Router.groupsFor(text, used, asked, purchase, fromSkill = skill))
    // Real requests from the emulator runs, with the tools each one actually used. None may go missing.
    val real = listOf(
        "Amma ku call pannu" to "call contacts_search",
        "Battery level" to "phone_state",
        "Book 2 tickets for Ramayana at Broadway Cinemas Coimbatore tomorrow evening, maximum 250 per ticket" to "ask_user open_app open_url order_check screen_scroll screen_tap screen_tap_at screen_type screen_wait screenshot web_search",
        "Call Amma" to "call contacts_search",
        "End busy mode" to "busy_mode",
        "Every morning at 8, give me AI news" to "skill_save",
        "Find buses from Coimbatore to Bangalore this Friday night and give me the link to the list" to "web_search",
        "Find the address Ram sent me" to "past_search",
        "Find the video called Seyal Test Clip on my phone and play it" to "file_open files_find screen_tap screen_tap_at screenshot",
        "Get my current location using location, then show a notification with the location name and map link using notify." to "location notify web_search",
        "Get my current location with location, then notification_reply to the sender with the place and map_link" to "contacts_search location notification_reply send_sms send_whatsapp web_search",
        "Give me my summary for today" to "daily_brief",
        "Give me todays top AI news in 5 points" to "news",
        "How much did I spend today?" to "money_summary",
        "How much storage is used on this phone, and which folders and apps take the most space?" to "storage_info",
        "I am in class till 4 pm, tell people who call that I will call back after 4" to "busy_mode",
        "In the Contacts app add a new contact named Test Kumar with the number 9876512345" to "open_app screen_tap screen_tap_at screen_type screenshot",
        "Kumar owes me 350 for tea" to "khata",
        "List the videos on this phone and send the list to priya@example.com as a PPT" to "contacts_search doc_create files_find send_email send_file",
        "Make a PDF with a packing list for a 2 day trip to Ooty" to "doc_create",
        "Make a PowerPoint about the benefits of rooftop solar power for homes, 6 slides || Email it to priya@example.com" to "contacts_search doc_create file_open send_file web_search",
        "Make a meeting link for Priya tomorrow 4 PM, email it to her and remind me before it starts" to "calendar_add contacts_search meeting_link reminder_set send_email",
        "Make me a home workout plan. I am a beginner, 20 minutes a day, Monday to Friday at 6 pm, no equipment, my goal is to lose fat, no injuries" to "ask_user plan_create",
        "My plans" to "plan_list",
        "Navigate to Gandhipuram bus stand" to "open_url",
        "Open Chrome, search for Broadway Cinemas Coimbatore and tell me the title of the first result" to "open_app screen_tap screen_type web_search",
        "Open Settings, find the Android version of this phone and tell me || Save this as a skill called Version check" to "open_app screen_scroll screen_tap skill_save_this",
        "Open Settings, find the Android version of this phone and tell me || The recorded steps above ran. Tell me the result in one or two lines. Call tools only if the goal is clearly not done yet." to "open_app screen_scroll screen_tap",
        "Open the Clock app and add an alarm for 7:15 AM using its screen" to "alarm_set open_app",
        "Order a biryani from a nearby hotel" to "apps_list ask_user location open_app open_url screen_back screen_scroll screen_tap screen_tap_at screen_type screen_wait screenshot wait web_search",
        "Play the oldest video from the YouTube channel @Google" to "open_app open_url screen_read screen_scroll screen_tap screen_type screenshot wait web_search",
        "Remember that my favourite theatre is Broadway Cinemas" to "memory_save",
        "Remind me in 10 minutes to drink water" to "reminder_set",
        "Run my Version check skill" to "skill_run",
        "Send a message to my dad saying I will be late today" to "contacts_search send_sms",
        "Set an alarm for 6:30 am" to "alarm_set",
        "Set the brightness to 30 percent and turn on do not disturb" to "set_setting",
        "Set the brightness to 30 percent and turn on do not disturb." to "set_setting",
        "Split 1200 among me, Ram and Priya" to "ask_user contacts_search send_sms send_whatsapp split_bill",
        "Summarise the book Atomic Habits in 5 lines" to "web_search",
        "Tell Amma that I reached Coimbatore" to "contacts_search send_sms",
        "Tell me when the page https://example.com says Book tickets" to "watch_add",
        "Today's sales" to "money_summary",
        "Tomorrow at 8 am send a WhatsApp to my dad saying good morning" to "contacts_search skill_save",
        "Torch on" to "say set_setting",
        "What are you watching for me?" to "watch_list",
        "What deadlines do I have?" to "inbox_list",
        "What is the weather in Coimbatore today? || And tomorrow?" to "weather",
        "What is the weather tomorrow in Coimbatore?" to "weather",
        "What notifications do I have right now?" to "notifications_read",
        "When the battery goes below 20 percent after 9 pm, set the brightness to 10" to "skill_save",
        "Whenever any app shows a message from Amma asking where are you, tell me my current location" to "contacts_search skill_save",
        "Where are my orders?" to "orders_list",
        "Where is the share log button in this app?" to "say",
        "Who owes me money?" to "khata",
        "Workout done" to "plan_mark",
    )
    for ((said, used) in real) {
        val need = used.split(' ').filter { it.isNotBlank() }
        val got = shown(said.replace(" || ", "\n"), purchase = Preflight.kind(said.substringBefore(" || ")) != null)
        T.eq("has what it used: ${said.take(60)}", emptyList<String>(), need.filter { it !in got })
    }
    val small = shown("Send a message to my dad saying I will be late today")
    T.check("a plain message needs no skill builder, plan coach, watcher or file maker", listOf("skill_save", "plan_create", "watch_add", "doc_create", "khata", "delegate").none { it in small })
    T.check("…but can still call, message, search and work the screen", listOf("send_whatsapp", "contacts_search", "web_search", "open_app", "screen_tap", "ask_user", Router.MORE).all { it in small })
    T.check("a simple request leaves out a large part of the tools", small.size < everyTool.size * 0.6)
    T.check("a tool no group knows is always sent", "some_new_tool" in small)
    T.check("a tool used earlier in the chat stays", "doc_create" in shown("ok", used = listOf("doc_create")))
    T.check("what the AI asks for is loaded", "plan_create" in shown("help", asked = listOf("plans")) && "skill_save" !in shown("help", asked = listOf("plans")))
    T.check("'all' loads everything", shown("help", asked = listOf("all")).size == everyTool.size)
    T.check("a booking always has the order check", "order_check" in shown("Ramayana ku rendu seat", purchase = true))
    T.check("…also when only the words say so", "order_check" in shown("Book 2 tickets for Leo"))
    T.check("a skill run has the notification tools", "notification_reply" in shown("Answer Amma", skill = true))
    for ((said, tool) in listOf(
        "Every day at 9 pm tell me my screen time" to "skill_save", "When I reach home turn on wifi" to "skill_save", "Tomorrow at 8 am send a WhatsApp to my dad" to "skill_save",
        "Make a PPT about solar power" to "doc_create", "Find the video called Seyal Test Clip and play it" to "files_find", "How much storage is used" to "storage_info", "Email it to priya@example.com" to "send_file",
        "Any mail from HR today?" to "read_mail", "Reply to Amma that I am coming" to "notification_reply", "What notifications do I have" to "notifications_read",
        "Add a meeting with Priya on Friday 4 pm" to "calendar_add", "Set a timer for 5 minutes" to "timer_set", "Tell me when bookings open for Leo at Broadway" to "watch_add",
        "I am in class till 4" to "busy_mode", "What deadlines do I have" to "inbox_list", "How much did I spend this month" to "money_summary", "Where are my orders" to "orders_list",
        "Ravi owes me 500" to "khata", "Split the bill among 4" to "split_bill", "Make me a workout plan" to "plan_create", "Find the address Ram sent me" to "past_search",
        "Book the same as last time" to "chats_search", "Wish everyone in my family happy Diwali" to "send_to_many", "Send the invite to Ram, Priya and Kumar" to "send_to_many",
        "Compare these three phones in a detailed report" to "delegate", "Make these photos into a PDF" to "photos_to_pdf", "தினமும் காலை 7 மணிக்கு நியூஸ் சொல்லு" to "skill_save",
        "inniku evlo selavu" to "money_summary", "Amma anupina address enna" to "past_search",
    )) T.check("\"$said\" has $tool", tool in shown(said))
    T.check("a very long request gets everything", shown("x ".repeat(400)).size == everyTool.size)

    println("Asking for more tools")
    T.check("loading says what arrived", Router.loadedNote(listOf("files"), emptySet()).let { it.startsWith("Loaded: files (") && it.contains("doc_create") })
    T.check("already loaded is said plainly", Router.loadedNote(listOf("files"), setOf("files")).startsWith("Already loaded"))
    T.check("an unknown group lists the real ones", Router.loadedNote(listOf("payments"), emptySet()).let { it.startsWith("No group called payments") && it.contains("money") })
    T.eq("groups can come as a list", listOf("files", "mail"), Router.asked(JSONObject().put("groups", JSONArray().put("files").put("mail"))))
    T.eq("…or as one word", listOf("all"), Router.asked(JSONObject().put("group", "all")))
    T.check("the description names what is not loaded", Router.moreAbout(setOf("screen", "files")).let { it.contains("mail =") && !it.contains("files =") })
    T.check("'I can't do that' is a give-up", Router.givesUp("I'm sorry, I can't create PowerPoint files."))
    T.check("'I don't have a tool' is a give-up", Router.givesUp("I don't have a tool to read your mail."))
    T.check("a real answer is not", !Router.givesUp("Alarm set for 6:30 AM tomorrow."))
    T.check("'bookings are not open' is not", !Router.givesUp("Broadway Cinemas has not opened bookings for tomorrow yet."))
    T.check("a question about the app gets the app notes", Router.asksAboutApp("Where is the share log button in this app?") && Router.asksAboutApp("What can you do?"))
    T.check("an ordinary command does not", !Router.asksAboutApp("Call Amma") && !Router.asksAboutApp("Order a biryani"))

    println("Nothing is sent that nobody asked for")
    val nowDt = java.time.LocalDateTime.of(2026, 11, 16, 10, 0)
    T.eq("names to split among", listOf("me", "Ram", "Priya"), Split.people("me, Ram and Priya"))
    T.eq("a head count", listOf("me", "Person 2", "Person 3", "Person 4"), Split.people("4 people"))
    T.eq("'the 3 of us'", 3, Split.people("the 3 of us").size)
    T.eq("one name is not a split", emptyList<String>(), Split.people("Ram"))
    T.eq("a sentence is not a list of names", emptyList<String>(), Split.people("the people who came to dinner yesterday night and those who left early"))
    T.eq("named shares are spelled out, with an offer to send", "₹1,200 among 3: me ₹400, Ram ₹400, Priya ₹400.\nSay \"send it to them\" and I'll message each person their share.", Split.say(1200, Split.shares(1200, listOf("me", "Ram", "Priya"))!!))
    T.eq("a head count gives the amount each", "₹1,200 among 4: ₹300 each.", Split.say(1200, Split.shares(1200, Split.people("4"))!!))
    val sp = Basics.plan("Split 1200 among me, Ram and Priya", nowDt, true)
    T.check("splitting a bill needs no AI", sp?.tool == "split_bill" && sp.args.optInt("total") == 1200 && sp.args.optJSONArray("people")?.length() == 3)
    T.eq("…in other words too", "split_bill", Basics.plan("divide ₹900 between 3 people", nowDt, true)?.tool)
    T.eq("a split with more to do is the AI's", null, Basics.plan("Split the bill in this photo among us", nowDt, true))
    T.check("'split 1200 among us three' asked to nobody", !Agent.asksToContact("Split 1200 among me, Ram and Priya"))
    T.check("…nor is a booking", !Agent.asksToContact("Book 2 tickets for Ramayana tomorrow evening"))
    T.check("…nor a question", !Agent.asksToContact("How much did I spend this month?"))
    for (x in listOf("Send a message to my dad saying I will be late", "Tell Amma that I reached", "Email it to priya@example.com", "Wish Ram happy birthday", "Split 1200 among us and send each their share",
        "Amma ku call pannu", "Appa kitta sollu naan late nu", "Remind them to pay", "அம்மாவுக்கு மெசேஜ் அனுப்பு", "Reply to Amma that I am coming", "Call Amma"))
        T.check("asks to reach someone: $x", Agent.asksToContact(x))

    println("What counts as a deadline")
    val z = java.time.ZoneId.systemDefault()
    val nowMs = nowDt.atZone(z).toInstant().toEpochMilli()
    fun caught(chat: String, text: String, app: String = "WhatsApp") = Catcher.scan(app, chat, text, nowMs)
    fun dayOf(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(z).toLocalDate().toString()
    T.eq("'come home before 8' is not a deadline", null, caught("Family: Amma", "Come home before 8 today, guests are coming"))
    T.eq("'movie by 6 tomorrow?' is not one", null, caught("Ram Kumar", "da reach the theatre by 6 tomorrow ok"))
    T.eq("'submit the record before 4' is", "deadline", caught("CSE Final Year: Class Rep", "Submit the DC record before 4 PM tomorrow")?.kind)
    T.eq("'last date tomorrow' still is", "deadline", caught("CSE Final Year: Ram Kumar", "bro last date for the assignment is tomorrow it seems")?.kind)
    val maint = caught("Apartment Residents: Secretary", "Maintenance of Rs 1500 for November is due by 20th. Pay to the association account.")
    T.eq("'due by 20th' is a bill", "bill", maint?.kind); T.eq("…on the 20th of this month", "2026-11-20", maint?.let { dayOf(it.due) })
    val quiz = caught("Coursera", "Your assignment is due in 2 days: Week 3 Quiz", "Gmail")
    T.eq("'due in 2 days' is caught", "deadline", quiz?.kind); T.eq("…two days from now", "2026-11-18", quiz?.let { dayOf(it.due) })
    T.eq("a day already past this month means next month", "2026-12-05", com.seyal.app.util.Dates.find("Pay the rent by 5th", nowDt)?.at?.toLocalDate()?.toString())
    T.eq("'4th semester' is not a date", null, com.seyal.app.util.Dates.find("on 4th semester students only", nowDt))
    T.eq("a promo with a date stays ignored", null, caught("AD-MYNTRA", "End of Reason Sale ends today midnight! Last chance to grab 50-80% off.", "Messages"))

    println("Searching the web")
    T.eq("quotes and site: are dropped", "Ramayana Coimbatore showtimes", com.seyal.app.util.Parsers.simplerQuery("site:in.bookmyshow.com \"Ramayana\" Coimbatore showtimes"))
    T.eq("years, dates and filler go", "Broadway Cinemas Coimbatore movie November", com.seyal.app.util.Parsers.simplerQuery("Broadway Cinemas Coimbatore movie listings November 17 2026"))
    T.eq("a long question is cut to its first words", 7, com.seyal.app.util.Parsers.simplerQuery("best chicken biryani under 300 near me Coimbatore swiggy zomato online order")?.split(' ')?.size)
    T.eq("plain words have nothing simpler", null, com.seyal.app.util.Parsers.simplerQuery("Atomic Habits summary"))

    println("Guidance follows the tools in view")
    val none: (String) -> Boolean = { false }
    val all: (String) -> Boolean = { true }
    T.check("booking rules are left out when nothing is being bought", !Prompts.keep("- Seats: people who book together sit together.", none))
    T.check("…and kept when something is", Prompts.keep("- Seats: people who book together sit together.", all))
    T.check("general rules always stay", Prompts.keep("- Act, don't narrate.", none) && Prompts.keep("- Safety: never type passwords", none))
    T.check("a question is not a screen task", !Prompts.screenTask("What is the capital of France?") && !Prompts.screenTask("Summarise the book Atomic Habits in 5 lines"))
    T.check("opening or ordering is", Prompts.screenTask("Open Settings and find the Android version") && Prompts.screenTask("Order a biryani from a nearby hotel") && Prompts.screenTask("Play the oldest video from the YouTube channel @Google"))
}

fun testTrust() {
    val min = 60_000L; val hour = 60 * min
    val now = java.time.LocalDateTime.of(2026, 11, 17, 8, 20).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    val eight = now - 20 * min

    println("A scheduled time is never skipped silently")
    T.eq("due in the future: nothing to do", "none", Skills.missedAction(now + hour, 0, now))
    T.eq("due a few seconds ago: the alarm is still on its way", "none", Skills.missedAction(now - 30_000, 0, now))
    T.eq("it ran at its time", "none", Skills.missedAction(eight, eight + 2_000, now))
    T.eq("20 minutes late and never ran: run it now", "run", Skills.missedAction(eight, eight - 24 * hour, now))
    T.eq("3 hours late: tell the user and offer it", "tell", Skills.missedAction(now - 3 * hour, now - 27 * hour, now))
    T.eq("a skill with no time set", "none", Skills.missedAction(0, 0, now))
    T.check("the note names the time and the reason", Skills.missedNote(eight).let { it.startsWith("Missed Tue 17 Nov, 8:00 AM") && it.contains("closed Seyal") })

    println("A late reminder or alarm is delivered, not dropped")
    T.eq("a reminder 5 minutes late fires as normal", "fire", Scheduler.lateAction("remind", 5 * min))
    T.eq("a reminder 2 hours late is told, with its time", "tell", Scheduler.lateAction("remind", 2 * hour))
    T.eq("an alarm 2 hours late is told, not rung", "tell", Scheduler.lateAction("alarm", 2 * hour))
    T.eq("a reminder from two days ago is let go", "drop", Scheduler.lateAction("remind", 50 * hour))
    T.eq("busy mode always ends, however late", "end", Scheduler.lateAction("busy", 9 * hour))
    T.check("the late text says what and when", Scheduler.lateText("remind", "Call the bank", "Tue 17 Nov, 8:00 AM").let { it.startsWith("Missed reminder (was for Tue 17 Nov, 8:00 AM): Call the bank") && it.contains("Keep Seyal running") })

    println("A meeting is saved with proof")
    val start = now + 3 * hour
    T.eq("Seyal's own reminder is 10 minutes before", start - 10 * min, Meetings.ownReminderAt(start, 10, now))
    T.eq("a meeting in 5 minutes is reminded in one minute", now + min, Meetings.ownReminderAt(now + 5 * min, 10, now))
    T.eq("a meeting starting now needs none", null, Meetings.ownReminderAt(now + min, 10, now))
    T.eq("the reminder says how long is left", "Team meeting starts in 10 minutes (11:20 AM)", Meetings.reminderText("Team meeting", start, start - 10 * min))
    val both = Meetings.proof("Team meeting", "today 11:20 AM", saved = true, calendarAlert = true, own = true, before = 10, ownAt = "today 11:10 AM")
    T.check("saved with both alerts says so", both.startsWith("Saved in your calendar: Team meeting, today 11:20 AM") && both.contains("Two alerts"))
    val noAlert = Meetings.proof("Team meeting", "today 11:20 AM", saved = true, calendarAlert = false, own = false, before = 10, ownAt = null)
    T.check("saved but with no alert says NO alert", noAlert.contains("NO alert could be set"))
    val notSaved = Meetings.proof("Team meeting", "today 11:20 AM", saved = false, calendarAlert = false, own = true, before = 10, ownAt = "today 11:10 AM")
    T.check("not in the calendar is said first, and that Seyal will still remind", notSaved.startsWith("NOT saved in your calendar") && notSaved.contains("Seyal will still remind you"))
    val nothing = Meetings.proof("Team meeting", "today 11:20 AM", saved = false, calendarAlert = false, own = false, before = 10, ownAt = null)
    T.check("nothing in place is never called scheduled", nothing.startsWith("NOT saved") && nothing.contains("add it yourself"))
    for (p in listOf(both, noAlert, notSaved, nothing)) T.check("the word 'scheduled' is never used loosely", !p.contains("scheduled", true))

    println("Screen control switched off by the phone")
    val me = "com.seyal.app/com.seyal.app.device.SeyalAccessibilityService"; val short = "com.seyal.app/.device.SeyalAccessibilityService"
    T.check("on and still listed: fine", !ScreenWatch.dropped(true, "com.google.android.marvin.talkback/.TalkBackService:$me", me, short))
    T.check("…also in the short form", !ScreenWatch.dropped(true, short, me, short))
    T.check("on before, gone from the list now: the phone did it", ScreenWatch.dropped(true, "com.google.android.marvin.talkback/.TalkBackService", me, short))
    T.check("…also when the list is empty", ScreenWatch.dropped(true, null, me, short))
    T.check("never switched on: nothing to say", !ScreenWatch.dropped(false, "", me, short))
    T.check("the Settings app in front means the user did it", ScreenWatch.userAtSettings("com.android.settings") && ScreenWatch.userAtSettings("com.miui.securitycenter"))
    T.check("another app in front means the phone did it", !ScreenWatch.userAtSettings("com.whatsapp") && !ScreenWatch.userAtSettings(""))

    println("Permissions are asked when needed")
    T.check("a refusal says what and why", Perms.refused("Contacts", "to find people by name").let { it.startsWith("Contacts is not allowed") && it.contains("to find people by name") && it.contains("Control → Phone permissions") })
    val bms = Lessons.textFor(listOf("BookMyShow"))
    T.check("every built-in booking note reaches the AI, first to last", bms.contains("by its NAME") && bms.contains("date strip") && bms.contains("your_turn") && bms.contains("seats_check") && bms.contains("stop at Pay"))
    T.check("the bus notes hand the seats to the user too", Lessons.textFor(listOf("redBus")).let { it.contains("your_turn") && it.contains("boarding point") })
    T.check("picking seats is a tool every screen task has", "your_turn" in Router.pick(listOf("your_turn", "ask_user"), Router.groupsFor("Book 2 tickets for Leo")))
}

fun testPhoneVideos() {
    val hour = 3600_000L; val now = 1_800_000_000_000L
    println("A phone that refuses screenshots")
    T.check("one or two failures are not enough to stop asking", !ShotHealth.broken(1, now - 1000, now) && !ShotHealth.broken(2, now - 1000, now))
    T.check("three in a row: work from the screen text for a while", ShotHealth.broken(3, now - 1000, now))
    T.check("ten minutes later: try a picture again", !ShotHealth.broken(5, now - 11 * 60_000L, now) && !ShotHealth.broken(5, now - 25 * hour, now))
    T.check("the AI is told it is the phone, not the app", ShotHealth.instead("Android error 1", 40).let { it.contains("NOT the app blocking you") && it.contains("screen_tap") && it.contains("your_turn") })
    T.check("an empty screen text says to wait", ShotHealth.instead("Android error 1", 0).contains("screen_wait"))

    println("Made-up excuses are sent back")
    for (x in listOf(
        "The ticket booking apps and their websites are currently blocking automated access on your device, preventing me from completing the booking process.",
        "The food delivery apps and their websites are currently blocking automated access on your device.",
        "The system is blocking automated browser and app actions for bus bookings.",
        "Ramayana tickets for today cannot be booked automatically because the booking apps are currently blocked on your device.",
        "BookMyShow detects automation and refuses to continue.",
    )) T.check("caught: ${x.take(50)}", Honest.blames(x))
    for (x in listOf(
        "Booked: 2 tickets for Ramayana at Sangeetha Somanur, 6:45 PM. Pay on the screen.",
        "Your cart has 3 chicken biryani (₹747). Tap Proceed to Pay.",
        "Broadway Cinemas has not opened bookings for tomorrow yet.",
        "I couldn't find Sangeetha Somanur in the list: it has no Ramayana show today.",
        "Spam calls are blocked by your phone's settings.",
    )) T.check("an honest answer passes: ${x.take(50)}", !Honest.blames(x))

    println("Piracy sites are never opened")
    for (u in listOf("https://www.1tamilmv.io/index.php?/forums/topic/1234", "https://tamilblasters.cafe/x", "https://isaimini.store/ramayana", "magnet:?xt=urn:btih:abc", "https://1337x.to/torrent/1", "https://ww7.movierulz.com/x"))
        T.check("blocked: $u", Links.isPirate(u))
    for (u in listOf("https://in.bookmyshow.com/explore/movies-coimbatore", "https://www.district.in/movies", "https://www.redbus.in/bus-tickets/coimbatore-to-bangalore", "https://en.wikipedia.org/wiki/Ramayana_(2026_film)", "https://www.imdb.com/title/tt1"))
        T.check("allowed: $u", !Links.isPirate(u))
    val html = """<a class="result__a" href="https://www.1tamilmv.io/index.php?/x">John Doe 2026 Tamil HQ</a><a class="result__snippet" href="#">torrent</a>""" +
        """<a class="result__a" href="https://in.bookmyshow.com/coimbatore/movies/ramayana">Ramayana - BookMyShow</a><a class="result__snippet" href="#">Book tickets</a>"""
    val res = com.seyal.app.util.Parsers.searchResults(html, 5)
    T.check("search results drop the piracy site and keep the real one", res.length() == 1 && res.optJSONObject(0).optString("url").contains("bookmyshow"))

    println("The food apps' own popups")
    val food = Lessons.textFor(listOf("Zomato", "Swiggy"))
    T.check("Zomato: pick the saved address, never Enable", food.contains("Device location not enabled") && food.contains("never tap Enable"))
    T.check("Swiggy: no swiggy.com search links", food.contains("Do NOT open swiggy.com links"))
}

fun testLongChats() {
    println("A chat used all morning stays light")
    fun u(t: String) = Msg("user", listOf(Part.Text(t)))
    fun call(id: String, name: String) = Msg("assistant", listOf(Part.Call(id, name, JSONObject())))
    fun res(id: String, name: String) = Msg("tool", listOf(Part.Result(id, name, JSONObject().put("ok", true).put("screen", "x".repeat(2000)), false)))
    fun say(t: String) = Msg("assistant", listOf(Part.Text(t)))
    val msgs = mutableListOf<Msg>()
    msgs += u("book ramayana ticket"); for (k in 1..30) { msgs += call("a$k", "screen_tap"); msgs += res("a$k", "screen_tap") }; msgs += say("Stopped at Pay ₹780 for 6 tickets.")
    msgs += u("buy me a black shoe that has most softest cushion sole"); for (k in 1..40) { msgs += call("b$k", "screen_tap"); msgs += res("b$k", "screen_tap") }; msgs += say("Campus North Plus, size 11, ₹1,144 is in the cart.")
    val lastAsk = msgs.size; msgs += u("buy me a chicken briyani"); msgs += call("c1", "open_app"); msgs += res("c1", "open_app")
    val lines = (1..40).associate { "b$it" to "Tapped $it" } + (1..30).associate { "a$it" to "Tapped $it" }
    val out = History.collapse(msgs, lastAsk, { it.role == "user" }, lines)
    T.eq("two finished requests become two lines each", 2 + 2 + 3, out.size)
    T.check("…keeping what was asked", out[0].text == "book ramayana ticket" && out[2].text.startsWith("buy me a black shoe"))
    T.check("…and what came of it", out[1].text.contains("Stopped at Pay ₹780") && out[3].text.contains("Campus North Plus"))
    T.check("…saying how many steps it took", out[3].text.contains("40 steps"))
    T.check("the request now running is untouched", out.takeLast(3).map { it.role } == listOf("user", "assistant", "tool"))
    T.check("turns still alternate user, assistant", out.zipWithNext().none { (a, b) -> a.role == b.role && a.role != "tool" })
    val noAnswer = listOf(u("open youtube"), call("x1", "open_app"), res("x1", "open_app"), u("now play songs"))
    T.check("a request with no final words keeps its last steps", History.collapse(noAnswer, 3, { it.role == "user" }, mapOf("x1" to "Opened YouTube"))[1].text.contains("Opened YouTube"))
    T.check("'email it' points back at the last request", History.pointsBack("email it to priya"))
    T.check("'retry the biryani' does too", History.pointsBack("retry the briyani"))
    T.check("'now send the ppt to ram on whatsapp' does too", History.pointsBack("now send the ppt to ram on whatsapp please"))
    T.check("a new request does not", !History.pointsBack("Book a bus ticket from Coimbatore to Bangalore for Friday night"))
    T.check("'a shoe that has…' is a new request, not a pointer", !History.pointsBack("buy me a black shoe that has most softest cushion sol"))
    T.eq("a short earlier request stays whole whatever is said next", 10, History.keepFrom(10, 16, "Book a bus ticket from Coimbatore to Bangalore for Friday night"))
    T.eq("a long one that is not pointed at is collapsed", 90, History.keepFrom(10, 90, "Book a bus ticket from Coimbatore to Bangalore for Friday night"))
    T.eq("a long one that is pointed at stays whole", 10, History.keepFrom(10, 90, "retry the briyani"))
    T.eq("the first request in a chat", 0, History.keepFrom(null, 0, "book ramayana ticket"))
    val ppt = listOf(u("Make a PPT about solar power"), call("d1", "doc_create"),
        Msg("tool", listOf(Part.Result("d1", "doc_create", JSONObject().put("ok", true).put("uri", "content://seyal/solar.pptx").put("name", "Solar power.pptx").put("slides", 6), false))),
        say("Made Solar power.pptx with 6 slides."), u("Book a bus ticket from Coimbatore to Bangalore for Friday night"))
    val kept = History.collapse(ppt, 4, { it.role == "user" }, mapOf("d1" to "Made Solar power.pptx"))[1].text
    T.check("a collapsed request still carries the file it made", kept.contains("uri=content://seyal/solar.pptx") && kept.contains("name=Solar power.pptx"))

    println("Answers show in full on the step card")
    T.check("seat-count rule: tap numbers by their text", Lessons.textFor(listOf("BookMyShow")).contains("by its TEXT"))
}

fun testSecondVideos() {
    val nowDt = java.time.LocalDateTime.of(2026, 11, 17, 10, 1)   // Tuesday, as in the recording

    println("Typing is checked, not assumed")
    T.check("the words are in the box", TypeCheck.took("chicken biryani", "chicken biryani", "", "", false))
    T.check("an empty box after 'typing' is a failure", !TypeCheck.took("", "chicken biryani", "", "", false))
    T.check("a box still showing its hint is a failure", !TypeCheck.took("Search for restaurant", "chicken biryani", "Search for restaurant", "", true))
    T.check("a rotating hint written as text is a failure", !TypeCheck.took("Try 'Pizza'", "chicken biryani", "", "Try 'Sweets'", false))
    T.check("the old text unchanged is a failure", !TypeCheck.took("black shoes", "campus shoes size 11", "", "black shoes", false))
    T.check("a number the app reformatted is fine", TypeCheck.took("98765 00002", "9876500002", "", "", false))
    T.check("capital letters added by the app are fine", TypeCheck.took("Chicken Biryani", "chicken biryani", "", "", false))
    T.check("a long text shown cut short still counts", TypeCheck.took("black shoe size 11 soft cushion sole men and more", "black shoe size 11 soft cushion sole men", "", "", false))
    T.check("a box that can't be read is not called a failure", TypeCheck.took(null, "chicken biryani", "", "", false))
    T.check("when nothing works the user is asked to type it", TypeCheck.refused("chicken biryani").let { it.contains("your_turn") && it.contains("chicken biryani") && it.contains("Do not try again") })

    println("The seat screen is checked before the seats are handed over")
    val order = Order(kind = "MOVIE", item = "Ramayana", date = "2026-11-17", count = 2, maxEach = 150)
    val wrong = "Ramayana\nWed, 18 Nov\n6 Tickets\n10:35 AM\n06:00 PM\n10:00 PM\nPLATINUM\nRs.130 GOLD\nAvailable\nSelected\nSold\nYES Private Debit Card Offer"
    val p = SeatScreen.problem(wrong, order, nowDt)
    T.check("6 tickets when 2 were asked is caught", p?.contains("says 6 tickets, but the user asked for 2") == true)
    T.check("Wednesday's show when today was asked is caught", p?.contains("This show is on Wed 18 Nov, but the user asked for Tue 17 Nov") == true)
    T.check("…and it says never to move the day alone", p?.contains("never move to another day by yourself") == true)
    val right = "Ramayana\nTue, 17 Nov\n2 Tickets\n10:30 AM\n06:00 PM\n10:00 PM\nRs.130 GOLD\nAvailable\nSelected\nSold"
    T.eq("the right day and count pass", null, SeatScreen.problem(right, order, nowDt))
    T.eq("a screen that names no day or count passes", null, SeatScreen.problem("GOLD\nSILVER\nAvailable\nSold", order, nowDt))
    T.eq("'25 Seats Left' on a bus is not a ticket count", null, SeatScreen.problem("NueGo\n25 Seats Left\n9 Single Seats\nLower deck", Order(kind = "TRAVEL", count = 2), nowDt))
    T.eq("an offer's date lower down is not the show's day", null, SeatScreen.problem("Ramayana\nTue, 17 Nov\n2 Tickets\n" + "seat ".repeat(160) + "\nOffer valid till 30 Nov", order, nowDt))
    T.eq("no order written down, nothing to check", null, SeatScreen.problem(wrong, null, nowDt))

    println("Notes from the recordings")
    val bms = Lessons.textFor(listOf("BookMyShow"))
    T.check("BookMyShow: search is at the bottom, not the profile icon", bms.contains("BOTTOM") && bms.contains("profile"))
    T.check("BookMyShow: never change the day", bms.contains("never switch to another day"))
    T.check("BookMyShow: an area is not a theatre name", bms.contains("An area instead of a theatre name"))
    val amz = Lessons.textFor(listOf("Amazon"))
    T.check("Amazon: a filtered list under the budget", amz.contains("amazon.in/s?k=") && amz.contains("p_36"))
    T.check("Amazon: at most three products, size by text, never Alexa", amz.contains("at most three") && amz.contains("by its TEXT") && amz.contains("Alexa"))
    T.check("Amazon notes are found for the app's full name", Lessons.textFor(listOf("Amazon Shopping")).contains("p_36"))
    T.check("Flipkart: a filtered list too", Lessons.textFor(listOf("Flipkart")).contains("facets.price_range"))
    T.check("the first question is not waited on for long", Agent.PREFLIGHT_WAIT_MS <= 15_000)
}

fun testThirdVideos() {
    // BookMyShow's search results for "Ramayana", as on the phone in the recording of 17 Nov, 11:40.
    val rowsA = """
1 [button] Navigate up @60,45
2 [field] Ramayana (focused) @400,45
3 [tap] All (selected) @55,80
4 [tap] Movies @170,80
5 [text] Showing 8 results @110,103
6 [tap] Ramayana · In cinemas now | Hindi, Kannada & 3 more @500,150
7 [tap] Valmiki Ramayana · In upcoming movies | Hindi @500,205
8 [tap] Nippon Ramayana Film · As an Artist @500,260
9 [tap] Ramayan Tiwari · As an Actor @500,315
10 [tap] Sillekyatha Ramayana (An Overnight Pe… · In puppetry | 20th Nov · Shreeram Lagoo Rang Avakash: Pune @500,375
11 [tap] S. Ramaanandh · As an Artist @500,430
12 [tap] Rameshwaram - Your Emotions in the … · In storytelling | 6th Dec @500,480
13 [button] View All Results @500,510
14 [tap] Home @190,930
15 [tap] Search (selected) @920,930
    """.trimIndent()

    println("The screen list is read as items")
    val a = Opener.items(rowsA)
    T.eq("every line is an item", 15, a.size)
    T.check("the box that was typed into is a field", a[1].isField && a[1].label == "Ramayana" && a[1].extra == "focused")
    T.check("a bracket in a name stays in the name", a[9].label.startsWith("Sillekyatha Ramayana (An Overnight") && a[9].y == 375)
    T.eq("an empty field with a hint", "hint: Search for movies, focused", Opener.items("3 [field]  (hint: Search for movies, focused) @500,60").single().extra)
    T.eq("lines that are not elements are skipped", 1, Opener.items("… (more not shown: scroll)\n4 [tap] Movies @170,80\n").size)

    println("The film in cinemas is opened, never the upcoming one with a similar name")
    val ra = Opener.movieResults(a, "Ramayana")
    T.eq("what each result is", "Ramayana=NOW, Valmiki Ramayana=UPCOMING, Nippon Ramayana Film=OTHER, Ramayan Tiwari=OTHER, Sillekyatha Ramayana (An Overnight Pe…=OTHER", ra.joinToString { "${it.title}=${it.showing}" })
    T.eq("the one in cinemas is the one to open", 6, (Opener.chooseMovie(ra, "Ramayana") as? Opener.Choice.Tap)?.result?.item?.id)
    T.eq("…also with the user's spelling slip", 6, (Opener.chooseMovie(Opener.movieResults(a, "ramyana"), "ramyana") as? Opener.Choice.Tap)?.result?.item?.id)
    T.eq("…and with 'movie' said after the name", 6, (Opener.chooseMovie(Opener.movieResults(a, "Ramayana movie"), "Ramayana movie") as? Opener.Choice.Tap)?.result?.item?.id)
    val onlyUpcoming = Opener.movieResults(a.filter { it.id != 6 }, "Ramayana")
    val tell = Opener.chooseMovie(onlyUpcoming, "Ramayana") as? Opener.Choice.Tell
    T.check("nothing in cinemas: nothing is opened, the user is asked", tell != null && tell.note.contains("Valmiki Ramayana (upcoming, cannot be booked)") && tell.note.contains("ask_user") && tell.note.contains("Do NOT open"))
    val two = Opener.items("6 [tap] Ramayana · In cinemas now | Hindi @500,150\n7 [tap] Ramayana · In cinemas now | Tamil @500,205")
    T.check("two in cinemas with the same name: the AI or the user decides", (Opener.chooseMovie(Opener.movieResults(two, "Ramayana"), "Ramayana") as? Opener.Choice.Tell)?.note?.contains("Several films in cinemas") == true)
    val part = Opener.items("6 [tap] Ramayana: The Legend of Prince Rama · In cinemas now | Hindi @500,150\n7 [tap] Valmiki Ramayana · In upcoming movies | Hindi @500,205")
    T.eq("the only one in cinemas, with a longer name, is opened", 6, (Opener.chooseMovie(Opener.movieResults(part, "Ramayana"), "Ramayana") as? Opener.Choice.Tap)?.result?.item?.id)
    T.check("no result about the film: left to the AI", Opener.chooseMovie(Opener.movieResults(Opener.items("6 [tap] Open Mic · In music shows @500,150"), "Ramayana"), "Ramayana") === Opener.Choice.Leave)
    val plain = Opener.items("6 [tap] Ramayana @500,150\n7 [tap] Valmiki Ramayana @500,300")
    T.eq("an app that does not say what a row is: the exact name", 6, (Opener.chooseMovie(Opener.movieResults(plain, "Ramayana"), "Ramayana") as? Opener.Choice.Tap)?.result?.item?.id)
    // The same list when the app joins a row's texts itself, and when each text is an element of its own.
    val rowsB = "2 [field] Ramayana (focused) @400,45\n6 [tap] Ramayana, In cinemas now | Hindi, Kannada & 3 more @500,150\n7 [tap] Valmiki Ramayana, In upcoming movies | Hindi @500,205"
    T.eq("rows joined by the app with a comma", 6, (Opener.chooseMovie(Opener.movieResults(Opener.items(rowsB), "Ramayana"), "Ramayana") as? Opener.Choice.Tap)?.result?.item?.id)
    val rowsC = "2 [field] Ramayana (focused) @400,45\n6 [text] Ramayana @400,150\n7 [text] In cinemas now | Hindi, Kannada & 3 more @420,170\n8 [text] Valmiki Ramayana @400,243\n9 [text] In upcoming movies | Hindi @420,263"
    val rc = Opener.movieResults(Opener.items(rowsC), "Ramayana")
    T.eq("title and label as separate lines", "6=NOW, 8=UPCOMING", rc.joinToString { "${it.item.id}=${it.showing}" })
    T.eq("…the right one is opened", 6, (Opener.chooseMovie(rc, "Ramayana") as? Opener.Choice.Tap)?.result?.item?.id)
    T.check("'Book tickets' is found on the film's page", Opener.bookButton(Opener.items("4 [text] Ramayana @300,90\n9 [button] Book tickets @500,940"))?.id == 9)
    T.check("…and 'Book a table at Barbeque' is not it", Opener.bookButton(Opener.items("9 [button] Book a table at Barbeque Nation @500,940")) == null)

    println("A tap by text means the thing with that name")
    fun c(text: String, clickable: Boolean = true, editable: Boolean = false, top: Int = 0, desc: String = "") = com.seyal.app.device.TextPick.Cand(text, desc, clickable, editable, top)
    val merged = listOf(c("Ramayana", editable = true, top = 40), c("Ramayana, In cinemas now | Hindi, Kannada & 3 more", top = 300), c("Valmiki Ramayana, In upcoming movies | Hindi", top = 440))
    T.check("(the wrong row is the shorter one: the old rule took it)", merged[2].text.length < merged[1].text.length)
    T.eq("'Ramayana' is the row that begins with it, not 'Valmiki Ramayana'", 1, com.seyal.app.device.TextPick.best("Ramayana", merged))
    val split = listOf(c("Ramayana", editable = true, top = 40), c("Ramayana", top = 300), c("Valmiki ", top = 440), c("Ramayana", top = 440))
    T.eq("…and never the search box the word was typed into", 1, com.seyal.app.device.TextPick.best("Ramayana", split))
    T.eq("an exact label wins over a longer one", 1, com.seyal.app.device.TextPick.best("2", listOf(c("2D", top = 10), c("2", top = 50), c("20", top = 90))))
    T.eq("'Book' is 'Book tickets', not 'Facebook'", 1, com.seyal.app.device.TextPick.best("Book", listOf(c("Facebook", top = 10), c("Book tickets", top = 900))))
    T.eq("among exact ones, the tappable", 1, com.seyal.app.device.TextPick.best("Search", listOf(c("Search", clickable = false, top = 10), c("Search", top = 900))))
    T.eq("a Search tab rather than the box that says 'Search for movies'", 1, com.seyal.app.device.TextPick.best("Search", listOf(c("Search for movies", editable = true, top = 40), c("Search", top = 900))))
    T.eq("only a box matches: the box", 0, com.seyal.app.device.TextPick.best("Search", listOf(c("Search for movies", editable = true, top = 40))))
    T.eq("a label that only contains the words still works when it is all there is", 0, com.seyal.app.device.TextPick.best("Ramayana", listOf(c("Valmiki Ramayana", top = 440))))
    T.eq("'exact only' takes nothing else", null, com.seyal.app.device.TextPick.best("Ramayana", listOf(c("Valmiki Ramayana", top = 440)), exactOnly = true))
    T.eq("a button named only in its description", 0, com.seyal.app.device.TextPick.best("Close", listOf(c("", desc = "Close", top = 10), c("Closed on Sundays", top = 300))))
    T.eq("nothing with those words", null, com.seyal.app.device.TextPick.best("Ramayana", listOf(c("Open Mic"), c("Home"))))

    println("Wrong turns are caught as they happen")
    val upcoming = "1 [button] Navigate up @40,45\n2 [text] Valmiki Ramayana @300,45\n3 [text] Releasing in 2026 @500,330\n4 [text] 14.2K+ are interested @300,375\n5 [text] Mark interested to know when bookings open @330,395\n6 [button] I'm interested @840,385\n7 [text] Top offers for you @200,545\n8 [tap] Not cashback, it's a gold-back card @500,930"
    T.check("the page of a film that is not out yet", Detour.upcoming(upcoming))
    T.check("…with a curly apostrophe too", Detour.upcoming("3 [button] I’m interested @840,385"))
    T.check("a film's page with Book tickets is fine", !Detour.upcoming("2 [text] Ramayana @300,45\n3 [text] 9.2/10 398K votes @300,300\n9 [button] Book tickets @500,940"))
    T.check("a list of shows is fine", !Detour.upcoming("2 [text] Ramayana @300,45\n3 [tap] TUE 17 NOV (selected) @100,120\n5 [text] Broadway Cinemas: Coimbatore @300,300\n6 [tap] 03:05 PM @120,350"))
    T.check("the note sends it back, and forbids the web", Detour.UPCOMING.contains("Press Back") && Detour.UPCOMING.contains("In cinemas now") && Detour.UPCOMING.contains("Do not look it up on the web"))
    T.check("a tap in BookMyShow that opened Chrome was an advert", Detour.strayed("screen_tap", "com.bt.bms", "com.android.chrome"))
    T.check("…or the Play Store, from Swiggy", Detour.strayed("screen_tap_at", "in.swiggy.android", "com.android.vending"))
    T.check("a tap that stays in the app is fine", !Detour.strayed("screen_tap", "com.bt.bms", "com.bt.bms"))
    T.check("a payment app opening is not an advert (Seyal stops there anyway)", !Detour.strayed("screen_tap", "com.bt.bms", "com.google.android.apps.nbu.paisa.user"))
    T.check("a link tapped in another kind of app is the user's business", !Detour.strayed("screen_tap", "com.instagram.android", "com.android.chrome"))
    T.check("opening a link on purpose is not a stray tap", !Detour.strayed("open_url", "com.bt.bms", "com.android.chrome"))
    T.check("working inside the browser is fine", !Detour.strayed("screen_tap", "com.android.chrome", "com.android.chrome"))
    T.check("the note names both apps and warns about adverts", Detour.strayNote("BookMyShow", "Chrome").let { it.contains("opened Chrome") && it.contains("back to BookMyShow") && it.contains("credit card") })

    println("Food apps: the search bar and the address sheet")
    val swiggyHome = "1 [tap] Home 2 · 7/120, Ezhil Nagar, Ezhil Nagar, Vellanoipat… @150,60\n2 [tap] Food @220,150\n3 [tap] Dineout @790,150\n4 [tap] Search for 'Biryani' @400,245\n5 [check:off] VEG @945,245\n6 [tap] OFFERS @300,305\n7 [text] What's on your mind? @200,870"
    T.eq("Swiggy's search bar, whatever dish it is suggesting", 4, Opener.searchEntry(Opener.items(swiggyHome))?.id)
    T.eq("BookMyShow's Search tab", 15, Opener.searchEntry(a.filter { !it.isField })?.id)
    T.eq("an icon with no label but a 'search' id", 3, Opener.searchEntry(Opener.items("2 [text] Movies @100,60\n3 [button] (unlabeled) (id: search icon) @930,60"))?.id)
    T.eq("no search on the screen", null, Opener.searchEntry(Opener.items("2 [text] Select Delivery Address @300,690\n3 [tap] Home 2 @200,770")))
    val sheet = swiggyHome + "\n8 [text] Location Permission is Off @300,580\n9 [button] GRANT @870,578\n10 [text] Granting location permission will ensure accurate address and hassle free delivery @400,610\n11 [text] Select Delivery Address @300,690\n12 [tap] VIEW ALL @860,692\n13 [tap] Broadway · 1, Broadway Megaaplex Mall, Kalapatti Ma… @500,770\n14 [tap] Home 2 · 7/120, Ezhil Nagar, Ezhil Nagar, Vellanoipat… @500,850\n15 [tap] Enter Location Manually @400,950"
    T.eq("the address already in use is the one tapped, never GRANT", 14, Opener.addressRow(Opener.items(sheet))?.id)
    val sheetOnly = "8 [text] Location Permission is Off @300,580\n9 [button] GRANT @870,578\n11 [text] Select Delivery Address @300,690\n12 [tap] VIEW ALL @860,692\n13 [tap] Home 2 · 7/120, Ezhil Nagar @500,770\n14 [tap] Broadway · 1, Broadway Megaaplex Mall @500,850\n15 [tap] Enter Location Manually @400,950"
    T.eq("with nothing to go by, the first saved address", 13, Opener.addressRow(Opener.items(sheetOnly))?.id)
    T.eq("no address sheet, nothing to tap", null, Opener.addressRow(Opener.items(swiggyHome)))

    println("What is done by code for which request")
    val film = Opener.plan(Preflight.Kind.MOVIE, "BookMyShow", Order(kind = "MOVIE", item = "Ramayana Hindi 3D", count = 2)) as? Opener.Plan.Movie
    T.eq("a film in BookMyShow: its name, without language or 3D", "Ramayana", film?.title)
    T.eq("a film whose name is a language word keeps it", "English Vinglish", Opener.movieQuery("English Vinglish movie"))
    T.eq("…and one word is never stripped to nothing", "Tamil", Opener.movieQuery("Tamil"))
    T.check("another ticket app is left to the AI", Opener.plan(Preflight.Kind.MOVIE, "District", Order(kind = "MOVIE", item = "Ramayana")) == null)
    T.eq("a dish in Swiggy, without the count", "mutton biryani", (Opener.plan(Preflight.Kind.FOOD, "Swiggy", Order(kind = "FOOD", item = "2 plates of mutton biryani")) as? Opener.Plan.Food)?.query)
    T.eq("a named restaurant is what is searched", "SS Hyderabad Biryani", (Opener.plan(Preflight.Kind.FOOD, "Zomato", Order(kind = "FOOD", item = "mutton biryani", place = "SS Hyderabad Biryani")) as? Opener.Plan.Food)?.query)
    val bus = Opener.plan(Preflight.Kind.TRAVEL, "redBus", Order(kind = "TRAVEL", item = "bus", from = "Coimbatore", to = "Bangalore", date = "2026-11-18")) as? Opener.Plan.Link
    T.check("a bus: the list for that route and day opens directly", bus?.url?.contains("coimbatore-to-bangalore") == true && bus.url.contains("onward=18-Nov-2026"))
    T.check("a bus with no day yet: nothing by code", Opener.plan(Preflight.Kind.TRAVEL, "redBus", Order(kind = "TRAVEL", item = "bus", from = "Coimbatore", to = "Bangalore")) == null)
    T.check("shopping is left to the AI", Opener.plan(Preflight.Kind.SHOP, "Amazon", Order(kind = "SHOP", item = "black shoes")) == null)
    T.check("nothing to search for, nothing by code", Opener.plan(Preflight.Kind.MOVIE, "BookMyShow", Order(kind = "MOVIE", count = 2)) == null)

    println("Small things from the recording")
    val chat = Session("v3", 1L)
    chat.messages += Msg("user", listOf(Part.Text("book 2 tickets for Ramayana")))
    repeat(29) { i -> chat.messages += Msg("assistant", listOf(Part.Call("o$i", "screen_tap", JSONObject()))); chat.messages += Msg("tool", listOf(Part.Result("o$i", "screen_tap", JSONObject().put("ok", true)))) }
    chat.messages += Msg("assistant", listOf(Part.Text("Ready at the payment page.")))
    chat.messages += Msg("user", listOf(Part.Text("buy me mutton briyani from nearby restaurant")))
    T.eq("a new request in a long chat starts at step 0, not 29", 0, Agent.stepsOfRequest(chat))
    chat.messages += Msg("assistant", listOf(Part.Call("n1", "open_app", JSONObject()))); chat.messages += Msg("tool", listOf(Part.Result("n1", "open_app", JSONObject().put("ok", true))))
    chat.messages += Msg("user", listOf(Part.Text(Agent.CHECK + "The app asked for a delivery address…")))
    T.eq("…and Seyal's own notes do not reset the count", 1, Agent.stepsOfRequest(chat))
    T.check("a five-part question gets no tap-buttons for its first part", Agent.manyParts("Please provide the missing details:\n1. Theatre (Any nearby, Kgam Cinemas, Cinepolis)\n2. Day (Today, Tomorrow)\n3. Show time"))
    T.check("…also written as 1) 2)", Agent.manyParts("Two things before I book:\n1) How many tickets?\n2) Which seat class?"))
    T.check("a single question keeps its buttons", !Agent.manyParts("How many tickets? (They will be seats next to each other.)"))
    T.check("…and a price like 2.5 is not a second part", !Agent.manyParts("Is ₹2.50 extra per ticket fine?"))
    val bms = Lessons.textFor(listOf("BookMyShow"))
    T.check("BookMyShow: only 'In cinemas now' can be booked", bms.contains("In cinemas now") && bms.contains("never open them"))
    T.check("the order's item is written with the spelling put right", Order.decl(Preflight.Kind.MOVIE).params.toString().contains("ramyana"))
}

fun main() {
    testModels(); testTimes(); testParsers(); testMemory(); testLlm(); testTriggers(); testFiles(); testPhase2(); testPhase3(); testPhase4(); testBasics(); testBatch23(); testReliability(); testQuality(); testTrust(); testPhoneVideos(); testLongChats(); testSecondVideos(); testThirdVideos()
    println("\n${T.passed} passed, ${T.failed} failed")
    if (T.failed > 0) System.exit(1)
}

/** Same checks, run by Gradle (./gradlew testLiteDebugUnitTest) on every build. */
class CoreJUnitTest {
    @org.junit.Test
    fun brain() {
        testModels(); testTimes(); testParsers(); testMemory(); testLlm(); testTriggers(); testFiles(); testPhase2(); testPhase3(); testPhase4(); testBasics(); testBatch23(); testReliability(); testQuality(); testTrust(); testPhoneVideos(); testLongChats(); testSecondVideos(); testThirdVideos()
        org.junit.Assert.assertEquals("failed checks", 0, T.failed)
    }
}
