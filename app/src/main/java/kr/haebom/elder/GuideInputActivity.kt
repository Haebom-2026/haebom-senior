package kr.haebom.elder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import kr.haebom.elder.family.Assessment
import kr.haebom.elder.family.ElderSettings
import kr.haebom.elder.family.Family
import kr.haebom.elder.family.Level
import kr.haebom.elder.family.SensitiveAlerter
import kr.haebom.elder.family.SensitiveApps
import kr.haebom.elder.family.Sensitivity
import kr.haebom.elder.family.SosContact
import kr.haebom.elder.guide.GuideCaption
import kr.haebom.elder.guide.GuideSession
import kr.haebom.elder.guide.GuideTask
import kr.haebom.elder.net.HaebomServer
import kr.haebom.elder.ui.ElderUi

/**
 * 말하기 창 (다른 앱 위 마이크나 메인 화면에서 열림).
 * - 들리는 말을 실시간으로 이 창과 큰 자막에 같이 보여 줌
 * - 결과를 문장으로 알려 줌: "못 알아들었어요", "유튜브를 열고 검색 버튼을 찾아 드릴게요" 등
 * - SOS: 저장된 보호자 번호로 문자 앱을 엶 (보내기는 어르신이 직접 누름). 번호가 없으면 입력받음
 */
