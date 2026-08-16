package com.rhodesisland.terminal.data.model

/**
 * 博士档案（「设置 → 我的形象」）。
 *
 * - [avatarPath]：博士头像内部存储路径（经 [com.rhodesisland.terminal.util.UserProfileImageStore] 落盘；空=未设置）。
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
    val persona: String = "",
    val relationship: String = "",
) {
    /**
     * 生成注入用指令块；全部字段为空时返回空串（调用方跳过注入）。
     * 纯函数，JVM 可测。
     */
    fun toDirectiveText(): String {
        if (persona.isBlank() && relationship.isBlank()) return ""
        return buildString {
            append("\n[用户信息] 用户是罗德岛的博士。")
            if (persona.isNotBlank()) append("人设：", persona.trim(), "。")
            if (relationship.isNotBlank()) append("博士与你的关系：", relationship.trim(), "。")
            append("请在对话中自然体现以上设定。")
        }
    }
}