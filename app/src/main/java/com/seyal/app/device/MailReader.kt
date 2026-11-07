package com.seyal.app.device

import com.seyal.app.data.Settings
import com.seyal.app.engine.Parsers
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm

/**
 * Reads the Gmail inbox over IMAP with a Gmail *app password* (Google Account → Security →
 * App passwords). Read-only: it never sends, moves or deletes mail. Nothing is stored.
 */
object MailReader {

    class Result(val items: JSONArray, val promoSkipped: Int, val total: Int)

    private val important = listOf("exam", "fee", "deadline", "placement", "internship", "interview", "offer", "result", "hall ticket", "assignment", "submission", "timetable", "urgent", "last date", "registration")

    fun read(hours: Int, max: Int, includePromotions: Boolean): Result {
        val email = Settings.gmailAddress
        val pass = Settings.gmailAppPassword.replace(" ", "")
        if (email.isBlank() || pass.isBlank()) throw IllegalStateException("Gmail isn't connected yet. Control → Gmail: add your address and an app password.")

        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", "imap.gmail.com")
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "25000")
        }
        val store = Session.getInstance(props).getStore("imaps")
        try {
            store.connect("imap.gmail.com", email, pass)
        } catch (e: Exception) {
            throw IllegalStateException("Gmail login failed. Check the address and the 16-letter app password in Control. (${e.message?.take(80)})")
        }
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            val since = Date(System.currentTimeMillis() - hours.coerceIn(1, 24 * 14) * 3_600_000L)
            val hits = inbox.search(ReceivedDateTerm(ComparisonTerm.GE, since)).takeLast(80).toTypedArray()
            // One round trip for all headers instead of one per mail.
            inbox.fetch(hits, FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add("List-Unsubscribe")
                add("Precedence")
            })
            val found = hits
                .filter { (it.receivedDate ?: it.sentDate ?: Date(0)).after(since) }
                .sortedByDescending { it.receivedDate ?: it.sentDate }
            val domain = Settings.collegeDomain
            val items = JSONArray()
            var promo = 0
            for (m in found) {
                if (items.length() >= max.coerceIn(1, 40)) break
                val isPromo = m.getHeader("List-Unsubscribe") != null || m.getHeader("Precedence")?.any { it.equals("bulk", true) } == true
                val from = (m.from?.firstOrNull() as? InternetAddress)
                val fromAddr = from?.address ?: ""
                val isCollege = domain.isNotBlank() && fromAddr.lowercase().endsWith(domain)
                if (isPromo && !isCollege && !includePromotions) { promo++; continue }
                val subject = m.subject ?: "(no subject)"
                val snippet = runCatching { textOf(m) }.getOrDefault("").replace(Regex("\\s+"), " ").trim().take(400)
                val text = "$subject $snippet".lowercase()
                items.put(
                    JSONObject()
                        .put("from", from?.personal ?: fromAddr)
                        .put("email", fromAddr)
                        .put("subject", subject)
                        .put("date", (m.receivedDate ?: m.sentDate)?.toString() ?: "")
                        .put("snippet", snippet)
                        .put("college", isCollege)
                        .put("looks_important", isCollege || important.any { text.contains(it) }),
                )
            }
            inbox.close(false)
            return Result(items, promo, found.size)
        } finally {
            runCatching { store.close() }
        }
    }

    private fun textOf(p: Part, depth: Int = 0): String {
        if (depth > 5) return ""
        return when {
            p.isMimeType("text/plain") -> p.content as? String ?: ""
            p.isMimeType("text/html") -> Parsers.strip(p.content as? String ?: "")
            p.isMimeType("multipart/*") -> {
                val mp = p.content as? Multipart ?: return ""
                var html = ""
                for (i in 0 until mp.count) {
                    val part = mp.getBodyPart(i)
                    if (part.isMimeType("text/plain")) return part.content as? String ?: ""
                    if (html.isBlank()) html = textOf(part, depth + 1)
                }
                html
            }
            p.isMimeType("message/rfc822") -> (p.content as? Part)?.let { textOf(it, depth + 1) } ?: ""
            else -> ""
        }
    }
}
