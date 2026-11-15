package com.seyal.app.device

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import com.seyal.app.agent.Habits
import com.seyal.app.agent.ToolContext
import com.seyal.app.agent.ToolResult
import com.seyal.app.agent.Toolbox
import com.seyal.app.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The few things Seyal notices about how the phone is used, while the app is alive: that it was
 * unlocked (for the family check-in), and when Do Not Disturb, the ringer or the headphones change
 * (so a daily habit can be offered as an automation). Nothing else is recorded.
 */
object Signals {
    private var registered = false
    /** When headphones were last connected; the app opened right after is the habit worth offering. */
    @Volatile private var headsetAt = 0L

    fun start(ctx: Context) {
        if (registered) return
        registered = true
        val c = ctx.applicationContext
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    val now = System.currentTimeMillis()
                    when (intent.action) {
                        Intent.ACTION_USER_PRESENT -> Settings.lastUnlock = now
                        NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                            if (isInitialStickyBroadcast) return
                            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            Habits.log(if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) "dnd:off" else "dnd:on", now)
                        }
                        AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                            if (isInitialStickyBroadcast) return
                            when (intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)) {
                                AudioManager.RINGER_MODE_SILENT -> Habits.log("ringer:silent", now)
                                AudioManager.RINGER_MODE_VIBRATE -> Habits.log("ringer:vibrate", now)
                            }
                        }
                        Intent.ACTION_HEADSET_PLUG -> if (!isInitialStickyBroadcast && intent.getIntExtra("state", 0) == 1) headsetAt = now
                    }
                }
            }
        }
        runCatching { if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else c.registerReceiver(r, f) }
    }

    /** Bluetooth headphones count too. */
    fun headsetConnected() { headsetAt = System.currentTimeMillis() }

    private val notAnApp = Regex("launcher|systemui|settings|com\\.seyal\\.app|inputmethod|keyboard|permissioncontroller|dialer|incallui", RegexOption.IGNORE_CASE)

    /** An app came to the front. If headphones went in less than 90 seconds ago, that pairing is noted once. */
    fun onApp(ctx: Context, pkg: String) {
        val at = headsetAt
        if (at == 0L || pkg.isBlank() || notAnApp.containsMatchIn(pkg)) return
        val now = System.currentTimeMillis()
        headsetAt = 0L.takeIf { now - at <= 90_000 } ?: 0L
        if (now - at > 90_000) return
        Habits.log("headset>" + ScreenDriver.appLabel(ctx, pkg), now)
    }
}

/**
 * The family check-in, for a parent's phone: if the phone has not been unlocked by a set time of
 * day, one text goes to the family member the owner chose. Set up by the owner in Control; one text
 * a day at most.
 */
object Guardian {
    private val scope = CoroutineScope(Dispatchers.IO)
    private fun zone() = ZoneId.systemDefault()
    private fun timeToday(now: Long): Long {
        val t = runCatching { LocalTime.parse(Settings.guardianTime) }.getOrDefault(LocalTime.of(11, 0))
        return Instant.ofEpochMilli(now).atZone(zone()).toLocalDate().atTime(t).atZone(zone()).toInstant().toEpochMilli()
    }

    /** Whether a check-in text is due: [unlockedAt] is before today, the time has come, none was sent today. Pure, for tests. */
    fun due(now: Long, checkAt: Long, unlockedAt: Long, sentDay: String, today: LocalDate, startOfToday: Long): Boolean =
        now >= checkAt && sentDay != today.toString() && unlockedAt < startOfToday

    /** When the next check is (epoch ms), or null when the check-in is off. */
    fun next(now: Long): Long? {
        if (!Settings.guardianOn || Settings.guardianNumber.filter { it.isDigit() }.length < 10) return null
        val today = timeToday(now)
        val day = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
        return if (now < today && Settings.guardianSentDay != day.toString()) today else today + 24 * 3600_000L
    }

    fun check(ctx: Context, now: Long) {
        if (!Settings.guardianOn || Settings.paused) return
        val day = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
        val start = day.atStartOfDay(zone()).toInstant().toEpochMilli()
        // Checked today already (texted, or found fine): once a day is enough.
        if (now < timeToday(now) || Settings.guardianSentDay == day.toString()) return
        val quiet = due(now, timeToday(now), Settings.lastUnlock, Settings.guardianSentDay, day, start)
        Settings.guardianSentDay = day.toString()
        if (!quiet) return
        val number = Settings.guardianNumber
        val who = Settings.userName.ifBlank { "This phone's owner" }
        val text = "Seyal check-in: $who has not used their phone today (as of ${Settings.guardianTime}). Please call and check on them."
        scope.launch {
            val tool = Toolbox.get("send_sms") ?: return@launch
            val r = runCatching { tool.run(JSONObject().put("phone", number).put("text", text), ToolContext("guardian", { emptyList() }, { _, _ -> "" })) }.getOrNull()
            Notify.line(ctx, if (r is ToolResult.Done) "Family check-in: this phone was not used this morning, so a text went to $number." else "Family check-in could not send its text (SMS permission?).")
        }
    }
}
