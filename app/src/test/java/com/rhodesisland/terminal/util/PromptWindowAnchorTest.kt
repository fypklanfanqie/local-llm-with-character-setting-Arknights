package com.rhodesisland.terminal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PromptWindowAnchor 纯函数测试。
 *
 * 核心（缓存）性质不是「各轮结果相等」——量子块内每轮结果长度递增——而是
 * **每轮结果是上一轮的前缀扩展**（同一起点向后追加），这正是云端 prompt 前缀缓存复用所需的；
 * 跨越量子边界那一轮允许起点前移一次（可接受的取舍）。同时验证通用不变量：
 * 结果恒为原列表后缀、保留数 ≤ max、最后一条必保留、退化参数安全收敛。
 */
class PromptWindowAnchorTest {

    private fun msgs(n: Int) = (1..n).map { "m-$it" }

    // ===== 边界：不触发截断 =====

    @Test
    fun anchoredWindow_emptyListReturnsEmpty() {
        assertEquals(emptyList<String>(), PromptWindowAnchor.anchoredWindow(emptyList<String>(), 100))
    }

    @Test
    fun anchoredWindow_belowCapReturnedAsIs() {
        val src = msgs(99)
        assertEquals(src, PromptWindowAnchor.anchoredWindow(src, 100))
    }

    @Test
    fun anchoredWindow_atCapReturnedAsIs() {
        val src = msgs(100)
        assertEquals(src, PromptWindowAnchor.anchoredWindow(src, 100))
    }

    // ===== 截断行为 =====

    @Test
    fun anchoredWindow_oneOverCapDropsExactlyOneStep() {
        val src = msgs(101)
        val out = PromptWindowAnchor.anchoredWindow(src, 100)
        assertEquals(src.drop(PromptWindowAnchor.TRIM_STEP), out)
        assertEquals(100 - PromptWindowAnchor.TRIM_STEP + 1, out.size)
    }

    @Test
    fun anchoredWindow_prefixExtendsWithinQuantum() {
        // 核心断言：溢出 1..step-1 条的过程中，窗口起点不动，每轮结果 = 上轮结果 + 追加尾部
        val base = msgs(300)
        var prev = PromptWindowAnchor.anchoredWindow(base.take(101), 100)
        val expectedFirst = base[PromptWindowAnchor.TRIM_STEP]
        for (n in 102..100 + PromptWindowAnchor.TRIM_STEP - 1) {
            val out = PromptWindowAnchor.anchoredWindow(base.take(n), 100)
            assertEquals("n=$n 起点", expectedFirst, out.first())
            assertEquals("n=$n 前缀扩展", prev, out.take(prev.size))
            prev = out
        }
    }

    @Test
    fun anchoredWindow_crossingQuantumIsStillASuffixOfSource() {
        // 跨量子边界（n=max+s+1）允许起点前移一次，但通用不变量必须成立：结果 = 原列表后缀
        val src = msgs(121)
        val out = PromptWindowAnchor.anchoredWindow(src, 100)
        assertEquals(src.takeLast(out.size), out)
        assertEquals(msgs(300).let { PromptWindowAnchor.anchoredWindow(it.take(121), 100) }, out)
    }

    @Test
    fun anchoredWindow_farOverCapBoundsSizeAndKeepsLast() {
        val src = msgs(300)
        val out = PromptWindowAnchor.anchoredWindow(src, 100)
        assertTrue("size=${out.size}", out.size in (100 - PromptWindowAnchor.TRIM_STEP + 1)..100)
        assertEquals("m-300", out.last())
        assertEquals(src.takeLast(out.size), out)
    }

    // ===== 退化参数 =====

    @Test
    fun anchoredWindow_nonPositiveMaxReturnsEmpty() {
        assertEquals(emptyList<String>(), PromptWindowAnchor.anchoredWindow(msgs(5), 0))
        assertEquals(emptyList<String>(), PromptWindowAnchor.anchoredWindow(msgs(5), -3))
    }

    @Test
    fun anchoredWindow_stepBelowOneBehavesAsTakeLast() {
        val src = msgs(105)
        val out = PromptWindowAnchor.anchoredWindow(src, 100, step = 0)
        assertEquals(src.takeLast(100), out)
    }

    @Test
    fun anchoredWindow_stepAboveMaxConvergesToKeepOne() {
        val src = msgs(101)
        val out = PromptWindowAnchor.anchoredWindow(src, 100, step = 500)
        assertEquals(listOf("m-101"), out)
    }

    // ===== 群聊参数组合（cap=40 / step=10）=====

    @Test
    fun anchoredWindow_groupChatParamsMatchBuilderExpectation() {
        val src = msgs(50) // 与 GroupChatPromptBuilderTest.takesLastContextMessages 同构
        val out = PromptWindowAnchor.anchoredWindow(
            src, com.rhodesisland.terminal.config.AppConfig.GroupChat.MAX_CONTEXT_MESSAGES,
            step = PromptWindowAnchor.GROUP_TRIM_STEP,
        )
        assertEquals(40, out.size)
        assertEquals("m-11", out.first())

        // 未跨量子边界（excess≤10）：丢 10 条后起点稳定，逐轮前缀扩展
        val base = msgs(300)
        val at50 = PromptWindowAnchor.anchoredWindow(base.take(50), 40, step = 10)
        val at49 = PromptWindowAnchor.anchoredWindow(base.take(49), 40, step = 10)
        assertEquals(at50.take(at49.size), at49)
    }
}
