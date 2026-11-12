package com.seyal.app.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Finds the date (and time, if any) a message talks about: "last date 15/11", "submit by Friday 5 pm",
 * "due on 20 Nov", "naalaikku". Plain pattern matching, done on the phone with no AI, so it is
 * instant, free and private.
 */
object Dates {
    class Found(val at: LocalDateTime, val hasTime: Boolean, val index: Int)

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MON = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
    private val dmy = Regex("(?<![\\d/.-])(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{4}|\\d{2}))?(?![\\d/-])")
    private val dMon = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s*(?:of\\s+)?$MON\\b\\.?,?\\s*(\\d{4})?", RegexOption.IGNORE_CASE)
    private val monD = Regex("\\b$MON\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b,?\\s*(\\d{4})?", RegexOption.IGNORE_CASE)
    private val relative = Regex("\\b(day after tomorrow|tomorrow|tmrw|tmr|today|tonight|naalaikku|nalaiku|naalai|inniku|innaiku|indru)\\b|நாளை மறுநாள்|நாளை|இன்று", RegexOption.IGNORE_CASE)
    private val weekday = Regex("\\b(this|next|coming)?\\s*(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\\b", RegexOption.IGNORE_CASE)
    private val inDays = Regex("\\b(?:in|within)\\s+(\\d{1,2})\\s+(day|days|hour|hours|hrs?)\\b", RegexOption.IGNORE_CASE)
    private val clock = Regex("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.?|p\\.m\\.?)(?![a-z])|\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b(?!\\s*(am|pm))", RegexOption.IGNORE_CASE)
    private val endOfDay = Regex("\\b(eod|end of (the )?day|midnight)\\b", RegexOption.IGNORE_CASE)
    private val noon = Regex("\\bnoon\\b", RegexOption.IGNORE_CASE)

    private fun date(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    /** A date written without a year is the next time that date comes (a little in the past still counts as this year). */
    private fun yearFor(m: Int, d: Int, today: LocalDate): LocalDate? {
        val thisYear = date(today.year, m, d) ?: return null
        return if (thisYear.isBefore(today.minusDays(45))) date(today.year + 1, m, d) else thisYear
    }

    /** Every date in [text], in the order written. */
    fun all(text: String, now: LocalDateTime): List<Pair<Int, LocalDate>> {
        val today = now.toLocalDate()
        val out = mutableListOf<Pair<Int, LocalDate>>()
        dmy.findAll(text).forEach { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val y = m.groupValues[3]
            // "10.30" before am/pm is a time, and "1/2" of something is not a date.
            val after = text.substring(m.range.last + 1).trimStart().lowercase()
            if (after.startsWith("am") || after.startsWith("pm") || after.startsWith("a.m") || after.startsWith("p.m")) return@forEach
            if (mo !in 1..12 || d !in 1..31) return@forEach
            if (y.isBlank() && m.value.contains('.')) return@forEach
            val ld = if (y.isBlank()) yearFor(mo, d, today) else date(if (y.length == 2) 2000 + y.toInt() else y.toInt(), mo, d)
            ld?.let { out += m.range.first to it }
        }
        fun month(s: String) = months.indexOf(s.lowercase().take(3)) + 1
        dMon.findAll(text).forEach { m ->
            val d = m.groupValues[1].toInt(); val mo = month(m.groupValues[2]); val y = m.groupValues[3]
            (if (y.isBlank()) yearFor(mo, d, today) else date(y.toInt(), mo, d))?.let { out += m.range.first to it }
        }
        monD.findAll(text).forEach { m ->
            val mo = month(m.groupValues[1]); val d = m.groupValues[2].toInt(); val y = m.groupValues[3]
            if (out.any { kotlin.math.abs(it.first - m.range.first) < 12 }) return@forEach
            (if (y.isBlank()) yearFor(mo, d, today) else date(y.toInt(), mo, d))?.let { out += m.range.first to it }
        }
        relative.findAll(text).forEach { m ->
            val w = m.value.lowercase()
            val add = when {
                w.startsWith("day after") || w == "நாளை மறுநாள்" -> 2L
                w in setOf("tomorrow", "tmrw", "tmr", "naalaikku", "nalaiku", "naalai", "நாளை") -> 1L
                else -> 0L
            }
            out += m.range.first to today.plusDays(add)
        }
        weekday.findAll(text).forEach { m ->
            val name = m.groupValues[2].lowercase().take(3)
            // "sat", "sun", "mon" inside other words are caught by \b; "may"/"march" are months, not here.
            val dow = when (name) { "mon" -> DayOfWeek.MONDAY; "tue" -> DayOfWeek.TUESDAY; "wed" -> DayOfWeek.WEDNESDAY; "thu" -> DayOfWeek.THURSDAY; "fri" -> DayOfWeek.FRIDAY; "sat" -> DayOfWeek.SATURDAY; else -> DayOfWeek.SUNDAY }
            var add = (dow.value - today.dayOfWeek.value + 7) % 7
            if (add == 0 && m.groupValues[1].isNotBlank()) add = 7
            out += m.range.first to today.plusDays(add.toLong())
        }
        inDays.findAll(text).forEach { m ->
            val n = m.groupValues[1].toLong()
            out += m.range.first to (if (m.groupValues[2].startsWith("h", true)) now.plusHours(n).toLocalDate() else today.plusDays(n))
        }
        return out.sortedBy { it.first }
    }

    /** The time of day written at or after [from] in [text] (the nearest one), or null. */
    fun time(text: String, from: Int = 0): LocalTime? {
        val m = clock.find(text, from.coerceIn(0, text.length)) ?: clock.find(text)
        if (m != null) {
            if (m.groupValues[3].isNotBlank()) {
                var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toIntOrNull() ?: 0
                val pm = m.groupValues[3].lowercase().startsWith("p")
                if (h in 1..12 && min in 0..59) { if (pm && h < 12) h += 12; if (!pm && h == 12) h = 0; return LocalTime.of(h, min) }
            } else if (m.groupValues[4].isNotBlank()) return LocalTime.of(m.groupValues[4].toInt(), m.groupValues[5].toInt())
        }
        if (noon.containsMatchIn(text)) return LocalTime.of(12, 0)
        if (endOfDay.containsMatchIn(text)) return LocalTime.of(23, 59)
        return null
    }

    /**
     * The date a message is about. When it names several, the first one written after [afterIndex]
     * (where the word "deadline" / "due" sits) wins; otherwise the first. No time written = end of that day.
     */
    fun find(text: String, now: LocalDateTime, afterIndex: Int = 0): Found? {
        val dates = all(text, now)
        if (dates.isEmpty()) return null
        val (idx, day) = dates.firstOrNull { it.first >= afterIndex } ?: dates.first()
        val t = time(text, idx)
        return Found(day.atTime(t ?: LocalTime.of(23, 59)), t != null, idx)
    }
}
