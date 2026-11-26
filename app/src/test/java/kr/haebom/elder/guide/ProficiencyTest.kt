package kr.haebom.elder.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProficiencyTest {
    @Test
    fun levelsReduceTheGuideStepByStep() {
        assertEquals(Proficiency.Highlight.FULL, Proficiency.BEGINNER.highlight)
        assertEquals(Proficiency.Highlight.LIGHT, Proficiency.FAMILIAR.highlight)
        assertEquals(Proficiency.Highlight.NONE, Proficiency.EXPERT.highlight)
        assertTrue(Proficiency.FAMILIAR.autofill)
        assertFalse(Proficiency.EXPERT.autofill)
        assertFalse(Proficiency.EXPERT.arrows)
        assertFalse(Proficiency.EXPERT.speakSteps)
        assertEquals("solo", Proficiency.EXPERT.aiMode)
        assertEquals("guide", Proficiency.BEGINNER.aiMode)
        assertEquals(Proficiency.FAMILIAR, Proficiency.BEGINNER.next)
        assertNull(Proficiency.EXPERT.next)
        assertEquals(Proficiency.BEGINNER, Proficiency.of(null))
        assertEquals(Proficiency.EXPERT, Proficiency.of("EXPERT"))
    }

    @Test
    fun suggestOnceAfterFiveFinishedGuides() {
        assertFalse(Proficiency.shouldSuggest(Proficiency.BEGINNER, 4, alreadySuggested = false))
        assertTrue(Proficiency.shouldSuggest(Proficiency.BEGINNER, 5, alreadySuggested = false))
        assertFalse(Proficiency.shouldSuggest(Proficiency.BEGINNER, 9, alreadySuggested = true))
        assertFalse(Proficiency.shouldSuggest(Proficiency.EXPERT, 20, alreadySuggested = false))
    }

    @Test
    fun serverV4FieldsAreRead() {
        val stopped = AiReply.parse("""{"requestId":1,"type":"say","say":"받는 분과 금액을 직접 확인하고 누르세요.","status":"stopped"}""")!!
        assertTrue(stopped.isStopped)
        val nav = AiReply.parse("""{"requestId":2,"type":"say","say":"홈 화면으로 가 주세요.","nav":"back"}""")!!
        assertEquals(DirectionHint.BACK, DirectionHint.from(nav)) // 문장보다 nav 가 먼저
        val suggest = AiReply.parse(
            """{"requestId":3,"type":"suggest","say":"넣기를 누르세요.","target":0,"chips":["열어보기 설정"],"typeText":"열어보기 설정"}""",
        )!!
        val field = ScreenNode(className = "EditText", editable = true)
        // AI 가 준 넣을 글자는 검색어처럼 자르지 않음
        assertEquals(listOf("열어보기 설정"), AutofillPlan.chips(suggest, field, GuideSession.Active(null, null)))
        val ask = AiReply.parse("""{"requestId":4,"type":"ask","say":"가입하셨나요?","options":["네","아니요"]}""")!!
        assertEquals(listOf("네", "아니요"), ask.options)
    }

    @Test
    fun expertModeIsSentToTheServer() {
        val screen = requireNotNull(ScreenPayload.screenObject("pkg", 1, 1, emptyList()))
        assertEquals("solo", org.json.JSONObject(ScreenPayload.guide(1, "UNKNOWN", "", emptyMap(), screen, mode = "solo")).getString("mode"))
        assertEquals("guide", org.json.JSONObject(ScreenPayload.guide(1, "UNKNOWN", "", emptyMap(), screen)).getString("mode"))
    }
}
