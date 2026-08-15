package com.rhodesisland.terminal.provider.local

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocalStreamRenderPump] 契约测试（Task 4）。
 *
 * 验证：首块立即渲染；高频 append 被 conflated 合并并按节流渲染；finish 取消渲染协程后
 * 同步渲染最终帧且不再触发任何 UI 回调；策略截断正确收缩累加器。
 */
class LocalStreamRenderPumpTest {

    private fun pump(
        scope: kotlinx.coroutines.CoroutineScope,
        minIntervalMs: Long = 30L,
        clock: () -> Long,
    ) = LocalStreamRenderPump(scope = scope, minIntervalMs = minIntervalMs, clock = clock)

    @Test
    fun firstDeltaRendersImmediately() = runTest {
        val rendered = mutableListOf<String>()
        val p = pump(backgroundScope, clock = { testScheduler.currentTime })
        p.decorate = { it }
        p.onChunk = { rendered += it }
        p.start()
        p.append("你好")
        runCurrent()
        assertEquals(listOf("你好"), rendered)
        p.finish()
    }

    @Test
    fun rapidAppendsCoalesceToCadence() = runTest {
        val rendered = mutableListOf<String>()
        val p = pump(backgroundScope, minIntervalMs = 30, clock = { testScheduler.currentTime })
        p.decorate = { it }
        p.onChunk = { rendered += it }
        p.start()

        p.append("a")
        runCurrent()
        assertEquals(listOf("a"), rendered)

        // 第二个信号在节流窗口内被合并：b、c 两个 append 合并为一次渲染
        p.append("b")
        advanceTimeBy(10)
        p.append("c")
        advanceTimeBy(30)
        runCurrent()
        assertEquals("b/c 未合并到一次渲染", listOf("a", "abc"), rendered)
        p.finish()
    }

    @Test
    fun finishRendersFinalFrameAndStopsFurtherCallbacks() = runTest {
        val rendered = mutableListOf<String>()
        val p = pump(backgroundScope, clock = { testScheduler.currentTime })
        p.decorate = { it }
        p.onChunk = { rendered += it }
        p.start()

        p.append("第一段")
        // 不推进时间：渲染协程尚在节流/挂起，finish 取消它并同步渲染最终帧
        p.finish()
        assertEquals(listOf("第一段"), rendered)

        // finish 后任何 append 都不得再触发渲染（避免完成路径追加幽灵 streaming 气泡）
        p.append("第二段")
        runCurrent()
        assertEquals(listOf("第一段"), rendered)
    }

    @Test
    fun truncateToShrinksAccumulator() = runTest {
        val rendered = mutableListOf<String>()
        val p = pump(backgroundScope, clock = { testScheduler.currentTime })
        p.decorate = { it }
        p.onChunk = { rendered += it }
        p.start()

        p.append("ABCDE")
        p.truncateTo(3)
        assertEquals("ABC", p.snapshot())
        p.finish()
        assertEquals(listOf("ABC"), rendered)
    }

    @Test
    fun emptySnapshotsDoNotRender() = runTest {
        val rendered = mutableListOf<String>()
        val p = pump(backgroundScope, clock = { testScheduler.currentTime })
        p.decorate = { it }
        p.onChunk = { rendered += it }
        p.start()
        p.finish()   // 未追加任何内容：不触发 onChunk
        assertTrue(rendered.isEmpty())
    }
}
