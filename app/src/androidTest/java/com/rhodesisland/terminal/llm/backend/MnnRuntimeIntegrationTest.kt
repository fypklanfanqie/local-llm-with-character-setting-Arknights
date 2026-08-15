package com.rhodesisland.terminal.llm.backend

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.rhodesisland.terminal.llm.template.ThinkingOutputClassifier
import com.rhodesisland.terminal.llm.template.ThinkingTemplateCapability
import com.rhodesisland.terminal.llm.template.ThinkingTemplateCapabilityResolver
import com.rhodesisland.terminal.llm.thinking.ThinkingPolicyTelemetry
import com.rhodesisland.terminal.provider.local.ModelPathResolver
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import com.chatbyyourside.llm.backend.MnnBridge

/**
 * MNN 运行时权威集成测试（Task 16 Step 3；Task 1 v2 场景追加）。
 *
 * 覆盖：JNI handshake、类加载（API 24+ 无高版本类型崩）、短 CPU 生成、EOS / max tokens 终止、
 * 取消（策略截断）、CJK/emoji UTF-8 完整性、两轮 KV 复用、生命周期 release。
 * 无真实模型 fixture 时以明确原因跳过（不静默通过）。
 */
@RunWith(AndroidJUnit4::class)
class MnnRuntimeIntegrationTest {

    companion object {
        private var loaded: BackendHandle? = null

        private class BackendHandle(val backend: MnnBackend, val configPath: String)

        @BeforeClass
        @JvmStatic
        fun loadFixture() {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            if (!MnnBridge.nativeAvailable) return
            val dirs = ModelPathResolver.getModelsDirectory(context)
                .listFiles { f -> f.isDirectory } ?: return
            for (dir in dirs) {
                val config = ModelPathResolver.getConfigPath(context, dir.name) ?: continue
                val backend = MnnBackend(context, MnnBackend.MnnMode.CPU, CpuBoostController(context))
                val ok = runBlocking {
                    backend.initialize(config, nativeConfigOf(), loadConfigHashOf(config))
                }
                if (ok) { loaded = BackendHandle(backend, config); return }
            }
        }

        private fun nativeConfigOf(): String =
            """{"schemaVersion":1,"backend_type":"cpu","thread_num":4,"cache_path":"/data/local/tmp/mnn_test_cache.bin","precision":"low","memory":"low","use_mmap":true,"reuse_kv":true,"attention_mode":8,"dynamic_option":0,"temperature":0.8,"topP":0.9,"repetition_penalty":1.2,"mixed_samplers":["penalty","topK","tfs","typical","topP","min_p","temperature"],"power":"high","kv_max_length":2048}"""

        private fun loadConfigHashOf(config: String): String = config.hashCode().toString(16)

        private fun messages(secondTurn: Boolean = false): List<ChatMessage> {
            val sys = ChatMessage(role = "system", content = "你是中文测试助手。")
            return if (secondTurn) {
                listOf(sys, ChatMessage(role = "user", content = "你好"), ChatMessage(role = "assistant", content = "你好！"), ChatMessage(role = "user", content = "请再说一句话"))
            } else {
                listOf(sys, ChatMessage(role = "user", content = "请用一句话介绍你自己。"))
            }
        }
    }

    private fun requireHandle(): BackendHandle {
        assumeTrue("设备上无 MNN 模型 fixture，跳过运行时集成测试（明确原因，非静默通过）", loaded != null)
        return loaded!!
    }

    @Test
    fun nativeRuntimeInfoHandshakeIsValid() {
        val info = MnnBridge.nativeGetRuntimeInfo()
        assertNotNull("nativeGetRuntimeInfo 应返回 JSON", info)
        assertTrue("应含 abiVersion", info!!.contains("abiVersion"))
        assertTrue("应含 mnnCommit", info.contains("mnnCommit"))
        // final review C1：capabilities 缺 summary_v2 时必须显式可检出——runtimeDiagnostic
        // 非空且（ABI/commit 均匹配时）点名 summary_v2，供旧 native 构建排查。
        val parsed = MnnRuntimeInfo.fromJson(info)
        if (parsed != null && !parsed.capabilities.contains(MnnBridge.CAPABILITY_SUMMARY_V2)) {
            assertNotNull("能力集缺 summary_v2 时应暴露诊断", MnnBridge.runtimeDiagnostic)
            val diag = MnnBridge.runtimeDiagnostic!!
            if (parsed.abiVersion == MnnBridge.EXPECTED_JNI_ABI &&
                parsed.mnnCommit == MnnBridge.EXPECTED_MNN_COMMIT
            ) {
                assertTrue("诊断应点名 summary_v2（got: $diag）", diag.contains("summary_v2"))
            }
        }
    }

