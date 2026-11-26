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
    fun missingOrEmptyListGivesNoLinks() {
        assertTrue(ServerFamilyGateway.parseLinks("e", null).isEmpty())
        assertTrue(ServerFamilyGateway.parseLinks("e", JSONObject("{}")).isEmpty())
    }
}
