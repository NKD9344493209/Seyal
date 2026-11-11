package com.seyal.app.agent

import android.content.Context
import android.content.Intent
import com.seyal.app.ai.Llm
import com.seyal.app.ai.LlmException
import com.seyal.app.data.CallState
import com.seyal.app.data.CallStatus
import com.seyal.app.data.Level
import com.seyal.app.data.Msg
import com.seyal.app.data.Part
import com.seyal.app.data.Session
import com.seyal.app.data.SessionInfo
import com.seyal.app.data.SessionStatus
import com.seyal.app.data.Settings
import com.seyal.app.data.Store
import com.seyal.app.data.newId
import com.seyal.app.device.AgentService
import com.seyal.app.device.Notify
import com.seyal.app.device.ScreenDriver
import com.seyal.app.device.Speech
import com.seyal.app.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * The agent loop — Seyal's equivalent of Claude Code's core.
 *
 *   user request → AI → tool calls → results → AI → … → one final answer
 *
 * The AI only proposes calls. This object decides whether each runs (levels, allow-lists, pause),
 * runs independent calls at the same time, waits for the user when needed, keeps the transcript,
 * and publishes fresh copies to the screens on every change.
 */
object Agent {
    lateinit var app: Context
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    /** Sessions in memory (all open ones, plus recent ones the user looked at). The engine changes these in place. */
    private val live = LinkedHashMap<String, Session>()
    private var index: MutableList<JSONObject> = mutableListOf()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    /** Copies of the loaded sessions, newest first. */
    val sessions: StateFlow<List<Session>> = _sessions
    private val _index = MutableStateFlow<List<SessionInfo>>(emptyList())
    /** Every session, newest first: id, title, status, result (no transcript). */
    val history: StateFlow<List<SessionInfo>> = _index
    private val _current = MutableStateFlow<String?>(null)
    /** The session the Chat tab shows. */
    val currentId: StateFlow<String?> = _current
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused
    private val _tick = MutableStateFlow(0L)
    /** Bumps on every change, for screens that want a cheap signal. */
    val tick: StateFlow<Long> = _tick

    private val jobs = ConcurrentHashMap<String, Job>()
    private val waits = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val saveJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var pauseGate = CompletableDeferred<Unit>().apply { complete(Unit) }

    fun init(context: Context) {
        app = context.applicationContext
        index = Store.loadIndex()
        _paused.value = Settings.paused
        // The status pill shown over other apps: Stop stops everything, a tap on the text opens Seyal.
        ScreenDriver.onStop = { cancelAll() }
        ScreenDriver.onOpen = { runCatching { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } }
        if (Settings.paused) pauseGate = CompletableDeferred()
        // Sessions that were running or waiting when the app died are closed (loading does that) and shown as such.
        var changed = false
        index.filter { it.optString("status") == SessionStatus.WAITING.name || it.optString("status") == SessionStatus.RUNNING.name }.forEachIndexed { i, e ->
            Store.loadSession(e.optString("id"))?.let { s ->
                if (i < 5) live[s.id] = s
                e.put("status", s.status.name); s.result?.let { r -> e.put("result", r) }
                Store.saveSession(s); changed = true
            }
        }
        if (changed) Store.saveIndex(index)
        // Bring the latest conversations back so the Chat tab picks up where the user left off.
        index.filter { it.optString("source") == "user" }.take(3).forEach { e ->
            val id = e.optString("id")
            if (!live.containsKey(id)) Store.loadSession(id)?.let { live[id] = it }
        }
        _current.value = index.firstOrNull { it.optString("source") == "user" }?.optString("id")
        Skills.init()
        publish()
    }

    // ------------------------------------------------------------ publishing

    private fun publish() {
        _sessions.value = live.values.sortedByDescending { it.createdAt }.map { it.snapshot() }
        _index.value = index.sortedByDescending { it.optLong("created_at") }.map { SessionInfo.fromJson(it) }
        _tick.value = _tick.value + 1
    }