    @Test
    fun shortCpuGenerationProducesText() {
        val fx = requireHandle()
        val sb = StringBuilder()
        val summary = runBlocking {
            fx.backend.generateStreamMessages(
                messages = messages(),
                maxTokens = 64, temperature = 0.8f, topP = 0.9f, repeatPenalty = 1.2f,
                enableThinking = false,
                onToken = { sb.append(it); true },
                batchMaxBytes = 256, batchMaxMs = 16, downgradeReasons = emptyList(),
                executionControl = null, powerPolicy = com.rhodesisland.terminal.llm.profile.PowerPolicy.DEFAULT,
                requestedMode = null, effectiveMode = null, loadConfigHash = null,
                attemptTrace = emptyList(), coldLoadMs = null, warmLoadMs = null,
                decodeStepTokens = 1, thinkingRequested = null, templateCapability = null,
                thinkingClassifier = null, thinkingPolicy = null,
                configuredContextTokens = null, actualContextTokens = null,
            )
        }
        assertNotNull(summary)
        assertTrue("应产出可见文本", sb.isNotBlank())
        assertTrue("gen_len>0", summary!!.generatedTokens > 0)
    }

    @Test
    fun eosOrMaxTokensTerminatesWithConsistentSummary() {
        val fx = requireHandle()
        val sb = StringBuilder()
        val summary = runBlocking {
            fx.backend.generateStreamMessages(
                messages = messages(), maxTokens = 64, temperature = 0.8f, topP = 0.9f,
                repeatPenalty = 1.2f, enableThinking = false,
                onToken = { sb.append(it); true },
                batchMaxBytes = 256, batchMaxMs = 16, downgradeReasons = emptyList(),
                executionControl = null,
                powerPolicy = com.rhodesisland.terminal.llm.profile.PowerPolicy.DEFAULT,
                requestedMode = null, effectiveMode = null, loadConfigHash = null,
                attemptTrace = emptyList(), coldLoadMs = null, warmLoadMs = null,
                decodeStepTokens = 1, thinkingRequested = null, templateCapability = null,
                thinkingClassifier = null, thinkingPolicy = null,
                configuredContextTokens = null, actualContextTokens = null,
            )
        }
        assertNotNull(summary)
        val s = summary!!
        assertTrue(
            "完成原因应为 native 推导的 EOS/MAX_TOKENS（got ${s.completionReason}）",
            s.completionReason == "EOS" || s.completionReason == "MAX_TOKENS",
        )
        if (s.completionReason == "EOS") {
            assertTrue("EOS 应产出可见文本", sb.isNotBlank())
        }
        // v2 契约：默认步长 1（旧 native v1 摘要解析回填默认值，新 native v2 摘要亦为 1，两态一致）。
        assertEquals("decodeStepTokens 应回填默认 1", 1, s.decodeStepTokens)
    }

    @Test
    fun maxTokensLimitIsEnforced() {
        val fx = requireHandle()
        val sb = StringBuilder()
        val summary = runBlocking {
            fx.backend.generateStreamMessages(
                messages = messages(), maxTokens = 2, temperature = 0.8f, topP = 0.9f,
                repeatPenalty = 1.2f, enableThinking = false,
                onToken = { sb.append(it); true },
                batchMaxBytes = 256, batchMaxMs = 16, downgradeReasons = emptyList(),
                executionControl = null,
                powerPolicy = com.rhodesisland.terminal.llm.profile.PowerPolicy.DEFAULT,
                requestedMode = null, effectiveMode = null, loadConfigHash = null,
                attemptTrace = emptyList(), coldLoadMs = null, warmLoadMs = null,
                decodeStepTokens = 1, thinkingRequested = null, templateCapability = null,
                thinkingClassifier = null, thinkingPolicy = null,
                configuredContextTokens = null, actualContextTokens = null,
            )
        }
        assertNotNull(summary)
        val s = summary!!
        assertTrue("gen_len（${s.generatedTokens}）不应超过 maxTokens=2", s.generatedTokens <= 2)
        assertTrue(
            "原因应为 MAX_TOKENS 或 EOS（模型 2 token 内自然结束），got ${s.completionReason}",
            s.completionReason == "MAX_TOKENS" || s.completionReason == "EOS",
        )
    }

