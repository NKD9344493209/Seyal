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

/** busy = worth trying again or another model. slow = timed out, quota = rate/quota limit (neither clears in seconds). */
class LlmException(message: String, val busy: Boolean = false, val slow: Boolean = false, val quota: Boolean = false, val retryAfterMs: Long = 0L) : IOException(message)

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

    /** Gemini models to fall back to, in order, when the chosen one is overloaded. */
    private val geminiFallbacks = listOf("gemini-flash-lite-latest", "gemini-flash-latest")

    /** A model that just hung or hit its quota is skipped until this time, so the next request doesn't wait on it again. */
    private val coolUntil = ConcurrentHashMap<String, Long>()

    /** How little each model may "think" before answering: minimal → low → (none, for models without the setting). Moves right when a model rejects a level. */
    private val thinkLevels = listOf("minimal", "low", null)
    private val thinkIndex = ConcurrentHashMap<String, Int>()

    suspend fun chat(system: String, messages: List<Msg>, tools: List<ToolDecl>, maxTokens: Int = 2048): LlmResponse = withContext(Dispatchers.IO) {
        if (!Settings.hasKey) throw LlmException("No AI key yet. Add one in Control → AI.")
        val all = when (Settings.provider) {
            "gemini" -> (listOf(Settings.effectiveModel()) + geminiFallbacks).distinct()
            else -> listOf(Settings.effectiveModel())
        }
        val now = System.currentTimeMillis()
        val models = all.filter { (coolUntil[it] ?: 0L) <= now }.ifEmpty { all }
        var last: LlmException? = null
        // "Busy" (503) spikes usually clear within seconds: wait a little longer each time.
        val backoff = longArrayOf(1500, 3500, 7000)
        for (model in models) {
            var waitedForLimit = false
            for (attempt in 0..backoff.size) {
                val t0 = System.currentTimeMillis()
                try {
                    val out = when (Settings.provider) {
                        "anthropic" -> anthropic(system, messages, tools, maxTokens, model)
                        "openai" -> openai(system, messages, tools, maxTokens, model)
                        else -> gemini(system, messages, tools, maxTokens, model)
                    }
                    log("$model try ${attempt + 1}: ok in ${System.currentTimeMillis() - t0} ms, ${out.tokens} tokens, ${out.calls.size} calls")
                    coolUntil.remove(model)
                    Settings.tokensUsed = Settings.tokensUsed + out.tokens
                    return@withContext out
                } catch (e: LlmException) {
                    last = e
                    log("$model try ${attempt + 1}: failed in ${System.currentTimeMillis() - t0} ms (busy=${e.busy}): ${e.message?.take(160)}")
                    if (!e.busy) throw e
                    // A per-minute limit says how long to wait: wait that long if it's short.
                    if (e.quota && !waitedForLimit && e.retryAfterMs in 1..40_000) { waitedForLimit = true; delay(e.retryAfterMs + 500); continue }
                    if (e.slow || e.quota || attempt == backoff.size) {
                        if (models.size > 1) coolUntil[model] = System.currentTimeMillis() + (if (e.quota) 15 else 5) * 60_000L
                        break
                    }
                    delay(backoff[attempt])
                }
            }
        }
        throw LlmException(
            when {
                last?.quota == true -> "Your AI key has hit its usage limit for now. Wait a bit, or add another key in Control → AI."
                last?.slow == true -> "Google's AI isn't answering right now (it timed out). Try again in a minute."
                else -> "Google's AI is overloaded right now. Try again in a minute."
            },
            busy = true,
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
                            is Part.Call -> parts.put(JSONObject().put("functionCall", JSONObject().put("name", p.name).put("args", p.args)).also { o -> p.signature?.let { o.put("thoughtSignature", it) } })
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
                put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO")))
            }
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", maxOf(maxTokens, 4096))
                // Phone tasks need little "thinking"; high thinking made every step take a minute.
                if (think != null) put("thinkingConfig", JSONObject().put("thinkingLevel", think))
            })
        }
        val res = post(url, body, mapOf("x-goog-api-key" to Settings.apiKey))
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
            readTimeout = 35_000
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
                // Gemini says how long to wait on a per-minute limit: error.details[].retryDelay = "23s".
                var retryMs = 0L
                err?.optJSONArray("details")?.let { d -> for (i in 0 until d.length()) d.optJSONObject(i)?.optString("retryDelay")?.removeSuffix("s")?.toDoubleOrNull()?.let { retryMs = (it * 1000).toLong() } }
                if (retryMs == 0L) conn.getHeaderField("retry-after")?.toDoubleOrNull()?.let { retryMs = (it * 1000).toLong() }
                throw LlmException(if (busy) "AI busy ($code): ${msg ?: "try again"}" else "AI error $code: ${msg ?: text.take(200)}", busy, quota = code == 429, retryAfterMs = retryMs)
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
}
