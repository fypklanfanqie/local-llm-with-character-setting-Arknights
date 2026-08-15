package com.rhodesisland.terminal.llm

import android.app.ActivityManager
import com.rhodesisland.terminal.llm.ModelAdmissionController.AdmissionDecision
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 每轮内存准入协调器测试（Task 15）：注入 fake 内存源与 PSS 源，验证全量/降档/拒绝/峰值校准。 */
class LocalInferenceAdmissionCoordinatorTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    private fun memInfo(
        availMem: Long,
        threshold: Long = 256L * mb,
        lowMemory: Boolean = false,
    ): ActivityManager.MemoryInfo = ActivityManager.MemoryInfo().apply {
        this.availMem = availMem
        this.threshold = threshold
        this.lowMemory = lowMemory
    }

    private fun coordinator(
        availMem: Long,
        lowMemory: Boolean = false,
        pss: Long? = 0L,
    ) = LocalInferenceAdmissionCoordinator(
        memoryInfoProvider = { memInfo(availMem, lowMemory = lowMemory) },
        pssProvider = { pss },
    )

    private suspend fun LocalInferenceAdmissionCoordinator.admit(
        modelId: String = "m",
        context: Int,
        weight: Long,
        kvDensityPerToken: Long = 512L * 1024,
        resident: Boolean = false,
    ): AdmissionDecision = admit(
        modelId = modelId,
        configuredContext = context,
        weightWorkingSetBytes = weight,
        kvBytesForContext = { it * kvDensityPerToken },
        modelAlreadyResident = resident,
    )

    @Test
    fun enoughMemoryKeepsConfiguredContext() = runTest {
        val d = coordinator(availMem = 8L * gb).admit(context = 4096, weight = 3L * gb)
        assertEquals(AdmissionDecision.Allowed(contextTokens = 4096), d)
    }

    @Test
    fun insufficientMemoryDowngradesContextOnly() = runTest {
        val d = coordinator(availMem = 5L * gb).admit(context = 4096, weight = 3L * gb)
        val downgraded = d as AdmissionDecision.Downgraded
        // budget = 5G - 256M = 4.75G；4096 档 = 3G + 2G + 预留 > 4.75G -> 2048。
        assertEquals(2048, downgraded.actualContext)
    }

    @Test
    fun rejectedWhenEvenMinimumDoesNotFit() = runTest {
        val d = coordinator(availMem = 4L * gb).admit(context = 4096, weight = 6L * gb)
        assertTrue(d is AdmissionDecision.Rejected)
    }

    @Test
    fun lowMemoryIsMoreConservative() = runTest {
        val weight = 27L * gb / 10  // 2.7GB：normal 下 2048 档放得下，lowMemory 下需降到 512。
        val normal = coordinator(availMem = 5L * gb).admit(context = 4096, weight = weight)
        val tight = coordinator(availMem = 5L * gb, lowMemory = true).admit(context = 4096, weight = weight)
        // normal：budget 4.75G，2048 档（2.7G + 1G + 预留 0.5G = 4.2G）放得下；
        // lowMemory：额外扣 1.25G -> budget 3.5G，仅 512 档（2.7G + 0.25G + 0.5G = 3.45G）放得下。
        val tightStep = (tight as AdmissionDecision.Downgraded).actualContext
        val normalStep = (normal as AdmissionDecision.Downgraded).actualContext
        assertTrue("lowMemory 应降档更狠", tightStep < normalStep)
    }

    @Test
    fun recordedPeakPssCalibratesSubsequentAdmission() = runTest {
        val c = coordinator(availMem = 6L * gb)
        c.recordPeakPss("m", 7L * gb)
        // 估算足迹（1G + 预留）远低于 7G 实测峰值：以实测为下限 -> 拒绝（保守校准）。
        val d = c.admit(modelId = "m", context = 512, weight = 1L * gb)
        assertTrue(d is AdmissionDecision.Rejected)
    }

    @Test
    fun invalidPeakPssIsIgnored() {
        val c = coordinator(availMem = 6L * gb)
        c.recordPeakPss("m", null)
        c.recordPeakPss("m", -1L)
        // 无有效记录：估算足迹 1G 放得进 5.75G 预算 -> Allowed。
        runTest {
            val d = c.admit(modelId = "m", context = 512, weight = 1L * gb)
            assertEquals(AdmissionDecision.Allowed(contextTokens = 512), d)
        }
    }

    @Test
    fun openclReserveIsLargerThanCpu() {
        assertTrue(
            LocalInferenceAdmissionCoordinator.OPENCL_BACKEND_RESERVE_BYTES >
                LocalInferenceAdmissionCoordinator.CPU_BACKEND_RESERVE_BYTES,
        )
    }

    @Test
    fun residentModelSkipsWeightInCoordinator() = runTest {
        // 同模型已驻留（当前 PSS 已含权重）：协调器透传 modelAlreadyResident，权重不再叠加，
        // 同一输入从「拒绝」变「允许」。
        val resident = coordinator(availMem = 6L * gb, pss = 4L * gb)
            .admit(context = 512, weight = 4L * gb, resident = true)
        assertEquals(AdmissionDecision.Allowed(contextTokens = 512), resident)

        val notResident = coordinator(availMem = 6L * gb, pss = 4L * gb)
            .admit(context = 512, weight = 4L * gb)
        assertTrue("非驻留应叠加权重从而拒绝", notResident is AdmissionDecision.Rejected)
    }
}
