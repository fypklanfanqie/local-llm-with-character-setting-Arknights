package com.rhodesisland.terminal.llm.backend

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.llm.CpuBoostController
import com.rhodesisland.terminal.llm.GenerationExecutionControl
import com.rhodesisland.terminal.llm.ProcessMemorySampler
import com.rhodesisland.terminal.llm.metrics.CompletionReason
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.profile.PowerPolicy
import com.rhodesisland.terminal.llm.metrics.InferenceTelemetry
import com.rhodesisland.terminal.llm.metrics.InferenceTurnRecord
import com.rhodesisland.terminal.llm.metrics.NativeGenerationSummary
import com.rhodesisland.terminal.llm.template.ThinkingOutputClassifier
import com.rhodesisland.terminal.llm.thinking.ThinkingPolicyTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import com.chatbyyourside.llm.backend.MnnBridge

/**
 * MNN 推理后端（[InferenceBackend] 实现）
 *
 * 封装 [MnnBridge]，加载 `.mnn` 模型目录、流式推理。一个实例对应一种执行模式 [mode]
 * （CPU / OpenCL GPU / QNN NPU），[BackendManager] 持有三个实例分别对应 [BackendType.MNN_CPU] /
 * [MNN_GPU] / [MNN_NPU]，按回退链调度。
 *
 * 模型格式：`.mnn` 目录（config.json + llm.mnn + 权重 + tokenizer），[initialize] 传入 config.json 路径。
 * 聊天模板：MNN 按各模型自带模板应用，故本后端重写 [generateStreamMessages] 接收**消息列表**。
 * 后端选择：MNN `set_config` 的 `backend_type`（cpu/opencl/qnn），在 [nativeCreate] 时传入。
 *
 * 并发：MNN 的流式回调（[MnnBridge.onToken]/[abort]）为静态全局态，同一时刻仅能跑一个 MNN 推理，
 * 故用 [mnnMutex]（伴生，三类 MNN 后端共享）串行化。BackendManager 本身也保证一次仅一个后端活跃。
 *
 * CPU 提频：[cpuBoostController] 在 [generateStreamMessages] 内包住 [nativeGenerateStream]
 * （hint session + 高线程优先级），MNN CPU 推理时把大核频率尽量推高；onToken 上报相邻 token 间隔
 * 给系统精确调频。GPU/NPU 模式下提频无意义但无害（enabled 由设置同步）。
 *
 * 失败语义：[initialize] 失败返回 false（不抛异常），由 [BackendManager] 按链回退（MNN_NPU -> MNN_GPU -> MNN_CPU）。
 */
