package com.rhodesisland.terminal.llm

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 温度监控自动降频
 *
 * 监听系统热状态，温度升高时按档位减少推理线程数：
 *   NONE / LIGHT   -> 不限制（-1）
 *   MODERATE       -> 大核数减半
 *   SEVERE         -> 降到 2 线程
 *   CRITICAL/紧急  -> 降到 1 线程
 *
 * 使用 PowerManager 的热状态监听 API（API 29+）：addThermalStatusListener /
 * getCurrentThermalStatus / removeThermalStatusListener。本工程的 android.jar 不含
 * ThermalManager 系统服务，故统一走 PowerManager（在所有 API 级别均可获取，仅热状态
 * 相关方法为 API 29+）。
 *
 * 降频生效路径：
 *  - 加载时：LocalChatProvider 在 loadModel 前用 [recommendedThreadCount] 计算有效线程数，
 *    若开机即高温则从一开始就用更少线程（最有效）。
 *  - 运行中：[startThermalMonitoring] 的回调会即时调用 onThrottle（best-effort 重绑当前线程）。
 *    注意 llama 工作线程亲和性在 context 创建时已固定，运行中无法改其数量；真正的线程数
 *    下调会在下一次 loadModel（模型切换 / 重载）时按当时热状态生效。
 *
 * API <29：[startThermalMonitoring] 直接返回，监控为 no-op（不影响推理）。
 * 所有 API 29+ 引用（OnThermalStatusChangedListener / addThermalStatusListener 等）隔离在
 * [ThermalApi29] 中，仅当 SDK_INT>=29 时才加载该类，避免 minSdk=24 设备解析 API 29+ 类型失败。
 */
class ThermalMonitor(
    private val context: Context,
    /** 提供当前大核数（由 InferenceThreadOptimizer 供给），用于按比例降频。返回 0 时回退默认档位。 */
    private val bigCoreCountProvider: () -> Int,
) {

    // PowerManager 自 API 1 即有，在所有设备上可安全获取；仅热状态相关方法为 API 29+。
    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    @Volatile
    private var currentThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE

    private var throttlingCallback: ((Int) -> Unit)? = null
    private var thermalListener: Any? = null
    private var started = false
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ThermalMonitor").apply { isDaemon = true }
    }

    /**
     * 开始监控温度变化。
     * @param onThrottle 温度升档需要降到的新线程数（>0）。回调在后台线程触发，调用方自行切线程。
     */
    fun startThermalMonitoring(onThrottle: (newThreadCount: Int) -> Unit) {
        if (started) return
        val pm = powerManager ?: run {
            Log.w(TAG, "PowerManager unavailable, monitoring disabled")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Thermal status listener requires API 29+, monitoring disabled")
            return
        }
        started = true
        throttlingCallback = onThrottle

        thermalListener = ThermalApi29.register(pm, executor) { status ->
            currentThermalStatus = status
            val bigCount = bigCoreCountProvider()
            val reduced = recommendedThreadCount(bigCount, status)
            if (reduced > 0) {
                Log.w(TAG, "Thermal status=${statusText(status)}, recommend $reduced threads")
                throttlingCallback?.invoke(reduced)
            } else {
                Log.i(TAG, "Thermal status=${statusText(status)}, no throttle")
            }
        }
        currentThermalStatus = ThermalApi29.currentStatus(pm)
        Log.i(TAG, "Thermal monitoring started, status=${statusText(currentThermalStatus)}")
    }

    /**
     * 按当前热状态计算推荐线程数。
     * @param bigCoreCount 大核数（<=0 时按 4 估算）
     * @param status 热状态，默认取当前
     * @return 推荐线程数；-1 表示不限制（NONE/LIGHT）
     */
    fun recommendedThreadCount(
        bigCoreCount: Int,
        status: Int = currentThermalStatus,
    ): Int {
        val bc = if (bigCoreCount > 0) bigCoreCount else 4
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> -1                       // 不限制
            PowerManager.THERMAL_STATUS_MODERATE -> (bc / 2).coerceAtLeast(1)
            PowerManager.THERMAL_STATUS_SEVERE -> minOf(2, bc)
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY -> 1
            else -> -1
        }
    }

    /** 当前温度状态描述（供浮窗展示） */
    fun getThermalStatusText(): String = statusText(currentThermalStatus)

    /** 当前原始热状态值（供调用方自行决策） */
    fun currentStatus(): Int = currentThermalStatus

    fun stopMonitoring() {
        val pm = powerManager
        if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThermalApi29.unregister(pm, thermalListener)
        }
        thermalListener = null
        throttlingCallback = null
        // 不 shutdown executor：ThermalMonitor 为应用级长生命周期组件，start/stop 会随进入/退出
        // 本地聊天反复触发。shutdownNow 后再次 start 会把 listener 注册到已关闭的 executor，后续
        // 热状态回调被 RejectedExecutionException 静默丢弃，监控永久失效。daemon 线程空闲零开销。
        currentThermalStatus = PowerManager.THERMAL_STATUS_NONE
        started = false
        Log.i(TAG, "Thermal monitoring stopped")
    }

    private fun statusText(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "正常"
        PowerManager.THERMAL_STATUS_LIGHT -> "轻微发热"
        PowerManager.THERMAL_STATUS_MODERATE -> "中等发热"
        PowerManager.THERMAL_STATUS_SEVERE -> "严重发热"
        PowerManager.THERMAL_STATUS_CRITICAL -> "危险温度"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "紧急温度"
        else -> "未知"
    }

    /** API 29+ PowerManager 热状态交互隔离层：仅在 SDK_INT>=29 时被加载，避免低版本解析失败。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private object ThermalApi29 {
        fun register(
            pm: PowerManager,
            executor: Executor,
            onStatus: (Int) -> Unit,
        ): Any {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                onStatus(status)
            }
            pm.addThermalStatusListener(executor, listener)
            return listener
        }

        fun unregister(pm: PowerManager, listener: Any?) {
            val l = listener as? PowerManager.OnThermalStatusChangedListener ?: return
            runCatching { pm.removeThermalStatusListener(l) }
                .onFailure { Log.w(TAG, "removeThermalStatusListener failed: ${it.message}") }
        }

        fun currentStatus(pm: PowerManager): Int =
            runCatching { pm.getCurrentThermalStatus() }
                .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
    }

    companion object {
        private const val TAG = "ThermalMonitor"
    }
}
