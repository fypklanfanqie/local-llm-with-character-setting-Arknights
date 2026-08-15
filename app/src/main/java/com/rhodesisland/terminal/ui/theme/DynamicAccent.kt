package com.rhodesisland.terminal.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 全局动态强调色：由通讯页当前立绘的主题色驱动。
 *
 * 仅在通讯页可见时非空（由通讯页上报、[com.rhodesisland.terminal.ui.navigation.AppNavGraph] 在
 * Scaffold 层级提供），驱动通讯页「开始对话/对话/人设」按钮与 dock 栏选中色随立绘变化。
 * 其它页面 / Tab 下为 null，组件回退到 Material colorScheme 默认紫罗兰，互不影响。
 */
val LocalDynamicAccent = staticCompositionLocalOf<Color?> { null }
