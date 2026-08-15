package com.rhodesisland.terminal.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Seedance 视频任务实体（Room `seedance_video` 表，v5 新增）。
 *
 * 一条行 = 一次自动视频任务的全部快照与远端状态。刻意不声明到 conversation/chat_history
 * 的级联外键：删除聊天/会话、角色原图或全局背景后，既有视频及其全部快照仍保留。
 *
 * 关键索引：
 * - (sourceAssistantMessageId, triggerType) 唯一：同一助手回复同一触发类型只允许一条任务；
 * - remoteTaskId 唯一（可空）：多行 NULL 合法（SQLite 唯一索引语义），未提交远端前可共存；
 * - (sourceConversationId, createdAt)：会话内按创建时间浏览；
 * - (state, nextRetryAt)：恢复扫描（见 [SeedanceVideoDao.listRecoverable]）。
 */
@Entity(
    tableName = "seedance_video",
    indices = [
        Index(value = ["sourceAssistantMessageId", "triggerType"], unique = true),
        Index(value = ["sourceConversationId", "createdAt"]),
        Index(value = ["state", "nextRetryAt"]),
        Index(value = ["remoteTaskId"], unique = true),
    ],
)
data class SeedanceVideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 任务目录名（filesDir/seedance/tasks/{taskUuid}），落库时生成一次，永不改变。 */
    val taskUuid: String,
    /** 触发类型存储键（如 "auto"）；与 sourceAssistantMessageId 共同构成唯一性。 */
    val triggerType: String,
    val sourceConversationId: Long,
    val sourceUserMessageId: Long?,
    val sourceAssistantMessageId: Long,
    // ===== 快照：生成提示词与提交任务的完整依据，任务不随源头变化而漂移 =====
    val characterIdSnapshot: String,
    val characterNameSnapshot: String,
    val characterRoleSnapshot: String,
    val characterSystemPromptSnapshot: String,
    val userTextSnapshot: String,
    val assistantTextSnapshot: String,
    val sceneDescriptionSnapshot: String,
    val promptBaseUrlSnapshot: String,
    val promptModelSnapshot: String,
    /** 提示词结构化 JSON（Task 4 生成，含 finalPrompt 的规范化文档）。 */
    val promptJson: String?,
    /** 提交 Seedance 创建任务时使用的最终提示词。 */
    val finalPrompt: String?,
    /** 角色图来源快照：内置 asset key 或内部文件路径（SNAPSHOT_PENDING 阶段复制依据）。 */
    val characterImageSourceSnapshot: String,
    /** 背景图来源快照（可选）。 */
    val backgroundImageSourceSnapshot: String?,
    // ===== 任务专属参考图（Worker 复制来源快照后写回） =====
    val characterImagePath: String?,
    val characterImageMime: String?,
    val characterImageSha256: String?,
    val backgroundImagePath: String?,
    val backgroundImageMime: String?,
    val backgroundImageSha256: String?,
    // ===== 生成参数快照（枚举存储键，见 SeedanceVideoRepository 映射） =====
    val modelVariant: String,
    val resolution: String,
    val ratio: String,
    val durationSeconds: Int,
    /** 固定开启：Seedance 2.0 生成音频不可关闭。 */
    val generateAudio: Boolean,
    val watermark: Boolean,
    // ===== 状态机与远端任务 =====
    /** SeedanceVideoState.storageKey。 */
    val state: String,
    /** 远端任务状态原文（queued/running/cancelled/succeeded/failed/expired）。 */
    val remoteStatus: String?,
    val generationAttempt: Int,
    val submissionAttemptId: String?,
    val submissionStartedAt: Long?,
    val requestFingerprint: String?,
    val remoteTaskId: String?,
    val remoteVideoUrl: String?,
    val remoteVideoUrlObservedAt: Long?,
    val remoteVideoUrlExpiresAt: Long?,
    val remoteRequestId: String?,
    /** 重新生成时归档的旧远端任务 ID（JSON 数组原文，Task 6 定义结构）。 */
    val previousRemoteTasksJson: String,
    // ===== 本地产物 =====
    val localVideoPath: String?,
    val videoMime: String?,
    val videoByteSize: Long?,
    val videoSha256: String?,
    val downloadedAt: Long?,
    // ===== 重试与错误 =====
    val automaticRetryCount: Int,
    val nextRetryAt: Long?,
    val errorStage: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val retryDisposition: String?,
    /** 重试将产生费用时置 true，须用户确认后才能重新提交。 */
    val requiresCostConfirmation: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
