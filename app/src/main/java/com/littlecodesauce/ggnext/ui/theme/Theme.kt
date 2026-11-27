package com.littlecodesauce.ggnext.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.littlecodesauce.ggnext.core.AppStrings
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.ui.components.I18n

val LocalAppPalette = staticCompositionLocalOf {
    buildAppPalette(ThemePreset.ROSE.darkSeed, dark = true)
}

val LocalAppLang = staticCompositionLocalOf { AppStrings.LANG_ZH }

/** 全局实时动态光照相位 (0f ~ 1f)，驱动主界面与悬浮窗所有页面的流光与镜面高光同步 */

object Rose {
    var palette by mutableStateOf(buildAppPalette(ThemePreset.ROSE.darkSeed, dark = true))

    val IsDark: Boolean get() = palette.isDark
    val Scrim: Color get() = palette.scrim
    val Surface: Color get() = palette.surface
    val Card: Color get() = palette.card
    val CardHigh: Color get() = palette.cardHigh
    val Row: Color get() = palette.row

    val Primary: Color get() = palette.primary
    val PrimaryDeep: Color get() = palette.primaryDeep
    val PrimarySoft: Color get() = palette.primarySoft
    val OnPrimary: Color get() = palette.onPrimary
    val Chip: Color get() = palette.chip
    val ChipSelected: Color get() = palette.chipSelected

    val Text: Color get() = palette.text
    val TextSecond: Color get() = palette.textSecond
    val TextDim: Color get() = palette.textDim
    val Divider: Color get() = palette.divider

    // 数据类型与内存区域语义色（浅色模式下采用深色高对比度色值）
    val Green: Color get() = if (palette.isDark) Color(0xFF7FD99A) else Color(0xFF0E632B)
    val Lime: Color get() = if (palette.isDark) Color(0xFFA8D97F) else Color(0xFF33610B)
    val Cyan: Color get() = if (palette.isDark) Color(0xFF8FD6DE) else Color(0xFF0B5863)
    val Blue: Color get() = if (palette.isDark) Color(0xFF8AA9E8) else Color(0xFF1A3E8C)
    val Violet: Color get() = if (palette.isDark) Color(0xFFC79AE0) else Color(0xFF5B1F7D)
    val Magenta: Color get() = if (palette.isDark) Color(0xFFD848A8) else Color(0xFF8C1160)
    val Yellow: Color get() = if (palette.isDark) Color(0xFFE8D07F) else Color(0xFF735100)
    val Orange: Color get() = if (palette.isDark) Color(0xFFE8A97F) else Color(0xFF8C3A06)
    val Red: Color get() = if (palette.isDark) Color(0xFFE87F8A) else Color(0xFFA11826)
    val Frozen: Color get() = palette.primary
    // 汇编操作码专属语义色（ARM / ARM64 / Thumb / x86 / x86_64 统一使用，与所有数值类型色明确区分）
    val Opcode: Color get() = if (palette.isDark) Color(0xFFF2A65A) else Color(0xFFB84A00)

    // 跳转地址专属高亮背景与边框色
    val JumpHighlight: Color get() = if (palette.isDark) Color(0x4CB8A832) else Color(0x36C8A618)
    val JumpBorder: Color get() = if (palette.isDark) Color(0xAAE8D07F) else Color(0xAA8C6900)

    // 原版 GG 内存页指针类型专属颜色
    val PointerRO: Color get() = if (palette.isDark) Color(0xFFFFF066) else Color(0xFF7A5900)
    val PointerW: Color get() = if (palette.isDark) Color(0xFFB57BFF) else Color(0xFF5618AB)
    val PointerX: Color get() = if (palette.isDark) Color(0xFFFF66B2) else Color(0xFFA60E58)
    val PointerWX: Color get() = if (palette.isDark) Color(0xFFFF66FF) else Color(0xFF8A0C8A)

