package com.rhodesisland.terminal.llm.backend

import com.rhodesisland.terminal.llm.metrics.CompletionReason
import com.rhodesisland.terminal.llm.template.EmptyResponseClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GenerationOutcomeClassifier 判定矩阵测试（Task 4，纯 Kotlin JVM）。
 *
 * 覆盖维度：EmptyResponseClass × thinkingRequested × 零/非零输出 × completionReason ×
 * 策略（DISABLED / CPU_BEFORE_FIRST_DELTA）× 后端（GPU / CPU）。
 * 边界：THINK_ONLY±thinking、NONE 不回退、null 分类不回退、终止集合绝不回退、null 完成原因放行。
 */
class GenerationOutcomeClassifierTest {

    // ===== 基础夹具 =====

    private val enabled = GenerationOutputPolicy(EmptyOutputFallbackPolicy.CPU_BEFORE_FIRST_DELTA)
    private val disabled = GenerationOutputPolicy(EmptyOutputFallbackPolicy.DISABLED)

    /** 默认参数为「最可回退」组合（GPU + 开启策略 + EOS + 零输出 + EOS_EMPTY + 思考关闭）。 */
    private fun eligible(
        policy: GenerationOutputPolicy = enabled,
        backend: BackendType = BackendType.MNN_GPU,
        completionReason: CompletionReason? = CompletionReason.EOS,
        emptyResponseClass: EmptyResponseClass? = EmptyResponseClass.EOS_EMPTY,
        generatedTokens: Int = 0,
        callbackBytes: Long = 0L,
        thinkingRequested: Boolean = false,
    ): Boolean = GenerationOutcomeClassifier.isEligibleForCpuFallback(
        policy = policy,
        backend = backend,
        completionReason = completionReason,
        emptyResponseClass = emptyResponseClass,
        generatedTokens = generatedTokens,
        callbackBytes = callbackBytes,
        thinkingRequested = thinkingRequested,
    )

    // ===== a. 后端与策略门 =====

    /** DISABLED 策略下任何组合都不回退。 */
    @Test
    fun disabledPolicy_neverFallsBack() {
        for (clazz in EmptyResponseClass.entries) {
            for (reason in CompletionReason.entries) {
                for (thinking in listOf(false, true)) {
                    assertFalse(
                        "DISABLED + $clazz + $reason 不应回退",
                        eligible(
                            policy = disabled,
                            emptyResponseClass = clazz,
                            completionReason = reason,
                            thinkingRequested = thinking,
                        ),
                    )
                }
            }
        }
    }

    /** 非 GPU 后端（CPU/NPU）即使其余条件全满足也不回退（`backend != MNN_GPU` 门对两者同样生效）。 */
    @Test
    fun nonGpuBackends_neverFallBack() {
        for (backend in listOf(BackendType.MNN_CPU, BackendType.MNN_NPU)) {
            for (clazz in listOf(
                EmptyResponseClass.EOS_EMPTY,
                EmptyResponseClass.MAX_TOKENS_EMPTY,
                EmptyResponseClass.TEMPLATE_SUPPRESSED_OUTPUT,
                EmptyResponseClass.THINK_ONLY,
            )) {
                assertFalse("$backend 后端 + $clazz 不应回退", eligible(backend = backend, emptyResponseClass = clazz))
            }
        }
    }

    // ===== b. 零输出硬条件 =====

    /** generatedTokens > 0 或 callbackBytes > 0（已有任何 delta）都绝不回退。 */
    @Test
    fun anyOutput_blocksFallback() {
        assertFalse("generatedTokens=1 不应回退", eligible(generatedTokens = 1))
        assertFalse("callbackBytes=3 不应回退", eligible(callbackBytes = 3L))
        assertFalse("两者皆非零不应回退", eligible(generatedTokens = 2, callbackBytes = 10L))
        // 有输出时即使分类/原因/思考组合全部可回退也不放行。
        assertFalse("有输出 + THINK_ONLY + 思考关闭不应回退", eligible(
            emptyResponseClass = EmptyResponseClass.THINK_ONLY, thinkingRequested = false, callbackBytes = 8L))
    }

    // ===== c. 终止集合绝不回退 =====

