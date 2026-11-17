package com.seyal.app.device

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
