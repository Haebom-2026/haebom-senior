package kr.haebom.elder.family

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeTest {
    private fun c(id: String, status: ChallengeStatus, at: Long, title: String = "도전 $id", hint: String = "", cheers: Map<String, String> = emptyMap()) =
        Challenge(id, title, hint, "g_1", status, createdAt = at, cheers = cheers)

    @Test
    fun parsesTheProposedList() {
        val json = JSONObject(
            """
            { "items": [
              { "id": "c1", "title": "카카오톡으로 사진 보내 보기", "hint": "사진 모양 단추", "suggestedBy": "g_1",
                "status": "sent", "createdAt": "2026-11-27T01:00:00.000Z", "completedAt": null, "withHelp": false, "cheers": {} },
              { "id": "c2", "title": "유튜브에서 노래 찾기", "hint": "", "suggestedBy": "g_2",
                "status": "done", "createdAt": "2026-11-26T01:00:00.000Z", "completedAt": "2026-11-26T02:00:00.000Z",
                "withHelp": true, "cheers": { "g_2": "최고예요! 👍" } },
              { "id": "bad", "status": "unknown" }
            ] }
            """.trimIndent(),
        )
        val list = Challenges.parse(json)
        assertEquals(listOf("c1", "c2"), list.map { it.id })
        assertEquals(ChallengeStatus.SENT, list[0].status)
        assertNull(list[0].completedAt)
        assertTrue(list[1].withHelp)
        assertEquals(mapOf("g_2" to "최고예요! 👍"), list[1].cheers)
    }

    @Test
    fun moneyAndAuthChallengesAreNeverShown() {
        // 보호자를 사칭한 도전 대비 (서버도 막지만 앱도 숨김)
        assertTrue(Challenges.isBlocked("엄마 통장으로 송금해 보기", ""))
        assertTrue(Challenges.isBlocked("문자 확인", "인증 번호 알려 주세요"))
        assertTrue(Challenges.isBlocked("otp 넣기", ""))
        assertFalse(Challenges.isBlocked("카카오톡으로 사진 보내 보기", "사진 모양 단추를 눌러 보세요"))
        val shown = Challenges.openInOrder(listOf(c("a", ChallengeStatus.SENT, 1, title = "상품권 사기"), c("b", ChallengeStatus.SENT, 2)))
        assertEquals(listOf("b"), shown.map { it.id })
    }

    @Test
    fun openChallengesInTheOrderReceivedIncludingLater() {
        val list = listOf(
            c("new", ChallengeStatus.SENT, 30),
            c("done", ChallengeStatus.DONE, 5),
            c("later", ChallengeStatus.LATER, 10),
            c("doing", ChallengeStatus.ACCEPTED, 20),
        )
        // "다음에 할게요" 한 도전도 다시 해 볼 수 있게 남음
        assertEquals(listOf("later", "doing", "new"), Challenges.openInOrder(list).map { it.id })
    }

    @Test
    fun cheersShowOnceAndAgainWhenChanged() {
        val done = c("d", ChallengeStatus.DONE, 1, cheers = mapOf("g_1" to "최고예요! 👍"))
        assertEquals(1, Challenges.unseenCheers(listOf(done), emptySet()).size)
        val seen = setOf(Challenges.cheerKey("d", "g_1", "최고예요! 👍"))
        assertTrue(Challenges.unseenCheers(listOf(done), seen).isEmpty())
        // 보호자가 문구를 바꾸면 다시 보여 줌
        val changed = done.copy(cheers = mapOf("g_1" to "다음에도 같이 해 봐요 😊"))
        assertEquals(1, Challenges.unseenCheers(listOf(changed), seen).size)
    }

    @Test
    fun subjectParticleFollowsTheLastSyllable() {
        assertEquals("딸이", Challenges.subject("딸"))
        assertEquals("배우자가", Challenges.subject("배우자"))
        assertEquals("큰딸이", Challenges.subject("큰딸"))
        assertEquals("Mina이(가)", Challenges.subject("Mina"))
    }
}
