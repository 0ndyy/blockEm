package ondy.example.blockem

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class BlockerService : AccessibilityService() {

    // 2.4 Define Target Packages
    private val targetPackages = setOf(
        "com.zhiliaoapp.musically", // tt
        "com.instagram.android",    // instagram
        "com.google.android.youtube"// youTube shorts
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // 2.5 Filter listener to only trigger for target apps
        if (packageName !in targetPackages) {
            return
        }

        // Detect when the user opens/switches to a target app
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d("BlockerEngine", "Target App Active: $packageName")
        }

        // Detect when the user scrolls inside a target app
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            Log.d("BlockerEngine", "Scroll detected in: $packageName")
        }
    }

    override fun onInterrupt() {
        Log.d("BlockerEngine", "Service Interrupted")
    }
}