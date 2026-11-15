package com.seyal.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One order on its way, pieced together from the shop's and the courier's notifications. */
class Parcel(
    /** shop + order number or item: the same order keeps the same key as its status moves on. */
    val key: String,
    val shop: String,
    val item: String,
    /** ordered | shipped | out | delivered | cancelled | returned */
    val status: String,
    /** "today", "tomorrow", "Tue 17 Nov" as the message put it; may be empty. */
    val eta: String,
    val updated: Long,
) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("shop", shop).put("item", item).put("status", status).put("eta", eta).put("updated", updated)
    /** "Amazon: boAt earphones, out for delivery (today)" */
    fun line(): String = "$shop: " + (item.ifBlank { "your order" }) + ", " + when (status) {
        "ordered" -> "order placed"; "shipped" -> "shipped"; "out" -> "out for delivery"; "delivered" -> "delivered"; "cancelled" -> "cancelled"; else -> "return or refund in progress"
    } + (if (eta.isNotBlank() && status != "delivered") " ($eta)" else "")
    companion object { fun fromJson(o: JSONObject) = Parcel(o.getString("key"), o.optString("shop"), o.optString("item"), o.optString("status"), o.optString("eta"), o.optLong("updated")) }
}

/** Reads order and delivery notifications. Pattern matching on the phone, no AI. */
object ParcelParser {
    private val shops = listOf(
        "Amazon" to "amazon", "Flipkart" to "flipkart|ekart", "Meesho" to "meesho", "Myntra" to "myntra", "Ajio" to "ajio", "Nykaa" to "nykaa", "Blinkit" to "blinkit", "Zepto" to "zepto",
        "Instamart" to "instamart", "BigBasket" to "bigbasket|big basket", "JioMart" to "jiomart", "Swiggy" to "swiggy", "Zomato" to "zomato", "Tata" to "tata (cliq|neu|1mg)", "Snapdeal" to "snapdeal",
        "FirstCry" to "firstcry", "Lenskart" to "lenskart", "Delhivery" to "delhivery", "Blue Dart" to "blue ?dart", "DTDC" to "dtdc", "Xpressbees" to "xpressbees", "Ecom Express" to "ecom express",
        "Shadowfax" to "shadowfax", "India Post" to "india ?post|speed post", "Shiprocket" to "shiprocket",
    ).map { it.first to Regex(it.second, RegexOption.IGNORE_CASE) }
    private val food = setOf("Swiggy", "Zomato", "Blinkit", "Zepto", "Instamart")
    private val promo = Regex("\\b(% ?off|sale|deals?|offers?|coupon|cashback|wishlist|cart|back in stock|price drop|recommended|rate (your|the)|review your|how was|feedback)\\b", RegexOption.IGNORE_CASE)
    private val secret = Regex("\\b(otp|one[- ]time|verification code)\\b", RegexOption.IGNORE_CASE)
    private val senderId = Regex("^[A-Z]{2}-[A-Z0-9]{3,9}(-[A-Z])?$")

