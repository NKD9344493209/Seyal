package com.seyal.app.agent

import com.seyal.app.ai.ToolDecl
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the user asked to buy or book, in their own terms, written down once before any app is opened.
 * It is shown to the AI on every step and compared in code with what the final screen shows, so the
 * theatre, the day, the number of tickets and the price can't quietly drift into something else.
 * Empty fields are the ones the user left open.
 */
class Order(
    val kind: String,
    val item: String = "",
    val language: String = "",
    val place: String = "",
    val from: String = "",
    val to: String = "",
    /** The day as YYYY-MM-DD. */
    val date: String = "",
    val time: String = "",
    val count: Int = 0,
    val seat: String = "",
    /** The most to pay for one (ticket, seat, item), in rupees. 0 = no limit given. */
    val maxEach: Int = 0,
    val app: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        mapOf("item" to item, "language" to language, "place" to place, "from" to from, "to" to to, "date" to date, "time" to time, "seat" to seat, "app" to app)
            .forEach { (k, v) -> if (v.isNotBlank()) put(k, v) }
        if (count > 0) put("count", count)
        if (maxEach > 0) put("max_each", maxEach)
    }

    val isEmpty: Boolean get() = toJson().length() <= 1

    /** The lines the AI sees on every step. */
    fun text(): String = buildList {
        if (item.isNotBlank()) add("What: $item" + (if (language.isNotBlank()) " ($language)" else ""))
        if (place.isNotBlank()) add("Where: $place  ← this exact place; a different theatre, restaurant or operator is NOT acceptable")
        if (from.isNotBlank() || to.isNotBlank()) add("Route: $from → $to")
        if (date.isNotBlank()) add("Day: $date" + (if (time.isNotBlank()) ", $time" else ""))
        else if (time.isNotBlank()) add("Time: $time")
        if (count > 0) add("How many: $count  ← exactly $count, whatever number the app has pre-selected")
        if (seat.isNotBlank()) add("Seat / class: $seat")
        if (maxEach > 0) add("Most to pay for each: ₹$maxEach")
        if (app.isNotBlank()) add("App: $app")
    }.joinToString("\n")

    companion object {
        fun fromJson(o: JSONObject): Order = Order(
            kind = o.optString("kind"),
            item = o.optString("item").clean(), language = o.optString("language").clean(), place = o.optString("place").clean(),
            from = o.optString("from").clean(), to = o.optString("to").clean(),
            date = o.optString("date").trim().takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }.orEmpty(),
            time = o.optString("time").clean(), count = o.optInt("count", 0).coerceIn(0, 50), seat = o.optString("seat").clean(),
            maxEach = o.optInt("max_each", 0).coerceAtLeast(0), app = o.optString("app").clean(),
        )

        /** "any", "you choose", "-" and the like mean the user left it open. */
        private fun String.clean(): String = trim().takeIf { it.isNotBlank() && !Regex("^(any|anything|none|n/?a|-|unknown|not (said|given|specified)|you choose|nearby|any nearby|best( rated)?( nearby)?)$", RegexOption.IGNORE_CASE).matches(it) }.orEmpty().take(120)

        /** The tool the AI fills in once the user has given the details. */
        fun decl(kind: Preflight.Kind): ToolDecl {
            fun p(type: String, d: String) = JSONObject().put("type", type).put("description", d)
            val props = JSONObject()
                .put("item", p("string", "Exactly what: the movie, dish, product, or 'bus' / 'train' / 'flight' / 'cab'. In the user's words."))
                .put("place", p("string", "The theatre, restaurant, shop or operator the user named, exactly as they said it. Empty if they left it open ('any nearby')."))
                .put("date", p("string", "The day as YYYY-MM-DD, worked out from NOW. Empty if not said."))
                .put("time", p("string", "The time or part of the day as the user said it ('after 1 pm', 'evening', '18:30'). Empty if not said."))
                .put("count", p("integer", "How many tickets / seats / people / items the user said. 0 if they did not say."))
                .put("max_each", p("integer", "The most the user will pay for ONE, in rupees. 0 if not said."))
                .put("seat", p("string", "Seat class or type the user said (balcony, recliner, sleeper, AC, window). Empty if not said."))
                .put("app", p("string", "The app the user named, if any."))
            if (kind == Preflight.Kind.MOVIE) props.put("language", p("string", "The language of the film, if the user said it."))
            if (kind == Preflight.Kind.TRAVEL || kind == Preflight.Kind.CAB) {
                props.put("from", p("string", "Where from")); props.put("to", p("string", "Where to"))
            }
            return ToolDecl(
                "details_complete",
                "Call this when the user has given every needed detail, or told you to choose for them. Write each detail down exactly as the user gave it. Never invent one: leave a field empty (or 0) when the user did not say it.",
                JSONObject().put("type", "object").put("properties", props),
            )
        }

        /**
         * A safety net when the AI could not be reached to write the order down: the number and the
         * price limit, read straight from the user's words.
         */
        fun fromWords(kind: Preflight.Kind, words: List<String>): Order =
            Order(kind = kind.name, count = countIn(words), maxEach = budgetIn(words))

        private val numberWords = mapOf(
            "one" to 1, "single" to 1, "onnu" to 1, "oru" to 1, "two" to 2, "couple" to 2, "rendu" to 2, "irandu" to 2, "three" to 3, "moonu" to 3, "moondru" to 3,
            "four" to 4, "naalu" to 4, "naangu" to 4, "five" to 5, "anju" to 5, "ainthu" to 5, "six" to 6, "aaru" to 6, "seven" to 7, "ezhu" to 7, "eight" to 8, "ettu" to 8, "nine" to 9, "ten" to 10,
        )
        private val unit = "(tickets?|seats?|people|persons?|members?|adults?|pax|of us|per|peru|nos?|plates?|portions?|pieces?|pcs|packs?|items?|டிக்கெட்)"

        /** "2 tickets", "for three people", "rendu ticket" → the number; 0 when nobody said one. The newest answer wins. */
        fun countIn(words: List<String>): Int {
            for (w in words.reversed()) {
                Regex("\\b(\\d{1,2})\\s*$unit", RegexOption.IGNORE_CASE).find(w)?.let { return it.groupValues[1].toInt() }
                Regex("\\b(${numberWords.keys.joinToString("|")})\\s+$unit", RegexOption.IGNORE_CASE).find(w)?.let { return numberWords[it.groupValues[1].lowercase()] ?: 0 }
                // A bare number as the whole answer to "how many?" ("2", "2, balcony", "two").
                Regex("^\\s*(\\d{1,2})\\s*(,|$)").find(w)?.let { return it.groupValues[1].toInt() }
                Regex("^\\s*(${numberWords.keys.joinToString("|")})\\s*(,|$)", RegexOption.IGNORE_CASE).find(w)?.let { return numberWords[it.groupValues[1].lowercase()] ?: 0 }
            }
            return 0
        }

        /** "under 250", "max ₹250 per ticket", "250 rupees each" → 250; 0 when no limit was said. */
        fun budgetIn(words: List<String>): Int {
            for (w in words.reversed()) {
                Regex("\\b(under|below|within|max|maximum|upto|up to|budget|at most|not more than|less than)\\D{0,14}(\\d{2,6})", RegexOption.IGNORE_CASE).find(w)?.let { return it.groupValues[2].toInt() }
                Regex("(₹|rs\\.?|inr)\\s*(\\d{2,6})\\s*(each|per|a ticket|per ticket|per head|max)", RegexOption.IGNORE_CASE).find(w)?.let { return it.groupValues[2].toInt() }
                Regex("\\b(\\d{2,6})\\s*(rs|rupees?|ரூ)?\\s*(each|per (ticket|seat|head|person)|max(imum)?)\\b", RegexOption.IGNORE_CASE).find(w)?.let { return it.groupValues[1].toInt() }
            }
            return 0
        }

        // ---- comparing with what the screen shows

        private fun tokens(s: String): List<String> = s.lowercase().replace(Regex("[^a-z0-9\\u0B80-\\u0BFF ]"), " ").split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in setOf("the", "and", "cinemas", "cinema", "theatre", "theater", "multiplex", "screen", "screens", "hotel", "restaurant", "travels", "tours", "mall", "road", "near") }

        internal fun distance(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]; d[0] = i
                for (j in 1..b.length) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t }
            }
            return d[b.length]
        }

        /**
         * Whether [shown] (a name read from the screen) is the place the user [wanted]. Spelling varies
         * ("Sangeetha" / "Sangeeta"), so words match when they are nearly the same; every distinctive word
         * of what the user said must be found.
         */
        fun samePlace(wanted: String, shown: String): Boolean {
            val want = tokens(wanted); val have = tokens(shown)
            if (want.isEmpty()) return true
            if (have.isEmpty()) return false
            return want.all { w -> have.any { h -> h == w || (w.length >= 5 && distance(w, h) <= (if (w.length >= 8) 2 else 1)) || (w.length >= 5 && h.startsWith(w)) || (h.length >= 5 && w.startsWith(h)) } }
        }
    }

    /**
     * Compares the order with what the review screen shows ([shown]: place, date as YYYY-MM-DD, count,
     * price_each or total, item, from, to). Returns the mismatches, each with what to do about it; empty = all good.
     * Details the screen value is missing for are returned in [unread] (the caller asks for them once).
     */
    fun mismatches(shown: JSONObject, unread: MutableList<String> = mutableListOf()): List<String> {
        val bad = mutableListOf<String>()
        val n = shown.optInt("count", 0)
        if (count > 0) {
            if (n <= 0) unread += "count (how many the app shows as selected)"
            else if (n != count) bad += "The app shows $n, but the user asked for exactly $count. Change the quantity to $count (the app pre-selects a number: tap $count), re-select if needed, and check again."
        }
        val sp = shown.optString("place")
        if (place.isNotBlank()) {
            if (sp.isBlank()) unread += "place (the theatre / restaurant / operator named on the screen)"
            else if (!samePlace(place, sp)) bad += "The screen shows \"$sp\", but the user asked for \"$place\". That is a different place: go back and pick \"$place\". If \"$place\" is not listed for this item and day, do NOT book elsewhere: ask_user, saying it is not listed and what the nearest options are."
        }
        val sd = shown.optString("date")
        if (date.isNotBlank()) {
            if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(sd)) unread += "date (the day shown, as YYYY-MM-DD)"
            else if (sd != date) bad += "The screen shows $sd, but the user asked for $date. Go back, tap the right day in the date strip, and pick the show or trip again."
        }
        if (maxEach > 0) {
            val each = shown.optInt("price_each", 0).takeIf { it > 0 } ?: shown.optInt("total", 0).takeIf { it > 0 && n > 0 }?.let { it / n } ?: 0
            if (each <= 0) unread += "price_each (the price of one, in rupees)"
            // Booking fees sit on top of the ticket price: a little over is the fee, a lot over is the wrong class.
            else if (each > maxEach * 1.2 + 5) bad += "One costs ₹$each, above the user's limit of ₹$maxEach. Pick a cheaper class or seat within ₹$maxEach. If nothing is within the limit, ask_user with what is available and its price."
        }
        if (from.isNotBlank() && shown.optString("from").isNotBlank() && !samePlace(from, shown.optString("from"))) bad += "The trip starts from \"${shown.optString("from")}\", but the user said \"$from\"."
        if (to.isNotBlank() && shown.optString("to").isNotBlank() && !samePlace(to, shown.optString("to"))) bad += "The trip goes to \"${shown.optString("to")}\", but the user said \"$to\"."
        return bad
    }
}
