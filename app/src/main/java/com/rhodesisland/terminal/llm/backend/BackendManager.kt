package com.rhodesisland.terminal.llm.backend

import android.content.Context
import android.util.Log
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.llm.CpuBoostController
import com.rhodesisland.terminal.llm.backend.MnnBackend.MnnMode
import kotlinx.coroutines.CancellationException

/**
 * 后端管理器（统一推理管理器）
 *
 * 持有 MNN 侧三个后端实例（[MnnBackend] ×3：CPU/OpenCL/QNN），按用户偏好 [BackendPreference]
 * + 设备能力选择后端执行推理。
 *
 * 回退链：显式选 NPU 时 MNN_NPU(QNN) > MNN_GPU(OpenCL) > MNN_CPU；AUTO **不含 NPU**（直接 HTP 在非
 * root 锁定设备原生崩在 PipelineModule::load，见 [backendOrder] 与 memory mnn-qnn-htp-selinux-blocked），
 * 走 MNN_GPU > MNN_CPU。任一后端 [initialize] 返回 false 或 [generateStreamMessages] 抛异常时，
 * 记录日志并尝试链中的下一个后端。链末端的 MNN_CPU 为兜底。
 *
 * 内存：切换后端前先释放可能驻留的其他后端模型，避免两套模型同时占内存。
 *
 * @param cpuBoostController CPU 提频控制器（透传给 [MnnBackend]，MNN CPU 推理时开 hint session）
 */
