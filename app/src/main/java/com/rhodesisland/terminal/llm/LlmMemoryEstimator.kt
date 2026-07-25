package com.rhodesisland.terminal.llm

import android.content.Context
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.provider.local.ModelPathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 本地 LLM 上下文长度内存估算器
 *
 * 基于 KV cache 估算：
 *   memory = contextLen * numLayers * hiddenSize * 2(K+V) * bytesPerElement
 *
 * 其中 bytesPerElement 按 fp16（2 字节）估算，precision="low" 下 MNN 的 KV 缓存通常为此精度。
 * 该值仅为量级参考，实际运行时还包括模型权重、激活、视觉编码器等额外占用。
 */
object LlmMemoryEstimator {

    /** 模型维度 */
    data class ModelDims(
        val hiddenSize: Int,
        val layerCount: Int,
    )

    /** 内存估算结果 */
    sealed class MemoryEstimate {
        data class Value(val bytes: Long) : MemoryEstimate()
        data object Unavailable : MemoryEstimate()
    }

    /** fp16 = 2 字节/元素 */
    private const val BYTES_PER_ELEMENT = 2L

    /** K + V 两个向量 */
    private const val KV_FACTOR = 2L

    /** 已知内置模型的维度兜底表（当 llm_config.json 缺少 layer_nums 时使用） */
    private val KNOWN_MODEL_DIMS: Map<String, ModelDims> = mapOf(
        // Qwen3.5 系列：hidden_size 来自 llm_config.json；layerCount 为同代 Qwen 近似值
        "Qwen3.5-0.8B-MNN" to ModelDims(hiddenSize = 1024, layerCount = 24),
        "Qwen3.5-2B-MNN" to ModelDims(hiddenSize = 2048, layerCount = 28),
        "Qwen3.5-4B-MNN" to ModelDims(hiddenSize = 2560, layerCount = 32),
        "Qwen3.5-9B-MNN" to ModelDims(hiddenSize = 3584, layerCount = 28),
        // Qwen3.5-35B-A3B 为 MoE，KV cache 不能简单按 dense 估算，故不加入兜底表

        // 推理/其他内置模型
        "DeepSeek-R1-1.5B-Qwen-MNN" to ModelDims(hiddenSize = 1536, layerCount = 28),
        "Qwen3-4B-MNN" to ModelDims(hiddenSize = 2560, layerCount = 36),
        "DeepSeek-R1-7B-Qwen-MNN" to ModelDims(hiddenSize = 3584, layerCount = 28),
        "DeepSeek-R1-0528-Qwen3-8B-MNN" to ModelDims(hiddenSize = 4096, layerCount = 36),

        // 其他厂商
        "Llama-3.2-1B-Instruct-MNN" to ModelDims(hiddenSize = 2048, layerCount = 16),
        "Llama-3.2-3B-Instruct-MNN" to ModelDims(hiddenSize = 3072, layerCount = 28),
        "gemma-2-2b-it-MNN" to ModelDims(hiddenSize = 2304, layerCount = 26),
        "SmolLM2-360M-Instruct-MNN" to ModelDims(hiddenSize = 960, layerCount = 32),
    )

    /**
     * 估算当前已选模型在指定上下文长度下的 KV cache 内存。
     *
     * @return [MemoryEstimate.Value] 或 [MemoryEstimate.Unavailable]（未选模型 / 未下载 / 读取失败）
     */
    suspend fun estimate(
        context: Context,
        settingsRepository: SettingsRepository,
        contextLen: Int,
    ): MemoryEstimate = withContext(Dispatchers.IO) {
        val modelId = settingsRepository.getActiveLocalModelIdNow()
        if (modelId.isNullOrBlank()) return@withContext MemoryEstimate.Unavailable

        val dims = readModelDims(context, modelId) ?: return@withContext MemoryEstimate.Unavailable
        val bytes = contextLen.toLong() * dims.layerCount * dims.hiddenSize * KV_FACTOR * BYTES_PER_ELEMENT
        MemoryEstimate.Value(bytes)
    }

    /**
     * 读取模型维度：优先从模型目录的 llm_config.json 解析 hidden_size + layer_nums/num_hidden_layers，
     * 解析不到 layer 时回退 [KNOWN_MODEL_DIMS]。
     */
    fun readModelDims(context: Context, modelId: String): ModelDims? {
        val configPath = ModelPathResolver.getLoadPath(context, modelId) ?: return null
        val modelDir = File(configPath).parentFile ?: return null
        val llmConfigFile = File(modelDir, "llm_config.json")
        if (!llmConfigFile.exists()) return null

        return try {
            val json = JSONObject(llmConfigFile.readText())
            val fallback = KNOWN_MODEL_DIMS[modelId]

            val hiddenSize = json.optInt("hidden_size", 0)
                .takeIf { it > 0 }
                ?: fallback?.hiddenSize
                ?: return null

            val layerCount = json.optInt("layer_nums", 0)
                .takeIf { it > 0 }
                ?: json.optInt("num_hidden_layers", 0).takeIf { it > 0 }
                ?: fallback?.layerCount
                ?: return null

            ModelDims(hiddenSize = hiddenSize, layerCount = layerCount)
        } catch (e: Exception) {
            null
        }
    }

    /** 将字节数格式化为人类可读的 MB/GB 字符串 */
    fun formatMemory(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.getDefault(), "%.0f MB", mb)
        }
    }
}
