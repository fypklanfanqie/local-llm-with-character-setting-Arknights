package com.rhodesisland.terminal.util

import com.rhodesisland.terminal.i18n.L10nRuntime

/**
 * 相对时间格式化（微信朋友圈风格）。纯函数，JVM 可测。
 *
 * 规则：
 * - 1 分钟内 → 刚刚
 * - 1 小时内 → N分钟前
 * - 当日 24 小时内 → N小时前
 * - 昨天 → 昨天 HH:mm（24h 内）
 * - 超过 24h → 同年 MM-dd，跨年 yyyy-MM-dd
 */
object RelativeTime {

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    fun format(createdAt: Long, now: Long): String {
        val delta = now - createdAt
        if (delta < 0) return L10nRuntime.t("刚刚") // 时钟回拨宽容
        if (delta < MINUTE_MS) return L10nRuntime.t("刚刚")
        if (delta < HOUR_MS) return L10nRuntime.format("{0}分钟前", delta / MINUTE_MS)
        if (delta < DAY_MS) return L10nRuntime.format("{0}小时前", delta / HOUR_MS)

        val yesterdayStart = calendarOf(now - DAY_MS).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (createdAt >= yesterdayStart) {
            val c = calendarOf(createdAt)
            return L10nRuntime.format(
                "昨天 {0}:{1}",
                String.format(java.util.Locale.ROOT, "%02d", c.get(java.util.Calendar.HOUR_OF_DAY)),
                String.format(java.util.Locale.ROOT, "%02d", c.get(java.util.Calendar.MINUTE)),
            )
        }

        val created = calendarOf(createdAt)
        val nowYear = calendarOf(now).get(java.util.Calendar.YEAR)
        val createdYear = created.get(java.util.Calendar.YEAR)
        val month = created.get(java.util.Calendar.MONTH) + 1
        val day = created.get(java.util.Calendar.DAY_OF_MONTH)
        return if (createdYear == nowYear) {
            String.format(java.util.Locale.getDefault(), "%02d-%02d", month, day)
        } else {
            String.format(java.util.Locale.getDefault(), "%d-%02d-%02d", createdYear, month, day)
        }
    }

    private fun calendarOf(timeInMillis: Long): java.util.Calendar =
        java.util.Calendar.getInstance().apply { this.timeInMillis = timeInMillis }
}
