package com.rhodesisland.terminal.ui.video

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.data.model.SeedanceVideoState

/** 视频卡容器 testTag（instrumentation 测试定位用）。 */
const val SEEDANCE_VIDEO_CARD_TAG = "seedance_video_card"

/**
 * Seedance 视频任务卡（Task 7）。
 *
 * 渲染每一个 [SeedanceVideoState]：
 *  - 构思/提交/排队/生成/保存/已取消/就绪均给中文状态文案；
 *  - QUEUED 显示「取消」；FAILED_* / EXPIRED 显示对应手动操作（继续查询 / 重新下载 /
 *    重新生成提示词 / 重新提交 / 重新生成）；
 *  - 费用性重试（FAILED_REMOTE / EXPIRED / 歧义 FAILED_SUBMISSION）必须先弹确认对话框
 *    （「可能产生费用，确认重新生成？」），用户确认后才调用 [onRetry]；
 *  - READY 显示播放 / 全屏 / 保存到本地；播放 / 全屏 / 导出回调由 Task 8 注入——回调为 null 时不渲染对应按钮。
 *
 * [onCancel]/[onRetry] 由 ViewModel 提供（Task 7 已接线）；[onPlay]/[onExport]/[onFullScreen] 由 Task 8 接线。
 * 活动内联表面判定读取 [LocalSeedancePlaybackController]（屏幕级控制器由 ChatScreen 提供）：卡片仅
 * 在自身是「活动内联视频」（controller 已加载本卡且全屏未开启）时挂载内联播放器表面（见
 * [SeedanceVideoPlayer]），其余卡片显示预览占位——防止 LazyColumn 每行一个 PlayerView。
 */
@Composable
fun SeedanceVideoCard(
    video: SeedanceVideo,
    onPlay: (() -> Unit)? = null,
    onFullScreen: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // 费用性重试确认对话框：状态或任务 id 变化时重置，避免陈旧对话框残留。
    var confirmRegenerate by remember(video.id, video.state) { mutableStateOf(false) }
    val costBearing = isCostBearingRetry(video)
    // 活动内联表面判定（Task 8）：屏幕级控制器（CompositionLocal）已加载本视频且全屏未开启 →
    // 本卡挂载 PlayerView；其余卡片（或未接线控制器）显示预览占位，保证同一时刻至多一个活动表面。
    val controller = LocalSeedancePlaybackController.current
    val activePath = controller?.activePath?.let { it.collectAsState().value }
    val fullScreen = controller?.fullScreen?.let { it.collectAsState().value } == true
    // 路径比较前归一化：控制器侧来自 File.absolutePath，卡片侧来自 Room 持久化的 localVideoPath，
    // 统一分隔符并折叠多余斜杠，避免同一文件的等价路径因表示差异不匹配。
    val isActiveInline = !fullScreen && activePath != null &&
        video.localVideoPath != null &&
        normalizeVideoPathForCompare(activePath) == normalizeVideoPathForCompare(video.localVideoPath)

    Surface(
        color = scheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag(SEEDANCE_VIDEO_CARD_TAG),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Videocam,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    stateText(video),
                    color = scheme.onSurface,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }

            when (video.state) {
                SeedanceVideoState.READY -> {
                    if (isActiveInline) {
                        // 活动内联视频：挂载 PlayerView（与全屏共用同一播放器，同一时刻仅一个表面）。
                        SeedanceVideoPlayer(
                            player = controller?.player,
                            showControls = false,
                            testTag = SEEDANCE_INLINE_PLAYER_TAG,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth()
                                .height(96.dp),
                        )
                    } else {
                        PreviewPlaceholder(video = video)
                    }
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (onPlay != null) {
                            CardActionButton("播放", onPlay, leadingIcon = {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(12.dp))
                            })
                            // 全屏：Task 8 提供独立入口；未接线时回退到 onPlay（与 Task 7 共用入口兼容）。
                            CardActionButton("全屏", onFullScreen ?: onPlay, leadingIcon = {
                                Icon(Icons.Outlined.Fullscreen, contentDescription = null, modifier = Modifier.size(12.dp))
                            })
                        }
                        if (onExport != null) {
                            CardActionButton("保存到本地", onExport, leadingIcon = {
                                Icon(Icons.Outlined.SaveAlt, contentDescription = null, modifier = Modifier.size(12.dp))
                            })
                        }
                    }
                }

                SeedanceVideoState.QUEUED -> {
                    if (onCancel != null) {
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            CardActionButton("取消", onCancel)
                        }
                    }
                }

                SeedanceVideoState.FAILED_SNAPSHOT,
                SeedanceVideoState.FAILED_PROMPT,
                SeedanceVideoState.FAILED_PROMPT_CONFIG_CHANGED,
                SeedanceVideoState.FAILED_SUBMISSION,
                SeedanceVideoState.FAILED_REMOTE,
                SeedanceVideoState.FAILED_QUERY,
                SeedanceVideoState.FAILED_DOWNLOAD,
                SeedanceVideoState.EXPIRED,
                -> {
                    if (onRetry != null) {
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            CardActionButton(
                                retryLabel(video.state),
                                onClick = {
                                    if (costBearing) confirmRegenerate = true else onRetry()
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(12.dp))
                                },
                            )
                        }
                    }
                }

                else -> {
                    // 构思/提交/生成/保存/已取消/正在取消等状态：仅展示文案，无操作。
                }
            }
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

