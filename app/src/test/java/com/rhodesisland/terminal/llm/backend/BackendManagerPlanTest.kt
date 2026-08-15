package com.rhodesisland.terminal.llm.backend

import android.test.mock.MockContext
import com.rhodesisland.terminal.data.model.AutoBackendModelClass
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.llm.CpuBoostController
import com.rhodesisland.terminal.llm.GenerationExecutionControl
import com.rhodesisland.terminal.llm.GenerationSafetyPolicy
import com.rhodesisland.terminal.llm.backend.MnnBackend.MnnMode
import com.rhodesisland.terminal.llm.metrics.CompletionReason
import com.rhodesisland.terminal.llm.metrics.NativeGenerationSummary
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.profile.InferenceProfileResolver
import com.rhodesisland.terminal.llm.profile.OpenClHealthState
import com.rhodesisland.terminal.llm.profile.PowerPolicy
import com.rhodesisland.terminal.llm.profile.ResolvedInferencePlan
import com.rhodesisland.terminal.llm.profile.RuntimeVariant
import com.rhodesisland.terminal.llm.template.ThinkingOutputClassifier
import com.rhodesisland.terminal.llm.template.ThinkingTemplateCapability
import com.rhodesisland.terminal.llm.thinking.ThinkingPolicyTelemetry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * BackendManager 执行语义测试（Task 7 Step 4）。
 *
 * BackendManager 依赖 Android Context（BackendSelector/MnnBackend），无法纯 JVM 实例化；
 * 本测试锁定其执行**输入**（resolvedPlan.attempts 的顺序/内容）与跨尝试执行语义
 * （CPU 优化失败推进兼容、首 delta 后不再换后端），这些由 [GenerationExecutionControl] 承载。
 */
class BackendManagerPlanTest {

    private lateinit var resolver: InferenceProfileResolver

    @Before
    fun setUp() {
        val dir = createTempDir()
        resolver = InferenceProfileResolver(dir, dir.absolutePath + "/m/config.json")
    }

    private fun plan(
        preference: BackendPreference,
        mode: InferencePerformanceMode = InferencePerformanceMode.BALANCED,
        openclHealth: OpenClHealthState = OpenClHealthState.UNKNOWN,
    ): ResolvedInferencePlan = resolver.resolve(
        mode = mode,
        backendPreference = preference,
        contextTokens = 4096,
        maxOutputTokens = 2048,
        thermalAdmittedThreads = 4,
        lookahead = false,
        temperature = 0.8f,
        topP = 0.9f,
        repeatPenalty = 1.2f,
        openclHealth = openclHealth,
        modelClass = AutoBackendModelClass.GPU_ELIGIBLE,
    )

    @Test
    fun cpuExecutionSequenceIsOptimizedThenCompatibility() {
        val p = plan(BackendPreference.MNN_CPU)

        val sequence = p.attempts.map { it.variant }
        assertEquals(listOf(RuntimeVariant.CPU_OPTIMIZED, RuntimeVariant.CPU_COMPATIBILITY), sequence)
        // CPU 优化失败推进 CPU 兼容：BackendManager 不黑名单 CPU，两变体都在链中。
        assertTrue(p.attempts.all { it.backend == BackendType.MNN_CPU })
    }

    @Test
    fun healthyOpenclAttemptLeadsExecutionAndRequiresProbe() {
        val p = plan(BackendPreference.AUTO, openclHealth = OpenClHealthState.MODEL_OK)

        assertEquals(RuntimeVariant.OPENCL, p.attempts.first().variant)
        assertTrue(p.attempts.first().requiresProbe)
        assertFalse(p.attempts.first { it.variant == RuntimeVariant.CPU_OPTIMIZED }.requiresProbe)
    }

    @Test
    fun autoPlanNeverContainsQnn() {
        val p = plan(BackendPreference.AUTO)
        val npu = plan(BackendPreference.MNN_NPU)

        assertFalse(p.attempts.any { it.backend == BackendType.MNN_NPU })
        assertFalse(npu.attempts.any { it.backend == BackendType.MNN_NPU })
    }

