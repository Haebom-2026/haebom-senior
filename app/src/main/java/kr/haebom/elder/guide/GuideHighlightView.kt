package kr.haebom.elder.guide

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * 눌러야 할 곳 표시 (터치는 통과).
 * - 바깥은 살짝 어둡게 해서 눈이 그곳으로 가게
 * - 흰 바깥선 + 굵은 빨간 테두리: 밝은 화면·어두운 화면 모두에서 잘 보이게
 * - 바깥으로 퍼지는 빛 고리 (애니메이션을 끈 기기에서는 멈춘 고리)
 * - "여기를 누르세요" 말풍선: 자리가 있는 쪽(위/아래)에 꼬리를 달아 표시
 */
internal class GuideHighlightView(
    context: Context,
    private val target: Rect?,
    private val label: String,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(96, 0, 0, 0) }
    private val shadowRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 10 * density
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(224, 30, 40)
        style = Paint.Style.STROKE
        strokeWidth = 6 * density
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(224, 30, 40)
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
    }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(196, 20, 30) }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20 * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val screenPosition = IntArray(2)
    private val frame = RectF()
    private val hole = Path()
    private val tailPath = Path()
    private var phase = 0.5f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (target != null && ValueAnimator.areAnimatorsEnabled()) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = target ?: return
        getLocationOnScreen(screenPosition)
        frame.set(bounds)
        frame.offset(-screenPosition[0].toFloat(), -screenPosition[1].toFloat())
        frame.inset(-6 * density, -6 * density)
        val radius = 14 * density

        // 1) 바깥 어둡게 (가운데는 뚫음)
        hole.reset()
        hole.fillType = Path.FillType.EVEN_ODD
        hole.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        hole.addRoundRect(frame, radius, radius, Path.Direction.CW)
        canvas.drawPath(hole, scrim)

        // 2) 퍼지는 빛 고리
        val spread = phase * 18 * density
        glow.alpha = ((1f - phase) * 200).toInt()
        canvas.drawRoundRect(
            frame.left - spread, frame.top - spread, frame.right + spread, frame.bottom + spread,
            radius + spread, radius + spread, glow,
        )

        // 3) 테두리 (흰 바깥선 + 빨간 선)
        canvas.drawRoundRect(frame, radius, radius, shadowRing)
        canvas.drawRoundRect(frame, radius, radius, ring)

        // 4) 말풍선
        drawBubble(canvas)
    }

    private fun drawBubble(canvas: Canvas) {
        val padX = 18 * density
        val boxHeight = 48 * density
        val tail = 12 * density
        val gap = 14 * density
        val boxWidth = (bubbleText.measureText(label) + padX * 2).coerceAtMost(width - 16 * density)
        val half = boxWidth / 2
        val centerX = frame.centerX().coerceIn(half + 8 * density, (width - half - 8 * density).coerceAtLeast(half + 8 * density))
        val below = frame.top - gap - tail - boxHeight < 24 * density
        val top = if (below) frame.bottom + gap + tail else frame.top - gap - tail - boxHeight
        val bob = if (ValueAnimator.areAnimatorsEnabled()) sin(phase * 2 * Math.PI).toFloat() * 3 * density else 0f
        val box = RectF(centerX - half, top + bob, centerX + half, top + bob + boxHeight)
        canvas.drawRoundRect(box, boxHeight / 2, boxHeight / 2, bubble)

        val tipX = frame.centerX().coerceIn(box.left + boxHeight / 2, (box.right - boxHeight / 2).coerceAtLeast(box.left + boxHeight / 2))
        tailPath.reset()
        if (below) {
            tailPath.moveTo(tipX - tail, box.top); tailPath.lineTo(tipX, box.top - tail); tailPath.lineTo(tipX + tail, box.top)
        } else {
            tailPath.moveTo(tipX - tail, box.bottom); tailPath.lineTo(tipX, box.bottom + tail); tailPath.lineTo(tipX + tail, box.bottom)
        }
        tailPath.close()
        canvas.drawPath(tailPath, bubble)
        val baseline = box.centerY() - (bubbleText.descent() + bubbleText.ascent()) / 2
        canvas.drawText(label, box.centerX(), baseline, bubbleText)
    }
}
