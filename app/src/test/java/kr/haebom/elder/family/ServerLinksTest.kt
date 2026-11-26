package kr.haebom.elder.family

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLinksTest {
    @Test
    fun parsesTheElderLinkListFromTheS1Contract() {
        val json = JSONObject(
            """
            { "links": [
              { "guardianId": "g_1", "guardianName": "김수연", "guardianLabel": "큰딸",
                "elderLabel": "어머니", "connectedAt": "2026-11-26T06:00:00.000Z" },
              { "guardianId": "g_2", "guardianName": "", "guardianLabel": "",
                "elderLabel": "", "connectedAt": "2026-11-26T06:05:00.000Z" }
            ] }
            """.trimIndent(),
        )
        val links = ServerFamilyGateway.parseLinks("e_1", json)
        assertEquals(listOf("g_1", "g_2"), links.map { it.guardianId })
        assertEquals("큰딸", links[0].labelForElder)
        assertEquals("e_1", links[0].elderId)
        assertEquals(1795672800000L, links[0].connectedAt)
        assertNull(links[1].guardianLabel)
        assertEquals(FamilyLink.DEFAULT_GUARDIAN_LABEL, links[1].labelForElder)
    }

    @Test
    fun notifiedGuardiansUseLabelThenNameThenDefault() {
        // S2 #6·#7·#8 의 201 응답 (b0a44d3)
        val json = JSONObject(
            """
            { "result": "created", "notificationId": "n1", "guardianCount": 3, "guardians": [
              { "guardianId": "g_1", "guardianLabel": "큰딸", "guardianName": "김수연" },
              { "guardianId": "g_2", "guardianLabel": "", "guardianName": "이민호" },
              { "guardianId": "g_3", "guardianLabel": "", "guardianName": "" }
            ] }
            """.trimIndent(),
        )
        assertEquals(listOf("큰딸", "이민호", FamilyLink.DEFAULT_GUARDIAN_LABEL), ServerFamilyGateway.notifiedLabels(json))
    }

    @Test
    fun noGuardiansFieldGivesNoLabels() {
        // 중복(duplicate_suppressed)·이전 서버 응답에는 guardians 가 없음 → 앱이 지금 목록으로 대신함
        assertTrue(ServerFamilyGateway.notifiedLabels(JSONObject("""{ "result": "duplicate_suppressed" }""")).isEmpty())
        assertTrue(ServerFamilyGateway.notifiedLabels(null).isEmpty())
    }

    @Test
    fun parsesTheRenameReplyAsOneLink() {
        // PATCH /v1/elder/links/{guardianId} 은 #18 항목 하나를 돌려줌
        val item = JSONObject(
            """{ "guardianId": "g_1", "guardianName": "김수연", "guardianLabel": "작은딸",
                 "elderLabel": "어머니", "connectedAt": "2026-11-26T06:00:00.000Z" }""",
        )
        val link = ServerFamilyGateway.parseLink("e_1", item)
        assertEquals("g_1", link.guardianId)
        assertEquals("작은딸", link.labelForElder)
    }

    @Test
    fun missingOrEmptyListGivesNoLinks() {
        assertTrue(ServerFamilyGateway.parseLinks("e", null).isEmpty())
        assertTrue(ServerFamilyGateway.parseLinks("e", JSONObject("{}")).isEmpty())
    }
}
