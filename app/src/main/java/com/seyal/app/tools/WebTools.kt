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
            ) { a, _ -> search(a.optString("query"), a.optInt("count", 6), a.optInt("read", 2)) },

            Tool("read_page", "web", "Read the text of one web page. Output: title, text.", schema { str("url", "https://…", required = true) }) { a, _ -> readPage(a.optString("url")) },

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

    private suspend fun search(query: String, count: Int, read: Int): ToolResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext ToolResult.Failed("Empty search")
        val html = runCatching { http("https://html.duckduckgo.com/html/?q=${URLEncoder.encode(query, "UTF-8")}") }
            .getOrElse { return@withContext ToolResult.Failed("Search failed (${it.message})", retryable = true) }
        val results = Parsers.searchResults(html, count.coerceIn(1, 10))
        if (results.length() == 0) return@withContext ToolResult.Failed("No results for \"$query\"", retryable = true)
        val urls = Parsers.readableUrls(results, read.coerceIn(0, 3))
        val pages = JSONArray()
        coroutineScope {
            urls.map { u -> async { runCatching { http(u, timeoutMs = 9_000) }.getOrNull()?.let { u to it } } }.awaitAll()
        }.filterNotNull().forEach { (u, page) ->
            val text = Parsers.strip(page).take(3500)
            if (text.length > 200) pages.put(JSONObject().put("url", u).put("title", Parsers.pageTitle(page)).put("text", text))
        }
        val line = "Searched: $query" + if (pages.length() > 0) " · read ${pages.length()} pages" else ""
        ToolResult.Done(JSONObject().put("query", query).put("results", results).put("pages", pages), line)
    }

    private suspend fun readPage(url: String): ToolResult = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext ToolResult.Failed("Not a web link")
        val html = runCatching { http(url) }.getOrElse { return@withContext ToolResult.Failed("Could not open the page (${it.message})", retryable = true) }
        val title = Parsers.pageTitle(html)
        val text = Parsers.strip(html).take(8000)
        ToolResult.Done(JSONObject().put("url", url).put("title", title).put("text", text), "Read: ${title.ifBlank { url }}")
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

    private suspend fun weather(place: String): ToolResult = withContext(Dispatchers.IO) {
        if (place.isBlank()) return@withContext ToolResult.Failed("Which place? (No home city set in Control → You.)")
        val geo = runCatching { JSONObject(http("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(place, "UTF-8")}&count=1&language=en&format=json", "application/json")) }
            .getOrElse { return@withContext ToolResult.Failed("Weather service unreachable (${it.message})", retryable = true) }
        val g = geo.optJSONArray("results")?.optJSONObject(0) ?: return@withContext ToolResult.Failed("No place called \"$place\" found")
        val lat = g.optDouble("latitude"); val lon = g.optDouble("longitude")
        val name = listOf(g.optString("name"), g.optString("admin1"), g.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(", ")
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&timezone=auto&forecast_days=3"
        val w = runCatching { JSONObject(http(url, "application/json")) }.getOrElse { return@withContext ToolResult.Failed("Weather service unreachable (${it.message})", retryable = true) }
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
