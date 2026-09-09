package com.rhodesisland.terminal.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.repository.SettingsRepository
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * 自动发圈调度器（与 [GreetingScheduler] 同构：周期心跳 + next_fire_at 门控）。
 *
 * - [ensureScheduled]：App 启动时 KEEP 入队（读不到设置也保活）；
 * - [reschedule]：设置变更后重算 next_fire_at，让新间隔/角色立刻生效；关闭/本地 cancel；
 * - [cancel]：取消周期工作。
 *
 * 投递时机 = DataStore `moment_next_fire_at`；[MomentWorker] 每周期检查到点才真正生成。
 * 时间精度 = 周期(15min) + ROM 延迟，对「隔几小时发一条」足够。
 */
object MomentScheduler {

    private const val WORK_NAME = "moment_work"

    /** 取消周期性自动发圈（关闭开关 / 切到本地时调用）。 */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** 确保周期工作存活（KEEP）；明确关闭 / 本地 -> cancel。 */
    suspend fun ensureScheduled(context: Context, settings: SettingsRepository) {
        if (isEnabledAndCloud(settings) == false) {
            cancel(context)
            return
        }
        enqueuePeriodic(context)
    }

    /** 设置变更后：确保周期存活 + next_fire_at 未初始化时补算。 */
    suspend fun reschedule(context: Context, settings: SettingsRepository) {
        when (isEnabledAndCloud(settings)) {
            false -> { cancel(context); return }
            null -> { enqueuePeriodic(context); return }
            true -> { /* 继续 */ }
        }
        enqueuePeriodic(context)
        val next = settings.getMomentNextFireAtNow()
        if (next <= 0L) {
            val config = settings.getMomentAutoConfigNow()
            settings.setMomentNextFireAt(computeNextFireAt(System.currentTimeMillis(), config.intervalHours))
        }
    }

    private fun enqueuePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<MomentWorker>(
            AppConfig.Moment.HEARTBEAT_INTERVAL_MIN, TimeUnit.MINUTES,
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    /** @return true 开启+云端 API 就绪；false 明确关闭/未配置；null 设置读取失败（保活语义）。 */
    private suspend fun isEnabledAndCloud(settings: SettingsRepository): Boolean? {
        val config = runCatching { settings.getMomentAutoConfigNow() }.getOrNull() ?: return null
        if (!config.enabled) return false
        // 与聊天 Provider 切换解耦：只要配置过云端 API（key 或内置免费代理），本地聊天下也自动发圈
        return settings.isCloudApiReady()
    }

    /**
     * 严格轮询选角色（自动发圈 Worker 与设置页「测试连接」共用同一条轮换链）：
     * 发上一位（moment_last_char_id）的下一个；无记录/记录失效则从排序后的第一位开始。
     * 调用方在成功投递后写回 [SettingsRepository.setMomentLastCharId] 推进轮换。
     */
    suspend fun pickNextCharacter(settings: SettingsRepository, charIds: Set<String>): String {
        if (charIds.size == 1) return charIds.first()
        val sorted = charIds.sorted()
        val last = settings.getMomentLastCharIdNow()
        val idx = last?.let { sorted.indexOf(it).takeIf { i -> i >= 0 } }
        return if (idx != null) sorted[(idx + 1) % sorted.size] else sorted.first()
    }

    /**
     * 下一次触发绝对时间（epoch ms）= now + intervalHours ± 12.5% 抖动，并钳制到发圈时段内
     * （落进 [AppConfig.Moment.HOUR_START]–[HOUR_END] 之外则推到下一个时段起点）。纯函数。
     */
    fun computeNextFireAt(now: Long, intervalHours: Int): Long {
        val intervalMs = intervalHours.coerceIn(AppConfig.Moment.MIN_INTERVAL_HOURS, AppConfig.Moment.MAX_INTERVAL_HOURS) * 3_600_000L
        val jittered = (intervalMs * (0.875 + Random.nextDouble() * 0.25)).roundToLong()
        val fireAt = now + jittered
        val hour = Calendar.getInstance().apply { timeInMillis = fireAt }.get(Calendar.HOUR_OF_DAY)
        return if (hour in AppConfig.Moment.HOUR_START until AppConfig.Moment.HOUR_END) {
            fireAt
        } else {
            nextWindowStart(fireAt)
        }
    }

    /** 距下一个时段起点（HOUR_START:00）的绝对时间；若当日已过则为次日。 */
    fun nextWindowStart(now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, AppConfig.Moment.HOUR_START)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }
}
