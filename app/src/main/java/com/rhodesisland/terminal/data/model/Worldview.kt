package com.rhodesisland.terminal.data.model

import kotlinx.serialization.Serializable

/** 世界观应用目标类型：角色私聊 / 群聊会话。 */
object WorldviewTargetType {
    const val CHARACTER = "character"
    const val GROUP = "group"
}

/**
 * 自定义世界观：一段注入 system prompt 的设定文本，与应用对象**一一对应**
 * （一条世界观只绑定一个目标；一个目标最多一条世界观，重复保存即替换）。
 *
 * - [targetType]：[WorldviewTargetType.CHARACTER]（私聊该角色时注入）或
 *   [WorldviewTargetType.GROUP]（群聊会话注入，targetId = 群 conversationId 字符串）
 * - [directiveText] 仿 [UserProfileConfig.toDirectiveText]，为各注入点提供统一格式
 */
@Serializable
data class Worldview(
    val id: String,
    val name: String,
    val content: String,
    val targetType: String,
    val targetId: String,
) {
    /** 生成注入用指令块；正文为空时返回空串（调用方跳过注入）。纯函数，JVM 可测。 */
    fun directiveText(): String {
        if (content.isBlank()) return ""
        return "\n[世界观设定]\n${content.trim()}\n请严格遵循以上世界观的设定进行对话。"
    }
}