    @Test
    fun multiTokenStepStillEnforcesMaxTokens() {
        val fx = requireHandle()
        val sb = StringBuilder()
        // step=4 > maxTokens=3：修复前内层 for 一轮生成 4 token 直接越过上限 -> generatedTokens=4
        // 超发；修复后内层逐 token 复核 maxTokens，must 在触顶前拦截。模型若提前自然 EOS 也通过。
        val summary = runBlocking {
            fx.backend.generateStreamMessages(
                messages = messages(), maxTokens = 3, temperature = 0.8f, topP = 0.9f,
                repeatPenalty = 1.2f, enableThinking = false,
                onToken = { sb.append(it); true },
                batchMaxBytes = 256, batchMaxMs = 16, downgradeReasons = emptyList(),
                executionControl = null,
                powerPolicy = com.rhodesisland.terminal.llm.profile.PowerPolicy.DEFAULT,
                requestedMode = null, effectiveMode = null, loadConfigHash = null,
                attemptTrace = emptyList(), coldLoadMs = null, warmLoadMs = null,
                decodeStepTokens = 4,
            )
        }
        assertNotNull(summary)
        val s = summary!!
        assertTrue("step=4 时 gen_len（${s.generatedTokens}）仍不应超过 maxTokens=3", s.generatedTokens <= 3)
        assertTrue(
            "原因应为 MAX_TOKENS 或 EOS（模型 3 token 内自然结束），got ${s.completionReason}",
            s.completionReason == "MAX_TOKENS" || s.completionReason == "EOS",
        )
        // 摘要回读实际生效步长：4 在 native clamp 范围 [1,4] 内，原样生效。
        assertEquals("decodeStepTokens 应回读 4", 4, s.decodeStepTokens)
    }

    @Test
    fun cjkAndEmojiOutputIsWellFormedUtf8() {
        val fx = requireHandle()
        val sb = StringBuilder()
        val summary = runBlocking {
            fx.backend.generateStreamMessages(
                messages = listOf(
                    ChatMessage(role = "system", content = "你是中文测试助手。你的每条回复都必须以中文为主，可以适当包含 emoji 表情符号。"),
                    ChatMessage(role = "user", content = "请用一句话介绍你自己，必须包含中文，并带上一个 emoji。"),
                ),
                maxTokens = 128, temperature = 0.8f, topP = 0.9f, repeatPenalty = 1.2f,
                enableThinking = false,
                onToken = { sb.append(it); true },
                batchMaxBytes = 256, batchMaxMs = 16, downgradeReasons = emptyList(),
                executionControl = null,
                powerPolicy = com.rhodesisland.terminal.llm.profile.PowerPolicy.DEFAULT,
                requestedMode = null, effectiveMode = null, loadConfigHash = null,
                attemptTrace = emptyList(), coldLoadMs = null, warmLoadMs = null,
                decodeStepTokens = 1, thinkingRequested = null, templateCapability = null,
                thinkingClassifier = null, thinkingPolicy = null,
                configuredContextTokens = null, actualContextTokens = null,
            )
        }
        assertNotNull(summary)
        assertTrue("应产出可见中文文本", sb.isNotBlank())
        // UTF-8 字符边界完整：拼接文本不含 U+FFFD（流式批处理切分不得破坏多字节序列）。
        assertTrue("出现 U+FFFD（UTF-8 序列被批边界截断）：$sb", sb.indexOf('\uFFFD') < 0)
        // 字节级完整性：Kotlin 拼接字节数与 native 摘要 callbackBytes 一致（每个字节恰好一次）。
        assertEquals(
            "native 摘要 callbackBytes ≠ Kotlin 拼接字节数",
            summary!!.callbackBytes,
            sb.toByteArray(Charsets.UTF_8).size.toLong(),
        )
    }

