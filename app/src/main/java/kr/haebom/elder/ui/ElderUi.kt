package kr.haebom.elder.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 어르신 화면 공통 모양: 큰 글씨, 높은 대비, 큰 단추(최소 64dp), 둥근 카드.
 * 색만 보고 판단하지 않도록 단추에는 항상 글자를 같이 씁니다.
 */
internal object ElderUi {
    val NAVY = Color.rgb(30, 76, 156)
    val NAVY_DARK = Color.rgb(18, 46, 99)
    val INK = Color.rgb(27, 31, 36)
    val MUTED = Color.rgb(84, 92, 104)
    val PAGE = Color.rgb(244, 246, 250)
    val CARD = Color.WHITE
    val LINE = Color.rgb(214, 222, 234)
    val RED = Color.rgb(176, 32, 42)
    val GREEN = Color.rgb(31, 122, 61)
    val YELLOW = Color.rgb(255, 197, 49)
    val SOFT_BLUE = Color.rgb(232, 239, 251)
    val SOFT_RED = Color.rgb(253, 236, 236)
    val SOFT_GREEN = Color.rgb(231, 245, 235)

    enum class Kind { PRIMARY, SECONDARY, DANGER, QUIET }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** 스크롤 되는 화면 한 장. 돌려준 세로 묶음에 내용을 붙이면 됨 */
    fun page(activity: Activity): LinearLayout {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(activity, 20)
            setPadding(pad, dp(activity, 28), pad, dp(activity, 40))
        }
        activity.setContentView(ScrollView(activity).apply {
            setBackgroundColor(PAGE)
            isFillViewport = true
            addView(column)
        })
        return column
    }

    fun title(context: Context, text: CharSequence) = text(context, text, 30f, INK, bold = true)

    fun section(context: Context, text: CharSequence) = text(context, text, 23f, NAVY_DARK, bold = true).apply {
        setPadding(0, dp(context, 26), 0, dp(context, 8))
    }

    fun body(context: Context, text: CharSequence, size: Float = 20f, color: Int = MUTED) = text(context, text, size, color)

    fun text(context: Context, text: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, 1.18f)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(context, 4), 0, dp(context, 4))
    }

    fun button(context: Context, label: CharSequence, kind: Kind = Kind.PRIMARY, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(context, 68)
            stateListAnimator = null
            gravity = Gravity.CENTER
            val (fill, ink, stroke) = when (kind) {
                Kind.PRIMARY -> Triple(NAVY, Color.WHITE, NAVY)
                Kind.SECONDARY -> Triple(Color.WHITE, NAVY_DARK, NAVY)
                Kind.DANGER -> Triple(RED, Color.WHITE, RED)
                Kind.QUIET -> Triple(Color.WHITE, INK, LINE)
            }
            setTextColor(ink)
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 0, 0, 0)),
                rounded(fill, dp(context, 16).toFloat(), dp(context, 2), stroke),
                null,
            )
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 10) }
            setOnClickListener { onClick() }
        }

    /** 흰 바탕 둥근 카드 (안에 세로로 쌓임) */
    fun card(context: Context, fill: Int = CARD, stroke: Int = LINE): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(context, 18)
        setPadding(pad, pad, pad, pad)
        background = rounded(fill, dp(context, 20).toFloat(), dp(context, 1), stroke)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(context, 12) }
    }

    fun input(context: Context, hint: String, value: String = "", phone: Boolean = false, digits: Boolean = false) =
        EditText(context).apply {
            this.hint = hint
            setText(value)
            textSize = 22f
            setTextColor(INK)
            setHintTextColor(MUTED)
            inputType = when {
                digits -> InputType.TYPE_CLASS_NUMBER
                phone -> InputType.TYPE_CLASS_PHONE
                else -> InputType.TYPE_CLASS_TEXT
            }
            minHeight = dp(context, 64)
            val pad = dp(context, 14)
            setPadding(pad, pad, pad, pad)
            background = rounded(Color.WHITE, dp(context, 14).toFloat(), dp(context, 2), LINE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 8) }
        }

    fun rounded(fill: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    fun View.visible(show: Boolean) {
        visibility = if (show) View.VISIBLE else View.GONE
    }

    /** "123456" → "1 2 3 4 5 6" (어르신이 한 자씩 불러 주기 쉽게) */
    fun spaced(code: String): String = code.toCharArray().joinToString(" ")
}
