package com.rhodesisland.terminal.llm.backend

import com.rhodesisland.terminal.data.model.AutoBackendModelClass
import com.rhodesisland.terminal.llm.profile.DowngradeReason
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.profile.InferenceProfileResolver
import com.rhodesisland.terminal.llm.profile.OpenClHealthState
import com.rhodesisland.terminal.llm.profile.ResolvedInferencePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 标准构建后端策略测试（Task 11 Step 1）：标准构建无任何可选的/自动的 QNN 尝试。
 */
class StandardBackendPolicyTest {

    private lateinit var resolver: InferenceProfileResolver

    @Before
    fun setUp() {
        val dir = createTempDir()
        resolver = InferenceProfileResolver(dir, dir.absolutePath + "/m/config.json")
    }

    private fun plan(
        preference: BackendPreference,
        mode: InferencePerformanceMode = InferencePerformanceMode.BALANCED,
    ): ResolvedInferencePlan = resolver.resolve(
        mode = mode,
        backendPreference = preference,
        contextTokens = 4096,
        maxOutputTokens = 2048,
        thermalAdmittedThreads = 4,
        lookahead = false,
        temperature = 0.8f,
        topP = 0.9f,
        repeatPenalty = 1.2f,
        openclHealth = OpenClHealthState.UNKNOWN,
        modelClass = AutoBackendModelClass.GPU_ELIGIBLE,
    )

    @Test
    fun autoNeverContainsNpuInAnyMode() {
        for (mode in InferencePerformanceMode.entries) {
            val p = plan(BackendPreference.AUTO, mode)
            assertFalse("AUTO/$mode 不应含 NPU", p.attempts.any { it.backend == BackendType.MNN_NPU })
        }
    }

    @Test
    fun maximumSpeedNeverContainsNpu() {
        val p = plan(BackendPreference.AUTO, InferencePerformanceMode.MAXIMUM_SPEED)
        assertFalse(p.attempts.any { it.backend == BackendType.MNN_NPU })
    }

    @Test
    fun legacyNpuPreferenceResolvesToCpuWithUserVisibleDowngradeReason() {
        val p = plan(BackendPreference.MNN_NPU)

        // 全部尝试均为 CPU（优化 + 兼容），无 NPU。
        assertTrue(p.attempts.none { it.backend == BackendType.MNN_NPU })
        assertTrue(p.attempts.all { it.backend == BackendType.MNN_CPU })
        // 显式降级原因 = 标准构建无 QNN，供 UI 展示用户可见解释。
        assertTrue(p.downgradeReasons.contains(DowngradeReason.QNN_UNAVAILABLE_IN_STANDARD_BUILD))
    }

    @Test
    fun standardBuildQnnReadyIsAlwaysFalse() {
        // MnnSupportDetector.qnnReady 标准构建恒 false（Android Context 依赖，无法 JVM 实例化；
        // 这里断言策略层对 QNN 的排除），真实 qnnReady 由 CI 编译 + 设备验证。
        val p = plan(BackendPreference.MNN_NPU)
        assertEquals(listOf("CPU_OPTIMIZED", "CPU_COMPATIBILITY"), RuntimeVariantName(p))
    }

    private fun RuntimeVariantName(p: ResolvedInferencePlan): List<String> = p.attempts.map { it.variant.name }
}
