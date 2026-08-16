package com.rhodesisland.terminal.work

import com.rhodesisland.terminal.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 群聊自动聊天调度纯函数测试：
 * - [GroupChatScheduler.computeNextDelay]/[computeNextFireAt]：时段外/配额满 -> 次日 HOUR_START；时段内 jitter 有界。
 * - [GroupChatScheduler.isAskUserRound]：轮型周期性翻转。
 *
 * 同一时刻的 local-timezone 断点用 Calendar 构造，测试不依赖机器时区。
 */
class GroupChatSchedulerLogicTest {

    private fun at(hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun computeNextFireAt_outsideWindow_targetsNextHourStart() {
        // 23 点在 09:00–22:00 时段外 -> 次日 09:00
        val fireAt = GroupChatScheduler.computeNextFireAt(at(23), remainingToday = 8)
        val cal = Calendar.getInstance().apply { timeInMillis = fireAt }
        assertEquals(AppConfig.GroupChat.HOUR_START, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertTrue(fireAt > at(23))
    }

    @Test
    fun computeNextFireAt_beforeWindow_targetsTodayHourStart() {
        // 7 点在时段外且早于 HOUR_START -> 当日 09:00
        val fireAt = GroupChatScheduler.computeNextFireAt(at(7), remainingToday = 8)
        val cal = Calendar.getInstance().apply { timeInMillis = fireAt }
        assertEquals(AppConfig.GroupChat.HOUR_START, cal.get(Calendar.HOUR_OF_DAY))
        assertTrue(fireAt > at(7))
    }

    @Test
    fun computeNextFireAt_quotaExhausted_targetsNextHourStart() {
        // 时段内但配额满 -> 次日 09:00
        val fireAt = GroupChatScheduler.computeNextFireAt(at(12), remainingToday = 0)
        val cal = Calendar.getInstance().apply { timeInMillis = fireAt }
        assertEquals(AppConfig.GroupChat.HOUR_START, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun computeNextDelay_inWindow_boundedBetweenMinuteAndWindowEnd() {
        val now = at(12)
        val remainingMs = at(AppConfig.GroupChat.HOUR_END) - now // 到 22:00
        val delay = GroupChatScheduler.computeNextDelay(now, remainingToday = 8)
        assertTrue("delay=$delay 应 >= 60s", delay >= 60_000L)
        assertTrue("delay=$delay 应 <= 窗口剩余 $remainingMs", delay <= remainingMs)
    }

    @Test
    fun isAskUserRound_cyclesEveryN() {
        val n = AppConfig.GroupChat.ASK_USER_EVERY_N_ROUNDS
        assertTrue("首轮应为问问用户轮", GroupChatScheduler.isAskUserRound(0L))
        assertFalse("第 2 轮应为互聊轮", GroupChatScheduler.isAskUserRound(1L))
        assertFalse("第 N 轮应为互聊轮", GroupChatScheduler.isAskUserRound(n.toLong() - 1L))
        assertTrue("第 N+1 轮应回到问问用户轮", GroupChatScheduler.isAskUserRound(n.toLong()))
    }
}