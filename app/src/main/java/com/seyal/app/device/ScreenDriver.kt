package com.seyal.app.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * The agent's eyes and hands on the phone screen (Full edition, backed by [SeyalAccessibilityService]).
 *
 * Reading: the screen becomes a short numbered list ("3 [button] Search @540,120"). A tappable row and
 * the texts inside it are one line, the way a person sees it.
 * Acting: tap, type, scroll, swipe, back/home. Every action waits for the screen to settle and returns
 * the new screen. Ids always refer to the screen the AI last saw, and are checked before use: if that
 * element moved or changed, the action is refused instead of hitting something else.
 * Safety: never types into secret fields (passwords, PINs, OTPs, card numbers) and never presses
 * payment or final-confirmation buttons, whichever way it is asked to.
 */
object ScreenDriver {
    @Volatile private var service: SeyalAccessibilityService? = null
    private val lock = Mutex()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastChange = 0L
    @Volatile private var foreground = ""

    val available: Boolean get() = service != null

    /** Ticks whenever screen control or notification access starts or stops, so setup screens update by themselves. */
    val access = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun accessChanged() { access.value = access.value + 1 }

    internal fun attach(s: SeyalAccessibilityService) { service = s; main.post { renderPill() }; accessChanged() }
    internal fun detach(s: SeyalAccessibilityService) { if (service === s) { main.post { removePill(s) }; service = null; accessChanged() } }
    internal fun noteChange() { lastChange = System.currentTimeMillis() }

    // ---------------------------------------------------------------- safety rules

    /** Buttons Seyal never presses: the user does the last step of anything involving money. */
    private val payWords = Regex(
        "\\b(pay|payment|pay\\s*now|pay\\s*₹|pay\\s*rs|make payment|proceed to pay|proceed to payment|pay securely|place order|place your order|buy now|confirm order|confirm booking|confirm payment|confirm and pay|confirm & pay|slide to pay|swipe to pay|send money|transfer money|upi pin|enter pin|subscribe now|complete purchase|confirm purchase|recharge now)\\b|செலுத்து|भुगतान",
        RegexOption.IGNORE_CASE,
    )
    private val payIds = Regex("pay|place.?order|buy.?now|confirm.?order|checkout.?confirm", RegexOption.IGNORE_CASE)

    /** Fields Seyal never types into or reads out. */
    private val secretWords = Regex("\\b(otp|one.?time|pin|mpin|upi|cvv|cvc|card.?number|card.?no|passw\\w*|passcode|security.?code|expiry|aadhaar)\\b", RegexOption.IGNORE_CASE)

    private fun viewId(n: AccessibilityNodeInfo): String = n.viewIdResourceName?.substringAfterLast('/').orEmpty()

