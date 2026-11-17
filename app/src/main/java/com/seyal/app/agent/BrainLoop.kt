package com.seyal.app.agent

import android.content.Context
import android.os.PowerManager
import com.seyal.app.ai.Llm
import com.seyal.app.data.Msg
import com.seyal.app.data.Part
import com.seyal.app.data.SessionStatus
import com.seyal.app.data.Settings
import com.seyal.app.device.Notify
import com.seyal.app.device.SeyalNotificationListener
import com.seyal.app.util.Times
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Brain on the phone: takes in each message, waits a moment for the rest of what that person is typing,
 * thinks once ([Brain]), then tells, offers or (where the user has allowed it) does, and learns from the answer.
 * It works only while the Brain switch is on, never on group chats, and never while Seyal is paused.
 */
object BrainLoop {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private class Waiting(val first: Long) { val texts = ArrayList<String>(); var key = ""; var canReply = false; var job: Job? = null }
    private val waiting = HashMap<String, Waiting>()
    /** Messages already thought about (the same text re-posted is not thought about twice). */
    private val seen = LinkedHashSet<Int>()
    /** One AI thought at a time. */
    private val one = Mutex()

    private fun today() = LocalDate.now().toString()

    /**
     * A message came in. [who]: the notification's title (the person). [key]: the notification, so a reply can go
     * back through it. Several messages from one chat within [waitMs] are thought about together.
     */
    fun onMessage(ctx: Context, app: String, title: String, text: String, group: Boolean, message: Boolean, key: String, canReply: Boolean, waitMs: Long = 25_000L) {
        if (!Settings.brainOn || Settings.paused || !message || group || text.isBlank()) return
        val who = Brain.name(title)
        val app0 = ctx.applicationContext
        val now = System.currentTimeMillis()
        val chat = "$app|$who"
        synchronized(waiting) {
            val w = waiting.getOrPut(chat) { Waiting(now) }
            w.texts += text; w.key = key; w.canReply = canReply
            w.job?.cancel()
            // Someone typing ten short messages must not hold the thought back for ever: a minute at most.
            val wait = if (now - w.first > 60_000L) 0L else waitMs
            w.job = scope.launch {
                delay(wait)
                val got = synchronized(waiting) { waiting.remove(chat) } ?: return@launch
                runCatching { consider(app0, Brain.Event(app, who, Brain.together(got.texts), group = false, message = true, at = System.currentTimeMillis(), key = got.key, canReply = got.canReply)) }
            }
        }
    }

    private suspend fun consider(ctx: Context, e: Brain.Event) {
        if (Brain.skip(e) != null) return
        synchronized(seen) {
            if (!seen.add((e.who + "|" + e.text).hashCode())) return
            if (seen.size > 80) seen.remove(seen.first())
        }
        val now = System.currentTimeMillis()
        val trust = BrainStore.trust
        // The user turned this person's offers down three times running: left alone for a month.
        if (trust.muted(Brain.fromKey(e.who), now)) return
        var t = Brain.quick(e) ?: think(ctx, e) ?: return
        if (t.kind != "none" && trust.muted(t.kind, now)) {
            // This kind of offer is switched off for now; only something urgent is still told.
            if (!t.urgent) return
            t = Brain.Thought(t.id, t.at, t.who, t.app, t.heard, "tell", "none", t.say, t.why, JSONObject(), true, t.key)
        }
        deliver(ctx, t)
    }

