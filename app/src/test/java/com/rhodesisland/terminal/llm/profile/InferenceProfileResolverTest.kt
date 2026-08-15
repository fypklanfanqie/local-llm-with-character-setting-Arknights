package com.rhodesisland.terminal.llm.profile

import com.rhodesisland.terminal.data.model.AutoBackendModelClass
import com.rhodesisland.terminal.llm.backend.BackendPreference
import com.rhodesisland.terminal.llm.backend.BackendType
import com.rhodesisland.terminal.llm.benchmark.CertifiedInferenceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** InferenceProfileResolver 尝试链 / 原生 JSON / 指纹 / 认证门禁测试（Task 7 Steps 1–2 + Task 6）。 */
class InferenceProfileResolverTest {

    private lateinit var resolver: InferenceProfileResolver

    @Before
    fun setUp() {
        val dir = createTempDir()
        resolver = InferenceProfileResolver(cacheDir = dir, modelPath = dir.absolutePath + "/m/config.json")
    }

    /** 构造一份认证记录；variant 默认 CPU_OPTIMIZED（认证的基准变体）。 */
    private fun cert(
        variant: String = RuntimeVariant.CPU_OPTIMIZED.name,
        lookahead: Boolean = false,
        step: Int = 1,
    ) = CertifiedInferenceOptions(
        deviceFingerprint = "device-a",
        modelFingerprint = "model-a",
        variant = variant,
        nativeBuildId = "build-1",
        mnnCommit = "abc123",
        lookahead = lookahead,
        decodeStepTokens = step,
    )

    private fun plan(
        preference: BackendPreference,
        mode: InferencePerformanceMode = InferencePerformanceMode.BALANCED,
        threads: Int = 4,
        openclHealth: OpenClHealthState = OpenClHealthState.UNKNOWN,
        lookahead: Boolean = false,
        certifiedOptions: CertifiedInferenceOptions? = null,
        // 默认 GPU_ELIGIBLE：既有 AUTO 用例语义为「GPU 可用的模型在 AUTO 下」，保持原意；
        // 模型大小门禁的专属用例显式传 CPU 分类。
        modelClass: AutoBackendModelClass = AutoBackendModelClass.GPU_ELIGIBLE,
    ): ResolvedInferencePlan = resolver.resolve(
        mode = mode,
        backendPreference = preference,
        contextTokens = 4096,
        maxOutputTokens = 2048,
        thermalAdmittedThreads = threads,
        lookahead = lookahead,
        temperature = 0.8f,
        topP = 0.9f,
        repeatPenalty = 1.2f,
        openclHealth = openclHealth,
        modelClass = modelClass,
        certifiedOptions = certifiedOptions,
    )

    /** CPU_OPTIMIZED attempt 的 native config JSON。 */
    private fun cpuOptimizedJson(p: ResolvedInferencePlan): String =
        p.attempts.first { it.variant == RuntimeVariant.CPU_OPTIMIZED }.nativeConfigJson

    private fun variants(p: ResolvedInferencePlan): List<RuntimeVariant> = p.attempts.map { it.variant }

    @Test
    fun autoWithoutHealthyOpenclFallsBackToCpuOptimizedThenCompatibility() {
        val p = plan(BackendPreference.AUTO, openclHealth = OpenClHealthState.UNKNOWN)

        assertEquals(listOf(RuntimeVariant.CPU_OPTIMIZED, RuntimeVariant.CPU_COMPATIBILITY), variants(p))
        assertTrue(p.attempts.none { it.backend == BackendType.MNN_GPU })
        assertFalse(p.attempts.any { it.backend == BackendType.MNN_NPU })
    }

    @Test
    fun autoWithHealthyOpenclPlacesOpenclFirstThenCpu() {
        val p = plan(BackendPreference.AUTO, openclHealth = OpenClHealthState.MODEL_OK)

        assertEquals(
            listOf(RuntimeVariant.OPENCL, RuntimeVariant.CPU_OPTIMIZED, RuntimeVariant.CPU_COMPATIBILITY),
            variants(p),
        )
        assertEquals(BackendType.MNN_GPU, p.attempts.first().backend)
    }

