package kr.haebom.elder.guide

import kotlin.math.abs

/** 화면 좌표 사각형 (안드로이드 Rect 대신: JVM 단위 테스트에서 그대로 쓰려고) */
internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    fun intersects(other: Box) = left < other.right && other.left < right && top < other.bottom && other.top < bottom
    fun inflate(by: Int) = Box(left - by, top - by, right + by, bottom + by)
}

/**
 * 자막 막대·마이크 묶음을 어디에 둘지 정하는 규칙 (상황별 대처).
 *
 * 1. 자막은 기본으로 아래. 눌러야 할 곳이 화면 아래쪽(55% 아래)에 있으면 위로 올림
 * 2. 키보드가 떠 있으면 자막을 키보드 바로 위에 둠 (자막이 위에 있으면 그대로)
 * 3. 마이크 묶음은 어르신이 둔 자리를 지키되, 눌러야 할 곳·자막·키보드를 가리면
 *    가까운 빈자리(같은 쪽 가장자리 먼저, 그다음 반대쪽)로 비켜 줌. 가릴 게 없어지면 원래 자리로
 */
internal object OverlayPlanner {

    /**
     * 자막을 위로 올릴지.
     * - 짚은 단추([target])가 있으면 그 단추 기준 (화면 아래쪽이면 위로)
     * - 짚은 단추가 없으면, 아래 자막 자리에 작업 단추 후보([others], 예: 화면 아래 고정 "발급하기")가
     *   있고 위 자리는 비어 있을 때 위로. 그래야 어르신이 단추를 직접 보고 누를 수 있음
     * [captionHeight]·[inset] 은 자막 막대 높이와 화면 끝에서 띄우는 거리.
     */
    fun captionAtTop(
        screenHeight: Int,
        target: Box?,
        keyboardTop: Int?,
        others: List<Box> = emptyList(),
        captionHeight: Int = 0,
        inset: Int = 0,
    ): Boolean {
        val usableBottom = keyboardTop ?: screenHeight
        if (target != null) return target.centerY > usableBottom * 0.55f
        if (others.isEmpty() || captionHeight <= 0) return false
        val bottomZone = captionBox(1, screenHeight, captionHeight, atTop = false, keyboardTop = keyboardTop, inset = inset)
        val topZone = captionBox(1, screenHeight, captionHeight, atTop = true, keyboardTop = keyboardTop, inset = inset)
        fun overlapsRows(zone: Box, box: Box) = box.top < zone.bottom && zone.top < box.bottom
        return others.any { overlapsRows(bottomZone, it) } && others.none { overlapsRows(topZone, it) }
    }

    /** 자막 막대가 차지할 곳 */
    fun captionBox(screenWidth: Int, screenHeight: Int, height: Int, atTop: Boolean, keyboardTop: Int?, inset: Int): Box =
        if (atTop) {
            Box(0, inset, screenWidth, inset + height)
        } else {
            val bottom = (keyboardTop ?: screenHeight) - inset
            Box(0, bottom - height, screenWidth, bottom)
        }

    /**
     * 마이크 묶음의 왼쪽 위 좌표. [preferredX]/[preferredY] 는 어르신이 끌어다 놓은 자리.
     * [blocked] 와 겹치지 않는 자리 중 원래 자리에서 가장 가까운 곳을 고름.
     */
    fun panelPosition(
        screenWidth: Int,
        screenHeight: Int,
        panelWidth: Int,
        panelHeight: Int,
        preferredX: Int,
        preferredY: Int,
        blocked: List<Box>,
        margin: Int,
        topInset: Int,
    ): Pair<Int, Int> {
        val maxX = (screenWidth - panelWidth - margin).coerceAtLeast(margin)
        val maxY = (screenHeight - panelHeight - margin).coerceAtLeast(topInset)
        val clampedX = preferredX.coerceIn(margin, maxX)
        val clampedY = preferredY.coerceIn(topInset, maxY)
        val avoid = blocked.map { it.inflate(margin) }

        fun free(x: Int, y: Int) = avoid.none { it.intersects(Box(x, y, x + panelWidth, y + panelHeight)) }
        if (free(clampedX, clampedY)) return clampedX to clampedY

        val sameSide = if (clampedX + panelWidth / 2 < screenWidth / 2) margin else maxX
        val otherSide = if (sameSide == margin) maxX else margin
        val step = (panelHeight / 2).coerceAtLeast(1)
        val ys = generateSequence(topInset) { it + step }.takeWhile { it <= maxY }.toList() + maxY
        val candidates = listOf(sameSide, otherSide).flatMap { x -> ys.map { y -> x to y } }
        return candidates
            .filter { (x, y) -> free(x, y) }
            .minByOrNull { (x, y) -> abs(y - clampedY) + if (x == sameSide) 0 else screenWidth }
            ?: (clampedX to clampedY)
    }

    /** 끌기를 놓았을 때 가까운 가장자리로 붙임 */
    fun snapX(x: Int, panelWidth: Int, screenWidth: Int, margin: Int): Int =
        if (x + panelWidth / 2 < screenWidth / 2) margin else (screenWidth - panelWidth - margin).coerceAtLeast(margin)
}
