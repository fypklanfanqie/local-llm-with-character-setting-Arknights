package com.rhodesisland.terminal.llm.benchmark

import com.rhodesisland.terminal.llm.backend.BackendType
import com.rhodesisland.terminal.llm.metrics.BenchmarkSummary
import com.rhodesisland.terminal.llm.metrics.CompletionReason
import com.rhodesisland.terminal.llm.metrics.InferenceTurnRecord
import com.rhodesisland.terminal.llm.metrics.mean
import com.rhodesisland.terminal.llm.metrics.median
import com.rhodesisland.terminal.llm.metrics.p95
import com.rhodesisland.terminal.llm.metrics.sampleStandardDeviation
import com.rhodesisland.terminal.llm.metrics.summarize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 基准统计工具测试（Task 2 Step 4）。
 *
 * 使用固定值核对中位数与样本标准差，保证统计口径不变。
 */
class BenchmarkStatisticsTest {

    @Test
    fun median_oddCount_returnsMiddle() {
        assertEquals(2.0f, median(listOf(3f, 1f, 2f))!!, 0.0001f)
    }

    @Test
    fun median_evenCount_returnsAverageOfTwoMiddle() {
        assertEquals(2.5f, median(listOf(1f, 2f, 3f, 4f))!!, 0.0001f)
    }

    @Test
    fun median_unsortedInput_isSortedFirst() {
        assertEquals(3.0f, median(listOf(5f, 1f, 3f, 2f, 4f))!!, 0.0001f)
    }

    @Test
    fun median_empty_returnsNull() {
        assertNull(median(emptyList()))
    }

    @Test
    fun median_single_returnsItself() {
        assertEquals(7.0f, median(listOf(7f))!!, 0.0001f)
    }

    @Test
    fun mean_basic() {
        assertEquals(2.0f, mean(listOf(1f, 2f, 3f))!!, 0.0001f)
    }

    @Test
    fun mean_empty_returnsNull() {
        assertNull(mean(emptyList()))
    }

    @Test
    fun sampleStandardDeviation_classicDataset() {
        // 经典样本：[2,4,4,4,5,5,7,9]，均值=5，方差=32/7≈4.5714，σ≈2.1381
        val vals = listOf(2f, 4f, 4f, 4f, 5f, 5f, 7f, 9f)
        assertEquals(2.1381f, sampleStandardDeviation(vals)!!, 0.001f)
    }

    @Test
    fun sampleStandardDeviation_identicalValues_isZero() {
        assertEquals(0.0f, sampleStandardDeviation(listOf(5f, 5f, 5f))!!, 0.0001f)
    }

    @Test
    fun sampleStandardDeviation_singleValue_returnsNull() {
        assertNull(sampleStandardDeviation(listOf(5f)))
    }

    @Test
    fun sampleStandardDeviation_empty_returnsNull() {
        assertNull(sampleStandardDeviation(emptyList()))
    }

    @Test
    fun summarize_emptyRecords_returnsAllNullDefaults() {
        val s = summarize(emptyList())
        assertNull(s.medianTtftMs)
        assertNull(s.medianDecodeTps)
        assertNull(s.kvReuseRate)
    }

    @Test
    fun summarize_aggregatesMediansAndExtremes() {
        val records = listOf(
            turn(gid = "a", ttft = 100f, decodeTps = 10f, pss = 500L, thermal = 1, kvReuse = true),
            turn(gid = "b", ttft = 200f, decodeTps = 20f, pss = 700L, thermal = 3, kvReuse = false),
            turn(gid = "c", ttft = 300f, decodeTps = 30f, pss = 600L, thermal = 2, kvReuse = true),
        )
        val s: BenchmarkSummary = summarize(records)
        // TTFT 中位数 = 200
        assertEquals(200f, s.medianTtftMs!!, 0.0001f)
        // decode 中位数 = 20
        assertEquals(20f, s.medianDecodeTps!!, 0.0001f)
        // 样本标准差 σ([10,20,30])：均值20，方差=(100+0+100)/2=100，σ=10
        assertEquals(10f, s.decodeStdDev!!, 0.0001f)
        // 峰值 PSS = 700
        assertEquals(700L, s.peakPssMb)
        // 最大热档 = 3
        assertEquals(3, s.maxThermalStatus)
        // KV 复用率 = 2/3
        assertEquals(2f / 3f, s.kvReuseRate!!, 0.0001f)
    }