    private fun passwordType(n: AccessibilityNodeInfo): Boolean {
        val t = n.inputType
        val cls = t and InputType.TYPE_MASK_CLASS
        val variation = t and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun hintOf(n: AccessibilityNodeInfo): String = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""

    /** A field for a password, PIN, OTP, card or UPI detail. */
    private fun isSecret(n: AccessibilityNodeInfo): Boolean {
        if (n.isPassword || (n.isEditable && passwordType(n))) return true
        if (!n.isEditable) return false
        val words = listOf(hintOf(n), n.contentDescription?.toString().orEmpty(), viewId(n).replace('_', ' ')).joinToString(" ")
        return secretWords.containsMatchIn(words)
    }

    /** What a tap on [n] would press, as words: its own label, its tappable row's label, its view id. */
    private fun pressLabel(n: AccessibilityNodeInfo): String {
        val target = clickableUp(n) ?: n
        val parts = mutableListOf<String>()
        listOf(n, target).forEach { x -> (x.text?.toString() ?: x.contentDescription?.toString())?.trim()?.takeIf { it.isNotBlank() }?.let { parts += it } }
        collectLabel(target, parts, 0, includeTappable = true)
        return parts.distinct().joinToString(" ")
    }

    private fun isPayment(n: AccessibilityNodeInfo): Boolean {
        if (payWords.containsMatchIn(pressLabel(n))) return true
        val ids = listOf(viewId(n), (clickableUp(n) ?: n).let { viewId(it) })
        return ids.any { it.isNotBlank() && payIds.containsMatchIn(it) }
    }

    private fun blocked(label: String) = JSONObject().put("ok", false).put("blocked", true)
        .put("error", "\"${label.take(60)}\" is a payment or final-confirmation step. Seyal never presses those. Stop here and tell the user the screen is ready for them to check and press it.")

    // ---------------------------------------------------------------- one task at a time

    @Volatile private var owner: String? = null
    @Volatile private var ownerAt = 0L

    /** The screen belongs to one task at a time. False if another task is using it right now. */
    fun claim(session: String): Boolean = synchronized(this) {
        val now = System.currentTimeMillis()
        if (owner != null && owner != session && now - ownerAt < 120_000) return false
        owner = session; ownerAt = now
        true
    }

    fun release(session: String) = synchronized(this) { if (owner == session) owner = null }

    // ---------------------------------------------------------------- foreground app

    private var imePackages: Set<String>? = null

    /** True when a real app came to the front. Keyboards, permission dialogs and the system bar are not "an app opening". */
    internal fun noteForeground(pkg: String): Boolean {
        main.post { renderPill() }
        val s = service ?: return false
        val ime = imePackages ?: runCatching { (s.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).inputMethodList.map { it.packageName }.toSet() }.getOrDefault(emptySet()).also { imePackages = it }
        if (pkg in ime || pkg == "com.android.systemui" || pkg == "android" || pkg.contains("permissioncontroller") || pkg == s.packageName) return false
        if (pkg == foreground) return false
        foreground = pkg
        return !pkg.contains("launcher", true)
    }

    fun appLabel(ctx: Context, pkg: String): String =
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    /** Package name of the app in front, or "". */
    fun frontPackage(): String = runCatching { service?.rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: foreground

    fun currentAppLine(): String {
        val s = service ?: return "screen control off"
        val pkg = frontPackage().ifBlank { return "unknown" }
        return "${appLabel(s, pkg)} ($pkg)"
    }

    private fun svc(): SeyalAccessibilityService = service ?: throw IllegalStateException("Screen control is off. Full edition → Control → turn on Seyal in Accessibility settings.")

    /** The real size of the screen in pixels (the coordinate space of every position). */
    private fun screenSize(s: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = runCatching { (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds }.getOrNull()
            if (b != null && b.width() > 0) return b.width() to b.height()
        }
        val m = s.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    // ---------------------------------------------------------------- reading

    private class El(val node: AccessibilityNodeInfo, val role: String, val text: String, val extra: String, val bounds: Rect, val raw: String)

    private fun rawOf(n: AccessibilityNodeInfo) = (n.text?.toString() ?: "") + "|" + (n.contentDescription?.toString() ?: "")

    /** The listing from the latest read; and the one the AI last saw (what its ids refer to). */
    @Volatile private var lastEls: Map<Int, El> = emptyMap()
    @Volatile private var modelEls: Map<Int, El> = emptyMap()
    @Volatile private var lastListing = ""
    @Volatile private var lastPkg = ""

    /** Called right before [session]'s AI is asked for its next step: from now on, ids mean this listing. Another task can't move them. */
    fun freezeIds(session: String) = synchronized(this) { if (owner == null || owner == session) modelEls = lastEls }

    /** Waits until the screen has stopped changing for a moment (at most [maxMs]). */
    private suspend fun settle(maxMs: Long = 1500) {
        var waited = 0L
        while (System.currentTimeMillis() - lastChange < 300 && waited < maxMs) { delay(100); waited += 100 }
    }

    /** What is on screen now. Output: app, package, size, elements (one per line), scrollable. */
    suspend fun read(maxElements: Int = 110): JSONObject = lock.withLock { settle(); describe(maxElements) }

    /** The screen after waiting [waitMs], or null when screen control is off. For tools that open apps. */
    suspend fun snapshotAfter(waitMs: Long): JSONObject? {
        if (!available) return null
        return lock.withLock { delay(waitMs); settle(); runCatching { describe(90) }.getOrNull() }
    }

    /** Waits until [pkg] is the app in front. */
    suspend fun waitForPackage(pkg: String, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) { if (frontPackage() == pkg) return true; delay(200) }
        return frontPackage() == pkg
    }

    /**
     * Texts inside a tappable row: "Wi-Fi · Home network · [on]". Tappable or editable children are
     * elements of their own and are left out (so a row is never labelled with a button inside it).
     */
    private fun collectLabel(n: AccessibilityNodeInfo, out: MutableList<String>, depth: Int, includeTappable: Boolean = false) {
        if (depth > 8 || out.size >= (if (includeTappable) 16 else 8)) return
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (!c.isVisibleToUser) continue
            // For the payment check, a button inside the row counts too ("Pay ₹500" inside a clickable bar).
            if (!includeTappable && (c.isClickable || c.isEditable)) continue
            if (c.isEditable) continue
            if (c.isCheckable) out += if (c.isChecked) "[on]" else "[off]"
            val t = (c.text?.toString()?.trim().orEmpty()).ifBlank { c.contentDescription?.toString()?.trim().orEmpty() }
            if (t.isNotBlank() && !c.isPassword && t !in out) out += if (t.length > 120) t.take(120) + "…" else t
            collectLabel(c, out, depth + 1, includeTappable)
        }
    }

    private fun describe(maxElements: Int): JSONObject {
        val s = svc()
        val root = s.rootInActiveWindow ?: return JSONObject().put("app", "unknown").put("elements", "").put("note", "No window content (lock screen, or an app that blocks reading). Try screenshot.")
        val pkg = root.packageName?.toString() ?: ""
        // Popups such as autocomplete lists are separate windows of the same app.
        val roots = mutableListOf(root)
        runCatching {
            s.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
                .filter { it.packageName?.toString() == pkg && it != root }.forEach { roots += it }
        }
        val els = mutableListOf<El>()
        val seen = HashSet<String>()
        var count = 0
        var scrollable = false
        val (sw, sh) = screenSize(s)
        val screenArea = sw.toLong() * sh
        fun walk(n: AccessibilityNodeInfo?, depth: Int, inRow: Boolean) {
            if (n == null || depth > 45 || count > 3000) return
            if (!n.isVisibleToUser) return
            count++
            val r = Rect(); n.getBoundsInScreen(r)
            if (n.isScrollable) scrollable = true
            val own = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val hint = hintOf(n)
            val rid = viewId(n).replace('_', ' ')
            val cls = n.className?.toString()?.substringAfterLast('.') ?: ""
            var childrenInRow = inRow
            if (!r.isEmpty) {
                when {
                    n.isEditable -> {
                        val secret = isSecret(n)
                        val showingHint = (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) || own == hint
                        val value = if (secret || showingHint) "" else own
                        val extra = listOfNotNull(
                            hint.ifBlank { desc }.ifBlank { rid }.takeIf { it.isNotBlank() }?.let { "hint: $it" },
                            if (n.isFocused) "focused" else null,
                            if (secret) "SECRET: only the user types here" else null,
                        ).joinToString(", ")
                        els += El(n, "field", value.take(120), extra, r, rawOf(n))
                    }
                    (n.isClickable || n.isCheckable) && r.width().toLong() * r.height() < screenArea * 45 / 100 -> {
                        val parts = mutableListOf<String>()
                        if (own.isNotBlank()) parts += own.take(120) else if (desc.isNotBlank()) parts += desc.take(120)
                        collectLabel(n, parts, 0)
                        var label = parts.distinct().joinToString(" · ")
                        if (label.length > 240) label = label.take(240) + "…"
                        val role = when {
                            n.isCheckable -> if (n.isChecked) "check:on" else "check:off"
                            cls.contains("Button", true) || cls.contains("Image", true) -> "button"
                            else -> "tap"
                        }
                        val extra = listOfNotNull(
                            if (label.isBlank() && rid.isNotBlank()) "id: $rid" else null,
                            if (n.isSelected) "selected" else null,
                            if (!n.isEnabled) "disabled" else null,
                        ).joinToString(", ")
                        if (seen.add("$label|${r.left},${r.top}")) els += El(n, role, label.ifBlank { "(unlabeled)" }, extra, r, rawOf(n))
                        childrenInRow = true
                    }
                    !inRow && (own.isNotBlank() || desc.isNotBlank()) -> {
                        val t = own.ifBlank { desc }
                        if (seen.add("$t|${r.left},${r.top}")) els += El(n, if (n.heading()) "heading" else "text", if (t.length > 300) t.take(300) + "…" else t, if (n.isSelected) "selected" else "", r, rawOf(n))
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, childrenInRow)
        }
        roots.forEach { walk(it, 0, false) }
        // Too much on screen: keep everything you can act on, then as much text as fits, in screen order.
        val kept = if (els.size <= maxElements) els else {
            val act = els.filter { it.role != "text" && it.role != "heading" }.take(maxElements)
            val texts = els.filter { it.role == "text" || it.role == "heading" }.take((maxElements - act.size).coerceAtLeast(0))
            val keep = (act + texts).toSet()
            els.filter { it in keep }
        }
        val map = HashMap<Int, El>()
        val sb = StringBuilder()
        kept.forEachIndexed { i, e ->
            val id = i + 1
            map[id] = e
            sb.append(id).append(" [").append(e.role).append("] ").append(e.text.replace('\n', ' '))
            if (e.extra.isNotBlank()) sb.append(" (").append(e.extra).append(')')
            // Positions on a 0-1000 scale of the screen, the same scale as screenshots and screen_tap_at.
            sb.append(" @").append(e.bounds.centerX() * 1000L / sw.coerceAtLeast(1)).append(',').append(e.bounds.centerY() * 1000L / sh.coerceAtLeast(1)).append('\n')
        }
        lastEls = map
        lastListing = sb.toString()
        lastPkg = pkg
        return JSONObject().put("app", appLabel(s, pkg)).put("package", pkg)
            .put("scrollable", scrollable).put("elements", sb.toString().trimEnd())
            .also { if (els.size > kept.size) it.put("note", "${els.size - kept.size} more items not listed: scroll, or tap by text.") }
            .also { if (kept.isEmpty()) it.put("note", "Nothing readable here (a game, video, map or canvas?). Use screenshot to look, and screen_tap_at to tap.") }
    }

    private fun AccessibilityNodeInfo.heading(): Boolean = Build.VERSION.SDK_INT >= 28 && isHeading

    /** Adds the settled screen to an action's result. [compare]: say so when nothing visibly changed. */
    private suspend fun withScreen(o: JSONObject, waitMs: Long = 350, compare: Boolean = true): JSONObject {
        if (!o.optBoolean("ok")) return o
        val before = lastListing
        val beforePkg = lastPkg
        delay(waitMs); settle()
        val screen = runCatching { describe(90) }.getOrNull() ?: return o
        if (compare && before.isNotBlank() && beforePkg == lastPkg && before == lastListing) {
            o.put("note", "The screen has not changed (yet). If the app is loading, screen_wait for what you expect before trying again; otherwise try another way.")
        }
        return o.put("screen", screen)
    }

    // ---------------------------------------------------------------- finding

    /** The element the AI meant by [id], if it is still the same thing (see [resolve]). */
    private fun nodeById(id: Int): AccessibilityNodeInfo? = resolve(id)?.first

    /**
     * The element the AI meant by [id]. Apps redraw all the time (a keyboard opens, a toolbar slides,
     * a video preview starts), so a changed screen doesn't mean the element is gone:
     * 1. the same node, same text, same place → it;
     * 2. otherwise the screen is read again, and exactly one element with the same role and label → that one;
     *    a field is also found again by its hint, or as the only field on screen.
     * Anything else (it's gone, or there are several look-alikes, like the "Add" buttons of a list that
     * scrolled) → null, and the AI picks again from the new screen.
     * second = true when it had to be found again.
     */
    private fun resolve(id: Int): Pair<AccessibilityNodeInfo, Boolean>? {
        val e = modelEls[id] ?: lastEls[id] ?: return null
        val n = e.node
        if (runCatching { n.refresh() }.getOrDefault(false) && n.isVisibleToUser && rawOf(n) == e.raw) {
            val r = Rect(); n.getBoundsInScreen(r)
            if (r == e.bounds) return n to false
        }
        runCatching { describe(90) }.getOrNull() ?: return null
        val now = lastEls.values
        val labelled = e.text.isNotBlank() && e.text != "(unlabeled)"
        if (labelled) {
            val same = now.filter { it.role == e.role && it.text == e.text }
            val exact = same.filter { it.extra == e.extra }
            (if (exact.size == 1) exact else same).singleOrNull()?.let { return it.node to true }
        }
        if (e.role == "field") {
            val fields = now.filter { it.role == "field" }
            val hint = Regex("hint: ([^,]+)").find(e.extra)?.groupValues?.get(1)
            // A field with a hint is only found again by that hint (a form's next field is not the same field).
            if (hint != null) fields.filter { it.extra.contains("hint: $hint") }.singleOrNull()?.let { return it.node to true }
            else fields.singleOrNull()?.let { return it.node to true }
        }
        return null
    }

    private fun labelOf(id: Int): String? = (modelEls[id] ?: lastEls[id])?.text
    private fun roleOf(id: Int): String? = (modelEls[id] ?: lastEls[id])?.role

    private fun clickableUp(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 7) { if (cur.isClickable) return cur; cur = cur.parent; hops++ }
        return null
    }

    /** Finds a node by visible text or label: exact match first (tappable preferred), then partial. */
    private fun findByText(text: String, exactOnly: Boolean = false): AccessibilityNodeInfo? {
        val root = svc().rootInActiveWindow ?: return null
        val t = text.trim()
        if (t.isBlank()) return null
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 45 || all.size > 300) return
            if (!n.isVisibleToUser) return
            val d1 = n.contentDescription?.toString()?.trim() ?: ""
            val t1 = n.text?.toString()?.trim() ?: ""
            if (d1.contains(t, true) || t1.contains(t, true)) all += n
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        fun isExact(n: AccessibilityNodeInfo) = n.text?.toString()?.trim().equals(t, true) || n.contentDescription?.toString()?.trim().equals(t, true)
        val exact = all.filter { isExact(it) }
        exact.firstOrNull { it.isClickable || it.parent?.isClickable == true }?.let { return it }
        exact.firstOrNull()?.let { return it }
        if (exactOnly) return null
        return all.minByOrNull { (it.text?.length ?: 0) + (it.contentDescription?.length ?: 0) }
    }

    /** The deepest visible node under a point. */
    private fun nodeAt(x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 45 || !n.isVisibleToUser) return
            val r = Rect(); n.getBoundsInScreen(r)
            if (!r.contains(x, y)) return
            best = n
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(service?.rootInActiveWindow, 0)
        return best
    }

