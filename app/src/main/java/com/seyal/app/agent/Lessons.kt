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

    private fun key(app: String) = app.trim().lowercase().removeSuffix(" app")
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
        "swiggy" to listOf(
            "To find a dish: tap the search bar, type the dish, then tap the matching suggestion (Dish or a restaurant) instead of pressing Enter. \"No results\" usually means the delivery location at the top is wrong.",
            "On a restaurant page tap ADD beside the item; if a sheet with choices opens, pick them and tap Add Item. Quantity is changed with + and − on the item. Then View Cart at the bottom. Stop at Proceed to Pay.",
        ),
        "zomato" to listOf(
            "Search from the bar at the top, tap the suggestion for the dish or restaurant. ADD beside the item, choose options in the sheet, then the cart bar at the bottom. Stop before Place Order / payment.",
        ),
        "bookmyshow" to listOf(
            "Search the movie by name and open its page, check the title, then Book tickets. Pick the date at the top; theatres are listed with their show times: find the theatre the user named and tap the time they want.",
            "A \"How many seats?\" sheet opens before the seat map: tap the number the user asked for (it defaults to 2, never leave a wrong number), then Select Seats.",
            "The seat map is a picture: take ONE screenshot. Free seats are white or outlined, sold ones are grey. Tap the seats, check the bottom button shows the right count and price (Pay ₹…), and stop there.",
        ),
        "redbus" to listOf(
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
                val notes = (builtIn[k] ?: emptyList()) + list.filter { it.app == k }.sortedBy { it.at }.map { it.text }
                if (notes.isEmpty()) continue
                appendLine("${k.replaceFirstChar { it.uppercase() }}:")
                notes.takeLast(9).forEach { appendLine("- $it") }
            }
        }.trim().take(2600)
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
        val ask = s.messages.lastOrNull { it.role == "user" }?.text?.take(300).orEmpty()
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
