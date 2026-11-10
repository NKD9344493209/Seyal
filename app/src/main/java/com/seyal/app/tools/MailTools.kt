package com.seyal.app.tools

import android.content.Intent
import android.net.Uri
import com.seyal.app.agent.Tool
import com.seyal.app.agent.ToolResult
import com.seyal.app.agent.schema
import com.seyal.app.data.Level
import com.seyal.app.data.Settings
import com.seyal.app.device.MailReader
import com.seyal.app.device.MailSender
import com.seyal.app.tools.Common.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Gmail over IMAP/SMTP with an app password: read, search, send. */
object MailTools {

    val all: List<Tool> by lazy {
        listOf(
            Tool(
                "read_mail", "mail",
                "Recent emails from the user's Gmail inbox (promotions skipped unless asked). Output: items[from, email, subject, date, snippet, college, looks_important]. Summarise; never open the Gmail app for this.",
                schema { int("hours", "How far back (default 24)"); int("max", "Most mails to return (default 20)"); bool("include_promotions", "Include newsletters and promotions") },
            ) { a, _ -> read(a.optInt("hours", 24), a.optInt("max", 20), a.optBoolean("include_promotions", false), "") },

            Tool(
                "search_mail", "mail", "Search the last 60 days of Gmail by sender, subject or words in the body.",
                schema { str("query", "Words or a sender", required = true); int("max", "Most mails to return (default 10)") },
            ) { a, _ -> read(24, a.optInt("max", 10), true, a.optString("query")) },

            Tool(
                "send_email", "comms",
                "Send an email. With Gmail connected it is sent directly; otherwise the mail app opens with everything filled in.",
                schema { str("to", "Recipient address(es), comma separated", required = true); str("subject", "Subject", required = true); str("body", "The email text", required = true); str("cc", "CC addresses, comma separated") },
                defaultLevel = Level.ASK, sideEffect = true,
                target = { it.optString("to").lowercase().ifBlank { null } },
                preview = { "Email to ${it.optString("to")} · ${it.optString("subject")}\n${it.optString("body").take(300)}" },
            ) { a, _ -> send(a.optString("to"), a.optString("subject"), a.optString("body"), a.optString("cc")) },
        )
    }

    private suspend fun read(hours: Int, max: Int, promos: Boolean, query: String): ToolResult = withContext(Dispatchers.IO) {
        val r = runCatching { MailReader.read(hours, max, promos, query) }
            .getOrElse { return@withContext ToolResult.Failed(it.message ?: "Could not read mail", retryable = it !is IllegalStateException) }
        val n = r.items.length()
        val line = when {
            query.isNotBlank() -> "$n mails match \"$query\""
            n == 0 -> "No new mail in the last $hours h" + (if (r.promoSkipped > 0) " (${r.promoSkipped} promotions skipped)" else "")
            else -> "Read $n mails" + (if (r.promoSkipped > 0) " · ${r.promoSkipped} promotions skipped" else "")
        }
        ToolResult.Done(JSONObject().put("count", n).put("promotions_skipped", r.promoSkipped).put("items", r.items), line)
    }

    private suspend fun send(to: String, subject: String, body: String, cc: String): ToolResult = withContext(Dispatchers.IO) {
        val tos = to.split(',', ';').map { it.trim() }.filter { it.contains("@") }
        if (tos.isEmpty()) return@withContext ToolResult.Failed("No valid email address in \"$to\"")
        val sig = Settings.signature
        val full = if (sig.isNotBlank() && !body.contains(sig)) "$body\n\n$sig" else body
        if (Settings.hasGmail) {
            return@withContext runCatching {
                MailSender.send(tos, subject, full, cc.split(',', ';').map { it.trim() }.filter { it.contains("@") })
                ToolResult.ok("Email sent to ${tos.joinToString()}", "sent" to true, "to" to tos.joinToString())
            }.getOrElse { ToolResult.Failed(it.message ?: "Could not send") }
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, tos.toTypedArray()); putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, full)
        }
        if (launch(intent)) ToolResult.ok("Email to ${tos.joinToString()} opened · tap Send", "sent" to false, "note" to "Gmail isn't connected in Control, so the mail app opened for the user to send.")
        else ToolResult.Failed("No email app found and Gmail isn't connected (Control → Gmail)")
    }
}
