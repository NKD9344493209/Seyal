package com.seyal.app.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.seyal.app.agent.Agent
import com.seyal.app.agent.Prompts
import com.seyal.app.agent.Skills
import com.seyal.app.agent.Toolbox
import com.seyal.app.data.CallState
import com.seyal.app.data.CallStatus
import com.seyal.app.data.Msg
import com.seyal.app.data.Part
import com.seyal.app.data.Session
import com.seyal.app.data.SessionStatus
import com.seyal.app.ui.theme.Ink

/**
 * One conversation as a transcript: what the user said, every step the agent took (live), questions
 * and approvals inline, and the answers. Used by the Chat and Activity tabs.
 */
@Composable
fun SessionView(s: Session, onOpenSession: (String) -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val results = HashMap<String, Part.Result>()
    for (m in s.messages) if (m.role == "tool") for (r in m.results) results[r.callId] = r

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var stepNo = 0
        for (m in s.messages) {
            if (m.role == "user") {
                UserBubble(m)
            } else if (m.role == "assistant") {
                if (m.text.isNotBlank()) {
                    // Text next to tool calls is the agent thinking aloud; text on its own is an answer.
                    if (m.calls.isEmpty()) Rich(m.text) else Rich(m.text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                if (m.calls.isNotEmpty()) {
                    val first = stepNo + 1
                    stepNo += m.calls.size
                    Card {
                        m.calls.forEachIndexed { i, call -> CallRow(first + i, call, s.calls[call.id], results[call.id], s, onOpenSession) }
                    }
                }
            }
        }

        if (s.status == SessionStatus.RUNNING) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Ink.green)
                Spacer(Modifier.width(8.dp))
                Text(if (s.steps == 0) "Thinking…" else "Working · step ${s.steps}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                SmallButton("Stop", filled = false) { Agent.cancel(s.id) }
            }
        } else if (s.status == SessionStatus.WAITING) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(Ink.amber); Spacer(Modifier.width(8.dp))
                Text("Waiting for you", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (s.calls.values.count { it.status == CallStatus.WAITING_APPROVAL } > 1) { SmallButton("Allow all") { Agent.decideAll(s.id, true) }; Spacer(Modifier.width(6.dp)) }
                SmallButton("Stop", filled = false) { Agent.cancel(s.id) }
            }
        } else {
            if (s.status == SessionStatus.FAILED && !s.result.isNullOrBlank()) Text(s.result ?: "", style = MaterialTheme.typography.bodyLarge, color = Ink.red)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SessionPill(s.status)
                Text(statsLine(s), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                // Worth saving only when Seyal actually did something (not for questions it just answered).
                val acted = s.messages.any { m -> m.calls.any { c -> s.calls[c.id]?.status == CallStatus.DONE && Toolbox.get(c.name)?.let { t -> t.sideEffect || t.group == "screen" } == true } }
                if (s.status == SessionStatus.DONE && acted && s.source == "user") {
                    var saved by remember(s.id) { mutableStateOf(false) }
                    if (saved) Text("Saved to Skills", style = MaterialTheme.typography.labelMedium, color = Ink.green)
                    else SmallButton("Save as skill", filled = false) { saved = Skills.fromSession(s) != null }
                }
            }
        }
    }
}

private fun statsLine(s: Session): String {
    val secs = ((s.updatedAt - s.createdAt) / 1000).coerceAtLeast(0)
    val t = if (secs < 60) "${secs}s" else if (secs < 3600) "${secs / 60}m ${secs % 60}s" else "${secs / 3600}h"
    val parts = mutableListOf<String>()
    if (s.steps > 0) parts += if (s.steps == 1) "1 step" else "${s.steps} steps"
    parts += t
    return parts.joinToString(" · ")
}

@Composable
fun UserBubble(m: Msg) {
    val image = m.parts.filterIsInstance<Part.Image>().firstOrNull()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier.widthIn(max = 300.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (m.text.isNotBlank()) Text(m.text.removePrefix(Agent.MIDTASK).take(1200), style = MaterialTheme.typography.bodyLarge)
            if (image != null) Thumb(image, 160)
        }
    }
}

