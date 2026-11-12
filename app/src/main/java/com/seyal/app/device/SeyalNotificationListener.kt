package com.seyal.app.device

import android.app.ActivityOptions
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.seyal.app.agent.Skills
import com.seyal.app.data.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full edition: lets Seyal read the notification shade, reply to a message straight from its
 * notification (no app opens), and start skills when an app notifies ("when Amma messages…").
 * Nothing is stored; text is only used for the task or trigger at hand.
 */
class SeyalNotificationListener : NotificationListenerService() {
    private val keywords = listOf("exam", "fees", "fee ", "deadline", "placement", "internship", "interview", "offer", "result", "hall ticket", "assignment", "submission", "timetable")
    /** The same message re-posted (apps update notifications often) counts once; sent again after 2 minutes, it counts again. */
    private val seen = LinkedHashMap<String, Long>()
    private var lastCallKey: String? = null

    private var lastCaller: String? = null

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key != lastCallKey) return
        lastCallKey = null
        // The ringing stopped. If a "missed call" notice shows up, nobody answered: busy mode may text the caller.
        val who = lastCaller ?: return
        lastCaller = null
        if (!Busy.active()) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val missed = runCatching { activeNotifications }.getOrNull()?.any { m ->
                val n = m.notification ?: return@any false
                val (t, x) = textOf(n)
                m.postTime > System.currentTimeMillis() - 30_000 && (n.category == "missed_call" || Regex("missed call|தவறிய அழைப்பு", RegexOption.IGNORE_CASE).containsMatchIn("$t $x"))
            } == true
            if (missed) Busy.onMissed(this, who)
        }, 4_000)
    }

    override fun onListenerConnected() {
        instance = this; ScreenDriver.accessChanged()
        if (Skills.watching().isNotEmpty()) { Skills.log("Notification reader started"); WatchService.sync(this) }
    }
    override fun onListenerDisconnected() {
        if (instance === this) { instance = null; ScreenDriver.accessChanged(); if (Skills.watching().isNotEmpty()) Skills.log("Notification reader stopped by Android: messages are not seen until Seyal is opened") }
    }
    override fun onDestroy() { if (instance === this) { instance = null; ScreenDriver.accessChanged() }; super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        // A ringing phone shows as a "call" notification with the caller's name: "when Amma calls…".
        if (n.category == Notification.CATEGORY_CALL && sbn.key != lastCallKey) {
            val (who, what) = textOf(n)
            // Only a call coming in, not one the user is making or already on.
            if (who.isNotBlank() && !Regex("dialing|calling|ongoing|on hold|missed", RegexOption.IGNORE_CASE).containsMatchIn("$who $what")) { lastCallKey = sbn.key; lastCaller = who; Skills.onEvent("call_from:$who"); runCatching { Busy.onCall(this, who) } }
        }
        // Group summaries and ongoing things (music, downloads, calls) are not news.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.isOngoing) return
        val (title, text) = textOf(n)
        if (title.isBlank() && text.isBlank()) return
        val key = (sbn.packageName + title + text).hashCode().toString()
        val now = System.currentTimeMillis()
        synchronized(seen) {
            if ((seen[key] ?: 0L) > now - 120_000) return
            seen[key] = now
            if (seen.size > 120) seen.keys.take(seen.size - 120).forEach { seen.remove(it) }
        }
        val label = ScreenDriver.appLabel(this, sbn.packageName)
        Skills.onNotification(label, sbn.packageName, title, mask("$title $text").removePrefix("$title "), isGroup(sbn), sbn.key)
        // Deadlines, bills and renewals in what just arrived: caught on the phone (no AI), and only the user is told.
        if (Settings.catchMessages && !Settings.paused) runCatching {
            val now = System.currentTimeMillis()
            com.seyal.app.agent.Catcher.scan(label, title, text, now)?.let { c ->
                if (com.seyal.app.agent.Inbox.add(c, now) && com.seyal.app.agent.Background.mayNotify()) {
                    com.seyal.app.agent.Inbox.told(c.id, now)
                    Notify.caught(this, c, when (c.kind) { "deadline" -> "Deadline"; "bill" -> "Bill"; "renewal" -> "Auto-charge"; "recharge" -> "Recharge"; else -> "Return" })
                }
            }
        }

        if (sbn.packageName == "com.google.android.gm") {
            val all = "$title $text".lowercase()
            val domain = Settings.collegeDomain
            val important = (domain.isNotBlank() && all.contains(domain)) || keywords.any { all.contains(it) }
            if (important) Notify.line(this, "Important mail: $title", null)
        }
    }

    companion object {
        @Volatile private var instance: SeyalNotificationListener? = null
        val connected: Boolean get() = instance != null

        private fun textOf(n: Notification): Pair<String, String> {
            val x = n.extras ?: Bundle()
            val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val big = x.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            val text = big?.takeIf { it.isNotBlank() } ?: x.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
            return title to text
        }

        /** Apps whose notifications are chats (their titles follow the "Group: Sender" habit). */
        private val chatApps = listOf("whatsapp", "telegram", "messaging", "mms", "instagram", "signal", "messenger", "orca", "snapchat", "discord", "slack", "teams", "imo", "viber", "line.android", "truecaller")

        /**
         * Whether a notification comes from a group chat. Android's own flag first; then the group's name
         * the app attached; then WhatsApp's chat id (groups end in @g.us); then the way the title is written.
         */
        internal fun isGroup(sbn: StatusBarNotification): Boolean {
            val n = sbn.notification ?: return false
            val x = n.extras ?: Bundle()
            if (x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)) return true
            if (!x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE).isNullOrBlank()) return true
            if (Build.VERSION.SDK_INT >= 26 && n.shortcutId?.contains("@g.us") == true) return true
            if (sbn.tag?.contains("@g.us") == true) return true
            val chat = n.category == Notification.CATEGORY_MESSAGE || chatApps.any { sbn.packageName.contains(it, true) }
            return chat && com.seyal.app.data.Trigger.looksLikeGroup(textOf(n).first)
        }

        private fun replyAction(n: Notification): Notification.Action? =
            n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

        private fun active(): List<StatusBarNotification> {
            val s = instance ?: throw IllegalStateException("Notification access is off. Control → Notification access → allow Seyal.")
            return (runCatching { s.activeNotifications }.getOrNull() ?: emptyArray()).filter { it.packageName != s.packageName }
        }

        /** What is in the notification shade now, newest first. [app] filters by app name. */
        fun list(app: String, max: Int): JSONArray {
            val s = instance ?: throw IllegalStateException("Notification access is off. Control → Notification access → allow Seyal.")
            val out = JSONArray()
            active().sortedByDescending { it.postTime }.forEach { sbn ->
                val n = sbn.notification ?: return@forEach
                if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return@forEach
                val label = ScreenDriver.appLabel(s, sbn.packageName)
                if (app.isNotBlank() && !label.contains(app, true) && !sbn.packageName.contains(app, true)) return@forEach
                val (title, text) = textOf(n)
                if (title.isBlank() && text.isBlank()) return@forEach
                if (out.length() >= max) return@forEach
                out.put(JSONObject().put("app", label).put("title", title).put("text", mask("$title $text").removePrefix("$title ").take(400)).put("minutes_ago", (System.currentTimeMillis() - sbn.postTime) / 60_000)
                    .put("can_reply", replyAction(n) != null).put("ongoing", sbn.isOngoing))
            }
            return out
        }

        private fun find(match: String, app: String): StatusBarNotification? {
            val s = instance ?: return null
            val m = match.trim()
            return active().sortedByDescending { it.postTime }.firstOrNull { sbn ->
                val n = sbn.notification ?: return@firstOrNull false
                val label = ScreenDriver.appLabel(s, sbn.packageName)
                val (title, text) = textOf(n)
                (app.isBlank() || label.contains(app, true) || sbn.packageName.contains(app, true)) &&
                    (m.isBlank() || title.contains(m, true) || text.contains(m, true) || label.contains(m, true))
            }
        }

        /** Codes in messages that look like OTPs or PINs are hidden before anything is shown to the AI or saved. */
        private val codeContext = Regex("(?i)otp|one.?time|verification|verify|code|passcode|pin\\b|password")
        fun mask(s: String): String = if (codeContext.containsMatchIn(s)) s.replace(Regex("\\b\\d{4,8}\\b"), "••••") else s

        /**
         * The notification a reply to [match] should go to: a title (sender or chat name) that equals it,
         * else one that contains it, else one whose message contains it. Several different chats → an error listing them.
         */
        private fun target(match: String, app: String, key: String? = null, noGroups: Boolean = false): StatusBarNotification {
            val s = instance ?: throw IllegalStateException("Notification access is off. Control → Notification access → allow Seyal.")
            // An automatic reply answers the very message that started it, nothing else.
            if (key != null) {
                val sbn = active().firstOrNull { it.key == key && it.notification?.let { n -> replyAction(n) } != null }
                    ?: throw IllegalStateException("That message's notification is gone, so it can't be answered from here.")
                if (isGroup(sbn)) throw IllegalStateException("That message is in a group chat. Seyal does not answer in groups by itself.")
                return sbn
            }
            val m = match.trim()
            if (m.isBlank()) throw IllegalStateException("Say who to reply to.")
            val replyable = active().sortedByDescending { it.postTime }.filter { sbn ->
                val n = sbn.notification ?: return@filter false
                val label = ScreenDriver.appLabel(s, sbn.packageName)
                replyAction(n) != null && (app.isBlank() || label.contains(app, true) || sbn.packageName.contains(app, true)) && !(noGroups && isGroup(sbn))
            }
            fun titleOf(sbn: StatusBarNotification) = textOf(sbn.notification).first
            val exact = replyable.filter { titleOf(it).equals(m, true) }
            val partial = replyable.filter { titleOf(it).contains(m, true) }
            val body = replyable.filter { textOf(it.notification).second.contains(m, true) }
            val pick = exact.ifEmpty { partial }.ifEmpty { body }
            if (pick.isEmpty()) throw IllegalStateException("No message notification from \"$m\"${if (app.isNotBlank()) " in $app" else ""} that can be replied to. notifications_read shows what is there.")
            val titles = pick.map { titleOf(it) }.distinct()
            if (titles.size > 1) throw IllegalStateException("Several chats match \"$m\": ${titles.take(5).joinToString(", ")}. Say which one.")
            return pick.first()
        }

        /** Who a reply would go to, for the approval card ("Ram Kumar on WhatsApp"), or null. */
        fun resolve(match: String, app: String): String? = runCatching {
            val s = instance ?: return null
            val sbn = target(match, app)
            "${textOf(sbn.notification).first}${if (isGroup(sbn)) " (GROUP chat: everyone in it will see this)" else ""} on ${ScreenDriver.appLabel(s, sbn.packageName)}"
        }.getOrNull()

        /** Replies from the notification itself (WhatsApp, Messages, Telegram… all support this). Returns who it went to. */
        fun reply(match: String, app: String, text: String, key: String? = null, noGroups: Boolean = false): String {
            val s = instance ?: throw IllegalStateException("Notification access is off. Control → Notification access → allow Seyal.")
            val sbn = target(match, app, key, noGroups)
            val action = replyAction(sbn.notification)!!
            val inputs = action.remoteInputs!!
            val results = Bundle()
            inputs.forEach { ri -> if (ri.allowFreeFormInput) results.putCharSequence(ri.resultKey, text) }
            val intent = Intent()
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(s, 0, intent)
            return "${textOf(sbn.notification).first.ifBlank { "the sender" }} (${ScreenDriver.appLabel(s, sbn.packageName)})"
        }

        /** Opens what the notification points to. */
        fun open(match: String, app: String): String {
            val s = instance ?: throw IllegalStateException("Notification access is off.")
            val sbn = find(match, app) ?: throw IllegalStateException("No notification matches \"$match\".")
            val pi = sbn.notification.contentIntent ?: throw IllegalStateException("That notification can't be opened.")
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+: say explicitly that this tap may open an app from the background.
                val opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
                pi.send(s, 0, null, null, null, null, opts)
            } else pi.send()
            return textOf(sbn.notification).first
        }

        /** Dismisses matching notifications (all clearable ones when both are blank). Returns how many. */
        fun dismiss(match: String, app: String): Int {
            val s = instance ?: throw IllegalStateException("Notification access is off. Control → Notification access → allow Seyal.")
            if (match.isBlank() && app.isBlank()) { val n = active().count { it.isClearable }; s.cancelAllNotifications(); return n }
            var n = 0
            active().forEach { sbn ->
                val label = ScreenDriver.appLabel(s, sbn.packageName)
                val (title, text) = textOf(sbn.notification ?: return@forEach)
                val hit = (app.isBlank() || label.contains(app, true) || sbn.packageName.contains(app, true)) && (match.isBlank() || title.contains(match, true) || text.contains(match, true))
                if (hit && sbn.isClearable) { s.cancelNotification(sbn.key); n++ }
            }
            return n
        }
    }
}
