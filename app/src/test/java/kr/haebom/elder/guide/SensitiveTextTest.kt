package kr.haebom.elder.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveTextTest {
    private fun text(t: String, top: Int, left: Int = 48, right: Int = 1032) =
        ScreenNode(text = t, className = "TextView", bounds = intArrayOf(left, top, right, top + 60))

    private fun field(top: Int, hint: String? = null, left: Int = 48) =
        ScreenNode(text = hint, className = "EditText", bounds = intArrayOf(left, top, 1032, top + 120), editable = true, clickable = true)

    @Test
    fun noticeTextIsDroppedAndTheRestIsSent() {
        // 로그인 뒤 신청서: 입력 칸 없이 안내 문구에만 '주민등록번호'
        val nodes = listOf(
            text("신청할 서비스를 선택하세요.", 300),
            text("장애인등록증 (주민등록번호가 포함된 경우만 가능함)", 500),
            ScreenNode(text = "주민등록표 등본 발급", clickable = true, bounds = intArrayOf(99, 700, 981, 800)),
        )
        val result = SensitiveText.sanitize(nodes)
        assertFalse(result.blocked)
        assertEquals(2, result.nodes.size)
        assertTrue(result.nodes.none { SensitiveText.isSensitive(it.text) })
    }

    @Test
    fun aFieldLabelledWithASensitiveWordPausesTheGuide() {
        // 칸 바로 위 이름표
        assertTrue(SensitiveText.sanitize(listOf(text("주민등록번호", 900), field(980))).blocked)
        // 같은 줄 왼쪽 이름표
        assertTrue(SensitiveText.sanitize(listOf(text("비밀번호", 1000, left = 48, right = 300), field(990, left = 320))).blocked)
        // 칸 안의 안내 글자(힌트)
        assertTrue(SensitiveText.sanitize(listOf(field(800, hint = "인증번호 6자리"))).blocked)
    }

    @Test
    fun aFarAwayNoticeDoesNotPauseAnOrdinaryField() {
        val nodes = listOf(text("주민등록번호는 수집하지 않습니다", 300), text("받는 분 이름", 1400), field(1480))
        val result = SensitiveText.sanitize(nodes)
        assertFalse(result.blocked)
        assertEquals(2, result.nodes.size)
    }
}