    @Test
    fun firstDeltaDisablesTransparentFallbackAcrossAttempts() {
        // 模拟 BackendManager 执行序列：CPU_OPTIMIZED 产出 token 后失败。
        // 请求级 control 累计 token -> 不再允许下一尝试，返回 typed 终止原因。
        val control = GenerationExecutionControl(
            policy = GenerationSafetyPolicy(maxTokens = 2048, stallTimeoutMs = 1_000, wallClockTimeoutMs = 5_000),
            startedElapsedMs = 100,
        )
        control.onProgress("cpu-opt", generatedTokens = 50, progressElapsedMs = 200)
        // 失败发生在已有可见输出之后：首个 delta 后禁止透明换后端。
        control.requestStop(CompletionReason.BACKEND_FAILURE)

        assertFalse(control.canTryNextBackend())
        assertEquals(CompletionReason.BACKEND_FAILURE, control.reason())
        // 未产出任何 token 的失败（remainingTokens 未降）仍允许推进到下一尝试。
        val noOutput = GenerationExecutionControl(
            policy = GenerationSafetyPolicy(maxTokens = 2048, stallTimeoutMs = 1_000, wallClockTimeoutMs = 5_000),
            startedElapsedMs = 100,
        )
        assertTrue(noOutput.canTryNextBackend())
    }
}

/**
 * BackendManager 输出策略回退测试（Task 4）：GPU 首 delta 前空输出回退 CPU 的调度路径覆盖。
 *
 * 方案说明：沿用 [BackendManagerHealthWiringTest] 的 fake 模式（backendFactory 注入 fake
 * [InferenceBackend]，Context 用 [MockContext]）。fake 在 generateStreamMessages 内模拟 MnnBackend
 * 的 finally 契约——调一次 [ThinkingOutputClassifier.finish]（回退判定消费其
 * [ThinkingOutputClassifier.lastEmptyResponseClass]），与真实 MnnBackend 行为同构。
 *
 * 场景（裁决 3 测试清单）：
 * a) GPU 空 summary（EOS, 0 token, 0 byte）+ 回退策略 -> 推进 CPU attempt 并成功；
 * b) 同上但策略 DISABLED -> 直接返回 GPU 空结果（含既有 MODEL_OK 健康记录）；
 * c) GPU 有输出后异常 -> 不回退（既有 requestStop(BACKEND_FAILURE) 机制）；
 * d) 取消（executionControl reason）-> 不回退；
 * e) CPU attempt 不存在/失败时 GPU 空结果原样返回（链末端兜底）。
 */
class BackendManagerOutputPolicyFallbackTest {

    private val maxTokens = 2048
    private lateinit var resolver: InferenceProfileResolver
    private lateinit var modelFile: File

    @Before
    fun setUp() {
        val dir = createTempDir()
        resolver = InferenceProfileResolver(dir, dir.absolutePath + "/m/config.json")
        modelFile = File(dir, "model-config.json")
        modelFile.writeText("""{"model":"plan-fallback-test"}""")
    }

    // ===== fakes =====

    /** 内存健康存储替身（同 [BackendManagerHealthWiringTest] 的 FakeHealthStore 模式）。 */
    private class FakeHealthStore : BackendHealthRecordStore {
        val records = mutableMapOf<BackendHealthKey, HealthRecord>()

        override suspend fun get(key: BackendHealthKey): HealthRecord? = records[key]

        override suspend fun update(key: BackendHealthKey, transform: (HealthRecord?) -> HealthRecord?) {
            val next = transform(records[key])
            if (next != null) records[key] = next else records.remove(key)
        }
    }

