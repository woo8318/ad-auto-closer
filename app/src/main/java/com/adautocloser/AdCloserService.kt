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
 *      1. NODE-BASED: match a clickable node by TEXT, contentDescription, OR
 *         view-id (e.g. .../interstitial_close). Handles native UI + WebView ads
 *         (토스 popups, AdMob/AppLovin HTML interstitials). Precise, low misfire.
 *      2. POSITION-BASED (fallback): when the ad is drawn on a game canvas the X is
 *         not an accessibility node, so no text ever matches. We dispatch taps at a
 *         few top-right candidate points where close buttons sit (portrait AND
 *         landscape). Triggered by a known ad-SDK activity OR an "AD" badge marker.
 *
 * SAFETY (see v0.2.1) — the service never fights the user for the phone:
 *  - Ignores ALL system surfaces: our own app, launcher/home, system UI, Settings
 *    ([shouldIgnorePackage]). No auto-clicking recents cards or home-screen icons.
 *  - The corner-tap fallback only fires while the SAME app that showed the ad stays
 *    foreground, waits [AD_SETTLE_MS], and is capped at [MAX_GESTURE_ATTEMPTS] taps.
 */
class AdCloserService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** Debounce so we don't hammer ACTION_CLICK on every content-changed event. */
    private var lastClickAt = 0L

    // --- Position-fallback state ---
    /** True while we believe an ad is in the foreground. */
    private var adActive = false
    /** Package that showed the current ad; the fallback only acts while it stays foreground. */
    private var adPackage: String? = null
    /** When the current ad first appeared (for the settle delay). */
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

        val root = rootInActiveWindow ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                updateAdState(pkg, event.className?.toString(), root)
                scanAndClose(root)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Some networks (e.g. Coupang) show the ad without a recognizable
                // activity name; catch those by their "AD" badge on content changes.
                if (!adActive) updateAdState(pkg, null, root)
                scanAndClose(root)
            }
        }
    }

    override fun onInterrupt() {
        // Required override; nothing to clean up.
    }

    /**
     * Decides whether an ad is in the foreground, via a known ad-SDK activity class
     * OR an "AD" badge marker in the tree. Only WINDOW_STATE_CHANGED carries a
     * className; content-changed events pass null and rely on the marker.
     */
    private fun updateAdState(pkg: String?, className: String?, root: AccessibilityNodeInfo) {
        val byClass = looksLikeAdActivity(className)
        val isAd = byClass || hasAdMarker(root)

        if (isAd) {
            if (!adActive) {
                adActive = true
                adPackage = pkg
                adAppearedAt = System.currentTimeMillis()
                gestureAttempts = 0
                Log.i(TAG, "Ad detected: pkg=$pkg class=$className byClass=$byClass")
                if (DEBUG_TOASTS) {
                    val label = className?.substringAfterLast('.') ?: "AD배지"
                    toast("광고 감지: $label — 닫기 대기")
                }
                // Canvas ads may emit no further content events, so poll ourselves.
                handler.removeCallbacks(fallbackRunnable)
                handler.postDelayed(fallbackRunnable, AD_SETTLE_MS)
            }
        } else if (className != null) {
            // A real (non-null className) screen change to a non-ad activity → ad gone.
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
     * is still foreground. Re-tries the node-based match first, then taps top-right
     * candidate points, up to [MAX_GESTURE_ATTEMPTS] times, then gives up.
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
                    tapCloseCandidate(gestureAttempts)
                    lastGestureAt = now
                    gestureAttempts++
                }
            }
            handler.postDelayed(this, FALLBACK_POLL_MS)
        }
    }

    /**
     * Dispatches a tap at one of [CORNER_CANDIDATES] — different spots near the
     * top-right corner where an X can sit. Cycling through them across attempts
     * covers portrait vs landscape and small position differences between networks.
     */
    private fun tapCloseCandidate(attempt: Int) {
        val size = screenSize()
        val (insetX, insetY) = CORNER_CANDIDATES[attempt % CORNER_CANDIDATES.size]
        val x = (size.x - dp(insetX)).coerceIn(0f, size.x.toFloat())
        val y = dp(insetY).coerceIn(0f, size.y.toFloat())

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, null, null)
        Log.i(TAG, "Corner tap #$attempt dispatched=$dispatched at ($x, $y)")
        if (DEBUG_TOASTS) toast("우상단 탭 ${attempt + 1}/${CORNER_CANDIDATES.size}")
    }

    private fun screenSize(): Point {
        val dm = resources.displayMetrics
        return Point(dm.widthPixels, dm.heightPixels)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /**
     * Depth-first search for a visible node that looks like a close/skip control
     * (by text, accessibility label, or view-id), returning the nearest clickable node.
     */
    private fun findCloseButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        if (node.isVisibleToUser &&
            (matchesClose(node.text?.toString()) ||
                matchesClose(node.contentDescription?.toString()) ||
                matchesCloseId(node.viewIdResourceName))
        ) {
            nearestClickable(node)?.let { return it }
        }

        for (i in 0 until node.childCount) {
            findCloseButton(node.getChild(i))?.let { return it }
        }
        return null
    }

    /** True if the tree contains a small "AD" / "Sponsored" badge (interstitial marker). */
    private fun hasAdMarker(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser) {
            val t = node.text?.toString()?.trim()?.lowercase()
            val d = node.contentDescription?.toString()?.trim()?.lowercase()
            if (t in AD_MARKERS || d in AD_MARKERS) return true
        }
        for (i in 0 until node.childCount) {
            if (hasAdMarker(node.getChild(i))) return true
        }
        return false
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

    /** Matches AdMob/other SDK close buttons by their view-id resource name. */
    private fun matchesCloseId(viewId: String?): Boolean {
        if (viewId.isNullOrBlank()) return false
        val id = viewId.substringAfterLast('/').lowercase()
        return CLOSE_ID_KEYWORDS.any { id.contains(it) }
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

        /** Show on-device toasts when an ad/button is detected/clicked. Handy for first tests. */
        const val DEBUG_TOASTS = true

        /** Master switch for the position-based corner-tap fallback. */
        const val POSITION_FALLBACK_ENABLED = true

        private const val CLICK_DEBOUNCE_MS = 1500L
        private const val MAX_CLICKABLE_LOOKUP = 5

        // --- Position fallback tuning ---
        /** Wait this long after an ad appears before corner-tapping (let the skip countdown finish). */
        private const val AD_SETTLE_MS = 6000L
        /** Minimum gap between corner taps. */
        private const val GESTURE_DEBOUNCE_MS = 2200L
        /** How often the fallback loop re-checks while an ad is up. */
        private const val FALLBACK_POLL_MS = 1500L
        /** Hard cap on corner taps per ad, so we never tap the app forever. */
        private const val MAX_GESTURE_ATTEMPTS = 3
        private const val TAP_DURATION_MS = 50L

        /**
         * (xInset, yInset) in dp from the top-right corner, tried in order. Covers a
         * close button at the very top (landscape ads, no status bar) and a bit lower
         * (portrait, below the status bar).
         */
        private val CORNER_CANDIDATES = listOf(
            28f to 28f,   // very top-right (landscape)
            30f to 52f,   // slightly lower (portrait, below status bar)
            48f to 24f    // a touch more inset, high up
        )

        private val EXACT_SYMBOLS = setOf("x", "✕", "×", "✖", "ⓧ", "❎")

        private val CONTAINS_KEYWORDS = listOf(
            // Korean
            "닫기", "건너뛰기", "광고 닫기", "광고닫기", "광고를 닫으려면",
            // English
            "close", "skip", "dismiss", "skip ad", "close ad", "interstitial close"
        )

        /** view-id fragments used by ad SDK close buttons. */
        private val CLOSE_ID_KEYWORDS = listOf(
            "close", "dismiss", "interstitial_close", "btn_close", "ad_close", "closebutton", "iv_close"
        )

        /** Standalone "this is an ad" badges. Exact-match only, to avoid false positives. */
        private val AD_MARKERS = setOf("ad", "ads", "sponsored", "adchoices")

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
         * Concrete ad-SDK activity classes (substring, case-insensitive). No generic
         * names, so normal screens never trip the corner-tap fallback. Unity's
         * AdUnitActivity is included so we catch canvas ads that run inside a game's
         * own process (e.g. BabyBus / 아기팬더의세상).
         */
        private val AD_ACTIVITY_HINTS = listOf(
            "com.google.android.gms.ads.AdActivity",
            "com.unity3d.services.ads.adunit.AdUnitActivity",
            "com.unity3d.ads.adunit.AdUnitActivity",
            "com.applovin.adview.AppLovinInterstitialActivity",
            "com.applovin.impl.adview.activity.AppLovinFullscreenActivity",
            "com.ironsource.sdk.controller.ControllerActivity",
            "com.vungle.warren.ui.VungleActivity",
            "com.vungle.ads.internal.ui.VungleActivity",
            "com.facebook.ads.AudienceNetworkActivity",
            "com.mbridge.msdk.activity.MBCommonActivity",
            "com.bytedance.sdk.openadsdk.activity.TTFullScreenVideoActivity",
            "com.bytedance.sdk.openadsdk.activity.TTInterstitialActivity"
        )
    }
}
