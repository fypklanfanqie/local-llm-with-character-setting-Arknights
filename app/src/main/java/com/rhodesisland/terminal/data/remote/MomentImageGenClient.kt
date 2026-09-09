package com.rhodesisland.terminal.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.i18n.L10nRuntime
import com.rhodesisland.terminal.llm.MomentPromptBuilder
import com.rhodesisland.terminal.util.MomentImageExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 朋友圈生图客户端（OpenAI 兼容出图，中转站兼容）。
 *
 * 流程：角色立绘 → 压缩 → data URL 作参考图 + 生图提示词文本 → 依次尝试：
 * 1. `/chat/completions` 聊天格式出图（nano-banana / gpt-4o-image 类中转模型）；
 *    用户把 Base URL 填成根域名（漏 /v1）时自动补 /v1 重试；
 * 2. `/v1/responses` Responses 格式兜底（gpt-image-* 类模型常只挂这个端点，
 *    聊天端点会报「not supported on the Chat Completions」；input 同样支持参考图）；
 * 3. 任务制媒体 API 兜底（lk888 类平台）：POST `{root}/media/generate` 拿 task_id
 *    → 轮询 `{root}/skills/task-status` 到 is_final → 下载 result_url 永久直链。
 *    参考图走 params.images（data URL），每张图一个任务、并发提交。
 *
 * 响应不再区分端点形态，统一交给 [MomentImageExtractor] 抽取图片（URL/base64/data URI/
 * Responses 的 result 字段）；任务制路径直接用 result_url 下载。
 *
 * 所有候选都失败时抛 [MomentImageGenException]（携带各端点的失败原因），由调用方降级纯文字发圈。
 */
