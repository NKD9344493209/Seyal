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
    /** [apps]: the apps that usually do this, by the name on their icon (used to see which are on the phone). */
    enum class Kind(val what: String, val details: String, val apps: List<String>, val unit: String) {
        MOVIE(
            "book movie tickets",
            "1) which movie (and which language, if they didn't say and it could be more than one)  2) which theatre (or 'any nearby')  3) which day  4) show time (or from when they are free)  5) how many tickets  6) seat class or the most to pay per ticket",
            listOf("BookMyShow", "District", "PVR", "INOX", "Paytm", "TicketNew"), "tickets",
        ),
        FOOD(
            "order food",
            "1) which dish, and its kind where it matters (veg / chicken / mutton, size)  2) how many  3) which restaurant (or 'best rated nearby')  4) budget",
            listOf("Swiggy", "Zomato", "EatSure", "Domino", "magicpin", "KFC", "McDonald", "Pizza Hut"), "portions",
        ),
        TRAVEL(
            "book a bus, train or flight",
            "1) from where  2) to where  3) which day  4) what time of day  5) how many people  6) seat type (sleeper / seater, AC / non-AC)  7) budget per seat",
            listOf("redBus", "AbhiBus", "IRCTC", "ixigo", "MakeMyTrip", "ConfirmTkt", "Goibibo", "Zingbus", "Cleartrip", "EaseMyTrip", "TNSTC", "Paytm"), "people",
        ),
        CAB(
            "book a cab or auto",
            "1) pickup place (their current location is fine if they said 'from here' or nothing else)  2) drop place  3) now or at what time  4) vehicle type (auto / mini / sedan / bike)",
            listOf("Uber", "Ola", "Rapido", "Namma Yatri", "inDrive", "BluSmart"), "",
        ),
        SHOP(
            "buy or order something",
            "1) exactly which item (brand, variant, size or colour where it matters)  2) how many  3) budget  4) which app or shop, if they care",
            listOf("Amazon", "Flipkart", "Meesho", "Myntra", "Blinkit", "Zepto", "Instamart", "bigbasket", "JioMart", "Ajio", "Nykaa", "Tata Neu", "DMart"), "items",
        ),
    }

    /** The names of the apps on the phone (set when the app starts; empty in tests). */
    @Volatile var appLabels: () -> List<String> = { emptyList() }

    /** Which of the apps that can do [kind] are among [labels], in the order of preference. */
    fun installed(kind: Kind, labels: List<String>): List<String> =
        kind.apps.mapNotNull { want -> labels.firstOrNull { it.contains(want, true) } }.distinct()

    /** The same, for this phone. If the user named an app in [said], it comes first. */
    fun appsFor(kind: Kind, said: String = ""): List<String> {
        val have = installed(kind, runCatching { appLabels() }.getOrDefault(emptyList()))
        return have.sortedBy { if (said.contains(it, true) || said.contains(it.substringBefore(' '), true)) 0 else 1 }
    }

    // ---- what the user really said about "how many" (the one detail a wrong guess makes expensive)

    private val notACount = Regex(
        "\\b\\d{1,2}(:\\d{2})?\\s*(am|pm|a\\.m|p\\.m|o'?clock|mani|manikku)\\b|\\b\\d{1,2}:\\d{2}\\b|(₹|rs\\.?|inr|rupees?)\\s*\\d+|\\d+\\s*(rs\\b|rupees?|ரூ|/-|k\\b|km|kms|kg|g\\b|ml|l\\b|inch|gb|mins?|minutes?|hours?|hrs?|days?|d\\b)" +
            "|\\b(under|below|within|max|maximum|upto|up to|budget|from|after|before|at|by|till|until)\\s+\\d+(:\\d{2})?|\\b\\d{1,2}(st|nd|rd|th)\\b|\\b\\d{3,}\\b|\\b\\d{1,2}[-/.]\\d{1,2}([-/.]\\d{2,4})?\\b|\\b(nov|dec|jan|feb|mar|apr|may|jun|jul|aug|sep|oct)\\w*\\s+\\d{1,2}\\b|\\b\\d{1,2}\\s+(nov|dec|jan|feb|mar|apr|may|jun|jul|aug|sep|oct)\\w*",
        RegexOption.IGNORE_CASE,
    )
    private val countWord = Regex(
        "\\b\\d{1,2}\\b|\\b(one|two|three|four|five|six|seven|eight|nine|ten|single|couple|both of us|just me|only me|myself|alone|onnu|rendu|irandu|moonu|moondru|naalu|naangu|anju|ainthu|aaru|ezhu|ettu)\\b|ஒன்று|ஒண்ணு|இரண்டு|ரெண்டு|மூன்று|மூணு|நான்கு|நாலு|ஐந்து|அஞ்சு|ஆறு",
        RegexOption.IGNORE_CASE,
    )
    private val leaves = Regex("\\b(you (choose|decide|pick)|your (choice|wish)|anything( is fine)?|whatever|up to you|nee(ye)? (choose|decide|paathu)|unga ishtam|un ishtam|edhavadhu|ethavathu)\\b|உங்க இஷ்டம்|எதாவது", RegexOption.IGNORE_CASE)

    /** Whether the user said how many, in any of [texts] (times, dates, prices and distances are not counts). */
    fun saysHowMany(texts: List<String>): Boolean = texts.any { countWord.containsMatchIn(notACount.replace(it, " ")) }

    private val seatOrBudget = Regex(
        "₹|\\brs\\.?\\b|\\brupees?\\b|ரூ|\\b(under|below|within|max|maximum|upto|up to|budget|cheap\\w*|costly|costliest|low(est)? price|premium|prime|recliner|balcony|gold|silver|platinum|diamond|classic|executive|elite|royal|club|first class|second class|front rows?|back rows?|middle rows?|last rows?|any (seat|seats|class|price|type)|price (doesn'?t|does not) matter|normal seats?)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Whether the user said which seats or how much to pay ("balcony", "under 200", "any seat"). */
    fun saysSeatOrBudget(texts: List<String>): Boolean = texts.any { seatOrBudget.containsMatchIn(it) }

    /**
     * The short question for what the user certainly did not say, whatever the AI thought:
     * how many ([needCount]) and, for a movie, which seats or how much to pay ([needSeat]).
     */
    fun missingQuestion(kind: Kind, needCount: Boolean, needSeat: Boolean): String? = when {
        needCount && needSeat -> "Two things before I book:\n1) How many tickets? (They will be seats next to each other.)\n2) Which seat class, or the most to pay per ticket? (or 'any')\nAnswer in one line."
        needSeat -> "Which seat class, or the most you want to pay per ticket? (or say 'any')"
        needCount -> fallbackQuestion(kind, onlyCount = true)
        else -> null
    }

    /** Whether the user left the choice to Seyal ("you choose", "unga ishtam"). */
    fun leavesChoice(texts: List<String>): Boolean = texts.any { leaves.containsMatchIn(it) }

    /**
     * The question Seyal asks by itself when the AI could not be reached for this step, or said
     * "everything is known" although nobody said how many. Plain and complete, so the task never
     * starts on guesses.
     */
    fun fallbackQuestion(kind: Kind, onlyCount: Boolean): String = when {
        onlyCount && kind == Kind.MOVIE -> "How many tickets? (They will be seats next to each other.)"
        onlyCount && kind == Kind.TRAVEL -> "How many people are travelling?"
        onlyCount -> "How many do you want?"
        else -> when (kind) {
            Kind.MOVIE -> "Before I book, tell me what you haven't said yet:\n1) Movie and language\n2) Theatre (or 'any nearby')\n3) Day and time\n4) How many tickets\n5) Seat class or the most to pay per ticket\nAnswer in one line, or say 'you choose'."
            Kind.FOOD -> "Before I order, tell me what you haven't said yet:\n1) Which dish (veg / chicken / mutton, size)\n2) How many\n3) Which restaurant (or 'best rated nearby')\n4) Budget\nAnswer in one line, or say 'you choose'."
            Kind.TRAVEL -> "Before I book, tell me what you haven't said yet:\n1) From and to\n2) Day and time of day\n3) How many people\n4) Seat type (sleeper / seater, AC / non-AC)\n5) Budget per seat\nAnswer in one line, or say 'you choose'."
            Kind.CAB -> "Before I book, tell me what you haven't said yet:\n1) Pickup (or 'from here')\n2) Drop place\n3) Now, or at what time\n4) Auto / mini / sedan / bike\nAnswer in one line."
            Kind.SHOP -> "Before I order, tell me what you haven't said yet:\n1) Exactly which item (brand, size, colour)\n2) How many\n3) Budget\n4) Which app, if you care\nAnswer in one line, or say 'you choose'."
        }
    }

    private val verb = Regex("\\b(book|order|buy|purchase|reserve|vaangu|vangu|vaangi|order pannu|book pannu)\\b|ஆர்டர்|புக்|வாங்கு|வாங்கி", RegexOption.IGNORE_CASE)
    /** Softer ways of asking: a purchase only when a ticket or a cab is named with them. */
    private val softVerb = Regex("\\b(get|grab|need|want|arrange|venum|vendum|venu|edu|eduthu|podu)\\b|வேணும்|வேண்டும்|எடு", RegexOption.IGNORE_CASE)
    private val ticket = Regex("\\b(tickets?|seats?)\\b|டிக்கெட்", RegexOption.IGNORE_CASE)
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
        if (t.length < 6 || notBuying.containsMatchIn(t)) return null
        if (!verb.containsMatchIn(t)) {
            // "Get me 2 tickets for Ramayana", "rendu ticket venum", "I need a cab to the airport": no "book", same wish.
            if (!softVerb.containsMatchIn(t)) return null
            return when {
                ticket.containsMatchIn(t) && travel.containsMatchIn(t) -> Kind.TRAVEL
                ticket.containsMatchIn(t) -> Kind.MOVIE
                cab.containsMatchIn(t) -> Kind.CAB
                else -> null
            }
        }
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

    fun system(kind: Kind, said: String = ""): String {
        val apps = appsFor(kind, said)
        return """
You are the first step of a phone assistant. The user wants to ${kind.what}. Before anything is opened, every detail below must come from the user.

NEEDED: ${kind.details}

NOW: ${Times.nowText()}. Home city: ${Settings.homeCity.ifBlank { "unknown" }}.
APPS ON THIS PHONE FOR THIS: ${if (apps.isEmpty()) "(none found)" else apps.joinToString(", ")}. The app is not a detail to ask about: the one the user named is used, otherwise the first of these.
KNOWN ABOUT THE USER: ${Memory.text().ifBlank { "(nothing)" }.take(600)}

Go through NEEDED one by one against what the user wrote in this conversation (things known about the user count):
- A detail counts as given only if the user said it. Never fill one in yourself: not the number of tickets or items, not the time, not the price, not the language.
- If one or more are missing: call ask_user ONCE. Its question lists every missing detail as a numbered line with two or three example choices, and ends with: "Answer in one line, or say 'you choose'." Do not ask for anything already given. Leave options empty.
- If nothing is missing, or the user said to choose for them: call details_complete.
Write the question in the language the user wrote in.
        """.trimIndent()
    }
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
