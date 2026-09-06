package com.rhodesisland.terminal.data.model

/**
 * 博士档案（「设置 → 我的形象」）。
 *
 * - [avatarPath]：博士头像内部存储路径（经 [com.rhodesisland.terminal.util.UserProfileImageStore] 落盘；空=未设置）。
 * - [displayName]：显示昵称（朋友圈等社交场景用；空 = 回退「我」）。
 * - [persona]：博士的人设（一段文本）。
 * - [relationship]：博士与角色之间的关系（全局一段文本）。
 *
 * [toDirectiveText] 把非空字段拼成注入 system prompt 的「用户信息」指令块：
 * 群聊（[com.rhodesisland.terminal.ui.groupchat.GroupChatPromptBuilder]）、云端/本地 1:1
 * （[com.rhodesisland.terminal.ui.chat.ChatViewModel]）、主动问候
 * （[com.rhodesisland.terminal.work.GreetingWorker]）统一使用，保持身份描述口径一致。
 */
data class UserProfileConfig(
    val avatarPath: String = "",
    val displayName: String = "",
    val persona: String = "",
    val relationship: String = "",
) {
    /** 朋友圈等社交场景的显示名：自定义昵称优先，否则「我」。 */
    val displayOrMe: String get() = displayName.trim().ifBlank { "我" }

    /**
     * 生成注入用指令块；全部字段为空时返回空串（调用方跳过注入）。
     * 纯函数，JVM 可测。
     */
    fun toDirectiveText(): String {
        if (persona.isBlank() && relationship.isBlank()) return ""
        return buildString {
            append("\n[用户信息] 用户是罗德岛的博士。")
            if (displayName.isNotBlank()) append("博士的名字：", displayName.trim(), "。")
            if (persona.isNotBlank()) append("人设：", persona.trim(), "。")
            if (relationship.isNotBlank()) append("博士与你的关系：", relationship.trim(), "。")
            append("请在对话中自然体现以上设定。")
        }
    }
}
