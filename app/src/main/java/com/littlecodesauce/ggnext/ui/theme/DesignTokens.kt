package com.littlecodesauce.ggnext.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 全局统一 UI Design Tokens（圆角、形状、间距、边框、图标尺寸、控件高度与字号阶梯）。
 * 以核心主界面与悬浮面板的既有视觉规范为唯一基准，消除各页面散落的魔法数值。
 */
object GgRadius {
    val Xxs = 4.dp
    val Xs = 6.dp
    val Sm = 8.dp
    val Md = 10.dp
    val Lg = 12.dp
    val Xl = 16.dp
    val Dialog = 18.dp
    val Xxl = 20.dp
    val Hero = 22.dp
    val Pill = 999.dp
}

object GgShapes {
    val Xxs = RoundedCornerShape(GgRadius.Xxs)
    val Xs = RoundedCornerShape(GgRadius.Xs)
    val Sm = RoundedCornerShape(GgRadius.Sm)
    val Md = RoundedCornerShape(GgRadius.Md)
    val Lg = RoundedCornerShape(GgRadius.Lg)
    val Xl = RoundedCornerShape(GgRadius.Xl)
    val Dialog = RoundedCornerShape(GgRadius.Dialog)
    val Xxl = RoundedCornerShape(GgRadius.Xxl)
    val Hero = RoundedCornerShape(GgRadius.Hero)
    val Pill = RoundedCornerShape(GgRadius.Pill)
}

object GgSpacing {
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 6.dp
    val Md = 8.dp
    val MdPlus = 10.dp
    val Lg = 12.dp
    val Xl = 16.dp
    val Xxl = 20.dp
}

object GgBorder {
    val Hairline = 0.6.dp
    val Thin = 0.8.dp
    val Default = 1.dp
    val Emphasis = 1.2.dp
    val Selected = 1.5.dp
}

object GgIconSize {
    val Xs = 14.dp
    val Sm = 16.dp
    val Md = 18.dp
    val Lg = 20.dp
    val Xl = 22.dp
    val Hero = 28.dp
    val Empty = 46.dp
}

object GgControlHeight {
    val Compact = 34.dp
    val Chip = 38.dp
    val Button = 40.dp
    val ButtonLg = 42.dp
    val Input = 44.dp
    val Row = 46.dp
    val RowTall = 48.dp
}
