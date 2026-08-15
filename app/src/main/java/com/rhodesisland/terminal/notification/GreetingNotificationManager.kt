package com.rhodesisland.terminal.notification

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

    /** 通知 PendingIntent extra：目标角色 id。 */
    const val EXTRA_CHARACTER_ID = "extra_greeting_character_id"
    /** 通知 PendingIntent extra：目标会话 id。 */
    const val EXTRA_CONVERSATION_ID = "extra_greeting_conversation_id"

    private const val NOTIFICATION_TAG = "greeting"

    /** 创建通知 channel（Android 8+ 必须）。importance HIGH -> 横幅通知（类微信）。 */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "角色问候",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "角色主动发来的消息提醒"
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

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
        // Android 13+ 未授予 POST_NOTIFICATIONS 时 notify 静默 no-op（不抛异常）；runCatching 兜底。
        runCatching { nm.notify(NOTIFICATION_TAG, characterId.hashCode(), notification) }
    }
}
