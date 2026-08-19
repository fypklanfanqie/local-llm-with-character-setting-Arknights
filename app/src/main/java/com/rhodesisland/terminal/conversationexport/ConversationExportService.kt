package com.rhodesisland.terminal.conversationexport

import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.Conversation
import com.rhodesisland.terminal.data.repository.CharacterRepository
import com.rhodesisland.terminal.data.repository.ChatRepository
import com.rhodesisland.terminal.data.repository.ConversationRepository

class ConversationExportException(message: String) : IllegalStateException(message)

class ConversationExportService(
    private val conversations: ConversationRepository,
    private val chats: ChatRepository,
    private val characters: CharacterRepository,
) {

    suspend fun prepare(conversationId: Long): ConversationExportDocument {
        val conversation = conversations.getById(conversationId)
            ?: throw ConversationExportException("该会话已不存在")
        val messages = chats.getHistory(conversation.id)
        if (messages.isEmpty()) throw ConversationExportException("没有可导出的已保存消息")

        val ownerName = displayName(conversation.characterId, "群聊")
        return ConversationExportDocument(
            title = conversation.title,
            ownerName = ownerName,
            createdAt = conversation.createdAt,
            exportedAt = System.currentTimeMillis(),
            messages = messages.map { message -> message.toExportMessage(conversation) },
        )
    }

    private suspend fun ChatMessage.toExportMessage(conversation: Conversation): ConversationExportMessage {
        val speakerId = if (conversation.isGroup) characterId else conversation.characterId
        return ConversationExportMessage(
            timestamp = timestamp,
            senderName = when (role) {
                "user" -> "博士"
                else -> displayName(speakerId, if (conversation.isGroup) "群聊成员" else "助手")
            },
            content = content,
            attachments = attachmentLabels(),
        )
    }

    private fun ChatMessage.attachmentLabels(): List<String> = buildList {
        if (images.isNotEmpty()) add("图片附件（${images.size} 张）")
        files.forEach { file -> add("附件：${safeLabel(file.name)}") }
        fileNames.filter { name -> files.none { it.name == name } }.forEach { name -> add("附件：${safeLabel(name)}") }
    }

    private suspend fun displayName(characterId: String?, fallback: String): String {
        if (characterId.isNullOrBlank()) return fallback
        return characters.getNow(characterId)?.name
            ?: Characters.ALL[characterId]?.name
            ?: fallback
    }

    private fun safeLabel(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .take(120)
        .ifBlank { "未命名附件" }
}
