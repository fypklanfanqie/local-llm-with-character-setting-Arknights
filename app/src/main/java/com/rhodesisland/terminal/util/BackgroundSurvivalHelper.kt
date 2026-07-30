package com.rhodesisland.terminal.util

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 国产 ROM 后台保活辅助。
 *
 * 国产 ROM（HyperOS/MIUI、ColorOS/OPPO/realme、OriginOS/vivo、MagicOS/HONOR、Flyme/Meizu、OneUI 等）
 * 在省电策略下会冻结/杀死后台进程，导致 WorkManager 周期不调度、主动问候收不到。代码无法完全解决，
 * 但可引导用户到各厂商的「自启动 / 后台运行 / 电池优化」设置开关。
 *
 * 两条路径：
 * - [isIgnoringBatteryOptimizations] / [requestIgnoreBatteryOptimizations]：标准 Android 电池优化白名单
 *   （`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，需 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限）。
 * - [manufacturerAutostartIntent]：按 `Build.MANUFACTURER`/`BRAND` 返回厂商自启动管理 Activity 的 intent，
 *   `resolveActivity` 探测不可达时返回 null（UI 隐藏该项）。均加 `FLAG_ACTIVITY_NEW_TASK`。
 *
 * 注意：各厂商 Activity 名随 ROM 版本变化，这里给多个候选逐一探测；全不可达则 UI 不显示该入口，
 * 用户仍可经「电池优化」白名单或应用详情页操作。绝不因入口失效而崩溃。
 */
object BackgroundSurvivalHelper {

    /** 是否已加入电池优化白名单。取不到 PowerManager 时按「已忽略」处理（不误导用户去操作）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 请求加入电池优化白名单（弹出系统确认）。需声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS。 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /** 是否可调度精确闹钟（Android 12+ 需 SCHEDULE_EXACT_ALARM 用户授权）。API < 31 视为可调度。 */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return am.canScheduleExactAlarms()
    }

    /** 跳转「精确闹钟」授权页（Android 12+）。 */
    fun requestScheduleExactAlarm(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * 按厂商返回「自启动 / 后台管理」设置 Activity intent；本机不可达返回 null。
     * 每个候选用 `resolveActivity` 探测，首个可达者返回（并加 `FLAG_ACTIVITY_NEW_TASK`）。无匹配厂商返回 null。
     */
    fun manufacturerAutostartIntent(context: Context): Intent? {
        val mfr = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        val candidates: List<Intent> = buildList {
            // 小米 / HyperOS / Redmi
            if (mfr.contains("xiaomi") || brand.contains("xiaomi") || brand.contains("redmi")) {
                add(component("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                add(component("com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"))
            }
            // OPPO / realme / ColorOS
            if (mfr.contains("oppo") || brand.contains("oppo") || brand.contains("realme")) {
                add(component("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
                add(component("com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"))
                add(component("com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"))
            }
            // vivo / iQOO / OriginOS
            if (mfr.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo")) {
                add(component("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
                add(component("com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
            }
            // HONOR / MagicOS（老 Honor 走 huawei.systemmanager，新 Honor 走 hihonor）
            if (mfr.contains("honor") || brand.contains("honor")) {
                add(component("com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.optimize.process.ProtectActivity"))
                add(component("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
            } else if (mfr.contains("huawei") || brand.contains("huawei")) {
                // HUAWEI / EMUI / HarmonyOS
                add(component("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                add(component("com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"))
            }
            // Meizu / Flyme
            if (mfr.contains("meizu") || brand.contains("meizu")) {
                add(component("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"))
            }
            // Samsung / OneUI
            if (mfr.contains("samsung") || brand.contains("samsung")) {
                add(component("com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"))
            }
        }
        return candidates.firstOrNull { it.resolveActivity(context.packageManager) != null }
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    private fun component(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))
}