    private val statuses = listOf(
        "cancelled" to Regex("\\b(has been |was |is )?cancell?ed\\b", RegexOption.IGNORE_CASE),
        "returned" to Regex("\\b(return (pick ?up|picked|request|initiated|scheduled|approved)|refund (initiated|processed|of|has been)|picked up for return|replacement (created|initiated))\\b", RegexOption.IGNORE_CASE),
        "delivered" to Regex("\\b(has been delivered|was delivered|is delivered|successfully delivered|delivered (to|on|at|today|:)|^delivered\\b|order delivered|package delivered|handed (over )?to)", RegexOption.IGNORE_CASE),
        "out" to Regex("\\b(out for delivery|arriving today|will be delivered today|delivery today|reaching you today|will reach you today|is (nearby|arriving)|on the way to you|on (its|the) way to (your|you)|delivery (partner|executive|agent) (is|has)|picked up your order)\\b", RegexOption.IGNORE_CASE),
        "shipped" to Regex("\\b(has been shipped|was shipped|is shipped|^shipped\\b|shipped[: ]|dispatched|in transit|on its way|on the way|picked up by|handed to (the )?courier|left (the )?(seller|facility|warehouse)|arriving (tomorrow|on|by|mon|tue|wed|thu|fri|sat|sun))", RegexOption.IGNORE_CASE),
        "ordered" to Regex("\\b(order (is |has been |was )?(placed|confirmed|received|successful)|thank(s| you) for (your )?(order|shopping|purchase)|we(?:'ve| have) received your order|preparing your order|is being (packed|prepared))\\b", RegexOption.IGNORE_CASE),
    )
    private val itemPatterns = listOf(
        Regex("\\b(?:package|order|shipment|parcel) (?:with|for|of|containing) (.{3,70}?)(?: (?:has|is|was|will|from|and \\d+ more)\\b|[.,;!]|$)", RegexOption.IGNORE_CASE),
        Regex("^(?:arriving \\w+|out for delivery|delivered|shipped|dispatched|order placed|order confirmed)\\s*[:\\-–]\\s*(?:your (?:package|order) (?:with |for |of )?)?(.{3,70}?)(?: (?:has|is|was|will)\\b|[.,;!]|$)", RegexOption.IGNORE_CASE),
        Regex("\\byour (.{3,60}?) (?:has been|is|was|will be) (?:shipped|delivered|dispatched|out for delivery|arriving|on its way|cancelled)", RegexOption.IGNORE_CASE),
        Regex("\\border from (.{3,40}?)(?: (?:has|is|was|will)\\b|[.,;!]|$)", RegexOption.IGNORE_CASE),
    )
    private val orderId = Regex("\\b(?:order|awb|tracking|shipment|consignment)(?: ?(?:id|no|number|#))?\\.?[:#\\s-]*([A-Z0-9][A-Z0-9-]{7,24})\\b", RegexOption.IGNORE_CASE)
    private val etaRx = Regex("\\b(?:arriving|arrives|delivery|delivered|reach(?:es|ing)? you|expected)(?: (?:on|by|is))? (today|tomorrow|tonight|(?:mon|tue|wed|thu|fri|sat|sun)\\w*(?:,? \\d{1,2} \\w{3,9})?|\\d{1,2}(?:st|nd|rd|th)? \\w{3,9}|\\w{3,9} \\d{1,2})\\b|\\bby (today|tomorrow|tonight|\\d{1,2}(?::\\d{2})? ?[ap]m|(?:mon|tue|wed|thu|fri|sat|sun)\\w*(?:,? \\d{1,2} \\w{3,9})?|\\d{1,2}(?:st|nd|rd|th)? \\w{3,9})\\b|\\bin (\\d{1,3} (?:mins?|minutes|hours?|days?))\\b", RegexOption.IGNORE_CASE)

    private fun tidy(item: String): String = item.trim().trim('"', '\'', '.', ' ').replace(Regex("^(your|the|a|an) ", RegexOption.IGNORE_CASE), "")
        .takeIf { it.length >= 3 && !Regex("^(order|package|item|items|parcel|shipment|delivery|it|order id|\\d+ items?)$", RegexOption.IGNORE_CASE).matches(it) }.orEmpty().take(60)

    /** The order update in this notification, or null when it is not one. */
    fun parse(app: String, title: String, text: String, now: Long): Parcel? {
        val t = "$title. $text".replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (text.length < 10 || secret.containsMatchIn(t)) return null
        val fromShop = shops.firstOrNull { it.second.containsMatchIn(app) }?.first
        // In a chat or SMS app only the shop or courier itself counts: a friend writing "my Amazon order was delivered" is not the user's order.
        val who = title.trim()
        val official = senderId.matches(who.uppercase()) || shops.any { it.second.containsMatchIn(who) } || who.all { it.isDigit() || it == '+' || it == ' ' || it == '-' } && who.count { it.isDigit() } in 3..8
        if (fromShop == null && !official) return null
        val shop = fromShop ?: shops.firstOrNull { it.second.containsMatchIn(t) }?.first ?: return null
        val status = statuses.firstOrNull { it.second.containsMatchIn(text) || it.second.containsMatchIn(title) }?.first ?: return null
        if (promo.containsMatchIn(t) && status == "ordered") return null
        var item = ""
        for (p in itemPatterns) { item = (p.find(text) ?: p.find(title) ?: p.find(t))?.groupValues?.get(1)?.let { tidy(it) }.orEmpty(); if (item.isNotBlank()) break }
        val id = orderId.find(t)?.groupValues?.get(1)?.takeIf { s -> s.any { it.isDigit() } }.orEmpty()
        val eta = etaRx.find(t)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.lowercase().orEmpty()
        val key = shop.lowercase() + "|" + (id.ifBlank { item.lowercase().split(' ').take(3).joinToString(" ").ifBlank { if (shop in food) "food" else "latest" } })
        return Parcel(key, shop, item, status, if (status == "out" && eta.isBlank()) "today" else eta, now)
    }

