package kr.haebom.elder

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
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
        findViewById<Button>(R.id.voice_command).setOnClickListener {
            startActivity(GuideInputActivity.intent(this))
        }
        findViewById<Button>(R.id.guide_contacts).setOnClickListener { startGuide(this, GuideTask.CONTACTS) }
        findViewById<Button>(R.id.guide_youtube).setOnClickListener { startGuide(this, GuideTask.YOUTUBE) }
        findViewById<Button>(R.id.guide_gov24).setOnClickListener { startGuide(this, GuideTask.GOV24) }
        findViewById<Button>(R.id.stop_guide).setOnClickListener {
            GuideSession.stop()
            updateGuideStatus()
        }
        findViewById<Button>(R.id.sos).setOnClickListener {
            startActivity(GuideInputActivity.intent(this, sos = true))
        }
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
            findViewById<Button>(R.id.open_dev_viewer).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    startActivity(Intent().setClassName(packageName, "kr.haebom.elder.dev.DevViewerActivity"))
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
}
