package com.rhodesisland.terminal.data.remote

import android.util.Log
import com.rhodesisland.terminal.data.model.SeedanceConfig
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.util.seedanceUserErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

/**
 * 取消端点是否已由官方文档复核。
 *
 * 已于实施时复核官方文档：取消排队任务为 `DELETE {baseUrl}/contents/generations/tasks/{id}`
 * （仅 queued 状态可取消，成功后无响应体；succeeded/failed/expired 为删除记录）。
 * 来源：火山方舟文档「取消或删除视频生成任务」(docs.volcengine.com/docs/82379/1521720) 及
 * volcengine Go/Python SDK 的 DeleteContentGenerationTask 操作。
 */
const val CANCEL_ENDPOINT_VERIFIED = true

/** Seedance 任务集合接口路径后缀（POST 创建任务的相对路径）。 */
internal const val SEEDANCE_TASKS_SUFFIX = "/contents/generations/tasks"

/** 中转站媒体协议单张参考图（base64 解码后）大小上限（10MB，对齐中转站文档）。 */
internal const val MEDIA_REFERENCE_MAX_BYTES = 10L * 1024 * 1024

/** 中转站媒体协议缺省模型 ID（dm1124/灵科 Seedance 2.0 参考生视频）。 */
internal const val DEFAULT_RELAY_MODEL_ID = "kwvideo-v2-ref"

/**
 * Seedance 服务端协议：
 *  - [ARK]：火山方舟官方 contents/generations/tasks；
 *  - [MEDIA_RELAY]：中转站媒体协议 POST /v1/media/generate + GET /v1/media/status。
 */
internal enum class SeedanceProtocol { ARK, MEDIA_RELAY }

/**
 * 依据「服务地址」形态识别协议：
 *  - 路径含 `/media/generate` → 媒体协议（中转站完整「创建任务」地址）；
 *  - 已知中转站主机（api.lk888.ai / api.lingkeai.ai / dm1124.com / lingkeai 系）且路径为空或 `/v1` → 媒体协议；
 *  - 其余（官方 base、任意完整资源路径）→ 方舟协议，行为与旧版完全一致。
 */
internal fun seedanceProtocolFor(baseUrl: String): SeedanceProtocol {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return SeedanceProtocol.ARK
    val afterScheme = trimmed.substringAfter("://", "")
    val host = afterScheme.substringBefore('/').lowercase()
    val path = afterScheme.substringAfter('/', "").trim('/')
    if ("/$path".contains("/media/generate")) return SeedanceProtocol.MEDIA_RELAY
    val bareOrV1 = path.isBlank() || path == "v1"
    if (bareOrV1 && host in MEDIA_RELAY_HOSTS) return SeedanceProtocol.MEDIA_RELAY
    return SeedanceProtocol.ARK
}

/** 已知中转站主机（仅当路径为空或 `/v1` 时判为媒体协议，避免误伤自建方舟网关）。 */
private val MEDIA_RELAY_HOSTS = setOf(
    "api.lk888.ai", "api.lingkeai.ai", "dm1124.com",
    "lingkeai.vip", "www.lingkeai.vip",
)

/**
 * 归一化中转站「创建任务」接口地址（媒体协议）：
 *  - 已含 `/media/generate` 的完整地址原样使用；
 *  - 裸主机或 `/v1` 自动补 `/v1/media/generate`；
 *  - 其它带路径地址原样使用（调用方负责保证其正确）。
 */
internal fun resolveMediaGenerateEndpoint(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return trimmed
    if (trimmed.contains("/media/generate")) return trimmed
    val scheme = trimmed.substringBefore("://", "").ifBlank { "https" }
    val afterScheme = if (trimmed.contains("://")) trimmed.substringAfter("://") else trimmed
    val host = afterScheme.substringBefore('/')
    val path = afterScheme.substringAfter('/', "").trim('/')
    return if (path.isBlank() || path == "v1") "$scheme://$host/v1/media/generate" else trimmed
}

