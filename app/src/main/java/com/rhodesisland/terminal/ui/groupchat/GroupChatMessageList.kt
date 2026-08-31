package com.rhodesisland.terminal.ui.groupchat

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
import androidx.compose.ui.unit.dp
import com.rhodesisland.terminal.data.model.DisplayMessage
import com.rhodesisland.terminal.ui.chat.ChatAutoScrollPolicy
import com.rhodesisland.terminal.ui.chat.ChatUiState
import com.rhodesisland.terminal.ui.chat.MessageBubble
import com.rhodesisland.terminal.ui.chat.TypingIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 群聊消息列表：复用单角色 [MessageBubble]，逐条按 [DisplayMessage.characterId] 定位成员头像/名字。
 * 与 [com.rhodesisland.terminal.ui.chat.ChatMessageList] 的关键差异：头像来源是「该条消息的发言人」，
 * 而非全局单一角色。
 *
 * 自动滚动与单聊同构（「用户接管优先」+ 跟随锚点）：旧实现无条件滚底，用户上翻浏览历史时
 * 任何新回复都会把列表瞬间拉回底部；现与单聊一致——用户上滑离开底部即暂停跟随并显示
 * 「回到底部」按钮，跟随滚动前做锚点校验消除「滚动落定 -> 策略更新」竞态。
 */
@Composable
fun GroupChatMessageList(
    state: GroupChatUiState,
    onDelete: (DisplayMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // MessageBubble 仅依赖 ChatUiState.ttsEnabled（群聊 v1 无朗读），构造空壳即可。
    val dummyChatState = remember { ChatUiState() }
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { NEAR_BOTTOM_DP.toPx() }.toInt()
    var policy by remember { mutableStateOf(ChatAutoScrollPolicy.State()) }

    fun nameOf(id: String?) = id?.let { mid -> state.members.firstOrNull { it.id == mid }?.name }
        ?: GroupChatPromptBuilder.FALLBACK_NAME
    fun imageOf(id: String?) = id?.let { mid -> state.memberImages[mid] } ?: ""

    // 会话切换（groupId 变化时 GroupChatViewModel 重建列表）：重置跟随并定位到底部
    LaunchedEffect(state.conversationId) {
        policy = ChatAutoScrollPolicy.onConversationChanged(policy)
        if (listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE)
        }
    }

    // 布局静止后按像素位置更新策略（与单聊同构）；确认在底部时记录跟随锚点。
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
            val nearBottom = !canScrollForward || distanceToEnd <= thresholdPx
            policy = ChatAutoScrollPolicy.onScrollSettled(
                policy, isNearBottom = nearBottom, totalItems = listState.layoutInfo.totalItemsCount,
            )
        }
    }

    // 新消息 / 流式内容增长：策略允许且锚点校验通过才跟随（见 [ChatAutoScrollPolicy.shouldFollowBottom]）。
    val lastContent = state.messages.lastOrNull()?.content
    LaunchedEffect(state.messages.size, lastContent, state.showTyping, policy.followBottom) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        if (!policy.followBottom) return@LaunchedEffect
        if (listState.isScrollInProgress) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        if (!ChatAutoScrollPolicy.shouldFollowBottom(policy, info.totalItemsCount, lastVisible.index)) {
            return@LaunchedEffect
        }
        delay(FRAME_MEASURE_MS)
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.scrollToItem(total - 1, Int.MAX_VALUE)
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            items(state.messages, key = { it.id }) { msg ->
                MessageBubble(
                    message = msg,
                    state = dummyChatState,
                    characterImage = imageOf(msg.characterId),
                    characterName = nameOf(msg.characterId),
                    userImage = state.userImage,
                    onTts = {},
                    onDelete = { onDelete(msg) },
                )
            }
            if (state.showTyping) {
                item {
                    TypingIndicator(
                        imageUrl = imageOf(state.typingCharacterId),
                        name = nameOf(state.typingCharacterId),
                    )
                }
            }
        }

        // 用户上滑离开底部后的「回到底部」按钮（与单聊一致）
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
                    .size(48.dp)
                    .background(BOTTOM_BUTTON_COLOR, CircleShape),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "回到底部",
                    tint = Color.White,
                )
            }
        }
    }
}

/** 距底部多少 dp 内视为「接近底部」并保持跟随（与单聊 [com.rhodesisland.terminal.ui.chat.ChatMessageList] 一致）。 */
private val NEAR_BOTTOM_DP = 96.dp

/** 内容增长后等待布局完成的时长（ms）。 */
private const val FRAME_MEASURE_MS = 16L

/** 回到底部按钮背景色（半透明深灰，深浅主题下均清晰）。 */
private val BOTTOM_BUTTON_COLOR = Color(0xCC333333)