    /** Call under [lock]: records a change to [s] and schedules a disk save. */
    private fun touch(s: Session) {
        s.updatedAt = System.currentTimeMillis()
        // An open task waits for the user whenever any of its steps does (even while another step runs).
        if (s.status.isOpen) s.status = if (s.calls.values.any { it.status.isWaiting }) SessionStatus.WAITING else SessionStatus.RUNNING
        val e = index.firstOrNull { it.optString("id") == s.id }
        if (e != null) { e.put("status", s.status.name); e.put("title", s.title); e.put("updated_at", s.updatedAt); e.put("steps", s.steps); s.result?.let { e.put("result", it) } }
        publish()
        saveJobs[s.id]?.cancel()
        saveJobs[s.id] = scope.launch { delay(400); save(s) }
    }

    private suspend fun save(s: Session) = lock.withLock {
        Store.saveSession(s)
        Store.saveIndex(index)
    }

    fun session(id: String): Session? = live[id]

    /** Loads a session for the Chat tab (from disk if needed). */
    fun open(id: String) = scope.launch {
        lock.withLock {
            if (!live.containsKey(id)) Store.loadSession(id)?.let { live[id] = it; trimLive() }
            _current.value = id
            publish()
        }
    }

    fun newChat() { _current.value = null; publish() }

    private fun trimLive() {
        val closed = live.values.filter { !it.status.isOpen }.sortedByDescending { it.updatedAt }
        closed.drop(12).forEach { if (it.id != _current.value) live.remove(it.id) }
    }

    // ------------------------------------------------------------ requests

    /** Starts a new session for a user request and runs the loop. */
    fun start(command: String, image: Part.Image? = null, source: String = "user"): Session {
        val s = Session(newId(), System.currentTimeMillis(), source)
        s.title = command.trim().lines().first().take(60)
        val parts = mutableListOf<Part>(Part.Text(command.trim()))
        image?.let { parts += it }
        s.messages += Msg("user", parts)
        scope.launch {
            lock.withLock {
                live[s.id] = s
                index.add(0, s.toIndexJson())
                if (source == "user") _current.value = s.id
                touch(s)
                run(s)
            }
        }
        return s
    }

    /**
     * A follow-up in an existing session. If the agent is waiting on a question, this is the answer.
     * If a tool round is in flight, it is queued until the results are in (the transcript must stay
     * assistant-calls → tool-results). Otherwise it is appended and the loop runs again.
     */
    fun say(sessionId: String, text: String, image: Part.Image? = null) = scope.launch {
        val msg = text.trim()
        val s = lock.withLock {
            val s = live[sessionId] ?: Store.loadSession(sessionId)?.also { live[sessionId] = it } ?: return@launch
            val parts = mutableListOf<Part>(Part.Text(msg))
            image?.let { parts += it }
            val question = s.calls.values.firstOrNull { it.status == CallStatus.WAITING_USER && waits.containsKey(it.callId) }
            when {
                // The answer to the agent's question.
                question != null -> { waits[question.callId]?.complete(msg); return@launch }
                // The agent is mid-step: it picks this up as soon as the step's results are in (or when it finishes).
                s.status.isOpen && jobs[s.id]?.isActive == true -> { s.queued += Msg("user", parts); touch(s); return@launch }
                // A finished, stopped or failed conversation carries on.
                else -> { s.messages += Msg("user", parts); s.status = SessionStatus.RUNNING; touch(s); run(s) }
            }
            s
        }
    }

    private fun run(s: Session) {
        val job = scope.launch { loop(s) }
        jobs[s.id] = job
        job.invokeOnCompletion { jobs.remove(s.id, job) }
    }

    /** Runs a sub-goal as a child session and waits for it (used by delegate). */
    suspend fun runChild(goal: String, parentId: String): Session {
        val s = Session(newId(), System.currentTimeMillis(), "child:$parentId")
        s.parentId = parentId
        s.title = goal.lines().first().take(60)
        s.messages += Msg("user", listOf(Part.Text(goal)))
        lock.withLock { live[s.id] = s; index.add(0, s.toIndexJson()); touch(s) }
        val job = scope.launch { loop(s, allowDelegate = false) }
        jobs[s.id] = job
        job.invokeOnCompletion { jobs.remove(s.id, job) }
        try { job.join() } catch (e: kotlinx.coroutines.CancellationException) { cancel(s.id); throw e }
        return s
    }

    // ------------------------------------------------------------ the loop

    /** Tools that use the one screen: run one at a time, in the order the AI gave them. */
    private val screenish = setOf("open_app", "open_url", "send_whatsapp", "notification_open")

