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

    /**
     * Whether skills may send messages by themselves when a message or call comes in ("when Amma asks where
     * I am, reply…"). Off until the user turns it on: a wrong automatic reply goes to real people.
     */
    var autoReplies: Boolean
        get() = plain.getBoolean("auto_replies", false)
        set(v) = plain.edit().putBoolean("auto_replies", v).apply()

    /** Busy mode's state (see device/Busy): until when, the message, who called. Empty = not busy. */
    var busyState: String
        get() = str("busy_state", "")
        set(v) = put("busy_state", v)

    /** Whether incoming messages are read for deadlines, bills and renewals (the user is only told; nothing is sent). */
    var catchMessages: Boolean
        get() = plain.getBoolean("catch_messages", true)
        set(v) = plain.edit().putBoolean("catch_messages", v).apply()

    /** How many "caught" notifications were shown today, as "yyyy-MM-dd:n" (a daily cap keeps them from becoming noise). */
    var caughtToday: String
        get() = str("caught_today", "")
        set(v) = put("caught_today", v)

    /** Warn when an incoming message looks like a scam (rules on the phone; only the user is told). */
    var scamWarnings: Boolean
        get() = plain.getBoolean("scam_warnings", true)
        set(v) = plain.edit().putBoolean("scam_warnings", v).apply()

    /** Senders the user marked "Not a scam", one per line. */
    var scamTrusted: String
        get() = str("scam_trusted", "")
        set(v) = put("scam_trusted", v)

    /** Keep incoming messages for 30 days on the phone so they can be searched later ("the address Ram sent"). */
    var keepHistory: Boolean
        get() = plain.getBoolean("keep_history", true)
        set(v) = plain.edit().putBoolean("keep_history", v).apply()

    /** Family check-in: text [guardianNumber] when this phone has not been unlocked by [guardianTime]. Off until set up. */
    var guardianOn: Boolean
        get() = plain.getBoolean("guardian_on", false)
        set(v) = plain.edit().putBoolean("guardian_on", v).apply()
    var guardianNumber: String
        get() = str("guardian_number", "")
        set(v) = put("guardian_number", v)
    var guardianTime: String
        get() = str("guardian_time", "11:00")
        set(v) = put("guardian_time", v)
    /** When the phone was last unlocked (epoch ms). */
    var lastUnlock: Long
        get() = plain.getLong("last_unlock", 0L)
        set(v) = plain.edit().putLong("last_unlock", v).apply()
    /** The day (yyyy-MM-dd) the check-in text was last sent: at most one a day. */
    var guardianSentDay: String
        get() = str("guardian_sent_day", "")
        set(v) = put("guardian_sent_day", v)

    /** Simple mode: one big microphone, big buttons, every answer spoken. For parents and anyone who finds apps hard. */
    var simpleMode: Boolean
        get() = plain.getBoolean("simple_mode", false)
        set(v) = plain.edit().putBoolean("simple_mode", v).apply()

    /** Offer to automate what the user does again and again (at most one offer a day). */
    var suggestHabits: Boolean
        get() = plain.getBoolean("suggest_habits", true)
        set(v) = plain.edit().putBoolean("suggest_habits", v).apply()
    /** Suggestions the user said no to, one key per line: never offered again. */
    var suggestDeclined: String
        get() = str("suggest_declined", "")
        set(v) = put("suggest_declined", v)
    /** Screenshots failing in a row on this phone, and when it was last found out. After two, Seyal works from the screen text. */
    var shotFails: Int
        get() = plain.getInt("shot_fails", 0)
        set(v) = plain.edit().putInt("shot_fails", v).apply()
    var shotFailAt: Long
        get() = plain.getLong("shot_fail_at", 0L)
        set(v) = plain.edit().putLong("shot_fail_at", v).apply()
    var shotWhy: String
        get() = plain.getString("shot_why", "") ?: ""
        set(v) = plain.edit().putString("shot_why", v).apply()
    /** Screen control was on the last time Seyal looked. Goes off when the user switches it off; if the phone does it, Seyal says so. */
    var screenWasOn: Boolean
        get() = plain.getBoolean("screen_was_on", false)
        set(v) = plain.edit().putBoolean("screen_was_on", v).apply()
    /** How many times the phone closed Seyal in the middle of a task (shown with the fix in Control). */
    var killedCount: Int
        get() = plain.getInt("killed_count", 0)
        set(v) = plain.edit().putInt("killed_count", v).apply()
    var killedAt: Long
        get() = plain.getLong("killed_at", 0L)
        set(v) = plain.edit().putLong("killed_at", v).apply()
    /** Places already looked up for the weather ("coimbatore" → latitude, longitude, name), so the next answer is one request, not two. */
    var placeCache: String
        get() = str("place_cache", "")
        set(v) = put("place_cache", v)
    /** The day (yyyy-MM-dd) a suggestion was last shown. */
    var suggestDay: String
        get() = str("suggest_day", "")
        set(v) = put("suggest_day", v)

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
