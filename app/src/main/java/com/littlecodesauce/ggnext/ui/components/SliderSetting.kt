package com.littlecodesauce.ggnext.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.ui.theme.Rose
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 纤细波浪滑条：常态下为纤细直线轨道，按住或拖动时激活段平滑变为动态正弦波浪线。
 */
@Composable
fun WavySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    activeColor: Color = Rose.Primary,
    inactiveColor: Color = Rose.CardHigh,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val minVal = valueRange.start
    val maxVal = valueRange.endInclusive
    val span = (maxVal - minVal).takeIf { it > 0f } ?: 1f
    val fraction = ((value - minVal) / span).coerceIn(0f, 1f)

    val waveAmpFactor by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = tween(180),
        label = "waveAmp"
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (isDragging) 1.22f else 1f,
        animationSpec = tween(150),
        label = "thumbScale"
    )
    val infiniteTransition = rememberInfiniteTransition(label = "wavePhaseTransition")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(minVal, maxVal, steps) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val downPos = down.position
                    val padPx = 10.dp.toPx()
                    fun updateAt(x: Float) {
                        val usableWidth = (size.width - padPx * 2f).coerceAtLeast(1f)
                        var rawFrac = ((x - padPx) / usableWidth).coerceIn(0f, 1f)
                        if (steps > 0) {
                            val stepCount = steps + 1
                            rawFrac = ((rawFrac * stepCount).roundToInt().toFloat() / stepCount).coerceIn(0f, 1f)
                        }
                        val nextVal = (minVal + rawFrac * (maxVal - minVal)).coerceIn(minVal, maxVal)
                        currentOnValueChange(nextVal)
                    }

                    var lockedHorizontal = false
                    var abortedByVertical = false
                    var lastX = downPos.x
                    var canceled = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            lastX = change.position.x
                            if (change.isConsumed) canceled = true
                            break
                        }

                        if (!lockedHorizontal) {
                            if (change.isConsumed) {
                                abortedByVertical = true
                                break
                            }
                            val dx = kotlin.math.abs(change.position.x - downPos.x)
                            val dy = kotlin.math.abs(change.position.y - downPos.y)
                            // 一旦出现明显纵向位移（或纵横比偏竖直），立即放弃拦截，交给父级列表顺滑滚动
                            if (dy > touchSlop * 0.65f && dy >= dx * 0.8f) {
                                abortedByVertical = true
                                break
                            }
                            // 仅当横向滑动显著超过 touchSlop 且明确主导方向时，才锁定为滑条拖动
                            if (dx > touchSlop && dx > dy * 1.35f) {
                                lockedHorizontal = true
                                isDragging = true
                                lastX = change.position.x
                                updateAt(lastX)
                                change.consume()
                            }
                        } else {
                            lastX = change.position.x
                            updateAt(lastX)
                            change.consume()
                        }
                    }

                    // 未发生垂直滚动、未拖动且未被父容器消费的干净轻点：在抬手瞬间跳转至点击位置
                    if (!lockedHorizontal && !abortedByVertical && !canceled) {
                        updateAt(lastX)
                    }
                    isDragging = false
                }
            }
    ) {
        val padPx = 10.dp.toPx()
        val startX = padPx
        val endX = (size.width - padPx).coerceAtLeast(startX + 1f)
        val centerY = size.height / 2f
        val thumbX = startX + (endX - startX) * fraction

        val idleStrokePx = 2.2.dp.toPx()
        val activeStrokePx = (2.3f + 0.5f * waveAmpFactor).dp.toPx()

        // 未激活右侧轨道（细线）
        if (thumbX < endX) {
            drawLine(
                color = inactiveColor,
                start = Offset(thumbX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = idleStrokePx,
                cap = StrokeCap.Round
            )
        }

        // 激活左侧轨道（常态细直线，拖动时变为动态正弦波浪线）
        if (thumbX > startX) {
            if (waveAmpFactor < 0.02f) {
                drawLine(
                    color = activeColor,
                    start = Offset(startX, centerY),
                    end = Offset(thumbX, centerY),
                    strokeWidth = activeStrokePx,
                    cap = StrokeCap.Round
                )
            } else {
                val maxAmpPx = 3.2.dp.toPx() * waveAmpFactor
                val waveLenPx = 18.dp.toPx().coerceAtLeast(6f)
                val activeLen = thumbX - startX
                val taperZonePx = 10.dp.toPx().coerceAtMost(activeLen * 0.45f)
                val stepPx = 2.dp.toPx().coerceAtLeast(1.5f)
                val path = Path()
                path.moveTo(startX, centerY)
                var x = startX + stepPx
                while (x < thumbX) {
                    val distFromStart = x - startX
                    val distFromEnd = thumbX - x
                    val edgeTaper = when {
                        taperZonePx <= 0.5f -> 1f
                        distFromStart < taperZonePx -> (distFromStart / taperZonePx).coerceIn(0f, 1f)
                        distFromEnd < taperZonePx -> (distFromEnd / taperZonePx).coerceIn(0f, 1f)
                        else -> 1f
                    }
                    val angle = ((distFromStart / waveLenPx) * (2.0 * PI) - wavePhase).toFloat()
                    val y = centerY + sin(angle) * maxAmpPx * edgeTaper
                    path.lineTo(x, y)
                    x += stepPx
                }
                path.lineTo(thumbX, centerY)
                drawPath(
                    path = path,
                    color = activeColor,
                    style = Stroke(
                        width = activeStrokePx,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        // 拖动光晕与滑块圆点
        if (waveAmpFactor > 0.02f) {
            drawCircle(
                color = activeColor.copy(alpha = 0.20f * waveAmpFactor),
                radius = 11.dp.toPx() * waveAmpFactor,
                center = Offset(thumbX, centerY)
            )
        }
        drawCircle(
            color = activeColor,
            radius = 5.6.dp.toPx() * thumbScale,
            center = Offset(thumbX, centerY)
        )
    }
}