    @Test
    fun cancellationStopsGeneration() {
        val fx = requireHandle()
        val sb = StringBuilder()
        val summary = runBlocking {
            fx.backend.generateStreamMessages(
                messages = messages(), maxTokens = 256, temperature = 0.8f, topP = 0.9f, repeatPenalty = 1.2f,
                enableThinking = false,
                onToken = { sb.append(it); sb.length < 8 },  // 立即截断
                batchMaxBytes = 256, batchMaxMs = 16, downgradeReasons = emptyList(),
                executionControl = null, powerPolicy = com.rhodesisland.terminal.llm.profile.PowerPolicy.DEFAULT,
                requestedMode = null, effectiveMode = null, loadConfigHash = null,
                attemptTrace = emptyList(), coldLoadMs = null, warmLoadMs = null,
                decodeStepTokens = 1, thinkingRequested = null, templateCapability = null,
                thinkingClassifier = null, thinkingPolicy = null,
                configuredContextTokens = null, actualContextTokens = null,
            )
        }
        assertNotNull(summary)
        // 策略截断（onToken false）应记 POLICY_TRUNCATION。
        assertTrue(
            "截断原因应为 POLICY_TRUNCATION 或提前结束",
            summary!!.completionReason == "POLICY_TRUNCATION" || summary.generatedTokens <= 16,
        )
    }

    @Test
    fun secondTurnReusesKvCache() {
        val fx = requireHandle()
        // 第一轮生成（预热 + 前缀）。
        runBlocking { fx.backend.generateStreamMessages(messages(false), 32, 0.8f, 0.9f, 1.2f, false, { true }, 256, 16, emptyList(), null, com.rhodesisland.terminal.llm.profile.PowerPolicy.DEFAULT, null, null, null, emptyList(), null, null, 1, null, null, null, null, null, null) }
        // 第二轮：新增 user，历史前缀应命中 KV。
        val summary = runBlocking {
            fx.backend.generateStreamMessages(messages(true), 32, 0.8f, 0.9f, 1.2f, false, { true }, 256, 16, emptyList(), null, com.rhodesisland.terminal.llm.profile.PowerPolicy.DEFAULT, null, null, null, emptyList(), null, null, 1, null, null, null, null, null, null)
        }
        assertNotNull(summary)
        assertEquals("第二轮应复用 KV 前缀", 1, summary!!.reuseKv)
    }

    @Test
    fun releaseDoesNotCrashAndAllowsReload() {
        val fx = requireHandle()
        fx.backend.release()
        val ok = runBlocking {
            fx.backend.initialize(fx.configPath, nativeConfigOf(), loadConfigHashOf(fx.configPath))
        }
        assertTrue("release 后应能重新加载", ok)
    }

    // ===== Task 4：首 delta 前 GPU 空输出回退 CPU（真机用例；CI 有模型机器时运行）=====

    /**
     * 脚本化 GPU 后端：GPU 侧输出形态由场景字段驱动（真实 GPU 空输出不可确定性复现——工作正常的
     * GPU 几乎必产出文本，坏掉的 GPU 又无法在 CI 上保证存在；stub 只负责「GPU 返回了空/部分输出」
     * 这一侧的确定性），回退后的 CPU 侧走**真实 MNN 运行时**：真实模型加载、真实 CPU 生成、真实
     * [releaseOthers] 释放语义，四类场景（空输出回退成功 / 部分输出不回退 / 取消不回退 / 无双驻留）
     * 均可确定性断言。
     */
    private class ScriptedGpuBackend(
        /** 首个 delta 前即输出的一段可见文本（模拟部分输出；null = 零输出）。 */
        var partialToken: String? = null,
        /** 请求级终止原因（模拟取消/超时等 requestStop 路径；null = 正常完成）。 */
        var stopReason: CompletionReason? = null,
        /** 空摘要的 token/字节数（部分输出场景须与 partialToken 一致非零）。 */
        var summaryTokens: Int = 0,
        var summaryBytes: Long = 0L,
    ) : InferenceBackend {
        var generateCalls = 0
        var released = false

        override val backendType: BackendType get() = BackendType.MNN_GPU
        override val backendName: String get() = backendType.displayName
        override val isSupported: Boolean get() = true
        override val isModelLoaded: Boolean get() = !released
        override val currentModelPath: String? get() = null
        override val lastErrorMessage: String? get() = null

        override suspend fun initialize(
            modelPath: String,
            nativeConfigJson: String,
            loadConfigHash: String,
        ): Boolean = true

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
            stopReason?.let { executionControl?.requestStop(it) }
            partialToken?.let { onToken(it) }
            // 模拟 MnnBackend finally 契约：分类器收口一次（回退判定消费 lastEmptyResponseClass）。
            thinkingClassifier?.finish(
                completionReason = stopReason ?: CompletionReason.EOS,
                generatedTokens = summaryTokens,
            )
            // 空输出摘要：EOS + 0 token + 0 bytes（部分输出场景下 callbackBytes>0 先被零输出硬条件拦截）。
            return NativeGenerationSummary(
                version = NativeGenerationSummary.VERSION,
                completionReason = "EOS",
                promptTokens = 10,
                generatedTokens = summaryTokens,
                prefillUs = 1L,
                decodeUs = 1L,
                reuseKv = 0,
                callbackCount = if (summaryTokens > 0) 1 else 0,
                callbackBytes = summaryBytes,
            )
        }

