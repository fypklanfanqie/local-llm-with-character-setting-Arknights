package com.rhodesisland.terminal.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Rhodes Island Terminal · PRTS 深色主题色板
 *
 * 设计语言：深藏青/近黑底 + 罗德岛金强调 + 青/蓝科技辅助色（明日方舟 PRTS 终端风）。
 * 保持大众版的玻璃组件与 Material3 结构，仅重配色为深色科幻风。
 */

// ---------- PRTS 强调色 ----------
val IrisPrimary = Color(0xFFC9A87C)      // 暗：罗德岛金
val IrisPrimaryLight = Color(0xFFD4B88C) // 亮：亮金（保白底可读）
val IrisBright = Color(0xFFE3CDA8)
val IrisDim = Color(0xFF8A7355)
val IrisViolet = Color(0xFF4FA5A0)       // 辅助：青（PRTS 科技辅色）
val IrisMint = Color(0xFF55B9B4)

val IrisDarkColors: ColorScheme = darkColorScheme(
    primary = IrisPrimary,
    onPrimary = Color(0xFF1A1510),
    primaryContainer = Color(0xFF2A241A),
    onPrimaryContainer = IrisBright,
    secondary = IrisViolet,
    onSecondary = Color(0xFF0D1B1A),
    secondaryContainer = Color(0xFF163331),
    onSecondaryContainer = Color(0xFFA9E6E1),
    tertiary = IrisMint,
    onTertiary = Color(0xFF0B1D1C),
    tertiaryContainer = Color(0xFF143634),
    onTertiaryContainer = Color(0xFFB8F0EC),
    background = Color(0xFF0A0A0F),
    onBackground = Color(0xFFE8E4E0),
    surface = Color(0xFF12121C),
    onSurface = Color(0xFFE8E4E0),
    surfaceVariant = Color(0xFF1E1E2E),
    onSurfaceVariant = Color(0xFF9A9690),
    surfaceTint = IrisPrimary,
    inverseSurface = Color(0xFFE8E4E0),
    inverseOnSurface = Color(0xFF14161C),
    outline = Color(0xFF3A352E),
    outlineVariant = Color(0xFF26221C),
    error = Color(0xFFB55A5A),
    onError = Color.White,
    errorContainer = Color(0xFF3A1F1F),
    onErrorContainer = Color(0xFFFFD8D0),
    scrim = Color.Black,
    surfaceBright = Color(0xFF1E1E2A),
    surfaceDim = Color(0xFF0C0C13),
    surfaceContainer = Color(0xFF14141E),
    surfaceContainerHigh = Color(0xFF1A1A26),
    surfaceContainerHighest = Color(0xFF1E1E2E),
    surfaceContainerLow = Color(0xFF0E0E16),
    surfaceContainerLowest = Color(0xFF07070B),
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
    borderHighlight = Color.White.copy(alpha = 0.14f),
    borderShadow = Color.Black.copy(alpha = 0.45f),
    sheen = Color.White.copy(alpha = 0.07f),
    ambientShadow = Color.Black.copy(alpha = 0.30f),
    spotShadow = Color.Black.copy(alpha = 0.50f),
    onGlass = Color(0xFFE8E4E0),
    onGlassSecondary = Color(0xFF9A9690),
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
