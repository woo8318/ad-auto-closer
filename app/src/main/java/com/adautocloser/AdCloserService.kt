package com.adautocloser

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches the active window for an interstitial-ad "close" control and taps it
 * once it becomes available.
 *
 * Design notes (see docs/01-concept.md):
 *  - We do NOT block or skip ads. We only tap the close (X) button after it appears.
 *  - Two detection layers, tried in order:
 *      1. NODE-BASED: match TEXT / contentDescription of a clickable node and
 *         perform ACTION_CLICK. Handles native UI + WebView ads (e.g. 토스 popups,
 *         AdMob/AppLovin HTML interstitials). Precise, low misfire risk.
 *      2. POSITION-BASED (fallback): when the ad is drawn on a game canvas
 *         (Unity/GL) the X is NOT an accessibility node, so no text ever matches.
 *         Here we dispatch a tap at the top-right corner where close buttons almost
 *         always sit.
 *
 * SAFETY (v0.2.1) — the service must never fight the user for control of the phone:
 *  - It ignores ALL system surfaces: our own app, the launcher/home screen,
 *    system UI (status bar / recents), and Settings ([shouldIgnorePackage]). This
 *    prevents auto-clicking "닫기/dismiss/×" on the recents cards or blind-tapping a
 *    home-screen icon (which is what re-launched this app before).
 *  - The corner-tap fallback only fires while the SAME app that showed the ad is
 *    still in the foreground, is capped at [MAX_GESTURE_ATTEMPTS] taps per ad, and
 *    waits [AD_SETTLE_MS] so it hits the (X), not the ad body during its countdown.
 *  - Only concrete ad-SDK activity classes count as "an ad" ([AD_ACTIVITY_HINTS]);
 *    no broad/generic name matching.
 */
