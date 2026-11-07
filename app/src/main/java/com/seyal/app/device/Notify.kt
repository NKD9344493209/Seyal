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
import com.seyal.app.data.Run
import com.seyal.app.data.Step
import com.seyal.app.engine.ActionReceiver
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

    private fun openApp(ctx: Context, runId: String? = null): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runId?.let { intent.putExtra("run", it) }
        return PendingIntent.getActivity(ctx, runId?.hashCode() ?: 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun line(ctx: Context, text: String, runId: String? = null) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_RESULTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(text.take(60))
            .setContentText("Tap to view")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx, runId))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify((runId ?: text).hashCode(), n)
    }

    fun approval(ctx: Context, run: Run, step: Step, preview: String) {
        if (!allowed(ctx)) return
        fun action(act: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, (run.id + step.id + act).hashCode(),
            Intent(ctx, ActionReceiver::class.java).setAction(act).putExtra("run", run.id).putExtra("step", step.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH_ASK)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("${step.title} — OK?")
            .setContentText(preview.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(openApp(ctx, run.id))
            .addAction(0, "Allow", action("com.seyal.app.ALLOW"))
            .addAction(0, "No", action("com.seyal.app.DENY"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(ctx).notify(approvalId(run.id, step.id), n)
    }

    fun cancelApproval(ctx: Context, runId: String, stepId: String) {
        NotificationManagerCompat.from(ctx).cancel(approvalId(runId, stepId))
    }

    private fun approvalId(runId: String, stepId: String) = ("ask$runId$stepId").hashCode()

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