    private fun usesScreen(name: String): Boolean = name in screenish || Toolbox.get(name)?.group == "screen"

    /** [softFail]: after a skill's recorded steps already worked, an AI hiccup still counts as done. */
    private suspend fun loop(s: Session, allowDelegate: Boolean = true, softFail: Boolean = false) {
        val maxSteps = Settings.maxSteps
        var stepsThisRun = 0
        // The same call giving the same result again and again means the agent is stuck.
        val lastOutput = HashMap<String, Int>()
        val sameCount = HashMap<String, Int>()
        try {
            while (coroutineContext.isActive) {
                pauseGate.await()
                // Tools can appear mid-task (the user turns screen control on), so look each step.
                val tools = Toolbox.available().filter { allowDelegate || it.name != "delegate" }
                val byName = tools.associateBy { it.name }
                val decls = tools.map { it.decl() }
                if (stepsThisRun >= maxSteps) {
                    finish(s, SessionStatus.FAILED, "I stopped after $maxSteps steps. Tell me how to continue, or try a smaller task.")
                    return
                }
                val go = lock.withLock {
                    if (s.status == SessionStatus.CANCELLED) false
                    else { if (s.status != SessionStatus.RUNNING) { s.status = SessionStatus.RUNNING; touch(s) }; true }
                }
                if (!go) return

                val snapshot = lock.withLock { repair(s); forModel(s) }
                report(if (stepsThisRun == 0) "Thinking…" else "Thinking · step ${stepsThisRun + 1}")
                // From here on, element ids mean the screen the AI is about to see.
                ScreenDriver.freezeIds(s.id)
                val t0 = System.currentTimeMillis()
                val resp = try {
                    Llm.chat(Prompts.system(s, tools), snapshot, decls, 2048)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (softFail && stepsThisRun == 0) { finish(s, SessionStatus.DONE, "Done: ${s.title}"); return }
                    val why = if (e is LlmException) e.message ?: "The AI did not answer." else "AI error: ${e.message?.take(160) ?: e.javaClass.simpleName}"
                    // If the work itself got done, say what was done rather than just "failed".
                    val (status, text) = lock.withLock { afterAiFailure(s, stepsThisRun, why) }
                    finish(s, status, text)
                    return
                }
                val parts = mutableListOf<Part>()
                if (resp.text.isNotBlank()) parts += Part.Text(resp.text)
                parts += resp.calls
                lock.withLock {
                    s.llmCalls++; s.tokens += resp.tokens; s.llmMs += System.currentTimeMillis() - t0
                    if (parts.isNotEmpty()) s.messages += Msg("assistant", parts)
                    resp.calls.forEach { c -> s.state(c.id).status = CallStatus.PENDING }
                    touch(s)
                }
                if (resp.calls.isEmpty()) {
                    finish(s, SessionStatus.DONE, resp.text.ifBlank { "Done." })
                    return
                }
                s.steps++; stepsThisRun++
                // Independent calls in one reply run at the same time. Anything touching the screen runs one by one, in order.
                val skipped = HashSet<String>()
                suspend fun one(call: Part.Call): Part.Result {
                    val sig = call.name + call.args.toString()
                    if ((sameCount[sig] ?: 0) >= 2 && call.name !in setOf("wait", "ask_user")) { skipped += call.id; return stuck(s, call) }
                    return runCall(s, call, byName[call.name])
                }
                val results = if (resp.calls.any { usesScreen(it.name) }) resp.calls.map { one(it) }
                else coroutineScope { resp.calls.map { call -> async { one(call) } }.awaitAll() }
                resp.calls.forEachIndexed { i, call ->
                    if (call.id in skipped) return@forEachIndexed
                    val sig = call.name + call.args.toString()
                    val h = results[i].output.toString().hashCode()
                    if (lastOutput[sig] == h) sameCount[sig] = (sameCount[sig] ?: 0) + 1 else { lastOutput[sig] = h; sameCount[sig] = 0 }
                }
                lock.withLock {
                    if (s.status == SessionStatus.CANCELLED) return
                    s.messages += Msg("tool", results)
                    if (s.queued.isNotEmpty()) { s.messages += s.queued; s.queued.clear() }
                    touch(s)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // cancelled by the user
        } catch (e: Throwable) {
            finish(s, SessionStatus.FAILED, "Something broke: ${e.message?.take(160) ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun finish(s: Session, status: SessionStatus, text: String) {
        lock.withLock {
            if (s.status == SessionStatus.CANCELLED) return
            s.status = status
            s.result = plain(text).lines().map { it.trim().trimStart('-', '•', ' ') }.firstOrNull { it.isNotBlank() }?.take(140) ?: "Done"
            s.calls.values.forEach { if (!it.status.isFinished) { it.status = CallStatus.CANCELLED } }
            if (s.queued.isNotEmpty()) { s.messages += s.queued; s.queued.clear(); s.status = SessionStatus.RUNNING; run(s) }
            if (!s.status.isOpen) ScreenDriver.release(s.id)
            touch(s)
            saveJobs[s.id]?.cancel()
            trimLive()
        }
        save(s)
        if (s.status.isOpen) return
        idleCheck(if (s.parentId == null) (if (status == SessionStatus.DONE) "Done · " else "Stopped · ") + (s.result ?: "") else null)
        if (s.parentId == null) {
            if (!Settings.quiet || status != SessionStatus.DONE) Notify.line(app, "${s.title}: ${s.result}", s.id)
            if (Settings.speak && status == SessionStatus.DONE) Speech.say(app, text)
        }
    }

    /**
     * Every assistant tool call must be followed by a tool result (the APIs reject anything else).
     * After an app restart, or a cancelled round, some are missing: fill them in as interrupted.
     */
    private fun repair(s: Session) {
        var i = 0
        while (i < s.messages.size) {
            val m = s.messages[i]
            if (m.role == "assistant" && m.calls.isNotEmpty()) {
                val next = s.messages.getOrNull(i + 1)
                val have = next?.takeIf { it.role == "tool" }?.results?.map { it.callId }?.toSet() ?: emptySet()
                val missing = m.calls.filter { it.id !in have }
                if (missing.isNotEmpty()) {
                    val fill = missing.map { c -> Part.Result(c.id, c.name, JSONObject().put("error", "interrupted: the app was closed or the task was stopped before this finished"), true) }
                    if (next?.role == "tool") { s.messages[i + 1] = Msg("tool", next.results + fill, next.at) }
                    else { s.messages.add(i + 1, Msg("tool", fill, m.at)) }
                    missing.forEach { c -> s.state(c.id).let { st -> if (!st.status.isFinished) { st.status = CallStatus.FAILED; st.error = "interrupted" } } }
                }
            }
            i++
        }
        // A session can't start with the assistant.
        if (s.messages.firstOrNull()?.role != "user") s.messages.add(0, Msg("user", listOf(Part.Text(s.title.ifBlank { "Continue." }))))
    }

    /**
     * The transcript as the AI should see it. The saved transcript keeps every step for the user;
     * the AI gets a lean copy: only the latest screens and screenshots (older ones are stale anyway),
     * and very old tool outputs shrunk to their one-liners once the conversation gets long.
     */
    private fun forModel(s: Session): List<Msg> {
        // Screenshots are big even on disk: keep only the last two rounds' images in the saved transcript too.
        val toolIdx = s.messages.indices.filter { s.messages[it].role == "tool" }
        for (i in toolIdx.dropLast(2)) {
            val m = s.messages[i]
            if (m.results.none { it.image != null }) continue
            s.messages[i] = Msg("tool", m.results.map { r -> if (r.image == null) r else Part.Result(r.callId, r.name, r.output, r.isError, null) }, m.at)
        }
        val msgs = s.messages.toMutableList()
        // Only the newest screen is current: strip every screen listing older than it.
        fun hasScreen(m: Msg) = m.results.any { r -> r.output.has("screen") || r.output.has("elements") }
        val newestScreen = toolIdx.lastOrNull { hasScreen(s.messages[it]) } ?: -1
        for (i in toolIdx) {
            if (i >= newestScreen) break
            val m = msgs[i]
            if (!hasScreen(m)) continue
            msgs[i] = Msg("tool", m.results.map { r ->
                if (!r.output.has("screen") && !r.output.has("elements")) r
                else Part.Result(r.callId, r.name, JSONObject(r.output.toString()).apply { remove("screen"); remove("elements"); put("screen_omitted", "older screen removed; a later result has a newer one (or call screen_read)") }, r.isError, null)
            }, m.at)
        }
        fun size(m: Msg) = m.parts.sumOf { p -> if (p is Part.Result) p.output.toString().length + (p.image?.b64?.length ?: 0) / 8 else if (p is Part.Text) p.text.length else 0 }
        var total = msgs.sumOf { size(it) }
        if (total < 70_000) return msgs
        for (i in toolIdx.dropLast(4)) {
            if (total < 45_000) break
            val m = msgs[i]
            val slim = Msg("tool", m.results.map { r ->
                val line = s.calls[r.callId]?.line ?: ""
                Part.Result(r.callId, r.name, JSONObject().put("earlier_result", line.ifBlank { if (r.isError) "failed" else "done" }), r.isError)
            }, m.at)
            total -= size(m) - size(slim)
            msgs[i] = slim
        }
        return msgs
    }

    /** What Seyal is doing right now, for the status pill over other apps and the "working" notification. */
    /** Grows with every status report, so an older "all done" never clears a newer task's status. */
    private val activity = java.util.concurrent.atomic.AtomicLong(0)

    /** Markdown marks out, for one-line places (pill, notifications, lists). */
    private fun plain(t: String) = t.replace("**", "").replace("`", "").replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")

    /**
     * The AI stopped answering mid-task. If this turn's actions all worked and the last one changed
     * something (a message sent, an alarm set…), the task is effectively done: report what was done.
     * Otherwise it failed, and the user hears both what got done and why it stopped.
     */
    private fun afterAiFailure(s: Session, steps: Int, why: String): Pair<SessionStatus, String> {
        if (steps == 0) return SessionStatus.FAILED to why
        val lastUser = s.messages.indexOfLast { it.role == "user" }
        val calls = s.messages.drop(lastUser + 1).filter { it.role == "assistant" }.flatMap { it.calls }
        val done = calls.mapNotNull { c -> s.calls[c.id]?.takeIf { it.status == CallStatus.DONE }?.line?.let { c to it } }
        if (done.isEmpty()) return SessionStatus.FAILED to why
        val did = done.map { it.second }.distinct().takeLast(4).joinToString("; ")
        val lastRound = s.messages.lastOrNull { it.role == "assistant" && it.calls.isNotEmpty() }?.calls.orEmpty()
        val lastOk = lastRound.isNotEmpty() && lastRound.all { s.calls[it.id]?.status == CallStatus.DONE }
        val acted = lastRound.any { Toolbox.get(it.name)?.sideEffect == true }
        return if (lastOk && acted) SessionStatus.DONE to "Done: $did."
        else SessionStatus.FAILED to "Got this far: $did. Then: $why Send a message to continue."
    }

    private fun report(text: String) {
        val g = activity.incrementAndGet()
        ScreenDriver.status(plain(text))
        AgentService.working(app, text, g)
    }

    /** When nothing is open any more: say the result on the pill for a moment, then clear up. */
    private fun idleCheck(lastWords: String?) {
        scope.launch {
            val g = lock.withLock { if (live.values.any { it.status.isOpen }) return@launch; activity.get() }
            AgentService.idle(g)
            if (lastWords != null) { ScreenDriver.status(plain(lastWords).take(70)); delay(4500) }
            lock.withLock { if (activity.get() == g && live.values.none { it.status.isOpen }) ScreenDriver.status(null) }
        }
    }

    /** The agent repeated the same call with the same result three times: stop it wasting steps. */
    private suspend fun stuck(s: Session, call: Part.Call): Part.Result = lock.withLock {
        val msg = "You already did exactly this several times and got the same result. Do NOT repeat it. Try a different way (other element, scroll, screenshot, another tool) or finish and tell the user what is blocking."
        s.state(call.id).apply { status = CallStatus.FAILED; error = "Skipped: repeated with no progress"; startedAt = System.currentTimeMillis(); endedAt = startedAt }
        touch(s)
        Part.Result(call.id, call.name, JSONObject().put("error", msg), true)
    }

    // ------------------------------------------------------------ one tool call

    private suspend fun runCall(s: Session, call: Part.Call, tool: Tool?): Part.Result {
        val state = lock.withLock { s.state(call.id).also { it.startedAt = System.currentTimeMillis() } }
        fun fail(msg: String, status: CallStatus = CallStatus.FAILED): Part.Result {
            state.status = status; state.error = msg; state.endedAt = System.currentTimeMillis()
            return Part.Result(call.id, call.name, JSONObject().put("error", msg), isError = true)
        }
        if (tool == null) return lock.withLock { fail("Unknown tool ${call.name}").also { touch(s) } }
        if (lock.withLock { s.status == SessionStatus.CANCELLED }) return lock.withLock { fail("Stopped by the user", CallStatus.CANCELLED) }

        // 1. May it run?
        val target = runCatching { tool.target(call.args) }.getOrNull()
        var level = Settings.level(tool.name, tool.defaultLevel)
        if (level == Level.ASK && target != null && Settings.isAllowed(tool.name, target)) level = Level.AUTO
        // Safety net: after 40 unattended sends in a day, ask again.
        if (level == Level.AUTO && tool.group == "comms" && tool.sideEffect && Settings.autoSendToday >= 40) level = Level.ASK
        // After the user said No to something in this conversation, screen actions ask first too
        // (so a declined message can't be sent another way through the app).
        if (level == Level.AUTO && tool.group == "screen" && tool.sideEffect && lock.withLock { s.calls.values.any { it.status == CallStatus.DENIED } }) level = Level.ASK
        if (level == Level.OFF) {
            val r = lock.withLock { fail("\"${tool.name}\" is turned off in Control → Permissions. Tell the user.", CallStatus.DENIED).also { touch(s) } }
            return r
        }
        pauseGate.await()
        if (level == Level.ASK) {
            val preview = runCatching { tool.preview(call.args) }.getOrDefault("").ifBlank { Prompts.friendly(call) }
            val wait = CompletableDeferred<String>()
            waits[call.id] = wait
            lock.withLock {
                state.status = CallStatus.WAITING_APPROVAL; state.preview = preview
                s.status = SessionStatus.WAITING; touch(s)
            }
            Notify.approval(app, s, call.id, Prompts.friendly(call), preview)
            report("Needs your OK · ${Prompts.friendly(call)}")
            val decision = try { withTimeoutOrNull(30 * 60_000L) { wait.await() } ?: "deny" } finally { waits.remove(call.id); Notify.cancelApproval(app, s.id, call.id) }
            if (decision == "allow_always") {
                if (target != null) Settings.allow(tool.name, target) else Settings.setLevel(tool.name, Level.AUTO)
            }
            if (decision.startsWith("deny")) {
                val r = lock.withLock { fail("The user said no to: $preview. Do not do it in any other way either; ask what they want instead, or finish.", CallStatus.DENIED).also { if (s.status != SessionStatus.CANCELLED) s.status = SessionStatus.RUNNING; touch(s) } }
                return r
            }
        }

        // 2. Run it.
        val stopped = lock.withLock {
            if (s.status == SessionStatus.CANCELLED) true
            else { state.status = CallStatus.RUNNING; s.status = SessionStatus.RUNNING; touch(s); false }
        }
        if (stopped) return lock.withLock { fail("Stopped by the user", CallStatus.CANCELLED) }
        if (usesScreen(tool.name) && ScreenDriver.available && !ScreenDriver.claim(s.id)) {
            return lock.withLock { fail("Another Seyal task is using the screen right now. Wait for it (or tell the user), then try again.").also { touch(s) } }
        }
        report(Prompts.friendly(call))
        val ctx = ToolContext(
            sessionId = s.id,
            delegate = { goals -> delegate(s, state, goals) },
            askUser = { q, opts -> askUser(s, state, q, opts) },
        )
        var result: ToolResult = ToolResult.Failed("Not run")
        for (attempt in 0 until 2) {
            result = try {
                if (tool.group == "agent") tool.run(call.args, ctx)
                else withTimeoutOrNull(150_000L) { tool.run(call.args, ctx) } ?: ToolResult.Failed("${tool.name} took too long")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                ToolResult.Failed(e.message?.take(200) ?: e.javaClass.simpleName, retryable = true)
            }
            val f = result as? ToolResult.Failed ?: break
            if (!f.retryable || tool.sideEffect || attempt == 1) break
            delay(1200)
        }

        // 3. Record it.
        return lock.withLock {
            state.endedAt = System.currentTimeMillis()
            val out = when (val r = result) {
                is ToolResult.Done -> {
                    // A screen action that didn't work still hands the AI the screen (not an error for it), but shows as failed.
                    if (r.output.optBoolean("ok", true)) { state.status = CallStatus.DONE; state.line = r.line; state.error = null }
                    else { state.status = CallStatus.FAILED; state.error = r.output.optString("error").ifBlank { r.line }; state.line = null }
                    if (tool.sideEffect && tool.group == "comms") Settings.autoSendToday = Settings.autoSendToday + 1
                    Part.Result(call.id, call.name, cap(r.output), false, r.image)
                }
                is ToolResult.Failed -> {
                    state.status = CallStatus.FAILED; state.error = r.message; state.line = null
                    Part.Result(call.id, call.name, JSONObject().put("error", r.message), true)
                }
            }
            touch(s)
            out
        }
    }

    /** Tool outputs larger than this are cut, so one web page can't flood the conversation. Short facts are kept. */
    private fun cap(o: JSONObject): JSONObject {
        if (o.toString().length <= 16_000) return o
        val c = JSONObject(o.toString())
        c.optJSONObject("screen")?.let { sc -> val e = sc.optString("elements"); if (e.length > 8000) sc.put("elements", e.take(8000) + "\n… (more not shown: scroll)") }
        val keys = c.keys().asSequence().toList()
        for (k in keys) {
            if (c.toString().length <= 16_000) break
            when (val v = c.opt(k)) {
                is String -> if (v.length > 3000) c.put(k, v.take(3000) + "…")
                is org.json.JSONArray -> { while (v.length() > 1 && v.toString().length > 6000) v.remove(v.length() - 1); c.put(k, v) }
                is JSONObject -> if (k != "screen" && v.toString().length > 6000) c.put(k, v.toString().take(6000) + "…")
            }
        }
        if (c.toString().length > 16_000) return JSONObject().put("truncated", true).put("text", c.toString().take(16_000))
        return c.put("truncated", true)
    }

    private suspend fun askUser(s: Session, state: CallState, question: String, options: List<String>): String {
        val wait = CompletableDeferred<String>()
        waits[state.callId] = wait
        lock.withLock {
            state.status = CallStatus.WAITING_USER; state.preview = question; state.options = options
            s.status = SessionStatus.WAITING; touch(s)
        }
        Notify.question(app, s, question)
        report("Question for you · ${question.take(50)}")
        val answer = try { withTimeoutOrNull(60 * 60_000L) { wait.await() } ?: "(no answer)" } finally { waits.remove(state.callId); Notify.cancelQuestion(app, s.id) }
        lock.withLock { s.status = SessionStatus.RUNNING; touch(s) }
        return answer
    }

    private suspend fun delegate(s: Session, state: CallState, goals: List<String>): List<JSONObject> = coroutineScope {
        val children = goals.take(5).map { g -> async { runChild(g, s.id) } }
        lock.withLock { state.children = emptyList(); touch(s) }
        val done = children.awaitAll()
        lock.withLock { state.children = done.map { it.id }; touch(s) }
        done.map { c -> JSONObject().put("goal", c.title).put("status", c.status.name.lowercase()).put("result", c.result ?: "").put("session_id", c.id) }
    }

    // ------------------------------------------------------------ user actions

    /** "allow" | "allow_always" | "deny" for a call waiting for approval. */
    fun decide(callId: String, decision: String) { waits[callId]?.complete(decision) }

    /** Allows or denies every waiting call in a session. */
    fun decideAll(sessionId: String, allow: Boolean) = scope.launch {
        val ids = lock.withLock { live[sessionId]?.calls?.values?.filter { it.status == CallStatus.WAITING_APPROVAL }?.map { it.callId } ?: emptyList() }
        ids.forEach { waits[it]?.complete(if (allow) "allow" else "deny") }
    }

    /** The answer to an ask_user question. */
    fun answer(callId: String, text: String) { waits[callId]?.complete(text) }

    fun cancel(sessionId: String) = scope.launch {
        lock.withLock {
            val s = live[sessionId] ?: return@launch
            if (!s.status.isOpen) return@launch
            s.status = SessionStatus.CANCELLED; s.result = "Stopped"
            s.calls.values.forEach { if (!it.status.isFinished) it.status = CallStatus.CANCELLED }
            touch(s)
        }
        val callIds = lock.withLock { live[sessionId]?.calls?.keys?.toList() ?: emptyList() }
        callIds.forEach { waits[it]?.complete("deny") }
        jobs[sessionId]?.cancel()
        ScreenDriver.release(sessionId)
        lock.withLock { live[sessionId]?.let { Store.saveSession(it) } }
        idleCheck(null)
    }

    /** Stops every running task (the Stop button on the pill and on the "working" notification). */
    fun cancelAll() = scope.launch {
        val ids = lock.withLock { live.values.filter { it.status.isOpen }.map { it.id } }
        ids.forEach { cancel(it) }
    }

    fun delete(sessionId: String) = scope.launch {
        lock.withLock { live[sessionId]?.let { if (it.status.isOpen) it.status = SessionStatus.CANCELLED } }
        jobs[sessionId]?.cancel()
        saveJobs.remove(sessionId)?.cancel()
        lock.withLock {
            live.remove(sessionId)
            index.removeAll { it.optString("id") == sessionId }
            Store.deleteSession(sessionId)
            Store.saveIndex(index)
            if (_current.value == sessionId) _current.value = null
            publish()
        }
    }

    /** Pause all: nothing new runs until resumed. Running steps finish; waiting ones wait. */
    fun setPaused(paused: Boolean) {
        Settings.paused = paused
        _paused.value = paused
        if (paused) { if (pauseGate.isCompleted) pauseGate = CompletableDeferred() }
        else pauseGate.complete(Unit)
        scope.launch { lock.withLock { live.values.filter { it.status.isOpen }.forEach { touch(it) } } }
        if (paused) Scheduler.cancelAll(app) else Scheduler.rescheduleAll(app)
    }

    fun wipeAll() = scope.launch {
        lock.withLock { live.values.forEach { if (it.status.isOpen) it.status = SessionStatus.CANCELLED } }
        jobs.values.forEach { it.cancel() }
        saveJobs.values.forEach { it.cancel() }; saveJobs.clear()
        lock.withLock {
            live.clear(); index.clear()
            Skills.wipe()
            Store.wipe()
            Memory.set("")
            _current.value = null
            publish()
        }
    }

    /**
     * Runs a skill as its own task: the recorded steps first (no AI), then one AI turn to report the
     * result, or the agent takes over with the goal if a step failed. Stop works at every point.
     */
    internal fun runSkill(s: Session, steps: List<Part.Call>, goal: String): Job {
        val job = scope.launch {
            lock.withLock { live[s.id] = s; index.add(0, s.toIndexJson()); touch(s) }
            if (steps.isNotEmpty()) {
                val ok = replay(s, steps)
                val go = lock.withLock {
                    if (s.status == SessionStatus.CANCELLED) false
                    else {
                        s.messages += Msg("user", listOf(Part.Text(
                            if (ok) "The recorded steps above ran. Tell me the result in one or two lines. Call tools only if the goal is clearly not done yet."
                            else "A recorded step failed (see above). Finish the goal yourself: $goal",
                        )))
                        touch(s); true
                    }
                }
                if (!go) return@launch
                if (ok && !Settings.hasKey) { finish(s, SessionStatus.DONE, "Done: ${s.title}"); return@launch }
                loop(s, softFail = ok)
            } else loop(s)
        }
        jobs[s.id] = job
        job.invokeOnCompletion { jobs.remove(s.id, job) }
        return job
    }

    /** Runs fixed steps without the AI. False if a step failed or the task was stopped. */
    private suspend fun replay(s: Session, steps: List<Part.Call>): Boolean {
        val tools = Toolbox.available().associateBy { it.name }
        for (step in steps) {
            val call = Part.Call(newId(), step.name, JSONObject(step.args.toString()))
            val go = lock.withLock {
                if (s.status == SessionStatus.CANCELLED) false
                else { s.messages += Msg("assistant", listOf(call)); s.state(call.id); touch(s); true }
            }
            if (!go) return false
            ScreenDriver.freezeIds(s.id)
            val r = runCall(s, call, tools[step.name])
            lock.withLock { s.messages += Msg("tool", listOf(r)); touch(s) }
            if (r.isError || r.output.optBoolean("ok", true) == false) return false
        }
        return true
    }
}
