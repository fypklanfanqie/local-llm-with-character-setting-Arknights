package com.rhodesisland.terminal.data.local

import android.content.Context
import androidx.room.*
import com.rhodesisland.terminal.config.AppConfig
import kotlinx.coroutines.flow.Flow

/**
 * 会话实体
 * 每个角色可有多个会话；每个会话有独立的消息历史与模型上下文。
 * 按 (characterId, updatedAt) 索引以便「按角色列出 + 最近活跃在前」。
 */
@Entity(
    tableName = "conversation",
    indices = [Index(value = ["characterId", "updatedAt"])]
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 聊天记录实体
 * 按 conversationId 分桶，每个会话最多 MAX_HISTORY_PER_CONVERSATION 条。
 */
@Entity(
    tableName = "chat_history",
    indices = [Index(value = ["conversationId", "timestamp"])]
)
data class ChatHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: String,        // 冗余保留：便于按角色整体清理 / 审计
    val conversationId: Long,       // 所属会话
    val role: String,               // "user" | "assistant"
    val content: String,
    val imagesJson: String = "",    // JSON 数组
    val filesJson: String = "",     // JSON 数组
    val fileNamesJson: String = "", // JSON 数组
    val timestamp: Long,
)

@Dao
interface ChatDao {

    // 取最新 N 条（DESC）再由 Repository 反转为 ASC 显示。
    // 旧实现用 ASC LIMIT N 取的是「最旧 N 条」：当 DB 临时多于 N 条（trim 未完成/失败）
    // 时会漏掉刚发送的最新消息，且进程被杀后可能永久不可见。改用 DESC 始终保留最新 N 条。
    @Query("SELECT * FROM chat_history WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT ${AppConfig.MAX_HISTORY_PER_CONVERSATION}")
    fun getHistory(conversationId: Long): Flow<List<ChatHistoryEntity>>

    @Query("SELECT * FROM chat_history WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT ${AppConfig.MAX_HISTORY_PER_CONVERSATION}")
    suspend fun getHistoryList(conversationId: Long): List<ChatHistoryEntity>

    @Insert
    suspend fun insert(entity: ChatHistoryEntity): Long

    @Query("DELETE FROM chat_history WHERE conversationId = :conversationId")
    suspend fun clearHistory(conversationId: Long)

    @Query("DELETE FROM chat_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM chat_history WHERE conversationId = :conversationId")
    suspend fun count(conversationId: Long): Int

    /** 删除最旧的记录，保留最新 N 条 */
    @Query("DELETE FROM chat_history WHERE conversationId = :conversationId AND id IN (SELECT id FROM chat_history WHERE conversationId = :conversationId ORDER BY timestamp ASC LIMIT :limit)")
    suspend fun trimOldest(conversationId: Long, limit: Int)

    /**
     * 原子地插入并修剪：把 insert + count + trim 包进单个事务。
     * 旧实现是三次独立 DB 操作，Flow 会在 insert 后、trim 前 emit 一次中间状态
     * （此时 DB 有 N+1 条，配合旧的 ASC 查询会漏掉最新消息，造成 UI 闪烁）；
     * 若进程在 insert 与 trim 之间被杀，DB 永久多于 N 条。事务保证 Flow 只在提交后 emit 一次。
     */
    @Transaction
    suspend fun insertAndTrim(conversationId: Long, entity: ChatHistoryEntity): Long {
        val id = insert(entity)
        val c = count(conversationId)
        if (c > AppConfig.MAX_HISTORY_PER_CONVERSATION) {
            trimOldest(conversationId, c - AppConfig.MAX_HISTORY_PER_CONVERSATION)
        }
        return id
    }
}

@Dao
interface ConversationDao {

    @Insert
    suspend fun insert(entity: ConversationEntity): Long

    @Query("UPDATE conversation SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, updatedAt: Long)

    /** 仅刷新 updatedAt（发消息后把会话顶到列表最前） */
    @Query("UPDATE conversation SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("SELECT * FROM conversation WHERE characterId = :characterId ORDER BY updatedAt DESC")
    fun observeByCharacter(characterId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE characterId = :characterId ORDER BY updatedAt DESC")
    suspend fun listByCharacter(characterId: String): List<ConversationEntity>

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    @Query("SELECT COUNT(*) FROM conversation WHERE characterId = :characterId")
    suspend fun count(characterId: String): Int

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM chat_history WHERE conversationId = :conversationId")
    suspend fun deleteMessages(conversationId: Long)

    /**
     * 事务性删除会话：先删该会话的全部消息，再删会话本身。
     * 跨表 SQL（conversation + chat_history）在单个 @Transaction 内保证原子。
     */
    @Transaction
    suspend fun deleteConversation(id: Long) {
        deleteMessages(id)
        delete(id)
    }
}

@Database(
    entities = [ChatHistoryEntity::class, ConversationEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // 双重检查锁定：避免两个并发首次调用各建一个 RoomDatabase 实例，
                // 否则先到的调用方会持有孤儿实例，其 Flow 观察者收不到后续写入通知。
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rhodes_chat.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
