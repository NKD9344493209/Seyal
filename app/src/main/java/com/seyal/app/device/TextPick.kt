package com.seyal.app.device

/**
 * Which row of the listed screen the AI meant when it gave a row's whole line as the text to tap.
 *
 * Seen in a test run, three times in a row: asked to tap `Ss Hyderabad Biryani Ss Hyderabad Biryani 4.6 (85K+) •
 * 25-30 MINS Gandhipuram · Ss Hyderabad Biryani @741,919`, Seyal answered "nothing called that on screen". A row's
 * line in the list is put together from several texts and its position; no single thing on the screen carries it.
 * So when the screen has nothing with the text, the rows the AI was shown are searched for the one it copied.
 */
object ListPick {
    class Row(val id: Int, val label: String, val u: Int, val v: Int)

    private val at = Regex("\\s*@\\s*(\\d{1,4})\\s*,\\s*(\\d{1,4})\\s*$")
    private val lead = Regex("^\\s*\\d{1,3}\\s*\\[[a-z:]+\\]\\s*")
    private val note = Regex("\\s*\\((focused|selected|disabled|SECRET[^)]*|hint: [^)]*|id: [^)]*)\\)\\s*$")
    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('…')

    /**
     * The id of the row meant by [query], or null. In order: the row at the position the query ends with ("@741,919");
     * the row whose label is the query; the one row whose label begins with the query; the longest row label the
     * query begins with. The last two only for queries of eight letters or more, so "OK" never matches by accident.
     */
    fun match(query: String, rows: List<Row>): Int? {
        val pos = at.find(query)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        val q = norm(note.replace(lead.replace(at.replace(query, ""), ""), ""))
        if (pos != null) {
            val near = rows.filter { kotlin.math.abs(it.u - pos.first) <= 6 && kotlin.math.abs(it.v - pos.second) <= 6 }
            (near.firstOrNull { norm(it.label) == q } ?: near.singleOrNull())?.let { return it.id }
        }
        if (q.length < 2) return null
        rows.firstOrNull { norm(it.label) == q }?.let { return it.id }
        if (q.length < 8) return null
        rows.filter { norm(it.label).startsWith(q) }.singleOrNull()?.let { return it.id }
        return rows.filter { val l = norm(it.label); l.length >= 8 && q.startsWith(l) }.maxByOrNull { it.label.length }?.id
    }
}

/**
 * Which of the things on screen a tap by text means.
 *
 * Seen on a real phone: asked to tap "Ramayana" in BookMyShow's search results, Seyal opened "Valmiki Ramayana",
 * a film that is not out yet, and spent two minutes there. The old rule was "the shortest label containing the
 * words", and the row "Valmiki Ramayana, In upcoming movies | Hindi" is shorter than the right row,
 * "Ramayana, In cinemas now | Hindi, Kannada & 3 more". Now a label that IS the words, or begins with them,
 * always beats one that only has them somewhere inside; and a box the words were typed into is never the target
 * while anything else fits.
 */
object TextPick {
    class Cand(val text: String, val desc: String, val clickable: Boolean, val editable: Boolean, val top: Int)

    private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

    /**
     * How well [label] fits [query]. 0 = it is exactly that. 1 = a row that begins with it ("Ramayana, In cinemas
     * now"; "Ramayana · 4.5"). 2 = it begins with it and goes on ("Ramayana Part 2"). 3 = it has the words, whole,
     * somewhere inside ("Valmiki Ramayana"). 4 = it has the letters inside a longer word. null = not at all.
     */
    internal fun rank(label: String, query: String): Int? {
        val l = norm(label); val q = norm(query)
        if (q.isEmpty() || l.isEmpty()) return null
        if (l == q) return 0
        if (l.startsWith(q)) {
            val rest = l.substring(q.length)
            val next = rest.trimStart().firstOrNull() ?: return 0
            if (next in ",·|•\n") return 1
            if (!rest.first().isLetterOrDigit()) return 2
            return 4
        }
        val at = l.indexOf(q)
        if (at < 0) return null
        val before = l[at - 1]
        val after = l.getOrNull(at + q.length)
        return if (!before.isLetterOrDigit() && (after == null || !after.isLetterOrDigit())) 3 else 4
    }

    /** The index in [cands] of the one a tap on [query] means, or null. [exactOnly]: only a label that is exactly the words. */
    fun best(query: String, cands: List<Cand>, exactOnly: Boolean = false): Int? {
        class Hit(val i: Int, val c: Cand, val rank: Int)
        val hits = cands.mapIndexedNotNull { i, c ->
            val r = listOfNotNull(rank(c.text, query), rank(c.desc, query)).minOrNull() ?: return@mapIndexedNotNull null
            Hit(i, c, r)
        }.filter { !exactOnly || it.rank == 0 }
        if (hits.isEmpty()) return null
        // What was typed into a search box also "matches": it is the target only when nothing else comes close.
        val pool = hits.filter { !it.c.editable && it.rank <= 2 }.ifEmpty { hits }
        val top = pool.minOf { it.rank }
        val same = pool.filter { it.rank == top }
        return when {
            top == 0 -> same.sortedWith(compareBy({ if (it.c.clickable) 0 else 1 }, { it.c.top }, { it.i })).first().i
            // A list of results is in the order the app thinks best: the first one that begins with the words.
            top <= 2 -> same.sortedWith(compareBy({ it.c.top }, { it.i })).first().i
            else -> same.sortedWith(compareBy({ it.c.text.length + it.c.desc.length }, { it.c.top }, { it.i })).first().i
        }
    }
}
