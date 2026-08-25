package com.rhodesisland.terminal.ui.groupchat

import com.rhodesisland.terminal.util.MarkdownParser

/** 模型生成的群聊回复经清理后的结果；speaker 身份来自代码，不来自模型文本。 */
sealed interface GroupChatReplyNormalization {
    data class Valid(val text: String) : GroupChatReplyNormalization
    data class ForeignSpeakerPrefix(val name: String) : GroupChatReplyNormalization
    data object Empty : GroupChatReplyNormalization
}

/**
 * 清理群聊模型回复：
 * 1. 先移除深度思考块（手动群聊可能启用了全局深度思考）；
 * 2. 只移除当前期望 speaker 的一个前缀；
 * 3. 若正文仍以其他群成员的「名字：」开头，拒绝而不是把 C 的话伪装成 A；
 * 4. 空正文不允许落库。
 */
fun normalizeGeneratedReply(
    raw: String,
    expectedSpeakerName: String,
    memberNames: List<String>,
): GroupChatReplyNormalization {
    var text = MarkdownParser.stripThink(raw).trim()
    if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
        text = text.substring(1, text.length - 1).trim()
    }
    if (text.length >= 2 && text.startsWith("「") && text.endsWith("」")) {
        text = text.substring(1, text.length - 1).trim()
    }

    val expectedPrefix = listOf("$expectedSpeakerName：", "$expectedSpeakerName:")
        .firstOrNull { text.startsWith(it) }
    if (expectedPrefix != null) text = text.removePrefix(expectedPrefix).trim()

    if (text.isBlank()) return GroupChatReplyNormalization.Empty

    val foreign = memberNames
        .asSequence()
        .filter { it.isNotBlank() && it != expectedSpeakerName }
        .firstOrNull { name -> text.startsWith("$name：") || text.startsWith("$name:") }
    if (foreign != null) return GroupChatReplyNormalization.ForeignSpeakerPrefix(foreign)

    return GroupChatReplyNormalization.Valid(text)
}
