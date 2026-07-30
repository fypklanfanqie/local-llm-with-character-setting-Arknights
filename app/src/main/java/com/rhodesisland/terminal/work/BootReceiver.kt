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
 * 开机完成：重新 arm 问候 alarm。
 *
 * AlarmManager 不跨重启（WorkManager 自带 boot 恢复，PeriodicWork 会自动重新排程，无需此处处理）。
 * 这里仅按已存的 `greeting_next_fire_at` 重 arm 闹钟；若该时刻已过则 arm 到当前（触发后由 Worker
 * 门控决定是否投递并重排下一轮）。
 *
 * `getGreetingNextFireAtNow` 为 suspend（DataStore 读），用 `goAsync` + 协程在 IO 调度器上调用，
 * 避免主线程阻塞；`goAsync` 给予 ~10s 窗口，finally 中 `finish()`。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = (context.applicationContext as? RhodesApp)
                    ?.container?.settingsRepository ?: return@launch
                val nextFire = settings.getGreetingNextFireAtNow()
                if (nextFire > 0L) {
                    GreetingAlarmScheduler.armNext(context, maxOf(nextFire, System.currentTimeMillis()))
                    Log.i("BootReceiver", "re-armed greeting alarm at $nextFire")
                }
            } catch (e: Exception) {
                Log.w("BootReceiver", "re-arm failed: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
