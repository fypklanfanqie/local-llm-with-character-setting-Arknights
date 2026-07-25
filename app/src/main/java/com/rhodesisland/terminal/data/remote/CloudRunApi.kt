package com.rhodesisland.terminal.data.remote

import com.rhodesisland.terminal.data.model.TtsConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.*

/**
 * CloudRun TTS 代理 API。
 *
 * 仅火山引擎 TTS 保留代理（火山签名复杂）。云端对话 / 翻译 / 图片识别 / 文档提取
 * 已改直连对话商 OpenAI 兼容 API（见 [DirectLlmClient]），不再经此代理。
 */
interface CloudRunApi {

    /** TTS 语音合成（火山引擎豆包，经代理转发） */
    @POST("tts")
    suspend fun tts(
        @Body body: TtsRequest,
    ): TtsResponse
}

// ===== 请求体 =====

@Serializable
data class TtsRequest(
    val text: String,
    val language: String = "zh",
    val characterId: String = "",
    val ttsConfig: TtsConfig,
    val voice: String? = null,
)

// ===== 响应体 =====

@Serializable
data class TtsResponse(
    @SerialName("audioBase64") val audioBase64: String = "",
    val format: String = "mp3",
    val error: String? = null,
)
