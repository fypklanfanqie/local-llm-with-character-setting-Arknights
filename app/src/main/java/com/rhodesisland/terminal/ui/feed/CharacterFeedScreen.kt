package com.rhodesisland.terminal.ui.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.rhodesisland.terminal.ui.glass.GlassButton
import com.rhodesisland.terminal.ui.glass.GlassButtonStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.pager.VerticalPager
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.ui.characters.CustomCharacterDialog
import com.rhodesisland.terminal.ui.characters.PersonaSheet
import com.rhodesisland.terminal.ui.applySystemBarIcons
import com.rhodesisland.terminal.util.CharacterImageStore
import com.rhodesisland.terminal.util.loadThemeColor
import kotlinx.coroutines.launch

/** 聊天 Tab 内嵌导航的路由常量。
 * 注意：内层 CHAT 不要与外层 BottomTab.Chat.route = "chat" 同名，避免导航日志/条件判断混淆。 */
object FeedRoute {
    const val FEED = "feed"
    const val CHAT = "chat_detail"
    const val ENCOUNTER = "encounter"
    /** 群聊列表（微信式：新建/进入已有群）。 */
    const val GROUP_LIST = "group_list"
    /** 群聊会话页路由模板（后接群 id）。 */
    const val GROUP_CHAT = "group_chat/{groupId}"

    fun groupChatRoute(groupId: Long): String = "group_chat/$groupId"
}

