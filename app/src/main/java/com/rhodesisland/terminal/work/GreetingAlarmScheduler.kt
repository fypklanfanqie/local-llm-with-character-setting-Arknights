package com.rhodesisland.terminal.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 问候 exact alarm 补时调度器（WorkManager PeriodicWork 的**补充**，非替代）。
 *
 * 国产 ROM 冻结后台时 WorkManager 周期可能根本不触发。用 [AlarmManager.setExactAndAllowWhileIdle]
 * 在 `greeting_next_fire_at` 时刻从 Doze 唤醒一次，触发 [GreetingAlarmReceiver] -> 即时 OneTime
 * [GreetingWorker]。与 PeriodicWork 共享 `next_fire_at` 门控，先到者投递并推进目标时间，后到者见
 * 未到点跳过--防重复投递。
 *
 * Android 12+ 需 `SCHEDULE_EXACT_ALARM` 权限且用户授权（[AlarmManager.canScheduleExactAlarms]）；
 * 未授权时 [armNext] 全程 no-op，回退纯 WorkManager，绝不会更糟。alarm 不跨重启，由 [BootReceiver] 重 arm。
 */
object GreetingAlarmScheduler {
    private const val TAG = "GreetingAlarm"
    private const val ALARM_REQUEST_CODE = 9001

    /** 在 [fireAt]（epoch ms）arm 一次精确唤醒闹钟；不可调度精确闹钟时 no-op（回退 WorkManager）。 */
    fun armNext(context: Context, fireAt: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            Log.i(TAG, "canScheduleExactAlarms=false，跳过 alarm（回退 WorkManager）")
            return
        }
        val pi = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE,
            Intent(context, GreetingAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            Log.i(TAG, "armed alarm at $fireAt")
        }.onFailure { Log.w(TAG, "setExactAndAllowWhileIdle failed: ${it.message}") }
    }

    /** 取消已 arm 的闹钟（无则 no-op）。 */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE,
            Intent(context, GreetingAlarmReceiver::class.java),
            // FLAG_NO_CREATE：不存在则返回 null，取消 no-op
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        runCatching { am.cancel(pi) }
    }
}