    /** fake 推理后端（Task 4 版）：在 HealthWiringTest fake 基础上增加空摘要 / 分类器收口 / release 追踪。 */
    private class PlanFakeBackend(
        override val backendType: BackendType,
    ) : InferenceBackend {
        var loadResult = true
        var generateCalls = 0
        /** 模拟 requestStop 提前返回路径（USER_CANCEL/TIMEOUT/THERMAL_STOP）。 */
        var controlStopReason: CompletionReason? = null
        /** 模拟 native 摘要完成原因（null = 不返回摘要）。 */
        var summaryReason: String? = null
        /** 空摘要的 token/字节数（默认非空：10 token / 50 bytes）。 */
        var summaryTokens = 10
        var summaryBytes = 50L
        /** 模拟生成异常。 */
        var generationFailure: RuntimeException? = null
        /** 首个 delta 后失败：先 onProgress 产出 token 再抛异常（禁止透明换后端）。 */
        var tokensBeforeFailure = 0
        /** release() 是否被调用（无双驻留断言：回退推进 CPU 前 GPU 须已释放）。 */
        var released = false

        override val backendName: String get() = backendType.displayName
        override val isSupported: Boolean get() = true
        override val isModelLoaded: Boolean get() = !released
        override val currentModelPath: String? get() = null
        override val lastErrorMessage: String? get() = null

        override suspend fun initialize(
            modelPath: String,
            nativeConfigJson: String,
            loadConfigHash: String,
        ): Boolean = loadResult

        override suspend fun generateStreamMessages(
            messages: List<ChatMessage>,
            maxTokens: Int,
            temperature: Float,
            topP: Float,
            repeatPenalty: Float,
            enableThinking: Boolean,
            onToken: (String) -> Boolean,
            batchMaxBytes: Int,
            batchMaxMs: Int,
            downgradeReasons: List<String>,
            executionControl: GenerationExecutionControl?,
            powerPolicy: PowerPolicy,
            requestedMode: InferencePerformanceMode?,
            effectiveMode: InferencePerformanceMode?,
            loadConfigHash: String?,
            attemptTrace: List<String>,
            coldLoadMs: Long?,
            warmLoadMs: Long?,
            decodeStepTokens: Int,
            thinkingRequested: Boolean?,
            templateCapability: String?,
            thinkingClassifier: ThinkingOutputClassifier?,
            thinkingPolicy: ThinkingPolicyTelemetry?,
            configuredContextTokens: Int?,
            actualContextTokens: Int?,
        ): NativeGenerationSummary? {
            generateCalls++
            if (tokensBeforeFailure > 0) {
                executionControl?.onProgress("fake-${backendType.name}", tokensBeforeFailure, progressElapsedMs = 100L)
            }
            controlStopReason?.let { executionControl?.requestStop(it) }
            generationFailure?.let { throw it }
            val summary = summaryReason?.let { summaryOf(it, summaryTokens, summaryBytes) }
            // 模拟 MnnBackend finally 契约：分类器收口一次（回退判定消费 lastEmptyResponseClass）。
            // 简化：仅正常返回路径收口（异常路径的分类与回退判定无关，catch 分支不消费）。
            thinkingClassifier?.finish(
                completionReason = controlStopReason
                    ?: summary?.completionReason?.let(CompletionReason::valueOf)
                    ?: CompletionReason.EOS,
                generatedTokens = summary?.generatedTokens ?: 0,
            )
            return summary
        }

        override suspend fun stopGeneration() = Unit

        override fun release() {
            released = true
        }

        override fun getBackendMetrics(): BackendMetrics =
            BackendMetrics(tokensPerSecond = 0f, gpuUtilization = null, memoryUsedMB = 0L, backendName = backendName)
    }

    private fun summaryOf(
        reason: String,
        generatedTokens: Int = 10,
        callbackBytes: Long = 50L,
    ): NativeGenerationSummary = NativeGenerationSummary(
        version = NativeGenerationSummary.VERSION,
        completionReason = reason,
        promptTokens = 5,
        generatedTokens = generatedTokens,
        prefillUs = 1_000_000L,
        decodeUs = 500_000L,
        reuseKv = 0,
        callbackCount = if (generatedTokens > 0) 5 else 0,
        callbackBytes = callbackBytes,
    )

    private fun control(): GenerationExecutionControl = GenerationExecutionControl(
        policy = GenerationSafetyPolicy(maxTokens = maxTokens, stallTimeoutMs = 1_000, wallClockTimeoutMs = 5_000),
        startedElapsedMs = 0L,
    )

    // ===== fixtures =====

    private fun manager(
        store: FakeHealthStore,
        backends: Map<BackendType, PlanFakeBackend>,
    ): BackendManager {
        val context = MockContext()
        return BackendManager(
            context = context,
            cpuBoostController = CpuBoostController(context),
            healthCoordinator = BackendHealthCoordinator(store, "device-fallback-test", clock = { 0L }),
            backendFactory = { mode ->
                when (mode) {
                    MnnMode.CPU -> backends.getValue(BackendType.MNN_CPU)
                    MnnMode.GPU_OPENCL -> backends.getValue(BackendType.MNN_GPU)
                    MnnMode.NPU_QNN -> backends.getValue(BackendType.MNN_NPU)
                }
            },
        )
    }

    private fun backends(
        gpu: PlanFakeBackend = PlanFakeBackend(BackendType.MNN_GPU),
        cpu: PlanFakeBackend = PlanFakeBackend(BackendType.MNN_CPU),
    ): Map<BackendType, PlanFakeBackend> = mapOf(
        BackendType.MNN_CPU to cpu,
        BackendType.MNN_GPU to gpu,
        BackendType.MNN_NPU to PlanFakeBackend(BackendType.MNN_NPU),
    )

