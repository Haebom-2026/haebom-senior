package kr.haebom.elder.family

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kr.haebom.elder.R
import kr.haebom.elder.guide.GuideCaption
import kr.haebom.elder.ui.ElderUi
import kr.haebom.elder.ui.ElderUi.visible

/**
 * 가족 연결 · 보호자 정보.
 * - 연결 코드 받기 (#11): 고령자가 코드를 만들고 보호자가 보호자 앱에 입력
 * - 연결된 보호자마다 부르는 이름(서버 guardianLabel)과 전화번호(폰에만) 바꾸기
 * - 연결 끊기 (#12): 보호자 앱에 보이는 해제 확인 코드를 입력해야 끊김
 * - 연결 전에도 SOS 문자 받을 보호자를 직접 등록
 */
class FamilyActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val settings by lazy { ElderSettings(this) }
    private val gateway: FamilyGateway? get() = Family.gateway
    private var subscription: AutoCloseable? = null

    private lateinit var codeDigits: TextView
    private lateinit var codeHelp: TextView
    private lateinit var codeButton: Button
    private lateinit var linkedTitle: TextView
    private lateinit var linkedList: LinearLayout
    private lateinit var manualCard: LinearLayout
    private val tick = object : Runnable {
        override fun run() {
            renderCode()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = ElderUi.page(this)
        page.addView(ElderUi.title(this, getString(R.string.family_title)))
        page.addView(ElderUi.body(this, getString(R.string.family_intro)))

        if (gateway == null) {
            page.addView(ElderUi.card(this, ElderUi.SOFT_RED, ElderUi.RED).apply {
                addView(ElderUi.text(context, getString(R.string.family_server_pending), 20f, ElderUi.INK))
            })
        }

        // 연결 코드
        page.addView(ElderUi.section(this, getString(R.string.family_code_section)))
        val codeCard = ElderUi.card(this, ElderUi.SOFT_BLUE, ElderUi.NAVY)
        codeDigits = ElderUi.text(this, "", 44f, ElderUi.NAVY_DARK, bold = true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            // 여섯 자리가 두 줄로 나뉘면 읽기 어려우므로 항상 한 줄 (좁은 화면에서는 글자를 줄임)
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(24, 44, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        codeHelp = ElderUi.text(this, "", 20f, ElderUi.INK).apply { gravity = Gravity.CENTER }
        codeButton = ElderUi.button(this, getString(R.string.family_code_get)) { issueCode() }
        codeCard.addView(codeDigits)
        codeCard.addView(codeHelp)
        codeCard.addView(codeButton)
        codeCard.visible(gateway != null)
        page.addView(codeCard)

        // 연결된 가족
        linkedTitle = ElderUi.section(this, "")
        linkedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(linkedTitle)
        page.addView(linkedList)
        linkedTitle.visible(gateway != null)

        // 직접 등록 (문자용)
        page.addView(ElderUi.section(this, getString(R.string.family_manual_section)))
        page.addView(ElderUi.body(this, getString(R.string.family_manual_help), 18f))
        manualCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(manualCard)
    }

    override fun onStart() {
        super.onStart()
        subscription = gateway?.observe { render() }
        if (subscription == null) render()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        subscription?.close()
        subscription = null
        super.onStop()
    }

    private fun render() {
        renderCode()
        renderLinks()
        renderManual()
    }

    private fun renderCode() {
        val code = gateway?.activeCode()
        val left = code?.let { (it.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) / 1_000 }
        if (code != null && left != null && left > 0) {
            codeDigits.text = ElderUi.spaced(code.code)
            codeDigits.visible(true)
            codeHelp.text = getString(R.string.family_code_help) + "\n" + getString(R.string.family_code_left, left / 60, left % 60)
            codeButton.text = getString(R.string.family_code_new)
        } else {
            codeDigits.visible(false)
            codeHelp.text = getString(R.string.family_code_none)
            codeButton.text = getString(R.string.family_code_get)
        }
    }

    private fun renderLinks() {
        val links = gateway?.links().orEmpty()
        linkedTitle.text = getString(R.string.family_linked_section, links.size)
        linkedList.removeAllViews()
        if (gateway == null) return
        if (links.isEmpty()) {
            linkedList.addView(ElderUi.body(this, getString(R.string.family_none)))
            return
        }
        for (link in links) {
            val card = ElderUi.card(this)
            card.addView(ElderUi.text(this, "👤 ${link.labelForElder}", 26f, ElderUi.INK, bold = true))
            val phone = settings.phoneFor(link.guardianId)
            card.addView(
                if (phone.isNotEmpty()) ElderUi.text(this, getString(R.string.family_phone, ElderSettings.displayPhone(phone)), 21f, ElderUi.INK)
                else ElderUi.body(this, getString(R.string.family_no_phone), 18f),
            )
            card.addView(ElderUi.button(this, getString(R.string.family_edit), ElderUi.Kind.SECONDARY) {
                editGuardian(link.labelForElder, phone) { name, number -> saveLinked(link, name, number) }
            })
            card.addView(ElderUi.button(this, getString(R.string.family_disconnect), ElderUi.Kind.QUIET) {
                confirmDisconnect(link)
            })
            linkedList.addView(card)
        }
    }

    private fun renderManual() {
        manualCard.removeAllViews()
        val manual = settings.manualContact
        if (manual == null) {
            manualCard.addView(ElderUi.button(this, getString(R.string.family_manual_add), ElderUi.Kind.SECONDARY) {
                editGuardian("", "") { name, number -> saveManual(name, number) }
            })
            return
        }
        val card = ElderUi.card(this)
        card.addView(ElderUi.text(this, "👤 ${manual.name}", 26f, ElderUi.INK, bold = true))
        card.addView(ElderUi.text(this, getString(R.string.family_phone, ElderSettings.displayPhone(manual.phone)), 21f, ElderUi.INK))
        card.addView(ElderUi.button(this, getString(R.string.family_edit), ElderUi.Kind.SECONDARY) {
            editGuardian(manual.name, manual.phone) { name, number -> saveManual(name, number) }
        })
        card.addView(ElderUi.button(this, getString(R.string.family_manual_delete), ElderUi.Kind.QUIET) {
            settings.manualContact = null
            say(getString(R.string.family_manual_deleted), GuideCaption.Tone.INFO)
            renderManual()
        })
        manualCard.addView(card)
    }

    private fun issueCode() {
        val gate = gateway ?: return
        codeButton.isEnabled = false
        gate.issueCode { code ->
            codeButton.isEnabled = true
            if (code == null) say(getString(R.string.family_failed), GuideCaption.Tone.WARN) else renderCode()
        }
    }

    /** 이름·전화번호 입력 창. 이름은 꼭, 번호는 비워도 됨 */
    private fun editGuardian(name: String, phone: String, save: (String, String) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ElderUi.dp(context, 20)
            setPadding(pad, ElderUi.dp(context, 8), pad, 0)
        }
        val nameInput = ElderUi.input(this, getString(R.string.family_name_hint), name)
        val phoneInput = ElderUi.input(this, getString(R.string.family_phone_hint), ElderSettings.displayPhone(phone), phone = true)
        box.addView(nameInput)
        box.addView(phoneInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.family_edit_title)
            .setView(box)
            .setPositiveButton(R.string.family_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        enlarge(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newName = nameInput.text.toString().trim()
            val newPhone = phoneInput.text.toString()
            when {
                newName.isEmpty() -> nameInput.error = getString(R.string.family_empty_name)
                newPhone.isNotBlank() && !ElderSettings.isValidPhone(newPhone) -> phoneInput.error = getString(R.string.family_bad_phone)
                else -> {
                    dialog.dismiss()
                    save(newName, newPhone)
                }
            }
        }
    }

    private fun saveLinked(link: FamilyLink, name: String, phone: String) {
        settings.setPhone(link.guardianId, phone)
        val gate = gateway ?: return
        gate.renameGuardian(link.guardianId, name) { ok ->
            if (ok) say(getString(R.string.family_saved, name), GuideCaption.Tone.DONE)
            else say(getString(R.string.family_save_failed), GuideCaption.Tone.WARN)
            render()
        }
    }

    private fun saveManual(name: String, phone: String) {
        settings.manualContact = SosContact(name, phone)
        say(getString(R.string.family_saved, name), GuideCaption.Tone.DONE)
        renderManual()
    }

    /** 보호자 앱의 해제 확인 코드를 넣어야 끊김. 틀리면 창을 닫지 않고 다시 넣게 함 */
    private fun confirmDisconnect(link: FamilyLink) {
        val gate = gateway ?: return
        val name = link.labelForElder
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ElderUi.dp(context, 20)
            setPadding(pad, ElderUi.dp(context, 8), pad, 0)
        }
        val help = ElderUi.text(this, getString(R.string.family_disconnect_help, name), 19f, ElderUi.INK)
        val codeInput = ElderUi.input(this, getString(R.string.family_release_hint), digits = true).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        box.addView(help)
        box.addView(codeInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.family_disconnect_title, name))
            .setView(box)
            .setPositiveButton(R.string.family_disconnect, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        enlarge(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            button.isEnabled = false
            gate.disconnect(link.guardianId, codeInput.text.toString()) { result ->
                button.isEnabled = true
                when (result) {
                    DisconnectResult.DISCONNECTED -> {
                        settings.forget(link.guardianId)
                        dialog.dismiss()
                        say(getString(R.string.family_disconnected, name), GuideCaption.Tone.DONE)
                    }
                    DisconnectResult.WRONG_CODE -> {
                        codeInput.text.clear()
                        codeInput.error = getString(R.string.family_wrong_code)
                        help.text = getString(R.string.family_wrong_code)
                    }
                    DisconnectResult.NOT_LINKED -> {
                        dialog.dismiss()
                        say(getString(R.string.family_not_linked), GuideCaption.Tone.INFO)
                    }
                    DisconnectResult.FAILED -> say(getString(R.string.family_failed), GuideCaption.Tone.WARN)
                }
            }
        }
    }

    /** 결과를 자막(음성)과 화면 아래 안내로 알림 */
    private fun say(text: String, tone: GuideCaption.Tone) {
        GuideCaption.post(text, tone)
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun enlarge(dialog: AlertDialog) {
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE)) {
            dialog.getButton(which)?.apply {
                textSize = 20f
                minHeight = ElderUi.dp(context, 56)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
