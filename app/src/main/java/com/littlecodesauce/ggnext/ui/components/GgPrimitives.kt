package com.littlecodesauce.ggnext.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.littlecodesauce.ggnext.data.AppIconCache
import com.littlecodesauce.ggnext.model.TargetProcess
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgControlHeight
import com.littlecodesauce.ggnext.ui.theme.GgIconSize
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose

/**
 * 页面级毛玻璃背景修饰符：
 * 同时服务于主界面各页面、子页面以及悬浮窗主面板，并随 LocalUiMetrics 实时联动。
 */
@Composable
fun Modifier.ggPageBackdrop(
    baseColor: Color = Rose.Surface,
    respectBgOpacity: Boolean = false,
    opacityScale: Float = 1.0f
): Modifier {
    val metrics = LocalUiMetrics.current
    val isDark = Rose.IsDark
    val primary = Rose.Primary
    val glassFactor = if (metrics.glassEnabled) (metrics.glassBlur.coerceIn(0, 36) / 36f) else 0f
    val rawOpacity = metrics.bgOpacity.coerceIn(0.25f, 1.0f) * opacityScale
    val surfaceAlpha = when {
        respectBgOpacity && metrics.glassEnabled -> (rawOpacity * (1f - glassFactor * 0.14f)).coerceIn(0.22f, 0.98f)
        respectBgOpacity -> rawOpacity.coerceIn(0.25f, 1.0f)
        else -> 1.0f
    }

    return this
        .background(baseColor.copy(alpha = surfaceAlpha))
        .drawBehind {
            if (glassFactor > 0f) {
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                val frostTop = if (isDark) Color.White.copy(alpha = 0.045f * glassFactor) else Color.White.copy(alpha = 0.42f * glassFactor)
                val frostMid = primary.copy(alpha = (if (isDark) 0.04f else 0.03f) * glassFactor)
                val frostBottom = if (isDark) Color.White.copy(alpha = 0.015f * glassFactor) else Color.White.copy(alpha = 0.18f * glassFactor)
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(frostTop, frostMid, frostBottom),
                        start = Offset(0f, 0f),
                        end = Offset(w, h)
                    )
                )
            }
        }
}

@Composable
fun Modifier.ggGlassSurface(
    shape: Shape = GgShapes.Xl,
    baseColor: Color = Rose.Card,
    borderColor: Color = Rose.Divider,
    borderWidth: Dp = GgBorder.Default,
    respectBgOpacity: Boolean = true,
    bgOpacityScale: Float = 0.90f,
    accentColor: Color = Rose.Primary,
    highlightStrength: Float = 1.0f
): Modifier {
    val metrics = LocalUiMetrics.current
    val isDark = Rose.IsDark
    val glassFactor = if (metrics.glassEnabled) (metrics.glassBlur.coerceIn(0, 36) / 36f) else 0f

    val bgOpacity = metrics.bgOpacity.coerceIn(0.25f, 1.0f)
    // 严格保留半透明高亮底色（如勾选行 0.18f），绝不强制拉高为不透明色块
    val baseAlpha = if (respectBgOpacity) {
        if (baseColor.alpha < 0.99f) {
            (baseColor.alpha * bgOpacity).coerceIn(0.06f, 0.95f)
        } else {
            (bgOpacity * bgOpacityScale).coerceIn(0.20f, 1.0f)
        }
    } else {
        baseColor.alpha
    }
    val effectiveAlpha = if (glassFactor > 0f) {
        (baseAlpha * (1f - glassFactor * 0.15f)).coerceIn(0.06f, 0.96f)
    } else {
        baseAlpha
    }

    val resolvedFill = if (glassFactor > 0f && baseColor.alpha >= 0.99f) {
        val frostTint = if (isDark) Color(0xFFF6F0F4) else Color(0xFFFFFFFF)
        lerp(baseColor, frostTint, (if (isDark) 0.04f else 0.20f) * glassFactor).copy(alpha = effectiveAlpha)
    } else {
        baseColor.copy(alpha = effectiveAlpha)
    }

    val borderMod = if (borderWidth > 0.dp && borderColor.alpha > 0f) {
        Modifier.border(borderWidth, borderColor, shape)
    } else {
        Modifier
    }

    return this
        .clip(shape)
        .background(resolvedFill, shape)
        .then(borderMod)
}

