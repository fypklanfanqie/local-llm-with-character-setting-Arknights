package com.rhodesisland.terminal.video

import com.rhodesisland.terminal.data.model.SeedanceConfig
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.data.model.SeedanceVideoState
import com.rhodesisland.terminal.data.remote.SeedanceProtocol
import com.rhodesisland.terminal.data.remote.seedanceProtocolFor

/**
 * Seedance 视频请求校验结果。
 */
sealed interface SeedanceValidationResult {
    /** 校验通过。 */
    data object Valid : SeedanceValidationResult

    /** 校验失败，[message] 为可直接展示的中文原因。 */
    data class Invalid(val message: String) : SeedanceValidationResult
}

/**
 * 校验一次 Seedance 视频生成请求：
 *  - [SeedanceConfig.baseUrl]/[SeedanceConfig.apiKey] 非空；
 *  - 中转站媒体协议下 [SeedanceConfig.relayModelId] 非空；
 *  - 时长在所选模型的 [SeedanceModelVariant.minDurationSeconds]..[maxDurationSeconds] 之间；
 *  - 分辨率在所选模型的 [SeedanceModelVariant.supportedResolutions] 内（Fast 仅 480p/720p，
 *    中转站「快速/Mini」版本同样仅支持 480p/720p，与现有约束一致）；
 *  - 角色立绘路径非空。
 * 背景图与场景描述可选，不参与校验。
 */
fun validateSeedanceRequest(
    config: SeedanceConfig,
    characterImagePath: String,
): SeedanceValidationResult {
    if (config.baseUrl.isBlank()) {
        return SeedanceValidationResult.Invalid("必须配置 Seedance 服务地址")
    }
    if (config.apiKey.isBlank()) {
        return SeedanceValidationResult.Invalid("必须配置 Seedance API Key")
    }
    if (seedanceProtocolFor(config.baseUrl) == SeedanceProtocol.MEDIA_RELAY && config.relayModelId.isBlank()) {
        return SeedanceValidationResult.Invalid("必须填写中转站模型 ID（如 kwvideo-v2-ref）")
    }
    val min = config.variant.minDurationSeconds
    val max = config.variant.maxDurationSeconds
    if (config.durationSeconds !in min..max) {
        return SeedanceValidationResult.Invalid("视频时长必须在 $min-$max 秒之间")
    }
    if (config.resolution !in config.variant.supportedResolutions) {
        return SeedanceValidationResult.Invalid(
            "该模型仅支持 ${config.variant.supportedResolutions.map { it.name }.joinToString("、")} 分辨率"
        )
    }
    if (characterImagePath.isBlank()) {
        return SeedanceValidationResult.Invalid("必须提供角色立绘图片")
    }
    return SeedanceValidationResult.Valid
}

/**
 * 判断 [from] → [to] 是否为合法状态转换（计划「持久化状态机」关键转换表）。
 *
 * 规则要点：
 *  - 流水线单向推进：SNAPSHOT_PENDING → PROMPT_* → SUBMISSION_* → QUEUED/RUNNING → DOWNLOAD_* → READY；
 *  - [SeedanceVideoState.READY]/[SeedanceVideoState.CANCELLED] 为终态，不允许任何迁出；
 *  - SUBMITTING 结果不确定时进入 [SeedanceVideoState.FAILED_SUBMISSION]（AMBIGUOUS_POST），
 *    结构上只能回到 SUBMISSION_PENDING 等待用户确认，绝不自动重发；
 *  - 各 FAILED_* 仅允许按计划规定的路径重试：快照/提示词失败回各自 PENDING，
 *    远端失败/过期经用户确认后回 SUBMISSION_PENDING（保留提示词与参考快照），
 *    查询/下载失败可自动重试；
 *  - PROMPTING → PROMPT_PENDING 与 DOWNLOADING → DOWNLOAD_PENDING 仅用于进程中断恢复重置。
 */
