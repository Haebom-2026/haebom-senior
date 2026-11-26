package kr.haebom.elder.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStateTest {
    @Test
    fun loginStateComesFromTheScreen() {
        assertEquals(ScreenState.Login.IN, ScreenState.login(listOf(ScreenNode(text = "로그아웃", clickable = true))))
        assertEquals(ScreenState.Login.OUT, ScreenState.login(listOf(ScreenNode(description = "로그인", clickable = true))))
        assertEquals(ScreenState.Login.UNKNOWN, ScreenState.login(listOf(ScreenNode(text = "로그인이 필요한 서비스입니다"))))
    }

    @Test
    fun summaryNamesFocusDisabledButtonsAndAuth() {
        val nodes = listOf(
            ScreenNode(text = "로그아웃", clickable = true),
            ScreenNode(className = "EditText", editable = true, focused = true, hint = "이름"),
            ScreenNode(text = "다음", clickable = true, enabled = false),
        )
        val summary = ScreenState.summary(nodes, keyboardOpen = true, justAuthenticated = true)
        assertEquals("로그인됨 · 방금 비밀번호·본인인증을 마침 · 키보드 열림 · 입력 중: 이름 · 못 누르는 단추: 다음", summary)
        assertTrue(summary.length <= 100)
        assertEquals("", ScreenState.summary(emptyList(), keyboardOpen = false, justAuthenticated = false))
    }
}
