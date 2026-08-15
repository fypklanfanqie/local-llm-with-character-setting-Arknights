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
    /**
     * Seedance 自动视频开关（按会话保存，新会话默认关闭）。
     * 仅 CLOUD Provider 且助手正常完整结束时触发；本地聊天与停止后的部分回复不触发。
     */
    val autoVideoEnabled: Boolean = false,
)