    /** True if [text] is visible somewhere on screen. */
    suspend fun isVisible(text: String, exactOnly: Boolean = false): Boolean = lock.withLock { runCatching { findByText(text, exactOnly) != null }.getOrDefault(false) }

    private fun center(n: AccessibilityNodeInfo): Pair<Float, Float> { val r = Rect(); n.getBoundsInScreen(r); return r.exactCenterX() to r.exactCenterY() }

    private fun changed(id: Int): JSONObject = JSONObject().put("ok", false)
        .put("error", "Element #$id is not on the screen any more (the screen changed). Here is the screen now: pick from it.")
        .put("screen", runCatching { describe(90) }.getOrNull())

    // ---------------------------------------------------------------- acting

    /**
     * Taps an element by id (from the screen the AI saw) or by visible text. Refuses payment buttons.
     * [pkg]: only if that app is in front. Returns the new screen.
     */
    suspend fun tap(id: Int?, text: String?, long: Boolean = false, exactOnly: Boolean = false, returnScreen: Boolean = true, pkg: String? = null): JSONObject = lock.withLock {
        if (pkg != null && frontPackage() != pkg) return@withLock JSONObject().put("ok", false).put("error", "The expected app is not in front any more.")
        var byId = id != null && id > 0
        var refound = false
        val node: AccessibilityNodeInfo = if (byId) {
            val hit = resolve(id!!)
            refound = hit?.second == true
            // The id is stale but the AI also said what it wanted: tap that text if it is on screen.
            hit?.first ?: (if (!text.isNullOrBlank()) findByText(text, exactOnly = true)?.also { byId = false; refound = true } else null) ?: return@withLock changed(id)
        } else {
            (if (!text.isNullOrBlank()) findByText(text, exactOnly) else null)
                ?: return@withLock JSONObject().put("ok", false).put("error", "Nothing called \"$text\" on screen. Here is the screen now.").put("screen", runCatching { describe(90) }.getOrNull())
        }
        val label = ((if (byId) labelOf(id!!) else null) ?: node.text?.toString()?.takeIf { it.isNotBlank() && !node.isEditable } ?: node.contentDescription?.toString()?.ifBlank { null } ?: hintOf(node).ifBlank { null } ?: text ?: "#$id").take(80)
        if (isPayment(node)) return@withLock blocked(label)
        if (!node.isEnabled) return@withLock JSONObject().put("ok", false).put("error", "\"$label\" is disabled right now.")
        val target = if (long) node else (clickableUp(node) ?: node)
        var ok = runCatching { target.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        if (!ok) { val (x, y) = center(node); ok = gestureTap(x, y, if (long) 700 else 60) }
        val o = JSONObject().put("ok", ok).put("tapped", label).also { if (!ok) it.put("error", "The tap did not register") }
        if (refound && ok) o.put("note", "The screen had changed since you looked; found \"$label\" again and tapped it.")
        if (returnScreen) withScreen(o) else o
    }

    /** Taps a point on screen (for things with no element: a map, a seat, a canvas). Same safety rules as tap. */
    /** A position on the 0-1000 scale (left/top 0, right/bottom 1000) → real screen pixels. */
    private fun toPixels(u: Int, v: Int): Pair<Int, Int> {
        val (sw, sh) = screenSize(svc())
        return (u.coerceIn(0, 1000) * sw / 1000) to (v.coerceIn(0, 1000) * sh / 1000)
    }

    /** Tap at [u],[v] on the 0-1000 scale of the screen (as in screenshots and the @x,y of elements). */
    suspend fun tapAt(u: Int, v: Int): JSONObject = lock.withLock {
        val (x, y) = toPixels(u, v)
        val under = nodeAt(x, y)
        if (under != null) {
            if (isPayment(under)) return@withLock blocked(pressLabel(under))
            if (isSecret(under)) return@withLock JSONObject().put("ok", false).put("error", "That point is a secret field (password/PIN/OTP). Only the user types there.")
        }
        if (screenLooksLikePayment()) return@withLock JSONObject().put("ok", false).put("blocked", true).put("error", "This screen asks for a PIN, OTP or card details. Seyal stops here: the user finishes it.")
        withScreen(JSONObject().put("ok", gestureTap(x.toFloat(), y.toFloat(), 60)).put("tapped", under?.let { pressLabel(it).take(60) } ?: "$u,$v"))
    }

    /** A PIN pad, OTP box or card form is on screen (tapping by coordinates there is the user's job). */
    private fun screenLooksLikePayment(): Boolean {
        var hit = false
        val words = Regex("\\b(enter (upi )?pin|upi pin|enter otp|cvv)\\b", RegexOption.IGNORE_CASE)
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || hit || d > 45 || !n.isVisibleToUser) return
            if (isSecret(n)) { hit = true; return }
            val t = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
            if (words.containsMatchIn(t)) { hit = true; return }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(service?.rootInActiveWindow, 0)
        return hit
    }