    /** 终止集合（取消/超时/热停/策略截断/后端失败）即使分类可回退也绝不回退。 */
    @Test
    fun terminalReasons_neverFallBack() {
        for (reason in listOf(
            CompletionReason.USER_CANCEL,
            CompletionReason.TIMEOUT,
            CompletionReason.THERMAL_STOP,
            CompletionReason.POLICY_TRUNCATION,
            CompletionReason.BACKEND_FAILURE,
        )) {
            for (clazz in listOf(
                EmptyResponseClass.EOS_EMPTY,
                EmptyResponseClass.MAX_TOKENS_EMPTY,
                EmptyResponseClass.TEMPLATE_SUPPRESSED_OUTPUT,
                EmptyResponseClass.THINK_ONLY,
            )) {
                assertFalse(
                    "终止原因 $reason + $clazz 不应回退",
                    eligible(completionReason = reason, emptyResponseClass = clazz),
                )
            }
        }
    }

    /** 非终止原因（EOS / MAX_TOKENS）放行；null 原因（无请求级终止、无摘要原因）也不在终止集合内。 */
    @Test
    fun nonTerminalReasons_allowFallback() {
        assertTrue("EOS 应放行", eligible(completionReason = CompletionReason.EOS))
        assertTrue("MAX_TOKENS 应放行", eligible(completionReason = CompletionReason.MAX_TOKENS))
        assertTrue("null 原因（不在终止集合内）应放行", eligible(completionReason = null))
    }

    // ===== d. 可回退分类集合 =====

    /** EOS_EMPTY / MAX_TOKENS_EMPTY / TEMPLATE_SUPPRESSED_OUTPUT 在零输出+非终止原因下回退（与思考开关无关）。 */
    @Test
    fun fallbackEligibleClasses_fallBackRegardlessOfThinking() {
        for (clazz in listOf(
            EmptyResponseClass.EOS_EMPTY,
            EmptyResponseClass.MAX_TOKENS_EMPTY,
            EmptyResponseClass.TEMPLATE_SUPPRESSED_OUTPUT,
        )) {
            assertTrue("$clazz + 思考关闭应回退", eligible(emptyResponseClass = clazz, thinkingRequested = false))
            assertTrue("$clazz + 思考开启应回退", eligible(emptyResponseClass = clazz, thinkingRequested = true))
        }
    }

    /** THINK_ONLY：仅思考关闭时回退；思考开启是模型合法行为，绝不回退。 */
    @Test
    fun thinkOnly_fallsBackOnlyWhenThinkingOff() {
        assertTrue("THINK_ONLY + 思考关闭应回退", eligible(emptyResponseClass = EmptyResponseClass.THINK_ONLY, thinkingRequested = false))
        assertFalse("THINK_ONLY + 思考开启不应回退", eligible(emptyResponseClass = EmptyResponseClass.THINK_ONLY, thinkingRequested = true))
    }

    /** 不可回退分类（NONE 等）在零输出 + 非终止原因下也一律不回退。 */
    @Test
    fun neverEligibleClasses_neverFallBack() {
        for (clazz in listOf(
            EmptyResponseClass.NONE,
            EmptyResponseClass.WHITESPACE_ONLY,
            EmptyResponseClass.PREFILL_FAILURE,
            EmptyResponseClass.DECODE_FAILURE,
            EmptyResponseClass.CANCELLED,
            EmptyResponseClass.TIMEOUT,
            EmptyResponseClass.THERMAL_STOP,
        )) {
            for (thinking in listOf(false, true)) {
                assertFalse("$clazz + thinking=$thinking 不应回退", eligible(emptyResponseClass = clazz, thinkingRequested = thinking))
            }
        }
    }

    // ===== e. 分类缺失保守不回退 =====

    /** 分类器缺失/未收口（null）时即使其余条件全满足也不回退。 */
    @Test
    fun nullClassifier_neverFallsBack() {
        assertFalse("null 分类不应回退", eligible(emptyResponseClass = null))
        assertFalse("null 分类 + null 原因不应回退", eligible(completionReason = null, emptyResponseClass = null))
        assertFalse("null 分类 + 思考关闭不应回退", eligible(emptyResponseClass = null, thinkingRequested = false))
    }

    /** 组合冒烟：真实生产形态（GPU + 开启 + EOS + EOS_EMPTY + 零输出 + 思考关闭）必须回退。 */
    @Test
    fun productionShape_fallsBack() {
        assertTrue(eligible())
        // 同一形态但 policy 缺省（旧调用方未传）时默认 DISABLED -> 不回退。
        assertFalse(
            "默认策略（DISABLED）不应回退",
            GenerationOutcomeClassifier.isEligibleForCpuFallback(
                policy = GenerationOutputPolicy(),
                backend = BackendType.MNN_GPU,
                completionReason = CompletionReason.EOS,
                emptyResponseClass = EmptyResponseClass.EOS_EMPTY,
                generatedTokens = 0,
                callbackBytes = 0L,
                thinkingRequested = false,
            ),
        )
    }
}
