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

data class VideoMetrics(val likes: String?, val comments: String?)

class BlockerService : AccessibilityService() {

    private lateinit var overlay: CounterOverlay
    private lateinit var dataStore: SettingsDataStore

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var lastLikeCount = ""
    private var lastCommentCount = ""
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
        val metrics = extractVideoMetrics(root, pkg) // We are replacing the old extract function

        currentSection = section
        val likes = metrics.likes
        val comments = metrics.comments

        // Instantly update the debug UI to show both metrics
        overlay.updateDebugText("App: $pkg\nSec: $section\nL: ${likes ?: "Nil"} | C: ${comments ?: "Nil"}")

        // Count scrolls only in Short-form video feeds
        if (section == "IG_REELS" || section == "TT_FYP" || section == "YT_SHORTS") {
            var isScroll = false

            // Did the likes change? (Only triggers if we had a previous like count and a current one)
            if (likes != null && lastLikeCount.isNotEmpty() && likes != lastLikeCount) {
                isScroll = true
            }

            // Did the comments change? (The Fallback: covers us if the like button disappears)
            if (comments != null && lastCommentCount.isNotEmpty() && comments != lastCommentCount) {
                isScroll = true
            }

            if (isScroll) {
                triggerScrollPenalty()
            }

            // Always save current state for the next 250ms check
            lastLikeCount = likes ?: ""
            lastCommentCount = comments ?: ""

        } else {
            // Left the feed, clear memory
            lastLikeCount = ""
            lastCommentCount = ""
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
    // HEURISTICS: VIDEO METRICS EXTRACTION
    // ==========================================
    private fun extractVideoMetrics(root: AccessibilityNodeInfo, pkg: String): VideoMetrics {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        val screenHeight = android.content.res.Resources.getSystem().displayMetrics.heightPixels
        val screenCenterY = screenHeight / 2

        var bestLikeCount: String? = null
        var bestCommentCount: String? = null
        var minDistanceLikes = Int.MAX_VALUE
        var minDistanceComments = Int.MAX_VALUE

        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""

            var foundLike: String? = null
            var foundComment: String? = null

            try {
                // 1. YouTube Shorts
                if (pkg.contains("youtube")) {
                    if (desc.contains("like this video along with", ignoreCase = true)) {
                        foundLike = Regex("\\d+[\\d,]*").find(desc)?.value
                    } else if (id.contains("like_button")) {
                        if (text.isNotEmpty() && text.any { it.isDigit() }) foundLike = text
                    }

                    if (desc.contains("comment", ignoreCase = true) && desc.any { it.isDigit() }) {
                        foundComment = Regex("\\d+[\\d,KkMm]*").find(desc)?.value ?: desc
                    }
                }

                // 2. TikTok FYP
                else if (pkg.contains("musically")) {
                    // Likes
                    if (id.contains("digg_count") || id.contains("like_text")) {
                        if (text.isNotEmpty() && text.any { it.isDigit() }) foundLike = text
                    } else if (desc.contains("like", ignoreCase = true) && desc.any { it.isDigit() }) {
                        foundLike = desc
                    }
                    // Comments
                    if (id.contains("comment_text") || id.contains("comment_count")) {
                        if (text.isNotEmpty() && text.any { it.isDigit() }) foundComment = text
                    } else if (desc.contains("comment", ignoreCase = true) && desc.any { it.isDigit() }) {
                        foundComment = desc
                    }
                }

                // 3. Instagram Reels
                else if (pkg.contains("instagram")) {
                    // Likes
                    if (id.contains("like_count") || id.contains("like_button")) {
                        if (text.isNotEmpty() && text.any { it.isDigit() }) foundLike = text
                    } else if (desc.equals("like", ignoreCase = true) || desc.equals("liked", ignoreCase = true)) {
                        if (text.isNotEmpty() && text.any { it.isDigit() }) {
                            foundLike = text
                        } else {
                            val parent = node.parent
                            if (parent != null) {
                                for (i in 0 until parent.childCount) {
                                    val sibText = parent.getChild(i)?.text?.toString() ?: ""
                                    if (sibText.isNotEmpty() && sibText.any { it.isDigit() }) {
                                        foundLike = sibText
                                        break
                                    }
                                }
                            }
                        }
                    }
                    // Comments
                    if (id.contains("comment_count") || id.contains("comment_button")) {
                        if (text.isNotEmpty() && text.any { it.isDigit() }) foundComment = text
                    } else if (desc.contains("comment", ignoreCase = true) && desc.any { it.isDigit() }) {
                        foundComment = desc
                    }
                }

                // Distance check for Likes
                if (foundLike != null) {
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.bottom > 0 && rect.top < screenHeight) {
                        val distance = Math.abs(screenCenterY - rect.centerY())
                        if (distance < minDistanceLikes) {
                            minDistanceLikes = distance
                            bestLikeCount = foundLike
                        }
                    }
                }

                // Distance check for Comments
                if (foundComment != null) {
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.bottom > 0 && rect.top < screenHeight) {
                        val distance = Math.abs(screenCenterY - rect.centerY())
                        if (distance < minDistanceComments) {
                            minDistanceComments = distance
                            bestCommentCount = foundComment
                        }
                    }
                }

            } catch (e: Exception) {}

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return VideoMetrics(bestLikeCount, bestCommentCount)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        overlay.detach()
    }
}