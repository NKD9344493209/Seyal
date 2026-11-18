package com.seyal.app.agent

import android.content.Context
import android.os.PowerManager
import com.seyal.app.ai.Llm
import com.seyal.app.data.Msg
import com.seyal.app.data.Part
import com.seyal.app.data.Settings
import com.seyal.app.device.Notify
import com.seyal.app.tools.Common
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * Dirghadarshi mode on the phone: reads the week's signals from the web, asks the AI once, keeps the report
 * and its predictions ([Foresight] has the rules). Runs when asked ("Scan now", or the foresight tool in a chat)
 * and by itself once a week when the user has switched that on.
 */
object ForesightRun {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val one = Mutex()
    /** What it is doing right now, for the screen ("Reading 7 sources…"); "" when idle. */
    val state = MutableStateFlow("")

    class Outcome(val report: Foresight.Report?, val error: String)

    /** Makes a report now. One at a time: a second request while one is running waits for it and gets the same result. */
    suspend fun run(ctx: Context): Outcome {
        if (!Settings.hasKey) return Outcome(null, "Add your AI key first (Control → AI).")
        if (!one.tryLock()) { one.lock(); one.unlock(); return ForesightStore.latest()?.let { Outcome(it, "") } ?: Outcome(null, ForesightStore.lastError.ifBlank { "The scan did not finish." }) }
        val wake = runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "seyal:foresight").apply { acquire(150_000) } }.getOrNull()
        try {
            val now = System.currentTimeMillis()
            val today = LocalDate.now()
            val sources = Foresight.sources(ForesightStore.topics, today, now)
            state.value = "Reading ${sources.size} sources…"
            // All sources at once; one that is slow or down is left out and named, never fatal.
            val got = coroutineScope {
                sources.map { s -> async(Dispatchers.IO) { s to runCatching { Foresight.parse(s, Common.http(s.url, "application/json,application/xml,*/*", 15_000)) }.getOrDefault(emptyList()) } }.awaitAll()
            }
            val read = got.filter { it.second.isNotEmpty() }.map { it.first.name }
            val failed = got.filter { it.second.isEmpty() }.map { it.first.name }
            val signals = Foresight.pick(got.flatMap { it.second })
            if (signals.size < 12 || got.count { it.second.isNotEmpty() } < 2) {
                return fail(now, "Could not read enough of the sources (${if (read.isEmpty()) "none answered" else "only " + read.joinToString(", ")}). Check the internet and try again.")
            }
            state.value = "Read ${signals.size} signals. Thinking…"
            val calls = ForesightStore.calls()
            val due = Foresight.due(calls, today)
            val answer = try {
                withTimeoutOrNull(90_000) {
                    Llm.chat(Foresight.system(Settings.userName, Memory.text(), ForesightStore.topics, today), listOf(Msg("user", listOf(Part.Text(Foresight.ask(signals, due))))), listOf(Foresight.decl(due.isNotEmpty())), 3000, forceTool = true, onWait = { state.value = it })
                }
            } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (x: Exception) { return fail(now, "The AI could not be reached: ${(x.message ?: x.javaClass.simpleName).take(90)}") }
            val args = answer?.calls?.firstOrNull { it.name == "report" }?.args ?: return fail(now, "The AI did not answer in time. Try again in a minute.")
            val trends = Foresight.read(args, signals, today)
            if (trends.isEmpty()) return fail(now, "The AI's answer did not stand on the signals it was given, so it was thrown away. Try again.")
            Foresight.judge(due, args.optJSONArray("checks"), today)
            val report = Foresight.Report(System.currentTimeMillis(), today.toString(), trends, read, failed, signals.size)
            ForesightStore.add(report, Foresight.newCalls(trends, calls, today))
            return Outcome(report, "")
        } finally {
            state.value = ""
            runCatching { if (wake?.isHeld == true) wake.release() }
            one.unlock()
        }
    }

    private fun fail(now: Long, why: String): Outcome { ForesightStore.tried(now, why); return Outcome(null, why) }

    /** "Scan now" on the screen. */
    fun start(ctx: Context) { val app = ctx.applicationContext; scope.launch { runCatching { run(app) } } }

    /**
     * The weekly report, when it is switched on and due. Called from the background wake-ups (the alarm tick and the
     * 15-minute heartbeat), so a Sunday morning without internet is caught up later the same day.
     */
    fun weekly(ctx: Context) {
        val now = System.currentTimeMillis()
        if (!ForesightStore.enabled || Settings.paused || !Settings.hasKey || !Foresight.dueNow(now, ForesightStore.lastOkAt, ForesightStore.lastTryAt)) return
        val app = ctx.applicationContext
        scope.launch {
            val out = runCatching { run(app) }.getOrNull() ?: return@launch
            val r = out.report ?: return@launch
            runCatching { Notify.foresight(app, "Dirghadarshi · ${r.trends.size} things rising this week", Foresight.text(r, ForesightStore.calls(), full = false)) }
        }
    }
}
