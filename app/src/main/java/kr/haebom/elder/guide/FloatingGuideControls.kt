package kr.haebom.elder.guide

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import kr.haebom.elder.R
import kotlin.math.abs

@SuppressLint("RtlHardcoded") // Window coordinates follow physical screen coordinates.
internal class FloatingGuideControls(
    private val context: Context,
    private val onMicrophone: () -> Unit,
    private val onSos: () -> Unit,
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val width = dp(96)
    private val height = dp(108)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var panel: LinearLayout? = null
    private var position: Point? = null
    private var params: WindowManager.LayoutParams? = null

    fun show() {
        if (panel != null) return
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(button(R.string.floating_mic, Color.rgb(30, 76, 156), onMicrophone).apply {
                setupDrag(this)
            })
            addView(button(R.string.floating_sos, Color.rgb(164, 33, 49), onSos))
        }
        val screen = screenSize()
        val target = position ?: Point(screen.x - width - dp(12), dp(110))
        clamp(target, screen)
        val layout = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = target.x
            y = target.y
        }
        try {
            manager.addView(controls, layout)
        } catch (_: WindowManager.BadTokenException) {
            return
        }
        position = target
        params = layout
        panel = controls
    }

    fun hide() {
        val controls = panel ?: return
        panel = null
        params = null
        manager.removeViewImmediate(controls)
    }

    private fun button(label: Int, background: Int, click: () -> Unit): Button = Button(context).apply {
        setText(label)
        textSize = 17f
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(background)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54))
        setOnClickListener { click() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(button: Button) {
        var startX = 0f
        var startY = 0f
        var initialX = 0
        var initialY = 0
        var dragging = false
        button.setOnTouchListener { view, event ->
            val location = position ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initialX = location.x
                    initialY = location.y
                    dragging = false
                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startX
                    val deltaY = event.rawY - startY
                    if (abs(deltaX) > slop || abs(deltaY) > slop) dragging = true
                    if (dragging) {
                        view.isPressed = false
                        location.set(initialX + deltaX.toInt(), initialY + deltaY.toInt())
                        clamp(location, screenSize())
                        val layout = params ?: return@setOnTouchListener true
                        layout.x = location.x
                        layout.y = location.y
                        panel?.let { manager.updateViewLayout(it, layout) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    if (!dragging) view.performClick()
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

    @Suppress("DEPRECATION") // Display#getSize supports the app's full Android version range.
    private fun screenSize(): Point = Point().also { manager.defaultDisplay.getSize(it) }

    private fun clamp(position: Point, screen: Point) {
        position.x = position.x.coerceIn(0, (screen.x - width).coerceAtLeast(0))
        position.y = position.y.coerceIn(0, (screen.y - height).coerceAtLeast(0))
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
