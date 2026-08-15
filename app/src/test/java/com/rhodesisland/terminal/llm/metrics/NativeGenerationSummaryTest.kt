package com.rhodesisland.terminal.llm.metrics

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NativeGenerationSummary wire 契约测试（Task 4 Step 1 + Task 1 v2）。
 *
 * 覆盖：每个 CompletionReason / InferenceStage 严格解析、未知版本/未知 reason/未知 stage
 * 拒收、非法 JSON 拒收、可选字段缺省、v1 摘要向后兼容（v2 新字段解析回填默认值）、
 * v2 新字段解析（decodeStepTokens / thinkingConfigAccepted / reasoningEndUs /
 * firstBodyDeltaUs / errorCode）、reuseKv 语义映射、序列化 round-trip（v1 与 v2）。
 */
class NativeGenerationSummaryTest {

    /** v1 wire 形态样本（Task 4）：v2 新字段缺省，用于验证向后兼容回填。 */
    private fun sampleJson(reason: String): String =
        """{"v":1,"completionReason":"$reason","promptTokens":120,"generatedTokens":45,"""" +
            """"prefillUs":900000,"decodeUs":450000,"reuseKv":1,"callbackCount":9,"callbackBytes":360,"""" +
            """"firstDeltaUs":950000,"errorStage":null,"errorMessage":null}"""

