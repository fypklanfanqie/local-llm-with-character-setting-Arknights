package com.rhodesisland.terminal.llm

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * CPU 推理提频控制器（非 root 路线）
 *
 * 非 root Android 不允许写 cpufreq sysfs（`scaling_governor` / `scaling_max_freq` 等），无法直接锁频。
 * 本类用三条**官方**机制把推理时大核/超核频率尽量推高：
 *
 * 1. **PerformanceHintManager hint session**（API 31+，核心）：为推理线程创建 hint session，
 *    请求系统给该线程提频；`reportActualWorkDuration` 上报真实负载，系统（高通平台 perf-hint）
 *    据此拉升所在 CPU 簇频率。因推理线程已由 `cpu_affinity` 绑定大核，同簇连带提频使 ggml 工作
 *    线程（继承亲和性、同处大核簇）一并受益。
 * 2. **推理线程高优先级**：`Process.setThreadPriority(THREAD_PRIORITY_URGENT_DISPLAY)`，确保推理线程
 *    在调度中胜出（不提频，但减少被抢占）。设自身线程优先级无需权限。
 * 3. **SustainedPerformanceMode**：见 `MainActivity`（窗口级，抗热降频），与本类解耦。
 *
 * 用法（在 MNN 推理线程上，由 [com.rhodesisland.terminal.llm.backend.MnnBackend.generateStreamMessages]
 * 包住 nativeGenerateStream）：
 * ```
 * val s = boostController?.beginInference(CpuBoostController.TARGET_WORK_DURATION_NS)
 * try { ... nativeGenerateStream ... } finally { s?.close() }
 * ```
 * `enabled=false`（设置开关关）或 API<31 / 无 PHM 服务时 `beginInference` 返回 null，调用方 no-op。
 *
 * API 隔离：`PerformanceHintManager` 为 API 31+，所有引用隔离进 `@RequiresApi(S)` 的 [HintApi31]，
 * 仿 [ThermalMonitor.ThermalApi29] 模式；外层只持有 `(Long)->Unit` / `()->Unit` lambda，避免
 * minSdk=24 在 API<31 设备上解析 `PerformanceHintManager.Session` 类型触发 NoClassDefFoundError。
 *
 * 注：本工程 android.jar 为裁剪版，只有 API 31 的 `createHintSession(int[], long)`，无 API 34 的
 * `createUpdateSessionForThread(int, long)`，故用前者（运行期在所有 API 31+ 设备可用，API 34 起标记
 * deprecated 但仍存在）。
 */
class CpuBoostController(private val context: Context) {

    /** 提频总开关。由 LocalChatProvider 从设置 `llmCpuBoost` 同步。关时 beginInference 返回 null。 */
    @Volatile
    var enabled: Boolean = true

    private val phmSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * 在推理线程上调用，开启本次推理的提频。返回的 [InferenceBoostSession] 须在 finally 调 [InferenceBoostSession.close]。
     * - `enabled=false` -> 返回 null（调用方 no-op）。
     * - 否则：设当前线程高优先级（保存旧值供 close 恢复）+（API31+）创建 hint session。
     * - API<31 / PHM 取不到 / 创建失败 -> 仍返回 session（只做优先级提频），hint 回调为 null。
     */
    fun beginInference(targetDurationNanos: Long): InferenceBoostSession? {
        if (!enabled) return null
        val tid = Process.myTid()
        val prevPriority = runCatching { Process.getThreadPriority(tid) }.getOrDefault(0)
        runCatching { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_DISPLAY) }
            .onFailure { Log.w(TAG, "setThreadPriority failed: ${it.message}") }
        var reportDuration: ((Long) -> Unit)? = null
        var closeHint: (() -> Unit)? = null
        if (phmSupported) {
            val h = HintApi31.acquire(context, tid, targetDurationNanos)
            if (h != null) {
                reportDuration = h.first
                closeHint = h.second
            }
        }
        Log.i(TAG, "beginInference: priority boost on, hintSession=${if (reportDuration != null) "on" else "off"}")
        return InferenceBoostSession(prevPriority, reportDuration, closeHint)
    }

    /** 一次推理的提频句柄：close 恢复线程优先级并关闭 hint session。 */
    class InferenceBoostSession(
        private val prevPriority: Int,
        private val reportDuration: ((Long) -> Unit)?,
        private val closeHint: (() -> Unit)?,
    ) {
        /** 上报单个 work cycle（如一个 token）的实际耗时，供系统精确调频。无 hint 时 no-op。 */
        fun reportWorkDuration(durationNanos: Long) {
            reportDuration?.invoke(durationNanos)
        }

        fun close() {
            runCatching { closeHint?.invoke() }
            // close 须在与 beginInference 同一推理线程上调用，myTid() 才对应。
            runCatching { Process.setThreadPriority(Process.myTid(), prevPriority) }
                .onFailure { Log.w(TAG, "restoreThreadPriority failed: ${it.message}") }
        }
    }

    /** API 31+ PerformanceHintManager 隔离层，仿 [ThermalMonitor.ThermalApi29]。仅 SDK_INT>=S 时被访问。 */
    @RequiresApi(Build.VERSION_CODES.S)
    private object HintApi31 {
        /** 成功返回 (reportWorkDuration, closeHint) 一对 lambda；失败返回 null。 */
        fun acquire(
            context: Context,
            tid: Int,
            targetDurationNanos: Long,
        ): Pair<(Long) -> Unit, () -> Unit>? {
            val phm = context.getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? PerformanceHintManager
                ?: run {
                    Log.w(TAG, "PerformanceHintManager service unavailable")
                    return null
                }
            val session = try {
                phm.createHintSession(intArrayOf(tid), targetDurationNanos)
            } catch (e: Exception) {
                Log.w(TAG, "createHintSession failed: ${e.message}")
                null
            } ?: run {
                Log.w(TAG, "createHintSession returned null")
                return null
            }
            return Pair(
                { dur: Long ->
                    runCatching { session.reportActualWorkDuration(dur) }
                        .onFailure { Log.w(TAG, "reportActualWorkDuration failed: ${it.message}") }
                },
                {
                    runCatching { session.close() }
                        .onFailure { Log.w(TAG, "hint session close failed: ${it.message}") }
                },
            )
        }
    }

    companion object {
        private const val TAG = "CpuBoost"

        /** 目标 work cycle 时长（ns）。设较小值（16ms≈62tok/s）请求激进提频；可按实测调。 */
        const val TARGET_WORK_DURATION_NS = 16_000_000L
    }
}
