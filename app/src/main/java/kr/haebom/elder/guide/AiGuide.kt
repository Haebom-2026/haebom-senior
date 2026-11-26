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
    /** (guide@v4) in_progress / clarify / off_track / stopped / done */
    val status: String? = null,
    /** (v4) suggest: 칸에 넣을 글자 하나 ("넣기" 단추). 어르신이 직접 쓸 칸이면 없음 */
    val typeText: String? = null,
    /** (v4) say: home / back */
    val nav: String? = null,
    /** (v4) ask: 답 단추 글자 */
    val options: List<String> = emptyList(),
) {
    /** (v4) 송금·결제 확정, 공식이 아닌 사이트, 같은 단계 3번 실패: 여기서 멈추고 직접 확인하시게 */
    val isStopped: Boolean get() = status == "stopped"

    /** 짚을 요소가 있는 응답 */
    val pointsAtTarget: Boolean get() = target != null && (type == HIGHLIGHT || type == SUGGEST)

    /**
     * 할 일을 몰라서 묻는 막연한 질문 ("무엇을 도와드릴까요?"). 앱이 아는 일에서는 앱의 안내로 넘김.
     * 화면 내용에 대한 질문("주소가 지금 사시는 곳과 같나요?")은 의미 있는 질문이라 그대로 씀
     */
    val isGenericAsk: Boolean get() = type == ASK && GENERIC_ASK.any { say.replace(" ", "").contains(it) }

    /** 서버가 모델을 못 써서 대신 보낸 문장 ("서버가 잠시 바빠요" 등): 로컬 안내를 유지하는 게 나음 */
    val isFallback: Boolean get() = type == SAY && FALLBACK_PREFIXES.any { say.startsWith(it) }

    companion object {
        const val HIGHLIGHT = "highlight"
        const val SUGGEST = "suggest"
        const val SWIPE = "swipe"
        const val SAY = "say"
        const val DONE = "done"
        const val ASK = "ask"

        /**
         * 서버가 모델 대신 보내는 문장들. "이 화면은 직접 입력해 주세요" 는 서버가 화면 글자에서 '주민등록번호' 같은 말만 봐도
         * 보내는데(안내 문구 포함), 앱은 실제 비밀번호 칸이 있으면 이미 서버에 보내지 않으므로 앱의 안내로 계속함
         */
        private val GENERIC_ASK = listOf("무엇을도와", "어떤일을", "어떤서류", "어떤서비스", "무엇을하고", "어떤것을", "어떤영상", "무엇을찾")

        private val FALLBACK_PREFIXES = listOf(
            "서버가 잠시 바빠요", "제가 화면을 잘 못 알아봤어요", "지금 화면을 읽지 못했어요", "이 화면은 직접 입력해 주세요",
        )

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
                status = json.optString("status").takeIf { it.isNotBlank() },
                typeText = json.optString("typeText").takeIf { it.isNotBlank() },
                nav = json.optString("nav").takeIf { it.isNotBlank() },
                options = json.optJSONArray("options")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
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
        mode: String,
        goal: String,
        utterance: String,
        slots: Map<String, String>,
        screen: String,
        history: List<String>,
        deviceId: String?,
        userReply: String,
        done: (requestId: Int, outcome: AiOutcome) -> Unit,
    ): Int {
        val id = ++lastRequestId
        val body = ScreenPayload.guide(id, goal, utterance, slots, screen, history, deviceId, mode, userReply)
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
