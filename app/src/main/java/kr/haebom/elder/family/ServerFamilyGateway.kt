package kr.haebom.elder.family

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import kr.haebom.elder.net.HaebomApi
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 실제 서버(S1)로 보호자 연동. 계약서: haebom-server docs/api/s1-users-links.md
 *   #2  POST   /v1/users/me                  시작할 때 고령자로 등록
 *   #4  PUT    /v1/devices/{deviceId}        FCM 토큰 등록 (연결 변경 알림 받기)
 *   #11 POST   /v1/elder/connect-codes       연결 코드
 *   #12 DELETE /v1/elder/links/{guardianId}  고령자 쪽 해제
 *   #18 GET    /v1/elder/links               연결된 보호자 목록
 *   #6  POST   /v1/elder/sensitive-entries   민감 앱 알림 (S2, 서버 배포 전이면 실패로 안내)
 * 연결이 바뀌면 서버가 FCM data { kind: "link" } 를 보내고, [HaebomMessagingService] 가 [refresh] 를 부릅니다.
 */
internal class ServerFamilyGateway(
    context: Context,
    private val api: HaebomApi,
) : FamilyGateway {
    private val settings = ElderSettings(context)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<() -> Unit>()
    private var cached: List<FamilyLink> = emptyList()
    private var code: ConnectionCode? = null
    private var started = false

    override val backendName = "실제 서버"

    /** 서버 #12 가 해제 확인 코드를 받기 전까지는 확인 창만 띄우고 끊음 */
    override val requiresReleaseCode = false

    /** 프로필 등록(#2)과 기기 등록(#4)을 한 번 하고 목록을 받아 옴 */
    fun start() {
        if (started) return
        started = true
        io.execute {
            api.ensureProfile()
            registerDeviceNow(null)
        }
        refresh()
    }

    override fun observe(listener: () -> Unit): AutoCloseable {
        listeners += listener
        listener()
        refresh()
        return AutoCloseable { listeners -= listener }
    }

    override fun links(): List<FamilyLink> = cached.map { link ->
        settings.labelOverride(link.guardianId)?.takeIf { it.isNotBlank() }?.let { link.copy(guardianLabel = it) } ?: link
    }

    override fun activeCode(): ConnectionCode? = code?.takeIf { it.expiresAt > System.currentTimeMillis() }

    override fun refresh() {
        io.execute {
            val reply = api.call("GET", "/v1/elder/links")
            if (!reply.ok) {
                Log.w(TAG, "목록 #18 → ${reply.code} ${reply.reason ?: reply.body.take(80)}")
                return@execute
            }
            val elderId = api.uid().orEmpty()
            val list = parseLinks(elderId, reply.json())
            main.post {
                cached = list
                notifyListeners()
            }
        }
    }

    override fun issueCode(done: (ConnectionCode?) -> Unit) {
        io.execute {
            val reply = api.call("POST", "/v1/elder/connect-codes", "{}", UUID.randomUUID().toString())
            val issued = reply.json()?.takeIf { reply.ok }?.let {
                ConnectionCode(it.getString("code"), Instant.parse(it.getString("expiresAt")).toEpochMilli())
            }
            Log.d(TAG, "연결 코드 #11 → ${reply.code}")
            main.post {
                if (issued != null) {
                    code = issued
                    notifyListeners()
                }
                done(issued)
            }
        }
    }

    override fun renameGuardian(guardianId: String, label: String, done: (Boolean) -> Unit) {
        // 서버에 고령자 쪽 호칭 변경 API 가 아직 없어 폰에 저장 (서버에 요청해 둠)
        settings.setLabelOverride(guardianId, label)
        notifyListeners()
        main.post { done(true) }
    }

    override fun disconnect(guardianId: String, releaseCode: String, done: (DisconnectResult) -> Unit) {
        io.execute {
            val reply = api.call("DELETE", "/v1/elder/links/$guardianId")
            Log.d(TAG, "해제 #12 → ${reply.code}")
            val result = when {
                reply.ok || reply.code == 404 -> DisconnectResult.DISCONNECTED // 404 = 이미 해제됨
                else -> DisconnectResult.FAILED
            }
            main.post {
                if (result == DisconnectResult.DISCONNECTED) {
                    cached = cached.filterNot { it.guardianId == guardianId }
                    notifyListeners()
                }
                done(result)
            }
            if (result == DisconnectResult.DISCONNECTED) refresh()
        }
    }

    override fun reportSensitiveEntry(appName: String, packageName: String, category: String, done: (NoticeOutcome) -> Unit) {
        io.execute {
            val body = JSONObject().put("packageName", packageName).put("appName", appName).put("category", category).toString()
            val reply = api.call("POST", "/v1/elder/sensitive-entries", body, UUID.randomUUID().toString())
            val labels = links().map { it.labelForElder }
            val outcome = when (reply.json()?.optString("result")) {
                "created" -> NoticeOutcome(NoticeResult.CREATED, labels)
                "duplicate_suppressed" -> NoticeOutcome(NoticeResult.DUPLICATE_SUPPRESSED, labels)
                "no_guardians" -> NoticeOutcome(NoticeResult.NO_GUARDIANS)
                else -> NoticeOutcome(NoticeResult.FAILED)
            }
            Log.d(TAG, "민감 알림 #6 → ${reply.code} ${outcome.result}")
            main.post { done(outcome) }
        }
    }

    /** #4: FCM 토큰 등록. 토큰이 바뀌었을 때도 부름 */
    fun registerDevice(token: String? = null) {
        io.execute { registerDeviceNow(token) }
    }

    private fun registerDeviceNow(knownToken: String?) {
        val token = knownToken ?: runCatching {
            Tasks.await(FirebaseMessaging.getInstance().token, 10, TimeUnit.SECONDS)
        }.getOrNull()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "FCM 토큰을 받지 못해 기기 등록을 건너뜀")
            return
        }
        val body = JSONObject().put("fcmToken", token).put("platform", "android").toString()
        val reply = api.call("PUT", "/v1/devices/${settings.deviceId}", body)
        Log.d(TAG, "기기 등록 #4 → ${reply.code}")
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    companion object {
        private const val TAG = "HaebomFamily"

        /** #18 응답 → FamilyLink 목록 (서버 시각은 ISO 8601) */
        fun parseLinks(elderId: String, json: JSONObject?): List<FamilyLink> {
            val array = json?.optJSONArray("links") ?: return emptyList()
            return (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                FamilyLink(
                    elderId = elderId,
                    guardianId = item.getString("guardianId"),
                    elderName = "",
                    elderLabel = item.optString("elderLabel").ifEmpty { null },
                    guardianLabel = item.optString("guardianLabel").ifEmpty { null },
                    connectedAt = runCatching { Instant.parse(item.getString("connectedAt")).toEpochMilli() }.getOrDefault(0L),
                    guardianName = item.optString("guardianName").ifEmpty { null },
                )
            }
        }
    }
}