    /** AUTO + MODEL_OK -> [OPENCL, CPU_OPTIMIZED, CPU_COMPATIBILITY]。 */
    private fun gpuPlan(): ResolvedInferencePlan = resolver.resolve(
        mode = InferencePerformanceMode.BALANCED,
        backendPreference = BackendPreference.AUTO,
        contextTokens = 4096,
        maxOutputTokens = 2048,
        thermalAdmittedThreads = 4,
        lookahead = false,
        temperature = 0.8f,
        topP = 0.9f,
        repeatPenalty = 1.2f,
        openclHealth = OpenClHealthState.MODEL_OK,
        modelClass = AutoBackendModelClass.GPU_ELIGIBLE,
    )

    /** 仅含 GPU attempt 的计划（链末端兜底场景：CPU attempt 不存在）。 */
    private fun gpuOnlyPlan(): ResolvedInferencePlan {
        val gpuAttempt = gpuPlan().attempts.first { it.backend == BackendType.MNN_GPU }
        return gpuPlan().copy(attempts = listOf(gpuAttempt))
    }

    private val fallbackPolicy = GenerationOutputPolicy(EmptyOutputFallbackPolicy.CPU_BEFORE_FIRST_DELTA)

    /** BackendManager.generate 期望健康记录键（与生产计算一致）。 */
    private fun gpuKey(): BackendHealthKey = BackendHealthStore.keyFor(
        "device-fallback-test", modelConfigFingerprint(modelFile.absolutePath), BackendType.MNN_GPU, RuntimeVariant.OPENCL,
    )

    private fun runGenerate(
        manager: BackendManager,
        plan: ResolvedInferencePlan,
        control: GenerationExecutionControl,
        outputPolicy: GenerationOutputPolicy = fallbackPolicy,
        thinkingRequested: Boolean = false,
    ): BackendManager.GenerationResult = runBlocking {
        manager.generate(
            modelPath = modelFile.absolutePath,
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            maxTokens = plan.maxOutputTokens,
            temperature = 0.8f,
            topP = 0.9f,
            repeatPenalty = 1.2f,
            enableThinking = false,
            onToken = { true },
            executionControl = control,
            resolvedPlan = plan,
            // 与生产调用同构：分类器 + 信封参数（fake 在其 generate 内收口分类）。
            thinkingRequested = thinkingRequested,
            templateCapability = ThinkingTemplateCapability.SUPPORTED.name,
            thinkingClassifier = ThinkingOutputClassifier(thinkingRequested, ThinkingTemplateCapability.SUPPORTED),
            outputPolicy = outputPolicy,
        )
    }

    // ===== tests =====

    /** a) GPU 空 summary（EOS, 0 token, 0 byte）+ 回退策略 -> 推进 CPU attempt 并成功（CPU EOS 有输出）。 */
    @Test
    fun emptyGpuSummary_fallsBackToCpuAndSucceeds() {
        val store = FakeHealthStore()
        val gpu = PlanFakeBackend(BackendType.MNN_GPU).apply {
            summaryReason = "EOS"
            summaryTokens = 0
            summaryBytes = 0L
        }
        val cpu = PlanFakeBackend(BackendType.MNN_CPU).apply { summaryReason = "EOS" }
        val result = runGenerate(manager(store, backends(gpu, cpu)), gpuPlan(), control())

        assertEquals("应推进到 CPU attempt", BackendType.MNN_CPU, result.usedBackend)
        assertEquals(CompletionReason.EOS, result.completionReason)
        assertEquals("GPU 应只尝试一次", 1, gpu.generateCalls)
        assertEquals("CPU 应成功尝试一次", 1, cpu.generateCalls)
        assertTrue("回退推进 CPU 前 GPU 应已释放（无双驻留）", gpu.released)
        // 健康统计零副作用：被丢弃的 GPU 空结果不写 MODEL_OK（空输出不足以证明 GPU 可用），
        // CPU 恒兜底不记录——「回退不 corrupt health statistics」的直接断言。
        assertTrue("空输出回退不应产生任何健康记录", store.records.isEmpty())
    }

    /** b) GPU 空 summary 但策略 DISABLED -> 直接返回 GPU 空结果（CPU 不被尝试，健康记录走旧语义）。 */
    @Test
    fun emptyGpuSummary_disabledPolicy_returnsGpuResult() {
        val store = FakeHealthStore()
        val gpu = PlanFakeBackend(BackendType.MNN_GPU).apply {
            summaryReason = "EOS"
            summaryTokens = 0
            summaryBytes = 0L
        }
        val cpu = PlanFakeBackend(BackendType.MNN_CPU).apply { summaryReason = "EOS" }
        val result = runGenerate(
            manager(store, backends(gpu, cpu)),
            gpuPlan(),
            control(),
            outputPolicy = GenerationOutputPolicy(EmptyOutputFallbackPolicy.DISABLED),
        )

        assertEquals("DISABLED 应原样返回 GPU 空结果", BackendType.MNN_GPU, result.usedBackend)
        assertEquals(CompletionReason.EOS, result.completionReason)
        assertEquals("CPU 不应被尝试", 0, cpu.generateCalls)
        assertFalse("GPU 应保持驻留（未触发回退）", gpu.released)
        // DISABLED = 旧行为：EOS 空结果仍按「完成一次非错误生成」升 MODEL_OK（Task 3 语义不变）。
        assertEquals("DISABLED 下空结果仍记 MODEL_OK（旧语义）", HealthState.MODEL_OK, store.records[gpuKey()]?.state)
    }

