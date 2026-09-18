package com.rhodesisland.terminal.util

/**
 * 单聊「微信式分句」：把一次 AI 回复按句子拆成多个连续短气泡。
 *
 * 规则（用户需求：每个气泡只有一句话，更像平常在微信聊天）：
 * - 在句尾标点（全半角句号/问号/叹号/分号、省略号、波浪号、闭引号/闭括号）之后断句，标点留在前一段；
 * - 每段去掉首尾空白，空白段丢弃；
 * - 「……」是两个省略号字符，朴素切分会从中间断开——拆完把以「…」开头的片段并回上一段；
 * - 拆不出 ≥2 段（本来只有一句话）时返回原句，行为与不拆一致。
 *
 * 纯函数、无 Android 依赖，JVM 可测；是否应用由渲染层决定（流式中/含代码块的消息不拆）。
 */
object ReplyBubbleSplitter {

    /** 闭标点：断句后以下列字符开头的碎片属于上一段（「）」不能单独成气泡）。 */
    private val CLOSERS = "」』））”"

    /** 句尾标点（单个字符宽度的后行断言），命中即在该位置后断句。 */
    private val sentenceEnd = Regex("(?<=[。！？!?；;…~～」』）)”])\\s*")

    fun split(content: String): List<String> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parts = sentenceEnd.split(trimmed)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size <= 1) return listOf(trimmed)
        // 「……」由两个 U+2026 组成，逐字符断句会把省略号拦腰截断——并回上一段
        val ellipsisMerged = mutableListOf<String>()
        parts.forEach { part ->
            if (part.startsWith("…") && ellipsisMerged.isNotEmpty()) {
                ellipsisMerged[ellipsisMerged.lastIndex] += part
            } else {
                ellipsisMerged += part
            }
        }
        // 连续闭标点（如「…」）同理：以下一段以闭标点开头的碎片并回上一段，
        // 避免「）」单独成一个气泡
        val merged = mutableListOf<String>()
        ellipsisMerged.forEach { part ->
            if (merged.isNotEmpty() && part.first() in CLOSERS) {
                merged[merged.lastIndex] += part
            } else {
                merged += part
            }
        }
        return if (merged.size <= 1) listOf(trimmed) else merged
    }
}