    /**
     * Types into a field: the one with [id], or the focused one, or the first field on screen.
     * A search bar that is still a button is tapped first. Refuses secret fields. [enter] presses the keyboard's action key.
     */
    suspend fun type(text: String, id: Int?, enter: Boolean, append: Boolean): JSONObject = lock.withLock {
        val s = svc()
        var node: AccessibilityNodeInfo? = null
        var note: String? = null
        if (id != null && id > 0) {
            val n = nodeById(id)
            // The AI picked a search icon or bar, and the screen changed under it (the search box opened):
            // if a field has the cursor, or there is only one field, that is where the text goes.
            // (A field it picked that is gone is not replaced by another field.)
            if (n == null && roleOf(id) != null && roleOf(id) != "field") {
                val focused = s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && it.isVisibleToUser }
                val only = if (focused == null) lastEls.values.filter { it.role == "field" }.singleOrNull()?.node else null
                node = focused ?: only ?: return@withLock changed(id)
                note = "The screen had changed since you looked; typed into the ${if (focused != null) "field with the cursor" else "only field on screen"}."
            } else if (n == null) return@withLock changed(id)
            else if (n.isEditable) node = n else {
                // A search icon or bar that opens the real field when tapped.
                if (isPayment(n)) return@withLock blocked(labelOf(id) ?: "#$id")
                runCatching { (clickableUp(n) ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                delay(500); settle()
            }
        }
        if (node == null) node = s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        if (node == null) {
            var first: AccessibilityNodeInfo? = null
            fun walk(n: AccessibilityNodeInfo?, d: Int) { if (n == null || first != null || d > 45) return; if (n.isEditable && n.isVisibleToUser) { first = n; return }; for (i in 0 until n.childCount) walk(n.getChild(i), d + 1) }
            walk(s.rootInActiveWindow, 0)
            node = first
        }
        val field = node ?: return@withLock JSONObject().put("ok", false).put("error", "No text field on screen. Tap the search bar or field first.")
        if (isSecret(field)) return@withLock JSONObject().put("ok", false).put("error", "That is a secret field (password, PIN, OTP or card). Seyal never types there: ask the user to type it.")
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val existing = field.text?.toString().orEmpty()
        val showingHint = (Build.VERSION.SDK_INT >= 26 && field.isShowingHintText) || existing == hintOf(field)
        val value = if (append && existing.isNotBlank() && !showingHint) existing + text else text
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        var ok = runCatching { field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
        var before = existing
        if (ok) {
            // Some apps (search boxes in shopping and food apps) accept set-text and then throw it away,
            // so their search never sees the words. If the box doesn't show them, paste them instead.
            delay(200)
            if (runCatching { field.refresh() }.getOrDefault(false)) {
                val shown = field.text?.toString()
                if (shown != null && !shown.contains(value.take(24))) { ok = false; before = if (shown == hintOf(field)) "" else shown }
            }
        }
        if (!ok) {
            // Some fields reject set-text: select what's there, then paste over it.
            if (before.isNotEmpty() && (before != existing || !showingHint)) {
                val sel = Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, before.length) }
                runCatching { field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel) }
            }
            val cm = s.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Seyal", value))
            ok = runCatching { field.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
        }
        val o = JSONObject().put("ok", ok).put("typed", text.take(80)).also { if (!ok) it.put("error", "Could not type here") }
        if (ok && note != null) o.put("note", note)
        if (ok && enter) {
            delay(250)
            if (screenLooksLikePayment()) {
                o.put("note", "Typed, but did not press Enter: this screen asks for payment details, which is the user's step.")
            } else {
                val entered = Build.VERSION.SDK_INT >= 30 && runCatching { field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }.getOrDefault(false)
                if (!entered) o.put("note", "Typed, but could not press Enter: tap the search/go/send button on screen.")
            }
        }
        withScreen(o, if (enter) 900 else 350)
    }

