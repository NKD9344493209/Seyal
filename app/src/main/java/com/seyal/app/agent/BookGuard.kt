package com.seyal.app.agent

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Three mistakes seen in one recording of a real booking (19 Nov 2026, v3.21.0), each now stopped by a plain rule:
 *
 * 1. The user said the 20th. Seyal tapped a date chip that showed "SUN 22 NOV" because the app had left an old
 *    description ("Thu, 20 Nov") on it. → A date chip is read by what it SHOWS, and a chip for another day than
 *    the user's is not tapped.
 * 2. The "How many seats?" sheet stood at 6 from an earlier visit; the user wanted 2. → The sheet is set by code.
 * 3. Seyal then tapped a seat itself, and the app selected six. → Seyal never taps a seat: seats are the user's.
 */
object BookGuard {
    private val I = RegexOption.IGNORE_CASE
    private const val DAY = "(?:mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)[a-z]*"
    private const val MON = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*"
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** A seat on a seat map, as apps describe one: "Row E, Seat 23, GOLD, Price ₹190", "Seat F10 available". */
    private val seat = Regex("\\brow\\s+[a-z]{1,2}\\b[\\s,·-]+seat\\s*\\d{1,3}\\b|\\bseat\\s+(?:no\\.?\\s*)?[a-z]{0,2}\\d{1,3}\\b.{0,30}\\b(?:available|price|selected|₹|rs)", I)

    /** [label] is a seat on a seat map. */
    fun isSeat(label: String): Boolean = seat.containsMatchIn(label)

    const val SEAT = "Not done: Seyal never picks seats. Which seats is the user's choice, and one tap here can select a whole row. " +
        "First make sure the day and the number of tickets on this screen are the user's, then call your_turn(\"Pick your seats, then tap Done\")."

    /** What a description and the visible words say when they are both there: the numbers in each. */
    private fun numbers(s: String) = Regex("\\d+").findAll(s).map { it.value.trimStart('0').ifEmpty { "0" } }.toSet()

    /**
     * The name of something tappable: its own [text], else its [description], then the words [inside] it.
     * When the description and the words inside disagree on a number ("Thu, 20 Nov" over "SUN", "22", "NOV"),
     * the description is a leftover from an earlier screen and is dropped: what the user can see is the truth.
     */
    fun name(text: String, description: String, inside: List<String>): List<String> {
        val parts = ArrayList<String>()
        if (text.isNotBlank()) parts += text
        else if (description.isNotBlank()) {
            val shown = numbers(inside.joinToString(" "))
            val said = numbers(description)
            if (!(shown.isNotEmpty() && said.isNotEmpty() && shown.intersect(said).isEmpty())) parts += description
        }
        for (w in inside) if (w.isNotBlank() && w !in parts) parts += w
        return parts
    }

    private val chip = Regex("^\\W*(?:($DAY)\\W+)?(\\d{1,2})(?:st|nd|rd|th)?\\W+($MON)\\W*$|^\\W*(?:($DAY)\\W+)?($MON)\\W+(\\d{1,2})(?:st|nd|rd|th)?\\W*$", I)

    /**
     * The day a date chip stands for ("FRI · 20 · NOV", "Sun, 22 Nov", "Nov 20"), or null when [label] is not a date
     * chip. The year is the one that puts the day nearest after [today].
     */
    fun chipDay(label: String, today: LocalDate): LocalDate? {
        val clean = label.replace(Regex("\\s*\\((?:selected|focused|disabled)\\)\\s*$", I), "").trim()
        if (clean.length > 24) return null
        val m = chip.find(clean) ?: return null
        val g = m.groupValues
        val day = (g[2].ifBlank { g[6] }).toIntOrNull() ?: return null
        val month = months.indexOf((g[3].ifBlank { g[5] }).lowercase().take(3)) + 1
        if (month < 1) return null
        val thisYear = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        return if (thisYear.isBefore(today.minusDays(30))) thisYear.plusYears(1) else thisYear
    }

