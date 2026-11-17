package com.seyal.app.device

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as SysSettings
import com.seyal.app.agent.Agent
import com.seyal.app.agent.Scheduler
import com.seyal.app.data.Session
import com.seyal.app.data.Settings

/**
 * "Report problem": one tap puts together everything needed to find out what went wrong (the phone,
 * what Seyal is allowed to do, the last tasks, and every step of the one that failed) and opens the
 * share sheet. No keys, no contacts, no message history are included.
 */
object Report {
    /** The facts about the phone and the setup, one per line. [checks]: name to "yes"/"no". */
    internal fun header(version: String, phone: String, android: String, model: String, checks: List<Pair<String, String>>, killed: Int, aiNote: String?): String = buildString {
        appendLine("Seyal problem report")
        appendLine("App: $version")
        appendLine("Phone: $phone · Android $android")
        appendLine("AI model: $model")
        appendLine("Setup: " + checks.joinToString(" · ") { "${it.first}: ${it.second}" })
        if (killed > 0) appendLine("The phone closed Seyal in the middle of a task $killed time${if (killed == 1) "" else "s"}.")
        if (!aiNote.isNullOrBlank()) appendLine("AI: $aiNote")
    }

    /** The whole report as text. [s]: the task it is about, when there is one. */
    fun text(ctx: Context, s: Session?): String {
        fun yn(b: Boolean) = if (b) "yes" else "no"
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val checks = listOf(
            "screen control" to yn(ScreenDriver.available),
            "notification access" to yn(SeyalNotificationListener.connected),
            "battery unrestricted" to yn(KeepAlive.unrestricted(ctx)),
            "exact alarms" to yn(Scheduler.canExact(ctx)),
            "notifications allowed" to yn(runCatching { nm.areNotificationsEnabled() }.getOrDefault(true)),
            "draw over apps" to yn(runCatching { SysSettings.canDrawOverlays(ctx) }.getOrDefault(false)),
            "screenshots work" to (if (ScreenDriver.shotsBroken()) "no (${Settings.shotWhy.take(40)})" else "yes"),
            "paused" to yn(Settings.paused),
        )
        val head = header(
            com.seyal.app.BuildConfig.VERSION_NAME, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE ?: "?", Settings.effectiveModel(), checks,
            Settings.killedCount, com.seyal.app.ai.Llm.limitNote(),
        )
        val recent = Agent.history.value.take(8).joinToString("\n") { "- ${it.status} · ${it.steps} steps · ${it.title.take(60)}" + (if (it.result.isNotBlank()) " → ${it.result.lineSequence().firstOrNull().orEmpty().take(90)}" else "") }
        return buildString {
            append(head)
            appendLine(); appendLine("Last tasks:"); appendLine(recent.ifBlank { "(none)" })
            if (s != null) { appendLine(); appendLine("The task this is about:"); append(Agent.debugLog(s)) }
        }
    }

    /** Opens the share sheet with the report. */
    fun share(ctx: Context, s: Session?) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Seyal problem" + (s?.let { ": ${it.title.take(60)}" } ?: ""))
            .putExtra(Intent.EXTRA_TEXT, text(ctx, s).take(90_000))
        runCatching { ctx.startActivity(Intent.createChooser(send, "Send the report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
