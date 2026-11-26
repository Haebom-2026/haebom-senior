package kr.haebom.elder.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AiGuideTest {
    @Test
    fun parsesEveryReplyShape() {
        val highlight = AiReply.parse(
            """{"requestId":3,"type":"highlight","say":"검색을 누르세요.","target":2,"targetText":"검색"}""",
        )!!
        assertEquals(3, highlight.requestId)
        assertEquals(2, highlight.target)
        assertEquals("검색", highlight.targetText)
        assertTrue(highlight.pointsAtTarget)

        val swipe = AiReply.parse("""{"requestId":4,"type":"swipe","say":"위로 밀어 보세요.","dir":"up","seekText":"발급"}""")!!
        assertEquals("up", swipe.dir)
        assertEquals("발급", swipe.seekText)
        assertFalse(swipe.pointsAtTarget)

        val suggest = AiReply.parse("""{"requestId":5,"type":"suggest","say":"골라 주세요.","target":0,"chips":["임영웅","임영웅 노래"]}""")!!
        assertEquals(listOf("임영웅", "임영웅 노래"), suggest.chips)

        assertNull(AiReply.parse("not json"))
        assertNull(AiReply.parse("""{"requestId":1}"""))
    }

    @Test
    fun serverFallbackSentencesKeepTheLocalGuide() {
        assertTrue(AiReply(1, "say", "서버가 잠시 바빠요. 조금 뒤에 다시 말씀해 주세요.").isFallback)
        assertTrue(AiReply(1, "say", "제가 화면을 잘 못 알아봤어요. 다시 말씀해 주시겠어요?").isFallback)
        assertFalse(AiReply(1, "say", "이 화면은 직접 입력해 주세요. 제가 대신 보지 않을게요.").isFallback)
        assertFalse(AiReply(1, "done", "서버가 잠시 바빠요").isFallback)
    }

    @Test
    fun targetIsCheckedAgainstItsText() {
        val nodes = listOf(
            ScreenNode(description = "알림", clickable = true),
            ScreenNode(description = "검색", clickable = true),
            ScreenNode(className = "LinearLayout", clickable = true), // 글자 없는 눌리는 틀
        )
        assertSame(nodes[1], AiReply.findTarget(AiReply(1, "highlight", "", target = 1, targetText = "검색"), nodes))
        // 번호가 바뀌어도 글자로 다시 찾음
        assertSame(nodes[1], AiReply.findTarget(AiReply(1, "highlight", "", target = 0, targetText = "검색"), nodes))
        // 틀 안의 글자를 targetText 로 주면 틀 자체를 그대로 씀
        assertSame(nodes[2], AiReply.findTarget(AiReply(1, "highlight", "", target = 2, targetText = "디스플레이"), nodes))
        assertNull(AiReply.findTarget(AiReply(1, "highlight", "", target = 9, targetText = "없는 단추"), nodes))
        assertNull(AiReply.findTarget(AiReply(1, "say", ""), nodes))
    }

    @Test
    fun unknownCommandsBecomeAiGoals() {
        assertEquals("OPEN_APP" to mapOf("app" to "카카오톡"), GuideTask.aiRequestFor("카카오톡 열어 줘"))
        assertEquals("OPEN_APP" to mapOf("app" to "카카오 택시"), GuideTask.aiRequestFor("카카오 택시 앱 좀 켜 줘"))
        assertEquals("UNKNOWN" to emptyMap<String, String>(), GuideTask.aiRequestFor("이 화면 어떻게 해"))
        assertEquals("YOUTUBE", GuideTask.YOUTUBE.aiGoal)
        GuideSession.startAi("카카오톡 열어 줘", "OPEN_APP", mapOf("app" to "카카오톡"))
        assertNull(GuideSession.active!!.task)
        assertNull(GuideSession.active!!.packageName)
        GuideSession.stop()
        GuideSession.start(GuideTask.YOUTUBE, "com.google.android.youtube", "임영웅", "유튜브에 임영웅 검색해줘")
        assertEquals(mapOf("query" to "임영웅"), GuideSession.active!!.slots)
        assertEquals("YOUTUBE", GuideSession.active!!.goal)
        GuideSession.stop()
    }
}
