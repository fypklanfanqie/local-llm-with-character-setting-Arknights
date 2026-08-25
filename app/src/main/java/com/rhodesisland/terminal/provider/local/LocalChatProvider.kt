package com.rhodesisland.terminal.provider.local

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.data.model.AutoBackendModelClass
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.DEFAULT_MNN_MODELS
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.llm.CpuBoostController
import com.rhodesisland.terminal.llm.GenerationExecutionControl
import com.rhodesisland.terminal.llm.LlmMemoryEstimator
import com.rhodesisland.terminal.llm.LocalInferenceAdmissionCoordinator
import com.rhodesisland.terminal.llm.MemoryAdmissionException
import com.rhodesisland.terminal.llm.ModelAdmissionController.AdmissionDecision
import com.rhodesisland.terminal.llm.ModelBundleValidator
import com.rhodesisland.terminal.llm.GenerationSafetyPolicy
import com.rhodesisland.terminal.llm.IncrementalScriptDetector
import com.rhodesisland.terminal.llm.ThermalDecision
import com.rhodesisland.terminal.llm.profile.DowngradeReason
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.profile.InferenceProfileResolver
import com.rhodesisland.terminal.llm.profile.OpenClHealthState
import com.rhodesisland.terminal.llm.InferenceThreadOptimizer
import com.rhodesisland.terminal.llm.PromptWindowPlanner
import com.rhodesisland.terminal.llm.PromptWindowResult
import com.rhodesisland.terminal.llm.ThermalMonitor
import com.rhodesisland.terminal.llm.backend.BackendHealthCoordinator
import com.rhodesisland.terminal.llm.backend.BackendManager
import com.rhodesisland.terminal.llm.backend.BackendManager.GenerationResult
import com.rhodesisland.terminal.llm.backend.BackendPreference
import com.rhodesisland.terminal.llm.backend.BackendType
import com.rhodesisland.terminal.llm.backend.EmptyOutputFallbackPolicy
import com.rhodesisland.terminal.llm.backend.GenerationOutputPolicy
import com.rhodesisland.terminal.llm.backend.LocalGenerationRequest
import com.rhodesisland.terminal.llm.backend.LocalGenerationRunner
import com.chatbyyourside.llm.backend.MnnBridge
import com.rhodesisland.terminal.llm.backend.modelConfigFingerprint
import com.rhodesisland.terminal.llm.benchmark.CertifiedInferenceOptions
import com.rhodesisland.terminal.llm.benchmark.InferenceCertificationStore
import com.rhodesisland.terminal.llm.metrics.CompletionReason
import com.rhodesisland.terminal.llm.profile.RuntimeVariant
import com.rhodesisland.terminal.llm.template.ThinkingOutputClassifier
import com.rhodesisland.terminal.llm.template.ThinkingTemplateCapabilityResolver
import com.rhodesisland.terminal.llm.thinking.LocalThinkingLevel
import com.rhodesisland.terminal.llm.thinking.LocalThinkingPolicyResolver
import com.rhodesisland.terminal.llm.thinking.NativeThinkingBudgetCapability
import com.rhodesisland.terminal.llm.thinking.NativeThinkingBudgetCapabilityResolver
import com.rhodesisland.terminal.llm.thinking.ThinkingPolicyTelemetry
import com.rhodesisland.terminal.llm.thinking.shouldTruncateThinking
import com.rhodesisland.terminal.llm.template.OutputSanityDetector
import com.rhodesisland.terminal.perfmon.BackendType as PerfmonBackendType
import com.rhodesisland.terminal.provider.ChatProvider
import com.rhodesisland.terminal.util.MnnTmpDirJanitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 本地聊天 Provider
 *
 * 调用 MNN 进行推理，支持原生 token 级流式输出。本地 AI 完全免费，无需 API Key。
 *
 * 后端选择：通过 [BackendManager] 按用户偏好（[com.rhodesisland.terminal.llm.backend.BackendPreference]）
 * 在 MNN CPU / OpenCL GPU / QNN NPU 间选择，不可用/失败时按链回退到 MNN_CPU。聊天模板由 MNN 按
 * 各模型自带模板应用（Qwen=ChatML，Llama/Gemma/Phi 各异）。
 *
 * CPU 调度优化 / 温度监控：保留并改接到 MNN。线程数取 min(用户设定, 大核数, 温度上限) 喂给 MNN
 * 的 thread_num（加载时生效）；CPU 提频由 [CpuBoostController] 在 MnnBackend 内包住推理调用。
 */
