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
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kr.haebom.elder.guide.GuideSession
import kr.haebom.elder.guide.GuideTask

class GuideInputActivity : Activity() {
    private var recognizer: SpeechRecognizer? = null
    private var handlingResult = false
    private var status: TextView? = null
    private val foregroundPackage: String?
        get() = intent.getStringExtra(EXTRA_FOREGROUND_PACKAGE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (intent.getBooleanExtra(EXTRA_SOS, false)) {
            openSms()
            return
        }

        val padding = (20 * resources.displayMetrics.density).toInt()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            status = TextView(context).apply {
                textSize = 20f
                setText(R.string.voice_prompt)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }.also { addView(it) }
            val request = EditText(context).apply {
                setHint(R.string.voice_request_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                isSaveEnabled = false
            }
            addView(request)
            addView(Button(context).apply {
                setText(R.string.voice_submit)
                setOnClickListener {
                    val text = request.text.toString()
                    releaseRecognizer()
                    handleCommand(text)
                }
            })
            addView(Button(context).apply {
                setText(R.string.voice_tap)
                setOnClickListener { requestSpeech() }
            })
            addView(Button(context).apply {
                setText(android.R.string.cancel)
                setOnClickListener { finish() }
            })
        })
        window.decorView.post { if (!isFinishing) requestSpeech() }
    }

    private fun requestSpeech() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            status?.setText(R.string.voice_device_unavailable)
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
            status?.setText(R.string.voice_permission_denied)
        }
    }

    private fun startListening() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        releaseRecognizer()
        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        val speech = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } catch (_: UnsupportedOperationException) {
            status?.setText(R.string.voice_device_unavailable)
            return
        }
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                status?.setText(R.string.voice_listening)
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                status?.setText(R.string.voice_processing)
            }

            override fun onError(error: Int) {
                if (isFinishing || recognizer !== speech) return
                releaseRecognizer()
                status?.setText(
                    if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                    ) R.string.voice_device_unavailable else R.string.voice_unrecognized,
                )
            }

            override fun onResults(results: Bundle?) {
                if (isFinishing || recognizer !== speech) return
                handlingResult = true
                releaseRecognizer()
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                handleCommand(spoken)
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        speech.startListening(speechIntent)
    }

    private fun handleCommand(spoken: String) {
        if (spoken.isBlank()) {
            status?.setText(R.string.voice_unrecognized)
            return
        }
        if (GuideTask.isScamCommand(spoken)) {
            GuideSession.stop()
            AlertDialog.Builder(this)
                .setMessage(R.string.scam_warning)
                .setPositiveButton(R.string.sos) { _, _ -> openSms() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        if (GuideTask.isHelpCommand(spoken)) {
            openSms()
            return
        }
        if (GuideTask.isFinance(foregroundPackage, spoken)) {
            GuideSession.stop()
            showGuideMessage(this, R.string.guide_bank_stop, ::finish)
            return
        }
        if (GuideTask.isPrivateCommand(spoken)) {
            GuideSession.stop()
            showGuideMessage(this, R.string.guide_authentication_stop, ::finish)
            return
        }
        val task = GuideTask.forCommand(spoken, foregroundPackage)
        if (task == null) {
            handlingResult = false
            status?.setText(R.string.voice_unrecognized)
        } else {
            handlingResult = true
            startGuide(this, task, foregroundPackage, ::finish)
        }
    }

    private fun openSms() {
        GuideSession.stop()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = getString(R.string.sos_number)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.sos_number)
            .setMessage(R.string.sos_explanation)
            .setView(input)
            .setPositiveButton(R.string.sos_open_sms, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val number = input.text.toString().filterNot { it.isWhitespace() || it == '-' }
            if (!number.matches(Regex("\\+?[0-9]{3,15}"))) {
                input.error = getString(R.string.sos_invalid_number)
                return@setOnClickListener
            }
            val sms = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
                .putExtra("sms_body", getString(R.string.sos_message))
            try {
                startActivity(sms)
                dialog.dismiss()
                finish()
            } catch (_: ActivityNotFoundException) {
                dialog.dismiss()
                showGuideMessage(this, R.string.sos_sms_unavailable, ::finish)
            }
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
