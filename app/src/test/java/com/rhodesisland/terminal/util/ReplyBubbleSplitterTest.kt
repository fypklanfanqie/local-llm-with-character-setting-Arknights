package com.rhodesisland.terminal.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 微信式分句拆分器测试：每个气泡恰好一句话，标点保留在前一段。
 */
class ReplyBubbleSplitterTest {

    @Test
    fun splitsMultipleSentencesKeepingPunctuation() {
        val out = ReplyBubbleSplitter.split("嗯。让我看看。好了！")
        assertEquals(listOf("嗯。", "让我看看。", "好了！"), out)
    }

    @Test
    fun splitsHalfWidthPunctuationToo() {
        val out = ReplyBubbleSplitter.split("Really? Yes! Okay;")
        assertEquals(listOf("Really?", "Yes!", "Okay;"), out)
    }

    @Test
    fun keepsEllipsisIntact() {
        // 「……」是两个省略号字符，不能从中间断开
        val out = ReplyBubbleSplitter.split("等一下……好吗？")
        assertEquals(listOf("等一下……", "好吗？"), out)
    }

    @Test
    fun singleSentenceReturnsAsIs() {
        val out = ReplyBubbleSplitter.split("今晚来喝一杯吗？")
        assertEquals(listOf("今晚来喝一杯吗？"), out)
    }

    @Test
    fun noTrailingPunctuationStillSplits() {
        // 上一段没有句尾标点、下一段前的空白也能成为断点的情况不存在——
        // 断点只认标点，无标点整段返回
        val out = ReplyBubbleSplitter.split("一句话没有标点")
        assertEquals(listOf("一句话没有标点"), out)
    }

    @Test
    fun blankAndEmptyHandled() {
        assertEquals(emptyList<String>(), ReplyBubbleSplitter.split(""))
        assertEquals(emptyList<String>(), ReplyBubbleSplitter.split("   "))
    }

    @Test
    fun closingQuotesAndBracketsAreBreakPoints() {
        val out = ReplyBubbleSplitter.split("她说「走吧」）然后笑了~")
        assertEquals(listOf("她说「走吧」）", "然后笑了~"), out)
    }
}
