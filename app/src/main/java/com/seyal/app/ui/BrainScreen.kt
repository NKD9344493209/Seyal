package com.seyal.app.ui

import android.content.Intent
import android.provider.Settings as SysSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.seyal.app.agent.Brain
import com.seyal.app.agent.BrainLoop
import com.seyal.app.agent.BrainStore
import com.seyal.app.data.Settings
import com.seyal.app.device.SeyalNotificationListener
import com.seyal.app.ui.theme.Ink
import com.seyal.app.util.Times

/** How long ago, in a few letters. */
internal fun ago(at: Long, now: Long): String {
    val m = ((now - at) / 60_000L).coerceAtLeast(0)
    return when { m < 1 -> "now"; m < 60 -> "$m min ago"; m < 24 * 60 -> "${m / 60} h ago"; else -> "${m / (24 * 60)} d ago" }
}

/** What became of a thought, in a word or two. */
internal fun outcome(t: Brain.Thought): String = when {
    t.level == "did" && t.state == "done" -> "Done by Seyal"
    t.state == "done" -> "You said yes"
    t.state == "no" -> "You said no"
    t.state == "undone" -> "Undone"
    t.state == "failed" -> "Did not work"
    t.state == "told" -> "Told you"
    else -> "Waiting for you"
}

/** The Brain: its switch, what is waiting for an answer, how much it may do alone, and everything it noticed. (What Seyal reads about the world is the Dirghadarshi tab.) */
@Composable
fun BrainScreen() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val version by BrainStore.version.collectAsState()
    var on by remember { mutableStateOf(Settings.brainOn) }
    val resumed = (ctx as? MainActivity)?.resumeTick?.value ?: 0
    val listener = remember(resumed, on) { SeyalNotificationListener.connected }
    val now = System.currentTimeMillis()
    val all = remember(version) { BrainStore.all() }
    val waiting = all.filter { it.state == "new" && it.level == "suggest" && now - it.at < 24 * 3600_000L }
    val earlier = all.filter { it !in waiting }.take(40)
    val trust = BrainStore.trust
    var sure by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp)) {
            Text("Brain", style = MaterialTheme.typography.headlineMedium)
            Text("Seyal thinks about the messages that arrive and speaks first. You decide how much it may do.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Card {
            SwitchRow(if (on) "The Brain is on" else "Turn the Brain on", "When a message needs you, Seyal offers a reminder, a calendar entry, a ready reply or a call. One tap to accept.", on) { Settings.brainOn = it; on = it }
            Spacer(Modifier.height(6.dp))
            Text(
                "It reads messages from people as they arrive (not group chats, codes, bank messages or adverts). One that seems to need you is sent to your AI, with your own key, to decide what to offer. " +
                    "It never pays, books, deletes or messages a group, and it sends a reply or makes a call only when you tap.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            if (!IS_FULL) Text("This needs the Full edition of Seyal (the Lite one cannot read notifications).", style = MaterialTheme.typography.bodySmall, color = Ink.red, modifier = Modifier.padding(top = 6.dp))
            else if (on && !listener) {
                Spacer(Modifier.height(8.dp))
                Text("Notification access is off, so no message reaches the Brain.", style = MaterialTheme.typography.bodySmall, color = Ink.red)
                Spacer(Modifier.height(4.dp))
                SmallButton("Turn on Notification access") { runCatching { ctx.startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            if (on && !Settings.hasKey) Text("Add your AI key first (Control → AI).", style = MaterialTheme.typography.bodySmall, color = Ink.red, modifier = Modifier.padding(top = 6.dp))
            if (on && !com.seyal.app.device.Notify.allowed(ctx)) Text("Seyal may not show notifications, so offers will appear only here. Allow notifications for Seyal in the phone's settings.", style = MaterialTheme.typography.bodySmall, color = Ink.red, modifier = Modifier.padding(top = 6.dp))
        }

        // Where things stand: the last message heard and what became of it, and a way to see it work right now.
        val heard = remember(version) { BrainStore.heardList() }
        Card {
            Text("Is it working?", style = MaterialTheme.typography.titleMedium)
            val last = heard.firstOrNull()
            Text(
                if (last == null) "No message has reached the Brain yet. A message reaches it only as a notification: it must arrive while the phone is locked or WhatsApp is not open on that chat. A message you send yourself is never heard."
                else "Last heard ${ago(last.at, now)}: ${last.who} (${last.app}) → ${last.fate}.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallButton("Try it now", filled = on && Settings.hasKey) { if (on && Settings.hasKey) BrainLoop.tryNow(ctx) }
                Spacer(Modifier.width(10.dp))
                Text("Runs a made-up message (\"Ram Kumar: project review tomorrow 10 AM\") through the Brain. The offer appears in about 10 seconds.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            if (heard.isNotEmpty()) {
                var showHeard by remember { mutableStateOf(false) }
                Spacer(Modifier.height(6.dp))
                Text(if (showHeard) "Hide what it heard" else "Show the last ${heard.size} messages it heard", style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.clickable { showHeard = !showHeard }.padding(vertical = 4.dp))
                if (showHeard) heard.take(25).forEach { h ->
                    Column(Modifier.padding(vertical = 3.dp)) {
                        Text("${h.who} · ${h.app} · ${ago(h.at, now)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Text("\"${h.text}\"", style = MaterialTheme.typography.bodySmall)
                        Text("→ ${h.fate}", style = MaterialTheme.typography.bodySmall, color = if (h.fate.startsWith("AI failed") || h.fate.startsWith("failed")) Ink.red else cs.primary)
                    }
                }
            }
        }

        if (waiting.isNotEmpty()) {
            SectionLabel("Waiting for you")
            waiting.forEach { t -> ThoughtCard(t, now, open = true) }
        }

        Card {
            Text("How much I may do by myself", style = MaterialTheme.typography.titleMedium)
            Text("Three yeses in a row and I ask whether to do that kind alone. Three noes in a row and I stop offering it for two weeks.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Brain.KINDS.forEach { kind ->
                val auto = remember(version) { trust.auto(kind) }
                val muted = remember(version) { trust.muted(kind, now) }
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Brain.kindName(kind).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("yes ${trust.yes(kind)} · no ${trust.no(kind)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    when {
                        muted -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Not offered until ${Times.format(trust.mutedUntil(kind))}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text("Offer again", style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.clickable { trust.unmute(kind); BrainStore.save() }.padding(6.dp))
                        }
                        kind in Brain.CAN_AUTO -> Row(Modifier.padding(top = 2.dp)) {
                            Chip("Ask me first", selected = !auto) { trust.setAuto(kind, false, System.currentTimeMillis()); BrainStore.save() }
                            Spacer(Modifier.width(8.dp))
                            Chip("Do it and tell me", selected = auto) { trust.setAuto(kind, true, System.currentTimeMillis()); BrainStore.save() }
                        }
                        else -> Text(if (kind == "calendar") "Asks first." else "Always asks first: nothing is sent and no call is made without your tap.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            val people = remember(version) { trust.mutedNow(now).filter { it.startsWith("from:") } }
            people.forEach { key ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Leaving ${key.removePrefix("from:")}'s messages alone until ${Times.format(trust.mutedUntil(key))}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text("Undo", style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.clickable { trust.unmute(key); BrainStore.save() }.padding(6.dp))
                }
            }
        }

        SectionLabel("What I noticed")
        if (all.isEmpty()) Text(if (on) "Nothing yet. When a message that needs you arrives, it shows here and as a notification." else "Turn the Brain on to start.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        earlier.forEach { t -> ThoughtCard(t, now, open = false) }

        if (all.isNotEmpty()) Row(Modifier.padding(horizontal = 4.dp)) {
            if (sure) SmallButton("Yes, forget it all", filled = false, danger = true) { BrainStore.forget(); sure = false }
            else SmallButton("Forget what the Brain noted and learnt", filled = false) { sure = true }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** One thing the Brain noticed. [open]: it still waits for an answer, so its buttons are shown. */
@Composable
private fun ThoughtCard(t: Brain.Thought, now: Long, open: Boolean) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${t.who} · ${t.app}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(if (open) ago(t.at, now) else "${outcome(t)} · ${ago(t.at, now)}", style = MaterialTheme.typography.labelSmall, color = if (t.state == "failed") Ink.red else cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(2.dp))
        Text(t.say, style = MaterialTheme.typography.bodyMedium)
        if (t.result.isNotBlank()) Text(t.result, style = MaterialTheme.typography.bodySmall, color = if (t.state == "failed") Ink.red else cs.primary, modifier = Modifier.padding(top = 2.dp))
        Text("Message: \"${t.heard.take(140)}\"" + (if (t.why.isNotBlank()) "  ·  Why: ${t.why}" else ""), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        val buttons = t.buttons()
        if (buttons.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row {
                buttons.forEach { (label, code) ->
                    SmallButton(label, filled = code != "no" && code != "undo" && code != "later") { BrainLoop.answer(ctx, t.id, code) }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}
