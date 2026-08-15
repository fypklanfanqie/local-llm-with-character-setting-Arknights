package com.rhodesisland.terminal.llm.backend

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.llm.CpuBoostController
import com.rhodesisland.terminal.llm.GenerationExecutionControl
import com.rhodesisland.terminal.llm.InferenceSessionController
import com.rhodesisland.terminal.llm.backend.MnnBackend.MnnMode
import com.rhodesisland.terminal.llm.metrics.CompletionReason
import com.rhodesisland.terminal.llm.profile.BackendAttempt
import com.rhodesisland.terminal.llm.profile.DowngradeReason
import com.rhodesisland.terminal.llm.profile.ResolvedInferencePlan
import com.rhodesisland.terminal.llm.profile.RuntimeVariant
import com.rhodesisland.terminal.llm.metrics.InferenceTurnRecord
import com.rhodesisland.terminal.llm.metrics.NativeGenerationSummary
import com.rhodesisland.terminal.llm.template.ThinkingOutputClassifier
import com.rhodesisland.terminal.llm.thinking.ThinkingPolicyTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.chatbyyourside.llm.backend.MnnBridge

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
    /** Task 3：后端健康协调器（AppContainer 注入真实实例；测试可传 null 或 fake）。 */
    private val healthCoordinator: BackendHealthCoordinator? = null,
    /** 后端工厂（Task 3 review M-3 测试注入点）：默认真实 [MnnBackend]；JVM 单测注入 fake
     *  [InferenceBackend] 以驱动 attempt 成功/失败路径（真实 MnnBackend 依赖 native，无法纯 JVM 构造）。 */
    private val backendFactory: (MnnMode) -> InferenceBackend = { mode ->
        MnnBackend(context, mode, cpuBoostController)
    },
) {
    private val selector = BackendSelector(context)
    /** 本地推理保活：生成期间启动前台服务 + WakeLock，防国产 ROM 杀进程/冻结（见 InferenceSessionController）。 */
    private val inferenceSession = InferenceSessionController(context)
    /** 整次请求（加载 + fallback + JNI）串行，防新请求改写旧请求共享的 abort/lifecycle 状态。 */
    private val generationMutex = Mutex()
    private val mnnCpuBackend: InferenceBackend = backendFactory(MnnMode.CPU)
    private val mnnGpuBackend: InferenceBackend = backendFactory(MnnMode.GPU_OPENCL)
    private val mnnNpuBackend: InferenceBackend = backendFactory(MnnMode.NPU_QNN)

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

    /** config.json 指纹惰性缓存（final review M-6）：按模型路径键控，路径不变则指纹不变。
     *  generate 由 [generationMutex] 串行，无需并发同步；[doReleaseAll]（模型删除/冷启动释放）清空。 */
    @Volatile
    private var fingerprintCachePath: String? = null
    @Volatile
    private var fingerprintCacheValue: String = ""

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
    /** 本次 generate 是否触发了模型(重新)加载。 */
    private var reloadedThisCall: Boolean = false

    /** 加载结果类型（Task 1 遥测）：复用 / 首次冷加载 / 配置变化重载。 */
    private enum class LoadKind { REUSE, COLD, WARM }

    /** 最近一次 ensureAttemptLoaded 的结果（generationMutex 单飞内读取，无需同步）。 */
    private var lastLoadKind: LoadKind = LoadKind.REUSE
    private var lastLoadMs: Long = 0L

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
     * @param batchMaxBytes native 流式批处理缓冲上限（字节）；Balanced 默认 256，Task 6 性能模式接入后
     *        由 [com.rhodesisland.terminal.llm.profile.InferencePerformanceMode] 解析覆盖。
     * @param batchMaxMs native 流式批处理缓冲时间上限（ms）；Balanced 16。
     */
    /**
     * 执行一次流式推理（Task 7）：按 [ResolvedInferencePlan.attempts] 显式执行后端尝试。
     *
     * 运行时配置（线程/上下文/采样/变体枚举）全部由 plan 的各 [BackendAttempt] 承载；
     * 流式批处理阈值取 plan.streamPolicy。CPU 优化失败推进到 CPU 兼容（不黑名单 CPU）；
     * 首个可见 delta 后禁止透明换后端（见 [GenerationExecutionControl]）。
     *
     * @param modelPath `.mnn` 目录的 config.json 路径
     * @param resolvedPlan [InferenceProfileResolver] 生成的不可变执行计划（必填）
     * @param onToken 流式回调；返回 false 触发策略截断
     */
    suspend fun generate(
        modelPath: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        enableThinking: Boolean,
        onToken: (String) -> Boolean,
        downgradeReasons: List<String> = emptyList(),
        // Task 2：思考请求 / 模板能力（生成前已知，信封透传）+ 分类器实例（MnnBackend 在 finally
        // 内收口分类并入 finalize；取代原 provider 侧补记路径，避免加载期/首 attempt 前取消时
        // 补记到上一轮记录）。带默认值，旧调用方不受影响。
        thinkingRequested: Boolean? = null,
        templateCapability: String? = null,
        thinkingClassifier: ThinkingOutputClassifier? = null,
        // Task 5：本地思考档位策略快照（单次透传；思考关闭/云端为 null）。
        thinkingPolicy: ThinkingPolicyTelemetry? = null,
        executionControl: GenerationExecutionControl? = null,
        resolvedPlan: ResolvedInferencePlan? = null,
        // Task 4：输出策略（GPU 首 delta 前空输出回退 CPU 等；带默认值，旧调用方不受影响——
        // 默认 DISABLED 使未显式启用的调用保持既有行为）。
        outputPolicy: GenerationOutputPolicy = GenerationOutputPolicy(),
        // Task 6：native decode 步长（1=逐 token 默认；2..4=多 token 步进，native clamp 到 [1,4]）。
        // 生成期参数（不参与模型加载指纹）；LocalChatProvider 从 resolvedPlan.decodeStepTokens 传入
        // （该值已经 resolver 认证门禁：未认证组合恒为 1）。
        // Task 6 review I-3：步进认证仅对 CPU_OPTIMIZED 变体生效（见 generate 内按变体守卫），
        // GPU/兼容变体恒 1；GPU 步进认证留未来扩展。
        // final review C1：v2 capability 门禁见 generate 内 [effectiveDecodeStepTokens]。
        decodeStepTokens: Int = 1,
    ): GenerationResult = generationMutex.withLock {
        val plan = resolvedPlan ?: throw IllegalStateException("Task 7 起 generate 必须提供 resolvedPlan")
        val attempts = plan.attempts
        if (attempts.isEmpty()) throw IllegalStateException("resolvedPlan.attempts 为空")
        // final review C1：v2 capability 门禁。旧 native（握手缺席或无 summary_v2 能力）会静默
        // 忽略 nativeGenerateStream 多余的 decodeStepTokens 栈参数（JNI 按符号名解析、不校验实参
        // 个数），本地构建 APK 将静默跑 v1 语义。门禁把「静默忽略」显式化：强制回落 1（v1 语义
        // 完全可用，仅 v2 步进增强不可用），并打警告日志便于检出陈旧 native 产物（需重编部署）。
        val effectiveDecodeStepTokens = if (MnnBridge.hasSummaryV2Capability) {
            decodeStepTokens
        } else {
            if (decodeStepTokens > 1) {
                Log.w(TAG, "native 未包含 summary_v2（旧构建，需重编部署），v2 步进已禁用")
            }
            1
        }
        // Task 3：健康记录键的模型指纹（config.json 内容 SHA-256 前 16 hex）；模型替换 -> 新指纹 ->
        // 旧健康记录自然失效。仅 GPU 失败/成功路径消费，CPU 恒兜底不记录。
        // final review M-6：惰性缓存（按路径键控）——每轮 generate 不再重读 config.json。
        val modelFingerprint = cachedModelConfigFingerprint(modelPath)
        Log.i(TAG, "执行计划: attempts=${attempts.joinToString { it.variant.name }} req=${plan.requestedMode}")
        var lastError: Exception? = null
        // 各尝试失败原因（变体名 + 诊断信息），全失败时汇总报错。
        val failureReasons = mutableListOf<String>()
        // Task 4：传给 generateStreamMessages 的遥测降级原因（裁决 7）——GPU 空输出回退发生后，
        // 把 EMPTY_GPU_OUTPUT_FALLBACK 并入后续 attempt（CPU 兜底）的遥测记录；GPU attempt 自身的
        // 遥测已在 MnnBackend finally 收口，无法追溯修改，故原因随下一 attempt 落盘。
        var attemptDowngradeReasons = downgradeReasons
        // Task 4：最后一次「GPU 空输出可回退」的 (摘要, 后端, 完成原因)——链末端兜底：
        // 回退后循环耗尽（计划仅含 GPU attempt / 后续 attempt 全失败）时原样返回该 GPU 空结果，
        // 不误报「所有后端尝试均失败」（旧行为即返回该结果，回退只是新增一次 CPU 重试机会）。
        var lastEmptyFallback: Triple<NativeGenerationSummary?, BackendType, CompletionReason?>? = null
        val effectiveBatchBytes = plan.streamPolicy.batchMaxBytes
        val effectiveBatchMs = plan.streamPolicy.batchMaxMs
        reloadedThisCall = false
        synchronized(lifecycleLock) {
            generating = true
            // LocalChatProvider 在调用本方法前已注册 request control：提前取消体现在 reason；否则清上轮残留 abort。
            MnnBridge.abort = executionControl?.reason() != null
        }
        // 本地推理保活：前台服务 + WakeLock 覆盖「模型加载 + prefill + 生成」全程。
        inferenceSession.begin(attempts.first().variant.name)
        try {
            for ((attemptIndex, attempt) in attempts.withIndex()) {
                if (executionControl?.canTryNextBackend() == false) break
                // 会话级失败黑名单（GPU/NPU；CPU 不黑名单）：命中则跳过该尝试，避免每轮重载再失败。
                if (isSessionFailed(attempt.backend)) continue

                // 切到此后端前，释放可能驻留的其他后端模型，避免两套模型同时占内存。
                releaseOthers(keep = attempt.backend)

                val ok = try {
                    ensureAttemptLoaded(attempt, modelPath)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    lastError = e
                    false
                }

                if (!ok) {
                    val reason = backendFor(attempt.backend).lastErrorMessage ?: lastError?.message ?: "初始化失败"
                    failureReasons += "${attempt.variant.name}: $reason"
                    Log.w(TAG, "${attempt.variant.name} 初始化失败: $reason")
                    // CPU 优化失败推进到 CPU 兼容（下一变体），不黑名单 CPU；GPU/NPU 失败记会话级黑名单。
                    if (attempt.backend == BackendType.MNN_GPU) {
                        markSessionFailed(BackendType.MNN_GPU)
                        // Task 15：GPU 加载失败回退 CPU 的可见原因（并入后续 CPU attempt 的遥测）。
                        attemptDowngradeReasons = (attemptDowngradeReasons + DowngradeReason.GPU_LOAD_FALLBACK.name).distinct()
                    }
                    if (attempt.backend == BackendType.MNN_NPU) markSessionFailed(BackendType.MNN_NPU)
                    // Task 3：加载失败叠加持久 LOAD 类别健康记录（非 CPU 后端；CPU 恒兜底不记，
                    // 与 markSessionFailed 的「CPU 不黑名单」语义一致）。Task 3 review M-4：
                    // 健康记录是旁路，写失败（如 DataStore I/O）不得使整次 generate 失败。
                    if (attempt.backend != BackendType.MNN_CPU) {
                        recordHealthWrite("afterLoadFailure") {
                            healthCoordinator?.afterLoadFailure(attempt.backend, attempt.variant, modelFingerprint)
                        }
                    }
                    runCatching { releaseBackend(attempt.backend) }
                    continue
                }

                if (executionControl?.canTryNextBackend() == false) break
                val attemptMaxTokens = executionControl?.remainingTokens() ?: maxTokens
                if (attemptMaxTokens <= 0) break

                try {
                    val backend = backendFor(attempt.backend)
                    // 提前标记当前后端：供性能浮窗在生成中查询此后的 native 指标（tps 等）。
                    lastUsedBackend = attempt.backend
                    // Task 6 review I-3：步进认证按变体守卫——plan 级 decodeStepTokens 是
                    // CPU_OPTIMIZED（基准认证变体）的证据；OPENCL（GPU 无步进认证）与
                    // CPU_COMPATIBILITY（兜底，非基准配置）恒 1，防止认证证据错配
                    // （CPU_OPTIMIZED 的 step 证据作用于 GPU/兼容变体）。GPU 步进认证留未来扩展。
                    // final review C1：再叠 v2 capability 门禁（旧 native 强制 1，见 generate 顶部）。
                    val step = if (attempt.variant == RuntimeVariant.CPU_OPTIMIZED) effectiveDecodeStepTokens else 1
                    val summary = backend.generateStreamMessages(
                        messages, attemptMaxTokens, temperature, topP, repeatPenalty, enableThinking, onToken,
                        effectiveBatchBytes, effectiveBatchMs, attemptDowngradeReasons, executionControl,
                        plan.powerPolicy,
                        // Task 1 遥测：性能模式 / 实际配置指纹 / 尝试链 / 加载耗时（冷/热区分）。
                        requestedMode = plan.requestedMode,
                        effectiveMode = plan.effectiveMode,
                        loadConfigHash = attempt.loadConfigHash,
                        attemptTrace = plan.attempts.map { it.variant.name },
                        coldLoadMs = if (lastLoadKind == LoadKind.COLD) lastLoadMs else null,
                        warmLoadMs = if (lastLoadKind == LoadKind.WARM) lastLoadMs else null,
                        // Task 2：思考请求 / 模板能力信封透传 + 分类器实例（分类在 finally 内收口并入 finalize）。
                        thinkingRequested = thinkingRequested,
                        templateCapability = templateCapability,
                        thinkingClassifier = thinkingClassifier,
                        // Task 5：本地思考档位策略快照单次透传。
                        thinkingPolicy = thinkingPolicy,
                        // Task 6：透传认证门禁后的 decode 步长（未认证组合为 1；按变体守卫见上）。
                        decodeStepTokens = step,
                        // Task 15：内存准入的上下文降级（配置值 -> 实际值；未降级时二者等值）。
                        configuredContextTokens = plan.configuredContextTokens ?: plan.contextTokens,
                        actualContextTokens = plan.contextTokens,
                    )
                    val completionReason = executionControl?.reason()
                        ?: (backend as? MnnBackend)?.lastTurnRecord?.completionReason
                        ?: summary?.completionReason?.let(CompletionReason::valueOf)
                    // Task 4：首 delta 前 GPU 空输出回退 CPU。
                    // - 判定**消费**（不重新分类）MnnBackend finally 内已收口的分类器结果
                    //   （ThinkingOutputClassifier.lastEmptyResponseClass），绝不再调 finish；
                    // - 请求级终止（取消/超时/热停/策略截断/后端失败已 requestStop）时
                    //   executionControl?.reason() != null：既有 canTryNextBackend()==false 检查
                    //   （循环顶/生成前）与本守卫双重拒绝回退，绝不覆盖既有 requestStop 路径；
                    // - 不调 afterGenerationFailure：空输出非异常（模板/模型行为），Task 3 已定
                    //   「空输出由分类器判定、不触发 health 降级」；本回退对健康统计零副作用
                    //   （连 markModelOk 也不写——被丢弃的空结果不足以证明 GPU 可用）；
                    // - 不显式 release：attempt 循环顶部 releaseOthers(keep=CPU) 在推进到 CPU
                    //   attempt 时已释放 GPU，杜绝双驻留（裁决 5）；
                    // - 零 delta（callbackBytes==0）才回退：onToken 从未输出，回退不拼接任何文本
                    //   （裁决 6）；首 delta 后失败走既有 requestStop(BACKEND_FAILURE) catch 路径。
                    if (executionControl?.reason() == null &&
                        GenerationOutcomeClassifier.isEligibleForCpuFallback(
                            policy = outputPolicy,
                            backend = attempt.backend,
                            completionReason = completionReason,
                            emptyResponseClass = thinkingClassifier?.lastEmptyResponseClass,
                            generatedTokens = summary?.generatedTokens ?: 0,
                            callbackBytes = summary?.callbackBytes ?: 0L,
                            thinkingRequested = thinkingRequested ?: false,
                        )
                    ) {
                        if (attemptIndex < attempts.lastIndex) {
                            Log.w(
                                TAG,
                                "GPU 空输出回退 CPU: reason=${summary?.completionReason} " +
                                    "class=${thinkingClassifier?.lastEmptyResponseClass}",
                            )
                        } else {
                            // 链末端（无后续 CPU attempt）：并非回退，只是记录结果供循环后原样返回。
                            Log.w(
                                TAG,
                                "GPU 空输出且无 CPU 可回退，原样返回: reason=${summary?.completionReason} " +
                                    "class=${thinkingClassifier?.lastEmptyResponseClass}",
                            )
                        }
                        // 裁决 7：回退原因并入后续 attempt 的遥测 downgradeReasons。
                        attemptDowngradeReasons = (attemptDowngradeReasons + EMPTY_GPU_OUTPUT_FALLBACK).distinct()
                        // 链末端兜底：记录本次可回退结果，循环耗尽时原样返回（见循环后判定）。
                        lastEmptyFallback = Triple(summary, attempt.backend, completionReason)
                        continue
                    }
                    // Task 3 review I-1：仅「完成一次非错误生成」的字面语义才升 MODEL_OK——
                    // 完成原因须在 {EOS, MAX_TOKENS, POLICY_TRUNCATION} 内。USER_CANCEL/TIMEOUT/
                    // THERMAL_STOP 是中断（requestStop 提前返回、不抛异常），不代表后端已证明可用；
                    // 若也标记，持续挂起（watchdog 超时但从不抛异常）的 OpenCL 每轮都被重标可用、
                    // 永不进入冷却升级，健康记录恒为「已证明可用」的谎言状态。null/其它原因同样不记。
                    // M-1：与失败路径一致，CPU 恒兜底不记录（CPU-only 设备不再每轮白写 CPU 键
                    // MODEL_OK，DataStore 全量重编码 + 磁盘写位于 generationMutex 内、返回前）。
                    // M-4：健康记录是旁路，写失败不得被误判为生成失败（否则会触发回退 + 黑名单）。
                    if (completionReason in COMPLETED_REASONS && attempt.backend != BackendType.MNN_CPU) {
                        recordHealthWrite("markModelOk") {
                            healthCoordinator?.markModelOk(attempt.backend, attempt.variant, modelFingerprint)
                        }
                    }
                    return@withLock GenerationResult(
                        summary = summary,
                        usedBackend = attempt.backend,
                        reloaded = reloadedThisCall,
                        completionReason = completionReason,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    // Task 3：生成异常才记持久 GENERATION 失败（非 CPU 后端；CPU 恒兜底不记）。
                    // 取消/超时/热停是 requestStop 提前返回的路径，不抛异常，不会进入本分支；
                    // CancellationException 已在上面单独 rethrow——故此处只可能是真实后端异常。
                    // Task 3 review M-4：健康记录是旁路，写失败不得让整次 generate 失败。
                    if (attempt.backend != BackendType.MNN_CPU) {
                        recordHealthWrite("afterGenerationFailure") {
                            healthCoordinator?.afterGenerationFailure(attempt.backend, attempt.variant, modelFingerprint)
                        }
                    }
                    if (executionControl != null && executionControl.remainingTokens() < maxTokens) {
                        // 已有可见输出后禁止透明换后端，否则两个模型的 delta 会拼成一条且 KV/语义均失配。
                        executionControl.requestStop(CompletionReason.BACKEND_FAILURE)
                    }
                    if (executionControl?.canTryNextBackend() == false) {
                        return@withLock GenerationResult(
                            usedBackend = attempt.backend,
                            reloaded = reloadedThisCall,
                            completionReason = executionControl.reason(),
                        )
                    }
                    Log.w(TAG, "${attempt.variant.name} 生成失败，尝试下一后端: ${e.message}")
                    failureReasons += "${attempt.variant.name}: 生成失败 - ${e.message}"
                    if (attempt.backend == BackendType.MNN_GPU) {
                        markSessionFailed(BackendType.MNN_GPU)
                        // Task 15：GPU 生成异常回退 CPU 的可见原因（并入后续 attempt 遥测）。
                        attemptDowngradeReasons = (attemptDowngradeReasons + DowngradeReason.GPU_GENERATION_FALLBACK.name).distinct()
                    }
                    if (attempt.backend == BackendType.MNN_NPU) markSessionFailed(BackendType.MNN_NPU)
                    lastError = e
                    runCatching { releaseBackend(attempt.backend) }
                }
            }

            executionControl?.reason()?.let { reason ->
                return@withLock GenerationResult(
                    usedBackend = lastUsedBackend,
                    reloaded = reloadedThisCall,
                    completionReason = reason,
                )
            }

            // Task 4 链末端兜底：GPU 空输出回退后循环耗尽（计划仅含 GPU attempt，或后续 attempt
            // 加载/生成全部失败）时，原样返回最后一次可回退的 GPU 空结果。回退本身只是新增一次
            // CPU 重试机会，不应把「可回退的空输出」误报成「所有后端尝试均失败」（测试清单 e）。
            lastEmptyFallback?.let { (summary, backend, completionReason) ->
                return@withLock GenerationResult(
                    summary = summary,
                    usedBackend = backend,
                    reloaded = reloadedThisCall,
                    completionReason = completionReason,
                )
            }

            // 所有尝试均失败：汇总各变体原因详细报错。
            val detail = if (failureReasons.isEmpty()) "所有后端尝试均初始化失败"
                else "本地模型加载失败（所有后端尝试均失败）。${failureReasons.joinToString("；")}"
            Log.e(TAG, detail)
            throw lastError?.let { IllegalStateException(detail, it) } ?: IllegalStateException(detail)
        } finally {
            // 本地推理保活收尾：结束前台服务 + 释放 WakeLock（幂等；异常吞掉，不影响生成结果返回）。
            runCatching { inferenceSession.end() }
            // 与 [release] 互斥：原子地清 generating 并取走 releasePending，决定是否本轮释放。
            val pending: Boolean
            synchronized(lifecycleLock) {
                generating = false
                MnnBridge.abort = false
                pending = releasePending
                releasePending = false
            }
            if (pending) {
                runCatching { doReleaseAll() }
            }
        }
    }

    /**
     * 单阶段本地生成的 runner adapter（Task 2）：把 [LocalGenerationRequest] 原样转发到
     * 公开 [generate] 的既有回退链 / attempt 执行语义，**不改变任何公共行为**。
     * 生产 [LocalChatProvider] 默认使用本 adapter；测试只测编排，注入 fake runner。
     * 用显式 object 而非 SAM lambda：接口抽象方法为 suspend，SAM 转换不可靠。
     */
    internal fun asLocalGenerationRunner(): LocalGenerationRunner =
        object : LocalGenerationRunner {
            override suspend fun generate(
                request: LocalGenerationRequest,
                executionControl: GenerationExecutionControl,
                onToken: (String) -> Boolean,
            ): GenerationResult = this@BackendManager.generate(
                modelPath = request.modelPath,
                messages = request.messages,
                maxTokens = request.maxTokens,
                temperature = request.temperature,
                topP = request.topP,
                repeatPenalty = request.repeatPenalty,
                enableThinking = request.enableThinking,
                onToken = onToken,
                downgradeReasons = request.downgradeReasons,
                thinkingRequested = request.thinkingRequested,
                templateCapability = request.templateCapability,
                thinkingClassifier = request.thinkingClassifier,
                thinkingPolicy = request.thinkingPolicy,
                executionControl = executionControl,
                resolvedPlan = request.resolvedPlan,
                outputPolicy = request.outputPolicy,
                decodeStepTokens = request.decodeStepTokens,
            )
        }

    /** 中断当前推理（所有 MNN 后端都设置 abort 标志） */
    suspend fun stopGeneration() {
        mnnCpuBackend.stopGeneration()
        mnnGpuBackend.stopGeneration()
        mnnNpuBackend.stopGeneration()
    }

    /** 非挂起中断：原因由请求级 control 先行写入；这里只在活跃生成期发布 abort，不释放 native。 */
    fun cancel() {
        if (!generating) return
        // 后端实例可为注入的 fake（Task 3 review M-3）：cancelNow 是 MnnBackend 独有，
        // 非 MnnBackend 的注入实例无需 abort（fake 无 native 生成）。
        (mnnCpuBackend as? MnnBackend)?.cancelNow()
        (mnnGpuBackend as? MnnBackend)?.cancelNow()
        (mnnNpuBackend as? MnnBackend)?.cancelNow()
    }

    /** 当前活跃后端的指标（按 [lastUsedBackend] 取） */
    fun getActiveMetrics(): BackendMetrics = backendFor(lastUsedBackend).getBackendMetrics()

    /** 当前活跃后端最近一次生成的遥测记录（Task 2）；供 LocalChatResult 汇总。三类后端均为 MnnBackend。 */
    fun lastTurnRecord(): InferenceTurnRecord? =
        (backendFor(lastUsedBackend) as? MnnBackend)?.lastTurnRecord

    // ===== Task 15/16：旁路操作（GPU 预热等）的「最近一次聊天」诊断保护 =====

    @Volatile
    private var stashedTurnForSideOp: Pair<BackendType, InferenceTurnRecord?>? = null

    /** 旁路操作前保存「最近一次聊天」诊断记录（含所属后端）。 */
    fun stashLastTurnForSideOp() {
        val type = lastUsedBackend
        stashedTurnForSideOp = type to (backendFor(type) as? MnnBackend)?.lastTurnRecord
    }

    /** 旁路操作后恢复被覆盖的「最近一次聊天」诊断记录（无 stash 时 no-op）。 */
    fun restoreLastTurnAfterSideOp() {
        val s = stashedTurnForSideOp ?: return
        stashedTurnForSideOp = null
        (backendFor(s.first) as? MnnBackend)?.restoreLastTurnRecord(s.second)
    }

    /** 当前是否有推理在进行（供性能浮窗决定取 native 实时 tps 还是归零）*/
    fun isGenerating(): Boolean = generating

    /** 指定模型（config.json 路径）是否已在任一后端驻留。供内存准入避免对已驻留权重重复计入。 */
    fun isModelResident(modelPath: String): Boolean =
        mnnCpuBackend.currentModelPath == modelPath ||
            mnnGpuBackend.currentModelPath == modelPath ||
            mnnNpuBackend.currentModelPath == modelPath

    /** 释放所有 MNN 后端资源。
     *
     * 推理进行中时**延迟释放**作为安全网：[nativeGenerateStream] 现用 stepping 解码（prefill + generate(1)
     * 循环），shouldAbort 命中后 1 token 内退出，decode 阶段中断极快；但 prefill 阶段（单次阻塞）不可
     * 中断，其进行中收到 release 仍需等其返回。故生成中仅置 [releasePending]，由 [generate] 的 finally
     * 在生成结束（JNI 已返回）后执行 [doReleaseAll]。典型场景：用户在流式回复进行中到模型管理页删除当前
     * 模型——删除立即返回（文件可删，mmap 的 inode 仍在），句柄在当前回复跑完后释放。
     * 非生成态立即释放。*/
    fun release() {
        // 与 [generate] 的 finally 在同一把 lifecycleLock 内原子判定 + 释放：要么见 generating=true
        // 置 pending（由 generate finally 释放），要么见 generating=false 立即释放。二者在锁内原子，
        // 消除「release 见非生成态后、generate 尚未置 generating=true 前」的 check-then-act 竞态——
        // 避免 doReleaseAll 在 generate 刚加载完新句柄后误销毁它（use-after-free / native crash）。
        synchronized(lifecycleLock) {
            if (generating) {
                releasePending = true
                Log.i(TAG, "release: 推理进行中，延迟释放（生成结束后执行）")
            } else {
                releaseAllLocked()
            }
        }
    }

    private val lifecycleLock = Any()

    /** 实际释放全部后端 + 清配置。synchronized 防并发 release（如 delete + 再次 delete）双重释放。*/
    private fun doReleaseAll() {
        synchronized(lifecycleLock) {
            releaseAllLocked()
        }
    }

    /** 释放全部后端 + 清配置。**调用方必须已持有 [lifecycleLock]**。 */
    private fun releaseAllLocked() {
        mnnCpuBackend.release()
        mnnGpuBackend.release()
        mnnNpuBackend.release()
        // final review M-6：释放即卸载——同路径模型被删除/替换后重下时指纹重算，
        // 避免健康键继续绑定旧模型内容哈希。
        fingerprintCachePath = null
        fingerprintCacheValue = ""
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

    /** 确保指定后端按指定尝试加载（同路径同 loadConfigHash 热复用，否则重载）。失败返回 false。 */
    private suspend fun ensureAttemptLoaded(attempt: BackendAttempt, modelPath: String): Boolean {
        val backend = backendFor(attempt.backend)
        if (backend.isModelLoaded && (backend as? MnnBackend)?.isLoadedWithConfigHash(modelPath, attempt.loadConfigHash) == true) {
            // 热复用：零加载耗时，不纳入冷/热统计。
            lastLoadKind = LoadKind.REUSE
            lastLoadMs = 0L
            return true
        }
        // 区分首次冷加载（此前未加载）与配置变化重载（此前加载过别的配置）。
        val wasLoaded = backend.isModelLoaded
        val t = SystemClock.elapsedRealtime()
        val ok = backend.initialize(modelPath, attempt.nativeConfigJson, attempt.loadConfigHash)
        lastLoadMs = SystemClock.elapsedRealtime() - t
        lastLoadKind = when {
            !ok -> LoadKind.REUSE
            wasLoaded -> LoadKind.WARM
            else -> LoadKind.COLD
        }
        if (ok) reloadedThisCall = true
        return ok
    }

    /** 释放 [keep] 以外的已加载后端模型，避免切换后两套模型同时占内存 */
    private suspend fun releaseOthers(keep: BackendType) {
        if (keep != BackendType.MNN_CPU && mnnCpuBackend.isModelLoaded) {
            runCatching { mnnCpuBackend.release() }
        }
        if (keep != BackendType.MNN_GPU && mnnGpuBackend.isModelLoaded) {
            runCatching { mnnGpuBackend.release() }
        }
        if (keep != BackendType.MNN_NPU && mnnNpuBackend.isModelLoaded) {
            runCatching { mnnNpuBackend.release() }
        }
    }

    private fun releaseBackend(type: BackendType) {
        when (type) {
            BackendType.MNN_CPU -> mnnCpuBackend.release()
            BackendType.MNN_GPU -> mnnGpuBackend.release()
            BackendType.MNN_NPU -> mnnNpuBackend.release()
        }
    }

    /**
     * Task 3 review M-4：健康记录写入统一旁路。
     *
     * 健康记录是旁路数据，任何写入异常（DataStore I/O 失败、协程取消竞态等）都不得影响推理本身：
     * - [afterLoadFailure] 原在 try 外——DataStore edit 抛 IOException 会直接使整次 generate 失败；
     * - [markModelOk] 原在 try 内——抛异常会被下方 catch 误判为 GENERATION 失败，触发回退 + 黑名单；
     * - [afterGenerationFailure] 原在 catch 块内——抛异常会改写整次 generate 的失败形态。
     * 三处一律经本方法包裹，失败仅记日志。
     */
    private suspend fun recordHealthWrite(tag: String, block: suspend () -> Unit) {
        runCatching { block() }
            .onFailure { Log.w(TAG, "健康记录写入失败（忽略，不影响推理）[$tag]: ${it.message}") }
    }

    /**
     * config.json 内容指纹（final review M-6）：按路径惰性缓存，避免每轮 generate 重读整个
     * config.json。路径不变则指纹不变（模型内容替换属非常规操作，由 [doReleaseAll] 清缓存兜底）；
     * 读取失败返回空串且不缓存（下次重试）。
     */
    private fun cachedModelConfigFingerprint(modelPath: String): String {
        val cached = fingerprintCacheValue
        if (fingerprintCachePath == modelPath && cached.isNotEmpty()) return cached
        return modelConfigFingerprint(modelPath).also {
            fingerprintCachePath = modelPath
            fingerprintCacheValue = it
        }
    }

    data class GenerationResult(
        /** native 返回的 GenerationSummary（null=摘要解析失败/未走 native）。全文不再整份携带，
         *  由 LocalChatProvider 作为唯一累加器拼接；此摘要仅供指标/完成原因上报。 */
        val summary: NativeGenerationSummary? = null,
        val usedBackend: BackendType,
        /** 本次推理是否触发了模型(重新)加载（冷启动首条 / 配置变更 / 后端切换均为 true）。 */
        val reloaded: Boolean = false,
        /** 请求级明确终止原因（跨后端 fallback）；优先于单 attempt 摘要。 */
        val completionReason: CompletionReason? = null,
    )

    companion object {
        private const val TAG = "BackendManager"

        /** Task 4：GPU 空输出回退 CPU 的遥测降级原因（并入后续 attempt 的 downgradeReasons）。
         *  internal（Task 5 review M-2）：可靠性基准 [com.rhodesisland.terminal.llm.benchmark.DefaultLocalInferenceBenchmarkRunner]
         *  需按此原因计数回退轮次，引用共享常量而非字面量复制。 */
        internal const val EMPTY_GPU_OUTPUT_FALLBACK = "EMPTY_GPU_OUTPUT_FALLBACK"

        /** Task 3 review I-1：「完成一次非错误生成」的完成原因集合——仅这些记 MODEL_OK。
         *  中断（USER_CANCEL/TIMEOUT/THERMAL_STOP）与后端错误不是完成，不得证明后端可用。 */
        private val COMPLETED_REASONS = setOf(
            CompletionReason.EOS,
            CompletionReason.MAX_TOKENS,
            CompletionReason.POLICY_TRUNCATION,
        )
    }
}
