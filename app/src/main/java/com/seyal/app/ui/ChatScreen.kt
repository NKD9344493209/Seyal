package com.seyal.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as SysSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.seyal.app.BuildConfig
import com.seyal.app.agent.Agent
import com.seyal.app.data.CallStatus
import com.seyal.app.data.Session
import com.seyal.app.data.SessionStatus
import com.seyal.app.data.Settings
import com.seyal.app.device.ScreenDriver
import com.seyal.app.device.SeyalNotificationListener
import com.seyal.app.device.Voice
import com.seyal.app.ui.theme.Ink

private const val CONTINUE_WINDOW_MS = 30 * 60_000L

/**
 * The main screen: one running conversation with the agent. A new message continues the current
 * conversation (so "call him" after "message Ram" works); "New" starts a clean one.
 */
@Composable
fun ChatScreen(openSessionId: String?, goTo: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val activity = context as? MainActivity
    val all by Agent.sessions.collectAsState()
    val paused by Agent.paused.collectAsState()
    val thread = all.filter { it.parentId == null }.sortedBy { it.createdAt }.takeLast(15)
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val voice = remember { Voice(context) }

    val sharedText = activity?.sharedText?.value
    val sharedImage = activity?.sharedImage?.value
    val listenTick = activity?.listenTick?.value ?: 0

    val last = thread.lastOrNull()
    // Keep the newest step in view: the end of the last item, not its top.
    val lastItem = thread.size + (if (thread.isEmpty()) 1 else 0) + (if (fresh && thread.isNotEmpty()) 1 else 0)
    LaunchedEffect(thread.size, last?.updatedAt, last?.messages?.size, fresh) { if (thread.isNotEmpty()) listState.scrollToItem(lastItem, 200_000) }
    DisposableEffect(Unit) { onDispose { voice.stop() } }

    // Who gets the next message: a task that is still open; else the conversation on screen if it is recent.
    val openSession = thread.lastOrNull { it.status.isOpen && it.source == "user" }
    val recent = if (!fresh && last != null && last.source == "user" && System.currentTimeMillis() - last.updatedAt < CONTINUE_WINDOW_MS) last else null
    val target = openSession ?: recent
    val waitingQuestion = openSession != null && openSession.calls.values.any { it.status == CallStatus.WAITING_USER }

    val send: (String) -> Unit = { raw ->
        var msg = raw.trim()
        if (msg.isNotBlank() || sharedText != null || sharedImage != null) {
            if (!Settings.hasKey) {
                goTo(Tab.CONTROL)
            } else {
                if (sharedText != null) msg = msg.ifBlank { "Help me with this." } + "\n\nShared with you:\n" + sharedText
                if (msg.isBlank()) msg = "Look at this picture and help me with it."
                if (target != null) Agent.say(target.id, msg, sharedImage) else Agent.start(msg, sharedImage)
                text = ""; fresh = false
                activity?.sharedText?.value = null
                activity?.sharedImage?.value = null
            }
        }
    }

    val listen: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity?.askFor(Manifest.permission.RECORD_AUDIO)
        } else {
            listening = true
            voice.start(if (Settings.language == "ta") "ta-IN" else "en-IN", onPartial = { text = it }, onDone = { r -> listening = false; if (!r.isNullOrBlank()) { text = r; send(r) } })
        }
    }
    // Opened as the phone's assistant, from the tile or the shortcut: start listening straight away.
    LaunchedEffect(listenTick) {
        if (activity != null && listenTick > activity.listenSeen) { activity.listenSeen = listenTick; if (!listening) listen() }
    }

    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        // Header
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(Ink.green, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Bolt, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Text("Seyal", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            if (thread.isNotEmpty() && openSession == null && !fresh) { SmallButton("New", filled = false) { fresh = true }; Spacer(Modifier.width(8.dp)) }
            if (paused) SmallButton("Resume", danger = true) { Agent.setPaused(false) }
            else SmallButton("Pause all", filled = false) { Agent.setPaused(true) }
        }
        StatusStrip(all)

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            item(key = "setup") { SetupCard(goTo) }
            if (thread.isEmpty()) item(key = "greeting") { Greeting(send) }
            items(thread, key = { it.id }) { s ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (s.source != "user") Text("${if (s.source.startsWith("skill")) "Skill" else "Auto"} · ${s.title}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    SessionView(s, onOpenSession = { activity?.openSessionId?.value = it })
                }
            }
            if (fresh && thread.isNotEmpty()) item(key = "fresh") { Text("New conversation. What should I do?", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
        }

        // Composer
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (sharedText != null || sharedImage != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    Pill(if (sharedImage != null) "Picture attached" else "Shared: " + (sharedText ?: "").replace('\n', ' ').take(36), cs.secondaryContainer, cs.onSecondaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text("Remove", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.clickable { activity?.sharedText?.value = null; activity?.sharedImage?.value = null })
                }
            }
            val shape = RoundedCornerShape(26.dp)
            Row(
                Modifier.fillMaxWidth().background(cs.surface, shape).border(1.dp, cs.outline, shape).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) Text(
                        when {
                            listening -> "Listening…"
                            waitingQuestion -> "Type your answer…"
                            openSession != null -> "Add to this task…"
                            sharedText != null || sharedImage != null -> "What should I do with it?"
                            target != null -> "Reply, or ask for something new…"
                            else -> "Ask Seyal to do anything…"
                        },
                        color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
                    )
                    BasicTextField(
                        value = text, onValueChange = { text = it }, maxLines = 5,
                        textStyle = TextStyle(color = cs.onSurface, fontSize = 16.sp), cursorBrush = SolidColor(Ink.green),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (listening) Ink.red else Color.Transparent).clickable {
                        if (listening) { voice.stop(); listening = false } else listen()
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Mic, "speak", tint = if (listening) Color.White else cs.onSurfaceVariant) }
                Spacer(Modifier.width(4.dp))
                if (openSession != null && text.isBlank()) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Ink.red).clickable { Agent.cancel(openSession.id) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Pause, "stop", tint = Color.White)
                    }
                } else {
                    val ready = text.isNotBlank() || sharedText != null || sharedImage != null
                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (ready) Ink.green else cs.surfaceVariant).clickable { send(text) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.ArrowUpward, "send", tint = if (ready) Color.White else cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusStrip(all: List<Session>) {
    val cs = MaterialTheme.colorScheme
    val running = all.count { it.status == SessionStatus.RUNNING }
    val needYou = all.count { it.status == SessionStatus.WAITING }
    if (running == 0 && needYou == 0) return
    Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running > 0) Pill("$running working", cs.primaryContainer, cs.onPrimaryContainer)
        if (needYou > 0) Pill("$needYou need you", cs.secondaryContainer, cs.onSecondaryContainer)
    }
}

