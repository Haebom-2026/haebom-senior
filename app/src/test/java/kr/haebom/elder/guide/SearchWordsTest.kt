package kr.haebom.elder.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchWordsTest {
    @Test
    fun onlyTheSearchWordIsKeptWhateverTheEnding() {
        assertEquals("임영웅", GuideTask.searchWords("임영웅 검색 해줘"))
        assertEquals("임영웅", GuideTask.searchWords("임영웅 검색 좀 해 줘"))
        assertEquals("임영웅", GuideTask.searchWords("임영웅을 검색해 줘요"))
        assertEquals("임영웅 노래", GuideTask.searchWords("임영웅 노래 찾아봐"))
        assertEquals("트로트", GuideTask.searchWords("트로트 틀어줘요"))
        assertEquals("임영웅", GuideTask.searchWords("임영웅 좀 보여 줘"))
        assertEquals("임영웅", GuideTask.searchWords("임영웅"))
        assertNull(GuideTask.searchWords("검색해 줘"))
        assertNull(GuideTask.searchWords("  "))
    }

    @Test
    fun youtubeAnswersAndChipsUseTheSearchWordOnly() {
        assertEquals("임영웅", GuideTask.youtubeQuery("유튜브에서 임영웅 검색 좀 해 줘"))
        // AI 가 "어떤 영상?" 하고 물었을 때의 대답
        GuideSession.start(GuideTask.YOUTUBE, "com.google.android.youtube")
        GuideSession.answer("임영웅 검색 해줘")
        assertEquals("임영웅", GuideSession.active!!.query)
        GuideSession.stop()
        // AI 추천어가 문장이어도 단추에는 검색어만
        val field = ScreenNode(className = "EditText", editable = true)
        val reply = AiReply(1, "suggest", "", target = 0, chips = listOf("임영웅 검색 해줘", "임영웅 노래"))
        val youtube = GuideSession.Active(GuideTask.YOUTUBE, "com.google.android.youtube", queryFilled = true)
        assertEquals(listOf("임영웅", "임영웅 노래"), AutofillPlan.chips(reply, field, youtube))
    }
}
