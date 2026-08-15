package com.rhodesisland.terminal.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.data.model.SeedanceVideoState
import kotlinx.coroutines.delay
import java.io.File
import java.util.Date
import java.util.Locale

/** 邂逅全屏播放器表面 testTag（instrumentation 断言「仅落定页挂载播放器」）。 */
const val SEEDANCE_ENCOUNTER_PLAYER_TAG = "seedance_encounter_player"

/** 邂逅页「播放/暂停」按钮 testTag。 */
const val SEEDANCE_ENCOUNTER_TOGGLE_TAG = "seedance_encounter_toggle"

/** 需要通用「重试」动作的状态（继续查询/重新下载有独立动作，不在此列）。 */
internal val genericRetryStates = setOf(
    SeedanceVideoState.FAILED_SNAPSHOT,
    SeedanceVideoState.FAILED_PROMPT,
    SeedanceVideoState.FAILED_PROMPT_CONFIG_CHANGED,
    SeedanceVideoState.FAILED_SUBMISSION,
    SeedanceVideoState.FAILED_REMOTE,
    SeedanceVideoState.EXPIRED,
)

/**
 * 邂逅单页布局（重设计版）。
 *
 * 全屏一页：READY 落定页挂载无系统控制条的播放器表面（[SEEDANCE_ENCOUNTER_PLAYER_TAG]），
 * 其余页用角色参考快照 + 渐变压暗作背景。底部为**单张有界玻璃故事卡**（角色名+状态胶囊+日期 /
 * 播放进度 / 会话摘要 / 提示词摘要 / 动作行），结构固定、逐行 maxLines 限高——
 * 修掉旧版「无约束信息层顶穿顶栏、与播放器控制条和浮动 dock 三重重叠」的问题。
 *
 * 播放/暂停由自绘迷你控制（[SEEDANCE_ENCOUNTER_TOGGLE_TAG] + 细进度条）完成，
 * 回调由 EncounterScreen 注入（驱动 [SeedancePlaybackController]）。
 *
 * 文本契约与旧版一致（角色名 / “用户原文” / 助手原文 / 「提示词：…」/ 状态文案 /
 * 「取消」「继续查询」「保存到本地」「详情」等按钮文案），instrumentation 测试不受影响。
 */
@Composable
fun EncounterVideoPage(
    video: SeedanceVideo,
    settled: Boolean,
    player: Player?,
    onOpenDetails: () -> Unit,
    onExport: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    bottomBarHeight: Dp = 0.dp,
    modifier: Modifier = Modifier,
    onTogglePlay: (() -> Unit)? = null,
) {
    // 仅落定页的 READY + 非空归档路径视频挂载播放器表面（与 settleEncounterPlayback 门控一致，
    // 避免 READY 但缺文件时挂载一个播放空黑的 PlayerView）。
    val attached = settled && video.state == SeedanceVideoState.READY &&
        !video.localVideoPath.isNullOrBlank() && player != null

    // 播放状态与进度：500ms 轮询读 player（无系统控制条，进度自绘）。
    var playing by remember(video.id) { mutableStateOf(false) }
    var positionMs by remember(video.id) { mutableLongStateOf(0L) }
    var durationMs by remember(video.id) { mutableLongStateOf(0L) }
    LaunchedEffect(attached, player) {
        if (attached && player != null) {
            while (true) {
                playing = player.isPlaying
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.coerceAtLeast(0L)
                delay(500)
            }
        } else {
            playing = false
            positionMs = 0L
            durationMs = 0L
        }
    }

    var confirmRegenerate by remember(video.id, video.state) { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (attached) {
            SeedanceVideoPlayer(
                player = player,
                showControls = false, // 不用系统控制条，避免与底部故事卡重叠
                testTag = SEEDANCE_ENCOUNTER_PLAYER_TAG,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            EncounterBackdrop(video)
        }

        EncounterStoryCard(
            video = video,
            attached = attached,
            playing = playing,
            positionMs = positionMs,
            durationMs = durationMs,
            onTogglePlay = if (attached) onTogglePlay else null,
            onOpenDetails = onOpenDetails,
            onExport = onExport,
            onCancel = onCancel,
            onRetry = {
                if (isCostBearingRetry(video)) confirmRegenerate = true else onRetry?.invoke()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp + bottomBarHeight),
        )
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

/**
 * 底部玻璃故事卡：有界结构（角色名+状态胶囊 / 日期 / 进度 / 摘要 / 动作），
 * 深色半透明底 + 圆角 22dp，任意背景图上恒可读。
 */
@Composable
private fun EncounterStoryCard(
    video: SeedanceVideo,
    attached: Boolean,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlay: (() -> Unit)?,
    onOpenDetails: () -> Unit,
    onExport: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xE613151B))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        // 角色名 + 状态胶囊
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = video.characterNameSnapshot.ifBlank { "角色" },
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StatusPill(stateText(video), statusPillColor(video))
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = formatVideoTimestamp(video.createdAt),
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
        )

        // 播放进度（仅落定 READY 挂载播放器时）
        if (attached && durationMs > 0) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatVideoTime(positionMs),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                )
                LinearProgressIndicator(
                    progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFF8AB4F8),
                    trackColor = Color.White.copy(alpha = 0.18f),
                )
                Text(
                    text = formatVideoTime(durationMs),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                )
            }
        }

        // 会话摘要（快照持久，聊天删除仍可见；逐行限高，绝不撑破卡片）
        val hasSummary = video.userTextSnapshot.isNotBlank() || video.assistantTextSnapshot.isNotBlank()
        if (hasSummary || video.finalPrompt?.isNotBlank() == true) Spacer(Modifier.height(10.dp))
        video.userTextSnapshot.takeIf { it.isNotBlank() }?.let { user ->
            Text(
                text = "“$user”",
                color = Color.White.copy(alpha = 0.95f),
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        video.assistantTextSnapshot.takeIf { it.isNotBlank() }?.let { assistant ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = assistant,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        video.finalPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = "提示词：$prompt",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(12.dp))

        // 动作行：播放/暂停 + 状态动作 + 详情
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onTogglePlay != null) {
                RoundGlassIconButton(
                    onClick = onTogglePlay,
                    testTag = SEEDANCE_ENCOUNTER_TOGGLE_TAG,
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            when {
                video.state == SeedanceVideoState.READY && onExport != null ->
                    EncounterPageChip("保存到本地", onExport)
                video.state == SeedanceVideoState.QUEUED && onCancel != null ->
                    EncounterPageChip("取消", onCancel)
                video.state == SeedanceVideoState.FAILED_QUERY && onRetry != null ->
                    EncounterPageChip("继续查询", onRetry)
                video.state == SeedanceVideoState.FAILED_DOWNLOAD && onRetry != null ->
                    EncounterPageChip("重新下载", onRetry)
                video.state in genericRetryStates && onRetry != null ->
                    EncounterPageChip(retryLabel(video.state), onRetry)
            }
            Spacer(Modifier.weight(1f))
            EncounterPageChip("详情", onOpenDetails)
        }
    }
}

