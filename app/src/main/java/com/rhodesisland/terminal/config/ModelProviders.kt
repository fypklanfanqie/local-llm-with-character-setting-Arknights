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

val PRESET_PROVIDERS: List<ModelProvider> = listOf(
    ModelProvider(
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
)