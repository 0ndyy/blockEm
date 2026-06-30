package ondy.example.blockem

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class BlockerService : AccessibilityService() {

    private lateinit var overlay: CounterOverlay
    private lateinit var dataStore: SettingsDataStore
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentScrollCount = 0
    private var maxScrolls = 50

    private var globalEnabled = true
    private var igEnabled = true
    private var ttEnabled = true
    private var ytEnabled = true

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

    // --- HYBRID CACHE ---
    private val lruCache = LruCache<String, Boolean>(20)
    private var lastExtractedText = ""
    private var currentForegroundApp = ""
    private var hasScrolledInApp = false
    private var lastEventTime = 0L
    private var isExecutingReturnAction = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        dataStore = SettingsDataStore(applicationContext)
        overlay = CounterOverlay(this)
        overlay.attach()

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

    // --- KILL SWITCH LOGIC ---
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_RETURN_APP") {
            val pkg = intent.getStringExtra("PACKAGE_NAME") ?: ""
            if (pkg.isNotEmpty()) executeReturnAction(pkg)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun executeReturnAction(pkg: String) {
        isExecutingReturnAction = true
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (launchIntent != null) {
            startActivity(launchIntent)
            scope.launch {
                delay(600)
                if (pkg == "com.zhiliaoapp.musically") {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(400)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                delay(1000)
                isExecutingReturnAction = false
            }
        } else {
            isExecutingReturnAction = false
        }
    }

    // --- THE HYBRID ENGINE ---
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !globalEnabled || isExecutingReturnAction) return
        val pkg = event.packageName?.toString() ?: return

        // Clear cache instantly if you switch apps
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg.isNotEmpty() && pkg != "ondy.example.blockem" && pkg != "com.android.systemui") {
                if (currentForegroundApp != pkg) {
                    currentForegroundApp = pkg
                    hasScrolledInApp = false
                    lruCache.evictAll()
                }
            }
        }

        if (pkg !in targetPackages) return
        val isAppEnabled = when (pkg) {
            "com.zhiliaoapp.musically" -> ttEnabled
            "com.instagram.android" -> igEnabled
            "com.google.android.youtube" -> ytEnabled
            else -> false
        }
        if (!isAppEnabled) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastEventTime < 400) return

            val root = rootInActiveWindow ?: return

            var scrollNode = event.source
            if (scrollNode == null || !scrollNode.isScrollable) {
                scrollNode = findLargestScrollableNode(root)
            }

            // If it's not a full screen video, abort immediately
            if (scrollNode == null || !isFullScreenVideoFeed(scrollNode)) return

            // Check Safe Zones
            val inSafeZone = when {
                ignoreDmGlobal && pkg == "com.zhiliaoapp.musically" && ignoreDmTt && isTikTokDm(root) -> true
                ignoreDmGlobal && pkg == "com.instagram.android" && ignoreDmIg && isIgDmVideo(root) -> true
                pkg == "com.instagram.android" && ignoreIgHome && isIgHomeFeed(root) -> true
                else -> false
            }
            if (inSafeZone) return

            // Instantly block if limit reached
            if (currentScrollCount >= maxScrolls) {
                launchBlockActivity(pkg)
                return
            }

            // Curbox Text Extraction & LruCache Math
            val currentText = extractTextFromNode(root).trim()
            if (currentText.length < 10) return

            if (isSubstantialTextChange(currentText, lastExtractedText)) {
                // If the cache doesn't recognize the text, it is a brand new video
                if (lruCache.get(currentText) == null) {
                    lruCache.put(currentText, true)
                    lastExtractedText = currentText
                    lastEventTime = currentTime

                    val wasFirst = !hasScrolledInApp
                    hasScrolledInApp = true

                    triggerScrollPenalty(wasFirst, pkg)
                }
            }
        }
    }

    private fun triggerScrollPenalty(wasFirst: Boolean, pkg: String) {
        if (ignoreFirstScroll && wasFirst) return

        if (currentScrollCount >= maxScrolls) {
            launchBlockActivity(pkg)
            return
        }

        scope.launch {
            val newTotal = dataStore.incrementScroll()
            if (newTotal >= maxScrolls) {
                launchBlockActivity(pkg)
            } else {
                overlay.flashCount(newTotal)
            }
        }
    }

    private fun launchBlockActivity(pkg: String) {
        val intent = Intent(this, BlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("PACKAGE_NAME", pkg)
        }
        startActivity(intent)
    }

    // --- EXCLUSION & FEED DETECTION METHODS ---

    private fun isTikTokDm(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""

            if (viewId.contains("im_") || viewId.contains("chat_room") || viewId.contains("msg_box") || text == "say hi") return true

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun isIgDmVideo(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val className = node.className?.toString() ?: ""
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            if (viewId.contains("direct_reply_to_author") || viewId.contains("direct_visual_message") || viewId.contains("message_composer")) return true
            if (className.contains("EditText", ignoreCase = true)) {
                if (!text.contains("comment") && !desc.contains("comment") && !text.contains("search") && !desc.contains("search")) return true
            }
            if (text == "message..." || text.startsWith("reply to")) return true

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun isIgHomeFeed(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

            if ((desc.contains("home") || desc.contains("inicio")) && node.isSelected) return true
            if (desc == "your story" || desc == "instagram") return true

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

    private fun extractTextFromNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        var result = ""
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (text != null) result += "$text "
        if (desc != null) result += "$desc "

        for (i in 0 until node.childCount) {
            result += extractTextFromNode(node.getChild(i))
        }
        return result
    }

    private fun isSubstantialTextChange(currentText: String, previousText: String): Boolean {
        if (currentText.isEmpty() || previousText.isEmpty()) return true

        fun countWords(text: String, wordCounts: HashMap<String, Int>) {
            val len = text.length
            var start = -1
            for (i in 0 until len) {
                if (text[i].isWhitespace()) {
                    if (start != -1) {
                        val word = text.substring(start, i)
                        wordCounts[word] = wordCounts.getOrDefault(word, 0) + 1
                        start = -1
                    }
                } else {
                    if (start == -1) start = i
                }
            }
            if (start != -1) {
                val word = text.substring(start, len)
                wordCounts[word] = wordCounts.getOrDefault(word, 0) + 1
            }
        }

        val currentWords = HashMap<String, Int>()
        val previousWords = HashMap<String, Int>()

        countWords(currentText, currentWords)
        countWords(previousText, previousWords)

        if (currentWords.isEmpty() || previousWords.isEmpty()) return true

        var intersectionSize = 0
        var totalSmaller = 0

        val smallerMap = if (currentWords.size < previousWords.size) currentWords else previousWords
        val largerMap = if (currentWords.size < previousWords.size) previousWords else currentWords

        for ((word, count) in smallerMap) {
            totalSmaller += count
            val largerCount = largerMap[word] ?: 0
            intersectionSize += minOf(count, largerCount)
        }

        if (totalSmaller == 0) return true
        val overlapRatio = intersectionSize.toFloat() / totalSmaller
        return overlapRatio < 0.85f
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        overlay.detach()
    }
}