package kr.haebom.elder.guardian

import java.security.SecureRandom

internal data class FamilyLink(
    val elderId: String,
    val guardianId: String,
    val elderName: String,
    val elderLabel: String?,
    val guardianLabel: String?,
    val relationship: String?,
    val connectedAt: Long,
) {
    val id: String
        get() = "${elderId}_$guardianId"
    val labelForElder: String
        get() = guardianLabel?.trim()?.takeIf { it.isNotEmpty() } ?: guardianId
    val labelForGuardian: String
        get() = elderLabel?.trim()?.takeIf { it.isNotEmpty() } ?: elderName
}

internal data class ConnectionCode(val code: String, val expiresAt: Long)

internal enum class ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    CANCELLED,
}

internal data class GuardianApproval(
    val id: String,
    val elderId: String,
    val guardianIds: Set<String>,
    val appName: String,
    val packageName: String,
    val action: String,
    val category: String,
    val status: ApprovalStatus,
    val createdAt: Long,
    val expiresAt: Long,
    val respondedAt: Long? = null,
    val respondedBy: String? = null,
)

internal class MockGuardianRepository(
    private val now: () -> Long = System::currentTimeMillis,
    private val nextCode: () -> Int = SecureRandom()::nextInt,
) {
    private val links = mutableMapOf<Pair<String, String>, FamilyLink>()
    private val codes = mutableMapOf<String, ConnectionCode>()
    private val reservedCodes = mutableMapOf<String, Long>()
    private val approvals = mutableMapOf<String, GuardianApproval>()
    private val listeners = mutableSetOf<() -> Unit>()
    private var nextApprovalId = 0

    fun observe(listener: () -> Unit): AutoCloseable {
        listeners.add(listener)
        listener()
        return AutoCloseable { listeners.remove(listener) }
    }

    fun linksForElder(elderId: String): List<FamilyLink> =
        links.values.filter { it.elderId == elderId }

    fun linksForGuardian(guardianId: String): List<FamilyLink> =
        links.values.filter { it.guardianId == guardianId }

    fun issueCode(elderId: String): ConnectionCode {
        val currentTime = now()
        codes.entries.removeAll { it.value.expiresAt <= currentTime }
        reservedCodes.entries.removeAll { it.value <= currentTime }
        var number = Math.floorMod(nextCode(), 1_000_000)
        while (number.toString().padStart(6, '0') in reservedCodes) {
            number = (number + 1) % 1_000_000
        }
        val code = ConnectionCode(number.toString().padStart(6, '0'), currentTime + CODE_TTL)
        codes[elderId] = code
        reservedCodes[code.code] = code.expiresAt
        notifyObservers()
        return code
    }

    fun connect(
        code: String,
        guardianId: String,
        elderLabel: String?,
        guardianLabel: String?,
        relationship: String? = null,
    ): FamilyLink? {
        val currentTime = now()
        val issued = codes.entries.firstOrNull {
            it.value.code == code.trim() && it.value.expiresAt > currentTime
        } ?: return null

        val elderId = issued.key
        val key = elderId to guardianId
        if (key in links) return null
        val connection = FamilyLink(
            elderId, guardianId, elderId, elderLabel, guardianLabel, relationship, currentTime,
        )
        codes.remove(elderId)
        links[key] = connection
        notifyObservers()
        return connection
    }

    fun disconnect(elderId: String, guardianId: String): Boolean {
        val removed = links.remove(elderId to guardianId) != null
        if (removed) notifyObservers()
        return removed
    }

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

    private fun notifyObservers() {
        listeners.toList().forEach { it() }
    }

    private companion object {
        const val CODE_TTL = 600_000L
        const val APPROVAL_TTL = 300_000L
    }
}
