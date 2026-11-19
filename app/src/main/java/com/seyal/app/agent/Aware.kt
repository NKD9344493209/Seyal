package com.seyal.app.agent

import com.seyal.app.util.Parsers
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dirghadarshi's awareness: every headline that matters in the fields the user chose, so nothing is missed.
 *
 * What the user asked for (19 Nov 2026): keep me aware, warn me early, spot opportunities, in MY interests;
 * full headlines, one line each, details only when I ask; a brief every day and an alert at once for something big.
 * What went wrong before: "any recent news on AI" gave three headlines from one newspaper and missed a Java
 * security flaw found with AI, which he then found himself.
 *
 * So a topic is not searched once. It is swept from five angles (what is new, what to watch out for,
 * opportunities, research, rules and money), the stories are merged, and the headlines are shown as the
 * publishers wrote them, with source, date and link. No AI writes or picks the headlines: nothing is invented.
 * Everything here is plain rules, testable without a phone; the fetching is in AwareRun.
 */
object Aware {
    private val I = RegexOption.IGNORE_CASE

    class Angle(val id: String, val label: String, val words: String)
    val ANGLES = listOf(
        Angle("threat", "Watch out", "vulnerability OR breach OR hack OR scam OR fraud OR warning OR ban OR recall OR outage OR layoffs"),
        Angle("new", "New", ""),
        Angle("chance", "Opportunities", "launch OR free OR hiring OR internship OR hackathon OR scholarship OR funding OR \"open source\""),
        Angle("research", "Research", "research OR study OR breakthrough OR paper"),
        Angle("rules", "Rules and money", "government OR regulation OR law OR policy OR price OR tax"),
    )
    private fun order(angle: String) = ANGLES.indexOfFirst { it.id == angle }.let { if (it < 0) 9 else it }

    /** One story as its publisher wrote it. [at] = when it was published (0 = unknown). */
    class Item(val title: String, val source: String, val url: String, val at: Long, val angle: String, val topic: String)

    /** One line of the brief: a story, and how many other publishers carry the same one. */
    class Headline(val item: Item, val also: Int, val urgent: Boolean, val others: List<String> = emptyList())

    /** The address to read for [topic] from one [angle], limited to the last [days] days. */
    fun query(topic: String, angle: Angle, days: Int): String {
        val q = (if (angle.words.isBlank()) topic else "$topic (${angle.words})") + " when:${days.coerceIn(1, 30)}d"
        return "https://news.google.com/rss/search?q=" + URLEncoder.encode(q, "UTF-8") + "&hl=en-IN&gl=IN&ceid=IN:en"
    }

