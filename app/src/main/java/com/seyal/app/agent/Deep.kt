package com.seyal.app.agent

/**
 * Dirghadarshi (தீர்க்கதரிசி, "one who sees far"): the chat's careful-thinking mode, switched on beside the
 * chat box or by starting a message with its name. A request in this mode is checked against sources before
 * it is answered, thought through for what comes later and what can go wrong, and reviewed once by the AI
 * itself before the user sees it. It is slower on purpose.
 *
 * Everything here is plain text and rules, so it can be tested without a phone.
 */
object Deep {
    private val I = RegexOption.IGNORE_CASE

    /** The name as people spell it: dirghadarshi, dheergadharisi, dheerkadharisi, dheergadhirisi, theerkatharisi… */
    private const val NAME = "(?:d|th)h?(?:ee|ii|i|e)r+(?:gh?|kh?)a?(?:d|th)h?[ai]r[ai]?(?:sh?|c)[iy]"
    private val named = Regex("(?<![\\p{L}])$NAME(?![\\p{L}])|தீர்க்கதரிசி|தீர்க்கதரிசன", I)
    private val slash = Regex("^\\s*/(?:think|deep)(?![\\p{L}])|^\\s*think (?:deep|deeply|hard|far|ahead|carefully)\\s*[:,-]", I)

    /** Whether the message itself asks for the mode ("Dirghadarshi: should I…", "use dheergadharisi mode…", "/think …"). */
    fun wants(text: String): Boolean = named.containsMatchIn(text.take(400)) || slash.containsMatchIn(text)

    private val lead = Regex("^\\s*(?:(?:please\\s+)?(?:use|in|with|on|run|start)\\s+)?(?:$NAME|தீர்க்கதரிசி)(?:\\s+mode)?\\s*(?:[:,.-]+|\\bla\\b)?\\s*|^\\s*/(?:think|deep)\\s+|^\\s*think (?:deep|deeply|hard|far|ahead|carefully)\\s*[:,-]\\s*", I)

    /** [text] without the mode's name in front of it: "Dirghadarshi: should I buy…" → "should I buy…". Unchanged when it does not start with it. */
    fun strip(text: String): String = lead.replaceFirst(text, "").ifBlank { text }

    /** Things that change, so an answer from memory is a guess: prices, rules, dates, what is best or new, safety, money, jobs. */
    private val changes = Regex(
        "\\b(price|prices|cost|costs|fees?|fare|rate|rates|salary|salaries|stipend|package|offer|offers|discount|emi|interest|tax|gst|" +
            "best|better|top|worth|vs|versus|compare|comparison|alternative|alternatives|review|reviews|rating|" +
            "latest|newest|new|current|currently|now|today|this (?:week|month|year)|next (?:week|month|year)|upcoming|release|launch|version|update|" +
            "202\\d|rule|rules|law|legal|allowed|eligib\\w*|deadline|last date|exam|admission|visa|scheme|policy|" +
            "safe|safety|scam|fraud|genuine|fake|trusted|risk|risky|side effects?|" +
            "buy|buying|invest\\w*|loan|insurance|job|jobs|hiring|internship|placement|company|startup|course|college|" +
            "trend\\w*|rising|future|will it|should i|is it (?:good|safe|worth|true|legal))\\b|விலை|பாதுகாப்|வேலை",
        I,
    )
    /** Tools that count as having looked something up. */
    val lookups = setOf(
        "web_search", "read_page", "news", "weather", "wikipedia", "foresight", "awareness", "read_mail", "search_mail", "past_search", "chats_search",
        "money_summary", "calendar_list", "notifications_read", "files_search", "phone_state", "app_usage", "location",
    )

    /** Whether [question] is about something that can change, so it must be looked up before it is answered. */
    fun needsLookup(question: String): Boolean = question.trim().length >= 12 && changes.containsMatchIn(question)

    /** Whether [toolsUsed] in this request include a look-up. */
    fun lookedUp(toolsUsed: Collection<String>): Boolean = toolsUsed.any { it in lookups }

    /**
     * Whether a finished [draft] should get the second, critical reading. Not for work done on the screen
     * (a booking is checked against the order instead), and not for a one-line result.
     */
    fun worthReview(draft: String, usedScreen: Boolean, hasOrder: Boolean): Boolean =
        !usedScreen && !hasOrder && draft.trim().length >= 120

