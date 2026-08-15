package com.rhodesisland.terminal.config

/**
 * 预设模型供应商及其模型列表
 *
 * 覆盖 DeepSeek、OpenAI、通义千问、智谱 GLM 四家，
 * 模型列表为截至 2026 年 7 月的最新版本。
 */

data class PresetModel(
    val id: String,           // API 模型名，如 "deepseek-v4-flash"
    val displayName: String,  // 展示名，如 "DeepSeek-V4-Flash"
    val description: String = "",
)

data class ModelProvider(
    val id: String,           // 供应商标识，如 "deepseek"
    val displayName: String,  // 展示名，如 "DeepSeek"
    val baseUrl: String,      // API 地址
    val defaultModel: String, // 默认模型 id
    val models: List<PresetModel>,
    val requiresApiKey: Boolean = true,
)

val PRESET_PROVIDERS: List<ModelProvider> = listOf(    ModelProvider(
        id = "deepseek",
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-v4-flash",
        models = listOf(
            PresetModel("deepseek-v4-flash", "DeepSeek-V4-Flash", "旗舰快速，思考/非思考双模式，1M 上下文"),
            PresetModel("deepseek-v4-pro", "DeepSeek-V4-Pro", "高端增强版"),
        ),
    ),
    ModelProvider(
        id = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o",
        models = listOf(
            PresetModel("gpt-4o", "GPT-4o", "多模态旗舰"),
            PresetModel("gpt-4o-mini", "GPT-4o Mini", "轻量快速"),
            PresetModel("gpt-4.1", "GPT-4.1", "最新编程优化版"),
            PresetModel("gpt-4.1-mini", "GPT-4.1 Mini", "轻量编程"),
            PresetModel("o4", "o4", "高级推理"),
            PresetModel("o4-mini", "o4-mini", "轻量推理"),
        ),
    ),
    ModelProvider(
        id = "qwen",
        displayName = "通义千问",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen3.7-max",
        models = listOf(
            PresetModel("qwen3.7-max", "Qwen3.7-Max", "最强旗舰"),
            PresetModel("qwen3.7-plus", "Qwen3.7-Plus", "平衡性能与成本"),
            PresetModel("qwen3.6-flash", "Qwen3.6-Flash", "轻量快速"),
        ),
    ),
    ModelProvider(
        id = "glm",
        displayName = "智谱 GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-5.2",
        models = listOf(
            PresetModel("glm-5.2", "GLM-5.2", "最新旗舰，1M 上下文"),
            PresetModel("glm-5.1", "GLM-5.1", "高性能通用"),
            PresetModel("glm-5-turbo", "GLM-5-Turbo", "快速高性价比"),
            PresetModel("glm-4.7-flash", "GLM-4.7-Flash", "免费轻量"),
        ),
    ),
    // 内置免费服务商：SiliconFlow 免费 7B。key 存于 Cloudflare Worker 加密环境变量，
    // 对话经 Worker 代理（App → Cloudflare 注入 key → 硅基流动），key 不出 Cloudflare，无需用户填 key。
    ModelProvider(
        id = "siliconflow-free",
        displayName = "免费对话",
        baseUrl = "https://siliconflow-free-proxy.lanfanqie.workers.dev",
        defaultModel = "Qwen/Qwen2.5-7B-Instruct",
        models = listOf(
            PresetModel("Qwen/Qwen2.5-7B-Instruct", "Qwen2.5-7B（免费）", "免费 7B 对话模型"),
            PresetModel("deepseek-ai/DeepSeek-R1-Distill-Qwen-7B", "DeepSeek-R1-7B（免费）", "免费 7B 推理模型"),
        ),
        requiresApiKey = false,
    ),
)

/** 该 baseUrl 是否为内置免费服务商（Cloudflare 代理）：客户端无需配置 API key，key 由代理注入。 */
fun isFreeProxyBaseUrl(baseUrl: String): Boolean =
    PRESET_PROVIDERS.any {
        it.id == "siliconflow-free" && baseUrl.trimEnd('/') == it.baseUrl.trimEnd('/')
    }