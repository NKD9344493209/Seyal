package com.seyal.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.seyal.app.agent.Foresight
import com.seyal.app.agent.ForesightRun
import com.seyal.app.agent.ForesightStore
import com.seyal.app.agent.Scheduler
import com.seyal.app.data.Settings
import com.seyal.app.ui.theme.Ink
import com.seyal.app.util.Times

internal fun stageLabel(stage: String) = when (stage) { "early" -> "Early"; "mainstream" -> "Already mainstream"; else -> "Rising" }
internal fun verdictLabel(v: String) = when (v) { "right" -> "Right"; "wrong" -> "Wrong"; "unclear" -> "Unclear"; else -> "Open" }

/**
 * Dirghadarshi mode: what is starting to rise, with proof; what it means for the user; one prediction per trend,
 * and how the earlier predictions turned out. Shown as the "World signals" page of the Dirghadarshi tab.
 */
@Composable
fun ForesightSection() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val version by ForesightStore.version.collectAsState()
    val doing by ForesightRun.state.collectAsState()
    val report = remember(version) { ForesightStore.latest() }
    val calls = remember(version) { ForesightStore.calls() }
    var weekly by remember(version) { mutableStateOf(ForesightStore.enabled) }
    var topics by remember(version) { mutableStateOf(ForesightStore.topics.joinToString(", ")) }
    var editing by remember { mutableStateOf(false) }
    val error = remember(version) { ForesightStore.lastError }

    Card {
        Text("World signals", style = MaterialTheme.typography.titleMedium)
        Text(
            "What Dirghadarshi reads about the world. Seyal reads this week's public signals (new code projects, engineers' discussions, research papers, news on your topics) and tells you what is starting to rise, with the proof, what it means for you, and one thing to do about it.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallButton(if (doing.isBlank()) "Scan now" else "Scanning…", filled = doing.isBlank() && Settings.hasKey) { if (doing.isBlank() && Settings.hasKey) ForesightRun.start(ctx) }
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    doing.isNotBlank() -> doing
                    !Settings.hasKey -> "Add your AI key first (Control → AI)."
                    report != null -> "Last report: ${Times.format(report.at)} · ${report.signals} signals"
                    else -> "Takes about half a minute and one AI request."
                },
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
        }
        if (doing.isBlank() && error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = Ink.red, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(6.dp))
        SwitchRow("A report every Sunday morning", "Made by itself around 9 AM (later that day if the phone had no internet), with a notification.", weekly) {
            ForesightStore.setEnabled(it); weekly = it; runCatching { Scheduler.syncBackground(ctx) }
        }
        Spacer(Modifier.height(6.dp))
        if (!editing) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Watching: ${ForesightStore.topics.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("Change", style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.clickable { editing = true }.padding(6.dp))
        } else {
            OutlinedTextField(topics, { topics = it }, label = { Text("Up to four topics, with commas") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row {
                SmallButton("Save") { ForesightStore.setTopics(topics); editing = false }
                Spacer(Modifier.width(8.dp))
                SmallButton("Cancel", filled = false) { topics = ForesightStore.topics.joinToString(", "); editing = false }
            }
        }
    }

    if (report == null) {
        Text("No report yet. Tap Scan now: the first one takes about half a minute.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
    } else {
        SectionLabel("What is rising · ${report.day}")
        if (report.trends.isEmpty()) Text("Nothing stood on enough evidence this week.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        report.trends.forEach { t -> TrendCard(t) }
        Text(
            "Read from ${report.signals} signals: ${report.read.joinToString(", ")}." + (if (report.failed.isNotEmpty()) " Could not read: ${report.failed.joinToString(", ")}." else ""),
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    Card {
        Text("Track record", style = MaterialTheme.typography.titleMedium)
        Text(Foresight.record(calls), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Text("Every trend comes with one prediction and a date. When the date comes, it is judged in the next report and counted here, right or wrong.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        calls.sortedByDescending { it.madeOn }.take(12).forEach { c ->
            Column(Modifier.padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(verdictLabel(c.verdict), style = MaterialTheme.typography.labelMedium, color = when (c.verdict) { "right" -> Ink.green; "wrong" -> Ink.red; else -> cs.onSurfaceVariant })
                    Spacer(Modifier.width(8.dp))
                    Text(if (c.verdict.isBlank()) "made ${c.madeOn} · checked on ${c.checkBy}" else "made ${c.madeOn} · judged ${c.checkedOn}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                Text(c.text, style = MaterialTheme.typography.bodySmall)
                if (c.note.isNotBlank()) Text(c.note, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
    Text("A reading of public signals, not certainty, and never investment advice. Check the proof links before you act on anything.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun TrendCard(t: Foresight.Trend) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Card {
        Text(t.title, style = MaterialTheme.typography.titleMedium)
        Text("${stageLabel(t.stage)} · ${t.confidence}% sure" + (if (t.horizon.isNotBlank()) " · matters in ${t.horizon}" else ""), style = MaterialTheme.typography.labelMedium, color = cs.primary)
        Spacer(Modifier.height(4.dp))
        Text(t.what, style = MaterialTheme.typography.bodyMedium)
        if (t.means.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text("For you", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant); Text(t.means, style = MaterialTheme.typography.bodySmall) }
        if (t.act.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text("This week", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant); Text(t.act, style = MaterialTheme.typography.bodySmall) }
        if (t.call.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text("My call (checked on ${t.checkBy})", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant); Text(t.call, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(6.dp))
        Text(if (open) "Hide the proof" else "Show the proof (${t.proof.size} signals)", style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp))
        if (open) t.proof.forEach { p ->
            Column(Modifier.fillMaxWidth().clickable(enabled = p.url.isNotBlank()) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }.padding(vertical = 4.dp)) {
                Text(Foresight.line(p), style = MaterialTheme.typography.bodySmall)
                if (p.url.isNotBlank()) Text(p.url.removePrefix("https://").take(60), style = MaterialTheme.typography.labelSmall, color = cs.primary)
            }
        }
    }
}
