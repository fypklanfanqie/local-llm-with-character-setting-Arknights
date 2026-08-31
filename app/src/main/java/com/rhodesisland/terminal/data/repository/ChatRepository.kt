package com.rhodesisland.terminal.data.repository

import com.rhodesisland.terminal.data.local.AppDatabase
import com.rhodesisland.terminal.data.local.ChatDao
import com.rhodesisland.terminal.data.local.ChatHistoryEntity
import com.rhodesisland.terminal.data.local.SpecialEventMemoryMessageEntity
import com.rhodesisland.terminal.data.local.toMessage as toArchiveMessage
import com.rhodesisland.terminal.data.model.AttachedFile
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.MessageCompletionState
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 聊天记录仓库（Room v12 起：按会话路由）。
 *
 * - 普通单聊 / 群聊：读写 `chat_history`——UI Flow 取 AppConfig.MAX_HISTORY_PER_CONVERSATION
 *   显示窗口；LLM/prompt 路径（[getHistory]）取 `MAX_PROMPT_SUPPLY` 供给量（cap+锚定步长），
 *   让调用侧的 `PromptWindowAnchor.anchoredWindow` 有溢出余量做量子截断，云端前缀缓存起点
 *   不随逐轮修剪漂移。事务修剪目标同为供给量。
 * - **特殊邂逅会话**（special_event.conversationId 命中）：读写永久归档表
 *   `special_event_memory_message`——无 100 条修剪、无删除接口，保证「永久回忆」。
 *
 * 所有读路径（Flow/一次性/导出）与写路径走同一套路由，杜绝「聊天页写归档、通知写普通表」分叉。
 */
class ChatRepository(
    private val dao: ChatDao,
    private val database: AppDatabase? = null,
) {
    /** 归档 DAO；database 未注入时（旧测试构造）事件路由不可用。 */
    private val memoryDao get() = database?.specialEventMemoryDao()

    /**
     * 判断 [conversationId] 是否为特殊邂逅会话。查询失败按普通会话处理（不阻塞聊天），
     * 但生产构造恒有 database，不会走该分支。
     */
    private suspend fun isEventConversation(conversationId: Long): Boolean {
        val db = database ?: return false
        return runCatching { db.affinityDao().getSpecialEventByConversation(conversationId) }
            .getOrNull() != null
    }

    fun getHistoryFlow(conversationId: Long): Flow<List<ChatMessage>> {
        val mem = memoryDao ?: return legacyFlow(conversationId)
        // Flow 无法逐值判路由：以「当前是否事件会话」一次性判定后选择数据源。
        // 会话类型在创建后不变（事件壳不会被转成普通会话），故静态判定安全。
        return kotlinx.coroutines.flow.flow {
            val eventId = runCatching { resolveEventId(conversationId) }.getOrNull()
            if (eventId != null) {
                mem.observeRecentMessages(eventId, EVENT_ARCHIVE_WINDOW).collect { rows ->
                    emit(rows.map { it.toArchiveMessage() }.asReversed())
                }
            } else {
                legacyFlow(conversationId).collect { emit(it) }
            }
        }
    }

    private fun legacyFlow(conversationId: Long): Flow<List<ChatMessage>> =
        dao.getHistory(conversationId).map { entities ->
            // DAO 返回最新 N 条（DESC），反转为 ASC 以便按时间正序展示
            entities.map { it.toMessage() }.asReversed()
        }

    suspend fun getHistory(conversationId: Long): List<ChatMessage> {
        val eventId = memoryDao?.let { runCatching { resolveEventId(conversationId) }.getOrNull() }
        if (eventId != null) {
            return memoryDao!!.loadRecentMessages(eventId, EVENT_ARCHIVE_WINDOW)
                .map { it.toArchiveMessage() }.asReversed()
        }
        // 供给查询（LIMIT = cap + 锚定步长）：所有调用方都是 LLM prompt 组装路径，
        // 各自再做 anchoredWindow / takeLast 截断——超额供给是量子截断生效的前提。
        return dao.getHistoryListForPrompt(conversationId).map { it.toMessage() }.asReversed()
    }

    /** 导出使用：按时间正序读取全部消息（事件归档全量；普通表不受 UI 窗口限制）。 */
    suspend fun getAllHistoryForExport(conversationId: Long): List<ChatMessage> {
        val eventId = memoryDao?.let { runCatching { resolveEventId(conversationId) }.getOrNull() }
        if (eventId != null) {
            return memoryDao!!.getAllMessages(eventId).map { it.toArchiveMessage() }
        }
        return dao.getAllHistoryList(conversationId).map { it.toMessage() }
    }

    /**
     * 追加一条消息。事件会话写入永久归档（幂等键 msg:<UUID>），返回归档行 id；
     * 普通会话保持原 insertAndTrim 行为。
     */
    suspend fun addMessage(characterId: String, conversationId: Long, message: ChatMessage): Long {
        val mem = memoryDao
        if (mem != null) {
            val eventId = runCatching { resolveEventId(conversationId) }.getOrNull()
            if (eventId != null) {
                val entity = SpecialEventMemoryMessageEntity(
                    eventId = eventId,
                    archiveKey = "msg:${UUID.randomUUID()}",
                    role = message.role,
                    characterId = message.characterId ?: characterId,
                    content = message.content,
                    imagesJson = encodeStringList(message.images),
                    filesJson = encodeFileList(message.files),
                    fileNamesJson = encodeStringList(message.fileNames),
                    timestamp = message.timestamp,
                    modelContent = message.modelContent,
                    completionState = message.completionState.storageKey,
                )
                val inserted = mem.insertMessageIgnore(entity)
                // IGNORE 冲突（同 key 已存在，理论不可能因 UUID 随机）兜底回读。
                return if (inserted != -1L) inserted
                else mem.getByArchiveKey(eventId, entity.archiveKey)?.id ?: -1L
            }
        }
        // 事务性插入 + 修剪，避免 Flow 在中间状态 emit（详见 ChatDao.insertAndTrim）
        return dao.insertAndTrim(conversationId, message.toEntity(characterId, conversationId))
    }

    /**
     * 按会话 + id 删除单条普通消息（发送失败回滚用）。
     * 事件归档行**不可删**：事件会话直接 no-op；普通会话才执行 ChatDao.deleteById。
     * 会话参数是必要防线，避免归档自增 id 与普通表 id 碰撞后误删另一会话的消息。
     */
    suspend fun deleteMessage(conversationId: Long, id: Long) {
        if (memoryDao != null && runCatching { isEventConversation(conversationId) }.getOrDefault(false)) return
        dao.deleteById(conversationId, id)
    }

    suspend fun clearHistory(conversationId: Long) {
        // 事件会话的 clearHistory no-op（归档不可清）；普通会话照旧。
        if (memoryDao != null && runCatching { isEventConversation(conversationId) }.getOrDefault(false)) return
        dao.clearHistory(conversationId)
    }

    /** 解析 conversationId 对应的事件 id；非事件会话返回 null。 */
    private suspend fun resolveEventId(conversationId: Long): Long? =
        database?.affinityDao()?.getSpecialEventByConversation(conversationId)?.id

    companion object {
        /** 归档最近窗口条数（回忆页/模型上下文共用）；导出走全量 getAllMessages。 */
        const val EVENT_ARCHIVE_WINDOW = 200
    }
}

