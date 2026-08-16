package com.rhodesisland.terminal.ui.groupchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rhodesisland.terminal.data.model.DisplayMessage
import com.rhodesisland.terminal.ui.chat.ChatUiState
import com.rhodesisland.terminal.ui.chat.MessageBubble
import com.rhodesisland.terminal.ui.chat.TypingIndicator
import kotlinx.coroutines.delay

/**
 * 群聊消息列表：复用单角色 [MessageBubble]，逐条按 [DisplayMessage.characterId] 定位成员头像/名字。
 * 与 [com.rhodesisland.terminal.ui.chat.ChatMessageList] 的关键差异：头像来源是「该条消息的发言人」，
 * 而非全局单一角色。
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

    fun nameOf(id: String?) = id?.let { mid -> state.members.firstOrNull { it.id == mid }?.name }
        ?: GroupChatPromptBuilder.FALLBACK_NAME
    fun imageOf(id: String?) = id?.let { mid -> state.memberImages[mid] } ?: ""

    // 简单自动滚动：有新消息 / 流式内容增长时滚到底部。
    val lastContent = state.messages.lastOrNull()?.content
    LaunchedEffect(state.messages.size, lastContent, state.showTyping) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        delay(16)
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.scrollToItem(total - 1, Int.MAX_VALUE)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp),
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
}