class MomentImageGenClient(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    class MomentImageGenException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 生成图片并落盘。
     *
     * @param config 生图 API 配置（须 [com.rhodesisland.terminal.data.model.MomentImageGenConfig.isConfigured]）
     * @param imagePrompt 生图提示词（对话 LLM 产出的英文描述）
     * @param referenceImagePath 角色立绘本地路径或 file:///android_asset URL；空则不带参考图纯文生图
     * @param count 目标图片数（1..[AppConfig.Moment.MAX_IMAGES]）
     * @return 落盘的本地图片 URI 列表（可能少于 [count]：模型只出一张时按实际数量返回）
     */
    suspend fun generateAndSave(
        config: com.rhodesisland.terminal.data.model.MomentImageGenConfig,
        imagePrompt: String,
        referenceImagePath: String?,
        count: Int,
    ): List<String> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) throw MomentImageGenException(L10nRuntime.t("生图 API 未配置"))
        val target = count.coerceIn(1, AppConfig.Moment.MAX_IMAGES)
        val userText = MomentPromptBuilder.buildImageGenUserMessage(imagePrompt, target)
        val failures = mutableListOf<String>()

        // 1) 聊天格式端点（漏 /v1 自动补试；直填完整端点也兼容）
        for (endpoint in MomentImageEndpoints.chatCandidates(config.baseUrl)) {
            val body = buildJsonObject {
                put("model", config.model)
                put("stream", false)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildContentItems(userText, referenceImagePath, responsesStyle = false))
                    })
                })
            }
            try {
                val raw = postJson(endpoint, body.toString(), config.apiKey)
                return@withContext extractAndSave(raw, target)
            } catch (e: MomentImageGenException) {
                failures += e.message.orEmpty()
            }
        }

        // 2) Responses 端点兜底（gpt-image-* 类模型常见只挂 /v1/responses）
        for (endpoint in MomentImageEndpoints.responsesCandidates(config.baseUrl)) {
            val body = buildJsonObject {
                put("model", config.model)
                put("stream", false)
                put("input", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildContentItems(userText, referenceImagePath, responsesStyle = true))
                    })
                })
            }
            try {
                val raw = postJson(endpoint, body.toString(), config.apiKey)
                return@withContext extractAndSave(raw, target)
            } catch (e: MomentImageGenException) {
                failures += e.message.orEmpty()
            }
        }

        // 3) 任务制媒体 API 兜底（lk888 类平台：提交拿 task_id → 轮询 → result_url）
        for (submitEndpoint in MomentImageEndpoints.mediaTaskCandidates(config.baseUrl)) {
            try {
                return@withContext generateViaTaskApi(
                    submitEndpoint, config.apiKey, config.model, imagePrompt, referenceImagePath, target,
                )
            } catch (e: MomentImageGenException) {
                failures += e.message.orEmpty()
            }
        }

        throw MomentImageGenException(
            failures.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("；")
                .ifBlank { L10nRuntime.t("生图失败") },
        )
    }

    /**
     * 任务制媒体 API 生成：并发提交 [target] 个任务（平台规定一次一个任务、不支持 count），
     * 统一轮询到 is_final，收集 result_url 后下载落盘。任一任务成功即计入；全部失败抛异常
     * （含各任务错误信息；按平台规则失败任务自动退款）。
     */
    private suspend fun generateViaTaskApi(
        submitEndpoint: String,
        apiKey: String,
        model: String,
        imagePrompt: String,
        referenceImagePath: String?,
        target: Int,
    ): List<String> = coroutineScope {
        val referenceDataUrl = referenceImagePath?.let { encodeReferenceImage(it) }
        val taskPrompt = MomentPromptBuilder.buildImageGenUserMessage(imagePrompt, 1)

        val outcomes = (1..target).map {
            async {
                try {
                    val body = buildJsonObject {
                        put("model", model)
                        put("prompt", taskPrompt)
                        put("params", buildJsonObject {
                            put("size", TASK_SIZE)
                            if (referenceDataUrl != null) {
                                put("images", buildJsonArray { add(JsonPrimitive(referenceDataUrl)) })
                            }
                        })
                    }
                    SubmitOutcome(taskId = MediaTaskApi.parseSubmitResponse(postJson(submitEndpoint, body.toString(), apiKey)))
                } catch (e: Exception) {
                    SubmitOutcome(error = e.message ?: L10nRuntime.t("提交失败"))
                }
            }
        }.awaitAll()

        val errors = outcomes.mapNotNull { it.error }.toMutableList()
        val pending = outcomes.mapNotNull { it.taskId }.toMutableList()
        val urls = mutableListOf<String>()
        val statusEndpoint = MomentImageEndpoints.taskStatusEndpoint(submitEndpoint)
        val deadline = System.currentTimeMillis() + TASK_POLL_BUDGET_MS

        // 轮询：每 5s 一轮；单个任务查询瞬时失败（网关 502 等）当轮跳过，下轮再试
        while (pending.isNotEmpty() && System.currentTimeMillis() < deadline) {
            delay(TASK_POLL_INTERVAL_MS)
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val taskId = iterator.next()
                val status = try {
                    MediaTaskApi.parseTaskStatus(getJson("$statusEndpoint?task_id=$taskId", apiKey))
                } catch (e: Exception) {
                    null
                }
                when {
                    status == null || !status.isFinal -> Unit
                    status.error != null -> {
                        errors += L10nRuntime.format("任务 {0}：{1}", taskId, status.error)
                        iterator.remove()
                    }
                    status.resultUrl.isNullOrBlank() ->
                        { errors += L10nRuntime.format("任务 {0}：任务完成但未返回结果链接", taskId); iterator.remove() }
                    status.resultType != null && status.resultType != "image" ->
                        {
                            errors += L10nRuntime.format(
                                "任务 {0}：结果类型为 {1}，预期 image", taskId, status.resultType,
                            )
                            iterator.remove()
                        }
                    else -> { urls += status.resultUrl!!; iterator.remove() }
                }
            }
        }
        pending.forEach {
            errors += L10nRuntime.format(
                "任务 {0}：轮询超时（{1}s）仍未完成", it, TASK_POLL_BUDGET_MS / 1000,
            )
        }

        if (urls.isEmpty()) {
            throw MomentImageGenException(
                L10nRuntime.t("生图任务失败：") + errors.distinct().joinToString("；")
                    .ifBlank { L10nRuntime.t("无任务成功") },
            )
        }

        val saved = urls.take(target).mapNotNull { url ->
            download(url)?.let { bytes -> saveAsJpeg(bytes) }
        }
        if (saved.isEmpty()) throw MomentImageGenException(L10nRuntime.t("图片下载/解码失败"))
        saved
    }

    private data class SubmitOutcome(val taskId: String? = null, val error: String? = null)

    /** GET JSON（任务状态查询用）；非 2xx / 网络异常抛 [MomentImageGenException]。 */
    private fun getJson(endpoint: String, apiKey: String): String {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw MomentImageGenException(
                        L10nRuntime.format("查询失败 HTTP {0}: {1}", response.code, text.take(120)),
                    )
                }
                text
            }
        } catch (e: MomentImageGenException) {
            throw e
        } catch (e: Exception) {
            throw MomentImageGenException(L10nRuntime.format("查询异常: {0}", e.message), e)
        }
    }

    /**
     * 消息 content 数组：文本 + 可选参考图。
     * [responsesStyle] = false 是 chat/completions 形态（type "text"/"image_url"，image_url 为对象）；
     * true 是 Responses 形态（type "input_text"/"input_image"，image_url 为字符串）。
     * 参考图编码失败时静默省略（退化为纯文生图，不阻断）。
     */
    private fun buildContentItems(
        userText: String,
        referenceImagePath: String?,
        responsesStyle: Boolean,
    ) = buildJsonArray {
        add(buildJsonObject {
            put("type", if (responsesStyle) "input_text" else "text")
            put("text", userText)
        })
        val dataUrl = referenceImagePath?.let { encodeReferenceImage(it) }
        if (dataUrl != null) {
            add(buildJsonObject {
                put("type", if (responsesStyle) "input_image" else "image_url")
                if (responsesStyle) put("image_url", dataUrl)
                else put("image_url", buildJsonObject { put("url", dataUrl) })
            })
        }
    }

    /** 统一抽图并落盘：任何图片都没有时抛带原始回复片段的异常（便于用户/日志定位）。 */
    private fun extractAndSave(raw: String, target: Int): List<String> {
        val refs = MomentImageExtractor.extract(raw)
        if (refs.isEmpty()) {
            throw MomentImageGenException(
                L10nRuntime.format("生图回复中未找到图片（回复开头：{0}）", raw.take(120)),
            )
        }
        val saved = mutableListOf<String>()
        for (ref in refs) {
            if (saved.size >= target) break
            val bytes = when {
                ref.base64 != null -> runCatching { Base64.decode(ref.base64, Base64.DEFAULT) }.getOrNull()
                ref.url != null -> download(ref.url)
                else -> null
            } ?: continue
            if (bytes.size > AppConfig.Moment.MAX_IMAGE_BYTES) continue
            val path = saveAsJpeg(bytes) ?: continue
            saved += path
        }
        if (saved.isEmpty()) throw MomentImageGenException(L10nRuntime.t("图片下载/解码失败"))
        return saved
    }

    /** POST JSON 并取响应文本；非 2xx / 网络异常都抛带端点信息的 [MomentImageGenException]。 */
    private fun postJson(endpoint: String, bodyJson: String, apiKey: String): String {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(bodyJson.toRequestBody(jsonMediaType))
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw MomentImageGenException(
                        L10nRuntime.format(
                            "生图请求失败 HTTP {0}: {1}（{2}）", response.code, text.take(200), endpoint,
                        ),
                    )
                }
                text
            }
        } catch (e: MomentImageGenException) {
            throw e
        } catch (e: Exception) {
            throw MomentImageGenException(
                L10nRuntime.format("生图请求异常: {0}（{1}）", e.message, endpoint), e,
            )
        }
    }

    private fun download(url: String): ByteArray? = runCatching {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            if (bytes.size > AppConfig.Moment.MAX_IMAGE_BYTES) return null
            bytes
        }
    }.getOrNull()

    /** 解码任意图片字节 → JPEG 压缩落盘（长边 1280 上限，质量 85），返回 file:// URI。 */
    private fun saveAsJpeg(bytes: ByteArray): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        try {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) return null
            val directory = File(context.filesDir, DIRECTORY).apply { if (!exists()) mkdirs() }
            val file = File(directory, "moment_${System.currentTimeMillis()}_${(0..999).random()}.jpg")
            file.writeBytes(out.toByteArray())
            android.net.Uri.fromFile(file).toString()
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    /**
     * 参考图编码为 data URL。与 AppContainer.encodeSeedanceImage 同思路：
     * 先探测尺寸再降采样 JPEG（长边 1024、质量梯度），绝不整读原图 Base64 防 OOM。
     * assets 路径（file:///android_asset/...）从 assets 读；本地 file:// 从磁盘读。
     */
    private fun encodeReferenceImage(path: String): String? {
        val bytes = when {
            path.startsWith("file:///android_asset/") -> {
                val rel = path.removePrefix("file:///android_asset/")
                runCatching { context.assets.open(rel).use { it.readBytes() } }.getOrNull()
            }
            else -> {
                val file = File(runCatching { android.net.Uri.parse(path).path }.getOrNull() ?: path)
                if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
            }
        } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val encoded = try {
            var quality = 85
            var data: ByteArray? = null
            while (quality >= 60) {
                val out = ByteArrayOutputStream()
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    data = out.toByteArray()
                    if (data.size <= REFERENCE_MAX_BYTES) break
                }
                quality -= 10
            }
            data
        } finally {
            bitmap.recycle()
        } ?: return null
        return "data:image/jpeg;base64," + Base64.encodeToString(encoded, Base64.NO_WRAP)
    }

    companion object {
        private const val DIRECTORY = "moment_images"
        private const val REFERENCE_MAX_BYTES = 600L * 1024

        /** 任务制：params.size 必填，官方推荐 auto（自适应比例）。 */
        private const val TASK_SIZE = "auto"
        /** 任务制轮询间隔（秒）。平台建议 5 秒，不要太频繁。 */
        private const val TASK_POLL_INTERVAL_MS = 5_000L
        /** 任务制轮询总预算（毫秒）。平台建议客户端读超时 ≥300s；图片通常几十秒内完成。 */
        private const val TASK_POLL_BUDGET_MS = 300_000L

        /** 生图专用 OkHttp（有限超时；不复用 streamingClient 的 0 超时）。 */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(AppConfig.Moment.IMAGE_GEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(AppConfig.Moment.IMAGE_GEN_TIMEOUT_MS + 30_000L, TimeUnit.MILLISECONDS)
            .build()
    }
}

