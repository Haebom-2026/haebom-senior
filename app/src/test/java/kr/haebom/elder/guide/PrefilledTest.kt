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

    @Test
    fun savedLoginWindowsAreLeftToTheServerQuestion() {
        // 크롬 "저장된 비밀번호를 사용하시겠습니까?" 창 (2026-11-27 서버 요청 #10, com.android.chrome)
        val chrome = listOf(
            label("저장된 비밀번호를 사용하시겠습니까?", 900),
            label("nid.naver.com에 로그인합니다", 980),
            field(1100, "haebom"),
            ScreenNode(className = "EditText", bounds = intArrayOf(60, 1260, 1020, 1400), editable = true, filled = true, secret = true),
            ScreenNode(text = "로그인", className = "Button", clickable = true, bounds = intArrayOf(60, 1500, 1020, 1640)),
        )
        assertTrue(Prefilled.isSavedLogin(chrome))
        assertTrue(Prefilled.isSavedLogin(listOf(label("Samsung Pass로 로그인", 900), field(1100, "a"), field(1300, "b"))))
        assertTrue(Prefilled.isSavedLogin(listOf(ScreenNode(description = "Google 비밀번호 관리자"), field(1100, "a"))))
        // 보통 로그인 화면·신청서는 그대로 물음
        val naver = listOf(label("아이디", 600), field(674, "haebomtest"), label("비밀번호", 840), field(914, null))
        assertFalse(Prefilled.isSavedLogin(naver))
        // 어르신이 칸에 쓴 글자는 보지 않음
        assertFalse(Prefilled.isSavedLogin(listOf(field(674, "저장된 계정"), field(914, "x"))))
    }
}