    private fun isPager(n: AccessibilityNodeInfo): Boolean {
        val cls = n.className?.toString() ?: ""
        if (cls.contains("ViewPager") || cls.contains("Pager") || cls.contains("HorizontalScrollView")) return true
        val ci = n.collectionInfo ?: return false
        return ci.rowCount == 1 && ci.columnCount > 1
    }

    /** Scrolls the biggest scrollable list on screen (or the one holding element [id]). */
    suspend fun scroll(direction: String, id: Int?): JSONObject = lock.withLock {
        val s = svc()
        val dir = direction.lowercase()
        val vertical = dir == "up" || dir == "down"
        var node: AccessibilityNodeInfo? = null
        if (id != null && id > 0) { var cur = nodeById(id); var hops = 0; while (cur != null && hops < 10) { if (cur.isScrollable && !(vertical && isPager(cur))) { node = cur; break }; cur = cur.parent; hops++ } }
        if (node == null) {
            var best: AccessibilityNodeInfo? = null; var bestArea = 0L
            fun walk(n: AccessibilityNodeInfo?, d: Int) {
                if (n == null || d > 45 || !n.isVisibleToUser) return
                if (n.isScrollable && !(vertical && isPager(n))) {
                    val r = Rect(); n.getBoundsInScreen(r); val a = r.width().toLong() * r.height()
                    if (a >= bestArea) { bestArea = a; best = n }   // ties: the inner list wins over its container
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
            }
            walk(s.rootInActiveWindow, 0)
            node = best
        }
        val forward = dir == "down" || dir == "right"
        var ok = runCatching { node?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }.getOrNull() ?: false
        if (!ok) {
            val (w0, h0) = screenSize(s)
            val w = w0.toFloat(); val h = h0.toFloat()
            ok = when (dir) {
                "up" -> gestureSwipe(w / 2, h * 0.3f, w / 2, h * 0.75f, 300)
                "left" -> gestureSwipe(w * 0.2f, h / 2, w * 0.8f, h / 2, 300)
                "right" -> gestureSwipe(w * 0.8f, h / 2, w * 0.2f, h / 2, 300)
                else -> gestureSwipe(w / 2, h * 0.75f, w / 2, h * 0.3f, 300)
            }
        }
        val o = withScreen(JSONObject().put("ok", ok).put("direction", dir), 600)
        if (o.has("note")) o.put("note", "Nothing moved: probably the end of the list in that direction (or it is still loading more).")
        o
    }

    /** Swipe between two points on the 0-1000 scale of the screen. */
    suspend fun swipe(u1: Int, v1: Int, u2: Int, v2: Int, ms: Int): JSONObject = lock.withLock {
        val (x1, y1) = toPixels(u1, v1)
        val (x2, y2) = toPixels(u2, v2)
        val start = nodeAt(x1, y1)
        if (start != null) {
            if (isPayment(start)) return@withLock blocked(pressLabel(start))
            if (isSecret(start)) return@withLock JSONObject().put("ok", false).put("error", "That starts on a secret field. Only the user acts there.")
        }
        withScreen(JSONObject().put("ok", gestureSwipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), ms.coerceIn(50, 3000).toLong())), 450)
    }

    suspend fun global(action: String, returnScreen: Boolean = true): JSONObject = lock.withLock {
        val s = svc()
        val code = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return@withLock JSONObject().put("ok", false).put("error", "Unknown action $action")
        }
        val o = JSONObject().put("ok", s.performGlobalAction(code))
        if (returnScreen) withScreen(o, 500, compare = false) else { delay(450); o }
    }

    /** Waits until [text] appears on screen (optionally only while [pkg] is in front), up to [timeoutMs]. */
    suspend fun waitFor(text: String, timeoutMs: Long, pkg: String? = null, returnScreen: Boolean = false): JSONObject {
        if (text.isBlank()) return JSONObject().put("ok", false).put("error", "Give some text to wait for")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val hit = lock.withLock { runCatching { if (pkg != null && frontPackage() != pkg) null else findByText(text) }.getOrNull() }
            if (hit != null) {
                val o = JSONObject().put("ok", true).put("found", text).put("after_ms", System.currentTimeMillis() - start)
                return if (returnScreen) lock.withLock { withScreen(o, 100, compare = false) } else o
            }
            delay(400)
        }
        val o = JSONObject().put("ok", false).put("error", "\"$text\" did not appear within ${timeoutMs / 1000}s")
        if (returnScreen) lock.withLock { runCatching { o.put("screen", describe(90)) } }
        return o
    }

    // ---------------------------------------------------------------- by view id (for known apps)

    private fun byViewId(pkg: String, id: String): AccessibilityNodeInfo? =
        runCatching { service?.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("$pkg:id/$id")?.firstOrNull { it.isVisibleToUser } }.getOrNull()

    /** Taps the element with view id [id] in [pkg] (which must be in front). */
    suspend fun tapViewId(pkg: String, id: String): Boolean = lock.withLock {
        if (frontPackage() != pkg) return@withLock false
        val n = byViewId(pkg, id) ?: return@withLock false
        if (isPayment(n)) return@withLock false
        runCatching { (clickableUp(n) ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
    }

    /** Text of a field by view id: "" when empty or showing its hint, null when not on screen. */
    suspend fun fieldText(pkg: String, id: String): String? = lock.withLock {
        val n = byViewId(pkg, id) ?: return@withLock null
        val t = n.text?.toString().orEmpty()
        if ((Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) || t == hintOf(n)) "" else t
    }

    // ---------------------------------------------------------------- screenshot

    class ScreenshotException(msg: String) : Exception(msg)

    /** A scaled-down JPEG of the screen for the AI, with its size and the real screen size. Android 11+. */
    class Shot(val mime: String, val b64: String, val width: Int, val height: Int, val screenWidth: Int, val screenHeight: Int)

    @Volatile private var lastShotAt = 0L

    /** One try at a screenshot: the whole display, or (Android 14+) just the app's window. Null + [why] on failure. */
    private suspend fun shoot(s: SeyalAccessibilityService, windowOnly: Boolean, why: (String) -> Unit): Bitmap? {
        val done = CompletableDeferred<Bitmap?>()
        val cb = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val hb = result.hardwareBuffer
                // Some screens (wide colour, HDR) hand back a buffer that needs a second way of reading it.
                val bmp = runCatching { Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) }
                    .recoverCatching { Bitmap.wrapHardwareBuffer(hb, null)?.copy(Bitmap.Config.ARGB_8888, false) }
                    .onFailure { why("could not read the picture (${it.javaClass.simpleName}: ${it.message?.take(60)})") }.getOrNull()
                runCatching { hb.close() }
                if (bmp == null) why("the picture came back empty")
                done.complete(bmp)
            }
            override fun onFailure(errorCode: Int) {
                why(
                    when (errorCode) {
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "too soon after the last screenshot"
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "this app blocks screenshots (secure screen): work from the element list instead"
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "Android refused (turn Seyal's Accessibility switch off and on)"
                        else -> "Android error $errorCode"
                    },
                )
                done.complete(null)
            }
        }
        main.post {
            runCatching {
                val wid = if (windowOnly && Build.VERSION.SDK_INT >= 34) s.rootInActiveWindow?.windowId else null
                if (wid != null && Build.VERSION.SDK_INT >= 34) s.takeScreenshotOfWindow(wid, { r -> main.post(r) }, cb)
                else s.takeScreenshot(Display.DEFAULT_DISPLAY, { r -> main.post(r) }, cb)
            }.onFailure { why(it.message ?: it.javaClass.simpleName); done.complete(null) }
        }
        return withTimeoutOrNull(6000) { done.await() }
    }

    suspend fun screenshot(maxWidth: Int = 720): Shot = lock.withLock {
        val s = svc()
        if (Build.VERSION.SDK_INT < 30) throw ScreenshotException("Screenshots need Android 11 or newer")
        var err = "no answer from Android"
        pillHidden(true)
        try {
            // Android refuses screenshots taken too close together.
            (1100 - (System.currentTimeMillis() - lastShotAt)).takeIf { it > 0 }?.let { delay(it) }
            var bmp = shoot(s, false) { err = it }
            if (bmp == null && !err.contains("secure")) { delay(1200); bmp = shoot(s, false) { err = it } }
            if (bmp == null && !err.contains("secure") && Build.VERSION.SDK_INT >= 34) { delay(400); bmp = shoot(s, true) { err = it } }
            lastShotAt = System.currentTimeMillis()
            if (bmp == null) throw ScreenshotException("Screenshot failed: $err")
            val scale = minOf(1f, maxWidth.toFloat() / bmp.width)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 60, out)
            val (sw, sh) = screenSize(s)
            Shot("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), small.width, small.height, sw, sh)
        } finally {
            pillHidden(false)
        }
    }

    // ---------------------------------------------------------------- gestures

    private suspend fun gestureTap(x: Float, y: Float, ms: Long): Boolean = gesture(Path().apply { moveTo(x, y) }, ms)

    private suspend fun gestureSwipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)

    private suspend fun gesture(path: Path, ms: Long): Boolean {
        val s = svc()
        val done = CompletableDeferred<Boolean>()
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        // The status pill must not catch the touch.
        pillHidden(true)
        try {
            main.post {
                val ok = runCatching {
                    s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(d: GestureDescription?) { done.complete(true) }
                        override fun onCancelled(d: GestureDescription?) { done.complete(false) }
                    }, null)
                }.getOrDefault(false)
                if (!ok) done.complete(false)
            }
            return withTimeoutOrNull(ms + 3000) { done.await() } ?: false
        } finally {
            pillHidden(false)
        }
    }

    // ---------------------------------------------------------------- status bar and question card

    /**
     * A progress bar just under the phone's status bar while Seyal works inside another app:
     * a spinner, "Seyal · Tapping Search", a Stop button, and a moving line underneath.
     * Tap the text to open Seyal, Stop to stop everything.
     */
    @Volatile private var statusText: String? = null
    @Volatile private var statusBusy = true
    @Volatile private var pill: LinearLayout? = null
    private var pillLabel: TextView? = null
    private var pillSpin: View? = null
    private var pillBar: View? = null
    var onStop: (() -> Unit)? = null
    var onOpen: (() -> Unit)? = null

    /** Sets what the bar says; null hides it. [busy] false = a final word (no spinner). Safe from any thread. */
    fun status(text: String?, busy: Boolean = true) {
        statusText = text; statusBusy = busy
        main.post { renderPill() }
    }

    private suspend fun pillHidden(hidden: Boolean) {
        if (pill == null) return
        val done = CompletableDeferred<Unit>()
        main.post { pill?.visibility = if (hidden) View.GONE else View.VISIBLE; done.complete(Unit) }
        withTimeoutOrNull(500) { done.await() }
        if (hidden) delay(60)
    }

    private fun themed(s: Context): Context = android.view.ContextThemeWrapper(s, android.R.style.Theme_DeviceDefault)

    private fun statusBarHeight(s: Context): Int {
        val id = s.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) s.resources.getDimensionPixelSize(id) else (28 * s.resources.displayMetrics.density).toInt()
    }

    private fun renderPill() {
        val s = service ?: return
        val text = statusText
        val show = text != null && frontPackage().let { it.isNotBlank() && it != s.packageName }
        if (!show) { removePill(s); return }
        runCatching {
            if (pill == null) {
                val d = s.resources.displayMetrics.density
                fun dp(v: Int) = (v * d).toInt()
                val spin = android.widget.ProgressBar(themed(s)).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#7BD3A8"))
                    layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(8) }
                }
                val label = TextView(s).apply {
                    setTextColor(Color.WHITE); textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(230)
                    setOnClickListener { onOpen?.invoke() }
                }
                val stop = TextView(s).apply {
                    this.text = "Stop"; setTextColor(Color.parseColor("#FFB4A8")); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(14), dp(2), dp(2), dp(2))
                    setOnClickListener { onStop?.invoke() }
                }
                val row = LinearLayout(s).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(spin); addView(label); addView(stop)
                }
                val bar = android.widget.ProgressBar(themed(s), null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#7BD3A8"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(5) }
                }
                val box = LinearLayout(s).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(7), dp(14), dp(6))
                    background = GradientDrawable().apply { setColor(Color.parseColor("#F21F1E1D")); cornerRadius = dp(16).toFloat() }
                    elevation = dp(6).toFloat()
                    addView(row); addView(bar)
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                // Just below the status bar: always in view, clear of the camera cut-out.
                ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = statusBarHeight(s) + dp(4) }
                (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(box, lp)
                pill = box; pillLabel = label; pillSpin = spin; pillBar = bar
            }
            pillLabel?.text = "Seyal · $text"
            val v = if (statusBusy) View.VISIBLE else View.GONE
            pillSpin?.visibility = v; pillBar?.visibility = v
        }
    }

    private fun removePill(s: SeyalAccessibilityService) {
        val p = pill ?: return
        runCatching { (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(p) }
        pill = null; pillLabel = null; pillSpin = null; pillBar = null
    }

    /**
     * A question or an approval, shown over the app Seyal is working in, so the user can answer with
     * one tap without leaving it. [options] become buttons; "Type an answer" opens Seyal.
     * Returns false when it can't be shown (screen control off, or Seyal itself is in front).
     */
    @Volatile private var card: View? = null
    private var cardKey: String? = null

    fun showCard(key: String, title: String, text: String, options: List<String>, typeLabel: String?, onPick: (String) -> Unit, onType: () -> Unit): Boolean {
        val s = service ?: return false
        if (frontPackage().let { it.isBlank() || it == s.packageName }) return false
        main.post {
            hideCardNow()
            runCatching {
                val d = s.resources.displayMetrics.density
                fun dp(v: Int) = (v * d).toInt()
                fun button(label: String, strong: Boolean, onClick: () -> Unit) = TextView(s).apply {
                    this.text = label; textSize = 15f; gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    setTextColor(if (strong) Color.parseColor("#1F1E1D") else Color.WHITE)
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    background = GradientDrawable().apply { setColor(Color.parseColor(if (strong) "#7BD3A8" else "#3A3937")); cornerRadius = dp(12).toFloat() }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                    setOnClickListener { onClick() }
                }
                val box = LinearLayout(s).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(16), dp(18), dp(16))
                    background = GradientDrawable().apply { setColor(Color.parseColor("#F81F1E1D")); cornerRadius = dp(22).toFloat() }
                    elevation = dp(10).toFloat()
                    addView(TextView(s).apply { this.text = title; setTextColor(Color.parseColor("#7BD3A8")); textSize = 12f; typeface = Typeface.DEFAULT_BOLD })
                    addView(TextView(s).apply { this.text = text; setTextColor(Color.WHITE); textSize = 15f; maxLines = 9; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, dp(4)) })
                    options.take(5).forEachIndexed { i, o -> addView(button(o, i == 0) { hideCard(key); onPick(o) }) }
                    if (typeLabel != null) addView(button(typeLabel, options.isEmpty()) { hideCard(key); onType() })
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(28); horizontalMargin = 0.04f; width = (s.resources.displayMetrics.widthPixels * 0.92f).toInt() }
                (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(box, lp)
                card = box; cardKey = key
            }
        }
        return true
    }

    fun hideCard(key: String) { main.post { if (cardKey == key) hideCardNow() } }

    private fun hideCardNow() {
        val c = card ?: return
        val s = service
        if (s != null) runCatching { (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(c) }
        card = null; cardKey = null
    }

    // ---------------------------------------------------------------- staying in the app being worked on

    val ownPackage: String get() = service?.packageName ?: "com.seyal.app"

    /** The app in front, when it is one Seyal could be working in (not Seyal, the launcher's recents or system UI). */
    fun workAppNow(): String? = frontPackage().takeIf { it.isNotBlank() && it != ownPackage && it != "com.android.systemui" }

    /**
     * Brings [pkg] back to the front as it was (after the user opened Seyal to answer a question or type).
     * True when it is in front afterwards.
     */
    suspend fun bringToFront(pkg: String): Boolean {
        val s = service ?: return false
        if (frontPackage() == pkg) return true
        val intent = s.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        // The launcher's own intent: Android resumes the app where it was instead of restarting it.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (!runCatching { s.startActivity(intent); true }.getOrDefault(false)) return false
        return waitForPackage(pkg, 4000)
    }
}
