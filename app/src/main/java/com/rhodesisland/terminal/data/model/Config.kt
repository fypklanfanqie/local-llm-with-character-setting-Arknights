package com.rhodesisland.terminal.data.model

import kotlinx.serialization.Serializable

/**
 * LLM API 配置
 * 对应小程序 storage.getApiConfig()
 */
@Serializable
data class ApiConfig(
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
)

/**
 * TTS 配置（火山引擎）
 * 对齐网页版 storage.getTtsConfig()
 * apiKey 为火山引擎控制台 API Key 管理中的 key；
 * appId + accessKey 为旧版账号体系的备选鉴权方式。
 */
@Serializable
data class TtsConfig(
    val apiKey: String = "",
    val appId: String = "",
    val accessKey: String = "",
)

/**
 * TTS 语言
 */
enum class TtsLanguage(val code: String, val label: String, val displayChar: String) {
    ZH("zh", "中文", "中"),
    JA("ja", "日本語", "日");

    companion object {
        fun fromCode(code: String): TtsLanguage =
            if (code == "ja") JA else ZH
    }
}

/**
 * 角色音色映射（火山引擎声音复刻 ID，S_xxx 格式）
 * zh / ja 分别对应中、日语音色；留空则由服务端按 characterId 默认选择。
 */
@Serializable
data class VoicePair(
    val zh: String = "",
    val ja: String = "",
)

/**
 * 聊天 Provider 类型
 */
enum class ChatProviderType(val label: String, val icon: String) {
    CLOUD("云端 AI", "☁"),
    LOCAL("本地 AI", "📱")
}
