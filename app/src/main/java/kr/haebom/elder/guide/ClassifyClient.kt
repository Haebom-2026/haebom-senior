package kr.haebom.elder.guide

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kr.haebom.elder.family.Level
import kr.haebom.elder.net.HaebomApi
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * #17 classify 호출기 + 앱 선택 API (haebom-server docs/api/s4-classify.md).
 * - 응답은 메인 스레드로. 4초가 넘으면 null (앱은 폰 규칙으로 진행, 지침서 3.5)
 * - 같은 말은 폰에 캐시한 판단을 다시 씀 ([ClassifyCache]): 서버(LLM) 토큰을 다시 쓰지 않음
 */
internal class ClassifyClient(context: Context, private val api: HaebomApi) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = ClassifyCache(context)
    private var lastCommandId = 0L

    /** 명령 번호: 시각 기반이라 앱을 다시 깔아도 하루 안에 겹치지 않음 (계약서 2장) */
    fun nextCommandId(): Long {
        lastCommandId = maxOf(System.currentTimeMillis(), lastCommandId + 1)
        return lastCommandId
    }

    /** 캐시에 있는 판단 (없으면 null) */
    fun cached(utterance: String): Classified? = cache.get(utterance)

    /**
     * 캐시로 바로 진행한 말도 서버에 명령을 만들어 둠 (기다리지 않음). 서버에 명령이 있어야 앱 선택·지도 안내·끝 신호가 됨.
     * 같은 실행기(한 줄)라 뒤에 부르는 앱 선택 API 는 이 요청이 끝난 뒤에 감
     */
    fun register(commandId: Long, utterance: String, ruleLevel: Level) {
        io.execute {
            val body = JSONObject().put("commandId", commandId).put("utterance", utterance.take(200))
                .put("ruleLevel", ruleLevel.wire).toString()
            val reply = api.call("POST", "/v1/elder/classify", body)
            Log.d(TAG, "classify #17 명령만 만듦 (캐시 사용) → ${reply.code} ${reply.reason ?: ""}")
        }
    }

    fun classify(commandId: Long, utterance: String, ruleLevel: Level, done: (Classified?) -> Unit) {
        var answered = false
        val timeout = Runnable {
            if (!answered) {
                answered = true
                Log.w(TAG, "classify #17 시간 초과 (${TIMEOUT_MS}ms) → 폰 규칙으로")
                done(null)
            }
        }
        main.postDelayed(timeout, TIMEOUT_MS)
        io.execute {
            val started = System.currentTimeMillis()
            val body = JSONObject().put("commandId", commandId).put("utterance", utterance.take(200))
                .put("ruleLevel", ruleLevel.wire).toString()
            val reply = api.call("POST", "/v1/elder/classify", body)
            val result = if (reply.ok) ServerRoute.parse(reply.json()) else null
            Log.d(
                TAG,
                "classify #17 → ${reply.code} ${reply.reason ?: ""} ${result?.let { r ->
                    "${r.intent} ${r.level.wire} ${r.category} role=${r.route?.role} 후보=${r.route?.candidates?.joinToString(",") { it.appId }} " +
                        "clarify=${r.clarify?.questionId}${if (r.degraded) " degraded" else ""}"
                }} (${System.currentTimeMillis() - started}ms)",
            )
            if (result != null && ServerRoute.cacheable(utterance, result)) cache.put(utterance, result)
            main.post {
                if (answered) return@post
                answered = true
                main.removeCallbacks(timeout)
                done(result)
            }
        }
    }

    /**
     * 앱 선택 API: 고른 후보 앱([appId])과 질문 답([answer] = questionId to optionId)을 서버에 알림.
     * 하려는 일 답이면 응답의 새 route 를 [done] 으로 (그 밖에는 응답을 쓰지 않음)
     */
    fun chooseApp(
        commandId: Long,
        appId: String?,
        askedUser: Boolean,
        answer: Pair<String, String>? = null,
        done: ((ServerRoute.Route?) -> Unit)? = null,
    ) {
        io.execute {
            val body = JSONObject().put("installState", "installed").put("askedUser", askedUser)
                .apply {
                    appId?.let { put("appId", it) }
                    answer?.let { (question, option) -> put("answer", JSONObject().put("questionId", question).put("option", option)) }
                }
                .toString()
            // 겹친 요청(409)·jev 시간 초과(503)는 같은 요청을 다시 (계약서 S4 3장, 앱 연동 안내 5장)
            var reply = api.call("POST", "/v1/elder/commands/$commandId/app", body)
            var tries = 1
            while ((reply.code == 409 || reply.code == 503) && tries < RETRIES) {
                Thread.sleep(RETRY_DELAY_MS * tries)
                reply = api.call("POST", "/v1/elder/commands/$commandId/app", body)
                tries++
            }
            Log.d(TAG, "앱 선택 → ${reply.code} ${reply.reason ?: ""} app=${appId ?: "-"} answer=${answer?.second ?: "-"} (${tries}번)")
            val route = reply.json()?.takeIf { reply.ok }?.optJSONObject("route")?.let(ServerRoute::parseRoute)
            done?.let { callback -> main.post { callback(route) } }
        }
    }

    /**
     * 끝 신호 `POST /v1/elder/commands/{id}/end` (S4 8장). [result]: done · stopped_safe · stopped_user · gave_up.
     * done·stopped_safe 이면 [finalScreen](guide 와 같은 화면 객체)을 꼭. 404(지난 명령)·409(이미 끝남)는 버림
     */
    fun end(commandId: Long, result: String, finalScreen: String?, deviceId: String?, lastAction: LastAction?) {
        io.execute {
            val body = JSONObject().put("result", result).apply {
                finalScreen?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { put("finalScreen", it) }
                deviceId?.takeIf(ScreenPayload::isValidDeviceId)?.let { put("deviceId", it) }
                lastAction?.let { put("lastAction", it.toJson()) }
            }.toString()
            var reply = api.call("POST", "/v1/elder/commands/$commandId/end", body)
            if (reply.code == 503 || reply.code == -1) reply = api.call("POST", "/v1/elder/commands/$commandId/end", body)
            Log.d(
                TAG,
                "끝 신호 → ${reply.code} ${reply.reason ?: ""} cmd=$commandId $result 화면=${finalScreen != null}" +
                    (lastAction?.let { " last=${it.seq}:${it.action}" }.orEmpty()),
            )
        }
    }

    private companion object {
        const val TAG = "HaebomGuide"
        const val TIMEOUT_MS = 4_000L
        const val RETRIES = 3
        const val RETRY_DELAY_MS = 400L
    }
}

