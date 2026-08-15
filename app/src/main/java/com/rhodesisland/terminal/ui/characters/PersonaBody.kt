package com.rhodesisland.terminal.ui.characters

/**
 * 提取人设正文：systemPrompt 首个「回答要求：」之前的部分（去首尾空白）；
 * 找不到则返回全文（自定义角色自由文本场景）。
 */
internal fun extractPersonaBody(systemPrompt: String): String {
    val marker = "回答要求："
    val idx = systemPrompt.indexOf(marker)
    return if (idx >= 0) systemPrompt.substring(0, idx).trim() else systemPrompt.trim()
}
