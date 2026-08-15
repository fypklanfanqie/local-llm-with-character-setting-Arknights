package com.rhodesisland.terminal.llm.backend

import android.test.mock.MockContext
import com.rhodesisland.terminal.data.model.AutoBackendModelClass
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.llm.CpuBoostController
import com.rhodesisland.terminal.llm.backend.MnnBackend.MnnMode
import com.rhodesisland.terminal.llm.GenerationExecutionControl
import com.rhodesisland.terminal.llm.GenerationSafetyPolicy
import com.rhodesisland.terminal.llm.metrics.CompletionReason
import com.rhodesisland.terminal.llm.metrics.NativeGenerationSummary
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.profile.InferenceProfileResolver
import com.rhodesisland.terminal.llm.profile.OpenClHealthState
import com.rhodesisland.terminal.llm.profile.PowerPolicy
import com.rhodesisland.terminal.llm.profile.ResolvedInferencePlan
import com.rhodesisland.terminal.llm.profile.RuntimeVariant
import com.rhodesisland.terminal.llm.template.ThinkingOutputClassifier
import com.rhodesisland.terminal.llm.thinking.ThinkingPolicyTelemetry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import com.chatbyyourside.llm.backend.MnnBridge

/**
 * BackendManager 健康记录接线测试（Task 3 review M-3：修复 I-1/M-1/M-4 后补 BackendManager 层覆盖）。
 *
 * 方案说明：真实 [MnnBackend] 依赖 native（[MnnBridge]），无法纯 JVM 实例化；BackendManager 本身
 * 依赖 Context（[BackendSelector] 构造）。故按最小侵入改造——BackendManager 新增 [backendFactory]
 * 构造注入点（默认真实 MnnBackend，生产路径零变化），测试注入 fake [InferenceBackend]，以
 * [BackendManager.generate] 的真实调度逻辑驱动 attempt 成功/失败路径。Context 用
 * android.test.mock.MockContext（构造即存引用，不触达框架方法）；单元测试配置
 * `unitTests.isReturnDefaultValues=true`，Log/SystemClock/Build 等桩调用返回默认值不抛异常。
 *
 * [BackendHealthCoordinator] 为 final 类不可 fake，但可注入内存 [BackendHealthRecordStore]
 * （同 [BackendHealthCoordinatorTest] 的 FakeHealthStore 模式）——以「store 中是否出现对应键的
 * 持久记录」作为 markModelOk / afterLoadFailure / afterGenerationFailure 被调用的断言形态，
 * 与生产 DataStore 写入路径同构（键 = keyFor(device, modelFingerprint, backend, variant)）。
 */
class BackendManagerHealthWiringTest {

    private val deviceFp = "device-test-health-wiring"
    private val maxTokens = 2048
    private lateinit var resolver: InferenceProfileResolver
    private lateinit var modelFile: File

    @Before
    fun setUp() {
        val dir = createTempDir()
        resolver = InferenceProfileResolver(dir, dir.absolutePath + "/m/config.json")
        modelFile = File(dir, "model-config.json")
        modelFile.writeText("""{"model":"health-wiring-test"}""")
    }

    // ===== fakes =====

    /** 内存健康存储替身（生产 BackendHealthStore 绑定 DataStore/Context，无法纯 JVM 实例化）。 */
    private class FakeHealthStore : BackendHealthRecordStore {
        val records = mutableMapOf<BackendHealthKey, HealthRecord>()
        /** 模拟 DataStore I/O 失败（M-4：健康记录写入异常不得拖垮推理本身）。 */
        var failWrites = false

        override suspend fun get(key: BackendHealthKey): HealthRecord? = records[key]

        override suspend fun update(key: BackendHealthKey, transform: (HealthRecord?) -> HealthRecord?) {
            if (failWrites) throw IOException("模拟 DataStore 磁盘写入失败")
            val next = transform(records[key])
            if (next != null) records[key] = next else records.remove(key)
        }
    }

