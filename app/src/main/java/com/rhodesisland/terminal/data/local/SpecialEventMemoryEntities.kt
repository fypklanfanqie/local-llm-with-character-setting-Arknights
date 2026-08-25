package com.rhodesisland.terminal.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.MessageCompletionState
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 特殊邂逅永久归档元数据（Room v12）。
 *
 * 每个已解锁的特殊事件对应一行；**应用内不提供任何删除接口**——普通聊天删除、消息删除、
 * 「清空聊天记录」都不得触碰本表。`eventId` 即 `special_event.id`（1:1，无自增）。
 *
 * 标题/角色快照在事件首次启动时落库，保证未来 assets 脚本版本变化后「永久回忆」的标题仍稳定。
 */
@Entity(
    tableName = "special_event_memory",
    indices = [Index(value = ["characterId", "updatedAt"])],
)
data class SpecialEventMemoryEntity(
    /** 对应 special_event.id（非自增主键）。 */
    @PrimaryKey val eventId: Long,
    val characterId: String,
    val threshold: Int,
    /** 归档时的事件标题快照。 */
    val title: String,
    /** 迁移审计/导航壳来源会话；不能作为归档消息的存储位置。 */
    val sourceConversationId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 特殊邂逅永久归档消息。
 *
 * - 唯一键 `(eventId, archiveKey)` 保证幂等写入：重复启动/重试不会产生重复行；
 *   archiveKey 约定：`opening:<eventId>` / `legacy:<旧chat_history id>` /
 *   `msg:<UUID>`（用户或助手新消息各生成一个）。
 * - 排序键 `(timestamp, id)` 与 chat_history 的 tie-break 语义一致。
 * - **没有 delete/trim 方法**：永久保存语义由 DAO 层面强制。
 */
@Entity(
    tableName = "special_event_memory_message",
    indices = [
        Index(value = ["eventId", "archiveKey"], unique = true),
        Index(value = ["eventId", "timestamp", "id"]),
        Index(value = ["sourceChatMessageId"]),
    ],
)
data class SpecialEventMemoryMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val archiveKey: String,
    /** v11→v12 迁移回填的旧普通表消息 id；新写入为 null。 */
    val sourceChatMessageId: Long? = null,
    val role: String,
    val characterId: String?,
    val content: String,
    val imagesJson: String = "",
    val filesJson: String = "",
    val fileNamesJson: String = "",
    val timestamp: Long,
    val modelContent: String? = null,
    val completionState: String = MessageCompletionState.COMPLETE.storageKey,
)

/** 归档消息 -> 领域 ChatMessage（复用 chat_history 同构字段）。 */
internal fun SpecialEventMemoryMessageEntity.toMessage(): ChatMessage = ChatMessage(
    role = role,
    content = content,
    images = decodeArchiveStringList(imagesJson),
    files = decodeArchiveFileList(filesJson),
    fileNames = decodeArchiveStringList(fileNamesJson),
    timestamp = timestamp,
    modelContent = modelContent,
    databaseId = id,
    completionState = MessageCompletionState.fromStorageKey(completionState),
    characterId = characterId,
)

@Dao
interface SpecialEventMemoryDao {

    @Query("SELECT * FROM special_event_memory WHERE eventId = :eventId")
    suspend fun getMemory(eventId: Long): SpecialEventMemoryEntity?

    @Query("SELECT * FROM special_event_memory WHERE characterId = :characterId")
    fun observeMemoriesForCharacter(characterId: String): Flow<List<SpecialEventMemoryEntity>>

    @Query("SELECT COUNT(*) FROM special_event_memory WHERE characterId = :characterId")
    suspend fun countMemoriesForCharacter(characterId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemoryIgnore(entity: SpecialEventMemoryEntity): Long

    @Query("UPDATE special_event_memory SET updatedAt = :updatedAt WHERE eventId = :eventId")
    suspend fun touchMemory(eventId: Long, updatedAt: Long)

    // ===== 消息读取 =====

    /** 最近窗口（DESC 取 N 条），调用方反转为 ASC 展示。 */
    @Query(
        "SELECT * FROM special_event_memory_message WHERE eventId = :eventId " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    suspend fun loadRecentMessages(eventId: Long, limit: Int): List<SpecialEventMemoryMessageEntity>

    /** 游标分页：取严格早于 (beforeTimestamp, beforeId) 的更早消息（DESC LIMIT N）。 */
    @Query(
        "SELECT * FROM special_event_memory_message WHERE eventId = :eventId AND " +
            "(timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId)) " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    suspend fun loadOlderMessages(eventId: Long, beforeTimestamp: Long, beforeId: Long, limit: Int): List<SpecialEventMemoryMessageEntity>

    /** 全量正序读取（仅导出等低频路径使用）。 */
    @Query(
        "SELECT * FROM special_event_memory_message WHERE eventId = :eventId " +
            "ORDER BY timestamp ASC, id ASC",
    )
    suspend fun getAllMessages(eventId: Long): List<SpecialEventMemoryMessageEntity>

    /** 实时最近窗口 Flow（回忆页/聊天页观察同一数据源）。 */
    @Query(
        "SELECT * FROM special_event_memory_message WHERE eventId = :eventId " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    fun observeRecentMessages(eventId: Long, limit: Int): Flow<List<SpecialEventMemoryMessageEntity>>

    @Query("SELECT COUNT(*) FROM special_event_memory_message WHERE eventId = :eventId")
    suspend fun countMessages(eventId: Long): Int

    // ===== 消息写入（全部幂等 IGNORE，无删除） =====

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIgnore(entity: SpecialEventMemoryMessageEntity): Long

    @Query(
        "SELECT * FROM special_event_memory_message WHERE eventId = :eventId AND archiveKey = :archiveKey LIMIT 1",
    )
    suspend fun getByArchiveKey(eventId: Long, archiveKey: String): SpecialEventMemoryMessageEntity?

    @Query(
        "SELECT id FROM special_event_memory_message WHERE eventId = :eventId AND sourceChatMessageId = :sourceChatMessageId LIMIT 1",
    )
    suspend fun findIdBySourceMessageId(eventId: Long, sourceChatMessageId: Long): Long?

    /** 取开场归档行 id（archiveKey = opening:<eventId>）；不存在返回 null。 */
    @Query(
        "SELECT id FROM special_event_memory_message WHERE eventId = :eventId AND archiveKey = 'opening:' || :eventId LIMIT 1",
    )
    suspend fun findIdBySourcelessOpening(eventId: Long): Long?
}

// ===== JSON 编解码（与 ChatRepository 的 entityJson 同构；独立副本避免循环依赖）=====

private val archiveJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private fun decodeArchiveStringList(s: String): List<String> =
    if (s.isBlank()) emptyList()
    else runCatching {
        archiveJson.decodeFromString(ListSerializer(String.serializer()), s)
    }.getOrDefault(emptyList())

private fun decodeArchiveFileList(s: String): List<com.rhodesisland.terminal.data.model.AttachedFile> =
    if (s.isBlank()) emptyList()
    else runCatching {
        archiveJson.decodeFromString(
            ListSerializer(com.rhodesisland.terminal.data.model.AttachedFile.serializer()),
            s,
        )
    }.getOrDefault(emptyList())
