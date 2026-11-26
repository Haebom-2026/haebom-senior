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
    /** 작업이 쓰는 검색창([GuideTask.searchFieldIds])이 화면에 있음 */
    val hasSearchField: Boolean = false,
) {
    companion object {
        /** 웹 화면(정부24 등)은 노드가 많아서 넉넉히 */
        private const val MAX_NODES = 1_500

        /** 글자만 있고 눌리지 않는 노드는 이만큼 위의 눌리는 부모를 단추로 봄 */
        private const val CLICKABLE_PARENT_DEPTH = 2

        /**
         * 화면을 읽어 작업에 필요한 단추만 모읍니다.
         * - 보이지 않는 노드라도 자식은 계속 읽습니다. 웹 화면은 크기 0 짜리 묶음 안에
         *   화면 아래 고정 단추("발급하기" 등)를 두는 일이 많아서, 부모에서 멈추면 단추를 놓칩니다.
         * - 해봄이 띄운 자막·테두리·마이크는 다른 창이라 여기에는 들어오지 않습니다.
         * [extraLabels] 는 그때그때 찾을 글자(예: 유튜브 추천 검색어).
         */
        @Suppress("DEPRECATION") // Older Android versions pool AccessibilityNodeInfo objects.
        fun read(root: AccessibilityNodeInfo, task: GuideTask, extraLabels: Set<String> = emptySet()): GuideScreen {
            val controls = mutableListOf<ScreenControl>()
            var hasPassword = false
            var hasScrollable = false
            var hasSearchField = false
            var visited = 0
            val allowlistedLabels = task.allowedLabels + extraLabels

            /** [clickableParent]: [parentDistance] 단계 위에 있는 가장 가까운 눌리는 부모의 영역 */
            fun inspect(node: AccessibilityNodeInfo, clickableParent: Rect?, parentDistance: Int) {
                if (++visited > MAX_NODES) return
                if (node.isPassword || isProtectedInput(node.inputType) ||
                    isProtectedId(node.viewIdResourceName)
                ) {
                    hasPassword = true
                    return
                }
                val visible = node.isVisibleToUser
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (visible && node.isScrollable) hasScrollable = true

                val editable = node.isEditable || node.className == "android.widget.EditText"
                if (editable) {
                    // 입력 칸의 글자는 읽지 않음. 작업이 쓰는 검색창인지만 확인
                    val id = node.viewIdResourceName?.substringAfterLast('/')
                    if (visible && id != null && id in task.searchFieldIds) hasSearchField = true
                    return
                }
                if (visible && node.isEnabled && !bounds.isEmpty) {
                    val labels = setOfNotNull(
                        node.viewIdResourceName?.substringAfterLast('/'),
                        node.text?.takeIf { it.length <= 64 }?.toString(),
                        node.contentDescription?.takeIf { it.length <= 64 }?.toString(),
                    ).map(GuideTask::normalize).filterTo(mutableSetOf(), allowlistedLabels::contains)
                    if (labels.isNotEmpty()) {
                        controls += when {
                            node.isClickable -> ScreenControl(labels, bounds, clickable = true)
                            clickableParent != null && parentDistance <= CLICKABLE_PARENT_DEPTH ->
                                ScreenControl(labels, Rect(clickableParent), clickable = true)
                            else -> ScreenControl(labels, bounds, clickable = false)
                        }
                    }
                }

                val ownClickable = visible && node.isClickable && !bounds.isEmpty
                val childParent = if (ownClickable) bounds else clickableParent
                val childDistance = if (ownClickable) 1 else parentDistance + 1
                for (index in 0 until node.childCount) {
                    if (visited >= MAX_NODES) break
                    val child = node.getChild(index) ?: continue
                    try {
                        inspect(child, childParent, childDistance)
                    } finally {
                        child.recycle()
                    }
                }
            }

            inspect(root, null, 0)
            return GuideScreen(
                if (hasPassword) emptyList() else controls, hasPassword, hasScrollable, hasSearchField,
            )
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
