package com.rhodesisland.terminal.llm.thinking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [NativeThinkingBudgetCapabilityResolver] 保守门禁测试。
 *
 * 当前实现恒回 [UNVERIFIED]；测试用完整/部分证据验证「弱证据永不判 VERIFIED」的约束，
 * 防止未来扩展时把模板开关、模型名或运行时宽泛能力误当成原生预算证据。
 */
class NativeThinkingBudgetCapabilityResolverTest {

    private val resolver = NativeThinkingBudgetCapabilityResolver()

    @Test
    fun noEvidenceIsUnverified() {
        assertEquals(NativeThinkingBudgetCapability.UNVERIFIED, resolver.resolve(null))
    }

    @Test
    fun completeLookingEvidenceIsStillUnverifiedInPhaseOne() {
        // 即使凑齐五个字段，首期未注册任何适配器，也必须回 UNVERIFIED。
        val evidence = NativeThinkingBudgetEvidence(
            adapterVersion = "MnnThinkingBudgetAdapterV1",
            runtimeCapabilityId = "thinking_budget_tokens_v1",
            modelFingerprint = "qwen3-4b@abc123",
            nativeBuildId = "ndk27-abc",
            certificationId = "cert-1",
        )
        assertEquals(NativeThinkingBudgetCapability.UNVERIFIED, resolver.resolve(evidence))
    }

    @Test
    fun partialEvidenceIsUnverified() {
        val partial = NativeThinkingBudgetEvidence(
            adapterVersion = "MnnThinkingBudgetAdapterV1",
            runtimeCapabilityId = "thinking_budget_tokens_v1",
            modelFingerprint = "qwen3-4b@abc123",
            nativeBuildId = "ndk27-abc",
            certificationId = "", // 缺少真机认证
        )
        assertEquals(NativeThinkingBudgetCapability.UNVERIFIED, resolver.resolve(partial))
    }
}
