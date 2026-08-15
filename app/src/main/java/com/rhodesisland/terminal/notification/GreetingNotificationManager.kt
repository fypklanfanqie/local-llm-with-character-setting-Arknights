package com.rhodesisland.terminal.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.rhodesisland.terminal.MainActivity
import com.rhodesisland.terminal.R

/**
 * 角色问候通知管理。
 *
 * 角色主动发来消息时以类微信横幅通知提醒；点按跳转到该角色的活跃会话。
 * 通知 channel 在 [com.rhodesisland.terminal.RhodesApp.onCreate] 中创建。
 */
object GreetingNotificationManager {

    const val CHANNEL_ID = "character_greeting"

    /** 问候生成期间的前台进度通知渠道（低优先级，无声）。 */
    const val PROGRESS_CHANNEL_ID = "greeting_progress"
    /** 问候 Worker 前台化用的通知 id（与投递通知的 characterId.hashCode() 区分）。 */
    const val PROGRESS_NOTIFICATION_ID = 2001

    /** 通知 PendingIntent extra：目标角色 id。 */
    const val EXTRA_CHARACTER_ID = "extra_greeting_character_id"
    /** 通知 PendingIntent extra：目标会话 id。 */
    const val EXTRA_CONVERSATION_ID = "extra_greeting_conversation_id"

    private const val NOTIFICATION_TAG = "greeting"

    /** 创建通知 channel（Android 8+ 必须）。
     *  - [CHANNEL_ID]：投递通知，importance HIGH -> 横幅（类微信）。
     *  - [PROGRESS_CHANNEL_ID]：生成期间前台保活通知，importance LOW（无声）。 */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val greeting = NotificationChannel(
                CHANNEL_ID, "角色问候", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "角色主动发来的消息提醒"
                enableVibration(true)
            }
            val progress = NotificationChannel(
                PROGRESS_CHANNEL_ID, "问候生成", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "生成角色主动消息时的保活通知"
                setShowBadge(false)
            }
            nm.createNotificationChannel(greeting)
            nm.createNotificationChannel(progress)
        }
    }

    /** 问候生成期间的前台保活通知（低优先级 ongoing），供 GreetingWorker setForeground 用。 */
    fun buildProgressNotification(context: Context): Notification =
        NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在生成角色消息…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    /**
     * 发送一条角色主动消息通知。
     *
     * @param characterId 角色 id（用于点按跳转 + 通知 id）
     * @param conversationId 目标会话 id
     * @param charName 角色名（通知标题）
     * @param message 主动消息内容（通知正文，[BigTextStyle] 展开全文）
     */
    fun notify(context: Context, characterId: String, conversationId: Long, charName: String, message: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            // 复用已存在的任务栈，避免开两层 MainActivity
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHARACTER_ID, characterId)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            characterId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(charName)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .build()
        val nm = context.getSystemService(NotificationManager::class.java)
        // Android 13+ 未授予 POST_NOTIFICATIONS / 通知被系统或用户关闭时 notify 静默 no-op；记日志便于排查。
        if (!nm.areNotificationsEnabled()) {
            android.util.Log.w("GreetingNotif", "通知被关闭，问候已投递但无法弹通知：$charName")
        }
        runCatching { nm.notify(NOTIFICATION_TAG, characterId.hashCode(), notification) }
    }
}