    private val doIt = Regex(
        "^(?:please |pls |can you |could you )?(?:send|call|ring|message|text|whatsapp|e-?mail|mail|remind|set|open|play|turn|switch|book|order|add|delete|remove|share|post|schedule|navigate|pause|resume|stop|start|mute|unmute)\\b",
        I,
    )

    private val orderIt = Regex(
        "^(?:please |pls |kindly |can you |could you |go ahead and |ok |okay |now )*(?:book|order|buy|purchase|reserve|get me|get us|i want to (?:book|order|buy)|i need to (?:book|order|buy)|i (?:want|need) (?:\\d+|a|an|two|three) )\\b" +
            "|\\b(?:book it|order it|buy it|place the order|go ahead|add (?:it )?to (?:the |my )?cart)\\b|வாங்கு|புக் பண்ணு|ஆர்டர் பண்ணு",
        I,
    )
    private val asking = Regex(
        "\\?|\\b(?:what|when|which|why|whether|how much|how many|how is|how are|is it|is this|is that|is now|are there|should i|shall i|can i|could i|will|would|does|do you think|did|tell me|explain|compare|" +
            "best time|good time|right time|worth|price of|rate of|cost of|today'?s? (?:rate|price)|current (?:rate|price)|cheapest|trend|forecast|predict\\w*|advice|suggest\\w*|recommend\\w*|enna|evlo|eppo|epdi|yen|sollu)\\b|என்ன|எவ்வளவு|எப்போ",
        I,
    )

    /**
     * In this mode a question is a question: "best time to buy the cheapest gold", "what is the price of gold",
     * "should I buy now" ask for facts and a judgement, and nothing is to be ordered. (On a real phone such a
     * question was met with "which app, how many?" and Amazon and Flipkart.) A request counts as an order here only
     * when it is worded as one ("book 2 tickets…", "order…", "buy me…", "go ahead") and asks nothing.
     */
    fun asksOnly(said: String): Boolean {
        val t = strip(said).trim()
        if (t.isEmpty()) return false
        return !orderIt.containsMatchIn(t) || (asking.containsMatchIn(t) && !Regex("^(?:please |pls |kindly |can you |could you )*(?:book|order|reserve)\\b", I).containsMatchIn(t))
    }

    /** A plain instruction ("send…", "set an alarm…", "open…"): something to do, not something to think through. */
    fun isAction(said: String): Boolean = doIt.containsMatchIn(strip(said).trim())

    /**
     * Whether one AI step should think hard: the answer and the plan. Not each tap inside an app (that made every
     * step take a minute), and not a plain instruction typed while the switch happened to be on.
     */
    fun thinkHard(deep: Boolean, usedScreen: Boolean, hasOrder: Boolean, said: String = ""): Boolean = deep && !usedScreen && !hasOrder && !isAction(said)

    /** The short answer: the default. The user asked for few words, exact, and more only on request. */
    const val SHORT_SHAPE = """ANSWER SHAPE: SHORT. Every word must earn its place.
**Answer**: one or two lines. The decision or the fact itself, with the exact figure and its date. When they ask you to choose (yes or no, now or wait, this or that), CHOOSE: the first word is the choice. "It depends" and "there is no simple answer" are not answers: the doubt goes under How sure.
**Why**: at most 3 bullets, each a checked fact with its number, its date and the name of its source.
**Risk**: the one thing most likely to make this wrong or costly → the precaution.
**How sure**: High, Medium or Low, with the reason in one line.
**Sources**: the pages you actually read, one per line as [name of the page](link).
Nothing else: no introduction, no repeating the question, no advice that was not asked for. About 80 words before Sources. The last line is exactly: Say "more" for the full view."""

