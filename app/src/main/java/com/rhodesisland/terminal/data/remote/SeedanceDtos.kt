package com.rhodesisland.terminal.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Seedance contents/generations/tasks 远端任务状态（官方取值）。
 *
 * 持久化/映射用 [storageKey]（= 请求原文值）；[fromStorageKey] 对未知值保守回落 [DEFAULT]。
 */
enum class SeedanceRemoteStatus(val storageKey: String) {
    /** 排队中。 */
    QUEUED("queued"),
    /** 生成中。 */
    RUNNING("running"),
    /** 已取消。 */
    CANCELLED("cancelled"),
    /** 生成成功。 */
    SUCCEEDED("succeeded"),
    /** 生成失败。 */
    FAILED("failed"),
    /** 已过期。 */
    EXPIRED("expired");

    companion object {
        /** 未知状态保守回落 [FAILED]：绝不误判为成功/就绪。 */
        val DEFAULT: SeedanceRemoteStatus = FAILED

        /** 从原文状态还原；未知/空值保守回落 [DEFAULT]。 */
        fun fromStorageKey(value: String?): SeedanceRemoteStatus =
            entries.firstOrNull { it.storageKey == value } ?: DEFAULT
    }
}

/**
 * Seedance 任务错误分类（供上层决定重试策略与用户文案）。
 *
 * - [SENSITIVE_CONTENT]：内容审核不通过，需用户修改提示词/参考图；
 * - [QUOTA_EXCEEDED]：额度/并发/限流超限；
 * - [AUTH]：API Key 无效或未授权（HTTP 401/403 或鉴权错误码）；
 * - [INVALID_PARAMETER]：请求参数不合法（HTTP 400/422 或参数错误码）；
 * - [BAD_ENDPOINT]：服务地址/路径不正确（HTTP 404/405 且响应体为空/HTML，即网关或路由层 404）；
 * - [NOT_FOUND]：模型或任务不存在（HTTP 404/405 且响应体为结构化错误，API 层已理解请求）；
 * - [MODEL_NOT_OPEN]：模型未在方舟控制台开通（HTTP 404，`ModelNotOpen` 类错误）；
 * - [TRANSIENT_429_5XX]：瞬时 429/5xx，可稍后重试；
 * - [AMBIGUOUS_TRANSPORT]：网络/传输层失败，无法确定任务是否已被服务端受理，绝不自动重发；
 * - [OTHER]：其余未识别错误。
 */
enum class SeedanceError {
    SENSITIVE_CONTENT,
    QUOTA_EXCEEDED,
    AUTH,
    INVALID_PARAMETER,
    BAD_ENDPOINT,
    NOT_FOUND,
    MODEL_NOT_OPEN,
    TRANSIENT_429_5XX,
    AMBIGUOUS_TRANSPORT,
    OTHER,
}

/**
 * Seedance 客户端异常：携带分类后的 [SeedanceError] 与仅用于排查的非敏感元数据。
 *
 * [message] 为面向用户的中文可读文案，绝不包含 API Key、base64 或签名视频 URL 原文。
 */
