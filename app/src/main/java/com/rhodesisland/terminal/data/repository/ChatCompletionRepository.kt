package com.rhodesisland.terminal.data.repository

import androidx.room.withTransaction
import com.rhodesisland.terminal.data.local.AppDatabase
import com.rhodesisland.terminal.data.local.SeedanceVideoEntity
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.data.model.SeedanceVideoState

/**
 * 自动视频 outbox 草稿：助手回复完成时随消息同事务落库的快照集合。
 *
 * 携带 Task 7 在发送时捕获的全部来源/参数快照（角色图/背景图为「来源快照」：
 * 内置 asset key 或内部文件路径，Worker 随后复制到任务目录）。[sourceAssistantMessageId]
 * 在事务内由 Room 自增主键回填（见 [AutoVideoOutboxDraft.toEntity]），不由调用方提供。
 */
data class AutoVideoOutboxDraft(
    /** 任务目录名（filesDir/seedance/tasks/{taskUuid}），调用方生成一次，永不改变。 */
    val taskUuid: String,
    /** 触发类型存储键（如 "auto"）；与 sourceAssistantMessageId 共同构成唯一性。 */
    val triggerType: String,
    val sourceConversationId: Long,
    val sourceUserMessageId: Long?,
    // ===== 提示词生成快照 =====
    val characterIdSnapshot: String,
    val characterNameSnapshot: String,
    val characterRoleSnapshot: String,
    val characterSystemPromptSnapshot: String,
    val userTextSnapshot: String,
    val assistantTextSnapshot: String,
    val sceneDescriptionSnapshot: String,
    val promptBaseUrlSnapshot: String,
    val promptModelSnapshot: String,
    // ===== 参考图来源快照 =====
    val characterImageSourceSnapshot: String,
    val backgroundImageSourceSnapshot: String?,
    // ===== 生成参数快照 =====
    val modelVariant: SeedanceModelVariant,
    val resolution: SeedanceResolution,
    val ratio: SeedanceRatio,
    val durationSeconds: Int,
    val generateAudio: Boolean,
    val watermark: Boolean,
)

/** 助手回复最终化结果：消息行主键 + 可选视频任务行主键（outbox 冲突被忽略时为 null）。 */
data class FinalizedAssistant(
    val assistantMessageId: Long,
    val videoTaskId: Long?,
)

/**
 * 聊天完成仓库：助手回复最终化的事务边界。
 *
 * 助手消息与自动视频 outbox 必须在同一个 Room 事务中落库——进程在回复保存后立刻死亡
 * 也不会漏掉自动视频。WorkManager 入队保持在事务之外（Task 6/7 负责）。
 */
class ChatCompletionRepository(private val database: AppDatabase) {

    /**
     * 原子地插入/修剪助手消息，并在 [outbox] 非空时以 INSERT IGNORE 落库自动视频任务
     * （初始态 SNAPSHOT_PENDING）。聊天回复本身不回滚 outbox 冲突：唯一索引已存在同
     * 助手消息任务时 [FinalizedAssistant.videoTaskId] 为 null。
     *
     * **特殊邂逅会话**（special_event.conversationId 命中）：助手消息只写永久归档表
     * （archiveKey = reply:<UUID>），忽略 outbox（事件中禁自动视频）、不写普通表。
     */
    suspend fun finalizeAssistant(
        characterId: String,
        conversationId: Long,
        assistant: ChatMessage,
        outbox: AutoVideoOutboxDraft?,
    ): FinalizedAssistant = database.withTransaction {
        val eventId = runCatching {
            database.affinityDao().getSpecialEventByConversation(conversationId)?.id
        }.getOrNull()
        if (eventId != null) {
            val entity = com.rhodesisland.terminal.data.local.SpecialEventMemoryMessageEntity(
                eventId = eventId,
                archiveKey = "reply:${java.util.UUID.randomUUID()}",
                role = assistant.role,
                characterId = assistant.characterId ?: characterId,
                content = assistant.content,
                imagesJson = encodeStringList(assistant.images),
                filesJson = encodeFileList(assistant.files),
                fileNamesJson = encodeStringList(assistant.fileNames),
                timestamp = assistant.timestamp,
                modelContent = assistant.modelContent,
                completionState = assistant.completionState.storageKey,
            )
            val memoryDao = database.specialEventMemoryDao()
            val inserted = memoryDao.insertMessageIgnore(entity)
            val rowId = if (inserted != -1L) inserted
            else memoryDao.getByArchiveKey(eventId, entity.archiveKey)?.id ?: -1L
            memoryDao.touchMemory(eventId, System.currentTimeMillis())
            return@withTransaction FinalizedAssistant(assistantMessageId = rowId, videoTaskId = null)
        }
        val assistantMessageId = database.chatDao().insertAndTrim(
            conversationId,
            assistant.toEntity(characterId, conversationId),
        )
        val videoTaskId = outbox?.let { draft ->
            database.seedanceVideoDao().insertIgnore(draft.toEntity(assistantMessageId))
                .takeIf { id -> id != -1L }
        }
        FinalizedAssistant(assistantMessageId = assistantMessageId, videoTaskId = videoTaskId)
    }
}

