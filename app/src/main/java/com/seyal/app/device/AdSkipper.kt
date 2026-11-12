package com.seyal.app.device

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.seyal.app.data.Settings

/**
 * Presses "Skip ad" (and the close button of full-screen ads) as soon as it shows up, in any app:
 * YouTube, MX Player, VLC, games. Only buttons whose own words say they skip or close an ad are pressed;
 * an ordinary "Close" or "Skip" (of a tutorial, an intro) is left alone unless it sits on an ad screen.
 * On by default; Control → "Skip ads by itself" turns it off.
 */
object AdSkipper {
    /** A label that by itself means "get rid of this ad". */
    private val adButton = Regex(
        "^(skip ads?|skip ad(vert|vertisement)?s?|skip (this )?ad|close ad(vert|vertisement)?|dismiss ad|close advertisement|interstitial close button|ad close button)\\b.*",
        RegexOption.IGNORE_CASE,
    )
    /** View ids that ad SDKs and players give their skip/close buttons. */
    private val adIds = listOf("skip_ad", "skipad", "ad_skip", "close_ad", "ad_close", "interstitial_close", "adclose")
    /** Words that show the screen is an ad (then a plain "Skip" / "Close" / "✕" may be pressed). */
    private val adScreen = Regex("\\b(ad|ads|advertisement|sponsored|visit advertiser)\\b|^ad ?[·•:]|\\bad \\d+ of \\d+", RegexOption.IGNORE_CASE)
    private val plain = Regex("^(skip|skip >|skip ›|skip»|close|✕|×|x)$", RegexOption.IGNORE_CASE)

    private var lastCheck = 0L
    private var lastPkg = ""
    private var recheck = 0
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    @Volatile var skipped = 0
        private set

    /** Called on screen changes; cheap: at most every 400 ms, and never in Seyal itself or the keyboard. */
    fun onScreenChanged(s: AccessibilityService, pkg: String) {
        if (!Settings.skipAds || pkg == s.packageName || pkg.contains("inputmethod") || pkg == "com.android.systemui") return
        val now = System.currentTimeMillis()
        if (now - lastCheck < 400 && pkg == lastPkg) return
        lastCheck = now; lastPkg = pkg
        scan(s, fromEvent = true)
    }

    /**
     * Looks for a skip/close button and presses it. A video ad shows "Skip" only after a few seconds, and a
     * playing video sends no screen events, so while an ad is on screen this looks again every second.
     */
    private fun scan(s: AccessibilityService, fromEvent: Boolean) {
        val root = runCatching { s.rootInActiveWindow }.getOrNull() ?: return
        val (target, adOnScreen) = find(root)
        if (target == null) {
            if (adOnScreen) {
                if (fromEvent) recheck = 45
                if (recheck > 0) { recheck--; handler.removeCallbacksAndMessages(null); handler.postDelayed({ if (Settings.skipAds) scan(s, fromEvent = false) }, 1000) }
            }
            return
        }
        // Never press anything that pays or buys, even if an ad calls it "skip".
        val label = (target.text ?: target.contentDescription ?: "").toString()
        if (Regex("pay|buy|subscribe|purchase|premium|install", RegexOption.IGNORE_CASE).containsMatchIn(label)) return
        var n: AccessibilityNodeInfo? = target
        var hops = 0
        while (n != null && !n.isClickable && hops < 4) { n = n.parent; hops++ }
        val clicked = n?.isClickable == true && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            // No clickable element behind it (custom-drawn players): touch the button itself.
            val r = android.graphics.Rect(); target.getBoundsInScreen(r)
            if (r.isEmpty) return
            val path = android.graphics.Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
            runCatching { s.dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null) }
        }
        skipped++
        // Another ad may follow ("Ad 1 of 2"): keep looking for a little while.
        recheck = 20
        handler.removeCallbacksAndMessages(null); handler.postDelayed({ if (Settings.skipAds) scan(s, fromEvent = false) }, 1200)
    }

    internal fun isAdButton(label: String, viewId: String, adOnScreen: Boolean, adActivity: Boolean = this.adActivity): Boolean {
        val l = label.trim()
        val id = viewId.substringAfterLast('/').lowercase()
        if (l.isNotEmpty() && adButton.matches(l)) return true
        if (adIds.any { id.contains(it) } && (l.isEmpty() || plain.matches(l) || l.contains("skip", true) || l.contains("close", true))) return true
        if (l.isEmpty() || !plain.matches(l)) return false
        // On a screen that shows an ad, a bare "Skip" is the way out (never "Skip intro" and the like).
        // A bare "Close" / ✕ is pressed only inside a full-screen ad, where nothing else can be closed by mistake.
        return if (l.startsWith("skip", true)) adOnScreen || adActivity else adActivity
    }

    /** Full-screen ads run in the ad networks' own screens. */
    private val adWindows = listOf("AdActivity", ".ads.", "applovin", "unity3d.ads", "vungle", "ironsource", "mbridge", "bytedance.sdk.openadsdk", "inmobi")
    @Volatile private var adActivity = false

    /** A new screen opened: [className] tells whether it is an ad network's full-screen ad. */
    fun onWindow(className: String) { if (className.contains('.')) adActivity = adWindows.any { className.contains(it, true) } }

    private fun find(root: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo?, Boolean> {
        // One pass: note whether the screen looks like an ad, and keep candidate buttons.
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Pair<String, String>>>()
        var adOnScreen = false
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 60 || candidates.size > 40) return
            if (!n.isVisibleToUser) return
            val label = (n.text ?: n.contentDescription ?: "").toString()
            val id = n.viewIdResourceName ?: ""
            val idl = id.lowercase()
            if (!adOnScreen && ((label.length < 40 && adScreen.containsMatchIn(label)) || idl.contains("ad_progress") || idl.contains("ad_badge") || idl.contains("ad_countdown") || idl.contains("skip_ad"))) adOnScreen = true
            if (label.length < 40 && (label.contains("skip", true) || label.contains("close", true) || label in listOf("✕", "×", "X", "x") || adIds.any { idl.contains(it) })) candidates += n to (label to id)
            for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull(), d + 1)
        }
        walk(root, 0)
        return candidates.firstOrNull { (_, li) -> isAdButton(li.first, li.second, adOnScreen) }?.first to adOnScreen
    }
}
