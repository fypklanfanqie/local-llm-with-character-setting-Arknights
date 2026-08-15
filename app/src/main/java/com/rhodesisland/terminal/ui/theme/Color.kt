package com.rhodesisland.terminal.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Chat by your side · Iris 玻璃主题色板
 *
 * 设计语言参考 Cresto Glasense：中性毛玻璃底 + 冰蓝 Iris 强调色，支持亮/暗双模。
 * 玻璃面的半透明叠层颜色见 [GlassTokens]（由 [LocalGlassTokens] 按亮暗提供）。
 */

// ---------- 紫罗兰强调色 ----------
val IrisPrimary = Color(0xFF7C5CFF)      // 暗：紫罗兰
val IrisPrimaryLight = Color(0xFF6E4DFF) // 亮：紫罗兰（略深保白底可读）
val IrisBright = Color(0xFFA99BFF)
val IrisDim = Color(0xFF3A2A6F)
val IrisViolet = Color(0xFF7C5CFF)
val IrisMint = Color(0xFF34C7BE)

val IrisDarkColors: ColorScheme = darkColorScheme(
    primary = IrisPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF221845),
    onPrimaryContainer = Color(0xFFD9CCFF),
    secondary = IrisViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF241A4D),
    onSecondaryContainer = Color(0xFFD9CCFF),
    tertiary = IrisMint,
    onTertiary = Color(0xFF00201E),
    tertiaryContainer = Color(0xFF0E2E2C),
    onTertiaryContainer = Color(0xFFB6F0E8),
    background = Color(0xFF07080C),
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF0E1016),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF1A1D26),
    onSurfaceVariant = Color(0xFFA8AEC0),
    surfaceTint = IrisPrimary,
    inverseSurface = Color(0xFFE8EAF0),
    inverseOnSurface = Color(0xFF14161C),
    outline = Color(0xFF2A2E3A),
    outlineVariant = Color(0xFF1F222C),
    error = Color(0xFFFF453A),
    onError = Color.White,
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD4),
    scrim = Color.Black,
    surfaceBright = Color(0xFF1B1E26),
    surfaceDim = Color(0xFF0A0B10),
    surfaceContainer = Color(0xFF12141B),
    surfaceContainerHigh = Color(0xFF181A22),
    surfaceContainerHighest = Color(0xFF1E2029),
    surfaceContainerLow = Color(0xFF0D0F15),
    surfaceContainerLowest = Color(0xFF06070B),
)

val IrisLightColors: ColorScheme = lightColorScheme(
    primary = IrisPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1E104F),
    secondary = IrisViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6DEFF),
    onSecondaryContainer = Color(0xFF1E104F),
    tertiary = IrisMint,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDF3EC),
    onTertiaryContainer = Color(0xFF00201E),
    background = Color(0xFFF2F3F8),
    onBackground = Color(0xFF0B0C10),
    surface = Color(0xFFFCFCFF),
    onSurface = Color(0xFF0B0C10),
    surfaceVariant = Color(0xFFE4E6EE),
    onSurfaceVariant = Color(0xFF47484F),
    surfaceTint = IrisPrimaryLight,
    inverseSurface = Color(0xFF14161C),
    inverseOnSurface = Color(0xFFE8EAF0),
    outline = Color(0xFFC2C5CE),
    outlineVariant = Color(0xFFE0E2EA),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFCFCFF),
    surfaceDim = Color(0xFFDADCFF),
    surfaceContainer = Color(0xFFEEF0F7),
    surfaceContainerHigh = Color(0xFFE8EAF1),
    surfaceContainerHighest = Color(0xFFE2E5EB),
    surfaceContainerLow = Color(0xFFF4F5FB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

// ---------- 玻璃叠层 tokens ----------
data class GlassTokens(
    val surfaceTint: Color,
    val borderHighlight: Color,
    val borderShadow: Color,
    val sheen: Color,
    val ambientShadow: Color,
    val spotShadow: Color,
    val onGlass: Color,
    val onGlassSecondary: Color,
)

val DarkGlassTokens = GlassTokens(
    surfaceTint = Color.Black.copy(alpha = 0.38f),
    borderHighlight = Color.White.copy(alpha = 0.16f),
    borderShadow = Color.Black.copy(alpha = 0.45f),
    sheen = Color.White.copy(alpha = 0.08f),
    ambientShadow = Color.Black.copy(alpha = 0.30f),
    spotShadow = Color.Black.copy(alpha = 0.50f),
    onGlass = Color(0xFFE8EAF0),
    onGlassSecondary = Color(0xFFA8AEC0),
)

val LightGlassTokens = GlassTokens(
    surfaceTint = Color.White.copy(alpha = 0.34f),      // 白霜（真模糊下 30-40% 通透）
    borderHighlight = Color.White.copy(alpha = 0.60f),  // 亮色描边（设计稿 0.85 与用户 30-50% 的平衡）
    borderShadow = Color(0xFF6E4DFF).copy(alpha = 0.10f),
    sheen = Color.White.copy(alpha = 0.55f),            // 顶部高光
    ambientShadow = Color(0xFF6E4DFF).copy(alpha = 0.10f), // 淡紫弥散阴影
    spotShadow = Color(0xFF7C5CFF).copy(alpha = 0.20f),    // 紫罗兰 glow（英雄级元素单独传更强 spot）
    onGlass = Color(0xFF0B0C10),
    onGlassSecondary = Color(0xFF47484F),
)

val LocalGlassTokens = staticCompositionLocalOf { DarkGlassTokens }
