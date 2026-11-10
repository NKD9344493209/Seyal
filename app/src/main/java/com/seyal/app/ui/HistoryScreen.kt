package com.seyal.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.seyal.app.agent.Agent
import com.seyal.app.data.SessionStatus
import com.seyal.app.util.Times

/** Everything Seyal has done, newest first. Tap one to see every step. */
@Composable
fun HistoryScreen(openSessionId: String?) {
    val cs = MaterialTheme.colorScheme
    val index by Agent.history.collectAsState()
    val sessions by Agent.sessions.collectAsState()
    var openId by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("all") }
    val activity = LocalContext.current as? MainActivity
    // Opened from a notification: show that conversation once, then forget the request.
    LaunchedEffect(openSessionId) {
        if (openSessionId != null) { openId = openSessionId; Agent.open(openSessionId); activity?.openSessionId?.value = null }
    }

    val open = openId?.let { id -> sessions.firstOrNull { it.id == id } }
    if (openId != null) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", modifier = Modifier.clickable { openId = null }.padding(4.dp))
                Spacer(Modifier.width(8.dp))
                Text(open?.title ?: "Session", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (open != null && !open.status.isOpen) SmallButton("Run again", filled = false) { Agent.start(open.firstCommand); openId = null }
                Spacer(Modifier.width(6.dp))
                SmallButton("Delete", filled = false, danger = true) { openId?.let { Agent.delete(it) }; openId = null }
            }
            if (open == null) Text("Loading…", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            else Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("${Times.format(open.createdAt)} · ${open.source.replace(':', ' ')}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                SessionView(open, onOpenSession = { openId = it; Agent.open(it) })
                Spacer(Modifier.padding(24.dp))
            }
        }
        return
    }

    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Text("Activity", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "All", "open" to "Open", "failed" to "Failed", "skills" to "Skills", "sub" to "Sub-tasks").forEach { (k, label) -> Chip(label, selected = filter == k) { filter = k } }
        }
        val shown = index.filter { e ->
            when (filter) {
                "open" -> e.status.isOpen
                "failed" -> e.status == SessionStatus.FAILED
                "skills" -> e.source.startsWith("skill")
                "sub" -> e.isChild
                else -> !e.isChild
            }
        }
        if (shown.isEmpty()) Text("Nothing here yet.", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { e ->
                Card(onClick = { openId = e.id; Agent.open(e.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.title.ifBlank { "(untitled)" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        SessionPill(e.status)
                    }
                    if (e.result.isNotBlank()) Text(e.result, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    val meta = Times.format(e.createdAt) + (if (e.steps > 0) " · ${e.steps} steps" else "") + (if (e.source != "user") " · " + e.source.substringBefore(':') else "")
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}
