package com.rhodesisland.terminal.llm.benchmark

import com.rhodesisland.terminal.llm.profile.InferenceProfileResolver

/**
 * KV 量化 / 动态量化档位认证编排（Wave 3，JVM 纯决策核）。
 *
 * 固定候选序（保守 -> 激进）：[(14,0), (9,0), (12,0 仅大模型), (8,8)]，基线 (8,0)。
 * - 14=KV-TQ4：KV 内存 −30%+、decode 带宽下降（官方推荐 4B+）——首选候选；
 * - 9=K-int8：「精度几乎无损」（官方口径），TQ 系不适用时的次选；
 * - 12=KV-TQ3：极致压缩，仅 ≥4B 且前两者都失败时尝试（<1B 精度损失大）；
 * - (8,8)=dynamic_option 高位：SME2 在线权重重排 decode 加速；非 SME2 机器是纯 no-op，
 *   会自然过不了 ≥10% 门禁（浪费几分钟基准时间，不会错误采纳）。
 *
 * 流程：逐候选 FIXED_DECODE（1 预热 + [RECORDED_ROUNDS] 记录，匹配 MIN_SAMPLES）->
 * [ExperimentalPromotionPolicy.evaluate] 过闸（≥10% decode 收益 / ≤30% TTFT·PSS 劣化 /
 * correctnessOk 必须为 true）-> 胜者取 medianDecodeTps 最高者 -> 可靠性否决
 * （[ReliabilityResult.nonEmptySuccessRate]==1f 且无 fallback）-> 经 toCertifiedOptions 落盘。
 *
 * 本对象只做**决策**（排序/比较/胜者/可靠性判定）；基准执行与存储 IO 由 UI 层接线
 * （BackendSettingsScreen，与既有 Lookahead 认证入口同模式）。全部纯函数 JVM 可测。
 */
object DecodeOptionCertification {

    /** 单个候选项（attention_mode, dynamic_option）。 */
    data class Candidate(val attentionMode: Int, val dynamicOption: Int) {
        val label: String get() = "attention=$attentionMode,dynamic=$dynamicOption"
    }

    /** 基线（现状安全对）。 */
    val BASELINE = Candidate(8, 0)

    /** 记录轮数（≥ ExperimentalPromotionPolicy.MIN_SAMPLES=3）。 */
    const val RECORDED_ROUNDS = 3

    /** 预热轮数。 */
    const val WARMUP_ROUNDS = 1

    /** 可靠性确认轮数（胜者复核；EMPTY_RESPONSE_CHECK 探针）。 */
    const val RELIABILITY_ROUNDS = 10

    /** mode 12（KV-TQ3）的最低参数量门槛（十亿）：官方建议 <1B 不用，取 4B 更保守。 */
    const val TQ3_MIN_PARAMS_B = 4f

    /**
     * 候选清单：大模型含 TQ3；小模型（<4B）剔除。
     */
    fun candidatesFor(modelParamsB: Float?): List<Candidate> {
        val list = mutableListOf(
            Candidate(14, 0),
            Candidate(9, 0),
        )
        if (modelParamsB != null && modelParamsB >= TQ3_MIN_PARAMS_B) {
            list += Candidate(12, 0)
        }
        list += Candidate(8, 8)
        return list
    }

    /**
     * 从「基线 + 各候选场景结果」中选出 Promote 胜者。
     *
     * @param baselineSample 基线样本（(8,0) 的 FIXED_DECODE 结果）。
     * @param candidateSamples 候选 -> 样本（调用方已按 [candidatesFor] 跑完）。
     * @return 胜者候选 + 决策理由（各候选拒绝原因）；全灭返回 null + 理由。
     */
    fun selectWinner(
        baselineSample: BenchmarkSample,
        candidateSamples: Map<Candidate, BenchmarkSample>,
    ): Pair<Candidate?, Map<String, List<String>>> {
        val reasons = mutableMapOf<String, List<String>>()
        var winner: Candidate? = null
        var winnerTps = baselineSample.decodeTpsMedian // 胜者须严格优于基线（evaluate 已保证 ≥1.10×）
        for ((candidate, sample) in candidateSamples) {
            when (val decision = ExperimentalPromotionPolicy.evaluate(baselineSample, sample)) {
                is PromotionDecision.Promote -> {
                    if (sample.decodeTpsMedian > winnerTps) {
                        winner = candidate
                        winnerTps = sample.decodeTpsMedian
                    }
                }
                is PromotionDecision.Reject -> reasons[candidate.label] = decision.reasons
            }
        }
        return winner to reasons
    }

    /**
     * 胜者可靠性否决：nonEmptySuccessRate 必须 ==1f 且零 GPU 回退（CPU 候选恒 0 回退）。
     * 乱码/复读轮已在 runner 侧并入 nonEmpty 口径（sanity 非 SANE 不计成功），此处只看汇总值。
     */
    fun reliabilityVeto(result: ReliabilityResult): List<String> {
        val reasons = mutableListOf<String>()
        if (result.totalRounds <= 0) reasons += "可靠性未执行（totalRounds=0）"
        if (result.nonEmptySuccessRate < 1f) {
            reasons += "可靠性未满分（${result.nonEmptySuccessRate} < 1.0，含空响应/乱码/复读轮）"
        }
        if (result.fallbackCount > 0) reasons += "出现后端回退（${result.fallbackCount} 轮）"
        return reasons
    }

    /** 候选是否在 resolver 白名单内（防御：编排层与 resolver 白名单漂移时早失败）。 */
    fun whitelisted(candidate: Candidate): Boolean =
        candidate.attentionMode in InferenceProfileResolver.ATTENTION_MODE_WHITELIST &&
            candidate.dynamicOption in InferenceProfileResolver.DYNAMIC_OPTION_WHITELIST
}
