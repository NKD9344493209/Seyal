package com.seyal.app.ai

import android.util.Log
import com.seyal.app.data.Msg
import com.seyal.app.data.Part
import com.seyal.app.data.Settings
import com.seyal.app.data.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * busy = worth trying again or another model. slow = timed out. quota = a rate limit (per minute unless [daily]).
 * daily = the model's quota for the day is used up. missing = this key can't use that model (404).
 */
class LlmException(
    message: String,
    val busy: Boolean = false,
    val slow: Boolean = false,
    val quota: Boolean = false,
    val retryAfterMs: Long = 0L,
    val daily: Boolean = false,
    val missing: Boolean = false,
) : IOException(message)

/** A tool the model may call. [params] is a JSON-schema object: {type: object, properties: {...}, required: [...]}. */
class ToolDecl(val name: String, val description: String, val params: JSONObject)

/** What the model said back: some text, and/or tool calls to run. */
class LlmResponse(val text: String, val calls: List<Part.Call>, val tokens: Int, val model: String, val stop: String)

/**
 * Talks to Gemini, Claude or any OpenAI-compatible server with the user's own key, using each
 * provider's native tool calling. One conversation format ([Msg]/[Part]) in, one response out.
 */
object Llm {
    private const val TAG = "SeyalAI"

    /** Gemini models to use, best first: the fast "lite" model does the work; plain Flash is the last resort (its free limit is tiny). */
    private val geminiFallbacks = listOf("gemini-flash-lite-latest", "gemini-flash-latest")

    /**
     * More Flash models this key can use, each with its own free limits: the ones Google names when it
     * retires a model ("use models/gemini-3.5-flash-lite"), and the ones its model list shows. Kept on disk.
     */
    private val extraModels: MutableList<String> by lazy {
        java.util.concurrent.CopyOnWriteArrayList(Settings.aiExtraModels.split(',').map { it.trim() }.filter { it.isNotBlank() })
    }
    @Volatile private var discovered = false

    private fun addModel(name: String) {
        val m = name.removePrefix("models/").trim()
        if (m.isBlank() || m in extraModels || m in geminiFallbacks) return
        extraModels += m
        Settings.aiExtraModels = extraModels.joinToString(",")
        log("added model $m")
    }

    /** Which Flash models a model-list entry is worth trying: text chat with tools, not image/audio/live/embedding ones. */
    internal fun usableFlash(name: String, methods: List<String>): Boolean {
        val n = name.lowercase()
        if (!n.contains("flash") || "generateContent" !in methods) return false
        return listOf("image", "tts", "audio", "live", "embed", "native", "exp", "computer", "robotics").none { n.contains(it) }
    }

