package com.seyal.app

import com.seyal.app.ai.PlanParser
import com.seyal.app.data.Run
import com.seyal.app.data.RunStatus
import com.seyal.app.data.Step
import com.seyal.app.data.StepStatus
import com.seyal.app.data.Trigger
import com.seyal.app.engine.Lanes
import com.seyal.app.engine.Parsers
import com.seyal.app.engine.Times
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** Plain-Kotlin tests for Seyal's brain. Run with: kotlinc ... -include-runtime, then java CoreTestKt */
object T {
    var passed = 0
    var failed = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) { passed++; println("  ok   $name") } else { failed++; println("  FAIL $name  $detail") }
    }
    fun eq(name: String, expected: Any?, actual: Any?) = check(name, expected == actual, "expected=$expected actual=$actual")
}

val TOOLS = setOf("find_contact", "send_whatsapp", "send_email", "call", "meeting_link", "create_event", "set_alarm", "remind", "fetch_news", "web_search", "read_page", "movie_info", "ai", "notify", "open_app", "open_url")

fun testTimes() {
    println("Times")
    val now = LocalDateTime.now()
    T.eq("ISO", LocalDateTime.of(2026, 11, 13, 7, 0), Times.parse("2026-11-13T07:00"))
    T.eq("ISO with space", LocalDateTime.of(2026, 11, 13, 7, 0), Times.parse("2026-11-13 07:00"))
    T.eq("ISO seconds", LocalDateTime.of(2026, 11, 13, 7, 0), Times.parse("2026-11-13T07:00:00"))
    T.eq("date only", LocalDateTime.of(2026, 11, 13, 9, 0), Times.parse("2026-11-13"))
    val tmr = Times.parse("tomorrow 07:00")!!
    T.eq("tomorrow", LocalDate.now().plusDays(1), tmr.toLocalDate()); T.eq("tomorrow time", 7, tmr.hour)
    val pm = Times.parse("5:30 pm")!!
    T.eq("pm clock", 17, pm.hour); T.eq("pm minutes", 30, pm.minute)
    T.check("past clock rolls to next day", pm.isAfter(now))
    val fri = Times.parse("friday 07:00")!!
    T.eq("friday", DayOfWeek.FRIDAY, fri.dayOfWeek); T.check("friday is in future", fri.isAfter(now))
    val in20 = Times.parse("in 20 minutes")!!
    T.check("in 20 minutes", in20.isAfter(now.plusMinutes(19)) && in20.isBefore(now.plusMinutes(21)))
    T.eq("garbage", null, Times.parse("sometime"))
    T.eq("blank", null, Times.parse(""))
    T.eq("clock 7am", 7, Times.parseClock("7am")?.hour)
    T.eq("clock 12am", 0, Times.parseClock("12am")?.hour)
    T.eq("clock 12pm", 12, Times.parseClock("12pm")?.hour)

    val daily = Trigger("daily", "08:00")
    val next = Times.nextFire(daily)
    T.check("daily next is future", next > System.currentTimeMillis())
    val nd = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), java.time.ZoneId.systemDefault())
    T.eq("daily at 8", 8, nd.hour)
    T.check("daily within 24h", next - System.currentTimeMillis() <= 24 * 3600_000L)
    val weekly = Trigger("weekly", "09:00", days = listOf(1, 2, 3, 4, 5))
    val wd = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(Times.nextFire(weekly)), java.time.ZoneId.systemDefault())
    T.check("weekday trigger lands on weekday", wd.dayOfWeek.value in 1..5)
    val once = Trigger("once", "09:00", day = "wednesday")
    val od = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(Times.nextFire(once)), java.time.ZoneId.systemDefault())
    T.eq("once wednesday", DayOfWeek.WEDNESDAY, od.dayOfWeek)
    T.eq("once in the past never fires", 0L, Times.nextFire(Trigger("once", "09:00", day = "2020-01-01")))
    T.eq("describe", "Daily · 08:00", daily.describe())
}

