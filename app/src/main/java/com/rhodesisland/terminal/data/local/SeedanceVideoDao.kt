package com.rhodesisland.terminal.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Seedance 视频任务 DAO。
 *
 * 状态以 [com.rhodesisland.terminal.data.model.SeedanceVideoState] 的存储键持久化；
 * 所有「条件推进」都走 [claim] 的 CAS 更新（UPDATE ... WHERE state = :from），
 * 保证同一阶段只有一个 Worker 认领成功。
 */
@Dao
interface SeedanceVideoDao {

    /**
     * 幂等插入（IGNORE）：(sourceAssistantMessageId, triggerType) 唯一索引冲突时
     * 返回 -1 且不覆盖既有行——同一助手回复不可能产生两条自动视频任务。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: SeedanceVideoEntity): Long

    /** 会话内任务，按创建时间正序（聊天时间线展示）。 */
    @Query("SELECT * FROM seedance_video WHERE sourceConversationId = :conversationId ORDER BY createdAt ASC")
    fun observeByConversation(conversationId: Long): Flow<List<SeedanceVideoEntity>>

    /** 全部任务，按创建时间倒序（邂逅历史流）。 */
    @Query("SELECT * FROM seedance_video ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SeedanceVideoEntity>>

    /**
     * 恢复扫描：可被 Worker 自动认领且未处于退避等待的任务。
     *
     * 状态集合与 [com.rhodesisland.terminal.data.model.SeedanceVideoState] 的存储键一一对应：
     * SNAPSHOT_PENDING / PROMPT_PENDING / SUBMISSION_PENDING / QUEUED / RUNNING /
     * CANCEL_REQUESTED / DOWNLOAD_PENDING。PROMPTING/SUBMITTING/DOWNLOADING 由
     * 恢复流程显式重置（见 Task 6），不在此直接扫描。
     */
    @Query(
        "SELECT * FROM seedance_video WHERE state IN " +
            "('snapshot_pending','prompt_pending','submission_pending','queued','running'," +
            "'cancel_requested','download_pending') " +
            "AND (nextRetryAt IS NULL OR nextRetryAt <= :now)"
    )
    suspend fun listRecoverable(now: Long): List<SeedanceVideoEntity>

    @Query("SELECT * FROM seedance_video WHERE id = :id")
    suspend fun getById(id: Long): SeedanceVideoEntity?

    /**
     * CAS 认领：仅当当前状态为 :from 时推进到 :to 并刷新 updatedAt。
     * 返回受影响行数（0 = 已被其他 Worker 抢占 / 行不存在）。
     */
    @Query("UPDATE seedance_video SET state = :to, updatedAt = :updatedAt WHERE id = :id AND state = :from")
    suspend fun claim(id: Long, from: String, to: String, updatedAt: Long): Int

    @Update
    suspend fun update(entity: SeedanceVideoEntity)
}
