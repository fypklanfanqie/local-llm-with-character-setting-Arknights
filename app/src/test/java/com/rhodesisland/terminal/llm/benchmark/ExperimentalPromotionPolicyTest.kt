package com.rhodesisland.terminal.llm.benchmark

import com.rhodesisland.terminal.llm.profile.RuntimeVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.chatbyyourside.llm.backend.MnnBridge

/** 实验促进门禁测试（Task 15 Step 1）+ 认证记录映射（Task 6）。 */
class ExperimentalPromotionPolicyTest {

    private val baseline = BenchmarkSample(decodeTpsMedian = 10f, ttftMsMedian = 800f, peakPssMb = 1000f, sampleCount = 3)
    private fun candidate(overrides: BenchmarkSample.() -> BenchmarkSample) =
        BenchmarkSample(decodeTpsMedian = 11.5f, ttftMsMedian = 850f, peakPssMb = 1050f, sampleCount = 3).overrides()

    @Test
    fun qualifiedCandidatePromotes() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { this })
        assertEquals(PromotionDecision.Promote, d)
    }

    @Test
    fun correctnessFailureRejects() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { copy(correctnessOk = false) })
        assertTrue(d is PromotionDecision.Reject)
        assertTrue((d as PromotionDecision.Reject).reasons.any { it.contains("正确性") })
    }

    @Test
    fun insufficientDecodeImprovementRejects() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { copy(decodeTpsMedian = 10.9f) })
        assertTrue(d is PromotionDecision.Reject)
    }

    @Test
    fun singleSampleRejects() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { copy(sampleCount = 1) })
        assertTrue(d is PromotionDecision.Reject)
        assertTrue((d as PromotionDecision.Reject).reasons.any { it.contains("样本数") })
    }

    @Test
    fun hotStartRejects() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { copy(hotStart = true) })
        assertTrue(d is PromotionDecision.Reject)
    }

    @Test
    fun ttftRegressionRejects() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { copy(ttftMsMedian = 1100f) })
        assertTrue(d is PromotionDecision.Reject)
    }

    @Test
    fun pssRegressionRejects() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { copy(peakPssMb = 1500f) })
        assertTrue(d is PromotionDecision.Reject)
    }

    // ===== Task 6：PromotionDecision -> 认证记录映射（InferenceCertificationStore.toCertifiedOptions）=====

    private fun case(quadrant: InferenceBackendQuadrant = InferenceBackendQuadrant.CPU_THINKING_OFF) =
        InferenceBenchmarkCase(
            scenario = InferenceBenchmarkScenario.FIXED_DECODE,
            quadrant = quadrant,
            modelFingerprint = "model-a",
            deviceFingerprint = "device-a",
            configHash = "case-config-hash",
        )

    @Test
    fun promotedCandidateProducesCertificationRecord() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { this })
        val record = InferenceCertificationStore.toCertifiedOptions(
            case = case(),
            decision = d,
            nativeBuildId = "build-1",
            mnnCommit = "abc123",
            decodeStepTokens = 1,
            // lookahead 基准（开 vs 关对比）-> 产生 lookahead 证据。
            lookaheadEvidence = true,
            configHash = "candidate-cfg",
            nowElapsedMs = 42_000L,
        )

        assertNotNull("Promote 应产出认证记录", record)
        record!!.let {
            // 身份分量来自用例 + 调用方传入的 native 构建身份（Task 6 review I-2：不再读 MnnBridge）。
            assertEquals("device-a", it.deviceFingerprint)
            assertEquals("model-a", it.modelFingerprint)
            assertEquals("CPU 象限应映射到 CPU_OPTIMIZED 变体", RuntimeVariant.CPU_OPTIMIZED.name, it.variant)
            assertEquals("build-1", it.nativeBuildId)
            assertEquals("abc123", it.mnnCommit)
            assertTrue("lookahead 基准应记录 lookahead 证据", it.lookahead)
            assertEquals("未测步进时记录 1", 1, it.decodeStepTokens)
            assertEquals("candidate-cfg", it.certifiedConfigHash)
            assertEquals(42_000L, it.certifiedAtElapsedMs)
        }
    }

    @Test
    fun gpuQuadrantMapsToOpenclVariant() {
        val record = InferenceCertificationStore.toCertifiedOptions(
            case = case(InferenceBackendQuadrant.GPU_THINKING_ON),
            decision = PromotionDecision.Promote,
            nativeBuildId = "build-1",
            mnnCommit = "abc123",
            decodeStepTokens = 1,
            lookaheadEvidence = true,
            configHash = null,
            nowElapsedMs = 1L,
        )

        assertNotNull(record)
        assertEquals(RuntimeVariant.OPENCL.name, record!!.variant)
    }

    @Test
    fun certifiedSteppingRecordsStepValue() {
        val record = InferenceCertificationStore.toCertifiedOptions(
            case = case(),
            decision = PromotionDecision.Promote,
            nativeBuildId = "build-1",
            mnnCommit = "abc123",
            decodeStepTokens = 2,
            // 纯步进基准（step=2 vs 1）不产生 lookahead 证据（Task 6 review I-1）。
            lookaheadEvidence = false,
            configHash = null,
            nowElapsedMs = 1L,
        )

        assertNotNull(record)
        assertEquals("认证步长应原样记录", 2, record!!.decodeStepTokens)
        assertFalse("纯步进认证不得记录 lookahead 证据", record.lookahead)
    }

    @Test
    fun rejectedCandidateProducesNoRecord() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { copy(sampleCount = 1) })
        assertTrue(d is PromotionDecision.Reject)

        assertNull(
            "Reject 不应产出认证记录",
            InferenceCertificationStore.toCertifiedOptions(
                case(), d,
                nativeBuildId = "build-1",
                mnnCommit = "abc123",
                decodeStepTokens = 1,
                lookaheadEvidence = true,
                configHash = null,
                nowElapsedMs = 1L,
            ),
        )
    }

    @Test
    fun promotedCandidateWithoutNativeIdentityProducesNoRecord() {
        // Task 6 review I-2：握手缺席（nativeBuildId/mnnCommit 空/空白）时不得生成认证记录——
        // 否则认证键退化为 device+model+variant 三分量，native 重建后旧二进制证据继续启用
        // 步进/lookahead（证据错配）。无 native 身份证明不认证。
        assertNull(
            "nativeBuildId 为空不应认证",
            InferenceCertificationStore.toCertifiedOptions(
                case(), PromotionDecision.Promote,
                nativeBuildId = "",
                mnnCommit = "abc123",
                decodeStepTokens = 1,
                lookaheadEvidence = true,
                configHash = null,
                nowElapsedMs = 1L,
            ),
        )
        assertNull(
            "mnnCommit 空白不应认证",
            InferenceCertificationStore.toCertifiedOptions(
                case(), PromotionDecision.Promote,
                nativeBuildId = "build-1",
                mnnCommit = "  ",
                decodeStepTokens = 1,
                lookaheadEvidence = true,
                configHash = null,
                nowElapsedMs = 1L,
            ),
        )
    }

    // ===== Task 4：Runtime/GPU 晋级门禁（evaluateRuntime）=====

    private fun runtimeCandidate(
        sample: BenchmarkSample = BenchmarkSample(
            decodeTpsMedian = 14f, ttftMsMedian = 800f, peakPssMb = 1000f, sampleCount = 5,
        ),
        commit: String = "candidate-commit",
        buildId: String = "candidate-build",
        backendCounts: Map<String, Int> = emptyMap(),
        kvReuseRate: Float? = 1f,
        emptyResponseRate: Float = 0f,
        isGpuCandidate: Boolean = false,
    ) = RuntimeBenchmarkCandidate(
        sample = sample,
        mnnCommit = commit,
        nativeBuildId = buildId,
        actualBackendCounts = backendCounts,
        kvReuseRate = kvReuseRate,
        emptyResponseRate = emptyResponseRate,
        isGpuCandidate = isGpuCandidate,
    )

    @Test
    fun runtimeCandidateRequiresDifferentNonBlankIdentity() {
        // 候选身份空白 -> 拒绝。
        val d1 = ExperimentalPromotionPolicy.evaluateRuntime(
            baseline, "base-commit", "base-build",
            candidate = runtimeCandidate(commit = "", buildId = "candidate-build"),
        )
        assertTrue(d1 is PromotionDecision.Reject)
        assertTrue((d1 as PromotionDecision.Reject).reasons.any { it.contains("身份") })

        // 候选身份与基线相同 -> 拒绝（无升级价值）。
        val d2 = ExperimentalPromotionPolicy.evaluateRuntime(
            baseline, "base-commit", "base-build",
            candidate = runtimeCandidate(commit = "base-commit", buildId = "base-build"),
        )
        assertTrue(d2 is PromotionDecision.Reject)
        assertTrue((d2 as PromotionDecision.Reject).reasons.any { it.contains("相同") })

        // 候选身份不同且非空白 -> 身份门禁通过（样本不足/性能不足等其它原因可另拒绝，
        // 但不得再以「身份」作为拒绝理由）。
        val d3 = ExperimentalPromotionPolicy.evaluateRuntime(
            baseline, "base-commit", "base-build",
            candidate = runtimeCandidate(sample = baseline, kvReuseRate = null),
        )
        val r3 = (d3 as? PromotionDecision.Reject)?.reasons ?: emptyList()
        assertTrue("身份不应再是拒绝原因：$r3", r3.none { it.contains("身份") })
    }

    @Test
    fun gpuCandidateRequiresActualGpuSamples() {
        // 候选声称 GPU 但混入 CPU fallback -> 证据无效，拒绝。
        val mixed = runtimeCandidate(
            backendCounts = mapOf("MNN_GPU" to 3, "CPU" to 2),
        )
        val d = ExperimentalPromotionPolicy.evaluateRuntime(
            baseline, "base-commit", "base-build", baselineKvReuseRate = 1f,
            candidate = mixed,
        )
        assertTrue(d is PromotionDecision.Reject)
        assertTrue((d as PromotionDecision.Reject).reasons.any { it.contains("GPU") })

        // 全部样本真实跑在 MNN_GPU -> GPU 证据成立；其余门禁通过则 Promote。
        val pure = mixed.copy(actualBackendCounts = mapOf("MNN_GPU" to 5))
        val dp = ExperimentalPromotionPolicy.evaluateRuntime(
            baseline, "base-commit", "base-build", baselineKvReuseRate = 1f,
            candidate = pure,
        )
        assertEquals(PromotionDecision.Promote, dp)
    }

    @Test
    fun flaggedGpuCandidateRequiresEverySampleOnMnnGpu() {
        // isGpuCandidate=true 且全部 decoded 样本实际跑在 MNN_GPU -> 证据成立，Promote。
        val pure = runtimeCandidate(
            isGpuCandidate = true,
            backendCounts = mapOf("MNN_GPU" to 5),
        )
        val dp = ExperimentalPromotionPolicy.evaluateRuntime(
            baseline, "base-commit", "base-build", baselineKvReuseRate = 1f,
            candidate = pure,
        )
        assertEquals(PromotionDecision.Promote, dp)

        // isGpuCandidate=true 但 0 个 GPU 样本（全 CPU fallback）-> 不可作为 GPU 收益证据，拒绝。
        val allFallback = runtimeCandidate(
            isGpuCandidate = true,
            backendCounts = mapOf("MNN_CPU" to 5),
        )
        val d0 = ExperimentalPromotionPolicy.evaluateRuntime(
            baseline, "base-commit", "base-build", baselineKvReuseRate = 1f,
            candidate = allFallback,
        )
        assertTrue(d0 is PromotionDecision.Reject)
        assertTrue(
            (d0 as PromotionDecision.Reject).reasons.any { it.contains("GPU 样本数不足") },
        )

        // isGpuCandidate=true 但混合（3 GPU / 2 CPU）-> 拒绝（同样给出全回退证据不足原因）。
        val mixed = runtimeCandidate(
            isGpuCandidate = true,
            backendCounts = mapOf("MNN_GPU" to 3, "MNN_CPU" to 2),
        )
        val dm = ExperimentalPromotionPolicy.evaluateRuntime(
            baseline, "base-commit", "base-build", baselineKvReuseRate = 1f,
            candidate = mixed,
        )
        assertTrue(dm is PromotionDecision.Reject)
        assertTrue(
            (dm as PromotionDecision.Reject).reasons.any { it.contains("GPU 样本数不足") },
        )
    }

    @Test
    fun cpuCandidateIsNotGatedByGpuEvidence() {
        // isGpuCandidate=false（默认）：CPU decode 数据即使提升显著也不触发 GPU 证据门禁，
        // 既有 CPU 候选调用行为不变。
        val cpu = runtimeCandidate(
            isGpuCandidate = false,
            backendCounts = mapOf("MNN_CPU" to 5),
        )
        val d = ExperimentalPromotionPolicy.evaluateRuntime(
            baseline, "base-commit", "base-build", baselineKvReuseRate = 1f,
            candidate = cpu,
        )
        assertEquals(PromotionDecision.Promote, d)
    }

    @Test
    fun candidateFailsWhenCorrectnessOrKvReuseRegresses() {
        // 正确性回归 -> 拒绝。
        val badCorrectness = runtimeCandidate(
            sample = BenchmarkSample(decodeTpsMedian = 14f, ttftMsMedian = 800f, peakPssMb = 1000f, sampleCount = 5, correctnessOk = false),
        )
        val d1 = ExperimentalPromotionPolicy.evaluateRuntime(
            baseline, "base-commit", "base-build", baselineKvReuseRate = 1f,
            candidate = badCorrectness,
        )
        assertTrue(d1 is PromotionDecision.Reject)
        assertTrue((d1 as PromotionDecision.Reject).reasons.any { it.contains("正确性") })

        // KV 复用率回归（0.5 < 基线 1.0）-> 拒绝。
        val kvRegress = runtimeCandidate(kvReuseRate = 0.5f)
        val d2 = ExperimentalPromotionPolicy.evaluateRuntime(
            baseline, "base-commit", "base-build", baselineKvReuseRate = 1f,
            candidate = kvRegress,
        )
        assertTrue(d2 is PromotionDecision.Reject)
        assertTrue((d2 as PromotionDecision.Reject).reasons.any { it.contains("KV") })
    }

    @Test
    fun candidateFailsWhenDecodeGainIsBelowTenPercent() {
        // 10.9/10 = 1.09 < 1.10 -> 拒绝。
        val lowGain = runtimeCandidate(
            sample = BenchmarkSample(decodeTpsMedian = 10.9f, ttftMsMedian = 800f, peakPssMb = 1000f, sampleCount = 3),
        )
        val d = ExperimentalPromotionPolicy.evaluateRuntime(
            baseline, "base-commit", "base-build",
            candidate = lowGain,
        )
        assertTrue(d is PromotionDecision.Reject)
        assertTrue((d as PromotionDecision.Reject).reasons.any { it.contains("decode") })
    }

    @Test
    fun candidateFailsWhenTtftOrPssRegressesOverThirtyPercent() {
        // TTFT 1100 > 800*1.3 -> 拒绝。
        val ttftRegress = runtimeCandidate(
            sample = BenchmarkSample(decodeTpsMedian = 14f, ttftMsMedian = 1100f, peakPssMb = 1000f, sampleCount = 3),
        )
        val d1 = ExperimentalPromotionPolicy.evaluateRuntime(
            baseline, "base-commit", "base-build",
            candidate = ttftRegress,
        )
        assertTrue(d1 is PromotionDecision.Reject)
        assertTrue((d1 as PromotionDecision.Reject).reasons.any { it.contains("TTFT") })

        // PSS 1500 > 1000*1.3 -> 拒绝。
        val pssRegress = runtimeCandidate(
            sample = BenchmarkSample(decodeTpsMedian = 14f, ttftMsMedian = 800f, peakPssMb = 1500f, sampleCount = 3),
        )
        val d2 = ExperimentalPromotionPolicy.evaluateRuntime(
            baseline, "base-commit", "base-build",
            candidate = pssRegress,
        )
        assertTrue(d2 is PromotionDecision.Reject)
        assertTrue((d2 as PromotionDecision.Reject).reasons.any { it.contains("PSS") })
    }

    // ===== Task 15：prefill/TTFT 目标晋级（evaluatePrefill）=====

    private val prefillBaseline = BenchmarkSample(
        decodeTpsMedian = 10f, ttftMsMedian = 800f, prefillTpsMedian = 100f, peakPssMb = 1000f, sampleCount = 5,
    )

    private fun prefillCandidate(
        prefillTps: Float = 120f,
        ttft: Float = 650f,
        decode: Float = 10f,
        sampleCount: Int = 5,
    ) = BenchmarkSample(
        decodeTpsMedian = decode,
        ttftMsMedian = ttft,
        prefillTpsMedian = prefillTps,
        peakPssMb = 1000f,
        sampleCount = sampleCount,
    )

    @Test
    fun prefillImprovementPromotes() {
        // prefill 吞吐 100 -> 120（≥10%）：达标。
        val d = ExperimentalPromotionPolicy.evaluatePrefill(prefillBaseline, prefillCandidate(prefillTps = 120f))
        assertEquals(PromotionDecision.Promote, d)
    }

    @Test
    fun ttftImprovementAlonePromotes() {
        // prefill 吞吐未达标但 TTFT 800 -> 600（改善 25%）：TTFT 达标即可。
        val d = ExperimentalPromotionPolicy.evaluatePrefill(
            prefillBaseline,
            prefillCandidate(prefillTps = 105f, ttft = 600f),
        )
        assertEquals(PromotionDecision.Promote, d)
    }

    @Test
    fun missingPrefillEvidenceRejects() {
        // 候选/基线缺 prefill 证据（null）：拒绝而非静默晋级。
        val d = ExperimentalPromotionPolicy.evaluatePrefill(
            prefillBaseline,
            prefillCandidate().copy(prefillTpsMedian = null),
        )
        assertTrue(d is PromotionDecision.Reject)
        assertTrue((d as PromotionDecision.Reject).reasons.any { it.contains("证据缺失") })
    }

    @Test
    fun prefillRegressionRejects() {
        val d = ExperimentalPromotionPolicy.evaluatePrefill(
            prefillBaseline,
            prefillCandidate(prefillTps = 90f, ttft = 750f),
        )
        assertTrue(d is PromotionDecision.Reject)
        assertTrue((d as PromotionDecision.Reject).reasons.any { it.contains("prefill") })
    }

    @Test
    fun decodeRegressionBeyondToleranceRejects() {
        // 不牺牲解码换首字：decode 10 -> 6（劣化 40% > 30% 容差）拒绝。
        val d = ExperimentalPromotionPolicy.evaluatePrefill(
            prefillBaseline,
            prefillCandidate(prefillTps = 130f, ttft = 500f, decode = 6f),
        )
        assertTrue(d is PromotionDecision.Reject)
        assertTrue((d as PromotionDecision.Reject).reasons.any { it.contains("decode") })
    }

    @Test
    fun prefillTtftRegressionRejects() {
        // TTFT 800 -> 1100（> 1.3x）拒绝。
        val d = ExperimentalPromotionPolicy.evaluatePrefill(
            prefillBaseline,
            prefillCandidate(prefillTps = 130f, ttft = 1100f),
        )
        assertTrue(d is PromotionDecision.Reject)
        assertTrue((d as PromotionDecision.Reject).reasons.any { it.contains("TTFT") })
    }
}
