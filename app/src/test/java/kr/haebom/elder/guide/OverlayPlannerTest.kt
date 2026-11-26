package kr.haebom.elder.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPlannerTest {
    private val w = 1080
    private val h = 2400
    private val panelW = 250
    private val panelH = 400

    @Test
    fun captionMovesUpWhenTheTargetIsLowOrAboveTheKeyboard() {
        assertFalse(OverlayPlanner.captionAtTop(h, null, null))
        assertFalse(OverlayPlanner.captionAtTop(h, Box(100, 300, 400, 400), null))
        assertTrue(OverlayPlanner.captionAtTop(h, Box(100, 2000, 400, 2100), null))
        // 키보드가 1400 에서 시작하면 그 위 영역 기준으로 판단
        assertTrue(OverlayPlanner.captionAtTop(h, Box(100, 900, 400, 1000), keyboardTop = 1400))

        val bottom = OverlayPlanner.captionBox(w, h, 300, atTop = false, keyboardTop = 1400, inset = 20)
        assertEquals(Box(0, 1080, w, 1380), bottom)
    }

    @Test
    fun panelKeepsItsPlaceWhenNothingIsCovered() {
        val pos = OverlayPlanner.panelPosition(w, h, panelW, panelH, 820, 300, emptyList(), 20, 90)
        assertEquals(810 to 300, pos) // 화면 밖으로 나가지 않게만 맞춤
    }

    @Test
    fun panelSlidesAwayFromTheTargetAndPrefersTheSameEdge() {
        val target = Box(700, 250, 1060, 500)
        val (x, y) = OverlayPlanner.panelPosition(w, h, panelW, panelH, 810, 300, listOf(target), 20, 90)
        assertEquals(810, x)
        val panel = Box(x, y, x + panelW, y + panelH)
        assertFalse(panel.intersects(target.inflate(20)))
    }

    @Test
    fun panelMovesToTheOtherEdgeWhenItsWholeSideIsBlocked() {
        val rightColumn = Box(760, 0, w, h)
        val (x, _) = OverlayPlanner.panelPosition(w, h, panelW, panelH, 810, 300, listOf(rightColumn), 20, 90)
        assertEquals(20, x)
    }

    @Test
    fun releasedPanelSnapsToTheNearestEdge() {
        assertEquals(20, OverlayPlanner.snapX(300, panelW, w, 20))
        assertEquals(810, OverlayPlanner.snapX(600, panelW, w, 20))
    }

    @Test
    fun transientCaptionsExpireBackToTheGuideCaption() {
        var clock = 1_000L
        GuideCaption.now = { clock }
        GuideCaption.listener = null
        GuideCaption.post("유튜브로 이동할게요", GuideCaption.Tone.INFO, durationMs = 5_000)
        assertEquals("유튜브로 이동할게요", GuideCaption.active()?.text)
        GuideCaption.heard("트로트")
        assertEquals("“트로트”", GuideCaption.active()?.text)
        assertFalse(GuideCaption.active()!!.speak)
        clock += GuideCaption.HEARD_MS
        assertNull(GuideCaption.active())
    }

    @Test
    fun captionMovesUpWhenNoTargetButAButtonSitsUnderTheBottomCaption() {
        val stickyApply = Box(450, 2250, 630, 2330) // 화면 아래 고정 "발급하기"
        assertTrue(OverlayPlanner.captionAtTop(h, null, null, listOf(stickyApply), captionHeight = 250, inset = 20))
        // 위 자리에도 단추가 있으면 그대로 아래
        val header = Box(0, 40, 200, 150)
        assertFalse(OverlayPlanner.captionAtTop(h, null, null, listOf(stickyApply, header), captionHeight = 250, inset = 20))
        // 가운데 단추는 상관없음
        assertFalse(OverlayPlanner.captionAtTop(h, null, null, listOf(Box(100, 1000, 300, 1100)), captionHeight = 250, inset = 20))
    }
}
