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
import android.os.IBinder
import com.seyal.app.R
import com.seyal.app.agent.Skills
import com.seyal.app.data.Settings
import com.seyal.app.ui.MainActivity

/**
 * Keeps Seyal running while it watches for something ("when Amma messages…", "when I unplug the
 * charger"), the way automation apps do: one silent notification says what it is watching.
 * Phones like Xiaomi, Oppo and Samsung stop apps in the background; without this, Seyal would not
 * be there when the message arrives. Runs only while such a skill is on.
 */
class WatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val watching = Skills.watching()
        if (watching.isEmpty() || !Settings.keepWatching) { stop(); return START_NOT_STICKY }
        val ok = runCatching {
            val n = build(this, watching)
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        }.isSuccess
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        running = true
        return START_STICKY
    }

    private fun stop() {
        running = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() { running = false; super.onDestroy() }

    companion object {
        private const val ID = 4202
        private const val CHANNEL = "watching"
        @Volatile var running = false
            private set
        @Volatile private var warned = false

        private fun build(ctx: Context, watching: List<String>): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Automations on", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while Seyal watches for messages or phone events for your skills"; setShowBadge(false)
                })
            }
            val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java).putExtra("tab", "skills").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(if (watching.size == 1) "Watching: ${watching[0]}" else "Watching for ${watching.size} automations")
                .setContentText(watching.joinToString(" · ").take(120))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        /** Starts or stops watching to match the skills now on. Safe to call often, from anywhere. */
        fun sync(ctx: Context) {
            val app = ctx.applicationContext
            val want = Settings.keepWatching && Skills.watching().isNotEmpty()
            when {
                want && running -> runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build(app, Skills.watching())) }
                want -> runCatching { app.startForegroundService(Intent(app, WatchService::class.java)) }.onFailure {
                    // Android doesn't let a background app start this (Android 12+); it starts the next time Seyal is opened.
                    if (!warned) { warned = true; Skills.log("Could not keep running in the background: open Seyal once to start watching") }
                }
                running -> runCatching { app.startService(Intent(app, WatchService::class.java)) }   // it sees nothing to watch and stops
            }
        }
    }
}