    private fun published(s: String): Long = runCatching { ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrDefault(0L)

    /** The stories in one feed (Google News RSS), as many as [max]. */
    fun parse(xml: String, angle: String, topic: String, max: Int = 60): List<Item> = runCatching {
        val out = ArrayList<Item>()
        for (m in Regex("<item>([\\s\\S]*?)</item>").findAll(xml)) {
            if (out.size >= max) break
            val block = m.groupValues[1]
            fun tag(n: String) = Regex("<$n[^>]*>([\\s\\S]*?)</$n>").find(block)?.groupValues?.get(1).orEmpty().trim().removePrefix("<![CDATA[").removeSuffix("]]>").trim()
            val source = Parsers.unescape(tag("source")).trim()
            val raw = Parsers.unescape(tag("title")).trim()
            val title = (if (source.isNotBlank() && raw.endsWith(" - $source")) raw.removeSuffix(" - $source") else raw.substringBeforeLast(" - ")).trim()
            val link = tag("link")
            if (title.length < 12 || Links.isPirate(link)) continue
            out += Item(title, source, link, published(tag("pubDate")), angle, topic)
        }
        out
    }.getOrDefault(emptyList())

    private val stop = setOf(
        "that", "this", "with", "from", "have", "will", "into", "over", "after", "about", "says", "said", "amid", "your", "their", "what", "when", "which", "report", "reports", "news", "india", "indian",
        "the", "and", "for", "are", "was", "has", "had", "its", "new", "how", "why", "who", "can", "now", "not", "but", "you", "all", "out", "may", "get", "set", "his", "her", "our", "one", "two", "more", "than", "here",
    )
    /** The telling words of a headline: names and numbers kept ("GPT", "6"), filler dropped. */
    private fun words(title: String): Set<String> =
        title.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(Regex("\\s+")).filter { w -> w.isNotEmpty() && (w.length >= 3 || w.any { it.isDigit() }) && w !in stop }.toSet()

    /** What a headline is remembered by, so the same story is not alerted twice. */
    fun key(title: String): String = words(title).sorted().take(8).joinToString(" ")

    private fun same(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        // Strict on purpose: two different stories shown as one would lose a headline; the same story shown twice loses nothing.
        val both = a.intersect(b).size
        return both >= 3 && both.toDouble() / minOf(a.size, b.size) >= 0.7
    }

    private val alarm = Regex(
        "\\b(vulnerabilit\\w*|flaws?|zero[- ]day|cve-\\d+|exploit(?:s|ed|ing)?|cyber[- ]?attacks?|attackers?|breach\\w*|hack(?:ed|ers?|ing)?|malware|ransomware|phishing|scam\\w*|fraud\\w*|" +
            "(?:data|password|credentials?|source code) leaks?|leak(?:s|ed|ing)? (?:data|passwords?|credentials?|personal|user)|" +
            "warn(?:s|ed|ing|ings)?|alert|urgent|recall\\w*|ban(?:s|ned)?|shut(?:s|ting)? down|outage|layoffs?|deadline|last date|crash(?:es|ed)?|plunge\\w*|lawsuit|fined|penalt(?:y|ies))\\b",
        I,
    )
    /** A headline that calls for a precaution (a flaw, a scam, a ban, a deadline). */
    fun urgent(title: String): Boolean = alarm.containsMatchIn(title)

    /**
     * The headlines out of every [items] read: stories older than [days] dropped, the same story from several
     * publishers shown once (the count kept), each under its most telling angle, the most carried and the newest
     * first within an angle.
     */
    fun merge(items: List<Item>, nowMs: Long, days: Int): List<Headline> {
        val from = nowMs - days.coerceIn(1, 30) * 86_400_000L
        class Group(var best: Item, val words: MutableSet<String>, val sources: LinkedHashSet<String>)
        val groups = ArrayList<Group>()
        for (it in items.filter { x -> x.at == 0L || x.at >= from }.sortedBy { x -> order(x.angle) }) {
            val w = words(it.title)
            val g = groups.firstOrNull { g -> same(g.words, w) }
            if (g == null) groups += Group(it, w.toMutableSet(), linkedSetOf(it.source))
            else {
                if (g.sources.none { s -> s.equals(it.source, true) }) g.sources += it.source
                // The telling of the story that came first by angle stays; a newer date is kept.
                if (it.at > g.best.at && order(it.angle) == order(g.best.angle)) g.best = it
            }
        }
        return groups.map { g ->
            // A story found under "watch out" that is no warning at all is simply new.
            val angle = if (g.best.angle == "threat" && !urgent(g.best.title)) "new" else g.best.angle
            val item = if (angle == g.best.angle) g.best else Item(g.best.title, g.best.source, g.best.url, g.best.at, angle, g.best.topic)
            val others = g.sources.filter { s -> s.isNotBlank() && !s.equals(item.source, true) }
            Headline(item, others.size, urgent(g.best.title), others)
        }.sortedWith(compareBy<Headline> { order(it.item.angle) }.thenByDescending { it.also }.thenByDescending { it.item.at })
    }

    /** At most [max] headlines, every angle given its share before any angle takes more. */
    fun pick(all: List<Headline>, max: Int): List<Headline> {
        val by = all.groupBy { it.item.angle }
        val out = ArrayList<Headline>()
        var round = 0
        while (out.size < max && by.values.any { it.size > round }) {
            for (a in ANGLES) { by[a.id]?.getOrNull(round)?.let { if (out.size < max) out += it } }
            round++
        }
        return out.sortedWith(compareBy<Headline> { order(it.item.angle) }.thenByDescending { it.also }.thenByDescending { it.item.at })
    }

    // ------------------------------------------------------------------ what an early adopter needs first
    //
    // Naveen, 19 Nov 2026, looking at a brief of 700 headlines: "make sure only these types of news makes me more like
    // early adopter". Most headlines are talk: someone warns, someone says, a stock moves. The few that change what a
    // person can do are of five kinds, and those are picked; the rest stays one tap away, never thrown out.

    class Kind(val id: String, val label: String, val weight: Int)
    val KINDS = listOf(
        Kind("act", "Act now", 6),            // a flaw to patch, a breach, a recall, a scam aimed at people like the user
        Kind("chance", "Opportunity", 5),     // free access, a course, an internship, hiring, a hackathon, a price cut
        Kind("try", "New to try", 5),         // something launched, released, opened up
        Kind("break", "Breakthrough", 4),     // a result: found, shown, beaten, first
        Kind("rule", "Rule change", 4),       // a ban, a law, a deadline, a tax
    )
    private val kindRx = mapOf(
        "act" to Regex("\\b(vulnerabilit\\w*|zero[- ]day|cve-\\d+|flaws?|exploit(?:ed|s)?|attackers?|malware|ransomware|patch(?:es|ed|ing)?|update now|urgent updates?|security (?:bug|hole|fix|update)|data breach|breach(?:ed|es)?|recall(?:s|ed)?|outage)\\b", I),
        "chance" to Regex("\\b(free (?:access|tier|course|courses|credits?|training|certification|for (?:students|developers|all|everyone))|opens? it free|scholarships?|internships?|hackathons?|early access|waitlist|fellowships?|applications? (?:open|invited)|registrations? open|stipend|price cuts?|cheaper|now free|hiring (?:freshers|drive|\\d))\\b", I),
        "try" to Regex("\\b(launch(?:es|ed)?|releas(?:es|ed)|unveil(?:s|ed)?|introduc(?:es|ed)|roll(?:s|ed|ing)? out|now available|generally available|open[- ]sourc(?:es|ed)|debuts?|goes live|opens? (?:it|up)|new (?:model|tool|feature|app|version|api|framework|library|sdk|plugin))\\b", I),
        "break" to Regex("\\b(breakthrough|first[- ]ever|world'?s first|record[- ]breaking|study (?:finds|shows)|researchers? (?:find|found|show|develop|build|discover)\\w*|new (?:method|technique|approach|algorithm|architecture|material)|outperforms?|beats|state[- ]of[- ]the[- ]art|discover(?:s|ed|y)|uncovers?|finds? \\d+)\\b", I),
        "rule" to Regex("\\b(bans?|banned|new (?:law|rules?|policy|regulations?)|bill (?:passed|introduced|cleared)|mandator\\w+|deadline|last date|comes? into (?:force|effect)|effective (?:from|today)|rbi|sebi|import duty|gst|tax (?:cut|hike|change)s?)\\b", I),
    )
    private val scamRx = Regex("\\b(scams?|fraud\\w*|phishing)\\b|மோசடி", I)
    /** Words that put a story near the user (India, Tamil Nadu, the things they pay with). */
    private val nearRx = Regex("\\b(india|indian|tamil ?nadu|chennai|coimbatore|bengaluru|bangalore|mumbai|delhi|upi|rbi|sebi|rupees?|aadhaar|irctc|upsc|tnpsc|csat|isro|iit|nit|tcs|infosys|wipro|zoho)\\b|₹|[\\u0B80-\\u0BFF]", I)

    /** Whether a story is near the user (says India or a place or thing there, or is written in Tamil). */
    fun near(title: String): Boolean = nearRx.containsMatchIn(title)

    /** Which of the five kinds a headline is, or null when it is none (most are none). A scam counts only when it is near the user. */
    fun kind(title: String): Kind? {
        val id = KINDS.firstOrNull { kindRx[it.id]!!.containsMatchIn(title) }?.id ?: if (scamRx.containsMatchIn(title) && near(title)) "act" else return null
        return KINDS.first { it.id == id }
    }

    private val talkRx = Regex(
        "\\b(warnings?|warns?|warned|says?|said|calls|urges?|weighs?|claims?|blamed|fears?|worr(?:y|ies|ied)|concerns?|opinion|podcast|explained|explainer|what we know|here'?s (?:what|why|how)|" +
            "why|how to|top \\d+|these \\d+|\\d+ (?:things|ways|jobs|stocks|points|reasons)|should you|bubble|billionaires?|decabillionaires?|déjà vu|asked if|bets its future)\\b|\\?\\s*$",
        I,
    )
    private val strongRx = Regex("\\b(vulnerabilit\\w*|zero[- ]day|cve-\\d+|patch(?:es|ed|ing)?|update now|urgent updates?|recall(?:s|ed)?)\\b", I)
    private val moneyRx = Regex("\\b(stock picks?|stocks?|shares|nasdaq|nyse|qqq|etfs?|investors?|wall street|price target|analysts?|earnings|valuation|ipo|debt financing|market cap|trading)\\b|\\((?:[A-Z]{2,5}|(?:NASDAQ|NYSE|NYSEARCA|BSE|NSE)\\s*:\\s*[A-Z.]+)\\)", I)
    private val moneyTopic = Regex("\\b(gold|silver|stocks?|shares?|market|invest\\w*|crypto\\w*|bitcoin|mutual funds?|sip|nifty|sensex|price|rate|forex|rupee)\\b", I)
    /** Publishers that mostly carry press releases or stock chatter: one of these alone is not enough for a pick. */
    private val weak = listOf("biggo", "kalkine", "24/7 wall st", "trade brains", "linkedin", "youtube", "tradingview", "marketbeat", "benzinga", "seeking alpha", "motley fool", "zacks",
        "globenewswire", "pr newswire", "prnewswire", "business wire", "businesswire", "openpr", "ein presswire", "einpresswire", "dev.ua", "fourweekmba", "money talks news", "ad-hoc-news", "proactive", "citybiz", "investing.com", "stocktwits", "msn")
    /** Publishers with an editor behind them: a story from one of these stands on its own. */
    private val known = listOf("reuters", "associated press", "ap news", "bbc", "the hindu", "times of india", "economic times", "indian express", "hindustan times", "ndtv", "mint", "moneycontrol", "business standard",
        "bloomberg", "cnbc", "financial times", "wall street journal", "new york times", "washington post", "guardian", "the verge", "techcrunch", "ars technica", "wired", "mit technology review", "the register",
        "bleepingcomputer", "hacker news", "securityweek", "cyberscoop", "dark reading", "infoq", "zdnet", "venturebeat", "engadget", "nature", "science", "ieee", "github", "google", "microsoft", "openai", "anthropic",
        "hugging face", "deccan herald", "india today", "news minute", "dt next", "dinamalar", "dinamani", "vikatan", "pti", "ani", "cnn", "fortune", "forbes", "business insider", "independent", "los angeles times",
        "south china morning post", "techradar", "9to5", "android authority", "xda", "gsmarena", "analytics india", "yourstory", "inc42", "medianama", "livemint", "firstpost", "the print", "scroll", "news18", "dailyhunt")
    fun isWeak(source: String): Boolean = source.lowercase().let { s -> weak.any { s.contains(it) } }
    fun isKnown(source: String): Boolean = source.lowercase().let { s -> known.any { s.contains(it) } }

    /**
     * Talk, not news to act on: someone warns, says or asks; a stock moves (unless money is the interest itself);
     * a headline in a script the user does not read. A plain fix to make ("patch released") is never talk.
     */
    fun noise(title: String, topic: String): Boolean {
        if (title.any { it.isLetter() && it.code > 0x24F && it.code !in 0x0B80..0x0BFF && it.code !in 0x2000..0x206F }) return true
        if (strongRx.containsMatchIn(title)) return false
        if (talkRx.containsMatchIn(title)) return true
        return moneyRx.containsMatchIn(title) && !moneyTopic.containsMatchIn(topic)
    }

    /** How much a headline deserves a place among the picks; null = it is not one (no kind, talk, or one weak source alone). */
    fun score(h: Headline, nowMs: Long): Int? {
        val k = kind(h.item.title) ?: return null
        if (noise(h.item.title, h.item.topic) || lone(h)) return null
        return k.weight + minOf(h.also, 4) + (if (isKnown(h.item.source)) 2 else 0) + (if (near(h.item.title)) 2 else 0) + (if (h.item.at > 0 && nowMs - h.item.at < 36 * 3_600_000L) 1 else 0)
    }

    /** The headlines an early adopter should see first, best first, [max] at most. */
    fun picks(heads: List<Headline>, max: Int, nowMs: Long): List<Headline> =
        heads.mapNotNull { h -> score(h, nowMs)?.let { h to it } }.sortedWith(compareByDescending<Pair<Headline, Int>> { it.second }.thenByDescending { it.first.item.at }).map { it.first }.take(max)

    /** The word shown with a picked headline ("New to try"); "" for the others. */
    fun tag(h: Headline): String = if (noise(h.item.title, h.item.topic) || lone(h)) "" else kind(h.item.title)?.label.orEmpty()

    /** One weak publisher alone carries it. Not enough for a pick, unless it names a plain fix to make (shown then with "one source"). */
    private fun lone(h: Headline): Boolean = isWeak(h.item.source) && h.also == 0 && !strongRx.containsMatchIn(h.item.title)

    /** Something to act on now (a red dot in the list). */
    fun actNow(h: Headline): Boolean = tag(h) == "Act now"

    /** How far a headline can be trusted as it stands: "" (a known publisher, or two or more carry it) or the caution to show. */
    fun caution(h: Headline): String = if (h.also > 0 || isKnown(h.item.source)) "" else "one source"

    private val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private fun line(n: Int, h: Headline, zone: ZoneId): String {
        val on = if (h.item.at > 0) Instant.ofEpochMilli(h.item.at).atZone(zone).format(dayFmt) else ""
        val by = listOf(h.item.source, on).filter { it.isNotBlank() }.joinToString(", ")
        val name = h.item.title.replace("[", "(").replace("]", ")")
        val t = tag(h)
        return "$n. " + (if (h.item.url.startsWith("http")) "[$name](${h.item.url})" else name) + (if (t.isNotBlank()) " · $t" else "") + (if (by.isNotBlank()) " · $by" else "") + (if (h.also > 0) " · +${h.also} more" else "")
    }

    const val PAGE = 30
    private fun tail(left: Int, n: Int): String =
        (if (left > 0) "Say \"more headlines\" for the other $left, or " else "Say ") + "\"more on ${if (n >= 2) 2 else 1}\" for the details of one."

    /**
     * The headlines on one topic, as shown in the chat: grouped by angle, numbered, one line each.
     * [heads] = the ones on this page, [total] = all that were found, [from] = how many were shown before this page.
     */
    fun text(topic: String, heads: List<Headline>, total: Int, read: Int, days: Int, zone: ZoneId, from: Int = 0): String {
        if (heads.isEmpty()) return if (from > 0) "That was all: $total headlines on $topic." else "No news on $topic in the last $days days from the sources I could read ($read stories checked). Try a wider word, or ask again later."
        val sb = StringBuilder(
            if (from > 0) "**$topic**: headlines ${from + 1} to ${from + heads.size} of $total.\n"
            else "**$topic**: $total headlines from the last $days days ($read stories read, the same story counted once)." + (if (heads.size < total) " The first ${heads.size}:" else "") + "\n",
        )
        var n = from
        for (a in ANGLES) {
            val mine = heads.filter { it.item.angle == a.id }
            if (mine.isEmpty()) continue
            sb.append("**${a.label}**\n")
            mine.forEach { sb.append(line(++n, it, zone)).append('\n') }
        }
        return sb.append(tail(total - from - heads.size, n)).toString()
    }

    /** The headlines in the order [text] numbers them. */
    fun numbered(heads: List<Headline>): List<Headline> = ANGLES.flatMap { a -> heads.filter { it.item.angle == a.id } }

    /** [shown] in the order of their numbers; the first [warned] of them are the warnings. */
    class Brief(val text: String, val shown: List<Headline>, val rest: List<Headline>, val warned: Int = 0)

    /** The daily brief over every interest: the warnings first, then each interest's headlines. */
    fun brief(byTopic: List<Pair<String, List<Headline>>>, day: LocalDate, perTopic: Int, zone: ZoneId, nowMs: Long = System.currentTimeMillis()): Brief {
        // A story that falls under two interests (a Java flaw found with AI) is one headline, under the first of them.
        val all = byTopic.flatMap { it.second }.distinctBy { key(it.item.title) }
        val names = byTopic.joinToString(", ") { it.first }
        if (all.isEmpty()) return Brief("Nothing new today in $names from the sources I could read.", emptyList(), emptyList())
        val shown = ArrayList<Headline>()
        // What to act on comes first, whatever interest it is under; then each interest's best few.
        val act = picks(all.filter { actNow(it) }, 5, nowMs)
        shown += act
        val body = StringBuilder()
        if (act.isNotEmpty()) { body.append("**Act now**\n"); act.forEachIndexed { i, h -> body.append(line(i + 1, h, zone)).append('\n') } }
        for ((topic, hs) in byTopic) {
            val mine = picks(hs.filter { it in all && it !in shown }, perTopic, nowMs)
            if (mine.isEmpty()) continue
            body.append("**$topic**\n")
            mine.forEach { shown += it; body.append(line(shown.size, it, zone)).append('\n') }
        }
        val rest = all.filter { it !in shown }
        val on = day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
        val of = "${all.size} ${if (all.size == 1) "headline" else "headlines"} in ${byTopic.size} ${if (byTopic.size == 1) "interest" else "interests"}"
        if (shown.isEmpty()) return Brief("**Dirghadarshi brief, $on**: nothing today to act on or to try, out of $of.\nSay \"more headlines\" to see them all.", emptyList(), rest)
        return Brief("**Dirghadarshi brief, $on**: ${shown.size} picked for an early adopter, out of $of.\n" + body + tail(rest.size, shown.size), shown, rest, act.size)
    }

    /** The next page of a list that did not fit: [rest] is what is left, [from] how many were shown. */
    fun more(rest: List<Headline>, from: Int, zone: ZoneId): String {
        if (rest.isEmpty()) return "That was all the headlines."
        val page = rest.take(PAGE)
        val sb = StringBuilder("Headlines ${from + 1} to ${from + page.size} of ${from + rest.size}.\n")
        page.forEachIndexed { i, h -> sb.append(line(from + i + 1, h, zone)).append('\n') }
        return sb.append(tail(rest.size - page.size, from + page.size)).toString()
    }

    // ------------------------------------------------------------------ the details of one

    private fun host(url: String): String = url.substringAfter("://").substringBefore("/").removePrefix("www.").lowercase()

    /** The page of the story [title] among search [results] (title, link): the same story, the same publisher when it is there. */
    fun story(title: String, source: String, results: List<Pair<String, String>>): String? {
        val w = words(title)
        val src = source.lowercase().replace(Regex("[^a-z0-9]"), "").removePrefix("the")
        val ok = results.filter { (t, u) -> u.startsWith("http") && !host(u).startsWith("news.google.") && !Links.isPirate(u) && same(w, words(t)) }
        return (ok.firstOrNull { (_, u) -> src.length >= 3 && host(u).replace(Regex("[^a-z0-9]"), "").contains(src) } ?: ok.firstOrNull())?.second
    }

    /** What a story's own page says it is about (the publisher's summary, else its first full paragraph). */
    fun lead(html: String): String? {
        fun meta(name: String): String? {
            val q = Regex.escape(name)
            return listOf(
                "<meta[^>]+(?:property|name)=[\"']$q[\"'][^>]*?content=\"([^\"]{40,})\"", "<meta[^>]+(?:property|name)=[\"']$q[\"'][^>]*?content='([^']{40,})'",
                "<meta[^>]+content=\"([^\"]{40,})\"[^>]*?(?:property|name)=[\"']$q[\"']", "<meta[^>]+content='([^']{40,})'[^>]*?(?:property|name)=[\"']$q[\"']",
            ).firstNotNullOfOrNull { Regex(it, I).find(html)?.groupValues?.get(1) }
        }
        val d = (meta("og:description") ?: meta("description") ?: meta("twitter:description"))?.let { Parsers.strip(it) }?.takeIf { it.length >= 40 }
            ?: Regex("<p[^>]*>([\\s\\S]*?)</p>", I).findAll(html).map { Parsers.strip(it.groupValues[1]) }.firstOrNull { it.length >= 120 }
        return d?.replace(Regex("\\s+"), " ")?.let { if (it.length > 600) it.take(600).substringBeforeLast(' ') + "…" else it }
    }

    /** The details of headline [n], as shown in the chat. [url] = the story's own page when it was found, [lead] = what that page says. */
    fun detail(n: Int, h: Headline, url: String?, lead: String?, of: Int, zone: ZoneId): String {
        val on = if (h.item.at > 0) Instant.ofEpochMilli(h.item.at).atZone(zone).format(dayFmt) else ""
        val also = if (h.others.isEmpty()) "" else " · also in " + h.others.take(3).joinToString(", ") + (if (h.others.size > 3) " (+${h.others.size - 3})" else "")
        val link = url ?: h.item.url
        val other = if (of <= 1) 1 else if (n < of) n + 1 else n - 1
        return "**${h.item.title}**\n" +
            listOf(h.item.source, on).filter { it.isNotBlank() }.joinToString(", ") + also + "\n" +
            (lead ?: "I could not read the story itself just now; the link opens it.") + "\n" +
            (if (link.startsWith("http")) "[Read the full story]($link)\n" else "") +
            "Say \"more on $other\" for another, or \"explain this\" for what it means for you."
    }

    // ------------------------------------------------------------------ the Dirghadarshi tab

    /** The small line under a headline: who wrote it, when, and how many others carry it. */
    fun meta(h: Headline, zone: ZoneId): String {
        val on = if (h.item.at > 0) Instant.ofEpochMilli(h.item.at).atZone(zone).format(dayFmt) else ""
        return listOf(h.item.source, on, if (h.also > 0) "+${h.also} more" else "").filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** The heading a brief's headline stands under: the warnings come first, the rest under their interest. */
    fun section(index: Int, warned: Int, h: Headline): String = if (index < warned) "Act now" else h.item.topic

    /** What is asked of the thinking mode when the user taps "What does it mean for me?" on a headline. */
    fun explainAsk(h: Headline, zone: ZoneId): String {
        val on = if (h.item.at > 0) Instant.ofEpochMilli(h.item.at).atZone(zone).format(dayFmt) else ""
        // It begins with "What", so a headline with "buy", "book" or "order" in it is read as a question, never as a purchase.
        return "What does this news mean for me? \"${h.item.title}\" (" + listOf(h.item.source, on).filter { it.isNotBlank() }.joinToString(", ") + "). Find the story by its title and read it first."
    }

    /** An alert that was sent, kept so a missed notification is not lost. */
    class Alert(val title: String, val source: String, val url: String, val topic: String, val carriers: Int, val at: Long)

    /** How much of the bottom bar a tab gets: by the length of its name, so a long name fits and a short one is still easy to tap. */
    fun tabWeight(label: String): Float = label.length.coerceIn(6, 12).toFloat()

    /** Whether [answer] is one of these lists or details (so "more on 2" speaks of it). */
    fun isList(answer: String?): Boolean = answer != null && answer.contains("\"more on ")

    /** The one line of a notification. */
    fun notice(byTopic: List<Pair<String, List<Headline>>>, nowMs: Long = System.currentTimeMillis()): String {
        val all = byTopic.flatMap { it.second }.distinctBy { key(it.item.title) }
        val best = picks(all, 60, nowMs)
        val act = best.count { actNow(it) }
        val first = best.firstOrNull { actNow(it) } ?: best.firstOrNull()
        return (if (best.isEmpty()) "Nothing to act on or to try today, out of ${all.size} headlines" else "${best.size} picked for you out of ${all.size}" + (if (act > 0) ", $act to act on" else "")) + (first?.let { ". ${it.item.title.take(90)}" } ?: "")
    }

    /** Headlines worth an alert at once: something to act on, carried by at least [carriers] publishers, not alerted before. */
    fun alerts(heads: List<Headline>, seen: Set<String>, carriers: Int = 3): List<Headline> =
        heads.filter { actNow(it) && it.also + 1 >= carriers && key(it.item.title) !in seen }.sortedByDescending { it.also }

    // ------------------------------------------------------------------ what the user says

    sealed class Ask {
        /** Headlines on one topic. */
        class News(val topic: String) : Ask()
        /** The brief over every interest. */
        object Brief : Ask()
        class Interests(val op: String, val items: List<String>) : Ask()   // set | add | remove | list
        class Daily(val on: Boolean) : Ask()
        /** The next page of the last list. */
        object More : Ask()
        /** The details of headline [n] of the last list. */
        class Detail(val n: Int) : Ask()
    }

    private val moreRx = Regex("^(?:(?:show|give|get|tell)(?: me)? )?(?:the )?(?:more|next|other|remaining|rest of the) (?:headlines?|news|ones)$|^(?:show |give )?(?:me )?(?:the )?(?:rest|next page)$|^innum (?:headlines?|news) (?:kaattu|kattu|sollu|venum)$", I)
    private val detailRx = listOf(
        Regex("^(?:(?:tell|give|show)(?: me)? )?(?:more|details?|elaborate|explain|expand|open|read)(?: more)?(?: (?:on|about|of|for))?(?: (?:no\\.?|number|headline|point|story|news))? ?#?(\\d{1,3})$", I),
        Regex("^(?:no\\.? ?|number |headline |point |#)?(\\d{1,3}) (?:more|details?|elaborate|explain|expand|in detail|pathi sollu|pathi|vilakku|vivaram)$", I),
    )

    private const val KIND = "(?:news|headlines?|trends?|developments?|happenings?)"
    private val news = listOf(
        Regex("^(?:any |what(?:'s| is| are)(?: the)? |give(?: me)? (?:the )?|show(?: me)? (?:the )?|tell(?: me)? (?:the )?|get(?: me)? (?:the )?)?(?:all )?(?:the )?(?:(?:recent|latest|new|today'?s|current|top|every|full) )*$KIND(?: today)? (?:on|in|about|for|of|regarding|related to) (.{2,60})$", I),
        Regex("^what(?:'s| is) (?:new|happening|trending|going on|the latest)(?: today)? (?:in|with|on|about) (.{2,60})$", I),
        Regex("^(?:latest|recent|new)(?: things)? (?:on|in|about) (.{2,60})$", I),
        Regex("^(.{2,40}?) (?:pathi|pathina|patri|pathiya) (?:news|seithi|headlines?)s?(?: sollu| solu| kaattu| kattu| venum)?$", I),
        Regex("^(.{2,40}?) ?(?:la|le) enna (?:pudhusu|pudusu|puthusu|new|nadakkuthu|nadakuthu)$", I),
        Regex("^(.{2,40}?) $KIND(?: today| now| this week| please)?$", I),
    )
    private val brief = Regex(
        "^(?:(?:show|give|get|tell)(?: me)? )?(?:my |today'?s |daily |morning |the )*(?:dirghadarshi )?brief(?:ing)?(?: now| today| please)?$|^brief me(?: now)?$|^what should i (?:know|be aware of)(?: today| now)?$" +
            "|^(?:any |what(?:'s| is| are)(?: the)? )?(?:(?:recent|latest|new|today'?s|top) )*$KIND(?: today| now)?$|^what(?:'s| is) new(?: today)?$|^keep me (?:aware|updated|posted)$",
        I,
    )
    private val notTopic = Regex("^(?:the|my|me|a|an|this|that|it|read|open|send|share|play|search|find|google|check|whatsapp|any|some|more|good|bad|fake|no|big|breaking)$", I)

    private fun topicOf(raw: String): String? {
        val t = raw.trim().trimEnd('.', '!', '?').replace(Regex("\\s+(?:today|now|this week|these days|right now|please|pls)$", I), "").replace(Regex("^(?:the|field of|world of|area of)\\s+", I), "").trim()
        // "what's new in my mail", "latest on our project", "what's new in Seyal": the user's own things and this app are not news topics.
        if (Regex("^(?:my|our|your|his|her|their)\\b|^(?:seyal|this app|the app|this version|this update|you)$", I).containsMatchIn(t)) return null
        return t.takeIf { it.length >= 2 && !notTopic.matches(it) && !Regex("^(?:read|open|send|share|play|search for|find|google)\\b", I).containsMatchIn(it) }
    }

    private fun listOf2(s: String): List<String> =
        s.split(Regex("\\s*(?:,|;|\\n|\\band\\b|&|\\+)\\s*", I)).map { it.trim().trim('.', '"', '\'') }.filter { it.length in 2..40 }.distinctBy { it.lowercase() }

    /** What a message asks of the awareness part; null when it is something else. */
    fun asks(text: String): Ask? {
        val t = text.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        Regex("^(?:my (?:fields of )?interests? (?:are|is|:)|(?:set|change|update|make) my interests? (?:to|as)|i(?: am|'m) interested in|watch these for me:?|keep me (?:aware|updated|posted) (?:on|about|of))\\s+(.+)$", I).find(t)?.let { m ->
            listOf2(m.groupValues[1]).takeIf { it.isNotEmpty() }?.let { return Ask.Interests("set", it) }
        }
        Regex("^(?:add|include|also watch|also add)\\s+(.+?)\\s+(?:to|in|into) my interests?$|^also (?:watch|follow|track)\\s+(.+)$", I).find(t)?.let { m ->
            listOf2(m.groupValues[1].ifBlank { m.groupValues[2] }).takeIf { it.isNotEmpty() }?.let { return Ask.Interests("add", it) }
        }
        Regex("^(?:remove|delete|drop|take)\\s+(.+?)\\s+(?:from|out of|off) my interests?$", I).find(t)?.let { m ->
            listOf2(m.groupValues[1]).takeIf { it.isNotEmpty() }?.let { return Ask.Interests("remove", it) }
        }
        if (Regex("^(?:what are |show |list |tell me )?my interests?$|^what (?:am i|do i) (?:watch|follow|track)(?:ing)?$", I).matches(t)) return Ask.Interests("list", emptyList())
        Regex("^(?:turn |switch )?(on|off) (?:the |my )?daily brief$|^(?:the |my )?daily brief (on|off)$", I).find(t)?.let { m -> return Ask.Daily((m.groupValues[1].ifBlank { m.groupValues[2] }).equals("on", true)) }
        if (Regex("^(?:send|give) me (?:a |the |my )?brief every (?:day|morning)$|^brief me every (?:day|morning)$|^start (?:the |my )?daily brief$", I).matches(t)) return Ask.Daily(true)
        if (Regex("^stop (?:the |my )?daily brief$|^no (?:more )?daily brief$", I).matches(t)) return Ask.Daily(false)
        if (brief.matches(t)) return Ask.Brief
        if (moreRx.matches(t)) return Ask.More
        for (rx in detailRx) rx.find(t)?.let { m -> m.groupValues[1].toIntOrNull()?.takeIf { it >= 1 }?.let { return Ask.Detail(it) } }
        // "Every morning at 8, give me AI news", "when Amma messages…", "send Ram the news": an automation or an errand, not a question.
        if (Regex("\\b(?:every|each|daily|tomorrow|tonight|whenever|when|remind|schedule|send|share|forward|mail|whatsapp|at \\d)\\b|,|\\b\\d{1,2}(?:[:.]\\d{2})?\\s?(?:am|pm)\\b|\\b\\d{1,2}[:.]\\d{2}\\b|\\b(?:in|after) \\d+ ?(?:min|mins|minutes?|hours?|hrs?)\\b", I).containsMatchIn(t)) return null
        for ((i, rx) in news.withIndex()) rx.find(t)?.let { m ->
            val topic = topicOf(m.groupValues[1]) ?: return@let
            // The bare "X news" shape only for a short name ("AI news", "tamil cinema news").
            if (i == news.lastIndex && topic.split(' ').size > 4) return@let
            return Ask.News(topic)
        }
        return null
    }

    // ------------------------------------------------------------------ when

    const val HOUR = 8
    /** The daily brief is due: it is past [hour] today and today's has not gone out. */
    fun dueDaily(now: Long, lastDay: String, zone: ZoneId, hour: Int = HOUR): Boolean {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        return t.hour >= hour && t.toLocalDate().toString() != lastDay
    }

    /** When the next daily brief should go out. */
    fun nextDaily(now: Long, lastDay: String, zone: ZoneId, hour: Int = HOUR): Long {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        if (dueDaily(now, lastDay, zone, hour)) return now
        val day = if (t.hour < hour) t.toLocalDate() else t.toLocalDate().plusDays(1)
        return day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    }

    /** The brief on screen is worth reading again: none yet, from another day, or more than four hours old. */
    fun stale(latestAt: Long, now: Long, zone: ZoneId): Boolean =
        latestAt <= 0 || now - latestAt > 4 * 3_600_000L || Instant.ofEpochMilli(latestAt).atZone(zone).toLocalDate() != Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    const val RETRY_MS = 15 * 60_000L
    /**
     * When the background should next wake for the brief and the watch: the morning brief (a quarter of an hour
     * after a try that could not reach the news, never at once again), or the next three-hourly watch.
     */
    fun nextWake(now: Long, lastDay: String, lastWatch: Long, lastTry: Long, zone: ZoneId): Long {
        val daily = nextDaily(now, lastDay, zone).let { if (lastTry > 0 && it < lastTry + RETRY_MS) lastTry + RETRY_MS else it }
        return minOf(daily, nextWatch(now, lastWatch, zone))
    }

    /** When the watch for something big next runs: three hours after the last one, and never between 10 PM and 7 AM (then at 7). */
    fun nextWatch(now: Long, lastWatch: Long, zone: ZoneId): Long {
        val t = maxOf(now + 60_000, lastWatch + 3 * 3_600_000L)
        val z = Instant.ofEpochMilli(t).atZone(zone)
        if (z.hour in 7..21) return t
        val day = if (z.hour < 7) z.toLocalDate() else z.toLocalDate().plusDays(1)
        return day.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
    }

    const val UNREACHABLE = "I could not reach the news just now (is the internet on?). Nothing was read, so this is not \"no news\". Try again in a minute."
    /** [text] with a line saying that some readings did not answer, so the list may be missing headlines. */
    fun withNote(text: String, failed: Int, asked: Int): String =
        if (failed <= 0 || asked <= 0) text
        else text.lines().let { ls -> (listOf(ls.first(), "Note: $failed of $asked readings did not answer, so some headlines may be missing.") + ls.drop(1)).joinToString("\n") }

    /** The watch for urgent news runs every three hours of the day (7 AM to 10 PM), not at night. */
    fun dueWatch(now: Long, lastWatch: Long, zone: ZoneId): Boolean {
        val h = Instant.ofEpochMilli(now).atZone(zone).hour
        return h in 7..21 && now - lastWatch >= 3 * 3_600_000L
    }

    const val NO_INTERESTS = "Tell me your interests first, and I will watch them every day. For example: \"my interests are AI, Java, data engineering jobs, gold price\"."
    fun interestsSaid(list: List<String>, daily: Boolean): String =
        if (list.isEmpty()) NO_INTERESTS
        else "Watching ${list.size}: ${list.joinToString(", ")}. " + (if (daily) "A brief comes every morning at $HOUR, and an alert at once for something big." else "Say \"daily brief on\" for a brief every morning, or \"brief\" whenever you want one.")
}

/** The user's interests and what they were last told: kept in a small file. */
object AwareStore {
    private var file: File? = null
    @Volatile var interests: List<String> = emptyList(); private set
    @Volatile var daily: Boolean = false; private set
    @Volatile var lastDay: String = ""; private set
    @Volatile var lastWatch: Long = 0; private set
    @Volatile var latest: String = ""; private set
    @Volatile var latestAt: Long = 0; private set
    private val seen = LinkedHashSet<String>()
    /** The headlines of the last list in the order they were numbered ("more on 2" means the second), and the ones that did not fit. */
    @Volatile var shown: List<Aware.Headline> = emptyList(); private set
    @Volatile var rest: List<Aware.Headline> = emptyList(); private set
    /** The latest brief as headlines, for the Dirghadarshi tab: the first [briefWarned] are the warnings; [briefRest] did not fit. */
    @Volatile var briefHeads: List<Aware.Headline> = emptyList(); private set
    @Volatile var briefRest: List<Aware.Headline> = emptyList(); private set
    @Volatile var briefWarned: Int = 0; private set
    /** The alerts that were sent, newest first. */
    @Volatile var alerts: List<Aware.Alert> = emptyList(); private set
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version

    @Synchronized fun init(dir: File) {
        file = File(dir, "seyal/aware.json"); interests = emptyList(); daily = false; lastDay = ""; lastWatch = 0; latest = ""; latestAt = 0; seen.clear(); shown = emptyList(); rest = emptyList()
        briefHeads = emptyList(); briefRest = emptyList(); briefWarned = 0; alerts = emptyList()
        runCatching {
            val o = JSONObject(file!!.readText())
            interests = o.optJSONArray("interests")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
            daily = o.optBoolean("daily"); lastDay = o.optString("last_day"); lastWatch = o.optLong("last_watch"); latest = o.optString("latest"); latestAt = o.optLong("latest_at")
            o.optJSONArray("seen")?.let { a -> (0 until a.length()).forEach { seen += a.optString(it) } }
            shown = heads(o.optJSONArray("shown")); rest = heads(o.optJSONArray("rest"))
            briefHeads = heads(o.optJSONArray("brief")); briefRest = heads(o.optJSONArray("brief_rest")); briefWarned = o.optInt("brief_warned").coerceIn(0, briefHeads.size)
            alerts = o.optJSONArray("alerts")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { x -> Aware.Alert(x.optString("t"), x.optString("s"), x.optString("u"), x.optString("k"), x.optInt("c"), x.optLong("at")) } } } ?: emptyList()
        }
        _version.value++
    }

