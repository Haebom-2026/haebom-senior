package kr.haebom.elder.guardian

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kr.haebom.elder.R

class MockGuardianActivity : Activity() {
    private val repo = MockGuardianRepository()
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
    private var code: ConnectionCode? = null
    private var codeUsed = false
    private var guardian = GUARDIAN_A

    private lateinit var codeStatus: TextView
    private lateinit var codeInput: EditText
    private lateinit var elderLinks: LinearLayout
    private lateinit var elderStatus: TextView
    private lateinit var guardianLinks: LinearLayout
    private lateinit var guardianStatus: TextView
    private lateinit var cancel: Button
    private lateinit var approve: Button
    private lateinit var reject: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        content.message(R.string.mock_title, 26f)
        content.message(R.string.mock_description)
        content.message(R.string.mock_elder_section, 22f)
        content.button(R.string.mock_issue_code, ::issue)
        codeStatus = content.message(R.string.mock_code_none)
        elderLinks = content.group()
        content.button(R.string.mock_request, ::request)
        cancel = content.button(R.string.mock_cancel) {
            receiver.current?.let { repo.cancel(it.id, ELDER) }
        }
        elderStatus = content.message(R.string.mock_status_none)

        content.message(R.string.mock_guardian_section, 22f)
        val group = RadioGroup(this)
        val first = RadioButton(this).apply {
            id = android.view.View.generateViewId()
            setText(R.string.mock_guardian_a)
        }
        group.addView(first)
        group.addView(RadioButton(this).apply {
            id = android.view.View.generateViewId()
            setText(R.string.mock_guardian_b)
        })
        group.check(first.id)
        group.setOnCheckedChangeListener { _, selected ->
            guardian = if (selected == first.id) GUARDIAN_A else GUARDIAN_B
            render()
        }
        content.addView(group)

        codeInput = EditText(this).apply {
            setHint(R.string.mock_code_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        content.addView(codeInput)
        content.button(R.string.mock_connect, ::connect)
        guardianLinks = content.group()
        guardianStatus = content.message(R.string.mock_guardian_no_request)
        approve = content.button(R.string.mock_approve) { respond(approve = true) }
        reject = content.button(R.string.mock_reject) { respond(approve = false) }
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

    private fun issue() {
        code = repo.issueCode(ELDER)
        codeUsed = false
        codeInput.setText(code?.code)
        updateCountdowns()
    }

    private fun connect() {
        val linked = repo.connect(
            codeInput.text.toString(),
            guardian,
            getString(R.string.mock_elder_label),
            getString(if (guardian == GUARDIAN_A) R.string.mock_a_label else R.string.mock_b_label),
            getString(if (guardian == GUARDIAN_A) R.string.mock_guardian_a else R.string.mock_guardian_b),
        )
        if (linked != null) {
            codeUsed = true
            code = null
            codeInput.text.clear()
            updateCountdowns()
        }
        Toast.makeText(this, if (linked == null) R.string.mock_code_invalid else R.string.mock_connected,
            Toast.LENGTH_SHORT).show()
    }

    private fun request() {
        receiver.current?.let { repo.cancel(it.id, ELDER) }
        val approval = repo.requestApproval(
            elderId = ELDER,
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
        elderLinks.removeAllViews()
        val forElder = repo.linksForElder(ELDER)
        if (forElder.isEmpty()) elderLinks.message(R.string.mock_no_links)
        for (link in forElder) {
            elderLinks.button(getString(R.string.mock_elder_link, link.labelForElder)) {
                confirmElderDisconnect(link)
            }
        }

        guardianLinks.removeAllViews()
        val forGuardian = repo.linksForGuardian(guardian)
        if (forGuardian.isEmpty()) guardianLinks.message(R.string.mock_no_links)
        for (link in forGuardian) {
            guardianLinks.button(getString(R.string.mock_guardian_link, link.labelForGuardian)) {
                repo.disconnect(link.elderId, link.guardianId)
            }
        }
        updateCountdowns()
    }

    /** 고령자 쪽 해제(#12)는 한 번 더 묻고, 그 보호자와의 연결 한 쌍만 끊습니다. */
    private fun confirmElderDisconnect(link: FamilyLink) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.mock_disconnect_confirm, link.labelForElder))
            .setPositiveButton(R.string.mock_disconnect_yes) { _, _ ->
                repo.disconnect(link.elderId, link.guardianId)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateCountdowns() {
        val currentCode = code
        codeStatus.text = if (currentCode != null && remaining(currentCode.expiresAt) > 0) {
            getString(R.string.mock_code_format, currentCode.code, remaining(currentCode.expiresAt))
        } else {
            getString(if (codeUsed) R.string.mock_code_used else R.string.mock_code_none)
        }
        val request = receiver.current
        elderStatus.text = when (request?.status) {
            null -> getString(R.string.mock_status_none)
            ApprovalStatus.PENDING -> getString(R.string.mock_status_pending, remaining(request.expiresAt))
            ApprovalStatus.APPROVED -> getString(
                R.string.mock_status_approved, repo.guardianLabelForElder(ELDER, request.respondedBy),
            )
            ApprovalStatus.REJECTED -> getString(
                R.string.mock_status_rejected, repo.guardianLabelForElder(ELDER, request.respondedBy),
            )
            ApprovalStatus.EXPIRED -> getString(R.string.mock_status_expired)
            ApprovalStatus.CANCELLED -> getString(R.string.mock_status_cancelled)
        }
        cancel.isEnabled = request?.status == ApprovalStatus.PENDING
        val canRespond = request != null && request.status == ApprovalStatus.PENDING &&
            guardian in request.guardianIds && repo.linksForGuardian(guardian).any { it.elderId == ELDER }
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

    private fun LinearLayout.message(text: Int, size: Float = 18f): TextView = TextView(context).also {
        it.setText(text)
        it.textSize = size
        addView(it)
    }

    private fun LinearLayout.button(text: Int, action: () -> Unit): Button =
        button(getString(text), action)

    private fun LinearLayout.button(text: String, action: () -> Unit): Button = Button(context).also {
        it.text = text
        it.minHeight = (52 * resources.displayMetrics.density).toInt()
        it.setOnClickListener { action() }
        addView(it, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    private fun LinearLayout.group(): LinearLayout = LinearLayout(context).also {
        it.orientation = LinearLayout.VERTICAL
        addView(it)
    }

    private companion object {
        const val ELDER = "mock-elder"
        const val GUARDIAN_A = "mock-guardian-a"
        const val GUARDIAN_B = "mock-guardian-b"
    }
}
