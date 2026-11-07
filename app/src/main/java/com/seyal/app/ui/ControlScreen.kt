package com.seyal.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.seyal.app.ai.LlmClient
import com.seyal.app.data.Level
import com.seyal.app.data.Settings
import com.seyal.app.device.SeyalAccessibilityService
import com.seyal.app.engine.Engine
import com.seyal.app.engine.Scheduler
import com.seyal.app.engine.Tools
import com.seyal.app.ui.theme.Ink
import com.seyal.app.BuildConfig
import kotlinx.coroutines.launch

@Composable
fun ControlScreen() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val paused by Engine.paused.collectAsState()

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp)) {
            Text("Control", style = MaterialTheme.typography.headlineMedium)
            Text("Powerful, but you stay in charge.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }

        // Emergency stop
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Emergency stop", style = MaterialTheme.typography.titleMedium)
                    Text(if (paused) "Everything is paused" else "Pauses every recipe and running task", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                SmallButton(if (paused) "Resume" else "Pause all", danger = !paused, filled = paused) { Engine.setPaused(!paused) }
            }
        }

        AiSection()
        LevelsSection()
        AutoSendSection()
        YouSection()
        PhoneSection()

        Card {
            Text("Your data", style = MaterialTheme.typography.titleMedium)
            Text("On this phone: commands, plans, results, recipes, your AI key (encrypted).\nTo the AI provider you chose: each command and the data a step needs.\nTo Seyal's makers: nothing. There is no Seyal server.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("AI tokens used: ${Settings.tokensUsed}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SmallButton("Delete all runs and recipes", filled = false) { Engine.wipeAll() }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AiSection() {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(Settings.provider) }
    var key by remember { mutableStateOf(Settings.apiKey) }
    var model by remember { mutableStateOf(Settings.model) }
    var base by remember { mutableStateOf(Settings.baseUrl) }
    var status by remember { mutableStateOf("") }
    Card {
        Text("AI", style = MaterialTheme.typography.titleMedium)
        Text("Your own key. Free Gemini keys: aistudio.google.com → Get API key.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Gemini", provider == "gemini") { provider = "gemini"; Settings.provider = "gemini" }
            Chip("Claude", provider == "anthropic") { provider = "anthropic"; Settings.provider = "anthropic" }
            Chip("OpenAI-style", provider == "openai") { provider = "openai"; Settings.provider = "openai" }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = key, onValueChange = { key = it; Settings.apiKey = it }, label = { Text("API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = model, onValueChange = { model = it; Settings.model = it }, label = { Text("Model (blank = ${Settings.defaultModel()})") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (provider == "openai") OutlinedTextField(value = base, onValueChange = { base = it; Settings.baseUrl = it }, label = { Text("Base URL (blank = api.openai.com; Groq, OpenRouter, Ollama…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton("Test key") {
                status = "Testing…"
                scope.launch {
                    status = runCatching { LlmClient.complete("Reply with the single word OK.", "ping", maxTokens = 10) }
                        .fold({ "Works: " + it.trim().take(40) }, { "Failed: " + (it.message ?: "") })
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (status.startsWith("Works")) Ink.green else cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun LevelsSection() {
    val cs = MaterialTheme.colorScheme
    Card {
        Text("Who decides", style = MaterialTheme.typography.titleMedium)
        Text("Auto = Seyal just does it · Ask = you approve first · Off = never. Passwords, OTPs and payments are always only you: Seyal has no tool for them.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        val labels = mapOf(
            "send_whatsapp" to "WhatsApp message", "send_email" to "Send email", "call" to "Phone call", "create_event" to "Calendar event",
            "set_alarm" to "Set alarm", "remind" to "Reminder", "open_app" to "Open an app", "open_url" to "Open a link",
            "fetch_news" to "Read news", "web_search" to "Search the web", "read_page" to "Read a web page", "movie_info" to "Movie info",
            "ai" to "AI summarise / write", "find_contact" to "Look up contacts", "notify" to "Notification", "meeting_link" to "Meeting link",
        )
        Tools.all.forEach { t ->
            var level by remember(t.name) { mutableStateOf(Settings.level(t.name, t.defaultLevel)) }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(labels[t.name] ?: t.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(Level.AUTO to "Auto", Level.ASK to "Ask", Level.OFF to "Off").forEach { (l, name) ->
                        val sel = level == l
                        Pill(
                            name,
                            bg = if (sel) (if (l == Level.ASK) cs.secondaryContainer else if (l == Level.OFF) cs.errorContainer else cs.primaryContainer) else cs.surface,
                            fg = if (sel) (if (l == Level.ASK) cs.onSecondaryContainer else if (l == Level.OFF) cs.onErrorContainer else cs.onPrimaryContainer) else cs.onSurfaceVariant,
                            modifier = Modifier.clickable { level = l; Settings.setLevel(t.name, l) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoSendSection() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var numbers by remember { mutableStateOf(Settings.autoSendContacts.toList()) }
    var input by remember { mutableStateOf("") }
    if (IS_LITE) {
        Card {
            Text("WhatsApp auto-send and mail alerts", style = MaterialTheme.typography.titleMedium)
            Text("Not in Seyal Lite. In India, Google Play Protect blocks downloaded apps that use Accessibility or Notification access. Install Seyal Full from your laptop over USB to get both. Everything else works here: WhatsApp opens with the message typed, you tap Send.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        return
    }
    Card {
        Text("WhatsApp auto-send", style = MaterialTheme.typography.titleMedium)
        Text("For these numbers Seyal taps Send itself (max 10 a day). Needs the accessibility switch on. Everyone else: you tap Send.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(if (SeyalAccessibilityService.isRunning) "Accessibility: on" else "Accessibility: off", color = if (SeyalAccessibilityService.isRunning) Ink.green else Ink.amber, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        numbers.forEach { n ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("+$n", Modifier.weight(1f))
                SmallButton("Remove", filled = false) { numbers = numbers - n; Settings.autoSendContacts = numbers.toSet() }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Phone (10 digits)") }, singleLine = true, modifier = Modifier.weight(1f))
            SmallButton("Add") {
                val d = input.filter { it.isDigit() }.let { if (it.length == 10) "91$it" else it }
                if (d.length >= 11) { numbers = (numbers + d).distinct(); Settings.autoSendContacts = numbers.toSet(); input = "" }
            }
        }
        Spacer(Modifier.height(8.dp))
        SmallButton("Open accessibility settings", filled = false) {
            ctx.startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
private fun YouSection() {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(Settings.userName) }
    var sig by remember { mutableStateOf(Settings.signature) }
    var domain by remember { mutableStateOf(Settings.collegeDomain) }
    var memory by remember { mutableStateOf(Settings.memory) }
    var quiet by remember { mutableStateOf(Settings.quiet) }
    Card {
        Text("You", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = name, onValueChange = { name = it; Settings.userName = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = sig, onValueChange = { sig = it; Settings.signature = it }, label = { Text("Email sign-off (e.g. Regards, Naveen)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = domain, onValueChange = { domain = it; Settings.collegeDomain = it }, label = { Text("College email domain (for mail alerts)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = memory, onValueChange = { memory = it; Settings.memory = it }, label = { Text("Memory: facts the AI should know") }, placeholder = { Text("Ram = Ram Kumar from project team\nMeetings default 30 min") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Silent mode", style = MaterialTheme.typography.bodyMedium)
                Text("No notification when everything went fine. Failures still alert.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Switch(checked = quiet, onCheckedChange = { quiet = it; Settings.quiet = it }, colors = SwitchDefaults.colors(checkedTrackColor = Ink.green))
        }
    }
}

@Composable
private fun PhoneSection() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val act = ctx as? MainActivity
    Card {
        Text("Phone permissions", style = MaterialTheme.typography.titleMedium)
        Text("Each one unlocks one feature. Deny any you don't need; that feature then opens the app for you to finish instead.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Contacts", filled = false) { act?.askFor(Manifest.permission.READ_CONTACTS) }
                SmallButton("Calendar", filled = false) { act?.askFor(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) }
                SmallButton("Calls", filled = false) { act?.askFor(Manifest.permission.CALL_PHONE) }
                SmallButton("Mic", filled = false) { act?.askFor(Manifest.permission.RECORD_AUDIO) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(if (Scheduler.canExact(ctx)) "Exact alarms ✓" else "Exact alarms", filled = false) {
                    if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(Intent(SysSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                if (!IS_LITE) SmallButton("Mail alerts (notification access)", filled = false) {
                    ctx.startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val pm = ctx.getSystemService(PowerManager::class.java)
                val ignoring = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
                SmallButton(if (ignoring) "Battery: unrestricted ✓" else "Allow background (battery)", filled = false) {
                    runCatching { ctx.startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
    }
}

/** Lite = the browser-installable edition without Accessibility / Notification access. */
private val IS_LITE: Boolean get() = BuildConfig.FLAVOR == "lite"
