package com.rhodesisland.terminal.llm.backend

import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.llm.GenerationExecutionControl
import com.rhodesisland.terminal.llm.GenerationSafetyPolicy
import com.rhodesisland.terminal.llm.metrics.CompletionReason
import com.rhodesisland.terminal.llm.metrics.NativeGenerationSummary
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.profile.PowerPolicy
import com.rhodesisland.terminal.llm.profile.ResidencyPolicy
import com.rhodesisland.terminal.llm.profile.ResolvedInferencePlan
import com.rhodesisland.terminal.llm.profile.StreamPolicy
import com.rhodesisland.terminal.llm.template.ThinkingOutputClassifier
import com.rhodesisland.terminal.llm.template.ThinkingTemplateCapability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocalGenerationRequest] 与 [LocalGenerationRunner] 的**请求值对象 / runner seam 契约**测试（Task 2）。
 *
 * 本测试只验证「请求值对象 + runner seam 的契约」，**不驱动 [LocalChatProvider] 编排**：
 * - runner 恰好转发一次（fake 记录调用）；
 * - 请求上限跨源一致：`maxTokens` 来自执行计划（`resolvedPlan.maxOutputTokens`），不随思考开关变化；
 * - 消息逐字透传：请求携带的消息与传入完全相等，无任何「直接给出最终答案」之类追加指令。
 *
 * 「Provider 每条消息恰好一次 generate」由 Task 3 的 MnnRuntimeIntegrationTest 真机测试 +
 * 本 diff 静态审查共同保证。用显式 object 实现 [LocalGenerationRunner] 记录调用，
 * 不实例化 Android Context / 真实 native。
 */
class LocalGenerationRequestTest {

    // ===== helpers =====

    private fun resolvedPlan(maxTokens: Int = 2048): ResolvedInferencePlan = ResolvedInferencePlan(
        requestedMode = InferencePerformanceMode.BALANCED,
        effectiveMode = InferencePerformanceMode.BALANCED,
        contextTokens = 2048,
        maxOutputTokens = maxTokens,
        streamPolicy = StreamPolicy(batchMaxBytes = 256, batchMaxMs = 16),
        powerPolicy = PowerPolicy.DEFAULT,
        residencyPolicy = ResidencyPolicy(keepAliveMs = 0L),
        attempts = emptyList(),
        downgradeReasons = emptyList(),
    )

    private fun localGenerationRequest(
        plan: ResolvedInferencePlan,
        enableThinking: Boolean,
        messages: List<ChatMessage> = listOf(ChatMessage(role = "user", content = "你好")),
    ): LocalGenerationRequest = LocalGenerationRequest(
        modelPath = "/models/config.json",
        messages = messages,
        // 镜像 Provider 构造路径：总上限从执行计划取，而不是独立传入。
        maxTokens = plan.maxOutputTokens,
        temperature = 0.8f,
        topP = 0.9f,
        repeatPenalty = 1.2f,
        enableThinking = enableThinking,
        downgradeReasons = emptyList(),
        resolvedPlan = plan,
        thinkingRequested = enableThinking,
        templateCapability = ThinkingTemplateCapability.SUPPORTED.name,
        thinkingClassifier = ThinkingOutputClassifier(
            thinkingRequested = enableThinking,
            templateCapability = ThinkingTemplateCapability.SUPPORTED,
        ),
        thinkingPolicy = null,
        outputPolicy = GenerationOutputPolicy(),
        decodeStepTokens = 1,
    )

    private fun control(maxTokens: Int): GenerationExecutionControl = GenerationExecutionControl(
        policy = GenerationSafetyPolicy.forMode(InferencePerformanceMode.BALANCED, maxTokens),
        startedElapsedMs = 0L,
    )

