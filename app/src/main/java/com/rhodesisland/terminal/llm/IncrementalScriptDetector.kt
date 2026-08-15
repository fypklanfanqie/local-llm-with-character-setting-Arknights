package com.rhodesisland.terminal.llm

/**
 * 增量剧本检测器（Task 4 Step 5）。
 *
 * 流式 token 增量到达时检测「角色名：」多角色剧本标记（全角冒号），只保留覆盖
 * `maxNameLen + 1` 的尾部后缀，避免对已消费的旧文本重复整段扫描（O(1) 空间、无重扫）。
 *
 * 不变量：任意完整标记「名字：」一旦其冒号进入流中，整个标记必完整落在
 * [buffer] + 当前 delta 内——因为标记长度 ≤ `maxNameLen + 1` = 缓冲长度，旧文本即便被
 * 丢弃，也绝不会切在标记内部。故 [append] 对 combined 的扫描必能发现所有完整标记。
 *
 * 与旧实现 `LocalChatProvider.findScriptCutPosition` 语义一致：只匹配全角冒号「：」，
 * 半角「:」易误伤时间 10:30 / 比例 1:2，不匹配。
 *
 * @param names 全部人设名（角色名集合）。空集合则永不检测（恒返回 null）。
 */
class IncrementalScriptDetector(
    names: List<String>,
) {
    private val markerTerms: List<String> = names.map { "$it：" }
    private val maxNameLen: Int = names.maxOfOrNull { it.length } ?: 0

    /** 当前保留的尾部后缀（测试可读；[bufferStartIndex] 指向其首字符的绝对下标）。 */
    internal var buffer: String = ""
        private set

    internal var bufferStartIndex: Int = 0
        private set

    /**
     * 追加一段增量文本（须与上层累加器按相同顺序喂入相同字符，使 [DetectionResult.cutAbsoluteIndex]
     * 与累加器下标对齐），返回最早出现的剧本标记起点绝对下标；无标记返回 null。
     */
    fun append(delta: String): DetectionResult {
        if (delta.isEmpty()) return DetectionResult(null)
        val combined = buffer + delta

        var earliestRel = -1
        for (term in markerTerms) {
            val idx = combined.indexOf(term)
            if (idx >= 0 && (earliestRel < 0 || idx < earliestRel)) earliestRel = idx
        }
        // 绝对下标 = combined 起始的绝对下标（裁剪前 bufferStartIndex）+ 相对偏移
        val cut = if (earliestRel >= 0) bufferStartIndex + earliestRel else null

        // 只保留尾部 maxNameLen+1 个字符（含刚追加的 delta，保证完整标记可被捕获）。
        // bufferStartIndex 更新前已用于计算 cut，此处再推进。
        val keep = maxNameLen + 1
        buffer = if (combined.length > keep) combined.takeLast(keep) else combined
        bufferStartIndex += combined.length - buffer.length

        return DetectionResult(cut)
    }
}

/**
 * 检测结果。[cutAbsoluteIndex] 为最早剧本标记在总流中的绝对起点下标；无标记为 null。
 */
data class DetectionResult(val cutAbsoluteIndex: Int?)
