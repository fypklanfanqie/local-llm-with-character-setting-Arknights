package com.rhodesisland.terminal.llm.metrics

import com.rhodesisland.terminal.llm.backend.BackendType
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.thinking.LocalThinkingLevel
import com.rhodesisland.terminal.llm.thinking.LocalThinkingPlan
import com.rhodesisland.terminal.llm.thinking.QuestionComplexity
import com.rhodesisland.terminal.llm.thinking.ThinkingControlMode
import com.rhodesisland.terminal.llm.thinking.ThinkingPolicyTelemetry
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InferenceTelemetry] 生命周期与序列化测试（Task 2 Step 1）。
 *
 * 纯 JVM 单测：不依赖 Android 运行时（遥测模型本身无 Android 引用）。
 */
class InferenceTelemetryTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun beginGeneration_publishesPrefillSnapshotWithZeroProgress() {
        val t = InferenceTelemetry()
        t.beginGeneration(
            generationId = "g1",
            requestedMode = InferencePerformanceMode.BALANCED,
            effectiveMode = InferencePerformanceMode.BALANCED,
            backend = BackendType.MNN_CPU,
            startedElapsedMs = 1000L,
        )
        val snap = t.snapshot()
        assertNotNull(snap)
        assertEquals("g1", snap!!.generationId)
        assertEquals(InferenceStage.PREFILL, snap.stage)
        assertEquals(InferencePerformanceMode.BALANCED, snap.requestedMode)
        assertEquals(BackendType.MNN_CPU, snap.backend)
        assertEquals(0, snap.tokenCount)
        assertEquals(0, snap.callbackCount)
        assertEquals(0L, snap.callbackBytes)
        assertNull(snap.currentTps)
        assertEquals(1000L, snap.startedElapsedMs)
        assertEquals(1000L, snap.lastProgressElapsedMs)
        assertTrue(t.isActive)
    }

    @Test
    fun onDecodeToken_updatesCountsAndPublishesDecodeSnapshot() {
        val t = InferenceTelemetry()
        t.beginGeneration("g2", null, null, BackendType.MNN_GPU, 2000L)

        t.onDecodeToken(tokenCount = 1, callbackCount = 1, callbackBytes = 3L, currentTps = 5.0f, nowElapsedMs = 2100L)
        var snap = t.snapshot()!!
        assertEquals(InferenceStage.DECODE, snap.stage)
        assertEquals(1, snap.tokenCount)
        assertEquals(3L, snap.callbackBytes)
        assertEquals(5.0f, snap.currentTps!!, 0.0001f)
        assertEquals(2100L, snap.lastProgressElapsedMs)

        t.onDecodeToken(tokenCount = 2, callbackCount = 2, callbackBytes = 7L, currentTps = 9.5f, nowElapsedMs = 2200L)
        snap = t.snapshot()!!
        assertEquals(2, snap.tokenCount)
        assertEquals(7L, snap.callbackBytes)
        assertEquals(9.5f, snap.currentTps!!, 0.0001f)
        assertEquals(2200L, snap.lastProgressElapsedMs)
    }

    @Test
    fun finalize_buildsRecordFromNativeMetricsAndClearsSnapshot() {
        val t = InferenceTelemetry()
        t.beginGeneration("g3", InferencePerformanceMode.MAXIMUM_SPEED, InferencePerformanceMode.BALANCED,
            BackendType.MNN_NPU, 3000L)
        // 首 token 在 3100ms，第二个在 3200ms
        t.onDecodeToken(1, 1, 3L, 5f, 3100L)
        t.onDecodeToken(2, 2, 6L, 9f, 3200L)

        // native metrics: [tps=8, prefillUs=500000, decodeUs=200000, promptLen=50, genLen=16, reuseKv=1]
        val record = t.finalize(
            nowElapsedMs = 4000L,
            completionReason = CompletionReason.EOS,
            nativeMetrics = floatArrayOf(8.0f, 500000f, 200000f, 50f, 16f, 1f),
            peakPssMb = 1234L,
            thermalStart = 1, thermalMax = 2, thermalEnd = 2,
            configHash = "abc123",
            attemptTrace = listOf("NPU", "GPU"),
            downgradeReasons = listOf("thermal"),
            coldLoadMs = 800L,
        )

        assertNotNull(record)
        record!!
        assertEquals("g3", record.generationId)
        assertEquals(InferencePerformanceMode.MAXIMUM_SPEED, record.requestedMode)
        assertEquals(InferencePerformanceMode.BALANCED, record.effectiveMode) // 降级
        assertEquals(BackendType.MNN_NPU, record.backend)
        assertEquals(3000L, record.startedElapsedMs)
        assertEquals(4000L, record.endedElapsedMs)
        assertEquals(800L, record.coldLoadMs)
        assertEquals(100L, record.ttftMs) // 3100 - 3000
        assertEquals(500L, record.prefillMs) // 500000us -> 500ms
        assertEquals(200L, record.decodeMs) // 200000us -> 200ms
        assertEquals(50, record.promptTokens)
        assertEquals(16, record.generatedTokens)
        assertEquals(8.0f, record.decodeTps!!, 0.0001f)
        assertEquals(100f, record.prefillTps!!, 0.0001f) // 50 tokens / 0.5s
        assertTrue(record.kvReuse!!)
        assertEquals(1234L, record.peakPssMb)
        assertEquals(2, record.thermalMax)
        assertEquals(CompletionReason.EOS, record.completionReason)
        assertEquals(listOf("NPU", "GPU"), record.attemptTrace)
        assertEquals("abc123", record.configHash)
        assertEquals(listOf("thermal"), record.downgradeReasons)

        // finalize 后快照清空
        assertNull(t.snapshot())
        assertFalse(t.isActive)
    }

    @Test
    fun finalize_withoutActiveGeneration_returnsNullAndIsSafe() {
        val t = InferenceTelemetry()
        assertNull(t.finalize(nowElapsedMs = 0L, completionReason = CompletionReason.BACKEND_FAILURE))
        assertNull(t.snapshot())
    }

    @Test
    fun finalize_withoutNativeMetrics_fallsBackToCallbackCounts() {
        val t = InferenceTelemetry()
        t.beginGeneration("g4", null, null, BackendType.MNN_CPU, 0L)
        t.onDecodeToken(7, 7, 21L, 3.3f, 500L)
        val record = t.finalize(nowElapsedMs = 1000L, completionReason = CompletionReason.MAX_TOKENS)!!
        assertEquals(7, record.promptTokens) // 无 native -> 回落 tokenCount
        assertEquals(7, record.generatedTokens)
        assertEquals(3.3f, record.decodeTps!!, 0.0001f) // 回落 lastTps
        assertNull(record.kvReuse)
        assertNull(record.prefillMs)
        assertEquals(500L, record.ttftMs)
    }

    @Test
    fun finalize_ttftOverridePrefersNativeFirstDelta() {
        // Task 1：native firstDeltaUs（us）换算的 TTFT 优先于 Kotlin 侧首回调时间。
        val t = InferenceTelemetry()
        t.beginGeneration("g-ttft", InferencePerformanceMode.BALANCED, null, BackendType.MNN_CPU, 1000L)
        t.onDecodeToken(1, 1, 3L, 5f, 2000L)   // 回调时间为 1000ms
        val record = t.finalize(
            nowElapsedMs = 3000L,
            completionReason = CompletionReason.EOS,
            ttftMsOverride = 480L,             // native firstDeltaUs=480000us
        )!!
        assertEquals(480L, record.ttftMs)
    }

    @Test
    fun finalize_withoutTtftOverride_fallsBackToCallbackTime() {
        val t = InferenceTelemetry()
        t.beginGeneration("g-ttft2", null, null, BackendType.MNN_CPU, 1000L)
        t.onDecodeToken(1, 1, 3L, 5f, 1600L)
        val record = t.finalize(nowElapsedMs = 3000L, completionReason = CompletionReason.EOS)!!
        assertEquals(600L, record.ttftMs)
    }

    @Test
    fun reset_clearsActiveAndSnapshot() {
        val t = InferenceTelemetry()
        t.beginGeneration("g5", null, null, null, 0L)
        t.onDecodeToken(1, 1, 1L, 1f, 1L)
        assertNotNull(t.snapshot())
        assertTrue(t.isActive)
        t.reset()
        assertNull(t.snapshot())
        assertFalse(t.isActive)
    }

    @Test
    fun turnRecord_serializesRoundTrip() {
        val original = InferenceTurnRecord(
            generationId = "g-rt",
            requestedMode = InferencePerformanceMode.BALANCED,
            effectiveMode = InferencePerformanceMode.MAXIMUM_SPEED,
            backend = BackendType.MNN_GPU,
            startedElapsedMs = 100L,
            endedElapsedMs = 500L,
            coldLoadMs = 300L,
            warmLoadMs = null,
            ttftMs = 120L,
            prefillMs = 50L,
            decodeMs = 330L,
            promptTokens = 42,
            generatedTokens = 30,
            prefillTps = 840f,
            decodeTps = 90.9f,
            kvReuse = true,
            peakPssMb = 999L,
            thermalStart = 0,
            thermalMax = 3,
            thermalEnd = 2,
            completionReason = CompletionReason.USER_CANCEL,
            attemptTrace = listOf("GPU", "CPU"),
            configHash = "hash-xyz",
            downgradeReasons = listOf("battery"),
        )
        val s: String = json.encodeToString(original)
        val decoded: InferenceTurnRecord = json.decodeFromString(s)
        assertEquals(original, decoded)
    }

    @Suppress("DEPRECATION") // 断言读取兼容字段 thinkingCapTokens，避免弃用告警。
    @Test
    fun finalize_carriesThinkingPolicy() {
        // Task 5：思考档位策略快照随 finalize 一次收口。
        val t = InferenceTelemetry()
        t.beginGeneration("g-pol-f", InferencePerformanceMode.BALANCED, null, BackendType.MNN_CPU, 0L)
        val policy = ThinkingPolicyTelemetry(
            requestedLevel = "auto",
            effectiveLevel = "short",
            complexity = "SIMPLE",
            controlMode = "PROMPT_FALLBACK",
            targetMinMs = 5_000L,
            targetMaxMs = 8_000L,
            checkpointBudget = 2,
            generationMode = ThinkingPolicyTelemetry.SINGLE_PASS_SHARED_LIMIT,
            nativeBudgetCapability = "UNVERIFIED",
        )
        assertEquals(0, policy.thinkingCapTokens)
        val record = t.finalize(
            nowElapsedMs = 100L,
            completionReason = CompletionReason.EOS,
            thinkingPolicy = policy,
        )!!
        assertEquals(policy, record.thinkingPolicy)
    }

    @Test
    fun turnRecord_roundTripsThinkingPolicy() {
        val policy = ThinkingPolicyTelemetry(
            requestedLevel = "auto",
            effectiveLevel = "medium",
            complexity = "STANDARD",
            controlMode = "PROMPT_FALLBACK",
            targetMinMs = 8_000L,
            targetMaxMs = 15_000L,
            checkpointBudget = 4,
            generationMode = ThinkingPolicyTelemetry.SINGLE_PASS_SHARED_LIMIT,
            nativeBudgetCapability = "UNVERIFIED",
        )
        val original = InferenceTurnRecord(
            generationId = "g-policy",
            requestedMode = InferencePerformanceMode.BALANCED,
            effectiveMode = InferencePerformanceMode.BALANCED,
            backend = BackendType.MNN_CPU,
            startedElapsedMs = 1L,
            endedElapsedMs = 2L,
            thinkingPolicy = policy,
        )
        val s: String = json.encodeToString(original)
        val decoded: InferenceTurnRecord = json.decodeFromString(s)
        assertEquals(original, decoded)
    }

    @Suppress("DEPRECATION") // 断言读取兼容字段 thinkingCapTokens，避免弃用告警。
    @Test
    fun thinkingPolicy_decodesLegacyJsonWithNonZeroCap() {
        // 旧两阶段记录：含 thinkingCapTokens 且无 generationMode —— 前向兼容解码，
        // generationMode 用默认 SINGLE_PASS_SHARED_LIMIT 兜底。
        val s = """{"requestedLevel":"auto","effectiveLevel":"medium","complexity":"STANDARD","controlMode":"PROMPT_FALLBACK","targetMinMs":8000,"targetMaxMs":15000,"checkpointBudget":4,"thinkingCapTokens":384,"nativeBudgetCapability":"UNVERIFIED"}"""
        val decoded: ThinkingPolicyTelemetry = json.decodeFromString(s)
        assertEquals(384, decoded.thinkingCapTokens)
        assertEquals(ThinkingPolicyTelemetry.SINGLE_PASS_SHARED_LIMIT, decoded.generationMode)
    }

    @Suppress("DEPRECATION") // 断言读取兼容字段 thinkingCapTokens，避免弃用告警。
    @Test
    fun thinkingPolicy_fromNewPlanRecordsZeroCapAndSinglePassMode() {
        // 新 from() 记录：生成模式固定 SINGLE_PASS_SHARED_LIMIT、思考 cap 恒为 0（纯软提示）。
        val plan = LocalThinkingPlan(
            requestedLevel = LocalThinkingLevel.AUTO,
            effectiveLevel = LocalThinkingLevel.MEDIUM,
            complexity = QuestionComplexity.STANDARD,
            controlMode = ThinkingControlMode.PROMPT_FALLBACK,
            targetMinMs = 8_000L,
            targetMaxMs = 15_000L,
            checkpointBudget = 4,
            systemInstruction = "纯软提示：只约束思考，不改变最终答案",
        )
        val telemetry = ThinkingPolicyTelemetry.from(plan, "UNVERIFIED")!!
        assertEquals(0, telemetry.thinkingCapTokens)
        assertEquals(ThinkingPolicyTelemetry.SINGLE_PASS_SHARED_LIMIT, telemetry.generationMode)
        assertEquals("PROMPT_FALLBACK", telemetry.controlMode)
    }

    @Test
    fun turnRecord_legacyJsonWithoutThinkingPolicyDefaultsNull() {
        // 旧记录缺 thinkingPolicy：ignoreUnknownKeys + 默认值应解码为 null（前向兼容）。
        val s = """{"generationId":"g-legacy-policy","requestedMode":"BALANCED","effectiveMode":"BALANCED","backend":"MNN_CPU","startedElapsedMs":1,"endedElapsedMs":2}"""
        val decoded: InferenceTurnRecord = json.decodeFromString(s)
        assertNull(decoded.thinkingPolicy)
    }

    @Test
    fun turnRecord_serializationToleratesUnknownFutureFields() {
        // 模拟未来版本新增字段：旧记录应能解码（ignoreUnknownKeys=true）
        val s = """{"generationId":"g-fwd","requestedMode":"BALANCED","effectiveMode":"BALANCED","backend":"MNN_CPU","startedElapsedMs":1,"endedElapsedMs":2,"futureField":99}"""
        val decoded: InferenceTurnRecord = json.decodeFromString(s)
        assertEquals("g-fwd", decoded.generationId)
        assertEquals(BackendType.MNN_CPU, decoded.backend)
        assertEquals(0, decoded.generatedTokens) // 默认值
    }

    @Test
    fun benchmarkSummary_task5P95Fields_defaultToNull() {
        // Task 5：新增 P95 字段必须可空默认 null，保证旧构造点与旧 JSON 前向兼容
        val s = BenchmarkSummary()
        assertNull(s.medianTtftMs)
        assertNull(s.medianDecodeTps)
        assertNull(s.decodeStdDev)
        assertNull(s.peakPssMb)
        assertNull(s.maxThermalStatus)
        assertNull(s.kvReuseRate)
        assertNull(s.p95TtftMs)
        assertNull(s.p95DecodeTps)
    }

    @Test
    fun benchmarkSummary_serializesP95FieldsRoundTrip() {
        // 新字段参与序列化（旧 JSON 缺字段靠默认值兜底，新 JSON 全字段可往返）
        val original = BenchmarkSummary(
            medianTtftMs = 120f,
            medianDecodeTps = 40f,
            decodeStdDev = 3.5f,
            peakPssMb = 800L,
            maxThermalStatus = 2,
            kvReuseRate = 0.5f,
            p95TtftMs = 250f,
            p95DecodeTps = 45f,
        )
        val s: String = json.encodeToString(original)
        val decoded: BenchmarkSummary = json.decodeFromString(s)
        assertEquals(original, decoded)
    }

    @Test
    fun benchmarkSummary_decodesLegacyJsonWithoutP95Fields() {
        // 旧版本 JSON 无 P95 字段：ignoreUnknownKeys + 默认值应解码为 null（前向兼容）
        val s = """{"medianTtftMs":100.0,"medianDecodeTps":30.0,"decodeStdDev":2.0,"peakPssMb":500,"maxThermalStatus":1,"kvReuseRate":0.6}"""
        val decoded: BenchmarkSummary = json.decodeFromString(s)
        assertEquals(100f, decoded.medianTtftMs!!, 0.0001f)
        assertNull(decoded.p95TtftMs)
        assertNull(decoded.p95DecodeTps)
    }
}