/**
 * 全局目标进程图标组件：
 * 在进程选择弹窗、悬浮窗顶部进程栏、应用内悬浮球、主页与工具页统一显示目标进程真实应用图标，
 * 并带毛玻璃底框与光照高光边框。
 */
@Composable
fun TargetProcessIcon(
    process: TargetProcess?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: Shape = GgShapes.Lg,
    showActiveBorder: Boolean = false
) {
    val ctx = LocalContext.current
    val bmp = remember(process?.packageName, process?.apkPath, process?.uid) {
        if (process == null) null
        else AppIconCache.get(ctx, process.packageName, process.apkPath, process.uid)
    }
    Box(
        modifier = modifier
            .size(size)
            .ggGlassSurface(
                shape = shape,
                baseColor = Rose.CardHigh,
                borderColor = if (showActiveBorder) Rose.Primary else Rose.Divider,
                borderWidth = if (showActiveBorder) GgBorder.Emphasis else GgBorder.Thin,
                respectBgOpacity = true,
                bgOpacityScale = 0.94f,
                accentColor = Rose.Primary,
                highlightStrength = if (showActiveBorder) 1.15f else 0.85f
            ),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = process?.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (showActiveBorder) 1.5.dp else 0.dp)
                    .clip(shape)
            )
        } else if (process != null) {
            val initial = process.label.trim().take(1).uppercase().ifEmpty { "P" }
            Text(
                text = initial,
                color = Rose.Primary,
                fontSize = fsp((size.value * 0.42f).coerceIn(10f, 20f)),
                fontWeight = FontWeight.Bold
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Memory,
                contentDescription = null,
                tint = Rose.TextDim,
                modifier = Modifier.size((size.value * 0.54f).dp)
            )
        }
    }
}

/**
 * 全局统一分组标题（替代各页面独立实现的 SectionLabel / HomeSectionTitle）。
 */
@Composable
fun GgSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Rose.Primary,
    fontSize: Float = 13f,
    startPadding: Dp = GgSpacing.Md
) {
    Text(
        text = tr(text),
        color = color,
        fontSize = fsp(fontSize),
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(start = startPadding, top = GgSpacing.Xs, bottom = GgSpacing.Xxs)
    )
}

/**
 * 全局统一圆角卡片容器（内置毛玻璃折射、动态环境光照与镜面高光边框，支持按 bgOpacity 实时联动）。
 */
@Composable
fun GgSurfaceCard(
    modifier: Modifier = Modifier,
    shape: Shape = GgShapes.Xl,
    backgroundColor: Color = Rose.Card,
    borderColor: Color = Rose.Divider,
    borderWidth: Dp = GgBorder.Default,
    contentPadding: PaddingValues = PaddingValues(vertical = GgSpacing.Xs),
    respectBgOpacity: Boolean = true,
    bgOpacityScale: Float = 0.90f,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .ggGlassSurface(
                shape = shape,
                baseColor = backgroundColor,
                borderColor = borderColor,
                borderWidth = borderWidth,
                respectBgOpacity = respectBgOpacity,
                bgOpacityScale = bgOpacityScale
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content
    )
}

/**
 * 全局统一列表分隔线（替代分散的 RowDivider / FilterDivider / 内联分隔条）。
 */
@Composable
fun GgDivider(
    modifier: Modifier = Modifier,
    startInset: Dp = GgSpacing.Xl,
    endInset: Dp = 14.dp,
    thickness: Dp = GgBorder.Default,
    color: Color = Rose.Divider
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startInset, end = endInset)
            .height(thickness)
            .background(color)
    )
}

/**
 * 全局统一状态徽章 / 轻量标签按钮（替代零散的标签胶囊、操作小按钮）。
 */
@Composable
fun GgBadge(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = Rose.Primary,
    backgroundColor: Color = textColor.copy(alpha = 0.16f),
    borderColor: Color = Color.Transparent,
    shape: Shape = GgShapes.Sm,
    fontSize: Float = 11.5f,
    monospace: Boolean = false,
    fontWeight: FontWeight = FontWeight.Medium,
    horizontalPadding: Dp = 9.dp,
    verticalPadding: Dp = 4.dp,
    translate: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val label = if (translate) tr(text) else text
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .then(
                if (borderColor.alpha > 0f) Modifier.border(GgBorder.Thin, borderColor, shape)
                else Modifier
            )
            .then(if (onClick != null) Modifier.clickableWithTooltip(onClick = onClick) else Modifier)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center
    ) {
        if (monospace) {
            Text(text = label, style = monoStyle(fontSize.toInt(), textColor, weight = fontWeight), maxLines = 1)
        } else {
            Text(
                text = label,
                color = textColor,
                fontSize = fsp(fontSize),
                fontWeight = fontWeight,
                maxLines = 1
            )
        }
    }
}

