package com.rhodesisland.terminal.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * 群聊精确闹钟接收器：[GroupChatAlarmScheduler.armNext] 在 `group_next_fire_at` 设置的闹钟到点时触发。
 * 立即排一个 OneTime [GroupChatWorker]（复用门控），与 PeriodicWork 用不同 unique work 名，二者并存、
 * 靠 `next_fire_at` 门控防重复。
 */
class GroupChatAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val request = OneTimeWorkRequestBuilder<GroupChatWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.KEEP, request,
        )
    }

    companion object {
        private const val WORK_NAME = "group_chat_work_alarm"
    }
}