class BackendManager(
    context: Context,
    private val cpuBoostController: CpuBoostController,
) {
    private val selector = BackendSelector(context)
    private val mnnCpuBackend = MnnBackend(context, MnnMode.CPU, cpuBoostController)
    private val mnnGpuBackend = MnnBackend(context, MnnMode.GPU_OPENCL, cpuBoostController)
    private val mnnNpuBackend = MnnBackend(context, MnnMode.NPU_QNN, cpuBoostController)

    /** 设备能力（惰性计算一次） */
    val deviceCapability: BackendSelector.DeviceCapability by lazy { selector.collectDeviceInfo() }

    /** MNN 各模式是否可用（惰性） */
    val mnnCpuSupported: Boolean by lazy { mnnCpuBackend.isSupported }
    val mnnGpuSupported: Boolean by lazy { mnnGpuBackend.isSupported }
    val mnnNpuSupported: Boolean by lazy { mnnNpuBackend.isSupported }

    /** 最近一次实际使用的后端类型（供 UI/浮窗展示） */
    @Volatile
    var lastUsedBackend: BackendType = BackendType.MNN_CPU
        private set

    /** MNN NPU 初始化失败缓存（会话级）：QNN 不可用/非 QNN 模型变体/库缺失时，首次失败后不再重试 */
    @Volatile
    private var mnnNpuFailed: Boolean = false

    /** MNN GPU 初始化失败缓存（会话级）：OpenCL 不可达时，首次失败后回退 MNN_CPU */
    @Volatile
    private var mnnGpuFailed: Boolean = false

    /** 是否有推理正在进行（[generate] 已进入未结束）。供 [release] 判定是否需延迟释放、
     *  性能浮窗决定是否取 native 实时 tps。*/
    @Volatile
    private var generating: Boolean = false

    /** 生成期间收到 [release] 请求时置位，由 [generate] 的 finally 在生成结束后统一释放。
     *  nativeGenerateStream 现用 stepping（prefill + generate(1) 循环），shouldAbort 命中后 1 token
     *  内即退出，故此延迟释放多为安全网、极少实际触发；保留以应对 prefill 阶段（不可中断）收到 release 的边角。*/
    @Volatile
    private var releasePending: Boolean = false

    /**
     * 各后端"当前已加载模型所用的"配置（路径 / 上下文 / 线程 / 温度 / lookahead）。供 [ensureLoaded] 判定是否需要重载：
     * 同模型同后端但线程/上下文/温度变了（用户在设置页改过）也必须重载。
     *
     * temperature 纳入指纹：MNN 采样器在 load() 内一次性构建，温度改值须重载才生效（见 mnn_jni.cpp）。
     * topP/repeatPenalty 为 AppConfig 常量、不会变，故不纳入指纹（但仍随 initialize 传入在 load 时设置）。
     */
    private data class LoadedConfig(val path: String, val contextLen: Int, val threads: Int, val temperature: Float, val lookahead: Boolean)

    private val configs = mutableMapOf<BackendType, LoadedConfig?>()

    /** 本次 generate 是否触发了模型(重新)加载。 */
    private var reloadedThisCall: Boolean = false

    /**
     * lookahead 仅 CPU 后端生效（JNI 内 cpu-gated），故仅对 CPU 比对；GPU/NPU 忽略 lookahead 变化，
     * 避免在非 CPU 后端上因切换该开关而白白重载多 GB 模型。temperature 对所有后端生效，一律比对。
     */
    private fun needsReload(
        cfg: LoadedConfig?, path: String, contextLen: Int, threads: Int,
        temperature: Float, lookahead: Boolean, type: BackendType,
    ): Boolean {
        if (cfg == null) return true
        if (cfg.path != path || cfg.contextLen != contextLen || cfg.threads != threads) return true
        if (cfg.temperature != temperature) return true
        if (type == BackendType.MNN_CPU && cfg.lookahead != lookahead) return true
        return false
    }

    /** 后端类型 -> 实例 */
    private fun backendFor(type: BackendType): InferenceBackend = when (type) {
        BackendType.MNN_CPU -> mnnCpuBackend
        BackendType.MNN_GPU -> mnnGpuBackend
        BackendType.MNN_NPU -> mnnNpuBackend
    }

    /** 该类型在运行时是否为候选（MNN_CPU 恒保留作兜底） */
    private fun isBackendCandidate(type: BackendType): Boolean = when (type) {
        BackendType.MNN_CPU -> mnnCpuSupported
        BackendType.MNN_GPU -> mnnGpuSupported
        BackendType.MNN_NPU -> mnnNpuSupported
    }

    /** 偏好映射到具体后端类型 */
    private fun preferredType(preference: BackendPreference): BackendType? = when (preference) {
        BackendPreference.AUTO -> null
        BackendPreference.MNN_CPU -> BackendType.MNN_CPU
        BackendPreference.MNN_GPU -> BackendType.MNN_GPU
        BackendPreference.MNN_NPU -> BackendType.MNN_NPU
    }

    /**
     * 按偏好解析「尝试顺序」。
     *
     * NPU 仅在用户**显式选择** [BackendPreference.MNN_NPU] 时进入链；AUTO 不含 NPU--直接 QNN HTP
     * 在非 root 锁定设备会原生崩在 `PipelineModule::load`（SIGSEGV 不可 catch，回退链失效，详见
     * memory `mnn-qnn-htp-selinux-blocked`），故 AUTO 默认走 [MNN_GPU, MNN_CPU]，避免每条消息都崩。
     * 显式选 NPU 时：NPU 就绪则 [NPU, GPU, CPU]，否则被 [isBackendCandidate] 过滤降级到 [GPU, CPU]。
     * 偏好类型若在链中则置首，其余按链兜底；MNN_CPU 恒在列。
     */
    fun backendOrder(preference: BackendPreference): List<BackendType> {
        val preferred = preferredType(preference)
        val baseChain = if (preferred == BackendType.MNN_NPU) {
            listOf(BackendType.MNN_NPU, BackendType.MNN_GPU, BackendType.MNN_CPU)
        } else {
            listOf(BackendType.MNN_GPU, BackendType.MNN_CPU)
        }
        val chain = baseChain.filter { it == BackendType.MNN_CPU || isBackendCandidate(it) }
        return if (preferred != null && chain.contains(preferred)) {
            listOf(preferred) + chain.filter { it != preferred }
        } else {
            chain
        }
    }

    /** 期望后端（尝试顺序中的首个） */
    fun desiredBackend(preference: BackendPreference): BackendType =
        backendOrder(preference).first()

    /** 会话级失败缓存是否命中（命中则跳过该后端，避免每条消息重载多 GB 模型再回退） */
    private fun isSessionFailed(type: BackendType): Boolean = when (type) {
        BackendType.MNN_NPU -> mnnNpuFailed
        BackendType.MNN_GPU -> mnnGpuFailed
        else -> false
    }

    private fun markSessionFailed(type: BackendType) {
        when (type) {
            BackendType.MNN_NPU -> mnnNpuFailed = true
            BackendType.MNN_GPU -> mnnGpuFailed = true
            else -> {}
        }
    }

    /**
     * 执行一次流式推理（含回退链）。
     *
     * @param modelPath `.mnn` 目录的 config.json 路径
     * @param messages 完整对话历史（MNN 后端由模型自带模板格式化）
     * @param preference 用户后端偏好
     * @param enableThinking 是否启用深度思考。透传给 MNN set_config 的 jinja context `enable_thinking`，
     *        运行时生效（无需重载）：false 时推理模型跳过 `<think>` 推理段直接作答。
     */
    suspend fun generate(
        modelPath: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        contextLen: Int,
        threads: Int,
        preference: BackendPreference,
        lookahead: Boolean,
        enableThinking: Boolean,
        onToken: (String) -> Boolean,
    ): GenerationResult {
        val order = backendOrder(preference)
        Log.i(TAG, "后端尝试顺序: $order (pref=$preference)")
        var lastError: Exception? = null
        reloadedThisCall = false
        generating = true
        try {
            for (type in order) {
                if (isSessionFailed(type)) continue

                // 切到此后端前，释放可能驻留的其他后端模型，避免两套模型同时占内存
                releaseOthers(keep = type)

                val ok = try {
                    ensureLoaded(type, modelPath, contextLen, threads, temperature, topP, repeatPenalty, lookahead)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    lastError = e
                    false
                }

                if (!ok) {
                    markSessionFailed(type)
                    runCatching { releaseBackend(type) }
                    continue
                }

                try {
                    val backend = backendFor(type)
                    // 提前标记当前后端：供性能浮窗在生成中查询此后端的 native 指标（tps 等）。
                    // 失败回退时会被下一个成功后端覆盖；全失败时无生成，指标亦无意义。
                    lastUsedBackend = type
                    // MNN 后端用模型自带 chat 模板格式化消息列表
                    val text = backend.generateStreamMessages(
                        messages, maxTokens, temperature, topP, repeatPenalty, enableThinking, onToken,
                    )
                    return GenerationResult(text, type, reloadedThisCall)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.w(TAG, "$type 生成失败，尝试下一后端: ${e.message}")
                    markSessionFailed(type)
                    lastError = e
                    runCatching { releaseBackend(type) }
                }
            }

            // 所有后端均失败：末端兜底（MNN_CPU）的异常保留在 lastError 中，向上冒泡
            throw lastError ?: IllegalStateException("所有后端均初始化失败")
        } finally {
            // 与 [release] 互斥：原子地清 generating 并取走 releasePending，决定是否本轮释放。
            // 此时 native 调用已返回（finally 在 generateStreamMessages 之后），释放安全。
            val pending: Boolean
            synchronized(lifecycleLock) {
                generating = false
                pending = releasePending
                releasePending = false
            }
            if (pending) {
                runCatching { doReleaseAll() }
            }
        }
    }

    /** 中断当前推理（所有 MNN 后端都设置 abort 标志） */
    suspend fun stopGeneration() {
        mnnCpuBackend.stopGeneration()
        mnnGpuBackend.stopGeneration()
        mnnNpuBackend.stopGeneration()
    }

    /** 非挂起中断：直接设置所有 MNN 后端的 abort 标志（供 ChatProvider.cancel 等非 suspend 调用方） */
    fun cancel() {
        mnnCpuBackend.cancelNow()
        mnnGpuBackend.cancelNow()
        mnnNpuBackend.cancelNow()
    }

    /** 当前活跃后端的指标（按 [lastUsedBackend] 取） */
    fun getActiveMetrics(): BackendMetrics = backendFor(lastUsedBackend).getBackendMetrics()

    /** 当前是否有推理在进行（供性能浮窗决定取 native 实时 tps 还是归零）*/
    fun isGenerating(): Boolean = generating

    /** 释放所有 MNN 后端资源。
     *
     * 推理进行中时**延迟释放**作为安全网：[nativeGenerateStream] 现用 stepping 解码（prefill + generate(1)
     * 循环），shouldAbort 命中后 1 token 内退出，decode 阶段中断极快；但 prefill 阶段（单次阻塞）不可
     * 中断，其进行中收到 release 仍需等其返回。故生成中仅置 [releasePending]，由 [generate] 的 finally
     * 在生成结束（JNI 已返回）后执行 [doReleaseAll]。典型场景：用户在流式回复进行中到模型管理页删除当前
     * 模型——删除立即返回（文件可删，mmap 的 inode 仍在），句柄在当前回复跑完后释放。
     * 非生成态立即释放。*/
    fun release() {
        // 与 [generate] 的 finally 互斥：要么见 generating=true 置 pending（由 generate finally 释放），
        // 要么见 generating=false 立即释放。二者原子，避免「release 见生成中置 pending、但 generate
        // finally 已读过 pending=false」的漏释放竞态。
        val defer: Boolean
        synchronized(lifecycleLock) {
            if (generating) {
                releasePending = true
                defer = true
            } else {
                defer = false
            }
        }
        if (defer) {
            Log.i(TAG, "release: 推理进行中，延迟释放（生成结束后执行）")
        } else {
            doReleaseAll()
        }
    }

    private val lifecycleLock = Any()

    /** 实际释放全部后端 + 清配置。synchronized 防并发 release（如 delete + 再次 delete）双重释放。*/
    private fun doReleaseAll() {
        synchronized(lifecycleLock) {
            mnnCpuBackend.release()
            mnnGpuBackend.release()
            mnnNpuBackend.release()
            configs.clear()
        }
    }

    /** 重置会话级后端失败缓存（[mnnGpuFailed]/[mnnNpuFailed]）。
     *  用户显式切换后端偏好时调用，让被选后端重新尝试——否则一旦某后端失败，整个会话期都不再重试，
     *  即便用户主动切到它也只会被跳过。显式切换本身是用户意图，承担一次可能的失败重试开销合理。*/
    fun resetSessionFailures() {
        mnnNpuFailed = false
        mnnGpuFailed = false
        Log.i(TAG, "会话级后端失败缓存已重置")
    }

    // ===== 内部：加载确保 / 释放 =====

    /** 确保指定后端已加载指定模型（同模型同配置则复用，否则重载）。失败返回 false。 */
    private suspend fun ensureLoaded(
        type: BackendType, modelPath: String, contextLen: Int, threads: Int,
        temperature: Float, topP: Float, repeatPenalty: Float, lookahead: Boolean,
    ): Boolean {
        val backend = backendFor(type)
        val cfg = configs[type]
        if (backend.isModelLoaded && !needsReload(cfg, modelPath, contextLen, threads, temperature, lookahead, type)) {
            return true
        }
        val ok = backend.initialize(modelPath, contextLen, threads, lookahead, temperature, topP, repeatPenalty)
        if (ok) {
            configs[type] = LoadedConfig(modelPath, contextLen, threads, temperature, lookahead)
            reloadedThisCall = true
        } else {
            configs[type] = null
        }
        return ok
    }

    /** 释放 [keep] 以外的已加载后端模型，避免切换后两套模型同时占内存 */
    private suspend fun releaseOthers(keep: BackendType) {
        if (keep != BackendType.MNN_CPU && mnnCpuBackend.isModelLoaded) {
            runCatching { mnnCpuBackend.release() }
            configs[BackendType.MNN_CPU] = null
        }
        if (keep != BackendType.MNN_GPU && mnnGpuBackend.isModelLoaded) {
            runCatching { mnnGpuBackend.release() }
            configs[BackendType.MNN_GPU] = null
        }
        if (keep != BackendType.MNN_NPU && mnnNpuBackend.isModelLoaded) {
            runCatching { mnnNpuBackend.release() }
            configs[BackendType.MNN_NPU] = null
        }
    }

    private fun releaseBackend(type: BackendType) {
        when (type) {
            BackendType.MNN_CPU -> { mnnCpuBackend.release(); configs[type] = null }
            BackendType.MNN_GPU -> { mnnGpuBackend.release(); configs[type] = null }
            BackendType.MNN_NPU -> { mnnNpuBackend.release(); configs[type] = null }
        }
    }

    data class GenerationResult(
        val text: String,
        val usedBackend: BackendType,
        /** 本次推理是否触发了模型(重新)加载（冷启动首条 / 配置变更 / 后端切换均为 true）。 */
        val reloaded: Boolean = false,
    )

    companion object {
        private const val TAG = "BackendManager"
    }
}
