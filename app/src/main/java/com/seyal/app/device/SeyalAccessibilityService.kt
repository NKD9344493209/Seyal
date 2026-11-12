package com.seyal.app.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import com.seyal.app.agent.Skills

/**
 * Seyal's eyes and hands (Full edition). It does nothing on its own: [ScreenDriver] asks it to read
 * the screen, tap, type or scroll while the agent is working on a task the user asked for.
 * It never records keystrokes and never reads password fields.
 */
class SeyalAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        ScreenDriver.attach(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ScreenDriver.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ScreenDriver.detach(this)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        val own = pkg == packageName
        // Seyal's own status pill must not count as "the screen changed" or "Seyal came to the front".
        val cls = e.className?.toString() ?: ""
        if (own && !cls.startsWith("com.seyal.app")) return
        ScreenDriver.noteChange()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !own) AdSkipper.onWindow(cls)
        AdSkipper.onScreenChanged(this, pkg)
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (ScreenDriver.noteForeground(pkg)) Skills.onEvent("app_opened:" + ScreenDriver.appLabel(this, pkg))
        }
    }
}
