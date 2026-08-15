package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PromptWindowPlanner 边界契约测试（Task 5 Step 1）。 */
class PromptWindowPlannerTest {

    private val planner = PromptWindowPlanner()

    @Test
    fun outputDefaultsAreBoundedButUnlimitedRemainsExplicit() {
        assertEquals(2048, com.rhodesisland.terminal.config.AppConfig.LLM.DEFAULT_MAX_TOKENS)
        assertEquals(65536, com.rhodesisland.terminal.config.AppConfig.LLM.MAX_TOKENS_UNLIMITED)
    }

    private fun msg(role: String, content: String, modelContent: String? = null) =
        ChatMessage(role = role, content = content, modelContent = modelContent)

    @Test
    fun systemPromptIsAlwaysRetained() {
        val messages = listOf(
            msg("system", "SYSTEM-ANCHOR"),
            msg("user", "old question ".repeat(40)),
            msg("assistant", "old answer ".repeat(40)),
            msg("user", "latest"),
        )

        val result = planner.plan(messages, admittedContextTokens = 80, requestedOutputTokens = 16)

        val plan = result as PromptWindowResult.Success
        assertEquals("system", plan.plan.messages.first().role)
        assertEquals("SYSTEM-ANCHOR", plan.plan.messages.first().content)
        assertEquals("latest", plan.plan.messages.last().content)
    }

    @Test
    fun retainsLargestRecentSuffixOfCompleteTurns() {
        val messages = listOf(
            msg("system", "system"),
            msg("user", "old-user-".repeat(20)),
            msg("assistant", "old-assistant-".repeat(20)),
            msg("user", "recent-user"),
            msg("assistant", "recent-assistant"),
            msg("user", "latest-user"),
        )

        val result = planner.plan(messages, admittedContextTokens = 110, requestedOutputTokens = 20)

        val kept = (result as PromptWindowResult.Success).plan.messages
        assertEquals(listOf("system", "user", "assistant", "user"), kept.map { it.role })
        assertEquals("recent-user", kept[1].content)
        assertEquals("recent-assistant", kept[2].content)
        assertEquals("latest-user", kept[3].content)
    }

    @Test
    fun neverRetainsOrphanAssistantTurn() {
        val messages = listOf(
            msg("system", "system"),
            msg("assistant", "orphan before any user"),
            msg("user", "paired-user"),
            msg("assistant", "paired-assistant"),
            msg("user", "latest"),
        )

        val result = planner.plan(messages, admittedContextTokens = 200, requestedOutputTokens = 20)

        val kept = (result as PromptWindowResult.Success).plan.messages
        assertFalse(kept.any { it.content == "orphan before any user" })
        assertEquals(listOf("system", "user", "assistant", "user"), kept.map { it.role })
    }

    @Test
    fun localAssistantEstimateUsesModelContentRatherThanDisplayContent() {
        val display = "短"
        val raw = "模型真实输出".repeat(80)
        val messages = listOf(
            msg("system", "system"),
            msg("user", "old question"),
            msg("assistant", display, modelContent = raw),
            msg("user", "latest"),
        )

        val result = planner.plan(messages, admittedContextTokens = 100, requestedOutputTokens = 16)

        val plan = (result as PromptWindowResult.Success).plan
        assertEquals(listOf("system", "user"), plan.messages.map { it.role })
        assertEquals("latest", plan.messages.last().content)
        assertTrue(plan.estimatedInputTokens < PromptWindowPlanner.estimateTextTokens(raw))
        // 被保留时 planner 必须原样返回双字段，实际模型可见映射在 provider 规划之后进行。
        val roomy = planner.plan(messages, admittedContextTokens = 800, requestedOutputTokens = 16)
            as PromptWindowResult.Success
        val keptAssistant = roomy.plan.messages.first { it.role == "assistant" }
        assertEquals(display, keptAssistant.content)
        assertEquals(raw, keptAssistant.modelContent)
    }

