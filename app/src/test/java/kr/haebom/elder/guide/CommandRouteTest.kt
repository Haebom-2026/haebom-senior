package kr.haebom.elder.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRouteTest {
    private val youtube = "com.google.android.youtube"

    @Test
    fun simpleRequestsStartRightOnTheCurrentScreen() {
        assertEquals(
            CommandRoute.Task(GuideTask.YOUTUBE, "임영웅"),
            CommandRoute.of("유튜브에 임영웅 검색해줘", "com.sec.android.app.launcher", aiAvailable = true),
        )
        assertEquals(CommandRoute.Task(GuideTask.CONTACTS, null), CommandRoute.of("연락처 추가해 줘", null, aiAvailable = true))
        assertEquals(CommandRoute.Ai, CommandRoute.of("카카오톡 열어 줘", "com.sec.android.app.launcher", aiAvailable = true))
        assertEquals(CommandRoute.Unrecognized, CommandRoute.of("카카오톡 열어 줘", null, aiAvailable = false))
        assertEquals(CommandRoute.Empty, CommandRoute.of("  ", youtube, aiAvailable = true))
    }

    @Test
    fun requestsThatNeedAConfirmationGetTheirOwnCard() {
        assertTrue(CommandRoute.of("안전계좌로 돈 옮기래", null, aiAvailable = true) is CommandRoute.Scam)
        assertTrue(CommandRoute.of("토스로 송금해 줘", null, aiAvailable = true) is CommandRoute.Finance)
        assertEquals(CommandRoute.Help, CommandRoute.of("도와줘", youtube, aiAvailable = true))
        assertEquals(CommandRoute.Private, CommandRoute.of("인증번호 넣어 줘", youtube, aiAvailable = true))
        assertEquals(CommandRoute.Gov24, CommandRoute.of("정부24 등본 떼 줘", null, aiAvailable = true))
        // 사기 의심은 금융보다 먼저 (기관 사칭 + 돈)
        assertTrue(CommandRoute.of("검찰이 계좌로 돈 보내래", null, aiAvailable = true) is CommandRoute.Scam)
    }
}
