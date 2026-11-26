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
        // 앱을 열기만 하라는 말은 서버가 없어도 그 앱만 엶
        assertEquals(CommandRoute.OpenApp("카카오톡"), CommandRoute.of("카카오톡 열어 줘", "com.sec.android.app.launcher", aiAvailable = true))
        assertEquals(CommandRoute.OpenApp("카카오톡"), CommandRoute.of("카카오톡 열어 줘", null, aiAvailable = false))
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

    @Test
    fun ambiguousRequestsAreGuessedAndAsked() {
        // 앱을 말하지 않음 → 유튜브 검색으로 짐작해서 물음
        val youtubeFirst = listOf(Search.Where.YOUTUBE, Search.Where.WEB)
        assertEquals(CommandRoute.Guess("임영웅", youtubeFirst), CommandRoute.of("임영웅 찾아줘", null, aiAvailable = true))
        // 노래는 어느 앱으로 들을지 깔린 앱을 보고 고르게 (유튜브로 정하지 않음)
        assertEquals(CommandRoute.PickApp(AppChoice.Kind.MUSIC, "트로트 노래"), CommandRoute.of("트로트 노래 틀어 줘", null, aiAvailable = true))
        // 분명한 말은 바로
        assertEquals(CommandRoute.Task(GuideTask.YOUTUBE, "임영웅"), CommandRoute.of("유튜브에 임영웅 검색해줘", null, aiAvailable = true))
        // 다른 곳을 말했으면 유튜브로 짐작하지 않음
        assertEquals(CommandRoute.Ai, CommandRoute.of("설정에서 디스플레이 찾아줘", null, aiAvailable = true))
        assertEquals(CommandRoute.OpenApp("카카오톡"), CommandRoute.of("카카오톡 열어 줘", null, aiAvailable = true))
    }

    @Test
    fun openingAnAppOnlyOpensIt() {
        // 정해 둔 시연(등본·검색·연락처 추가)으로 넘어가지 않고 앱만 엶
        assertEquals(CommandRoute.OpenApp("정부24"), CommandRoute.of("정부24 열어 줘", null, aiAvailable = true))
        assertEquals(CommandRoute.OpenApp("정부24"), CommandRoute.of("정부24 앱 좀 열어줘", null, aiAvailable = true))
        assertEquals(CommandRoute.OpenApp("유튜브"), CommandRoute.of("유튜브 켜 줘", null, aiAvailable = true))
        assertEquals(CommandRoute.OpenApp("연락처"), CommandRoute.of("연락처 열어 줘", null, aiAvailable = true))
        // 할 일이 더 있으면 그 일로
        assertEquals(CommandRoute.Gov24, CommandRoute.of("정부24에서 등본 떼 줘", null, aiAvailable = true))
        assertEquals(CommandRoute.Task(GuideTask.YOUTUBE, "임영웅"), CommandRoute.of("유튜브에서 임영웅 검색해 줘", null, aiAvailable = true))
    }

    @Test
    fun requestsOutsideTheKnownStepsGoToTheAiAsSpoken() {
        // 정부24 의 다른 서류, 연락처에서 찾기 같은 일은 앱이 순서를 모름 → 말 그대로 AI 안내
        assertEquals(CommandRoute.Ai, CommandRoute.of("정부24에서 건강보험 자격득실 떼 줘", null, aiAvailable = true))
        assertEquals(CommandRoute.Ai, CommandRoute.of("연락처에서 홍길동 찾아 줘", null, aiAvailable = true))
        assertEquals(CommandRoute.Ai, CommandRoute.of("카카오톡 켜서 큰딸한테 사진 보내 줘", null, aiAvailable = true))
    }

    @Test
    fun webAndNearbySearches() {
        assertEquals(
            CommandRoute.WebSearch(Search.Web("오늘 날씨", Search.Engine.NAVER)),
            CommandRoute.of("네이버에서 오늘 날씨 찾아 줘", null, aiAvailable = true),
        )
        assertEquals(
            CommandRoute.WebSearch(Search.Web("손주 선물", Search.Engine.GOOGLE)),
            CommandRoute.of("구글에 손주 선물 검색해 줘", null, aiAvailable = true),
        )
        assertEquals(CommandRoute.MapSearch("맛집"), CommandRoute.of("주변 맛집 알려 줘", null, aiAvailable = true))
        assertEquals(CommandRoute.MapSearch("우체국"), CommandRoute.of("근처 우체국 어디야", null, aiAvailable = true))
        assertEquals(CommandRoute.MapSearch("헬스장"), CommandRoute.of("가까운 헬스장 찾아 줘", null, aiAvailable = true))
        // "근처 은행" 은 금융 경고가 아니라 장소 찾기
        assertEquals(CommandRoute.MapSearch("은행"), CommandRoute.of("근처 은행 어디 있어", null, aiAvailable = true))
        // 가고 싶다는 말은 검색어에서 빼고 지도로 (2026-11-27 폰: "병원 가고 싶어" 가 그대로 검색어가 됨)
        assertEquals(CommandRoute.MapSearch("병원"), CommandRoute.of("근처 병원 가고 싶어", null, aiAvailable = true))
        assertEquals(CommandRoute.MapSearch("병원"), CommandRoute.of("병원 가고 싶어", null, aiAvailable = true))
        assertEquals(CommandRoute.MapSearch("약국"), CommandRoute.of("약국에 가려고 해", null, aiAvailable = true))
        assertEquals(CommandRoute.MapSearch("보건소"), CommandRoute.of("가까운 보건소 가려면", null, aiAvailable = true))
        // 장소 이름 끝 글자는 자르지 않음 ("종로" 의 "로")
        assertEquals(CommandRoute.MapSearch("종로 우체국"), CommandRoute.of("종로 우체국 가고 싶어", null, aiAvailable = true))
        // 장소 이름 + 찾아 줘 → 지도 먼저, 지식 질문 → 인터넷 먼저
        assertEquals(CommandRoute.Guess("약국", listOf(Search.Where.MAP, Search.Where.WEB)), CommandRoute.of("약국 찾아 줘", null, aiAvailable = true))
        assertEquals(CommandRoute.Guess("오늘 날씨", listOf(Search.Where.WEB, Search.Where.YOUTUBE)), CommandRoute.of("오늘 날씨 알려 줘", null, aiAvailable = true))
    }

    @Test
    fun spokenChoicesAndSearchUrls() {
        val options = listOf(Search.Where.YOUTUBE, Search.Where.WEB)
        assertEquals(Search.Where.WEB, Confirm.pick("인터넷", options))
        assertEquals(Search.Where.YOUTUBE, Confirm.pick("유튜브로", options))
        assertEquals(null, Confirm.pick("지도", options))
        assertEquals("https://search.naver.com/search.naver?query=%EC%9A%B0%EC%B2%B4%EA%B5%AD", Search.webUrl(Search.Web("우체국", Search.Engine.NAVER)))
        assertTrue(Search.mapUrls("우체국", "kr.haebom.elder").first().first.startsWith("nmap://search?query=%EC%9A%B0"))
    }

    @Test
    fun appKindsWithoutANamedAppArePicked() {
        assertEquals(CommandRoute.PickApp(AppChoice.Kind.MUSIC, null), CommandRoute.of("노래 좀 틀어 줘", null, aiAvailable = true))
        assertEquals(CommandRoute.PickApp(AppChoice.Kind.TAXI, null), CommandRoute.of("택시 불러 줘", null, aiAvailable = true))
        assertEquals(CommandRoute.PickApp(AppChoice.Kind.MAP, null), CommandRoute.of("지도 열어 줘", null, aiAvailable = true))
        // 앱을 말했으면 그 앱으로
        assertEquals(CommandRoute.Task(GuideTask.YOUTUBE, "임영웅 노래"), CommandRoute.of("유튜브에서 임영웅 노래 틀어 줘", null, aiAvailable = true))
        assertEquals(CommandRoute.Ai, CommandRoute.of("멜론에서 임영웅 노래 틀어 줘", null, aiAvailable = true))
    }

    @Test
    fun stopWordsStopTheGuide() {
        for (said in listOf("그만", "그만해 줘", "멈춰", "안내 그만", "이제 됐어", "그만할래요", "취소")) {
            assertEquals(said, CommandRoute.Stop, CommandRoute.of(said, youtube, aiAvailable = true))
        }
        // 다른 할 일이 붙으면 멈춤이 아님
        assertEquals(CommandRoute.OpenApp("유튜브"), CommandRoute.of("유튜브 켜 줘", null, aiAvailable = true))
        assertTrue(CommandRoute.of("그만 보고 카카오톡 열어 줘", null, aiAvailable = true) != CommandRoute.Stop)
    }

    @Test
    fun mentioningASiteIsNotASearchUnlessAskedToFind() {
        assertEquals(CommandRoute.Ai, CommandRoute.of("네이버에 로그인하게 도와줘", null, aiAvailable = true))
        assertEquals(
            CommandRoute.WebSearch(Search.Web("오늘 날씨", Search.Engine.NAVER)),
            CommandRoute.of("네이버에서 오늘 날씨 알려 줘", null, aiAvailable = true),
        )
    }
}
