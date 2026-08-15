package com.rhodesisland.terminal.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 按当前页面背景驱动系统状态栏 / 导航栏图标颜色：
 *
 * @param light true → 白色图标（深色照片背景，如通讯立绘页 / 聊天背景页）；
 *              false → 深色图标（全局亮色主题）。
 *
 * 用 [LaunchedEffect]（不做 dispose 恢复）：feed/chat 同属通讯 Tab，切换时若用「dispose 恢复成深色」，
 * 旧页面的恢复会覆盖新页面刚设的白色（竞态）。非通讯 Tab 的深色复位统一由 AppNavGraph 在路由变化时处理。
 */
@Composable
internal fun applySystemBarIcons(light: Boolean) {
    val view = LocalView.current
    val window = (LocalContext.current as? Activity)?.window
    LaunchedEffect(light, window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = !light
        controller?.isAppearanceLightNavigationBars = !light
    }
}
