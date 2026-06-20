package ondy.example.blockem

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BlockerService — accessibility service that counts reel scrolls and blocks
 * the app when the daily limit is reached.
 *
 * ────────────────────────────────────────────────────────────────────────────
 *  Architecture change vs the previous version:
 *
 *  OLD  — listened to TYPE_VIEW_SCROLLED, used isFullScreenVideoFeed() +
 *          manual DM-flag state (isAlreadyInTtDm, INSTANT TT DM OPEN block).
 *          Problems: fires in search, menus, DMs, closing panels, etc.
 *
 *  NEW  — delegates to ReelDetectionEngine which uses TYPE_WINDOW_CONTENT_CHANGED
 *          + a text-comparison fingerprint (author name / like count).
 *          A scroll is counted only when the per-video fingerprint changes by
 *          > 10 % word difference AND has not been seen before (LRU cache).
 *          DM / search exclusion is handled declaratively via requiresAbsent
 *          view IDs rather than brittle flag state.
 *
 *  Bugs fixed by this rewrite:
 *    ✓ "Counter counts in random places" — anchor viewId absent outside reel player.
 *    ✓ "Closing a reel in DMs counts"   — fingerprint becomes blank → early-return.
 *    ✓ "Opening TikTok in DMs counts"   — INSTANT TT DM OPEN block removed.
 *    ✓ "First TikTok DM reel not counted (ignore off)" — text-change from "" fires.
 *    ✓ "IG home feed not reliably ignored" — reel_viewer_root absent on home feed.
 *    ✓ "isAlreadyInTtDm flag glitches"  — entire flag removed; stateless design.
 * ────────────────────────────────────────────────────────────────────────────
 */
class BlockerService : AccessibilityService() {

    private lateinit var overlay: CounterOverlay
    private lateinit var dataStore: SettingsDataStore
    private lateinit var engine: ReelDetectionEngine

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Runtime state ──────────────────────────────────────────────────────
    private var isExecutingReturnAction = false
    private var currentForegroundApp = ""

    /**
     * Tracks whether the user has produced any counted scroll in the current
     * app session.  Reset each time a different app comes to the foreground.
     * Used by the ignoreFirstScroll setting.
     */
    private var hasScrolledInApp = false

    // ── Settings — kept in sync by DataStore flows ─────────────────────────
    private var currentScrollCount = 0
    private var maxScrolls = 50

    private var globalEnabled = true
    private var igEnabled     = true
    private var ttEnabled     = true
    @Suppress("unused") // reserved for future YouTube Shorts support
    private var ytEnabled     = true

    private var ignoreFirstScroll = true
    private var ignoreIgHome      = true
    private var ignoreDmGlobal    = true
    private var ignoreDmIg        = true
    private var ignoreDmTt        = true

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()

        dataStore = SettingsDataStore(applicationContext)
        overlay   = CounterOverlay(this)
        overlay.attach()
        engine = ReelDetectionEngine(this)

