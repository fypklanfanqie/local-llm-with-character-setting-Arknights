package com.rhodesisland.terminal.data.model

import kotlinx.serialization.Serializable

/**
 * 模型格式。MNN-only：`.mnn` 目录（config.json + llm.mnn + llm.mnn.weight + tokenizer.txt，
 * 大权重可能切分成 `.partN`），走 MNN 后端（CPU / OpenCL GPU / QNN NPU）。
 *
 * （GGUF / llama.cpp 支持已移除，仅保留 MNN。枚举保留单值以维持 [ModelInfo] 序列化稳定。）
 */
enum class ModelFormat(val displayName: String) {
    MNN("MNN"),
}

/**
 * 本地模型信息（MNN）
 *
 * 模型来源：内置清单 [DEFAULT_MNN_MODELS]（不再从网络拉取 MNN 模型市场）。
 * 每个条目对应 HuggingFace `taobao-mnn/<id>` 仓库（镜像 ModelScope `MNN/<id>`），
 * 整仓库多文件下载 + 分片合并（见 [com.rhodesisland.terminal.download.DownloadManager]）。
 *
 * @param id          模型唯一 ID（MNN 模型目录名，如 `Qwen3.5-2B-MNN`）
 * @param name        展示名称
 * @param description 模型说明
 * @param size        合并后总字节数（用于下载进度与剩余空间判断；近似值，真实大小以 Content-Length 为准）
 * @param version     版本号
 * @param format      模型格式（恒 [ModelFormat.MNN]）
 * @param repo        主仓库坐标（HuggingFace，如 `taobao-mnn/Qwen3.5-2B-MNN`，整仓库多文件下载）
 * @param altRepos    备用仓库坐标（ModelScope 镜像，如 `MNN/Qwen3.5-2B-MNN`）
 * @param tags        模型标签（Vision/Audio/Code/Math/Think/NPU/Chat 等），用于 UI 分类与筛选
 * @param vendor      模型厂商（Qwen/DeepSeek/Llama/Gemma/Phi/…）
 * @param recommended 是否推荐（⭐ 标记）
 *
 * 说明：[repo]/[altRepos] 整仓库按多镜像源（ModelScope 国内 -> hf-mirror -> HuggingFace 原站）
 * 自动拼接并依次回退，任一镜像可访问即下载成功，规避单一源 404 / 限流问题。
 */
@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val size: Long,
    val version: String = "mnn",
    val format: ModelFormat = ModelFormat.MNN,
    val repo: String = "",
    val altRepos: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val vendor: String = "",
    val recommended: Boolean = false,
) {
    /** 是否带 NPU 标签（MNN 模型市场里 QNN 预编译变体） */
    val isNpuVariant: Boolean get() = tags.any { it.equals("NPU", ignoreCase = true) }
}

/**
 * 服务器模型列表响应（保留结构兼容；MNN-only 下 [models] 即 [DEFAULT_MNN_MODELS]）
 */
@Serializable
data class ModelListResponse(
    val models: List<ModelInfo> = emptyList(),
    val updatedAt: String = "",
)

/**
 * 已安装模型
 */
data class InstalledModel(
    val info: ModelInfo,
    val localPath: String,
    val verified: Boolean,
)

/**
 * 下载状态
 */
sealed interface DownloadState {
    /** 未下载 */
    data object NotDownloaded : DownloadState

