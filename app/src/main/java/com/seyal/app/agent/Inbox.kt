package com.seyal.app.agent

import com.seyal.app.util.Dates
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** One thing caught from a message that the user must not miss: a deadline, a bill, a renewal, a return window. */
class Caught(
    val id: String,
    /** deadline | bill | renewal | recharge | return */
    val kind: String,
    val title: String,
    val text: String,
    /** "WhatsApp · CSE Final Year" */
    val source: String,
    val link: String,
    /** When it is due (epoch ms). */
    val due: Long,
    val hasTime: Boolean,
    val amount: Int,
    val found: Long,
    /** open | done | dismissed */
    var state: String = "open",
    /** When the user was last told about it. */
    var told: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("title", title).put("text", text).put("source", source).put("link", link)
        .put("due", due).put("hasTime", hasTime).put("amount", amount).put("found", found).put("state", state).put("told", told)

    companion object {
        fun fromJson(o: JSONObject) = Caught(
            o.getString("id"), o.optString("kind"), o.optString("title"), o.optString("text"), o.optString("source"), o.optString("link"),
            o.optLong("due"), o.optBoolean("hasTime"), o.optInt("amount"), o.optLong("found"), o.optString("state", "open"), o.optLong("told"),
        )
    }
}

/**
 * Reads incoming messages for things with a date on them that cost something when missed. It only
 * reads and tells the user: it never replies anywhere. Everything is pattern matching on the phone
 * (no AI, nothing leaves the device).
 */
object Catcher {
    private val secret = Regex("\\b(otp|one[- ]time|verification code|passcode|password|cvv|upi pin|do not share|don'?t share)\\b", RegexOption.IGNORE_CASE)
    private val promo = Regex("\\b(offer|sale|% ?off|cashback|discount|coupon|voucher|lucky|winner|congratulations|pre-?approved|loan offer|limited period|shop now|buy now|flat \\d|deal)\\b", RegexOption.IGNORE_CASE)
    private val money = Regex("(?:₹|\\brs\\.?|\\binr)\\s?([\\d,]{1,9})(?:\\.\\d{1,2})?", RegexOption.IGNORE_CASE)
    val url = Regex("https?://[^\\s<>\"')]+", RegexOption.IGNORE_CASE)

    private val renewal = Regex("free trial|trial (ends|expires|period|is ending)|auto[- ]?renew|will (be )?renew|renews? on|subscription[^.]{0,50}(renew|expire|end|charged|debited)|will be (charged|debited|deducted)|auto[- ]?pay|auto[- ]?debit|e-?mandate|standing instruction", RegexOption.IGNORE_CASE)
    private val bill = Regex("\\b(bill|emi|premium|rent|fees?|dues?|outstanding|statement|instal?lment)\\b[^.]{0,70}\\b(due|pay by|last date|before|overdue|payable)|\\b(due date|due on|due by|pay by|pay before|minimum (amount )?due|total (amount )?due|overdue|disconnect(ion|ed)?)\\b", RegexOption.IGNORE_CASE)
    private val recharge = Regex("\\b(recharge|plan|pack|validity|data pack)\\b[^.]{0,50}\\b(expire[sd]?|expiring|ends?|ending|valid (till|until|upto)|over)\\b", RegexOption.IGNORE_CASE)
    private val returns = Regex("\\b(return|replace(ment)?|refund|exchange)\\b[^.]{0,50}\\b(window|closes?|ends?|last day|till|until|by|before|eligible)\\b", RegexOption.IGNORE_CASE)
    private val deadline = Regex(
        "last date|last day|deadline|due (on|by|date)|on or before|\\bbefore\\s+\\d|\\bby\\s+(\\d|tomorrow|today|tonight|mon|tue|wed|thu|fri|sat|sun|eod)|closes? (on|by|at|today|tomorrow)|ends? (on|by|today|tomorrow)|" +
            "\\b(register|registration|registrations|apply|application|applications|enrol+|enrol+ment|submit|submission|upload)\\b|fill (up |in |out )?(the |this )?(form|details)|google form|form link|" +
            "hall ticket|exam fee|fee payment|\\b(placement|recruitment|internship|hackathon|interview) (drive|form|registration|round|schedule|on)\\b|\\b(assignment|record|viva|internal|test|exam|seminar|project review)\\b[^.]{0,40}\\b(on|by|before|tomorrow|today|date)\\b|கடைசி நாள்|கடைசி தேதி",
        RegexOption.IGNORE_CASE,
    )
    private val campus = Regex("\\b(exam|semester|tuition|college|hostel|registration|application|arrear|revaluation|convocation|bus) fees?\\b|hall ticket|\\b(placement|internship|hackathon|symposium|scholarship)\\b", RegexOption.IGNORE_CASE)
    private val formLink = Regex("forms\\.gle|docs\\.google\\.com/forms|forms\\.office\\.com|typeform\\.com|unstop\\.com|\\b(register|apply|registration|form)\\b", RegexOption.IGNORE_CASE)

