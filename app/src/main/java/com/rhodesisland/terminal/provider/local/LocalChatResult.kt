package com.rhodesisland.terminal.provider.local

import com.rhodesisland.terminal.llm.backend.BackendType
import com.rhodesisland.terminal.llm.metrics.CompletionReason

/**
 * 本地聊天的类型化结果（Task 3 Step 4）。
 *
 * 分离「展示文本」与「模型原始文本」，避免展示装饰（`<think>` 折叠包装）污染回传给模型的 历史，
 * 导致 native `syncPromptCache()` 记录的前缀与重放历史失配、KV 复用失效。
 *
 * @param displayText 展示文本：经 `<think>` 折叠装饰，存入 `ChatMessage.content`、驱动 UI。展示装饰永不进入 toMessagesJson。
 * @param modelText 模型原始输出：与 native `syncPromptCache()` 逐字节一致，存入 `ChatMessage.modelContent`，
 *   重放本地历史时优先取它喂回 MNN，保证 KV 前缀精确复用。
 * @param generation 本次生成摘要（来自 Task 2 遥测）；可能为 null（如旧路径未产出）。
 */
data class LocalChatResult(
    val displayText: String,
    val modelText: String,
    val generation: GenerationSummary? = null,
)

/**
 * 单次本地生成摘要（Task 2 遥测 → Task 3 结果的投影）。
 */
data class GenerationSummary(
    val backend: BackendType,
    val reloaded: Boolean,
    val generatedTokens: Int,
    val decodeTps: Float?,
    val kvReuse: Boolean?,
    val completionReason: CompletionReason?,
)
