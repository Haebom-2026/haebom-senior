package kr.haebom.elder.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPayloadTest {
    private val device = "9b2f0c1e-4d7a-4c55-9a51-2f0b8e6c1d33"
    private val at = "2026-11-26T07:30:00.123Z"

    @Test
    fun screenMatchesTheViewerShape() {
        val json = ScreenPayload.screen(device, at, "kr.go.minwon.m", 1080, 2400, listOf(
            ScreenNode(text = "민원 신청", className = "Button", bounds = intArrayOf(100, 500, 500, 600), clickable = true),
            ScreenNode(description = "뒤로 가기", className = "ImageButton", bounds = intArrayOf(0, 80, 120, 200)),
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
        val many = List(80) { ScreenNode(text = "가".repeat(150)) } + ScreenNode(text = "010-1234-5678", editable = true)
        val json = requireNotNull(ScreenPayload.screen(device, at, "pkg", 1, 1, many))
        assertEquals(60, Regex("\\{\"i\":").findAll(json).count())
        assertTrue(json.contains("\"t\":\"${"가".repeat(100)}\""))
        assertFalse(json.contains("가".repeat(101)))

        // 비어 있는 칸(글자가 힌트뿐)은 값으로 보내지 않음
        val empty = requireNotNull(ScreenPayload.screen(device, at, "pkg", 1, 1,
            listOf(ScreenNode(text = "이름 입력", className = "EditText", editable = true, hint = "이름 입력"))))
        assertFalse(empty.contains("\"t\":"))
        assertTrue(empty.contains("\"editable\":true"))
    }

    @Test
    fun rejectsInvalidFieldsAndEscapesText() {
        assertNull(ScreenPayload.screen("short", at, "pkg", 1, 1, emptyList()))
        assertNull(ScreenPayload.screen(device, at, "", 1, 1, emptyList()))
        assertNull(ScreenPayload.screen(device, at, "pkg", 0, 1, emptyList()))
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", ScreenPayload.quote("a\"b\\c\nd\u0001"))
        assertEquals("Button", ScreenPayload.shortClass("android.widget.Button"))
        assertEquals(
            "{\"deviceId\":\"$device\",\"model\":\"SM-F711N\",\"os\":\"Android 13\",\"appVersion\":\"0.1.0\"}",
            ScreenPayload.heartbeat(device, "SM-F711N", "Android 13", "0.1.0"),
        )
    }

    @Test
    fun inputFieldsSendTheTypedValueOrTheirHint() {
        val json = requireNotNull(ScreenPayload.screenObject("kr.go.minwon.m", 1080, 2400, listOf(
            ScreenNode(text = "서울시 중구 세종대로 110", className = "EditText", editable = true, filled = true, hint = "주소"),
            ScreenNode(text = "연락처 입력", className = "EditText", editable = true, filled = false, hint = "연락처 입력"),
        )))
        // 채워진 칸은 값을 보내 AI 가 입력을 마쳤는지 알 수 있게
        assertTrue(json.contains("\"t\":\"서울시 중구 세종대로 110\""))
        assertTrue(json.contains("\"d\":\"주소\""))
        // 빈 칸은 힌트만
        assertTrue(json.contains("\"d\":\"연락처 입력\""))
        assertFalse(json.contains("\"t\":\"연락처 입력\""))
    }

    @Test
    fun guideBodyCarriesHistoryAndDevice() {
        val screen = requireNotNull(ScreenPayload.screenObject("com.google.android.youtube", 1080, 2640, emptyList()))
        val history = List(12) { "안내 $it" }
        val json = org.json.JSONObject(
            ScreenPayload.guide(7, "YOUTUBE", "유튜브에 임영웅 검색해줘", mapOf("query" to "임영웅"), screen, history, device),
        )
        assertEquals(7, json.getInt("requestId"))
        assertEquals("임영웅", json.getJSONObject("slots").getString("query"))
        assertEquals(10, json.getJSONArray("history").length())
        assertEquals("안내 2", json.getJSONArray("history").getString(0))
        assertEquals(device, json.getString("deviceId"))
        assertFalse(org.json.JSONObject(ScreenPayload.guide(1, "UNKNOWN", "", emptyMap(), screen, deviceId = "x")).has("deviceId"))
    }

    @Test
    fun fillingAnInputFieldChangesTheFingerprint() {
        val before = listOf(ScreenNode(className = "EditText", editable = true, hint = "이름"))
        val after = listOf(ScreenNode(text = "김순자", className = "EditText", editable = true, hint = "이름", filled = true))
        val retyped = listOf(ScreenNode(text = "김순희", className = "EditText", editable = true, hint = "이름", filled = true))
        assertFalse(ScreenPayload.fingerprint("gov", before) == ScreenPayload.fingerprint("gov", after))
        // 한 글자씩 고쳐도 매번 다시 묻지는 않음 (채워졌는지만 봄)
        assertEquals(ScreenPayload.fingerprint("gov", after), ScreenPayload.fingerprint("gov", retyped))
    }

    @Test
    fun fingerprintIgnoresTimersAndPlainText() {
        val base = listOf(
            ScreenNode(description = "검색", className = "ImageView", clickable = true),
            ScreenNode(description = "0분 0초 중 0분 1초", className = "SeekBar", clickable = true),
            ScreenNode(text = "조회수 1만회", className = "TextView"),
        )
        val later = listOf(
            ScreenNode(description = "검색", className = "ImageView", clickable = true),
            ScreenNode(description = "0분 3초 중 0분 9초", className = "SeekBar", clickable = true),
            ScreenNode(text = "조회수 2만회", className = "TextView"),
        )
        assertEquals(ScreenPayload.fingerprint("yt", base), ScreenPayload.fingerprint("yt", later))
        val other = base + ScreenNode(text = "방탄소년단", className = "TextView", clickable = true)
        assertFalse(ScreenPayload.fingerprint("yt", base) == ScreenPayload.fingerprint("yt", other))
    }
}
