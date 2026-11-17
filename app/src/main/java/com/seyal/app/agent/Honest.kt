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
        "Look at your steps: a failed screenshot (\"Android error 1\") only means this phone gives no pictures, and the screen text still works " +
        "(screen_read, screen_tap by text). Carry on with the task from where it stopped. If you really cannot go on, tell the user in plain words " +
        "exactly which step failed and what the screen showed, and what they can do; never blame blocking."
}