class GuideInputActivity : Activity() {
    private var recognizer: SpeechRecognizer? = null
    private var handlingResult = false
    private var status: TextView? = null
    private var heard: TextView? = null
    private val settings by lazy { ElderSettings(this) }
    private val foregroundPackage: String?
        get() = intent.getStringExtra(EXTRA_FOREGROUND_PACKAGE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (intent.getBooleanExtra(EXTRA_SOS, false)) {
            openSms()
            return
        }

        val pad = ElderUi.dp(this, 20)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(ElderUi.PAGE)
            status = ElderUi.text(context, getString(R.string.voice_prompt), 22f, ElderUi.INK, bold = true).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }.also { addView(it) }
            heard = ElderUi.text(context, "", 26f, ElderUi.NAVY_DARK, bold = true).apply {
                visibility = View.GONE
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }.also { addView(it) }
            val request = ElderUi.input(context, getString(R.string.voice_request_hint)).apply {
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                isSaveEnabled = false
            }
            addView(request)
            addView(ElderUi.button(context, getString(R.string.voice_submit)) {
                val text = request.text.toString()
                releaseRecognizer()
                handleCommand(text)
            })
            addView(ElderUi.button(context, "🎤 " + getString(R.string.voice_tap), ElderUi.Kind.SECONDARY) { requestSpeech() })
            addView(ElderUi.button(context, getString(R.string.cancel), ElderUi.Kind.QUIET) { finish() })
        })
        window.decorView.post { if (!isFinishing) requestSpeech() }
    }

    private fun setStatus(textId: Int, tone: GuideCaption.Tone, caption: Boolean = true) {
        val text = getString(textId)
        status?.text = text
        if (caption) GuideCaption.post(text, tone)
    }

    private fun showHeard(text: String) {
        heard?.text = "“$text”"
        heard?.visibility = View.VISIBLE
        GuideCaption.heard(text)
    }

    private fun requestSpeech() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            setStatus(R.string.voice_device_unavailable, GuideCaption.Tone.WARN)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MICROPHONE_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            setStatus(R.string.voice_permission_denied, GuideCaption.Tone.WARN)
        }
    }

    private fun startListening() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        releaseRecognizer()
        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        val speech = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } catch (_: UnsupportedOperationException) {
            setStatus(R.string.voice_device_unavailable, GuideCaption.Tone.WARN)
            return
        }
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                setStatus(R.string.caption_listening, GuideCaption.Tone.LISTEN)
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                setStatus(R.string.caption_processing, GuideCaption.Tone.INFO, caption = false)
            }

            override fun onError(error: Int) {
                if (isFinishing || recognizer !== speech) return
                releaseRecognizer()
                when (error) {
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        setStatus(R.string.voice_device_unavailable, GuideCaption.Tone.WARN)
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        setStatus(R.string.caption_no_speech, GuideCaption.Tone.WARN)
                    else -> setStatus(R.string.caption_unrecognized, GuideCaption.Tone.WARN)
                }
            }

            override fun onResults(results: Bundle?) {
                if (isFinishing || recognizer !== speech) return
                handlingResult = true
                releaseRecognizer()
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                handleCommand(spoken)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (partial.isNotBlank()) showHeard(partial)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        speech.startListening(speechIntent)
    }

    /** 들은 말(또는 입력한 글)을 판단하고, 무엇을 할지 문장으로 먼저 알려 줌 */
    private fun handleCommand(spoken: String) {
        if (spoken.isBlank()) {
            setStatus(R.string.caption_unrecognized, GuideCaption.Tone.WARN)
            return
        }
        showHeard(spoken)
        val assessment = Sensitivity.fromCommand(spoken, foregroundPackage)
        // L3 사기 의심: 동의 없이 바로 보호자에게 알리고 안내를 멈춤
        if (assessment.level == Level.L3) {
            GuideSession.stop()
            handlingResult = true
            GuideCaption.post(getString(R.string.alert_scam), GuideCaption.Tone.WARN, durationMs = 12_000)
            SensitiveAlerter.send(this, assessment, appLabel(foregroundPackage), foregroundPackage.orEmpty())
            AlertDialog.Builder(this)
                .setMessage(R.string.alert_scam)
                .setPositiveButton(R.string.sos) { _, _ -> openSms() }
                .setNegativeButton(R.string.alert_stop) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        if (GuideTask.isHelpCommand(spoken)) {
            openSms()
            return
        }
        if (GuideTask.isPrivateCommand(spoken)) {
            GuideSession.stop()
            GuideCaption.post(getString(R.string.caption_private), GuideCaption.Tone.WARN, durationMs = 10_000)
            showGuideMessage(this, R.string.guide_authentication_stop, ::finish)
            return
        }
        // L2 금융: 막지 않음. 경고 + 동의 없이 바로 알림 + 계속할지 어르신이 고름
        if (assessment.level == Level.L2) {
            warnFinance(spoken, assessment)
            return
        }
        val task = GuideTask.forCommand(spoken, foregroundPackage)
        if (task == null && HaebomServer.api != null) {
            // 앱이 모르는 일: 화면 안내 AI 가 지금 화면을 보고 하나씩 안내
            handlingResult = true
            val text = getString(R.string.caption_heard_task, spoken, getString(R.string.caption_ai_started))
            status?.text = text
            GuideCaption.post(text, GuideCaption.Tone.INFO)
            startAiGuide(this, spoken, foregroundPackage, ::finish)
        } else if (task == null) {
            handlingResult = false
            setStatus(R.string.caption_unrecognized, GuideCaption.Tone.WARN)
        } else {
            handlingResult = true
            val next = getString(task.nextStepText())
            val text = getString(R.string.caption_heard_task, spoken, next)
            status?.text = text
            GuideCaption.post(text, GuideCaption.Tone.INFO)
            val query = if (task == GuideTask.YOUTUBE) GuideTask.youtubeQuery(spoken) else null
            startGuide(this, task, foregroundPackage, ::finish, announce = false, query = query, utterance = spoken)
        }
    }

    private fun warnFinance(spoken: String, assessment: Assessment) {
        GuideSession.stop()
        handlingResult = true
        val bankPackage = Sensitivity.financeAppFor(spoken)
            ?.takeIf { packageManager.getLaunchIntentForPackage(it) != null }
        val target = bankPackage ?: foregroundPackage?.takeIf { SensitiveApps.category(it) == SensitiveApps.CATEGORY_FINANCE }
        if (SensitiveAlerter.claim(SensitiveAlerter.keyFor(assessment, target))) {
            SensitiveAlerter.send(this, assessment, appLabel(target), target.orEmpty())
        }
        GuideCaption.post(getString(R.string.alert_finance_command), GuideCaption.Tone.WARN, durationMs = 10_000)
        AlertDialog.Builder(this)
            .setMessage(R.string.alert_finance_command)
            .setPositiveButton(R.string.alert_continue) { _, _ ->
                val launch = bankPackage?.let { packageManager.getLaunchIntentForPackage(it) }
                when {
                    launch != null -> startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    target == null -> GuideCaption.post(getString(R.string.alert_open_bank), GuideCaption.Tone.INFO)
                }
                finish()
            }
            .setNegativeButton(R.string.alert_stop) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** 패키지 → 앱 이름 (없으면 "금융 요청") */
    private fun appLabel(pkg: String?): String = pkg?.let {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull()
    } ?: getString(R.string.alert_app_unknown)

    /**
     * 도움 요청 문자. 저장된 보호자가 한 명이면 바로, 여러 명이면 고르게, 없으면 번호를 입력받음.
     * 문자는 문자 앱에서 어르신이 확인하고 직접 보냄.
     */
    private fun openSms() {
        GuideSession.stop()
        GuideCaption.post(getString(R.string.caption_sos), GuideCaption.Tone.INFO)
        val contacts = settings.sosContacts(Family.gateway?.links().orEmpty())
        when (contacts.size) {
            0 -> askNumber()
            1 -> sendTo(contacts.single())
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.sos_choose)
                .setItems(contacts.map { "${it.name} (${ElderSettings.displayPhone(it.phone)})" }.toTypedArray()) { _, which ->
                    sendTo(contacts[which])
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    private fun askNumber() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ElderUi.dp(context, 20)
            setPadding(pad, 0, pad, 0)
        }
        val input = ElderUi.input(this, getString(R.string.sos_number), phone = true)
        box.addView(input)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.sos_number)
            .setMessage(R.string.sos_explanation)
            .setView(box)
            .setPositiveButton(R.string.sos_open_sms, null)
            .setNeutralButton(R.string.sos_save_number, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.show()
        fun number(): String? = input.text.toString().takeIf(ElderSettings::isValidPhone)
            ?: run { input.error = getString(R.string.sos_invalid_number); null }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val phone = number() ?: return@setOnClickListener
            dialog.dismiss()
            sendTo(SosContact(getString(R.string.family_title), phone))
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val phone = number() ?: return@setOnClickListener
            settings.manualContact = SosContact(kr.haebom.elder.family.FamilyLink.DEFAULT_GUARDIAN_LABEL, phone)
            dialog.dismiss()
            sendTo(settings.manualContact!!)
        }
    }

    private fun sendTo(contact: SosContact) {
        val sms = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", ElderSettings.normalizePhone(contact.phone), null))
            .putExtra("sms_body", getString(R.string.sos_message))
        try {
            startActivity(sms)
            finish()
        } catch (_: ActivityNotFoundException) {
            GuideCaption.post(getString(R.string.sos_sms_unavailable), GuideCaption.Tone.WARN)
            showGuideMessage(this, R.string.sos_sms_unavailable, ::finish)
        }
    }

    override fun onPause() {
        if (recognizer != null && !handlingResult) {
            releaseRecognizer()
            finish()
        }
        super.onPause()
    }

    override fun onDestroy() {
        releaseRecognizer()
        super.onDestroy()
    }

    private fun releaseRecognizer() {
        val speech = recognizer ?: return
        recognizer = null
        speech.cancel()
        speech.destroy()
    }

    companion object {
        private const val EXTRA_SOS = "sos"
        private const val EXTRA_FOREGROUND_PACKAGE = "foreground_package"
        private const val MICROPHONE_REQUEST = 8

        fun intent(context: Context, sos: Boolean = false, foregroundPackage: String? = null): Intent =
            Intent(context, GuideInputActivity::class.java)
                .putExtra(EXTRA_SOS, sos)
                .putExtra(EXTRA_FOREGROUND_PACKAGE, foregroundPackage)
    }
}
