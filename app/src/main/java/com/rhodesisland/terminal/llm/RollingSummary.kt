package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.config.AppConfig

/**
 * 滚动摘要上下文压缩（单聊云端）纯逻辑：折叠时机判定 / 摘要提示词构建 / 模型输出清洗。
 *
 * 与缓存布局的关系见 [AppConfig.RollingSummary] 注释；编排层（取候选→调模型→写回水位）
 * 见 RollingSummarizer。函数均为纯函数、无 Android 依赖，JVM 可测。
 */
object RollingSummary {

    /** 用户可调折叠批量的钳制：非法存储值/越界 UI 输入收敛到 [AppConfig.RollingSummary] 区间。 */
    fun coerceBatch(raw: Int): Int =
        raw.coerceIn(
            AppConfig.RollingSummary.MIN_FOLD_BATCH,
            AppConfig.RollingSummary.MAX_FOLD_BATCH,
        )

    /**
     * 触发阈值由批量派生：min(2N, 供给上限-5)。语义=未摘要数翻倍即折一批，折完回落到 N~(N-1)。
     * 封顶于 `MAX_PROMPT_SUPPLY - 5`：chat_history 修剪目标就是 MAX_PROMPT_SUPPLY，
     * 若阈值 ≥ 上限，countUnfolded 永远到不了 → 折叠饿死。留 5 条余量防御瞬时并发写入。
     */
    fun triggerFor(batch: Int): Int =
        minOf(coerceBatch(batch) * 2, AppConfig.MAX_PROMPT_SUPPLY - 5)

    /** 未摘要原文条数是否达到该批量下的折叠阈值。 */
    fun shouldFold(unfoldedCount: Int, foldBatch: Int): Boolean =
        unfoldedCount > triggerFor(foldBatch)

    /**
     * 构建折叠调用的用户提示词：把旧摘要与本批最旧原文交给云端模型压成新摘要。
     * [oldSummary] 为空表示首次折叠（占位「无」）。
     */
    fun buildFoldPrompt(oldSummary: String, batchLines: List<String>): String = buildString {
        appendLine("请把下面的「待归档对话」与「已有前情提要」合并压缩成一段新的前情提要。")
        // 四要素与长度约束为产品设计规格（滚动摘要上下文压缩方案表），不得弱化
        appendLine("要求：保留人物关系变化、双方的承诺与约定、未解决的伏笔、当前情绪基调；" +
            "用第三人称中文叙述；总长不超过300字；直接输出提要正文，不要任何解释或格式标记。")
        appendLine("【已有前情提要】")
        appendLine(oldSummary.trim().ifEmpty { "无" })
        appendLine("【待归档对话】")
        batchLines.forEachIndexed { idx, line ->
            append(idx + 1).append(". ").appendLine(line)
        }
    }

    /** 清洗模型返回：剥 <think> 推理段（含未闭合截断）、去首尾空白、硬截到 300 字上限。 */
    fun sanitizeSummary(raw: String): String {
        var s = raw.replace(Regex("<think>[\\s\\S]*?</think>"), "")
        val openIdx = s.indexOf("<think>")
        if (openIdx >= 0) s = s.substring(0, openIdx)
        return s.trim().take(AppConfig.RollingSummary.SUMMARY_MAX_CHARS)
    }
}
