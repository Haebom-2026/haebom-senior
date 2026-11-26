package kr.haebom.elder.guide

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R6 명령 기록: lastAction(stepId·seq·action·text), guide 본문, 끝 신호 결과 */
class CommandStepsTest {
    private val button = intArrayOf(100, 500, 400, 600)
    private fun shownTap(stepId: String = "s1", fp: String = "fpA") =
        CommandSteps.Shown(stepId, AiReply.HIGHLIGHT, box = button, label = "디스플레이", fingerprint = fp)

    @Test
    fun tapOnHighlightedTargetCarriesStepIdAndTargetLabel() {
        val steps = CommandSteps(42)
        steps.show(shownTap())
        // 누른 것은 글자를 감싼 틀 (자기 글자 없음)
        val action = steps.record(LastAction.TAP, "", intArrayOf(0, 480, 1080, 620))
        assertEquals(LastAction("s1", 0, "tap", "디스플레이"), action)
        assertEquals(action, steps.takePending())
        assertNull(steps.takePending())
    }

    @Test
    fun tapElsewhereHasNoStepIdAndOwnText() {
        val steps = CommandSteps(42)
        steps.show(shownTap())
        val action = steps.record(LastAction.TAP, "소리", intArrayOf(100, 900, 400, 1000))
        assertNull(action.stepId)
        assertEquals("소리", action.text)
    }

    @Test
    fun seqCountsEveryActionAndLatestWins() {
        val steps = CommandSteps(7)
        steps.record(LastAction.TAP, "a")
        steps.record(LastAction.TAP, "b")
        val last = steps.takePending()!!
        assertEquals(1, last.seq)
        assertEquals("b", last.text)
        assertEquals(2, steps.record(LastAction.HOME, "").seq)
    }

    @Test
    fun followingOnceClearsShownGuidance() {
        val steps = CommandSteps(1)
        steps.show(shownTap())
        assertEquals("s1", steps.record(LastAction.TAP, "디스플레이", button).stepId)
        // 같은 자리를 또 누름: 안내 없이 한 행동
        assertNull(steps.record(LastAction.TAP, "디스플레이", button).stepId)
    }

    @Test
    fun newScreenForgetsPreviousGuidance() {
        val steps = CommandSteps(1)
        steps.show(shownTap(fp = "fpA"))
        steps.forgetShownUnless("fpA")
        assertTrue(steps.shown != null)
        steps.forgetShownUnless("fpB")
        assertNull(steps.shown)
    }

    @Test
    fun swipeTypeAndNavFollowing() {
        val swipe = CommandSteps.Shown("s2", AiReply.SWIPE, dir = "left")
        assertTrue(CommandSteps.followed(swipe, "swipe_left", null))
        assertFalse(CommandSteps.followed(swipe, "swipe_right", null))
        val suggest = CommandSteps.Shown("s3", AiReply.SUGGEST, box = button)
        assertTrue(CommandSteps.followed(suggest, LastAction.TYPE, button))
        val back = CommandSteps.Shown("s4", AiReply.SAY, nav = "back")
        assertTrue(CommandSteps.followed(back, LastAction.BACK, null))
        assertFalse(CommandSteps.followed(back, LastAction.HOME, null))
        // type 은 넣은 값을 보내지 않음
        val steps = CommandSteps(1)
        steps.show(suggest)
        assertEquals(LastAction("s3", 0, "type", ""), steps.record(LastAction.TYPE, "", button))
    }

    @Test
    fun finishResults() {
        val done = CommandSteps(1).apply { markDone("{\"pkg\":\"a\"}") }
        assertEquals("done" to "{\"pkg\":\"a\"}", done.finish())
        assertNull(done.finish()) // 한 번만

        val safe = CommandSteps(2).apply { markSafeStop("{\"pkg\":\"b\"}") }
        assertEquals("stopped_safe" to "{\"pkg\":\"b\"}", safe.finish())

        val resumed = CommandSteps(3).apply {
            markSafeStop("{}")
            clearSafeStop()
        }
        assertEquals("stopped_user" to null, resumed.finish())
    }

    @Test
    fun guideBodyCarriesCommandIdAndLastAction() {
        val screen = ScreenPayload.screenObject("com.android.settings", 1080, 2400, emptyList())!!
        val json = JSONObject(
            ScreenPayload.guide(
                3, "OPEN_APP", "화면 좀 어둡게 해 줘", emptyMap(), screen, deviceId = "a1b2c3d4e5f6",
                commandId = 1764200000123, lastAction = LastAction("smwy00pxz5355f7", 1, "tap", "디스플레이"),
            ),
        )
        assertEquals(1764200000123, json.getLong("commandId"))
        assertEquals("a1b2c3d4e5f6", json.getString("deviceId"))
        val last = json.getJSONObject("lastAction")
        assertEquals("smwy00pxz5355f7", last.getString("stepId"))
        assertEquals(1, last.getInt("seq"))
        assertEquals("tap", last.getString("action"))
        assertEquals("디스플레이", last.getString("text"))
        assertEquals("com.android.settings", json.getJSONObject("screen").getString("pkg"))

        val plain = JSONObject(ScreenPayload.guide(1, "UNKNOWN", "", emptyMap(), screen))
        assertFalse(plain.has("commandId"))
        assertFalse(plain.has("lastAction"))
        // 안내 없이 한 행동은 stepId 없음
        assertFalse(LastAction(null, 0, "home", "").toJson().has("stepId"))
    }

    @Test
    fun replyCarriesStepIdAndSource() {
        val reply = AiReply.parse(
            """{"requestId":3,"type":"highlight","say":"'디스플레이'를 눌러 주세요.","target":4,"status":"in_progress","stepId":"smwy00pxz5355f7","source":"map"}""",
        )!!
        assertEquals("smwy00pxz5355f7", reply.stepId)
        assertEquals("map", reply.source)
        assertNull(AiReply.parse("""{"requestId":1,"type":"say","say":"x"}""")!!.stepId)
    }
}
