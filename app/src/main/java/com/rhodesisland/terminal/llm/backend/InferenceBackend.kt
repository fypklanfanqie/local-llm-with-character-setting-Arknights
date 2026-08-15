package com.rhodesisland.terminal.llm.backend

import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.llm.GenerationExecutionControl
import com.rhodesisland.terminal.llm.metrics.NativeGenerationSummary
import com.rhodesisland.terminal.llm.template.ThinkingOutputClassifier
import com.rhodesisland.terminal.llm.thinking.ThinkingPolicyTelemetry
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.profile.PowerPolicy

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
     * 最近一次初始化或推理失败的原因（供 [BackendManager] 收集后上报），
     * 解决「所有后端均初始化失败」无诊断信息、无法定位部分芯片失败原因的问题。
     * 成功加载后清空（但生成为同一会话复用，不重 alloc，故不影响）。
     */
    val lastErrorMessage: String?

    /**
     * 加载模型并初始化后端（Task 7）。
     *
     * 运行时配置由 [com.rhodesisland.terminal.llm.profile.InferenceProfileResolver] 生成并规范化为
     * [nativeConfigJson]，[loadConfigHash] 是其唯一重载指纹：同路径 + 同哈希已加载则热复用，
     * 否则按新配置重建。采样参数（temperature/topP/repeatPenalty）已内含于配置 JSON。
     *
     * @param modelPath `.mnn` 目录的 `config.json` 路径
     * @param nativeConfigJson 规范化 native set_config JSON（由 resolver 生成）
     * @param loadConfigHash 配置指纹；模型重载判定的唯一依据
     * @return true 成功（可能为热复用）；false 失败（返回 false 触发回退）
     */
    suspend fun initialize(
        modelPath: String,
        nativeConfigJson: String,
        loadConfigHash: String,
    ): Boolean

    /**
     * 流式生成（消息列表路径，[BackendManager] 统一调用此方法）。
     *
     * MNN 后端把 [messages] 交给 MNN，由模型自带 chat 模板格式化（支持 Qwen/Llama/Gemma/Phi 等多模板）。
     *
     * 本方法**不累积**完整回复：每个 delta 经 [onToken] 实时转发，[onToken] 返回 false 表示策略
     * 截断（后端设 abort、记 [completionReason] 为 POLICY_TRUNCATION）。完整回复由调用方
     * [com.rhodesisland.terminal.provider.local.LocalChatProvider] 作为唯一累加器拼接。
     *
     * @param messages 完整对话历史（system + user/assistant 轮次）
     * @param enableThinking 是否启用深度思考。经 set_config 注入 jinja context `enable_thinking`，
     *        控制推理模型（Qwen3/R1）chat 模板是否生成 `<think>` 推理段；运行时生效，无需重载模型。
     *        无该分支的模板（Llama/Gemma）忽略，无害。
     * @param onToken 流式回调；返回 false 触发策略截断（abort + POLICY_TRUNCATION）。
     * @param batchMaxBytes native 流式批处理缓冲上限（字节）。首个完整可见字符立即回调，
     *        其余按字节或时间达标批量 flush（首 delta 即时性 + 回调次数削减）。Task 6 性能模式
     *        接入前用 Balanced 默认 256。
     * @param batchMaxMs native 流式批处理缓冲时间上限（ms）。Balanced 16；Maximum Speed 24–32。
     * @return native 返回的紧凑版本化 GenerationSummary（[NativeGenerationSummary.parse] 校验过的；
     *         解析失败/未走 native 返回 null）
     */
    suspend fun generateStreamMessages(
        messages: List<ChatMessage>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        enableThinking: Boolean,
        onToken: (String) -> Boolean,
        batchMaxBytes: Int = DEFAULT_BATCH_MAX_BYTES,
        batchMaxMs: Int = DEFAULT_BATCH_MAX_MS,
        downgradeReasons: List<String> = emptyList(),
        executionControl: GenerationExecutionControl? = null,
        powerPolicy: PowerPolicy = PowerPolicy.DEFAULT,
        /** 遥测（Task 1）：请求/生效性能模式，避免记录为 null。 */
        requestedMode: InferencePerformanceMode? = null,
        effectiveMode: InferencePerformanceMode? = null,
        /** 遥测：本次尝试实际应用的配置指纹（loadConfigHash），而非路径哈希。 */
        loadConfigHash: String? = null,
        /** 遥测：后端尝试链（按序变体名，用于分析回退路径）。 */
        attemptTrace: List<String> = emptyList(),
        /** 遥测：首次冷加载 / 配置变化重载耗时（ms）；复用加载为 null。 */
        coldLoadMs: Long? = null,
        warmLoadMs: Long? = null,
        /** Task 1 v2：native decode 步长（1=逐 token，默认；2..4=多 token 步进，native clamp 到 [1,4]）。
         *  即使 step>1，native 每步内仍逐 token 检查 EOS/maxTokens/abort，取消粒度恒为 1 token。 */
        decodeStepTokens: Int = 1,
        // Task 2：思考请求 / 模板能力 / 思考分类器（遥测信封；带默认值，旧调用方不受影响）。
        /** 本轮是否请求了深度思考（deepThinking 设置值）。 */
        thinkingRequested: Boolean? = null,
        /** 模板能力枚举名（ThinkingTemplateCapability）。 */
        templateCapability: String? = null,
        /** 思考分类器实例：实现方在生成结束 finally 内收口分类（ThinkingEffect / EmptyResponseClass），
         *  随遥测 finalize 一并写入记录（取代原 provider 侧补记路径）。 */
        thinkingClassifier: ThinkingOutputClassifier? = null,
        // Task 5：本地思考档位策略快照（单次透传；思考关闭/云端为 null）。
        thinkingPolicy: ThinkingPolicyTelemetry? = null,
        // Task 15：内存准入的上下文降级（配置值 -> 实际值；未降级为 null）。
        configuredContextTokens: Int? = null,
        actualContextTokens: Int? = null,
    ): NativeGenerationSummary?

    /** 流式批处理 Balanced 默认参数（Task 6 性能模式接入前的稳定取值，与设计文档 §流式行一致）。 */
    companion object {
        const val DEFAULT_BATCH_MAX_BYTES = 256
        const val DEFAULT_BATCH_MAX_MS = 16
    }

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
 * @param gpuUtilization GPU/NPU 占用率近似值 0..1；不可用时为 null（CPU 后端恒为 null，
 *        OpenCL/QNN 后端目前亦无可靠读取口径，统一以 null 表示 N/A，不再使用 0.85f 假值）
 * @param memoryUsedMB 占用内存近似值（MB）
 * @param backendName 来源后端名
 */
data class BackendMetrics(
    val tokensPerSecond: Float,
    val gpuUtilization: Float?,
    val memoryUsedMB: Long,
    val backendName: String,
)