class MnnBackend(
    private val context: Context,
    val mode: MnnMode,
    private val cpuBoostController: CpuBoostController,
) : InferenceBackend {

    /** MNN 执行模式 */
    enum class MnnMode(val mnnBackendType: String, val displayName: String) {
        CPU("cpu", "MNN CPU"),
        GPU_OPENCL("opencl", "MNN OpenCL GPU"),
        NPU_QNN("qnn", "MNN QNN NPU"),
    }

    private val bridge = MnnBridge()

    /** 所有 MNN 后端共享的串行锁（静态回调全局态） */
    private val mutex get() = mnnMutex

    @Volatile
    private var handle: Long = 0L
    private var loadedConfigPath: String? = null
    /** 当前已加载配置的 loadConfigHash（Task 7 唯一重载指纹）。 */
    @Volatile
    private var loadedConfigHash: String? = null

    @Volatile
    private var isGenerating: Boolean = false
    @Volatile
    private var currentTps: Float = 0f
    private var genStartTime: Long = 0L
    private var tokenCount: Int = 0
    /** 流式回调累计 UTF-8 字节数（核对拼接完整性 / Task 4 流式批处理用）。 */
    private var callbackBytes: Long = 0L
    /** 每次生成的递增序号，用于遥测 generationId。 */
    private val generationCounter = AtomicInteger(0)
    /** 遥测原子存储：onToken 写实时快照供 overlay 读取，finally 写最终记录。 */
    private val telemetry = InferenceTelemetry()
    /** 最近一次生成的最终遥测记录（供 Task 9 健康库等消费；本任务仅产出与日志）。 */
    @Volatile
    var lastTurnRecord: InferenceTurnRecord? = null
        private set

    /** Task 15/16：旁路操作（GPU 预热等）后恢复被覆盖的「最近一次聊天」记录。 */
    internal fun restoreLastTurnRecord(record: InferenceTurnRecord?) {
        lastTurnRecord = record
    }

    override val backendType: BackendType = when (mode) {
        MnnMode.CPU -> BackendType.MNN_CPU
        MnnMode.GPU_OPENCL -> BackendType.MNN_GPU
        MnnMode.NPU_QNN -> BackendType.MNN_NPU
    }

    override val backendName: String = mode.displayName

    /**
     * 是否支持本模式：
     * - CPU：libMNN.so 就绪即支持。
     * - GPU：libMNN.so 就绪即试探（OpenCL 运行时检测；不可达则 initialize 失败回退 CPU）。
     * - NPU：libMNN.so 就绪 + QNN 库打包 + 骁龙旗舰（[MnnSupportDetector.qnnReady]）。
     */
    override val isSupported: Boolean
        get() = when (mode) {
            MnnMode.CPU -> MnnBridge.nativeAvailable
            MnnMode.GPU_OPENCL -> MnnBridge.nativeAvailable && MnnSupportDetector.openclAvailable()
            MnnMode.NPU_QNN -> MnnBridge.nativeAvailable && MnnSupportDetector.qnnReady(context)
        }

    override val isModelLoaded: Boolean
        get() = handle != 0L

    override val currentModelPath: String?
        get() = loadedConfigPath

    @Volatile
    override var lastErrorMessage: String? = null
        private set

    override suspend fun initialize(
        modelPath: String,
        nativeConfigJson: String,
        loadConfigHash: String,
    ): Boolean = mutex.withLock {
        lastErrorMessage = null  // 清旧值，避免跨调用残留误导诊断
        if (!MnnBridge.nativeAvailable) {
            lastErrorMessage = "MNN native 不可用（libMNN/libmnn_jni 未加载）"
            Log.e(TAG, lastErrorMessage!!)
            return@withLock false
        }
        val configFile = File(modelPath)
        if (!configFile.exists()) {
            lastErrorMessage = "config.json 不存在: $modelPath"
            Log.e(TAG, lastErrorMessage!!)
            return@withLock false
        }
        // 热复用：同路径 + 同 loadConfigHash 已加载 -> 直接复用，不重建。
        if (handle != 0L && loadedConfigPath == modelPath && loadedConfigHash == loadConfigHash) {
            Log.i(TAG, "MNN 已加载且配置指纹一致，热复用 (${mode.displayName}, hash=${loadConfigHash.take(8)})")
            return@withLock true
        }

        freeHandleLocked()

        currentCoroutineContext().ensureActive()
        Log.i(TAG, "加载 MNN 模型: $modelPath (backend=${mode.mnnBackendType}, configHash=${loadConfigHash.take(8)})")

        val h = try {
            bridge.nativeCreate(modelPath, nativeConfigJson)
        } catch (e: Throwable) {
            lastErrorMessage = "nativeCreate 异常: ${e.message}"
            Log.e(TAG, lastErrorMessage!!)
            0L
        }
        if (h == 0L) {
            // nativeCreate 返回 0：取 native 侧真实失败原因，供 BackendManager 汇总上报。
            val nativeErr = runCatching { bridge.nativeGetLastError() }.getOrDefault("").orEmpty()
            lastErrorMessage = "模型加载失败 (backend=${mode.mnnBackendType})" +
                (if (nativeErr.isNotBlank()) ": $nativeErr" else "")
            Log.e(TAG, lastErrorMessage!!)
            return@withLock false
        }
        handle = h
        loadedConfigPath = modelPath
        loadedConfigHash = loadConfigHash
        lastErrorMessage = null
        Log.i(TAG, "MNN 后端就绪 (${mode.displayName})")
        true
    }

    /**
     * MNN 流式生成（重写）：把消息列表交给 MNN，由模型自带 chat 模板格式化后推理。
     * BackendManager 统一调用本方法（消息列表路径），由 MNN 套用各模型自带 chat 模板
     * （Qwen=ChatML，Llama/Gemma/Phi 各异）。
     */
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
        /** Task 1 v2：native decode 步长（默认 1，见 [InferenceBackend.generateStreamMessages]；
         *  摘要 decodeStepTokens 记 native clamp 后的实际生效值）。 */
        decodeStepTokens: Int,
        // Task 2：思考请求 / 模板能力 / 思考分类器（override 不重复接口默认值，见
        // [InferenceBackend.generateStreamMessages]；分类器在 finally 内收口并入 finalize）。
        thinkingRequested: Boolean?,
        templateCapability: String?,
        thinkingClassifier: ThinkingOutputClassifier?,
        // Task 5：本地思考档位策略快照（单次透传；思考关闭/云端为 null）。
        thinkingPolicy: ThinkingPolicyTelemetry?,
        // Task 15：内存准入的上下文降级（配置值 -> 实际值；未降级为 null）。
        configuredContextTokens: Int?,
        actualContextTokens: Int?,
    ): NativeGenerationSummary? = mutex.withLock {
        if (handle == 0L) throw IllegalStateException("MNN 后端未加载模型")
        currentCoroutineContext().ensureActive()
        // Task 16：阶段边界 PSS 采样（入口 = 模型已加载后基线；首 delta = prefill 完成后峰值近似；
        // finally = 生成后）。不逐 token 采样（getProcessMemoryInfo 数十 ms 级开销会拖慢推理）。
        val pssStartBytes = ProcessMemorySampler.currentProcessPssBytes(context)
        var pssPrefillBytes: Long? = null

        val messagesJson = MnnBridge.toMessagesJson(messages)
        tokenCount = 0
        callbackBytes = 0L
        currentTps = 0f
        genStartTime = SystemClock.elapsedRealtime()
        isGenerating = true
        val generationId = "${mode.name.lowercase()}-${generationCounter.incrementAndGet()}"
        telemetry.beginGeneration(
            generationId = generationId,
            requestedMode = requestedMode,   // Task 1：由 resolvedPlan 传入，不再为 null
            effectiveMode = effectiveMode,
            backend = backendType,
            startedElapsedMs = genStartTime,
        )

        // CPU 提频：由 PowerPolicy 驱动（Task 8），包住 nativeGenerateStream（同一推理线程 begin/close）。
        // onToken 与 nativeGenerateStream 同线程（同步 JNI 回调），故 reportWorkDuration 的 tid 一致。
        val boost = cpuBoostController.beginInference(powerPolicy)
        var lastTokenTimeNs = 0L
        // Task 4 Step 3：本方法不累积完整回复（全文拼接唯一归 LocalChatProvider）。
        // policyStopped 标记策略截断（onToken 返回 false），完成原因优先级高于 USER_CANCEL。
        var policyStopped = false
        // 批处理后回调携带 native 实时 gen_len（真实 token 数），token 数与回调（批次）次数解耦：
        // tokenCount=gen_len 供实时 tps；callbackCount=批次次数入快照。
        var callbackCount = 0
        // abort/stop reason 由整次 BackendManager 请求的 control 管理；本后端绝不在 fallback 时复位。
        MnnBridge.onToken = { token, generatedTokens ->
            callbackCount++
            // prefill 峰值近似：首个可见 delta 采样一次（prefill 完成 + KV 全量分配后，非逐 token）。
            if (callbackCount == 1) {
                pssPrefillBytes = ProcessMemorySampler.currentProcessPssBytes(context)
            }
            callbackBytes += token.toByteArray(Charsets.UTF_8).size.toLong()
            tokenCount = generatedTokens   // native 实时 gen_len（绝对累计，非增量）
            val now = System.nanoTime()
            if (lastTokenTimeNs > 0L) {
                boost?.reportWorkDuration(now - lastTokenTimeNs)
            }
            lastTokenTimeNs = now
            val nowElapsed = SystemClock.elapsedRealtime()
            val elapsed = (nowElapsed - genStartTime) / 1000f
            if (elapsed > 0f) currentTps = tokenCount / elapsed
            // 发布 DECODE 实时快照（原子）：overlay 500ms 读它，零 native 调用。
            telemetry.onDecodeToken(
                tokenCount = tokenCount,
                callbackCount = callbackCount,
                callbackBytes = callbackBytes,
                currentTps = if (elapsed > 0f) currentTps else null,
                nowElapsedMs = nowElapsed,
            )
            executionControl?.onProgress(generationId, tokenCount, nowElapsed)
            val cont = onToken(token)
            if (!cont) {
                policyStopped = true
                MnnBridge.abort = true
            }
        }

        var completedNormally = false
        var parsed: NativeGenerationSummary? = null
        try {
            // 第二次检查关闭 ensureActive() -> JNI 之间的取消窗口；请求级终止已确定时不再启动新 JNI。
            currentCoroutineContext().ensureActive()
            if (executionControl?.reason() != null) {
                MnnBridge.abort = true
                completedNormally = true
                null
            } else {
                // native 不再返回完整回复，只回紧凑 GenerationSummary JSON；文本走流式回调。
                val summaryJson = bridge.nativeGenerateStream(
                    handle, messagesJson, maxTokens, temperature, topP, repeatPenalty, enableThinking,
                    batchMaxBytes, batchMaxMs, decodeStepTokens,
                )
                completedNormally = true
                parsed = NativeGenerationSummary.parse(summaryJson)
                if (parsed == null) {
                    Log.e(TAG, "native GenerationSummary 解析失败: ${summaryJson.take(200)}")
                }
                parsed
            }
        } finally {
            val cancelled = MnnBridge.abort
            MnnBridge.onToken = null
            MnnBridge.abort = false
            isGenerating = false
            boost?.close()
            // 受控点（finally 内单次调用）：优先用摘要组装的指标数组（零二次 native 调用），
            // 摘要不可用（解析失败/未走 native）才回退 nativeGetMetrics。overlay 永不在此并发读。
            val nativeMetrics = parsed?.toMetricsArray() ?: runCatching {
                if (handle != 0L) bridge.nativeGetMetrics(handle) else null
            }.getOrNull()
            // 请求级 token 预算以最终 native genLen 再对齐一次，覆盖末批/异常路径中回调未完整发布的情况。
            if (nativeMetrics != null && nativeMetrics.size > 4) {
                executionControl?.onProgress(
                    generationId = generationId,
                    generatedTokens = nativeMetrics[4].toInt(),
                    progressElapsedMs = SystemClock.elapsedRealtime(),
                )
            }
            val stopReason = executionControl?.reason()
            // 完成原因优先级：策略截断 > 请求级终止 > 用户取消 > 后端失败 > 摘要原因。
            val completionReason = when {
                policyStopped -> CompletionReason.POLICY_TRUNCATION
                stopReason != null -> stopReason
                cancelled -> CompletionReason.USER_CANCEL
                !completedNormally || parsed == null -> CompletionReason.BACKEND_FAILURE
                // NativeGenerationSummary.parse 已严格拒绝未知 reason，此处完整恢复所有合法枚举，
                // 不能把 TIMEOUT/THERMAL_STOP 等误记成 EOS。
                else -> CompletionReason.valueOf(parsed.completionReason)
            }
            // Task 2：分类器在本轮唯一收口点（finally 内）完成观察收口——与 finalize 同处持有
            // completionReason / native genLen 的权威口径，杜绝 provider 侧补记写错轮次的污染。
            val thinkingObs = thinkingClassifier?.finish(
                completionReason = completionReason,
                generatedTokens = nativeMetrics?.getOrNull(4)?.toInt() ?: tokenCount,
                // M-2：PREFILL/DECODE_FAILURE 优先用 native errorStage，缺失时回退 token 近似。
                errorStage = parsed?.errorStage,
            )
            if (thinkingObs != null) {
                Log.i(
                    TAG,
                    "thinking 分类: requested=$thinkingRequested capability=$templateCapability " +
                        "effect=${thinkingObs.thinkingEffect} empty=${thinkingObs.emptyResponseClass} " +
                        "open=${thinkingClassifier?.sawThinkOpen} close=${thinkingClassifier?.sawThinkClose} " +
                        "rawBytes=${thinkingClassifier?.rawBytes} bodyBytes=${thinkingClassifier?.bodyBytes} " +
                        "firstBodyOffset=${thinkingClassifier?.firstBodyOffset}",
                )
            }
            lastTurnRecord = telemetry.finalize(
                nowElapsedMs = SystemClock.elapsedRealtime(),
                completionReason = completionReason,
                nativeMetrics = nativeMetrics,
                // Task 1：TTFT 优先取 native firstDeltaUs（us->ms，相对 prefill 起点更精确），
                // 为空时回退 Kotlin 侧首回调时间（finalize 内部逻辑）。
                ttftMsOverride = parsed?.firstDeltaUs?.let { it / 1000 },
                configHash = loadConfigHash ?: loadedConfigPath?.let { it.hashCode().toString(16) },
                attemptTrace = attemptTrace,
                downgradeReasons = downgradeReasons,
                coldLoadMs = coldLoadMs,
                warmLoadMs = warmLoadMs,
                // Task 1 v2：思考配置接受 / 思考边界 / 首正文时刻 / 实际生效步长（摘要缺失时为 null）。
                thinkingConfigAccepted = parsed?.thinkingConfigAccepted,
                reasoningEndUs = parsed?.reasoningEndUs,
                firstBodyDeltaUs = parsed?.firstBodyDeltaUs,
                decodeStepTokens = parsed?.decodeStepTokens,
                // Task 2：思考请求 / 模板能力（生成前已知，信封透传）；思考效果 / 空响应分类
                // 由分类器在 finally 内收口（thinkingObs），四字段齐全。
                thinkingRequested = thinkingRequested,
                templateCapability = templateCapability,
                thinkingEffective = thinkingObs?.thinkingEffect?.name,
                emptyResponseClass = thinkingObs?.emptyResponseClass?.name,
                // Task 5：本地思考档位策略快照随本轮记录一并收口。
                thinkingPolicy = thinkingPolicy,
                // Task 15：内存准入的上下文降级（配置值 -> 实际值）。
                configuredContextTokens = configuredContextTokens,
                actualContextTokens = actualContextTokens,
                // Task 16：阶段边界 PSS 峰值（MB）= max(加载后基线, prefill 首 delta, 生成后)；
                // 采样失败为 null（既有 PSS 门禁按无证据处理）。
                peakPssMb = maxOf(
                    pssStartBytes ?: 0L,
                    pssPrefillBytes ?: 0L,
                    ProcessMemorySampler.currentProcessPssBytes(context) ?: 0L,
                ).takeIf { it > 0L }?.div(1024L * 1024),
            )
            // 汇总日志：tps + 摘要实测复用/前缀/批处理指标，便于核对多轮前缀复用与回调削减是否生效。
            if (parsed != null) {
                Log.i(TAG, "生成结束 ${mode.displayName}: tps=${"%.1f".format(nativeMetrics?.get(0) ?: 0f)} " +
                    "promptLen=${parsed.promptTokens} genLen=${parsed.generatedTokens} " +
                    "reuseKv=${parsed.reuseKv} cb=${parsed.callbackCount} " +
                    "step=${parsed.decodeStepTokens} reason=$completionReason")
            } else if (nativeMetrics != null && nativeMetrics.size >= 6) {
                Log.i(TAG, "生成结束 ${mode.displayName}: tps=${"%.1f".format(nativeMetrics[0])} " +
                    "promptLen=${nativeMetrics[3].toInt()} genLen=${nativeMetrics[4].toInt()} " +
                    "reuseKv=${nativeMetrics[5].toInt()} reason=$completionReason")
            }
        }
    }

    /**
     * 单 prompt 路径：MNN 以消息列表为核心，此处把 prompt 当作单条 user 消息走 [generateStreamMessages]，
     * 由 MNN 套用 chat 模板。BackendManager 对 MNN 后端调用 [generateStreamMessages]，本方法仅供兼容极少路径。
     */
    suspend fun generateStream(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        onToken: (String) -> Boolean,
    ): NativeGenerationSummary? = generateStreamMessages(
        listOf(ChatMessage(role = "user", content = prompt)),
        maxTokens, temperature, topP, repeatPenalty, enableThinking = true, onToken,
        batchMaxBytes = InferenceBackend.DEFAULT_BATCH_MAX_BYTES,
        batchMaxMs = InferenceBackend.DEFAULT_BATCH_MAX_MS,
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
        thinkingRequested = null,
        templateCapability = null,
        thinkingClassifier = null,
        thinkingPolicy = null,
        configuredContextTokens = null,
        actualContextTokens = null,
    )

    override suspend fun stopGeneration() {
        MnnBridge.abort = true
        if (handle != 0L) runCatching { bridge.nativeStop(handle) }
    }

    /** 非挂起中断：仅设置全局 abort/nativeStop，不持有完成原因（原因归请求级 control 所有）。 */
    fun cancelNow() {
        MnnBridge.abort = true
        if (handle != 0L) runCatching { bridge.nativeStop(handle) }
    }

    /** 是否已加载同一路径同一配置指纹（热复用判定，Task 7）。 */
    fun isLoadedWithConfigHash(path: String, hash: String): Boolean =
        handle != 0L && loadedConfigPath == path && loadedConfigHash == hash

    override fun release() {
        if (handle != 0L) {
            runCatching { bridge.nativeRelease(handle) }
            handle = 0L
            loadedConfigPath = null
            loadedConfigHash = null
        }
    }

    private fun freeHandleLocked() {
        if (handle != 0L) {
            runCatching { bridge.nativeRelease(handle) }
            handle = 0L
            loadedConfigPath = null
            loadedConfigHash = null
        }
    }

    override fun getBackendMetrics(): BackendMetrics {
        // overlay 读取：仅用 onToken 回调期已算出的实时快照，绝不并发调用 nativeGetMetrics。
        // 精确 native tps 在生成结束的 finally 受控点写入 [lastTurnRecord]。
        val snap = telemetry.snapshot()
        val tps = if (isGenerating) (snap?.currentTps ?: currentTps) else 0f
        return BackendMetrics(
            tokensPerSecond = tps,
            // 无可靠 GPU/NPU 占用率读取口径，统一 N/A（不再用 0.85f 假值）。
            gpuUtilization = null,
            memoryUsedMB = 0L,
            backendName = backendName,
        )
    }

    companion object {
        private const val TAG = "MnnBackend"
        /** 三类 MNN 后端共享的串行锁（静态流式回调全局态） */
        private val mnnMutex = Mutex()
    }
}