/**
 * outbox 草稿 -> 实体：以 Room 回填的 [sourceAssistantMessageId] 落库，
 * 初始态 [SeedanceVideoState.SNAPSHOT_PENDING]（待复制参考图快照）。
 * Worker 后续填充的字段全部置空/零值；createdAt/updatedAt 为同一时间戳。
 */
internal fun AutoVideoOutboxDraft.toEntity(sourceAssistantMessageId: Long): SeedanceVideoEntity {
    val now = System.currentTimeMillis()
    return SeedanceVideoEntity(
        id = 0,
        taskUuid = taskUuid,
        triggerType = triggerType,
        sourceConversationId = sourceConversationId,
        sourceUserMessageId = sourceUserMessageId,
        sourceAssistantMessageId = sourceAssistantMessageId,
        characterIdSnapshot = characterIdSnapshot,
        characterNameSnapshot = characterNameSnapshot,
        characterRoleSnapshot = characterRoleSnapshot,
        characterSystemPromptSnapshot = characterSystemPromptSnapshot,
        userTextSnapshot = userTextSnapshot,
        assistantTextSnapshot = assistantTextSnapshot,
        sceneDescriptionSnapshot = sceneDescriptionSnapshot,
        promptBaseUrlSnapshot = promptBaseUrlSnapshot,
        promptModelSnapshot = promptModelSnapshot,
        promptJson = null,
        finalPrompt = null,
        characterImageSourceSnapshot = characterImageSourceSnapshot,
        backgroundImageSourceSnapshot = backgroundImageSourceSnapshot,
        characterImagePath = null,
        characterImageMime = null,
        characterImageSha256 = null,
        backgroundImagePath = null,
        backgroundImageMime = null,
        backgroundImageSha256 = null,
        modelVariant = modelVariant.storageKey,
        resolution = resolution.storageKey,
        ratio = ratio.storageKey,
        durationSeconds = durationSeconds,
        generateAudio = generateAudio,
        watermark = watermark,
        state = SeedanceVideoState.SNAPSHOT_PENDING.storageKey,
        remoteStatus = null,
        generationAttempt = 0,
        submissionAttemptId = null,
        submissionStartedAt = null,
        requestFingerprint = null,
        remoteTaskId = null,
        remoteVideoUrl = null,
        remoteVideoUrlObservedAt = null,
        remoteVideoUrlExpiresAt = null,
        remoteRequestId = null,
        previousRemoteTasksJson = "",
        localVideoPath = null,
        videoMime = null,
        videoByteSize = null,
        videoSha256 = null,
        downloadedAt = null,
        automaticRetryCount = 0,
        nextRetryAt = null,
        errorStage = null,
        errorCode = null,
        errorMessage = null,
        retryDisposition = null,
        requiresCostConfirmation = false,
        createdAt = now,
        updatedAt = now,
    )
}
