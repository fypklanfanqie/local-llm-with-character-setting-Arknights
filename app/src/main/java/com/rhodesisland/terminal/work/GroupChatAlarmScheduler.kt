package com.rhodesisland.terminal.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 群聊 exact alarm 补时调度器（WorkManager PeriodicWork 的**补充**，非替代）。
 *
 * 与 [GreetingAlarmScheduler] 同构、独立 request code：在 `group_next_fire_at` 时刻从 Doze 唤醒一次，
 * 触发 [GroupChatAlarmReceiver] -> 即时 OneTime [GroupChatWorker]。与 PeriodicWork 共享 `next_fire_at`
 * 门控，先到者执行并推进目标时间，后到者见未到点跳过，防重复。
 */
object GroupChatAlarmScheduler {
    private const val TAG = "GroupChatAlarm"
    private const val ALARM_REQUEST_CODE = 9002

    fun armNext(context: Context, fireAt: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            Log.i(TAG, "canScheduleExactAlarms=false，跳过 alarm（回退 WorkManager）")
            return
        }
        val pi = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE,
            Intent(context, GroupChatAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            Log.i(TAG, "armed alarm at $fireAt")
        }.onFailure { Log.w(TAG, "setExactAndAllowWhileIdle failed: ${it.message}") }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE,
            Intent(context, GroupChatAlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        runCatching { am.cancel(pi) }
    }
}