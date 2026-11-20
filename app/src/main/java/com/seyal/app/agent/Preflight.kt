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
            "1) which movie, and its language and 2D/3D when it runs in several (ask this now, not later)  2) which theatre (or 'any nearby')  3) which day  4) show time (or from when they are free)  5) how many tickets  6) seat class or the most to pay per ticket",
            listOf("BookMyShow", "District", "PVR", "INOX", "Paytm", "TicketNew"), "tickets",
        ),
        FOOD(
            "order food",
            "1) which dish, and its kind where it matters (veg / chicken / mutton, size)  2) how many  3) which restaurant (or 'best rated nearby')  4) budget",
            listOf("Swiggy", "Zomato", "EatSure", "Domino", "magicpin", "KFC", "McDonald", "Pizza Hut"), "portions",
        ),
        TRAVEL(
            "book a bus, train or flight",
            "1) from where  2) to where  3) which day  4) what time of day  5) how many people  6) seat type (sleeper / seater, AC / non-AC)  7) budget per seat  8) boarding point, if the city has several (or 'any')",
            listOf("redBus", "AbhiBus", "IRCTC", "ixigo", "MakeMyTrip", "ConfirmTkt", "Goibibo", "Zingbus", "Cleartrip", "EaseMyTrip", "TNSTC", "Paytm"), "people",
        ),
        CAB(
            "book a cab or auto",
            "1) pickup place (their current location is fine if they said 'from here' or nothing else)  2) drop place  3) now or at what time  4) vehicle type (auto / mini / sedan / bike)",
            listOf("Uber", "Ola", "Rapido", "Namma Yatri", "inDrive", "BluSmart"), "",
        ),
        SHOP(
            "buy or order something",
            "1) exactly which thing. When they named two with 'or', which of the two: say in a few words what each is (\"cauliflower, the vegetable\" / \"gobi chilli, the dish\"). Brand, size or colour ONLY where the thing cannot be bought without it (clothes, shoes, a phone): never for vegetables, groceries or food  2) how much or how many, in the unit that fits (kg, packets, plates, pieces)",
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
        val labels = runCatching { appLabels() }.getOrDefault(emptyList())
        val have = if (kind == Kind.SHOP) shopApps(said, labels).let { g -> g.first + g.second + g.third } else installed(kind, labels)
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

    /**
     * What a film booking request does not say, as the questions to ask, when the AI could not be reached to word
     * them: only what is really missing. Empty = everything needed was said, so nothing is asked.
     * (Seen on a real phone: "book o. m movie ticket releasing on 20th November.. any nearby theatre 2 tickets,
     * tamil, any time.. 190 per ticket" was answered with a five-part question asking for all of it again.)
     * 2D or 3D and the language are never asked here: 2D is taken, and the language is the one the film page offers.
     */
    fun missingMovie(words: List<String>, now: java.time.LocalDateTime): List<String> {
        val o = Order.fromWords(Kind.MOVIE, words, now)
        return buildList {
            if (o.item.isBlank()) add("Which movie?")
            if (o.date.isBlank()) add("Which day?")
            if (!Order.saysTime(words)) add("What time (or 'any time')?")
            if (!Order.saysPlace(words)) add("Which theatre (or 'any nearby')?")
            if (o.count <= 0 && !saysHowMany(words)) add("How many tickets? (They will be seats next to each other.)")
            if (o.maxEach <= 0 && !saysSeatOrBudget(words)) add("Which seat class, or the most to pay per ticket (or 'any')?")
        }
    }

    /**
     * What a food order does not say, as the questions to ask: only what is really missing. Empty = all of it was said.
     * (Seen on a real phone: "order dosa from nearby restaurant 2 dosa... 100 per dosa" got the four-part question
     * asking for the dish, how many, the restaurant and the budget again.) The budget is not required: without one,
     * the cheapest good match is taken and the total is shown before paying.
     */
    fun missingFood(words: List<String>): List<String> {
        val o = Order.fromWords(Kind.FOOD, words)
        return buildList {
            if (o.item.isBlank()) add("Which dish (veg / chicken / mutton, size)?")
            if (o.count <= 0 && !saysHowMany(words)) add("How many?")
            if (!Order.saysRestaurant(words)) add("Which restaurant (or 'best rated nearby')?")
        }
    }

    /** One question out of [parts]: a single one as it is, several numbered. [order]: "order" for food, else "book". */
    fun askFor(parts: List<String>, verb: String = "book"): String =
        if (parts.size == 1) parts[0] else "Before I $verb, tell me:\n" + parts.mapIndexed { i, q -> "${i + 1}) $q" }.joinToString("\n") + "\nAnswer in one line, or say 'you choose'."

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
            Kind.MOVIE -> "Before I book, tell me what you haven't said yet:\n1) Movie, language and 2D or 3D\n2) Theatre (or 'any nearby')\n3) Day and time\n4) How many tickets\n5) Seat class or the most to pay per ticket\nAnswer in one line, or say 'you choose'. (You will pick the seats yourself on the seat map; say 'pick the seats for me' if you'd rather I do.)"
            Kind.FOOD -> "Before I order, tell me what you haven't said yet:\n1) Which dish (veg / chicken / mutton, size)\n2) How many\n3) Which restaurant (or 'best rated nearby')\n4) Budget\nAnswer in one line, or say 'you choose'."
            Kind.TRAVEL -> "Before I book, tell me what you haven't said yet:\n1) From and to\n2) Day and time of day\n3) How many people\n4) Seat type (sleeper / seater, AC / non-AC)\n5) Budget per seat\nAnswer in one line, or say 'you choose'."
            Kind.CAB -> "Before I book, tell me what you haven't said yet:\n1) Pickup (or 'from here')\n2) Drop place\n3) Now, or at what time\n4) Auto / mini / sedan / bike\nAnswer in one line."
            Kind.SHOP -> "Before I order, tell me:\n1) Exactly which item\n2) How much or how many (for example 1 kg, 2 packets, 1 plate)\nAnswer in one line."
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
    /** Asking to be told, shown or reminded is not asking to buy, unless it goes on "…and book it". */
    private val lookup = Regex("^(tell me|let me know|notify me|alert me|inform me|watch|remind me|show|list|find|search|check|compare|track|any|do i|have i|can i|could i|will|would|open my|open the)\\b", RegexOption.IGNORE_CASE)
    private val thenBuy = Regex("\\b(and|then|,)\\s+(book|order|buy)\\b", RegexOption.IGNORE_CASE)
    private val other = Regex("https?://|\\b(remind|reminder|alarm|remember|note that|bookings?|orders|ordered|booked|bought)\\b", RegexOption.IGNORE_CASE)
    /** "book" as a thing to read, not a thing to do. */
    private val bookNoun = Regex("\\b(a|an|the|this|that|my|your|any|one|some|which|what|text|note|story|comic|e|audio|good|new|best|pdf)[ -]book\\b|\\bbook (name|title|author|pdf|summary|review|list|about|called|by)\\b|\\b(read|reading|summari[sz]e|write|download)\\b.{0,30}\\bbook\\b", RegexOption.IGNORE_CASE)
    private val buyVerb = Regex("\\b(buy|purchase|vaangu|vangu|vaangi)\\b|வாங்கு|வாங்கி", RegexOption.IGNORE_CASE)
    private val orderVerb = Regex("\\border\\b|ஆர்டர்", RegexOption.IGNORE_CASE)

    /** [kind] for a chat in Dirghadarshi mode ([deep]): there a question is never an order (see Deep.asksOnly). */
    fun kind(text: String, deep: Boolean): Kind? = if (deep && Deep.asksOnly(text)) null else kind(text)

    fun kind(text: String): Kind? {
        // "Dirghadarshi: should I buy…" is the question "should I buy…" asked in the thinking mode.
        val t = Deep.strip(text).trim()
        if (t.length < 6 || notBuying.containsMatchIn(t) || other.containsMatchIn(t)) return null
        if (lookup.containsMatchIn(t) && !thenBuy.containsMatchIn(t)) return null
        // "Read a book", "summarise this book": nothing is being bought unless it also says buy or order.
        if (bookNoun.containsMatchIn(t) && !buyVerb.containsMatchIn(t) && !orderVerb.containsMatchIn(t)) return null
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
            // "buy gobi chilli", "order paneer butter masala": a cooked dish comes from a restaurant, not from a shop.
            sells(t) == Sells.DISH -> Kind.FOOD
            // "Book" or "reserve" with nothing recognisable after it (a table, a slot, an appointment) is not a shop order.
            buyVerb.containsMatchIn(t) || orderVerb.containsMatchIn(t) -> Kind.SHOP
            else -> null
        }
    }

    // ---- buying a thing: what kind of thing it is decides the app, and the user is never asked which app

    /** Who sells a thing: a restaurant (a cooked dish), a grocery app (vegetables, fruit, milk, daily needs), or a shopping app. MIXED: the words name more than one kind. */
    enum class Sells { DISH, GROCERY, GOODS, MIXED }

    private val I = RegexOption.IGNORE_CASE
    /** Cooked dishes that only a restaurant sells. Things also sold in a pack (pasta, soup, chapati) are left out on purpose. */
    private val dishWords = Regex(
        "\\b(gobi (?:chill?[iy]|manchurian|65|fry|masala)|chill?[iy] (?:gobi|chicken|paneer|parotta|mushroom|potato|fish|prawn|egg|idli|beef)|manchurian|(?:chicken|gobi|paneer|mushroom|fish|prawn) 65|" +
            "pulao|uttapam|pongal|vada|vadai|bonda|bajji|pakoda|pakora|samosa|naan|kurma|korma|gravy|curry(?! leaves| powder| leaf| paste)|butter chicken|butter masala|tikka|kebab|kabab|tandoori|shawarma|" +
            "(?:egg|chicken|paneer|veg|kathi|spring|mutton) rolls?|(?:egg|veg|chicken) puffs?|sandwich|momos?|chaat|pani puri|pav bhaji|thali|omelette|milkshake|lassi|falooda)\\b",
        I,
    )
    /** The same word as something for the kitchen shelf: "curry paste", "vada mix", "frozen samosa". */
    private val formWords = Regex("\\b(powder|mix|paste|batter|raw|frozen|instant|ready ?made|packets?|packs?|pkt)\\b", I)
    /** …or as a thing to cook it with: "sandwich maker", "dosa tawa". */
    private val toolWords = Regex("\\b(makers?|pan|tawa|machine|toaster|grill|griller|oven|cookers?|boilers?|dispenser|grinder|mixer|juicer|kettle|flask|jars?|containers?|baskets?|rack|cutter|slicer|peeler|knife|mould|book|box|plates?|stand|mugs?|cups?|case|cover|toys?)\\b", I)
    private val groceryWords = Regex(
        "\\b(vegetables?|veggies|fruits?|grocer\\w+|provisions?|cauliflower|gobi|cabbage|onions?|tomato(?:es)?|potato(?:es)?|carrots?|beans|brinjal|ladies finger|okra|drumsticks?|spinach|keerai|coriander|curry lea(?:f|ves)|mint|ginger|garlic|" +
            "chill?(?:i|ies|y)|lemons?|coconuts?|bananas?|apples?(?! (?:iphone|ipad|mac\\w*|watch|airpods|pencil|tv|phone|laptop|charger|earphones|store))|mango(?:es)?|oranges?|grapes|pomegranates?|papaya|watermelon|milk|curd|paneer|butter|ghee|cheese|eggs?|bread|rice|atta|maida|rava|flour|dal|dhal|sugar|salt|oil|" +
            "tea|coffee|biscuits?|chips|maggi|soap|shampoo|detergent|toothpaste|tissues?|diapers?|" +
            "thakkali|vengayam|urulai\\w*|kathiri\\w*|vendakk?ai|murungakk?ai|kaaikari|kaikari|keerai|pazham|paal|thayir|muttai|arisi|paruppu|sakkarai|uppu|ennai)\\b",
        I,
    )

    private fun sellsOne(t: String): Sells {
        val dish = dishWords.containsMatchIn(t)
        val form = formWords.containsMatchIn(t)
        val grocery = groceryWords.containsMatchIn(t)
        return when {
            // "sandwich maker", "rice cooker", "milk jar": a thing for the kitchen, not something to eat.
            (dish || grocery) && toolWords.containsMatchIn(t) -> Sells.GOODS
            dish && !form -> Sells.DISH
            dish || grocery -> Sells.GROCERY
            else -> Sells.GOODS
        }
    }

    /**
     * What kind of seller has the thing [text] names. A guess by rule: it orders the apps and sends a plain cooked
     * dish to the food apps; the AI and the user's answer decide the rest. "A or B" of two kinds is MIXED.
     */
    fun sells(text: String): Sells {
        val parts = text.split(Regex("\\s+or\\s+", I))
        if (parts.size == 2) { val a = sellsOne(parts[0]); val b = sellsOne(parts[1]); return if (a == b) a else Sells.MIXED }
        return sellsOne(text)
    }

    private val groceryApps = listOf("Blinkit", "Zepto", "Instamart", "bigbasket", "JioMart", "DMart")

    /** The apps on this phone for buying a thing, as (first choice, second, the rest) by what [said] names. */
    internal fun shopApps(said: String, labels: List<String>): Triple<List<String>, List<String>, List<String>> {
        val food = installed(Kind.FOOD, labels)
        val grocery = groceryApps.mapNotNull { want -> labels.firstOrNull { it.contains(want, true) } }.distinct()
        val goods = installed(Kind.SHOP, labels).filter { it !in grocery }
        return when (sells(said)) {
            Sells.DISH -> Triple(food, grocery, goods)
            Sells.MIXED -> Triple(grocery, food, goods)
            Sells.GROCERY -> Triple(grocery, goods, emptyList())
            Sells.GOODS -> Triple(goods, grocery, emptyList())
        }
    }

    /** The apps as the AI is told them for a purchase: by what each kind of app sells, with the rule that the app is never asked about. */
    internal fun shopAppsLine(said: String, labels: List<String> = runCatching { appLabels() }.getOrDefault(emptyList())): String {
        fun list(l: List<String>) = if (l.isEmpty()) "(none on this phone)" else l.joinToString(", ")
        val food = installed(Kind.FOOD, labels)
        val grocery = groceryApps.mapNotNull { want -> labels.firstOrNull { it.contains(want, true) } }.distinct()
        val goods = installed(Kind.SHOP, labels).filter { it !in grocery }
        return "APPS ON THIS PHONE, by what they sell: a cooked dish from a restaurant: ${list(food)} · vegetables, fruit, milk, groceries, daily needs: ${list(grocery)} · everything else (clothes, electronics, books, things for the house): ${list(goods)}. " +
            "THINK about what the thing is and where a person really gets it: a dish is not bought on Amazon, a vegetable not on Flipkart, a phone not on Swiggy. Use the app the user named, otherwise the first app of the right group, and write it in details_complete's app. " +
            "NEVER ask the user which app or shop, and never ask for a budget: the price is shown to them before anything is paid."
    }

    private val eitherOr = Regex("\\b(?:buy|order|purchase|get|vaangu|vangu)\\s+(?:me\\s+|us\\s+)?(?:(?:a|an|some|one)\\s+)?(.{2,30}?)\\s+or\\s+(.{2,30}?)(?=\\s*(?:[,.;!?]|$)|\\s+(?:from|for|under|below|on|in|at|today|tomorrow|now)\\b)", I)

    /** "buy cauliflower or gobi chilli" → (cauliflower, gobi chilli): two things, of which the user wants one. */
    fun either(said: String): Pair<String, String>? = eitherOr.find(said.trim())?.let { it.groupValues[1].trim() to it.groupValues[2].trim() }?.takeIf { it.first.isNotBlank() && it.second.isNotBlank() }

    /** Whether the user said how much by weight, volume or pack ("1 kg", "half litre", "2 packets", "a dozen"). */
    fun saysHowMuch(texts: List<String>): Boolean = texts.any { Regex("\\b(?:\\d+(?:\\.\\d+)?|half|quarter|one|two|three|a|kaal|arai|oru)\\s?(?:kgs?|kilos?|grams?|gms?|g|litres?|liters?|l|ml|packets?|packs?|pkt|dozen|bunch(?:es)?|bottles?|plates?|pieces?|pcs|nos?|boxes|box)\\b", I).containsMatchIn(it) }

    /**
     * What a "buy…" request does not say, as the questions to ask: which of two things, and how much. Never the app
     * and never the budget. (Seen on a real phone: "buy cauliflower or gobi chilli" was asked four things, the last
     * being "Which app or shop? (Amazon / Flipkart / Blinkit)".) Empty = nothing to ask.
     */
    fun missingShop(words: List<String>): List<String> {
        val first = words.firstOrNull().orEmpty()
        val two = either(first)
        return buildList {
            if (two != null && words.size == 1) add("Which one: ${two.first} or ${two.second}?")
            else if (words.size == 1 && Order.itemFromWords(words).isBlank() && first.trim().split(Regex("\\s+")).size <= 3) add("Exactly which item?")
            if (!saysHowMany(words) && !saysHowMuch(words)) add(if (sells(words.joinToString(" ")) == Sells.GOODS) "How many?" else "How much or how many? (for example 1 kg, 2 packets, 1 plate)")
        }
    }

    private val notAsked = Regex("\\b(apps?|shops?|stores?|websites?|sites?|budget|price range|maximum|max|how much (?:do|can|would|will) you (?:want to )?(?:spend|pay)|amazon|flipkart|blinkit|zepto|instamart|bigbasket|meesho|myntra|swiggy|zomato)\\b", I)

    /**
     * The question for a "buy…" request: the AI's own wording without any line about the app, the shop or the budget;
     * the built-in one when nothing of the AI's is left; null when the user's words already say it all.
     */
    fun shopQuestion(fromAi: String?, words: List<String>): String? {
        val goods = sells(words.joinToString(" ")) == Sells.GOODS
        val missing = missingShop(words)
        fun ours() = missing.takeIf { it.isNotEmpty() }?.let { askFor(it, "order") }
        // Food and groceries need only the thing and the amount: both said, nothing to ask (no brand of a vegetable).
        // Other goods may need a size or a variant, so the AI's question about that stays.
        if (missing.isEmpty() && !goods) return null
        val text = fromAi.orEmpty().trim()
        val numbered = text.lines().map { it.trim() }.filter { Regex("^\\d{1,2}\\s*[.)]").containsMatchIn(it) }
        // One plain question from the AI that is not about the app or the budget stays as it is.
        if (numbered.isEmpty()) return if (text.isNotBlank() && !notAsked.containsMatchIn(text)) text else ours()
        val kept = numbered.filter { !notAsked.containsMatchIn(it) }.map { it.replace(Regex("^\\d{1,2}\\s*[.)]\\s*"), "") }
        if (kept.isEmpty() || (!goods && kept.size > missing.size)) return ours()
        return if (kept.size == 1) kept[0] else kept.mapIndexed { i, q -> "${i + 1}) $q" }.joinToString("\n") + "\nAnswer in one line."
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
${if (kind == Kind.SHOP) shopAppsLine(said) else "APPS ON THIS PHONE FOR THIS: " + (if (apps.isEmpty()) "(none found)" else apps.joinToString(", ")) + ". The app is not a detail to ask about: the one the user named is used, otherwise the first of these."}
KNOWN ABOUT THE USER: ${Memory.text().ifBlank { "(nothing)" }.take(600)}
LAST TIME (this user's last order of this kind that went through): ${Usual.text(kind.name).ifBlank { "(none yet)" }}. If they say "same as last time" or "the usual", those details count as given (never the day or the time). Otherwise you may offer it as the first choice in your question.

Go through NEEDED one by one against the user's LATEST request and their answers since. Earlier requests in this chat are finished: their numbers, days and places do not carry over. What is known about the user may fill in a favourite place or app, never how many, which day, what time or what price:
- A detail counts as given only if the user said it. Never fill one in yourself: not the number of tickets or items, not the time, not the price, not the language.
- If one or more are missing: call ask_user ONCE. Its question lists every missing detail as a numbered line with two or three example choices, and ends with: "Answer in one line, or say 'you choose'." Do not ask for anything already given. Leave options empty.
- If nothing is missing, or the user said to choose for them: call details_complete, writing each detail exactly as the user gave it (the date as YYYY-MM-DD worked out from NOW; empty or 0 for what they left open).
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
