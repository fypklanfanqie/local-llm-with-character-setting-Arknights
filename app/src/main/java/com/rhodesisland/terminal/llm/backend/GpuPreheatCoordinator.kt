package com.rhodesisland.terminal.llm.backend

import android.content.Context
import android.os.SystemClock
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.AutoBackendModelClass
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.llm.metrics.InferenceTurnRecord
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.profile.InferenceProfileResolver
import com.rhodesisland.terminal.llm.profile.OpenClHealthState

/**
 * GPU 完整预热协调器（Task 15/16）。
 *
 * 手动预热：加载当前模型并执行一次**极短** GPU 生成（无思考、1 字节级 prompt、≤8 token），
 * 触发 OpenCL 图编译 / 内核 / 缓存预热，以降低首次真实消息的 TTFT。
 *
 * 状态隔离（不污染用户会话）：
 * - 不写聊天数据库 / 会话历史 / DataStore / 认证 / 基准归档；
 * - 预热后**无条件 release**（销毁模型与 KV，不把预热 prompt 缓存留给正常聊天）；
 * - 「最近一次聊天」诊断记录在预热前后保存/恢复（[BackendManager.stashLastTurnForSideOp] /
 *   [BackendManager.restoreLastTurnAfterSideOp]），不被预热记录覆盖。
 *
 * 前置：当前模型总参数量 >7B（[AutoBackendModelClass.GPU_ELIGIBLE]，调用方按按钮可用性把关）、
 * OpenCL 可用、健康检查通过（需要时自动跑隔离探测）、无进行中的生成。GPU 实际加载/生成失败会
 * 按计划链回退 CPU——此时如实报告「预热未生效（实际走了 CPU）」，不冒充 GPU 预热成功。
 */
class GpuPreheatCoordinator(
    private val context: Context,
    private val backendManager: BackendManager,
    private val healthCoordinator: BackendHealthCoordinator,
    private val settings: SettingsRepository,
) {

    sealed interface PreheatResult {
        /** 预热完成（实际后端；null 字段 = 无对应指标）。 */
        data class Done(
            val backend: BackendType,
            val ttftMs: Long?,
            val prefillMs: Long?,
            val loadMs: Long?,
        ) : PreheatResult

        /** 前置不满足 / GPU 未生效。 */
        data class Skipped(val reason: String) : PreheatResult
    }

    suspend fun preheat(modelId: String, modelPath: String): PreheatResult {
        if (backendManager.isGenerating()) {
            return PreheatResult.Skipped("当前有生成任务进行中，请稍后再试")
        }
        if (!backendManager.mnnGpuSupported) {
            return PreheatResult.Skipped("设备不支持 OpenCL GPU")
        }
        // 健康优先：需要时先跑一次隔离探测（复用现有 15s 超时与健康去重）；不健康则跳过。
        val modelFingerprint = modelConfigFingerprint(modelPath)
        val health = healthCoordinator.resolveForGpu(modelFingerprint)
        if (health.state != OpenClHealthState.PROBE_OK && health.state != OpenClHealthState.MODEL_OK) {
            return PreheatResult.Skipped("OpenCL 健康检查未通过（${health.reason ?: health.state.name}），未执行预热")
        }
        val snapshot = settings.getLocalInferenceSettingsNow()
        // 显式 GPU 计划（预热目的明确：测 GPU 路径本身；失败自然回退 CPU 并如实报告）。
        val plan = InferenceProfileResolver(context.cacheDir, modelPath).resolve(
            mode = InferencePerformanceMode.BALANCED,
            backendPreference = BackendPreference.MNN_GPU,
            // 预热用小上下文足够（KV 上限取 min(用户配置, 8192)，省内存）。
            contextTokens = snapshot.contextLen.coerceIn(512, 8192),
            maxOutputTokens = PREHEAT_MAX_TOKENS,
            thermalAdmittedThreads = snapshot.threads.coerceAtLeast(1),
            lookahead = false,
            temperature = snapshot.temperature,
            topP = AppConfig.LLM.DEFAULT_TOP_P,
            repeatPenalty = AppConfig.LLM.DEFAULT_REPEAT_PENALTY,
            openclHealth = health.state,
            modelClass = AutoBackendModelClass.GPU_ELIGIBLE,
        )
        val t0 = SystemClock.elapsedRealtime()
        backendManager.stashLastTurnForSideOp()
        try {
            val result = backendManager.generate(
                modelPath = modelPath,
                messages = PREHEAT_MESSAGES,
                maxTokens = PREHEAT_MAX_TOKENS,
                temperature = snapshot.temperature,
                topP = AppConfig.LLM.DEFAULT_TOP_P,
                repeatPenalty = AppConfig.LLM.DEFAULT_REPEAT_PENALTY,
                enableThinking = false,
                onToken = { true }, // 预热不产出文本，只触发图编译/内核/缓存。
                resolvedPlan = plan,
            )
            val record: InferenceTurnRecord? = backendManager.lastTurnRecord()
            return when (result.usedBackend) {
                BackendType.MNN_GPU -> PreheatResult.Done(
                    backend = result.usedBackend,
                    ttftMs = record?.ttftMs,
                    prefillMs = record?.prefillMs,
                    loadMs = record?.coldLoadMs ?: record?.warmLoadMs,
                )
                else -> PreheatResult.Skipped(
                    "GPU 预热未生效：实际走了 ${result.usedBackend.displayName}（OpenCL 加载/生成失败回退；耗 ${SystemClock.elapsedRealtime() - t0}ms）",
                )
            }
        } finally {
            // 状态隔离：无条件释放模型与 KV（不留预热缓存给正常聊天），并恢复「最近一次聊天」诊断。
            runCatching { backendManager.release() }
            backendManager.restoreLastTurnAfterSideOp()
        }
    }

    companion object {
        /** 预热生成的输出上限（极小：只触发一次完整 prefill + 几 token decode）。 */
        const val PREHEAT_MAX_TOKENS = 8

        /** 预热探针：极短 prompt，无思考。 */
        private val PREHEAT_MESSAGES: List<ChatMessage> = listOf(
            ChatMessage(role = "system", content = "你是中文测试助手。"),
            ChatMessage(role = "user", content = "你好。"),
        )
    }
}
