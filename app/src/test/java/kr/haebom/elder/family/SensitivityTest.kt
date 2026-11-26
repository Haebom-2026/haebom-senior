package kr.haebom.elder.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitivityTest {
    private fun level(command: String, pkg: String? = null) = Sensitivity.fromCommand(command, pkg).level

    @Test
    fun everydayRequestsStayAtL0() {
        assertEquals(Level.L0, level("유튜브에서 트로트 검색해 줘"))
        assertEquals(Level.L0, level("연락처 추가해 줘"))
        assertEquals(Level.L0, level("페이스북 열어 줘"))
        assertEquals(Level.L0, level("이 페이지 크게 보여 줘"))
    }

    @Test
    fun publicAndIdentityRequestsAreL1AndAskFirst() {
        assertEquals(Level.L1, level("정부24에서 등본 떼 줘"))
        assertEquals(Level.L1, level("연말정산 어떻게 해"))
        assertEquals(Level.L1, level("본인인증 해야 돼"))
        assertTrue(Level.L1.askFirst)
        assertFalse(Level.L1.alertNow)
    }

    @Test
    fun moneyRequestsAreL2AlertRightAwayButDoNotStop() {
        val transfer = Sensitivity.fromCommand("아들한테 10만원 송금해 줘")
        assertEquals(Level.L2, transfer.level)
        assertEquals(Sensitivity.FINANCE, transfer.category)
        assertTrue(transfer.reason.contains("송금"))
        assertEquals(Level.L2, level("토스 열어 줘"))
        assertEquals(Level.L2, level("통장 잔액 확인해 줘"))
        assertTrue(Level.L2.alertNow)
        assertFalse(Level.L2.stopGuide)
    }

    @Test
    fun scamSignsAreL3AndStopTheGuide() {
        assertEquals(Level.L3, level("안전계좌로 돈 옮기래"))
        assertEquals(Level.L3, level("검찰이라는데 계좌로 이체하래"))
        assertEquals(Level.L3, level("팀뷰어 설치해 줘"))
        assertEquals(Level.L2, level("경찰서 가는 길 알려 주고 통장 잔액도"))
        assertTrue(Level.L3.alertNow)
        assertTrue(Level.L3.stopGuide)
    }

    @Test
    fun theForegroundAppCanRaiseTheLevelButNeverLowerIt() {
        assertEquals(Level.L2, level("이 화면 안내해 줘", "viva.republica.toss"))
        assertEquals(Level.L1, level("이 화면 안내해 줘", "kr.go.minwon.m"))
        assertEquals(Level.L3, level("팀뷰어 설치해 줘", "kr.go.minwon.m"))
        assertEquals(Level.L0, Sensitivity.fromApp("com.google.android.youtube").level)
    }

    @Test
    fun financeAppNamesMapToInstalledPackages() {
        assertEquals("com.kakaobank.channel", Sensitivity.financeAppFor("카카오뱅크로 송금"))
        assertEquals("viva.republica.toss", Sensitivity.financeAppFor("토스 열어 줘"))
        assertNull(Sensitivity.financeAppFor("송금해 줘"))
    }

    @Test
    fun theSameAlertIsSentOncePerTenMinutes() {
        var clock = 0L
        SensitiveAlerter.now = { clock }
        val finance = Sensitivity.fromCommand("송금해 줘")
        val key = SensitiveAlerter.keyFor(finance, "viva.republica.toss")
        assertEquals(key, SensitiveAlerter.keyFor(finance, "com.kakaobank.channel"))
        assertTrue(SensitiveAlerter.claim(key))
        assertFalse(SensitiveAlerter.claim(key))
        // 5분 안에 다시 들어와도 한 번, 5분이 지나 들어오면 다시 알림
        assertEquals(300_000L, Family.NOTICE_COOLDOWN_MS)
        clock += Family.NOTICE_COOLDOWN_MS - 1
        assertFalse(SensitiveAlerter.claim(key))
        clock += 1
        assertTrue(SensitiveAlerter.claim(key))
    }
}
