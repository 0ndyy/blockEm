package ondy.example.blockem

import android.graphics.Rect
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
//import android.view.accessibility.AccessibilityService
import android.accessibilityservice.AccessibilityService

// ─────────────────────────────────────────────────────────────────────────────
//  Data model
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Describes how to detect reels in one specific app.
 *
 * @param eventType         Bitmask of AccessibilityEvent types that trigger a check.
 * @param viewId            Resource ID of the anchor view that MUST be visible on screen
 *                          (confirms we're inside the reel player, not some other screen).
 * @param requiresPresent   Additional view IDs that must ALL be on-screen.
 * @param requiresAbsent    View IDs that must NOT be on-screen.
 *                          DM and search IDs live here by default; buildEffectiveConfig()
 *                          removes them when the user disables the relevant ignore toggle.
 * @param dynamicComparator View IDs whose combined extracted text changes every time
 *                          a new reel loads (author name, like count, etc.).
 *                          This is what drives deduplication.
 * @param cleaner           Optional transform applied to extracted text before comparison.
 */
data class ReelAppData(
    val eventType: Int,
    val viewId: String,
    val requiresPresent: List<String> = emptyList(),
    val requiresAbsent: List<String> = emptyList(),
    val dynamicComparator: List<String> = emptyList(),
    val cleaner: (String) -> String = { it }
)

// ─────────────────────────────────────────────────────────────────────────────
//  Per-app configurations
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Hardcoded reel configurations for each supported app.
 *
 * ───────────────────────────────────────────────────────────────────────────
 *  HOW TO UPDATE VIEW IDs AFTER AN APP UPDATE BREAKS DETECTION:
 *
 *   1. Enable Developer Options → USB Debugging on your phone.
 *   2. Open the target app to the reel player screen.
 *   3. From a terminal run:
 *        adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
 *      Or use Android Studio › Layout Inspector on a live process.
 *   4. Search the XML for the element you need (e.g. the author name label).
 *   5. Replace the resource-id string below.
 * ───────────────────────────────────────────────────────────────────────────
 */
object ReelAppConfig {

    val reelData: Map<String, ReelAppData> = mapOf(

        // ── TikTok ────────────────────────────────────────────────────────
        "com.zhiliaoapp.musically" to ReelAppData(
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,

            // The author-name label is the sharpest anchor: it exists in
            // For You, Search Reels, Suggested, profile reels — everywhere
            // you watch a full-screen video — and it changes per video.
            //
            // ⚠ If TikTok renames this ID, replace it here AND in the first
            //   dynamicComparator entry below.
            viewId = "com.zhiliaoapp.musically:id/author_name",

            // These IDs are present when TikTok is showing a chat (DMs)
            // or the search-results page.  Their presence means we are NOT
            // in the reel player → skip the count.
            // buildEffectiveConfig() removes them if the user sets ignoreDmTt=false.
            requiresAbsent = listOf(
                "com.zhiliaoapp.musically:id/im_chat_root",   // DM conversation root
                "com.zhiliaoapp.musically:id/et_msg_box",     // DM message input field
                "com.zhiliaoapp.musically:id/et_search_kw",  // Search bar
            ),

            // The combination of author name + like count is unique per video.
            // When you swipe, both values change completely → < 90 % word overlap
            // → the engine fires.  A single new like on the current video only
            // changes one token → > 90 % overlap → correctly ignored.
            dynamicComparator = listOf(
                "com.zhiliaoapp.musically:id/author_name",   // display name
                "com.zhiliaoapp.musically:id/btn_like",      // like count / label (desc)
                "com.zhiliaoapp.musically:id/btn_comment",   // comment count (extra signal)
            )
        ),

        // ── Instagram ─────────────────────────────────────────────────────
        "com.instagram.android" to ReelAppData(
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,

            // reel_viewer_root is the full-screen reel player.  It appears
            // in the Reels tab, Explore, and when you tap any reel from
            // anywhere.  It does NOT appear for inline home-feed videos,
            // so ignoreIgHome is partially handled automatically here.
            viewId = "com.instagram.android:id/reel_viewer_root",

            requiresAbsent = listOf(
                "com.instagram.android:id/message_composer",            // DM compose box
                "com.instagram.android:id/direct_text_message_layout",  // DM thread layout
            ),

            dynamicComparator = listOf(
                "com.instagram.android:id/reel_viewer_username",  // author handle
                "com.instagram.android:id/row_like_count",         // like count text
            )
        )
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Detection engine
// ─────────────────────────────────────────────────────────────────────────────

/**
 *
 * Root problem with raw TYPE_VIEW_SCROLLED:
 *   It fires everywhere — DMs, menus, search results, closing panels —
 *   making reliable exclusion impossible.
 *
 * This engine instead:
 *   1. Waits for TYPE_WINDOW_CONTENT_CHANGED events (fires when the reel UI
 *      actually updates its displayed content).
 *   2. Confirms the anchor view is visible on screen.
 *   3. Confirms no excluded-context views are visible (DMs, search …).
 *   4. Extracts a text fingerprint from per-video comparator views.
 *   5. Counts a new reel only when the fingerprint changes by > 10 % word
 *      difference AND the fingerprint has not been seen before this session
 *      (LRU cache prevents double-counting during rapid swipes).
 *
 * Key fixes this achieves over the old implementation:
 *   • "Closing a reel in DMs counts" → fingerprint goes blank → early-return.
 *   • "Opening TikTok in DMs counts" → no TYPE_VIEW_SCROLLED hook anymore.
 *   • "Random counts in search/home" → anchor viewId not found → early-return.
 *   • "IG home feed not ignored reliably" → reel_viewer_root absent on home → early-return.
 *   • "TikTok DM first reel" bug → text-change on first open is counted correctly.
 */
class ReelDetectionEngine(private val service: AccessibilityService) {

    /** Last seen comparator text per package. Cleared when the user leaves the app. */
    private val lastDynamicText = mutableMapOf<String, String>()

    /**
     * Per-package LRU cache of recently-seen reel fingerprints.
     * Capacity 50 keeps memory negligible while covering a typical session.
     * Intentionally NOT cleared on app-switch so returning to the same app
     * within a session doesn't re-count reels already seen.
     */
    private val seenReelsCache = mutableMapOf<String, LruCache<String, Boolean>>()

    /**
     * Per-package debounce timestamps.
     * TYPE_WINDOW_CONTENT_CHANGED can fire hundreds of times per second
     * during animations; 250 ms is enough to catch every real swipe without
     * doing expensive tree traversal on every frame.
     */
    private val lastCheckTime = mutableMapOf<String, Long>()

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns `true` exactly once per newly detected reel swipe.
     *
     * Must be called from the main thread (accessibility events are delivered there).
     *
     * @param event  The raw AccessibilityEvent that triggered this check.
     * @param pkg    The foreground app's package name.
     * @param config The effective config for this app (after exclusion toggles applied).
     */
    fun checkForNewReel(event: AccessibilityEvent, pkg: String, config: ReelAppData): Boolean {

        // ── Debounce ──────────────────────────────────────────────────────
        val now = System.currentTimeMillis()
        if (now - (lastCheckTime[pkg] ?: 0L) < 250L) return false
        lastCheckTime[pkg] = now

        // ── Event-type filter ─────────────────────────────────────────────
        if ((event.eventType and config.eventType) == 0) return false

        val root = service.rootInActiveWindow ?: return false

        // ── 1. Anchor view must be visible on screen ──────────────────────
        val anchor = findById(root, config.viewId)
        if (anchor == null || !isOnScreen(anchor)) {
            anchor?.recycle()
            return false
        }
        anchor.recycle()

        // ── 2. requiresPresent: all must be on-screen ─────────────────────
        for (id in config.requiresPresent) {
            val node = findById(root, id)
            if (node == null || !isOnScreen(node)) {
                node?.recycle()
                return false
            }
            node.recycle()
        }

        // ── 3. requiresAbsent: none may be on-screen ──────────────────────
        //    This is the core exclusion gate.  DM and search IDs live here.
        for (id in config.requiresAbsent) {
            val node = findById(root, id)
            if (node != null && isOnScreen(node)) {
                node.recycle()
                return false  // We're in a DM chat / search / other excluded context
            }
            node?.recycle()
        }

        // ── 4. Extract dynamic fingerprint text ───────────────────────────
        val sb = StringBuilder()
        for (id in config.dynamicComparator) {
            val node = findById(root, id)
            if (node != null) {
                sb.append(config.cleaner(extractText(node)))
                node.recycle()
            }
        }
        val currentText = sb.toString()

        // If comparator views weren't found (app update changed IDs?) there's
        // nothing to compare — return false rather than over-counting.
        if (currentText.isBlank()) return false

        val previousText = lastDynamicText[pkg] ?: ""

        // Always keep the baseline up-to-date so the next check has
        // a valid "previous" state to compare against.
        if (isSubstantialChange(currentText, previousText) || currentText.length > previousText.length) {
            lastDynamicText[pkg] = currentText
        }

        // ── 5. Novelty check: count only genuinely new reels ─────────────
        //    • Text must have actually changed.
        //    • Change must be substantial (> 10 % word difference).
        //    • The fingerprint must not be in the recent-seen cache.
        if (currentText != previousText && isSubstantialChange(currentText, previousText)) {
            val cache = seenReelsCache.getOrPut(pkg) { LruCache(50) }
            if (cache.get(currentText) == null) {
                cache.put(currentText, true)
                return true   // ← genuine new reel
            }
        }

        return false
    }

    fun resetForPackage(pkg: String) {
        lastDynamicText.remove(pkg)
        lastCheckTime.remove(pkg)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun findById(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.viewIdResourceName == viewId) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun isOnScreen(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val dm = service.resources.displayMetrics
        return rect.left < dm.widthPixels
                && rect.right > 0
                && rect.top < dm.heightPixels
                && rect.bottom > 0
                && rect.width() > 0
                && rect.height() > 0
    }

    /** Recursively extracts all text and content-descriptions from a subtree. */
    private fun extractText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        node.text?.let { sb.append(it) }
        node.contentDescription?.let { sb.append(it) }
        for (i in 0 until node.childCount) sb.append(extractText(node.getChild(i)))
        return sb.toString()
    }

    private fun isSubstantialChange(current: String, previous: String): Boolean {
        if (current.isEmpty() || previous.isEmpty()) return true

        fun wordCounts(text: String): HashMap<String, Int> {
            val counts = HashMap<String, Int>()
            val len = text.length
            var start = -1
            for (i in 0 until len) {
                if (text[i].isWhitespace()) {
                    if (start != -1) {
                        val w = text.substring(start, i)
                        counts[w] = (counts[w] ?: 0) + 1
                        start = -1
                    }
                } else if (start == -1) {
                    start = i
                }
            }
            if (start != -1) {
                val w = text.substring(start)
                counts[w] = (counts[w] ?: 0) + 1
            }
            return counts
        }

        val cw = wordCounts(current)
        val pw = wordCounts(previous)
        if (cw.isEmpty() || pw.isEmpty()) return true

        val smaller = if (cw.size <= pw.size) cw else pw
        val larger  = if (cw.size <= pw.size) pw  else cw

        var intersection = 0
        var totalSmaller = 0
        for ((word, count) in smaller) {
            totalSmaller += count
            intersection += minOf(count, larger[word] ?: 0)
        }

        if (totalSmaller == 0) return true
        return (intersection.toFloat() / totalSmaller) < 0.90f
    }
}