    /** v2 wire 形态样本（Task 1）：含全部 v2 新字段（native mnn_jni.cpp v2 摘要的输出形态）。 */
    private fun sampleJsonV2(
        reason: String = "EOS",
        decodeStepTokens: Int = 2,
        thinkingConfigAccepted: String = "true",
        reasoningEndUs: Long? = 123456L,
        firstBodyDeltaUs: Long? = 234567L,
        errorCode: String? = null,
    ): String = buildString {
        append("""{"v":2,"completionReason":"$reason","promptTokens":120,"generatedTokens":45,"""")
        append(""""prefillUs":900000,"decodeUs":450000,"reuseKv":1,"callbackCount":9,"callbackBytes":360,"""")
        append(""""firstDeltaUs":950000,"errorStage":null,"errorMessage":null,""")
        append(""""decodeStepTokens":$decodeStepTokens,"thinkingConfigAccepted":$thinkingConfigAccepted,""")
        append(""""reasoningEndUs":${reasoningEndUs ?: "null"},"firstBodyDeltaUs":${firstBodyDeltaUs ?: "null"},""")
        append(""""errorCode":${errorCode?.let { "\"$it\"" } ?: "null"}}""")
    }

    @Test
    fun parsesEveryCompletionReason() {
        for (reason in CompletionReason.entries) {
            val s = NativeGenerationSummary.parse(sampleJson(reason.name))
            assertNotNull("应能解析 $reason", s)
            assertEquals(reason.name, s!!.completionReason)
        }
    }

    @Test
    fun parsesAllInferenceStages() {
        for (stage in InferenceStage.entries) {
            val json = sampleJson("EOS").replace(
                "\"errorStage\":null",
                "\"errorStage\":\"${stage.name}\"",
            )
            val s = NativeGenerationSummary.parse(json)
            assertNotNull("应能解析 errorStage=$stage", s)
            assertEquals(stage.name, s!!.errorStage)
        }
    }

    @Test
    fun unknownVersionRejected() {
        // v1/v2 均为合法 wire 版本；v3（未来版本）应拒收。
        assertNull(NativeGenerationSummary.parse(sampleJson("EOS").replace("\"v\":1", "\"v\":3")))
        assertNull(NativeGenerationSummary.parse(sampleJsonV2().replace("\"v\":2", "\"v\":99")))
    }

    @Test
    fun unknownCompletionReasonRejected() {
        assertNull(NativeGenerationSummary.parse(sampleJson("DEFINITELY_NOT_A_REASON")))
    }

    @Test
    fun unknownErrorStageRejected() {
        assertNull(NativeGenerationSummary.parse(
            sampleJson("EOS").replace("\"errorStage\":null", "\"errorStage\":\"BOGUS\""),
        ))
    }

    @Test
    fun malformedJsonRejected() {
        assertNull(NativeGenerationSummary.parse("{not json"))
        assertNull(NativeGenerationSummary.parse(""))
        assertNull(NativeGenerationSummary.parse("null"))
    }

    @Test
    fun missingOptionalFieldsTolerated() {
        val json = """{"v":1,"completionReason":"EOS","promptTokens":1,"generatedTokens":1,"""" +
            """"prefillUs":1,"decodeUs":1,"reuseKv":0,"callbackCount":1,"callbackBytes":4}"""
        val s = NativeGenerationSummary.parse(json)
        assertNotNull(s)
        assertNull(s!!.firstDeltaUs)
        assertNull(s.errorStage)
        assertNull(s.errorMessage)
        // v2 新字段缺省（v1 摘要）：全部回填默认/可空值。
        assertEquals(1, s.decodeStepTokens)
        assertNull(s.thinkingConfigAccepted)
        assertNull(s.reasoningEndUs)
        assertNull(s.firstBodyDeltaUs)
        assertNull(s.errorCode)
    }

    @Test
    fun kvReuseSemanticMapping() {
        assertTrue(NativeGenerationSummary.parse(sampleJson("EOS"))!!.kvReuse == true)
        assertTrue(NativeGenerationSummary.parse(
            sampleJson("EOS").replace("\"reuseKv\":1", "\"reuseKv\":0"),
        )!!.kvReuse == false)
        assertNull(NativeGenerationSummary.parse(
            sampleJson("EOS").replace("\"reuseKv\":1", "\"reuseKv\":-1"),
        )!!.kvReuse)
    }

    @Test
    fun roundTripSerialization() {
        val s = NativeGenerationSummary.parse(sampleJson("MAX_TOKENS"))!!
        val encoded = NativeGenerationSummary.summaryJson.encodeToString(s)
        val decoded = NativeGenerationSummary.parse(encoded)
        assertNotNull(decoded)
        assertEquals("MAX_TOKENS", decoded!!.completionReason)
        assertEquals(s.generatedTokens, decoded.generatedTokens)
        assertEquals(s.prefillUs, decoded.prefillUs)
        // v1 round-trip 后 v2 字段仍为默认。
        assertEquals(1, decoded.decodeStepTokens)
        assertNull(decoded.thinkingConfigAccepted)
    }

    // ---- Task 1 v2：新字段解析与 v1 向后兼容 ----

    @Test
    fun v2SummaryParsesWithNewFields() {
        val s = NativeGenerationSummary.parse(sampleJsonV2())
        assertNotNull("v2 摘要应能解析", s)
        assertEquals(2, s!!.version)
        assertEquals(2, s.decodeStepTokens)
        assertEquals(true, s.thinkingConfigAccepted)
        assertEquals(123456L, s.reasoningEndUs)
        assertEquals(234567L, s.firstBodyDeltaUs)
        assertNull(s.errorCode)
    }

    @Test
    fun v2ThinkingConfigFalseDistinctFromNull() {
        // false ≠ null：set_config 被接受但返回 false 与「未知」必须可区分。
        val s = NativeGenerationSummary.parse(sampleJsonV2(thinkingConfigAccepted = "false"))
        assertNotNull(s)
        assertEquals(false, s!!.thinkingConfigAccepted)
        val s2 = NativeGenerationSummary.parse(sampleJsonV2(thinkingConfigAccepted = "null"))
        assertNotNull(s2)
        assertNull(s2!!.thinkingConfigAccepted)
    }

    @Test
    fun v2ErrorCodeParses() {
        val json = sampleJsonV2(errorCode = "PREFILL_EXCEPTION")
            .replace("\"errorStage\":null", "\"errorStage\":\"PREFILL\"")
        val s = NativeGenerationSummary.parse(json)
        assertNotNull(s)
        assertEquals("PREFILL_EXCEPTION", s!!.errorCode)
        assertEquals("PREFILL", s.errorStage)
    }

    @Test
    fun v1SummaryBackfillsV2Defaults() {
        val s = NativeGenerationSummary.parse(sampleJson("EOS"))
        assertNotNull("v1 摘要应仍可解析（向后兼容）", s)
        assertEquals(1, s!!.version)
        assertEquals(1, s.decodeStepTokens)   // v1 缺省 -> 默认 1（等价 v1 逐 token 行为）
        assertNull(s.thinkingConfigAccepted)
        assertNull(s.reasoningEndUs)
        assertNull(s.firstBodyDeltaUs)
        assertNull(s.errorCode)
    }

    @Test
    fun v2RoundTripSerialization() {
        val s = NativeGenerationSummary.parse(sampleJsonV2(reason = "MAX_TOKENS", decodeStepTokens = 3))!!
        val encoded = NativeGenerationSummary.summaryJson.encodeToString(s)
        val decoded = NativeGenerationSummary.parse(encoded)
        assertNotNull(decoded)
        assertEquals("MAX_TOKENS", decoded!!.completionReason)
        assertEquals(3, decoded.decodeStepTokens)
        assertEquals(true, decoded.thinkingConfigAccepted)
        assertEquals(123456L, decoded.reasoningEndUs)
        assertEquals(234567L, decoded.firstBodyDeltaUs)
    }

    // ---- toMetricsArray()：摘要 -> nativeGetMetrics 同构数组 [tps, prefillUs, decodeUs, promptLen, genLen, reuseKv] ----

    @Test
    fun toMetricsArrayShapeAndTpsDerivation() {
        // sampleJson: prompt=120 gen=45 prefillUs=900000 decodeUs=450000 reuseKv=1
        // tps = genLen * 1e6 / decodeUs = 45 * 1e6 / 450000 = 100
        val m = NativeGenerationSummary.parse(sampleJson("EOS"))!!.toMetricsArray()
        assertEquals(6, m.size)
        assertEquals(100f, m[0], 0.001f)
        assertEquals(900000f, m[1], 0.001f)
        assertEquals(450000f, m[2], 0.001f)
        assertEquals(120f, m[3], 0.001f)
        assertEquals(45f, m[4], 0.001f)
        assertEquals(1f, m[5], 0.001f)   // reuseKv 原样透传（nativeGetMetrics 同构，下游按 !=0 判复用）
    }

    @Test
    fun toMetricsArrayZeroDecodeUsYieldsZeroTps() {
        val s = NativeGenerationSummary.parse(
            sampleJson("EOS").replace("\"decodeUs\":450000", "\"decodeUs\":0"),
        )!!
        assertEquals(0f, s.toMetricsArray()[0], 0.001f)
    }
}
