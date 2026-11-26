package kr.haebom.elder.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfirmTest {
    @Test
    fun shortSpokenAnswersAreYesOrNo() {
        listOf("네", "예", "응", "네 해 주세요", "그래", "좋아요", "열어 줘").forEach {
            assertEquals(it, Confirm.Answer.YES, Confirm.answer(it))
        }
        listOf("아니요", "아니", "아뇨", "됐어", "취소").forEach {
            assertEquals(it, Confirm.Answer.NO, Confirm.answer(it))
        }
        // 길거나 다른 말은 새 요청
        assertEquals(Confirm.Answer.OTHER, Confirm.answer("아니 유튜브에서 트로트 찾아 줘"))
        assertEquals(Confirm.Answer.OTHER, Confirm.answer("카카오톡"))
        assertEquals(Confirm.Answer.OTHER, Confirm.answer(""))
    }

    @Test
    fun spokenAppNameFindsTheInstalledApp() {
        val apps = listOf("카카오톡" to "com.kakao.talk", "카카오T" to "com.kakao.taxi", "YouTube" to "com.google.android.youtube")
        assertEquals("com.kakao.talk", Confirm.findApp("카카오톡", apps)?.second)
        assertEquals("com.kakao.talk", Confirm.findApp("카카오톡 앱", apps)?.second)
        assertEquals("com.google.android.youtube", Confirm.findApp("유튜브", apps)?.second)
        assertNull(Confirm.findApp("배달의민족", apps))
    }
}
