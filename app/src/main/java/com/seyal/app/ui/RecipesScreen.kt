package com.seyal.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.seyal.app.data.Recipe
import com.seyal.app.data.Trigger
import com.seyal.app.engine.Engine
import com.seyal.app.engine.Times
import com.seyal.app.ui.theme.Ink

@Composable
fun RecipesScreen() {
    val cs = MaterialTheme.colorScheme
    val recipes by Engine.recipes.collectAsState()
    val tick by Engine.tick.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Recipe?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Recipes", style = MaterialTheme.typography.headlineMedium)
            Text("Saved automations. They run on time, without the AI planning again.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (recipes.isEmpty()) Text("None yet. Say something like \"every morning at 8, give me AI news\", or save a run from Activity.", Modifier.padding(20.dp), color = cs.onSurfaceVariant)
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recipes, key = { it.id }) { r ->
                @Suppress("UNUSED_VARIABLE") val redraw = tick
                val isOpen = open == r.id
                Card(onClick = { open = if (isOpen) null else r.id; code = false }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium, color = if (r.enabled) cs.onSurface else cs.onSurfaceVariant)
                            val next = if (r.enabled && r.nextRun > 0) "next ${Times.format(r.nextRun)}" else if (!r.enabled) "paused" else "not scheduled"
                            val last = if (r.lastRun > 0) " · last ${Times.format(r.lastRun)}" else ""
                            Text("${r.trigger.describe()} · $next$last", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Switch(checked = r.enabled, onCheckedChange = { Engine.setRecipeEnabled(r.id, it) }, colors = SwitchDefaults.colors(checkedTrackColor = Ink.green))
                    }
                    if (isOpen) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Card", selected = !code) { code = false }
                            Chip("Code", selected = code) { code = true }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (code) {
                            Text(r.codeJson(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        } else {
                            KeyValue("When", r.trigger.describe())
                            KeyValue("Do", r.steps.mapIndexed { i, s -> "${i + 1}. ${s.title}" }.joinToString("\n"))
                            KeyValue("If it fails", "Retry twice, then one line to you")
                            KeyValue("From", r.command)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallButton("Test run") { Engine.runRecipe(r.id) }
                            SmallButton("Edit time", filled = false) { editing = r }
                            SmallButton("Delete", filled = false) { Engine.deleteRecipe(r.id); open = null }
                        }
                    }
                }
            }
        }
    }
    editing?.let { r ->
        TriggerDialog(title = "Edit recipe", name = r.name, trigger = r.trigger, onDismiss = { editing = null }) { name, trig ->
            Engine.updateRecipe(r.id, name, trig); editing = null
        }
    }
}

/** Name + when: daily / weekdays / once. */
@Composable
fun TriggerDialog(title: String, name: String, trigger: Trigger? = null, onDismiss: () -> Unit, onSave: (String, Trigger) -> Unit) {
    var n by remember { mutableStateOf(name) }
    var type by remember { mutableStateOf(trigger?.type ?: "daily") }
    var time by remember { mutableStateOf(trigger?.time ?: "08:00") }
    var day by remember { mutableStateOf(trigger?.day ?: "tomorrow") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Daily", type == "daily") { type = "daily" }
                    Chip("Weekdays", type == "weekly") { type = "weekly" }
                    Chip("Once", type == "once") { type = "once" }
                }
                OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("Time (HH:mm)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (type == "once") OutlinedTextField(value = day, onValueChange = { day = it }, label = { Text("Day (today, tomorrow, Friday, 2026-11-13)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = Times.parseClock(time.lowercase())?.let { String.format("%02d:%02d", it.hour, it.minute) } ?: "08:00"
                onSave(n, Trigger(type, t, if (type == "once") day else null, if (type == "weekly") listOf(1, 2, 3, 4, 5) else emptyList()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