    @Test
    fun probeOkOpenclIsEligibleAndPlacesOpenclFirst() {
        // PROBE_OK 与 MODEL_OK 同为健康证据（Task 3：探测成功即可入链，无需等首次生成）。
        val p = plan(BackendPreference.AUTO, openclHealth = OpenClHealthState.PROBE_OK)

        assertEquals(
            listOf(RuntimeVariant.OPENCL, RuntimeVariant.CPU_OPTIMIZED, RuntimeVariant.CPU_COMPATIBILITY),
            variants(p),
        )
        assertEquals(BackendType.MNN_GPU, p.attempts.first().backend)
    }

    @Test
    fun explicitGpuWithBlacklistHealthDowngradesToCpu() {
        // CRASH_BLACKLISTED 与 COOLDOWN 同为不健康：显式 GPU 降级并记 OPENCL_UNHEALTHY。
        val p = plan(BackendPreference.MNN_GPU, openclHealth = OpenClHealthState.CRASH_BLACKLISTED)

        assertTrue(p.downgradeReasons.contains(DowngradeReason.OPENCL_UNHEALTHY))
        assertTrue(p.attempts.none { it.backend == BackendType.MNN_GPU })
        assertEquals(RuntimeVariant.CPU_OPTIMIZED, p.attempts.first().variant)
    }

    @Test
    fun qnnNeverAppearsInAutoEvenWithNpuPreference() {
        // 显式选 NPU 的标准版：QNN 不可用，解析为 CPU 并记录 UNSUPPORTED_SETTING。
        val p = plan(BackendPreference.MNN_NPU, openclHealth = OpenClHealthState.UNKNOWN)

        assertTrue(p.attempts.none { it.backend == BackendType.MNN_NPU })
        assertTrue(p.downgradeReasons.contains(DowngradeReason.QNN_UNAVAILABLE_IN_STANDARD_BUILD))
        assertEquals(RuntimeVariant.CPU_OPTIMIZED, p.attempts.first().variant)
    }

    @Test
    fun explicitGpuWithCooldownHealthRecordsDowngradeAndFallsBackToCpu() {
        // UNKNOWN 需先探测（不算 unhealthy）；COOLDOWN/BLACKLISTED 视为不健康 -> 显式 GPU 降级。
        val p = plan(BackendPreference.MNN_GPU, openclHealth = OpenClHealthState.COOLDOWN)

        assertTrue(p.downgradeReasons.contains(DowngradeReason.OPENCL_UNHEALTHY))
        assertTrue(p.attempts.none { it.backend == BackendType.MNN_GPU })
        assertEquals(RuntimeVariant.CPU_OPTIMIZED, p.attempts.first().variant)
    }

    @Test
    fun unknownOpenclHealthRequiresProbeNotImmediateLoad() {
        // UNKNOWN：OpenCL 不入链（需 probe），且不标 unhealthy（健康未知 ≠ 不健康）。
        val p = plan(BackendPreference.AUTO, openclHealth = OpenClHealthState.UNKNOWN)
        assertFalse(p.attempts.any { it.backend == BackendType.MNN_GPU })
        assertFalse(p.downgradeReasons.contains(DowngradeReason.OPENCL_UNHEALTHY))
    }

    @Test
    fun thermalDowngradeCannotBeBypassedByMaximumSpeed() {
        val balanced = plan(BackendPreference.MNN_CPU, InferencePerformanceMode.BALANCED, threads = 2)
        val speed = plan(BackendPreference.MNN_CPU, InferencePerformanceMode.MAXIMUM_SPEED, threads = 2)

        // 热准入后的线程数不受模式影响（MAXIMUM_SPEED 不绕过温控）。
        assertEquals(2, balanced.powerPolicy.cpuThreads)
        assertEquals(2, speed.powerPolicy.cpuThreads)
    }

