package com.seyal.app.agent

import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Today's figures from today's pages, checked by code and not by the AI's word.
 *
 * Seen on a real phone (Dirghadarshi, 20 Nov 2026): asked the 22k gold rate, Seyal answered ₹9,745 per gram, then
 * ₹11,510, each "on 20 November 2026" and each "How sure: High". Both were from pages of another year: a page
 * written on 20 November 2025 also says "today, November 20". The user knew the real rate, so he saw it; for news he
 * does not know, he could not.
 *
 * So, for a question about a figure that moves (gold, fuel, a currency, an index, "today's price"):
 *  - every page read gets the day the page itself says it is from, year included ([dateOf]);
 *  - a page that is not from today or yesterday, or shows no date, is kept from the AI ([WebTools] with fresh_days);
 *  - the "How sure" line is written by code from how many dated pages of different sites hold the figure ([sure]);
 *  - each source in the answer gets its page's date beside it ([dateSources]).
 *
 * Plain Kotlin, so it is tested without a phone. Patterns avoid "[:" … ":]" (Android refuses such a pattern).
 */
object Fresh {
    private val I = RegexOption.IGNORE_CASE

    // ------------------------------------------------------------------ the day a page says it is from

    private val monthNo = mapOf("jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12)
    private const val MON = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    /** "20 November 2026", "20th Nov, 2026" */
    private val dayMonthYear = Regex("(?<![\\d.,])(\\d{1,2})(?:st|nd|rd|th)?\\s+($MON)\\.?,?\\s+(20\\d{2})(?!\\d)", I)
    /** "November 20, 2026", "Nov. 20 2026" */
    private val monthDayYear = Regex("\\b($MON)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(20\\d{2})(?!\\d)", I)
    /** "2026-11-20", also at the start of "2026-11-20T09:15:00+05:30" */
    private val yearFirst = Regex("(?<!\\d)(20\\d{2})-(\\d{2})-(\\d{2})(?!\\d)")
    /** "20-11-2026", "20/11/2026", "20.11.2026" (day first, as written in India) */
    private val dayFirst = Regex("(?<!\\d)(\\d{1,2})[/.-](\\d{1,2})[/.-](20\\d{2})(?!\\d)")

    private fun day(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    /** Every full date (with its year) written in [text], in the order they stand. */
    fun datesIn(text: String): List<LocalDate> {
        val found = ArrayList<Pair<Int, LocalDate>>()
        for (m in dayMonthYear.findAll(text)) day(m.groupValues[3].toInt(), monthNo[m.groupValues[2].take(3).lowercase()] ?: continue, m.groupValues[1].toInt())?.let { found += m.range.first to it }
        for (m in monthDayYear.findAll(text)) day(m.groupValues[3].toInt(), monthNo[m.groupValues[1].take(3).lowercase()] ?: continue, m.groupValues[2].toInt())?.let { found += m.range.first to it }
        for (m in yearFirst.findAll(text)) day(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { found += m.range.first to it }
        for (m in dayFirst.findAll(text)) day(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())?.let { found += m.range.first to it }
        return found.sortedBy { it.first }.map { it.second }
    }

    private const val KEYS = "article:modified_time|article:published_time|og:updated_time|og:article:modified_time|og:article:published_time|datePublished|dateModified|dateCreated|uploadDate|last-modified|lastmod|publish-date|publishdate|pubdate|DC\\.date(?:\\.issued)?|sailthru\\.date|parsely-pub-date"
    /** <meta property="article:published_time" content="2026-11-20T…"> in either order of its parts. */
    private val metaKeyFirst = Regex("<meta[^>]{0,200}?(?:property|name|itemprop)\\s*=\\s*[\"'](?:$KEYS)[\"'][^>]{0,200}?content\\s*=\\s*[\"']\\s*(20\\d{2}-\\d{2}-\\d{2})", I)
    private val metaValueFirst = Regex("<meta[^>]{0,200}?content\\s*=\\s*[\"']\\s*(20\\d{2}-\\d{2}-\\d{2})[^>]{0,200}?(?:property|name|itemprop)\\s*=\\s*[\"'](?:$KEYS)[\"']", I)
    /** "datePublished":"2026-11-20T…" in the page's own description of itself. */
    private val described = Regex("\"(?:datePublished|dateModified|dateCreated|uploadDate)\"\\s*:\\s*\"\\s*(20\\d{2}-\\d{2}-\\d{2})", I)
    private val stampWord = Regex("\\b(?:updated|update|as on|as of|published|posted|dated|date|today)\\b\\W{0,6}(?:on\\W{0,3})?$", I)
    private val timeTag = Regex("<time[^>]{0,200}?datetime\\s*=\\s*[\"']\\s*(20\\d{2}-\\d{2}-\\d{2})", I)

    /**
     * The day the page says it is from, year included; null when it shows none (then it cannot be used for "today").
     * In order of trust: a full date in the page's title; one in its address; the page's own published / updated
     * marks (the latest of them: a live-rates page is published once and updated daily); a full date in the first
     * lines of its text; its first time stamp. A date after tomorrow is not believed.
     * [html] may be the raw page; [text] its readable words (see Parsers.strip).
     */
    fun dateOf(html: String, url: String, title: String, text: String, today: LocalDate): LocalDate? {
        fun ok(d: LocalDate?) = d != null && !d.isAfter(today.plusDays(1)) && d.year >= 2005
        datesIn(title).firstOrNull { ok(it) }?.let { return it }
        val address = runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url).substringAfter("://").substringAfter('/', "")
        Regex("(?<!\\d)(20\\d{2})/(\\d{1,2})/(\\d{1,2})(?!\\d)").find(address)?.let { m -> day(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.takeIf { ok(it) }?.let { return it } }
        yearFirst.find(address)?.let { m -> day(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.takeIf { ok(it) }?.let { return it } }
        datesIn(address.replace(Regex("[-_/+]"), " ")).firstOrNull { ok(it) }?.let { return it }
        val marks = (metaKeyFirst.findAll(html) + metaValueFirst.findAll(html) + described.findAll(html)).mapNotNull { runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }.filter { ok(it) }.toList()
        marks.maxOrNull()?.let { return it }
        val top = text.take(8000)
        // "Updated: 20 Nov 2026", "as on 20-11-2026", "Published November 20, 2026": a date the page puts beside such a word.
        for (m in dayMonthYear.findAll(top) + monthDayYear.findAll(top) + dayFirst.findAll(top) + yearFirst.findAll(top)) {
            if (!stampWord.containsMatchIn(top.substring((m.range.first - 40).coerceAtLeast(0), m.range.first))) continue
            datesIn(m.value).firstOrNull { ok(it) }?.let { return it }
        }
        datesIn(text.take(1500)).firstOrNull { ok(it) }?.let { return it }
        return timeTag.find(html)?.let { runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }?.takeIf { ok(it) }
    }

    fun say(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    fun age(d: LocalDate, today: LocalDate): String = when (val n = ChronoUnit.DAYS.between(d, today)) {
        0L -> "today"; 1L -> "yesterday"; in 2L..59L -> "$n days old"; in 60L..729L -> "${n / 30} months old"; else -> "${n / 365} years old"
    }

    // ------------------------------------------------------------------ which questions need today's pages

    private val market = Regex(
        "\\b(gold|silver|platinum|petrol|diesel|lpg|cylinder|cng|crude|dollar|dollars|usd|euro|pound|yen|dirham|rupee|exchange rate|forex|sensex|nifty|share price|stock price|bitcoin|btc|ethereum|crypto|repo rate|22 ?k|24 ?k|18 ?k|22 ?carat|24 ?carat|22 ?karat|24 ?karat|sovereign|pavan|pawn|savaran)\\b|தங்க|வெள்ளி|பெட்ரோல்|டீசல்|பவுன்|சவரன்",
        I,
    )
    private val priceWord = Regex("\\b(price|prices|priced|rate|rates|cost|costs|value|worth|how much|quote|trading|buy|buying|sell|selling|invest\\w*|cheap\\w*|vilai|velai)\\b|விலை|ரேட்", I)
    private val nowWord = Regex("\\b(today|todays|today's|now|current|currently|live|latest|right now|this morning|tonight|real ?time|at present|presently|innaiku|inniku|ippo|ippodhu)\\b|இன்று|இன்னைக்கு|இப்போ", I)
    private val past = Regex("\\b(history|historical|in (?:19|20)\\d{2}|last year|years? ago|\\d+ years|decade|since (?:19|20)\\d{2}|was the|were the|all[- ]time|record high|highest ever)\\b", I)

    /**
     * How old, in days, a page may be to answer [question]: 1 for a figure that moves every day (gold, fuel, a
     * currency, an index, anything asked as "today's price"); null when the question is not of that kind (then
     * pages are only labelled with their dates).
     */
    fun limit(question: String): Int? {
        val q = question.take(600)
        if (q.isBlank() || past.containsMatchIn(q)) return null
        return when {
            market.containsMatchIn(q) && (priceWord.containsMatchIn(q) || nowWord.containsMatchIn(q)) -> 1
            Regex("\\b(price|prices|rate|rates)\\b|விலை", I).containsMatchIn(q) && nowWord.containsMatchIn(q) -> 1
            else -> null
        }
    }

    /** The limit for a chat: the latest message decides; a short follow-up ("and 24k?", "then you said 9000") keeps the chat's. */
    fun limit(said: String, earlier: List<String>): Int? =
        limit(said) ?: if (said.trim().length <= 60 && !past.containsMatchIn(said)) earlier.asReversed().firstNotNullOfOrNull { limit(it) } else null

    /** [query] with today's full date in it, so the search prefers today's pages over the same day of another year. */
    fun sharpen(query: String, today: LocalDate): String {
        val q = query.trim()
        if (datesIn(q).isNotEmpty()) return q
        return q.replace(Regex("\\s+(?:in\\s+)?20\\d{2}\\s*$"), "") + " " + today.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))
    }

    private val money = Regex("(?:₹|\\bRs\\.?|\\bINR|\\$)\\s?\\d")
    /** At most [n] characters of [text], starting a little before its first price (the top of a page is menus). */
    fun focus(text: String, n: Int): String {
        if (text.length <= n) return text
        val first = money.find(text)?.range?.first ?: return text.take(n)
        val from = (first - 500).coerceAtLeast(0)
        return if (from == 0) text.take(n) else text.substring(from, (from + n).coerceAtMost(text.length))
    }

    /** Whether a page dated [dated] may answer a question with the limit [days]. A page a day ahead (another time zone) counts as today's. */
    fun fresh(dated: LocalDate?, today: LocalDate, days: Int): Boolean = dated != null && ChronoUnit.DAYS.between(dated, today) in -1L..days.toLong()

    const val NO_DATE = "no date on the page"
    /** What the AI is told about the pages that were set aside. */
    fun note(kept: Int, aside: List<Pair<String, LocalDate?>>, today: LocalDate, days: Int): String = buildString {
        append("Today is ${say(today)}. This question is about a figure that changes every day, so only pages dated today or yesterday were read. ")
        if (kept == 0) append("NO such page was found. Do not give today's figure. Say plainly that you could not confirm it for today; you may give the newest figure you were shown, WITH its date (or \"the page shows no date\"), as \"the latest I found\". Or search again with other words. ")
        if (aside.isNotEmpty()) append("Set aside (their figures are not today's and were not shown to you): " + aside.take(6).joinToString("; ") { (host, d) -> host + " (" + (d?.let { "dated ${say(it)}, ${age(it, today)}" } ?: NO_DATE) + ")" } + ". ")
        append("A page from another year with the same day and month is the commonest mistake: never use one.")
    }

    // ------------------------------------------------------------------ how sure, said by code

    class Page(val host: String, val url: String, val dated: LocalDate?, val text: String)

    fun host(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.").removePrefix("m.").removePrefix("amp.").lowercase()

    /** The pages in what web_search and read_page gave back (see WebTools). */
    fun pages(outputs: List<JSONObject>): List<Page> {
        val out = ArrayList<Page>()
        fun take(p: JSONObject) {
            val url = p.optString("url"); if (!url.startsWith("http")) return
            out += Page(host(url), url, p.optString("dated").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, p.optString("title") + "\n" + p.optString("text"))
        }
        for (o in outputs) {
            o.optJSONArray("pages")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::take) }
            if (o.has("text") && o.has("url")) take(o)
        }
        return out
    }

    private val rupees = Regex("(?:₹|\\bRs\\.?|\\bINR)\\s?(\\d[\\d,]*(?:\\.\\d+)?)", I)
    private val anyMoney = Regex("(?:₹|\\bRs\\.?|\\bINR|\\$)\\s?(\\d[\\d,]*(?:\\.\\d+)?)", I)
    private const val SURE_LINE = "(?im)^[ \\t]*(?:[-*•>#]+[ \\t]*)?\\**[ \\t]*How sure[ \\t]*\\**[ \\t]*(?::|：|-)[^\\n]*$"
    private val sourcesHead = Regex("(?im)^[ \\t]*(?:[-*•>#]+[ \\t]*)?\\**[ \\t]*Sources[ \\t]*\\**[ \\t]*(?::|：)?[ \\t]*\\**[ \\t]*$")

    /** The figure the answer rests on: the first amount of money in it (the Answer line comes first), without commas. */
    fun keyFigure(answer: String): String? {
        val body = answer.replace(Regex("\\[[^\\]]*\\]\\([^)]*\\)|https?://\\S+"), " ")
        val m = rupees.find(body) ?: anyMoney.find(body) ?: return null
        return m.groupValues[1].replace(",", "").trimEnd('.').takeIf { it.any(Char::isDigit) }
    }

    private fun holds(text: String, digits: String): Boolean =
        Regex("(?<![\\d.])" + Regex.escape(digits) + "(?![\\d])").containsMatchIn(text.replace(Regex("(?<=\\d),(?=\\d)"), ""))

    class Verdict(val level: String, val line: String, val confirmed: Boolean)

    /**
     * How sure the answer's figure is, from the pages themselves: High when two or more sites dated today or
     * yesterday hold it, Medium when one does, Low when only older or undated pages do. Null when the answer
     * has no amount of money, or the figure is in no page at all (the figure check deals with that).
     */
    fun sure(answer: String, pages: List<Page>, today: LocalDate, days: Int): Verdict? {
        val digits = keyFigure(answer) ?: return null
        val with = pages.filter { holds(it.text, digits) }
        if (with.isEmpty()) return null
        val good = with.filter { fresh(it.dated, today, days) }.groupBy { it.host }.map { (h, ps) -> h to ps.maxOf { it.dated!! } }
        val shown = answer.replace(Regex("\\[[^\\]]*\\]\\([^)]*\\)|https?://\\S+"), " ").let { body -> (rupees.find(body) ?: anyMoney.find(body))?.value?.trim()?.trimEnd('.') } ?: digits
        return when {
            good.size >= 2 -> {
                val days2 = good.map { it.second }.distinct().sorted()
                val whenSaid = if (days2.size == 1) say(days2[0]) else "${say(days2.first())} and ${say(days2.last())}"
                Verdict("High", "High: ${good.size} sources dated $whenSaid show $shown (${good.joinToString(", ") { it.first }}). Checked by Seyal, not only by the AI.", true)
            }
            good.size == 1 -> Verdict("Medium", "Medium: one source dated ${say(good[0].second)} shows $shown (${good[0].first}). No second dated source confirmed it.", true)
            else -> {
                val newest = with.mapNotNull { p -> p.dated?.let { p.host to it } }.maxByOrNull { it.second }
                Verdict(
                    "Low",
                    if (newest != null) "Low: $shown is from a page dated ${say(newest.second)} (${age(newest.second, today)}, ${newest.first}). No page dated today confirms it."
                    else "Low: $shown is from a page that shows no date (${with.first().host}). It may be old.",
                    false,
                )
            }
        }
    }

    /** [answer] with its "How sure" line written from [v]; a figure that is not confirmed for today is said so on the first line too. */
    fun stamp(answer: String, v: Verdict): String {
        val line = "**How sure**: ${v.line}"
        val rx = Regex(SURE_LINE)
        var out = if (rx.containsMatchIn(answer)) rx.replaceFirst(answer, Regex.escapeReplacement(line)) else {
            val head = sourcesHead.find(answer)
            if (head != null) answer.substring(0, head.range.first).trimEnd() + "\n" + line + "\n" + answer.substring(head.range.first) else answer.trimEnd() + "\n" + line
        }
        if (!v.confirmed && !out.trimStart().startsWith("Not confirmed for today")) out = "Not confirmed for today. Check before you decide.\n" + out.trimStart()
        return out
    }

    private val link = Regex("\\[([^\\]\\n]{1,160})\\]\\((https?://[^)\\s]+)\\)")
    /** In the Sources part of [answer], each link gets the date of its page beside it ("· dated 20 Nov 2026", or "· no date on the page"). */
    fun dateSources(answer: String, pages: List<Page>): String {
        val head = sourcesHead.find(answer) ?: return answer
        val before = answer.substring(0, head.range.last + 1)
        val after = answer.substring(head.range.last + 1)
        val byUrl = pages.associateBy { it.url.trimEnd('/') }
        val done = after.lines().joinToString("\n") { l ->
            val m = link.find(l) ?: return@joinToString l
            if (l.contains("· dated") || l.contains(NO_DATE)) return@joinToString l
            val url = m.groupValues[2].trimEnd('/')
            val page = byUrl[url] ?: pages.filter { it.host == host(url) }.takeIf { it.isNotEmpty() }?.maxByOrNull { it.dated ?: LocalDate.MIN } ?: return@joinToString l
            l.trimEnd() + " · " + (page.dated?.let { "dated ${say(it)}" } ?: NO_DATE)
        }
        return before + done
    }
}
