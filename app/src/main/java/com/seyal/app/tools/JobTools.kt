package com.seyal.app.tools

import com.seyal.app.agent.Agent
import com.seyal.app.agent.JobRun
import com.seyal.app.agent.JobStore
import com.seyal.app.agent.Jobs
import com.seyal.app.agent.Tool
import com.seyal.app.agent.ToolResult
import com.seyal.app.agent.schema
import org.json.JSONArray
import org.json.JSONObject

/**
 * The internship and job watcher from the chat: "watch internships for data engineer in Bengaluru or remote",
 * "jobs", "check jobs now", "job watch off", "watch company Zepto". It reads companies' own job boards only and never
 * applies for anything: the user opens a posting and applies there.
 */
object JobTools {
    private fun out(say: String, extra: JSONObject.() -> Unit = {}): ToolResult =
        ToolResult.Done(JSONObject().put("ok", true).put("say", say).apply(extra), say.lineSequence().firstOrNull().orEmpty().replace("**", "").take(90))

    private fun list(a: JSONObject, key: String): List<String>? = a.optString(key).takeIf { it.isNotBlank() }?.let { Jobs.items(it) }

    private fun matchesJson(): JSONArray = JSONArray().apply {
        JobStore.get().matches.take(10).forEach { m -> put(JSONObject().put("title", m.job.title).put("company", m.job.company).put("why", m.why.joinToString(" · ")).put("url", m.job.url)) }
    }

