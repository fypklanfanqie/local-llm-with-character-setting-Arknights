package com.rhodesisland.terminal.ui.navigation

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.ui.characters.CharactersScreen
import com.rhodesisland.terminal.ui.chat.ChatScreen
import com.rhodesisland.terminal.ui.feed.CharacterFeedScreen
import com.rhodesisland.terminal.ui.feed.CharacterFeedHost
import com.rhodesisland.terminal.ui.feed.FeedRoute
import com.rhodesisland.terminal.ui.glass.GlassNavBar
import com.rhodesisland.terminal.ui.glass.GlassNavItem
import com.rhodesisland.terminal.ui.groupchat.GroupChatScreen
import com.rhodesisland.terminal.ui.groupchat.GroupListScreen
import com.rhodesisland.terminal.ui.groupchat.GroupNavigationBus
import com.rhodesisland.terminal.ui.models.ModelManagerScreen
import com.rhodesisland.terminal.ui.music.MusicScreen
import com.rhodesisland.terminal.ui.lorebook.LorebookDetailScreen
import com.rhodesisland.terminal.ui.lorebook.LorebookEntryEditScreen
import com.rhodesisland.terminal.ui.settings.BackendSettingsScreen
import com.rhodesisland.terminal.ui.settings.SettingsScreen
import com.rhodesisland.terminal.ui.theme.LocalDynamicAccent
import com.rhodesisland.terminal.ui.video.EncounterScreen
import com.rhodesisland.terminal.ui.affinity.DailyCheckinBus
import com.rhodesisland.terminal.ui.affinity.DailyCheckinDialog
import com.rhodesisland.terminal.ui.affinity.CheckinShopScreen
import com.rhodesisland.terminal.ui.affinity.AffinityScreen
import com.rhodesisland.terminal.ui.affinity.AffinityGiftsScreen
import com.rhodesisland.terminal.ui.affinity.AffinityEventsScreen
import androidx.compose.ui.graphics.Color

/**
 * 底部导航 Tab 定义
 *
 * 移除了"积分"Tab（付费功能已删除）
 * 新增"模型"Tab（本地 AI 模型管理）
 * 图标细线化：未选中 Outlined 描边 / 选中 Filled 实心（iOS SF Symbols 风）。
 */
sealed class BottomTab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    object Chat : BottomTab("chat", "通讯", Icons.AutoMirrored.Outlined.Chat, Icons.AutoMirrored.Filled.Chat)
    object Characters : BottomTab("characters", "角色", Icons.Outlined.Person, Icons.Filled.Person)
    object Music : BottomTab("music", "音乐", Icons.Outlined.MusicNote, Icons.Filled.MusicNote)
    object Models : BottomTab("models", "模型", Icons.Outlined.Storage, Icons.Filled.Storage)
    object Settings : BottomTab("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings)
}

private const val CHECKIN_SHOP_ROUTE = "checkin_shop"
private const val AFFINITY_ROUTE = "affinity/{characterId}"
private const val AFFINITY_GIFTS_ROUTE = "affinity_gifts/{characterId}"
private const val AFFINITY_EVENTS_ROUTE = "affinity_events/{characterId}"
private fun affinityRoute(characterId: String): String = "affinity/${android.net.Uri.encode(characterId)}"
private fun affinityGiftsRoute(characterId: String): String = "affinity_gifts/${android.net.Uri.encode(characterId)}"
private fun affinityEventsRoute(characterId: String): String = "affinity_events/${android.net.Uri.encode(characterId)}"

// 世界书两级路由：书详情（条目列表/作用域绑定）→ 条目编辑（entryId="new" 表新建）
private const val LOREBOOK_DETAIL_ROUTE = "lorebook/{bookId}"
private const val LOREBOOK_ENTRY_ROUTE = "lorebook/{bookId}/entry/{entryId}"
private fun lorebookDetailRoute(bookId: String): String = "lorebook/${android.net.Uri.encode(bookId)}"
private fun lorebookEntryRoute(bookId: String, entryId: String): String =
    "lorebook/${android.net.Uri.encode(bookId)}/entry/${android.net.Uri.encode(entryId)}"

