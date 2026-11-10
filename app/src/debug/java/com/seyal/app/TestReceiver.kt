package com.seyal.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.seyal.app.agent.Agent
import com.seyal.app.data.CallStatus
import com.seyal.app.data.Settings

/**
 * Test hook (debug builds only, adb-only via the DUMP permission).
 *   am broadcast -n com.seyal.app/.TestReceiver --es key KEY [--es model M] [--es name N]
 *   am broadcast -n com.seyal.app/.TestReceiver --es command "text"      (new conversation)
 *   am broadcast -n com.seyal.app/.TestReceiver --es say "text"          (follow-up in the newest conversation)
 *   am broadcast -n com.seyal.app/.TestReceiver --ez approve_all true
 *   am broadcast -n com.seyal.app/.TestReceiver --es answer "text"
 */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("key")?.let {
            Settings.provider = "gemini"
            Settings.apiKey = it
            Settings.model = intent.getStringExtra("model") ?: ""
        }
        intent.getStringExtra("name")?.let { Settings.userName = it }
        intent.getStringExtra("city")?.let { Settings.homeCity = it }
        intent.getStringExtra("command")?.let { Agent.start(it) }
        val open = Agent.sessions.value.filter { it.parentId == null }.maxByOrNull { it.createdAt }
        val lastChat = Agent.sessions.value.filter { it.source == "user" }.maxByOrNull { it.createdAt }
        intent.getStringExtra("say")?.let { text -> if (lastChat != null) Agent.say(lastChat.id, text) else Agent.start(text) }
        if (intent.getBooleanExtra("cancel_all", false)) Agent.cancelAll()
        if (open != null && intent.getBooleanExtra("approve_all", false)) {
            Agent.sessions.value.forEach { s -> s.calls.values.filter { it.status == CallStatus.WAITING_APPROVAL }.forEach { Agent.decide(it.callId, "allow") } }
        }
        intent.getStringExtra("answer")?.let { ans ->
            Agent.sessions.value.forEach { s -> s.calls.values.filter { it.status == CallStatus.WAITING_USER }.forEach { Agent.answer(it.callId, ans) } }
        }
    }
}
