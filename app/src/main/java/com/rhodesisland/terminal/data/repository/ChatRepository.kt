package com.rhodesisland.terminal.data.repository

import com.rhodesisland.terminal.data.local.ChatDao
import com.rhodesisland.terminal.data.local.ChatHistoryEntity
import com.rhodesisland.terminal.data.model.AttachedFile
import com.rhodesisland.terminal.data.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 聊天记录仓库
 * 按会话（conversationId）分桶读写消息；会话本身见 [ConversationRepository]。
 * 对应小程序 storage.getHistory / setHistory / clearHistory。
 */
class ChatRepository(private val dao: ChatDao) {

    private val json = Json { ignoreUnknownKeys = true }

    fun getHistoryFlow(conversationId: Long): Flow<List<ChatMessage>> =
        dao.getHistory(conversationId).map { entities ->
            // DAO 返回最新 N 条（DESC），反转为 ASC 以便按时间正序展示
            entities.map { it.toMessage() }.asReversed()
        }

    suspend fun getHistory(conversationId: Long): List<ChatMessage> =
        dao.getHistoryList(conversationId).map { it.toMessage() }.asReversed()

    suspend fun addMessage(characterId: String, conversationId: Long, message: ChatMessage): Long {
        // 事务性插入 + 修剪，避免 Flow 在中间状态 emit（详见 ChatDao.insertAndTrim）
        return dao.insertAndTrim(conversationId, message.toEntity(characterId, conversationId))
    }

    /** 按 id 删除单条消息（发送失败回滚用） */
    suspend fun deleteMessage(id: Long) {
        dao.deleteById(id)
    }

    suspend fun clearHistory(conversationId: Long) {
        dao.clearHistory(conversationId)
    }

    // ===== 转换 =====

    private fun ChatHistoryEntity.toMessage(): ChatMessage = ChatMessage(
        role = role,
        content = content,
        images = decodeStringList(imagesJson),
        files = decodeFileList(filesJson),
        fileNames = decodeStringList(fileNamesJson),
        timestamp = timestamp,
    )

    private fun ChatMessage.toEntity(characterId: String, conversationId: Long): ChatHistoryEntity = ChatHistoryEntity(
        characterId = characterId,
        conversationId = conversationId,
        role = role,
        content = content,
        imagesJson = encodeStringList(images),
        filesJson = encodeFileList(files),
        fileNamesJson = encodeStringList(fileNames),
        timestamp = timestamp,
    )

    private fun encodeStringList(list: List<String>): String =
        if (list.isEmpty()) "" else json.encodeToString(ListSerializer(String.serializer()), list)

    private fun decodeStringList(s: String): List<String> =
        if (s.isBlank()) emptyList()
        else runCatching { json.decodeFromString(ListSerializer(String.serializer()), s) }.getOrDefault(emptyList())

    private fun encodeFileList(list: List<AttachedFile>): String =
        if (list.isEmpty()) "" else json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AttachedFile.serializer()), list)

    private fun decodeFileList(s: String): List<AttachedFile> =
        if (s.isBlank()) emptyList()
        else runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AttachedFile.serializer()), s) }.getOrDefault(emptyList())
}
