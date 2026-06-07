package ondy.example.blockem

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

class BlockerService : AccessibilityService() {

    private lateinit var overlay: CounterOverlay
    private var scrollCount = 0
    private var lastScrollTime = 0L

    private val targetPackages = setOf(
        "com.zhiliaoapp.musically", // TikTok
        "com.instagram.android",    // Instagram
        "com.google.android.youtube"// YouTube
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Connect the transparent UI once
        overlay = CounterOverlay(this)
        overlay.attach()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        // Only track our 3 target apps
        if (packageName !in targetPackages) return

        // We only care about scrolling events
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {

            // Get the node that actually scrolled
            var scrollNode = event.source

            // Fallback if the app tries to hide the source node
            if (scrollNode == null || !scrollNode.isScrollable) {
                scrollNode = findLargestScrollableNode(rootInActiveWindow)
            }

            if (scrollNode == null) return

            // THE FLAWLESS GEOMETRIC FIX:
            // Isolate full-screen paging feeds (FYP/Reels/Shorts/DM Videos)
            // and completely ignore chats, comments, and regular home feeds.
            if (isFullScreenVideoFeed(scrollNode)) {
                val currentTime = System.currentTimeMillis()

                // 800ms debounce ensures one physical swipe = one count
                if (currentTime - lastScrollTime > 800) {
                    lastScrollTime = currentTime
                    scrollCount++

                    // Flash the visual pill
                    overlay.flashCount(scrollCount)
                }
            }
        }
    }

    /**
     * This physically measures the screen.
     * It completely ignores text, languages, and view IDs, making it flawless.
     */
    private fun isFullScreenVideoFeed(scrollNode: AccessibilityNodeInfo): Boolean {
        val listRect = Rect()
        scrollNode.getBoundsInScreen(listRect)

        // 1. The scrollable area must take up the majority of the screen (ignores tiny carousels)
        val screenHeight = resources.displayMetrics.heightPixels
        if (listRect.height() < screenHeight * 0.5) return false

        // 2. The children of this list must be FULL SCREEN.
        // In Comments or DM chats, children are small text bubbles.
        // In Reels/TikToks/DM Videos, EVERY child is exactly the size of the screen.
        for (i in 0 until scrollNode.childCount) {
            val child = scrollNode.getChild(i) ?: continue
            val childRect = Rect()
            child.getBoundsInScreen(childRect)

            // If we find even one child that takes up 85%+ of the scrollable container,
            // we are 100% inside a Short-form video player!
            if (childRect.height() >= listRect.height() * 0.85) {
                return true
            }
        }

        return false
    }

    // Failsafe to find the list if event.source is hidden
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