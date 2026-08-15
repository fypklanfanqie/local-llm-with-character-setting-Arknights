package com.rhodesisland.terminal.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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
 * 角色问候调度器。
 *
 * 用 WorkManager 的唯一 [OneTimeWorkRequest] 自延续链实现「每天 N 条、白天随机时间」：
 * 每条消息发出后，[GreetingWorker] 计算下一次延迟并重新入队，链条跨进程死亡/重启自动存活
 * （WorkManager 持久化待执行工作，无需 RECEIVE_BOOT_COMPLETED）。
 *
 * - [ensureScheduled]：KEEP 语义，App 启动时调用，不扰动已排程的待执行工作。
 * - [reschedule]：REPLACE 语义，设置变更 / Worker 发完一条后调用，按新配额重排。
 * - [scheduleNext]：固定延迟入队（Worker 生成失败重试用）。
 */
object GreetingScheduler {

    private const val WORK_NAME = "greeting_work"
    private const val WORK_NAME_TEST = "greeting_work_test"

    /** WorkData 键：标记本次为用户触发的测试（10s 预览），跳过门控/配额/重排。 */
    const val KEY_TEST = "is_test"

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /**
     * 调度一次测试问候（默认 10 秒后）。独立于日常问候链（不同 unique work name），
     * 不计配额、不重排、不依赖开关，仅供用户在设置页预览效果。
     */
    fun scheduleTest(context: Context, delayMillis: Long = 10_000L) {
        val request = OneTimeWorkRequestBuilder<GreetingWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_TEST to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME_TEST, ExistingWorkPolicy.REPLACE, request)
    }

    /** 取消全部已排程的问候工作（关闭开关 / 切到本地时调用）。 */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * 以固定延迟入队下一次问候（REPLACE）。供 Worker 生成失败后按 [AppConfig.Greeting.RETRY_DELAY_MS] 重排。
     */
    fun scheduleNext(context: Context, delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<GreetingWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * 确保自延续链存活（KEEP）：仅当「已开启 + 云端」时，若无待执行工作则排程下一次。
     * App 启动 / 被动恢复时调用；不扰动已排程的定时器。
     */
    suspend fun ensureScheduled(context: Context, settings: SettingsRepository) {
        if (!isEnabledAndCloud(settings)) {
            cancel(context)
            return
        }
        val remaining = remainingToday(settings)
        val delay = computeNextDelay(System.currentTimeMillis(), remaining)
        val request = OneTimeWorkRequestBuilder<GreetingWorker>()
            .setInitialDelay(delay.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            .build()
        // KEEP：若已有待执行工作（如「明早 8 点」），保持不动，避免每次开 App 都重置定时器。
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * 立即重排（REPLACE）：按当前设置与配额重新计算下一次延迟并替换待执行工作。
     * 设置变更（开关/角色/次数）或 Worker 发完一条后调用。
     */
    suspend fun reschedule(context: Context, settings: SettingsRepository) {
        if (!isEnabledAndCloud(settings)) {
            cancel(context)
            return
        }
        val remaining = remainingToday(settings)
        val delay = computeNextDelay(System.currentTimeMillis(), remaining)
        scheduleNext(context, delay)
    }

    private suspend fun isEnabledAndCloud(settings: SettingsRepository): Boolean =
        settings.getGreetingEnabledNow() &&
            settings.getActiveProviderNow() == ChatProviderType.CLOUD

    /** 今日剩余配额 = 每日上限 - 今日已发（跨天则已发归零）。 */
    private suspend fun remainingToday(settings: SettingsRepository): Int {
        val daily = settings.getGreetingDailyCountNow()
        val (date, count) = settings.getGreetingQuotaNow()
        val used = if (date == todayString()) count else 0
        return (daily - used).coerceAtLeast(0)
    }

    private fun todayString(): String = dateFmt.format(Date())

    /**
     * 计算下一次触发的延迟（毫秒）。纯函数，不读设置。
     *
     * - 不在触发时段（[AppConfig.Greeting.HOUR_START]–[HOUR_END]）-> 下一个 HOUR_START。
     * - 今日配额已满（remaining <= 0）-> 下一个 HOUR_START（即次日早晨）。
     * - 在时段内且有余量 -> 在剩余清醒时间内按余量随机分布（抖动 0.6–1.4）。
     */
    fun computeNextDelay(now: Long, remainingToday: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val start = AppConfig.Greeting.HOUR_START
        val end = AppConfig.Greeting.HOUR_END

        if (hour < start || hour >= end || remainingToday <= 0) {
            return millisToNextHour(now, start)
        }

        // 到今日 end 的剩余毫秒
        val endCal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, end)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val remainingMs = endCal.timeInMillis - now
        if (remainingMs <= 60_000L) return millisToNextHour(now, start)

        // 平均间隔 = 剩余时间 / (余量+1)，加随机抖动
        val avg = remainingMs / (remainingToday + 1).coerceAtLeast(1)
        val factor = 0.6 + Random.nextDouble() * 0.8 // 0.6..1.4
        val delay = (avg * factor).roundToLong()
        return delay.coerceIn(60_000L, remainingMs)
    }

    /** 距下一个 targetHour:00 的毫秒数（若今日该时刻已过则为次日）。 */
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