class LocalChatProvider(
    private val context: Context,
    private val backendManager: BackendManager,
    private val settings: SettingsRepository,
    private val cpuBoostController: CpuBoostController,
    /** Task 3：OpenCL 健康协调器（与 BackendManager 共享同一实例，探测/记录单点，避免两套状态）。 */
    private val healthCoordinator: BackendHealthCoordinator,
    /**
     * Task 7：推理选项认证存储。每轮生成按 device+model+CPU 变体+native 组合查证认证记录
     * （lookahead / 多 token 步进门禁的启用证据，见 [loadCertifiedOptions]）。
     */
    private val certificationStore: InferenceCertificationStore,
) : ChatProvider {

    // ===== CPU 调度优化 / 温度监控（不改 MNN 加载逻辑，仅优化线程数与提频）=====
    // threadOptimizer 须先于 thermalMonitor 初始化（thermalMonitor 的 bigCoreCountProvider 引用它）。
    private val threadOptimizer = InferenceThreadOptimizer()
    private val thermalMonitor = ThermalMonitor(context) { threadOptimizer.getBigCoreCount() }
    private val promptWindowPlanner = PromptWindowPlanner()
    /** Task 15：每轮内存准入协调器（读取系统内存 + 进程 PSS；维护同模型实测峰值校准）。 */
    private val admissionCoordinator = LocalInferenceAdmissionCoordinator(context)

    @Volatile
    private var previousPromptAnchor: String? = null
    /** 当前整次本地生成的请求级控制面；CAS 清理避免旧请求 finally 抹掉新请求。 */
    private val activeExecutionControl = AtomicReference<GenerationExecutionControl?>(null)
    /** 上一轮可复用的实测 assistant token 数；仅在原始文本精确匹配时用于下一轮估算。 */
    @Volatile
    private var measuredAssistantText: String? = null
    @Volatile
    private var measuredAssistantTokens: Int = 0
    @Volatile
    private var thermalMonitoringStarted = false
    /** 最近一次热状态决策（Task 8）；影响下一轮有效模式与线程 cap。 */
    @Volatile
    private var thermalDecision: ThermalDecision? = null
    /** 当前请求的性能模式（热降级决策的参考模式；由 chatTyped 每轮更新）。 */
    @Volatile
    private var currentRequestedMode: InferencePerformanceMode = InferencePerformanceMode.DEFAULT
    /** Task 2：模板能力解析器（进程内缓存，按 config 路径 + mtime 判失效，避免每轮重复读盘）。 */
    private val thinkingTemplateResolver = ThinkingTemplateCapabilityResolver()
    /** 本地思考策略解析器：AUTO 复杂度路由 + 手动档 + 软收束提示（纯 Kotlin，仅本地生效）。 */
    private val thinkingPolicyResolver = LocalThinkingPolicyResolver()
    /** 原生思考预算能力门禁：首期恒 UNVERIFIED（无经验证的适配器），统一回退提示策略。 */
    private val nativeThinkingBudgetResolver = NativeThinkingBudgetCapabilityResolver()
    /** Task 2：单阶段生成 runner（[BackendManager.asLocalGenerationRunner] adapter）。
     *  思考与正文在同一次 generate 中共享总上限，不再有两阶段控制流。 */
    private val localGenerationRunner: LocalGenerationRunner = backendManager.asLocalGenerationRunner()

    /** 启动温度监控（幂等）。热回调按 [ThermalMonitor.decide] 决策：撤销 hint / 请求 THERMAL_STOP /
     *  记录下一轮模式与线程 cap。MNN 已加载线程数不可中途改变，线程下调走下一轮 resolve。 */
    private fun ensureThermalMonitoring() {
        if (thermalMonitoringStarted) return
        thermalMonitoringStarted = true
        thermalMonitor.startThermalMonitoring { _ ->
            val level = thermalMonitor.currentLevel()
            val decision = ThermalMonitor.decide(
                level = level,
                requestedMode = currentRequestedMode,
                bigCoreCount = threadOptimizer.getBigCoreCount(),
            )
            thermalDecision = decision
            Log.w(
                TAG,
                "Thermal level=$level -> mode=${decision.effectiveMode} cap=${decision.nextThreadCap} " +
                    "boost=${!decision.removeBoostNow} stop=${decision.stopNow}",
            )
            if (decision.removeBoostNow) cpuBoostController.deactivateHintNow()
            if (decision.stopNow) {
                // 热停止：先原子锁定原因（不计后端失败），再请求全局 abort；JNI 返回后由 finally 收尾。
                activeExecutionControl.get()?.requestStop(CompletionReason.THERMAL_STOP)
                backendManager.cancel()
            }
        }
    }

    /**
     * Task 7：查证当前组合的认证记录（device+model+CPU_OPTIMIZED+native 身份）。
     *
     * 键派生与认证落盘侧同源（Task 6 M-3 一致性约束）：
     * - device 指纹 = [BackendHealthCoordinator.deviceFingerprintOf]（与认证落盘侧同源）；
     * - model 指纹 = config.json 内容哈希（[modelConfigFingerprint]）；
     * - 变体 = CPU_OPTIMIZED（lookahead/步进认证只对 CPU 基准变体有意义，resolver 门禁
     *   matchesCpuVariant 只认该变体）；
     * - native 身份 = [MnnBridge.runtimeInfo]；握手缺席（null）时返回 null 不查询——
     *   认证键含 native 身份（[InferenceCertificationStore.certKey] 五分量），无身份无法匹配。
     *
     * final review I1：**不按 lookahead 开关短路**——DataStore 读为内存级（成本可忽略），
     * 且认证门禁独立于用户开关：lookahead 未请求时步进认证（step>1）仍须查证可达
     * （未来步进认证落盘后，开关关闭时不得因短路而永不查证）。lookahead 未请求时 resolver
     * 不会产生 LOOKAHEAD_UNCERTIFIED 噪音（该原因仅在 lookahead && 未认证时记录）。
     */
    private suspend fun loadCertifiedOptions(
        modelFingerprint: String,
    ): CertifiedInferenceOptions? {
        val runtime = MnnBridge.runtimeInfo ?: return null
        val key = InferenceCertificationStore.certKey(
            deviceFingerprint = BackendHealthCoordinator.deviceFingerprintOf(),
            modelFingerprint = modelFingerprint,
            variant = RuntimeVariant.CPU_OPTIMIZED.name,
            nativeBuildId = runtime.nativeBuildId,
            mnnCommit = runtime.mnnCommit,
        )
        return certificationStore.get(key)
    }

    override val type: ChatProviderType = ChatProviderType.LOCAL

    /** ChatProvider 接口：返回展示文本（本地历史精确复用走 [chatTyped] 取 modelText）。 */
    override suspend fun chat(
        messages: List<ChatMessage>,
        onChunk: (String) -> Unit,
    ): String = chatTyped(messages, onChunk).displayText

    /**
     * 估算未知模型（不在内置清单）的权重工作集：有界扫描模型目录文件大小作为上界。
     * 内置模型用 [com.rhodesisland.terminal.data.model.ModelInfo.size]（更精确），此处仅兜底未知模型，
     * 避免把「未知权重」误当成 0（0 会让大模型在无证据时被乐观放行）。上限 [MAX_UNKNOWN_WEIGHT_BYTES]。
     */
    private fun estimateUnknownModelWeightBytes(modelPath: String): Long {
        val dir = runCatching { File(modelPath).parentFile }.getOrNull() ?: return 0L
        var total = 0L
        runCatching {
            dir.walkTopDown().take(MAX_DIR_SIZE_SCAN_FILES).forEach { f ->
                if (f.isFile) {
                    total = (total + f.length()).coerceAtMost(MAX_UNKNOWN_WEIGHT_BYTES)
                }
            }
        }
        return total
    }

    /**
     * 已安装模型的 tmp 目录哈希集（供 [MnnTmpDirJanitor.sweep] 区分「已装模型缓存」与「孤儿缓存」）。
     * 与 resolver 的 tmp 目录命名同源（MnnTmpDirJanitor.tmpDirFor + config.json 路径哈希），
     * 保证加载与清扫用同一把尺子：删除模型后其 tmp 目录即时变为孤儿，下一轮 chatTyped 即被清。
     */
    private fun modelInstalledTmpHashes(): Set<String> {
        val modelsDir = ModelPathResolver.getModelsDirectory(context)
        return (modelsDir.listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { d ->
                val config = File(d, ModelPathResolver.MNN_CONFIG_FILE)
                val model = File(d, ModelPathResolver.MNN_MODEL_FILE)
                if (config.exists() && model.exists()) {
                    MnnTmpDirJanitor.tmpDirFor(context.cacheDir, config.absolutePath)
                        .name.substringAfter(MnnTmpDirJanitor.TMP_DIR_PREFIX)
                } else {
                    null
                }
            }.toSet()
    }

    /**
     * 本地聊天（类型化结果，Task 3 Step 4）：分离展示文本与模型原始文本。
     *
     * - [LocalChatResult.displayText]：经 `<think>` 折叠装饰的展示文本，存 `content`、驱动 UI。
     * - [LocalChatResult.modelText]：模型原始输出（与 native `syncPromptCache()` 逐字节一致），存 `modelContent`，
     *   重放本地历史时优先取它喂回 MNN，保证 KV 前缀复用精确命中。展示装饰永不进入 toMessagesJson。
     */
    suspend fun chatTyped(
        messages: List<ChatMessage>,
        onChunk: (String) -> Unit,
    ): LocalChatResult {
        // 1. 确保模型已选定并解析路径（MNN 目录的 config.json）
        val activeModelId = settings.getActiveLocalModelIdNow()
        if (activeModelId.isNullOrBlank()) {
            throw Exception("未选择本地模型，请先在模型管理页下载并选择模型")
        }

        val modelPath = ModelPathResolver.getLoadPath(context, activeModelId)
            ?: throw Exception("模型文件未找到，请先下载并选择模型")

        // 2a. 模型包完整性校验（Task 12）：config 派生必需文件（graph/weight/tokenizer/...）存在、
        //     非空、非分片、路径不逃逸。校验失败拒绝进入 native（绝不硬编码 verified=true）。
        val validation = ModelBundleValidator.validate(File(modelPath).parentFile ?: File(modelPath))
        if (!validation.valid) {
            Log.e(TAG, "模型包校验失败: ${validation.errors.joinToString("；")}")
            throw Exception("模型包校验失败，请重新下载模型")
        }

        // 2. 检查 MNN 引擎 native 就绪（libMNN.so）
        if (!backendManager.mnnCpuSupported) {
            throw Exception("MNN 引擎未就绪。当前版本未集成 libMNN.so，请等待后续版本。")
        }

        // native 加载与推理均为阻塞调用，必须切到 IO 调度器，否则在主线程上会 ANR。
        // onChunk 回调会跨线程调用 StateFlow.update，但 update 是 CAS 线程安全的。
        return withContext(Dispatchers.IO) {
            ensureThermalMonitoring()

            // 3. 读取推理参数（Task 6：一次 DataStore 快照读，替换逐字段 8 个 .first()；
            //    国产 ROM DataStore I/O 被拦截时整体超时回退不可变默认快照，避免逐字段多次挂起点）。
            val settingsNow = settings.getLocalInferenceSettingsNow()
            val contextLen = settingsNow.contextLen
            val userThreads = settingsNow.threads
            val temperature = settingsNow.temperature
            val maxTokens = settingsNow.maxTokens
            val preference = settingsNow.backend
            val lookahead = settingsNow.lookahead
            val performanceMode = settingsNow.performanceMode
            currentRequestedMode = performanceMode
            // Task 8：不再设置全局 boost 开关——提频由 PowerPolicy 驱动（Balanced 温和/MAXIMUM_SPEED 激进+sustained）。
            // 深度思考开关：透传给 MNN jinja context enable_thinking（运行时生效，无需重载）。
            // 关闭时推理模型跳过 <think> 推理段直接作答（修复「关闭开关仍深度思考」）。
            val deepThinking = settingsNow.deepThinking
            // 本地思考档位（仅本地，默认 AUTO）：开启深度思考时按档位/复杂度解析计划。
            // AUTO+SIMPLE 路由跳过（高效推理 routing）：简单问题直接关 enable_thinking，
            // 省掉整段无意义思考的 token 与首字延迟。手动档永不跳过。
            val nativeBudgetCapability = nativeThinkingBudgetResolver.resolve(null)
            val nativeBudgetAvailable = nativeBudgetCapability == NativeThinkingBudgetCapability.VERIFIED
            val thinkingPlan = thinkingPolicyResolver.resolve(
                enabled = deepThinking,
                requestedLevel = settingsNow.thinkingLevel,
                latestUserContent = messages.lastOrNull { it.role == "user" }?.content.orEmpty(),
                nativeBudgetAvailable = nativeBudgetAvailable,
            )
            val skipThinking = thinkingPlan?.skipThinking == true
            val firstRoundEnableThinking = deepThinking && !skipThinking
            // 仅推理模型（Think 标签）的输出需要折叠包装：其 chat 模板把起始 <think> 放在 generation
            // prompt 前缀（非输出流），故 native 输出缺起始 <think>，parseWithThink 无法折叠（修复「本地
            // 思考过程不可折叠」）。非推理模型（Llama/Gemma/SmolLM）不产生 <think>，无需包装。
            // 路由跳过的轮次没有思考段（模板注入的是空 <think></think> 前缀），不得折叠——否则会把
            // 正文误包进思考块。
            val shouldFoldThink = deepThinking && !skipThinking && isThinkingModel(activeModelId)
            val thinkingPolicyTelemetry = ThinkingPolicyTelemetry.from(
                thinkingPlan,
                nativeBudgetCapability.name,
            )

            // 有效线程数 = min(用户设定, 大核数, 温度上限)。
            // - 不超过大核数：多了会跑到小核，反而变慢且更耗电发热。
            // - 温度上限：当前若已高温（MODERATE/SEVERE/CRITICAL），开箱即降频。
            val opt = threadOptimizer.optimizeThreadAffinity()
            // 大核探测失败（cpu_sys_jni 未加载或返回空）时回退到 availableProcessors（封顶 4、至少 2），
            // 不能让线程数塌缩到 1——单线程跑数 GB 模型 prefill 可达数分钟级（5 分钟无输出即此症状）。
            val bigCount = opt.bigCoreIds.size.let {
                if (it > 0) it else Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            }
            val thermalCap = thermalMonitor.recommendedThreadCount(bigCount) // -1 = 不限制
            val baseThreads = (if (thermalCap > 0) {
                minOf(userThreads, bigCount, thermalCap)
            } else {
                minOf(userThreads, bigCount)
            }).coerceAtLeast(1)
            // Task 8：热降级决策的下一轮线程 cap（MODERATE=大核半、SEVERE=2、CRITICAL=1）再收紧。
            val decision = thermalDecision
            val effectiveThreads = if (decision != null && decision.nextThreadCap > 0) {
                minOf(baseThreads, decision.nextThreadCap).coerceAtLeast(1)
            } else baseThreads

            // 模型加载（阻塞 native）期间若被取消，立即抛 CancellationException，不进入生成。
            ensureActive()

            // Task 15：模型大小分类——AUTO 仅对「总参数量严格 >7B」探测/启用 GPU（探测前短路，
            // 小模型不必为注定 CPU 的推理等待最长 ~15s 的 OpenCL 探测；未知模型安全默认 CPU）。
            val builtInModel = DEFAULT_MNN_MODELS.firstOrNull { it.id == activeModelId }
            val modelClass = builtInModel?.autoBackendModelClass ?: AutoBackendModelClass.CPU_UNKNOWN_PARAMETERS

            // Task 15：每轮内存准入（稳定优先）——权重工作集 + KV + 预留 vs 当前可用内存。
            // 不足时仅本次把 context 逐级减半（最低 512），**不改用户设置**；512 仍不足则友好拒绝。
            val gpuPossible = preference == BackendPreference.MNN_GPU ||
                (preference == BackendPreference.AUTO && modelClass == AutoBackendModelClass.GPU_ELIGIBLE)
            val modelDims = LlmMemoryEstimator.readModelDims(context, activeModelId)
            val kvForContext: (Int) -> Long = { ctx ->
                when {
                    modelDims == null ->
                        // 维度未知：稳定优先用保守密度估算，绝不传 0（0 = 把未知当零成本，大模型可被乐观放行）。
                        LlmMemoryEstimator.UNKNOWN_KV_BYTES_PER_TOKEN * ctx.toLong()
                    modelDims.numKeyValueHeads > 0 && modelDims.headDim > 0 ->
                        LlmMemoryEstimator.kvCacheBytes(ctx.toLong(), modelDims.layerCount, modelDims.numKeyValueHeads, modelDims.headDim)
                    else ->
                        LlmMemoryEstimator.kvCacheBytesFullHidden(ctx.toLong(), modelDims.layerCount, modelDims.hiddenSize)
                }
            }
            // 权重工作集：内置模型用清单 size；未知模型用目录实际文件大小兜底（仍 0 则准入按 KV+预留+PSS 兜底）。
            val weightWorkingSetBytes = builtInModel?.size?.takeIf { it > 0L }
                ?: estimateUnknownModelWeightBytes(modelPath)
            // 同模型已驻留（热复用）：权重已在当前进程 PSS 内，准入不再重复计入（修复双重计数导致的误降级/误拒）。
            val modelAlreadyResident = backendManager.isModelResident(modelPath)
            val admission = admissionCoordinator.admit(
                modelId = activeModelId,
                configuredContext = contextLen,
                weightWorkingSetBytes = weightWorkingSetBytes,
                kvBytesForContext = kvForContext,
                backendReserveBytes = if (gpuPossible) {
                    LocalInferenceAdmissionCoordinator.OPENCL_BACKEND_RESERVE_BYTES
                } else {
                    LocalInferenceAdmissionCoordinator.CPU_BACKEND_RESERVE_BYTES
                },
                modelAlreadyResident = modelAlreadyResident,
            )
            val actualContext = when (admission) {
                is AdmissionDecision.Allowed -> admission.contextTokens
                is AdmissionDecision.Downgraded -> admission.actualContext
                is AdmissionDecision.Rejected -> throw MemoryAdmissionException(
                    "内存不足：当前可用约 ${LlmMemoryEstimator.formatMemory(admission.details["availableBytes"] ?: 0L)}，" +
                        "模型与 ${admission.details["minContext"] ?: 512}-token 最小上下文预计至少需要 " +
                        "${LlmMemoryEstimator.formatMemory(admission.details["requiredBytes"] ?: 0L)}。" +
                        "请关闭其他大型应用、改用更小模型，或稍后重试。",
                )
            }
            if (actualContext < contextLen) {
                Log.w(TAG, "内存准入降级 context: $contextLen -> $actualContext（仅本次，不改设置）")
            }

            Log.i(
                TAG, "infer: user=$userThreads big=$bigCount thermalCap=$thermalCap " +
                    "-> threads=$effectiveThreads, pref=$preference, lookahead=$lookahead"
            )

            // 4. 统一走 BackendManager：按偏好选 MNN 后端，失败按链回退。
            //    MNN 后端由模型自带 chat 模板格式化消息列表。topP/repeatPenalty 沿用默认值。
            // 本地小模型专属防「上头」：给 system prompt 追加输出规范约束（仅本地，云端大模型走
            // CloudChatProvider 不受影响），压制角色扮演滑向编造多角色剧本并无限生成。
            val enhancedMessages = messages.mapIndexed { idx, msg ->
                if (idx == 0 && msg.role == "system") {
                    // 输出规范 + 思考软收束提示（仅深度思考开启时才有 thinkingPlan，否则不追加）。
                    msg.copy(content = msg.content + RESPONSE_GUIDE + (thinkingPlan?.systemInstruction.orEmpty()))
                } else msg
            }
            // Task 5：先在保留 modelContent 的原始消息上规划窗口（估算用 modelContent ?: content），
            // 再把选中 assistant 的原始模型文本映射到 content 喂 MNN。绝不摘要/改写历史文本。
            val measuredText = measuredAssistantText
            val knownTokenCounts = if (measuredText != null && measuredAssistantTokens > 0) {
                enhancedMessages.mapIndexedNotNull { index, message ->
                    val raw = message.modelContent ?: message.content
                    if (message.role == "assistant" && raw == measuredText) index to measuredAssistantTokens else null
                }.toMap()
            } else emptyMap()
            val promptResult = promptWindowPlanner.plan(
                messages = enhancedMessages,
                admittedContextTokens = actualContext,
                requestedOutputTokens = maxTokens,
                previousAnchor = previousPromptAnchor,
                knownMessageTokenCounts = knownTokenCounts,
            )
            val promptPlan = when (promptResult) {
                is PromptWindowResult.Success -> promptResult.plan
                is PromptWindowResult.AdmissionFailure -> throw com.rhodesisland.terminal.llm.PromptAdmissionException(promptResult)
            }
            val modelMessages = promptPlan.messages.map { message ->
                val raw = message.modelContent ?: message.content
                message.copy(content = raw, modelContent = null)
            }
            if (promptPlan.anchorChanged || promptPlan.downgradeReason != null) {
                Log.i(
                    TAG,
                    "prompt window: input=${promptPlan.estimatedInputTokens} output=${promptPlan.reservedOutputTokens} " +
                        "anchorChanged=${promptPlan.anchorChanged} reason=${promptPlan.downgradeReason}",
                )
            }
            // Task 4 Step 3：本地是**唯一**原始回复累加器——native 不再返回全文，[LocalStreamRenderPump]
            // 持有 [accumulated] 由流式 delta 拼接；BackendManager 只带回 GenerationSummary（指标/完成原因）。
            // （Task 17：onToken 截断状态移入 runRound 局部，见下方生成段。）
            // 增量剧本检测（Task 4 Step 5）：每 token 只扫新增区间 + 最长角色名重叠窗口（O(1) 空间、
            // 无重扫），替代旧实现每块对全文 [indexOf] 的 O(n²)。cutAbsoluteIndex 与全文累加器下标
            // 对齐（同序从空喂入，由解码线程串行调用）。
            val scriptDetector = IncrementalScriptDetector(SCRIPT_NAMES)
            // Task 2：模板能力解析（诊断用，进程内缓存；不改变展示语义——renderLocalThink/isThinkingModel
            // 保持现状）。输入模型目录（config.json 所在目录）；MNN 模型包在 config.json 内嵌完整
            // Jinja chat 模板，据此判定模板是否含 enable_thinking 分支。
            val templateCapability = thinkingTemplateResolver.resolve(
                File(modelPath).parentFile ?: File(modelPath),
            )
            // Task 2：思考效果/空响应分类器——增量旁路（O(1) 空间），只观察未装饰的原始模型文本，
            // 不持有全文、不改输出；思考请求与模板能力在生成前已知，随构造传入。
            val thinkingClassifier = ThinkingOutputClassifier(
                thinkingRequested = deepThinking,
                templateCapability = templateCapability,
            )
            // Task 4：GPU 空输出回退策略按后端偏好构造（裁决 1）：
            // - AUTO / 显式 MNN_GPU / 显式 MNN_NPU -> CPU_BEFORE_FIRST_DELTA：链中含 GPU attempt
            //   （NPU 链在标准版解析为 CPU 计划，无 GPU attempt 时策略无副作用），首 delta 前
            //   GPU 空输出可回退 CPU 重跑；
            // - 显式 MNN_CPU -> DISABLED：纯 CPU 链，无 GPU attempt，策略无意义。
            // 判定本身由 GenerationOutcomeClassifier 把关（仅 MNN_GPU 后端 + 零输出 + 可回退分类
            // 才生效），此处只是按用户偏好开/关。
            val outputPolicy = GenerationOutputPolicy(
                emptyOutputFallback = if (preference == BackendPreference.MNN_CPU) {
                    EmptyOutputFallbackPolicy.DISABLED
                } else {
                    EmptyOutputFallbackPolicy.CPU_BEFORE_FIRST_DELTA
                },
            )
            // Task 4 Step 6：`<think>` 装饰与 UI 回调移入独立渲染协程（LocalStreamRenderPump），
            // 解码回调只做增量 append + 剧本检测 + conflated 信号，不再整串 toString + renderLocalThink，
            // 避免同步工作直接推迟下一次 generate(1)。首块立即渲染保证即时性；末帧由 finish 兜底。
            val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val renderPump = LocalStreamRenderPump(scope = renderScope, minIntervalMs = RENDER_THROTTLE_MS)
            renderPump.decorate = { renderLocalThink(it, shouldFoldThink) }
            renderPump.onChunk = { onChunk(it) }
            renderPump.start()
            // Task 2：单阶段生成使用一个 GenerationExecutionControl（见下方生成段），思考与正文
            // 共享同一总上限；watchdog 与 CAS 清理都只观察这一个 control。
            // Task 7：由性能模式/后端偏好/设备/热准入线程解析不可变执行计划（含每变体 native 配置）。
            // Task 3：真实 OpenCL 健康入链依据（取代 mnnGpuSupported 捷径——库可达 ≠ 运行时健康）：
            // - mnnGpuSupported 仅作前置快速门：库不可加载时连探测都跳过，直接 UNKNOWN 走 CPU 链，
            //   省一次探测进程启动；不再作为健康证据。
            // - 显式选 CPU（或标准版 NPU 偏好解析为 CPU）不触发探测：探测结果不影响纯 CPU 计划。
            // - AUTO / 显式 GPU：按持久健康记录解析，需要时同步跑一次隔离探测（5s 超时）再解析；
            //   探测失败自然回落 COOLDOWN -> 计划走 CPU 链，不阻塞 CPU 路径。
            // Task 15：AUTO 仅对「总参数量严格 >7B」探测/启用 GPU（modelClass 已在上面准入段计算）。
            val wantsGpuPath = preference == BackendPreference.MNN_GPU ||
                (preference == BackendPreference.AUTO && modelClass == AutoBackendModelClass.GPU_ELIGIBLE)
            // Task 7 review M-6：model 指纹每轮只算一次——GPU 健康键与认证键同源复用
            // （此前 GPU 路径下每轮重算两次：resolveForGpu + loadCertifiedOptions）。
            val modelFingerprint = modelConfigFingerprint(modelPath)
            val openclHealth = if (backendManager.mnnGpuSupported && wantsGpuPath) {
                val health = healthCoordinator.resolveForGpu(modelFingerprint)
                // Task 3 review M-5：决策理由有值时记录（COOLDOWN/BLACKLISTED 用 warn，其余 info）——
                // 便于从日志定位 OpenCL 被排除/降级/重新验证的原因，而不只是看到最终 state。
                health.reason?.let { reason ->
                    val msg = "OpenCL 健康决策: state=${health.state} reason=$reason"
                    if (health.state == OpenClHealthState.COOLDOWN ||
                        health.state == OpenClHealthState.CRASH_BLACKLISTED
                    ) {
                        Log.w(TAG, msg)
                    } else {
                        Log.i(TAG, msg)
                    }
                }
                health.state
            } else {
                OpenClHealthState.UNKNOWN
            }
            // Task 7：认证门禁查证——当前 device+model+CPU 变体+native 组合的基准认证记录。
            // lookahead / 多 token 步进只在存在认证证据时被 resolver 门禁启用（用户 legacy 请求
            // 只是使用既有认证的许可）；无认证回落安全默认（lookahead=false / step=1）。
            // final review I1：每轮恒查证（不按 lookahead 开关短路）——步进认证在开关关闭时
            // 同样可达；lookahead 噪音由 resolver 的 lookahead && 未认证条件天然排除。
            val certifiedOptions = loadCertifiedOptions(modelFingerprint)
            // Step1（Wave 1）：tmp_path 缓存目录清扫——未安装模型缓存优先删，再按 LRU 驱逐到预算
            // （每份 ≈ 模型大小）。预算默认保留最大一份活跃模型缓存 + 1 GiB 余量（见 MnnTmpDirJanitor）。
            val installedTmpHashes = modelInstalledTmpHashes()
            val swept = MnnTmpDirJanitor.sweep(
                context.cacheDir,
                installedTmpHashes,
                MnnTmpDirJanitor.defaultBudgetBytes(context.cacheDir, installedTmpHashes),
            )
            if (swept.isNotEmpty()) {
                Log.i(TAG, "tmp_path 清扫 ${swept.size} 个缓存目录: " + swept.joinToString { it.name })
            }
            // 磁盘资格（复用准入已算好的 weightWorkingSetBytes）：空闲额度足够才给
            // 该模型开 tmp_path（mmap 权重落盘 + 二次加载快启）；不足保持全内存驻留现状。
            val resolvedPlanBase = InferenceProfileResolver(
                context.cacheDir,
                modelPath,
                tmpPathEligible = { _ ->
                    runCatching {
                        val sf = StatFs(context.cacheDir.absolutePath)
                        MnnTmpDirJanitor.eligibleFor(weightWorkingSetBytes, sf.availableBytes)
                    }.getOrDefault(false)
                },
                // Wave 2：native 宣告采样热重建能力时，温度等标量不进 load 配置（调参不重载）；
                // 旧 .so 无能力时保持 legacy 行为逐位不变。
                samplerHotUpdateCapable = MnnBridge.hasSamplerHotUpdateCapability,
            ).resolve(
                // Task 8：热降级后的有效模式（MODERATE+ 恒 BALANCED，撤销 sustained）。
                mode = decision?.effectiveMode ?: performanceMode,
                backendPreference = preference,
                // Task 15：内存准入后的实际 context（降级时仅本次生效）。
                contextTokens = actualContext,
                maxOutputTokens = promptPlan.reservedOutputTokens,
                thermalAdmittedThreads = effectiveThreads,
                lookahead = lookahead,
                temperature = temperature,
                topP = AppConfig.LLM.DEFAULT_TOP_P,
                repeatPenalty = AppConfig.LLM.DEFAULT_REPEAT_PENALTY,
                openclHealth = openclHealth,
                modelClass = modelClass,
                certifiedOptions = certifiedOptions,
            )
            // Task 15：内存准入降级时，把「配置值 -> 实际值」与 MEMORY 原因带上（仅本次，不落盘设置）。
            val resolvedPlan = if (actualContext < contextLen) {
                resolvedPlanBase.copy(
                    configuredContextTokens = contextLen,
                    downgradeReasons = resolvedPlanBase.downgradeReasons + DowngradeReason.MEMORY,
                )
            } else {
                resolvedPlanBase
            }
            // Task 17：思考档位 -> 思考段字节硬预算。思考段（`<think>` 起至 `</think>` 止）超过预算
            // 即截断并进入收束轮直接作答——使「思考长度」设置真正生效（推理模型对软提示服从度低）。
            val thinkingBudgetBytes = thinkingPlan?.thinkingBudgetBytes
            var thinkingBudgetTruncated = false
            // 思考段循环退化早停标记（与预算截断共用收束轮通道；遥测区分原因）。
            var thinkingDegenerateTruncated = false
            val thinkSanity = OutputSanityDetector()
            val downgradeReasons = (listOfNotNull(promptPlan.downgradeReason) +
                resolvedPlan.downgradeReasons.map { it.name }).distinct()
            var lastControl: GenerationExecutionControl? = null

            /**
             * 单轮生成：watchdog + control + generate + renderPump 收尾。
             *
             * @param roundMessages 本轮消息。
             * @param roundEnableThinking 透传 native 的 enable_thinking（收束轮传 false 直接作答）。
             * @param roundThinkingRequested 遥测的思考请求标记（收束轮仍传 true——用户确实请求了
             *        思考，只是被预算截断；避免诊断误显示「请求关闭」）。
             * @param roundClassifier 本轮分类器实例（**每轮独立**——首轮思考段状态残留会污染
             *        收束轮的空响应/思考效果分类）。
             * @param roundPump 本轮渲染泵（收束轮以「思考+闭合」为种子复用累计文本）。
             * @param extraDowngrades 并入本轮遥测的额外降级原因（如 THINKING_BUDGET_TRUNCATED）。
             * @param enforceThinkingBudget 是否启用思考段预算截断（仅首轮；收束轮关闭——预算判定
             *        只对「思考段」有意义）。
             * @param enableScriptDetect 是否启用多角色剧本兜底截断（仅首轮；收束轮关闭，避免
             *        增量检测器跨轮绝对偏移与渲染泵错位）。
             */
            suspend fun runRound(
                roundMessages: List<ChatMessage>,
                roundEnableThinking: Boolean,
                roundThinkingRequested: Boolean,
                roundClassifier: ThinkingOutputClassifier,
                roundPump: LocalStreamRenderPump,
                extraDowngrades: List<String>,
                enforceThinkingBudget: Boolean,
                enableScriptDetect: Boolean,
            ): GenerationResult {
                var roundTruncated = false
                return try {
                    coroutineScope {
                        // 请求级 watchdog：进度由 MnnBackend 回调按真实时间直接写入 control；本协程只判 deadline。
                        // timeout 先原子锁定原因，再请求全局 abort；绝不跨线程释放 native。
                        val watchdog = launch {
                            while (isActive) {
                                delay(WATCHDOG_POLL_MS)
                                if (activeExecutionControl.get()?.completionReason(SystemClock.elapsedRealtime()) ==
                                    CompletionReason.TIMEOUT
                                ) {
                                    Log.w(TAG, "generation watchdog timeout -> request abort")
                                    backendManager.cancel()
                                    break
                                }
                            }
                        }
                        try {
                            val generationControl = GenerationExecutionControl(
                                policy = GenerationSafetyPolicy.forMode(performanceMode, resolvedPlan.maxOutputTokens),
                                startedElapsedMs = SystemClock.elapsedRealtime(),
                            )
                            activeExecutionControl.set(generationControl)
                            lastControl = generationControl
                            val request = LocalGenerationRequest(
                                modelPath = modelPath,
                                messages = roundMessages,
                                maxTokens = resolvedPlan.maxOutputTokens,
                                temperature = temperature,
                                topP = AppConfig.LLM.DEFAULT_TOP_P,
                                repeatPenalty = AppConfig.LLM.DEFAULT_REPEAT_PENALTY,
                                enableThinking = roundEnableThinking,
                                downgradeReasons = downgradeReasons + extraDowngrades,
                                resolvedPlan = resolvedPlan,
                                thinkingRequested = roundThinkingRequested,
                                templateCapability = templateCapability.name,
                                thinkingClassifier = roundClassifier,
                                thinkingPolicy = thinkingPolicyTelemetry,
                                outputPolicy = outputPolicy,
                                decodeStepTokens = resolvedPlan.decodeStepTokens,
                            )
                            localGenerationRunner.generate(request, generationControl) { token ->
                                // Task 2 旁路：分类器观察未装饰的原始流（截断后仍继续观察真实到达的
                                // token，使 raw/body 字节计数完整），增量 O(1)，不进渲染路径。
                                roundClassifier.append(token)
                                if (!roundTruncated) {
                                    // 同步回调只做三件事：append、剧本检测截断、并发渲染信号——不做任何字符串
                                    // 整段拷贝/装饰/UI 更新，让 generate(1) 尽快回到 MNN 解码。
                                    roundPump.append(token)
                                    if (enableScriptDetect) {
                                        // 兜底截断：增量检测「角色名：」多角色剧本标记 -> 截到标记前并停止。
                                        val cutPos = scriptDetector.append(token).cutAbsoluteIndex
                                        if (cutPos != null) {
                                            roundPump.truncateTo(cutPos)
                                            roundTruncated = true
                                        }
                                    }
                                    // Task 17：思考段超过档位预算 -> 截断（POLICY_TRUNCATION），
                                    // 由调用方发起收束轮直接作答。
                                    if (!roundTruncated && enforceThinkingBudget &&
                                        thinkingBudgetBytes != null &&
                                        shouldTruncateThinking(roundClassifier, thinkingBudgetBytes)
                                    ) {
                                        Log.w(TAG, "思考段超过档位预算（${thinkingBudgetBytes}B），截断并进入收束轮")
                                        thinkingBudgetTruncated = true
                                        roundTruncated = true
                                    }
                                    // 思考段循环退化早停（高效推理 early-exit）：小模型思考常见复读
                                    // 环/单字符集退化（"哈哈哈哈…"、"。。。。。"），等到字节预算耗尽
                                    // 才截纯属浪费——检测器只喂思考段内的 token（正文的角色笑声等
                                    // 合法重复不得误伤），命中即与预算截断走同一收束轮路径。
                                    if (!roundTruncated && enforceThinkingBudget &&
                                        roundClassifier.sawThinkOpen && !roundClassifier.sawThinkClose
                                    ) {
                                        thinkSanity.append(token)
                                        when (thinkSanity.classify()) {
                                            OutputSanityDetector.SanityClass.REPETITION_LOOP,
                                            OutputSanityDetector.SanityClass.DEGENERATE,
                                            -> {
                                                Log.w(TAG, "思考段循环退化早停，进入收束轮")
                                                thinkingDegenerateTruncated = true
                                                thinkingBudgetTruncated = true // 复用收束轮触发通道
                                                roundTruncated = true
                                            }
                                            else -> Unit
                                        }
                                    }
                                }
                                // false -> abort + POLICY_TRUNCATION；max-token 由 native 硬边界返回 MAX_TOKENS。
                                !roundTruncated
                            }
                        } finally {
                            watchdog.cancel()
                        }
                    }
                } finally {
                    // 取消渲染协程并同步渲染最终帧；roundPump 的 scope 由调用方回收。
                    try {
                        roundPump.finish()
                    } catch (e: Exception) {
                        Log.w(TAG, "renderPump.finish 异常（忽略）: ${e.message}")
                    }
                    activeExecutionControl.compareAndSet(lastControl, null)
                }
            }

            // 首轮：完整思考 + 正文（思考超预算/循环退化由 runRound 内检测截断）。
            // AUTO+SIMPLE 路由跳过时直接 enable_thinking=false 作答（无思考段）。
            val firstResult = runRound(
                roundMessages = modelMessages,
                roundEnableThinking = firstRoundEnableThinking,
                roundThinkingRequested = deepThinking,
                roundClassifier = thinkingClassifier,
                roundPump = renderPump,
                extraDowngrades = emptyList(),
                enforceThinkingBudget = true,
                enableScriptDetect = true,
            )
            renderScope.cancel()

            // Task 17：思考预算截断 -> 收束轮。以「原消息 + 强制收束指令、enableThinking=false」
            // 直接产出正文（KV 前缀命中，仅 prefill 新增收束指令，成本小）。思考流保留并补
            // `</think>` 闭合（未闭合时），两轮文本拼接为最终输出——与单阶段展示语义一致。
            // 收束轮异常时回退到「截断思考」本身（不抛错，用户至少保留思考过程）。
            var finalResult = firstResult
            var finalRaw = renderPump.snapshot()
            if (thinkingBudgetTruncated && firstResult.completionReason == CompletionReason.POLICY_TRUNCATION) {
                val seededRaw = if (thinkingClassifier.sawThinkClose) finalRaw else finalRaw + "</think>"
                Log.i(TAG, "思考预算截断：进入收束轮直接作答（思考已保留 ${finalRaw.length} 字符）")
                val roundTwoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val roundTwoPump = LocalStreamRenderPump(scope = roundTwoScope, minIntervalMs = RENDER_THROTTLE_MS)
                roundTwoPump.decorate = { renderLocalThink(it, shouldFoldThink) }
                roundTwoPump.onChunk = { onChunk(it) }
                // 种子：思考（含闭合）先渲染可见，正文随后流式追加。
                roundTwoPump.append(seededRaw)
                roundTwoPump.start()
                // 收束轮用独立分类器（thinkingRequested=true 反映用户意图；enableThinking=false 实际直接作答）。
                val coalesceClassifier = ThinkingOutputClassifier(
                    thinkingRequested = true,
                    templateCapability = templateCapability,
                )
                val secondResult = try {
                    runRound(
                        roundMessages = modelMessages +
                            ChatMessage(role = "user", content = THINKING_COALESCE_INSTRUCTION),
                        roundEnableThinking = false,
                        roundThinkingRequested = true,
                        roundClassifier = coalesceClassifier,
                        roundPump = roundTwoPump,
                        // 降级原因按触发源区分：预算耗尽 vs 思考循环退化（诊断可分辨）。
                        extraDowngrades = listOf(
                            if (thinkingDegenerateTruncated) THINKING_DEGENERATE_TRUNCATED
                            else THINKING_BUDGET_TRUNCATED,
                        ),
                        enforceThinkingBudget = false,
                        enableScriptDetect = false,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.w(TAG, "思考收束轮异常（保留截断思考作为最终结果）: ${e.message}")
                    finalRaw = seededRaw
                    null
                }
                roundTwoScope.cancel()
                if (secondResult != null) {
                    finalResult = secondResult
                    finalRaw = roundTwoPump.snapshot()
                }
            }
            previousPromptAnchor = promptPlan.anchor

            // 配置变更检测：本次推理成功后，把"本次生效的"用户配置写回 last_applied，使设置页横幅归位。
            if (finalResult.reloaded) {
                Log.i(
                    TAG,
                    "本次推理触发模型加载/重载: userThreads=$userThreads ctx=$contextLen pref=$preference " +
                        "lookahead=$lookahead (effectiveThreads=$effectiveThreads, backend=${finalResult.usedBackend.displayName})"
                )
            }
            settings.acknowledgeLlmConfig(userThreads, contextLen, preference, lookahead, temperature)
            // Task 7：ack 实际应用的 plan 配置哈希（重载指纹），供诊断/后续健康记录。
            settings.setLlmLastConfigHash(resolvedPlan.firstAttempt?.loadConfigHash)

            Log.i(TAG, "生成完成，使用后端: ${finalResult.usedBackend.displayName}")

            // 本地是唯一累加器：全文来自流式拼接（native 不再返回全文），空则占位文案。
            // 折叠包装落库：与流式展示共用同一 [renderLocalThink] 逻辑，使历史消息重新渲染时仍可折叠
            // （修复「输出中可折叠、输出完不可折叠」--之前未见 </think> 时落库不补起始 <think>，
            // parseWithThink 失配变纯文本）。被 max_tokens 截断在思考中途时保留未闭合 <think>，
            // 半截思考仍可折叠查看、不泄漏到正文。（Task 17：思考预算截断时 finalRaw 已含补全的
            // `</think>` 闭合，与折叠语义一致。）
            val finalText = renderLocalThink(finalRaw, shouldFoldThink)
            val displayText = finalText.ifBlank { "(本地模型未生成回复)" }
            // 原始模型输出（与 native syncPromptCache 逐字节一致）：本地累加器即最终原始文本。
            val modelText = finalRaw
            val record = backendManager.lastTurnRecord()
            // Task 15：回填本轮实测峰值 PSS（MB -> 字节）——同模型后续准入以实测足迹为下限校准。
            admissionCoordinator.recordPeakPss(activeModelId, record?.peakPssMb?.times(1024L * 1024))
            // Task 2：思考分类已随生成在 MnnBackend finally 内收口并入遥测记录（四字段齐全），
            // provider 侧不再补记——避免加载期/首 attempt 前取消（本轮无 attempt 执行 finalize）时
            // 分类被写进上一轮记录（污染）且 generatedTokens 取错。
            if (modelText.isNotEmpty() && record != null && record.generatedTokens > 0) {
                measuredAssistantText = modelText
                measuredAssistantTokens = record.generatedTokens
            }
            LocalChatResult(
                displayText = displayText,
                modelText = modelText,
                generation = GenerationSummary(
                    backend = finalResult.usedBackend,
                    reloaded = finalResult.reloaded,
                    generatedTokens = record?.generatedTokens ?: 0,
                    decodeTps = record?.decodeTps,
                    kvReuse = record?.kvReuse,
                    completionReason = finalResult.completionReason ?: record?.completionReason,
                ),
            )
        }
    }

    override fun cancel() {
        // 原因必须先于全局 abort 发布，避免 active backend 先返回而把取消误记成其他原因。
        activeExecutionControl.get()?.requestStop(CompletionReason.USER_CANCEL)
        backendManager.cancel()
    }

    // ===== 供性能浮窗调用的接口 =====
    /** 最快大核当前频率（GHz），读不到返回 0 */
    fun getBigCoreFreqGHz(): Float = threadOptimizer.getBigCoreFreqGHz()

    /** 当前温度状态文案（正常/轻微发热/中等发热/...） */
    fun getThermalStatus(): String = thermalMonitor.getThermalStatusText()

    /** CPU 拓扑 JSON */
    fun getCpuTopology(): String = threadOptimizer.getCpuTopologyJson()

    /** 当前实际使用的后端类型（供浮窗「引擎」栏高亮，映射到 perfmon.BackendType）*/
    fun getActiveBackend(): PerfmonBackendType = when (backendManager.lastUsedBackend) {
        BackendType.MNN_GPU -> PerfmonBackendType.GPU
        BackendType.MNN_NPU -> PerfmonBackendType.NPU
        BackendType.MNN_CPU -> PerfmonBackendType.CPU
    }

    /** 当前是否正在推理（供性能浮窗决定取 native 实时 tps 还是归零）*/
    fun isGenerating(): Boolean = backendManager.isGenerating()

    /** 当前活跃后端的 native 实时 tps（gen_seq_len/decode_us，精确；MNN 边 decode 边更新）。
     *  未加载/未生成返回 0。供性能浮窗在生成中显示精确速率，替代按 flush 近似计数的偏差。*/
    fun getActiveTps(): Float = backendManager.getActiveMetrics().tokensPerSecond

    companion object {
        private const val TAG = "LocalChatProvider"

        /** 未知模型权重目录扫描的最大文件数（有界，防超大目录拖慢准入）。 */
        private const val MAX_DIR_SIZE_SCAN_FILES = 4096

        /** 未知模型权重工作集估算上限（64 GiB）：防目录大小异常溢出 Long 求和。 */
        private const val MAX_UNKNOWN_WEIGHT_BYTES = 64L * 1024 * 1024 * 1024

        /** 仅轮询 Kotlin 原子进度快照；不读取/释放 native。 */
        private const val WATCHDOG_POLL_MS = 1000L

        /** 流式渲染节流（ms）：`<think>` 装饰与 onChunk 仅在该间隔放行时构造（Task 4 Step 6）。
         *  与 ChatViewModel.STREAM_THROTTLE_MS(30) 对齐；首 delta 无条件放行保证即时性。 */
        private const val RENDER_THROTTLE_MS = 30L

        /** 本地小模型输出规范：约束单角色简短回复、禁剧本格式。追加到 system prompt（仅本地）。
         *  针对小模型角色扮演「上头」编多角色剧本并无限生成的根因（见 .claude/plans/fix-llm-not-stopping.md）。
         *  首句为「默认简短、用户要求详细时完整回答」：短思考策略不得截短用户明确要求的最终答案。 */
        private const val RESPONSE_GUIDE = "\n\n【输出规范（严格遵守）】\n" +
            "- 回复默认简短自然；用户明确要求详细说明、代码、列表或指定篇幅时，按用户要求完整回答。\n" +
            "- 只以你自己的角色身份说话，不要扮演、模拟或代言其他角色。\n" +
            "- 禁止使用「名字：」格式的对话剧本/台词录，禁止自问自答、不要连续生成多个角色的台词。\n" +
            "- 不要写大段括号心理活动旁白。"

        /**
         * 剧本标记检测用角色名集合：全部人设名。模型滑向多角色剧本时会生成
         * 「角色名：台词」格式；正常单角色回复不用此格式（角色对用户说话用逗号或直说）。
         */
        private val SCRIPT_NAMES: List<String> = buildList {
            addAll(Characters.ALL.values.map { it.name })
        }

        /** Task 17：思考预算截断的遥测降级原因（并入收束轮记录，诊断页可见）。 */
        const val THINKING_BUDGET_TRUNCATED = "THINKING_BUDGET_TRUNCATED"

        /** 思考段循环退化早停的遥测降级原因（复读环/字符集退化，OutputSanityDetector 判定）。 */
        const val THINKING_DEGENERATE_TRUNCATED = "THINKING_DEGENERATE_TRUNCATED"

        /** Task 17：思考预算截断后的收束指令——作为新一轮 user 消息，enableThinking=false 直接作答。 */
        private const val THINKING_COALESCE_INSTRUCTION = "你的思考已经足够，请立即停止继续思考，直接给出最终答案。"

        /**
         * 判断模型是否为推理模型（产生 `<think>...</think>` 思考段）。
         *
         * 推理模型（Qwen3 / DeepSeek-R1 等）的 chat 模板把起始 `<think>` 放在 generation prompt 前缀
         * （非输出流），故 native 输出缺起始 `<think>`，[renderLocalThink] 据此补回以供折叠。
         *
         * 判定优先级：① 内置清单 [DEFAULT_MNN_MODELS] 的 Think 标签（权威）；② 清单外模型按 modelId
         * 关键词兜底（qwen3/qwq/deepseek-r1/reason/think），使自行添加的推理模型也能折叠。即便两者都
         * 未命中，[renderLocalThink] 仍会在输出含 `</think>` 时补起始标签折叠--本判定仅决定流式中
         * （尚未出现 `</think>` 时）是否补 `<think>` 显示「思考中…」。非推理模型不产生 `<think>`，无需处理。
         */
        private fun isThinkingModel(modelId: String?): Boolean {
            if (modelId.isNullOrBlank()) return false
            if (DEFAULT_MNN_MODELS.firstOrNull { it.id == modelId }
                    ?.tags?.any { it.equals("Think", ignoreCase = true) } == true) return true
            val id = modelId.lowercase()
            return id.contains("qwen3") || id.contains("qwq") || id.contains("deepseek-r1") ||
                id.contains("reason") || id.contains("think")
        }

        /**
         * 把本地推理模型的输出包装为可折叠的 `<think>...</think>` 结构，供 [MarkdownParser.parseWithThink] 折叠。
         *
         * 背景：Qwen3/R1 的 chat 模板把起始 `<think>` 放在 generation prompt 前缀（非输出流），故 native
         * 输出缺起始 `<think>`，[MarkdownParser.parseWithThink] 需 `<think>` 才能识别为思考段，否则推理过程
         * 以纯文本（夹一个孤立 `</think>`）显示、不可折叠。
         *
         * 包装规则（流式与落库共用同一逻辑，保证「输出中」与「输出完」折叠一致）：
         * - 含 `</think>`：补起始 `<think>`（[stripLeadingThink] 防模型自输出时双标签）-> 可折叠。
         * - 不含 `</think>` 但含 `<think>`：模型自输出起始标签（流式未闭合），保持原样即可折叠为「思考中…」。
         * - 两者都没有：仅当 [foldIfNoClose]（预判推理模型，其起始 `<think>` 在前缀故输出流缺）时补
         *   `<think>` 显示「思考中…」（含被 max_tokens 截断在思考中途，半截思考仍可折叠、不泄漏正文）；
         *   否则不补，避免把正常回复困在「思考中」折叠块。
         */
        private fun renderLocalThink(raw: String, foldIfNoClose: Boolean): String {
            val closeTag = "</think>"
            val closeIdx = raw.indexOf(closeTag)
            if (closeIdx >= 0) {
                val reasoning = stripLeadingThink(raw.substring(0, closeIdx))
                val content = raw.substring(closeIdx + closeTag.length)
                return "<think>$reasoning</think>$content"
            }
            if (raw.contains("<think>")) return raw  // 模型自输出起始标签，未闭合但已可折叠
            return if (foldIfNoClose) "<think>" + stripLeadingThink(raw) else raw
        }

        /** 去掉开头的 `<think>` 标签（trim 后匹配），防止模型自行输出 `<think>` 时与 [renderLocalThink] 补的重复。 */
        private fun stripLeadingThink(s: String): String {
            val t = s.trimStart()
            return if (t.startsWith("<think>")) t.substring("<think>".length) else s
        }
    }
}