    @Test
    fun openclAttemptUsesThreadNum68Encoding() {
        val p = plan(BackendPreference.AUTO, openclHealth = OpenClHealthState.MODEL_OK)

        val openclJson = p.attempts.first { it.variant == RuntimeVariant.OPENCL }.nativeConfigJson
        assertTrue(openclJson.contains("\"thread_num\":68"))
        assertTrue(openclJson.contains("\"backend_type\":\"opencl\""))
    }

    @Test
    fun compatibilityVariantUsesConservativePrecisionMemoryAndPower() {
        val p = plan(BackendPreference.MNN_CPU)

        val compat = p.attempts.first { it.variant == RuntimeVariant.CPU_COMPATIBILITY }.nativeConfigJson
        assertTrue(compat.contains("\"precision\":\"normal\""))
        assertTrue(compat.contains("\"memory\":\"normal\""))
        assertTrue(compat.contains("\"power\":\"normal\""))

        val optimized = p.attempts.first { it.variant == RuntimeVariant.CPU_OPTIMIZED }.nativeConfigJson
        assertTrue(optimized.contains("\"precision\":\"low\""))
        assertTrue(optimized.contains("\"power\":\"high\""))
    }

    @Test
    fun loadConfigHashIsStableAndSensitiveToChanges() {
        val a = plan(BackendPreference.MNN_CPU, threads = 4)
        val b = plan(BackendPreference.MNN_CPU, threads = 4)
        val c = plan(BackendPreference.MNN_CPU, threads = 6)

        assertEquals(a.attempts.first().loadConfigHash, b.attempts.first().loadConfigHash)
        assertNotEquals(a.attempts.first().loadConfigHash, c.attempts.first().loadConfigHash)
    }

    @Test
    fun canonicalJsonSortsKeysRecursively() {
        val canonical = InferenceProfileResolver.canonicalJsonString(
            kotlinx.serialization.json.buildJsonObject {
                put("z", 1)
                put("a", kotlinx.serialization.json.buildJsonObject { put("y", 2); put("b", 3) })
            },
        )
        // 根键 a 在 z 前；嵌套对象 b 在 y 前。
        val aIndex = canonical.indexOf("\"a\"")
        val zIndex = canonical.indexOf("\"z\"")
        assertTrue(aIndex in 0 until zIndex)
        assertTrue(canonical.indexOf("\"b\"") < canonical.indexOf("\"y\""))
    }

    @Test
    fun streamAndResidencyPoliciesFollowMode() {
        val balanced = plan(BackendPreference.MNN_CPU, InferencePerformanceMode.BALANCED)
        val speed = plan(BackendPreference.MNN_CPU, InferencePerformanceMode.MAXIMUM_SPEED)

        assertEquals(256, balanced.streamPolicy.batchMaxBytes)
        assertEquals(16, balanced.streamPolicy.batchMaxMs)
        assertEquals(512, speed.streamPolicy.batchMaxBytes)
        assertTrue(speed.powerPolicy.sustainedMode)
        assertTrue(speed.powerPolicy.aggressiveHint)
        assertTrue(speed.residencyPolicy.keepAliveMs > balanced.residencyPolicy.keepAliveMs)
    }

    // ===== Task 6：认证门禁（lookahead / decodeStepTokens）=====

    @Test
    fun lookaheadRequestedWithoutCertificationIsDowngradedToDisabled() {
        // 用户请求 lookahead 但无认证：回落 false，记 LOOKAHEAD_UNCERTIFIED，config 无 speculative_type。
        val p = plan(BackendPreference.MNN_CPU, lookahead = true)

        assertTrue(p.downgradeReasons.contains(DowngradeReason.LOOKAHEAD_UNCERTIFIED))
        assertFalse("未认证不应启用 lookahead", p.powerPolicy.lookahead)
        assertFalse("native config 不应含 speculative_type", cpuOptimizedJson(p).contains("speculative_type"))
    }

    @Test
    fun lookaheadCertifiedForMatchingVariantIsEnabled() {
        // 认证匹配（CPU_OPTIMIZED + lookahead 证据）+ 用户请求 -> 启用，且 powerPolicy 同步。
        val p = plan(BackendPreference.MNN_CPU, lookahead = true, certifiedOptions = cert(lookahead = true))

        assertFalse(p.downgradeReasons.contains(DowngradeReason.LOOKAHEAD_UNCERTIFIED))
        assertTrue("认证后应启用 lookahead", p.powerPolicy.lookahead)
        assertTrue("native config 应含 speculative_type", cpuOptimizedJson(p).contains("speculative_type"))
    }

