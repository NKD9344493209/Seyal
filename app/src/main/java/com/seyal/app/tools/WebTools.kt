package com.seyal.app.tools

import com.seyal.app.agent.Tool
import com.seyal.app.agent.ToolResult
import com.seyal.app.agent.schema
import com.seyal.app.data.Settings
import com.seyal.app.tools.Common.http
import com.seyal.app.util.Parsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Reading the web: search with page reading, pages, news, weather, Wikipedia. */
object WebTools {

    val all: List<Tool> by lazy {
        listOf(
            Tool(
                "web_search", "web",
                "Search the web and read the top pages. Output: results[title, url, snippet], pages[url, title, text]. For prices, ratings, release dates, lists, show times, 'what's new'. read = how many top pages to read (0-3, default 2).",
                schema { str("query", "Search words", required = true); int("count", "Results to return (1-10)"); int("read", "Pages to read (0-3)") },
            ) { a, _ -> search(a.optString("query"), a.optInt("count", 6), a.optInt("read", 2), a.optInt("fresh_days", 0)) },

            Tool("read_page", "web", "Read the text of one web page. Output: title, text.", schema { str("url", "https://…", required = true) }) { a, _ -> readPage(a.optString("url"), a.optInt("fresh_days", 0)) },

            Tool(
                "news", "web", "Latest headlines on a topic (Google News, India edition). Output: items[title, source, link, published].",
                schema { str("topic", "Topic, e.g. 'AI', 'Chennai rain', 'cricket'", required = true); int("count", "How many (default 10)") },
            ) { a, _ -> news(a.optString("topic"), a.optInt("count", 10)) },

            Tool(
                "weather", "web", "Current weather and a 3-day forecast for a place (no key needed).",
                schema { str("place", "City or area. Empty = home city.") },
            ) { a, _ -> weather(a.optString("place").ifBlank { Settings.homeCity }) },

            Tool(
                "wikipedia", "web", "Facts about a film, person, place or thing from Wikipedia. Output: results[title, extract].",
                schema { str("query", "What to look up, e.g. 'Ramayana 2026 film'", required = true) },
            ) { a, _ -> wikipedia(a.optString("query")) },
        )
    }