/** First-run checklist: shows only what is still missing, and disappears when everything needed is set. */
@Composable
private fun SetupCard(goTo: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val activity = context as? MainActivity
    // Reading these makes the card refresh when the user comes back from a permission or settings screen.
    val resumed = activity?.resumeTick?.value ?: 0
    val tick by Agent.tick.collectAsState()
    val access by ScreenDriver.access.collectAsState()
    val full = BuildConfig.FLAVOR == "full"
    val granted: (String) -> Boolean = { p -> ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED }
    val open: (String) -> Unit = { action -> runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    val hasKey = remember(resumed, tick) { Settings.hasKey }
    val contacts = remember(resumed) { granted(Manifest.permission.READ_CONTACTS) }
    val notif = remember(resumed) { Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS) }
    val screen = remember(resumed, tick, access) { ScreenDriver.available }
    val listener = remember(resumed, tick, access) { SeyalNotificationListener.connected }
    val done = hasKey && contacts && notif && (!full || (screen && listener))
    if (done) return

    Card {
        Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
        Text(if (full) "A minute now, then Seyal can do everything." else "A minute now, then you're ready.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        SetupRow("AI key", "Free Gemini key works", hasKey) { goTo(Tab.CONTROL) }
        SetupRow("Contacts", "So \"message Amma\" finds her", contacts) { activity?.askFor(Manifest.permission.READ_CONTACTS) }
        SetupRow("Notifications", "Results and questions reach you", notif) { activity?.askFor(Manifest.permission.POST_NOTIFICATIONS) }
        if (full) {
            SetupRow("Screen control", "Accessibility → Seyal → On. Lets it use any app", screen) { open(SysSettings.ACTION_ACCESSIBILITY_SETTINGS) }
            SetupRow("Notification access", "Read and reply to messages; \"when X messages…\"", listener) { open(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        }
    }
}

@Composable
private fun SetupRow(title: String, why: String, done: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(if (done) Ink.green else cs.outline, 10)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (done) cs.onSurfaceVariant else cs.onSurface)
            if (!done) Text(why, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (done) Text("Done", style = MaterialTheme.typography.labelMedium, color = Ink.green) else SmallButton("Set up", onClick = onClick)
    }
}

@Composable
private fun Greeting(onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val name = Settings.userName
    val screen = ScreenDriver.available
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (name.isBlank()) "What should I do?" else "Hi $name. What should I do?", style = MaterialTheme.typography.displaySmall)
        Text("Tamil, Tanglish or English. I work it out, use the phone, and tell you in one line.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        val ideas = if (screen) listOf(
            "Message Amma on WhatsApp that I'll be home by 8",
            "Open YouTube and play the latest Tamil songs",
            "What notifications did I miss?",
            "Find black shoes under 500 on Amazon and show me the best one",
            "Every morning at 8, give me AI news in 5 points",
            "When I plug in the charger at night, set an alarm for 6:30",
            "How long was I on YouTube today?",
            "Any important mail from college?",
        ) else listOf(
            "Send a WhatsApp to Amma that I'll be home by 8",
            "Give me today's AI news in 5 points",
            "Remind me in 20 minutes to drink water",
            "Any important mail from college?",
            "Set an alarm for 6:30 tomorrow",
            "What's the weather like today?",
            "How long was I on YouTube today?",
            "Every morning at 8, give me the news",
        )
        ideas.forEach { idea ->
            Row(Modifier.fillMaxWidth().clickable { onPick(idea) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(Ink.green, 6); Spacer(Modifier.width(10.dp)); Text(idea, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
