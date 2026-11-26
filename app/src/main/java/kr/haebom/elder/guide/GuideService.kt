package kr.haebom.elder.guide

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView
import kr.haebom.elder.R

class GuideService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var connected = false
    private var refreshScheduled = false
    private val refresh = Runnable {
        refreshScheduled = false
        refreshHighlight()
    }
    private var highlight: View? = null
    private var highlightedTarget: Target? = null
    private var spokenCaption: Pair<Int, Int>? = null
    private var voice: GuideVoice? = null
    private val sessionChanged: () -> Unit = {
        stopHighlighting()
        scheduleRefresh()
    }

    override fun onServiceConnected() {
        connected = true
        GuideSession.onChange = sessionChanged
        scheduleRefresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (event.packageName != packageName && event.packageName != GuideSession.active?.packageName) return
                if (event.windowId == highlightedTarget?.windowId) clearHighlight()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (event.packageName != null &&
                    event.packageName != packageName &&
                    event.packageName != GuideSession.active?.packageName
                ) {
                    stopHighlighting()
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

    override fun onUnbind(intent: Intent): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        voice?.close()
        voice = null
        super.onDestroy()
    }

    private fun scheduleRefresh() {
        if (!connected || refreshScheduled) return
        refreshScheduled = true
        handler.postDelayed(refresh, SCREEN_UPDATE_INTERVAL_MS)
    }

    private fun refreshHighlight() {
        val target = findTarget()
        if (target == highlightedTarget) return
        clearHighlight()
        if (target == null) return

        val view = createOverlay(target)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
        } catch (_: WindowManager.BadTokenException) {
            // The system can revoke the accessibility overlay token during a service restart.
            return
        }
        highlight = view
        highlightedTarget = target
        val caption = target.caption
        if (caption != null && spokenCaption != (target.windowId to caption)) {
            spokenCaption = target.windowId to caption
            if (voice == null) voice = GuideVoice(this)
            voice?.speak(getString(caption))
        }
    }

    private fun createOverlay(target: Target): View {
        val outline = GuideHighlightView(this, target.bounds)
        val caption = target.caption ?: return outline
        val density = resources.displayMetrics.density
        val padding = (12 * density).toInt()
        val bounds = target.bounds
        val anchor = if (bounds != null && bounds.bottom > resources.displayMetrics.heightPixels * 0.7f) {
            Gravity.TOP
        } else {
            Gravity.BOTTOM
        }
        return FrameLayout(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(outline, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            addView(TextView(context).apply {
                setText(caption)
                textSize = 21f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(padding, padding, padding, padding)
                background = GradientDrawable().apply {
                    setColor(Color.argb(235, 20, 27, 36))
                    cornerRadius = 10 * density
                }
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                anchor,
            ).apply {
                setMargins(padding, padding * 3, padding, padding * 4)
            })
        }
    }

    @Suppress("DEPRECATION") // Node recycling is still needed on Android versions before API 33.
    private fun findTarget(): Target? {
        val root = rootInActiveWindow ?: return null
        try {
            val screenPackage = root.packageName?.toString() ?: return null
            if (screenPackage != packageName) {
                val active = GuideSession.active ?: return null
                if (active.packageName != screenPackage) return null
                val screen = GuideScreen.read(root)
                val step = active.task.findStep(screen.controls, screen.hasPasswordField)
                return Target(root.windowId, screenPackage, step.control?.bounds, step.caption)
            }

            for (id in listOf(R.id.practice_finish, R.id.practice_next)) {
                val nodes = root.findAccessibilityNodeInfosByViewId(resources.getResourceName(id))
                try {
                    for (node in nodes) {
                        if (!node.isVisibleToUser || !node.isEnabled) continue
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        if (!bounds.isEmpty) return Target(root.windowId, screenPackage, bounds)
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
        val previous = highlightedTarget ?: return
        val root = rootInActiveWindow
        try {
            if (root?.windowId != previous.windowId || root.packageName != previous.packageName) {
                stopHighlighting()
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
        spokenCaption = null
        voice?.stop()
        clearHighlight()
    }

    private fun disconnect() {
        connected = false
        if (GuideSession.onChange === sessionChanged) GuideSession.onChange = null
        stopHighlighting()
    }

    private data class Target(
        val windowId: Int,
        val packageName: String,
        val bounds: Rect?,
        val caption: Int? = null,
    )

    private companion object {
        const val SCREEN_UPDATE_INTERVAL_MS = 500L
    }
}
