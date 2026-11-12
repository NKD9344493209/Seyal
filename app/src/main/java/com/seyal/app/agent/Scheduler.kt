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
import kotlinx.coroutines.launch

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

    /** Busy mode ends at [at]. */
    fun setBusyEnd(ctx: Context, at: Long) { remember("busy", "end", at, ""); set(ctx, "busy", "end", at, "") }

    /**
     * One wake-up for the quiet background work: reminders about caught deadlines and bills, and the
     * next check of watched pages. Set again after every change.
     */
    fun syncBackground(ctx: Context) {
        val now = System.currentTimeMillis()
        val next = listOfNotNull(Inbox.nextReminder(now), Watchers.nextCheck(now)).minOrNull()
        if (next == null) { am(ctx).cancel(pending(ctx, "tick", "bg", 0, "")); forget("tick", "bg"); return }
        val at = maxOf(next, now + 5_000)
        remember("tick", "bg", at, "")
        // Not an alarm-clock alarm (those show an alarm icon): a quiet wake-up that still fires while the phone dozes.
        runCatching { am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, "tick", "bg", at, "")) }
    }

    /** Pending alarms and reminders. */
    fun pendingTimers(): List<Timer> = Store.loadTimers().filter { it.at > System.currentTimeMillis() && it.kind in setOf("alarm", "remind") }.sortedBy { it.at }

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
            if (t.kind == "tick") return@forEach
            if (t.at > now) set(ctx, t.kind, t.id, t.at, t.text)
            else if (t.at > now - 30 * 60_000) fire(ctx, t.kind, t.id, t.at, t.text) // missed by a little: fire now
        }
        Store.saveTimers(Store.loadTimers().filter { it.at > now })
        Skills.rescheduleAll()
        syncBackground(ctx)
    }

    fun fire(ctx: Context, kind: String, id: String, at: Long, text: String) {
        when (kind) {
            "alarm" -> { forget("alarm", id); AlarmActivity.ring(ctx, id, text) }
            "remind" -> { forget("remind", id); Notify.reminder(ctx, id, text) }
            // The alarm-clock broadcast lets the app start its "working" service, which keeps it alive while the skill runs.
            "skill" -> Skills.run(id, fromTrigger = true)
            "busy" -> { forget("busy", id); com.seyal.app.device.Busy.stop(ctx) }
            "tick" -> { forget("tick", id); Background.tick(ctx) }
        }
    }
}

class SchedulerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Scheduler.ACTION_FIRE) return
        if (intent.getStringExtra("kind") == "tick") {
            // Background checks take a few seconds of network: hold the broadcast open until they finish.
            val pending = goAsync()
            Store.saveTimers(Store.loadTimers().filterNot { it.kind == "tick" })
            Background.tick(context) { runCatching { pending.finish() } }
            return
        }
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
        // Buttons on a "caught for you" notification.
        intent.getStringExtra("caught")?.let { id ->
            Inbox.mark(id, if (intent.action == "com.seyal.app.CAUGHT_DROP") "dismissed" else "done")
            runCatching { androidx.core.app.NotificationManagerCompat.from(context).cancel("caught$id".hashCode()) }
            return
        }
        val callId = intent.getStringExtra("call") ?: return
        when (intent.action) {
            "com.seyal.app.ALLOW" -> Agent.decide(callId, "allow")
            "com.seyal.app.DENY" -> Agent.decide(callId, "deny")
        }
    }
}

/** The quiet background work: reminders about what was caught, and checks of watched pages. No AI is used. */
object Background {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Whether one more "caught" notification may be shown today (at most [max] a day; the rest wait in the list). */
    fun mayNotify(max: Int = 8): Boolean {
        val today = java.time.LocalDate.now().toString()
        val (day, n) = Settings.caughtToday.split(':').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        val count = if (day == today) n else 0
        if (count >= max) return false
        Settings.caughtToday = "$today:${count + 1}"
        return true
    }

    /** [done] is called when the work is over (the alarm's receiver waits for it, so Android keeps the app alive meanwhile). */
    fun tick(ctx: Context, done: () -> Unit = {}) {
        if (!running.compareAndSet(false, true)) { done(); return }
        // Keep the phone awake for the few seconds this takes, even from deep sleep.
        val wake = runCatching {
            (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "seyal:background").apply { acquire(45_000) }
        }.getOrNull()
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                // 1. Reminders whose time has come.
                Inbox.dueReminders(now).forEach { c -> Inbox.told(c.id, now); Notify.caught(ctx, c, "Reminder") }
                // 2. Watched pages whose turn it is.
                // A few per wake-up, so it always finishes in time; the rest follow a minute later.
                if (!Settings.paused) Watchers.dueNow(now).take(3).forEach { w ->
                    val page = runCatching { com.seyal.app.util.Parsers.strip(com.seyal.app.tools.Common.http(w.url, timeoutMs = 8_000)) }.getOrNull()
                    Watchers.record(w, page, System.currentTimeMillis())?.let { say -> Notify.alert(ctx, "watch${w.id}", if (w.enabled || w.hitAt > 0) "It's time: ${w.what}" else "Stopped watching", say, w.url) }
                }
            } finally {
                running.set(false)
                runCatching { Scheduler.syncBackground(ctx) }
                runCatching { if (wake?.isHeld == true) wake.release() }
                runCatching { done() }
            }
        }
    }
}
