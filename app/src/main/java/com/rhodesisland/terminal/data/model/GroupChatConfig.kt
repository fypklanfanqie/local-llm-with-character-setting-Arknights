package com.rhodesisland.terminal.data.model

/**
 * 群聊配置聚合快照（仅云端可用）。
 *
 * 对应 SettingsStore 的 `group_chat_enabled` / `group_member_ids` / `group_auto_chat_enabled` 三键，
 * 单次原子读写（仿 [SeedanceConfig] 的聚合模式），避免「picker 逐字段写回被并发覆盖」的 lost update。
 * 每日轮次上限/配额/上次发言者/下次触发时间等运行时字段各自独立存储（Worker 高频读写，不进本聚合）。
 */
data class GroupChatConfig(
    /** 群聊开关（默认关）。 */
    val enabled: Boolean = false,
    /** 群成员角色 id 集合（可多选，默认空）。 */
    val memberIds: Set<String> = emptySet(),
    /** 空闲自动聊天开关（默认开）——关闭后成员不再自动互聊/提问，但用户仍可手动群聊。 */
    val autoChat: Boolean = true,
)