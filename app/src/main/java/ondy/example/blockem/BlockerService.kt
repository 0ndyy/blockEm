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
import android.content.Intent

data class VideoMetrics(val likes: String?, val comments: String?)

class BlockerService : AccessibilityService() {

    private lateinit var overlay: CounterOverlay
    private lateinit var dataStore: SettingsDataStore

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var lastLikeCount = ""
    private var lastCommentCount = ""
    private var lastSection = "UNKNOWN"
    private var currentSection = "UNKNOWN"

    private var pollingJob: Job? = null
    private var currentForegroundApp = ""

    private var globalEnabled = true
    private var ignoreIgHome = false
    private var ignoreDmGlobal = false
    private var ignoreDmIg = false
    private var ignoreDmTt = false
    private var maxScrolls = 50
    private var showScrollsLeft = true

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
        scope.launch { dataStore.ignoreIgHomeFlow.collect { ignoreIgHome = it } }
        scope.launch { dataStore.ignoreDmGlobalFlow.collect { ignoreDmGlobal = it } }
        scope.launch { dataStore.ignoreDmIgFlow.collect { ignoreDmIg = it } }
        scope.launch { dataStore.ignoreDmTtFlow.collect { ignoreDmTt = it } }
        scope.launch { dataStore.maxScrollsFlow.collect { maxScrolls = it } }
        scope.launch { dataStore.showScrollsLeftFlow.collect { showScrollsLeft = it } }
        scope.launch { dataStore.showDebugUiFlow.collect { showDebug -> overlay.setDebugVisible(showDebug) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !globalEnabled) return

        // 1. track what app is currently on screen
        val pkg = event.packageName?.toString()
        if (pkg != null && pkg != "ondy.example.blockem" && pkg != "com.android.systemui") {
            if (currentForegroundApp != pkg) {
                currentForegroundApp = pkg
                managePollingLoop()
            }
        }

        if (pkg in targetPackages && pollingJob?.isActive != true) {
            currentForegroundApp = pkg ?: ""
            managePollingLoop()
        }
    }

    // ==========================================
    // POLLING ENGINE(ensures tha app is running)
    // ==========================================
    private fun managePollingLoop() {
        if (currentForegroundApp in targetPackages) {
            if (pollingJob?.isActive == true) return // alreadt running

            // start scanning screen 4x/s
            pollingJob = scope.launch {
                while (isActive) {
                    val root = try { rootInActiveWindow } catch (e: Exception) { null }

                    if (root != null) {
                        analyzeScreenState(root, currentForegroundApp)
                    } else {
                        // screen off or minimized - refresh
                        lastLikeCount = ""
                        overlay.updateDebugText("Status: Waiting for Screen...")
                    }
                    delay(250)
                }
            }
        } else {
            // target app left - kill to save battery
            pollingJob?.cancel()
            lastLikeCount = ""
            overlay.updateDebugText("Status: Sleeping")
        }
    }

    private fun analyzeScreenState(root: AccessibilityNodeInfo, pkg: String) {
        val section = identifySection(root, pkg)
        val metrics = extractVideoMetrics(root, pkg)

        currentSection = section
        val likes = metrics.likes
        val comments = metrics.comments

        overlay.updateDebugText("App: $pkg\nSec: $section\nL: ${likes ?: "Nil"} | C: ${comments ?: "Nil"}")

        var isScroll = false

        // value > value || not found > value likes
        if (likes != null && likes != lastLikeCount) {
            isScroll = true
        }

        // value > value || not found > value comments fallback
        if (comments != null && comments != lastCommentCount) {
            isScroll = true
        }

        if (isScroll) {
            // --- ignores and exclusions
            val skipIgHome = (ignoreIgHome && section == "IG_HOME")
            val skipIgComments = (section == "IG_COMMENTS")
            val justLeftComments = (lastSection == "IG_COMMENTS" && section != "IG_COMMENTS")
            val skipTtDm = (ignoreDmGlobal && ignoreDmTt && section == "TT_DM_VIDEO")
            val skipIgDm = (ignoreDmGlobal && ignoreDmIg && section == "IG_DM_VIDEO")

            if (!skipIgHome && !skipIgComments && !justLeftComments && !skipTtDm && !skipIgDm) {
                triggerScrollPenalty()
            }
        }

        // saves current state for next time
        lastLikeCount = likes ?: ""
        lastCommentCount = comments ?: ""
        lastSection = section
    }

    private fun triggerScrollPenalty() {
        scope.launch {
            val newTotal = dataStore.incrementScroll()

            if (newTotal >= maxScrolls) {
                launchBlockActivity()
            } else {
                overlay.flashCount(newTotal, maxScrolls, showScrollsLeft) // NEW: Added showScrollsLeft
            }
        }
    }