// ===== 转换（顶层 internal，便于单测；纯函数无 Android 依赖）=====

/**
 * 实体 -> 领域消息。[modelContent] 原样还原；旧行该列为 null，由调用方 `modelContent ?: content` 兼容。
 * [completionState] 从存储键还原，未知值保守回退 [MessageCompletionState.COMPLETE]。
 */
internal fun ChatHistoryEntity.toMessage(): ChatMessage = ChatMessage(
    role = role,
    content = content,
    images = decodeStringList(imagesJson),
    files = decodeFileList(filesJson),
    fileNames = decodeStringList(fileNamesJson),
    timestamp = timestamp,
    modelContent = modelContent,
    // 回填 Room 自增主键：持久消息的稳定标识（Compose key + 完成消息协调）。
    databaseId = id,
    completionState = MessageCompletionState.fromStorageKey(completionState),
    // 发言人角色 id（群聊按条渲染；1:1 下恒为该会话角色，UI 不读取）。
    characterId = characterId,
)

/**
 * 领域消息 -> 实体。[modelContent] 持久化（本地助手消息存原始文本）；用户消息/云端消息为 null。
 * 注：[ChatMessage.multimodalImages] 运行时字段不持久化（仅发送给 API）。
 * [ChatMessage.databaseId] 为应用内标识，不随实体持久化：id 恒为 0，由 Room 自增生成（@Insert 忽略其值）。
 */
internal fun ChatMessage.toEntity(characterId: String, conversationId: Long): ChatHistoryEntity = ChatHistoryEntity(
    characterId = characterId,
    conversationId = conversationId,
    role = role,
    content = content,
    imagesJson = encodeStringList(images),
    filesJson = encodeFileList(files),
    fileNamesJson = encodeStringList(fileNames),
    timestamp = timestamp,
    modelContent = modelContent,
    completionState = completionState.storageKey,
)

/** 实体字段编解码用的 JSON（宽松：容忍历史行多余/缺失字段）。 */
private val entityJson = Json { ignoreUnknownKeys = true }

internal fun encodeStringList(list: List<String>): String =
    if (list.isEmpty()) "" else entityJson.encodeToString(ListSerializer(String.serializer()), list)

private fun decodeStringList(s: String): List<String> =
    if (s.isBlank()) emptyList()
    else runCatching { entityJson.decodeFromString(ListSerializer(String.serializer()), s) }.getOrDefault(emptyList())

internal fun encodeFileList(list: List<AttachedFile>): String =
    if (list.isEmpty()) "" else entityJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(AttachedFile.serializer()), list)

private fun decodeFileList(s: String): List<AttachedFile> =
    if (s.isBlank()) emptyList()
    else runCatching { entityJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AttachedFile.serializer()), s) }.getOrDefault(emptyList())
