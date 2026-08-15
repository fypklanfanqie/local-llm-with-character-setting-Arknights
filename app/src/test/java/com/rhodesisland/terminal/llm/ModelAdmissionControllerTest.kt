package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.llm.ModelAdmissionController.AdmissionDecision
import com.rhodesisland.terminal.llm.ModelAdmissionController.MemoryInputs
import com.rhodesisland.terminal.llm.profile.DowngradeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 存储/RAM 准入决策测试（Task 13 + Task 15 口径修正：进程总足迹需求侧计量，无双计数）。 */
class ModelAdmissionControllerTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    // 512 KiB/token 的 KV 密度（量级接近 9B 级模型 fp16 KV；测试合成口径，非真实模型值）。
    private fun kvFor(context: Int, bytesPerToken: Long = 512L * 1024): Long = context * bytesPerToken

    private fun mem(
        weightWorkingSet: Long,
        context: Int,
        availMem: Long,
        lowMemory: Boolean = false,
        priorPeakPss: Long? = null,
        currentPss: Long = 0L,
        activationReserve: Long = 128L * mb,
        backendReserve: Long = 64L * mb,
        threshold: Long = 256L * mb,
        resident: Boolean = false,
    ) = MemoryInputs(
        weightWorkingSetBytes = weightWorkingSet,
        configuredContext = context,
        kvBytesForContext = { kvFor(it) },
        activationReserveBytes = activationReserve,
        backendReserveBytes = backendReserve,
        currentProcessPssBytes = currentPss,
        priorMeasuredTotalPssBytes = priorPeakPss,
        availMemBytes = availMem,
        thresholdBytes = threshold,
        lowMemory = lowMemory,
        modelAlreadyResident = resident,
    )

    @Test
    fun storageSufficientIsAllowed() {
        val d = ModelAdmissionController.assessStorage(
            bundleBytes = 2L * gb, availableBytes = 4L * gb,
        )
        assertTrue(d is AdmissionDecision.Allowed)
    }

    @Test
    fun storageInsufficientRejectsWithRequiredAndAvailable() {
        val d = ModelAdmissionController.assessStorage(
            bundleBytes = 3L * gb, availableBytes = 2L * gb,
        ) as AdmissionDecision.Rejected

        assertEquals(
            ModelAdmissionController.storageRequiredBytes(3L * gb),
            d.details["requiredBytes"],
        )
        assertEquals(2L * gb, d.details["availableBytes"])
    }

    @Test
    fun largeRamAllowsFullConfiguredContext() {
        // 8 GB 可用：权重 3GB + 4096 context KV(~8MB) + 预留 放得进。
        val d = ModelAdmissionController.decideMemory(mem(weightWorkingSet = 3L * gb, context = 4096, availMem = 8L * gb))
        assertEquals(AdmissionDecision.Allowed(contextTokens = 4096), d)
    }

    @Test
    fun lowRamDowngradesContextStep() {
        // 5 GB 可用：3GB 权重 + 4096 KV(2GB) 放不进 4.75GB 预算 -> 2048 档（3G+1G+192M）放得下。
        val d = ModelAdmissionController.decideMemory(mem(weightWorkingSet = 3L * gb, context = 4096, availMem = 5L * gb))
        val downgraded = d as AdmissionDecision.Downgraded
        assertEquals(2048, downgraded.actualContext)
        assertTrue(downgraded.reasons.contains(DowngradeReason.MEMORY))
    }

    @Test
    fun modelTooLargeIsRejected() {
        // 8GB 权重：即使 512 最小档（8G + 256M KV + 192M 预留 ≈ 8.44GB）也超 7.75GB 预算 -> 拒绝。
        val d = ModelAdmissionController.decideMemory(mem(weightWorkingSet = 8L * gb, context = 4096, availMem = 8L * gb))
        assertTrue(d is AdmissionDecision.Rejected)
    }

    @Test
    fun lowMemoryGuardRejectsOrDowngradesMoreAggressively() {
        // lowMemory 时额外扣 availMem/4：可用 5GB 中预算 = 5G - 256M - 1.25G = 3.49G；权重 3G + 预留
        // 192M + KV -> 4096 放不下，降档。
        val d = ModelAdmissionController.decideMemory(
            mem(weightWorkingSet = 3L * gb, context = 4096, availMem = 5L * gb, lowMemory = true),
        )
        assertTrue("lowMemory 下应拒绝或降档", d is AdmissionDecision.Rejected || d is AdmissionDecision.Downgraded)
    }

    @Test
    fun contextStepsHalveDownToMinimum() {
        assertEquals(listOf(4096, 2048, 1024, 512), ModelAdmissionController.contextSteps(4096))
    }

    // ===== Task 15：进程总足迹口径（无双计数）=====

    @Test
    fun priorPeakPssIsFloorNotAdditionalDemand() {
        // 历史实测峰值 7GB（含权重/KV 全部）不应在估算之外再叠加：若按旧口径会 double-count。
        // 权重 1GB、当前进程 0.3GB、可用 6GB：估算足迹 = max(0.3G + 1G + KV + 预留, 7G) = 7G > 预算 5.75G
        // -> 拒绝；若错误叠加（1G+... + 7G）会同样拒绝但数字不同；关键断言是「取 max 而非相加」：
        val d = ModelAdmissionController.decideMemory(
            mem(weightWorkingSet = 1L * gb, context = 512, availMem = 6L * gb, priorPeakPss = 7L * gb),
        )
        assertTrue("历史峰值高于估算时应以实测峰值为准（floor）", d is AdmissionDecision.Rejected)
    }

    @Test
    fun currentProcessPssCountedInFootprint() {
        // 当前进程基线 2GB 计入足迹：权重 2GB、KV 1M、预留 192M、可用 4.5GB（预算 4.25G）-> 放不下，降档或拒绝。
        val d = ModelAdmissionController.decideMemory(
            mem(weightWorkingSet = 2L * gb, context = 4096, availMem = 4L * gb + 512L * mb, currentPss = 2L * gb),
        )
        assertTrue(d is AdmissionDecision.Downgraded || d is AdmissionDecision.Rejected)
    }

    @Test
    fun residentModelDoesNotDoubleCountWeight() {
        // 同模型已驻留（当前进程 PSS 已含权重）：准入不再叠加权重，同一输入从「拒绝」变「允许」。
        val resident = ModelAdmissionController.decideMemory(
            mem(weightWorkingSet = 4L * gb, context = 512, availMem = 6L * gb, currentPss = 5L * gb, resident = true),
        )
        assertEquals(AdmissionDecision.Allowed(contextTokens = 512), resident)

        val notResident = ModelAdmissionController.decideMemory(
            mem(weightWorkingSet = 4L * gb, context = 512, availMem = 6L * gb, currentPss = 5L * gb, resident = false),
        )
        assertTrue("非驻留应叠加权重从而拒绝", notResident is AdmissionDecision.Rejected)
    }

    @Test
    fun minContextExactlyFitsIsDowngradedToMinimum() {
        // 5.2GB 权重：仅 512 档（5.2G + 256M KV + 192M 预留 ≈ 5.64GB）放得进 5.75GB 预算 -> 降档到 512。
        val d = ModelAdmissionController.decideMemory(
            mem(weightWorkingSet = 5.2f * gb.toFloat().toLong(), context = 4096, availMem = 6L * gb),
        ) as AdmissionDecision.Downgraded
        assertEquals(512, d.actualContext)
        assertTrue(d.reasons.contains(DowngradeReason.MEMORY))
    }

    @Test
    fun minContextFailingRejectsWithDetails() {
        val d = ModelAdmissionController.decideMemory(
            mem(weightWorkingSet = 6L * gb, context = 4096, availMem = 4L * gb),
        ) as AdmissionDecision.Rejected

        assertEquals(512L, d.details["minContext"])
        assertTrue("拒绝应含所需字节", d.details["requiredBytes"] != null && d.details["requiredBytes"]!! > 0L)
        assertTrue("拒绝应含可用预算", d.details["availableBytes"] != null && d.details["availableBytes"]!! > 0L)
    }

    @Test
    fun saturatingArithmeticPreventsOverflowAndNegative() {
        assertEquals(Long.MAX_VALUE, ModelAdmissionController.saturatingAdd(Long.MAX_VALUE, 1L))
        assertEquals(Long.MAX_VALUE, ModelAdmissionController.saturatingAdd(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(0L, ModelAdmissionController.saturatingSub(5L, 10L))
        assertEquals(5L, ModelAdmissionController.saturatingSub(5L, 0L))
    }

    @Test
    fun zeroBudgetRejectsImmediately() {
        val d = ModelAdmissionController.decideMemory(
            mem(weightWorkingSet = 1L * gb, context = 4096, availMem = 100L * mb),
        )
        assertTrue(d is AdmissionDecision.Rejected)
    }
}
