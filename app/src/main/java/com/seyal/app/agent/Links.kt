package com.seyal.app.agent

import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Links that belong to an app on the phone. A link to Swiggy, Zomato, BookMyShow, District or redBus
 * is opened inside that app when the app accepts it: no "Open with" box, no browser tab that needs a
 * separate login. Amazon, Flipkart and YouTube are left to Android, which already does this for them.
 */
object Links {
    class Owner(val name: String, val packages: List<String>)

    private val owners = listOf(
        Regex("(^|\\.)swiggy\\.com$") to Owner("Swiggy", listOf("in.swiggy.android")),
        Regex("(^|\\.)zomato\\.com$|(^|\\.)zoma\\.to$") to Owner("Zomato", listOf("com.application.zomato")),
        Regex("(^|\\.)bookmyshow\\.com$") to Owner("BookMyShow", listOf("com.bt.bms")),
        Regex("(^|\\.)district\\.in$") to Owner("District", listOf("com.application.zomato.district")),
        Regex("(^|\\.)redbus\\.in$") to Owner("redBus", listOf("in.redbus.android")),
        Regex("(^|\\.)abhibus\\.com$") to Owner("AbhiBus", listOf("com.abhibus")),
        Regex("(^|\\.)blinkit\\.com$") to Owner("Blinkit", listOf("com.grofers.customerapp")),
        Regex("(^|\\.)zeptonow\\.com$") to Owner("Zepto", listOf("com.zeptoconsumerapp")),
    )

    /** The host of an http(s) link, lower case, or "" when it is not one. */
    internal fun host(url: String): String =
        Regex("^https?://([^/?#:]+)", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.get(1)?.lowercase().orEmpty()

    /** The app a link belongs to, when it is one of those worth opening in-app. */
    fun owner(url: String): Owner? { val h = host(url); return if (h.isBlank()) null else owners.firstOrNull { it.first.containsMatchIn(h) }?.second }

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
    private fun slug(s: String) = s.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** The list of buses for a route and day on redBus. */
    fun bus(from: String, to: String, date: LocalDate): String =
        "https://www.redbus.in/bus-tickets/${slug(from)}-to-${slug(to)}?fromCityName=${enc(from)}&toCityName=${enc(to)}&onward=" + date.format(DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH))

    /** Search results for a dish or a restaurant. [app]: swiggy | zomato. */
    fun food(app: String, words: String): String? = when (app.trim().lowercase()) {
        "swiggy" -> "https://www.swiggy.com/search?query=${enc(words)}"
        "zomato" -> "https://www.zomato.com/search?q=${enc(words)}"
        else -> null
    }

    /** The films running in a city on BookMyShow. */
    fun movies(city: String): String = "https://in.bookmyshow.com/explore/movies-${slug(city)}"
}
