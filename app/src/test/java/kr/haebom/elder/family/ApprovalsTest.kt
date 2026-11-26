package kr.haebom.elder.family

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 보호자 승인 (S3, haebom-server docs/api/s3-approvals.md) */
class ApprovalsTest {
    @Test
    fun createdAndExistingBothWaitOnTheGivenRequest() {
        val created = Approvals.parseStart(
            true,
            JSONObject("""{ "result": "created", "approvalId": "a1", "expiresAt": "2026-11-26T10:38:29.720Z" }"""),
        )
        assertEquals(ApprovalStart.Waiting("a1", 1795689509720L, existing = false), created)
        val existing = Approvals.parseStart(
            true,
            JSONObject("""{ "result": "existing", "approvalId": "a1", "expiresAt": "2026-11-26T10:38:29.720Z" }"""),
        )
        assertEquals(ApprovalStart.Waiting("a1", 1795689509720L, existing = true), existing)
    }

    @Test
    fun noGuardiansAndErrorsAreToldApart() {
        assertEquals(ApprovalStart.NoGuardians, Approvals.parseStart(true, JSONObject("""{ "result": "no_guardians" }""")))
        assertEquals(ApprovalStart.Failed, Approvals.parseStart(false, JSONObject("""{ "error": { "reason": "internal" } }""")))
        assertEquals(ApprovalStart.Failed, Approvals.parseStart(false, null))
        // 성공인데 id·만료 시각이 없으면 기다릴 수 없음
        assertEquals(ApprovalStart.Failed, Approvals.parseStart(true, JSONObject("""{ "result": "created" }""")))
        assertEquals(
            ApprovalStart.Failed,
            Approvals.parseStart(true, JSONObject("""{ "result": "created", "approvalId": "a1", "expiresAt": "곧" }""")),
        )
    }

    @Test
    fun readsTheResultWithTheRespondersLabel() {
        val json = JSONObject(
            """
            { "id": "a1", "status": "approved", "action": "guide_gov24_auth", "packageName": "kr.go.minwon.m",
              "appName": "정부24", "category": "public", "createdAt": "2026-11-26T10:33:29.720Z",
              "expiresAt": "2026-11-26T10:38:29.720Z", "respondedAt": "2026-11-26T10:33:31.000Z",
              "respondedBy": "g_1", "respondedByLabel": "큰딸" }
            """.trimIndent(),
        )
        assertEquals(ApprovalState("a1", ApprovalStatus.APPROVED, "큰딸"), Approvals.parseState(json))
        assertEquals(
            ApprovalState("a1", ApprovalStatus.PENDING),
            Approvals.parseState(JSONObject("""{ "id": "a1", "status": "pending" }""")),
        )
        assertNull(Approvals.parseState(JSONObject("""{ "id": "a1", "status": "maybe" }""")))
        assertNull(Approvals.parseState(null))
    }

    @Test
    fun readsTheResultPushSentToTheElder() {
        assertEquals(
            ApprovalState("a1", ApprovalStatus.REJECTED, "아들"),
            Approvals.fromPush(mapOf("kind" to "approval", "id" to "a1", "status" to "rejected", "respondedBy" to "g_2", "respondedByLabel" to "아들")),
        )
        assertEquals(
            ApprovalState("a1", ApprovalStatus.CANCELLED),
            Approvals.fromPush(mapOf("kind" to "approval", "id" to "a1", "status" to "cancelled")),
        )
        // 보호자에게 가는 "새 요청" 메시지 모양(status 없음)은 무시
        assertNull(Approvals.fromPush(mapOf("kind" to "approval", "id" to "a1", "elderId" to "e_1")))
    }

    @Test
    fun theFirstFinishedResultWinsOnce() {
        val wait = ApprovalWait("a1", expiresAt = 100_000L)
        assertNull(wait.accept(ApprovalState("a1", ApprovalStatus.PENDING)))
        assertNull(wait.accept(ApprovalState("other", ApprovalStatus.APPROVED)))
        assertEquals(ApprovalStatus.APPROVED, wait.accept(ApprovalState("a1", ApprovalStatus.APPROVED, "큰딸"))?.status)
        // 같은 결과가 #19 와 FCM 으로 또 와도 한 번만
        assertNull(wait.accept(ApprovalState("a1", ApprovalStatus.APPROVED, "큰딸")))
        assertNull(wait.expireIfLate(200_000L))
        assertTrue(wait.finished)
    }

    @Test
    fun endsOnItsOwnFiveSecondsAfterExpiry() {
        val wait = ApprovalWait("a1", expiresAt = 100_000L)
        assertNull(wait.expireIfLate(104_999L))
        assertEquals(ApprovalState("a1", ApprovalStatus.EXPIRED), wait.expireIfLate(105_000L))
        assertNull(wait.accept(ApprovalState("a1", ApprovalStatus.APPROVED)))
    }

    @Test
    fun resultsAfterCancellingAreIgnored() {
        val wait = ApprovalWait("a1", expiresAt = 100_000L)
        wait.cancel()
        assertNull(wait.accept(ApprovalState("a1", ApprovalStatus.APPROVED)))
        assertNull(wait.expireIfLate(200_000L))
    }
}