/**
 * 任务制媒体 API 响应解析（纯函数，JVM 可测）。
 *
 * 提交响应是唯一带 `{code,msg,data}` 信封的接口（code==200 才算成功，data.task_id 为数字）；
 * 任务状态响应无信封，state/is_final/result_url/error 全在顶层——error 为 JSON 对象时是
 * 网关瞬时故障（非任务字段），调用方应跳过本轮轮询。
 */
internal object MediaTaskApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class TaskStatus(val isFinal: Boolean, val resultUrl: String?, val resultType: String?, val error: String?)

    /** 取字符串字段；JsonNull / 缺失 / 空串一律返回 null（JsonNull.content 是 "null" 串，不能直读）。 */
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

    /** 解析提交响应 → task_id；code!=200 / 无 task_id 抛 [IllegalArgumentException]。 */
    fun parseSubmitResponse(body: String): String {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrNull()
            ?: throw IllegalArgumentException(
                L10nRuntime.format("提交响应不是 JSON 对象: {0}", body.take(120)),
            )
        val code = root.text("code")?.toIntOrNull()
        if (code != 200) {
            val msg = root.text("msg") ?: body.take(120)
            throw IllegalArgumentException(L10nRuntime.format("提交被拒（code={0}）：{1}", code, msg))
        }
        val taskId = (root["data"] as? JsonObject)?.text("task_id")
        if (taskId.isNullOrBlank()) {
            throw IllegalArgumentException(
                L10nRuntime.format("提交成功但未返回 task_id: {0}", body.take(120)),
            )
        }
        return taskId
    }

    /** 解析任务状态；网关瞬时故障（error 为对象 / 非 JSON）抛异常，调用方当轮跳过。 */
    fun parseTaskStatus(body: String): TaskStatus {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrNull()
            ?: throw IllegalArgumentException(L10nRuntime.format("状态响应不是 JSON: {0}", body.take(120)))
        if (root["error"] is JsonObject) throw IllegalArgumentException(L10nRuntime.t("网关瞬时故障"))
        val state = root.text("state")
        val error = root.text("error")
            ?: state?.takeIf { it == "failed" }?.let { L10nRuntime.t("任务失败（state=failed，平台已自动退款）") }
        return TaskStatus(
            isFinal = root.text("is_final")?.toBooleanStrictOrNull() ?: false,
            resultUrl = root.text("result_url"),
            resultType = root.text("result_type"),
            error = error,
        )
    }
}

