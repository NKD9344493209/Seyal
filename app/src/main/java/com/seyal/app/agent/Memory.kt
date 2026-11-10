package com.seyal.app.agent

import com.seyal.app.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What Seyal remembers about the user between sessions: one fact per line, read on every step
 * (like Claude Code's memory file). The user can edit it in Control.
 */
object Memory {
    const val MAX = 4000
    private val _text = MutableStateFlow<String?>(null)
    val flow: StateFlow<String?> = _text

    fun text(): String = _text.value ?: Store.loadMemory().also { _text.value = it }

    fun set(text: String) {
        val t = text.trim().take(MAX)
        _text.value = t
        Store.saveMemory(t)
    }

    /** Adds a fact; a line about the same subject ("dad = …") replaces the old one. */
    fun add(fact: String): String = merge(text(), fact).also { set(it) }

    /** Removes lines containing [text]. Returns how many. */
    fun forget(text: String): Int = remove(text(), text).let { (kept, n) -> set(kept); n }

    // ---- pure parts (unit-tested) ----

    fun merge(existing: String, fact: String): String {
        val f = fact.trim().replace("\n", " ").removePrefix("- ").take(200)
        if (f.isBlank()) return existing
        val key = subject(f)
        val lines = existing.lines().filter { it.isNotBlank() }.toMutableList()
        if (key.length in 2..40) lines.removeAll { subject(it.removePrefix("- ")) == key }
        lines.removeAll { it.removePrefix("- ").equals(f, ignoreCase = true) }
        lines += "- $f"
        var joined = lines.joinToString("\n")
        while (joined.length > MAX && lines.size > 1) { lines.removeAt(0); joined = lines.joinToString("\n") }
        return joined
    }

    fun remove(existing: String, text: String): Pair<String, Int> {
        val t = text.trim().lowercase()
        if (t.isBlank()) return existing to 0
        val lines = existing.lines()
        val keep = lines.filterNot { it.lowercase().contains(t) }
        return keep.joinToString("\n") to lines.size - keep.size
    }

    private fun subject(line: String): String = line.substringBefore("=").substringBefore(":").trim().lowercase()
}
