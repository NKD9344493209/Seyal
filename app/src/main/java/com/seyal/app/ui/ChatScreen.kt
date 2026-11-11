package com.seyal.app.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import com.seyal.app.ai.Llm
import com.seyal.app.data.SessionInfo
import com.seyal.app.util.Times
import kotlinx.coroutines.delay
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

/**
 * The main screen: one chat at a time, like Claude. A chat keeps its whole conversation, so follow-ups
 * ("call him", "and tomorrow?") work, and you can come back to any chat later from the list (☰),
 * rename it or delete it. "+" starts a new chat. Skills run on their own and show in Activity.
 */
@Composable
fun ChatScreen(openSessionId: String?, goTo: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val activity = context as? MainActivity
    val all by Agent.sessions.collectAsState()
    val history by Agent.history.collectAsState()
    val currentId by Agent.currentId.collectAsState()
    val paused by Agent.paused.collectAsState()
    val tick by Agent.tick.collectAsState()
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var showChats by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Pair<String, String>?>(null) }
    val voice = remember { Voice(context) }

    val sharedText = activity?.sharedText?.value
    val sharedImage = activity?.sharedImage?.value
    val listenTick = activity?.listenTick?.value ?: 0

    // The chat on screen. Opening one from the list loads it from disk if needed.
    val current = all.firstOrNull { it.id == currentId }
    LaunchedEffect(currentId) { if (currentId != null && current == null) Agent.open(currentId!!) }
    val chats = history.filter { it.source == "user" }.sortedByDescending { it.updatedAt }

    // Keep the newest step in view: the end of the chat, not its top.
    LaunchedEffect(current?.id, current?.updatedAt, current?.messages?.size) { if (current != null) listState.scrollToItem(1, 200_000) }
    DisposableEffect(Unit) { onDispose { voice.stop() } }

    // The AI's limits, before the user types: checked now and then, and after every change.
    var limit by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) { limit = Llm.limitNote() }
    LaunchedEffect(Unit) { while (true) { delay(15_000); limit = Llm.limitNote() } }

    val open = current?.takeIf { it.status.isOpen }
    val waitingQuestion = open != null && open.calls.values.any { it.status == CallStatus.WAITING_USER }

    val send: (String) -> Unit = { raw ->
        var msg = raw.trim()
        if (msg.isNotBlank() || sharedText != null || sharedImage != null) {
            if (!Settings.hasKey) {
                goTo(Tab.CONTROL)
            } else {
                if (sharedText != null) msg = msg.ifBlank { "Help me with this." } + "\n\nShared with you:\n" + sharedText
                if (msg.isBlank()) msg = "Look at this picture and help me with it."
                if (current != null) Agent.say(current.id, msg, sharedImage) else Agent.start(msg, sharedImage)
                text = ""
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

    if (showChats) {
        ChatsPanel(
            chats = chats, currentId = currentId,
            onOpen = { id -> Agent.open(id); showChats = false },
            onNew = { Agent.newChat(); showChats = false },
            onRename = { id, title -> renaming = id to title },
            onDelete = { id -> Agent.delete(id) },
            onClose = { showChats = false },
        )
    } else Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        // Header: chats list, this chat's name (tap to rename), new chat.
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { showChats = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Menu, "chats", tint = cs.onSurface)
            }
            Column(Modifier.weight(1f).clickable(enabled = current != null) { current?.let { renaming = it.id to it.title } }.padding(horizontal = 6.dp)) {
                Text(current?.title?.ifBlank { null } ?: "Seyal", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (current != null) Text("Tap to rename", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            if (paused) { SmallButton("Resume", danger = true) { Agent.setPaused(false) }; Spacer(Modifier.width(6.dp)) }
            else if (all.any { it.status.isOpen }) { SmallButton("Pause all", filled = false) { Agent.setPaused(true) }; Spacer(Modifier.width(6.dp)) }
            Box(Modifier.size(40.dp).clip(CircleShape).background(cs.surfaceVariant).clickable { Agent.newChat() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, "new chat", tint = cs.onSurface)
            }
        }
        StatusStrip(all.filter { it.id != currentId }, onOpen = { goTo(Tab.HISTORY) })
        limit?.let { note ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).background(Ink.amber.copy(alpha = 0.15f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(Ink.amber); Spacer(Modifier.width(8.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            item(key = "setup") { SetupCard(goTo) }
            if (current == null) item(key = "greeting") { Greeting(send) }
            else item(key = current.id) { SessionView(current, onOpenSession = { activity?.openSessionId?.value = it }) }
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
                            open != null -> "Add to this task…"
                            sharedText != null || sharedImage != null -> "What should I do with it?"
                            current != null -> "Reply in this chat…"
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
                if (open != null && text.isBlank()) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Ink.red).clickable { Agent.cancel(open.id) }, contentAlignment = Alignment.Center) {
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

    renaming?.let { (id, title) ->
        RenameDialog(title, onDismiss = { renaming = null }) { Agent.rename(id, it); renaming = null }
    }
}

/** Every chat, newest first: open one, start a new one, rename or delete. */
@Composable
private fun ChatsPanel(
    chats: List<SessionInfo>, currentId: String?,
    onOpen: (String) -> Unit, onNew: () -> Unit, onRename: (String, String) -> Unit, onDelete: (String) -> Unit, onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<SessionInfo?>(null) }
    val shown = if (query.isBlank()) chats else chats.filter { it.title.contains(query, true) || it.result.contains(query, true) }
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            SmallButton("New chat") { onNew() }
            Spacer(Modifier.width(8.dp))
            SmallButton("Close", filled = false) { onClose() }
        }
        val shape = RoundedCornerShape(22.dp)
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(cs.surface, shape).border(1.dp, cs.outline, shape).padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (query.isEmpty()) Text("Search chats", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = cs.onSurface, fontSize = 15.sp), cursorBrush = SolidColor(Ink.green), modifier = Modifier.fillMaxWidth())
        }
        if (shown.isEmpty()) Text(if (chats.isEmpty()) "No chats yet. Start one with New chat." else "No chat matches.", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
            items(shown, key = { it.id }) { c ->
                var menu by remember(c.id) { mutableStateOf(false) }
                val selected = c.id == currentId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) cs.surfaceVariant else Color.Transparent)
                        .clickable { onOpen(c.id) }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title.ifBlank { "Chat" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOf(Times.format(c.updatedAt), c.result.replace('\n', ' ')).filter { it.isNotBlank() }.joinToString(" · ")
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (c.status.isOpen) { Dot(if (c.status == SessionStatus.WAITING) Ink.amber else Ink.green); Spacer(Modifier.width(4.dp)) }
                    Box {
                        Box(Modifier.size(40.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.MoreVert, "more", tint = cs.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename(c.id, c.title) })
                            DropdownMenuItem(text = { Text("Delete", color = Ink.red) }, onClick = { menu = false; confirmDelete = c })
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this chat?") },
            text = { Text("\"${c.title}\" and everything in it will be gone.") },
            confirmButton = { TextButton(onClick = { onDelete(c.id); confirmDelete = null }) { Text("Delete", color = Ink.red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StatusStrip(others: List<Session>, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // Other chats and skills working in the background: tap to see them in Activity.
    val running = others.count { it.status == SessionStatus.RUNNING }
    val needYou = others.count { it.status == SessionStatus.WAITING }
    if (running == 0 && needYou == 0) return
    Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()).clickable { onOpen() }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running > 0) Pill("$running more working", cs.primaryContainer, cs.onPrimaryContainer)
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
    val files = remember(resumed) {
        if (full && Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_VIDEO)
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val background = remember(resumed) { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName) }
    val core = hasKey && contacts && notif && (!full || (screen && listener))
    val done = core && files && background
    if (done || (core && Settings.setupHidden)) return

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
        SetupRow("Files and media", "Find and play your movies, songs, photos, PDFs", files) {
            if (full && Build.VERSION.SDK_INT >= 30) runCatching { context.startActivity(Intent(SysSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { open(SysSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) }
            else if (Build.VERSION.SDK_INT >= 33) activity?.askFor(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES)
            else activity?.askFor(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        SetupRow("Run in the background", "So skills and \"when X messages…\" work when Seyal is closed", background) {
            runCatching { context.startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        if (core) Text("Hide this", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp).clickable { Settings.setupHidden = true; activity?.resumeTick?.let { it.value = it.value + 1 } })
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
