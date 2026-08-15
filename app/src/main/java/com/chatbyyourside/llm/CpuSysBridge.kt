package com.chatbyyourside.llm

import android.util.Log
/**
 * CPU 系统（大核 / 频率 / 拓扑）只读 JNI 桥接
 *
 * 对应 C 层 libcpu_sys_jni.so（由 cpp/cpu_affinity_jni.cpp 编译，仅依赖 liblog，无 llama/MNN）：
 *   Java_com_chatbyyourside_llm_CpuSysBridge_getBigCoreIds
 *   Java_com_chatbyyourside_llm_CpuSysBridge_getCpuTopology
 *   Java_com_chatbyyourside_llm_CpuSysBridge_getBigCoreFreqMHz
 *
 * 取代已删除的 LlamaBridge 中的 CPU 亲和性部分。原 LlamaBridge 的 bindToBigCores /
 * setupInferenceThreads（sched_setaffinity 绑核，供 llama 工作线程继承亲和性）随 llama.cpp
 * 一并移除--MNN 自管线程调度，无需外部绑核。本类只保留**只读**探测，供：
 *  - [InferenceThreadOptimizer] 探测大核数、推荐线程数、读拓扑（日志）
 *  - [com.rhodesisland.terminal.perfmon.PerformanceCollector] 经 LocalChatProvider 读大核频率
 *
 * 库加载顺序：c++_shared -> cpu_sys_jni。
 */
class CpuSysBridge {

    companion object {
        private const val TAG = "CpuSysBridge"

        /** 依赖顺序加载：c++_shared -> 本工程 CPU 系统 JNI 包装 libcpu_sys_jni.so */
        private val LIBS = arrayOf(
            "c++_shared",
            "cpu_sys_jni",  // 本工程 JNI 包装（含 CpuSysBridge_* 符号）
        )

        @Volatile
        private var bridgeLoaded = false

        init {
            for (lib in LIBS) {
                try {
                    System.loadLibrary(lib)
                    if (lib == "cpu_sys_jni") bridgeLoaded = true
                    Log.i(TAG, "✓ $lib loaded")
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "✗ $lib FAILED: ${e.message}")
                }
            }
            Log.i(TAG, "bridgeLoaded=$bridgeLoaded")
        }

        /** JNI 包装库是否可用（native 调用的前提） */
        val nativeAvailable: Boolean
            get() = bridgeLoaded
    }

    /** 大核（含超核）CPU ID 列表，按最大频率降序（最快在最前）。native 不可用返回空数组 */
    external fun getBigCoreIds(): IntArray

    /** CPU 拓扑 JSON：[{"cpu":0,"maxFreq":..,"curFreq":..,"isBig":0,"isPrime":0}, ...] */
    external fun getCpuTopology(): String

    /** 最快大核当前频率（MHz）。读不到返回 0 */
    external fun getBigCoreFreqMHz(): Float
}