@Composable
fun AppNavGraph(container: AppContainer, initialChatOpen: Boolean = false) {
    val navController = rememberNavController()
    // 聊天 Tab 的内嵌导航控制器：提升到 AppNavGraph 作用域，跨 Tab 切换存活，
    // 保证「feed → chat」的嵌套栈在切走再切回时不丢（否则每次回来都重置回 feed）。
    val feedNavController = rememberNavController()
    // 冷启动来自问候通知时只消费一次，避免每次从其他 Tab 切回通讯都重复跳转到聊天页。
    var hasHandledInitialChat by rememberSaveable { mutableStateOf(false) }
    val dailyCheckinNonce by DailyCheckinBus.requests.collectAsState()
    var handledDailyCheckinNonce by remember { mutableStateOf(0L) }
    var showDailyCheckin by remember { mutableStateOf(false) }
    LaunchedEffect(dailyCheckinNonce) {
        if (dailyCheckinNonce > handledDailyCheckinNonce) {
            handledDailyCheckinNonce = dailyCheckinNonce
            showDailyCheckin = true
        }
    }
    val tabs = listOf(BottomTab.Chat, BottomTab.Characters, BottomTab.Music, BottomTab.Models, BottomTab.Settings)

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // 系统栏图标颜色复位：本应用固定 PRTS 深色主题，非通讯 Tab 都是深色极光底 → 白色图标；
    // 通讯 Tab 由 feed/chat 页面各自按背景驱动（深色立绘/照片 → 白色图标），此处不覆盖。
    val view = LocalView.current
    val window = (LocalContext.current as? Activity)?.window
    val isChatTab = currentDestination?.hierarchy?.any { it.route == BottomTab.Chat.route } == true
    LaunchedEffect(isChatTab) {
        if (!isChatTab) {
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
        }
    }

    // 群聊通知点按（冷启动 + 运行中 onNewIntent 统一走 GroupNavigationBus）：切到通讯 Tab 并直达对应群聊。
    // nonce 计数 + remember 已处理 nonce，消费一次即幂等，重组/切 Tab 不重复跳转。
    val groupRequest by GroupNavigationBus.requests.collectAsState()
    var lastHandledGroupNonce by remember { mutableStateOf(0L) }
    LaunchedEffect(groupRequest) {
        val req = groupRequest ?: return@LaunchedEffect
        if (req.nonce > lastHandledGroupNonce) {
            lastHandledGroupNonce = req.nonce
            navController.navigate(BottomTab.Chat.route) {
                popUpTo(BottomTab.Chat.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            feedNavController.navigate(FeedRoute.groupChatRoute(req.groupId)) { launchSingleTop = true }
        }
    }

    // 通讯页立绘主题色：由通讯页上报，在此为整个 Scaffold（含 dock 栏）提供，
    // 仅在通讯页可见时非空，其它 Tab 自动复位为默认紫罗兰。
    var accentColor by remember { mutableStateOf<Color?>(null) }

    CompositionLocalProvider(LocalDynamicAccent provides accentColor) {
    androidx.compose.material3.Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        // 各页面已在自身根布局上用 windowInsetsPadding(WindowInsets.statusBars) 处理顶部 inset，
        // 这里若再用默认 contentWindowInsets(=systemBars) 会重复加一遍状态栏 padding，
        // 导致顶部留出 ~2× 状态栏高度的空白黑条（聊天页因有图片背景尤为明显）。
        // 置 0 交由各页自处理；底部 NavigationBar 自带 navigationBars inset，行为不变。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            val isAffinityDestination = currentDestination?.route in setOf(CHECKIN_SHOP_ROUTE, AFFINITY_ROUTE, AFFINITY_GIFTS_ROUTE, AFFINITY_EVENTS_ROUTE)
            if (!isAffinityDestination) {
            val currentTabRoute = tabs.firstOrNull { tab ->
                currentDestination?.hierarchy?.any { it.route == tab.route } == true
            }?.route
            GlassNavBar(
                items = tabs.map {
                    GlassNavItem(route = it.route, label = it.label, icon = it.icon, selectedIcon = it.selectedIcon)
                },
                currentRoute = currentTabRoute ?: "",
                onSelect = { route ->
                    android.util.Log.d("AppNavGraph", "onSelect: route=$route currentTabRoute=$currentTabRoute")
                    if (route == currentTabRoute) {
                        // 重选当前 Tab：通讯 Tab 内若已进到聊天页，则回到卡片流首页。
                        if (route == BottomTab.Chat.route) {
                            feedNavController.popBackStack(FeedRoute.FEED, inclusive = false)
                        }
                        return@GlassNavBar
                    }
                    navController.navigate(route) {
                        // 弹出到起始 Tab（通讯），保存其它 Tab 的状态；
                        // 用 route 字符串避免 findStartDestination().id 在嵌套导航下可能的不确定性。
                        popUpTo(BottomTab.Chat.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
            }
        }
    ) { padding ->
        // Scaffold 内容底部 padding（= 底栏高度）作为 bottomBarHeight 传入：通讯 Tab 全屏铺背景、
        // 交互内容用它抬到 dock 之上；其余 Tab 用它（tabBottomPadding）预留底栏空间。
        val bottomBarHeight = padding.calculateBottomPadding()
        // 键盘弹出时「隐藏」底栏：底栏始终绘制但被键盘自然覆盖（视觉隐藏）。内容底部留白取
        // max(底栏高度, IME 高度) = padding(=底栏高度 N) + (IME - N).coerceAtLeast(0)，由
        // ClampedImeBottomPadding 在 measure 阶段读取 IME（仅 relayout、不触发重组），配合
        // MainActivity 请求的最高刷新率让上移动画跑满帧、不丢帧。
        // NavHost 放在 Scaffold content 内，应用 padding 避免被底部导航栏遮挡
        // 背景极光已由 MainActivity 的 GlassBackdrop + MeshBackground 全屏提供；此处不铺内层，
        // 避免双重渲染，也让半透明玻璃面板采样到的背板与可见背景严格一致。
        // 通讯 Tab（feed / chat）需铺满整屏：立绘 / 聊天背景要一直延伸到浮动 dock 与系统导航栏
        // 背后、直达屏幕最底部，因此不在此处为它预留底栏高度（dock 作为浮层叠在最上）。
        // 其余 Tab 仍预留底栏 + 钳制 IME，保持原行为。
        // 注：曾用 extendBelow(bottomBarHeight) 让背景「溢出」到 dock 背后，但 NavHost 内部
        // 用 AnimatedContent 会按自身边界裁剪内容，溢出部分被裁掉 -> 背景仍止于 dock 上方，
        // 下方露出浅色极光底（即「底部一大块白色」）。改为通讯 Tab 直接全屏即可根治。
        val tabBottomPadding = Modifier
            .fillMaxSize()
            .padding(padding)
            .then(ClampedImeBottomPadding(WindowInsets.ime, padding))

        NavHost(
            navController = navController,
            startDestination = BottomTab.Chat.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(BottomTab.Chat.route) {
                // 聊天 Tab = 「刷抖音」卡片流(feed) + 完整聊天(chat) 的内嵌导航。
                // startDestination 恒为 feed：冷启动来自问候通知时在其上压入 chat（栈底保留 feed，
                // 返回键可回到卡片流，而非直接退出 App）。
                NavHost(
                    navController = feedNavController,
                    startDestination = FeedRoute.FEED,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable(FeedRoute.FEED) {
                        CharacterFeedHost(
                            container = container,
                            bottomBarHeight = bottomBarHeight,
                            onAccent = { accentColor = it },
                            onOpenChat = { charId ->
                                feedNavController.navigate(FeedRoute.CHAT) { launchSingleTop = true }
                            },
                            onNavigateToCharacters = {
                                navController.navigate(BottomTab.Characters.route) { launchSingleTop = true }
                            },
                            onOpenEncounter = {
                                feedNavController.navigate(FeedRoute.ENCOUNTER) { launchSingleTop = true }
                            },
                            onOpenGroupChat = {
                                feedNavController.navigate(FeedRoute.GROUP_LIST) { launchSingleTop = true }
                            },
                        )
                    }
                    composable(FeedRoute.CHAT) {
                        ChatScreen(
                            container = container,
                            // 底栏高度：供聊天交互层把输入栏抬到 dock 之上（背景层已独立全屏铺满）。
                            bottomBarHeight = bottomBarHeight,
                            onBack = { feedNavController.popBackStack() },
                            onNavigateToCharacters = {
                                navController.navigate(BottomTab.Characters.route) { launchSingleTop = true }
                            },
                        )
                    }
                    composable(FeedRoute.ENCOUNTER) {
                        EncounterScreen(
                            container = container,
                            // 底栏高度：浮层 dock 之上预留交互内容空间（背景层全屏铺满）。
                            bottomBarHeight = bottomBarHeight,
                            onBack = { feedNavController.popBackStack() },
                        )
                    }
                    composable(FeedRoute.GROUP_LIST) {
                        GroupListScreen(
                            container = container,
                            bottomBarHeight = bottomBarHeight,
                            onBack = { feedNavController.popBackStack() },
                            onOpenGroup = { groupId ->
                                feedNavController.navigate(FeedRoute.groupChatRoute(groupId)) { launchSingleTop = true }
                            },
                        )
                    }
                    composable(
                        route = FeedRoute.GROUP_CHAT,
                        arguments = listOf(navArgument("groupId") { type = NavType.LongType }),
                    ) { entry ->
                        val groupId = entry.arguments?.getLong("groupId") ?: 0L
                        GroupChatScreen(
                            container = container,
                            // 底栏高度：输入栏抬起到底栏之上（背景层全屏铺满）。
                            bottomBarHeight = bottomBarHeight,
                            groupId = groupId,
                            onBack = { feedNavController.popBackStack() },
                        )
                    }
                }
                // 冷启动来自问候通知：直达该角色会话（栈底仍为 feed，返回可回到卡片流）。
                // 用 rememberSaveable 的 flag 保证只执行一次，防止切回通讯 Tab 时重复跳转。
                LaunchedEffect(Unit) {
                    if (initialChatOpen && !hasHandledInitialChat) {
                        hasHandledInitialChat = true
                        feedNavController.navigate(FeedRoute.CHAT) { launchSingleTop = true }
                    }
                }
            }
            composable(BottomTab.Characters.route) {
                // 宽屏适配：内容列限宽 640dp 居中（Box 默认 TopCenter），手机宽度下无效果；
                // 通讯 Tab（feed/chat/群聊/邂逅）刻意全出血沉浸设计，不加限宽。
                Box(tabBottomPadding) { Box(Modifier.fillMaxWidth().widthIn(max = 640.dp)) { CharactersScreen(
                    container = container,
                    onNavigateToChat = {
                        // 切到聊天 Tab（保底栏状态）
                        navController.navigate(BottomTab.Chat.route) {
                            popUpTo(BottomTab.Chat.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                        // 网格选完角色 → 直达嵌套聊天（弹掉 feed，避免回来又停在卡片流）
                        feedNavController.navigate(FeedRoute.CHAT) {
                            popUpTo(FeedRoute.FEED)
                            launchSingleTop = true
                        }
                    },
                    onOpenCheckinShop = { navController.navigate(CHECKIN_SHOP_ROUTE) },
                    onOpenAffinity = { characterId -> navController.navigate(affinityRoute(characterId)) },
                ) } }
            }
            composable(CHECKIN_SHOP_ROUTE) {
                // 独立目的地：不包在角色页或底栏内容容器中，避免浮在角色网格上。
                CheckinShopScreen(container = container, onBack = { navController.popBackStack() })
            }
            composable(
                route = AFFINITY_ROUTE,
                arguments = listOf(navArgument("characterId") { type = NavType.StringType }),
            ) { entry ->
                val characterId = entry.arguments?.getString("characterId").orEmpty()
                val character by container.characterRepository.characters.collectAsState(initial = emptyList())
                val selected = character.firstOrNull { it.id == characterId }
                if (selected != null) {
                    AffinityScreen(
                        container = container,
                        character = selected,
                        imageUrl = if (selected.isCustom && selected.image.isNotBlank()) selected.image else container.assetRepository.getSelectionPicture(selected.id),
                        onBack = { navController.popBackStack() },
                        onOpenGifts = { navController.navigate(affinityGiftsRoute(selected.id)) },
                        onOpenEvents = { navController.navigate(affinityEventsRoute(selected.id)) },
                    )
                }
            }
            composable(
                route = AFFINITY_GIFTS_ROUTE,
                arguments = listOf(navArgument("characterId") { type = NavType.StringType }),
            ) { entry ->
                val characterId = entry.arguments?.getString("characterId").orEmpty()
                val character by container.characterRepository.characters.collectAsState(initial = emptyList())
                character.firstOrNull { it.id == characterId }?.let { selected ->
                    AffinityGiftsScreen(container, selected, onBack = { navController.popBackStack() })
                }
            }
            composable(
                route = AFFINITY_EVENTS_ROUTE,
                arguments = listOf(navArgument("characterId") { type = NavType.StringType }),
            ) { entry ->
                val characterId = entry.arguments?.getString("characterId").orEmpty()
                val character by container.characterRepository.characters.collectAsState(initial = emptyList())
                character.firstOrNull { it.id == characterId }?.let { selected ->
                    AffinityEventsScreen(
                        container,
                        selected,
                        onBack = { navController.popBackStack() },
                        onOpenEventConversation = {
                            navController.navigate(BottomTab.Chat.route) {
                                popUpTo(BottomTab.Chat.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                            feedNavController.navigate(FeedRoute.CHAT) {
                                popUpTo(FeedRoute.FEED)
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }
            composable(BottomTab.Music.route) {
                Box(tabBottomPadding) { Box(Modifier.fillMaxWidth().widthIn(max = 640.dp)) { MusicScreen(container = container) } }
            }
            composable(BottomTab.Models.route) {
                Box(tabBottomPadding) { Box(Modifier.fillMaxWidth().widthIn(max = 640.dp)) { ModelManagerScreen(container = container) } }
            }
            composable(BottomTab.Settings.route) {
                Box(tabBottomPadding) {
                    Box(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                        SettingsScreen(
                            container = container,
                            onNavigateToBackendSettings = {
                                navController.navigate("backend_settings") { launchSingleTop = true }
                            },
                            onNavigateToLorebook = { bookId ->
                                navController.navigate(lorebookDetailRoute(bookId)) { launchSingleTop = true }
                            },
                        )
                    }
                }
            }
            composable("backend_settings") {
                Box(tabBottomPadding) {
                    Box(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                        BackendSettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
            composable(
                route = LOREBOOK_DETAIL_ROUTE,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            ) { entry ->
                Box(tabBottomPadding) {
                    Box(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                        LorebookDetailScreen(
                            container = container,
                            bookId = entry.arguments?.getString("bookId").orEmpty(),
                            onBack = { navController.popBackStack() },
                            onOpenEntry = { entryId ->
                                val bookId = entry.arguments?.getString("bookId").orEmpty()
                                navController.navigate(lorebookEntryRoute(bookId, entryId)) { launchSingleTop = true }
                            },
                        )
                    }
                }
            }
            composable(
                route = LOREBOOK_ENTRY_ROUTE,
                arguments = listOf(
                    navArgument("bookId") { type = NavType.StringType },
                    navArgument("entryId") { type = NavType.StringType },
                ),
            ) { entry ->
                Box(tabBottomPadding) {
                    Box(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                        LorebookEntryEditScreen(
                            container = container,
                            bookId = entry.arguments?.getString("bookId").orEmpty(),
                            entryId = entry.arguments?.getString("entryId").orEmpty(),
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
        if (showDailyCheckin) {
            DailyCheckinDialog(container = container, onDismiss = { showDailyCheckin = false })
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
internal class ClampedImeBottomPadding(
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
