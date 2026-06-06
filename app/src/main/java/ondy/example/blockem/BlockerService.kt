package com.example.blockem

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class BlockerService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Detect when the user opens/switches apps
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            Log.d("BlockerEngine", "App Opened: $packageName")
        }

        // Detect when the user scrolls
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            Log.d("BlockerEngine", "User scrolled the screen!")
        }
    }

    override fun onInterrupt() {
        Log.d("BlockerEngine", "Service Interrupted")
    }
}