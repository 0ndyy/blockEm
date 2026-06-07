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

    // Live settings from the UI
    private var globalEnabled = true
    private var igEnabled = true
    private var ttEnabled = true
    private var ytEnabled = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = CounterOverlay(this)
        overlay.attach()

        dataStore = SettingsDataStore(applicationContext)

        // Listen to the switches from the UI in real-time
        scope.launch { dataStore.globalEnabledFlow.collect { globalEnabled = it } }
        scope.launch { dataStore.igEnabledFlow.collect { igEnabled = it } }
        scope.launch { dataStore.ttEnabledFlow.collect { ttEnabled = it } }
        scope.launch { dataStore.ytEnabledFlow.collect { ytEnabled = it } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !globalEnabled) return
        val packageName = event.packageName?.toString() ?: return

        // Check if the specific app is enabled in settings
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

            if (isFullScreenVideoFeed(scrollNode)) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastScrollTime > 800) {
                    lastScrollTime = currentTime

                    // Increment the persistent daily count and flash the overlay
                    scope.launch {
                        val newTotal = dataStore.incrementScroll()
                        overlay.flashCount(newTotal)
                    }
                }
            }
        }
    }

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