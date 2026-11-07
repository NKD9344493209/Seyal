package com.seyal.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.seyal.app.data.RunStatus
import com.seyal.app.data.Settings
import com.seyal.app.data.StepStatus
import com.seyal.app.device.Voice
import com.seyal.app.engine.Engine
import com.seyal.app.ui.theme.Ink
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable
fun HomeScreen(openRunId: String?, goTo: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val runs by Engine.runs.collectAsState()
    val planning by Engine.planning.collectAsState()
    val paused by Engine.paused.collectAsState()
    var text by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    val voice = remember { Voice(ctx) }
    DisposableEffect(Unit) { onDispose { voice.stop() } }
    val listState = rememberLazyListState()
    val chat = runs.asReversed().takeLast(30)

    LaunchedEffect(runs.size, planning) { if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size) }

    fun send(cmd: String) {
        val c = cmd.trim()
        if (c.isBlank() || planning) return
        text = ""
        if (!Settings.hasKey) { goTo(Tab.CONTROL); return }
        scope.launch { Engine.submit(c) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Top: wordmark + pause
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).background(Ink.green, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("Seyal", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            val shape = RoundedCornerShape(18.dp)
            Row(
                Modifier.height(36.dp).background(if (paused) Ink.red else cs.surface, shape).border(1.dp, if (paused) Ink.red else cs.outline, shape)
                    .clickable { Engine.setPaused(!paused) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, contentDescription = null, tint = if (paused) Color.White else Ink.red, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (paused) "Paused · resume" else "Pause all", color = if (paused) Color.White else cs.onSurface, style = MaterialTheme.typography.labelMedium)
            }
        }

        // Status strip
        val running = runs.count { it.status == RunStatus.RUNNING }
        val needYou = runs.sumOf { r -> r.steps.count { it.status.isWaiting } } + runs.count { it.mode == "clarify" && it.createdAt > System.currentTimeMillis() - 3600_000 }
        val failed = runs.count { (it.status == RunStatus.FAILED || it.status == RunStatus.PARTIAL) && it.createdAt > System.currentTimeMillis() - 24 * 3600_000 }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("$running running", cs.primaryContainer, cs.onPrimaryContainer, running > 0) { }
            StatusChip("$needYou need you", cs.secondaryContainer, cs.onSecondaryContainer, needYou > 0) { }
            StatusChip("$failed failed", cs.errorContainer, cs.onErrorContainer, failed > 0) { goTo(Tab.ACTIVITY) }
        }

        if (!Settings.hasKey) {
            Card(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), onClick = { goTo(Tab.CONTROL) }) {
                Text("Add your AI key to start", style = MaterialTheme.typography.titleMedium)
                Text("Control → AI. A free Gemini key works. Takes 1 minute.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }

        // Chat
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (chat.isEmpty()) item { Greeting { send(it) } }
            items(chat, key = { it.id }) { run ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (run.recipeId == null) UserBubble(run.command) else Text("Recipe · ${run.title}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    RunBody(run, scope)
                }
            }
            if (planning) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ink.green)
                    Spacer(Modifier.width(8.dp))
                    Text("Planning…", color = cs.onSurfaceVariant)
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        // Input bar
        Column(Modifier.imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (chat.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions().forEach { s -> Chip(s) { send(s) } }
            }
            val shape = RoundedCornerShape(22.dp)
            Row(
                Modifier.fillMaxWidth().background(cs.surface, shape).border(1.dp, if (listening) Ink.green else cs.outline, shape).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    textStyle = TextStyle(color = cs.onSurface, fontSize = 15.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(Ink.green),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(text) }),
                    maxLines = 5,
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text(if (listening) "Listening… speak in Tamil or English" else "Ask Seyal to do anything…", color = cs.onSurfaceVariant, fontSize = 15.sp)
                        inner()
                    },
                )
                Spacer(Modifier.width(8.dp))
                RoundButton(Icons.Outlined.Mic, if (listening) Ink.green else cs.surfaceVariant, if (listening) Color.White else cs.onSurface, "Speak") {
                    if (listening) { voice.stop(); listening = false }
                    else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        (ctx as? MainActivity)?.askFor(Manifest.permission.RECORD_AUDIO)
                    } else {
                        listening = true
                        voice.start("en-IN", onPartial = { text = it }) { r -> listening = false; if (r != null) text = r }
                    }
                }
                Spacer(Modifier.width(8.dp))
                RoundButton(Icons.Outlined.ArrowUpward, Ink.green, Color.White, "Send") { send(text) }
            }
        }
    }
}

@Composable
private fun Greeting(onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val hour = LocalTime.now().hour
    val part = when { hour < 12 -> "morning"; hour < 17 -> "afternoon"; else -> "evening" }
    val name = Settings.userName
    Column(Modifier.padding(top = 36.dp)) {
        Text("Good $part${if (name.isNotBlank()) ", $name" else ""}", color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text("What should I do for you?", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions().forEach { s ->
                Row(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(Ink.green, 6); Spacer(Modifier.width(8.dp)); Text(s, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun suggestions(): List<String> = listOf(
    "Message Ram on WhatsApp that I'm coming home, then call him",
    "Make a meeting link for Ram tomorrow 4 PM and email it to him",
    "Every morning at 8, give me AI news",
    "Has the movie Ramayana released yet?",
    "Remind me in 20 minutes to drink water",
    "On Wednesday, set an alarm for Friday 7 AM",
)

@Composable
private fun StatusChip(text: String, bg: Color, fg: Color, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.height(28.dp).background(if (active) bg else cs.surface, shape).border(1.dp, if (active) bg else cs.outline, shape).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) { Dot(fg, 7); Spacer(Modifier.width(6.dp)) }
        Text(text, color = if (active) fg else cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, bg: Color, fg: Color, label: String, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).background(bg, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(20.dp))
    }
}
