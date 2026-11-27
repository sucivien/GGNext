package com.littlecodesauce.ggnext.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgControlHeight
import com.littlecodesauce.ggnext.ui.theme.GgIconSize
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import kotlin.math.roundToInt

@Composable
fun fsp(base: Float): androidx.compose.ui.unit.TextUnit =
    (base * LocalUiMetrics.current.fontScale).sp

@Composable
fun NextSheetCard(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    maxBodyHeight: Int = 620,
    showSideKeyboard: Boolean = false,
    sideKeyboard: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit
) {
    val metrics = LocalUiMetrics.current
    val corner = metrics.dialogCorner.dp
    val shape = RoundedCornerShape(corner)

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            configuration.screenWidthDp > configuration.screenHeightDp
        val fallbackHeightDp = configuration.screenHeightDp.coerceAtLeast(240)
        val availHeightDp = if (maxHeight.value.isFinite() && maxHeight.value > 120f) {
            maxHeight.value.toInt()
        } else {
            fallbackHeightDp
        }
        val isCompactHeight = availHeightDp < 460
        val maxCardHeight = (availHeightDp - if (isCompactHeight) 8 else 20).coerceAtLeast(160).dp
        val headerReserve = if (isCompactHeight) {
            if (subtitle.isNullOrBlank()) 44 else 60
        } else {
            if (subtitle.isNullOrBlank()) 60 else 84
        }
        val effectiveMaxBodyHeight = minOf(maxBodyHeight, (availHeightDp - headerReserve - 12).coerceAtLeast(110)).dp
        val useLandscapeSideKeyboard = isLandscape && showSideKeyboard && sideKeyboard != null
        val maxCardWidth = when {
            useLandscapeSideKeyboard -> 960.dp
            isLandscape -> 560.dp
            else -> Dp.Unspecified
        }

        val cardModifier = Modifier
            .padding(horizontal = 10.dp, vertical = if (isCompactHeight) 3.dp else 6.dp)
            .then(if (maxCardWidth != Dp.Unspecified) Modifier.widthIn(max = maxCardWidth) else Modifier)
            .fillMaxWidth()
            .heightIn(max = maxCardHeight)
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) { /* 消费弹窗内容区域点击，防止误触发关闭 */ }
            .ggGlassSurface(
                shape = shape,
                baseColor = Rose.Card,
                borderColor = Rose.Divider,
                borderWidth = GgBorder.Default,
                respectBgOpacity = true,
                bgOpacityScale = 0.96f,
                accentColor = Rose.Primary,
                highlightStrength = 1.1f
            )

        if (useLandscapeSideKeyboard) {
            Layout(
                modifier = cardModifier.padding(end = 10.dp),
                content = {
                    Column(
                        modifier = Modifier.heightIn(max = maxCardHeight)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = 16.dp,
                                    end = 10.dp,
                                    top = if (isCompactHeight) 7.dp else 11.dp,
                                    bottom = if (subtitle.isNullOrBlank()) (if (isCompactHeight) 4.dp else 6.dp) else 2.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tr(title), color = Rose.Text, fontSize = fsp(if (isCompactHeight) 16.5f else 19f), fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            CloseBadge(onDismiss)
                        }
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                tr(subtitle),
                                color = Rose.TextSecond,
                                fontSize = fsp(if (isCompactHeight) 11f else 12.5f),
                                modifier = Modifier.padding(start = 16.dp, end = 12.dp, bottom = if (isCompactHeight) 3.dp else 5.dp)
                            )
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .heightIn(max = effectiveMaxBodyHeight)
                                .padding(start = 14.dp, end = 10.dp, bottom = if (isCompactHeight) 8.dp else 10.dp)
                        ) { body() }
                    }

                    Box(
                        modifier = Modifier
                            .padding(vertical = if (isCompactHeight) 8.dp else 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        sideKeyboard?.invoke()
                    }
                }
            ) { measurables, constraints ->
                val totalW = constraints.maxWidth.coerceAtLeast(1)
                val leftW = (totalW * 0.54f).roundToInt().coerceIn(1, totalW - 1)
                val rightW = (totalW - leftW).coerceAtLeast(1)
                val maxH = constraints.maxHeight

                val leftPlaceable = measurables[0].measure(
                    Constraints(
                        minWidth = leftW,
                        maxWidth = leftW,
                        minHeight = 0,
                        maxHeight = maxH
                    )
                )
                val minKbHeight = 196.dp.roundToPx().coerceAtMost(maxH)
                val containerH = maxOf(leftPlaceable.height, minKbHeight).coerceAtMost(maxH)
                val rightPlaceable = measurables[1].measure(
                    Constraints.fixed(width = rightW, height = containerH)
                )

                layout(totalW, containerH) {
                    leftPlaceable.placeRelative(0, 0)
                    rightPlaceable.placeRelative(leftW, 0)
                }
            }
        } else {
            Column(cardModifier) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 18.dp,
                            end = 12.dp,
                            top = if (isCompactHeight) 7.dp else 13.dp,
                            bottom = if (subtitle.isNullOrBlank()) (if (isCompactHeight) 4.dp else 7.dp) else 2.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(tr(title), color = Rose.Text, fontSize = fsp(if (isCompactHeight) 17f else 20f), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    CloseBadge(onDismiss)
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        tr(subtitle),
                        color = Rose.TextSecond,
                        fontSize = fsp(if (isCompactHeight) 11.5f else 13f),
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = if (isCompactHeight) 3.dp else 6.dp)
                    )
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = effectiveMaxBodyHeight)
                        .padding(horizontal = 14.dp)
                        .padding(bottom = if (isCompactHeight) 8.dp else 12.dp)
                ) {
                    body()
                    if (sideKeyboard != null) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showSideKeyboard,
                            enter = expandEnter(),
                            exit = expandExit()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Spacer(Modifier.height(6.dp))
                                sideKeyboard()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CloseBadge(onClick: () -> Unit) {
    GgIconActionButton(
        icon = Icons.Rounded.Close,
        contentDescription = "关闭",
        onClick = onClick,
        tint = Rose.Text,
        boxSize = 36.dp,
        iconSize = GgIconSize.Md,
        backgroundColor = Rose.CardHigh,
        shape = GgShapes.Md,
        tooltip = "关闭"
    )
}

@Composable
fun Chip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = Rose.Primary,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, Motion.Fast, label = "chipScale")
    val bg by animateColorAsState(
        if (selected) Rose.ChipSelected else if (pressed) Rose.CardHigh else Rose.Chip,
        Motion.ColorFast,
        label = "chipBg"
    )
    Box(
        modifier
            .heightIn(min = GgControlHeight.Button)
            .scale(scale)
            .ggGlassSurface(
                shape = GgShapes.Md,
                baseColor = bg,
                borderColor = if (selected) accent.copy(alpha = 0.65f) else Rose.Divider,
                borderWidth = if (selected) GgBorder.Default else GgBorder.Thin,
                respectBgOpacity = true,
                bgOpacityScale = 0.94f,
                accentColor = accent,
                highlightStrength = if (selected) 1.05f else 0.55f
            )
            .clickableWithTooltip(label, onPressChange = { pressed = it }, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            tr(label),
            color = if (selected) Rose.Text else Rose.TextSecond,
            fontSize = fsp(14f),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
fun RoseButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    tooltip: String = label,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed && enabled) 0.94f else 1f, Motion.Fast, label = "btnScale")
    val bg by animateColorAsState(
        when {
            !enabled -> Rose.CardHigh
            pressed -> Rose.PrimaryDeep
            filled -> Rose.ChipSelected
            else -> Rose.Chip
        },
        Motion.ColorFast,
        label = "btnBg"
    )
    Box(
        modifier
            .heightIn(min = GgControlHeight.ButtonLg)
            .scale(scale)
            .ggGlassSurface(
                shape = GgShapes.Md,
                baseColor = bg,
                borderColor = if (filled && enabled) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                borderWidth = if (filled && enabled) GgBorder.Default else GgBorder.Thin,
                respectBgOpacity = true,
                bgOpacityScale = 0.95f,
                accentColor = Rose.Primary,
                highlightStrength = if (filled && enabled) 1.15f else 0.6f
            )
            .clickableWithTooltip(
                tooltip = tooltip,
                enabled = enabled,
                onPressChange = { pressed = it },
                onClick = onClick
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            tr(label),
            color = when {
                !enabled -> Rose.TextDim
                filled -> Rose.Text
                else -> Rose.TextSecond
            },
            fontSize = fsp(14f),
            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(GgBorder.Hairline)
            .background(Rose.Divider)
    )
}

@Composable
fun Scrim(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Rose.Scrim)
            .clickableWithTooltip("关闭弹窗", onClick = onClick)
    )
}
