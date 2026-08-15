package com.rhodesisland.terminal.ui.settings

import com.rhodesisland.terminal.llm.benchmark.BenchmarkScenarioResult
import com.rhodesisland.terminal.llm.benchmark.InferenceBackendQuadrant
import com.rhodesisland.terminal.llm.benchmark.InferenceBenchmarkCase
import com.rhodesisland.terminal.llm.benchmark.InferenceBenchmarkScenario
import com.rhodesisland.terminal.llm.benchmark.InferenceCertificationStore
import com.rhodesisland.terminal.llm.metrics.BenchmarkSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.chatbyyourside.llm.backend.MnnBridge

/**
 * Lookahead 认证闭环判定链测试（Task 7 Step 3）。
 *
 * 覆盖 [decideLookaheadCertification] 的 evaluate → toCertifiedOptions 纯映射：
 * Promote → Certified（variant 由象限推导、lookahead 证据落位、native 身份归档）；
 * Reject → NotCertified（原因透传）；native 身份缺失 → NotCertified（不落盘）。
 * 另含 Task 6 M-3 转交项：认证记录键派生一致性（落盘键 == 生产查证键，见 [certificationKeyConsistency]）。
 */
class LookaheadCertificationDecisionTest {

    private val case = InferenceBenchmarkCase(
        scenario = InferenceBenchmarkScenario.FIXED_DECODE,
        quadrant = InferenceBackendQuadrant.CPU_THINKING_OFF,
        modelFingerprint = "model-a",
        deviceFingerprint = "device-a",
        configHash = "cfg-hash",
    )

    private fun result(decodeTps: Float, sampleCount: Int = 3, coolRun: Boolean = true): BenchmarkScenarioResult =
        BenchmarkScenarioResult(
            scenario = InferenceBenchmarkScenario.FIXED_DECODE,
            deviceFingerprint = case.deviceFingerprint,
            configFingerprint = case.configHash,
            summary = BenchmarkSummary(medianDecodeTps = decodeTps, medianTtftMs = 900f, peakPssMb = 1100L),
            recordedSampleCount = sampleCount,
            warmupSampleCount = 1,
            coolRun = coolRun,
        )

    /** 基线 12 tok/s；候选 14 tok/s（+16.7%，满足 ≥10%），样本 3（满足 MIN_SAMPLES）。 */
    private val baseline = result(decodeTps = 12f)
    private val candidate = result(decodeTps = 14f)

    @Test
    fun promoteProducesCertifiedWithLookaheadEvidenceAndNativeIdentity() {
        val decision = decideLookaheadCertification(
            baseline = baseline,
            candidate = candidate,
            case = case,
            nativeBuildId = "build-1",
            mnnCommit = "abc123",
            nowElapsedMs = 5000L,
        )
        assertTrue("合格候选应判定 Certified: $decision", decision is LookaheadCertificationDecision.Certified)
        val options = (decision as LookaheadCertificationDecision.Certified).options
        // CPU 象限 -> CPU_OPTIMIZED 变体（lookahead 认证只对 CPU 变体有意义）。
        assertEquals("CPU_OPTIMIZED", options.variant)
        // lookahead 开 vs 关对比基准 -> lookahead 证据为真。
        assertTrue(options.lookahead)
        // 步进不在本任务范围：候选步长恒 1。
        assertEquals(1, options.decodeStepTokens)
        // 指纹与身份来自 case / 调用方传入的 native 身份（与生产查证同源）。
        assertEquals(case.deviceFingerprint, options.deviceFingerprint)
        assertEquals(case.modelFingerprint, options.modelFingerprint)
        assertEquals("build-1", options.nativeBuildId)
        assertEquals("abc123", options.mnnCommit)
        assertEquals(case.configHash, options.certifiedConfigHash)
        assertEquals(5000L, options.certifiedAtElapsedMs)
    }

    @Test
    fun gpuQuadrantCaseMapsToOpenclVariant() {
        val gpuCase = case.copy(quadrant = InferenceBackendQuadrant.GPU_THINKING_OFF)
        val decision = decideLookaheadCertification(
            baseline = baseline,
            candidate = candidate,
            case = gpuCase,
            nativeBuildId = "build-1",
            mnnCommit = "abc123",
            nowElapsedMs = 0L,
        )
        assertTrue(decision is LookaheadCertificationDecision.Certified)
        assertEquals("GPU 象限认证应落到 OPENCL 变体", "OPENCL", (decision as LookaheadCertificationDecision.Certified).options.variant)
    }