/**
 * 生图端点候选计算（纯函数，JVM 可测）。
 *
 * 中转站的 Base URL 填法五花八门：根域名（漏 /v1，最常见笔误）、带 /v1、直接填完整
 * chat/completions 端点。这里按顺序给出候选，调用方逐个尝试，第一个成功者生效。
 * Responses 端点同理（gpt-image-* 类模型常只挂 /v1/responses）。
 */
object MomentImageEndpoints {

    /** 聊天格式出图候选端点，按优先级排列。 */
    fun chatCandidates(baseUrl: String): List<String> {
        val base = normalizeRoot(baseUrl)
        if (base.endsWith("/chat/completions", ignoreCase = true)) return listOf(base)
        val list = mutableListOf("$base/chat/completions")
        if (!hasVersionSegment(base)) list += "$base/v1/chat/completions"
        return list
    }

    /** Responses 格式出图候选端点（API 根去掉 chat 后缀；无版本段补 /v1）。 */
    fun responsesCandidates(baseUrl: String): List<String> {
        var root = normalizeRoot(baseUrl)
        if (root.endsWith("/chat/completions", ignoreCase = true)) {
            root = root.dropLast("/chat/completions".length).trimEnd('/')
        }
        return listOf(if (hasVersionSegment(root)) "$root/responses" else "$root/v1/responses")
    }

