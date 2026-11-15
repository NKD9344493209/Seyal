package com.seyal.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.seyal.app.agent.Agent
import com.seyal.app.agent.Coach
import com.seyal.app.agent.CoachPlan
import com.seyal.app.agent.Khata
import com.seyal.app.agent.Ledger
import com.seyal.app.agent.Parcels
import com.seyal.app.agent.Usual
import com.seyal.app.data.SessionStatus
import com.seyal.app.data.Settings
import com.seyal.app.device.KeepAlive
import com.seyal.app.device.Report
import com.seyal.app.device.Voice
import com.seyal.app.ui.theme.Ink

/** A switch with a title and one line saying what it does. */
@Composable
internal fun SwitchRow(title: String, about: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** One plan in the list: where it stands, what today's session is, and the buttons to answer it. */
@Composable
internal fun PlanCard(p: CoachPlan, onChange: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val now = System.currentTimeMillis()
    var said by remember(p.id) { mutableStateOf("") }
    var confirmRemove by remember(p.id) { mutableStateOf(false) }
    Card {
        Text(p.title, style = MaterialTheme.typography.titleMedium)
        Text(Coach.status(p, now), style = MaterialTheme.typography.bodySmall, color = cs.primary)
        if (!p.paused) {
            Spacer(Modifier.height(4.dp))
            Text("Next: ${p.next().title} (${p.todaysMinutes()} min)", style = MaterialTheme.typography.bodyMedium)
            p.todaysItems().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        }
        if (said.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(said, style = MaterialTheme.typography.bodySmall, color = cs.primary) }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.paused) SmallButton("Start again") { said = Coach.mark(p, "resume", now); onChange() }
            else {
                SmallButton("Done") { said = Coach.mark(p, "done", now); onChange() }
                SmallButton("Skip today", filled = false) { said = Coach.mark(p, "skip", now); onChange() }
                SmallButton("Pause", filled = false) { said = Coach.mark(p, "pause", now); onChange() }
            }
            if (confirmRemove) SmallButton("Yes, delete", filled = false, danger = true) { Coach.remove(p.id); onChange() }
            else SmallButton("Delete", filled = false, danger = true) { confirmRemove = true }
        }
    }
}

