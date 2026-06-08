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
    private lateinit var blockOverlay: BlockOverlay
    private lateinit var dataStore: SettingsDataStore

    // BUG FIX: Changed from Dispatchers.IO to Dispatchers.Main
    // This ensures that when the limit is hit, the UI is drawn on the correct thread!
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

        blockOverlay = BlockOverlay(this) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            blockOverlay.hideWindow()
        }

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
            if (pkg.isNotEmpty() && pkg != currentForegroundApp) {
                currentForegroundApp = pkg
                hasScrolledInApp = false

                if (pkg !in targetPackages) {
                    blockOverlay.hideWindow()
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
                    val wasFirst = !hasScrolledInApp
                    hasScrolledInApp = true
                    lastScrollTime = currentTime

                    if (ignoreDmGlobal) {
                        if (packageName == "com.zhiliaoapp.musically" && ignoreDmTt && isTikTokDm(rootInActiveWindow)) return
                        if (packageName == "com.instagram.android" && ignoreDmIg && isIgDmVideo(rootInActiveWindow)) return
                    }
                    if (packageName == "com.instagram.android" && ignoreIgHome && isIgHomeTabSelected(rootInActiveWindow)) return
                    if (ignoreFirstScroll && wasFirst) return

                    // If they are already blocked but try to scroll anyway, show block again and abort
                    if (currentScrollCount >= maxScrolls) {
                        blockOverlay.showWindow()
                        return
                    }

                    // Count the scroll and check limit ON THE MAIN THREAD
                    scope.launch {
                        val newTotal = dataStore.incrementScroll()
                        if (newTotal >= maxScrolls) {
                            blockOverlay.showWindow()
                        } else {
                            overlay.flashCount(newTotal)
                        }
                    }
                }
            }
        }
    }

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

    private fun isTikTokDm(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (desc == "back" || desc == "go back") return true
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun isIgHomeTabSelected(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if ((desc.contains("home") || desc.contains("inicio")) && node.isSelected) return true
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
        blockOverlay.hideWindow()
    }
}