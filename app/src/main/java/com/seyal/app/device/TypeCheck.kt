package com.seyal.app.device

/**
 * Did the text really go into the field? Some apps (Swiggy's search box among them) say yes to set-text or
 * paste and then show nothing, and Seyal used to report "typed" and try again for twenty steps.
 */
object TypeCheck {
    fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private val hintLike = Regex("^(try|search|type|enter|find|e\\.?g\\.?|eg|ask|what|where)\\b", RegexOption.IGNORE_CASE)

    /**
     * [shown]: the field's text after the attempt (null = it can't be read, so there is nothing to judge by).
     * [before]: its text before. [hint]: its hint. [showingHint]: Android says it is showing the hint.
     */
    fun took(shown: String?, value: String, hint: String, before: String, showingHint: Boolean): Boolean {
        if (shown == null) return true
        if (showingHint) return false
        val got = norm(shown); val want = norm(value)
        if (want.isEmpty()) return true
        if (got.isEmpty()) return false
        if (got.contains(want.take(20))) return true
        // Still the old text, the hint, or a rotating hint written as text ("Try 'Pizza'"): nothing went in.
        if (shown == before || (hint.isNotBlank() && shown.trim() == hint.trim())) return false
        if (hintLike.containsMatchIn(shown.trim()) && !got.contains(want.take(4))) return false
        // Something else is there now: the app reformatted what was typed (a phone number, a date).
        return true
    }

    /** What the AI is told when no way of typing worked. */
    fun refused(text: String): String =
        "The text did NOT go into the field: this app's box takes nothing typed by Seyal. Do not try again. " +
            "Call your_turn with: Type \"${text.take(60)}\" in the search box, then tap Done. Carry on from the screen it returns."
}