    @Test
    fun knownRuntimeTokenCountsOverrideFallbackEstimate() {
        val messages = listOf(msg("system", "very long system text".repeat(20)), msg("user", "latest"))

        val result = planner.plan(
            messages = messages,
            admittedContextTokens = 80,
            requestedOutputTokens = 32,
            knownMessageTokenCounts = mapOf(0 to 3, 1 to 2),
        )

        val plan = (result as PromptWindowResult.Success).plan
        assertEquals(13, plan.estimatedInputTokens) // 实测内容 3+2，加两条消息模板开销 4+4
        assertEquals(32, plan.reservedOutputTokens)
    }

    @Test
    fun respectsOutputAndTemplateReserve() {
        val messages = listOf(msg("system", "system"), msg("user", "latest question"))

        val result = planner.plan(
            messages = messages,
            admittedContextTokens = 128,
            requestedOutputTokens = 48,
            templateReserveTokens = 24,
        )

        val plan = (result as PromptWindowResult.Success).plan
        assertEquals(48, plan.reservedOutputTokens)
        assertTrue(plan.estimatedInputTokens + plan.reservedOutputTokens + 24 <= 128)
    }

    @Test
    fun appendingFirstCompleteTurnDoesNotCountAsLeftAnchorChange() {
        val first = listOf(msg("system", "system"), msg("user", "first"))
        val firstPlan = (planner.plan(first, 200, 32) as PromptWindowResult.Success).plan
        val next = listOf(
            msg("system", "system"),
            msg("user", "first"),
            msg("assistant", "answer"),
            msg("user", "second"),
        )

        val nextPlan = (planner.plan(next, 200, 32, previousAnchor = firstPlan.anchor)
            as PromptWindowResult.Success).plan

        assertFalse(nextPlan.anchorChanged)
    }

    @Test
    fun detectsAnchorChangeWhenOldHistoryIsDropped() {
        val full = listOf(
            msg("system", "system"),
            msg("user", "old user ".repeat(30)),
            msg("assistant", "old assistant ".repeat(30)),
            msg("user", "latest"),
        )
        val fullResult = planner.plan(full, admittedContextTokens = 500, requestedOutputTokens = 32)
        val fullPlan = (fullResult as PromptWindowResult.Success).plan

        val trimmedResult = planner.plan(
            messages = full,
            admittedContextTokens = 80,
            requestedOutputTokens = 32,
            previousAnchor = fullPlan.anchor,
        )

        val trimmed = (trimmedResult as PromptWindowResult.Success).plan
        assertTrue(trimmed.anchorChanged)
        assertTrue(trimmed.downgradeReason?.contains("history") == true)
    }

    @Test
    fun oversizedSystemReturnsTypedAdmissionFailure() {
        val messages = listOf(
            msg("system", "过大的系统提示".repeat(200)),
            msg("user", "latest"),
        )

        val result = planner.plan(messages, admittedContextTokens = 64, requestedOutputTokens = 32)

        val failure = result as PromptWindowResult.AdmissionFailure
        assertEquals(PromptAdmissionFailureReason.SYSTEM_PROMPT_TOO_LARGE, failure.reason)
    }

    @Test
    fun oversizedLatestUserReturnsTypedAdmissionFailure() {
        val messages = listOf(
            msg("system", "system"),
            msg("user", "超大最新消息".repeat(200)),
        )

        val result = planner.plan(messages, admittedContextTokens = 64, requestedOutputTokens = 32)

        assertTrue(result is PromptWindowResult.AdmissionFailure)
        val failure = result as PromptWindowResult.AdmissionFailure
        assertEquals(PromptAdmissionFailureReason.LATEST_USER_TOO_LARGE, failure.reason)
        assertTrue(failure.requiredInputTokens > failure.availableInputTokens)
        assertNull(failure.plan)
    }

    @Test
    fun safetyPolicyKeepsHardLimitsProfileIndependentAndWallClockProfileSpecific() {
        val balanced = GenerationSafetyPolicy.forMode(
            com.rhodesisland.terminal.llm.profile.InferencePerformanceMode.BALANCED,
            maxTokens = 2048,
        )
        val speed = GenerationSafetyPolicy.forMode(
            com.rhodesisland.terminal.llm.profile.InferencePerformanceMode.MAXIMUM_SPEED,
            maxTokens = 2048,
        )

        assertEquals(balanced.maxTokens, speed.maxTokens)
        assertEquals(balanced.stallTimeoutMs, speed.stallTimeoutMs)
        assertTrue(balanced.wallClockTimeoutMs != speed.wallClockTimeoutMs)
    }

