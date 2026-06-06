package ondy.example.blockem

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class BlockerService : AccessibilityService() {

    private lateinit var overlay: CounterOverlay
    private var scrollCount = 0
    private var lastScrollTime = 0L

    private val targetPackages = setOf(
        "com.zhiliaoapp.musically",
        "com.instagram.android",
        "com.google.android.youtube"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = CounterOverlay(this)
        overlay.attach()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        if (packageName !in targetPackages) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val className = event.className?.toString() ?: ""

            // Limit to actual lists/feeds
            if (className.contains("RecyclerView") || className.contains("ViewPager") || className.contains("ScrollView")) {

                val currentTime = System.currentTimeMillis()

                if (currentTime - lastScrollTime > 800) {

                    // Lightning-fast native check: Are we actually in the short-form feed?
                    if (isStrictlyShortsFeed(rootInActiveWindow, packageName)) {
                        lastScrollTime = currentTime

                        scrollCount++
                        overlay.flashCount(scrollCount)
                    }
                }
            }
        }
    }

    // Uses native Android UI queries (Zero CPU Lag) to check the Bottom Navigation Tabs
    private fun isStrictlyShortsFeed(root: AccessibilityNodeInfo?, pkg: String): Boolean {
        if (root == null) return false

        try {
            when (pkg) {
                "com.instagram.android" -> {
                    // Find the "Reels" tab. If it exists AND is actively selected, we are in Reels.
                    val reelsNodes = root.findAccessibilityNodeInfosByText("Reels")
                    for (node in reelsNodes) {
                        if (node.isSelected) return true
                    }
                }
                "com.google.android.youtube" -> {
                    // Find the "Shorts" tab.
                    val shortsNodes = root.findAccessibilityNodeInfosByText("Shorts")
                    for (node in shortsNodes) {
                        if (node.isSelected) return true
                    }
                }
                "com.zhiliaoapp.musically" -> {
                    // TikTok FYP and Following feeds are under the "Home" tab
                    val homeNodes = root.findAccessibilityNodeInfosByText("Home")
                    for (node in homeNodes) {
                        if (node.isSelected) return true
                    }
                    // TikTok Friends feed is under the "Friends" tab
                    val friendsNodes = root.findAccessibilityNodeInfosByText("Friends")
                    for (node in friendsNodes) {
                        if (node.isSelected) return true
                    }
                }
            }
        } catch (e: Exception) {
            // Failsafe: if the view tree is busy, just ignore this scroll
        }

        return false // If the specific tab isn't selected, assume we are in DMs, Home Feed, etc.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        overlay.detach()
    }
} 