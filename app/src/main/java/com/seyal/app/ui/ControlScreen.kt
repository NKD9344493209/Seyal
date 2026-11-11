package com.seyal.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.seyal.app.BuildConfig
import com.seyal.app.agent.Agent
import com.seyal.app.agent.Memory
import com.seyal.app.agent.Scheduler
import com.seyal.app.agent.Toolbox
import com.seyal.app.ai.Llm
import com.seyal.app.data.Level
import com.seyal.app.data.Settings
import com.seyal.app.device.AdSkipper
import com.seyal.app.device.ScreenDriver
import com.seyal.app.device.SeyalNotificationListener
import com.seyal.app.ui.theme.Ink
import kotlinx.coroutines.launch

private val IS_FULL get() = BuildConfig.FLAVOR == "full"

/** Opens a system settings page; some phones don't have every page, so never crash on it. */
private fun Context.open(intent: Intent) { runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

@Composable
fun ControlScreen() {
    val cs = MaterialTheme.colorScheme
    val paused by Agent.paused.collectAsState()

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp)) {
            Text("Control", style = MaterialTheme.typography.headlineMedium)
            Text("Seyal ${if (IS_FULL) "Full" else "Lite"} · powerful, but you stay in charge.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Emergency stop", style = MaterialTheme.typography.titleMedium)
                    Text(if (paused) "Everything is paused" else "Pauses every task and skill at once", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                SmallButton(if (paused) "Resume" else "Pause all", danger = !paused, filled = paused) { Agent.setPaused(!paused) }
            }
        }
        AiSection()
        ScreenSection()
        GmailSection()
        PermissionsSection()
        YouSection()
        MemorySection()
        PhoneSection()
        Card {
            Text("Your data", style = MaterialTheme.typography.titleMedium)
            Text("On this phone: conversations, steps, results, skills, memory, your keys (encrypted).\nTo the AI provider you chose: each request and what a step needs (screen text or screenshots only while it works on your task).\nTo Seyal's makers: nothing. There is no Seyal server.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("AI requests today: ${Settings.aiRequestsToday} · tokens so far: ${Settings.tokensUsed}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Llm.limitNote()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.amber) }
            Spacer(Modifier.height(8.dp))
            SmallButton("Delete all conversations and skills", filled = false, danger = true) { Agent.wipeAll() }
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
    var steps by remember { mutableStateOf(Settings.maxSteps.toString()) }
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
        if (provider == "gemini") {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("Fast", model.isBlank() || model == "gemini-flash-lite-latest") { model = ""; Settings.model = "" }
                Chip("Smart", model == "gemini-flash-latest") { model = "gemini-flash-latest"; Settings.model = model }
            }
            Text("Fast answers in about a second. Smart is better at long tasks inside apps, but slower and the free key runs out sooner.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        OutlinedTextField(value = model, onValueChange = { model = it; Settings.model = it }, label = { Text("Model (blank = ${Settings.defaultModel()})") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (provider == "openai") OutlinedTextField(value = base, onValueChange = { base = it; Settings.baseUrl = it }, label = { Text("Base URL (blank = api.openai.com; Groq, OpenRouter, Ollama…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = steps, onValueChange = { steps = it; it.toIntOrNull()?.let { n -> Settings.maxSteps = n } }, label = { Text("Max steps per task (5-80)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton("Test key") {
                status = "Testing…"
                scope.launch {
                    status = runCatching { Llm.complete("Reply with the single word OK.", "ping", maxTokens = 10) }
                        .fold({ "Works: " + it.trim().take(40) }, { "Failed: " + (it.message ?: "") })
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (status.startsWith("Works")) Ink.green else cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScreenSection() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val tick by Agent.tick.collectAsState()
    val resumed = (ctx as? MainActivity)?.resumeTick?.value ?: 0
    val access by ScreenDriver.access.collectAsState()
    val on = remember(tick, resumed, access) { ScreenDriver.available }
    val listenerOn = remember(tick, resumed, access) { SeyalNotificationListener.connected }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Screen control", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (!IS_FULL) "Lite edition: not available. The Full edition (installed over USB) lets Seyal see the screen and use any app for you."
                    else if (on) "On. Seyal can see the screen and tap, type and scroll for you while it works on your request."
                    else "Off. Turn it on: Accessibility → Installed apps → Seyal → On.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
            if (IS_FULL) SmallButton(if (on) "Settings" else "Turn on", filled = !on) { ctx.open(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        if (IS_FULL) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Notification access", style = MaterialTheme.typography.titleMedium)
                    Text(if (listenerOn) "On. Seyal can read and reply to notifications, and start skills from them." else "Off. Needed to read or reply to messages from their notifications, and for \"when X messages…\" skills.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                SmallButton(if (listenerOn) "Settings" else "Turn on", filled = !listenerOn) { ctx.open(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Make Seyal your assistant", style = MaterialTheme.typography.titleMedium)
                Text("On most phones a long-press on the power or home button then opens Seyal, listening. Choose Seyal under \"Digital assistant app\".", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            SmallButton("Open", filled = false) { ctx.open(Intent(SysSettings.ACTION_VOICE_INPUT_SETTINGS)) }
        }
        Spacer(Modifier.height(8.dp))
        Text("Also: add the \"Ask Seyal\" tile to Quick Settings, share anything to Seyal from another app, or select text and choose Ask Seyal.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("Seyal never types passwords or OTPs, never pays, and stops before any 'place order / pay' button so you can check and tap it yourself.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun GmailSection() {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(Settings.gmailAddress) }
    var pass by remember { mutableStateOf(Settings.gmailAppPassword) }
    var domain by remember { mutableStateOf(Settings.collegeDomain) }
    var status by remember { mutableStateOf("") }
    Card {
        Text("Gmail", style = MaterialTheme.typography.titleMedium)
        Text(
            "Lets Seyal read your inbox and send mail for you.\n1. Google Account → Security → 2-Step Verification on.\n2. Search \"App passwords\" → create one named Seyal.\n3. Paste the 16 letters here.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = address, onValueChange = { address = it; Settings.gmailAddress = it }, label = { Text("Gmail address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = pass, onValueChange = { pass = it; Settings.gmailAppPassword = it }, label = { Text("App password (16 letters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = domain, onValueChange = { domain = it; Settings.collegeDomain = it }, label = { Text("College / work email domain, e.g. cit.edu.in") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton("Test Gmail") {
                status = "Connecting…"
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    status = runCatching { com.seyal.app.device.MailReader.read(24, 3, true) }.fold({ "Works: ${it.total} mails in the last 24 h" }, { "Failed: ${it.message}" })
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (status.startsWith("Works")) Ink.green else cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionsSection() {
    val cs = MaterialTheme.colorScheme
    var allow by remember { mutableStateOf(Settings.allowList) }
    Card {
        Text("What Seyal may do by itself", style = MaterialTheme.typography.titleMedium)
        Text("Auto: just does it. Ask: shows a card first (Allow / Always / No). Off: never.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        for ((group, label) in Toolbox.groups) {
            val tools = Toolbox.all.filter { it.group == group && it.name !in setOf("ask_user", "wait", "screen_read", "screenshot", "screen_wait") }
            if (tools.isNotEmpty()) {
                SectionLabel(label, Modifier.padding(top = 10.dp, bottom = 4.dp))
                for (t in tools) LevelRow(t.name, t.defaultLevel)
            }
        }
        if (allow.isNotEmpty()) {
            SectionLabel("Always allowed", Modifier.padding(top = 10.dp, bottom = 4.dp))
            allow.sorted().forEach { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.replaceFirst(":", " → ").replace('_', ' '), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    SmallButton("Remove", filled = false) { Settings.allowList = Settings.allowList - a; allow = Settings.allowList }
                }
            }
        }
    }
}

@Composable
private fun LevelRow(name: String, default: Level) {
    val cs = MaterialTheme.colorScheme
    var level by remember(name) { mutableStateOf(Settings.level(name, default)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name.replace('_', ' '), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (l in listOf(Level.AUTO, Level.ASK, Level.OFF)) {
                val sel = level == l
                Pill(
                    l.name.lowercase(), if (sel) (if (l == Level.OFF) Ink.red else Ink.green) else cs.surfaceVariant, if (sel) Color.White else cs.onSurfaceVariant,
                    Modifier.clickable { level = l; Settings.setLevel(name, l) },
                )
            }
        }
    }
}

@Composable
private fun YouSection() {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(Settings.userName) }
    var city by remember { mutableStateOf(Settings.homeCity) }
    var sig by remember { mutableStateOf(Settings.signature) }
    var lang by remember { mutableStateOf(Settings.language) }
    var speak by remember { mutableStateOf(Settings.speak) }
    var quiet by remember { mutableStateOf(Settings.quiet) }
    Card {
        Text("You", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = name, onValueChange = { name = it; Settings.userName = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = city, onValueChange = { city = it; Settings.homeCity = it }, label = { Text("Home city (weather, bookings)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = sig, onValueChange = { sig = it; Settings.signature = it }, label = { Text("Email signature") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SectionLabel("Reply language")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("auto" to "Same as me", "en" to "English", "ta" to "தமிழ்", "tanglish" to "Tanglish").forEach { (k, l) -> Chip(l, selected = lang == k) { lang = k; Settings.language = k } }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Speak answers", style = MaterialTheme.typography.bodyMedium); Text("Read the final answer aloud", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            Switch(checked = speak, onCheckedChange = { speak = it; Settings.speak = it })
        }
        if (IS_FULL) Row(verticalAlignment = Alignment.CenterVertically) {
            var skip by remember { mutableStateOf(Settings.skipAds) }
            Column(Modifier.weight(1f)) { Text("Skip ads by itself", style = MaterialTheme.typography.bodyMedium); Text("Presses \"Skip ad\" and ad close buttons in YouTube, MX Player, VLC and other apps (${AdSkipper.skipped} skipped since start)", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            Switch(checked = skip, onCheckedChange = { skip = it; Settings.skipAds = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Quiet", style = MaterialTheme.typography.bodyMedium); Text("Notify only when something fails or needs you", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            Switch(checked = quiet, onCheckedChange = { quiet = it; Settings.quiet = it })
        }
    }
}

@Composable
private fun MemorySection() {
    val cs = MaterialTheme.colorScheme
    val stored by Memory.flow.collectAsState()
    var text by remember(stored) { mutableStateOf(stored ?: Memory.text()) }
    Card {
        Text("Memory", style = MaterialTheme.typography.titleMedium)
        Text("What Seyal knows about you, one fact per line. It reads this every time. Edit freely.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("- dad = Appa Jio\n- college = cit.edu.in\n- favourite theatre = Broadway Cinemas") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Spacer(Modifier.height(6.dp))
        SmallButton("Save memory") { Memory.set(text) }
    }
}

@Composable
private fun PhoneSection() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val act = ctx as? MainActivity
    Card {
        Text("Phone permissions", style = MaterialTheme.typography.titleMedium)
        Text("Each one unlocks something. Tap to grant.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Contacts") { act?.askFor(Manifest.permission.READ_CONTACTS) }
            Chip("Calls") { act?.askFor(Manifest.permission.CALL_PHONE) }
            if (IS_FULL) Chip("SMS") { act?.askFor(Manifest.permission.SEND_SMS) }
            Chip("Calendar") { act?.askFor(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) }
            Chip("Location") { act?.askFor(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION) }
            Chip("Microphone") { act?.askFor(Manifest.permission.RECORD_AUDIO) }
            if (Build.VERSION.SDK_INT >= 33) Chip("Notifications") { act?.askFor(Manifest.permission.POST_NOTIFICATIONS) }
        }
        Spacer(Modifier.height(10.dp))
        SectionLabel("Special access")
        Spacer(Modifier.height(4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Screen time") { ctx.open(Intent(SysSettings.ACTION_USAGE_ACCESS_SETTINGS)) }
            Chip("Do not disturb") { ctx.open(Intent(SysSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            Chip("System settings") { ctx.open(Intent(SysSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
            if (Build.VERSION.SDK_INT >= 31 && !Scheduler.canExact(ctx)) Chip("Exact alarms") { ctx.open(Intent(SysSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) }
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) Chip("Battery: no limits") { ctx.open(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Text(
            listOf(
                "Exact alarms: ${if (Scheduler.canExact(ctx)) "yes" else "no"}",
                "DND access: ${if (nm.isNotificationPolicyAccessGranted) "yes" else "no"}",
                "System settings: ${if (SysSettings.System.canWrite(ctx)) "yes" else "no"}",
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
        )
    }
}
