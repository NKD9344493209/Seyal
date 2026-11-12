package com.seyal.app.agent

import com.seyal.app.ai.ToolDecl
import com.seyal.app.data.Part
import com.seyal.app.data.Settings
import com.seyal.app.util.Times
import org.json.JSONObject

/**
 * Before buying, booking or ordering, the details that change what is bought or what it costs must
 * come from the user, not from a guess. Telling the AI so in its instructions was not enough (it
 * opened the app and picked 6 tickets), so this is a step of its own: for such a request the AI
 * gets only two choices: ask the user for what is missing, or confirm that nothing is.
 */
object Preflight {
    enum class Kind(val what: String, val details: String) {
        MOVIE(
            "book movie tickets",
            "1) which movie  2) which theatre (or 'any nearby')  3) which day  4) show time (or from when they are free)  5) how many tickets  6) seat type or the most to pay per ticket",
        ),
        FOOD(
            "order food",
            "1) which dish, and its kind where it matters (veg / chicken / mutton, size)  2) how many  3) which restaurant (or 'best rated nearby')  4) budget",
        ),
        TRAVEL(
            "book a bus, train or flight",
            "1) from where  2) to where  3) which day  4) what time of day  5) how many people  6) seat type (sleeper / seater, AC / non-AC)  7) budget per seat",
        ),
        CAB(
            "book a cab or auto",
            "1) pickup place (their current location is fine if they said 'from here' or nothing else)  2) drop place  3) now or at what time  4) vehicle type (auto / mini / sedan / bike)",
        ),
        SHOP(
            "buy or order something",
            "1) exactly which item (brand, variant, size or colour where it matters)  2) how many  3) budget  4) which app or shop, if they care",
        ),
    }

