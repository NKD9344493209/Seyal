package com.seyal.app.ui

import android.content.Context
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
import androidx.compose.material3.Switch
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
import com.seyal.app.agent.JobRun
import com.seyal.app.agent.JobStore
import com.seyal.app.agent.Jobs
import com.seyal.app.agent.Scheduler
import com.seyal.app.ui.theme.Ink
import com.seyal.app.util.Times
import kotlinx.coroutines.launch

/** Opens a posting or a search in the browser or the site's app. */
private fun openLink(ctx: Context, url: String) {
    if (url.startsWith("http")) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Dirghadarshi → Jobs: the internship and job watcher. First a short form (roles, places, internships or jobs);
 * then the openings that fit, best first, each with why it fits and one tap to open it; fresh searches on the big
 * job sites; hiring drives in the news; and the companies whose job boards are read. No AI is used here.
 */
@Composable
fun JobsPage() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by JobStore.version.collectAsState()
    val store = remember(version) { JobStore.get() }
    val profile = store.profile
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf("") }
    var editing by remember(version) { mutableStateOf(profile == null) }
    val check: () -> Unit = {
        if (!busy) {
            busy = true; problem = ""
            scope.launch {
                val r = runCatching { JobRun.check() }.getOrNull()
                problem = r?.problem ?: "The check stopped. Try again."
                busy = false
            }
        }
    }
    // Opening the page shows fresh openings: a look older than the background's interval is done again by itself.
    LaunchedEffect(profile != null) {
        if (profile != null && System.currentTimeMillis() - JobStore.get().checkedAt > JobRun.EVERY_MS) check()
    }

    if (editing) {
        ProfileCard(profile, onCancel = if (profile != null) ({ editing = false }) else null) { p ->
            JobStore.setProfile(p); editing = false
            runCatching { Scheduler.syncBackground(ctx) }
            check()
        }
        return
    }
    val p = profile ?: return

    // ---- What is watched
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Internship and job watcher", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = store.on, onCheckedChange = { JobStore.setOn(it); runCatching { Scheduler.syncBackground(ctx) } })
        }
        Text(p.said() + ".", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Text(
            if (store.on) "Alerts on: I look about every 6 hours between 7 AM and 10 PM and tell you when a new opening fits." else "Alerts off. Tap Check now to look anyway.",
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallButton(if (busy) "Checking…" else "Check now", filled = !busy) { check() }
            Spacer(Modifier.width(8.dp))
            SmallButton("Change", filled = false) { editing = true }
        }
        val when_ = when {
            busy -> "Reading ${store.companies.size} companies' job boards… up to half a minute, no AI."
            store.checkedAt > 0 -> "Checked ${Times.format(store.checkedAt)} · ${store.looked} openings read from ${store.companies.size - store.failed} of ${store.companies.size} companies"
            else -> "Not checked yet."
        }
        Text(when_, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        if (!busy && problem.isNotBlank()) Text(problem, style = MaterialTheme.typography.bodySmall, color = Ink.red, modifier = Modifier.padding(top = 4.dp))
    }

    // ---- The openings that fit
    Card {
        Text(if (store.matches.isEmpty()) "Openings that fit you" else "${store.matches.size} openings fit you", style = MaterialTheme.typography.titleMedium)
        if (store.matches.isEmpty()) Text(
            if (store.checkedAt > 0) "None right now. Most companies post a few openings a week; I keep looking and tell you the moment one fits." else "Tap Check now.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        else Text("Best first. Tap one to open it and apply on the company's own page.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        var all by remember(version) { mutableStateOf(false) }
        val shown = if (all) store.matches else store.matches.take(15)
        shown.forEach { m -> MatchRow(m, m.job.id in store.newIds) { openLink(ctx, m.job.url) } }
        if (store.matches.size > 15) Text(
            if (all) "Show fewer" else "Show all ${store.matches.size}",
            style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.clickable { all = !all }.padding(vertical = 8.dp),
        )
        if (store.aside.isNotEmpty()) Text(
            "Set aside: " + store.aside.entries.sortedByDescending { it.value }.take(6).joinToString(" · ") { "${it.value} ${it.key}" },
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
        )
    }
    // Seen: nothing is "new" once the page has been open a moment.
    LaunchedEffect(store.newIds.size) { if (store.newIds.isNotEmpty()) { kotlinx.coroutines.delay(4000); JobStore.viewed() } }

    // ---- Fresh searches on the big job sites
    Card {
        Text("Search the big job sites", style = MaterialTheme.typography.titleMedium)
        Text("These open the site with your role and place filled in. Seyal does not read them; most new postings there appear within a day.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Jobs.links(p).forEach { l ->
            Row(Modifier.fillMaxWidth().clickable { openLink(ctx, l.url) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(l.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(l.note, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }

    // ---- Hiring drives in the news
    if (store.news.isNotEmpty()) Card {
        Text("Hiring drives in the news", style = MaterialTheme.typography.titleMedium)
        Text("From news sites, the last 7 days. Apply only on the company's own website; never pay to apply.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        store.news.forEach { n ->
            Column(Modifier.fillMaxWidth().clickable { openLink(ctx, n.url) }.padding(vertical = 6.dp)) {
                Text(n.title, style = MaterialTheme.typography.bodyMedium)
                Text(listOf(n.source, if (n.at > 0) Times.format(n.at) else "").filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }

    // ---- The companies whose boards are read
    CompaniesCard(store)
}

@Composable
private fun MatchRow(m: Jobs.Match, new: Boolean, open: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clickable { open() }.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.job.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (new) Pill("New", Ink.greenSoft, Ink.greenDeep)
        }
        Text(m.job.company + " · " + m.why.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun ProfileCard(old: Jobs.Profile?, onCancel: (() -> Unit)?, onSave: (Jobs.Profile) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var roles by remember { mutableStateOf(old?.roles?.joinToString(", ").orEmpty()) }
    var places by remember { mutableStateOf(old?.places?.joinToString(", ") { Jobs.placeName(it) }.orEmpty()) }
    var skills by remember { mutableStateOf(old?.skills?.joinToString(", ").orEmpty()) }
    var kind by remember { mutableStateOf(old?.kind ?: Jobs.Kind.BOTH) }
    var years by remember { mutableStateOf((old?.maxYears ?: 1).toString()) }
    var note by remember { mutableStateOf("") }
    Card {
        Text(if (old == null) "Start here: what should I watch for you?" else "Change what I watch", style = MaterialTheme.typography.titleMedium)
        Text(
            "I read companies' own job boards through the day and tell you when a new opening fits: the right role, place and level, posted recently, and never one that asks you for money.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Chip("Internships", selected = kind == Jobs.Kind.INTERN) { kind = Jobs.Kind.INTERN }
            Spacer(Modifier.width(6.dp))
            Chip("Jobs", selected = kind == Jobs.Kind.JOB) { kind = Jobs.Kind.JOB }
            Spacer(Modifier.width(6.dp))
            Chip("Both", selected = kind == Jobs.Kind.BOTH) { kind = Jobs.Kind.BOTH }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(roles, { roles = it; note = "" }, label = { Text("Roles") }, placeholder = { Text("data engineer, data analyst") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(places, { places = it }, label = { Text("Places (empty = anywhere in India)") }, placeholder = { Text("Bengaluru, Chennai, Coimbatore, remote") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(skills, { skills = it }, label = { Text("Skills (optional)") }, placeholder = { Text("SQL, Python, Spark, Azure") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(years, { years = it.filter { c -> c.isDigit() }.take(2) }, label = { Text("Most years of experience asked") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallButton(if (old == null) "Start watching" else "Save") {
                val r = Jobs.items(roles)
                if (r.isEmpty()) note = "Type at least one role."
                else onSave(
                    Jobs.Profile(
                        roles = r, places = Jobs.items(places).map { Jobs.place(it) }.distinct(), skills = Jobs.items(skills), kind = kind,
                        days = old?.days ?: 14, maxYears = (years.toIntOrNull() ?: 1).coerceIn(0, 10), avoid = old?.avoid.orEmpty(),
                    ),
                )
            }
            if (onCancel != null) { Spacer(Modifier.width(8.dp)); SmallButton("Cancel", filled = false) { onCancel() } }
            Spacer(Modifier.width(10.dp))
            Text(note.ifBlank { "No AI request." }, style = MaterialTheme.typography.labelSmall, color = if (note.isBlank()) cs.onSurfaceVariant else Ink.red)
        }
    }
}

@Composable
private fun CompaniesCard(store: JobStore) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    Card {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("Companies watched (${store.companies.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelMedium, color = cs.primary)
        }
        if (!open) return@Card
        Text("Their own job boards, read directly. Add any company by name or by its careers link (Greenhouse, Lever, Ashby or SmartRecruiters).", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        store.companies.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium)
                    Text(store.status[c.key] ?: (if (c.ats.isBlank()) "looked for by name at the next check" else "not read yet"), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                Text("Remove", style = MaterialTheme.typography.labelMedium, color = Ink.red, modifier = Modifier.clickable { JobStore.removeCompany(c.name) }.padding(8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(add, { add = it; note = "" }, label = { Text("Add a company") }, placeholder = { Text("Zepto, or https://jobs.lever.co/…") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallButton("Add") {
                val c = Jobs.companyFrom(add)
                note = when {
                    c == null -> "Type a company name or paste its careers link."
                    !JobStore.addCompany(c) -> "${c.name} is already in the list."
                    else -> { add = ""; if (c.ats.isBlank()) "Added. Its job board is looked for by name at the next check." else "Added." }
                }
            }
            Spacer(Modifier.width(8.dp))
            SmallButton("Starting list", filled = false) { JobStore.resetCompanies(); note = "Back to the starting list." }
        }
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