/** Orders on the way, money seen today, and the credit book: each shown only when there is something in it. */
@Composable
internal fun TodayCards(version: Int, onChange: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val now = System.currentTimeMillis()
    val parcels = remember(version) { Parcels.open(now) }
    val anyMoney = remember(version) { Ledger.between(Ledger.range("month", now).first, now + 1).isNotEmpty() }
    val owing = remember(version) { Khata.owing() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (parcels.isNotEmpty()) Card {
            Text("Orders on the way", style = MaterialTheme.typography.titleMedium)
            parcels.forEach { Text("• ${it.line()}", style = MaterialTheme.typography.bodySmall, color = if (it.status == "out") cs.primary else cs.onSurfaceVariant) }
            Text("Read from the shops' own notifications.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        if (anyMoney) Card {
            Text("Money", style = MaterialTheme.typography.titleMedium)
            Text(Ledger.summary("today", "both", now), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(Ledger.summary("month", "both", now), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Text("Added up from payment notifications (Google Pay, PhonePe, Paytm, bank SMS). It stays on this phone.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(6.dp))
            var sure by remember { mutableStateOf(false) }
            if (sure) SmallButton("Yes, clear the totals", filled = false, danger = true) { Ledger.clear(); sure = false; onChange() }
            else SmallButton("Clear", filled = false) { sure = true }
        }
        if (owing.isNotEmpty()) Card {
            Text("Credit book", style = MaterialTheme.typography.titleMedium)
            Text(Khata.summary(now), style = MaterialTheme.typography.bodySmall)
            Text("Say \"Kumar owes me 350\" or \"Kumar paid 200\" in chat to update it.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Control: the helpers that work by themselves, each with its own switch. */
@Composable
internal fun HelpersSection(onSimple: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var scam by remember { mutableStateOf(Settings.scamWarnings) }
    var history by remember { mutableStateOf(Settings.keepHistory) }
    var habits by remember { mutableStateOf(Settings.suggestHabits) }
    var simple by remember { mutableStateOf(Settings.simpleMode) }
    Card {
        Text("Helpers", style = MaterialTheme.typography.titleMedium)
        Text("These work on the phone itself. They never reply to anyone and send nothing anywhere.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (IS_FULL) {
            SwitchRow("Scam warnings", "Warns you when a message looks like a scam: fake KYC, electricity cut, prize, \"share the OTP\", fake police", scam) { scam = it; Settings.scamWarnings = it }
            SwitchRow("Remember messages for 30 days", "So you can ask \"find the address Ram sent me\". Codes and passwords are never kept", history) { history = it; Settings.keepHistory = it; if (!it) com.seyal.app.agent.Past.clear() }
        }
        SwitchRow("Offer to automate my habits", "When you do the same thing at the same time most days, Seyal asks once if it should do it for you", habits) { habits = it; Settings.suggestHabits = it }
        SwitchRow("Simple mode", "One big microphone and big buttons, answers read aloud. Made for parents", simple) { simple = it; Settings.simpleMode = it; if (it) onSimple() }
    }
}

/** Control: text someone when this phone has not been touched by a set time. For a parent living alone. */
@Composable
internal fun FamilySection() {
    val cs = MaterialTheme.colorScheme
    var on by remember { mutableStateOf(Settings.guardianOn) }
    var number by remember { mutableStateOf(Settings.guardianNumber) }
    var time by remember { mutableStateOf(Settings.guardianTime) }
    val goodNumber = number.filter { it.isDigit() }.length >= 10
    val goodTime = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$").matches(time.trim())
    val appCtx = LocalContext.current.applicationContext
    if (!IS_FULL) return
    Card {
        Text("Family check-in", style = MaterialTheme.typography.titleMedium)
        Text("Set this on a parent's phone. If the phone has not been unlocked by the time below, Seyal sends one text to the number below, once a day. Nothing is sent on a normal day.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        OutlinedTextField(value = number, onValueChange = { number = it.take(16); Settings.guardianNumber = number.trim(); com.seyal.app.agent.Scheduler.syncBackground(appCtx) }, label = { Text("Who to text (mobile number)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = time, onValueChange = { time = it.take(5); if (Regex("^([01]?\\d|2[0-3]):[0-5]\\d$").matches(time.trim())) { Settings.guardianTime = time.trim(); com.seyal.app.agent.Scheduler.syncBackground(appCtx) } }, label = { Text("Check at (24-hour time, like 11:00)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (number.isNotBlank() && !goodNumber) Text("Type the full mobile number.", style = MaterialTheme.typography.bodySmall, color = Ink.red)
        if (!goodTime) Text("Type the time like 11:00 or 09:30.", style = MaterialTheme.typography.bodySmall, color = Ink.red)
        SwitchRow("Turn on", if (on) "On: checks every day at ${Settings.guardianTime}" else "Off", on && goodNumber) {
            if (it && !goodNumber) return@SwitchRow
            on = it; Settings.guardianOn = it; com.seyal.app.agent.Scheduler.syncBackground(appCtx)
        }
        val act = LocalContext.current as? MainActivity
        if (on) Text("Needs the SMS permission.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.clickable { act?.askFor(Manifest.permission.SEND_SMS) })
    }
}

/** Control: what Seyal has picked up about the user's usual choices, each removable. */
@Composable
internal fun KnowsSection() {
    val cs = MaterialTheme.colorScheme
    var version by remember { mutableStateOf(0) }
    val lines = remember(version) { Usual.lines() }
    Card {
        Text("What Seyal has learnt about your choices", style = MaterialTheme.typography.titleMedium)
        Text("From the orders and bookings you finished, and from the times you told it which person you meant. Used when you say \"same as last time\". Kept on this phone.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (lines.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        lines.forEach { (key, text) ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text("Remove", style = MaterialTheme.typography.labelMedium, color = Ink.red, modifier = Modifier.clickable { Usual.forget(key); version++ }.padding(6.dp))
            }
        }
    }
}

/** Control: the steps that stop this brand of phone from closing Seyal, with buttons that open the right pages. */
@Composable
internal fun KeepAliveSection() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val act = ctx as? MainActivity
    @Suppress("UNUSED_VARIABLE") val resumed = act?.resumeTick?.value
    val family = remember { KeepAlive.family() }
    val free = KeepAlive.unrestricted(ctx)
    val killed = Settings.killedCount
    if (!KeepAlive.strict(family) && free && killed == 0) return
    Card {
        Text("Keep Seyal running", style = MaterialTheme.typography.titleMedium)
        Text(
            if (killed > 0) "Your phone closed Seyal in the middle of a task $killed time${if (killed == 1) "" else "s"}. These steps stop that."
            else "${KeepAlive.brandName(family)} phones close apps to save battery. Then reminders come late and tasks stop half way. Do these once:",
            style = MaterialTheme.typography.bodySmall, color = if (killed > 0) Ink.red else cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        KeepAlive.steps(family).forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (family != "other" && family != "samsung") SmallButton("Open the setting") { KeepAlive.openAutostart(ctx) }
            SmallButton(if (free) "Battery: done ✓" else "Battery", filled = !free) { KeepAlive.openBattery(ctx) }
            if (killed > 0) SmallButton("I did these", filled = false) { Settings.killedCount = 0; act?.resumeTick?.let { it.value = it.value + 1 } }
        }
    }
}

/** Control: one tap to send everything needed to look into a problem. */
@Composable
internal fun ReportSection() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    Card {
        Text("Something not working?", style = MaterialTheme.typography.titleMedium)
        Text("One tap collects what is needed to find the cause (your phone model, what Seyal is allowed to do, your last tasks and their steps) and lets you send it on WhatsApp or mail. No passwords, keys or contacts are included.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        SmallButton("Report a problem") {
            val last = Agent.history.value.firstOrNull { !it.isChild }?.id?.let { id -> Agent.sessions.value.firstOrNull { it.id == id } }
            Report.share(ctx, last)
        }
    }
}

/** A big button for simple mode. */
@Composable
private fun BigButton(label: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable { onClick() }.padding(vertical = 18.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(sub, fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/**
 * Simple mode: one big microphone, four big buttons, and the answer in large letters (also read aloud).
 * Nothing else on the screen. Made for someone who finds the normal app too much.
 */
@Composable
fun SimpleScreen(onLeave: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val act = ctx as? MainActivity
    val all by Agent.sessions.collectAsState()
    val currentId by Agent.currentId.collectAsState()
    @Suppress("UNUSED_VARIABLE") val tick by Agent.tick.collectAsState()
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    var mine by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    val voice = remember { Voice(ctx) }
    val tamil = Settings.language == "ta"
    val session = all.firstOrNull { it.id == (mine ?: currentId) }
    val working = session != null && mine != null && (session.status == SessionStatus.RUNNING || session.status == SessionStatus.WAITING)

    fun ask(text: String) { heard = text; mine = Agent.start(text).id }
    val listen: () -> Unit = {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) act?.askFor(Manifest.permission.RECORD_AUDIO)
        else {
            listening = true; heard = ""
            voice.start(if (tamil) "ta-IN" else "en-IN", onPartial = { heard = it }, onDone = { r -> listening = false; if (!r.isNullOrBlank()) ask(r) })
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Seyal", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (leaving) {
                SmallButton(if (tamil) "ஆம்" else "Yes, normal mode", filled = false) { Settings.simpleMode = false; onLeave() }
                Spacer(Modifier.width(6.dp))
                SmallButton(if (tamil) "வேண்டாம்" else "Stay", filled = true) { leaving = false }
            } else Text(if (tamil) "சாதாரண முறை" else "Normal mode", fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.clickable { leaving = true }.padding(8.dp))
        }
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(170.dp).clip(CircleShape).background(if (listening) Ink.red else cs.primary).clickable { if (listening) { voice.stop(); listening = false } else listen() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Mic, if (tamil) "பேசுங்கள்" else "Speak", tint = Color.White, modifier = Modifier.size(84.dp)) }
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                listening -> if (tamil) "கேட்கிறேன்… சொல்லுங்கள்" else "Listening… speak now"
                working -> if (tamil) "செய்கிறேன்…" else "Working on it…"
                else -> if (tamil) "தொட்டு பேசுங்கள்" else "Tap and speak"
            },
            fontSize = 22.sp, fontWeight = FontWeight.Medium,
        )
        if (heard.isNotBlank()) Text("\"$heard\"", fontSize = 18.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        if (working) { Spacer(Modifier.height(8.dp)); SmallButton(if (tamil) "நிறுத்து" else "Stop", filled = false, danger = true) { session?.let { Agent.cancel(it.id) } } }
        // The answer, in large letters.
        val answer = session?.takeIf { mine != null && !working }?.result
        if (!answer.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceVariant).padding(16.dp)) {
                Text(answer.take(900), fontSize = 20.sp, lineHeight = 28.sp, color = if (session?.status == SessionStatus.FAILED) Ink.red else cs.onSurface)
            }
        }
        // Seyal is asking something (a permission, a choice): the full screen shows it properly.
        if (session?.status == SessionStatus.WAITING) {
            Spacer(Modifier.height(10.dp))
            Text(if (tamil) "ஒரு கேள்வி உள்ளது. பதில் சொல்ல கீழே தொடவும்." else "Seyal needs an answer from you.", fontSize = 18.sp, color = Ink.amber)
            SmallButton(if (tamil) "பதில் சொல்" else "Answer") { Settings.simpleMode = false; onLeave() }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(if (tamil) "மெசேஜ் படி" else "Read messages", if (tamil) "புதிய மெசேஜ்கள்" else "New messages, aloud", Modifier.weight(1f)) { ask(if (tamil) "மெசேஜ் படி" else "Read my messages") }
            BigButton(if (tamil) "இன்று" else "Today", if (tamil) "இன்றைய சுருக்கம்" else "My day in short", Modifier.weight(1f)) { ask("Give me my summary for today") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(if (tamil) "நேரம்" else "Time", if (tamil) "இப்போது மணி" else "What time is it", Modifier.weight(1f)) { ask(if (tamil) "நேரம் என்ன" else "What time is it?") }
            BigButton(if (tamil) "வானிலை" else "Weather", if (tamil) "இன்றைய வானிலை" else "Today's weather", Modifier.weight(1f)) { ask("Weather today") }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (tamil) "சொல்லிப் பாருங்கள்: \"அம்மாவுக்கு கால் பண்ணு\", \"7 மணிக்கு அலாரம்\", \"மாத்திரை நினைவூட்டு\"" else "Try saying: \"Call Ravi\", \"Alarm at 7\", \"Remind me to take my tablet at 9\"",
            fontSize = 16.sp, color = cs.onSurfaceVariant,
        )
    }
}