    /** fake 推理后端：由场景字段驱动 generate 的四种结局（摘要完成 / control 中断 / 生成异常 / 加载失败）。 */
    private class FakeBackend(
        override val backendType: BackendType,
    ) : InferenceBackend {
        var loadResult = true
        var initializeCalls = 0
        var generateCalls = 0
        /** 模拟 requestStop 提前返回路径（USER_CANCEL/TIMEOUT/THERMAL_STOP）：在 generateStreamMessages
         *  内部置因再正常返回（与真实 MnnBackend 的 stopReason 提前返回语义一致）。 */
        var controlStopReason: CompletionReason? = null
        /** 模拟 native 摘要完成原因（EOS/MAX_TOKENS/POLICY_TRUNCATION）。 */
        var summaryReason: String? = null
        /** 模拟生成异常（真实后端异常路径 -> afterGenerationFailure）。 */
        var generationFailure: RuntimeException? = null
        /** 模拟首个 delta 后失败：先 onProgress 产出 token 再抛异常（禁止透明换后端）。 */
        var tokensBeforeFailure = 0

        override val backendName: String get() = backendType.displayName
        override val isSupported: Boolean get() = true
        override val isModelLoaded: Boolean get() = true
        override val currentModelPath: String? get() = null
        override val lastErrorMessage: String? get() = null

        override suspend fun initialize(
            modelPath: String,
            nativeConfigJson: String,
            loadConfigHash: String,
        ): Boolean {
            initializeCalls++
            return loadResult
        }

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
            return summaryReason?.let { summaryOf(it) }
        }

        override suspend fun stopGeneration() = Unit

        override fun release() = Unit

