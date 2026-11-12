package com.seyal.app.tools

import com.seyal.app.agent.Tool
import com.seyal.app.agent.ToolResult
import com.seyal.app.agent.schema
import com.seyal.app.data.Level
import com.seyal.app.device.SeyalNotificationListener
import org.json.JSONObject

/** The notification shade (Full edition with Notification access on): read, reply in place, open, clear. */
object NotificationTools {
    private val on = { SeyalNotificationListener.connected }

    val all: List<Tool> by lazy {
        listOf(
            Tool(
                "notifications_read", "notifications",
                "The notifications on the phone right now, newest first: app, title (usually the sender), text, minutes_ago, can_reply. Use for 'what did I miss', 'any messages from X', 'read my WhatsApp messages'.",
                schema { str("app", "Only this app, e.g. 'WhatsApp'. Empty = all."); int("max", "Most to return (default 25)") },
                available = on,
            ) { a, _ ->
                val items = try { SeyalNotificationListener.list(a.optString("app"), a.optInt("max", 25).coerceIn(1, 60)) } catch (e: Exception) { return@Tool ToolResult.Failed(e.message ?: "Could not read notifications") }
                ToolResult.Done(JSONObject().put("count", items.length()).put("items", items), "${items.length()} notifications")
            },

            Tool(
                "notification_reply", "comms",
                "Reply to a message straight from its notification, without opening the app (works for WhatsApp, Messages, Telegram, Instagram…). Only works while that notification is still there (can_reply true). match = the sender's name or words from the message.",
                schema { str("match", "Sender name or words in the notification", required = true); str("text", "The reply", required = true); str("app", "App name, e.g. 'WhatsApp'") },
                defaultLevel = Level.ASK, sideEffect = true, available = on,
                target = { SeyalNotificationListener.resolve(it.optString("match"), it.optString("app"))?.lowercase() },
                preview = { "Reply to ${SeyalNotificationListener.resolve(it.optString("match"), it.optString("app")) ?: it.optString("match")}: \"${it.optString("text").take(200)}\"" },
            ) { a, ctx ->
                if (a.optString("text").isBlank()) return@Tool ToolResult.Failed("Empty reply")
                // A run that a message started answers that very message (by Android's id for it), and never a group.
                val s = com.seyal.app.agent.Agent.session(ctx.sessionId)
                val auto = s?.unattended == true
                val key = s?.replyKey?.takeIf { auto && com.seyal.app.agent.Agent.samePerson(a.optString("match"), s.trustedReplyTo) }
                if (auto && key == null) return@Tool ToolResult.Failed("An automatic run may only answer the person whose message started it (match = that sender), and never a group. Use notify to tell the user instead.")
                try { ToolResult.ok("Replied to ${SeyalNotificationListener.reply(a.optString("match"), a.optString("app"), a.optString("text"), key, noGroups = auto)}: \"${a.optString("text").take(60)}\"", "sent" to true) }
                catch (e: Exception) { ToolResult.Failed(e.message ?: "Could not reply") }
            },

            Tool(
                "notification_open", "notifications", "Open what a notification points to (the chat, the mail, the order).",
                schema { str("match", "Sender name or words in the notification", required = true); str("app", "App name") },
                available = on,
            ) { a, _ ->
                try { ToolResult.ok("Opened \"${SeyalNotificationListener.open(a.optString("match"), a.optString("app")).take(40)}\"") } catch (e: Exception) { ToolResult.Failed(e.message ?: "Could not open") }
            },

            Tool(
                "notifications_clear", "notifications", "Dismiss notifications that match (by words or app). To clear every notification, set all=true; only when the user asked for that.",
                schema { str("match", "Words in the notification"); str("app", "App name"); bool("all", "Clear everything") },
                sideEffect = true, available = on,
            ) { a, _ ->
                if (a.optString("match").isBlank() && a.optString("app").isBlank() && !a.optBoolean("all")) return@Tool ToolResult.Failed("Say which notifications to clear (match or app), or all=true if the user asked to clear everything.")
                try { val n = SeyalNotificationListener.dismiss(a.optString("match"), a.optString("app")); ToolResult.ok("Cleared $n notifications", "cleared" to n) } catch (e: Exception) { ToolResult.Failed(e.message ?: "Could not clear") }
            },
        )
    }
}