/** 폰에 두는 #17 판단 캐시: 말 열쇠(해시) → 판단 + 저장 시각. 7일, 최대 200개 (오래된 것부터 지움) */
internal class ClassifyCache(context: Context) {
    private val prefs = context.getSharedPreferences("haebom_classify_cache", Context.MODE_PRIVATE)

    fun get(utterance: String): Classified? {
        val raw = prefs.getString(ServerRoute.cacheKey(utterance), null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (System.currentTimeMillis() - json.optLong("at") > ServerRoute.CACHE_TTL_MS) {
            prefs.edit().remove(ServerRoute.cacheKey(utterance)).apply()
            return null
        }
        return ServerRoute.parse(json.optJSONObject("data"))
    }

    fun put(utterance: String, result: Classified) {
        val editor = prefs.edit()
        val all = prefs.all
        if (all.size >= ServerRoute.CACHE_MAX) {
            // 가장 오래된 것부터 지움
            all.entries.sortedBy { (_, value) -> runCatching { JSONObject(value as String).optLong("at") }.getOrDefault(0L) }
                .take(all.size - ServerRoute.CACHE_MAX + 1)
                .forEach { editor.remove(it.key) }
        }
        val entry = JSONObject().put("at", System.currentTimeMillis()).put("data", ServerRoute.toJson(result))
        editor.putString(ServerRoute.cacheKey(utterance), entry.toString()).apply()
    }
}
