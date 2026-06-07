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
    private var isFirstScroll = false

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

        // 1. Detect App Swapping (FIXED FIRST-SCROLL BUG)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            // ONLY reset the first scroll if we actually changed apps.
            // (Previous version reset on every swipe!)
            if (pkg != null && pkg != currentForegroundApp) {
                currentForegroundApp = pkg
                isFirstScroll = true
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

            // Ensure it's a full-screen video player (Flawless Geometric Check)
            // This naturally ignores comments and DM text chats.
            if (isFullScreenVideoFeed(scrollNode)) {
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastScrollTime > 800) {

                    // --- EXCLUSION CHECKS ---

                    // A. Ignore DM Videos (FIXED DM BUG)
                    if (ignoreDmGlobal) {
                        val checkIg = packageName == "com.instagram.android" && ignoreDmIg
                        val checkTt = packageName == "com.zhiliaoapp.musically" && ignoreDmTt

                        if (checkIg || checkTt) {
                            // If we find a "Back" button, we are in a DM video player. Ignore the scroll!
                            if (isSubScreen(rootInActiveWindow)) {
                                lastScrollTime = currentTime
                                return
                            }
                        }
                    }

                    // B. Ignore IG Home Feed
                    if (packageName == "com.instagram.android" && ignoreIgHome) {
                        if (isIgHomeFeed(rootInActiveWindow)) {
                            lastScrollTime = currentTime
                            return
                        }
                    }

                    // C. Ignore First Scroll
                    if (ignoreFirstScroll && isFirstScroll) {
                        isFirstScroll = false
                        lastScrollTime = currentTime
                        return
                    }
                    isFirstScroll = false // clear it to be safe

                    // --- COUNT IT ---
                    lastScrollTime = currentTime
                    scope.launch {
                        val newTotal = dataStore.incrementScroll()
                        overlay.flashCount(newTotal)
                    }
                }
            }
        }
    }

    // POSITIVE CHECK: DM Videos open in full screen but ALWAYS have a "Back" arrow at the top.
    // The main FYP and Reels tabs do not have back arrows.
    private fun isSubScreen(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue

            // Standard Android labels for back buttons
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (desc == "back" || desc == "navigate up" || desc == "close") {
                return true
            }

            // Also check IDs just in case
            val id = node.viewIdResourceName?.lowercase() ?: ""
            if (id.contains("back_button") || id.contains("action_bar_back")) {
                return true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        // No back button found -> We are on the main FYP/Reels!
        return false
    }

    // POSITIVE CHECK: The IG Home feed has the literal "Instagram" text logo at the top left.
    private fun isIgHomeFeed(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (desc == "instagram") return true

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    // GEOMETRIC CHECK: Flawlessly detects video feeds by measuring the screen.
    private fun isFullScreenVideoFeed(scrollNode: AccessibilityNodeInfo): Boolean {
        val listRect = Rect()
        scrollNode.getBoundsInScreen(listRect)

        val screenHeight = resources.displayMetrics.heightPixels
        if (listRect.height() < screenHeight * 0.5) return false

        for (i in 0 until scrollNode.childCount) {
            val child = scrollNode.getChild(i) ?: continue
            val childRect = Rect()
            child.getBoundsInScreen(childRect)

            if (childRect.height() >= listRect.height() * 0.85) {
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