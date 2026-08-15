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
 * 角色问候调度器。
 *
 * 用 WorkManager 的**周期性工作**（[PeriodicWorkRequestBuilder]，15 分钟一轮）取代原先的
 * 自延续链。关键差异：自延续链「发一条 -> reschedule 下一条」一旦某次预定执行因进程被杀 /
 * 国产 ROM 省电冻结而**没发生**，就没有 reschedule，链条**永久断裂**（用户退出 App 后不再打开
 * 即无法恢复 -> 收不到问候）。周期性工作错失一次，下个周期仍会触发，链条不会因进程死亡而断--
 * 这正是「让它在后台等待」所需。
 *
 * 投递时机由 DataStore 中的 `greeting_next_fire_at`（epoch ms）决定：[GreetingWorker] 每个周期
 * 检查 `now >= next_fire_at` 才真正投递，发完后用 [computeNextDelay] 算出下一个目标时间写回。
 * 时间精度 = 周期(15min) + ROM 延迟，对「白天随机时间」足够。
 *
 * - [ensureScheduled]：KEEP 语义，App 启动时调用，不扰动已排程的周期工作。
 * - [reschedule]：设置变更时调用，重算 `next_fire_at` 让新配额/角色立刻生效；关闭/本地时 cancel。
 * - [cancel]：取消周期工作（关闭开关 / 切到本地）。
 * - [scheduleTest]：独立 OneTime 工作，设置页预览用，与日常周期工作互不影响。
 */
object GreetingScheduler {

    private const val WORK_NAME = "greeting_work"
    private const val WORK_NAME_TEST = "greeting_work_test"

    /** WorkData 键：标记本次为用户触发的测试（10s 预览），跳过门控/配额/重排。 */
    const val KEY_TEST = "is_test"

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /**
     * 调度一次测试问候（默认 10 秒后）。独立于日常问候周期（不同 unique work name），
     * 不计配额、不依赖开关，仅供用户在设置页预览效果。
     */
    fun scheduleTest(context: Context, delayMillis: Long = 10_000L) {
        val request = OneTimeWorkRequestBuilder<GreetingWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_TEST to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME_TEST, ExistingWorkPolicy.REPLACE, request)
    }

    /** 取消周期性问候工作（关闭开关 / 切到本地时调用）。 */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        GreetingAlarmScheduler.cancel(context) // 同步取消 exact alarm
    }

    /** 按当前 next_fire_at 重 arm 闹钟（<=0 则取消）。与 PeriodicWork 并行，门控防重复投递。 */
    suspend fun rearmAlarm(context: Context, settings: SettingsRepository) {
        val next = settings.getGreetingNextFireAtNow()
        if (next > 0L) GreetingAlarmScheduler.armNext(context, next)
        else GreetingAlarmScheduler.cancel(context)
    }

    /**
     * 确保周期性问候工作存活（KEEP）：入队一个 15 分钟周期的 PeriodicWork。
     * App 启动 / 被动恢复时调用；KEEP 不扰动已排程的周期工作。
     *
     * 设置读取失败（DataStore 冷启动超时）时**仍入队保活**--周期工作会持续触发，Worker 触发时
     * 再带重试读设置；绝不因读不到设置而让周期工作消失。
     *
     * 明确关闭 / 本地模式 -> cancel（让周期工作彻底停止，省电）。
     */
    suspend fun ensureScheduled(context: Context, settings: SettingsRepository) {
        if (isEnabledAndCloud(settings) == false) {
            cancel(context)
            return
        }
        // null（读不到设置）或 true：都入队保活。Worker 内部按设置决定是否真正投递。
        enqueuePeriodic(context)
        rearmAlarm(context, settings)
    }

    /**
     * 设置变更后重算下一次投递目标时间。**不动 PeriodicWork 本身**（周期固定 15 分钟），
     * 只更新 `next_fire_at` 让新配额/角色立刻影响投递节奏。
     *
     * - 明确关闭 / 本地模式 -> cancel 周期工作。
     * - 读不到设置（null）-> 保活入队，不动 next_fire_at（Worker 下次再读再算）。
     * - 已开启 + 云端 -> 确保 PeriodicWork 存活；若 `next_fire_at` 未设置(<=0) 则初始化一个随机延迟，
     *   避免首次启用后立刻投递（应等下一个随机时刻）。
     */
    suspend fun reschedule(context: Context, settings: SettingsRepository) {
        when (isEnabledAndCloud(settings)) {
            false -> { cancel(context); return }
            null -> { enqueuePeriodic(context); return } // 保活，等下次周期再读设置
            true -> { /* 继续重算 next_fire_at */ }
        }
        enqueuePeriodic(context) // KEEP 幂等，确保周期工作存活
        val next = settings.getGreetingNextFireAtNow()
        if (next <= 0L) {
            val now = System.currentTimeMillis()
            settings.setGreetingNextFireAt(computeNextFireAt(now, remainingToday(settings)))
        }
        rearmAlarm(context, settings) // 按更新后的 next_fire_at 重 arm 闹钟
    }

    /** 入队 15 分钟周期工作（KEEP：已存在则保持不动，不重置周期计时）。 */
    private fun enqueuePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<GreetingWorker>(
            AppConfig.Greeting.HEARTBEAT_INTERVAL_MIN, TimeUnit.MINUTES,
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    /**
     * 读取「问候已开启 + 云端模式」。
     * @return true 符合；false 明确不符合（已关闭或本地）；null 设置读取超时（未知）。
     */
    private suspend fun isEnabledAndCloud(settings: SettingsRepository): Boolean? {
        val enabled = settings.getGreetingEnabledOrNull() ?: return null
        if (!enabled) return false
        // provider 超时回退 CLOUD（安全默认：当作可用继续），不会误杀链条
        return settings.getActiveProviderNow() == ChatProviderType.CLOUD
    }

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

    /** 下一次投递的绝对目标时间（epoch ms）= now + [computeNextDelay]。纯函数。 */
    fun computeNextFireAt(now: Long, remainingToday: Int): Long =
        now + computeNextDelay(now, remainingToday)

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
