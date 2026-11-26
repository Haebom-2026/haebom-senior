package kr.haebom.elder.guide

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kr.haebom.elder.net.HaebomApi
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 화면 안내 AI(#16 `POST /v1/elder/guide`) 응답 하나. 계약서: haebom-server docs/api/s5-guide.md
 * [type]: highlight · suggest · swipe · ask · say · done
 */
internal data class AiReply(
    val requestId: Int,
    val type: String,
    val say: String,
    val target: Int? = null,
    val targetText: String? = null,
    val chips: List<String> = emptyList(),
    val dir: String? = null,
    val seekText: String? = null,
) {
    /** 짚을 요소가 있는 응답 */
    val pointsAtTarget: Boolean get() = target != null && (type == HIGHLIGHT || type == SUGGEST)

    /** 서버가 모델을 못 써서 대신 보낸 문장 ("서버가 잠시 바빠요" 등): 로컬 안내를 유지하는 게 나음 */
    val isFallback: Boolean get() = type == SAY && FALLBACK_PREFIXES.any { say.startsWith(it) }

    companion object {
        const val HIGHLIGHT = "highlight"
        const val SUGGEST = "suggest"
        const val SWIPE = "swipe"
        const val SAY = "say"
        const val DONE = "done"

        private val FALLBACK_PREFIXES = listOf("서버가 잠시 바빠요", "제가 화면을 잘 못 알아봤어요", "지금 화면을 읽지 못했어요")

        fun parse(body: String): AiReply? = runCatching {
            val json = JSONObject(body)
            val type = json.optString("type")
            if (type.isEmpty()) return null
            AiReply(
                requestId = json.getInt("requestId"),
                type = type,
                say = json.optString("say").trim(),
                target = if (json.has("target")) json.getInt("target") else null,
                targetText = json.optString("targetText").takeIf { it.isNotBlank() },
                chips = json.optJSONArray("chips")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
                dir = json.optString("dir").takeIf { it.isNotBlank() },
                seekText = json.optString("seekText").takeIf { it.isNotBlank() },
            )
        }.getOrNull()

        /**
         * 응답의 [target] 이 지금 화면의 어느 요소인지. 번호가 같아도 글자가 다르면 다른 요소로 봄.
         * 번호로 못 찾으면 [targetText] 와 글자가 같은 요소. 없으면 null (응답을 버림)
         */
        fun findTarget(reply: AiReply, nodes: List<ScreenNode>): ScreenNode? {
            val index = reply.target ?: return null
            val wanted = reply.targetText
            val byIndex = nodes.getOrNull(index)
            if (byIndex != null && (wanted == null || label(byIndex) == null || label(byIndex) == wanted)) return byIndex
            if (wanted == null) return null
            val same = nodes.filter { label(it) == wanted }
            return same.firstOrNull { it.clickable } ?: same.firstOrNull()
        }

        private fun label(node: ScreenNode): String? =
            node.text?.trim()?.takeIf { it.isNotEmpty() } ?: node.description?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** AI 요청 결과: 응답 또는 실패 이유 */
internal sealed class AiOutcome {
    data class Reply(val reply: AiReply) : AiOutcome()
    data class Failed(val reason: String) : AiOutcome()
}

/**
 * #16 호출기. 요청마다 requestId 를 올리고, 결과는 메인 스레드로 돌려줌.
 * 늦은 응답 버리기(requestId·화면 지문)는 부르는 쪽([GuideService])에서 함.
 */
internal class AiGuideClient(private val api: HaebomApi) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastRequestId = 0

    fun request(
        goal: String,
        utterance: String,
        slots: Map<String, String>,
        screen: String,
        history: List<String>,
        deviceId: String?,
        done: (requestId: Int, outcome: AiOutcome) -> Unit,
    ): Int {
        val id = ++lastRequestId
        val body = ScreenPayload.guide(id, goal, utterance, slots, screen, history, deviceId)
        io.execute {
            val started = SystemClock.uptimeMillis()
            val reply = api.call("POST", "/v1/elder/guide", body)
            val elapsed = SystemClock.uptimeMillis() - started
            val parsed = if (reply.ok) AiReply.parse(reply.body) else null
            val outcome = when {
                !reply.ok -> AiOutcome.Failed("HTTP ${reply.code} ${reply.reason.orEmpty()}".trim())
                elapsed > TIMEOUT_MS -> AiOutcome.Failed("timeout ${elapsed}ms")
                parsed == null -> AiOutcome.Failed("bad-body")
                else -> AiOutcome.Reply(parsed)
            }
            val summary = when (outcome) {
                is AiOutcome.Reply -> outcome.reply.let { "${it.type} target=${it.target} '${it.say}'" }
                is AiOutcome.Failed -> outcome.reason
            }
            Log.i(TAG, "AI #$id $goal → $summary (${elapsed}ms)")
            main.post { done(id, outcome) }
        }
        return id
    }

    companion object {
        /** 이보다 늦은 응답은 버리고 로컬 안내를 유지 (서버 모델 타임아웃 8.5초) */
        const val TIMEOUT_MS = 9_500L
        const val TAG = "HaebomGuide"
    }
}
