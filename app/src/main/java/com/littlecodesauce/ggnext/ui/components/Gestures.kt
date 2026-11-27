package com.littlecodesauce.ggnext.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * 界面调整 1 & 3：
 * 1. 彻底移除长按普通按钮弹出按钮名称气泡的功能。
 * 2. 全面采用 Compose 原生具备滚动容器感知延迟（TapGestureDetector + ScrollableContainer 协同）的
 *    `clickable` 与 `combinedClickable`，彻底解决初始手指按在按钮、芯片或列表行上时无法滑动屏幕的问题，
 *    同时保持子控件独立消费点击、不穿透触发父级列表行。
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.clickableLongPress(
    onClick: () -> Unit,
    onLongPress: () -> Unit
): Modifier = composed {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val interactionSource = remember { MutableInteractionSource() }
    this.combinedClickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = { currentOnClick() },
        onLongClick = { currentOnLongPress() }
    )
}

/**
 * 界面调整 1 & 3：
 * - 不再响应长按显示提示文字（保留参数签名兼容全项目调用点）。
 * - 使用原生 `Modifier.clickable` 配合 `MutableInteractionSource`，当手指从按钮上开始滑动屏幕时，
 *   外层 `LazyColumn` / `verticalScroll` / `horizontalScroll` 可无缝接管滚动手势，绝不卡住滑动。
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.clickableWithTooltip(
    tooltip: String = "",
    enabled: Boolean = true,
    onPressChange: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPressChange by rememberUpdatedState(onPressChange)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed) {
        currentOnPressChange?.invoke(isPressed)
    }

    this.clickable(
        enabled = enabled,
        interactionSource = interactionSource,
        indication = null,
        onClick = { currentOnClick() }
    )
}
