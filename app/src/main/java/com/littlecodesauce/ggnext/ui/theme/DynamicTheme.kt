package com.littlecodesauce.ggnext.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/** 16 套高辨识度主题配色（深色 / 浅色双种子，避免浅色模式脏灰） */
enum class ThemePreset(
    val label: String,
    val darkSeed: Color,
    val lightSeed: Color
) {
    ROSE("玫瑰", Color(0xFFE7A8B8), Color(0xFFB33B5E)),
    GREEN("青翠", Color(0xFF7FD99A), Color(0xFF1A7A42)),
    CYAN("天青", Color(0xFF8FD6DE), Color(0xFF0E7485)),
    BLUE("蔚蓝", Color(0xFF8AA9E8), Color(0xFF2855B8)),
    VIOLET("紫罗兰", Color(0xFFC79AE0), Color(0xFF73359E)),
    AMBER("琥珀", Color(0xFFE8C07F), Color(0xFFA8650A)),
    RED("赤红", Color(0xFFE88A8A), Color(0xFFC02A33)),
    MONO("中性", Color(0xFFC9C4C6), Color(0xFF474A54)),
    CORAL("珊瑚", Color(0xFFF09D82), Color(0xFFC44A26)),
    EMERALD("祖母绿", Color(0xFF5AD2A8), Color(0xFF0F7A5A)),
    INDIGO("靛蓝", Color(0xFF939CF0), Color(0xFF3A44B8)),
    BERRY("莓果", Color(0xFFEB89B5), Color(0xFFAD2466)),
    TEAL("松石", Color(0xFF6CD4C8), Color(0xFF0D766E)),
    OAT("燕麦", Color(0xFFDCC7A6), Color(0xFF7F5F32)),
    LIME("青柠", Color(0xFFB6DE7B), Color(0xFF4A7812)),
    SLATE("石板", Color(0xFF9CB4CC), Color(0xFF335573));

    /** 兼容深色默认主色访问 */
    val seed: Color get() = darkSeed

    fun seedFor(dark: Boolean): Color = if (dark) darkSeed else lightSeed
}

enum class DarkMode(val label: String) { FOLLOW("跟随系统"), DARK("深色"), LIGHT("浅色") }

data class AppPalette(
    val isDark: Boolean,
    val scrim: Color,
    val surface: Color,
    val card: Color,
    val cardHigh: Color,
    val row: Color,
    val primary: Color,
    val primaryDeep: Color,
    val primarySoft: Color,
    val onPrimary: Color,
    val chip: Color,
    val chipSelected: Color,
    val text: Color,
    val textSecond: Color,
    val textDim: Color,
    val divider: Color
)

/**
 * 为动态壁纸取色或外部种子在浅色模式下钳制亮度，保留原有色相饱和度而不变浑浊发灰。
 */
private fun ensureLightAccent(seed: Color): Color {
    if (seed.luminance() <= 0.24f) return seed
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(seed.toArgb(), hsl)
    hsl[1] = hsl[1].coerceAtLeast(if (hsl[1] < 0.08f) hsl[1] else 0.48f)
    hsl[2] = hsl[2].coerceAtMost(0.38f)
    return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
}

fun buildAppPalette(seed: Color, dark: Boolean, isDynamicSeed: Boolean = false): AppPalette {
    return if (dark) {
        val baseDark = Color(0xFF121013)
        val surface = lerp(baseDark, seed, 0.04f)
        val row = lerp(Color(0xFF1D1A1E), seed, 0.07f)
        val card = lerp(Color(0xFF211D22), seed, 0.08f)
        val cardHigh = lerp(Color(0xFF2B252C), seed, 0.11f)
        val chip = lerp(Color(0xFF332C34), seed, 0.14f)
        val chipSelected = lerp(Color(0xFF3E3540), seed, 0.38f)
        val primaryDeep = lerp(Color(0xFF2A2228), seed, 0.52f)
        AppPalette(
            isDark = true,
            scrim = Color(0xCC09070A),
            surface = surface,
            card = card,
            cardHigh = cardHigh,
            row = row,
            primary = seed,
            primaryDeep = primaryDeep,
            primarySoft = seed.copy(alpha = 0.22f),
            onPrimary = if (seed.luminance() > 0.45f) Color(0xFF1A1216) else Color(0xFFFFFFFF),
            chip = chip,
            chipSelected = chipSelected,
            text = Color(0xFFF3EDF0),
            textSecond = lerp(Color(0xFFB8B0B4), seed, 0.16f),
            textDim = lerp(Color(0xFF857C81), seed, 0.12f),
            divider = Color(0x24F3EDF0)
        )
    } else {
        // 浅色模式：使用独立饱和的 lightSeed（或 HSL 亮度钳制后的动态色），确保纯正鲜明且高对比
        val accent = if (isDynamicSeed) ensureLightAccent(seed) else ensureLightAccent(seed)
        val surface = lerp(Color(0xFFF5F6FA), accent, 0.04f)
        val card = Color(0xFFFFFFFF)
        val row = lerp(Color(0xFFEDF0F7), accent, 0.05f)
        val cardHigh = lerp(Color(0xFFE4E7F1), accent, 0.08f)
        val chip = lerp(Color(0xFFDCDEE9), accent, 0.09f)
        val chipSelected = lerp(Color(0xFFE8EAF2), accent, 0.24f)
        val primaryDeep = lerp(Color(0xFFD5D9E6), accent, 0.34f)
        AppPalette(
            isDark = false,
            scrim = Color(0x66121016),
            surface = surface,
            card = card,
            cardHigh = cardHigh,
            row = row,
            primary = accent,
            primaryDeep = primaryDeep,
            primarySoft = accent.copy(alpha = 0.14f),
            onPrimary = Color(0xFFFFFFFF),
            chip = chip,
            chipSelected = chipSelected,
            text = Color(0xFF121217),
            textSecond = Color(0xFF34343E),
            textDim = Color(0xFF585866),
            divider = Color(0x26121217)
        )
    }
}

fun presetScheme(palette: AppPalette): ColorScheme {
    return if (palette.isDark) {
        darkColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.chipSelected,
            onPrimaryContainer = palette.text,
            secondary = palette.primary,
            background = palette.surface,
            onBackground = palette.text,
            surface = palette.card,
            onSurface = palette.text,
            surfaceVariant = palette.cardHigh,
            onSurfaceVariant = palette.textSecond,
            outline = palette.divider,
            error = Color(0xFFE87F8A)
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.chipSelected,
            onPrimaryContainer = palette.text,
            secondary = palette.primary,
            background = palette.surface,
            onBackground = palette.text,
            surface = palette.card,
            onSurface = palette.text,
            surfaceVariant = palette.cardHigh,
            onSurfaceVariant = palette.textSecond,
            outline = palette.divider,
            error = Color(0xFFB3261E)
        )
    }
}

@Composable
fun rememberPaletteAndScheme(
    preset: ThemePreset,
    darkMode: DarkMode,
    dynamicColor: Boolean,
    systemDark: Boolean
): Pair<AppPalette, ColorScheme> {
    val dark = when (darkMode) {
        DarkMode.DARK -> true
        DarkMode.LIGHT -> false
        DarkMode.FOLLOW -> systemDark
    }
    val ctx = LocalContext.current
    if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        val pal = buildAppPalette(dynScheme.primary, dark, isDynamicSeed = true)
        return pal to dynScheme
    }
    val pal = buildAppPalette(preset.seedFor(dark), dark, isDynamicSeed = false)
    return pal to presetScheme(pal)
}
