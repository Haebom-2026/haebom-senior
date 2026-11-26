package kr.haebom.elder.guide

import kr.haebom.elder.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTaskTest {
    @Test
    fun contextualRequestsUseTheForegroundApp() {
        assertEquals(GuideTask.YOUTUBE, GuideTask.forCommand("트로트 검색해 줘", "com.google.android.youtube"))
        assertEquals(GuideTask.CONTACTS, GuideTask.forCommand("이 화면 안내해 줘", "com.android.contacts"))
        assertNull(GuideTask.forCommand("이 화면 안내해 줘", "com.example.unsupported"))
    }

    @Test
    fun passwordScreenDoesNotReturnAControl() {
        val step = GuideTask.CONTACTS.findStep(emptyList(), hasPasswordField = true)
        assertEquals(R.string.guide_authentication_stop, step.caption)
        assertNull(step.control)
    }

    @Test
    fun protectedRequestsHaveLocalGuardsAndOnlyKnownUiLabelsAreKept() {
        assertTrue(GuideTask.isFinance("viva.republica.toss", "이 화면 안내"))
        assertTrue(GuideTask.isPrivateCommand("인증번호 넣어 줘"))
        assertFalse(GuideTask.isHelpCommand("이 화면 좀 도와줘"))
        assertFalse("01012345678" in GuideTask.CONTACTS.allowedLabels)
    }
    @Test
    fun gov24WebFallbackOpensInChromeAndChromeCountsAsGov24WhileGuiding() {
        assertEquals("https://plus.gov.kr", GuideTask.GOV24.webUrl)
        assertNull(GuideTask.YOUTUBE.webUrl)
        GuideSession.stop()
        assertNull(GuideTask.forPackage("com.android.chrome"))
        GuideSession.start(GuideTask.GOV24, "com.android.chrome")
        assertEquals(GuideTask.GOV24, GuideTask.forPackage("com.android.chrome"))
        GuideSession.stop()
    }

    @Test
    fun youtubeQueryIsTakenFromTheSpokenCommand() {
        assertEquals("임영웅", GuideTask.youtubeQuery("유튜브에 임영웅 검색해줘"))
        assertEquals("임영웅", GuideTask.youtubeQuery("유튜브에서 임영웅을 검색해 줘"))
        assertEquals("트로트 노래", GuideTask.youtubeQuery("유튜브에서 트로트 노래 좀 틀어 줘"))
        assertEquals("임영웅", GuideTask.youtubeQuery("YouTube 임영웅 영상 보여주세요."))
        assertEquals("트로트", GuideTask.youtubeQuery("트로트 검색해 줘"))
        assertNull(GuideTask.youtubeQuery("유튜브 열어 줘"))
        assertNull(GuideTask.youtubeQuery("유튜브 검색해줘"))
    }

    @Test
    fun youtubeStepsWithAQuery() {
        val search = ScreenControl(setOf("검색"), android.graphics.Rect(), clickable = true)
        val suggestion = ScreenControl(setOf("임영웅"), android.graphics.Rect(), clickable = true)
        val results = ScreenControl(setOf("search_query"), android.graphics.Rect(), clickable = true)

        val home = GuideTask.YOUTUBE.findStep(listOf(search), false, "임영웅")
        assertEquals(R.string.guide_youtube_search_query, home.caption)
        assertSame(search, home.control)
        assertEquals("임영웅", home.arg)

        val typed = GuideTask.YOUTUBE.findStep(listOf(suggestion), false, "임영웅", searchField = true)
        assertEquals(R.string.guide_youtube_pick_suggestion, typed.caption)
        assertSame(suggestion, typed.control)

        val noSuggestion = GuideTask.YOUTUBE.findStep(emptyList(), false, "임영웅", searchField = true)
        assertEquals(R.string.guide_youtube_press_enter, noSuggestion.caption)
        assertNull(noSuggestion.control)

        // 말한 검색어의 결과 화면이면 끝
        val ownResults = ScreenControl(setOf("search_query", "임영웅"), android.graphics.Rect(), clickable = true)
        val done = GuideTask.YOUTUBE.findStep(listOf(ownResults, search), false, "임영웅")
        assertEquals(R.string.guide_youtube_results, done.caption)
        assertNull(done.control)

        // 예전에 검색해 둔 다른 결과 화면이면 끝내지 않고 위쪽 검색어 칸을 짚음
        val oldResults = GuideTask.YOUTUBE.findStep(listOf(results, search), false, "임영웅")
        assertEquals(R.string.guide_youtube_new_search, oldResults.caption)
        assertSame(results, oldResults.control)

        // 검색어 없이 시작하면 예전처럼 검색 단추만 안내
        assertEquals(R.string.guide_youtube_search, GuideTask.YOUTUBE.findStep(listOf(search), false).caption)
    }

    @Test
    fun youtubeSearchFieldIsTheOnlyFillableField() {
        assertEquals(setOf("search_edit_text"), GuideTask.YOUTUBE.searchFieldIds)
        assertTrue(GuideTask.GOV24.searchFieldIds.isEmpty())
        GuideSession.start(GuideTask.YOUTUBE, "com.google.android.youtube", "임영웅")
        assertFalse(GuideSession.active!!.queryFilled)
        GuideSession.markQueryFilled()
        assertTrue(GuideSession.active!!.queryFilled)
        GuideSession.stop()
    }

    @Test
    fun answerContinuesTheSameGuideAndSerialMarksNewSteps() {
        // 단추로 시작하면 AI 에 할 일을 분명히 보냄 (되묻지 않게)
        GuideSession.start(GuideTask.GOV24, "kr.go.minwon.m")
        assertTrue("등본" in GuideSession.active!!.utterance)
        assertEquals("주민등록표 등본(초본) 발급", GuideSession.active!!.slots["service"])

        GuideSession.start(GuideTask.YOUTUBE, "com.google.android.youtube")
        val serial = GuideSession.serial
        // AI: "어떤 영상을 찾을까요?" → "임영웅"
        GuideSession.answer("임영웅")
        assertEquals("임영웅", GuideSession.active!!.query)
        assertEquals("임영웅", GuideSession.active!!.slots["query"])
        assertFalse(GuideSession.active!!.queryFilled)
        assertTrue(GuideSession.active!!.utterance.endsWith("/ 임영웅"))
        // 같은 안내를 이어 감 (serial 그대로), 대답은 다음 요청에 한 번만
        assertEquals(serial, GuideSession.serial)
        assertEquals("임영웅", GuideSession.consumeUserReply())
        assertEquals("", GuideSession.consumeUserReply())
        GuideSession.stop()
    }

    @Test
    fun spokenAppNameMatchesTheOpenedApp() {
        assertTrue(GuideTask.isSameApp("카카오톡", "카카오톡"))
        assertTrue(GuideTask.isSameApp("카카오톡 앱", "카카오톡"))
        assertTrue(GuideTask.isSameApp("유튜브", "YouTube"))
        assertTrue(GuideTask.isSameApp("설정", "설정"))
        assertFalse(GuideTask.isSameApp("카카오톡", "One UI 홈"))
        assertFalse(GuideTask.isSameApp("", "설정"))
    }

    @Test
    fun phoneNumberRequestsGoToAddingAContact() {
        assertEquals(GuideTask.CONTACTS, GuideTask.fromCommand("전화번호 등록하고 싶어"))
        assertEquals(GuideTask.CONTACTS, GuideTask.fromCommand("엄마 전화번호 저장해 줘"))
        assertEquals(GuideTask.CONTACTS, GuideTask.fromCommand("번호 추가해 줘"))
        assertEquals(GuideTask.CONTACTS, GuideTask.fromCommand("주소록에 넣어 줘"))
        assertEquals(GuideTask.CONTACTS, GuideTask.fromCommand("새 연락처 만들어 줘"))
        // 추가·저장하는 말이 없으면 연락처 추가가 아님
        assertNull(GuideTask.fromCommand("연락처에서 홍길동 찾아 줘"))
        assertNull(GuideTask.fromCommand("인증번호 넣어 줘"))
        assertNull(GuideTask.fromCommand("비밀번호 입력해 줘"))
        assertEquals(CommandRoute.Task(GuideTask.CONTACTS, null), CommandRoute.of("전화번호 등록하고 싶어", null, aiAvailable = true))
    }

    @Test
    fun gov24KeepsGuidingWhenOnlyAuthenticationWordsRemain() {
        val bounds = android.graphics.Rect()
        val loginChoice = ScreenControl(setOf("회원신청하기"), bounds, clickable = true)
        val leftoverText = ScreenControl(setOf("본인인증"), bounds, clickable = false)
        assertEquals(R.string.guide_authentication_stop, GuideTask.GOV24.findStep(listOf(loginChoice), false).caption)
        // 인증을 마친 뒤 글자만 남은 화면은 멈추지 않음
        assertFalse(GuideTask.GOV24.findStep(listOf(leftoverText), false).caption == R.string.guide_authentication_stop)
    }

    @Test
    fun gov24StepsOnlyForTheCertificateTheAppKnows() {
        assertEquals(GuideTask.GOV24, GuideTask.fromCommand("정부24에서 등본 떼 줘"))
        assertEquals(GuideTask.GOV24, GuideTask.fromCommand("주민등록 초본 발급해 줘"))
        assertNull(GuideTask.fromCommand("정부24에서 건강보험 자격득실 떼 줘"))
        // AI 에는 정부24 일이라는 것만 알리고, 할 일은 말 그대로 (등본을 넣지 않음)
        assertEquals("GOV24" to mapOf("app" to "정부24"), GuideTask.aiRequestFor("정부24에서 건강보험 자격득실 떼 줘"))
    }

    @Test
    fun openOnlyMeansNothingElseToDo() {
        assertEquals("정부24", GuideTask.openOnlyApp("정부24 열어 줘"))
        assertEquals("카카오톡", GuideTask.openOnlyApp("카카오톡 앱을 실행해 주세요."))
        assertEquals("유튜브", GuideTask.openOnlyApp("유튜브 좀 켜 줘"))
        // 지도 앱을 고른 뒤의 안내 문장: 열기만이 아니면 앱이 떠도 안내를 끝내지 않음
        assertEquals(null, GuideTask.openOnlyApp("네이버지도에서 근처 병원 가고 싶어"))
        assertEquals(null, GuideTask.openOnlyApp("카카오맵에서 길 찾아 줘"))
        assertEquals("네이버지도", GuideTask.openOnlyApp("네이버지도 열어 줘"))
        assertNull(GuideTask.openOnlyApp("유튜브 켜서 임영웅 찾아 줘"))
        assertNull(GuideTask.openOnlyApp("정부24에서 등본 열어 줘"))
        assertNull(GuideTask.openOnlyApp("임영웅 노래 틀어 줘"))
    }
}
