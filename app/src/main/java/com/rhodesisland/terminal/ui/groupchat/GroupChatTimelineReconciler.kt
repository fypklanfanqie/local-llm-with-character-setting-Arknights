package com.rhodesisland.terminal.ui.groupchat

import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.DisplayMessage
import com.rhodesisland.terminal.ui.chat.PendingFinal
import com.rhodesisland.terminal.util.MarkdownParser

/**
 * 群聊时间线协调器（纯 Kotlin，无 Android 依赖，JVM 可测）。
 *
 * 与单角色 [com.rhodesisland.terminal.ui.chat.ChatTimelineReconciler] 同构，两处差异：
 * - sender 逐条按行级 `characterId` 解析（[speakerNameOf] 回退「群聊成员」），user 恒为 "YOU"；
 * - 乐观完成消息是**列表**（一轮多人答复会连续落库多条，Room Flow 回填可能只包含其中一部分）：
 *   未被 Room 确认的 pending 全部保留展示，确认/跨会话的以行 ID 返回给调用方移除。
 */
object GroupChatTimelineReconciler {

    data class Result(
        val messages: List<DisplayMessage>,
        val showWelcome: Boolean,
        /** 已由 Room 确认（或属于其它会话）的 pending 行 ID，调用方从持有列表移除。 */
        val resolvedPendingIds: Set<Long>,
    )

    fun reconcile(
        history: List<ChatMessage>,
        activeConversationId: Long?,
        pendingFinals: List<PendingFinal>,
        streaming: DisplayMessage?,
        speakerNameOf: (String?) -> String,
    ): Result {
        val base = renderHistory(history, speakerNameOf)
        val seen = HashSet<String>(base.size + 4)
        base.forEach { seen.add(it.id) }

        val resolved = HashSet<Long>()
        val messages = buildList {
            addAll(base)
            pendingFinals.forEach { pending ->
                when {
                    pending.conversationId != activeConversationId -> resolved.add(pending.databaseId)
                    seen.contains(pending.message.id) -> resolved.add(pending.databaseId)
                    else -> {
                        add(pending.message)
                        seen.add(pending.message.id)
                    }
                }
            }
            if (streaming != null && seen.add(streaming.id)) {
                add(streaming)
            }
        }
        val showWelcome = history.isEmpty() && streaming == null && pendingFinals.isEmpty()
        return Result(messages = messages, showWelcome = showWelcome, resolvedPendingIds = resolved)
    }

    private fun renderHistory(
        history: List<ChatMessage>,
        speakerNameOf: (String?) -> String,
    ): List<DisplayMessage> {
        if (history.isEmpty()) return emptyList()
        return history.mapIndexed { idx, msg ->
            val src = MarkdownParser.stripThink(msg.content) // 群聊恒剥离思考（云端）
            val segments = MarkdownParser.parseWithThink(src, isStreaming = false)
            val id = msg.databaseId?.let { "msg-$it" }
                ?: "msg-${msg.timestamp}-$idx"
            DisplayMessage(
                id = id,
                role = msg.role,
                content = msg.content,
                segments = segments,
                sender = if (msg.role == "user") "YOU" else speakerNameOf(msg.characterId),
                images = msg.images,
                files = msg.files,
                completionState = msg.completionState,
                databaseId = msg.databaseId,
                characterId = msg.characterId,
            )
        }
    }
}