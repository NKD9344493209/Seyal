package com.seyal.app.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.seyal.app.R
import com.seyal.app.agent.ActionReceiver
import com.seyal.app.ui.MainActivity

/**
 * Keeps Seyal alive while it is working, even when another app is in front.
 * Shows one quiet notification: what it is doing now, with a Stop button. Gone when the work is done.
 *
 * Every start and stop goes through the main thread, and each carries a number from the agent:
 * a "stop" older than the latest "working" is ignored, so a task that just started is never cut off.
 */
class AgentService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Android requires startForeground soon after every start request, even if we are about to stop.
        val ok = runCatching {
            val n = build(this, lastText)
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        }.isSuccess
        if (!ok) { running = false; stopSelf(startId); return START_NOT_STICKY }
        running = true
        if (!wanted) stopNow()
        return START_NOT_STICKY
    }

    private fun stopNow() {
        running = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelfResult(lastStartId)
    }

    override fun onDestroy() { running = false; if (instance === this) instance = null; super.onDestroy() }

    companion object {
        private const val ID = 4201
        private const val CHANNEL = "working"
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentService? = null
        @Volatile private var running = false
        @Volatile private var wanted = false
        @Volatile private var lastStartId = 0
        @Volatile private var lastText = "Working…"
        @Volatile private var latestGen = 0L

        private fun build(ctx: Context, text: String): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Working", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while Seyal is doing a task"; setShowBadge(false) })
            }
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, ActionReceiver::class.java).setAction("com.seyal.app.STOP_ALL"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Seyal is working")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            return b.build()
        }

        /** Work started or the current step changed. [gen] grows with every report from the agent. */
        fun working(ctx: Context, text: String, gen: Long) {
            val app = ctx.applicationContext
            main.post {
                if (gen < latestGen) return@post
                latestGen = gen
                lastText = text.take(80)
                wanted = true
                if (running) {
                    runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build(app, lastText)) }
                } else {
                    // Not allowed from the background on Android 12+ (e.g. a notification-triggered skill): then we just run without it.
                    runCatching { app.startForegroundService(Intent(app, AgentService::class.java)) }
                }
            }
        }

        /** Nothing is running any more, as of the agent's report number [gen]. */
        fun idle(gen: Long) {
            main.post {
                if (gen < latestGen) return@post
                wanted = false
                if (running) instance?.stopNow()
            }
        }
    }
}
