package com.rhodesisland.terminal.llm.thinking

import kotlinx.serialization.Serializable

/**
 * 本轮思考策略的遥测快照（随 [com.rhodesisland.terminal.llm.metrics.InferenceTurnRecord] 持久化）。
 *
 * 只记录枚举名、数值和证据 ID，**不记录用户问题正文**。思考关闭/云端轮次为 null。
 *
 * 在 [LocalThinkingPlan] 解析完成时一次构造，随
 * `LocalChatProvider -> BackendManager -> InferenceBackend -> MnnBackend -> InferenceTelemetry.finalize`
 * 单次透传，避免 provider 在生成结束后补写上一轮记录。
 */
@Serializable
data class ThinkingPolicyTelemetry(
    /** 设置页选择的档位（auto/short/medium/long）。 */
    val requestedLevel: String,
    /** 实际执行档位（AUTO 解析后的受限子集，否则与请求档位一致）。 */
    val effectiveLevel: String,
    /** AUTO 复杂度分类（TRIVIAL/SIMPLE/STANDARD/COMPLEX）；手动档为 null。 */
    val complexity: String?,
    /** 本轮是否整体跳过思考（仅 AUTO→TRIVIAL 为 true）。 */
    val skipThinking: Boolean = false,
    /** 控制方式（PROMPT_FALLBACK / NATIVE_BUDGET）。 */
    val controlMode: String,
    /** 思考软目标时长范围（ms）。 */
    val targetMinMs: Long,
    val targetMaxMs: Long,
    /** 建议的思考核验点上限。 */
    val checkpointBudget: Int,
    /** 生成模式：收敛后统一为单阶段生成、与正文共享同一 maxTokens 上限。 */
    val generationMode: String = SINGLE_PASS_SHARED_LIMIT,
    /** 旧两阶段思考的硬 token 上限；新记录恒为 0，仅保留解码旧 JSON（诊断不显示）。 */
    @Deprecated("旧两阶段记录兼容；新记录恒为 0")
    val thinkingCapTokens: Int = 0,
    /** 原生预算能力判定（UNVERIFIED / VERIFIED）。 */
    val nativeBudgetCapability: String,
) {
    companion object {
        /** 单阶段生成、思考与正文共享同一 maxTokens 上限（无独立思考 token cap）。 */
        const val SINGLE_PASS_SHARED_LIMIT = "SINGLE_PASS_SHARED_LIMIT"

        /**
         * 从本轮 [LocalThinkingPlan] 构造遥测快照；思考关闭（plan 为 null）时返回 null。
         * 新记录固定 [SINGLE_PASS_SHARED_LIMIT]、思考 cap 恒为 0（纯软提示策略，无两阶段硬上限）。
         * @param nativeBudgetCapability [NativeThinkingBudgetCapabilityResolver] 的判定结果名。
         */
        @Suppress("DEPRECATION") // from() 只写入默认 0，兼容旧 JSON 读取，不再读 plan cap。
        fun from(
            plan: LocalThinkingPlan?,
            nativeBudgetCapability: String,
        ): ThinkingPolicyTelemetry? = plan?.let {
            ThinkingPolicyTelemetry(
                requestedLevel = it.requestedLevel.storageKey,
                effectiveLevel = it.effectiveLevel.storageKey,
                complexity = it.complexity?.name,
                skipThinking = it.skipThinking,
                controlMode = it.controlMode.name,
                targetMinMs = it.targetMinMs,
                targetMaxMs = it.targetMaxMs,
                checkpointBudget = it.checkpointBudget,
                generationMode = SINGLE_PASS_SHARED_LIMIT,
                thinkingCapTokens = 0,
                nativeBudgetCapability = nativeBudgetCapability,
            )
        }
    }
}
