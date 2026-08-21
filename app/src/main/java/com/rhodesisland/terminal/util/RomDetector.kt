package com.rhodesisland.terminal.util

import android.os.Build
import java.util.Locale

/**
 * 厂商 ROM 识别。
 *
 * Android 无统一的「当前 ROM」API，各厂商判断方式不一（部分有官方 sysprop，部分只能靠
 * MANUFACTURER 推断）。本类综合两类信号：
 * 1. **系统属性（getprop）**：经反射 `android.os.SystemProperties.get` 读取（@hide API，
 *    全版本可用）；属性存在即命中，最可靠。
 * 2. **Build.MANUFACTURER/BRAND**：getprop 不可靠的 ROM（如 ColorOS 的
 *    `ro.build.version.opporom` 在多数机型为空）按厂商推断兜底。
 *
 * 用途：设置页「后台保活」引导区显示当前系统名称、按 ROM 决定展示哪些引导项
 * （如小米系的「后台弹出界面」独立开关）。识别失败返回 [RomType.UNKNOWN]，UI 隐藏定制项，
 * 绝不影响功能。
 */
object RomDetector {

    /** 识别出的 ROM 类型（含中文显示名）。 */
    enum class RomType(val displayName: String) {
        MIUI("MIUI（小米）"),
        HYPEROS("HyperOS（小米）"),
        EMUI("EMUI（华为）"),
        HARMONYOS("HarmonyOS（华为）"),
        MAGIC_OS("MagicOS（荣耀）"),
        COLOR_OS("ColorOS（OPPO/一加/realme）"),
        ORIGIN_OS("OriginOS/FuntouchOS（vivo/iQOO）"),
        FLYME("Flyme（魅族）"),
        ONE_UI("One UI（三星）"),
        UNKNOWN("标准 Android"),
    }

    /** 检测结果：类型 + 属性里带出的版本串（如 "V130" / "4.0"，取不到则空）。 */
    data class RomInfo(val type: RomType, val version: String)

    @Volatile
    private var cached: RomInfo? = null

    /**
     * 识别当前 ROM（结果缓存；ROM 不可能在进程生命周期内变化）。
     * 检测顺序按「属性可靠性」从高到低：MIUI/HyperOS/EMUI/OriginOS/Flyme/OneUI 的官方属性 →
     * HarmonyOS/MagicOS 类反射 → ColorOS 属性辅助 + 厂商推断兜底。
     */
    fun detect(): RomInfo {
        cached?.let { return it }
        val info = detectUncached()
        cached = info
        return info
    }