        override fun getBackendMetrics(): BackendMetrics =
            BackendMetrics(tokensPerSecond = 0f, gpuUtilization = null, memoryUsedMB = 0L, backendName = backendName)
    }

    private fun summaryOf(reason: String): NativeGenerationSummary = NativeGenerationSummary(
        version = NativeGenerationSummary.VERSION,
        completionReason = reason,
        promptTokens = 5,
        generatedTokens = 10,
        prefillUs = 1_000_000L,
        decodeUs = 500_000L,
        reuseKv = 0,
        callbackCount = 5,
        callbackBytes = 50L,
    )

    private fun control(): GenerationExecutionControl = GenerationExecutionControl(
        policy = GenerationSafetyPolicy(maxTokens = maxTokens, stallTimeoutMs = 1_000, wallClockTimeoutMs = 5_000),
        startedElapsedMs = 0L,
    )

    // ===== fixtures =====

    private fun coordinator(store: FakeHealthStore): BackendHealthCoordinator =
        BackendHealthCoordinator(store, deviceFp, clock = { 0L })

    private fun manager(
        coordinator: BackendHealthCoordinator,
        backends: Map<BackendType, FakeBackend>,
    ): BackendManager {
        val context = MockContext()
        return BackendManager(
            context = context,
            cpuBoostController = CpuBoostController(context),
            healthCoordinator = coordinator,
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
        gpu: FakeBackend = FakeBackend(BackendType.MNN_GPU),
        cpu: FakeBackend = FakeBackend(BackendType.MNN_CPU),
    ): Map<BackendType, FakeBackend> = mapOf(
        BackendType.MNN_CPU to cpu,
        BackendType.MNN_GPU to gpu,
        BackendType.MNN_NPU to FakeBackend(BackendType.MNN_NPU),
    )

    /** AUTO + MODEL_OK：OpenCL 入链置首 -> [OPENCL, CPU_OPTIMIZED, CPU_COMPATIBILITY]。 */
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

    /** 显式 CPU -> [CPU_OPTIMIZED, CPU_COMPATIBILITY]（均 MNN_CPU）。 */
    private fun cpuPlan(): ResolvedInferencePlan = resolver.resolve(
        mode = InferencePerformanceMode.BALANCED,
        backendPreference = BackendPreference.MNN_CPU,
        contextTokens = 4096,
        maxOutputTokens = 2048,
        thermalAdmittedThreads = 4,
        lookahead = false,
        temperature = 0.8f,
        topP = 0.9f,
        repeatPenalty = 1.2f,
        openclHealth = OpenClHealthState.UNKNOWN,
        modelClass = AutoBackendModelClass.GPU_ELIGIBLE,
    )

    /** BackendManager.generate 期望健康记录键（与生产 BackendManager 内部计算一致）。 */
    private fun gpuKey(): BackendHealthKey = BackendHealthStore.keyFor(
        deviceFp, modelConfigFingerprint(modelFile.absolutePath), BackendType.MNN_GPU, RuntimeVariant.OPENCL,
    )

    private fun runGenerate(
        manager: BackendManager,
        plan: ResolvedInferencePlan,
        control: GenerationExecutionControl,
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
        )
    }

    // ===== tests =====

    /** a) EOS / MAX_TOKENS / POLICY_TRUNCATION 是「完成」，均应升 MODEL_OK。 */
    @Test
    fun completedReasons_recordModelOk() {
        for (reason in listOf("EOS", "MAX_TOKENS", "POLICY_TRUNCATION")) {
            val store = FakeHealthStore()
            val gpu = FakeBackend(BackendType.MNN_GPU).apply { summaryReason = reason }
            val result = runGenerate(manager(coordinator(store), backends(gpu)), gpuPlan(), control())

            assertEquals("$reason 完成应原样返回", reason, result.completionReason?.name)
            val record = store.records[gpuKey()]
            assertEquals("$reason 完成应记 MODEL_OK", HealthState.MODEL_OK, record?.state)
        }
    }

    /** b) USER_CANCEL / TIMEOUT / THERMAL_STOP 是中断不是完成：不标 OK，也不记任何失败。 */
    @Test
    fun interruptionReasons_doNotRecordAnything() {
        for (reason in listOf(CompletionReason.USER_CANCEL, CompletionReason.TIMEOUT, CompletionReason.THERMAL_STOP)) {
            val store = FakeHealthStore()
            val gpu = FakeBackend(BackendType.MNN_GPU).apply { controlStopReason = reason }
            val result = runGenerate(manager(coordinator(store), backends(gpu)), gpuPlan(), control())

            assertEquals(reason, result.completionReason)
            assertTrue("$reason 是中断（requestStop 提前返回、非完成），不得写任何健康记录", store.records.isEmpty())
        }
    }

    /** c) 生成异常：不标 OK，且 afterGenerationFailure 被调用（GENERATION 类别持久化）。 */
    @Test
    fun generationFailure_recordsFailureAndNotOk() {
        val store = FakeHealthStore()
        val gpu = FakeBackend(BackendType.MNN_GPU).apply {
            tokensBeforeFailure = 1  // 首个 delta 后失败 -> 禁止透明换后端 -> 提前 return
            generationFailure = RuntimeException("模拟 OpenCL 生成异常")
        }
        val result = runGenerate(manager(coordinator(store), backends(gpu)), gpuPlan(), control())

        assertEquals(CompletionReason.BACKEND_FAILURE, result.completionReason)
        val record = store.records[gpuKey()]
        assertEquals(HealthState.COOLDOWN, record?.state)
        assertEquals(HealthFailureClass.GENERATION, record?.failureClass)
    }

    /** d) CPU attempt 成功也不记录（CPU 恒兜底；CPU-only 设备不得每轮白写 CPU 键 MODEL_OK）。 */
    @Test
    fun cpuSuccess_doesNotRecordAnything() {
        val store = FakeHealthStore()
        val cpu = FakeBackend(BackendType.MNN_CPU).apply { summaryReason = "EOS" }
        val result = runGenerate(manager(coordinator(store), backends(cpu = cpu)), cpuPlan(), control())

        assertEquals(BackendType.MNN_CPU, result.usedBackend)
        assertEquals(CompletionReason.EOS, result.completionReason)
        assertTrue("CPU 恒兜底，成功也不得写健康记录", store.records.isEmpty())
    }

    /** e) 加载失败：记 LOAD 类别且推进 CPU 兜底；TIMEOUT/取消不触发失败记录（见 b）。 */
    @Test
    fun loadFailure_recordsLoadCategoryAndFallsBackToCpu() {
        val store = FakeHealthStore()
        val gpu = FakeBackend(BackendType.MNN_GPU).apply { loadResult = false }
        val result = runGenerate(manager(coordinator(store), backends(gpu)), gpuPlan(), control())

        assertEquals(BackendType.MNN_CPU, result.usedBackend)
        assertEquals(CompletionReason.EOS, result.completionReason)
        val record = store.records[gpuKey()]
        assertEquals(HealthState.COOLDOWN, record?.state)
        assertEquals(HealthFailureClass.LOAD, record?.failureClass)
    }

    /** M-4：markModelOk 写失败（DataStore I/O 异常）被吞掉，推理正常返回、不误记 GENERATION 失败。 */
    @Test
    fun healthWriteFailure_doesNotBreakInference() {
        val store = FakeHealthStore().apply { failWrites = true }
        val gpu = FakeBackend(BackendType.MNN_GPU).apply { summaryReason = "EOS" }
        val result = runGenerate(manager(coordinator(store), backends(gpu)), gpuPlan(), control())

        assertEquals(CompletionReason.EOS, result.completionReason)
        assertEquals(BackendType.MNN_GPU, result.usedBackend)
        assertTrue("写失败被吞掉，不得产生任何记录（含误判的 GENERATION 失败）", store.records.isEmpty())
    }

    /** M-4：afterLoadFailure 写失败不得阻断回退链（GPU 加载失败 -> 仍推进 CPU 兜底）。 */
    @Test
    fun loadFailureWriteFailure_stillFallsBackToCpu() {
        val store = FakeHealthStore().apply { failWrites = true }
        val gpu = FakeBackend(BackendType.MNN_GPU).apply { loadResult = false }
        val result = runGenerate(manager(coordinator(store), backends(gpu)), gpuPlan(), control())

        assertEquals(BackendType.MNN_CPU, result.usedBackend)
        assertEquals(CompletionReason.EOS, result.completionReason)
    }
}
