package kr.haebom.elder.guide

import kr.haebom.elder.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillPlanTest {
    private val field = ScreenNode(className = "EditText", editable = true, clickable = true)
    private val button = ScreenNode(description = "검색", clickable = true)

    @Test
    fun spokenQueryComesFirstThenAiSuggestions() {
        val active = GuideSession.Active(GuideTask.YOUTUBE, "com.google.android.youtube", "임영웅")
        val reply = AiReply(1, "suggest", "검색창에 입력해 보세요.", target = 0, chips = listOf("임영웅", "임영웅 노래", "임영웅 콘서트", "더"))
        assertEquals(listOf("임영웅", "임영웅 노래", "임영웅 콘서트"), AutofillPlan.chips(reply, field, active))
    }

    @Test
    fun noChipsForButtonsOrAfterTheQueryIsTyped() {
        val active = GuideSession.Active(GuideTask.YOUTUBE, "com.google.android.youtube", "임영웅")
        val highlight = AiReply(1, "highlight", "검색을 누르세요.", target = 0)
        assertTrue(AutofillPlan.chips(highlight, button, active).isEmpty())
        assertEquals(listOf("임영웅"), AutofillPlan.chips(highlight, field, active))
        assertTrue(AutofillPlan.chips(highlight, field, active.copy(queryFilled = true)).isEmpty())
        // AI 만 쓰는 일: AI 추천어만
        val aiOnly = GuideSession.Active(null, null, goal = "UNKNOWN")
        val suggest = AiReply(1, "suggest", "", target = 0, chips = listOf("디스플레이"))
        assertEquals(listOf("디스플레이"), AutofillPlan.chips(suggest, field, aiOnly))
    }

    @Test
    fun localYoutubeStepOffersTheAutofillButtonInsteadOfTyping() {
        val step = GuideTask.YOUTUBE.findStep(emptyList(), false, "임영웅", searchField = true, queryFilled = false)
        assertEquals(R.string.guide_youtube_tap_autofill, step.caption)
        assertEquals(listOf("임영웅"), step.chips)
    }

    @Test
    fun neverOffersTextForASecretField() {
        // 비밀번호·인증번호 칸: AI 가 넣을 글자를 줘도, 말한 값이 있어도 단추를 만들지 않음
        val secret = field.copy(secret = true)
        val aiOnly = GuideSession.Active(null, null, goal = "UNKNOWN", slots = mapOf("query" to "1234"))
        val suggest = AiReply(1, "suggest", "", target = 0, typeText = "1234", chips = listOf("1234"))
        assertTrue(AutofillPlan.chips(suggest, secret, aiOnly).isEmpty())
    }
}
