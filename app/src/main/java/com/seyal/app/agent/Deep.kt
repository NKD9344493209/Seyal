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
        "web_search", "read_page", "news", "weather", "wikipedia", "foresight", "read_mail", "search_mail", "past_search", "chats_search",
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

    /** A plain instruction ("send…", "set an alarm…", "open…"): something to do, not something to think through. */
    fun isAction(said: String): Boolean = doIt.containsMatchIn(strip(said).trim())

    /**
     * Whether one AI step should think hard: the answer and the plan. Not each tap inside an app (that made every
     * step take a minute), and not a plain instruction typed while the switch happened to be on.
     */
    fun thinkHard(deep: Boolean, usedScreen: Boolean, hasOrder: Boolean, said: String = ""): Boolean = deep && !usedScreen && !hasOrder && !isAction(said)

    /** Added to the system prompt while the mode is on. */
    val SYSTEM = """
DIRGHADARSHI MODE IS ON (தீர்க்கதரிசி: one who sees far). The user switched it on because they want your most careful thinking, not your fastest. For this request:
1. Understand the need behind the words. If ONE missing fact would change the answer (budget, deadline, who it is for), ask it once with ask_user; otherwise say your assumption in one line.
2. Check before you say. Anything that can change (prices, rules, dates, versions, offers, availability, news, what is best now) → web_search or read_page FIRST, and two independent sources for what matters most. Only when the question is about what is new or rising in technology, AI or jobs, call foresight (it takes half a minute: never for other subjects such as gold, phones or health). For questions about the user's own life, use their data (memory, messages, calendar, money tools). Never answer such things from memory.
3. Think ahead in time: what happens next if they do this, in a week, in some months, in a year. What is changing in the world that makes this choice better or worse later.
4. Think about what can go wrong: the likely failure, the costly failure, the scam or trap, the fine print, the step that cannot be undone. Give the precaution for each.
5. Say what they may not know: a cheaper, safer or better alternative, a hidden cost, a deadline, a rule, a better time to do it.

ANSWER SHAPE (plain words, short lines; leave a part out when you have nothing real for it; never pad):
**Answer**: the direct answer or recommendation first, in 1 to 3 lines. When they ask you to choose (yes or no, now or wait, this or that), CHOOSE: say which way you lean and the one reason that decides it, even when it is uncertain. "It depends" and "there is no simple answer" are not answers: the doubt goes under How sure. Asked again for "yes or no", the first word is Yes or No.
**Look ahead**: what is likely next and how it changes things.
**Watch out**: up to 3 risks, each with its precaution ("→ do this").
**You may not know**: 1 or 2 things.
**Do now**: 1 to 3 concrete steps, the first one doable today. Offer to do the ones you can do on this phone (a reminder, a calendar entry, a message, a search).
**How sure**: High, Medium or Low, why, and what would change your mind.
**Sources**: the links you actually read, one per line as [name of the page](link).

RULES OF THE MODE: every fact that matters says where it is from; an estimate is called an estimate; never invent numbers, links or quotes. Never name a share, a coin or a bet to buy or sell. For other money questions (gold, a house, a big purchase, a loan) do give your lean, as what a careful person would do and why, with the main risk, and one line saying it is general information and the choice is theirs. For a health, legal or money matter of weight, say when a professional must be asked.

WHEN THE REQUEST IS AN ACTION on the phone (book, order, buy, send, post, delete, install, share): do it as usual, and also (a) before the step that commits, read the screen for the total with every fee, the date and time, the count, who receives it, and the cancel or refund rule; (b) if something looks off (a price above what was said, the wrong day, a badly rated seller, a link that is not the real site, anyone asking for an OTP or PIN), stop and ask; (c) end with "Check before you pay" (or send): the 2 or 3 things the user should see with their own eyes.
""".trim()

    /** Sent when the AI answered a changing question without looking anything up. */
    const val LOOKUP_NUDGE =
        "Dirghadarshi mode: you answered from memory, and this is about things that change. Look it up now (web_search, read_page, foresight, or the user's own data), " +
            "then answer from what you found. If a search finds nothing, say that plainly instead of guessing."

    /** Sent once after the first full answer: the AI reads its own draft as a critic, then writes the final answer. */
    const val REVIEW_NUDGE =
        "Dirghadarshi review, before the user sees anything. Read your draft above as a careful critic whose job is to protect the user:\n" +
            "1. Is any fact unverified, out of date or from memory? Verify it now with a tool, or mark it \"not verified\".\n" +
            "2. What did the draft miss: a risk, a hidden cost, a deadline, a better alternative, what happens later?\n" +
            "3. Is the first line a clear answer? Is anything padded, repeated or vague?\n" +
            "Then write the FINAL answer in the Dirghadarshi shape (Answer, Look ahead, Watch out, You may not know, Do now, How sure, Sources), in the user's language. " +
            "Write only the final answer: do not mention the draft or this review."

    /** The parts of the answer shape found in [answer], in order (for the tests, and to tell a shaped answer from a plain one). */
    fun parts(answer: String): List<String> {
        val names = listOf("Answer", "Look ahead", "Watch out", "You may not know", "Do now", "How sure", "Sources")
        return names.filter { n -> Regex("(?m)^\\s*(?:[#>*-]+\\s*)?\\**\\s*${Regex.escape(n)}\\s*\\**\\s*[:：-]", I).containsMatchIn(answer) }
    }

    /** What the status line says while the mode is working. */
    fun status(step: Int, reviewing: Boolean): String = when {
        reviewing -> "Reading my answer again for what it missed…"
        step == 0 -> "Thinking far ahead…"
        else -> "Thinking far ahead · step ${step + 1}"
    }
}
