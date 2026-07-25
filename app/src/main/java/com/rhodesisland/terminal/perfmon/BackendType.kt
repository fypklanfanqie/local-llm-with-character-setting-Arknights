package com.rhodesisland.terminal.perfmon

/**
 * 推理后端类型（性能浮窗用）。
 *
 * MNN 推理：[PerformanceCollector.activeBackend] 由 [com.rhodesisland.terminal.provider.local.LocalChatProvider.getActiveBackend]
 * 映射自当前 MNN 后端（MNN_CPU->CPU / MNN_GPU->GPU / MNN_NPU->NPU）。
 * GPU / NPU 枚举值用于：
 *  ① 浮窗按 sysfs 显示真实 GPU/NPU 占用（MNN CPU 推理时通常为 0/低，反映真实情况）；
 *  ② 浮窗「引擎」栏高亮当前激活后端。
 *
 * @param displayName 浮窗「引擎」栏展示文案
 */
enum class BackendType(val displayName: String) {
    CPU("CPU 推理"),
    GPU("GPU"),
    NPU("NPU"),
}
