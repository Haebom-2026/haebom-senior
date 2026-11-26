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
 *        PATCH  /v1/elder/links/{guardianId}  고령자가 부르는 보호자 호칭(guardianLabel) 바꾸기
 *   #6  POST   /v1/elder/sensitive-entries   민감 앱 알림 (S2). 201 의 guardians 로 "{보호자}에게 알렸어요"
 *   #7  POST   /v1/elder/stuck               막힘 (S2)
 *   #8  POST   /v1/elder/sos                 SOS (S2, category scam 이면 L3)
 *   #9  POST   /v1/elder/approvals           보호자 승인 요청 (S3)
 *   #10 POST   /v1/elder/approvals/{id}/cancel  승인 기다리기 취소 (S3)
 *   #19 GET    /v1/elder/approvals/{id}      승인 결과 (S3, 대기 중 3초마다. 결과 FCM 이 먼저 오면 그것으로)
 * 연결이 바뀌면 서버가 FCM data { kind: "link" } 를 보내고, [HaebomMessagingService] 가 [refresh] 를 부릅니다.
 */
internal class ServerFamilyGateway(
    context: Context,
    private val api: HaebomApi,
) : FamilyGateway {
    private val settings = ElderSettings(context)
    private val ownPackage = context.packageName
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
        val clean = label.trim().take(MAX_LABEL)
        io.execute {
            val body = JSONObject().put("guardianLabel", clean).toString()
            val reply = api.call("PATCH", "/v1/elder/links/$guardianId", body)
            Log.d(TAG, "호칭 변경 → ${reply.code} ${reply.reason.orEmpty()}")
            val updated = reply.json()?.takeIf { reply.ok }?.let { parseLink(api.uid().orEmpty(), it) }
            main.post {
                if (updated != null) {
                    // 서버에 저장됨: 예전에 폰에만 저장해 둔 호칭은 지움 (서버 값이 기준)
                    settings.setLabelOverride(guardianId, "")
                    cached = cached.map { if (it.guardianId == guardianId) updated else it }
                    notifyListeners()
                }
                done(updated != null)
            }
        }
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

    override fun reportSensitiveEntry(
        appName: String,
        packageName: String,
        category: String,
        level: String,
        reason: String,
        done: (NoticeOutcome) -> Unit,
    ) {
        io.execute {
            // packageName·appName 은 서버 필수값. 앱을 특정하지 못한 말(예: 은행 앱이 없음)은 해봄에서 온 요청으로 보냄
            val body = JSONObject()
                .put("packageName", packageName.ifBlank { ownPackage }.take(200))
                .put("appName", appName.ifBlank { "해봄" }.take(100))
                .put("category", category).put("level", level).put("reason", reason)
                .toString()
            val reply = api.call("POST", "/v1/elder/sensitive-entries", body, UUID.randomUUID().toString())
            val outcome = noticeOutcome(reply)
            Log.d(TAG, "민감 알림 #6 ($level $category) → ${reply.code} ${reply.reason ?: ""} ${outcome.result}")
            main.post { done(outcome) }
        }
    }

    override fun reportSos(appName: String, packageName: String, category: String?, reason: String, done: (NoticeOutcome) -> Unit) {
        io.execute {
            val body = JSONObject()
                .put("packageName", packageName.take(200)).put("appName", appName.take(100))
                .put("reason", reason.take(100))
                .apply { category?.let { put("category", it) } }
                .toString()
            val reply = api.call("POST", "/v1/elder/sos", body, UUID.randomUUID().toString())
            val outcome = noticeOutcome(reply)
            Log.d(TAG, "SOS #8 (${category ?: "-"}) → ${reply.code} ${reply.reason ?: ""} ${outcome.result}")
            main.post { done(outcome) }
        }
    }

    override fun reportStuck(appName: String, packageName: String, done: (NoticeOutcome) -> Unit) {
        io.execute {
            val body = JSONObject().put("packageName", packageName.take(200)).put("appName", appName.take(100)).toString()
            val reply = api.call("POST", "/v1/elder/stuck", body, UUID.randomUUID().toString())
            val outcome = noticeOutcome(reply)
            Log.d(TAG, "막힘 #7 → ${reply.code} ${reply.reason ?: ""} ${outcome.result}")
            main.post { done(outcome) }
        }
    }

    override fun requestApproval(
        appName: String,
        packageName: String,
        action: String,
        category: String,
        done: (ApprovalStart) -> Unit,
    ) {
        io.execute {
            val body = JSONObject()
                .put("packageName", packageName.ifBlank { ownPackage }.take(200))
                .put("appName", appName.ifBlank { "해봄" }.take(100))
                .put("action", action).put("category", category)
                .toString()
            val reply = api.call("POST", "/v1/elder/approvals", body, UUID.randomUUID().toString())
            val start = Approvals.parseStart(reply.ok, reply.json())
            Log.d(TAG, "승인 요청 #9 ($action) → ${reply.code} ${reply.reason ?: ""} ${start.javaClass.simpleName}")
            main.post { done(start) }
        }
    }

    override fun approvalStatus(id: String, done: (ApprovalState?) -> Unit) {
        io.execute {
            val reply = api.call("GET", "/v1/elder/approvals/$id")
            val state = Approvals.parseState(reply.json()?.takeIf { reply.ok })
            if (state == null) Log.w(TAG, "승인 조회 #19 → ${reply.code} ${reply.reason ?: ""}")
            main.post { done(state) }
        }
    }

    override fun cancelApproval(id: String) {
        io.execute {
            val reply = api.call("POST", "/v1/elder/approvals/$id/cancel")
            // 409(이미 대답·만료·취소)·404 는 무시
            Log.d(TAG, "승인 취소 #10 → ${reply.code} ${reply.reason ?: ""}")
        }
    }

    /** #6·#7·#8 응답 → 결과. 201 의 guardians 로 이름을, 없으면(중복·이전 서버) 지금 목록의 호칭 */
    private fun noticeOutcome(reply: HaebomApi.Reply): NoticeOutcome {
        val json = reply.json()
        val labels = notifiedLabels(json).ifEmpty { links().map { it.labelForElder } }
        return when (json?.optString("result")) {
            "created" -> NoticeOutcome(NoticeResult.CREATED, labels)
            "duplicate_suppressed" -> NoticeOutcome(NoticeResult.DUPLICATE_SUPPRESSED, labels)
            "no_guardians" -> NoticeOutcome(NoticeResult.NO_GUARDIANS)
            else -> NoticeOutcome(NoticeResult.FAILED)
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
        private const val MAX_LABEL = 20

        /** #6·#7·#8 의 201 `guardians` → 어르신이 부르는 호칭 목록 (guardianLabel → guardianName → "보호자") */
        fun notifiedLabels(json: JSONObject?): List<String> {
            val array = json?.optJSONArray("guardians") ?: return emptyList()
            return (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                FamilyLink(
                    elderId = "",
                    guardianId = item.optString("guardianId"),
                    elderName = "",
                    elderLabel = null,
                    guardianLabel = item.optString("guardianLabel").ifEmpty { null },
                    connectedAt = 0L,
                    guardianName = item.optString("guardianName").ifEmpty { null },
                ).labelForElder
            }
        }

        /** #18 응답 → FamilyLink 목록 (서버 시각은 ISO 8601) */
        fun parseLinks(elderId: String, json: JSONObject?): List<FamilyLink> {
            val array = json?.optJSONArray("links") ?: return emptyList()
            return (0 until array.length()).map { i -> parseLink(elderId, array.getJSONObject(i)) }
        }

        /** #18 항목 하나 (호칭 변경 PATCH 응답도 같은 모양) */
        fun parseLink(elderId: String, item: JSONObject): FamilyLink = FamilyLink(
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
