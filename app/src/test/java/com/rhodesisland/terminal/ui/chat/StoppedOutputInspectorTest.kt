package com.rhodesisland.terminal.ui.chat

import com.rhodesisland.terminal.data.model.MessageCompletionState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [StoppedOutputInspector] 纯函数测试（Task 6）。
 */
class StoppedOutputInspectorTest {

    @Test
    fun unclosedThinkIsBeforeFinal() {
        assertEquals(
            MessageCompletionState.STOPPED_BEFORE_FINAL,
            StoppedOutputInspector.inspect("<think>我正在分析这个问题的多种解法"),
        )
    }

    @Test
    fun closedThinkWithoutBodyIsBeforeFinal() {
        assertEquals(
            MessageCompletionState.STOPPED_BEFORE_FINAL,
            StoppedOutputInspector.inspect("<think>思考结束</think>   "),
        )
    }

    @Test
    fun closedThinkWithBodyIsPartial() {
        assertEquals(
            MessageCompletionState.STOPPED_PARTIAL,
            StoppedOutputInspector.inspect("<think>思考</think>答案是 42"),
        )
    }

    @Test
    fun noTagsWithTextIsPartial() {
        assertEquals(
            MessageCompletionState.STOPPED_PARTIAL,
            StoppedOutputInspector.inspect("已生成的正文"),
        )
    }

    @Test
    fun emptyOutputIsBeforeFinal() {
        assertEquals(
            MessageCompletionState.STOPPED_BEFORE_FINAL,
            StoppedOutputInspector.inspect(""),
        )
        assertEquals(
            MessageCompletionState.STOPPED_BEFORE_FINAL,
            StoppedOutputInspector.inspect("   "),
        )
    }
}
