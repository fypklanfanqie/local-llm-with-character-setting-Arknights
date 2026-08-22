package com.rhodesisland.terminal.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 全局亮/暗主题标记：替代组件内直接调用 [isSystemInDarkTheme]，保证主题决策单一来源。 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * Chat by your side · Iris 玻璃主题。
 *
 * - 跟随系统亮/暗
 * - 挂 Material3 colorScheme（Iris 冰蓝）+ Iris 字体 + Iris 形状
 * - 通过 [LocalGlassTokens] 提供亮/暗玻璃叠层色
 * - 通过 [LocalDarkTheme] 提供统一明暗标记
 * - 同步系统状态栏 / 导航栏图标明暗
 */
@Composable
fun ChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) IrisDarkColors else IrisLightColors
    val glassTokens = if (darkTheme) DarkGlassTokens else LightGlassTokens
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // 安全取 Activity + 整段 runCatching：个别 ROM/主题包装使 Compose 视图 context
            // 非 Activity（ContextThemeWrapper 等），首帧强转曾直接 ClassCastException。
            // 失败仅放弃本次系统栏图标同步，绝不影响首帧渲染。
            runCatching {
                val activity = view.context as? Activity ?: return@SideEffect
                val window = activity.window
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(
        LocalGlassTokens provides glassTokens,
        LocalDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = IrisTypography,
            shapes = IrisShapes,
            content = content,
        )
    }
}
