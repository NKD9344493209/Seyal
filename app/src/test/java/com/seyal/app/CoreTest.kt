package com.seyal.app

import com.seyal.app.agent.Memory
import com.seyal.app.ai.Llm
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
    val reset = Llm.quotaResetAt() - System.currentTimeMillis()
    T.check("daily reset within a day", reset in 1..(25 * 3_600_000L), "$reset ms")
}

fun main() {
    testModels(); testTimes(); testParsers(); testMemory(); testLlm()
    println("\n${T.passed} passed, ${T.failed} failed")
    if (T.failed > 0) System.exit(1)
}

/** Same checks, run by Gradle (./gradlew testLiteDebugUnitTest) on every build. */
class CoreJUnitTest {
    @org.junit.Test
    fun brain() {
        testModels(); testTimes(); testParsers(); testMemory(); testLlm()
        org.junit.Assert.assertEquals("failed checks", 0, T.failed)
    }
}
