package com.seyal.app.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.seyal.app.R
import com.seyal.app.agent.ActionReceiver
import com.seyal.app.data.Session
import com.seyal.app.ui.MainActivity

/** One line per result. Nothing more. */
object Notify {
    const val CH_RESULTS = "results"
    const val CH_ASK = "ask"
    const val CH_ALARM = "alarm"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_RESULTS, "Results", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "One line when a task finishes" })
        nm.createNotificationChannel(NotificationChannel(CH_ASK, "Needs you", NotificationManager.IMPORTANCE_HIGH).apply { description = "Approvals and questions" })
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Alarms and reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alarms you asked for"
            enableVibration(true)
        })
    }

    private fun allowed(ctx: Context) =
        android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context, sessionId: String? = null): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        sessionId?.let { intent.putExtra("session", it) }
        return PendingIntent.getActivity(ctx, sessionId?.hashCode() ?: 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun line(ctx: Context, text: String, sessionId: String? = null) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_RESULTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(text.take(60))
            .setContentText("Tap to view")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx, sessionId))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify((sessionId ?: text).hashCode(), n)
    }

    /** "Tap to run it now": for a skill that could not run at its time (the phone was locked). */
    fun runSkill(ctx: Context, skillId: String, title: String, text: String) {
        if (!allowed(ctx)) return
        val intent = Intent(ctx, MainActivity::class.java).setAction("com.seyal.app.RUN_SKILL").putExtra("skill", skillId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, "run$skillId".hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_ASK)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title.take(60))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .addAction(0, "Run now", pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify("run$skillId".hashCode(), n)
    }

    fun approval(ctx: Context, s: Session, callId: String, title: String, preview: String) {
        if (!allowed(ctx)) return
        fun action(act: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, (callId + act).hashCode(),
            Intent(ctx, ActionReceiver::class.java).setAction(act).putExtra("call", callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH_ASK)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$title — OK?")
            .setContentText(preview.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(openApp(ctx, s.id))
            .addAction(0, "Allow", action("com.seyal.app.ALLOW"))
            .addAction(0, "No", action("com.seyal.app.DENY"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(ctx).notify(approvalId(s.id, callId), n)
    }

    fun question(ctx: Context, s: Session, question: String) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_ASK)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Seyal asks")
            .setContentText(question.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setContentIntent(openApp(ctx, s.id))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(ctx).notify(("q" + s.id).hashCode(), n)
    }

    fun cancelQuestion(ctx: Context, sessionId: String) {
        NotificationManagerCompat.from(ctx).cancel(("q$sessionId").hashCode())
    }

    fun cancelApproval(ctx: Context, sessionId: String, callId: String) {
        NotificationManagerCompat.from(ctx).cancel(approvalId(sessionId, callId))
    }

    private fun approvalId(sessionId: String, callId: String) = ("ask$sessionId$callId").hashCode()

    fun reminder(ctx: Context, id: String, text: String) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(text.take(60))
            .setContentText("Reminder from Seyal")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        NotificationManagerCompat.from(ctx).notify(("rem$id").hashCode(), n)
    }
}
