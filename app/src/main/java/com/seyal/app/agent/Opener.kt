package com.seyal.app.agent

import java.time.LocalDate

/** One line of the screen as Seyal lists it: `12 [tap] Ramayana · In cinemas now | Hindi @500,210`. */
class Item(val id: Int, val role: String, val label: String, val extra: String, val x: Int, val y: Int) {
    val isField: Boolean get() = role == "field"
}

/**
 * The first moves of a booking or a food order, done by code instead of by the AI: open the app, find its search,
 * type what the user asked for, open the right result. Each of those used to be one question to the AI (four to
 * five seconds each), and sometimes a wrong turn: on a real phone an upcoming film was opened instead of the one in
 * cinemas, and two minutes went on a page where nothing could be booked. The AI takes over from the screen this
 * leaves, for the parts that need judgement: the show, the restaurant, the dish.
 *
 * Everything here works on the screen's text, so it is tested without a phone. Whenever the screen is not what a
 * step expects, the step is not taken and the AI carries on from there as before: this can save time, never cost a
 * wrong tap.
 */
object Opener {
    private val I = RegexOption.IGNORE_CASE
    private val lineRx = Regex("^(\\d+) \\[([a-z:]+)\\] (.*) @(\\d+),(\\d+)$")
    private val extraRx = Regex("^(.*) \\(((?:hint: |focused|selected|disabled|id: |SECRET)[^()]*)\\)$")

    /** The lines of a screen list ([elements] as ScreenDriver writes them) as items. Lines that are not elements are skipped. */
    fun items(elements: String): List<Item> = elements.lines().mapNotNull { raw ->
        val m = lineRx.find(raw.trimEnd()) ?: return@mapNotNull null
        var label = m.groupValues[3]; var extra = ""
        extraRx.find(label)?.let { label = it.groupValues[1]; extra = it.groupValues[2] }
        Item(m.groupValues[1].toInt(), m.groupValues[2], label.trim(), extra, m.groupValues[4].toInt(), m.groupValues[5].toInt())
    }

    // ------------------------------------------------------------------ what to do for which request

    sealed class Plan {
        class Movie(val app: String, val title: String) : Plan()
        class Food(val app: String, val query: String) : Plan()
        class Link(val url: String) : Plan()
    }

    private val afterTitle = setOf("movie", "film", "padam", "tickets", "ticket", "show", "in", "version", "language", "tamil", "hindi", "english", "telugu", "malayalam", "kannada", "2d", "3d", "imax", "4dx")

