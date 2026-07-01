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
 *         always sit. This is gated hard for safety (see below).
 *
 * Safety for the position fallback (avoid misfiring on the host app / clicking
 * through the ad):
 *  - Only fires while we believe an AD ACTIVITY is in the foreground — detected by
 *    matching the window's className against known ad-SDK activities
 *    ([AD_ACTIVITY_HINTS]). A normal game/app screen never matches, so we never
 *    blind-tap the app's own UI.
 *  - Waits [AD_SETTLE_MS] after the ad appears so we tap the (X) — not the ad body
 *    during its countdown, which would register a fraudulent click-through.
 *  - Heavily debounced ([GESTURE_DEBOUNCE_MS]).
 */
class AdCloserService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** Debounce so we don't hammer ACTION_CLICK on every content-changed event. */
    private var lastClickAt = 0L

    // --- Position-fallback state ---
    /** True while we believe an ad activity is in the foreground. */
    private var adActive = false
    /** When the current ad activity first appeared (for the settle delay). */
    private var adAppearedAt = 0L
    /** Last time we dispatched a corner tap. */
    private var lastGestureAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AdCloserService connected")
        if (DEBUG_TOASTS) toast("자동 닫기 서비스 활성화됨")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                updateAdState(event.className?.toString())
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
    private fun updateAdState(className: String?) {
        val isAd = looksLikeAdActivity(className)
        if (isAd) {
            if (!adActive) {
                adActive = true
                adAppearedAt = System.currentTimeMillis()
                Log.i(TAG, "Ad activity detected: $className")
                if (DEBUG_TOASTS) toast("광고 감지됨 — 닫기 대기")
                // Canvas ads may emit no further content events, so poll ourselves.
                handler.removeCallbacks(fallbackRunnable)
                handler.postDelayed(fallbackRunnable, AD_SETTLE_MS)
            }
        } else {
            // Foreground moved to a non-ad screen → the ad is gone.
            if (adActive) {
                adActive = false
                handler.removeCallbacks(fallbackRunnable)
            }
        }
    }

    private fun scanAndClose(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastClickAt < CLICK_DEBOUNCE_MS) return

        val target = findCloseButton(root) ?: return
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastClickAt = now
            adActive = false
            handler.removeCallbacks(fallbackRunnable)
            Log.i(TAG, "Close button clicked (node-based)")
            if (DEBUG_TOASTS) toast("닫기 버튼 클릭")
        }
    }

    /**
     * Position-based fallback loop. Runs only while [adActive]; re-tries the
     * node-based match first (the X may have just become a node), then dispatches a
     * top-right corner tap. Reschedules itself until the ad is gone.
     */
    private val fallbackRunnable = object : Runnable {
        override fun run() {
            if (!POSITION_FALLBACK_ENABLED || !adActive) return
            val now = System.currentTimeMillis()

            if (now - adAppearedAt >= AD_SETTLE_MS) {
                // Prefer a real close node if one appeared in the meantime.
                val node = rootInActiveWindow?.let { findCloseButton(it) }
                if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    lastClickAt = now
                    adActive = false
                    Log.i(TAG, "Close button clicked (node-based, from poll)")
                    if (DEBUG_TOASTS) toast("닫기 버튼 클릭")
                    return
                }
                if (now - lastGestureAt >= GESTURE_DEBOUNCE_MS) {
                    tapTopRight()
                    lastGestureAt = now
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
        Log.i(TAG, "Corner tap dispatched=$dispatched at ($x, $y)")
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

        /**
         * Foreground activity class fragments that indicate an ad is showing.
         * Matching is substring + case-insensitive. Covers the mediation networks
         * listed in docs/01-concept.md §3. Unity's AdUnitActivity is included so we
         * still detect canvas ads that run inside a game's own process (e.g. BabyBus
         * / 아기팬더의세상).
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
            "com.ironsource.mediationsdk.testSuite",
            // Vungle / Liftoff
            "com.vungle.warren.ui.VungleActivity",
            "com.vungle.ads.internal.ui.VungleActivity",
            // Meta Audience Network
            "com.facebook.ads.AudienceNetworkActivity",
            // Mintegral
            "com.mbridge.msdk.activity",
            // Pangle (TikTok / ByteDance)
            "com.bytedance.sdk.openadsdk.activity",
            // Generic hints seen across smaller SDKs
            "InterstitialActivity",
            "FullScreenActivity",
            "RewardedActivity"
        )
    }
}
