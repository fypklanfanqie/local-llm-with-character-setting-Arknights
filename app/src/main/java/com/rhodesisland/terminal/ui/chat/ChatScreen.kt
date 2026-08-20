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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.os.Build
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rhodesisland.terminal.data.model.TtsLanguage
import coil.compose.AsyncImage
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.model.AttachedFile
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.Conversation
import com.rhodesisland.terminal.data.model.DisplayMessage
import com.rhodesisland.terminal.data.model.MessageCompletionState
import com.rhodesisland.terminal.conversationexport.ConversationExportDocument
import com.rhodesisland.terminal.conversationexport.ConversationExportWriter
import com.rhodesisland.terminal.conversationexport.ConversationImageLayout
import com.rhodesisland.terminal.conversationexport.ConversationImageMode
import com.rhodesisland.terminal.conversationexport.ConversationImageRenderer
import com.rhodesisland.terminal.conversationexport.ConversationTextExporter
import com.rhodesisland.terminal.conversationexport.suggestedExportBaseName
import com.rhodesisland.terminal.ui.affinity.GiftInventorySheet
import com.rhodesisland.terminal.affinity.GiftSendResult
import com.rhodesisland.terminal.affinity.OwnedGift
import com.rhodesisland.terminal.data.model.MessageSegment
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.data.repository.ChatBackgroundConfig
import com.rhodesisland.terminal.perfmon.PerformanceGlassOverlay
import com.rhodesisland.terminal.ui.glass.GlassSegmented
import com.rhodesisland.terminal.ui.navigation.ClampedImeBottomPadding
import com.rhodesisland.terminal.ui.video.BgmDuck
import com.rhodesisland.terminal.ui.video.LocalSeedancePlaybackController
import com.rhodesisland.terminal.ui.video.SEEDANCE_FULLSCREEN_PLAYER_TAG
import com.rhodesisland.terminal.ui.video.SeedancePlaybackController
import com.rhodesisland.terminal.ui.video.SeedanceVideoCard
import com.rhodesisland.terminal.ui.video.SeedanceVideoPlayer
import com.rhodesisland.terminal.ui.glass.LocalBackdropState
import com.rhodesisland.terminal.ui.glass.MonogramAvatar
import com.rhodesisland.terminal.ui.glass.frostedGlass
import com.rhodesisland.terminal.ui.glass.liquidGlass
import com.rhodesisland.terminal.ui.applySystemBarIcons
import com.rhodesisland.terminal.ui.theme.GlassShapes
import com.rhodesisland.terminal.ui.theme.LocalDarkTheme
import com.rhodesisland.terminal.video.SeedanceVideoExporter
import com.rhodesisland.terminal.video.VideoExportTarget
import com.rhodesisland.terminal.video.exportTargetForSdk
import com.rhodesisland.terminal.video.suggestedVideoFileName
import kotlinx.coroutines.launch
import java.io.File

// ===== 设计 Token：iMessage 风圆角 / 尺寸系统 =====
private val BubbleRadius = 18.dp
private val BubbleTailRadius = 5.dp
private val AiAvatarSize = 38.dp
private val UserAvatarSize = 30.dp
private val SuccessGreen = Color(0xFF34C759)

/**
 * 聊天界面玻璃方案 · C 实体卡片：高不透明度玻璃面，最大化文字可读性。
 *
 * 用户上传照片背景时 [frostedGlass] 的真实背板采样会被关闭（LocalBackdropState = null），
 * 玻璃退化为纯半透明色块--没有真实模糊可用，故照片上只能靠提高不透明度保证文字可读。
 * tint 主题感知：亮色用白底、暗色用深底，配 scheme.onSurface 文字在两种主题下都高对比
 * （旧实现暗色下也是白底 + 浅色 onSurface 文字，对比极低）。阴影统一 14dp，与底栏 dock 协调。
 */
private data class ChatGlassScheme(
    val aiTint: Color,
    val inputTint: Color,
    val topBarTint: Color,
    val chipTint: Color,
    val photoScrimBase: Float,
    val blur: Dp,
    val shadow: Dp,
)

private val ChatGlassSchemeLight = ChatGlassScheme(
    aiTint = Color.White.copy(alpha = 0.94f),
    inputTint = Color.White.copy(alpha = 0.92f),
    topBarTint = Color.White.copy(alpha = 0.92f),
    chipTint = Color.White.copy(alpha = 0.70f),
    photoScrimBase = 0.50f,
    blur = 8.dp,
    shadow = 14.dp,
)

private val ChatGlassSchemeDark = ChatGlassScheme(
    aiTint = Color(0xFF1E2029).copy(alpha = 0.94f),
    inputTint = Color(0xFF181A22).copy(alpha = 0.92f),
    topBarTint = Color(0xFF181A22).copy(alpha = 0.92f),
    chipTint = Color(0xFF181A22).copy(alpha = 0.70f),
    photoScrimBase = 0.70f,
    blur = 8.dp,
    shadow = 14.dp,
)

@Composable
private fun chatGlass(): ChatGlassScheme =
    if (LocalDarkTheme.current) ChatGlassSchemeDark else ChatGlassSchemeLight