fun canTransition(from: SeedanceVideoState, to: SeedanceVideoState): Boolean = when (from) {
    SeedanceVideoState.SNAPSHOT_PENDING ->
        to in setOf(SeedanceVideoState.PROMPT_PENDING, SeedanceVideoState.FAILED_SNAPSHOT)
    SeedanceVideoState.PROMPT_PENDING ->
        to in setOf(SeedanceVideoState.PROMPTING, SeedanceVideoState.FAILED_PROMPT_CONFIG_CHANGED)
    SeedanceVideoState.PROMPTING -> to in setOf(
        SeedanceVideoState.SUBMISSION_PENDING,
        SeedanceVideoState.PROMPT_PENDING, // 中断恢复重置
        SeedanceVideoState.FAILED_PROMPT,
        SeedanceVideoState.FAILED_PROMPT_CONFIG_CHANGED,
    )
    SeedanceVideoState.SUBMISSION_PENDING -> to == SeedanceVideoState.SUBMITTING
    SeedanceVideoState.SUBMITTING -> to in setOf(
        SeedanceVideoState.QUEUED,
        SeedanceVideoState.RUNNING,
        SeedanceVideoState.DOWNLOAD_PENDING,
        SeedanceVideoState.FAILED_SUBMISSION, // AMBIGUOUS_POST
        SeedanceVideoState.FAILED_REMOTE,     // 创建即失败
    )
    SeedanceVideoState.QUEUED -> to in setOf(
        SeedanceVideoState.RUNNING,
        SeedanceVideoState.CANCEL_REQUESTED,
        SeedanceVideoState.DOWNLOAD_PENDING,
        SeedanceVideoState.FAILED_REMOTE,
        SeedanceVideoState.FAILED_QUERY,
        SeedanceVideoState.EXPIRED,
    )
    SeedanceVideoState.RUNNING -> to in setOf(
        SeedanceVideoState.DOWNLOAD_PENDING,
        SeedanceVideoState.FAILED_REMOTE,
        SeedanceVideoState.FAILED_QUERY,
        SeedanceVideoState.EXPIRED,
    )
    // 取消与开始运行竞态时重新 GET，以服务端状态为准。
    SeedanceVideoState.CANCEL_REQUESTED -> to in setOf(
        SeedanceVideoState.CANCELLED,
        SeedanceVideoState.RUNNING,
        SeedanceVideoState.DOWNLOAD_PENDING,
        SeedanceVideoState.FAILED_QUERY,
    )
    SeedanceVideoState.DOWNLOAD_PENDING ->
        to in setOf(SeedanceVideoState.DOWNLOADING, SeedanceVideoState.EXPIRED)
    SeedanceVideoState.DOWNLOADING -> to in setOf(
        SeedanceVideoState.READY,
        SeedanceVideoState.FAILED_DOWNLOAD,
        SeedanceVideoState.EXPIRED,
        SeedanceVideoState.DOWNLOAD_PENDING, // 中断恢复重置
    )
    SeedanceVideoState.READY, SeedanceVideoState.CANCELLED -> false
    SeedanceVideoState.EXPIRED -> to == SeedanceVideoState.SUBMISSION_PENDING
    SeedanceVideoState.FAILED_SNAPSHOT -> to == SeedanceVideoState.SNAPSHOT_PENDING
    SeedanceVideoState.FAILED_PROMPT -> to == SeedanceVideoState.PROMPT_PENDING
    SeedanceVideoState.FAILED_PROMPT_CONFIG_CHANGED -> to == SeedanceVideoState.PROMPT_PENDING
    SeedanceVideoState.FAILED_SUBMISSION -> to == SeedanceVideoState.SUBMISSION_PENDING
    SeedanceVideoState.FAILED_REMOTE -> to == SeedanceVideoState.SUBMISSION_PENDING
    // 继续查询复用同一 remoteTaskId，以服务端状态为准。
    SeedanceVideoState.FAILED_QUERY -> to in setOf(
        SeedanceVideoState.QUEUED,
        SeedanceVideoState.RUNNING,
        SeedanceVideoState.DOWNLOAD_PENDING,
    )
    // 继续下载不重新生成；URL 失效先 GET 刷新，仍过期才进入 EXPIRED。
    SeedanceVideoState.FAILED_DOWNLOAD ->
        to in setOf(SeedanceVideoState.DOWNLOAD_PENDING, SeedanceVideoState.EXPIRED)
}
