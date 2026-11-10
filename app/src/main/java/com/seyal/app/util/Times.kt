package com.seyal.app.util

import com.seyal.app.data.Trigger
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Date maths is done here in code, never by the AI. */
object Times {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun nowText(): String {
        val now = LocalDateTime.now()
        val day = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return "$day, ${now.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH))} (${zone.id})"
    }

    fun toEpoch(dt: LocalDateTime): Long = dt.atZone(zone).toInstant().toEpochMilli()

    fun format(epoch: Long): String {
        val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epoch), zone)
        val today = LocalDate.now()
        val dayPart = when (dt.toLocalDate()) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> dt.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
        }
        return "$dayPart ${dt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))}"
    }

    /**
     * Accepts "2026-11-13T07:00", "2026-11-13 07:00", "07:00", "7am", "tomorrow 07:00",
     * "friday 07:00", "in 20 minutes". Times already in the past roll to the next day.
     */
    fun parse(raw: String?): LocalDateTime? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().lowercase()
        val iso = raw.trim().replace(' ', 'T')
        if (iso.length >= 16 && iso[4] == '-' && iso[10] == 'T') {
            runCatching { return LocalDateTime.parse(iso.take(16)) }
            runCatching { return LocalDateTime.parse(iso.take(19)) }
        }
        if (iso.length == 10 && iso[4] == '-') {
            runCatching { return LocalDate.parse(iso).atTime(9, 0) }
        }

        Regex("in (\\d+) ?(min|minute|minutes|hour|hours|h|m)s?").find(s)?.let { m ->
            val n = m.groupValues[1].toLong()
            return if (m.groupValues[2].startsWith("h")) LocalDateTime.now().plusHours(n) else LocalDateTime.now().plusMinutes(n)
        }

        val time = parseClock(s) ?: return null
        val now = LocalDateTime.now()
        var date = LocalDate.now()
        when {
            s.contains("tomorrow") -> date = date.plusDays(1)
            s.contains("today") -> {}
            else -> {
                DayOfWeek.values().firstOrNull { d -> s.contains(d.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase()) || s.contains(d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase()) }
                    ?.let { d ->
                        var diff = (d.value - date.dayOfWeek.value + 7) % 7
                        if (diff == 0 && time <= now.toLocalTime()) diff = 7
                        date = date.plusDays(diff.toLong())
                    }
            }
        }
        var dt = LocalDateTime.of(date, time)
        if (dt.isBefore(now) && !s.contains("today")) dt = dt.plusDays(1)
        return dt
    }

    fun parseClock(s: String): LocalTime? {
        Regex("(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?").find(s)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val ap = m.groupValues[3]
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (h in 0..23 && min in 0..59) return LocalTime.of(h, min)
        }
        Regex("(\\d{1,2})\\s*(am|pm)").find(s)?.let { m ->
            var h = m.groupValues[1].toInt()
            if (m.groupValues[2] == "pm" && h < 12) h += 12
            if (m.groupValues[2] == "am" && h == 12) h = 0
            if (h in 0..23) return LocalTime.of(h, 0)
        }
        return null
    }

    /** Next time a trigger fires, after [after]. 0 if never. */
    fun nextFire(t: Trigger, after: Long = System.currentTimeMillis()): Long {
        if (!t.isTime) return 0L
        val clock = parseClock(t.time) ?: LocalTime.of(9, 0)
        val afterDt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(after), zone)
        return when (t.repeat) {
            "daily" -> {
                var dt = LocalDateTime.of(afterDt.toLocalDate(), clock)
                if (!dt.isAfter(afterDt)) dt = dt.plusDays(1)
                toEpoch(dt)
            }
            "weekly" -> {
                val days = t.days.filter { it in 1..7 }.ifEmpty { listOf(afterDt.dayOfWeek.value) }
                var best: LocalDateTime? = null
                for (i in 0..7) {
                    val d = afterDt.toLocalDate().plusDays(i.toLong())
                    if (d.dayOfWeek.value in days) {
                        val dt = LocalDateTime.of(d, clock)
                        if (dt.isAfter(afterDt)) { best = dt; break }
                    }
                }
                best?.let { toEpoch(it) } ?: 0L
            }
            else -> {
                val dt = parse("${t.day ?: ""} ${t.time}") ?: return 0L
                if (dt.isAfter(afterDt)) toEpoch(dt) else 0L
            }
        }
    }
}
