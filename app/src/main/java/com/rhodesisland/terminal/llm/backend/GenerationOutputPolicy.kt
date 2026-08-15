package com.rhodesisland.terminal.llm.backend

/**
 * 空输出回退策略（Task 4）。
 *
 * 控制「GPU 在首个可见 delta 之前就空输出」时的处理方式：回退 CPU 重跑同一 prompt，
 * 或按既有行为原样返回空结果。
 */
enum class EmptyOutputFallbackPolicy {
    /** 禁用：GPU 空输出不回退 CPU，原样返回（既有行为）。 */
    DISABLED,

    /** 首 delta 前空输出可回退 CPU：仅当零输出（0 token / 0 callback 字节）且空响应分类可回退时生效。 */
    CPU_BEFORE_FIRST_DELTA,
}

/**
 * 生成输出策略（Task 4）：每轮请求级配置，由 [LocalChatProvider] 按用户后端偏好构造。
 *
 * 默认 [EmptyOutputFallbackPolicy.DISABLED]：未显式启用（如旧调用方）时行为与 Task 3 完全一致。
 */
data class GenerationOutputPolicy(
    val emptyOutputFallback: EmptyOutputFallbackPolicy = EmptyOutputFallbackPolicy.DISABLED,
)
