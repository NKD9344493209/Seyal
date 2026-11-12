package com.seyal.app

import com.seyal.app.agent.Memory
import com.seyal.app.ai.Llm
import com.seyal.app.agent.Agent
import com.seyal.app.tools.FileTools
import com.seyal.app.device.AdSkipper
import com.seyal.app.device.PhoneEvents
import com.seyal.app.agent.Preflight
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

fun main() {
    testModels(); testTimes(); testParsers(); testMemory(); testLlm(); testTriggers(); testFiles(); testPhase2()
    println("\n${T.passed} passed, ${T.failed} failed")
    if (T.failed > 0) System.exit(1)
}

/** Same checks, run by Gradle (./gradlew testLiteDebugUnitTest) on every build. */
class CoreJUnitTest {
    @org.junit.Test
    fun brain() {
        testModels(); testTimes(); testParsers(); testMemory(); testLlm(); testTriggers(); testFiles(); testPhase2()
        org.junit.Assert.assertEquals("failed checks", 0, T.failed)
    }
}
