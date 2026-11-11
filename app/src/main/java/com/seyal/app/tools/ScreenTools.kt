package com.seyal.app.tools

import com.seyal.app.agent.Tool
import com.seyal.app.agent.ToolResult
import com.seyal.app.agent.noArgs
import com.seyal.app.agent.schema
import com.seyal.app.data.Part
import com.seyal.app.device.ScreenDriver
import org.json.JSONObject

/**
 * Operating any app through the screen (Full edition with Accessibility on). The screen is a numbered
 * list; every action returns the new screen, so the usual rhythm is act → act → act, not act → look → act.
 */
object ScreenTools {
    private val screenOn = { ScreenDriver.available }

    private const val SCREEN_NOTE = "The result includes the new screen (app + numbered elements); use those ids next."

    val all: List<Tool> by lazy {
        listOf(
            Tool(
                "screen_read", "screen",
                "What is on the screen right now: app, and elements one per line as `id [role] label (notes) @x,y`. Roles: button, tap (tappable row), field, check:on/off, heading, text. Only needed at the start or after waiting: every screen action already returns the new screen.",
                noArgs, available = screenOn,
            ) { _, _ -> wrap(ScreenDriver.read()) { "Looked at ${it.optString("app")}" } },

            Tool(
                "screenshot", "screen",
                "A picture of the screen, when the element list isn't enough (images, maps, seat layouts, games, charts, or an empty list). You will see the image. Then use screen_tap_at with positions on the 0-1000 scale of the picture.",
                noArgs, available = screenOn,
            ) { _, _ ->
                val shot = try { ScreenDriver.screenshot() } catch (e: Exception) { return@Tool ToolResult.Failed(e.message ?: "Screenshot failed", retryable = true) }
                ToolResult.Done(
                    JSONObject().put("ok", true).put("app", ScreenDriver.currentAppLine())
                        .put("note", "Positions for screen_tap_at / screen_swipe: x and y on a 0-1000 scale of this picture: (0,0) top-left, (1000,1000) bottom-right. Several taps you are sure of (seats) can go in one reply."),
                    "Took a screenshot", Part.Image(shot.mime, shot.b64),
                )
            },

            Tool(
                "screen_tap", "screen", "Tap an element by id (from the latest screen) or by its visible text. $SCREEN_NOTE",
                schema { int("id", "Element id from the latest screen"); str("text", "Visible text or label, if you have no id") },
                available = screenOn, sideEffect = true,
            ) { a, _ ->
                wrap(ScreenDriver.tap(a.optInt("id").takeIf { it > 0 }, a.optString("text").ifBlank { null })) { "Tapped ${it.optString("tapped")}" }
            },

            Tool(
                "screen_tap_at", "screen", "Tap a point when there is no element to target (a seat, a map, a canvas): x,y on a 0-1000 scale of the screen, as in the screenshot (0,0 top-left, 1000,1000 bottom-right) and the @x,y of elements. $SCREEN_NOTE",
                schema { int("x", "0-1000 from the left", required = true); int("y", "0-1000 from the top", required = true) },
                available = screenOn, sideEffect = true,
            ) { a, _ -> wrap(ScreenDriver.tapAt(a.optInt("x"), a.optInt("y"))) { "Tapped at ${a.optInt("x")},${a.optInt("y")}" } },

            Tool(
                "screen_long_press", "screen", "Long-press an element by id or visible text (opens context menus, selects items). $SCREEN_NOTE",
                schema { int("id", "Element id"); str("text", "Visible text, if no id") }, available = screenOn, sideEffect = true,
            ) { a, _ -> wrap(ScreenDriver.tap(a.optInt("id").takeIf { it > 0 }, a.optString("text").ifBlank { null }, long = true)) { "Long-pressed ${it.optString("tapped")}" } },

            Tool(
                "screen_type", "screen",
                "Type text into a field: the one with id, else the focused one, else the first field. A search bar that is still a button is tapped first for you. Replaces what's there unless append=true. enter=true presses the keyboard's Go/Search/Send. Never for passwords, PINs or OTPs. $SCREEN_NOTE",
                schema { str("text", "Text to type", required = true); int("id", "Field id from the latest screen"); bool("enter", "Press enter/search after typing"); bool("append", "Add to the existing text") },
                available = screenOn, sideEffect = true,
            ) { a, _ ->
                val t = a.optString("text")
                wrap(ScreenDriver.type(t, a.optInt("id").takeIf { it > 0 }, a.optBoolean("enter"), a.optBoolean("append"))) { "Typed \"${t.take(40)}\"" }
            },

            Tool(
                "screen_scroll", "screen", "Scroll to see more. direction: down (further down the page) | up | left | right. $SCREEN_NOTE",
                schema { str("direction", "down | up | left | right", required = true, enum = listOf("down", "up", "left", "right")); int("id", "An element inside the list to scroll, optional") },
                available = screenOn,
            ) { a, _ -> wrap(ScreenDriver.scroll(a.optString("direction", "down"), a.optInt("id").takeIf { it > 0 })) { "Scrolled ${it.optString("direction")}" } },

            Tool(
                "screen_swipe", "screen", "Swipe from one point to another (0-1000 scale of the screen, like screen_tap_at): carousels, sliders, pull-to-refresh. $SCREEN_NOTE",
                schema { int("x1", "Start x", required = true); int("y1", "Start y", required = true); int("x2", "End x", required = true); int("y2", "End y", required = true); int("ms", "Duration (default 300)") },
                available = screenOn,
            ) { a, _ -> wrap(ScreenDriver.swipe(a.optInt("x1"), a.optInt("y1"), a.optInt("x2"), a.optInt("y2"), a.optInt("ms", 300))) { "Swiped" } },

            Tool("screen_back", "screen", "Press Back (closes a popup, keyboard or page). $SCREEN_NOTE", noArgs, available = screenOn) { _, _ -> wrap(ScreenDriver.global("back")) { "Went back" } },
            Tool("screen_home", "screen", "Go to the home screen.", noArgs, available = screenOn) { _, _ -> wrap(ScreenDriver.global("home")) { "Went home" } },
            Tool("screen_recents", "screen", "Open the recent-apps view.", noArgs, available = screenOn) { _, _ -> wrap(ScreenDriver.global("recents")) { "Opened recents" } },
            Tool("screen_notifications", "screen", "Pull down the notification shade.", noArgs, available = screenOn) { _, _ -> wrap(ScreenDriver.global("notifications")) { "Opened notifications" } },

            Tool(
                "screen_wait", "screen", "Wait until some text appears on screen (a page finished loading, a result arrived), then return the screen.",
                schema { str("text", "Text to wait for", required = true); int("seconds", "Give up after (default 10, max 60)") },
                available = screenOn,
            ) { a, _ -> wrap(ScreenDriver.waitFor(a.optString("text"), a.optInt("seconds", 10).coerceIn(1, 60) * 1000L, returnScreen = true)) { "\"${a.optString("text").take(30)}\" appeared" } },
        )
    }

    /** A failed action still hands back the current screen, as a normal result marked ok=false, so the AI can carry on from it. */
    private fun wrap(o: JSONObject, line: (JSONObject) -> String): ToolResult =
        if (o.optBoolean("ok", true) && !o.has("error")) ToolResult.Done(o, line(o))
        else if (o.has("screen") && !o.optBoolean("blocked")) ToolResult.Done(o.put("ok", false), "Didn't work: ${o.optString("error").take(60)}")
        else ToolResult.Failed(o.optString("error").ifBlank { "Did not work" })
}
