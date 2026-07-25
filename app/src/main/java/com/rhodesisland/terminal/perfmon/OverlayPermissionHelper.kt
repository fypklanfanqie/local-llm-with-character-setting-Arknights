package com.rhodesisland.terminal.perfmon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 悬浮窗（SYSTEM_ALERT_WINDOW）权限辅助。
 *
 * 权限流程由 ChatScreen 用 Compose 承载：
 *  1) [hasPermission] 判断是否已授予；
 *  2) 未授予时弹出解释 Dialog（解释为何需要 —— 实时展示推理性能数据：Token 速率、
 *     CPU/GPU/NPU 占用、温度等）；
 *  3) 用户点「前往设置」-> 用 [settingsIntent] 启动系统「显示在其他应用上层」设置页；
 *  4) 返回后通过 [hasPermission] 重新校验，授予则显示浮窗。
 *
 * 说明：提示词原稿里的 activity.startActivityForResult + 手动 onActivityResult 回调
 * 在 ComponentActivity（本工程 MainActivity）上已弃用且不可靠，故本类只提供
 * 「判断 + 构造 Intent」，结果处理交给 Compose 的 rememberLauncherForActivityResult。
 */
object OverlayPermissionHelper {

    /** 是否已拥有「显示在其他应用上层」权限（API23+ 需显式授予；本工程 minSdk=24） */
    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /** 构造跳转「管理悬浮窗权限」设置的 Intent（指向本应用） */
    fun settingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }
}