/** 归一化中转站「任务状态查询」接口地址：由创建地址把 `/media/generate` 替换为 `/media/status`。 */
internal fun resolveMediaStatusEndpoint(baseUrl: String): String =
    resolveMediaGenerateEndpoint(baseUrl).replace("/media/generate", "/media/status")

/**
 * 归一化用户填写的“服务地址”为任务集合接口地址。
 *
 * 按路径形态区分两类填写方式：
 *  - **官方 base 形态**（仅主机、`/api`、`/vN`、`/api/vN`，如 `https://ark.cn-beijing.volces.com/api/v3`）：
 *    自动拼接 `[SEEDANCE_TASKS_SUFFIX]`；
 *  - **带具体资源路径的完整接口地址**（如中转站 `https://xxx/v1/media/generate`）：
 *    原样作为“创建任务”接口使用，不再追加，避免拼出错误路径导致 404。
 *
 * 已以 `[SEEDANCE_TASKS_SUFFIX]` 结尾的地址同样原样使用（防双拼）。
 */
internal fun resolveSeedanceTaskCollectionEndpoint(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return trimmed
    if (trimmed.endsWith(SEEDANCE_TASKS_SUFFIX)) return trimmed
    return if (isKnownBaseUrl(trimmed)) trimmed + SEEDANCE_TASKS_SUFFIX else trimmed
}

/** 是否为“官方 base 形态”（仅主机/根、`/api`、`/vN`、`/api/vN`）；其余带资源路径视为完整接口地址。 */
private fun isKnownBaseUrl(url: String): Boolean {
    val afterScheme = url.substringAfter("://", "")
    val path = afterScheme.substringAfter('/', "")
    val p = "/" + path.trimEnd('/')
    return path.isBlank() || p == "/api" || BASE_PATH_PATTERNS.any { it.matches(p) }
}

/** 官方 base 形态的路径模式（版本/API 前缀，需自动补任务后缀）。 */
private val BASE_PATH_PATTERNS = listOf(Regex("^/v\\d+$"), Regex("^/api/v\\d+$"))

/** 单个任务接口地址：集合接口 + `/{taskId}`（GET 查询 / DELETE 取消共用）。 */
internal fun resolveSeedanceTaskEndpoint(baseUrl: String, taskId: String): String =
    resolveSeedanceTaskCollectionEndpoint(baseUrl) + "/" + taskId

/**
 * “测试连接”结果（设置页用）：区分接口正常 / 地址或路径问题，用户可直接看到中文结论。
 */
sealed interface SeedanceProbeResult {
    /** 接口可达且路径正确（探测不发任务、不产生费用）。 */
    data class Ok(val message: String) : SeedanceProbeResult
    /** 不可达或配置有问题，需用户调整。 */
    data class Failed(val message: String) : SeedanceProbeResult
}

/**
 * 预编码的参考图内容：调用方已读图并编码，客户端只负责拼接 data URL。
 *
 * @property mimeType 图片 MIME（如 "image/png"），不含 "data:" 前缀；
 * @property base64NoPrefix 无 "data:image/...;base64," 前缀的 base64 正文。
 */
data class SeedanceImageContent(
    val mimeType: String,
    val base64NoPrefix: String,
)

/**
 * 一次 Seedance 视频生成任务输入（客户端入参，非序列化 DTO）。
 *
 * 图片已预编码为 [SeedanceImageContent]；[character] 必填，[background] 可选。
 * [variant]/[resolution]/[ratio]/[durationSeconds]/[watermark] 为本次任务实际采用的生成参数。
 */
data class CreateSeedanceTask(
    val finalPrompt: String,
    val character: SeedanceImageContent,
    val background: SeedanceImageContent?,
    val variant: SeedanceModelVariant,
    val resolution: SeedanceResolution,
    val ratio: SeedanceRatio,
    val durationSeconds: Int,
    val watermark: Boolean,
)

