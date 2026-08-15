package com.rhodesisland.terminal.llm.backend

import com.rhodesisland.terminal.llm.metrics.CompletionReason
import com.rhodesisland.terminal.llm.template.EmptyResponseClass

/**
 * 生成结果回退判定器（Task 4）：判定「首 delta 前 GPU 空输出」是否可回退 CPU。
 *
 * 只**消费**（不重新分类）[EmptyResponseClass]——空响应分类（EOS_EMPTY / THINK_ONLY 等）由
 * [com.rhodesisland.terminal.llm.template.ThinkingOutputClassifier.finish] 在 MnnBackend 的 finally 内
 * 收口产生（Task 2），本判定器复用其结果做零输出回退决策，绝不二次 finish（避免状态污染）。
 *
 * 全部满足才回退：
 * a. 后端为 [BackendType.MNN_GPU] 且策略为 [EmptyOutputFallbackPolicy.CPU_BEFORE_FIRST_DELTA]；
 * b. 零输出硬条件：[generatedTokens] == 0 且 [callbackBytes] == 0（首 delta 前）；
 * c. 完成原因不在终止集合（USER_CANCEL/TIMEOUT/THERMAL_STOP/POLICY_TRUNCATION/BACKEND_FAILURE——
 *    这些是 requestStop/异常路径，已有既有机制处理，绝不走本回退）；
 * d. 空响应分类属于可回退集合：EOS_EMPTY / MAX_TOKENS_EMPTY / TEMPLATE_SUPPRESSED_OUTPUT，
 *    以及 THINK_ONLY（仅当思考关闭——思考开启时 THINK_ONLY 是模型合法行为，不回退）；
 *    NONE/WHITESPACE_ONLY/PREFILL_FAILURE/DECODE_FAILURE/CANCELLED/TIMEOUT/THERMAL_STOP 不可回退
 *    （NONE 表示有正文或分类器未判定；PREFILL/DECODE_FAILURE 走既有异常/失败机制）；
 * e. 分类缺失（null）保守不回退（分类器缺失 = 信息不足）。
 *
 * 纯 Kotlin、无 Android 依赖，JVM 单测直测。
 */
object GenerationOutcomeClassifier {

    /**
     * 注意：**「计划链中确实存在可用的后续 CPU attempt」条件由调度层承担，本谓词不含该条件**——
     * [BackendManager] 的 attempt 循环（推进 / 会话失败黑名单 / canTryNextBackend）与链末端兜底
     * （无后续 attempt 时原样返回本次可回退结果）负责检查可用性；本谓词只回答
     * 「这一次空输出是否值得发起回退」。
     *
     * @param policy 本轮输出策略（[LocalChatProvider] 按后端偏好构造）。
     * @param backend 刚完成生成的尝试后端。
     * @param completionReason 本轮终止原因（请求级 reason 优先，其次单 attempt 摘要）。
     * @param emptyResponseClass 分类器已产出的空响应分类（null = 分类器缺失/未收口，保守不回退）。
     * @param generatedTokens 本轮生成 token 数（0 = 零输出）。
     * @param callbackBytes 流式回调累计 UTF-8 字节数（0 = 无任何可见 delta）。
     * @param thinkingRequested 本轮是否请求了深度思考（THINK_ONLY 回退性的唯一前提）。
     */
    fun isEligibleForCpuFallback(
        policy: GenerationOutputPolicy,
        backend: BackendType,
        completionReason: CompletionReason?,
        emptyResponseClass: EmptyResponseClass?,
        generatedTokens: Int,
        callbackBytes: Long,
        thinkingRequested: Boolean,
    ): Boolean {
        // a. 仅 GPU 后端 + 回退策略开启。
        if (backend != BackendType.MNN_GPU) return false
        if (policy.emptyOutputFallback != EmptyOutputFallbackPolicy.CPU_BEFORE_FIRST_DELTA) return false
        // b. 零输出硬条件（首 delta 前；回退不拼接，任何已输出字节都意味着开始显示）。
        if (generatedTokens != 0 || callbackBytes != 0L) return false
        // c. 终止集合绝不回退（requestStop/异常路径由既有机制处理）。
        if (completionReason in TERMINAL_REASONS) return false
        // e. 分类缺失（分类器未收口/未使用）保守不回退。
        val clazz = emptyResponseClass ?: return false
        // d. 可回退集合；THINK_ONLY 仅思考关闭时回退（思考开启时 THINK_ONLY 是模型合法行为）。
        if (clazz == EmptyResponseClass.THINK_ONLY) return !thinkingRequested
        return clazz in FALLBACK_EMPTY_CLASSES
    }

    /** 终止集合：取消/超时/热停/策略截断/后端失败——既有 requestStop 与 catch 路径已处理，绝不走本回退。 */
    private val TERMINAL_REASONS = setOf(
        CompletionReason.USER_CANCEL,
        CompletionReason.TIMEOUT,
        CompletionReason.THERMAL_STOP,
        CompletionReason.POLICY_TRUNCATION,
        CompletionReason.BACKEND_FAILURE,
    )

    /** 可回退的空响应分类（零输出 + 完成路径下的「模型没说话」形态）。 */
    private val FALLBACK_EMPTY_CLASSES = setOf(
        EmptyResponseClass.EOS_EMPTY,
        EmptyResponseClass.MAX_TOKENS_EMPTY,
        EmptyResponseClass.TEMPLATE_SUPPRESSED_OUTPUT,
    )
}
