package com.seyal.app.tools

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.seyal.app.agent.Scheduler
import com.seyal.app.agent.Tool
import com.seyal.app.agent.ToolResult
import com.seyal.app.agent.noArgs
import com.seyal.app.agent.schema
import com.seyal.app.data.Level
import com.seyal.app.data.Settings
import com.seyal.app.device.Notify
import com.seyal.app.device.ScreenDriver
import com.seyal.app.device.Speech
import com.seyal.app.tools.Common.ctx
import com.seyal.app.tools.Common.has
import com.seyal.app.tools.Common.launch
import com.seyal.app.util.Times
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone

/** Direct phone actions: contacts, calls, messages, calendar, alarms, apps, settings. */
object PhoneTools {

    val all: List<Tool> by lazy {
        listOf(
            Tool(
                "contacts_search", "phone",
                "Find people in the phone's contacts by name (family words like dad/amma/anna work). Output: matches[name, phone, email, starred]. If several match, pick the obvious one or ask_user.",
                schema { str("name", "Name or family word, e.g. 'Ram', 'dad', 'Priya S'", required = true) },
            ) { a, _ -> findContacts(a.optString("name")) },

            Tool(
                "call", "comms", "Place a phone call now.",
                schema { str("phone", "Phone number", required = true); str("name", "Person's name, for the user") },
                defaultLevel = Level.ASK, sideEffect = true,
                target = { it.optString("phone").filter { c -> c.isDigit() }.takeLast(10).ifBlank { null } },
                preview = { "Call ${it.optString("name").ifBlank { it.optString("phone") }}" },
            ) { a, _ -> call(a.optString("phone"), a.optString("name")) },

            Tool(
                "send_sms", "comms", "Send an SMS text message directly (no app opens).",
                schema { str("phone", "Phone number", required = true); str("text", "The message", required = true); str("name", "Person's name") },
                defaultLevel = Level.ASK, sideEffect = true,
                target = { it.optString("phone").filter { c -> c.isDigit() }.takeLast(10).ifBlank { null } },
                preview = { "SMS to ${it.optString("name").ifBlank { it.optString("phone") }}: \"${it.optString("text").take(160)}\"" },
            ) { a, _ -> sendSms(a.optString("phone"), a.optString("text"), a.optString("name")) },

            Tool(
                "send_whatsapp", "comms",
                "Send a WhatsApp message. With screen control on it is sent by itself; otherwise the chat opens with the text typed and the user taps Send.",
                schema { str("phone", "Phone number", required = true); str("text", "The message", required = true); str("name", "Person's name") },
                defaultLevel = Level.ASK, sideEffect = true,
                target = { Common.normalisePhone(it.optString("phone")).ifBlank { null } },
                preview = { "WhatsApp to ${it.optString("name").ifBlank { it.optString("phone") }}: \"${it.optString("text").take(200)}\"" },
            ) { a, ctx -> sendWhatsApp(a.optString("phone"), a.optString("text"), a.optString("name"), ctx.sessionId) },

            Tool(
                "calendar_add", "phone", "Add a calendar event.",
                schema {
                    str("title", "Event title", required = true); str("start", "Local ISO start, e.g. 2026-11-08T16:00", required = true)
                    int("duration_min", "Length in minutes (default 30)"); str("location", "Place"); str("description", "Notes or a meeting link")
                },
                sideEffect = true,
                preview = { "Add \"${it.optString("title")}\" at ${Times.parse(it.optString("start"))?.let { d -> Times.format(Times.toEpoch(d)) } ?: it.optString("start")}" },
            ) { a, _ -> createEvent(a) },

            Tool(
                "calendar_list", "phone", "Events in the calendar between two times. Output: events[title, start, end, location].",
                schema { str("from", "Local ISO start (default now)"); str("to", "Local ISO end (default +7 days)") },
            ) { a, _ -> listEvents(a.optString("from"), a.optString("to")) },

            Tool(
                "alarm_set", "phone", "Set an alarm that rings at a time (Seyal's own alarm screen).",
                schema { str("at", "Local ISO date-time, e.g. 2026-11-08T06:30", required = true); str("label", "Short label") },
                sideEffect = true,
            ) { a, _ -> timer("alarm", a.optString("at"), a.optString("label").ifBlank { "Alarm" }) },

            Tool(
                "reminder_set", "phone", "Show a reminder notification at a time.",
                schema { str("at", "Local ISO date-time", required = true); str("text", "What to remind", required = true) },
                sideEffect = true,
            ) { a, _ -> timer("remind", a.optString("at"), a.optString("text")) },

            Tool(
                "timer_set", "phone", "A countdown: reminds after N minutes.",
                schema { int("minutes", "Minutes from now", required = true); str("label", "What it's for") },
                sideEffect = true,
            ) { a, _ ->
                val ms = System.currentTimeMillis() + a.optInt("minutes", 1).coerceIn(1, 24 * 60) * 60_000L
                Scheduler.setReminder(ctx, ms, a.optString("label").ifBlank { "Timer done" })
                ToolResult.ok("Timer set for ${Times.format(ms)}", "at_text" to Times.format(ms))
            },

            Tool(
                "meeting_link", "phone", "Create a video meeting link (Jitsi, no account needed). Output: link.",
                schema { str("title", "Meeting title") },
            ) { a, _ ->
                val slug = a.optString("title").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifBlank { "meet" }
                val code = (1..4).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
                ToolResult.ok("Meeting link ready", "link" to "https://meet.jit.si/Seyal-$slug-$code")
            },

            Tool("notify", "phone", "Show a notification with a short text now.", schema { str("text", "The text", required = true) }) { a, _ ->
                Notify.line(ctx, a.optString("text").ifBlank { "Done" }); ToolResult.ok("Notified")
            },

            Tool("say", "phone", "Speak a short text out loud.", schema { str("text", "What to say", required = true) }) { a, _ ->
                Speech.say(ctx, a.optString("text")); ToolResult.ok("Spoke")
            },

            Tool("wait", "phone", "Pause for a few seconds (e.g. for a page or app to load).", schema { int("seconds", "1-60", required = true) }) { a, _ ->
                delay(a.optInt("seconds", 2).coerceIn(1, 60) * 1000L); ToolResult.ok("Waited")
            },

            Tool("open_app", "phone", "Open an installed app by name.", schema { str("name", "App name as shown on the phone, e.g. 'YouTube', 'BookMyShow'", required = true) },
                target = { it.optString("name").lowercase().ifBlank { null } },
            ) { a, _ -> openApp(a.optString("name")) },

            Tool(
                "open_url", "phone",
                "Open a link in the browser or the app that handles it. Also app deep links: geo:0,0?q=<place> (map), google.navigation:q=<place> (start navigation), https://www.youtube.com/results?search_query=<words>, market://details?id=<package>, https://www.google.com/search?q=<words>. With screen control on, the result includes the new screen.",
                schema { str("url", "The link", required = true) },
            ) { a, _ ->
                val raw = a.optString("url").trim()
                val u = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:(?!\\d)").containsMatchIn(raw)) raw else "https://$raw"
                val scheme = u.substringBefore(':').lowercase()
                // Only kinds of links that can't move money or reach private files.
                val allowed = setOf("http", "https", "geo", "google.navigation", "market", "tel", "mailto", "sms", "smsto", "vnd.youtube", "spotify", "whatsapp")
                if (scheme !in allowed) return@Tool ToolResult.Failed("Seyal doesn't open \"$scheme:\" links (payment apps and local files are the user's to open).")
                if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))) return@Tool ToolResult.Failed("Nothing on this phone can open that link")
                val out = JSONObject().put("ok", true).put("url", u)
                ScreenDriver.snapshotAfter(1800)?.let { out.put("screen", it) }
                ToolResult.Done(out, "Opened ${u.take(50)}")
            },

            Tool("apps_list", "phone", "Names of the apps installed on this phone.", noArgs) { _, _ ->
                val pm = ctx.packageManager
                val names = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.loadLabel(pm).toString() }.distinct().sorted()
                ToolResult.Done(JSONObject().put("apps", JSONArray(names)), "${names.size} apps")
            },

            Tool("phone_state", "phone", "Battery, network, volume, ringer, do-not-disturb, storage, time.", noArgs) { _, _ -> phoneState() },

            Tool(
                "set_setting", "phone",
                "Change a phone setting. name: brightness (0-100) | volume_media | volume_ring | volume_alarm (0-100) | ringer (normal|vibrate|silent) | dnd (on|off) | flashlight (on|off) | wifi (on|off: opens the quick panel) | bluetooth (on|off) | auto_rotate (on|off)",
                schema { str("name", "Which setting", required = true); str("value", "New value", required = true) },
                sideEffect = true,
                preview = { "Set ${it.optString("name")} to ${it.optString("value")}" },
            ) { a, _ -> setSetting(a.optString("name").lowercase(), a.optString("value").lowercase()) },

            Tool(
                "app_usage", "phone", "Screen time per app for today or the last 7 days. Output: apps[name, minutes], total_minutes.",
                schema { str("range", "today | week", enum = listOf("today", "week")) },
            ) { a, _ -> appUsage(a.optString("range", "today")) },

            Tool("location", "phone", "Where the phone is now: place name, lat, lon and map_link (a Google Maps link: send it when someone asks where the user is).", noArgs) { _, _ -> location() },

            Tool(
                "clipboard", "phone", "Read the clipboard, or copy text to it.",
                schema { str("set", "Text to copy. Leave empty to read.") },
            ) { a, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val set = a.optString("set")
                if (set.isNotBlank()) { cm.setPrimaryClip(ClipData.newPlainText("Seyal", set)); ToolResult.ok("Copied") }
                else ToolResult.ok("Read clipboard", "text" to (cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""))
            },
        )
    }

    // ---------------- contacts ----------------
    /** Family words → the names people actually save in their phones (Tamil, Hindi, Telugu, Malayalam, English). */
    private val family = mapOf(
        "dad" to listOf("dad", "appa", "father", "pappa", "papa", "daddy", "nanna", "achan", "abba", "pitaji", "baba"),
        "mom" to listOf("mom", "amma", "mother", "mummy", "mum", "maa", "ammi", "mommy"),
        "brother" to listOf("brother", "anna", "thambi", "bro", "bhai", "bhaiya", "annan"),
        "sister" to listOf("sister", "akka", "thangachi", "thangai", "sis", "didi", "chechi"),
        "grandma" to listOf("grandma", "paati", "ammachi", "nani", "dadi", "aaya", "ammamma"),
        "grandpa" to listOf("grandpa", "thatha", "nana", "dada", "appachan", "thaatha"),
        "wife" to listOf("wife", "wifey", "pondatti"),
        "husband" to listOf("husband", "hubby"),
        "uncle" to listOf("uncle", "mama", "chithappa", "periyappa", "chacha", "mamu"),
        "aunt" to listOf("aunt", "aunty", "athai", "chithi", "periyamma", "mami", "chachi", "bua"),
    )

    private fun searchNames(name: String): List<String> {
        val n = name.trim().lowercase().removePrefix("my ").trim()
        val group = family.entries.firstOrNull { (k, v) -> n == k || n in v || n == k + "dy" }?.value
        return (listOf(n) + (group ?: emptyList())).distinct()
    }

    private suspend fun findContacts(name: String): ToolResult = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext ToolResult.Failed("No name given")
        if (!has(Manifest.permission.READ_CONTACTS)) return@withContext ToolResult.Failed("Contacts permission not granted. The user can allow it in Control → Phone permissions.")
        val cr = ctx.contentResolver
        val found = linkedMapOf<String, JSONObject>()
        val names = searchNames(name)
        for (term in names) {
            cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.STARRED),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("$term%", "% $term%"), null,
            )?.use { c ->
                while (c.moveToNext() && found.size < 12) {
                    val id = c.getString(0)
                    if (found.containsKey(id)) continue
                    found[id] = JSONObject().put("name", c.getString(1)).put("phone", c.getString(2) ?: "").put("email", "").put("starred", c.getInt(3) == 1)
                }
            }
            if (found.isNotEmpty() && term == names.first()) break
        }
        for ((id, o) in found) {
            cr.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?", arrayOf(id), null,
            )?.use { if (it.moveToFirst()) o.put("email", it.getString(0) ?: "") }
        }
        if (found.isEmpty()) {
            for (term in names) {
                cr.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID, ContactsContract.CommonDataKinds.Email.DISPLAY_NAME, ContactsContract.CommonDataKinds.Email.ADDRESS),
                    "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?", arrayOf("$term%", "% $term%"), null,
                )?.use { c ->
                    while (c.moveToNext() && found.size < 12) {
                        val id = c.getString(0)
                        if (found.containsKey(id)) continue
                        found[id] = JSONObject().put("name", c.getString(1)).put("phone", "").put("email", c.getString(2) ?: "").put("starred", false)
                    }
                }
            }
        }
        val list = found.values.sortedByDescending { it.optBoolean("starred") }
        if (list.isEmpty()) return@withContext ToolResult.Failed("No contact found for \"$name\" (also tried: ${names.drop(1).take(5).joinToString()}). Ask the user who that is, then memory_save it.")
        val exact = list.filter { it.optString("name").equals(name.trim(), true) }
        val best = if (exact.size == 1) exact else list
        ToolResult.Done(JSONObject().put("matches", JSONArray(best)).put("count", best.size), if (best.size == 1) "Found ${best[0].optString("name")}" else "${best.size} matches for $name")
    }

    // ---------------- calls and messages ----------------
    private fun call(phoneRaw: String, name: String): ToolResult {
        val phone = phoneRaw.trim()
        if (phone.isBlank()) return ToolResult.Failed("No phone number for ${name.ifBlank { "this contact" }}")
        val who = name.ifBlank { phone }
        return if (has(Manifest.permission.CALL_PHONE) && launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")))) ToolResult.ok("Calling $who", "called" to who)
        else if (launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))) ToolResult.ok("Dialer opened for $who · tap Call", "opened_dialer" to who)
        else ToolResult.Failed("Could not open the dialer")
    }

    private fun sendSms(phoneRaw: String, text: String, name: String): ToolResult {
        val phone = phoneRaw.trim()
        if (phone.isBlank() || text.isBlank()) return ToolResult.Failed("Need a number and a message")
        if (!has(Manifest.permission.SEND_SMS)) {
            // Lite edition, or permission not given: open the Messages app with everything filled in.
            val ok = launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).putExtra("sms_body", text))
            return if (ok) ToolResult.ok("Messages opened for ${name.ifBlank { phone }} · tap Send", "sent" to false, "note" to "Seyal can't send SMS by itself here (Full edition + SMS permission needed), so the Messages app is open with the text typed.")
            else ToolResult.Failed("No SMS app found")
        }
        return runCatching {
            val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sm.divideMessage(text)
            sm.sendMultipartTextMessage(phone, null, parts, null, null)
            ToolResult.ok("SMS sent to ${name.ifBlank { phone }}", "sent" to true)
        }.getOrElse { ToolResult.Failed("SMS failed: ${it.message}") }
    }

    private suspend fun sendWhatsApp(phoneRaw: String, text: String, name: String, sessionId: String? = null): ToolResult {
        val phone = Common.normalisePhone(phoneRaw)
        if (phone.isBlank()) return ToolResult.Failed("No phone number for ${name.ifBlank { "this contact" }}")
        if (text.isBlank()) return ToolResult.Failed("Empty message")
        val pm = ctx.packageManager
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }
            ?: return ToolResult.Failed("WhatsApp is not installed")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone?text=${URLEncoder.encode(text, "UTF-8")}")).setPackage(pkg)
        val who = name.ifBlank { "+$phone" }
        // One WhatsApp send at a time: the screen is shared.
        return whatsAppLock.withLock {
            if (!launch(intent)) return@withLock ToolResult.Failed("Could not open WhatsApp")
            if (!ScreenDriver.available) return@withLock ToolResult.ok("WhatsApp opened for $who · tap Send", "sent" to false, "to" to who, "note" to "Screen control is off, so the user must tap Send.")
            if (sessionId != null && !ScreenDriver.claim(sessionId)) return@withLock ToolResult.ok("WhatsApp opened for $who · tap Send", "sent" to false, "to" to who, "note" to "Another task is using the screen, so the user must tap Send.")
            // Wait until WhatsApp itself is in front with its Send button (never Seyal's own screen).
            val start = System.currentTimeMillis()
            var ready = false
            while (System.currentTimeMillis() - start < 15_000) {
                if (ScreenDriver.frontPackage() == pkg && (ScreenDriver.fieldText(pkg, "send") != null || ScreenDriver.isVisible("Send", exactOnly = true))) { ready = true; break }
                delay(400)
            }
            if (!ready) {
                val screen = ScreenDriver.snapshotAfter(0)
                return@withLock ToolResult.Done(JSONObject().put("ok", true).put("sent", false).put("to", who).put("note", "WhatsApp opened but the chat with the Send button did not show. Look at the screen: the number may not be on WhatsApp, or a popup is in the way.").put("screen", screen), "WhatsApp opened for $who · not sent yet")
            }
            delay(600)
            val tapped = ScreenDriver.tapViewId(pkg, "send") || ScreenDriver.tap(null, "Send", exactOnly = true, returnScreen = false, pkg = pkg).optBoolean("ok")
            if (!tapped) return@withLock ToolResult.ok("WhatsApp opened for $who · tap Send", "sent" to false, "to" to who)
            // Sent = WhatsApp still in front and its message box empty again (checked for up to 3 s).
            var sent = false
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 3000) {
                delay(400)
                if (ScreenDriver.frontPackage() != pkg) break
                val box = ScreenDriver.fieldText(pkg, "entry")
                if (box == "" || (box == null && !ScreenDriver.isVisible("Send", exactOnly = true))) { sent = true; break }
            }
            if (!sent) return@withLock ToolResult.ok("WhatsApp opened for $who · check and tap Send", "sent" to false, "to" to who, "note" to "Tapped Send but could not confirm the message left. The chat is still open for the user to check.")
            ScreenDriver.global("back", returnScreen = false); ScreenDriver.global("back", returnScreen = false)
            ToolResult.ok("WhatsApp sent to $who", "sent" to true, "to" to who)
        }
    }

    private val whatsAppLock = kotlinx.coroutines.sync.Mutex()

    // ---------------- calendar ----------------
    private fun createEvent(a: JSONObject): ToolResult {
        val title = a.optString("title").ifBlank { "Event" }
        val start = Times.parse(a.optString("start")) ?: return ToolResult.Failed("Could not understand the start time \"${a.optString("start")}\"")
        val dur = a.optInt("duration_min", 30).coerceIn(5, 600)
        val startMs = Times.toEpoch(start)
        val endMs = startMs + dur * 60_000L
        val text = Times.format(startMs)
        if (has(Manifest.permission.WRITE_CALENDAR) && has(Manifest.permission.READ_CALENDAR)) {
            val calId = ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
                "${CalendarContract.Calendars.VISIBLE} = 1", null, null,
            )?.use { c ->
                var first: Long? = null
                while (c.moveToNext()) { val id = c.getLong(0); if (first == null) first = id; if (c.getInt(1) == 1) return@use id }
                first
            }
            if (calId != null) {
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calId); put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DTSTART, startMs); put(CalendarContract.Events.DTEND, endMs)
                    put(CalendarContract.Events.DESCRIPTION, a.optString("description")); put(CalendarContract.Events.EVENT_LOCATION, a.optString("location"))
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
                val uri = runCatching { ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull()
                if (uri != null) return ToolResult.ok("Event added: $title · $text", "event_id" to uri.lastPathSegment, "start_text" to text)
            }
        }
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            .putExtra(CalendarContract.Events.DESCRIPTION, a.optString("description")).putExtra(CalendarContract.Events.EVENT_LOCATION, a.optString("location"))
        return if (launch(intent)) ToolResult.ok("Calendar opened for $title · tap Save", "start_text" to text, "saved" to false) else ToolResult.Failed("No calendar app found")
    }

    private fun listEvents(fromRaw: String, toRaw: String): ToolResult {
        if (!has(Manifest.permission.READ_CALENDAR)) return ToolResult.Failed("Calendar permission not granted (Control → Phone permissions).")
        val from = Times.parse(fromRaw)?.let { Times.toEpoch(it) } ?: System.currentTimeMillis()
        val to = Times.parse(toRaw)?.let { Times.toEpoch(it) } ?: (from + 7 * 24 * 3600_000L)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(from.toString()).appendPath(to.toString()).build()
        val events = JSONArray()
        ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION), null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && events.length() < 40) {
                events.put(JSONObject().put("title", c.getString(0) ?: "").put("start", Times.format(c.getLong(1))).put("end", Times.format(c.getLong(2))).put("location", c.getString(3) ?: ""))
            }
        }
        return ToolResult.Done(JSONObject().put("events", events).put("count", events.length()), "${events.length()} events")
    }

    private fun timer(kind: String, at: String, text: String): ToolResult {
        val dt = Times.parse(at) ?: return ToolResult.Failed("Could not understand the time \"$at\"")
        val ms = Times.toEpoch(dt)
        if (kind == "alarm") Scheduler.setAlarm(ctx, ms, text) else Scheduler.setReminder(ctx, ms, text)
        val t = Times.format(ms)
        return ToolResult.ok(if (kind == "alarm") "Alarm set for $t" else "Reminder set for $t", "at_text" to t, "text" to text)
    }

    // ---------------- apps and settings ----------------
    private suspend fun openApp(name: String): ToolResult {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val n = name.trim()
        val target = apps.firstOrNull { it.loadLabel(pm).toString().equals(n, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().replace(" ", "").equals(n.replace(" ", ""), true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(n, true) }
            ?: apps.firstOrNull { it.activityInfo.packageName.contains(n.replace(" ", "").lowercase()) }
            ?: return ToolResult.Failed("No app called \"$name\" is installed. apps_list shows what is.")
        val label = target.loadLabel(pm).toString()
        val intent = pm.getLaunchIntentForPackage(target.activityInfo.packageName) ?: return ToolResult.Failed("Can't open $label")
        if (!launch(intent)) return ToolResult.Failed("Can't open $label")
        val pkg = target.activityInfo.packageName
        val out = JSONObject().put("ok", true).put("app", label).put("package", pkg)
        if (ScreenDriver.available) {
            if (!ScreenDriver.waitForPackage(pkg, 6000)) out.put("note", "$label did not come to the front yet (a popup, or the phone blocked it). Check the screen.")
            ScreenDriver.snapshotAfter(900)?.let { out.put("screen", it) }
        }
        return ToolResult.Done(out, "Opened $label")
    }

    private fun phoneState(): ToolResult {
        val o = JSONObject()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        o.put("battery_percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        o.put("charging", bm.isCharging)
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        o.put("network", when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "other"
        })
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun pct(stream: Int) = (100 * am.getStreamVolume(stream) / maxOf(1, am.getStreamMaxVolume(stream)))
        o.put("volume_media", pct(AudioManager.STREAM_MUSIC)).put("volume_ring", pct(AudioManager.STREAM_RING)).put("volume_alarm", pct(AudioManager.STREAM_ALARM))
        o.put("ringer", when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" })
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        o.put("dnd", nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
        runCatching { o.put("brightness_percent", android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) * 100 / 255) }
        val st = StatFs(Environment.getDataDirectory().path)
        o.put("storage_free_gb", String.format("%.1f", st.availableBytes / 1e9))
        o.put("time", Times.nowText())
        o.put("screen_control", ScreenDriver.available)
        return ToolResult.Done(o, "Battery ${o.optInt("battery_percent")}% · ${o.optString("network")}")
    }

    private fun setSetting(name: String, value: String): ToolResult {
        val on = value in setOf("on", "true", "1", "yes", "enable")
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun vol(stream: Int, label: String): ToolResult {
            val pct = value.filter { it.isDigit() }.toIntOrNull() ?: return ToolResult.Failed("Give a number 0-100")
            val max = am.getStreamMaxVolume(stream)
            return runCatching { am.setStreamVolume(stream, (pct.coerceIn(0, 100) * max / 100), 0); ToolResult.ok("$label volume $pct%") }
                .getOrElse { ToolResult.Failed("Couldn't change $label volume (${it.message}). Do-not-disturb access may be needed.") }
        }
        return when (name) {
            "volume_media", "media" -> vol(AudioManager.STREAM_MUSIC, "Media")
            "volume_ring", "ring" -> vol(AudioManager.STREAM_RING, "Ring")
            "volume_alarm", "alarm" -> vol(AudioManager.STREAM_ALARM, "Alarm")
            "ringer" -> {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val mode = when (value) { "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
                runCatching { am.ringerMode = mode; ToolResult.ok("Ringer: $value") }.getOrElse {
                    if (!nm.isNotificationPolicyAccessGranted) launch(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    ToolResult.Failed("Changing the ringer needs Do-not-disturb access for Seyal. The settings page is open; the user must allow it, then try again.")
                }
            }
            "dnd" -> {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!nm.isNotificationPolicyAccessGranted) {
                    launch(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    return ToolResult.Failed("Do-not-disturb needs access for Seyal. The settings page is open; the user must allow it, then try again.")
                }
                nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                ToolResult.ok("Do not disturb ${if (on) "on" else "off"}")
            }
            "brightness" -> {
                val pct = value.filter { it.isDigit() }.toIntOrNull() ?: return ToolResult.Failed("Give a number 0-100")
                if (!android.provider.Settings.System.canWrite(ctx)) {
                    launch(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    return ToolResult.Failed("Brightness needs 'Modify system settings' for Seyal. The settings page is open; the user must allow it, then try again.")
                }
                android.provider.Settings.System.putInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                android.provider.Settings.System.putInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, pct.coerceIn(1, 100) * 255 / 100)
                ToolResult.ok("Brightness $pct%")
            }
            "auto_rotate", "rotation" -> {
                if (!android.provider.Settings.System.canWrite(ctx)) {
                    launch(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    return ToolResult.Failed("Needs 'Modify system settings' for Seyal. The settings page is open; the user must allow it, then try again.")
                }
                android.provider.Settings.System.putInt(ctx.contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
                ToolResult.ok("Auto-rotate ${if (on) "on" else "off"}")
            }
            "flashlight", "torch" -> runCatching {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return ToolResult.Failed("No flashlight on this phone")
                cm.setTorchMode(id, on); ToolResult.ok("Flashlight ${if (on) "on" else "off"}")
            }.getOrElse { ToolResult.Failed("Flashlight: ${it.message}") }
            "wifi" -> {
                if (Build.VERSION.SDK_INT >= 29) { launch(Intent(android.provider.Settings.Panel.ACTION_WIFI)); ToolResult.ok("Wi-Fi panel opened · toggle it there", "opened_panel" to true, "note" to "Android does not let apps switch Wi-Fi directly; the quick panel is open for the user.") }
                else { launch(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)); ToolResult.ok("Wi-Fi settings opened") }
            }
            "bluetooth" -> {
                if (on) launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) else launch(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                ToolResult.ok("Bluetooth ${if (on) "prompt" else "settings"} opened", "opened_panel" to true)
            }
            else -> ToolResult.Failed("Unknown setting \"$name\"")
        }
    }

    private fun appUsage(range: String): ToolResult {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        if (mode != AppOpsManager.MODE_ALLOWED) {
            launch(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return ToolResult.Failed("Screen time needs 'Usage access' for Seyal. The settings page is open; the user must allow it, then try again.")
        }
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = if (range == "week") end - 7 * 24 * 3600_000L else Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end).orEmpty()
        val pm = ctx.packageManager
        val byPkg = HashMap<String, Long>()
        stats.filter { it.firstTimeStamp >= start - 60_000 || range == "today" }.forEach { s -> if (s.totalTimeInForeground > 0) byPkg[s.packageName] = (byPkg[s.packageName] ?: 0L) + s.totalTimeInForeground }
        val top = byPkg.entries.sortedByDescending { it.value }.take(12)
        val arr = JSONArray()
        var total = 0L
        top.forEach { (pkg, ms) ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            if (pkg == ctx.packageName || label.contains("launcher", true)) return@forEach
            arr.put(JSONObject().put("name", label).put("minutes", ms / 60_000)); total += ms
        }
        return ToolResult.Done(JSONObject().put("range", range).put("apps", arr).put("total_minutes", total / 60_000), "Top: ${arr.optJSONObject(0)?.optString("name") ?: "-"} ${arr.optJSONObject(0)?.optLong("minutes") ?: 0} min")
    }

    /** A fresh position (up to 12 s), else the newest one the phone already has. */
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun freshLocation(lm: LocationManager): android.location.Location? {
        val known = runCatching { listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time } }.getOrNull()
        if (known != null && System.currentTimeMillis() - known.time < 3 * 60_000) return known
        if (Build.VERSION.SDK_INT >= 30) {
            val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            if (provider != null) {
                val fresh = kotlinx.coroutines.withTimeoutOrNull(12_000) {
                    kotlinx.coroutines.suspendCancellableCoroutine<android.location.Location?> { cont ->
                        val cancel = android.os.CancellationSignal()
                        cont.invokeOnCancellation { cancel.cancel() }
                        runCatching { lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { cont.resumeWith(Result.success(it)) } }
                            .onFailure { if (cont.isActive) cont.resumeWith(Result.success(null)) }
                    }
                }
                if (fresh != null) return fresh
            }
        }
        return known
    }

    private suspend fun location(): ToolResult {
        if (!has(Manifest.permission.ACCESS_COARSE_LOCATION) && !has(Manifest.permission.ACCESS_FINE_LOCATION)) return ToolResult.Failed("Location permission not granted (Control → Phone permissions). Home city from settings: ${Settings.homeCity.ifBlank { "unknown" }}")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = freshLocation(lm)
            ?: return ToolResult.Failed("No location fix (is Location on?). Home city from settings: ${Settings.homeCity.ifBlank { "unknown" }}")
        val place = runCatching { @Suppress("DEPRECATION") Geocoder(ctx).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { a -> listOfNotNull(a.thoroughfare, a.subLocality, a.locality).distinct().joinToString(", ") } }.getOrNull() ?: ""
        // A link anyone can open on a map: what to send when someone asks where you are.
        val link = "https://maps.google.com/?q=${"%.6f".format(java.util.Locale.US, loc.latitude)},${"%.6f".format(java.util.Locale.US, loc.longitude)}"
        return ToolResult.ok(place.ifBlank { "Location found" }, "lat" to loc.latitude, "lon" to loc.longitude, "place" to place, "map_link" to link, "age_min" to (System.currentTimeMillis() - loc.time) / 60_000)
    }
}