/**
 * Seedance 2.0 视频生成任务客户端（火山方舟 contents/generations/tasks）。
 *
 * - [createTask]：`POST {baseUrl}/contents/generations/tasks`；
 * - [getTask]：`GET {baseUrl}/contents/generations/tasks/{id}`；
 * - [cancelQueuedTask]：`DELETE {baseUrl}/contents/generations/tasks/{id}`（仅 queued 可取消，见
 *   [CANCEL_ENDPOINT_VERIFIED]）。
 *
 * 与 [DirectLlmClient] 并行、独立：不复用其 buildEndpoint，也不经 ChatProvider 转发。
 * 使用构造注入的专用有限超时 OkHttp 客户端（不使用 RetrofitClient.streamingClient，后者无超时）。
 * 协程取消经 `invokeOnCompletion { call.cancel() }` 传播到底层 [okhttp3.Call]。
 */
@OptIn(ExperimentalSerializationApi::class) // explicitNulls=false：省略空字段，保持 content 项无 null 字段
class SeedanceClient(
    private val client: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    },
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** 提交创建任务。请求体不含 fps/seed/camera；`generate_audio` 恒为 true。 */
    suspend fun createTask(config: SeedanceConfig, request: CreateSeedanceTask): SeedanceTaskResponse {
        if (seedanceProtocolFor(config.baseUrl) == SeedanceProtocol.MEDIA_RELAY) {
            return createMediaTask(config, request)
        }
        val body = json.encodeToString(CreateSeedanceTaskRequest.serializer(), buildCreateRequest(config, request))
        val httpRequest = Request.Builder()
            .url(resolveSeedanceTaskCollectionEndpoint(config.baseUrl))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return execute(httpRequest, taskId = null)
    }

    /** 查询任务状态/结果。 */
    suspend fun getTask(config: SeedanceConfig, taskId: String): SeedanceTaskResponse {
        if (seedanceProtocolFor(config.baseUrl) == SeedanceProtocol.MEDIA_RELAY) {
            return getMediaTask(config, taskId)
        }
        val httpRequest = Request.Builder()
            .url(resolveSeedanceTaskEndpoint(config.baseUrl, taskId))
            .header("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()
        return execute(httpRequest, taskId = taskId)
    }

    /**
     * 取消排队中的任务（官方 DELETE）。
     *
     * 仅远端状态为 queued 时才有取消语义；成功返回空体时合成 [SeedanceRemoteStatus.CANCELLED]。
     * 中转站媒体协议未提供取消端点：抛 [UnsupportedOperationException]，
     * 由协调器兜底转为继续轮询（以服务端状态为准）。
     */
    suspend fun cancelQueuedTask(config: SeedanceConfig, taskId: String): SeedanceTaskResponse {
        if (seedanceProtocolFor(config.baseUrl) == SeedanceProtocol.MEDIA_RELAY) {
            throw UnsupportedOperationException("media relay does not support task cancellation")
        }
        val httpRequest = Request.Builder()
            .url(resolveSeedanceTaskEndpoint(config.baseUrl, taskId))
            .header("Authorization", "Bearer ${config.apiKey}")
            .delete()
            .build()
        val parsed = execute(httpRequest, taskId = taskId)
        // 官方 DELETE 成功返回空体；合成取消结果（若服务端返回了任务对象则原样透传）。
        return if (parsed.id == null && parsed.status == null) {
            parsed.copy(id = taskId, status = SeedanceRemoteStatus.CANCELLED.storageKey)
        } else {
            parsed
        }
    }

    // ===== 中转站媒体协议（POST /v1/media/generate + GET /v1/media/status）=====

    /** 媒体协议：提交创建任务，解析 `data.task_id`（数字/字符串兼容）。 */
    private suspend fun createMediaTask(
        config: SeedanceConfig,
        request: CreateSeedanceTask,
    ): SeedanceTaskResponse {
        val body = json.encodeToString(MediaGenerateRequest.serializer(), buildMediaCreateRequest(config, request))
        val httpRequest = Request.Builder()
            .url(resolveMediaGenerateEndpoint(config.baseUrl))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            val call = client.newCall(httpRequest)
            val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    val requestId = extractRequestId(response)
                    val raw = response.body?.string().orEmpty()
                    val httpOk = response.isSuccessful
                    val wrapper = parseMediaCreateResponse(raw)
                    // 业务码（HTTP 2xx 时以包装 code 为准）：code != 200 → 明确失败，绝不重发。
                    val businessCode = wrapper?.numericCode
                    if (httpOk && businessCode != null && businessCode != 200) {
                        val (remoteCode, remoteMessage) = parseErrorBody(raw)
                        // 创建阶段的业务失败是终局性的（余额不足/参数错误等），以消息标记词分类
                        // （追加原始体截断文本，覆盖 `data.失败原因` 等嵌套字段）；仅 429 视为瞬时繁忙
                        // （协调器同样不会自动重发，只影响文案）。
                        val classification = if (businessCode == 429) {
                            SeedanceError.TRANSIENT_429_5XX
                        } else {
                            classifySeedanceError(null, remoteCode, "$remoteMessage ${raw.take(2000)}")
                        }
                        Log.w(TAG, "seedance media create error http=200 businessCode=$businessCode classification=$classification")
                        throw SeedanceApiException(
                            classification = classification,
                            message = humanReadableMessage(classification, businessCode),
                            httpStatus = businessCode,
                            remoteCode = remoteCode,
                            requestId = requestId,
                            taskId = null,
                        )
                    }
                    if (!httpOk) {
                        throw buildApiError(response.code, raw, requestId, null, response.retryAfterMillis())
                    }
                    val taskId = wrapper?.taskId
                    if (taskId.isNullOrBlank()) {
                        // 成功但未返回任务 ID：返回空响应，由协调器按歧义处理（可能已产生费用）。
                        SeedanceTaskResponse(requestId = requestId)
                    } else {
                        // 创建成功即进入排队；合成 queued 驱动协调器转入轮询阶段。
                        SeedanceTaskResponse(id = taskId, status = SeedanceRemoteStatus.QUEUED.storageKey, requestId = requestId)
                    }
                }
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                Log.w(TAG, "seedance media create network error")
                throw SeedanceApiException(
                    classification = SeedanceError.AMBIGUOUS_TRANSPORT,
                    message = "网络错误，无法确认任务状态：${describeNetworkError(e)}",
                    taskId = null,
                    cause = e,
                )
            } finally {
                handle?.dispose()
                call.cancel()
            }
        }
    }

    /** 媒体协议：轮询任务状态（GET /v1/media/status?task_id=...）。 */
    private suspend fun getMediaTask(config: SeedanceConfig, taskId: String): SeedanceTaskResponse {
        val base = resolveMediaStatusEndpoint(config.baseUrl)
        val url = base.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("task_id", taskId)
            ?.build()
            ?.toString()
            ?: "$base?task_id=$taskId"
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            val call = client.newCall(httpRequest)
            val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    val requestId = extractRequestId(response)
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw buildApiError(response.code, raw, requestId, taskId, response.retryAfterMillis())
                    }
                    parseMediaStatusOrThrow(raw, response.code, requestId, taskId)
                }
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                Log.w(TAG, "seedance media status network error taskId=$taskId")
                throw SeedanceApiException(
                    classification = SeedanceError.AMBIGUOUS_TRANSPORT,
                    message = "网络错误，无法确认任务状态：${describeNetworkError(e)}",
                    taskId = taskId,
                    cause = e,
                )
            } finally {
                handle?.dispose()
                call.cancel()
            }
        }
    }

    /** 解析媒体协议创建响应包装；非 JSON / 空体返回 null（由调用方按歧义/错误处理）。 */
    private fun parseMediaCreateResponse(raw: String): MediaCreateResponse? {
        if (raw.isBlank()) return null
        return runCatching { json.decodeFromString(MediaCreateResponse.serializer(), raw) }.getOrNull()
    }

    /** 解析媒体协议状态响应；无任务字段时按错误包装分类抛出。 */
    private fun parseMediaStatusOrThrow(
        raw: String,
        httpStatus: Int,
        requestId: String?,
        taskId: String?,
    ): SeedanceTaskResponse {
        if (raw.isBlank()) return SeedanceTaskResponse()
        val payload: JsonObject = try {
            val obj = json.parseToJsonElement(raw).jsonObject
            if (obj["state"] != null) obj else (obj["data"] as? JsonObject) ?: obj
        } catch (e: Exception) {
            return SeedanceTaskResponse()
        }
        val status = runCatching { json.decodeFromJsonElement(MediaTaskStatus.serializer(), payload) }.getOrNull()
        if (status != null && (status.state != null || status.taskId != null)) {
            return mapMediaStatusToTaskResponse(status, requestId)
        }
        // 无任务字段：HTTP 2xx 下的错误包装（如「任务不存在」）。以包装 code 为有效状态码分类。
        val (remoteCode, remoteMessage) = parseErrorBody(raw)
        val wrapper = parseMediaCreateResponse(raw)
        val effectiveStatus = wrapper?.numericCode ?: httpStatus
        val classification = classifySeedanceError(effectiveStatus, remoteCode, remoteMessage)
        val finalClassification =
            if (classification == SeedanceError.OTHER) SeedanceError.NOT_FOUND else classification
        Log.w(TAG, "seedance media status error taskId=$taskId effectiveStatus=$effectiveStatus classification=$finalClassification")
        throw SeedanceApiException(
            classification = finalClassification,
            message = humanReadableMessage(finalClassification, effectiveStatus),
            httpStatus = effectiveStatus,
            remoteCode = remoteCode,
            requestId = requestId,
            taskId = taskId,
        )
    }

    /** 媒体协议创建请求：立绘+背景进 `params.images`，标准/Fast 映射「标准/快速」，P4K 映射「4K」。 */
    private fun buildMediaCreateRequest(config: SeedanceConfig, request: CreateSeedanceTask): MediaGenerateRequest {
        val images = buildList {
            add(request.character.toDataUrl())
            request.background?.let { add(it.toDataUrl()) }
        }
        return MediaGenerateRequest(
            model = config.relayModelId.ifBlank { DEFAULT_RELAY_MODEL_ID },
            prompt = request.finalPrompt,
            params = MediaGenerateParams(
                images = images,
                version = when (request.variant) {
                    SeedanceModelVariant.STANDARD -> "标准"
                    SeedanceModelVariant.FAST -> "快速"
                },
                duration = request.durationSeconds.toString(),
                aspectRatio = request.ratio.apiValue,
                resolution = mediaResolutionValue(request.resolution),
            ),
        )
    }

    /** 媒体协议分辨率映射：4K 为大写 K（与方舟协议的 "4k" 不同）。 */
    private fun mediaResolutionValue(resolution: SeedanceResolution): String = when (resolution) {
        SeedanceResolution.P480 -> "480p"
        SeedanceResolution.P720 -> "720p"
        SeedanceResolution.P1080 -> "1080p"
        SeedanceResolution.P4K -> "4K"
    }

    /**
     * 探测“服务地址”是否可达且路径正确（设置页「测试连接」用）。
     *
     * 不创建任务、不产生费用：仅 GET 一个不存在的探测任务 id。
     * 判定规则：
     *  - 2xx → 接口正常；
     *  - 401/403 → 地址可达，API Key 无效；
     *  - 429/5xx → 地址可达，服务繁忙；
     *  - 404/405 且响应体为 JSON → 路径正确（对不存在任务的预期返回）；
     *  - 404/405 且响应体非 JSON（网关 HTML 页）→ 路径可能不正确；
     *  - 连接/IO 错误 → 地址不可达。
     * 媒体协议地址探测 GET `/v1/media/status?task_id=__probe__`；方舟探测 GET 任务详情。
     */
    suspend fun probeEndpoint(config: SeedanceConfig): SeedanceProbeResult = withContext(Dispatchers.IO) {
        val probeId = "__seedance_probe_check__"
        val media = seedanceProtocolFor(config.baseUrl) == SeedanceProtocol.MEDIA_RELAY
        val base = if (media) resolveMediaStatusEndpoint(config.baseUrl) else resolveSeedanceTaskEndpoint(config.baseUrl, probeId)
        val url = if (media) {
            base.toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("task_id", probeId)?.build()?.toString()
                ?: "$base?task_id=$probeId"
        } else {
            base
        }
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()
        val call = client.newCall(httpRequest)
        val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                val status = response.code
                val raw = response.body?.string().orEmpty()
                when {
                    status in 200..299 -> SeedanceProbeResult.Ok("接口正常，服务地址可用")
                    status == 401 || status == 403 ->
                        SeedanceProbeResult.Failed("接口可达，但 API Key 无效或未授权")
                    status == 429 || status >= 500 ->
                        SeedanceProbeResult.Failed("接口可达，但服务暂时繁忙，请稍后重试")
                    status == 404 || status == 405 -> {
                        // 路径正确时，对不存在的探测任务服务端返回 JSON 错误体（如「任务不存在」）；
                        // 路径错误（被网关拦下）则通常是 HTML/空体。
                        val jsonBody = raw.isNotBlank() &&
                            (raw.trimStart().startsWith("{") || raw.trimStart().startsWith("["))
                        if (jsonBody) {
                            SeedanceProbeResult.Ok("接口可达，路径正确（探测任务返回预期结果）")
                        } else {
                            val hint = if (media) {
                                "中转站地址请填写完整「创建任务」接口（如 https://api.lk888.ai/v1/media/generate），或直接填该站点主机"
                            } else {
                                "官方地址填 base（含 /api/v3）；中转站请粘贴完整的「创建任务」接口地址（如 https://xxx/v1/media/generate），不要只填主机或 /v1"
                            }
                            SeedanceProbeResult.Failed("接口可达，但路径可能不正确：$hint")
                        }
                    }
                    else -> SeedanceProbeResult.Failed("接口可达，但返回异常，请检查服务地址")
                }
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive() // 被取消（超时/页面离开）时抛 CancellationException
            Log.w(TAG, "seedance probe network error")
            SeedanceProbeResult.Failed("无法连接服务，请检查地址与网络")
        } finally {
            handle?.dispose()
            call.cancel()
        }
    }

    private suspend fun execute(request: Request, taskId: String?): SeedanceTaskResponse =
        withContext(Dispatchers.IO) {
            val call = client.newCall(request)
            // 复用 DirectLlmClient 的取消模式：在当前协程 Job 上注册 invokeOnCompletion，
            // 协程取消时关闭底层 Call；finally 兜底再 cancel，确保 Call 一定被释放。
            val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    val requestId = extractRequestId(response)
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw buildApiError(response.code, raw, requestId, taskId, response.retryAfterMillis())
                    }
                    parseResponse(raw).copy(requestId = requestId)
                }
            } catch (e: IOException) {
                coroutineContext.ensureActive() // 被取消时抛 CancellationException，不误判为传输错误
                Log.w(TAG, "seedance network error taskId=$taskId")
                throw SeedanceApiException(
                    classification = SeedanceError.AMBIGUOUS_TRANSPORT,
                    message = "网络错误，无法确认任务状态：${describeNetworkError(e)}",
                    taskId = taskId,
                    cause = e,
                )
            } finally {
                handle?.dispose()
                call.cancel()
            }
        }

    private fun buildApiError(
        httpStatus: Int,
        raw: String,
        requestId: String?,
        taskId: String?,
        retryAfterMillis: Long?,
    ): SeedanceApiException {
        val (code, message) = parseErrorBody(raw)
        val classification = classifySeedanceError(httpStatus, code, message)
        // 仅记录非敏感元数据：任务 ID、HTTP 状态、request-id、分类后的错误码。
        Log.w(TAG, "seedance task error taskId=$taskId http=$httpStatus requestId=$requestId classification=$classification")
        return SeedanceApiException(
            classification = classification,
            message = humanReadableMessage(classification, httpStatus),
            httpStatus = httpStatus,
            remoteCode = code,
            requestId = requestId,
            taskId = taskId,
            retryAfterMillis = retryAfterMillis,
        )
    }

    /** 解析 Retry-After 头（秒 → 毫秒）；缺失或非数值时返回 null。 */
    private fun Response.retryAfterMillis(): Long? =
        header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { it * 1_000L }

    private fun buildCreateRequest(config: SeedanceConfig, request: CreateSeedanceTask): CreateSeedanceTaskRequest {
        val content = mutableListOf<SeedanceContentPart>(
            SeedanceContentPart(type = "text", text = request.finalPrompt),
            SeedanceContentPart(
                type = "image_url",
                role = "reference_image",
                imageUrl = SeedanceImageUrl(url = request.character.toDataUrl()),
            ),
        )
        request.background?.let {
            content += SeedanceContentPart(
                type = "image_url",
                role = "reference_image",
                imageUrl = SeedanceImageUrl(url = it.toDataUrl()),
            )
        }
        return CreateSeedanceTaskRequest(
            model = request.variant.modelId,
            content = content,
            resolution = request.resolution.apiValue(),
            ratio = request.ratio.apiValue,
            duration = request.durationSeconds,
            generateAudio = config.generateAudio,
            watermark = request.watermark,
        )
    }

    private fun parseResponse(raw: String): SeedanceTaskResponse {
        if (raw.isBlank()) return SeedanceTaskResponse()
        return try {
            json.decodeFromString(SeedanceTaskResponse.serializer(), raw)
        } catch (e: Exception) {
            // 非 JSON / 未知形状：保守返回空响应，不因字段缺失崩溃。
            SeedanceTaskResponse()
        }
    }

    /** 从错误体提取 (code, message)，非 JSON 或缺失时回落 null。兼容 `{code,msg}` 与 `{error:{code,message}}`。 */
    private fun parseErrorBody(raw: String): Pair<String?, String?> {
        if (raw.isBlank()) return null to null
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            val err = obj["error"]
            val code = when (err) {
                is JsonObject -> err["code"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
            val message = when (err) {
                is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
            (code ?: obj["code"]?.jsonPrimitive?.contentOrNull) to
                (message ?: obj["message"]?.jsonPrimitive?.contentOrNull ?: obj["msg"]?.jsonPrimitive?.contentOrNull)
        } catch (e: Exception) {
            null to null
        }
    }

    private fun extractRequestId(response: Response): String? =
        REQUEST_ID_HEADERS.firstNotNullOfOrNull { response.header(it) }

    private fun SeedanceImageContent.toDataUrl(): String = "data:$mimeType;base64,$base64NoPrefix"

    private fun SeedanceResolution.apiValue(): String = when (this) {
        SeedanceResolution.P480 -> "480p"
        SeedanceResolution.P720 -> "720p"
        SeedanceResolution.P1080 -> "1080p"
        SeedanceResolution.P4K -> "4k"
    }

    /** 把底层 IOException 分类为具体中文原因，便于用户/开发直接定位网络层问题。 */
    private fun describeNetworkError(e: IOException): String {
        val type = when (e) {
            is SocketTimeoutException -> "连接或读取超时"
            is UnknownHostException -> "无法解析服务器地址（DNS 失败或域名被墙）"
            is ConnectException -> "无法连接到服务器（连接被拒绝或端口不通）"
            is SSLException -> "安全连接失败（TLS/证书问题）"
            else -> "网络异常"
        }
        val detail = e.message?.take(120)?.let { "：$it" } ?: ""
        return "$type$detail"
    }

    /** 面向用户的中文可读文案，绝不携带 API Key / base64 / 签名 URL / 服务端原始消息。 */
    private fun humanReadableMessage(classification: SeedanceError, httpStatus: Int): String =
        seedanceUserErrorMessage(classification)

    companion object {
        private const val TAG = "SeedanceClient"

        /** request-id 候选响应头（OkHttp header 匹配大小写不敏感）。 */
        private val REQUEST_ID_HEADERS = listOf("X-Request-Id", "Request-Id", "X-Tt-Logid", "x-trace-id")
    }
}
