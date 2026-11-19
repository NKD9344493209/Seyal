package com.seyal.app.agent

import com.seyal.app.ai.Llm
import com.seyal.app.data.CallStatus
import com.seyal.app.data.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What Seyal has learnt about doing things in each app: short how-to notes ("In Swiggy, tap the
 * suggestion under the search box, not Enter"). After a long task on the screen works out, Seyal
 * writes down what would make it faster next time; before working in an app it reads that app's
 * notes. This is how it gets better the more it is used. The user can see and clear them in Control.
 */
object Lessons {
    class Lesson(val app: String, val text: String, val at: Long = System.currentTimeMillis()) {
        fun toJson(): JSONObject = JSONObject().put("app", app).put("text", text).put("at", at)
    }

    private val list = java.util.concurrent.CopyOnWriteArrayList<Lesson>()
    private val _all = MutableStateFlow<List<Lesson>>(emptyList())
    val all: StateFlow<List<Lesson>> = _all
    private var file: File? = null

    internal fun init(dir: File) {
        file = File(dir, "seyal/lessons.json")
        list.clear()
        runCatching { file!!.takeIf { it.exists() }?.readText()?.let { JSONArray(it) }?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { o -> list += Lesson(o.optString("app"), o.optString("text"), o.optLong("at")) } } }
        _all.value = list.toList()
    }

    private fun save() {
        _all.value = list.toList()
        val f = file ?: return
        runCatching { f.parentFile?.mkdirs(); f.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()) }
    }

    /** One name per app, however its icon is labelled ("Swiggy Food & Grocery" → "swiggy", "PVR INOX" → "pvr inox"). */
    private fun key(app: String): String {
        val k = app.trim().lowercase().removeSuffix(" app")
        return builtInNames.firstOrNull { k == it || k.startsWith("$it ") || k.startsWith("$it:") || k.startsWith("$it-") } ?: k
    }
    private val builtInNames = listOf("youtube", "amazon", "flipkart", "swiggy", "zomato", "bookmyshow", "district", "redbus", "google meet", "chrome", "whatsapp", "google maps")
    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Whether two notes say much the same (most of the shorter one's words are in the longer one). */
    internal fun similar(a: String, b: String): Boolean {
        val wa = norm(a).split(' ').filter { it.length > 2 }.toSet(); val wb = norm(b).split(' ').filter { it.length > 2 }.toSet()
        if (wa.isEmpty() || wb.isEmpty()) return norm(a) == norm(b)
        val small = if (wa.size <= wb.size) wa else wb; val big = if (wa.size <= wb.size) wb else wa
        return small.count { it in big } >= small.size * 0.7
    }

    /** Adds a note for [app]. A note that says the same as an old one replaces it; each app keeps its newest 10. */
    fun add(app: String, text: String): Boolean {
        val k = key(app); val t = text.trim().replace('\n', ' ').take(260)
        if (k.length < 2 || t.length < 12) return false
        // Never keep anything that looks like a secret or a person's details.
        if (Regex("password|\\botp\\b|\\bpin\\b|cvv|\\b\\d{6,}\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return false
        list.removeAll { it.app == k && similar(it.text, t) }
        list += Lesson(k, t)
        val mine = list.filter { it.app == k }
        if (mine.size > 10) list.removeAll(mine.sortedBy { it.at }.take(mine.size - 10).toSet())
        while (list.size > 150) list.removeAt(0)
        save()
        return true
    }

    fun clear() { list.clear(); save() }
    fun remove(l: Lesson) { list.remove(l); save() }

    /** What ships with Seyal: how the common apps usually work. The user's own lessons are added to these. */
    internal val builtIn: Map<String, List<String>> = mapOf(
        "youtube" to listOf(
            "open_url https://www.youtube.com/results?search_query=WORDS opens the app on the results; tap the video's title to play.",
            "A channel's videos: open_url https://www.youtube.com/@HANDLE/videos; the chips at the top sort them (Latest, Popular, Oldest). Oldest video = tap Oldest, then the first video.",
            "Ads are skipped by Seyal itself; just wait a few seconds.",
        ),
        "district" to listOf(
            "District (by Zomato) books movies too: Movies tab → search the film → pick the theatre and time → choose how many → seats → stop at Pay. Check the film's name and language on its page before picking a show.",
        ),
        "swiggy" to listOf(
            "Do NOT open swiggy.com links (search or restaurant): they open an empty search with \"No results\". Open the Swiggy app with open_app and use its own search bar.",
            "A \"Location Permission is Off\" banner or a \"Select Delivery Address\" list: tap the user's saved address (Home) in that list; never tap GRANT or Enable. Close notification or offer popups with \"Not now\" or X.",
            "After the location is set the home screen can take a few seconds to draw and its list is long: screen_wait for \"Search\", then go on. If taps seem to do nothing, take a screenshot and use screen_tap_at; if it still does not move, open_app Swiggy again (it keeps the location).",
            "Seyal usually opens Swiggy, sets the saved address and types the dish before you start: carry on from the screen you are given (tap the matching suggestion).",
            "To find a dish: tap the search bar, type the dish, then tap the matching suggestion (Dish or a restaurant) instead of pressing Enter. \"No results\" usually means the delivery location at the top is wrong.",
            "On a restaurant page tap ADD beside the item; if a sheet with choices opens, pick them and tap Add Item. Quantity is changed with + and − on the item. Then View Cart at the bottom. Stop at Proceed to Pay.",
        ),
        "zomato" to listOf(
            "Search from the bar at the top, tap the suggestion for the dish or restaurant. ADD beside the item, choose options in the sheet, then the cart bar at the bottom. Stop before Place Order / payment.",
            "Zomato opens with a \"Device location not enabled\" sheet over a list of saved addresses: tap the saved address (Home) under it; never tap Enable. A \"Want to stay updated…\" popup: tap Not now. \"Introducing Host Mode\": close it with X.",
            "Do NOT open zomato.com links: open the Zomato app with open_app and use its search bar. Filters under the search results sort by rating and price.",
        ),
        "amazon" to listOf(
            "Start from a filtered list, not the search bar: open_url https://www.amazon.in/s?k=WORDS&rh=p_36%3A-MAXPAISE&s=review-rank (WORDS = what it is in 3 to 5 plain words joined by +, e.g. men+black+walking+shoes+memory+foam; MAXPAISE = the budget in rupees times 100, e.g. 90000 for ₹900). It lists what fits the budget, best rated first. Do not put the size or the word \"sole\" in WORDS (that finds insoles).",
            "Choose from the list itself: each row has the name, stars, number of ratings and price. Skip rows marked Sponsored when an unsponsored one fits. Open at most three products in all.",
            "On the product page tap the size by its TEXT (e.g. \"11 UK\"). \"Currently unavailable\" for that size means this product is out: go back and open the next one; do not read the size guide.",
            "Then Add to Cart. For more than one, open the cart and use + on that item. If the cart already holds other things, leave them, tell the user, and stop before Proceed to Buy.",
            "A full-screen Prime offer (\"Get Prime… Make Payment\") can open first: press Back once. Never tap Make Payment, and never tap Alexa in the bottom bar.",
        ),
        "flipkart" to listOf(
            "Start from a filtered list: open_url https://www.flipkart.com/search?q=WORDS&sort=popularity&p%5B%5D=facets.price_range.from%3DMin&p%5B%5D=facets.price_range.to%3DMAX (WORDS joined by +, MAX = the budget in rupees). Choose from the list (name, rating, price); open at most three products.",
            "On the product page tap the size by its TEXT, then Add to cart. A size shown greyed or struck out is not available: take the next product. Stop before Place Order.",
        ),
        "bookmyshow" to listOf(
            "Search is the magnifier in the bar at the BOTTOM of the home screen (Home · Live Events · Search). The round icon at the top right is the profile: never tap it. Ignore the \"Pick Up Where You Left Off / Resume booking\" card: it carries the old day and the old ticket count.",
            "The day is the user's day, always. If that day has no show near the time they asked, tell them the show times that day has (ask_user) and let them choose; never switch to another day by yourself.",
            "An area instead of a theatre name (\"near Somanur\"): scroll the whole theatre list for names with that area in them. None there → ask the user which of the listed theatres they want; do not take the first one.",
            "Theatre: after opening the film, the show list has many theatres. Find the one the user named by its NAME (scroll the list, or use the search icon on that list); the first one listed is usually a big multiplex and is NOT the one asked for. If the user's theatre is not in the list, it has no show for that film or day on this app: ask the user (say so, and name two nearby theatres that do), never book another one by yourself.",
            "Day: the date strip at the top of the show list. Seyal sets the user's day itself (screen_day) and tells you which day is highlighted; when you need a day yourself call screen_day with the date, NEVER screen_tap on a date chip: a tap by name has jumped to another day more than once. Then pick a show time, and read the day back from the seat screen's header.",
            "How many: tap the number with screen_tap by its TEXT (\"2\"), never by position: the numbers sit close together and a position tap hits the wrong one. The seat map's top right then shows \"N Tickets\": it must be the user's number BEFORE you call your_turn; if not, tap it and fix the number.",
            "How many: the \"How many seats?\" sheet opens with a number already chosen, often left from last time. Tap the user's number (the row of 1 to 10) and check the picture changed, then Select Seats. On the seat map tap exactly that many seats; the bar at the bottom must show that many.",
            "Search results say what each row is: only a row marked \"In cinemas now\" can be booked. \"Out of cinemas\", \"In upcoming movies\", \"As an Actor\", plays and events with a similar name are not the film: never open them. Seyal usually does the search and opens the right film by itself before you start; carry on from the screen you are given.",
            "Search the movie by name and open its page, check the title, then Book tickets. Pick the date at the top; theatres are listed with their show times: find the theatre the user named and tap the time they want.",
            "If the film runs in several languages or formats (Tamil / Hindi / English, 2D / 3D), a sheet asks which: pick what the user said. If they did not say, ask them; never pick one yourself.",
            "Named theatre: if the app's search shows the THEATRE itself (under Cinemas or Venues) when you type its name, open it that way: its page lists only that theatre's shows, so the wrong theatre cannot be picked. Then the day, then the film's show time. If the search does not show it, go by the film as below.",
            "Choosing the seats: when the seat map opens, Seyal usually hands it to the user by itself (your_turn, \"Pick your N seats, side by side, then tap Done\"); if it has not, call your_turn so the user picks the seats they like with their own fingers; it is quicker and never wrong. Pick them yourself ONLY if the user said so (\"you choose the seats\", \"any seats\").",
            "If you pick: the seat map is a picture, take ONE screenshot. Free seats are white or outlined, sold ones are grey; rows have a letter on the left, numbers run along the row; the price classes are labelled above each block. Seats for people going together must be side by side in ONE row: find a row in the user's class with enough free seats in a run, away from the first two rows, as near the middle as you can, and tap those neighbours.",
            "After the seats are chosen (by the user or by you), the bar at the bottom shows them and the price (e.g. \"A5, A6 · Pay ₹380\"): read it, call seats_check with those labels, and fix the selection if they are not together or the count is wrong (if the user chose them, tell them what is wrong and hand it back with your_turn). No row has enough seats together in budget → ask the user (say what is free) before taking separate seats. Then stop at Pay.",
        ),
        "redbus" to listOf(
            "Choosing the seats: when the seat page opens, call your_turn (\"Pick your N seats, then tap Done\") so the user picks with their own fingers. Pick them yourself ONLY if the user said so (\"you choose\", \"any seat\").",
            "If you pick: the seat page is a drawing (Lower and Upper deck for sleepers). Take ONE screenshot: free seats are white or outlined, sold are grey, pink is for women. Tap free seats that touch each other for people travelling together (a double berth, or two in the same row), with screen_tap_at. The bar at the bottom then shows the seat numbers and the fare: read it, and tap again if the count is wrong.",
            "After seats: choose the boarding point and the dropping point (the one the user named; if they named none and there are several, ask), then passenger details. Name, age and gender come from the user: saved passengers can be ticked, otherwise ask_user once for all of them. Call order_check on the review page, and stop at Proceed to Pay.",
            "Filters at the top of the bus list (AC, Sleeper, departure time, price) are faster than scrolling: set what the user asked for first.",
            "Search link: https://www.redbus.in/bus-tickets/FROM-to-TO?fromCityName=From&toCityName=To&onward=DD-Mon-YYYY (e.g. coimbatore-to-bangalore, onward=14-Nov-2026). There is no link for one bus: share the search link with the bus name, time and price.",
            "Cheapest first: Sort → Price low to high. Each row shows operator, departure, duration, seat type and price.",
        ),
        "google meet" to listOf(
            "A link without starting a call: New meeting → \"Get a meeting link to share\". The link shows in a dialog (and is copied: the clipboard tool reads it). Close the dialog. Never use \"Start an instant meeting\".",
        ),
        "chrome" to listOf("First run: tap \"Use without an account\", then \"Got it\" / \"No thanks\" on the next screens."),
        "whatsapp" to listOf("Send text with send_whatsapp and files with send_file, not by tapping through the app. To read a chat, open it and screen_read."),
        "google maps" to listOf("Directions: open_url https://www.google.com/maps/dir/?api=1&destination=PLACE opens the route with time and distance."),
    )

    /** Notes for the apps a task involves, as text for the AI: built-in ones first, then what was learnt. */
    fun textFor(apps: Collection<String>): String {
        val keys = apps.map { key(it) }.filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) return ""
        return buildString {
            for (k in keys) {
                // The built-in notes are the tested way through the app: all of them, always. What was learnt on this
                // phone comes after, newest six.
                val notes = (builtIn[k] ?: emptyList()) + list.filter { it.app == k }.sortedBy { it.at }.map { it.text }.takeLast(6)
                if (notes.isEmpty()) continue
                appendLine("${k.replaceFirstChar { it.uppercase() }}:")
                notes.forEach { appendLine("- $it") }
            }
        }.trim().take(6000)
    }

    /** Apps a request names, among the ones there are notes for ("order dosa on swiggy" → swiggy). */
    fun appsIn(text: String): List<String> {
        val t = " " + norm(text) + " "
        return (builtIn.keys + list.map { it.app }).distinct().filter { t.contains(" " + norm(it) + " ") }
    }

    /**
     * After a task that took real work on the screen: ask the AI what it would do differently next
     * time in that app, and keep up to two notes. One small request; skipped for short or simple tasks.
     */
    suspend fun learnFrom(s: Session, appLabel: String) {
        val calls = s.messages.filter { it.role == "assistant" }.flatMap { it.calls }
        val screen = calls.filter { Toolbox.get(it.name)?.group == "screen" }
        val failed = screen.count { s.calls[it.id]?.status == CallStatus.FAILED }
        // Worth a lesson only if it was long or something had to be retried.
        if (screen.size < 6 || (screen.size < 10 && failed == 0)) return
        val log = calls.takeLast(40).joinToString("\n") { c ->
            val st = s.calls[c.id]
            "- ${Prompts.friendly(c)} [${st?.status?.name?.lowercase() ?: "?"}]" + (st?.error?.let { " (${it.take(90)})" } ?: "")
        }
        val ask = Agent.lastSaid(s).take(300)
        val out = runCatching {
            Llm.complete(
                "You help a phone assistant get faster. From the log of a task it just finished in the app \"$appLabel\", write at most 2 short notes that would make the SAME KIND of task quicker next time in this app: where the right button is, which step to skip, what did not work and what did. " +
                    "General how-to only: no names, numbers, addresses, prices or anything about this one request. One line each, starting with \"- \". If nothing useful, answer NONE.",
                "Request: $ask\n\nSteps:\n$log", 300,
            )
        }.getOrNull() ?: return
        if (out.trim().equals("NONE", true)) return
        out.lines().map { it.trim().removePrefix("-").removePrefix("•").trim() }.filter { it.length in 15..260 }.take(2).forEach { add(appLabel, it) }
    }
}
