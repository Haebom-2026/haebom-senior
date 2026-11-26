package kr.haebom.elder.guide

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * 이동 방향 화살표 (터치는 통과).
 * - 상하좌우: 화면 가운데에 큰 화살표가 미는 방향으로 미끄러지며 반복 (손가락을 그 방향으로 밀라는 뜻)
 * - 홈·뒤로: 화면 아래쪽을 가리키는 화살표 + 이름표 ("홈", "뒤로")
 * 흰 바깥선 + 주황 화살표: 밝은 화면·어두운 화면 모두에서 잘 보이게. 애니메이션을 끈 기기에서는 멈춘 화살표.
 */
internal class DirectionArrowView(
    context: Context,
    private val hint: DirectionHint,
    private val label: String,
    /** (서버 v4 swipe target) 밀어야 할 영역의 가운데 (화면 좌표). 없으면 화면 가운데 */
    private val anchorX: Int? = null,
    private val anchorY: Int? = null,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 100, 0) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6 * density
        strokeJoin = Paint.Join.ROUND
    }
    private val tagFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(196, 70, 0) }
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22 * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val arrow = Path()
    private val screenPosition = IntArray(2)
    private var phase = 0.5f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_200
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
        if (ValueAnimator.areAnimatorsEnabled()) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(screenPosition)
        val w = width.toFloat()
        val h = height.toFloat()
        // 밀 영역이 있으면 그 자리에 조금 작게, 없으면 화면 가운데에 크게
        val cx = anchorX?.let { (it - screenPosition[0]).toFloat().coerceIn(w * 0.2f, w * 0.8f) } ?: (w / 2)
        val cy = anchorY?.let { (it - screenPosition[1]).toFloat().coerceIn(h * 0.15f, h * 0.85f) } ?: (h / 2)
        val size = min(w, h) * (if (anchorX != null) 0.24f else 0.32f)
        // 0→1 동안 미는 방향으로 이동하며 나타났다 사라짐
        val travel = size * 0.6f
        val shift = (phase - 0.5f) * travel
        val alpha = if (ValueAnimator.areAnimatorsEnabled()) (255 * (1f - kotlin.math.abs(phase - 0.5f) * 1.6f)).toInt() else 255
        fill.alpha = alpha.coerceIn(60, 255)
        outline.alpha = fill.alpha

        when (hint) {
            DirectionHint.UP -> drawArrow(canvas, cx, cy - shift, size, angle = 0f)
            DirectionHint.DOWN -> drawArrow(canvas, cx, cy + shift, size, angle = 180f)
            DirectionHint.LEFT -> drawArrow(canvas, cx - shift, cy, size, angle = 270f)
            DirectionHint.RIGHT -> drawArrow(canvas, cx + shift, cy, size, angle = 90f)
            DirectionHint.HOME, DirectionHint.BACK -> {
                // 화면 아래 내비게이션 쪽을 가리킴
                val small = size * 0.6f
                val y = h - 150 * density - screenPosition[1] + shift * 0.4f
                drawArrow(canvas, w / 2, y, small, angle = 180f)
                drawTag(canvas, w / 2, y - small * 0.75f - 34 * density)
                return
            }
        }
        drawTag(canvas, cx, (cy + size * 0.95f).coerceAtMost(h - 60 * density))
    }

    /** 위쪽을 향한 화살표를 [angle]도 돌려 (cx, cy) 가운데에 그림 */
    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, size: Float, angle: Float) {
        val half = size / 2
        val shaft = size * 0.18f
        arrow.reset()
        arrow.moveTo(0f, -half)                 // 끝
        arrow.lineTo(half * 0.9f, -half * 0.05f)
        arrow.lineTo(shaft, -half * 0.05f)
        arrow.lineTo(shaft, half)
        arrow.lineTo(-shaft, half)
        arrow.lineTo(-shaft, -half * 0.05f)
        arrow.lineTo(-half * 0.9f, -half * 0.05f)
        arrow.close()
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angle)
        canvas.drawPath(arrow, outline)
        canvas.drawPath(arrow, fill)
        canvas.restore()
    }

    private fun drawTag(canvas: Canvas, cx: Float, cy: Float) {
        if (label.isBlank()) return
        val padX = 20 * density
        val boxHeight = 52 * density
        val boxWidth = (tagText.measureText(label) + padX * 2).coerceAtMost(width - 16 * density)
        val box = RectF(cx - boxWidth / 2, cy - boxHeight / 2, cx + boxWidth / 2, cy + boxHeight / 2)
        canvas.drawRoundRect(box, boxHeight / 2, boxHeight / 2, tagFill)
        val baseline = box.centerY() - (tagText.descent() + tagText.ascent()) / 2
        canvas.drawText(label, box.centerX(), baseline, tagText)
    }
}
