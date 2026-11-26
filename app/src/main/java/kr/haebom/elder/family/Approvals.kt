package kr.haebom.elder.family

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.Instant

/**
 * 보호자 승인 (서버 S3, 계약서 haebom-server docs/api/s3-approvals.md).
 * 거래 승인이 아니라 "해봄이 계속 도와 드려도 되는지" 가족에게 묻는 것.
 *   #9  POST /v1/elder/approvals             요청 (created / existing / no_guardians)
 *   #10 POST /v1/elder/approvals/{id}/cancel  취소 ([기다리지 않기], 새 명령, 그만)
 *   #19 GET  /v1/elder/approvals/{id}         결과 (대기 중 3초마다)
 *   FCM data { kind: "approval", id, status, respondedBy?, respondedByLabel? } 결과 (먼저 오면 그것으로)
 */
internal enum class ApprovalStatus {
    PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED;

    companion object {
        fun of(wire: String?): ApprovalStatus? = entries.firstOrNull { it.name.equals(wire?.trim(), ignoreCase = true) }
    }
}

/** 승인 하나의 지금 상태. [respondedByLabel] 은 대답한 가족을 어르신이 부르는 호칭 (허락·거절일 때만) */
internal data class ApprovalState(
    val id: String,
    val status: ApprovalStatus,
    val respondedByLabel: String? = null,
)

/** #9 결과 */
internal sealed class ApprovalStart {
    /** 새 요청이거나(created) 같은 일이 이미 대기 중(existing): 그 요청을 기다림 */
    data class Waiting(val id: String, val expiresAt: Long, val existing: Boolean) : ApprovalStart()
    object NoGuardians : ApprovalStart()
    object Failed : ApprovalStart()
}

internal object Approvals {
    /** 정부24 본인인증 화면 */
    const val ACTION_GOV24_AUTH = "guide_gov24_auth"
    /** 은행 앱 열기 전 (금융 경고 카드) */
    const val ACTION_BANK_OPEN = "guide_bank_open"

    /** 대기 중 #19 간격 */
    const val POLL_MS = 3_000L
    /** expiresAt 이 지나고 이만큼 더 기다리면 서버와 관계없이 끝 */
    const val GRACE_MS = 5_000L
    /** 허락 받은 뒤 이어서 안내하기까지 (안내 문장을 들을 시간) */
    const val RESUME_DELAY_MS = 2_500L

    private const val TAG = "HaebomFamily"
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** 결과를 받는 곳 (화면 안내 서비스). 메인 스레드에서 부름 */
    @Volatile
    var listener: ((ApprovalState) -> Unit)? = null

    /**
     * 대답한 가족을 부르는 말 ("큰딸 님"). 호칭·이름이 모두 비어 서버가 respondedByLabel 을 빼면 "보호자 님"
     * (S1 의 guardianLabel → guardianName → "보호자" 와 같은 순서, 서버 담당 2026-11-27)
     */
    fun responderName(respondedByLabel: String?): String =
        (respondedByLabel?.trim()?.takeIf { it.isNotEmpty() } ?: FamilyLink.DEFAULT_GUARDIAN_LABEL) + " 님"

    /** FCM data 메시지 → 결과 (모르는 상태·id 없음은 null) */
    fun fromPush(data: Map<String, String>): ApprovalState? {
        val id = data["id"]?.takeIf { it.isNotBlank() } ?: return null
        val status = ApprovalStatus.of(data["status"]) ?: return null
        return ApprovalState(id, status, data["respondedByLabel"]?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun onPush(data: Map<String, String>) {
        val state = fromPush(data) ?: return
        Log.d(TAG, "승인 결과 FCM: ${state.status}")
        main.post { listener?.invoke(state) }
    }

    /** #9 응답 → 결과. 성공 응답인데 id·만료 시각이 없으면 실패로 봄 */
    fun parseStart(ok: Boolean, json: org.json.JSONObject?): ApprovalStart {
        if (!ok || json == null) return ApprovalStart.Failed
        return when (val result = json.optString("result")) {
            "created", "existing" -> {
                val id = json.optString("approvalId").takeIf { it.isNotBlank() } ?: return ApprovalStart.Failed
                val expiresAt = parseTime(json.optString("expiresAt")) ?: return ApprovalStart.Failed
                ApprovalStart.Waiting(id, expiresAt, existing = result == "existing")
            }
            "no_guardians" -> ApprovalStart.NoGuardians
            else -> ApprovalStart.Failed
        }
    }

    /** #19 (그리고 #10 200) 응답 → 상태 */
    fun parseState(json: org.json.JSONObject?): ApprovalState? {
        val id = json?.optString("id")?.takeIf { it.isNotBlank() } ?: return null
        val status = ApprovalStatus.of(json.optString("status")) ?: return null
        return ApprovalState(id, status, json.optString("respondedByLabel").trim().takeIf { it.isNotEmpty() })
    }

    private fun parseTime(text: String): Long? = runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}

/**
 * 기다리는 승인 하나. FCM·#19·앱 시계 중 먼저 온 끝난 결과 하나만 받음.
 * (같은 결과가 FCM 과 #19 로 두 번 와도 안내는 한 번)
 */
internal class ApprovalWait(val id: String, val expiresAt: Long) {
    var finished = false
        private set

    /** 이 요청의 끝난 상태면 처음 한 번만 돌려줌 (대기 중·다른 요청은 null) */
    fun accept(state: ApprovalState): ApprovalState? {
        if (finished || state.id != id || state.status == ApprovalStatus.PENDING) return null
        finished = true
        return state
    }

    /** expiresAt + 5초가 지나면 서버 대답 없이 만료로 끝냄 (계약서 2장 #19) */
    fun expireIfLate(nowMs: Long): ApprovalState? {
        if (finished || nowMs < expiresAt + Approvals.GRACE_MS) return null
        finished = true
        return ApprovalState(id, ApprovalStatus.EXPIRED)
    }

    /** 어르신이 기다리지 않기로 함 (뒤에 오는 결과는 버림) */
    fun cancel() {
        finished = true
    }
}
