package ondy.example.blockem

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BlockerService : AccessibilityService() {

    private val targetPackages = setOf(
        "com.zhiliaoapp.musically",
        "com.instagram.android",
        "com.google.android.youtube"
    )

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var dataStore: ScrollDataStore

    // prevents rapid event firing from counting as multiple scrolls
    private var lastScrollTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        dataStore = ScrollDataStore(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        if (packageName !in targetPackages) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val currentTime = System.currentTimeMillis()

            // 600ms cooldown / scroll detection
            if (currentTime - lastScrollTime > 600) {
                lastScrollTime = currentTime

                scope.launch {
                    dataStore.incrementScroll()
                    Log.d("BlockerEngine", "scroll logged. package: $packageName")
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}