    private fun save() {
        runCatching {
            val f = file ?: return
            f.parentFile?.mkdirs()
            f.writeText(JSONObject().put("interests", JSONArray(interests)).put("daily", daily).put("last_day", lastDay).put("last_watch", lastWatch).put("latest", latest).put("latest_at", latestAt).put("seen", JSONArray(seen.toList())).put("shown", json(shown)).put("rest", json(rest))
                .put("brief", json(briefHeads)).put("brief_rest", json(briefRest)).put("brief_warned", briefWarned)
                .put("alerts", JSONArray(alerts.map { x -> JSONObject().put("t", x.title).put("s", x.source).put("u", x.url).put("k", x.topic).put("c", x.carriers).put("at", x.at) })).toString())
        }
        _version.value++
    }

    private fun json(hs: List<Aware.Headline>) = JSONArray(hs.take(300).map { h ->
        JSONObject().put("t", h.item.title).put("s", h.item.source).put("u", h.item.url).put("at", h.item.at).put("a", h.item.angle).put("k", h.item.topic).put("w", h.urgent).put("o", JSONArray(h.others))
    })
    private fun heads(a: JSONArray?): List<Aware.Headline> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val others = o.optJSONArray("o")?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: emptyList()
        Aware.Headline(Aware.Item(o.optString("t"), o.optString("s"), o.optString("u"), o.optLong("at"), o.optString("a"), o.optString("k")), others.size, o.optBoolean("w"), others)
    }

    /** A new list was shown: [numbered] in the order of its numbers, [left] what did not fit. */
    @Synchronized fun listed(numbered: List<Aware.Headline>, left: List<Aware.Headline>) { shown = numbered; rest = left; save() }
    /** The next page was shown. */
    @Synchronized fun paged(n: Int) { shown = shown + rest.take(n); rest = rest.drop(n); save() }

    private fun clean(xs: List<String>) = xs.map { it.trim() }.filter { it.length in 2..40 }.distinctBy { it.lowercase() }.take(12)

    @Synchronized fun set(xs: List<String>) { interests = clean(xs); save() }
    @Synchronized fun add(xs: List<String>) { interests = clean(interests + xs); save() }
    @Synchronized fun remove(xs: List<String>) { val gone = xs.map { it.trim().lowercase() }.toSet(); interests = interests.filter { it.lowercase() !in gone }; save() }
    @Synchronized fun setDaily(on: Boolean) { daily = on; save() }
    @Synchronized fun briefed(text: String, now: Long, day: String, heads: List<Aware.Headline> = emptyList(), left: List<Aware.Headline> = emptyList(), warned: Int = 0) {
        latest = text; latestAt = now; lastDay = day; briefHeads = heads; briefRest = left; briefWarned = warned.coerceIn(0, heads.size); save()
    }
    /** Alerts that were just sent: kept newest first, thirty at most. */
    @Synchronized fun alerted(xs: List<Aware.Alert>) { if (xs.isNotEmpty()) { alerts = (xs + alerts).take(30); save() } }
    @Synchronized fun watched(now: Long) { lastWatch = now; save() }
    @Synchronized fun seen(): Set<String> = seen.toSet()
    @Synchronized fun remember(keys: Collection<String>) { seen += keys; while (seen.size > 400) seen.remove(seen.first()); save() }
}
