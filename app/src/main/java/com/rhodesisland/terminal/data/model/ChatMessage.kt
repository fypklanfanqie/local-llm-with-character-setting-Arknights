package com.rhodesisland.terminal.data.model

import kotlinx.serialization.Serializable

/**
 * 聊天消息
 * 对应小程序 messageHistory 中的消息对象
 *
 * role 取值：
 *  - "user"：用户消息
 *  - "assistant"：AI 回复（已存储到历史）
 *  - "streaming"：流式输出中（仅 UI 临时态，不存储）
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val images: List<String> = emptyList(),
    val files: List<AttachedFile> = emptyList(),
    val fileNames: List<String> = emptyList(),
    /** 运行时多模态图片 base64（不含 data: 前缀）。仅发送给 API，不持久化（ChatRepository.toEntity 未映射）。 */
    val multimodalImages: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * 模型可见的原始文本（本地 MNN 生成、未经展示层处理的版本，可含 `<think>` 等）。
     *
     * - 本地助手消息：`content` 存展示文本、`modelContent` 存原始文本，重放历史时优先用它喂回模型，保证 KV 前缀精确一致。
     * - 用户消息 / 云端消息 / 旧库行：为 null，回退 `content`（[com.rhodesisland.terminal.data.repository.toMessage] + 调用方 `modelContent ?: content`）。
     */
    val modelContent: String? = null,
    /**
     * Room 主键（应用内使用，不持久化为列）。
     *
     * 由 [com.rhodesisland.terminal.data.repository.toMessage] 从 Room 自增主键回填，用于：
     * - 持久消息的 Compose key（`msg-$databaseId`），使乐观完成消息与 Room 回填后的消息保持同一 key；
     * - [com.rhodesisland.terminal.ui.chat.ChatTimelineReconciler] 以行 ID 判断 Room 是否已确认完成消息，
     *   杜绝「首次回答短暂显示后消失」竞态，且两次相同文本的回答不会混淆。
     * 新建消息为 null，插入后由 [com.rhodesisland.terminal.ui.chat.ChatViewModel.sendMessage] 用返回值回填。
     */
    val databaseId: Long? = null,
    /**
     * 消息完成状态（Task 6）：本地助手消息用户停止时记录；默认 [MessageCompletionState.COMPLETE]。
     * 独立于 `content`/`modelContent` 持久化，仅用于 UI 停止 badge。
     */
    val completionState: MessageCompletionState = MessageCompletionState.COMPLETE,
)

@Serializable
data class AttachedFile(
    val path: String,
    val name: String,
    val size: Long = 0,
)

/**
 * 消息完成状态（Task 6）。本地助手消息在用户点击「停止」时记录；普通完成消息为 [COMPLETE]。
 *
 * 以独立字段持久化（Room 列），只用于 UI badge 展示；不写入 [ChatMessage.content]/
 * [ChatMessage.modelContent]，避免后续轮次把状态标记喂给模型。
 */
enum class MessageCompletionState(val storageKey: String) {
    COMPLETE("complete"),
    /** 已停止但保留了部分最终正文。 */
    STOPPED_PARTIAL("stopped_partial"),
    /** 已停止且尚未生成最终答案（只有未闭合思考 / 空正文）。 */
    STOPPED_BEFORE_FINAL("stopped_before_final");

    companion object {
        val DEFAULT: MessageCompletionState = COMPLETE

        /** 从存储键还原；未知/空值回落 [DEFAULT]，避免历史脏值导致崩溃。 */
        fun fromStorageKey(value: String?): MessageCompletionState =
            entries.firstOrNull { it.storageKey == value } ?: DEFAULT
    }
}

/**
 * 渲染分段（代码高亮解析后）
 * 对应小程序 codeHighlight.parseContent 返回的 segments
 */
sealed class MessageSegment {
    data class Text(val content: String) : MessageSegment()
    data class Code(
        val language: String,
        val rawCode: String,
        val firstLine: String,
        val lines: List<List<Token>>,   // [[{text, color}]]
        var folded: Boolean = false,
    ) : MessageSegment()
    data class Science(
        val language: String,
        val rawCode: String,
        val firstLine: String,
        val lines: List<List<FormulaToken>>,
    ) : MessageSegment()
    /** Qwen3 思考过程（<think>...</think>），UI 默认折叠；streaming=true 表示尚未闭合 */
    data class Think(val content: String, val streaming: Boolean = false) : MessageSegment()
}

data class Token(val text: String, val color: String)

data class FormulaToken(
    val text: String,
    val color: String,
    val format: String, // "normal" | "sub" | "sup"
)

/**
 * 渲染用消息（UI 状态）
 */
data class DisplayMessage(
    val id: String,
    val role: String,           // user / assistant / streaming
    val content: String,
    val segments: List<MessageSegment>,
    val sender: String,
    val images: List<String> = emptyList(),
    val files: List<AttachedFile> = emptyList(),
    val isStreaming: Boolean = false,
    /** 消息完成状态（Task 6）：停止 badge 展示用；不进入 content/modelContent。 */
    val completionState: MessageCompletionState = MessageCompletionState.COMPLETE,
    /**
     * Seedance 自动视频任务（Task 7）：仅展示层投影，由 [com.rhodesisland.terminal.ui.chat.ChatTimelineReconciler]
     * 按 `sourceAssistantMessageId` 附加到对应助手消息。`ChatMessage` 持久化保持不变——
     * 视频状态/提示词/本地路径绝不进入 LLM 历史。
     */
    val video: SeedanceVideo? = null,
    /**
     * 已落库消息的 Room 主键（持久消息才有值；流式气泡 / 乐观完成消息由构造方回填）。
     * 供气泡「删除」操作按行 ID 精确删除（[com.rhodesisland.terminal.ui.chat.ChatViewModel.deleteMessage]）。
     */
    val databaseId: Long? = null,
)
