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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.seyal.app.data.Run
import com.seyal.app.data.RunStatus
import com.seyal.app.data.StepStatus
import com.seyal.app.engine.Engine
import com.seyal.app.engine.Times
import com.seyal.app.ui.theme.Ink

@Composable
fun ActivityScreen(openRunId: String?) {
    val cs = MaterialTheme.colorScheme
    val runs by Engine.runs.collectAsState()
    val tick by Engine.tick.collectAsState()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(openRunId) }
    var saving by remember { mutableStateOf<Run?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Activity", style = MaterialTheme.typography.headlineMedium)
            Text("One line per run. Tap for the log.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (runs.isEmpty()) Text("Nothing yet. Give a command on Home.", Modifier.padding(20.dp), color = cs.onSurfaceVariant)
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(runs, key = { it.id }) { run ->
                @Suppress("UNUSED_VARIABLE") val redraw = tick
                val open = expanded == run.id
                Card(onClick = { expanded = if (open) null else run.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val color = when (run.status) {
                            RunStatus.DONE -> Ink.green
                            RunStatus.FAILED, RunStatus.PARTIAL -> Ink.red
                            RunStatus.WAITING -> Ink.amber
                            RunStatus.CANCELLED -> cs.onSurfaceVariant
                            else -> Ink.green
                        }
                        Dot(color)
                        Spacer(Modifier.padding(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(run.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(run.summary ?: run.status.name.lowercase(), Times.format(run.createdAt), if (run.recipeId != null) "recipe" else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            )
                        }
                    }
                    if (open) {
                        Spacer(Modifier.height(12.dp))
                        Text(run.command, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        RunBody(run, scope, compact = true, tick = tick)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (run.steps.any { it.status == StepStatus.FAILED || it.status == StepStatus.SKIPPED || it.status == StepStatus.CANCELLED } && !run.status.isOpen) SmallButton("Retry failed") { Engine.retryFailed(run.id) }
                            if (run.steps.isNotEmpty()) SmallButton("Run again", filled = false) { Engine.rerun(run.id) }
                            if (run.steps.isNotEmpty() && run.recipeId == null) SmallButton("Save as recipe", filled = false) { saving = run }
                            SmallButton("Delete", filled = false) { Engine.delete(run.id) }
                        }
                    }
                }
            }
        }
    }
    saving?.let { run -> TriggerDialog(title = "Save as recipe", name = run.title, onDismiss = { saving = null }) { name, trig ->
        Engine.saveAsRecipe(run.id, name, trig); saving = null
    } }
}
