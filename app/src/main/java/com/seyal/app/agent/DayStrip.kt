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
 * Second lesson (the same evening, v3.25.0): BookMyShow gives its date chips no readable text at all, and the one name
 * it does give ("Thu, 20 Nov") sat on the chip showing MON 23. So the words are taken from a picture of the screen,
 * read on the phone (device/Eyes), and when even that fails no date is touched.
 *
 * Everything here goes by words and where they are, never by descriptions, so it is tested without a phone.
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

    /** One date as it was read: the day it shows and the middle of its number. */
    class Chip(val day: LocalDate, val x: Int, val y: Int, val selected: Boolean) {
        fun label(): String = day.format(fmt)
    }

    /**
     * Things that stand in a row at a steady distance, each one more than the one before: the dates of a strip
     * (FRI 20 · SAT 21 · SUN 22), the numbers of a "how many" row (1 2 3 … 10). A few of them read for certain are
     * enough to know where each of the others is, also one whose text could not be read.
     * [firstValue] stands at [firstX]; each next value is [pitch] pixels further right; all at height [y].
     */
    class Row(val firstValue: Long, val firstX: Double, val pitch: Double, val y: Int, val read: Int) {
        fun xOf(v: Long): Int = Math.round(firstX + (v - firstValue) * pitch).toInt()
        /** The values that stand fully on a screen [width] pixels wide. */
        fun onScreen(width: Int): List<Long> {
            val edge = pitch * 0.34
            val lo = Math.ceil((edge - firstX) / pitch).toLong() + firstValue
            val hi = Math.floor((width - 1 - edge - firstX) / pitch).toLong() + firstValue
            return if (hi < lo) emptyList() else (lo..hi).toList()
        }
        /** Two spots beside each value on the screen, inside its chip and clear of its text: where its background is looked at. */
        fun samplePoints(width: Int): List<Pair<Int, Int>> = onScreen(width).flatMap { v ->
            val dx = (pitch * 0.3).toInt().coerceAtLeast(6)
            listOf((xOf(v) - dx).coerceAtLeast(0) to y, (xOf(v) + dx).coerceAtMost(width - 1) to y)
        }
        /**
         * The highlighted value, told from the [colors] at [samplePoints] (same order): the one whose background
         * differs from what most of the row stands on. Null when none or several differ.
         */
        fun byColor(width: Int, colors: List<Int>): Long? {
            val vs = onScreen(width)
            if (vs.isEmpty() || colors.size != vs.size * 2) return null
            val page = colors.maxByOrNull { c -> colors.count { far(it, c) <= 30 } } ?: return null
            return vs.filterIndexed { i, _ -> far(colors[i * 2], page) > 90 && far(colors[i * 2 + 1], page) > 90 }.singleOrNull()
        }

        companion object {
            /**
             * The row through [points] (value, x), all at about height [y]. Needs three or more; values must grow to the
             * right; every point must sit where the row says (within a quarter of a step), else it is not such a row.
             */
            fun fit(points: List<Pair<Long, Int>>, y: Int): Row? {
                val ps = points.distinctBy { it.first }.sortedBy { it.second }
                if (ps.size < 3) return null
                if (ps.zipWithNext().any { (a, b) -> b.first <= a.first || b.second - a.second < 8 }) return null
                val steps = ps.zipWithNext().map { (a, b) -> (b.second - a.second).toDouble() / (b.first - a.first) }.sorted()
                val pitch = steps[steps.size / 2]
                if (pitch < 8) return null
                // The row is laid through the middle one, so one sloppy reading at an end does not tilt it.
                val mid = ps[ps.size / 2]
                val row = Row(ps.first().first, mid.second - (mid.first - ps.first().first) * pitch, pitch, y, ps.size)
                if (ps.any { (v, x) -> kotlin.math.abs(x - row.xOf(v)) > pitch * 0.25 }) return null
                return row
            }
        }
    }

    private fun far(a: Int, b: Int): Int =
        kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) + kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + kotlin.math.abs((a and 0xFF) - (b and 0xFF))

    /** The strip: the dates that were read, and the row they stand in (which also places the dates that were not read). */
    class Strip(val chips: List<Chip>, val row: Row) {
        val pitch: Int get() = Math.round(row.pitch).toInt()
        /** The date the app itself marks as selected, when it marks exactly one. */
        val marked: Chip? get() = chips.filter { it.selected }.singleOrNull()
        /** Where [day] stands on a screen [width] wide (the middle of its number), or null when it is off the screen. */
        fun where(day: LocalDate, width: Int): Pair<Int, Int>? = if (day.toEpochDay() in row.onScreen(width)) row.xOf(day.toEpochDay()) to row.y else null
        fun days(width: Int): List<LocalDate> = row.onScreen(width).map { LocalDate.ofEpochDay(it) }
        fun span(width: Int): String = days(width).let { if (it.isEmpty()) "" else "${it.first().format(fmt)} to ${it.last().format(fmt)}" }
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
        val line = (chips.map { c -> chips.filter { kotlin.math.abs(it.y - c.y) <= band } }.maxByOrNull { it.size } ?: return null).distinctBy { it.day }.sortedBy { it.x }
        if (line.size < 3) return null
        // Left to right the dates must run forward, a day a step: anything else is not a strip of dates (a table, a calendar grid).
        val row = Row.fit(line.map { it.day.toEpochDay() to it.x }, line.map { it.y }.sorted()[line.size / 2]) ?: return null
        return Strip(line, row)
    }

    /** Where each date's background is looked at, on a screen [width] wide. */
    fun samplePoints(strip: Strip, width: Int): List<Pair<Int, Int>> = strip.row.samplePoints(width)

    /** The highlighted date, told from the [colors] at [samplePoints]: the one date on a filled chip. */
    fun byColor(strip: Strip, width: Int, colors: List<Int>): LocalDate? = strip.row.byColor(width, colors)?.let { LocalDate.ofEpochDay(it) }

    /** The highlighted date: the one the app marks, else the one the picture shows. */
    fun selected(strip: Strip, width: Int, colors: List<Int>?): LocalDate? = strip.marked?.day ?: colors?.let { byColor(strip, width, it) }

    /**
     * [want] is not on the screen: which way to drag the strip to bring it in ("earlier": finger moves right;
     * "later": finger moves left), or null when it is on the screen already.
     */
    fun drag(strip: Strip, want: LocalDate, width: Int): String? {
        val days = strip.days(width)
        return when {
            days.isEmpty() || want in days -> null
            want.isBefore(days.first()) -> "earlier"
            else -> "later"
        }
    }

    /**
     * The numbers of a "How many seats?" row (1 2 3 … 10) among words read off the screen: where each stands.
     * Six or more of them must be read, on one line, a step apart.
     */
    fun countRow(nodes: List<Seen>): Row? {
        val nums = nodes.filter { Regex("^(?:10|[1-9])$").matches(it.text.trim()) && it.w > 0 && it.h > 0 }
        if (nums.size < 6) return null
        val band = nums.map { it.h }.sorted()[nums.size / 2].coerceAtLeast(14)
        val line = nums.map { n -> nums.filter { kotlin.math.abs(it.cy - n.cy) <= band } }.maxByOrNull { it.size } ?: return null
        if (line.distinctBy { it.text.trim() }.size < 6) return null
        return Row.fit(line.map { it.text.trim().toLong() to it.cx }, line.map { it.cy }.sorted()[line.size / 2])
    }

    // ------------------------------------------------------------------ what is said about it

    fun name(day: LocalDate): String = day.format(fmt)

    fun alreadySaid(day: LocalDate) = "${name(day)} is already the selected day (its chip is the highlighted one), so nothing was tapped. Go on to the show times."
    fun nowSaid(day: LocalDate) = "${name(day)} is now the selected day: the chip that shows ${day.dayOfMonth} was pressed and it is the highlighted one. Go on to the show times."
    fun unsureSaid(day: LocalDate) = "The chip that shows ${day.dayOfMonth} (${name(day)}) was pressed. Which chip is highlighted could not be checked (no picture of the screen), so make sure of the day on the next screen before anything else."
    fun wrongSaid(day: LocalDate, got: LocalDate) = "Not done: the chip that shows ${day.dayOfMonth} was pressed twice, but the highlighted day is ${name(got)}. Do not go on to a show: tell the user that the app will not select ${name(day)}, or use your_turn (\"Tap ${name(day)} in the dates\")."
    fun missingSaid(day: LocalDate, span: String) = "Not done: this page has no chip for ${name(day)}. Its dates run from $span. If the film has no show that day, tell the user which days it has and ask."
    const val NONE = "There is no strip of dates on this screen."
    /**
     * Said when a date was to be tapped but the dates on the page could not be read. Nothing is tapped then: on a real
     * phone the thing BookMyShow names "Thu, 20 Nov" sat on the chip that showed MON 23, and a press on it changed a
     * right day into a wrong one, three times.
     */
    const val UNREADABLE = "Not done, and nothing was tapped: Seyal could not read the dates on this page, and what this page names its date chips cannot be trusted " +
        "(a chip named \"Thu, 20 Nov\" has shown MON 23). Leave the dates alone: the day that is selected now is usually the right one, and the seat screen's header names the day, where it is checked. " +
        "Only if that header shows another day: come back, take a screenshot, and use screen_tap_at on the chip that SHOWS the day's number."
}
