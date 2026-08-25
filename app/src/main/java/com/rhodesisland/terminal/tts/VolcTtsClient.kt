package com.rhodesisland.terminal.tts

import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.TtsAuthMode
import com.rhodesisland.terminal.data.model.TtsConfig
import com.rhodesisland.terminal.data.model.authMode
import com.rhodesisland.terminal.data.model.validationError
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        private const val TAG = "VolcTtsClient"

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
        require(speakerId.isNotBlank()) { "请先填写该角色当前语言的 speaker_id" }

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
            val body = response.body ?: throw Exception("语音服务返回为空")
            val logId = response.header("X-Tt-Logid")
            if (!response.isSuccessful) {
                val raw = body.bytes()
                Log.w(TAG, "TTS HTTP failure code=${response.code} logId=$logId bodyLength=${raw.size}")
                throw Exception("语音服务请求失败，请检查配置后重试")
            }
            parseChunkedResponse(body, logId)
        }
    }

    /** 检查新版 API Key 是否已配置。speaker_id 在角色朗读时单独按语言校验。 */
    fun hasCredentials(config: TtsConfig): Boolean = config.apiKey.isNotBlank()

    private fun parseChunkedResponse(body: ResponseBody, logId: String?): ByteArray {
        val output = ByteArrayOutputStream()
        val source = body.source()
        var errorInfo: JsonObject? = null

        while (true) {
            val line = source.readUtf8Line() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("data:") || trimmed.startsWith("event:")) {
                throw Exception("语音服务返回格式不受支持")
            }

            val obj = try {
                json.parseToJsonElement(trimmed).jsonObject
            } catch (_: Exception) {
                throw Exception("语音服务返回格式异常")
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
                    throw Exception("语音数据异常")
                }
            }
        }

        errorInfo?.let { error ->
            val code = error["code"]?.jsonPrimitive?.intOrNull
            Log.w(TAG, "TTS service error code=$code logId=$logId")
            throw Exception("语音服务暂时无法合成，请稍后重试")
        }
        if (output.size() == 0) throw Exception("语音服务未返回音频，请稍后重试")
        return output.toByteArray()
    }
}
