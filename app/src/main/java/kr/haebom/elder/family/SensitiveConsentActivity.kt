package kr.haebom.elder.family

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kr.haebom.elder.R
import kr.haebom.elder.guide.GuideCaption
import kr.haebom.elder.ui.ElderUi

/**
 * 민감 앱(은행·정부24 등)에 들어왔을 때 묻는 창.
 * "예" 를 눌러야만 보호자에게 알림(#6)을 보냅니다. "아니요"·뒤로 가기·바깥 누르기는 아무것도 보내지 않습니다.
 */
class SensitiveConsentActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var message: TextView
    private lateinit var buttons: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setFinishOnTouchOutside(false)
        val appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty()
        val pad = ElderUi.dp(this, 22)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = ElderUi.rounded(ElderUi.CARD, ElderUi.dp(context, 24).toFloat())
        }
        root.addView(ElderUi.text(this, "🔒 $appName", 26f, ElderUi.INK, bold = true))
        message = ElderUi.text(this, getString(R.string.consent_question), 22f, ElderUi.INK)
        root.addView(message)
        root.addView(ElderUi.body(this, getString(R.string.consent_detail), 18f))
        buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buttons.addView(ElderUi.button(this, getString(R.string.consent_yes)) { send() })
        buttons.addView(ElderUi.button(this, getString(R.string.consent_no), ElderUi.Kind.QUIET) { decline() })
        root.addView(buttons)
        setContentView(root)
        GuideCaption.post(getString(R.string.consent_question), GuideCaption.Tone.INFO)
    }

    private fun send() {
        val gateway = Family.gateway
        if (gateway == null) {
            done(getString(R.string.consent_unavailable), GuideCaption.Tone.WARN)
            return
        }
        setButtonsEnabled(false)
        gateway.reportSensitiveEntry(
            appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty(),
            packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty(),
            category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty(),
        ) { outcome ->
            if (isFinishing || isDestroyed) return@reportSensitiveEntry
            val names = outcome.guardianLabels.joinToString(", ") { "$it 님" }
            when (outcome.result) {
                NoticeResult.CREATED -> done(getString(R.string.consent_sent, names), GuideCaption.Tone.DONE)
                NoticeResult.DUPLICATE_SUPPRESSED -> done(getString(R.string.consent_duplicate), GuideCaption.Tone.INFO)
                NoticeResult.NO_GUARDIANS -> done(getString(R.string.consent_no_guardians), GuideCaption.Tone.WARN)
                NoticeResult.FAILED -> done(getString(R.string.consent_failed), GuideCaption.Tone.WARN)
            }
        }
    }

    private fun decline() = done(getString(R.string.consent_declined), GuideCaption.Tone.INFO)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        decline()
    }

    /** 결과를 창과 자막에 보여 주고 잠시 뒤 닫음 (원래 앱으로 돌아감) */
    private fun done(text: String, tone: GuideCaption.Tone) {
        setButtonsEnabled(false)
        message.text = text
        GuideCaption.post(text, tone)
        handler.postDelayed({ if (!isFinishing) finish() }, CLOSE_DELAY_MS)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        for (i in 0 until buttons.childCount) (buttons.getChildAt(i) as? Button)?.isEnabled = enabled
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_APP_NAME = "app_name"
        private const val EXTRA_CATEGORY = "category"
        private const val CLOSE_DELAY_MS = 2_200L

        fun intent(context: Context, packageName: String, appName: String, category: String): Intent =
            Intent(context, SensitiveConsentActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_APP_NAME, appName)
                .putExtra(EXTRA_CATEGORY, category)
    }
}
