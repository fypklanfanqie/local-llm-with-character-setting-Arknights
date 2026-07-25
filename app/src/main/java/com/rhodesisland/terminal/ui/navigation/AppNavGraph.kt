package com.rhodesisland.terminal.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.ui.characters.CharactersScreen
import com.rhodesisland.terminal.ui.chat.ChatScreen
import com.rhodesisland.terminal.ui.models.ModelManagerScreen
import com.rhodesisland.terminal.ui.music.MusicScreen
import com.rhodesisland.terminal.ui.settings.BackendSettingsScreen
import com.rhodesisland.terminal.ui.settings.SettingsScreen
import com.rhodesisland.terminal.ui.theme.PrtsColors

/**
 * 底部导航 Tab 定义
 *
 * 移除了"积分"Tab（付费功能已删除）
 * 新增"模型"Tab（本地 AI 模型管理）
 */
sealed class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    object Chat : BottomTab("chat", "通讯", Icons.Filled.Chat)
    object Characters : BottomTab("characters", "干员", Icons.Filled.Person)
    object Music : BottomTab("music", "音乐", Icons.Filled.MusicNote)
    object Models : BottomTab("models", "模型", Icons.Filled.Storage)
    object Settings : BottomTab("settings", "设置", Icons.Filled.Settings)
}

@Composable
fun AppNavGraph(container: AppContainer) {
    val navController = rememberNavController()
    val tabs = listOf(BottomTab.Chat, BottomTab.Characters, BottomTab.Music, BottomTab.Models, BottomTab.Settings)

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    androidx.compose.material3.Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PrtsColors.BgPrimary,
        // 各页面已在自身根布局上用 windowInsetsPadding(WindowInsets.statusBars) 处理顶部 inset，
        // 这里若再用默认 contentWindowInsets(=systemBars) 会重复加一遍状态栏 padding，
        // 导致顶部留出 ~2× 状态栏高度的空白黑条（聊天页因有图片背景尤为明显）。
        // 置 0 交由各页自处理；底部 NavigationBar 自带 navigationBars inset，行为不变。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(
                containerColor = androidx.compose.ui.graphics.Color(0xFF0E0E16),
                contentColor = PrtsColors.Gold,
            ) {
                tabs.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) },
                        colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                            selectedIconColor = PrtsColors.Gold,
                            selectedTextColor = PrtsColors.Gold,
                            unselectedIconColor = PrtsColors.TextDim,
                            unselectedTextColor = PrtsColors.TextDim,
                            indicatorColor = androidx.compose.ui.graphics.Color(0x33C9A87C),
                        ),
                    )
                }
            }
        }
    ) { padding ->
        // 键盘弹出时「隐藏」底栏：底栏始终绘制但被键盘自然覆盖（视觉隐藏）。内容底部留白取
        // max(底栏高度, IME 高度) = padding(=底栏高度 N) + (IME - N).coerceAtLeast(0)，由
        // ClampedImeBottomPadding 在 measure 阶段读取 IME（仅 relayout、不触发重组），配合
        // MainActivity 请求的最高刷新率让上移动画跑满帧、不丢帧。
        // NavHost 放在 Scaffold content 内，应用 padding 避免被底部导航栏遮挡
        NavHost(
            navController = navController,
            startDestination = BottomTab.Chat.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .then(ClampedImeBottomPadding(WindowInsets.ime, padding)),
        ) {
            composable(BottomTab.Chat.route) {
                ChatScreen(container = container, onNavigateToCharacters = {
                    navController.navigate(BottomTab.Characters.route) {
                        launchSingleTop = true
                    }
                })
            }
            composable(BottomTab.Characters.route) {
                CharactersScreen(container = container, onNavigateToChat = {
                    navController.navigate(BottomTab.Chat.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable(BottomTab.Music.route) {
                MusicScreen(container = container)
            }
            composable(BottomTab.Models.route) {
                ModelManagerScreen(container = container)
            }
            composable(BottomTab.Settings.route) {
                SettingsScreen(
                    container = container,
                    onNavigateToBackendSettings = {
                        navController.navigate("backend_settings") { launchSingleTop = true }
                    },
                )
            }
            composable("backend_settings") {
                BackendSettingsScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * 底部额外留白 = (IME 高度 - 底栏预留高度 [reserved]).coerceAtLeast(0)。
 *
 * 与外层 [Modifier.padding]（已预留底栏高度 N）叠加后，内容底部恰为 max(N, IME)：
 * - 键盘低于底栏：额外 0，输入框不动（底栏被键盘逐步覆盖，不留空隙）；
 * - 键盘高于底栏：额外 IME-N，输入框随键盘上移到键盘顶部；
 * - 无键盘：IME 为 0，(0-N) 取 0，布局与原来一致。
 *
 * IME 在 [measure] 阶段经 [WindowInsets.getBottom] 读取（snapshot 状态）-> 仅触发 relayout，
 * 不触发重组，故 IME 动画逐帧更新时不会重组内容、不丢帧（配合高刷新率可跑满 120fps）。
 */
private class ClampedImeBottomPadding(
    private val ime: WindowInsets,
    private val reserved: PaddingValues,
) : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val imePx = ime.getBottom(this)
        val reservedPx = reserved.calculateBottomPadding().roundToPx()
        val extraPx = (imePx - reservedPx).coerceAtLeast(0)
        // 底部留白 extraPx：把可用高度收窄 extraPx，再在 layout 里把高度补回，使留白落在底部。
        val maxHeight = if (constraints.maxHeight == Constraints.Infinity) Constraints.Infinity
            else (constraints.maxHeight - extraPx).coerceAtLeast(0)
        val placeable = measurable.measure(
            constraints.copy(
                minHeight = (constraints.minHeight - extraPx).coerceAtLeast(0),
                maxHeight = maxHeight,
            )
        )
        return layout(placeable.width, placeable.height + extraPx) {
            placeable.place(0, 0)
        }
    }
}
