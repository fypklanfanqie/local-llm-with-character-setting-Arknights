package com.rhodesisland.terminal.ui.chat

import com.rhodesisland.terminal.data.model.MessageCompletionState

/**
 * 停止输出分类器（纯函数，JVM 可测）。
 *
 * 用户点击「停止」后，根据已生成的展示文本判断属于哪种停止状态：
 * - [MessageCompletionState.STOPPED_PARTIAL]：已有最终正文（思考闭合后存在正文，或根本没有
 *   思考标签但有非空文本）。
 * - [MessageCompletionState.STOPPED_BEFORE_FINAL]：只有未闭合 `<think>`、思考闭合但正文为空、
 *   或完全没有输出——即「尚未生成最终答案」。
 *
 * 只观察 [displayText]（展示文本），不修改 `content`/`modelContent`；停止状态以独立字段
 * 渲染 badge，绝不拼进喂给模型的文本。
 */
object StoppedOutputInspector {

    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"

    fun inspect(displayText: String): MessageCompletionState {
        val closeIdx = displayText.indexOf(THINK_CLOSE)
        if (closeIdx >= 0) {
            // 思考已闭合：闭合后的正文非空 -> 已有部分最终答案。
            val body = displayText.substring(closeIdx + THINK_CLOSE.length)
            return if (body.isNotBlank()) {
                MessageCompletionState.STOPPED_PARTIAL
            } else {
                MessageCompletionState.STOPPED_BEFORE_FINAL
            }
        }
        if (displayText.contains(THINK_OPEN)) {
            // 只有未闭合思考段：推理尚未结束，谈不上最终答案。
            return MessageCompletionState.STOPPED_BEFORE_FINAL
        }
        // 无思考标签：有非空文本即视为部分正文；空输出视为尚无最终答案。
        return if (displayText.isBlank()) {
            MessageCompletionState.STOPPED_BEFORE_FINAL
        } else {
            MessageCompletionState.STOPPED_PARTIAL
        }
    }
}
