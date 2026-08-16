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
 * 群聊通知管理。
 *
 * 群成员主动发言（互聊轮或提问轮）时以类微信横幅通知提醒；点按跳转到群聊会话。
 * 通知 channel 在 [com.rhodesisland.terminal.RhodesApp.onCreate] 中创建（与问候各自独立渠道）。
 */
object GroupChatNotificationManager {

    const val CHANNEL_ID = "group_chat"

    /** 群聊生成期间的前台进度通知渠道（低优先级，无声）。 */
    const val PROGRESS_CHANNEL_ID = "group_chat_progress"
    /** 群聊 Worker 前台化用的通知 id。 */
    const val PROGRESS_NOTIFICATION_ID = 2002

    /** 通知 PendingIntent extra：目标群聊会话 id。 */
    const val EXTRA_GROUP_CONVERSATION_ID = "extra_group_conversation_id"

    private const val NOTIFICATION_TAG = "group_chat"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val group = NotificationChannel(
                CHANNEL_ID, "群聊", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "群聊成员主动发言与提问提醒"
                enableVibration(true)
            }
            val progress = NotificationChannel(
                PROGRESS_CHANNEL_ID, "群聊生成", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "生成群聊发言时的保活通知"
                setShowBadge(false)
            }
            nm.createNotificationChannel(group)
            nm.createNotificationChannel(progress)
        }
    }

    /** 群聊生成期间的前台保活通知（低优先级 ongoing），供 GroupChatWorker setForeground 用。 */
    fun buildProgressNotification(context: Context): Notification =
        NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("群聊成员正在聊天…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    /**
     * 发送一条群聊发言通知。
     *
     * @param conversationId 群聊会话 id（用于点按跳转）
     * @param speakerName 发言成员名（通知标题）
     * @param message 发言内容（通知正文，[BigTextStyle] 展开全文）
     */
    fun notify(context: Context, conversationId: Long, speakerName: String, message: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_GROUP_CONVERSATION_ID, conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            "group_chat".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(speakerName)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .build()
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.util.Log.w("GroupChatNotif", "通知被关闭，群聊已生成但无法弹通知：$speakerName")
        }
        runCatching { nm.notify(NOTIFICATION_TAG, conversationId.toInt(), notification) }
    }
}