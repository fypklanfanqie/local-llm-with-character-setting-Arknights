package com.rhodesisland.terminal.llm.backend

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.llm.CpuBoostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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

    @Volatile
    private var isGenerating: Boolean = false
    @Volatile
    private var currentTps: Float = 0f
    private var genStartTime: Long = 0L
    private var tokenCount: Int = 0

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
        contextLength: Int,
        threads: Int,
        lookahead: Boolean,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
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
        // 先释放已有实例
        freeHandleLocked()

        currentCoroutineContext().ensureActive()
        Log.i(TAG, "加载 MNN 模型: $modelPath (backend=${mode.mnnBackendType}, ctx=$contextLength, threads=$threads, lookahead=$lookahead, temp=$temperature, topP=$topP, rep=$repeatPenalty)")

        val h = try {
            bridge.nativeCreate(modelPath, mode.mnnBackendType, threads, contextLength, lookahead, temperature, topP, repeatPenalty)
        } catch (e: Throwable) {
            lastErrorMessage = "nativeCreate 异常: ${e.message}"
            Log.e(TAG, lastErrorMessage!!)
            0L
        }
        if (h == 0L) {
            // nativeCreate 返回 0：取 native 侧真实失败原因（含 CPU 安全配置重试结果），供 BackendManager 汇总上报
            val nativeErr = runCatching { bridge.nativeGetLastError() }.getOrDefault("").orEmpty()
            lastErrorMessage = "模型加载失败 (backend=${mode.mnnBackendType})" +
                (if (nativeErr.isNotBlank()) ": $nativeErr" else "")
            Log.e(TAG, lastErrorMessage!!)
            return@withLock false
        }
        handle = h
        loadedConfigPath = modelPath
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
    ): String = mutex.withLock {
        if (handle == 0L) throw IllegalStateException("MNN 后端未加载模型")
        currentCoroutineContext().ensureActive()

        val messagesJson = MnnBridge.toMessagesJson(messages)
        tokenCount = 0
        currentTps = 0f
        genStartTime = SystemClock.elapsedRealtime()
        isGenerating = true

        val fullText = StringBuilder()
        // CPU 提频：包住 nativeGenerateStream（同一推理线程 begin/close）。
        // onToken 与 nativeGenerateStream 同线程（同步 JNI 回调），故 reportWorkDuration 的 tid 一致。
        val boost = cpuBoostController.beginInference(CpuBoostController.TARGET_WORK_DURATION_NS)
        var lastTokenTimeNs = 0L
        // 先复位 abort 再装 onToken：若复位在装回调之后，装回调与复位之间收到 stopGeneration
        // (abort=true) 会被复位覆盖，取消信号丢失、生成本应停止却跑到结束。
        MnnBridge.abort = false
        MnnBridge.onToken = { token ->
            tokenCount++
            val now = System.nanoTime()
            if (lastTokenTimeNs > 0L) {
                boost?.reportWorkDuration(now - lastTokenTimeNs)
            }
            lastTokenTimeNs = now
            val elapsed = (SystemClock.elapsedRealtime() - genStartTime) / 1000f
            if (elapsed > 0f) currentTps = tokenCount / elapsed
            fullText.append(token)
            val cont = onToken(token)
            if (!cont) MnnBridge.abort = true
        }

        try {
            val bytes = bridge.nativeGenerateStream(
                handle, messagesJson, maxTokens, temperature, topP, repeatPenalty, enableThinking,
            )
            val nativeFull = String(bytes, Charsets.UTF_8)
            fullText.toString().ifBlank { nativeFull }
        } finally {
            MnnBridge.onToken = null
            MnnBridge.abort = false
            isGenerating = false
            boost?.close()
            // 汇总日志：tps + MNN 实测复用/前缀指标，便于核对多轮前缀复用是否生效。
            // metrics=[tps, prefillUs, decodeUs, promptLen, genLen, reuseKv]
            runCatching {
                if (handle != 0L) {
                    val m = bridge.nativeGetMetrics(handle)
                    if (m != null && m.size >= 6) {
                        Log.i(TAG, "生成结束 ${mode.displayName}: tps=${"%.1f".format(m[0])} " +
                            "promptLen=${m[3].toInt()} genLen=${m[4].toInt()} reuseKv=${m[5].toInt()}")
                    }
                }
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
    ): String = generateStreamMessages(
        listOf(ChatMessage(role = "user", content = prompt)),
        maxTokens, temperature, topP, repeatPenalty, enableThinking = true, onToken,
    )

    override suspend fun stopGeneration() {
        MnnBridge.abort = true
        if (handle != 0L) runCatching { bridge.nativeStop(handle) }
    }

    /** 非挂起中断（供 BackendManager.cancel 等非 suspend 调用方） */
    fun cancelNow() {
        MnnBridge.abort = true
        if (handle != 0L) runCatching { bridge.nativeStop(handle) }
    }

    override fun release() {
        if (handle != 0L) {
            runCatching { bridge.nativeRelease(handle) }
            handle = 0L
            loadedConfigPath = null
        }
    }

    private fun freeHandleLocked() {
        if (handle != 0L) {
            runCatching { bridge.nativeRelease(handle) }
            handle = 0L
            loadedConfigPath = null
        }
    }

    override fun getBackendMetrics(): BackendMetrics {
        // 优先用 MNN LlmContext 的 decode_us/gen_seq_len 算 tps（更准）
        val ctxTps = runCatching {
            if (handle != 0L) {
                val m = bridge.nativeGetMetrics(handle) // [tps, prefillUs, decodeUs, promptLen, genLen, reuseKv]
                if (m != null && m.isNotEmpty()) m[0] else 0f
            } else 0f
        }.getOrDefault(0f)
        return BackendMetrics(
            tokensPerSecond = if (ctxTps > 0f) ctxTps else currentTps,
            gpuUtilization = if (isGenerating && mode != MnnMode.CPU) 0.85f else 0f,
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
