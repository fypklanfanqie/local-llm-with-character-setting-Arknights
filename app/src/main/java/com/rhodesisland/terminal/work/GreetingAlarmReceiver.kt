package com.rhodesisland.terminal.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * 问候精确闹钟接收器：[GreetingAlarmScheduler.armNext] 在 `greeting_next_fire_at` 设置的闹钟到点时触发。
 *
 * 立即排一个 OneTime [GreetingWorker]（复用现有门控：到点投递、否则跳过；写回 next_fire_at 时由
 * Worker 一并 arm 下一轮）。与 PeriodicWork 用不同 unique work 名，二者并存、靠 `next_fire_at` 门控
 * 防重复投递。
 */
class GreetingAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val request = OneTimeWorkRequestBuilder<GreetingWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.KEEP, request,
        )
    }

    companion object {
        // 与 PeriodicWork 的 "greeting_work" 区分：闹钟路径走独立 OneTime unique work，不影响周期工作。
        private const val WORK_NAME = "greeting_work_alarm"
    }
}
