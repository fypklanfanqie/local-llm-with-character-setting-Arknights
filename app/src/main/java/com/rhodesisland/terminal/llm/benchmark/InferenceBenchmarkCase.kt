package com.rhodesisland.terminal.llm.benchmark

import kotlinx.serialization.Serializable

/**
 * 一次基准用例的完整坐标（Task 5 Step 2）。
 *
 * 性能基准按「场景 + 四象限」测量（[LocalInferenceBenchmarkRunner.run]）；
 * 可靠性基准按用例整组跑固定轮数（[LocalInferenceBenchmarkRunner.runReliability]）。
 *
 * 三个指纹维度与 [BenchmarkScenarioResult] 的归档分组口径一致：模型指纹（config.json 内容
 * SHA-256 前 16 hex，与 [com.rhodesisland.terminal.llm.backend.BackendManager] 健康记录口径一致）、
 * 设备指纹（SoC/Android/ABI）、配置哈希（模型路径+线程数+上下文长度+模式等）。
 */
@Serializable
data class InferenceBenchmarkCase(
    /** 场景（[InferenceBenchmarkScenario.EMPTY_RESPONSE_CHECK] 等）。 */
    val scenario: InferenceBenchmarkScenario,
    /** 被测象限（CPU/GPU × 思考开/关）。 */
    val quadrant: InferenceBackendQuadrant,
    /** 模型指纹。 */
    val modelFingerprint: String,
    /** 设备指纹。 */
    val deviceFingerprint: String,
    /** 生效配置哈希。 */
    val configHash: String,
)
