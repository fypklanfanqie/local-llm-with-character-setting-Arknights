package com.rhodesisland.terminal.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 手动重试的通用准备：按目标入口状态 [entryState] 决定是否走「重新生成」语义。
 *
 * 仅当 [entryState] == [SeedanceVideoState.SUBMISSION_PENDING]（FAILED_SUBMISSION /
 * FAILED_REMOTE / EXPIRED 重试）才归档当前 [SeedanceVideo.remoteTaskId] 进
 * [SeedanceVideo.previousRemoteTasksJson]（JSON 字符串数组；解析失败/脏数据时从空数组
 * 重新开始，绝不让坏值阻塞重试）并 [SeedanceVideo.generationAttempt] += 1。
 *
 * 其余重试（FAILED_QUERY -> QUEUED 继续查询、FAILED_DOWNLOAD -> DOWNLOAD_PENDING
 * 重新下载、FAILED_SNAPSHOT -> SNAPSHOT_PENDING / FAILED_PROMPT -> PROMPT_PENDING）不归档
 * 也不加次数：QUERY/DOWNLOAD 复用的是仍在生效的同一 remoteTaskId，SNAPSHOT/PROMPT 从未
 * 创建过远端任务。
 *
 * [SeedanceVideo.automaticRetryCount] = 0 与 [SeedanceVideo.requiresCostConfirmation] = false
 * 对**所有**手动重试无条件重置（用户已确认才走到手动重试，无需再卡一次费用确认）。
 *
 * 供 [com.rhodesisland.terminal.ui.chat.ChatViewModel.retryVideoTask] 与
 * [com.rhodesisland.terminal.ui.video.EncounterViewModel.retryTask] 共用。
 */
internal fun SeedanceVideo.prepareRetry(entryState: SeedanceVideoState): SeedanceVideo {
    val regenerate = entryState == SeedanceVideoState.SUBMISSION_PENDING
    val archived = if (regenerate && !remoteTaskId.isNullOrBlank()) {
        val existing = runCatching { Json.decodeFromString<List<String>>(previousRemoteTasksJson) }
            .getOrElse { emptyList() }
        Json.encodeToString(existing + remoteTaskId)
    } else {
        previousRemoteTasksJson
    }
    return copy(
        previousRemoteTasksJson = archived,
        generationAttempt = if (regenerate) generationAttempt + 1 else generationAttempt,
        automaticRetryCount = 0,
        requiresCostConfirmation = false,
    )
}

/**
 * Seedance 视频任务的持久化状态机（Room `seedance_video.state` 列取值）。
 *
 * 每个状态以 [storageKey] 持久化；[fromStorageKey] 还原时对未知/空值保守回落 [DEFAULT]，
 * 避免历史脏值导致崩溃或误触发自动提交。
 *
 * 合法转换集中定义于 [com.rhodesisland.terminal.video.canTransition]，此处只声明状态集合。
 * 领域数据类 [SeedanceVideo] 与本文件同包（Task 2 引入，与 Room 映射一起落地）。
 */
enum class SeedanceVideoState(val storageKey: String) {
    /** 待复制角色图/背景图快照（outbox 落库初始态）。 */
    SNAPSHOT_PENDING("snapshot_pending"),
    /** 待生成视频提示词。 */
    PROMPT_PENDING("prompt_pending"),
    /** 提示词生成中。 */
    PROMPTING("prompting"),
    /** 待提交远端创建任务。 */
    SUBMISSION_PENDING("submission_pending"),
    /** 远端创建任务提交中。 */
    SUBMITTING("submitting"),
    /** 远端任务排队中。 */
    QUEUED("queued"),
    /** 远端任务生成中。 */
    RUNNING("running"),
    /** 已请求取消（仅 QUEUED 可发起），结果以服务端状态为准。 */
    CANCEL_REQUESTED("cancel_requested"),
    /** 待下载成品视频。 */
    DOWNLOAD_PENDING("download_pending"),
    /** 成品视频下载中。 */
    DOWNLOADING("downloading"),
    /** 终态：视频已下载校验并归档，可播放。 */
    READY("ready"),
    /** 终态：远端任务已取消。 */
    CANCELLED("cancelled"),
    /** 远端任务过期（需用户确认后重新提交）。 */
    EXPIRED("expired"),
    /** 快照复制失败（修复角色图后可手动重试）。 */
    FAILED_SNAPSHOT("failed_snapshot"),
    /** 提示词生成失败。 */
    FAILED_PROMPT("failed_prompt"),
    /** 当前模型/基地址配置与任务快照不一致，拒绝静默换模型。 */
    FAILED_PROMPT_CONFIG_CHANGED("failed_prompt_config_changed"),
    /** 提交失败或结果不确定（AMBIGUOUS_POST），绝不自动重发。 */
    FAILED_SUBMISSION("failed_submission"),
    /** 远端模型生成失败。 */
    FAILED_REMOTE("failed_remote"),
    /** 查询远端状态失败。 */
    FAILED_QUERY("failed_query"),
    /** 下载失败。 */
    FAILED_DOWNLOAD("failed_download");

    companion object {
        /**
         * 未知状态值保守回落 [FAILED_SUBMISSION]：既不冒充 [READY] 播放未校验文件，
         * 也不会被 Worker 自动认领而产生重复提交，等待用户确认后再行动。
         */
        val DEFAULT: SeedanceVideoState = FAILED_SUBMISSION

        /** 从存储键还原；未知/空值保守回落 [DEFAULT]。 */
        fun fromStorageKey(value: String?): SeedanceVideoState =
            entries.firstOrNull { it.storageKey == value } ?: DEFAULT
    }
}

/**
 * Seedance 视频任务领域模型。
 *
 * 与 Room 实体 [com.rhodesisland.terminal.data.local.SeedanceVideoEntity] 一一对应（映射见
 * [com.rhodesisland.terminal.data.repository.SeedanceVideoRepository]）：枚举字段以存储键往返，
 * 其余字段 Kotlin 类型原样透传；[previousRemoteTasksJson] 为归档 JSON 原文，不做解析。
 */
data class SeedanceVideo(
    val id: Long,
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
    /** 角色图来源快照：内置 asset key 或内部文件路径。 */
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
    // ===== 生成参数快照 =====
    val modelVariant: SeedanceModelVariant,
    val resolution: SeedanceResolution,
    val ratio: SeedanceRatio,
    val durationSeconds: Int,
    /** 固定开启：Seedance 2.0 生成音频不可关闭。 */
    val generateAudio: Boolean,
    val watermark: Boolean,
    // ===== 状态机与远端任务 =====
    val state: SeedanceVideoState,
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
