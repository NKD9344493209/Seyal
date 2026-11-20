package com.seyal.app.agent

import android.content.Context
import com.seyal.app.data.Settings
import com.seyal.app.device.Notify
import com.seyal.app.tools.Common
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * What the job watcher keeps on the phone (seyal/jobs.json, so a backup carries it): the user's profile, the
 * companies watched, the last matches, which openings were already told, and when it last looked.
 * Opened by itself on first use; nothing at app start depends on it.
 */
object JobStore {
    private var file: File? = null
    private var opened = false

    @Volatile var profile: Jobs.Profile? = null; private set
    @Volatile var on: Boolean = true; private set
    @Volatile var companies: List<Jobs.Company> = Jobs.DEFAULT_COMPANIES; private set
    /** Per company key: "ok 12", "failed: HTTP 404", "no board found", with when. */
    @Volatile var status: Map<String, String> = emptyMap(); private set
    @Volatile var matches: List<Jobs.Match> = emptyList(); private set
    @Volatile var newIds: Set<String> = emptySet(); private set
    @Volatile var news: List<Aware.Item> = emptyList(); private set
    @Volatile var checkedAt: Long = 0; private set
    @Volatile var triedAt: Long = 0; private set
    @Volatile var looked: Int = 0; private set
    @Volatile var aside: Map<String, Int> = emptyMap(); private set
    @Volatile var failed: Int = 0; private set
    /** Notifications sent: the day (yyyy-mm-dd) and how many that day. */
    @Volatile var toldDay: String = ""; private set
    @Volatile var toldCount: Int = 0; private set
    private val seen = LinkedHashSet<String>()
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version

    @Synchronized fun init(dir: File) {
        file = File(dir, "seyal/jobs.json"); opened = true
        profile = null; on = true; companies = Jobs.DEFAULT_COMPANIES; status = emptyMap(); matches = emptyList(); newIds = emptySet(); news = emptyList()
        checkedAt = 0; triedAt = 0; looked = 0; aside = emptyMap(); failed = 0; toldDay = ""; toldCount = 0; seen.clear()
        runCatching {
            val o = JSONObject(file!!.readText())
            profile = Jobs.Profile.from(o.optJSONObject("profile"))
            on = o.optBoolean("on", true)
            o.optJSONArray("companies")?.let { a -> companies = (0 until a.length()).mapNotNull { Jobs.Company.from(a.optJSONObject(it)) } }
            o.optJSONObject("status")?.let { s -> status = s.keys().asSequence().associateWith { s.optString(it) } }
            o.optJSONArray("matches")?.let { a -> matches = (0 until a.length()).mapNotNull { Jobs.Match.from(a.optJSONObject(it)) } }
            o.optJSONArray("new")?.let { a -> newIds = (0 until a.length()).map { a.optString(it) }.toSet() }
            o.optJSONArray("news")?.let { a -> news = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { x -> Aware.Item(x.optString("t"), x.optString("s"), x.optString("u"), x.optLong("at"), "jobs", x.optString("topic")) } } }
            checkedAt = o.optLong("checked_at"); triedAt = o.optLong("tried_at"); looked = o.optInt("looked"); failed = o.optInt("failed")
            o.optJSONObject("aside")?.let { s -> aside = s.keys().asSequence().associateWith { s.optInt(it) } }
            toldDay = o.optString("told_day"); toldCount = o.optInt("told_count")
            o.optJSONArray("seen")?.let { a -> (0 until a.length()).forEach { seen += a.optString(it) } }
        }
        _version.value++
    }

    /** Opens the store the first time it is needed (in tests, init is called with a folder). */
    private fun open() {
        if (opened) return
        synchronized(this) { if (!opened) runCatching { init(Agent.app.filesDir) }.onFailure { opened = true } }
    }

    private fun save() {
        runCatching {
            val f = file ?: return
            f.parentFile?.mkdirs()
            val o = JSONObject()
                .put("profile", profile?.json()).put("on", on)
                .put("companies", JSONArray().apply { companies.forEach { put(it.json()) } })
                .put("status", JSONObject(status))
                .put("matches", JSONArray().apply { matches.take(60).forEach { put(it.json()) } })
                .put("new", JSONArray(newIds.toList()))
                .put("news", JSONArray().apply { news.forEach { put(JSONObject().put("t", it.title).put("s", it.source).put("u", it.url).put("at", it.at).put("topic", it.topic)) } })
                .put("checked_at", checkedAt).put("tried_at", triedAt).put("looked", looked).put("failed", failed)
                .put("aside", JSONObject(aside)).put("told_day", toldDay).put("told_count", toldCount)
                .put("seen", JSONArray(seen.toList().takeLast(4000)))
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        _version.value++
    }

    @Synchronized fun get(): JobStore { open(); return this }

    @Synchronized fun setProfile(p: Jobs.Profile) { open(); profile = p; on = true; matches = emptyList(); newIds = emptySet(); checkedAt = 0; save() }
    @Synchronized fun setOn(v: Boolean) { open(); on = v; save() }
    @Synchronized fun addCompany(c: Jobs.Company): Boolean {
        open()
        if (companies.any { it.key == c.key || it.name.equals(c.name, true) }) return false
        companies = companies + c; save(); return true
    }
    @Synchronized fun removeCompany(name: String): Jobs.Company? {
        open()
        val gone = companies.firstOrNull { it.name.equals(name.trim(), true) || it.token.equals(name.trim(), true) } ?: return null
        companies = companies - gone; save(); return gone
    }
    @Synchronized fun resetCompanies() { open(); companies = Jobs.DEFAULT_COMPANIES; status = emptyMap(); save() }
    /** A company found under its name on one of the services. */
    @Synchronized fun found(old: Jobs.Company, ats: String) { open(); companies = companies.map { if (it === old || it.key == old.key) Jobs.Company(old.name, ats, old.token) else it }; save() }
    @Synchronized fun tried(at: Long) { open(); triedAt = at; save() }

    @Synchronized fun checked(ranked: Jobs.Ranked, fresh: List<Jobs.Match>, st: Map<String, String>, failedCount: Int, newsItems: List<Aware.Item>?, at: Long) {
        open()
        matches = ranked.matches; looked = ranked.looked; aside = ranked.aside; status = status + st; failed = failedCount; checkedAt = at
        newIds = (newIds.filter { id -> ranked.matches.any { it.job.id == id } } + fresh.map { it.job.id }).toSet()
        seen += ranked.matches.map { it.job.id }
        if (newsItems != null) news = newsItems
        save()
    }

    /** The user has looked at the matches: nothing is "new" any more. */
    @Synchronized fun viewed() { open(); if (newIds.isNotEmpty()) { newIds = emptySet(); save() } }

    @Synchronized fun told(day: String) { open(); if (toldDay != day) { toldDay = day; toldCount = 0 }; toldCount++; save() }

    @Synchronized fun seenIds(): Set<String> { open(); return seen.toSet() }
}