    /** The full answer: only when the user asks for more. */
    const val FULL_SHAPE = """ANSWER SHAPE: FULL (the user asked for more). Plain words, short lines; leave a part out when you have nothing real for it; never pad:
**Answer**: the direct answer or recommendation first, in 1 to 3 lines. When they ask you to choose, CHOOSE: the first word is the choice.
**Look ahead**: what is likely next and how it changes things.
**Watch out**: up to 3 risks, each with its precaution ("→ do this").
**You may not know**: 1 or 2 things.
**Do now**: 1 to 3 concrete steps, the first one doable today. Offer to do the ones you can do on this phone (a reminder, a calendar entry, a message, a search).
**How sure**: High, Medium or Low, why, and what would change your mind.
**Sources**: the pages you actually read, one per line as [name of the page](link)."""

    private val more = Regex(
        "^(?:more|tell me more|say more|go on|continue|explain|explain (?:it|this|that|more|in detail|fully)|elaborate|expand|extend|extend it|in detail|detail|details|full|full view|full answer|the full view|why|why so|how so|" +
            "innum|innum sollu|virivaa sollu|detail ah sollu|detail la sollu|விரிவாக|விரிவா சொல்லு|மேலும்)[.!? ]*$" +
            "|\\b(?:in (?:more )?detail|in depth|elaborate|explain (?:more|fully|in detail)|full (?:view|answer|analysis|picture|details?)|long(?:er)? (?:answer|version)|extend (?:it|this|the answer))\\b",
        I,
    )

    /** The user asks for the long answer ("more", "explain in detail", "full view"). */
    fun wantsMore(said: String): Boolean = more.containsMatchIn(strip(said).trim())

    /** The mode's rules for the system prompt: the short shape unless [full]. */
    fun system(full: Boolean): String = SYSTEM.replace("%SHAPE%", if (full) FULL_SHAPE else SHORT_SHAPE)

    /** The mode's rules, with a place for the answer shape (see [system]). */
    val SYSTEM = """
DIRGHADARSHI MODE IS ON (தீர்க்கதரிசி: one who sees far). The user switched it on because they want your most careful thinking, not your fastest. For this request:
1. Understand the need behind the words. If ONE missing fact would change the answer (budget, deadline, who it is for), ask it once with ask_user; otherwise say your assumption in one line.
2. Check before you say. Anything that can change (prices, rules, dates, versions, offers, availability, news, what is best now) → web_search or read_page FIRST, and two independent sources for what matters most. Only when the question is about what is new or rising in technology, AI or jobs, call foresight (it takes half a minute: never for other subjects such as gold, phones or health). For questions about the user's own life, use their data (memory, messages, calendar, money tools). Never answer such things from memory.
3. Think ahead in time: what happens next if they do this, in a week, in some months, in a year. What is changing in the world that makes this choice better or worse later.
4. Think about what can go wrong: the likely failure, the costly failure, the scam or trap, the fine print, the step that cannot be undone. Give the precaution for each.
5. Say what they may not know: a cheaper, safer or better alternative, a hidden cost, a deadline, a rule, a better time to do it.

%SHAPE%

PRECISION (the user makes real decisions from this; a wrong answer is worse than no answer):
- Exact figures, with unit and date ("₹1,35,400 per 10 g on 18 Nov 2026"). Not "around", "soon", "some", "recently" when a source has the number. When sources disagree, give both figures and say they disagree.
- Every number, date, price and name you write must be in a tool result of this conversation or in the user's own words. What you could not find is left out or marked "not verified". Your own arithmetic is shown as such ("by my sum: 190 × 2 = 380").
- The figure the decision rests on needs two independent sources. Read the page, not only the search snippet. More searches are fine: take the time.
- If what you found cannot support a choice, say "I could not verify enough to say", and name what is missing. Never fill the gap with a guess.
- A question that can be read two ways ("when the gold rate decreases": under what conditions, or when will it): answer the reading a person deciding something needs (here: can anyone say when, and what to watch), and say in the first line which reading you took. Nobody can date a market move: say so plainly instead of implying a date.
- Whose word counts: a central bank, a regulator, an exchange, the company itself, a standards body, or a news agency with editors (Reuters, PTI, AP, a national daily) comes first. A dealer's blog, an affiliate site or an unknown site is used only with a second source beside it, or marked "(one source, a dealer's site)". Name the source by what it is.
- Dates of pages: every page in a tool result carries "dated", the day the page itself says it is from (empty = it shows none). Give today's figure only from a page dated today or yesterday, and write that date beside it. A page from another year with the same day and month ("November 20" written in 2025) is the commonest mistake: check the year. When a result's note says no page of today was found, your first line says you could not confirm it for today.
- A local price (gold, silver, fuel): name the purity or grade, the unit and the city (the user's city when the context gives it). 22K and 24K gold are different figures; per gram and per 10 g are different figures: never mix them, and never work one out from the other without saying "by my sum".
- A question is a question. "Best time to buy…", "what is the price of…", "should I buy…" ask for facts and a judgement: answer them. Never ask which app or how many, never offer shops or carts, unless the user tells you to buy, book or order.
- The user lives in India: give money in rupees and Indian units as well (gold per gram or per 10 g in the user's city when a source has it, not only dollars per ounce), and say what is different here (the rupee, import duty, GST, local rules).
- Never name a share, a coin or a bet to buy or sell. For other money questions (gold, a house, a big purchase, a loan) do give your lean, as what a careful person would do and why, with the main risk, and one line saying it is general information and the choice is theirs. For a health, legal or money matter of weight, say when a professional must be asked.

WHEN THE REQUEST IS AN ACTION on the phone (book, order, buy, send, post, delete, install, share): do it as usual, and also (a) before the step that commits, read the screen for the total with every fee, the date and time, the count, who receives it, and the cancel or refund rule; (b) if something looks off (a price above what was said, the wrong day, a badly rated seller, a link that is not the real site, anyone asking for an OTP or PIN), stop and ask; (c) end with "Check before you pay" (or send): the 2 or 3 things the user should see with their own eyes.
""".trim()