/** 状态胶囊：小圆角底色 + 白字状态文案。 */
@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.22f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** 状态胶囊颜色（READY 绿 / 失败红 / 处理中蓝 / 保存紫 / 取消灰）。 */
private fun statusPillColor(video: SeedanceVideo): Color = when (video.state) {
    SeedanceVideoState.READY -> Color(0xFF4ADE80)
    SeedanceVideoState.FAILED_SNAPSHOT,
    SeedanceVideoState.FAILED_PROMPT,
    SeedanceVideoState.FAILED_PROMPT_CONFIG_CHANGED,
    SeedanceVideoState.FAILED_SUBMISSION,
    SeedanceVideoState.FAILED_REMOTE,
    SeedanceVideoState.FAILED_QUERY,
    SeedanceVideoState.FAILED_DOWNLOAD,
    SeedanceVideoState.EXPIRED -> Color(0xFFF87171)
    SeedanceVideoState.DOWNLOAD_PENDING,
    SeedanceVideoState.DOWNLOADING -> Color(0xFFA78BFA)
    SeedanceVideoState.CANCELLED,
    SeedanceVideoState.CANCEL_REQUESTED -> Color(0xFF9CA3AF)
    else -> Color(0xFF60A5FA)
}

/** 背景：角色参考快照（Coil）或早期任务的主题渐变兜底，叠加底部压暗保证文字可读。 */
@Composable
private fun EncounterBackdrop(video: SeedanceVideo) {
    Box(Modifier.fillMaxSize()) {
        val imagePath = video.characterImagePath
        if (!imagePath.isNullOrBlank()) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = "${video.characterNameSnapshot} 参考图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 快照复制前（SNAPSHOT_PENDING 早期）尚无参考图：主题渐变兜底。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF33334A), Color(0xFF0E0E16)),
                        )
                    )
            )
        }
        // 底部压暗（0.45 高度以下渐黑），保证故事卡在任意图片上可读。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        0.75f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Black.copy(alpha = 0.92f),
                    )
                )
        )
    }
}

/** 圆形玻璃图标按钮（播放/暂停等）。 */
@Composable
private fun RoundGlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .let { if (testTag != null) it.testTag(testTag) else it }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 页面胶囊按钮（白字半透明白底，深色背景下恒可读）。 */
@Composable
private fun EncounterPageChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 毫秒 -> `m:ss`（进度条时间）。 */
private fun formatVideoTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** 时间戳 -> `yyyy-MM-dd HH:mm`（邂逅历史流展示用）。 */
internal fun formatVideoTimestamp(ts: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

/**
 * 落定页播放决策（供 [EncounterVideoPager] 与 instrumentation 测试共用）：
 * 落定视频为 READY 且本地归档文件就绪 -> 播放；否则（非 READY / 离开列表 / 文件缺失）暂停。
 */
internal fun settleEncounterPlayback(
    settledVideo: SeedanceVideo?,
    controller: SeedancePlaybackController,
) {
    val path = settledVideo?.localVideoPath
    if (settledVideo != null && settledVideo.state == SeedanceVideoState.READY && !path.isNullOrBlank()) {
        controller.play(File(path))
    } else {
        controller.pause()
    }
}