/**
 * The job watcher at work: reads every watched company's job board, keeps what fits, and tells the user about new
 * openings. In the background it looks about every [EVERY_MS] between 7 AM and 10 PM; "Check now" looks at once.
 * It runs on its own (nothing else waits for it), one check at a time.
 */
object JobRun {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val one = Mutex()

    const val EVERY_MS = 6 * 3_600_000L
    const val RETRY_MS = 30 * 60_000L
    /** At most this many notifications a day. */
    const val TELL_PER_DAY = 4

    class Checked(val ranked: Jobs.Ranked?, val fresh: List<Jobs.Match>, val companies: Int, val failed: Int, val problem: String = "")

    /** Whether a background check is due at [now]: watching, every [EVERY_MS], in the day, not straight after a failed try. */
    fun due(now: Long, on: Boolean, hasProfile: Boolean, checkedAt: Long, triedAt: Long, zone: ZoneId): Boolean {
        if (!on || !hasProfile) return false
        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        if (hour < 7 || hour >= 22) return false
        return now - checkedAt >= EVERY_MS && now - triedAt >= RETRY_MS
    }

    /** When the background should next wake for the watcher (null when it is off). */
    fun nextAt(now: Long, on: Boolean, hasProfile: Boolean, checkedAt: Long, triedAt: Long, zone: ZoneId): Long? {
        if (!on || !hasProfile) return null
        var at = maxOf(checkedAt + EVERY_MS, triedAt + RETRY_MS, now + 60_000L)
        val z = Instant.ofEpochMilli(at).atZone(zone)
        if (z.hour < 7) at = z.toLocalDate().atTime(7, 5).atZone(zone).toInstant().toEpochMilli()
        else if (z.hour >= 22) at = z.toLocalDate().plusDays(1).atTime(7, 5).atZone(zone).toInstant().toEpochMilli()
        return at
    }

    fun next(now: Long): Long? {
        val s = JobStore.get()
        return nextAt(now, s.on, s.profile != null, s.checkedAt, s.triedAt, ZoneId.systemDefault())
    }

