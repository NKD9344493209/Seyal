package com.seyal.app.util

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/** Pure text parsers (no Android): news RSS, search result HTML, page text. */
object Parsers {

    fun unescape(s: String): String = s
        .replace("<![CDATA[", "").replace("]]>", "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }

    /** HTML → readable text. */
    fun strip(html: String): String = unescape(
        html.replace(Regex("<script[\\s\\S]*?</script>|<style[\\s\\S]*?</style>"), " ")
            .replace(Regex("<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>|</tr>"), "\n")
            .replace(Regex("<[^>]+>"), " "),
    ).replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").replace(Regex(" ?\\n ?"), "\n").replace(Regex("\\n{2,}"), "\n").trim()

    /** Google News RSS → items[title, source, link, published], duplicates removed. */
    fun newsItems(xml: String, count: Int): JSONArray {
        val items = JSONArray()
        val seen = HashSet<String>()
        for (m in Regex("<item>([\\s\\S]*?)</item>").findAll(xml)) {
            if (items.length() >= count.coerceIn(1, 25)) break
            val block = m.groupValues[1]
            val rawTitle = unescape(Regex("<title>([\\s\\S]*?)</title>").find(block)?.groupValues?.get(1) ?: "").trim()
            val link = Regex("<link>([\\s\\S]*?)</link>").find(block)?.groupValues?.get(1)?.trim() ?: ""
            val pub = Regex("<pubDate>([\\s\\S]*?)</pubDate>").find(block)?.groupValues?.get(1)?.trim() ?: ""
            val source = unescape(Regex("<source[^>]*>([\\s\\S]*?)</source>").find(block)?.groupValues?.get(1) ?: "").trim()
            val title = (if (source.isNotBlank() && rawTitle.endsWith(" - $source")) rawTitle.removeSuffix(" - $source") else rawTitle.substringBeforeLast(" - ")).trim()
            val key = title.lowercase().replace(Regex("[^a-z0-9 ]"), "").split(" ").filter { it.length > 3 }.take(6).joinToString(" ")
            if (title.isBlank() || !seen.add(key)) continue
            items.put(JSONObject().put("title", title).put("source", source).put("link", link).put("published", pub))
        }
        return items
    }

    /** DuckDuckGo HTML results → results[title, url, snippet]. */
    fun searchResults(html: String, count: Int): JSONArray {
        val results = JSONArray()
        val linkRe = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>")
        val snipRe = Regex("<a[^>]*class=\"result__snippet\"[^>]*>([\\s\\S]*?)</a>|<td[^>]*class=\"result-snippet\"[^>]*>([\\s\\S]*?)</td>")
        val snips = snipRe.findAll(html).map { strip(it.groupValues[1].ifBlank { it.groupValues[2] }) }.toList()
        linkRe.findAll(html).forEachIndexed { i, m ->
            if (results.length() >= count.coerceIn(1, 10)) return@forEachIndexed
            var url = unescape(m.groupValues[1])
            Regex("uddg=([^&]+)").find(url)?.let { url = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (url.startsWith("//")) url = "https:$url"
            if (url.contains("duckduckgo.com/y.js")) return@forEachIndexed // ad
            results.put(JSONObject().put("title", strip(m.groupValues[2])).put("url", url).put("snippet", snips.getOrNull(i) ?: ""))
        }
        return results
    }

    /** The first [n] result links worth reading as text: one per site, no video, social or file links. */
    fun readableUrls(results: JSONArray, n: Int): List<String> {
        val skip = listOf("youtube.com", "youtu.be", "facebook.com", "instagram.com", "x.com", "twitter.com", "tiktok.com", "linkedin.com", "pinterest.")
        val hosts = HashSet<String>()
        val out = mutableListOf<String>()
        for (i in 0 until results.length()) {
            if (out.size >= n) break
            val url = results.optJSONObject(i)?.optString("url").orEmpty()
            if (!url.startsWith("http")) continue
            val host = url.substringAfter("://").substringBefore("/").removePrefix("www.").lowercase()
            if (skip.any { host == it || host.endsWith(".$it") || host.startsWith(it) }) continue
            if (Regex("\\.(pdf|docx?|xlsx?|pptx?|zip|apk)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)) continue
            if (hosts.add(host)) out += url
        }
        return out
    }

    fun pageTitle(html: String): String = Regex("<title>([\\s\\S]*?)</title>").find(html)?.groupValues?.get(1)?.let { strip(it) } ?: ""

    /** Wikipedia extracts API → results[title, extract]. */
    fun wikiResults(json: String): JSONArray {
        val results = JSONArray()
        val pages = runCatching { JSONObject(json).optJSONObject("query")?.optJSONObject("pages") }.getOrNull() ?: return results
        val keys = pages.keys()
        val list = mutableListOf<JSONObject>()
        while (keys.hasNext()) pages.optJSONObject(keys.next())?.let { list += it }
        list.sortedBy { it.optInt("index", 99) }.forEach { p ->
            results.put(JSONObject().put("title", p.optString("title")).put("extract", p.optString("extract")))
        }
        return results
    }
}
