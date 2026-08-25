package com.rhodesisland.terminal.util

/**
 * LLM 历史窗口锚定截断（纯函数，JVM 可测）。
 *
 * 云端服务商的 prompt 前缀缓存按「请求前缀字节一致」复用：普通 [kotlin.collections.takeLast]
 * 每新增一条消息就把窗口起点前移一格，长对话每轮前缀全变、命中率归零。[anchoredWindow] 把
 * 丢弃条数向上取整到 [step] 的整数倍——溢出量在一个量子块内增长时窗口起点不动，连续多轮
 * 请求共享同一稳定前缀；跨越量子边界那轮一次性多丢最多 step-1 条，换取后续约 step 轮的前缀稳定。
 *
 * 不变量：结果恒为原列表后缀（最后一条必保留）；保留数 ≤ max 且 ≥ 1（max > 0 时）；
 * 同一量子块内各轮结果互为前缀扩展（起点相同、逐轮向后追加）。
 */
object PromptWindowAnchor {

    /** 单聊默认步长：cap=100 最坏少带 19 条(~19%)，每个量子块覆盖约 10 轮对话。 */
    const val TRIM_STEP = 20

    /** 群聊专用步长：cap=40 用 20 会砍半窗口；10 损失 ≤9 条且与自动聊每轮 +2 条节奏匹配。 */
    const val GROUP_TRIM_STEP = 10

    /**
     * 锚定截断：取 [list] 尾部窗口，丢弃条数向上取整到 [step] 整数倍以稳定窗口起点。
     * size ≤ max 原样返回；max ≤ 0 返回空；[step] < 1 按 1 处理（等价 takeLast）、大于 max 收敛到 max。
     */
    fun <T> anchoredWindow(list: List<T>, max: Int, step: Int = TRIM_STEP): List<T> {
        if (list.size <= max) return list
        if (max <= 0) return emptyList()
        val s = step.coerceIn(1, max)
        val excess = list.size - max
        val drop = ((excess + s - 1) / s) * s
        return list.subList(drop, list.size).toList()
    }
}
