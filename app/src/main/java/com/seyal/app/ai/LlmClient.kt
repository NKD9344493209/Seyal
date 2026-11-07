package com.seyal.app.ai

import android.util.Log
import com.seyal.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class LlmException(message: String, val busy: Boolean = false) : IOException(message)

/** Talks to Gemini, Claude or any OpenAI-compatible server with the user's own key. */
object LlmClient {

    /** Gemini models to fall back to, in order, when the chosen one is overloaded. */
    private val geminiFallbacks = listOf("gemini-flash-lite-latest", "gemini-flash-latest")

    /** Models that rejected thinkingConfig, so we stop sending it to them. */
    private val noThinking = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun complete(system: String, user: String, json: Boolean = false, maxTokens: Int = 2048): String =
        withContext(Dispatchers.IO) {
            if (!Settings.hasKey) throw LlmException("No AI key yet. Add one in Control.")
            val models = when (Settings.provider) {
                "gemini" -> (listOf(Settings.effectiveModel()) + geminiFallbacks).distinct()
                else -> listOf(Settings.effectiveModel())
            }
            for (model in models) {
                // Busy servers often recover within seconds: one quick retry, then the next model.
                for (attempt in 0 until 2) {
                    val t0 = System.currentTimeMillis()
                    try {
                        val out = when (Settings.provider) {
                            "anthropic" -> anthropic(system, user, maxTokens, model)
                            "openai" -> openai(system, user, json, maxTokens, model)
                            else -> gemini(system, user, json, maxTokens, model)
                        }
                        log("$model try ${attempt + 1}: ok in ${System.currentTimeMillis() - t0} ms")
                        return@withContext out
                    } catch (e: LlmException) {
                        log("$model try ${attempt + 1}: failed in ${System.currentTimeMillis() - t0} ms (busy=${e.busy}): ${e.message?.take(160)}")
                        if (!e.busy) throw e
                        if (attempt == 0) delay(1500L)
                    }
                }
            }
            throw LlmException("The AI is overloaded right now. Try again in a minute.", busy = true)
        }

    private fun log(msg: String) { runCatching { Log.i("SeyalAI", msg) } }

    // ---- Gemini ----
    private fun gemini(system: String, user: String, json: Boolean, maxTokens: Int, model: String): String = try {
        geminiCall(system, user, json, maxTokens, model, thinkLow = model !in noThinking)
    } catch (e: LlmException) {
        // Older models don't know thinkingLevel: retry once without it.
        if (model !in noThinking && !e.busy && (e.message ?: "").contains("think", ignoreCase = true)) {
            noThinking += model
            geminiCall(system, user, json, maxTokens, model, thinkLow = false)
        } else throw e
    }

    private fun geminiCall(system: String, user: String, json: Boolean, maxTokens: Int, model: String, thinkLow: Boolean): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val body = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", maxOf(maxTokens, 8192))
                if (json) put("responseMimeType", "application/json")
                // Planning a phone command needs little "thinking"; high thinking made replies take 60–90 s.
                if (thinkLow) put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            })
        }
        val res = post(url, body, mapOf("x-goog-api-key" to Settings.apiKey))
        res.optJSONObject("usageMetadata")?.let { Settings.tokensUsed += it.optLong("totalTokenCount", 0L) }
        val cands = res.optJSONArray("candidates") ?: throw LlmException(res.optJSONObject("error")?.optString("message") ?: "Empty answer from Gemini")
        val parts = cands.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: throw LlmException("Gemini gave no text (blocked?)")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
        return sb.toString()
    }

    // ---- Anthropic ----
    private fun anthropic(system: String, user: String, maxTokens: Int, model: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", 0.2)
            put("system", system)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        }
        val res = post(
            "https://api.anthropic.com/v1/messages", body,
            mapOf("x-api-key" to Settings.apiKey, "anthropic-version" to "2023-06-01"),
        )
        res.optJSONObject("usage")?.let { Settings.tokensUsed += it.optLong("input_tokens", 0L) + it.optLong("output_tokens", 0L) }
        val content = res.optJSONArray("content") ?: throw LlmException(res.optJSONObject("error")?.optString("message") ?: "Empty answer from Claude")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            if (c.optString("type") == "text") sb.append(c.optString("text"))
        }
        return sb.toString()
    }

    // ---- OpenAI-compatible ----
    private fun openai(system: String, user: String, json: Boolean, maxTokens: Int, model: String): String {
        val base = Settings.baseUrl.ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("max_tokens", maxTokens)
            if (json) put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        }
        val res = post("$base/chat/completions", body, mapOf("Authorization" to "Bearer ${Settings.apiKey}"))
        res.optJSONObject("usage")?.let { Settings.tokensUsed += it.optLong("total_tokens", 0L) }
        val choices = res.optJSONArray("choices") ?: throw LlmException(res.optJSONObject("error")?.optString("message") ?: "Empty answer from model")
        return choices.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: ""
    }

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Seyal/0.1")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                val busy = code == 429 || code == 503 || code == 529 || code == 500 ||
                    (msg ?: text).contains("high demand", true) || (msg ?: text).contains("overloaded", true)
                throw LlmException(if (busy) "AI busy ($code): ${msg ?: "try again"}" else "AI error $code: ${msg ?: text.take(200)}", busy)
            }
            return JSONObject(text)
        } catch (e: java.net.SocketTimeoutException) {
            // A stuck server: treat like "busy" so we move on to the next model.
            throw LlmException("AI took too long to answer", busy = true)
        } finally {
            conn.disconnect()
        }
    }

    fun extractJson(text: String): JSONObject = PlanParser.extractJson(text)
}
