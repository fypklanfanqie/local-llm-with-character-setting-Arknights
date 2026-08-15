package com.rhodesisland.terminal.llm.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lookahead 单实验认证门禁测试（Task 17 Step 1）。
 *
 * 用 ExperimentalPromotionPolicy 验证 lookahead 候选相对基线的 promotion：仅正确性 + 冷启 +
 * ≥10% decode 提升 + 无 TTFT/PSS 回归才通过。不修改生产配置。
 */
class LookaheadCertificationTest {

    private val baseline = BenchmarkSample(decodeTpsMedian = 12f, ttftMsMedian = 900f, peakPssMb = 1100f, sampleCount = 3)

    private fun lookaheadCandidate(
        decodeTps: Float = 14f,
        correctness: Boolean = true,
        samples: Int = 3,
    ) = BenchmarkSample(
        decodeTpsMedian = decodeTps,
        ttftMsMedian = 950f,
        peakPssMb = 1150f,
        sampleCount = samples,
        hotStart = false,
        correctnessOk = correctness,
    )

    @Test
    fun qualifiedLookaheadCertifies() {
        // 14/12 = +16.7% decode，TTFT/PSS 劣化 <30%，冷启 3 样本，正确性 OK。
        assertEquals(PromotionDecision.Promote, ExperimentalPromotionPolicy.evaluate(baseline, lookaheadCandidate()))
    }

    @Test
    fun lookaheadWithoutRepetitionCorrectnessRejects() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, lookaheadCandidate(correctness = false))
        assertTrue(d is PromotionDecision.Reject)
    }

    @Test
    fun lookaheadWithOneSampleIsNotEvidence() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, lookaheadCandidate(samples = 1))
        assertTrue(d is PromotionDecision.Reject)
    }

    @Test
    fun lookaheadBurstSpeedNotSufficientWithoutSustainedGain() {
        // 单样本 + 高突发（decode 高但样本不足）——最高突发不充分（对应 Step 5 精神，这里用样本数表达）。
        val d = ExperimentalPromotionPolicy.evaluate(baseline, lookaheadCandidate(decodeTps = 30f, samples = 1))
        assertTrue(d is PromotionDecision.Reject)
    }
}