class AdCloserService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** Debounce so we don't hammer ACTION_CLICK on every content-changed event. */
    private var lastClickAt = 0L

    // --- Position-fallback state ---
    /** True while we believe an ad activity is in the foreground. */
    private var adActive = false
    /** Package that showed the current ad; the fallback only acts while it stays foreground. */
    private var adPackage: String? = null
    /** When the current ad activity first appeared (for the settle delay). */
    private var adAppearedAt = 0L
    /** Last time we dispatched a corner tap. */
    private var lastGestureAt = 0L
    /** Corner taps dispatched for the current ad (capped so we never tap forever). */
    private var gestureAttempts = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AdCloserService connected")
        if (DEBUG_TOASTS) toast("자동 닫기 서비스 활성화됨")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()

        // Never act on our own app, the home screen, system UI, or Settings.
        // Leaving the ad's app also means the ad is gone.
        if (shouldIgnorePackage(pkg)) {
            if (adActive && pkg != adPackage) clearAdState()
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                updateAdState(pkg, event.className?.toString())
                rootInActiveWindow?.let { scanAndClose(it) }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                rootInActiveWindow?.let { scanAndClose(it) }
            }
        }
    }

    override fun onInterrupt() {
        // Required override; nothing to clean up.
    }

    /**
     * Tracks whether an ad activity is in the foreground. Only WINDOW_STATE_CHANGED
     * carries a meaningful className, so we flip state here and let content-changed
     * events reuse it.
     */
    private fun updateAdState(pkg: String?, className: String?) {
        if (looksLikeAdActivity(className)) {
            if (!adActive) {
                adActive = true
                adPackage = pkg
                adAppearedAt = System.currentTimeMillis()
                gestureAttempts = 0
                Log.i(TAG, "Ad activity detected: pkg=$pkg class=$className")
                if (DEBUG_TOASTS) toast("광고 감지됨 — 닫기 대기")
                // Canvas ads may emit no further content events, so poll ourselves.
                handler.removeCallbacks(fallbackRunnable)
                handler.postDelayed(fallbackRunnable, AD_SETTLE_MS)
            }
        } else {
            // Foreground moved to a non-ad screen → the ad is gone.
            if (adActive) clearAdState()
        }
    }

    private fun clearAdState() {
        adActive = false
        adPackage = null
        gestureAttempts = 0
        handler.removeCallbacks(fallbackRunnable)
    }

    private fun scanAndClose(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastClickAt < CLICK_DEBOUNCE_MS) return

        val target = findCloseButton(root) ?: return
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastClickAt = now
            clearAdState()
            Log.i(TAG, "Close button clicked (node-based)")
            if (DEBUG_TOASTS) toast("닫기 버튼 클릭")
        }
    }

    /**
     * Position-based fallback loop. Runs only while [adActive] AND the ad's own app
     * is still foreground. Re-tries the node-based match first, then dispatches a
     * top-right corner tap, up to [MAX_GESTURE_ATTEMPTS] times, then gives up.
     */
    private val fallbackRunnable = object : Runnable {
        override fun run() {
            if (!POSITION_FALLBACK_ENABLED || !adActive) return
            val now = System.currentTimeMillis()

            val root = rootInActiveWindow
            val curPkg = root?.packageName?.toString()
            // Bail out the moment we're no longer on the ad's app (home, another app…).
            if (root == null || curPkg != adPackage || shouldIgnorePackage(curPkg)) {
                clearAdState()
                return
            }

            if (now - adAppearedAt >= AD_SETTLE_MS) {
                // Prefer a real close node if one appeared in the meantime.
                val node = findCloseButton(root)
                if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    lastClickAt = now
                    Log.i(TAG, "Close button clicked (node-based, from poll)")
                    if (DEBUG_TOASTS) toast("닫기 버튼 클릭")
                    clearAdState()
                    return
                }
                if (gestureAttempts >= MAX_GESTURE_ATTEMPTS) {
                    Log.i(TAG, "Corner-tap attempts exhausted; giving up on this ad")
                    clearAdState()
                    return
                }
                if (now - lastGestureAt >= GESTURE_DEBOUNCE_MS) {
                    tapTopRight()
                    lastGestureAt = now
                    gestureAttempts++
                }
            }
            handler.postDelayed(this, FALLBACK_POLL_MS)
        }
    }

    /** Dispatches a single tap near the top-right corner (typical X position). */
    private fun tapTopRight() {
        val size = screenSize()
        val x = (size.x - dp(CORNER_INSET_X_DP)).coerceIn(0f, size.x.toFloat())
        val y = dp(CORNER_INSET_Y_DP).coerceIn(0f, size.y.toFloat())

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, null, null)
        Log.i(TAG, "Corner tap #$gestureAttempts dispatched=$dispatched at ($x, $y)")
        if (DEBUG_TOASTS) toast("우상단 탭 (캔버스 광고)")
    }

    private fun screenSize(): Point {
        val dm = resources.displayMetrics
        return Point(dm.widthPixels, dm.heightPixels)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /**
     * Depth-first search for a visible node whose text or accessibility label
     * looks like a close/skip control, returning the nearest clickable node.
     */
    private fun findCloseButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        if (node.isVisibleToUser &&
            (matchesClose(node.text?.toString()) || matchesClose(node.contentDescription?.toString()))
        ) {
            nearestClickable(node)?.let { return it }
        }

        for (i in 0 until node.childCount) {
            findCloseButton(node.getChild(i))?.let { return it }
        }
        return null
    }

    /** Walks up at most a few levels to find a clickable ancestor (icons are often non-clickable themselves). */
    private fun nearestClickable(start: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = start
        var depth = 0
        while (cur != null && depth < MAX_CLICKABLE_LOOKUP) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun matchesClose(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val t = raw.trim().lowercase()

        // Short symbols must match exactly, otherwise "x" matches words like "next"/"box".
        if (t in EXACT_SYMBOLS) return true

        return CONTAINS_KEYWORDS.any { t == it || t.contains(it) }
    }

    /** True if the window class looks like a known interstitial/rewarded ad activity. */
    private fun looksLikeAdActivity(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        return AD_ACTIVITY_HINTS.any { className.contains(it, ignoreCase = true) }
    }

    /** Surfaces we must never touch: our own app, launcher/home, system UI, Settings. */
    private fun shouldIgnorePackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        if (pkg == packageName) return true
        if (pkg in IGNORED_PACKAGES) return true
        // Any launcher (One UI: com.sec.android.app.launcher, Pixel: ...nexuslauncher, etc.)
        if (pkg.contains("launcher", ignoreCase = true)) return true
        return false
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val TAG = "AdCloser"

        /** Show on-device toasts when a button is detected/clicked. Handy for first tests. */
        const val DEBUG_TOASTS = true

        /** Master switch for the position-based corner-tap fallback. */
        const val POSITION_FALLBACK_ENABLED = true

        private const val CLICK_DEBOUNCE_MS = 1500L
        private const val MAX_CLICKABLE_LOOKUP = 5

        // --- Position fallback tuning ---
        /** Wait this long after an ad appears before corner-tapping (let the skip countdown finish). */
        private const val AD_SETTLE_MS = 6000L
        /** Minimum gap between corner taps. */
        private const val GESTURE_DEBOUNCE_MS = 2500L
        /** How often the fallback loop re-checks while an ad is up. */
        private const val FALLBACK_POLL_MS = 1500L
        /** Hard cap on corner taps per ad, so we never tap the app forever. */
        private const val MAX_GESTURE_ATTEMPTS = 3
        /** Tap point insets from the top-right corner, in dp. */
        private const val CORNER_INSET_X_DP = 30f
        private const val CORNER_INSET_Y_DP = 52f
        private const val TAP_DURATION_MS = 50L

        private val EXACT_SYMBOLS = setOf("x", "✕", "×", "✖", "ⓧ", "❎")

        private val CONTAINS_KEYWORDS = listOf(
            // Korean
            "닫기", "건너뛰기", "광고 닫기", "광고닫기",
            // English
            "close", "skip", "dismiss", "skip ad", "close ad"
        )

        /** Packages the service must never act on (system surfaces). */
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",       // status bar, recents, nav bar
            "com.android.settings",       // Settings (incl. the Accessibility page)
            "com.samsung.android.settings",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "android"                     // system dialogs
        )

        /**
         * Foreground activity class fragments that indicate an ad is showing.
         * Matching is substring + case-insensitive. Concrete ad-SDK activity classes
         * only — no generic names — so normal app screens never trip the corner-tap
         * fallback. Unity's AdUnitActivity is included so we still detect canvas ads
         * that run inside a game's own process (e.g. BabyBus / 아기팬더의세상).
         */
        private val AD_ACTIVITY_HINTS = listOf(
            // Google AdMob / Google Mobile Ads
            "com.google.android.gms.ads.AdActivity",
            // Unity Ads (also used via AdMob mediation inside games)
            "com.unity3d.services.ads.adunit.AdUnitActivity",
            "com.unity3d.ads.adunit.AdUnitActivity",
            // AppLovin / MAX
            "com.applovin.adview.AppLovinInterstitialActivity",
            "com.applovin.impl.adview.activity.AppLovinFullscreenActivity",
            // ironSource / LevelPlay
            "com.ironsource.sdk.controller.ControllerActivity",
            // Vungle / Liftoff
            "com.vungle.warren.ui.VungleActivity",
            "com.vungle.ads.internal.ui.VungleActivity",
            // Meta Audience Network
            "com.facebook.ads.AudienceNetworkActivity",
            // Mintegral
            "com.mbridge.msdk.activity.MBCommonActivity",
            // Pangle (TikTok / ByteDance)
            "com.bytedance.sdk.openadsdk.activity.TTFullScreenVideoActivity",
            "com.bytedance.sdk.openadsdk.activity.TTInterstitialActivity"
        )
    }
}
