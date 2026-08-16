package com.rhodesisland.terminal.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rhodesisland.terminal.RhodesApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机完成：重新 arm 问候 / 群聊 alarm。
 *
 * AlarmManager 不跨重启（WorkManager 自带 boot 恢复，PeriodicWork 会自动重新排程，无需此处处理）。
 * 这里仅按已存的目标时间重 arm 两个闹钟；若该时刻已过则 arm 到当前（触发后由 Worker 门控决定
 * 是否执行并重排下一轮）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = (context.applicationContext as? RhodesApp)
                    ?.container?.settingsRepository ?: return@launch
                val nextGreeting = settings.getGreetingNextFireAtNow()
                if (nextGreeting > 0L) {
                    GreetingAlarmScheduler.armNext(context, maxOf(nextGreeting, System.currentTimeMillis()))
                    Log.i("BootReceiver", "re-armed greeting alarm at $nextGreeting")
                }
                val nextGroup = settings.getGroupNextFireAtNow()
                if (nextGroup > 0L) {
                    GroupChatAlarmScheduler.armNext(context, maxOf(nextGroup, System.currentTimeMillis()))
                    Log.i("BootReceiver", "re-armed group chat alarm at $nextGroup")
                }
            } catch (e: Exception) {
                Log.w("BootReceiver", "re-arm failed: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
