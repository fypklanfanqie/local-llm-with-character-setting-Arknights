package com.rhodesisland.terminal.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rhodesisland.terminal.data.model.DisplayMessage
import com.rhodesisland.terminal.data.model.MessageCompletionState
import com.rhodesisland.terminal.data.model.MessageSegment
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.i18n.t
import com.rhodesisland.terminal.util.ReplyBubbleSplitter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 消息列表（唯一 LazyColumn）+ 「用户接管优先」自动滚动。
 *
 * 修复点（Task 3）：
 * 1. 底部判定用**像素距离**而非末项索引：末项 bottom 距 viewportEnd 在 [NEAR_BOTTOM_DP] 内
 *    视为「接近底部」；单个超高思考消息「可见」不再被误判为在底部。
 * 2. 跟随用末项 end 对齐（[scrollToItem] 末项 + [Int.MAX_VALUE] offset），而非默认 offset=0 把
 *    增长中的生成消息顶部钉在视口顶部——这是长深度思考内容无法下滑查看的根因。
 * 3. 用户下滑离开底部后 [ChatAutoScrollPolicy] 暂停跟随并显示「回到底部」按钮；点击恢复。
 * 4. 流式更新用即时滚动，不为每个 chunk 启动动画，避免 LaunchedEffect 反复取消动画导致跳顶。
 */
@Composable
fun ChatMessageList(
    state: ChatUiState,
    onTts: (DisplayMessage) -> Unit,
    modifier: Modifier = Modifier,
    /** 微信式分句：true 时把 AI 的整段回复按句拆成多个连续短气泡（流式中/含代码块的消息不拆）。 */
    splitReplies: Boolean = false,
    onDelete: (DisplayMessage) -> Unit = {},
    onPlayVideo: ((SeedanceVideo) -> Unit)? = null,
    onFullScreenVideo: ((SeedanceVideo) -> Unit)? = null,
    onExportVideo: ((SeedanceVideo) -> Unit)? = null,
    onCancelVideo: (SeedanceVideo) -> Unit = {},
    onRetryVideo: (SeedanceVideo) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { NEAR_BOTTOM_DP.toPx() }.toInt()
    // 用 Compose 状态持有策略 State，按钮显隐可触发重组
    var policy by remember { mutableStateOf(ChatAutoScrollPolicy.State()) }

    // 会话切换：重置跟随并立即定位到底部
    LaunchedEffect(state.activeConversationId) {
        policy = ChatAutoScrollPolicy.onConversationChanged(policy)
        if (listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE)
        }
    }

    // 布局静止后按像素位置更新策略（拖拽/程序滚动进行中不判定，避免把程序滚动当用户意图）。
    // 确认在底部时记录跟随锚点（总项数），供跟随滚动前校验「旧末项仍在视口内」。
    LaunchedEffect(Unit) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val distanceToEnd = if (last != null) {
                (last.offset + last.size) - info.viewportEndOffset
            } else 0
            Triple(distanceToEnd, listState.canScrollForward, listState.isScrollInProgress)
        }.collect { (distanceToEnd, canScrollForward, isScrolling) ->
            if (isScrolling) return@collect
            // !canScrollForward = 精确到底；否则距离在阈值内也算接近底部（负值=还有底部 padding）。
            val nearBottom = !canScrollForward || distanceToEnd <= thresholdPx
            policy = ChatAutoScrollPolicy.onScrollSettled(
                policy, isNearBottom = nearBottom, totalItems = listState.layoutInfo.totalItemsCount,
            )
        }
    }

    // 流式内容增长：跟随底部。仅在策略允许（未离开底部）且无滚动进行中时执行。
    // 额外锚点校验：视口内必须仍能看到「最近一次确认在底部时的旧末项」。
    // 修复快速上翻被瞬间拉回底部的竞态——用户上翻的落定瞬间（isScrollInProgress 刚变 false、
    // 策略尚未更新为暂停）恰逢流式 chunk 重启本效果时，旧逻辑会直接 scrollToItem 拉底；
    // 现在旧末项已滚出视口则拒绝跟随，内容自然增长时旧末项必在视口内、正常跟随不受影响。
    val lastContent = state.messages.lastOrNull()?.content
    LaunchedEffect(state.messages.size, lastContent, state.showTyping, policy.followBottom) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        if (!policy.followBottom) return@LaunchedEffect
        if (listState.isScrollInProgress) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (!ChatAutoScrollPolicy.shouldFollowBottom(policy, info.totalItemsCount, lastVisible)) {
            return@LaunchedEffect
        }
        // 等一帧让增长后的内容完成测量，再滚到真实末尾
        delay(FRAME_MEASURE_MS)
        val total = listState.layoutInfo.totalItemsCount
        if (total <= 0) return@LaunchedEffect
        listState.scrollToItem(total - 1, Int.MAX_VALUE)
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp).testTag(CHAT_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            items(state.messages, key = { it.id }) { msg ->
                val bubbles = if (splitReplies) splitReplyBubbles(msg) else listOf(msg)
                // 分句时多个气泡纵向紧排（间距收紧），首段承载完整信息（TTS/视频卡等），其余为纯文本气泡
                bubbles.forEachIndexed { index, bubble ->
                    MessageBubble(
                        message = bubble,
                        state = state,
                        characterImage = state.characterImage,
                        characterName = state.characterName,
                        userImage = state.userImage,
                        onTts = { onTts(msg) },
                        onDelete = { onDelete(msg) },
                        // 视频卡回调仅在对应助手消息附带视频时注入（Task 8 接播放/全屏/导出，Task 7 接取消/重试）。
                        onPlayVideo = msg.video?.let { video -> onPlayVideo?.let { cb -> { cb(video) } } },
                        onFullScreenVideo = msg.video?.let { video -> onFullScreenVideo?.let { cb -> { cb(video) } } },
                        onExportVideo = msg.video?.let { video -> onExportVideo?.let { cb -> { cb(video) } } },
                        onCancelVideo = msg.video?.let { video -> { onCancelVideo(video) } },
                        onRetryVideo = msg.video?.let { video -> { onRetryVideo(video) } },
                        // 分句的后续气泡视觉上属于同一条消息：去掉顶部圆角并收紧间距
                        compact = index > 0,
                    )
                    if (index < bubbles.lastIndex) {
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(vertical = 2.dp))
                    }
                }
            }
            if (state.showTyping) {
                item {
                    TypingIndicator(imageUrl = state.characterImage, name = state.characterName)
                }
            }
        }

        // 用户离开底部暂停跟随后的「回到底部」按钮：右下角，避免遮挡居中字幕条。
        if (policy.showReturnToBottom) {
            IconButton(
                onClick = {
                    policy = ChatAutoScrollPolicy.onReturnToBottom(policy)
                    scope.launch {
                        if (listState.layoutInfo.totalItemsCount > 0) {
                            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 12.dp)
                    .size(48.dp)   // ≥48dp 触摸目标
                    .background(BOTTOM_BUTTON_COLOR, CircleShape),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = t("回到底部"),
                    tint = Color.White,
                )
            }
        }
    }
}