/**
 * 刷抖音式角色卡片流（首页）。
 * 全屏竖滑浏览所有角色，停到哪个就能直接与它对话；角色会随机问好。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CharacterFeedScreen(
    container: AppContainer,
    bottomBarHeight: Dp = 0.dp,
    onOpenChat: (String) -> Unit,
    onNavigateToCharacters: () -> Unit,
    /** 进入「邂逅」沉浸式视频历史流（顶栏玻璃按钮）。 */
    onOpenEncounter: () -> Unit = {},
    /** 进入「群聊」多人同群聊天（顶栏玻璃按钮，仅云端可用）。 */
    onOpenGroupChat: () -> Unit = {},
    /** 进入好感度独立页面（由 [CharacterFeedHost] 承载）。 */
    onOpenAffinity: (String) -> Unit = {},
    /** 当前落定立绘的主题色上报（供 dock 栏等全局着色）；页面销毁时应回传 null 复位。 */
    onAccent: (Color?) -> Unit = {},
) {
    val characters by container.characterRepository.characters.collectAsState(
        initial = Characters.getOrderedList(),
    )
    val activeCharacter by container.settingsRepository.activeCharacter.collectAsState(
        initial = Characters.DEFAULT_CHARACTER_ID,
    )
    val volume by container.settingsRepository.volume.collectAsState(initial = 60)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 立绘背景为全屏深色画面：系统状态栏 / 导航栏图标改为白色，保证在深色立绘上可读。
    applySystemBarIcons(light = true)

    // 顶栏下移量：取「WindowInsets 状态栏高度 / 系统 status_bar_height 资源 / 56dp 保底」的最大值，
    // 再叠加 8dp 余量。个别 ROM inset 异常时也能保证「通讯 / 全部角色 / 新建」文字
    // 完整位于状态栏下方，绝不遮挡。
    val density = LocalDensity.current
    val statusBarInset = WindowInsets.statusBars.getTop(density)
    val statusBarRes = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    val statusBarH = if (statusBarRes > 0) context.resources.getDimensionPixelSize(statusBarRes) else 0
    val topBarPadding = with(density) {
        (maxOf(statusBarInset, statusBarH, 56.dp.roundToPx()) + 8.dp.roundToPx()).toDp()
    }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { characters.size })

    var showPersona by remember { mutableStateOf<Character?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Character?>(null) }

    // settle 检测：滚动停止且落在某页时更新（滚动中保持旧值），驱动回弹 / 图标弹出 / 随机问好
    val settledPage = pagerState.settledPage

    // 当前落定角色的立绘主题色：滑动 / 切角色时自动重提取，上报给全局动态强调色
    // （驱动「开始对话/对话/人设」按钮与 dock 栏着色）；页面销毁时复位为 null。
    val settledCharacter = characters.getOrNull(
        if (settledPage >= 0) settledPage else pagerState.currentPage,
    )
    val settledImageUrl = settledCharacter?.let { char ->
        if (char.isCustom && char.image.isNotBlank()) char.image
        else container.assetRepository.getSelectionPicture(char.id)
    }
    LaunchedEffect(settledImageUrl) {
        onAccent(settledImageUrl?.let { loadThemeColor(context, it) })
    }
    DisposableEffect(Unit) {
        onDispose { onAccent(null) }
    }

    // 起始定位 / 外部切角色（问候通知、网格选角色）时跳到该角色页；只响应 activeCharacter，
    // 避免自定义角色列表刷新时把用户手动浏览的位置强行拉回。
    LaunchedEffect(activeCharacter) {
        val idx = characters.indexOfFirst { it.id == activeCharacter }
        if (idx >= 0 && idx != pagerState.currentPage) {
            pagerState.scrollToPage(idx)
        }
    }

    // 列表收缩 clamp（删除自定义角色后）
    LaunchedEffect(characters.size) {
        if (characters.isEmpty()) return@LaunchedEffect
        if (pagerState.currentPage >= characters.size) {
            pagerState.scrollToPage(characters.size - 1)
        }
    }

    // 根容器铺满整屏：通讯 Tab 已在全屏层（不预留底栏），故立绘背景自然延伸到浮动 dock
    // 与系统导航栏背后、直达屏幕最底部，dock 作为浮层叠在最上。加黑色兜底背景，防止图片
    // 加载前 / 失败时透出浅色窗口底。
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        VerticalPager(
            state = pagerState,
            beyondBoundsPageCount = 1,
            key = { i -> characters.getOrNull(i)?.id ?: i },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val char = characters.getOrNull(page) ?: return@VerticalPager
            CharacterFeedPage(
                character = char,
                isActive = char.id == activeCharacter,
                imageUrl = if (char.isCustom && char.image.isNotBlank())
                    char.image
                else
                    container.assetRepository.getSelectionPicture(char.id),
                pagerState = pagerState,
                pageIndex = page,
                settled = settledPage == page,
                bottomBarHeight = bottomBarHeight,
                onChat = {
                    scope.launch {
                        container.settingsRepository.setActiveCharacter(char.id)
                        onOpenChat(char.id)
                    }
                },
                onPersona = { showPersona = char },
                onAffinity = { onOpenAffinity(char.id) },
                onVoice = container.assetRepository.getVoice(char.id).takeIf { it.isNotBlank() }?.let { url ->
                    { scope.launch { container.audioManager.playVoice(url, volume) } }
                },
                onDelete = if (char.isCustom) ({ deleteTarget = char }) else null,
            )
        }

        // 顶栏：沉浸覆盖，文字整体下移（状态栏高度 + 余量），避免遮挡状态栏
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(start = 20.dp, end = 20.dp, top = topBarPadding, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "通讯",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            // 常态右对齐；窄屏/大字号放不下时整行可横向滑动，避免按钮被裁切
            Box(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                contentAlignment = Alignment.CenterEnd,
            ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassButton(
                    onClick = onNavigateToCharacters,
                    style = GlassButtonStyle.Glass,
                    horizontalPadding = 12.dp,
                    verticalPadding = 8.dp,
                ) {
                    Text(
                        "全部角色",
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                GlassButton(
                    onClick = onOpenEncounter,
                    style = GlassButtonStyle.Glass,
                    horizontalPadding = 12.dp,
                    verticalPadding = 8.dp,
                ) {
                    Text(
                        "邂逅",
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                GlassButton(
                    onClick = onOpenGroupChat,
                    style = GlassButtonStyle.Glass,
                    horizontalPadding = 12.dp,
                    verticalPadding = 8.dp,
                ) {
                    Text(
                        "群聊",
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                GlassButton(
                    onClick = { showCreate = true },
                    style = GlassButtonStyle.Glass,
                    horizontalPadding = 12.dp,
                    verticalPadding = 8.dp,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "新建",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            }
        }
    }

    showPersona?.let { char ->
        PersonaSheet(
            character = char,
            imageUrl = if (char.isCustom && char.image.isNotBlank())
                char.image
            else
                container.assetRepository.getSelectionPicture(char.id),
            onDismiss = { showPersona = null },
        )
    }

    if (showCreate) {
        CustomCharacterDialog(
            onDismiss = { showCreate = false },
            onConfirm = { c ->
                scope.launch { container.characterRepository.addCustom(c) }
                showCreate = false
            },
        )
    }

    deleteTarget?.let { char ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("删除角色") },
            text = { Text("确定删除「${char.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        CharacterImageStore.delete(context, char.image)
                        container.characterRepository.removeCustom(char.id)
                        if (activeCharacter == char.id) {
                            container.settingsRepository.setActiveCharacter(Characters.DEFAULT_CHARACTER_ID)
                        }
                    }
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}
