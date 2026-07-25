package com.rhodesisland.terminal.util

import android.content.Context
import android.provider.Settings

/**
 * 设备唯一标识
 *
 * Android 无微信 openid，使用 Android ID 作为用户标识。
 * 替代小程序的 X-Openid header。
 *
 * 兜底 UUID 会落盘，避免 ANDROID_ID 为空时每次冷启动都生成新 ID。
 */
object DeviceId {

    private const val PREFS_NAME = "device_id"
    private const val KEY_ID = "id"

    @Volatile
    private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.let {
            cached = it
            return it
        }

        val id = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            ""
        }
        val result = if (id.isNullOrBlank()) {
            // 兜底：随机 UUID
            java.util.UUID.randomUUID().toString()
        } else {
            id
        }
        prefs.edit().putString(KEY_ID, result).apply()
        cached = result
        return result
    }
}