    /**
     * The day a tappable thing stands for when it is a date chip, however its name was put together
     * ("FRI · 20 · NOV", "Fri, 20 Nov · FRI · 20 · NOV", "20 Nov"). Null when it is not one, or its parts name different days.
     */
    fun dayOfLabel(label: String, today: LocalDate): LocalDate? {
        chipDay(label, today)?.let { return it }
        val parts = label.split(" · ").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size !in 2..6) return null
        val found = (parts.mapNotNull { chipDay(it, today) } + listOfNotNull(chipDay(parts.takeLast(3).joinToString(" "), today), chipDay(parts.takeLast(2).joinToString(" "), today))).distinct()
        return found.singleOrNull()
    }

    /**
     * Before a tap in a booking: null when it may go ahead, else why not (in words for the AI).
     * [label]: the name of what is about to be tapped. [wanted]: the day the user asked for ("2026-11-20"), "" when none.
     */
    fun beforeTap(label: String, wanted: String, now: LocalDateTime): String? {
        if (isSeat(label)) return SEAT
        val want = runCatching { LocalDate.parse(wanted) }.getOrNull() ?: return null
        val day = dayOfLabel(label, now.toLocalDate()) ?: return null
        if (day == want) return null
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH)
        return "Not done: that is the chip for ${day.format(fmt)}, but the user asked for ${want.format(fmt)}. Call screen_day with date ${want} instead: " +
            "it presses the chip that SHOWS ${want.dayOfMonth} and checks which one is highlighted."
    }

    /** The "How many seats?" sheet: what to tap to set it to the user's count. */
    class Sheet(val number: Item?, val go: Item?, val alreadySet: Boolean)

    private val goRx = Regex("^(select seats?|continue|proceed|done|book seats?)$", I)

    /**
     * The seat-count sheet on [items] and how to set it to [count]: the number to tap (null when it is already the one
     * selected, or cannot be found) and the button that goes on. Null when the screen is not that sheet.
     */
    fun seatSheet(items: List<Item>, count: Int): Sheet? {
        if (count !in 1..10) return null
        if (items.none { Regex("how many seats|number of seats|how many tickets|select number of", I).containsMatchIn(it.label) }) return null
        val digits = items.filter { !it.isField && Regex("^(?:10|[1-9])$").matches(it.label.trim()) }
        // The row of numbers: at least five of them on one line.
        val row = digits.groupBy { it.y / 40 }.values.maxByOrNull { it.size }?.takeIf { it.size >= 5 } ?: return Sheet(null, items.firstOrNull { goRx.matches(it.label.trim()) }, alreadySet = false)
        val mine = row.firstOrNull { it.label.trim() == count.toString() }
        // "Already set" is believed only when the app marks exactly one number as selected and it is the user's.
        val marked = row.filter { it.extra.contains("selected", true) }
        val already = mine != null && marked.size == 1 && marked[0] === mine
        return Sheet(if (already) null else mine, items.firstOrNull { goRx.matches(it.label.trim()) }, already)
    }

    /**
     * The seat map itself is open on [screen] (not the "How many seats?" sheet over it, and not after seats were
     * picked): the header names the number of tickets and the map's own words are there. This is where the user is
     * handed the screen to pick seats.
     */
    fun seatMap(screen: String): Boolean =
        Regex("\\b\\d{1,2}\\s*tickets?\\b", I).containsMatchIn(screen) &&
            !Regex("how many seats|number of seats|how many tickets|select number of", I).containsMatchIn(screen) &&
            Regex("\\b(?:available|sold|selected|bestseller|screen this way|all eyes this way)\\b", I).findAll(screen).map { it.value.lowercase() }.distinct().count() >= 2 &&
            !Regex("\\b(?:pay|proceed to pay)\\s*(?:₹|rs\\.?)", I).containsMatchIn(screen)

    /** The user asked Seyal to choose the seats ("pick the seats for me", "best seats"): then they are not handed the seat map. */
    fun seyalPicksSeats(words: List<String>): Boolean =
        words.any { Regex("\\b(?:pick|choose|select)\\s+(?:the\\s+|my\\s+)?seats?\\s+(?:for me|yourself|your ?self)\\b|\\byou\\s+(?:pick|choose|select)\\s+(?:the\\s+)?seats?\\b|\\bbest\\s+(?:available\\s+)?seats?\\b|\\bany\\s+seats?\\b", I).containsMatchIn(it) }

    /** What the user is told on the bar over the seat map. */
    fun pickSeats(count: Int): String = if (count >= 2) "Pick your $count seats, side by side, then tap Done" else "Pick your seat, then tap Done"

    private val showTime = Regex("\\b\\d{1,2}:\\d{2}\\s?(?:AM|PM)\\b", I)
    /** [label] is a show time ("06:00 AM · RGB LASER"): the tap on one opens the count sheet or the seat map a moment later. */
    fun isShowTime(label: String): Boolean = showTime.containsMatchIn(label) && label.length <= 60

    /** The screen is a seat map or its count sheet (the only places where the header's day and ticket count are checked). */
    fun seatPage(screen: String): Boolean = Regex("\\b\\d{1,2} tickets?\\b|how many seats|select seats", I).containsMatchIn(screen)
}
