package com.littlecodesauce.ggnext.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import com.littlecodesauce.ggnext.ui.theme.Motion

/** 弹窗入场：缩放 + 淡入（只走 graphicsLayer，不触发重组） */
fun dialogEnter(): EnterTransition =
    fadeIn(tween(Motion.DIALOG_ENTER_MS)) + scaleIn(
        initialScale = 0.92f,
        animationSpec = tween(Motion.DIALOG_ENTER_MS)
    )

fun dialogExit(): ExitTransition =
    fadeOut(tween(Motion.DIALOG_EXIT_MS)) + scaleOut(
        targetScale = 0.96f,
        animationSpec = tween(Motion.DIALOG_EXIT_MS)
    )

/** 展开 / 收起一行 */
fun expandEnter(): EnterTransition = expandVertically(tween(200)) + fadeIn(tween(200))
fun expandExit(): ExitTransition = shrinkVertically(tween(160)) + fadeOut(tween(120))
