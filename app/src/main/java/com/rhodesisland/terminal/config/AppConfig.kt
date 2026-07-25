package com.rhodesisland.terminal.config

/**
 * 应用全局配置
 */
object AppConfig {
    // ===== CloudRun 服务 =====
    // 现仅用于火山引擎 TTS 代理（云端对话/翻译/OCR/文档提取已改直连对话商 API）。
    // 部署后填入 CloudRun 公网域名（如 https://llm-proxy-xxxx.cloudrun.cloudbase.net）
    // 也可填入自建服务器地址
    const val CLOUD_RUN_BASE_URL = "https://llm-proxy1-262820-10-1437375546.sh.run.tcloudbase.com"

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
}
