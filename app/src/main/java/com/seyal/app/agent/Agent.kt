package com.seyal.app.agent

import android.content.Context
import android.content.Intent
import com.seyal.app.BuildConfig
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
import com.seyal.app.device.ScreenAccess
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

    /**
     * What a finished task was about, as something a tap can open (a Target): the mail app when it read or wrote mail,
     * else the app it worked in. "" when it was about nothing outside Seyal.
     */
    internal fun aboutWhat(s: Session): String {
        val used = s.messages.flatMap { it.calls }.map { it.name }
        return when {
            used.any { Router.groupOf(it) == "mail" } -> "mail:"
            s.workApp != null -> "pkg:${s.workApp}"
            else -> ""
        }
    }

    /** A question that lists several things to answer ("1. Theatre … 2. Day …"). */
    internal fun manyParts(question: String): Boolean = Regex("(^|\\n)\\s*2\\s*[.)]").containsMatchIn(question)

    /** How many steps the request being worked on has taken so far (the chat's earlier requests are not counted). */
    fun stepsOfRequest(s: Session): Int {
        val from = s.messages.indexOfLast { fromUser(it) }
        return s.messages.drop(from + 1).count { it.role == "assistant" && it.calls.isNotEmpty() }
    }

    /** The user's latest words in [s]. */
    internal fun lastSaid(s: Session): String = s.messages.lastOrNull { fromUser(it) }?.text?.removePrefix(MIDTASK).orEmpty()

    /** Everything the user said in [s]: their messages and their answers to questions. */
    internal fun userWords(s: Session): List<String> =
        s.messages.filter { fromUser(it) }.map { it.text.removePrefix(MIDTASK) } +
            s.messages.filter { it.role == "tool" }.flatMap { it.results }.filter { it.name == "ask_user" }.map { it.output.optString("answer") }.filter { it.isNotBlank() }

    /**
     * Runs an everyday command with no AI: looks the person up if there is one (asking which, when several
     * match), runs the tool through the usual permission rules, and words the answer. Null = it could not
     * be done this way; the AI takes over and sees what was tried.
     */
    private suspend fun runBasic(s: Session, plan: Basics.Plan, byName: Map<String, Tool>): String? {
        if (plan.tool.isEmpty()) return plan.say
        val tool = byName[plan.tool] ?: return null
        val args = JSONObject(plan.args.toString())
        if (plan.who != null) {
            // Asked "Which Ram?" before: the answer was remembered.
            Usual.alias(plan.who)?.let { known ->
                args.put("phone", known.second).put("name", known.first)
                val call = Part.Call(newId(), plan.tool, args)
                val r = runSynthetic(s, call, tool); s.steps++
                val st = lock.withLock { s.calls[call.id] }
                if (st?.status == CallStatus.DENIED) return "Okay, I didn't do it."
                if (r.isError || !r.output.optBoolean("ok", true)) return null
                return plan.answer?.invoke(r.output) ?: plan.say ?: st?.line?.takeIf { it.isNotBlank() } ?: "Done."
            }
            val search = byName["contacts_search"] ?: return null
            val c = Part.Call(newId(), "contacts_search", JSONObject().put("name", plan.who))
            val r = runSynthetic(s, c, search); s.steps++
            val found = r.output.optJSONArray("matches")?.let { m -> (0 until m.length()).mapNotNull { m.optJSONObject(it) }.map { it.optString("name") to it.optString("phone") } }.orEmpty().filter { it.second.isNotBlank() }
            if (found.isEmpty()) return "I couldn't find \"${plan.who}\" in your contacts. Tell me the name as it is saved, or the number."
            var pick = Basics.pick(plan.who, found)
            if (pick == null) {
                // Several different people match: ask which, then carry on.
                val ask = byName["ask_user"] ?: return null
                val names = found.distinctBy { it.second.filter { ch -> ch.isDigit() }.takeLast(10) }.take(4)
                val q = Part.Call(newId(), "ask_user", JSONObject().put("question", "Which ${plan.who}?").put("options", org.json.JSONArray(names.map { it.first })))
                val a = runSynthetic(s, q, ask).output.optString("answer").trim(); s.steps++
                pick = names.firstOrNull { it.first.equals(a, true) } ?: names.firstOrNull { a.isNotBlank() && (it.first.contains(a, true) || a.contains(it.first, true)) }
                    ?: return "I wasn't sure who you meant, so I did nothing."
                Usual.setAlias(plan.who, pick.first, pick.second)
            }
            args.put("phone", pick.second).put("name", pick.first)
        }
        val call = Part.Call(newId(), plan.tool, args)
        val r = runSynthetic(s, call, tool); s.steps++
        val st = lock.withLock { s.calls[call.id] }
        if (st?.status == CallStatus.DENIED) return "Okay, I didn't do it."
        if (r.isError || !r.output.optBoolean("ok", true)) return null
        return plan.answer?.invoke(r.output) ?: plan.say ?: st?.line?.takeIf { it.isNotBlank() } ?: "Done."
    }

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
        // "Screen control only while Seyal works": nothing is running when the app starts, so it rests (payment apps work).
        scope.launch {
            delay(8000)
            if (BuildConfig.FLAVOR == "full" && ScreenDriver.available && ScreenAccess.onDemand(app) && lock.withLock { live.values.none { it.status.isOpen } }) runCatching { ScreenAccess.off(app) }
        }
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
        // A task of the user's that was running a moment ago: the phone closed Seyal. Count it (Control shows the fix) and say so.
        val cut = interrupted(index.map { Triple(it.optString("status"), it.optString("source"), it.optLong("updated_at")) }, System.currentTimeMillis(), changed)
        if (cut >= 0) runCatching {
            Settings.killedCount = Settings.killedCount + 1; Settings.killedAt = System.currentTimeMillis()
            val e = index[cut]
            Notify.line(app, "Your phone closed Seyal while it was working on \"${e.optString("title").take(50)}\". Open Seyal and send \"continue\". To stop this: Control → Keep Seyal running.", e.optString("id"))
        }
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
        Ledger.init(context.filesDir); Parcels.init(context.filesDir); Past.init(context.filesDir)
        Coach.init(context.filesDir); Usual.init(context.filesDir); Khata.init(context.filesDir); Habits.init(context.filesDir); BrainStore.init(context.filesDir); ForesightStore.init(context.filesDir); AwareStore.init(context.filesDir)
        Coach.onChange = { runCatching { Scheduler.syncBackground(app) } }
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
    /** The tool groups this chat needs: from what the user said, what was already used, and what the AI asked for. */
    internal fun loadedGroups(s: Session, excludeLatestAsk: Boolean = false): Set<String> {
        val calls = s.messages.flatMap { it.calls }
        val asks = calls.filter { it.name == Router.MORE }.let { if (excludeLatestAsk) it.dropLast(1) else it }.flatMap { Router.asked(it.args) }
        val said = lastSaid(s)
        return Router.groupsFor(
            text = (userWords(s) + s.firstCommand).joinToString("\n"),
            used = calls.map { it.name },
            asked = if (s.allTools) asks + "all" else asks,
            purchase = s.order != null || Preflight.kind(said) != null || Preflight.kind(s.firstCommand) != null,
            attached = s.messages.any { m -> m.role == "user" && m.parts.any { it is Part.Image } },
            fromSkill = s.source.startsWith("skill"),
        ).let { if (s.deep) it + "foresight" else it }
    }

    /** The one line kept in the task list and the notification: the first line that says something, not a lead-in ending in a colon when more follows. */
    internal fun shortResult(text: String): String {
        val lines = plain(text).lines().map { it.trim().trimStart('-', '•', '*', ' ') }.filter { it.isNotBlank() }
        val first = lines.firstOrNull() ?: return "Done"
        val pick = if (first.endsWith(":") && lines.size > 1) first.trimEnd(':') + ": " + lines.drop(1).take(3).joinToString("; ") else first
        return pick.take(140)
    }

    /**
     * Whether the final answer still has to be added to the chat as a message. The AI's own answer is already
     * there ([lastAnswer] is the newest assistant text, [lastRole] who spoke last); an answer made without the AI is not.
     */
    internal fun needsBubble(lastAnswer: String?, lastRole: String?, text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return false
        return !(lastRole == "assistant" && lastAnswer != null && lastAnswer.trim() == t)
    }

    /** Whether the user's own words ask to reach someone: send, tell, message, call, mail, reply, wish, remind them… */
    internal fun asksToContact(words: String): Boolean = Regex(
        "\\b(send|sending|sent|message|msg|text|texting|tell|inform|let \\w+ know|notify|ping|whatsapp|sms|mail|e-?mail|reply|respond|answer|forward|share|wish|greet|invite|ask|request|remind (him|her|them|\\w+ to)|call|ring|dial|phone|post|announce|say (hi|hello|sorry|thanks|bye) to)\\b" +
            "|anuppu|anupu|anupidu|sollu|solli|kelu|kooptu|kupdu|pesu|(message|msg|call|phone|mail|whatsapp|sms) pannu|அனுப்பு|சொல்லு|கேளு|கூப்பிடு|மெசேஜ்",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(words)

    internal const val PREFLIGHT_WAIT_MS = 12_000L

    /** Tools whose every use is approved by the user, even after "Always allow". */
    internal fun mustAsk(tool: String): Boolean = tool == "send_to_many"

    /**
     * Which task (its place in [rows]: status, source, last change) was cut off by the phone closing Seyal: one of the
     * user's own, failed by the restart, and busy within the last ten minutes. -1 when there is none.
     */
    internal fun interrupted(rows: List<Triple<String, String, Long>>, now: Long, restartClosedSome: Boolean): Int {
        if (!restartClosedSome) return -1
        return rows.indexOfFirst { (status, source, at) -> status == SessionStatus.FAILED.name && source == "user" && now - at in 0..10 * 60_000L }
    }

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
    fun start(command: String, image: Part.Image? = null, source: String = "user", files: List<Part.Image> = emptyList(), deep: Boolean = false): Session {
        val s = Session(newId(), System.currentTimeMillis(), source)
        s.title = titleFrom(command)
        s.deep = source == "user" && (deep || Deep.wants(command))
        val parts = mutableListOf<Part>(Part.Text(command.trim()))
        image?.let { parts += it }
        parts += files
        // What is asked again and again at the same hour is a habit worth offering to automate.
        if (source == "user" && Habits.worthLogging(command)) runCatching { Habits.log("cmd:" + Routines.normalise(command), System.currentTimeMillis()) }
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
    fun say(sessionId: String, text: String, image: Part.Image? = null, files: List<Part.Image> = emptyList(), deep: Boolean = false) = scope.launch {
        val msg = text.trim()
        val s = lock.withLock {
            val s = live[sessionId] ?: Store.loadSession(sessionId)?.also { live[sessionId] = it } ?: return@launch
            val parts = mutableListOf<Part>(Part.Text(msg))
            image?.let { parts += it }
            parts += files
            val question = s.calls.values.firstOrNull { it.status == CallStatus.WAITING_USER && waits.containsKey(it.callId) }
            when {
                // The answer to the agent's question.
                question != null -> { waits[question.callId]?.complete(msg); return@launch }
                // The agent is mid-step: it picks this up as soon as the step's results are in (or when it finishes).
                s.status.isOpen && jobs[s.id]?.isActive == true -> {
                    parts[0] = Part.Text(MIDTASK + msg)
                    s.queued += Msg("user", parts); touch(s); return@launch
                }
                // A new job typed into an old chat: a fresh chat, because every AI step carries the whole chat along.
                // On a real phone one chat was used all day and a booking started at "step 73", slow and confused.
                newTask(s, msg, deep) -> {
                    // (The lock is held here; start() takes it in its own coroutine, so the note lands before anything reads the chat.)
                    start(msg, image, files = files, deep = deep).note = NEW_CHAT_NOTE
                    return@launch
                }
                // A finished, stopped or failed conversation carries on. The switch counts for this request only.
                else -> { s.deep = deep || Deep.wants(msg); s.messages += Msg("user", parts); s.status = SessionStatus.RUNNING; touch(s); run(s) }
            }
            s
        }
    }

    const val NEW_CHAT_NOTE = "Started a new chat for this: long chats make every step slower. The old chat is under ☰."
    private val followUp = Regex(
        "^(and|also|but|then|now|next|again|same|another|one more|more|it|that|this|those|these|there|here|no|yes|yeah|ok|okay|fine|instead|change|make it|cancel|stop|wait|why|what about|how about|same as|repeat|continue|go on|carry on|undo|redo|try again|retry|" +
            "அதே|இன்னொரு|மறுபடியும்|அது|இது|இல்ல|சரி|ஆமா)(?![\\p{L}\\p{M}])|^[^\\p{L}]*$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Whether [msg], typed into the finished chat [s], is a new job rather than a follow-up: a buy, booking or
     * order, or any longer request in a chat that has already served two or more. Short answers, "and…", "same
     * again", "it", "that" and the like stay in the chat they refer to.
     */
    internal fun newTask(s: Session, msg: String, deep: Boolean = false): Boolean {
        if (s.status.isOpen || s.parentId != null) return false
        val m = msg.trim()
        if (m.length < 12 || followUp.containsMatchIn(m)) return false
        // "Go to that link" means a link of this chat's last answer; "pause screen control" is no job at all.
        if (LinkPick.pointsAtLink(m) || PayPause.asks(m) != null) return false
        if (Preflight.kind(m) != null) return true
        // A Dirghadarshi conversation is one line of thought: "do gold prices fall?" after "buy gold now or wait?"
        // belongs to it. (On a real phone that follow-up was moved to a new chat and lost what came before.)
        if (deep || s.deep) return false
        val served = s.messages.count { fromUser(it) }
        return served >= 2 && m.split(Regex("\\s+")).size >= 4
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
                // In a booking, the steps that need no judgement are done by code, on the screen as it is now: the day in the
                // strip of dates, the "How many seats?" sheet, and handing the seat map to the user.
                if (!firstRound && s.parentId == null) stepsThisRun += runCatching { bookSteps(s) }.getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e; 0 }
                if (lock.withLock { s.status == SessionStatus.CANCELLED }) return
                // A dead end met during the last step (the app's search has nothing by that name, twice over): stop here.
                deadEnds.remove(s.id)?.let { end -> finish(s, SessionStatus.DONE, end); return }
                // Screen control dropped out in the middle of the request (Android leaves it switched on but dead after an
                // update): it is switched on again where Seyal can, else said plainly. On a real phone the AI reopened the
                // app again and again instead, with no word to the user.
                if (!firstRound && s.parentId == null && BuildConfig.FLAVOR == "full" && !ScreenDriver.available) {
                    val lost = lock.withLock { s.messages.lastOrNull()?.takeIf { it.role == "tool" }?.results?.any { it.isError && PayPause.lostScreen(it.output.optString("error")) } == true }
                    if (lost) {
                        report("Switching screen control on…")
                        val back = runCatching { ScreenAccess.on(app) }.getOrDefault(false)
                        if (!back) { finish(s, SessionStatus.FAILED, PayPause.LOST); return }
                        lock.withLock { s.messages += Msg("user", listOf(Part.Text(CHECK + PayPause.BACK_ON))); touch(s) }
                    }
                }
                // The seats are picked and the Pay button is up: Seyal's part is over, said at once and by code.
                readyToPay.remove(s.id)?.let { ready ->
                    val note = PayPause.payNote(true, ScreenDriver.available, ScreenAccess.onDemand(app))
                    val text = if (note != null) ready + "\n\n" + note else ready
                    lock.withLock { s.messages += Msg("assistant", listOf(Part.Text(text))); touch(s) }
                    finish(s, SessionStatus.DONE, text)
                    return
                }
                // The words of a new request (first round only).
                val said = if (!firstRound) "" else lock.withLock { s.messages.lastOrNull() }?.takeIf { it.role == "user" && it.parts.none { p -> p is Part.Image } }?.text?.removePrefix(MIDTASK).orEmpty()
                // Screen control may be resting (payment apps refuse to pay while it is on): it is switched on, or asked
                // for, BEFORE the tools are listed, so that this very step can use the screen.
                if (firstRound && !screenReady(s, said)) return
                // Tools can appear mid-task (the user turns screen control on), so look each step.
                val noShots = runCatching { ScreenDriver.shotsBroken() }.getOrDefault(false)
                val every = Toolbox.available().filter { (allowDelegate || it.name != "delegate") && !(noShots && it.name == "screenshot") }
                // Any tool can be run; only the ones this request can need are described to the AI (see Router).
                val byName = every.associateBy { it.name }
                val loaded = lock.withLock { loadedGroups(s) }
                val shown = Router.pick(every.map { it.name }, loaded)
                val tools = every.filter { it.name in shown }
                val decls = tools.map { it.decl() }
                // Bookings and orders are long by nature (search, pick, seats, review): give them room.
                if (stepsThisRun >= (if (s.order != null) maxOf(maxSteps, 50) else maxSteps)) {
                    finish(s, SessionStatus.FAILED, "I stopped after $stepsThisRun steps without finishing. Say \"continue\" and I'll carry on from where it is now.")
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
                        // 1a. The everyday commands (call, message, alarm, weather, battery…): understood on the phone, no AI asked.
                        val lastPlace = s.messages.asReversed().asSequence().filter { it.role == "tool" }.flatMap { it.results.asSequence() }.firstOrNull { it.name == "weather" && !it.isError }?.output?.optString("place")?.substringBefore(",")?.takeIf { it.isNotBlank() }
                        // The answer before this message: "go to that link" means a link in it.
                        val lastAnswer = lock.withLock { s.messages.dropLast(1).lastOrNull { it.role == "assistant" && it.calls.isEmpty() && it.text.isNotBlank() }?.text }
                        val basic = if (quick == null) Basics.plan(said, whatsApp = runCatching { Preflight.appLabels().any { it.contains("WhatsApp", true) } }.getOrDefault(true), lastPlace = lastPlace, lastAnswer = lastAnswer) else null
                        if (basic != null) {
                            val answer = runBasic(s, basic, byName)
                            if (lock.withLock { s.status == SessionStatus.CANCELLED }) return
                            if (answer != null) {
                                s.replayed = true
                                // Asked by voice in Tamil, or simple mode: say it aloud as well.
                                if (basic.speak && !Settings.speak && !Settings.simpleMode) runCatching { com.seyal.app.device.Speech.say(app, answer) }
                                finish(s, SessionStatus.DONE, answer); return
                            }
                        }
                        // 1b. Asked before, done the same way: repeat the steps that worked, with no AI at all.
                        val known = if (quick == null && basic == null && !s.deep && Preflight.kind(said) == null) Routines.exact(said)?.takeIf { r -> r.steps.all { byName[it.name] != null } } else null
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
                        // 1c. A plan (workout, study, diet): ask about the person first, so the plan fits them.
                        val coach = if (quick == null && basic == null && known == null) Coach.wants(said) else null
                        if (coach != null) {
                            val ask = byName["ask_user"]
                            if (ask != null) {
                                report("Thinking about what to ask…")
                                val pre = try {
                                    Llm.chat(Coach.system(coach), lock.withLock { repair(s); forModel(s) }, listOf(ask.decl(), Preflight.complete), 600, forceTool = true, onWait = { report(it) })
                                        .also { r -> lock.withLock { s.llmCalls++; s.tokens += r.tokens; touch(s) } }
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                                val q = pre?.calls?.firstOrNull { it.name == "ask_user" && it.args.optString("question").isNotBlank() }
                                    ?: if (pre?.calls?.any { it.name == "details_complete" } == true) null else Part.Call(newId(), "ask_user", JSONObject().put("question", Coach.fallbackQuestion(coach)))
                                if (q != null) { runSynthetic(s, q, ask); s.steps++; stepsThisRun++ }
                                if (lock.withLock { s.status == SessionStatus.CANCELLED }) return
                            }
                        }
                        // 2. Buying, booking or ordering: the user gives the details before anything is opened.
                        val kind = if (quick == null && known == null) Preflight.kind(said) else null
                        val ask = byName["ask_user"]
                        if (kind != null && ask != null) {
                            report("Checking what I need to know…")
                            // The AI words the question better, but the user must not wait for it: on a real phone this took
                            // 40 seconds. After 12 the built-in question is asked instead.
                            val pre = try {
                                withTimeoutOrNull(PREFLIGHT_WAIT_MS) { Llm.chat(Preflight.system(kind, said), lock.withLock { repair(s); forModel(s) }, listOf(ask.decl(), Order.decl(kind)), 700, forceTool = true, onWait = { report(it) }) }
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                            lock.withLock { s.llmCalls++; s.tokens += pre?.tokens ?: 0; touch(s) }
                            var question = pre?.calls?.firstOrNull { it.name == "ask_user" && it.args.optString("question").isNotBlank() }
                            // A question with several numbered parts is answered in one typed line. Tap-buttons for its first
                            // part only (seen on a real phone: "Any nearby · Kgam Cinemas · Cinepolis") answer a fifth of it.
                            question?.let { q -> if (manyParts(q.args.optString("question"))) q.args.remove("options") }
                            // Food: when the user's own words already say the dish, how many and where from, no question is
                            // asked, whatever the AI wanted to ask ("already told u", seen on a real phone).
                            if (kind == Preflight.Kind.FOOD && question != null && Preflight.missingFood(lock.withLock { requestWords(s) }).isEmpty()) question = null
                            // A film too: "…190 per ticket" was asked "the most you want to pay per ticket?" (real phone, twice).
                            if (kind == Preflight.Kind.MOVIE && question != null && Preflight.missingMovie(lock.withLock { requestWords(s) }, java.time.LocalDateTime.now()).isEmpty()) question = null
                            if (question == null) {
                                // The AI did not ask. That is only right when the user really gave the details: check the one
                                // that costs most when guessed (how many), and ask it ourselves if nobody said it.
                                val words = lock.withLock { requestWords(s) }
                                val saidComplete = pre?.calls?.any { it.name == "details_complete" } == true
                                val leaves = Preflight.leavesChoice(words)
                                val usual = if (words.any { Usual.asksForSame(it) }) Usual.last(kind.name) else null
                                val needCount = kind.unit.isNotBlank() && !Preflight.saysHowMany(words) && (usual?.count ?: 0) == 0
                                val needSeat = kind == Preflight.Kind.MOVIE && !Preflight.saysSeatOrBudget(words) && usual?.let { it.seat.isNotBlank() || it.maxEach > 0 } != true
                                question = when {
                                    leaves -> null
                                    // The AI did not answer in time. For a film, what the user's own words say is read by code and
                                    // only what is missing is asked (nothing, when it was all said). Other kinds: the full question.
                                    !saidComplete -> (when (kind) {
                                        Preflight.Kind.MOVIE -> Preflight.missingMovie(words, java.time.LocalDateTime.now()).takeIf { it.isNotEmpty() }?.let { Preflight.askFor(it) }
                                        Preflight.Kind.FOOD -> Preflight.missingFood(words).takeIf { it.isNotEmpty() }?.let { Preflight.askFor(it, "order") }
                                        else -> Preflight.fallbackQuestion(kind, onlyCount = false)
                                    })
                                        ?.let { q -> Part.Call(newId(), "ask_user", JSONObject().put("question", q)) }
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
                            // Meanwhile the film's own page is looked for on the web (two seconds, in the background):
                            // opening it skips the app's splash, adverts and search box.
                            if (kind == Preflight.Kind.MOVIE && ScreenDriver.available) {
                                val title = Opener.movieQuery(Order.titleIn(lock.withLock { requestWords(s) }))
                                if (title.isNotBlank()) filmLinks[s.id] = scope.async { title to findFilmLink(title) }
                            }
                            if (written == null && question != null) {
                                report("Noting your details…")
                                written = try {
                                    withTimeoutOrNull(PREFLIGHT_WAIT_MS) {
                                        Llm.chat(Preflight.system(kind, said) + "\nThe user has answered. Call details_complete now.", lock.withLock { repair(s); forModel(s) }, listOf(Order.decl(kind)), 500, forceTool = true, onWait = { report(it) })
                                            .also { r -> lock.withLock { s.llmCalls++; s.tokens += r.tokens; touch(s) } }
                                            .calls.firstOrNull { it.name == "details_complete" }?.args
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                            }
                            val words = lock.withLock { requestWords(s) }
                            val byAi = written?.let { Order.fromJson(JSONObject(it.toString()).put("kind", kind.name)) }
                            val byWords = Order.fromWords(kind, words)
                            // A number the user plainly said beats whatever the AI wrote down.
                            val noted = if (byAi == null) byWords.takeIf { !it.isEmpty } else Order.fromJson(byAi.toJson().apply {
                                if (byWords.count > 0) put("count", byWords.count)
                                if (byWords.maxEach > 0) put("max_each", byWords.maxEach)
                                // So does a day they wrote out ("20th November") when the AI noted none.
                                if (byAi.date.isBlank() && byWords.date.isNotBlank()) put("date", byWords.date)
                                // The thing itself must be the user's own word. The AI once wrote "Sikandar" for "scene".
                                if (!Order.grounded(byAi.item, words)) put("item", when (kind) { Preflight.Kind.MOVIE -> Order.titleIn(words); Preflight.Kind.FOOD -> byWords.item; else -> Order.itemFromWords(words) })
                                // The food the user named, when the AI noted none.
                                if (kind == Preflight.Kind.FOOD && byAi.item.isBlank() && byWords.item.isNotBlank()) put("item", byWords.item)
                                // The place is held against the Pay screen later: only a place the user really named counts.
                                // "Any nearby theatre" is no place (it was once noted as the theatre's name).
                                put("place", Order.placeFor(byAi.place, words, kind))
                            })
                            // "Same as last time": what they left out comes from the last order of this kind that went through.
                            s.order = if (words.any { Usual.asksForSame(it) }) Usual.fill(noted ?: Order(kind.name)).takeIf { !it.isEmpty } else noted
                            // 4. The first moves are the same every time (open the app, search, open the right result):
                            // done by code, at once, instead of one AI question per tap.
                            if (lock.withLock { s.status == SessionStatus.CANCELLED }) return
                            stepsThisRun += runCatching { runOpener(s, kind, byName) }.getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e; 0 }
                            // The page the opener leaves (the film's show list) gets the user's day before the AI sees it.
                            stepsThisRun += runCatching { bookSteps(s) }.getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e; 0 }
                            filmLinks.remove(s.id)?.cancel()
                            if (lock.withLock { s.status == SessionStatus.CANCELLED }) return
                            // The film is not running: said at once, with no AI steps spent looking further.
                            deadEnds.remove(s.id)?.let { end -> finish(s, SessionStatus.DONE, end); return }
                        }
                    }
                }

                val snapshot = lock.withLock { repair(s); forModel(s) }
                // Dirghadarshi mode thinks hard about the answer and the plan, not about each tap inside an app.
                val usedScreen = s.deep && lock.withLock { didSince(s).any { usesScreen(it.name) } }
                val hard = Deep.thinkHard(s.deep, usedScreen, s.order != null, if (s.deep) lock.withLock { lastSaid(s) } else "")
                report(if (hard) Deep.status(stepsThisRun, reviewing = "deep-review" in s.checked) else if (stepsThisRun == 0) "Thinking…" else "Thinking · step ${stepsThisRun + 1}")
                // From here on, element ids mean the screen the AI is about to see.
                ScreenDriver.freezeIds(s.id)
                val t0 = System.currentTimeMillis()
                val resp = try {
                    Llm.chat(Prompts.system(s, tools, loaded), snapshot, decls, if (hard) 6000 else 2048, onWait = { report(it) }, deep = hard)
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
                    // "I can't" while only part of the tools was in view: show them all and let it try once more.
                    if (!s.allTools && s.parentId == null && Router.missing(loaded).isNotEmpty() && Router.givesUp(resp.text)) {
                        s.allTools = true
                        lock.withLock { s.messages += Msg("user", listOf(Part.Text(CHECK + "You answered that it can't be done while seeing only part of your tools. Every tool is in your list now. Look through it and do the request if any tool can; say it can't be done only if none can."))); touch(s) }
                        continue
                    }
                    // "The apps are blocking automated access" was never true: a screenshot had failed on this phone.
                    // A made-up reason is worse than none: send it back once to finish or say what really failed.
                    if (!s.blameNudged && s.parentId == null && Honest.blames(resp.text)) {
                        s.blameNudged = true
                        lock.withLock { s.messages += Msg("user", listOf(Part.Text(CHECK + Honest.NUDGE))); touch(s) }
                        continue
                    }
                    // It said it selected, booked or sent something, yet no step of this request did that: never told to the user as fact.
                    if ("claim" !in s.checked && s.parentId == null && (s.order != null || Preflight.kind(lock.withLock { lastSaid(s) }) != null)) {
                        val did = lock.withLock { didSince(s) }
                        val deed = Honest.unbacked(resp.text, did.filter { it.ok }.map { it.name })
                        if (deed != null) {
                            s.checked += "claim"
                            lock.withLock { s.messages += Msg("user", listOf(Part.Text(CHECK + Honest.claimNudge(deed, ScreenDriver.available)))); touch(s) }
                            continue
                        }
                    }
                    // Dirghadarshi mode: a question about things that change is looked up, never answered from memory…
                    if (s.deep && s.parentId == null && "deep-look" !in s.checked) {
                        val asked = lock.withLock { lastSaid(s) }
                        val used = lock.withLock { didSince(s).map { it.name } }
                        if (Deep.needsLookup(asked) && !Deep.lookedUp(used) && used.none { usesScreen(it) }) {
                            s.checked += "deep-look"
                            lock.withLock { s.messages += Msg("user", listOf(Part.Text(CHECK + Deep.LOOKUP_NUDGE))); touch(s) }
                            continue
                        }
                    }
                    // …and the first full answer is read once more, as a critic would, before the user sees it.
                    if (s.deep && s.parentId == null && "deep-review" !in s.checked && Deep.worthReview(resp.text, usedScreen, s.order != null)) {
                        s.checked += "deep-review"
                        lock.withLock { s.messages += Msg("user", listOf(Part.Text(CHECK + Deep.REVIEW_NUDGE))); touch(s) }
                        continue
                    }
                    // …and every figure in it must be in something that was read (or in the user's own words).
                    if (s.deep && s.parentId == null && "deep-facts" !in s.checked && !usedScreen && s.order == null) {
                        val evidence = lock.withLock {
                            s.messages.filter { it.role == "tool" }.flatMap { it.results }.filter { it.name in Deep.lookups }.joinToString("\n") { it.output.toString() } + "\n" + userWords(s).joinToString("\n")
                        }
                        val looked = lock.withLock { didSince(s).any { it.name in Deep.lookups } } || lock.withLock { s.messages.any { m -> m.role == "tool" && m.results.any { it.name in Deep.lookups } } }
                        val loose = if (looked) Deep.unverified(resp.text, evidence) else emptyList()
                        if (loose.isNotEmpty()) {
                            s.checked += "deep-facts"
                            report("Checking the figures…")
                            lock.withLock { s.messages += Msg("user", listOf(Part.Text(CHECK + Deep.factsNudge(loose)))); touch(s) }
                            continue
                        }
                    }
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
                    // A meeting that is not in the calendar is never reported as "scheduled": if the answer leaves that out, it is added.
                    val meetings = lock.withLock { didSince(s).filter { it.name == "calendar_add" }.mapNotNull { it.output } }
                    // A booking that has reached payment: payment apps refuse to pay while screen control is on.
                    val onDemand = ScreenAccess.onDemand(app)
                    val payNote = PayPause.payNote(s.order != null && s.parentId == null && PayPause.speaksOfPaying(resp.text), ScreenDriver.available, onDemand)
                    val answer = Meetings.withProof(resp.text, meetings).let { if (payNote != null && it.isNotBlank()) it.trimEnd() + "\n\n" + payNote else it }
                    if (answer != resp.text) lock.withLock {
                        val i = s.messages.indexOfLast { it.role == "assistant" && it.calls.isEmpty() }
                        if (i >= 0) s.messages[i] = Msg("assistant", listOf(Part.Text(answer))) else s.messages += Msg("assistant", listOf(Part.Text(answer)))
                        touch(s)
                    }
                    finish(s, SessionStatus.DONE, answer.ifBlank { "Done." })
                    return
                }
                s.steps++; stepsThisRun++
                // Independent calls in one reply run at the same time. Anything touching the screen runs one by one, in order.
                val skipped = HashSet<String>()
                suspend fun one(call: Part.Call): Part.Result {
                    val sig = call.name + call.args.toString()
                    if ((sameCount[sig] ?: 0) >= 2 && call.name !in setOf("wait", "ask_user")) { skipped += call.id; return stuck(s, call) }
                    // The AI hands the seat map to the user in its own words ("…in row D or F", rows it made up, on a real
                    // phone): on a seat map the bar says only what is true.
                    if (call.name == "your_turn" && s.parentId == null) runCatching {
                        val o = s.order
                        if (o != null && o.kind == Preflight.Kind.MOVIE.name && o.count in 1..10 && ScreenDriver.available && BookGuard.seatMap(ScreenDriver.peek()) &&
                            !BookGuard.seyalPicksSeats(lock.withLock { requestWords(s) })
                        ) { call.args.put("what", BookGuard.pickSeats(o.count)); s.checked += "handover" }
                    }
                    // The seats the user picked are never thrown away by a step back (real phone: Back three times at "Pay ₹380").
                    if (call.name in BookGuard.leaving && s.parentId == null && s.order != null && ScreenDriver.available) {
                        val refuse = runCatching {
                            val did = lock.withLock { didSince(s) }
                            val picked = did.indexOfLast { it.name == "your_turn" && it.ok }
                            picked >= 0 && BookGuard.keepSeats(call.name, true, did.drop(picked + 1).any { it.name == "order_check" && !it.ok }, ScreenDriver.peek(), s.order, java.time.LocalDateTime.now())
                        }.getOrDefault(false)
                        if (refuse && s.checked.count { it.startsWith("keep") } < 3) {
                            s.checked += "keep" + s.checked.size
                            return lock.withLock {
                                s.state(call.id).apply { status = CallStatus.FAILED; error = "Not done: your seats are selected on this page"; startedAt = System.currentTimeMillis(); endedAt = startedAt }
                                touch(s)
                                Part.Result(call.id, call.name, JSONObject().put("error", BookGuard.KEEP_SEATS), true)
                            }
                        }
                    }
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

    /**
     * The first moves of a booking or food order, run by code (see [Opener]): open the app, get to its search, type
     * what the user asked for, open the right result. Every move is an ordinary step in the chat. It stops at the
     * first screen that is not what it expects, and the AI carries on from there. Returns how many steps it took.
     */
    private suspend fun runOpener(s: Session, kind: Preflight.Kind, byName: Map<String, Tool>): Int {
        val order = s.order ?: return 0
        if (!ScreenDriver.available || s.parentId != null) return 0
        val words = lock.withLock { requestWords(s) }.joinToString(" ")
        val appName = Preflight.appsFor(kind, order.app.ifBlank { words }).firstOrNull() ?: return 0
        val plan = Opener.plan(kind, appName, order) ?: return 0
        var n = 0
        // One step, recorded like any other. Null = it did not work, or the user pressed Stop.
        suspend fun step(name: String, args: JSONObject): JSONObject? {
            val tool = byName[name] ?: return null
            ScreenDriver.freezeIds(s.id)
            val r = runSynthetic(s, Part.Call(newId(), name, args), tool); s.steps++; n++
            if (lock.withLock { s.status == SessionStatus.CANCELLED }) return null
            return r.output.takeIf { !r.isError && it.optBoolean("ok", true) }
        }
        fun els(screen: JSONObject?) = Opener.items(screen?.optString("elements").orEmpty())
        suspend fun look(waitMs: Long): JSONObject? { delay(waitMs); return runCatching { ScreenDriver.read() }.getOrNull() }
        suspend fun tell(text: String) = lock.withLock { s.messages += Msg("user", listOf(Part.Text(CHECK + text))); touch(s) }
        fun tap(it: Item) = JSONObject().put("id", it.id).put("text", it.label.take(60))

        var address: String? = null
        // What was done and why it stopped, in a few words each: left in the chat so a recording or a report shows it.
        val trace = ArrayList<String>()
        /** The film itself was on the screen: tapped. Null = it was not there. */
        var filmTapped: JSONObject? = null
        /**
         * Gets to the app's search box, whatever the app opened on: an address sheet (food), an offer lying over the
         * home page, a page left open last time, or a home page still drawing. True = the search is open.
         * It never presses Back while there is anything else to try: on a real phone that Back closed the app.
         * Every tap meant to open the search is checked: the box must really appear before typing is tried.
         */
        suspend fun toSearch(pkg: String, first: JSONObject?, food: Boolean, film: String? = null): Boolean {
            var screen = first
            var backed = false; var closed = 0; var looks = 0; var searchTaps = 0
            for (round in 0 until 10) {
                val items = els(screen)
                if (film != null) Opener.onScreen(items, film)?.let { f ->
                    trace += "saw \"${f.label}\" on the page and tapped it"
                    filmTapped = step("screen_tap", tap(f))
                    return false
                }
                val row = if (food && address == null) Opener.addressRow(items) else null
                if (row != null) {
                    address = row.label.substringBefore(" · ").take(40)
                    trace += "chose the address \"$address\""
                    step("screen_tap", tap(row)) ?: return false
                    screen = look(1200); looks = 0
                    continue
                }
                // A full-screen advert at launch ("Dismiss", "Skip") is closed first: typing over it went nowhere.
                val close = if (closed < 2 && (screen?.optBoolean("popup") == true || Opener.advert(items))) Opener.closeButton(items) else null
                if (close != null) {
                    closed++
                    trace += "closed a popup (\"${close.label}\")"
                    screen = (step("screen_tap", tap(close)) ?: return false).optJSONObject("screen")
                    continue
                }
                if (items.any { it.isField }) { trace += "the search box is open"; return true }
                if (searchTaps < 2) {
                    val entry = Opener.searchEntry(items)
                    val bar = if (food) "Search for" else "Search"
                    val how = when {
                        entry != null -> { trace += "tapped \"${entry.label}\""; tap(entry) }
                        // A long home page is cut short in the list, yet the bar is on screen: tap it by its words.
                        ScreenDriver.isVisible(bar, exactOnly = !food) -> { trace += "tapped \"$bar\""; JSONObject().put("text", bar) }
                        !food -> Opener.cornerIcon(items)?.let { trace += "tapped the icon in the top right corner"; tap(it) }
                        else -> null
                    }
                    if (how != null) {
                        searchTaps++
                        screen = (step("screen_tap", how) ?: return false).optJSONObject("screen")
                        if (els(screen).none { it.isField }) screen = look(900)
                        if (els(screen).none { it.isField }) screen = look(900)
                        continue
                    }
                }
                // Still drawing (a splash screen takes four or five seconds)? Look again before trying anything else.
                if (looks < 6) { looks++; screen = look(800); continue }
                if (backed) { trace += "found no search on this page"; return false }
                backed = true
                trace += "pressed Back"
                val o = step("screen_back", JSONObject()) ?: return false
                screen = if (ScreenDriver.frontPackage() == pkg) o.optJSONObject("screen") else { trace += "that left the app, so it was opened again"; ScreenDriver.bringToFront(pkg); look(900) }
                looks = 1; searchTaps = 0
            }
            trace += "found no search"
            return false
        }
        /** The film's page: Book tickets, or the two wrong turns. Returns whether booking went on. */
        suspend fun onFilmPage(o: JSONObject): Boolean {
            Detour.note(o.optJSONObject("screen")?.optString("elements").orEmpty())?.let { wrong -> trace += "that page cannot be booked, went back"; step("screen_back", JSONObject()); tell(wrong); return false }
            val book = Opener.bookButton(els(o.optJSONObject("screen"))) ?: Opener.bookButton(els(look(900)))
            if (book == null) { trace += "no Book tickets button on its page"; return false }
            trace += "tapped \"${book.label}\""
            step("screen_tap", tap(book))
            return true
        }
        suspend fun leaveTrace() {
            if (n == 0 || trace.isEmpty()) return
            tell("Seyal did the first steps itself: ${trace.joinToString("; ")}. Carry on from the screen as it is now.")
        }

        when (plan) {
            is Opener.Plan.Link -> { step("open_url", JSONObject().put("url", plan.url)) }
            is Opener.Plan.Movie -> {
                // 1. The film's own page, found on the web and opened in the app: one step, past the splash, the adverts and the search.
                val early = filmLinks.remove(s.id)?.let { job -> withTimeoutOrNull(5000) { runCatching { job.await() }.getOrNull() } }
                val link = if (early != null && early.first.equals(plan.title, true)) early.second else { report("Finding the film's page…"); withTimeoutOrNull(8000) { findFilmLink(plan.title) } }
                if (link != null && step("open_url", JSONObject().put("url", link)) != null) {
                    var page = look(1500); var tries = 0
                    fun text() = page?.optString("elements").orEmpty()
                    while (tries < 5 && Opener.bookButton(els(page)) == null && Detour.note(text()) == null) { tries++; page = look(900) }
                    if (ScreenDriver.frontPackage() == Opener.BMS && Opener.pageIsAbout(els(page), plan.title)) {
                        trace += "opened the film's own page from the web"
                        when {
                            Detour.ended(text()) -> { deadEnds[s.id] = Detour.notRunning(plan.title, "ended"); return n }
                            Detour.upcoming(text()) -> { deadEnds[s.id] = Detour.notRunning(plan.title, "upcoming"); return n }
                        }
                        val book = Opener.bookButton(els(page))
                        if (book != null) { trace += "tapped \"${book.label}\""; step("screen_tap", tap(book)); leaveTrace(); return n }
                        trace += "no Book tickets button on it"
                    } else trace += "the web link did not open the film in the app"
                }
                // 2. Otherwise through the app's own search.
                var o = step("open_app", JSONObject().put("name", plan.app)) ?: return n
                trace += "opened ${plan.app}"
                val open = toSearch(o.optString("package"), o.optJSONObject("screen"), food = false, film = plan.title)
                filmTapped?.let { onFilmPage(it); leaveTrace(); return n }
                if (!open) { leaveTrace(); return n }
                o = step("screen_type", JSONObject().put("text", plan.title)) ?: run { trace += "typing \"${plan.title}\" did not go in"; leaveTrace(); return n }
                trace += "typed \"${plan.title}\""
                var results = Opener.movieResults(els(o.optJSONObject("screen")), plan.title)
                // The list comes from the network: give it a moment before deciding nothing is in cinemas.
                var waits = 0
                while (results.none { it.showing == Opener.Showing.NOW } && waits < 3) { waits++; results = Opener.movieResults(els(look(700)), plan.title) }
                // Two letters find nothing in some search boxes (seen: "OM" left the page on its "Trending" list).
                // The same title with its dots ("O.M") is tried once.
                if (results.isEmpty()) Opener.dotted(plan.title)?.let { alt ->
                    step("screen_type", JSONObject().put("text", alt))?.let { typed ->
                        trace += "nothing for \"${plan.title}\", typed \"$alt\""
                        results = Opener.movieResults(els(typed.optJSONObject("screen")), plan.title)
                        waits = 0
                        while (results.none { it.showing == Opener.Showing.NOW } && waits < 3) { waits++; results = Opener.movieResults(els(look(700)), plan.title) }
                    }
                }
                // The app itself says it has nothing by that name: the film is not running. Said at once.
                if (results.isEmpty() && Detour.nothingFound(runCatching { ScreenDriver.read().optString("elements") }.getOrDefault(""))) {
                    trace += "the app's search has no results"
                    deadEnds[s.id] = Detour.notRunning(plan.title, "none")
                    return n
                }
                when (val c = Opener.chooseMovie(results, plan.title)) {
                    is Opener.Choice.Tap -> {
                        trace += "opened \"${c.result.title}\" (${if (c.result.showing == Opener.Showing.NOW) "in cinemas now" else "the only match"})"
                        o = step("screen_tap", tap(c.result.item)) ?: run { leaveTrace(); return n }
                        onFilmPage(o)
                    }
                    is Opener.Choice.Tell -> { trace += "no result in cinemas matched"; tell(c.note) }
                    Opener.Choice.Leave -> trace += "could not tell which result is the film"
                }
                leaveTrace()
            }
            is Opener.Plan.Food -> {
                val o = step("open_app", JSONObject().put("name", plan.app)) ?: return n
                trace += "opened ${plan.app}"
                val open = toSearch(o.optString("package"), o.optJSONObject("screen"), food = true)
                // Typing goes in whichever way the box accepts; if it accepts none, the user types this one line.
                if (open) {
                    if (step("screen_type", JSONObject().put("text", plan.query)) == null) {
                        if (lock.withLock { s.status == SessionStatus.CANCELLED }) return n
                        trace += "typing did not go in, asked the user to type \"${plan.query}\""
                        step("your_turn", JSONObject().put("what", "Type \"${plan.query}\" in the search box, then tap Done"))
                    } else trace += "typed \"${plan.query}\""
                }
                leaveTrace()
                address?.let { tell("The app asked for a delivery address and \"$it\" was chosen (the one already in use, or the first saved one). Name this address in your final message so the user can check it.") }
            }
        }
        return n
    }

    private suspend fun finish(s: Session, status: SessionStatus, text: String) {
        lock.withLock {
            if (s.status == SessionStatus.CANCELLED) return
            s.status = status
            s.result = shortResult(text)
            // An answer worked out without the AI (the time, a list, "Alarm set") was never written into the chat:
            // only its first line showed in the task list. Every answer is a message the user can read and copy.
            if (status == SessionStatus.DONE && needsBubble(s.messages.lastOrNull { it.role == "assistant" && it.calls.isEmpty() }?.text, s.messages.lastOrNull()?.role, text))
                s.messages += Msg("assistant", listOf(Part.Text(text.trim())))
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
        idleCheck(if (s.parentId == null) (if (status == SessionStatus.DONE) "Done · " else "Stopped · ") + (s.result ?: "") else null, payingNext = s.order != null)
        if (s.parentId == null) {
            val skillId = s.source.takeIf { it.startsWith("skill:") }?.removePrefix("skill:")
            if (skillId != null) runCatching { Skills.noteResult(skillId, status == SessionStatus.DONE, s.result ?: "") }
            // What a scheduled skill has to tell (the mail, the news, the total) IS its result: it arrives in full,
            // whatever the Quiet setting says. A failure is always told.
            if (skillId != null && status == SessionStatus.DONE && wantsAnswer(s.firstCommand)) Notify.result(app, s.title, plain(text).trim().take(1500), s.id, aboutWhat(s))
            else if (!Settings.quiet || status != SessionStatus.DONE) Notify.line(app, "${s.title}: ${s.result}", s.id)
            if ((Settings.speak || (Settings.simpleMode && s.source == "user")) && status == SessionStatus.DONE) Speech.say(app, text)
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
        // (Something typed while a task runs is part of that task, not a new request.)
        val asks = msgs.indices.filter { fromUser(msgs[it]) && !msgs[it].text.startsWith(MIDTASK) }
        // The request before this one stays whole when it was short, or when the new words point back at it
        // ("email it", "retry"). A long one that is not pointed at is finished business like the rest.
        val last = asks.lastOrNull(); val prev = asks.getOrNull(asks.size - 2)
        val keepFrom = History.keepFrom(prev, last, last?.let { msgs[it].text }.orEmpty()) ?: 0
        for (i in 0 until keepFrom) msgs[i] = History.shrink(msgs[i]) { id -> s.calls[id]?.line }
        // Requests older than that are collapsed to what was asked and what came of it: a chat used for a whole
        // morning (tickets, then shoes, then biryani) carried 90 old steps into every new step and hit the AI limit.
        val collapsed = History.collapse(msgs, keepFrom, { fromUser(it) && !it.text.startsWith(MIDTASK) }, s.calls.mapValues { it.value.line })
        msgs.clear(); msgs.addAll(collapsed)
        fun size(m: Msg) = m.parts.sumOf { p -> if (p is Part.Result) p.output.toString().length + (p.image?.b64?.length ?: 0) / 8 else if (p is Part.Text) p.text.length else if (p is Part.Call) p.args.toString().length else 0 }
        var total = msgs.sumOf { size(it) }
        if (total < 40_000) return msgs
        // Positions in msgs (old requests may have been collapsed above, so s.messages' positions no longer apply).
        for (i in msgs.indices.filter { msgs[it].role == "tool" }.dropLast(4)) {
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

    /**
     * The seat-count sheet of a booking app, set by code: tap the user's number, then the button that goes on.
     * Done at most twice per request; anything unexpected is left to the AI. Returns the steps taken.
     */
    private suspend fun setSeatCount(s: Session, screen: String): Int {
        val order = s.order ?: return 0
        if (order.count !in 1..10 || s.checked.count { it.startsWith("seatsheet") } >= 2) return 0
        if (BookGuard.seatSheet(Opener.items(screen), order.count) == null) return 0
        val tapTool = Toolbox.get("screen_tap") ?: return 0
        s.checked += "seatsheet" + s.checked.size
        var n = 0
        // Read for real now, and make its numbers the ones the taps below go by.
        val now = runCatching { ScreenDriver.read().optString("elements") }.getOrDefault("")
        ScreenDriver.freezeIds(s.id)
        val sheet = BookGuard.seatSheet(Opener.items(now), order.count) ?: return 0
        var after = now
        var byEye: String? = null
        if (sheet.number != null) {
            report("Setting ${order.count} ticket${if (order.count == 1) "" else "s"}…")
            val r = runSynthetic(s, Part.Call(newId(), "screen_tap", JSONObject().put("id", sheet.number.id)), tapTool); s.steps++; n++
            if (r.isError) return n
            after = r.output.optJSONObject("screen")?.optString("elements") ?: runCatching { ScreenDriver.read().optString("elements") }.getOrDefault("")
            ScreenDriver.freezeIds(s.id)
        } else if (!sheet.alreadySet) {
            // The sheet's numbers are not handed over as text (BookMyShow draws them): read the row 1 … 10 by eye and
            // make the user's number the highlighted one. If even that cannot be done, the AI carries on as before.
            report("Setting ${order.count} ticket${if (order.count == 1) "" else "s"}…")
            byEye = runCatching { ScreenDriver.pickCount(order.count) }.getOrNull()?.takeIf { it == "already" || it == "set" } ?: return n
            after = runCatching { ScreenDriver.read().optString("elements") }.getOrDefault("")
            ScreenDriver.freezeIds(s.id)
        }
        val go = BookGuard.seatSheet(Opener.items(after), order.count)?.go ?: return n
        val r = runSynthetic(s, Part.Call(newId(), "screen_tap", JSONObject().put("id", go.id)), tapTool); s.steps++; n++
        if (byEye != null) r.output.put("count", "Before this tap Seyal looked at the \"How many seats?\" row: ${order.count} was " + (if (byEye == "already") "already the highlighted number." else "pressed and is now the highlighted number."))
        return n
    }

    /**
     * The steps of a booking that need no judgement, done by code at the top of each round, on the screen as it is now
     * (not as the last step's result described it: a sheet that opened a second later was missed that way, and the
     * AI was asked about it three times, 35 seconds, on a real phone):
     * 1. the "How many seats?" sheet → the user's number, then Select Seats;
     * 2. the seat map, with the user's day and number of tickets → handed to the user to pick seats;
     * 3. a strip of dates → the user's day made the selected one (pressed where it shows, and checked).
     * Each only when the screen is exactly that; anything else is left to the AI. Returns how many steps it took.
     */
    private suspend fun bookSteps(s: Session): Int {
        val order = s.order ?: return 0
        // Film bookings only: these three screens were learnt from recordings of a real one.
        if (!ScreenDriver.available || order.kind != Preflight.Kind.MOVIE.name) return 0
        // Only right after a step on the screen.
        val last = lock.withLock { s.messages.lastOrNull()?.takeIf { it.role == "tool" }?.results?.lastOrNull() } ?: return 0
        if (!usesScreen(last.name) && last.name !in setOf("open_url", "open_app", "your_turn")) return 0
        // Looking only: the numbered list the AI was last given must keep its meaning (ScreenDriver.peek).
        suspend fun look() = runCatching { ScreenDriver.peek() }.getOrDefault("")
        var now = look()
        fun loaded(t: String) = BookGuard.seatSheet(Opener.items(t), order.count.coerceIn(1, 10)) != null || BookGuard.seatMap(t)
        // A show time was just tapped: its sheet or seat map takes a moment. Three seconds here cost less than an AI question about a page still loading.
        if (last.name == "screen_tap" && BookGuard.isShowTime(last.output.optString("tapped")) && !loaded(now)) {
            report("Waiting for the seats…")
            for (i in 1..4) { delay(750); now = look(); if (loaded(now)) break }
        }
        // 0. The user has picked the seats and the Pay button is up, for the right day, number and price: the end.
        suspend fun readyOn(screen: String): Boolean {
            if ("ready" in s.checked || BookGuard.seyalPicksSeats(lock.withLock { requestWords(s) })) return false
            val did = lock.withLock { didSince(s) }
            if (did.none { it.name == "your_turn" && it.ok }) return false
            val time = did.lastOrNull { it.name == "screen_tap" && it.ok && BookGuard.isShowTime(it.output?.optString("tapped").orEmpty()) }?.output?.optString("tapped").orEmpty()
            val text = BookGuard.ready(screen, order, time, java.time.LocalDateTime.now()) ?: return false
            s.checked += "ready"; readyToPay[s.id] = text
            return true
        }
        if (readyOn(now)) return 0
        var n = 0
        // 1. How many.
        n += setSeatCount(s, now)
        if (n > 0) { delay(400); now = look() }
        // 2. The seats are the user's to pick.
        if (order.count in 1..10 && "handover" !in s.checked && BookGuard.seatMap(now) &&
            SeatScreen.problem(now, order, java.time.LocalDateTime.now()) == null && !BookGuard.seyalPicksSeats(lock.withLock { requestWords(s) })
        ) {
            val turn = Toolbox.get("your_turn") ?: return n
            s.checked += "handover"
            runSynthetic(s, Part.Call(newId(), "your_turn", JSONObject().put("what", BookGuard.pickSeats(order.count))), turn); s.steps++; n++
            // The user is back: with their seats picked and the Pay button up, that is the end (no AI round in between).
            delay(300); readyOn(look())
            return n
        }
        // 3. The day.
        val want = runCatching { java.time.LocalDate.parse(order.date) }.getOrNull()
        // Only on a show list (two or more show times on the screen), once per visit to it.
        val showList = Regex("\\b\\d{1,2}:\\d{2}\\s?(?:AM|PM)\\b", RegexOption.IGNORE_CASE).findAll(now).count() >= 2
        val justOpened = Regex("book tickets?", RegexOption.IGNORE_CASE).containsMatchIn(last.output.optString("tapped"))
        if (n == 0 && want != null && "day-ok" !in s.checked && "day-na" !in s.checked && last.name != "screen_day" && s.checked.count { it.startsWith("dayset") } < 4 && !BookGuard.seatPage(now) && (showList || justOpened)) {
            // "Book tickets" was just tapped: the show list with its dates takes a moment to load.
            if (!showList && justOpened) delay(1500)
            val dayTool = Toolbox.get("screen_day") ?: return n
            s.checked += "dayset" + s.checked.size
            report("Checking the day…")
            val r = runSynthetic(s, Part.Call(newId(), "screen_day", JSONObject().put("date", want.toString())), dayTool); s.steps++; n++
            // Once per visit to the show list: the day was set or found right ("day-ok"), or the dates cannot be read
            // here ("day-na": the AI was told to leave them alone). The seat screen's header is read in any case.
            s.checked += if (!r.isError && r.output.optBoolean("ok")) "day-ok" else "day-na"
        }
        return n
    }

    /** A booking that has reached the Pay button with the user's own seats: the words to finish with, by chat id. */
    private val readyToPay = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** A request that code has found cannot go on (the film is not running): the words to finish with, by chat id. */
    private val deadEnds = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** The film's own page, looked for on the web while the booking details are still being noted: chat id → (title, link). */
    private val filmLinks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Pair<String, String?>>>()

    private suspend fun findFilmLink(title: String): String? =
        runCatching { Opener.filmLink(com.seyal.app.tools.WebTools.links(Opener.filmSearch(title, Settings.homeCity)), title, Settings.homeCity) }.getOrNull()

    /** What Seyal is doing this moment, in words ("Noting your details…", "Waiting 20 s for the AI's limit"): shown under the chat. */
    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress

    /** "Screen control only while Seyal works": switched on here, at the start of a request that may need it. */
    private suspend fun ensureScreen() {
        if (BuildConfig.FLAVOR != "full" || ScreenDriver.available || !ScreenAccess.onDemand(app)) return
        report("Switching screen control on…")
        runCatching { ScreenAccess.on(app) }
    }

    /**
     * Before a request starts: is the screen usable if the request needs it? Everyday commands (torch, call, "pause
     * screen control") need nothing. Otherwise a resting screen control is switched on where Seyal can do that itself.
     * A booking or an order with screen control off, on a phone where it cannot: the user is asked at once, and either
     * switches it on or gets the app opened with an honest "the rest is yours". (On a real phone Seyal instead handed
     * every step to the user and then invented the seats.) False = the request has been answered or stopped here.
     */
    private suspend fun screenReady(s: Session, said: String): Boolean {
        if (BuildConfig.FLAVOR != "full" || ScreenDriver.available) return true
        if (s.parentId != null) return true
        if (s.source != "user") { ensureScreen(); return true }
        if (said.isBlank() || Quick.match(said) != null || runCatching { Basics.plan(said) }.getOrNull() != null) return true
        ensureScreen()
        val kind = Preflight.kind(said)
        if (ScreenDriver.available || kind == null) return true
        val ask = Toolbox.get("ask_user") ?: return true
        val appName = runCatching { Preflight.appsFor(kind, said).firstOrNull() }.getOrNull().orEmpty()
        val q = Part.Call(newId(), "ask_user", JSONObject().put("question", PayPause.needQuestion(appName)).put("options", org.json.JSONArray(listOf(PayPause.TURN_ON, PayPause.JUST_OPEN))))
        val a = runSynthetic(s, q, ask).output.optString("answer"); s.steps++
        if (lock.withLock { s.status == SessionStatus.CANCELLED }) return false
        var on = false
        if (PayPause.wantsOn(a)) {
            report("Switching screen control on…")
            on = ScreenAccess.on(app)
            if (!on && ScreenAccess.openSettings(app)) { report("Waiting for you to switch Seyal on in Accessibility…"); on = ScreenAccess.waitForUser() }
            // Back from the Settings page to the chat, where the questions about the booking come next.
            if (on) runCatching { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        }
        if (on) return true
        if (appName.isNotBlank()) Toolbox.get("open_app")?.let { runSynthetic(s, Part.Call(newId(), "open_app", JSONObject().put("name", appName)), it); s.steps++ }
        finish(s, SessionStatus.DONE, (if (appName.isNotBlank()) "Opened $appName. " else "") + PayPause.stillOff(appName))
        return false
    }

    private fun report(text: String) {
        _progress.value = plain(text)
        val g = activity.incrementAndGet()
        ScreenDriver.status(plain(text))
        AgentService.working(app, text, g)
    }

    /** When nothing is open any more: say the result on the pill for a moment, then clear up. */
    private fun idleCheck(lastWords: String?, payingNext: Boolean = false) {
        scope.launch {
            val g = lock.withLock { if (live.values.any { it.status.isOpen }) return@launch; activity.get() }
            AgentService.idle(g)
            _progress.value = ""
            if (lastWords != null) { ScreenDriver.status(plain(lastWords).take(70), busy = false); delay(if (payingNext) 1200 else 4500) }
            lock.withLock { if (activity.get() == g && live.values.none { it.status.isOpen }) ScreenDriver.status(null) }
            // "Only while Seyal works": nothing is running any more, so screen control goes off (the user may be about to pay).
            if (ScreenDriver.available && ScreenAccess.onDemand(app) && lock.withLock { activity.get() == g && live.values.none { it.status.isOpen } }) runCatching { ScreenAccess.off(app) }
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
        // A message to many people is shown in full and approved every single time, whatever was allowed before.
        if (mustAsk(tool.name)) level = Level.ASK
        // The user never said to contact anyone, yet the AI is about to (a bill split that "helpfully" texts the
        // friends): that message is always shown first, even to someone on the always-allow list.
        if (level == Level.AUTO && tool.group == "comms" && tool.sideEffect && s.source == "user" && s.parentId == null && !lock.withLock { asksToContact(userWords(s).joinToString("\n")) }) level = Level.ASK
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
                if (target != null) Settings.allow(tool.name, target) else if (!mustAsk(tool.name)) Settings.setLevel(tool.name, Level.AUTO)
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
        // In a booking: never a seat (the user's choice), never a date chip for another day than the user's.
        if (s.order != null && s.parentId == null && tool.name in setOf("screen_tap", "screen_long_press")) {
            val name = call.args.optString("text").ifBlank { call.args.optInt("id", 0).takeIf { it > 0 }?.let { ScreenDriver.labelFor(it) }.orEmpty() }
            BookGuard.beforeTap(name, s.order?.date.orEmpty(), java.time.LocalDateTime.now())?.let { why ->
                return lock.withLock { fail(why).also { touch(s) } }
            }
        }
        if (usesScreen(tool.name) && ScreenDriver.available && !ScreenDriver.claim(s.id)) {
            return lock.withLock { fail("Another Seyal task is using the screen right now. Wait for it (or tell the user), then try again.").also { touch(s) } }
        }
        // The user opened Seyal meanwhile (to approve, answer or type): go back to the app first.
        if (tool.group == "screen") backToWork(s)
        val frontBefore = if (tool.group == "screen" && ScreenDriver.available) ScreenDriver.frontPackage() else null
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

        // Wrong turns, caught the moment they happen (both were seen on a real phone, and cost two minutes):
        if (tool.group == "screen") (result as? ToolResult.Done)?.let { r ->
            val now = ScreenDriver.frontPackage()
            if (Detour.strayed(tool.name, frontBefore, now)) {
                // 1. a tap in an ordering app that opened a browser or a store was an advert: come straight back.
                report("That was an advert · going back")
                var screen = runCatching { ScreenDriver.global("back").optJSONObject("screen") }.getOrNull()
                if (ScreenDriver.frontPackage() != frontBefore) { runCatching { ScreenDriver.bringToFront(frontBefore!!) }; screen = ScreenDriver.snapshotAfter(700) }
                r.output.remove("note"); r.output.remove("pressed")
                r.output.put("ok", false).put("error", Detour.strayNote(ScreenDriver.appLabel(app, frontBefore!!), ScreenDriver.appLabel(app, now)))
                screen?.let { r.output.put("screen", it) }
            } else if (s.order?.kind == Preflight.Kind.MOVIE.name) {
                // 2. the page of a film that is not out yet, or no longer running: nothing to book there, however far it is scrolled.
                val page = r.output.optJSONObject("screen")?.optString("elements").orEmpty()
                Detour.note(page)?.let { r.output.put("wrong_turn", it) }
                // The app's search has nothing by that name. Told once; if the AI keeps looking, the request ends here.
                // (On a real phone it retyped, switched tabs and scrolled for 18 steps for a film from years ago.)
                if (Detour.nothingFound(page) && !s.checked.add("nofind")) deadEnds[s.id] = Detour.notRunning(s.order?.item.orEmpty(), "none")
                // On the seat map and its count sheet: is this the user's day and the user's number of tickets? Said at once,
                // not after six seats are selected. (Seen: Sunday the 22nd and "6 Tickets" for a request of 2 on the 20th.)
                if (!r.output.has("wrong_turn") && s.order != null && BookGuard.seatPage(page)) {
                    SeatScreen.problem(page, s.order, java.time.LocalDateTime.now())?.let { r.output.put("wrong_turn", "Before anything else on this screen: $it"); s.checked.remove("day-ok"); s.checked.remove("day-na") }
                }
            }
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
            Routines.clear(); Inbox.clear(); Watchers.clear(); Ledger.clear(); Parcels.clear(); Past.clear(); Coach.clear(); Usual.clear(); Khata.clear(); Habits.clear()
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
                // A scheduled news read ("gold news at 11:09 PM"): the headlines as read ARE the answer. An AI turn would
                // squeeze them into two lines.
                val told = if (!ok) null else lock.withLock {
                    s.messages.lastOrNull { it.role == "tool" }?.results?.lastOrNull()?.takeIf { it.name == "awareness" && !it.isError }?.output?.optString("say")?.takeIf { it.isNotBlank() }
                }
                if (told != null) {
                    lock.withLock { if (s.messages.lastOrNull()?.role == "user") s.messages.removeAt(s.messages.size - 1) }
                    finish(s, SessionStatus.DONE, told)
                    return@launch
                }
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
    /** The user's words point back at the request just before ("email it", "retry", "same again", "continue"). */
    fun pointsBack(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.length <= 25) return true
        return Regex(
            "\\b(it|them|those|same|again|retry|continue|instead|another one|one more|previous|last one|above|the (file|ppt|pdf|document|doc|link|photo|picture|list|result|answer|summary|plan))\\b" +
                "|\\b(send|mail|email|share|open|play|book|order|buy|save|forward|delete|print|read|call|message|use|add|change|make) (this|that)\\b|^(and|but|now|then|no|yes|ok|also)\\b",
        ).containsMatchIn(t)
    }

    /**
     * Where the part of the chat that is kept whole begins: at the request before this one ([prev]) when that one
     * was short (at most [SMALL] messages) or [said] points back at it; otherwise at this request ([last]).
     */
    fun keepFrom(prev: Int?, last: Int?, said: String): Int? {
        if (last == null) return null
        if (prev == null) return last
        return if (last - prev <= SMALL || pointsBack(said)) prev else last
    }
    const val SMALL = 14

    /**
     * Requests that start before [keepFrom] become two messages each: what the user said, and one line of what came
     * of it. Everything from [keepFrom] on is kept as it is. [isAsk]: a message is a new request from the user.
     */
    fun collapse(msgs: List<Msg>, keepFrom: Int, isAsk: (Msg) -> Boolean, lines: Map<String, String?>): List<Msg> {
        val out = ArrayList<Msg>()
        var i = 0
        while (i < msgs.size) {
            if (i < keepFrom && isAsk(msgs[i])) {
                var end = i + 1
                while (end < msgs.size && end < keepFrom && !isAsk(msgs[end])) end++
                out += msgs[i]
                if (end - i > 1) outcome(msgs.subList(i + 1, end), lines)?.let { out += Msg("assistant", listOf(Part.Text(it)), msgs[i].at) }
                i = end
            } else { out += msgs[i]; i++ }
        }
        return out
    }

    /**
     * One line for a finished earlier request: its final answer if there was one, else the last steps' summaries.
     * [lines]: each call's one-line summary by call id. Null when nothing happened.
     */
    fun outcome(span: List<Msg>, lines: Map<String, String?>): String? {
        val said = span.lastOrNull { it.role == "assistant" && it.calls.isEmpty() && it.text.isNotBlank() }?.text
        val steps = span.flatMap { it.calls }.mapNotNull { c -> lines[c.id]?.takeIf { it.isNotBlank() } }
        val body = said?.take(300) ?: steps.takeLast(3).joinToString("; ").take(300).ifBlank { return null }
        // What a later request may point back to (the file that was made, the link, the number) is carried along,
        // so "send the PPT to Ram" still finds it.
        val kept = LinkedHashMap<String, String>()
        for (m in span.asReversed()) for (r in m.results) if (!r.isError) r.output.keys().forEach { k ->
            val v = r.output.opt(k)
            if (k in keep && k !in kept && kept.size < 10 && (v is Number || (v is String && v.isNotBlank() && v.length <= 200))) kept[k] = v.toString()
        }
        return "(Earlier request, finished; ${steps.size} steps.) $body" + (if (kept.isEmpty()) "" else "\nFrom it: " + kept.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

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
        // A file attached to an earlier request is not sent again with every later one.
        "user" -> if (m.parts.none { it is Part.Image }) m else Msg("user", m.parts.map { p -> if (p is Part.Image) Part.Text("[a file was attached here earlier: ${p.mime}]") else p }, m.at)
        else -> m
    }
}
