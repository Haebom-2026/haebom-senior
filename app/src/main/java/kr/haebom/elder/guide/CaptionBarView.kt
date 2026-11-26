package kr.haebom.elder.guide

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 큰 자막 막대. 위 줄은 무엇인지(안내/듣는 중/들은 말/알림/주의/완료), 아래 줄은 내용.
 * 색과 함께 글자 머리표를 같이 써서 색을 구분하기 어려운 분도 알 수 있게 합니다.
 */
internal class CaptionBarView(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val tag = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.argb(230, 255, 255, 255))
    }
    private val body = TextView(context).apply {
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setLineSpacing(0f, 1.15f)
        maxLines = 5
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val pad = (16 * density).toInt()
        setPadding(pad + (4 * density).toInt(), pad, pad, pad)
        addView(tag)
        addView(body)
    }

    fun show(text: String, tone: GuideCaption.Tone) {
        val (label, color) = when (tone) {
            GuideCaption.Tone.GUIDE -> "👉 안내" to Color.rgb(18, 46, 99)
            GuideCaption.Tone.LISTEN -> "🎤 듣고 있어요" to Color.rgb(30, 76, 156)
            GuideCaption.Tone.HEARD -> "💬 말씀하신 내용" to Color.rgb(45, 52, 64)
            GuideCaption.Tone.INFO -> "ℹ️ 알림" to Color.rgb(18, 46, 99)
            GuideCaption.Tone.WARN -> "⚠️ 잠깐만요" to Color.rgb(150, 28, 36)
            GuideCaption.Tone.DONE -> "✅ 완료" to Color.rgb(24, 104, 52)
        }
        tag.text = label
        body.text = text
        background = GradientDrawable().apply {
            setColor(Color.argb(242, Color.red(color), Color.green(color), Color.blue(color)))
            cornerRadius = 22 * density
            setStroke((3 * density).toInt(), Color.rgb(255, 120, 120).takeIf { tone == GuideCaption.Tone.GUIDE } ?: Color.argb(90, 255, 255, 255))
        }
    }
}