@Composable
fun ChatScreen(
    container: AppContainer,
    bottomBarHeight: Dp = 0.dp,
    onBack: () -> Unit,
    onNavigateToCharacters: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as com.rhodesisland.terminal.RhodesApp
    val viewModel: ChatViewModel = viewModel(
        factory = viewModelFactory { initializer { ChatViewModel(app, container) } }
    )
    val state by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    // 自动滚动与「回到底部」逻辑已内聚到 ChatMessageList（像素级底部判定 + 用户接管策略）。

    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.addImage(uri.toString())
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val name = queryDisplayName(context, uri) ?: "附件"
            viewModel.addFile(uri.toString(), name)
        }
    }

    val isLocal = state.activeProvider == ChatProviderType.LOCAL
    val exportScope = rememberCoroutineScope()
    val conversationExportWriter = remember { ConversationExportWriter(context.applicationContext) }
    var pendingConversationExport by remember { mutableStateOf<ConversationExportDocument?>(null) }
    var pendingImageMode by remember { mutableStateOf<ConversationImageMode?>(null) }
    var exportBusy by remember { mutableStateOf(false) }
    var showExportConversationPicker by remember { mutableStateOf(false) }
    var showExportFormatPicker by remember { mutableStateOf(false) }
    var showExportImageModePicker by remember { mutableStateOf(false) }
    var showGiftSheet by remember { mutableStateOf(false) }
    var giftSendBusy by remember { mutableStateOf(false) }
    val ownedGifts by container.affinityRepository.observeOwnedGifts().collectAsState(initial = emptyList())

    fun sendGift(gift: OwnedGift) {
        val conversationId = state.activeConversationId ?: return
        if (giftSendBusy || state.isStreaming) return
        giftSendBusy = true
        exportScope.launch {
            when (val result = container.affinityRepository.sendGift(state.characterId, gift.definition.id, conversationId)) {
                is GiftSendResult.Sent -> {
                    viewModel.sendGiftThanks(result.history)
                    Toast.makeText(context, "已赠送 ${gift.definition.name}，好感度 +${result.history.affinityGain}", Toast.LENGTH_SHORT).show()
                    showGiftSheet = false
                }
                GiftSendResult.InventoryEmpty -> Toast.makeText(context, "该礼物库存不足", Toast.LENGTH_SHORT).show()
                GiftSendResult.GiftMissing -> Toast.makeText(context, "礼物已不存在", Toast.LENGTH_SHORT).show()
            }
            giftSendBusy = false
        }
    }

    fun exportTextTo(uri: Uri) {
        val document = pendingConversationExport ?: return
        pendingConversationExport = null
        exportBusy = true
        exportScope.launch {
            conversationExportWriter.writeText(uri, ConversationTextExporter.render(document))
                .onSuccess { Toast.makeText(context, "聊天记录已导出", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "导出失败：${it.message ?: "无法写入文件"}", Toast.LENGTH_SHORT).show() }
            exportBusy = false
        }
    }

    fun exportImageTo(uri: Uri) {
        val document = pendingConversationExport ?: return
        val mode = pendingImageMode ?: return
        pendingConversationExport = null
        pendingImageMode = null
        exportBusy = true
        exportScope.launch {
            runCatching { ConversationImageRenderer.render(ConversationImageLayout.plan(document, mode), context).single() }
                .onSuccess { png ->
                    conversationExportWriter.writePng(uri, png)
                        .onSuccess { Toast.makeText(context, "聊天记录图片已导出", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, "导出失败：${it.message ?: "无法写入图片"}", Toast.LENGTH_SHORT).show() }
                }
                .onFailure { Toast.makeText(context, it.message ?: "图片生成失败，请尝试分页导出或 TXT", Toast.LENGTH_SHORT).show() }
            exportBusy = false
        }
    }

    val exportTextLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> if (uri != null) exportTextTo(uri) else { pendingConversationExport = null } }
    val exportLongImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri -> if (uri != null) exportImageTo(uri) else { pendingConversationExport = null; pendingImageMode = null } }
    val exportPagedImagesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        val document = pendingConversationExport
        pendingConversationExport = null
        pendingImageMode = null
        if (treeUri != null && document != null) {
            exportBusy = true
            exportScope.launch {
                runCatching { ConversationImageRenderer.render(ConversationImageLayout.plan(document, ConversationImageMode.PAGINATED), context) }
                    .fold(
                        onSuccess = { pngs ->
                            conversationExportWriter.writePngPages(treeUri, suggestedExportBaseName(document.ownerName, document.title, document.exportedAt), pngs)
                                .onSuccess { count -> Toast.makeText(context, "已导出 $count 张聊天记录图片", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, "导出失败：${it.message ?: "无法写入图片"}", Toast.LENGTH_SHORT).show() }
                        },
                        onFailure = { Toast.makeText(context, it.message ?: "图片生成失败，请尝试 TXT", Toast.LENGTH_SHORT).show() },
                    )
                exportBusy = false
            }
        }
    }
    val liquidGlassEnabled by container.settingsRepository.liquidGlass.collectAsState(initial = true)

    val bgConfig by container.chatBackgroundRepository.config.collectAsState(
        initial = ChatBackgroundConfig(enabled = false, paths = emptyList())
    )
    val bgUrls = remember(bgConfig) { container.chatBackgroundRepository.effectiveUrls(bgConfig) }
    // PRTS 深色主题：聊天页始终深色（极光底或照片背景），系统状态栏/导航栏图标保持白色。
    applySystemBarIcons(light = true)
    val bgCount = bgUrls.size
    var bgIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(bgCount) {
        if (bgCount <= 0) return@LaunchedEffect
        if (bgIndex >= bgCount) bgIndex = 0
        while (true) {
            kotlinx.coroutines.delay(8000)
            bgIndex = (bgIndex + 1) % bgCount
        }
    }

    // ===== Seedance 视频播放 / 导出（Task 8）=====
    // 屏幕级唯一播放控制器：内联卡片与全屏共用同一 ExoPlayer，只播放本地归档文件；
    // 视频出声时暂停全局 BGM、退后台/销毁时暂停并释放（绝不复用 audioManager 的播放器实例）。
    val lifecycleOwner = LocalLifecycleOwner.current
    // TTS 抢占标记：视频出声时暂停应用内 TTS，视频释放后若此前正在播放则恢复（Task 8 音频互斥）。
    var ttsWasPlaying by remember { mutableStateOf(false) }
    val playbackController = remember {
        SeedancePlaybackController(
            context = context.applicationContext,
            bgm = object : BgmDuck {
                override fun isPlaying(): Boolean = container.audioManager.isPlaying
                override fun pause() { container.audioManager.pauseMusic() }
                override fun resume() { container.audioManager.playMusic() }
            },
            onAcquireAudio = {
                if (container.ttsManager.playing && !ttsWasPlaying) {
                    ttsWasPlaying = true
                    container.ttsManager.pause()
                }
            },
            onReleaseAudio = {
                if (ttsWasPlaying) {
                    ttsWasPlaying = false
                    container.ttsManager.resume()
                }
            },
            lifecycle = lifecycleOwner.lifecycle,
        )
    }
    DisposableEffect(Unit) {
        onDispose { playbackController.release() }
    }

    val exporter = remember { SeedanceVideoExporter(context.applicationContext) }
    val videoScope = rememberCoroutineScope()
    // Android 7–9 导出：SAF ACTION_CREATE_DOCUMENT 由用户选择保存位置后流式写入内部文件。
    var pendingExport by remember { mutableStateOf<Pair<SeedanceVideo, String>?>(null) }
    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/*")
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (uri != null && pending != null) {
            val (video, _) = pending
            videoScope.launch {
                exporter.exportToUri(video, uri).onSuccess {
                    Toast.makeText(context, "视频已保存到所选位置", Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Toast.makeText(context, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    // 内联播放开关（卡片「播放」）：同一视频再次点击暂停，其他视频切换加载；全屏由卡片 onFullScreen 接管。
    val handleVideoPlay: (SeedanceVideo) -> Unit = { video ->
        val path = video.localVideoPath
        if (path.isNullOrBlank()) {
            Toast.makeText(context, "视频文件尚未就绪", Toast.LENGTH_SHORT).show()
        } else {
            playbackController.toggle(File(path))
        }
    }
    // 全屏预览（卡片「全屏」）：确保该视频已加载播放后开启全屏，内联表面让出，仅全屏表面持有播放器。
    val handleVideoFullScreen: (SeedanceVideo) -> Unit = { video ->
        val path = video.localVideoPath
        if (path.isNullOrBlank()) {
            Toast.makeText(context, "视频文件尚未就绪", Toast.LENGTH_SHORT).show()
        } else {
            playbackController.play(File(path))
            playbackController.setFullScreen(true)
        }
    }
    // 保存到本地（卡片「保存到本地」）：Android 10+ 写 MediaStore 相册；7–9 弹 SAF 选择器后流式写入。
    val handleVideoExport: (SeedanceVideo) -> Unit = { video ->
        if (video.localVideoPath.isNullOrBlank()) {
            Toast.makeText(context, "视频文件尚未就绪", Toast.LENGTH_SHORT).show()
        } else {
            when (exportTargetForSdk(Build.VERSION.SDK_INT)) {
                VideoExportTarget.MediaStoreMovies -> videoScope.launch {
                    exporter.exportToMediaStore(video).onSuccess {
                        Toast.makeText(context, "视频已保存到相册 Movies/RhodesIslandTerminal", Toast.LENGTH_SHORT).show()
                    }.onFailure { e ->
                        Toast.makeText(context, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                VideoExportTarget.CreateDocument -> {
                    pendingExport = video to suggestedVideoFileName(video)
                    createDocumentLauncher.launch(suggestedVideoFileName(video))
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        ChatBackground(urls = bgUrls, bgIndex = bgIndex)

        // 开照片背景时背板仍只有极光，玻璃面板采样会出现「玻璃里是 mesh、外面是照片」的错位，
        // 这里对整页玻璃回退半透明叠层，保持视觉一致。
        val chatBackdrop = if (bgUrls.isNotEmpty()) null else LocalBackdropState.current
        CompositionLocalProvider(LocalBackdropState provides chatBackdrop) {
        // statusBarsPadding 下移避开状态栏，再补 20dp：顶栏与状态栏留出 ~26dp 呼吸空间，
        // 不再「贴」在背景图片顶部（符合现代 AI 聊天应用的沉浸式间距）。
        // 底部预留 bottomBarHeight（dock 高度，含导航栏 inset）+ IME 钳制：无键盘时交互内容
        // 止于 dock 之上，键盘弹出时随键盘上移；背景层（ChatBackground）独立铺满整屏不被裁。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 20.dp, bottom = bottomBarHeight)
                .then(ClampedImeBottomPadding(WindowInsets.ime, PaddingValues(bottom = bottomBarHeight))),
        ) {
            ChatTopBar(
                name = state.characterName,
                role = state.characterRole,
                imageUrl = state.characterImage,
                activeProvider = state.activeProvider,
                specialEventActive = state.activeSpecialEventId != null,
                ttsLanguage = state.ttsLanguage,
                conversationCount = state.conversations.size,
                deepThinkingEnabled = state.deepThinkingEnabled,
                videoAutoEnabled = state.activeConversationAutoVideoEnabled,
                videoToggleDisabled = isLocal,
                onBack = onBack,
                onClickCharacter = onNavigateToCharacters,
                onSwitchProvider = { viewModel.switchProvider(it) },
                onToggleLang = { viewModel.toggleTtsLanguage() },
                onToggleDeepThinking = { viewModel.toggleDeepThinking() },
                onToggleVideoAuto = {
                    val convId = state.activeConversationId
                    if (convId != null) {
                        viewModel.setAutoVideoEnabled(convId, !state.activeConversationAutoVideoEnabled)
                    }
                },
                onOpenConversations = { viewModel.toggleConversationSheet(true) },
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
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
                    WelcomeState(
                        name = state.characterName,
                        role = state.characterRole,
                        imageUrl = state.characterImage,
                        onSuggest = { text ->
                            viewModel.updateInputText(text)
                            viewModel.sendMessage()
                        },
                    )
                } else {
                    // 屏幕级播放控制器注入：卡片经 CompositionLocal 判定活动内联表面并驱动全屏。
                    CompositionLocalProvider(
                        LocalSeedancePlaybackController provides playbackController,
                    ) {
                        ChatMessageList(
                            state = state,
                            onTts = { viewModel.playTts(it) },
                            onDelete = { viewModel.deleteMessage(it.databaseId) },
                            modifier = Modifier.fillMaxSize(),
                            // Task 8：内联播放开关 / 全屏 / 保存到本地；Task 7 接取消/重试。
                            onPlayVideo = { video -> handleVideoPlay(video) },
                            onFullScreenVideo = { video -> handleVideoFullScreen(video) },
                            onExportVideo = { video -> handleVideoExport(video) },
                            onCancelVideo = { viewModel.cancelVideoTask(it.id) },
                            onRetryVideo = { viewModel.retryVideoTask(it.id) },
                        )
                    }
                }

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

            state.errorMessage?.let { error ->
                Snackbar(
                    modifier = Modifier.padding(8.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) { Text("关闭") }
                    }
                ) { Text(error) }
            }

            ChatInputBar(
                text = state.inputText,
                isStreaming = state.isStreaming,
                stopRequested = state.stopRequested,
                images = state.uploadedImages,
                files = state.uploadedFiles,
                onTextChange = { viewModel.updateInputText(it) },
                onSend = { viewModel.sendMessage() },
                onStop = { viewModel.stopGeneration() },
                onPickImage = { imagePicker.launch(arrayOf("image/*")) },
                onPickFile = { filePicker.launch(arrayOf("*/*")) },
                onOpenGifts = { showGiftSheet = true },
                onRemoveImage = { viewModel.removeImage(it) },
                onRemoveFile = { viewModel.removeFile(it) },
            )
        }
        }

        if (isLocal) {
            PerformanceGlassOverlay(
                container = container,
                liquidGlassEnabled = liquidGlassEnabled,
            )
        }

        if (showGiftSheet) {
            GiftInventorySheet(
                gifts = ownedGifts,
                onSend = ::sendGift,
                onPickAttachment = { imagePicker.launch(arrayOf("image/*")) },
                onDismiss = { if (!giftSendBusy) showGiftSheet = false },
            )
        }

        if (state.showConversationSheet) {
            ConversationSheet(
                conversations = state.conversations,
                activeConversationId = state.activeConversationId,
                onNew = { viewModel.newConversation() },
                onSwitch = { viewModel.switchConversation(it) },
                onRename = { id, title -> viewModel.renameConversation(id, title) },
                onDelete = { viewModel.deleteConversation(it) },
                onStartExport = { showExportConversationPicker = true },
                exportEnabled = !exportBusy,
                onDismiss = { viewModel.toggleConversationSheet(false) },
            )
        }

        if (showExportConversationPicker) {
            ConversationExportSelectionDialog(
                conversations = state.conversations,
                activeConversationId = state.activeConversationId,
                onSelect = { conversationId ->
                    showExportConversationPicker = false
                    exportBusy = true
                    viewModel.prepareConversationExport(
                        conversationId,
                        // 导出包含当前正在轮播显示的聊天背景（自定义图片或内置 assets）。
                        backgroundPath = bgUrls.getOrNull(bgIndex) ?: bgUrls.firstOrNull().orEmpty(),
                        onReady = { document ->
                            pendingConversationExport = document
                            exportBusy = false
                            showExportFormatPicker = true
                        },
                        onError = { message ->
                            exportBusy = false
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        },
                    )
                },
                onDismiss = { showExportConversationPicker = false },
            )
        }

        if (showExportFormatPicker) {
            ConversationExportFormatDialog(
                onText = {
                    val document = pendingConversationExport ?: return@ConversationExportFormatDialog
                    showExportFormatPicker = false
                    exportTextLauncher.launch("${suggestedExportBaseName(document.ownerName, document.title, document.exportedAt)}.txt")
                },
                onImage = {
                    showExportFormatPicker = false
                    showExportImageModePicker = true
                },
                onDismiss = {
                    showExportFormatPicker = false
                    pendingConversationExport = null
                },
            )
        }

        if (showExportImageModePicker) {
            ConversationExportImageModeDialog(
                onPaged = {
                    showExportImageModePicker = false
                    pendingImageMode = ConversationImageMode.PAGINATED
                    exportPagedImagesLauncher.launch(null)
                },
                onLong = {
                    val document = pendingConversationExport ?: return@ConversationExportImageModeDialog
                    val error = runCatching { ConversationImageLayout.plan(document, ConversationImageMode.LONG_IMAGE) }.exceptionOrNull()
                    if (error != null) {
                        Toast.makeText(context, error.message ?: "图片生成失败，请尝试分页导出或 TXT", Toast.LENGTH_LONG).show()
                        return@ConversationExportImageModeDialog
                    }
                    showExportImageModePicker = false
                    pendingImageMode = ConversationImageMode.LONG_IMAGE
                    exportLongImageLauncher.launch("${suggestedExportBaseName(document.ownerName, document.title, document.exportedAt)}.png")
                },
                onDismiss = {
                    showExportImageModePicker = false
                    pendingConversationExport = null
                },
            )
        }

        // Seedance 全屏预览（Task 8）：全屏 Dialog，与内联卡片共用同一播放器；
        // 打开时内联表面让出，同一时刻仅一个活动表面；关闭即暂停。
        val fullScreenOpen by playbackController.fullScreen.collectAsState()
        if (fullScreenOpen) {
            SeedanceFullScreenPlayer(
                player = playbackController.player,
                onClose = {
                    playbackController.setFullScreen(false)
                    playbackController.pause()
                },
            )
        }
    }
}

