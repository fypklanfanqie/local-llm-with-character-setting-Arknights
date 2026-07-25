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
)

@Serializable
data class AttachedFile(
    val path: String,
    val name: String,
    val size: Long = 0,
)

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
)
