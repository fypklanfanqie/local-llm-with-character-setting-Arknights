package com.rhodesisland.terminal.data.model

import com.rhodesisland.terminal.config.AppConfig

/**
 * 朋友圈生图 API 配置（OpenAI 聊天格式兼容端点：中转站/官方均可）。
 * 与主 LLM 的 [ApiConfig] 分离——生图模型与对话模型通常是不同的服务/密钥。
 */
data class MomentImageGenConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    /** 生图 API 是否已配置（三项齐全才可用）。 */
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    companion object {
        val EMPTY = MomentImageGenConfig()
    }
}

/**
 * 朋友圈自动发圈配置聚合快照。
 * [intervalHours] 越界回落默认值；[enabled] 默认关。
 */
data class MomentAutoConfig(
    val enabled: Boolean = false,
    val intervalHours: Int = AppConfig.Moment.DEFAULT_INTERVAL_HOURS,
    val characterIds: Set<String> = emptySet(),
) {
    init {
        require(intervalHours > 0) { "intervalHours must be positive" }
    }
}
