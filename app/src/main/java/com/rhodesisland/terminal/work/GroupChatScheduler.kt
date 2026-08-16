package com.rhodesisland.terminal.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.repository.SettingsRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * 群聊自动聊天调度器（仅云端可用）。与 [GreetingScheduler] 同构：
 * PeriodicWork 15 分钟一轮驱动 [GroupChatWorker]；投递时机由 `group_next_fire_at` 门控。
 *
 * - [ensureScheduled]：KEEP 语义，App 启动时调用。
 * - [reschedule]：群聊设置变更时重算目标时间；关闭 / 非云端 / 关闭自动聊天时 cancel。
 * - [cancel]：取消周期工作 + 精确闹钟。
 * - [scheduleTest]：独立 OneTime 工作，设置页「测试群聊」用。
 */
object GroupChatScheduler {

    private const val WORK_NAME = "group_chat_work"
    private const val WORK_NAME_TEST = "group_chat_work_test"

    /** WorkData 键：标记本次为用户触发的测试（10s 预览），跳过门控/配额/重排。 */
    const val KEY_TEST = "is_test"

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun scheduleTest(context: Context, delayMillis: Long = 10_000L) {
        val request = OneTimeWorkRequestBuilder<GroupChatWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_TEST to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME_TEST, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        GroupChatAlarmScheduler.cancel(context)
    }

    suspend fun rearmAlarm(context: Context, settings: SettingsRepository) {
        val next = settings.getGroupNextFireAtNow()
        if (next > 0L) GroupChatAlarmScheduler.armNext(context, next)
        else GroupChatAlarmScheduler.cancel(context)
    }

    suspend fun ensureScheduled(context: Context, settings: SettingsRepository) {
        if (isActiveAndCloud(settings) == false) {
            cancel(context)
            return
        }
        enqueuePeriodic(context)
        rearmAlarm(context, settings)
    }

    suspend fun reschedule(context: Context, settings: SettingsRepository) {
        when (isActiveAndCloud(settings)) {
            false -> { cancel(context); return }
            null -> { enqueuePeriodic(context); return }
            true -> { /* 继续重算 next_fire_at */ }
        }
        enqueuePeriodic(context)
        val next = settings.getGroupNextFireAtNow()
        if (next <= 0L) {
            val now = System.currentTimeMillis()
            settings.setGroupNextFireAt(computeNextFireAt(now, remainingToday(settings)))
        }
        rearmAlarm(context, settings)
    }

    private fun enqueuePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<GroupChatWorker>(
            AppConfig.GroupChat.HEARTBEAT_INTERVAL_MIN, TimeUnit.MINUTES,
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    /**
     * 读取「群聊开启 + 自动聊天开启 + 云端模式」。
     * @return true 符合；false 明确不符合；null 设置读取超时（未知）。
     */
    private suspend fun isActiveAndCloud(settings: SettingsRepository): Boolean? {
        val config = settings.getGroupChatConfigOrNull() ?: return null
        if (!config.enabled) return false
        if (!config.autoChat) return false
        return settings.getActiveProviderNow() == ChatProviderType.CLOUD
    }

    private suspend fun remainingToday(settings: SettingsRepository): Int {
        val daily = settings.getGroupDailyRoundsNow()
        val (date, count) = settings.getGroupQuotaNow()
        val used = if (date == todayString()) count else 0
        return (daily - used).coerceAtLeast(0)
    }

    private fun todayString(): String = dateFmt.format(Date())

    /**
     * 计算下一次触发的延迟（毫秒）。纯函数。
     * - 不在时段（[AppConfig.GroupChat.HOUR_START]–[HOUR_END]）-> 下一个 HOUR_START。
     * - 今日轮次已满（remaining <= 0）-> 下一个 HOUR_START。
     * - 在时段内且有余量 -> 在剩余清醒时间内随机分布（抖动 0.6–1.4）。
     */
    fun computeNextDelay(now: Long, remainingToday: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val start = AppConfig.GroupChat.HOUR_START
        val end = AppConfig.GroupChat.HOUR_END

        if (hour < start || hour >= end || remainingToday <= 0) {
            return millisToNextHour(now, start)
        }

        val endCal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, end)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val remainingMs = endCal.timeInMillis - now
        if (remainingMs <= 60_000L) return millisToNextHour(now, start)

        val avg = remainingMs / (remainingToday + 1).coerceAtLeast(1)
        val factor = 0.6 + Random.nextDouble() * 0.8
        val delay = (avg * factor).roundToLong()
        return delay.coerceIn(60_000L, remainingMs)
    }

    fun computeNextFireAt(now: Long, remainingToday: Int): Long =
        now + computeNextDelay(now, remainingToday)

    /** 轮型判定（纯函数）：counter 每 [AppConfig.GroupChat.ASK_USER_EVERY_N_ROUNDS] 轮归零（含首轮）为「问用户」轮，其余为互聊轮。 */
    fun isAskUserRound(counter: Long): Boolean =
        counter % AppConfig.GroupChat.ASK_USER_EVERY_N_ROUNDS == 0L

    private fun millisToNextHour(now: Long, targetHour: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis - now
    }
}