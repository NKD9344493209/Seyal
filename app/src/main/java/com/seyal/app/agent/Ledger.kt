package com.seyal.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One payment seen in a notification: money that came in or went out. */
class Txn(
    val id: String,
    /** Rupees (paise are rounded). */
    val amount: Int,
    val incoming: Boolean,
    /** Who paid, or who was paid ("Ram Kumar", "SWIGGY"). May be empty. */
    val party: String,
    /** The app that showed it ("Google Pay", "Messages"). */
    val via: String,
    /** The bank's or UPI's reference number, when shown. */
    val ref: String,
    val at: Long,
    /** food | travel | shopping | bills | groceries | health | other (for money going out). */
    val category: String,
    /** It came as a bank SMS rather than from a payment app (the same payment often arrives as both). */
    val bank: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("amount", amount).put("in", incoming).put("party", party).put("via", via).put("ref", ref).put("at", at).put("cat", category).put("bank", bank)
    companion object {
        fun fromJson(o: JSONObject) = Txn(o.getString("id"), o.optInt("amount"), o.optBoolean("in"), o.optString("party"), o.optString("via"), o.optString("ref"), o.optLong("at"), o.optString("cat", "other"), o.optBoolean("bank"))
    }
}

/**
 * Reads payment notifications (Google Pay, PhonePe, Paytm, bank SMS) into [Txn]s. Pattern matching on
 * the phone: no AI, nothing typed by the user, nothing sent anywhere.
 */
