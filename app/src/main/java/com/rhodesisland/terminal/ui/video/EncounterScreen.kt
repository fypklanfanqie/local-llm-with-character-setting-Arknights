package com.rhodesisland.terminal.ui.video

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.RhodesApp
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.data.model.SeedanceVideoState
import com.rhodesisland.terminal.ui.applySystemBarIcons
import com.rhodesisland.terminal.video.SeedanceVideoExporter
import com.rhodesisland.terminal.video.VideoExportTarget
import com.rhodesisland.terminal.video.exportTargetForSdk
import com.rhodesisland.terminal.video.suggestedVideoFileName
import kotlinx.coroutines.launch
import java.io.File

/**
 * 「邂逅」沉浸式历史流（Task 9）。
 *
 * 全屏 [VerticalPager] 竖滑浏览全部 Seedance 视频任务（createdAt DESC，最新在前）。
 * 屏幕级唯一播放控制器（[SeedancePlaybackController]，与 ChatScreen 相互独立）：
 * 落定到 READY 视频自动播放该视频的本地归档文件；落定到非 READY / 页面切走 / 退后台
 * 立即暂停；[DisposableEffect] 在离开本屏时释放。同一时刻至多一个 [ExoPlayer] 且至多
 * 一个 PlayerView 表面（仅落定页挂载，见 [EncounterVideoPage]）。
 *
 * 导出复用 Task 8 的 [SeedanceVideoExporter]：Android 10+ 写 MediaStore 相册，
 * Android 7–9 走 SAF [android.app.Activity] ACTION_CREATE_DOCUMENT（用户选择位置）。
 * 重试/取消动作委托 [EncounterViewModel]，不在此复制流水线逻辑。
 */
