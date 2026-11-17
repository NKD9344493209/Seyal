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

    /** Something only the user can put right: tapping it opens the settings page where they do. */
    fun fix(ctx: Context, title: String, body: String, open: Intent) {
        if (!allowed(ctx)) return
        val pi = PendingIntent.getActivity(ctx, title.hashCode(), open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_ASK)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title.take(60))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .addAction(0, "Fix now", pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(title.hashCode(), n)
    }

    /** A result with a title and the whole answer: pull it down to read it all, tap to open the chat. */
    fun result(ctx: Context, title: String, body: String, sessionId: String? = null) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_RESULTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title.take(60))
            .setContentText(body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(ctx, sessionId))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify((sessionId ?: title).hashCode(), n)
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

    /** Something caught from a message (a deadline, a bill): what, when, where from, with Done / Open / Not for me. */
    fun caught(ctx: Context, c: com.seyal.app.agent.Caught, lead: String) {
        if (!allowed(ctx)) return
        val now = System.currentTimeMillis()
        val whenText = com.seyal.app.agent.Catcher.whenText(c.due, c.hasTime, now)
        fun act(action: String) = PendingIntent.getBroadcast(
            ctx, (action + c.id).hashCode(),
            Intent(ctx, com.seyal.app.agent.ActionReceiver::class.java).setAction(action).putExtra("caught", c.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(ctx, CH_ASK)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$lead · due $whenText".take(70))
            .setContentText(c.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${c.title}\n${c.source}\n\n${c.text.take(300)}"))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .addAction(0, "Done", act("com.seyal.app.CAUGHT_DONE"))
        if (c.link.isNotBlank()) runCatching {
            val open = PendingIntent.getActivity(ctx, ("open" + c.id).hashCode(), Intent(Intent.ACTION_VIEW, android.net.Uri.parse(c.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, if (c.kind == "deadline") "Open form" else "Open", open)
        }
        b.addAction(0, "Not for me", act("com.seyal.app.CAUGHT_DROP"))
        NotificationManagerCompat.from(ctx).notify("caught${c.id}".hashCode(), b.build())
    }

    private fun action(ctx: Context, code: String, fill: Intent.() -> Unit): PendingIntent = PendingIntent.getBroadcast(
        ctx, code.hashCode(), Intent(ctx, com.seyal.app.agent.ActionReceiver::class.java).setAction("com.seyal.app.$code").apply(fill),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Today's piece of a plan, with one-tap answers. [adjust]: it has slipped several times, so offer to lighten or pause it. */
    fun plan(ctx: Context, planId: String, title: String, body: String, adjust: Boolean) {
        if (!allowed(ctx)) return
        fun act(state: String) = action(ctx, "PLAN_${state.uppercase()}_$planId") { putExtra("plan", planId); putExtra("state", state) }
        val b = NotificationCompat.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title.take(70))
            .setContentText(body.lines().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(0, "Done", act("done"))
        if (adjust) b.addAction(0, "Make it easier", act("easier")).addAction(0, "Pause", act("pause"))
        else b.addAction(0, "Later", act("later")).addAction(0, "Skip", act("skip"))
        NotificationManagerCompat.from(ctx).notify("plan$planId".hashCode(), b.build())
        if (com.seyal.app.data.Settings.speak) runCatching { Speech.say(ctx, title) }
    }

    /** An offer to automate something the user keeps doing by hand: Yes / No thanks. */
    fun offer(ctx: Context, key: String, title: String, text: String) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_ASK)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title.take(60))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .addAction(0, "Yes, do it", action(ctx, "OFFER_YES") { putExtra("offer", key); putExtra("yes", true) })
            .addAction(0, "No thanks", action(ctx, "OFFER_NO") { putExtra("offer", key); putExtra("yes", false) })
            .build()
        NotificationManagerCompat.from(ctx).notify("offer".hashCode(), n)
    }

    /** A message that looks like a scam: say why, and what not to do. "Not a scam" stops warnings for that sender. */
    fun scam(ctx: Context, sender: String, why: String) {
        if (!allowed(ctx)) return
        val trust = PendingIntent.getBroadcast(
            ctx, ("trust$sender").hashCode(),
            Intent(ctx, com.seyal.app.agent.ActionReceiver::class.java).setAction("com.seyal.app.SCAM_OK").putExtra("scam_sender", sender),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "$why\nDon't tap its link, don't call its number, and don't share any code."
        val n = NotificationCompat.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Careful: the message from ${sender.take(30)} looks like a scam")
            .setContentText(why)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .addAction(0, "Not a scam", trust)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        NotificationManagerCompat.from(ctx).notify(("scam$sender").hashCode(), n)
        if (com.seyal.app.data.Settings.simpleMode) runCatching { Speech.say(ctx, "Careful. The new message from $sender looks like a scam. Do not open its link or share any code.") }
    }

    /** A watcher's news, or anything else with a page to open: tap opens [url]. */
    fun alert(ctx: Context, id: String, title: String, text: String, url: String) {
        if (!allowed(ctx)) return
        val pi = runCatching {
            PendingIntent.getActivity(ctx, id.hashCode(), Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }.getOrNull() ?: openApp(ctx)
        val n = NotificationCompat.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title.take(60))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .addAction(0, "Open", pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        NotificationManagerCompat.from(ctx).notify(id.hashCode(), n)
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

    fun cancel(ctx: Context, tag: String) { runCatching { NotificationManagerCompat.from(ctx).cancel(tag.hashCode()) } }

    /** Opens Seyal on the Brain tab; with [id], also answers that thought (used where the answer must open something, like a call). */
    private fun openBrain(ctx: Context, id: String? = null, answer: String? = null): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("tab", "brain")
        if (id != null) intent.putExtra("brain", id).putExtra("answer", answer)
        return PendingIntent.getActivity(ctx, ("brain" + id + answer).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * The Brain speaking first: something it noticed ([t] tells), offers (one-tap answers) or has done (with Undo).
     * [silent]: no sound (night hours, unless urgent).
     */
    fun brain(ctx: Context, t: com.seyal.app.agent.Brain.Thought, silent: Boolean) {
        if (!allowed(ctx)) return
        val asks = t.level == "suggest"
        val body = when {
            t.level == "did" -> t.result
            t.why.isNotBlank() -> "${t.say}\n${t.why}"
            else -> t.say
        }
        val b = NotificationCompat.Builder(ctx, if (asks || t.urgent) CH_ASK else CH_RESULTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle((if (t.level != "did") t.who else if (t.state == "failed") "Could not do it · ${t.who}" else "Done for you · ${t.who}").take(60))
            .setContentText((if (t.level == "did") t.result else t.say).take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openBrain(ctx))
            .setAutoCancel(true)
            .setSilent(silent)
        t.buttons().forEach { (label, code) ->
            // A call has to open the dialer, which only a tap that opens the app may do; the rest are answered in place.
            val pi = if (t.kind == "call" && code == "yes") openBrain(ctx, t.id, code)
            else action(ctx, "BRAIN_${code}_${t.id}") { putExtra("brain", t.id); putExtra("answer", code) }
            b.addAction(0, label, pi)
        }
        NotificationManagerCompat.from(ctx).notify("brain${t.id}".hashCode(), b.build())
        if (!silent && com.seyal.app.data.Settings.speak && (asks || t.urgent)) runCatching { Speech.say(ctx, t.say) }
    }

    /** After three yeses in a row: "shall I do these by myself from now on?" */
    fun brainAuto(ctx: Context, kind: String) {
        if (!allowed(ctx)) return
        val what = com.seyal.app.agent.Brain.kindName(kind)
        val text = "You said yes to three $what in a row. Shall I set them by myself from now on, and just tell you? Each one will have Undo."
        val n = NotificationCompat.Builder(ctx, CH_ASK)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Do $what by myself?")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openBrain(ctx))
            .setAutoCancel(true)
            .addAction(0, "Yes, do them", action(ctx, "BRAIN_AUTO_YES_$kind") { putExtra("brain", ""); putExtra("answer", "auto_yes:$kind") })
            .addAction(0, "Keep asking", action(ctx, "BRAIN_AUTO_NO_$kind") { putExtra("brain", ""); putExtra("answer", "auto_no:$kind") })
            .build()
        NotificationManagerCompat.from(ctx).notify("brainauto$kind".hashCode(), n)
    }

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
