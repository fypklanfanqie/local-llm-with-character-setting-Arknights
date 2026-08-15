package com.rhodesisland.terminal.llm.profile

import com.rhodesisland.terminal.llm.backend.BackendType

/**
 * 运行时配置变体（Task 7）。
 *
 * 每种变体对应一份显式的 native set_config JSON：
 * - [CPU_OPTIMIZED]：low precision/memory + Power_High + 热准入线程数（性能优先）。
 * - [CPU_COMPATIBILITY]：保守 precision/memory/power 枚举，不依赖省略字段继承未知模型默认。
 * - [OPENCL]：MNN 要求的 `thread_num=68` 编码。
 */
enum class RuntimeVariant {
    CPU_OPTIMIZED,
    CPU_COMPATIBILITY,
    OPENCL,
}

/**
 * OpenCL 健康状态（Task 9）：由 BackendHealthStore 持久记录，resolver 据此决定是否把 OpenCL
 * 放进尝试链。UNKNOWN 需先探测（Task 10 probe）；COOLDOWN/CRASH_BLACKLISTED 不进入。
 */
enum class OpenClHealthState { UNKNOWN, PROBE_OK, MODEL_OK, COOLDOWN, CRASH_BLACKLISTED }

/**
 * 单次后端尝试（Task 7 Step 1）。
 *
 * [BackendManager] 按此列表显式加载，不再由 JNI 隐式执行 CPU 安全重试。
 *
 * @param backend 后端类型（MNN_CPU / MNN_GPU / MNN_NPU）。
 * @param variant 运行时配置变体（同后端可有优化/兼容多份）。
 * @param nativeConfigJson 规范化后的 native set_config JSON（键排序，逐字节传给 JNI）。
 * @param loadConfigHash 模型加载指纹：canonical(nativeConfigJson) 的哈希，作为唯一重载指纹。
 * @param requiresProbe 是否需先经 OpenCL 探测/健康记录确认（Task 后期 BackendHealthStore 接入）。
 */
data class BackendAttempt(
    val backend: BackendType,
    val variant: RuntimeVariant,
    val nativeConfigJson: String,
    val loadConfigHash: String,
    val requiresProbe: Boolean,
)

/** 流式批处理策略（只影响 UI/桥接，不参与模型加载指纹）。 */
data class StreamPolicy(
    val batchMaxBytes: Int,
    val batchMaxMs: Int,
)

/**
 * 功耗/调度策略（Task 7 解析产出；具体行为由后续 power/thermal 任务执行）。
 *
 * @param cpuThreads 热准入后的 CPU 线程数（MAXIMUM_SPEED 不能绕过温控上限）。
 * @param lookahead 是否启用投机解码（仅 CPU；MAXIMUM_SPEED 仅在基准证明收益后开启）。
 * @param sustainedMode 是否开启 sustained performance（仅本次生成期间，finally 恢复）。
 * @param aggressiveHint 性能提示目标是否激进（MAXIMUM_SPEED 更积极）。
 */
data class PowerPolicy(
    val cpuThreads: Int,
    val lookahead: Boolean,
    val sustainedMode: Boolean,
    val aggressiveHint: Boolean,
) {
    companion object {
        /** 兜底策略（Balanced 语义：4 线程、无 sustained、温和 hint）；无 plan 的兼容路径使用。 */
        val DEFAULT = PowerPolicy(cpuThreads = 4, lookahead = false, sustainedMode = false, aggressiveHint = false)
    }
}

/** 模型驻留策略（后台/切云后多久释放）。 */
data class ResidencyPolicy(
    val keepAliveMs: Long,
)

/** 计划级安全降级原因（类型化，替代散落的布尔/字符串）。 */
enum class DowngradeReason {
    THERMAL,            // 高温降线程/降模式
    MEMORY,             // 内存准入受限
    OPENCL_UNHEALTHY,   // OpenCL 探测失败或健康记录异常
    BACKEND_UNAVAILABLE,// 后端/变体不可用（如 OpenCL 缺运行时）
    UNSUPPORTED_SETTING,// 已保存但不再支持的选择，解析为 CPU
    QNN_UNAVAILABLE_IN_STANDARD_BUILD, // 标准构建不含 QNN 运行时；legacy NPU 偏好解析为 CPU（Task 11）
    // Task 6：用户请求 lookahead 但该 device+model+variant+native 组合没有基准认证
    // （InferenceCertificationStore 无记录 / 记录无 lookahead 证据 / 变体不匹配）——native
    // config 回落 lookahead=false，仅在基准证明收益的认证存在时才启用。
    LOOKAHEAD_UNCERTIFIED,
    // ===== 模型大小策略（AUTO 仅对总参数量 >7B 使用 GPU）=====
    /** AUTO 下模型总参数量 <= 7B：跳过 GPU，直接 CPU。 */
    AUTO_MODEL_AT_OR_BELOW_7B_CPU,
    /** AUTO 下模型参数元数据未知：安全默认 CPU。 */
    AUTO_MODEL_PARAMETERS_UNKNOWN_CPU,
    // ===== GPU 尝试失败后回退 CPU 的可见原因（BackendManager 并入后续 CPU attempt 遥测）=====
    /** GPU（OpenCL）模型加载失败后回退 CPU。 */
    GPU_LOAD_FALLBACK,
    /** GPU（OpenCL）生成异常回退 CPU（首 delta 前）。 */
    GPU_GENERATION_FALLBACK,
}

/**
 * 每轮推理前由 [InferenceProfileResolver] 生成的不可变执行计划（Task 7）。
 *
 * 覆盖设计规格 §4.3：请求/实际模式、实际上下文与输出上限、流式/功耗/驻留策略、
 * 有序后端尝试列表、全部安全降级原因。BackendManager 只按 [attempts] 显式执行。
 */
data class ResolvedInferencePlan(
    val requestedMode: InferencePerformanceMode,
    val effectiveMode: InferencePerformanceMode,
    val contextTokens: Int,
    /** 用户配置的上下文长度（Task 15：内存准入降级前）；null = 与 [contextTokens] 相同（未降级）。
     *  仅 Provider 在准入降级时设置；resolver 本身不产此字段。 */
    val configuredContextTokens: Int? = null,
    val maxOutputTokens: Int,
    val streamPolicy: StreamPolicy,
    val powerPolicy: PowerPolicy,
    val residencyPolicy: ResidencyPolicy,
    val attempts: List<BackendAttempt>,
    val downgradeReasons: List<DowngradeReason>,
    /** Task 6：native decode 步长（1=逐 token；2..4=多 token 步进，native clamp 到 [1,4]）。
     *  仅当 [InferenceCertificationStore] 认证了该 device+model+variant+native 组合的步进收益
     *  时才 >1，否则恒为 1（多 token 步进保持默认关闭，直到有基准证据）。 */
    val decodeStepTokens: Int = 1,
) {
    /** 首个可用尝试（期望执行的后端/变体）。 */
    val firstAttempt: BackendAttempt? get() = attempts.firstOrNull()
}