    private fun launchBlockActivity() {
        val intent = Intent(this, BlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }


    // =========================================
    // HEURISTICS: APP SECTION DETECTION
    // ==========================================
    private fun identifySection(root: AccessibilityNodeInfo, pkg: String): String {
        var isIgHome = false
        var isIgReels = false
        var isIgExplore = false
        var isIgComments = false
        var isIgDmFeed = false
        var isIgDmVideo = false

        var isTtFyp = false
        var isTtFollowing = false
        var isTtInbox = false
        var isTtDmChat = false
        var isTtDmVideo = false
        var isTtProfile = false
        var isTtSearch = false

        var isYtShorts = false
        var hasOtherTabSelected = false

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while(queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""

            if (pkg.contains("instagram")) {
                // fullscreen detection
                if (desc == "reels" || text == "reels") isIgReels = true
                if (desc == "search and explore" || text == "search and explore" || desc == "explore" || text == "explore") isIgExplore = true

                // detect home tab on cold start fix
                if (desc.contains("home") || desc.contains("inicio")) isIgHome = true
                if (node.isSelected && (desc == "search and explore" || desc == "explore" || desc == "reels" || desc == "profile" || text == "profile")) {
                    hasOtherTabSelected = true
                }

                // comment section detection
                if (text.equals("comments", ignoreCase = true) || id.contains("comment_thread")) isIgComments = true

                // dm feed detection
                if (text.equals("send to chat", ignoreCase = true) || desc.equals("send to chat", ignoreCase = true)) isIgDmFeed = true

                // dm video detection !!need to add dm posts here
                if (text.startsWith("reply to ", ignoreCase = true) || desc.startsWith("reply to ", ignoreCase = true)) isIgDmVideo = true
            }
            else if (pkg.contains("musically")) {
                if (text == "for you" && node.isSelected) isTtFyp = true
                if (text == "following" && node.isSelected) isTtFollowing = true
                if (text == "inbox" && node.isSelected) isTtInbox = true
                if (text == "profile" && node.isSelected) isTtProfile = true
                //need to add searched images here!!!

                if (id.contains("chat_room") || id.contains("msg_box") || id.contains("im_message")) isTtDmChat = true
                if (text == "message..." || desc == "message...") isTtDmChat = true

                if (text.startsWith("message ") || desc.startsWith("message ")) {
                    if (!text.contains("comment") && !desc.contains("comment")) {
                        isTtDmVideo = true
                    }
                }

                if (id.contains("search") || text == "search") isTtSearch = true
            }
            else if (pkg.contains("youtube")) {
                if (desc.contains("shorts") && node.isSelected) isYtShorts = true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        // return order = priority
        return when {
            isIgComments -> "IG_COMMENTS"
            isIgDmVideo -> "IG_DM_VIDEO"
            isIgDmFeed -> "IG_DM_FEED"
            (isIgHome && !hasOtherTabSelected) -> "IG_HOME"
            isIgReels -> "IG_REELS"
            isIgExplore -> "IG_EXPLORE"

            isTtDmVideo -> "TT_DM_VIDEO"
            isTtDmChat -> "TT_DM_CHAT"
            isTtInbox -> "TT_INBOX"
            isTtSearch -> "TT_SEARCH"
            isTtProfile -> "TT_PROFILE"
            isTtFollowing -> "TT_FOLLOWING"
            isTtFyp -> "TT_FYP"

            isYtShorts -> "YT_SHORTS"

            pkg.contains("instagram") -> "IG_UNKNOWN"
            pkg.contains("musically") -> "TT_UNKNOWN"
            pkg.contains("youtube") -> "YT_UNKNOWN"
            else -> "UNKNOWN"
        }
    }

    // =========================================
    // HEURISTICS: VIDEO METRICS EXTRACTION
    // ==========================================
    private fun extractVideoMetrics(root: AccessibilityNodeInfo, pkg: String): VideoMetrics {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        val displayMetrics = android.content.res.Resources.getSystem().displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
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

                // 2. TikTok FYP and Following
                else if (pkg.contains("musically")) {
                    if (id.contains("digg_count") || id.contains("like_text")) {
                        if (text.isNotEmpty() && text.any { it.isDigit() }) foundLike = text
                    } else if (desc.contains("like", ignoreCase = true) && desc.any { it.isDigit() }) {
                        foundLike = desc
                    }
                    if (id.contains("comment_text") || id.contains("comment_count")) {
                        if (text.isNotEmpty() && text.any { it.isDigit() }) foundComment = text
                    } else if (desc.contains("comment", ignoreCase = true) && desc.any { it.isDigit() }) {
                        foundComment = desc
                    }
                }

                // 3. Instagram Reels and Home
                else if (pkg.contains("instagram")) {
                    // LIKES (primary)
                    if (!id.contains("comment")) {
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
                        } else if (desc.contains("like", ignoreCase = true) && desc.any { it.isDigit() }) {
                            val match = Regex("(?i)(\\d+[\\d,kKmM]*)\\s*likes?").find(desc)
                            if (match != null) foundLike = match.value
                        }
                    }

                    // COMMENTS  (secondary)
                    if ((id.contains("comment_count") || id.contains("comment_button")) && !id.contains("like")) {
                        if (text.isNotEmpty() && text.any { it.isDigit() }) foundComment = text
                    } else if (desc.contains("comment", ignoreCase = true) && desc.any { it.isDigit() }) {
                        val match = Regex("(?i)(\\d+[\\d,kKmM]*)\\s*comments?").find(desc)
                        if (match != null) {
                            foundComment = match.value
                        } else {
                            foundComment = desc
                        }
                    }
                } 


                if (foundLike != null) {
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.bottom > 0 && rect.top < screenHeight && rect.right > 0 && rect.left < screenWidth) {
                        val distance = Math.abs(screenCenterY - rect.centerY())
                        if (distance < minDistanceLikes) {
                            minDistanceLikes = distance
                            bestLikeCount = foundLike
                        }
                    }
                }

                if (foundComment != null) {
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.bottom > 0 && rect.top < screenHeight && rect.right > 0 && rect.left < screenWidth) {
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