    // 全局语义组件色（消除业务页面与组件中的硬编码 Color(0x...)）
    val OnDarkInk: Color = Color(0xFF141013)
    val CheckMark: Color get() = if (palette.isDark) OnDarkInk else Color(0xFFFFFFFF)
    val VirtualBadge: Color get() = if (palette.isDark) Color(0xFF42A5F5) else Color(0xFF1565C0)
    val ToastBg: Color get() = if (palette.isDark) Color(0xF21C181E) else Color(0xF8FFFFFF)
    val ToastBrand: Color get() = if (palette.isDark) palette.textSecond else Color(0xFF6B6875)
    val SwitchTrackOff: Color get() = if (palette.isDark) Color(0xFF322B32) else Color(0xFFD2D5E0)
    val SwitchThumbOff: Color = Color(0xFFF5F2F4)
    val SwitchThumbDisabled: Color = Color(0xFF888888)
    val HeroRunningBg: Color get() = if (palette.isDark) Color(0xFF0E4A2F) else Color(0xFFDDF4E7)
    val HeroRunningAccent: Color get() = if (palette.isDark) Color(0xFF8BE2B0) else Color(0xFF146C3E)
    val HeroRunningSub: Color get() = if (palette.isDark) Color(0xFFA7DFBE) else Color(0xFF287C50)
}

fun regionColor(code: String): Color {
    val isDark = Rose.palette.isDark
    return when (code.uppercase()) {
        "CH" -> Rose.Green
        "JH" -> Rose.Green
        "CA" -> Rose.Lime
        "CD" -> Rose.Lime
        "CB" -> Rose.Lime
        "A", "AA" -> Rose.Green
        "PS" -> Rose.Lime
        "J" -> if (isDark) Color(0xFFD4C894) else Color(0xFF7A6318)
        "S" -> if (isDark) Color(0xFFC8CA8E) else Color(0xFF5E6614)
        "AS" -> if (isDark) Color(0xFFBAC484) else Color(0xFF4B6318)
        "V" -> if (isDark) Color(0xFFBAC484) else Color(0xFF4B6318)
        "O" -> if (isDark) Color(0xFFD4D050) else Color(0xFF756A00)
        "B" -> Rose.Red
        "XA" -> if (isDark) Color(0xFFB880B8) else Color(0xFF7B2B7B)
        "XS" -> Rose.Magenta
        else -> Rose.TextSecond
    }
}

fun typeColor(flags: Int): Color = when (flags and 0x7F) {
    1 -> if (Rose.palette.isDark) Color(0xFFE7A8D8) else Color(0xFF82186A) // Byte
    2 -> Rose.Lime                                                           // Word
    4 -> Rose.Cyan                                                           // Dword
    8 -> if (Rose.palette.isDark) Color(0xFFB9A8E7) else Color(0xFF43288C) // Xor
    16 -> if (Rose.palette.isDark) Color(0xFFE7A8A8) else Color(0xFF991F1F) // Float
    32 -> Rose.Blue                                                          // Qword
    64 -> Rose.Yellow                                                        // Double
    127 -> Rose.Text                                                         // Auto
    else -> Rose.Text
}

fun pointerColor(pointer: Int): Color = when (pointer) {
    8 -> Rose.PointerRO
    16 -> Rose.PointerW
    2 -> Rose.PointerX
    1 -> Rose.PointerWX
    else -> Rose.TextSecond
}

fun pointerTypeLabel(pointer: Int): String = when (pointer) {
    8 -> "RO"
    16 -> "W"
    2 -> "X"
    1 -> "WX"
    else -> ""
}

object Motion {
    val Fast = androidx.compose.animation.core.tween<Float>(140)
    val Normal = androidx.compose.animation.core.tween<Float>(220)
    val Springy = androidx.compose.animation.core.spring<Float>(
        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
    )
    val NavSpring = androidx.compose.animation.core.spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
    )
    val ColorFast = androidx.compose.animation.core.tween<Color>(140)
    val ColorNormal = androidx.compose.animation.core.tween<Color>(220)
    const val DIALOG_ENTER_MS = 200
    const val DIALOG_EXIT_MS = 150
}

