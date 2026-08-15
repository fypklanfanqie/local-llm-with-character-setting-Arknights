package com.rhodesisland.terminal.llm.benchmark

import com.rhodesisland.terminal.llm.backend.BackendPreference
import com.rhodesisland.terminal.llm.metrics.BenchmarkSummary
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四象限与基准用例测试（Task 5 Step 2/3/6 纯 Kotlin 部分）。
 *
 * 覆盖：象限 storageKey round-trip、象限推导（of）、EMPTY_RESPONSE_CHECK 场景追加、
 * [InferenceBenchmarkCase] / [ReliabilityResult] / [BenchmarkScenarioResult] 序列化 round-trip。
 */
class InferenceBenchmarkCaseTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ------------------------------------------------------------------
    // 象限 storageKey round-trip
    // ------------------------------------------------------------------

    @Test
    fun quadrant_storageKeyRoundTrips() {
        for (q in InferenceBackendQuadrant.entries) {
            assertEquals(q, InferenceBackendQuadrant.fromStorageKey(q.storageKey))
        }
    }

    @Test
    fun quadrant_fromStorageKey_unknownOrNullReturnsNull() {
        assertNull(InferenceBackendQuadrant.fromStorageKey("NO_SUCH_QUADRANT"))
        assertNull(InferenceBackendQuadrant.fromStorageKey(null))
    }

    // ------------------------------------------------------------------
    // 象限推导（of）与属性
    // ------------------------------------------------------------------

    @Test
    fun quadrant_of_mapsBackendPreferenceAndThinking() {
        assertEquals(InferenceBackendQuadrant.CPU_THINKING_OFF, InferenceBackendQuadrant.of(BackendPreference.MNN_CPU, false))
        assertEquals(InferenceBackendQuadrant.CPU_THINKING_ON, InferenceBackendQuadrant.of(BackendPreference.MNN_CPU, true))
        assertEquals(InferenceBackendQuadrant.GPU_THINKING_OFF, InferenceBackendQuadrant.of(BackendPreference.MNN_GPU, false))
        assertEquals(InferenceBackendQuadrant.GPU_THINKING_ON, InferenceBackendQuadrant.of(BackendPreference.MNN_GPU, true))
        // AUTO 解析链 GPU 优先 -> GPU 象限
        assertEquals(InferenceBackendQuadrant.GPU_THINKING_OFF, InferenceBackendQuadrant.of(BackendPreference.AUTO, false))
        // NPU 偏好标准构建解析为 CPU（Task 11 语义）
        assertEquals(InferenceBackendQuadrant.CPU_THINKING_ON, InferenceBackendQuadrant.of(BackendPreference.MNN_NPU, true))
    }

    @Test
    fun quadrant_usesGpuAndThinkingEnabledFlags() {
        assertFalse(InferenceBackendQuadrant.CPU_THINKING_OFF.usesGpu)
        assertFalse(InferenceBackendQuadrant.CPU_THINKING_ON.usesGpu)
        assertTrue(InferenceBackendQuadrant.GPU_THINKING_OFF.usesGpu)
        assertTrue(InferenceBackendQuadrant.GPU_THINKING_ON.usesGpu)
        assertFalse(InferenceBackendQuadrant.CPU_THINKING_OFF.thinkingEnabled)
        assertTrue(InferenceBackendQuadrant.CPU_THINKING_ON.thinkingEnabled)
        assertFalse(InferenceBackendQuadrant.GPU_THINKING_OFF.thinkingEnabled)
        assertTrue(InferenceBackendQuadrant.GPU_THINKING_ON.thinkingEnabled)
    }

    // ------------------------------------------------------------------
    // EMPTY_RESPONSE_CHECK 场景（追加不改既有 5 场景）
    // ------------------------------------------------------------------

    @Test
    fun emptyResponseCheck_appendedWithoutRemovingExistingScenarios() {
        // 既有 5 场景 + Task 5 新增 1 = 6
        assertEquals(6, InferenceBenchmarkScenario.entries.size)
        assertEquals(
            InferenceBenchmarkScenario.EMPTY_RESPONSE_CHECK,
            InferenceBenchmarkScenario.fromStorageKey("EMPTY_RESPONSE_CHECK"),
        )
        assertFalse(InferenceBenchmarkScenario.EMPTY_RESPONSE_CHECK.requiresColdStart)
        // 既有场景 storageKey 保持原值（归档键不变）
        assertEquals(InferenceBenchmarkScenario.COLD_LOAD, InferenceBenchmarkScenario.fromStorageKey("COLD_LOAD"))
        assertEquals(InferenceBenchmarkScenario.SHORT_TTFT, InferenceBenchmarkScenario.fromStorageKey("SHORT_TTFT"))
        assertEquals(InferenceBenchmarkScenario.LONG_PREFILL, InferenceBenchmarkScenario.fromStorageKey("LONG_PREFILL"))
        assertEquals(InferenceBenchmarkScenario.FIXED_DECODE, InferenceBenchmarkScenario.fromStorageKey("FIXED_DECODE"))
        assertEquals(
            InferenceBenchmarkScenario.SECOND_TURN_KV_REUSE,
            InferenceBenchmarkScenario.fromStorageKey("SECOND_TURN_KV_REUSE"),
        )
    }

    // ------------------------------------------------------------------
    // 序列化 round-trip
    // ------------------------------------------------------------------

    @Test
    fun benchmarkCase_serializesRoundTrip() {
        val case = InferenceBenchmarkCase(
            scenario = InferenceBenchmarkScenario.EMPTY_RESPONSE_CHECK,
            quadrant = InferenceBackendQuadrant.GPU_THINKING_ON,
            modelFingerprint = "model-abc123",
            deviceFingerprint = "sdm8gen3-android14-arm64",
            configHash = "cfg-xyz789",
        )
        val s: String = json.encodeToString(case)
        val decoded: InferenceBenchmarkCase = json.decodeFromString(s)
        assertEquals(case, decoded)
    }

    @Test
    fun reliabilityResult_serializesRoundTrip() {
        val r = ReliabilityResult(
            emptyResponseClasses = mapOf("NONE" to 18, "THINK_ONLY" to 1, "EOS_EMPTY" to 1),
            fallbackCount = 2,
            nonEmptySuccessRate = 0.9f,
            totalRounds = 20,
        )
        val s: String = json.encodeToString(r)
        val decoded: ReliabilityResult = json.decodeFromString(s)
        assertEquals(r, decoded)
    }

    @Test
    fun benchmarkScenarioResult_serializesWithNewDimensions() {
        val result = BenchmarkScenarioResult(
            scenario = InferenceBenchmarkScenario.SHORT_TTFT,
            deviceFingerprint = "dev-1",
            configFingerprint = "cfg-1",
            summary = BenchmarkSummary(
                medianTtftMs = 120f,
                medianDecodeTps = 40f,
                decodeStdDev = 3.5f,
                p95TtftMs = 200f,
                p95DecodeTps = 55f,
                kvReuseRate = 1f,
            ),
            recordedSampleCount = 5,
            warmupSampleCount = 1,
            coolRun = true,
            discardedReasons = emptyList(),
            quadrant = InferenceBackendQuadrant.CPU_THINKING_OFF,
            thinkingRequested = false,
            backendVariant = "CPU_OPTIMIZED",
            nativeBuildId = "build-1",
            mnnCommit = "af0142bc",
        )
        val s: String = json.encodeToString(result)
        val decoded: BenchmarkScenarioResult = json.decodeFromString(s)
        assertEquals(result, decoded)
    }

    @Test
    fun benchmarkScenarioResult_decodesLegacyJsonWithoutTask5Fields() {
        // 旧版本 JSON 无象限/构建字段：默认值兜底解码（前向兼容，任务 Step 3 要求不破坏既有构造点）
        val s = """{"scenario":"SHORT_TTFT","deviceFingerprint":"dev","configFingerprint":"cfg","summary":{},"recordedSampleCount":5,"warmupSampleCount":1,"coolRun":true,"discardedReasons":[]}"""
        val decoded: BenchmarkScenarioResult = json.decodeFromString(s)
        assertEquals(InferenceBenchmarkScenario.SHORT_TTFT, decoded.scenario)
        assertTrue(decoded.coolRun)
        assertNull(decoded.quadrant)
        assertNull(decoded.thinkingRequested)
        assertNull(decoded.backendVariant)
        assertNull(decoded.nativeBuildId)
        assertNull(decoded.mnnCommit)
    }
}
