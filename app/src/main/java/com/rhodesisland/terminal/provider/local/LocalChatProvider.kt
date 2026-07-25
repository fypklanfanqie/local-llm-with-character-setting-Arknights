package com.rhodesisland.terminal.provider.local

import android.content.Context
import android.util.Log
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.DEFAULT_MNN_MODELS
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.llm.CpuBoostController
import com.rhodesisland.terminal.llm.InferenceThreadOptimizer
import com.rhodesisland.terminal.llm.ThermalMonitor
import com.rhodesisland.terminal.llm.backend.BackendManager
import com.rhodesisland.terminal.llm.backend.BackendType
import com.rhodesisland.terminal.perfmon.BackendType as PerfmonBackendType
import com.rhodesisland.terminal.provider.ChatProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

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
) : ChatProvider {

    // ===== CPU 调度优化 / 温度监控（不改 MNN 加载逻辑，仅优化线程数与提频）=====
    // threadOptimizer 须先于 thermalMonitor 初始化（thermalMonitor 的 bigCoreCountProvider 引用它）。
    private val threadOptimizer = InferenceThreadOptimizer()
    private val thermalMonitor = ThermalMonitor(context) { threadOptimizer.getBigCoreCount() }

    @Volatile
    private var thermalMonitoringStarted = false

    /** 启动温度监控（幂等）。高温回调仅记录建议线程数；MNN 运行中无法改线程数，
     *  真正的下调在下次加载模型时按 [ThermalMonitor.recommendedThreadCount] 生效。 */
    private fun ensureThermalMonitoring() {
        if (thermalMonitoringStarted) return
        thermalMonitoringStarted = true
        thermalMonitor.startThermalMonitoring { reduced ->
            Log.w(TAG, "Thermal throttling -> recommend $reduced threads (生效于下次 MNN 加载)")
        }
    }

    override val type: ChatProviderType = ChatProviderType.LOCAL

    override suspend fun chat(
        messages: List<ChatMessage>,
        onChunk: (String) -> Unit,
    ): String {
        // 1. 确保模型已选定并解析路径（MNN 目录的 config.json）
        val activeModelId = settings.getActiveLocalModelIdNow()
        if (activeModelId.isNullOrBlank()) {
            throw Exception("未选择本地模型，请先在模型管理页下载并选择模型")
        }

        val modelPath = ModelPathResolver.getLoadPath(context, activeModelId)
            ?: throw Exception("模型文件未找到，请先下载模型: $activeModelId")

        // 2. 检查 MNN 引擎 native 就绪（libMNN.so）
        if (!backendManager.mnnCpuSupported) {
            throw Exception("MNN 引擎未就绪。当前版本未集成 libMNN.so，请等待后续版本。")
        }

        // native 加载与推理均为阻塞调用，必须切到 IO 调度器，否则在主线程上会 ANR。
        // onChunk 回调会跨线程调用 StateFlow.update，但 update 是 CAS 线程安全的。
        return withContext(Dispatchers.IO) {
            ensureThermalMonitoring()

            // 3. 读取推理参数（contextLen/threads 仅在加载时生效，但 BackendManager 内部决定加载时机，
            //    故每轮读取并传入；DataStore.first() 有缓存，开销可忽略）
            val contextLen = settings.llmContextLen.first()
            val userThreads = settings.llmThreads.first()
            val temperature = settings.llmTemperature.first()
            val maxTokens = settings.llmMaxTokens.first()
            val preference = settings.llmBackend.first()
            val lookahead = settings.llmLookahead.first()
            // 同步 CPU 提频开关到 controller（MnnBackend 据此决定是否开 hint session）
            cpuBoostController.enabled = settings.llmCpuBoost.first()
            // 深度思考开关：透传给 MNN jinja context enable_thinking（运行时生效，无需重载）。
            // 关闭时推理模型跳过 <think> 推理段直接作答（修复「关闭开关仍深度思考」）。
            val deepThinking = settings.deepThinking.first()
            // 仅推理模型（Think 标签）的输出需要折叠包装：其 chat 模板把起始 <think> 放在 generation
            // prompt 前缀（非输出流），故 native 输出缺起始 <think>，parseWithThink 无法折叠（修复「本地
            // 思考过程不可折叠」）。非推理模型（Llama/Gemma/SmolLM）不产生 <think>，无需包装。
            val shouldFoldThink = deepThinking && isThinkingModel(activeModelId)

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
            val effectiveThreads = (if (thermalCap > 0) {
                minOf(userThreads, bigCount, thermalCap)
            } else {
                minOf(userThreads, bigCount)
            }).coerceAtLeast(1)

            // 模型加载（阻塞 native）期间若被取消，立即抛 CancellationException，不进入生成。
            ensureActive()

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
                    msg.copy(content = msg.content + RESPONSE_GUIDE)
                } else msg
            }
            val accumulated = StringBuilder()
            var truncated = false  // onToken 截断后置位，后续 token 不再累积、持续返回 false 让 native abort
            var seenCloseThink = false  // 本轮输出是否出现过 </think>（用于最终是否保留折叠包装）
            val result = backendManager.generate(
                modelPath = modelPath,
                messages = enhancedMessages,
                maxTokens = maxTokens,
                temperature = temperature,
                topP = AppConfig.LLM.DEFAULT_TOP_P,
                repeatPenalty = AppConfig.LLM.DEFAULT_REPEAT_PENALTY,
                contextLen = contextLen,
                threads = effectiveThreads,
                preference = preference,
                lookahead = lookahead,
                enableThinking = deepThinking,
                onToken = { token ->
                    if (!truncated) {
                        accumulated.append(token)
                        if (shouldFoldThink && !seenCloseThink && accumulated.indexOf("</think>") >= 0) {
                            seenCloseThink = true
                        }
                        // 兜底截断：累积文本出现「角色名：」多角色剧本标记 -> 截到标记前并停止。
                        // 模型遵守 system 规范时不会触发；不遵守时此为硬性防线，避免长篇剧本耗满 maxTokens。
                        val cutPos = findScriptCutPosition(accumulated)
                        if (cutPos >= 0) {
                            accumulated.setLength(cutPos)
                            truncated = true
                        }
                        // 折叠包装：补回起始 <think> 使 parseWithThink 能把推理段识别为可折叠 Think 段。
                        val raw = accumulated.toString()
                        onChunk(if (shouldFoldThink) renderLocalThink(raw) else raw)
                    }
                    // false -> MnnBackend 设 MnnBridge.abort=true -> native stepping 1 token 内停。
                    // 截断后持续返回 false 确保 abort 生效（native 可能再推 1 个 token 才检测 shouldAbort）。
                    !truncated
                },
            )

            // 配置变更检测：本次推理成功后，把"本次生效的"用户配置写回 last_applied，使设置页横幅归位。
            if (result.reloaded) {
                Log.i(
                    TAG,
                    "本次推理触发模型加载/重载: userThreads=$userThreads ctx=$contextLen pref=$preference " +
                        "lookahead=$lookahead (effectiveThreads=$effectiveThreads, backend=${result.usedBackend.displayName})"
                )
            }
            settings.acknowledgeLlmConfig(userThreads, contextLen, preference, lookahead, temperature)

            Log.i(TAG, "生成完成，使用后端: ${result.usedBackend.displayName}")

            // 优先使用流式累积的文本，否则回退 native 返回值，再回退占位文案。
            // 折叠包装落库：仅当本轮确实出现过 </think>（模型真推理了）才保留 <think> 包装，使历史消息
            // 重新渲染时仍可折叠（与流式展示一致）。未出现 </think>（模型未推理 / 被 max_tokens 截断在
            // 推理中途）则不补 <think>，避免把正常回复困在「思考中」折叠块里。
            val finalRaw = accumulated.toString()
            val finalText = if (shouldFoldThink && seenCloseThink) renderLocalThink(finalRaw) else finalRaw
            finalText.ifBlank { result.text.ifBlank { "(本地模型未生成回复)" } }
        }
    }

    override fun cancel() {
        // 非挂起：设置所有 MNN 后端的 abort 标志，正在运行的一方下一轮检测后退出。
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

        /** 本地小模型输出规范：约束单角色简短回复、禁剧本格式。追加到 system prompt（仅本地）。
         *  针对小模型角色扮演「上头」编多角色剧本并无限生成的根因（见 .claude/plans/fix-llm-not-stopping.md）。 */
        private const val RESPONSE_GUIDE = "\n\n【输出规范（严格遵守）】\n" +
            "- 每次只回复一两句话，简短自然，回复完立即停止。\n" +
            "- 只以你自己的角色身份说话，不要扮演、模拟或代言其他角色（如博士、其他干员）。\n" +
            "- 禁止使用「名字：」格式的对话剧本/台词录，禁止自问自答、不要连续生成多个角色的台词。\n" +
            "- 不要写大段括号心理活动旁白。"

        /**
         * 剧本标记检测用角色名集合：全部干员名 + 常见 NPC。模型滑向多角色剧本时会生成
         * 「角色名：台词」格式；正常单角色回复不用此格式（角色对博士说话用逗号或直说）。
         */
        private val SCRIPT_NAMES: List<String> = buildList {
            addAll(Characters.ALL.values.map { it.name })
            addAll(listOf("博士", "凯尔希", "特蕾西娅"))
        }

        /**
         * 兜底截断：检测 [text] 中最早出现的「角色名＋全角冒号」剧本标记，返回其起始下标（截到此处）。
         * 只匹配全角冒号「：」--半角「:」易误伤时间 10:30 / 比例 1:2；全角冒号在正常单角色回复里
         * 极稀有，误伤概率极低。无标记返回 -1。
         */
        private fun findScriptCutPosition(text: CharSequence): Int {
            var earliest = -1
            for (name in SCRIPT_NAMES) {
                val idx = text.indexOf("$name：")
                if (idx >= 0 && (earliest < 0 || idx < earliest)) earliest = idx
            }
            return earliest
        }

        /**
         * 判断模型是否为推理模型（产生 `<think>...</think>` 思考段）。
         * 据内置清单 [DEFAULT_MNN_MODELS] 的 Think 标签判定。推理模型（Qwen3 / DeepSeek-R1 等）的 chat
         * 模板把起始 `<think>` 放在 generation prompt 前缀，输出流缺起始 `<think>`，需 [renderLocalThink]
         * 补回以供折叠。非推理模型（Llama/Gemma/SmolLM）不产生 `<think>`，无需处理。未知模型回退 false。
         */
        private fun isThinkingModel(modelId: String?): Boolean {
            if (modelId.isNullOrBlank()) return false
            return DEFAULT_MNN_MODELS.firstOrNull { it.id == modelId }
                ?.tags?.any { it.equals("Think", ignoreCase = true) } == true
        }

        /**
         * 把本地推理模型的输出包装为 `<think>...</think>` 结构，供 [MarkdownParser.parseWithThink] 折叠。
         *
         * 背景：Qwen3/R1 的 chat 模板把起始 `<think>` 放在 generation prompt 前缀（非输出流），故 native
         * 输出形如 `[reasoning]</think>[response]`——缺起始 `<think>`，[MarkdownParser.parseWithThink]
         * 需 `<think>` 才能识别为思考段，否则推理过程以纯文本（夹一个孤立 `</think>`）显示、不可折叠。
         * 这里补回起始 `<think>`：
         * - 含 `</think>`：`<think>{reasoning}</think>{response}`（思考闭合 + 正文）。
         * - 不含 `</think>`（流式中思考未结束）：`<think>{reasoning}`（未闭合，UI 显示「思考中…」可折叠查看）。
         *
         * 防御：若模型自行输出了起始 `<think>`（个别模板行为），先剥掉避免 `<think><think>` 双标签。
         * 是否最终保留包装由调用方按「本轮是否出现过 `</think>`」决定（未出现则不补，避免困住正常回复）。
         */
        private fun renderLocalThink(raw: String): String {
            val closeTag = "</think>"
            val closeIdx = raw.indexOf(closeTag)
            if (closeIdx < 0) {
                return "<think>" + stripLeadingThink(raw)
            }
            val reasoning = stripLeadingThink(raw.substring(0, closeIdx))
            val content = raw.substring(closeIdx + closeTag.length)
            return "<think>$reasoning</think>$content"
        }

        /** 去掉开头的 `<think>` 标签（trim 后匹配），防止模型自行输出 `<think>` 时与 [renderLocalThink] 补的重复。 */
        private fun stripLeadingThink(s: String): String {
            val t = s.trimStart()
            return if (t.startsWith("<think>")) t.substring("<think>".length) else s
        }
    }
}
