package com.rhodesisland.terminal.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.llm.MomentPromptBuilder
import com.rhodesisland.terminal.util.MomentImageExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
 * 朋友圈生图客户端（OpenAI 聊天格式出图，中转站兼容）。
 *
 * 流程：角色立绘 → 压缩 → data URL 作 image_url 参考图 + 生图提示词文本一起发给
 * `/chat/completions`（非流式）→ 从回复提取图片（URL/base64/data URI）→ 下载/解码 →
 * JPEG 落盘 `filesDir/moment_images/` → 返回本地 file:// 路径列表。
 *
 * 任何一步失败抛 [MomentImageGenException]，由调用方降级纯文字发圈。
 */
class MomentImageGenClient(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }
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
        if (!config.isConfigured) throw MomentImageGenException("生图 API 未配置")
        val target = count.coerceIn(1, AppConfig.Moment.MAX_IMAGES)

        val contentItems = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", MomentPromptBuilder.buildImageGenUserMessage(imagePrompt, target))
            })
            referenceImagePath?.let { path ->
                val dataUrl = encodeReferenceImage(path)
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", dataUrl) })
                })
            }
        }
        val body = buildJsonObject {
            put("model", config.model)
            put("stream", false)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", contentItems)
                })
            })
        }

        val endpoint = buildEndpoint(config.baseUrl)
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val raw = try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw MomentImageGenException("生图请求失败 HTTP ${response.code}: ${text.take(200)}")
                }
                text
            }
        } catch (e: MomentImageGenException) {
            throw e
        } catch (e: Exception) {
            throw MomentImageGenException("生图请求异常: ${e.message}", e)
        }

        val reply = parseReplyContent(raw)
            ?: throw MomentImageGenException("生图响应格式无法解析")
        val refs = MomentImageExtractor.extract(reply)
        if (refs.isEmpty()) throw MomentImageGenException("生图回复中未找到图片")

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
        if (saved.isEmpty()) throw MomentImageGenException("图片下载/解码失败")
        saved
    }

    /** 解析 OpenAI 格式响应取 choices[0].message.content。 */
    private fun parseReplyContent(raw: String): String? = runCatching {
        val root = json.parseToJsonElement(raw).jsonObjectOrNull() ?: return null
        val choices = root["choices"]?.jsonArrayOrNull() ?: return null
        val message = (choices.firstOrNull()?.jsonObjectOrNull())?.get("message")?.jsonObjectOrNull() ?: return null
        val content = message["content"] ?: return null
        when {
            content.toString().startsWith("\"") -> content.toString().removeSurrounding("\"")
            else -> content.toString()
        }
    }.getOrNull()

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

    /** OpenAI 端点拼接（与 DirectLlmClient.buildEndpoint 语义一致）。 */
    private fun buildEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() =
        this as? kotlinx.serialization.json.JsonObject

    private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull() =
        this as? kotlinx.serialization.json.JsonArray

    companion object {
        private const val DIRECTORY = "moment_images"
        private const val REFERENCE_MAX_BYTES = 600L * 1024

        /** 生图专用 OkHttp（有限超时；不复用 streamingClient 的 0 超时）。 */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(AppConfig.Moment.IMAGE_GEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(AppConfig.Moment.IMAGE_GEN_TIMEOUT_MS + 30_000L, TimeUnit.MILLISECONDS)
            .build()
    }
}
