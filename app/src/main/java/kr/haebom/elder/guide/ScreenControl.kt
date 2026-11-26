package kr.haebom.elder.guide

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

internal data class ScreenControl(val labels: Set<String>, val bounds: Rect, val clickable: Boolean) {
    fun matches(values: Set<String>): Boolean = labels.any(values::contains)
}

internal data class GuideScreen(
    val controls: List<ScreenControl>,
    val hasPasswordField: Boolean,
) {
    companion object {
        private const val MAX_NODES = 350

        @Suppress("DEPRECATION") // Older Android versions pool AccessibilityNodeInfo objects.
        fun read(root: AccessibilityNodeInfo): GuideScreen {
            val controls = mutableListOf<ScreenControl>()
            var hasPassword = false
            var visited = 0

            fun inspect(node: AccessibilityNodeInfo) {
                if (++visited > MAX_NODES || !node.isVisibleToUser) return
                if (node.isPassword) {
                    hasPassword = true
                    return
                }

                // Inspect controls only. Text already entered into a field stays in its app.
                if (node.isEditable) return
                if (node.isEnabled) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    val labels = setOfNotNull(
                        node.viewIdResourceName?.substringAfterLast('/'),
                        node.text?.toString()?.takeIf { node.isClickable || it.length <= 32 },
                        node.contentDescription?.toString(),
                    ).filter { it.length <= 64 }.mapTo(mutableSetOf(), GuideTask::normalize)
                    if (labels.isNotEmpty() && !bounds.isEmpty) {
                        controls += ScreenControl(labels, bounds, node.isClickable)
                    }
                }

                for (index in 0 until node.childCount) {
                    if (visited >= MAX_NODES) break
                    val child = node.getChild(index) ?: continue
                    try {
                        inspect(child)
                    } finally {
                        child.recycle()
                    }
                }
            }

            inspect(root)
            return GuideScreen(controls, hasPassword)
        }
    }
}
