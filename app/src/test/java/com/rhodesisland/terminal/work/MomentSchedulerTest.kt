package com.rhodesisland.terminal.work

import com.rhodesisland.terminal.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [MomentScheduler] 纯函数契约测试（触发时间计算）。
 */
class MomentSchedulerTest {

    private fun cal(timeInMillis: Long): Calendar = Calendar.getInstance().apply { this.timeInMillis = timeInMillis }

    @Test
    fun computeNextFireAt_withinInterval_andInsideWindow() {
        // 用一个肯定在时段内（10 点）的 now，大间隔 + 抖动 ±12.5% 仍在 8-23 点内
        val now = System.currentTimeMillis()
        val c = cal(now)
        // 把 now 摆到 10:00
        c.set(Calendar.HOUR_OF_DAY, 10)
        c.set(Calendar.MINUTE, 0)
        val base = c.timeInMillis
        repeat(20) {
            val fireAt = MomentScheduler.computeNextFireAt(base, 6)
            assertTrue("fireAt=$fireAt", fireAt > base)
            val hour = cal(fireAt).get(Calendar.HOUR_OF_DAY)
            assertTrue("hour=$hour", hour >= AppConfig.Moment.HOUR_START && hour < AppConfig.Moment.HOUR_END)
        }
    }

    @Test
    fun computeNextFireAt_outsideWindow_pushesToNextStart() {
        // 23:30 触发 -> 落到次日 8:00
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
        }
        val fireAt = MomentScheduler.computeNextFireAt(c.timeInMillis, 6)
        val fc = cal(fireAt)
        assertEquals(AppConfig.Moment.HOUR_START, fc.get(Calendar.HOUR_OF_DAY))
        assertTrue(fireAt > c.timeInMillis)
    }

    @Test
    fun nextWindowStart_todayMorning() {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 6)
            set(Calendar.MINUTE, 0)
        }
        val next = MomentScheduler.nextWindowStart(c.timeInMillis)
        val nc = cal(next)
        assertEquals(AppConfig.Moment.HOUR_START, nc.get(Calendar.HOUR_OF_DAY))
        assertTrue(next > c.timeInMillis)
    }
}
