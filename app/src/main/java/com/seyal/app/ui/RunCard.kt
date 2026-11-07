package com.seyal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.seyal.app.data.Run
import com.seyal.app.data.RunStatus
import com.seyal.app.data.Step
import com.seyal.app.data.StepStatus
import com.seyal.app.engine.Engine
import com.seyal.app.ui.theme.Ink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The assistant's reply for one run: plan, approvals, choices, result. */
@Composable
fun RunBody(run: Run, scope: CoroutineScope, compact: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    var showCode by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (run.mode) {
            "answer" -> {
                Text(run.finalText ?: "", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            "clarify" -> {
                Text(run.question ?: "", style = MaterialTheme.typography.bodyLarge)
                if (run.options.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        run.options.forEach { opt -> Chip(opt) { scope.launch { Engine.answerClarify(run.id, opt) } } }
                    }
                }
                return@Column
            }
            "schedule" -> {
                Text(run.finalText ?: "Saved as a recipe", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
        }

        if (run.steps.isNotEmpty()) {
            val lanes = run.steps.count { it.dependsOn.isEmpty() }
            val headline = when (run.status) {
                RunStatus.DONE -> run.summary ?: "Done"
                RunStatus.PARTIAL, RunStatus.FAILED -> run.summary ?: "Some steps failed"
                RunStatus.CANCELLED -> "Cancelled"
                RunStatus.WAITING -> "Waiting for you"
                else -> if (run.steps.size == 1) "Doing it…" else "${run.steps.size} tasks · $lanes lanes run together"
            }
            Text(headline, style = MaterialTheme.typography.titleLarge)

            Card {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Plan")
                    Spacer(Modifier.weight(1f))
                    SmallButton(if (showCode) "Card" else "Code", filled = false) { showCode = !showCode }
                }
                Spacer(Modifier.height(10.dp))
                if (showCode) {
                    Text(run.planJson(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                } else {
                    run.steps.forEachIndexed { i, s -> StepRow(i + 1, s, run, scope) }
                }
                if (run.status.isOpen) {
                    val waiting = run.steps.filter { it.status == StepStatus.WAITING_APPROVAL }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (waiting.size > 1) PrimaryButton("Allow all") { Engine.approveAll(run.id, true) }
                        GhostButton("Cancel") { Engine.cancel(run.id) }
                    }
                }
            }
        }

        if (!run.status.isOpen && run.finalText != null) {
            Text(run.finalText!!, style = MaterialTheme.typography.bodyLarge)
            if (!compact) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (run.steps.any { it.status == StepStatus.FAILED || it.status == StepStatus.SKIPPED || it.status == StepStatus.CANCELLED }) SmallButton("Retry failed") { Engine.retryFailed(run.id) }
                SmallButton("Run again", filled = false) { Engine.rerun(run.id) }
            }
        }
    }
}

@Composable
private fun StepRow(n: Int, s: Step, run: Run, scope: CoroutineScope) {
    val cs = MaterialTheme.colorScheme
    val laneColor = if (s.dependsOn.isEmpty()) Ink.green else cs.outline
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(3.dp).height(20.dp).background(laneColor, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$n", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(s.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                StatusPill(s.status)
            }
            val sub = when (s.status) {
                StepStatus.DONE -> s.line
                StepStatus.FAILED, StepStatus.SKIPPED, StepStatus.CANCELLED -> s.error
                StepStatus.WAITING_APPROVAL -> s.line
                StepStatus.WAITING_CHOICE -> s.choicePrompt
                else -> if (s.dependsOn.isNotEmpty()) "after ${s.dependsOn.joinToString(", ")}" else null
            }
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 18.dp, top = 2.dp))

            if (s.status == StepStatus.WAITING_APPROVAL) {
                Row(Modifier.padding(start = 18.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Allow") { Engine.approve(run.id, s.id, true) }
                    SmallButton("No", filled = false) { Engine.approve(run.id, s.id, false) }
                }
            }
            if (s.status == StepStatus.WAITING_CHOICE) {
                val choices = s.choices
                if (choices != null) Row(Modifier.padding(start = 18.dp, top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until choices.length()) {
                        val label = choices.optJSONObject(i)?.optString("label") ?: continue
                        Chip(label) { Engine.choose(run.id, s.id, i) }
                    }
                }
            }
        }
    }
}

@Composable
fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier.widthIn(max = 300.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        ) { Text(text, style = MaterialTheme.typography.bodyLarge) }
    }
}
