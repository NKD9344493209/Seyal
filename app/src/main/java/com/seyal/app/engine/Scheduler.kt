package com.seyal.app.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.seyal.app.data.Settings
import com.seyal.app.data.Store
import com.seyal.app.data.Timer
import com.seyal.app.data.newId
import com.seyal.app.device.AlarmActivity
import com.seyal.app.device.Notify
import com.seyal.app.data.Recipe

/**
 * Exact alarms for things that must ring at a time (alarms, reminders, recipes);
 * WorkManager runs the recipe itself so it survives the app being closed.
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

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

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

    fun schedule(ctx: Context, recipe: Recipe) {
        if (!recipe.enabled || recipe.nextRun <= 0) { cancel(ctx, recipe); return }
        set(ctx, "recipe", recipe.id, recipe.nextRun, recipe.name)
    }

    fun cancel(ctx: Context, recipe: Recipe) {
        am(ctx).cancel(pending(ctx, "recipe", recipe.id, 0, ""))
    }

    fun cancelAll(ctx: Context) {
        Engine.recipes.value.forEach { cancel(ctx, it) }
    }

    /** After boot, time change or app update: put every alarm and recipe back. */
    fun rescheduleAll(ctx: Context) {
        if (Settings.paused) return
        val now = System.currentTimeMillis()
        Store.loadTimers().forEach { t ->
            if (t.at > now) set(ctx, t.kind, t.id, t.at, t.text)
            else if (t.at > now - 30 * 60_000) fire(ctx, t.kind, t.id, t.at, t.text) // missed by a little: fire now
        }
        Store.saveTimers(Store.loadTimers().filter { it.at > now })
        Engine.recipes.value.filter { it.enabled }.forEach { r ->
            if (r.nextRun <= now) r.nextRun = Times.nextFire(r.trigger)
            schedule(ctx, r)
        }
    }

    fun fire(ctx: Context, kind: String, id: String, at: Long, text: String) {
        when (kind) {
            "alarm" -> {
                forget("alarm", id)
                AlarmActivity.ring(ctx, id, text)
            }
            "remind" -> {
                forget("remind", id)
                Notify.reminder(ctx, id, text)
            }
            "recipe" -> {
                val req = OneTimeWorkRequestBuilder<RecipeWorker>()
                    .setInputData(workDataOf("recipe_id" to id))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(ctx).enqueueUniqueWork("recipe-$id-$at", ExistingWorkPolicy.KEEP, req)
            }
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

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.rescheduleAll(context)
    }
}

/** Runs one recipe. The Application is created first, so the engine is ready. */
class RecipeWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val id = inputData.getString("recipe_id") ?: return Result.failure()
        Engine.runRecipe(id, fromSchedule = true)
        // Keep the process alive a little so the steps can finish.
        var waited = 0
        while (waited < 120_000) {
            Thread.sleep(2000); waited += 2000
            val open = Engine.runs.value.any { it.recipeId == id && it.status.isOpen && it.status != com.seyal.app.data.RunStatus.WAITING }
            if (!open) break
        }
        return Result.success()
    }
}

/** Allow / Deny buttons on approval notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getStringExtra("run") ?: return
        val stepId = intent.getStringExtra("step") ?: return
        when (intent.action) {
            "com.seyal.app.ALLOW" -> Engine.approve(runId, stepId, true)
            "com.seyal.app.DENY" -> Engine.approve(runId, stepId, false)
        }
    }
}