    /** The top results for [query] as (title, link), for code that only needs to find a page. Empty when the search site does not answer. */
    suspend fun links(query: String, count: Int = 8): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val found = runCatching { Parsers.searchResults(http("https://html.duckduckgo.com/html/?q=${URLEncoder.encode(query, "UTF-8")}", timeoutMs = 7_000), count.coerceIn(1, 10)) }.getOrNull()
            ?: return@withContext emptyList()
        (0 until found.length()).mapNotNull { i -> found.optJSONObject(i)?.let { it.optString("title") to it.optString("url") } }.filter { it.second.startsWith("http") }
    }

    /**
     * [freshDays] > 0 (set by Seyal itself in Dirghadarshi mode, for a figure that moves every day; see Fresh): only
     * pages dated within that many days are given to the AI, more pages are tried, and search snippets are left out.
     */
    private suspend fun search(query: String, count: Int, read: Int, freshDays: Int = 0): ToolResult = withContext(Dispatchers.IO) {
        if (freshDays > 0) return@withContext searchFresh(query, freshDays)
        if (query.isBlank()) return@withContext ToolResult.Failed("Empty search")
        fun find(q: String): JSONArray? = runCatching { Parsers.searchResults(http("https://html.duckduckgo.com/html/?q=${URLEncoder.encode(q, "UTF-8")}"), count.coerceIn(1, 10)) }.getOrNull()
        var asked = query
        var results = find(query) ?: find(query) ?: return@withContext ToolResult.Failed("Search failed (no connection to the search site). Try once more.", retryable = true)
        // Long, quoted or site-limited searches often come back empty. Try the same thing in plainer words before
        // giving the AI a failure it would spend a whole step working around.
        if (results.length() == 0) Parsers.simplerQuery(query)?.let { plain -> find(plain)?.takeIf { it.length() > 0 }?.let { results = it; asked = plain } }
        if (results.length() == 0) return@withContext ToolResult.Failed("No results for \"$query\", nor for plainer words. Use 3 to 6 plain words (no quotes, no site:), or open the site itself with open_url.", retryable = true)
        val urls = Parsers.readableUrls(results, read.coerceIn(0, 3))
        val pages = JSONArray()
        coroutineScope {
            urls.map { u -> async { runCatching { http(u, timeoutMs = 9_000) }.getOrNull()?.let { u to it } } }.awaitAll()
        }.filterNotNull().forEach { (u, page) ->
            val whole = Parsers.strip(page)
            val text = whole.take(3500)
            val title = Parsers.pageTitle(page)
            // The day the page says it is from, so an old page is never taken for today's (empty = it shows none).
            val dated = runCatching { com.seyal.app.agent.Fresh.dateOf(page, u, title, whole, java.time.LocalDate.now()) }.getOrNull()
            if (text.length > 200) pages.put(JSONObject().put("url", u).put("title", title).put("dated", dated?.toString().orEmpty()).put("text", text))
        }
        val line = "Searched: $query" + if (pages.length() > 0) " · read ${pages.length()} pages" else ""
        ToolResult.Done(JSONObject().put("query", asked).put("results", results).put("pages", pages), line)
    }

    /**
     * A search for today's figure: up to six pages are opened, each is dated by what the page itself says, and only
     * those from the last [days] day(s) are given back (newest first, three at most). The rest are named with their
     * dates, without their text. When none is that new, the newest dated page is given, marked as old.
     */
    private suspend fun searchFresh(query: String, days: Int): ToolResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext ToolResult.Failed("Empty search")
        val F = com.seyal.app.agent.Fresh
        val today = java.time.LocalDate.now()
        val asked = F.sharpen(query, today)
        fun find(q: String): JSONArray? = runCatching { Parsers.searchResults(http("https://html.duckduckgo.com/html/?q=${URLEncoder.encode(q, "UTF-8")}"), 10) }.getOrNull()
        var results = find(asked) ?: find(asked) ?: return@withContext ToolResult.Failed("Search failed (no connection to the search site). Try once more.", retryable = true)
        if (results.length() == 0) Parsers.simplerQuery(asked)?.let { plain -> find(plain)?.takeIf { it.length() > 0 }?.let { results = it } }
        if (results.length() == 0) return@withContext ToolResult.Failed("No results for \"$asked\". Use 3 to 6 plain words.", retryable = true)
        val urls = Parsers.readableUrls(results, 6)
        class Read(val url: String, val title: String, val dated: java.time.LocalDate?, val text: String)
        val read = coroutineScope {
            urls.map { u -> async { runCatching { http(u, timeoutMs = 9_000) }.getOrNull()?.let { u to it } } }.awaitAll()
        }.filterNotNull().mapNotNull { (u, page) ->
            val whole = Parsers.strip(page)
            if (whole.length <= 200) return@mapNotNull null
            val title = Parsers.pageTitle(page)
            Read(u, title, runCatching { F.dateOf(page, u, title, whole, today) }.getOrNull(), F.focus(whole, 3500))
        }
        val good = read.filter { F.fresh(it.dated, today, days) }.sortedByDescending { it.dated }.distinctBy { F.host(it.url) }.take(3)
        val aside = read.filter { r -> good.none { it.url == r.url } }
        val pages = JSONArray()
        good.forEach { pages.put(JSONObject().put("url", it.url).put("title", it.title).put("dated", it.dated.toString()).put("text", it.text)) }
        // Nothing from today: the newest dated page is shown, marked, so the AI can say "the latest I found" with its date.
        val newestOld = if (good.isEmpty()) aside.filter { it.dated != null }.maxByOrNull { it.dated!! } else null
        newestOld?.let {
            pages.put(JSONObject().put("url", it.url).put("title", it.title).put("dated", it.dated.toString()).put("old", true)
                .put("text", "OLD PAGE, dated ${F.say(it.dated!!)} (${F.age(it.dated, today)}). Its figures are NOT today's.\n" + it.text))
        }
        // …and one page that shows no date at all, marked too: a live-rates page may be right, but nothing on it proves it.
        val undated = if (good.isEmpty()) aside.firstOrNull { it.dated == null } else null
        undated?.let {
            pages.put(JSONObject().put("url", it.url).put("title", it.title).put("dated", "").put("old", true)
                .put("text", "UNDATED PAGE: it shows no date, so its figures may be old. Give them only as \"the page shows no date\".\n" + it.text))
        }
        val shownOnly = JSONArray().also { a -> good.forEach { a.put(JSONObject().put("title", it.title).put("url", it.url)) } }
        val note = F.note(good.size, aside.filter { it.url != newestOld?.url && it.url != undated?.url }.map { F.host(it.url) to it.dated }, today, days)
        val line = "Searched: $asked · " + (if (good.isEmpty()) "no page dated today" else "read ${good.size} page${if (good.size == 1) "" else "s"} dated ${good.mapNotNull { it.dated }.distinct().joinToString(", ") { F.say(it) }}") +
            (if (aside.isNotEmpty()) " · set aside ${aside.size} old or undated" else "")
        ToolResult.Done(JSONObject().put("query", asked).put("today", today.toString()).put("results", shownOnly).put("pages", pages).put("note", note), line)
    }

    private suspend fun readPage(url: String, freshDays: Int = 0): ToolResult = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext ToolResult.Failed("Not a web link")
        if (com.seyal.app.agent.Links.isPirate(url)) return@withContext ToolResult.Failed(com.seyal.app.agent.Links.PIRATE_NOTE)
        val html = runCatching { http(url) }.getOrElse { return@withContext ToolResult.Failed("Could not open the page (${it.message})", retryable = true) }
        val title = Parsers.pageTitle(html)
        val whole = Parsers.strip(html)
        val today = java.time.LocalDate.now()
        val dated = runCatching { com.seyal.app.agent.Fresh.dateOf(html, url, title, whole, today) }.getOrNull()
        val out = JSONObject().put("url", url).put("title", title).put("dated", dated?.toString().orEmpty())
        // A question about today's figure: a page that is not from today or yesterday is marked, loudly.
        if (freshDays > 0 && !com.seyal.app.agent.Fresh.fresh(dated, today, freshDays)) {
            val why = dated?.let { "dated ${com.seyal.app.agent.Fresh.say(it)} (${com.seyal.app.agent.Fresh.age(it, today)})" } ?: "showing no date"
            out.put("old", true).put("text", "OLD OR UNDATED PAGE, $why. Its figures are NOT confirmed for today (${com.seyal.app.agent.Fresh.say(today)}): give them only with that date, never as today's.\n" + com.seyal.app.agent.Fresh.focus(whole, 6000))
            return@withContext ToolResult.Done(out, "Read: ${title.ifBlank { url }} · $why")
        }
        out.put("text", if (freshDays > 0) com.seyal.app.agent.Fresh.focus(whole, 8000) else whole.take(8000))
        ToolResult.Done(out, "Read: ${title.ifBlank { url }}")
    }

    private suspend fun news(topic: String, count: Int): ToolResult = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(topic.ifBlank { "top stories" } + " when:1d", "UTF-8")
        val xml = runCatching { http("https://news.google.com/rss/search?q=$q&hl=en-IN&gl=IN&ceid=IN:en") }
            .getOrElse { return@withContext ToolResult.Failed("Could not reach the news feed (${it.message})", retryable = true) }
        val items = Parsers.newsItems(xml, count.coerceIn(1, 25))
        if (items.length() == 0) ToolResult.Failed("No news found for \"$topic\"")
        else ToolResult.Done(JSONObject().put("topic", topic).put("items", items).put("count", items.length()), "Fetched ${items.length()} stories on $topic")
    }

    private val codes = mapOf(0 to "clear", 1 to "mostly clear", 2 to "partly cloudy", 3 to "overcast", 45 to "fog", 48 to "fog", 51 to "light drizzle", 53 to "drizzle", 55 to "heavy drizzle",
        61 to "light rain", 63 to "rain", 65 to "heavy rain", 66 to "freezing rain", 67 to "freezing rain", 71 to "light snow", 73 to "snow", 75 to "heavy snow", 80 to "rain showers", 81 to "rain showers", 82 to "heavy showers",
        95 to "thunderstorm", 96 to "thunderstorm with hail", 99 to "thunderstorm with hail")

    /** Tries once more when the first attempt is slow or fails: a short wait twice beats one long one. */
    // Five seconds, then one more try of six. (It used to be 7 + 9, twice over: a weather service that was down cost 33 s before the web was asked.)
    private fun quick(url: String): String = runCatching { http(url, "application/json", 5_000) }.getOrElse { http(url, "application/json", 6_000) }

    private suspend fun weather(place: String): ToolResult = withContext(Dispatchers.IO) {
        if (place.isBlank()) return@withContext ToolResult.Failed("Which place? (No home city set in Control → You.)")
        // A place looked up before is remembered, so the next answer needs one request instead of two.
        val key = place.trim().lowercase()
        val cache = runCatching { JSONObject(Settings.placeCache) }.getOrElse { JSONObject() }
        // The common cities need no lookup at all.
        var g = cache.optJSONObject(key) ?: com.seyal.app.util.Places.find(place)
        if (g == null) {
            val geo = runCatching { JSONObject(quick("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(place, "UTF-8")}&count=1&language=en&format=json")) }
                .getOrElse { return@withContext ToolResult.Failed("Weather service unreachable (${it.message})", retryable = true) }
            val found = geo.optJSONArray("results")?.optJSONObject(0) ?: return@withContext ToolResult.Failed("No place called \"$place\" found")
            g = JSONObject().put("latitude", found.optDouble("latitude")).put("longitude", found.optDouble("longitude")).put("name", found.optString("name")).put("admin1", found.optString("admin1")).put("country", found.optString("country"))
            runCatching { if (cache.length() > 40) cache.keys().asSequence().toList().take(10).forEach { cache.remove(it) }; cache.put(key, g); Settings.placeCache = cache.toString() }
        }
        val lat = g.optDouble("latitude"); val lon = g.optDouble("longitude")
        val name = listOf(g.optString("name"), g.optString("admin1"), g.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(", ")
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&timezone=auto&forecast_days=3"
        val w = runCatching { JSONObject(quick(url)) }.getOrElse { return@withContext ToolResult.Failed("Weather service unreachable (${it.message}). Do not call weather again now: web_search \"weather $place today\" instead.", retryable = false) }
        val c = w.optJSONObject("current") ?: JSONObject()
        val current = JSONObject().put("temp_c", c.optDouble("temperature_2m")).put("feels_like_c", c.optDouble("apparent_temperature")).put("humidity", c.optInt("relative_humidity_2m"))
            .put("rain_mm", c.optDouble("precipitation")).put("wind_kmh", c.optDouble("wind_speed_10m")).put("sky", codes[c.optInt("weather_code")] ?: "unknown")
        val d = w.optJSONObject("daily") ?: JSONObject()
        val days = JSONArray()
        val dates = d.optJSONArray("time") ?: JSONArray()
        for (i in 0 until dates.length()) {
            days.put(JSONObject().put("date", dates.optString(i)).put("max_c", d.optJSONArray("temperature_2m_max")?.optDouble(i)).put("min_c", d.optJSONArray("temperature_2m_min")?.optDouble(i))
                .put("rain_chance", d.optJSONArray("precipitation_probability_max")?.optInt(i)).put("sky", codes[d.optJSONArray("weather_code")?.optInt(i) ?: -1] ?: "unknown"))
        }
        ToolResult.Done(JSONObject().put("place", name).put("current", current).put("days", days), "$name: ${current.optDouble("temp_c").toInt()}°C, ${current.optString("sky")}")
    }

    private suspend fun wikipedia(query: String): ToolResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext ToolResult.Failed("Nothing to look up")
        val q = URLEncoder.encode(query, "UTF-8")
        val json = runCatching { http("https://en.wikipedia.org/w/api.php?action=query&format=json&prop=extracts&exintro=1&explaintext=1&exchars=3000&generator=search&gsrsearch=$q&gsrlimit=3", "application/json") }
            .getOrElse { return@withContext ToolResult.Failed("Could not reach Wikipedia (${it.message})", retryable = true) }
        val results = Parsers.wikiResults(json)
        if (results.length() == 0) ToolResult.Failed("Nothing on Wikipedia for \"$query\"")
        else ToolResult.Done(JSONObject().put("results", results), "Looked up $query")
    }
}
