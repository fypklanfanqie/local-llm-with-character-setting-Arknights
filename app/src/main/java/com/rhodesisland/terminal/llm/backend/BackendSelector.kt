package com.rhodesisland.terminal.llm.backend

import android.app.ActivityManager
import android.content.Context

/**
 * 后端选择器
 *
 * 收集设备能力（NPU 支持、CPU 核数、总内存），并按 MNN_NPU > MNN_CPU 的优先级推荐最优后端。
 *
 * NPU 检测委托 [NpuSupportDetector]（高通骁龙 SoC + 芯片等级），用于 MNN QNN NPU 就绪判定
 * （[MnnSupportDetector.qnnReady]）。运行时是否真正可用由 [MnnBackend.isSupported] 再判定
 * （QNN 需 libQnnHtp.so 等运行时库打包）。
 */
class BackendSelector(private val context: Context) {

    data class DeviceCapability(
        val hasNpuSupport: Boolean,
        val hasNpuBackend: Boolean,   // 是否存在可用的 NPU 后端实现（MnnBackend NPU 模式）
        val cpuCoreCount: Int,
        val totalRAMMB: Long,
        val npuInfo: NpuSupportDetector.NpuSupportInfo,
    )

    fun collectDeviceInfo(): DeviceCapability {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val npuInfo = NpuSupportDetector.detect(context)

        return DeviceCapability(
            hasNpuSupport = npuInfo.supported,
            hasNpuBackend = true,   // MnnBackend NPU 模式已实现（native QNN 就绪与否由 isSupported 判定）
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            totalRAMMB = memInfo.totalMem / (1024 * 1024),
            npuInfo = npuInfo,
        )
    }

    /**
     * 推荐后端：MNN_NPU（设备支持时）> MNN_CPU。
     * 运行时是否真正可用由各后端 [InferenceBackend.isSupported] 再判定（如 NPU 需
     * libQnnHtp.so 就绪）。
     */
    fun recommendBackend(info: DeviceCapability = collectDeviceInfo()): BackendType {
        return when {
            info.hasNpuBackend && info.hasNpuSupport -> BackendType.MNN_NPU
            else -> BackendType.MNN_CPU
        }
    }
}
