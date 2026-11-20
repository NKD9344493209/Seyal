package com.seyal.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.seyal.app.agent.Agent
import com.seyal.app.agent.Aware
import com.seyal.app.agent.AwareRun
import com.seyal.app.agent.AwareStore
import com.seyal.app.agent.Scheduler
import com.seyal.app.data.Settings
import com.seyal.app.ui.theme.Ink
import com.seyal.app.util.Times
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * The Dirghadarshi tab (தீர்க்கதரிசி, "one who sees far"). Everything Dirghadarshi does for the user in one place:
 * today's brief over the interests they chose (warnings first; a tap on a headline shows what the story says),
 * a box to ask before a decision (it opens a chat with the thinking mode on), the interests themselves, the
 * alerts that were sent, and, on its second page, the weekly reading of world signals. Its third page is the
 * internship and job watcher (JobsScreen.kt).
 * Asked for by Naveen on 19 Nov 2026: before this it was spread over the chat, the Brain tab and notifications.
 */
@Composable
fun DirghadarshiScreen(goTo: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val activity = LocalContext.current as? MainActivity
    var page by remember { mutableStateOf(0) }
    // A tap on the morning brief opens "For you"; on the weekly report, "World signals".
    val wantPage = activity?.dirghaPage?.value
    LaunchedEffect(wantPage) { if (wantPage != null) { page = wantPage; activity?.dirghaPage?.value = null } }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp)) {
            Text("Dirghadarshi", style = MaterialTheme.typography.headlineMedium)
            Text(
                when (page) {
                    0 -> "Sees far for you: what to know today, what to watch out for, and help before you decide."
                    1 -> "What is starting to rise in the world, told early, with proof."
                    else -> "Internships and fresher jobs that fit you, from companies' own job boards, checked through the day."
                },
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Chip("For you", selected = page == 0) { page = 0 }
                Spacer(Modifier.width(8.dp))
                Chip("World signals", selected = page == 1) { page = 1 }
                Spacer(Modifier.width(8.dp))
                Chip("Jobs", selected = page == 2) { page = 2 }
            }
        }
        when (page) {
            1 -> ForesightSection()
            2 -> JobsPage()
            else -> ForYou(goTo)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Splits what was typed into interests: commas, semicolons or new lines between them. */
private fun typed(s: String): List<String> = s.split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }

@Composable
private fun ForYou(goTo: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()
    val version by AwareStore.version.collectAsState()
    val interests = remember(version) { AwareStore.interests }
    val heads = remember(version) { AwareStore.briefHeads }
    val rest = remember(version) { AwareStore.briefRest }
    val warned = remember(version) { AwareStore.briefWarned }
    val latest = remember(version) { AwareStore.latest }
    val latestAt = remember(version) { AwareStore.latestAt }
    val alerts = remember(version) { AwareStore.alerts }
    var daily by remember(version) { mutableStateOf(AwareStore.daily) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf("") }
    val read: () -> Unit = {
        if (!busy) {
            busy = true; failed = ""
            scope.launch {
                val b = runCatching { AwareRun.brief() }.getOrNull()
                if (b == null || !b.ok) failed = b?.text ?: Aware.UNREACHABLE
                busy = false
            }
        }
    }

    // Opening the tab shows today's news: an old brief (another day's, or hours old) is read again by itself. No AI is used.
    LaunchedEffect(interests.isNotEmpty()) { if (interests.isNotEmpty() && Aware.stale(AwareStore.latestAt, System.currentTimeMillis(), zone)) read() }

    // ---- Nothing chosen yet: one box, one button.
    if (interests.isEmpty()) {
        var first by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        Card {
            Text("Start here: what should I watch for you?", style = MaterialTheme.typography.titleMedium)
            Text(
                "Type the things you care about, in your own words, with commas between them. Every day I read the news on each one from five sides: what to watch out for, what is new, opportunities, research, and rules and money.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = first, onValueChange = { first = it; note = "" },
                label = { Text("Your interests (up to 12)") },
                placeholder = { Text("AI, Java, data engineering jobs, gold price") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallButton("Save and read now") {
                    if (typed(first).none { it.length >= 2 }) note = "Type at least one interest first."
                    else { AwareStore.set(typed(first)); runCatching { Scheduler.syncBackground(ctx) } }   // the brief is then read by itself (see above)
                }
                Spacer(Modifier.width(10.dp))
                Text(note.ifBlank { "Takes a few seconds. No AI request." }, style = MaterialTheme.typography.labelSmall, color = if (note.isBlank()) cs.onSurfaceVariant else Ink.red, modifier = Modifier.weight(1f))
            }
        }
    } else {
        // ---- Today's brief
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Today's brief", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SmallButton(if (busy) "Reading…" else "Read now", filled = !busy) { read() }
            }
            Text(
                when {
                    busy -> "Reading the news on each interest… a few seconds, no AI."
                    latestAt > 0 && heads.isNotEmpty() -> "Read ${Times.format(latestAt)} · ${heads.size} picked for an early adopter out of ${heads.size + rest.size} headlines of the last two days: what to act on, what is new to try, openings, results and rule changes. Tap one to see what it says."
                    latestAt > 0 -> "Read ${Times.format(latestAt)} · ${rest.size} headlines from the last two days."
                    else -> "Not read yet. Tap Read now."
                },
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            if (!busy && failed.isNotBlank()) Text(failed, style = MaterialTheme.typography.bodySmall, color = Ink.red, modifier = Modifier.padding(top = 6.dp))
            if (heads.isEmpty() && latestAt > 0 && latest.isNotBlank()) Rich(latest, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            var section = ""
            heads.forEachIndexed { i, h ->
                val now = Aware.section(i, warned, h)
                if (now != section) { section = now; Spacer(Modifier.height(10.dp)); SectionLabel(now) }
                HeadlineRow(h, zone, goTo)
            }
            if (rest.isNotEmpty()) {
                var all by remember(version) { mutableStateOf(false) }
                Text(
                    if (all) "Hide the other ${rest.size}" else "Show the other ${rest.size} headlines",
                    style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.clickable { all = !all }.padding(vertical = 8.dp),
                )
                if (all) rest.forEach { h -> HeadlineRow(h, zone, goTo, topic = true) }
            }
        }
    }

    // ---- Ask before a decision: the thinking mode, started from here.
    var ask by remember { mutableStateOf("") }
    Card {
        Text("Ask before you decide", style = MaterialTheme.typography.titleMedium)
        Text(
            "Dirghadarshi looks things up, checks every figure against what it read, and answers short: the answer, why, the risk, how sure, and the sources. It takes a minute or two.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = ask, onValueChange = { ask = it },
            label = { Text("Your question") },
            placeholder = { Text("Should I buy a phone now or wait for the year-end sale?") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallButton("Think it through", filled = ask.isNotBlank() && Settings.hasKey) {
                if (!Settings.hasKey) goTo(Tab.CONTROL)
                else if (ask.isNotBlank()) { Agent.start(ask.trim(), deep = true); ask = ""; goTo(Tab.HOME) }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (!Settings.hasKey) "This part needs your AI key (Control → AI). The brief does not." else "Opens a new chat with Dirghadarshi mode on.",
                style = MaterialTheme.typography.labelSmall, color = if (Settings.hasKey) cs.onSurfaceVariant else Ink.red, modifier = Modifier.weight(1f),
            )
        }
    }

    // ---- The interests, and every headline of the week on one of them (or on any topic).
    var picked by remember { mutableStateOf<String?>(null) }
    var swept by remember { mutableStateOf<AwareRun.Swept?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showing by remember { mutableStateOf(Aware.PAGE) }
    var add by remember { mutableStateOf("") }
    var addNote by remember { mutableStateOf("") }
    var sure by remember { mutableStateOf(false) }
    val look: (String) -> Unit = { t ->
        picked = t; swept = null; loading = true; showing = Aware.PAGE; sure = false
        scope.launch { val s = runCatching { AwareRun.sweep(t, 7) }.getOrNull(); if (picked == t) { swept = s; loading = false } }
    }
    if (interests.isNotEmpty()) Card {
        Text("Your interests", style = MaterialTheme.typography.titleMedium)
        Text("Tap one for every headline of the week on it.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            interests.forEach { t -> Chip(t, selected = picked == t) { if (picked == t) { picked = null; swept = null; loading = false } else look(t) } }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = add, onValueChange = { add = it; addNote = "" },
            label = { Text("Add an interest, or look up any topic") },
            placeholder = { Text("cricket") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallButton("Add to my interests", filled = add.isNotBlank()) {
                val xs = typed(add).filter { it.length >= 2 }
                if (xs.isEmpty()) addNote = "Type it first."
                else if (interests.size >= 12) addNote = "Twelve at most. Remove one first."
                else { AwareStore.add(xs); add = ""; runCatching { Scheduler.syncBackground(ctx) } }
            }
            Spacer(Modifier.width(8.dp))
            SmallButton("Just show headlines", filled = false) {
                val t = add.trim()
                if (t.length < 2) addNote = "Type a topic first." else look(t)
            }
        }
        if (addNote.isNotBlank()) Text(addNote, style = MaterialTheme.typography.labelSmall, color = Ink.red, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(6.dp))
        SwitchRow("A brief every morning at 8, and an alert for something big", "The alert comes at once when a warning in one of your interests is carried by three or more publishers. Checked every three hours, 7 AM to 10 PM.", daily) { v ->
            daily = v; AwareStore.setDaily(v); runCatching { Scheduler.syncBackground(ctx) }
        }
        if (daily && !com.seyal.app.device.Notify.allowed(ctx)) Text("Seyal may not show notifications, so the brief and alerts will appear only here. Allow notifications for Seyal in the phone's settings.", style = MaterialTheme.typography.bodySmall, color = Ink.red, modifier = Modifier.padding(top = 4.dp))
    }

    val topic = picked
    if (topic != null) Card {
        val s = swept
        Text("$topic · this week", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                loading -> "Reading from five sides…"
                s == null || s.unreachable -> Aware.UNREACHABLE
                s.heads.isEmpty() -> "No news on $topic in the last 7 days from the sources I could read (${s.read} stories checked). Try a wider word."
                else -> "${s.heads.size} headlines from the last 7 days (${s.read} stories read, the same story counted once)." + (if (s.failed > 0) " ${s.failed} of ${s.asked} readings did not answer, so some may be missing." else "")
            },
            style = MaterialTheme.typography.bodySmall, color = if (!loading && (s == null || s.unreachable)) Ink.red else cs.onSurfaceVariant,
        )
        if (!loading && s != null && s.heads.isNotEmpty()) {
            // First what an early adopter needs; then everything else, by angle.
            val best = remember(s) { Aware.picks(s.heads, 8, System.currentTimeMillis()) }
            if (best.isNotEmpty()) { Spacer(Modifier.height(10.dp)); SectionLabel("Picked for an early adopter"); best.forEach { h -> HeadlineRow(h, zone, goTo) } }
            val page = remember(s, showing) { Aware.numbered(Aware.pick(s.heads.filter { it !in best }, showing)) }
            Aware.ANGLES.forEach { a ->
                val mine = page.filter { it.item.angle == a.id }
                if (mine.isNotEmpty()) { Spacer(Modifier.height(10.dp)); SectionLabel(a.label); mine.forEach { h -> HeadlineRow(h, zone, goTo) } }
            }
            if (page.size + best.size < s.heads.size) Text(
                "Show ${minOf(Aware.PAGE, s.heads.size - best.size - page.size)} more (${s.heads.size - best.size - page.size} left)",
                style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.clickable { showing += Aware.PAGE }.padding(vertical = 8.dp),
            )
        }
        if (interests.any { it.equals(topic, true) }) {
            Spacer(Modifier.height(8.dp))
            if (sure) SmallButton("Yes, stop watching $topic", filled = false, danger = true) { AwareStore.remove(listOf(topic)); picked = null; swept = null; sure = false; runCatching { Scheduler.syncBackground(ctx) } }
            else SmallButton("Remove $topic from my interests", filled = false) { sure = true }
        }
    }

    // ---- The alerts that were sent, so a missed notification is not lost.
    if (interests.isNotEmpty()) Card {
        Text("Alerts", style = MaterialTheme.typography.titleMedium)
        if (alerts.isEmpty()) Text(
            if (daily) "None so far. One comes when a warning in your interests is carried by three or more publishers." else "Off. Turn on the switch above to get the morning brief and alerts.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        alerts.take(10).forEach { a ->
            Column(Modifier.fillMaxWidth().clickable(enabled = a.url.startsWith("http")) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }.padding(vertical = 6.dp)) {
                Text(a.title, style = MaterialTheme.typography.bodyMedium)
                Text("${a.topic} · ${a.source}" + (if (a.carriers > 1) " and ${a.carriers - 1} more" else "") + " · ${Times.format(a.at)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }

    Text(
        "The headlines are the publishers' own words, read from Google News. They are real headlines, not checked facts: one carried by several publishers (+N more) or by a known one is the safer to believe, and \"one source\" marks the rest. The picks are made by fixed rules, not by an AI. Only \"What it means for me\" and \"Ask before you decide\" use your AI.",
        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/**
 * One headline. A tap opens it in place: what the story's own page says it is about, who else carries it, a button
 * to open the story and one to ask what it means for the user (a chat with the thinking mode on).
 * [topic]: show the interest it belongs to in the small line (used where no heading names it).
 */
@Composable
private fun HeadlineRow(h: Aware.Headline, zone: ZoneId, goTo: (Tab) -> Unit, topic: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember(h.item.title, h.item.url) { mutableStateOf(false) }
    var loading by remember(h.item.title, h.item.url) { mutableStateOf(false) }
    var story by remember(h.item.title, h.item.url) { mutableStateOf<AwareRun.Story?>(null) }
    Column(
        Modifier.fillMaxWidth().clickable {
            open = !open
            if (open && story == null && !loading) {
                loading = true
                scope.launch { story = runCatching { AwareRun.story(h) }.getOrNull() ?: AwareRun.Story(null, null, ""); loading = false }
            }
        }.padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (Aware.actNow(h)) { Box(Modifier.padding(top = 6.dp, end = 8.dp)) { Dot(Ink.red) } }
            Text(h.item.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        Text(
            listOf(if (topic) h.item.topic else "", Aware.tag(h), Aware.meta(h, zone), Aware.caution(h)).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        )
        if (open) {
            Spacer(Modifier.height(6.dp))
            if (loading) Text("Reading the story…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            else {
                Text(story?.lead ?: "I could not read the story itself just now. Open it to read.", style = MaterialTheme.typography.bodySmall)
                if (h.others.isNotEmpty()) Text("Also in " + h.others.take(4).joinToString(", ") + (if (h.others.size > 4) " and ${h.others.size - 4} more" else ""), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(8.dp))
                Row {
                    SmallButton("Open the story", filled = false) {
                        val link = story?.url ?: h.item.url
                        if (link.startsWith("http")) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    SmallButton("What it means for me") {
                        if (!Settings.hasKey) goTo(Tab.CONTROL)
                        else { Agent.start(Aware.explainAsk(h, zone), deep = true); goTo(Tab.HOME) }
                    }
                }
            }
        }
    }
}
