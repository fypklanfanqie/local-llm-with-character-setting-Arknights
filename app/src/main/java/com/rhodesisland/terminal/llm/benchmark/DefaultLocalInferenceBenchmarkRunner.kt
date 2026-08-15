package com.rhodesisland.terminal.llm.benchmark

import android.content.Context
import android.util.Log
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.local.LocalInferenceSettings
import com.rhodesisland.terminal.data.model.AutoBackendModelClass
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.llm.ThermalLevel
import com.rhodesisland.terminal.llm.ThermalMonitor
import com.rhodesisland.terminal.llm.backend.BackendManager
import com.rhodesisland.terminal.llm.backend.BackendPreference
import com.rhodesisland.terminal.llm.backend.EmptyOutputFallbackPolicy
import com.rhodesisland.terminal.llm.backend.GenerationOutputPolicy
import com.chatbyyourside.llm.backend.MnnBridge
import com.rhodesisland.terminal.llm.metrics.BenchmarkSummary
import com.rhodesisland.terminal.llm.metrics.InferenceTurnRecord
import com.rhodesisland.terminal.llm.metrics.summarize
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.profile.InferenceProfileResolver
import com.rhodesisland.terminal.llm.profile.OpenClHealthState
import com.rhodesisland.terminal.llm.profile.ResolvedInferencePlan
import com.rhodesisland.terminal.llm.profile.RuntimeVariant
import com.rhodesisland.terminal.llm.template.EmptyResponseClass
import com.rhodesisland.terminal.llm.template.ThinkingOutputClassifier
import com.rhodesisland.terminal.llm.template.ThinkingTemplateCapability
import com.rhodesisland.terminal.llm.template.ThinkingTemplateCapabilityResolver
import com.rhodesisland.terminal.provider.local.ModelPathResolver
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * 默认本地推理基准运行器（Task 5 Step 4/5，契约 + 核心循环实现）。
 *
 * 实现 [LocalInferenceBenchmarkRunner] 的采样循环：
 * - **热检查**：[isThermallyHot] 用注入的 [ThermalMonitor]（可空）判 SEVERE+ 为热；未注入时
 *   自建 monitor 并调 [ThermalMonitor.startThermalMonitoring] 进入采样状态（Task 5 review I-1）。
 *   API 29+/PowerManager 缺席（或注入实例未启动监控）时为 no-op，热读取恒为 NONE——基准默认
 *   不拒绝热态；需真实热防护的调用方（Task 7 UI 入口）请注入已启动监控的实例。
 * - **采样循环**：`warmupRounds` 预热轮（丢弃）+ `recordedRounds` 记录轮（入样本）；每轮调
 *   [BackendManager.generate]（固定中文探针 prompt + 固定采样参数），取
 *   [BackendManager.lastTurnRecord] 为样本；热态样本丢弃并记入 [BenchmarkScenarioResult.discardedReasons]。
 * - **象限**：被测象限由设置快照推导（[InferenceBackendQuadrant.of]，AUTO→GPU、NPU→CPU 口径见该函数）；
 *   思考开关随象限透传为 generate 的 enableThinking。
 * - **COLD_LOAD 场景**：先 [BackendManager.release] 使首轮为冷启动。
 * - **可靠性**（[runReliability]）：固定轮数逐轮如实记录空响应分类，失败样本**不重试替换**；
 *   GPU 象限开启 Task 4 的 CPU_BEFORE_FIRST_DELTA 回退策略，回退轮次计入
 *   [ReliabilityResult.fallbackCount]。
 *
 * Task 4 Step 6 范围修正：**场景专用 fixture**（[messagesFor]）——不再所有场景共用同一固定
 * 探针。COLD_LOAD/SHORT_TTFT 用短探针，LONG_PREFILL 用确定性长 prompt，FIXED_DECODE 用
 * 固定提示 + 总 maxTokens=256，SECOND_TURN_KV_REUSE 在同一已加载 backend 先跑第一轮并用
 * assistant 原始文本构造第二轮（仅第二轮计数），EMPTY_RESPONSE_CHECK 用极短探针。
 * 仍不实现：UI 接入（Task 7）、自动调参（Task 6 认证门）。
 *
 * final review I3（裁决：文档化延迟）：四象限归档（[run] 各象限 save）与 [runReliability]
 * 在**生产主源码无触发入口**——仅 Lookahead 认证基准经设置页接线。四象限/可靠性验证由
 * CI/真机验收执行；UI 入口留后续版本，本版本不新增（避免范围膨胀）。
 */