/**
 * 全局统一分段选项 / 选项卡按钮（带毛玻璃与选中态边缘光照高亮）。
 */
@Composable
fun GgSegmentTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = GgControlHeight.Chip,
    shape: Shape = GgShapes.Md,
    accent: Color = Rose.Primary,
    selectedBackground: Color = Rose.ChipSelected,
    unselectedBackground: Color = Rose.CardHigh,
    showBorder: Boolean = true,
    fontSize: Float = 12.5f,
    monospace: Boolean = false,
    translate: Boolean = true,
    horizontalPadding: Dp = GgSpacing.Md,
    onClick: () -> Unit
) {
    val bg = when {
        !enabled -> unselectedBackground.copy(alpha = 0.4f)
        selected -> selectedBackground
        else -> unselectedBackground
    }
    val textColor = when {
        !enabled -> Rose.TextDim
        selected -> accent
        else -> Rose.TextSecond
    }
    val weight = if (selected) FontWeight.SemiBold else FontWeight.Normal
    val displayText = if (translate) tr(label) else label
    Box(
        modifier = modifier
            .height(height)
            .ggGlassSurface(
                shape = shape,
                baseColor = bg,
                borderColor = if (selected && enabled) accent else Rose.Divider,
                borderWidth = if (showBorder) (if (selected && enabled) GgBorder.Emphasis else GgBorder.Thin) else 0.dp,
                respectBgOpacity = true,
                bgOpacityScale = 0.94f,
                accentColor = accent,
                highlightStrength = if (selected && enabled) 1.15f else 0.55f
            )
            .clickableWithTooltip(enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        if (monospace) {
            Text(text = displayText, style = monoStyle(fontSize.toInt(), textColor, weight = weight), maxLines = 1)
        } else {
            Text(
                text = displayText,
                color = textColor,
                fontSize = fsp(fontSize),
                fontWeight = weight,
                maxLines = 1
            )
        }
    }
}

/**
 * 全局统一图标动作按钮（支持悬停提示、按压缩放、可选底色容器）。
 */
@Composable
fun GgIconActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Rose.TextSecond,
    boxSize: Dp = 30.dp,
    iconSize: Dp = GgIconSize.Md,
    backgroundColor: Color = Color.Transparent,
    shape: Shape = GgShapes.Sm,
    tooltip: String = contentDescription.orEmpty(),
    enabled: Boolean = true
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed && enabled) 0.90f else 1f, Motion.Fast, label = "iconActScale")
    Box(
        modifier = modifier
            .size(boxSize)
            .scale(scale)
            .clip(shape)
            .then(if (backgroundColor.alpha > 0f) Modifier.background(backgroundColor) else Modifier)
            .clickableWithTooltip(
                tooltip = tooltip,
                enabled = enabled,
                onPressChange = { pressed = it },
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription?.let { tr(it) },
            tint = if (enabled) tint else Rose.TextDim,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 全局统一设置/列表导航行（通过 iconInBox 切换悬浮设置页带底框图标风格与主页简洁图标风格）。
 */
@Composable
fun GgSettingNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    iconInBox: Boolean = true,
    badgeText: String? = null,
    badgeColor: Color = Rose.Primary,
    respectBgOpacity: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val bgOpacity = LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = Motion.Fast,
        label = "settingNavRowScale"
    )
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.74f else 1f,
        animationSpec = Motion.Fast,
        label = "settingNavRowAlpha"
    )
    val hPad = if (iconInBox) 14.dp else 18.dp
    val vPad = if (iconInBox) 11.dp else 14.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(pressScale)
            .alpha(pressAlpha)
            .clickableWithTooltip(onPressChange = { pressed = it }, onClick = onClick)
            .padding(horizontal = hPad, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingContent != null) {
            leadingContent()
            Spacer(Modifier.width(14.dp))
        } else if (iconInBox) {
            Box(
                Modifier
                    .size(38.dp)
                    .ggGlassSurface(
                        shape = GgShapes.Md,
                        baseColor = Rose.CardHigh,
                        borderColor = Rose.Divider,
                        borderWidth = GgBorder.Thin,
                        respectBgOpacity = respectBgOpacity,
                        bgOpacityScale = 0.92f,
                        highlightStrength = 0.75f
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = Rose.Primary, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(14.dp))
        } else {
            Icon(icon, null, tint = Rose.TextSecond, modifier = Modifier.size(GgIconSize.Xl))
            Spacer(Modifier.width(GgSpacing.Xl))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = tr(title),
                color = Rose.Text,
                fontSize = fsp(if (iconInBox) 15f else 15.5f),
                fontWeight = FontWeight.Medium
            )
            if (!iconInBox) Spacer(Modifier.height(GgSpacing.Xxs))
            Text(
                text = tr(subtitle),
                color = Rose.TextSecond,
                fontSize = fsp(if (iconInBox) 12f else 12.5f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (badgeText != null) {
            Spacer(Modifier.width(GgSpacing.Md))
            GgBadge(text = badgeText, textColor = badgeColor)
        } else {
            Spacer(Modifier.width(GgSpacing.Sm))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Rose.TextDim,
                modifier = Modifier.size(if (iconInBox) GgIconSize.Md else GgIconSize.Lg)
            )
        }
    }
}