fun testPlanParser() {
    println("PlanParser")
    val raw = """Here is the plan:
```json
{"mode":"now","title":"Message and call Ram","steps":[
 {"id":"s1","tool":"find_contact","title":"Find Ram","args":{"name":"Ram"},"depends_on":[]},
 {"id":"s2","tool":"send_whatsapp","title":"WhatsApp Ram","args":{"phone":"{{s1.phone}}","name":"{{s1.name}}","text":"Coming home"},"depends_on":["s1"]},
 {"id":"s3","tool":"call","title":"Call Ram","args":{"phone":"{{s1.phone}}","name":"{{s1.name}}"},"depends_on":["s2"]},
 {"id":"s4","tool":"movie_info","title":"Ramayana released?","args":{"title":"Ramayana"},"depends_on":[]},
 {"id":"s5","tool":"fetch_news","title":"New releases","args":{"topic":"new movie releases","count":8},"depends_on":[]},
 {"id":"s6","tool":"ai","title":"Decide","args":{"task":"Say if Ramayana released and list new releases","input":"{{s4}} {{s5}}"},"depends_on":["s4","s5"]},
 {"id":"s7","tool":"hack_phone","title":"bad","args":{},"depends_on":[]},
 {"id":"s8","tool":"notify","title":"loop a","args":{"text":"x"},"depends_on":["s9","s99"]},
 {"id":"s9","tool":"notify","title":"loop b","args":{"text":"y"},"depends_on":["s8"]}
],"trigger":null,"answer":null,"question":null,"options":[]}
```"""
    val plan = PlanParser.parse(PlanParser.extractJson(raw), raw, TOOLS)
    T.eq("mode", "now", plan.mode)
    T.eq("unknown tool dropped", 8, plan.steps.size)
    T.eq("unknown dep removed", listOf("s9"), plan.steps.first { it.id == "s8" }.dependsOn.let { if (it.isEmpty()) emptyList() else it })
    val s8 = plan.steps.first { it.id == "s8" }; val s9 = plan.steps.first { it.id == "s9" }
    T.check("cycle broken", !(s8.dependsOn.contains("s9") && s9.dependsOn.contains("s8")), "s8=${s8.dependsOn} s9=${s9.dependsOn}")
    T.eq("title kept", "Message and call Ram", plan.title)

    val ans = PlanParser.parse(JSONObject("""{"mode":"answer","answer":"Binary search halves the range each step.","steps":[]}"""), "", TOOLS)
    T.eq("answer mode", "answer", ans.mode)
    val clar = PlanParser.parse(JSONObject("""{"mode":"clarify","question":"Which Ram?","options":["Ram (project)","Ram Kumar"]}"""), "", TOOLS)
    T.eq("clarify options", 2, clar.options.size)
    val empty = PlanParser.parse(JSONObject("""{"mode":"now","steps":[]}"""), "", TOOLS)
    T.eq("empty now → clarify", "clarify", empty.mode)
    val sched = PlanParser.parse(JSONObject("""{"mode":"schedule","title":"AI news","trigger":{"type":"daily","time":"08:00"},"steps":[{"tool":"fetch_news","title":"News","args":{"topic":"AI"}},{"tool":"ai","title":"Sum","args":{"task":"summarise","input":"{{s1}}"},"depends_on":["s1"]}]}"""), "", TOOLS)
    T.eq("schedule mode", "schedule", sched.mode)
    T.eq("ids filled", "s1", sched.steps[0].id)
    T.eq("trigger parsed", "daily", sched.trigger?.type)
    val noTrig = PlanParser.parse(JSONObject("""{"mode":"schedule","steps":[{"tool":"notify","args":{"text":"hi"}}]}"""), "", TOOLS)
    T.eq("schedule without trigger → now", "now", noTrig.mode)
    T.check("prose only fails cleanly", runCatching { PlanParser.extractJson("I cannot do that.") }.isFailure)
    val wrapped = PlanParser.extractJson("Sure! {\"mode\":\"answer\",\"answer\":\"hi\"} hope it helps")
    T.eq("json inside prose", "hi", wrapped.optString("answer"))
}

fun step(id: String, tool: String, deps: List<String> = emptyList(), args: String = "{}") = Step(id, tool, id, JSONObject(args), deps)

