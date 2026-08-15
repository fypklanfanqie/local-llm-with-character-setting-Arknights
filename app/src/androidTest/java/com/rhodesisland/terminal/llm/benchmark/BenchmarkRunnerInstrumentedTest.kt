package com.rhodesisland.terminal.llm.benchmark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhodesisland.terminal.data.local.SettingsStore
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.llm.CpuBoostController
import com.rhodesisland.terminal.llm.backend.BackendManager
import com.chatbyyourside.llm.backend.MnnBridge
import com.rhodesisland.terminal.llm.metrics.BenchmarkSummary
import com.rhodesisland.terminal.provider.local.ModelPathResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 基准运行器与结果存储的真机仪器测试（Task 5 Step 4/5 端到端）。
 *
 * 覆盖：
 * 1. [DataStoreBenchmarkResultStore]：仅冷态结果落盘、按「场景+象限+指纹」键读回（含 M-1 按象限
 *    直取）、旧 JSON 兼容。
 * 2. [DefaultLocalInferenceBenchmarkRunner.run]：性能采样循环冒烟（结构不变量，不比对具体数值）。
 * 3. 热守卫（Task 5 review M-4/M-3）：入口热态拒绝 + 循环内热态样本丢弃（HotFlipRunner 桩化
 *    isThermallyHot，免真实热状态依赖）+ 可靠性轮热态抛异常。
 * 4. [DefaultLocalInferenceBenchmarkRunner.runReliability]：固定轮数逐轮如实记录、失败不重试替换。
 *
 * **Fixture 假设守卫**：无已安装 MNN 模型（config.json + llm.mnn）或 native 不可用时
 * [requireModel] 抛出 Assume 跳过，CI 无模型机器不失败（与 MnnStreamingIntegrationTest 一致）。
 * 运行器用例会真实执行推理，属慢测试——冒烟轮数取小值（性能 0+2、可靠性 3）。
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkRunnerInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ------------------------------------------------------------------
    // 1. DataStoreBenchmarkResultStore
    // ------------------------------------------------------------------

    @Test
    fun dataStoreStore_persistsOnlyCoolResultsAndLoadsBack() = runBlocking {
        val store = DataStoreBenchmarkResultStore(context)
        val device = "test-device-${System.currentTimeMillis()}"
        val cfg = "test-cfg-${System.currentTimeMillis()}"

        // 热态（coolRun=false）结果不落盘
        store.save(result(device, cfg, coolRun = false, quadrant = InferenceBackendQuadrant.CPU_THINKING_OFF))
        assertNull("热态结果不应被持久化", store.load(InferenceBenchmarkScenario.SHORT_TTFT, device, cfg))

        // 冷态结果落盘并可读回（四象限键均可命中）
        val cold = result(device, cfg, coolRun = true, quadrant = InferenceBackendQuadrant.CPU_THINKING_OFF)
        store.save(cold)
        val loaded = store.load(InferenceBenchmarkScenario.SHORT_TTFT, device, cfg)
        assertNotNull("冷态结果未读回", loaded)
        assertEquals("round-trip 不等", cold, loaded)
    }

    @Test
    fun dataStoreStore_sameQuadrantOverwrites_gpuQuadrantKeyIndependent() = runBlocking {
        val store = DataStoreBenchmarkResultStore(context)
        val device = "test-device-${System.currentTimeMillis()}"
        val cfg = "test-cfg-${System.currentTimeMillis()}"

        // 同场景同指纹、不同象限互不覆盖：先存 GPU 后存 CPU，load 首命中按枚举序（CPU 在前）
        val gpu = result(device, cfg, coolRun = true, quadrant = InferenceBackendQuadrant.GPU_THINKING_OFF)
        val cpu = result(device, cfg, coolRun = true, quadrant = InferenceBackendQuadrant.CPU_THINKING_OFF)
        store.save(gpu)
        store.save(cpu)
        val loaded = store.load(InferenceBenchmarkScenario.SHORT_TTFT, device, cfg)
        assertNotNull(loaded)
        assertEquals(InferenceBackendQuadrant.CPU_THINKING_OFF, loaded!!.quadrant)
        // M-1 审查修复：load 带 quadrant 参数直取该象限键，不被「枚举序首命中」（CPU 在前）遮蔽。
        val gpuLoaded = store.load(
            InferenceBenchmarkScenario.SHORT_TTFT, device, cfg,
            InferenceBackendQuadrant.GPU_THINKING_OFF,
        )
        assertNotNull("按 GPU 象限加载未命中", gpuLoaded)
        assertEquals(InferenceBackendQuadrant.GPU_THINKING_OFF, gpuLoaded!!.quadrant)
    }

    // ------------------------------------------------------------------
    // 2. 性能采样循环冒烟
    // ------------------------------------------------------------------

    @Test
    fun runner_run_performanceLoopStructuralInvariants() = runBlocking {
        requireModel()
        val runner = newRunner()
        val result = runner.run(
            scenario = InferenceBenchmarkScenario.SHORT_TTFT,
            configFingerprint = "smoke-cfg",
            deviceFingerprint = "smoke-device",
            warmupRounds = 0, // 冒烟：不跑预热，缩短时长
            recordedRounds = 2,
        )
        assertNotNull(result)
        // M-5 审查修复：原「<= 请求轮数」为恒真断言（代码不可能超发），改为真实口径——
        // 样本级后端计数合计必须与 recordedSampleCount 严格一致（I-2 新增字段的自洽性）。
        assertEquals(
            "actualBackendCounts 合计 != recordedSampleCount（样本级 backend 统计口径不符）",
            result.recordedSampleCount,
            result.actualBackendCounts?.values?.sum() ?: 0,
        )
        assertNotNull(result.summary)
        // 象限与构建维度随结果记录（GPU 象限在无 OpenCL 设备上会自然回退，记录不丢）
        assertNotNull(result.quadrant)
        assertEquals(result.thinkingRequested, result.quadrant!!.thinkingEnabled)
        // 记录轮全失败时汇总字段为 null、discardedReasons 非空——如实反映，不静默
        if (result.recordedSampleCount == 0) {
            assertFalse("零样本却无剔除原因", result.discardedReasons.isEmpty())
            assertNull(result.summary.medianTtftMs)
        } else {
            assertNotNull(result.summary.medianTtftMs)
        }
    }

    @Test
    fun runner_run_coldLoadScenarioReleasesFirst() = runBlocking {
        requireModel()
        val runner = newRunner()
        // COLD_LOAD 要求冷启动：requiresColdStart 触发 release，首轮为真实冷加载。
        // M-5 审查修复：原「<= 1」为恒真断言，改为精确断言——模型可用时 0 预热 + 1 记录轮
        // 必产出 1 个样本（冷加载/生成失败会如实记 NO_RECORD 而非崩溃，但 MnnStreamingIntegrationTest
        // 同款模型可用性守卫下真实生成被预期成功）。
        val result = runner.run(
            scenario = InferenceBenchmarkScenario.COLD_LOAD,
            configFingerprint = "smoke-cfg",
            deviceFingerprint = "smoke-device",
            warmupRounds = 0,
            recordedRounds = 1,
        )
        assertEquals("COLD_LOAD 单记录轮应精确产出 1 个样本", 1, result.recordedSampleCount)
        assertEquals("样本后端计数与样本数不符", 1, result.actualBackendCounts?.values?.sum() ?: 0)
    }

    // ------------------------------------------------------------------
    // 3. 热守卫（Task 5 review M-4 审查修复）
    // ------------------------------------------------------------------

    /**
     * 热守卫桩：isThermallyHot 前 [flipAfterReads] 次返回 false，之后恒 true。
     * 用于验证 run() 的入口拒绝与循环内热态样本丢弃（真实 ThermalMonitor 状态不可控，
     * 故桩化 runner 的 isThermallyHot——类已 open 仅为此）。
     */
    private class HotFlipRunner(
        context: Context,
        backendManager: BackendManager,
        settings: SettingsRepository,
        private val flipAfterReads: Int,
    ) : DefaultLocalInferenceBenchmarkRunner(context, backendManager, settings) {
        private var reads = 0
        override fun isThermallyHot(): Boolean = reads++ >= flipAfterReads
    }

    @Test
    fun runner_run_hotEntryRejectedWithReason() = runBlocking {
        // 入口热态拒绝：热守卫在 resolveModelPath 之前，无需模型/native，任何设备可跑。
        val runner = HotFlipRunner(
            context, BackendManager(context, CpuBoostController(context)),
            SettingsRepository(SettingsStore(context)), flipAfterReads = 0,
        )
        val result = runner.run(
            scenario = InferenceBenchmarkScenario.SHORT_TTFT,
            configFingerprint = "hot-cfg",
            deviceFingerprint = "hot-device",
            warmupRounds = 0,
            recordedRounds = 2,
        )
        assertEquals("热态入口拒绝应零样本", 0, result.recordedSampleCount)
        assertTrue("热态拒绝原因未记录: ${result.discardedReasons}", result.discardedReasons.any { it == "THERMALLY_HOT" })
        assertFalse("热态结果不应标记 coolRun（不得落盘）", result.coolRun)
    }

    @Test
    fun runner_run_hotMidLoopDiscardsSampleAndRecordsReason() = runBlocking {
        requireModel()
        // 入口与首轮读热为 false，第二轮读热为 true：首轮样本照常入样，热轮不采样即 break。
        val runner = HotFlipRunner(
            context, BackendManager(context, CpuBoostController(context)),
            SettingsRepository(SettingsStore(context)), flipAfterReads = 2,
        )
        val result = runner.run(
            scenario = InferenceBenchmarkScenario.SHORT_TTFT,
            configFingerprint = "hot-cfg",
            deviceFingerprint = "hot-device",
            warmupRounds = 0,
            recordedRounds = 2,
        )
        assertEquals("热轮样本不应入样（仅首轮 1 个）", 1, result.recordedSampleCount)
        assertEquals("样本后端计数与样本数不符", 1, result.actualBackendCounts?.values?.sum() ?: 0)
        assertTrue("热态丢弃原因未记录: ${result.discardedReasons}", result.discardedReasons.any { it == "THERMALLY_HOT" })
        assertFalse("含热态丢弃不应标记 coolRun（不得落盘）", result.coolRun)
    }

    @Test
    fun runner_runReliability_hotEntryThrows() = runBlocking {
        // M-3 审查修复：可靠性轮入口热守卫——热态抛 IllegalStateException 而非产出伪有效结果。
        val runner = HotFlipRunner(
            context, BackendManager(context, CpuBoostController(context)),
            SettingsRepository(SettingsStore(context)), flipAfterReads = 0,
        )
        val case = InferenceBenchmarkCase(
            scenario = InferenceBenchmarkScenario.EMPTY_RESPONSE_CHECK,
            quadrant = InferenceBackendQuadrant.CPU_THINKING_OFF,
            modelFingerprint = "smoke-model",
            deviceFingerprint = "smoke-device",
            configHash = "smoke-cfg",
        )
        val ex = try {
            runner.runReliability(case, rounds = 3)
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull("热态可靠性轮应抛 IllegalStateException", ex)
        assertTrue("异常信息不可读: ${ex!!.message}", ex.message!!.contains("过热"))
    }

    // ------------------------------------------------------------------
    // 4. 可靠性样本冒烟
    // ------------------------------------------------------------------

    @Test
    fun runner_runReliability_reportsClassesWithoutRetry() = runBlocking {
        requireModel()
        val runner = newRunner()
        val case = InferenceBenchmarkCase(
            scenario = InferenceBenchmarkScenario.EMPTY_RESPONSE_CHECK,
            quadrant = InferenceBackendQuadrant.CPU_THINKING_OFF,
            modelFingerprint = "smoke-model",
            deviceFingerprint = "smoke-device",
            configHash = "smoke-cfg",
        )
        val reliability = runner.runReliability(case, rounds = 3)
        // 固定轮数、不重试替换：totalRounds 恒等于请求轮数，分类计数和也恒等于轮数
        assertEquals(3, reliability.totalRounds)
        assertEquals("分类计数和 != totalRounds（存在重试替换或丢轮）", 3, reliability.emptyResponseClasses.values.sum())
        // M-5 审查修复：原「fallbackCount >= 0」「rate in 0f..1f」为恒真断言——CPU 象限下
        // allowCpuFallback 恒不生效（仅 quadrant.usesGpu 开启 CPU_BEFORE_FIRST_DELTA 策略），
        // 回退轮次恒 0，改为精确断言；GPU 象限回退计数留 Task 8 真机 GPU 用例。
        // 非空率区间约束已由下方精确交叉核对（== NONE/totalRounds）覆盖，删除冗余范围断言。
        assertEquals("CPU 象限不应有 GPU→CPU 回退计数", 0, reliability.fallbackCount)
        // 非空率 == NONE 轮次 / totalRounds（分母恒为 totalRounds）
        val noneCount = reliability.emptyResponseClasses["NONE"] ?: 0
        assertEquals(
            "非空率口径不符",
            noneCount.toFloat() / 3f,
            reliability.nonEmptySuccessRate,
            0.0001f,
        )
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun newRunner(): DefaultLocalInferenceBenchmarkRunner =
        DefaultLocalInferenceBenchmarkRunner(
            context = context,
            backendManager = BackendManager(context, CpuBoostController(context)),
            settings = SettingsRepository(SettingsStore(context)),
        )

    /** 无模型/native 不可用时 Assume 跳过（与 MnnStreamingIntegrationTest 同款守卫）。 */
    private fun requireModel() {
        assumeTrue(
            "设备上未安装 MNN 模型（config.json + llm.mnn）或 native 不可用，跳过运行器仪器测试",
            modelAvailable(context),
        )
    }

    private fun modelAvailable(context: Context): Boolean {
        if (!MnnBridge.nativeAvailable) return false
        val dirs = ModelPathResolver.getModelsDirectory(context)
            .listFiles { f -> f.isDirectory } ?: return false
        return dirs.any { ModelPathResolver.getConfigPath(context, it.name) != null }
    }

    private fun result(
        deviceFingerprint: String,
        configFingerprint: String,
        coolRun: Boolean,
        quadrant: InferenceBackendQuadrant,
    ): BenchmarkScenarioResult = BenchmarkScenarioResult(
        scenario = InferenceBenchmarkScenario.SHORT_TTFT,
        deviceFingerprint = deviceFingerprint,
        configFingerprint = configFingerprint,
        summary = BenchmarkSummary(
            medianTtftMs = 150f,
            medianDecodeTps = 25f,
            decodeStdDev = 2.0f,
            p95TtftMs = 240f,
            p95DecodeTps = 28f,
            kvReuseRate = 0f,
        ),
        recordedSampleCount = 5,
        warmupSampleCount = 1,
        coolRun = coolRun,
        quadrant = quadrant,
        thinkingRequested = quadrant.thinkingEnabled,
        backendVariant = "CPU_OPTIMIZED",
        nativeBuildId = "test-build",
        mnnCommit = "test-commit",
    )
}
