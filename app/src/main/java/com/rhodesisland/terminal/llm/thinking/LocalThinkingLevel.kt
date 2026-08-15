package com.rhodesisland.terminal.llm.thinking

/**
 * 本地模型思考档位（仅本地生效，云端行为不变）。
 *
 * 与全局 [com.rhodesisland.terminal.data.local.LocalInferenceSettings.deepThinking] 开关相互独立：
 * - 开关（`deep_thinking`）决定本轮是否请求深度思考（透传 MNN jinja context `enable_thinking`）。
 * - 本档位只决定开启思考后「思考要多长/多深」，通过 [LocalThinkingPolicyResolver] 转为软收束提示。
 *
 * - [AUTO]：默认。按问题结构复杂度选择受限子集的思考强度；普通问题以约 5–15 秒为真机调优目标，
 *   复杂问题允许适度延长，不硬保证时长。
 * - [SHORT]：只做必要核验，尽快给出结论。
 * - [MEDIUM]：平衡分析与响应速度。
 * - [LONG]：覆盖更多方案、边界与自检，可明显延长。
 *
 * 新增档位时只追加枚举值，并同步更新 [fromStorageKey]；持久化用稳定 [storageKey]，
 * 勿直接使用枚举 `name`（便于未来重构重命名）。
 */
enum class LocalThinkingLevel(
    /** 持久化键（DataStore/JSON），稳定不变；勿用枚举 name。 */
    val storageKey: String,
    /** 设置项展示名。 */
    val displayName: String,
) {
    AUTO("auto", "自动"),
    SHORT("short", "短"),
    MEDIUM("medium", "中"),
    LONG("long", "长");

    companion object {
        /** 默认档位。未配置或无法识别时回落，保证前向兼容旧配置。 */
        val DEFAULT: LocalThinkingLevel = AUTO

        /** 从持久化键还原；未知/空值回落 [DEFAULT]，避免历史脏值导致崩溃。 */
        fun fromStorageKey(key: String?): LocalThinkingLevel =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}
