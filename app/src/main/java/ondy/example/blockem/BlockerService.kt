package ondy.example.blockem

import android.accessibilityservice.AccessibilityService
import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class BlockerService : AccessibilityService() {

    private lateinit var overlay: CounterOverlay
    private lateinit var dataStore: SettingsDataStore

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // --- NEW ENGINE VARIABLES ---
    private var lastLikeCount = ""
    private var currentSection = "UNKNOWN"

    private var pollingJob: Job? = null
    private var currentForegroundApp = ""

    private var globalEnabled = true

    private val targetPackages = setOf(
        "com.zhiliaoapp.musically",
        "com.instagram.android",
        "com.google.android.youtube"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        dataStore = SettingsDataStore(applicationContext)
        overlay = CounterOverlay(this)
        overlay.attach()

        scope.launch { dataStore.globalEnabledFlow.collect { globalEnabled = it } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !globalEnabled) return

        // 1. Keep track of what app is currently on the screen
        val pkg = event.packageName?.toString()
        if (pkg != null && pkg != "ondy.example.blockem" && pkg != "com.android.systemui") {
            if (currentForegroundApp != pkg) {
                currentForegroundApp = pkg
                managePollingLoop()
            }
        }

        // Fallback: If Android drops the state change event, ensure the loop runs anyway if a target app sends any event
        if (pkg in targetPackages && pollingJob?.isActive != true) {
            currentForegroundApp = pkg ?: ""
            managePollingLoop()
        }
    }

    // ==========================================
    // THE POLLING ENGINE (Fixes Screen Off & Missed Updates)
    // ==========================================
    private fun managePollingLoop() {
        if (currentForegroundApp in targetPackages) {
            if (pollingJob?.isActive == true) return // Already running

            // Start scanning the screen 4 times a second
            pollingJob = scope.launch {
                while (isActive) {
                    val root = try { rootInActiveWindow } catch (e: Exception) { null }

                    if (root != null) {
                        analyzeScreenState(root, currentForegroundApp)
                    } else {
                        // Screen turned off, or app minimized. Reset memory so we don't get stuck!
                        lastLikeCount = ""
                        overlay.updateDebugText("Status: Waiting for Screen...")
                    }
                    delay(250) // Wait 250ms before checking again (smooth, responsive, saves battery)
                }
            }
        } else {
            // We left the target apps. Kill the loop.
            pollingJob?.cancel()
            lastLikeCount = ""
            overlay.updateDebugText("Status: Sleeping")
        }
    }

    private fun analyzeScreenState(root: AccessibilityNodeInfo, pkg: String) {
        val section = identifySection(root, pkg)
        val likes = extractOnScreenLikeCount(root, pkg)

        currentSection = section

        // Instantly update the debug UI with the exact state
        overlay.updateDebugText("App: $pkg\nSec: $section\nLikes: ${likes ?: "Not Found"}")

        // Count scrolls only in Short-form video feeds
        if (section == "IG_REELS" || section == "TT_FYP" || section == "YT_SHORTS") {
            if (likes != null) {
                if (lastLikeCount.isNotEmpty() && likes != lastLikeCount) {
                    triggerScrollPenalty()
                }
                lastLikeCount = likes
            }
        } else {
            // Left the feed, clear the memory so we don't false trigger on return
            lastLikeCount = ""
        }
    }

    private fun triggerScrollPenalty() {
        scope.launch {
            val newTotal = dataStore.incrementScroll()
            overlay.flashCount(newTotal)
        }
    }

    // ==========================================
    // HEURISTICS: APP SECTION DETECTION
    // ==========================================
    private fun identifySection(root: AccessibilityNodeInfo, pkg: String): String {
        var isIgHome = false
        var isIgReels = false
        var isIgDms = false
        var isIgExplore = false

        var isTtFyp = false
        var isTtInbox = false
        var isTtDmChat = false
        var isTtDmVideo = false
        var isTtProfile = false
        var isTtSearch = false

        var isYtShorts = false

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while(queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""

            if (pkg.contains("instagram")) {
                if ((desc == "reels" || text == "reels") && node.isSelected) isIgReels = true
                if ((desc.contains("home") || desc.contains("inicio")) && node.isSelected) isIgHome = true
                if (desc.contains("direct") || desc.contains("message") || id.contains("message_composer")) isIgDms = true
                if ((desc == "search and explore" || desc == "explore") && node.isSelected) isIgExplore = true
            }
            else if (pkg.contains("musically")) {
                // Tabs
                if (text == "for you" && node.isSelected) isTtFyp = true
                if (text == "inbox" && node.isSelected) isTtInbox = true
                if (text == "profile" && node.isSelected) isTtProfile = true

                // DM Chat
                if (id.contains("chat_room") || id.contains("msg_box") || id.contains("im_message")) isTtDmChat = true
                if (text == "message..." || desc == "message...") isTtDmChat = true

                // DM Video Check: Looks for "message [user]..." input field at the bottom of the video
                if (text.startsWith("message ") || desc.startsWith("message ")) {
                    if (!text.contains("comment") && !desc.contains("comment")) {
                        isTtDmVideo = true
                    }
                }

                // Search
                if (id.contains("search") || text == "search") isTtSearch = true
            }
            else if (pkg.contains("youtube")) {
                if (desc.contains("shorts") && node.isSelected) isYtShorts = true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return when {
            isIgReels -> "IG_REELS"
            isIgHome -> "IG_HOME"
            isIgDms -> "IG_DMS"
            isIgExplore -> "IG_EXPLORE"

            isTtDmVideo -> "TT_DM_VIDEO"
            isTtDmChat -> "TT_DM_CHAT"
            isTtInbox -> "TT_INBOX"
            isTtSearch -> "TT_SEARCH"
            isTtProfile -> "TT_PROFILE"
            isTtFyp -> "TT_FYP"

            isYtShorts -> "YT_SHORTS"

            pkg.contains("instagram") -> "IG_UNKNOWN"
            pkg.contains("musically") -> "TT_UNKNOWN"
            pkg.contains("youtube") -> "YT_UNKNOWN"
            else -> "UNKNOWN"
        }
    }

    // ==========================================
    // HEURISTICS: LIKE COUNT EXTRACTION (Fixes Prev/Next Bug)
    // ==========================================
    private fun extractOnScreenLikeCount(root: AccessibilityNodeInfo, pkg: String): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        // Get actual physical screen dimensions
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        val screenCenterY = screenHeight / 2

        var bestLikeCountText: String? = null
        var minDistanceToCenter = Int.MAX_VALUE

        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""

            var foundLikeText: String? = null

            try {
                // 1. YouTube Shorts
                if (pkg.contains("youtube")) {
                    if (desc.contains("like this video along with", ignoreCase = true)) {
                        val match = Regex("\\d+[\\d,]*").find(desc)
                        if (match != null) foundLikeText = match.value
                    } else if (id.contains("like_button")) {
                        if (text.isNotEmpty() && text.any { it.isDigit() }) foundLikeText = text
                    }
                }

                // 2. TikTok FYP
                else if (pkg.contains("musically")) {
                    if (id.contains("digg_count") || id.contains("like_text")) {
                        if (text.isNotEmpty() && text.any { it.isDigit() }) foundLikeText = text
                    } else if (desc.contains("like", ignoreCase = true) && desc.any { it.isDigit() }) {
                        foundLikeText = desc
                    }
                }

                // 3. Instagram Reels
                else if (pkg.contains("instagram")) {
                    if (id.contains("like_count") || id.contains("like_button")) {
                        if (text.isNotEmpty() && text.any { it.isDigit() }) foundLikeText = text
                    } else if (desc.equals("like", ignoreCase = true) || desc.equals("liked", ignoreCase = true)) {
                        if (text.isNotEmpty() && text.any { it.isDigit() }) {
                            foundLikeText = text
                        } else {
                            // Extract text from sibling node
                            val parent = node.parent
                            if (parent != null) {
                                for (i in 0 until parent.childCount) {
                                    val sibling = parent.getChild(i)
                                    val sibText = sibling?.text?.toString() ?: ""
                                    if (sibText.isNotEmpty() && sibText.any { it.isDigit() }) {
                                        foundLikeText = sibText
                                        break
                                    }
                                }
                            }
                        }
                    }
                }

                // --- THE SECRET SAUCE: VISIBILITY DISTANCE CHECK ---
                // If we found a string that looks like a like count, we must ensure it's ACTUALLY on screen
                if (foundLikeText != null) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)

                    // 1. Is it physically on the screen? (Eliminates hidden pre-loaded videos entirely)
                    if (rect.bottom > 0 && rect.top < screenHeight) {

                        // 2. How close is this button to the center of the screen?
                        val distance = Math.abs(screenCenterY - rect.centerY())

                        // 3. If it's the closest one we've found so far, it is the active video!
                        if (distance < minDistanceToCenter) {
                            minDistanceToCenter = distance
                            bestLikeCountText = foundLikeText
                        }
                    }
                }

            } catch (e: Exception) {}

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return bestLikeCountText
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        overlay.detach()
    }
}