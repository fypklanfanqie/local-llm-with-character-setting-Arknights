package com.rhodesisland.terminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rhodesisland.terminal.R

/**
 * 本地推理保活前台服务。
 *
 * 由 [com.rhodesisland.terminal.llm.InferenceSessionController] 在 `BackendManager.generate` 期间启停。
 * 生成期间常驻，防国产 ROM 把进程冻结/杀死导致 prefill/流式生成中断；持 `PARTIAL_WAKE_LOCK` 保证
 * CPU 不睡。
 *
 * - `foregroundServiceType="dataSync"`（Android 14 需 manifest 声明 + `FOREGROUND_SERVICE_DATA_SYNC` 权限）。
 * - 通知 channel [CHANNEL_ID] `IMPORTANCE_LOW`（无声），在 [RhodesApp.onCreate] 经 [createChannel] 创建。
 * - `START_NOT_STICKY`：生成结束即停；若被系统杀则推理已终止，无需自动重启。
 *
 - onStartCommand 第一步即 `startForeground`，满足 `startForegroundService` 后 5s 内必须调用的约束。
 */
class InferenceForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_BACKEND_LABEL) ?: "本地 AI"
        startForegroundCompat(NOTIFICATION_ID, buildNotification(label))
        acquireWakeLock()
        return START_NOT_STICKY
    }

    /**
     * startForeground 须在 onStartCommand 第一步。前台类型按 API 分支：
     * - API 35+（targetSdk 35）：`specialUse`——Android 15 起 dataSync 有 6h/24h 超时上限，
     *   本地推理时长不可控会撞线；specialUse 无时限（manifest 已声明 subtype property）。
     * - API 34：`dataSync`——specialUse 类型常量虽在 API 34 引入，但 targetSdk 34 下 dataSync
     *   无超时限制，且 WorkManager/系统对 dataSync 校验路径更成熟。
     * - <34：两参重载（无类型概念）。
     */
    private fun startForegroundCompat(id: Int, notification: Notification) {
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                    startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else ->
                    startForeground(id, notification)
            }
        }.onFailure {
            Log.w(TAG, "startForeground failed: ${it.message}; stopSelf")
            stopSelf() // 启动失败立即自尽，避免空壳前台服务
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            wl.setReferenceCounted(false)
            wl.acquire(WAKE_LOCK_TIMEOUT_MS) // 超时兜底防泄漏（生成罕超 10min）
            wakeLock = wl
        }.onFailure { Log.w(TAG, "acquireWakeLock failed: ${it.message}") }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun buildNotification(label: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("本地 AI 推理中…")
            .setContentText("正在使用 $label 生成回复")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    companion object {
        const val CHANNEL_ID = "inference_running"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_BACKEND_LABEL = "extra_inference_backend_label"
        private const val WAKE_LOCK_TAG = "rhodes:inference"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val TAG = "InferenceFGService"

        /** 在 [com.rhodesisland.terminal.RhodesApp.onCreate] 调用，创建低优先级无声通知渠道。 */
        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "本地推理", NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "本地 AI 推理进行时的保活通知"
                    setShowBadge(false)
                }
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
        }
    }
}
