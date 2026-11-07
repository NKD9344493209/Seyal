package com.seyal.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * User settings. The AI key lives in an encrypted file (Android Keystore);
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

    // ---- AI ----
    /** "gemini" | "anthropic" | "openai" */
    var provider: String
        get() = plain.getString("provider", "gemini") ?: "gemini"
        set(v) = plain.edit().putString("provider", v).apply()

    var model: String
        get() = plain.getString("model", "") ?: ""
        set(v) = plain.edit().putString("model", v).apply()

    var apiKey: String
        get() = secure.getString("api_key", "") ?: ""
        set(v) = secure.edit().putString("api_key", v.trim()).apply()

    /** For OpenAI-compatible servers (Groq, OpenRouter, Ollama …). */
    var baseUrl: String
        get() = plain.getString("base_url", "") ?: ""
        set(v) = plain.edit().putString("base_url", v.trim()).apply()

    val hasKey: Boolean get() = apiKey.isNotBlank()

    fun defaultModel(): String = when (provider) {
        "anthropic" -> "claude-haiku-4-5"
        "openai" -> "gpt-4o-mini"
        else -> "gemini-2.0-flash"
    }

    fun effectiveModel(): String = model.ifBlank { defaultModel() }

    // ---- you ----
    var userName: String
        get() = plain.getString("user_name", "") ?: ""
        set(v) = plain.edit().putString("user_name", v.trim()).apply()

    var signature: String
        get() = plain.getString("signature", "") ?: ""
        set(v) = plain.edit().putString("signature", v.trim()).apply()

    var collegeDomain: String
        get() = plain.getString("college_domain", "") ?: ""
        set(v) = plain.edit().putString("college_domain", v.trim().lowercase()).apply()

    // ---- control ----
    var paused: Boolean
        get() = plain.getBoolean("paused", false)
        set(v) = plain.edit().putBoolean("paused", v).apply()

    var quiet: Boolean
        get() = plain.getBoolean("quiet", false)
        set(v) = plain.edit().putBoolean("quiet", v).apply()

    fun level(tool: String, default: Level): Level {
        val s = plain.getString("level_$tool", null) ?: return default
        return runCatching { Level.valueOf(s) }.getOrDefault(default)
    }

    fun setLevel(tool: String, level: Level) = plain.edit().putString("level_$tool", level.name).apply()

    /** Phone numbers (digits only) allowed for WhatsApp auto-send. */
    var autoSendContacts: Set<String>
        get() = plain.getStringSet("auto_send", emptySet()) ?: emptySet()
        set(v) = plain.edit().putStringSet("auto_send", v).apply()

    var autoSendToday: Int
        get() {
            val day = plain.getString("auto_send_day", "")
            val today = java.time.LocalDate.now().toString()
            return if (day == today) plain.getInt("auto_send_count", 0) else 0
        }
        set(v) = plain.edit().putString("auto_send_day", java.time.LocalDate.now().toString()).putInt("auto_send_count", v).apply()

    var onboarded: Boolean
        get() = plain.getBoolean("onboarded", false)
        set(v) = plain.edit().putBoolean("onboarded", v).apply()

    /** Small memory the AI can read: facts you told it, e.g. "Ram = Ram Kumar (project)". */
    var memory: String
        get() = plain.getString("memory", "") ?: ""
        set(v) = plain.edit().putString("memory", v.trim()).apply()

    var tokensUsed: Long
        get() = plain.getLong("tokens_used", 0L)
        set(v) = plain.edit().putLong("tokens_used", v).apply()
}
