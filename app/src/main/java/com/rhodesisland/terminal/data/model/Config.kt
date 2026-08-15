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
 * 朗读引擎：
 *  - [SYSTEM]：手机自带 TTS（默认，无需网络与凭据，离线可用）；
 *  - [CLOUD]：云端火山豆包 TTS（声音复刻音色、中日双语，需配置凭据）。
 */
enum class TtsEngine(val storageKey: String, val label: String) {
    SYSTEM("system", "手机系统语音"),
    CLOUD("cloud", "云端（火山豆包）");

    companion object {
        val DEFAULT: TtsEngine = SYSTEM
        fun fromStorageKey(value: String?): TtsEngine =
            entries.firstOrNull { it.storageKey == value } ?: DEFAULT
    }
}

/**
 * 系统引擎声音模板：语速（1.0 标准，>1 快）/ 音调（1.0 标准，>1 高）+ 设备语音名匹配关键词。
 *
 * 匹配在 [com.rhodesisland.terminal.tts.matchSystemVoiceForTemplate] 中完成：
 * 按关键词（小写包含）在中文语音里挑最接近的；无任何命中时回落引擎默认语音（不报错）。
 * 关键词只放稳定通用词（女/男/童 + 常见语音名片段），不同手机厂商的语音命名差异大。
 */
enum class SystemVoiceTemplate(
    val storageKey: String,
    val label: String,
    val pitch: Float,
    val rate: Float,
    val voiceMatchers: List<String>,
) {
    DEFAULT("default", "跟随系统", 1.0f, 1.0f, emptyList()),
    GENTLE_FEMALE("gentle_female", "温柔女声", 1.08f, 0.85f, listOf("female", "女", "yue", "xiaoyan", "xiaoqi")),
    VIVID_GIRL("vivid_girl", "元气少女", 1.25f, 1.15f, listOf("female", "女", "xiaoyan", "xiaoqi", "mei")),
    STEADY_MALE("steady_male", "沉稳男声", 0.72f, 0.85f, listOf("male", "男", "yunxi", "yunyang")),
    YOUNG_MALE("young_male", "清爽少年", 0.95f, 1.05f, listOf("male", "男")),
    CHILD("child", "童声", 1.45f, 1.2f, listOf("child", "童", "kids"));

    companion object {
        val DEFAULT_TEMPLATE: SystemVoiceTemplate = GENTLE_FEMALE
        fun fromStorageKey(value: String?): SystemVoiceTemplate =
            entries.firstOrNull { it.storageKey == value } ?: DEFAULT_TEMPLATE
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