object MoneyParser {
    private val secret = Regex("\\b(otp|one[- ]time|verification code|cvv|do not share|don'?t share)\\b", RegexOption.IGNORE_CASE)
    /** Not a payment that happened: a request, a failure, an offer, a balance, a bill to pay. */
    private val notDone = Regex(
        "\\b(request(ed|ing|s)?( you)? (to pay|money|₹|rs)|has requested|requesting|collect request|failed|declined|unsuccessful|could ?n[o']t be (processed|completed)|not (processed|completed|successful)|reversed|reversal|pending|" +
            "will be (debited|deducted|charged|credited)|is due|due (on|by|date)|overdue|outstanding|minimum due|pay now|pay your|cashback (of|up ?to|worth)|get (up ?to )?(₹|rs)|win (₹|rs)|offer|coupon|voucher|" +
            "avl\\.? ?bal|available balance is|balance (is|:)|limit (is|:)|eligible|pre-?approved|loan|emi (of|is)|scheduled|autopay (set|registered|created)|mandate (created|registered|set))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val amountRx = Regex("(?:₹|\\brs\\.?|\\binr)\\s?([\\d,]{1,12}(?:\\.\\d{1,2})?)|\\b(?:debited|credited) (?:by|for|with) (?:(?:rs\\.?|inr) ?)?([\\d,]{1,12}(?:\\.\\d{1,2})?)", RegexOption.IGNORE_CASE)
    private val incomingRx = Regex("\\b(paid you|sent you|received|credited|deposited|has paid|payment (of .{0,20})?received|money received|you(?:'ve| have) (?:got|received))\\b", RegexOption.IGNORE_CASE)
    private val outgoingRx = Regex("\\b(debited|paid to|you paid|sent to|spent|paid ₹|paid rs|payment (of .{0,20})?(to|successful|made)|purchase|withdrawn|txn of|transferred to|trf to|paid successfully|sent ₹|sent rs)\\b", RegexOption.IGNORE_CASE)
    private val refRx = Regex("\\b(?:ref(?:erence)?(?:\\.? ?(?:no|number|id))?|upi(?: ref)?(?:\\.? ?(?:no|id))?|txn(?:\\.? ?(?:id|no))?|rrn|utr)[.:\\s#-]*([0-9]{8,})", RegexOption.IGNORE_CASE)
    private val chatApps = Regex("whatsapp|telegram|instagram|messenger|signal|snapchat|discord|slack|teams|gmail|mail|outlook", RegexOption.IGNORE_CASE)
    private val bankSender = Regex("^[A-Z]{2}-[A-Z0-9]{3,9}(-[A-Z])?$")

    private val partyPatterns = listOf(
        Regex("^([\\p{L}][\\p{L} .'&-]{1,40}?) (?:has )?(?:paid|sent) you", RegexOption.IGNORE_CASE),
        Regex("\\b(?:received|credited).{0,40}?\\bfrom (?:vpa )?([\\p{L}0-9][\\p{L}0-9 .'&@_-]{1,40})", RegexOption.IGNORE_CASE),
        Regex("\\b(?:paid|sent|transferred|trf|payment(?: of .{0,20})?) to (?:vpa )?([\\p{L}0-9][\\p{L}0-9 .'&@_-]{1,40})", RegexOption.IGNORE_CASE),
        Regex(";\\s*([\\p{L}0-9][\\p{L}0-9 .'&@_-]{1,40}?) credited", RegexOption.IGNORE_CASE),
        Regex("\\b(?:spent|purchase|txn).{0,40}?\\b(?:at|on) ([\\p{L}][\\p{L}0-9 .'&_-]{1,40})", RegexOption.IGNORE_CASE),
        Regex("\\bby (?:upi[ /-]*)?([\\p{L}][\\p{L} .'&-]{2,40})", RegexOption.IGNORE_CASE),
    )

    private fun cleanParty(raw: String): String = raw
        .split(Regex("\\s+(?:on|ref\\w*|upi|rrn|utr|txn\\w*|imps|neft|via|using|thru|through|at|in|for|a/c|ac|if|not|call|avl|bal\\w*|to your|from your)\\b|\\s+\\d{2}[-/]|\\s+\\d{6,}|[.(,;:]", RegexOption.IGNORE_CASE)).first()
        .trim().trim('-', '@', ' ').take(32).let { if (it.contains('@')) it.substringBefore('@') else it }
        .takeIf { it.length >= 2 && !Regex("^(your|you|a/c|account|bank|upi|the|ac|acct)$", RegexOption.IGNORE_CASE).matches(it) }.orEmpty()

    private val categories = listOf(
        "food" to Regex("swiggy|zomato|restaurant|hotel|cafe|coffee|bakery|biryani|dominos|pizza|kfc|mcdonald|burger|a2b|saravana|annapoorna|eatsure|tea|juice|mess|canteen|sweets", RegexOption.IGNORE_CASE),
        "groceries" to Regex("blinkit|zepto|instamart|bigbasket|big basket|dmart|d-mart|jiomart|supermarket|grocery|provision|vegetable|fruits|milk|aavin|store|mart", RegexOption.IGNORE_CASE),
        "travel" to Regex("uber|ola|rapido|irctc|redbus|abhibus|metro|petrol|fuel|bunk|hpcl|bpcl|iocl|indian oil|bharat petroleum|fastag|toll|parking|makemytrip|ixigo|indigo|tnstc|bus|auto", RegexOption.IGNORE_CASE),
        "shopping" to Regex("amazon|flipkart|myntra|meesho|ajio|nykaa|trends|textile|garments|fashion|mall|electronics|croma|reliance digital|decathlon|lenskart", RegexOption.IGNORE_CASE),
        "bills" to Regex("recharge|jio|airtel|vodafone|\\bvi\\b|bsnl|electricity|tneb|tangedco|broadband|act fibernet|dth|tata play|gas|lpg|indane|insurance|lic|rent|emi|netflix|hotstar|spotify|youtube premium|prime", RegexOption.IGNORE_CASE),
        "health" to Regex("pharmacy|medical|medicals|apollo|hospital|clinic|lab|diagnostic|pharmeasy|1mg|netmeds|doctor", RegexOption.IGNORE_CASE),
    )
    fun category(party: String, text: String): String = categories.firstOrNull { it.second.containsMatchIn(party) }?.first ?: categories.firstOrNull { it.second.containsMatchIn(text) }?.first ?: "other"

    /** The payment this notification reports, or null when it is not one. [title]: the notification's title. */
    fun parse(app: String, title: String, text: String, now: Long, newId: () -> String = { java.util.UUID.randomUUID().toString().take(8) }): Txn? {
        val t = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (t.length < 8 || chatApps.containsMatchIn(app) || secret.containsMatchIn(t) || notDone.containsMatchIn(t)) return null
        val inAt = incomingRx.find(t)?.range?.first ?: -1
        val outAt = outgoingRx.find(t)?.range?.first ?: -1
        if (inAt < 0 && outAt < 0) return null
        // "A/c debited for Rs 250; SWIGGY credited": the verb that comes first is about the user's account.
        val incoming = if (inAt >= 0 && outAt >= 0) inAt < outAt else inAt >= 0
        val m = amountRx.find(t) ?: return null
        val amount = (m.groupValues[1].ifBlank { m.groupValues[2] }).replace(",", "").toDoubleOrNull()?.let { Math.round(it).toInt() } ?: return null
        if (amount <= 0 || amount > 50_00_000) return null
        var party = ""
        for (p in partyPatterns) { party = p.find(t)?.groupValues?.get(1)?.let { cleanParty(it) }.orEmpty(); if (party.isNotBlank()) break }
        if (party.isBlank() && incoming) Regex("^([\\p{L}][\\p{L} .'-]{1,30})$").find(title.trim())?.value?.takeIf { !app.contains(it, true) && !Regex("pay|bank|upi|paytm|phonepe|bhim|messages", RegexOption.IGNORE_CASE).containsMatchIn(it) }?.let { party = it }
        val bank = bankSender.matches(title.trim().uppercase()) || Regex("\\ba/?c\\b|\\bacct\\b|\\baccount\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)
        return Txn(newId(), amount, incoming, party, app, refRx.find(t)?.groupValues?.get(1).orEmpty(), now, if (incoming) "income" else category(party, t), bank)
    }
}

/** The payments seen so far, kept on the phone, with totals. */
object Ledger {
    private var file: File? = null
    private val list = mutableListOf<Txn>()
    @Volatile var onChange: () -> Unit = {}

    @Synchronized fun init(dir: File) {
        file = File(dir, "seyal/ledger.json"); list.clear()
        runCatching { val a = JSONArray(file!!.readText()); for (i in 0 until a.length()) list += Txn.fromJson(a.getJSONObject(i)) }
    }
    private fun save() { val f = file ?: return; runCatching { f.parentFile?.mkdirs(); f.writeText(JSONArray(list.map { it.toJson() }).toString()) } }
    @Synchronized fun clear() { list.clear(); save(); onChange() }
    @Synchronized fun all(): List<Txn> = list.toList()

    /**
     * Adds [t] unless it is the same payment seen again: the same reference number, the payment app and
     * the bank both reporting it, or the app re-posting its own notification.
     */
    @Synchronized fun add(t: Txn): Boolean {
        val dup = list.asReversed().take(60).any { o ->
            o.incoming == t.incoming && o.amount == t.amount && (
                (t.ref.isNotBlank() && o.ref == t.ref) ||
                    (kotlin.math.abs(o.at - t.at) < 4 * 60_000L && o.bank != t.bank && (t.ref.isBlank() || o.ref.isBlank())) ||
                    (kotlin.math.abs(o.at - t.at) < 25_000L && o.via == t.via && o.party.equals(t.party, true))
                )
        }
        if (dup) return false
        list += t
        val cutoff = t.at - 400L * 24 * 3600_000L
        list.removeAll { it.at < cutoff }
        while (list.size > 6000) list.removeAt(0)
        save(); onChange()
        return true
    }

    private fun zone() = ZoneId.systemDefault()
    /** The start and end (epoch ms) of today | yesterday | week (last 7 days) | month (this calendar month). */
    fun range(name: String, now: Long): Pair<Long, Long> {
        val today = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
        fun start(d: LocalDate) = d.atStartOfDay(zone()).toInstant().toEpochMilli()
        return when (name.lowercase()) {
            "yesterday" -> start(today.minusDays(1)) to start(today)
            "week" -> start(today.minusDays(6)) to now + 1
            "month" -> start(today.withDayOfMonth(1)) to now + 1
            "last_month" -> start(today.withDayOfMonth(1).minusMonths(1)) to start(today.withDayOfMonth(1))
            else -> start(today) to now + 1
        }
    }

    @Synchronized fun between(from: Long, to: Long, incoming: Boolean? = null): List<Txn> = list.filter { it.at in from until to && (incoming == null || it.incoming == incoming) }

    private fun rupees(n: Int): String {
        val s = n.toString()
        if (s.length <= 3) return "₹$s"
        val last3 = s.takeLast(3); var rest = s.dropLast(3)
        val parts = mutableListOf<String>()
        while (rest.length > 2) { parts.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return "₹" + parts.joinToString(",") + "," + last3
    }

    /** "Received today: ₹4,250 in 17 payments…" and "Spent today: ₹640 (food ₹420, travel ₹220)". [direction]: in | out | both. */
    fun summary(rangeName: String, direction: String, now: Long): String {
        val (from, to) = range(rangeName, now)
        val label = when (rangeName.lowercase()) { "yesterday" -> "yesterday"; "week" -> "in the last 7 days"; "month" -> "this month"; "last_month" -> "last month"; else -> "today" }
        val lines = mutableListOf<String>()
        if (direction != "out") {
            val got = between(from, to, true)
            if (got.isEmpty()) lines += "Received $label: nothing seen."
            else {
                lines += "Received $label: ${rupees(got.sumOf { it.amount })} in ${got.size} payment${if (got.size == 1) "" else "s"}."
                val top = got.filter { it.party.isNotBlank() }.groupBy { it.party.lowercase() }.map { (_, v) -> v.first().party to v.sumOf { it.amount } }.sortedByDescending { it.second }.take(3)
                if (top.isNotEmpty() && got.size > 1) lines += "Most from: " + top.joinToString(", ") { "${it.first} ${rupees(it.second)}" } + "."
            }
        }
        if (direction != "in") {
            val spent = between(from, to, false)
            if (spent.isEmpty()) lines += "Spent $label: nothing seen."
            else {
                val cats = spent.groupBy { it.category }.map { (c, v) -> c to v.sumOf { it.amount } }.sortedByDescending { it.second }
                lines += "Spent $label: ${rupees(spent.sumOf { it.amount })} in ${spent.size} payment${if (spent.size == 1) "" else "s"}" + (if (cats.size > 1 || cats.first().first != "other") " (" + cats.take(5).joinToString(", ") { "${it.first} ${rupees(it.second)}" } + ")." else ".")
                val top = spent.maxByOrNull { it.amount }
                if (top != null && spent.size > 1) lines += "Biggest: ${rupees(top.amount)}" + (if (top.party.isNotBlank()) " to ${top.party}" else "") + "."
            }
        }
        return lines.joinToString("\n")
    }
}
