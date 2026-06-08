package ondy.example.blockem

import android.accessibilityservice.AccessibilityService
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastScrollTime = 0L
    private var currentForegroundApp = ""
    private var hasScrolledInApp = false

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
        "com.zhiliaoapp.musically", // TikTok
        "com.instagram.android",    // Instagram
        "com.google.android.youtube"// YouTube
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = CounterOverlay(this)
        overlay.attach()
        dataStore = SettingsDataStore(applicationContext)

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

        // 1. App Swap Detection -> Resets "First Scroll" safely
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            // ONLY reset the "first scroll" flag when we literally switch apps.
            // This stops the bug where it ignored everything!
            if (pkg.isNotEmpty() && pkg != currentForegroundApp) {
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

        // 2. Handle the Scroll
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            var scrollNode = event.source
            if (scrollNode == null || !scrollNode.isScrollable) {
                scrollNode = findLargestScrollableNode(rootInActiveWindow)
            }
            if (scrollNode == null) return

            // DigiPaws logic: Only trigger on ViewPagers and RecyclerViews
            val className = scrollNode.className?.toString() ?: ""
            val isPager = className.contains("ViewPager", true) ||
                    className.contains("RecyclerView", true) ||
                    className.contains("ScrollView", true)

            // Flawless Geometric Check (Isolates Reels/FYP perfectly from comments/chats)
            if (isPager && isFullScreenVideoFeed(scrollNode)) {
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastScrollTime > 800) {

                    val wasFirst = !hasScrolledInApp
                    hasScrolledInApp = true
                    lastScrollTime = currentTime

                    // --- EXCLUSION CHECKS ---

                    // A. Ignore DM Videos
                    if (ignoreDmGlobal) {
                        if (packageName == "com.zhiliaoapp.musically" && ignoreDmTt) {
                            if (isTikTokDm(rootInActiveWindow)) return
                        }
                        if (packageName == "com.instagram.android" && ignoreDmIg) {
                            if (isIgDmVideo(rootInActiveWindow)) return
                        }
                    }

                    // B. Ignore IG Home Feed
                    if (packageName == "com.instagram.android" && ignoreIgHome) {
                        if (isIgHomeTabSelected(rootInActiveWindow)) return
                    }

                    // C. Ignore First Scroll
                    if (ignoreFirstScroll && wasFirst) {
                        return
                    }

                    // --- COUNT IT ---
                    scope.launch {
                        val newTotal = dataStore.incrementScroll()
                        overlay.flashCount(newTotal)
                    }
                }
            }
        }
    }

    // NATIVE CHECK: If "Send", "Share", "Like", or "Comment" buttons are MISSING,
    // we are mathematically inside a private DM video player.
    // As soon as you swipe down to the FYP, the Send button appears, and it COUNTS!
    private fun isIgDmVideo(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var hasPublicButtons = false
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""

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

    // NATIVE CHECK: TikTok DM viewers have a "Back" button, FYP does not.
    private fun isTikTokDm(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""

            if (desc == "back" || desc == "go back") {
                return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    // NATIVE CHECK: If the "Home" tab is actively selected, we are on the Home feed carousel.
    private fun isIgHomeTabSelected(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""

            if ((desc.contains("home") || desc.contains("inicio")) && node.isSelected) {
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
        // Scroll area must be at least 60% of the screen
        if (listRect.height() < screenHeight * 0.6) return false

        for (i in 0 until scrollNode.childCount) {
            val child = scrollNode.getChild(i) ?: continue
            val childRect = Rect()
            child.getBoundsInScreen(childRect)

            // If a child item takes up 80%+ of the container, it's a short-form video player
            if (childRect.height() >= listRect.height() * 0.80) {
                return true
            }
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