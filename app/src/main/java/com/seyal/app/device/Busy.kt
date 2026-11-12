package com.seyal.app.device

import android.app.NotificationManager
import android.content.Context
import android.provider.ContactsContract
import com.seyal.app.agent.Scheduler
import com.seyal.app.agent.ToolContext
import com.seyal.app.agent.Toolbox
import com.seyal.app.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The rules of busy mode that need no phone to check. */
object BusyRules {
    const val URGENT_WINDOW_MS = 10 * 60_000L
    const val MAX_TEXTS = 10

    /** The same person calling twice within ten minutes is treated as urgent. [calls]: when they rang (ms), this call included. */
    fun urgent(calls: List<Long>, now: Long): Boolean = calls.count { it > now - URGENT_WINDOW_MS } >= 2

    /** Whether a caller shown as [who] is a saved contact (a bare number is someone unknown: never texted). */
    fun isSavedName(who: String): Boolean = who.any { it.isLetter() } && !Regex("^(unknown|private|hidden|spam|suspected|no caller id|incoming)", RegexOption.IGNORE_CASE).containsMatchIn(who.trim())

    /** One text per caller per busy period, to saved contacts only, and a cap on the whole period. */
    fun mayText(who: String, alreadyTexted: Set<String>, sentSoFar: Int): Boolean = isSavedName(who) && who.lowercase() !in alreadyTexted && sentSoFar < MAX_TEXTS

    /** "Amma (2), Ram" */
    fun summary(missed: Map<String, Int>): String = missed.entries.sortedByDescending { it.value }.joinToString(", ") { (w, n) -> if (n > 1) "$w ($n)" else w }
}

/**
 * Busy mode: "I'm in class till 4". Until then Seyal can keep the phone quiet, text people whose
 * call was missed (once each, saved contacts only, with the message the user approved), let a
 * second call from the same person through as urgent, and at the end list who to call back.
 */
object Busy {
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun load(): JSONObject = runCatching { JSONObject(Settings.busyState) }.getOrDefault(JSONObject())
    private fun save(o: JSONObject) { Settings.busyState = o.toString() }

    fun until(): Long = load().optLong("until")
    fun active(now: Long = System.currentTimeMillis()): Boolean = until() > now
    fun message(): String = load().optString("message")
    fun missed(): Map<String, Int> = load().optJSONObject("missed")?.let { m -> m.keys().asSequence().associateWith { m.optInt(it) } } ?: emptyMap()

    fun start(ctx: Context, until: Long, message: String, text: Boolean, silence: Boolean) {
        save(JSONObject().put("until", until).put("message", message).put("text", text && message.isNotBlank()).put("silence", silence).put("started", System.currentTimeMillis()))
        if (silence) dnd(ctx, true)
        Scheduler.setBusyEnd(ctx, until)
    }

    /** Ends busy mode (its time came, or the user said so) and says who called meanwhile. */
    fun stop(ctx: Context) {
        val o = load()
        if (o.optLong("until") == 0L) return
        val missed = missed()
        if (o.optBoolean("silence")) dnd(ctx, false)
        Settings.busyState = ""
        Scheduler.cancelTimer(ctx, "busy", "end")
        if (missed.isNotEmpty()) Notify.reminder(ctx, "busyend", "While you were busy, you missed: ${BusyRules.summary(missed)}. Call back?")
        else Notify.line(ctx, "Busy mode is over. Nobody called.")
    }

    private fun dnd(ctx: Context, on: Boolean) = runCatching {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    /** The phone is ringing and [who] is shown as the caller. */
    fun onCall(ctx: Context, who: String) {
        val now = System.currentTimeMillis()
        val o = load()
        if (o.optLong("until") <= now) { if (o.optLong("until") > 0) stop(ctx); return }
        val calls = o.optJSONObject("calls") ?: JSONObject()
        val mine = calls.optJSONArray(who.lowercase()) ?: JSONArray()
        mine.put(now); calls.put(who.lowercase(), mine); o.put("calls", calls); save(o)
        val times = (0 until mine.length()).map { mine.optLong(it) }
        if (BusyRules.isSavedName(who) && BusyRules.urgent(times, now)) {
            // Twice in ten minutes: let it through, loudly.
            if (o.optBoolean("silence")) dnd(ctx, false)
            Notify.reminder(ctx, "urgent${who.hashCode()}", "$who is calling again. It may be urgent.")
        }
    }

    /** That call was not answered. */
    fun onMissed(ctx: Context, who: String) {
        val now = System.currentTimeMillis()
        val o = load()
        if (o.optLong("until") <= now) return
        val missed = o.optJSONObject("missed") ?: JSONObject()
        missed.put(who, missed.optInt(who) + 1); o.put("missed", missed)
        val texted = o.optJSONArray("texted") ?: JSONArray()
        val already = (0 until texted.length()).map { texted.optString(it) }.toSet()
        val text = o.optString("message")
        val send = o.optBoolean("text") && text.isNotBlank() && !Settings.paused && BusyRules.mayText(who, already, already.size)
        if (send) { texted.put(who.lowercase()); o.put("texted", texted) }
        save(o)
        if (!send) return
        scope.launch {
            val number = numberOf(ctx, who) ?: return@launch
            val tool = Toolbox.get("send_sms") ?: return@launch
            val r = runCatching { tool.run(JSONObject().put("phone", number).put("text", text).put("name", who), ToolContext("busy", { emptyList() }, { _, _ -> "" })) }.getOrNull()
            if (r is com.seyal.app.agent.ToolResult.Done) Notify.line(ctx, "Busy mode: texted $who \"${text.take(60)}\"")
        }
    }

    /** The mobile number saved for the contact shown as [name], or null. */
    private fun numberOf(ctx: Context, name: String): String? = runCatching {
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?", arrayOf(name), null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}
