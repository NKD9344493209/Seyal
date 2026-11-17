package com.seyal.app.device

/** Whether screenshots can be counted on, from how the recent ones went. */
object ShotHealth {
    const val FAILS_TO_GIVE_UP = 3
    const val RETRY_AFTER_MS = 10 * 60_000L

    /**
     * [fails] in a row, the last at [lastFailAt]: not worth asking again for ten minutes (each ask costs several
     * seconds of retries). Screenshots on a phone come and go, so it is never given up on for long.
     */
    fun broken(fails: Int, lastFailAt: Long, now: Long): Boolean = fails >= FAILS_TO_GIVE_UP && now - lastFailAt < RETRY_AFTER_MS

    /** What the AI is told in place of a picture. [elements]: how many things the screen text lists. */
    fun instead(why: String, elements: Int): String =
        "No screenshot could be taken just now ($why). That is this phone, NOT the app blocking you. " +
            (if (elements > 0) "Here is the same screen as text instead: work from it with screen_tap (by id or text) and screen_scroll. "
            else "The screen text is empty too: the app may still be loading, so screen_wait a moment and read again. ") +
            "For a screen that is only a picture (a seat map, a map, a game), call your_turn so the user taps that part. Do not ask for a screenshot again in this task unless the text is empty."
}
