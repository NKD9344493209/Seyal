package com.seyal.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The internship and job watcher. Asked for by Naveen on 20 Nov 2026: "Build a smart internship and job watcher.
 * But don't ever try to change the other functionalities."
 *
 * Where the openings come from: companies' OWN public job boards, the same feeds their careers pages are built on
 * (Greenhouse, Lever, Ashby, SmartRecruiters). No login, no scraping of job portals, no aggregator sites (many of
 * those carry made-up "internships"). Each opening is checked against what the user wants (roles, places,
 * internship or job, how fresh, how much experience it asks for, money-asking scams) and only the ones that fit are
 * kept, best first, each with the reasons it fits. New ones are told by a notification.
 *
 * Pure Kotlin, no Android: everything here is unit-tested. The fetching, the store and the timing are in JobRun.
 */
object Jobs {
    private val I = RegexOption.IGNORE_CASE

    // ---------------------------------------------------------------------------------------------------------------
    // What the user wants

    enum class Kind(val word: String) {
        INTERN("internships"), JOB("jobs for freshers"), BOTH("internships and fresher jobs");

        companion object { fun of(s: String?): Kind = values().firstOrNull { it.name.equals(s?.trim(), true) } ?: BOTH }
    }

    class Profile(
        val roles: List<String>,
        val places: List<String> = emptyList(),
        val skills: List<String> = emptyList(),
        val kind: Kind = Kind.BOTH,
        /** Openings posted within this many days. */
        val days: Int = 14,
        /** The most years of experience an opening may ask for. */
        val maxYears: Int = 1,
        /** Words that rule an opening out ("sales", "night shift"). */
        val avoid: List<String> = emptyList(),
    ) {
        fun json(): JSONObject = JSONObject().put("roles", JSONArray(roles)).put("places", JSONArray(places)).put("skills", JSONArray(skills))
            .put("kind", kind.name).put("days", days).put("max_years", maxYears).put("avoid", JSONArray(avoid))

        fun said(): String = buildString {
            append(kind.word.replaceFirstChar { it.uppercase() }).append(" for ").append(roles.joinToString(", "))
            append(if (places.isEmpty()) " anywhere in India" else " in " + places.joinToString(", ") { placeName(it) })
            if (skills.isNotEmpty()) append(" · skills: ").append(skills.joinToString(", "))
            append(" · posted in the last $days days · asking at most $maxYears year${if (maxYears == 1) "" else "s"} of experience")
            if (avoid.isNotEmpty()) append(" · never: ").append(avoid.joinToString(", "))
        }

        companion object {
            fun from(o: JSONObject?): Profile? {
                if (o == null) return null
                fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() } } ?: emptyList()
                val roles = list("roles")
                if (roles.isEmpty()) return null
                return Profile(roles, list("places"), list("skills"), Kind.of(o.optString("kind")), o.optInt("days", 14).coerceIn(1, 60), o.optInt("max_years", 1).coerceIn(0, 10), list("avoid"))
            }
        }
    }

    /** Splits what was typed into items: commas, semicolons, "and", "or", "/" or new lines between them. */
    fun items(s: String): List<String> =
        s.split(Regex("\\s*(?:,|;|/|\\n|\\band\\b|\\bor\\b|&)\\s*", I)).map { it.trim().trim('.', '-', ':') }.filter { it.length >= 2 }.distinctBy { it.lowercase() }.take(12)

    // ---------------------------------------------------------------------------------------------------------------
    // Places

    /** Each place by its usual name, and every way it is written. */
    private val placeAliases: Map<String, List<String>> = linkedMapOf(
        "bengaluru" to listOf("bengaluru", "bangalore", "blr"),
        "chennai" to listOf("chennai", "madras"),
        "coimbatore" to listOf("coimbatore", "kovai"),
        "hyderabad" to listOf("hyderabad", "secunderabad"),
        "pune" to listOf("pune"),
        "mumbai" to listOf("mumbai", "bombay", "navi mumbai", "thane"),
        "delhi ncr" to listOf("delhi", "new delhi", "ncr", "gurugram", "gurgaon", "noida", "faridabad", "ghaziabad"),
        "kolkata" to listOf("kolkata", "calcutta"),
        "ahmedabad" to listOf("ahmedabad"),
        "kochi" to listOf("kochi", "cochin"),
        "trivandrum" to listOf("trivandrum", "thiruvananthapuram"),
        "mysuru" to listOf("mysuru", "mysore"),
        "madurai" to listOf("madurai"),
        "trichy" to listOf("trichy", "tiruchirappalli"),
        "remote" to listOf("remote", "work from home", "wfh", "anywhere in india", "anywhere"),
    )

    private val indiaPlace = Regex(
        "\\b(india|bengaluru|bangalore|chennai|madras|coimbatore|hyderabad|secunderabad|pune|mumbai|bombay|thane|delhi|ncr|gurugram|gurgaon|noida|faridabad|ghaziabad|kolkata|ahmedabad|" +
            "kochi|cochin|trivandrum|thiruvananthapuram|jaipur|indore|chandigarh|mysuru|mysore|madurai|tiruchirappalli|trichy|salem|vellore|visakhapatnam|vizag|bhubaneswar|nagpur|surat|lucknow|mangaluru|mangalore)\\b",
        I,
    )
    private val remoteWord = Regex("\\b(remote|work from home|wfh)\\b", I)

    /** The usual name of a place the user typed ("Bangalore" → "bengaluru"), or what they typed. */
    fun place(typed: String): String {
        val t = typed.trim().lowercase()
        return placeAliases.entries.firstOrNull { (_, all) -> t in all }?.key ?: t
    }

    /** Whether [typed] is a place this watcher knows (so "in Chennai" is a place and "in data science" is not). */
    fun knownPlace(typed: String): Boolean = placeAliases.values.any { typed.trim().lowercase() in it } || indiaPlace.matches(typed.trim())

    fun placeName(p: String): String = when (p) { "delhi ncr" -> "Delhi NCR"; "remote" -> "Remote"; else -> p.split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } } }

    private fun mentions(text: String, word: String): Boolean = Regex("\\b" + Regex.escape(word) + "\\b", I).containsMatchIn(text)

    // ---------------------------------------------------------------------------------------------------------------
    // Where openings come from

    /** A company's job board. [ats] is greenhouse, lever, lever_eu, ashby or smartrecruiters; empty = not found yet (it is looked for by name). */
    class Company(val name: String, val ats: String, val token: String) {
        fun json(): JSONObject = JSONObject().put("name", name).put("ats", ats).put("token", token)
        val key: String get() = (if (ats.isBlank()) "?" else ats) + ":" + token.lowercase()

        companion object {
            fun from(o: JSONObject?): Company? = o?.let { x -> x.optString("token").trim().takeIf { it.isNotBlank() }?.let { Company(x.optString("name").ifBlank { it }, x.optString("ats"), it) } }
        }
    }

    val ATS = listOf("greenhouse", "lever", "ashby", "smartrecruiters")

    /**
     * The companies watched to start with: Indian companies and companies with Indian offices whose job boards were
     * found on these four services on 20 Nov 2026 (each seen with an opening in Bengaluru, Chennai or India). The user
     * adds or removes companies by name or by a careers link.
     */
    val DEFAULT_COMPANIES: List<Company> = listOf(
        Company("Razorpay", "greenhouse", "razorpaysoftwareprivatelimited"),
        Company("Postman", "greenhouse", "postman"),
        Company("Stripe", "greenhouse", "stripe"),
        Company("Databricks", "greenhouse", "databricks"),
        Company("DevRev", "greenhouse", "devrev"),
        Company("HackerRank", "greenhouse", "hackerrank"),
        Company("Enterpret", "greenhouse", "enterpret"),
        Company("Karya", "greenhouse", "karya"),
        Company("DataHub (Acryl Data)", "greenhouse", "acryldata"),
        Company("CRED", "lever", "cred"),
        Company("Meesho", "lever", "meesho"),
        Company("Fam (FamPay)", "lever", "fampay"),
        Company("Acceldata", "lever", "acceldata"),
        Company("AiDash", "lever", "aidash"),
        Company("Fi (epiFi)", "lever", "epifi"),
        Company("Greenlight", "lever", "greenlight"),
        Company("Sprinto", "lever", "Sprinto"),
        Company("Freshworks", "smartrecruiters", "Freshworks"),
    )

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    /** The feed of a company's open jobs. */
    fun api(c: Company, ats: String = c.ats): String? = when (ats) {
        "greenhouse" -> "https://boards-api.greenhouse.io/v1/boards/${enc(c.token)}/jobs"
        "lever" -> "https://api.lever.co/v0/postings/${enc(c.token)}?mode=json"
        "lever_eu" -> "https://api.eu.lever.co/v0/postings/${enc(c.token)}?mode=json"
        "ashby" -> "https://api.ashbyhq.com/posting-api/job-board/${enc(c.token)}"
        "smartrecruiters" -> "https://api.smartrecruiters.com/v1/companies/${enc(c.token)}/postings?limit=100"
        else -> null
    }

    /** The company's careers page, for people. */
    fun board(c: Company): String = when (c.ats) {
        "greenhouse" -> "https://job-boards.greenhouse.io/${c.token}"
        "lever" -> "https://jobs.lever.co/${c.token}"
        "lever_eu" -> "https://jobs.eu.lever.co/${c.token}"
        "ashby" -> "https://jobs.ashbyhq.com/${c.token}"
        "smartrecruiters" -> "https://careers.smartrecruiters.com/${c.token}"
        else -> ""
    }

    private val boardLink = listOf(
        "greenhouse" to Regex("(?:boards|job-boards)\\.greenhouse\\.io/(?:embed/job_board\\?for=)?([A-Za-z0-9_-]+)", I),
        "greenhouse" to Regex("boards-api\\.greenhouse\\.io/v1/boards/([A-Za-z0-9_-]+)", I),
        "greenhouse" to Regex("greenhouse\\.io/[^ ]*[?&]for=([A-Za-z0-9_-]+)", I),
        "lever_eu" to Regex("(?:jobs|api)\\.eu\\.lever\\.co/(?:v0/postings/)?([A-Za-z0-9_.-]+)", I),
        "lever" to Regex("(?:jobs|api)\\.lever\\.co/(?:v0/postings/)?([A-Za-z0-9_.-]+)", I),
        "ashby" to Regex("(?:jobs\\.ashbyhq\\.com|api\\.ashbyhq\\.com/posting-api/job-board)/([A-Za-z0-9_.%-]+)", I),
        "smartrecruiters" to Regex("(?:careers|jobs)\\.smartrecruiters\\.com/([A-Za-z0-9_-]+)", I),
        "smartrecruiters" to Regex("api\\.smartrecruiters\\.com/v1/companies/([A-Za-z0-9_-]+)", I),
    )

    /**
     * A company from what the user gave: a careers link on one of the four services ("https://jobs.lever.co/cred"),
     * or a plain name ("Zepto"), which is then looked for on each service by its name.
     */
    fun companyFrom(said: String): Company? {
        val s = said.trim().trim('"', '\'', '<', '>')
        if (s.isBlank()) return null
        for ((ats, rx) in boardLink) {
            val m = rx.find(s) ?: continue
            val token = java.net.URLDecoder.decode(m.groupValues[1], "UTF-8").trim('.')
            if (token.isBlank() || token.equals("embed", true) || token.equals("v1", true)) continue
            return Company(token.replaceFirstChar { it.uppercase() }, ats, token)
        }
        if (s.contains("://") || s.length > 60) return null
        val token = s.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (token.length < 2) return null
        return Company(s, "", token)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // One opening

    class Job(
        /** Stable across checks: service, company, the posting's own id. */
        val id: String,
        val company: String,
        val title: String,
        val place: String,
        val remote: Boolean,
        val url: String,
        /** When it was posted, epoch ms; 0 = the board does not say. */
        val posted: Long,
        /** "Intern", "Full-time", … as the board labels it. */
        val type: String = "",
        /** The posting's text, when the board gives it (it may be empty). */
        val text: String = "",
        val country: String = "",
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("company", company).put("title", title).put("place", place).put("remote", remote).put("url", url)
            .put("posted", posted).put("type", type).put("country", country)

        companion object {
            fun from(o: JSONObject?): Job? = o?.takeIf { it.optString("id").isNotBlank() && it.optString("url").isNotBlank() }?.let { x ->
                Job(x.optString("id"), x.optString("company"), x.optString("title"), x.optString("place"), x.optBoolean("remote"), x.optString("url"), x.optLong("posted"), x.optString("type"), "", x.optString("country"))
            }
        }
    }

    /** An ISO time ("2026-11-18T10:00:00-05:00", "…Z", "2026-11-18") or epoch seconds or ms, as epoch ms; 0 when it cannot be read. */
    fun time(s: String?): Long {
        val t = s?.trim().orEmpty()
        if (t.isEmpty() || t == "null") return 0
        t.toLongOrNull()?.let { return if (it > 100_000_000_000L) it else it * 1000 }
        return runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(t).toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(t.take(19)).toInstant(ZoneOffset.UTC).toEpochMilli() }
            .recoverCatching { java.time.LocalDate.parse(t.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun clean(s: String?): String = s.orEmpty().replace(Regex("\\s+"), " ").trim()

    /** Whether [body] is what the service sends for a real job board (used when a company is looked for by name). */
    fun isBoard(ats: String, body: String): Boolean = runCatching {
        val t = body.trimStart()
        when (ats) {
            "lever", "lever_eu" -> t.startsWith("[")
            "greenhouse", "ashby" -> t.startsWith("{") && JSONObject(t).has("jobs")
            "smartrecruiters" -> t.startsWith("{") && JSONObject(t).has("content") && JSONObject(t).optInt("totalFound", -1) >= 0
            else -> false
        }
    }.getOrDefault(false)

    /** Every opening on one company's board, from the service's answer [body]. Nothing on any fault. */
    fun read(c: Company, body: String, ats: String = c.ats): List<Job> = runCatching {
        when (ats) {
            "greenhouse" -> greenhouse(c, JSONObject(body))
            "lever", "lever_eu" -> lever(c, JSONArray(body))
            "ashby" -> ashby(c, JSONObject(body))
            "smartrecruiters" -> smartRecruiters(c, JSONObject(body))
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun greenhouse(c: Company, o: JSONObject): List<Job> {
        val a = o.optJSONArray("jobs") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val j = a.optJSONObject(i) ?: return@mapNotNull null
            val title = clean(j.optString("title")); val url = j.optString("absolute_url")
            if (title.isBlank() || url.isBlank()) return@mapNotNull null
            val place = clean(j.optJSONObject("location")?.optString("name"))
            val posted = time(j.optString("first_published").takeIf { it.isNotBlank() && it != "null" } ?: j.optString("updated_at"))
            Job("greenhouse:${c.token}:${j.opt("id")}", c.name, title, place, remoteWord.containsMatchIn(place), url, posted)
        }
    }

    private fun lever(c: Company, a: JSONArray): List<Job> = (0 until a.length()).mapNotNull { i ->
        val j = a.optJSONObject(i) ?: return@mapNotNull null
        val title = clean(j.optString("text")); val url = j.optString("hostedUrl")
        if (title.isBlank() || url.isBlank()) return@mapNotNull null
        val cat = j.optJSONObject("categories")
        val all = cat?.optJSONArray("allLocations")?.let { l -> (0 until l.length()).map { l.optString(it) } }.orEmpty()
        val place = clean((listOf(cat?.optString("location").orEmpty()) + all).filter { it.isNotBlank() }.distinct().joinToString(" / "))
        val text = clean(j.optString("descriptionPlain") + " " + j.optString("additionalPlain")).take(6000)
        val posted = j.optLong("createdAt").takeIf { it > 0 } ?: time(j.optString("createdAt"))
        Job("lever:${c.token}:${j.optString("id")}", c.name, title, place, j.optString("workplaceType").equals("remote", true) || remoteWord.containsMatchIn(place), url, posted, clean(cat?.optString("commitment")), text, j.optString("country"))
    }

    private fun ashby(c: Company, o: JSONObject): List<Job> {
        val a = o.optJSONArray("jobs") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val j = a.optJSONObject(i) ?: return@mapNotNull null
            if (j.has("isListed") && !j.optBoolean("isListed", true)) return@mapNotNull null
            val title = clean(j.optString("title")); val url = j.optString("jobUrl").ifBlank { j.optString("applyUrl") }
            if (title.isBlank() || url.isBlank()) return@mapNotNull null
            val more = j.optJSONArray("secondaryLocations")?.let { l -> (0 until l.length()).mapNotNull { l.optJSONObject(it)?.optString("location") } }.orEmpty()
            val place = clean((listOf(j.optString("location")) + more).filter { it.isNotBlank() }.distinct().joinToString(" / "))
            val remote = j.optBoolean("isRemote") || j.optString("workplaceType").equals("remote", true) || remoteWord.containsMatchIn(place)
            Job("ashby:${c.token}:${j.optString("id")}", c.name, title, place, remote, url, time(j.optString("publishedAt")), clean(j.optString("employmentType")), clean(j.optString("descriptionPlain")).take(6000))
        }
    }

    private fun smartRecruiters(c: Company, o: JSONObject): List<Job> {
        val a = o.optJSONArray("content") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val j = a.optJSONObject(i) ?: return@mapNotNull null
            val title = clean(j.optString("name")); val id = j.optString("id")
            if (title.isBlank() || id.isBlank()) return@mapNotNull null
            val loc = j.optJSONObject("location")
            val place = clean(loc?.optString("fullLocation")?.takeIf { it.isNotBlank() } ?: listOfNotNull(loc?.optString("city"), loc?.optString("country")).filter { it.isNotBlank() }.joinToString(", "))
            val who = j.optJSONObject("company")?.optString("identifier")?.takeIf { it.isNotBlank() } ?: c.token
            val type = listOfNotNull(j.optJSONObject("typeOfEmployment")?.optString("label"), j.optJSONObject("experienceLevel")?.optString("label")).filter { it.isNotBlank() }.joinToString(" · ")
            Job("smartrecruiters:${c.token}:$id", c.name, title, place, loc?.optBoolean("remote") == true || remoteWord.containsMatchIn(place), "https://jobs.smartrecruiters.com/$who/$id", time(j.optString("releasedDate")), type, "", loc?.optString("country").orEmpty())
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Does it fit?

    class Match(val job: Job, val score: Int, val why: List<String>, val intern: Boolean) {
        fun json(): JSONObject = job.json().put("score", score).put("why", JSONArray(why)).put("intern", intern)

        companion object {
            fun from(o: JSONObject?): Match? = Job.from(o)?.let { j ->
                Match(j, o!!.optInt("score"), o.optJSONArray("why")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(), o.optBoolean("intern"))
            }
        }
    }

    /** Why openings were set aside, counted, so the user can see the watcher at work and trust it. */
    class Ranked(val matches: List<Match>, val looked: Int, val aside: Map<String, Int>)

    private val internWords = Regex("\\b(intern|interns|internship|internships|trainee|trainees|apprentice|apprenticeship|co-?op|student researcher|summer analyst)\\b", I)
    private val seniorWords = Regex(
        "\\b(senior|sr|lead|leader|principal|staff|manager|director|head|vp|vice president|chief|architect|ii|iii|iv|l[3-9]|sde ?(?:2|3)|sde ?ii|sde ?iii|mts ?(?:2|3))\\b",
        I,
    )
    private val entryWords = Regex("\\b(fresher|freshers|entry[ -]level|graduate|graduates|new grad|campus|junior|jr|associate|trainee|engineer i|analyst i|developer i|level 1|sde ?1|sde ?i)\\b", I)
    private val yearsAsked = Regex("(\\d{1,2})\\s*\\+?\\s*(?:(?:-|to|–)\\s*\\d{1,2}\\s*\\+?\\s*)?(?:years?|yrs?)'?(?:\\s+of)?(?:\\s+(?:relevant|professional|industry|hands-on|work|working|proven))?\\s+(?:experience|exp)\\b", I)
    /** Real employers never ask an applicant for money. */
    private val asksMoney = Regex(
        "\\b(registration fee|registration charges?|security deposit|refundable deposit|training fee|processing fee|joining fee|kit fee|pay to (?:apply|join)|pay (?:rs\\.?|inr) ?\\d|pay ₹ ?\\d)|₹ ?\\d[\\d,]* (?:registration|deposit|fee)",
        I,
    )
    private val stop = setOf("and", "or", "the", "a", "an", "of", "for", "in", "at", "to", "role", "roles", "job", "jobs", "intern", "interns", "internship", "internships", "fresher", "freshers", "position", "opening", "openings", "-", "&")

    /** One word as a stem, so "engineer", "engineering" and "engineers" are one word. */
    private fun stem(w: String): String {
        val x = w.lowercase().trim('.', ',', '(', ')', '/', '-', ':', '|', '&')
        return when {
            x.startsWith("engineer") -> "engineer"
            x.startsWith("analy") -> "analy"
            x.startsWith("develop") -> "develop"
            x.startsWith("scien") -> "scien"
            x.startsWith("program") -> "program"
            x.startsWith("design") -> "design"
            x.startsWith("market") -> "market"
            x.startsWith("manag") -> "manag"
            x.startsWith("admin") -> "admin"
            x.startsWith("test") || x == "qa" -> "test"
            x.startsWith("secur") -> "secur"
            x.startsWith("product") -> "product"
            x.length > 4 && x.endsWith("s") -> x.dropLast(1)
            else -> x
        }
    }

    /** Short forms said as their words: "ML" is machine learning, "SDE" a software engineer, "BI" business intelligence. */
    private fun spelled(s: String): String = s.lowercase()
        .replace(Regex("\\bml\\b"), "machine learning").replace(Regex("\\b(?:sde|swe)\\b"), "software engineer").replace(Regex("\\bsoftware development engineer\\b"), "software engineer")
        .replace(Regex("\\bbi\\b"), "business intelligence").replace(Regex("\\bdba\\b"), "database administrator").replace(Regex("\\bai/ml\\b"), "ai machine learning")
        .replace(Regex("\\bfront[ -]end\\b"), "frontend").replace(Regex("\\bback[ -]end\\b"), "backend").replace(Regex("\\bfull[ -]stack\\b"), "fullstack")

    private fun words(s: String): List<String> = spelled(s).split(Regex("[^a-z0-9+#.]+")).map { stem(it) }.filter { it.isNotBlank() && it !in stop }

    /** How well [role] fits [title] (and the posting's [text]): 60 all its words in the title, 35 its main word and half, 25 all of them in the text, else 0. */
    fun roleFit(role: String, title: String, text: String = ""): Int {
        val want = words(role).distinct()
        if (want.isEmpty()) return 0
        val inTitle = words(title).toSet()
        if (want.all { it in inTitle }) return 60
        val hits = want.count { it in inTitle }
        if (want.size >= 2 && want.first() in inTitle && hits * 2 >= want.size) return 35
        if (text.isNotBlank()) { val inText = words(text).toSet() + inTitle; if (want.all { it in inText }) return 25 }
        return 0
    }

    fun isIntern(j: Job): Boolean = internWords.containsMatchIn(j.title) || internWords.containsMatchIn(j.type)

    /** The years of experience a posting asks for (the first it names), or null. */
    fun yearsAsked(text: String): Int? = yearsAsked.find(text)?.groupValues?.get(1)?.toIntOrNull()

    fun asksMoney(text: String): Boolean = asksMoney.containsMatchIn(text)

    private val placeFiller = Regex("\\b(india|in|remote|hybrid|on-?site|in-?office|office|any)\\b", I)

    /** "India", "India - Remote", "IN (Hybrid)": India, but no city named. */
    fun noCity(place: String): Boolean = placeFiller.replace(place, " ").none { it.isLetter() }

    fun inIndia(j: Job): Boolean = indiaPlace.containsMatchIn(j.place) || j.country.equals("IN", true) || j.country.equals("India", true)

    private const val DAY = 86_400_000L

    /** How old a posting is, in words. */
    fun age(posted: Long, now: Long): String {
        if (posted <= 0) return "date not given"
        val d = ((now - posted) / DAY).toInt()
        return when { d <= 0 -> "posted today"; d == 1 -> "posted yesterday"; else -> "posted $d days ago" }
    }

    /**
     * Whether [j] fits [p], and how well. Null (with the reason in [aside]) when it does not: wrong role, a senior
     * post, more experience than allowed, the wrong place, internship when a job was asked for (or the other way),
     * too old, or it asks for money.
     */
    fun fit(j: Job, p: Profile, now: Long, aside: MutableMap<String, Int> = HashMap()): Match? {
        fun no(why: String): Match? { aside[why] = (aside[why] ?: 0) + 1; return null }
        if (p.roles.isEmpty()) return no("no role set")
        val all = j.title + " " + j.type + " " + j.text
        if (p.avoid.any { mentions(j.title, it) }) return no("has a word you ruled out")
        if (asksMoney(all)) return no("asks for money")
        // Internship or job.
        val intern = isIntern(j)
        if (p.kind == Kind.INTERN && !intern) return no("not an internship")
        if (p.kind == Kind.JOB && intern) return no("an internship")
        // A senior post, or more experience than the user has.
        if (!intern && seniorWords.containsMatchIn(j.title)) return no("a senior post")
        val years = yearsAsked(j.text)
        if (!intern && years != null && years > p.maxYears) return no("asks for more experience")
        // Too old.
        if (j.posted > 0 && now - j.posted > p.days * DAY) return no("older than ${p.days} days")
        // The place.
        val city = p.places.filter { it != "remote" }.firstOrNull { pl -> (placeAliases[pl] ?: listOf(pl)).any { mentions(j.place, it) } }
        val remoteOk = j.remote && (inIndia(j) || Regex("^\\s*(remote|anywhere)\\s*$", I).matches(j.place) && mentions(j.text, "india"))
        val placeWhy: String = when {
            city != null -> placeName(city)
            "remote" in p.places && remoteOk -> "Remote"
            p.places.isEmpty() && (inIndia(j) || remoteOk) -> j.place.ifBlank { "India" }.take(40)
            p.places.isNotEmpty() && inIndia(j) && noCity(j.place) -> "India (no city given)"
            else -> return no("another place")
        }
        // The role.
        val fit = p.roles.maxOf { roleFit(it, j.title, j.text) }
        if (fit == 0) return no("another kind of work")
        val role = p.roles.maxByOrNull { roleFit(it, j.title, j.text) }.orEmpty()
        var score = fit
        val why = ArrayList<String>()
        why += if (fit >= 60) role else if (fit >= 35) "close to $role" else "$role in the description"
        why += if (intern) "internship" else if (entryWords.containsMatchIn(j.title + " " + j.type)) "entry level" else "job"
        why += placeWhy
        if (intern || entryWords.containsMatchIn(j.title + " " + j.type)) score += 10
        if (city != null) score += 10
        if (placeWhy.startsWith("India (")) score -= 5
        val skills = p.skills.filter { mentions(all, it) }
        if (skills.isNotEmpty()) { score += minOf(20, skills.size * 5); why += "skills: " + skills.take(4).joinToString(", ") }
        if (years != null && years <= p.maxYears) why += if (years == 0) "for freshers" else "asks $years year${if (years == 1) "" else "s"}"
        if (j.posted > 0) {
            val d = (now - j.posted) / DAY
            score += when { d <= 1 -> 15; d <= 3 -> 10; d <= 7 -> 5; else -> 0 }
        }
        why += age(j.posted, now)
        if (score < THRESHOLD) return no("too weak a fit")
        return Match(j, score, why, intern)
    }

    const val THRESHOLD = 45

    /** Every opening in [jobs] that fits [p], the same post on two boards (or twice on one) kept once, best first. */
    fun rank(jobs: List<Job>, p: Profile, now: Long): Ranked {
        val aside = LinkedHashMap<String, Int>()
        val fits = jobs.mapNotNull { fit(it, p, now, aside) }
        val seen = HashSet<String>()
        val out = fits.sortedWith(compareByDescending<Match> { it.score }.thenByDescending { it.job.posted }).filter { m ->
            val key = m.job.company.lowercase() + "|" + words(m.job.title).joinToString(" ") + "|" + m.job.place.lowercase().take(30)
            seen.add(key) || run { aside["listed twice"] = (aside["listed twice"] ?: 0) + 1; false }
        }
        return Ranked(out, jobs.size, aside)
    }

    /** The matches not told before. */
    fun fresh(matches: List<Match>, seen: Set<String>): List<Match> = matches.filter { it.job.id !in seen }

    // ---------------------------------------------------------------------------------------------------------------
    // What the user reads

    /** One match as a numbered chat line with its link. */
    fun line(n: Int, m: Match, new: Boolean): String =
        "$n. **${m.job.title}** · ${m.job.company}${if (new) " · NEW" else ""}\n   ${m.why.joinToString(" · ")}\n   [Open the posting](${m.job.url})"

    /** The matches as text for the chat. */
    fun say(p: Profile?, matches: List<Match>, newIds: Set<String>, checkedAt: Long, companies: Int, failed: Int, shown: Int = 10): String {
        if (p == null) return NO_PROFILE
        val head = when {
            checkedAt <= 0 -> "Not checked yet."
            matches.isEmpty() -> "No opening fits right now (checked ${companies - failed} of $companies companies' job boards). I keep checking through the day and tell you the moment one appears."
            else -> "**${matches.size} opening${if (matches.size == 1) "" else "s"} fit you** (from $companies companies' own job boards${if (failed > 0) "; $failed could not be read" else ""}). Best first:"
        }
        val body = matches.take(shown).mapIndexed { i, m -> line(i + 1, m, m.job.id in newIds) }.joinToString("\n")
        val more = if (matches.size > shown) "\n…and ${matches.size - shown} more on Dirghadarshi → Jobs." else ""
        return listOf(head, body + more, "Watching: " + p.said() + ".").filter { it.isNotBlank() }.joinToString("\n\n")
    }

    const val NO_PROFILE = "Tell me what to watch for, in one line. For example: \"watch internships for data engineer and data analyst in Bengaluru, Chennai or remote\". " +
        "Or open Dirghadarshi → Jobs and fill it in there."

    /** The notification for new matches: a title and up to five lines. */
    fun notice(new: List<Match>): Pair<String, String> {
        val interns = new.count { it.intern }
        val what = when { interns == new.size -> "internship"; interns == 0 -> "job"; else -> "opening" }
        val title = "${new.size} new $what${if (new.size == 1) "" else "s"} for you"
        val body = new.take(5).joinToString("\n") { m -> "• ${m.job.title} · ${m.job.company} · ${m.why.getOrNull(2).orEmpty()}" } +
            (if (new.size > 5) "\n…and ${new.size - 5} more" else "") + "\nTap to see them and apply early."
        return title to body
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fresh searches on the big job sites (opened for the user; nothing is read from them)

    class Link(val name: String, val note: String, val url: String)

    fun links(p: Profile): List<Link> {
        val role = p.roles.firstOrNull().orEmpty()
        val city = p.places.firstOrNull { it != "remote" }
        val where = city?.let { placeName(it) } ?: "India"
        val q = role + if (p.kind == Kind.INTERN) " intern" else ""
        val out = ArrayList<Link>()
        val level = when (p.kind) { Kind.INTERN -> "1"; Kind.JOB -> "2"; Kind.BOTH -> "1%2C2" }
        out += Link("LinkedIn", "posted in the last 24 hours", "https://www.linkedin.com/jobs/search/?keywords=${enc(role)}&location=${enc(where)}&f_TPR=r86400&f_E=$level")
        out += Link("Indeed", "posted in the last day", "https://in.indeed.com/jobs?q=${enc(q)}&l=${enc(where)}&fromage=1")
        val slug = role.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val naukriCity = when (city) { null -> "india"; "bengaluru" -> "bangalore"; "delhi ncr" -> "delhi-ncr"; else -> city.replace(' ', '-') }
        if (slug.isNotBlank() && p.kind != Kind.INTERN) out += Link("Naukri", "freshers (0 years)", "https://www.naukri.com/$slug-jobs-in-$naukriCity?experience=0")
        if (slug.isNotBlank() && p.kind != Kind.JOB) out += Link("Internshala", "internships", "https://internshala.com/internships/keywords-${enc(role).replace("+", "%20")}/")
        return out
    }

    /** Google News searches for hiring drives on the user's roles (big recruiters announce drives in the news, not on these boards). */
    fun newsQueries(p: Profile): List<String> = p.roles.take(2).map { r ->
        val q = "\"$r\" (\"off campus\" OR freshers OR internship OR \"hiring drive\") when:7d"
        "https://news.google.com/rss/search?q=" + enc(q) + "&hl=en-IN&gl=IN&ceid=IN:en"
    }

    // ---------------------------------------------------------------------------------------------------------------
    // From the chat, with no AI: "jobs", "any new internships", "check jobs now", "job watch off",
    // "watch internships for data engineer in Bengaluru or remote".

    private val notMine = Regex("\\b(send|message|msg|whatsapp|sms|e-?mail|mail|call|remind|reminder|alarm|book|order|buy|news|headlines?|resume|cv|apply for me|how to|how do|what is|what's|why|explain|tips?|interview|salary|stipend|market)\\b", I)
    private val listAsk = Regex(
        "^(?:(?:show|see|list|give|get|any|check|open|my|the|new|latest|fresh|today'?s|me|all)\\s+)*(?:job|jobs|internship|internships|openings?)(?:\\s+(?:and|or|&)\\s+(?:jobs?|internships?))?" +
            "(?:\\s+(?:matches|alerts?|for me|list|found|today|now|you found|so far))*$|^(?:what|which|any) (?:new )?(?:jobs|internships|openings)(?: did you find| have you found| for me| today| now)?$|^did you find any (?:new )?(?:jobs|internships|openings)$",
        I,
    )
    private val checkAsk = Regex("^(?:check|look|search|scan|refresh)(?: for)?(?: new)? (?:jobs|internships|openings|job matches)(?: now| again)?$|^(?:job|internship) check(?: now)?$|^check (?:the )?job watch(?:er)?(?: now)?$", I)
    private val onOff = Regex("^(?:(?:turn|switch) (on|off) (?:the )?(?:job|internship)s? (?:watch|watcher|alerts?)|(?:job|internship)s? (?:watch|watcher|alerts?) (on|off)|(stop|pause|start|resume) (?:the )?(?:job|internship)s? (?:watch|watcher|alerts?))$", I)
    private val statusAsk = Regex("^(?:my )?(?:job|internship)s? (?:watch|watcher)(?: status| settings)?$|^what (?:jobs|internships) (?:are you|am i) watching(?: for)?$", I)
    private val setAsk = Regex(
        "^(?:please )?(?:watch|track|alert me (?:about|on|for)|notify me (?:about|of|on)|tell me about new|find me|look for|keep looking for) (?:new |fresh )?" +
            "(internships?|jobs?|fresher jobs?|internships? (?:and|or) (?:fresher )?jobs?|(?:fresher )?jobs? (?:and|or) internships?|openings?) (?:for|as|in|on|of|like) (.+)$",
        I,
    )
    private val companiesAsk = Regex("^(?:which|what) companies (?:do you|are you|you) (?:watch|watching|check|checking|read|reading)(?: for (?:jobs|internships|openings))?$", I)
    private val addCompany = Regex("^(?:watch|add|track) (?:the )?company (.+?)(?: (?:to|for|in) (?:the |my )?(?:job|internship)s?(?: watch(?:er)?)?)?$|^(?:watch|track) (?:jobs|internships|openings) at (.+)$", I)
    private val removeCompany = Regex("^(?:stop watching|remove|unwatch) (?:the )?company (.+?)(?: from (?:the |my )?(?:job|internship)s?(?: watch(?:er)?)?)?$", I)
    private val notARole = Regex("\\b(my|his|her|their|friend|brother|sister|someone|somebody|anyone|me|you)\\b", I)

    /** A plan for [t] (the request in lower case) when it is a job-watcher request, else null. */
    fun plan(t: String): Basics.Plan? {
        val s = t.trim().trimEnd('?', '.', '!').replace(Regex("\\s+"), " ")
        if (s.length < 3 || s.length > 200) return null
        setAsk.find(s)?.let { m ->
            if (Regex("\\b(send|message|whatsapp|call|remind|alarm|book|order|buy|news)\\b", I).containsMatchIn(s)) return null
            val (roles, places) = rolesAndPlaces(m.groupValues[2])
            if (roles.isEmpty() || roles.any { notARole.containsMatchIn(it) }) return null
            val k = m.groupValues[1].lowercase()
            val kind = when { k.contains("intern") && k.contains("job") -> Kind.BOTH; k.contains("intern") -> Kind.INTERN; k.startsWith("job") || k.startsWith("fresher") -> Kind.JOB; else -> Kind.BOTH }
            val args = JSONObject().put("action", "set").put("roles", roles.joinToString(", ")).put("kind", kind.name)
            if (places.isNotEmpty()) args.put("places", places.joinToString(", "))
            return Basics.Plan("job_watch", args) { o -> o.optString("say") }
        }
        if (notMine.containsMatchIn(s)) return null
        if (companiesAsk.matches(s)) return Basics.Plan("job_watch", JSONObject().put("action", "companies")) { o -> o.optString("say") }
        removeCompany.find(s)?.let { m -> return Basics.Plan("job_watch", JSONObject().put("action", "remove_company").put("company", m.groupValues[1].trim())) { o -> o.optString("say") } }
        addCompany.find(s)?.let { m ->
            val name = (m.groupValues[1] + m.groupValues[2]).trim()
            if (name.isBlank() || knownPlace(name) || notARole.containsMatchIn(name)) return null
            return Basics.Plan("job_watch", JSONObject().put("action", "add_company").put("company", name)) { o -> o.optString("say") }
        }
        onOff.find(s)?.let { m ->
            val word = (m.groupValues[1] + m.groupValues[2] + m.groupValues[3]).lowercase()
            val on = word == "on" || word == "start" || word == "resume"
            return Basics.Plan("job_watch", JSONObject().put("action", if (on) "on" else "off")) { o -> o.optString("say") }
        }
        if (statusAsk.matches(s)) return Basics.Plan("job_watch", JSONObject().put("action", "show")) { o -> o.optString("say") }
        if (checkAsk.matches(s)) return Basics.Plan("jobs_check", JSONObject()) { o -> o.optString("say") }
        if (listAsk.matches(s)) return Basics.Plan("jobs_list", JSONObject()) { o -> o.optString("say") }
        return null
    }

    /** "data engineer and data analyst in Bengaluru, Chennai or remote" → the roles and the places (only places this watcher knows count as places). */
    fun rolesAndPlaces(said: String): Pair<List<String>, List<String>> {
        val s = said.trim().trimEnd('.', '?', '!')
        val at = Regex("\\s(?:in|at|near|around|from)\\s", I).findAll(s).lastOrNull()
        if (at != null) {
            val tail = items(s.substring(at.range.last + 1))
            if (tail.isNotEmpty() && tail.all { knownPlace(it) }) return items(s.substring(0, at.range.first)) to tail.map { place(it) }.distinct()
        }
        // "… or remote" with no "in": the places are at the end.
        val parts = items(s)
        val places = parts.takeLastWhile { knownPlace(it) }
        return parts.dropLast(places.size) to places.map { place(it) }.distinct()
    }

    internal val patterns: List<Regex> get() = listOf(indiaPlace, remoteWord, placeFiller, internWords, seniorWords, entryWords, yearsAsked, asksMoney, notMine, listAsk, checkAsk, onOff, statusAsk, setAsk, notARole, companiesAsk, addCompany, removeCompany) + boardLink.map { it.second }
}
