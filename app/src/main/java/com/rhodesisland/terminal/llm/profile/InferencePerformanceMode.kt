package com.rhodesisland.terminal.llm.profile

/**
 * 推理性能模式。
 *
 * - [BALANCED]：平衡模式。在保证首字延迟（TTFT）可接受的前提下，优先稳定解码速度与功耗；
 *   适合常规多轮对话，是默认档位。
 * - [MAXIMUM_SPEED]：极速模式。牺牲功耗/发热换取最大解码吞吐（提频、关降频、放开更多算子融合），
 *   适合单轮长输出或用户显式追求速度的场景。受热/电约束时会被降级回 [BALANCED]。
 *
 * Task 2 提前定义本枚举供 [com.rhodesisland.terminal.llm.metrics.InferenceSnapshot] 引用；
 * 真正的模式解析与切换逻辑在 Task 6（resolved plans / admission）实现，在此之前应用侧不主动启用。
 *
 * 新增档位时只追加枚举值，并同步更新 [fromStorageKey] 与 [storageKey]。
 */
enum class InferencePerformanceMode(
    /** 持久化键（DataStore/JSON），稳定不变；勿用枚举 name 以便重构重命名。 */
    val storageKey: String,
    /** 设置项展示名。 */
    val displayName: String,
) {
    BALANCED("BALANCED", "平衡"),
    MAXIMUM_SPEED("MAXIMUM_SPEED", "极速");

    companion object {
        /** 默认模式。未配置或无法识别时回落，保证前向兼容旧配置。 */
        val DEFAULT: InferencePerformanceMode = BALANCED

        /** 从持久化键还原；未知/空值回落 [DEFAULT]，避免历史脏值导致崩溃。 */
        fun fromStorageKey(key: String?): InferencePerformanceMode =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}
