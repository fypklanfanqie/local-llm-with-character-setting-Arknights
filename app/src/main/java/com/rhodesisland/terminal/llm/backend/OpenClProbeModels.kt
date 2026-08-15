package com.rhodesisland.terminal.llm.backend

import kotlinx.serialization.Serializable
import com.chatbyyourside.llm.backend.OpenClProbeService

/**
 * OpenCL 探测结果模型（Task 10）。
 *
 * 由 `:mnn_probe` 进程的 [OpenClProbeService] 产出，经跨进程 SharedPreferences 返回主进程；失败带
 * 类型化 [failureCode]，成功带 vendor/device/driver 身份，供 DeviceRuntimeFingerprint 与健康记录。
 */
@Serializable
data class OpenClProbeResult(
    val success: Boolean,
    val platform: String? = null,
    val vendor: String? = null,
    val device: String? = null,
    val driver: String? = null,
    val durationMs: Long = 0L,
    val failureCode: String? = null,
) {
    companion object {
        /** 常用失败码（镜像 native 侧常量，便于主进程诊断）。 */
        const val FAILURE_OPENCL_NOT_LOADABLE = "OPENCL_NOT_LOADABLE"
        const val FAILURE_SYMBOL_RESOLUTION = "SYMBOL_RESOLUTION"
        const val FAILURE_NO_DEVICE = "NO_DEVICE"
        const val FAILURE_KERNEL_BUILD = "KERNEL_BUILD"
        const val FAILURE_KERNEL_EXECUTION = "KERNEL_EXECUTION"
        const val FAILURE_OUTPUT_MISMATCH = "OUTPUT_MISMATCH"
        const val FAILURE_PROCESS_DEATH = "PROCESS_DEATH"
        const val FAILURE_TIMEOUT = "TIMEOUT"
        const val FAILURE_BIND = "BIND"
    }
}
