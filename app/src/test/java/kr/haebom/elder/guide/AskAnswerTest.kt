package kr.haebom.elder.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAnswerTest {
    private val unknown = "모르겠어요"

    @Test
    fun spokenAnswersPickTheMatchingButton() {
        val yesNo = listOf("네", "아니요", unknown)
        assertEquals("네", AskAnswer.match("네", yesNo, unknown))
        assertEquals("네", AskAnswer.match("응 맞아", yesNo, unknown))
        assertEquals("아니요", AskAnswer.match("아니", yesNo, unknown))
        assertEquals(unknown, AskAnswer.match("잘 모르겠는데", yesNo, unknown))
        val dates = listOf("11월 28일", "12월 5일", unknown)
        assertEquals("12월 5일", AskAnswer.match("12월 5일", dates, unknown))
        assertNull(AskAnswer.match("다음 주에 할래", dates, unknown))
        assertNull(AskAnswer.match("네", emptyList(), unknown))
    }

    @Test
    fun requestCarriesUserReplyAndSwipeAreaIsRead() {
        val screen = requireNotNull(ScreenPayload.screenObject("pkg", 1, 1, emptyList()))
        val json = org.json.JSONObject(ScreenPayload.guide(1, "GOV24", "", emptyMap(), screen, userReply = "모르겠어요"))
        assertEquals("모르겠어요", json.getString("userReply"))
        assertTrue(!org.json.JSONObject(ScreenPayload.guide(1, "GOV24", "", emptyMap(), screen)).has("userReply"))
        val swipe = AiReply.parse("""{"requestId":1,"type":"swipe","say":"위로 밀어 올리세요.","dir":"up","target":2,"targetText":"목록"}""")!!
        val nodes = listOf(ScreenNode(text = "a"), ScreenNode(text = "b"), ScreenNode(text = "목록", bounds = intArrayOf(0, 500, 1080, 1500)))
        assertEquals(1000, AiReply.findTarget(swipe, nodes)!!.bounds!![1] + 500)
    }
}