class SeedanceApiException(
    val classification: SeedanceError,
    message: String,
    val httpStatus: Int? = null,
    val remoteCode: String? = null,
    val requestId: String? = null,
    val taskId: String? = null,
    /** 服务端 Retry-After 头（秒 → 毫秒）；缺失/非数值时为 null。 */
    val retryAfterMillis: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 依据 HTTP 状态 + 远端错误码/消息分类错误。优先级（从高到低）：
 * 鉴权(401/403) → 瞬时(429/5xx) → 敏感内容 → 配额 → 参数错误 → OTHER。
 * 429/5xx 优先判为瞬时（服务端错误/限流），即使消息含 quota 字样也不误判为配额；
 * 敏感/配额/参数均来自非瞬时 4xx 响应的错误码/消息启发式。
 */
internal fun classifySeedanceError(
    httpStatus: Int?,
    remoteCode: String?,
    remoteMessage: String?,
): SeedanceError {
    if (httpStatus == 401 || httpStatus == 403) return SeedanceError.AUTH
    if (httpStatus == 429 || (httpStatus != null && httpStatus >= 500)) return SeedanceError.TRANSIENT_429_5XX
    // 404/405：带结构化错误体（有 code/message）说明 API 层已理解请求、只是资源不存在（模型/任务）；
    // 空体/HTML 说明是网关或路由层 404，即服务地址/路径错误。二者语义不同，先于正文启发式区分。
    if (httpStatus == 404 || httpStatus == 405) {
        val notFoundText = "${remoteCode.orEmpty()} ${remoteMessage.orEmpty()}".lowercase()
        if (containsAny(notFoundText, MODEL_NOT_OPEN_MARKERS)) return SeedanceError.MODEL_NOT_OPEN
        return if (remoteCode != null || remoteMessage != null) SeedanceError.NOT_FOUND
        else SeedanceError.BAD_ENDPOINT
    }

    val text = "${remoteCode.orEmpty()} ${remoteMessage.orEmpty()}".lowercase()
    if (containsAny(text, SENSITIVE_MARKERS)) return SeedanceError.SENSITIVE_CONTENT
    if (containsAny(text, QUOTA_MARKERS)) return SeedanceError.QUOTA_EXCEEDED
    if (containsAny(text, AUTH_MARKERS)) return SeedanceError.AUTH
    if (httpStatus == 400 || httpStatus == 422 || containsAny(text, PARAM_MARKERS)) {
        return SeedanceError.INVALID_PARAMETER
    }
    return SeedanceError.OTHER
}

/** 创建视频生成任务请求体（POST /contents/generations/tasks）。 */
@Serializable
data class CreateSeedanceTaskRequest(
    val model: String,
    val content: List<SeedanceContentPart>,
    val resolution: String,
    val ratio: String,
    val duration: Int,
    @SerialName("generate_audio") val generateAudio: Boolean,
    val watermark: Boolean,
)

/** content 数组单项：text 或 image_url（多模态）。空字段由 Json(explicitNulls=false) 省略。 */
@Serializable
data class SeedanceContentPart(
    val type: String,
    val text: String? = null,
    /** image_url 项的参考图角色（"reference_image"）。 */
    val role: String? = null,
    @SerialName("image_url") val imageUrl: SeedanceImageUrl? = null,
)

/** image_url 对象。 */
@Serializable
data class SeedanceImageUrl(val url: String)

/** 生成结果输出。 */
@Serializable
data class SeedanceTaskOutput(
    @SerialName("video_url") val videoUrl: String? = null,
    @SerialName("last_frame_url") val lastFrameUrl: String? = null,
)

/** 远端任务错误体（error{code,message}）。 */
@Serializable
data class SeedanceTaskError(
    val code: String? = null,
    val message: String? = null,
)

/**
 * 远端任务响应（GET 查询 / POST 创建 / DELETE 取消 共用）。
 *
 * 所有响应字段可空：服务端可能省略任意字段；未知字段被忽略，绝不因字段缺失而反序列化崩溃。
 * [requestId] 由响应头捕获（非响应体字段）。
 */
@Serializable
data class SeedanceTaskResponse(
    val id: String? = null,
    /** 远端状态原文（queued/running/cancelled/succeeded/failed/expired）。 */
    val status: String? = null,
    val output: SeedanceTaskOutput? = null,
    val error: SeedanceTaskError? = null,
    val usage: JsonElement? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
    @Transient val requestId: String? = null,
) {
    /** 类型化远端状态：原文缺失返回 null；未知值保守回落 [SeedanceRemoteStatus.FAILED]。 */
    val remoteStatus: SeedanceRemoteStatus? get() =
        status?.takeIf { it.isNotBlank() }?.let { SeedanceRemoteStatus.fromStorageKey(it) }
}

/** 敏感内容标记（错误码/消息启发式，均小写匹配）。 */
private val SENSITIVE_MARKERS = listOf(
    "sensitive", "contentreview", "content_review", "审核", "敏感", "违规",
    "moderation", "unsafe", "policy", "violate", "inappropriate",
)

/** 配额/限流标记。 */
private val QUOTA_MARKERS = listOf(
    "quota", "exceed", "insufficient", "余额", "额度", "配额", "欠费", "balance", "limit", "限额",
)

/** 鉴权标记（401/403 已由 HTTP 状态优先判定，此处兜底非标准错误码）。 */
private val AUTH_MARKERS = listOf(
    "unauthorized", "unauthenticated", "auth", "apikey", "api key", "invalid key", "credential",
    "签名", "鉴权", "凭证", "权限",
)

/** 参数错误标记。 */
private val PARAM_MARKERS = listOf(
    "invalid", "parameter", "param", "参数", "不合法", "bad request", "bad_request", "validation",
)

/** 模型未开通标记（HTTP 404，方舟 `ModelNotOpen` 类错误）。 */
private val MODEL_NOT_OPEN_MARKERS = listOf(
    "modelnotopen", "not activated", "activate the model",
)

private fun containsAny(lowercased: String, markers: List<String>): Boolean =
    markers.any { lowercased.contains(it) }

// =============================================================================================
// 中转站「媒体协议」DTO（POST /v1/media/generate + GET /v1/media/status）
// 协议出处：dm1124/灵科中转站 Seedance 2.0（kwvideo-v2-ref）接入文档。与上方方舟 DTO 互不影响。
// =============================================================================================

/** 中转站媒体协议创建任务请求体：固定三字段 model · prompt · params。 */
@Serializable
internal data class MediaGenerateRequest(
    val model: String,
    val prompt: String,
    val params: MediaGenerateParams,
)

/** 中转站媒体协议 params：可选项因模型而异；空字段由 Json(explicitNulls=false) 省略。 */
@Serializable
internal data class MediaGenerateParams(
    /** 参考图片 URL / data URL 列表（kwvideo-v2-ref：1~9 张，必填）。 */
    val images: List<String>,
    /** 速度版本（kwvideo-v2-ref：Mini / 快速 / 标准，必填）。 */
    val version: String,
    /** 视频时长字符串（kwvideo-v2-ref：auto / 4~15，必填）。 */
    val duration: String,
    /** 宽高比（adaptive / 9:16 / 16:9 / 1:1 / 3:4 / 4:3 / 21:9）。 */
    @SerialName("aspect_ratio") val aspectRatio: String? = null,
    /** 分辨率（480p / 720p / 1080p / 4K）。 */
    val resolution: String? = null,
)

/**
 * 中转站媒体协议创建任务响应：`{code, msg, data:{task_id}}` 包装。
 *
 * 字段全部可空：`code` 可能为数字或字符串；`data` 保留原样由 [MediaCreateResponse.taskId]
 * 提取 `task_id`（兼容数字/字符串两种 JSON 类型，也兼容无包装直接平铺 `task_id` 的渠道）。
 */
@Serializable
internal data class MediaCreateResponse(
    val code: JsonPrimitive? = null,
    val msg: String? = null,
    val message: String? = null,
    val data: JsonObject? = null,
    @SerialName("task_id") val flatTaskId: JsonPrimitive? = null,
) {
    /** 从 data.task_id（或顶层 task_id）提取任务 ID 文本；缺失返回 null。 */
    val taskId: String?
        get() = (data?.get("task_id")?.jsonPrimitive?.contentOrNull) ?: flatTaskId?.contentOrNull

    /** 业务码数字形式（`code` 缺失或非数值返回 null）。 */
    val numericCode: Int? get() = code?.contentOrNull?.trim()?.toIntOrNull()

    /** 错误文案：msg 优先，回落 message。 */
    val errorText: String? get() = msg?.takeIf { it.isNotBlank() } ?: message?.takeIf { it.isNotBlank() }
}

/**
 * 中转站媒体协议任务状态（GET /v1/media/status 响应）。
 *
 * 判定规则（文档原文）：终态用 `is_final === true`；成功/失败用 `state`（pending / running /
 * success / failed）；`status` / `status_group` 是中文展示字段，不参与逻辑判定。
 */
@Serializable
internal data class MediaTaskStatus(
    @SerialName("task_id") val taskId: JsonPrimitive? = null,
    val state: String? = null,
    @SerialName("is_final") val isFinal: Boolean? = null,
    val progress: String? = null,
    @SerialName("result_url") val resultUrl: String? = null,
    @SerialName("result_type") val resultType: String? = null,
    val error: String? = null,
    /** 中文展示字段，仅给人看，不用于逻辑判定。 */
    val status: String? = null,
    /** 中文展示字段，仅给人看，不用于逻辑判定。 */
    @SerialName("status_group") val statusGroup: String? = null,
)

/**
 * 把中转站任务状态映射为方舟形状的 [SeedanceTaskResponse]，供协调器复用同一套状态机。
 *
 * 映射规则（保守，绝不错判）：
 * - `success` 且 `result_url` 非空 → SUCCEEDED（可下载）；
 * - `success` 但 URL 未就绪 → RUNNING（继续轮询，避免进入「成功无产物」分支）；
 * - `failed` → FAILED（携带 [MediaTaskStatus.error] 文案）；
 * - `pending` → QUEUED，`running` → RUNNING；
 * - 未知状态：`is_final == true` 且非 success → 保守 FAILED；否则 RUNNING（继续轮询）。
 */
internal fun mapMediaStatusToTaskResponse(
    status: MediaTaskStatus,
    requestId: String?,
): SeedanceTaskResponse {
    val state = status.state?.trim()?.lowercase()
    val url = status.resultUrl?.takeIf { it.isNotBlank() }
    val remote = when {
        state == "success" && url != null -> SeedanceRemoteStatus.SUCCEEDED
        state == "success" -> SeedanceRemoteStatus.RUNNING
        state == "failed" -> SeedanceRemoteStatus.FAILED
        state == "pending" -> SeedanceRemoteStatus.QUEUED
        state == "running" -> SeedanceRemoteStatus.RUNNING
        status.isFinal == true -> SeedanceRemoteStatus.FAILED
        else -> SeedanceRemoteStatus.RUNNING
    }
    return SeedanceTaskResponse(
        id = status.taskId?.contentOrNull,
        status = remote.storageKey,
        output = if (remote == SeedanceRemoteStatus.SUCCEEDED) SeedanceTaskOutput(videoUrl = url) else null,
        error = if (remote == SeedanceRemoteStatus.FAILED) {
            SeedanceTaskError(code = "REMOTE_FAILED", message = status.error?.takeIf { it.isNotBlank() })
        } else null,
        requestId = requestId,
    )
}