data class UiMetrics(
    val fontScale: Float = 0.88f,
    val dialogCorner: Int = 12,
    val sheetBlur: Int = 110,
    val iconBlur: Int = 139,
    val skeletonLoading: Boolean = true,
    val bgOpacity: Float = 0.96f,
    val panelScale: Float = 1.0f,
    val glassEnabled: Boolean = false,
    val glassBlur: Int = 16,
    val showProcessIconOnBall: Boolean = true
)

val LocalUiMetrics = staticCompositionLocalOf { UiMetrics() }

val LocalAppFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

@Composable
fun GgNextTheme(
    preset: ThemePreset? = null,
    darkMode: DarkMode? = null,
    dynamicColor: Boolean? = null,
    metrics: UiMetrics? = null,
    content: @Composable () -> Unit
) {
    val ui by Session.ui.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val activePreset = preset ?: ui.preset
    val activeDarkMode = darkMode ?: ui.darkMode
    val activeDynamic = dynamicColor ?: ui.dynamicColor
    val lang = ui.settings["language"] ?: AppStrings.LANG_ZH
    val activeFontFamily = androidx.compose.runtime.remember(ui.customFontPath) {
        AppFontCache.fontFamilyFor(ui.customFontPath)
    }

    val (computedPalette, scheme) = rememberPaletteAndScheme(
        preset = activePreset,
        darkMode = activeDarkMode,
        dynamicColor = activeDynamic,
        systemDark = systemDark
    )

    SideEffect {
        if (Rose.palette != computedPalette) {
            Rose.palette = computedPalette
        }
        AppStrings.setLang(lang)
        if (I18n.currentLang != lang) {
            I18n.currentLang = lang
        }
    }

    val m = metrics ?: ui.metrics
    val baseTypography = Typography()
    val typography = Typography(
        displayLarge = baseTypography.displayLarge.copy(fontFamily = activeFontFamily),
        displayMedium = baseTypography.displayMedium.copy(fontFamily = activeFontFamily),
        displaySmall = baseTypography.displaySmall.copy(fontFamily = activeFontFamily),
        headlineLarge = baseTypography.headlineLarge.copy(fontFamily = activeFontFamily),
        headlineMedium = baseTypography.headlineMedium.copy(fontFamily = activeFontFamily),
        headlineSmall = baseTypography.headlineSmall.copy(fontFamily = activeFontFamily),
        titleLarge = baseTypography.titleLarge.copy(
            fontFamily = activeFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (21 * m.fontScale).sp,
            color = computedPalette.text
        ),
        titleMedium = baseTypography.titleMedium.copy(fontFamily = activeFontFamily, color = computedPalette.text),
        titleSmall = baseTypography.titleSmall.copy(fontFamily = activeFontFamily, color = computedPalette.text),
        bodyLarge = baseTypography.bodyLarge.copy(fontFamily = activeFontFamily, color = computedPalette.text),
        bodyMedium = baseTypography.bodyMedium.copy(
            fontFamily = activeFontFamily,
            fontSize = (14 * m.fontScale).sp,
            color = computedPalette.text
        ),
        bodySmall = baseTypography.bodySmall.copy(fontFamily = activeFontFamily, color = computedPalette.textSecond),
        labelLarge = baseTypography.labelLarge.copy(fontFamily = activeFontFamily),
        labelMedium = baseTypography.labelMedium.copy(fontFamily = activeFontFamily),
        labelSmall = baseTypography.labelSmall.copy(
            fontFamily = activeFontFamily,
            fontSize = (10 * m.fontScale).sp,
            color = computedPalette.textDim
        )
    )

    CompositionLocalProvider(
        LocalAppPalette provides computedPalette,
        LocalAppLang provides lang,
        LocalUiMetrics provides m,
        LocalAppFontFamily provides activeFontFamily,
        LocalContentColor provides computedPalette.text
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            androidx.compose.material3.ProvideTextStyle(
                value = TextStyle(fontFamily = activeFontFamily, color = computedPalette.text)
            ) {
                content()
            }
        }
    }
}