    @Test
    fun lookaheadCertifiedButUserDidNotRequestStaysDisabled() {
        // 有认证但用户未请求：lookahead 是用户许可 + 认证证据双条件，不自动开启。
        val p = plan(BackendPreference.MNN_CPU, lookahead = false, certifiedOptions = cert(lookahead = true))

        assertFalse(p.powerPolicy.lookahead)
        assertFalse(cpuOptimizedJson(p).contains("speculative_type"))
        // 用户未请求，不算降级（无 LOOKAHEAD_UNCERTIFIED）。
        assertFalse(p.downgradeReasons.contains(DowngradeReason.LOOKAHEAD_UNCERTIFIED))
    }

    @Test
    fun lookaheadCertWithoutLookaheadEvidenceStaysDisabled() {
        // 认证存在但无 lookahead 证据（如纯步进认证）：用户请求也不启用，记降级原因。
        val p = plan(BackendPreference.MNN_CPU, lookahead = true, certifiedOptions = cert(lookahead = false))

        assertTrue(p.downgradeReasons.contains(DowngradeReason.LOOKAHEAD_UNCERTIFIED))
        assertFalse(p.powerPolicy.lookahead)
        assertFalse(cpuOptimizedJson(p).contains("speculative_type"))
    }

    @Test
    fun lookaheadCertForMismatchedVariantIsNotApplied() {
        // 认证变体不匹配（OPENCL 组合的认证不是 CPU 组合的证据）-> 不启用 + 记原因。
        val gpuCert = cert(variant = RuntimeVariant.OPENCL.name, lookahead = true)
        val p = plan(BackendPreference.MNN_CPU, lookahead = true, certifiedOptions = gpuCert)

        assertTrue(p.downgradeReasons.contains(DowngradeReason.LOOKAHEAD_UNCERTIFIED))
        assertFalse(p.powerPolicy.lookahead)
        assertFalse(cpuOptimizedJson(p).contains("speculative_type"))
    }

    @Test
    fun decodeStepTokensDefaultsToOneWithoutCertification() {
        // 无认证：多 token 步进保持关闭（native 逐 token），plan 恒 1。
        val p = plan(BackendPreference.MNN_CPU)

        assertEquals("未认证步进应默认 1", 1, p.decodeStepTokens)
        val certified = plan(BackendPreference.MNN_CPU, certifiedOptions = cert(step = 1))
        assertEquals("认证了步长 1（无步进证据）仍为 1", 1, certified.decodeStepTokens)
    }

    @Test
    fun decodeStepTokensTakesCertifiedValueWhenMatching() {
        // 认证了步进收益（step=2）且变体匹配 -> plan 取认证值。
        val p = plan(BackendPreference.MNN_CPU, certifiedOptions = cert(step = 2))

        assertEquals("plan 应取认证步长", 2, p.decodeStepTokens)
    }

    @Test
    fun stepCertificationReachableWithoutLookaheadRequestAndNoNoise() {
        // final review I1：认证记录含 step=2、无 lookahead 证据（lookahead=false）时，lookahead
        // 未请求（旧开关关闭）仍应启用步进——provider 侧短路删除后，步进认证在开关关闭时可达；
        // 且不产生 LOOKAHEAD_UNCERTIFIED 噪音（该原因仅在 lookahead && 未认证时记录）。
        val p = plan(
            BackendPreference.MNN_CPU,
            lookahead = false,
            certifiedOptions = cert(lookahead = false, step = 2),
        )

        assertEquals("开关关闭时步进认证仍应生效", 2, p.decodeStepTokens)
        assertFalse("未请求 lookahead 不应有未认证噪音", p.downgradeReasons.contains(DowngradeReason.LOOKAHEAD_UNCERTIFIED))
    }