    /** The words to type into the app's search: the film's name without "movie", the language or 2D/3D after it. */
    fun movieQuery(item: String): String {
        val w = item.replace(Regex("\\(.*?\\)"), " ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        while (w.size > 1 && w.last().lowercase().trim(',', '.', ':', '-') in afterTitle) w.removeAt(w.size - 1)
        return w.joinToString(" ").trim(',', '.', ' ').take(60)
    }

    /** "2 plates of mutton biryani" → "mutton biryani". */
    fun foodQuery(item: String): String =
        item.trim().replace(Regex("^(\\d+|one|two|three|four|a|an)\\s+(x\\s+)?((plates?|portions?|nos?|pieces?|pcs|packs?)\\s+)?(of\\s+)?", I), "").trim(',', '.', ' ').take(60)

    /**
     * What can be done by code for this [order] in [app] (the name on its icon); null = nothing, the AI does it all.
     * Only for apps whose first screens are known: BookMyShow, Swiggy, Zomato, and redBus (which has a link per route and day).
     */
    fun plan(kind: Preflight.Kind, app: String, order: Order): Plan? {
        val a = app.lowercase()
        return when (kind) {
            Preflight.Kind.MOVIE -> movieQuery(order.item).takeIf { it.length >= 2 && a.contains("bookmyshow") }?.let { Plan.Movie(app, it) }
            Preflight.Kind.FOOD -> order.place.ifBlank { foodQuery(order.item) }.takeIf { it.length >= 2 && (a.contains("swiggy") || a.contains("zomato")) }?.let { Plan.Food(app, it) }
            Preflight.Kind.TRAVEL -> {
                val day = runCatching { LocalDate.parse(order.date) }.getOrNull()
                val bus = Regex("\\bbus(es)?\\b|பஸ்", I).containsMatchIn(order.item) || a.contains("redbus")
                if (bus && a.contains("redbus") && day != null && order.from.isNotBlank() && order.to.isNotBlank()) Plan.Link(Links.bus(order.from, order.to, day)) else null
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------ finding the search

    private val searchWords = Regex("^search\\b|\\bsearch for\\b|restaurant name or a dish", I)

    /** The thing to tap to start searching: a "Search" tab or icon, or a bar that reads "Search for 'Biryani'". Not a field (a field is typed into directly). */
    fun searchEntry(items: List<Item>): Item? =
        items.filter { !it.isField && it.label.length <= 60 && (searchWords.containsMatchIn(it.label) || (it.label == "(unlabeled)" && Regex("^id: .*search", I).containsMatchIn(it.extra))) }
            .sortedWith(compareBy({ if (it.role == "text" || it.role == "heading") 1 else 0 }, { it.y })).firstOrNull()

    private val addressAsk = Regex("select (a |your )?delivery (address|location)|location permission is off|device location (is )?not enabled|choose (a |your )?delivery (address|location)", I)
    private val notAddress = Regex("^(view all|grant|granting|enable|allow|enter location manually|add (a )?(new )?address|use (my )?current location|search|confirm|close|skip|not now|saved address(es)?|recent (searches|locations))\\b", I)

    /**
     * Food apps open with "Location Permission is Off · Select Delivery Address" over a list of the user's saved
     * addresses. The row to tap: the address already shown at the top of the app when it is in the list, else the
     * first saved one. Never GRANT or Enable. Null when no such sheet is showing.
     */
    fun addressRow(items: List<Item>): Item? {
        val ask = items.firstOrNull { addressAsk.containsMatchIn(it.label) } ?: return null
        val rows = items.filter { it.y > ask.y && !it.isField && it.role != "heading" && it.label != "(unlabeled)" && it.label.length >= 3 && !notAddress.containsMatchIn(it.label) && !addressAsk.containsMatchIn(it.label) }
            .let { all -> all.filter { it.role == "tap" || it.role == "button" }.ifEmpty { all } }
        if (rows.isEmpty()) return null
        val current = items.filter { it.y < ask.y && it.y < 150 && !it.isField }.map { it.label.substringBefore(" · ").trim() }.filter { it.length >= 3 }
        return rows.firstOrNull { r -> current.any { c -> r.label.startsWith(c, true) } } ?: rows.first()
    }

    // ------------------------------------------------------------------ the film among the search results

    enum class Showing { NOW, UPCOMING, GONE, OTHER, UNKNOWN }
    class Result(val item: Item, val title: String, val showing: Showing)

    private val nowRx = Regex("\\bin cinemas?\\b|\\bnow showing\\b|\\bin theat(re|er)s\\b|\\bnow running\\b", I)
    private val upcomingRx = Regex("\\bupcoming\\b|\\bcoming soon\\b|\\breleasing\\b|\\bnot (yet )?released\\b", I)
    private val goneRx = Regex("\\bout of cinemas?\\b|\\bno longer (in|running|showing)\\b|\\bunavailable\\b", I)
    private val kindRx = Regex("^\\s*(as an?|in)\\b", I)
    /** A row reads "Title · what it is" (or "Title, In cinemas now" where the app joins them itself). */
    private val splitRx = Regex("^(.*?)(?:\\s*[·•|]\\s*|,\\s+(?=(?:in|as an?)\\b))(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val filler = setOf("the", "a", "an", "movie", "film", "padam")

    private fun toks(s: String): List<String> = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
    private fun core(s: String): List<String> = toks(s).filter { it !in filler }.ifEmpty { toks(s) }
    /** The same word, allowing one slip of the finger in a longer word ("ramyana" / "ramayana"). */
    private fun alike(a: String, b: String): Boolean = a == b || (minOf(a.length, b.length) >= 5 && Order.distance(a, b) <= (if (minOf(a.length, b.length) >= 9) 2 else 1))

    /** Every word the user said is in [title]. */
    internal fun about(title: String, wanted: String): Boolean { val w = core(wanted); val t = toks(title); return w.isNotEmpty() && w.all { x -> t.any { alike(it, x) } } }
    /** [title] is what the user said, word for word (spelling slips and "The" aside). */
    internal fun same(title: String, wanted: String): Boolean { val w = core(wanted); val t = core(title); return w.isNotEmpty() && w.size == t.size && w.indices.all { alike(w[it], t[it]) } }

    /** The search results that are about [wanted], each with what the app says it is. */
    fun movieResults(items: List<Item>, wanted: String): List<Result> {
        val out = ArrayList<Result>()
        for ((i, it) in items.withIndex()) {
            if (it.isField || it.label == "(unlabeled)") continue
            val m = splitRx.find(it.label)
            val title = (m?.groupValues?.get(1) ?: it.label).trim()
            var tail = m?.groupValues?.get(2).orEmpty()
            if (title.length > 80 || !about(title, wanted)) continue
            // In some layouts the line under the title is an element of its own: the one or two just below it.
            if (tail.isBlank()) tail = items.drop(i + 1).take(3).filter { n -> !n.isField && n.y - it.y in 0..60 && !about(n.label, wanted) }.joinToString(" | ") { n -> n.label }
            val showing = when {
                goneRx.containsMatchIn(tail) -> Showing.GONE
                nowRx.containsMatchIn(tail) -> Showing.NOW
                upcomingRx.containsMatchIn(tail) -> Showing.UPCOMING
                kindRx.containsMatchIn(tail) -> Showing.OTHER
                else -> Showing.UNKNOWN
            }
            out += Result(it, title, showing)
        }
        return out
    }

    sealed class Choice {
        /** Open this one. */
        class Tap(val result: Result) : Choice()
        /** Nothing is opened; the AI is told this. */
        class Tell(val note: String) : Choice()
        /** Nothing to go by: the AI looks for itself. */
        object Leave : Choice()
    }

    private fun word(s: Showing) = when (s) { Showing.NOW -> "in cinemas now"; Showing.UPCOMING -> "upcoming, cannot be booked"; Showing.GONE -> "no longer in cinemas"; Showing.OTHER -> "not a film in cinemas"; Showing.UNKNOWN -> "no label" }

    /**
     * Which result is the film to book. Only one that the app marks as in cinemas now is opened by code; an upcoming
     * film, a play or an actor with a similar name never is. When nothing in cinemas matches, the user is asked.
     */
    fun chooseMovie(results: List<Result>, wanted: String): Choice {
        if (results.isEmpty()) return Choice.Leave
        val now = results.filter { it.showing == Showing.NOW }
        val nowSame = now.filter { same(it.title, wanted) }
        if (nowSame.size == 1) return Choice.Tap(nowSame[0])
        if (nowSame.isEmpty() && now.size == 1) return Choice.Tap(now[0])
        if (now.size > 1) return Choice.Tell("Several films in cinemas match \"$wanted\": ${now.take(5).joinToString("; ") { it.title }}. Open the one the user meant (their exact name or language). If you cannot tell which, ask_user with these names.")
        if (results.all { it.showing == Showing.UNKNOWN }) {
            val exact = results.filter { same(it.title, wanted) }
            return if (exact.size == 1) Choice.Tap(exact[0]) else Choice.Leave
        }
        return Choice.Tell(
            "No film called \"$wanted\" is in cinemas now in this app. The search shows: ${results.take(5).joinToString("; ") { "${it.title} (${word(it.showing)})" }}. " +
                "Do NOT open any of these to book and do not look it up on the web. ask_user now: say it is not running in cinemas at the moment, name what the search shows, and ask what they would like instead.",
        )
    }

    private val closeRx = Regex("^(close|dismiss|✕|×|x|not now|skip|maybe later|later|no,? thanks)$", I)
    /** The button that closes an offer or a notice lying over the app. */
    fun closeButton(items: List<Item>): Item? = items.firstOrNull { !it.isField && closeRx.matches(it.label.trim()) }

    /** The "Book tickets" button of a film's page. */
    fun bookButton(items: List<Item>): Item? = items.firstOrNull { !it.isField && Regex("^book( tickets?| now)?$", I).matches(it.label.trim()) }
}

/** Wrong turns seen on a real phone, caught by code the moment they happen instead of ten steps later. */
object Detour {
    private val I = RegexOption.IGNORE_CASE
    private val notOut = Regex("\\breleasing (in|on)\\b|\\bi[’']?m interested\\b|\\bmark interested\\b|\\bbookings? (are |is )?not (yet )?open", I)
    private val bookable = Regex("\\bbook tickets?\\b|\\bbook now\\b|\\bselect seats?\\b", I)

    /** A film's page that has no shows to book: it is not released yet. */
    fun upcoming(screen: String): Boolean = notOut.containsMatchIn(screen) && !bookable.containsMatchIn(screen)

    private val over = Regex("\\bno watch options\\b|\\bout of cinemas?\\b", I)
    /** A film's page whose run in cinemas is over. */
    fun ended(screen: String): Boolean = over.containsMatchIn(screen) && !bookable.containsMatchIn(screen)

    const val ENDED = "WRONG TURN: this film is NOT in cinemas any more (\"Out of cinemas\", \"No watch options\"). Nothing can be booked here. Press Back now. " +
        "Only a search result marked \"In cinemas now\" whose name is the film the user asked for can be booked. If there is none, ask_user: say their film is not running and what the search shows. Never book a different film."

    /** What to tell the AI about the page it has landed on, or null when the page is fine. */
    fun note(screen: String): String? = when { upcoming(screen) -> UPCOMING; ended(screen) -> ENDED; else -> null }

    const val UPCOMING = "WRONG TURN: this page is a film that is NOT in cinemas yet (\"Releasing…\", \"I'm interested\"). Nothing can be booked here and scrolling will not change that. " +
        "Press Back now and open the search result whose name is the film AND says \"In cinemas now\". If no result says that, the film is not running: ask_user, telling them what the search shows. Do not look it up on the web."

    /** Apps Seyal orders and books in. A tap inside one of them that lands in a browser or an app store hit an advert. */
    internal val orderApps = setOf(
        "com.bt.bms", "in.swiggy.android", "com.application.zomato", "com.application.zomato.district", "in.redbus.android", "com.abhibus",
        "in.amazon.mShop.android.shopping", "com.flipkart.android", "com.grofers.customerapp", "com.zeptoconsumerapp", "com.myntra.android", "com.meesho.supply", "com.bigbasket.mobileapp",
    )
    internal val outside = setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser", "com.opera.browser", "com.opera.mini.native", "com.brave.browser", "com.microsoft.emmx",
        "com.mi.globalbrowser", "com.android.browser", "com.heytap.browser", "com.coloros.browser", "com.vivo.browser", "com.duckduckgo.mobile.android", "com.UCMobile.intl",
        "com.android.vending", "com.xiaomi.mipicks", "com.heytap.market", "com.xiaomi.market",
    )
    private val taps = setOf("screen_tap", "screen_tap_at", "screen_long_press")

    /** A tap ([tool]) made in an ordering app ([before]) left it for a browser or a store ([after]). */
    fun strayed(tool: String, before: String?, after: String): Boolean = tool in taps && before != null && before != after && before in orderApps && after in outside

    fun strayNote(from: String, to: String): String =
        "That tap opened $to: an advert or an outside link, not a step of this order. Seyal went back to $from. " +
            "Do not tap banners, offer or cashback cards, \"credit card\" tiles or \"You might also like\": find what the user asked for by its name."
}
