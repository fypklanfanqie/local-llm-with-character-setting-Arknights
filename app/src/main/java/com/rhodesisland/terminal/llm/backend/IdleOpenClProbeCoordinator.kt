package com.rhodesisland.terminal.llm.backend

import android.content.Context
import android.util.Log
import com.rhodesisland.terminal.data.model.AutoBackendModelClass
import com.rhodesisland.terminal.data.model.DEFAULT_MNN_MODELS
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.provider.local.ModelPathResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 空闲 OpenCL 轻量探测协调器（Task 15/16）。
 *
 * 应用进入前台并空闲 [idleDelayMs] 后，**仅当**「显式 GPU 或 AUTO+总参数量>7B 模型」且健康记录
 * 确需探测时，执行**一次**隔离探测（[:mnn_probe] 进程，复用现有 15s 超时与健康去重）。
 *
 * 边界（严格遵守用户决策）：
 * - 空闲任务**只做轻量探测**，绝不加载模型、绝不执行完整预热；
 * - 退后台立即取消尚未开始的调度；
 * - 同一时刻最多一个探测（会话级 in-flight 守卫 + 健康 store 的 PROBE_OK/COOLDOWN 去重）；
 * - 探测在隔离进程执行，主进程崩溃边界不受影响。
 */
class IdleOpenClProbeCoordinator(
    private val scope: CoroutineScope,
    private val context: Context,
    private val healthCoordinator: BackendHealthCoordinator,
    private val settings: SettingsRepository,
    private val isForeground: () -> Boolean,
    private val idleDelayMs: Long = DEFAULT_IDLE_DELAY_MS,
) {

    private var scheduledJob: Job? = null
    private var probeInFlight = false

    /** 前台变化入口（由 AppLifecycleObserver 监听回调）。 */
    fun onAppForegroundChanged(foreground: Boolean) {
        scheduledJob?.cancel()
        scheduledJob = null
        if (!foreground) return
        scheduledJob = scope.launch {
            delay(idleDelayMs)
            if (!isForeground()) return@launch
            probeInFlight = true
            try {
                runProbeIfNeeded()
            } catch (e: Exception) {
                // 探测是旁路：任何异常只记日志，绝不影响主流程。
                Log.w(TAG, "空闲 OpenCL 探测异常（忽略）: ${e.message}")
            } finally {
                probeInFlight = false
            }
        }
    }

    private suspend fun runProbeIfNeeded() {
        val snapshot = settings.getLocalInferenceSettingsNow()
        val modelId = settings.getActiveLocalModelIdNow()
        val wantsProbe = snapshot.backend == BackendPreference.MNN_GPU ||
            (snapshot.backend == BackendPreference.AUTO && modelId != null &&
                DEFAULT_MNN_MODELS.firstOrNull { it.id == modelId }
                    ?.autoBackendModelClass == AutoBackendModelClass.GPU_ELIGIBLE)
        if (!wantsProbe) return
        val modelPath = modelId?.let { ModelPathResolver.getLoadPath(context, it) } ?: return
        // runProbeIfNeeded：健康记录已 PROBE_OK/MODEL_OK 时不启动探测进程（store 去重）；
        // UNKNOWN/冷却期已过才启动。探测结果写健康 store，供后续真实推理直接入链。
        val state = healthCoordinator.runProbeIfNeeded(modelConfigFingerprint(modelPath))
        Log.i(TAG, "空闲探测完成: state=$state model=$modelId")
    }

    companion object {
        private const val TAG = "IdleOpenClProbeCoordinator"

        /** 进入前台后的空闲去抖时长（避免与冷启动/页面初始化抢资源）。 */
        const val DEFAULT_IDLE_DELAY_MS = 5_000L
    }
}