    private fun detectUncached(): RomInfo {
        // —— 小米：MIUI 与 HyperOS（Android 14 起小米更名 HyperOS，新属性 ro.mi.os.version.name）
        prop("ro.mi.os.version.name")?.let { return RomInfo(RomType.HYPEROS, it) }
        prop("ro.miui.ui.version.name")?.let { return RomInfo(RomType.MIUI, it) }
        // —— 华为 EMUI / MagicOS（荣耀）：官方属性（EMUI 12+ 部分机型已移除该属性，靠厂商兜底）
        prop("ro.build.version.emui")?.let { return RomInfo(RomType.EMUI, it) }
        prop("ro.build.version.magic")?.let { return RomInfo(RomType.MAGIC_OS, it) }
        // —— vivo OriginOS / FuntouchOS
        prop("ro.vivo.os.version")?.let { return RomInfo(RomType.ORIGIN_OS, it) }
        prop("ro.vivo.product.brand")?.let { return RomInfo(RomType.ORIGIN_OS, it) }
        // —— 魅族 Flyme
        prop("ro.flyme.published")?.let { if (it.isNotBlank()) return RomInfo(RomType.FLYME, it) }
        prop("ro.build.display.id")?.takeIf { it.contains("flyme", true) }
            ?.let { return RomInfo(RomType.FLYME, it) }
        // —— 三星 One UI（如 "14011" = One UI 6.1.1；空值常见于老机型）
        prop("ro.build.version.oneui")?.takeIf { it.isNotBlank() }
            ?.let { return RomInfo(RomType.ONE_UI, it) }

        // —— 鸿蒙：反射 com.huawei.system.BuildEx.getOsBrand() == "harmony"（HarmonyOS 2/3 官方判法；
        //    HarmonyOS NEXT 及部分新机型无此属性，靠厂商兜底）
        try {
            val clz = Class.forName("com.huawei.system.BuildEx")
            val brand = clz.getMethod("getOsBrand").invoke(clz) as? String
            if (brand == "harmony") return RomInfo(RomType.HARMONYOS, "")
        } catch (_: Throwable) {
            // 非 Huawei 设备类不存在，正常路径
        }
        // —— MagicOS 兜底：荣耀 12+ 官方建议探测 hihonor framework 类
        if (isManufacturerOrBrand("honor")) {
            try {
                Class.forName("com.hihonor.android.launcher.Launcher")
                return RomInfo(RomType.MAGIC_OS, "")
            } catch (_: Throwable) {
                // 类不存在仍按 honor 厂商归 MagicOS（所有荣耀机均为 MagicOS/旧 Magic UI）
                return RomInfo(RomType.MAGIC_OS, Build.DISPLAY ?: "")
            }
        }

        // —— 华为兜底：EMUI 属性被移除的新机型
        if (isManufacturerOrBrand("huawei")) {
            return if (Build.VERSION.SDK_INT >= 30) RomInfo(RomType.HARMONYOS, Build.DISPLAY ?: "")
            else RomInfo(RomType.EMUI, Build.DISPLAY ?: "")
        }

        // —— OPPO 系 ColorOS：ro.build.version.opporom 多数机型实测为空（文章验证），仅作辅助；
        //    以厂商判定为准（OPPO/OnePlus/realme 全系均运行 ColorOS）
        val opporom = prop("ro.build.version.opporom")
        if (opporom != null && opporom.isNotBlank()) {
            return RomInfo(RomType.COLOR_OS, opporom)
        }
        if (listOf("oppo", "oneplus", "realme").any { isManufacturerOrBrand(it) }) {
            return RomInfo(RomType.COLOR_OS, Build.DISPLAY ?: "")
        }

        // —— 其余厂商兜底归类（vivo/魅族/三星属性缺失的老机型）
        if (isManufacturerOrBrand("vivo") || isManufacturerOrBrand("iqoo")) {
            return RomInfo(RomType.ORIGIN_OS, Build.DISPLAY ?: "")
        }
        if (isManufacturerOrBrand("meizu")) return RomInfo(RomType.FLYME, Build.DISPLAY ?: "")
        if (isManufacturerOrBrand("samsung")) return RomInfo(RomType.ONE_UI, Build.DISPLAY ?: "")

        // 小米系最后兜底（避免把红米误判成标准 Android——redmi 品牌 MANUFACTURER 可能是 "Xiaomi"）
        if (isManufacturerOrBrand("xiaomi") || isManufacturerOrBrand("redmi")) {
            return if (Build.VERSION.SDK_INT >= 34) RomInfo(RomType.HYPEROS, Build.DISPLAY ?: "")
            else RomInfo(RomType.MIUI, Build.DISPLAY ?: "")
        }

        return RomInfo(RomType.UNKNOWN, Build.VERSION.RELEASE ?: "")
    }

    private fun isManufacturerOrBrand(name: String): Boolean {
        val mfr = (Build.MANUFACTURER ?: "").lowercase(Locale.ROOT)
        val brand = (Build.BRAND ?: "").lowercase(Locale.ROOT)
        return mfr.contains(name) || brand.contains(name)
    }

    /**
     * 反射读系统属性（@hide android.os.SystemProperties.get）。属性不存在返回 null，
     * 存在但为空串返回 ""（调用方自行区分「无此属性」与「属性为空」）。
     */
    fun readSystemProp(name: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        get.invoke(null, name) as? String
    } catch (e: Throwable) {
        null
    }

    /** 属性不存在或为空时返回 null 的便捷封装。 */
    private fun prop(name: String): String? =
        readSystemProp(name)?.takeIf { it.isNotBlank() }
}
