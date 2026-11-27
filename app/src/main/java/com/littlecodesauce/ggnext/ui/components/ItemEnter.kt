package com.littlecodesauce.ggnext.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * 列表项入场：仅对首屏前 10 项做轻量级淡入 + 上浮动画（只写 graphicsLayer，不触发布局与重组）；
 * 索引 >= 10 的滚动项直接零开销渲染，保证数千条内存地址高速滑动时稳帧 120fps。
 */
@Composable
fun ItemEnter(
    key: Any?,
    index: Int = 0,
    content: @Composable () -> Unit
) {
    if (index >= 10) {
        content()
        return
    }
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        if (index > 0) delay((index * 15).toLong())
        progress.animateTo(1f, tween(190))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 14f
        }
    ) { content() }
}
