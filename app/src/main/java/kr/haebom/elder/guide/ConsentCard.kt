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
 * 지금 화면 위에 바로 띄우는 큰 글씨 카드 (해봄 앱 화면으로 넘어가지 않음).
 * 보호자 알림 동의, 금융·사기 의심 경고, 비밀번호 주의, 정부24 시작 확인에 씀.
 *
 * 접근성 오버레이라 그 앱이 화면을 바꿔도 대답할 때까지 맨 위에 남음.
 * 단추를 누르면 카드는 바로 닫히고 그 단추의 일을 함 (결과는 큰 자막으로).
 * 카드 밖 터치는 아래 앱으로 통과.
 */
internal class OverlayCard(private val context: Context) {
    data class Action(val label: String, val kind: ElderUi.Kind = ElderUi.Kind.PRIMARY, val run: () -> Unit)

    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: LinearLayout? = null
    private var shownKey: String? = null

    val isShowing: Boolean get() = view != null

    /** [key] 가 같은 카드가 떠 있으면 다시 띄우지 않음. [accent] 는 테두리 색 */
    fun show(
        key: String,
        title: String,
        message: String,
        detail: String? = null,
        actions: List<Action>,
        accent: Int = ElderUi.NAVY,
    ) {
        if (shownKey == key && view != null) return
        // "보호자에게 알릴까요?" 카드는 AI 질문 카드로 덮지 않음: 덮으면 5분 동안 다시 묻지 않아 놓치게 됨.
        // 질문은 자막·음성으로 그대로 나오고, 동의 카드에 답하면 다음 화면 갱신 때 질문 카드가 뜸
        if (view != null && shownKey?.startsWith("consent:") == true && key.startsWith("ask:")) {
            android.util.Log.d("HaebomGuide", "카드 미룸: $key (동의 카드에 먼저 답해야 함)")
            return
        }
        hide()
        val pad = ElderUi.dp(context, 22)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = ElderUi.rounded(ElderUi.CARD, ElderUi.dp(context, 24).toFloat(), ElderUi.dp(context, 4), accent)
            elevation = ElderUi.dp(context, 12).toFloat()
        }
        card.addView(ElderUi.text(context, title, 28f, ElderUi.INK, bold = true))
        card.addView(ElderUi.text(context, message, 24f, ElderUi.INK).apply { setLineSpacing(0f, 1.25f) })
        detail?.let { card.addView(ElderUi.text(context, it, 21f, ElderUi.MUTED)) }
        actions.forEach { action ->
            card.addView(ElderUi.button(context, action.label, action.kind) {
                hide()
                action.run()
            })
        }

        val side = ElderUi.dp(context, 16)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
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
        shownKey = key
    }

    fun hide() {
        val current = view ?: return
        // 어느 카드를 누가 닫았는지 추적용
        android.util.Log.d("HaebomGuide", "카드 닫음: $shownKey ← ${Throwable().stackTrace.getOrNull(1)}")
        view = null
        shownKey = null
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
            shownKey = null
        }
    }
}

/**
 * 민감 앱(정부24 등 L1)에 들어오거나 L1 일을 말했을 때 묻는 "보호자에게 알릴까요?".
 * "예" 를 눌러야만 #6 을 보냄. "아니요" 는 아무것도 보내지 않음.
 */
internal object ConsentPrompt {
    fun show(card: OverlayCard, context: Context, packageName: String, appName: String, category: String) {
        card.show(
            key = "consent:$packageName",
            title = "🔒 $appName",
            message = context.getString(R.string.consent_question),
            detail = context.getString(R.string.consent_detail),
            actions = listOf(
                OverlayCard.Action(context.getString(R.string.consent_yes)) {
                    if (Family.gateway == null) {
                        GuideCaption.post(context.getString(R.string.consent_unavailable), GuideCaption.Tone.WARN)
                        return@Action
                    }
                    GuideCaption.post(context.getString(R.string.consent_sending), GuideCaption.Tone.INFO)
                    val assessment = Sensitivity.fromApp(packageName).takeIf { it.level != Level.L0 }
                        ?: Assessment(Level.L1, category, "공공 민원 앱 사용")
                    // 결과 문장("○○ 님에게 알렸어요" 등)은 SensitiveAlerter 가 자막에 띄움
                    SensitiveAlerter.send(context, assessment.copy(reason = assessment.reason + " (어르신 동의)"), appName, packageName)
                },
                OverlayCard.Action(context.getString(R.string.consent_no), ElderUi.Kind.QUIET) {
                    GuideCaption.post(context.getString(R.string.consent_declined), GuideCaption.Tone.INFO)
                },
            ),
        )
        GuideCaption.post(context.getString(R.string.consent_question), GuideCaption.Tone.INFO)
    }
}
