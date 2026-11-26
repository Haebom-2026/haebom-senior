package kr.haebom.elder.guardian

import kr.haebom.elder.family.ApprovalStatus
import kr.haebom.elder.family.DisconnectResult
import kr.haebom.elder.family.FamilyLink
import kr.haebom.elder.family.GuardianApproval
import kr.haebom.elder.family.NoticeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockGuardianRepositoryTest {
    private var time = 1_000L
    private val repository = MockGuardianRepository(now = { time }, nextCode = { 42 })

    @Test
    fun codesUseSixDigitsExpireAndCanOnlyBeRedeemedOnce() {
        val first = repository.issueCode("elder")
        val second = repository.issueCode("elder")
        assertEquals("000042", first.code)
        assertNotEquals(first.code, second.code)
        assertNull(repository.connect(first.code, "a", null, null))
        assertNotNull(repository.connect(second.code, "a", "어머니", "큰딸", "김수연"))
        assertNull(repository.connect(second.code, "b", null, null))

        val expiring = repository.issueCode("elder")
        assertEquals(time + 600_000, expiring.expiresAt)
        time = expiring.expiresAt
        assertNull(repository.connect(expiring.code, "b", null, null))
    }

    @Test
    fun multipleLinksRemainIndependentAndBothObserversReceiveDisconnection() {
        link("elder-a", "guardian-a")
        link("elder-a", "guardian-b")
        link("elder-b", "guardian-a")
        var eldersView = emptyList<FamilyLink>()
        var guardiansView = emptyList<FamilyLink>()
        val elderObserver = repository.observe {
            eldersView = repository.linksForElder("elder-a")
        }
        val guardianObserver = repository.observe {
            guardiansView = repository.linksForGuardian("guardian-a")
        }
        assertEquals(setOf("guardian-a", "guardian-b"), eldersView.map { it.guardianId }.toSet())
        assertEquals(setOf("elder-a", "elder-b"), guardiansView.map { it.elderId }.toSet())

        assertTrue(repository.disconnect("elder-a", "guardian-a"))
        assertEquals(listOf("guardian-b"), eldersView.map { it.guardianId })
        assertEquals(listOf("elder-b"), guardiansView.map { it.elderId })
        assertFalse(repository.disconnect("elder-a", "guardian-a"))
        elderObserver.close()
        guardianObserver.close()
    }

    @Test
    fun directionalLabelsAreDistinctAndFallBackWithoutRelationship() {
        val outgoing = link("elder", "ga", elderLabel = "엄마", guardianLabel = "큰딸")
        assertEquals("큰딸", outgoing.labelForElder)
        assertEquals("엄마", outgoing.labelForGuardian)

        val named = link("elder", "gb", guardianLabel = "  ", guardianName = "김수연")
        assertEquals("김수연", named.labelForElder)
        assertEquals("elder", named.labelForGuardian)

        val unnamed = link("elder", "gc")
        assertEquals(FamilyLink.DEFAULT_GUARDIAN_LABEL, unnamed.labelForElder)
    }

    @Test
    fun approvalResultNamesTheRespondingGuardianForTheElder() {
        link("elder", "a", guardianLabel = "큰딸")
        link("elder", "b", guardianLabel = "아들")
        val approval = requireNotNull(request("elder"))
        val receiver = MockApprovalReceiver(now = { time })
        receiver.begin(approval)
        val observer = repository.observe { receiver.receive(repository.approval(approval.id)) }

        time += 20_000
        assertNotNull(repository.respond(approval.id, "b", approve = false))
        val result = requireNotNull(receiver.current)
        assertEquals(ApprovalStatus.REJECTED, result.status)
        assertEquals(time, result.respondedAt)
        assertEquals("아들", repository.guardianLabelForElder("elder", result.respondedBy))

        assertTrue(repository.disconnect("elder", "b"))
        assertEquals(
            FamilyLink.DEFAULT_GUARDIAN_LABEL,
            repository.guardianLabelForElder("elder", result.respondedBy),
        )
        observer.close()
    }

    @Test
    fun disconnectingOneGuardianKeepsTheOthersForNewApprovals() {
        link("elder", "a")
        link("elder", "b")
        assertTrue(repository.disconnect("elder", "a"))
        assertEquals(setOf("b"), requireNotNull(request("elder")).guardianIds)
        assertTrue(repository.disconnect("elder", "b"))
        assertNull(request("elder"))
    }

    @Test
    fun currentApprovalAcceptsFirstEligibleGuardianResponse() {
        assertNull(request("elder"))
        link("elder", "a")
        link("elder", "b")
        val approval = requireNotNull(request("elder"))
        assertEquals(setOf("a", "b"), approval.guardianIds)
        val receiver = MockApprovalReceiver(now = { time })
        receiver.begin(approval)
        val observer = repository.observe { receiver.receive(repository.approval(approval.id)) }

        assertNull(repository.respond(approval.id, "unrelated", approve = true))
        assertNotNull(repository.respond(approval.id, "b", approve = true))
        assertNull(repository.respond(approval.id, "a", approve = false))
        assertEquals(ApprovalStatus.APPROVED, receiver.current?.status)
        assertEquals("b", receiver.current?.respondedBy)
        observer.close()
    }

    @Test
    fun rejectsWhenDisconnectedOrExpiredAndIgnoresResultsFromPreviousRequests() {
        link("elder", "a")
        link("elder", "b")
        val first = requireNotNull(request("elder"))
        val second = requireNotNull(request("elder"))
        val receiver = MockApprovalReceiver(now = { time })
        receiver.begin(second)
        assertTrue(repository.disconnect("elder", "b"))
        assertNull(repository.respond(second.id, "b", approve = true))

        assertNotNull(repository.respond(first.id, "a", approve = false))
        assertFalse(receiver.receive(repository.approval(first.id)))
        assertTrue(repository.cancel(second.id, "elder"))
        assertTrue(receiver.receive(repository.approval(second.id)))
        assertEquals(ApprovalStatus.CANCELLED, receiver.current?.status)
        assertNull(repository.respond(second.id, "a", approve = true))

        val third = requireNotNull(request("elder"))
        assertEquals(time + 300_000, third.expiresAt)
        receiver.begin(third)
        time = third.expiresAt
        assertFalse(receiver.receive(third.copy(
            status = ApprovalStatus.APPROVED,
            respondedAt = time - 1,
            respondedBy = "a",
        )))
        assertNull(repository.respond(third.id, "a", approve = true))
        assertTrue(receiver.receive(repository.approval(third.id)))
        assertEquals(ApprovalStatus.EXPIRED, receiver.current?.status)
    }

    @Test
    fun elderDisconnectNeedsTheGuardiansReleaseCode() {
        link("elder", "a", guardianLabel = "큰딸")
        link("elder", "b", guardianLabel = "아들")
        assertEquals(DisconnectResult.WRONG_CODE, repository.disconnectByElder("elder", "a", "123456"))

        val release = requireNotNull(repository.issueReleaseCode("elder", "a"))
        assertEquals(DisconnectResult.WRONG_CODE, repository.disconnectByElder("elder", "a", "000000"))
        assertEquals(DisconnectResult.WRONG_CODE, repository.disconnectByElder("elder", "b", release.code))
        assertEquals(DisconnectResult.DISCONNECTED, repository.disconnectByElder("elder", "a", release.code))
        assertEquals(listOf("b"), repository.linksForElder("elder").map { it.guardianId })
        assertEquals(DisconnectResult.NOT_LINKED, repository.disconnectByElder("elder", "a", release.code))
    }

    @Test
    fun releaseCodesExpireAndStopWorkingAfterFiveWrongTries() {
        link("elder", "a")
        val expiring = requireNotNull(repository.issueReleaseCode("elder", "a"))
        time = expiring.expiresAt
        assertEquals(DisconnectResult.WRONG_CODE, repository.disconnectByElder("elder", "a", expiring.code))

        val fresh = requireNotNull(repository.issueReleaseCode("elder", "a"))
        repeat(MockGuardianRepository.MAX_RELEASE_FAILURES) {
            assertEquals(DisconnectResult.WRONG_CODE, repository.disconnectByElder("elder", "a", "999999"))
        }
        assertEquals(DisconnectResult.WRONG_CODE, repository.disconnectByElder("elder", "a", fresh.code))
        assertEquals(1, repository.linksForElder("elder").size)
        assertNull(repository.issueReleaseCode("elder", "nobody"))
    }

    @Test
    fun elderCanRenameAGuardianWithoutChangingTheGuardiansView() {
        link("elder", "a", elderLabel = "어머니", guardianLabel = "김준표")
        assertTrue(repository.renameGuardian("elder", "a", "  김영표 "))
        val renamed = repository.linksForElder("elder").single()
        assertEquals("김영표", renamed.labelForElder)
        assertEquals("어머니", renamed.labelForGuardian)
        assertFalse(repository.renameGuardian("elder", "nobody", "누구"))
    }

    @Test
    fun sensitiveNoticesReachEveryLinkedGuardianOncePerTenMinutes() {
        assertEquals(NoticeResult.NO_GUARDIANS, notice("viva.republica.toss").result)
        link("elder", "a", guardianLabel = "큰딸")
        link("elder", "b", guardianLabel = "아들")

        val first = notice("viva.republica.toss")
        assertEquals(NoticeResult.CREATED, first.result)
        assertEquals(listOf("큰딸", "아들"), first.guardianLabels)
        assertEquals(NoticeResult.DUPLICATE_SUPPRESSED, notice("viva.republica.toss").result)
        assertEquals(NoticeResult.CREATED, notice("kr.go.minwon.m").result)
        time += 600_000
        assertEquals(NoticeResult.CREATED, notice("viva.republica.toss").result)

        assertEquals(3, repository.noticesForGuardian("a").size)
        assertEquals("토스", repository.noticesForGuardian("b").first().appName)
        repository.disconnect("elder", "b")
        assertTrue(repository.noticesForGuardian("b").isEmpty())
    }

    @Test
    fun pendingApprovalIsCancelledWhenNoLinkedGuardianIsLeft() {
        link("elder", "a")
        val approval = requireNotNull(request("elder"))
        assertTrue(repository.disconnect("elder", "a"))
        assertEquals(ApprovalStatus.CANCELLED, repository.approval(approval.id)?.status)
    }

    private fun notice(pkg: String) =
        repository.reportSensitiveEntry("elder", if (pkg.contains("toss")) "토스" else "정부24", pkg, "finance")

    private fun link(
        elderId: String,
        guardianId: String,
        elderLabel: String? = null,
        guardianLabel: String? = null,
        guardianName: String? = null,
    ): FamilyLink = requireNotNull(repository.connect(
        repository.issueCode(elderId).code, guardianId, elderLabel, guardianLabel, guardianName,
    ))

    private fun request(elderId: String): GuardianApproval? =
        repository.requestApproval(elderId, "정부24", "kr.go.minwon.m", "guide_gov24_auth", "public")
}
