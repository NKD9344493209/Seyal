package com.seyal.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * User settings. Keys and passwords live in an encrypted file (Android Keystore);
 * everything else in plain preferences.
 */
object Settings {
    private lateinit var plain: SharedPreferences
    private lateinit var secure: SharedPreferences

    fun init(context: Context) {
        plain = context.getSharedPreferences("seyal", Context.MODE_PRIVATE)
        secure = runCatching {
            val key = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "seyal_secure", key, context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { context.getSharedPreferences("seyal_secure_fallback", Context.MODE_PRIVATE) }
    }

    private fun str(key: String, default: String = ""): String = plain.getString(key, default) ?: default
    private fun put(key: String, v: String) = plain.edit().putString(key, v).apply()

    // ---- AI ----
    /** "gemini" | "anthropic" | "openai" */
    var provider: String
        get() = str("provider", "gemini")
        set(v) = put("provider", v)

    var model: String
        get() = str("model")
        set(v) = put("model", v.trim())

    var apiKey: String
        get() = secure.getString("api_key", "") ?: ""
        set(v) = secure.edit().putString("api_key", v.trim()).apply()

    /** For OpenAI-compatible servers (Groq, OpenRouter, Ollama …). */
    var baseUrl: String
        get() = str("base_url")
        set(v) = put("base_url", v.trim())

    val hasKey: Boolean get() = apiKey.isNotBlank()

    fun defaultModel(): String = when (provider) {
        "anthropic" -> "claude-haiku-5-5"
        "openai" -> "gpt-4o-mini"
        // Google's alias for the newest Flash-Lite: fast, big free quota. Flash is the automatic backup.
        else -> "gemini-flash-lite-latest"
    }

    fun effectiveModel(): String = model.ifBlank { defaultModel() }

    /** Most tool-call rounds the agent may take for one request. */
    var maxSteps: Int
        get() = plain.getInt("max_steps", 30)
        set(v) = plain.edit().putInt("max_steps", v.coerceIn(5, 80)).apply()

    // ---- you ----
    var userName: String
        get() = str("user_name")
        set(v) = put("user_name", v.trim())

    var signature: String
        get() = str("signature")
        set(v) = put("signature", v.trim())

    var homeCity: String
        get() = str("home_city")
        set(v) = put("home_city", v.trim())

    /** Reply language hint: "auto" | "en" | "ta" | "tanglish". */
    var language: String
        get() = str("language", "auto")
        set(v) = put("language", v)

    /** Gmail address used to read and send mail (IMAP/SMTP with an app password). */
    var gmailAddress: String
        get() = str("gmail_address")
        set(v) = put("gmail_address", v.trim())

    /** 16-letter Gmail app password (stored encrypted). */
    var gmailAppPassword: String
        get() = secure.getString("gmail_app_password", "") ?: ""
        set(v) = secure.edit().putString("gmail_app_password", v.replace(" ", "").trim()).apply()

    val hasGmail: Boolean get() = gmailAddress.isNotBlank() && gmailAppPassword.isNotBlank()

    var collegeDomain: String
        get() = str("college_domain")
        set(v) = put("college_domain", v.trim().lowercase())

    // ---- control ----
    var paused: Boolean
        get() = plain.getBoolean("paused", false)
        set(v) = plain.edit().putBoolean("paused", v).apply()

    var quiet: Boolean
        get() = plain.getBoolean("quiet", false)
        set(v) = plain.edit().putBoolean("quiet", v).apply()

    /** Speak final answers out loud. */
    var speak: Boolean
        get() = plain.getBoolean("speak", false)
        set(v) = plain.edit().putBoolean("speak", v).apply()

    fun level(tool: String, default: Level): Level {
        val s = plain.getString("level_$tool", null) ?: return default
        return runCatching { Level.valueOf(s) }.getOrDefault(default)
    }

    fun setLevel(tool: String, level: Level) = plain.edit().putString("level_$tool", level.name).apply()

    /** "tool:target" pairs the user allowed for good, e.g. "send_whatsapp:919876500001". */
    var allowList: Set<String>
        get() = plain.getStringSet("allow_list", emptySet()) ?: emptySet()
        set(v) = plain.edit().putStringSet("allow_list", v).apply()

    fun allow(tool: String, target: String) { allowList = allowList + "$tool:${target.lowercase()}" }
    fun isAllowed(tool: String, target: String) = allowList.contains("$tool:${target.lowercase()}")

    var onboarded: Boolean
        get() = plain.getBoolean("onboarded", false)
        set(v) = plain.edit().putBoolean("onboarded", v).apply()

    var tokensUsed: Long
        get() = plain.getLong("tokens_used", 0L)
        set(v) = plain.edit().putLong("tokens_used", v).apply()

    /** AI requests sent today (Gemini's free limits count requests per day). */
    var aiRequestsToday: Int
        get() {
            val day = plain.getString("ai_req_day", "")
            return if (day == java.time.LocalDate.now().toString()) plain.getInt("ai_req_count", 0) else 0
        }
        set(v) = plain.edit().putString("ai_req_day", java.time.LocalDate.now().toString()).putInt("ai_req_count", v).apply()

    /** Models resting until their daily limit resets, as {"model": untilMs}, so a restart remembers it. */
    var aiRests: String
        get() = str("ai_rests", "{}")
        set(v) = put("ai_rests", v)

    /** Extra Gemini models found for this key (comma-separated), tried after the usual ones. */
    var aiExtraModels: String
        get() = str("ai_extra_models")
        set(v) = put("ai_extra_models", v)

    /** Keep running in the background (with a quiet notification) while a message or event skill is on. */
    var keepWatching: Boolean
        get() = plain.getBoolean("keep_watching", true)
        set(v) = plain.edit().putBoolean("keep_watching", v).apply()

    /** Press "Skip ad" and ad close buttons by itself, in any app (Full edition). */
    var skipAds: Boolean
        get() = plain.getBoolean("skip_ads", true)
        set(v) = plain.edit().putBoolean("skip_ads", v).apply()

    /** The user hid the setup card. */
    var setupHidden: Boolean
        get() = plain.getBoolean("setup_hidden", false)
        set(v) = plain.edit().putBoolean("setup_hidden", v).apply()

    /** Daily cap on messages the agent sends by itself (WhatsApp/SMS/email), as a safety net. */
    var autoSendToday: Int
        get() {
            val day = plain.getString("auto_send_day", "")
            val today = java.time.LocalDate.now().toString()
            return if (day == today) plain.getInt("auto_send_count", 0) else 0
        }
        set(v) = plain.edit().putString("auto_send_day", java.time.LocalDate.now().toString()).putInt("auto_send_count", v).apply()
}
