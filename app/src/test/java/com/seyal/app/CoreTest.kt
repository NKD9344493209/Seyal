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

fun main() {
    testModels(); testTimes(); testParsers(); testMemory(); testLlm(); testTriggers(); testFiles(); testPhase2(); testPhase3(); testPhase4()
    println("\n${T.passed} passed, ${T.failed} failed")
    if (T.failed > 0) System.exit(1)
}

/** Same checks, run by Gradle (./gradlew testLiteDebugUnitTest) on every build. */
class CoreJUnitTest {
    @org.junit.Test
    fun brain() {
        testModels(); testTimes(); testParsers(); testMemory(); testLlm(); testTriggers(); testFiles(); testPhase2(); testPhase3(); testPhase4()
        org.junit.Assert.assertEquals("failed checks", 0, T.failed)
    }
}