    fun isFood(shop: String) = shop in food
}

/** The orders being tracked, kept on the phone. */
object Parcels {
    private var file: File? = null
    private val map = LinkedHashMap<String, Parcel>()
    @Volatile var onChange: () -> Unit = {}
    private val rank = mapOf("ordered" to 1, "shipped" to 2, "out" to 3, "delivered" to 4, "cancelled" to 5, "returned" to 5)

    @Synchronized fun init(dir: File) {
        file = File(dir, "seyal/parcels.json"); map.clear()
        runCatching { val a = JSONArray(file!!.readText()); for (i in 0 until a.length()) Parcel.fromJson(a.getJSONObject(i)).let { map[it.key] = it } }
    }
    private fun save() { val f = file ?: return; runCatching { f.parentFile?.mkdirs(); f.writeText(JSONArray(map.values.map { it.toJson() }).toString()) } }
    @Synchronized fun clear() { map.clear(); save(); onChange() }

    /**
     * Takes in an update. A later status replaces an earlier one; an older message arriving late does not
     * move an order backwards. The item's name is kept once known. Returns true when something changed.
     */
    @Synchronized fun upsert(p: Parcel): Boolean {
        // A courier's message without the item belongs to the one open order from that shop, when there is exactly one.
        val existing = map[p.key] ?: map.values.filter { it.shop == p.shop && it.status !in setOf("delivered", "cancelled", "returned") }.takeIf { p.key.endsWith("|latest") && it.size == 1 }?.first()
        val fresh = existing == null || (existing.status in setOf("delivered", "cancelled") && p.status in setOf("ordered", "shipped") && p.updated - existing.updated > 6 * 3600_000L)
        if (!fresh && existing != null) {
            if ((rank[p.status] ?: 0) < (rank[existing.status] ?: 0)) return false
            if (p.status == existing.status && p.eta == existing.eta && (p.item.isBlank() || p.item == existing.item)) return false
            map[existing.key] = Parcel(existing.key, existing.shop, existing.item.ifBlank { p.item }, p.status, p.eta.ifBlank { existing.eta }, p.updated)
        } else map[p.key] = p
        val cutoff = p.updated - 45L * 24 * 3600_000L
        map.values.removeAll { it.updated < cutoff }
        save(); onChange()
        return true
    }

    /** Orders still worth showing: on their way (food for 3 hours, parcels for 3 weeks), and delivered ones for two days. */
    @Synchronized fun open(now: Long): List<Parcel> = map.values.filter { p ->
        val age = now - p.updated
        when {
            p.status == "delivered" -> age < 2L * 24 * 3600_000L && !ParcelParser.isFood(p.shop)
            p.status in setOf("cancelled", "returned") -> age < 5L * 24 * 3600_000L
            ParcelParser.isFood(p.shop) -> age < 3 * 3600_000L
            else -> age < 21L * 24 * 3600_000L
        }
    }.sortedBy { -(rank[it.status]?.takeIf { r -> r <= 3 } ?: 0) }

    /** The answer to "where are my orders?". */
    fun summary(now: Long): String {
        val list = open(now)
        if (list.isEmpty()) return "No orders on the way that I know of. I pick them up from the shop's notifications, so orders placed before I was installed won't show."
        return "Your orders:\n" + list.joinToString("\n") { "• " + it.line() }
    }
}
