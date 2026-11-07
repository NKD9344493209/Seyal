package com.seyal.app.device

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred

/**
 * Does exactly one thing: when Seyal has opened a WhatsApp chat with a typed message for an
 * auto-send contact, it taps the Send button once. It never reads messages or passwords.
 */
class SeyalAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val waiting = pending ?: return
        if (waiting.isCompleted) return
        if (System.currentTimeMillis() > armedUntil) { pending = null; return }
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!pkg.startsWith("com.whatsapp")) return
        val send = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()
            ?: root.findAccessibilityNodeInfosByText("Send").firstOrNull { it.isClickable }
            ?: return
        val entry = root.findAccessibilityNodeInfosByViewId("$pkg:id/entry").firstOrNull()
        if (entry != null && entry.text.isNullOrBlank()) return // message not typed yet
        if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            waiting.complete(true)
            pending = null
            // Go back home so the phone is as the user left it.
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    companion object {
        @Volatile var running: Boolean = false
            private set
        @Volatile private var pending: CompletableDeferred<Boolean>? = null
        @Volatile private var armedUntil: Long = 0L

        val isRunning: Boolean get() = running

        fun armSend() {
            pending = CompletableDeferred()
            armedUntil = System.currentTimeMillis() + 25_000
        }

        suspend fun awaitSend(): Boolean = pending?.await() ?: false

        fun disarm() { pending = null; armedUntil = 0 }
    }
}
