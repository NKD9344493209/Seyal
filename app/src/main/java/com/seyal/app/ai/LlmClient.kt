package com.seyal.app.ai

import com.seyal.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class LlmException(message: String) : IOException(message)

/** Talks to Gemini, Claude or any OpenAI-compatible server with the user's own key. */
object LlmClient {

    suspend fun complete(system: String, user: String, json: Boolean = false, maxTokens: Int = 2048): String =
        withContext(Dispatchers.IO) {
            if (!Settings.hasKey) throw LlmException("No AI key yet. Add one in Control.")
            when (Settings.provider) {
                "anthropic" -> anthropic(system, user, maxTokens)
                "openai" -> openai(system, user, json, maxTokens)
                else -> gemini(system, user, json, maxTokens)
            }
        }

    // ---- Gemini ----
    private fun gemini(system: String, user: String, json: Boolean, maxTokens: Int): String {
        val model = Settings.effectiveModel()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val body = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", maxTokens)
                if (json) put("responseMimeType", "application/json")
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
    private fun anthropic(system: String, user: String, maxTokens: Int): String {
        val body = JSONObject().apply {
            put("model", Settings.effectiveModel())
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
    private fun openai(system: String, user: String, json: Boolean, maxTokens: Int): String {
        val base = Settings.baseUrl.ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
        val body = JSONObject().apply {
            put("model", Settings.effectiveModel())
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
            readTimeout = 90_000
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
                throw LlmException("AI error $code: ${msg ?: text.take(200)}")
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    fun extractJson(text: String): JSONObject = PlanParser.extractJson(text)
}
