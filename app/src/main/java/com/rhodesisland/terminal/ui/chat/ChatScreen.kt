package com.rhodesisland.terminal.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.ui.text.style.TextOverflow
import com.rhodesisland.terminal.data.model.Conversation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.model.AttachedFile
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.DisplayMessage
import com.rhodesisland.terminal.data.model.MessageSegment
import com.rhodesisland.terminal.data.repository.ChatBackgroundConfig
import com.rhodesisland.terminal.perfmon.PerformanceGlassOverlay
import com.rhodesisland.terminal.ui.characters.OperatorPortrait
import com.rhodesisland.terminal.ui.theme.PrtsColors
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

// ===== 设计 Token：现代 IM 风圆角 / 尺寸系统（替换散落的硬编码 4dp）=====
private val BubbleRadius = 16.dp
private val BubbleTailRadius = 4.dp
private val InputRadius = 24.dp
private val CardRadius = 12.dp
private val ThumbRadius = 8.dp
private val AiAvatarSize = 36.dp
private val UserAvatarSize = 32.dp

@Composable
fun ChatScreen(
    container: AppContainer,
    onNavigateToCharacters: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as com.rhodesisland.terminal.RhodesApp
    val viewModel: ChatViewModel = viewModel(
        factory = viewModelFactory { initializer { ChatViewModel(app, container) } }
    )
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 点击消息列表/空白处收起输入法：监听 Initial 阶段按下事件但不消费，保留消息气泡复制/朗读等点击。
    val focusManager = LocalFocusManager.current

    // 自动滚动到底部：消息数量变化或最后一条内容增长（流式输出）时触发。
    // 仅在已贴近底部时跟随，避免流式输出时把用户向上翻看历史的操作强制拉回底部；
    // 用即时 scrollToItem 而非 animateScrollToItem：流式 ~30fps 重建会不断取消动画导致抖动。
    val lastContent = state.messages.lastOrNull()?.content
    LaunchedEffect(state.messages.size, lastContent) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        // 首帧 layoutInfo 为空（lastVisible==null）时默认滚动，确保进入聊天/收到首条消息时贴底
        if (lastVisible == null || lastVisible >= state.messages.size - 2) {
            listState.scrollToItem(state.messages.size - 1)
        }
    }

    // 图片/文件选择器：用 OpenDocument 系列并取得持久化 URI 权限，
    // 否则旋转/进程重建后 ViewModel 持有的 content URI 丢权限，发送时 SecurityException。
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.addImage(uri.toString())
        }
    }

    // 文件选择器（文档解析）
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val name = queryDisplayName(context, uri) ?: "附件"
            viewModel.addFile(uri.toString(), name)
        }
    }

    // ===== 性能监控浮窗：仅本地聊天界面显示（应用内液态玻璃，真折射）=====
    val isLocal = state.activeProvider == ChatProviderType.LOCAL
    val liquidGlassEnabled by container.settingsRepository.liquidGlass.collectAsState(initial = true)

    // 聊天背景：内置 PRTS 轮播 / 用户自定义图片（最多 20 张，见 ChatBackgroundRepository）。
    // bgConfig 驱动 effectiveUrls：启用自定义且非空 -> 自定义路径；否则内置。bgIndex 提到此处，
    // 供 ChatBackground 与性能浮窗镜像源共用同一张图（浮窗 record 根 View 折射全部内容，自动同步）。
    val bgConfig by container.chatBackgroundRepository.config.collectAsState(
        initial = ChatBackgroundConfig(enabled = false, paths = emptyList())
    )
    val bgUrls = remember(bgConfig) { container.chatBackgroundRepository.effectiveUrls(bgConfig) }
    val bgCount = bgUrls.size
    var bgIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(bgCount) {
        if (bgCount <= 0) return@LaunchedEffect
        // 列表缩短时把索引夹回有效区间，避免越界
        if (bgIndex >= bgCount) bgIndex = 0
        while (true) {
            kotlinx.coroutines.delay(8000)
            bgIndex = (bgIndex + 1) % bgCount
        }
    }

    // 根 Box 不加 statusBars padding：状态栏区域显示 BgPrimary。
    // ChatBackground 与 Column 均各自加 statusBarsPadding -> 背景图顶部与顶部栏（本地/云端切换）对齐，
    // 不延伸到屏幕最顶部；可交互内容同样不被状态栏遮挡。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrtsColors.BgPrimary)
    ) {
        // 聊天背景轮播
        ChatBackground(urls = bgUrls, bgIndex = bgIndex)

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // ===== 顶部栏：角色信息 + 模型切换 + 会话入口（合并原 Header + Toolbar）=====
            ChatTopBar(
                name = state.characterName,
                role = state.characterRole,
                imageUrl = state.characterImage,
                activeProvider = state.activeProvider,
                ttsLanguage = state.ttsLanguage,
                conversationCount = state.conversations.size,
                deepThinkingEnabled = state.deepThinkingEnabled,
                onClickCharacter = onNavigateToCharacters,
                onSwitchProvider = { viewModel.switchProvider(it) },
                onToggleLang = { viewModel.toggleTtsLanguage() },
                onToggleDeepThinking = { viewModel.toggleDeepThinking() },
                onOpenConversations = { viewModel.toggleConversationSheet(true) },
            )

            // ===== 消息列表 =====
            Box(
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        // 在 Initial 阶段观察按下事件并清除焦点（收起键盘），但不消费事件，
                        // 故 LazyColumn 内消息气泡的复制/朗读等 clickable 仍正常响应。
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) {
                                    focusManager.clearFocus()
                                }
                            }
                        }
                    },
            ) {
                if (state.showWelcome) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "[罗德岛通讯 - 频道已开启]",
                                color = PrtsColors.GoldDim,
                                fontSize = 16.sp,
                                letterSpacing = 2.sp,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "选择角色，开始对话...",
                                color = PrtsColors.TextDim,
                                fontSize = 14.sp,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        items(state.messages, key = { it.id }) { msg ->
                            MessageBubble(
                                message = msg,
                                state = state,
                                characterImage = state.characterImage,
                                characterName = state.characterName,
                                onTts = { viewModel.playTts(msg) },
                            )
                        }
                        if (state.showTyping) {
                            item {
                                TypingIndicator(
                                    imageUrl = state.characterImage,
                                    name = state.characterName,
                                )
                            }
                        }
                    }
                }

                // 字幕条：TTS 朗读时显示译文/原文；角色切换时短暂显示角色台词
                val ttsActive = state.ttsLoadingIndex >= 0 || state.ttsPlayingIndex >= 0
                val showTtsSub = ttsActive && (state.ttsSubtitleCn.isNotBlank() || state.ttsSubtitleJp.isNotBlank())
                val showSwitchSub = state.showSwitchSubtitle && (state.subtitleCn.isNotBlank() || state.subtitleJp.isNotBlank())
                if (showTtsSub || showSwitchSub) {
                    SubtitleBar(
                        jp = if (showTtsSub) state.ttsSubtitleJp else state.subtitleJp,
                        cn = if (showTtsSub) state.ttsSubtitleCn else state.subtitleCn,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp),
                    )
                }
            }

            // ===== 错误提示 =====
            state.errorMessage?.let { error ->
                Snackbar(
                    modifier = Modifier.padding(8.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("关闭", color = PrtsColors.Gold)
                        }
                    }
                ) { Text(error, color = PrtsColors.DangerBright) }
            }

            // ===== 输入区 =====
            ChatInputBar(
                text = state.inputText,
                isStreaming = state.isStreaming,
                images = state.uploadedImages,
                files = state.uploadedFiles,
                onTextChange = { viewModel.updateInputText(it) },
                onSend = { viewModel.sendMessage() },
                onPickImage = { imagePicker.launch(arrayOf("image/*")) },
                onPickFile = { filePicker.launch(arrayOf("*/*")) },
                onRemoveImage = { viewModel.removeImage(it) },
                onRemoveFile = { viewModel.removeFile(it) },
            )
        }

        // ===== 性能监控浮窗（应用内液态玻璃，仅本地）=====
        if (isLocal) {
            PerformanceGlassOverlay(
                container = container,
                liquidGlassEnabled = liquidGlassEnabled,
            )
        }

        // ===== 会话管理抽屉 =====
        if (state.showConversationSheet) {
            ConversationSheet(
                conversations = state.conversations,
                activeConversationId = state.activeConversationId,
                onNew = { viewModel.newConversation() },
                onSwitch = { viewModel.switchConversation(it) },
                onRename = { id, title -> viewModel.renameConversation(id, title) },
                onDelete = { viewModel.deleteConversation(it) },
                onDismiss = { viewModel.toggleConversationSheet(false) },
            )
        }
    }
}

