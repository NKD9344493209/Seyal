package com.seyal.app.agent

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The strip of dates on a booking page (FRI 20 NOV · SAT 21 NOV · SUN 22 NOV …), read the way the user sees it.
 *
 * Why this exists. On a real phone (19 Nov 2026, v3.24.0, BookMyShow) the page stood on Friday the 20th, the day the
 * user asked for. Seyal "tapped 20 Nov" and the app jumped to Tuesday the 24th; getting back cost two more wrong
 * days and a minute. An earlier recording ended on the 22nd the same way. A tap by name asks the app to "click" a
 * thing in its list, with no place on the glass; an app that works out which date was meant from where the finger
 * landed then picks whatever it likes. So a date is chosen here as a person does it: find the chip that SHOWS the
 * number, press that spot, and look which chip is highlighted afterwards.
 *
 * Everything here goes by the words on the screen and where they are, never by descriptions (BookMyShow leaves old
 * ones on its chips), so it is tested without a phone.
 */
object DayStrip {
    private val I = RegexOption.IGNORE_CASE
    private val weekdays = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val dayRx = Regex("^(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)[a-z]*\\.?$", I)
    private val monRx = Regex("^(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?$", I)
    private val numRx = Regex("^(?:[1-9]|[12]\\d|3[01])$")
    private val fmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** One piece of text on the screen and where it is (real pixels). [selected]: the app marks it, or something it sits in, as selected. */
    class Seen(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val selected: Boolean = false) {
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2
        val w get() = right - left
        val h get() = bottom - top
    }

    /** One date of the strip: the day it shows and the spot to press (the middle of its number). */
    class Chip(val day: LocalDate, val x: Int, val y: Int, val selected: Boolean) {
        fun label(): String = day.format(fmt)
    }

    /** The strip: its dates from left to right, and how far apart they stand. */
    class Strip(val chips: List<Chip>, val pitch: Int) {
        /** The date the app itself marks as selected, when it marks exactly one. */
        val marked: Chip? get() = chips.filter { it.selected }.singleOrNull()
        fun has(day: LocalDate): Chip? = chips.firstOrNull { it.day == day }
        fun span(): String = "${chips.first().label()} to ${chips.last().label()}"
    }

    private fun wd(s: String): Int = dayRx.find(s.trim())?.groupValues?.get(1)?.lowercase()?.take(3)?.let { weekdays.indexOf(it) } ?: -1
    private fun mo(s: String): Int = monRx.find(s.trim())?.groupValues?.get(1)?.lowercase()?.take(3)?.let { months.indexOf(it) } ?: -1

    /** The date a number stands for, given the month and/or weekday written with it; null when they cannot all be true. */
    private fun dateOf(n: Int, month: Int, weekday: Int, today: LocalDate): LocalDate? {
        if (month >= 0) {
            val d = listOf(today.year, today.year + 1, today.year - 1).firstNotNullOfOrNull { y ->
                runCatching { LocalDate.of(y, month + 1, n) }.getOrNull()?.takeIf { !it.isBefore(today.minusDays(31)) && it.isBefore(today.plusDays(335)) }
            } ?: return null
            return d.takeIf { weekday < 0 || it.dayOfWeek.value - 1 == weekday }
        }
        if (weekday < 0) return null
        // Only a weekday is written ("FRI 20"): the first such day from yesterday on.
        return (-1L..62L).map { today.plusDays(it) }.firstOrNull { it.dayOfMonth == n && it.dayOfWeek.value - 1 == weekday }
    }

