package kr.haebom.elder.guardian

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import kr.haebom.elder.R
import kr.haebom.elder.family.ApprovalStatus
import kr.haebom.elder.family.FamilyLink
import kr.haebom.elder.ui.ElderUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 보호자 앱 흉내 (debug 전용). 고령자 앱의 "가족 연결" 화면과 같은 기기 메모리(MockBackend)를 씁니다.
 * - 고령자 화면에서 받은 연결 코드를 넣어 보호자 A·B 연결 (#14)
 * - 해제 확인 코드 보여 주기 → 고령자 화면에서 입력해야 해제 (#12)
 * - 보호자 쪽 해제 (#15), 받은 민감 앱 알림 보기 (#6)
 * - 정부24 안내 승인 요청(고령자 쪽 흉내)과 승인·거절 (#9 #10 #13)
 */
class MockGuardianActivity : Activity() {
    private val repo = MockBackend.repo
    private val elder = MockBackend.ELDER_ID
    private val receiver = MockApprovalReceiver()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            repo.expireDueApprovals()
            updateCountdowns()
            handler.postDelayed(this, 1_000)
        }
    }
    private var subscription: AutoCloseable? = null
    private var guardian = GUARDIAN_A
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    private lateinit var codeInput: android.widget.EditText
    private lateinit var activeCode: TextView
    private lateinit var guardianLinks: LinearLayout
    private lateinit var noticeList: LinearLayout
    private lateinit var elderStatus: TextView
    private lateinit var guardianStatus: TextView
    private lateinit var cancel: Button
    private lateinit var approve: Button
    private lateinit var reject: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = ElderUi.page(this)
        page.addView(ElderUi.title(this, getString(R.string.mock_title)))
        page.addView(ElderUi.body(this, getString(R.string.mock_description), 17f))

        page.addView(ElderUi.section(this, getString(R.string.mock_guardian_section)))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val first = RadioButton(this).apply {
            id = android.view.View.generateViewId()
            setText(R.string.mock_guardian_a)
            textSize = 20f
        }
        group.addView(first)
        group.addView(RadioButton(this).apply {
            id = android.view.View.generateViewId()
            setText(R.string.mock_guardian_b)
            textSize = 20f
        })
        group.check(first.id)
        group.setOnCheckedChangeListener { _, selected ->
            guardian = if (selected == first.id) GUARDIAN_A else GUARDIAN_B
            render()
        }
        page.addView(group)

        // 연결
        activeCode = ElderUi.body(this, "", 17f)
        codeInput = ElderUi.input(this, getString(R.string.mock_code_hint), digits = true).apply {
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        page.addView(activeCode)
        page.addView(codeInput)
        page.addView(ElderUi.button(this, getString(R.string.mock_paste_code), ElderUi.Kind.QUIET) {
            repo.activeCode(elder)?.let { codeInput.setText(it.code) }
        })
        page.addView(ElderUi.button(this, getString(R.string.mock_connect)) { connect() })

        // 연결된 어르신 (해제 확인 코드 / 보호자 쪽 해제)
        guardianLinks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(guardianLinks)

        // 받은 알림
        page.addView(ElderUi.section(this, getString(R.string.mock_notices)))
        noticeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(noticeList)

        // 승인
        page.addView(ElderUi.section(this, getString(R.string.mock_approval_section)))
        page.addView(ElderUi.button(this, getString(R.string.mock_request), ElderUi.Kind.SECONDARY) { request() })
        cancel = ElderUi.button(this, getString(R.string.mock_cancel), ElderUi.Kind.QUIET) {
            receiver.current?.let { repo.cancel(it.id, elder) }
        }
        page.addView(cancel)
        elderStatus = ElderUi.text(this, "", 20f, ElderUi.INK, bold = true)
        page.addView(elderStatus)
        guardianStatus = ElderUi.body(this, "", 18f)
        page.addView(guardianStatus)
        approve = ElderUi.button(this, getString(R.string.mock_approve)) { respond(approve = true) }
        reject = ElderUi.button(this, getString(R.string.mock_reject), ElderUi.Kind.DANGER) { respond(approve = false) }
        page.addView(approve)
        page.addView(reject)
    }

    override fun onStart() {
        super.onStart()
        subscription = repo.observe {
            receiver.current?.let { receiver.receive(repo.approval(it.id)) }
            render()
        }
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        subscription?.close()
        subscription = null
        super.onStop()
    }

    private fun connect() {
        val linked = repo.connect(
            codeInput.text.toString(),
            guardian,
            elderLabel = getString(R.string.mock_elder_label),
            guardianLabel = getString(if (guardian == GUARDIAN_A) R.string.mock_a_label else R.string.mock_b_label),
            guardianName = getString(if (guardian == GUARDIAN_A) R.string.mock_guardian_a else R.string.mock_guardian_b),
        )
        if (linked != null) codeInput.text.clear()
        Toast.makeText(this, if (linked == null) R.string.mock_code_invalid else R.string.mock_connected, Toast.LENGTH_SHORT).show()
    }

    private fun request() {
        receiver.current?.let { repo.cancel(it.id, elder) }
        val approval = repo.requestApproval(
            elderId = elder,
            appName = "정부24",
            packageName = "kr.go.minwon.m",
            action = "guide_gov24_auth",
            category = "public",
        )
        if (approval == null) {
            Toast.makeText(this, R.string.mock_no_guardians, Toast.LENGTH_SHORT).show()
            return
        }
        receiver.begin(approval)
        updateCountdowns()
    }

    private fun respond(approve: Boolean) {
        receiver.current?.let { repo.respond(it.id, guardian, approve) }
    }

    private fun render() {
        guardianLinks.removeAllViews()
        val links = repo.linksForGuardian(guardian)
        if (links.isEmpty()) guardianLinks.addView(ElderUi.body(this, getString(R.string.mock_no_links), 18f))
        for (link in links) guardianLinks.addView(linkCard(link))

        noticeList.removeAllViews()
        val notices = repo.noticesForGuardian(guardian)
        if (notices.isEmpty()) noticeList.addView(ElderUi.body(this, getString(R.string.mock_no_notices), 18f))
        for (notice in notices) {
            noticeList.addView(ElderUi.card(this).apply {
                addView(ElderUi.text(context, getString(R.string.mock_notice_line, notice.appName), 20f, ElderUi.INK, bold = true))
                addView(ElderUi.body(context, "${clock.format(Date(notice.createdAt))} · ${notice.category}", 16f))
            })
        }
        updateCountdowns()
    }

    private fun linkCard(link: FamilyLink) = ElderUi.card(this).apply {
        addView(ElderUi.text(context, getString(R.string.mock_linked_elder, link.labelForGuardian, link.labelForElder), 20f, ElderUi.INK, bold = true))
        val release = repo.activeReleaseCode(link.elderId, link.guardianId)
        if (release != null) {
            addView(ElderUi.text(context, getString(R.string.mock_release_code, ElderUi.spaced(release.code)), 26f, ElderUi.NAVY_DARK, bold = true))
        }
        addView(ElderUi.button(context, getString(R.string.mock_show_release), ElderUi.Kind.SECONDARY) {
            repo.issueReleaseCode(link.elderId, link.guardianId)
        })
        addView(ElderUi.button(context, getString(R.string.mock_guardian_link, link.labelForGuardian), ElderUi.Kind.QUIET) {
            repo.disconnect(link.elderId, link.guardianId)
        })
    }

    private fun updateCountdowns() {
        val code = repo.activeCode(elder)
        activeCode.text = if (code != null) {
            getString(R.string.mock_active_code, code.code, remaining(code.expiresAt))
        } else {
            getString(R.string.mock_code_none)
        }
        val request = receiver.current
        elderStatus.text = when (request?.status) {
            null -> getString(R.string.mock_status_none)
            ApprovalStatus.PENDING -> getString(R.string.mock_status_pending, remaining(request.expiresAt))
            ApprovalStatus.APPROVED -> getString(R.string.mock_status_approved, repo.guardianLabelForElder(elder, request.respondedBy))
            ApprovalStatus.REJECTED -> getString(R.string.mock_status_rejected, repo.guardianLabelForElder(elder, request.respondedBy))
            ApprovalStatus.EXPIRED -> getString(R.string.mock_status_expired)
            ApprovalStatus.CANCELLED -> getString(R.string.mock_status_cancelled)
        }
        cancel.isEnabled = request?.status == ApprovalStatus.PENDING
        val canRespond = request != null && request.status == ApprovalStatus.PENDING &&
            guardian in request.guardianIds && repo.linksForGuardian(guardian).any { it.elderId == elder }
        guardianStatus.text = if (canRespond && request != null) {
            getString(R.string.mock_guardian_pending, remaining(request.expiresAt))
        } else {
            getString(R.string.mock_guardian_no_request)
        }
        approve.isEnabled = canRespond
        reject.isEnabled = canRespond
    }

    private fun remaining(expiresAt: Long): Long =
        ((expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1_000

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val GUARDIAN_A = "mock-guardian-a"
        const val GUARDIAN_B = "mock-guardian-b"
    }
}
