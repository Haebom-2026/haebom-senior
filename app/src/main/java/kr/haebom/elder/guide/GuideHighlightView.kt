package kr.haebom.elder.guide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

internal class GuideHighlightView(context: Context, private val target: Rect?) : View(context) {
    private val density = resources.displayMetrics.density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(194, 24, 24)
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
    }
    private val screenPosition = IntArray(2)
    private val frame = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = target ?: return
        getLocationOnScreen(screenPosition)
        frame.set(bounds)
        frame.offset(-screenPosition[0].toFloat(), -screenPosition[1].toFloat())
        frame.inset(-2 * density, -2 * density)
        canvas.drawRoundRect(frame, 8 * density, 8 * density, outline)
    }
}