    private fun zone() = ZoneId.systemDefault()
    private fun epoch(dt: LocalDateTime) = dt.atZone(zone()).toInstant().toEpochMilli()

    /**
     * What, if anything, in this message the user must not miss. [app]: the app's name; [chat]: the
     * notification's title (person or group); [now]: the current time (epoch ms).
     */
    fun scan(app: String, chat: String, text: String, now: Long, newId: () -> String = { java.util.UUID.randomUUID().toString().take(8) }): Caught? {
        val t = text.trim()
        if (t.length < 12 || secret.containsMatchIn(t)) return null
        val nowDt = java.time.Instant.ofEpochMilli(now).atZone(zone()).toLocalDateTime()
        val link = url.find(t)?.value?.trimEnd('.', ',', ')').orEmpty()
        val amount = money.find(t)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
        val source = (if (chat.isBlank() || chat.equals(app, true)) app else "$app · ${chat.take(40)}").trim()

        fun make(kind: String, title: String, at: Dates.Found?): Caught? {
            val due = at?.let { epoch(it.at) } ?: return null
            // An old message about a day long gone is not news; a far-off date is likely not a deadline at all.
            if (due < now - 12 * 3600_000L || due > now + 120L * 24 * 3600_000L) return null
            return Caught(newId(), kind, title.take(80), t.take(500), source, link, due, at.hasTime, amount, now)
        }
        fun where(r: Regex) = r.find(t)?.range?.first ?: 0
        fun who() = chat.substringBefore(":").trim().ifBlank { app }.take(30)
        fun rupees() = if (amount > 0) " ₹$amount" else ""

        // College notices about fees and forms are deadlines to act on, not bills from a biller.
        if (campus.containsMatchIn(t) && !promo.containsMatchIn(t)) deadline.find(t)?.let { m -> make("deadline", headline(t), Dates.find(t, nowDt, m.range.first))?.let { return it } }
        // Money first: these are specific, and the cost of missing one is real.
        if (renewal.containsMatchIn(t)) make("renewal", "Auto-charge coming${rupees()} (${who()})", Dates.find(t, nowDt, where(renewal)))?.let { return it }
        if (bill.containsMatchIn(t) && !promo.containsMatchIn(t)) make("bill", "Payment due${rupees()} (${who()})", Dates.find(t, nowDt, where(bill)))?.let { return it }
        if (recharge.containsMatchIn(t) && !promo.containsMatchIn(t)) make("recharge", "Plan ending (${who()})", Dates.find(t, nowDt, where(recharge)))?.let { return it }
        if (returns.containsMatchIn(t) && !promo.containsMatchIn(t)) make("return", "Return window closing (${who()})", Dates.find(t, nowDt, where(returns)))?.let { return it }

        // Deadlines: a deadline word with a date; or a form to fill with a date anywhere in the message.
        if (promo.containsMatchIn(t)) return null
        val m = deadline.find(t)
        val hasForm = link.isNotBlank() && formLink.containsMatchIn(t)
        if (m == null && !hasForm) return null
        val at = Dates.find(t, nowDt, m?.range?.first ?: 0) ?: return null
        return make("deadline", headline(t), at)
    }

