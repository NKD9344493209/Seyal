package com.seyal.app.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings as SysSettings
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.seyal.app.agent.Scheduler
import com.seyal.app.data.Settings
import java.util.concurrent.TimeUnit

/**
 * A second heartbeat. Alarms are what run a skill at 8:00, but some phones throw an app's alarms away when they
 * close it. Android's own job system starts Seyal again every 15 minutes or so; each start puts the alarms back and
 * notices anything that should have run and did not (see Skills.rescheduleAll), so a missed time is caught within
 * minutes instead of being lost until the app is next opened.
 */
class Pulse(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        runCatching { Scheduler.rescheduleAll(applicationContext) }
        runCatching { ScreenWatch.check(applicationContext) }
        runCatching { com.seyal.app.agent.ForesightRun.weekly(applicationContext) }
        return Result.success()
    }

    companion object {
        fun start(ctx: Context) {
            runCatching {
                val work = PeriodicWorkRequest.Builder(Pulse::class.java, 15, TimeUnit.MINUTES).build()
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("seyal-pulse", ExistingPeriodicWorkPolicy.KEEP, work)
            }
        }
    }
}

/**
 * Screen control (the accessibility service) is what lets Seyal work inside other apps and show its progress bar.
 * Several phone brands switch it off by themselves when they close an app. Seyal cannot switch it back on (only the
 * user can), but it must never leave the user wondering why nothing works: it says so, once, with one tap to fix it.
 */
object ScreenWatch {
    /**
     * The phone switched Screen control off: it was on ([wasOn]; cleared when the user turns it off the normal way),
     * and Seyal's service is no longer in Android's list of switched-on services ([enabled]).
     */
    fun dropped(wasOn: Boolean, enabled: String?, component: String, shortComponent: String): Boolean {
        if (!wasOn) return false
        val list = enabled.orEmpty().split(':').map { it.trim() }
        return list.none { it.equals(component, true) || it.equals(shortComponent, true) }
    }

    /** The Settings app was in front: the user is the one switching things there. */
    fun userAtSettings(frontPackage: String): Boolean = Regex("settings|securitycenter|permissioncontroller|systemmanager|safecenter", RegexOption.IGNORE_CASE).containsMatchIn(frontPackage)

    fun check(ctx: Context) {
        if (!Settings.screenWasOn) return
        val me = ComponentName(ctx, SeyalAccessibilityService::class.java)
        val enabled = runCatching { SysSettings.Secure.getString(ctx.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }.getOrNull()
        if (!dropped(true, enabled, me.flattenToString(), me.flattenToShortString())) return
        // Tell once per drop: the flag goes off now and comes back on when the user switches the service on again.
        Settings.screenWasOn = false
        runCatching { Settings.killedCount = Settings.killedCount + 1; Settings.killedAt = System.currentTimeMillis() }
        runCatching {
            Notify.fix(ctx, "Your phone switched off Seyal's Screen control",
                "Without it Seyal cannot work inside other apps or show its progress bar. Tap, choose Seyal, and switch it On. To stop the phone doing this again: Control → Keep Seyal running.",
                Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}
