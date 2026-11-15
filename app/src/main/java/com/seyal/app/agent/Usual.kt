package com.seyal.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the user usually chooses, learned from what they actually did: the theatre, the number of
 * tickets and the price limit of bookings that went through, and which contact they mean by a name
 * that matches several. So "same as last time" works and the same question is not asked twice.
 * Kept on the phone; the user can see and remove every line.
 */
object Usual {
    private var file: File? = null
    /** kind (MOVIE, FOOD…) → past orders, newest last. */
    private val orders = HashMap<String, MutableList<JSONObject>>()
    /** A word the user used for someone ("ram") → that contact's saved name and number. */
    private val aliases = LinkedHashMap<String, Pair<String, String>>()

    @Synchronized fun init(dir: File) {
        file = File(dir, "seyal/usual.json"); orders.clear(); aliases.clear()
        runCatching {
            val o = JSONObject(file!!.readText())
            o.optJSONObject("orders")?.let { m -> m.keys().forEach { k -> orders[k] = m.getJSONArray(k).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() } } }
            o.optJSONObject("aliases")?.let { m -> m.keys().forEach { k -> m.optJSONObject(k)?.let { aliases[k] = it.optString("name") to it.optString("phone") } } }
        }
    }
    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(JSONObject().put("orders", JSONObject().apply { orders.forEach { (k, v) -> put(k, JSONArray(v)) } })
                .put("aliases", JSONObject().apply { aliases.forEach { (k, v) -> put(k, JSONObject().put("name", v.first).put("phone", v.second)) } }).toString())
        }
    }
    @Synchronized fun clear() { orders.clear(); aliases.clear(); save() }

    /** A booking or order that matched what was asked and reached the payment step: remember its choices (not its date). */
    @Synchronized fun noteOrder(o: Order) {
        val j = o.toJson().apply { remove("date"); remove("time") }
        if (j.length() <= 1) return
        val list = orders.getOrPut(o.kind) { mutableListOf() }
        list += j
        while (list.size > 12) list.removeAt(0)
        save()
    }

    /** The choices of the last order of this [kind], or null. */
    @Synchronized fun last(kind: String): Order? = orders[kind]?.lastOrNull()?.let { Order.fromJson(it) }

    /** How often [value] was the choice for [field] among the remembered orders of [kind]. */
    private fun times(kind: String, field: String, value: String): Int = orders[kind]?.count { it.optString(field).equals(value, true) } ?: 0

    /** One line for the AI and for the "what Seyal knows" page: "Movie: Sangeetha Somanur (3 times), 2 tickets, up to ₹250 each". */
    @Synchronized fun text(kind: String): String {
        val o = last(kind) ?: return ""
        return buildList {
            if (o.place.isNotBlank()) add(o.place + times(kind, "place", o.place).let { if (it > 1) " ($it times)" else "" })
            if (o.item.isNotBlank() && kind != "MOVIE") add(o.item)
            if (o.count > 0) add("${o.count} " + (Preflight.Kind.values().firstOrNull { it.name == kind }?.unit ?: ""))
            if (o.seat.isNotBlank()) add(o.seat)
            if (o.maxEach > 0) add("up to ₹${o.maxEach} each")
            if (o.app.isNotBlank()) add("on ${o.app}")
            if (o.from.isNotBlank() || o.to.isNotBlank()) add("${o.from} → ${o.to}")
        }.joinToString(", ").trim()
    }

    private val sameRx = Regex("\\b(same as (last time|before|usual|always|yesterday)|like last time|as usual|the usual|my usual|usual (one|place|order)|same (order|place|theatre|thing|one) (again|as before)|repeat (my )?(last )?order|pona (thadava|vaati) (maari|madhiri)|eppavum pola|same again)\\b|வழக்கம் போல", RegexOption.IGNORE_CASE)
    /** Whether the user asked for what they had before. */
    fun asksForSame(text: String): Boolean = sameRx.containsMatchIn(text)

    /**
     * [now] with everything the user left open filled in from the last order of that kind
     * (never the date or the time: those are always this request's own).
     */
    fun fill(now: Order): Order {
        val was = last(now.kind) ?: return now
        return Order(
            kind = now.kind, item = now.item.ifBlank { was.item }, language = now.language.ifBlank { was.language }, place = now.place.ifBlank { was.place },
            from = now.from.ifBlank { was.from }, to = now.to.ifBlank { was.to }, date = now.date, time = now.time,
            count = if (now.count > 0) now.count else was.count, seat = now.seat.ifBlank { was.seat }, maxEach = if (now.maxEach > 0) now.maxEach else was.maxEach, app = now.app.ifBlank { was.app },
        )
    }

    // ---- who the user means

    @Synchronized fun alias(word: String): Pair<String, String>? = aliases[word.trim().lowercase()]
    @Synchronized fun setAlias(word: String, name: String, phone: String) {
        if (word.isBlank() || phone.isBlank()) return
        aliases[word.trim().lowercase()] = name to phone
        while (aliases.size > 60) aliases.remove(aliases.keys.first())
        save()
    }

    /** Every remembered line, with a key to remove it by: for the "What Seyal knows about me" page. */
    @Synchronized fun lines(): List<Pair<String, String>> =
        orders.keys.sorted().mapNotNull { k -> text(k).takeIf { it.isNotBlank() }?.let { "order:$k" to "Usual ${k.lowercase()} choice: $it" } } +
            aliases.map { (w, v) -> "alias:$w" to "\"$w\" means ${v.first}" }

    @Synchronized fun forget(key: String) {
        if (key.startsWith("order:")) orders.remove(key.removePrefix("order:")) else aliases.remove(key.removePrefix("alias:"))
        save()
    }
}