fun testLanes() {
    println("Lanes")
    val s1 = step("s1", "find_contact"); val s2 = step("s2", "send_whatsapp", listOf("s1"), """{"phone":"{{s1.phone}}","text":"hi {{s1.name}}"}""")
    val s3 = step("s3", "call", listOf("s2")); val s4 = step("s4", "movie_info"); val s5 = step("s5", "fetch_news")
    val s6 = step("s6", "ai", listOf("s4", "s5"), """{"input":"{{s4.results.0.title}} / {{s5}}"}""")
    val steps = listOf(s1, s2, s3, s4, s5, s6)
    T.eq("3 lanes start together", listOf("s1", "s4", "s5"), Lanes.ready(steps).map { it.id })
    s1.status = StepStatus.DONE; s1.output = JSONObject("""{"name":"Ram","phone":"9876543210"}""")
    T.eq("s2 ready after s1", listOf("s2", "s4", "s5"), Lanes.ready(steps).map { it.id })
    val sub = Lanes.substitute(s2.args, steps)
    T.eq("phone substituted", "9876543210", sub.optString("phone"))
    T.eq("name inside text", "hi Ram", sub.optString("text"))
    s4.status = StepStatus.DONE; s4.output = JSONObject("""{"results":[{"title":"Ramayana (2026 film)","extract":"..."}]}""")
    s5.status = StepStatus.FAILED; s5.error = "timeout"
    Lanes.skipBlocked(steps)
    T.eq("s6 skipped when s5 failed", StepStatus.SKIPPED, s6.status)
    val sub6 = Lanes.substitute(s6.args, steps)
    T.check("array path works", sub6.optString("input").startsWith("Ramayana (2026 film) / "), sub6.optString("input"))
    T.eq("unknown ref kept literal", "{{s77.x}}", Lanes.substituteText("{{s77.x}}", steps.associateBy { it.id }))
    T.eq("missing field → empty", "", Lanes.substituteText("{{s1.email}}", steps.associateBy { it.id }))

    // approval → cancel chain
    s2.status = StepStatus.CANCELLED
    Lanes.skipBlocked(steps)
    T.eq("call skipped after user said no", StepStatus.SKIPPED, s3.status)
    T.check("finished", Lanes.isFinished(steps))
    val out = Lanes.outcome(steps)
    T.eq("partial status", RunStatus.PARTIAL, out.status)
    T.eq("summary line", "2 of 6 done · 4 didn't", out.summary)
    T.check("final text has log", out.finalText.contains("✓") && out.finalText.contains("✗"), out.finalText)

    // all good
    val a = step("a", "fetch_news"); val b = step("b", "ai", listOf("a"))
    a.status = StepStatus.DONE; a.line = "Fetched 7"; b.status = StepStatus.DONE; b.output = JSONObject().put("text", "- Gemini 3 launched\n- OpenAI agents")
    val ok = Lanes.outcome(listOf(a, b))
    T.eq("done status", RunStatus.DONE, ok.status)
    T.eq("done summary", "All 2 tasks done", ok.summary)
    T.check("ai text first", ok.finalText.startsWith("- Gemini 3 launched"), ok.finalText)
    T.eq("single done", "Done", Lanes.outcome(listOf(step("x", "notify").apply { status = StepStatus.DONE })).summary)

    // stuck detection
    val p = step("p", "notify", listOf("q")); val q = step("q", "notify", listOf("p"))
    T.check("stuck loop fails instead of hanging", Lanes.failStuck(listOf(p, q), false) && p.status == StepStatus.FAILED)
    T.check("nothing stuck when a job runs", !Lanes.failStuck(listOf(step("r", "notify", listOf("zz"))), true))
}

fun testModels() {
    println("Models")
    val run = Run("r1", "msg ram", "Msg", 1L, mutableListOf(step("s1", "find_contact"), step("s2", "call", listOf("s1"))))
    run.steps[0].status = StepStatus.RUNNING; run.status = RunStatus.RUNNING
    run.options = listOf("a", "b"); run.question = "Which?"
    val back = Run.fromJson(JSONObject(run.toJson().toString()))
    T.eq("run id", "r1", back.id)
    T.eq("steps survive", 2, back.steps.size)
    T.eq("deps survive", listOf("s1"), back.steps[1].dependsOn)
    T.eq("running step marked failed after restart", StepStatus.FAILED, back.steps[0].status)
    T.check("run not left open", !back.status.isOpen)
    T.eq("options survive", listOf("a", "b"), back.options)
    T.check("plan json renders", run.planJson().contains("\"tool\": \"call\""))
    val t = Trigger.fromJson(JSONObject("""{"type":"WEEKLY","time":"18:30","days":[1,3]}"""))
    T.eq("trigger type lowercased", "weekly", t.type); T.eq("trigger days", listOf(1, 3), t.days)
    T.eq("trigger describe", "Weekly · Mon Wed · 18:30", t.describe())
}

