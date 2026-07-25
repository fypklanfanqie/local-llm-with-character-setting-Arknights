package com.rhodesisland.terminal.llm

import android.util.Log

/**
 * 推理线程亲和性 / CPU 拓扑探测
 *
 * 探测大核数量、推荐线程数、读 CPU 拓扑与频率，供：
 *  - [com.rhodesisland.terminal.provider.local.LocalChatProvider] 计算有效线程数（min(用户设定, 大核数, 温度上限)）
 *  - [ThermalMonitor] 取大核数做按比例降频
 *  - [com.rhodesisland.terminal.perfmon.PerformanceCollector] 经 LocalChatProvider 读大核频率
 *
 * 仅**只读**探测，不绑核（原 llama 专用的 sched_setaffinity 绑核随 llama.cpp 移除--MNN 自管线程调度）。
 * native 读取经 [CpuSysBridge]（libcpu_sys_jni.so）。native 不可用时返回安全默认值。
 */
class InferenceThreadOptimizer {

    private val bridge = CpuSysBridge()

    /** 最近一次探测到的大核数量（0 表示未探测或不可用），供 ThermalMonitor 等复用，避免重复读 /sys */
    @Volatile
    private var cachedBigCoreCount: Int = 0

    /**
     * 缓存的拓扑探测结果。CPU 拓扑（大核 ID / 推荐线程数）在运行期不变，首次探测后直接复用，
     * 避免每条消息都读 /sys 下 CPU 拓扑并把整段拓扑 JSON 写进 logcat。
     *
     * 注意：[getCpuTopologyJson] 含实时 curFreq，单独调用时仍实时读取（不受此缓存影响），
     * 故性能浮窗若需实时频率不会拿到过期值。
     */
    @Volatile
    private var cachedResult: ThreadOptimizeResult? = null

    /**
     * 探测 CPU 拓扑与大核数。首次调用读 /sys 并缓存，后续直接返回缓存结果。
     */
    fun optimizeThreadAffinity(): ThreadOptimizeResult {
        cachedResult?.let { return it }

        val topology = getCpuTopologyJson()
        val bigCores = try {
            bridge.getBigCoreIds().toList()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "getBigCoreIds unavailable (native lib too old?): ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "getBigCoreIds failed: ${e.message}")
            emptyList()
        }

        cachedBigCoreCount = bigCores.size
        Log.i(TAG, "CPU Topology: $topology")
        Log.i(TAG, "Big cores detected: $bigCores")

        // 推荐线程数 = 大核数，封顶 6（现代移动 SoC 大核类核心通常 ≤6；多了无收益）。
        val optimalThreadCount = bigCores.size.coerceAtMost(MAX_RECOMMEND_THREADS)

        val result = ThreadOptimizeResult(
            bigCoreIds = bigCores,
            recommendedThreads = optimalThreadCount,
            topologyJson = topology,
        )
        cachedResult = result
        return result
    }

    /** 当前大核数量（先用缓存，无缓存则探测一次）。native 不可用返回 0。 */
    fun getBigCoreCount(): Int {
        if (cachedBigCoreCount > 0) return cachedBigCoreCount
        if (!CpuSysBridge.nativeAvailable) return 0
        return try {
            bridge.getBigCoreIds().size.also { cachedBigCoreCount = it }
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "getBigCoreIds unavailable (native lib not loaded?): ${e.message}")
            0
        } catch (e: Exception) {
            Log.w(TAG, "getBigCoreCount failed: ${e.message}")
            0
        }
    }

    /** CPU 拓扑 JSON（直接读 /sys，不触发绑定）。native 不可用返回 "[]" */
    fun getCpuTopologyJson(): String = try {
        bridge.getCpuTopology()
    } catch (e: UnsatisfiedLinkError) {
        Log.w(TAG, "getCpuTopology unavailable: ${e.message}")
        "[]"
    } catch (e: Exception) {
        Log.w(TAG, "getCpuTopology failed: ${e.message}")
        "[]"
    }

    /** 最快大核当前频率（GHz），用于浮窗监控。native 不可用返回 0f
     *
     * 注意：UnsatisfiedLinkError 是 Error 而非 Exception，只 catch(Exception) 捕获不到；
     * native 库缺失时会在主线程定时器（性能浮窗 500ms 刷新）里直接崩溃。故先查
     * [CpuSysBridge.nativeAvailable]，并显式 catch UnsatisfiedLinkError。 */
    fun getBigCoreFreqGHz(): Float {
        if (!CpuSysBridge.nativeAvailable) return 0f
        return try {
            bridge.getBigCoreFreqMHz() / 1000f
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "getBigCoreFreqMHz unavailable (native lib not loaded?): ${e.message}")
            0f
        } catch (e: Exception) {
            Log.w(TAG, "getBigCoreFreqMHz failed: ${e.message}")
            0f
        }
    }

    data class ThreadOptimizeResult(
        val bigCoreIds: List<Int>,
        val recommendedThreads: Int,
        val topologyJson: String,
    )

    companion object {
        private const val TAG = "ThreadOptimizer"
        private const val MAX_RECOMMEND_THREADS = 6
    }
}