    /** One company's board: its openings, or why it could not be read. A company known only by name is looked for on each service. */
    private suspend fun board(c: Jobs.Company): Pair<List<Jobs.Job>?, String> {
        if (c.ats.isNotBlank()) {
            val url = Jobs.api(c) ?: return null to "unknown service"
            return runCatching { Jobs.read(c, Common.http(url, "application/json", 15_000)) to "" }.getOrElse { null to (it.message ?: "could not read").take(60) }
        }
        for (ats in Jobs.ATS) {
            val url = Jobs.api(c, ats) ?: continue
            val body = runCatching { Common.http(url, "application/json", 12_000) }.getOrNull() ?: continue
            if (!Jobs.isBoard(ats, body)) continue
            val jobs = Jobs.read(c, body, ats)
            // An empty board under a guessed name may be someone else's: only a board with openings counts as found.
            if (jobs.isEmpty()) continue
            JobStore.found(c, ats)
            return jobs to ""
        }
        return null to "no job board found under this name: add its careers link instead"
    }

    /** Looks at every watched board now. */
    suspend fun check(): Checked = one.withLock {
        val s = JobStore.get()
        val p = s.profile ?: return@withLock Checked(null, emptyList(), 0, 0, Jobs.NO_PROFILE)
        val now = System.currentTimeMillis()
        JobStore.tried(now)
        val list = s.companies
        val gate = Semaphore(6)
        val got = coroutineScope {
            list.map { c -> async(Dispatchers.IO) { gate.withPermit { c to board(c) } } }.awaitAll()
        }
        val jobs = got.flatMap { it.second.first.orEmpty() }
        val failed = got.count { it.second.first == null }
        val status = got.associate { (c, r) -> c.key to (if (r.first != null) "ok ${r.first!!.size} open" else "failed: ${r.second}") }
        if (failed == list.size && list.isNotEmpty()) {
            JobStore.checked(Jobs.Ranked(s.matches, s.looked, s.aside), emptyList(), status, failed, null, s.checkedAt)
            return@withLock Checked(null, emptyList(), list.size, failed, "Could not reach any job board. Check the internet and try again.")
        }
        val ranked = Jobs.rank(jobs, p, now)
        val fresh = Jobs.fresh(ranked.matches, JobStore.seenIds())
        // Hiring drives in the news: a few lines, read with the same reader as the Dirghadarshi brief. Optional.
        val news = runCatching {
            val items = Jobs.newsQueries(p).flatMap { q -> runCatching { Aware.parse(Common.http(q, "application/rss+xml,application/xml,*/*", 10_000), "jobs", p.roles.first()) }.getOrDefault(emptyList()) }
            items.distinctBy { it.title.lowercase() }.sortedByDescending { it.at }.take(8)
        }.getOrNull()
        JobStore.checked(ranked, fresh, status, failed, news, now)
        Checked(ranked, fresh, list.size, failed)
    }

    /** The matches as chat text (checks first when the last look is old). */
    suspend fun list(): String {
        val s = JobStore.get()
        if (s.profile == null) return Jobs.NO_PROFILE
        if (System.currentTimeMillis() - s.checkedAt > EVERY_MS) runCatching { check() }
        val t = JobStore.get()
        return Jobs.say(t.profile, t.matches, t.newIds, t.checkedAt, t.companies.size, t.failed)
    }

    /** From the background wake-up: a check when one is due, on its own (it never holds up anything else). */
    fun tick(ctx: Context) {
        val s = JobStore.get()
        val now = System.currentTimeMillis()
        if (Settings.paused || !due(now, s.on, s.profile != null, s.checkedAt, s.triedAt, ZoneId.systemDefault())) return
        val app = ctx.applicationContext
        scope.launch {
            val wake = runCatching {
                (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "seyal:jobs").apply { acquire(90_000) }
            }.getOrNull()
            try {
                val c = runCatching { check() }.getOrNull() ?: return@launch
                tell(app, c.fresh)
            } finally {
                runCatching { Scheduler.syncBackground(app) }
                runCatching { if (wake?.isHeld == true) wake.release() }
            }
        }
    }

    /** A notification for new matches, a few a day at most. */
    fun tell(ctx: Context, fresh: List<Jobs.Match>) {
        if (fresh.isEmpty()) return
        val day = java.time.LocalDate.now().toString()
        val s = JobStore.get()
        if (s.toldDay == day && s.toldCount >= TELL_PER_DAY) return
        val (title, body) = Jobs.notice(fresh)
        runCatching { Notify.foresight(ctx, title, body, tab = "jobs") }
        JobStore.told(day)
    }
}