        // Subscribe to all settings so changes in the UI take effect immediately.
        scope.launch { dataStore.dailyScrollsFlow.collect    { currentScrollCount = it } }
        scope.launch { dataStore.maxScrollsFlow.collect      { maxScrolls = it } }
        scope.launch { dataStore.globalEnabledFlow.collect   { globalEnabled = it } }
        scope.launch { dataStore.igEnabledFlow.collect       { igEnabled = it } }
        scope.launch { dataStore.ttEnabledFlow.collect       { ttEnabled = it } }
        scope.launch { dataStore.ytEnabledFlow.collect       { ytEnabled = it } }
        scope.launch { dataStore.ignoreFirstScrollFlow.collect   { ignoreFirstScroll = it } }
        scope.launch { dataStore.ignoreIgHomeFlow.collect    { ignoreIgHome = it } }
        scope.launch { dataStore.ignoreDmGlobalFlow.collect  { ignoreDmGlobal = it } }
        scope.launch { dataStore.ignoreDmIgFlow.collect      { ignoreDmIg = it } }
        scope.launch { dataStore.ignoreDmTtFlow.collect      { ignoreDmTt = it } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Triggered by the "Return" button in BlockActivity.
        if (intent?.action == "ACTION_RETURN_APP") {
            val pkg = intent.getStringExtra("PACKAGE_NAME") ?: ""
            if (pkg.isNotEmpty()) executeReturnAction(pkg)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onInterrupt() { /* required override — nothing to do */ }

    override fun onDestroy() {
        super.onDestroy()
        overlay.detach()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main event handler
    // ─────────────────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !globalEnabled || isExecutingReturnAction) return

        // ── Foreground-app tracking ────────────────────────────────────────
        // We only need TYPE_WINDOW_STATE_CHANGED to track which app is in
        // front.  All reel detection is done via TYPE_WINDOW_CONTENT_CHANGED
        // inside the engine, so there is NO additional logic here for DMs or
        // home feed — those are handled declaratively in buildEffectiveConfig.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName || pkg == "com.android.systemui") return

            if (currentForegroundApp != pkg) {
                // User switched apps: reset the per-app baseline.
                engine.resetForPackage(currentForegroundApp)
                currentForegroundApp = pkg
                hasScrolledInApp = false
            }

            // Instant re-block: if the user was already over the limit and
            // returns to a target app, block them immediately.
            if (pkg in ReelAppConfig.reelData && currentScrollCount >= maxScrolls) {
                launchBlockActivity(pkg)
            }
            return
        }

        // ── Reel detection — only for target apps ─────────────────────────
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in ReelAppConfig.reelData) return

        val isEnabled = when (pkg) {
            "com.zhiliaoapp.musically" -> ttEnabled
            "com.instagram.android"   -> igEnabled
            else                      -> false
        }
        if (!isEnabled) return

        // Build the effective config, applying the user's DM/home toggles.
        val config = buildEffectiveConfig(pkg) ?: return

        if (engine.checkForNewReel(event, pkg, config)) {
            val wasFirst = !hasScrolledInApp
            hasScrolledInApp = true
            triggerScrollPenalty(wasFirst, pkg)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Effective config builder
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Produces the [ReelAppData] that the engine should use for [pkg] right now,
     * taking the user's DM and home-feed ignore toggles into account.
     *
     * Strategy:
     *  • Base configs ship with DM view IDs in requiresAbsent (safe default:
     *    DM reels are not counted unless the user specifically enables it).
     *  • When a "count DM reels" setting is active, we REMOVE the corresponding
     *    IDs from requiresAbsent so DM reel players are no longer excluded.
     *  • When ignoreIgHome is ON, we ADD the home-feed story-tray IDs to
     *    requiresAbsent.  If any of those views is visible, the anchor-view
     *    check would already return false for reel_viewer_root, so this is
     *    extra defense for edge cases.
     */
    private fun buildEffectiveConfig(pkg: String): ReelAppData? {
        val base = ReelAppConfig.reelData[pkg] ?: return null
        val absent = base.requiresAbsent.toMutableList()

        when (pkg) {
            "com.zhiliaoapp.musically" -> {
                val shouldIgnoreTtDm = ignoreDmGlobal && ignoreDmTt
                if (!shouldIgnoreTtDm) {
                    // User wants TikTok DM reels counted → unblock DM contexts.
                    absent.removeAll { "im_chat_root" in it || "et_msg_box" in it }
                }
            }

            "com.instagram.android" -> {
                val shouldIgnoreIgDm = ignoreDmGlobal && ignoreDmIg
                if (!shouldIgnoreIgDm) {
                    // User wants IG DM reels counted → unblock DM contexts.
                    absent.removeAll { "message_composer" in it || "direct_text_message" in it }
                }

                if (ignoreIgHome) {
                    // The story tray only appears on the home feed tab.
                    // Adding these as requiresAbsent means: if stories are visible,
                    // we're on the home feed → don't count.
                    // reel_viewer_root already won't be found for inline home-feed
                    // videos, so this is belt-and-suspenders for edge cases where
                    // Instagram shows the reel_viewer_root from the home tab.
                    absent += listOf(
                        "com.instagram.android:id/tray_list_root",        // stories tray
                        "com.instagram.android:id/stories_container_id",  // stories container
                    )
                }
            }
        }

        // Only copy the object if we actually changed the list (avoids allocation).
        return if (absent == base.requiresAbsent) base else base.copy(requiresAbsent = absent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Counting and blocking
    // ─────────────────────────────────────────────────────────────────────────

    private fun triggerScrollPenalty(wasFirst: Boolean, pkg: String) {
        // ignoreFirstScroll: skip the very first scroll detected in each app session.
        // In practice this is the second reel you see (opening the app shows the
        // first reel as a baseline; the first SWIPE is the first "scroll").
        if (ignoreFirstScroll && wasFirst) return

        if (currentScrollCount >= maxScrolls) {
            launchBlockActivity(pkg)
            return
        }

        scope.launch {
            val newTotal = dataStore.incrementScroll()
            if (newTotal >= maxScrolls) {
                launchBlockActivity(pkg)
            } else {
                overlay.flashCount(newTotal)
            }
        }
    }

    private fun launchBlockActivity(pkg: String) {
        val intent = Intent(this, BlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("PACKAGE_NAME", pkg)
        }
        startActivity(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Return action  (BlockActivity → "Return" button)
    // ─────────────────────────────────────────────────────────────────────────

    private fun executeReturnAction(pkg: String) {
        isExecutingReturnAction = true

        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        if (launchIntent != null) {
            startActivity(launchIntent)

            scope.launch {
                delay(600) // wait for the app to render

                if (pkg == "com.zhiliaoapp.musically") {
                    // TikTok needs two back presses to fully exit the reel player
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(400)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }

                delay(1000)
                isExecutingReturnAction = false
            }
        } else {
            isExecutingReturnAction = false
        }
    }
}