package com.rhodesisland.terminal.data.model

/**
 * 会话（conversation）领域模型
 * 每个角色可有多个会话；每个会话拥有独立的消息历史与模型上下文。
 * 对应 data/local/ConversationEntity。
 */
data class Conversation(
    val id: Long,
    val characterId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)