    /**
     * 任务制媒体 API 提交端点候选（lk888 类平台：POST `{versioned}/media/generate`）。
     * 根域名未带版本段时依次试 OpenAI 风格 `/v1` 与该平台风格 `/api/v1`。
     */
    fun mediaTaskCandidates(baseUrl: String): List<String> {
        var root = normalizeRoot(baseUrl)
        if (root.endsWith("/chat/completions", ignoreCase = true)) {
            root = root.dropLast("/chat/completions".length).trimEnd('/')
        }
        if (hasVersionSegment(root)) return listOf("$root/media/generate")
        return listOf("$root/v1/media/generate", "$root/api/v1/media/generate")
    }

    /** 由生效的提交端点推导任务状态查询端点：`{root}/media/generate` → `{root}/skills/task-status`。 */
    fun taskStatusEndpoint(submitEndpoint: String): String {
        val endpoint = submitEndpoint.trim().trimEnd('/')
        return if (endpoint.endsWith("/media/generate", ignoreCase = true)) {
            endpoint.dropLast("/media/generate".length) + "/skills/task-status"
        } else {
            "$endpoint/skills/task-status"
        }
    }

    private fun normalizeRoot(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    /** 路径里是否已含版本段（/v1、/v2…）。填了版本段的站不多半也不接受再补一个。 */
    private fun hasVersionSegment(base: String): Boolean =
        Regex("/v\\d+(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(base)
}