    val all: List<Tool> by lazy {
        listOf(
            Tool(
                "job_watch", "jobs",
                "The internship and job watcher: what to watch for. It reads companies' own job boards (Greenhouse, Lever, Ashby, SmartRecruiters) through the day and tells the user about new openings that fit. Use only when the user asks to watch, see or check job or internship openings for themselves; not for news or trends about jobs (foresight or web_search do that), and not for a message, mail or reminder that mentions a job. " +
                    "action: set (roles required; places, skills, kind, days, max_years, avoid optional) | show | on | off | add_company | remove_company | companies | reset_companies. " +
                    "kind: INTERN (internships only) | JOB (fresher jobs only) | BOTH. Places in the user's words (\"Bengaluru\", \"Chennai\", \"remote\"); leave empty for anywhere in India. " +
                    "company: a company name (\"Zepto\") or its careers link (\"https://jobs.lever.co/cred\"). Give the user the `say` text as it is. It never applies for a job.",
                schema {
                    str("action", "set | show | on | off | add_company | remove_company | companies | reset_companies", required = true)
                    str("roles", "For set: the roles, comma-separated, e.g. \"data engineer, data analyst\"")
                    str("places", "For set: cities and/or remote, comma-separated; empty = anywhere in India")
                    str("skills", "For set, optional: skills that make a match stronger, e.g. \"SQL, Python, Spark\"")
                    str("kind", "For set: INTERN | JOB | BOTH")
                    int("days", "For set, optional: only openings posted in the last N days (default 14)")
                    int("max_years", "For set, optional: the most years of experience an opening may ask for (default 1)")
                    str("avoid", "For set, optional: words that rule an opening out, comma-separated")
                    str("company", "For add_company/remove_company: a name or a careers link")
                },
            ) { a, _ ->
                val s = JobStore.get()
                when (a.optString("action").lowercase().trim()) {
                    "set" -> {
                        val old = s.profile
                        val roles = list(a, "roles") ?: old?.roles
                        if (roles.isNullOrEmpty()) return@Tool ToolResult.Failed("Give the roles to watch, e.g. roles: \"data engineer, data analyst\"")
                        val p = Jobs.Profile(
                            roles = roles,
                            places = (list(a, "places") ?: old?.places.orEmpty()).map { Jobs.place(it) }.distinct(),
                            skills = list(a, "skills") ?: old?.skills.orEmpty(),
                            kind = a.optString("kind").takeIf { it.isNotBlank() }?.let { Jobs.Kind.of(it) } ?: old?.kind ?: Jobs.Kind.BOTH,
                            days = a.optInt("days", old?.days ?: 14).coerceIn(1, 60),
                            maxYears = a.optInt("max_years", old?.maxYears ?: 1).coerceIn(0, 10),
                            avoid = list(a, "avoid") ?: old?.avoid.orEmpty(),
                        )
                        JobStore.setProfile(p)
                        runCatching { com.seyal.app.agent.Scheduler.syncBackground(Agent.app) }
                        val c = runCatching { JobRun.check() }.getOrNull()
                        val t = JobStore.get()
                        val say = "Watching now: " + p.said() + ".\n\n" + (c?.problem?.takeIf { it.isNotBlank() } ?: Jobs.say(t.profile, t.matches, t.newIds, t.checkedAt, t.companies.size, t.failed).substringBefore("\n\nWatching:")) +
                            "\n\nI check again about every 6 hours between 7 AM and 10 PM and send a notification when something new fits. Everything is also on Dirghadarshi → Jobs."
                        out(say) { put("matches", matchesJson()) }
                    }
                    "show" -> out(s.profile?.let { "Watching: " + it.said() + ". " + (if (s.on) "Alerts on." else "Alerts off (say \"job watch on\").") + " ${s.companies.size} companies' job boards." } ?: Jobs.NO_PROFILE)
                    "on", "off" -> {
                        val on = a.optString("action").equals("on", true)
                        JobStore.setOn(on)
                        runCatching { com.seyal.app.agent.Scheduler.syncBackground(Agent.app) }
                        out(if (!on) "The job watcher is off. Nothing is checked until you say \"job watch on\"." else if (s.profile == null) "The job watcher is on. " + Jobs.NO_PROFILE else "The job watcher is on: " + s.profile!!.said() + ".")
                    }
                    "add_company" -> {
                        val c = Jobs.companyFrom(a.optString("company")) ?: return@Tool ToolResult.Failed("Give a company name or its careers link")
                        if (!JobStore.addCompany(c)) out("${c.name} is already watched.")
                        else out("Added ${c.name}." + (if (c.ats.isBlank()) " I will look for its job board by name at the next check; if it is not found, send me its careers link." else ""))
                    }
                    "remove_company" -> JobStore.removeCompany(a.optString("company"))?.let { out("Stopped watching ${it.name}.") } ?: ToolResult.Failed("That company is not in the list. Say \"which companies do you watch\" to see it.")
                    "companies" -> out("Watching the job boards of ${s.companies.size} companies: " + s.companies.joinToString(", ") { it.name } + ".")
                    "reset_companies" -> { JobStore.resetCompanies(); out("Back to the starting list of ${Jobs.DEFAULT_COMPANIES.size} companies.") }
                    else -> ToolResult.Failed("action must be set, show, on, off, add_company, remove_company, companies or reset_companies")
                }
            },
            Tool(
                "jobs_list", "jobs",
                "The openings that fit the user, best first, each with why it fits and its link (from the job watcher; it checks first when the last look is old). Only when the user asks about openings for themselves, not job news. Give the user the `say` text as it is.",
                schema { },
            ) { _, _ ->
                val say = JobRun.list()
                JobStore.viewed()
                out(say) { put("matches", matchesJson()) }
            },
            Tool(
                "jobs_check", "jobs",
                "Checks every watched company's job board now (takes up to half a minute), then lists the openings that fit. Give the user the `say` text as it is.",
                schema { },
            ) { _, _ ->
                val c = JobRun.check()
                val t = JobStore.get()
                if (c.problem.isNotBlank() && t.profile == null) return@Tool out(c.problem)
                val say = (if (c.problem.isNotBlank()) c.problem + "\n\n" else "") +
                    (if (c.fresh.isNotEmpty()) "${c.fresh.size} new since the last look.\n\n" else "") +
                    Jobs.say(t.profile, t.matches, t.newIds, t.checkedAt, t.companies.size, t.failed)
                JobStore.viewed()
                out(say) { put("matches", matchesJson()) }
            },
        )
    }
}