    /** The first meaningful line of a notice, as its title. */
    internal fun headline(text: String): String {
        val line = text.lines().map { it.trim().trim('*', '_', '-', '•', '#', ' ') }
            .firstOrNull { it.length >= 8 && !Regex("^(dear|hi|hello|respected|good (morning|evening|afternoon)|greetings|forwarded)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: text.trim()
        return url.replace(line, "").replace(Regex("\\s+"), " ").trim().let { if (it.length > 72) it.take(70).substringBeforeLast(' ') + "…" else it }
    }

    /**
     * The times to remind about something due at [due]: the evening before, the morning of, and two
     * hours before a set time. Only those still ahead of [now] and before the deadline.
     */
    fun reminderTimes(due: Long, hasTime: Boolean, now: Long): List<Long> {
        val d = java.time.Instant.ofEpochMilli(due).atZone(zone()).toLocalDateTime()
        val times = mutableListOf(epoch(d.toLocalDate().minusDays(1).atTime(LocalTime.of(18, 0))), epoch(d.toLocalDate().atTime(LocalTime.of(8, 30))))
        if (hasTime) times += due - 2 * 3600_000L
        return times.filter { it > now && it < due }.distinct().sorted()
    }

    /** "today 5:00 PM", "tomorrow", "Fri 15 Nov", "3 days ago". */
    fun whenText(due: Long, hasTime: Boolean, now: Long): String {
        val d = java.time.Instant.ofEpochMilli(due).atZone(zone()).toLocalDateTime()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, d.toLocalDate())
        val day = when {
            days == 0L -> "today"; days == 1L -> "tomorrow"; days == -1L -> "yesterday"
            days < -1 -> "${-days} days ago"
            else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH))
        }
        return if (hasTime) "$day ${d.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH))}" else day
    }
}

/** The list of caught things, kept on the phone. */
object Inbox {
    private var file: File? = null
    private val items = mutableListOf<Caught>()
    /** Called after any change (the app sets it: reschedule reminders, refresh the screen). */
    @Volatile var onChange: () -> Unit = {}

    @Synchronized fun init(dir: File) {
        file = File(dir, "seyal/inbox.json"); items.clear()
        runCatching { val a = JSONArray(file!!.readText()); for (i in 0 until a.length()) items += Caught.fromJson(a.getJSONObject(i)) }
    }
    private fun save() { val f = file ?: return; runCatching { f.parentFile?.mkdirs(); f.writeText(JSONArray(items.map { it.toJson() }).toString()) } }

    @Synchronized fun all(): List<Caught> = items.toList()
    /** Still to be dealt with, soonest first. */
    @Synchronized fun open(now: Long = System.currentTimeMillis()): List<Caught> = items.filter { it.state == "open" && it.due > now - 24 * 3600_000L }.sortedBy { it.due }
    @Synchronized fun get(id: String): Caught? = items.firstOrNull { it.id == id }
    @Synchronized fun clear() { items.clear(); save(); onChange() }

    private fun same(a: Caught, b: Caught): Boolean =
        (a.link.isNotBlank() && a.link == b.link) || a.text.take(80).equals(b.text.take(80), true) ||
            (a.kind == b.kind && a.kind != "deadline" && a.source == b.source && kotlin.math.abs(a.due - b.due) < 36 * 3600_000L && a.amount == b.amount)

    /** Adds [c] unless the same thing is already there. Returns true when it is new. */
    @Synchronized fun add(c: Caught, now: Long = System.currentTimeMillis()): Boolean {
        items.removeAll { (it.state != "open" && it.found < now - 14L * 24 * 3600_000L) || it.due < now - 7L * 24 * 3600_000L }
        if (items.any { same(it, c) }) return false
        items += c
        while (items.size > 200) items.removeAt(0)
        save(); onChange()
        return true
    }

    @Synchronized fun mark(id: String, state: String): Caught? = items.firstOrNull { it.id == id }?.also { it.state = state; save(); onChange() }
    @Synchronized fun told(id: String, at: Long) { items.firstOrNull { it.id == id }?.let { it.told = at; save() } }

    /** The next moment anything open needs a reminder, or null. */
    @Synchronized fun nextReminder(now: Long): Long? = items.filter { it.state == "open" }.mapNotNull { c -> Catcher.reminderTimes(c.due, c.hasTime, maxOf(now, c.told + 60_000)).firstOrNull() }.minOrNull()

    /** The open things whose reminder time has come (and that were not just told). */
    @Synchronized fun dueReminders(now: Long): List<Caught> = items.filter { c ->
        c.state == "open" && c.due > now && Catcher.reminderTimes(c.due, c.hasTime, c.told + 60_000).any { it <= now + 60_000 }
    }
}
