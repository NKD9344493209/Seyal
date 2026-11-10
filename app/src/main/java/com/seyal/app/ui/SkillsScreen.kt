package com.seyal.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.seyal.app.util.Times

/** Saved automations: what runs when, and the steps the agent recorded. */
@Composable
fun SkillsScreen(goTo: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val skills by Skills.skills.collectAsState()
    var editing by remember { mutableStateOf<Skill?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Skills", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            SmallButton("New") { creating = true }
        }
        Text("Things Seyal does on its own: on a schedule, when an app notifies you, or when you say so. Ask in chat (\"every morning at 8…\") or tap New.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
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
                        if (s.steps.isNotEmpty()) "${s.steps.size} recorded steps" else "agent decides",
                        if (s.nextRun > 0 && s.enabled) "next ${Times.format(s.nextRun)}" else null,
                        if (s.lastRun > 0) "last ${Times.format(s.lastRun)}" else null,
                    ).joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("Run now") { Skills.run(s.id); goTo(Tab.HOME) }
                        SmallButton("Edit", filled = false) { editing = s }
                        SmallButton(if (code) "Card" else "Code", filled = false) { code = !code }
                        SmallButton("Delete", filled = false, danger = true) { Skills.delete(s.id) }
                    }
                    if (code) Text(s.codeJson(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
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
                        OutlinedTextField(contains, { contains = it }, label = { Text("Containing (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Needs the Full edition with Notification access on.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    "event" -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("charging" to "Charging", "unplugged" to "Unplugged", "boot" to "Phone starts", "app_opened:" to "App opens").forEach { (k, l) -> Chip(l, selected = event.startsWith(k)) { event = k } }
                    }
                }
                if (type == "event" && event.startsWith("app_opened")) OutlinedTextField(event.removePrefix("app_opened:"), { event = "app_opened:$it" }, label = { Text("App name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = when (type) {
                    "time" -> Trigger("time", time.trim(), day.trim().ifBlank { null }, days, repeat)
                    "notification" -> Trigger("notification", app = app.trim(), contains = contains.trim())
                    "event" -> Trigger("event", event = event)
                    else -> null
                }
                onSave(n.trim(), g.trim(), t)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