    /** One small AI question about [e]. Null when it may not be asked now, could not be, or the answer is "nothing". */
    private suspend fun think(ctx: Context, e: Brain.Event): Brain.Thought? {
        if (!Settings.hasKey) return null
        return one.withLock {
            var tries = 0
            while (true) {
                val now = System.currentTimeMillis()
                // The user's own task comes first: the Brain never spends the AI's minute while one is running.
                val busy = Agent.sessions.value.any { it.status == SessionStatus.RUNNING }
                val wait = Brain.mayThink(now, BrainStore.lastThinkAt, BrainStore.thinksToday(today()), busy)
                if (wait == 0L) break
                if (wait < 0 || ++tries > 6) return@withLock null
                delay(wait)
            }
            // Keep the phone awake for the few seconds the answer takes (messages also arrive with the screen off).
            val wake = runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "seyal:brain").apply { acquire(40_000) } }.getOrNull()
            try {
                val now = System.currentTimeMillis()
                BrainStore.thought(today(), now)
                val r = withTimeoutOrNull(30_000) {
                    Llm.chat(Brain.system(Settings.userName, Settings.language, now, Memory.text(), BrainStore.trust.summary()), listOf(Msg("user", listOf(Part.Text(Brain.asks(e))))), listOf(Brain.decl()), 400, forceTool = true)
                }
                r?.calls?.firstOrNull { it.name == "decide" }?.args?.let { Brain.read(it, e, System.currentTimeMillis()) }
            } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (x: Exception) { null } finally { runCatching { if (wake?.isHeld == true) wake.release() } }
        }
    }

    private suspend fun deliver(ctx: Context, t: Brain.Thought) {
        val now = System.currentTimeMillis()
        val silent = Brain.quiet(now) && !t.urgent
        if (t.level == "suggest" && BrainStore.trust.auto(t.kind)) {
            // "Do it and tell me": the user switched this on for this kind. Done first, told at once, with Undo.
            val (ok, result) = act(ctx, t, "yes")
            t.level = "did"; t.state = if (ok) "done" else "failed"; t.result = result; t.answeredAt = now
            BrainStore.add(t)
            Notify.brain(ctx, t, silent)
            return
        }
        if (t.level == "tell") t.state = "told"
        // A dozen offers a day at most; the rest wait quietly in the Brain tab.
        val show = t.urgent || BrainStore.mayOffer(today())
        BrainStore.add(t)
        if (show) Notify.brain(ctx, t, silent)
    }

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime().toString().take(16)
    private val quietCtx = ToolContext("brain", { emptyList() }, { _, _ -> "" })

    /** Carries out [t] as answered ([answer]: yes | later | reply:N). Returns whether it worked and one honest line about it. */
    private suspend fun act(ctx: Context, t: Brain.Thought, answer: String): Pair<Boolean, String> = runCatching {
        when {
            answer == "later" -> {
                val at = System.currentTimeMillis() + 30 * 60_000L
                t.args.put("timer", Scheduler.setReminder(ctx, at, "Call ${t.who}"))
                true to "I'll remind you at ${Times.format(at)} to call ${t.who}."
            }
            t.kind == "reminder" -> {
                val at = t.args.optLong("at")
                if (at < System.currentTimeMillis()) false to "That time has already passed, so no reminder was set."
                else { t.args.put("timer", Scheduler.setReminder(ctx, at, t.args.optString("title"))); true to "Reminder set for ${Times.format(at)}: ${t.args.optString("title")}" }
            }
            t.kind == "calendar" -> {
                val tool = Toolbox.get("calendar_add") ?: return@runCatching false to "The calendar cannot be reached on this phone."
                val a = JSONObject().put("title", t.args.optString("title")).put("start", iso(t.args.optLong("at"))).put("description", "From ${t.who}'s message (${t.app}): ${t.heard}")
                if (t.args.has("duration_min")) a.put("duration_min", t.args.optInt("duration_min"))
                if (t.args.has("place")) a.put("location", t.args.optString("place"))
                when (val r = tool.run(a, quietCtx)) {
                    is ToolResult.Done -> r.output.optBoolean("ok", true) to r.output.optString("say").ifBlank { r.line }
                    is ToolResult.Failed -> false to r.message
                }
            }
            t.kind == "reply" -> {
                val text = t.replies().getOrNull(answer.substringAfter("reply:", "0").toIntOrNull() ?: 0) ?: return@runCatching false to "That reply is no longer there."
                val to = SeyalNotificationListener.reply("", "", text, key = t.key, noGroups = true)
                t.args.put("sent", text)
                true to "Sent to $to: \"$text\""
            }
            t.kind == "call" -> {
                val found = (Toolbox.get("contacts_search")?.run(JSONObject().put("name", t.who), quietCtx) as? ToolResult.Done)?.output?.optJSONArray("matches")
                val phone = (0 until (found?.length() ?: 0)).map { found!!.getJSONObject(it) }.let { m -> m.firstOrNull { it.optString("name").equals(t.who, true) } ?: m.firstOrNull() }?.optString("phone").orEmpty()
                if (phone.isBlank()) false to "I could not find a number for ${t.who} in your contacts."
                else when (val r = Toolbox.get("call")?.run(JSONObject().put("phone", phone).put("name", t.who), quietCtx)) {
                    is ToolResult.Done -> true to r.line
                    is ToolResult.Failed -> false to r.message
                    else -> false to "Calling is not available on this phone."
                }
            }
            else -> false to "Nothing to do."
        }
    }.getOrElse { x -> false to (x.message ?: "It did not work.") }

    /**
     * The user answered thought [id]: yes | no | later | reply:N | undo. Also "auto_yes:KIND" / "auto_no:KIND"
     * (the answer to "shall I do these by myself from now on?"). [done] is called when everything is finished.
     */
    fun answer(ctx: Context, id: String, answer: String, done: () -> Unit = {}) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val trust = BrainStore.trust
                if (answer.startsWith("auto_")) {
                    val kind = answer.substringAfter(':')
                    val yes = answer.startsWith("auto_yes")
                    trust.setAuto(kind, yes, now); BrainStore.save()
                    Notify.cancel(app, "brainauto$kind")
                    Notify.line(app, if (yes) "From now on I'll set ${Brain.kindName(kind)} by myself and tell you each time. Every one has Undo, and the Brain tab switches this off." else "Fine. I'll keep asking first.")
                    return@launch
                }
                val t = BrainStore.get(id) ?: return@launch
                Notify.cancel(app, "brain$id")
                if (answer == "undo") {
                    if (t.level != "did" || t.state != "done") return@launch
                    t.args.optString("timer").takeIf { it.isNotBlank() }?.let { Scheduler.cancelTimer(app, "remind", it) }
                    t.state = "undone"; t.result = "Undone."; t.answeredAt = now
                    val f = trust.answer(t.kind, false, now)
                    BrainStore.save()
                    Notify.line(app, if (f == Brain.Follow.AUTO_OFF) "Undone. That is twice, so I'll go back to asking you before setting ${Brain.kindName(t.kind)}." else "Undone: \"${t.args.optString("title")}\" will not remind you.")
                    return@launch
                }
                if (t.state != "new") return@launch
                t.answeredAt = now
                if (answer == "no") {
                    t.state = "no"
                    val f = trust.answer(t.kind, false, now)
                    val g = trust.answer(Brain.fromKey(t.who), false, now, muteDays = 30)
                    BrainStore.save()
                    if (f == Brain.Follow.MUTED) Notify.line(app, "You said no to ${Brain.kindName(t.kind)} three times, so I'll stop offering them for two weeks. The Brain tab turns them back on.")
                    else if (g == Brain.Follow.MUTED) Notify.line(app, "I'll leave ${t.who}'s messages alone for a month. The Brain tab changes that.")
                    return@launch
                }
                val (ok, result) = act(app, t, answer)
                t.state = if (ok) "done" else "failed"; t.result = result
                // "In 30 min" is neither a yes nor a no to calling.
                val f = if (ok && answer != "later") { trust.answer(Brain.fromKey(t.who), true, now); trust.answer(t.kind, true, now) } else null
                BrainStore.save()
                Notify.line(app, if (ok) result else "Could not do it: $result")
                if (f == Brain.Follow.OFFER_AUTO) Notify.brainAuto(app, t.kind)
            } finally { runCatching { done() } }
        }
    }
}