/**
 * 全局统一开关行（统一采用 GgSwitch，消除原生 Switch 与手工 Row 碎片化）。
 */
@Composable
fun GgSettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    containerBackground: Color = Color.Transparent,
    shape: Shape = GgShapes.Md,
    horizontalPadding: Dp = 14.dp,
    verticalPadding: Dp = 11.dp
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (containerBackground.alpha > 0f) {
                    Modifier.ggGlassSurface(
                        shape = shape,
                        baseColor = containerBackground,
                        borderColor = if (checked) Rose.Primary.copy(alpha = 0.45f) else Rose.Divider,
                        borderWidth = GgBorder.Thin,
                        respectBgOpacity = true,
                        bgOpacityScale = 0.92f,
                        highlightStrength = if (checked) 0.95f else 0.5f
                    )
                } else {
                    Modifier
                }
            )
            .clickableWithTooltip { onCheckedChange(!checked) }
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = tr(title),
                color = Rose.Text,
                fontSize = fsp(if (subtitle != null) 13.5f else 15f),
                fontWeight = if (subtitle != null) FontWeight.Medium else FontWeight.Normal
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = tr(subtitle),
                    color = Rose.TextSecond,
                    fontSize = fsp(11f)
                )
            }
        }
        Spacer(Modifier.width(GgSpacing.Md))
        GgSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * 全局统一纤细波浪滑条行（供设置页与外观页共同复用，消除跨层引用）。
 */
@Composable
fun GgSettingSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit
) {
    val bgOpacity = LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = GgSpacing.Sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(title), color = Rose.Text, fontSize = fsp(14.5f), modifier = Modifier.weight(1f))
            GgBadge(
                text = display,
                textColor = Rose.TextSecond,
                backgroundColor = Rose.CardHigh.copy(alpha = (bgOpacity * 0.92f).coerceIn(0.22f, 1.0f)),
                shape = GgShapes.Xs,
                fontSize = 12f,
                fontWeight = FontWeight.Normal,
                horizontalPadding = 9.dp,
                verticalPadding = 3.dp,
                translate = false
            )
        }
        Spacer(Modifier.height(GgSpacing.Xxs))
        WavySlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            activeColor = Rose.Primary,
            inactiveColor = Rose.CardHigh.copy(alpha = bgOpacity)
        )
    }
}

/**
 * 全局统一键值信息行（图标 + 标题 + 右侧值）。
 */