    /** 下载中 */
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : DownloadState {
        /** 0f ~ 1f */
        val progress: Float
            get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    /** 已暂停 */
    data object Paused : DownloadState

    /** 校验中 */
    data class Verifying(val progress: Float) : DownloadState

    /** 已完成 */
    data class Completed(val path: String) : DownloadState

    /** 失败 */
    data class Failed(val error: String) : DownloadState
}

/**
 * 内置 MNN 模型清单（**唯一模型来源**，不再从网络拉取模型市场）
 *
 * 源：HuggingFace `taobao-mnn/<name>`，镜像 ModelScope `MNN/<name>`。
 * 大小为近似值（仅用于首屏展示与剩余空间判断，真实大小以下载时 Content-Length 为准）。
 */
val DEFAULT_MNN_MODELS: List<ModelInfo> = listOf(
    // Qwen3.5 系列（MNN 优化版，多模态含 visual.mnn；阿里官方 taobao-mnn/<id> + ModelScope MNN/<id> 镜像）：
    // 下载时按 ModelScope 国内 -> hf-mirror -> HuggingFace 多镜像回退（符合「国内镜像优先」）。
    // 注：原清单里的「Claude-4.6-Opus-Reasoning-Dist」蒸馏变体三源均 404 不存在，已移除；
    // 推理模型改用下方真实可下的 DeepSeek-R1 / Qwen3-Think 系列。
    qwen35("Qwen3.5-0.8B-MNN", "Qwen3.5 0.8B", "MNN 优化版，专为移动端或嵌入式设备设计，体积小、效率高。", 522.28 * MIB),
    qwen35("Qwen3.5-2B-MNN", "Qwen3.5 2B", "MNN 优化版，适合中端设备运行。", 1.29 * GIB, recommended = true),
    qwen35("Qwen3.5-4B-MNN", "Qwen3.5 4B", "MNN 优化版，兼顾性能与移动端适配。", 2.65 * GIB, recommended = true),
    qwen35("Qwen3.5-9B-MNN", "Qwen3.5 9B", "MNN 优化版，适合高性能移动设备或边缘计算场景。", 6.78 * GIB),
    qwen35("Qwen3.5-35B-A3B-MNN", "Qwen3.5 35B-A3B", "超大参数 MNN 优化模型，可能采用稀疏化或量化技术，适用于高端设备或服务器部署。", 21.23 * GIB),
    // 推理模型（Think）：均为国内 ModelScope+hf-mirror 双源可下的真实 MNN 推理模型，支持断点续传。
    // 字节数取自 ModelScope API 实测（sizeGb*1e9 精确还原，仅用于展示与剩余空间判断）。
    mnn("DeepSeek-R1-1.5B-Qwen-MNN", "DeepSeek", 1.020644886, listOf("Think")),
    mnn("Qwen3-4B-MNN", "Qwen", 2.713766729, listOf("Think")),
    mnn("DeepSeek-R1-7B-Qwen-MNN", "DeepSeek", 4.647473365, listOf("Think")),
    mnn("DeepSeek-R1-0528-Qwen3-8B-MNN", "DeepSeek", 5.507637931, listOf("Think")),
    // 其他厂商
    mnn("Llama-3.2-1B-Instruct-MNN", "Llama", 1.0, listOf("Chat")),
    mnn("Llama-3.2-3B-Instruct-MNN", "Llama", 3.0, listOf("Chat")),
    mnn("gemma-2-2b-it-MNN", "Gemma", 2.0, listOf("Chat")),
    mnn("SmolLM2-360M-Instruct-MNN", "Smol", 0.36, listOf("Chat")),
)

/** 2 进制 MiB / GiB（const 编译期常量，无顶层 val 初始化顺序问题），用于 [qwen35] 按标注
 *  MB/GB 换算字节数，使 UI formatSize 显示与标注大小一致（仍为近似值，真实大小以 Content-Length 为准）。 */
private const val MIB = 1024L * 1024
private const val GIB = 1024L * 1024 * 1024

/** 构造 Qwen3.5 系列模型（Claude 蒸馏推理版 / MNN 优化版），统一 Think+Vision 标签，
 *  仓库 taobao-mnn/<id> + MNN/<id>。[sizeBytes] 为已按 MiB/GiB 换算的字节数。 */
private fun qwen35(
    id: String,
    displayName: String,
    description: String,
    sizeBytes: Double,
    recommended: Boolean = false,
): ModelInfo = ModelInfo(
    id = id,
    name = displayName,
    description = description,
    size = sizeBytes.toLong(),
    version = "mnn",
    format = ModelFormat.MNN,
    repo = "taobao-mnn/$id",
    altRepos = listOf("MNN/$id"),
    tags = listOf("Think", "Vision"),
    vendor = "Qwen",
    recommended = recommended,
)

private fun mnn(modelName: String, vendor: String, sizeGb: Double, tags: List<String>, recommended: Boolean = false): ModelInfo =
    ModelInfo(
        id = modelName,
        name = modelName.removeSuffix("-MNN").replace("-", " "),
        description = "$vendor · ${tags.joinToString("/")}",
        size = (sizeGb * 1_000_000_000L).toLong(),
        version = "mnn",
        format = ModelFormat.MNN,
        repo = "taobao-mnn/$modelName",
        altRepos = listOf("MNN/$modelName"),
        tags = tags,
        vendor = vendor,
        recommended = recommended,
    )