    private fun fakeGenerationResult(reason: CompletionReason): BackendManager.GenerationResult =
        BackendManager.GenerationResult(
            summary = NativeGenerationSummary(
                version = NativeGenerationSummary.VERSION,
                completionReason = reason.name,
                promptTokens = 5,
                generatedTokens = 8,
                prefillUs = 1_000_000L,
                decodeUs = 500_000L,
                reuseKv = 0,
                callbackCount = 8,
                callbackBytes = 40L,
            ),
            usedBackend = BackendType.MNN_CPU,
            reloaded = false,
            completionReason = reason,
        )

    private fun recordingRunner(calls: MutableList<LocalGenerationRequest>): LocalGenerationRunner =
        object : LocalGenerationRunner {
            override suspend fun generate(
                request: LocalGenerationRequest,
                executionControl: GenerationExecutionControl,
                onToken: (String) -> Boolean,
            ): BackendManager.GenerationResult {
                calls += request
                onToken("分析过程</think>最终答案")
                return fakeGenerationResult(CompletionReason.EOS)
            }
        }

    // ===== tests =====

    @Test
    fun thinkingRequestRunsExactlyOnceWithResolvedTotalLimit() = runTest {
        val calls = mutableListOf<LocalGenerationRequest>()
        val runner = recordingRunner(calls)
        val plan = resolvedPlan(2048)
        val request = localGenerationRequest(plan, enableThinking = true)

        runner.generate(request, control(maxTokens = 2048)) { true }

        assertEquals(1, calls.size)
        val call = calls.single()
        assertEquals("请求上限应等于构造时传入的 2048", 2048, call.maxTokens)
        assertEquals("请求上限应与执行计划跨源一致（maxTokens 取自 plan）",
            plan.maxOutputTokens, call.maxTokens)
        assertTrue(call.enableThinking)
    }

    @Test
    fun thinkingOffStillRunsOnceWithSameResolvedLimit() = runTest {
        val calls = mutableListOf<LocalGenerationRequest>()
        val runner = recordingRunner(calls)
        val plan = resolvedPlan(1024)
        val request = localGenerationRequest(plan, enableThinking = false)

        runner.generate(request, control(maxTokens = 1024)) { true }

        assertEquals(1, calls.size)
        val call = calls.single()
        assertEquals("请求上限应与执行计划跨源一致（maxTokens 取自 plan）",
            plan.maxOutputTokens, call.maxTokens)
        assertFalse(call.enableThinking)
    }

    @Test
    fun maxTokensComesFromPlanNotThinkingToggle() = runTest {
        val calls = mutableListOf<LocalGenerationRequest>()
        val runner = recordingRunner(calls)
        val plan = resolvedPlan(2048)
        // 同一 plan 构造思考开/关两个 request：总上限只来自 plan，不随思考开关变化。
        val onRequest = localGenerationRequest(plan, enableThinking = true)
        val offRequest = localGenerationRequest(plan, enableThinking = false)

        runner.generate(onRequest, control(maxTokens = 2048)) { true }
        runner.generate(offRequest, control(maxTokens = 2048)) { true }

        assertEquals(2, calls.size)
        assertEquals("思考开/关两请求的上限都来自 plan", plan.maxOutputTokens, calls[0].maxTokens)
        assertEquals("思考开/关两请求的上限彼此相等", calls[0].maxTokens, calls[1].maxTokens)
        assertTrue(calls[0].enableThinking)
        assertFalse(calls[1].enableThinking)
    }

    @Test
    fun requestMessagesArePassedThroughVerbatim() = runTest {
        val calls = mutableListOf<LocalGenerationRequest>()
        val runner = recordingRunner(calls)
        val messages = listOf(
            ChatMessage(role = "system", content = "sys"),
            ChatMessage(role = "user", content = "请回答"),
        )
        val plan = resolvedPlan(2048)
        val request = localGenerationRequest(plan, enableThinking = true, messages = messages)

        runner.generate(request, control(maxTokens = 2048)) { true }

        // 消息逐字透传：列表完全相等，无任何「直接给出最终答案」之类的追加指令。
        assertEquals(messages, calls.single().messages)
    }
}
