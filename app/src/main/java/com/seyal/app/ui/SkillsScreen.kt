package com.seyal.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.seyal.app.agent.Agent
import com.seyal.app.agent.Skills
import com.seyal.app.data.Skill
import com.seyal.app.data.Trigger
import com.seyal.app.data.Only
import com.seyal.app.util.Times

/** Saved automations: what runs when, and the steps the agent recorded. */
@Composable
fun SkillsScreen(goTo: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val skills by Skills.skills.collectAsState()
    val triggerLog by Skills.triggerLog.collectAsState()
    var showLog by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Skill?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Skills", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (skills.any { it.trigger?.type == "notification" || it.trigger?.type == "event" }) { SmallButton(if (showLog) "Skills" else "Trigger log", filled = false) { showLog = !showLog }; Spacer(Modifier.width(8.dp)) }
            SmallButton(if (showGallery) "My skills" else "Ready-made", filled = false) { showGallery = !showGallery; showLog = false }
            Spacer(Modifier.width(8.dp))
            SmallButton("New") { creating = true }
        }
        Text("Things Seyal does on its own: on a schedule, when an app notifies you, or when you say so. Ask in chat (\"every morning at 8…\") or tap New.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        if (com.seyal.app.BuildConfig.FLAVOR == "full" && !showGallery && !showLog) {
            // Answering people by itself is the one thing that can embarrass the user: it has its own switch, off by default.
            var auto by remember { mutableStateOf(com.seyal.app.data.Settings.autoReplies) }
            val waiting = skills.count { it.enabled && it.trigger?.type == "notification" && Skills.sendsOut(it) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic replies", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (auto) "On: skills may answer a person's message by themselves. Never in group chats, one answer per chat every 10 minutes, and you are told each time."
                        else "Off: no skill sends a message by itself when someone messages or calls you." + (if (waiting > 0) " $waiting skill${if (waiting == 1) "" else "s"} wait${if (waiting == 1) "s" else ""} for this." else ""),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                Switch(checked = auto, onCheckedChange = { auto = it; com.seyal.app.data.Settings.autoReplies = it; Skills.log(if (it) "Automatic replies turned ON" else "Automatic replies turned OFF") })
            }
        }
        if (showGallery) {
            // Skills that work out of the box: one tap adds one (edit the time or words afterwards).
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(Skills.templates) { t ->
                    val have = skills.any { it.name.equals(t.name, true) }
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name, style = MaterialTheme.typography.titleMedium)
                                Text(t.trigger.describe(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                Text(t.about, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            if (have) Text("Added", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) else SmallButton("Add") { Skills.addTemplate(t) }
                        }
                    }
                }
            }
        } else if (showLog) {
            // What the message and event triggers saw since Seyal started, and why each did or didn't run.
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (triggerLog.isEmpty()) item { Text("Nothing yet. Messages and phone events that a skill watches for show up here, with what ran.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
                items(triggerLog) { line -> Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        } else SkillList(skills, { editing = it }, goTo)
    }

    editing?.let { s ->
        TriggerDialog(title = "Edit skill", name = s.name, goal = s.goal, trigger = s.trigger, onDismiss = { editing = null }) { name, _, trigger ->
            Skills.update(s.id, name, trigger); editing = null
        }
    }
    if (creating) {
        TriggerDialog(title = "New skill", name = "", goal = "", trigger = Trigger("time", "08:00"), onDismiss = { creating = false }) { name, goal, trigger ->
            Skills.save(name.ifBlank { goal.take(30) }, "", goal, emptyList(), trigger); creating = false
        }
    }
}

@Composable
private fun TriggerDialog(title: String, name: String, goal: String, trigger: Trigger?, onDismiss: () -> Unit, onSave: (String, String, Trigger?) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var n by remember { mutableStateOf(name) }
    var g by remember { mutableStateOf(goal) }
    var type by remember { mutableStateOf(trigger?.type ?: "manual") }
    var repeat by remember { mutableStateOf(trigger?.repeat ?: "daily") }
    var time by remember { mutableStateOf(trigger?.time?.ifBlank { "08:00" } ?: "08:00") }
    var day by remember { mutableStateOf(trigger?.day ?: "") }
    var days by remember { mutableStateOf(trigger?.days ?: listOf(1, 2, 3, 4, 5)) }
    var app by remember { mutableStateOf(trigger?.app ?: "") }
    var contains by remember { mutableStateOf(trigger?.contains ?: "") }
    var from by remember { mutableStateOf(trigger?.from ?: "") }
    var after by remember { mutableStateOf(trigger?.only?.after ?: "") }
    var before by remember { mutableStateOf(trigger?.only?.before ?: "") }
    var onlyWifi by remember { mutableStateOf(trigger?.only?.wifi ?: "") }
    var event by remember { mutableStateOf(trigger?.event?.ifBlank { "charging" } ?: "charging") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(n, { n = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (goal.isEmpty() && name.isEmpty()) OutlinedTextField(g, { g = it }, label = { Text("What should it do?") }, modifier = Modifier.fillMaxWidth())
                SectionLabel("When")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("manual" to "Manual", "time" to "Time", "notification" to "Notification", "event" to "Event").forEach { (k, l) -> Chip(l, selected = type == k) { type = k } }
                }
                when (type) {
                    "time" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("daily", "weekly", "once").forEach { r -> Chip(r.replaceFirstChar { it.uppercase() }, selected = repeat == r) { repeat = r } } }
                        OutlinedTextField(time, { time = it }, label = { Text("Time (HH:mm)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (repeat == "weekly") Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { i, d -> val v = i + 1; Chip(d, selected = v in days) { days = if (v in days) days - v else days + v } }
                        }
                        if (repeat == "once") OutlinedTextField(day, { day = it }, label = { Text("Day (today, tomorrow, friday, 2026-11-13)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    "notification" -> {
                        OutlinedTextField(app, { app = it }, label = { Text("From app (e.g. WhatsApp, Swiggy)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(from, { from = it }, label = { Text("From (name as in contacts, optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(contains, { contains = it }, label = { Text("Containing (optional; a|b for either)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Needs the Full edition with Notification access on.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    "event" -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        eventKinds.forEach { (k, l, _) -> Chip(l, selected = event.substringBefore(':') == k.substringBefore(':')) { event = k } }
                    }
                }
                // Events with a detail: which app, which Wi-Fi, which device, what level, who.
                if (type == "event") eventKinds.firstOrNull { it.first.endsWith(":") && event.substringBefore(':') == it.first.substringBefore(':') }?.let { (k, _, hint) ->
                    OutlinedTextField(event.substringAfter(':', ""), { event = k + it }, label = { Text(hint) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (k.startsWith("arrive") || k.startsWith("leave")) Text("Places are easiest by chat: \"when I reach home, …\" while you are there.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (type != "manual") {
                    Text("Only when (optional)", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(after, { after = it }, label = { Text("After HH:mm") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(before, { before = it }, label = { Text("Before HH:mm") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(onlyWifi, { onlyWifi = it }, label = { Text("On Wi-Fi named (* = any, - = none)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Keep the conditions this form doesn't show (battery, charging, days) as they were.
                val old = trigger?.only
                val only = Only(after.trim(), before.trim(), old?.days ?: emptyList(), old?.batteryBelow ?: 0, old?.batteryAbove ?: 0, old?.charging, onlyWifi.trim()).takeIf { !it.isEmpty }
                val t = when (type) {
                    "time" -> Trigger("time", time.trim(), day.trim().ifBlank { null }, days, repeat, only = only)
                    "notification" -> Trigger("notification", app = app.trim(), contains = contains.trim(), from = from.trim(), only = only)
                    "event" -> Trigger("event", event = event.trimEnd(':'), only = only, lat = trigger?.lat ?: 0.0, lon = trigger?.lon ?: 0.0, radius = trigger?.radius ?: 200)
                    else -> null
                }
                onSave(n.trim(), g.trim(), t)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SkillList(skills: List<Skill>, onEdit: (Skill) -> Unit, goTo: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity
    if (skills.isEmpty()) Text("No skills yet.", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(skills, key = { it.id }) { s ->
                var code by remember(s.id) { mutableStateOf(false) }
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium)
                            Text(s.trigger?.describe() ?: "Manual", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Switch(checked = s.enabled, onCheckedChange = { Skills.setEnabled(s.id, it) })
                    }
                    if (s.description.isNotBlank() || s.goal.isNotBlank()) Text(s.description.ifBlank { s.goal }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    val meta = listOfNotNull(
                        if (s.steps.isNotEmpty()) "${s.steps.size} fixed steps" + (if (Agent.wantsAnswer(s.goal)) "" else " · runs without the AI") else "the AI works it out each time",
                        if (s.nextRun > 0 && s.enabled) "next ${Times.format(s.nextRun)}" else null,
                        if (s.lastRun > 0) "last ${Times.format(s.lastRun)}" else null,
                    ).joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // The run shows in Activity (chats stay for conversations).
                        SmallButton("Run now") { Skills.run(s.id)?.let { id -> activity?.openSessionId?.value = id } ?: goTo(Tab.HISTORY) }
                        if (s.trigger != null) SmallButton("Test", filled = false) { Skills.test(s.id)?.let { id -> activity?.openSessionId?.value = id } }
                        SmallButton("Edit", filled = false) { onEdit(s) }
                        if (activity != null) SmallButton("Add to home screen", filled = false) { pinSkill(activity, s) }
                        SmallButton(if (code) "Card" else "Code", filled = false) { code = !code }
                        SmallButton("Delete", filled = false, danger = true) { Skills.delete(s.id) }
                    }
                    if (code) Text(s.codeJson(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
}

/** The phone events a skill can wait for: key (ending in ":" when it takes a detail), label, and the detail's hint. */
private val eventKinds = listOf(
    Triple("charging", "Charging", ""), Triple("unplugged", "Unplugged", ""), Triple("boot", "Phone starts", ""),
    Triple("app_opened:", "App opens", "App name"),
    Triple("wifi_connected:", "Wi-Fi connects", "Wi-Fi name (empty = any)"), Triple("wifi_disconnected:", "Wi-Fi disconnects", "Wi-Fi name (empty = any)"),
    Triple("bluetooth_connected:", "Bluetooth connects", "Device name (empty = any)"), Triple("bluetooth_disconnected:", "Bluetooth disconnects", "Device name (empty = any)"),
    Triple("headphones_in", "Headphones in", ""), Triple("headphones_out", "Headphones out", ""),
    Triple("battery_below:", "Battery below", "Percent, e.g. 20"), Triple("battery_above:", "Battery above", "Percent, e.g. 90"),
    Triple("call_from:", "Someone calls", "Name as in contacts (empty = anyone)"),
    Triple("arrive:", "Reach a place", "Place name"), Triple("leave:", "Leave a place", "Place name"),
)

/** Puts a button for this skill on the home screen: one tap runs it. */
private fun pinSkill(activity: MainActivity, s: Skill) {
    val sm = activity.getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
    if (!sm.isRequestPinShortcutSupported) { android.widget.Toast.makeText(activity, "This home screen doesn't take shortcuts", android.widget.Toast.LENGTH_SHORT).show(); return }
    val intent = android.content.Intent(activity, MainActivity::class.java).setAction(MainActivity.ACTION_RUN_SKILL).putExtra("skill", s.id)
    val info = android.content.pm.ShortcutInfo.Builder(activity, "skill_${s.id}").setShortLabel(s.name.take(24)).setLongLabel("Run ${s.name}".take(40))
        .setIcon(android.graphics.drawable.Icon.createWithResource(activity, com.seyal.app.R.mipmap.ic_launcher)).setIntent(intent).build()
    runCatching { sm.requestPinShortcut(info, null) }
}
