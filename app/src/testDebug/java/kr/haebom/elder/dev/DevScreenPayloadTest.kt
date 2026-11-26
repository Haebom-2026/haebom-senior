package kr.haebom.elder.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevScreenPayloadTest {
    private val device = "9b2f0c1e-4d7a-4c55-9a51-2f0b8e6c1d33"
    private val at = "2026-11-26T07:30:00.123Z"

    @Test
    fun screenMatchesTheViewerShape() {
        val json = DevScreenPayload.screen(device, at, "kr.go.minwon.m", 1080, 2400, listOf(
            DevNode(text = "민원 신청", className = "Button", bounds = intArrayOf(100, 500, 500, 600), clickable = true),
            DevNode(description = "뒤로 가기", className = "ImageButton", bounds = intArrayOf(0, 80, 120, 200)),
        ))
        assertEquals(
            "{\"deviceId\":\"$device\",\"capturedAt\":\"$at\",\"screen\":{\"pkg\":\"kr.go.minwon.m\"," +
                "\"width\":1080,\"height\":2400,\"nodes\":[" +
                "{\"i\":0,\"t\":\"민원 신청\",\"c\":\"Button\",\"b\":[100,500,500,600],\"clickable\":true}," +
                "{\"i\":1,\"d\":\"뒤로 가기\",\"c\":\"ImageButton\",\"b\":[0,80,120,200]}]}}",
            json,
        )
    }

    @Test
    fun limitsNodesAndTextAndNeverSendsTypedText() {
        val many = List(80) { DevNode(text = "가".repeat(150)) } + DevNode(text = "010-1234-5678", editable = true)
        val json = requireNotNull(DevScreenPayload.screen(device, at, "pkg", 1, 1, many))
        assertEquals(60, Regex("\\{\"i\":").findAll(json).count())
        assertTrue(json.contains("\"t\":\"${"가".repeat(100)}\""))
        assertFalse(json.contains("가".repeat(101)))

        val typed = requireNotNull(DevScreenPayload.screen(device, at, "pkg", 1, 1,
            listOf(DevNode(text = "010-1234-5678", className = "EditText", editable = true))))
        assertFalse(typed.contains("010-1234-5678"))
        assertTrue(typed.contains("\"editable\":true"))
    }

    @Test
    fun rejectsInvalidFieldsAndEscapesText() {
        assertNull(DevScreenPayload.screen("short", at, "pkg", 1, 1, emptyList()))
        assertNull(DevScreenPayload.screen(device, at, "", 1, 1, emptyList()))
        assertNull(DevScreenPayload.screen(device, at, "pkg", 0, 1, emptyList()))
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", DevScreenPayload.quote("a\"b\\c\nd\u0001"))
        assertEquals("Button", DevScreenPayload.shortClass("android.widget.Button"))
        assertEquals(
            "{\"deviceId\":\"$device\",\"model\":\"SM-F711N\",\"os\":\"Android 13\",\"appVersion\":\"0.1.0\"}",
            DevScreenPayload.heartbeat(device, "SM-F711N", "Android 13", "0.1.0"),
        )
    }
}
