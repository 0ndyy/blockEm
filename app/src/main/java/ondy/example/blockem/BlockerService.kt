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

        // REVERTED: We removed the 'pkg != currentForegroundApp' check.
        // This makes sure opening a DM sub-screen perfectly resets the "First Scroll" flag!
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg.isNotEmpty() && pkg != "ondy.example.blockem") {
                currentForegroundApp = pkg
                hasScrolledInApp = false
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
                    val wasFirst = !hasScrolledInApp
                    hasScrolledInApp = true
                    lastScrollTime = currentTime

                    if (ignoreDmGlobal) {
                        if (packageName == "com.zhiliaoapp.musically" && ignoreDmTt && isTikTokDm(rootInActiveWindow)) return
                        if (packageName == "com.instagram.android" && ignoreDmIg && isIgDmVideo(rootInActiveWindow)) return
                    }
                    if (packageName == "com.instagram.android" && ignoreIgHome && isIgHomeFeed(rootInActiveWindow)) return
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
            }
        }
    }

    private fun launchBlockActivity() {
        val intent = Intent(this, BlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }

    private fun isIgDmVideo(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var hasPublicButtons = false
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            // The flawless IG DM check you liked
            if (desc == "send" || desc == "share" || desc == "like" || desc == "comment") {
                hasPublicButtons = true
                break
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return !hasPublicButtons
    }

    private fun isTikTokDm(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            // The flawless TikTok DM check you liked
            if (desc == "back" || desc == "go back") return true
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    // POSITIVE CHECK: Detects the normal Home feed OR any Reel opened from the Home/Explore/Profile page.
    private fun isIgHomeFeed(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""

            // 1. Check if we are on the normal Home feed (Home tab is selected)
            if ((desc.contains("home") || desc.contains("inicio")) && node.isSelected) {
                return true
            }

            // 2. Check if we clicked into a Reel from the Home/Explore/Profile page.
            // These sub-reels ALWAYS have a back button. The main addictive Reels tab NEVER has a back button.
            if (desc == "back" || desc == "navigate up" || id.contains("back") || id.contains("action_bar_back")) {
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