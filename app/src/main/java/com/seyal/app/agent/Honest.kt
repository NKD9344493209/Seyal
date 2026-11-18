package com.seyal.app.agent

/** Catches a made-up excuse in a final answer. */
object Honest {
    private val blame = Regex(
        "\\b(block(s|ed|ing)?|prevent(s|ed|ing)?|restrict(s|ed|ing)?|stop(s|ped|ping)?|not allow(ing|ed)?|disallow\\w*|refus(e|es|ed|ing))\\b[^.]{0,60}\\b(automat\\w*|bots?|seyal|me from|access)\\b" +
            "|\\b(automat\\w*|bot) (access|actions?) (is|are) (blocked|not allowed|restricted)\\b|anti-?bot|detect(s|ed)? (automation|bots?)" +
            "|\\b(apps?|websites?|sites?|system)\\b[^.]{0,30}\\b(is|are) (currently |now )?(blocked|blocking|restricted)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun blames(answer: String): Boolean = blame.containsMatchIn(answer.take(800))

    const val NUDGE = "You wrote that apps or websites are blocking automated access. That is not what happened: no app blocks Seyal. " +
        "Look at your steps: a failed screenshot (\"Android error 1\") only means no picture could be taken at that moment, and the screen text still works " +
        "(screen_read, screen_tap by text). Carry on with the task from where it stopped. If you really cannot go on, tell the user in plain words " +
        "exactly which step failed and what the screen showed, and what they can do; never blame blocking."

    private val claim = Regex(
        "\\bI(?:'ve| have| had)? (?:successfully |already |now |just )?(selected|booked|reserved|chosen|picked|ordered|placed|added|confirmed|paid)\\b(?! (?:not|no|nothing))",
        RegexOption.IGNORE_CASE,
    )
    /** Steps that really change something inside another app. */
    private val hands = setOf("screen_tap", "screen_tap_at", "screen_type", "screen_long_press", "screen_swipe", "screen_scroll", "seats_check", "order_check")

    /**
     * A booking or an order answered with "I have selected 2 tickets (F10, F11)" although no step of the request
     * touched or read another app: seen on a real phone with screen control off, where the seats were invented.
     * Returns the claimed deed ("selected"), or null when the words are backed by [okTools] or claim nothing.
     */
    fun unbacked(text: String, okTools: Collection<String>): String? {
        val m = claim.find(text.take(700)) ?: return null
        return if (okTools.any { it in hands }) null else m.groupValues[1].lowercase()
    }

    fun claimNudge(deed: String, screenOn: Boolean): String =
        "You wrote that you $deed something, but no step of this request did that: you did not tap, type or read anything inside the app. " +
            (if (screenOn) "" else "Screen control is OFF, so you cannot see or touch other apps and you do not know what the user did there. ") +
            "Never state seats, items, prices or a selection that no tool result showed you. Say only what really happened (what you opened), what is left for the user to do, and how to let you do it next time."
}