    @Test
    fun decodeStepTokensIgnoredForMismatchedVariant() {
        // 步进认证但变体不匹配（GPU 组合）-> 恒 1。
        val p = plan(
            BackendPreference.MNN_CPU,
            certifiedOptions = cert(variant = RuntimeVariant.OPENCL.name, step = 4),
        )

        assertEquals("变体不匹配时步进应回落 1", 1, p.decodeStepTokens)
    }

    @Test
    fun decodeStepTokensClampedToFourWhenCertificationRecordCorrupted() {
        // Task 6 review M-2：损坏记录（step=99）经 coerceIn(1,4) 收敛，不直传 native
        // （native 已有 clamp [1,4]，此为 Kotlin 侧纵深防御）。
        val p = plan(
            BackendPreference.MNN_CPU,
            certifiedOptions = cert(step = 99),
        )

        assertEquals("越界步长应收敛到 4", 4, p.decodeStepTokens)
    }

    @Test
    fun lookaheadAndStepGatesAreIndependent() {
        // 认证既有 lookahead 证据又有步进证据：用户请求 lookahead -> 两者都生效。
        val p = plan(
            BackendPreference.MNN_CPU,
            lookahead = true,
            certifiedOptions = cert(lookahead = true, step = 2),
        )

        assertTrue(p.powerPolicy.lookahead)
        assertTrue(cpuOptimizedJson(p).contains("speculative_type"))
        assertEquals(2, p.decodeStepTokens)
        assertFalse(p.downgradeReasons.contains(DowngradeReason.LOOKAHEAD_UNCERTIFIED))
    }

    @Test
    fun openclAttemptNeverEnablesLookaheadEvenWhenCertified() {
        // OpenCL attempt 恒 lookahead=false（lookahead 仅 CPU）；认证不影响 GPU 变体。
        val p = plan(
            BackendPreference.AUTO,
            lookahead = true,
            openclHealth = OpenClHealthState.MODEL_OK,
            certifiedOptions = cert(lookahead = true),
        )

        val openclJson = p.attempts.first { it.variant == RuntimeVariant.OPENCL }.nativeConfigJson
        assertFalse(openclJson.contains("speculative_type"))
    }

    // ===== Task 7 M-4：基准候选旁路的合成认证路径 =====

    /**
     * 构造 runner 候选旁路的合成认证记录（[CandidateOverrides] -> 合成 cert 的形态）：
     * device/model 指纹留空（resolver 只匹配 variant，不读指纹）、variant 恒 CPU_OPTIMIZED、
     * native 身份来自运行时握手。
     */
    private fun syntheticCandidateCert(
        lookahead: Boolean,
        step: Int = 1,
    ) = CertifiedInferenceOptions(
        deviceFingerprint = "",
        modelFingerprint = "",
        variant = RuntimeVariant.CPU_OPTIMIZED.name,
        nativeBuildId = "runtime-build",
        mnnCommit = "runtime-commit",
        lookahead = lookahead,
        decodeStepTokens = step,
    )

    @Test
    fun syntheticCandidateCertWithEmptyFingerprintsEnablesLookahead() {
        // runner 候选旁路（M-4）依赖的契约：合成认证（空 device/model 指纹）在「用户请求」同值
        // 传入时放行候选配置——否则基准永远测不到 lookahead 候选（认证流无法闭环）。
        val p = plan(
            BackendPreference.MNN_CPU,
            lookahead = true,
            certifiedOptions = syntheticCandidateCert(lookahead = true),
        )

        assertFalse(p.downgradeReasons.contains(DowngradeReason.LOOKAHEAD_UNCERTIFIED))
        assertTrue("合成认证应放行 lookahead 候选", p.powerPolicy.lookahead)
        assertTrue("native config 应含 speculative_type", cpuOptimizedJson(p).contains("speculative_type"))
    }