/** 距底部多少 dp 内视为「接近底部」并保持跟随。 */
private val NEAR_BOTTOM_DP = 96.dp

/** 内容增长后等待布局完成的时长（ms）。 */
private const val FRAME_MEASURE_MS = 16L

/** 回到底部按钮背景色（半透明深灰，深浅主题下均清晰）。 */
private val BOTTOM_BUTTON_COLOR = Color(0xCC333333)

/** LazyColumn testTag（instrumentation 滚动测试定位用）。 */
const val CHAT_LIST_TAG = "chat_message_list"

/**
 * 把一条消息拆成渲染气泡：仅单聊开启分句、助手消息已完成时才拆。
 *
 * 深度思考兼容：segments 里的 [MessageSegment.Think]（可折叠推理块）**不参与拆分**，
 * 原样挂在第一个气泡上——拆的只是正文文本。否则思考块的存在会让整条消息被排除，
 * 出现「开深度思考就不能分句」的不一致（用户实测反馈）。
 * 含代码块/公式/表格等富分段的 Markdown 消息拆开会破坏排版，仍不拆；流式中的消息不拆。
 * 只有一个气泡时返回单元素列表，渲染与不拆完全一致。
 */
private fun splitReplyBubbles(msg: DisplayMessage): List<DisplayMessage> {
    val splittable = msg.role == "assistant" &&
        !msg.isStreaming &&
        msg.completionState == MessageCompletionState.COMPLETE &&
        msg.segments.isNotEmpty() &&
        msg.segments.all { it is MessageSegment.Text || it is MessageSegment.Think }
    if (!splittable) return listOf(msg)
    // 思考块之外的可见正文：拆分只作用于它
    val visibleText = msg.segments.filterIsInstance<MessageSegment.Text>()
        .joinToString("") { it.content }
    val parts = ReplyBubbleSplitter.split(visibleText)
    if (parts.size <= 1) return listOf(msg)
    val thinkSegs = msg.segments.filterIsInstance<MessageSegment.Think>()
    return parts.mapIndexed { index, part ->
        msg.copy(
            content = part,
            segments = buildList {
                // 思考块只挂第一个气泡：折叠推理 UI 保留且不重复
                if (index == 0) addAll(thinkSegs)
                add(MessageSegment.Text(part))
            },
        )
    }
}
