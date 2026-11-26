package kr.haebom.elder.guide

import android.graphics.Rect
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo

internal data class ScreenControl(val labels: Set<String>, val bounds: Rect, val clickable: Boolean) {
    fun matches(values: Set<String>): Boolean = labels.any(values::contains)
}

internal data class GuideScreen(
    val controls: List<ScreenControl>,
    val hasPasswordField: Boolean,
    val hasScrollable: Boolean = false,
) {
    companion object {
        private const val MAX_NODES = 350

        @Suppress("DEPRECATION") // Older Android versions pool AccessibilityNodeInfo objects.
        fun read(root: AccessibilityNodeInfo, task: GuideTask): GuideScreen {
            val controls = mutableListOf<ScreenControl>()
            var hasPassword = false
            var hasScrollable = false
            var visited = 0
            val allowlistedLabels = task.allowedLabels

            fun inspect(node: AccessibilityNodeInfo) {
                if (++visited > MAX_NODES || !node.isVisibleToUser) return
                if (node.isPassword || isProtectedInput(node.inputType) ||
                    isProtectedId(node.viewIdResourceName)
                ) {
                    hasPassword = true
                    return
                }

                if (node.isScrollable) hasScrollable = true

                // Inspect controls only. Text already entered into a field stays in its app.
                if (node.isEditable || node.className == "android.widget.EditText") return
                if (node.isEnabled) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    val labels = setOfNotNull(
                        node.viewIdResourceName?.substringAfterLast('/'),
                        node.text?.takeIf { it.length <= 64 }?.toString(),
                        node.contentDescription?.takeIf { it.length <= 64 }?.toString(),
                    ).map(GuideTask::normalize).filterTo(mutableSetOf(), allowlistedLabels::contains)
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
            return GuideScreen(if (hasPassword) emptyList() else controls, hasPassword, hasScrollable)
        }
        internal fun isProtectedInput(type: Int): Boolean {
            val variation = type and InputType.TYPE_MASK_VARIATION
            return when (type and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }

        internal fun isProtectedId(resource: String?): Boolean {
            val id = resource?.substringAfterLast('/')?.lowercase(java.util.Locale.ROOT) ?: return false
            return listOf("password", "passcode", "otp_code", "ssn", "resident_number").any { it in id }
        }
    }
}