@Composable
private fun CallRow(n: Int, call: Part.Call, state: CallState?, result: Part.Result?, s: Session, onOpenSession: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status = state?.status ?: CallStatus.PENDING
    var open by remember(call.id) { mutableStateOf(false) }
    val lane = when (status) {
        CallStatus.FAILED -> Ink.red
        CallStatus.WAITING_APPROVAL, CallStatus.WAITING_USER -> Ink.amber
        CallStatus.DONE -> Ink.green
        else -> cs.outline
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.Top) {
            Box(Modifier.width(3.dp).height(20.dp).background(lane, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$n", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(Prompts.friendly(call), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (status == CallStatus.RUNNING) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Ink.green); Spacer(Modifier.width(6.dp)) }
                    if (status != CallStatus.DONE) StatusPill(status)
                }
                val sub = if (status == CallStatus.DONE) state?.line else if (status == CallStatus.FAILED || status == CallStatus.DENIED) state?.error else null
                if (!sub.isNullOrBlank()) Text(sub.lines().first().take(140), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 18.dp, top = 2.dp))
            }
        }

        // Approval card
        if (status == CallStatus.WAITING_APPROVAL && state != null) {
            Column(Modifier.padding(start = 13.dp, top = 8.dp).fillMaxWidth().background(cs.secondaryContainer, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text(state.preview ?: Prompts.friendly(call), style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Allow") { Agent.decide(call.id, "allow") }
                    SmallButton("Always", filled = false) { Agent.decide(call.id, "allow_always") }
                    SmallButton("No", filled = false) { Agent.decide(call.id, "deny") }
                }
            }
        }
        // Question card
        if (status == CallStatus.WAITING_USER && state != null) {
            Column(Modifier.padding(start = 13.dp, top = 8.dp).fillMaxWidth().background(cs.secondaryContainer, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text(state.preview ?: "?", style = MaterialTheme.typography.bodyLarge, color = cs.onSecondaryContainer)
                if (state.options.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.options.forEach { opt -> Chip(opt) { Agent.answer(call.id, opt) } }
                    }
                }
                Text("or type your answer below", style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer, modifier = Modifier.padding(top = 6.dp))
            }
        }
        // Sub-tasks
        if (state != null && state.children.isNotEmpty()) {
            val children = Agent.sessions.value
            Column(Modifier.padding(start = 13.dp, top = 6.dp)) {
                state.children.forEach { cid ->
                    val child = children.firstOrNull { it.id == cid }
                    Row(Modifier.fillMaxWidth().clickable { onOpenSession(cid) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("↳ ", color = cs.onSurfaceVariant)
                        Text(child?.title ?: cid, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (child != null) SessionPill(child.status)
                    }
                    val childResult = child?.result
                    if (!childResult.isNullOrBlank()) Text(childResult, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
        // Details
        val image = result?.image
        if (open) {
            Column(Modifier.padding(start = 13.dp, top = 6.dp).fillMaxWidth().background(cs.surfaceVariant, RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text(call.name + " " + call.args.toString().take(600), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (result != null) {
                    Spacer(Modifier.height(6.dp))
                    val shown = result.output.optJSONObject("screen")?.let { "screen: " + it.optString("app") + "\n" + it.optString("elements") } ?: result.output.toString()
                    Text(shown.take(1500), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = if (result.isError) Ink.red else cs.onSurface)
                    if (image != null) { Spacer(Modifier.height(6.dp)); Thumb(image, 260) }
                }
                val ms = (state?.endedAt ?: 0L) - (state?.startedAt ?: 0L)
                if (ms > 0) Text("${ms / 100 / 10.0}s", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        } else if (image != null && status == CallStatus.DONE) {
            Box(Modifier.padding(start = 13.dp, top = 6.dp)) { Thumb(image, 140) }
        }
    }
}

@Composable
private fun Thumb(img: Part.Image, heightDp: Int) {
    val bmp = remember(img.b64.length) { runCatching { val bytes = Base64.decode(img.b64, Base64.DEFAULT); BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() }
    if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "picture", modifier = Modifier.height(heightDp.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
}