open class DefaultLocalInferenceBenchmarkRunner(
    private val context: Context,
    private val backendManager: BackendManager,
    private val settings: SettingsRepository,
    private val thermalMonitor: ThermalMonitor? = null,
) : LocalInferenceBenchmarkRunner {
    // open 仅供仪器测试桩化 isThermallyHot 验证热守卫（Task 5 review M-4）；生产不子类化。

    private val effectiveThermalMonitor: ThermalMonitor = thermalMonitor
        ?: ThermalMonitor(context) { DEFAULT_BIG_CORE_COUNT }.apply {
            // Task 5 review I-1：自建 monitor 也必须启动采样，否则 currentLevel 恒为 NONE、
            // isThermallyHot 恒 false——热态样本不会被丢弃（「rejecting hot runs」默认不生效）。
            // startThermalMonitoring 可重复调用安全（内部 started 守卫）、无主线程要求；
            // API<29 或 PowerManager 缺席时为 no-op，热读取恒为 NONE（基准默认不拒绝，见类 KDoc）。
            startThermalMonitoring { /* 基准只读取热档位，不消费降频回调 */ }
        }

    private val templateResolver = ThinkingTemplateCapabilityResolver()

    /** 当前是否过热（SEVERE 及以上视为热，基准样本会污染，拒绝运行/采样）。 */
    override fun isThermallyHot(): Boolean = when (effectiveThermalMonitor.currentLevel()) {
        ThermalLevel.SEVERE, ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY -> true
        else -> false
    }

    override suspend fun run(
        scenario: InferenceBenchmarkScenario,
        configFingerprint: String,
        deviceFingerprint: String,
        warmupRounds: Int,
        recordedRounds: Int,
        candidateOverrides: CandidateOverrides?,
        target: BenchmarkTarget?,
    ): BenchmarkScenarioResult {
        if (isThermallyHot()) {
            return rejectedResult(
                scenario, configFingerprint, deviceFingerprint,
                listOf(REASON_THERMALLY_HOT),
            )
        }
        val snapshot = settings.getLocalInferenceSettingsNow()
        // Task 15/16：显式目标强制象限（不修改持久化设置）；null 按设置快照推导。
        var quadrant = if (target != null) {
            when (target) {
                BenchmarkTarget.CPU_OPTIMIZED ->
                    if (snapshot.deepThinking) InferenceBackendQuadrant.CPU_THINKING_ON else InferenceBackendQuadrant.CPU_THINKING_OFF
                BenchmarkTarget.OPENCL_GPU ->
                    if (snapshot.deepThinking) InferenceBackendQuadrant.GPU_THINKING_ON else InferenceBackendQuadrant.GPU_THINKING_OFF
            }
        } else {
            InferenceBackendQuadrant.of(snapshot.backend, snapshot.deepThinking)
        }
        // Task 7 M-4：候选旁路只对 CPU 变体有意义（lookahead / 多 token 步进仅 CPU 生效）——
        // 强制 CPU 象限测量，防止 GPU/AUTO 偏好下把 OPENCL 样本当作候选证据（证据错配，
        // 与 Task 6 review I-3 的「步进证据按变体守卫」同源约束）。思考开关沿用设置快照推导值。
        if (candidateOverrides != null) {
            quadrant = if (quadrant.thinkingEnabled) {
                InferenceBackendQuadrant.CPU_THINKING_ON
            } else {
                InferenceBackendQuadrant.CPU_THINKING_OFF
            }
        }

        // COLD_LOAD：先卸载模型，让首轮成为真实冷启动（mmap + load）。
        if (scenario.requiresColdStart) {
            Log.i(TAG, "场景 ${scenario.storageKey} 需要冷启动，先 release 模型")
            runCatching { backendManager.release() }
        }

        val modelPath = resolveModelPath(snapshot)
        if (modelPath == null) {
            return rejectedResult(scenario, configFingerprint, deviceFingerprint, listOf(REASON_NO_MODEL))
        }
        val plan = buildPlan(snapshot, quadrant, modelPath, candidateOverrides)
        val templateCapability = templateResolver.resolve(File(modelPath).parentFile ?: File(modelPath))

        // Task 4 Step 6：场景专用 fixture。SECOND_TURN_KV_REUSE 在同一已加载 backend 上先跑
        // 第一轮并用 assistant 原始文本构造第二轮（仅第二轮进入样本循环）；其余场景用静态 fixture。
        // Task 15：LONG_PREFILL 必须测「完整 prefill」——每轮用确定性但**不同**的 round nonce 前缀，
        // 使后一轮不命中上一轮的 KV 前缀缓存（否则记录的 KV 复用而非完整 prefill 吞吐）。
        // 注意：lambda 必须经显式变量返回——若把 `{ ... }` 字面量直接放在函数调用后一行，Kotlin
        // 会把它解析为上一行的尾随 lambda 参数（跨行尾随 lambda），导致 buildSecondTurnMessages/
        // messagesFor 收到多余参数（"Too many arguments"）。
        val messagesForRound: (Int) -> List<ChatMessage> = when (scenario) {
            InferenceBenchmarkScenario.SECOND_TURN_KV_REUSE -> {
                val secondTurn = buildSecondTurnMessages(modelPath, snapshot, plan, quadrant, templateCapability)
                val provider: (Int) -> List<ChatMessage> = { secondTurn }
                provider
            }
            InferenceBenchmarkScenario.LONG_PREFILL -> { round ->
                longDeterministicPrompt(targetEstimatedTokens = LONG_PREFILL_TARGET_TOKENS, roundNonce = "r$round")
            }
            else -> {
                val static = messagesFor(scenario)
                val provider: (Int) -> List<ChatMessage> = { static }
                provider
            }
        }
        // FIXED_DECODE 通过总 maxTokens=FIXED_DECODE_MAX_TOKENS + 固定提示约束输出长度，
        // 不用应用层思考 cap。
        val maxTokensOverride = if (scenario == InferenceBenchmarkScenario.FIXED_DECODE) {
            FIXED_DECODE_MAX_TOKENS
        } else {
            null
        }

        val discardedReasons = mutableListOf<String>()
        val samples = mutableListOf<InferenceTurnRecord>()
        var warmupDone = 0
        val totalRounds = warmupRounds + recordedRounds
        for (round in 0 until totalRounds) {
            if (isThermallyHot()) {
                discardedReasons += REASON_THERMALLY_HOT
                break
            }
            val record = runOneRound(
                modelPath, snapshot, plan, quadrant, templateCapability,
                messages = messagesForRound(round), maxTokensOverride = maxTokensOverride,
            )
            if (record == null) {
                discardedReasons += "NO_RECORD_ROUND_${round + 1}"
                continue
            }
            // Task 15：完整 prefill 样本不得含 KV 复用（兜底防线；正常由 round nonce 保证前缀失配）。
            if (scenario == InferenceBenchmarkScenario.LONG_PREFILL && record.kvReuse == true) {
                discardedReasons += "KV_REUSE_CONTAMINATION_ROUND_${round + 1}"
                continue
            }
            if (round < warmupRounds) warmupDone++ else samples += record
        }

        val summary = summarize(samples)
        // Task 5 review I-2：实际后端分布按样本级 record.backend 统计——样本级字段不落盘，
        // 归档后经 actualBackendCounts 可追溯「真 GPU」与「回退 CPU」（backendVariant 只记
        // 计划首个尝试，OpenCL 加载失败落到 CPU attempt 时无法分辨）。
        val actualBackendCounts = samples
            .mapNotNull { it.backend?.name }
            .groupingBy { it }
            .eachCount()
        val coolRun = samples.isNotEmpty() && discardedReasons.none { it.startsWith(REASON_THERMALLY_HOT) }
        return BenchmarkScenarioResult(
            scenario = scenario,
            deviceFingerprint = deviceFingerprint,
            configFingerprint = configFingerprint,
            summary = summary,
            recordedSampleCount = samples.size,
            warmupSampleCount = warmupDone,
            coolRun = coolRun,
            discardedReasons = discardedReasons,
            quadrant = quadrant,
            thinkingRequested = quadrant.thinkingEnabled,
            backendVariant = plan.firstAttempt?.variant?.name,
            actualBackendCounts = actualBackendCounts,
            nativeBuildId = MnnBridge.runtimeInfo?.nativeBuildId,
            mnnCommit = MnnBridge.runtimeInfo?.mnnCommit,
        )
    }

    override suspend fun runReliability(case: InferenceBenchmarkCase, rounds: Int): ReliabilityResult {
        require(rounds >= 0) { "rounds 必须 >= 0" }
        // Task 5 review M-3：热守卫入口早退——热降频同样污染可靠性样本。ReliabilityResult 无
        // coolRun 拒绝通道（与 run() 的 rejectedResult 语义不同型，返回全 NO_RECORD 会被误当
        // 伪有效结果归档），故抛异常让调用方明确感知（本函数既有 require 亦为抛错风格）；
        // 调用方（Task 7 UI 入口）应先用 isThermallyHot() 查询或捕获本异常。
        if (isThermallyHot()) {
            throw IllegalStateException("设备过热，可靠性基准未执行")
        }
        val snapshot = settings.getLocalInferenceSettingsNow()
        val modelPath = resolveModelPath(snapshot)
        val classes = mutableMapOf<String, Int>()
        var fallbackCount = 0
        var nonEmptyCount = 0
        if (modelPath != null) {
            val plan = buildPlan(snapshot, case.quadrant, modelPath)
            val templateCapability = templateResolver.resolve(File(modelPath).parentFile ?: File(modelPath))
            for (round in 0 until rounds) {
                // 失败样本如实记录，绝不用重试替换（每轮只执行一次）。
                // Task 4 Step 6：可靠性轮次固定用 EMPTY_RESPONSE_CHECK 探针。
                val record = runOneRound(
                    modelPath, snapshot, plan, case.quadrant, templateCapability,
                    messages = emptyResponseProbe(),
                    allowCpuFallback = true,
                )
                val cls = record?.emptyResponseClass ?: NO_RECORD_CLASS
                classes[cls] = (classes[cls] ?: 0) + 1
                if (cls == EmptyResponseClass.NONE.name) nonEmptyCount++
                if (record?.downgradeReasons?.contains(BackendManager.EMPTY_GPU_OUTPUT_FALLBACK) == true) fallbackCount++
            }
        } else if (rounds > 0) {
            classes[NO_MODEL_CLASS] = rounds
        }
        return ReliabilityResult(
            emptyResponseClasses = classes,
            fallbackCount = fallbackCount,
            nonEmptySuccessRate = if (rounds > 0) nonEmptyCount.toFloat() / rounds else 0f,
            totalRounds = rounds,
        )
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 一次探针生成：场景专用 prompt + 固定采样参数，返回本轮的最终遥测记录（异常返回 null）。 */
    private suspend fun runOneRound(
        modelPath: String,
        snapshot: LocalInferenceSettings,
        plan: ResolvedInferencePlan,
        quadrant: InferenceBackendQuadrant,
        templateCapability: ThinkingTemplateCapability,
        messages: List<ChatMessage>,
        maxTokensOverride: Int? = null,
        allowCpuFallback: Boolean = false,
    ): InferenceTurnRecord? {
        val classifier = ThinkingOutputClassifier(
            thinkingRequested = quadrant.thinkingEnabled,
            templateCapability = templateCapability,
        )
        // 性能基准用默认策略（DISABLED，不引入回退偏置）；可靠性基准在 GPU 象限开启
        // CPU_BEFORE_FIRST_DELTA，使 EMPTY_GPU_OUTPUT_FALLBACK 可被观察计数。
        val outputPolicy = GenerationOutputPolicy(
            emptyOutputFallback = if (allowCpuFallback && quadrant.usesGpu) {
                EmptyOutputFallbackPolicy.CPU_BEFORE_FIRST_DELTA
            } else {
                EmptyOutputFallbackPolicy.DISABLED
            },
        )
        // Task 4 Step 6：FIXED_DECODE 以总 maxTokens 约束输出长度（不改应用层思考 cap）；
        // 覆盖时同步把 resolvedPlan.maxOutputTokens 对齐，避免「generate 用 256 而计划声明 2048」的错配。
        val effectiveMax = maxTokensOverride ?: plan.maxOutputTokens
        val effectivePlan = if (maxTokensOverride != null) plan.copy(maxOutputTokens = effectiveMax) else plan
        try {
            backendManager.generate(
                modelPath = modelPath,
                messages = messages,
                maxTokens = effectiveMax,
                temperature = snapshot.temperature,
                topP = AppConfig.LLM.DEFAULT_TOP_P,
                repeatPenalty = AppConfig.LLM.DEFAULT_REPEAT_PENALTY,
                enableThinking = quadrant.thinkingEnabled,
                onToken = { true }, // 基准只测速，不截断
                thinkingRequested = quadrant.thinkingEnabled,
                templateCapability = templateCapability.name,
                thinkingClassifier = classifier,
                resolvedPlan = effectivePlan,
                outputPolicy = outputPolicy,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // 后端异常：本轮如实记为失败（记录可能不存在），不重试替换。
            Log.w(TAG, "基准轮生成异常（本轮如实计失败）: ${e.message}")
            return null
        }
        // MnnBackend 在 generateStreamMessages 的 finally 内收口遥测记录，generate 返回后必然可读。
        return backendManager.lastTurnRecord()
    }

    // ------------------------------------------------------------------
    // Task 4 Step 6：场景专用 fixture（不再所有场景共用同一短探针）
    // ------------------------------------------------------------------

    /**
     * 场景 → 消息 fixture。
     *
     * - [InferenceBenchmarkScenario.COLD_LOAD] / [InferenceBenchmarkScenario.SHORT_TTFT]：短探针；
     * - [InferenceBenchmarkScenario.LONG_PREFILL]：确定性长 prompt（估计 ~[LONG_PREFILL_TARGET_TOKENS] token）；
     * - [InferenceBenchmarkScenario.FIXED_DECODE]：固定提示 + 总 maxTokens=[FIXED_DECODE_MAX_TOKENS]；
     * - [InferenceBenchmarkScenario.SECOND_TURN_KV_REUSE]：第一轮种子消息（真正的两轮由
     *   [buildSecondTurnMessages] 在 [run] 内构造）；
     * - [InferenceBenchmarkScenario.EMPTY_RESPONSE_CHECK]：极短探针。
     */
    private fun messagesFor(scenario: InferenceBenchmarkScenario): List<ChatMessage> = when (scenario) {
        InferenceBenchmarkScenario.COLD_LOAD,
        InferenceBenchmarkScenario.SHORT_TTFT -> shortPrompt()
        InferenceBenchmarkScenario.LONG_PREFILL -> longDeterministicPrompt(targetEstimatedTokens = LONG_PREFILL_TARGET_TOKENS)
        InferenceBenchmarkScenario.FIXED_DECODE -> fixedDecodePrompt(targetOutputTokens = FIXED_DECODE_MAX_TOKENS)
        InferenceBenchmarkScenario.SECOND_TURN_KV_REUSE -> firstTurnMessages()
        InferenceBenchmarkScenario.EMPTY_RESPONSE_CHECK -> emptyResponseProbe()
    }

    /** 短探针：COLD_LOAD / SHORT_TTFT 共用（与 MnnStreamingIntegrationTest.probeMessages 同源）。 */
    private fun shortPrompt(): List<ChatMessage> = PROBE_MESSAGES

    /** SECOND_TURN_KV_REUSE 的第一轮种子（第二轮由 [buildSecondTurnMessages] 基于真实输出构造）。 */
    private fun firstTurnMessages(): List<ChatMessage> = PROBE_MESSAGES

    /**
     * 长 prefill 探针：确定性（无随机）长中文文本，估计约 [targetEstimatedTokens] token。
     * 中文在常见 tokenizer 下约 1~2 字符/token，此处按 2 字符/token 估计并固定文本（每次完全一致），
     * 仅用于测量 prefill 吞吐，不要求 token 数精确。
     *
     * @param roundNonce 轮次标记（Task 15）：置于 user 内容**最前**，改变整个前缀使本轮不命中
     *   上一轮的 KV 前缀缓存——保证记录到的是完整 prefill 而非 KV 复用。确定性（round 序号），
     *   不引入随机性。
     */
    private fun longDeterministicPrompt(targetEstimatedTokens: Int, roundNonce: String = ""): List<ChatMessage> {
        val block = "这是用于评估长前缀填充吞吐的固定文本。它不包含随机内容，因此每次基准的 prompt 完全一致。" +
            "请忽略这段内容的含义，只需完整复述其中的事实要点。"
        val repeatCount = (targetEstimatedTokens * 2) / block.length + 1
        val body = buildString {
            if (roundNonce.isNotEmpty()) append("基准轮次标记 $roundNonce。")
            repeat(repeatCount) { append(block) }
        }
        return listOf(
            ChatMessage(role = "system", content = "你是中文测试助手。"),
            ChatMessage(role = "user", content = body),
        )
    }

    /**
     * 固定解码探针：短固定提示；输出长度由总 maxTokens（[FIXED_DECODE_MAX_TOKENS]，经
     * [runOneRound] 的 maxTokensOverride 传入）与提示共同约束，不用应用层思考 cap。
     */
    private fun fixedDecodePrompt(targetOutputTokens: Int): List<ChatMessage> {
        val instruction = "请连续列出 $targetOutputTokens 个不同的中文词汇，每个一行，不要额外解释。"
        return listOf(
            ChatMessage(role = "system", content = "你是中文测试助手。"),
            ChatMessage(role = "user", content = instruction),
        )
    }

    /** EMPTY_RESPONSE_CHECK 探针：极短 prompt，最大化空输出可观测性（可靠性维度，不做吞吐）。 */
    private fun emptyResponseProbe(): List<ChatMessage> = listOf(
        ChatMessage(role = "system", content = "你是中文测试助手。"),
        ChatMessage(role = "user", content = "你好。"),
    )

    /**
     * SECOND_TURN_KV_REUSE：在同一已加载 backend 上先跑第一轮（短探针），捕获 assistant 原始
     * 流式文本（onToken 逐段拼接 = modelContent 等价物），用其构造第二轮消息
     * [system, user, assistant(raw), user("请针对以上内容继续补充。")]。
     * 第一轮只构造第二轮，**不计数**；随后 [run] 的预热/记录轮全部使用第二轮——真实两轮前缀，
     * 而非静态的伪多轮消息。
     */
    private suspend fun buildSecondTurnMessages(
        modelPath: String,
        snapshot: LocalInferenceSettings,
        plan: ResolvedInferencePlan,
        quadrant: InferenceBackendQuadrant,
        templateCapability: ThinkingTemplateCapability,
    ): List<ChatMessage> {
        val classifier = ThinkingOutputClassifier(
            thinkingRequested = quadrant.thinkingEnabled,
            templateCapability = templateCapability,
        )
        val firstTurn = firstTurnMessages()
        val raw = StringBuilder()
        val firstOutputPolicy = GenerationOutputPolicy(emptyOutputFallback = EmptyOutputFallbackPolicy.DISABLED)
        try {
            backendManager.generate(
                modelPath = modelPath,
                messages = firstTurn,
                maxTokens = plan.maxOutputTokens,
                temperature = snapshot.temperature,
                topP = AppConfig.LLM.DEFAULT_TOP_P,
                repeatPenalty = AppConfig.LLM.DEFAULT_REPEAT_PENALTY,
                enableThinking = quadrant.thinkingEnabled,
                onToken = { delta -> raw.append(delta); true }, // 只构造第二轮，不截断
                thinkingRequested = quadrant.thinkingEnabled,
                templateCapability = templateCapability.name,
                thinkingClassifier = classifier,
                resolvedPlan = plan,
                outputPolicy = firstOutputPolicy,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "SECOND_TURN_KV_REUSE 第一轮构造失败（本轮不计数）: ${e.message}")
        }
        val assistantRaw = raw.toString()
        if (assistantRaw.isBlank()) {
            Log.w(TAG, "SECOND_TURN_KV_REUSE 第一轮未产出文本，以空 assistant 消息构造第二轮")
        }
        // content 与 modelContent 同时写入原始文本：后端渲染按任一字段都不会丢 KV 前缀文本。
        return firstTurn +
            ChatMessage(role = "assistant", content = assistantRaw, modelContent = assistantRaw) +
            ChatMessage(role = "user", content = "请针对以上内容继续补充。")
    }

    /**
     * 构建固定参数的计划：平衡档、象限决定后端偏好、OpenCL 健康强制按可用入链。
     *
     * @param candidateOverrides Task 7 M-4 候选配置旁路（仅认证基准流程传入；null=生产语义）：
     *        非 null 时以候选值为「用户请求」输入并构造合成 [CertifiedInferenceOptions]（variant 恒
     *        [RuntimeVariant.CPU_OPTIMIZED]——lookahead/步进认证只对 CPU 变体有意义，resolver 门禁
     *        matchesCpuVariant 只认该变体），使 resolver 门禁放行候选配置；device/model 指纹留空
     *        （resolver 只匹配 variant，不读指纹）；native 身份取运行时握手（MnnBridge.runtimeInfo）
     *        供归档。KDoc 约束：**仅供基准流程**，生产路径（LocalChatProvider）不传。
     */
    private fun buildPlan(
        snapshot: LocalInferenceSettings,
        quadrant: InferenceBackendQuadrant,
        modelPath: String,
        candidateOverrides: CandidateOverrides? = null,
    ): ResolvedInferencePlan = InferenceProfileResolver(context.cacheDir, modelPath).resolve(
        // 基准固定平衡档：性能模式不是本任务的控制变量（Task 6 认证门再做调参）。
        mode = InferencePerformanceMode.BALANCED,
        // GPU 象限强制 MNN_GPU 偏好；OpenCL 健康强制按可用入链——基准要测 GPU 路径本身，
        // 实际加载/生成失败仍会自然回退 CPU，并在记录 backend/attemptTrace 中如实体现。
        backendPreference = if (quadrant.usesGpu) BackendPreference.MNN_GPU else BackendPreference.MNN_CPU,
        contextTokens = snapshot.contextLen,
        maxOutputTokens = snapshot.maxTokens,
        thermalAdmittedThreads = snapshot.threads.coerceAtLeast(1),
        // 旁路时以候选值为「用户请求」输入（与合成认证同源，使 lookahead && cert.lookahead 门禁
        // 放行候选配置）；生产路径仍取设置快照（用户请求只是使用既有认证的许可）。
        lookahead = candidateOverrides?.lookahead ?: snapshot.lookahead,
        temperature = snapshot.temperature,
        topP = AppConfig.LLM.DEFAULT_TOP_P,
        repeatPenalty = AppConfig.LLM.DEFAULT_REPEAT_PENALTY,
        openclHealth = if (quadrant.usesGpu) OpenClHealthState.PROBE_OK else OpenClHealthState.UNKNOWN,
        // 基准用显式 MNN_GPU/MNN_CPU 偏好（见上），模型大小门槛对显式选择不生效；按 GPU 目标传
        // GPU_ELIGIBLE，避免 AUTO 语义干扰基准象限。
        modelClass = AutoBackendModelClass.GPU_ELIGIBLE,
        certifiedOptions = candidateOverrides?.let { overrides ->
            CertifiedInferenceOptions(
                deviceFingerprint = "",
                modelFingerprint = "",
                variant = RuntimeVariant.CPU_OPTIMIZED.name,
                nativeBuildId = MnnBridge.runtimeInfo?.nativeBuildId ?: "",
                mnnCommit = MnnBridge.runtimeInfo?.mnnCommit ?: "",
                lookahead = overrides.lookahead,
                decodeStepTokens = overrides.decodeStepTokens,
            )
        },
    )

    /** 解析当前选中模型的 config.json 路径；未选模型/文件缺失返回 null。 */
    private suspend fun resolveModelPath(snapshot: LocalInferenceSettings): String? {
        val activeModelId = settings.getActiveLocalModelIdNow()
        if (activeModelId.isNullOrBlank()) {
            Log.w(TAG, "未选择本地模型，无法运行基准")
            return null
        }
        val path = ModelPathResolver.getLoadPath(context, activeModelId)
        if (path == null) Log.w(TAG, "模型文件缺失: $activeModelId")
        return path
    }

    private fun rejectedResult(
        scenario: InferenceBenchmarkScenario,
        configFingerprint: String,
        deviceFingerprint: String,
        reasons: List<String>,
    ): BenchmarkScenarioResult = BenchmarkScenarioResult(
        scenario = scenario,
        deviceFingerprint = deviceFingerprint,
        configFingerprint = configFingerprint,
        summary = BenchmarkSummary(),
        recordedSampleCount = 0,
        warmupSampleCount = 0,
        coolRun = false,
        discardedReasons = reasons,
    )

    companion object {
        private const val TAG = "DefaultLocalInferenceBenchmarkRunner"

        /** 固定中文探针 prompt（与 MnnStreamingIntegrationTest.probeMessages 同源，保证口径一致）。 */
        val PROBE_MESSAGES: List<ChatMessage> = listOf(
            ChatMessage(
                role = "system",
                content = "你是中文测试助手。你的每条回复都必须以中文为主，可以适当包含 emoji 表情符号。",
            ),
            ChatMessage(
                role = "user",
                content = "请用三句话介绍你自己，必须包含中文，并带上一个 emoji。",
            ),
        )

        // ---- Task 4 Step 6：场景 fixture 常量 ----
        /** LONG_PREFILL 长 prompt 的目标估计 token 数（按 2 字符/token 估计，仅用于吞吐测量）。 */
        private const val LONG_PREFILL_TARGET_TOKENS = 1024

        /** FIXED_DECODE 总 maxTokens（固定解码场景通过它约束输出长度，不用应用层思考 cap）。 */
        private const val FIXED_DECODE_MAX_TOKENS = 256

        /** 热态拒绝原因（isThermallyHot 命中时写入 discardedReasons）。 */
        private const val REASON_THERMALLY_HOT = "THERMALLY_HOT"

        /** 无模型/模型文件缺失拒绝原因。 */
        private const val REASON_NO_MODEL = "NO_MODEL"

        /** 可靠性轮次未产出遥测记录时的分类占位（后端异常/取消等）。 */
        private const val NO_RECORD_CLASS = "NO_RECORD"

        /** 未选模型时全部轮次的分类占位。 */
        private const val NO_MODEL_CLASS = "NO_MODEL"

        /** 默认构造 ThermalMonitor 时的大核数兜底（仅用于 recommendedThreadCount 比例，不影响本类逻辑）。 */
        private const val DEFAULT_BIG_CORE_COUNT = 4
    }
}
