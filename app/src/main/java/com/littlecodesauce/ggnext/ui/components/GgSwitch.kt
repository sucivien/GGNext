package com.littlecodesauce.ggnext.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.ui.theme.Rose

/**
 * 现代精简平滑开关控件（Switch），替代原先粗糙的 CheckBox：
 * - 尺寸紧凑（44.dp × 26.dp）
 * - 平滑滑动与色彩过渡动画
 * - 追随当前主题色与深浅色模式
 */
@Composable
fun GgSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    width: Dp = 44.dp,
    height: Dp = 26.dp
) {
    val trackBg by animateColorAsState(
        targetValue = when {
            !enabled -> Rose.CardHigh.copy(alpha = 0.5f)
            checked -> Rose.Primary
            else -> Rose.SwitchTrackOff
        },
        animationSpec = tween(160),
        label = "switchTrack"
    )

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) (width - height + 1.dp) else 2.dp,
        animationSpec = tween(160),
        label = "switchThumb"
    )

    val thumbColor by animateColorAsState(
        targetValue = when {
            !enabled -> Rose.SwitchThumbDisabled
            checked -> Rose.OnPrimary
            else -> Rose.SwitchThumbOff
        },
        animationSpec = tween(160),
        label = "switchThumbColor"
    )

    val pillShape = RoundedCornerShape(height / 2)
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(pillShape)
            .background(trackBg)
            .border(
                width = com.littlecodesauce.ggnext.ui.theme.GgBorder.Default,
                color = if (checked) Rose.Primary.copy(alpha = 0.5f) else Rose.Divider,
                shape = pillShape
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                enabled = enabled
            ) {
                onCheckedChange(!checked)
            }
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(height - 6.dp)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}
