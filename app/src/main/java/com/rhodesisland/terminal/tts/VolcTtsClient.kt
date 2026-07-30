package com.rhodesisland.terminal.tts

import android.util.Base64
import com.rhodesisland.terminal.data.model.TtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * 火山引擎 TTS 客户端（透明代理模式）
 *
 * 对齐网页版 js/tts.js + workers/cloudbase-tts-fn：
 * - 请求体：火山引擎 HTTP V3 原生格式（BidirectionalTTS）
 * - 鉴权：HTTP Header（X-Api-Key / X-Api-Resource-Id / X-Api-Request-Id）
 * - 代理：CloudBase Web 函数透明转发，不转换请求体
 * - 响应：NDJSON（每行一个 JSON），base64 音频在 "data" 字段
 *
 * API 参考: https://www.volcengine.com/docs/6561/1598757
 */
class VolcTtsClient(
    private val proxyUrl: String,
    private val client: OkHttpClient,
) {

    companion object {
        /** 火山引擎豆包 2.0 即时克隆资源 ID */
        private const val RESOURCE_ID = "seed-icl-2.0"

        /** 默认音色（未匹配到角色时使用，对齐网页版 VOICE_IDS） */
        private val DEFAULT_VOICES = mapOf(
            "zh" to "S_c1jmOCG72",
            "ja" to "S_d1jmOCG72",
        )

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
    }

    // ===== V3 请求/响应模型 =====

    @Serializable
    private data class V3Request(
        val user: V3User,
        val namespace: String,
        val req_params: V3ReqParams,
    )

    @Serializable
    private data class V3User(val uid: String)

    @Serializable
    private data class V3ReqParams(
        val text: String,
        val speaker: String,
        val audio_params: V3AudioParams,
        val additions: String? = null,
    )

    @Serializable
    private data class V3AudioParams(
        val format: String = "mp3",
        val sample_rate: Int = 24000,
    )

    // ===== Public API =====

    /**
     * 合成语音，返回 mp3 字节数组。
     *
     * @param text        待合成文本
     * @param language    语言（zh / ja），用于选择默认音色
     * @param characterId 角色 ID（用于日志）
     * @param ttsConfig   TTS 配置（含 apiKey / appId / accessKey）
     * @param voice       火山引擎音色 ID（S_xxx 格式）；null 则用语言默认音色
     */
    suspend fun synthesize(
        text: String,
        language: String,
        characterId: String,
        ttsConfig: TtsConfig,
        voice: String? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        // 音色选择：优先传入 > 语言默认
        val speaker = voice?.takeIf { it.isNotBlank() }
            ?: DEFAULT_VOICES[language]
            ?: DEFAULT_VOICES["zh"]!!

        // 构建火山引擎 V3 请求体（对齐网页版 synthesize()）
        val v3Body = V3Request(
            user = V3User(uid = "mrfz-talk-terminal"),
            namespace = "BidirectionalTTS",
            req_params = V3ReqParams(
                text = text.trim(),
                speaker = speaker,
                audio_params = V3AudioParams(format = "mp3", sample_rate = 24000),
                additions = """{"disable_markdown_filter":true}""",
            ),
        )

        val requestBody = json.encodeToString(V3Request.serializer(), v3Body)
            .toRequestBody("application/json".toMediaType())

        // 构建 HTTP 请求（鉴权在 Header，对齐网页版 + 代理透传逻辑）
        val request = Request.Builder()
            .url(proxyUrl)
            .post(requestBody)
            .apply {
                if (ttsConfig.apiKey.isNotBlank()) {
                    addHeader("X-Api-Key", ttsConfig.apiKey)
                    addHeader("X-Api-Resource-Id", RESOURCE_ID)
                    addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
                }
                if (ttsConfig.appId.isNotBlank()) {
                    addHeader("X-Api-App-Key", ttsConfig.appId)
                }
                if (ttsConfig.accessKey.isNotBlank()) {
                    addHeader("X-Api-Access-Key", ttsConfig.accessKey)
                }
            }
            .build()

        // 执行请求（OkHttp execute 是阻塞的，在 IO 调度器运行）
        val response = client.newCall(request).execute()
        val responseBytes = response.body?.bytes()
            ?: throw Exception("TTS 代理返回空响应体")

        if (!response.isSuccessful) {
            val errorSnippet = String(responseBytes).take(500)
            throw Exception("TTS HTTP ${response.code}: $errorSnippet")
        }

        // 解析 NDJSON 响应（对齐网页版 synthesize() 的 JSON 行解析逻辑）
        parseV3Response(responseBytes)
    }

    /** 检查 TTS 凭据是否已配置（对齐网页版 hasCredentials()） */
    fun hasCredentials(config: TtsConfig): Boolean {
        return config.apiKey.isNotBlank() || (config.appId.isNotBlank() && config.accessKey.isNotBlank())
    }

    // ===== Private =====

    /** 解析火山引擎 V3 NDJSON 响应，提取合并所有 base64 音频 chunk */
    private fun parseV3Response(rawBytes: ByteArray): ByteArray {
        val rawText = String(rawBytes)
        val allBase64 = StringBuilder()
        var errorInfo: JsonObject? = null

        for (line in rawText.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val obj = try {
                json.parseToJsonElement(trimmed).jsonObject
            } catch (_: Exception) {
                continue
            }

            // 收集音频 data（空字符串也判掉，对齐网页版 != null && !== ''）
            val data = obj["data"]?.jsonPrimitive?.contentOrNull
            if (!data.isNullOrEmpty()) {
                allBase64.append(data.replace(Regex("\\s"), ""))
            }

            // 捕获错误（code != 0 或 message != success，对齐网页版逻辑）
            val code = obj["code"]?.jsonPrimitive?.intOrNull
            val message = obj["message"]?.jsonPrimitive?.contentOrNull
            val error = obj["error"]?.jsonPrimitive?.booleanOrNull
            val isError = (code != null && code != 0) || error == true ||
                (message != null && message != "success")

            if (errorInfo == null && isError) {
                errorInfo = obj
                // 不立即抛异常：后续行可能仍含音频，先全部收集
            }
        }

        // 有错误记录且无音频数据 → 抛出火山引擎错误
        if (allBase64.isEmpty() && errorInfo != null) {
            val err = errorInfo!!
            val errCode = err["code"]?.jsonPrimitive?.intOrNull?.toString() ?: ""
            val errMsg = err["message"]?.jsonPrimitive?.contentOrNull
                ?: err["error"]?.jsonPrimitive?.contentOrNull
                ?: ""
            val desc = listOf(errCode, errMsg).filter { it.isNotBlank() }.joinToString(": ")
            throw Exception("火山引擎错误: ${desc.ifBlank { err.toString() }}")
        }

        if (allBase64.isEmpty()) {
            // 无音频也无错误信息 → 打印原始响应片段帮助诊断
            throw Exception("火山引擎返回无音频数据 — 响应: ${rawText.take(300)}")
        }

        return Base64.decode(allBase64.toString(), Base64.DEFAULT)
    }
}
