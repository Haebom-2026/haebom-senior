package kr.haebom.elder.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefilledTest {
    private fun field(top: Int, text: String?, hint: String? = null, filled: Boolean = text != null, focused: Boolean = false) =
        ScreenNode(text = text, hint = hint, className = "EditText", bounds = intArrayOf(60, top, 1020, top + 140),
            clickable = true, editable = true, filled = filled, focused = focused)

    private fun label(t: String, top: Int) = ScreenNode(text = t, className = "TextView", bounds = intArrayOf(60, top, 1020, top + 60))

    @Test
    fun greyExampleTextIsNotTypedText() {
        // PASS·토스 인증 화면에서 실제로 보이는 예시 글자들
        assertTrue(Prefilled.looksLikeExample("홍길동", null))
        assertTrue(Prefilled.looksLikeExample("YYYYMMDD", null))
        assertTrue(Prefilled.looksLikeExample("생년월일 8자리", null))
        assertTrue(Prefilled.looksLikeExample("예) 010-1234-5678", null))
        assertTrue(Prefilled.looksLikeExample("000-0000-0000", null))
        assertTrue(Prefilled.looksLikeExample("휴대폰번호 입력", null))
        assertTrue(Prefilled.looksLikeExample("이름을 입력해 주세요", "이름을 입력해 주세요"))
        assertTrue(Prefilled.looksLikeExample("통신사 선택", null))
        // 어르신이 쓴 값은 그대로
        assertFalse(Prefilled.looksLikeExample("김순자", null))
        assertFalse(Prefilled.looksLikeExample("19500315", null))
        assertFalse(Prefilled.looksLikeExample("010-2345-6789", null))
        assertFalse(Prefilled.looksLikeExample("haebomtest", null))
        assertFalse(Prefilled.looksLikeExample(null, "이름"))
    }

    @Test
    fun onlyFormsWithTwoOrMoreFieldsAreAsked() {
        val search = listOf(field(200, "트로트", hint = "검색"))
        assertEquals("", Prefilled.formKey("com.google.android.youtube", search))
        val login = listOf(field(600, "haebomtest", hint = "아이디"), field(800, null, hint = "비밀번호"))
        assertTrue(Prefilled.formKey("com.android.chrome", login).isNotEmpty())
    }

    @Test
    fun filledOnArrivalSkipsTheFieldBeingTyped() {
        val name = field(600, "김순자", hint = "이름")
        val phone = field(800, "010", hint = "휴대폰", focused = true)
        assertEquals(listOf(name), Prefilled.filledOnArrival(listOf(name, phone)))
    }

    @Test
    fun notMineFieldsBecomeEmptyAndCarryNoText() {
        val nodes = listOf(label("이름", 520), field(600, "김순자"), field(800, "19500315", hint = "생년월일"))
        val key = Prefilled.fieldKey("com.toss", nodes[1], nodes)
        val marked = Prefilled.markNotMine("com.toss", nodes, setOf(key))
        assertFalse(marked[1].filled)
        assertNull(marked[1].text)
        // 다른 칸은 그대로
        assertTrue(marked[2].filled)
        assertEquals("19500315", marked[2].text)
    }
}