    /** c) GPU 有输出后异常 -> 不回退（既有 requestStop(BACKEND_FAILURE) + canTryNextBackend==false 机制）。 */
    @Test
    fun gpuFailureAfterDelta_doesNotFallBack() {
        val gpu = PlanFakeBackend(BackendType.MNN_GPU).apply {
            tokensBeforeFailure = 1
            generationFailure = RuntimeException("模拟 OpenCL 生成异常")
        }
        val cpu = PlanFakeBackend(BackendType.MNN_CPU).apply { summaryReason = "EOS" }
        val result = runGenerate(manager(FakeHealthStore(), backends(gpu, cpu)), gpuPlan(), control())

        assertEquals("首 delta 后失败应返回部分失败（不透明切换）", CompletionReason.BACKEND_FAILURE, result.completionReason)
        assertEquals(BackendType.MNN_GPU, result.usedBackend)
        assertEquals("CPU 不应被尝试", 0, cpu.generateCalls)
    }

    /** d) 取消（executionControl reason 已置）-> 不回退，原样返回取消原因。 */
    @Test
    fun cancel_doesNotFallBack() {
        val gpu = PlanFakeBackend(BackendType.MNN_GPU).apply {
            summaryReason = "EOS"
            summaryTokens = 0
            summaryBytes = 0L
            controlStopReason = CompletionReason.USER_CANCEL
        }
        val cpu = PlanFakeBackend(BackendType.MNN_CPU).apply { summaryReason = "EOS" }
        val result = runGenerate(manager(FakeHealthStore(), backends(gpu, cpu)), gpuPlan(), control())

        assertEquals("取消应原样返回 USER_CANCEL", CompletionReason.USER_CANCEL, result.completionReason)
        assertEquals(BackendType.MNN_GPU, result.usedBackend)
        assertEquals("取消后 CPU 不应被尝试", 0, cpu.generateCalls)
    }

    /** e1) CPU attempt 不存在（计划仅含 GPU）-> GPU 空结果原样返回，不误报「所有后端尝试均失败」。 */
    @Test
    fun emptyGpuSummary_gpuOnlyPlan_returnsGpuResult() {
        val gpu = PlanFakeBackend(BackendType.MNN_GPU).apply {
            summaryReason = "EOS"
            summaryTokens = 0
            summaryBytes = 0L
        }
        val cpu = PlanFakeBackend(BackendType.MNN_CPU).apply { summaryReason = "EOS" }
        val result = runGenerate(manager(FakeHealthStore(), backends(gpu, cpu)), gpuOnlyPlan(), control())

        assertEquals("链末端应原样返回 GPU 空结果", BackendType.MNN_GPU, result.usedBackend)
        assertEquals(CompletionReason.EOS, result.completionReason)
        assertEquals("GPU 应只尝试一次", 1, gpu.generateCalls)
        assertEquals("CPU 不在计划内，不应被尝试", 0, cpu.generateCalls)
    }

    /** e2) CPU attempt 加载失败（后续 attempt 全失败）-> GPU 空结果原样返回（链末端兜底）。 */
    @Test
    fun emptyGpuSummary_cpuLoadFailure_returnsGpuResult() {
        val gpu = PlanFakeBackend(BackendType.MNN_GPU).apply {
            summaryReason = "EOS"
            summaryTokens = 0
            summaryBytes = 0L
        }
        val cpu = PlanFakeBackend(BackendType.MNN_CPU).apply { loadResult = false }
        val result = runGenerate(manager(FakeHealthStore(), backends(gpu, cpu)), gpuPlan(), control())

        assertEquals("CPU 加载失败后应返回最后一次可回退的 GPU 空结果", BackendType.MNN_GPU, result.usedBackend)
        assertEquals(CompletionReason.EOS, result.completionReason)
        assertEquals("GPU 应只尝试一次", 1, gpu.generateCalls)
    }
}