fun testParsers() {
    println("Parsers")
    val rss = """<rss><channel>
<item><title>Google launches Gemini 3 for Android agents - The Verge</title><link>https://a</link><pubDate>Sat, 07 Nov 2026 05:00:00 GMT</pubDate><source url="x">The Verge</source></item>
<item><title><![CDATA[Google launches Gemini 3 for Android agents - TechCrunch]]></title><link>https://b</link><source url="y">TechCrunch</source></item>
<item><title>OpenAI &amp; Microsoft sign new deal - Reuters</title><link>https://c</link><source url="z">Reuters</source></item>
<item><title>Something else entirely</title><link>https://d</link></item>
</channel></rss>"""
    val items = Parsers.newsItems(rss, 10)
    T.eq("duplicate story removed", 3, items.length())
    T.eq("source stripped from title", "Google launches Gemini 3 for Android agents", items.getJSONObject(0).getString("title"))
    T.eq("entity unescaped", "OpenAI & Microsoft sign new deal", items.getJSONObject(1).getString("title"))
    T.eq("count cap", 2, Parsers.newsItems(rss, 2).length())

    val ddg = """<div class="result"><h2><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FRamayana_(film)&amp;rut=abc">Ramayana (2026 film) - <b>Wikipedia</b></a></h2>
<a class="result__snippet" href="x">Ramayana is an upcoming Indian film. Part 1 releases on <b>Diwali 2026</b>.</a></div>
<div class="result"><a class="result__a" href="https://example.com/news">Ramayana release date update</a><a class="result__snippet" href="y">Snippet two</a></div>"""
    val res = Parsers.searchResults(ddg, 5)
    T.eq("two results", 2, res.length())
    T.eq("redirect unwrapped", "https://en.wikipedia.org/wiki/Ramayana_(film)", res.getJSONObject(0).getString("url"))
    T.eq("title tags stripped", "Ramayana (2026 film) - Wikipedia", res.getJSONObject(0).getString("title"))
    T.check("snippet kept", res.getJSONObject(0).getString("snippet").contains("Diwali 2026"))

    val html = "<html><head><title>Hello &amp; welcome</title><style>a{}</style></head><body><script>x()</script><h1>Big</h1><p>One</p><p>Two</p></body></html>"
    T.eq("page title", "Hello & welcome", Parsers.pageTitle(html))
    val text = Parsers.strip(html)
    T.check("script/style removed", !text.contains("x()") && !text.contains("a{}"), text)
    T.check("paragraphs on lines", text.contains("One\nTwo"), text.replace("\n", "\\n"))

    val wiki = """{"query":{"pages":{"2":{"index":2,"title":"Ramayana (1992 film)","extract":"old"},"1":{"index":1,"title":"Ramayana (2026 film)","extract":"new"}}}}"""
    val w = Parsers.wikiResults(wiki)
    T.eq("wiki sorted by index", "Ramayana (2026 film)", w.getJSONObject(0).getString("title"))
    T.eq("wiki bad json → empty", 0, Parsers.wikiResults("nope").length())
}

fun main() {
    testTimes(); testPlanParser(); testLanes(); testModels(); testParsers()
    println("\n${T.passed} passed, ${T.failed} failed")
    if (T.failed > 0) System.exit(1)
}

/** Same checks, run by Gradle (./gradlew testDebugUnitTest) on every build. */
class CoreJUnitTest {
    @org.junit.Test
    fun brain() {
        T.passed = 0; T.failed = 0
        testTimes(); testPlanParser(); testLanes(); testModels(); testParsers()
        println("${T.passed} passed, ${T.failed} failed")
        org.junit.Assert.assertEquals("failed checks", 0, T.failed)
    }
}
