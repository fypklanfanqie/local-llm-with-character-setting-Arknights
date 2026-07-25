package com.rhodesisland.terminal.llm.backend

import android.content.Context
import android.os.Build

/**
 * NPU 支持检测
 *
 * 通过 Build.SOC_MANUFACTURER / SOC_MODEL / HARDWARE 判断是否为高通骁龙芯片，
 * 并按 SOC_MODEL 划分芯片等级。仅高通旗舰/高端/中端芯片判定为支持 NPU。
 *
 * SOC_MANUFACTURER / SOC_MODEL 为 API 31+ (Android 12) 字段，低版本直接判不支持。
 */
object NpuSupportDetector {

    data class NpuSupportInfo(
        val supported: Boolean,
        val socManufacturer: String,
        val socModel: String,
        val hardware: String,
        val chipLevel: ChipLevel,
        val reason: String,
    )

    enum class ChipLevel(val displayName: String) {
        FLAGSHIP("旗舰 (骁龙8 Gen2+)"),
        HIGH_END("高端 (骁龙8 Gen1/8+ Gen1)"),
        MID_RANGE("中端 (骁龙7/6系)"),
        UNSUPPORTED("不支持 NPU"),
    }

    fun detect(context: Context): NpuSupportInfo {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return NpuSupportInfo(
                supported = false, "", "", "", ChipLevel.UNSUPPORTED,
                "Android 版本过低，需要 Android 12 以上",
            )
        }

        val socMfr = Build.SOC_MANUFACTURER ?: ""
        val socModel = Build.SOC_MODEL ?: ""
        val hardware = Build.HARDWARE ?: ""

        val isQualcomm = socMfr.equals("Qualcomm", ignoreCase = true) ||
            hardware.contains("qcom", ignoreCase = true)

        if (!isQualcomm) {
            return NpuSupportInfo(
                false, socMfr, socModel, hardware, ChipLevel.UNSUPPORTED,
                "非高通设备 ($socMfr), NPU 后端仅支持骁龙芯片",
            )
        }

        val chipLevel = detectChipLevel(socModel, hardware)
        val supported = chipLevel != ChipLevel.UNSUPPORTED
        return NpuSupportInfo(
            supported, socMfr, socModel, hardware, chipLevel,
            if (supported) "支持 NPU: $chipLevel" else "芯片等级不足以支持 NPU 推理",
        )
    }

    private fun detectChipLevel(socModel: String, hardware: String): ChipLevel {
        // 骁龙芯片标识映射（按 SOC_MODEL）：
        //   旗舰  : SM8750(8 Gen4/Elite) SM8650(8 Gen3) SM8550(8 Gen2)
        //   高端  : SM8475(8+ Gen1) SM8450(8 Gen1) SM8350(888)
        //   中端  : SM7675/SM7635/SM7475/SM7450(7系) SM6475/SM6450(6系)
        // 注：SM8450 归入高端（与 ChipLevel.HIGH_END 的 displayName「8 Gen1/8+ Gen1」一致），
        //     故仅 SM8750/SM8650/SM8550 为旗舰。
        val flagshipModels = listOf("SM8750", "SM8650", "SM8550")
        val highEndModels = listOf("SM8475", "SM8450", "SM8350")
        val midRangeModels = listOf(
            "SM7675", "SM7635", "SM7475", "SM7450",
            "SM6475", "SM6450",
        )

        return when {
            flagshipModels.any { socModel.contains(it, ignoreCase = true) } -> ChipLevel.FLAGSHIP
            highEndModels.any { socModel.contains(it, ignoreCase = true) } -> ChipLevel.HIGH_END
            midRangeModels.any { socModel.contains(it, ignoreCase = true) } -> ChipLevel.MID_RANGE
            else -> {
                // 无法精确识别，按 hardware 猜测：高通平台但型号未知，按中端保守处理
                if (hardware.contains("qcom", ignoreCase = true)) ChipLevel.MID_RANGE
                else ChipLevel.UNSUPPORTED
            }
        }
    }
}
