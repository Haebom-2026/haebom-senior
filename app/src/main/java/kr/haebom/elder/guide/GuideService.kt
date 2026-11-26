package kr.haebom.elder.guide

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kr.haebom.elder.R

class GuideService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var refreshScheduled = false
    private val refresh = Runnable {
        refreshScheduled = false
        refreshHighlight()
    }
    private var highlight: GuideHighlightView? = null
    private var highlightedTarget: Target? = null

    override fun onServiceConnected() {
        scheduleRefresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (event.packageName != packageName) return
                if (event.windowId == highlightedTarget?.windowId) clearHighlight()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (event.packageName != null && event.packageName != packageName) {
                    clearHighlight()
                } else {
                    clearIfActiveWindowChanged()
                }
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> clearIfActiveWindowChanged()
        }
        scheduleRefresh()
    }

    override fun onInterrupt() {
        stopHighlighting()
    }

    override fun onDestroy() {
        stopHighlighting()
        super.onDestroy()
    }

    private fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        handler.postDelayed(refresh, SCREEN_UPDATE_INTERVAL_MS)
    }

    private fun refreshHighlight() {
        val target = findPracticeTarget()
        if (target == highlightedTarget) return
        clearHighlight()
        if (target == null) return

        val view = GuideHighlightView(this, target.bounds)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        getSystemService(WindowManager::class.java).addView(view, params)
        highlight = view
        highlightedTarget = target
    }

    @Suppress("DEPRECATION") // Node recycling is still needed on Android versions before API 33.
    private fun findPracticeTarget(): Target? {
        val root = rootInActiveWindow ?: return null
        try {
            if (root.packageName != packageName) return null

            for (id in listOf(R.id.practice_finish, R.id.practice_next)) {
                val nodes = root.findAccessibilityNodeInfosByViewId(resources.getResourceName(id))
                try {
                    for (node in nodes) {
                        if (!node.isVisibleToUser || !node.isEnabled) continue
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        if (!bounds.isEmpty) return Target(root.windowId, bounds)
                    }
                } finally {
                    nodes.forEach { it.recycle() }
                }
            }
            return null
        } finally {
            root.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun clearIfActiveWindowChanged() {
        val windowId = highlightedTarget?.windowId ?: return
        val root = rootInActiveWindow
        try {
            if (root?.windowId != windowId || root.packageName != packageName) {
                clearHighlight()
            }
        } finally {
            root?.recycle()
        }
    }

    private fun clearHighlight() {
        val view = highlight ?: return
        highlight = null
        highlightedTarget = null
        getSystemService(WindowManager::class.java).removeViewImmediate(view)
    }

    private fun stopHighlighting() {
        handler.removeCallbacks(refresh)
        refreshScheduled = false
        clearHighlight()
    }

    private data class Target(val windowId: Int, val bounds: Rect)

    private companion object {
        const val SCREEN_UPDATE_INTERVAL_MS = 500L
    }
}
