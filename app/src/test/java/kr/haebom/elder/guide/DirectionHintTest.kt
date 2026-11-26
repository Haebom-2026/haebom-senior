package kr.haebom.elder.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DirectionHintTest {
    @Test
    fun swipeDirectionsBecomeArrows() {
        assertEquals(DirectionHint.UP, DirectionHint.from(AiReply(1, "swipe", "위로 밀어 보세요.", dir = "up")))
        assertEquals(DirectionHint.LEFT, DirectionHint.from(AiReply(1, "swipe", "옆으로 밀어 보세요.", dir = "left")))
        assertEquals(DirectionHint.RIGHT, DirectionHint.fromSwipe("right"))
        assertEquals(DirectionHint.DOWN, DirectionHint.fromSwipe("down"))
        assertNull(DirectionHint.fromSwipe(null))
    }

    @Test
    fun homeAndBackComeFromTheSentence() {
        assertEquals(DirectionHint.HOME, DirectionHint.from(AiReply(1, "say", "먼저 홈 화면으로 가 주세요.")))
        assertEquals(DirectionHint.HOME, DirectionHint.from(AiReply(1, "say", "아래 홈 버튼을 눌러 주세요.")))
        assertEquals(DirectionHint.BACK, DirectionHint.from(AiReply(1, "say", "뒤로 가기를 눌러 이전 화면으로 가세요.")))
        // 짚을 요소가 있으면 빨간 테두리로 충분
        assertNull(DirectionHint.from(AiReply(1, "highlight", "홈 화면의 카카오톡을 누르세요.", target = 0)))
        assertNull(DirectionHint.from(AiReply(1, "done", "홈 화면으로 돌아왔어요.")))
        assertNull(DirectionHint.from(AiReply(1, "say", "잠시 기다려 주세요.")))
    }

    @Test
    fun seekTextIsFoundWhenItScrollsIntoView() {
        val nodes = listOf(
            ScreenNode(text = "디스플레이", className = "TextView", bounds = intArrayOf(0, 900, 800, 960)),
            ScreenNode(text = "디스플레이", className = "LinearLayout", bounds = intArrayOf(0, 880, 1080, 1000), clickable = true),
            ScreenNode(text = "화면 밝기", bounds = intArrayOf(0, 0, 0, 0)),
        )
        assertSame(nodes[1], DirectionHint.findSeek("디스플레이", nodes))
        assertSame(nodes[1], DirectionHint.findSeek("디스 플레이", nodes))
        assertNull(DirectionHint.findSeek("화면 밝기", nodes)) // 화면 밖(크기 0)
        assertNull(DirectionHint.findSeek("배터리", nodes))
        assertNull(DirectionHint.findSeek(null, nodes))
    }

    @Test
    fun stuckAfterTheSameScreenForAWhileOncePerGuide() {
        val watch = StuckWatch(limitMs = 45_000)
        watch.seen("A", serial = 1, now = 0)
        assertFalse(watch.due(1, 44_000))
        assertTrue(watch.due(1, 45_000))
        // 화면이 바뀌면 다시 셈
        watch.seen("B", serial = 1, now = 50_000)
        assertFalse(watch.due(1, 90_000))
        assertTrue(watch.due(1, 95_000))
        // 한 번 물었으면 같은 안내에서는 다시 묻지 않음
        watch.markAsked(1)
        assertFalse(watch.due(1, 200_000))
        // 새 안내는 다시
        watch.seen("B", serial = 2, now = 200_000)
        assertTrue(watch.due(2, 245_000))
    }
}
