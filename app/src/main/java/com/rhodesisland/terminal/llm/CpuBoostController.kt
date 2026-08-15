package com.rhodesisland.terminal.llm

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import com.rhodesisland.terminal.llm.profile.PowerPolicy

/**
 * CPU 推理提频控制器（非 root 路线）
 *
 * 非 root Android 不允许写 cpufreq sysfs（`scaling_governor` / `scaling_max_freq` 等），无法直接锁频。
 * 本类用三条**官方**机制把推理时大核/超核频率尽量推高：
 *
 * 1. **PerformanceHintManager hint session**（API 31+，核心）：为推理线程创建 hint session，
 *    请求系统给该线程提频；`reportActualWorkDuration` 上报真实负载，系统据此拉升所在 CPU 簇频率。
 * 2. **推理线程高优先级**：`Process.setThreadPriority(THREAD_PRIORITY_URGENT_DISPLAY)`。
 * 3. **SustainedPerformanceMode**：经 [sustainedModeSetter] 由 MainActivity 注入 window 设置；
 *    仅 [PowerPolicy.sustainedMode]（MAXIMUM_SPEED）时在本次生成期间开启，close 恢复。
 *
 * Task 8：每轮由 [PowerPolicy] 驱动，不再有全局 [enabled] 布尔；热回调可经
 * [deactivateHintNow] 线程安全地立即撤销 hint session。
 *
 * API 隔离：`PerformanceHintManager` 为 API 31+，所有引用隔离进 `@RequiresApi(S)` 的 [HintApi31]；
 * 外层只持有 lambda，避免 minSdk=24 设备解析 API 31 类型触发 NoClassDefFoundError。
 */
class CpuBoostController(private val context: Context) {

    /** 由 MainActivity 注入：`window.setSustainedPerformanceMode`。null = 不支持/no-op。 */
    @Volatile
    var sustainedModeSetter: ((Boolean) -> Unit)? = null

    /**
     * 清除由指定 Activity 注入的 sustained setter。仅当当前 setter 与传入引用**相同**时清除，
     * 避免配置变更（旋转等）重建时，旧 Activity 的 onDestroy 误清新 Activity 已注入的 setter。
     */
    fun clearSustainedModeSetter(setter: ((Boolean) -> Unit)?) {
        if (setter != null && sustainedModeSetter === setter) {
            sustainedModeSetter = null
        }
    }

    /** 当前活跃 hint 关闭回调；热回调线程安全撤销（不碰线程优先级）。 */
    @Volatile
    private var activeCloseHint: (() -> Unit)? = null

    private val phmSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * 在推理线程上调用，开启本次推理的提频（Task 8 由 [PowerPolicy] 驱动）。
     * 返回的 [InferenceBoostSession] 须在 finally 调 [InferenceBoostSession.close]。
     * - [PowerPolicy.sustainedMode] 时开启 sustained（close 恢复）；
     * - 设当前线程高优先级（保存旧值供 close 恢复）+（API31+）创建 hint session，目标时长随
     *   [PowerPolicy.aggressiveHint] 取 [AGGRESSIVE_TARGET_WORK_DURATION_NS] 或 [TARGET_WORK_DURATION_NS]。
     */
    fun beginInference(policy: PowerPolicy): InferenceBoostSession? {
        val sustainedOn = shouldEnableSustained(policy)
        if (sustainedOn) {
            runCatching { sustainedModeSetter?.invoke(true) }
                .onFailure { Log.w(TAG, "enable sustained failed: ${it.message}") }
        }
        val tid = Process.myTid()
        val prevPriority = runCatching { Process.getThreadPriority(tid) }.getOrDefault(0)
        runCatching { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_DISPLAY) }
            .onFailure { Log.w(TAG, "setThreadPriority failed: ${it.message}") }
        var reportDuration: ((Long) -> Unit)? = null
        var closeHint: (() -> Unit)? = null
        if (phmSupported) {
            val h = HintApi31.acquire(context, tid, targetDurationNs(policy))
            if (h != null) {
                reportDuration = h.first
                closeHint = h.second
            }
        }
        activeCloseHint = closeHint
        Log.i(TAG, "beginInference: priority on, hint=${if (reportDuration != null) "on" else "off"}, sustained=$sustainedOn")
        return InferenceBoostSession(
            prevPriority = prevPriority,
            reportDuration = reportDuration,
            closeHint = closeHint,
            sustainedOn = sustainedOn,
            sustainedSetter = sustainedModeSetter,
        )
    }

    /** 热回调线程安全地立即撤销 hint session（不碰线程优先级，后者由 session close 在推理线程恢复）。 */
    fun deactivateHintNow() {
        val close = activeCloseHint
        activeCloseHint = null
        close?.invoke()
    }

    /** 一次推理的提频句柄：close 恢复线程优先级、关闭 hint session、恢复 sustained。 */
    class InferenceBoostSession(
        private val prevPriority: Int,
        private val reportDuration: ((Long) -> Unit)?,
        private val closeHint: (() -> Unit)?,
        private val sustainedOn: Boolean,
        private val sustainedSetter: ((Boolean) -> Unit)?,
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
            if (sustainedOn) {
                runCatching { sustainedSetter?.invoke(false) }
                    .onFailure { Log.w(TAG, "disable sustained failed: ${it.message}") }
            }
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

        /** 平衡目标 work cycle 时长（ns）。16ms≈62tok/s。 */
        const val TARGET_WORK_DURATION_NS = 16_000_000L

        /** 极速目标 work cycle 时长（ns）：更短目标请求更高频率。 */
        const val AGGRESSIVE_TARGET_WORK_DURATION_NS = 8_000_000L

        /** 目标时长随 [PowerPolicy.aggressiveHint]：极速请求更激进（更短）目标。 */
        fun targetDurationNs(policy: PowerPolicy): Long =
            if (policy.aggressiveHint) AGGRESSIVE_TARGET_WORK_DURATION_NS else TARGET_WORK_DURATION_NS

        /** 仅 [PowerPolicy.sustainedMode]（MAXIMUM_SPEED）时开启 sustained；Balanced 永不。 */
        fun shouldEnableSustained(policy: PowerPolicy): Boolean = policy.sustainedMode
    }
}
