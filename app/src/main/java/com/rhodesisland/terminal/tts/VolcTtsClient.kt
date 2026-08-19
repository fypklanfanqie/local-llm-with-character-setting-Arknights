package com.rhodesisland.terminal.tts

import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.TtsAuthMode
import com.rhodesisland.terminal.data.model.TtsConfig
import com.rhodesisland.terminal.data.model.authMode
import com.rhodesisland.terminal.data.model.validationError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

/**
 * 火山引擎语音合成 HTTP V3 Chunked 客户端。
 *
 * 直接调用官方 `/api/v3/tts/unidirectional` endpoint：
 * - 新版控制台：X-Api-Key + 固定声音复刻 2.0 资源；
 * - 响应逐行读取官方 Chunked JSON，不支持 SSE `data:` 封装。
 */
class VolcTtsClient(
    private val endpoint: String,
    private val client: OkHttpClient,
) {

    companion object {
        private const val SUCCESS_AUDIO_CHUNK = 0
        private const val SUCCESS_FINISH = 20_000_000

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
    }

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

    /** 合成语音，返回拼接后的 MP3 字节。 */
    suspend fun synthesize(
        text: String,
        characterId: String,
        ttsConfig: TtsConfig,
        speakerId: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "没有可朗读的文本" }
        require(speakerId.isNotBlank()) { "请先填写默认自定义音色 ID（speaker_id）" }

        val requestBody = json.encodeToString(
            V3Request.serializer(),
            V3Request(
                user = V3User(uid = "mrfz-talk-terminal"),
                namespace = "BidirectionalTTS",
                req_params = V3ReqParams(
                    text = text.trim(),
                    speaker = speakerId,
                    audio_params = V3AudioParams(),
                    additions = """{"disable_markdown_filter":true}""",
                ),
            ),
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .header("X-Api-Resource-Id", AppConfig.TTS_VOICE_CLONE_RESOURCE_ID)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .apply {
                when (ttsConfig.authMode()) {
                    TtsAuthMode.API_KEY -> header("X-Api-Key", ttsConfig.apiKey)
                    TtsAuthMode.NONE -> throw IllegalArgumentException(ttsConfig.validationError()!!)
                }
            }
            .build()

        val call = client.newCall(request)
        currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { call.cancel() } }

        call.execute().use { response ->
            val body = response.body ?: throw Exception("火山引擎 TTS 返回空响应体")
            val logId = response.header("X-Tt-Logid")
            if (!response.isSuccessful) {
                val snippet = body.bytes().decodeToString().take(500)
                throw Exception("TTS HTTP ${response.code}: $snippet${logId?.let { "（LogID: $it）" } ?: ""}")
            }
            parseChunkedResponse(body, logId)
        }
    }

    /** 检查新版或完整旧版凭据是否可用。 */
    fun hasCredentials(config: TtsConfig): Boolean =
        config.apiKey.isNotBlank() && config.defaultVoiceId.isNotBlank()

    private fun parseChunkedResponse(body: ResponseBody, logId: String?): ByteArray {
        val output = ByteArrayOutputStream()
        val source = body.source()
        var errorInfo: JsonObject? = null

        while (true) {
            val line = source.readUtf8Line() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("data:") || trimmed.startsWith("event:")) {
                throw Exception("火山引擎返回 SSE 格式；当前客户端仅支持 HTTP Chunked")
            }

            val obj = try {
                json.parseToJsonElement(trimmed).jsonObject
            } catch (_: Exception) {
                throw Exception("火山引擎返回了无法解析的 Chunked JSON")
            }
            val code = obj["code"]?.jsonPrimitive?.intOrNull
            when (code) {
                null, SUCCESS_AUDIO_CHUNK, SUCCESS_FINISH -> Unit
                else -> if (errorInfo == null) errorInfo = obj
            }

            val data = obj["data"]?.jsonPrimitive?.contentOrNull
            if (!data.isNullOrEmpty()) {
                try {
                    output.write(Base64.getMimeDecoder().decode(data.replace(Regex("\\s"), "")))
                } catch (_: IllegalArgumentException) {
                    throw Exception("火山引擎返回了非法 Base64 音频数据")
                }
            }
        }

        errorInfo?.let { error ->
            val code = error["code"]?.jsonPrimitive?.intOrNull ?: "未知"
            val message = error["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            throw Exception("火山引擎错误 $code: $message${logId?.let { "（LogID: $it）" } ?: ""}")
        }
        if (output.size() == 0) throw Exception("火山引擎返回无音频数据${logId?.let { "（LogID: $it）" } ?: ""}")
        return output.toByteArray()
    }
}
