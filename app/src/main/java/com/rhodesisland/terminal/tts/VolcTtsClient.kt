package com.rhodesisland.terminal.tts

import com.rhodesisland.terminal.data.model.TtsConfig
import com.rhodesisland.terminal.data.remote.CloudRunApi
import com.rhodesisland.terminal.data.remote.TtsRequest
import android.util.Base64

/**
 * 火山引擎 TTS 客户端
 *
 * 保持原小程序逻辑：通过 CloudRun /tts 代理调用火山引擎豆包语音合成 2.0
 * 返回 base64 mp3，由 TtsManager 写入临时文件播放。
 *
 * 云端回复和本地回复都调用此 TTS。
 */
class VolcTtsClient(private val api: CloudRunApi) {

    suspend fun synthesize(
        text: String,
        language: String,
        characterId: String,
        ttsConfig: TtsConfig,
        voice: String? = null,
    ): ByteArray {
        val request = TtsRequest(
            text = text,
            language = language,
            characterId = characterId,
            ttsConfig = ttsConfig,
            voice = voice,
        )

        val response = api.tts(request)
        response.error?.let { throw Exception(it) }

        if (response.audioBase64.isBlank()) {
            throw Exception("TTS 服务返回无音频数据")
        }

        return Base64.decode(response.audioBase64, Base64.DEFAULT)
    }

    /** 检查 TTS 凭据是否已配置 */
    fun hasCredentials(config: TtsConfig): Boolean {
        return config.apiKey.isNotBlank()
    }
}