/** 聊天头像：有立绘用图，否则 monogram 渐变首字。群聊逐条复用（internal）。 */
@Composable
internal fun ChatAvatar(
    imageUrl: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = AiAvatarSize,
) {
    if (imageUrl.isNotBlank()) {
        AsyncImage(
            model = imageUrl,
            contentDescription = name,
            modifier = modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        MonogramAvatar(text = name, modifier = modifier, size = size)
    }
}

/**
 * 聊天背景轮播：每 8 秒切换一张背景图，Crossfade 过渡 + scrim 保证内容可读。
 * 背景铺满整屏：通讯 Tab 已在全屏层（不预留底栏），背景自然延伸到状态栏 / 浮动 dock /
 * 系统导航栏背后、直达屏幕最底部（dock 作为浮层叠在最上），不再露出浅色极光底。
 * 无背景图时不绘制，透出根 MeshBackground 的玻璃底。
 */
@Composable
private fun ChatBackground(urls: List<String>, bgIndex: Int) {
    if (urls.isEmpty()) return
    val url = urls.getOrNull(bgIndex) ?: urls.first()
    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(targetState = url, animationSpec = tween(1500), label = "bgCrossfade") { current ->
            val model: Any = if (current.startsWith("/")) File(current) else current
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        // 纵向渐变 scrim：顶部加深保证白色状态栏图标可读，底部加深让 dock / 输入区与背景融合；
        // 中段按 ChatGlassScheme.photoScrimBase 压暗（方案 C：亮 0.50 / 暗 0.70），繁忙照片上也保证文字可读。
        val base = chatGlass().photoScrimBase
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = (base + 0.26f).coerceAtMost(0.85f)),
                            Color.Black.copy(alpha = base),
                            Color.Black.copy(alpha = base),
                            Color.Black.copy(alpha = (base + 0.16f).coerceAtMost(0.85f)),
                        ),
                    )
                )
        )
    }
}