/**
 * 聊天背景轮播：每 8 秒切换一张背景图（内置 PRTS 或用户自定义），Crossfade 过渡 + 深色 scrim 保证内容可读。
 */
@Composable
private fun ChatBackground(urls: List<String>, bgIndex: Int) {
    if (urls.isEmpty()) return
    val url = urls.getOrNull(bgIndex) ?: urls.first()
    // 与顶部栏（本地/云端切换）对齐：背景从状态栏下方开始，不延伸到屏幕最顶部（状态栏区域显示 BgPrimary）。
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Crossfade(targetState = url, animationSpec = tween(1500), label = "bgCrossfade") { current ->
            // 自定义背景是内部存储绝对路径（"/data/..."），Coil 对无 scheme 路径解析不可靠 -> 显式转 File；
            // 内置背景是 "file:///android_asset/..." 字符串，原样传入。
            val model: Any = if (current.startsWith("/")) File(current) else current
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        // 深色 scrim：压暗背景，保证消息气泡可读
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PrtsColors.BgPrimary.copy(alpha = 0.8f))
        )
    }
}

/**
 * 紧凑顶栏：圆形头像 + 角色名/角色（点击进角色页）+ 模型分段切换 + 语言 + 会话入口。
 * 取代原 120dp 角色大卡片 + 工具栏两层结构，释放消息区垂直空间。
 */
