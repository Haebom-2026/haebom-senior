package kr.haebom.elder.guide

import org.json.JSONObject

/**
 * 어르신이 실제로 한 행동 하나 (서버 S5 `lastAction`, R6 앱 연동 안내 2장 ⑤).
 * - [stepId]: 그 화면에서 받은 안내의 id. **그 안내를 따랐을 때만** (안내 없이 한 행동이면 null)
 * - [seq]: 명령 안 행동 순번 (0부터 +1, 보내지 못하고 덮인 행동은 번호가 건너뜀 → 서버가 그 구간을 학습하지 않음)
 * - [action]: tap · type · swipe_up/down/left/right (손가락 방향) · back · home · other
 * - [text]: 누른 요소의 글자 (t, 없으면 d). type 이면 넣은 값은 보내지 않음
 */
internal data class LastAction(val stepId: String?, val seq: Int, val action: String, val text: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        stepId?.let { put("stepId", it) }
        put("seq", seq)
        put("action", action)
        put("text", text.take(100))
    }

    companion object {
        const val TAP = "tap"
        const val TYPE = "type"
        const val BACK = "back"
        const val HOME = "home"
        const val OTHER = "other"

        /** 손가락이 움직인 방향 ("up" → swipe_up) */
        fun swipe(dir: String): String = "swipe_$dir"
    }
}

/**
 * 명령 하나([commandId])의 안내·행동 기록. 안드로이드 없이 단위 테스트.
 * guide 응답의 `stepId` 를 화면에 보인 안내([Shown])로 기억하고, 접근성 이벤트로 잡은 행동이 그 안내를 따랐는지 봄.
 * 다음 guide 요청(또는 끝 신호)에 [takePending] 으로 한 번 실어 보냄.
 */
internal class CommandSteps(val commandId: Long) {
    /**
     * 지금 화면에 보이는 서버 안내.
     * [box]: 짚은 요소 자리 (left, top, right, bottom), [label]: 짚은 요소의 글자,
     * [dir]: 밀기 방향(손가락), [nav]: home/back, [fingerprint]: 안내한 화면
     */
    data class Shown(
        val stepId: String,
        val type: String,
        val box: IntArray? = null,
        val label: String = "",
        val dir: String? = null,
        val nav: String? = null,
        val fingerprint: String = "",
    ) {
        override fun equals(other: Any?): Boolean =
            other is Shown && stepId == other.stepId && type == other.type && box.contentEquals(other.box) &&
                label == other.label && dir == other.dir && nav == other.nav && fingerprint == other.fingerprint

        override fun hashCode(): Int = stepId.hashCode() * 31 + fingerprint.hashCode()
    }

    var shown: Shown? = null
        private set
    private var nextSeq = 0
    private var pending: LastAction? = null

    /** 끝 신호 결과: done (다 됨) — 마지막 화면과 함께 */
    var doneScreen: String? = null
        private set
    /** 서버가 멈춤 단계(status stopped)라고 한 화면. 그 뒤 안내가 이어지면 지움 */
    var safeStopScreen: String? = null
        private set
    var ended = false
        private set

    fun show(next: Shown) {
        shown = next
    }

    /** 다른 화면으로 넘어갔으면 앞 화면 안내는 지움 (새 화면에서 한 행동을 앞 안내를 따른 것으로 보지 않게) */
    fun forgetShownUnless(fingerprint: String) {
        if (shown?.fingerprint != fingerprint) shown = null
    }

    /** 행동 하나를 기록. [box] 는 누른·입력한 요소의 자리 (알 수 없으면 null) */
    fun record(action: String, text: String, box: IntArray? = null): LastAction {
        val current = shown
        val followed = current != null && followed(current, action, box)
        // 안내를 따랐으면 서버 지도와 같은 글자가 되게 짚은 요소의 글자로 (누른 틀이 글자의 부모일 때가 많음)
        val label = when {
            action == LastAction.TYPE -> ""
            followed && current != null && current.label.isNotEmpty() && action == LastAction.TAP -> current.label
            else -> text
        }
        val recorded = LastAction(if (followed) current?.stepId else null, nextSeq++, action, label.trim())
        pending = recorded
        // 한 안내는 한 번만 따름 (같은 화면에서 또 누르면 안내 없이 한 행동)
        if (followed) shown = null
        return recorded
    }

    /** 다음 guide 요청에 실을 행동 (한 번 꺼내면 비움) */
    fun takePending(): LastAction? = pending.also { pending = null }

    fun markDone(finalScreen: String?) {
        if (doneScreen == null) doneScreen = finalScreen ?: ""
    }

    fun markSafeStop(screen: String?) {
        safeStopScreen = screen
    }

    fun clearSafeStop() {
        safeStopScreen = null
    }

    /**
     * 끝 신호 결과와 마지막 화면. [userStopped]: 어르신이 그만둠(그만·새 명령 등).
     * 다 됐으면 done, 서버가 멈춤 단계라 한 화면에서 끝났으면 stopped_safe, 아니면 stopped_user
     */
    fun finish(): Pair<String, String?>? {
        if (ended) return null
        ended = true
        doneScreen?.let { return RESULT_DONE to it.ifEmpty { null } }
        safeStopScreen?.let { return RESULT_STOPPED_SAFE to it }
        return RESULT_STOPPED_USER to null
    }

    companion object {
        const val RESULT_DONE = "done"
        const val RESULT_STOPPED_SAFE = "stopped_safe"
        const val RESULT_STOPPED_USER = "stopped_user"

        /** 행동이 보인 안내를 따랐는지: 짚은 요소를 누름·그 칸에 입력, 같은 방향으로 밂, 홈·뒤로 */
        fun followed(shown: Shown, action: String, box: IntArray?): Boolean = when (shown.type) {
            AiReply.HIGHLIGHT, AiReply.SUGGEST -> {
                val target = shown.box
                (action == LastAction.TAP || action == LastAction.TYPE) && target != null && box != null && overlaps(target, box)
            }
            AiReply.SWIPE -> shown.dir != null && action == LastAction.swipe(shown.dir)
            AiReply.SAY -> (shown.nav == "back" && action == LastAction.BACK) || (shown.nav == "home" && action == LastAction.HOME)
            else -> false
        }

        /** 한쪽 가운데가 다른 쪽 안에 있음 (누른 틀이 짚은 글자를 감싸거나, 그 반대) */
        private fun overlaps(a: IntArray, b: IntArray): Boolean = centerInside(a, b) || centerInside(b, a)

        private fun centerInside(inner: IntArray, outer: IntArray): Boolean {
            if (inner.size < 4 || outer.size < 4) return false
            val x = (inner[0] + inner[2]) / 2
            val y = (inner[1] + inner[3]) / 2
            return x in outer[0]..outer[2] && y in outer[1]..outer[3]
        }
    }
}