    /**
     * The strip of dates among [nodes], or null when the screen has none. A date is a number with its weekday above
     * and/or its month below (three pieces of text, as BookMyShow draws them), or one piece of text holding all of it
     * ("FRI 20 NOV"). A strip is three or more such dates on one line, later dates to the right.
     */
    fun find(nodes: List<Seen>, today: LocalDate): Strip? {
        val chips = ArrayList<Chip>()
        val words = nodes.filter { it.text.isNotBlank() && it.w > 0 && it.h > 0 }
        val days = words.filter { wd(it.text) >= 0 }
        val mons = words.filter { mo(it.text) >= 0 }
        fun sameColumn(a: Seen, b: Seen) = kotlin.math.abs(a.cx - b.cx) <= maxOf(a.w, b.w) / 2 + 12
        for (n in words) {
            val t = n.text.trim()
            if (numRx.matches(t)) {
                val near = n.h * 4
                val day = days.filter { sameColumn(it, n) && kotlin.math.abs(it.cy - n.cy) <= near && it !== n }.minByOrNull { kotlin.math.abs(it.cy - n.cy) }
                val mon = mons.filter { sameColumn(it, n) && kotlin.math.abs(it.cy - n.cy) <= near && it !== n }.minByOrNull { kotlin.math.abs(it.cy - n.cy) }
                if (day == null && mon == null) continue
                val date = dateOf(t.toInt(), mon?.let { mo(it.text) } ?: -1, day?.let { wd(it.text) } ?: -1, today) ?: continue
                chips += Chip(date, n.cx, n.cy, n.selected || day?.selected == true || mon?.selected == true)
            } else if (t.length <= 24 && t.any { it.isDigit() }) {
                // All in one piece: "FRI\n20\nNOV", "Fri, 20 Nov", "Nov 20".
                val parts = t.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }
                if (parts.size !in 2..3) continue
                val num = parts.singleOrNull { numRx.matches(it) }?.toInt() ?: continue
                val weekday = parts.map { wd(it) }.firstOrNull { it >= 0 } ?: -1
                val month = parts.map { mo(it) }.firstOrNull { it >= 0 } ?: -1
                if (parts.size != 1 + (if (weekday >= 0) 1 else 0) + (if (month >= 0) 1 else 0)) continue
                val date = dateOf(num, month, weekday, today) ?: continue
                chips += Chip(date, n.cx, n.cy, n.selected)
            }
        }
        if (chips.size < 3) return null
        // One line of them: the line with the most dates. Their numbers are small text, so "one line" is a narrow band.
        val band = (words.filter { numRx.matches(it.text.trim()) }.map { it.h }.sorted().let { it.getOrNull(it.size / 2) } ?: 40).coerceAtLeast(16)
        val row = chips.map { c -> chips.filter { kotlin.math.abs(it.y - c.y) <= band } }.maxByOrNull { it.size } ?: return null
        val line = row.distinctBy { it.day }.sortedBy { it.x }
        if (line.size < 3) return null
        // Left to right the dates must run forward; anything else is not a strip of dates (a calendar grid, a table).
        if (line.zipWithNext().any { (a, b) -> !b.day.isAfter(a.day) || b.x - a.x < 8 }) return null
        val gaps = line.zipWithNext().map { (a, b) -> b.x - a.x }.sorted()
        return Strip(line, gaps[gaps.size / 2])
    }

    /**
     * Where to look at each date's background: two spots beside its number, inside its chip and clear of the text.
     * (The chosen date is drawn on a filled chip; the others on the page's own colour.)
     */
    fun samplePoints(strip: Strip): List<Pair<Int, Int>> = strip.chips.flatMap { c ->
        val dx = (strip.pitch * 0.32).toInt().coerceAtLeast(6)
        listOf((c.x - dx).coerceAtLeast(0) to c.y, (c.x + dx) to c.y)
    }

    private fun far(a: Int, b: Int): Int =
        kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) + kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + kotlin.math.abs((a and 0xFF) - (b and 0xFF))

    /**
     * The highlighted date, told from the [colors] at [samplePoints] (two for each date, in the same order): the one
     * date whose background differs from what most of the strip stands on. Null when none or several differ.
     */
    fun byColor(strip: Strip, colors: List<Int>): Chip? {
        if (colors.size != strip.chips.size * 2) return null
        // The page's own colour: the one most samples are close to.
        val page = colors.maxByOrNull { c -> colors.count { far(it, c) <= 30 } } ?: return null
        val odd = strip.chips.filterIndexed { i, _ -> far(colors[i * 2], page) > 90 && far(colors[i * 2 + 1], page) > 90 }
        return odd.singleOrNull()
    }

    /** The highlighted date: the one the app marks, else the one the picture shows. */
    fun selected(strip: Strip, colors: List<Int>?): Chip? = strip.marked ?: colors?.let { byColor(strip, it) }

    /**
     * [want] is not among the dates on show: which way to drag the strip to bring it in ("earlier": finger moves right;
     * "later": finger moves left), or null when dragging cannot help (it lies between two dates that are both shown).
     */
    fun drag(strip: Strip, want: LocalDate): String? = when {
        strip.has(want) != null -> null
        want.isBefore(strip.chips.first().day) -> "earlier"
        want.isAfter(strip.chips.last().day) -> "later"
        else -> null
    }

    // ------------------------------------------------------------------ what is said about it

    fun name(day: LocalDate): String = day.format(fmt)

    fun alreadySaid(day: LocalDate) = "${name(day)} is already the selected day (its chip is the highlighted one), so nothing was tapped. Go on to the show times."
    fun nowSaid(day: LocalDate) = "${name(day)} is now the selected day: the chip that shows ${day.dayOfMonth} was pressed and it is the highlighted one. Go on to the show times."
    fun unsureSaid(day: LocalDate) = "The chip that shows ${day.dayOfMonth} (${name(day)}) was pressed. Which chip is highlighted could not be checked (no picture of the screen), so make sure of the day on the next screen before anything else."
    fun wrongSaid(day: LocalDate, got: LocalDate) = "Not done: the chip that shows ${day.dayOfMonth} was pressed twice, but the highlighted day is ${name(got)}. Do not go on to a show: tell the user that the app will not select ${name(day)}, or use your_turn (\"Tap ${name(day)} in the dates\")."
    fun missingSaid(day: LocalDate, strip: Strip) = "Not done: this page has no chip for ${name(day)}. Its dates run from ${strip.span()}. If the film has no show that day, tell the user which days it has and ask."
    const val NONE = "There is no strip of dates on this screen."
}