    @Test
    fun syntheticCandidateCertEnablesDecodeStepCandidate() {
        // 候选旁路同时支持步进候选（decodeStepTokens=2）：合成认证后 plan 取候选步长。
        val p = plan(
            BackendPreference.MNN_CPU,
            lookahead = false,
            certifiedOptions = syntheticCandidateCert(lookahead = false, step = 2),
        )

        assertEquals("合成认证应放行步进候选", 2, p.decodeStepTokens)
        assertFalse("未请求 lookahead 时仍不启用", p.powerPolicy.lookahead)
    }

    @Test
    fun syntheticCandidateCertLookaheadFalseKeepsBaselineOff() {
        // 基线旁路（lookahead=false）：runner 以候选值覆盖「用户请求」输入（buildPlan 的
        // lookahead = candidateOverrides.lookahead，而非设置快照 legacy 值）——legacy 开关开
        // 也不会被放大到基线，基线恒关闭，且不产生 LOOKAHEAD_UNCERTIFIED 噪音
        // （旁路语义下无「用户请求未认证」：请求输入本身即候选值）。
        val p = plan(
            BackendPreference.MNN_CPU,
            lookahead = false, // runner 旁路传入的覆盖值
            certifiedOptions = syntheticCandidateCert(lookahead = false),
        )

        assertFalse("基线旁路应保持 lookahead=false", p.powerPolicy.lookahead)
        assertFalse(cpuOptimizedJson(p).contains("speculative_type"))
        assertFalse("覆盖值语义下不应有未认证降级噪音", p.downgradeReasons.contains(DowngradeReason.LOOKAHEAD_UNCERTIFIED))
    }

    // ===== Task 15：模型大小门禁（AUTO 仅 >7B 用 GPU）=====

    @Test
    fun autoSmallModelSkipsGpuEvenWhenOpenclHealthy() {
        val p = plan(
            BackendPreference.AUTO,
            openclHealth = OpenClHealthState.MODEL_OK,
            modelClass = AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD,
        )

        assertTrue(p.attempts.none { it.backend == BackendType.MNN_GPU })
        assertEquals(
            listOf(RuntimeVariant.CPU_OPTIMIZED, RuntimeVariant.CPU_COMPATIBILITY),
            variants(p),
        )
        assertTrue(p.downgradeReasons.contains(DowngradeReason.AUTO_MODEL_AT_OR_BELOW_7B_CPU))
    }

    @Test
    fun autoUnknownModelSkipsGpuAndRecordsUnknownReason() {
        val p = plan(
            BackendPreference.AUTO,
            openclHealth = OpenClHealthState.MODEL_OK,
            modelClass = AutoBackendModelClass.CPU_UNKNOWN_PARAMETERS,
        )

        assertTrue(p.attempts.none { it.backend == BackendType.MNN_GPU })
        assertTrue(p.downgradeReasons.contains(DowngradeReason.AUTO_MODEL_PARAMETERS_UNKNOWN_CPU))
        assertEquals(RuntimeVariant.CPU_OPTIMIZED, p.attempts.first().variant)
    }

    @Test
    fun autoGpuEligibleModelKeepsOpenclFirstWhenHealthy() {
        val p = plan(
            BackendPreference.AUTO,
            openclHealth = OpenClHealthState.MODEL_OK,
            modelClass = AutoBackendModelClass.GPU_ELIGIBLE,
        )

        assertEquals(BackendType.MNN_GPU, p.attempts.first().backend)
        assertFalse(p.downgradeReasons.contains(DowngradeReason.AUTO_MODEL_AT_OR_BELOW_7B_CPU))
        assertFalse(p.downgradeReasons.contains(DowngradeReason.AUTO_MODEL_PARAMETERS_UNKNOWN_CPU))
    }

    @Test
    fun explicitGpuHonoredEvenForSmallModel() {
        // 显式 MNN_GPU：模型大小门槛不生效（用户显式选择优先）。
        val p = plan(
            BackendPreference.MNN_GPU,
            openclHealth = OpenClHealthState.MODEL_OK,
            modelClass = AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD,
        )

        assertEquals(BackendType.MNN_GPU, p.attempts.first().backend)
        assertFalse(p.downgradeReasons.contains(DowngradeReason.AUTO_MODEL_AT_OR_BELOW_7B_CPU))
    }
}
