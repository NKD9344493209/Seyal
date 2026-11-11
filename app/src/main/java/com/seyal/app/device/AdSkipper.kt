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
    private val adScreen = Regex("\\b(ad|ads|advertisement|sponsored|visit advertiser|install now|learn more)\\b", RegexOption.IGNORE_CASE)
    private val plain = Regex("^(skip|skip >|skip ›|skip»|close|✕|×|x)$", RegexOption.IGNORE_CASE)

    private var lastCheck = 0L
    private var lastPkg = ""
    @Volatile var skipped = 0
        private set

    /** Called on screen changes; cheap: at most every 400 ms, and never in Seyal itself or the keyboard. */
    fun onScreenChanged(s: AccessibilityService, pkg: String) {
        if (!Settings.skipAds || pkg == s.packageName || pkg.contains("inputmethod") || pkg == "com.android.systemui") return
        val now = System.currentTimeMillis()
        if (now - lastCheck < 400 && pkg == lastPkg) return
        lastCheck = now; lastPkg = pkg
        val root = runCatching { s.rootInActiveWindow }.getOrNull() ?: return
        val target = find(root) ?: return
        // Never press anything that pays or buys, even if an ad calls it "skip".
        val label = (target.text ?: target.contentDescription ?: "").toString()
        if (Regex("pay|buy|subscribe|purchase|premium", RegexOption.IGNORE_CASE).containsMatchIn(label)) return
        var n: AccessibilityNodeInfo? = target
        var hops = 0
        while (n != null && !n.isClickable && hops < 4) { n = n.parent; hops++ }
        if (n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) skipped++
    }

    internal fun isAdButton(label: String, viewId: String, adOnScreen: Boolean): Boolean {
        val l = label.trim()
        val id = viewId.substringAfterLast('/').lowercase()
        if (l.isNotEmpty() && adButton.matches(l)) return true
        if (adIds.any { id.contains(it) } && (l.isEmpty() || plain.matches(l) || l.contains("skip", true) || l.contains("close", true))) return true
        return adOnScreen && l.isNotEmpty() && plain.matches(l) && (id.contains("ad") || id.contains("skip") || id.contains("close"))
    }

    private fun find(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // One pass: note whether the screen looks like an ad, and keep candidate buttons.
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Pair<String, String>>>()
        var adOnScreen = false
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 40 || candidates.size > 40) return
            if (!n.isVisibleToUser) return
            val label = (n.text ?: n.contentDescription ?: "").toString()
            val id = n.viewIdResourceName ?: ""
            if (!adOnScreen && label.length < 40 && adScreen.containsMatchIn(label)) adOnScreen = true
            if (label.length < 40 && (label.contains("skip", true) || label.contains("close", true) || label in listOf("✕", "×", "X", "x") || adIds.any { id.lowercase().contains(it) })) candidates += n to (label to id)
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return candidates.firstOrNull { (_, li) -> isAdButton(li.first, li.second, adOnScreen) }?.first
    }
}