    @Test
    fun generationGuardReturnsMaxTokensAtHardLimit() {
        val guard = GenerationProgressGuard(
            policy = GenerationSafetyPolicy(maxTokens = 8, stallTimeoutMs = 1_000, wallClockTimeoutMs = 5_000),
            startedElapsedMs = 100,
        )

        guard.onProgress(generatedTokens = 8, nowElapsedMs = 300)

        assertEquals(
            com.rhodesisland.terminal.llm.metrics.CompletionReason.MAX_TOKENS,
            guard.completionReason(nowElapsedMs = 300),
        )
    }

    @Test
    fun requestStopStateIsTerminalAcrossBackendFallback() {
        val state = GenerationRequestStopState()
        state.beginRequest()
        assertTrue(state.canTryNextBackend())

        state.requestStop(com.rhodesisland.terminal.llm.metrics.CompletionReason.TIMEOUT)

        assertEquals(com.rhodesisland.terminal.llm.metrics.CompletionReason.TIMEOUT, state.reason())
        assertFalse(state.canTryNextBackend())
        // 后到的用户取消不覆盖最先确定的 timeout 终态。
        state.requestStop(com.rhodesisland.terminal.llm.metrics.CompletionReason.USER_CANCEL)
        assertEquals(com.rhodesisland.terminal.llm.metrics.CompletionReason.TIMEOUT, state.reason())
    }

    @Test
    fun generationGuardKeepsOneRequestDeadlineAcrossBackendAttempts() {
        val guard = GenerationProgressGuard(
            policy = GenerationSafetyPolicy(maxTokens = 100, stallTimeoutMs = 1_000, wallClockTimeoutMs = 5_000),
            startedElapsedMs = 100,
        )
        guard.onProgress("gpu-1", generatedTokens = 20, progressElapsedMs = 2_000)
        guard.onProgress("cpu-2", generatedTokens = 1, progressElapsedMs = 4_000)

        assertEquals(21, guard.generatedTokens())
        assertEquals(79, guard.remainingTokens())
        assertEquals(
            com.rhodesisland.terminal.llm.metrics.CompletionReason.TIMEOUT,
            guard.completionReason(nowElapsedMs = 5_101),
        )
    }

    @Test
    fun generationGuardUsesActualProgressTimestampNotPollTime() {
        val guard = GenerationProgressGuard(
            policy = GenerationSafetyPolicy(maxTokens = 100, stallTimeoutMs = 1_000, wallClockTimeoutMs = 10_000),
            startedElapsedMs = 100,
        )
        guard.onProgress("cpu-1", generatedTokens = 1, progressElapsedMs = 200)

        assertEquals(
            com.rhodesisland.terminal.llm.metrics.CompletionReason.TIMEOUT,
            guard.completionReason(nowElapsedMs = 1_201),
        )
    }

    @Test
    fun generationGuardReturnsTimeoutForStallOrWallClockDeadline() {
        val stall = GenerationProgressGuard(
            policy = GenerationSafetyPolicy(maxTokens = 100, stallTimeoutMs = 1_000, wallClockTimeoutMs = 5_000),
            startedElapsedMs = 100,
        )
        stall.onProgress(generatedTokens = 1, nowElapsedMs = 200)
        assertEquals(
            com.rhodesisland.terminal.llm.metrics.CompletionReason.TIMEOUT,
            stall.completionReason(nowElapsedMs = 1_201),
        )

        val wall = GenerationProgressGuard(
            policy = GenerationSafetyPolicy(maxTokens = 100, stallTimeoutMs = 10_000, wallClockTimeoutMs = 2_000),
            startedElapsedMs = 100,
        )
        assertEquals(
            com.rhodesisland.terminal.llm.metrics.CompletionReason.TIMEOUT,
            wall.completionReason(nowElapsedMs = 2_101),
        )
    }
}
