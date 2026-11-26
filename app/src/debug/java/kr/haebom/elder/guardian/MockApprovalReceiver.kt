package kr.haebom.elder.guardian

import kr.haebom.elder.family.ApprovalStatus
import kr.haebom.elder.family.GuardianApproval

internal class MockApprovalReceiver(
    private val now: () -> Long = System::currentTimeMillis,
) {
    var current: GuardianApproval? = null
        private set

    fun begin(request: GuardianApproval) {
        current = request
    }

    fun receive(update: GuardianApproval?): Boolean {
        val waiting = current ?: return false
        if (update == null || waiting.id != update.id || waiting.elderId != update.elderId ||
            waiting.status != ApprovalStatus.PENDING || update.status == ApprovalStatus.PENDING
        ) return false

        if (update.status == ApprovalStatus.APPROVED || update.status == ApprovalStatus.REJECTED) {
            val respondedAt = update.respondedAt ?: return false
            if (respondedAt !in waiting.createdAt until waiting.expiresAt || now() >= waiting.expiresAt) {
                return false
            }
        }
        current = update
        return true
    }
}