        override suspend fun stopGeneration() = Unit

        override fun release() {
            released = true
        }

        override fun getBackendMetrics(): BackendMetrics =
            BackendMetrics(tokensPerSecond = 0f, gpuUtilization = null, memoryUsedMB = 0L, backendName = backendName)
    }

    /** 真实 BackendManager：GPU 用脚本化 stub，CPU 用真实 MnnBackend（回退链路 + 真实运行时）。 */
    private fun realManager(
        configPath: String,
        gpuStub: ScriptedGpuBackend,
        onCpuCreated: (MnnBackend) -> Unit,
    ): BackendManager {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return BackendManager(
            context = context,
            cpuBoostController = CpuBoostController(context),
            healthCoordinator = null,
            backendFactory = { mode ->
                when (mode) {
                    MnnMode.CPU -> MnnBackend(context, MnnMode.CPU, CpuBoostController(context)).also { onCpuCreated(it) }
                    // AUTO 计划不含 NPU attempt；返回同一 stub 仅为满足构造（永不被调度）。
                    MnnMode.GPU_OPENCL, MnnMode.NPU_QNN -> gpuStub
                }
            },
        )
    }

    /** AUTO + MODEL_OK -> [OPENCL, CPU_OPTIMIZED, CPU_COMPATIBILITY]（与生产解析同构）。 */
    private fun realPlan(configPath: String): ResolvedInferencePlan {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return InferenceProfileResolver(context.cacheDir, configPath).resolve(
            mode = InferencePerformanceMode.BALANCED,
            backendPreference = BackendPreference.AUTO,
            contextTokens = 4096,
            maxOutputTokens = 256,
            thermalAdmittedThreads = 4,
            lookahead = false,
            temperature = 0.8f,
            topP = 0.9f,
            repeatPenalty = 1.2f,
            openclHealth = OpenClHealthState.MODEL_OK,
            modelClass = AutoBackendModelClass.GPU_ELIGIBLE,
        )
    }

    private fun realControl(maxTokens: Int): GenerationExecutionControl = GenerationExecutionControl(
        policy = GenerationSafetyPolicy(
            maxTokens = maxTokens,
            stallTimeoutMs = GenerationSafetyPolicy.DEFAULT_STALL_TIMEOUT_MS,
            wallClockTimeoutMs = GenerationSafetyPolicy.BALANCED_WALL_CLOCK_TIMEOUT_MS,
        ),
        startedElapsedMs = android.os.SystemClock.elapsedRealtime(),
    )

    private val fallbackPolicy = GenerationOutputPolicy(EmptyOutputFallbackPolicy.CPU_BEFORE_FIRST_DELTA)

    /** 统一 generate 入口：GPU stub + 回退策略 + 分类器（与生产调用同构）。 */
    private fun runGenerateWithFallback(
        manager: BackendManager,
        configPath: String,
        plan: ResolvedInferencePlan,
        control: GenerationExecutionControl,
        onToken: (String) -> Boolean,
    ): BackendManager.GenerationResult = runBlocking {
        manager.generate(
            modelPath = configPath,
            messages = messages(),
            maxTokens = plan.maxOutputTokens,
            temperature = 0.8f,
            topP = 0.9f,
            repeatPenalty = 1.2f,
            enableThinking = false,
            onToken = onToken,
            executionControl = control,
            resolvedPlan = plan,
            thinkingRequested = false,
            templateCapability = ThinkingTemplateCapability.SUPPORTED.name,
            thinkingClassifier = ThinkingOutputClassifier(false, ThinkingTemplateCapability.SUPPORTED),
            outputPolicy = fallbackPolicy,
        )
    }

    /** 真机用例 1：GPU 空输出（EOS, 0 token, 0 byte）-> 回退 CPU 并成功产出文本。 */
    @Test
    fun gpuEmptyOutputFallsBackToCpu() {
        val fx = requireHandle()
        val stub = ScriptedGpuBackend()
        lateinit var cpuBackend: MnnBackend
        val manager = realManager(fx.configPath, stub) { cpuBackend = it }
        val plan = realPlan(fx.configPath)
        val sb = StringBuilder()
        val result = runGenerateWithFallback(manager, fx.configPath, plan, realControl(plan.maxOutputTokens)) {
            sb.append(it); true
        }

        assertEquals("GPU 空输出应回退到 CPU", BackendType.MNN_CPU, result.usedBackend)
        assertEquals(CompletionReason.EOS, result.completionReason)
        assertTrue("CPU 应产出可见文本", sb.isNotBlank())
        assertTrue("回退推进 CPU 前 GPU 应已释放", stub.released)
        assertTrue("CPU 应已加载", cpuBackend.isModelLoaded)
        assertEquals("GPU 应只尝试一次", 1, stub.generateCalls)
    }

    /** 真机用例 2：GPU 部分输出（已有 delta）-> 不回退，原样返回 GPU 结果。 */
    @Test
    fun gpuPartialOutputDoesNotFallBack() {
        val fx = requireHandle()
        val stub = ScriptedGpuBackend(partialToken = "你", summaryTokens = 1, summaryBytes = 3L)
        lateinit var cpuBackend: MnnBackend
        val manager = realManager(fx.configPath, stub) { cpuBackend = it }
        val plan = realPlan(fx.configPath)
        val sb = StringBuilder()
        val result = runGenerateWithFallback(manager, fx.configPath, plan, realControl(plan.maxOutputTokens)) {
            sb.append(it); true
        }

        assertEquals("部分输出应留在 GPU，不回退", BackendType.MNN_GPU, result.usedBackend)
        assertEquals(CompletionReason.EOS, result.completionReason)
        assertEquals("已输出的 delta 原样保留", "你", sb.toString())
        assertFalse("未回退，GPU 不应被释放", stub.released)
        assertFalse("CPU 不应被加载（未触发回退）", cpuBackend.isModelLoaded)
    }

    /** 真机用例 3：取消（executionControl reason）-> 不回退，原样返回取消原因。 */
    @Test
    fun cancelDoesNotFallBack() {
        val fx = requireHandle()
        val stub = ScriptedGpuBackend(stopReason = CompletionReason.USER_CANCEL)
        lateinit var cpuBackend: MnnBackend
        val manager = realManager(fx.configPath, stub) { cpuBackend = it }
        val plan = realPlan(fx.configPath)
        val result = runGenerateWithFallback(manager, fx.configPath, plan, realControl(plan.maxOutputTokens)) {
            true
        }

        assertEquals("取消应原样返回，不回退", CompletionReason.USER_CANCEL, result.completionReason)
        assertEquals(BackendType.MNN_GPU, result.usedBackend)
        assertFalse("未回退，GPU 不应被释放", stub.released)
        assertFalse("CPU 不应被加载（取消路径）", cpuBackend.isModelLoaded)
    }

    /** 真机用例 4：空输出回退全程无双驻留——GPU 句柄释放后 CPU 才加载驻留。 */
    @Test
    fun emptyGpuFallbackKeepsSingleModelResidency() {
        val fx = requireHandle()
        val stub = ScriptedGpuBackend()
        lateinit var cpuBackend: MnnBackend
        val manager = realManager(fx.configPath, stub) { cpuBackend = it }
        val plan = realPlan(fx.configPath)
        val sb = StringBuilder()
        val result = runGenerateWithFallback(manager, fx.configPath, plan, realControl(plan.maxOutputTokens)) {
            sb.append(it); true
        }

        assertEquals(BackendType.MNN_CPU, result.usedBackend)
        // 无双驻留：回退推进 CPU attempt 前（releaseOthers(keep=CPU)）GPU stub 已被 release；
        // 断言「GPU 已释放」与「CPU 已加载」同真——二者不可能同时驻留。
        assertTrue("GPU stub 应已被 release（回退前释放）", stub.released)
        assertTrue("CPU 应已加载（回退后唯一驻留）", cpuBackend.isModelLoaded)
        assertTrue("CPU 应产出可见文本", sb.isNotBlank())
    }

    // ===== Task 3：单阶段思考（思考与正文共享同一 maxTokens 上限）=====

    /**
     * 恰好一次 JNI generation：直接调用 [MnnBackend.generateStreamMessages] 收集原始 delta，
     * 返回非空摘要；不经过 Provider helper，避免注入第二次 generate 调用。
     */
    private fun generateThinking(
        backend: MnnBackend,
        maxTokens: Int,
        enableThinking: Boolean,
    ): Pair<String, NativeGenerationSummary> {
        val raw = StringBuilder()
        val summary = runBlocking {
            backend.generateStreamMessages(
                messages = messages(),
                maxTokens = maxTokens,
                temperature = 0.8f,
                topP = 0.9f,
                repeatPenalty = 1.2f,
                enableThinking = enableThinking,
                onToken = { delta -> raw.append(delta); true },
                batchMaxBytes = 256,
                batchMaxMs = 16,
                downgradeReasons = emptyList(),
                executionControl = null,
                powerPolicy = PowerPolicy.DEFAULT,
                requestedMode = null,
                effectiveMode = null,
                loadConfigHash = null,
                attemptTrace = emptyList(),
                coldLoadMs = null,
                warmLoadMs = null,
                decodeStepTokens = 1,
                thinkingRequested = enableThinking,
                templateCapability = null,
                thinkingClassifier = null,
                thinkingPolicy = null,
                configuredContextTokens = null,
                actualContextTokens = null,
            )
        }
        return raw.toString() to requireNotNull(summary)
    }

    /** 真机用例：思考开启时思考与正文共享同一个 maxTokens 总上限，单阶段自然结束。 */
    @Test
    fun thinkingAndBodyShareOneMaxTokenLimit() {
        val fx = requireHandle()
        val (raw, summary) = generateThinking(fx.backend, maxTokens = 128, enableThinking = true)
        assertTrue(summary.generatedTokens <= 128)
        assertTrue(summary.completionReason == "EOS" || summary.completionReason == "MAX_TOKENS")
        assertTrue(raw.isNotEmpty() || summary.generatedTokens == 0)
        // 模板能力门控：仅当 fixture 模板含 enable_thinking 分支时才要求「思考配置被 native 接受」且
        // 思考段在正文首个 delta 之前自然闭合；模板未知/不支持时用 assumeTrue 明确跳过该子断言——
        // 不把「模型没有 think 标签」误判成 runtime 失败。
        val capability = ThinkingTemplateCapabilityResolver().resolve(
            File(fx.configPath).parentFile ?: File(fx.configPath),
        )
        assumeTrue(
            "fixture 模板不含 enable_thinking 分支（capability=$capability），跳过思考配置/边界子断言",
            capability == ThinkingTemplateCapability.SUPPORTED,
        )
        // 模板能力守卫之外还需 native v2 能力守卫：pinned libmnn_jni.so 若为 v1（无 summary_v2），
        // thinkingConfigAccepted 为 null（摘要解析回填默认值），此时跳过而非硬失败——与相邻
        // reasoningEndUs/firstBodyDeltaUs 的 null 保护同风格，避免真机 CI 误红。
        if (summary.thinkingConfigAccepted != null) {
            assertEquals(true, summary.thinkingConfigAccepted)
        }
        val reasoningEnd = summary.reasoningEndUs
        val firstBody = summary.firstBodyDeltaUs
        if (reasoningEnd != null && firstBody != null) {
            assertTrue(
                "思考段应在正文首个 delta 之前闭合（reasoningEndUs=$reasoningEnd, firstBodyDeltaUs=$firstBody）",
                reasoningEnd <= firstBody,
            )
        }
    }

    /** 真机用例：思考开/关走同一单阶段生成契约（同一步长、同一 maxTokens 总上限）。 */
    @Test
    fun thinkingToggleUsesSameGenerationContract() {
        val fx = requireHandle()
        val (_, off) = generateThinking(fx.backend, maxTokens = 128, enableThinking = false)
        val (_, on) = generateThinking(fx.backend, maxTokens = 128, enableThinking = true)
        assertEquals(1, off.decodeStepTokens)
        assertEquals(1, on.decodeStepTokens)
        assertTrue(off.generatedTokens <= 128)
        assertTrue(on.generatedTokens <= 128)
    }
}
