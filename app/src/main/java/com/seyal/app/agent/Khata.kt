package com.seyal.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A credit book ("khata"): who owes the user money and how much, for a shop or among friends.
 * "Ravi owes me 500", "Ravi paid 200", "who owes me?". Kept on the phone. Reminders to the people
 * who owe are only drafted here: sending one always goes through the user's approval.
 */
object Khata {
    class Entry(val name: String, /** Positive: they took credit. Negative: they paid back. */ val amount: Int, val note: String, val at: Long) {
        fun toJson(): JSONObject = JSONObject().put("name", name).put("amount", amount).put("note", note).put("at", at)
    }

    private var file: File? = null
    private val entries = mutableListOf<Entry>()

    @Synchronized fun init(dir: File) {
        file = File(dir, "seyal/khata.json"); entries.clear()
        runCatching { val a = JSONArray(file!!.readText()); for (i in 0 until a.length()) a.getJSONObject(i).let { entries += Entry(it.optString("name"), it.optInt("amount"), it.optString("note"), it.optLong("at")) } }
    }
    private fun save() { val f = file ?: return; runCatching { f.parentFile?.mkdirs(); f.writeText(JSONArray(entries.map { it.toJson() }).toString()) } }
    @Synchronized fun clear() { entries.clear(); save() }

    private fun key(name: String) = name.trim().lowercase().replace(Regex("\\s+"), " ")
    /** The name as first written for this person, so "ravi" and "Ravi" are one account. */
    private fun canonical(name: String): String = entries.firstOrNull { key(it.name) == key(name) }?.name ?: name.trim().replaceFirstChar { it.uppercase() }

    /** Records credit given ([gave] = true) or money paid back. Returns that person's balance now. */
    @Synchronized fun add(name: String, amount: Int, gave: Boolean, note: String, now: Long): Int {
        val who = canonical(name)
        entries += Entry(who, if (gave) amount else -amount, note.take(80), now)
        while (entries.size > 3000) entries.removeAt(0)
        save()
        return balance(who)
    }

    @Synchronized fun balance(name: String): Int = entries.filter { key(it.name) == key(name) }.sumOf { it.amount }

    /** Everyone with money still owed, biggest first: name, amount, and when their oldest unpaid credit was given. */
    @Synchronized fun owing(): List<Triple<String, Int, Long>> = entries.groupBy { key(it.name) }.mapNotNull { (_, list) ->
        val bal = list.sumOf { it.amount }
        if (bal > 0) Triple(list.first().name, bal, list.filter { it.amount > 0 }.minOf { it.at }) else null
    }.sortedByDescending { it.second }

    fun summary(now: Long): String {
        val list = owing()
        if (list.isEmpty()) return "Nobody owes you anything in the credit book."
        return "Owed to you: ₹${list.sumOf { it.second }} from ${list.size} ${if (list.size == 1) "person" else "people"}.\n" +
            list.take(20).joinToString("\n") { (n, amt, since) -> "• $n: ₹$amt (since ${(now - since) / (24 * 3600_000L)} days)" }
    }

    /** A polite reminder for [name], in the user's voice. */
    fun reminder(name: String, from: String): String {
        val bal = balance(name)
        return "Hi ${canonical(name)}, a gentle reminder: ₹$bal is pending" + (if (from.isNotBlank()) " with $from" else "") + ". Please pay when you can. Thank you!"
    }
}

/** Splitting a bill exactly, so nobody is a rupee short or over. */
object Split {
    /** "₹1,200 among 3: me ₹400, Ram ₹400, Priya ₹400." (or "₹300 each" when nobody is named). */
    fun say(total: Int, shares: List<Pair<String, Int>>): String {
        val unnamed = shares.all { it.first == "me" || it.first.startsWith("Person ") }
        val same = shares.map { it.second }.distinct().size == 1
        val head = "₹${"%,d".format(total)} among ${shares.size}: "
        return head + (if (unnamed && same) "₹${"%,d".format(shares.first().second)} each." else shares.joinToString(", ") { "${it.first} ₹${"%,d".format(it.second)}" } + ".") +
            (if (!unnamed) "\nSay \"send it to them\" and I'll message each person their share." else "")
    }

    /** "me, Ram and Priya" → [me, Ram, Priya]; "4" / "4 people" / "the 4 of us" → [me, Person 2, Person 3, Person 4]. */
    fun people(text: String): List<String> {
        val t = text.trim().trimEnd('.', '?', '!')
        Regex("^(?:the )?(\\d{1,2})(?: (?:people|persons|of us|friends|ways|members|heads|per))?$", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val n = m.groupValues[1].toInt(); if (n in 2..50) return listOf("me") + (2..n).map { "Person $it" }
        }
        val names = t.split(Regex("\\s*(?:,|&|\\band\\b|\\bmatrum\\b)\\s*", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotBlank() }
            .map { if (it.lowercase() in setOf("me", "i", "myself", "us", "naan", "nan")) "me" else it.replaceFirstChar { c -> c.uppercase() } }
        return if (names.size >= 2 && names.all { it.length <= 30 && it.split(' ').size <= 3 }) names.distinct() else emptyList()
    }

    /**
     * Each person's share of [total]. With [own] (what each one's own items cost, same order as [people]),
     * everyone pays their own and the rest (tax, shared dishes) is split equally. Rounding leftovers go to
     * the first people, a rupee each, so the shares always add up to the total. Null when it can't be split.
     */
    fun shares(total: Int, people: List<String>, own: List<Int> = emptyList()): List<Pair<String, Int>>? {
        if (total <= 0 || people.size < 2) return null
        val mine = if (own.size == people.size) own.map { it.coerceAtLeast(0) } else List(people.size) { 0 }
        val shared = total - mine.sum()
        if (shared < 0) return null
        val each = shared / people.size
        var left = shared - each * people.size
        return people.mapIndexed { i, n -> n to (mine[i] + each + (if (left > 0) { left--; 1 } else 0)) }
    }
}