    /** Sent when the AI answered a changing question without looking anything up. */
    const val LOOKUP_NUDGE =
        "Dirghadarshi mode: you answered from memory, and this is about things that change. Look it up now (web_search, read_page, foresight, or the user's own data), " +
            "then answer from what you found. If a search finds nothing, say that plainly instead of guessing."

    /** Sent once after the first full answer: the AI reads its own draft as a critic, then writes the final answer. */
    const val REVIEW_NUDGE =
        "Dirghadarshi review, before the user sees anything. The user will decide something real from this, so read your draft above as a careful critic:\n" +
            "1. Go through EVERY number, date, price and name in the draft, one by one. Is it in a tool result above, word for word? If not: look it up now, or take it out, or mark it \"not verified\".\n" +
            "2. Does the figure the answer rests on have two independent sources? If it has one, search once more.\n" +
            "3. Is the first word of the Answer the choice or the fact they asked for? Is anything vague (\"around\", \"soon\", \"some\"), padded or repeated? Cut it.\n" +
            "4. What is the one risk most likely to make this wrong?\n" +
            "5. For today's price or rate: is the page it comes from dated today or yesterday (its \"dated\" field, year included)? If the page is older or shows no date, the first line says it is not confirmed for today and gives that date.\n" +
            "Then write the FINAL answer in the answer shape the mode asks for (short unless the user asked for more), in the user's language. " +
            "Write only the final answer: do not mention the draft or this review."

    private val figure = Regex("(₹|\\bRs\\.?\\s?|\\bINR\\s?|\\$)?(\\d[\\d,]*(?:\\.\\d+)?)\\s?(%|percent|lakhs?|lacs?|crores?|cr\\b|k\\b|million|billion|mn\\b|bn\\b)?", I)

    private fun plainDigits(n: String) = n.replace(",", "").trimEnd('.')

