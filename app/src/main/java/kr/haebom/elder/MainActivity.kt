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
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import kr.haebom.elder.family.ElderSettings
import kr.haebom.elder.family.Family
import kr.haebom.elder.family.FamilyActivity
import kr.haebom.elder.firebase.ElderFirebaseConnection
import kr.haebom.elder.guide.GuideCaption
import kr.haebom.elder.guide.GuideService
import kr.haebom.elder.guide.GuideSession
import kr.haebom.elder.guide.GuideTask
import kr.haebom.elder.ui.ElderUi
import kr.haebom.elder.ui.ElderUi.visible

/**
 * 첫 화면. 위에서부터 "지금 상태 → 도움 받기 → 가족 → 설정" 순서로, 단추는 크게 글자는 또렷하게.
 * 개발자 도구는 debug 빌드에서만 보입니다.
 */
class MainActivity : Activity() {
    private val firebaseHandler = Handler(Looper.getMainLooper())
    private var firebaseCheck = 0
    private val settings by lazy { ElderSettings(this) }

    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusHelp: TextView
    private lateinit var openSettings: Button
    private lateinit var guideButtons: List<Button>
    private lateinit var stopButton: Button
    private lateinit var sosButton: Button
    private lateinit var familySummary: TextView
    private var familySubscription: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = ElderUi.page(this)
        page.addView(ElderUi.title(this, getString(R.string.app_name)))
        page.addView(ElderUi.body(this, getString(R.string.main_greeting), 21f, ElderUi.INK))

        // 지금 상태
        statusCard = ElderUi.card(this)
        statusTitle = ElderUi.text(this, "", 23f, ElderUi.INK, bold = true)
        statusHelp = ElderUi.body(this, "", 19f)
        openSettings = ElderUi.button(this, getString(R.string.open_accessibility_settings), ElderUi.Kind.SECONDARY) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        statusCard.addView(statusTitle)
        statusCard.addView(statusHelp)
        statusCard.addView(openSettings)
        page.addView(statusCard)

        // 도움 받기
        page.addView(ElderUi.section(this, getString(R.string.main_section_help)))
        val voice = ElderUi.button(this, getString(R.string.voice_command)) {
            startActivity(GuideInputActivity.intent(this))
        }.apply { minHeight = ElderUi.dp(context, 84); textSize = 25f }
        page.addView(voice)
        val contacts = ElderUi.button(this, getString(R.string.command_contacts), ElderUi.Kind.SECONDARY) {
            startGuide(this, GuideTask.CONTACTS)
        }
        val youtube = ElderUi.button(this, getString(R.string.command_youtube), ElderUi.Kind.SECONDARY) {
            startGuide(this, GuideTask.YOUTUBE)
        }
        val gov24 = ElderUi.button(this, getString(R.string.command_gov24), ElderUi.Kind.SECONDARY) {
            startGuide(this, GuideTask.GOV24)
        }
        listOf(contacts, youtube, gov24).forEach(page::addView)
        stopButton = ElderUi.button(this, getString(R.string.stop_guide), ElderUi.Kind.QUIET) {
            GuideSession.stop()
            GuideCaption.post(getString(R.string.caption_guide_stopped), GuideCaption.Tone.DONE)
            updateGuideStatus()
        }
        page.addView(stopButton)
        val practice = ElderUi.button(this, getString(R.string.open_practice), ElderUi.Kind.QUIET) {
            GuideSession.stop()
            startActivity(Intent(this, GuidePracticeActivity::class.java))
        }
        page.addView(practice)
        guideButtons = listOf(voice, contacts, youtube, gov24, practice)

        // 가족
        page.addView(ElderUi.section(this, getString(R.string.main_section_family)))
        familySummary = ElderUi.body(this, "", 19f)
        page.addView(familySummary)
        page.addView(ElderUi.button(this, getString(R.string.main_open_family), ElderUi.Kind.SECONDARY) {
            startActivity(Intent(this, FamilyActivity::class.java))
        })
        sosButton = ElderUi.button(this, getString(R.string.sos), ElderUi.Kind.DANGER) {
            startActivity(GuideInputActivity.intent(this, sos = true))
        }.apply { minHeight = ElderUi.dp(context, 76) }
        page.addView(sosButton)

        // 설정
        page.addView(ElderUi.section(this, getString(R.string.main_section_settings)))
        page.addView(switchRow(getString(R.string.main_sos_switch), getString(R.string.main_sos_switch_help), settings.sosEnabled) {
            settings.sosEnabled = it
            sosButton.visible(it)
        })
        page.addView(switchRow(getString(R.string.main_sensitive_switch), getString(R.string.main_sensitive_switch_help), settings.sensitiveAlerts) {
            settings.sensitiveAlerts = it
        })

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) addDeveloperTools(page)
    }

    override fun onStart() {
        super.onStart()
        familySubscription = Family.gateway?.observe { renderFamily() }
        if (familySubscription == null) renderFamily()
    }

    override fun onStop() {
        familySubscription?.close()
        familySubscription = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateGuideStatus()
        sosButton.visible(settings.sosEnabled)
    }

    override fun onDestroy() {
        firebaseHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun renderFamily() {
        familySummary.text = getString(R.string.main_family_summary, Family.gateway?.links()?.size ?: 0)
    }

    private fun switchRow(title: String, help: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
        ElderUi.card(this).apply {
            addView(Switch(context).apply {
                text = title
                textSize = 21f
                setTextColor(ElderUi.INK)
                isChecked = checked
                minHeight = ElderUi.dp(context, 56)
                setOnCheckedChangeListener { _, on -> onChange(on) }
            })
            help?.let { addView(ElderUi.body(context, it, 17f)) }
        }

    private fun addDeveloperTools(page: LinearLayout) {
        page.addView(ElderUi.section(this, getString(R.string.main_section_dev)))
        page.addView(ElderUi.button(this, getString(R.string.open_mock_guardian), ElderUi.Kind.QUIET) {
            startActivity(Intent().setClassName(packageName, "kr.haebom.elder.guardian.MockGuardianActivity"))
        })
        page.addView(ElderUi.button(this, getString(R.string.open_dev_viewer), ElderUi.Kind.QUIET) {
            startActivity(Intent().setClassName(packageName, "kr.haebom.elder.dev.DevViewerActivity"))
        })
        val firebaseStatus = ElderUi.body(this, "", 17f)
        val check = ElderUi.button(this, getString(R.string.firebase_check), ElderUi.Kind.QUIET) {}
        check.setOnClickListener { verifyFirebase(check, firebaseStatus) }
        page.addView(check)
        page.addView(firebaseStatus)
    }

    private fun verifyFirebase(button: Button, status: TextView) {
        val check = ++firebaseCheck
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
        statusTitle.setText(if (enabled) R.string.main_status_on else R.string.main_status_off)
        statusHelp.setText(if (enabled) R.string.main_status_on_help else R.string.main_status_off_help)
        statusTitle.setTextColor(if (enabled) ElderUi.GREEN else ElderUi.RED)
        statusCard.background = ElderUi.rounded(
            if (enabled) ElderUi.SOFT_GREEN else ElderUi.SOFT_RED,
            ElderUi.dp(this, 20).toFloat(), ElderUi.dp(this, 2), if (enabled) ElderUi.GREEN else ElderUi.RED,
        )
        openSettings.visible(!enabled)
        guideButtons.forEach { it.isEnabled = enabled }
        stopButton.isEnabled = GuideSession.active != null
    }
}