/**
 * 欢迎态：大头像 + 角色名/定位 + 简介 + 推荐话题药丸（点击即发送）。
 */
@Composable
private fun WelcomeState(
    name: String,
    role: String,
    imageUrl: String,
    onSuggest: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val glass = chatGlass()
    val suggestions = remember { listOf("和我打个招呼", "今天过得怎么样", "讲个故事给我听") }
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ChatAvatar(
                imageUrl = imageUrl,
                name = name,
                size = 96.dp,
                modifier = Modifier
                    .shadow(
                        24.dp, RoundedCornerShape(32.dp), clip = false,
                        ambientColor = Color(0xFFC44CE0).copy(alpha = 0.30f),
                        spotColor = Color(0xFF7C5CFF).copy(alpha = 0.45f),
                    )
                    .clip(RoundedCornerShape(32.dp)),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                name.ifBlank { "未选择角色" },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onBackground,
            )
            if (role.isNotBlank()) {
                Text(role, color = scheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (name.isBlank()) "去角色页选择一位，开始对话吧" else "开始和 $name 对话吧",
                color = scheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                suggestions.forEach { s ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .liquidGlass(
                                GlassShapes.pill,
                                shadowElevation = 6.dp,
                                blurRadius = glass.blur,
                                fillBrush = Brush.linearGradient(
                                    listOf(glass.inputTint, glass.chipTint),
                                ),
                            )
                            .clickable { onSuggest(s) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(s, color = scheme.onSurface, fontSize = 12.5.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * 玻璃顶栏：头像+在线点+角色名/定位 + 云端/本地分段 + 思考/会话/更多图标。
 */
@Composable
private fun ChatTopBar(
    name: String,
    role: String,
    imageUrl: String,
    activeProvider: ChatProviderType,
    specialEventActive: Boolean,
    ttsLanguage: com.rhodesisland.terminal.data.model.TtsLanguage,
    conversationCount: Int,
    deepThinkingEnabled: Boolean,
    videoAutoEnabled: Boolean,
    videoToggleDisabled: Boolean,
    onBack: () -> Unit,
    onClickCharacter: () -> Unit,
    onSwitchProvider: (ChatProviderType) -> Unit,
    onToggleLang: () -> Unit,
    onToggleDeepThinking: () -> Unit,
    onToggleVideoAuto: () -> Unit,
    onOpenConversations: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val glass = chatGlass()
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .liquidGlass(GlassShapes.cardSmall, shadowElevation = glass.shadow, tint = glass.topBarTint, blurRadius = glass.blur)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 第一行：返回 + 头像/角色名 + 会话记录（角色信息占满剩余空间）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconBubble(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
            )
            Spacer(Modifier.width(2.dp))

            // 头像 + 角色信息（点击进角色页）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(GlassShapes.cardSmall)
                    .clickable(onClick = onClickCharacter)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    ChatAvatar(imageUrl = imageUrl, name = name, size = AiAvatarSize)
                    // 在线点
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(SuccessGreen)
                            .border(BorderStroke(2.dp, scheme.surface), CircleShape),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        name.ifBlank { "未选择角色" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        role.ifBlank { "角色" },
                        color = scheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 会话记录
            IconBubble(
                icon = Icons.AutoMirrored.Outlined.Chat,
                contentDescription = "会话记录",
                badge = conversationCount.takeIf { it > 0 },
                onClick = onOpenConversations,
            )
        }

        // 第二行：云端/本地 + 深度思考 + 视频 + 中英文切换。独占整行、窄屏可横向滑动，
        // 保证最右侧语言按钮（中/日）始终可见，不再被同行头像/分段挤到屏外。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassSegmented(
                options = if (specialEventActive) {
                    listOf(ChatProviderType.CLOUD to "☁ 云端")
                } else {
                    ChatProviderType.values().map {
                        it to "${it.icon} ${if (it == ChatProviderType.CLOUD) "云端" else "本地"}"
                    }
                },
                selected = activeProvider,
                onSelect = onSwitchProvider,
            )

            IconBubble(
                icon = Icons.Outlined.Psychology,
                contentDescription = "深度思考",
                highlighted = deepThinkingEnabled,
                onClick = onToggleDeepThinking,
            )
            // Seedance 自动视频开关（Task 7）：特殊邂逅与本地 Provider 下禁用；群聊入口本身不创建视频任务。
            IconBubble(
                icon = Icons.Outlined.Videocam,
                contentDescription = when {
                    specialEventActive -> "特殊邂逅中不可生成视频"
                    videoToggleDisabled -> "自动视频：仅云端可用"
                    else -> "自动视频"
                },
                highlighted = videoAutoEnabled && !videoToggleDisabled && !specialEventActive,
                onClick = {
                    when {
                        specialEventActive -> Toast.makeText(context, "特殊邂逅中不可生成视频", Toast.LENGTH_SHORT).show()
                        videoToggleDisabled -> Toast.makeText(context, "自动视频仅云端可用", Toast.LENGTH_SHORT).show()
                        else -> onToggleVideoAuto()
                    }
                },
            )
            LangBubble(
                lang = ttsLanguage,
                contentDescription = "语音语言：${ttsLanguage.label}",
                onClick = {
                    val next = if (ttsLanguage == TtsLanguage.ZH) TtsLanguage.JA else TtsLanguage.ZH
                    onToggleLang()
                    Toast.makeText(context, "语音语言已切换至${next.label}", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

/** 圆形玻璃图标按钮，可选高亮 / 数字角标。 */
@Composable
private fun IconBubble(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    badge: Int? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .then(
                if (highlighted) Modifier.background(scheme.primary.copy(alpha = 0.16f))
                else Modifier.frostedGlass(CircleShape, shadowElevation = 4.dp, borderWidth = 1.dp, blurRadius = 16.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (highlighted) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$badge",
                    color = scheme.onPrimary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 圆形玻璃文字按钮：顶栏 TTS 语言切换，显示当前语言字符（中/日），点击切换并 toast 提示。 */
@Composable
private fun LangBubble(
    lang: TtsLanguage,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(scheme.primary.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            lang.displayChar,
            color = scheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun MessageBubble(
    message: DisplayMessage,
    state: ChatUiState,
    characterImage: String,
    characterName: String,
    /** 博士头像（设置「我的形象」）；空串回落 monogram「我」。 */
    userImage: String = "",
    onTts: () -> Unit,
    onDelete: () -> Unit = {},
    onPlayVideo: (() -> Unit)? = null,
    onFullScreenVideo: (() -> Unit)? = null,
    onExportVideo: (() -> Unit)? = null,
    onCancelVideo: (() -> Unit)? = null,
    onRetryVideo: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val glass = chatGlass()
    val isUser = message.role == "user"
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { kotlinx.coroutines.delay(1200); copied = false }
    }
    // 气泡：用户=紫罗兰实心 + 紫辉光（iOS 用户气泡）；AI=磨砂玻璃（采样模糊背板）
    val bubbleShape: Shape = if (isUser) {
        RoundedCornerShape(topStart = BubbleRadius, topEnd = BubbleRadius, bottomStart = BubbleRadius, bottomEnd = BubbleTailRadius)
    } else {
        RoundedCornerShape(topStart = BubbleRadius, topEnd = BubbleRadius, bottomStart = BubbleTailRadius, bottomEnd = BubbleRadius)
    }
    val bubbleContentColor = if (isUser) scheme.onPrimary else scheme.onSurface

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.82f
        Row(
            modifier = Modifier.align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart),
            verticalAlignment = Alignment.Top,
        ) {
            if (!isUser) {
                ChatAvatar(imageUrl = characterImage, name = characterName, size = AiAvatarSize)
                Spacer(Modifier.width(8.dp))
            }

            Column(
                modifier = Modifier.widthIn(max = maxBubbleWidth),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = maxBubbleWidth)
                        .then(
                            if (isUser) Modifier
                                .shadow(
                                    14.dp, bubbleShape, clip = false,
                                    ambientColor = Color(0xFF6E4DFF).copy(alpha = 0.15f),
                                    spotColor = Color(0xFF7C5CFF).copy(alpha = 0.35f),
                                )
                                .background(scheme.primary, bubbleShape)
                            else Modifier.frostedGlass(
                                bubbleShape,
                                tint = glass.aiTint,
                                borderWidth = 1.dp,
                                blurRadius = glass.blur,
                                shadowElevation = glass.shadow,
                            )
                        ),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        message.segments.forEach { seg ->
                            when (seg) {
                                is MessageSegment.Text -> {
                                    if (!message.isStreaming && seg.content.contains('$')) {
                                        MathView(
                                            seg.content,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        )
                                    } else {
                                        Text(
                                            seg.content,
                                            color = bubbleContentColor,
                                            fontSize = 14.5.sp,
                                            lineHeight = 21.sp,
                                        )
                                    }
                                }
                                is MessageSegment.Code -> CodeBlockView(seg)
                                is MessageSegment.Science -> ScienceBlockView(seg)
                                is MessageSegment.Think -> ThinkBlockView(seg)
                            }
                        }
                        if (message.images.isNotEmpty()) {
                            Row(
                                modifier = Modifier.padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                message.images.forEach { uri ->
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = null,
                                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
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
                                            .background(scheme.surface.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Outlined.Description, contentDescription = null, tint = bubbleContentColor.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                                        Text(
                                            file.name,
                                            color = bubbleContentColor.copy(alpha = 0.85f),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                        if (message.isStreaming) {
                            StreamingCursor(color = bubbleContentColor)
                        }
                        // Task 6/7：停止状态 badge（独立于 content/modelContent；仅非流式展示）。
                        // 复制、TTS 与后续模型上下文均通过 content/modelContent 取文本，不含此标记。
                        if (!message.isStreaming && message.completionState != MessageCompletionState.COMPLETE) {
                            Text(
                                text = when (message.completionState) {
                                    MessageCompletionState.STOPPED_PARTIAL -> "已停止（已保留部分输出）"
                                    MessageCompletionState.STOPPED_BEFORE_FINAL -> "已停止（尚未生成最终答案）"
                                    MessageCompletionState.COMPLETE -> ""
                                },
                                color = bubbleContentColor.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                // Seedance 视频卡（Task 7）：紧贴助手气泡下方渲染；播放/导出由 Task 8 注入。
                message.video?.let { video ->
                    SeedanceVideoCard(
                        video = video,
                        onPlay = onPlayVideo,
                        onFullScreen = onFullScreenVideo,
                        onExport = onExportVideo,
                        onCancel = onCancelVideo,
                        onRetry = onRetryVideo,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                // 操作胶囊：复制 / 朗读 / 重生成（仅 AI 且非流式）
                if (!message.isStreaming) {
                    Row(
                        modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActionChip(
                            icon = Icons.Outlined.ContentCopy,
                            label = if (copied) "已复制" else "复制",
                            done = copied,
                            onClick = {
                                val text = messageCopyText(message)
                                if (text.isNotBlank()) {
                                    clipboard.setText(AnnotatedString(text))
                                    copied = true
                                }
                            },
                        )
                        if (!isUser && state.ttsEnabled) {
                            ActionChip(
                                icon = Icons.AutoMirrored.Outlined.VolumeUp,
                                label = "朗读",
                                onClick = onTts,
                            )
                        }
                        // 删除单条消息（用户问题 / 助手回答）：仅持久消息提供入口（数据库Id非空）。
                        if (message.databaseId != null) {
                            ActionChip(
                                icon = Icons.Outlined.Delete,
                                label = "删除",
                                onClick = onDelete,
                            )
                        }
                    }
                }
            }

            if (isUser) {
                Spacer(Modifier.width(8.dp))
                // 博士头像（设置「我的形象」）；未设置时 ChatAvatar 自动回落 monogram「我」
                ChatAvatar(imageUrl = userImage, name = "我", size = UserAvatarSize)
            }
        }
    }
}

/** 操作胶囊：复制 / 朗读 / 重生成。 */
@Composable
private fun ActionChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    done: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val glass = chatGlass()
    Row(
        modifier = modifier
            .frostedGlass(GlassShapes.pill, tint = glass.chipTint, borderWidth = 1.dp, shadowElevation = 1.dp, blurRadius = glass.blur)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (done) SuccessGreen else scheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            label,
            color = if (done) SuccessGreen else scheme.onSurfaceVariant,
            fontSize = 10.5.sp,
        )
    }
}

@Composable
private fun CodeBlockView(seg: MessageSegment.Code) {
    val dark = LocalDarkTheme.current
    var folded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(copied) {
        if (copied) { kotlinx.coroutines.delay(1200); copied = false }
    }
    Surface(
        color = if (dark) Color(0xFF0C0E14) else Color(0xFF1E1E2E),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    seg.language.uppercase().ifBlank { "CODE" },
                    color = Color(0xFF9aa5ce),
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
                    Text(if (copied) "✓ 已复制" else "复制", color = if (copied) SuccessGreen else Color(0xFF9aa5ce), fontSize = 10.sp)
                }
                TextButton(
                    onClick = { folded = !folded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(if (folded) "展开" else "折叠", color = Color(0xFF9aa5ce), fontSize = 10.sp)
                }
            }
            if (!folded) {
                seg.lines.forEach { line ->
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 0.dp)) {
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
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(12.dp),
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
                    Text(if (copied) "✓ 已复制" else "复制", color = if (copied) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
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
    val scheme = MaterialTheme.colorScheme
    var folded by remember(seg.streaming) { mutableStateOf(!seg.streaming) }
    Surface(
        color = scheme.primary.copy(alpha = 0.10f),
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.22f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .clickable { folded = !folded }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (seg.streaming) "思考中…" else "思考过程",
                    color = scheme.primary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(if (folded) "▸" else "▾", color = scheme.primary, fontSize = 12.sp)
            }
            if (!folded && seg.content.isNotEmpty()) {
                Text(
                    seg.content,
                    color = scheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SubtitleBar(jp: String, cn: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (LocalDarkTheme.current) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.75f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.25f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (jp.isNotBlank()) {
                Text(jp, color = scheme.onSurface, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
            }
            if (cn.isNotBlank()) {
                Text(cn, color = scheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
internal fun TypingIndicator(imageUrl: String, name: String) {
    val glass = chatGlass()
    Row(verticalAlignment = Alignment.Top) {
        ChatAvatar(imageUrl = imageUrl, name = name, size = AiAvatarSize)
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.frostedGlass(
                RoundedCornerShape(
                    topStart = BubbleRadius, topEnd = BubbleRadius,
                    bottomStart = BubbleTailRadius, bottomEnd = BubbleRadius,
                ),
                tint = glass.aiTint,
                borderWidth = 1.dp,
                blurRadius = glass.blur,
                shadowElevation = glass.shadow,
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) { i ->
                    val alpha by rememberInfiniteTransition().animateFloat(
                        initialValue = 0.2f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(400, delayMillis = i * 150, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "typing$i",
                    )
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingCursor(color: Color) {
    val alpha by rememberInfiniteTransition().animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursor",
    )
    Text("|", color = color.copy(alpha = alpha), fontSize = 14.sp)
}

@Composable
internal fun ChatInputBar(
    text: String,
    isStreaming: Boolean,
    stopRequested: Boolean,
    images: List<String>,
    files: List<AttachedFile>,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onOpenGifts: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onRemoveFile: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val glass = chatGlass()
    // 导航栏 inset 已由外层交互 Column 的 bottom = bottomBarHeight（含导航栏 inset）+ IME 钳制
    // 统一预留，此处不再重复加 windowInsetsPadding(navigationBars)，避免输入行被多顶一个导航栏高度。
    Column {
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
                                    .clip(RoundedCornerShape(12.dp)),
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
                                    Icon(Icons.Outlined.Close, contentDescription = "移除", tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
                files.forEachIndexed { idx, file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .frostedGlass(GlassShapes.cardSmall, shadowElevation = 0.dp)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Description, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Text(
                            file.name,
                            color = scheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        IconButton(onClick = { onRemoveFile(idx) }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = "移除", tint = scheme.error, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        // 胶囊输入行：[+图片][📎文件] 输入框 [圆形发送]
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
                .liquidGlass(
                    RoundedCornerShape(26.dp),
                    shadowElevation = glass.shadow,
                    tint = glass.inputTint,
                    blurRadius = glass.blur,
                    fillBrush = Brush.linearGradient(
                        listOf(glass.inputTint, glass.chipTint),
                    ),
                )
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier.size(40.dp).clickable(onClick = onOpenGifts),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.CardGiftcard, contentDescription = "赠送礼物", tint = scheme.primary, modifier = Modifier.size(20.dp))
            }
            Box(
                modifier = Modifier.size(40.dp).clickable(onClick = onPickImage),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "添加图片", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Box(
                modifier = Modifier.size(40.dp).clickable(onClick = onPickFile),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AttachFile, contentDescription = "添加文件", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
                textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.5.sp, lineHeight = 20.sp),
                singleLine = false,
                maxLines = 4,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text("输入消息…", color = scheme.onSurfaceVariant, fontSize = 14.5.sp)
                    }
                    inner()
                },
            )
            // 发送 / 停止按钮（Task 7）：生成中切换为「停止」，stopRequested 时显示「正在停止」并禁用重复点击。
            // 视觉圆形保持 36dp（与原有发送按钮一致，未改变触摸目标尺寸）。
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .shadow(
                        10.dp, CircleShape, clip = false,
                        ambientColor = Color(0xFF6E4DFF).copy(alpha = 0.20f),
                        spotColor = Color(0xFF7C5CFF).copy(alpha = 0.45f),
                    )
                    .clip(CircleShape)
                    .background(
                        when {
                            stopRequested -> scheme.error.copy(alpha = 0.85f)
                            isStreaming -> scheme.error
                            else -> scheme.primary
                        }
                    )
                    .semantics {
                        contentDescription = when {
                            stopRequested -> "正在停止"
                            isStreaming -> "停止生成"
                            else -> "发送"
                        }
                    }
                    .clickable(
                        enabled = !(isStreaming && stopRequested),
                        onClick = { if (isStreaming) onStop() else onSend() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isStreaming) {
                    // 停止图标：实心方框（生成中显示；stopRequested 时点击已禁用但仍保持可视状态）。
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(scheme.onError, RoundedCornerShape(2.dp)),
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "发送",
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

/** 从 content URI 查询显示文件名 */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
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
 * 提取消息可复制纯文本：拼接 Text / Code / Science 段，跳过 Think。
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
 * 会话管理抽屉：玻璃底部弹层 + 遮罩，列出当前角色的全部会话，可新建 / 切换 / 重命名 / 删除。
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
    onStartExport: () -> Unit,
    exportEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Conversation?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        shape = GlassShapes.sheet,
        dragHandle = { GlassDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .frostedGlass(GlassShapes.sheet, tint = scheme.surfaceContainerHigh.copy(alpha = 0.95f))
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("对话记录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onStartExport, enabled = exportEnabled) {
                        Icon(Icons.Outlined.Description, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (exportEnabled) "导出记录" else "导出中…", color = scheme.primary)
                    }
                    TextButton(onClick = onNew, enabled = exportEnabled) {
                        Icon(Icons.Outlined.Add, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建对话", color = scheme.primary)
                    }
                }
            }
            if (conversations.isEmpty()) {
                Text(
                    "暂无对话，点右上角「新建对话」开始",
                    color = scheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
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

    renaming?.let { conv ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = scheme.surfaceContainerHigh,
            title = { Text("重命名对话", color = scheme.onSurface) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.sp),
                    colors = TextFieldDefaults.colors(
                        cursorColor = scheme.primary,
                        focusedIndicatorColor = scheme.primary,
                        unfocusedIndicatorColor = scheme.outline,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(conv.id, renameText)
                    renaming = null
                }) { Text("确定", color = scheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消", color = scheme.onSurfaceVariant) }
            },
        )
    }

    deleting?.let { conv ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = scheme.surfaceContainerHigh,
            title = { Text("删除对话", color = scheme.onSurface) },
            text = { Text("确定删除「${conv.title.ifBlank { "新对话" }}」？该对话的全部消息将被清除。", color = scheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(conv.id)
                    deleting = null
                }) { Text("删除", color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消", color = scheme.onSurfaceVariant) }
            },
        )
    }
}

@Composable
private fun GlassDragHandle() {
    Box(
        modifier = Modifier
            .padding(vertical = 10.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {}
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    isActive: Boolean,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (isActive) scheme.primary.copy(alpha = 0.14f) else Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSwitch() }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    conversation.title.ifBlank { "新对话" },
                    color = if (isActive) scheme.primary else scheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatConversationTime(conversation.updatedAt),
                    color = scheme.onSurfaceVariant,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = onRename, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "重命名", tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = scheme.error, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Seedance 全屏预览（Task 8）：全屏 [Dialog] 内挂载 [SeedanceVideoPlayer]，
 * 与内联卡片共用同一 [SeedancePlaybackController] 的播放器——全屏开启时内联表面让出，
 * 仅全屏表面挂载播放器，保证同一时刻至多一个活动表面。关闭即暂停并让出音频。
 */
@Composable
private fun SeedanceFullScreenPlayer(
    player: androidx.media3.common.Player,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            SeedanceVideoPlayer(
                player = player,
                showControls = true,
                testTag = SEEDANCE_FULLSCREEN_PLAYER_TAG,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(40.dp),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭全屏", tint = Color.White)
            }
        }
    }
}
