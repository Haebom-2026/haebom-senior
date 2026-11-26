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

    /**
     * 어르신용 큰 글씨 확인 창 (안드로이드 기본 창은 글씨가 작아서).
     * [title] 은 선택, [message] 는 24sp. 단추는 큰 단추로 세로로 쌓음. 바깥을 눌러도 닫히지 않음.
     * [onCancel] 은 뒤로 가기로 닫을 때.
     */
    fun dialog(
        activity: Activity,
        message: CharSequence,
        positive: CharSequence,
        onPositive: () -> Unit,
        negative: CharSequence? = null,
        onNegative: () -> Unit = {},
        title: CharSequence? = null,
        positiveKind: Kind = Kind.PRIMARY,
        onCancel: () -> Unit = onNegative,
    ): android.app.AlertDialog {
        val pad = dp(activity, 22)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(activity, 14))
        }
        title?.let { body.addView(text(activity, it, 26f, INK, bold = true)) }
        body.addView(text(activity, message, 24f, INK).apply { setLineSpacing(0f, 1.25f) })
        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(ScrollView(activity).apply { addView(body) })
            .setOnCancelListener { onCancel() }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        body.addView(button(activity, positive, positiveKind) { dialog.dismiss(); onPositive() })
        negative?.let { label ->
            body.addView(button(activity, label, Kind.QUIET) { dialog.dismiss(); onNegative() })
        }
        dialog.show()
        return dialog
    }

    /** 화면 맨 위 남색 머리 카드 (제목·인사·상태를 담음) */
    fun hero(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(context, 22)
        setPadding(pad, dp(context, 24), pad, dp(context, 20))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(30, 76, 156), Color.rgb(18, 46, 99)),
        ).apply { cornerRadius = dp(context, 28).toFloat() }
        elevation = dp(context, 4).toFloat()
    }

    /** 머리 카드 안의 둥근 상태 표시 (예: "✅ 화면 안내가 켜져 있어요") */
    fun pill(context: Context, text: CharSequence, fill: Int, ink: Int) = TextView(context).apply {
        this.text = text
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ink)
        val h = dp(context, 14)
        setPadding(h, dp(context, 8), h, dp(context, 8))
        background = rounded(fill, dp(context, 40).toFloat())
    }

    /**
     * 정사각형에 가까운 큰 바둑판 단추 (큰 그림 글자 + 이름). 두 개씩 한 줄에 놓음.
     * [label] 은 줄바꿈("\n") 가능
     */
    fun tile(context: Context, icon: String, label: CharSequence, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(context, 150)
            val pad = dp(context, 12)
            setPadding(pad, pad, pad, pad)
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(40, 30, 76, 156)),
                rounded(CARD, dp(context, 24).toFloat(), dp(context, 2), LINE),
                null,
            )
            elevation = dp(context, 2).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = label.toString().replace("\n", " ")
            addView(TextView(context).apply {
                text = icon
                textSize = 42f
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = label
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(NAVY_DARK)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.1f)
                setPadding(0, dp(context, 6), 0, 0)
            })
            setOnClickListener { onClick() }
        }

    /** 두 개씩 나란히 놓는 줄 (바둑판 단추용) */
    fun pairRow(context: Context, left: View, right: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val gap = dp(context, 12)
        addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = gap / 2 })
        addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = gap / 2 })
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = gap }
    }

    /** 고르기 카드 (안내 정도 등). [selected] 면 남색 굵은 테두리 + 연한 파랑 + "✓ 지금 단계" */
    fun choiceCard(
        context: Context,
        title: CharSequence,
        help: CharSequence,
        selected: Boolean,
        selectedLabel: CharSequence,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(context, 18)
        setPadding(pad, pad, pad, pad)
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(40, 30, 76, 156)),
            rounded(
                if (selected) SOFT_BLUE else CARD, dp(context, 20).toFloat(),
                dp(context, if (selected) 4 else 2), if (selected) NAVY else LINE,
            ),
            null,
        )
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(context, 10) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(context, title, 23f, INK, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) addView(pill(context, selectedLabel, NAVY, Color.WHITE).apply { textSize = 15f })
        })
        addView(body(context, help, 18f))
        contentDescription = "$title. ${help.toString().trimEnd('.')}" + if (selected) ". $selectedLabel" else ""
        setOnClickListener { onClick() }
    }

    /** "123456" → "1 2 3 4 5 6" (어르신이 한 자씩 불러 주기 쉽게) */
    fun spaced(code: String): String = code.toCharArray().joinToString(" ")
}
