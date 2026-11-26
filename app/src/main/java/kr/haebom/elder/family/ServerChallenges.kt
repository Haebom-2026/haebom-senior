package kr.haebom.elder.family

import android.os.Handler
import android.os.Looper
import android.util.Log
import kr.haebom.elder.net.HaebomApi
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 실제 서버로 가족의 도전 (S4 제안). 서버에 아직 경로가 없으면(404 등) 목록은 빈 채로 두고 홈 카드도 보이지 않음.
 * 보내는 것은 누른 단추와 withHelp 뿐 (화면·입력 내용은 보내지 않음).
 */
internal class ServerChallenges(private val api: HaebomApi, private val settings: ElderSettings) : ChallengeGateway {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    @Volatile
    private var list: List<Challenge> = emptyList()

    override fun cached(): List<Challenge> = list

    override fun refresh(done: (List<Challenge>?) -> Unit) {
        io.execute {
            val reply = api.call("GET", "/v1/elder/challenges")
            Log.d(TAG, "도전 목록 → ${reply.code} ${reply.reason.orEmpty()}")
            val parsed = if (reply.ok) Challenges.parse(reply.json()) else null
            main.post {
                if (parsed != null) {
                    list = parsed
                    // 보호자가 취소했거나(change: cancelled) 목록에서 사라진 도전은 폰에 둔 기록도 지움
                    settings.keepChallenges(parsed.filter { it.open }.map { it.id }.toSet())
                    listeners.toList().forEach { it() }
                }
                done(parsed)
            }
        }
    }

    override fun observe(listener: () -> Unit): AutoCloseable {
        listeners += listener
        listener()
        return AutoCloseable { listeners -= listener }
    }

    override fun accept(id: String, done: (ChallengeResult) -> Unit) = send(id, "accept", null, done)

    override fun later(id: String, done: (ChallengeResult) -> Unit) = send(id, "later", null, done)

    override fun done(id: String, withHelp: Boolean, done: (ChallengeResult) -> Unit) =
        send(id, "done", JSONObject().put("withHelp", withHelp).toString(), done)

    private fun send(id: String, action: String, body: String?, done: (ChallengeResult) -> Unit) {
        io.execute {
            val reply = api.call("POST", "/v1/elder/challenges/$id/$action", body ?: "{}", UUID.randomUUID().toString())
            Log.d(TAG, "도전 $action → ${reply.code} ${reply.reason.orEmpty()}")
            val result = when {
                reply.ok -> ChallengeResult.OK
                reply.reason == "failed-precondition" || reply.code == 409 -> ChallengeResult.NOT_ALLOWED
                else -> ChallengeResult.FAILED
            }
            main.post { done(result) }
            if (result != ChallengeResult.FAILED) refresh()
        }
    }

    private companion object {
        const val TAG = "HaebomChallenge"
    }
}
