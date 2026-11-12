package com.seyal.app.agent

/**
 * Brakes for skills that start by themselves when a message arrives. A skill that answers people must
 * never turn into a stream of messages: one chat gets one answer per while, and a skill that keeps
 * firing is switched off and the user is told. Plain bookkeeping, no Android: easy to test.
 */
object AutoGuard {
    /** The same chat is answered again only after this long: short when the skill waits for particular words, long when it answers anything. */
    const val WITH_WORDS_MS = 10 * 60_000L
    const val ANY_MESSAGE_MS = 60 * 60_000L
    /** More runs than this inside [BURST_MS], or [DAY_MAX] in a day, and the skill is switched off. */
    const val BURST_MAX = 5
    const val BURST_MS = 10 * 60_000L
    const val DAY_MAX = 30

    private val lastPerChat = HashMap<String, Long>()
    private val runs = HashMap<String, MutableList<Long>>()

    /** Null when the skill may run for this chat now; otherwise why not (for the trigger log). Does not record anything. */
    @Synchronized fun blocked(skillId: String, chat: String, waitsForWords: Boolean, now: Long): String? {
        val last = lastPerChat["$skillId|${chat.lowercase()}"] ?: return null
        val gap = if (waitsForWords) WITH_WORDS_MS else ANY_MESSAGE_MS
        val left = last + gap - now
        return if (left > 0) "already answered this chat ${(now - last) / 60_000} min ago (again in ${left / 60_000 + 1} min)" else null
    }

    /**
     * Records a run. Returns true when the skill has now run so often that it must be switched off
     * (more than [BURST_MAX] times in ten minutes, or [DAY_MAX] in a day).
     */
    @Synchronized fun record(skillId: String, chat: String, now: Long): Boolean {
        lastPerChat["$skillId|${chat.lowercase()}"] = now
        if (lastPerChat.size > 400) lastPerChat.entries.sortedBy { it.value }.take(200).forEach { lastPerChat.remove(it.key) }
        val list = runs.getOrPut(skillId) { mutableListOf() }
        list += now
        list.removeAll { it < now - 24 * 3600_000L }
        return list.count { it > now - BURST_MS } > BURST_MAX || list.size > DAY_MAX
    }

    @Synchronized fun clear(skillId: String? = null) {
        if (skillId == null) { lastPerChat.clear(); runs.clear() }
        else { runs.remove(skillId); lastPerChat.keys.filter { it.startsWith("$skillId|") }.forEach { lastPerChat.remove(it) } }
    }

    /** Whether a skill sends something to other people: a fixed send step, or a goal that says to reply or send. */
    fun sends(goal: String, stepTools: List<String>, isComms: (String) -> Boolean): Boolean =
        stepTools.any(isComms) || Regex(
            "\\b(reply|replies|respond|answer (him|her|them|back)|send|sends|message (him|her|them|back)|whatsapp (him|her|them)|text (him|her|them|back)|tell (him|her|them)|inform (him|her|them)|forward|notification_reply|send_whatsapp|send_sms|send_email|anuppu|sollu avar|reply pannu)\\b|பதில்|அனுப்பு",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(goal.substringBefore("\n\n"))
}