/** READY 预览占位（Task 8 替换为真实缩略图/播放器）。 */
@Composable
private fun PreviewPlaceholder(video: SeedanceVideo) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(96.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = scheme.surfaceContainerHighest.copy(alpha = 0.5f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.Videocam,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (video.localVideoPath.isNullOrBlank()) "视频已生成" else "点击播放",
                    color = scheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** 卡片操作按钮（玻璃胶囊式小按钮）。 */
@Composable
private fun CardActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(scheme.primary.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.invoke()
        if (leadingIcon != null) Spacer(Modifier.size(3.dp))
        Text(label, color = scheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// ===== 纯逻辑（internal，供 instrumentation 测试断言）=====

/** 状态 -> 主文案。 */
internal fun stateText(video: SeedanceVideo): String = when (video.state) {
    SeedanceVideoState.SNAPSHOT_PENDING,
    SeedanceVideoState.PROMPT_PENDING,
    SeedanceVideoState.PROMPTING -> "正在构思视频…"
    SeedanceVideoState.SUBMISSION_PENDING,
    SeedanceVideoState.SUBMITTING -> "正在提交…"
    SeedanceVideoState.QUEUED -> "已排队"
    SeedanceVideoState.RUNNING -> "正在生成…"
    SeedanceVideoState.DOWNLOAD_PENDING,
    SeedanceVideoState.DOWNLOADING -> "生成完成，正在保存…"
    SeedanceVideoState.READY -> "视频已生成"
    SeedanceVideoState.CANCELLED -> "已取消"
    SeedanceVideoState.CANCEL_REQUESTED -> "正在取消…"
    SeedanceVideoState.EXPIRED -> video.errorMessage?.takeIf { it.isNotBlank() } ?: "视频任务已过期"
    SeedanceVideoState.FAILED_SNAPSHOT,
    SeedanceVideoState.FAILED_PROMPT,
    SeedanceVideoState.FAILED_PROMPT_CONFIG_CHANGED,
    SeedanceVideoState.FAILED_SUBMISSION,
    SeedanceVideoState.FAILED_REMOTE,
    SeedanceVideoState.FAILED_QUERY,
    SeedanceVideoState.FAILED_DOWNLOAD ->
        video.errorMessage?.takeIf { it.isNotBlank() } ?: defaultFailureText(video.state)
}

/** FAILED_* 缺少 errorMessage 时的兜底文案。 */
internal fun defaultFailureText(state: SeedanceVideoState): String = when (state) {
    SeedanceVideoState.FAILED_SNAPSHOT -> "角色图片快照失败"
    SeedanceVideoState.FAILED_PROMPT -> "提示词生成失败"
    SeedanceVideoState.FAILED_PROMPT_CONFIG_CHANGED -> "模型/服务地址已变更，无法继续"
    SeedanceVideoState.FAILED_SUBMISSION -> "提交失败"
    SeedanceVideoState.FAILED_REMOTE -> "视频生成失败"
    SeedanceVideoState.FAILED_QUERY -> "查询任务状态失败"
    SeedanceVideoState.FAILED_DOWNLOAD -> "视频下载失败"
    else -> "任务失败"
}

/** 失败/过期状态 -> 手动操作按钮文案。 */
internal fun retryLabel(state: SeedanceVideoState): String = when (state) {
    SeedanceVideoState.FAILED_QUERY -> "继续查询"
    SeedanceVideoState.FAILED_DOWNLOAD -> "重新下载"
    SeedanceVideoState.FAILED_REMOTE -> "重新生成"
    SeedanceVideoState.EXPIRED -> "重新生成"
    SeedanceVideoState.FAILED_SUBMISSION -> "重新提交"
    SeedanceVideoState.FAILED_SNAPSHOT -> "重试快照"
    SeedanceVideoState.FAILED_PROMPT,
    SeedanceVideoState.FAILED_PROMPT_CONFIG_CHANGED -> "重新生成提示词"
    else -> "重试"
}

/**
 * 该重试是否可能产生费用（须先弹确认对话框）。
 * FAILED_REMOTE / EXPIRED 一定费用；FAILED_SUBMISSION 仅在 [SeedanceVideo.requiresCostConfirmation]
 * （歧义 POST）时费用。
 */
internal fun isCostBearingRetry(video: SeedanceVideo): Boolean = when (video.state) {
    SeedanceVideoState.FAILED_REMOTE,
    SeedanceVideoState.EXPIRED -> true
    SeedanceVideoState.FAILED_SUBMISSION -> video.requiresCostConfirmation
    else -> false
}

/**
 * 本地视频路径比较归一化（纯函数）：统一 Windows/Unix 分隔符并折叠多余斜杠、去除尾部斜杠，
 * 使同一文件的等价路径表示（如 `/data/user/0/pkg//files/a.mp4` 与 `\data\user\0\pkg\files\a.mp4`）
 * 在「活动内联视频」判定中可比。不解析符号链接、不做文件系统 I/O。
 */
internal fun normalizeVideoPathForCompare(path: String): String =
    path.replace('\\', '/').replace(Regex("/{2,}"), "/").trimEnd('/')
