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

        val done = GuideTask.YOUTUBE.findStep(listOf(results, search), false, "임영웅")
        assertEquals(R.string.guide_youtube_results, done.caption)
        assertNull(done.control)

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
}
