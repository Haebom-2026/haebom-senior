package kr.haebom.elder

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kr.haebom.elder.firebase.ElderFirebaseConnection
import kr.haebom.elder.guide.GuideService
import kr.haebom.elder.guide.GuideSession
import kr.haebom.elder.guide.GuideTask

class MainActivity : Activity() {
    private val firebaseHandler = Handler(Looper.getMainLooper())
    private var firebaseCheck = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.open_accessibility_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.open_practice).setOnClickListener {
            GuideSession.stop()
            startActivity(Intent(this, GuidePracticeActivity::class.java))
        }
        findViewById<Button>(R.id.voice_command).setOnClickListener { listen() }
        findViewById<Button>(R.id.guide_contacts).setOnClickListener { startGuide(GuideTask.CONTACTS) }
        findViewById<Button>(R.id.guide_youtube).setOnClickListener { startGuide(GuideTask.YOUTUBE) }
        findViewById<Button>(R.id.guide_gov24).setOnClickListener { startGuide(GuideTask.GOV24) }
        findViewById<Button>(R.id.stop_guide).setOnClickListener {
            GuideSession.stop()
            updateGuideStatus()
        }
        findViewById<Button>(R.id.sos).setOnClickListener { openSos() }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            findViewById<Button>(R.id.check_firebase).apply {
                visibility = View.VISIBLE
                setOnClickListener { verifyFirebase(this) }
            }
            findViewById<TextView>(R.id.firebase_status).visibility = View.VISIBLE
            findViewById<Button>(R.id.open_mock_guardian).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    startActivity(Intent().setClassName(
                        packageName, "kr.haebom.elder.guardian.MockGuardianActivity",
                    ))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateGuideStatus()
    }

    override fun onDestroy() {
        firebaseHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun verifyFirebase(button: Button) {
        val check = ++firebaseCheck
        val status = findViewById<TextView>(R.id.firebase_status)
        button.isEnabled = false
        status.setText(R.string.firebase_checking)

        val timeout = Runnable {
            firebaseCheck++
            status.setText(R.string.firebase_timeout)
            button.isEnabled = true
        }
        firebaseHandler.postDelayed(timeout, 15_000)
        ElderFirebaseConnection.verify(applicationContext) { result ->
            if (isDestroyed || isFinishing || firebaseCheck != check) return@verify
            firebaseHandler.removeCallbacks(timeout)
            status.text = when (result) {
                ElderFirebaseConnection.Result.MissingConfiguration -> getString(R.string.firebase_missing)
                ElderFirebaseConnection.Result.DifferentProject -> getString(
                    R.string.firebase_wrong_project, ElderFirebaseConnection.PROJECT_ID,
                )
                ElderFirebaseConnection.Result.NonAnonymousAccount -> getString(R.string.firebase_not_anonymous)
                is ElderFirebaseConnection.Result.AuthFailed -> getString(R.string.firebase_auth_failed, result.code)
                is ElderFirebaseConnection.Result.FirestoreFailed -> getString(
                    R.string.firebase_firestore_failed, result.code,
                )
                is ElderFirebaseConnection.Result.Connected -> getString(
                    if (result.profileExists) R.string.firebase_connected else R.string.firebase_profile_missing,
                )
            }
            button.isEnabled = true
        }
    }

    private fun updateGuideStatus() {
        val manager = getSystemService(AccessibilityManager::class.java)
        val guide = ComponentName(this, GuideService::class.java)
        val enabled = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service ->
                val info = service.resolveInfo.serviceInfo
                ComponentName(info.packageName, info.name) == guide
            }

        if (!enabled) GuideSession.stop()
        findViewById<TextView>(R.id.guide_status).setText(
            if (enabled) R.string.guide_enabled else R.string.guide_disabled,
        )
        for (id in listOf(R.id.open_practice, R.id.voice_command, R.id.guide_contacts,
            R.id.guide_youtube, R.id.guide_gov24)) {
            findViewById<Button>(id).isEnabled = enabled
        }
        findViewById<Button>(R.id.stop_guide).isEnabled = GuideSession.active != null
    }

    private fun startGuide(task: GuideTask) {
        GuideSession.stop()
        val intent = task.newIntent(packageManager)
        val destination = intent?.resolveActivity(packageManager)?.packageName
        if (intent == null || destination == null || destination == "android") {
            showMessage(R.string.guide_app_unavailable)
            return
        }
        intent.setPackage(destination)

        if (task == GuideTask.GOV24) {
            AlertDialog.Builder(this)
                .setMessage(R.string.guide_gov24_limited)
                .setPositiveButton(R.string.guide_continue) { _, _ -> launch(task, destination, intent) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            launch(task, destination, intent)
        }
    }

    private fun launch(task: GuideTask, destination: String, intent: Intent) {
        GuideSession.start(task, destination)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            GuideSession.stop()
            showMessage(R.string.guide_app_unavailable)
        }
    }

    @Suppress("DEPRECATION")
    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        try {
            startActivityForResult(intent, SPEECH_REQUEST)
        } catch (_: ActivityNotFoundException) {
            showMessage(R.string.voice_unavailable)
        }
    }

    @Deprecated("Used with the platform speech input activity")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SPEECH_REQUEST || resultCode != RESULT_OK) return

        val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (GuideTask.isScamCommand(spoken)) {
            GuideSession.stop()
            AlertDialog.Builder(this)
                .setMessage(R.string.scam_warning)
                .setPositiveButton(R.string.sos) { _, _ -> openSos() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        if (GuideTask.isHelpCommand(spoken)) {
            openSos()
            return
        }
        val task = GuideTask.fromCommand(spoken)
        if (task == null) showMessage(R.string.voice_unrecognized) else startGuide(task)
    }

    private fun openSos() {
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
            .setNegativeButton(android.R.string.cancel, null)
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
            } catch (_: ActivityNotFoundException) {
                dialog.dismiss()
                showMessage(R.string.sos_sms_unavailable)
            }
        }
    }

    private fun showMessage(message: Int) {
        AlertDialog.Builder(this).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private companion object {
        const val SPEECH_REQUEST = 1
    }
}
