package com.rhodesisland.terminal.conversationexport

import com.rhodesisland.terminal.config.AssetPaths
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.Conversation
import com.rhodesisland.terminal.data.repository.CharacterRepository
import com.rhodesisland.terminal.data.repository.ChatRepository
import com.rhodesisland.terminal.data.repository.ConversationRepository
import com.rhodesisland.terminal.data.repository.SettingsRepository

class ConversationExportException(message: String) : IllegalStateException(message)

class ConversationExportService(
    private val conversations: ConversationRepository,
    private val chats: ChatRepository,
    private val characters: CharacterRepository,
    private val settings: SettingsRepository,
) {

    suspend fun prepare(conversationId: Long, backgroundPath: String = ""): ConversationExportDocument {
        val conversation = conversations.getById(conversationId)
            ?: throw ConversationExportException("该会话已不存在")
        val messages = chats.getAllHistoryForExport(conversation.id)
        if (messages.isEmpty()) throw ConversationExportException("没有可导出的已保存消息")

        val ownerName = displayName(conversation.characterId, "群聊")
        // 博士头像：设置「我的形象」的内部存储路径（空=未设置，导出画 monogram）。
        val userAvatarPath = settings.getUserProfileNow().avatarPath
        return ConversationExportDocument(
            title = conversation.title,
            ownerName = ownerName,
            createdAt = conversation.createdAt,
            exportedAt = System.currentTimeMillis(),
            messages = messages.map { message -> message.toExportMessage(conversation, userAvatarPath) },
            // 聊天背景（由调用方传入当前轮播到的背景路径；http(s) 网络直链离线不可加载，留空回退纯色底）。
            backgroundPath = backgroundPath.takeIf { !it.startsWith("http://") && !it.startsWith("https://") }.orEmpty(),
        )
    }

    private suspend fun ChatMessage.toExportMessage(
        conversation: Conversation,
        userAvatarPath: String,
    ): ConversationExportMessage {
        val speakerId = if (conversation.isGroup) characterId else conversation.characterId
        val isUser = role == "user"
        return ConversationExportMessage(
            timestamp = timestamp,
            senderName = when {
                isUser -> "博士"
                else -> displayName(speakerId, if (conversation.isGroup) "群聊成员" else "助手")
            },
            content = content,
            attachments = attachmentLabels(),
            avatarPath = if (isUser) userAvatarPath else characterImagePath(speakerId),
        )
    }

    /** 角色头像源：自定义角色用本地立绘路径，内置角色用 assets 路径（PRTS 网络直链等不可离线加载时返回空）。 */
    private suspend fun characterImagePath(characterId: String?): String {
        if (characterId.isNullOrBlank()) return ""
        val char = characters.getNow(characterId) ?: Characters.ALL[characterId] ?: return ""
        val custom = if (char.isCustom) char.image.takeIf { it.isNotBlank() } else null
        return custom ?: AssetPaths.PICTURES[characterId].orEmpty().takeIf { path ->
            // 仅保留本地 assets 相对路径；PRTS 直链（http(s)）导出时离线加载不了，回退 monogram。
            !path.startsWith("http://") && !path.startsWith("https://")
        }.orEmpty()
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
