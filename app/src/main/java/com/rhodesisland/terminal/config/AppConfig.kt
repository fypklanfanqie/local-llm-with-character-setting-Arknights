package com.rhodesisland.terminal.config

/**
 * 应用全局配置
 */
object AppConfig {
    // ===== TTS 代理 =====
    // 火山引擎 TTS 透明代理（CloudBase Web 函数），对齐网页版 workers/cloudbase-tts-fn。
    // 代理直接透传 header + body 到火山引擎 V3 API，不做请求格式转换。
    const val TTS_PROXY_URL = "https://lanfanqie-d8go1l51d56f44d20.service.tcloudbase.com/tts"

    // ===== 云端 AI 默认配置 =====
    const val DEFAULT_API_BASE = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-chat"

    // ===== 资源 CDN（立绘/语音/BGM 公网地址）=====
    // 由于 Android 无微信云存储，需将 assets 上传至公网 CDN
    // 留空则使用本地 assets 回退
    const val ASSET_CDN_BASE = ""

    // ===== TTS =====
    const val TTS_DEFAULT_VOLUME = 85

    // ===== 本地 LLM 默认参数 =====
    object LLM {
        const val DEFAULT_CONTEXT_LEN = 2048
        const val DEFAULT_THREADS = 4
        // 0.8（非 0.9）：小模型角色扮演在高温下易「上头」，从单角色回复滑向编造多角色剧本并无限生成
        // （配合 LocalChatProvider 的 system prompt 输出规范 + onToken 剧本标记截断兜底）。0.8 保留角色
        // 语气多样性的同时显著降低跑偏概率。
        const val DEFAULT_TEMPERATURE = 0.8f
        // 1024（非 2048）：正常聊天回复罕超 1024 token；缩小上限让模型即便「上头」也早停，避免长篇
        // 剧本耗满上下文。与 onToken 截断兜底互为防线。
        const val DEFAULT_MAX_TOKENS = 1024
        const val DEFAULT_TOP_P = 0.9f
        // 1.2（非 1.1）：小模型无重复惩罚时会逐字复读角色卡循环；mixed_samplers 现已含 "penalty"
        // 生效（见 mnn_jni.cpp set_config）。1.1 偏弱压不住结构性复读，1.2 在 max_penalty=10 内安全。
        const val DEFAULT_REPEAT_PENALTY = 1.2f
    }

    // ===== 聊天历史 =====
    // 每个会话（conversation）最多保留的消息条数；超出按时间修剪最旧消息。
    const val MAX_HISTORY_PER_CONVERSATION = 50
    // 单次请求喂给模型上下文的最大消息条数（取该会话最近 N 条）。
    const val MAX_CONTEXT_MESSAGES = 20

    // ===== 角色问候（角色主动消息）=====
    // 开启后，所选角色会在白天随机时间主动给用户发消息（早安/晚安/关心/开话题）。
    // 仅云端 AI 模式可用：消息由 DirectLlmClient.chatOnce 生成，符合角色人设。
    object Greeting {
        // 用户可设置的每天主动消息条数范围与默认值
        const val DEFAULT_DAILY_COUNT = 3
        const val MIN_DAILY_COUNT = 1
        const val MAX_DAILY_COUNT = 10
        // 仅在此时段内触发（避免深夜打扰）：08:00–23:00
        const val HOUR_START = 8
        const val HOUR_END = 23
        // 生成主动消息时带入的最近历史条数（让消息能衔接正在聊的话题）
        const val CONTEXT_MESSAGES = 6
        // 单次生成超时（秒）
        const val GENERATE_TIMEOUT_MS = 60_000L
        // PeriodicWork 周期（分钟）。WorkManager 最短允许 15 分钟。
        // 用周期性 Worker 取代脆弱的自延续链：错失一次下个周期仍会触发，链条不会因进程被杀而永久断裂。
        const val HEARTBEAT_INTERVAL_MIN = 15L
        // 云 API 生成失败后下一次投递的退避间隔（毫秒）。
        // 写入 next_fire_at，让 PeriodicWork 在此间隔后再尝试，避免每个周期都失败重试。
        const val RETRY_DELAY_MS = 45 * 60 * 1000L
    }
}