    @Test
    fun summarize_ignoresZeroOrMissingMetrics() {
        // 零值/缺失字段不应污染中位数（如失败轮次 ttft=0、decodeTps 缺失）
        val records = listOf(
            turn(gid = "ok1", ttft = 120f, decodeTps = 12f),
            turn(gid = "ok2", ttft = 180f, decodeTps = 18f),
            turn(gid = "fail", ttft = 0f, decodeTps = null),
        )
        val s = summarize(records)
        // 只应纳入 120/180 -> 中位数 150
        assertEquals(150f, s.medianTtftMs!!, 0.0001f)
        // 只应纳入 12/18 -> 中位数 15
        assertEquals(15f, s.medianDecodeTps!!, 0.0001f)
    }

    // ------------------------------------------------------------------
    // Task 5：P95 分位数（线性插值口径：pos = 0.95 * (n - 1)）
    // ------------------------------------------------------------------

    @Test
    fun p95_linearInterpolatesBetweenNeighbors() {
        // 1..20：pos = 0.95*19 = 18.05 -> sorted[18]=19 + 0.05*(20-19) = 19.05
        val vals = (1..20).map { it.toFloat() }
        assertEquals(19.05f, p95(vals)!!, 0.0001f)
    }

    @Test
    fun p95_oddCount_interpolatesNearMax() {
        // [1,2,3]：pos = 0.95*2 = 1.9 -> 2 + 0.9*(3-2) = 2.9
        assertEquals(2.9f, p95(listOf(1f, 2f, 3f))!!, 0.0001f)
    }

    @Test
    fun p95_evenCount_twoSamples_weightedTowardMax() {
        // [10,20]：pos = 0.95*1 = 0.95 -> 10 + 0.95*(20-10) = 19.5
        assertEquals(19.5f, p95(listOf(10f, 20f))!!, 0.0001f)
    }

    @Test
    fun p95_unsortedInput_isSortedFirst() {
        assertEquals(19.05f, p95((20 downTo 1).map { it.toFloat() })!!, 0.0001f)
    }

    @Test
    fun p95_single_returnsItself() {
        assertEquals(7f, p95(listOf(7f))!!, 0.0001f)
    }

    @Test
    fun p95_empty_returnsNull() {
        assertNull(p95(emptyList()))
    }

    @Test
    fun summarize_fillsP95Fields() {
        val records = listOf(
            turn(gid = "a", ttft = 100f, decodeTps = 10f),
            turn(gid = "b", ttft = 200f, decodeTps = 20f),
            turn(gid = "c", ttft = 300f, decodeTps = 30f),
        )
        val s: BenchmarkSummary = summarize(records)
        // ttft p95：pos = 0.95*2 = 1.9 -> 200 + 0.9*(300-200) = 290
        assertEquals(290f, s.p95TtftMs!!, 0.0001f)
        // decode p95：同型 -> 20 + 0.9*(30-20) = 29
        assertEquals(29f, s.p95DecodeTps!!, 0.0001f)
    }

    @Test
    fun summarize_p95AppliesPositiveFilter() {
        // 零值/缺失轮次（ttft=0、decodeTps=null）同样不得污染 P95
        val records = listOf(
            turn(gid = "ok1", ttft = 120f, decodeTps = 12f),
            turn(gid = "ok2", ttft = 180f, decodeTps = 18f),
            turn(gid = "fail", ttft = 0f, decodeTps = null),
        )
        val s = summarize(records)
        // 仅 120/180 纳入：pos = 0.95*1 = 0.95 -> 120 + 0.95*(180-120) = 177
        assertEquals(177f, s.p95TtftMs!!, 0.0001f)
        // 仅 12/18 纳入：12 + 0.95*6 = 17.7
        assertEquals(17.7f, s.p95DecodeTps!!, 0.0001f)
    }

    @Test
    fun summarize_emptyRecords_p95FieldsNull() {
        val s = summarize(emptyList())
        assertNull(s.p95TtftMs)
        assertNull(s.p95DecodeTps)
    }

    private fun turn(
        gid: String,
        ttft: Float? = null,
        decodeTps: Float? = null,
        pss: Long? = null,
        thermal: Int? = null,
        kvReuse: Boolean? = null,
    ): InferenceTurnRecord = InferenceTurnRecord(
        generationId = gid,
        requestedMode = null,
        effectiveMode = null,
        backend = BackendType.MNN_CPU,
        startedElapsedMs = 0L,
        endedElapsedMs = 0L,
        ttftMs = ttft?.toLong(),
        decodeTps = decodeTps,
        peakPssMb = pss,
        thermalMax = thermal,
        kvReuse = kvReuse,
        completionReason = CompletionReason.EOS,
    )
}