@Composable
fun EncounterScreen(
    container: AppContainer,
    bottomBarHeight: Dp = 0.dp,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as RhodesApp
    val viewModel: EncounterViewModel = viewModel(
        factory = viewModelFactory { initializer { EncounterViewModel(app, container) } }
    )
    val videos by viewModel.videos.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 屏幕级唯一播放控制器：与 ChatScreen 各自持有实例，互不干扰（每屏一个 ExoPlayer）。
    val playbackController = remember {
        SeedancePlaybackController(
            context = context.applicationContext,
            lifecycle = lifecycleOwner.lifecycle,
        )
    }
    DisposableEffect(Unit) {
        onDispose { playbackController.release() }
    }

    // 列表清空时：pager 被空状态替换，其 LaunchedEffect 未 settle 到 null，
    // 若不显式暂停，控制器会在空状态下继续出声，直到本屏销毁。
    LaunchedEffect(videos.isEmpty()) {
        if (videos.isEmpty()) playbackController.pause()
    }

    // ===== 导出（Task 8 复用）=====
    val exporter = remember { SeedanceVideoExporter(context.applicationContext) }
    val videoScope = rememberCoroutineScope()
    // Android 7–9 导出：SAF ACTION_CREATE_DOCUMENT 由用户选择保存位置后流式写入内部文件。
    var pendingExport by remember { mutableStateOf<SeedanceVideo?>(null) }
    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/*")
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (uri != null && pending != null) {
            videoScope.launch {
                exporter.exportToUri(pending, uri).onSuccess {
                    Toast.makeText(context, "视频已保存到所选位置", Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Toast.makeText(context, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val handleExport: (SeedanceVideo) -> Unit = { video ->
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
                    pendingExport = video
                    createDocumentLauncher.launch(suggestedVideoFileName(video))
                }
            }
        }
    }

    // 沉浸深色背景：系统状态栏 / 导航栏图标改为白色。
    applySystemBarIcons(light = true)

    var detailsVideo by remember { mutableStateOf<SeedanceVideo?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (videos.isEmpty()) {
            EncounterEmptyState()
        } else {
            EncounterVideoPager(
                videos = videos,
                playbackController = playbackController,
                onOpenDetails = { detailsVideo = it },
                onExport = { handleExport(it) },
                onCancel = { viewModel.cancel(it) },
                onRetry = { viewModel.retry(it) },
                onTogglePlay = { video ->
                    // 播放/暂停切换：仅 READY 且有本地归档时有效（按钮只在挂载播放器时渲染）。
                    video.localVideoPath?.let { path -> playbackController.toggle(File(path)) }
                },
                bottomBarHeight = bottomBarHeight,
                modifier = Modifier.fillMaxSize(),
            )
        }

        EncounterTopBar(onBack = onBack)
    }

    detailsVideo?.let { video ->
        EncounterDetailsDialog(
            video = video,
            onExport = { handleExport(video) },
            onCancel = { viewModel.cancel(video.id) },
            onRetry = { viewModel.retry(video.id) },
            onContinueQuery = { viewModel.continueQuery(video.id) },
            onRetryDownload = { viewModel.retryDownload(video.id) },
            onDismiss = { detailsVideo = null },
        )
    }
}

/**
 * 全屏竖滑历史流核心（Task 9）。
 *
 * 抽出为独立 internal composable 供 [EncounterScreen] 与 instrumentation 测试共同使用：
 * 含「落定页 READY 才挂载播放器」门控与「落定页自动播放 / 切走暂停」语义。真实播放器实例
 * 由调用方注入，测试可在真机/模拟器上验证「同一时刻至多一个 PlayerView 表面、前页暂停」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EncounterVideoPager(
    videos: List<SeedanceVideo>,
    playbackController: SeedancePlaybackController,
    onOpenDetails: (SeedanceVideo) -> Unit,
    onExport: (SeedanceVideo) -> Unit,
    onCancel: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    bottomBarHeight: Dp = 0.dp,
    modifier: Modifier = Modifier,
    onTogglePlay: (SeedanceVideo) -> Unit = {},
) {
    val pagerState = rememberPagerState(pageCount = { videos.size })
    val settledPage = pagerState.settledPage
    val settledVideo = videos.getOrNull(settledPage)

    // 落定页播放门控：READY 视频自动播放本地归档文件；落定到非 READY（或离开列表）立即暂停。
    // 切到另一 READY 页时 play() 会切换媒体并接管播放，前页表面因 `settledPage == page` 失效而让出。
    LaunchedEffect(settledPage, settledVideo?.id, settledVideo?.state, settledVideo?.localVideoPath) {
        settleEncounterPlayback(settledVideo, playbackController)
    }

    // 列表收缩 clamp（任务被清理后）
    LaunchedEffect(videos.size) {
        if (videos.isNotEmpty() && pagerState.currentPage >= videos.size) {
            pagerState.scrollToPage(videos.size - 1)
        }
    }

    VerticalPager(
        state = pagerState,
        beyondBoundsPageCount = 1,
        key = { i -> videos.getOrNull(i)?.id ?: i },
        modifier = modifier,
    ) { page ->
        val video = videos.getOrNull(page) ?: return@VerticalPager
        // 与 settleEncounterPlayback 一致：READY + 非空本地归档路径才挂载，避免黑屏表面。
        val isActive = settledPage == page && video.state == SeedanceVideoState.READY && !video.localVideoPath.isNullOrBlank()
        EncounterVideoPage(
            video = video,
            settled = settledPage == page,
            // 仅落定页 READY 挂载播放器：其它页 player 传 null（表面让出）。
            player = if (isActive) playbackController.player else null,
            onOpenDetails = { onOpenDetails(video) },
            onExport = { onExport(video) },
            onCancel = { onCancel(video.id) },
            onRetry = { onRetry(video.id) },
            onTogglePlay = { onTogglePlay(video) },
            bottomBarHeight = bottomBarHeight,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 顶栏：返回 + 「邂逅」标题（沉浸覆盖，避开状态栏，顶部渐变压暗保证可读）。 */
@Composable
private fun EncounterTopBar(onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f),
                    1f to Color.Transparent,
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
            )
        }
        Text(
            text = "邂逅",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** 空状态：暂无任何视频故事。 */
@Composable
internal fun EncounterEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF22223A), Color(0xFF0C0C14)))
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Videocam,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = "还没有视频故事",
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "开启角色会话的自动视频后，生成的视频会出现在这里",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }
}

