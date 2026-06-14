package ondy.example.blockem

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class BlockerService : AccessibilityService() {

    private lateinit var overlay: CounterOverlay
    private lateinit var dataStore: SettingsDataStore

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var lastScrollTime = 0L
    private var currentForegroundApp = ""
    private var hasScrolledInApp = false
    private var isAlreadyInTtDm = false // Fixes the +1 on resume bug

    private var currentScrollCount = 0
    private var maxScrolls = 50

    // Target states
    private var globalEnabled = true
    private var igEnabled = true
    private var ttEnabled = true
    private var ytEnabled = true

    // Exclusion states
    private var ignoreFirstScroll = true
    private var ignoreIgHome = true
    private var ignoreDmGlobal = true
    private var ignoreDmIg = true
    private var ignoreDmTt = true

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

        // Live listeners
        scope.launch { dataStore.dailyScrollsFlow.collect { currentScrollCount = it } }
        scope.launch { dataStore.maxScrollsFlow.collect { maxScrolls = it } }

        scope.launch { dataStore.globalEnabledFlow.collect { globalEnabled = it } }
        scope.launch { dataStore.igEnabledFlow.collect { igEnabled = it } }
        scope.launch { dataStore.ttEnabledFlow.collect { ttEnabled = it } }
        scope.launch { dataStore.ytEnabledFlow.collect { ytEnabled = it } }

        scope.launch { dataStore.ignoreFirstScrollFlow.collect { ignoreFirstScroll = it } }
        scope.launch { dataStore.ignoreIgHomeFlow.collect { ignoreIgHome = it } }
        scope.launch { dataStore.ignoreDmGlobalFlow.collect { ignoreDmGlobal = it } }
        scope.launch { dataStore.ignoreDmIgFlow.collect { ignoreDmIg = it } }
        scope.launch { dataStore.ignoreDmTtFlow.collect { ignoreDmTt = it } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !globalEnabled) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg.isNotEmpty() && pkg != "ondy.example.blockem") {
                // If switching to a completely new app, reset everything
                if (currentForegroundApp != pkg) {
                    currentForegroundApp = pkg
                    hasScrolledInApp = false
                    isAlreadyInTtDm = false
                }
            }

            // INSTANT TT DM OPEN DETECTION
            if (pkg == "com.zhiliaoapp.musically" && ttEnabled) {
                val root = rootInActiveWindow
                val isSearch = isTikTokSearch(root)
                val inDm = !isSearch && isTikTokDm(root) // Guarantees search isn't falsely flagged as a DM

                if (inDm) {
                    val shouldIgnoreTtDm = ignoreDmGlobal && ignoreDmTt
                    // Apply penalty ONLY if it's a new entry to the DM screen
                    if (!shouldIgnoreTtDm && !isAlreadyInTtDm) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastScrollTime > 800) {
                            val wasFirst = !hasScrolledInApp
                            hasScrolledInApp = true
                            lastScrollTime = currentTime
                            triggerScrollPenalty(wasFirst)
                        }
                    }
                    isAlreadyInTtDm = true
                } else {
                    isAlreadyInTtDm = false
                }
            }
        }

        val packageName = event.packageName?.toString() ?: return
        if (packageName !in targetPackages) return

        val isAppEnabled = when (packageName) {
            "com.zhiliaoapp.musically" -> ttEnabled
            "com.instagram.android" -> igEnabled
            "com.google.android.youtube" -> ytEnabled
            else -> false
        }
        if (!isAppEnabled) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            var scrollNode = event.source
            if (scrollNode == null || !scrollNode.isScrollable) {
                scrollNode = findLargestScrollableNode(rootInActiveWindow)
            }
            if (scrollNode == null) return

            val className = scrollNode.className?.toString() ?: ""
            val isPager = className.contains("ViewPager", true) ||
                    className.contains("RecyclerView", true) ||
                    className.contains("ScrollView", true)

            if (isPager && isFullScreenVideoFeed(scrollNode)) {
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastScrollTime > 800) {
                    val root = rootInActiveWindow
                    val wasFirst = !hasScrolledInApp

                    // Respect ignores
                    if (ignoreDmGlobal) {
                        if (packageName == "com.zhiliaoapp.musically" && ignoreDmTt) {
                            // Ensure Search is tracked by ignoring the DM check if we are in Search
                            if (!isTikTokSearch(root) && isTikTokDm(root)) return
                        }
                        if (packageName == "com.instagram.android" && ignoreDmIg && isIgDmVideo(root)) return
                    }
                    if (packageName == "com.instagram.android" && ignoreIgHome && isIgHomeFeed(root)) return

                    // Apply penalty
                    hasScrolledInApp = true
                    lastScrollTime = currentTime
                    triggerScrollPenalty(wasFirst)
                }
            }
        }
    }

    private fun triggerScrollPenalty(wasFirst: Boolean) {
        if (ignoreFirstScroll && wasFirst) return

        if (currentScrollCount >= maxScrolls) {
            launchBlockActivity()
            return
        }

        scope.launch {
            val newTotal = dataStore.incrementScroll()
            if (newTotal >= maxScrolls) {
                launchBlockActivity()
            } else {
                overlay.flashCount(newTotal)
            }
        }
    }

    private fun launchBlockActivity() {
        val intent = Intent(this, BlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }

    // ==========================================
    // EXCLUSION & FEED DETECTION METHODS
    // ==========================================

    private fun isTikTokSearch(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            // If we see the search bar or search tabs, it's definitively the search page
            if (viewId.contains("search") || desc.contains("search") || text == "search") {
                return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun isTikTokDm(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""

            // Strict ID check for messages
            if (viewId.contains("im_") || viewId.contains("chat_room") || viewId.contains("msg_box") || text == "say hi") {
                return true
            }
            // Fallback for older versions: check back button, protected by isTikTokSearch() running first
            if (desc == "back" || desc == "go back") return true

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun isIgDmVideo(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val className = node.className?.toString() ?: ""
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            if (viewId.contains("direct_reply_to_author") ||
                viewId.contains("direct_visual_message") ||
                viewId.contains("message_composer")) {
                return true
            }

            if (className.contains("EditText", ignoreCase = true)) {
                if (!text.contains("comment") && !desc.contains("comment") &&
                    !text.contains("search") && !desc.contains("search")) {
                    return true
                }
            }

            if (text == "message..." || text.startsWith("reply to")) {
                return true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun isIgHomeFeed(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

            if ((desc.contains("home") || desc.contains("inicio")) && node.isSelected) {
                return true
            }
            if (desc == "your story") {
                return true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun isFullScreenVideoFeed(scrollNode: AccessibilityNodeInfo): Boolean {
        val listRect = Rect()
        scrollNode.getBoundsInScreen(listRect)
        val screenHeight = resources.displayMetrics.heightPixels
        if (listRect.height() < screenHeight * 0.6) return false
        for (i in 0 until scrollNode.childCount) {
            val child = scrollNode.getChild(i) ?: continue
            val childRect = Rect()
            child.getBoundsInScreen(childRect)
            if (childRect.height() >= listRect.height() * 0.80) return true
        }
        return false
    }

    private fun findLargestScrollableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var largest: AccessibilityNodeInfo? = null
        var maxArea = 0
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            if (node.isScrollable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val area = rect.width() * rect.height()
                if (area > maxArea) {
                    maxArea = area
                    largest = node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return largest
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        overlay.detach()
    }
}