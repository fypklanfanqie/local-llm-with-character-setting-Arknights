package com.rhodesisland.terminal.llm.backend

import com.rhodesisland.terminal.data.model.ChatMessage

/**
 * 推理后端抽象接口
 *
 * MNN 后端（[MnnBackend] ×3：CPU/OpenCL/QNN）实现本接口，供 [BackendManager] 统一调度。
 *
 * 设计约定：
 * - **MNN-only**：`.mnn` 模型走 MNN（CPU/OpenCL/QNN）。（GGUF/llama.cpp 支持已移除。）
 * - **聊天模板**：MNN 后端接收**消息列表**（[generateStreamMessages]），由 MNN 按各模型自带模板
 *   格式化（Qwen=ChatML，Llama/Gemma/Phi 各异）。
 * - 流式生成通过 [generateStreamMessages] 的 [onToken] 回调实时回传每个 token；
 *   回调返回 false 表示请求停止（后端在下一轮检测后中断）。
 * - OpenCL/QNN 初始化失败时 [initialize] 返回 false，由 [BackendManager] 自动回退到 MNN_CPU。
 */
interface InferenceBackend {

    /** 后端类型 */
    val backendType: BackendType

    /** 展示名（如 "MNN OpenCL GPU"） */
    val backendName: String

    /** 当前设备/运行时是否支持此后端（MNN 需 libMNN.so；QNN 另需 libQnnHtp.so 等） */
    val isSupported: Boolean

    /** 是否已加载模型 */
    val isModelLoaded: Boolean

    /** 当前加载的模型路径（未加载为 null） */
    val currentModelPath: String?

    /**
     * 加载模型并初始化后端。
     * @param modelPath `.mnn` 目录的 `config.json` 路径
     * @param contextLength 上下文长度
     * @param threads CPU 线程数
     * @param lookahead CPU 模式下是否启用 lookahead n-gram 投机解码（仅 MNN CPU 后端生效；
     *        非 cpu 后端忽略）。改值需重载模型才生效。
     * @param temperature 采样温度。MNN 采样器在 load() 内一次性构建，须在加载前传入才能生效；
     *        改值由 [BackendManager] 纳入重载指纹，触发下次重载。
     * @param topP top-p 采样（MNN 键 `topP`）。AppConfig 常量。
     * @param repeatPenalty 重复惩罚（MNN 键 `repetition_penalty`）。AppConfig 常量。
     * @return true 成功；false 失败（返回 false 触发回退）
     */
    suspend fun initialize(
        modelPath: String,
        contextLength: Int,
        threads: Int,
        lookahead: Boolean,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
    ): Boolean

    /**
     * 流式生成（消息列表路径，[BackendManager] 统一调用此方法）。
     *
     * MNN 后端把 [messages] 交给 MNN，由模型自带 chat 模板格式化（支持 Qwen/Llama/Gemma/Phi 等多模板）。
     *
     * @param messages 完整对话历史（system + user/assistant 轮次）
     * @param enableThinking 是否启用深度思考。经 set_config 注入 jinja context `enable_thinking`，
     *        控制推理模型（Qwen3/R1）chat 模板是否生成 `<think>` 推理段；运行时生效，无需重载模型。
     *        无该分支的模板（Llama/Gemma）忽略，无害。
     * @return 完整生成文本
     */
    suspend fun generateStreamMessages(
        messages: List<ChatMessage>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        enableThinking: Boolean,
        onToken: (String) -> Boolean,
    ): String

    /** 中断当前生成（非阻塞，下一轮 token 前检测） */
    suspend fun stopGeneration()

    /** 释放后端资源（模型/上下文） */
    fun release()

    /** 性能指标（供浮窗使用） */
    fun getBackendMetrics(): BackendMetrics
}

/**
 * 后端类型。MNN 侧：MNN_CPU / MNN_GPU(OpenCL) / MNN_NPU(QNN)。
 */
enum class BackendType(val displayName: String, val description: String) {
    MNN_CPU("MNN CPU", "MNN · CPU 推理，兼容性最好"),
    MNN_GPU("MNN OpenCL GPU", "MNN · OpenCL GPU 加速"),
    MNN_NPU("MNN QNN NPU", "MNN · 高通 Hexagon NPU"),
}

/**
 * 用户后端偏好（设置项）。
 * - [AUTO]：由 [BackendSelector] 按设备能力自动推荐（MNN_NPU > MNN_GPU > MNN_CPU）。
 * - 显式选项：强制首选对应后端，不可用时按回退链回退。
 */
enum class BackendPreference(val storageKey: String, val displayName: String) {
    AUTO("AUTO", "自动（推荐）"),
    MNN_CPU("MNN_CPU", "强制 MNN CPU"),
    MNN_GPU("MNN_GPU", "强制 MNN GPU"),
    MNN_NPU("MNN_NPU", "强制 MNN NPU");

    companion object {
        fun fromKey(key: String?): BackendPreference =
            entries.firstOrNull { it.storageKey == key } ?: AUTO
    }
}

/**
 * 后端性能指标。
 * @param tokensPerSecond 当前生成速度（tokens/s）
 * @param gpuUtilization GPU/NPU 占用率近似值 0..1（CPU 后端恒为 0）
 * @param memoryUsedMB 占用内存近似值（MB）
 * @param backendName 来源后端名
 */
data class BackendMetrics(
    val tokensPerSecond: Float,
    val gpuUtilization: Float,
    val memoryUsedMB: Long,
    val backendName: String,
)
