package com.rhodesisland.terminal.data.model

import kotlinx.serialization.Serializable

/**
 * 云端 Token 用量统计（设置页「Token 用量」区）。
 *
 * - [TokenUsageEntry]：单个角色的累计用量（输入/输出 token + 调用次数）。
 * - [TokenUsageSnapshot]：全角色快照（DataStore JSON 持久化）；总量由 [total] 汇总得出，
 *   不单独落库避免漂移。
 *
 * 归属口径：1:1 聊天（ChatViewModel）、主动问候、群聊发言、朋友圈文案/评论/回复
 * 都按当时角色累计；无角色上下文的调用（翻译预检、摘要折叠、连通性测试）不计入。
 */
@Serializable
data class TokenUsageEntry(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val calls: Int = 0,
) {
    /** 总 token（输入 + 输出）。 */
    val totalTokens: Long get() = promptTokens + completionTokens

    operator fun plus(other: TokenUsageEntry): TokenUsageEntry = TokenUsageEntry(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        calls = calls + other.calls,
    )
}

@Serializable
data class TokenUsageSnapshot(
    val chars: Map<String, TokenUsageEntry> = emptyMap(),
) {
    /** 全部角色合计（设置页「总量」数字）。 */
    val total: TokenUsageEntry get() = chars.values.fold(TokenUsageEntry()) { acc, e -> acc + e }
}
