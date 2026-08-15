package com.rhodesisland.terminal.data.repository

import com.rhodesisland.terminal.data.local.SeedanceVideoDao
import com.rhodesisland.terminal.data.local.SeedanceVideoEntity
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.data.model.SeedanceVideoState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Seedance 视频任务仓库
 * 按会话/全局观察任务，供聊天视频卡与「邂逅」历史流使用；
 * 恢复扫描与 CAS 认领供 Worker 协调器（Task 6）使用。
 */
class SeedanceVideoRepository(private val dao: SeedanceVideoDao) {

    /** 会话内任务，按创建时间正序（聊天时间线展示）。 */
    fun observeForConversation(conversationId: Long): Flow<List<SeedanceVideo>> =
        dao.observeByConversation(conversationId).map { list -> list.map(SeedanceVideoEntity::toDomain) }

    /** 全部任务，按创建时间倒序（邂逅历史流）。 */
    fun observeAll(): Flow<List<SeedanceVideo>> =
        dao.observeAll().map { list -> list.map(SeedanceVideoEntity::toDomain) }

    /** 恢复扫描：可自动认领且退避已到期的任务。 */
    suspend fun listRecoverable(now: Long): List<SeedanceVideo> =
        dao.listRecoverable(now).map(SeedanceVideoEntity::toDomain)

    /**
     * CAS 认领：仅当当前状态为 [from] 时推进到 [to]。
     * 返回 false 表示已被其他 Worker 抢占或行不存在——调用方不得继续该阶段。
     */
    suspend fun claim(id: Long, from: SeedanceVideoState, to: SeedanceVideoState): Boolean =
        dao.claim(id, from.storageKey, to.storageKey, System.currentTimeMillis()) == 1

    suspend fun getById(id: Long): SeedanceVideo? = dao.getById(id)?.let(SeedanceVideoEntity::toDomain)

    suspend fun update(video: SeedanceVideo) {
        dao.update(video.toEntity())
    }
}

// ===== 转换（顶层 internal，便于单测；纯函数无 Android 依赖）=====

/**
 * 实体 -> 领域。[previousRemoteTasksJson] 为归档 JSON 原文，逐字节透传（Task 6 定义结构后再解析）。
 * 未知/空枚举存储键保守回落：state -> [SeedanceVideoState.DEFAULT]（FAILED_SUBMISSION，
 * 不冒充 READY、不被自动认领），其余 -> SeedanceConfig 的默认档位。
 */
internal fun SeedanceVideoEntity.toDomain(): SeedanceVideo = SeedanceVideo(
    id = id,
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
    promptJson = promptJson,
    finalPrompt = finalPrompt,
    characterImageSourceSnapshot = characterImageSourceSnapshot,
    backgroundImageSourceSnapshot = backgroundImageSourceSnapshot,
    characterImagePath = characterImagePath,
    characterImageMime = characterImageMime,
    characterImageSha256 = characterImageSha256,
    backgroundImagePath = backgroundImagePath,
    backgroundImageMime = backgroundImageMime,
    backgroundImageSha256 = backgroundImageSha256,
    modelVariant = SeedanceModelVariant.fromStorageKey(modelVariant),
    resolution = SeedanceResolution.fromStorageKey(resolution),
    ratio = SeedanceRatio.fromStorageKey(ratio),
    durationSeconds = durationSeconds,
    generateAudio = generateAudio,
    watermark = watermark,
    state = SeedanceVideoState.fromStorageKey(state),
    remoteStatus = remoteStatus,
    generationAttempt = generationAttempt,
    submissionAttemptId = submissionAttemptId,
    submissionStartedAt = submissionStartedAt,
    requestFingerprint = requestFingerprint,
    remoteTaskId = remoteTaskId,
    remoteVideoUrl = remoteVideoUrl,
    remoteVideoUrlObservedAt = remoteVideoUrlObservedAt,
    remoteVideoUrlExpiresAt = remoteVideoUrlExpiresAt,
    remoteRequestId = remoteRequestId,
    previousRemoteTasksJson = previousRemoteTasksJson,
    localVideoPath = localVideoPath,
    videoMime = videoMime,
    videoByteSize = videoByteSize,
    videoSha256 = videoSha256,
    downloadedAt = downloadedAt,
    automaticRetryCount = automaticRetryCount,
    nextRetryAt = nextRetryAt,
    errorStage = errorStage,
    errorCode = errorCode,
    errorMessage = errorMessage,
    retryDisposition = retryDisposition,
    requiresCostConfirmation = requiresCostConfirmation,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** 领域 -> 实体。枚举以存储键持久化；id 原样保留（更新时 Room 按主键定位）。 */
internal fun SeedanceVideo.toEntity(): SeedanceVideoEntity = SeedanceVideoEntity(
    id = id,
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
    promptJson = promptJson,
    finalPrompt = finalPrompt,
    characterImageSourceSnapshot = characterImageSourceSnapshot,
    backgroundImageSourceSnapshot = backgroundImageSourceSnapshot,
    characterImagePath = characterImagePath,
    characterImageMime = characterImageMime,
    characterImageSha256 = characterImageSha256,
    backgroundImagePath = backgroundImagePath,
    backgroundImageMime = backgroundImageMime,
    backgroundImageSha256 = backgroundImageSha256,
    modelVariant = modelVariant.storageKey,
    resolution = resolution.storageKey,
    ratio = ratio.storageKey,
    durationSeconds = durationSeconds,
    generateAudio = generateAudio,
    watermark = watermark,
    state = state.storageKey,
    remoteStatus = remoteStatus,
    generationAttempt = generationAttempt,
    submissionAttemptId = submissionAttemptId,
    submissionStartedAt = submissionStartedAt,
    requestFingerprint = requestFingerprint,
    remoteTaskId = remoteTaskId,
    remoteVideoUrl = remoteVideoUrl,
    remoteVideoUrlObservedAt = remoteVideoUrlObservedAt,
    remoteVideoUrlExpiresAt = remoteVideoUrlExpiresAt,
    remoteRequestId = remoteRequestId,
    previousRemoteTasksJson = previousRemoteTasksJson,
    localVideoPath = localVideoPath,
    videoMime = videoMime,
    videoByteSize = videoByteSize,
    videoSha256 = videoSha256,
    downloadedAt = downloadedAt,
    automaticRetryCount = automaticRetryCount,
    nextRetryAt = nextRetryAt,
    errorStage = errorStage,
    errorCode = errorCode,
    errorMessage = errorMessage,
    retryDisposition = retryDisposition,
    requiresCostConfirmation = requiresCostConfirmation,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
