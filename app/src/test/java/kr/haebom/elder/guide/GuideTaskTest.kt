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
        assertTrue(GuideSession.serial > serial)
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
}