@Composable
fun GgSettingInfoRow(
    icon: ImageVector,
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingContent != null) {
            leadingContent()
            Spacer(Modifier.width(GgSpacing.Lg))
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Rose.TextSecond,
                modifier = Modifier.size(21.dp)
            )
            Spacer(Modifier.width(GgSpacing.Xl))
        }
        Text(
            text = tr(title),
            color = Rose.Text,
            fontSize = fsp(14.5f),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(GgSpacing.MdPlus))
        Text(
            text = value,
            color = Rose.TextSecond,
            fontSize = fsp(13f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 全局统一单行输入容器（支持毛玻璃与边缘光照、可选 0x 前缀、错误态边框、尾部操作槽）。
 */
@Composable
fun GgInputBox(
    modifier: Modifier = Modifier,
    height: Dp = GgControlHeight.Input,
    shape: Shape = GgShapes.Md,
    backgroundColor: Color = Rose.CardHigh,
    borderColor: Color = Rose.Divider,
    borderWidth: Dp = GgBorder.Default,
    horizontalPadding: Dp = GgSpacing.Lg,
    prefix: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .ggGlassSurface(
                shape = shape,
                baseColor = backgroundColor,
                borderColor = borderColor,
                borderWidth = borderWidth,
                respectBgOpacity = true,
                bgOpacityScale = 0.94f,
                highlightStrength = 0.65f
            )
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (prefix != null) {
            prefix()
        }
        content()
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * 全局统一居中模态遮罩层（带毛玻璃环境光暗幕与弹窗缩放动效）。
 */
@Composable
fun GgModalOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    zIndex: Float = 20f,
    dismissOnScrimClick: Boolean = true,
    content: @Composable () -> Unit
) {
    val metrics = LocalUiMetrics.current
    val primary = Rose.Primary
    val scrimBase = Rose.Scrim
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
            .fillMaxSize()
            .zIndex(zIndex)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimBase)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    if (dismissOnScrimClick) onDismiss()
                },
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = dialogEnter(),
                exit = dialogExit()
            ) {
                Box(
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {}
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * 全局统一二次确认弹窗（基于 GgModalOverlay + GgSurfaceCard + RoseButton）。
 */
@Composable
fun GgConfirmDialog(
    visible: Boolean,
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    cancelLabel: String = "取消"
) {
    GgModalOverlay(
        visible = visible,
        onDismiss = onDismiss
    ) {
        GgSurfaceCard(
            modifier = Modifier.padding(horizontal = 24.dp),
            shape = GgShapes.Xl,
            contentPadding = PaddingValues(18.dp)
        ) {
            Text(
                text = tr(title),
                color = Rose.Text,
                fontSize = fsp(17f),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(GgSpacing.Md))
            Text(
                text = tr(body),
                color = Rose.TextSecond,
                fontSize = fsp(13.5f),
                lineHeight = fsp(19.5f)
            )
            Spacer(Modifier.height(GgSpacing.Xl))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GgSpacing.MdPlus)
            ) {
                RoseButton(
                    label = cancelLabel,
                    modifier = Modifier
                        .weight(1f)
                        .height(GgControlHeight.Button)
                ) {
                    onDismiss()
                }
                RoseButton(
                    label = confirmLabel,
                    filled = true,
                    modifier = Modifier
                        .weight(1f)
                        .height(GgControlHeight.Button)
                ) {
                    onConfirm()
                }
            }
        }
    }
}

/**
 * 全局统一空状态占位视图（支持简约图标风格与带底框+CTA按钮风格）。
 */
@Composable
fun GgEmptyState(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    iconInBadge: Boolean = false,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (iconInBadge) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .ggGlassSurface(
                        shape = GgShapes.Xl,
                        baseColor = Rose.Primary.copy(alpha = 0.16f),
                        borderColor = Rose.Primary.copy(alpha = 0.35f),
                        borderWidth = GgBorder.Thin,
                        respectBgOpacity = false
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title?.let { tr(it) },
                    tint = Rose.Primary,
                    modifier = Modifier.size(GgIconSize.Hero)
                )
            }
        } else {
            Icon(
                imageVector = icon,
                contentDescription = title?.let { tr(it) },
                tint = Rose.TextDim,
                modifier = Modifier.size(GgIconSize.Empty)
            )
        }
        Spacer(Modifier.height(if (iconInBadge) GgSpacing.Lg else 14.dp))
        if (!title.isNullOrBlank()) {
            Text(
                text = tr(title),
                color = Rose.Text,
                fontSize = fsp(15.5f),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(GgSpacing.Sm))
        }
        Text(
            text = tr(message),
            color = Rose.TextSecond,
            fontSize = fsp(if (title != null) 12f else 14f),
            lineHeight = fsp(if (title != null) 18f else 21f),
            textAlign = TextAlign.Center
        )
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Spacer(Modifier.height(GgSpacing.Xl))
            RoseButton(
                label = actionLabel,
                filled = true,
                enabled = actionEnabled,
                modifier = Modifier.height(GgControlHeight.Chip)
            ) {
                onAction()
            }
        }
    }
}
