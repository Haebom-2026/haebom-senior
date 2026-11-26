package kr.haebom.elder.guide

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 화면을 서버로 보낼 노드 목록으로 읽음 (화면 안내 AI #16, 디버그 뷰어 공통).
 * - 비밀번호·인증 입력 칸이 있으면 [blocked] = true (서버로 보내지 않음). 노드는 앱 안 로그인 안내([AuthGuide])용으로
 *   남기되, 그 칸은 [ScreenNode.secret] 으로 자리·채워짐만 담고 글자는 담지 않음
 * - 입력 칸에 적힌 글자는 [ScreenPayload] 가 보내지 않음
 * - 글자·설명이 있거나 누를 수 있는 요소만, 최대 [ScreenPayload.MAX_NODES] 개
 * - 보이지 않는 묶음 안의 보이는 요소도 읽음 (웹 화면의 아래 고정 단추)
 */
internal data class CapturedScreen(
    val packageName: String,
    val nodes: List<ScreenNode>,
    val blocked: Boolean,
) {
    val fingerprint: String by lazy { ScreenPayload.fingerprint(packageName, nodes) }

    companion object {
        private const val MAX_VISIT = 1_500

        @Suppress("DEPRECATION") // 노드 recycle 은 API 33 미만에서 필요
        fun read(root: AccessibilityNodeInfo): CapturedScreen? {
            val pkg = root.packageName?.toString() ?: return null
            val nodes = mutableListOf<ScreenNode>()
            var visited = 0
            var blocked = false

            fun visit(node: AccessibilityNodeInfo) {
                if (++visited > MAX_VISIT) return
                if (node.isPassword || GuideScreen.isProtectedInput(node.inputType) ||
                    GuideScreen.isProtectedId(node.viewIdResourceName)
                ) {
                    blocked = true
                    val rect = Rect().also(node::getBoundsInScreen)
                    if (node.isVisibleToUser && !rect.isEmpty && nodes.size < ScreenPayload.MAX_NODES) {
                        // 글자는 읽지 않음: 비어 있는지만 (비밀번호는 접근성에 ●●● 로 옴)
                        nodes += ScreenNode(
                            description = node.contentDescription?.toString(),
                            hint = node.hintText?.toString(),
                            className = ScreenPayload.shortClass(node.className?.toString()),
                            bounds = intArrayOf(rect.left, rect.top, rect.right, rect.bottom),
                            clickable = true,
                            editable = true,
                            enabled = node.isEnabled,
                            focused = node.isFocused,
                            filled = !node.isShowingHintText && !node.text.isNullOrEmpty(),
                            secret = true,
                        )
                    }
                    return
                }
                if (node.isVisibleToUser && nodes.size < ScreenPayload.MAX_NODES) {
                    val useful = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank() ||
                        node.isClickable || node.isEditable || node.isScrollable
                    val rect = Rect().also(node::getBoundsInScreen)
                    if (useful && !rect.isEmpty) {
                        nodes += ScreenNode(
                            text = node.text?.toString(),
                            description = node.contentDescription?.toString(),
                            className = ScreenPayload.shortClass(node.className?.toString()),
                            bounds = intArrayOf(rect.left, rect.top, rect.right, rect.bottom),
                            clickable = node.isClickable,
                            editable = node.isEditable,
                            scrollable = node.isScrollable,
                            checked = node.isChecked,
                            selected = node.isSelected,
                            hint = node.hintText?.toString(),
                            enabled = node.isEnabled,
                            focused = node.isFocused,
                            filled = node.isEditable && !node.isShowingHintText && !node.text.isNullOrBlank(),
                        )
                    }
                }
                for (index in 0 until node.childCount) {
                    if (visited >= MAX_VISIT) break
                    val child = node.getChild(index) ?: continue
                    try {
                        visit(child)
                    } finally {
                        child.recycle()
                    }
                }
            }

            visit(root)
            // 민감한 말이 붙은 입력 칸도 비밀 칸으로. 비밀 칸이 있으면 보내지 않음(blocked)
            val safe = SensitiveText.sanitize(nodes)
            return CapturedScreen(pkg, safe.nodes, blocked || safe.blocked)
        }
    }
}
