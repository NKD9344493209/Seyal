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
import com.seyal.app.util.Times
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
    /** Marks a message the user sent while a task was running, so the AI reads it as their latest word. Not shown in the chat. */
    const val MIDTASK = "[New message, sent while you were working] "
    /** Marks a reminder Seyal gives itself before accepting "done" (see [Checks]). It is not from the user and is not shown in the chat. */
    const val CHECK = "[Seyal's own check before finishing, not a message from the user] "

    /** Whether a message is something the user really said (not Seyal's own check). */
    internal fun fromUser(m: Msg): Boolean = m.role == "user" && !m.text.startsWith(CHECK)

    /** The user's latest words in [s]. */
    internal fun lastSaid(s: Session): String = s.messages.lastOrNull { fromUser(it) }?.text?.removePrefix(MIDTASK).orEmpty()

    /** Everything the user said in [s]: their messages and their answers to questions. */
    internal fun userWords(s: Session): List<String> =
        s.messages.filter { fromUser(it) }.map { it.text.removePrefix(MIDTASK) } +
            s.messages.filter { it.role == "tool" }.flatMap { it.results }.filter { it.name == "ask_user" }.map { it.output.optString("answer") }.filter { it.isNotBlank() }

    /** What the user said for the request in hand only: their latest message and their answers since (never an older request's "6 tickets"). */
    internal fun requestWords(s: Session): List<String> {
        val from = s.messages.indexOfLast { fromUser(it) }
        if (from < 0) return emptyList()
        return listOf(s.messages[from].text.removePrefix(MIDTASK)) +
            s.messages.drop(from + 1).filter { it.role == "tool" }.flatMap { it.results }.filter { it.name == "ask_user" }.map { it.output.optString("answer") }.filter { it.isNotBlank() }
    }

    /** What was done since the user's latest message, for [Checks]. */
    internal fun didSince(s: Session): List<Did> {
        val from = s.messages.indexOfLast { fromUser(it) }
        val results = s.messages.drop(from + 1).filter { it.role == "tool" }.flatMap { it.results }.associateBy { it.callId }
        return s.messages.drop(from + 1).filter { it.role == "assistant" }.flatMap { it.calls }.map { c ->
            val st = s.calls[c.id]
            Did(c.name, st?.status == CallStatus.DONE, results[c.id]?.output, st?.status == CallStatus.DENIED)
        }
    }

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
        Lessons.init(context.filesDir)
        Routines.init(context.filesDir)
        Inbox.init(context.filesDir); Watchers.init(context.filesDir)
        Inbox.onChange = { runCatching { Scheduler.syncBackground(app) } }
        Watchers.onChange = { runCatching { Scheduler.syncBackground(app) } }
        runCatching { Scheduler.syncBackground(context) }
        // Which apps are on the phone (by the name on their icon), looked up at most once a minute.
        var labels: List<String> = emptyList(); var labelsAt = 0L
        Preflight.appLabels = {
            val now = System.currentTimeMillis()
            if (now - labelsAt > 60_000 || labels.isEmpty()) {
                labels = runCatching {
                    val pm = app.packageManager
                    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.loadLabel(pm).toString() }.distinct()
                }.getOrDefault(labels)
                labelsAt = now
            }
            labels
        }
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

    /** Loads a session (from disk if needed). [asChat]: it becomes the chat on the Chat tab (user chats only). */
    fun open(id: String, asChat: Boolean = true) = scope.launch {
        lock.withLock {
            if (!live.containsKey(id)) Store.loadSession(id)?.let { live[id] = it; trimLive() }
            if (asChat && live[id]?.source == "user") _current.value = id
            publish()
        }
    }

    fun newChat() { _current.value = null; publish() }

    /** Gives a chat the name the user chose (kept in the list and on disk). */
    fun rename(sessionId: String, title: String) = scope.launch {
        val t = title.trim().replace('\n', ' ').take(80)
        if (t.isBlank()) return@launch
        lock.withLock {
            val s = live[sessionId] ?: Store.loadSession(sessionId)
            index.firstOrNull { it.optString("id") == sessionId }?.put("title", t)
            if (s != null) { s.title = t; Store.saveSession(s) }
            Store.saveIndex(index)
            publish()
        }
    }

    /**
     * The whole task as plain text: each message, each step with its arguments, status and result
     * (screens and long outputs shortened). "Share log" sends this so a problem can be seen exactly.
     */
    fun debugLog(s: Session): String = buildString {
        val results = HashMap<String, Part.Result>()
        s.messages.filter { it.role == "tool" }.forEach { m -> m.results.forEach { results[it.callId] = it } }
        appendLine("Seyal ${com.seyal.app.BuildConfig.VERSION_NAME} · ${s.title} · ${s.status} · ${s.steps} steps · ${s.llmCalls} AI calls · model ${Settings.effectiveModel()}")
        appendLine("Started ${Times.format(s.createdAt)}, last change ${Times.format(s.updatedAt)}")
        var n = 0
        for (m in s.messages) when (m.role) {
            "user" -> { appendLine(); appendLine(if (m.text.startsWith(CHECK)) "SELF-CHECK: ${m.text.removePrefix(CHECK).take(600)}" else "USER: ${m.text.removePrefix(MIDTASK).take(1500)}") }
            "assistant" -> {
                if (m.text.isNotBlank()) appendLine("SEYAL: ${m.text.take(1500)}")
                m.calls.forEach { c ->
                    n++
                    val st = s.calls[c.id]
                    val secs = if (st != null && st.endedAt > 0 && st.startedAt > 0) " ${(st.endedAt - st.startedAt) / 100 / 10.0}s" else ""
                    appendLine("  $n. ${c.name} [${st?.status ?: "?"}]$secs ${c.args.toString().take(400)}")
                    results[c.id]?.output?.let { o ->
                        val copy = JSONObject(o.toString())
                        copy.optJSONObject("screen")?.let { sc -> copy.put("screen", sc.toString().take(900) + "…") }
                        appendLine("     → ${copy.toString().take(1200)}")
                    }
                }
            }
        }
        s.result?.let { appendLine(); appendLine("RESULT: $it") }
    }

    /** A short chat name from the first message: its first words, cut at a word. */
    internal fun titleFrom(command: String): String {
        val line = command.trim().lines().firstOrNull { it.isNotBlank() }?.trim() ?: return "Chat"
        if (line.length <= 48) return line
        val cut = line.take(48)
        return cut.substringBeforeLast(' ', cut).trimEnd(',', '.', ' ') + "…"
    }

    private fun trimLive() {
        val closed = live.values.filter { !it.status.isOpen }.sortedByDescending { it.updatedAt }
        closed.drop(12).forEach { if (it.id != _current.value) live.remove(it.id) }
    }

    // ------------------------------------------------------------ requests

    /** Starts a new session for a user request and runs the loop. */
    fun start(command: String, image: Part.Image? = null, source: String = "user"): Session {
        val s = Session(newId(), System.currentTimeMillis(), source)
        s.title = titleFrom(command)
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
                s.status.isOpen && jobs[s.id]?.isActive == true -> {
                    parts[0] = Part.Text(MIDTASK + msg)
                    s.queued += Msg("user", parts); touch(s); return@launch
                }
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
        var firstRound = true
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

                if (firstRound) {
                    firstRound = false
                    // A new request gets its own checks before "done".
                    s.checked.clear()
                    val lastMsg = lock.withLock { s.messages.lastOrNull() }
                    val said = lastMsg?.takeIf { it.role == "user" && it.parts.none { p -> p is Part.Image } }?.text?.removePrefix(MIDTASK).orEmpty()
                    if (s.source == "user" && s.parentId == null && said.isNotBlank()) {
                        // 1. A command that needs no thinking ("torch on", "open YouTube"): do it now.
                        val quick = Quick.match(said)
                        val quickTool = quick?.let { byName[it.name] }
                        if (quick != null && quickTool != null) {
                            val r = runSynthetic(s, quick, quickTool)
                            s.steps++; stepsThisRun++
                            if (!r.isError && r.output.optBoolean("ok", true)) {
                                finish(s, SessionStatus.DONE, lock.withLock { s.calls[quick.id]?.line } ?: "Done.")
                                return
                            }
                        }
                        // 1b. Asked before, done the same way: repeat the steps that worked, with no AI at all.
                        val known = if (quick == null && Preflight.kind(said) == null) Routines.exact(said)?.takeIf { r -> r.steps.all { byName[it.name] != null } } else null
                        if (known != null) {
                            report("Doing it the way that worked last time…")
                            val steps = known.steps.map { Part.Call(newId(), it.name, JSONObject(it.args.toString())) }
                            if (replay(s, steps)) {
                                s.steps += steps.size; s.replayed = true
                                val lines = lock.withLock {
                                    val from = s.messages.indexOfLast { fromUser(it) }
                                    s.messages.drop(from + 1).filter { it.role == "assistant" }.flatMap { it.calls }.mapNotNull { s.calls[it.id]?.line }.filter { it.isNotBlank() }
                                }
                                finish(s, SessionStatus.DONE, lines.joinToString("\n").ifBlank { "Done." })
                                return
                            }
                            // It did not work this time: forget the shortcut and let the AI look at what happened.
                            Routines.distrust(said)
                            if (lock.withLock { s.status == SessionStatus.CANCELLED }) return
                            lock.withLock { s.messages += Msg("user", listOf(Part.Text(CHECK + "The steps above were repeated from last time and one did not work. Finish the request yourself: $said"))); touch(s) }
                        }
                        // 2. Buying, booking or ordering: the user gives the details before anything is opened.
                        val kind = if (quick == null && known == null) Preflight.kind(said) else null
                        val ask = byName["ask_user"]
                        if (kind != null && ask != null) {
                            report("Checking what I need to know…")
                            val pre = try {
                                Llm.chat(Preflight.system(kind, said), lock.withLock { repair(s); forModel(s) }, listOf(ask.decl(), Order.decl(kind)), 700, forceTool = true, onWait = { report(it) })
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                            lock.withLock { s.llmCalls++; s.tokens += pre?.tokens ?: 0; touch(s) }
                            var question = pre?.calls?.firstOrNull { it.name == "ask_user" && it.args.optString("question").isNotBlank() }
                            if (question == null) {
                                // The AI did not ask. That is only right when the user really gave the details: check the one
                                // that costs most when guessed (how many), and ask it ourselves if nobody said it.
                                val words = lock.withLock { requestWords(s) }
                                val saidComplete = pre?.calls?.any { it.name == "details_complete" } == true
                                val leaves = Preflight.leavesChoice(words)
                                val needCount = kind.unit.isNotBlank() && !Preflight.saysHowMany(words)
                                val needSeat = kind == Preflight.Kind.MOVIE && !Preflight.saysSeatOrBudget(words)
                                question = when {
                                    leaves -> null
                                    !saidComplete -> Part.Call(newId(), "ask_user", JSONObject().put("question", Preflight.fallbackQuestion(kind, onlyCount = false)))
                                    else -> Preflight.missingQuestion(kind, needCount, needSeat)?.let { q ->
                                        val args = JSONObject().put("question", q)
                                        if (needCount && !needSeat) args.put("options", org.json.JSONArray(listOf("1", "2", "3", "4")))
                                        Part.Call(newId(), "ask_user", args)
                                    }
                                }
                            }
                            if (question != null) { runSynthetic(s, question, ask); s.steps++; stepsThisRun++ }
                            // 3. Write the order down once, in the user's terms. Every later step is held to it.
                            var written = pre?.calls?.firstOrNull { it.name == "details_complete" }?.args?.takeIf { question == null }
                            if (written == null && question != null) {
                                report("Noting your details…")
                                written = try {
                                    Llm.chat(Preflight.system(kind, said) + "\nThe user has answered. Call details_complete now.", lock.withLock { repair(s); forModel(s) }, listOf(Order.decl(kind)), 500, forceTool = true, onWait = { report(it) })
                                        .also { r -> lock.withLock { s.llmCalls++; s.tokens += r.tokens; touch(s) } }
                                        .calls.firstOrNull { it.name == "details_complete" }?.args
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                            }
                            val words = lock.withLock { requestWords(s) }
                            val byAi = written?.let { Order.fromJson(JSONObject(it.toString()).put("kind", kind.name)) }
                            val byWords = Order.fromWords(kind, words)
                            // A number the user plainly said beats whatever the AI wrote down.
                            s.order = if (byAi == null) byWords.takeIf { !it.isEmpty } else Order.fromJson(byAi.toJson().apply {
                                if (byWords.count > 0) put("count", byWords.count)
                                if (byWords.maxEach > 0) put("max_each", byWords.maxEach)
                            })
                        }
                    }
                }

                val snapshot = lock.withLock { repair(s); forModel(s) }
                report(if (stepsThisRun == 0) "Thinking…" else "Thinking · step ${stepsThisRun + 1}")
                // From here on, element ids mean the screen the AI is about to see.
                ScreenDriver.freezeIds(s.id)
                val t0 = System.currentTimeMillis()
                val resp = try {
                    Llm.chat(Prompts.system(s, tools), snapshot, decls, 2048, onWait = { report(it) })
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
                    // "Done" has to mean done: compare what was asked with what happened, and send the AI back once per gap.
                    val gap = if (s.source == "user" && s.parentId == null && s.checked.size < 5) lock.withLock {
                        val said = lastSaid(s)
                        val words = userWords(s)
                        val one = Regex("\\b(1|one|single|just me|only me|myself|alone)\\b", RegexOption.IGNORE_CASE).containsMatchIn(words.joinToString(" ")) && !Regex("\\b([2-9]|two|three|four|five|six)\\s*(tickets?|seats?|people|persons?)", RegexOption.IGNORE_CASE).containsMatchIn(words.joinToString(" "))
                        Checks.beforeDone(said, Preflight.kind(said) ?: Preflight.kind(words.firstOrNull().orEmpty()).takeIf { said.length < 40 }, didSince(s), s.checked, manyPeople = !one, hasOrder = s.order != null)
                    } else null
                    if (gap != null) {
                        s.checked += gap.key
                        report("Checking the result…")
                        lock.withLock { s.messages += Msg("user", listOf(Part.Text(CHECK + gap.text))); touch(s) }
                        continue
                    }
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
                // Screen actions sent together assume each one worked: after one fails, the rest would hit the wrong screen.
                var screenBroke = false
                val results = if (resp.calls.any { usesScreen(it.name) }) resp.calls.map { call ->
                    if (screenBroke && usesScreen(call.name)) {
                        skipped += call.id
                        lock.withLock { s.state(call.id).apply { status = CallStatus.CANCELLED; error = "Skipped: the step before it did not work"; startedAt = System.currentTimeMillis(); endedAt = startedAt }; touch(s) }
                        Part.Result(call.id, call.name, JSONObject().put("error", "Skipped because the action before it failed. Look at the screen it returned and decide again."), true)
                    } else one(call).also { r -> if (usesScreen(call.name) && (r.isError || !r.output.optBoolean("ok", true))) screenBroke = true }
                }
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

    /** Whether a skill's goal is to find something out and say it (then the AI words the result), rather than just to do things. */
    internal fun wantsAnswer(goal: String): Boolean =
        Regex("\\b(tell|what|how|which|who|when|where|show|read|check|find|answer|summar\\w*|list|news|weather|give me|sollu|enna|evlo|yaaru)\\b|\\?|சொல்|என்ன", RegexOption.IGNORE_CASE).containsMatchIn(goal.substringBefore("\n\n"))

    /** Runs a call the agent decided on without the AI, recorded like any other step. */
    private suspend fun runSynthetic(s: Session, call: Part.Call, tool: Tool): Part.Result {
        lock.withLock { s.messages += Msg("assistant", listOf(call)); s.state(call.id).status = CallStatus.PENDING; touch(s) }
        val r = runCall(s, call, tool)
        lock.withLock { s.messages += Msg("tool", listOf(r)); touch(s) }
        return r
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
        // Remember how it was done, so the same request is instant next time and a similar one starts ahead.
        if (status == SessionStatus.DONE && s.parentId == null && s.source == "user" && !s.replayed) runCatching {
            val said = lastSaid(s)
            val from = s.messages.indexOfLast { fromUser(it) }
            val calls = s.messages.drop(from + 1).filter { it.role == "assistant" }.flatMap { it.calls }
            val allOk = calls.isNotEmpty() && calls.all { s.calls[it.id]?.status == CallStatus.DONE } && s.checked.isEmpty()
            if (said.isNotBlank() && Quick.match(said) == null) Routines.learn(said, calls.map { Routines.Step(it.name, it.args) }, allOk, wantsAnswer(said), System.currentTimeMillis())
        }
        s.replayed = false
        // Learn from it: what would make the same kind of task quicker next time in that app.
        if (status == SessionStatus.DONE && s.parentId == null && Settings.hasKey) s.workApp?.let { pkg ->
            scope.launch { runCatching { Lessons.learnFrom(s, ScreenDriver.appLabel(app, pkg)) } }
        }
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
        // Earlier requests in this chat are finished business: keep what was said and what came of it, not every
        // result in full. (A long chat otherwise makes each step slower and eats the per-minute limit.)
        // The request just before this one stays whole: "email it", "add one more slide" point straight back at it.
        val asks = msgs.indices.filter { fromUser(msgs[it]) }
        val keepFrom = asks.getOrNull(asks.size - 2) ?: 0
        for (i in 0 until keepFrom) msgs[i] = History.shrink(msgs[i]) { id -> s.calls[id]?.line }
        fun size(m: Msg) = m.parts.sumOf { p -> if (p is Part.Result) p.output.toString().length + (p.image?.b64?.length ?: 0) / 8 else if (p is Part.Text) p.text.length else if (p is Part.Call) p.args.toString().length else 0 }
        var total = msgs.sumOf { size(it) }
        if (total < 40_000) return msgs
        for (i in toolIdx.dropLast(4)) {
            if (total < 26_000) break
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
        val lastUser = s.messages.indexOfLast { fromUser(it) }
        val calls = s.messages.drop(lastUser + 1).filter { it.role == "assistant" }.flatMap { it.calls }
        val done = calls.mapNotNull { c -> s.calls[c.id]?.takeIf { it.status == CallStatus.DONE }?.line?.let { c to it } }
        if (done.isEmpty()) return SessionStatus.FAILED to why
        val did = done.map { it.second.take(70) }.distinct().takeLast(3).joinToString("; ")
        val lastRound = s.messages.lastOrNull { it.role == "assistant" && it.calls.isNotEmpty() }?.calls.orEmpty()
        val lastOk = lastRound.isNotEmpty() && lastRound.all { s.calls[it.id]?.status == CallStatus.DONE }
        // Only a finished direct action (a message sent, an alarm set) counts as done. Taps on a screen are
        // steps on the way: stopping after them means the task is NOT finished.
        val acted = lastRound.any { Toolbox.get(it.name)?.let { t -> t.sideEffect && t.group != "screen" } == true }
        val midScreen = calls.any { Toolbox.get(it.name)?.group == "screen" }
        return if (lastOk && acted && !midScreen) SessionStatus.DONE to "Done: $did."
        // The reason goes first: the one-line result shown in lists is cut short.
        else SessionStatus.FAILED to "Not finished. $why Done so far: $did. Say \"continue\" to carry on."
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
            if (lastWords != null) { ScreenDriver.status(plain(lastWords).take(70), busy = false); delay(4500) }
            lock.withLock { if (activity.get() == g && live.values.none { it.status.isOpen }) ScreenDriver.status(null) }
        }
    }

    /** The agent repeated the same call with the same result three times: stop it wasting steps. */
    private suspend fun stuck(s: Session, call: Part.Call): Part.Result = lock.withLock {
        val msg = "You already did exactly this several times and got the same result. Do NOT repeat it. Recover, in this order, until something moves: " +
            "1) screen_wait 4 seconds for a word you expect (the app may be loading); 2) screenshot, then screen_tap_at on what you see; 3) screen_scroll, or tap a different element that leads to the same place; " +
            "4) screen_back once; 5) open_app again (the app restarts where it was) and continue. Only after these, if it still will not move, say exactly which screen is stuck and what you already did."
        s.state(call.id).apply { status = CallStatus.FAILED; error = "Skipped: repeated with no progress"; startedAt = System.currentTimeMillis(); endedAt = startedAt }
        touch(s)
        Part.Result(call.id, call.name, JSONObject().put("error", msg), true)
    }

    // ------------------------------------------------------------ one tool call

    /** Whether a message target is the person a skill was started by ("amma" and "Amma (2 messages)"). */
    internal fun samePerson(target: String, person: String?): Boolean {
        if (person.isNullOrBlank()) return false
        val a = com.seyal.app.data.Trigger.norm(target); val b = com.seyal.app.data.Trigger.norm(person)
        if (a.length < 2 || b.length < 2) return false
        return a == b || a.startsWith("$b ") || b.startsWith("$a ")
    }

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
        // A skill the user made to answer a person ("when Amma asks where I am, reply with my location"):
        // replying to that same person is what they asked for.
        if (level == Level.ASK && tool.group == "comms" && target != null && !target.contains("group chat") && samePerson(target, s.trustedReplyTo)) level = Level.AUTO
        // A scheduled or automatic send the user confirmed when the skill was made ("tomorrow 8 am send Ram the link").
        if (level == Level.ASK && target != null && "${tool.name}|$target" in s.preApproved) level = Level.AUTO
        // Safety net: after 40 unattended sends in a day, ask again.
        if (level == Level.AUTO && tool.group == "comms" && tool.sideEffect && Settings.autoSendToday >= 40) level = Level.ASK
        // After the user said No to something in this conversation, screen actions ask first too
        // (so a declined message can't be sent another way through the app).
        if (level == Level.AUTO && tool.group == "screen" && tool.sideEffect && lock.withLock { s.calls.values.any { it.status == CallStatus.DENIED } }) level = Level.ASK
        if (level == Level.OFF) {
            val r = lock.withLock { fail("\"${tool.name}\" is turned off in Control → Permissions. Tell the user.", CallStatus.DENIED).also { touch(s) } }
            return r
        }
        // A run that started by itself from an incoming message or call: sending is a privilege, tightly held.
        if (s.unattended && tool.group == "comms" && tool.sideEffect) {
            val sentAlready = lock.withLock { s.calls.values.any { it.callId != call.id && it.status == CallStatus.DONE && it.sent } }
            val no = when {
                !Settings.autoReplies -> "Automatic replies are turned off (Skills → Automatic replies), so nothing was sent. Use notify to tell the user what arrived instead."
                sentAlready -> "One answer was already sent in this run. An automatic run sends one message, to the person who wrote, and stops."
                target != null && target.contains("group chat") -> "That is a group chat. Seyal never sends to a group by itself. Use notify to tell the user instead."
                else -> null
            }
            if (no != null) return lock.withLock { fail(no, CallStatus.DENIED).also { touch(s) } }
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
            ScreenDriver.showCard(
                call.id, "Seyal needs your OK", preview, listOf("Allow", "Always allow", "No"), null,
                onPick = { wait.complete(when (it) { "Allow" -> "allow"; "Always allow" -> "allow_always"; else -> "deny" }) }, onType = {},
            )
            val decision = try { withTimeoutOrNull(30 * 60_000L) { wait.await() } ?: "deny" } finally { waits.remove(call.id); Notify.cancelApproval(app, s.id, call.id); ScreenDriver.hideCard(call.id) }
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
        // The user opened Seyal meanwhile (to approve, answer or type): go back to the app first.
        if (tool.group == "screen") backToWork(s)
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

        if (usesScreen(tool.name)) ScreenDriver.workAppNow()?.let { s.workApp = it }
        if (tool.name == "ask_user") (result as? ToolResult.Done)?.let { r ->
            backToWork(s)?.let { screen -> r.output.put("note", "You are back in the app you were working in. This is its screen now.").put("screen", screen) }
        }

        // 3. Record it.
        return lock.withLock {
            state.endedAt = System.currentTimeMillis()
            val out = when (val r = result) {
                is ToolResult.Done -> {
                    // A screen action that didn't work still hands the AI the screen (not an error for it), but shows as failed.
                    if (r.output.optBoolean("ok", true)) { state.status = CallStatus.DONE; state.line = r.line; state.error = null }
                    else { state.status = CallStatus.FAILED; state.error = r.output.optString("error").ifBlank { r.line }; state.line = null }
                    if (tool.sideEffect && tool.group == "comms") {
                        Settings.autoSendToday = Settings.autoSendToday + 1
                        if (state.status == CallStatus.DONE) {
                            state.sent = true
                            // Nobody was watching: say at once what went out and to whom, so a wrong one is caught on the first.
                            if (s.unattended) {
                                Skills.log("\"${s.title}\" sent by itself: ${r.line.take(80)}")
                                runCatching { Notify.line(app, "Seyal answered by itself (${s.title}): ${r.line.take(90)}. Turn this off in Skills.", s.id) }
                            }
                        }
                    }
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
        // Over the app being worked in: answer with one tap, or open Seyal to type.
        ScreenDriver.showCard(
            state.callId, "Seyal asks", question, options, "Type an answer",
            onPick = { wait.complete(it) },
            onType = { ScreenDriver.onOpen?.invoke() },
        )
        val answer = try { withTimeoutOrNull(60 * 60_000L) { wait.await() } ?: "(no answer)" } finally { waits.remove(state.callId); Notify.cancelQuestion(app, s.id); ScreenDriver.hideCard(state.callId) }
        lock.withLock { s.status = SessionStatus.RUNNING; touch(s) }
        return answer
    }

    /**
     * The user may have opened Seyal (to answer, approve or type) while a task was inside another app.
     * Go back to that app before touching the screen again. Returns the fresh screen, or null if nothing moved.
     */
    private suspend fun backToWork(s: Session): JSONObject? {
        val pkg = s.workApp ?: return null
        if (!ScreenDriver.available || ScreenDriver.frontPackage() != ScreenDriver.ownPackage) return null
        report("Back to ${ScreenDriver.appLabel(app, pkg)}")
        if (!ScreenDriver.bringToFront(pkg)) return null
        return ScreenDriver.snapshotAfter(500)
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
            Routines.clear(); Inbox.clear(); Watchers.clear()
            Skills.wipe()
            Lessons.clear()
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
                // The recorded steps did the whole job: finish at once, without the AI (instant, works offline,
                // costs nothing). Only a skill whose goal is to tell the user something needs the AI to word it.
                if (ok && (!Settings.hasKey || !wantsAnswer(goal))) {
                    val last = lock.withLock {
                        // The closing instruction was for the AI; nobody needs to see it.
                        if (s.messages.lastOrNull()?.role == "user") s.messages.removeAt(s.messages.size - 1)
                        s.calls.values.filter { it.status == CallStatus.DONE && it.line != null }.maxByOrNull { it.endedAt }?.line
                    }
                    finish(s, SessionStatus.DONE, "Done: " + (last ?: s.title))
                    return@launch
                }
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

/** Making finished parts of a conversation small before they are sent to the AI again. */
object History {
    /** Values a later request may point back to ("email it", "call that number", "open the link"). */
    private val keep = setOf("uri", "path", "link", "url", "map_link", "file", "name", "id", "to", "number", "phone", "email", "answer", "title", "sent", "saved", "place")

    /**
     * A message from an earlier request, cut down: a result keeps its one-line summary and the short
     * values worth pointing back to; a call keeps its name and short arguments.
     */
    fun shrink(m: Msg, line: (String) -> String?): Msg = when (m.role) {
        "tool" -> Msg("tool", m.results.map { r ->
            if (r.output.toString().length <= 300 && r.image == null) r
            else {
                val o = JSONObject().put("earlier_result", line(r.callId)?.takeIf { it.isNotBlank() } ?: if (r.isError) "failed" else "done")
                r.output.keys().forEach { k -> val v = r.output.opt(k); if (k in keep && (v is Number || v is Boolean || (v is String && v.length <= 300))) o.put(k, v) }
                if (r.isError) r.output.optString("error").takeIf { it.isNotBlank() }?.let { o.put("error", it.take(200)) }
                Part.Result(r.callId, r.name, o, r.isError)
            }
        }, m.at)
        "assistant" -> if (m.calls.none { it.args.toString().length > 400 }) m else Msg("assistant", m.parts.map { p ->
            if (p !is Part.Call || p.args.toString().length <= 400) p
            else Part.Call(p.id, p.name, JSONObject().apply { p.args.keys().forEach { k -> val v = p.args.opt(k); put(k, if (v is String && v.length > 160) v.take(160) + "…" else if (v is org.json.JSONArray && v.toString().length > 200) "(${v.length()} items, sent earlier)" else v) } }, p.signature)
        }, m.at)
        else -> m
    }
}
