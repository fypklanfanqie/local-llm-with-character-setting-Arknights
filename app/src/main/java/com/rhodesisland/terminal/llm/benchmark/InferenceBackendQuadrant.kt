package com.rhodesisland.terminal.llm.benchmark

import com.rhodesisland.terminal.llm.backend.BackendPreference

/**
 * 推理基准四象限（Task 5 Step 2）。
 *
 * CPU/GPU × 思考开/关 的四个正交配置维度。基准结果按象限归档（[BenchmarkScenarioResult.quadrant]），
 * 使「同模型同设备」的性能/可靠性可按象限对比，而不是混在单一平均值里。
 *
 * 与 [InferenceBenchmarkScenario] 风格一致：持久化键用 [storageKey]（稳定不变，勿用枚举 name 以便
 * 重构重命名），展示名用 [displayName]。
 */
enum class InferenceBackendQuadrant(
    val storageKey: String,
    val displayName: String,
) {
    CPU_THINKING_OFF("CPU_THINKING_OFF", "CPU 思考关"),
    CPU_THINKING_ON("CPU_THINKING_ON", "CPU 思考开"),
    GPU_THINKING_OFF("GPU_THINKING_OFF", "GPU 思考关"),
    GPU_THINKING_ON("GPU_THINKING_ON", "GPU 思考开");

    /** 是否走 GPU（OpenCL）路径。 */
    val usesGpu: Boolean get() = this == GPU_THINKING_OFF || this == GPU_THINKING_ON

    /** 是否请求深度思考（透传为 generate 的 enableThinking）。 */
    val thinkingEnabled: Boolean get() = this == CPU_THINKING_ON || this == GPU_THINKING_ON

    companion object {
        /** 从持久化键还原；未知/空值返回 null（与 [InferenceBenchmarkScenario.fromStorageKey] 一致）。 */
        fun fromStorageKey(key: String?): InferenceBackendQuadrant? =
            entries.firstOrNull { it.storageKey == key }

        /**
         * 由后端偏好 + 思考开关推导被测象限。
         *
         * 映射口径与 [com.rhodesisland.terminal.llm.profile.InferenceProfileResolver] 解析链对齐：
         * - [BackendPreference.MNN_CPU] -> CPU 象限；
         * - [BackendPreference.MNN_GPU] -> GPU 象限；
         * - [BackendPreference.AUTO] -> GPU 象限（AUTO 解析链 GPU 优先，基准按期望路径计 GPU）；
         * - [BackendPreference.MNN_NPU] -> CPU 象限（标准构建不含 QNN 运行时，NPU 偏好解析为 CPU）。
         */
        fun of(backendPreference: BackendPreference, deepThinking: Boolean): InferenceBackendQuadrant =
            when (backendPreference) {
                BackendPreference.MNN_CPU ->
                    if (deepThinking) CPU_THINKING_ON else CPU_THINKING_OFF
                BackendPreference.MNN_GPU, BackendPreference.AUTO ->
                    if (deepThinking) GPU_THINKING_ON else GPU_THINKING_OFF
                BackendPreference.MNN_NPU ->
                    if (deepThinking) CPU_THINKING_ON else CPU_THINKING_OFF
            }
    }
}
