package kr.haebom.elder.guide

import kr.haebom.elder.family.Level
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * 서버 #17 classify 응답 (haebom-server docs/api/s4-classify.md). 안드로이드 없이 단위 테스트.
 * 말 해석(무엇을 하려는지, 어느 앱으로 할지)은 서버 LLM·jev 가 하고, 앱은 그 결과로 앱을 열어 화면 안내를 시작함.
 * 그만·SOS·사기 의심·금융 경고·"○○ 열어 줘" 는 지금처럼 폰 규칙으로 바로 (빠르고 서버가 없어도 되게).
 */
internal data class Classified(
    val intent: String,
    val app: String?,
    val level: Level,
    val category: String,
    val reason: String,
    /** 서버 안전 분류가 실패해 규칙 결과로만 답함 (route 없음) */
    val degraded: Boolean = false,
    val route: ServerRoute.Route? = null,
    val clarify: ServerRoute.Clarify? = null,
)

internal object ServerRoute {
    data class Option(val id: String, val name: String)

    /** 어르신께 물을 것 (하려는 일 "intent", 또는 후보 앱의 목표 "goal:…") */
    data class Clarify(val questionId: String, val say: String, val options: List<Option>)

    data class Candidate(
        val appId: String,
        val name: String,
        val packages: List<String>,
        val level: Level,
        val goalName: String? = null,
        val clarify: Clarify? = null,
    )

    data class Route(
        val role: String,
        val named: Boolean,
        val exclusive: Boolean,
        /** 은행·결제처럼 순서대로 고르지 말고 어르신께 물어야 하는 역할 */
        val ask: Boolean,
        val candidates: List<Candidate>,
    )

    /** 캐시에 두는 기간 */
    const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    const val CACHE_MAX = 200

    fun parse(json: JSONObject?): Classified? {
        json ?: return null
        val sensitivity = json.optJSONObject("sensitivity") ?: return null
        val level = level(sensitivity.optString("level")) ?: return null
        return Classified(
            intent = json.optString("intent").ifBlank { "UNKNOWN" },
            app = json.optString("app").takeIf { it.isNotBlank() && it != "null" },
            level = level,
            category = sensitivity.optString("category").ifBlank { "none" },
            reason = sensitivity.optString("reason"),
            degraded = json.optBoolean("degraded", false),
            route = json.optJSONObject("route")?.let(::parseRoute),
            clarify = json.optJSONObject("clarify")?.let(::parseClarify),
        )
    }

    fun parseRoute(json: JSONObject): Route = Route(
        role = json.optString("role"),
        named = json.optBoolean("named", false),
        exclusive = json.optBoolean("exclusive", false),
        ask = json.optBoolean("ask", false),
        candidates = json.optJSONArray("candidates").objects().mapNotNull { item ->
            val appId = item.optString("appId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Candidate(
                appId = appId,
                name = item.optString("name").ifBlank { appId },
                packages = item.optJSONArray("packages").strings(),
                level = level(item.optString("level")) ?: Level.L0,
                goalName = item.optJSONObject("goal")?.optString("name")?.takeIf { it.isNotBlank() },
                clarify = item.optJSONObject("clarify")?.let(::parseClarify),
            )
        },
    )

    private fun parseClarify(json: JSONObject): Clarify? {
        val id = json.optString("questionId").takeIf { it.isNotBlank() } ?: return null
        val options = json.optJSONArray("options").objects().mapNotNull { option ->
            val optionId = option.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Option(optionId, option.optString("name").ifBlank { optionId })
        }
        if (options.isEmpty()) return null
        return Clarify(id, json.optString("say"), options)
    }

    /**
     * 같은 말인지 보는 열쇠: 띄어쓰기·문장부호·대소문자를 빼고 SHA-256 (폰에 말 원문을 남기지 않음).
     * "동대구역까지 택시 타고 가고 싶어" 와 "동대구역까지 택시타고 가고싶어." 는 같은 열쇠
     */
    fun cacheKey(utterance: String): String {
        val normalized = utterance.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 다시 써도 되는 판단만 캐시: 서버가 제대로 판단했고(degraded 아님), 사기 의심·L3 가 아니고,
     * 되묻는 질문이 없고(답은 그 명령 번호로 서버에 보내야 함), 숫자(번호·금액 등 개인정보일 수 있음)가 없는 말
     */
    fun cacheable(utterance: String, result: Classified): Boolean =
        !result.degraded && result.level != Level.L3 && result.category != "scam" &&
            result.clarify == null && result.route?.candidates.orEmpty().none { it.clarify != null } &&
            utterance.none(Char::isDigit)

    /** 캐시에 저장할 모양 (서버 응답과 같은 모양이라 [parse] 로 다시 읽음) */
    fun toJson(result: Classified): JSONObject = JSONObject()
        .put("intent", result.intent)
        .put("app", result.app ?: JSONObject.NULL)
        .put(
            "sensitivity",
            JSONObject().put("level", result.level.wire).put("category", result.category).put("reason", result.reason),
        )
        .apply {
            result.route?.let { route ->
                put(
                    "route",
                    JSONObject().put("role", route.role).put("named", route.named).put("exclusive", route.exclusive)
                        .put("ask", route.ask)
                        .put(
                            "candidates",
                            JSONArray(
                                route.candidates.map { c ->
                                    JSONObject().put("appId", c.appId).put("name", c.name)
                                        .put("packages", JSONArray(c.packages)).put("level", c.level.wire)
                                        .apply { c.goalName?.let { put("goal", JSONObject().put("name", it)) } }
                                },
                            ),
                        ),
                )
            }
        }

    /** 깔린 후보만 (후보 순서 그대로). [installed] 는 패키지가 폰에 있는지 */
    fun installedCandidates(route: Route, installed: (String) -> Boolean): List<Pair<Candidate, String>> =
        route.candidates.mapNotNull { c -> c.packages.firstOrNull(installed)?.let { c to it } }

    private fun level(wire: String?): Level? = Level.entries.firstOrNull { it.wire == wire?.trim() }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}
