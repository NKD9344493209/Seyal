package com.seyal.app.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.seyal.app.data.Settings
import com.seyal.app.data.Skill
import com.seyal.app.data.Store
import com.seyal.app.data.Timer
import com.seyal.app.data.newId
import com.seyal.app.device.AlarmActivity
import com.seyal.app.device.Notify

/**
 * Exact alarms for things that must happen at a time (alarms, reminders, timed skills);
 * a skill then runs as a normal task, kept alive by the "working" foreground service.
 */
object Scheduler {
    const val ACTION_FIRE = "com.seyal.app.FIRE"

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(ctx: Context, kind: String, id: String, at: Long, text: String): PendingIntent {
        val intent = Intent(ctx, SchedulerReceiver::class.java).setAction(ACTION_FIRE)
            .putExtra("kind", kind).putExtra("id", id).putExtra("at", at).putExtra("text", text)
            .setData(android.net.Uri.parse("seyal://$kind/$id"))
        return PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun canExact(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    private fun set(ctx: Context, kind: String, id: String, at: Long, text: String) {
        val pi = pending(ctx, kind, id, at, text)
        val mgr = am(ctx)
        runCatching {
            if (canExact(ctx)) mgr.setAlarmClock(AlarmManager.AlarmClockInfo(at, pi), pi)
            else mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure { mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    private fun remember(kind: String, id: String, at: Long, text: String) {
        val timers = Store.loadTimers().filterNot { it.kind == kind && it.id == id }.toMutableList()
        timers += Timer(kind, id, at, text)
        Store.saveTimers(timers.filter { it.at > System.currentTimeMillis() - 60_000 })
    }

    private fun forget(kind: String, id: String) {
        Store.saveTimers(Store.loadTimers().filterNot { it.kind == kind && it.id == id })
    }

    fun setAlarm(ctx: Context, at: Long, label: String): String {
        val id = newId()
        remember("alarm", id, at, label)
        set(ctx, "alarm", id, at, label)
        return id
    }

    fun setReminder(ctx: Context, at: Long, text: String): String {
        val id = newId()
        remember("remind", id, at, text)
        set(ctx, "remind", id, at, text)
        return id
    }

    /** Pending alarms and reminders. */
    fun pendingTimers(): List<Timer> = Store.loadTimers().filter { it.at > System.currentTimeMillis() }.sortedBy { it.at }

    fun cancelTimer(ctx: Context, kind: String, id: String) {
        am(ctx).cancel(pending(ctx, kind, id, 0, ""))
        forget(kind, id)
    }

    fun schedule(ctx: Context, skill: Skill) {
        if (!skill.enabled || skill.trigger?.isTime != true || skill.nextRun <= 0) { cancel(ctx, skill); return }
        set(ctx, "skill", skill.id, skill.nextRun, skill.name)
    }

    fun cancel(ctx: Context, skill: Skill) {
        am(ctx).cancel(pending(ctx, "skill", skill.id, 0, ""))
    }

    fun cancelAll(ctx: Context) {
        Skills.all().forEach { cancel(ctx, it) }
    }

    /** After boot, time change or app update: put every alarm and timed skill back. */
    fun rescheduleAll(ctx: Context) {
        if (Settings.paused) return
        val now = System.currentTimeMillis()
        Store.loadTimers().forEach { t ->
            if (t.at > now) set(ctx, t.kind, t.id, t.at, t.text)
            else if (t.at > now - 30 * 60_000) fire(ctx, t.kind, t.id, t.at, t.text) // missed by a little: fire now
        }
        Store.saveTimers(Store.loadTimers().filter { it.at > now })
        Skills.rescheduleAll()
    }

    fun fire(ctx: Context, kind: String, id: String, at: Long, text: String) {
        when (kind) {
            "alarm" -> { forget("alarm", id); AlarmActivity.ring(ctx, id, text) }
            "remind" -> { forget("remind", id); Notify.reminder(ctx, id, text) }
            // The alarm-clock broadcast lets the app start its "working" service, which keeps it alive while the skill runs.
            "skill" -> Skills.run(id, fromTrigger = true)
        }
    }
}

class SchedulerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Scheduler.ACTION_FIRE) return
        Scheduler.fire(
            context,
            intent.getStringExtra("kind") ?: return,
            intent.getStringExtra("id") ?: return,
            intent.getLongExtra("at", 0L),
            intent.getStringExtra("text") ?: "",
        )
    }
}

/** Boot, time changes, charging: puts alarms back and fires event-triggered skills. */
class EventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Scheduler.rescheduleAll(context)
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) Skills.onEvent("boot")
                // Start watching again for message and event skills (allowed right after boot or an update).
                com.seyal.app.device.WatchService.sync(context)
            }
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Scheduler.rescheduleAll(context)
            Intent.ACTION_POWER_CONNECTED -> Skills.onEvent("charging")
            Intent.ACTION_POWER_DISCONNECTED -> Skills.onEvent("unplugged")
        }
    }
}

/** Buttons on approval notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.seyal.app.STOP_ALL") { Agent.cancelAll(); return }
        val callId = intent.getStringExtra("call") ?: return
        when (intent.action) {
            "com.seyal.app.ALLOW" -> Agent.decide(callId, "allow")
            "com.seyal.app.DENY" -> Agent.decide(callId, "deny")
        }
    }
}
