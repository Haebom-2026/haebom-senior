package kr.haebom.elder.guide

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kr.haebom.elder.R

/**
 * 검색창 바로 아래에 띄우는 "자동완성 입력" 단추 (어르신이 눌러야만 글자를 넣음).
 * 단추를 누르면 [onPick] 으로 그 글자를 돌려줌. 단추 말고 다른 곳은 터치가 통과함.
 */
@SuppressLint("RtlHardcoded")
internal class AutofillChips(
    private val context: Context,
    private val onPick: (String) -> Unit,
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: LinearLayout? = null
    private var shownKey: String? = null

    val isShowing: Boolean get() = view != null

    /** [chips] 를 [anchor](검색창) 바로 아래에 보여 줌. 같은 내용이면 그대로 둠 */
    fun show(chips: List<String>, anchor: Rect) {
        val key = chips.joinToString("\u0001") + "@" + anchor.flattenToString()
        if (key == shownKey && view != null) return
        hide()
        if (chips.isEmpty()) return
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        chips.forEachIndexed { index, chip ->
            column.addView(
                button(context.getString(R.string.chip_autofill, chip), primary = index == 0) { onPick(chip) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { if (index > 0) topMargin = dp(8) },
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            // 빨간 테두리 바깥선과 겹치지 않게 조금 띄움
            y = anchor.bottom + dp(18)
            horizontalMargin = 0f
        }
        column.setPadding(dp(16), dp(4), dp(16), dp(4))
        try {
            manager.addView(column, params)
        } catch (_: WindowManager.BadTokenException) {
            return
        }
        view = column
        shownKey = key
    }

    fun hide() {
        val current = view ?: return
        view = null
        shownKey = null
        manager.removeViewImmediate(current)
    }

    /** 다른 오버레이(빨간 표시 등)를 붙인 뒤 단추가 그 위에 오도록 다시 붙임 */
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

    private fun button(label: String, primary: Boolean, click: () -> Unit) = TextView(context).apply {
        text = "⌨  $label"
        textSize = 21f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else Color.rgb(20, 40, 110))
        minHeight = dp(64)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        elevation = dp(6).toFloat()
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (primary) Color.rgb(30, 76, 156) else Color.WHITE)
            setStroke(dp(3), if (primary) Color.WHITE else Color.rgb(30, 76, 156))
        }
        contentDescription = label
        setOnClickListener { click() }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
