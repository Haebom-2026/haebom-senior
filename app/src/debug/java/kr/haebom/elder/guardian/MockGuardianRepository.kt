package kr.haebom.elder.guardian

import kr.haebom.elder.family.ApprovalStatus
import kr.haebom.elder.family.ConnectionCode
import kr.haebom.elder.family.DisconnectResult
import kr.haebom.elder.family.Family
import kr.haebom.elder.family.FamilyLink
import kr.haebom.elder.family.GuardianApproval
import kr.haebom.elder.family.NoticeOutcome
import kr.haebom.elder.family.NoticeResult
import kr.haebom.elder.family.SensitiveNotice
import java.security.SecureRandom

/**
 * 서버 대신 쓰는 기기 안 메모리 백엔드 (debug 전용).
 * 고령자 쪽(#11 #12 #6 #9 #10)과 보호자 쪽(#13 #14 #15, 해제 확인 코드) 동작을 한곳에서 흉내 냅니다.
 */
internal class MockGuardianRepository(
    private val now: () -> Long = System::currentTimeMillis,
    private val nextCode: () -> Int = SecureRandom()::nextInt,
) {
    private val links = mutableMapOf<Pair<String, String>, FamilyLink>()
    private val codes = mutableMapOf<String, ConnectionCode>()
    private val reservedCodes = mutableMapOf<String, Long>()
    private val releaseCodes = mutableMapOf<Pair<String, String>, ConnectionCode>()
    private val releaseFailures = mutableMapOf<Pair<String, String>, Int>()
    private val approvals = mutableMapOf<String, GuardianApproval>()
    private val notices = mutableListOf<SensitiveNotice>()
    private val lastNoticeAt = mutableMapOf<Pair<String, String>, Long>()
    private val listeners = mutableSetOf<() -> Unit>()
    private var nextApprovalId = 0
    private var nextNoticeId = 0

    fun observe(listener: () -> Unit): AutoCloseable {
        listeners.add(listener)
        listener()
        return AutoCloseable { listeners.remove(listener) }
    }

    fun linksForElder(elderId: String): List<FamilyLink> =
        links.values.filter { it.elderId == elderId }.sortedBy { it.connectedAt }

    fun linksForGuardian(guardianId: String): List<FamilyLink> =
        links.values.filter { it.guardianId == guardianId }.sortedBy { it.connectedAt }

    // ───────────── 연결 코드 (#11 고령자 발급, #14 보호자 입력) ─────────────

    fun issueCode(elderId: String): ConnectionCode {
        val code = newCode()
        codes[elderId] = code
        notifyObservers()
        return code
    }

    /** 아직 쓰지 않았고 시간이 남은 연결 코드 */
    fun activeCode(elderId: String): ConnectionCode? = codes[elderId]?.takeIf { it.expiresAt > now() }

    fun connect(
        code: String,
        guardianId: String,
        elderLabel: String?,
        guardianLabel: String?,
        guardianName: String? = null,
    ): FamilyLink? {
        val currentTime = now()
        val issued = codes.entries.firstOrNull {
            it.value.code == code.trim() && it.value.expiresAt > currentTime
        } ?: return null

        val elderId = issued.key
        val key = elderId to guardianId
        if (key in links) return null
        val connection = FamilyLink(
            elderId = elderId,
            guardianId = guardianId,
            elderName = elderId,
            elderLabel = elderLabel,
            guardianLabel = guardianLabel,
            connectedAt = currentTime,
            guardianName = guardianName,
        )
        codes.remove(elderId)
        links[key] = connection
        notifyObservers()
        return connection
    }

    // ───────────── 호칭 ─────────────

    /** 고령자가 보호자 호칭(guardianLabel)을 바꿈. 보호자 앱의 어르신 호칭에는 영향 없음 */
    fun renameGuardian(elderId: String, guardianId: String, label: String): Boolean {
        val key = elderId to guardianId
        val link = links[key] ?: return false
        links[key] = link.copy(guardianLabel = label.trim().ifEmpty { null })
        notifyObservers()
        return true
    }

    /**
     * 승인·거절한 보호자를 고령자 앱 호칭으로 바꿈 ("큰딸이 허락했어요").
     * 응답 뒤에 연결이 해제됐으면 기본 호칭을 씁니다.
     */
    fun guardianLabelForElder(elderId: String, guardianId: String?): String =
        guardianId?.let { links[elderId to it]?.labelForElder } ?: FamilyLink.DEFAULT_GUARDIAN_LABEL

    // ───────────── 해제 ─────────────

    /** 보호자 쪽 해제 (#15): 확인 없이 바로 끊음 */
    fun disconnect(elderId: String, guardianId: String): Boolean {
        val removed = links.remove(elderId to guardianId) != null
        releaseCodes.remove(elderId to guardianId)
        if (removed) {
            cancelOrphanApprovals(elderId)
            notifyObservers()
        }
        return removed
    }

    /** 보호자 앱이 보여 주는 해제 확인 코드 (6자리, 10분, 일회용). 고령자가 이 코드를 입력해야 해제됨 */
    fun issueReleaseCode(elderId: String, guardianId: String): ConnectionCode? {
        val key = elderId to guardianId
        if (key !in links) return null
        val code = newCode()
        releaseCodes[key] = code
        releaseFailures.remove(key)
        notifyObservers()
        return code
    }

    fun activeReleaseCode(elderId: String, guardianId: String): ConnectionCode? =
        releaseCodes[elderId to guardianId]?.takeIf { it.expiresAt > now() }

    /** 고령자 쪽 해제 (#12): 해제 확인 코드가 맞을 때만. 5번 틀리면 그 코드는 무효 */
    fun disconnectByElder(elderId: String, guardianId: String, code: String): DisconnectResult {
        val key = elderId to guardianId
        if (key !in links) return DisconnectResult.NOT_LINKED
        val expected = activeReleaseCode(elderId, guardianId)
        if (expected == null || expected.code != code.trim()) {
            val failures = (releaseFailures[key] ?: 0) + 1
            releaseFailures[key] = failures
            if (failures >= MAX_RELEASE_FAILURES) releaseCodes.remove(key)
            return DisconnectResult.WRONG_CODE
        }
        disconnect(elderId, guardianId)
        return DisconnectResult.DISCONNECTED
    }

    // ───────────── 민감 앱 알림 (#6) ─────────────

    fun reportSensitiveEntry(elderId: String, appName: String, packageName: String, category: String): NoticeOutcome {
        val guardians = linksForElder(elderId)
        if (guardians.isEmpty()) return NoticeOutcome(NoticeResult.NO_GUARDIANS)
        val currentTime = now()
        val key = elderId to packageName
        val last = lastNoticeAt[key]
        if (last != null && currentTime - last < Family.NOTICE_COOLDOWN_MS) {
            return NoticeOutcome(NoticeResult.DUPLICATE_SUPPRESSED, guardians.map { it.labelForElder })
        }
        lastNoticeAt[key] = currentTime
        notices += SensitiveNotice(
            id = "mock-notice-${++nextNoticeId}",
            elderId = elderId,
            guardianIds = guardians.mapTo(mutableSetOf()) { it.guardianId },
            appName = appName,
            packageName = packageName,
            category = category,
            createdAt = currentTime,
        )
        notifyObservers()
        return NoticeOutcome(NoticeResult.CREATED, guardians.map { it.labelForElder })
    }

    /** 보호자 알림함 (최신순). 해제된 어르신의 알림은 보이지 않음 */
    fun noticesForGuardian(guardianId: String): List<SensitiveNotice> =
        notices.filter { guardianId in it.guardianIds && (it.elderId to guardianId) in links }
            .sortedByDescending { it.createdAt }

    // ───────────── 승인 (#9 #10 #13) ─────────────

    fun requestApproval(
        elderId: String,
        appName: String,
        packageName: String,
        action: String,
        category: String,
    ): GuardianApproval? {
        val guardians = linksForElder(elderId).mapTo(mutableSetOf()) { it.guardianId }
        if (guardians.isEmpty()) return null
        val currentTime = now()
        val request = GuardianApproval(
            id = "mock-approval-${++nextApprovalId}",
            elderId = elderId,
            guardianIds = guardians,
            appName = appName,
            packageName = packageName,
            action = action,
            category = category,
            status = ApprovalStatus.PENDING,
            createdAt = currentTime,
            expiresAt = currentTime + APPROVAL_TTL,
        )
        approvals[request.id] = request
        notifyObservers()
        return request
    }

    fun approval(id: String): GuardianApproval? = approvals[id]

    fun respond(id: String, guardianId: String, approve: Boolean): GuardianApproval? {
        val currentTime = now()
        expireDueApprovals(currentTime)
        val request = approvals[id] ?: return null
        if (request.status != ApprovalStatus.PENDING ||
            guardianId !in request.guardianIds ||
            (request.elderId to guardianId) !in links
        ) return null

        val result = request.copy(
            status = if (approve) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED,
            respondedAt = currentTime,
            respondedBy = guardianId,
        )
        approvals[id] = result
        notifyObservers()
        return result
    }

    fun cancel(id: String, elderId: String): Boolean {
        expireDueApprovals()
        val request = approvals[id] ?: return false
        if (request.elderId != elderId || request.status != ApprovalStatus.PENDING) return false
        approvals[id] = request.copy(status = ApprovalStatus.CANCELLED)
        notifyObservers()
        return true
    }

    fun expireDueApprovals(currentTime: Long = now()) {
        val expired = approvals.values.filter {
            it.status == ApprovalStatus.PENDING && it.expiresAt <= currentTime
        }
        if (expired.isEmpty()) return
        for (request in expired) {
            approvals[request.id] = request.copy(status = ApprovalStatus.EXPIRED)
        }
        notifyObservers()
    }

    /** 응답할 보호자가 모두 해제된 대기 요청은 취소 (서버 검토 답변 3.3 제안과 같음) */
    private fun cancelOrphanApprovals(elderId: String) {
        val linked = linksForElder(elderId).mapTo(mutableSetOf()) { it.guardianId }
        approvals.values.filter {
            it.elderId == elderId && it.status == ApprovalStatus.PENDING && it.guardianIds.none(linked::contains)
        }.forEach { approvals[it.id] = it.copy(status = ApprovalStatus.CANCELLED) }
    }

    private fun newCode(): ConnectionCode {
        val currentTime = now()
        codes.entries.removeAll { it.value.expiresAt <= currentTime }
        reservedCodes.entries.removeAll { it.value <= currentTime }
        var number = Math.floorMod(nextCode(), 1_000_000)
        while (number.toString().padStart(6, '0') in reservedCodes) {
            number = (number + 1) % 1_000_000
        }
        val code = ConnectionCode(number.toString().padStart(6, '0'), currentTime + Family.CODE_TTL_MS)
        reservedCodes[code.code] = code.expiresAt
        return code
    }

    private fun notifyObservers() {
        listeners.toList().forEach { it() }
    }

    companion object {
        const val APPROVAL_TTL = 300_000L
        const val MAX_RELEASE_FAILURES = 5
    }
}