/** 任务详情全屏弹层（Task 9）。
 *
 * 展示完整会话快照（用户/助手原文）、最终提示词、生成参数（模型/分辨率/画幅/时长/音频/水印）、
 * 错误阶段/错误码/错误信息，以及导出 / 取消 / 重试 / 继续查询 / 重新下载动作。
 * 费用性重试（FAILED_REMOTE / EXPIRED / 歧义 FAILED_SUBMISSION）先弹确认对话框。
 */
@Composable
internal fun EncounterDetailsDialog(
    video: SeedanceVideo,
    onExport: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onContinueQuery: (() -> Unit)?,
    onRetryDownload: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var confirmRegenerate by remember(video.id, video.state) { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xF2121419))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "任务详情",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))

            DetailSection(title = "角色") {
                DetailText(video.characterNameSnapshot)
                video.characterRoleSnapshot.takeIf { it.isNotBlank() }?.let { DetailText(it, alpha = 0.6f) }
            }
            DetailSection(title = "用户") {
                DetailText(video.userTextSnapshot.ifBlank { "—" })
            }
            DetailSection(title = "助手") {
                DetailText(video.assistantTextSnapshot.ifBlank { "—" })
            }
            video.finalPrompt?.takeIf { it.isNotBlank() }?.let {
                DetailSection(title = "最终提示词") { DetailText(it) }
            }
            DetailSection(title = "生成参数") {
                ParamRow("模型", video.modelVariant.modelId)
                ParamRow("分辨率", video.resolution.storageKey)
                ParamRow("画幅", video.ratio.storageKey)
                ParamRow("时长", "${video.durationSeconds} 秒")
                ParamRow("音频", if (video.generateAudio) "开启" else "关闭")
                ParamRow("水印", if (video.watermark) "开启" else "关闭")
            }
            if (video.errorStage != null || video.errorCode != null || video.errorMessage != null) {
                DetailSection(title = "错误信息") {
                    video.errorStage?.takeIf { it.isNotBlank() }?.let { ParamRow("阶段", it) }
                    video.errorCode?.takeIf { it.isNotBlank() }?.let { ParamRow("错误码", it) }
                    video.errorMessage?.takeIf { it.isNotBlank() }?.let { ParamRow("信息", it) }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (video.state == SeedanceVideoState.READY && onExport != null) {
                    EncounterActionButton("保存到本地", onExport)
                }
                when {
                    video.state == SeedanceVideoState.QUEUED && onCancel != null ->
                        EncounterActionButton("取消", onCancel)
                    video.state == SeedanceVideoState.FAILED_QUERY && onContinueQuery != null ->
                        EncounterActionButton("继续查询", onContinueQuery)
                    video.state == SeedanceVideoState.FAILED_DOWNLOAD && onRetryDownload != null ->
                        EncounterActionButton("重新下载", onRetryDownload)
                    video.state in genericRetryStates && onRetry != null ->
                        EncounterActionButton(retryLabel(video.state), onClick = {
                            if (isCostBearingRetry(video)) confirmRegenerate = true else onRetry()
                        })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("重新生成视频") },
            text = { Text("该操作可能产生费用，确认重新生成？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRegenerate = false
                    onRetry?.invoke()
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) { Text("取消") }
            },
        )
    }
}

/** 详情弹层分区：标题 + 半透明圆角卡片。 */
@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Surface(
            color = Color.White.copy(alpha = 0.08f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) { content() }
        }
    }
}

/** 详情弹层正文文本（白字，可换行）。 */
@Composable
private fun DetailText(text: String, alpha: Float = 0.92f) {
    Text(text, color = Color.White.copy(alpha = alpha), fontSize = 13.sp)
}

/** 详情弹层参数行：标签 + 值。 */
@Composable
private fun ParamRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 详情弹层动作按钮（白字半透明白底胶囊）。 */
@Composable
private fun EncounterActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