    private val verb = Regex("\\b(book|order|buy|purchase|reserve|vaangu|vangu|vaangi|order pannu|book pannu)\\b|ஆர்டர்|புக்|வாங்கு|வாங்கி", RegexOption.IGNORE_CASE)
    private val movie = Regex("\\b(movie|film|ticket|tickets|show|cinema|cinemas|theatre|theater|bookmyshow|padam)\\b|டிக்கெட்|படம்", RegexOption.IGNORE_CASE)
    private val travel = Regex("\\b(bus|buses|train|flight|redbus|irctc|abhibus|makemytrip|ixigo)\\b|பஸ்|ரயில்", RegexOption.IGNORE_CASE)
    private val cab = Regex("\\b(cab|taxi|auto|uber|ola|rapido|ride)\\b", RegexOption.IGNORE_CASE)
    private val food = Regex(
        "\\b(food|biryani|biriyani|dosa|dosai|idli|idly|pizza|burger|meals|parotta|chicken|mutton|noodles|fried rice|roast|lunch|dinner|breakfast|tiffin|swiggy|zomato|restaurant|hotel|eat|saapadu)\\b|பிரியாணி|தோசை|சாப்பாடு",
        RegexOption.IGNORE_CASE,
    )
    /** Things "book"/"order" can mean that are not purchases. */
    private val notBuying = Regex(
        "\\b(bookmark|book a meeting|book a call|book a slot with|order by|in order|sort order|facebook|notebook|(my|the|last|previous|that) order|order status|track|cancel|refund)\\b" +
            "|^(how|what|when|where|why|which|who|is|are|does|did|should)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** What kind of purchase a request is, or null when it isn't one (questions and look-ups are not). */
    fun kind(text: String): Kind? {
        val t = text.trim()
        if (t.length < 6 || !verb.containsMatchIn(t) || notBuying.containsMatchIn(t)) return null
        return when {
            movie.containsMatchIn(t) -> Kind.MOVIE
            travel.containsMatchIn(t) -> Kind.TRAVEL
            cab.containsMatchIn(t) -> Kind.CAB
            food.containsMatchIn(t) -> Kind.FOOD
            else -> Kind.SHOP
        }
    }

    /** The second choice the AI gets beside ask_user. */
    val complete = ToolDecl(
        "details_complete",
        "Call this only when the user has already given every needed detail, or told you to choose for them.",
        JSONObject().put("type", "object").put("properties", JSONObject().put("summary", JSONObject().put("type", "string").put("description", "The details, in one line"))),
    )

    fun system(kind: Kind): String = """
You are the first step of a phone assistant. The user wants to ${kind.what}. Before anything is opened, every detail below must come from the user.

NEEDED: ${kind.details}

NOW: ${Times.nowText()}. Home city: ${Settings.homeCity.ifBlank { "unknown" }}.
KNOWN ABOUT THE USER: ${Memory.text().ifBlank { "(nothing)" }.take(600)}

Go through NEEDED one by one against what the user wrote in this conversation (things known about the user count):
- A detail counts as given only if the user said it. Never fill one in yourself: not the number of tickets or items, not the time, not the price.
- If one or more are missing: call ask_user ONCE. Its question lists every missing detail as a numbered line with two or three example choices, and ends with: "Answer in one line, or say 'you choose'." Do not ask for anything already given. Leave options empty.
- If nothing is missing, or the user said to choose for them: call details_complete.
Write the question in the language the user wrote in.
    """.trimIndent()
}

/**
 * Commands so simple that asking the AI would only add a wait: "torch on", "open YouTube",
 * "timer 5 minutes". Matched exactly; anything else goes to the AI as usual.
 */
object Quick {
    private fun call(name: String, vararg kv: Pair<String, Any>) = Part.Call(com.seyal.app.data.newId(), name, JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } })
    private val onOff = "(on|off)"

    fun match(raw: String): Part.Call? {
        val t = raw.trim().trimEnd('.', '!').lowercase().replace(Regex("\\s+"), " ").removePrefix("please ").removeSuffix(" please")
        if (t.length > 48) return null
        Regex("^(?:turn |switch )?$onOff (?:the )?(?:torch|flashlight|flash light)$").find(t)?.let { return call("set_setting", "name" to "flashlight", "value" to it.groupValues[1]) }
        Regex("^(?:turn |switch )?(?:the )?(?:torch|flashlight|flash light) $onOff$").find(t)?.let { return call("set_setting", "name" to "flashlight", "value" to it.groupValues[1]) }
        Regex("^(?:turn |switch )?$onOff (?:dnd|do not disturb)$").find(t)?.let { return call("set_setting", "name" to "dnd", "value" to it.groupValues[1]) }
        Regex("^(?:turn |switch )?(?:dnd|do not disturb) $onOff$").find(t)?.let { return call("set_setting", "name" to "dnd", "value" to it.groupValues[1]) }
        Regex("^(?:set )?(?:the )?brightness (?:to )?(\\d{1,3}) ?(?:%|percent)?$").find(t)?.let { return call("set_setting", "name" to "brightness", "value" to it.groupValues[1]) }
        Regex("^(?:put (?:the )?phone (?:on|in) |set (?:the )?ringer to )?(silent|vibrate)(?: mode)?$").find(t)?.let { return call("set_setting", "name" to "ringer", "value" to it.groupValues[1]) }
        Regex("^(?:set |start )?(?:a )?timer (?:for |of )?(\\d{1,3}) ?(?:min|mins|minute|minutes)$").find(t)?.let { return call("timer_set", "minutes" to it.groupValues[1].toInt()) }
        Regex("^(?:run|start) (?:my )?(.{2,30}?)(?: skill)?$").find(t)?.let { m -> Skills.byName(m.groupValues[1])?.let { return call("skill_run", "name" to it.name) } }
        Regex("^(?:open|launch|start) (?:the )?([\\p{L}\\p{N} .+&'-]{2,28}?)(?: app)?$").find(t)?.let { m ->
            val app = m.groupValues[1].trim()
            // "open youtube and search…" is a task for the AI, not just an app to open.
            if (Regex("\\b(and|then|search|play|find|send|to|for|in|on|with|my|the)\\b").containsMatchIn(app)) return null
            return call("open_app", "name" to app)
        }
        return null
    }
}
