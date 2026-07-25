package com.rhodesisland.terminal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * PRTS 终端主题
 * 强制深色模式
 */
private val PrtsColorScheme = darkColorScheme(
    primary = PrtsColors.Gold,
    onPrimary = PrtsColors.BgPrimary,
    primaryContainer = PrtsColors.BgCard,
    onPrimaryContainer = PrtsColors.GoldBright,
    secondary = PrtsColors.AccentBlue,
    onSecondary = PrtsColors.BgPrimary,
    secondaryContainer = PrtsColors.BgTertiary,
    onSecondaryContainer = PrtsColors.TextPrimary,
    tertiary = PrtsColors.GoldDim,
    background = PrtsColors.BgPrimary,
    onBackground = PrtsColors.TextPrimary,
    surface = PrtsColors.BgSecondary,
    onSurface = PrtsColors.TextPrimary,
    surfaceVariant = PrtsColors.BgTertiary,
    onSurfaceVariant = PrtsColors.TextSecondary,
    outline = PrtsColors.Border,
    outlineVariant = PrtsColors.BorderLight,
    error = PrtsColors.DangerBright,
    onError = PrtsColors.TextPrimary,
)

@Composable
fun RhodesIslandTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PrtsColorScheme,
        typography = PrtsTypography,
        content = content,
    )
}
