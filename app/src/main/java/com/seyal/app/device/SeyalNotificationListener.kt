package com.seyal.app.device

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.seyal.app.data.Settings

/**
 * Phase 1 mail alerts: watches Gmail notifications and re-raises the important ones
 * (your college domain, exams, fees, placement). Nothing is stored.
 */
class SeyalNotificationListener : NotificationListenerService() {
    private val keywords = listOf("exam", "fees", "fee ", "deadline", "placement", "internship", "interview", "offer", "result", "hall ticket", "assignment", "submission", "timetable")
    private val seen = ArrayDeque<String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.google.android.gm") return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val all = "$title $text $sub".lowercase()
        val key = (title + text).hashCode().toString()
        if (seen.contains(key)) return
        seen.addLast(key); if (seen.size > 50) seen.removeFirst()
        val domain = Settings.collegeDomain
        val important = (domain.isNotBlank() && all.contains(domain)) || keywords.any { all.contains(it) }
        if (!important) return
        Notify.line(this, "Important mail: $title", null)
    }
}
