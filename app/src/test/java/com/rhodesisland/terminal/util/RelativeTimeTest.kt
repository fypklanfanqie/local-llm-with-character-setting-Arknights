package com.rhodesisland.terminal.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * [RelativeTime] 契约测试（朋友圈相对时间）。
 */
class RelativeTimeTest {

    private fun at(y: Int, month0: Int, d: Int, h: Int, min: Int): Long = Calendar.getInstance().apply {
        set(y, month0, d, h, min, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun justNow() {
        val now = at(2026, 7, 31, 12, 0)
        assertEquals("刚刚", RelativeTime.format(now - 10_000L, now))
        assertEquals("刚刚", RelativeTime.format(now - 59_000L, now))
    }

    @Test
    fun minutesAgo() {
        val now = at(2026, 7, 31, 12, 0)
        assertEquals("4分钟前", RelativeTime.format(now - 4 * 60_000L, now))
        assertEquals("59分钟前", RelativeTime.format(now - 59 * 60_000L, now))
    }

    @Test
    fun hoursAgo() {
        val now = at(2026, 7, 31, 18, 0)
        assertEquals("3小时前", RelativeTime.format(now - 3 * 3_600_000L, now))
        assertEquals("23小时前", RelativeTime.format(now - 23 * 3_600_000L, now))
    }

    @Test
    fun yesterday() {
        val now = at(2026, 7, 31, 10, 0)
        // 25 小时前 = 昨天 09:00
        assertEquals("昨天 09:00", RelativeTime.format(now - 25 * 3_600_000L, now))
    }

    @Test
    fun sameYearDate() {
        val now = at(2026, 7, 31, 10, 0)
        assertEquals("08-20", RelativeTime.format(at(2026, 7, 20, 9, 30), now))
    }

    @Test
    fun crossYearDate() {
        val now = at(2026, 0, 5, 10, 0)
        assertEquals("2025-12-30", RelativeTime.format(at(2025, 11, 30, 9, 0), now))
    }

    @Test
    fun futureClampToJustNow() {
        val now = at(2026, 7, 31, 12, 0)
        assertEquals("刚刚", RelativeTime.format(now + 60_000L, now))
    }
}