@Composable
private fun ChatTopBar(
    name: String,
    role: String,
    imageUrl: String,
    activeProvider: ChatProviderType,
    ttsLanguage: com.rhodesisland.terminal.data.model.TtsLanguage,
    conversationCount: Int,
    deepThinkingEnabled: Boolean,
    onClickCharacter: () -> Unit,
    onSwitchProvider: (ChatProviderType) -> Unit,
    onToggleLang: () -> Unit,
    onToggleDeepThinking: () -> Unit,
    onOpenConversations: () -> Unit,
) {
    Surface(color = PrtsColors.BgSecondary.copy(alpha = 0.92f)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 头像 + 角色信息（点击进角色页）
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(CardRadius))
                        .clickable(onClick = onClickCharacter)
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OperatorPortrait(
                        imageUrl = imageUrl,
                        name = name,
                        modifier = Modifier
                            .size(AiAvatarSize)
                            .clip(CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            name.ifBlank { "未选择干员" },
                            color = PrtsColors.GoldBright,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            role.ifBlank { "罗德岛 · 干员" },
                            color = PrtsColors.TextDim,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // 模型分段切换（云端 / 本地）
                Surface(
                    shape = RoundedCornerShape(50),
                    color = PrtsColors.BgInput,
                ) {
                    Row {
                        ChatProviderType.values().forEach { type ->
                            val selected = activeProvider == type
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (selected) PrtsColors.Gold.copy(alpha = 0.22f) else Color.Transparent)
                                    .clickable { onSwitchProvider(type) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${type.icon} ${if (type == ChatProviderType.CLOUD) "云端" else "本地"}",
                                    fontSize = 10.sp,
                                    color = if (selected) PrtsColors.GoldBright else PrtsColors.TextDim,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                // 语言切换
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(PrtsColors.BgInput)
                        .clickable(onClick = onToggleLang),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ttsLanguage.displayChar, color = PrtsColors.GoldDim, fontSize = 13.sp)
                }

                Spacer(Modifier.width(6.dp))

                // 深度思考开关（开启时金色高亮）
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (deepThinkingEnabled) PrtsColors.Gold.copy(alpha = 0.22f) else PrtsColors.BgInput)
                        .clickable(onClick = onToggleDeepThinking),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Psychology,
                        contentDescription = "深度思考",
                        tint = if (deepThinkingEnabled) PrtsColors.GoldBright else PrtsColors.TextDim,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Spacer(Modifier.width(6.dp))

                // 会话入口（带未读角标显示会话数）
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(PrtsColors.BgInput)
                        .clickable(onClick = onOpenConversations),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Chat,
                        contentDescription = "会话记录",
                        tint = PrtsColors.Gold,
                        modifier = Modifier.size(18.dp),
                    )
                    if (conversationCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(14.dp)
                                .background(PrtsColors.Gold, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (conversationCount > 9) "9+" else conversationCount.toString(),
                                color = PrtsColors.BgPrimary,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            // 底部金色分隔线（扫描线感）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(PrtsColors.Gold.copy(alpha = 0.25f))
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: DisplayMessage,
    state: ChatUiState,
    characterImage: String,
    characterName: String,
    onTts: () -> Unit,
) {
    val isUser = message.role == "user"
    // 复制反馈：复制后短暂显示「✓ 已复制」
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { kotlinx.coroutines.delay(1200); copied = false }
    }
    // 气泡配色：用户金色半透明 + 亮金边；AI 深底 + 金边
    val bubbleColor = if (isUser) PrtsColors.Gold.copy(alpha = 0.16f) else PrtsColors.BgTertiary.copy(alpha = 0.92f)
    val borderColor = if (isUser) PrtsColors.GoldBright.copy(alpha = 0.5f) else PrtsColors.Gold.copy(alpha = 0.3f)
    // 不对称圆角：尾尖指向头像侧
    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = BubbleRadius, topEnd = BubbleRadius, bottomStart = BubbleRadius, bottomEnd = BubbleTailRadius)
    } else {
        RoundedCornerShape(topStart = BubbleRadius, topEnd = BubbleRadius, bottomStart = BubbleTailRadius, bottomEnd = BubbleRadius)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.78f
        Row(
            modifier = Modifier.align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart),
            verticalAlignment = Alignment.Top,
        ) {
            // AI 头像（左侧）
            if (!isUser) {
                OperatorPortrait(
                    imageUrl = characterImage,
                    name = characterName,
                    modifier = Modifier.size(AiAvatarSize).clip(CircleShape),
                )
                Spacer(Modifier.width(8.dp))
            }

            Column(
                modifier = Modifier.widthIn(max = maxBubbleWidth),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            ) {
                // 发送者标签（整合到气泡上方，不再浮空）
                Text(
                    message.sender,
                    color = if (isUser) PrtsColors.AccentBlueDim else PrtsColors.GoldDim,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 3.dp, start = 4.dp, end = 4.dp),
                )
                // 气泡
                Surface(
                    color = bubbleColor,
                    shape = bubbleShape,
                    border = BorderStroke(1.dp, borderColor),
                    modifier = Modifier.widthIn(max = maxBubbleWidth),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        // 渲染分段
                        message.segments.forEach { seg ->
                            when (seg) {
                                is MessageSegment.Text -> {
                                    // 含 $ 的文本段用 KaTeX 渲染（行内 $...$ / 块级 $$...$$）；流式中保持纯文本
                                    if (!message.isStreaming && seg.content.contains('$')) {
                                        MathView(
                                            seg.content,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        )
                                    } else {
                                        Text(seg.content, color = PrtsColors.TextPrimary, fontSize = 14.sp, lineHeight = 22.sp)
                                    }
                                }
                                is MessageSegment.Code -> {
                                    CodeBlockView(seg)
                                }
                                is MessageSegment.Science -> {
                                    ScienceBlockView(seg)
                                }
                                is MessageSegment.Think -> {
                                    ThinkBlockView(seg)
                                }
                            }
                        }
                        // 附件（图片缩略图 / 文件标签）
                        if (message.images.isNotEmpty()) {
                            Row(
                                modifier = Modifier.padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                message.images.forEach { uri ->
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = null,
                                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(ThumbRadius)),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                            }
                        }
                        if (message.files.isNotEmpty()) {
                            Column(
                                modifier = Modifier.padding(top = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                message.files.forEach { file ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(PrtsColors.BgInput, RoundedCornerShape(ThumbRadius))
                                            .padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.Description,
                                            contentDescription = null,
                                            tint = PrtsColors.GoldDim,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            file.name,
                                            color = PrtsColors.TextSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                        // 流式光标（脉冲动画）
                        if (message.isStreaming) {
                            StreamingCursor()
                        }
                    }
                }
                // 操作胶囊：复制（输入/输出均可）/ 朗读（仅 AI 且 TTS 启用）。流式输出中不显示。
                if (!message.isStreaming) {
                    Row(
                        modifier = Modifier.padding(top = 3.dp, start = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 复制
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable {
                                    val text = messageCopyText(message)
                                    if (text.isNotBlank()) {
                                        clipboard.setText(AnnotatedString(text))
                                        copied = true
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "复制",
                                tint = if (copied) PrtsColors.Success else PrtsColors.GoldDim,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                if (copied) "✓ 已复制" else "复制",
                                color = if (copied) PrtsColors.Success else PrtsColors.GoldDim,
                                fontSize = 10.sp,
                            )
                        }
                        // 朗读（仅 AI 且 TTS 启用）
                        if (!isUser && state.ttsEnabled) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable(onClick = onTts)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.VolumeUp,
                                    contentDescription = "朗读",
                                    tint = PrtsColors.GoldDim,
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(Modifier.width(3.dp))
                                Text("朗读", color = PrtsColors.GoldDim, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }

            // 用户头像（右侧，D = Doctor）
            if (isUser) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    modifier = Modifier.size(UserAvatarSize),
                    shape = CircleShape,
                    color = PrtsColors.BgCard,
                    border = BorderStroke(1.dp, PrtsColors.Gold.copy(alpha = 0.6f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("D", color = PrtsColors.GoldBright, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockView(seg: MessageSegment.Code) {
    var folded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(copied) {
        if (copied) { kotlinx.coroutines.delay(1200); copied = false }
    }
    Surface(
        color = PrtsColors.CodeBg,
        shape = RoundedCornerShape(ThumbRadius),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    seg.language.uppercase().ifBlank { "CODE" },
                    color = Color(0xFF569CD6),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(seg.rawCode))
                        copied = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(if (copied) "✓ 已复制" else "📋 复制", color = if (copied) PrtsColors.Success else PrtsColors.TextDim, fontSize = 10.sp)
                }
                TextButton(
                    onClick = { folded = !folded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(if (folded) "▸ 展开" else "▾ 折叠", color = PrtsColors.TextDim, fontSize = 10.sp)
                }
            }
            if (!folded) {
                seg.lines.forEach { line ->
                    Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                        line.forEach { token ->
                            Text(token.text, color = Color(android.graphics.Color.parseColor(token.color)), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScienceBlockView(seg: MessageSegment.Science) {
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(copied) {
        if (copied) { kotlinx.coroutines.delay(1200); copied = false }
    }
    Surface(
        color = PrtsColors.ScienceBg,
        shape = RoundedCornerShape(ThumbRadius),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    seg.language.uppercase().ifBlank { "FORMULA" },
                    color = Color(0xFF4EC9B0),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(seg.rawCode))
                        copied = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(if (copied) "✓ 已复制" else "📋 复制", color = if (copied) PrtsColors.Success else PrtsColors.TextDim, fontSize = 10.sp)
                }
            }
            seg.lines.forEach { line ->
                Row {
                    line.forEach { token ->
                        val color = Color(android.graphics.Color.parseColor(token.color))
                        val baseline = when (token.format) {
                            "sub" -> androidx.compose.ui.text.style.BaselineShift.Subscript
                            "sup" -> androidx.compose.ui.text.style.BaselineShift.Superscript
                            else -> androidx.compose.ui.text.style.BaselineShift.None
                        }
                        Text(token.text, color = color, fontSize = 14.sp, style = TextStyle(baselineShift = baseline))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkBlockView(seg: MessageSegment.Think) {
    // 流式时默认展开（实时观察思考），完成后自动折叠；用户可随时切换。
    // 以 seg.streaming 为 key：思考段闭合（streaming=false）时重置为折叠态。
    var folded by remember(seg.streaming) { mutableStateOf(!seg.streaming) }
    Surface(
        color = PrtsColors.CodeBg,
        shape = RoundedCornerShape(ThumbRadius),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (seg.streaming) "💭 思考中…" else "💭 思考过程",
                    color = PrtsColors.GoldDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { folded = !folded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(if (folded) "▸ 展开" else "▾ 折叠", color = PrtsColors.TextDim, fontSize = 10.sp)
                }
            }
            if (!folded && seg.content.isNotEmpty()) {
                Text(
                    seg.content,
                    color = PrtsColors.TextDim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SubtitleBar(jp: String, cn: String, modifier: Modifier = Modifier) {
    Surface(
        color = PrtsColors.BgTertiary.copy(alpha = 0.92f),
        shape = RoundedCornerShape(CardRadius),
        border = BorderStroke(1.dp, PrtsColors.Gold.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (jp.isNotBlank()) {
                Text(jp, color = PrtsColors.GoldBright, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (cn.isNotBlank()) {
                Text(cn, color = PrtsColors.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun TypingIndicator(imageUrl: String, name: String) {
    val infiniteTransition = rememberInfiniteTransition()
    Row(verticalAlignment = Alignment.Top) {
        // AI 头像，与消息气泡一致
        OperatorPortrait(
            imageUrl = imageUrl,
            name = name,
            modifier = Modifier.size(AiAvatarSize).clip(CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            color = PrtsColors.BgTertiary.copy(alpha = 0.92f),
            shape = RoundedCornerShape(
                topStart = BubbleRadius, topEnd = BubbleRadius,
                bottomStart = BubbleTailRadius, bottomEnd = BubbleRadius,
            ),
            border = BorderStroke(1.dp, PrtsColors.Gold.copy(alpha = 0.3f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) { i ->
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.15f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(400, delayMillis = i * 150, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(PrtsColors.GoldDim.copy(alpha = alpha), CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingCursor() {
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    Text("|", color = PrtsColors.Gold.copy(alpha = alpha), fontSize = 14.sp)
}

@Composable
private fun ChatInputBar(
    text: String,
    isStreaming: Boolean,
    images: List<String>,
    files: List<AttachedFile>,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onRemoveFile: (Int) -> Unit,
) {
    Surface(color = PrtsColors.BgSecondary) {
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            // 顶部金色分隔线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(PrtsColors.Gold.copy(alpha = 0.25f))
            )
            // 附件预览
            if (images.isNotEmpty() || files.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (images.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            images.forEachIndexed { idx, uri ->
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(ThumbRadius))
                                        .background(PrtsColors.BgInput),
                                ) {
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                    IconButton(
                                        onClick = { onRemoveImage(idx) },
                                        modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "移除",
                                            tint = PrtsColors.DangerBright,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    files.forEachIndexed { idx, file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PrtsColors.BgInput, RoundedCornerShape(ThumbRadius))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Description,
                                contentDescription = null,
                                tint = PrtsColors.GoldDim,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                file.name,
                                color = PrtsColors.TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            IconButton(
                                onClick = { onRemoveFile(idx) },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除",
                                    tint = PrtsColors.DangerBright,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }

            // 胶囊输入行：[+图片][📎文件] 输入框 [圆形发送]
            Row(
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(InputRadius))
                    .background(PrtsColors.BgInput)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clickable(onClick = onPickImage),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加图片", tint = PrtsColors.GoldDim, modifier = Modifier.size(20.dp))
                }
                Box(
                    modifier = Modifier.size(40.dp).clickable(onClick = onPickFile),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "添加文件", tint = PrtsColors.GoldDim, modifier = Modifier.size(20.dp))
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    textStyle = TextStyle(color = PrtsColors.TextPrimary, fontSize = 14.sp, lineHeight = 20.sp),
                    singleLine = false,
                    maxLines = 4,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(PrtsColors.Gold),
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text("输入消息...", color = PrtsColors.TextDim, fontSize = 14.sp)
                        }
                        inner()
                    },
                )
                // 圆形发送按钮
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isStreaming) PrtsColors.BgTertiary else PrtsColors.Gold)
                        .clickable(enabled = !isStreaming, onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "发送",
                        tint = if (isStreaming) PrtsColors.TextDim else PrtsColors.BgPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** 从 content URI 查询显示文件名 */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}

/** 时间戳格式化为「MM-dd HH:mm」 */
private fun formatConversationTime(ts: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

/**
 * 提取消息可复制纯文本：拼接 Text / Code / Science 段，跳过 Think（思考过程不作为正文复制）。
 * 结果为空时回退到原始 [DisplayMessage.content]。
 */
private fun messageCopyText(message: DisplayMessage): String {
    val built = buildString {
        message.segments.forEach { seg ->
            when (seg) {
                is MessageSegment.Text -> append(seg.content)
                is MessageSegment.Code -> {
                    if (isNotEmpty()) append("\n")
                    append(seg.rawCode)
                    append("\n")
                }
                is MessageSegment.Science -> {
                    if (isNotEmpty()) append("\n")
                    append(seg.rawCode)
                    append("\n")
                }
                is MessageSegment.Think -> { /* 跳过思考过程 */ }
            }
        }
    }.trim()
    return built.ifBlank { message.content }
}

/**
 * 会话管理抽屉：列出当前角色的全部会话，可新建 / 切换 / 重命名 / 删除。
 * 当前活跃会话高亮；重命名与删除带二次确认弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSheet(
    conversations: List<Conversation>,
    activeConversationId: Long?,
    onNew: () -> Unit,
    onSwitch: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Conversation?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PrtsColors.BgSecondary,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "对话记录",
                    color = PrtsColors.GoldBright,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onNew) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = PrtsColors.Gold, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建对话", color = PrtsColors.Gold, fontSize = 12.sp)
                }
            }
            if (conversations.isEmpty()) {
                Text(
                    "暂无对话，点右上角「新建对话」开始",
                    color = PrtsColors.TextDim,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 420.dp),
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        ConversationItem(
                            conversation = conv,
                            isActive = conv.id == activeConversationId,
                            onSwitch = { onSwitch(conv.id) },
                            onRename = {
                                renaming = conv
                                renameText = conv.title
                            },
                            onDelete = { deleting = conv },
                        )
                    }
                }
            }
        }
    }

    // 重命名弹窗
    renaming?.let { conv ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    textStyle = TextStyle(color = PrtsColors.TextPrimary, fontSize = 14.sp),
                    colors = TextFieldDefaults.colors(
                        cursorColor = PrtsColors.Gold,
                        focusedIndicatorColor = PrtsColors.Gold,
                        unfocusedIndicatorColor = PrtsColors.GoldDim,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(conv.id, renameText)
                    renaming = null
                }) { Text("确定", color = PrtsColors.Gold) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消", color = PrtsColors.TextDim) }
            },
        )
    }

    // 删除确认弹窗
    deleting?.let { conv ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除对话") },
            text = { Text("确定删除「${conv.title.ifBlank { "新对话" }}」？该对话的全部消息将被清除。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(conv.id)
                    deleting = null
                }) { Text("删除", color = PrtsColors.DangerBright) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消", color = PrtsColors.TextDim) }
            },
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    isActive: Boolean,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = if (isActive) PrtsColors.Gold.copy(alpha = 0.12f) else PrtsColors.BgTertiary.copy(alpha = 0.6f),
        shape = RoundedCornerShape(CardRadius),
        border = if (isActive) BorderStroke(1.dp, PrtsColors.Gold.copy(alpha = 0.5f)) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSwitch() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    conversation.title.ifBlank { "新对话" },
                    color = if (isActive) PrtsColors.GoldBright else PrtsColors.TextPrimary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatConversationTime(conversation.updatedAt),
                    color = PrtsColors.TextDim,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = "重命名", tint = PrtsColors.GoldDim, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = PrtsColors.DangerBright, modifier = Modifier.size(18.dp))
            }
        }
    }
}
