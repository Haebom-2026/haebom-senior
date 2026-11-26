package kr.haebom.elder.guide

import kr.haebom.elder.family.Level
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 서버 #17 classify 응답 해석·폰 캐시 규칙 (haebom-server docs/api/s4-classify.md) */
class ServerRouteTest {
    private val taxi = JSONObject(
        """
        { "commandId": 1795700000000, "intent": "OPEN_APP", "app": null,
          "sensitivity": { "level": "L0", "category": "none", "reason": "", "signals": [] },
          "route": { "mapVersion": "map@seed-2", "role": "taxi", "named": false, "exclusive": false, "ask": false,
            "candidates": [
              { "appId": "kakaot", "name": "카카오T", "packages": ["com.kakao.taxi"], "by": "default", "level": "L0",
                "goal": { "id": "kakaot.call", "version": 1, "name": "택시 부르기", "level": "L0", "slots": [], "verified": true } }
            ] } }
        """.trimIndent(),
    )

    @Test
    fun readsTheTaxiRoute() {
        val result = ServerRoute.parse(taxi)!!
        assertEquals("OPEN_APP", result.intent)
        assertNull(result.app)
        assertEquals(Level.L0, result.level)
        assertEquals("taxi", result.route?.role)
        val candidate = result.route!!.candidates.single()
        assertEquals("kakaot", candidate.appId)
        assertEquals(listOf("com.kakao.taxi"), candidate.packages)
        assertEquals("택시 부르기", candidate.goalName)
    }

    @Test
    fun readsQuestionsAndDegradedReplies() {
        val clarify = ServerRoute.parse(
            JSONObject(
                """
                { "intent": "UNKNOWN", "app": null, "sensitivity": { "level": "L0", "category": "none", "reason": "" },
                  "clarify": { "questionId": "intent", "say": "영상 보기인가요, 연락처인가요?",
                    "options": [{ "id": "video", "name": "영상 보기" }, { "id": "contacts", "name": "연락처" }] } }
                """.trimIndent(),
            ),
        )!!
        assertEquals("intent", clarify.clarify?.questionId)
        assertEquals(listOf("영상 보기", "연락처"), clarify.clarify?.options?.map { it.name })
        val degraded = ServerRoute.parse(
            JSONObject("""{ "intent": "UNKNOWN", "sensitivity": { "level": "L2", "category": "finance", "reason": "ai-unavailable" }, "degraded": true }"""),
        )!!
        assertTrue(degraded.degraded)
        assertEquals(Level.L2, degraded.level)
        assertNull(ServerRoute.parse(JSONObject("""{ "intent": "UNKNOWN" }""")))
        assertNull(ServerRoute.parse(null))
    }

    @Test
    fun sameSentenceGivesTheSameKeyWithoutKeepingTheText() {
        val key = ServerRoute.cacheKey("동대구역까지 택시 타고 가고 싶어")
        assertEquals(key, ServerRoute.cacheKey("동대구역까지 택시타고 가고싶어."))
        assertNotEquals(key, ServerRoute.cacheKey("서울역까지 택시 타고 가고 싶어"))
        assertEquals(64, key.length)
        assertFalse("동대구" in key)
    }

    @Test
    fun onlyReusableDecisionsAreCached() {
        val result = ServerRoute.parse(taxi)!!
        assertTrue(ServerRoute.cacheable("동대구역까지 택시 타고 가고 싶어", result))
        // 숫자가 든 말(번호·금액일 수 있음), 서버가 제대로 판단 못 한 것, 사기 의심, 되묻는 질문은 캐시하지 않음
        assertFalse(ServerRoute.cacheable("010 1234 5678로 전화해 줘", result))
        assertFalse(ServerRoute.cacheable("택시 불러 줘", result.copy(degraded = true)))
        assertFalse(ServerRoute.cacheable("택시 불러 줘", result.copy(level = Level.L3, category = "scam")))
        assertFalse(
            ServerRoute.cacheable(
                "영상 틀어 줘",
                result.copy(clarify = ServerRoute.Clarify("intent", "무엇을?", listOf(ServerRoute.Option("video", "영상")))),
            ),
        )
    }

    @Test
    fun cachedDecisionReadsBackTheSame() {
        val result = ServerRoute.parse(taxi)!!
        val again = ServerRoute.parse(JSONObject(ServerRoute.toJson(result).toString()))
        assertEquals(result, again)
    }

    @Test
    fun onlyInstalledCandidatesInServerOrder() {
        val route = ServerRoute.Route(
            role = "music", named = false, exclusive = false, ask = false,
            candidates = listOf(
                ServerRoute.Candidate("melon", "멜론", listOf("com.iloen.melon"), Level.L0),
                ServerRoute.Candidate("youtube", "유튜브", listOf("com.google.android.youtube"), Level.L0),
                ServerRoute.Candidate("ytmusic", "유튜브 뮤직", listOf("com.google.android.apps.youtube.music"), Level.L0),
            ),
        )
        val installed = setOf("com.google.android.youtube", "com.google.android.apps.youtube.music")
        assertEquals(listOf("youtube", "ytmusic"), ServerRoute.installedCandidates(route) { it in installed }.map { it.first.appId })
    }
}
