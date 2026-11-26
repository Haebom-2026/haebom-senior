package kr.haebom.elder.guide

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import kr.haebom.elder.R
import kr.haebom.elder.family.Assessment
import kr.haebom.elder.family.Family
import kr.haebom.elder.family.Level
import kr.haebom.elder.family.SensitiveAlerter
import kr.haebom.elder.family.Sensitivity
import kr.haebom.elder.ui.ElderUi

/**
 * 민감 앱(정부24 등 L1)에 들어왔을 때 묻는 "보호자에게 알릴까요?" 카드.
 *
 * 예전에는 따로 창(Activity)으로 띄웠는데, 바로 뒤에 그 앱이 자기 화면을 띄우면 창이 뒤로 덮여 사라졌음.
 * 그래서 접근성 오버레이로 다른 앱 위에 띄움: 앱이 화면을 바꿔도 대답할 때까지 남아 있음.
 * "예" 를 눌러야만 #6 을 보냄. "아니요" 는 아무것도 보내지 않음. 카드 밖 터치는 앱으로 통과.
 */
internal class ConsentCard(private val context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: LinearLayout? = null
    private var shownFor: String? = null

    val isShowing: Boolean get() = view != null

    fun show(packageName: String, appName: String, category: String) {
        if (shownFor == packageName && view != null) return
        hide()
        val pad = ElderUi.dp(context, 22)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = ElderUi.rounded(ElderUi.CARD, ElderUi.dp(context, 24).toFloat(), ElderUi.dp(context, 3), ElderUi.NAVY)
            elevation = ElderUi.dp(context, 12).toFloat()
        }
        card.addView(ElderUi.text(context, "🔒 $appName", 28f, ElderUi.INK, bold = true))
        card.addView(ElderUi.text(context, context.getString(R.string.consent_question), 25f, ElderUi.INK))
        card.addView(ElderUi.text(context, context.getString(R.string.consent_detail), 21f, ElderUi.MUTED))
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // 누르면 카드는 바로 닫고, 결과는 큰 자막으로만 알림 (카드 안 글자를 바꾸지 않음)
        buttons.addView(ElderUi.button(context, context.getString(R.string.consent_yes)) {
            hide()
            if (Family.gateway == null) {
                GuideCaption.post(context.getString(R.string.consent_unavailable), GuideCaption.Tone.WARN)
                return@button
            }
            GuideCaption.post(context.getString(R.string.consent_sending), GuideCaption.Tone.INFO)
            val assessment = Sensitivity.fromApp(packageName).takeIf { it.level != Level.L0 }
                ?: Assessment(Level.L1, category, "공공 민원 앱 사용")
            // 결과 문장("○○ 님에게 알렸어요" 등)은 SensitiveAlerter 가 자막에 띄움
            SensitiveAlerter.send(context, assessment.copy(reason = assessment.reason + " (어르신 동의)"), appName, packageName)
        })
        buttons.addView(ElderUi.button(context, context.getString(R.string.consent_no), ElderUi.Kind.QUIET) {
            hide()
            GuideCaption.post(context.getString(R.string.consent_declined), GuideCaption.Tone.INFO)
        })
        card.addView(buttons)

        val side = ElderUi.dp(context, 16)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            horizontalMargin = 0f
        }
        val host = LinearLayout(context).apply {
            setPadding(side, 0, side, 0)
            addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        try {
            manager.addView(host, params)
        } catch (_: WindowManager.BadTokenException) {
            return
        }
        view = host
        shownFor = packageName
        GuideCaption.post(context.getString(R.string.consent_question), GuideCaption.Tone.INFO)
    }

    fun hide() {
        val current = view ?: return
        view = null
        shownFor = null
        manager.removeViewImmediate(current)
    }

    /** 다른 오버레이를 붙인 뒤 카드가 맨 위에 오도록 */
    fun bringToFront() {
        val current = view ?: return
        val params = current.layoutParams as? WindowManager.LayoutParams ?: return
        manager.removeViewImmediate(current)
        try {
            manager.addView(current, params)
        } catch (_: WindowManager.BadTokenException) {
            view = null
            shownFor = null
        }
    }
}
