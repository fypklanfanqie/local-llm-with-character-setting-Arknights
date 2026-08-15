package com.rhodesisland.terminal.ui.chat

import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.DisplayMessage
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.util.MarkdownParser

/**
 * 乐观完成消息（pending-final）：assistant 已落库但 Room Flow 尚未回填该行时的临时桥。
 *
 * 只在 `addMessage()` 成功返回行 ID 后设置；Room 快照包含同一 [databaseId] 时才可清除。
 * 生成中的 [ChatTimelineReconciler] 依赖它在延迟/旧 Flow 快照到达时保留完成回复，
 * 修复「首次回答短暂显示后消失」。字段不可变，由 [ChatViewModel.pendingFinal] 持有。
 */
data class PendingFinal(
    /** 所属会话：跨会话绝不渲染（防止旧会话回复串入新会话）。 */
    val conversationId: Long,
    /** 已落库的 assistant 行 ID（Room 主键）。 */
    val databaseId: Long,
    /** 乐观完成消息。其 id 必须为 `msg-$databaseId`，与 Room 回填后的渲染 key 一致。 */
    val message: DisplayMessage,
)

/**
 * 聊天时间线协调器（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 统一渲染三条输入：Room 历史快照、流式气泡（id=`streaming`）、乐观完成消息（[PendingFinal]）。
 * 保证：
 * - Room 以**行 ID** 确认前，pending-final 不被任何延迟 Flow 覆盖（修复回答消失竞态）；
 * - Room 确认后只显示一次，绝不因文本相等而重复或误清；
 * - 跨会话 pending 一律丢弃并标记清除；
 * - 生成期间流式气泡始终保留为最后一条。
 *
 * 调用方（[ChatViewModel.renderMessages]）在 [Result.pendingResolved] 为 true 时清除 [ChatViewModel.pendingFinal]。
 */
object ChatTimelineReconciler {

    data class Result(
        val messages: List<DisplayMessage>,
        val showWelcome: Boolean,
        /** Room 快照已包含 pending 行 ID（或 pending 属于其它会话）-> 调用方应清除 pendingFinal。 */
        val pendingResolved: Boolean,
    )

    fun reconcile(
        history: List<ChatMessage>,
        activeConversationId: Long?,
        pendingFinal: PendingFinal?,
        streaming: DisplayMessage?,
        showThink: Boolean,
        characterName: String,
        videos: List<SeedanceVideo> = emptyList(),
    ): Result {
        val base = renderHistory(history, showThink, characterName, videos)
        val seen = HashSet<String>(base.size + 2)
        base.forEach { seen.add(it.id) }

        var pendingResolved = false
        val messages = buildList {
            addAll(base)
            val pending = pendingFinal
            if (pending != null) {
                if (pending.conversationId != activeConversationId) {
                    // 跨会话（切角色/切会话后的残留）：绝不渲染，立即清除，防串台。
                    pendingResolved = true
                } else if (seen.contains(pending.message.id)) {
                    // Room 已回填同一行：清 pending，不重复显示。
                    pendingResolved = true
                } else {
                    add(pending.message)
                    seen.add(pending.message.id)
                }
            }
            if (streaming != null && seen.add(streaming.id)) {
                add(streaming)
            }
        }
        val showWelcome = history.isEmpty() && streaming == null && pendingFinal == null
        return Result(messages = messages, showWelcome = showWelcome, pendingResolved = pendingResolved)
    }

    /** Room 快照 -> 渲染列表。持久消息统一用 `msg-$databaseId` 稳定 key。 */
    private fun renderHistory(
        history: List<ChatMessage>,
        showThink: Boolean,
        characterName: String,
        videos: List<SeedanceVideo>,
    ): List<DisplayMessage> {
        if (history.isEmpty()) return emptyList()
        val videoByAssistantId = buildVideoByAssistantId(videos)
        return history.mapIndexed { idx, msg ->
            val src = if (showThink) msg.content else MarkdownParser.stripThink(msg.content)
            val segments = MarkdownParser.parseWithThink(src, isStreaming = false)
            val id = msg.databaseId?.let { "msg-$it" }
                ?: "msg-${msg.timestamp}-$idx"   // 无 databaseId（旧库行/内存消息）用 timestamp-index 兜底，保持稳定
            DisplayMessage(
                id = id,
                role = msg.role,
                content = msg.content,
                segments = segments,
                sender = if (msg.role == "user") "YOU" else (characterName.ifEmpty { "AI" }),
                images = msg.images,
                files = msg.files,
                // Task 6/7：停止 badge 渲染源；不进入 content/modelContent。
                completionState = msg.completionState,
                // Task 7：Seedance 视频任务仅附加到助手消息（展示层投影，不进入 LLM 历史）。
                video = if (msg.role == "assistant") videoByAssistantId[msg.databaseId] else null,
                // 持久行主键：气泡删除操作按此精确删行（流式气泡为 null）。
                databaseId = msg.databaseId,
            )
        }
    }

    /**
     * 按 `sourceAssistantMessageId` 聚合视频任务。
     * 同一助手行出现多条记录时按 [SeedanceVideo.updatedAt] 后写覆盖（last-write-wins，
     * 防御唯一索引冲突前的并发写）；[SeedanceVideo.updatedAt] 相同则后出现的覆盖。
     */
    private fun buildVideoByAssistantId(videos: List<SeedanceVideo>): Map<Long, SeedanceVideo> {
        val map = HashMap<Long, SeedanceVideo>(videos.size)
        for (video in videos) {
            val prev = map[video.sourceAssistantMessageId]
            if (prev == null || video.updatedAt >= prev.updatedAt) {
                map[video.sourceAssistantMessageId] = video
            }
        }
        return map
    }
}