    /**
     * The figures in [answer] that appear nowhere in [evidence] (everything looked up, plus the user's own words):
     * the ones a decision could wrongly rest on. A price, a percentage, an amount in lakh or crore, any number of
     * three digits or more. Not checked: list numbers, days of the month, years, and small counts.
     * A figure counts as found when its digits are in the evidence, also across spellings
     * ("₹1.35 lakh" and "1,35,400"; "5.2%" and "5.2 per cent").
     */
    fun unverified(answer: String, evidence: String): List<String> {
        val ev = evidence.replace(Regex("(?<=\\d),(?=\\d)"), "")
        fun has(digits: String) = Regex("(?<![\\d.])" + Regex.escape(digits) + "(?![\\d])").containsMatchIn(ev)
        val out = LinkedHashSet<String>()
        // Links and the sources block are names of pages, not claims.
        val body = answer.replace(Regex("\\[[^\\]]*\\]\\([^)]*\\)|https?://\\S+"), " ")
        for (line in body.lines()) {
            val text = line.replace(Regex("^\\s*(?:[-*•]\\s*)?\\d{1,2}[.)]\\s"), " ")
            if (Regex("by my (?:sum|calculation|estimate)|not verified", I).containsMatchIn(text)) continue
            for (m in figure.findAll(text)) {
                val money = m.groupValues[1].isNotBlank()
                val digits = plainDigits(m.groupValues[2])
                val unit = m.groupValues[3].lowercase()
                val whole = digits.substringBefore('.')
                val value = digits.toDoubleOrNull() ?: continue
                val year = unit.isEmpty() && !money && whole.length == 4 && value in 1900.0..2100.0 && !digits.contains('.')
                val small = unit.isEmpty() && !money && !digits.contains('.') && value < 100
                if (year || small) continue
                if (has(digits)) continue
                // "1.35 lakh" ↔ 135000…135999; "2 crore" ↔ 20000000; "45k" ↔ 45000.
                val times = when { unit.startsWith("lakh") || unit.startsWith("lac") -> 100_000.0; unit.startsWith("crore") || unit == "cr" -> 10_000_000.0; unit == "k" -> 1000.0; unit == "million" || unit == "mn" -> 1_000_000.0; unit == "billion" || unit == "bn" -> 1_000_000_000.0; else -> 0.0 }
                if (times > 0) {
                    val full = Math.round(value * times).toString()
                    val lead = full.trimEnd('0').ifEmpty { "0" }
                    if (Regex("(?<![\\d.])" + Regex.escape(lead) + "\\d{" + (full.length - lead.length) + "}(?![\\d])").containsMatchIn(ev)) continue
                }
                // "1,35,400" ↔ "1.35 lakh" / "1.354 lakh" in the evidence.
                if (unit.isEmpty() && !digits.contains('.') && whole.length >= 6) {
                    val inLakh = value / 100_000.0
                    val two = String.format(java.util.Locale.ENGLISH, "%.2f", Math.floor(inLakh * 100) / 100).trimEnd('0').trimEnd('.')
                    if (Regex("(?<![\\d.])" + Regex.escape(two) + "\\d*\\s?(?:lakh|lac|l\\b)", I).containsMatchIn(ev)) continue
                    val inCr = value / 10_000_000.0
                    val twoCr = String.format(java.util.Locale.ENGLISH, "%.2f", Math.floor(inCr * 100) / 100).trimEnd('0').trimEnd('.')
                    if (whole.length >= 8 && Regex("(?<![\\d.])" + Regex.escape(twoCr) + "\\d*\\s?(?:crore|cr\\b)", I).containsMatchIn(ev)) continue
                }
                out += m.value.trim()
                if (out.size >= 6) return out.toList()
            }
        }
        return out.toList()
    }

    /** Sent once when the final answer holds figures that are in nothing that was read. */
    fun factsNudge(loose: List<String>): String =
        "Dirghadarshi fact check: these figures in your answer are not in anything you looked up in this conversation, nor in the user's words: ${loose.joinToString(", ")}. " +
            "For each one: look it up now and correct it, or remove it, or, if it is your own arithmetic, write \"by my sum\" and show the sum. Then give the final answer again in the same shape. Do not mention this check."

    /** The parts of the answer shape found in [answer], in order (for the tests, and to tell a shaped answer from a plain one). */
    fun parts(answer: String): List<String> {
        val names = listOf("Answer", "Why", "Risk", "Look ahead", "Watch out", "You may not know", "Do now", "How sure", "Sources")
        return names.filter { n -> Regex("(?m)^\\s*(?:[#>*-]+\\s*)?\\**\\s*${Regex.escape(n)}\\s*\\**\\s*[:：-]", I).containsMatchIn(answer) }
    }

    /** What the status line says while the mode is working. */
    fun status(step: Int, reviewing: Boolean): String = when {
        reviewing -> "Reading my answer again for what it missed…"
        step == 0 -> "Thinking far ahead…"
        else -> "Thinking far ahead · step ${step + 1}"
    }
}
