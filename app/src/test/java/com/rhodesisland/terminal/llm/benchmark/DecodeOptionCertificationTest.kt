package com.rhodesisland.terminal.llm.benchmark

import com.rhodesisland.terminal.llm.profile.InferenceProfileResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DecodeOptionCertification 决策核单测：候选清单/胜者选取/可靠性否决/白名单一致性。 */
class DecodeOptionCertificationTest {

    private fun sample(tps: Float, ttft: Float = 500f, pss: Float = 800f) = BenchmarkSample(
        decodeTpsMedian = tps,
        ttftMsMedian = ttft,
        peakPssMb = pss,
        sampleCount = 3,
        correctnessOk = true,
    )

    @Test
    fun candidatesExcludeTq3ForSmallModels() {
        val small = DecodeOptionCertification.candidatesFor(modelParamsB = 1.7f)
        val unknown = DecodeOptionCertification.candidatesFor(modelParamsB = null)
        // 小模型与未知模型同表：无 12 候选；首选 TQ4、末位 dynamic 高位
        assertTrue(small.none { it.attentionMode == 12 })
        assertTrue(unknown.none { it.attentionMode == 12 })
        assertEquals(14, small.first().attentionMode)
        assertEquals(8, small.last().attentionMode)
        assertEquals(8, small.last().dynamicOption)
    }

    @Test
    fun candidatesIncludeTq3OnlyForLargeModels() {
        val large = DecodeOptionCertification.candidatesFor(modelParamsB = 7.6f)
        assertTrue(large.any { it.attentionMode == 12 && it.dynamicOption == 0 })
    }

    @Test
    fun winnerIsFastestPromotedCandidate() {
        val baseline = sample(tps = 10f)
        val candidates = mapOf(
            DecodeOptionCertification.Candidate(14, 0) to sample(tps = 12f),   // +20% Promote
            DecodeOptionCertification.Candidate(9, 0) to sample(tps = 11.5f),  // +15% Promote 但更慢
            DecodeOptionCertification.Candidate(8, 8) to sample(tps = 9.9f),   // 不达标 Reject
        )
        val (winner, reasons) = DecodeOptionCertification.selectWinner(baseline, candidates)
        assertEquals(DecodeOptionCertification.Candidate(14, 0), winner)
        assertTrue(reasons.containsKey("attention=8,dynamic=8"))
    }

    @Test
    fun allRejectedYieldsNullWinnerWithReasons() {
        val baseline = sample(tps = 10f)
        val candidates = mapOf(
            DecodeOptionCertification.Candidate(14, 0) to sample(tps = 10.5f), // <10% 拒
            DecodeOptionCertification.Candidate(9, 0) to sample(
                tps = 13f, pss = 1200f,
            ), // PSS 劣化 >30% 拒
        )
        val (winner, reasons) = DecodeOptionCertification.selectWinner(baseline, candidates)
        assertNull(winner)
        assertEquals(2, reasons.size)
    }

    @Test
    fun incorrectnessSampleAlwaysRejected() {
        val baseline = sample(tps = 10f)
        val garbage = BenchmarkSample(
            decodeTpsMedian = 30f, ttftMsMedian = 400f, peakPssMb = 700f,
            sampleCount = 3, correctnessOk = false, // FFFF/复读：性能再好也不许晋级
        )
        val (winner, _) = DecodeOptionCertification.selectWinner(
            baseline,
            mapOf(DecodeOptionCertification.Candidate(14, 0) to garbage),
        )
        assertNull(winner)
    }

    @Test
    fun reliabilityVetoOnEmptyOrFallbackRounds() {
        val clean = ReliabilityResult(
            emptyResponseClasses = mapOf("NONE" to 10),
            fallbackCount = 0,
            nonEmptySuccessRate = 1f,
            totalRounds = 10,
        )
        assertTrue(DecodeOptionCertification.reliabilityVeto(clean).isEmpty())

        val withGarbage = clean.copy(nonEmptySuccessRate = 0.9f)
        assertTrue(DecodeOptionCertification.reliabilityVeto(withGarbage).isNotEmpty())

        val withFallback = clean.copy(fallbackCount = 2)
        assertTrue(DecodeOptionCertification.reliabilityVeto(withFallback).isNotEmpty())
    }

    @Test
    fun orchestrationWhitelistMatchesResolverWhitelist() {
        for (candidate in DecodeOptionCertification.candidatesFor(modelParamsB = 7.6f)) {
            assertTrue(
                "编排候选 ${candidate.label} 必须在 resolver 白名单内（漂移=认证永远不生效）",
                DecodeOptionCertification.whitelisted(candidate),
            )
        }
        // 白名单外防御
        assertTrue(!DecodeOptionCertification.whitelisted(DecodeOptionCertification.Candidate(10, 0)))
        assertTrue(!DecodeOptionCertification.whitelisted(DecodeOptionCertification.Candidate(8, 2)))
        // resolver 常量存在性（引用即编译校验）
        assertTrue(InferenceProfileResolver.ATTENTION_MODE_WHITELIST.contains(8))
        assertEquals(setOf(0, 8), InferenceProfileResolver.DYNAMIC_OPTION_WHITELIST)
    }
}
