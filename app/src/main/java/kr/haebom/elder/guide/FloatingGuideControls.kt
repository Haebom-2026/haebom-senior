package kr.haebom.elder.guide

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kr.haebom.elder.R
import kotlin.math.abs

/**
 * 다른 앱 위에 떠 있는 마이크·SOS 묶음.
 * - 묶음 어디를 잡아도 끌어서 옮김. 놓으면 가까운 가장자리로 붙고 그 자리를 기억함
 * - 눌러야 할 곳·자막·키보드를 가리면 스스로 비켜 줌 ([avoid]). 가릴 것이 없어지면 기억한 자리로 돌아감
 * - 안내 중에는 마이크가 숨 쉬듯 커졌다 작아짐 ([setGuiding])
 * - SOS 는 설정에서 끌 수 있음 ([setSosVisible])
 */
@SuppressLint("RtlHardcoded") // 창 좌표는 실제 화면 좌표를 따름
internal class FloatingGuideControls(
    private val context: Context,
    private val onMicrophone: () -> Unit,
    private val onSos: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("floating_controls", Context.MODE_PRIVATE)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val margin = dp(10)
    private val width = dp(96)
    private val micSize = dp(80)
    private val sosSize = dp(66)
    private val stopSize = dp(66)
    private var panel: LinearLayout? = null
    private var mic: TextView? = null
    private var sos: TextView? = null
    private var stop: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var sosVisible = true
    private var guiding = false
    private var listening = false
    private var blocked: List<Box> = emptyList()
    private var mover: ValueAnimator? = null
    private val pulse = ValueAnimator.ofFloat(1f, 1.1f).apply {
        duration = 900
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            val scale = it.animatedValue as Float
            mic?.scaleX = scale
            mic?.scaleY = scale
        }
    }

    /** 어르신이 끌어다 놓은 자리 (기기에 저장) */
    private var preferred: Point
        get() {
            val screen = screenSize()
            return Point(prefs.getInt(KEY_X, screen.x - width - margin), prefs.getInt(KEY_Y, dp(120)))
        }
        set(value) = prefs.edit().putInt(KEY_X, value.x).putInt(KEY_Y, value.y).apply()

    val isShowing: Boolean get() = panel != null

    private val height: Int
        get() = dp(8) + micSize +
            (if (guiding) dp(12) + stopSize else 0) +
            (if (sosVisible) dp(12) + sosSize else 0) + dp(8)

    fun show() {
        if (panel != null) return
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            clipChildren = false
            clipToPadding = false
        }
        val micButton = circle(context.getString(R.string.floating_mic), "🎤", MIC_COLOR, micSize).also(::paintMic)
        val sosButton = circle(context.getString(R.string.floating_sos), "🆘", Color.rgb(176, 32, 42), sosSize).also {
            it.contentDescription = context.getString(R.string.floating_sos_description)
            it.visibility = if (sosVisible) View.VISIBLE else View.GONE
        }
        // 안내 중에만 보이는 "그만" 단추
        val stopButton = circle(context.getString(R.string.floating_stop), "⏹", Color.rgb(90, 90, 96), stopSize).also {
            it.contentDescription = context.getString(R.string.floating_stop_description)
            it.visibility = if (guiding) View.VISIBLE else View.GONE
        }
        column.addView(micButton, LinearLayout.LayoutParams(micSize, micSize))
        column.addView(stopButton, LinearLayout.LayoutParams(stopSize, stopSize).apply { topMargin = dp(12) })
        column.addView(sosButton, LinearLayout.LayoutParams(sosSize, sosSize).apply { topMargin = dp(12) })
        setupDrag(column, micButton, onMicrophone)
        setupDrag(column, stopButton, onStop)
        setupDrag(column, sosButton, onSos)

        val start = plannedPosition()
        val layout = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = start.first
            y = start.second
        }
        try {
            manager.addView(column, layout)
        } catch (_: WindowManager.BadTokenException) {
            return
        }
        panel = column
        mic = micButton
        sos = sosButton
        stop = stopButton
        params = layout
        applyPulse()
    }

    fun hide() {
        mover?.cancel()
        pulse.cancel()
        val controls = panel ?: return
        panel = null
        mic = null
        sos = null
        stop = null
        params = null
        manager.removeViewImmediate(controls)
    }

    /** 다른 오버레이(빨간 표시 등)를 새로 붙인 뒤 버튼이 그 위에 오도록 다시 붙임 */
    fun bringToFront() {
        if (panel == null) return
        hide()
        show()
    }

    fun setSosVisible(visible: Boolean) {
        if (sosVisible == visible) return
        sosVisible = visible
        val layout = params ?: return
        sos?.visibility = if (visible) View.VISIBLE else View.GONE
        layout.height = height
        panel?.let { manager.updateViewLayout(it, layout) }
        moveTo(plannedPosition())
    }

    /** 듣는 중이면 말하기 단추가 "종료" 단추로 바뀜 (누르면 듣기를 그만둠) */
    fun setListening(active: Boolean) {
        if (listening == active) return
        listening = active
        mic?.let(::paintMic)
        applyPulse()
    }

    private fun paintMic(button: TextView) {
        if (listening) {
            button.text = "⏹\n" + context.getString(R.string.floating_listen_stop)
            button.contentDescription = context.getString(R.string.floating_listen_stop_description)
            (button.background as? GradientDrawable)?.setColor(LISTENING_COLOR)
        } else {
            button.text = "🎤\n" + context.getString(R.string.floating_mic)
            button.contentDescription = context.getString(R.string.floating_mic_description)
            (button.background as? GradientDrawable)?.setColor(MIC_COLOR)
        }
    }

    fun setGuiding(active: Boolean) {
        if (guiding == active) return
        guiding = active
        stop?.visibility = if (active) View.VISIBLE else View.GONE
        params?.let { layout ->
            layout.height = height
            panel?.let { manager.updateViewLayout(it, layout) }
            moveTo(plannedPosition())
        }
        applyPulse()
    }

    /** 가리면 안 되는 곳들(눌러야 할 곳, 자막, 키보드). 바뀌었을 때만 움직임 */
    fun avoid(areas: List<Box>) {
        if (areas == blocked) return
        blocked = areas
        if (panel != null) moveTo(plannedPosition())
    }

    /** 지금 묶음이 차지한 곳 */
    fun bounds(): Box? {
        val layout = params ?: return null
        return Box(layout.x, layout.y, layout.x + width, layout.y + height)
    }

    private fun plannedPosition(): Pair<Int, Int> {
        val screen = screenSize()
        val want = preferred
        return OverlayPlanner.panelPosition(
            screen.x, screen.y, width, height, want.x, want.y, blocked, margin, topInset = dp(36),
        )
    }

    private fun moveTo(target: Pair<Int, Int>) {
        val layout = params ?: return
        val view = panel ?: return
        mover?.cancel()
        val fromX = layout.x
        val fromY = layout.y
        if (fromX == target.first && fromY == target.second) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            layout.x = target.first
            layout.y = target.second
            manager.updateViewLayout(view, layout)
            return
        }
        mover = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val current = params ?: return@addUpdateListener
                current.x = (fromX + (target.first - fromX) * f).toInt()
                current.y = (fromY + (target.second - fromY) * f).toInt()
                panel?.let { p -> manager.updateViewLayout(p, current) }
            }
            start()
        }
    }

    private fun applyPulse() {
        if ((guiding || listening) && panel != null && ValueAnimator.areAnimatorsEnabled()) {
            if (!pulse.isRunning) pulse.start()
        } else {
            pulse.cancel()
            mic?.scaleX = 1f
            mic?.scaleY = 1f
        }
    }

    private fun circle(label: String, icon: String, color: Int, size: Int) = TextView(context).apply {
        text = "$icon\n$label"
        gravity = Gravity.CENTER
        textSize = if (size >= micSize) 17f else 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setLineSpacing(0f, 0.95f)
        elevation = dp(6).toFloat()
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(3), Color.WHITE)
        }
        minimumWidth = size
        minimumHeight = size
    }

    /** 단추를 잡고 움직이면 묶음 전체가 끌리고, 살짝 누르면 단추 동작 */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(group: View, button: View, click: () -> Unit) {
        var startX = 0f
        var startY = 0f
        var initialX = 0
        var initialY = 0
        var dragging = false
        button.setOnClickListener { click() }
        button.setOnTouchListener { view, event ->
            val layout = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mover?.cancel()
                    startX = event.rawX
                    startY = event.rawY
                    initialX = layout.x
                    initialY = layout.y
                    dragging = false
                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        view.isPressed = false
                        val screen = screenSize()
                        layout.x = (initialX + dx.toInt()).coerceIn(0, (screen.x - width).coerceAtLeast(0))
                        layout.y = (initialY + dy.toInt()).coerceIn(0, (screen.y - height).coerceAtLeast(0))
                        manager.updateViewLayout(group, layout)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    if (dragging) {
                        val screen = screenSize()
                        preferred = Point(OverlayPlanner.snapX(layout.x, width, screen.x, margin), layout.y)
                        moveTo(plannedPosition())
                    } else {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    @Suppress("DEPRECATION") // Display#getRealSize: 앱 지원 범위(API 26~) 전체에서 실제 화면 크기
    private fun screenSize(): Point = Point().also { manager.defaultDisplay.getRealSize(it) }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_X = "x"
        const val KEY_Y = "y"
        val MIC_COLOR = Color.rgb(30, 76, 156)
        /** 듣는 중: 눈에 띄는 주황 */
        val LISTENING_COLOR = Color.rgb(214, 92, 0)
    }
}
