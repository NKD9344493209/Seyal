package com.seyal.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.seyal.app.data.Settings
import com.seyal.app.data.StepStatus
import com.seyal.app.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Test hook (debug builds only, adb-only via DUMP permission).
 *   am broadcast -n com.seyal.app/.TestReceiver --es key KEY [--es model M] [--es name N]
 *   am broadcast -n com.seyal.app/.TestReceiver --es command "text"
 *   am broadcast -n com.seyal.app/.TestReceiver --ez approve_all true
 *   am broadcast -n com.seyal.app/.TestReceiver --ei choose 0
 */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("key")?.let {
            Settings.provider = "gemini"
            Settings.apiKey = it
            Settings.model = intent.getStringExtra("model") ?: ""
        }
        intent.getStringExtra("name")?.let { Settings.userName = it }
        intent.getStringExtra("command")?.let { cmd -> scope.launch { Engine.submit(cmd) } }
        val newest = Engine.runs.value.firstOrNull()
        if (newest != null && intent.getBooleanExtra("approve_all", false)) Engine.approveAll(newest.id, true)
        if (newest != null && intent.hasExtra("choose")) {
            val idx = intent.getIntExtra("choose", 0)
            newest.steps.firstOrNull { it.status == StepStatus.WAITING_CHOICE }?.let { Engine.choose(newest.id, it.id, idx) }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