    /** Asks Google which models this key can use (once per app start, in the background). */
    private fun discoverModels() {
        if (discovered || Settings.provider != "gemini") return
        discovered = true
        runCatching {
            val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("x-goog-api-key", Settings.apiKey)
            }
            val text = try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
            val arr = JSONObject(text).optJSONArray("models") ?: return
            val found = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val methods = o.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                val name = o.optString("name").removePrefix("models/")
                if (usableFlash(name, methods) && !name.endsWith("-latest")) found += name
            }
            // Lite models first (bigger free limits), then the rest; newest first within each.
            found.sortedWith(compareBy<String>({ !it.contains("lite") }, { it.contains("preview") }).thenByDescending { it }).take(6).forEach { addModel(it) }
        }.onFailure { log("model list failed: ${it.message}") }
    }

    /** A model that is resting (busy, hung, or at a limit) is skipped until this time; [coolWhy] says why. */
    private val coolUntil = ConcurrentHashMap<String, Long>()
    private val coolWhy = ConcurrentHashMap<String, String>()

    private fun rest(model: String, untilMs: Long, why: String) { coolUntil[model] = untilMs; coolWhy[model] = why }
    @Volatile private var lastKey = 0

    /**
     * Pacing. Free keys allow only so many requests a minute per model (15 for Flash-Lite, 5 for Flash).
     * Running into that limit costs a failed request and a long wait, so Seyal keeps count itself:
     * it knows when a model has room, uses another model meanwhile, and otherwise waits exactly as
     * long as needed. Limits are learnt from Google's own error details when one is hit.
     */
    internal class Pace(@Volatile var perMinute: Int) { val recent = java.util.ArrayDeque<Long>() }
    private val paces = ConcurrentHashMap<String, Pace>()
    /** The real model behind a name ("gemini-flash-lite-latest" → "gemini-3.5-flash-lite"): they share one limit. */
    private val realName = ConcurrentHashMap<String, String>()

    internal fun defaultPerMinute(model: String): Int = when {
        Settings.provider != "gemini" -> 10_000
        model.contains("lite") -> 15
        else -> 5
    }

    private fun pace(model: String): Pace { val real = realName[model] ?: model; return paces.getOrPut(real) { Pace(defaultPerMinute(real)) } }

    /** When [model] can take one more request: now or earlier = it has room. */
    internal fun roomAt(model: String, now: Long): Long {
        val rest = coolUntil[model] ?: 0L
        val p = pace(model)
        val window = synchronized(p) {
            while (p.recent.isNotEmpty() && p.recent.peekFirst()!! < now - 60_000) p.recent.pollFirst()
            windowRoomAt(p.recent.toList(), p.perMinute, now)
        }
        return maxOf(rest, window)
    }

    /**
     * Given when the last requests went out ([recent], oldest first) and the per-minute limit: the time the
     * next request may go (0 = now). Keeps one below the limit: Google's minute and ours don't start together.
     */
    internal fun windowRoomAt(recent: List<Long>, perMinute: Int, now: Long): Long {
        val live = recent.filter { it >= now - 60_000 }
        val cap = (perMinute - 1).coerceAtLeast(1)
        return if (live.size >= cap) live[live.size - cap] + 60_500 else 0L
    }

    private fun used(model: String, at: Long) { val p = pace(model); synchronized(p) { p.recent.addLast(at) } }

    /** Test hook: forget all pacing. */
    internal fun resetPacing() { paces.clear(); realName.clear(); coolUntil.clear(); coolWhy.clear() }
    internal fun noteUsed(model: String, at: Long) = used(model, at)
    internal fun learnLimit(model: String, perMinute: Int) { pace(model).perMinute = perMinute.coerceAtLeast(1) }

    /** How little each model may "think" before answering: minimal → low → (none, for models without the setting). Moves right when a model rejects a level. */
    private val thinkLevels = listOf("minimal", "low", null)
    private val thinkIndex = ConcurrentHashMap<String, Int>()

    /** Gemini's free daily limits start again at midnight in California. */
    fun quotaResetAt(): Long {
        val la = java.time.ZoneId.of("America/Los_Angeles")
        return java.time.ZonedDateTime.now(la).toLocalDate().plusDays(1).atStartOfDay(la).toInstant().toEpochMilli()
    }

    private fun clock(ms: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("h:mm a").format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))

    /** The models to try, best first: the chosen one, the other lite models, then the rest. Names for the same real model count once. */
    private fun models(): List<String> {
        val chosen = Settings.effectiveModel()
        if (Settings.provider != "gemini") return listOf(chosen)
        val rest = (geminiFallbacks + extraModels).filter { it != chosen }
        val ordered = listOf(chosen) + rest.filter { it.contains("lite") } + rest.filter { !it.contains("lite") }
        return ordered.distinctBy { realName[it] ?: it }
    }

    /** A new key or provider has its own limits: forget what the old one ran into. Daily rests survive restarts. */
    private fun syncKey() {
        val keyNow = (Settings.provider + Settings.apiKey).hashCode()
        if (keyNow == lastKey) return
        coolUntil.clear(); coolWhy.clear(); paces.clear(); discovered = false
        if (lastKey == 0 && Settings.provider == "gemini") runCatching {
            // First call since the app started: bring back daily rests for this same key.
            val o = JSONObject(Settings.aiRests)
            if (o.optInt("key") == keyNow) o.optJSONObject("rests")?.let { r -> r.keys().forEach { m -> val until = r.optLong(m); if (until > System.currentTimeMillis()) rest(m, until, "daily") } }
        }
        lastKey = keyNow
    }

    private fun saveDailyRests() = runCatching {
        val r = JSONObject(); coolWhy.filter { it.value == "daily" }.keys.forEach { m -> r.put(m, coolUntil[m] ?: 0L) }
        Settings.aiRests = JSONObject().put("key", lastKey).put("rests", r).toString()
    }

    /**
     * Shown before the user types: why the AI can't answer right now and when it can again, or null when
     * it is ready (a wait of a few seconds for the per-minute limit doesn't count).
     */
    fun limitNote(now: Long = System.currentTimeMillis()): String? {
        if (!Settings.hasKey) return null
        syncKey()
        val usable = models().filter { coolWhy[it] != "missing" }
        if (usable.isEmpty()) return null
        val back = usable.minOf { roomAt(it, now) }
        if (back - now < 8_000) return null
        val resting = usable.filter { (coolUntil[it] ?: 0L) > now }
        val whenBack = if (back - now < 90_000) "in ${(back - now) / 1000 + 1} s" else "at ${clock(back)}"
        return when {
            resting.size == usable.size && resting.all { coolWhy[it] == "daily" } -> "Today's free AI limit is used up · back at ${clock(back)}"
            resting.size == usable.size && resting.none { coolWhy[it] == "minute" } -> "The AI is overloaded · trying again $whenBack"
            else -> "AI limit reached · back $whenBack"
        }
    }

    /** Set for one request: the model must answer with a tool call (used to make it ask before it acts). */
    private val mustCall = ThreadLocal<Boolean>()

    /**
     * One answer from the AI. Picks the best model that has room right now; when none has, waits exactly until
     * the first one does (telling [onWait]) for up to about three minutes. A hung or overloaded model rests and
     * the next one answers. [forceTool]: the reply must be a tool call.
     */
    suspend fun chat(
        system: String, messages: List<Msg>, tools: List<ToolDecl>, maxTokens: Int = 2048,
        forceTool: Boolean = false, onWait: (String) -> Unit = {},
    ): LlmResponse = withContext(Dispatchers.IO) {
        if (!Settings.hasKey) throw LlmException("No AI key yet. Add one in Control → AI.")
        syncKey()
        if (!discovered && Settings.provider == "gemini") kotlin.concurrent.thread(isDaemon = true) { discoverModels() }
        val chosen = Settings.effectiveModel()
        var last: LlmException? = null
        val started = System.currentTimeMillis()
        val busyTries = HashMap<String, Int>()
        var attempts = 0
        while (attempts < 14) {
            val now = System.currentTimeMillis()
            val all = models().filter { coolWhy[it] != "missing" || (coolUntil[it] ?: 0L) <= now }
            val ready = all.firstOrNull { roomAt(it, now) <= now }
            // The quick "lite" model is the one to use. If it only needs a few seconds, wait for it rather than
            // switch to a bigger model that is slower, sometimes hangs, and has a tiny free limit.
            val liteAt = if (Settings.provider == "gemini") all.filter { it.contains("lite") }.minOfOrNull { roomAt(it, now) } else null
            val model = if (ready != null && !ready.contains("lite") && liteAt != null && liteAt - now <= 15_000) null else ready
            if (model == null) {
                // No model has room. A per-minute limit or a short rest clears soon: wait for exactly that.
                val soonest = (if (ready != null) liteAt else all.filter { coolWhy[it] != "daily" || (coolUntil[it] ?: 0L) <= now }.minOfOrNull { roomAt(it, now) }) ?: break
                val wait = soonest - now
                if (wait > 90_000 || now - started + wait > 200_000) break
                if (wait > 1500) onWait("AI limit · continuing in ${(wait + 999) / 1000} s")
                delay(wait.coerceAtLeast(100))
                continue
            }
            attempts++
            val t0 = System.currentTimeMillis()
            used(model, t0)
            Settings.aiRequestsToday = Settings.aiRequestsToday + 1
            try {
                mustCall.set(forceTool && tools.isNotEmpty())
                val out = try {
                    when (Settings.provider) {
                        "anthropic" -> anthropic(system, messages, tools, maxTokens, model)
                        "openai" -> openai(system, messages, tools, maxTokens, model)
                        else -> gemini(system, messages, tools, maxTokens, model)
                    }
                } finally { mustCall.remove() }
                log("$model: ok in ${System.currentTimeMillis() - t0} ms, ${out.tokens} tokens, ${out.calls.size} calls")
                if (coolWhy.remove(model) == "daily") saveDailyRests()
                coolUntil.remove(model)
                Settings.tokensUsed = Settings.tokensUsed + out.tokens
                return@withContext out
            } catch (e: LlmException) {
                last = e
                val end = System.currentTimeMillis()
                log("$model: failed in ${end - t0} ms (busy=${e.busy}, quota=${e.quota}, daily=${e.daily}, slow=${e.slow}): ${e.message?.take(160)}")
                when {
                    // A model this key can't use: skip it, and try the one Google names instead, if any.
                    e.missing && model != chosen -> {
                        rest(model, end + 24 * 3_600_000L, "missing")
                        Regex("use models/([A-Za-z0-9._-]+)").find(e.message ?: "")?.groupValues?.get(1)?.trimEnd('.')?.let { addModel(it) }
                    }
                    !e.busy -> throw e
                    e.quota && e.daily -> { rest(model, quotaResetAt(), "daily"); saveDailyRests() }
                    e.quota -> rest(model, end + e.retryAfterMs.coerceIn(4_000, 65_000), "minute")
                    // It hung: let another model answer for a couple of minutes.
                    e.slow -> rest(model, end + 2 * 60_000L, "busy")
                    else -> {
                        // "Overloaded" (503) spikes usually clear in seconds: a short rest, longer each time.
                        val n = (busyTries[model] ?: 0) + 1; busyTries[model] = n
                        rest(model, end + (if (n == 1) 1500L else if (n == 2) 4000L else 60_000L), "busy")
                    }
                }
            }
        }
        val now = System.currentTimeMillis()
        val usable = models().filter { coolWhy[it] != "missing" }
        throw LlmException(
            when {
                usable.isNotEmpty() && usable.all { coolWhy[it] == "daily" && (coolUntil[it] ?: 0L) > now } ->
                    "Today's free AI limit on this key is used up. It starts again at ${clock(quotaResetAt())}. Until then you can add a key from another provider in Control → AI."
                last?.quota == true -> "The AI's per-minute limit is still reached. Try again in a minute."
                last?.slow == true -> "The AI isn't answering right now (it timed out). Try again in a minute."
                else -> "The AI is overloaded right now. Try again in a minute."
            },
            busy = true,
            quota = last?.quota == true,
        )
    }

    /** Plain text in, plain text out (titles, summaries, the key test). */
    suspend fun complete(system: String, user: String, maxTokens: Int = 512): String =
        chat(system, listOf(Msg("user", listOf(Part.Text(user)))), emptyList(), maxTokens).text

    private fun log(msg: String) { runCatching { Log.i(TAG, msg) } }

    // ---------------------------------------------------------------- Gemini

    private fun gemini(system: String, messages: List<Msg>, tools: List<ToolDecl>, maxTokens: Int, model: String): LlmResponse = try {
        geminiCall(system, messages, tools, maxTokens, model, thinkLevels[thinkIndex[model] ?: 0])
    } catch (e: LlmException) {
        val i = thinkIndex[model] ?: 0
        if (!e.busy && i < thinkLevels.size - 1 && (e.message ?: "").contains("think", ignoreCase = true)) {
            thinkIndex[model] = i + 1
            log("$model rejected thinking level ${thinkLevels[i]}, using ${thinkLevels[i + 1] ?: "none"}")
            gemini(system, messages, tools, maxTokens, model)
        } else throw e
    }

    /** Gemini 2.x models: thinking is set by budget, and they neither need nor know Gemini 3's thought signatures. */
    internal fun isGemini2(model: String) = Regex("gemini-2(\\.|-)").containsMatchIn(model)

    /**
     * The thought signature to send back with a function call. Gemini 3 insists on one for the first call of
     * each step; a step that another model answered (a fallback, or another provider) has none, so it gets the
     * documented placeholder. Gemini 2.x gets none at all.
     */
    internal fun geminiSignature(own: String?, firstCall: Boolean, model: String): String? = when {
        isGemini2(model) -> null
        own != null -> own
        firstCall -> "skip_thought_signature_validator"
        else -> null
    }

    private fun geminiCall(system: String, messages: List<Msg>, tools: List<ToolDecl>, maxTokens: Int, model: String, think: String?): LlmResponse {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val contents = JSONArray()
        for (m in messages) {
            when (m.role) {
                "assistant" -> {
                    val parts = JSONArray()
                    m.parts.forEach { p ->
                        when (p) {
                            is Part.Text -> if (p.text.isNotBlank()) parts.put(JSONObject().put("text", p.text))
                            is Part.Call -> {
                                val o = JSONObject().put("functionCall", JSONObject().put("name", p.name).put("args", p.args))
                                geminiSignature(p.signature, firstCall = (0 until parts.length()).none { parts.getJSONObject(it).has("functionCall") }, model = model)?.let { o.put("thoughtSignature", it) }
                                parts.put(o)
                            }
                            else -> {}
                        }
                    }
                    if (parts.length() > 0) contents.put(JSONObject().put("role", "model").put("parts", parts))
                }
                "tool" -> {
                    val parts = JSONArray()
                    val images = JSONArray()
                    m.results.forEach { r ->
                        parts.put(JSONObject().put("functionResponse", JSONObject().put("name", r.name).put("response", r.output)))
                        r.image?.takeIf { it.b64.isNotBlank() }?.let { img ->
                            images.put(JSONObject().put("text", "Image from ${r.name}:"))
                            images.put(JSONObject().put("inline_data", JSONObject().put("mime_type", img.mime).put("data", img.b64)))
                        }
                    }
                    for (i in 0 until images.length()) parts.put(images.get(i))
                    if (parts.length() > 0) contents.put(JSONObject().put("role", "user").put("parts", parts))
                }
                else -> {
                    val parts = JSONArray()
                    m.parts.forEach { p ->
                        when (p) {
                            is Part.Text -> parts.put(JSONObject().put("text", p.text.ifBlank { "." }))
                            is Part.Image -> if (p.b64.isNotBlank()) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", p.mime).put("data", p.b64)))
                            else -> {}
                        }
                    }
                    if (parts.length() > 0) contents.put(JSONObject().put("role", "user").put("parts", parts))
                }
            }
        }
        val body = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", contents)
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().apply {
                    tools.forEach { t ->
                        val d = JSONObject().put("name", t.name).put("description", t.description)
                        // Gemini rejects an OBJECT schema with no properties: leave parameters out for no-arg tools.
                        if ((t.params.optJSONObject("properties")?.length() ?: 0) > 0) d.put("parameters", geminiSchema(t.params))
                        put(d)
                    }
                })))
                put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", if (mustCall.get() == true) "ANY" else "AUTO")))
            }
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", maxOf(maxTokens, 4096))
                // Phone tasks need little "thinking"; high thinking made every step take a minute.
                if (isGemini2(model)) put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                else if (think != null) put("thinkingConfig", JSONObject().put("thinkingLevel", think))
            })
        }
        val res = post(url, body, mapOf("x-goog-api-key" to Settings.apiKey))
        // Which real model answered: names that point at the same one share its per-minute limit.
        res.optString("modelVersion").removePrefix("models/").takeIf { it.isNotBlank() && it != model }?.let { real ->
            if (realName.put(model, real) != real) paces.remove(model)?.let { old ->
                val p = paces.getOrPut(real) { old }
                if (p !== old) synchronized(p) { p.recent.addAll(old.recent) }
            }
        }
        val cands = res.optJSONArray("candidates") ?: throw LlmException(res.optJSONObject("error")?.optString("message") ?: "Empty answer from Gemini")
        val cand = cands.optJSONObject(0) ?: throw LlmException("Gemini gave no answer")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder()
        val calls = mutableListOf<Part.Call>()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought")) continue
            p.optJSONObject("functionCall")?.let { fc -> calls += Part.Call(newId(), fc.optString("name"), fc.optJSONObject("args") ?: JSONObject(), p.optString("thoughtSignature").ifBlank { null }) }
            if (p.has("text")) text.append(p.optString("text"))
        }
        val stop = cand.optString("finishReason", "")
        if (text.isBlank() && calls.isEmpty() && stop == "SAFETY") throw LlmException("Gemini declined (safety filter)")
        val usage = res.optJSONObject("usageMetadata")
        val tokens = usage?.optInt("totalTokenCount", 0) ?: 0
        usage?.optInt("thoughtsTokenCount", 0)?.takeIf { it > 0 }?.let { log("$model thought for $it tokens (level ${think ?: "none"})") }
        return LlmResponse(text.toString().trim(), calls, tokens, model, stop)
    }

    /** Gemini wants upper-case schema types and no unknown keys. */
    private fun geminiSchema(s: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = s.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (k) {
                "type" -> out.put("type", s.optString("type").uppercase())
                "properties" -> {
                    val props = s.optJSONObject("properties") ?: JSONObject()
                    val po = JSONObject()
                    val pk = props.keys()
                    while (pk.hasNext()) { val name = pk.next(); props.optJSONObject(name)?.let { po.put(name, geminiSchema(it)) } }
                    out.put("properties", po)
                }
                "items" -> s.optJSONObject("items")?.let { out.put("items", geminiSchema(it)) }
                "description", "enum", "required", "nullable" -> out.put(k, s.opt(k))
                else -> {}
            }
        }
        return out
    }

    // ---------------------------------------------------------------- Anthropic

    private fun anthropic(system: String, messages: List<Msg>, tools: List<ToolDecl>, maxTokens: Int, model: String): LlmResponse {
        val msgs = JSONArray()
        for (m in messages) {
            val content = JSONArray()
            when (m.role) {
                "assistant" -> m.parts.forEach { p ->
                    when (p) {
                        is Part.Text -> if (p.text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", p.text))
                        is Part.Call -> content.put(JSONObject().put("type", "tool_use").put("id", p.id).put("name", p.name).put("input", p.args))
                        else -> {}
                    }
                }
                "tool" -> m.results.forEach { r ->
                    val inner = JSONArray().put(JSONObject().put("type", "text").put("text", r.output.toString()))
                    r.image?.takeIf { it.b64.isNotBlank() }?.let { inner.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", it.mime).put("data", it.b64))) }
                    content.put(JSONObject().put("type", "tool_result").put("tool_use_id", r.callId).put("content", inner).put("is_error", r.isError))
                }
                else -> m.parts.forEach { p ->
                    when (p) {
                        is Part.Text -> content.put(JSONObject().put("type", "text").put("text", p.text.ifBlank { "." }))
                        is Part.Image -> content.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", p.mime).put("data", p.b64)))
                        else -> {}
                    }
                }
            }
            if (content.length() > 0) msgs.put(JSONObject().put("role", if (m.role == "assistant") "assistant" else "user").put("content", content))
        }
        val body = JSONObject().apply {
            put("model", model); put("max_tokens", maxTokens); put("system", system); put("messages", msgs)
            if (tools.isNotEmpty()) put("tools", JSONArray().apply { tools.forEach { put(JSONObject().put("name", it.name).put("description", it.description).put("input_schema", it.params)) } })
            if (tools.isNotEmpty() && mustCall.get() == true) put("tool_choice", JSONObject().put("type", "any"))
        }
        val res = post("https://api.anthropic.com/v1/messages", body, mapOf("x-api-key" to Settings.apiKey, "anthropic-version" to "2023-06-01"))
        val content = res.optJSONArray("content") ?: throw LlmException(res.optJSONObject("error")?.optString("message") ?: "Empty answer from Claude")
        val text = StringBuilder()
        val calls = mutableListOf<Part.Call>()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> text.append(b.optString("text"))
                "tool_use" -> calls += Part.Call(b.optString("id").ifBlank { newId() }, b.optString("name"), b.optJSONObject("input") ?: JSONObject())
            }
        }
        val u = res.optJSONObject("usage")
        val tokens = (u?.optInt("input_tokens") ?: 0) + (u?.optInt("output_tokens") ?: 0)
        return LlmResponse(text.toString().trim(), calls, tokens, model, res.optString("stop_reason", ""))
    }

    // ---------------------------------------------------------------- OpenAI-compatible

    private fun openai(system: String, messages: List<Msg>, tools: List<ToolDecl>, maxTokens: Int, model: String): LlmResponse {
        val base = Settings.baseUrl.ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (m in messages) {
            when (m.role) {
                "assistant" -> {
                    val o = JSONObject().put("role", "assistant").put("content", m.text.ifBlank { JSONObject.NULL })
                    if (m.calls.isNotEmpty()) o.put("tool_calls", JSONArray().apply {
                        m.calls.forEach { put(JSONObject().put("id", it.id).put("type", "function").put("function", JSONObject().put("name", it.name).put("arguments", it.args.toString()))) }
                    })
                    msgs.put(o)
                }
                "tool" -> {
                    val images = JSONArray()
                    m.results.forEach { r ->
                        msgs.put(JSONObject().put("role", "tool").put("tool_call_id", r.callId).put("content", r.output.toString()))
                        r.image?.takeIf { it.b64.isNotBlank() }?.let {
                            images.put(JSONObject().put("type", "text").put("text", "Image from ${r.name}:"))
                            images.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${it.mime};base64,${it.b64}")))
                        }
                    }
                    if (images.length() > 0) msgs.put(JSONObject().put("role", "user").put("content", images))
                }
                else -> {
                    val content = JSONArray()
                    m.parts.forEach { p ->
                        when (p) {
                            is Part.Text -> content.put(JSONObject().put("type", "text").put("text", p.text.ifBlank { "." }))
                            is Part.Image -> content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${p.mime};base64,${p.b64}")))
                            else -> {}
                        }
                    }
                    msgs.put(JSONObject().put("role", "user").put("content", content))
                }
            }
        }
        val body = JSONObject().apply {
            put("model", model); put("messages", msgs); put("max_tokens", maxTokens); put("temperature", 0.2)
            if (tools.isNotEmpty()) put("tools", JSONArray().apply {
                tools.forEach { put(JSONObject().put("type", "function").put("function", JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.params))) }
            })
            if (tools.isNotEmpty() && mustCall.get() == true) put("tool_choice", "required")
        }
        val res = post("$base/chat/completions", body, mapOf("Authorization" to "Bearer ${Settings.apiKey}"))
        val choice = res.optJSONArray("choices")?.optJSONObject(0) ?: throw LlmException(res.optJSONObject("error")?.optString("message") ?: "Empty answer from model")
        val msg = choice.optJSONObject("message") ?: JSONObject()
        val calls = mutableListOf<Part.Call>()
        msg.optJSONArray("tool_calls")?.let { a ->
            for (i in 0 until a.length()) {
                val tc = a.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                calls += Part.Call(tc.optString("id").ifBlank { newId() }, fn.optString("name"), args)
            }
        }
        val tokens = res.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0
        val content = if (msg.isNull("content")) "" else msg.optString("content", "")
        return LlmResponse(content.trim(), calls, tokens, model, choice.optString("finish_reason", ""))
    }

    // ---------------------------------------------------------------- HTTP

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            // Flash-Lite answers in 1-3 s. A model that says nothing for 25 s has hung: another one takes over.
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Seyal/2.0")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) {
                val err = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
                val msg = err?.optString("message")
                val busy = code == 429 || code == 503 || code == 529 || code == 500 ||
                    (msg ?: text).contains("high demand", true) || (msg ?: text).contains("overloaded", true)
                val (retryMs0, daily) = quotaInfo(err)
                quotaDetail(err)?.let { log("quota: $it") }
                perMinuteLimit(err)?.let { (m, n) -> learnLimit(m, n) }
                var retryMs = retryMs0
                if (retryMs == 0L) conn.getHeaderField("retry-after")?.toDoubleOrNull()?.let { retryMs = (it * 1000).toLong() }
                throw LlmException(
                    if (busy) "AI busy ($code): ${msg ?: "try again"}" else "AI error $code: ${msg ?: text.take(200)}",
                    busy, quota = code == 429, retryAfterMs = retryMs, daily = code == 429 && daily, missing = code == 404,
                )
            }
            return JSONObject(text)
        } catch (e: java.net.SocketTimeoutException) {
            throw LlmException("AI took too long to answer", busy = true, slow = true)
        } catch (e: java.net.UnknownHostException) {
            throw LlmException("No internet connection")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * From a Gemini error: how long it asks us to wait (error.details[].retryDelay = "23s"), and whether the
     * limit hit is the daily one (QuotaFailure violations with a quotaId like "…PerDay…"). A daily limit
     * also comes with a short retryDelay, which must not be believed.
     */
    internal fun quotaInfo(err: JSONObject?): Pair<Long, Boolean> {
        var retryMs = 0L
        var daily = false
        val d = err?.optJSONArray("details") ?: return 0L to false
        for (i in 0 until d.length()) {
            val o = d.optJSONObject(i) ?: continue
            o.optString("retryDelay").removeSuffix("s").toDoubleOrNull()?.let { retryMs = (it * 1000).toLong() }
            val v = o.optJSONArray("violations") ?: continue
            for (j in 0 until v.length()) if (v.optJSONObject(j)?.optString("quotaId")?.contains("PerDay", ignoreCase = true) == true) daily = true
        }
        return retryMs to daily
    }

    /** Which limit was hit and how big it is, e.g. "GenerateRequestsPerDayPerProjectPerModel-FreeTier = 250 (gemini-3.5-flash-lite)". */
    internal fun quotaDetail(err: JSONObject?): String? {
        val d = err?.optJSONArray("details") ?: return null
        val out = mutableListOf<String>()
        for (i in 0 until d.length()) {
            val v = d.optJSONObject(i)?.optJSONArray("violations") ?: continue
            for (j in 0 until v.length()) {
                val o = v.optJSONObject(j) ?: continue
                val model = o.optJSONObject("quotaDimensions")?.optString("model").orEmpty()
                out += "${o.optString("quotaId")} = ${o.optString("quotaValue")}" + if (model.isNotBlank()) " ($model)" else ""
            }
        }
        return out.joinToString("; ").ifBlank { null }
    }

    /** The per-minute request limit a 429 names, with its model: ("gemini-3.5-flash-lite", 15). */
    internal fun perMinuteLimit(err: JSONObject?): Pair<String, Int>? {
        val d = err?.optJSONArray("details") ?: return null
        for (i in 0 until d.length()) {
            val v = d.optJSONObject(i)?.optJSONArray("violations") ?: continue
            for (j in 0 until v.length()) {
                val o = v.optJSONObject(j) ?: continue
                if (!o.optString("quotaId").contains("RequestsPerMinute", true)) continue
                val model = o.optJSONObject("quotaDimensions")?.optString("model").orEmpty()
                val n = o.optString("quotaValue").toIntOrNull() ?: continue
                if (model.isNotBlank()) return model to n
            }
        }
        return null
    }
}