    @Test
    fun insufficientSpeedRejectsWithReason() {
        // 候选 12.5/12 = +4.2% < 10%：Reject，原因透传给 UI 展示。
        val decision = decideLookaheadCertification(
            baseline = baseline,
            candidate = result(decodeTps = 12.5f),
            case = case,
            nativeBuildId = "build-1",
            mnnCommit = "abc123",
            nowElapsedMs = 0L,
        )
        assertTrue(decision is LookaheadCertificationDecision.NotCertified)
        val reasons = (decision as LookaheadCertificationDecision.NotCertified).reasons
        assertTrue("Reject 原因应可读: $reasons", reasons.isNotEmpty())
        assertTrue(reasons.any { it.contains("10%") })
    }

    @Test
    fun insufficientSamplesRejects() {
        // 记录轮样本 < MIN_SAMPLES=3（如热态/异常轮被剔除）：不得认证。
        val decision = decideLookaheadCertification(
            baseline = result(decodeTps = 12f, sampleCount = 2),
            candidate = result(decodeTps = 14f, sampleCount = 2),
            case = case,
            nativeBuildId = "build-1",
            mnnCommit = "abc123",
            nowElapsedMs = 0L,
        )
        assertTrue(decision is LookaheadCertificationDecision.NotCertified)
        assertTrue((decision as LookaheadCertificationDecision.NotCertified).reasons.any { it.contains("样本数不足") })
    }

    @Test
    fun hotRunIsNotCertifiedEvidence() {
        // coolRun=false（热态/被拒绝结果）：hotStart=true -> Reject（证据无效）。
        val decision = decideLookaheadCertification(
            baseline = baseline,
            candidate = result(decodeTps = 14f, coolRun = false),
            case = case,
            nativeBuildId = "build-1",
            mnnCommit = "abc123",
            nowElapsedMs = 0L,
        )
        assertTrue(decision is LookaheadCertificationDecision.NotCertified)
    }

    @Test
    fun missingNativeIdentityNeverCertifies() {
        // native 握手缺席（runtimeInfo==null 对应的空身份）：toCertifiedOptions 返回 null -> NotCertified，
        // 绝不落 device+model+variant 三分量的退化键（证据错配防护，Task 6 I-2）。
        val decision = decideLookaheadCertification(
            baseline = baseline,
            candidate = candidate,
            case = case,
            nativeBuildId = "",
            mnnCommit = "",
            nowElapsedMs = 0L,
        )
        assertTrue(decision is LookaheadCertificationDecision.NotCertified)
        val reasons = (decision as LookaheadCertificationDecision.NotCertified).reasons
        assertTrue(reasons.any { it.contains("native 构建身份缺失") })
    }

    // ===== Task 6 M-3 转交项：键派生一致性 =====

    @Test
    fun certificationKeyConsistencyBetweenSaveAndLookup() {
        // 落盘键（认证记录自身的 certKey）必须等于生产查证键
        // （InferenceCertificationStore.certKey(device, model, variant, native, commit) 五分量）——
        // LocalChatProvider 按同样五分量查证（deviceFingerprintOf / modelConfigFingerprint /
        // CPU_OPTIMIZED / MnnBridge.runtimeInfo 同源），键不一致则认证永不生效。
        val decision = decideLookaheadCertification(
            baseline = baseline,
            candidate = candidate,
            case = case,
            nativeBuildId = "build-1",
            mnnCommit = "abc123",
            nowElapsedMs = 0L,
        )
        val options = (decision as LookaheadCertificationDecision.Certified).options
        assertEquals(
            InferenceCertificationStore.certKey(options),
            InferenceCertificationStore.certKey(
                deviceFingerprint = case.deviceFingerprint,
                modelFingerprint = case.modelFingerprint,
                variant = "CPU_OPTIMIZED",
                nativeBuildId = "build-1",
                mnnCommit = "abc123",
            ),
        )
    }

    @Test
    fun decisionUsesCandidateAndBaselineDecodeMedians() {
        // 基准样本映射：decodeTps 中位数 / 样本数 / 冷态标志正确进入策略输入。
        val sample = benchmarkSampleFrom(result(decodeTps = 17.5f, sampleCount = 4))
        assertEquals(17.5f, sample.decodeTpsMedian, 0.0001f)
        assertEquals(4, sample.sampleCount)
        assertEquals(false, sample.hotStart)
        assertNotNull(sample.ttftMsMedian)
        assertNotNull(sample.peakPssMb)
    }

    @Test
    fun hotResultMapsToHotStartSample() {
        val sample = benchmarkSampleFrom(result(decodeTps = 10f, coolRun = false))
        assertTrue("非冷态结果应映射 hotStart=true（证据无效）", sample.hotStart)
    }
}
